package com.jdkshen.xiangqi.plugin;

/**
 * libxiangqi_engine.so 的 JNI 门面。
 *
 * <p>引擎源码与宿主随包的 libpikafish.so 完全同源
 * （{@code modules/engine/src/main/cpp/pikafish/pikafish_jni.cpp}，JNI 导出名由编译期宏
 * {@code PIKAFISH_JNI_CLASS} 决定），只是换了个库名重新编了一份。
 *
 * <p>⚠️ 为什么必须另起库名：插件与宿主是两个 APK，同名 .so 在同一个进程里会被 Android 链接器
 * 按 SONAME 复用，插件就可能拿到宿主那份（它的 JNI 符号指向宿主类），从而
 * {@code UnsatisfiedLinkError}。因此这里固定用 {@code libxiangqi_engine.so}。
 *
 * <p>目前只有 arm64-v8a 一份构建；加载失败会记在 {@link #isAvailable()} / {@link #loadError()}，
 * 让脚本拿到一条能看懂的报错，而不是在调用点崩。
 */
public final class NativeEngine {

    private static final boolean LOADED;
    private static final String LOAD_ERROR;

    static {
        boolean loaded = false;
        String error = "";
        try {
            System.loadLibrary("xiangqi_engine");
            loaded = true;
        } catch (Throwable failure) {
            error = failure.toString();
        }
        LOADED = loaded;
        LOAD_ERROR = error;
    }

    private NativeEngine() {
    }

    /** 本设备能不能用（.so 是否加载成功）。 */
    public static boolean isAvailable() {
        return LOADED;
    }

    /** 加载失败原因（成功时是空串）。 */
    public static String loadError() {
        return LOAD_ERROR;
    }

    public static native boolean nativeStart(String evalFilePath, int threads, int hashMb, int contempt);

    public static native int nativeConfiguredThreads();

    /** 启动时从 "uci" 回复抓到的 "option name ..." 行（换行分隔）；未启动为空串。 */
    public static native String nativeOptions();

    /** 算一步棋（阻塞）：返回 "info ..." 行 + "bestmove ..." 行；解析在上层。 */
    public static native String nativeAnalyze(String fen, long movetimeMs, long timeoutMs, int multiPv, int depth);

    /** 从已确认着法历史（{@code position fen ... moves ...}）搜索。 */
    public static native String nativeAnalyzePosition(String positionCommand, long movetimeMs,
                                                      long timeoutMs, int multiPv, int depth);

    public static native void nativeStop();

    /** 仅当当前搜索由指定 Android 线程发起时停止。 */
    public static native boolean nativeStopForTid(int tid);

    /** 退出引擎线程并释放内存（幂等）。 */
    public static native void nativeQuit();

    public static native boolean nativeIsRunning();
}
