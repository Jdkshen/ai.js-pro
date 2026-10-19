package com.stardust.autojs.runtime.api;

import android.app.Activity;
import android.content.Context;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.ContextThemeWrapper;

import com.afollestad.materialdialogs.MaterialDialog;
import com.afollestad.materialdialogs.Theme;
import com.stardust.autojs.R;
import com.stardust.autojs.annotation.ScriptInterface;
import com.stardust.autojs.annotation.ScriptVariable;
import com.stardust.autojs.core.ui.dialog.BlockedMaterialDialog;
import com.stardust.autojs.core.ui.dialog.JsDialogBuilder;
import com.stardust.autojs.runtime.ScriptRuntime;
import com.stardust.autojs.util.FloatingPermission;
import com.stardust.util.ArrayUtils;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Created by Stardust on 2017/5/8.
 */

public class Dialogs implements AutoCloseable {

    private static final String TAG = "Dialogs";

    /**
     * 引擎退出时等待主线程关完对话框的上限。正常情况 p90 远小于 1 帧；
     * 这里必须有限，避免 main looper 卡住时把引擎销毁流程也一起卡死/ANR。
     */
    private static final long CLOSE_DIALOGS_TIMEOUT_MS = 1000L;

    private ContextThemeWrapper mThemeWrapper;
    private final ScriptRuntime mRuntime;

    /**
     * 本 runtime 创建过、且可能仍在屏幕上的对话框。只存弱引用：
     * 对话框被 JS/Builder 正常关闭并释放后，不能因为清理表而继续强引用它
     * （旧实现里 dialog 的 listener 还会反向持有 Rhino 引擎，形成跨脚本泄漏）。
     */
    private final List<WeakReference<MaterialDialog>> mDialogs = new ArrayList<>();
    private final Object mDialogsLock = new Object();
    private boolean mClosed;

    @ScriptVariable
    public final NonUiDialogs nonUiDialogs = new NonUiDialogs();

    public Dialogs(ScriptRuntime runtime) {
        mRuntime = runtime;
        // 注册到 runtime 的退出清理：stop / 未捕获异常 / 正常结束都会走 closeCleanables()。
        // close() 是幂等的，ScriptRuntime.onExit 里还会更早显式关一次（见 onExit 注释）。
        mRuntime.addCleanable(this);
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
        if (!(context instanceof Activity)) {
            return null;
        }
        Activity activity = (Activity) context;
        // task-118：清掉已 finish/destroy 的 Activity，避免拿到旧主题（Theme.Black.NoTitleBar）
        // 给 MaterialDialog 用；文件列表运行 YOLO 时 dialogs.select inflate md_listitem 崩溃就是这条。
        return activity.isFinishing() || activity.isDestroyed() ? null : activity;
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
        TrackedBlockedMaterialDialogBuilder builder = new TrackedBlockedMaterialDialogBuilder(
                this, resolveContext(null), mRuntime, callback);
        builder.theme(Theme.LIGHT);
        return builder;
    }

    @ScriptInterface
    public MaterialDialog.Builder newBuilder() {
        return newBuilder(null);
    }

    /** `dialogs.build({type: …})` 会把这个 type 传进来覆盖默认类型。 */
    @ScriptInterface
    public MaterialDialog.Builder newBuilder(String type) {
        return new TrackedJsDialogBuilder(this, resolveContext(type), mRuntime)
                .theme(Theme.LIGHT);
    }

    // ------------------------------------------------------------------
    // 对话框生命周期
    // ------------------------------------------------------------------

    /**
     * 同步/回调式对话框（alert、confirm、rawInput、select…）的 Builder。
     *
     * <p>{@link BlockedMaterialDialog.Builder#showAndGet()} 内部走
     * {@code MaterialDialog.Builder.show() -> build()}，因此在这里截获实际创建的
     * {@link MaterialDialog} 即可覆盖所有内置对话框，无需改动 Builder 本身。
     */
    public static final class TrackedBlockedMaterialDialogBuilder extends BlockedMaterialDialog.Builder {

        private final Dialogs mOwner;

        public TrackedBlockedMaterialDialogBuilder(Dialogs owner, Context context,
                                                   ScriptRuntime runtime, Object callback) {
            super(context, runtime, callback);
            mOwner = owner;
        }

        @Override
        public MaterialDialog build() {
            MaterialDialog dialog = super.build();
            mOwner.trackDialog(dialog);
            return dialog;
        }
    }

    /**
     * {@code dialogs.build(...)}（Rhino）和 QuickJS host bridge 使用的异步 Builder。
     *
     * <p>Rhino 的 {@code __dialogs__.js} 调 {@code buildDialog()}，其内部
     * {@code JsDialog} 构造器又会调 {@code build()}；两条路都会在这里登记同一个
     * MaterialDialog。QuickJS host bridge 直接调 {@code build()}，也会被登记，
     * 从而在引擎销毁时按本 runtime 关闭，而不是靠全局 dismiss。
     */
    public static final class TrackedJsDialogBuilder extends JsDialogBuilder {

