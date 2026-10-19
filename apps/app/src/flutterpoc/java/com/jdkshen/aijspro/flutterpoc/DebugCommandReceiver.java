package com.jdkshen.aijspro.flutterpoc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

import com.jdkshen.aijspro.autojs.AutoJs;
import com.jdkshen.aijspro.model.sample.SampleFiles;
import com.jdkshen.aijspro.timing.TimedTask;
import com.jdkshen.aijspro.timing.TimedTaskManager;
import com.stardust.autojs.execution.ExecutionConfig;

import java.io.File;
import java.io.IOException;

/**
 * POC 调试入口：adb 广播可靠触发脚本运行/停止/AUTO 门控，避免 UI 自动化点不准。
 * 仅 flutterpoc（debug POC）变体注册，正式构建不存在。
 *
 * 用法（README 式速查，只在本机 debug/真机验收用）：
 *
 * <pre>
 * # 1. 直接运行 App 内置示例（不用 push 到 /sdcard）：assetPath 相对 APK assets，
 * #    例：sample/Rhino 引擎/传感器/显示常用传感器信息.js
 * adb shell am broadcast -n com.jdkshen.aijspro/com.jdkshen.aijspro.flutterpoc.DebugCommandReceiver \
 *     -a com.jdkshen.aijspro.flutterpoc.DEBUG_RUN \
 *     -e assetPath "sample/Rhino 引擎/传感器/显示常用传感器信息.js"
 *
 * # 2. 运行磁盘上已有脚本
 * adb shell am broadcast -n ... -a com.jdkshen.aijspro.flutterpoc.DEBUG_RUN \
 *     -e path /sdcard/脚本/x.js [--ez autoInScript true]
 *
 * # 3. 定时运行磁盘脚本（N 秒后由定时任务调度）
 * adb shell am broadcast -n ... -a com.jdkshen.aijspro.flutterpoc.DEBUG_RUN \
 *     -e path /sdcard/脚本/x.js --ei timerSeconds 10
 *
 * # 4. 停止全部（宿主进程 :script/:native + 主进程引擎）
 * adb shell am broadcast -n ... -a com.jdkshen.aijspro.flutterpoc.DEBUG_RUN --ez stopAll true
 *     # 只停宿主进程、不动主进程：再追加 --ez stopMain false
 * </pre>
 *
 * <p>assetPath 的处理：把示例所在目录（含同目录伴生文件与子目录，示例用
 * {@code images.read("./x.png")} / {@code ui.layout("./x.xml")} / {@code require("./子模块.js")}
 * 这类相对路径）复制到 {@code getCacheDir()/debug-samples/&lt;hash&gt;-&lt;文件名&gt;}，
 * 再从磁盘运行；asset 不存在/复制失败时中文 Toast + Log，不会崩。
 */
public class DebugCommandReceiver extends BroadcastReceiver {

