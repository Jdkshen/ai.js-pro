package com.stardust.autojs.runtime.api;

import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;

import com.stardust.autojs.runtime.api.shizuku.ShizukuRemoteProcesses;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import rikka.shizuku.Shizuku;

/** Executes shell commands through a running Shizuku/Sui service. */
public final class ShizukuShell {

    private static final int MIN_SDK = Build.VERSION_CODES.M;
    private static final int MIN_PERMISSION_TIMEOUT_MILLIS = 1000;
    private static final int MAX_PERMISSION_TIMEOUT_MILLIS = 120_000;
    private static final long AVAILABILITY_CONNECT_MILLIS = 1_500L;
    private static final long CONNECT_TIMEOUT_MILLIS = 5_000L;
    private static final long READER_JOIN_MILLIS = 1_500L;

    private static final AtomicInteger REQUEST_CODES = new AtomicInteger(0x4100);

    private ShizukuShell() {
    }

    /**
     * Whether a Shizuku binder is visible in this process. This starts the lazy
     * per-process binder lookup; a UI-thread call returns the current state without
     * waiting, while a worker gets a short bounded wait for the first lookup.
     */
    public static boolean isAvailable() {
        if (Build.VERSION.SDK_INT < MIN_SDK) return false;
        ShizukuClient.warmUp();
        if (ShizukuClient.isBinderAlive()) return true;
        if (isMainThread()) return false;
        // A script worker may wait briefly for the one per-process binder lookup. The
        // main thread only starts the lookup and never waits here.
        return ShizukuClient.ensureConnected(AVAILABILITY_CONNECT_MILLIS);
    }

    /**
     * Reads the cached permission state (refreshing on a worker) and starts the lazy
     * binder lookup when needed. It does not block the UI thread on Shizuku.
     */
    public static boolean hasPermission() {
        if (!isAvailable()) return false;
        return ShizukuClient.hasPermission();
    }

