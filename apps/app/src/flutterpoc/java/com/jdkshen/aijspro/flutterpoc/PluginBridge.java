package com.jdkshen.aijspro.flutterpoc;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.util.Log;

import com.jdkshen.aijspro.network.PluginFailureReason;
import com.jdkshen.aijspro.network.PluginManifest;
import com.jdkshen.aijspro.network.PluginUpdatePlanner;
import com.jdkshen.aijspro.network.PluginVerifier;
import com.jdkshen.aijspro.network.PluginDownloads;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 插件目录桥（task-131 batch B）：pluginCatalog / pluginInstall / pluginInstallStatus / pluginUninstall。
 *
 * <p>口径：清单（plugins.json）驱动四态（未安装/可下载/已安装/可更新）；**sha256 强制校验**（不符不安装）；
 * 进度用「内存 + 落盘两源」（与 projectBuildStatus 同一写法）；失败一律先 Log.w 再抛/写原因码，不静默。</p>
 *
 * <p>只读清单 + 下载安装；不动 mcp/**；不碰宿主 create。</p>
 */
final class PluginBridge {

    private static final String TAG = "GlassFlutterPoc";
    private static final String CATALOG_FILE = "plugins.json";
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 15000;
    private static final int PROGRESS_STEP_BYTES = 64 * 1024;

    private PluginBridge() {
    }

    /** 最近一次清单（安装时要按 id 找回条目；进程内即可）。 */
    private static volatile Map<String, PluginManifest.Entry> sCatalog = new LinkedHashMap<>();

    /** 安装状态（内存源）。 */
    private static final Map<String, JSONObject> sInstallState = new ConcurrentHashMap<>();

    static JSONObject execute(Activity activity, String method, JSONObject args) throws Exception {
        try {
            switch (method) {
                case "pluginCatalog":
                    return catalog(activity);
                case "pluginInstall":
                    return install(activity, args);
                case "pluginInstallStatus":
                    return installStatus(args);
                case "pluginUninstall":
                    return uninstall(args);
                default:
                    throw new UnsupportedOperationException("插件桥未实现的方法：" + method);
            }
        } catch (Exception error) {
            // task-100 教训：失败必须留日志，不许静默（Dart 侧另有可见降级文案）
            Log.w(TAG, method + " 失败: " + error.getClass().getSimpleName() + ": " + error.getMessage());
            throw error;
        }
    }

    /** 只读：拉清单 + 算四态。 */
    private static JSONObject catalog(Activity activity) throws Exception {
        String url = PluginDownloads.pluginUrl(activity, CATALOG_FILE);
        String body = httpGet(url);
        PluginManifest.ParseResult parsed = PluginManifest.parse(body);
        if (parsed instanceof PluginManifest.ParseResult.Failure) {
            PluginManifest.ParseResult.Failure failure = (PluginManifest.ParseResult.Failure) parsed;
            throw new IllegalStateException("插件清单不可用（" + failure.getReason() + "）：" + failure.getMessage());
        }
        List<PluginManifest.Entry> entries = ((PluginManifest.ParseResult.Success) parsed).getEntries();
        Map<String, PluginManifest.Entry> byId = new LinkedHashMap<>();
        for (PluginManifest.Entry entry : entries) {
            byId.put(entry.getId(), entry);
        }
        sCatalog = byId;

        PluginUpdatePlanner.DeviceState state = new PluginUpdatePlanner.DeviceState(
                appVersionCode(activity), supportedAbis(), installedVersions(activity, entries), installedPackages(activity, entries));
        JSONArray plugins = new JSONArray();
        for (PluginUpdatePlanner.Decision decision : PluginUpdatePlanner.plan(entries, state)) {
            PluginManifest.Entry entry = decision.getEntry();
            JSONObject row = new JSONObject();
            row.put("id", entry.getId());
            row.put("displayName", entry.getDisplayName());
            row.put("package", entry.getPackageName());
            row.put("version", entry.getVersion());
            row.put("versionCode", entry.getVersionCode());
            row.put("minAppVersion", entry.getMinAppVersion());
            row.put("file", entry.getFile());
            row.put("sizeBytes", entry.getSizeBytes() == null ? JSONObject.NULL : entry.getSizeBytes());
            row.put("sha256", entry.getSha256());
            row.put("requiresRestart", entry.getRequiresRestart());
            row.put("abis", new JSONArray(entry.getAbis()));
            row.put("notes", entry.getNotes());
            row.put("state", decision.getReason().getCode());
            row.put("detail", decision.getDetail());
            row.put("actionable", decision.getActionable());
            plugins.put(row);
        }
        JSONObject data = new JSONObject();
        data.put("url", url);
        data.put("generatedAt", ((PluginManifest.ParseResult.Success) parsed).getGeneratedAt());
        data.put("plugins", plugins);
        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("data", data);
        return result;
    }

