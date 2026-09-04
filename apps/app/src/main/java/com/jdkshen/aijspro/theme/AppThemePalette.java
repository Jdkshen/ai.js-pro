package com.jdkshen.aijspro.theme;

import android.graphics.Color;

/**
 * Immutable semantic color palette for the entire app.
 * Every UI component reads colors from here — no hardcoded values allowed.
 *
 * Java uses ARGB int. C++ receives the same int array via JNI in field order.
 * The ordinal of each field must stay stable: add new fields at the END only.
 */
public final class AppThemePalette {

    // ---- Palette field order (JNI contract) — DO NOT reorder ----
    public final boolean isDark;
    public final int windowBackground;
    public final int surfacePrimary;
    public final int surfaceSecondary;
    public final int surfaceElevated;
    public final int toolbarBackground;
    public final int rowBackground;
    public final int rowPressed;
    public final int popupBackground;
    public final int scrim;
    public final int divider;
    public final int textPrimary;
    public final int textSecondary;
    public final int textDisabled;
    public final int iconPrimary;
    public final int accent;
    public final int accentPressed;
    public final int accentMuted;
    public final int danger;
    public final int statusBar;
    public final int navigationBar;
    public final int fabBackground;
    public final int fabForeground;
    public final int editorBackground;
    public final int editorToolbar;
    public final int editorTabActive;
    public final int editorTabInactive;
    public final int editorSelection;
    public final int terminalBackground;
    public final int terminalForeground;
    // ---- END JNI contract ----

    /** Number of int fields in the palette (excluding isDark boolean). */
    public static final int FIELD_COUNT = 29;

    private AppThemePalette(Builder b) {
        this.isDark = b.isDark;
        this.windowBackground = b.windowBackground;
        this.surfacePrimary = b.surfacePrimary;
        this.surfaceSecondary = b.surfaceSecondary;
        this.surfaceElevated = b.surfaceElevated;
        this.toolbarBackground = b.toolbarBackground;
        this.rowBackground = b.rowBackground;
        this.rowPressed = b.rowPressed;
        this.popupBackground = b.popupBackground;
        this.scrim = b.scrim;
        this.divider = b.divider;
        this.textPrimary = b.textPrimary;
        this.textSecondary = b.textSecondary;
        this.textDisabled = b.textDisabled;
        this.iconPrimary = b.iconPrimary;
        this.accent = b.accent;
        this.accentPressed = b.accentPressed;
        this.accentMuted = b.accentMuted;
        this.danger = b.danger;
        this.statusBar = b.statusBar;
        this.navigationBar = b.navigationBar;
        this.fabBackground = b.fabBackground;
        this.fabForeground = b.fabForeground;
        this.editorBackground = b.editorBackground;
        this.editorToolbar = b.editorToolbar;
        this.editorTabActive = b.editorTabActive;
        this.editorTabInactive = b.editorTabInactive;
        this.editorSelection = b.editorSelection;
        this.terminalBackground = b.terminalBackground;
        this.terminalForeground = b.terminalForeground;
    }

    /**
     * Serialize to int array for JNI. Field 0 = isDark ? 1 : 0, then all ARGB ints in order.
     */
    public int[] toIntArray() {
        int[] arr = new int[1 + FIELD_COUNT];
        arr[0] = isDark ? 1 : 0;
        arr[1] = windowBackground;
        arr[2] = surfacePrimary;
        arr[3] = surfaceSecondary;
        arr[4] = surfaceElevated;
        arr[5] = toolbarBackground;
        arr[6] = rowBackground;
        arr[7] = rowPressed;
        arr[8] = popupBackground;
        arr[9] = scrim;
        arr[10] = divider;
        arr[11] = textPrimary;
        arr[12] = textSecondary;
        arr[13] = textDisabled;
        arr[14] = iconPrimary;
        arr[15] = accent;
        arr[16] = accentPressed;
        arr[17] = accentMuted;
        arr[18] = danger;
        arr[19] = statusBar;
        arr[20] = navigationBar;
        arr[21] = fabBackground;
        arr[22] = fabForeground;
        arr[23] = editorBackground;
        arr[24] = editorToolbar;
        arr[25] = editorTabActive;
        arr[26] = editorTabInactive;
        arr[27] = editorSelection;
        arr[28] = terminalBackground;
        arr[29] = terminalForeground;
        return arr;
    }

    // ---- Factory methods ----

