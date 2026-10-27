package com.stardust.autojs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.stardust.autojs.engine.ScriptEngine;
import com.stardust.autojs.execution.ExecutionConfig;
import com.stardust.autojs.execution.ScriptExecution;
import com.stardust.autojs.execution.ScriptExecutionListener;
import com.stardust.autojs.script.ScriptSource;

import org.junit.Test;

import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class ScriptExecutionTrackingTest {

    @Test
    public void snapshotIsAStableCopy() {
        ScriptExecutionRegistry registry = new ScriptExecutionRegistry();
        FakeExecution first = new FakeExecution(1);
        FakeExecution second = new FakeExecution(2);
        registry.register(first);
        registry.register(second);

        Collection<ScriptExecution> snapshot = registry.snapshot();
        assertEquals(2, snapshot.size());

        registry.unregister(first.getId());
        registry.register(new FakeExecution(3));

        // 旧快照不受之后的登记/移除影响，调用方可以在任意线程安全遍历。
        assertEquals(2, snapshot.size());
        assertTrue(snapshot.contains(first));
        assertTrue(snapshot.contains(second));
        assertEquals(2, registry.snapshot().size());
        assertNull(registry.get(first.getId()));
        assertSame(second, registry.get(second.getId()));
    }

    @Test
    public void executionWithoutEngineCanBeRemovedByExecutionId() {
        ScriptExecutionRegistry registry = new ScriptExecutionRegistry();
        FakeExecution execution = new FakeExecution(7);
        registry.register(execution);
        assertSame(execution, registry.get(execution.getId()));

        registry.unregister(execution.getId());
        assertNull(registry.get(execution.getId()));
        assertTrue(registry.snapshot().isEmpty());
    }

    @Test
    public void concurrentRegisterRemoveAndSnapshotStayConsistent() throws Exception {
        final ScriptExecutionRegistry registry = new ScriptExecutionRegistry();
        final int threadCount = 4;
        final int iterations = 500;
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threadCount);
        final AtomicReference<Throwable> failure = new AtomicReference<>();

        for (int thread = 0; thread < threadCount; thread++) {
            final int threadIndex = thread;
            Thread worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        start.await();
                        for (int i = 0; i < iterations; i++) {
                            FakeExecution execution = new FakeExecution(threadIndex * iterations + i);
                            registry.register(execution);

                            Collection<ScriptExecution> snapshot = registry.snapshot();
                            for (ScriptExecution item : snapshot) {
                                if (item == null) {
                                    throw new AssertionError("snapshot contained null");
                                }
                            }
                            assertSame(execution, registry.get(execution.getId()));

                            registry.unregister(execution.getId());
                        }
                    } catch (Throwable error) {
                        failure.compareAndSet(null, error);
                    } finally {
                        done.countDown();
                    }
                }
            }, "execution-registry-test-" + thread);
            worker.start();
        }

        start.countDown();
        assertTrue("concurrent workers did not finish", done.await(10, TimeUnit.SECONDS));
        if (failure.get() != null) {
            throw new AssertionError("concurrent registry access failed", failure.get());
        }
        assertTrue(registry.snapshot().isEmpty());
    }

    /**
     * 只实现执行表需要的 id；引擎创建失败场景下 getEngine() 正是 null。
     */
    private static final class FakeExecution implements ScriptExecution {

        private final int mId;

        FakeExecution(int id) {
            mId = id;
        }

        @Override
        public ScriptEngine getEngine() {
            return null;
        }

        @Override
        public ScriptSource getSource() {
            return null;
        }

        @Override
        public ScriptExecutionListener getListener() {
            return null;
        }

        @Override
        public ExecutionConfig getConfig() {
            return null;
        }

        @Override
        public int getId() {
            return mId;
        }
    }
}
