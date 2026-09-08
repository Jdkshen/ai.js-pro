package com.stardust.autojs.core.util;

import android.content.Context;
import android.os.Handler;
import android.util.Log;

import com.stardust.autojs.runtime.ScriptRuntime;
import com.stardust.autojs.runtime.api.AbstractShell;
import com.stardust.autojs.runtime.exception.ScriptInterruptedException;
import com.stardust.pio.UncheckedIOException;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;

/**
 * Persistent Android shell backed by {@link ProcessBuilder}.
 *
 * <p>The original implementation used Jackpal's terminal JNI library. That
 * library only shipped obsolete armeabi/x86 binaries, so loading it crashed
 * every current arm64 build. A background shell does not need a terminal
 * emulator or a pseudo-TTY; the platform sh/su process is sufficient.</p>
 */
public class Shell extends AbstractShell {

    public interface Callback {
        void onOutput(String str);
        void onNewLine(String line);
        void onInitialized();
        void onInterrupted(InterruptedException e);
    }

    public static class SimpleCallback implements Callback {
        @Override public void onOutput(String str) { }
        @Override public void onNewLine(String str) { }
        @Override public void onInitialized() { }
        @Override public void onInterrupted(InterruptedException e) { }
    }

    private static final boolean DEBUG = true;
    private static final String TAG = "Shell";

    private final Object mInitLock = new Object();
    private final Object mWriteLock = new Object();
    private final Object mCommandOutputLock = new Object();
    private volatile Process mProcess;
    private volatile BufferedWriter mWriter;
    private volatile RuntimeException mInitException;
    private volatile boolean mInitialized;
    private volatile boolean mClosed;
    private volatile boolean mProcessEnded;
    private volatile Callback mCallback;
    private Callback mInitializationCallbackDeliveredTo;
    private String mPendingMarker;
    private ArrayList<String> mPendingOutput;

    public Shell(Context context) {
        this(context, false);
    }

    public Shell(Context context, boolean root) {
        this(context, root, true);
    }

    public Shell(Context context, boolean root, boolean shouldReadOutput) {
        super(context, root);
        // Output must always be drained to prevent a long-running child from
        // blocking on a full pipe. The parameter is retained for API compatibility.
    }

    public Shell() {
        this(false);
    }

    public Shell(boolean root) {
        this(ScriptRuntime.getApplicationContext(), root);
    }

    @Override
    protected void init(final String initialCommand) {
        // AbstractShell invokes this override from its constructor. Posting to
        // the main queue guarantees this class's fields are initialized first.
        new Handler(mContext.getMainLooper()).post(() -> {
            Thread thread = new Thread(() -> startProcess(initialCommand),
                    "AutoJs-" + (isRoot() ? "RootShell" : "Shell"));
            thread.setDaemon(true);
            thread.start();
        });
    }

