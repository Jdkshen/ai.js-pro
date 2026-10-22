package com.jdkshen.aijspro.flutterpoc;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.os.Build;
import android.util.Log;

import com.jdkshen.aijspro.build.ApkBuilderPluginHelper;
import com.jdkshen.aijspro.model.plugin.PluginRegistry;
import com.jdkshen.aijspro.model.sample.SampleFiles;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 内置目录服务：给 Flutter「本地资源 / 本机 SDK 插件」两页提供同步数据源。
 *
 * <p>背景：这两页之前的数据来自 127.0.0.1:19888 的外部目录服务（电脑上跑的 catalog），
 * 新手机/没连电脑时该服务不存在，页面会按「Connection refused」空掉。改为由 App 自己
 * 从 assets 与 PackageManager 生成本地目录：
 *
 * <ul>
 *   <li><b>本地资源</b>：递归扫 {@code assets/sample}，只列「工程型示例」（目录里有
 *       {@code project.json}）；普通零散 .js/.mjs 不再单独列出（按用户要求只显示项目类）；</li>
 *   <li><b>本机 SDK 插件</b>：{@link PluginRegistry#build} 做事实层（批量 meta-data → 逐包
 *       GET_META_DATA 兜底 → 已知包名精确查询），本类只做 PackageManager 适配/取版本/APK 大小。</li>
 * </ul>
 *
 * <p>返回结构：{@code {items: [...]}}；扫描整体失败时返回 {@code {items: [], error: "中文"}}，
 * 单条失败只跳过该条。所有异常只写 logcat，桥层不会因此失败。
 *
 * <p>与 Dart 侧约定（字段尽量与旧外部目录服务对齐）：资源/插件条目都带
 * {@code id/name/category/summary/author/size/local}；资源另有 {@code assetPath}、
 * {@code kind(script|project)}、{@code directory}；插件另有 {@code packageName/versionName/installed}。
 */
final class PocCatalog {

    private static final String TAG = "GlassFlutterPoc";

    /** assets 根下的内置示例目录，与 SampleFiles.ASSET_ROOT 保持一致。 */
    private static final String SAMPLE_ROOT = SampleFiles.ASSET_ROOT;

    /** 单次目录最多返回的条目数（示例目录现在约 300 条，留足余量）。 */
    private static final int MAX_ITEMS = 600;

    /** 扫描递归深度上限（防御异常目录）。 */
    private static final int MAX_DEPTH = 10;

    /** 单个工程/目录体积统计上限：防止把超大模型库整个读一遍。 */
    private static final long MAX_DIR_BYTES = 64L * 1024 * 1024;

    /** project.json 读取上限。 */
    private static final int MAX_PROJECT_JSON_BYTES = 64 * 1024;
    // APK assets cannot change during this process's lifetime. Keep the built
    // catalogue read-only; installing a new APK starts a fresh process/cache.
    private static JSONArray cachedResourceItems;

    /** 已知插件包名：与 plugins 下各模块 applicationId 一致（查询后仍要确认 registry 非空）。 */
    private static final String[] KNOWN_PLUGIN_PACKAGES = {
            "com.jdkshen.xiangqi.plugin",
            ApkBuilderPluginHelper.PLUGIN_PACKAGE_NAME,
    };

    private PocCatalog() {
    }

    /** 同步桥入口：{@code localCatalog({plugin: bool})}。 */
    static Object localCatalog(Activity activity, JSONObject args) {
        JSONObject result = new JSONObject();
        try {
            boolean plugin = args != null && args.optBoolean("plugin", false);
            JSONArray items = plugin ? pluginItems(activity) : resourceItems(activity);
            return result.put("items", items);
        } catch (Throwable error) {
            Log.w(TAG, "内置目录读取失败", error);
            try {
                result.put("items", new JSONArray());
                result.put("error", "内置目录读取失败：" + describe(error));
            } catch (Throwable ignored) {
                // 极端情况下 JSON 组装失败也没有更好的兜底了。
            }
            return result;
        }
    }

    // ---- 本地资源（assets/sample）---------------------------------------

    private static synchronized JSONArray resourceItems(Activity activity) {
        if (cachedResourceItems != null) return cachedResourceItems;
        List<JSONObject> items = new ArrayList<>();
        try {
            scanResourceDir(activity.getAssets(), SAMPLE_ROOT, 0, items);
        } catch (Throwable error) {
            Log.w(TAG, "扫描内置示例失败", error);
        }
        Collections.sort(items, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject first, JSONObject second) {
                int byCategory = first.optString("category", "")
                        .compareToIgnoreCase(second.optString("category", ""));
                if (byCategory != 0) {
                    return byCategory;
                }
                return first.optString("name", "")
                        .compareToIgnoreCase(second.optString("name", ""));
            }
        });
        JSONArray array = new JSONArray();
        for (JSONObject item : items) {
            array.put(item);
        }
        cachedResourceItems = array;
        return array;
    }

    private static void scanResourceDir(AssetManager assets, String assetDir, int depth,
                                        List<JSONObject> items) {
        if (depth > MAX_DEPTH || items.size() >= MAX_ITEMS) {
            return;
        }
        String[] children = list(assets, assetDir);
        if (children.length == 0) {
            return;
        }
        // 工程型示例：目录里有 project.json 就只出「工程」一条，不展开里面的零散 .js。
        if (hasChild(children, "project.json")) {
            JSONObject project = projectItem(assets, assetDir);
            if (project != null && items.size() < MAX_ITEMS) {
                items.add(project);
            }
            return;
        }
        Arrays.sort(children);
        for (String child : children) {
            if (items.size() >= MAX_ITEMS) {
                return;
            }
            String childAsset = assetDir + "/" + child;
            if (isDirectory(assets, childAsset)) {
                scanResourceDir(assets, childAsset, depth + 1, items);
            }
            // 按用户要求：内置示例目录只显示「项目类」（含 project.json 的工程），
            // 零散 .js/.mjs 不再逐条列出（仍可在脚本目录里直接打开运行）。
        }
    }

    private static JSONObject scriptItem(AssetManager assets, String assetPath) {
        try {
            return new JSONObject()
                    .put("id", assetPath)
                    .put("name", displayNameOf(assetPath))
                    .put("category", categoryOf(assetPath))
                    .put("summary", "本地示例脚本")
                    .put("author", "本地资源")
                    .put("size", assetFileBytes(assets, assetPath, MAX_DIR_BYTES))
                    .put("assetPath", assetPath)
                    .put("kind", "script")
                    .put("directory", false)
                    .put("local", true);
        } catch (Throwable error) {
            Log.w(TAG, "跳过内置示例：" + assetPath, error);
            return null;
        }
    }

    private static JSONObject projectItem(AssetManager assets, String assetDir) {
        try {
            String name = readProjectName(assets, assetDir);
            if (name.isEmpty()) {
                name = SampleFiles.nameOf(assetDir);
            }
            return new JSONObject()
                    .put("id", assetDir)
                    .put("name", name)
                    .put("category", categoryOf(assetDir))
                    .put("summary", "本地示例工程")
                    .put("author", "本地资源")
                    .put("size", assetDirBytes(assets, assetDir))
                    .put("assetPath", assetDir)
                    .put("kind", "project")
                    .put("directory", true)
                    .put("local", true);
        } catch (Throwable error) {
            Log.w(TAG, "跳过内置工程：" + assetDir, error);
            return null;
        }
    }

    /** 相对 sample 的父路径，例如 {@code Rhino 引擎/界面控件}；根下返回空串。 */
    private static String categoryOf(String assetPath) {
        String relative = SampleFiles.relativePath(assetPath);
        int index = relative.lastIndexOf('/');
        return index <= 0 ? "" : relative.substring(0, index);
    }

    /** 显示名：去掉 .js/.mjs 扩展名（与旧目录服务/示例名一致）。 */
    private static String displayNameOf(String assetPath) {
        String name = SampleFiles.nameOf(assetPath);
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".mjs")) {
            return name.substring(0, name.length() - 4);
        }
        if (lower.endsWith(".js")) {
            return name.substring(0, name.length() - 3);
        }
        return name;
    }

    private static String readProjectName(AssetManager assets, String assetDir) {
        try (InputStream input = assets.open(assetDir + "/project.json")) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            int total = 0;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_PROJECT_JSON_BYTES) {
                    break;
                }
                output.write(buffer, 0, count);
            }
            JSONObject project = new JSONObject(new String(output.toByteArray(), "UTF-8"));
            String name = project.optString("name", "").trim();
            if (name.isEmpty()) {
                name = project.optString("packageName", "").trim();
            }
            return name;
        } catch (Throwable error) {
            return "";
        }
    }

    private static boolean isRunnableScript(String name) {
        if (name == null || name.startsWith(".")) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".js") || lower.endsWith(".mjs");
    }

    private static String[] list(AssetManager assets, String assetPath) {
        try {
            String[] children = assets.list(assetPath);
            return children == null ? new String[0] : children;
        } catch (IOException error) {
            return new String[0];
        }
    }

    private static boolean isDirectory(AssetManager assets, String assetPath) {
        return list(assets, assetPath).length > 0;
    }

    private static boolean hasChild(String[] children, String name) {
        for (String child : children) {
            if (name.equals(child)) {
                return true;
            }
        }
        return false;
    }

    /** 单个 asset 文件字节数（流式计数，脚本都很小；读不到返回 0）。 */
    private static long assetFileBytes(AssetManager assets, String assetPath, long cap) {
        try (InputStream input = assets.open(assetPath)) {
            byte[] buffer = new byte[16 * 1024];
            long total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > cap) {
                    return cap;
                }
            }
            return total;
        } catch (Throwable error) {
            return 0;
        }
    }

    /** 目录字节数（递归；超过 MAX_DIR_BYTES 就按上限返回，避免把超大模型库整读一遍）。 */
    private static long assetDirBytes(AssetManager assets, String assetDir) {
        String[] children = list(assets, assetDir);
        if (children.length == 0) {
            return assetFileBytes(assets, assetDir, MAX_DIR_BYTES);
        }
        long total = 0;
        for (String child : children) {
            String childAsset = assetDir + "/" + child;
            if (isDirectory(assets, childAsset)) {
                total += assetDirBytes(assets, childAsset);
            } else {
                total += assetFileBytes(assets, childAsset, MAX_DIR_BYTES);
            }
            if (total > MAX_DIR_BYTES) {
                return MAX_DIR_BYTES;
            }
        }
        return total;
    }

    // ---- 已装插件（PluginRegistry + PackageManager）---------------------

    private static JSONArray pluginItems(Activity activity) throws Exception {
        PackageManager pm = activity.getPackageManager();
        List<ApplicationInfo> apps = new ArrayList<>();
        String scanError = null;
        try {
            List<ApplicationInfo> bulk = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            if (bulk != null) {
                apps.addAll(bulk);
            }
        } catch (Throwable error) {
            scanError = describe(error);
            Log.w(TAG, "读取已安装应用失败", error);
        }

        List<PluginRegistry.InstalledApp> adapted = new ArrayList<>();
        for (ApplicationInfo app : apps) {
            try {
                // Discovery only needs package names and registry metadata.
                // Keep every name for the existing per-package fallback, while
                // resolving labels/versions only for confirmed plugin entries.
                adapted.add(new PluginRegistry.InstalledApp(app.packageName, app.packageName,
                        "", 0, registryClassOf(app)));
            } catch (Throwable error) {
                Log.w(TAG, "跳过已安装应用：" + app.packageName, error);
            }
        }

        PluginRegistry.ScanResult scan = PluginRegistry.build(adapted,
                packageName -> registryClassOfQuerying(pm, packageName), true,
                Arrays.asList(KNOWN_PLUGIN_PACKAGES),
                packageName -> matchOfQuerying(pm, packageName));

        JSONArray items = new JSONArray();
        for (PluginRegistry.PluginEntry entry : scan.getEntries()) {
            String packageName = entry.getPackageName();
            // 「安装本地插件 APK」占位行交给 Dart 侧自己渲染，不属于已装插件。
            if (packageName == null || packageName.trim().isEmpty()) {
                continue;
            }
            try {
                items.put(pluginItem(pm, entry, packageName));
            } catch (Throwable error) {
                Log.w(TAG, "跳过插件条目：" + packageName, error);
            }
        }
        if (items.length() == 0 && scanError != null) {
            throw new IOException("读取插件列表失败：" + scanError);
        }
        return items;
    }

    private static JSONObject pluginItem(PackageManager pm, PluginRegistry.PluginEntry entry,
                                         String packageName) throws Exception {
        boolean installed = entry.isInstalled();
        PackageInfo info = null;
        if (installed) {
            try {
                info = pm.getPackageInfo(packageName, 0);
            } catch (Throwable error) {
                installed = false;   // 列表来自缓存/旧扫描时包可能已卸载。
            }
        }
        String label = entry.getLabel();
        if (label == null || label.trim().isEmpty()) {
            label = packageName;
        }
        if (installed && info != null && info.applicationInfo != null) {
            String appLabel = labelOf(pm, info.applicationInfo);
            if (!appLabel.isEmpty()) {
                label = appLabel;
            }
        }
        String versionName = "";
        if (info != null && info.versionName != null) {
            versionName = info.versionName;
        }
        if (versionName.isEmpty()) {
            versionName = versionFromSummary(entry.getSummary());
        }
        long size = 0;
        if (info != null && info.applicationInfo != null
                && info.applicationInfo.sourceDir != null) {
            File apk = new File(info.applicationInfo.sourceDir);
            if (apk.isFile()) {
                size = apk.length();
            }
        }
        String summary = entry.getSummary();
        if (installed && !versionName.isEmpty()) {
            summary = "已安装 · " + versionName;
        } else if (summary == null || summary.trim().isEmpty()) {
            summary = installed ? "已安装" + (versionName.isEmpty() ? "" : " · " + versionName)
                    : "未安装";
        }
        return new JSONObject()
                .put("id", packageName)
                .put("name", label)
                .put("packageName", packageName)
                .put("versionName", versionName)
                .put("installed", installed)
                .put("category", "插件")
                .put("author", "本地")
                .put("summary", summary)
                .put("size", size)
                .put("local", true);
    }

    /** PluginEntry.summary 形如「已安装 · 1.0.0」；精确查询路径下用它兜底版本号。 */
    private static String versionFromSummary(String summary) {
        if (summary == null) {
            return "";
        }
        int index = summary.lastIndexOf('·');
        if (index < 0) {
            return "";
        }
        return summary.substring(index + 1).trim();
    }

    private static String labelOf(PackageManager pm, ApplicationInfo app) {
        try {
            CharSequence label = pm.getApplicationLabel(app);
            return label == null ? "" : label.toString();
        } catch (Throwable error) {
            return "";
        }
    }

    private static String registryClassOf(ApplicationInfo app) {
        try {
            if (app.metaData == null) {
                return null;
            }
            String value = app.metaData.getString(PluginRegistry.REGISTRY_KEY);
            return value == null || value.trim().isEmpty() ? null : value;
        } catch (Throwable error) {
            return null;
        }
    }

    private static String registryClassOfQuerying(PackageManager pm, String packageName) {
        PluginRegistry.PackageMatch match = matchOfQuerying(pm, packageName);
        return match == null ? null : match.getRegistryClass();
    }

    /** 单包精确查询 meta-data（HyperOS 等会裁剪批量返回的 metaData，慢路径兜底）。 */
    private static PluginRegistry.PackageMatch matchOfQuerying(PackageManager pm, String packageName) {
        try {
            PackageInfo info = pm.getPackageInfo(packageName, PackageManager.GET_META_DATA);
            String registryClass = null;
            String label = null;
            if (info.applicationInfo != null) {
                if (info.applicationInfo.metaData != null) {
                    String value = info.applicationInfo.metaData
                            .getString(PluginRegistry.REGISTRY_KEY);
                    if (value != null && !value.trim().isEmpty()) {
                        registryClass = value;
                    }
                }
                label = labelOf(pm, info.applicationInfo);
            }
            String versionName = info.versionName;
            if (versionName == null || versionName.isEmpty()) {
                versionName = String.valueOf(versionCode(info));
            }
            return new PluginRegistry.PackageMatch(registryClass,
                    label == null ? "" : label, versionName);
        } catch (Throwable error) {
            return null;
        }
    }

    private static String versionNameOf(PackageManager pm, String packageName) {
        try {
            PackageInfo info = pm.getPackageInfo(packageName, 0);
            return info.versionName;
        } catch (Throwable error) {
            return null;
        }
    }

    private static int versionCodeOf(PackageManager pm, String packageName) {
        try {
            return versionCode(pm.getPackageInfo(packageName, 0));
        } catch (Throwable error) {
            return 0;
        }
    }

    @SuppressWarnings("deprecation")
    private static int versionCode(PackageInfo info) {
        if (info == null) {
            return 0;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return (int) info.getLongVersionCode();
        }
        return info.versionCode;
    }

    private static String describe(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : "：" + message);
    }
}