    /** Dark theme with the given accent color. */
    public static AppThemePalette dark(int accent) {
        Builder b = new Builder();
        b.isDark = true;
        b.windowBackground = 0xFF090C12;
        b.surfacePrimary = 0xFF1F2123;
        b.surfaceSecondary = 0xFF2A2C2E;
        b.surfaceElevated = 0xFF333537;
        b.toolbarBackground = 0xFF121416;
        b.rowBackground = 0xFF26282A;
        b.rowPressed = 0xFF3A3C3E;
        b.popupBackground = 0xFF1F2123;
        b.scrim = 0x78000000;   // 46% black
        b.divider = 0x1AFFFFFF;
        b.textPrimary = 0xFFF1F3F4;
        b.textSecondary = 0xFFA6ABAF;
        b.textDisabled = 0xFF616467;
        b.iconPrimary = 0xFFDDE1E4;
        b.accent = accent;
        b.accentPressed = darken(accent, 0.15f);
        b.accentMuted = setAlpha(accent, 0x30);
        b.danger = 0xFFEF7676;
        b.statusBar = 0xFF090C12;
        b.navigationBar = 0xFF090C12;
        b.fabBackground = accent;
        b.fabForeground = 0xFFFFFFFF;
        b.editorBackground = 0xFF1E1E1E;
        b.editorToolbar = 0xFF181A1A;
        b.editorTabActive = 0xFF303232;
        b.editorTabInactive = 0xFF1E1E1E;
        b.editorSelection = 0x3326A69A;
        b.terminalBackground = 0xFF000000;
        b.terminalForeground = 0xFFFFFFFF;
        return new AppThemePalette(b);
    }

    /** Light theme with the given accent color. */
    public static AppThemePalette light(int accent) {
        Builder b = new Builder();
        b.isDark = false;
        b.windowBackground = 0xFFF5F5F5;
        b.surfacePrimary = 0xFFFFFFFF;
        b.surfaceSecondary = 0xFFF0F0F0;
        b.surfaceElevated = 0xFFFFFFFF;
        b.toolbarBackground = 0xFFFFFFFF;
        b.rowBackground = 0xFFFFFFFF;
        b.rowPressed = 0xFFE8E8E8;
        b.popupBackground = 0xFFFFFFFF;
        b.scrim = 0x33000000;   // 20% black
        b.divider = 0x1A000000;
        b.textPrimary = 0xFF212121;
        b.textSecondary = 0xFF757575;
        b.textDisabled = 0xFFBDBDBD;
        b.iconPrimary = 0xFF616161;
        b.accent = accent;
        b.accentPressed = darken(accent, 0.10f);
        b.accentMuted = setAlpha(accent, 0x1A);
        b.danger = 0xFFD32F2F;
        b.statusBar = 0xFFE0E0E0;
        b.navigationBar = 0xFFF5F5F5;
        b.fabBackground = accent;
        b.fabForeground = 0xFFFFFFFF;
        b.editorBackground = 0xFFFFFFFF;
        b.editorToolbar = 0xFFF8F8F8;
        b.editorTabActive = 0xFFE0E0E0;
        b.editorTabInactive = 0xFFF5F5F5;
        b.editorSelection = 0x3303A9F4;
        b.terminalBackground = 0xFF1E1E1E;
        b.terminalForeground = 0xFFCCCCCC;
        return new AppThemePalette(b);
    }

    /** Create a new palette with a different accent color, keeping everything else. */
    public AppThemePalette withAccent(int newAccent) {
        Builder b = new Builder();
        b.isDark = this.isDark;
        b.windowBackground = this.windowBackground;
        b.surfacePrimary = this.surfacePrimary;
        b.surfaceSecondary = this.surfaceSecondary;
        b.surfaceElevated = this.surfaceElevated;
        b.toolbarBackground = this.toolbarBackground;
        b.rowBackground = this.rowBackground;
        b.rowPressed = this.rowPressed;
        b.popupBackground = this.popupBackground;
        b.scrim = this.scrim;
        b.divider = this.divider;
        b.textPrimary = this.textPrimary;
        b.textSecondary = this.textSecondary;
        b.textDisabled = this.textDisabled;
        b.iconPrimary = this.iconPrimary;
        b.accent = newAccent;
        b.accentPressed = darken(newAccent, 0.15f);
        b.accentMuted = setAlpha(newAccent, this.isDark ? 0x30 : 0x1A);
        b.danger = this.danger;
        b.statusBar = this.isDark ? 0xFF090C12 : 0xFFE0E0E0;
        b.navigationBar = this.isDark ? 0xFF090C12 : 0xFFF5F5F5;
        b.fabBackground = newAccent;
        b.fabForeground = this.fabForeground;
        b.editorBackground = this.editorBackground;
        b.editorToolbar = this.editorToolbar;
        b.editorTabActive = this.editorTabActive;
        b.editorTabInactive = this.editorTabInactive;
        b.editorSelection = setAlpha(newAccent, 0x33);
        b.terminalBackground = this.terminalBackground;
        b.terminalForeground = this.terminalForeground;
        return new AppThemePalette(b);
    }

    // ---- Utility ----

    private static int darken(int color, float factor) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] *= (1.0f - factor);
        return Color.HSVToColor(Color.alpha(color), hsv);
    }

    private static int setAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private static class Builder {
        boolean isDark;
        int windowBackground, surfacePrimary, surfaceSecondary, surfaceElevated;
        int toolbarBackground, rowBackground, rowPressed, popupBackground;
        int scrim, divider;
        int textPrimary, textSecondary, textDisabled, iconPrimary;
        int accent, accentPressed, accentMuted, danger;
        int statusBar, navigationBar;
        int fabBackground, fabForeground;
        int editorBackground, editorToolbar, editorTabActive, editorTabInactive, editorSelection;
        int terminalBackground, terminalForeground;
    }
}
