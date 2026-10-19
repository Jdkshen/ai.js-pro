package com.stardust.autojs.core.floaty;

import android.util.Log;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import java.util.ArrayList;
import java.util.List;

/**
 * floaty 窗口 inflate 完成后，把「{@code android.view.SurfaceView} 原类」实例替换成
 * {@link ScriptThreadSurfaceView}，让脚本通过 {@code getHolder()} 拿到会投递脚本线程的代理。
 *
 * <p>为什么替换而不是给 holder 挂 callback：项目脚本自己会
 * {@code holder.addCallback(new SurfaceHolder.Callback({...}))}，必须让 {@code getHolder()}
 * 返回我们的代理才能在源头上改投递线程。替换发生在窗口创建阶段（脚本线程还阻塞在
 * {@code waitForCreation}，还没拿到 view 引用），因此脚本后续按 id 找到的就是替换后的实例。
 *
 * <p>约束：
 * <ul>
 *   <li>只替换 {@code view.getClass() == SurfaceView.class}，GLSurfaceView/自定义 SurfaceView
 *       子类一律不动，避免破坏其它脚本；</li>
 *   <li>复制 id / LayoutParams / 可见性 / 事件开关等必要属性，XML 的 w/h、id 等语义不变；</li>
 *   <li>只由 {@link RawWindow} / {@link BaseResizableFloatyWindow} 在窗口创建时调用，
 *       普通（非 floaty）UI 路径不受影响。</li>
 * </ul>
 */
final class FloatySurfaceThreading {

    private static final String TAG = "FloatySurfaceThreading";

    private FloatySurfaceThreading() {
    }

    /** 遍历并替换 root 下所有原生 SurfaceView（幂等：已是 ScriptThreadSurfaceView 的跳过）。 */
    static void rebindSurfaceViews(View root) {
        if (root == null) {
            return;
        }
        List<View> targets = new ArrayList<>();
        collect(root, targets);
        for (View target : targets) {
            replace(target);
        }
    }

    /**
     * 窗口 close() 前同步释放所有 Surface 回调投递点（幂等）。
     *
     * <p>为什么需要在 close() 里做而不是等 onDetachedFromWindow：removeView 的 detach 要等
     * 下一次 traversal（main 线程异步），而 C 侧 STOP 顺序是 closeAll → stopExecution →
     * engine.destroy；中间可能已有回调 post 到脚本线程 Handler。提前 release 后，投递队列里的
     * runnable 看到 release 标记就直接丢弃，不会在 runtime/engine 销毁后再碰脚本回调。
     */
    static void releaseSurfaceViews(View root) {
        if (root == null) {
            return;
        }
        List<View> views = new ArrayList<>();
        collectAll(root, views);
        for (View view : views) {
            if (view instanceof ScriptThreadSurfaceView) {
                ((ScriptThreadSurfaceView) view).releaseHolder();
            }
        }
    }

    private static void collectAll(View view, List<View> out) {
        if (view == null) {
            return;
        }
        if (view instanceof ScriptThreadSurfaceView) {
            out.add(view);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectAll(group.getChildAt(i), out);
            }
        }
    }

    private static void collect(View view, List<View> out) {
        if (view == null) {
            return;
        }
        if (view.getClass() == SurfaceView.class) {
            out.add(view);
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collect(group.getChildAt(i), out);
            }
        }
    }

    private static void replace(View old) {
        ViewParent parent = old.getParent();
        if (!(parent instanceof ViewGroup)) {
            return;
        }
        ViewGroup group = (ViewGroup) parent;
        int index = group.indexOfChild(old);
        if (index < 0) {
            return;
        }
        try {
            ScriptThreadSurfaceView replacement = new ScriptThreadSurfaceView(old.getContext());
            applyCommonViewState(old, replacement);
            ViewGroup.LayoutParams params = old.getLayoutParams();
            group.removeViewAt(index);
            if (params != null) {
                group.addView(replacement, index, params);
            } else {
                group.addView(replacement, index);
            }
            Log.i(TAG, "SurfaceView 回调已切到脚本线程：id=" + old.getId());
        } catch (Throwable error) {
            Log.w(TAG, "替换 SurfaceView 失败（保持原行为）", error);
        }
    }

    private static void applyCommonViewState(View old, View replacement) {
        replacement.setId(old.getId());
        replacement.setVisibility(old.getVisibility());
        replacement.setAlpha(old.getAlpha());
        replacement.setEnabled(old.isEnabled());
        replacement.setClickable(old.isClickable());
        replacement.setLongClickable(old.isLongClickable());
        replacement.setFocusable(old.isFocusable());
        replacement.setContentDescription(old.getContentDescription());
        replacement.setBackground(old.getBackground());
        replacement.setPadding(old.getPaddingLeft(), old.getPaddingTop(),
                old.getPaddingRight(), old.getPaddingBottom());
        Object tag = old.getTag();
        if (tag != null) {
            replacement.setTag(tag);
        }
        replacement.setLayoutParams(old.getLayoutParams());
    }
}