    private void startProcess(String initialCommand) {
        try {
            Process process = new ProcessBuilder(initialCommand)
                    .redirectErrorStream(true)
                    .start();
            BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(process.getOutputStream()));
            synchronized (mInitLock) {
                mProcess = process;
                mWriter = writer;
                mInitialized = true;
                mInitLock.notifyAll();
            }
            deliverInitializedCallback(mCallback);
            readOutput(process);
        } catch (IOException error) {
            failInitialization(new UncheckedIOException(error));
        } catch (RuntimeException error) {
            failInitialization(error);
        }
    }

    private void failInitialization(RuntimeException error) {
        synchronized (mInitLock) {
            mInitException = error;
            mInitLock.notifyAll();
        }
        notifyProcessEnded();
    }

    private void readOutput(Process process) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (completePendingCommandIfMarker(line)) continue;
                synchronized (mCommandOutputLock) {
                    if (mPendingOutput != null) mPendingOutput.add(line);
                }
                Callback callback = mCallback;
                if (callback != null) {
                    callback.onOutput(line + "\n");
                    callback.onNewLine(line);
                }
            }
        } catch (IOException error) {
            if (!mClosed) Log.w(TAG, "Cannot read shell output", error);
        } finally {
            notifyProcessEnded();
        }
    }

    private boolean completePendingCommandIfMarker(String line) {
        synchronized (mCommandOutputLock) {
            if (mPendingMarker == null || !mPendingMarker.equals(line)) return false;
            mPendingMarker = null;
            mCommandOutputLock.notifyAll();
            return true;
        }
    }

    private void notifyProcessEnded() {
        synchronized (mInitLock) {
            mInitialized = false;
            mProcessEnded = true;
            mInitLock.notifyAll();
        }
        synchronized (mCommandOutputLock) {
            mPendingMarker = null;
            mCommandOutputLock.notifyAll();
        }
    }

    @Override
    public void exec(String command) {
        ensureInitialized();
        write(command.endsWith(COMMAND_LINE_END) ? command : command + COMMAND_LINE_END);
    }

    private void write(String command) {
        synchronized (mWriteLock) {
            try {
                BufferedWriter writer = mWriter;
                if (writer == null) throw new IllegalStateException("Shell is not available");
                writer.write(command);
                writer.flush();
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }
    }

    public String execAndWaitFor(String command) {
        ensureInitialized();
        synchronized (mCommandOutputLock) {
            String marker = "__AIJS_COMMAND_DONE_" + System.nanoTime() + "__";
            mPendingMarker = marker;
            mPendingOutput = new ArrayList<>();
            write(command + "\nprintf '" + marker + "\\n'\n");
            while (mPendingMarker != null && isProcessAlive()) {
                try {
                    mCommandOutputLock.wait();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    onInterrupted(error);
                    break;
                }
            }
            String result = android.text.TextUtils.join("\n", mPendingOutput);
            mPendingOutput = null;
            return result;
        }
    }

    public void setCallback(Callback callback) {
        mCallback = callback;
        deliverInitializedCallback(callback);
    }

    private void deliverInitializedCallback(Callback callback) {
        if (callback == null) return;
        synchronized (mInitLock) {
            if (!mInitialized || mInitializationCallbackDeliveredTo == callback) return;
            mInitializationCallbackDeliveredTo = callback;
        }
        callback.onInitialized();
    }

    public boolean isInitialized() {
        return mInitialized;
    }

    private void ensureInitialized() {
        synchronized (mInitLock) {
            while (!mInitialized && mInitException == null && !mClosed && !mProcessEnded) {
                try {
                    mInitLock.wait();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    onInterrupted(error);
                    return;
                }
            }
        }
        if (mInitException != null) throw mInitException;
        if (!mInitialized || mProcessEnded || !isProcessAlive()) {
            throw new IllegalStateException("Shell process is not running");
        }
        logDebug("initialized");
    }

    private boolean isProcessAlive() {
        Process process = mProcess;
        if (process == null) return false;
        try {
            process.exitValue();
            return false;
        } catch (IllegalThreadStateException stillRunning) {
            return true;
        }
    }

    private void onInterrupted(InterruptedException error) {
        Callback callback = mCallback;
        if (callback == null) {
            exit();
            throw new ScriptInterruptedException();
        }
        callback.onInterrupted(error);
    }

    @Override
    public void exit() {
        mClosed = true;
        Process process = mProcess;
        if (process != null && isProcessAlive()) {
            try {
                write(COMMAND_EXIT);
            } catch (RuntimeException ignored) {
                // The process may already have closed its input stream.
            }
            process.destroy();
        }
        synchronized (mInitLock) {
            mInitLock.notifyAll();
        }
        notifyProcessEnded();
    }

    @Override
    public void exitAndWaitFor() {
        Process process = mProcess;
        exit();
        if (process == null) return;
        try {
            process.waitFor();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            onInterrupted(error);
        }
    }

    private void logDebug(String message) {
        if (DEBUG) Log.d(TAG, message);
    }
}
