package com.stardust.autojs.execution;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

public class ScriptThreadNamesTest {
    @Test
    public void executionNamesFitNativeLimitIncludingOverflow() {
        for (int id : new int[]{0, 1, Integer.MAX_VALUE, Integer.MIN_VALUE, -1}) {
            assertNativeSafe(ScriptThreadNames.execution(id));
        }
    }

    @Test
    public void workerNamesFitNativeLimitForEntireLongRange() {
        for (long id : new long[]{0, 1, Long.MAX_VALUE, Long.MIN_VALUE, -1}) {
            assertNativeSafe(ScriptThreadNames.worker(id));
        }
    }

    @Test
    public void executionAndWorkerNamesKeepDistinctIdentifiers() {
        Set<String> names = new HashSet<>();
        for (int id = 0; id < 1000; id++) {
            assertTrue(names.add(ScriptThreadNames.execution(id)));
            assertTrue(names.add(ScriptThreadNames.worker(id)));
        }
    }

    @Test
    public void runnableNamingDoesNotReadChineseSourcePath() throws Exception {
        RunnableScriptExecution execution = new RunnableScriptExecution(null, null) {
            @Override
            public com.stardust.autojs.script.ScriptSource getSource() {
                throw new AssertionError("Thread names must not read source paths, including 中文控制/worker.js");
            }

            @Override
            public Object execute() {
                assertEquals(ScriptThreadNames.execution(getId()), Thread.currentThread().getName());
                return null;
            }

            @Override
            protected void onException(com.stardust.autojs.engine.ScriptEngine engine, Throwable error) {
                throw new AssertionError(error);
            }
        };
        final Throwable[] failure = {null};
        Thread thread = new Thread(execution);
        thread.setUncaughtExceptionHandler((ignored, error) -> failure[0] = error);
        thread.start();
        thread.join(5000);
        assertFalse("Execution did not finish", thread.isAlive());
        assertNull(failure[0]);
        assertNativeSafe(thread.getName());
    }

    private static void assertNativeSafe(String name) {
        assertTrue(name, name.matches("[A-Za-z0-9-]+"));
        assertTrue(name, name.getBytes(StandardCharsets.UTF_8).length <= 15);
    }
}
