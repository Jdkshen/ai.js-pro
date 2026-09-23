package com.jdkshen.aijspro.external;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import com.stardust.autojs.execution.ScriptExecution;
import com.stardust.autojs.execution.ScriptExecutionListener;

import com.jdkshen.aijspro.autojs.AutoJs;
import com.jdkshen.aijspro.external.open.RunIntentActivity;

/**
 * 「外部 Intent 跑脚本」的断点续跑。
 *
 * 背景：这类常驻脚本（示例：象棋面板「悬浮窗运行与测试.js」）是靠外部命令
 * <pre>am start -n …/RunIntentActivity --es path /sdcard/…/脚本.js</pre>
 * 拉起来的。系统把进程杀掉后（实测 2026-09-21 K40：一天被杀 5 次，无 crash、无回调），
 * 前台服务会被自动重启，但**脚本不会自己跑** —— 面板窗口消失，断线期间游戏还会进入
 * 自带「托管」胡走。本类补上「进程重启后自动把上次没跑完的外部脚本重新拉起」：
 *
 * <ol>
 *   <li>{@link #recordStart}：每一次外部「按路径运行」启动成功都登记 {path, startedAt}，
 *       并在内存里记住那次 {@link ScriptExecution} 本体；</li>
 *   <li>同一个进程里该 execution 正常结束（onSuccess / onException，含用户点停止）
 *       ⇒ 清除登记（用**对象身份**比对而不是 id —— 进程重启后 id 会从头计数，比对会误伤）；</li>
 *   <li>{@link #resumeIfNeeded}（App.onCreate 调用，每次进程启动必经）延迟几秒检查：
 *       登记还在 = 上次是「跑到一半进程没了」⇒ 重新拉起（走和人工 am start 完全相同的
 *       RunIntentActivity 入口；起 Activity 失败时退化为直跑脚本）。</li>
 * </ol>
 *
 * 防重复 / 防惊扰：
 * <ul>
 *   <li>任何一次真实启动都会刷新 startedAt；重启后 60s 内已有启动（系统顺带恢复了
 *       Activity、人工 am start 等）⇒ 跳过续跑；</li>
 *   <li>登记超过 {@link #STALE_MS}（3 小时）视为过期，直接作废（面板任务本身最多 2 小时）；</li>
 *   <li>只在主进程生效（LeakCanary 分析进程等不掺和）；</li>
 *   <li>用户主动停止脚本 ⇒ 正常结束 ⇒ 登记被清，不会被续跑。</li>
 * </ul>
 */
public final class ExternalRunResume {

    private static final String TAG = "ExternalRunResume";
    private static final String PREFS = "external_run_resume";
    private static final String KEY_PATH = "path";
    private static final String KEY_STARTED_AT = "started_at";
    private static final String KEY_ACTIVE = "active";

    /** 超过这个年龄的登记不再续跑（面板任务本身最多 2h）。 */
    private static final long STALE_MS = 3 * 60 * 60 * 1000L;
    /** 重启后延迟一下再续跑，等前台服务/初始化就位。 */
    private static final long RESUME_DELAY_MS = 6000L;
    /** 重启后 60s 内已有过真实启动 ⇒ 不续跑（防和系统恢复 / 人工拉起撞车）。 */
    private static final long DEDUP_MS = 60 * 1000L;

    private static ScriptExecution sTrackedExecution;
    private static boolean sListenerInstalled;

