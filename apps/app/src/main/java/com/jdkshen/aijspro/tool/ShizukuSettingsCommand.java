package com.jdkshen.aijspro.tool;

import android.content.Context;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.ResultReceiver;
import com.stardust.autojs.runtime.api.ShizukuShell;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import rikka.shizuku.ShizukuBinderWrapper;
import rikka.shizuku.SystemServiceHelper;

/** Settings service commands through an already-authorized Shizuku Binder, without a UserService. */
final class ShizukuSettingsCommand {
    // Android Binder's stable shellCommand transaction ('_CMD'); not part of android.jar.
    private static final int SHELL_COMMAND_TRANSACTION = 0x5f434d44;
    private static final int OUTPUT_LIMIT = 32768;
    // A stuck system Binder cannot spawn an unbounded number of waiting threads.
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(0, 1, 20L,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(1), runnable -> {
                Thread thread = new Thread(runnable, "shizuku-settings");
                thread.setDaemon(true);
                return thread;
            });

    private ShizukuSettingsCommand() { }

    static final class Result {
        final int code;
        final String output, error;
        Result(int code, String output, String error) {
            this.code = code; this.output = output; this.error = error;
        }
    }

    static Result execute(Context context, long timeoutMillis, String... arguments) {
        if (Looper.myLooper() == Looper.getMainLooper())
            return failure("系统设置操作必须在工作线程执行");
        if (!ShizukuShell.isAvailable() || !ShizukuShell.hasPermission())
            return failure("Shizuku 未运行或未授权");
        Future<Result> pending = null;
        try {
            pending = WORKER.submit(() -> transact(context, arguments));
            return pending.get(Math.max(1L, timeoutMillis), TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return failure("系统设置操作已取消");
        } catch (java.util.concurrent.TimeoutException error) {
            return failure("timeout: 等待 Shizuku 系统设置调用超时");
        } catch (Exception error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            return failure("Shizuku 系统设置调用失败: " + cause);
        } finally {
            if (pending != null && !pending.isDone()) {
                pending.cancel(true);
                if (pending instanceof Runnable) WORKER.remove((Runnable) pending);
            }
        }
    }

    private static Result transact(Context context, String[] arguments) throws Exception {
        IBinder raw = SystemServiceHelper.getSystemService("settings");
        if (raw == null) return failure("系统没有提供 settings Binder");
        File outputFile = File.createTempFile("aijs-settings-", ".out", context.getCacheDir());
        File errorFile = null;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            errorFile = File.createTempFile("aijs-settings-", ".err", context.getCacheDir());
            try (ParcelFileDescriptor input = ParcelFileDescriptor.open(new File("/dev/null"), ParcelFileDescriptor.MODE_READ_ONLY);
                 ParcelFileDescriptor output = ParcelFileDescriptor.open(outputFile, ParcelFileDescriptor.MODE_READ_WRITE);
                 ParcelFileDescriptor error = ParcelFileDescriptor.open(errorFile, ParcelFileDescriptor.MODE_READ_WRITE)) {
                CountDownLatch completed = new CountDownLatch(1);
                AtomicInteger exit = new AtomicInteger(-1);
                ResultReceiver receiver = new ResultReceiver(null) {
                    @Override protected void onReceiveResult(int resultCode, android.os.Bundle resultData) {
                        exit.set(resultCode);
                        completed.countDown();
                    }
                };
                data.writeFileDescriptor(input.getFileDescriptor());
                data.writeFileDescriptor(output.getFileDescriptor());
                data.writeFileDescriptor(error.getFileDescriptor());
                data.writeStringArray(arguments);
                data.writeStrongBinder(null); // ShellCallback: no files may be opened by name.
                receiver.writeToParcel(data, 0);
                if (!new ShizukuBinderWrapper(raw).transact(SHELL_COMMAND_TRANSACTION, data, reply, 0))
                    return failure("系统不支持 settings shellCommand");
                reply.readException();
                if (!completed.await(3L, TimeUnit.SECONDS)) return failure("系统设置未返回执行结果");
                return new Result(exit.get(), read(outputFile), read(errorFile));
            }
        } finally {
            data.recycle(); reply.recycle(); outputFile.delete();
            if (errorFile != null) errorFile.delete();
        }
    }

    private static String read(File file) throws Exception {
        if (file.length() > OUTPUT_LIMIT) throw new java.io.IOException("系统设置输出超过限制");
        try (FileInputStream stream = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int offset = 0, length;
            while (offset < bytes.length && (length = stream.read(bytes, offset, bytes.length - offset)) > 0) offset += length;
            return new String(bytes, 0, offset, StandardCharsets.UTF_8);
        }
    }

    private static Result failure(String message) {
        return new Result(-1, "", message);
    }
}
