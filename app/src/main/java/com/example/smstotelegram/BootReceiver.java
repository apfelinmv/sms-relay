package com.example.smstotelegram;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        RelayKeepAliveService.start(context);
        if (SmsQueue.hasPending(context)) {
            DeliveryScheduler.schedule(context, "boot");
        }
    }
}
