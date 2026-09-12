package com.stardust.autojs.engine;

import android.util.Log;
import android.os.Process;

import com.stardust.autojs.core.looper.LooperHelper;
import com.stardust.autojs.runtime.ScriptRuntime;
import com.stardust.autojs.script.JavaScriptSource;
import com.stardust.autojs.script.QuickJsBytecodeSource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * QuickJS-backed engine for opt-in scripts. Rhino remains the compatibility
 * engine and continues to own the complete legacy Auto.js API surface.
 */
public class QuickJsJavaScriptEngine extends JavaScriptEngine {

    private static final String TAG = "QuickJsEngine";
    private static final long DEFAULT_MEMORY_LIMIT = 64L * 1024L * 1024L;
    private static final long DEFAULT_STACK_LIMIT = 2L * 1024L * 1024L;

    private final Map<String, Object> mPendingGlobals = new LinkedHashMap<>();
    private volatile long mNativeHandle;
    private volatile Thread mThread;
    private volatile QuickJsHostBridge mHostBridge;
    /** `"ui"` 模式（跑在 ScriptExecuteActivity 里）注入的宿主 Activity；普通脚本为 null。 */
    private volatile android.app.Activity mHostActivity;
    private int mOriginalThreadPriority = Process.THREAD_PRIORITY_DEFAULT;
    private boolean mPriorityRaised;
    /** forceStop() 过：用于把 native 的通用“interrupted”异常识刷为正常停止。 */
    private volatile boolean mInterruptRequested;

    @Override
    public synchronized void put(String name, Object value) {
        if (!isSupportedGlobal(value)) {
            Log.d(TAG, "Ignoring non-primitive global for QuickJS: " + name);
            return;
        }
        if (mNativeHandle == 0) {
            mPendingGlobals.put(name, value);
        } else {
            QuickJsNativeBridge.setGlobal(mNativeHandle, name, value);
        }
    }

    @Override
    public void init() {
        mThread = Thread.currentThread();
        try {
            mOriginalThreadPriority = Process.getThreadPriority(Process.myTid());
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY);
            mPriorityRaised = true;
        } catch (Throwable error) {
            Log.w(TAG, "Cannot raise QuickJS thread priority", error);
        }
        LooperHelper.prepare();

        ScriptRuntime runtime = getRuntime();
        runtime.init();
        QuickJsHostBridge hostBridge = new QuickJsHostBridge(runtime);
        mHostBridge = hostBridge;
        long handle = QuickJsNativeBridge.create(
                hostBridge,
                DEFAULT_MEMORY_LIMIT,
                DEFAULT_STACK_LIMIT
        );
        if (handle == 0) {
            throw new QuickJsException("Unable to create QuickJS runtime");
        }
        hostBridge.attachEngine(handle);
        hostBridge.setUiHostActivity(mHostActivity);
        mNativeHandle = handle;
        synchronized (this) {
            for (Map.Entry<String, Object> entry : mPendingGlobals.entrySet()) {
                QuickJsNativeBridge.setGlobal(handle, entry.getKey(), entry.getValue());
            }
            mPendingGlobals.clear();
        }
    }

    @Override
    protected Object doExecution(JavaScriptSource scriptSource) {
        long handle = mNativeHandle;
        if (handle == 0) {
            throw new IllegalStateException("QuickJS engine has not been initialized");
        }
        mInterruptRequested = false;
        try {
            // 打包加密等级 ≥ 2 的产物是 QuickJS 字节码（没有源码文本），走执行字节码。
            if (scriptSource instanceof QuickJsBytecodeSource) {
                return QuickJsNativeBridge.evaluateBytecode(handle,
                        ((QuickJsBytecodeSource) scriptSource).getBytecode(),
                        scriptSource.toString());
            }
            return QuickJsNativeBridge.evaluate(handle, scriptSource.getScript(), scriptSource.toString());
        } catch (QuickJsException error) {
            // 主动停止 / 超时会中断 JS 执行，native 侧抛的是通用 QuickJsException，
            // 这里换成引擎统一的 ScriptInterruptedException，让上层（MCP / 历史记录）
            // 能把“正常停止”和“真出错”区分开。
            if (mInterruptRequested || isInterruptMessage(error.getMessage())) {
                throw new com.stardust.autojs.runtime.exception.ScriptInterruptedException(error);
            }
            throw error;
        }
    }

    private static boolean isInterruptMessage(String message) {
        return message != null && message.contains("interrupted");
    }

    @Override
    public void forceStop() {
        mInterruptRequested = true;
        long handle = mNativeHandle;
        if (handle != 0) {
            QuickJsNativeBridge.requestInterrupt(handle);
        }
        Thread thread = mThread;
        if (thread != null) {
            thread.interrupt();
            LooperHelper.quitForThread(thread);
        }
    }

    /**
     * `"ui"` 模式的脚本跑在 {@code ScriptExecuteActivity} 里：把宿主 Activity 交给 host bridge，
     * {@code ui.layout} 就会 inflate 到 Activity 的内容视图（按 Home 退到后台、返回键才结束），
     * 而不是挂一个一直盖在最上层的系统悬浮窗。
     */
    public void setHostActivity(android.app.Activity activity) {
        mHostActivity = activity;
        QuickJsHostBridge hostBridge = mHostBridge;
        if (hostBridge != null) {
            hostBridge.setUiHostActivity(activity);
        }
    }

    @Override
    public synchronized void destroy() {
        long handle = mNativeHandle;
        mNativeHandle = 0;
        Thread thread = mThread;
        if (handle != 0) {
            // 先请求中断并等脚本线程退出，再释放 JS 运行时。
            // 脚本线程平时就停在 native 的事件循环里（界面还在就会有定时器在跑），
            // 直接 destroy 会把 context 从正在执行的线程脚下抽走（use-after-free）——
            // 「返回键结束 ui 脚本」正好会走到这条路径。
            QuickJsNativeBridge.requestInterrupt(handle);
            if (thread != null && thread != Thread.currentThread()) {
                try {
                    thread.join(2000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                if (thread.isAlive()) {
                    Log.w(TAG, "script thread did not exit before destroy; releasing runtime anyway");
                }
            }
            QuickJsNativeBridge.destroy(handle);
        }
        QuickJsHostBridge hostBridge = mHostBridge;
        mHostBridge = null;
        if (hostBridge != null) {
            hostBridge.close();
        }
        if (thread != null) {
            LooperHelper.quitForThread(thread);
        }
        if (mPriorityRaised && Thread.currentThread() == thread) {
            try {
                Process.setThreadPriority(mOriginalThreadPriority);
            } catch (Throwable error) {
                Log.w(TAG, "Cannot restore QuickJS thread priority", error);
            }
            mPriorityRaised = false;
        }
        super.destroy();
    }

    public String getEngineVersion() {
        return QuickJsNativeBridge.version();
    }

    private static boolean isSupportedGlobal(Object value) {
        return value == null || value instanceof String || value instanceof Number || value instanceof Boolean;
    }
}
