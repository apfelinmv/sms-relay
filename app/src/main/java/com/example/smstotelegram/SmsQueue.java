package com.example.smstotelegram;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class SmsQueue {
    private static final String PREFS = "sms_queue";
    private static final String ITEMS = "items";
    private static final int MAX_ITEMS = 100;
    private static final int MAX_BODY_CHARS = 3500;

    private SmsQueue() {
    }

    static synchronized void enqueue(Context context, String sender, String body,
                                     long sentAtMillis, int simSlot) {
        String safeBody = body == null ? "" : body;
        if (safeBody.length() > MAX_BODY_CHARS) {
            safeBody = safeBody.substring(0, MAX_BODY_CHARS) + "\n...[truncated]";
        }

        List<QueuedSms> items = load(context);
        items.add(new QueuedSms(
                UUID.randomUUID().toString(),
                sender == null || sender.isEmpty() ? "unknown" : sender,
                safeBody,
                sentAtMillis,
                System.currentTimeMillis(),
                0,
                simSlot));

        while (items.size() > MAX_ITEMS) {
            items.remove(0);
        }
        saveNow(context, items);
    }

    static synchronized List<QueuedSms> load(Context context) {
        ArrayList<QueuedSms> result = new ArrayList<>();
        String raw = prefs(context).getString(ITEMS, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                result.add(QueuedSms.fromJson(array.getJSONObject(i)));
            }
        } catch (JSONException ignored) {
            prefs(context).edit().remove(ITEMS).apply();
        }
        return result;
    }

    static synchronized void removeDelivered(Context context, List<String> deliveredIds) {
        if (deliveredIds.isEmpty()) {
            return;
        }

        Set<String> ids = new HashSet<>(deliveredIds);
        List<QueuedSms> current = load(context);
        List<QueuedSms> remaining = new ArrayList<>();
        for (QueuedSms item : current) {
            if (!ids.contains(item.id)) {
                remaining.add(item);
            }
        }
        saveNow(context, remaining);
    }

    @SuppressLint("ApplySharedPref")
    private static void saveNow(Context context, List<QueuedSms> items) {
        JSONArray array = new JSONArray();
        for (QueuedSms item : items) {
            try {
                array.put(item.toJson());
            } catch (JSONException ignored) {
                // Skip a malformed item instead of blocking delivery of the rest.
            }
        }
        prefs(context).edit().putString(ITEMS, array.toString()).commit();
    }

    static synchronized boolean hasPending(Context context) {
        return !load(context).isEmpty();
    }

    static synchronized int count(Context context) {
        return load(context).size();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
