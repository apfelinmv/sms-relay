package com.example.smstotelegram;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.os.PersistableBundle;

final class DeliveryScheduler {
    static final int JOB_ID = 30230;
    private static final String EXTRA_REASON = "reason";

    private DeliveryScheduler() {
    }

    static void schedule(Context context, String reason) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) {
            return;
        }

        PersistableBundle extras = new PersistableBundle();
        extras.putString(EXTRA_REASON, reason);

        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, DeliveryJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .setPersisted(true)
                .setExtras(extras)
                .build();
        scheduler.schedule(job);
    }
}
