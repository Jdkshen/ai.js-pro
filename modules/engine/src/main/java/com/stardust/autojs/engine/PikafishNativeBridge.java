package com.stardust.autojs.engine;

/**
 * libpikafish.so 的 JNI 门面：中国象棋 NNUE 引擎（third-party/Pikafish）。
 *
 * <p>引擎本体跑在 native 侧的独立线程上（UCI 循环 + 标准输入输出重定向），
 * 这里的方法就是「投喂一条命令 / 等待一条结果」的同步封装：
 * {@link #nativeStart} 建会话（uci → setoption → isready），
 * {@link #nativeAnalyze} 投喂当前 FEN，{@link #nativeAnalyzePosition} 投喂已确认着法历史；两者都阻塞等待 bestmove。
 *
 * <p>会话是**进程级单例**（native 侧同一时刻只支持一个引擎实例），
 * 多个脚本引擎（含 threads worker）共享它，native 侧用互斥锁串行化 analyze。
 * 所以 {@link #nativeQuit} 只在真的要卸载引擎时调（本类不随 QuickJsHostBridge 关闭自动退出）。
 *
 * <p>目前只有 arm64-v8a 随包这个 .so；其它 ABI 上加载失败会记在 {@link #isAvailable()} /
 * {@link #loadError()} 里，让脚本拿到一条能看懂的报错，而不是 UnsatisfiedLinkError。
 */
public final class PikafishNativeBridge {

    private static final boolean LOADED;
    private static final String LOAD_ERROR;

    static {
        boolean loaded = false;
        String error = "";
        try {
            System.loadLibrary("pikafish");
            loaded = true;
        } catch (Throwable failure) {
            error = failure.toString();
        }
        LOADED = loaded;
        LOAD_ERROR = error;
    }

    private PikafishNativeBridge() {
    }

    /** 本设备能不能用（.so 是否加载成功）。 */
    public static boolean isAvailable() {
        return LOADED;
    }

    /** 加载失败原因（成功时是空串）。 */
    public static String loadError() {
        return LOAD_ERROR;
    }

    /**
     * 启动（或复用）引擎会话；已启动但参数变化时，在两次搜索之间重新设置 UCI 选项
     * （Threads / Hash / Contempt / EvalFile）。
     *
     * @param evalFilePath NNUE 权重文件绝对路径
     * @param threads      搜索线程数（native 侧下限 1、默认 2）
     * @param hashMb       置换表 MB（native 侧下限 16、默认 64）
     * @param contempt     避和倾向（自编译补丁选项；-200..200，0 = 关，正数 = 根方避开和棋线）
     */
    public static native boolean nativeStart(String evalFilePath, int threads, int hashMb, int contempt);

    /** 最近一次收到 readyok 确认的原生 Threads 值；未启动时为 0。 */
    public static native int nativeConfiguredThreads();

    /**
     * 启动时从 "uci" 回复抓到的选项行（每行一条 "option name ..."，换行分隔）。
     * 未启动或旧 .so 为空串——调用方用 try/catch 包住即可。
     */
    public static native String nativeOptions();

    /**
     * 算一步棋（阻塞）。
     *
     * @param multiPv 同时分析的着法路数（1 = 只算最好的一手；&gt;1 用于避开排除表挑次优）
     * @return 所有带 pv 的 info 行 + bestmove 行（按行拼接）；失败时 "error: ..."
     */
    public static native String nativeAnalyze(String fen, long movetimeMs, long timeoutMs, int multiPv, int depth);

    /** Search from a validated UCI position command with confirmed moves. */
    public static native String nativeAnalyzePosition(String positionCommand, long movetimeMs,
                                                      long timeoutMs, int multiPv, int depth);

    /** 打断正在跑的搜索（go 会以 bestmove 正常收尾，analyze 随即返回）。 */
    public static native void nativeStop();

    /** 仅当当前搜索由指定 Android 线程发起时停止；避免停掉其他脚本的搜索。 */
    public static native boolean nativeStopForTid(int tid);

    /** 退出引擎线程并释放内存（幂等）。 */
    public static native void nativeQuit();

    public static native boolean nativeIsRunning();
}
