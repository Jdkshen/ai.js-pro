package com.jdkshen.aijspro.theme;

import android.content.Context;
import android.content.ComponentCallbacks;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;

import com.stardust.theme.ThemeColor;
import com.stardust.theme.ThemeColorManager;

import java.util.List;
import java.util.Arrays;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Single source of truth for the app theme state.
 * Produces immutable {@link AppThemePalette} and broadcasts changes to listeners.
 */
public final class AppThemeRepository {

    public interface ThemeListener {
        void onThemeChanged(AppThemePalette palette);
    }

    private static volatile AppThemeRepository sInstance;

    private final Context mAppContext;
    private final SharedPreferences mPrefs;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final List<ThemeListener> mListeners = new CopyOnWriteArrayList<>();

    private static final String KEY_NIGHT_MODE = "key_night_mode";
    private static final String KEY_ACCENT_COLOR = "app_theme_accent_color";
    private static final String KEY_THEME_MODE = "app_theme_mode"; // 0=follow, 1=light, 2=dark
    private static final int DEFAULT_ACCENT = 0xFF009688; // teal

    private volatile AppThemePalette mCurrentPalette;
    private final SharedPreferences.OnSharedPreferenceChangeListener mPreferenceListener = (prefs, key) -> {
        if (KEY_NIGHT_MODE.equals(key) || "follow_system_theme".equals(key)
                || KEY_ACCENT_COLOR.equals(key)) refreshFromLegacyTheme();
    };
    private final Runnable mNotifyListeners = () -> {
        AppThemePalette palette = mCurrentPalette;
        for (ThemeListener listener : mListeners) listener.onThemeChanged(palette);
    };

    private AppThemeRepository(Context context) {
        mAppContext = context.getApplicationContext();
        mPrefs = PreferenceManager.getDefaultSharedPreferences(mAppContext);
        mCurrentPalette = buildPalette();
        mPrefs.registerOnSharedPreferenceChangeListener(mPreferenceListener);
        mAppContext.registerComponentCallbacks(new ComponentCallbacks() {
            @Override public void onConfigurationChanged(Configuration configuration) {
                refreshSystemAppearance();
            }
            @Override public void onLowMemory() { }
        });
    }

    public static AppThemeRepository get(Context context) {
        if (sInstance == null) {
            synchronized (AppThemeRepository.class) {
                if (sInstance == null) {
                    sInstance = new AppThemeRepository(context);
                }
            }
        }
        return sInstance;
    }

    /** Current palette — safe to call from any thread. */
    public AppThemePalette getPalette() {
        return mCurrentPalette;
    }

    /** Register a listener. Will be called on main thread. */
    public void addListener(ThemeListener listener) {
        if (!mListeners.contains(listener)) {
            mListeners.add(listener);
        }
    }

    public void removeListener(ThemeListener listener) {
        mListeners.remove(listener);
    }

    /** Call when the user picks a new accent color (from ThemeColorManager). */
    public void refreshFromLegacyTheme() {
        AppThemePalette palette = buildPalette();
        if (Arrays.equals(mCurrentPalette.toIntArray(), palette.toIntArray())) return;
        mCurrentPalette = palette;
        notifyListeners();
    }

    /** Rebuild a follow-system palette after a configuration (night mode) change. */
    public void refreshSystemAppearance() {
        if (getThemeMode() != 0) return;
        refreshFromLegacyTheme();
    }

    /** Switch display mode: 0=follow system, 1=light, 2=dark. */
    public void setThemeMode(int mode) {
        if (mode < 0 || mode > 2) throw new IllegalArgumentException("Unknown theme mode: " + mode);
        SharedPreferences.Editor editor = mPrefs.edit()
                .putInt(KEY_THEME_MODE, mode)
                .putBoolean("follow_system_theme", mode == 0);
        // Preserve the manual choice when enabling follow-system.
        if (mode != 0) editor.putBoolean(KEY_NIGHT_MODE, mode == 2);
        editor.apply();
    }

    /** Set accent color and persist. */
    public void setAccentColor(int color) {
        mPrefs.edit().putInt(KEY_ACCENT_COLOR, color).apply();
        mCurrentPalette = buildPalette();
        notifyListeners();
    }

    /** Reset to default theme. */
    public void resetToDefault() {
        mPrefs.edit()
                .remove(KEY_THEME_MODE)
                .remove(KEY_ACCENT_COLOR)
                .putBoolean("follow_system_theme", true)
                .apply();
        mCurrentPalette = buildPalette();
        notifyListeners();
    }

    /** Get the current theme mode. */
    public int getThemeMode() {
        if (mPrefs.getBoolean("follow_system_theme", false)) return 0;
        return mPrefs.getBoolean(KEY_NIGHT_MODE, false) ? 2 : 1;
    }

    /** Get the current accent color. */
    public int getAccentColor() {
        if (mPrefs.contains(KEY_ACCENT_COLOR)) {
            return mPrefs.getInt(KEY_ACCENT_COLOR, DEFAULT_ACCENT);
        }
        // Fall back to legacy ThemeColorManager accent
        try {
            ThemeColor tc = ThemeColorManager.getThemeColor();
            if (tc != null && tc.colorAccent != 0) {
                return tc.colorAccent;
            }
        } catch (Throwable ignored) {
        }
        return DEFAULT_ACCENT;
    }

    public boolean isDark() {
        return mCurrentPalette.isDark;
    }

    // ---- Internal ----

    private AppThemePalette buildPalette() {
        int accent = getAccentColor();
        int mode = getThemeMode();
        boolean dark;
        switch (mode) {
            case 1: dark = false; break;
            case 2: dark = true; break;
            default: dark = isSystemDark(); break;
        }
        return dark ? AppThemePalette.dark(accent) : AppThemePalette.light(accent);
    }

    private boolean isSystemDark() {
        int nightMode = mAppContext.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        if (nightMode != Configuration.UI_MODE_NIGHT_UNDEFINED) {
            return nightMode == Configuration.UI_MODE_NIGHT_YES;
        }
        // Legacy night mode preference
        return mPrefs.getBoolean(KEY_NIGHT_MODE, false);
    }

    private void notifyListeners() {
        // An atomic mode change can notify for two keys; repaint only the final palette.
        mMainHandler.removeCallbacks(mNotifyListeners);
        mMainHandler.post(mNotifyListeners);
    }
}
