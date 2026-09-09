package com.jdkshen.aijspro.theme;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import androidx.core.content.ContextCompat;
import androidx.appcompat.app.AppCompatDelegate;

import com.stardust.app.GlobalAppContext;

import com.jdkshen.aijspro.R;

import com.stardust.theme.ThemeColor;
import com.stardust.theme.ThemeColorManager;

/**
 * Created by Stardust on 2017/3/12.
 */

public class ThemeColorManagerCompat {

    private static SharedPreferences sSharedPreferences;
    private static Context sContext;
    private static SharedPreferences.OnSharedPreferenceChangeListener sPreferenceChangeListener = (sharedPreferences, key) -> {
        if (key.equals(sContext.getString(R.string.key_night_mode))) {
            setNightModeEnabled(sharedPreferences.getBoolean(key, false));
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
        int requestedMode = enabled
                ? AppCompatDelegate.MODE_NIGHT_YES
                : AppCompatDelegate.MODE_NIGHT_NO;
        if (enabled) {
            if (AppCompatDelegate.getDefaultNightMode() != requestedMode) {
                AppCompatDelegate.setDefaultNightMode(requestedMode);
            }
            ThemeColor currentTheme = ThemeColor.fromPreferences(PreferenceManager.getDefaultSharedPreferences(sContext), null);
            if (currentTheme != null) {
                currentTheme.saveIn(sSharedPreferences);
            }
            ThemeColorManager.setThemeColor(ContextCompat.getColor(sContext, R.color.theme_color_black));
        } else {
            if (AppCompatDelegate.getDefaultNightMode() != requestedMode) {
                AppCompatDelegate.setDefaultNightMode(requestedMode);
            }
            ThemeColor previousTheme = ThemeColor.fromPreferences(sSharedPreferences, null);
            if (previousTheme != null) {
                ThemeColorManager.setThemeColor(previousTheme.colorPrimary);
            }
        }
    }

    public static void init(Context context, ThemeColor defaultThemeColor) {
        sContext = context.getApplicationContext();
        sSharedPreferences = sContext.getSharedPreferences("theme_color", Context.MODE_PRIVATE);
        ThemeColorManager.setDefaultThemeColor(defaultThemeColor);
        ThemeColorManager.init(sContext);
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(sContext);
        preferences.registerOnSharedPreferenceChangeListener(sPreferenceChangeListener);
        // Apply before the first Activity is created. Applying it from MainActivity.onCreate
        // caused AppCompat and MIUI to schedule several back-to-back relaunches on cold start.
        setNightModeEnabled(preferences.getBoolean(sContext.getString(R.string.key_night_mode), false));
    }
}
