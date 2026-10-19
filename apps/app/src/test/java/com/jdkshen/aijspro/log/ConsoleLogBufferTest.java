package com.jdkshen.aijspro.log;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class ConsoleLogBufferTest {
    private static final class Row {
        final long seq;
        final String token;
        Row(long seq, String token) { this.seq = seq; this.token = token; }
    }
    private ConsoleLogBuffer<Row> buffer(int capacity) {
        return new ConsoleLogBuffer<>(capacity, row -> row.seq);
    }
    private void append(ConsoleLogBuffer<Row> buffer, String token, int count) {
        for (int i = 0; i < count; i++) buffer.append(seq -> new Row(seq, token));
    }

    @Test public void backlogLargerThanOneReplyNeverSkipsTheMiddle() {
        ConsoleLogBuffer<Row> buffer = buffer(4000);
        append(buffer, "node", 1);
        long cursor = buffer.page(0, 800, null, -1).version;
        append(buffer, "node", 2000);
        List<Long> delivered = new ArrayList<>();
        ConsoleLogBuffer.Page<Row> page;
        do {
            page = buffer.page(cursor, 800, null, 0);
            for (Row row : page.entries) delivered.add(row.seq);
            cursor = page.version;
        } while (page.more);
        assertEquals(2000, delivered.size());
        for (int i = 0; i < delivered.size(); i++) assertEquals(i + 2L, delivered.get(i).longValue());
        assertEquals(2001, cursor);
    }

    @Test public void returnedCursorDoesNotChangeWhenAnAppendRacesTheClient() {
        ConsoleLogBuffer<Row> buffer = buffer(20);
        append(buffer, "rhino", 3);
        ConsoleLogBuffer.Page<Row> first = buffer.page(0, 20, null, -1);
        append(buffer, "quickjs", 1);
        assertEquals(3, first.version);
        ConsoleLogBuffer.Page<Row> second = buffer.page(first.version, 20, null, first.generation);
        assertEquals(1, second.entries.size());
        assertEquals(4, second.entries.get(0).seq);
    }

    @Test public void clearIsVisibleWithoutRewindingTheSequenceOrAppendingAnotherLine() {
        ConsoleLogBuffer<Row> buffer = buffer(20);
        append(buffer, "node", 3);
        ConsoleLogBuffer.Page<Row> initial = buffer.page(0, 20, null, -1);
        buffer.clear();
        ConsoleLogBuffer.Page<Row> cleared = buffer.page(initial.version, 20, null, initial.generation);
        assertTrue(cleared.reset);
        assertTrue(cleared.entries.isEmpty());
        assertEquals(3, cleared.version);
        append(buffer, "node", 1);
        ConsoleLogBuffer.Page<Row> resumed = buffer.page(cleared.version, 20, null, cleared.generation);
        assertFalse(resumed.reset);
        assertEquals(4, resumed.entries.get(0).seq);
    }

    @Test public void aSlowViewResetsAndGetsOnlyRowsProducedAfterClear() {
        ConsoleLogBuffer<Row> buffer = buffer(20);
        append(buffer, "node", 5);
        buffer.clear();
        append(buffer, "quickjs", 2);
        ConsoleLogBuffer.Page<Row> page = buffer.page(4, 20, null, 0);
        assertTrue(page.reset);
        assertEquals(2, page.entries.size());
        assertEquals(6, page.entries.get(0).seq);
        assertEquals(7, page.version);
    }

    @Test public void filteredPagesKeepTheirOwnCursorUntilMatchingRowsAreConsumed() {
        ConsoleLogBuffer<Row> buffer = buffer(20);
        for (int i = 0; i < 8; i++) {
            append(buffer, i % 2 == 0 ? "A" : "B", 1);
        }
        ConsoleLogBuffer.Page<Row> page = buffer.page(1, 2, row -> row.token.equals("B"), 0);
        assertTrue(page.more);
        assertEquals(4, page.version);
        page = buffer.page(page.version, 2, row -> row.token.equals("B"), 0);
        assertFalse(page.more);
        assertEquals(6, page.entries.get(0).seq);
        assertEquals(8, page.version);
        assertEquals(8, buffer.page(1, 2, row -> row.token.equals("missing"), 0).version);
    }

    @Test public void initialTailAndEvictedIncrementalRowsReportTruncation() {
        ConsoleLogBuffer<Row> buffer = buffer(3);
        append(buffer, "A", 5);
        ConsoleLogBuffer.Page<Row> initial = buffer.page(0, 2, null, -1);
        assertTrue(initial.truncated);
        assertFalse(initial.more);
        assertEquals(4, initial.entries.get(0).seq);
        ConsoleLogBuffer.Page<Row> incremental = buffer.page(1, 2, null, 0);
        assertTrue(incremental.truncated);
        assertTrue(incremental.more);
        assertEquals(3, incremental.entries.get(0).seq);
    }
}
