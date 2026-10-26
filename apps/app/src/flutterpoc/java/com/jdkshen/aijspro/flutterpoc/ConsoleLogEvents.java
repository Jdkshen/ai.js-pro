package com.jdkshen.aijspro.flutterpoc;

import android.os.Handler;
import android.os.Looper;
import com.jdkshen.aijspro.flutterpoc.scriptproc.HostConsoleSink;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded change notifications; clients fetch rows using the atomic cursor API. */
final class ConsoleLogEvents implements EventChannel.StreamHandler, HostConsoleSink.Listener {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final EventChannel channel;
    private volatile EventChannel.EventSink sink;
    private final AtomicBoolean pending = new AtomicBoolean();
    private final Runnable notify = () -> {
        pending.set(false);
        if (sink == null) return;
        Map<String, Object> event = new HashMap<>();
        event.put("version", HostConsoleSink.version());
        event.put("generation", HostConsoleSink.generation());
        sink.success(event);
    };

    ConsoleLogEvents(BinaryMessenger messenger) {
        channel = new EventChannel(messenger, "autojs.local.logs");
        channel.setStreamHandler(this);
    }

    @Override public void onListen(Object arguments, EventChannel.EventSink events) {
        sink = events;
        HostConsoleSink.ensureMainConsoleTap();
        HostConsoleSink.addListener(this);
        schedule();
    }
    @Override public void onCancel(Object arguments) {
        HostConsoleSink.removeListener(this);
        main.removeCallbacks(notify);
        pending.set(false);
        sink = null;
    }
    @Override public void onConsoleLine(HostConsoleSink.Entry entry) { schedule(); }
    @Override public void onConsoleCleared(long generation) { schedule(); }
    private void schedule() {
        if (sink == null || !pending.compareAndSet(false, true)) return;
        // Batch chatty script output; the cursor API still fetches every row.
        main.postDelayed(notify, 150L);
    }
    void dispose() {
        onCancel(null);
        channel.setStreamHandler(null);
    }
}
