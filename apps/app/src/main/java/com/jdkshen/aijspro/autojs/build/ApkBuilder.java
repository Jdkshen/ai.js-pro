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
import com.stardust.pio.PFiles;
import com.stardust.pio.UncheckedIOException;
import com.stardust.util.AdvancedEncryptionStandard;
import com.stardust.util.MD5;

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
    // key = MD5(packageName + versionName + mainScriptFile),
    // vector = MD5(buildId + name).substring(0, 16)
    // The inrt runtime derives the same values from the same project.json.
    private String mKey;
    private String mInitVector;
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
        File abiDir = new File(workspace, HOST_LIB_PREFIX);
        File[] existing = abiDir.isDirectory()
                ? abiDir.listFiles((dir, name) -> name != null && name.endsWith(".so")) : null;
        if (existing != null && existing.length > 0) {
            Log.i("C-PKG", "template libs already present count=" + existing.length
                    + " (旧全量模板，跳过从宿主补齐)");
            return 0;
        }
        File hostApk = hostApkFile(context);
        List<String> wanted = loadExcludedLibNames(context, workspace);
        boolean fallbackAll = wanted.isEmpty();
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
                wanted = listHostArm64Libs(zip);
                if (wanted.isEmpty()) {
                    throw new IOException("补全模板 lib 失败：宿主 APK 里没有 lib/arm64-v8a/*.so：" + hostApk);
                }
                Log.i("C-PKG", "excluded-zip-files.json 不可用，兜底扫描宿主 APK libs=" + wanted.size());
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
            throw new IOException("补全模板 lib 失败：清单中的 so 在宿主 APK 中均不存在（"
                    + hostApk + "）");
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
            if (name.startsWith(HOST_LIB_PREFIX) && name.endsWith(".so")) {
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

    private static List<String> listHostArm64Libs(ZipFile zip) {
        List<String> result = new ArrayList<>();
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (!entry.isDirectory() && name.startsWith(HOST_LIB_PREFIX) && name.endsWith(".so")
                    && isRuntimeLibrary(name.substring(HOST_LIB_PREFIX.length()))) {
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
        copyProjectToWorkspace();
        copySplashIcon();
        removeDisabledFeatures();
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
            copyDir(source.getPath(), mWorkspacePath + "/assets/project",
                    excludedOutputDir(source));
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
     * <p>选了 {@code scriptStorage=native} 时载荷不写成文件，而是追加到工作区里各个 ABI 的
     * {@code libaijscrypto.so} 尾部（产物里一个脚本文件都没有，见 {@link EmbeddedScriptFooter}）。
     */
    private void writeEntryScript(File source) throws Exception {
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
        if (ScriptProtection.shouldCompile(mEncryptLevel)) {
            if (isQuickJsEngine()) {
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

    /** 生成「文件头 + 密文」的完整加密载荷字节（写文件与嵌进原生库共用同一份）。 */
    private byte[] encryptedPayload(byte[] plain, short flags) throws IOException {
        if (mKey == null || mInitVector == null) {
            throw new IllegalStateException("Script encryption key is not initialized");
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(plain.length + 32);
            EncryptedScriptFileHeader.INSTANCE.writeHeader(out, flags);
            out.write(new AdvancedEncryptionStandard(
                    mKey.getBytes("UTF-8"), mInitVector).encrypt(plain));
            return out.toByteArray();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to encrypt script", e);
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

    private void copyDir(String src, String dst, File excluded) throws IOException {
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
        File excludedCanonical = excluded == null ? null : excluded.getCanonicalFile();
        for (File child : children) {
            if (excludedCanonical != null && child.getCanonicalFile().equals(excludedCanonical)) {
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
        if (mKey == null || mInitVector == null) {
            throw new IllegalStateException("Script encryption key is not initialized");
        }
        try {
            byte[] plain = PFiles.readBytes(file.getPath());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            EncryptedScriptFileHeader.INSTANCE.writeHeader(out, (short) 0);
            out.write(new AdvancedEncryptionStandard(mKey.getBytes("UTF-8"), mInitVector).encrypt(plain));
            return out.toByteArray();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to encrypt script: " + file, e);
        }
    }

    /**
     * Syncs {@code assets/project/project.json} with the packaged app identity and derives
     * the script encryption key/vector exactly like the inrt runtime launcher does:
     * {@code key = MD5(packageName + versionName + mainScriptFile)} and
     * {@code vector = MD5(buildId + name).substring(0, 16)}. The runtime reads the same
     * project.json (written here) and derives the same values, so both ends must stay in sync.
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

            mKey = MD5.md5(json.getString("packageName") + json.getString("versionName") + mMainScriptFile);
            mInitVector = MD5.md5(buildInfo.getBuildId() + json.getString("name")).substring(0, 16);

            // 密钥硬化（新方案）：随机盐 + 签名证书指纹参与派生，替换上面的旧算法。
            // 拿不到签名指纹时（例如身份还没准备好）保持旧算法，产物仍然可用。
            // 注意：这两个字段必须在写文件之前塞进 json，否则运行端读到的 project.json
            // 里没有盐/指纹，就会回退旧算法，而包装时用的是新密钥 → 解密失败。
            String fingerprint = resolveSigningCertificateFingerprint();
            if (ScriptKeyDerivation.hasCertificateFingerprint(fingerprint)) {
                String salt = json.optString("scriptSalt", null);
                if (salt == null || salt.isEmpty()) {
                    salt = ScriptKeyDerivation.randomSaltHex();
                }
                mKey = ScriptKeyDerivation.deriveKey(json.optString("packageName"), salt, fingerprint);
                mInitVector = ScriptKeyDerivation.deriveVector(salt, fingerprint);
                json.put("scriptSalt", salt);
                json.put("signatureFingerprint", fingerprint);
            } else {
                // 没有签名指纹就不写这两个字段：运行端看到字段缺失会走旧算法。
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
         * Script engine of the packaged app: {@code "rhino"} or {@code "quickjs"};
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
