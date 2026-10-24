package com.jdkshen.aijspro.flutterpoc;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import com.jdkshen.aijspro.Pref;
import com.jdkshen.aijspro.external.fileprovider.AppFileProvider;
import com.jdkshen.aijspro.model.sample.SampleFiles;
import com.jdkshen.aijspro.model.script.ScriptFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.Enumeration;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 第二批集成桥：MCP 开关、插件列表/启停/安装/图标、文件与资源导入（系统选择器）、
 * 示例重置/复制到脚本目录、桌面快捷方式。
 *
 * <p>MCP 的类在 miuix 源集里，这里用反射调用，保证 POC 调试源集在任意变体下都能编译。
 */
final class PocIntegration {

    private static final String TAG = "GlassFlutterPoc";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final String PREFS_GLASS = "flutter-glass";
    private static final String PREF_DISABLED_PLUGINS = "plugins.disabled";

    private static final int REQ_IMPORT_WORKSPACE = 8801;
    private static final int REQ_IMPORT_PLUGIN = 8802;
    private static final int REQ_IMPORT_RESOURCE = 8803;
    private static final int REQ_IMPORT_PROJECT_ICON = 8804;
    private static final int REQ_IMPORT_PROJECT_SIGNING = 8805;

    private static final Map<Integer, PendingImport> PENDING = new HashMap<>();

    private PocIntegration() {
    }

    interface Callback {
        void reply(Object data, Throwable error);
    }

    private static final class PendingImport {
        final Activity activity;
        final File target;
        final String mode;
        final Callback callback;

        PendingImport(Activity activity, File target, String mode, Callback callback) {
            this.activity = activity;
            this.target = target;
            this.mode = mode;
            this.callback = callback;
        }
    }

    /** 同步方法。 */
    static Object execute(Activity activity, String method, JSONObject args) throws Exception {
        switch (method) {
            case "mcpState":
                return mcpState(activity);
            case "mcpSet":
                return mcpSet(activity, args);
            case "pluginStates":
                return pluginStates(activity);
            case "pluginSetEnabled":
                return pluginSetEnabled(activity, args.getString("packageName"),
                        args.getBoolean("enabled"));
            case "pluginIcon":
                return pluginIcon(activity, args.getString("packageName"));
            case "openBuildPage":
                return openBuildPage(activity, args.getString("path"));
            case "pendingAction":
                return pendingFromPrefs(activity, "pending_action");
            case "shortcut":
                return shortcut(activity, args.getString("path"));
            case "resetSample":
                return resetSample(activity, args.getString("path"));
            case "copyExampleTree":
                return copyExampleTree(activity, args.getString("path"));
            case "sampleAction":
                return sampleAction(activity, args);
            // ---- 玻璃打包页 ---------------------------------------------------
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
                return PocPackaging.execute(activity, method, args);
            default:
                throw new UnsupportedOperationException("POC 尚未实现集成桥方法：" + method);
        }
    }

    /** 需要系统选择器或网络的方法。 */
    static boolean isAsync(String method) {
        return "importWorkspace".equals(method) || "importPlugin".equals(method)
                || "importResource".equals(method) || "importCatalogResource".equals(method)
                || "installPlugin".equals(method) || "computerPluginSet".equals(method)
                || "importProjectIcon".equals(method) || "importProjectSigning".equals(method);
    }

