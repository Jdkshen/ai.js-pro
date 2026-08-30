package com.stardust.autojs.engine;

import android.util.Log;

import com.stardust.autojs.core.looper.LooperHelper;
import com.stardust.autojs.runtime.ScriptRuntime;
import com.stardust.autojs.script.JavaScriptSource;

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
        return QuickJsNativeBridge.evaluate(handle, scriptSource.getScript(), scriptSource.toString());
    }

    @Override
    public void forceStop() {
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

    @Override
    public synchronized void destroy() {
        long handle = mNativeHandle;
        mNativeHandle = 0;
        if (handle != 0) {
            QuickJsNativeBridge.destroy(handle);
        }
        QuickJsHostBridge hostBridge = mHostBridge;
        mHostBridge = null;
        if (hostBridge != null) {
            hostBridge.close();
        }
        Thread thread = mThread;
        if (thread != null) {
            LooperHelper.quitForThread(thread);
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
