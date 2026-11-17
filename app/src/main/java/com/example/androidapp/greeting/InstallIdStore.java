package com.example.androidapp.greeting;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;

//Random per-install ID sent to the backend as X-Gifty-Install-Id so it can rate limit per device.
//It is not tied to the user account or any personal data, and it lives in its own preferences
//file so the existing "GiftyPrefs" session data is untouched.
public final class InstallIdStore {

    private static final String PREFS_NAME = "GiftyAiPrefs";
    private static final String KEY_INSTALL_ID = "INSTALL_ID";

    private InstallIdStore() { }

    public static synchronized String get(Context context) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String id = prefs.getString(KEY_INSTALL_ID, null);
        if (id == null || id.isEmpty()) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString(KEY_INSTALL_ID, id).apply();
        }
        return id;
    }
}
