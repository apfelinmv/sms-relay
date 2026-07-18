package com.example.smstotelegram;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Telephony;

public final class RelayKeepAliveService extends Service {
    private static final String CHANNEL_ID = "sms_relay_status";
    private static final int NOTIFICATION_ID = 30231;
    private static final long INBOX_SETTLE_DELAY_MILLIS = 750L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean inboxObserverRegistered;
    private final Runnable recoverInbox = () -> {
        int recovered = InboxRecovery.recoverRecent(this, 5 * 60_000L);
        if (recovered > 0 || SmsQueue.hasPending(this)) {
            DeliveryScheduler.schedule(this, "inbox-change");
        }
    };
    private final ContentObserver inboxObserver = new ContentObserver(mainHandler) {
        @Override
        public void onChange(boolean selfChange) {
            mainHandler.removeCallbacks(recoverInbox);
            mainHandler.postDelayed(recoverInbox, INBOX_SETTLE_DELAY_MILLIS);
        }
    };

    static void start(Context context) {
        if (!SettingsStore.isConfigured(context)) {
            return;
        }
        Intent intent = new Intent(context, RelayKeepAliveService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (IllegalStateException | SecurityException ignored) {
            // Boot restrictions vary by firmware. Opening the app starts it again.
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, notification());
        ensureInboxObserver();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ensureInboxObserver();
        if (SmsQueue.hasPending(this)) {
            DeliveryScheduler.schedule(this, "keep-alive");
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        mainHandler.removeCallbacks(recoverInbox);
        if (inboxObserverRegistered) {
            getContentResolver().unregisterContentObserver(inboxObserver);
            inboxObserverRegistered = false;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    static boolean isRunning(Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) {
            return false;
        }
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (RelayKeepAliveService.class.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.relay_active_text));
        channel.setShowBadge(false);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private void ensureInboxObserver() {
        if (inboxObserverRegistered
                || checkSelfPermission(Manifest.permission.READ_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            getContentResolver().registerContentObserver(
                    Telephony.Sms.CONTENT_URI, true, inboxObserver);
            inboxObserverRegistered = true;
        } catch (RuntimeException ignored) {
            // Some vendor providers can be temporarily unavailable during boot.
        }
    }

    private Notification notification() {
        Intent openApp = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                0,
                openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(getString(R.string.relay_active_title))
                .setContentText(getString(R.string.relay_active_text))
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }
}
