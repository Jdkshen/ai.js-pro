package com.jdkshen.aijspro.tool;

/**
 * Created by Stardust on 2017/2/2.
 */


import android.content.Intent;
import android.os.Build;
import android.os.Looper;
import android.util.Log;

import com.stardust.app.GlobalAppContext;

import com.jdkshen.aijspro.BuildConfig;
import org.mozilla.javascript.RhinoException;

import com.stardust.view.accessibility.AccessibilityService;
import com.tencent.bugly.crashreport.BuglyLog;
import com.tencent.bugly.crashreport.CrashReport;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.lang.Thread.UncaughtExceptionHandler;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

public class CrashHandler extends CrashReport.CrashHandleCallback implements UncaughtExceptionHandler {
    private static final String TAG = "CrashHandler";
    private static int crashCount = 0;
    private static long firstCrashMillis = 0;
    private final Class<?> mErrorReportClass;
    private UncaughtExceptionHandler mBuglyHandler;
    private UncaughtExceptionHandler mSystemHandler;

    public CrashHandler(Class<?> errorReportClass) {
        this.mErrorReportClass = errorReportClass;
        mSystemHandler = Thread.getDefaultUncaughtExceptionHandler();
    }

    public void setBuglyHandler(UncaughtExceptionHandler buglyHandler) {
        mBuglyHandler = buglyHandler;
    }

    public void uncaughtException(Thread thread, Throwable ex) {
        Log.e(TAG, "Uncaught Exception", ex);
        appendCrashFile(thread, ex);
        if (thread != Looper.getMainLooper().getThread()) {
            if(!(ex instanceof RhinoException)){
                CrashReport.postCatchedException(ex, thread);
            }
            return;
        }
        AccessibilityService service = AccessibilityService.Companion.getInstance();
        if (service != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Log.d(TAG, "disable service: " + service);
            service.disableSelf();
        } else {
            BuglyLog.d(TAG, "cannot disable service: " + service);
        }
        if (BuildConfig.DEBUG) {
            mSystemHandler.uncaughtException(thread, ex);
        } else {
            mBuglyHandler.uncaughtException(thread, ex);
        }
    }

    @Override
    public synchronized Map<String, String> onCrashHandleStart(int crashType, String errorType,
                                                               String errorMessage, String errorStack) {
        Log.d(TAG, "onCrashHandleStart: crashType = " + crashType + ", errorType = " + errorType + ", msg = "
                + errorMessage + ", stack = " + errorStack);
        try {
            if (crashTooManyTimes())
                return super.onCrashHandleStart(crashType, errorType, errorMessage, errorStack);
            String msg = errorType + ": " + errorMessage;
            startErrorReportActivity(msg, errorStack);
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }
        return super.onCrashHandleStart(crashType, errorType, errorMessage, errorStack);
    }

    /**
     * 应用级崩溃也落一份文件（logs/app-crash.log）—— 跑脚本时整个应用崩掉也能事后看。
     * 只用普通文件 IO：这里可能已经处在不稳定状态，任何失败都必须静默。
     */
    private void appendCrashFile(Thread thread, Throwable ex) {
        try {
            File dir = com.stardust.autojs.core.console.ScriptRunLog.logsDir(GlobalAppContext.get());
            if (dir == null) {
                return;
            }
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File file = new File(dir, "app-crash.log");
            if (file.length() > 2L * 1024 * 1024) {
                File old = new File(dir, "app-crash.1.log");
                //noinspection ResultOfMethodCallIgnored
                old.delete();
                //noinspection ResultOfMethodCallIgnored
                file.renameTo(old);
            }
            try (FileWriter writer = new FileWriter(file, true)) {
                writer.append("════════ 应用崩溃 ")
                        .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date()))
                        .append(" 线程 ").append(String.valueOf(thread.getName()))
                        .append(" ════════\n");
                ex.printStackTrace(new PrintWriter(writer));
                writer.append('\n');
                writer.flush();
            }
        } catch (Throwable ignored) {
        }
    }

    private void startErrorReportActivity(String msg, String detail) {
        Intent intent = new Intent(GlobalAppContext.get(), this.mErrorReportClass);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra("message", msg);
        intent.putExtra("error", detail);
        GlobalAppContext.get().startActivity(intent);
    }

    private boolean crashTooManyTimes() {
        if (crashIntervalTooLong()) {
            resetCrashCount();
            return false;
        }
        crashCount++;
        return crashCount >= 5;
    }

    private void resetCrashCount() {
        firstCrashMillis = System.currentTimeMillis();
        crashCount = 0;
    }

    private boolean crashIntervalTooLong() {
        return System.currentTimeMillis() - firstCrashMillis > 3000;
    }


}