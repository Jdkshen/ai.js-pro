package com.stardust.autojs.runtime.api;

import android.content.Context;

import com.stardust.autojs.annotation.ScriptInterface;
import com.stardust.autojs.runtime.ScriptRuntime;

import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Locale;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * PDF §2.9 `$debug`：内存 dump、堆栈、内存泄露检测、建议 GC。
 *
 * <p>文档：「Debug 模块提供了一些调试工具，比如诊断内存泄露，获取一个 Error 的详细堆栈等。」
 */
public class Debug {

    private final ScriptRuntime mRuntime;

    public Debug(ScriptRuntime runtime) {
        mRuntime = runtime;
        // 泄露日志走脚本 console（文档：「将会在日志中打印没有手动回收的对象」）。
        MemoryLeakDetector.setReporter(message -> {
            try {
                runtime.console.warn(message);
            } catch (Throwable ignored) {
                // 引擎已退出时 console 可能不可用，忽略即可
            }
        });
        // 文档：「在 Auto.js Pro 运行时，此功能默认开启；在打包软件中，此功能默认关闭」——
        // 打包产物是 inrt 模板应用（包名/类名里带 inrt），据此区分。
        MemoryLeakDetector.setEnabled(!isPackagedApp());
    }

    /** `$debug.dumpHprof(file)`：把整个脚本进程的内存 dump 到文件（期间进程会卡住）。 */
    @ScriptInterface
    public String dumpHprof(String file) {
        String path = resolvePath(file);
        try {
            File target = new File(path);
            File parent = target.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            android.os.Debug.dumpHprofData(path);
            // dump 是内存问题的现场，顺带把已有的泄露报出来
            MemoryLeakDetector.drain();
            return path;
        } catch (Exception e) {
            throw new RuntimeException("dumpHprof failed: " + e.getMessage());
        }
    }

    /** `$debug.dumpAndSendHprof([file])`：dump 后压缩成 zip（最高压缩等级），默认 `./dump.hprof.zip`。 */
    @ScriptInterface
    public String dumpAndSendHprof() {
        return dumpAndSendHprof(null);
    }

    @ScriptInterface
    public String dumpAndSendHprof(String file) {
        String zipPath = file == null || file.isEmpty() ? resolvePath("dump.hprof.zip") : resolvePath(file);
        String tempPath = zipPath + ".tmp.hprof";
        dumpHprof(tempPath);
        try {
            zipHighest(tempPath, zipPath);
        } catch (Exception e) {
            throw new RuntimeException("dumpAndSendHprof failed: " + e.getMessage());
        } finally {
            new File(tempPath).delete();
        }
        return zipPath;
    }

    /** `$debug.getStackTrace(error)`：取异常的详细堆栈。 */
    @ScriptInterface
    public String getStackTrace(Object error) {
        if (error == null || error instanceof org.mozilla.javascript.Undefined) {
            return "";
        }
        Object unwrapped = error instanceof org.mozilla.javascript.Wrapper
                ? ((org.mozilla.javascript.Wrapper) error).unwrap() : error;
        if (unwrapped instanceof Throwable) {
            return stackTraceOf((Throwable) unwrapped);
        }
        if (error instanceof Scriptable) {
            Scriptable scriptable = (Scriptable) error;
            String name = String.valueOf(ScriptableObject.getProperty(scriptable, "name"));
            String message = String.valueOf(ScriptableObject.getProperty(scriptable, "message"));
            Object stack = ScriptableObject.getProperty(scriptable, "stack");
            String head = name + ": " + message;
            if (stack != Scriptable.NOT_FOUND && stack != null) {
                return head + "\n" + stack;
            }
            return head;
        }
        return String.valueOf(error);
    }

    /** `$debug.setMemoryLeakDetectionEnabled(enabled)`：开启后会打印没有手动回收的对象。 */
    @ScriptInterface
    public void setMemoryLeakDetectionEnabled(boolean enabled) {
        MemoryLeakDetector.setEnabled(enabled);
    }

    /** 文档没写、验收用的查询接口：当前是否开启检测。 */
    @ScriptInterface
    public boolean isMemoryLeakDetectionEnabled() {
        return MemoryLeakDetector.isEnabled();
    }

    /** 文档没写、验收用的查询接口：累计已经报出来的泄露对象数。 */
    @ScriptInterface
    public int getMemoryLeakCount() {
        MemoryLeakDetector.drain();
        return MemoryLeakDetector.getLeakCount();
    }

    /** `$debug.gc()`：建议 JVM 进行垃圾回收（并不一定进行垃圾回收）。 */
    @ScriptInterface
    public void gc() {
        System.gc();
        Runtime.getRuntime().runFinalization();
        if (!MemoryLeakDetector.isEnabled()) {
            return;
        }
        // 引用队列是异步填充的，给一点时间把刚被回收的对象统计出来
        for (int i = 0; i < 5; i++) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            MemoryLeakDetector.drain();
        }
    }

    private String resolvePath(String file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("需要文件路径");
        }
        try {
            return mRuntime.files.path(file);
        } catch (Throwable e) {
            return file;
        }
    }

    private static String stackTraceOf(Throwable throwable) {
        StringWriter writer = new StringWriter();
        PrintWriter printer = new PrintWriter(writer);
        throwable.printStackTrace(printer);
        printer.flush();
        return writer.toString();
    }

    private static void zipHighest(String source, String dest) throws Exception {
        File destFile = new File(dest);
        File parent = destFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (ZipOutputStream zos = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(destFile)))) {
            zos.setLevel(Deflater.BEST_COMPRESSION);
            zos.putNextEntry(new ZipEntry(new File(source).getName()));
            byte[] buffer = new byte[64 * 1024];
            try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(source))) {
                int count;
                while ((count = bis.read(buffer)) > 0) {
                    zos.write(buffer, 0, count);
                }
            }
            zos.closeEntry();
        }
    }

    /**
     * 当前是不是打包产物（包名/类名里带 inrt）——文档规定泄露检测在运行端默认开、打包端默认关。
     * 脚本退出时 {@code ScriptRuntime.onExit()} 也用它把检测回置到默认值。
     */
    public static boolean isPackagedApp() {
        try {
            Context context = ScriptRuntime.getApplicationContext();
            if (context == null) {
                return false;
            }
            String packageName = context.getPackageName();
            String className = context.getApplicationInfo().className;
            return containsInrt(packageName) || containsInrt(className);
        } catch (Throwable e) {
            return false;
        }
    }

    private static boolean containsInrt(String value) {
        return value != null && value.toLowerCase(Locale.ROOT).contains("inrt");
    }
}
