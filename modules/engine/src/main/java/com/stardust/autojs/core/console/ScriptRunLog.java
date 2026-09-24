package com.stardust.autojs.core.console;

import android.content.Context;
import android.util.Log;

import com.stardust.autojs.engine.JavaScriptEngine;
import com.stardust.autojs.engine.ScriptEngine;
import com.stardust.autojs.runtime.ScriptRuntime;
import com.stardust.autojs.runtime.exception.ScriptInterruptedException;
import com.stardust.autojs.script.ScriptSource;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 一次脚本运行的日志落盘 —— 跑完（尤其是崩溃）之后还能回头翻。
 *
 * <p>为什么要有它：控制台日志只存在内存里（{@link ConsoleImpl#getAllLogs()}），
 * 脚本一崩、界面一关、进程一死就全没了，用户没法事后排查。
 *
 * <p>落盘位置：{@code <externalFilesDir>/logs}（挂不上外置存储时退回 {@code files/logs}），
 * 不需要任何存储权限，手机文件管理器 / 数据线 / PC 都能直接看。
 * 文件名：{@code 2026-09-24_15-30-12_脚本名.log}；每次运行一个文件，互不混淆。
 *
 * <p>三个关键取舍：
 * <ul>
 *   <li><b>逐行 flush</b>：脚本线程、甚至整个进程被 native 崩掉时，已经打印的部分也留得住；</li>
 *   <li><b>绝不抛异常</b>：日志失败不能反过来把脚本搞挂，所有 IO 失败静默降级（只打 logcat）；</li>
 *   <li><b>单文件 4MB 上限 + 保留 7 天 / 200 个</b>：跑疯的脚本不会把存储写满。</li>
 * </ul>
 *
 * <p>挂接方式见 {@link #attach(ScriptEngine, ScriptSource, Context)}：挂在**这次运行自己的**
 * 控制台上（{@code runtime.console}），所以全局控制台、<globalconsole/> 都不受影响。
 */
public final class ScriptRunLog {

    private static final String TAG = "ScriptRunLog";
    private static final String DIR_NAME = "logs";
    private static final String FILE_EXT = ".log";
    /** 应用崩溃记录的文件名前缀（见 CrashHandler），不参与脚本日志轮转。 */
    public static final String CRASH_FILE_PREFIX = "app-crash";
    private static final long MAX_FILE_CHARS = 4L * 1024 * 1024;
    private static final int KEEP_FILES = 200;
    private static final long KEEP_MILLIS = 7L * 24 * 60 * 60 * 1000;

    private final File mFile;
    private final Writer mWriter;
    private final SimpleDateFormat mDateTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    private final SimpleDateFormat mLineTime = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private long mWritten = 0;
    private boolean mTruncated = false;
    private boolean mClosed = false;

    private ScriptRunLog(File dir, String scriptName) throws Exception {
        SimpleDateFormat fileTime = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US);
        mFile = uniqueFile(dir, fileTime.format(new Date()) + "_" + sanitize(scriptName));
        mWriter = new OutputStreamWriter(new FileOutputStream(mFile, true), StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------------
    // 对外入口
    // ---------------------------------------------------------------------

    /**
     * 给这次运行挂上落盘日志。返回的实例由调用方在运行结束（finally）时 {@link #finish}。
     * 任何环节失败都返回 null —— 调用方按“没有日志”继续跑即可。
     */
    public static ScriptRunLog attach(ScriptEngine engine, ScriptSource source, Context context) {
        try {
            if (!(engine instanceof JavaScriptEngine)) {
                // RootAutomator 之类没有 JS runtime，也就没有控制台。
                return null;
            }
            ScriptRuntime runtime = ((JavaScriptEngine) engine).getRuntime();
            if (runtime == null || !(runtime.console instanceof ConsoleImpl)) {
                return null;
            }
            // 引擎名用实现类（RhinoJavaScriptEngine / QuickJsJavaScriptEngine）：source.getEngineName()
            // 对没写指令的脚本给的是通用键（JavaScriptSource.Engine），看不出到底用的哪个。
            ScriptRunLog runLog = begin(context, source, engine.getClass().getSimpleName());
            if (runLog == null) {
                return null;
            }
            ((ConsoleImpl) runtime.console).setRunLog(runLog);
            return runLog;
        } catch (Throwable error) {
            warn("挂接脚本运行日志失败", error);
            return null;
        }
    }

    /** 按 Context + 脚本信息开一份日志（先建目录、清理过期文件，再写头）。 */
    public static ScriptRunLog begin(Context context, ScriptSource source, String engineName) {
        File dir = logsDir(context);
        if (dir == null) {
            return null;
        }
        return open(dir, source == null ? null : source.getName(), engineName);
    }

    /** 日志目录（可能不存在）；拿不到任何可写位置时返回 null。 */
    public static File logsDir(Context context) {
        if (context == null) {
            return null;
        }
        File base = null;
        try {
            base = context.getExternalFilesDir(null);
        } catch (Throwable ignored) {
        }
        if (base == null) {
            try {
                base = context.getFilesDir();
            } catch (Throwable ignored) {
            }
        }
        return base == null ? null : new File(base, DIR_NAME);
    }

    /** 最近的历史日志文件（新 → 旧，最多 max 个），日志页「历史」用。 */
    public static List<File> listRecent(Context context, int max) {
        File dir = logsDir(context);
        if (dir == null || !dir.isDirectory()) {
            return Collections.emptyList();
        }
        File[] files = dir.listFiles((d, name) -> name.endsWith(FILE_EXT));
        if (files == null || files.length == 0) {
            return Collections.emptyList();
        }
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        List<File> result = new ArrayList<>();
        for (File file : files) {
            if (result.size() >= max) {
                break;
            }
            result.add(file);
        }
        return result;
    }

    // ---------------------------------------------------------------------
    // 写日志
    // ---------------------------------------------------------------------

    /** 追加一行控制台输出（ConsoleImpl 的每个 println 都会走到这里）。 */
    public void line(int level, CharSequence text) {
        if (text == null) {
            return;
        }
        String content = text.toString();
        synchronized (this) {
            if (mClosed) {
                return;
            }
            if (mTruncated || mWritten > MAX_FILE_CHARS) {
                if (!mTruncated) {
                    mTruncated = true;
                    writeRaw("（本条运行日志超过上限，后续内容省略）\n");
                }
                return;
            }
            if (!content.endsWith("\n")) {
                content = content + "\n";
            }
            writeRaw(mLineTime.format(new Date()) + " [" + levelChar(level) + "] " + content);
        }
    }

    /**
     * 运行结束：写结尾（正常 / 被停止 / 异常 + 堆栈）并关文件。
     *
     * @param failure 引擎拿到的未捕获异常（没有就传 null）。
     */
    public void finish(Throwable failure) {
        boolean interrupted = failure != null && ScriptInterruptedException.causedByInterrupted(failure);
        String footer = failure == null ? "正常结束" : (interrupted ? "被停止" : "异常退出");
        synchronized (this) {
            if (mClosed) {
                return;
            }
            mClosed = true;
            writeRaw("\n════════ " + footer + " " + mDateTime.format(new Date()) + " ════════\n");
            if (failure != null && !interrupted) {
                writeRaw(stackTraceOf(failure));
            }
            try {
                mWriter.close();
            } catch (Throwable ignored) {
            }
        }
    }

    // ---------------------------------------------------------------------
    // 打开 / 轮转（包内可见，便于 JVM 单测直接跑文件逻辑）
    // ---------------------------------------------------------------------

    static ScriptRunLog open(File dir, String scriptName, String engineName) {
        try {
            if (dir == null) {
                return null;
            }
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return null;
            }
            pruneOldFiles(dir);
            ScriptRunLog runLog = new ScriptRunLog(dir, scriptName);
            runLog.writeRaw("════════ 脚本运行 " + runLog.mDateTime.format(new Date()) + " ════════\n");
            runLog.writeRaw("脚本: " + (scriptName == null || scriptName.isEmpty() ? "?" : scriptName) + "\n");
            runLog.writeRaw("引擎: " + (engineName == null || engineName.isEmpty() ? "?" : engineName) + "\n");
            runLog.writeRaw("──────────────────────────────\n");
            return runLog;
        } catch (Throwable error) {
            warn("创建脚本运行日志失败：" + dir, error);
            return null;
        }
    }

    /** 超过 200 个或超过 7 天的删掉；{@code app-crash*} 不归这里管。 */
    static void pruneOldFiles(File dir) {
        try {
            File[] files = dir.listFiles(
                    (d, name) -> name.endsWith(FILE_EXT) && !name.startsWith(CRASH_FILE_PREFIX));
            if (files == null || files.length == 0) {
                return;
            }
            Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            long cutoff = System.currentTimeMillis() - KEEP_MILLIS;
            for (int i = 0; i < files.length; i++) {
                if (i >= KEEP_FILES || files[i].lastModified() < cutoff) {
                    //noinspection ResultOfMethodCallIgnored
                    files[i].delete();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    static String sanitize(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "script";
        }
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_' || c > 0x2E80) {
                sb.append(c);
            } else {
                sb.append('-');
            }
        }
        String result = sb.toString();
        if (result.length() > 48) {
            result = result.substring(0, 48);
        }
        if (result.isEmpty() || result.equals("-")) {
            result = "script";
        }
        return result;
    }

    // ---------------------------------------------------------------------
    // 内部
    // ---------------------------------------------------------------------

    private static File uniqueFile(File dir, String base) {
        File file = new File(dir, base + FILE_EXT);
        int i = 2;
        while (file.exists() && i < 100) {
            file = new File(dir, base + "-" + i + FILE_EXT);
            i++;
        }
        return file;
    }

    /** 调用方负责持有本对象的锁（line / finish 都在锁里）。 */
    private void writeRaw(String text) {
        try {
            mWriter.write(text);
            mWriter.flush();
            mWritten += text.length();
        } catch (Throwable error) {
            warn("写脚本运行日志失败：" + mFile, error);
        }
    }

    private static String stackTraceOf(Throwable failure) {
        try {
            return ScriptRuntime.getStackTrace(failure, true);
        } catch (Throwable ignored) {
            StringWriter writer = new StringWriter();
            failure.printStackTrace(new PrintWriter(writer));
            return writer.toString();
        }
    }

    private static char levelChar(int level) {
        switch (level) {
            case Log.VERBOSE:
                return 'V';
            case Log.DEBUG:
                return 'D';
            case Log.INFO:
                return 'I';
            case Log.WARN:
                return 'W';
            case Log.ERROR:
                return 'E';
            case Log.ASSERT:
                return 'A';
            default:
                return 'D';
        }
    }

    private static void warn(String message, Throwable error) {
        try {
            Log.w(TAG, message, error);
        } catch (Throwable ignored) {
            // JVM 单测环境（android.jar stub）里 Log 会抛 Stub! —— 忽略
        }
    }
}
