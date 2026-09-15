package com.stardust.autojs.core.inputevent;

import android.content.Context;
import android.view.ViewConfiguration;

import com.stardust.autojs.core.util.ProcessShell;
import com.stardust.autojs.runtime.ScriptRuntime;
import com.stardust.autojs.runtime.api.ShizukuShell;
import com.stardust.util.ScreenMetrics;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * PDF §2.2.3（第 40~45 页）：{@code new RootAutomator2([options])}。
 *
 * <p>选项（与文档一致）：{@code adb} 默认 false；不指定 adb 时 {@code root} 默认 true。
 * <ul>
 *   <li><b>root 通道</b>：复用仓库已有的 {@link RootAutomator}（打包的输入事件二进制 + su），
 *       支持多点触控。</li>
 *   <li><b>adb 通道</b>：走 Shizuku/adb shell 的 {@code input} 命令；单指触摸可用
 *       （{@code input motionevent}），<b>不支持多点触控</b>（数组形式的 touchDown/Move/Up
 *       会明确报错，而不是静默忽略）。</li>
 * </ul>
 *
 * <p>⚠️ 没有 root / 没有运行中的 Shizuku 时，本类<b>构造</b>不会失败，
 * 但真正注入事件时会抛出带中文说明的异常（不静默返回成功）。
 */
public class RootAutomator2 implements AutoCloseable {

    private static final int SHELL_TIMEOUT_MILLIS = 5000;
    /** 文档：长按时长 = ViewConfiguration.getLongPressTimeout() + 100 毫秒。 */
    private static final int LONG_PRESS_EXTRA_MILLIS = 100;

    /** 当前进程内未 exit() 的 RootAutomator2 数量（探针用）。 */
    private static final java.util.concurrent.atomic.AtomicInteger sLiveCount =
            new java.util.concurrent.atomic.AtomicInteger();

    public static int liveCount() {
        return sLiveCount.get();
    }

    private final ScriptRuntime mRuntime;
    private final Context mContext;
    private final boolean mUseAdb;
    private ScreenMetrics mScreenMetrics;
    private int mDesignWidth;
    private int mDesignHeight;
    private RootAutomator mRootAutomator;
    private boolean mClosed;

    public RootAutomator2(Context context, boolean useAdb) {
        this(null, context, useAdb);
    }

    /**
     * @param runtime 传 runtime 时，脚本没调 {@code exit()} 也会在脚本退出时自动收尾
     *                （root 通道会挂着 su 壳进程，adb 通道会挂着未抬起的“手指”）
     */
    public RootAutomator2(ScriptRuntime runtime, Context context, boolean useAdb) {
        mRuntime = runtime;
        mContext = context;
        mUseAdb = useAdb;
        sLiveCount.incrementAndGet();
        if (runtime != null) {
            runtime.addCleanable(this);
        }
    }

    public boolean isAdb() {
        return mUseAdb;
    }

    public void setScreenMetrics(int width, int height) {
        if (mScreenMetrics == null) {
            mScreenMetrics = new ScreenMetrics();
        }
        mScreenMetrics.setScreenMetrics(width, height);
        mDesignWidth = width;
        mDesignHeight = height;
    }

    // ------------------------------------------------------------------ 同步操作

    public void tap(int x, int y) throws IOException {
        if (mUseAdb) {
            execAdb("input tap " + scaleX(x) + " " + scaleY(y));
            return;
        }
        root().tap(x, y);
    }

    public void longPress(int x, int y) throws IOException {
        longPress(x, y, defaultId());
    }

    public void longPress(int x, int y, int id) throws IOException {
        press(x, y, ViewConfiguration.getLongPressTimeout() + LONG_PRESS_EXTRA_MILLIS, id);
    }

    public void press(int x, int y, int duration) throws IOException {
        press(x, y, duration, defaultId());
    }

    public void press(int x, int y, int duration, int id) throws IOException {
        if (mUseAdb) {
            // input swipe 起点=终点 等价于长按/按住不动
            execAdb("input swipe " + scaleX(x) + " " + scaleY(y) + " " + scaleX(x) + " " + scaleY(y)
                    + " " + Math.max(1, duration));
            return;
        }
        root().press(x, y, duration, id);
    }

    public void swipe(int x1, int y1, int x2, int y2, int duration) throws IOException {
        swipe(x1, y1, x2, y2, duration, defaultId());
    }

    public void swipe(int x1, int y1, int x2, int y2, int duration, int id) throws IOException {
        if (mUseAdb) {
            execAdb("input swipe " + scaleX(x1) + " " + scaleY(y1) + " " + scaleX(x2) + " " + scaleY(y2)
                    + " " + Math.max(1, duration));
            return;
        }
        root().swipe(x1, y1, x2, y2, duration, id);
    }

