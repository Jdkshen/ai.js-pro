package com.jdkshen.aijspro.flutterpoc.scriptproc;

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
 *   <li>{@code level} 取批次内最高等级（android.util.Log 数值越大越严重），主进程按它给整批着色/写日志；</li>
 *   <li>单段文本超过 {@value #MAX_CHARS} 时截断并加中文提示，保证单条 Binder 消息不会超事务上限。</li>
 * </ul>
 */
public final class ConsoleBatch {

    /** 单批最大字符数（Binder 事务约 1MB，这里远低于上限，避免日志刷屏卡住脚本线程）。 */
    static final int MAX_CHARS = 8000;
    /** 单批最大行数。 */
    static final int MAX_LINES = 40;

    /** 一次 flush 出来的批量文本（跨包复用，字段只读）。 */
    public static final class Batch {
        public final String text;
        public final int level;

        Batch(String text, int level) {
            this.text = text;
            this.level = level;
        }

        public boolean isEmpty() {
            return text == null || text.isEmpty();
        }
    }

    private final StringBuilder mBuffer = new StringBuilder();
    private int mLines;
    private int mLevel;

    /**
     * 追加一段 console 输出。
     *
     * @param level   android.util.Log 等级（可已归一化，也可传原始值；本类只取最大值）
     * @param content 输出文本（可为多行）
     * @return 达到阈值时返回「应立即发送」的批次（缓冲已被取走）；未达阈值返回 null（等定时 flush）
     */
    public synchronized Batch append(int level, CharSequence content) {
        if (content == null) {
            return null;
        }
        String text = content.toString();
        if (text.isEmpty()) {
            return null;
        }
        if (text.length() > MAX_CHARS) {
            text = text.substring(0, MAX_CHARS) + "\n…（单条日志过长，已截断）";
        }
        if (mBuffer.length() > 0 && mBuffer.charAt(mBuffer.length() - 1) != '\n') {
            mBuffer.append('\n');
        }
        mBuffer.append(text);
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
        if (mBuffer.length() == 0) {
            mLines = 0;
            mLevel = 0;
            return null;
        }
        String text = mBuffer.toString();
        int level = mLevel;
        mBuffer.setLength(0);
        mLines = 0;
        mLevel = 0;
        return new Batch(text, level);
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
        return String.format(Locale.US, "ConsoleBatch{chars=%d, lines=%d, level=%d}",
                mBuffer.length(), mLines, mLevel);
    }
}
