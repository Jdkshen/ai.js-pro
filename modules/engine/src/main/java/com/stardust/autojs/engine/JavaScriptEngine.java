package com.stardust.autojs.engine;

import android.os.Looper;
import android.util.Log;

import com.stardust.autojs.runtime.ScriptRuntime;
import com.stardust.autojs.script.JavaScriptSource;
import com.stardust.autojs.script.ScriptSource;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Created by Stardust on 2017/8/3.
 */

public abstract class JavaScriptEngine extends ScriptEngine.AbstractScriptEngine<JavaScriptSource> {

    private static final String TAG = "JavaScriptEngine";

    /**
     * destroy 前等待脚本 Looper 队列空闲 / 脚本线程收尾的最长时间。
     * 超时只记录日志并继续销毁，避免退出流程被脚本里的死循环/慢操作永久卡住。
     */
    public static final long SCRIPT_LOOPER_IDLE_TIMEOUT_MS = 2000L;

    private ScriptRuntime mRuntime;
    private Object mExecArgv;
    private final AtomicBoolean mDestroyStarted = new AtomicBoolean(false);

    @Override
    public Object execute(JavaScriptSource scriptSource) {
        if ((scriptSource.getExecutionMode() & JavaScriptSource.EXECUTION_MODE_AUTO) != 0) {
            getRuntime().ensureAccessibilityServiceEnabled();
        }
        return doExecution(scriptSource);
    }

    protected abstract Object doExecution(JavaScriptSource scriptSource);

    public ScriptRuntime getRuntime() {
        return mRuntime;
    }

    public void setRuntime(ScriptRuntime runtime) {
        if (mRuntime != null) {
            throw new IllegalStateException("a runtime has been set");
        }
        mRuntime = runtime;
        mRuntime.engines.setCurrentEngine(this);
        put("runtime", runtime);
    }

    public void emit(String eventName, Object... args) {
        mRuntime.timers.getMainTimer().postDelayed(() -> mRuntime.events.emit(eventName, args), 0);
    }

    public ScriptSource getSource() {
        return (ScriptSource) getTag(TAG_SOURCE);
    }

    public void setExecArgv(Object execArgv) {
        if (mExecArgv != null) {
            return;
        }
        mExecArgv = execArgv;
    }

    public Object getExecArgv() {
        return mExecArgv;
    }

    /**
     * 销毁引擎。
     *
     * <p>与旧实现的区别：不再在任意线程上立刻执行 {@code runtime.onExit()}（其中的
     * {@code events.emit("exit")} 会触发第三方 native UI 的 nativeShutdown），而是：
     * <ol>
     *   <li>跨线程销毁时先等脚本线程退出当前正在执行的帧/回调（LoopBased/Rhino 有 thread）；</li>
     *   <li>再等脚本 Looper 队列空闲（FIFO 屏障）；</li>
     *   <li>然后才执行 {@code runtime.onExit()}；onExit 内部还会把 exit 事件再投递到
     *       脚本 Looper 串行执行。</li>
     * </ol>
     * 这样 nativeShutdown/DestroyContext 不会与 nativeNewFrame/nativeRender/nativeButton
     * 重叠（ImGui 桥 GImGui=NULL SIGSEGV 的修复，见
     * docs/reports/2026-10-17_ImGui桥native上下文定位.md §3.4/§6 #1）。
     *
     * <p>这里不使用 synchronized：等待脚本线程/Looper 期间若持有引擎锁，脚本线程在退出
     * 路径上再调用引擎方法会互相等待（只能靠超时解开）；改用 AtomicBoolean 保证只销毁一次，
     * 把等待放在锁外。等待都有上限（{@link #SCRIPT_LOOPER_IDLE_TIMEOUT_MS}），不会卡死退出。
     */
    @Override
    public void destroy() {
        if (!mDestroyStarted.compareAndSet(false, true)) {
            return;
        }
        ScriptRuntime runtime = mRuntime;
        if (runtime == null) {
            Log.w(TAG, "destroy before setRuntime: " + this);
            super.destroy();
            return;
        }
        awaitScriptThreadIfNeeded(SCRIPT_LOOPER_IDLE_TIMEOUT_MS);
        boolean idle = runtime.awaitScriptLooperIdle(SCRIPT_LOOPER_IDLE_TIMEOUT_MS);
        if (!idle) {
            Log.w(TAG, "script looper not idle after " + SCRIPT_LOOPER_IDLE_TIMEOUT_MS + "ms, destroy anyway");
        }
        // onExit 内部会把 events.emit("exit") 投递回脚本 Looper 串行执行并等待（有超时/回退），
        // 保证 exit 监听里的 nativeShutdown 不会插进渲染帧中间。
        runtime.onExit();
        super.destroy();
    }

    /**
     * 跨线程销毁时，先等脚本线程退出当前正在执行的帧/回调。
     * 调用线程就是脚本线程、或引擎没有公开线程对象（如 QuickJS/Node24，其 destroy
     * 自己已做线程收尾）时直接跳过。
     */
    private void awaitScriptThreadIfNeeded(long timeoutMs) {
        if (!(this instanceof RhinoJavaScriptEngine)) {
            return;
        }
        Thread scriptThread;
        try {
            scriptThread = ((RhinoJavaScriptEngine) this).getThread();
        } catch (Throwable ignored) {
            // RhinoJavaScriptEngine.thread 是 lateinit，init 之前访问会抛异常：没有线程可等。
            return;
        }
        if (scriptThread == null || scriptThread == Thread.currentThread()) {
            return;
        }
        // 在 Android 主线程上销毁（例如 Activity.onDestroy / 服务主线程收尾）时压短等待，
        // 避免退出清理把主线程卡到 ANR；主线程模式下通常 Looper 就是主 Looper，不会走到这里。
        long effectiveTimeout = timeoutMs;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            effectiveTimeout = Math.min(timeoutMs, ScriptRuntime.MAX_MAIN_THREAD_WAIT_MS);
        }
        try {
            scriptThread.join(effectiveTimeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "interrupted while waiting script thread to finish");
            return;
        }
        if (scriptThread.isAlive()) {
            Log.w(TAG, "script thread still alive after " + effectiveTimeout + "ms, continue destroy");
        }
    }

    /** 等脚本 Looper 队列空闲（供 RunnableScriptExecution 在 destroy 前后显式调用）。 */
    public boolean awaitScriptLooperIdle(long timeoutMs) {
        ScriptRuntime runtime = mRuntime;
        return runtime == null || runtime.awaitScriptLooperIdle(timeoutMs);
    }

    @Override
    public String toString() {
        return "ScriptEngine@" + Integer.toHexString(hashCode()) + "{" +
                "id=" + getId() + "," +
                "source='" + getTag(TAG_SOURCE) + "'," +
                "cwd='" + cwd() + "'" +
                "}";
    }
}
