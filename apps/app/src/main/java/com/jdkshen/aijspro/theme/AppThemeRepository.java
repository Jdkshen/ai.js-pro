package com.jdkshen.aijspro.theme;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;

import com.stardust.theme.ThemeColor;
import com.stardust.theme.ThemeColorManager;

import java.util.List;
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

    private AppThemeRepository(Context context) {
        mAppContext = context.getApplicationContext();
        mPrefs = PreferenceManager.getDefaultSharedPreferences(mAppContext);
        mCurrentPalette = buildPalette();
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
        mCurrentPalette = buildPalette();
        notifyListeners();
    }

    /** Rebuild a follow-system palette after a configuration (night mode) change. */
    public void refreshSystemAppearance() {
        if (getThemeMode() != 0) return;
        mCurrentPalette = buildPalette();
        notifyListeners();
    }

    /** Switch display mode: 0=follow system, 1=light, 2=dark. */
    public void setThemeMode(int mode) {
        mPrefs.edit().putInt(KEY_THEME_MODE, mode).apply();
        // Also sync with old night mode preference for backward compat
        boolean night = (mode == 2) || (mode == 0 && isSystemDark());
        mPrefs.edit().putBoolean(KEY_NIGHT_MODE, night).apply();
        mCurrentPalette = buildPalette();
        notifyListeners();
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
                .apply();
        mCurrentPalette = buildPalette();
        notifyListeners();
    }

    /** Get the current theme mode. */
    public int getThemeMode() {
        return mPrefs.getInt(KEY_THEME_MODE, 0);
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
        final AppThemePalette palette = mCurrentPalette;
        mMainHandler.post(() -> {
            for (ThemeListener l : mListeners) {
                l.onThemeChanged(palette);
            }
        });
    }
}
