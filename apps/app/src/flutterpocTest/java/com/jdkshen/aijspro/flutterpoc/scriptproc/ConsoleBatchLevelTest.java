package com.jdkshen.aijspro.flutterpoc.scriptproc;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link ConsoleBatch} 的纯 JVM 回归：同一批 V/D/I/W/E/A 必须保留逐段等级与顺序，
 * 同时旧客户端的 {@code text} + 最高 {@code level} 仍然兼容；drain 后不得残留、数组是快照。
 *
 * <p>{@code ConsoleBatch} 在 {@code src/flutterpoc} 源集；Gradle 单测需带
 * {@code -PflutterPoc=true}（例如 {@code :app:testMiuixCompatDebugUnitTest}）。
 * 通过条件 testDebug 源集加入，普通构建不依赖 flutterpoc 的生产类。
 * 本测试不依赖 android.jar，直接按 android.util.Log 的数值常量断言。
 */
public class ConsoleBatchLevelTest {

    // android.util.Log 等级数值（避免纯 JVM 测试依赖 android.jar 的桩实现）。
    private static final int VERBOSE = 2;
    private static final int DEBUG = 3;
    private static final int INFO = 4;
    private static final int WARN = 5;
    private static final int ERROR = 6;
    private static final int ASSERT = 7;

    @Test
    public void mixedLevelsKeepOrderAndHighestLevelForLegacyFields() {
        ConsoleBatch batch = new ConsoleBatch();
        assertNull(batch.append(VERBOSE, "verbose"));
        assertNull(batch.append(DEBUG, "debug"));
        assertNull(batch.append(INFO, "info"));
        assertNull(batch.append(WARN, "warn"));
        assertNull(batch.append(ERROR, "error"));
        assertNull(batch.append(ASSERT, "assert"));

        ConsoleBatch.Batch out = batch.drain();
        assertNotNull(out);

        // 旧客户端兼容字段：整批拼接文本 + 批次最高等级。
        assertEquals("verbose\ndebug\ninfo\nwarn\nerror\nassert", out.text);
        assertEquals(ASSERT, out.level);

        // 新字段：每段自己的文本/等级，顺序与 append 完全一致。
        assertArrayEquals(
                new String[]{"verbose", "debug", "info", "warn", "error", "assert"},
                out.segmentTexts);
        assertArrayEquals(
                new int[]{VERBOSE, DEBUG, INFO, WARN, ERROR, ASSERT},
                out.segmentLevels);
        assertNull(batch.drain());
    }

    @Test
    public void drainClearsQueueAndReturnedSegmentsAreSnapshots() {
        ConsoleBatch batch = new ConsoleBatch();
        assertNull(batch.append(VERBOSE, "first"));
        ConsoleBatch.Batch first = batch.drain();
        assertNotNull(first);

        // 第二次 drain 必须为空，新一批不能串进上一批。
        assertNull(batch.drain());
        assertNull(batch.append(INFO, ""));
        assertNull(batch.drain());

        assertNull(batch.append(ERROR, "second"));
        ConsoleBatch.Batch second = batch.drain();
        assertNotNull(second);
        assertEquals("second", second.text);
        assertEquals(ERROR, second.level);
        assertArrayEquals(new String[]{"second"}, second.segmentTexts);
        assertArrayEquals(new int[]{ERROR}, second.segmentLevels);

        // first 是 drain 时的快照，后续 append/drain 不能改到它。
        assertEquals("first", first.text);
        assertEquals(VERBOSE, first.level);
        assertArrayEquals(new String[]{"first"}, first.segmentTexts);
        assertArrayEquals(new int[]{VERBOSE}, first.segmentLevels);
        assertNull(batch.drain());
    }

    @Test
    public void multilineSegmentKeepsItsOwnLevelAndAppendOrder() {
        ConsoleBatch batch = new ConsoleBatch();
        assertNull(batch.append(WARN, "warn-1\nwarn-2"));
        assertNull(batch.append(DEBUG, "debug-1"));

        ConsoleBatch.Batch out = batch.drain();
        assertNotNull(out);
        assertEquals("warn-1\nwarn-2\ndebug-1", out.text);
        // 原始 content 可多行：一个 append 段仍是一个段，内部换行不拆段。
        assertArrayEquals(new String[]{"warn-1\nwarn-2", "debug-1"}, out.segmentTexts);
        assertArrayEquals(new int[]{WARN, DEBUG}, out.segmentLevels);
        assertEquals(WARN, out.level);
    }

