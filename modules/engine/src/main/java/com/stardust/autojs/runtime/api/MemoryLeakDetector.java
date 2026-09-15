package com.stardust.autojs.runtime.api;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 需要手动 {@code recycle()} 的对象（目前是图片/图像）的泄露检测，
 * 对应 PDF §2.9 的 {@code $debug.setMemoryLeakDetectionEnabled(enabled)}。
 *
 * <p>原理：对象创建时登记一个弱引用，正常 {@code recycle()} 就标记为已回收；
 * 如果对象被 GC 回收时还没标记过，就认为「没有手动回收」并打印一条日志。
 * 登记表用 {@link WeakHashMap}（弱键）与弱引用，**不会**拖住被观测的对象。
 */
public final class MemoryLeakDetector {

    private static final ReferenceQueue<Object> sQueue = new ReferenceQueue<>();
    private static final Map<Object, Record> sTracked =
            Collections.synchronizedMap(new WeakHashMap<Object, Record>());
    private static final AtomicInteger sLeakCount = new AtomicInteger();

    private static volatile boolean sEnabled;
    private static volatile Consumer<String> sReporter;

    private MemoryLeakDetector() {
    }

    /** 日志出口（由 {@link Debug} 接到脚本 console 上）。 */
    public static void setReporter(Consumer<String> reporter) {
        sReporter = reporter;
    }

    public static boolean isEnabled() {
        return sEnabled;
    }

    public static void setEnabled(boolean enabled) {
        sEnabled = enabled;
        if (enabled) {
            drain();
        } else {
            sTracked.clear();
        }
    }

    /** 对象创建时登记；未开启检测时是空操作。 */
    public static void watch(Object target, String type) {
        if (!sEnabled || target == null) {
            return;
        }
        Record record = new Record(type);
        sTracked.put(target, record);
        record.reference = new TrackedReference(target, record);
    }

    /** 对象被正确回收（如 {@code Image.recycle()}）时调用。 */
    public static void markRecycled(Object target) {
        if (!sEnabled || target == null) {
            return;
        }
        Record record = sTracked.get(target);
        if (record != null) {
            record.recycled = true;
        }
    }

    /** 累计报告过的泄露对象数。 */
    public static int getLeakCount() {
        return sLeakCount.get();
    }

    /** 处理已经 GC 掉的登记项：没被回收过的就是泄露，打印并计数。 */
    public static int drain() {
        int found = 0;
        Reference<?> reference;
        while ((reference = sQueue.poll()) != null) {
            if (!(reference instanceof TrackedReference)) {
                continue;
            }
            Record record = ((TrackedReference) reference).record;
            if (record.recycled) {
                continue;
            }
            found++;
            int total = sLeakCount.incrementAndGet();
            Consumer<String> reporter = sReporter;
            if (reporter != null) {
                reporter.accept("[MemoryLeak] " + record.type + " 对象没有调用 recycle() 就被回收，"
                        + "可能造成内存泄露（累计 " + total + " 个）");
            }
        }
        return found;
    }

    private static final class Record {
        final String type;
        volatile boolean recycled;
        TrackedReference reference;

        Record(String type) {
            this.type = type;
        }
    }

    private static final class TrackedReference extends WeakReference<Object> {
        final Record record;

        TrackedReference(Object target, Record record) {
            super(target, sQueue);
            this.record = record;
        }
    }
}
