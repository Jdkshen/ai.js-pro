package org.autojs.autojs.ui.imgui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.SurfaceTexture;
import android.graphics.Rect;
import android.os.Build;
import android.view.DisplayCutout;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.accessibility.AccessibilityNodeProvider;

import java.util.Collections;

final class ImGuiSurfaceView extends TextureView
        implements TextureView.SurfaceTextureListener, Runnable {

    interface ActionListener {
        void onImGuiAction(int action);
    }

    private final ActionListener mActionListener;
    private final ImGuiAccessibilityProvider mAccessibilityProvider;
    private volatile boolean mRunning;
    private Thread mRenderThread;
    private Surface mNativeSurface;
    private int mNotifiedAccessibilityRevision = -1;
    private long mLastAccessibilityNotificationAt;

    ImGuiSurfaceView(Context context, ActionListener actionListener) {
        super(context);
        mActionListener = actionListener;
        mAccessibilityProvider = new ImGuiAccessibilityProvider(this);
        setSurfaceTextureListener(this);
        setOpaque(true);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
        mNativeSurface = new Surface(surfaceTexture);
        if (!ImGuiNativeBridge.attachSurface(mNativeSurface)) {
            mNativeSurface.release();
            mNativeSurface = null;
            return;
        }
        mRunning = true;
        mRenderThread = new Thread(this, "AutoJsImGuiRender");
        mRenderThread.start();
        updateSystemGestureExclusion(width, height);
        dispatchViewportMetrics();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        updateSystemGestureExclusion(width, height);
        dispatchViewportMetrics();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        updateSystemGestureExclusion(width, height);
        dispatchViewportMetrics();
    }

    /**
     * Collects unified ViewportMetrics and sends them to the native layer.
     * Called on surface change, view size change, and configuration change.
     */
    void dispatchViewportMetrics() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        float density = dm.density;
        float scaledDensity = dm.scaledDensity;
        float fontScale = getResources().getConfiguration().fontScale;
        int orientation = getResources().getConfiguration().orientation ==
                Configuration.ORIENTATION_LANDSCAPE ? 2 : 1;

        // Safe insets: system bars + display cutout (punch-hole, notch)
        int insetLeft = 0, insetTop = 0, insetRight = 0, insetBottom = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.view.WindowInsets insets = getRootWindowInsets();
            if (insets != null) {
                insetLeft = insets.getSystemWindowInsetLeft();
                insetTop = insets.getSystemWindowInsetTop();
                insetRight = insets.getSystemWindowInsetRight();
                insetBottom = insets.getSystemWindowInsetBottom();
            }
            // API 28+: overlay DisplayCutout insets (punch-hole / notch / rounded corners)
            if (Build.VERSION.SDK_INT >= 28 && insets != null) {
                DisplayCutout cutout = insets.getDisplayCutout();
                if (cutout != null) {
                    insetLeft = Math.max(insetLeft, cutout.getSafeInsetLeft());
                    insetTop = Math.max(insetTop, cutout.getSafeInsetTop());
                    insetRight = Math.max(insetRight, cutout.getSafeInsetRight());
                    insetBottom = Math.max(insetBottom, cutout.getSafeInsetBottom());
                }
            }
        }

        ImGuiNativeBridge.setViewportMetrics(w, h, density, scaledDensity, fontScale,
                insetLeft, insetTop, insetRight, insetBottom, orientation);
    }

    private void updateSystemGestureExclusion(int width, int height) {
        if (Build.VERSION.SDK_INT >= 29 && width > 0 && height > 0) {
            // Horizontal drags drive ImGui's five-page workspace. Without an exclusion rect,
            // gesture-navigation devices consume edge-originating drags as system Back.
            try {
                int allowedHeight = Math.min(height, Math.round(
                        200f * getResources().getDisplayMetrics().density));
                int exclusionTop = Math.max(0, (height - allowedHeight) / 2);
                // The project still compiles against Android 9, so call the Android 10 API
                // reflectively while preserving the existing minimum/compile SDK setup.
                TextureView.class
                        .getMethod("setSystemGestureExclusionRects", java.util.List.class)
                        .invoke(this, Collections.singletonList(
                                new Rect(0, exclusionTop, width, exclusionTop + allowedHeight)));
            } catch (ReflectiveOperationException ignored) {
                // Double-back protection in the Activity still prevents an accidental exit.
            }
        }
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        mRunning = false;
        if (mRenderThread != null) {
            try {
                mRenderThread.join(1500L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            mRenderThread = null;
        }
        ImGuiNativeBridge.detachSurface();
        if (mNativeSurface != null) {
            mNativeSurface.release();
            mNativeSurface = null;
        }
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        // Rendering and presentation are driven by the native EGL loop.
    }

    @Override
    public void run() {
        while (mRunning) {
            ImGuiNativeBridge.renderFrame(getWidth(), getHeight(),
                    getResources().getDisplayMetrics().density);
            int accessibilityRevision = ImGuiNativeBridge.getAccessibilityRevision();
            long now = android.os.SystemClock.uptimeMillis();
            if (accessibilityRevision != mNotifiedAccessibilityRevision &&
                    now - mLastAccessibilityNotificationAt >= 100L) {
                mNotifiedAccessibilityRevision = accessibilityRevision;
                mLastAccessibilityNotificationAt = now;
                post(mAccessibilityProvider::invalidateVirtualTree);
            }
            int action;
            while ((action = ImGuiNativeBridge.pollAction()) != 0) {
                final int dispatchedAction = action;
                post(() -> mActionListener.onImGuiAction(dispatchedAction));
            }
        }
    }

    @Override
    public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return mAccessibilityProvider;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int pointerIndex = Math.min(event.getActionIndex(), event.getPointerCount() - 1);
        ImGuiNativeBridge.touch(event.getActionMasked(),
                event.getX(pointerIndex), event.getY(pointerIndex));
        return true;
    }
}