    static void startAsync(Activity activity, String method, JSONObject args, Callback callback)
            throws Exception {
        switch (method) {
            case "importWorkspace":
                pickFiles(activity, REQ_IMPORT_WORKSPACE, "workspace",
                        new File(args.getString("path")), true, callback);
                return;
            case "importPlugin":
                pickFiles(activity, REQ_IMPORT_PLUGIN, "plugin", cacheDir(activity), false, callback);
                return;
            case "importResource":
                pickFiles(activity, REQ_IMPORT_RESOURCE, "resource", cacheDir(activity), false, callback);
                return;
            case "importCatalogResource":
                importCatalogResource(activity, args, callback);
                return;
            case "importProjectIcon": {
                File project = new File(args.getString("path")).getCanonicalFile();
                if (!project.isDirectory()) {
                    throw new IOException("项目目录不存在");
                }
                pickSingle(activity, REQ_IMPORT_PROJECT_ICON, "projectIcon", project, "image/*", callback);
                return;
            }
            case "importProjectSigning": {
                File dir = com.jdkshen.aijspro.autojs.build.sign.SigningOptions.INSTANCE
                        .keyStoreDir(Pref.getScriptDirPath());
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    throw new IOException("无法创建签名目录");
                }
                pickSingle(activity, REQ_IMPORT_PROJECT_SIGNING, "projectSigning", dir, "*/*", callback);
                return;
            }
            case "installPlugin":
                installPlugin(activity, args, callback);
                return;
            case "computerPluginSet":
                // 服务在应用主线程创建，等待必须放到后台线程，否则会把 startForeground 一起堵住。
                new Thread(() -> {
                    try {
                        Object data = PocDeviceLink.set(activity, args);
                        MAIN.post(() -> callback.reply(data, null));
                    } catch (Throwable error) {
                        MAIN.post(() -> callback.reply(null, error));
                    }
                }, "poc-computer-plugin").start();
                return;
            default:
                callback.reply(null, new UnsupportedOperationException("未知导入操作：" + method));
        }
    }

    private static void pickSingle(Activity activity, int requestCode, String mode, File target,
                                   String mime, Callback callback) {
        synchronized (PENDING) {
            PENDING.put(requestCode, new PendingImport(activity, target, mode, callback));
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivityForResult(intent, requestCode);
    }

    static boolean onActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {
        PendingImport pending;
        synchronized (PENDING) {
            pending = PENDING.remove(requestCode);
        }
        if (pending == null) {
            return false;
        }
        if (resultCode != Activity.RESULT_OK || data == null) {
            pending.callback.reply(cancelled(), null);
            return true;
        }
        if ("projectIcon".equals(pending.mode) || "projectSigning".equals(pending.mode)) {
            Uri uri = data.getData();
            if (uri == null) {
                pending.callback.reply(null, new IOException("没有选择文件"));
                return true;
            }
            new Thread(() -> {
                try {
                    File copied = copyUri(activity, uri, pending.target, null);
                    JSONObject payload = new JSONObject()
                            .put("cancelled", false)
                            .put("path", copied.getPath())
                            .put("name", copied.getName());
                    MAIN.post(() -> pending.callback.reply(payload, null));
                } catch (Throwable error) {
                    MAIN.post(() -> pending.callback.reply(null, error));
                }
            }, "poc-import-project").start();
            return true;
        }
        if ("plugin".equals(pending.mode) || "resource".equals(pending.mode)) {
            Uri uri = data.getData();
            if (uri == null) {
                pending.callback.reply(null, new IOException("没有选择文件"));
                return true;
            }
            new Thread(() -> {
                try {
                    File copied = copyUri(activity, uri, pending.target, null);
                    if ("plugin".equals(pending.mode)) {
                        PocImportRecords.add(activity, "plugin", copied.getName(), copied.getPath(),
                                uri.toString(), "", "");
                    } else {
                        PocImportRecords.add(activity, "resource", copied.getName(),
                                copied.getPath(), "", "", "");
                    }
                    MAIN.post(() -> pending.callback.reply(copied.getPath(), null));
                } catch (Throwable error) {
                    MAIN.post(() -> pending.callback.reply(null, error));
                }
            }, "poc-import-file").start();
            return true;
        }
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                uris.add(data.getClipData().getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        new Thread(() -> {
            try {
                JSONArray imported = new JSONArray();
                for (Uri uri : uris) {
                    File copied = copyUri(activity, uri, pending.target, null);
                    if (copied != null) {
                        PocImportRecords.add(activity, "workspace", copied.getName(),
                                copied.getPath(), "", "", "");
                        imported.put(copied.getName());
                    }
                }
                JSONObject value = new JSONObject().put("cancelled", false)
                        .put("count", imported.length()).put("names", imported);
                MAIN.post(() -> pending.callback.reply(value, null));
            } catch (Throwable error) {
                MAIN.post(() -> pending.callback.reply(null, error));
            }
        }, "poc-import-workspace").start();
        return true;
    }

    private static Object cancelled() {
        JSONObject value = new JSONObject();
        try {
            value.put("cancelled", true);
        } catch (Exception ignored) {
        }
        return value;
    }

    // ---- MCP -------------------------------------------------------------

    private static Object mcpState(Activity activity) throws Exception {
        Class<?> settings = Class.forName("com.jdkshen.aijspro.mcp.McpSettings");
        Object instance = settings.getField("INSTANCE").get(null);
        int settingsPort = (Integer) settings.getMethod("port", Context.class).invoke(instance, activity);
        // McpService listens on 0.0.0.0; report the effective network mode.
        boolean lan = true;
        boolean autoStart = (Boolean) settings.getMethod("autoStart", Context.class).invoke(instance, activity);
        // task-105：运行状态以持久化状态 + 10s 心跳为准，避免进程被回收/服务异常后仍显示「运行中」。
        boolean stateKnown = false;
        boolean stateRunning = false;
        long stateUpdatedAt = 0L;
        long heartbeatAgeMs = -1L;
        int statePort = 0;
        String stateLocalUrl = "";
        String stateLanUrl = "";
        String stateError = "";
        try {
            Class<?> service = Class.forName("com.jdkshen.aijspro.mcp.McpService");
            Object companion = service.getField("Companion").get(null);
            Object state = companion.getClass().getMethod("readState", Context.class).invoke(companion, activity);
            if (state != null) {
                Class<?> stateClass = state.getClass();
                stateKnown = true;
                stateRunning = (Boolean) stateClass.getMethod("getRunning").invoke(state);
                stateUpdatedAt = (Long) stateClass.getMethod("getUpdatedAt").invoke(state);
                statePort = (Integer) stateClass.getMethod("getPort").invoke(state);
                stateLocalUrl = String.valueOf(stateClass.getMethod("getLocalUrl").invoke(state));
                stateLanUrl = String.valueOf(stateClass.getMethod("getLanUrl").invoke(state));
                stateError = String.valueOf(stateClass.getMethod("getError").invoke(state));
                if ("null".equals(stateLocalUrl)) stateLocalUrl = "";
                if ("null".equals(stateLanUrl)) stateLanUrl = "";
                if ("null".equals(stateError)) stateError = "";
                heartbeatAgeMs = stateUpdatedAt > 0 ? System.currentTimeMillis() - stateUpdatedAt : -1L;
            }
        } catch (Throwable error) {
            Log.w(TAG, "C-MCP: 读取持久化状态失败", error);
        }
        boolean stateFresh = stateKnown && stateRunning && stateUpdatedAt > 0
                && heartbeatAgeMs >= 0 && heartbeatAgeMs <= 10000L;
        boolean liveRunning = mcpRunning();
        boolean running = stateKnown ? stateFresh : liveRunning;
        int port = statePort > 0 ? statePort : settingsPort;
        String error = stateError.isEmpty() ? mcpLastError() : stateError;
        // task-55：局域网地址不能是占位符。枚举真实局域网 IPv4（优先 wlan/ap 的 site-local）。
        String lanIp = lanIpv4Address();
        String localUrl = stateLocalUrl.isEmpty()
                ? "http://127.0.0.1:" + port + "/mcp" : stateLocalUrl;
        String lanUrl = stateLanUrl.isEmpty()
                ? (lan && !lanIp.isEmpty() ? "http://" + lanIp + ":" + port + "/mcp" : "")
                : stateLanUrl;
        String stateLabel = !stateKnown ? "unknown" : (stateFresh ? "running" : (stateRunning ? "stale" : "stopped"));
        // task-103/105：C-MCP 诊断日志（state/heartbeat/port/真实 IP/URL/autoStart/error）。
        Log.i(TAG, "C-MCP: state=" + stateLabel + " running=" + running + " live=" + liveRunning
                + " port=" + port + " heartbeatAgeMs=" + heartbeatAgeMs
                + " ip=" + (lanIp.isEmpty() ? "<none>" : lanIp) + " lan=" + lan
                + " autoStart=" + autoStart + " localUrl=" + localUrl
                + " lanUrl=" + (lanUrl.isEmpty() ? "<empty>" : lanUrl)
                + " error=" + (error.isEmpty() ? "<none>" : error));
        JSONObject diagnostics = new JSONObject();
        try {
            Class<?> service = Class.forName("com.jdkshen.aijspro.mcp.McpService");
            Object companion = service.getField("Companion").get(null);
            Class<?> type = companion.getClass();
            diagnostics.put("toolCount", type.getMethod("getToolCount").invoke(companion))
                    .put("requestCount", type.getMethod("getRequestCount").invoke(companion))
                    .put("lastRequestAt", type.getMethod("getLastRequestAt").invoke(companion))
                    .put("recentEvents", new JSONArray((java.util.Collection<?>) type.getMethod("recentEvents").invoke(companion)));
        } catch (ReflectiveOperationException ignored) { }
        try {
            Object engineJson = Class.forName("com.jdkshen.aijspro.mcp.McpEngineDiagnostics")
                    .getMethod("snapshotJson", Context.class).invoke(null, activity);
            diagnostics.put("engineDiagnostics", new JSONObject(String.valueOf(engineJson)));
        } catch (ReflectiveOperationException | org.json.JSONException unavailable) {
            diagnostics.put("engineDiagnostics", new JSONObject()
                    .put("schemaVersion", 1).put("unavailable", true));
        }
        return diagnostics.put("bindHost", "0.0.0.0")
                .put("running", running)
                .put("enabled", running)
                .put("liveRunning", liveRunning)
                .put("state", stateLabel)
                .put("stale", stateKnown && stateRunning && !stateFresh)
                .put("updatedAt", stateUpdatedAt)
                .put("heartbeatAgeMs", heartbeatAgeMs)
                .put("lanEnabled", lan)
                .put("autoStart", autoStart)
                .put("port", port)
                .put("localUrl", localUrl)
                .put("lanIp", lanIp.isEmpty() ? JSONObject.NULL : lanIp)
                .put("lanUrl", lanUrl)
                .put("error", error);
    }

    private static Object mcpSet(Activity activity, JSONObject args) throws Exception {
        Class<?> settings = Class.forName("com.jdkshen.aijspro.mcp.McpSettings");
        Object instance = settings.getField("INSTANCE").get(null);
        boolean runningBefore = mcpRunning();
        boolean requested = args.has("enabled") ? args.getBoolean("enabled") : runningBefore;
        boolean lanChanged = args.has("lanEnabled");
        boolean portChanged = args.has("port");
        StringBuilder requestLog = new StringBuilder("C-MCP: set");
        try {
            requestLog.append(" enabled=").append(args.has("enabled") ? String.valueOf(requested) : "<unchanged>");
            if (args.has("enabled")) {
                // task-57/task-103：持久化「用户希望 MCP 开启」的意图（McpSettings.autoStart）。
                // 重启 App 后由 App 启动路径上的 McpAutoLifecycle 按 autoStart && !running && lastError==null 自动恢复。
                settings.getMethod("setAutoStart", Context.class, boolean.class)
                        .invoke(instance, activity, requested);
            }
            if (lanChanged) {
                boolean lan = args.getBoolean("lanEnabled");
                settings.getMethod("setLanEnabled", Context.class, boolean.class)
                        .invoke(instance, activity, lan);
                requestLog.append(" lanEnabled=").append(lan);
            }
            if (portChanged) {
                int port = args.getInt("port");
                if (port < 1024 || port > 65535) {
                    throw new IOException("MCP 端口必须在 1024-65535");
                }
                settings.getMethod("setPort", Context.class, int.class).invoke(instance, activity, port);
                requestLog.append(" port=").append(port);
            }
            Log.i(TAG, requestLog.toString());

            // 局域网/端口变化时重启服务让配置生效；普通开关不重启已运行的服务。
            boolean needRestart = runningBefore && requested && (lanChanged || portChanged);
            if (needRestart) {
                stopMcp(activity);
                if (!waitForMcpRunning(false, 1500)) {
                    throw new IOException("MCP 服务重启失败：旧服务未能停止");
                }
            }
            boolean running = mcpRunning();
            if (requested) {
                if (!running) {
                    startMcp(activity);
                    if (!waitForMcpRunning(true, 2500)) {
                        String last = mcpLastError();
                        int portNow = (Integer) settings.getMethod("port", Context.class).invoke(instance, activity);
                        String reason = last == null || last.isEmpty()
                                ? "端口 " + portNow + " 可能被占用，或前台服务被系统限制"
                                : last;
                        throw new IOException("MCP 服务启动失败：" + reason);
                    }
                }
            } else if (running) {
                stopMcp(activity);
                if (!waitForMcpRunning(false, 2000)) {
                    throw new IOException("MCP 服务停止失败，请稍后重试");
                }
            }
        } catch (IOException error) {
            Log.w(TAG, "C-MCP: " + error.getMessage(), error);
            throw error;
        } catch (Throwable error) {
            Log.w(TAG, "C-MCP: 设置失败", error);
            String message = error.getMessage() == null ? String.valueOf(error) : error.getMessage();
            throw new IOException("MCP 设置失败：" + message);
        }
        Object state = mcpState(activity);
        Log.i(TAG, "C-MCP: set done state=" + state);
        return state;
    }

    /**
     * task-55：枚举本机局域网 IPv4（MCP 局域网地址显示用）。
     *
     * <p>排序：优先名称以 wlan 或 ap 开头的 Wi-Fi 网卡上的 site-local 地址
     * （192.168/10/172.16-31），其次其它非回环、site-local 网卡；跳过
     * rmnet/ccmni（移动数据，外部连不上）、p2p/tun/dummy。
     * 找不到返回空串，避免把「&lt;本机局域网IP&gt;」这种占位符显示给用户。
     */
    private static String lanIpv4Address() {
        String fallback = "";
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (network == null || !network.isUp() || network.isLoopback()) {
                    continue;
                }
                String name = network.getName() == null
                        ? "" : network.getName().toLowerCase(java.util.Locale.ROOT);
                if (name.startsWith("rmnet") || name.startsWith("ccmni")
                        || name.startsWith("p2p") || name.startsWith("tun")
                        || name.startsWith("dummy")) {
                    continue;
                }
                boolean preferred = name.startsWith("wlan") || name.startsWith("ap")
                        || name.startsWith("eth");
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses != null && addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) {
                        continue;
                    }
                    String host = address.getHostAddress();
                    if (host == null || host.isEmpty() || host.startsWith("127.")) {
                        continue;
                    }
                    if (!address.isSiteLocalAddress()) {
                        // 169.254/公网地址不作为局域网地址候选。
                        continue;
                    }
                    if (preferred) {
                        return host;
                    }
                    if (fallback.isEmpty()) {
                        fallback = host;
                    }
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "枚举局域网 IPv4 失败", error);
        }
        return fallback;
    }

    private static boolean mcpRunning() {
        try {
            Class<?> service = Class.forName("com.jdkshen.aijspro.mcp.McpService");
            Object companion = service.getField("Companion").get(null);
            return (Boolean) companion.getClass().getMethod("getRunning").invoke(companion);
        } catch (Throwable error) {
            Log.w(TAG, "读取 MCP 状态失败", error);
            return false;
        }
    }

    private static String mcpLastError() {
        try {
            Class<?> service = Class.forName("com.jdkshen.aijspro.mcp.McpService");
            Object companion = service.getField("Companion").get(null);
            Object value = companion.getClass().getMethod("getLastError").invoke(companion);
            return value == null ? "" : value.toString();
        } catch (Throwable error) {
            return "";
        }
    }

    private static void startMcp(Activity activity) throws IOException {
        try {
            // McpService.start(Context) 内部用 startForegroundService（API26+），比自己 startService 更稳。
            Class<?> service = Class.forName("com.jdkshen.aijspro.mcp.McpService");
            Object companion = service.getField("Companion").get(null);
            companion.getClass().getMethod("start", Context.class).invoke(companion, activity);
            Log.i(TAG, "C-MCP: start requested");
        } catch (Throwable error) {
            Log.w(TAG, "C-MCP: Companion.start 调用失败，回退 startService", error);
            try {
                Intent intent = new Intent().setClassName(activity, "com.jdkshen.aijspro.mcp.McpService");
                activity.startService(intent);
                Log.i(TAG, "C-MCP: startService requested");
            } catch (Throwable fallback) {
                throw new IOException("MCP 服务启动失败：" + fallback.getMessage());
            }
        }
    }

    private static void stopMcp(Activity activity) throws IOException {
        try {
            Class<?> service = Class.forName("com.jdkshen.aijspro.mcp.McpService");
            Object companion = service.getField("Companion").get(null);
            companion.getClass().getMethod("stop", Context.class).invoke(companion, activity);
            Log.i(TAG, "C-MCP: stop requested");
        } catch (Throwable error) {
            try {
                Intent intent = new Intent().setClassName(activity, "com.jdkshen.aijspro.mcp.McpService");
                activity.stopService(intent);
                Log.i(TAG, "C-MCP: stopService requested");
            } catch (Throwable fallback) {
                throw new IOException("MCP 服务停止失败：" + fallback.getMessage());
            }
        }
    }

    /** task-103：等待 McpService 真正进入/退出 running（避免 startService 后立刻读到旧状态）。 */
    private static boolean waitForMcpRunning(boolean expected, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            boolean running = mcpRunning();
            if (running == expected) {
                return true;
            }
            if (expected && !running) {
                String error = mcpLastError();
                if (!error.isEmpty()) {
                    return false;
                }
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return mcpRunning() == expected;
    }

    // ---- 插件 -------------------------------------------------------------

    private static Set<String> disabledPlugins(Activity activity) {
        SharedPreferences prefs = activity.getSharedPreferences(PREFS_GLASS, Context.MODE_PRIVATE);
        Set<String> values = prefs.getStringSet(PREF_DISABLED_PLUGINS, null);
        return values == null ? new HashSet<>() : new HashSet<>(values);
    }

    private static Object pluginStates(Activity activity) throws Exception {
        return new JSONObject().put("disabled", new JSONArray(disabledPlugins(activity)));
    }

    private static Object pluginSetEnabled(Activity activity, String packageName, boolean enabled)
            throws Exception {
        if (packageName == null || packageName.trim().isEmpty()) {
            throw new IOException("缺少插件包名");
        }
        Set<String> disabled = disabledPlugins(activity);
        if (enabled) {
            disabled.remove(packageName);
        } else {
            disabled.add(packageName);
        }
        SharedPreferences prefs = activity.getSharedPreferences(PREFS_GLASS, Context.MODE_PRIVATE);
        prefs.edit().putStringSet(PREF_DISABLED_PLUGINS, disabled).apply();
        return new JSONObject().put("enabled", enabled);
    }

    private static Object pluginIcon(Activity activity, String packageName) throws Exception {
        Drawable drawable;
        try {
            drawable = activity.getPackageManager().getApplicationIcon(packageName);
        } catch (Exception error) {
            throw new IOException("读取插件图标失败：" + error.getMessage());
        }
        Bitmap bitmap;
        if (drawable instanceof BitmapDrawable) {
            bitmap = ((BitmapDrawable) drawable).getBitmap();
        } else {
            int size = 96;
            bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
            drawable.setBounds(0, 0, size, size);
            drawable.draw(canvas);
        }
        File dir = new File(activity.getCacheDir(), "plugin-icons");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("无法创建插件图标缓存");
        }
        File target = new File(dir, packageName.replace('.', '_') + ".png");
        try (OutputStream out = new FileOutputStream(target)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        return target.getPath();
    }

    private static void installPlugin(Activity activity, JSONObject args, Callback callback) {
        String path = args.optString("path", "");
        String url = args.optString("url", "");
        if (!path.isEmpty()) {
            MAIN.post(() -> {
                try {
                    File apk = new File(path);
                    startInstaller(activity, apk);
                    PocImportRecords.add(activity, "plugin", apk.getName(), apk.getPath(),
                            "", "", "");
                    callback.reply(true, null);
                } catch (Throwable error) {
                    callback.reply(null, error);
                }
            });
            return;
        }
        if (url.isEmpty()) {
            callback.reply(null, new IOException("缺少安装包路径或下载地址"));
            return;
        }
        new Thread(() -> {
            File downloaded = null;
            try {
                downloaded = download(activity, url);
                File target = downloaded;
                MAIN.post(() -> {
                    try {
                        startInstaller(activity, target);
                        PocImportRecords.add(activity, "plugin", target.getName(),
                                target.getPath(), url, "", "");
                        callback.reply(true, null);
                    } catch (Throwable error) {
                        callback.reply(null, error);
                    }
                });
            } catch (Throwable error) {
                File cleanup = downloaded;
                MAIN.post(() -> {
                    if (cleanup != null) {
                        cleanup.delete();
                    }
                    callback.reply(null, error);
                });
            }
        }, "poc-install-plugin").start();
    }

    private static void startInstaller(Activity activity, File apk) throws IOException {
        if (!apk.isFile()) {
            throw new IOException("安装包不存在：" + apk.getName());
        }
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(AppFileProvider.getUriForFile(activity, apk),
                        "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(intent);
    }

    private static File download(Activity activity, String url) throws IOException {
        File dir = new File(activity.getCacheDir(), "plugin-downloads");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("无法创建插件下载目录");
        }
        File target = new File(dir, "plugin-" + System.currentTimeMillis() + ".apk");
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(120000);
        connection.setInstanceFollowRedirects(true);
        try (InputStream in = connection.getInputStream();
             OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[16384];
            int count;
            long total = 0;
            while ((count = in.read(buffer)) != -1) {
                total += count;
                if (total > 200L * 1024 * 1024) {
                    throw new IOException("插件包超过 200MB");
                }
                out.write(buffer, 0, count);
            }
        } finally {
            connection.disconnect();
        }
        return target;
    }

    // ---- 导入 -------------------------------------------------------------

    private static void pickFiles(Activity activity, int requestCode, String mode, File target,
                                  boolean multiple, Callback callback) {
        Intent intent = new Intent(multiple ? Intent.ACTION_OPEN_DOCUMENT : Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        if (multiple) {
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        synchronized (PENDING) {
            PENDING.put(requestCode, new PendingImport(activity, target, mode, callback));
        }
        try {
            activity.startActivityForResult(intent, requestCode);
        } catch (Throwable error) {
            synchronized (PENDING) {
                PENDING.remove(requestCode);
            }
            callback.reply(null, error);
        }
    }

    private static void importCatalogResource(Activity activity, JSONObject args, Callback callback) {
        // 内置目录（task-39）：assetPath 指向 assets 里的示例/工程，直接复制进脚本目录；
        // 旧的外部目录服务仍走 file 参数（本机临时文件），行为保持不变。
        String assetPath = args.optString("assetPath", "").trim();
        if (!assetPath.isEmpty()) {
            importCatalogAsset(activity, args, assetPath, callback);
            return;
        }
        String file = args.optString("file", "");
        new Thread(() -> {
            try {
                File source = new File(file);
                if (!source.isFile()) {
                    throw new IOException("资源文件不存在");
                }
                File scripts = new File(Pref.getScriptDirPath());
                if (!scripts.isDirectory() && !scripts.mkdirs()) {
                    throw new IOException("无法创建脚本目录");
                }
                File target = new File(scripts, source.getName());
                copyFile(source, target);
                String name = args.optString("name", "");
                if (name.trim().isEmpty()) {
                    name = target.getName();
                }
                PocImportRecords.add(activity, "resource", name, target.getPath(),
                        args.optString("file", ""), args.optString("id", ""), "");
                JSONObject value = new JSONObject().put("path", target.getPath());
                MAIN.post(() -> callback.reply(value, null));
            } catch (Throwable error) {
                MAIN.post(() -> callback.reply(null, error));
            }
        }, "poc-import-catalog").start();
    }

    /**
     * 内置目录「导入」：把 assets 下的示例/工程目录（含伴随资源）复制到脚本目录。
     *
     * <p>assetPath 可以是脚本文件（{@code sample/.../x.js}，导入它所在目录，保证相对路径的
     * 伴随图片/子模块都在）或工程目录（含 project.json，整目录导入）。目录名规则与
     * {@link SampleFiles#importForEdit} 一致：工程型用工程目录名，普通脚本用脚本名；
     * 重名时自动加「 (2)」后缀且不覆盖已有目录。
     */
    private static void importCatalogAsset(Activity activity, JSONObject args, String assetPath,
                                           Callback callback) {
        new Thread(() -> {
            try {
                if (!assetPath.startsWith(SampleFiles.ASSET_ROOT + "/")) {
                    throw new IOException("assetPath 必须以 " + SampleFiles.ASSET_ROOT + "/ 开头");
                }
                File scripts = new File(Pref.getScriptDirPath());
                if (!scripts.isDirectory() && !scripts.mkdirs()) {
                    throw new IOException("无法创建脚本目录");
                }
                boolean directory = isAssetDirectory(activity, assetPath);
                String folder;
                if (directory) {
                    folder = SampleFiles.nameOf(assetPath);
                } else {
                    String parent = SampleFiles.parentOf(assetPath);
                    folder = containsAssetProject(activity, parent)
                            ? SampleFiles.nameOf(parent) : SampleFiles.baseNameOf(assetPath);
                }
                if (folder == null || folder.trim().isEmpty()) {
                    folder = "导入资源";
                }
                File target = uniqueDir(scripts, folder);
                if (directory) {
                    SampleFiles.copyAssetTree(activity, assetPath, target, false,
                            SampleFiles.MAX_SUBDIR_BYTES);
                } else {
                    SampleFiles.copyAssetTree(activity, SampleFiles.parentOf(assetPath), target,
                            false, SampleFiles.MAX_SUBDIR_BYTES);
                }
                File entry = directory ? target : new File(target, SampleFiles.nameOf(assetPath));
                String name = args.optString("name", "").trim();
                if (name.isEmpty()) {
                    name = entry.getName();
                }
                PocImportRecords.add(activity, "resource", name, entry.getPath(),
                        "asset:" + assetPath, args.optString("id", assetPath), "");
                JSONObject value = new JSONObject()
                        .put("path", entry.getPath())
                        .put("assetPath", assetPath)
                        .put("directory", directory);
                MAIN.post(() -> callback.reply(value, null));
            } catch (Throwable error) {
                MAIN.post(() -> callback.reply(null, error));
            }
        }, "poc-import-catalog-asset").start();
    }

    private static boolean isAssetDirectory(Activity activity, String assetPath) {
        try {
            String[] children = activity.getAssets().list(assetPath);
            return children != null && children.length > 0;
        } catch (IOException error) {
            return false;
        }
    }

    private static boolean containsAssetProject(Activity activity, String assetDir) {
        try {
            String[] children = activity.getAssets().list(assetDir);
            if (children != null) {
                for (String child : children) {
                    if ("project.json".equals(child)) {
                        return true;
                    }
                }
            }
        } catch (IOException ignored) {
            // 列不出目录就当不是工程型，退回脚本名导入。
        }
        return false;
    }

    /** 脚本目录下不重名的目录：已存在时依次尝试「name (2)」「name (3)」…。 */
    private static File uniqueDir(File parent, String name) {
        File target = new File(parent, name);
        int index = 2;
        while (target.exists() && index < 1000) {
            target = new File(parent, name + " (" + index + ")");
            index++;
        }
        return target;
    }

    private static File copyUri(Activity activity, Uri uri, File targetDir, String nameOverride)
            throws IOException {
        String name = nameOverride;
        if (name == null || name.isEmpty()) {
            name = queryName(activity, uri);
        }
        if (name == null || name.isEmpty()) {
            name = "import-" + System.currentTimeMillis();
        }
        File target = new File(targetDir, name);
        int index = 1;
        while (target.exists()) {
            target = new File(targetDir, index++ + "_" + name);
        }
        try (InputStream in = activity.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(target)) {
            if (in == null) {
                throw new IOException("无法读取所选文件");
            }
            byte[] buffer = new byte[16384];
            int count;
            long total = 0;
            while ((count = in.read(buffer)) != -1) {
                total += count;
                if (total > 512L * 1024 * 1024) {
                    throw new IOException("文件超过 512MB");
                }
                out.write(buffer, 0, count);
            }
        }
        return target;
    }

    private static String queryName(Activity activity, Uri uri) {
        try (android.database.Cursor cursor = activity.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    return cursor.getString(index);
                }
            }
        } catch (Throwable ignored) {
        }
        String path = uri.getPath();
        return path == null ? null : new File(path).getName();
    }

    private static void copyFile(File source, File target) throws IOException {
        try (InputStream in = new java.io.FileInputStream(source);
             OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[16384];
            int count;
            while ((count = in.read(buffer)) != -1) {
                out.write(buffer, 0, count);
            }
        }
    }

    private static File cacheDir(Activity activity) {
        File dir = new File(activity.getCacheDir(), "imports");
        if (!dir.isDirectory()) {
            dir.mkdirs();
        }
        return dir;
    }

    // ---- 示例 / 快捷方式 ---------------------------------------------------

    private static Object resetSample(Activity activity, String path) throws Exception {
        File samples = SampleFiles.sampleRoot(activity).getCanonicalFile();
        File disk = new File(path).getCanonicalFile();
        if (!disk.getPath().startsWith(samples.getPath() + File.separator)
                && !disk.equals(samples)) {
            throw new IOException("只能重置内置示例");
        }
        String relative = disk.getPath().substring(samples.getPath().length()).replace(File.separatorChar, '/');
        String assetPath = SampleFiles.ASSET_ROOT + relative;
        // 历史下载副本（task-129 已彻底禁用下载）只有磁盘没有内置 asset：先删再拷会直接丢示例，必须先拦住。
        if (!assetExists(activity, assetPath)) {
            throw new IOException("安装包里没有这个示例，无法重置；"
                    + "如果是旧版本留下的示例副本，删除后重新安装最新 APK 即可恢复");
        }
        if (disk.isDirectory()) {
            deleteTree(disk);
            SampleFiles.copyAssetTree(activity, assetPath, disk, true, SampleFiles.MAX_SUBDIR_BYTES);
        } else {
            disk.delete();
            SampleFiles.copyAssetTree(activity, assetPath, disk, true, 0);
        }
        return new JSONObject().put("path", disk.getPath());
    }

    private static Object copyExampleTree(Activity activity, String path) throws Exception {
        File samples = SampleFiles.sampleRoot(activity).getCanonicalFile();
        File source = new File(path).getCanonicalFile();
        if (!source.getPath().startsWith(samples.getPath() + File.separator)) {
            throw new IOException("只能复制内置示例");
        }
        String relative = source.getPath().substring(samples.getPath().length()).replace(File.separatorChar, '/');
        String assetPath = SampleFiles.ASSET_ROOT + relative;
        boolean bundled = assetExists(activity, assetPath);
        if (source.isDirectory()) {
            File target = new File(Pref.getScriptDirPath(), source.getName());
            if (bundled) {
                SampleFiles.copyAssetTree(activity, assetPath, target, false,
                        SampleFiles.MAX_SUBDIR_BYTES);
            } else {
                copyDiskSampleTree(source, target, SampleFiles.MAX_SUBDIR_BYTES);
            }
            return new JSONObject().put("path", target.getPath());
        }
        File imported = bundled
                ? SampleFiles.importForEdit(activity, assetPath)
                : importDiskSample(activity, source);
        if (imported == null) {
            throw new IOException("导入示例失败");
        }
        return new JSONObject().put("path", imported.getPath());
    }

    /** 示例页（只读）的查看/运行/打包/导入/重置全部示例；path 是 files/sample 下的磁盘路径。 */
    private static Object sampleAction(Activity activity, JSONObject args) throws Exception {
        String action = args.optString("action", "");
        String path = args.optString("path", "");
        File samples = SampleFiles.sampleRoot(activity).getCanonicalFile();
        File disk = path.isEmpty() ? null : new File(path).getCanonicalFile();
        if (disk != null && !disk.getPath().startsWith(samples.getPath() + File.separator)
                && !disk.equals(samples)) {
            throw new IOException("只能操作内置示例");
        }
        switch (action) {
            case "view": {
                File file = materializeSample(activity, samples, disk);
                String assetPath = sampleAssetPath(samples, disk);
                // 下载版示例在安装包里没有对应 asset：不标记成内置示例，编辑器里就不会出现
                // 「重置示例」（那条路要从 assets 读，下载版没有）。
                if (!assetExists(activity, assetPath)) {
                    assetPath = null;
                }
                Intent intent = com.jdkshen.aijspro.ui.editor.ProCodeEditorActivity.sampleIntent(
                        activity, file, assetPath);
                activity.startActivity(intent);
                return Boolean.TRUE;
            }
            case "run": {
                File file = materializeSample(activity, samples, disk);
                return PocRuntime.execute(activity, "run",
                        new JSONObject().put("path", file.getPath()));
            }
            case "build": {
                File target = disk != null && disk.isDirectory()
                        ? disk : materializeSample(activity, samples, disk);
                return openBuildPage(activity, target.getPath());
            }
            case "buildProject": {
                if (disk == null || !disk.isDirectory()
                        || !new File(disk, "project.json").isFile()) {
                    throw new IOException("这个示例不是可打包的项目");
                }
                return openBuildPage(activity, disk.getPath());
            }
            case "import":
                return copyExampleTree(activity, disk.getPath());
            case "resetAll": {
                SampleFiles.copyAssetTree(activity, SampleFiles.ASSET_ROOT, samples, true,
                        SampleFiles.MAX_SUBDIR_BYTES);
                return Boolean.TRUE;
            }
            default:
                throw new IOException("不支持的示例操作：" + action);
        }
    }

    /** 示例文件若还没落盘，从 assets 补齐同目录伴生文件；目录直接返回。 */
    private static File materializeSample(Activity activity, File samples, File disk)
            throws Exception {
        if (disk == null || !disk.exists()) {
            throw new IOException("示例不存在");
        }
        if (disk.isDirectory()) {
            return disk;
        }
        String assetPath = sampleAssetPath(samples, disk);
        if (disk.isFile() && !assetExists(activity, assetPath)) {
            // 历史下载副本（task-129 已彻底禁用下载）：磁盘上就是完整一套，运行/查看直接用磁盘路径。
            return disk;
        }
        File file = SampleFiles.materialize(activity, assetPath);
        if (file == null) {
            throw new IOException("示例还没落盘，请重新打开一次示例");
        }
        return file;
    }

    private static String sampleAssetPath(File samples, File disk) throws IOException {
        String relative = disk.getPath().substring(samples.getPath().length())
                .replace(File.separatorChar, '/');
        return SampleFiles.ASSET_ROOT + relative;
    }

    /** 示例文件是否还在 APK 内置 assets 里（历史下载副本只有磁盘、没有 asset）。 */
    private static boolean assetExists(Activity activity, String assetPath) {
        try (InputStream in = activity.getAssets().open(assetPath)) {
            return true;
        } catch (IOException notAFile) {
            try {
                String[] children = activity.getAssets().list(assetPath);
                return children != null && children.length > 0;
            } catch (IOException error) {
                return false;
            }
        }
    }

    /**
     * 下载版示例的「导入到我的脚本」：直接从 files/sample 磁盘树复制（规则与内置示例的
     * {@code SampleFiles.importForEdit} 一致：目录里有 project.json 时用目录名，否则用文件名）。
     */
    private static File importDiskSample(Activity activity, File source) throws IOException {
        File scriptDir = new File(Pref.getScriptDirPath());
        if (!scriptDir.isDirectory() && !scriptDir.mkdirs()) {
            throw new IOException("无法创建脚本目录");
        }
        File parent = source.getParentFile();
        if (parent == null) {
            throw new IOException("示例路径无效");
        }
        String folder = new File(parent, "project.json").isFile()
                ? parent.getName() : baseNameOf(source.getName());
        File target = new File(scriptDir, folder);
        copyDiskSampleTree(parent, target, SampleFiles.MAX_SUBDIR_BYTES);
        return new File(target, source.getName());
    }

    /** 单个文件名去掉 .js 后缀（与 SampleFiles.baseNameOf 同规则）。 */
    private static String baseNameOf(String name) {
        String value = name == null ? "" : name;
        return value.toLowerCase(Locale.ROOT).endsWith(".js")
                ? value.substring(0, value.length() - 3) : value;
    }

    /** 磁盘示例树复制：与 SampleFiles.copyAssetTree 的语义一致（不覆盖、跳过超大子目录）。 */
    private static void copyDiskSampleTree(File source, File target, long maxSubdirBytes)
            throws IOException {
        if (!source.isDirectory()) {
            copyDiskFile(source, target);
            return;
        }
        if (!target.isDirectory() && !target.mkdirs() && !target.isDirectory()) {
            throw new IOException("无法创建目录 " + target);
        }
        File[] children = source.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            File next = new File(target, child.getName());
            if (child.isDirectory()) {
                if (maxSubdirBytes > 0 && diskTreeBytes(child) > maxSubdirBytes) {
                    Log.w(TAG, "跳过过大的示例子目录（" + (maxSubdirBytes / 1048576) + "MB）：" + child);
                    continue;
                }
                copyDiskSampleTree(child, next, maxSubdirBytes);
            } else {
                copyDiskFile(child, next);
            }
        }
    }

    private static void copyDiskFile(File source, File target) throws IOException {
        if (target.isFile()) {
            return;
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("无法创建目录 " + parent);
        }
        try (InputStream in = new FileInputStream(source);
             OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = in.read(buffer)) != -1) {
                out.write(buffer, 0, count);
            }
        }
    }

    private static long diskTreeBytes(File file) {
        if (file.isFile()) {
            return file.length();
        }
        long total = 0;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                total += diskTreeBytes(child);
                if (total > 64L * 1024 * 1024) {
                    return total;
                }
            }
        }
        return total;
    }

    private static Object shortcut(Activity activity, String path) throws Exception {
        File file = new File(path).getCanonicalFile();
        if (!file.isFile()) {
            throw new IOException("脚本不存在：" + file.getName());
        }
        Intent intent = new Intent().setClassName(activity,
                        "com.jdkshen.aijspro.ui.shortcut.ShortcutCreateActivity")
                .putExtra("file", new ScriptFile(file.getPath()));
        activity.startActivity(intent);
        return Boolean.TRUE;
    }

    /**
     * 打包/签名统一交给原生打包页（BuildActivity → MiuixBuildActivity）：
     * 单文件传脚本路径，工程传目录路径；页面内已有签名模式（自动/已有密钥库/新建）与进度。
     */
    private static Object openBuildPage(Activity activity, String path) throws Exception {
        File source = new File(path).getCanonicalFile();
        if (!source.exists()) {
            throw new IOException("脚本或工程不存在：" + source.getName());
        }
        Intent intent = new Intent()
                .setClassName(activity, "com.jdkshen.aijspro.ui.project.BuildActivity")
                .putExtra("com.jdkshen.aijspro.ui.project.BuildActivity.extra_source_file",
                        source.getPath());
        activity.startActivity(intent);
        return Boolean.TRUE;
    }

    /** 后台（悬浮控制条等）留下的待处理请求；没有时返回空对象。 */
    private static Object pendingFromPrefs(Activity activity, String key) {
        SharedPreferences prefs = activity.getSharedPreferences(PREFS_GLASS, Context.MODE_PRIVATE);
        String raw = prefs.getString(key, null);
        if (raw == null || raw.isEmpty()) {
            return new JSONObject();
        }
        prefs.edit().remove(key).apply();
        try {
            return new JSONObject(raw);
        } catch (Exception error) {
            return new JSONObject();
        }
    }

    private static void deleteTree(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteTree(child);
                }
            }
        }
        file.delete();
    }
}
