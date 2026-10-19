package com.jdkshen.aijspro.flutterpoc;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.util.Log;
import android.view.WindowManager;

import com.jdkshen.aijspro.Pref;
import com.jdkshen.aijspro.autojs.AutoJs;
import com.jdkshen.aijspro.flutterpoc.nativeproc.PocNativeRunner;
import com.jdkshen.aijspro.flutterpoc.scriptproc.HostConsoleSink;
import com.jdkshen.aijspro.flutterpoc.scriptproc.PocScriptHost;
import com.jdkshen.aijspro.model.sample.SampleFiles;
import com.jdkshen.aijspro.theme.AppThemeRepository;
import com.jdkshen.aijspro.tool.AccessibilityServiceTool;
import com.stardust.autojs.execution.ScriptExecution;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * POC 桥（-PflutterPoc=true 才编译进 debug 变体）。
 *
 * <p>玻璃界面首屏需要的三条主桥：{@code bootstrap} / {@code list} / {@code theme}；
 * 入口切到 Flutter 之后，抽屉里会跳原生页的入口（日志/设置/终端/工作区/退出/权限设置）也走
 * 原生 Activity 兜底。其余方法按契约返回 {@code {ok:false,error}}，界面降级显示、不崩溃。
 *
 * <p>契约与重建线 {@code output/autojs-pro-flutter-rebuild/android-bridge} 的 FlutterBridge 对齐：
 * JSON over StringCodec，统一回 {@code {ok:true,data:...}} / {@code {ok:false,error:...}}。
 */
final class PocBridge {

    private static final String TAG = "GlassFlutterPoc";
    static final String CHANNEL = "autojs.local.bridge";
    private static final String PREFS_GLASS = "flutter-glass";
    private static final int MAX_ENTRIES = 5000;
    /** 运行设置键：与重建线 RuntimePreferences 的契约一致（settings_page.dart 会校验）。 */
    private static final String[] RUNTIME_NUMERIC = {
            "consoleMaxLogCount", "logMaxFileSize", "logMaxBackups", "consoleInitialReadFileSize"
    };
    private static final String[] RUNTIME_FLAGS = {
            "key_use_volume_control_running", "key_use_volume_control_record", "key_localize_message",
            "key_guard_mode", "key_soft_wrap", "key_show_invisibles", "key_show_gutter",
            "key_show_print_margin", "key_enable_live_completion", "key_enable_js_lint",
            "key_enable_lsp", "key_enable_magnifier", "key_foreground_service"
    };
    private static final String[] RUNTIME_STRINGS = {"key_npm_registry", "key_script_dir_path"};

    private static final String MAIN_ACTIVITY = "com.jdkshen.aijspro.ui.main.MainActivity";
    /** AI.js Pro 新版多文件脚本工作区（Ace 编辑器，含文件树/标签/日志面板）。 */
    private static final String EDITOR_ACTIVITY = "com.jdkshen.aijspro.ui.editor.ProCodeEditorActivity";
    private static final String EDITOR_PREFS = "pro_code_editor";
    private static final String LOG_ACTIVITY = "com.jdkshen.aijspro.ui.log.LogActivity";
    private static final String SETTINGS_ACTIVITY = "com.jdkshen.aijspro.ui.settings.MiuixSettingsActivity";
    private static final String TERMINAL_ACTIVITY = "com.jdkshen.aijspro.ui.terminal.EmbeddedTerminalActivity";

    private PocBridge() {
    }

    interface Replier {
        void reply(String json);
    }

    /** 处理一条桥请求：同步方法立即回复，系统选择器/下载类方法稍后回复。 */
    static void handle(Activity activity, String message, Replier replier) {
        String method = "?";
        try {
            JSONObject request = message == null || message.trim().isEmpty()
                    ? new JSONObject() : new JSONObject(message);
            method = request.optString("method", "");
            JSONObject args = request.optJSONObject("args");
            if (args == null) {
                args = new JSONObject();
            }
            final String resolvedMethod = method;
            if (PocServices.isAsync(method)) {
                PocServices.startAsync(activity, method, args, (data, error) -> {
                    if (error != null) {
                        Log.w(TAG, "bridge failure: " + resolvedMethod, error);
                    }
                    replier.reply(replyJson(data, error));
                });
                return;
            }
            if (PocIntegration.isAsync(method)) {
                PocIntegration.startAsync(activity, method, args, (data, error) -> {
                    if (error != null) {
                        Log.w(TAG, "bridge failure: " + resolvedMethod, error);
                    }
                    replier.reply(replyJson(data, error));
                });
                return;
            }
            Object data = execute(activity, method, args);
            replier.reply(replyJson(data, null));
        } catch (Throwable error) {
            Log.w(TAG, "bridge failure: " + method, error);
            replier.reply(replyJson(null, error));
        }
    }