    private static final String TAG = "PocDebugCmd";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        try {
            String debugStopPath = intent.getStringExtra("stopPath");
            if (debugStopPath != null && !debugStopPath.isEmpty()) {
                boolean stopped = com.jdkshen.aijspro.flutterpoc.scriptproc.PocScriptHost.stopByPath(debugStopPath);
                Log.i(TAG, "debug stopPath: " + debugStopPath + " stopped=" + stopped);
                return;
            }
            if (intent.getBooleanExtra("volumeUp", false)) {
            // 调试用：模拟物理音量上键（adb 注入按键不会进入无障碍过滤键通道，只能这样验证停止链路）
            try {
                com.jdkshen.aijspro.autojs.key.GlobalKeyObserver.getSingleton().onVolumeUp();
                Log.i(TAG, "debug volumeUp: GlobalKeyObserver.onVolumeUp invoked");
            } catch (Throwable error) {
                Log.e(TAG, "debug volumeUp failed", error);
            }
            return;
        }
        if (intent.getBooleanExtra("stopAll", false)) {
                // 停两处：宿主进程（:script/:native）由 PocRuntime 管；主进程引擎里跑的
                // Rhino/UI 脚本必须走 ScriptEngineService.stopAll()，否则 ScriptExecuteActivity
                // 和 UI 会一直留在前台，污染后续调试/真机矩阵的截图。
                // 默认两者都停；需要只停宿主时传 --ez stopMain false。
                boolean stopMain = !intent.hasExtra("stopMain")
                        || intent.getBooleanExtra("stopMain", true);
                PocRuntime.stopAllHostScripts();
                int mainStopped = 0;
                if (stopMain) {
                    try {
                        mainStopped = AutoJs.getInstance().getScriptEngineService().stopAll();
                    } catch (Throwable error) {
                        Log.w(TAG, "停止主进程脚本失败", error);
                    }
                }
                if (stopMain) {
                    Log.i(TAG, "debug stopAll: host + main");
                    if (mainStopped > 0) {
                        Log.i(TAG, "debug stopAll: main stopped " + mainStopped);
                    }
                } else {
                    Log.i(TAG, "debug stopAll: host only");
                }
                return;
            }
            if (intent.getBooleanExtra("node24PoolSpike", false)) {
                try {
                    Intent start = new Intent();
                    start.setClassName(context, "com.jdkshen.aijspro.flutterpoc.scriptproc.ScriptHostService");
                    context.startService(start);
                    Intent spike = new Intent("com.jdkshen.aijspro.NODE24_POOL_SPIKE")
                            .setPackage(context.getPackageName());
                    context.sendBroadcast(spike);
                    Log.i(TAG, "debug node24PoolSpike dispatched");
                } catch (Throwable error) {
                    Log.e(TAG, "debug node24PoolSpike failed", error);
                }
                return;
            }
            if (intent.getBooleanExtra("node24Spike", false)) {
                // task-175 spike：启动 :node24 服务执行同进程双 Node24Engine 并行/取消验证。
                try {
                    Intent spike = new Intent();
                    spike.setClassName(context, "org.autojs.source.engine.android.Node24Service");
                    spike.setAction("com.jdkshen.aijspro.NODE24_SPIKE");
                    context.startService(spike);
                    Log.i(TAG, "debug node24Spike dispatched");
                } catch (Throwable error) {
                    Log.e(TAG, "debug node24Spike failed", error);
                }
                return;
            }
            if (intent.getBooleanExtra("taskPageStop", false)) {
                // task-166 复验：任务页/脚本列表「停止全部」统一入口。
                try {
                    PocRuntime.stopAllScripts(context.getApplicationContext());
                    Log.i(TAG, "debug taskPageStop dispatched");
                } catch (Throwable error) {
                    Log.e(TAG, "debug taskPageStop failed", error);
                }
                return;
            }
            if (intent.getBooleanExtra("notificationStop", false)) {
                // task-166 复验：模拟通知栏「停止」PendingIntent 的 action（ForegroundService）。
                try {
                    Intent stopIntent = new Intent(context,
                            com.jdkshen.aijspro.external.foreground.ForegroundService.class)
                            .setAction(com.jdkshen.aijspro.external.foreground.ForegroundService.ACTION_STOP_ALL_SCRIPTS);
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        context.startForegroundService(stopIntent);
                    } else {
                        context.startService(stopIntent);
                    }
                    Log.i(TAG, "debug notificationStop dispatched");
                } catch (Throwable error) {
                    Log.e(TAG, "debug notificationStop failed", error);
                }
                return;
            }
            if (intent.getBooleanExtra("floatingStop", false)) {
                // task-166 复验：悬浮菜单 ⏹ 停止入口。
                PocFloatingBar.debugStopAll();
                Log.i(TAG, "debug floatingStop dispatched");
                return;
            }
            if (intent.getBooleanExtra("autoInScript", false)) {
                PocRuntime.setAutoInScriptEnabled(true);
                Log.i(TAG, "debug autoInScript=true");
            } else if (intent.getBooleanExtra("autoInScriptOff", false)) {
                PocRuntime.setAutoInScriptEnabled(false);
                Log.i(TAG, "debug autoInScript=false");
            }
            // 内置示例：assetPath 直接跑 assets 里的示例（见类注释用法 1），与 path 互斥。
            String assetPath = intent.getStringExtra("assetPath");
            if (assetPath != null && assetPath.length() > 0) {
                runBundledSample(context, assetPath);
                return;
            }
            int timerSeconds = intent.getIntExtra("timerSeconds", 0);
            String path = intent.getStringExtra("path");
            if (timerSeconds > 0) {
                if (path == null || path.length() == 0) {
                    Log.w(TAG, "debug timer: path is empty");
                    return;
                }
                ExecutionConfig config = ExecutionConfig.getDefault();
                config.setWorkingDirectory(new File(path).getParentFile().getAbsolutePath());
                // timeFlag=0 表示一次性任务；直接用毫秒，避免 java.time 在部分编译配置下的兼容问题。
                long millis = System.currentTimeMillis() + timerSeconds * 1000L;
                TimedTask task = new TimedTask(millis, 0L, path, config);
                TimedTaskManager.getInstance().addTask(task);
                Log.i(TAG, "debug timer created: " + path + " in " + timerSeconds + "s id=" + task.getId());
                Toast.makeText(context, "调试定时任务已创建：" + timerSeconds + "s 后运行", Toast.LENGTH_SHORT).show();
                return;
            }
            if (path == null || path.length() == 0) {
                Log.w(TAG, "debug run: path is empty");
                return;
            }
            File file = new File(path);
            if (!file.exists()) {
                Log.w(TAG, "debug run: file not found: " + path);
                Toast.makeText(context, "调试运行失败：文件不存在", Toast.LENGTH_SHORT).show();
                return;
            }
            Log.i(TAG, "debug run: " + path);
            PocRuntime.runScript(context, path);
        } catch (Throwable e) {
            Log.e(TAG, "debug command failed", e);
            Toast.makeText(context, "调试命令失败：" + describe(e), Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------
    // 内置示例（assets/sample/...）直接运行
    // ------------------------------------------------------------------

    /** 调试示例复制目录名，统一放在 cacheDir 下，不污染用户脚本目录/示例同步目录。 */
    private static final String CACHE_SUB_DIR = "debug-samples";

    /**
     * 运行 App 内置示例：先复制到 cacheDir，再从磁盘路径交给 {@link PocRuntime#runScript}。
     *
     * <p>为什么先复制：示例的伴生资源都是相对路径（{@code images.read("./x.png")}、
     * {@code ui.layout("./x.xml")}、{@code require("./子模块.js")}），直接从 asset 读正文会让
     * 工作目录/相对路径全部失效；而 cacheDir 是进程可读写的临时目录，dex/so 也不受
     * /sdcard 可写文件加载限制。
     */
    private static void runBundledSample(Context context, String assetPath) {
        try {
            File script = prepareBundledSample(context, assetPath);
            Log.i(TAG, "debug asset run: " + assetPath + " -> " + script.getAbsolutePath());
            PocRuntime.runScript(context, script.getAbsolutePath());
        } catch (Throwable e) {
            // 失败必须给中文提示：广播调用方（adb/脚本）看不到 logcat 时也能确认是 asset 问题。
            Log.e(TAG, "debug asset run failed: " + assetPath, e);
            Toast.makeText(context, "调试运行内置示例失败：" + describe(e), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 把示例所在目录复制到 {@code cacheDir/debug-samples/<assetPath hash>-<文件名>}，
     * 返回入口脚本在磁盘上的路径。
     *
     * <p>连同目录一起复制（而不是单文件）：内置示例大量使用同目录伴生文件/子目录
     * （例如 YOLO 的 {@code DNN/models}），子目录体积上限沿用
     * {@link SampleFiles#MAX_SUBDIR_BYTES}（16MB，正好覆盖随包模型目录）。
     * 同名目录复用并覆盖：升级 APK 后再次广播能跑到新示例，而不是 cache 里的旧版本。
     */
    private static File prepareBundledSample(Context context, String assetPath) throws IOException {
        String normalized = normalizeAssetPath(assetPath);
        int slash = normalized.lastIndexOf('/');
        if (slash <= 0 || slash == normalized.length() - 1) {
            throw new IOException("assetPath 需形如 sample/Rhino 引擎/传感器/显示常用传感器信息.js");
        }
        String parent = normalized.substring(0, slash);
        String name = normalized.substring(slash + 1);
        if (!name.toLowerCase().endsWith(".js") && !name.toLowerCase().endsWith(".mjs")) {
            throw new IOException("assetPath 只支持 .js/.mjs 示例：" + normalized);
        }
        String key = Integer.toHexString(normalized.hashCode());
        File root = new File(context.getCacheDir(), CACHE_SUB_DIR);
        File dir = new File(root, key + "-" + name);
        SampleFiles.copyAssetTree(context, parent, dir, true, SampleFiles.MAX_SUBDIR_BYTES);
        File script = new File(dir, name);
        if (!script.isFile()) {
            throw new IOException("asset 不存在或无法复制：" + normalized);
        }
        return script;
    }

    /** 允许 {@code assets/} 前缀与反斜杠，最终统一成 APK assets 内路径（如 {@code sample/x/y.js}）。 */
    private static String normalizeAssetPath(String assetPath) {
        String path = assetPath == null ? "" : assetPath.trim().replace('\\', '/');
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.startsWith("assets/")) {
            path = path.substring("assets/".length());
        }
        return path;
    }

    /** 异常 → 一句可读中文（Toast/日志共用；空 message 回退类名，别显示 null）。 */
    private static String describe(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }
}
