package com.stardust.autojs.engine;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.media.MediaPlayer;
import android.media.session.MediaSession;

/** Tracks explicitly constructed resources; borrowed framework objects remain with their owner. */
public final class ScriptJavaResources implements AutoCloseable {
    private final ScriptResourceScope resources = new ScriptResourceScope();
    private final ScriptResourceScope views = new ScriptResourceScope();
    private volatile Throwable viewFailure;

    public <T> T constructed(T value) {
        if (value instanceof MediaPlayer) {
            MediaPlayer player = (MediaPlayer) value;
            resources.track(value, () -> {
                try {
                    player.setOnCompletionListener(null);
                    player.setOnPreparedListener(null);
                    player.setOnErrorListener(null);
                } finally { player.release(); }
            });
        } else if (value instanceof MediaSession) {
            MediaSession session = (MediaSession) value;
            resources.track(value, () -> {
                try { session.setActive(false); session.setCallback(null); }
                finally { session.release(); }
            });
        } else if (value instanceof java.io.Closeable) {
            java.io.Closeable stream = (java.io.Closeable) value;
            resources.track(value, () -> {
                try { stream.close(); }
                catch (java.io.IOException error) { throw new IllegalStateException(error); }
            });
        }
        return value;
    }

    /** Invoke only after a successful Java call, including successful explicit release. */
    public void called(Object target, String name, Object[] args) {
        if (target instanceof Context && args.length > 0 && args[0] instanceof BroadcastReceiver) {
            BroadcastReceiver receiver = (BroadcastReceiver) args[0];
            if ("registerReceiver".equals(name)) {
                resources.track(receiver, () -> ((Context) target).unregisterReceiver(receiver));
            } else if ("unregisterReceiver".equals(name)) resources.forget(receiver);
        } else if (target instanceof android.view.WindowManager && args.length > 0
                && args[0] instanceof android.view.View) {
            android.view.WindowManager manager = (android.view.WindowManager) target;
            android.view.View view = (android.view.View) args[0];
            if ("addView".equals(name)) {
                views.track(view, () -> ScriptUiTasks.runAndWait(() -> {
                    if (view.isAttachedToWindow()) manager.removeViewImmediate(view);
                }, 1500L));
            } else if ("removeViewImmediate".equals(name)
                    || ("removeView".equals(name) && !view.isAttachedToWindow())) views.forget(view);
        } else if ("release".equals(name) || "close".equals(name)) {
            resources.forget(target);
        }
    }

    public void detachViews() {
        try { ScriptUiTasks.runAndWait(views::close, 1500L); }
        catch (RuntimeException error) { viewFailure = error; throw error; }
    }
    public boolean viewsDetached() { return views.isClosed() && views.failureCount() == 0 && viewFailure == null; }
    public int failureCount() { return views.failureCount() + resources.failureCount() + (viewFailure == null ? 0 : 1); }
    public Throwable firstFailure() {
        if (viewFailure != null) return viewFailure;
        return views.firstFailure() != null ? views.firstFailure() : resources.firstFailure();
    }
    public void track(Object identity, Runnable cleanup) { resources.track(identity, cleanup); }
    public void forget(Object identity) { resources.forget(identity); }
    @Override public void close() {
        try { detachViews(); } finally { resources.close(); }
    }
}
