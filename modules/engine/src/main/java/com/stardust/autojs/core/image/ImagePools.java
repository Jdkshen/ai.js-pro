package com.stardust.autojs.core.image;

import android.util.Log;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * {@link ImagePool} 的进程级登记表 + **未回收护栏**。
 *
 * <p>为什么要有护栏：作用域池靠调用方自觉 {@code close()}；引擎内部的图像代码（找色 / 模板匹配 /
 * 特征匹配）一旦在某条异常路径上提前返回，池就可能被忘在一边，中间 Mat 会一直占着 native 内存
 * 直到 GC。这里给出一个**明确的脚本结束时机**：{@link #drainAll(String)} 强制回收所有仍打开池的
 * 未归还资源，并打印「谁没还 + 可抄提示」。
 *
 * <p>与已有的两套护栏的关系（不重复、不覆盖）：
 * <ul>
 *   <li>{@code ResourceMonitor}（common）：管 {@code Mat}/{@code MatOfPoint} 的分配栈与 finalize
 *       报警，是「对象级」的最后一道；</li>
 *   <li>{@code MemoryLeakDetector}（runtime/api）：管脚本可见的 {@code ImageWrapper}；</li>
 *   <li>本类：管「作用域池里没归还」的**结构化**信号（谁借的、池是否关闭、上限是否被突破），
 *       比 finalize 更早、更有指向性。</li>
 * </ul>
 *
 * <p>诊断代价：{@link #allocationSite()} 每次登记抓一次当前堆栈（截断到 2 帧），可用
 * {@link #setDiagnosticsEnabled(boolean)} 关掉（关掉后仍会照常登记与强制回收，只是报警里没有
 * 分配点）。
 */
public final class ImagePools {

    /** 真机验收用的固定 logcat tag。 */
    public static final String TAG = "ImagePool";

    private static final Object LOCK = new Object();
    private static final Set<ImagePool> POOLS = new LinkedHashSet<>();
    private static volatile boolean sDiagnosticsEnabled = true;
    private static volatile Consumer<String> sReporter = message -> Log.w(TAG, message);

    private ImagePools() {
    }

    static void register(ImagePool pool) {
        synchronized (LOCK) {
            POOLS.add(pool);
        }
    }

    static void unregister(ImagePool pool) {
        synchronized (LOCK) {
            POOLS.remove(pool);
        }
    }

    /** 仍打开的池数量（close 后不再计入）。 */
    public static int openPoolCount() {
        synchronized (LOCK) {
            return POOLS.size();
        }
    }

    /** 进程内全部池的未归还资源总数。 */
    public static int pendingCount() {
        int total = 0;
        for (ImagePool pool : snapshot()) {
            total += pool.pendingCount();
        }
        return total;
    }

    /** 进程内全部池累计强制释放数。 */
    public static long forcedCount() {
        long total = 0;
        for (ImagePool pool : snapshot()) {
            total += pool.forced();
        }
        return total;
    }

    /** 一行池统计（验收口径）：{@code {"pools":1,"pending":0,"forced":2,"borrowed":2,"returned":0,"overflow":0}}。 */
    public static String stats() {
        List<ImagePool> pools = snapshot();
        int pending = 0;
        long forced = 0;
        long borrowed = 0;
        long returned = 0;
        long overflow = 0;
        for (ImagePool pool : pools) {
            pending += pool.pendingCount();
            forced += pool.forced();
            borrowed += pool.borrowed();
            returned += pool.returned();
            overflow += pool.overflow();
        }
        return "{\"pools\":" + pools.size()
                + ",\"pending\":" + pending
                + ",\"forced\":" + forced
                + ",\"borrowed\":" + borrowed
                + ",\"returned\":" + returned
                + ",\"overflow\":" + overflow + "}";
    }

    /**
     * 未回收护栏：把所有仍打开池里**没归还**的资源强制 release，并逐池打印警告 + 可抄提示。
     *
     * <p>调用时机（引擎既有时序点）：{@code ScriptRuntime.onExit → closeCleanables →
     * ScriptRuntimeGlobals.close() → ColorMapping.recycleSingleton()}（与 {@code
     * MemoryLeakDetector.reset()} 同一处）；也可由任何宿主/测试显式调用。
     *
     * @param reason 出现在日志里的时机描述（例如 {@code "script-exit"}）
     * @return 被强制回收的资源总数
     */
    public static int drainAll(@Nullable String reason) {
        String where = (reason == null || reason.isEmpty()) ? "unknown" : reason;
        int forced = 0;
        for (ImagePool pool : snapshot()) {
            List<String> sites = pool.drainUnreturned();
            if (sites.isEmpty()) {
                continue;
            }
            forced += sites.size();
            report("[ImagePool] 未归还：脚本结束(" + where + ")时仍有 " + sites.size()
                    + " 个图像资源没有 recycle()，已强制 release；分配点：" + String.join(" | ", sites));
            report("[ImagePool] 可抄提示：把作用域写成 try (ImagePool pool = new ImagePool()) { … }，"
                    + "或对每个 make()/mat()/matOfPoint() 的返回值调用 pool.recycle(x)；"
                    + "池统计 ImagePools.stats()。");
        }
        return forced;
    }

    /** 是否采集分配点（关掉只省钱，不影响回收与强制回收）。 */
    public static boolean isDiagnosticsEnabled() {
        return sDiagnosticsEnabled;
    }

    public static void setDiagnosticsEnabled(boolean enabled) {
        sDiagnosticsEnabled = enabled;
    }

    /** 日志出口；传 {@code null} 恢复默认（logcat {@value #TAG}）。 */
    public static void setReporter(@Nullable Consumer<String> reporter) {
        sReporter = reporter != null ? reporter : message -> Log.w(TAG, message);
    }

    static void report(String message) {
        Consumer<String> reporter = sReporter;
        if (reporter == null) {
            return;
        }
        try {
            reporter.accept(message);
        } catch (Throwable ignored) {
            // 日志出口不可用（例如宿主 console 已销毁 / JVM 单测里的 android.jar stub）不能影响回收
        }
    }

    /**
     * 当前分配点：抓一次堆栈，跳过池自身的帧，最多保留 2 帧。
     *
     * @return {@code 类名.方法(文件:行)} 形式的字符串；关闭诊断时返回 {@code null}
     */
    @Nullable
    static String allocationSite() {
        if (!sDiagnosticsEnabled) {
            return null;
        }
        StackTraceElement[] frames = new Throwable().getStackTrace();
        StringBuilder builder = new StringBuilder();
        int kept = 0;
        for (StackTraceElement frame : frames) {
            String className = frame.getClassName();
            if (className.equals(ImagePool.class.getName()) || className.equals(ImagePools.class.getName())) {
                continue;
            }
            if (kept > 0) {
                builder.append(" <- ");
            }
            builder.append(className).append('.').append(frame.getMethodName())
                    .append('(').append(frame.getFileName()).append(':').append(frame.getLineNumber()).append(')');
            if (++kept >= 2) {
                break;
            }
        }
        return builder.length() == 0 ? null : builder.toString();
    }

    private static List<ImagePool> snapshot() {
        synchronized (LOCK) {
            return new ArrayList<>(POOLS);
        }
    }
}