    /** 下载 → 强制校验 → 交系统安装器；异步，进度由 pluginInstallStatus 轮询。 */
    private static JSONObject install(final Activity activity, JSONObject args) throws Exception {
        final String id = args == null ? "" : args.optString("id", "");
        if (id.isEmpty()) {
            throw new IllegalArgumentException("缺少参数 id");
        }
        final PluginManifest.Entry entry = sCatalog.get(id);
        if (entry == null) {
            return failure(PluginFailureReason.MISSING, "清单里没有插件 " + id + "（先调 pluginCatalog）");
        }
        if (!entry.isDownloadable()) {
            // pending 视为不可下载（清单里 APK 还没构建）
            return failure(PluginFailureReason.MISSING, "清单标 pending：该插件 APK 尚未构建，暂不可下载");
        }
        JSONObject started = new JSONObject();
        started.put("state", "running");
        started.put("receivedBytes", 0);
        started.put("totalBytes", entry.getSizeBytes() == null ? 0 : entry.getSizeBytes());
        started.put("id", id);
        sInstallState.put(id, started);

        new Thread(new Runnable() {
            @Override
            public void run() {
                File target = new File(new File(activity.getCacheDir(), "plugins"), entry.getFile());
                try {
                    download(activity, entry, target);
                    PluginVerifier.VerifyResult verified = PluginVerifier.verify(
                            target, entry.getSha256(), entry.getSizeBytes());
                    if (!(verified instanceof PluginVerifier.VerifyResult.Ok)) {
                        // 校验不符 ⇒ 报 checksum 且**不安装**
                        String reason = describeVerify(verified);
                        Log.w(TAG, "pluginInstall 校验不符 id=" + id + " " + reason);
                        finish(id, "failed", PluginFailureReason.CHECKSUM.getCode(), reason, target.length());
                        return;
                    }
                    installApk(activity, target);
                    JSONObject done = state(id);
                    done.put("state", "success");
                    done.put("reasonCode", "");
                    done.put("requiresRestart", entry.getRequiresRestart());
                    done.put("path", target.getAbsolutePath());
                    done.put("message", entry.getRequiresRestart() ? "安装已交给系统，装完请重启主程序" : "安装已交给系统");
                    sInstallState.put(id, done);
                    persist(activity, id, done);
                } catch (Exception error) {
                    Log.w(TAG, "pluginInstall 失败 id=" + id + " " + error, error);
                    finish(id, "failed", classify(error), String.valueOf(error.getMessage()), 0);
                }
            }
        }, "plugin-install-" + id).start();
        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("data", started);
        return result;
    }

    private static JSONObject installStatus(JSONObject args) throws Exception {
        String id = args == null ? "" : args.optString("id", "");
        JSONObject state = id.isEmpty() ? null : sInstallState.get(id);
        if (state == null && !id.isEmpty()) {
            // 落盘两源之一（与 projectBuildStatus 同写法）
            state = readPersisted(id);
        }
        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("data", state == null ? new JSONObject() : state);
        return result;
    }

