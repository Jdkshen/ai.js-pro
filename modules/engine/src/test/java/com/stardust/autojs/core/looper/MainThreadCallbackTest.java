package com.stardust.autojs.core.looper;

import com.stardust.autojs.runtime.exception.ScriptInterruptedException;
import org.junit.Test;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.WrappedException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class MainThreadCallbackTest {
    public static class StopApi {
        public boolean afterExit;
        public void exit() {
            Thread.currentThread().interrupt();
            throw new ScriptInterruptedException();
        }
        public void after() { afterExit = true; }
        public void nestedEvent() {
            MainThreadCallback.run(this::exit, failure -> fail("inner Java callback must unwind the enclosing script"));
        }
    }

    @Test public void rhinoExitUnwindsScriptButNotTheHostCallback() {
        Context cx = Context.enter();
        try {
            cx.setOptimizationLevel(-1);
            Scriptable scope = cx.initStandardObjects();
            StopApi api = new StopApi();
            ScriptableObject.putProperty(scope, "stopApi", Context.javaToJS(api, scope));
            AtomicBoolean terminated = new AtomicBoolean(), windowClosed = new AtomicBoolean();
            List<Runnable> pending = new ArrayList<>();
            pending.add(() -> fail("stopped timer was rescheduled"));
            boolean completed = MainThreadCallback.run(() -> cx.evaluateString(scope,
                    "(function(){stopApi.exit();stopApi.after();})()", "ui-timer-exit", 1, null), failure -> {
                assertTrue(ScriptInterruptedException.causedByInterrupted(failure));
                pending.clear();
                windowClosed.set(true);
                terminated.set(true);
                throw new ScriptInterruptedException(); // Same contract as ScriptRuntime.exit(failure).
            });
            assertFalse(completed);
            assertFalse(api.afterExit);
            assertTrue(terminated.get());
            assertTrue(windowClosed.get());
            assertTrue(pending.isEmpty());
            assertTrue("keep the engine's stop signal until runtime teardown", Thread.currentThread().isInterrupted());
            Thread.interrupted(); // ScriptRuntime.onExit owns this step in the app.
            AtomicBoolean nextHostCallback = new AtomicBoolean();
            assertTrue(MainThreadCallback.run(() -> nextHostCallback.set(true), failure -> fail()));
            assertTrue(nextHostCallback.get());
        } finally { Thread.interrupted(); Context.exit(); }
    }

    @Test public void ordinaryScriptErrorStillReportsItsOriginalFailure() {
        IllegalArgumentException original = new IllegalArgumentException("script error");
        AtomicReference<Throwable> reported = new AtomicReference<>();
        assertFalse(MainThreadCallback.run(() -> { throw original; }, failure -> {
            reported.set(failure);
            throw new WrappedException(new ScriptInterruptedException());
        }));
        assertSame(original, reported.get());
    }

    @Test public void programmaticJavaEventDoesNotResumeItsEnclosingScriptAfterExit() {
        Context cx = Context.enter();
        try {
            cx.setOptimizationLevel(-1);
            Scriptable scope = cx.initStandardObjects();
            StopApi api = new StopApi();
            ScriptableObject.putProperty(scope, "stopApi", Context.javaToJS(api, scope));
            AtomicBoolean outerStopped = new AtomicBoolean();
            assertFalse(MainThreadCallback.run(() -> cx.evaluateString(scope,
                "stopApi.nestedEvent();stopApi.after();", "programmatic-event", 1, null), failure -> {
                outerStopped.set(true);
                assertTrue(ScriptInterruptedException.causedByInterrupted(failure));
                throw new ScriptInterruptedException();
            }));
            assertTrue(outerStopped.get());
            assertFalse(api.afterExit);
        } finally { Thread.interrupted(); Context.exit(); }
    }

    @Test public void externalStopAndNestedInterruptionAreContained() {
        RuntimeException stopped = new WrappedException(new ScriptInterruptedException());
        AtomicReference<Throwable> reported = new AtomicReference<>();
        assertFalse(MainThreadCallback.run(() -> { throw stopped; }, failure -> {
            reported.set(failure);
            throw new RuntimeException(new ScriptInterruptedException());
        }));
        assertSame(stopped, reported.get());
    }

    @Test public void successfulCallbackDoesNotStopTheEngine() {
        assertTrue(MainThreadCallback.run(() -> {}, failure -> fail("unexpected stop")));
    }

    @Test public void unrelatedTerminationBugIsNotSilentlySwallowed() {
        IllegalStateException defect = new IllegalStateException("cleanup bug");
        try {
            MainThreadCallback.run(() -> { throw new ScriptInterruptedException(); }, failure -> { throw defect; });
            fail("unexpected host failure must remain visible");
        } catch (IllegalStateException actual) { assertSame(defect, actual); }
    }

    @Test public void fatalVmErrorsAreNotConvertedIntoScriptStops() {
        AssertionError fatal = new AssertionError("host failure");
        try {
            MainThreadCallback.run(() -> { throw fatal; }, failure -> fail("not a script exception"));
            fail("fatal error swallowed");
        } catch (AssertionError actual) { assertSame(fatal, actual); }
    }
}
