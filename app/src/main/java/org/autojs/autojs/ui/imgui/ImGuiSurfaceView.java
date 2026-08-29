package org.autojs.autojs.ui.imgui;

import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.util.Collections;

final class ImGuiSurfaceView extends SurfaceView implements SurfaceHolder.Callback, Runnable {

    interface ActionListener {
        void onImGuiAction(int action);
    }

    private final ActionListener mActionListener;
    private volatile boolean mRunning;
    private Thread mRenderThread;

    ImGuiSurfaceView(Context context, ActionListener actionListener) {
        super(context);
        mActionListener = actionListener;
        getHolder().addCallback(this);
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (!ImGuiNativeBridge.attachSurface(holder.getSurface())) {
            return;
        }
        mRunning = true;
        mRenderThread = new Thread(this, "AutoJsImGuiRender");
        mRenderThread.start();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        updateSystemGestureExclusion(width, height);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        updateSystemGestureExclusion(width, height);
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
                SurfaceView.class
                        .getMethod("setSystemGestureExclusionRects", java.util.List.class)
                        .invoke(this, Collections.singletonList(
                                new Rect(0, exclusionTop, width, exclusionTop + allowedHeight)));
            } catch (ReflectiveOperationException ignored) {
                // Double-back protection in the Activity still prevents an accidental exit.
            }
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
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
    }

    @Override
    public void run() {
        while (mRunning) {
            ImGuiNativeBridge.renderFrame(getWidth(), getHeight(),
                    getResources().getDisplayMetrics().density);
            int action;
            while ((action = ImGuiNativeBridge.pollAction()) != 0) {
                final int dispatchedAction = action;
                post(() -> mActionListener.onImGuiAction(dispatchedAction));
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int pointerIndex = Math.min(event.getActionIndex(), event.getPointerCount() - 1);
        ImGuiNativeBridge.touch(event.getActionMasked(),
                event.getX(pointerIndex), event.getY(pointerIndex));
        return true;
    }
}
