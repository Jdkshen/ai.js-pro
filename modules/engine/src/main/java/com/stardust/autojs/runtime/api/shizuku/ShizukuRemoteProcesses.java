package com.stardust.autojs.runtime.api.shizuku;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import com.stardust.autojs.runtime.api.ShizukuClient;
import com.stardust.autojs.runtime.api.ShizukuShell;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import rikka.shizuku.Shizuku;

/** A different service per client process; every command also owns a Binder death token. */
public final class ShizukuRemoteProcesses {
    private static final Object LOCK = new Object();
    private static IShizukuUserService service;
    private static boolean binding;
    private static Shizuku.UserServiceArgs args;
    private static final ServiceConnection CONNECTION = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder binder) {
            synchronized (LOCK) { service = IShizukuUserService.Stub.asInterface(binder); binding = false; LOCK.notifyAll(); }
        }
        public void onServiceDisconnected(ComponentName name) {
            synchronized (LOCK) { service = null; binding = false; LOCK.notifyAll(); }
        }
    };
    private ShizukuRemoteProcesses() { }
    private static IShizukuUserService service(long timeoutMillis) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IOException("Shizuku命令必须在工作线程执行");
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1, timeoutMillis));
        if (!ShizukuClient.ensureConnected(Math.min(3000, Math.max(1, timeoutMillis)))) throw new IOException("Shizuku未运行或Binder未连接");
        if (!ShizukuShell.hasPermission()) throw new IOException("尚未授予Shizuku权限");
        if (Thread.currentThread().isInterrupted()) throw new IOException("Shizuku命令已取消");
        synchronized (LOCK) {
            if (service != null && service.asBinder().pingBinder()) return service;
            if (!binding) {
                Context context = ShizukuClient.context();
                if (context == null) throw new IOException("Shizuku上下文尚未初始化");
                String suffix = "aijs_shizuku_" + android.os.Process.myPid();
                args = new Shizuku.UserServiceArgs(new ComponentName(context.getPackageName(), ShizukuUserService.class.getName()))
                        .tag(suffix).processNameSuffix(suffix).version(1).debuggable(false);
                binding = true;
                try { Shizuku.bindUserService(args, CONNECTION); }
                catch (Throwable error) { binding = false; throw new IOException("无法连接Shizuku执行服务", error); }
            }
            while (service == null || !service.asBinder().pingBinder()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) { binding = false; throw new IOException("timeout: 等待Shizuku执行服务超时"); }
                try { LOCK.wait(Math.max(1, remaining / 1_000_000)); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException("Shizuku连接已取消", error); }
            }
            return service;
        }
    }
    public static Process start(String command, File directory, Map<String, String> env, boolean merge) throws IOException {
        return start(command, directory, env, merge, 8000);
    }
    public static Process start(String command, File directory, Map<String, String> env, boolean merge, long timeoutMillis) throws IOException {
        ShizukuCommands.validateCommand(command);
        List<String> environment = new ArrayList<>();
        if (env != null) for (Map.Entry<String,String> entry : env.entrySet()) {
            if (!entry.getKey().matches("[A-Za-z_][A-Za-z0-9_]*") || entry.getValue() == null || entry.getValue().indexOf('\0') >= 0)
                throw new IllegalArgumentException("无效环境变量");
            environment.add(entry.getKey() + "=" + entry.getValue());
        }
        IBinder owner = new Binder();
        try { return new RemoteProcess(service(timeoutMillis).start(new String[]{"/system/bin/sh", "-c", command}, environment.toArray(new String[0]),
                directory == null ? null : directory.getAbsolutePath(), merge, owner), owner); }
        catch (RemoteException error) { throw new IOException("Shizuku执行服务已断开", error); }
    }
    public static Process install(File[] files, String[] options) throws IOException {
        if (files == null || files.length < 1 || files.length > 128) throw new IllegalArgumentException("需要1..128个APK文件");
        ShizukuCommands.installOptions(options, android.os.Process.myUid() / 100000);
        ParcelFileDescriptor[] handles = new ParcelFileDescriptor[files.length]; long[] sizes = new long[files.length];
        try {
            for (int i = 0; i < files.length; i++) {
                if (!files[i].isFile() || files[i].length() <= 0) throw new IOException("APK不存在或为空：" + files[i]);
                sizes[i] = files[i].length(); handles[i] = ParcelFileDescriptor.open(files[i], ParcelFileDescriptor.MODE_READ_ONLY);
            }
            IBinder owner = new Binder();
            return new RemoteProcess(service(8000).install(handles, sizes, options == null ? new String[0] : options, owner), owner);
        } catch (RemoteException error) { throw new IOException("Shizuku安装服务已断开", error); }
        finally { for (ParcelFileDescriptor handle : handles) if (handle != null) try { handle.close(); } catch (IOException ignored) { } }
    }
    private static final class RemoteProcess extends Process {
        private final IShizukuProcess remote;
        @SuppressWarnings("unused") private final IBinder owner;
        private final InputStream stdout, stderr; private final OutputStream stdin;
        RemoteProcess(IShizukuProcess remote, IBinder owner) throws IOException, RemoteException {
            if (remote == null) throw new IOException("Shizuku未返回进程句柄");
            this.remote = remote; this.owner = owner;
            OutputStream openedInput = null;
            InputStream openedOutput = null, openedError = null;
            try {
                openedInput = new ParcelFileDescriptor.AutoCloseOutputStream(remote.stdin());
                openedOutput = new ParcelFileDescriptor.AutoCloseInputStream(remote.stdout());
                openedError = new ParcelFileDescriptor.AutoCloseInputStream(remote.stderr());
            } catch (Throwable error) {
                try { remote.destroy(); } catch (RemoteException ignored) { }
                for (java.io.Closeable stream : new java.io.Closeable[]{openedInput, openedOutput, openedError}) {
                    if (stream != null) try { stream.close(); } catch (IOException ignored) { }
                }
                throw new IOException(error);
            }
            stdin = openedInput; stdout = openedOutput; stderr = openedError;
        }
        public OutputStream getOutputStream() { return stdin; }
        public InputStream getInputStream() { return stdout; }
        public InputStream getErrorStream() { return stderr; }
        public int waitFor() throws InterruptedException {
            // Poll instead of permanently occupying a Binder thread; interruption cancels remotely.
            while (true) {
                if (Thread.interrupted()) { destroy(); throw new InterruptedException(); }
                try { int value = remote.exitValue(); if (value != Integer.MIN_VALUE) return value; }
                catch (RemoteException error) { throw new IllegalStateException("Shizuku执行进程失联", error); }
                Thread.sleep(25);
            }
        }
        public int exitValue() {
            try { int value = remote.exitValue(); if (value == Integer.MIN_VALUE) throw new IllegalThreadStateException("process hasn't exited"); return value; }
            catch (RemoteException error) { throw new IllegalStateException(error); }
        }
        public void destroy() {
            try { remote.destroy(); } catch (RemoteException ignored) { }
            try { stdin.close(); } catch (IOException ignored) { }
        }
    }
}
