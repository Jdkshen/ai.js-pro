package com.stardust.autojs.runtime.api;

import com.stardust.autojs.core.inputevent.RootAutomator2;
import com.stardust.autojs.core.inputevent.TouchController;
import com.stardust.autojs.runtime.ScriptRuntime;
import java.io.IOException;
import java.io.UncheckedIOException;

/** Built-in replacement for the ZIP's Touch interface; IDs come before coordinates. */
public final class TouchSession implements AutoCloseable {
    private final ScriptRuntime runtime;
    private final TouchController controller;
    private final boolean adb;

    public TouchSession(ScriptRuntime runtime, boolean adb) {
        this.runtime = runtime;
        this.adb = adb;
        // Only this session owns the backend: cleanup must lift pointers BEFORE closing it.
        RootAutomator2 automator = new RootAutomator2(runtime.uiHandler.getContext(), adb);
        controller = new TouchController(new TouchController.Backend() {
            public void down(int id, int x, int y) throws IOException { automator.touchDown(x, y, id); }
            public void move(int id, int x, int y) throws IOException { automator.touchMove(x, y, id); }
            public void up(int id, int x, int y) throws IOException { automator.touchUpAt(x, y, id); }
            public void close() throws IOException { automator.exit(); }
        }, adb ? 1 : 10);
        runtime.addCleanable(this);
    }
    public void down(int id, int x, int y) throws IOException { controller.down(id, x, y); }
    public void move(int id, int x, int y) throws IOException { controller.move(id, x, y); }
    public void up(int id) throws IOException { controller.up(id); }
    public boolean isAdb() { return adb; }
    public int pointerCount() { return controller.pointerCount(); }
    public boolean isClosed() { return controller.isClosed(); }
    public void exit() throws IOException {
        try { controller.close(); } finally { runtime.removeCleanable(this); }
    }
    @Override public void close() {
        try { exit(); } catch (IOException error) { throw new UncheckedIOException(error); }
    }
}
