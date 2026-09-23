package com.jdkshen.aijspro.theme;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import androidx.appcompat.app.AppCompatDelegate;

import com.stardust.app.GlobalAppContext;

import com.jdkshen.aijspro.R;

import com.stardust.theme.ThemeColor;
import com.stardust.theme.ThemeColorManager;

/**
 * Created by Stardust on 2017/3/12.
 */

public class ThemeColorManagerCompat {

    private static Context sContext;
    private static SharedPreferences.OnSharedPreferenceChangeListener sPreferenceChangeListener = (sharedPreferences, key) -> {
        if (sContext.getString(R.string.key_night_mode).equals(key)
                || "follow_system_theme".equals(key)) {
            applyThemeMode();
        }
    };

    public static int getColorPrimary() {
        int color = ThemeColorManager.getColorPrimary();
        if (color == 0) {
            return GlobalAppContext.get().getResources().getColor(R.color.colorPrimary);
        } else {
            return color;
        }
    }

    public static void setNightModeEnabled(boolean enabled) {
        PreferenceManager.getDefaultSharedPreferences(sContext).edit()
                .putBoolean(sContext.getString(R.string.key_night_mode), enabled).apply();
    }

    /** Apply the same persisted choice to native resources and Compose surfaces. */
    public static void applyThemeMode() {
        com.jdkshen.aijspro.theme.AijsMiuixThemeKt.ensureMiuixThemeStateLoaded();
        int requestedMode = com.jdkshen.aijspro.Pref.isFollowSystemThemeEnabled()
                ? AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                : com.jdkshen.aijspro.Pref.isNightModeEnabled()
                ? AppCompatDelegate.MODE_NIGHT_YES
                : AppCompatDelegate.MODE_NIGHT_NO;
        if (AppCompatDelegate.getDefaultNightMode() != requestedMode) {
            AppCompatDelegate.setDefaultNightMode(requestedMode);
        }
    }

    public static void init(Context context, ThemeColor defaultThemeColor) {
        sContext = context.getApplicationContext();
        ThemeColorManager.setDefaultThemeColor(defaultThemeColor);
        ThemeColorManager.init(sContext);
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(sContext);
        preferences.registerOnSharedPreferenceChangeListener(sPreferenceChangeListener);
        // Apply before the first Activity is created. Applying it from MainActivity.onCreate
        // caused AppCompat and MIUI to schedule several back-to-back relaunches on cold start.
        applyThemeMode();
    }
}
