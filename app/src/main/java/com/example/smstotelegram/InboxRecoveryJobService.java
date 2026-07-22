package com.example.smstotelegram;

import android.app.job.JobParameters;
import android.app.job.JobService;

public final class InboxRecoveryJobService extends JobService {
    private volatile boolean stopped;
    private volatile Thread worker;

    @Override
    public boolean onStartJob(JobParameters params) {
        stopped = false;
        worker = new Thread(() -> recover(params), "sms-inbox-recovery");
        worker.start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        stopped = true;
        if (worker != null) {
            worker.interrupt();
        }
        return true;
    }

    private void recover(JobParameters params) {
        int recovered = InboxRecovery.recoverRecent(this, 15 * 60_000L);
        if (recovered > 0 || SmsQueue.hasPending(this)) {
            DeliveryScheduler.schedule(this, "periodic-inbox-recovery");
        }
        if (!stopped && worker == Thread.currentThread()) {
            jobFinished(params, false);
        }
    }
}