    /**
     * Requests Shizuku permission and waits for the manager response. This is only
     * allowed on a worker thread; the main thread is rejected immediately. The wait,
     * including the initial connection wait, is bounded by {@code timeoutMillis}.
     */
    public static boolean requestPermission(int timeoutMillis) {
        if (Build.VERSION.SDK_INT < MIN_SDK) return false;
        if (isMainThread()) {
            ShizukuClient.reportError("requestPermission 只允许在工作线程等待 Shizuku 授权");
            return false;
        }

        final int timeout = Math.max(MIN_PERMISSION_TIMEOUT_MILLIS,
                Math.min(timeoutMillis, MAX_PERMISSION_TIMEOUT_MILLIS));
        final long deadline = SystemClock.elapsedRealtime() + timeout;
        if (!ShizukuClient.ensureConnected(remaining(deadline))) return false;
        if (ShizukuClient.hasPermission()) return true;
        if (remaining(deadline) <= 0L) return false;

        try {
            if (Shizuku.isPreV11() || Shizuku.shouldShowRequestPermissionRationale()) {
                return false;
            }
        } catch (Throwable error) {
            ShizukuClient.reportError("读取 Shizuku 授权状态失败: " + message(error));
            return false;
        }

        final int requestCode = REQUEST_CODES.incrementAndGet();
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean granted = new AtomicBoolean(false);
        final Shizuku.OnRequestPermissionResultListener listener = (code, result) -> {
            if (code != requestCode) return;
            granted.set(result == PackageManager.PERMISSION_GRANTED);
            latch.countDown();
        };

        boolean listenerAdded = false;
        try {
            Shizuku.addRequestPermissionResultListener(listener);
            listenerAdded = true;
            Shizuku.requestPermission(requestCode);

            long wait = remaining(deadline);
            if (wait > 0L) {
                latch.await(wait, TimeUnit.MILLISECONDS);
            }
            boolean result = granted.get() || ShizukuClient.hasPermission();
            ShizukuClient.onPermissionResult(result);
            return result;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            ShizukuClient.reportError("等待 Shizuku 授权被中断");
            return false;
        } catch (Throwable error) {
            ShizukuClient.reportError("请求 Shizuku 授权失败: " + message(error));
            return false;
        } finally {
            if (listenerAdded) {
                try {
                    Shizuku.removeRequestPermissionResultListener(listener);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * Starts a remote command using the shared Shizuku remote-process helper. The
     * command string keeps its original argument boundaries; the helper owns the
     * remote process representation and cleanup.
     */
    public static Process start(String command, File directory,
                                Map<String, String> env, boolean mergeError)
            throws IOException {
        if (Build.VERSION.SDK_INT < MIN_SDK) {
            throw new IOException("Android 6.0 以下不支持 Shizuku");
        }
        return ShizukuRemoteProcesses.start(command, directory, env, mergeError);
    }

    public static Result execute(String command, int timeoutMillis, int maxOutputBytes) {
        if (Build.VERSION.SDK_INT < MIN_SDK) {
            return new Result(-1, "", "Android 6.0 以下不支持 Shizuku");
        }

        final long deadline = SystemClock.elapsedRealtime() + Math.max(1, timeoutMillis);
        if (!ShizukuClient.isBinderAlive()) {
            if (isMainThread()) {
                // Do not block the UI on ContentResolver / permission work.
                ShizukuClient.warmUp();
            } else {
                long connectTimeout = Math.min(CONNECT_TIMEOUT_MILLIS, remaining(deadline));
                if (connectTimeout > 0L) {
                    ShizukuClient.ensureConnected(connectTimeout);
                }
            }
        }
        if (!ShizukuClient.isBinderAlive()) {
            return new Result(-1, "", "Shizuku 未运行或 Binder 尚未连接");
        }
        if (!ShizukuClient.hasPermission()) {
            return new Result(-1, "", "尚未授予 AI.js Pro 的 Shizuku 权限");
        }
        if (remaining(deadline) <= 0L) {
            return new Result(-1, "", "timeout");
        }

        Process process = null;
        final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Thread stdoutReader = null;
        Thread stderrReader = null;
        try {
            process = ShizukuRemoteProcesses.start(command, null, null, false, remaining(deadline));
            final int limit = Math.max(0, maxOutputBytes);
            stdoutReader = drain(process.getInputStream(), stdout, limit,
                    "Shizuku-shell-stdout");
            stderrReader = drain(process.getErrorStream(), stderr, limit,
                    "Shizuku-shell-stderr");

            // Some Process implementations override waitFor(timeout, unit) by calling
            // exitValue() before the remote process has exited. Keep the blocking wait
            // on a helper thread so the timeout is consistent across implementations.
            final Process runningProcess = process;
            FutureTask<Integer> waiter = new FutureTask<>(runningProcess::waitFor);
            Thread waiterThread = new Thread(waiter, "Shizuku-shell-waiter");
            waiterThread.setDaemon(true);
            waiterThread.start();

            boolean timedOut = false;
            int code = -1;
            try {
                code = waiter.get(Math.max(1L, remaining(deadline)), TimeUnit.MILLISECONDS);
            } catch (TimeoutException timeout) {
                timedOut = true;
                destroyQuietly(process);
                waiter.cancel(true);
            }

            joinReaders(stdoutReader, stderrReader);
            return result(code, stdout, stderr, timedOut ? "timeout" : null);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            joinReaders(stdoutReader, stderrReader);
            return result(-1, stdout, stderr, "等待 Shizuku 命令被中断");
        } catch (Throwable error) {
            joinReaders(stdoutReader, stderrReader);
            return result(-1, stdout, stderr, message(error));
        } finally {
            destroyQuietly(process);
        }
    }

    private static Result result(int code, ByteArrayOutputStream stdout,
                                 ByteArrayOutputStream stderr, String failure) {
        String output = new String(stdout.toByteArray(), StandardCharsets.UTF_8);
        String error = new String(stderr.toByteArray(), StandardCharsets.UTF_8);
        if (failure != null && !failure.isEmpty()) {
            error = error.isEmpty() ? failure : failure + "\n" + error;
        }
        return new Result(code, output, error);
    }

    private static long remaining(long deadline) {
        return Math.max(0L, deadline - SystemClock.elapsedRealtime());
    }

    private static boolean isMainThread() {
        try {
            Looper mainLooper = Looper.getMainLooper();
            return mainLooper != null && Looper.myLooper() == mainLooper;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void destroyQuietly(Process process) {
        if (process == null) return;
        try {
            process.destroy();
        } catch (Throwable ignored) {
        }
    }

    private static void joinReaders(Thread stdoutReader, Thread stderrReader) {
        long deadline = SystemClock.elapsedRealtime() + READER_JOIN_MILLIS;
        joinQuietly(stdoutReader, remaining(deadline));
        joinQuietly(stderrReader, remaining(deadline));
    }

    private static void joinQuietly(Thread thread, long millis) {
        if (thread == null) return;
        try {
            thread.join(Math.max(1L, millis));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static Thread drain(InputStream input, ByteArrayOutputStream output,
                                int maxOutputBytes, String name) {
        Thread thread = new Thread(() -> {
            try (InputStream stream = input) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = stream.read(buffer)) != -1) {
                    int remaining = maxOutputBytes - output.size();
                    if (remaining > 0) output.write(buffer, 0, Math.min(read, remaining));
                }
            } catch (IOException ignored) {
                // Destroying a timed-out remote process closes its pipes.
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static String message(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof ExecutionException
                || cause instanceof java.lang.reflect.InvocationTargetException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isEmpty()
                ? cause.getClass().getSimpleName() : message;
    }

    public static final class Result {
        public final int code;
        public final String output;
        public final String error;

        Result(int code, String output, String error) {
            this.code = code;
            this.output = output;
            this.error = error;
        }
    }
}
