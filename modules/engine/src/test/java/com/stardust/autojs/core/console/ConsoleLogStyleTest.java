package com.stardust.autojs.core.console;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class ConsoleLogStyleTest {
    @Test public void allSixLevelsKeepTheirShortAndLongNamesAndOpaqueThemeColors() {
        String[] shortNames = {"V", "D", "I", "W", "E", "A"};
        String[] longNames = {"TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL"};
        for (int i = 0; i < 6; i++) {
            assertEquals(shortNames[i], ConsoleLogStyle.shortLevel(i + 2));
            assertEquals(longNames[i], ConsoleLogStyle.levelName(i + 2));
            assertEquals(0xff, ConsoleLogStyle.color(i + 2, true) >>> 24);
            assertEquals(0xff, ConsoleLogStyle.color(i + 2, false) >>> 24);
        }
        assertEquals("INFO", ConsoleLogStyle.levelName(999));
    }

    @Test public void theSharedFormatPreservesSourceMultilineUnicodeAndEmptyOutput() {
        String value = ConsoleLogStyle.format(1000, 6, "测试.node.js", "中文🙂\nstack");
        assertTrue(value.matches("(?s)\\d{2}:\\d{2}:\\d{2}\\.\\d{3}/E: 测试.node.js: 中文🙂\\nstack"));
        assertTrue(ConsoleLogStyle.format(1000, 3, "main", "").endsWith("/D: "));
    }

    @Test public void concurrentEnginesDoNotCorruptSharedTimestamps() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> results = new ArrayList<>();
            for (int thread = 0; thread < 8; thread++) {
                final long time = 1792400000000L + thread * 17000001L;
                final String expected = ConsoleLogStyle.format(time, 4, "node", "🙂");
                results.add(pool.submit(() -> {
                    for (int i = 0; i < 500; i++) assertEquals(expected,
                            ConsoleLogStyle.format(time, 4, "node", "🙂"));
                }));
            }
            for (Future<?> result : results) result.get(10, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
    }
}
