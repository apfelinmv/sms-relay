package com.example.smstotelegram;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.UUID;

final class SettingsStore {
    private static final String PREFS = "settings";
    private static final String SERVER_URL = "server_url";
    private static final String BOT_TOKEN = "bot_token";
    private static final String SIM_ONE_WHITELIST = "sim_one_whitelist";
    private static final String SIM_TWO_WHITELIST = "sim_two_whitelist";
    private static final String CERT_PIN = "cert_pin";
    private static final String DEVICE_ID = "device_id";
    private static final String DEVICE_NAME = "device_name";
    private static final String CLAIM_LEGACY = "claim_legacy";

    private SettingsStore() {
    }

    static String serverUrl(Context context) {
        return prefs(context).getString(SERVER_URL, BuildConfig.RELAY_DEFAULT_URL);
    }

    static String botToken(Context context) {
        return prefs(context).getString(BOT_TOKEN, "");
    }

    static String simOneWhitelist(Context context) {
        return prefs(context).getString(SIM_ONE_WHITELIST, "");
    }

    static String simTwoWhitelist(Context context) {
        return prefs(context).getString(SIM_TWO_WHITELIST, "");
    }

    static String certPin(Context context) {
        return prefs(context).getString(CERT_PIN, BuildConfig.RELAY_DEFAULT_PIN);
    }

    static String deviceId(Context context) {
        ensureDeviceIdentity(context);
        return prefs(context).getString(DEVICE_ID, "");
    }

    static String deviceName(Context context) {
        ensureDeviceIdentity(context);
        return prefs(context).getString(DEVICE_NAME, defaultDeviceName());
    }

    static boolean shouldClaimLegacyRoutes(Context context) {
        ensureDeviceIdentity(context);
        return prefs(context).getBoolean(CLAIM_LEGACY, false);
    }

    static void markLegacyRoutesClaimed(Context context) {
        prefs(context).edit().putBoolean(CLAIM_LEGACY, false).apply();
    }

    static void save(Context context, String deviceName, String serverUrl, String botToken,
                     String simOneWhitelist, String simTwoWhitelist, String certPin) {
        ensureDeviceIdentity(context);
        String safeDeviceName = deviceName.trim();
        if (safeDeviceName.isEmpty()) {
            safeDeviceName = defaultDeviceName();
        }
        prefs(context).edit()
                .putString(DEVICE_NAME, safeDeviceName)
                .putString(SERVER_URL, trimTrailingSlash(serverUrl.trim()))
                .putString(BOT_TOKEN, botToken.trim())
                .putString(SIM_ONE_WHITELIST, simOneWhitelist.trim())
                .putString(SIM_TWO_WHITELIST, simTwoWhitelist.trim())
                .putString(CERT_PIN, certPin.replaceAll("[^0-9A-Fa-f]", "")
                        .toUpperCase(Locale.ROOT))
                .apply();
    }

    static boolean isConfigured(Context context) {
        return serverUrl(context).startsWith("https://")
                && !botToken(context).isEmpty()
                && (!simOneWhitelist(context).isEmpty() || !simTwoWhitelist(context).isEmpty());
    }

    private static String trimTrailingSlash(String value) {
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static synchronized void ensureDeviceIdentity(Context context) {
        SharedPreferences preferences = prefs(context);
        if (preferences.contains(DEVICE_ID)) {
            return;
        }
        boolean hasLegacyConfiguration = preferences.contains(BOT_TOKEN)
                && !preferences.getString(BOT_TOKEN, "").isEmpty()
                && preferences.contains(SERVER_URL);
        preferences.edit()
                .putString(DEVICE_ID, stableDeviceId(context))
                .putString(DEVICE_NAME, defaultDeviceName())
                .putBoolean(CLAIM_LEGACY, hasLegacyConfiguration)
                .commit();
    }

    private static String stableDeviceId(Context context) {
        String androidId = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (androidId == null || androidId.isEmpty()) {
            return "random-" + UUID.randomUUID();
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    ("sms-relay:" + androidId).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder("android-");
            for (int i = 0; i < 16; i++) {
                result.append(String.format(Locale.US, "%02x", digest[i]));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException ignored) {
            return "android-" + androidId;
        }
    }

    private static String defaultDeviceName() {
        String manufacturer = Build.MANUFACTURER == null ? "Android" : Build.MANUFACTURER;
        String model = Build.MODEL == null ? "phone" : Build.MODEL;
        return (manufacturer + " " + model).trim();
    }
}
