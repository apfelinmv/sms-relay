package com.example.smstotelegram;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

final class SettingsStore {
    private static final String PREFS = "settings";
    private static final String SERVER_URL = "server_url";
    private static final String BOT_TOKEN = "bot_token";
    private static final String SIM_ONE_WHITELIST = "sim_one_whitelist";
    private static final String SIM_TWO_WHITELIST = "sim_two_whitelist";
    private static final String CERT_PIN = "cert_pin";

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

    static void save(Context context, String serverUrl, String botToken,
                     String simOneWhitelist, String simTwoWhitelist, String certPin) {
        prefs(context).edit()
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
}
