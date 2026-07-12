package com.example.smstotelegram;

import android.app.job.JobParameters;
import android.app.job.JobService;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DeliveryJobService extends JobService {
    private ExecutorService executor;

    @Override
    public boolean onStartJob(JobParameters params) {
        executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> deliver(params));
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        if (executor != null) {
            executor.shutdownNow();
        }
        return true;
    }

    private void deliver(JobParameters params) {
        boolean shouldRetry = false;
        List<String> deliveredIds = new java.util.ArrayList<>();

        for (QueuedSms sms : SmsQueue.load(this)) {
            try {
                if (!RelaySender.sendQueuedSms(this, sms)) {
                    shouldRetry = true;
                } else {
                    deliveredIds.add(sms.id);
                }
            } catch (IOException e) {
                shouldRetry = true;
            }
        }

        // Remove only the snapshot entries that were delivered. SMS received while this
        // job was running stay in the queue for the next pass.
        SmsQueue.removeDelivered(this, deliveredIds);
        boolean hasPending = SmsQueue.hasPending(this);
        jobFinished(params, shouldRetry && hasPending);
    }
}