    @Test
    public void oversizedSegmentIsTruncatedAndAutoDrained() {
        ConsoleBatch batch = new ConsoleBatch();
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < ConsoleBatch.MAX_CHARS + 500; i++) {
            content.append('x');
        }

        ConsoleBatch.Batch out = batch.append(INFO, content);
        assertNotNull(out);
        assertTrue(out.text.length() > ConsoleBatch.MAX_CHARS);
        assertTrue(out.text.length() <= ConsoleBatch.MAX_CHARS + 64);
        assertTrue(out.text.endsWith("…（单条日志过长，已截断）"));
        assertEquals(1, out.segmentTexts.length);
        assertEquals(out.text, out.segmentTexts[0]);
        assertArrayEquals(new int[]{INFO}, out.segmentLevels);
        assertEquals(INFO, out.level);
        assertNull(batch.drain());
    }

    @Test
    public void fortyLineThresholdEmitsOneBoundedBatch() {
        ConsoleBatch batch = new ConsoleBatch();
        ConsoleBatch.Batch overflow = null;
        for (int i = 1; i <= ConsoleBatch.MAX_LINES; i++) {
            int level = i == ConsoleBatch.MAX_LINES ? ERROR : INFO;
            ConsoleBatch.Batch result = batch.append(level, "line-" + i);
            if (i < ConsoleBatch.MAX_LINES) {
                assertNull(result);
            } else {
                overflow = result;
            }
        }

        assertNotNull(overflow);
        assertEquals(ConsoleBatch.MAX_LINES, overflow.segmentTexts.length);
        assertEquals(ConsoleBatch.MAX_LINES, overflow.segmentLevels.length);
        assertEquals(ConsoleBatch.MAX_LINES, overflow.text.split("\n", -1).length);
        assertEquals("line-1", overflow.segmentTexts[0]);
        assertEquals("line-40", overflow.segmentTexts[ConsoleBatch.MAX_LINES - 1]);
        assertEquals(INFO, overflow.segmentLevels[0]);
        assertEquals(ERROR, overflow.segmentLevels[ConsoleBatch.MAX_LINES - 1]);
        assertEquals(ERROR, overflow.level);
        assertTrue(overflow.text.length() <= ConsoleBatch.MAX_CHARS);
        assertNull(batch.drain());
    }

    @Test
    public void multilineContentStillCountsTowardLineThreshold() {
        ConsoleBatch batch = new ConsoleBatch();
        ConsoleBatch.Batch overflow = null;
        for (int i = 0; i < 20; i++) {
            int level = i == 19 ? ASSERT : WARN;
            ConsoleBatch.Batch result = batch.append(level, "a" + i + "\nb" + i);
            if (i < 19) {
                assertNull(result);
            } else {
                overflow = result;
            }
        }

        assertNotNull(overflow);
        assertEquals(20, overflow.segmentTexts.length);
        assertEquals(40, overflow.text.split("\n", -1).length);
        assertEquals("a0\nb0", overflow.segmentTexts[0]);
        assertEquals("a19\nb19", overflow.segmentTexts[19]);
        assertEquals(ASSERT, overflow.level);
        assertEquals(ASSERT, overflow.segmentLevels[19]);
        assertNull(batch.drain());
    }

    @Test
    public void characterThresholdTriggersOnceAndStaysBounded() {
        ConsoleBatch batch = new ConsoleBatch();
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            content.append('a');
        }
        String line = content.toString();

        ConsoleBatch.Batch overflow = null;
        int appends = 0;
        for (int i = 0; i < 30 && overflow == null; i++) {
            overflow = batch.append(INFO, line);
            appends++;
        }

        assertNotNull(overflow);
        // 27 段 × 300 字符 + 26 个分隔换行 = 8126，首次达到 8000 阈值。
        assertEquals(27, appends);
        assertEquals(27, overflow.segmentTexts.length);
        assertEquals(8126, overflow.text.length());
        assertTrue(overflow.text.length() >= ConsoleBatch.MAX_CHARS);
        // 沿用原触发语义：单次 append 后检查阈值，故整批上界由单段截断保证（约 2×8KB）。
        assertTrue(overflow.text.length() <= 2 * ConsoleBatch.MAX_CHARS);
        assertNull(batch.drain());
    }
}
