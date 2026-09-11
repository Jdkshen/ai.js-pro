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

    /**
     * 把脚本编译成 QuickJS 字节码（打包加密等级 ≥ 2 用）。
     *
     * <p>不依赖引擎句柄：打包发生在主应用进程里，那里不一定有正在运行的引擎，
     * 原生侧会临时建一套 runtime/context 编译完就释放。
     */
    static native byte[] compileToBytecode(String source, String sourceName);

    /** 执行 QuickJS 字节码（编译产物的载荷）。 */
    static native Object evaluateBytecode(long handle, byte[] code, String sourceName);

    /** Java → JS 同步回调：调用脚本里注册的 `__aiInvokeCallback(id, argsJson)`。 */
    static native String invokeJsCallback(long handle, long callbackId, String argsJson);

    static native void setGlobal(long handle, String name, Object value);

    static native void requestInterrupt(long handle);

    static native void destroy(long handle);

    static native String version();
}
