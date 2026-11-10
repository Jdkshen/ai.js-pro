package com.jdkshen.aijspro.autojs.build;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import com.jdkshen.aijspro.autojs.build.sign.AutoSigningIdentity;
import com.stardust.autojs.apkbuilder.ApkPackager;
import com.stardust.autojs.apkbuilder.ManifestEditor;
import com.stardust.autojs.apkbuilder.Signer;
import com.stardust.autojs.engine.QuickJsBytecodeCompiler;
import com.stardust.autojs.project.BuildInfo;
import com.stardust.autojs.project.LaunchConfig;
import com.stardust.autojs.project.ProjectConfig;
import com.stardust.autojs.project.ScriptKeyDerivation;
import com.stardust.autojs.project.ScriptProtection;
import com.stardust.autojs.rhino.AndroidClassLoader;
import com.stardust.autojs.script.CompiledScriptPayload;
import com.stardust.autojs.script.EmbeddedScriptFooter;
import com.stardust.autojs.script.ScriptCompiler;
import com.stardust.autojs.script.EncryptedScriptFileHeader;
import com.stardust.autojs.script.StringScriptSource;
import com.stardust.autojs.script.JavaScriptFileSource;
import com.stardust.autojs.script.JavaScriptSource;
import com.stardust.app.GlobalAppContext;
import com.stardust.pio.PFiles;
import com.stardust.pio.UncheckedIOException;
import com.stardust.autojs.core.ui.inflater.CompiledProjectResources;
import com.stardust.autojs.engine.encryption.ScriptPayloadV2;

import com.jdkshen.aijspro.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Builds a standalone APK from the bundled inrt runtime template and a user script.
 *
 * <p>This is an API-compatible reconstruction of the class originally provided by the
 * Auto.js source, generated from the DEX signatures preserved in the archived Debug
 * APK. The packaging pipeline (unzip template, rewrite manifest/arsc/icon, encrypt
 * scripts, re-sign) mirrors {@link com.stardust.autojs.apkbuilder.ApkBuilder}.</p>
 */
public class ApkBuilder {

    /** Relative path of the optional custom splash image inside the packaged assets. */
    private static final String SPLASH_ASSET_PATH = "assets/project/splash.png";

    /**
     * 打包页“特性”开关关掉时要移除的东西：
     *  - 无障碍服务声明（清单里的 service 节点）
     *  - 图色模块（OpenCV 动态库，单 ABI 约 70MB）
     */
    private static final String ACCESSIBILITY_SERVICE =
            "com.google.android.accessibility.selecttospeak.SelectToSpeakService";
    private static final String LEGACY_ACCESSIBILITY_SERVICE =
            "com.stardust.autojs.core.accessibility.AccessibilityService";

    /** 内嵌脚本载荷用的原生库（每个 ABI 一份，载荷追加在它尾部）。 */
    private static final String EMBEDDED_SCRIPT_LIBRARY = "libaijscrypto.so";

    /**
     * 「移除图像模块」的判定前缀：模板里的 OpenCV 运行时全在这里面。
     *
     * <p>为什么用前缀而不是逐个列名：模板升级会新增 OpenCV 库
     * （SIFT 接入时多了 {@code libopencv_features.so}，而这份清单没跟上，
     * 于是「移除图像模块」只删了一半、留下一个孤立的 features —— 既浪费体积，
     * 又让打包测试的「opencv 必须删干净」断言莫名其妙地红）。
     */
    private static final String IMAGE_MODULE_LIB_PREFIX = "libopencv_";
    /** 名字不带 opencv 前缀、但同属图像模块的库（OpenCV 的并行后端）。 */
    private static final String[] IMAGE_MODULE_EXTRA_LIBS = {"libtbb.so"};

    public interface ProgressCallback {
        void onPrepare(ApkBuilder builder);

        void onBuild(ApkBuilder builder);

        void onSign(ApkBuilder builder);

        void onClean(ApkBuilder builder);
    }

    private ApkPackager mApkPackager;
    private String mWorkspacePath;
    private File mOutApkFile;
    private ManifestEditor mManifestEditor;
    private ProgressCallback mProgressCallback;
    private AppConfig mAppConfig;
    private String mArscPackageName;
    // Derived in syncProjectJsonAndDeriveKeys() from assets/project/project.json:
    // task-123：脚本密钥**只**由新方案派生 ——
    // key = ScriptKeyDerivation.deriveKey(packageName, scriptSalt, 证书指纹)
    // vector = ScriptKeyDerivation.deriveVector(scriptSalt, 证书指纹)
    //（旧的 MD5 方案已从打包端删除：运行端不再支持，降级产物必然解不开）
    // The inrt runtime derives the same values from the same project.json.
    // task-127：v2 = AES-256-GCM + 真 32 字节密钥（不再有 String key / 固定 IV 这两个概念）。
    private byte[] mKeyV2;
    private String mMainScriptFile = "main.js";
    private String mScriptFile;
    // 脚本保护等级（project.json 的 encryptLevel）：0 明文、1 AES、≥2 编译后加密。
    // 默认跟工程配置走，打包页显式选过则以 AppConfig 为准（见 syncProjectJsonAndDeriveKeys）。
    private int mEncryptLevel = ScriptProtection.DEFAULT_LEVEL;
    // 脚本存放位置（project.json 的 scriptStorage）：assets 里的脚本文件，或嵌进原生库尾部。
    private String mScriptStorage = ScriptProtection.DEFAULT_STORAGE;
    // 密钥硬化用的字段：随机盐与打包时的签名证书指纹（两者都写进产物 project.json）。
    private String mScriptSalt;
    private String mSignatureFingerprint;
    // 本次入口脚本实际写出的载荷形态（0/1/2 级 × 引擎，见 NodeScriptProtection），
    // 主要给日志与单测用：等级 2 对 Node 不是字节码，必须能被观察出来。
    private NodeScriptProtection.PayloadKind mPayloadKind =
            NodeScriptProtection.PayloadKind.PLAIN_TEXT;

    public ApkBuilder(InputStream apkInputStream, File outApkFile, String workspacePath) {
        mOutApkFile = outApkFile;
        mWorkspacePath = workspacePath;
        mApkPackager = new ApkPackager(apkInputStream, workspacePath);
    }

    public ApkBuilder(File inFile, File outFile, String workspacePath) {
        this(toInputStream(inFile), outFile, workspacePath);
    }

    private static InputStream toInputStream(File file) {
        try {
            return new FileInputStream(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- task-108：瘦身模板从宿主 APK 补 lib/arm64-v8a -----------------

    private static final String HOST_LIB_PREFIX = "lib/arm64-v8a/";
    /** 兜底（无 excluded-zip-files.json 时）只补 inrt 运行时真正需要的 so，避免把 Flutter/MLKit 库也塞进产物。 */
    private static final String[] RUNTIME_LIB_NAMES = {
            "libaijscrypto.so", "libquickjs.so", "libquickjs_jni.so", "libppocr.so",
            "libmlkit_google_ocr_pipeline.so", "libc++_shared.so", "libpikafish.so"
    };
    private static final String[] RUNTIME_LIB_PREFIXES = {"libopencv_", "libtbb", "libv8"};
    /** 模板自带的补全清单（A 的 gradle/inrt 会把它打进模板 assets；也兼容主程序 assets）。 */
    private static final String[] EXCLUDED_LIST_PATHS = {
            "assets/template/excluded-zip-files.json",
            "assets/excluded-zip-files.json"
    };
    private static final String[] EXCLUDED_LIST_ASSETS = {
            "template/excluded-zip-files.json",
            "excluded-zip-files.json"
    };

    /**
     * task-108：把瘦身模板缺少的 lib/arm64-v8a/*.so 从宿主 APK 补进打包工作区。
     *
     * <p>清单来源优先级：模板自身 assets/template/excluded-zip-files.json →
     * 主程序同名 assets → 兜底扫描宿主 APK 全部 arm64 so。旧全量模板（已带 lib）
     * 直接跳过并打 C-PKG: template libs already present。失败抛中文 IOException。
     *
     * @return 本次补入的 so 数量；旧模板跳过返回 0
     */
    public static int ensureTemplateLibs(Context context, File workspace) throws IOException {
        if (context == null || workspace == null) {
            throw new IOException("补全模板 lib 失败：缺少 Context 或工作区");
        }
        File hostApk = hostApkFile(context);
        List<String> wanted = loadExcludedLibNames(context, workspace);
        boolean fallbackAll = wanted.isEmpty();
        // task-74：模板是否已带「清单里所有 ABI」的 lib —— 只带 arm64 的模板**仍要**补 v7a。
        // 单一事实来源 = excluded-zip-files.json（这里不写第二份 ABI 清单，也不写死 lib/arm64-v8a）。
        java.util.Set<String> presentAbis = TemplateLibCompletion.abisWithLibs(workspace);
        if (!fallbackAll) {
            java.util.Set<String> wantedAbis = TemplateLibCompletion.abisOf(wanted);
            List<String> missingAbis = new ArrayList<>(wantedAbis);
            missingAbis.removeAll(presentAbis);
            if (missingAbis.isEmpty()) {
                Log.i("C-PKG", TemplateLibCompletion.describeSkip(wanted, presentAbis));
                return 0;
            }
            wanted = TemplateLibCompletion.entriesForMissingAbis(wanted, presentAbis);
            Log.i("C-PKG", "template libs completion: wantedAbis=" + wantedAbis + " presentAbis=" + presentAbis
                    + " missingAbis=" + missingAbis + " entriesToCopy=" + wanted.size());
        } else {
            File abiDir = new File(workspace, HOST_LIB_PREFIX);
            File[] existing = abiDir.isDirectory()
                    ? abiDir.listFiles((dir, name) -> name != null && name.endsWith(".so")) : null;
            if (existing != null && existing.length > 0) {
                Log.i("C-PKG", "template libs already present count=" + existing.length
                        + " (模板自带 arm64 且无清单，跳过从宿主补齐)");
                return 0;
            }
        }
        int added = 0;
        int general = 0;
        int opencv = 0;
        int v8 = 0;
        ZipFile zip;
        try {
            zip = new ZipFile(hostApk);
        } catch (IOException error) {
            throw new IOException("打开宿主 APK 失败：" + hostApk + "（" + error.getMessage() + "）", error);
        }
        try {
            if (fallbackAll) {
                wanted = listHostRuntimeLibs(zip);
                if (wanted.isEmpty()) {
                    throw new IOException("补全模板 lib 失败：宿主 APK 里没有可用的 lib/<abi>/*.so：" + hostApk);
                }
                Log.i("C-PKG", "excluded-zip-files.json 不可用，兜底扫描宿主 APK libs=" + wanted.size()
                        + " abis=" + TemplateLibCompletion.abisOf(wanted));
            }
            for (String entryName : wanted) {
                ZipEntry entry = zip.getEntry(entryName);
                if (entry == null || entry.isDirectory()) {
                    Log.w("C-PKG", "C-PKG: host lib missing entry=" + entryName);
                    continue;
                }
                File out = new File(workspace, entryName);
                if (out.isFile() && out.length() > 0) {
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("无法创建模板 lib 目录：" + parent);
                }
                try (InputStream input = zip.getInputStream(entry);
                     FileOutputStream output = new FileOutputStream(out)) {
                    StreamUtils.write(input, output);
                }
                added++;
                String lower = entryName.toLowerCase(Locale.ROOT);
                if (lower.contains("opencv") || lower.contains("tbb")) {
                    opencv++;
                } else if (lower.contains("v8")) {
                    v8++;
                } else {
                    general++;
                }
            }
        } finally {
            zip.close();
        }
        if (added <= 0) {
            // dev-1451 回归修复（Lead 真机抓到）：清单里若只剩**别的 ABI** 的条目（例如宿主是 arm64-only
            // 而清单同时声明了 v7a），这里会 added=0 —— 那是「该 ABI 的库拿不到」，**不是**补全失败，
            // 不能把用户的打包整死（v7a 是否缺由 AbiPolicy 的显式 warning 负责）。只有**无清单**
            // 的兜底扫描（fallbackAll）拿不到任何宿主 lib 时才是真失败。
            if (fallbackAll) {
                throw new IOException("补全模板 lib 失败：宿主 APK 里没有可用的 lib/<abi>/*.so（"
                        + hostApk + "）");
            }
            Log.w("C-PKG", "template libs completion: 0 entries copied（清单条目在宿主 APK 中不存在，"
                    + "例如 ABI 不匹配）hostApk=" + hostApk.getPath());
            return 0;
        }
        Log.i("C-PKG", "template slim=true hostApk=" + hostApk.getPath()
                + " added=" + added + " (general=" + general + " opencv=" + opencv + " v8=" + v8
                + (fallbackAll ? " fallback=all" : " list=excluded-zip-files.json") + ")");
        return added;
    }

    private static File hostApkFile(Context context) throws IOException {
        try {
            ApplicationInfo info = context.getPackageManager()
                    .getApplicationInfo(context.getPackageName(), 0);
            String path = info == null ? null : info.sourceDir;
            if (path == null || path.isEmpty()) {
                throw new IOException("PackageManager 没有返回宿主 APK 路径");
            }
            File apk = new File(path);
            if (!apk.isFile()) {
                throw new IOException("宿主 APK 不存在：" + apk);
            }
            return apk;
        } catch (IOException error) {
            throw error;
        } catch (Throwable error) {
            throw new IOException("读取宿主 APK 失败：" + error.getMessage(), error);
        }
    }

    /** 读 excluded-zip-files.json（模板内/主程序 assets），返回标准化后的 lib entry 列表。 */
    private static List<String> loadExcludedLibNames(Context context, File workspace) {
        Set<String> result = new LinkedHashSet<>();
        String text = null;
        for (String path : EXCLUDED_LIST_PATHS) {
            File file = new File(workspace, path);
            if (file.isFile()) {
                try {
                    text = new String(readAll(file), "UTF-8");
                    Log.i("C-PKG", "excluded list from template workspace: " + path);
                    break;
                } catch (Throwable error) {
                    Log.w("C-PKG", "读取模板 excluded list 失败：" + path, error);
                }
            }
        }
        if (text == null) {
            for (String asset : EXCLUDED_LIST_ASSETS) {
                try (InputStream input = context.getAssets().open(asset)) {
                    java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) > 0) {
                        output.write(buffer, 0, read);
                    }
                    text = new String(output.toByteArray(), "UTF-8");
                    Log.i("C-PKG", "excluded list from app assets: " + asset);
                    break;
                } catch (Throwable ignored) {
                    // 继续下一个候选
                }
            }
        }
        if (text == null) {
            return new ArrayList<>(result);
        }
        try {
            JSONObject json = new JSONObject(text);
            for (String key : new String[]{"general", "opencv", "v8"}) {
                collectLibNames(json.opt(key), result);
            }
            if (result.isEmpty()) {
                // 兜底：有些清单直接是数组，或键名不同
                if (text.trim().startsWith("[")) {
                    collectLibNames(new JSONArray(text), result);
                } else {
                    for (java.util.Iterator<String> it = json.keys(); it.hasNext(); ) {
                        collectLibNames(json.opt(it.next()), result);
                    }
                }
            }
        } catch (Throwable error) {
            Log.w("C-PKG", "解析 excluded-zip-files.json 失败，改用兜底扫描", error);
        }
        return new ArrayList<>(result);
    }

    private static void collectLibNames(Object value, Set<String> out) {
        if (value == null) {
            return;
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                collectLibNames(array.opt(i), out);
            }
            return;
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            for (java.util.Iterator<String> it = object.keys(); it.hasNext(); ) {
                collectLibNames(object.opt(it.next()), out);
            }
            return;
        }
        if (!(value instanceof String)) {
            return;
        }
        String name = ((String) value).trim().replace('\\', '/');
        if (name.isEmpty()) {
            return;
        }
        if (name.startsWith("lib/")) {
            // task-74 补漏（Lead 真机抓到）：这里原来只收 lib/arm64-v8a/ 的条目，导致清单里的 v7a 条目
            // 在**加载阶段**就被丢掉（真机日志：`清单 15 条`，而 assets 里其实有 21 条），于是
            // ensureTemplateLibs 永远走「都已存在」分支、v7a 补齐永不触发。改成「lib/<任意 abi>/*.so」
            // 一律收，ABI 只由路径推导（不写第二份清单）。
            if (name.endsWith(".so") && TemplateLibCompletion.abiOf(name) != null) {
                out.add(name);
            }
            return;
        }
        if (name.contains("/")) {
            return;
        }
        if (!name.endsWith(".so")) {
            name = name + ".so";
        }
        out.add(HOST_LIB_PREFIX + name);
    }

