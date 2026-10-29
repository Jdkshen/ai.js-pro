package com.stardust.autojs.runtime;

import com.stardust.autojs.core.image.ColorMapping;
import com.stardust.autojs.runtime.api.MemoryLeakDetector;
import java.util.concurrent.atomic.AtomicBoolean;

/** Process-wide image state outlives every concurrent runtime using it. */
final class ScriptRuntimeGlobals implements AutoCloseable {
    private static final Object LOCK = new Object();
    private static int users;
    private final AtomicBoolean closed = new AtomicBoolean();

    ScriptRuntimeGlobals() { synchronized (LOCK) { users++; } }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        synchronized (LOCK) {
            if (--users != 0) return;
            try { ColorMapping.recycleSingleton(); }
            finally { MemoryLeakDetector.reset(com.stardust.autojs.runtime.api.Debug.isPackagedApp()); }
        }
    }
}