    public void swipe(int x1, int y1, int x2, int y2) throws IOException {
        swipe(x1, y1, x2, y2, 300);
    }

    // ------------------------------------------------------------ 异步触摸（单指）

    public void touchDown(int x, int y) throws IOException {
        touchDown(x, y, defaultId());
    }

    public void touchDown(int x, int y, int id) throws IOException {
        if (mUseAdb) {
            execAdb("input motionevent DOWN " + scaleX(x) + " " + scaleY(y));
            return;
        }
        root().touchDown(x, y, id);
    }

    public void touchMove(int x, int y) throws IOException {
        touchMove(x, y, defaultId());
    }

    public void touchMove(int x, int y, int id) throws IOException {
        if (mUseAdb) {
            execAdb("input motionevent MOVE " + scaleX(x) + " " + scaleY(y));
            return;
        }
        root().touchMove(x, y, id);
    }

    public void touchUp() throws IOException {
        touchUp(defaultId());
    }

    public void touchUp(int id) throws IOException {
        if (mUseAdb) {
            execAdb("input motionevent UP " + scaleX(0) + " " + scaleY(0));
            return;
        }
        root().touchUp(id);
    }

    // ------------------------------------------------- 多点触控：数组形式在 JS 层展开

    public void flush() throws IOException {
        // adb 通道的 input 命令本身就是同步的；root 通道还没发过事件时也没有要等的队列
        if (mUseAdb || mRootAutomator == null) {
            return;
        }
        mRootAutomator.sendSync();
    }

    public void exit() throws IOException {
        if (mClosed) {
            return;
        }
        mClosed = true;
        sLiveCount.decrementAndGet();
        if (mRuntime != null) {
            mRuntime.removeCleanable(this);
        }
        if (!mUseAdb && mRootAutomator != null) {
            mRootAutomator.exit();
        }
    }

    /** 脚本退出时的兜底收尾（等同 {@link #exit()}）。 */
    @Override
    public void close() {
        try {
            exit();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------------------------------------------------------------------- 内部

    private int defaultId() throws IOException {
        return mUseAdb ? 0 : root().getDefaultId();
    }

    private RootAutomator root() throws IOException {
        if (mClosed) {
            throw new IllegalStateException("RootAutomator2 已经 exit()");
        }
        if (mRootAutomator == null) {
            ensureRootUsable();
            mRootAutomator = new RootAutomator(mContext, null, true);
            if (mScreenMetrics != null) {
                mRootAutomator.setScreenMetrics(mDesignWidth, mDesignHeight);
            }
        }
        return mRootAutomator;
    }

    /**
     * 先确认 root 真的可用，避免 {@link RootAutomator} 在等壳回调时把脚本卡死
     * （没有 su 的设备上会一直等不到 ready）。
     */
    private void ensureRootUsable() {
        boolean usable;
        try {
            usable = ProcessShell.exec(new String[]{"id"}, true).code == 0;
        } catch (Throwable e) {
            usable = false;
        }
        if (!usable) {
            throw new IllegalStateException(
                    "root 通道不可用（设备没有 root 或没有授予 su）；"
                            + "要免 root 用 input 注入请改用 new RootAutomator2({adb: true})（需要 Shizuku）");
        }
    }

    private void execAdb(String command) throws IOException {
        if (mClosed) {
            throw new IllegalStateException("RootAutomator2 已经 exit()");
        }
        if (!ShizukuShell.isAvailable()) {
            throw new IllegalStateException("adb 通道需要运行中的 Shizuku（或用 adb 授权）："
                    + "当前 Shizuku 未运行，请先启动 Shizuku 并授予 AI.js Pro 权限");
        }
        if (!ShizukuShell.hasPermission()) {
            throw new IllegalStateException("adb 通道需要 Shizuku 权限：请在弹出的授权框里允许 AI.js Pro");
        }
        ShizukuShell.Result result = ShizukuShell.execute(command, SHELL_TIMEOUT_MILLIS, 0);
        if (result.code != 0) {
            String message = result.error == null ? "" : result.error.trim();
            throw new IllegalStateException("adb 通道执行失败（" + command + "）："
                    + (message.isEmpty() ? "退出码 " + result.code : message));
        }
    }

    private int scaleX(int x) {
        return mScreenMetrics == null ? x : mScreenMetrics.scaleX(x);
    }

    private int scaleY(int y) {
        return mScreenMetrics == null ? y : mScreenMetrics.scaleY(y);
    }
}
