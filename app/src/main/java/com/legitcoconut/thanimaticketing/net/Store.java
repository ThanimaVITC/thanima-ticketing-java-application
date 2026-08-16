package com.legitcoconut.thanimaticketing.net;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

/**
 * Token, server URL and the one disk cache. Encrypted where the device lets us,
 * plain app private preferences where the keystore misbehaves, because losing the
 * ability to log in is worse than storing a 7 day token unencrypted.
 */
public final class Store {

    public static final String KEY_TOKEN = "auth_token";
    public static final String KEY_SERVER_URL = "server_url";
    public static final String KEY_USER = "auth_user";

    private static SharedPreferences prefs;

    private Store() {
    }

    public static synchronized void init(Context context) {
        if (prefs != null) return;
        Context app = context.getApplicationContext();
        try {
            MasterKey key = new MasterKey.Builder(app)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            prefs = EncryptedSharedPreferences.create(
                    app,
                    "thanima_secure",
                    key,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception e) {
            Log.w("Store", "Encrypted storage unavailable, falling back", e);
            prefs = app.getSharedPreferences("thanima", Context.MODE_PRIVATE);
        }
    }

    public static String get(String key, String fallback) {
        return prefs == null ? fallback : prefs.getString(key, fallback);
    }

    public static void put(String key, String value) {
        if (prefs == null) return;
        prefs.edit().putString(key, value).apply();
    }

    public static void remove(String key) {
        if (prefs == null) return;
        prefs.edit().remove(key).apply();
    }

    /** Drops every cached response. Signing out must not leave attendee data on the device. */
    public static void clearCache() {
        if (prefs == null) return;
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith("cache:")) editor.remove(key);
        }
        editor.apply();
    }

    public static boolean has(String key) {
        String v = get(key, null);
        return v != null && !v.isEmpty();
    }
}
