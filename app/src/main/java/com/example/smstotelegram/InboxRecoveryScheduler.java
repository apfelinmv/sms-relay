package com.example.smstotelegram;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;

final class InboxRecoveryScheduler {
    static final int JOB_ID = 30232;
    private static final long INTERVAL_MILLIS = 15 * 60_000L;

    private InboxRecoveryScheduler() {
    }

    static void schedule(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null || isScheduled(scheduler)) {
            return;
        }

        JobInfo job = new JobInfo.Builder(
                JOB_ID,
                new ComponentName(context, InboxRecoveryJobService.class))
                .setPeriodic(INTERVAL_MILLIS)
                .setPersisted(true)
                .build();
        scheduler.schedule(job);
    }

    private static boolean isScheduled(JobScheduler scheduler) {
        for (JobInfo job : scheduler.getAllPendingJobs()) {
            if (job.getId() == JOB_ID) {
                return true;
            }
        }
        return false;
    }
}
