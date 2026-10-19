package com.jdkshen.aijspro.flutterpoc.scriptproc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 隔离脚本进程（{@code :script} / {@code :native}）到主进程的 console 批量缓冲
 * （纯 Java，无 Android 依赖，便于 JVM 单测）。
 *
 * <p>为什么需要它：脚本一行 console 就回一条 Binder 消息会把跨进程通道刷爆（尤其
 * {@code for} 循环里 console.log 的脚本）。{@link ScriptHostService} 和 {@code :native} 的
 * {@code NativeScriptService} 各自的 ConsoleForwarder 都复用本类：每次收到
 * {@code ConsoleImpl.LogEntry} 先塞进本缓冲，再按两种条件之一 flush：
 * <ul>
 *   <li>定时：200ms 一次（见 ScriptHostService.CONSOLE_FLUSH_INTERVAL_MS）；</li>
 *   <li>阈值：累计 {@value #MAX_LINES} 行或 {@value #MAX_CHARS} 字符时立即 flush，避免攒爆内存/延迟过高。</li>
 * </ul>
 *
 * <p>格式约定：
 * <ul>
 *   <li>每次 {@link #append} 是一段文本；与上一段之间补一个换行（上一段已以换行结尾则不补），
 *       多条 console 输出拼成一段多行文本，一次 Binder 消息传完；</li>
 *   <li>{@link Batch#text} + {@link Batch#level} 是旧客户端兼容字段：整批拼接文本 + 批次内最高等级
 *       （android.util.Log 数值越大越严重）；只按它给整批着色会把 V/D/I/W/E/A 混批全部染成最高级，
 *       所以新协议另带逐段快照；</li>
 *   <li>{@link Batch#segmentTexts} / {@link Batch#segmentLevels} / {@link Batch#segmentTimes}
 *       按 append 顺序记录每段文本、原始等级与源时间（等长），主进程据此逐段落日志；段文本可能本身含多行；</li>
 *   <li>源时间在 append 时确定（未显式传入则取 {@link System#currentTimeMillis()}），随段保存，
 *       drain 时不会统一成 flush 时刻；同一等级不同时间也必须各成一段；</li>
 *   <li>单段文本超过 {@value #MAX_CHARS} 时截断并加中文提示，保证单条 Binder 消息不会超事务上限。</li>
 * </ul>
 */
public final class ConsoleBatch {

    /** 单批最大字符数（Binder 事务约 1MB，这里远低于上限，避免日志刷屏卡住脚本线程）。 */
    static final int MAX_CHARS = 8000;
    /** 单批最大行数。 */
    static final int MAX_LINES = 40;
    /** 单批最大段数：每段至少占一行，所以不会超过 40（供主进程校验可选 metadata）。 */
    public static final int MAX_SEGMENTS = MAX_LINES;

    /** 一次 flush 出来的批量文本、逐段等级与源时间（跨包复用，字段只读；数组是 drain 时的快照）。 */
    public static final class Batch {
        /** 旧客户端兼容：整批拼接文本（多行用 {@code \n} 分隔）。 */
        public final String text;
        /** 旧客户端兼容：批次内最高等级（android.util.Log 数值）。 */
        public final int level;
        /** 每段 append 的文本（与原始 content 相同，可含多行；按 append 顺序，非 null）。 */
        public final String[] segmentTexts;
        /** 每段对应的原始等级（与 {@link #segmentTexts} 等长同序）。 */
        public final int[] segmentLevels;
        /** 每段 append 的源时间（Unix 毫秒；与 {@link #segmentTexts} 等长同序，不是 flush 时刻）。 */
        public final long[] segmentTimes;

        Batch(String text, int level, String[] segmentTexts, int[] segmentLevels, long[] segmentTimes) {
            this.text = text;
            this.level = level;
            this.segmentTexts = segmentTexts;
            this.segmentLevels = segmentLevels;
            this.segmentTimes = segmentTimes;
        }

        public boolean isEmpty() {
            return segmentTexts == null || segmentTexts.length == 0;
        }
    }

    private final StringBuilder mBuffer = new StringBuilder();
    private final List<String> mSegmentTexts = new ArrayList<>();
    private final List<Integer> mSegmentLevels = new ArrayList<>();
    private final List<Long> mSegmentTimes = new ArrayList<>();
    private int mLines;
    private int mLevel;

    /**
     * 追加一段 console 输出，源时间取当前系统时间。
     *
     * <p>等价于 {@link #append(int, CharSequence, long)} 并传入
     * {@link System#currentTimeMillis()}；每次 append 都记录自己的时间，不会使用 flush 时刻。
     *
     * @param level   android.util.Log 等级（可已归一化，也可传原始值；本类只取最大值）
     * @param content 输出文本（可为多行）
     * @return 达到阈值时返回「应立即发送」的批次（缓冲已被取走）；未达阈值返回 null（等定时 flush）
     */
    public synchronized Batch append(int level, CharSequence content) {
        return append(level, content, System.currentTimeMillis());
    }

    /**
     * 追加一段带明确源时间的 console 输出。
     *
     * <p>{@code time} 在该段 append 时写入 {@link Batch#segmentTimes}；drain 只复制快照，不会把整批
     * 改成 flush 时刻。即使两段等级相同，只要时间不同也各自成段、各自保留时间。
     *
     * @param level   android.util.Log 等级（可已归一化，也可传原始值；本类只取最大值）
     * @param content 输出文本（可为多行）
     * @param time    源时间，Unix 毫秒（与 {@link System#currentTimeMillis()} 同基准）
     * @return 达到阈值时返回「应立即发送」的批次（缓冲已被取走）；未达阈值返回 null（等定时 flush）
     */
    public synchronized Batch append(int level, CharSequence content, long time) {
        if (content == null) {
            return null;
        }
        String text = content.toString();
        if (text.length() > MAX_CHARS) {
            int end = MAX_CHARS;
            if (Character.isHighSurrogate(text.charAt(end - 1))
                    && Character.isLowSurrogate(text.charAt(end))) end--;
            text = text.substring(0, end) + "\n…（单条日志过长，已截断）";
        }
        if (!mSegmentTexts.isEmpty()
                && !mSegmentTexts.get(mSegmentTexts.size() - 1).endsWith("\n")) {
            mBuffer.append('\n');
        }
        mBuffer.append(text);
        mSegmentTexts.add(text);
        mSegmentLevels.add(level);
        mSegmentTimes.add(time);
        mLines += 1 + countNewlines(text);
        mLevel = Math.max(mLevel, level);
        if (mBuffer.length() >= MAX_CHARS || mLines >= MAX_LINES) {
            return drainLocked();
        }
        return null;
    }

    /** 定时 flush：取走当前缓冲；没有内容返回 null。 */
    public synchronized Batch drain() {
        return drainLocked();
    }

    private Batch drainLocked() {
        if (mSegmentTexts.isEmpty()) {
            clearLocked();
            return null;
        }
        String text = mBuffer.toString();
        int level = mLevel;
        String[] segmentTexts = mSegmentTexts.toArray(new String[0]);
        int[] segmentLevels = new int[mSegmentLevels.size()];
        for (int i = 0; i < segmentLevels.length; i++) {
            segmentLevels[i] = mSegmentLevels.get(i);
        }
        long[] segmentTimes = new long[mSegmentTimes.size()];
        for (int i = 0; i < segmentTimes.length; i++) {
            segmentTimes[i] = mSegmentTimes.get(i);
        }
        clearLocked();
        return new Batch(text, level, segmentTexts, segmentLevels, segmentTimes);
    }

    private void clearLocked() {
        mBuffer.setLength(0);
        mSegmentTexts.clear();
        mSegmentLevels.clear();
        mSegmentTimes.clear();
        mLines = 0;
        mLevel = 0;
    }

    private static int countNewlines(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }

    /** 仅用于日志调试。 */
    @Override
    public synchronized String toString() {
        return String.format(Locale.US, "ConsoleBatch{chars=%d, lines=%d, segments=%d, level=%d}",
                mBuffer.length(), mLines, mSegmentTexts.size(), mLevel);
    }
}
