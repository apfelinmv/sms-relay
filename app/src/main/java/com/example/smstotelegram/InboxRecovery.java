package com.example.smstotelegram;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Build;
import android.provider.Telephony;
import android.telephony.SubscriptionManager;

final class InboxRecovery {
    private static final String PREFS = "inbox_recovery";
    private static final String LAST_ID = "last_id";

    private InboxRecovery() {
    }

    static synchronized int recoverRecent(Context context, long initialMaxAgeMillis) {
        if (context.checkSelfPermission(Manifest.permission.READ_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            return 0;
        }

        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean initialized = preferences.contains(LAST_ID);
        long lastId = preferences.getLong(LAST_ID, -1L);
        long newestId = initialized ? lastId : latestInboxId(context);
        String selection = initialized
                ? Telephony.Sms._ID + " > ?"
                : Telephony.Sms.DATE + " >= ?";
        String[] selectionArgs = {
                Long.toString(initialized
                        ? lastId
                        : System.currentTimeMillis() - initialMaxAgeMillis)
        };
        String[] projection = {
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.SUBSCRIPTION_ID
        };
        int recovered = 0;
        try (Cursor cursor = context.getContentResolver().query(
                Telephony.Sms.Inbox.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                Telephony.Sms._ID + " ASC")) {
            if (cursor == null) {
                return 0;
            }
            int idColumn = cursor.getColumnIndexOrThrow(Telephony.Sms._ID);
            int senderColumn = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS);
            int bodyColumn = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY);
            int dateColumn = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE);
            int subscriptionColumn = cursor.getColumnIndexOrThrow(
                    Telephony.Sms.SUBSCRIPTION_ID);
            boolean unresolvedRowSeen = false;
            while (cursor.moveToNext()) {
                long id = cursor.getLong(idColumn);
                int subscriptionId = cursor.getInt(subscriptionColumn);
                int slot = simSlot(context, subscriptionId);
                if (slot != 0 && slot != 1) {
                    unresolvedRowSeen = true;
                    continue;
                }
                if (SmsQueue.enqueueInbox(
                        context,
                        id,
                        cursor.getString(senderColumn),
                        cursor.getString(bodyColumn),
                        cursor.getLong(dateColumn),
                        slot)) {
                    recovered++;
                }
                if (!unresolvedRowSeen) {
                    newestId = Math.max(newestId, id);
                }
            }
            if (newestId >= 0L && (!initialized || newestId > lastId)) {
                preferences.edit().putLong(LAST_ID, newestId).commit();
            }
        } catch (RuntimeException ignored) {
            return recovered;
        }
        return recovered;
    }

    private static long latestInboxId(Context context) {
        try (Cursor cursor = context.getContentResolver().query(
                Telephony.Sms.Inbox.CONTENT_URI,
                new String[]{Telephony.Sms._ID},
                null,
                null,
                Telephony.Sms._ID + " DESC")) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getLong(0);
            }
        } catch (RuntimeException ignored) {
            // The provider can be unavailable briefly while the phone is booting.
        }
        return -1L;
    }

    private static int simSlot(Context context, int subscriptionId) {
        int slot = -1;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            slot = SubscriptionManager.getSlotIndex(subscriptionId);
            if (slot == 0 || slot == 1) {
                return slot;
            }
        }
        boolean simOneConfigured = !SettingsStore.simOneWhitelist(context).isEmpty();
        boolean simTwoConfigured = !SettingsStore.simTwoWhitelist(context).isEmpty();
        if (simOneConfigured != simTwoConfigured) {
            return simOneConfigured ? 0 : 1;
        }
        return -1;
    }
}
