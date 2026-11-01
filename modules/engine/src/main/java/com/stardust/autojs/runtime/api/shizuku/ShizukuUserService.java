package com.stardust.autojs.runtime.api.shizuku;

import android.os.Binder;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.system.Os;
import android.system.OsConstants;
import androidx.annotation.Keep;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Runs only in Shizuku's separately created shell/root process, never the app process. */
@Keep
public final class ShizukuUserService extends IShizukuUserService.Stub {
    private final Set<Job> jobs = Collections.newSetFromMap(new ConcurrentHashMap<Job, Boolean>());
    private final ScheduledExecutorService idle = Executors.newSingleThreadScheduledExecutor();
    private volatile long lastUse = System.nanoTime();
    private final AtomicBoolean closing = new AtomicBoolean();
    @Keep public ShizukuUserService() {
        idle.scheduleWithFixedDelay(() -> {
            synchronized (ShizukuUserService.this) {
                if (jobs.isEmpty() && System.nanoTime() - lastUse > 30_000_000_000L) destroy();
            }
        }, 30, 10, TimeUnit.SECONDS);
    }
    private void admit(Job job) throws IOException {
        if (closing.get() || jobs.size() >= 16) throw new IOException("Shizuku执行任务已满或服务正在停止");
        jobs.add(job); lastUse = System.nanoTime();
    }
    @Override public synchronized IShizukuProcess start(String[] argv, String[] environment, String directory, boolean merge, IBinder owner) {
        try {
            if (closing.get() || jobs.size() >= 16) throw new IOException("Shizuku执行任务已满或服务正在停止");
            ShizukuCommands.argv(argv);
            ShellJob job = new ShellJob(owner, argv, environment, directory, merge); admit(job); job.begin(); return job;
        } catch (IOException error) { throw new IllegalStateException(error.getMessage(), error); }
    }
    @Override public synchronized IShizukuProcess install(ParcelFileDescriptor[] files, long[] sizes, String[] options, IBinder owner) {
        try {
            if (closing.get() || jobs.size() >= 16) throw new IOException("Shizuku执行任务已满或服务正在停止");
            if (files == null || sizes == null || files.length < 1 || files.length > 128 || sizes.length != files.length)
                throw new IOException("APK文件列表无效");
            List<String> parsed = ShizukuCommands.installOptions(options, Binder.getCallingUid() / 100000);
            long total = 0;
            for (int i = 0; i < files.length; i++) {
                if (files[i] == null || sizes[i] <= 0 || sizes[i] > Long.MAX_VALUE - total) throw new IOException("APK长度无效");
                total += sizes[i];
            }
            InstallJob job = new InstallJob(owner, files, sizes, parsed, total); admit(job); job.begin(); return job;
        } catch (IOException | RuntimeException error) {
            if (files != null) for (ParcelFileDescriptor file : files) close(file);
            throw new IllegalStateException(error.getMessage(), error);
        }
    }
    @Override public synchronized void destroy() {
        if (!closing.compareAndSet(false, true)) return;
        for (Job job : jobs) job.destroy();
        new Thread(() -> {
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (!jobs.isEmpty() && System.nanoTime() < deadline) try { Thread.sleep(25); } catch (InterruptedException ignored) { break; }
            // This is the dedicated UserService process, not Application/Node/MCP.
            System.exit(0);
        }, "shizuku-service-destroy").start();
    }
    private abstract class Job extends IShizukuProcess.Stub {
        final IBinder owner;
        final AtomicBoolean cancelled = new AtomicBoolean();
        final CountDownLatch complete = new CountDownLatch(1);
        final ParcelFileDescriptor[] out = ParcelFileDescriptor.createPipe(), err = ParcelFileDescriptor.createPipe();
        final OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(out[1]);
        final OutputStream error = new ParcelFileDescriptor.AutoCloseOutputStream(err[1]);
        volatile OwnedProcess active;
        volatile int exit = Integer.MIN_VALUE;
        final IBinder.DeathRecipient death = this::destroy;
        Job(IBinder owner) throws IOException {
            if (owner == null) throw new IOException("缺少命令所有者");
            this.owner = owner;
            try { owner.linkToDeath(death, 0); } catch (RemoteException failure) { close(output); close(error); close(out[0]); close(err[0]); throw new IOException("命令所有者已结束", failure); }
        }
        void begin() {
            new Thread(() -> {
                int code = 1;
                try { ensureRunning(); code = run(); }
                catch (Throwable failure) { try { error.write((failure.getMessage() + "\n").getBytes(StandardCharsets.UTF_8)); } catch (IOException ignored) { } }
                finally {
                    OwnedProcess process = active; if (process != null) process.kill();
                    try { cleanup(); } catch (Throwable ignored) { }
                    close(output); close(error); try { owner.unlinkToDeath(death, 0); } catch (Throwable ignored) { }
                    exit = cancelled.get() ? 130 : code; complete.countDown(); lastUse = System.nanoTime(); jobs.remove(this);
                }
            }, "shizuku-owned-job").start();
        }
        void ensureRunning() throws IOException { if (cancelled.get()) throw new IOException("命令已取消"); }
        abstract int run() throws Exception;
        void cleanup() { }
        @Override public synchronized ParcelFileDescriptor stdout() { ParcelFileDescriptor result = out[0]; out[0] = null; return result; }
        @Override public synchronized ParcelFileDescriptor stderr() { ParcelFileDescriptor result = err[0]; err[0] = null; return result; }
        @Override public ParcelFileDescriptor stdin() {
            try { return ParcelFileDescriptor.open(new File("/dev/null"), ParcelFileDescriptor.MODE_WRITE_ONLY); }
            catch (IOException failure) { throw new IllegalStateException(failure); }
        }
        @Override public int exitValue() { return exit; }
        @Override public int waitFor() { try { complete.await(); return exit; } catch (InterruptedException failure) { Thread.currentThread().interrupt(); return 130; } }
        @Override public void destroy() {
            if (!cancelled.compareAndSet(false, true)) return;
            OwnedProcess process = active; if (process != null) process.kill();
            synchronized (this) { close(out[0]); out[0] = null; close(err[0]); err[0] = null; }
        }
        OwnedProcess launch(String[] argv, String[] env, String dir, boolean merge) throws Exception {
            ensureRunning(); OwnedProcess process = new OwnedProcess(argv, env, dir, merge); active = process;
            if (cancelled.get()) { process.kill(); throw new IOException("命令已取消"); }
            return process;
        }
    }
    private final class ShellJob extends Job {
        final String[] argv, environment; final String directory; final boolean merge;
        final ParcelFileDescriptor[] in = ParcelFileDescriptor.createPipe();
        ShellJob(IBinder owner, String[] argv, String[] environment, String directory, boolean merge) throws IOException {
            super(owner); this.argv = argv; this.environment = environment; this.directory = directory; this.merge = merge;
        }
        @Override public synchronized ParcelFileDescriptor stdin() { ParcelFileDescriptor result = in[1]; in[1] = null; return result; }
        @Override int run() throws Exception {
            OwnedProcess process = launch(argv, environment, directory, merge);
            Thread input = pump(new ParcelFileDescriptor.AutoCloseInputStream(in[0]), process.process.getOutputStream(), true);
            Thread stdout = pump(process.stdout, output, false), stderr = pump(process.process.getErrorStream(), error, false);
            int code = process.process.waitFor(); process.kill(); close(in[0]);
            stdout.join(2000); stderr.join(2000); input.interrupt(); return code;
        }
        @Override void cleanup() { close(in[0]); close(in[1]); }
        @Override public void destroy() { super.destroy(); if (in != null) close(in[0]); }
    }
    private final class InstallJob extends Job {
        final ParcelFileDescriptor[] files; final long[] sizes; final List<String> options; final long total;
        int session = -1; boolean committed;
        InstallJob(IBinder owner, ParcelFileDescriptor[] files, long[] sizes, List<String> options, long total) throws IOException {
            super(owner); this.files = files; this.sizes = sizes; this.options = options; this.total = total;
        }
        @Override int run() throws Exception {
            List<String> create = new ArrayList<>(); create.add("pm"); create.add("install-create"); create.addAll(options);
            create.add("-S"); create.add(String.valueOf(total));
            CommandResult created = command(create.toArray(new String[0]), null, 0, false);
            Matcher matcher = Pattern.compile("\\[([0-9]+)\\]").matcher(created.text);
            if (created.code != 0 || !matcher.find()) throw new IOException("创建安装会话失败：" + created.text);
            session = Integer.parseInt(matcher.group(1));
            for (int i = 0; i < files.length; i++) {
                ensureRunning();
                try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(files[i])) {
                    CommandResult written = command(new String[]{"pm", "install-write", "-S", String.valueOf(sizes[i]), String.valueOf(session), "part" + i + ".apk", "-"}, input, sizes[i], false);
                    if (written.code != 0) throw new IOException("写入APK失败：" + written.text);
                }
            }
            ensureRunning();
            CommandResult result = command(new String[]{"pm", "install-commit", String.valueOf(session)}, null, 0, false);
            committed = result.code == 0;
            output.write(result.text.getBytes(StandardCharsets.UTF_8));
            return result.code;
        }
        CommandResult command(String[] argv, InputStream input, long expected, boolean abandon) throws Exception {
            OwnedProcess process = abandon ? new OwnedProcess(argv, null, null, true) : launch(argv, null, null, true);
            ByteArrayOutputStream capture = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> { try { drain(process.stdout, capture, 65536); } catch (IOException ignored) { } }, "shizuku-pm-output"); reader.start();
            try {
                try (OutputStream target = process.process.getOutputStream()) {
                    if (input != null) {
                        byte[] buffer = new byte[65536]; long copied = 0; int count;
                        while ((count = input.read(buffer)) != -1) { ensureRunning(); target.write(buffer, 0, count); copied += count; }
                        if (copied != expected) throw new IOException("APK写入期间文件长度改变");
                    }
                }
                if (!waitTimed(process.process, abandon ? 5 : 120)) throw new IOException("安装命令超时");
                reader.join(2000);
                return new CommandResult(process.process.exitValue(), new String(capture.toByteArray(), StandardCharsets.UTF_8));
            } finally { process.kill(); reader.join(1000); }
        }
        @Override void cleanup() {
            for (ParcelFileDescriptor file : files) close(file);
            if (session >= 0 && !committed) try { command(new String[]{"pm", "install-abandon", String.valueOf(session)}, null, 0, true); } catch (Exception ignored) { }
        }
    }
    private static final class CommandResult {
        final int code; final String text;
        CommandResult(int code, String text) { this.code = code; this.text = text; }
    }
    private static final class OwnedProcess {
        final Process process; final InputStream stdout; final int group;
        private final AtomicBoolean killed = new AtomicBoolean();
        OwnedProcess(String[] argv, String[] environment, String directory, boolean merge) throws Exception {
            String command = "printf '%s\\n' \"$$\"; exec " + ShizukuCommands.argv(argv);
            ProcessBuilder builder = new ProcessBuilder("/system/bin/setsid", "-w", "/system/bin/sh", "-c", command).redirectErrorStream(merge);
            if (directory != null && !directory.isEmpty()) {
                File dir = new File(directory); if (!dir.isDirectory()) throw new IOException("Shizuku无法访问工作目录：" + directory); builder.directory(dir);
            }
            if (environment != null) {
                if (environment.length > 128) throw new IOException("环境变量过多");
                for (String entry : environment) {
                    int equal = entry.indexOf('=');
                    if (equal < 1 || !entry.substring(0, equal).matches("[A-Za-z_][A-Za-z0-9_]*") || entry.indexOf('\0') >= 0) throw new IOException("环境变量无效");
                    builder.environment().put(entry.substring(0, equal), entry.substring(equal + 1));
                }
            }
            process = builder.start(); stdout = process.getInputStream();
            FutureTask<Integer> handshake = new FutureTask<>(() -> {
                StringBuilder value = new StringBuilder(); int ch;
                while ((ch = stdout.read()) != -1 && ch != '\n' && value.length() < 16) value.append((char) ch);
                return Integer.parseInt(value.toString());
            });
            Thread reader = new Thread(handshake, "shizuku-pgid"); reader.setDaemon(true); reader.start();
            try {
                group = handshake.get(3, TimeUnit.SECONDS);
                if (group <= 1) throw new IOException("Shizuku进程组无效");
                // A short command may already have exited; its group can still contain children.
            } catch (Throwable failure) { process.destroy(); handshake.cancel(true); throw new IOException("Shizuku进程初始化失败", failure); }
        }
        void kill() {
            if (!killed.compareAndSet(false, true)) return;
            try { Os.kill(-group, OsConstants.SIGKILL); } catch (Exception ignored) { }
            try { process.exitValue(); } catch (IllegalThreadStateException running) { process.destroy(); }
            close(process.getOutputStream());
        }
    }
    private static Thread pump(InputStream input, OutputStream output, boolean closeOutput) {
        Thread thread = new Thread(() -> { try { byte[] buffer = new byte[16384]; int count; while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count); }
            catch (IOException ignored) { } finally { close(input); if (closeOutput) close(output); } }, "shizuku-pipe");
        thread.setDaemon(true); thread.start(); return thread;
    }
    private static void drain(InputStream input, ByteArrayOutputStream output, int max) throws IOException {
        byte[] buffer = new byte[4096]; int count;
        while ((count = input.read(buffer)) != -1) { int keep = Math.min(count, max - output.size()); if (keep > 0) output.write(buffer, 0, keep); }
    }
    private static boolean waitTimed(Process process, int seconds) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        do { try { process.exitValue(); return true; } catch (IllegalThreadStateException running) { Thread.sleep(25); } } while (System.nanoTime() < end);
        return false;
    }
    private static void close(Closeable value) { if (value != null) try { value.close(); } catch (IOException ignored) { } }
}
