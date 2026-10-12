package com.aijs.editor.theme;

/** Shared AI/AJ workspace colors. Keep the mirrored source byte-identical. */
public final class WorkspaceColors {
    public static final float DISABLED_ALPHA = 0.38f;
    public static final int TAB_INDICATOR = 0xFF73D89A;
    private static final WorkspaceColors LIGHT = new WorkspaceColors(false);
    private static final WorkspaceColors DARK = new WorkspaceColors(true);

    public final int background, drawer, toolbar, tabs, selectedTool;
    public final int primary, secondary, icons, popup, status, navigation, scrim;

    private WorkspaceColors(boolean dark) {
        background = dark ? 0xFF1E1E1E : 0xFFFFFFFF;
        drawer = dark ? 0xFF1F2123 : 0xFFFFFFFF;
        toolbar = dark ? 0xFF181A1A : 0xFFF8F8F8;
        tabs = dark ? 0xFF1E1E1E : 0xFFF5F5F5;
        selectedTool = dark ? 0xFF303232 : 0xFFE0E0E0;
        primary = dark ? 0xFFF1F3F4 : 0xFF212121;
        secondary = dark ? 0xFFA6ABAF : 0xFF757575;
        icons = dark ? 0xFFDDE1E4 : 0xFF616161;
        popup = dark ? 0xFF333537 : 0xFFFFFFFF;
        status = dark ? 0xFF090C12 : 0xFFE0E0E0;
        navigation = dark ? 0xFF090C12 : 0xFFF5F5F5;
        scrim = dark ? 0x78000000 : 0x33000000;
    }

    public static WorkspaceColors get(boolean dark) { return dark ? DARK : LIGHT; }
}
