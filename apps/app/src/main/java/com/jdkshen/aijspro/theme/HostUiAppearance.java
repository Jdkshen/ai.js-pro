package com.jdkshen.aijspro.theme;

import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.Window;

/** Surfaces shared by the launcher, Flutter window and bundled documentation. */
public final class HostUiAppearance {
    public static final int DARK_SURFACE = 0xFF1C2022;
    public static final int LIGHT_SURFACE = 0xFFF7F7F8;

    public static int surface(boolean dark) { return dark ? DARK_SURFACE : LIGHT_SURFACE; }

    public static void apply(Window window, boolean dark) {
        int color = surface(dark);
        window.setBackgroundDrawable(new ColorDrawable(color));
        window.getDecorView().setBackgroundColor(color);
        window.setStatusBarColor(color);
        window.setNavigationBarColor(color);
        int icons = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        int flags = window.getDecorView().getSystemUiVisibility();
        window.getDecorView().setSystemUiVisibility(dark ? flags & ~icons : flags | icons);
        try {
            window.getClass().getMethod("setExtraFlags", int.class, int.class)
                    .invoke(window, dark ? 0 : 0x20, 0x20);
        } catch (ReflectiveOperationException ignored) {
            // Standard system-bar flags apply on non-MIUI devices.
        }
    }

    private HostUiAppearance() {}
}