        private final Dialogs mOwner;

        public TrackedJsDialogBuilder(Dialogs owner, Context context, ScriptRuntime runtime) {
            super(context, runtime);
            mOwner = owner;
        }

        @Override
        public MaterialDialog build() {
            MaterialDialog dialog = super.build();
            mOwner.trackDialog(dialog);
            return dialog;
        }
    }

    private void trackDialog(MaterialDialog dialog) {
        if (dialog == null) {
            return;
        }
        boolean closeNow = false;
        synchronized (mDialogsLock) {
            if (mClosed) {
                // 极端竞态：引擎退出清理已经开始，UI 线程又构建了一个对话框。
                // 不能把它留在屏幕上，立即按同一套主线程规则关掉。
                closeNow = true;
            } else {
                removeDeadDialogsLocked();
                mDialogs.add(new WeakReference<>(dialog));
            }
        }
        if (closeNow) {
            // 用 post 而不是同步 dismiss：退出清理和 UI 线程可能并发，当前可能正处在
            // build() -> show() 之间。投递到主线程队列尾部可保证同一轮 build/show 结束后
            // 再关，避免「刚关掉又被 show 出来」。
            closeDialogLater(dialog);
        }
    }

    private void removeDeadDialogsLocked() {
        Iterator<WeakReference<MaterialDialog>> iterator = mDialogs.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().get() == null) {
                iterator.remove();
            }
        }
    }

    /**
     * 关闭本 runtime 创建的所有对话框。由 {@link ScriptRuntime#onExit()} 调用。
     *
     * <p>只处理本实例登记的对话框，不调用任何全局 dismissAll，因此不会误关其它
     * 引擎/悬浮窗的窗口。关闭动作正常路径一定投递到主线程执行；dialog listener 里的
     * 用户回调保持原样（这里不清 listener、不吞事件），对话框自身照常发 dismiss。
     */
    @Override
    public void close() {
        List<MaterialDialog> dialogs;
        synchronized (mDialogsLock) {
            mClosed = true;
            dialogs = new ArrayList<>(mDialogs.size());
            for (WeakReference<MaterialDialog> reference : mDialogs) {
                MaterialDialog dialog = reference.get();
                if (dialog != null) {
                    dialogs.add(dialog);
                }
            }
            mDialogs.clear();
        }
        if (dialogs.isEmpty()) {
            return;
        }
        Log.d(TAG, "Closing " + dialogs.size() + " dialog(s) on engine exit");
        closeDialogsOnMainThread(dialogs);
    }

    private void closeDialogsOnMainThread(final List<MaterialDialog> dialogs) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            dismissDialogs(dialogs);
            return;
        }
        final CountDownLatch done = new CountDownLatch(1);
        boolean posted;
        try {
            posted = mRuntime.uiHandler.post(() -> {
                try {
                    dismissDialogs(dialogs);
                } finally {
                    done.countDown();
                }
            });
        } catch (Throwable error) {
            Log.w(TAG, "Cannot post dialog cleanup to main thread", error);
            posted = false;
        }
        if (!posted) {
            // Handler 已 quit 之类：没有 UI 线程可用了，退化为当前线程执行。
            // Android Dialog.dismiss() 在非主线程会自己 post，不会跨线程碰 View。
            dismissDialogs(dialogs);
            return;
        }
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CLOSE_DIALOGS_TIMEOUT_MS);
        for (;;) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                Log.w(TAG, "Timed out waiting for main thread to dismiss " + dialogs.size() + " dialog(s)");
                break;
            }
            try {
                if (done.await(remaining, TimeUnit.NANOSECONDS)) {
                    break;
                }
            } catch (InterruptedException error) {
                // 停止脚本时当前线程常常正处于 interrupt 状态。这里不能直接放弃等待：
                // 先把中断记下来继续等，最后再恢复，保证 dialog 在 onExit 返回前已经关闭。
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void dismissDialogs(List<MaterialDialog> dialogs) {
        for (MaterialDialog dialog : dialogs) {
            try {
                dialog.dismiss();
            } catch (Throwable error) {
                Log.w(TAG, "Cannot dismiss dialog: " + dialog, error);
            }
        }
    }

    /** 退出清理开始之后才建出来的对话框：投递到主线程尾部关掉，不阻塞调用方。 */
    private void closeDialogLater(final MaterialDialog dialog) {
        Runnable close = () -> {
            List<MaterialDialog> dialogs = new ArrayList<>(1);
            dialogs.add(dialog);
            dismissDialogs(dialogs);
        };
        boolean posted;
        try {
            posted = mRuntime.uiHandler.post(close);
        } catch (Throwable error) {
            Log.w(TAG, "Cannot post late dialog cleanup to main thread", error);
            posted = false;
        }
        if (!posted) {
            close.run();
        }
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