    private static String replyJson(Object data, Throwable error) {
        JSONObject result = new JSONObject();
        try {
            if (error != null) {
                result.put("ok", false);
                result.put("error", error.getMessage() == null ? error.toString() : error.getMessage());
            } else {
                result.put("ok", true);
                result.put("data", data == null ? JSONObject.NULL : data);
            }
        } catch (JSONException ignored) {
            return "{\"ok\":false,\"error\":\"bridge failure\"}";
        }
        return result.toString();
    }

    private static Object execute(Activity activity, String method, JSONObject args) throws Exception {
        switch (method) {
            case "bootstrap":
                return bootstrap(activity);
            case "scriptsRoot":
                return new JSONObject().put("path", Pref.getScriptDirPath());
            case "list":
                return list(activity, args);
            case "theme":
                return setTheme(activity, args);
            // 内置目录：本地资源 / 已装插件列表（同步返回，替代外部 127.0.0.1:19888 目录服务）。
            case "localCatalog":
                return PocCatalog.localCatalog(activity, args);

            // ---- 运行 / 任务 / 日志（PocRuntime）--------------------------------
            case "run":
            case "runProject":
            case "floatRun":
            case "tasks":
            case "stopTasks":
            case "cancelTask":
            case "timedTask":
                return PocRuntime.execute(activity, method, args);
            // 任务页「查看该任务日志」：按 name/token 只读该任务最近一次运行日志文件；
            // 不带过滤参数时行为与原来完全一致（走 PocRuntime 的最近一次脚本日志）。
            case "logs":
                if (args.has("name") || args.has("token")) {
                    return logsForTask(activity, args);
                }
                return PocRuntime.execute(activity, method, args);

            // ---- 统一实时日志流（HostConsoleSink：主进程脚本 + :script + :native）------   // lgtm[comment]
            case "liveLogs":
                return liveLogs(args);
            case "clearLiveLogs":
                return clearLiveLogs();

            // ---- 文件增删改 / 回收站 / 分享（PocWorkspace）----------------------
            case "createFile":
            case "createFolder":
            case "createProject":
            case "rename":
            case "delete":
            case "deletePaths":
            case "fileClipboard":
            case "pastePaths":
            case "trashList":
            case "restoreTrash":
            case "workspaceContext":
            case "share":
            case "shareFile":
            case "openWith":
            case "aceOpenFile":
                return new PocWorkspace(activity).execute(method, args);

            // ---- 原生页兜底（Flutter 侧入口切过来后，这些是“去原生页”的唯一出口）----
            case "openLog":
                launch(activity, LOG_ACTIVITY, null);
                return Boolean.TRUE;
            case "engineSettings":
                launch(activity, SETTINGS_ACTIVITY, null);
                return Boolean.TRUE;
            case "openTerminal": {
                String path = args.optString("path", "").trim();
                File dir = path.isEmpty() ? new File(Pref.getScriptDirPath()) : new File(path);
                Intent intent = new Intent().setClassName(activity, TERMINAL_ACTIVITY)
                        .putExtra("working_directory", dir.getAbsolutePath());
                activity.startActivity(intent);
                return Boolean.TRUE;
            }
            case "openEditor": {
                // “打开工作区”按钮：打开 AI.js Pro 新版多文件工作区（ProCodeEditorActivity），
                // 优先恢复上一次会话的活跃文件/标签；没有可打开文件时才退回原生工作区列表。
                File target = lastWorkspaceFile(activity);
                if (target != null) {
                    activity.startActivity(new Intent().setClassName(activity, EDITOR_ACTIVITY)
                            .putExtra("path", target.getAbsolutePath()));
                } else {
                    launch(activity, MAIN_ACTIVITY,
                            Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                }
                return Boolean.TRUE;
            }
            case "exitApp":
                activity.finishAffinity();
                return Boolean.TRUE;
            case "keepScreenOn": {
                boolean enabled = args.optBoolean("enabled", false);
                if (enabled) {
                    activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                } else {
                    activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
                return Boolean.TRUE;
            }
            case "openUrl": {
                String url = args.optString("url", "");
                if (!url.startsWith("https://") && !url.startsWith("http://")) {
                    throw new IOException("不支持的链接");
                }
                activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                return Boolean.TRUE;
            }
            case "deviceState":
                return deviceState(activity);

            // ---- 示例库（task-129：下载已彻底禁用，恒返回空清单，示例页不出下载行）----
            case "sampleLibrary":
                return PocSamples.library(activity);

            // ---- MCP / 插件 / 示例 / 快捷方式（PocIntegration）------------------
            case "mcpState":
            case "mcpSet":
            case "pluginStates":
            case "pluginSetEnabled":
            case "pluginIcon":
            case "shortcut":
            case "resetSample":
            case "copyExampleTree":
            case "sampleAction":
            case "openBuildPage":
            // ---- 玻璃打包页（PocPackaging）-----------------------------------
            case "projectLoad":
            case "projectSave":
            case "projectBuild":
            case "projectBuildStatus":
            case "projectSigningList":
            case "projectSigningAliases":
            case "projectSigningCreate":
            case "prepareSingleFile":
            case "projectBuildInstall":
            case "projectBuildShare":
            case "nativePackagingRequest":
            case "pendingAction":
                return PocIntegration.execute(activity, method, args);

            // ---- 系统选择器 / 网络（异步回复）----------------------------------
            case "importWorkspace":
            case "importPlugin":
            case "importResource":
            case "importCatalogResource":
            case "importProjectIcon":
            case "importProjectSigning":
            case "installPlugin":
                throw new IllegalStateException("异步桥方法不应走到同步分支");
            case "accessibilitySettings":
                AccessibilityServiceTool.goToAccessibilitySetting();
                return Boolean.TRUE;
            case "overlaySettings": {
                Intent intent;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + activity.getPackageName()));
                } else {
                    intent = appDetailsIntent(activity);
                }
                activity.startActivity(intent);
                return Boolean.TRUE;
            }
            case "storagePermission":
                try {
                    activity.startActivity(storagePermissionIntent(activity));
                } catch (Throwable t) {
                    // 部分 ROM 的权限编辑器组件名不同，直接退回应用详情页
                    activity.startActivity(appDetailsIntent(activity));
                }
                return Boolean.TRUE;
            // ---- 系统服务（Shizuku / 修复无障碍 / 悬浮菜单，PocServices）----------
            case "shizukuState":
            case "shizukuOpen":
            case "repairAccessibility":
            case "floatingWindow":
                return PocServices.execute(activity, method, args);

            // ---- 应用信息（更新 / 关于 / 许可 / 编辑器字号，PocAppInfo）---------
            case "checkUpdate":
            case "updateSource":
            case "openIssueReport":
            case "openAbout":
            case "openLicenses":
            case "editorSettings":
                return PocAppInfo.execute(activity, method, args);

            case "computerPluginState":
                return PocDeviceLink.state(activity);
            case "computerPluginSet":
                throw new IllegalStateException("异步桥方法不应走到同步分支");

            // ---- 启动路径上会被调到、但 POC 只做内存/偏好实现 ----
            case "runtimePreferences":
                return runtimePreferences(activity, args);
            case "drawerPreferences":
                return drawerPreferences(activity, args);
            case "browserPreferences":
                return browserPreferences(activity, args);
            case "importRecords":
                return PocImportRecords.list(activity);
            default:
                throw new UnsupportedOperationException("POC 尚未实现桥方法：" + method);
        }
    }

    /** 优先直接打开「文件管理/存储权限」授权页，失败再退回应用详情页。 */
    private static Intent storagePermissionIntent(Activity activity) {
        String pkg = activity.getPackageName();
        try {
            // 隐式 action 由 ROM 自行解析（各 MIUI 版本组件名不同，避免 explicit 找不到）
            Intent miui = new Intent("miui.intent.action.APP_PERM_EDITOR");
            miui.putExtra("extra_pkgname", pkg);
            if (miui.resolveActivity(activity.getPackageManager()) != null) {
                return miui;
            }
        } catch (Throwable ignored) {
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent allFiles = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + pkg));
                if (allFiles.resolveActivity(activity.getPackageManager()) != null) {
                    return allFiles;
                }
            } catch (Throwable ignored) {
            }
        }
        return appDetailsIntent(activity);
    }

    private static JSONObject bootstrap(Activity activity) throws Exception {
        File scripts = new File(Pref.getScriptDirPath()).getCanonicalFile();
        File samples = SampleFiles.sampleRoot(activity).getCanonicalFile();
        return new JSONObject()
                .put("scripts", scripts.getPath())
                .put("samples", samples.getPath())
                .put("storagePermission", storageState(activity))
                .put("dark", AppThemeRepository.get(activity).isDark())
                .put("frontend", "dart-glass-poc")
                .put("runtime", "ai-js-pro / miuixCompat debug");
    }

    private static JSONObject storageState(Activity activity) throws JSONException {
        boolean granted = hasStorageAccess(activity);
        // prompted=true：POC 不弹授权引导，交给原生权限流程。
        return new JSONObject().put("granted", granted).put("prompted", true).put("allFiles", granted);
    }

    private static boolean hasStorageAccess(Activity activity) {
        boolean granted = new File(Pref.getScriptDirPath()).canRead();
        if (!granted) {
            try {
                granted = activity.checkCallingOrSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                        == PackageManager.PERMISSION_GRANTED;
            } catch (Throwable ignored) {
                granted = false;
            }
        }
        if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                granted = Environment.isExternalStorageManager();
            } catch (Throwable ignored) {
                granted = false;
            }
        }
        return granted;
    }

    private static boolean hasOverlayPermission(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        try {
            return Settings.canDrawOverlays(context);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Intent appDetailsIntent(Context context) {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + context.getPackageName()));
    }

    private static JSONObject deviceState(Activity activity) throws JSONException {
        boolean accessibility = false;
        try {
            accessibility = AccessibilityServiceTool.isAccessibilityServiceEnabled(activity);
        } catch (Throwable error) {
            Log.w(TAG, "accessibility state unavailable", error);
        }
        // task-54：无障碍处于开启状态即记录「用户开过」，供下次启动检测/自动恢复使用。
        if (accessibility && !Pref.isAccessibilityUserEnabled()) {
            Pref.setAccessibilityUserEnabled(true);
        }
        boolean shizukuAvailable = false;
        boolean shizukuAuthorized = false;
        try {
            shizukuAvailable = AccessibilityServiceTool.isShizukuAvailable();
            shizukuAuthorized = shizukuAvailable && AccessibilityServiceTool.hasShizukuPermission();
        } catch (Throwable error) {
            Log.w(TAG, "shizuku state unavailable", error);
        }
        return new JSONObject()
                .put("accessibilityEnabled", accessibility)
                // task-54：用户曾开启过但当前关闭 → 界面可显示「点击重新开启」非阻塞提示。
                .put("accessibilityUserEnabled", Pref.isAccessibilityUserEnabled())
                .put("accessibilityNeedsAttention", Pref.isAccessibilityUserEnabled() && !accessibility)
                .put("floatingWindowShowing", PocServices.floatingMenuShowing())
                .put("floatingUserEnabled", Pref.isFloatingMenuShown())
                .put("overlayGranted", hasOverlayPermission(activity))
                .put("shizukuAvailable", shizukuAvailable)
                .put("shizukuAuthorized", shizukuAuthorized)
                .put("storageGranted", hasStorageAccess(activity));
    }

    private static JSONObject runtimePreferences(Activity activity, JSONObject args) throws Exception {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        if (args.has("key")) {
            String key = args.optString("key", "");
            if (Arrays.asList(RUNTIME_NUMERIC).contains(key)) {
                int value = args.optInt("value", 0);
                int min = "logMaxBackups".equals(key) ? 0 : ("consoleMaxLogCount".equals(key) ? 256 : 4);
                int max = "logMaxBackups".equals(key) ? 100 : 1000000;
                if (value < min || value > max) {
                    throw new IOException("设置数值超出允许范围");
                }
                prefs.edit().putInt(key, value).apply();
            } else if ("stableMode".equals(key)) {
                Pref.setStableModeEnabled(args.optBoolean("value", false));
            } else if (Arrays.asList(RUNTIME_FLAGS).contains(key)) {
                prefs.edit().putBoolean(key, args.optBoolean("value", false)).apply();
            } else if (Arrays.asList(RUNTIME_STRINGS).contains(key)) {
                prefs.edit().putString(key, args.optString("value", "")).apply();
            } else {
                throw new IOException("未知的运行设置");
            }
        }
        JSONObject result = new JSONObject()
                .put("consoleMaxLogCount", prefs.getInt("consoleMaxLogCount", 1000))
                .put("logMaxFileSize", prefs.getInt("logMaxFileSize", 1024))
                .put("logMaxBackups", prefs.getInt("logMaxBackups", 5))
                .put("consoleInitialReadFileSize", prefs.getInt("consoleInitialReadFileSize", 256))
                .put("stableMode", Pref.isStableModeEnabled());
        for (String flag : RUNTIME_FLAGS) {
            result.put(flag, prefs.getBoolean(flag, false));
        }
        for (String key : RUNTIME_STRINGS) {
            String value = prefs.getString(key, null);
            result.put(key, value == null ? JSONObject.NULL : value);
        }
        result.put("key_script_dir_path", Pref.getScriptDirPath());
        return result;
    }

    private static JSONObject drawerPreferences(Activity activity, JSONObject args) throws JSONException {
        SharedPreferences prefs = activity.getSharedPreferences(PREFS_GLASS, Context.MODE_PRIVATE);
        JSONObject value = args.optJSONObject("value");
        if (value == null) {
            return new JSONObject(prefs.getString("drawer.developer", "{}"));
        }
        JSONObject stored = new JSONObject()
                .put("remoteDebug", value.optBoolean("remoteDebug", false))
                .put("keepScreenOn", value.optBoolean("keepScreenOn", false))
                .put("devPluginTipDismissed", value.optBoolean("devPluginTipDismissed", false));
        prefs.edit().putString("drawer.developer", stored.toString()).apply();
        return stored;
    }

    private static JSONObject browserPreferences(Activity activity, JSONObject args) throws JSONException {
        String profile = args.optString("profile", "");
        if (!"scripts".equals(profile) && !"samples".equals(profile)) {
            throw new JSONException("无效的目录配置");
        }
        SharedPreferences prefs = activity.getSharedPreferences(PREFS_GLASS, Context.MODE_PRIVATE);
        JSONObject value = args.optJSONObject("value");
        if (value != null) {
            prefs.edit().putString("browser." + profile, value.toString()).apply();
            return value;
        }
        return new JSONObject(prefs.getString("browser." + profile, "{}"));
    }

    private static Object setTheme(Activity activity, JSONObject args) {
        boolean dark = args.optBoolean("dark", false);
        AppThemeRepository.get(activity).setThemeMode(dark ? 2 : 1);
        return Boolean.TRUE;
    }

    /**
     * 工作区入口要打开“上一次会话”的那份文件（编辑器自己的规则：启动文件获得焦点，
     * 其余标签从会话恢复）。顺序：active_tab → open_tabs → 脚本目录里第一个 .js。
     */
    private static File lastWorkspaceFile(Activity activity) {
        SharedPreferences prefs = activity.getSharedPreferences(EDITOR_PREFS, Context.MODE_PRIVATE);
        File last = readableFile(prefs.getString("active_tab", null));
        if (last != null) {
            return last;
        }
        try {
            JSONArray tabs = new JSONArray(prefs.getString("open_tabs", "[]"));
            for (int i = 0; i < tabs.length(); i++) {
                File candidate = readableFile(tabs.optString(i, ""));
                if (candidate != null) {
                    return candidate;
                }
            }
        } catch (Throwable ignored) {
        }
        return firstScript(new File(Pref.getScriptDirPath()), 0);
    }

    private static File readableFile(String path) {
        if (path == null || path.trim().isEmpty()) {
            return null;
        }
        File file = new File(path);
        return file.isFile() && file.canRead() ? file : null;
    }

    private static File firstScript(File directory, int depth) {
        if (directory == null || depth > 3) {
            return null;
        }
        File[] children = directory.listFiles();
        if (children == null) {
            return null;
        }
        Arrays.sort(children, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        File firstDirectory = null;
        for (File child : children) {
            String name = child.getName();
            if (name.startsWith(".")) {
                continue;
            }
            if (child.isFile() && name.toLowerCase(Locale.ROOT).endsWith(".js")) {
                return child;
            }
            if (child.isDirectory() && firstDirectory == null) {
                firstDirectory = child;
            }
        }
        return firstScript(firstDirectory, depth + 1);
    }

    private static void launch(Activity activity, String className, Integer flags) {
        Intent intent = new Intent().setClassName(activity, className);
        if (flags != null) {
            intent.addFlags(flags);
        }
        activity.startActivity(intent);
    }

    private static JSONArray list(Activity activity, JSONObject args) throws Exception {
        File scripts = new File(Pref.getScriptDirPath()).getCanonicalFile();
        File samples = SampleFiles.sampleRoot(activity).getCanonicalFile();
        if (!scripts.exists() && !scripts.mkdirs() && !scripts.isDirectory()) {
            throw new IOException("无法创建脚本目录，请允许存储访问");
        }
        String owner = args.optString("owner", "default");
        if (!"scripts".equals(owner) && !"samples".equals(owner) && !"default".equals(owner)) {
            throw new IOException("目录请求来源无效");
        }
        String rawPath = args.optString("path", "").trim();
        File directory = rawPath.isEmpty() ? scripts : new File(rawPath).getCanonicalFile();
        boolean inScripts = within(directory, scripts);
        boolean inSamples = within(directory, samples);
        if (!inScripts && !inSamples) {
            throw new IOException("目录不在 POC 允许的工作区内");
        }
        if (!directory.isDirectory()) {
            throw new IOException("目录已经不存在，请返回上一级");
        }
        String query = args.optString("query", "").trim();
        if (query.length() > 256) {
            throw new IOException("搜索表达式不能超过 256 个字符");
        }
        String needle = query.toLowerCase(Locale.ROOT);
        boolean showHidden = args.optBoolean("showHidden", false);
        File[] children = directory.listFiles();
        if (children == null) {
            throw new IOException("无法读取目录：" + directory.getName());
        }
        List<File> files = new ArrayList<>();
        for (File child : children) {
            String name = child.getName();
            if (name.startsWith(".autojs-glass-") || name.startsWith(".flutter-")) {
                continue;
            }
            if (!showHidden && name.startsWith(".")) {
                continue;
            }
            if (!needle.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            if (files.size() >= MAX_ENTRIES) {
                throw new IOException("目录条目超过 " + MAX_ENTRIES + "，请进入子目录后再搜索");
            }
            files.add(child);
        }
        Collections.sort(files, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) {
                return a.isDirectory() ? -1 : 1;
            }
            return a.getName().compareToIgnoreCase(b.getName());
        });
        JSONArray values = new JSONArray();
        for (File file : files) {
            values.put(new JSONObject()
                    .put("name", file.getName())
                    .put("path", file.getAbsolutePath())
                    .put("relativePath", file.getAbsolutePath().substring(directory.getPath().length() + 1))
                    .put("directory", file.isDirectory())
                    .put("project", file.isDirectory() && new File(file, "project.json").isFile())
                    .put("size", file.length())
                    .put("modified", file.lastModified()));
        }
        return values;
    }

    private static boolean within(File file, File root) {
        String path = file.getPath();
        String base = root.getPath();
        return path.equals(base) || path.startsWith(base + File.separator);
    }

    // ---- 任务页「查看该任务日志」：按 name/token 找该脚本最近一次运行日志 ----------

    /**
     * 「该任务最近一次运行日志」：可按 {@code name}（任务页任务名）或 {@code token}
     * （任务页 token，如 script:3；桥自动映射到正在跑的任务名）过滤。
     *
     * <p>找不到对应文件时返回空 content（任务页显示"暂无该任务日志"），不影响原 logs 行为。
     */
    private static Object logsForTask(Activity activity, JSONObject args) throws Exception {
        String name = sanitizeTaskKey(args.optString("name", ""));
        String token = sanitizeTaskKey(args.optString("token", ""));
        if (name.isEmpty() && token.isEmpty()) {
            // Flutter 侧把 token 以 JSON null 传下来（普通「运行日志」页没有任务 token）：
            // 行为退回原来的「最近一次脚本运行日志」，保持不变。
            return PocRuntime.execute(activity, "logs", args);
        }
        if (name.isEmpty()) {
            name = scriptNameForToken(token);
        }
        File log = name.isEmpty() ? null : findLogFileByName(activity, name);
        if (log == null) {
            return new JSONObject().put("content", "").put("truncated", false)
                    .put("length", 0L).put("start", 0L).put("end", 0L)
                    .put("name", name).put("path", "").put("modified", 0L);
        }
        JSONObject result = readLogTail(log, args);
        return result.put("name", log.getName())
                .put("path", log.getAbsolutePath())
                .put("modified", log.lastModified());
    }

    /** 运行中任务 token → 脚本名（:script/:native 用公开 runningTasks 快照；main 取主进程执行名）。 */
    private static String scriptNameForToken(String token) {
        if (token == null || token.isEmpty()) {
            return "";
        }
        try {
            JSONArray scriptTasks = PocScriptHost.runningTasks();
            for (int i = 0; i < scriptTasks.length(); i++) {
                JSONObject task = scriptTasks.optJSONObject(i);
                if (task != null && token.equals(task.optString("token", ""))) {
                    return task.optString("name", "");
                }
            }
            JSONArray nativeTasks = PocNativeRunner.runningTasks();
            for (int i = 0; i < nativeTasks.length(); i++) {
                JSONObject task = nativeTasks.optJSONObject(i);
                if (task != null && token.equals(task.optString("token", ""))) {
                    return task.optString("name", "");
                }
            }
            if (LIVE_TOKEN_MAIN.equals(token) || "main".equals(token)) {
                return mainScriptName();
            }
        } catch (Throwable error) {
            Log.w(TAG, "token 到脚本名映射失败", error);
        }
        return "";
    }

    /** 最近日志（新→旧）里按脚本名找该脚本最近一次运行日志文件（跳过 app-crash*.log）。 */
    private static File findLogFileByName(Activity activity, String name) {
        try {
            String needle = name.toLowerCase(Locale.ROOT);
            int dot = needle.lastIndexOf('.');
            String base = dot > 0 ? needle.substring(0, dot) : needle;
            List<File> recent =
                    com.stardust.autojs.core.console.ScriptRunLog.listRecent(activity, 80);
            for (File file : recent) {
                if (file == null || !file.isFile()) {
                    continue;
                }
                String fileName = file.getName().toLowerCase(Locale.ROOT);
                if (fileName.startsWith(
                        com.stardust.autojs.core.console.ScriptRunLog.CRASH_FILE_PREFIX)) {
                    continue;
                }
                if (fileName.contains(needle) || (!base.isEmpty() && fileName.contains(base))) {
                    return file;
                }
            }
            return null;
        } catch (Throwable error) {
            Log.w(TAG, "按名查找运行日志失败", error);
            return null;
        }
    }

    /** 读日志尾部窗口（与 PocRuntime.logs 同契约：content/truncated/length/start/end；支持 from/end/window）。 */
    private static JSONObject readLogTail(File log, JSONObject args) throws Exception {
        long window = args.has("window") ? args.getLong("window") : 262144L;
        long end = args.has("end") ? args.getLong("end") : 0L;
        window = Math.max(4096L, Math.min(8L * 1024 * 1024, window));
        end = Math.max(0L, end);
        try (java.io.RandomAccessFile input = new java.io.RandomAccessFile(log, "r")) {
            long length = input.length();
            long stop = Math.max(0L, length - end);
            long start;
            boolean incremental = args.has("from");
            if (incremental) {
                start = args.getLong("from");
                if (start < 0L || start > stop) {
                    start = Math.max(0L, stop - window);
                }
            } else {
                start = Math.max(0L, stop - window);
            }
            if (start >= stop) {
                return new JSONObject().put("content", "").put("truncated", false)
                        .put("length", length).put("start", stop).put("end", stop)
                        .put("appended", true);
            }
            input.seek(start);
            byte[] bytes = new byte[(int) (stop - start)];
            input.readFully(bytes);
            String content = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            if (incremental) {
                int newline = content.lastIndexOf('\n');
                if (newline >= 0 && stop < length) {
                    content = content.substring(0, newline + 1);
                    stop = start + content.getBytes(
                            java.nio.charset.StandardCharsets.UTF_8).length;
                }
            } else {
                if (start > 0L) {
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
            return new JSONObject().put("content", content).put("truncated", start > 0L)
                    .put("length", length).put("start", start).put("end", stop)
                    .put("incremental", incremental).put("window", window);
        }
    }

    // ---- 统一实时日志流（HostConsoleSink：主进程脚本 + :script + :native） ----------

    /** 主进程脚本在统一流里的 token（:script/:native 用协议 token 原值）。 */
    private static final String LIVE_TOKEN_MAIN = "main";

    /** liveLogs 行时间戳格式：与 ScriptRunLog / Flutter parseLogItems 的行格式一致。 */
    private static final java.text.SimpleDateFormat LIVE_TIME_FORMAT =
            new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault());

    private static boolean sGlobalConsoleTapped;

    /**
     * 实时统一流：从 {@link HostConsoleSink} 取最近/增量行（主进程脚本 + :script + :native 汇总）。
     *
     * <p>参数（全部可选）：{@code since}（上次返回的 version，用于增量）、{@code maxLines}
     * （默认 800，上限 2000）、{@code token}（任务页过滤，如 {@code "script:3"} /
     * {@code "native:3"} / {@code "main"}；空 = 全部）。
     *
     * <p>返回：{@code {content, count, version, running, lines, token}}。
     * {@code content} 每行格式 {@code HH:mm:ss.SSS [I] 脚本名: 正文}，Flutter LogPage 的
     * parseLogItems 可直接解析（等级着色/6 档过滤/搜索照旧）；{@code version} 传回下次 since。
     */
    private static Object liveLogs(JSONObject args) {
        ensureGlobalConsoleTap();
        long since = Math.max(0L, args.optLong("since", 0L));
        int maxLines = args.has("maxLines")
                ? args.optInt("maxLines", 800) : args.optInt("window", 800);
        if (maxLines <= 0) maxLines = 800;
        if (maxLines > HostConsoleSink.MAX_BUFFER_LINES) {
            maxLines = HostConsoleSink.MAX_BUFFER_LINES;
        }
        String token = normalizeLiveToken(sanitizeTaskKey(args.optString("token", "")));
        List<HostConsoleSink.Entry> entries =
                HostConsoleSink.snapshotSince(since, maxLines, token);
        StringBuilder content = new StringBuilder();
        JSONArray structured = new JSONArray();
        try {
            for (HostConsoleSink.Entry entry : entries) {
                if (content.length() > 0) {
                    content.append('\n');
                }
                content.append(formatLiveLine(entry));
                structured.put(new JSONObject()
                        .put("seq", entry.seq)
                        .put("token", entry.token)
                        .put("name", entry.name)
                        .put("level", entry.level)
                        .put("levelName", levelName(entry.level))
                        .put("time", entry.time)
                        .put("line", entry.line));
            }
            return new JSONObject()
                    .put("content", content.toString())
                    .put("entries", structured)
                    .put("count", HostConsoleSink.lineCount())
                    .put("version", HostConsoleSink.version())
                    .put("running", isAnyScriptRunning())
                    .put("returned", entries.size())
                    .put("token", token);
        } catch (Throwable error) {
            throw new IllegalStateException("实时日志组包失败", error);
        }
    }

    /** 清空统一实时流缓冲（只清 HostConsoleSink，不影响已落盘的运行日志文件）。 */
    private static Object clearLiveLogs() {
        HostConsoleSink.clear();
        try {
            return new JSONObject().put("cleared", true)
                    .put("version", HostConsoleSink.version());
        } catch (Throwable error) {
            throw new IllegalStateException("清空实时日志失败", error);
        }
    }

    /**
     * token 参数归一化：接受任务页展示形态 "script:3"/"native:3"（原样传给 HostConsoleSink 的
     * 匹配器，它会同时兼容上游可能写入的裸 token "3"）、协议原始 token，及 "main"。
     */
    private static String normalizeLiveToken(String raw) {
        if (raw == null) {
            return "";
        }
        String token = raw.trim();
        if ("main-process".equals(token) || "mainProcess".equals(token)) {
            return LIVE_TOKEN_MAIN;
        }
        return token;
    }

    /** Flutter 的 JSON null 经 bridge 传过来会变成字符串 "null"；统一当空处理。 */
    private static String sanitizeTaskKey(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim();
        if ("null".equalsIgnoreCase(value) || "undefined".equalsIgnoreCase(value)) {
            return "";
        }
        return value;
    }

    private static String formatLiveLine(HostConsoleSink.Entry entry) {
        String name = entry.name == null || entry.name.isEmpty() ? "" : entry.name + ": ";
        String time;
        synchronized (LIVE_TIME_FORMAT) {
            time = LIVE_TIME_FORMAT.format(new java.util.Date(entry.time));
        }
        return time + " [" + levelChar(entry.level) + "] " + name + entry.line;
    }

    private static String levelChar(int level) {
        switch (level) {
            case Log.VERBOSE:
                return "V";
            case Log.DEBUG:
                return "D";
            case Log.INFO:
                return "I";
            case Log.WARN:
                return "W";
            case Log.ERROR:
                return "E";
            case Log.ASSERT:
                return "A";
            default:
                return "I";
        }
    }

    private static String levelName(int level) {
        switch (level) {
            case Log.VERBOSE:
                return "TRACE";
            case Log.DEBUG:
                return "DEBUG";
            case Log.INFO:
                return "INFO";
            case Log.WARN:
                return "WARN";
            case Log.ERROR:
                return "ERROR";
            case Log.ASSERT:
                return "FATAL";
            default:
                return "INFO";
        }
    }

    /** 主进程 / :script / :native 任一有脚本在跑即为 true（任务页/日志页状态用）。 */
    private static boolean isAnyScriptRunning() {
        return !runningMainExecutions().isEmpty()
                || PocScriptHost.runningTasks().length() > 0
                || PocNativeRunner.runningTasks().length() > 0;
    }

    /** 主进程里正在跑的脚本执行（数量少，遍历一次没开销）。 */
    private static List<ScriptExecution> runningMainExecutions() {
        try {
            AutoJs autojs = AutoJs.getInstance();
            if (autojs == null || autojs.getScriptEngineService() == null) {
                return Collections.emptyList();
            }
            java.util.Collection<ScriptExecution> executions =
                    autojs.getScriptEngineService().getScriptExecutions();
            return executions == null ? Collections.<ScriptExecution>emptyList()
                    : new ArrayList<>(executions);
        } catch (Throwable error) {
            return Collections.emptyList();
        }
    }

    /**
     * 一次安装 GlobalConsole 的「被动采集」：主进程脚本的 console 会走 GlobalConsole，
     * 用 log4j Appender 旁路抄一份进 HostConsoleSink，让统一实时流也能看到主进程脚本。
     *
     * <p>用 Appender 而不是 ConsoleImpl.setLogListener：后者是单槽位，会覆盖原生日志页监听。
     * HostConsoleSink 把 :script 行写 GlobalConsole 时也会经过这里，按「buffer 最后一行 + 时间」
     * 去重，避免 :script 行在统一流里出现两次。
     */
    private static synchronized void ensureGlobalConsoleTap() {
        if (sGlobalConsoleTapped) {
            return;
        }
        try {
            org.apache.log4j.Logger logger = org.apache.log4j.Logger.getLogger("GlobalConsole");
            logger.addAppender(new GlobalConsoleTap());
            sGlobalConsoleTapped = true;
        } catch (Throwable error) {
            Log.w(TAG, "无法把 GlobalConsole 接入实时日志流", error);
        }
    }

    /** log4j 被动采集：GlobalConsole.println 的行格式是 {@code HH:mm:ss.SSS/L: 正文}。 */
    private static final class GlobalConsoleTap extends org.apache.log4j.AppenderSkeleton {

        private static final java.util.regex.Pattern LINE = java.util.regex.Pattern.compile(
                "^\\d{2}:\\d{2}:\\d{2}\\.\\d{3}/([VDIWEAF]): (.*)$");

        @Override
        protected void append(org.apache.log4j.spi.LoggingEvent event) {
            try {
                Object message = event == null ? null : event.getMessage();
                String text = message == null ? "" : message.toString();
                java.util.regex.Matcher matcher = LINE.matcher(text);
                if (!matcher.matches()) {
                    return;
                }
                String body = matcher.group(2) == null ? "" : matcher.group(2);
                if (body.isEmpty()) {
                    return;
                }
                // :script/:native 行已由 HostConsoleSink.append 先写入缓冲：跳过重复行。
                List<HostConsoleSink.Entry> last = HostConsoleSink.snapshot(1, null);
                if (!last.isEmpty()) {
                    HostConsoleSink.Entry previous = last.get(last.size() - 1);
                    if (previous.line.equals(body)
                            && Math.abs(System.currentTimeMillis() - previous.time) < 1500L) {
                        return;
                    }
                }
                HostConsoleSink.append(LIVE_TOKEN_MAIN, mainScriptName(),
                        levelOf(matcher.group(1)), body);
            } catch (Throwable ignored) {
                // 被动采集失败绝不能影响脚本/日志本身。
            }
        }

        @Override
        public void close() {
        }

        @Override
        public boolean requiresLayout() {
            return false;
        }

        private static int levelOf(String ch) {
            if ("V".equals(ch)) {
                return Log.VERBOSE;
            }
            if ("D".equals(ch)) {
                return Log.DEBUG;
            }
            if ("W".equals(ch)) {
                return Log.WARN;
            }
            if ("E".equals(ch)) {
                return Log.ERROR;
            }
            if ("A".equals(ch)) {
                return Log.ASSERT;
            }
            return Log.INFO;
        }
    }

    /** 主进程当前脚本名（只有一个在跑时用于统一流显示；否则留空）。 */
    private static String mainScriptName() {
        try {
            List<ScriptExecution> executions = runningMainExecutions();
            if (executions.size() != 1) {
                return "";
            }
            com.stardust.autojs.script.ScriptSource source = executions.get(0).getSource();
            String name = source == null ? null : source.getName();
            if (name == null) {
                return "";
            }
            int dot = name.lastIndexOf('.');
            return dot > 0 ? name.substring(0, dot) : name;
        } catch (Throwable error) {
            return "";
        }
    }
}