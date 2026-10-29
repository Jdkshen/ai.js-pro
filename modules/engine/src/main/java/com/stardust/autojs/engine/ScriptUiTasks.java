package com.stardust.autojs.engine;

import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Bounded UI teardown; a timed-out queued task cannot run against the next execution. */
public final class ScriptUiTasks {
    private ScriptUiTasks() { }

    public static void runAndWait(Runnable operation, long timeoutMs) {
        if (Looper.myLooper() == Looper.getMainLooper()) { operation.run(); return; }
        Handler handler = new Handler(Looper.getMainLooper());
        FutureTask<Void> task = new FutureTask<>(operation, null);
        if (!handler.post(task)) throw new IllegalStateException("UI looper is unavailable");
        try {
            task.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during UI teardown", error);
        } catch (Exception error) {
            throw new IllegalStateException("UI teardown did not complete", error);
        } finally {
            if (!task.isDone()) task.cancel(false);
            handler.removeCallbacks(task);
        }
    }
}
