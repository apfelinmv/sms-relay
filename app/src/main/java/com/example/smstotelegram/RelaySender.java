package com.example.smstotelegram;

import android.annotation.SuppressLint;
import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Locale;
import java.util.UUID;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;

final class RelaySender {
    private RelaySender() {
    }

    static boolean configure(Context context) throws IOException {
        JSONObject payload = new JSONObject();
        try {
            JSONObject routes = new JSONObject();
            routes.put("0", phoneArray(SettingsStore.simOneWhitelist(context)));
            routes.put("1", phoneArray(SettingsStore.simTwoWhitelist(context)));
            payload.put("routes", routes);
            payload.put("device_id", SettingsStore.deviceId(context));
            payload.put("device_name", SettingsStore.deviceName(context));
            payload.put("claim_legacy", SettingsStore.shouldClaimLegacyRoutes(context));
        } catch (JSONException e) {
            throw new IOException("Cannot encode configuration", e);
        }
        boolean configured = post(context, "/configure", payload);
        if (configured) {
            SettingsStore.markLegacyRoutesClaimed(context);
        }
        return configured;
    }

    static boolean sendQueuedSms(Context context, QueuedSms sms) throws IOException {
        JSONObject payload = new JSONObject();
        try {
            payload.put("id", sms.id);
            payload.put("sender", sms.sender);
            payload.put("body", sms.body);
            payload.put("sent_at", sms.sentAtMillis);
            payload.put("sim_slot", sms.simSlot);
            payload.put("device_id", SettingsStore.deviceId(context));
        } catch (JSONException e) {
            throw new IOException("Cannot encode SMS", e);
        }
        return post(context, "/sms", payload);
    }

    static boolean sendTest(Context context, int simSlot) throws IOException {
        long now = System.currentTimeMillis();
        return sendQueuedSms(context, new QueuedSms(
                UUID.randomUUID().toString(),
                "SMS Relay",
                "Test message from Android. The relay is working.",
                now,
                now,
                0,
                simSlot));
    }

    private static JSONArray phoneArray(String values) {
        JSONArray result = new JSONArray();
        for (String value : values.split("[,;\\n\\s]+")) {
            if (!value.trim().isEmpty()) {
                result.put(value.trim());
            }
        }
        return result;
    }

    private static boolean post(Context context, String path, JSONObject payload) throws IOException {
        if (!SettingsStore.isConfigured(context)) {
            return false;
        }
        URL url = new URL(SettingsStore.serverUrl(context) + path);
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IOException("HTTPS is required");
        }

        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        try {
            String pin = SettingsStore.certPin(context);
            if (!pin.isEmpty()) {
                connection.setSSLSocketFactory(pinnedSslContext(pin).getSocketFactory());
            }
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(15_000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty(
                    "Authorization", "Bearer " + SettingsStore.botToken(context));
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

            byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (BufferedOutputStream output = new BufferedOutputStream(connection.getOutputStream())) {
                output.write(bytes);
            }

            int code = connection.getResponseCode();
            InputStream response = code >= 200 && code < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            if (response != null) {
                consume(response);
            }
            return code >= 200 && code < 300;
        } finally {
            connection.disconnect();
        }
    }

    @SuppressLint("CustomX509TrustManager")
    private static SSLContext pinnedSslContext(String expectedPin) throws IOException {
        try {
            X509TrustManager trustManager = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType)
                        throws CertificateException {
                    if (chain == null || chain.length == 0) {
                        throw new CertificateException("Missing server certificate");
                    }
                    try {
                        byte[] digest = MessageDigest.getInstance("SHA-256")
                                .digest(chain[0].getEncoded());
                        if (!constantTimeEquals(toHex(digest), expectedPin)) {
                            throw new CertificateException("Server certificate pin mismatch");
                        }
                    } catch (GeneralSecurityException e) {
                        throw new CertificateException(e);
                    }
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            };
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new X509TrustManager[]{trustManager}, null);
            return context;
        } catch (GeneralSecurityException e) {
            throw new IOException("Cannot initialize TLS", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.US, "%02X", value));
        }
        return result.toString();
    }

    private static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }

    private static void consume(InputStream stream) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            while (reader.readLine() != null) {
                // Drain the short response before closing the connection.
            }
        }
    }
}