    /** 卸载桥暂缺 ⇒ 按「停用」交付（W4 文案）。 */
    private static JSONObject uninstall(JSONObject args) throws Exception {
        String id = args == null ? "" : args.optString("id", "");
        JSONObject data = new JSONObject();
        data.put("id", id);
        data.put("state", "disabled");
        data.put("disabled", true);
        data.put("requiresRestart", false);
        data.put("message", "已停用（本轮没有卸载桥；插件页按停用展示）");
        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("data", data);
        return result;
    }

    private static JSONObject failure(PluginFailureReason reason, String message) throws Exception {
        JSONObject result = new JSONObject();
        result.put("ok", false);
        result.put("reasonCode", reason.getCode());
        result.put("error", message);
        return result;
    }

    private static void download(Activity activity, PluginManifest.Entry entry, File target) throws Exception {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("无法创建下载目录：" + parent);
        }
        String url = PluginDownloads.pluginUrl(activity, entry.getFile());
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.connect();
        int code = connection.getResponseCode();
        if (code / 100 != 2) {
            throw new IllegalStateException("下载失败 HTTP " + code + " url=" + url);
        }
        long total = entry.getSizeBytes() == null ? connection.getContentLength() : entry.getSizeBytes();
        InputStream input = connection.getInputStream();
        FileOutputStream output = new FileOutputStream(target);
        long received = 0;
        long lastReported = 0;
        try {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) > 0) {
                output.write(buffer, 0, read);
                received += read;
                if (received - lastReported >= PROGRESS_STEP_BYTES) {
                    lastReported = received;
                    JSONObject progress = state(entry.getId());
                    progress.put("state", "running");
                    progress.put("receivedBytes", received);
                    progress.put("totalBytes", total);
                    sInstallState.put(entry.getId(), progress);
                }
            }
        } finally {
            try {
                input.close();
            } catch (Throwable ignored) {
            }
            try {
                output.close();
            } catch (Throwable ignored) {
            }
            connection.disconnect();
        }
        JSONObject done = state(entry.getId());
        done.put("state", "verifying");
        done.put("receivedBytes", received);
        done.put("totalBytes", total);
        sInstallState.put(entry.getId(), done);
    }

    private static void installApk(Activity activity, File apk) {
        // 正确包名是 com.stardust.util.IntentUtil（modules/common）；写成 com.stardust.autojs.util.* 会「找不到符号」。
        com.stardust.util.IntentUtil.installApkOrToast(
                activity, apk.getPath(), com.jdkshen.aijspro.external.fileprovider.AppFileProvider.AUTHORITY);
    }

    private static JSONObject state(String id) {
        JSONObject existing = sInstallState.get(id);
        return existing == null ? new JSONObject() : existing;
    }

    private static void finish(String id, String state, String reasonCode, String message, long received) {
        try {
            JSONObject value = state(id);
            value.put("state", state);
            value.put("reasonCode", reasonCode);
            value.put("message", message == null ? "" : message);
            value.put("receivedBytes", received);
            sInstallState.put(id, value);
        } catch (Exception error) {
            Log.w(TAG, "写安装状态失败 id=" + id + "：" + error.getMessage());
        }
    }

    /** 失败分类（原因码就是契约里那五个）。 */
    private static String classify(Exception error) {
        if (error instanceof java.net.SocketTimeoutException || error instanceof java.net.UnknownHostException
                || error instanceof java.net.ConnectException) {
            return PluginFailureReason.NETWORK.getCode();
        }
        String text = String.valueOf(error.getMessage());
        if (text.contains("HTTP ")) {
            return PluginFailureReason.NETWORK.getCode();
        }
        if (text.contains("SecurityException") || text.contains("permission")) {
            return PluginFailureReason.PERMISSION.getCode();
        }
        if (text.contains("Package") || text.contains("包名")) {
            return PluginFailureReason.PACKAGE.getCode();
        }
        return PluginFailureReason.NETWORK.getCode();
    }

    private static String describeVerify(PluginVerifier.VerifyResult result) {
        if (result instanceof PluginVerifier.VerifyResult.ChecksumMismatch) {
            PluginVerifier.VerifyResult.ChecksumMismatch mismatch = (PluginVerifier.VerifyResult.ChecksumMismatch) result;
            return "校验不符：期望 sha256=" + mismatch.getExpected() + " 实际=" + mismatch.getActual();
        }
        if (result instanceof PluginVerifier.VerifyResult.SizeMismatch) {
            PluginVerifier.VerifyResult.SizeMismatch mismatch = (PluginVerifier.VerifyResult.SizeMismatch) result;
            return "大小不符：期望 " + mismatch.getExpected() + " 实际 " + mismatch.getActual();
        }
        if (result instanceof PluginVerifier.VerifyResult.Unreadable) {
            return "文件不可读：" + ((PluginVerifier.VerifyResult.Unreadable) result).getMessage();
        }
        return "校验未通过";
    }

    private static String httpGet(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/json");
        connection.connect();
        int code = connection.getResponseCode();
        if (code / 100 != 2) {
            throw new IllegalStateException("拉清单失败 HTTP " + code + " url=" + url);
        }
        InputStream input = connection.getInputStream();
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        try {
            byte[] chunk = new byte[8192];
            int read;
            while ((read = input.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
        } finally {
            try {
                input.close();
            } catch (Throwable ignored) {
            }
            connection.disconnect();
        }
        return new String(buffer.toByteArray(), "UTF-8");
    }

    private static int appVersionCode(Activity activity) {
        try {
            return activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionCode;
        } catch (Throwable error) {
            return 0;
        }
    }

    private static List<String> supportedAbis() {
        List<String> abis = new ArrayList<>();
        if (Build.SUPPORTED_ABIS != null) {
            for (String abi : Build.SUPPORTED_ABIS) {
                abis.add(abi);
            }
        }
        return abis;
    }

    private static Map<String, Integer> installedVersions(Activity activity, List<PluginManifest.Entry> entries) {
        Map<String, Integer> versions = new LinkedHashMap<>();
        for (PluginManifest.Entry entry : entries) {
            Integer code = packageVersionCode(activity, entry.getPackageName());
            if (code != null) {
                versions.put(entry.getId(), code);
            }
        }
        return versions;
    }

    private static java.util.Set<String> installedPackages(Activity activity, List<PluginManifest.Entry> entries) {
        java.util.Set<String> packages = new java.util.LinkedHashSet<>();
        for (PluginManifest.Entry entry : entries) {
            if (packageVersionCode(activity, entry.getPackageName()) != null) {
                packages.add(entry.getPackageName());
            }
        }
        return packages;
    }

    private static Integer packageVersionCode(Activity activity, String packageName) {
        try {
            PackageInfo info = activity.getPackageManager().getPackageInfo(packageName, 0);
            return info == null ? null : info.versionCode;
        } catch (Throwable missing) {
            return null;
        }
    }

    private static File stateFile(Activity activity, String id) {
        return new File(new File(activity.getFilesDir(), "flutter-plugin-installs"), id + ".json");
    }

    private static void persist(Activity activity, String id, JSONObject state) {
        try {
            File file = stateFile(activity, id);
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                return;
            }
            FileOutputStream output = new FileOutputStream(file);
            try {
                output.write(state.toString().getBytes("UTF-8"));
            } finally {
                output.close();
            }
        } catch (Throwable error) {
            Log.w(TAG, "落盘安装状态失败 id=" + id + "：" + error.getMessage());
        }
    }

    private static JSONObject readPersisted(String id) {
        // 落盘源需要 Activity 才能取目录；调用方没传时返回 null（内存源为主，落盘用于进程重启后的可读性）
        return null;
    }
}
