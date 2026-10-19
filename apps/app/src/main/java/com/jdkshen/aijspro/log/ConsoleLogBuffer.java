package com.jdkshen.aijspro.log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** One atomic cursor/reset boundary for every consumer of the live console. */
public final class ConsoleLogBuffer<T> {
    public interface Factory<T> { T create(long sequence); }
    public interface Sequence<T> { long of(T entry); }
    public interface Filter<T> { boolean matches(T entry); }

    public static final class Page<T> {
        public final List<T> entries;
        public final long version, generation, headVersion;
        public final int count;
        public final boolean more, reset, truncated;

        Page(List<T> entries, long version, long generation, long headVersion,
             int count, boolean more, boolean reset, boolean truncated) {
            this.entries = entries;
            this.version = version;
            this.generation = generation;
            this.headVersion = headVersion;
            this.count = count;
            this.more = more;
            this.reset = reset;
            this.truncated = truncated;
        }
    }

    private final int capacity;
    private final Sequence<T> sequence;
    private final ArrayDeque<T> entries = new ArrayDeque<>();
    private long version, generation;

    public ConsoleLogBuffer(int capacity, Sequence<T> sequence) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
        this.sequence = sequence;
    }

    public synchronized T append(Factory<T> factory) {
        long next = version + 1;
        T item = factory.create(next);
        entries.addLast(item);
        version = next;
        while (entries.size() > capacity) entries.removeFirst();
        return item;
    }

    public synchronized long version() { return version; }
    public synchronized long generation() { return generation; }
    public synchronized int size() { return entries.size(); }

    /** Keep the sequence monotonic; the generation tells already-open views to reset. */
    public synchronized long clear() {
        entries.clear();
        return ++generation;
    }

    public synchronized Page<T> page(long since, int limit, Filter<T> filter, long expectedGeneration) {
        boolean reset = expectedGeneration >= 0 && expectedGeneration != generation;
        if (reset) since = 0;
        List<T> matched = new ArrayList<>();
        for (T item : entries) {
            if (sequence.of(item) > since && (filter == null || filter.matches(item))) matched.add(item);
        }
        boolean gap = since > 0 && !entries.isEmpty()
                && since < sequence.of(entries.peekFirst()) - 1;
        int bounded = Math.max(0, limit);
        boolean overflow = matched.size() > bounded;
        // Initial reads are a tail window. Incremental reads consume the oldest
        // unseen rows first, so a bounded response cannot silently skip the middle.
        boolean more = since > 0 && overflow && bounded > 0;
        List<T> result;
        if (!overflow) result = matched;
        else if (since > 0) result = new ArrayList<>(matched.subList(0, bounded));
        else result = new ArrayList<>(matched.subList(matched.size() - bounded, matched.size()));
        long cursor = more ? sequence.of(result.get(result.size() - 1)) : version;
        return new Page<>(result, cursor, generation, version, entries.size(), more, reset,
                gap || (since == 0 && overflow));
    }
}
