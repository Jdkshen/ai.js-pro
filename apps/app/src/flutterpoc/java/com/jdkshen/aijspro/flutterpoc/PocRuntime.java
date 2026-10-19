package com.jdkshen.aijspro.flutterpoc;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

import com.jdkshen.aijspro.Pref;
import com.jdkshen.aijspro.autojs.AutoJs;
import com.jdkshen.aijspro.external.ScriptIntents;
import com.jdkshen.aijspro.flutterpoc.nativeproc.NativeProjectDetector;
import com.jdkshen.aijspro.flutterpoc.nativeproc.PocNativeRunner;
import com.jdkshen.aijspro.flutterpoc.scriptproc.PocScriptHost;
import com.jdkshen.aijspro.timing.IntentTask;
import com.jdkshen.aijspro.timing.TimedTask;
import com.jdkshen.aijspro.timing.TimedTaskManager;
import com.jdkshen.aijspro.ui.timing.TimedTaskSettingActivity;
import com.stardust.autojs.core.console.ScriptRunLog;
import com.stardust.autojs.engine.ScriptEngine;
import com.stardust.autojs.execution.ScriptExecution;
import com.stardust.autojs.script.JavaScriptFileSource;
import com.stardust.autojs.script.JavaScriptSource;
import com.stardust.autojs.script.ScriptSource;
import com.stardust.pio.PFiles;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 运行桥：运行脚本/项目、运行中与定时任务列表、停止/取消任务、读取运行日志、
 * 打开定时任务设置。数据源全部用 ai-js-pro 自己的引擎与任务管理（不再反射原厂类）。
 *
 * <p>Phase1 P1 路由（见 {@link #runFile}）：{@code "auto";} 执行模式脚本默认留在主进程，门控
 * {@link #isAutoInScriptEnabled()} 打开（P1 验收后）即进 :script；使用本机截图/投屏能力的图色脚本
 * 留在主进程（隔离进程没有本地 MediaProjection 帧）；只有<b>项目运行</b>
 * （{@code runProject}，或入口是 main.js 且同目录有 project.json）里的 native 项目才走 Phase0 的
 * {@code :native}；其余脚本（含所有单文件 .js）默认走 {@code :script} 宿主进程
 * （{@link PocScriptHost}），:script 不可用时回退主进程引擎。
 * 任务列表/停止仍然统一走本类，token 用 {@code "script:<id>"} / {@code "native:<id>"} /
 * 纯数字（主进程引擎）区分。
 */
final class PocRuntime {

    private static final String TAG = "GlassFlutterPoc";

    /**
     * Phase1 P1 AUTO 门控：默认 {@code false}（auto 执行模式脚本继续留主进程）。
     *
     * <p>远程自动化代理（task-20：:script -> 主进程 IAutomationHost）接好后，把这里打开即可让
     * {@code "auto";} 脚本也走 :script；<b>P1 真机验收后打开</b>（Lead 统一决定，也可用
     * {@link #setAutoInScriptEnabled(boolean)} 切换做调试）。
     */
    private static volatile boolean sAutoInScriptEnabled = true; // task-159：Rhino :script 无障碍代理接入后默认启用

    static {
        // Phase1 第一批兜底：:script 报「暂存失败」时回退主进程旧路径执行（由 PocScriptHost 回调）。
        PocScriptHost.setLegacyRunner(PocRuntime::runLegacyInMainProcess);
    }

    /** AUTO 脚本是否也路由到 :script（默认 false 保持 Phase1 第一批行为；P1 验收后打开）。 */
    public static boolean isAutoInScriptEnabled() {
        return sAutoInScriptEnabled;
    }

    /** 打开/关闭 AUTO 门控（调试或 P1 验收后由 Lead 统一打开）。 */
    public static void setAutoInScriptEnabled(boolean enabled) {
        sAutoInScriptEnabled = enabled;
    }

    private PocRuntime() {
    }

    static Object execute(Activity activity, String method, JSONObject args) throws Exception {
        switch (method) {
            case "run":
                runScript(activity, args.getString("path"));
                return Boolean.TRUE;
            case "runProject":
                runProject(activity, args.getString("path"));
                return Boolean.TRUE;
            case "floatRun":
                return PocFloatingBar.show(activity, args.getString("path"));
            case "tasks":
                return tasks();
            case "stopTasks":
                return stopTasks(args.optJSONArray("tokens"));
            case "cancelTask":
                return stopTasks(new JSONArray().put(args.getString("token")));
            case "logs":
                return logs(activity, args);
            case "timedTask":
                return openTimedTaskSetting(activity, args.getString("path"));
            default:
                throw new UnsupportedOperationException("POC 尚未实现运行桥方法：" + method);
        }
    }

    // ---- 运行 ------------------------------------------------------------

    /**
     * 主进程统一运行入口（单文件）：桥 {@code run}、悬浮控制条 ▶ 以及其它主进程页面都走这里。
     *
     * <p>先做脚本文件校验，再按 {@link #runFile(Context, File, File, boolean)} 的路由执行；路由只在
     * 这一处决定，避免「桥上一套、悬浮条一套」打架。
     *
     * <p><b>Phase1 真机回归修复</b>：单文件脚本默认<b>不做</b> NativeProjectDetector 递归检测 ——
     * 以前把脚本父目录当项目扫，{@code /sdcard/脚本} 里别处的 .so/.dex 会让普通 .js 被误判成
     * native 项目，进 :native 后 staging 要复制整棵目录（超 256MB 直接失败）。只有「入口文件是
     * main.js 且同目录存在 project.json」的手工工程才按项目路由（允许 native 检测）。
     *
     * @param context 任意 Context（Activity/Application 均可；内部只用于绑定宿主进程与提示）
     * @param path    脚本路径（可为相对路径，内部转 canonical）
     */
    static void runScript(Context context, String path) throws IOException {
        File file = new File(path).getCanonicalFile();
        if (!file.isFile()) {
            throw new IOException("脚本文件不存在：" + file.getName());
        }
        if (!file.getName().toLowerCase(java.util.Locale.ROOT).matches(".+\\.(js|mjs)")) {
            throw new IOException("请选择 JavaScript 脚本");
        }
        File parent = file.getParentFile();
        // 单文件脚本只有「main.js + 同目录 project.json」才按项目路由；其它一律不做 native 递归检测。
        runFile(context, file, parent, isProjectEntry(file, parent));
    }

    /**
     * 单文件是否其实是某个项目的入口：文件名是 main.js 且同目录有 project.json。
     *
     * <p>用来给 {@link #runScript} 放行 native 判定；普通 .js 平铺在脚本目录时绝不触发递归检测。
     */
    private static boolean isProjectEntry(File file, File parent) {
        if (file == null || parent == null) {
            return false;
        }
        if (!"main.js".equalsIgnoreCase(file.getName())) {
            return false;
        }
        return new File(parent, "project.json").isFile();
    }

    /**
     * 统一执行路由（Phase1 第一批，按优先级）：
     * <ol>
     *   <li>{@code "ui";} 执行模式脚本 → 主进程（需要 Activity 承载 UI）；</li>
     *   <li><b>项目运行</b>（{@code projectMode=true}）且是 native 项目（含 .so/.dex）
     *       → Phase0 的 {@code :native} 隔离进程 {@link PocNativeRunner}；</li>
     *   <li>其余调用本机截图/投屏能力的脚本（{@link ScreenCaptureScriptRouting}）→ 主进程，
     *       保留真实投屏帧与 NativeFrame 零拷贝路径；</li>
     *   <li>{@code "auto";} 执行模式且 {@link #isAutoInScriptEnabled()} 为 false → 主进程旧路径
     *       （P1 验收后打开门控即进 :script；见 {@link #isAutoModeScript(File)}）；</li>
     *   <li>其余脚本（含所有单文件 .js，{@code projectMode=false}）→ {@code :script} 宿主进程
     *       {@link PocScriptHost}（默认）。</li>
     * </ol>
     *
     * <p>为什么单文件不检测 native（Phase1 真机回归）：{@code /sdcard/脚本} 下别的项目带 .so/.dex 时，
     * 对父目录递归检测会把普通脚本误判成 native 项目，进 :native 后 staging 整树复制超 256MB 直接
     * 失败；单文件执行没有「项目根」概念，交给 :script（服务端 staging 对单文件不复制）即可。
     *
     * <p>{@link PocScriptHost#run} 同步绑定失败（:script 服务未注册/系统不允许）时会抛
     * {@link IOException}，这里捕获后回退主进程引擎并提示一次，保证 manifest 未合入时点运行也有反应。
     *
     * <p>回退到主进程引擎时走 {@link #executeFile(File, File)}：不打开 RunIntentActivity，
     * 普通脚本后台线程跑、带 {@code "ui";} 的脚本由 ScriptExecuteActivity 承载。
     *
     * @param projectMode true 表示「项目运行」（runProject / main.js+project.json），只有这种才允许
     *                    NativeProjectDetector 递归判定
     */
    private static void runFile(Context context, File file, File workDir, boolean projectMode) throws IOException {
        // "ui"; 文档优先：ui.layout/ui.inflate 需要 Activity 承载，而 :native 隔离进程没有 UI 模式
        // （进 :native 会报「需要在ui模式下运行才能使用该函数」）。带 .so 的 UI 项目（如 ImGui demo）
        // 因此回主进程 ScriptExecuteActivity；native 隔离只留给真正的 headless native 脚本。
        if (isUiModeScript(file)) {
            executeFile(file, workDir);
            return;
        }
        // 本机截图/投屏能力：:script 经 RemoteAutomationBridge 只能拿主进程 PNG 回传，
        // requestScreenCapture/captureScreenNative 也没有本地 MediaProjection；图色性能脚本必须
        // 保留真实投屏帧和 NativeFrame 零拷贝路径，所以进入 :script 前回主进程。
        // UI 优先的判定仍在上面；探测失败按普通脚本继续路由，不阻断运行。
        List<String> captureApis = null;
        try {
            captureApis = ScreenCaptureScriptRouting.localCaptureApis(file);
        } catch (IOException error) {
            Log.w(TAG, "读取脚本截图能力失败，按普通脚本路由", error);
        }
        if (captureApis != null && !captureApis.isEmpty()) {
            // task-147：截图/投屏脚本必须在主进程（真实 MediaProjection + 系统授权弹窗），
            // 该判定必须放在 native 项目检测之前：象棋画框等项目带 .so/.dex 会被判为 native，
            // 先进 :native 就只剩「隔离进程暂不支持投屏截图」。
            Log.i(TAG, "脚本使用本地截图能力 " + captureApis + "，主进程执行：" + file.getName());
            executeFile(file, workDir);
            return;
        }
        // native 项目继续保留原有隔离边界，不因入口中的截图调用加载进主进程。
        if (projectMode && NativeProjectDetector.isNativeProject(workDir)) {
            PocNativeRunner.run(context, file, workDir);
            return;
        }
        // Phase1 P1：auto 模式脚本依赖 Accessibility/Automator。远程自动化代理（task-20）已接好，
        // 但门控默认关闭（isAutoInScriptEnabled()=false），仍保留主进程旧路径；P1 验收后打开开关
        // 即可让 auto 脚本和普通脚本一样走 :script，无需再改这里。
        if (isAutoModeScript(file) && !isAutoInScriptEnabled()) {
            executeFile(file, workDir);
            return;
        }
        // "ui"; 脚本需要 Activity 承载（ui.layout/ui.inflate 等），而 Phase1 的 :script 宿主目前是
        // 无 Activity 的主线程 Looper 宿主，进入会报「需要在ui模式下运行才能使用该函数」。第一批先
        // 回退主进程 ScriptExecuteActivity（与 Phase0 前行为一致）；后续把 ScriptExecuteActivity 声明进
        // :script 进程（Pro 模型）后再放开到 :script。
        if (isUiModeScript(file)) {
            executeFile(file, workDir);
            return;
        }
        if (PocScriptHost.isEnabled()) {
            try {
                PocScriptHost.run(context, file, workDir);
                return;
            } catch (IOException hostError) {
                // :script 服务未就绪（manifest 未注册 / 系统不允许绑定）：回退旧路径，别让点击没反应。
                Log.w(TAG, "脚本宿主进程不可用，回退旧执行路径", hostError);
                toast(context, "脚本宿主进程不可用，已回退本地执行");
            }
        }
        executeFile(file, workDir);
    }

    /** 非致命提示：Toast 失败不能影响脚本执行主流程。 */
    private static void toast(Context context, String text) {
        if (context == null) {
            return;
        }
        try {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
        } catch (Throwable error) {
            Log.w(TAG, "Toast 显示失败", error);
        }
    }

    /**
     * 是否 {@code "auto";}（含 {@code "ui auto";}）执行模式脚本。
     *
     * <p>Phase1 P1：这类脚本需要 Accessibility/Automator；远程自动化代理（task-20）已接好后，
     * 是否仍留主进程由 {@link #isAutoInScriptEnabled()} 门控决定（默认关闭，P1 验收后打开）。
     *
     * <p>判断方式与引擎一致：构造 {@link JavaScriptFileSource} 读首行执行模式标记
     * （{@link JavaScriptSource#getExecutionMode()}）；读失败按普通脚本处理，别因为探测失败就退回主进程。
     */
    static boolean isAutoModeScript(File file) {
        if (file == null) {
            return false;
        }
        try {
            JavaScriptFileSource source = new JavaScriptFileSource(file);
            return (source.getExecutionMode() & JavaScriptSource.EXECUTION_MODE_AUTO) != 0;
        } catch (Throwable error) {
            Log.w(TAG, "读取脚本执行模式失败，按普通脚本路由", error);
            return false;
        }
    }

    /**
     * 是否 {@code "ui";} 执行模式脚本。
     *
     * <p>Phase1 第一批：:script 宿主没有 Activity，UI 模式脚本先回退主进程 ScriptExecuteActivity；
     * 判断方式同 {@link #isAutoModeScript(File)}（读首行执行模式标记，读失败按普通脚本）。
     */
    static boolean isUiModeScript(File file) {
        if (file == null) {
            return false;
        }
        try {
            JavaScriptFileSource source = new JavaScriptFileSource(file);
            return (source.getExecutionMode() & JavaScriptSource.EXECUTION_MODE_UI) != 0;
        } catch (Throwable error) {
            Log.w(TAG, "读取脚本执行模式失败，按普通脚本路由", error);
            return false;
        }
    }

    /** :script 报「暂存失败」时回退主进程执行（PocScriptHost 在主线程回调，不经过 staging）。 */
    private static void runLegacyInMainProcess(File scriptFile, File workDir) throws IOException {
        Log.w(TAG, "脚本进程暂存失败，回退主进程执行：" + scriptFile.getPath());
        executeFile(scriptFile, workDir);
    }

    /** 用脚本所在目录作为工作目录（相对 import/read 才能找到同目录资源）。 */
    private static void executeFile(File file, File workingDirectory) throws IOException {
        com.jdkshen.aijspro.autojs.AutoJs autojs = com.jdkshen.aijspro.autojs.AutoJs.getInstance();
        if (autojs == null || autojs.getScriptEngineService() == null) {
            throw new IOException("脚本引擎尚未初始化，请回到脚本页再试");
        }
        JavaScriptFileSource source = new JavaScriptFileSource(file);
        String workDir = workingDirectory == null ? file.getParent() : workingDirectory.getAbsolutePath();
        com.stardust.autojs.execution.ExecutionConfig config =
                com.stardust.autojs.execution.ExecutionConfig.getDefault();
        config.setWorkingDirectory(workDir == null ? "" : workDir);
        autojs.getScriptEngineService().execute(source, config);
    }

    private static void runProject(Activity activity, String path) throws IOException {
        File project = new File(path).getCanonicalFile();
        if (project.isFile()) {
            runScript(activity, project.getPath());
            return;
        }
        if (!project.isDirectory()) {
            throw new IOException("项目目录不存在");
        }
        File config = new File(project, "project.json");
        if (!config.isFile()) {
            throw new IOException("项目缺少 project.json");
        }
        String main = "main.js";
        try {
            JSONObject json = new JSONObject(readSmall(config));
            main = json.optString("main", "main.js");
        } catch (Exception error) {
            throw new IOException("project.json 无法解析：" + error.getMessage());
        }
        File mainFile = new File(project, main);
        if (!mainFile.isFile()) {
            throw new IOException("项目入口文件不存在：" + main);
        }
        // 项目运行（有 project.json）：允许 native 递归检测。
        // 路由：auto → 主进程；native 项目 → :native；其余 → :script（失败回退主进程）。
        runFile(activity, mainFile, project, true);
    }

    private static Object openTimedTaskSetting(Activity activity, String path) throws IOException {
        File file = new File(path).getCanonicalFile();
        if (!file.exists()) {
            throw new IOException("脚本文件不存在");
        }
        activity.startActivity(new Intent().setClassName(activity, TimedTaskSettingActivity.class.getName())
                .putExtra(ScriptIntents.EXTRA_KEY_PATH, file.getPath()));
        return Boolean.TRUE;
    }

    // ---- 任务列表 / 停止 ------------------------------------------------

    private static Object tasks() throws Exception {
        JSONArray running = new JSONArray();
        try {
            Collection<ScriptExecution> executions = AutoJs.getInstance()
                    .getScriptEngineService().getScriptExecutions();
            if (executions != null) {
                for (ScriptExecution execution : executions) {
                    ScriptSource source = execution.getSource();
                    String path = source instanceof JavaScriptFileSource
                            ? ((JavaScriptFileSource) source).getFile().getPath() : "";
                    running.put(new JSONObject()
                            .put("token", String.valueOf(execution.getId()))
                            .put("kind", "script")
                            .put("name", source.getName())
                            .put("path", path)
                            .put("engine", engineLabel(source))
                            .put("detail", "运行中")
                            .put("nextTime", 0));
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "读取运行中任务失败", error);
        }
        try {
            // :script 宿主进程里的任务由主进程侧客户端登记，合并进同一个运行列表（token 前缀 script:）。
            JSONArray scriptTasks = PocScriptHost.runningTasks();
            for (int i = 0; i < scriptTasks.length(); i++) {
                running.put(scriptTasks.get(i));
            }
        } catch (Throwable error) {
            Log.w(TAG, "读取脚本进程任务失败", error);
        }
        try {
            // Phase0 的 :native 兜底路径继续合并（token 前缀 native:），旧任务/回退时都能看到、能停。
            JSONArray nativeTasks = PocNativeRunner.runningTasks();
            for (int i = 0; i < nativeTasks.length(); i++) {
                running.put(nativeTasks.get(i));
            }
        } catch (Throwable error) {
            Log.w(TAG, "读取隔离进程任务失败", error);
        }
        JSONArray scheduled = new JSONArray();
        try {
            TimedTaskManager manager = TimedTaskManager.getInstance();
            List<TimedTask> timedTasks = manager.getAllTasksAsList();
            if (timedTasks != null) {
                for (TimedTask task : timedTasks) {
                    scheduled.put(new JSONObject()
                            .put("token", timedToken(task))
                            .put("kind", "timed")
                            .put("name", PFiles.getSimplifiedPath(task.getScriptPath()))
                            .put("path", task.getScriptPath())
                            .put("engine", engineOf(task.getScriptPath()))
                            .put("detail", "定时任务")
                            .put("nextTime", task.getNextTime()));
                }
            }
            List<IntentTask> intentTasks = manager.getAllIntentTasksAsList();
            if (intentTasks != null) {
                for (IntentTask task : intentTasks) {
                    scheduled.put(new JSONObject()
                            .put("token", intentToken(task))
                            .put("kind", "intent")
                            .put("name", PFiles.getSimplifiedPath(task.getScriptPath()))
                            .put("path", task.getScriptPath())
                            .put("engine", engineOf(task.getScriptPath()))
                            .put("detail", "事件任务 · " + task.getAction())
                            .put("nextTime", 0));
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "读取定时任务失败", error);
        }
        return new JSONObject().put("running", running).put("scheduled", scheduled);
    }

    private static Object stopTasks(JSONArray tokens) throws Exception {
        if (tokens == null || tokens.length() == 0) {
            return Boolean.TRUE;
        }
        for (int i = 0; i < tokens.length(); i++) {
            String token = tokens.optString(i, "");
            if (token.startsWith(PocScriptHost.TOKEN_PREFIX)) {
                // "script:<id>"：Phase1 的 :script 宿主进程任务。
                PocScriptHost.stop(token.substring(PocScriptHost.TOKEN_PREFIX.length()));
                continue;
            }
            if (token.startsWith(PocNativeRunner.TOKEN_PREFIX)) {
                // "native:<id>"：Phase0 :native 兜底任务的旧 token，继续兼容。
                PocNativeRunner.stop(token.substring(PocNativeRunner.TOKEN_PREFIX.length()));
                continue;
            }
            stopToken(token);
        }
        return Boolean.TRUE;
    }

    private static void stopToken(String token) throws Exception {
        if (token == null || token.isEmpty()) {
            return;
        }
        if (token.startsWith("timed:")) {
            TimedTaskManager manager = TimedTaskManager.getInstance();
            List<TimedTask> tasks = manager.getAllTasksAsList();
            if (tasks != null) {
                for (TimedTask task : tasks) {
                    if (timedToken(task).equals(token)) {
                        manager.removeTask(task);
                        return;
                    }
                }
            }
            return;
        }
        if (token.startsWith("intent:")) {
            TimedTaskManager manager = TimedTaskManager.getInstance();
            List<IntentTask> tasks = manager.getAllIntentTasksAsList();
            if (tasks != null) {
                for (IntentTask task : tasks) {
                    if (intentToken(task).equals(token)) {
                        manager.removeTask(task);
                        return;
                    }
                }
            }
            return;
        }
        try {
            int id = Integer.parseInt(token);
            ScriptExecution execution = AutoJs.getInstance()
                    .getScriptEngineService().getScriptExecution(id);
            if (execution != null) {
                ScriptEngine engine = execution.getEngine();
                if (engine != null) {
                    engine.forceStop();
                }
            }
        } catch (NumberFormatException ignored) {
            // 未知 token：忽略，避免一个坏条目让整次停止失败。
        }
    }

    // ---- 悬浮控制条：宿主进程任务的查询/停止 -----------------------------
    // 统一放在这里判断，悬浮条不需要直接认识 :script/:native 两个客户端，避免两套路由逻辑打架。

    /** 某个脚本路径是否正在宿主进程（:script 或 :native）里运行。 */
    static boolean hasRunningScript(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        try {
            if (PocScriptHost.hasRunning(path)) {
                return true;
            }
        } catch (Throwable error) {
            Log.w(TAG, "查询脚本进程任务失败", error);
        }
        return !nativeIdsOf(path).isEmpty();
    }

    /** 停止某个脚本路径在宿主进程里的任务，返回是否真的提交了停止。 */
    static boolean stopRunningScript(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        boolean stopped = false;
        try {
            stopped = PocScriptHost.stopByPath(path);
        } catch (Throwable error) {
            Log.w(TAG, "停止脚本进程任务失败", error);
        }
        for (String id : nativeIdsOf(path)) {
            PocNativeRunner.stop(id);
            stopped = true;
        }
        return stopped;
    }

    /** 停止所有宿主进程脚本（悬浮条 ⏹ 用；主进程引擎的 stopAll 由悬浮条自己兜底）。 */
    static void stopAllHostScripts() {
        try {
            PocScriptHost.stopAll();
        } catch (Throwable error) {
            Log.w(TAG, "停止全部脚本进程任务失败", error);
        }
        for (String id : nativeIdsOf(null)) {
            PocNativeRunner.stop(id);
        }
    }

    /** 从 Phase0 :native 客户端登记的任务里取协议 token；path 为 null 时返回全部。 */
    private static List<String> nativeIdsOf(String path) {
        List<String> ids = new ArrayList<>();
        try {
            JSONArray tasks = PocNativeRunner.runningTasks();
            for (int i = 0; i < tasks.length(); i++) {
                JSONObject task = tasks.optJSONObject(i);
                if (task == null) {
                    continue;
                }
                if (path != null && !samePath(path, task.optString("path", ""))) {
                    continue;
                }
                String displayToken = task.optString("token", "");
                String prefix = PocNativeRunner.TOKEN_PREFIX;
                if (displayToken.startsWith(prefix)) {
                    ids.add(displayToken.substring(prefix.length()));
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "读取隔离进程任务失败", error);
        }
        return ids;
    }

    private static boolean samePath(String first, String second) {
        if (first == null || first.isEmpty() || second == null || second.isEmpty()) {
            return false;
        }
        try {
            return new File(first).getCanonicalPath().equals(new File(second).getCanonicalPath());
        } catch (IOException ignored) {
            return new File(first).getAbsolutePath().equals(new File(second).getAbsolutePath());
        }
    }

    private static String timedToken(TimedTask task) {
        return "timed:" + task.getMillis() + "|" + task.getTimeFlag() + "|" + task.getScriptPath();
    }

    private static String intentToken(IntentTask task) {
        return "intent:" + task.getAction() + "|" + task.getScriptPath();
    }

    /**
     * 执行中任务显示用的引擎名。注意 Rhino 的 getEngineName() 是基类常量
     * （不含 "rhino" 字样），不能直接拿来做界面标签，否则头像永远显示 N。
     */
    private static String engineLabel(ScriptSource source) {
        String name = source == null ? "" : String.valueOf(source.getEngineName());
        if (name.contains("QuickJS")) {
            return "QuickJS";
        }
        if (name.contains("Node24")) {
            return "Node";
        }
        return "Rhino";
    }

    /** 定时/意图任务没有执行对象，按脚本首行引擎标记判断；读不出时按 Rhino 显示。 */
    private static String engineOf(String path) {
        if (path == null || !path.toLowerCase(java.util.Locale.ROOT).endsWith(".js")) {
            return "";
        }
        try (java.io.InputStream in = new java.io.FileInputStream(path)) {
            byte[] buffer = new byte[4096];
            int read = in.read(buffer);
            String head = read <= 0 ? "" : new String(buffer, 0, read, StandardCharsets.UTF_8)
                    .toLowerCase(java.util.Locale.ROOT);
            if (head.contains("quickjs")) {
                return "QuickJS";
            }
            if (head.contains("nodejs") || head.contains("// @engine node")) {
                return "Node";
            }
        } catch (Throwable ignored) {
            // 读不出（文件不存在/权限）就按 Rhino 显示，不影响任务列表
        }
        return "Rhino";
    }

    // ---- 运行日志 --------------------------------------------------------

    /**
     * Flutter 日志页的「最近一次脚本运行日志」。
     *
     * <p>文件格式见 {@link ScriptRunLog#line(int, CharSequence)}：{@code HH:mm:ss.SSS [I] 正文}
     * （不是 Pro log4j 的 {@code yyyy-MM-dd HH:mm:ss.SSS/INFO: 正文}）；日志页两种格式都解析。
     *
     * <p>注意排除 {@code app-crash*.log}：崩溃处理器也往同目录写 .log，不排除会抢走「最近一次脚本」；
     * 没有脚本日志时才退回最新 .log，保证日志页仍有内容可看。
     */
    private static Object logs(Activity activity, JSONObject args) throws Exception {
        File log = pickLatestScriptLog(ScriptRunLog.listRecent(activity, 20));
        if (log == null) {
            return new JSONObject().put("content", "").put("truncated", false)
                    .put("length", 0L).put("start", 0L).put("end", 0L)
                    .put("name", "").put("path", "").put("modified", 0L);
        }
        JSONObject result = readLogWindow(log, args);
        // 附加信息（向后兼容的增量字段）：日志页可显示文件名/路径/时间，便于排查。
        result.put("name", log.getName());
        result.put("path", log.getAbsolutePath());
        result.put("modified", log.lastModified());
        return result;
    }

    /** 在最近日志列表（新→旧）里挑最新一份「脚本运行日志」；没有脚本日志时退回最新 .log。 */
    private static File pickLatestScriptLog(java.util.List<File> recent) {
        if (recent == null || recent.isEmpty()) {
            return null;
        }
        for (File candidate : recent) {
            if (candidate != null && candidate.isFile()
                    && !candidate.getName().startsWith(ScriptRunLog.CRASH_FILE_PREFIX)) {
                return candidate;
            }
        }
        File fallback = recent.get(0);
        return fallback != null && fallback.isFile() ? fallback : null;
    }

    /**
     * 读日志文件的尾部窗口（首次/加载更早）或增量（带 {@code from}）。
     *
     * <p>返回字段与 Flutter LogPage 契约一致：{@code content/truncated/length/start/end}；
     * 外层 {@link #logs} 再补 name/path/modified。
     */
    private static JSONObject readLogWindow(File log, JSONObject args) throws Exception {
        long window = args.has("window") ? args.getLong("window") : 262144L;
        long end = args.has("end") ? args.getLong("end") : 0L;
        window = Math.max(4096L, Math.min(8L * 1024 * 1024, window));
        end = Math.max(0L, end);
        try (RandomAccessFile input = new RandomAccessFile(log, "r")) {
            long length = input.length();
            long stop = Math.max(0, length - end);
            long start;
            boolean incremental = args.has("from");
            if (incremental) {
                start = args.getLong("from");
                if (start < 0 || start > stop) {
                    start = Math.max(0, stop - window);
                }
            } else {
                start = Math.max(0, stop - window);
            }
            if (start >= stop) {
                return new JSONObject().put("content", "").put("truncated", false)
                        .put("length", length).put("start", stop).put("end", stop).put("appended", true);
            }
            input.seek(start);
            byte[] bytes = new byte[(int) (stop - start)];
            input.readFully(bytes);
            String content = new String(bytes, StandardCharsets.UTF_8);
            if (incremental) {
                int newline = content.lastIndexOf('\n');
                if (newline >= 0 && stop < length) {
                    content = content.substring(0, newline + 1);
                    stop = start + content.getBytes(StandardCharsets.UTF_8).length;
                }
            } else {
                if (start > 0) {
                    int newline = content.indexOf('\n');
                    if (newline >= 0) {
                        content = content.substring(newline + 1);
                    }
                }
                if (stop < length) {
                    int newline = content.lastIndexOf('\n');
                    if (newline >= 0) {
                        content = content.substring(0, newline + 1);
                    }
                }
            }
            return new JSONObject().put("content", content).put("truncated", start > 0)
                    .put("length", length).put("start", start).put("end", stop)
                    .put("incremental", incremental).put("window", window);
        }
    }

    private static String readSmall(File file) throws IOException {
        try (java.io.InputStream in = new java.io.FileInputStream(file);
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1) {
                out.write(buffer, 0, count);
                if (out.size() > 2 * 1024 * 1024) {
                    throw new IOException("文件过大");
                }
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
