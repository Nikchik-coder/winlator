package com.winlator.core;

import android.content.Context;

import androidx.preference.PreferenceManager;

public class AppLock {
    private static final String KEY_IS_UNLOCKED = "is_unlocked";
    private static final String KEY_SAVED_CODE = "saved_code";

    public static boolean isUnlocked(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY_IS_UNLOCKED, false);
    }

    public static void saveUnlocked(Context context, String code) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putBoolean(KEY_IS_UNLOCKED, true)
            .putString(KEY_SAVED_CODE, code)
            .apply();
    }
}

