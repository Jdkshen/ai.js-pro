package com.stardust.autojs.runtime.api;

import android.app.Activity;
import android.content.Context;
import android.text.TextUtils;
import android.view.ContextThemeWrapper;
import android.widget.CompoundButton;

import com.afollestad.materialdialogs.MaterialDialog;
import com.afollestad.materialdialogs.Theme;
import com.stardust.autojs.R;
import com.stardust.autojs.annotation.ScriptInterface;
import com.stardust.autojs.annotation.ScriptVariable;
import com.stardust.autojs.core.ui.dialog.BlockedMaterialDialog;
import com.stardust.autojs.core.ui.dialog.JsDialogBuilder;
import com.stardust.autojs.runtime.ScriptBridges;
import com.stardust.autojs.runtime.ScriptRuntime;
import com.stardust.autojs.util.FloatingPermission;
import com.stardust.util.ArrayUtils;
import com.stardust.util.UiHandler;

import java.util.Locale;

/**
 * Created by Stardust on 2017/5/8.
 */

public class Dialogs {

    private ContextThemeWrapper mThemeWrapper;
    private final ScriptRuntime mRuntime;

    @ScriptVariable
    public final NonUiDialogs nonUiDialogs = new NonUiDialogs();

    public Dialogs(ScriptRuntime runtime) {
        mRuntime = runtime;
    }

    @ScriptInterface
    public Object rawInput(String title, String prefill, Object callback) {
        return ((BlockedMaterialDialog.Builder) dialogBuilder(callback)
                .input(null, prefill, true)
                .title(title))
                .showAndGet();
    }

    @ScriptInterface
    public Object alert(String title, String content, Object callback) {
        MaterialDialog.Builder builder = dialogBuilder(callback)
                .alert()
                .title(title)
                .positiveText(R.string.ok);
        if (!TextUtils.isEmpty(content)) {
            builder.content(content);
        }
        return ((BlockedMaterialDialog.Builder) builder).showAndGet();
    }

    @ScriptInterface
    public Object confirm(String title, String content, Object callback) {
        MaterialDialog.Builder builder = dialogBuilder(callback)
                .confirm()
                .title(title)
                .positiveText(R.string.ok)
                .negativeText(R.string.cancel);
        if (!TextUtils.isEmpty(content)) {
            builder.content(content);
        }
        return ((BlockedMaterialDialog.Builder) builder).showAndGet();
}

    private Context getContext() {
        if (mThemeWrapper != null)
            return mThemeWrapper;
        mThemeWrapper = new ContextThemeWrapper(mRuntime.uiHandler.getContext().getApplicationContext(), R.style.Theme_AppCompat_Light);
        return mThemeWrapper;
    }

    // ------------------------------------------------------------------
    // PDF §2.11：对话框类型（Pro 8.8.5 新增的 dialog.setDefaultDialogType）
    // ------------------------------------------------------------------

    /** 始终用悬浮窗权限显示（走 BlockedMaterialDialog 的 TYPE_APPLICATION_OVERLAY 分支）。 */
    public static final String TYPE_OVERLAY = "overlay";
    /** 始终为应用内对话框（必须有 Activity）。 */
    public static final String TYPE_APP = "app";
    /** 有界面就用应用内，没有界面用悬浮窗。 */
    public static final String TYPE_APP_OR_OVERLAY = "app-or-overlay";
    /** 本应用在前台用应用内，后台用悬浮窗。 */
    public static final String TYPE_FOREGROUND_OR_OVERLAY = "foreground-or-overlay";

    /**
     * 仓库既有行为就是 app-or-overlay（有 Activity 用应用内，否则走悬浮窗），
     * 所以默认值取它；文档写的默认是 overlay（为了兼容旧版本），两者的差别记在文档 §9。
     */
    private volatile String mDefaultDialogType = TYPE_APP_OR_OVERLAY;

    /** `dialog.setDefaultDialogType(type)`：设置所有对话框的默认类型。 */
    @ScriptInterface
    public void setDefaultDialogType(String type) {
        mDefaultDialogType = normalizeDialogType(type);
    }

    /** 文档没写、验收用的查询接口：当前默认对话框类型。 */
    @ScriptInterface
    public String getDefaultDialogType() {
        return mDefaultDialogType;
    }

