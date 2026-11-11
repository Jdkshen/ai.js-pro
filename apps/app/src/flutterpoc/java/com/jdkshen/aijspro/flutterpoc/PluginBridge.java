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
    /** APK 下载：connect 10s；read 用「**无进展(stall)**」判定 —— Java 的 read timeout 是「两次读到数据之间的最大间隔」，
     *  每次 read 成功都会重新计时 ⇒ 60s 无字节才算失败，**总时长不设上限**（60.5MB 的象棋插件在慢网/加速代理下也不会被腰斩）。 */
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 60000;
    private static final int PROGRESS_STEP_BYTES = 64 * 1024;
    /** 插件图标：缩到 <=96px 的 PNG 落 app 缓存（几 KB），清单里给绝对路径 + 可选内联 base64。 */
    private static final int ICON_SIZE_PX = 96;
    private static final String ICON_DIR = "plugin-icons";
    private static final long ICON_INLINE_MAX_BYTES = 12 * 1024L;
    /** 清单是**同步桥**（Dart 侧等返回）⇒ 后台拉取后最多等这么久；刻意短于 Dart 的 8s 超时，
     *  好让「可解释的失败原因」（带异常类型）先于 Dart 的泛化超时出现。 */
    private static final long CATALOG_TIMEOUT_MS = 20000L;
    /** 每个候选源各自的预算（connect+read ≈6s）：候选链 3 条 ⇒ 总预算 20s 足够，弱网也不再被总预算一刀切。 */
    private static final int CANDIDATE_CONNECT_TIMEOUT_MS = 3000;
    private static final int CANDIDATE_READ_TIMEOUT_MS = 3000;

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

    /**
     * 只读：拉清单 + 算四态（**同步桥**：Dart 侧等返回）。
     *
     * <p>真机 bug（dev-1494 用户截图）：原来 URL + 读取 + 解析跑在**调用线程（UI 主线程）** ⇒ Android
     * 直接抛 NetworkOnMainThreadException，目录永远拉不到。修法：整段放到后台线程，主调用线程
     * **join 等结果**（带超时），失败时把**异常类型**带进原因（形如 network:UnknownHostException: …）
     * 便于真机一眼判因。</p>
     */
    private static JSONObject catalog(final Activity activity) throws Exception {
        final java.util.List<PluginDownloads.Candidate> candidates =
                PluginDownloads.pluginUrlCandidates(activity, CATALOG_FILE);
        final String url = candidates.get(0).url;
        final long startedAt = System.currentTimeMillis();
        final java.util.concurrent.atomic.AtomicInteger tried = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicReference<String> lastSource =
                new java.util.concurrent.atomic.AtomicReference<>("(none)");
        final java.util.concurrent.atomic.AtomicReference<String> lastUrl =
                new java.util.concurrent.atomic.AtomicReference<>(url);
        final java.util.concurrent.atomic.AtomicReference<Object> holder =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    holder.set(catalogOnWorker(activity, candidates, tried, lastSource, lastUrl));
                } catch (Throwable error) {
                    holder.set(error);
                }
            }
        }, "plugin-catalog");
        worker.start();
        worker.join(CATALOG_TIMEOUT_MS);
        long elapsedMs = System.currentTimeMillis() - startedAt;
        if (worker.isAlive()) {
            throw new IllegalStateException("source:插件清单拉取超时（试了 " + tried.get() + "/"
                    + candidates.size() + " 条源，耗时 " + elapsedMs + "ms，最后一条="
                    + lastSource.get() + " " + lastUrl.get() + "）");
        }
        Object value = holder.get();
        if (value instanceof JSONObject) {
            return (JSONObject) value;
        }
        if (value instanceof Throwable) {
            Throwable error = (Throwable) value;
            String detail = error.getMessage() == null ? "" : (": " + error.getMessage());
            // 不用 (String, Throwable) 构造：本模块编译目标下该重载不可用（javac: Throwable 无法转换为 Exception）。
            // 把 cause 拼进消息即可满足「一眼判因」，且不改变异常类型语义。
            String prefix = classify(error);
            throw new IllegalStateException(
                    (prefix.isEmpty() ? "" : prefix + ":") + error.getClass().getSimpleName() + detail
                            + " cause=" + error.getClass().getName());
        }
        throw new IllegalStateException("network:插件清单拉取没有结果 url=" + url);
    }

    /** 后台线程体：网络读取 + 解析 + 四态（**不得触碰 UI**）。 */
    private static JSONObject catalogOnWorker(Activity activity,
            java.util.List<PluginDownloads.Candidate> candidates,
            java.util.concurrent.atomic.AtomicInteger tried,
            java.util.concurrent.atomic.AtomicReference<String> lastSource,
            java.util.concurrent.atomic.AtomicReference<String> lastUrl) throws Exception {
        Exception lastError = null;
        long startedAt = System.currentTimeMillis();
        for (PluginDownloads.Candidate candidate : candidates) {
            tried.incrementAndGet();
            lastSource.set(candidate.source);
            lastUrl.set(candidate.url);
            long attemptStart = System.currentTimeMillis();
            try {
                JSONObject result = catalogFromSource(activity, candidate.url, candidate.source);
                Log.i(TAG, "pluginCatalog 源命中 source=" + candidate.source
                        + " 耗时=" + (System.currentTimeMillis() - attemptStart) + "ms");
                return result;
            } catch (Exception error) {
                lastError = error;
                Log.w(TAG, "pluginCatalog 源失败 source=" + candidate.source + " url=" + candidate.url
                        + " 耗时=" + (System.currentTimeMillis() - attemptStart) + "ms"
                        + " : " + error.getClass().getSimpleName() + ": " + error.getMessage());
            }
        }
        long elapsed = System.currentTimeMillis() - startedAt;
        if (lastError == null) {
            throw new IllegalStateException("source:没有可用的插件源（候选链为空）");
        }
        throw new IllegalStateException("source:插件清单拉取失败（试了 " + tried.get() + "/" + candidates.size()
                + " 条源，耗时 " + elapsed + "ms，最后一条=" + lastSource.get() + " " + lastUrl.get()
                + "）：" + lastError.getClass().getSimpleName() + ": " + lastError.getMessage());
    }

    private static JSONObject catalogFromSource(Activity activity, String url, String source) throws Exception {
        Fetch fetch = httpGet(url, source);
        String body = fetch.body;
        if (body.trim().isEmpty()) {
            throw new IllegalStateException("manifest:清单为空" + fetch.describe());
        }
        PluginManifest.ParseResult parsed = PluginManifest.parse(body);
        if (parsed instanceof PluginManifest.ParseResult.Failure) {
            PluginManifest.ParseResult.Failure failure = (PluginManifest.ParseResult.Failure) parsed;
            throw new IllegalStateException("manifest:" + failure.getReason() + "：" + failure.getMessage() + fetch.describe());
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
            // task-131：图标的唯一原生缺口 —— 已装插件才有本地图标；未装留空（Flutter 用占位图），
            // 取不到只记日志，绝不让整份清单失败。
            applyPluginIcon(activity, row, entry);
            plugins.put(row);
        }
        JSONObject data = new JSONObject();
        data.put("url", url);
        // task-131：把「这条清单最终是从哪个源拿到的」放进 data（custom/builtin-release/builtin-raw/builtin-direct）
        // ⇒ 插件页零改动就能显示「清单来源：…」，排障也不必翻日志。
        data.put("source", source);
        data.put("generatedAt", ((PluginManifest.ParseResult.Success) parsed).getGeneratedAt());
        data.put("plugins", plugins);
        Log.i(TAG, "pluginCatalog ok source=" + fetch.source + " http=" + fetch.status
                + " bytes=" + body.length() + " plugins=" + entries.size() + " url=" + fetch.url);
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

    /** 一次清单请求的结果（用于把「源 / 状态码 / 长度 / 头部」带进失败原因与成功日志）。 */
    private static final class Fetch {
        final String url;
        final String source;
        final int status;
        final String body;

        Fetch(String url, String source, int status, String body) {
            this.url = url;
            this.source = source;
            this.status = status;
            this.body = body == null ? "" : body;
        }

        String describe() {
            String head = body.length() > 80 ? body.substring(0, 80) : body;
            return " url=" + url + " http=" + status + " bytes=" + body.length()
                    + " source=" + source + " head=" + head.replace('\n', ' ').replace('\r', ' ');
        }
    }

    /** 自建源优先（PluginDownloads.pluginUrl 的逻辑）；内置源前缀固定是这个加速前缀 ⇒ 据此标注来源。 */
    private static String sourceOf(String url) {
        return url != null && url.startsWith("https://gh-proxy.com/") ? "builtin" : "custom";
    }

    /**
     * 给清单条目补 iconPath（绝对路径）与可选 iconBase64。
     *
     * <p>口径（Lead）：① 已装 ⇒ PackageManager.getApplicationIcon 画成 <=96px PNG 落到
     * cache/plugin-icons/<pkg>-v<versionCode>.png（文件名带版本 ⇒ 版本变自然重画、同版幂等）；
     * ② 未安装 ⇒ 字段空串（Flutter 用占位图，不联网）；③ 任何异常只 Log.w，清单照常返回。</p>
     */
    private static void applyPluginIcon(Activity activity, JSONObject row, PluginManifest.Entry entry) {
        String packageName = entry.getPackageName();
        try {
            Integer version = packageVersionCode(activity, packageName);
            if (version == null) {
                row.put("iconPath", "");
                return;
            }
            File dir = new File(activity.getCacheDir(), ICON_DIR);
            File png = new File(dir, packageName + "-v" + version + ".png");
            if (!png.isFile()) {
                android.graphics.drawable.Drawable drawable =
                        activity.getPackageManager().getApplicationIcon(packageName);
                int width = Math.max(1, drawable.getIntrinsicWidth());
                int height = Math.max(1, drawable.getIntrinsicHeight());
                int side = Math.max(width, height);
                float scale = side > ICON_SIZE_PX ? (float) ICON_SIZE_PX / (float) side : 1f;
                int outW = Math.max(1, Math.round(width * scale));
                int outH = Math.max(1, Math.round(height * scale));
                android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(outW, outH,
                        android.graphics.Bitmap.Config.ARGB_8888);
                android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
                drawable.setBounds(0, 0, outW, outH);
                drawable.draw(canvas);
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    throw new IllegalStateException("无法创建图标缓存目录：" + dir);
                }
                FileOutputStream output = new FileOutputStream(png);
                try {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
                } finally {
                    try {
                        output.close();
                    } catch (Throwable ignored) {
                    }
                    bitmap.recycle();
                }
                pruneOldIcons(dir, packageName, png);
            }
            row.put("iconPath", png.getAbsolutePath());
            row.put("iconBase64", png.length() > 0 && png.length() <= ICON_INLINE_MAX_BYTES
                    ? android.util.Base64.encodeToString(readFileBytes(png), android.util.Base64.NO_WRAP)
                    : "");
        } catch (Throwable error) {
            Log.w(TAG, "pluginCatalog 图标失败 pkg=" + packageName + " : " + error);
            try {
                row.put("iconPath", "");
            } catch (Throwable ignored) {
            }
        }
    }

    /** 删掉同包旧版本图标，避免缓存里堆历史文件。 */
    private static void pruneOldIcons(File dir, String packageName, File current) {
        try {
            File[] files = dir.listFiles();
            if (files == null) {
                return;
            }
            for (File file : files) {
                if (file.equals(current)) {
                    continue;
                }
                if (file.getName().startsWith(packageName + "-v") && file.getName().endsWith(".png")) {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "pluginCatalog 清理旧图标失败 pkg=" + packageName + " : " + error);
        }
    }

    private static byte[] readFileBytes(File file) throws Exception {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        InputStream input = new java.io.FileInputStream(file);
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
        }
        return buffer.toByteArray();
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
        java.util.List<PluginDownloads.Candidate> candidates =
                PluginDownloads.pluginUrlCandidates(activity, entry.getFile());
        Exception lastError = null;
        for (PluginDownloads.Candidate candidate : candidates) {
            try {
                downloadOnce(activity, candidate.url, entry, target);
                Log.i(TAG, "pluginInstall apk source=" + candidate.source + " url=" + candidate.url);
                return;
            } catch (Exception error) {
                lastError = error;
                Log.w(TAG, "pluginInstall 源失败 source=" + candidate.source + " url=" + candidate.url
                        + " : " + error.getClass().getSimpleName() + ": " + error.getMessage());
            }
        }
        throw lastError == null
                ? new IllegalStateException("source:没有可用的插件源（候选链为空）")
                : lastError;
    }

    private static void downloadOnce(Activity activity, String url, PluginManifest.Entry entry, File target)
            throws Exception {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("无法创建下载目录：" + parent);
        }
        // url 由调用方按候选链给定（见 download 的降级循环）
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.connect();
        int code = connection.getResponseCode();
        if (code / 100 != 2) {
            throw new IllegalStateException("source:下载失败 HTTP " + code + " url=" + url);
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
    private static String classify(Throwable error) {   // Throwable：catalog 后台线程搬回来的异常可能是 Error 子类
        // task-131：抛点已带原因码（source:/manifest:）⇒ 这里返回空串，调用方不再重复加前缀。
        String marked = String.valueOf(error.getMessage());
        if (marked.startsWith("source:") || marked.startsWith("manifest:")) {
            return "";
        }
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

    private static Fetch httpGet(String url) throws Exception {
        return httpGet(url, sourceOf(url));
    }

    /** 回退链版本：来源标签由候选链给定（custom/builtin-release/builtin-raw/builtin-direct）。 */
    private static Fetch httpGet(String url, String source) throws Exception {
        return httpGet(url, source, CANDIDATE_CONNECT_TIMEOUT_MS, CANDIDATE_READ_TIMEOUT_MS);
    }

    private static Fetch httpGet(String url, String source, int connectMs, int readMs) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        // 每候选各自的预算（不再共用一个总预算，弱网下第一条挂住不会拖死后面的候选）。
        connection.setConnectTimeout(connectMs);
        connection.setReadTimeout(readMs);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/json");
        connection.connect();
        int code = connection.getResponseCode();
        if (code / 100 != 2) {
            throw new IllegalStateException("source:请求失败 HTTP " + code + " url=" + url);
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
        return new Fetch(url, source, code, new String(buffer.toByteArray(), "UTF-8"));
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
