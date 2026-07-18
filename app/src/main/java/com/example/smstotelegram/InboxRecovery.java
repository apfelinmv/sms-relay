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

    static int recoverRecent(Context context, long maxAgeMillis) {
        if (context.checkSelfPermission(Manifest.permission.READ_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            return 0;
        }

        long cutoff = System.currentTimeMillis() - maxAgeMillis;
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long lastId = preferences.getLong(LAST_ID, -1L);
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
                Telephony.Sms.DATE + " >= ? AND " + Telephony.Sms._ID + " > ?",
                new String[]{Long.toString(cutoff), Long.toString(lastId)},
                Telephony.Sms.DATE + " ASC")) {
            if (cursor == null) {
                return 0;
            }
            int idColumn = cursor.getColumnIndexOrThrow(Telephony.Sms._ID);
            int senderColumn = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS);
            int bodyColumn = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY);
            int dateColumn = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE);
            int subscriptionColumn = cursor.getColumnIndexOrThrow(
                    Telephony.Sms.SUBSCRIPTION_ID);
            long newestId = lastId;
            while (cursor.moveToNext()) {
                long id = cursor.getLong(idColumn);
                int subscriptionId = cursor.getInt(subscriptionColumn);
                int slot = simSlot(context, subscriptionId);
                if (slot != 0 && slot != 1) {
                    continue;
                }
                if (SmsQueue.enqueue(
                        context,
                        cursor.getString(senderColumn),
                        cursor.getString(bodyColumn),
                        cursor.getLong(dateColumn),
                        slot)) {
                    recovered++;
                }
                newestId = Math.max(newestId, id);
            }
            if (newestId > lastId) {
                preferences.edit().putLong(LAST_ID, newestId).commit();
            }
        } catch (RuntimeException ignored) {
            return recovered;
        }
        return recovered;
    }

    private static int simSlot(Context context, int subscriptionId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return SubscriptionManager.getSlotIndex(subscriptionId);
        }
        boolean simOneConfigured = !SettingsStore.simOneWhitelist(context).isEmpty();
        boolean simTwoConfigured = !SettingsStore.simTwoWhitelist(context).isEmpty();
        if (simOneConfigured != simTwoConfigured) {
            return simOneConfigured ? 0 : 1;
        }
        return -1;
    }
}