    static String normalizeDialogType(String type) {
        String value = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty() || "undefined".equals(value) || "null".equals(value)) {
            throw new IllegalArgumentException("对话框类型不能为空");
        }
        switch (value) {
            case TYPE_OVERLAY:
            case TYPE_APP:
            case TYPE_APP_OR_OVERLAY:
            case TYPE_FOREGROUND_OR_OVERLAY:
                return value;
            default:
                throw new IllegalArgumentException("未知的对话框类型: " + type
                        + "（可选 overlay / app / app-or-overlay / foreground-or-overlay）");
        }
    }

    /**
     * 根据类型挑 Context：
     * 返回 Activity → 普通应用内对话框；返回应用 Context → {@link BlockedMaterialDialog} 会
     * 自己把窗口设成 {@code TYPE_APPLICATION_OVERLAY}。
     *
     * @param type 为空时用默认类型；{@code dialogs.build({type: …})} 传入的就是这里
     */
    private Context resolveContext(String type) {
        String resolved = isNoType(type) ? mDefaultDialogType : normalizeDialogType(type);
        Activity activity = currentActivity();
        boolean hasActivity = activity != null && !activity.isFinishing();
        if (TYPE_APP.equals(resolved)) {
            if (!hasActivity) {
                throw new IllegalStateException("app 类型对话框需要本应用有界面（当前没有可用的 Activity）");
            }
            return activity;
        }
        if (TYPE_OVERLAY.equals(resolved)) {
            return overlayContext();
        }
        // app-or-overlay / foreground-or-overlay：
        // 该仓库没有单独跟踪"应用是否在前台"的状态，用「有未结束的可见 Activity」近似，
        // 没有 Activity 时回落到悬浮窗（与文档描述一致）。
        return hasActivity ? activity : overlayContext();
    }

    /** 悬浮窗对话框：要求有悬浮窗权限，没权限就直接报错（文档：「没有悬浮窗权限时抛出异常」）。 */
    private Context overlayContext() {
        if (!FloatingPermission.canDrawOverlays(mRuntime.uiHandler.getContext())) {
            throw new IllegalStateException("悬浮窗类型对话框需要悬浮窗权限");
        }
        return getContext();
    }

    private Activity currentActivity() {
        Context context = mRuntime.app.getCurrentActivity();
        return context instanceof Activity ? (Activity) context : null;
    }

    /** 没传 / 传了 JS 的 undefined（Rhino 会变成字符串 "undefined"）都算没指定类型。 */
    private static boolean isNoType(String type) {
        if (type == null) {
            return true;
        }
        String value = type.trim().toLowerCase(Locale.ROOT);
        return value.isEmpty() || "undefined".equals(value) || "null".equals(value);
    }

    @ScriptInterface
    public Object select(String title, String[] items, Object callback) {
        return ((BlockedMaterialDialog.Builder) dialogBuilder(callback)
                .itemsCallback()
                .title(title)
                .items((CharSequence[]) items))
                .showAndGet();
    }

    private String[] getItems(Object[] args) {
        int len = 0;
        if (args.length > 1) {
            if (args[args.length - 1] instanceof CharSequence) {
                len = args.length;
            } else {
                len = args.length - 1;
            }
        }
        String[] items = new String[len];
        for (int i = 0; i < len; i++) {
            items[i] = args[i] == null ? null : args[i].toString();
        }
        return items;
    }

    @ScriptInterface
    public Object singleChoice(String title, int selectedIndex, String[] items, Object callback) {
        return ((BlockedMaterialDialog.Builder) dialogBuilder(callback)
                .itemsCallbackSingleChoice(selectedIndex)
                .title(title)
                .positiveText(R.string.ok)
                .items((CharSequence[]) items))
                .showAndGet();
    }

    @ScriptInterface
    public Object multiChoice(String title, int[] indices, String[] items, Object callback) {
        return ((BlockedMaterialDialog.Builder) dialogBuilder(callback)
                .itemsCallbackMultiChoice(ArrayUtils.box(indices))
                .title(title)
                .positiveText(R.string.ok)
                .items((CharSequence[]) items))
                .showAndGet();
    }

    @ScriptInterface
    public Object selectFile(String title, String prefill, Object callback) {
        return ((BlockedMaterialDialog.Builder) dialogBuilder(callback)
                .input(null, prefill, true)
                .title(title))
                .showAndGet();
    }

    private BlockedMaterialDialog.Builder dialogBuilder(Object callback) {
        return (BlockedMaterialDialog.Builder) new BlockedMaterialDialog.Builder(
                resolveContext(null), mRuntime, callback)
                .theme(Theme.LIGHT);
    }

    @ScriptInterface
    public MaterialDialog.Builder newBuilder() {
        return newBuilder(null);
    }

    /** `dialogs.build({type: …})` 会把这个 type 传进来覆盖默认类型。 */
    @ScriptInterface
    public MaterialDialog.Builder newBuilder(String type) {
        return new JsDialogBuilder(resolveContext(type), mRuntime)
                .theme(Theme.LIGHT);
    }

    public class NonUiDialogs {

        public String rawInput(String title, String prefill, Object callback) {
            return (String) Dialogs.this.rawInput(title, prefill, callback);
        }

        @ScriptInterface
        public boolean confirm(String title, String content, Object callback) {
            return (boolean) Dialogs.this.confirm(title, content, callback);
        }

        @ScriptInterface
        public int select(String title, String[] items, Object callback) {
            return (Integer) Dialogs.this.select(title, items, callback);
        }

        @ScriptInterface
        public int singleChoice(String title, int selectedIndex, String[] items, Object callback) {
            return (int) Dialogs.this.singleChoice(title, selectedIndex, items, callback);
        }

        @ScriptInterface
        public int[] multiChoice(String title, int[] indices, String[] items, Object callback) {
            return (int[]) Dialogs.this.multiChoice(title, indices, items, callback);
        }

        @ScriptInterface
        public Object alert(String title, String content, Object callback) {
            return Dialogs.this.alert(title, content, callback);
        }

    }
}
