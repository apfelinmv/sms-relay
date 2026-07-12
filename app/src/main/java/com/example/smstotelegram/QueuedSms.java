package com.example.smstotelegram;

import org.json.JSONException;
import org.json.JSONObject;

final class QueuedSms {
    final String id;
    final String sender;
    final String body;
    final long sentAtMillis;
    final long receivedAtMillis;
    final int attempts;
    final int simSlot;

    QueuedSms(String id, String sender, String body, long sentAtMillis,
              long receivedAtMillis, int attempts, int simSlot) {
        this.id = id;
        this.sender = sender;
        this.body = body;
        this.sentAtMillis = sentAtMillis;
        this.receivedAtMillis = receivedAtMillis;
        this.attempts = attempts;
        this.simSlot = simSlot;
    }

    QueuedSms withAttempt() {
        return new QueuedSms(id, sender, body, sentAtMillis, receivedAtMillis, attempts + 1, simSlot);
    }

    JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("id", id);
        object.put("sender", sender);
        object.put("body", body);
        object.put("sentAtMillis", sentAtMillis);
        object.put("receivedAtMillis", receivedAtMillis);
        object.put("attempts", attempts);
        object.put("simSlot", simSlot);
        return object;
    }

    static QueuedSms fromJson(JSONObject object) {
        return new QueuedSms(
                object.optString("id"),
                object.optString("sender"),
                object.optString("body"),
                object.optLong("sentAtMillis"),
                object.optLong("receivedAtMillis"),
                object.optInt("attempts"),
                object.optInt("simSlot", -1));
    }
}