    private ExternalRunResume() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 由 {@link ScriptIntents#execute} 调用：登记一次外部按路径运行。 */
    public static void recordStart(Context context, String path, ScriptExecution execution) {
        if (context == null || execution == null || TextUtils.isEmpty(path)) {
            return;
        }
        try {
            sTrackedExecution = execution;
            prefs(context).edit()
                    .putString(KEY_PATH, path)
                    .putLong(KEY_STARTED_AT, System.currentTimeMillis())
                    .putBoolean(KEY_ACTIVE, true)
                    .apply();
            installFinishListener(context);
            Log.i(TAG, "record start: " + path);
        } catch (Throwable e) {
            Log.w(TAG, "record start failed", e);
        }
    }

    /** 该 execution 正常结束（成功 / 异常 / 被停止）⇒ 登记作废。 */
    private static void installFinishListener(final Context context) {
        if (sListenerInstalled) {
            return;
        }
        try {
            AutoJs.getInstance().getScriptEngineService()
                    .registerGlobalScriptExecutionListener(new ScriptExecutionListener() {
                        @Override
                        public void onStart(ScriptExecution execution) {
                        }

                        @Override
                        public void onSuccess(ScriptExecution execution, Object result) {
                            clearIfTracked(context, execution);
                        }

                        @Override
                        public void onException(ScriptExecution execution, Throwable e) {
                            clearIfTracked(context, execution);
                        }
                    });
            sListenerInstalled = true;
        } catch (Throwable e) {
            Log.w(TAG, "install finish listener failed", e);
        }
    }

    private static void clearIfTracked(Context context, ScriptExecution execution) {
        // 身份比对（不用 id）：跨进程重启后 id 会从头计数，用 id 比对会误伤
        if (execution == null || execution != sTrackedExecution) {
            return;
        }
        sTrackedExecution = null;
        try {
            prefs(context).edit().putBoolean(KEY_ACTIVE, false).commit();
            Log.i(TAG, "tracked run finished, resume state cleared");
        } catch (Throwable ignored) {
        }
    }

    /** 由 App.onCreate 调用：上次「跑到一半进程没了」⇒ 自动重新拉起。 */
    public static void resumeIfNeeded(final Application app) {
        if (app == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 28) {
            String process = app.getProcessName();
            if (process != null && !process.equals(app.getPackageName())) {
                return; // 只在主进程续跑
            }
        }
        final Context ctx = app.getApplicationContext();
        final SharedPreferences sp = prefs(ctx);
        if (!sp.getBoolean(KEY_ACTIVE, false)) {
            return;
        }
        long startedAt = sp.getLong(KEY_STARTED_AT, 0L);
        if (TextUtils.isEmpty(sp.getString(KEY_PATH, null))) {
            return;
        }
        if (startedAt <= 0 || System.currentTimeMillis() - startedAt > STALE_MS) {
            sp.edit().putBoolean(KEY_ACTIVE, false).apply(); // 太老，作废
            Log.i(TAG, "stale resume state, discard");
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    resumeNow(ctx);
                } catch (Throwable e) {
                    Log.w(TAG, "resume failed", e);
                    note("断点续跑失败：" + e);
                }
            }
        }, RESUME_DELAY_MS);
    }

    private static void resumeNow(Context ctx) {
        SharedPreferences sp = prefs(ctx);
        if (!sp.getBoolean(KEY_ACTIVE, false)) {
            return; // 延迟期间脚本正常结束 / 登记被清
        }
        long startedAt = sp.getLong(KEY_STARTED_AT, 0L);
        long since = System.currentTimeMillis() - startedAt;
        if (since >= 0 && since < DEDUP_MS) {
            Log.i(TAG, "skip resume: a run started " + since + "ms ago");
            return; // 刚有人启动过（系统恢复 Activity / 人工 am start）
        }
        String path = sp.getString(KEY_PATH, null);
        if (TextUtils.isEmpty(path)) {
            return;
        }
        note("上次运行被系统中断（进程重启），自动重新拉起：" + path);
        // 与人工 `am start …RunIntentActivity --es path …` 完全同路。
        // 后台起 Activity 的豁免：本应用持有 SYSTEM_ALERT_WINDOW（悬浮窗权限），
        // Android 10+ 的「后台启动 Activity」限制对此放行。
        try {
            Intent intent = new Intent(ctx, RunIntentActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra(ScriptIntents.EXTRA_KEY_PATH, path);
            ctx.startActivity(intent);
        } catch (Throwable e) {
            // 兜底：直接执行（不起 Activity；面板本身靠悬浮窗自持）
            Log.w(TAG, "startActivity failed, fall back to direct execute", e);
            Intent intent = new Intent();
            intent.putExtra(ScriptIntents.EXTRA_KEY_PATH, path);
            ScriptExecution execution = ScriptIntents.execute(ctx, intent);
            if (execution == null) {
                note("自动重拉失败（脚本没有启动）：" + path);
            }
        }
    }

    private static void note(String message) {
        Log.i(TAG, message);
        try {
            AutoJs.getInstance().getGlobalConsole().info("[self-heal] " + message);
        } catch (Throwable ignored) {
        }
    }
}
