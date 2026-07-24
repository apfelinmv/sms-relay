package com.example.smstotelegram;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import android.telephony.SubscriptionManager;

import java.util.LinkedHashMap;
import java.util.Map;

public final class SmsReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) {
            return;
        }

        RelayKeepAliveService.startForIncomingSms(context);
        if (context.checkSelfPermission(Manifest.permission.READ_SMS)
                == PackageManager.PERMISSION_GRANTED) {
            boolean enqueued = InboxRecovery.recoverRecent(context, 2 * 60_000L) > 0;
            if (enqueued || SmsQueue.hasPending(context)) {
                DeliveryScheduler.schedule(context, "sms-inbox");
            }
            return;
        }

        // Without READ_SMS, the protected broadcast is the only available source.
        SmsMessage[] messages = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        int simSlot = simSlot(intent);

        Map<String, StringBuilder> bodiesBySender = new LinkedHashMap<>();
        Map<String, Long> timesBySender = new LinkedHashMap<>();
        if (messages != null) {
            for (SmsMessage message : messages) {
                if (message == null) {
                    continue;
                }
                String sender = message.getDisplayOriginatingAddress();
                String key = sender == null ? "unknown" : sender;
                String body = message.getMessageBody();
                if (!bodiesBySender.containsKey(key)) {
                    bodiesBySender.put(key, new StringBuilder());
                    timesBySender.put(key, message.getTimestampMillis());
                }
                if (body != null) {
                    bodiesBySender.get(key).append(body);
                }
            }
        }

        boolean enqueued = false;
        if (simSlot == 0 || simSlot == 1) {
            for (Map.Entry<String, StringBuilder> entry : bodiesBySender.entrySet()) {
                enqueued |= SmsQueue.enqueue(context, entry.getKey(), entry.getValue().toString(),
                        timesBySender.get(entry.getKey()), simSlot);
            }
        }
        if (enqueued || SmsQueue.hasPending(context)) {
            DeliveryScheduler.schedule(context, "sms-broadcast");
        }
    }

    private int simSlot(Intent intent) {
        int slot = intent.getIntExtra("android.telephony.extra.SLOT_INDEX", -1);
        if (slot < 0) {
            slot = intent.getIntExtra("slot", -1);
        }
        if (slot < 0) {
            slot = intent.getIntExtra("phone", -1);
        }
        if (slot < 0) {
            int subscriptionId = intent.getIntExtra(
                    "android.telephony.extra.SUBSCRIPTION_INDEX", -1);
            if (subscriptionId < 0) {
                subscriptionId = intent.getIntExtra("subscription", -1);
            }
            if (subscriptionId >= 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                slot = SubscriptionManager.getSlotIndex(subscriptionId);
            }
        }
        return slot == 0 || slot == 1 ? slot : -1;
    }
}