    /** 无清单时的兜底：扫描宿主 APK **所有 ABI** 的 lib/<abi>/*.so，再按运行时白名单过滤（task-74：不再只扫 arm64）。 */
    private static List<String> listHostRuntimeLibs(ZipFile zip) {
        List<String> result = new ArrayList<>();
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (entry.isDirectory() || !name.endsWith(".so")) {
                continue;
            }
            String abi = TemplateLibCompletion.abiOf(name);
            if (abi == null) {
                continue;
            }
            if (isRuntimeLibrary(name.substring(name.lastIndexOf('/') + 1))) {
                result.add(name);
            }
        }
        return result;
    }

    /** 无清单时的兜底过滤：general/opencv/v8 相关；排除 Flutter/MLKit/androidx 等无关库。 */
    private static boolean isRuntimeLibrary(String fileName) {
        for (String prefix : RUNTIME_LIB_PREFIXES) {
            if (fileName.startsWith(prefix)) {
                return true;
            }
        }
        for (String exact : RUNTIME_LIB_NAMES) {
            if (exact.equals(fileName)) {
                return true;
            }
        }
        return false;
    }

    public ApkBuilder prepare() throws IOException {
        notifyPrepare();
        new File(mWorkspacePath).mkdirs();
        mApkPackager.unzip();
        // A template supplies the runtime, never a second project's files or configuration.
        // Older templates contain main.js, project.json and auto.js.png here.
        File project = new File(mWorkspacePath, "assets/project");
        deleteTemplateProject(project);
        if (!project.mkdirs() && !project.isDirectory()) {
            throw new IOException("无法创建打包项目目录：" + project);
        }
        mManifestEditor = new ManifestEditor(new FileInputStream(getManifestFile()));
        return this;
    }

    private static void deleteTemplateProject(File file) throws IOException {
        if (!file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("无法读取模板项目目录：" + file);
            for (File child : children) deleteTemplateProject(child);
        }
        if (!file.delete()) throw new IOException("无法清理模板项目文件：" + file);
    }

    protected File getManifestFile() {
        return new File(mWorkspacePath, "AndroidManifest.xml");
    }

    public ApkBuilder setProgressCallback(ProgressCallback progressCallback) {
        mProgressCallback = progressCallback;
        return this;
    }

    public ApkBuilder withConfig(AppConfig appConfig) {
        mAppConfig = appConfig;
        return this;
    }

    public ApkBuilder setScriptFile(String scriptFile) {
        mScriptFile = scriptFile;
        return this;
    }

    public ApkBuilder setArscPackageName(String arscPackageName) {
        mArscPackageName = arscPackageName;
        return this;
    }

    public ManifestEditor editManifest() {
        return mManifestEditor;
    }

    public ApkBuilder replaceFile(String relativePath, String newFilePath) throws IOException {
        StreamUtils.write(new FileInputStream(newFilePath),
                new FileOutputStream(new File(mWorkspacePath, relativePath)));
        return this;
    }

    public ApkBuilder build() throws Exception {
        notifyBuild();
        // Config patches (package name / label / version / icon) must reach the manifest
        // editor BEFORE commit() serializes it, otherwise they are silently dropped and
        // the packaged APK keeps the template identity (com.jdkshen.aijspro.inrt).
        if (mAppConfig != null) {
            updateProjectConfig(mAppConfig);
        }
        if (mManifestEditor != null) {
            mManifestEditor.commit();
            mManifestEditor.writeTo(new FileOutputStream(getManifestFile()));
        }
        if (mArscPackageName != null) {
            buildArsc();
        }
        // （task-131 批次 A 的 prune 调用已下移到工程内容进工作区之后，见 copyProjectToWorkspace 之后的同一带）
        copyProjectToWorkspace();
        copySplashIcon();
        removeDisabledFeatures();
        stageSjbcAssets();
        pruneDisabledPackagingAssets();
        // task-131 批次 A：必须放在**工程内容已进工作区之后**（真机 dev-1478 证据：放在 buildArsc 之后时
        // sidecars=0，因为 copyProjectToWorkspace() 还没跑）。判定对象 = 工作区 assets/**/build/project-res.json，
        // 与 sidecar 记录的构建期宿主摘要比对（宿主摘要用 W1 的算法取宿主 APK）。
        // 只在**确定不匹配**时删（判不出来一律原样保留，默认行为不变）。
        pruneStaleProjectResPacks();
        enforcePackagingPolicy();
        // 等级 × 引擎的实际载荷形态记一条：等级 2 对 Node 不是字节码，日志里必须能看出来。
        Log.i("C-PKG", "script protection level=" + mEncryptLevel
                + " storage=" + mScriptStorage + " payload=" + mPayloadKind);
        // task-106（复查点 2）：getPackagingWarnings() 目前全仓**没有消费者**（只有 logcat 能看到），
        // 所以把 warning 也放进结构化报告，凡读 getPackagingReport() 的调用方都能拿到。
        report(PackagingReportFields.PACKAGING_WARNINGS, new org.json.JSONArray(mPackagingWarnings));
        return this;
    }

    /**
     * Applies the packaging page's "features" switches by deleting what the template ships
     * but the user does not want in the packaged app.
     *
     * <p>⚠️ 「移除图像模块」对当前模板（QuickJS + OCR）来说仍是危险开关：本机
     * {@code libquickjs_jni.so} / {@code libppocr.so} 直接依赖 {@code libopencv_java5.so}，
     * 删掉 OpenCV 后它们加载会失败（内置示例的 README 也写了别关这个开关）。
     * 这里只保证「关掉时删干净」，不是「关掉是安全的」。
     */
    private void removeDisabledFeatures() {
        if (mAppConfig != null && !mAppConfig.includeImageModule) {
            deleteLibrariesRecursively(new File(mWorkspacePath, "lib"),
                    ApkBuilder::isImageModuleLibrary);
        }
    }

    /**
     * task-101：按工程开关，把**模板/宿主继承进来**的那部分资源从工作区剪掉（默认不动任何东西）：
     * <ul>
     *   <li>{@code optimization.removeOcr=true} ⇒ 删 {@code assets/mlkit-google-ocr-models/**} 与
     *       {@code assets/modules/__ocr__.js}（OCR 的 .so 由 {@link NativeLibPolicy} 同开关丢弃）；</li>
     *   <li>{@code features.nodejs=disabled} ⇒ 删 {@code assets/node24/**} 与
     *       {@code assets/source-engine-upgrade/**}（含与 lib/ 重复的 libc++_shared.so）。</li>
     * </ul>
     * 只在开关明确打开时动手；否则一条日志都不加（保证默认产物逐条不变）。
     */
    private void pruneDisabledPackagingAssets() throws IOException {
        File projectFile = new File(mAppConfig.sourcePath, "project.json");
        if (!projectFile.isFile()) {
            return;
        }
        JSONObject project;
        try {
            project = new JSONObject(new String(PFiles.readBytes(projectFile.getPath()), "UTF-8"));
        } catch (Throwable error) {
            Log.w("C-PKG", "读取 project.json 失败，跳过资源剪裁：" + error.getMessage());
            return;
        }
        JSONObject optimization = project.optJSONObject("optimization");
        boolean removeOcr = optimization != null && optimization.optBoolean("removeOcr", false);
        boolean nodejsDisabled = isNodeJsDisabled(project);
        // task-104 ③：四个字段**两态都写**（关时写 0/空串）——报告消费方不必区分「没有」与「0」。
        int[] ocrRemoved = new int[2];
        String ocrPrefix = "";
        if (removeOcr) {
            // task-104 ②：不写死 assets/mlkit-google-ocr-models/**，扫 assets 里实际存在的 OCR 资产；
            // 命中前缀回显到日志与报告，模板改名不会再静默变成 files=0。
            // task-106 隐患 A：名字匹配只是候选，**必须**是宿主/模板确实提供的 asset 条目才删。
            // 拿不到清单（空集）⇒ OcrAssetDiscovery.find 返回空 ⇒ 一个都不删（fail-safe）。
            List<String> providedAssets = hostAssetEntryNames();
            File assetsDir = new File(mWorkspacePath, "assets");
            List<File> candidates = OcrAssetDiscovery.candidates(assetsDir);
            // task-129：只删**模型目录**；__ocr__.js 不删，改成桩（直删会让守卫不可达）。
            List<File> ocrTargets = OcrAssetDiscovery.find(assetsDir, providedAssets);
            for (File target : ocrTargets) {
                deletePathRecursively(target, ocrRemoved);
            }
            ocrPrefix = OcrAssetDiscovery.describe(ocrTargets);
            int stubBytes = 0;
            File rhinoModule = OcrAssetDiscovery.findRhinoModule(assetsDir, providedAssets);
            if (rhinoModule != null) {
                // task-131 修正（K40 dev-1482 集成缺口）：**保留真实 __ocr__.js**。
                // W1 的模块版本已同时包含两条路：「插件优先 ⇒ $plugins.load(OCR 插件)」与
                // 「无插件/能力缺失 ⇒ 点名错误」。之前我们用 796B 桩替换它 ⇒ 桩直接 throw，
                // 插件分发永远走不到（装了插件也拿不到 OCR）。
                // 我们只剪模型与原生库（体积大头），模块本身原样保留。
                Log.i("C-PKG", "ocr removed: 保留真实 __ocr__.js（插件优先 + 点名错误两条路都在）；只剪模型与原生库");
            } else {
                // 真实模块不存在时才回退桩：否则脚本会拿到 Module "__ocr__" not found.，我们的文案不可达。
                File stubTarget = new File(assetsDir, OcrAssetDiscovery.RHINO_OCR_MODULE);
                byte[] stub = OcrModuleStub.content(
                        com.stardust.autojs.runtime.api.OcrAvailability.NOT_PACKAGED_MESSAGE)
                        .getBytes("UTF-8");
                writeBytes(stubTarget, stub);
                stubBytes = stub.length;
                Log.w("C-PKG", "ocr removed: 真实 __ocr__.js 不存在 ⇒ 写了 " + stubBytes + "B 桩（点名错误可达）");
            }
            List<File> skippedByName = new ArrayList<>(candidates);
            skippedByName.removeAll(ocrTargets);
            if (!skippedByName.isEmpty()) {
                // 名字像 OCR 资产、但模板/宿主并不提供 ⇒ 视为用户内容，保留（task-106 的反例场景）
                Log.i("C-PKG", "ocr prune: 按名字命中但模板未提供，保留不删 "
                        + OcrAssetDiscovery.describe(skippedByName));
            }
            if (providedAssets.isEmpty()) {
                Log.w("C-PKG", "ocr prune: 拿不到宿主/模板的 assets 清单 ⇒ 保守不删任何东西");
            }
            // QuickJS 侧（task-104 ①）：qjs-plugins.js / __plugins__.js 是**共享插件门面**，不能剪；
            // 它们只在运行期用 $ocr 全局（拿不到时自身有 guard + 引擎点名错误）⇒ 打包侧只剪 Rhino 的 __ocr__.js。
            Log.i("C-PKG", "ocr removed (removeOcr=true) files=" + ocrRemoved[0] + " bytes=" + ocrRemoved[1]
                    + " prefix=" + (ocrPrefix.isEmpty() ? "(none)" : ocrPrefix)
                    + " rhinoModule=" + (stubBytes > 0 ? "stubbed(__ocr__.js " + stubBytes + "B, real absent)" : "kept(real module)")
                    + " quickjs=kept(qjs-plugins.js 是共享插件门面，运行期 $ocr 缺失走点名错误)");
            if (ocrTargets.isEmpty() && stubBytes == 0) {
                Log.w("C-PKG", "ocr removed: 工作区里没找到任何 OCR 资产（模板可能改名）——前缀发现为空");
            }
            mPackagingWarnings.add("optimization.removeOcr=true：产物不含内建 OCR（$ocr 会点名报错）");
        }
        int[] node24Skipped = new int[2];
        if (nodejsDisabled) {
            deletePathRecursively(new File(mWorkspacePath, "assets/node24"), node24Skipped);
            deletePathRecursively(new File(mWorkspacePath, "assets/source-engine-upgrade"), node24Skipped);
            Log.i("C-PKG", "node24 assets skipped (nodejs=disabled) entries=" + node24Skipped[0]
                    + " bytes=" + node24Skipped[1]);
        }
        report(PackagingReportFields.OCR_REMOVED_FILES, ocrRemoved[0]);
        report(PackagingReportFields.OCR_REMOVED_BYTES, ocrRemoved[1]);
        report(PackagingReportFields.OCR_REMOVED_PREFIX, ocrPrefix);
        report(PackagingReportFields.NODE24_SKIPPED_ENTRIES, node24Skipped[0]);
        report(PackagingReportFields.NODE24_SKIPPED_BYTES, node24Skipped[1]);
    }

    /**
     * task-106：宿主 APK 里 assets/ 下的条目名（形如 assets/xxx/yyy）。
     *
     * <p>用途：把「OCR 资产」的删除限定为**模板/宿主确实提供**的条目，避免按名字误删用户内容。
     * 拿不到宿主 APK 时返回空集合 ⇒ 调用方保守地不删。</p>
     */
    private List<String> hostAssetEntryNames() {
        List<String> names = new ArrayList<>();
        try {
            File hostApk = hostApkFile(GlobalAppContext.get());
            try (ZipFile zip = new ZipFile(hostApk)) {
                java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    java.util.zip.ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (!entry.isDirectory() && name.startsWith("assets/")) {
                        names.add(name);
                    }
                }
            }
        } catch (Throwable error) {
            Log.w("C-PKG", "读取宿主 assets 清单失败（OCR 剪裁将保守跳过）：" + error.getMessage());
            return new ArrayList<>();
        }
        return names;
    }

    /**
     * task-131 批次 A：丢掉与**产物宿主**资源表不匹配的样例工程资源包（project-res.apk + json）。
     *
     * <p>判据走 {@link ProjectResSidecarPolicy}（纯逻辑、有单测）：摘要相等 ⇒ 原样保留；
     * 摘要确定不同 ⇒ 删这一对 + 打一条**打包提示**（复用 W1 的 staleResourcePackMessage，
     * 不抄第二份文案）+ C-PKG 日志；判不出来（缺 sidecar/缺摘要/读不到 arsc）⇒ 什么都不做。</p>
     *
     * <p>必须在 {@code buildArsc()} **之后**调用：那一步会改写产物宿主的 resources.arsc，
     * 放早了会拿旧摘要去比，误删好包。</p>
     */
    private void pruneStaleProjectResPacks() {
        // 产物宿主摘要：用 W1 的同一套算法（宿主 APK 的 resources.arsc），**不自己算第二份**。
        // 工作区那份 arsc 只作为兜底（拿不到宿主 APK 时用）。
        File hostArsc = new File(mWorkspacePath, "resources.arsc");
        String hostDigest = null;
        try {
            hostDigest = CompiledProjectResources.hostResourcesSha256OrNull(
                    hostApkFile(GlobalAppContext.get()).getPath());
        } catch (Throwable error) {
            Log.w("C-PKG", "project-res: 取宿主摘要失败，回退工作区 arsc：" + error.getMessage());
        }
        String fallbackDigest = hostDigest != null ? hostDigest : ProjectResSidecarPolicy.sha256(hostArsc);
        List<File> sidecars = new ArrayList<>();
        collectNamedFiles(new File(mWorkspacePath, "assets"), ProjectResSidecarPolicy.SIDECAR_NAME, sidecars, 0);
        Log.i("C-PKG", "project-res check: sidecars=" + sidecars.size()
                + " hostDigest=" + (fallbackDigest == null ? "(null)" : fallbackDigest.substring(0, 12))
                + " firstSidecar=" + (sidecars.isEmpty() ? "(none)" : sidecars.get(0).getPath()));
        for (File sidecar : sidecars) {
            File apk = ProjectResSidecarPolicy.apkFor(sidecar);
            String expected = null;
            try {
                expected = ProjectResSidecarPolicy.extractHostDigest(
                        new String(PFiles.readBytes(sidecar.getPath()), "UTF-8"));
            } catch (Throwable error) {
                Log.w("C-PKG", "project-res: 读 sidecar 失败：" + error.getMessage());
            }
            ProjectResSidecarPolicy.Decision decision =
                    ProjectResSidecarPolicy.decide(expected, fallbackDigest);
            // 每个 sidecar 都留一条决策日志（KEEP 也打）：下次真机若有问题，一眼看出是"没扫到"还是"判等"
            Log.i("C-PKG", "project-res decision: " + decision.getReason() + " sidecar=" + sidecar.getPath());
            if (decision.getAction() != ProjectResSidecarPolicy.Action.DROP) {
                continue;
            }
            String warning = CompiledProjectResources.staleResourcePackMessage(
                    decision.getExpectedDigest(), decision.getActualDigest(), apk);
            Log.w("C-PKG", "stale project-res dropped: " + decision.getReason() + " apk=" + apk);
            // task-131：丢了文件之后运行期**无文件可判** ⇒ 写一个极小标记把可执行文案带进产物
            //（LogActivity 才有东西可显示）。只在 DROP 写：KEEP 一律不写 ⇒ 默认行为不变。
            writeStaleResMarker(sidecar, decision, warning);
            //noinspection ResultOfMethodCallIgnored
            apk.delete();
            //noinspection ResultOfMethodCallIgnored
            sidecar.delete();
            mPackagingWarnings.add(warning);
        }
    }

    /**
     * task-131：丢掉 stale 资源包时留下**极小标记**（只给运行期读），让用户看到我们的可执行文案
     * 而不是白屏。命名与同类样例一致：与被丢的那个 sidecar 同目录，固定叫 project-res.stale.json。
     *
     * <p>内容：**只有两个摘要** expected（sidecar 记录的原宿主摘要）/ actual（本次产物宿主摘要）。
     * **不写 message**：打包期拼出来的文案里带着打包机的工作区路径（dev-1480 实测
     * /data/user/0/com.jdkshen.aijspro/cache/glass-build/...），用户在自己设备上看到会困惑 ⇒
     * 文案由**运行期**用运行时真实路径重新拼（仍然是 W1 的 staleResourcePackMessage 同一份函数）。
     * 只在 DROP 分支调用。</p>
     */
    private void writeStaleResMarker(File sidecar, ProjectResSidecarPolicy.Decision decision, String message) {
        try {
            org.json.JSONObject marker = new org.json.JSONObject();
            marker.put("expected", decision.getExpectedDigest() == null ? "" : decision.getExpectedDigest());
            marker.put("actual", decision.getActualDigest() == null ? "" : decision.getActualDigest());
            // message 不写进标记（见上面的原因）；包装期文案仍进 mPackagingWarnings + C-PKG 日志。
            byte[] bytes = marker.toString().getBytes("UTF-8");
            File target = new File(sidecar.getParentFile(), STALE_RES_MARKER);
            writeBytes(target, bytes);
            Log.w("C-PKG", "project-res stale marker: " + target.getPath() + " bytes=" + bytes.length);
        } catch (Throwable error) {
            // 标记写不出来不该让打包失败：打包提示与 C-PKG 日志已经记录过这件事。
            Log.w("C-PKG", "写 project-res stale 标记失败（不影响打包）：" + error.getMessage());
        }
    }

    /** 丢包后留给运行期的标记文件名（与 project-res.apk/json 同目录、同类样例一致）。 */
    private static final String STALE_RES_MARKER = "project-res.stale.json";

    /** 递归找指定文件名的文件（只用于工作区 assets/ 下的样例资源包，深度有界）。 */
    private static void collectNamedFiles(File dir, String name, List<File> out, int depth) {
        if (dir == null || !dir.isDirectory() || depth > 12) {
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collectNamedFiles(child, name, out, depth + 1);
            } else if (child.getName().equals(name)) {
                out.add(child);
            }
        }
    }

    /** project.json 的 {@code features.nodejs} 是否是 {@code disabled}（task-101）。 */
    private static boolean isNodeJsDisabled(JSONObject project) {
        if (project == null) {
            return false;
        }
        JSONObject features = project.optJSONObject("features");
        if (features == null) {
            return false;
        }
        return "disabled".equalsIgnoreCase(String.valueOf(features.opt("nodejs")));
    }

    /** 递归删除并把 [文件数, 字节数] 累加到 out（只用于日志/报告，不抛）。 */
    private static void deletePathRecursively(File path, int[] out) {
        if (path == null || !path.exists()) {
            return;
        }
        if (path.isDirectory()) {
            File[] children = path.listFiles();
            if (children != null) {
                for (File child : children) {
                    deletePathRecursively(child, out);
                }
            }
            //noinspection ResultOfMethodCallIgnored
            path.delete();
            return;
        }
        long length = path.length();
        if (path.delete()) {
            out[0]++;
            out[1] += (int) Math.min(Integer.MAX_VALUE, length);
        }
    }

    /** 是否属于「图像模块」（OpenCV 运行时 + 它的并行后端）。 */
    private static boolean isImageModuleLibrary(String fileName) {
        if (fileName.startsWith(IMAGE_MODULE_LIB_PREFIX)) {
            return true;
        }
        for (String extra : IMAGE_MODULE_EXTRA_LIBS) {
            if (extra.equals(fileName)) {
                return true;
            }
        }
        return false;
    }

    private void deleteLibrariesRecursively(File dir, java.util.function.Predicate<String> shouldDelete) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                deleteLibrariesRecursively(child, shouldDelete);
            } else if (shouldDelete.test(child.getName())) {
                //noinspection ResultOfMethodCallIgnored
                child.delete();
            }
        }
    }

    /**
     * task-63：打包不变量（全部集中在 build() 的同一个点，便于审计与单测纯逻辑）。
     *
     * 1) 产物 lib/ 只保留「模板必需 / 工程策略（removeOpenCv、pikafish）/ 内建 OCR」能解释的 .so，
     *    其余按泄漏删除并记日志（判据见 NativeLibPolicy，device4 实测 libpikafish.so 就是这么漏进去的）；
     * 2) project.json 声明的 abis 必须被尊重（AbiPolicy 两档）：声明的 ABI 里**至少有一个能产出** → 产出可用的 + 显式 warning 并回显（abisDeclared/abisProduced/abisMissing）；**一个都产不出**才报错。**不许静默少出**，但也不因为模板缺 v7a 把用户的工程挡死（v7a 属模板能力缺口）。
     * 3) 模板自带的 assets/autojs.keystore 是第三方打包器的旧签名身份，绝不能随产物分发 → 删除并记日志。
     */
    private final List<String> mPackagingWarnings = new ArrayList<>();
    private final JSONObject mPackagingReport = new JSONObject();

    /** task-63：打包过程中用户可执行的 warning（ABI 缺口、未覆盖插件…），调用方可直接展示。 */
    public List<String> getPackagingWarnings() {
        return new ArrayList<>(mPackagingWarnings);
    }

    /** task-63：结构化打包报告（abisDeclared/abisProduced/abisMissing/plugins/pluginCoverage/droppedNativeLibs/…）。 */
    public JSONObject getPackagingReport() {
        // task-111：每次读取都用**实时** warnings 同步一次 —— 报告可能在 warnings 还没加完时就被读过，
        // 或者调用方拿到的是不同时刻的快照；这样读到的永远是最新列表（消费方不必关心读取时机）。
        try {
            mPackagingReport.put(PackagingReportFields.PACKAGING_WARNINGS,
                    new org.json.JSONArray(mPackagingWarnings));
        } catch (Throwable ignored) {
            // 只用于可观测，同步失败不影响打包
        }
        return mPackagingReport;
    }

    private void report(String key, Object value) {
        try {
            mPackagingReport.put(key, value);
        } catch (Throwable ignored) {
            // 只用于可观测，失败不影响打包
        }
    }

    private String mSjbcCertSha;
    /** task-85 ②：上一次打过的 ABI 缺失结论签名（进程级；同一条只 W 一次）。 */
    private static volatile String sLastAbisWarningSignature;

    /** task-80 ②：由调用方（知道真正用的是哪把签名证书）传入的证书 SHA-256（冒号/大小写随意，会归一）。 */
    public ApkBuilder setSjbcCertSha(String certSha) {
        mSjbcCertSha = certSha;
        return this;
    }

    /**
     * task-80 ①：把工程的 .sjbc + manifest.json 原样收进产物 assets/sjbc/，并把 manifest 的 certSha
     * 对齐本次签名证书（换证书 → warning「必须重编脚本」）。**不含 .sjbc 时什么都不做**（行为不变）。
     *
     * <p>注意：这里**不**把 .sjbc 当脚本加密产物再套壳 —— 只做「原样拷贝 + manifest 字段对齐」。
     */
    private void stageSjbcAssets() throws IOException {
        if (mAppConfig == null || mAppConfig.sourcePath == null) {
            return;
        }
        File projectDir = new File(mAppConfig.sourcePath);
        if (!projectDir.isDirectory()) {
            return;
        }
        File sourceDir = SjbcPackaging.findSourceDir(projectDir);
        SjbcPackaging.Stage stage = SjbcPackaging.collect(sourceDir);
        if (stage.getEntries().isEmpty()) {
            return;
        }
        String certSha = SjbcPackaging.normalizeCertSha(mSjbcCertSha);
        if (certSha == null && mAppConfig != null
                && mAppConfig.getSigner() instanceof com.jdkshen.aijspro.autojs.build.sign.KeyStoreApkSigner) {
            // 真正会用哪把证书签名，就从那个 Signer 取（单一事实来源，避免与自定义 keystore 不一致）。
            certSha = SjbcPackaging.certSha256(((com.jdkshen.aijspro.autojs.build.sign.KeyStoreApkSigner)
                    mAppConfig.getSigner()).getSigningKey().getCertificate());
        }
        if (certSha == null) {
            // 没显式给就用本机自动签名身份（与 AutoSigningIdentity.signer 实际使用的一致）；
            // 若调用方用的是自定义 keystore，必须显式 setSjbcCertSha。
            certSha = SjbcPackaging.normalizeCertSha(
                    com.jdkshen.aijspro.autojs.build.sign.AutoSigningIdentity.INSTANCE.certificateFingerprint());
        }
        File targetDir = new File(mWorkspacePath, SjbcPackaging.TARGET_DIR);
        if (!targetDir.isDirectory() && !targetDir.mkdirs()) {
            throw new IOException("无法创建 SJBC 目标目录：" + targetDir);
        }
        List<String> sjbcNames = new ArrayList<>();
        String manifestText = null;
        for (SjbcPackaging.Entry entry : stage.getEntries()) {
            File out = new File(targetDir, entry.getRelativePath());
            File parent = out.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("无法创建 SJBC 子目录：" + parent);
            }
            byte[] bytes = PFiles.readBytes(entry.getSource().getPath());
            try (FileOutputStream output = new FileOutputStream(out)) {
                output.write(bytes);
            }
            if (entry.getRelativePath().endsWith(".sjbc")) {
                sjbcNames.add(entry.getRelativePath());
            } else if (entry.getRelativePath().equals("manifest.json")) {
                manifestText = new String(bytes, "UTF-8");
            }
        }
        try {
            SjbcPackaging.PatchResult patch = SjbcPackaging.patchManifest(
                    manifestText, certSha, mAppConfig.getPackageName(), sjbcNames);
            try (FileOutputStream output = new FileOutputStream(new File(targetDir, "manifest.json"))) {
                output.write(patch.getJson().getBytes("UTF-8"));
            }
            if (patch.getWarning() != null) {
                Log.w("C-PKG", patch.getWarning());
                mPackagingWarnings.add(patch.getWarning());
            }
        } catch (Throwable error) {
            Log.w("C-PKG", "sjbc: manifest 对齐失败（保留原文件）：" + error.getMessage());
        }
        Log.i("C-PKG", SjbcPackaging.describe(stage, certSha, mAppConfig.getPackageName()));
    }

    private void enforcePackagingPolicy() throws IOException {
        File projectRoot = new File(mWorkspacePath, "assets/project");
        JSONObject project = null;
        File projectJson = new File(projectRoot, "project.json");
        if (projectJson.isFile()) {
            try {
                project = new JSONObject(new String(PFiles.readBytes(projectJson.getPath()), "UTF-8"));
            } catch (Throwable error) {
                Log.w("C-PKG", "读取 project.json 失败，按默认策略处理：" + error.getMessage());
            }
        }
        boolean removeOpenCv = false;
        boolean removeOcr = false;
        List<String> plugins = new ArrayList<>();
        List<String> declaredAbis = new ArrayList<>();
        if (project != null) {
            JSONObject optimization = project.optJSONObject("optimization");
            removeOpenCv = optimization != null && optimization.optBoolean("removeOpenCv", false);
            // task-101：与 removeOpenCv 同形；未设置 = false（默认产物逐条不变）。
            removeOcr = optimization != null && optimization.optBoolean("removeOcr", false);
            JSONObject pluginConfig = project.optJSONObject("plugins");
            if (pluginConfig != null) {
                java.util.Iterator<String> keys = pluginConfig.keys();
                while (keys.hasNext()) plugins.add(keys.next());
            }
            org.json.JSONArray abis = project.optJSONArray("abis");
            if (abis != null) {
                for (int index = 0; index < abis.length(); index++) declaredAbis.add(String.valueOf(abis.opt(index)));
            }
        }
        boolean usesPuzzleEngine = projectReferencesPuzzleEngine(projectRoot);
        NativeLibPolicy.ProjectNeeds needs = new NativeLibPolicy.ProjectNeeds(removeOpenCv, plugins, usesPuzzleEngine, removeOcr);

        File libRoot = new File(mWorkspacePath, "lib");
        List<String> availableAbis = new ArrayList<>();
        org.json.JSONArray droppedEntries = new org.json.JSONArray();
        int dropped = 0;
        File[] abiDirs = libRoot.isDirectory() ? libRoot.listFiles() : null;
        if (abiDirs != null) {
            for (File abiDir : abiDirs) {
                if (abiDir == null || !abiDir.isDirectory()) continue;
                File[] libs = abiDir.listFiles();
                boolean hasAny = false;
                if (libs != null) {
                    for (File lib : libs) {
                        if (lib == null || !lib.isFile()) continue;
                        String entry = "lib/" + abiDir.getName() + "/" + lib.getName();
                        NativeLibPolicy.Verdict verdict = NativeLibPolicy.classify(entry, needs);
                        if (verdict.getDecision() == NativeLibPolicy.Decision.DROP) {
                            //noinspection ResultOfMethodCallIgnored
                            lib.delete();
                            dropped++;
                            droppedEntries.put(entry + " :: " + verdict.getReason());
                            Log.i("C-PKG", "native lib dropped " + entry + " reason=" + verdict.getReason());
                        } else {
                            hasAny = true;
                        }
                    }
                }
                if (hasAny) availableAbis.add(abiDir.getName());
            }
        }
        if (dropped > 0) {
            Log.i("C-PKG", "packaging policy dropped native libs=" + dropped
                    + " (removeOpenCv=" + removeOpenCv + " removeOcr=" + removeOcr + " plugins=" + plugins + ")");
        }
        report("droppedNativeLibs", droppedEntries);
        try {
            // task-63 ③（Lead 定夺两档）：至少一个可产出 → 产出 + 显式 warning；一个都产不出 → 硬报错。
            AbiPolicy.Resolution resolution = AbiPolicy.resolve(declaredAbis, availableAbis);
            Log.i("C-PKG", resolution.logLine() + " note=" + resolution.getNote());
            report("abisDeclared", resolution.getDeclared());
            report("abisProduced", resolution.getProduced());
            report("abisMissing", resolution.getMissing());
            if (resolution.getWarning() != null) {
                // task-85 ②：同一条 ABI 结论在同一进程里只 W 一次，重复时降为 INFO（事实仍写进报告，不静默）。
                String signature = AbiPolicy.signature(resolution);
                String level = AbiPolicy.warningLevel(sLastAbisWarningSignature, resolution);
                if ("WARN".equals(level)) {
                    Log.w("C-PKG", "abis warning: " + resolution.getWarning());
                    mPackagingWarnings.add("abis: " + resolution.getWarning());
                    sLastAbisWarningSignature = signature;
                } else {
                    Log.i("C-PKG", "abis missing=[" + resolution.getMissing() + "] produced=[" + resolution.getProduced()
                            + "]（同一结论已提示过，降为 INFO；事实仍在报告里）");
                }
            }
        } catch (IllegalArgumentException error) {
            throw new IOException(error.getMessage(), error);
        }
        // task-63 ④（Lead 定夺）：插件只做显式回显 + 不静默，不按 Pro 形态带插件 APK。
        Log.i("C-PKG", NativeLibPolicy.pluginSummaryLine(plugins));
        report("plugins", plugins);
        org.json.JSONArray pluginCoverage = new org.json.JSONArray();
        for (NativeLibPolicy.PluginCoverage coverage : NativeLibPolicy.pluginCoverage(plugins, removeOcr)) {
            JSONObject row = new JSONObject();
            try {
                row.put("plugin", coverage.getPlugin());
                row.put("coveredBy", coverage.getCoveredBy() == null ? JSONObject.NULL : coverage.getCoveredBy());
                row.put("note", coverage.getNote());
            } catch (Throwable ignored) {
                // report 只是可观测，不因它失败而中断打包
            }
            pluginCoverage.put(row);
            if (coverage.getCoveredBy() == null) {
                Log.w("C-PKG", "plugin not covered: " + coverage.getPlugin() + " - " + coverage.getNote());
                mPackagingWarnings.add("plugin " + coverage.getPlugin() + ": " + coverage.getNote());
            } else {
                Log.i("C-PKG", "plugin " + coverage.getPlugin() + " coveredBy=" + coverage.getCoveredBy());
            }
        }
        report("pluginCoverage", pluginCoverage);
        File keystore = new File(mWorkspacePath, "assets/autojs.keystore");
        if (keystore.isFile()) {
            long size = keystore.length();
            //noinspection ResultOfMethodCallIgnored
            keystore.delete();
            Log.i("C-PKG", "removed bundled signing identity assets/autojs.keystore (" + size
                    + " B)：第三方打包器旧默认密钥，绝不能随产物分发");
            report("keystoreRemoved", size);
        }
    }

    /** 工程（被复制进包的文本）里是否提到象棋引擎：命中才需要 libpikafish.so。 */
    private static boolean projectReferencesPuzzleEngine(File projectRoot) {
        if (!projectRoot.isDirectory()) return false;
        java.util.ArrayDeque<File> stack = new java.util.ArrayDeque<>();
        stack.add(projectRoot);
        int visited = 0;
        while (!stack.isEmpty() && visited < 512) {
            File current = stack.removeLast();
            File[] children = current.listFiles();
            if (children == null) continue;
            for (File child : children) {
                visited++;
                if (visited > 512) break;
                if (child.isDirectory()) {
                    stack.add(child);
                } else if (child.length() <= 512 * 1024) {
                    String name = child.getName().toLowerCase(Locale.ROOT);
                    if (name.endsWith(".apk") || name.endsWith(".zip") || name.endsWith(".so")) continue;
                    try {
                        String text = new String(PFiles.readBytes(child.getPath()), "UTF-8").toLowerCase(Locale.ROOT);
                        if (text.contains("pikafish") || text.contains("xiangqi")) return true;
                    } catch (Throwable ignored) {
                        // 二进制/读不了的文件跳过
                    }
                }
            }
        }
        return false;
    }

    private void copyProjectToWorkspace() throws Exception {
        if (mAppConfig == null || mAppConfig.sourcePath == null) {
            if (mScriptFile != null) {
                syncProjectJsonAndDeriveKeys();
                writeEntryScript(new File(mScriptFile));
            }
            return;
        }
        File source = new File(mAppConfig.sourcePath);
        if (source.isDirectory()) {
            // task-63 ②：默认排除「打包产物目录」（工程根 build/ + 产物目录若在工程内 + 显式 ignoredDirs），
            // 判据与理由见 PackageOutputExclusions（device4 实测：产物在工程外时旧逻辑什么都没排 → +59MB 套娃）。
            java.util.Set<String> excluded = PackageOutputExclusions.projectExclusions(
                    source, mOutApkFile, mAppConfig.ignoredDirs == null ? null : mAppConfig.ignoredDirs.stream()
                            .map(String::valueOf).collect(java.util.stream.Collectors.toList()));
            if (!excluded.isEmpty()) {
                Log.i("C-PKG", "project copy excludes=" + PackageOutputExclusions.describe(excluded, source));
                report("excludedProjectPaths", new org.json.JSONArray(excluded));
            }
            copyDir(source.getPath(), mWorkspacePath + "/assets/project", excluded);
            syncProjectJsonAndDeriveKeys();
            writeEntryScript(new File(mWorkspacePath, "assets/project/" + mMainScriptFile));
            // 除入口外的所有脚本收进加密容器：解包 APK 不该能读到工程源码。
            packProjectScripts();
        } else {
            syncProjectJsonAndDeriveKeys();
            writeEntryScript(source);
            if (mAppConfig.ignoredDirs != null) {
                for (Object ignored : mAppConfig.ignoredDirs) {
                    new File(mWorkspacePath, "assets/project/" + ignored).delete();
                }
            }
        }
    }

    /**
     * 按当前等级写入入口脚本：0 = 明文拷贝，1 = 加密，≥ 2 = 先编译再加密。
     *
     * <p>Node 没有可搬运的字节码（见 {@link NodeScriptPayload} 的说明），等级 2 对 Node
     * 产出的是「自解包加载器」——同样源码不可读、同样 AES 加密，但不是 V8 字节码；
     * 这里把实际做法记进日志，避免用户以为拿到了和 Rhino 一样的东西。
     *
     * <p>选了 {@code scriptStorage=native} 时载荷不写成文件，而是追加到工作区里各个 ABI 的
     * {@code libaijscrypto.so} 尾部（产物里一个脚本文件都没有，见 {@link EmbeddedScriptFooter}）。
     */
    private void writeEntryScript(File source) throws Exception {
        JavaScriptFileSource entry = new JavaScriptFileSource(source);
        File projectFile = new File(mWorkspacePath, "assets/project/project.json");
        ProjectConfig projectConfig = ProjectConfig.fromFile(projectFile.getPath());
        String preferredEngine = projectConfig == null
                ? (mAppConfig == null ? null : mAppConfig.engine)
                : projectConfig.getEngine(mMainScriptFile);
        entry.setPreferredEngine(preferredEngine);
        boolean nodeEntry = JavaScriptSource.ENGINE_NODE24.equals(entry.getEngineName());
        if (NodeScriptProtection.mustRejectCompile(entry.getEngineName())
                && ScriptProtection.shouldCompile(mEncryptLevel)) {
            // 目前没有任何引擎会走到这里（Node 用自解包加载器兜住）；留着是为了「既不支持
            // 编译产物、又不允许降级」的组合有单一拒绝点，而不是静默产出跑不起来的包。
            throw new IOException(ScriptProtection.describeChoice(
                    ScriptProtection.choiceOf(mEncryptLevel, mScriptStorage))
                    + "：该引擎没有可搬运的编译产物，请改用普通加密或明文");
        }
        mPayloadKind = NodeScriptProtection.payloadKindOf(
                mEncryptLevel, nodeEntry, isQuickJsEngine());
        JSONObject project = projectFile.isFile()
                ? new JSONObject(new String(PFiles.readBytes(projectFile.getPath()), "UTF-8")) : null;
        if (isNodeJsDisabled(project)) {
            // task-101：features.nodejs=disabled ⇒ 运行资产已在 pruneDisabledPackagingAssets() 里剪掉，
            // 这里必须「跳过并说明」，既不去宿主补一份、也不把缺失当失败（否则工程直接打不出包）。
            Log.i("C-PKG", "node24 runtime copy skipped (nodejs=disabled): assets/node24/** 与 "
                    + "assets/source-engine-upgrade/** 不随包；node24 相关校验一并跳过");
        } else if (Node24PackagingAssets.requiresRuntime(nodeEntry, project)) {
            File hostApk = hostApkFile(GlobalAppContext.get());
            File workspace = new File(mWorkspacePath);
            // 先记下模板自带的 Node 运行时哈希，再让宿主的运行资产覆盖它——否则漂移就看不出来了。
            java.util.Map<String, String> templateRuntime =
                    Node24TemplateConsistency.readWorkspaceRuntimeHashes(workspace);
            Node24PackagingAssets.copyFromHost(hostApk, workspace);
            // 模板与宿主可能不是同一版运行时（旧外置模板 / 手动放到 /sdcard 的模板）。
            // Node 的运行库与 runtime.json 在这里对账：内容对不上就带原因失败，
            // 只是版本漂移（已被宿主覆盖）就记日志。
            // 入口确实是 Node 脚本（或工程 type=node）时，运行资产不完整 = 产物必崩，直接失败；
            // 只开了 nodejs 特性位的工程按旧行为只告警，不把本来能出的包打死。
            boolean strictRuntime = nodeEntry || Node24PackagingAssets.isNodeProject(project);
            Node24TemplateConsistency.verifyWorkspace(hostApk, workspace, templateRuntime,
                    strictRuntime);
        }
        File target = new File(mWorkspacePath, "assets/project/" + mMainScriptFile);
        if (!ScriptProtection.shouldEncrypt(mEncryptLevel)) {
            // 目录工程时入口脚本已经在工作区里了：自己拷自己会先把目标清空，直接跳过。
            if (source.getCanonicalFile().equals(target.getCanonicalFile())) {
                return;
            }
            copyFile(source, target);
            return;
        }
        byte[] sourceBytes = PFiles.readBytes(source.getPath());
        String sourceText = new String(sourceBytes, "UTF-8");
        short modeFlags = (short) (new StringScriptSource(source.getName(), sourceText)
                .getExecutionMode() & 0xFF);
        byte[] payload;
        short flags;
        if (mPayloadKind == NodeScriptProtection.PayloadKind.RHINO_CLASS
                || mPayloadKind == NodeScriptProtection.PayloadKind.QUICKJS_BYTECODE
                || mPayloadKind == NodeScriptProtection.PayloadKind.SOURCE_FREE_TEXT) {
            if (nodeEntry) {
                // Node：没有可搬运的 V8 字节码，等级 2 = 自解包加载器（源码不可读，仍走 AES）。
                // 载荷类型保持 TEXT，运行端解密后按普通源码执行，不引入新的引擎常量依赖。
                payload = NodeScriptPayload.wrap(sourceText, source.getName()).getBytes("UTF-8");
                flags = modeFlags;
                Log.i("C-PKG", "node level=2 payload=source-free-wrapper bytes=" + payload.length
                        + " (非 V8 字节码，见 NodeScriptPayload 说明)");
            } else if (isQuickJsEngine()) {
                // QuickJS 工程：编译成 QuickJS 字节码（载荷类型 2）。
                payload = QuickJsBytecodeCompiler.compile(sourceText, source.getName());
                flags = EncryptedScriptFileHeader.INSTANCE.flagsWithPayloadType(
                        EncryptedScriptFileHeader.PAYLOAD_TYPE_QUICKJS_BYTECODE, modeFlags);
            } else {
                payload = compileEntryScript(source);
                flags = EncryptedScriptFileHeader.INSTANCE.flagsWithPayloadType(
                        EncryptedScriptFileHeader.PAYLOAD_TYPE_RHINO_CLASS, modeFlags);
            }
        } else {
            payload = sourceBytes;
            flags = modeFlags;
        }
        if (ScriptProtection.usesNativeStorage(mScriptStorage) && mEncryptLevel > 0) {
            // task-127：原生加载器只懂 v1/CBC，v2 是 GCM ⇒ 明确失败，绝不静默降级成 v1。
            throw new IOException(ScriptPayloadV2.NATIVE_UNSUPPORTED_MESSAGE);
        }
        byte[] encrypted = encryptedPayload(payload, flags);
        if (ScriptProtection.usesNativeStorage(mScriptStorage)) {
            // 工作区里已经拷进来的入口脚本必须删掉：藏了就要藏干净。
            if (target.exists()) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
            }
            embedScriptIntoLibraries(encrypted);
            return;
        }
        writeBytes(target, encrypted);
    }

    /** 把脚本载荷追加到工作区里每个 ABI 的 libaijscrypto.so 尾部。 */
    private void embedScriptIntoLibraries(byte[] payload) throws IOException {
        File libRoot = new File(mWorkspacePath, "lib");
        File[] abiDirs = libRoot.listFiles();
        int embedded = 0;
        if (abiDirs != null) {
            for (File abiDir : abiDirs) {
                if (!abiDir.isDirectory()) {
                    continue;
                }
                File library = new File(abiDir, EMBEDDED_SCRIPT_LIBRARY);
                if (!library.isFile()) {
                    continue;
                }
                byte[] libraryBytes = PFiles.readBytes(library.getPath());
                byte[] withPayload = EmbeddedScriptFooter.embed(libraryBytes, payload);
                writeBytes(library, withPayload);
                embedded++;
            }
        }
        if (embedded == 0) {
            // 模板里没有解密库就说明产物跑不起来，宁可打包失败也不要出一个必然闪退的包。
            throw new IOException("模板里没有 " + EMBEDDED_SCRIPT_LIBRARY + "，无法内嵌脚本");
        }
    }

    /** 打包的产物使用 QuickJS 引擎（此时不能写 Rhino 编译产物）。 */
    private boolean isQuickJsEngine() {
        return mAppConfig != null && "quickjs".equalsIgnoreCase(mAppConfig.engine);
    }

    /**
     * 目录工程：把除入口脚本外的所有 .js 打成 zip、整体加密成
     * {@link ScriptProtection#SCRIPT_PACK_FILE}，再把原文件从工作区删掉。
     *
     * <p>以前只有入口脚本受保护，{@code src/**}、{@code tools/**} 这类成百上千的脚本
     * 是明文拷进 {@code assets/project/} 的——解包 APK 就能读到全部源码。现在产物里
     * 只剩一个加密容器，运行端启动时先解密再展开到私有目录
     * （{@code AssetsProjectLauncher.prepare()}），模块加载看到的布局不变。
     */
    private void packProjectScripts() throws IOException {
        if (!ScriptProtection.shouldEncrypt(mEncryptLevel)) {
            return;
        }
        File projectDir = new File(mWorkspacePath, "assets/project");
        List<File> scripts = new ArrayList<>();
        collectScriptFiles(projectDir, scripts);
        // 入口脚本自己已经处理过（加密写文件或嵌进原生库），不进容器。
        String entryPath = new File(projectDir, mMainScriptFile).getCanonicalPath();
        for (int i = scripts.size() - 1; i >= 0; i--) {
            if (scripts.get(i).getCanonicalPath().equals(entryPath)) {
                scripts.remove(i);
            }
        }
        if (scripts.isEmpty()) {
            return;
        }
        byte[] archive = zipScripts(projectDir, scripts);
        short flags = EncryptedScriptFileHeader.INSTANCE.flagsWithPayloadType(
                EncryptedScriptFileHeader.PAYLOAD_TYPE_SCRIPT_PACK);
        writeBytes(new File(projectDir, ScriptProtection.SCRIPT_PACK_FILE),
                encryptedPayload(archive, flags));
        for (File script : scripts) {
            //noinspection ResultOfMethodCallIgnored
            script.delete();
        }
        pruneEmptyDirs(projectDir);
    }

    private static void collectScriptFiles(File dir, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collectScriptFiles(child, out);
            } else if (child.getName().toLowerCase(Locale.ROOT).endsWith(".js")) {
                out.add(child);
            }
        }
    }

    /** 组一个只含脚本的 zip，条目名就是脚本相对工程目录的路径（/ 分隔）。 */
    private static byte[] zipScripts(File projectDir, List<File> scripts) throws IOException {
        String base = projectDir.getAbsolutePath() + File.separator;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(buffer);
        try {
            byte[] chunk = new byte[8192];
            for (File script : scripts) {
                String path = script.getAbsolutePath();
                if (!path.startsWith(base)) {
                    throw new IOException("脚本不在工程目录里：" + path);
                }
                ZipEntry entry = new ZipEntry(
                        path.substring(base.length()).replace(File.separatorChar, '/'));
                entry.setTime(script.lastModified());
                zip.putNextEntry(entry);
                InputStream in = new FileInputStream(script);
                try {
                    int read;
                    while ((read = in.read(chunk)) > 0) {
                        zip.write(chunk, 0, read);
                    }
                } finally {
                    in.close();
                }
                zip.closeEntry();
            }
        } finally {
            zip.close();
        }
        return buffer.toByteArray();
    }

    /** 脚本全被收进容器后，把空掉的目录骨架删掉（保留工程根目录本身）。 */
    private static void pruneEmptyDirs(File dir) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (!child.isDirectory()) {
                continue;
            }
            pruneEmptyDirs(child);
            File[] remaining = child.listFiles();
            if (remaining != null && remaining.length == 0) {
                //noinspection ResultOfMethodCallIgnored
                child.delete();
            }
        }
    }

    /**
     * 把脚本编译成 class 字节并打包成编译载荷。
     *
     * <p>借用一个「记录字节 + 正常加载」的类加载器（{@link RecordingClassLoader}）：
     * Rhino 编译出类时会通过 defineClass 把字节交给我们，一边存一边照常加载。
     */
    private byte[] compileEntryScript(File source) throws Exception {
        String text = new String(PFiles.readBytes(source.getPath()), "UTF-8");
        Map<String, byte[]> sink = new LinkedHashMap<>();
        File cacheDir = new File(mWorkspacePath, "compiled-classes");
        RecordingClassLoader loader = new RecordingClassLoader(
                ApkBuilder.class.getClassLoader(), cacheDir, sink);
        ScriptCompiler.CompiledClass compiled = ScriptCompiler.compile(text,
                source.getName(), ScriptCompiler.factoryFor(loader), sink);
        return new CompiledScriptPayload(compiled.className, compiled.bytes).toBytes();
    }

    /** 把载荷加密后写入目标文件（不加密时不会走到这里，见 {@link #writeEntryScript}）。 */
    private void writeEncrypted(File target, byte[] plain, short flags) throws IOException {
        writeBytes(target, encryptedPayload(plain, flags));
    }

    /**
     * 生成完整 v2 加密载荷（写文件与嵌进原生库共用同一份）。
     *
     * <p>task-127：头与 IV 都由 {@link ScriptPayloadV2#encrypt} 内部写（IV 每文件随机）；
     * flags 按 v1 的字节布局传：header[6]=载荷类型（高字节）、header[7]=执行模式（低字节）。</p>
     */
    private byte[] encryptedPayload(byte[] plain, short flags) throws IOException {
        if (mKeyV2 == null) {
            throw new IllegalStateException("Script encryption key is not initialized");
        }
        try {
            int modeLow = flags & 0xFF;
            int payloadTypeHigh = (flags >> 8) & 0xFF;
            return ScriptPayloadV2.encrypt(plain, mKeyV2, payloadTypeHigh, modeLow);
        } catch (Exception e) {
            throw new IOException("Failed to encrypt script (v2/AES-GCM)", e);
        }
    }

    private static void writeBytes(File target, byte[] bytes) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        FileOutputStream fos = new FileOutputStream(target);
        try {
            fos.write(bytes);
        } finally {
            fos.close();
        }
    }

    /** 记录 Rhino 编译产物的类加载器（先存字节，再交给 AndroidClassLoader 正常加载）。 */
    private static final class RecordingClassLoader extends AndroidClassLoader {
        private final Map<String, byte[]> mSink;

        RecordingClassLoader(ClassLoader parent, File cacheDir, Map<String, byte[]> sink) {
            super(parent, cacheDir);
            mSink = sink;
        }

        @Override
        public Class<?> defineClass(String name, byte[] data) {
            mSink.put(name, data);
            return super.defineClass(name, data);
        }
    }

    /** 明文拷贝（不加密时的分支，目录不存在则先建）。 */
    private void copyFile(File input, File output) throws IOException {
        File parent = output.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        StreamUtils.write(new FileInputStream(input), new FileOutputStream(output));
    }

    /** 本次打包最终生效的脚本保护等级（供测试与日志使用）。 */
    public int getEncryptLevel() {
        return mEncryptLevel;
    }

    /** 脚本存放位置：{@code assets}（脚本文件）或 {@code native}（嵌进原生库）。 */
    public String getScriptStorage() {
        return mScriptStorage;
    }

    /** 本次打包写入产物的随机盐（未使用新方案时为 null）。 */
    public String getScriptSalt() {
        return mScriptSalt;
    }

    /** 本次打包写入产物的签名证书指纹（未使用新方案时为 null）。 */
    public String getSignatureFingerprint() {
        return mSignatureFingerprint;
    }

    /**
     * 解析本次产物将要使用的签名证书指纹。
     *
     * <p>优先用页面选的密钥库（{@link AppConfig#setSigningCertificateFingerprint}），
     * 否则用本机自动身份（{@link AutoSigningIdentity}）——两者与 {@code sign()} 阶段
     * 实际使用的签名必须一致，否则产物自己都解不开脚本。
     */
    private String resolveSigningCertificateFingerprint() {
        if (mAppConfig != null && ScriptKeyDerivation.hasCertificateFingerprint(
                mAppConfig.signingCertificateFingerprint)) {
            return mAppConfig.signingCertificateFingerprint;
        }
        try {
            return AutoSigningIdentity.INSTANCE.certificateFingerprint();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Copies the user-selected splash image into the packaged assets. The inrt runtime
     * picks it up from {@code assets/project/splash.png} when the splash screen is shown.
     */
    private void copySplashIcon() throws IOException {
        if (mAppConfig == null || mAppConfig.splashIconPath == null) {
            return;
        }
        File target = new File(mWorkspacePath, SPLASH_ASSET_PATH);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        StreamUtils.write(new FileInputStream(mAppConfig.splashIconPath),
                new FileOutputStream(target));
    }

    private void notifyPrepare() {
        if (mProgressCallback != null) {
            mProgressCallback.onPrepare(this);
        }
    }

    private void notifyBuild() {
        if (mProgressCallback != null) {
            mProgressCallback.onBuild(this);
        }
    }

    private void notifySign() {
        if (mProgressCallback != null) {
            mProgressCallback.onSign(this);
        }
    }

    private void notifyClean() {
        if (mProgressCallback != null) {
            mProgressCallback.onClean(this);
        }
    }

    private void updateProjectConfig(AppConfig config) throws IOException {
        if (mManifestEditor == null) {
            return;
        }
        if (config.appName != null) {
            mManifestEditor.setAppName(config.appName);
        }
        if (config.packageName != null) {
            mManifestEditor.setPackageName(config.packageName);
        }
        if (config.versionCode != -1) {
            mManifestEditor.setVersionCode(config.versionCode);
        }
        if (config.versionName != null) {
            mManifestEditor.setVersionName(config.versionName);
        }
        if (config.icon != null) {
            try {
                Bitmap bitmap = config.icon.call();
                if (bitmap == null) throw new IOException("项目图标不存在或无法解码，请重新选择图标");
                replaceIcon(bitmap);
            } catch (Exception e) {
                throw new UncheckedIOException(new IOException("Failed to replace icon", e));
            }
        }
        if (config.permissionsToAdd != null) {
            mManifestEditor.setPermissionsToAdd(config.permissionsToAdd);
        }
        if (config.permissionsToRemove != null) {
            mManifestEditor.setPermissionsToRemove(config.permissionsToRemove);
        }
        if (!config.includeAccessibility) {
            mManifestEditor.setComponentsToRemove(
                    Arrays.asList(ACCESSIBILITY_SERVICE, LEGACY_ACCESSIBILITY_SERVICE));
        }
    }

    /**
     * Replaces every launcher icon bitmap the template ships (density and shape variants)
     * instead of a single hardcoded path, so the packaged app picks up the user icon
     * regardless of how aapt2 laid the resources out.
     */
    private void replaceIcon(Bitmap bitmap) throws IOException {
        if (bitmap == null) {
            return;
        }
        List<File> targets = new ArrayList<>();
        collectLauncherIcons(new File(mWorkspacePath, "res"), targets);
        if (targets.isEmpty()) {
            targets.add(new File(mWorkspacePath, "res/mipmap-mdpi/ic_launcher.png"));
        }
        for (File target : targets) {
            File parent = target.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            FileOutputStream out = new FileOutputStream(target);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            out.close();
        }
    }

    private void collectLauncherIcons(File dir, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collectLauncherIcons(child, out);
            } else if (isLauncherIconFile(child.getName())) {
                out.add(child);
            }
        }
    }

    private static boolean isLauncherIconFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.startsWith("ic_launcher") && lower.endsWith(".png");
    }

    private void buildArsc() {
        try {
            File arsc = new File(mWorkspacePath, "resources.arsc");
            if (mArscPackageName == null || mArscPackageName.isEmpty() || !arsc.isFile()) {
                Log.i("C-PKG", "arsc rewrite skipped package=" + mArscPackageName + " exists=" + arsc.isFile());
                return;
            }
            byte[] data = readAll(arsc);
            int offset = 12; // ResTable_header: type(2) headerSize(2) size(4) packageCount(4)
            boolean rewritten = false;
            while (offset + 12 <= data.length) {
                int type = readU16(data, offset);
                int headerSize = readU16(data, offset + 2);
                long chunkSize = readU32(data, offset + 4);
                if (chunkSize <= 0 || offset + chunkSize > data.length) break;
                if (type == 0x0200) { // RES_TABLE_PACKAGE_TYPE
                    // package header: ... id(4) name[128 UTF-16LE](256)
                    int nameOffset = offset + 12;
                    byte[] name = mArscPackageName.getBytes("UTF-16LE");
                    int max = Math.min(name.length, 254);
                    for (int i = 0; i < 256; i++) {
                        data[nameOffset + i] = i < max ? name[i] : 0;
                    }
                    rewritten = true;
                    break;
                }
                offset += (int) chunkSize;
            }
            if (!rewritten) {
                Log.w("C-PKG", "arsc package chunk not found; suppress package rewrite");
                return;
            }
            writeAll(arsc, data);
            Log.i("C-PKG", "arsc package=" + mArscPackageName + " rewritten bytes=" + data.length);
        } catch (Throwable error) {
            Log.w("C-PKG", "arsc rewrite failed", error);
        }
    }

    private static int readU16(byte[] data, int offset) {
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
    }

    private static long readU32(byte[] data, int offset) {
        return (data[offset] & 0xffL) | ((data[offset + 1] & 0xffL) << 8)
                | ((data[offset + 2] & 0xffL) << 16) | ((data[offset + 3] & 0xffL) << 24);
    }

    private static byte[] readAll(File file) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (java.io.FileInputStream input = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) > 0) out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static void writeAll(File file, byte[] data) throws Exception {
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(file)) {
            output.write(data);
        }
    }


    /**
     * 工程目录里属于「打包产物」的子目录（默认 `build/`）不能进 assets：
     * 一是会把上一次的 APK 塞进新包（体积翻倍），二是 inrt 启动器复制资产时
     * 会把 `project/build` 当文件打开 —— `FileNotFoundException: project/build`，产物一装就崩。
     * 只有产物目录确实在工程目录里面时才排除。
     */
    private File excludedOutputDir(File sourceDir) throws IOException {
        if (mOutApkFile == null) {
            return null;
        }
        File outputDir = mOutApkFile.getParentFile();
        if (outputDir == null) {
            return null;
        }
        String source = sourceDir.getCanonicalPath();
        String output = outputDir.getCanonicalPath();
        return output.startsWith(source + File.separator) ? outputDir : null;
    }

    private void copyDir(String src, String dst) throws IOException {
        copyDir(src, dst, null);
    }

    private void copyDir(String src, String dst, java.util.Set<String> excluded) throws IOException {
        File srcDir = new File(src);
        File[] children = srcDir.listFiles();
        if (children == null) {
            return;
        }
        // 目标目录必须自己建：以前只会被「上层先写了文件」顺带创建，工程里出现
        // 二级子目录（如 assets/models/）时就会 FileNotFoundException: ENOENT。
        File dstDir = new File(dst);
        if (!dstDir.isDirectory() && !dstDir.mkdirs()) {
            throw new IOException("无法创建目录：" + dst);
        }
        // 排除项必须按规范路径比较：从 /sdcard/... 这类符号链接路径打开工程时，
        // child 的规范路径是 /storage/emulated/0/...，直接比字符串会漏排除，
        // 结果把上一次的产物 APK 又打进新包（曾经产出过一个翻倍的包）。
        for (File child : children) {
            if (excluded != null && !excluded.isEmpty()
                    && excluded.contains(child.getCanonicalFile().getPath())) {
                continue;
            }
            if (child.isDirectory()) {
                copyDir(child.getPath(), dst + "/" + child.getName(), excluded);
            } else {
                FileOutputStream out = new FileOutputStream(new File(dstDir, child.getName()));
                FileInputStream in = new FileInputStream(child);
                StreamUtils.write(in, out);
            }
        }
    }

    private void encrypt(File input, File output) throws IOException {
        byte[] encrypted = encryptBytes(input);
        File parent = output.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        FileOutputStream fos = new FileOutputStream(output);
        fos.write(encrypted);
        fos.close();
    }

    private void encrypt(FileOutputStream outputStream, File file) throws IOException {
        outputStream.write(encryptBytes(file));
    }

    private byte[] encryptBytes(File file) throws IOException {
        if (mKeyV2 == null) {
            throw new IllegalStateException("Script encryption key is not initialized");
        }
        try {
            byte[] plain = PFiles.readBytes(file.getPath());
            // task-127：v2（AES-256-GCM，IV 随机）；这里 flags 全 0（执行模式 0 / 载荷类型 0）。
            return ScriptPayloadV2.encrypt(plain, mKeyV2, 0, 0);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to encrypt script: " + file, e);
        }
    }

    /**
     * Syncs {@code assets/project/project.json} with the packaged app identity and derives
     * the script encryption key exactly like the runtime does for v2:
     * {@code key = ScriptPayloadV2.deriveKeyV2(packageName, scriptSalt, 证书指纹)}（真 32 字节）。
     * The runtime reads the same project.json (written here) and derives the same key, so both
     * ends must stay in sync. task-127：旧的 MD5 方案已删除（运行端不再支持）。
     */
    private void syncProjectJsonAndDeriveKeys() throws IOException {
        File jsonFile = new File(mWorkspacePath, "assets/project/project.json");
        try {
            JSONObject json;
            if (jsonFile.exists()) {
                json = new JSONObject(new String(PFiles.readBytes(jsonFile.getPath()), "UTF-8"));
            } else {
                json = new JSONObject();
            }
            AppConfig config = mAppConfig;
            if (config != null) {
                if (config.appName != null) {
                    json.put("name", config.appName);
                }
                if (config.packageName != null) {
                    json.put("packageName", config.packageName);
                }
                if (config.versionName != null) {
                    json.put("versionName", config.versionName);
                }
                if (config.versionCode != -1) {
                    json.put("versionCode", config.versionCode);
                }
                if (config.engine != null && config.engine.length() > 0) {
                    json.put("engine", config.engine);
                }
                // Runtime behaviour of the packaged app. The inrt launcher reads these
                // values back (AssetsProjectLauncher / SplashActivity).
                JSONObject launchConfig = json.optJSONObject("launchConfig");
                if (launchConfig == null) {
                    launchConfig = new JSONObject();
                }
                launchConfig.put("hideLogs", config.hideLogs);
                launchConfig.put("logBridge", config.logBridge);
                launchConfig.put("showSplash", config.showSplash);
                if (config.requestPermissions != null && !config.requestPermissions.isEmpty()) {
                    JSONArray requestPermissions = new JSONArray();
                    for (String permission : config.requestPermissions) {
                        if (permission != null && permission.length() > 0) {
                            requestPermissions.put(permission);
                        }
                    }
                    launchConfig.put("requestPermissions", requestPermissions);
                } else {
                    launchConfig.remove("requestPermissions");
                }
                if (config.splashText != null && config.splashText.length() > 0) {
                    launchConfig.put("splashText", config.splashText);
                } else {
                    launchConfig.remove("splashText");
                }
                json.put("launchConfig", launchConfig);
            }
            if (!json.has("name")) {
                json.put("name", "");
            }
            if (!json.has("packageName")) {
                json.put("packageName", "");
            }
            if (!json.has("versionName")) {
                json.put("versionName", "");
            }
            mMainScriptFile = json.optString("main", "main.js");
            json.put("main", mMainScriptFile);

            // 脚本保护等级：工程里写了就以工程为准，打包页显式选过则以页面控件为准（-1 = 未指定）。
            // 写回 project.json 是为了让产物里的配置与实际行为一致（运行端读回时能看到真实等级）。
            int level = json.optInt("encryptLevel", ScriptProtection.DEFAULT_LEVEL);
            if (mAppConfig != null && mAppConfig.encryptLevel >= 0) {
                level = mAppConfig.encryptLevel;
            }
            mEncryptLevel = ScriptProtection.normalize(level);

            // 脚本存放位置：工程里写了就以工程为准，打包页显式选过以页面为准。
            String storage = json.optString("scriptStorage", ScriptProtection.DEFAULT_STORAGE);
            if (mAppConfig != null && mAppConfig.scriptStorage != null) {
                storage = mAppConfig.scriptStorage;
            }
            mScriptStorage = ScriptProtection.normalizeStorage(storage);
            if (ScriptProtection.usesNativeStorage(mScriptStorage)
                    && !ScriptProtection.shouldEncrypt(mEncryptLevel)) {
                // 嵌进原生库的内容必须是密文（不然和一个明文文件没区别）。
                mEncryptLevel = ScriptProtection.LEVEL_ENCRYPT;
            }
            json.put("encryptLevel", mEncryptLevel);
            json.put("scriptStorage", mScriptStorage);

            JSONObject build = json.optJSONObject("build");
            long buildNumber = 1;
            if (build != null) {
                buildNumber = build.optLong("build_number", 0) + 1;
            }
            BuildInfo buildInfo = BuildInfo.generate(buildNumber);
            JSONObject newBuild = new JSONObject();
            newBuild.put("build_number", buildInfo.getBuildNumber());
            newBuild.put("build_time", buildInfo.getBuildTime());
            newBuild.put("build_id", buildInfo.getBuildId());
            json.put("build", newBuild);

            // task-123：**只用新方案**派生脚本密钥（salt + 签名证书指纹）。
            // 旧的 MD5 方案已从打包端删除：运行端不再支持它，任何「降级产物」都必然解不开
            // ⇒ 宁可在这里明确失败，也不产出打不开的 APK（见下面的 else-if）。
            // 注意：这两个字段必须在写文件之前塞进 json，否则运行端读到的 project.json
            // 里没有盐/指纹，就会（在新方案下）直接点名报错。
            String fingerprint = resolveSigningCertificateFingerprint();
            boolean needsScriptKey = mEncryptLevel > 0;
            if (ScriptKeyDerivation.hasCertificateFingerprint(fingerprint)) {
                String salt = json.optString("scriptSalt", null);
                if (salt == null || salt.isEmpty()) {
                    salt = ScriptKeyDerivation.randomSaltHex();
                }
                // task-127：真 32 字节密钥由引擎侧唯一实现派生（打包端不再自己拼 SHA-256）。
                mKeyV2 = ScriptPayloadV2.deriveKeyV2(json.optString("packageName"), salt, fingerprint);
                // 必然写进产物：运行端就是靠这两个字段派生同一把密钥。
                json.put("scriptSalt", salt);
                json.put("signatureFingerprint", fingerprint);
            } else if (needsScriptKey) {
                // 明确失败而不是降级：降级产物在运行端必然解不开（旧方案已不支持），
                // 静默产出等于给用户一个能装不能跑的 APK。
                throw new IOException("打包需要脚本加密密钥，但拿不到签名证书指纹："
                        + "新方案（scriptSalt + 证书指纹派生）是唯一路径，运行端已不支持旧的 MD5 方案，"
                        + "因此不会静默降级。请确认签名身份可用（重新生成/选择密钥库）后重试。");
            } else {
                // 未产生受保护载荷（encryptLevel=0）：不派生、也不写 salt/指纹（既有语义不变）。
                mKeyV2 = null;
                json.remove("scriptSalt");
                json.remove("signatureFingerprint");
            }
            mScriptSalt = json.optString("scriptSalt", null);
            mSignatureFingerprint = json.optString("signatureFingerprint", null);

            FileOutputStream fos = new FileOutputStream(jsonFile);
            try {
                fos.write(json.toString(2).getBytes("UTF-8"));
            } finally {
                fos.close();
            }
        } catch (JSONException e) {
            throw new IOException("Failed to sync assets/project/project.json", e);
        }
    }

    public ApkBuilder sign() throws Exception {
        notifySign();
        // 产物目录可能还不存在（第一次在某个工程里打包时，工程下还没有 build/ 目录），
        // 以前直接写产物会抛 "open failed: ENOENT (No such file or directory)"。
        // 这里统一先建好目录：选择任意新文件夹当输出目录也能打包成功。
        File outputDir = mOutApkFile.getParentFile();
        if (outputDir == null) {
            throw new IOException("无法确定产物目录：" + mOutApkFile);
        }
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw new IOException("无法创建产物目录：" + outputDir);
        }
        Signer signer = mAppConfig != null ? mAppConfig.getSigner() : null;
        if (signer == null) {
            // 没有显式指定签名就用本机为该应用自动生成的身份。
            // tiny-sign 那份全世界共用的测试证书已经不再使用：它会让所有产物共用一份
            // 私钥，安全软件据此就能把它们归成同一家族。
            signer = AutoSigningIdentity.INSTANCE.signer(outputDir,
                    mAppConfig != null ? mAppConfig.getAppName() : null);
        }
        mApkPackager.setSigner(signer);
        mApkPackager.repackage(mOutApkFile.getPath());
        return this;
    }

    public ApkBuilder cleanWorkspace() {
        notifyClean();
        delete(new File(mWorkspacePath));
        return this;
    }

    private void delete(File file) {
        if (file.isFile()) {
            file.delete();
            return;
        }
        File[] list = file.listFiles();
        if (list != null) {
            for (File child : list) {
                delete(child);
            }
        }
        file.delete();
    }

    public static class AppConfig {
        private String appName;
        private String packageName;
        private String sourcePath;
        private String versionName;
        private int versionCode = -1;
        private Callable<Bitmap> icon;
        private List<String> permissionsToAdd;
        private List<String> permissionsToRemove;
        private List<String> requestPermissions;
        private boolean hideLogs = false;
        /** 打包版是否开放 log-only 调试桥（project.json 的 launchConfig.logBridge，默认关）。 */
        private boolean logBridge = false;
        private boolean showSplash = true;
        private String splashText;
        private String splashIconPath;
        private String engine;
        private boolean includeAccessibility = true;
        private boolean includeImageModule = true;
        /** 脚本保护等级；-1 表示未指定，打包时沿用 project.json 里的 encryptLevel。 */
        private int encryptLevel = -1;

        /** 脚本存放位置；null 表示未指定，打包时沿用 project.json 里的 scriptStorage。 */
        private String scriptStorage;
        /** 用户选的密钥库对应的证书指纹（SHA-256 冒号分隔大写）；null 表示用本机自动身份。 */
        private String signingCertificateFingerprint;
        private Signer signer;
        private final ArrayList<String> ignoredDirs = new ArrayList<>();

        /**
         * 显式指定产物要用哪份签名（用户选的密钥库）。
         *
         * <p>置空表示「自动」：打包时会为本应用生成/复用一份本机专属身份。
         * 无论如何都不会再用 tiny-sign 那份全世界共用的测试证书。
         */
        public AppConfig setSigner(Signer signer) {
            this.signer = signer;
            return this;
        }

        public Signer getSigner() {
            return signer;
        }

        public static AppConfig fromProjectConfig(String source, ProjectConfig projectConfig) {
            AppConfig config = new AppConfig();
            config.sourcePath = source;
            config.appName = projectConfig.getName();
            config.packageName = projectConfig.getPackageName();
            config.versionName = projectConfig.getVersionName();
            config.versionCode = projectConfig.getVersionCode();
            if (projectConfig.getIcon() != null && !projectConfig.getIcon().isEmpty()) {
                File iconFile = new File(projectConfig.getIcon());
                String iconPath = iconFile.isAbsolute() ? iconFile.getPath()
                        : new File(source, projectConfig.getIcon()).getPath();
                config.icon = () -> BitmapFactory.decodeFile(iconPath);
            }
            config.engine = projectConfig.getEngine(null);
            config.encryptLevel = projectConfig.getEncryptLevel();
            config.scriptStorage = projectConfig.getScriptStorage();
            LaunchConfig launchConfig = projectConfig.getLaunchConfig();
            if (launchConfig != null) {
                config.hideLogs = launchConfig.shouldHideLogs();
                config.logBridge = launchConfig.shouldLogBridge();
                config.showSplash = launchConfig.shouldShowSplash();
                config.splashText = launchConfig.getSplashText();
                config.requestPermissions = launchConfig.getRequestPermissions();
            }
            File projectSplash = new File(source, "splash.png");
            if (projectSplash.isFile()) {
                config.splashIconPath = projectSplash.getPath();
            }
            return config;
        }

        public AppConfig setAppName(String appName) {
            this.appName = appName;
            return this;
        }

        public AppConfig setPackageName(String packageName) {
            this.packageName = packageName;
            return this;
        }

        public AppConfig setSourcePath(String sourcePath) {
            this.sourcePath = sourcePath;
            return this;
        }

        public AppConfig setVersionName(String versionName) {
            this.versionName = versionName;
            return this;
        }

        public AppConfig setVersionCode(int versionCode) {
            this.versionCode = versionCode;
            return this;
        }

        public AppConfig setIcon(String iconPath) {
            if (iconPath == null) {
                this.icon = null;
            } else {
                this.icon = () -> BitmapFactory.decodeFile(iconPath);
            }
            return this;
        }

        public AppConfig setIcon(Callable<Bitmap> icon) {
            this.icon = icon;
            return this;
        }

        /**
         * Permissions to declare on top of the template manifest. Pass null to leave the
         * template permission set untouched.
         */
        public AppConfig setPermissionsToAdd(List<String> permissions) {
            this.permissionsToAdd = permissions;
            return this;
        }

        /**
         * Template permissions to drop from the packaged manifest. Pass null to keep all.
         */
        public AppConfig setPermissionsToRemove(List<String> permissions) {
            this.permissionsToRemove = permissions;
            return this;
        }

        /** Skip the log screen and run the script right after launch. */
        public AppConfig setHideLogs(boolean hideLogs) {
            this.hideLogs = hideLogs;
            return this;
        }

        /**
         * 打包版是否提供 log-only 调试桥（VSCode 连 9317 看脚本日志）。默认 false；
         * 打开后打包版只发日志、不接受任何命令。
         */
        public AppConfig setLogBridge(boolean logBridge) {
            this.logBridge = logBridge;
            return this;
        }

        /**
         * Script engine of the packaged app: {@code "rhino"}, {@code "quickjs"}, or {@code "nodejs"};
         * null keeps the runtime default.
         */
        public AppConfig setEngine(String engine) {
            this.engine = engine;
            return this;
        }

        /** When false the packaged app no longer declares the accessibility service. */
        public AppConfig setIncludeAccessibility(boolean includeAccessibility) {
            this.includeAccessibility = includeAccessibility;
            return this;
        }

        /** When false the OpenCV libraries (约 70MB/ABI) are dropped from the package. */
        public AppConfig setIncludeImageModule(boolean includeImageModule) {
            this.includeImageModule = includeImageModule;
            return this;
        }

        /**
         * 脚本保护等级，见 {@link ScriptProtection}：0 不加密、1 AES 加密、2 编译后加密。
         * 不调用则沿用工程 project.json 里的值。
         */
        public AppConfig setEncryptLevel(int encryptLevel) {
            this.encryptLevel = encryptLevel;
            return this;
        }

        public int getEncryptLevel() {
            return encryptLevel;
        }

        /** 脚本存放位置（{@link ScriptProtection#STORAGE_ASSETS} / {@link ScriptProtection#STORAGE_NATIVE}）。 */
        public AppConfig setScriptStorage(String value) {
            this.scriptStorage = ScriptProtection.normalizeStorage(value);
            return this;
        }

        public String getScriptStorage() {
            return scriptStorage;
        }

        /**
         * 本次使用的签名证书指纹（来自用户选的密钥库）。
         * 不调用时用本机自动身份；两者都必须与最终签名一致，否则密钥派生对不上。
         */
        public AppConfig setSigningCertificateFingerprint(String fingerprint) {
            this.signingCertificateFingerprint = fingerprint;
            return this;
        }

        /**
         * Runtime permissions the packaged app requests on start-up. Empty means "keep the
         * runtime default" (storage + phone state).
         */
        public AppConfig setRequestPermissions(List<String> requestPermissions) {
            this.requestPermissions = requestPermissions;
            return this;
        }

        public AppConfig setShowSplash(boolean showSplash) {
            this.showSplash = showSplash;
            return this;
        }

        /** Text shown on the splash screen; null/empty keeps the runtime default. */
        public AppConfig setSplashText(String splashText) {
            this.splashText = splashText;
            return this;
        }

        /** Local image file copied into the package as the splash screen image. */
        public AppConfig setSplashIcon(String splashIconPath) {
            this.splashIconPath = splashIconPath;
            return this;
        }

        public AppConfig ignoreDir(File dir) {
            ignoredDirs.add(dir.getPath());
            return this;
        }

        public String getAppName() {
            return appName;
        }

        public String getPackageName() {
            return packageName;
        }

        public String getSourcePath() {
            return sourcePath;
        }

        public String getVersionName() {
            return versionName;
        }

        public int getVersionCode() {
            return versionCode;
        }
    }

    // small helper matching the third-party StreamUtils semantics without an import cycle
    private static class StreamUtils {
        static void write(InputStream in, FileOutputStream out) throws IOException {
            byte[] buffer = new byte[8192];
            int length;
            while ((length = in.read(buffer)) != -1) {
                out.write(buffer, 0, length);
            }
            in.close();
        }
    }
}
