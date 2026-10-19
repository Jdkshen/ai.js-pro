package com.jdkshen.aijspro.flutterpoc;

import android.os.Handler;
import android.os.Looper;
import com.jdkshen.aijspro.flutterpoc.scriptproc.HostConsoleSink;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;
import java.util.HashMap;
import java.util.Map;

/** Bounded change notifications; clients fetch rows using the atomic cursor API. */
final class ConsoleLogEvents implements EventChannel.StreamHandler, HostConsoleSink.Listener {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final EventChannel channel;
    private EventChannel.EventSink sink;
    private boolean pending;
    private final Runnable notify = () -> {
        pending = false;
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
        pending = false;
        sink = null;
    }
    @Override public void onConsoleLine(HostConsoleSink.Entry entry) { schedule(); }
    @Override public void onConsoleCleared(long generation) { schedule(); }
    private void schedule() {
        if (pending || sink == null) return;
        pending = true;
        main.postDelayed(notify, 50L);
    }
    void dispose() {
        onCancel(null);
        channel.setStreamHandler(null);
    }
}
