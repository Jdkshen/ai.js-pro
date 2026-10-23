package com.stardust.autojs.core.looper;

import com.stardust.autojs.runtime.exception.ScriptInterruptedException;
import org.mozilla.javascript.Context;

/** The outer Java callback boundary, after the script stack has unwound. */
public final class MainThreadCallback {
    private MainThreadCallback() {}

    public interface FailureHandler {
        void terminate(Throwable failure);
    }

    public static void terminate(Throwable failure, FailureHandler handler) {
        // performClick()/emit()/close() can synchronously re-enter Java from a
        // still-running script. Unwind that caller too; do not resume its JS
        // statements or replace the original error with a second stop signal.
        Context context = Context.getCurrentContext();
        if (context != null && org.mozilla.javascript.ScriptRuntime.hasTopCall(context)) {
            if (failure instanceof RuntimeException) throw (RuntimeException) failure;
            if (failure instanceof Error) throw (Error) failure;
            throw new RuntimeException(failure);
        }
        stop(() -> handler.terminate(failure));
    }

    public static void stop(Runnable stop) {
        Context context = Context.getCurrentContext();
        if (context != null && org.mozilla.javascript.ScriptRuntime.hasTopCall(context)) {
            stop.run();
            return;
        }
        try { stop.run(); }
        catch (RuntimeException stopping) {
            if (!ScriptInterruptedException.causedByInterrupted(stopping)) { throw stopping; }
        }
    }

    public static boolean run(Runnable callback, FailureHandler handler) {
        try {
            callback.run();
            return true;
        } catch (Exception failure) {
            // ScriptRuntime.exit() deliberately throws after stopping its
            // engine. Contain that second throw only at this outer boundary.
            terminate(failure, handler);
            return false;
        }
    }
}
