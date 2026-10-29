package org.autojs.source.engine.android;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.stardust.autojs.engine.ScriptUiTasks;

/**
 * task-218：清理脚本直接 WindowManager.addView 留下的 Node View。
 *
 * <p>:node24 脚本可能绕过 Node24LocalOverlay / NodeUiHost，直接对 WindowManager
 * addView（node-port 画布/文本 overlay）；这些 View 不在 OverlayRegistry 里，
 * 脚本结束后会继续拦截触摸。这里按规格 4.3.3 反射 WindowManagerGlobal.mViews：
 * 移除 class 名包含 NodeUiCanvasView 或 tag=node-port-overlay 的 View。
 */
final class Node24DirectOverlayCleanup {

    private static final String TAG = "Node24Overlay";
    private static final String DIRECT_OVERLAY_TAG = "node-port-overlay";
    private static final String CANVAS_VIEW_CLASS = "NodeUiCanvasView";

    private Node24DirectOverlayCleanup() {
    }

    /** 在主线程扫描 WindowManagerGlobal.mViews 并移除直接挂载的 Node overlay View。 */
    static int closeAllInProcess() {
        final AtomicInteger count = new AtomicInteger();
        final Runnable task = () -> {
            try {
                Class<?> wmgClass = Class.forName("android.view.WindowManagerGlobal");
                Method getInstance = wmgClass.getDeclaredMethod("getInstance");
                getInstance.setAccessible(true);
                final Object wmg = getInstance.invoke(null);
                Field viewsField = wmgClass.getDeclaredField("mViews");
                viewsField.setAccessible(true);
                Object rawViews = viewsField.get(wmg);
                if (!(rawViews instanceof ArrayList)) {
                    return;
                }
                @SuppressWarnings("unchecked")
                ArrayList<View> views = new ArrayList<>((ArrayList<View>) rawViews);
                Method removeView = wmgClass.getDeclaredMethod("removeView", View.class, boolean.class);
                removeView.setAccessible(true);
                for (View view : views) {
                    if (view == null) {
                        continue;
                    }
                    String className = view.getClass().getName();
                    String tag = String.valueOf(view.getTag());
                    if (!className.contains(CANVAS_VIEW_CLASS) && !DIRECT_OVERLAY_TAG.equals(tag)) {
                        continue;
                    }
                    try {
                        removeView.invoke(wmg, view, true);
                        count.incrementAndGet();
                    } catch (Throwable ignored) {
                        // 单个 View 已被移除/已 detach：继续清理其它 View。
                    }
                }
            } catch (Throwable ignored) {
                // 反射字段在部分 ROM 不可用：不能让清理步骤影响脚本收尾。
            }
        };
        ScriptUiTasks.runAndWait(task, 1500L);
        Log.i(TAG, "direct overlay cleanup count=" + count.get());
        return count.get();
    }
}
