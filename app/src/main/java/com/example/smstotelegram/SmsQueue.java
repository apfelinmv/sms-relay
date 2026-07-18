package com.example.smstotelegram;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class SmsQueue {
    private static final String PREFS = "sms_queue";
    private static final String ITEMS = "items";
    private static final String RECENT = "recent";
    private static final int MAX_ITEMS = 100;
    private static final int MAX_RECENT = 50;
    private static final int MAX_BODY_CHARS = 3500;
    private static final long DUPLICATE_WINDOW_MILLIS = 15_000L;

    private SmsQueue() {
    }

    static synchronized boolean enqueue(Context context, String sender, String body,
                                        long sentAtMillis, int simSlot) {
        String safeBody = body == null ? "" : body;
        if (safeBody.length() > MAX_BODY_CHARS) {
            safeBody = safeBody.substring(0, MAX_BODY_CHARS) + "\n...[truncated]";
        }

        String safeSender = sender == null || sender.isEmpty() ? "unknown" : sender;
        String signatureIdentity = SettingsStore.deviceId(context) + "\n"
                + safeSender + "\n" + safeBody + "\n" + simSlot;
        String signature = UUID.nameUUIDFromBytes(
                signatureIdentity.getBytes(StandardCharsets.UTF_8)).toString();
        long now = System.currentTimeMillis();
        if (wasRecentlyEnqueued(context, signature, now)) {
            return false;
        }
        String identity = SettingsStore.deviceId(context) + "\n"
                + safeSender + "\n" + safeBody + "\n" + sentAtMillis + "\n" + simSlot;
        String id = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();

        List<QueuedSms> items = load(context);
        for (QueuedSms item : items) {
            if (id.equals(item.id)) {
                return false;
            }
        }
        items.add(new QueuedSms(
                id,
                safeSender,
                safeBody,
                sentAtMillis,
                System.currentTimeMillis(),
                0,
                simSlot));

        while (items.size() > MAX_ITEMS) {
            items.remove(0);
        }
        saveNow(context, items);
        rememberEnqueued(context, signature, now);
        return true;
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

    private static boolean wasRecentlyEnqueued(Context context, String signature, long now) {
        JSONArray recent = recent(context);
        for (int i = 0; i < recent.length(); i++) {
            JSONObject item = recent.optJSONObject(i);
            long age = item == null ? Long.MAX_VALUE : now - item.optLong("seen_at");
            if (item != null
                    && signature.equals(item.optString("signature"))
                    && age >= 0
                    && age <= DUPLICATE_WINDOW_MILLIS) {
                return true;
            }
        }
        return false;
    }

    @SuppressLint("ApplySharedPref")
    private static void rememberEnqueued(Context context, String signature, long now) {
        JSONArray current = recent(context);
        JSONArray updated = new JSONArray();
        int start = Math.max(0, current.length() - MAX_RECENT + 1);
        for (int i = start; i < current.length(); i++) {
            JSONObject item = current.optJSONObject(i);
            long age = item == null ? Long.MAX_VALUE : now - item.optLong("seen_at");
            if (item != null
                    && age >= 0
                    && age <= DUPLICATE_WINDOW_MILLIS) {
                updated.put(item);
            }
        }
        JSONObject item = new JSONObject();
        try {
            item.put("signature", signature);
            item.put("seen_at", now);
            updated.put(item);
            prefs(context).edit().putString(RECENT, updated.toString()).commit();
        } catch (JSONException ignored) {
            // The values above are JSON-safe primitives.
        }
    }

    private static JSONArray recent(Context context) {
        try {
            return new JSONArray(prefs(context).getString(RECENT, "[]"));
        } catch (JSONException ignored) {
            return new JSONArray();
        }
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
