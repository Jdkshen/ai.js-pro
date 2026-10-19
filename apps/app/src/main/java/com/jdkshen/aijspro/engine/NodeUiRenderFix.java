package com.jdkshen.aijspro.engine;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.appcompat.widget.Toolbar;

import com.stardust.autojs.core.ui.inflater.CompiledProjectResources;
import com.stardust.autojs.core.ui.inflater.ProjectResourceBundle;

/**
 * Node UI 真 Activity 的 inflater 选择与标题栏兜底。
 *
 * <p>有 project-res.apk 时完全交给系统 LayoutInflater + 项目 Resources，Activity 不再对视图做
 * 绘制修补；只有 DynamicLayoutInflater 路径才可能需要补一个标题栏。MaterialButton/Adapter
 * item 的图标绘制由引擎侧负责，这里不再遍历 Activity 根视图做 compound drawable 兜底。
 */
final class NodeUiRenderFix {

    private static final String TAG = "C-INFLATE";

    private NodeUiRenderFix() {
    }

    /** 有编译资源（project-res.apk）时用系统 inflater + 项目 Resources 渲染；没有则返回 null（调用方回退 dynamic）。 */
    static View inflateFramework(Context activity, ProjectResourceBundle bundle, Context themed,
                                 String layoutName, int layoutId) {
        try {
            CompiledProjectResources compiled = bundle.getCompiled();
            if (compiled == null || compiled.getResources() == null) {
                Log.i(TAG, "dynamic fallback (no project-res.apk) layout=" + layoutName);
                return null;
            }
            final Resources resources = compiled.getResources();
            Context projectContext = new ContextWrapper(themed) {
                @Override public Resources getResources() { return resources; }
                @Override public android.content.res.AssetManager getAssets() { return resources.getAssets(); }
            };
            int id = layoutId != 0 ? layoutId : bundle.layoutId(layoutName);
            if (id == 0) {
                Log.i(TAG, "dynamic fallback (layout id=0) layout=" + layoutName);
                return null;
            }
            LayoutInflater inflater = LayoutInflater.from(themed).cloneInContext(projectContext);
            View view = inflater.inflate(id, null);
            Log.i(TAG, "framework inflate id=" + id + " layout=" + layoutName);
            return view;
        } catch (Throwable error) {
            Log.w(TAG, "framework inflate failed, dynamic fallback: " + error);
            return null;
        }
    }

    /**
     * 仅用于动态 inflater 且布局没有声明 Toolbar 的工程：用工程声明的 title（intent 传入）
     * 补一个固定标题栏。
     *
     * <p>布局自己声明了 Toolbar 时直接返回 content，避免出现第二个 Toolbar；title 为空时不补，
     * 不再给任意工程硬编码演示标题。状态栏 inset 只由 wrapper 的 fitsSystemWindows
     * 处理一次，不再手工加 statusBar 高度，避免和系统 inset 重复。
     */
    static View wrapWithTitleBar(Activity activity, View content, CharSequence title) {
        if (content == null) {
            return null;
        }
        if (title == null || title.length() == 0) {
            Log.i(TAG, "C-TOPBAR: skip (empty title)");
            return content;
        }
        if (findToolbar(content) != null) {
            Log.i(TAG, "C-TOPBAR: skip (layout declares toolbar)");
            return content;
        }
        try {
            int barHeight = Math.round(56 * activity.getResources().getDisplayMetrics().density);

            Toolbar toolbar = new Toolbar(activity);
            toolbar.setTitle(title);

            LinearLayout wrapper = new LinearLayout(activity);
            wrapper.setOrientation(LinearLayout.VERTICAL);
            // 只保留这一处状态栏处理：Activity 内容区本身已 fit 时不会重复，edge-to-edge 时补一次。
            wrapper.setFitsSystemWindows(true);
            wrapper.addView(toolbar, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, barHeight));
            wrapper.addView(content, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
            Log.i(TAG, "C-TOPBAR: inserted h=" + barHeight + " title=" + title);
            return wrapper;
        } catch (Throwable error) {
            Log.w(TAG, "C-TOPBAR insert failed", error);
            return content;
        }
    }

    /** 布局树里是否已有 Toolbar（MaterialToolbar 是 appcompat Toolbar 的子类）。 */
    private static View findToolbar(View view) {
        if (view == null) {
            return null;
        }
        if (view instanceof Toolbar) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findToolbar(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
