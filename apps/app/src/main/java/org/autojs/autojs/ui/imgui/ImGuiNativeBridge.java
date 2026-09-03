package org.autojs.autojs.ui.imgui;

import android.view.Surface;

final class ImGuiNativeBridge {

    static {
        System.loadLibrary("autojs_imgui");
    }

    private ImGuiNativeBridge() {
    }

    static native boolean attachSurface(Surface surface);

    static native void detachSurface();

    static native void renderFrame(int width, int height, float density);

    static native void touch(int action, float x, float y);

    static native void setUiFontPath(String path);

    static native void setWorkspaceState(boolean accessibilityEnabled, int runningScriptCount);

    static native void setDrawerState(boolean accessibilityEnabled, boolean floatingShown,
                                      boolean devConnected);

    static native boolean closeWorkspaceDrawer();

    static native void setScriptEntries(String directoryLabel, String[] names, String[] paths,
                                        boolean[] directories, String[] types,
                                        String[] modifiedTimes, int[] iconKinds);

    static native void setSampleEntries(String directoryLabel, String[] names, String[] paths,
                                        boolean[] directories, String[] types,
                                        String[] modifiedTimes);

    static native void setResourceEntries(String[] names, String[] descriptions,
                                          String[] metadata, String[] assetPaths,
                                          boolean[] imported);

    static native void setPluginEntries(String[] names, String[] versions,
                                        String[] packages, boolean[] installed);

    static native void setTaskEntries(String[] runningNames, String[] runningDescriptions,
                                      String[] pendingNames, String[] pendingDescriptions);

    static native String getSelectedScriptPath();

    static native String getSelectedSamplePath();

    static native String getSelectedResourcePath();

    static native String getSelectedPluginPackage();

    static native int getSelectedTaskKey();

    static native int getSelectedBreadcrumbDepth();

    static native int getCurrentSection();

    static native int pollAction();

    static native int getAccessibilityRevision();

    static native String[] getAccessibilitySnapshot();

    static native boolean performAccessibilityAction(int virtualViewId, int action);

    /**
     * Passes unified viewport metrics to the native layer.
     * Used for rendering (glViewport, io.DisplaySize) and touch coordinate mapping.
     */
    static native void setViewportMetrics(int viewWidthPx, int viewHeightPx,
                                          float density, float scaledDensity, float fontScale,
                                          int safeInsetLeftPx, int safeInsetTopPx,
                                          int safeInsetRightPx, int safeInsetBottomPx,
                                          int orientation);

    /**
     * Passes the complete theme palette to the native layer.
     * argbColors[0] = isDark ? 1 : 0, then all ARGB ints in AppThemePalette field order.
     */
    static native void setThemePalette(int[] argbColors);
}
