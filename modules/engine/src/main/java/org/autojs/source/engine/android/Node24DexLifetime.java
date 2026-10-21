package org.autojs.source.engine.android;

import android.util.Log;
import dalvik.system.DexFile;
import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Keeps dex resources alive until their defining loader has actually been collected. */
public final class Node24DexLifetime {
    private static final String TAG = "Node24DexLifetime";
    private static final ReferenceQueue<ClassLoader> QUEUE = new ReferenceQueue<>();
    // Values never refer to their loader. Holding a loader here would prevent unloading.
    private static final Map<PhantomReference<ClassLoader>, List<DexFile>> RESOURCES = new HashMap<>();
    private static final Map<ClassLoader, Boolean> TRACKED = new WeakHashMap<>();
    private static final AtomicInteger CLOSED = new AtomicInteger();
    private static final AtomicInteger FAILURES = new AtomicInteger();
    private static boolean started;

    private Node24DexLifetime() {}

    public static synchronized void track(ClassLoader loader) throws ReflectiveOperationException {
        if (loader == null || TRACKED.containsKey(loader)) return;
        Object pathList = field(loader.getClass(), "pathList").get(loader);
        Object[] elements = (Object[]) field(pathList.getClass(), "dexElements").get(pathList);
        List<DexFile> files = new ArrayList<>();
        for (Object element : elements) {
            Object value = field(element.getClass(), "dexFile").get(element);
            if (value instanceof DexFile) files.add((DexFile) value);
        }
        if (files.isEmpty()) throw new IllegalStateException("Generated class loader has no dex resources");
        RESOURCES.put(new PhantomReference<>(loader, QUEUE), files);
        TRACKED.put(loader, true);
        if (!started) {
            Thread reaper = new Thread(Node24DexLifetime::reap, "Node24-dex-release");
            reaper.setDaemon(true);
            reaper.start();
            started = true;
        }
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field result = current.getDeclaredField(name);
                result.setAccessible(true);
                return result;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    private static void reap() {
        while (true) {
            try {
                @SuppressWarnings("unchecked")
                PhantomReference<ClassLoader> reference = (PhantomReference<ClassLoader>) QUEUE.remove();
                release(reference);
            } catch (InterruptedException ignored) { }
        }
    }

    // A separate frame avoids retaining the most recently closed DexFile in the
    // reaper's local variables while it blocks waiting for the next loader.
    private static void release(PhantomReference<ClassLoader> reference) {
        List<DexFile> files;
        synchronized (Node24DexLifetime.class) { files = RESOURCES.remove(reference); }
        if (files != null) for (DexFile file : files) {
            try { file.close(); CLOSED.incrementAndGet(); }
            catch (Exception failure) { FAILURES.incrementAndGet(); Log.e(TAG, "release generated dex", failure); }
        }
        reference.clear();
    }

    public static synchronized int pendingLoaderCount() { return RESOURCES.size(); }
    public static int closedDexCount() { return CLOSED.get(); }
    public static int failureCount() { return FAILURES.get(); }
}
