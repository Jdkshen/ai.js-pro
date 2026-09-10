package com.stardust.autojs.runtime.api;

import android.content.pm.PackageManager;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import rikka.shizuku.Shizuku;

/** Executes shell commands through a running Shizuku/Sui service. */
public final class ShizukuShell {

    private static final AtomicInteger REQUEST_CODES = new AtomicInteger(0x4100);

    private ShizukuShell() {
    }

    public static boolean isAvailable() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false;
        try {
            return Shizuku.pingBinder();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean hasPermission() {
        try {
            return isAvailable()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Requests Shizuku permission and waits for the manager response. This is called from a script
     * worker thread, while Shizuku delivers the permission callback on the Android main thread.
     */
    public static boolean requestPermission(int timeoutMillis) {
        if (!isAvailable()) return false;
        if (hasPermission()) return true;
        try {
            if (Shizuku.isPreV11() || Shizuku.shouldShowRequestPermissionRationale()) return false;
        } catch (Throwable error) {
            return false;
        }

        final int requestCode = REQUEST_CODES.incrementAndGet();
        final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(1);
        final AtomicBoolean granted = new AtomicBoolean(false);
        Shizuku.OnRequestPermissionResultListener listener = (code, result) -> {
            if (code != requestCode) return;
            granted.set(result == PackageManager.PERMISSION_GRANTED);
            latch.countDown();
        };
        Shizuku.addRequestPermissionResultListener(listener);
        try {
            Shizuku.requestPermission(requestCode);
            latch.await(Math.max(1000, timeoutMillis), TimeUnit.MILLISECONDS);
            return granted.get() || hasPermission();
        } catch (Throwable ignored) {
            return false;
        } finally {
            Shizuku.removeRequestPermissionResultListener(listener);
        }
    }

    public static Result execute(String command, int timeoutMillis, int maxOutputBytes) {
        if (!isAvailable()) {
            return new Result(-1, "", "Shizuku 未运行或 Binder 尚未连接");
        }
        if (!hasPermission()) {
            return new Result(-1, "", "尚未授予 AI.js Pro 的 Shizuku 权限");
        }

        Process process = null;
        try {
            process = startRemoteProcess(command);
            final int limit = Math.max(0, maxOutputBytes);
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            Thread stdoutReader = drain(process.getInputStream(), stdout, limit,
                    "Shizuku-shell-stdout");
            Thread stderrReader = drain(process.getErrorStream(), stderr, limit,
                    "Shizuku-shell-stderr");

            // Some Android Process implementations override waitFor(timeout, unit) by calling
            // exitValue() before the remote process has exited. Shizuku's RemoteProcess then
            // leaks IllegalThreadStateException ("process hasn't exited") instead of polling.
            // Keep the blocking wait on a helper thread so timeout behaviour is consistent on
            // every supported Android version.
            final Process runningProcess = process;
            FutureTask<Integer> waiter = new FutureTask<>(runningProcess::waitFor);
            Thread waiterThread = new Thread(waiter, "Shizuku-shell-waiter");
            waiterThread.setDaemon(true);
            waiterThread.start();

            boolean finished = false;
            int code = -1;
            try {
                code = waiter.get(Math.max(1, timeoutMillis), TimeUnit.MILLISECONDS);
                finished = true;
            } catch (TimeoutException timeout) {
                process.destroy();
                waiter.cancel(true);
            }
            stdoutReader.join(1000);
            stderrReader.join(1000);

            String output = new String(stdout.toByteArray(), StandardCharsets.UTF_8);
            String error = new String(stderr.toByteArray(), StandardCharsets.UTF_8);
            if (!finished) error = error.isEmpty() ? "timeout" : "timeout\n" + error;
            return new Result(code, output, error);
        } catch (Throwable error) {
            Throwable cause = error instanceof InvocationTargetException
                    && ((InvocationTargetException) error).getTargetException() != null
                    ? ((InvocationTargetException) error).getTargetException() : error;
            return new Result(-1, "", cause.getMessage() == null
                    ? cause.getClass().getSimpleName() : cause.getMessage());
        } finally {
            if (process != null) {
                try {
                    process.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static Process startRemoteProcess(String command) throws Exception {
        // The API 11 process bridge is intentionally isolated here so the public script API stays
        // stable; a future client upgrade can replace this method with a UserService implementation.
        Method method = Shizuku.class.getDeclaredMethod(
                "newProcess", String[].class, String[].class, String.class);
        method.setAccessible(true);
        return (Process) method.invoke(null,
                (Object) new String[]{"sh", "-c", command}, null, null);
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
