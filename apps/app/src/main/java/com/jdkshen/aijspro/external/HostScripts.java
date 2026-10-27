package com.jdkshen.aijspro.external;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Phase 1：把主进程里的脚本触发/停止入口统一接到 {@code :script} 宿主进程的薄门面。
 *
 * <p>覆盖的调用点：定时任务触发（{@link com.jdkshen.aijspro.timing.TimedTaskScheduler}）、前台服务
 * 通知栏「停止」、音量上键停止（{@link com.jdkshen.aijspro.autojs.key.GlobalKeyObserver}）、悬浮菜单 /
 * 任务页 / 退出 / 编辑器 / 扩展调试服务器的「停止全部」。
 *
 * <p><b>为什么用反射</b>：flutterPoc 源集（{@code com.jdkshen.aijspro.flutterpoc.PocRuntime}、
 * {@code ...scriptproc.PocScriptHost}）只在 {@code -PflutterPoc=true} 的 debug 构建里参与编译
 * （见 app/build.gradle 的 sourceSets），主源集直接 import 会让普通构建编译失败；仓库里 Miuix 可选
 * 宿主（MiuixBubbleMenuHost / MiuixExplorerMenuHost 等）用的是同一套 {@link Class#forName} 可选依赖写法。
 *
 * <p><b>失败即回退</b>：类不存在（非 flutterPoc 构建）、Phase1 路由开关关闭、宿主同步绑定失败，
 * 一律返回 false / 静默返回，调用方继续走旧的主进程路径 —— 这就是任务要求里的 flag 保护。
 *
 * <p>运行入口统一转到 {@code PocRuntime.runScript(Context, String)} 而不是直接调
 * {@code PocScriptHost.run}：auto 模式暂留主进程 / native 项目 → {@code :native} / 其余 → {@code :script}
 * 的路由只在 PocRuntime 一处决定（PocRuntime 自己的注释也要求这样），避免「Flutter 页一套、定时任务一套」
 * 打架；宿主绑定失败时 PocRuntime 内部也会回退主进程旧路径。
 *
 * <p>已知边界：宿主 RUN 只是「已入队」，真正的启动确认与连接超时（PocScriptHost 内部 8s）主进程侧拿不到
 * 回调；要做「启动超时后自动回退」需要给 PocScriptHost 加 public 的 started/connected 查询，属跨源集
 * 改动（PocScriptHost 不属本任务写作用域），本任务只做「同步失败 → 立即回退」。
 */
public final class HostScripts {

    private static final String TAG = "HostScripts";

    /** Phase1 运行桥（包裹路由/任务列表/停止，见 PocRuntime 类注释）。 */
    private static final String POC_RUNTIME_CLASS =
            "com.jdkshen.aijspro.flutterpoc.PocRuntime";
    /** 宿主进程客户端（只用来读 Phase1 路由开关）。 */
    private static final String POC_SCRIPT_HOST_CLASS =
            "com.jdkshen.aijspro.flutterpoc.scriptproc.PocScriptHost";

    private static volatile boolean sResolved;
    private static Method sRunScript;
    private static Method sStopAllHostScripts;
    private static Method sStopAllScripts;
    private static Method sIsEnabled;
    private static Method sRunningNotificationTasks;
    private static Method sStopNotificationTask;

    private HostScripts() {
    }

    /**
     * 本次构建 + 当前开关下，宿主脚本进程是否可用。
     *
     * <p>普通（非 flutterPoc）构建里类不存在 → false；flutterPoc 构建里再叠加
     * {@code PocScriptHost.isEnabled()} 的 Phase1 路由开关。
     */
    public static boolean isAvailable() {
        resolve();
        Method enabled = sIsEnabled;
        if (enabled == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(enabled.invoke(null));
        } catch (Throwable error) {
            Log.w(TAG, "读取脚本宿主进程开关失败", error);
            return false;
        }
    }

    /**
     * 用与 Flutter 页「运行」完全相同的路由执行一个脚本文件。
     *
     * @param path 脚本绝对路径（定时任务只存路径）
     * @return true 表示已被宿主链路接管（含 PocRuntime 内部回退）；false 表示调用方应回退旧路径
     */
    public static boolean runScript(Context context, String path) {
        if (context == null || path == null || path.isEmpty()) {
            return false;
        }
        resolve();
        Method method = sRunScript;
        if (method == null) {
            return false;
        }
        Context target = context.getApplicationContext();
        if (target == null) {
            target = context;
        }
        try {
            method.invoke(null, target, path);
            return true;
        } catch (InvocationTargetException error) {
            // PocRuntime.runScript 对文件不存在/非法扩展名抛 IOException，由旧路径再走一次以保留原有提示。
            Log.w(TAG, "宿主链路执行失败，回退旧路径：" + path, error.getCause());
            return false;
        } catch (Throwable error) {
            Log.w(TAG, "宿主链路执行失败，回退旧路径：" + path, error);
            return false;
        }
    }

    /**
     * 停止所有宿主进程脚本（{@code :script} + Phase0 {@code :native}）。
     *
     * <p>主进程引擎里的旧任务不在这里停，调用方按原逻辑再调 {@code ScriptEngineService.stopAll()} 兜底。
     */
    public static void stopAllHostScripts() {
        resolve();
        Method method = sStopAllHostScripts;
        if (method == null) {
            return;
        }
        try {
            method.invoke(null);
        } catch (Throwable error) {
            Log.w(TAG, "停止宿主进程脚本失败", error);
        }
    }

    /**
     * task-165：「停止全部脚本」统一入口（通知栏停止 / 任务页按钮 / 悬浮条 ⏹）。
     *
     * <p>优先调 PocRuntime.stopAllScripts(Context)：宿主进程 + 主进程引擎 + Node UI 覆盖 Activity +
     * 前台通知 lease 一起收掉；旧构建没有该方法时退回 {@link #stopAllHostScripts()}。
     */
    public static void stopAllScripts(Context context) {
        if (context != null && !HostProcess.isMain(context)) {
            // The clients' task tables are process-local. Always coordinate through the main app.
            context.sendBroadcast(new Intent(context, StopAllScriptsReceiver.class)
                    .setAction(StopAllScriptsReceiver.ACTION));
            Log.i(TAG, "stop-all relayed from " + HostProcess.name());
            return;
        }
        resolve();
        Method method = sStopAllScripts;
        if (context != null) com.jdkshen.aijspro.engine.NodeUiRenderService.closeAll(context);
        if (method != null && context != null) {
            try {
                method.invoke(null, context.getApplicationContext() == null
                        ? context : context.getApplicationContext());
                return;
            } catch (Throwable error) {
                Log.w(TAG, "停止全部脚本失败，回退宿主进程停止", error);
            }
        }
        stopAllHostScripts();
        try {
            com.stardust.autojs.ScriptEngineService service = com.stardust.autojs.ScriptEngineService.getInstance();
            if (service != null) service.stopAll();
        } finally {
            com.stardust.autojs.overlay.OverlayRegistry.closeAll("host_stop_all_fallback");
        }
    }

    /** Same live tokens as the task manager; never enumerate scheduled tasks here. */
    public static org.json.JSONArray runningNotificationTasks() {
        resolve();
        if (sRunningNotificationTasks != null) try {
            return (org.json.JSONArray)sRunningNotificationTasks.invoke(null);
        } catch (Throwable error) { Log.w(TAG,"运行通知任务查询失败",error); }
        org.json.JSONArray rows=new org.json.JSONArray();
        try {
            com.stardust.autojs.ScriptEngineService service=com.stardust.autojs.ScriptEngineService.getInstance();
            if(service!=null)for(com.stardust.autojs.execution.ScriptExecution execution:service.getScriptExecutions()){
                String type=execution.getEngine()==null?"":execution.getEngine().getClass().getSimpleName();
                String label=type.contains("Node")?"Node":type.contains("Quick")?"QuickJS":"Rhino";
                rows.put(new org.json.JSONObject().put("token",String.valueOf(execution.getId())).put("name",execution.getSource().getName()).put("engine",label));
            }
        } catch(Throwable error){Log.w(TAG,"本地运行任务查询失败",error);}
        return rows;
    }

    /** A token targets one live execution, including one Node worker, never a process. */
    public static boolean stopNotificationTask(String token) {
        if(token==null||token.isEmpty()||token.startsWith("timed:")||token.startsWith("intent:"))return false;
        resolve();
        if(sStopNotificationTask!=null)try{return Boolean.TRUE.equals(sStopNotificationTask.invoke(null,token));}
        catch(Throwable error){Log.w(TAG,"停止单个通知任务失败",error);return false;}
        try{
            com.stardust.autojs.ScriptEngineService service=com.stardust.autojs.ScriptEngineService.getInstance();
            com.stardust.autojs.execution.ScriptExecution execution=service==null?null:service.getScriptExecution(Integer.parseInt(token));
            if(execution!=null&&execution.getEngine()!=null){if(!service.stopExecution(execution))execution.getEngine().forceStop();return true;}
        }catch(Throwable error){Log.w(TAG,"停止本地通知任务失败",error);}
        return false;
    }

    /** 一次性解析反射入口；解析失败（普通构建）只记一条日志，后续调用不再重试。 */
    private static void resolve() {
        if (sResolved) {
            return;
        }
        synchronized (HostScripts.class) {
            if (sResolved) {
                return;
            }
            ClassLoader loader = HostScripts.class.getClassLoader();
            try {
                Class<?> runtime = Class.forName(POC_RUNTIME_CLASS, true, loader);
                sRunScript = accessible(runtime.getDeclaredMethod(
                        "runScript", Context.class, String.class));
                sStopAllHostScripts = accessible(runtime.getDeclaredMethod("stopAllHostScripts"));
                try {
                    sRunningNotificationTasks=accessible(runtime.getDeclaredMethod("runningTasksForNotification"));
                    sStopNotificationTask=accessible(runtime.getDeclaredMethod("stopRunningTaskForNotification",String.class));
                } catch(NoSuchMethodException ignored) { }
                try {
                    sStopAllScripts = accessible(runtime.getDeclaredMethod(
                            "stopAllScripts", Context.class));
                } catch (NoSuchMethodException ignored) {
                    // 旧版 PocRuntime：退回只停宿主进程。
                }
            } catch (Throwable error) {
                Log.i(TAG, "宿主运行桥不可用（非 flutterPoc 构建？）：" + error);
            }
            try {
                Class<?> scriptHost = Class.forName(POC_SCRIPT_HOST_CLASS, true, loader);
                sIsEnabled = accessible(scriptHost.getDeclaredMethod("isEnabled"));
            } catch (Throwable error) {
                Log.i(TAG, "脚本宿主进程客户端不可用（非 flutterPoc 构建？）：" + error);
            }
            sResolved = true;
        }
    }

    /** PocRuntime / PocScriptHost 都是包内可见类，方法也非 public，反射调用前统一打开可访问性。 */
    private static Method accessible(Method method) {
        method.setAccessible(true);
        return method;
    }
}
