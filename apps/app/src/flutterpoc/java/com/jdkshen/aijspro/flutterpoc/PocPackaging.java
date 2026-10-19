package com.jdkshen.aijspro.flutterpoc;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.jdkshen.aijspro.Pref;
import com.jdkshen.aijspro.autojs.build.ApkBuilder;
import com.jdkshen.aijspro.autojs.build.BuildFailureLog;
import com.jdkshen.aijspro.autojs.build.sign.KeyStoreGenerator;
import com.jdkshen.aijspro.autojs.build.sign.SigningKey;
import com.jdkshen.aijspro.autojs.build.sign.SigningOptions;
import com.jdkshen.aijspro.build.ApkBuilderPluginHelper;
import com.jdkshen.aijspro.external.fileprovider.AppFileProvider;
import com.stardust.autojs.project.ProjectConfig;
import com.stardust.util.IntentUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 玻璃打包页（Flutter \`ProjectPage\`）的桥后端：项目配置读写、后台打包任务、签名管理。
 *
 * <p>只负责「页面需要的 JSON + 真正调用 ApkBuilder」，不碰界面。打包在后台线程执行，
 * 状态按 2s 轮询（页面 \`projectBuildStatus\`），进程活着就继续。
 */
final class PocPackaging {

    private static final String TAG = "GlassFlutterPoc";
    private static final String PREF_PACKAGING = "flutter-packaging";
    private static final String PREF_ACTIVE_JOB = "active_job";
    private static final String KEY_ALIAS_PASSWORD = "aijspro.build.signing.aliasPassword.";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService BUILDS = Executors.newSingleThreadExecutor(
            runnable -> new Thread(runnable, "glass-project-build"));
    private static final Map<String, Job> JOBS = Collections.synchronizedMap(new HashMap<>());
    private static final AtomicReference<String> ACTIVE = new AtomicReference<>();

    /** 打包模板自带的权限（与原生打包页 PermissionCatalog.TEMPLATE_DEFAULTS 一致）。 */
    private static final List<String> TEMPLATE_DEFAULTS = Arrays.asList(
            "android.permission.ACCESS_WIFI_STATE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.READ_EXTERNAL_STORAGE",
            "com.android.launcher.permission.INSTALL_SHORTCUT",
            "com.android.launcher.permission.UNINSTALL_SHORTCUT",
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.SYSTEM_ALERT_WINDOW",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.RECORD_AUDIO",
            "android.permission.READ_PHONE_STATE",
            "android.permission.VIBRATE",
            "android.permission.WAKE_LOCK",
            "android.permission.REQUEST_INSTALL_PACKAGES",
            "android.permission.WRITE_SETTINGS",
            "android.permission.WRITE_SECURE_SETTINGS");
    private static final List<String> DEFAULT_REQUEST = Collections.singletonList(
            "android.permission.WRITE_EXTERNAL_STORAGE");
    private static final List<String> KNOWN_ABIS = Arrays.asList("arm64-v8a", "armeabi-v7a");

    private PocPackaging() {
    }

    // ---------------------------------------------------------------- 分发

    static Object execute(Activity activity, String method, JSONObject args) throws Exception {
        switch (method) {
            case "projectLoad":
                return load(activity, args.getString("path"));
            case "projectSave":
                return save(activity, args);
            case "projectBuild":
                return build(activity, args.getString("path"), args.optString("revision", ""));
            case "projectBuildStatus":
                return status(activity, args.getString("path"), args.optString("id", ""));
            case "projectSigningList":
                return signingList(activity);
            case "projectSigningAliases":
                return signingAliases(activity, args);
            case "projectSigningCreate":
                return signingCreate(activity, args);
            case "prepareSingleFile":
                return prepareSingleFile(activity, args.getString("path"));
            case "projectBuildInstall":
                return installArtifact(activity, args.getString("id"));
            case "projectBuildShare":
                return shareArtifact(activity, args.getString("id"));
            case "nativePackagingRequest":
                return nativePackagingRequest(activity);
            default:
                throw new UnsupportedOperationException("POC 尚未实现打包桥方法：" + method);
        }
    }

    // ------------------------------------------------------------ 项目加载

    private static Object load(Activity activity, String rawPath) throws Exception {
        File directory = projectDir(activity, rawPath);
        File configFile = new File(directory, ProjectConfig.CONFIG_FILE_NAME);
        JSONObject config;
        byte[] bytes;
        if (configFile.isFile()) {
            bytes = readAll(configFile);
            config = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } else {
            config = defaults(directory);
            bytes = config.toString(2).getBytes(StandardCharsets.UTF_8);
            writeFile(configFile, bytes);
        }
        normalize(config, directory);
        JSONObject loaded = new JSONObject()
                .put("path", directory.getPath())
                .put("config", config)
                .put("revision", md5Bytes(bytes))
                .put("engine", "node".equals(config.optString("type")) ? "node" : "rhino")
                .put("scripts", new JSONArray())
                .put("installedPlugins", new JSONArray())
                .put("packaging", new JSONObject()
                        .put("shipped", new JSONArray(KNOWN_ABIS))
                        .put("template", new JSONArray(KNOWN_ABIS)));
        JSONObject signingConfig = config.optJSONObject("signingConfig");
        String keystore = signingConfig == null ? "" : signingConfig.optString("keystore", "");
        boolean hasPassword = !keystore.isEmpty()
                && !Pref.getPrefString(SigningOptions.INSTANCE.passwordPrefKey(keystore), "").isEmpty();
        loaded.put("signing", new JSONObject().put("hasPassword", hasPassword));
        loaded.put("nativeBridge", detectNativeBridge(directory, config));
        return loaded;
    }

    /** task-117：扫描 libs/**.so、libs/*.jar、project.json features/plugins，命中第三方 native 桥时返回警告信息。 */
    private static JSONObject detectNativeBridge(File directory, JSONObject config) throws org.json.JSONException {
        try {
            JSONArray libs = new JSONArray();
            JSONArray jars = new JSONArray();
            JSONArray features = new JSONArray();
            File libsDir = new File(directory, "libs");
            File libDir = new File(directory, "lib");
            collectFiles(libsDir, libsDir, ".so", libs, 0);
            collectFiles(libsDir, libsDir, ".jar", jars, 0);
            collectFiles(libsDir, libsDir, ".dex", jars, 0);
            if (libDir.isDirectory()) {
                collectFiles(libDir, libDir, ".so", libs, 0);
                collectFiles(libDir, libDir, ".dex", jars, 0);
                collectFiles(libDir, libDir, ".jar", jars, 0);
            }
            JSONObject featureConfig = config.optJSONObject("features");
            if (featureConfig != null) {
                java.util.Iterator<String> keys = featureConfig.keys();
                while (keys.hasNext()) features.put(keys.next());
            }
            JSONObject pluginConfig = config.optJSONObject("plugins");
            if (pluginConfig != null) {
                java.util.Iterator<String> keys = pluginConfig.keys();
                while (keys.hasNext()) features.put("plugin:" + keys.next());
            }
            boolean detected = libs.length() > 0 || jars.length() > 0;
            if (!detected) {
                return new JSONObject().put("detected", false);
            }
            boolean imGui = false;
            for (int i = 0; i < libs.length() && !imGui; i++) {
                if (libs.optString(i).toLowerCase(Locale.ROOT).contains("imgui")) imGui = true;
            }
            for (int i = 0; i < jars.length() && !imGui; i++) {
                if (jars.optString(i).toLowerCase(Locale.ROOT).contains("imgui")) imGui = true;
            }
            String message = imGui
                    ? "检测到 ImGui 第三方 native 桥（libImGuiBridge.so 等），打包产物可能需 :script 运行时支持；如闪退请用内置 ImGui API 或 Pro 打包"
                    : "检测到第三方 native 库，打包产物可能需 :script 运行时支持；如闪退请用内置 ImGui API 或 Pro 打包";
            Log.i(TAG, "C-PKG: native-bridge project detected libs=" + libs
                    + " jars=" + jars + " features=" + features + " imGui=" + imGui);
            return new JSONObject().put("detected", true)
                    .put("imGui", imGui)
                    .put("libs", libs)
                    .put("jars", jars)
                    .put("features", features)
                    .put("message", message);
        } catch (Throwable error) {
            Log.w(TAG, "C-PKG: native-bridge detect failed", error);
            return new JSONObject().put("detected", false);
        }
    }

    private static void collectFiles(File baseDir, File dir, String suffix, JSONArray out, int depth) {
        if (dir == null || depth > 8 || !dir.isDirectory()) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                collectFiles(baseDir, child, suffix, out, depth + 1);
            } else if (child.getName().toLowerCase(Locale.ROOT).endsWith(suffix)) {
                String path = child.getPath();
                String base = baseDir == null ? "" : baseDir.getPath();
                out.put(path.startsWith(base + File.separator) ? path.substring(base.length() + 1) : child.getName());
            }
        }
    }

    private static JSONObject defaults(File directory) throws Exception {
        String name = directory.getName();
        String packageName = "com.example.script" + (System.currentTimeMillis() / 1000L);
        JSONObject config = new JSONObject()
                .put("name", name)
                .put("packageName", packageName)
                .put("versionName", "1.0.0")
                .put("versionCode", 1)
                .put("main", "main.js")
                .put("encryptLevel", 0)
                .put("icon", "")
                .put("assets", new JSONArray())
                .put("useFeatures", new JSONArray())
                .put("abis", new JSONArray(KNOWN_ABIS))
                .put("features", new JSONObject())
                .put("plugins", new JSONObject())
                .put("optimization", optimization(false))
                .put("signingConfig", new JSONObject())
                .put("permissionConfig", new JSONObject()
                        .put("manifestPermissions", new JSONArray(TEMPLATE_DEFAULTS))
                        .put("requestListOnStartup", new JSONArray(DEFAULT_REQUEST)))
                .put("launchConfig", new JSONObject()
                        .put("displaySplash", true)
                        .put("hideLogs", false)
                        .put("stableMode", false)
                        .put("splashText", "AI.js Pro"));
        String main = null;
        File[] children = directory.listFiles();
        if (children != null) {
            Arrays.sort(children);
            for (File child : children) {
                if (child.isFile() && child.getName().toLowerCase(Locale.ROOT).endsWith(".js")) {
                    main = child.getName();
                    break;
                }
            }
        }
        if (main != null) {
            config.put("main", main).put("name", main.substring(0, main.length() - 3));
        }
        return config;
    }

    private static JSONObject optimization(boolean value) throws Exception {
        return new JSONObject()
                .put("unusedResources", value)
                .put("removeOpenCv", false)
                .put("removeAccessibilityService", false)
                .put("obfuscateComponents", false);
    }

    private static void normalize(JSONObject config, File directory) throws Exception {
        if (!config.has("name")) config.put("name", directory.getName());
        if (!config.has("packageName")) config.put("packageName", "com.example.script" + (System.currentTimeMillis() / 1000L));
        if (!config.has("versionName")) config.put("versionName", "1.0.0");
        if (!config.has("versionCode")) config.put("versionCode", 1);
        if (!config.has("main")) config.put("main", "main.js");
        if (!config.has("encryptLevel")) config.put("encryptLevel", 0);
        if (!config.has("icon")) config.put("icon", "");
        if (!config.has("assets")) config.put("assets", new JSONArray());
        if (!config.has("useFeatures")) config.put("useFeatures", new JSONArray());
        if (!config.has("abis")) config.put("abis", new JSONArray(KNOWN_ABIS));
        if (!config.has("features")) config.put("features", new JSONObject());
        if (!config.has("plugins")) config.put("plugins", new JSONObject());
        if (!config.has("optimization")) config.put("optimization", optimization(false));
        if (!config.has("signingConfig")) config.put("signingConfig", new JSONObject());
        if (!config.has("permissionConfig")) {
            config.put("permissionConfig", new JSONObject()
                    .put("manifestPermissions", new JSONArray(TEMPLATE_DEFAULTS))
                    .put("requestListOnStartup", new JSONArray(DEFAULT_REQUEST)));
        }
        if (!config.has("launchConfig")) {
            config.put("launchConfig", new JSONObject()
                    .put("displaySplash", true).put("hideLogs", false)
                    .put("stableMode", false).put("splashText", "AI.js Pro"));
        }
    }

    // ------------------------------------------------------------ 项目保存

    private static Object save(Activity activity, JSONObject args) throws Exception {
        File directory = projectDir(activity, args.getString("path"));
        File configFile = new File(directory, ProjectConfig.CONFIG_FILE_NAME);
        String expectedRevision = args.optString("revision", "");
        JSONObject previous = configFile.isFile()
                ? new JSONObject(new String(readAll(configFile), StandardCharsets.UTF_8))
                : null;
        String currentRevision = previous == null ? "" : md5(configFile);
        if (!expectedRevision.isEmpty() && !expectedRevision.equals(currentRevision)) {
            throw new IOException("项目配置已在别处修改，请重新读取后再保存");
        }
        JSONObject incoming = args.optJSONObject("value");
        if (incoming == null) {
            throw new IOException("没有收到项目配置");
        }
        JSONObject merged = previous == null ? new JSONObject() : new JSONObject(previous.toString());
        JSONObject signing = incoming.optJSONObject("signingConfig");
        for (String key : new String[]{"name", "packageName", "versionName", "main", "icon", "type",
                "engine", "scriptStorage", "signatureFingerprint"}) {
            if (incoming.has(key)) merged.put(key, incoming.optString(key, ""));
        }
        if (incoming.has("versionCode")) {
            merged.put("versionCode", incoming.optInt("versionCode", 1));
        }
        if (incoming.has("encryptLevel")) {
            merged.put("encryptLevel", incoming.optInt("encryptLevel", 0));
        }
        merged.put("assets", incoming.optJSONArray("assets") == null ? new JSONArray() : incoming.optJSONArray("assets"));
        merged.put("useFeatures", incoming.optJSONArray("useFeatures") == null ? new JSONArray() : incoming.optJSONArray("useFeatures"));
        merged.put("abis", incoming.optJSONArray("abis") == null ? new JSONArray(KNOWN_ABIS) : incoming.optJSONArray("abis"));
        if (incoming.optJSONObject("features") != null) merged.put("features", incoming.optJSONObject("features"));
        if (incoming.optJSONObject("plugins") != null) merged.put("plugins", incoming.optJSONObject("plugins"));
        if (incoming.optJSONObject("optimization") != null) merged.put("optimization", incoming.optJSONObject("optimization"));
        if (incoming.optJSONObject("permissionConfig") != null) merged.put("permissionConfig", incoming.optJSONObject("permissionConfig"));
        if (incoming.optJSONObject("launchConfig") != null) merged.put("launchConfig", incoming.optJSONObject("launchConfig"));
        merged.put("signingConfig", signing == null ? new JSONObject() : signing);

        JSONObject signingPatch = args.optJSONObject("signing");
        if (signingPatch != null) {
            String keystore = signing == null ? "" : signing.optString("keystore", "");
            if (signingPatch.optBoolean("clear", false)) {
                if (!keystore.isEmpty()) {
                    Pref.setPrefString(SigningOptions.INSTANCE.passwordPrefKey(keystore), "");
                    Pref.setPrefString(KEY_ALIAS_PASSWORD + keystore, "");
                }
            } else {
                if (!keystore.isEmpty() && signingPatch.has("password")
                        && !signingPatch.optString("password", "").isEmpty()) {
                    Pref.setPrefString(SigningOptions.INSTANCE.passwordPrefKey(keystore),
                            signingPatch.optString("password"));
                }
                if (!keystore.isEmpty() && signingPatch.has("aliasPassword")
                        && !signingPatch.optString("aliasPassword", "").isEmpty()) {
                    Pref.setPrefString(KEY_ALIAS_PASSWORD + keystore,
                            signingPatch.optString("aliasPassword"));
                }
            }
        }
        normalize(merged, directory);
        byte[] bytes = merged.toString(2).getBytes(StandardCharsets.UTF_8);
        writeFile(configFile, bytes);
        return load(activity, directory.getPath());
    }

    // ------------------------------------------------------------ 打包任务

    private static Object build(Activity activity, String rawPath, String revision) throws Exception {
        File directory = projectDir(activity, rawPath);
        File configFile = new File(directory, ProjectConfig.CONFIG_FILE_NAME);
        if (!configFile.isFile()) {
            throw new IOException("项目缺少 project.json");
        }
        if (!revision.isEmpty() && !revision.equals(md5(configFile))) {
            throw new IOException("项目配置已变化，请刷新后再打包");
        }
        String active = ACTIVE.get();
        if (active != null) {
            Job running = JOBS.get(active);
            if (running != null && running.running()) {
                throw new IOException("已有打包任务在运行，请等待完成");
            }
        }
        JSONObject config = new JSONObject(new String(readAll(configFile), StandardCharsets.UTF_8));
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Job job = new Job(id, directory.getPath());
        JOBS.put(id, job);
        ACTIVE.set(id);
        job.start();
        BUILDS.execute(() -> runBuild(activity.getApplicationContext(), job, directory, config));
        return job.json();
    }

    private static void runBuild(android.content.Context context, Job job, File directory,
                                 JSONObject config) {
        File temporary = new File(context.getCacheDir(), "glass-build/" + job.id);
        try {
            job.stage("starting");
            job.event("开始打包：" + directory.getName());
            ApkBuilderPluginHelper.TemplateStatus templateStatus =
                    ApkBuilderPluginHelper.templateStatus(context);
            File templateFile = ApkBuilderPluginHelper.cachedTemplateFile(context);
            long templateBytes = templateFile.isFile() ? templateFile.length() : -1L;
            Log.i(TAG, "C-PKG: template available=" + templateStatus.available
                    + " source=" + templateStatus.source
                    + " detail=" + templateStatus.detail
                    + " size=" + templateBytes);
            if (!templateStatus.available) {
                throw new IOException("打包模板未就绪：" + templateStatus.detail
                        + "（请安装「APK 打包插件」，或把 template.apk 放到 /sdcard/template.apk）");
            }
            ProjectConfig projectConfig = ProjectConfig.fromProjectDir(directory.getPath());
            if (projectConfig == null) {
                throw new IOException("project.json 无法解析");
            }
            ApkBuilder.AppConfig appConfig = ApkBuilder.AppConfig.fromProjectConfig(
                    directory.getPath(), projectConfig);
            appConfig.setShowSplash(config.optJSONObject("launchConfig") == null
                    || config.optJSONObject("launchConfig").optBoolean("displaySplash", true));
            // task-106：权限三态（对齐 Pro）—— null=保留模板默认、空数组=全删、列表=精确替换。
            JSONObject permissionConfig = config.optJSONObject("permissionConfig");
            JSONArray manifestArray = permissionConfig == null
                    ? null : permissionConfig.optJSONArray("manifestPermissions");
            boolean permissionDefaults = manifestArray == null;
            List<String> manifest = new ArrayList<>();
            if (permissionDefaults) {
                manifest.addAll(TEMPLATE_DEFAULTS);
            } else {
                for (int i = 0; i < manifestArray.length(); i++) manifest.add(manifestArray.optString(i));
            }
            List<String> add = new ArrayList<>(manifest);
            add.removeAll(TEMPLATE_DEFAULTS);
            List<String> remove = new ArrayList<>(TEMPLATE_DEFAULTS);
            remove.removeAll(manifest);
            appConfig.setPermissionsToAdd(add);
            appConfig.setPermissionsToRemove(remove);
            Log.i(TAG, "C-PKG: permissions mode="
                    + (permissionDefaults ? "default" : (manifest.isEmpty() ? "empty" : "list"))
                    + " declared=" + manifest.size() + " add=" + add.size() + " remove=" + remove.size());
            JSONObject launch = config.optJSONObject("launchConfig");
            JSONArray request = launch == null ? null : launch.optJSONArray("requestListOnStartup");
            List<String> requestList = new ArrayList<>();
            if (request != null) {
                for (int i = 0; i < request.length(); i++) requestList.add(request.optString(i));
            }
            if (requestList.isEmpty()) requestList.addAll(DEFAULT_REQUEST);
            appConfig.setRequestPermissions(requestList);
            applySigning(context, config, appConfig);

            // task-106：输出目录与 Pro 对齐：/sdcard/脚本/APK输出
            File outputDir = new File(Pref.getScriptDirPath(), "APK输出");
            if (!outputDir.isDirectory() && !outputDir.mkdirs()) {
                throw new IOException("无法创建 APK 输出目录：" + outputDir);
            }
            String appName = config.optString("name", directory.getName());
            String versionName = config.optString("versionName", "1.0.0");
            File outApk = new File(outputDir, appName + "_v" + versionName + ".apk");
            if (outApk.exists() && !outApk.delete()) {
                throw new IOException("无法覆盖已有 APK：" + outApk.getName());
            }
            if (!temporary.isDirectory() && !temporary.mkdirs()) {
                throw new IOException("无法创建临时目录");
            }
            job.event("输出：" + outApk.getPath());
            Log.i(TAG, "C-PKG: output=" + outApk.getAbsolutePath()
                    + " templateSize=" + templateBytes
                    + " project=" + directory.getPath());
            ApkBuilder builder = new ApkBuilder(
                    ApkBuilderPluginHelper.openTemplateApk(context), outApk, temporary.getPath());
            // task-106：resources.arsc 包名重写（与清单包名一致）。
            builder.setArscPackageName(config.optString("packageName", ""));
            Log.i(TAG, "C-PKG: arsc package=" + config.optString("packageName", ""));
            builder.setProgressCallback(new ApkBuilder.ProgressCallback() {
                @Override
                public void onPrepare(ApkBuilder ignored) {
                    job.stage("prepare");
                    job.event("准备打包工作区");
                }

                @Override
                public void onBuild(ApkBuilder ignored) {
                    job.stage("package");
                    job.event("生成 APK");
                }

                @Override
                public void onSign(ApkBuilder ignored) {
                    job.stage("sign");
                    job.event("签名 APK");
                }

                @Override
                public void onClean(ApkBuilder ignored) {
                    job.stage("clean");
                    job.event("清理工作区");
                }
            });
            builder.prepare();
            // task-108：瘦身模板（无 lib/）从宿主 APK 补 lib/arm64-v8a；旧全量模板自动跳过。
            int templateLibsAdded = ApkBuilder.ensureTemplateLibs(context, temporary);
            Log.i(TAG, "C-PKG: template libs added=" + templateLibsAdded);
            builder.withConfig(appConfig).build();
            job.stage("zip");
            job.event("压缩 APK");
            Log.i(TAG, "C-PKG: progress stage=zip");
            builder.sign().cleanWorkspace();
            if (!outApk.isFile()) {
                throw new IOException("打包完成但没有找到 APK");
            }
            String sha256 = sha256(outApk);
            boolean signed = false;
            String installedPackage = "";
            try {
                android.content.pm.PackageInfo info = context.getPackageManager()
                        .getPackageArchiveInfo(outApk.getPath(),
                                android.content.pm.PackageManager.GET_SIGNATURES);
                if (info == null) {
                    throw new IOException("生成的 APK 无法解析（文件损坏或模板不完整）");
                }
                installedPackage = info.packageName == null ? "" : info.packageName;
                signed = info.signatures != null && info.signatures.length > 0;
                if (!signed) {
                    throw new IOException("生成的 APK 没有签名（请检查打包模板与签名配置）");
                }
            } catch (IOException error) {
                throw error;
            } catch (Throwable error) {
                throw new IOException("校验生成的 APK 失败：" + error.getMessage(), error);
            }
            Log.i(TAG, "C-PKG: build ok apk=" + outApk.getAbsolutePath()
                    + " size=" + outApk.length() + " sha256=" + sha256
                    + " signed=" + signed + " package=" + installedPackage
                    + " configPackage=" + config.optString("packageName", ""));
            job.stage("success");
            job.event("打包完成：" + outApk.getName());
            job.success(outApk, sha256, config.optInt("versionCode", 1), config.optInt("encryptLevel", 0),
                    config.optString("packageName", ""), abisOf(config));
            Log.i(TAG, "C-PKG: progress stage=success");
        } catch (Throwable error) {
            Log.w(TAG, "glass build failed", error);
            Log.e(TAG, "C-PKG: build failed reason=" + BuildFailureLog.describe(error), error);
            job.stage("error");
            job.failure(BuildFailureLog.describe(error));
            Log.i(TAG, "C-PKG: progress stage=error");
            job.event("打包失败：" + BuildFailureLog.describe(error));
        } finally {
            ACTIVE.compareAndSet(job.id, null);
            job.persist(context);
            deleteTree(temporary);
        }
    }

    private static void applySigning(android.content.Context context, JSONObject config,
                                     ApkBuilder.AppConfig appConfig) throws Exception {
        JSONObject signingConfig = config.optJSONObject("signingConfig");
        String keystore = signingConfig == null ? "" : signingConfig.optString("keystore", "");
        if (keystore.isEmpty()) {
            return;
        }
        File file = new File(keystore);
        if (!file.isFile()) {
            throw new IOException("签名文件不存在：" + keystore);
        }
        String alias = signingConfig.optString("alias", "");
        List<String> candidates = new ArrayList<>();
        String typed = Pref.getPrefString(SigningOptions.INSTANCE.passwordPrefKey(keystore), "");
        if (!typed.isEmpty()) candidates.add(typed);
        candidates.addAll(SigningOptions.INSTANCE.recordedPasswords(file));
        if (candidates.isEmpty()) {
            throw new IOException("没有记录签名口令，请重新选择签名");
        }
        String aliasPassword = Pref.getPrefString(KEY_ALIAS_PASSWORD + keystore, "");
        SigningKey key = null;
        Exception failure = null;
        for (String password : candidates) {
            try {
                key = SigningKey.load(file, null, password.toCharArray(),
                        alias.isEmpty() ? null : alias,
                        (aliasPassword.isEmpty() ? password : aliasPassword).toCharArray());
                break;
            } catch (Exception error) {
                failure = error;
            }
        }
        if (key == null) {
            throw new IOException("签名文件口令不可用：" + (failure == null ? "" : failure.getMessage()));
        }
        appConfig.setSigner(SigningOptions.INSTANCE.signerFor(
                SigningOptions.MODE_EXISTING, key, alias));
        String fingerprint = key.getCertificateFingerprint();
        if (fingerprint != null && !fingerprint.isEmpty()) {
            appConfig.setSigningCertificateFingerprint(fingerprint);
        }
    }

    private static List<String> permissionsOf(JSONObject config) {
        JSONObject permissionConfig = config.optJSONObject("permissionConfig");
        JSONArray array = permissionConfig == null ? null
                : permissionConfig.optJSONArray("manifestPermissions");
        List<String> result = new ArrayList<>();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) result.add(array.optString(i));
        }
        if (result.isEmpty()) result.addAll(TEMPLATE_DEFAULTS);
        return result;
    }

    private static JSONArray abisOf(JSONObject config) {
        JSONArray abis = config.optJSONArray("abis");
        return abis == null ? new JSONArray(KNOWN_ABIS) : abis;
    }

    private static Object status(Activity activity, String rawPath, String id) throws Exception {
        File directory = projectDir(activity, rawPath);
        if (!id.isEmpty()) {
            Job job = JOBS.get(id);
            if (job != null) {
                return job.json();
            }
            Job persisted = readJob(activity, directory, id);
            if (persisted != null) {
                return persisted.json();
            }
            throw new IOException("找不到打包任务：" + id);
        }
        String active = ACTIVE.get();
        if (active != null) {
            Job job = JOBS.get(active);
            if (job != null && job.path.equals(directory.getPath())) {
                return job.json();
            }
        }
        Job latest = latestJob(activity, directory);
        return latest == null ? JSONObject.NULL : latest.json();
    }

    private static Object installArtifact(Activity activity, String id) throws Exception {
        File apk = artifact(id);
        long size = apk.length();
        Log.i(TAG, "C-PKG: install start apk=" + apk.getAbsolutePath() + " size=" + size);
        if (size < 1024L * 1024L) {
            throw new IOException("APK 文件过小（" + size + " 字节），可能没有打包成功：" + apk.getName());
        }
        try {
            IntentUtil.installApkOrToast(activity, apk.getPath(), AppFileProvider.AUTHORITY);
            Log.i(TAG, "C-PKG: install launched path=" + apk.getAbsolutePath());
            return new JSONObject().put("installed", true).put("path", apk.getAbsolutePath());
        } catch (Throwable error) {
            Log.e(TAG, "C-PKG: install failed apk=" + apk.getAbsolutePath(), error);
            throw new IOException("无法启动安装器：" + error.getMessage()
                    + "（可在文件管理器打开 APK 手动安装）", error);
        }
    }

    private static Object shareArtifact(Activity activity, String id) throws Exception {
        File apk = artifact(id);
        Uri uri = AppFileProvider.getUriForFile(activity, apk);
        Intent intent = new Intent(Intent.ACTION_SEND)
                .setType("application/vnd.android.package-archive")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(Intent.createChooser(intent, "分享 APK"));
        return Boolean.TRUE;
    }

    private static File artifact(String id) throws Exception {
        Job job = JOBS.get(id);
        if (job == null) {
            throw new IOException("找不到打包任务：" + id);
        }
        File apk = job.apk;
        if (apk == null || !apk.isFile()) {
            throw new IOException("APK 还没有生成完成");
        }
        return apk;
    }

    private static Object nativePackagingRequest(Activity activity) throws Exception {
        SharedPreferences prefs = activity.getSharedPreferences("flutter-glass",
                android.content.Context.MODE_PRIVATE);
        String raw = prefs.getString("native_packaging_request", "");
        if (raw == null || raw.isEmpty()) {
            return new JSONObject();
        }
        prefs.edit().remove("native_packaging_request").apply();
        return new JSONObject(raw);
    }

    // ------------------------------------------------------------ 单文件

    private static Object prepareSingleFile(Activity activity, String rawPath) throws Exception {
        File source = new File(rawPath).getCanonicalFile();
        if (!source.exists()) {
            throw new IOException("文件不存在：" + source.getName());
        }
        if (source.isDirectory()) {
            if (!new File(source, ProjectConfig.CONFIG_FILE_NAME).isFile()) {
                throw new IOException("这个目录不是项目（缺少 project.json）");
            }
            File scripts = new File(Pref.getScriptDirPath()).getCanonicalFile();
            if (source.getPath().startsWith(scripts.getPath() + File.separator)
                    || source.equals(scripts)) {
                return new JSONObject().put("path", source.getPath());
            }
            // 示例项目在 files/sample 里（只读语料），复制到临时项目再打包，避免写坏内置示例。
            File parent = new File(activity.getFilesDir(), "flutter-single-file");
            String token = "dir-" + md5Bytes(source.getPath().getBytes(StandardCharsets.UTF_8))
                    .substring(0, 8) + "-" + System.currentTimeMillis() / 1000L;
            File project = new File(parent, token);
            copyTree(source, project);
            return new JSONObject().put("path", project.getPath());
        }
        File parent = new File(activity.getFilesDir(), "flutter-single-file");
        String token = md5Bytes(source.getPath().getBytes(StandardCharsets.UTF_8))
                .substring(0, 8) + "-" + System.currentTimeMillis() / 1000L;
        File project = new File(parent, token);
        if (!project.isDirectory() && !project.mkdirs()) {
            throw new IOException("无法创建单文件项目目录");
        }
        String name = source.getName();
        File target = new File(project, name);
        copyFile(source, target);
        JSONObject config = defaults(project);
        config.put("name", name.toLowerCase(Locale.ROOT).endsWith(".js")
                ? name.substring(0, name.length() - 3) : name)
                .put("main", name)
                .put("assets", new JSONArray())
                .put("useFeatures", new JSONArray())
                .put("icon", "")
                .put("versionName", "1.0.0")
                .put("versionCode", 1);
        writeFile(new File(project, ProjectConfig.CONFIG_FILE_NAME),
                config.toString(2).getBytes(StandardCharsets.UTF_8));
        return new JSONObject().put("path", project.getPath());
    }

    // ------------------------------------------------------------ 签名管理

    private static Object signingList(Activity activity) throws Exception {
        File dir = keystoreDir(activity);
        JSONArray result = new JSONArray();
        for (File file : SigningOptions.INSTANCE.listKeyStores(dir)) {
            result.put(new JSONObject().put("name", file.getName()).put("path", file.getPath()));
        }
        return result;
    }

    private static Object signingAliases(Activity activity, JSONObject args) throws Exception {
        String path = args.getString("keystore");
        String password = args.optString("password", "");
        List<String> passwords = new ArrayList<>();
        if (!password.isEmpty()) passwords.add(password);
        passwords.addAll(SigningOptions.INSTANCE.recordedPasswords(new File(path)));
        KeyStore store = null;
        Exception failure = null;
        for (String candidate : passwords) {
            try {
                store = KeyStore.getInstance("JKS");
                try (InputStream input = new FileInputStream(path)) {
                    store.load(input, candidate.toCharArray());
                }
                break;
            } catch (Exception error) {
                failure = error;
                store = null;
            }
        }
        if (store == null) {
            throw new IOException("无法读取签名文件："
                    + (failure == null ? "请填写口令" : failure.getMessage()));
        }
        JSONArray aliases = new JSONArray();
        Enumeration<String> names = store.aliases();
        while (names.hasMoreElements()) {
            aliases.put(names.nextElement());
        }
        return aliases;
    }

    private static Object signingCreate(Activity activity, JSONObject args) throws Exception {
        File dir = keystoreDir(activity);
        String name = args.optString("name", "");
        String fileName = SigningOptions.INSTANCE.newKeyStoreFileName(name);
        File target = new File(dir, fileName);
        if (target.exists()) {
            throw new IOException("签名文件已存在：" + fileName);
        }
        String alias = args.optString("alias", "").trim();
        String password = args.optString("password", "");
        String aliasPassword = args.optString("aliasPassword", "");
        if (alias.isEmpty() || password.isEmpty()) {
            throw new IOException("别名和口令不能为空");
        }
        JSONObject certificate = args.optJSONObject("certificate");
        String commonName = certificate == null ? "AI.js Pro"
                : certificate.optString("name", "AI.js Pro");
        String organization = certificate == null ? "AI.js Pro"
                : certificate.optString("organization", "AI.js Pro");
        String country = certificate == null ? "CN" : certificate.optString("country", "CN");
        SigningKey key = KeyStoreGenerator.generate(
                commonName.isEmpty() ? "AI.js Pro" : commonName,
                organization.isEmpty() ? "AI.js Pro" : organization,
                country.isEmpty() ? "CN" : country);
        KeyStoreGenerator.save(key, target, password.toCharArray(), alias);
        Pref.setPrefString(SigningOptions.INSTANCE.passwordPrefKey(target.getPath()), password);
        if (!aliasPassword.isEmpty()) {
            Pref.setPrefString(KEY_ALIAS_PASSWORD + target.getPath(), aliasPassword);
        }
        return new JSONObject().put("path", target.getPath())
                .put("name", target.getName()).put("alias", alias);
    }

    private static File keystoreDir(Activity activity) throws IOException {
        File dir = SigningOptions.INSTANCE.keyStoreDir(Pref.getScriptDirPath());
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("无法创建签名目录：" + dir);
        }
        return dir;
    }

    // ------------------------------------------------------------ 工具

    private static File projectDir(Activity activity, String rawPath) throws IOException {
        File directory = new File(rawPath).getCanonicalFile();
        File scripts = new File(Pref.getScriptDirPath()).getCanonicalFile();
        File internal = new File(activity.getFilesDir(), "flutter-single-file").getCanonicalFile();
        boolean allowed = directory.equals(scripts) || directory.getPath().startsWith(scripts.getPath() + File.separator)
                || directory.getPath().startsWith(internal.getPath() + File.separator);
        if (!allowed) {
            throw new IOException("只能打包脚本目录或单文件临时项目");
        }
        if (!directory.isDirectory()) {
            throw new IOException("项目目录不存在：" + directory.getName());
        }
        return directory;
    }

    private static String md5(File file) throws Exception {
        return md5Bytes(readAll(file));
    }

    private static String md5Bytes(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        byte[] hash = digest.digest(bytes);
        StringBuilder builder = new StringBuilder();
        for (byte value : hash) {
            builder.append(Character.forDigit((value >> 4) & 0xF, 16));
            builder.append(Character.forDigit(value & 0xF, 16));
        }
        return builder.toString();
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder builder = new StringBuilder();
        for (byte value : digest.digest()) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }

    private static byte[] readAll(File file) throws IOException {
        try (InputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[65536];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static void writeFile(File file, byte[] bytes) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("无法创建目录：" + parent);
        }
        File pending = new File(file.getPath() + ".pending");
        FileOutputStream output = new FileOutputStream(pending);
        try {
            output.write(bytes);
            output.getFD().sync();
        } finally {
            output.close();
        }
        if (!pending.renameTo(file)) {
            if (!file.delete() || !pending.renameTo(file)) {
                throw new IOException("无法写入文件：" + file);
            }
        }
    }

    private static void copyTree(File source, File target) throws IOException {
        if (source.isDirectory()) {
            if (!target.isDirectory() && !target.mkdirs()) {
                throw new IOException("无法创建目录：" + target);
            }
            File[] children = source.listFiles();
            if (children != null) {
                for (File child : children) {
                    copyTree(child, new File(target, child.getName()));
                }
            }
            return;
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("无法创建目录：" + parent);
        }
        copyFile(source, target);
    }

    private static void copyFile(File source, File target) throws IOException {
        try (InputStream input = new FileInputStream(source);
             OutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteTree(child);
            }
        }
        file.delete();
    }

    private static File jobRecordDir(Activity activity) {
        File dir = new File(activity.getFilesDir(), "flutter-project-builds");
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    private static Job readJob(Activity activity, File project, String id) {
        File file = new File(jobRecordDir(activity), id + ".json");
        if (!file.isFile()) return null;
        try {
            Job job = Job.from(new JSONObject(new String(readAll(file), StandardCharsets.UTF_8)));
            return job != null && job.path.equals(project.getPath()) ? job : null;
        } catch (Exception error) {
            return null;
        }
    }

    private static Job latestJob(Activity activity, File project) {
        File[] files = jobRecordDir(activity).listFiles();
        if (files == null) return null;
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (File file : files) {
            if (!file.isFile() || !file.getName().endsWith(".json")) continue;
            try {
                Job job = Job.from(new JSONObject(new String(readAll(file), StandardCharsets.UTF_8)));
                if (job != null && job.path.equals(project.getPath())) return job;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /** 后台打包任务：内存里保留进度，同时落一份 JSON 供进程重启后查看。 */
    private static final class Job {
        final String id;
        final String path;
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        final long startedAt = System.currentTimeMillis();
        volatile String status = "running";
        volatile String stage = "starting";
        volatile String error = "";
        volatile File apk;
        volatile String sha256 = "";
        volatile long size;
        volatile int versionCode;
        volatile int encryptLevel;
        volatile String packageName = "";
        volatile JSONArray abis = new JSONArray();

        Job(String id, String path) {
            this.id = id;
            this.path = path;
        }

        static Job from(JSONObject json) {
            if (json == null || !json.has("id") || !json.has("projectPath")) return null;
            Job job = new Job(json.optString("id"), json.optString("projectPath"));
            job.status = json.optString("status", "running");
            job.stage = json.optString("stage", "");
            job.error = json.optString("error", "");
            job.sha256 = json.optString("sha256", "");
            job.size = json.optLong("size", 0);
            job.versionCode = json.optInt("versionCode", 0);
            job.encryptLevel = json.optInt("encryptLevel", 0);
            job.packageName = json.optString("packageName", "");
            job.apis(json.optJSONArray("abis"));
            String artifactPath = json.optString("path", "");
            if (!artifactPath.isEmpty()) job.apk = new File(artifactPath);
            JSONArray log = json.optJSONArray("events");
            if (log != null) {
                for (int i = 0; i < log.length(); i++) job.events.add(log.optString(i));
            }
            return job;
        }

        void apis(JSONArray value) {
            abis = value == null ? new JSONArray() : value;
        }

        boolean running() {
            return "running".equals(status);
        }

        void stage(String value) {
            stage = value;
        }

        void event(String value) {
            if (events.size() < 80) {
                events.add(value);
            }
        }

        void success(File artifact, String digest, int code, int level, String appPackage,
                     JSONArray architectures) {
            apk = artifact;
            sha256 = digest;
            size = artifact.length();
            versionCode = code;
            encryptLevel = level;
            packageName = appPackage == null ? "" : appPackage;
            apis(architectures);
            status = "success";
            stage = "";
        }

        void failure(String message) {
            error = message;
            status = "failed";
        }

        void start() {
            event("任务已创建");
        }

        JSONObject json() throws Exception {
            JSONObject value = new JSONObject()
                    .put("id", id)
                    .put("status", status)
                    .put("projectPath", path)
                    .put("stage", stage)
                    .put("events", new JSONArray(events))
                    .put("startedAt", startedAt);
            if (!error.isEmpty()) value.put("error", error);
            if (apk != null && apk.isFile()) {
                value.put("path", apk.getPath());
                value.put("size", size);
                value.put("sha256", sha256);
                value.put("versionCode", versionCode);
                value.put("encryptLevel", encryptLevel);
                value.put("packageName", packageName);
                value.put("abis", abis);
            }
            return value;
        }

        void persist(android.content.Context context) {
            try {
                File dir = new File(context.getFilesDir(), "flutter-project-builds");
                if (!dir.isDirectory() && !dir.mkdirs()) return;
                writeFile(new File(dir, id + ".json"),
                        json().toString().getBytes(StandardCharsets.UTF_8));
            } catch (Throwable ignored) {
            }
        }
    }
}
