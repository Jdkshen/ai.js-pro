package com.stardust.autojs.engine;

import java.nio.ByteBuffer;

final class QuickJsNativeBridge {

    static {
        System.loadLibrary("quickjs");
        System.loadLibrary("quickjs_jni");
    }

    private QuickJsNativeBridge() {
    }

    static native long create(QuickJsHostBridge hostBridge, long memoryLimitBytes, long stackLimitBytes);

    static native long createNativeFrame(long engineHandle, ByteBuffer rgbaBuffer,
                                         int width, int height, int rowStride, int pixelStride,
                                         int targetShortEdge);

    static native Object evaluate(long handle, String source, String sourceName);

    /** Java → JS 同步回调：调用脚本里注册的 `__aiInvokeCallback(id, argsJson)`。 */
    static native String invokeJsCallback(long handle, long callbackId, String argsJson);

    static native void setGlobal(long handle, String name, Object value);

    static native void requestInterrupt(long handle);

    static native void destroy(long handle);

    static native String version();
}
