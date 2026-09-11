package com.jdkshen.aijspro.autojs.build;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.jdkshen.aijspro.autojs.build.sign.AutoSigningIdentity;
import com.stardust.autojs.apkbuilder.ApkPackager;
import com.stardust.autojs.apkbuilder.ManifestEditor;
import com.stardust.autojs.apkbuilder.Signer;
import com.stardust.autojs.project.BuildInfo;
import com.stardust.autojs.project.LaunchConfig;
import com.stardust.autojs.project.ProjectConfig;
import com.stardust.autojs.project.ScriptProtection;
import com.stardust.autojs.rhino.AndroidClassLoader;
import com.stardust.autojs.script.CompiledScriptPayload;
import com.stardust.autojs.script.ScriptCompiler;
import com.stardust.autojs.script.EncryptedScriptFileHeader;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

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
            "com.stardust.autojs.core.accessibility.AccessibilityService";
    private static final String[] IMAGE_MODULE_LIBS = {
            "libopencv_core.so", "libopencv_dnn.so", "libopencv_flann.so",
            "libopencv_geometry.so", "libopencv_imgcodecs.so", "libopencv_imgproc.so",
            "libopencv_java5.so", "libtbb.so"};

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

    public ApkBuilder prepare() throws IOException {
        notifyPrepare();
        new File(mWorkspacePath).mkdirs();
        mApkPackager.unzip();
        mManifestEditor = new ManifestEditor(new FileInputStream(getManifestFile()));
        return this;
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
     */
    private void removeDisabledFeatures() {
        if (mAppConfig == null || !mAppConfig.includeImageModule) {
            if (mAppConfig != null) {
                for (String lib : IMAGE_MODULE_LIBS) {
                    deleteFileRecursively(new File(mWorkspacePath, "lib"), lib);
                }
            }
        }
    }

    private void deleteFileRecursively(File dir, String fileName) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                deleteFileRecursively(child, fileName);
            } else if (fileName.equals(child.getName())) {
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
            copyDir(source.getPath(), mWorkspacePath + "/assets/project");
            syncProjectJsonAndDeriveKeys();
            writeEntryScript(new File(mWorkspacePath, "assets/project/" + mMainScriptFile));
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
     * <p>编译需要引擎配合：Rhino 才支持编译成 class；QuickJS 字节码是后续批次，
     * 在那之前 QuickJS 工程暂时退化为「加密」（不会产出跑不起来的产物）。
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
        if (ScriptProtection.shouldCompile(mEncryptLevel) && !isQuickJsEngine()) {
            byte[] payload = compileEntryScript(source);
            writeEncrypted(target, payload,
                    EncryptedScriptFileHeader.INSTANCE.flagsWithPayloadType(
                            EncryptedScriptFileHeader.PAYLOAD_TYPE_RHINO_CLASS));
            return;
        }
        writeEncrypted(target, PFiles.readBytes(source.getPath()), (short) 0);
    }

    /** 打包的产物使用 QuickJS 引擎（此时不能写 Rhino 编译产物）。 */
    private boolean isQuickJsEngine() {
        return mAppConfig != null && "quickjs".equalsIgnoreCase(mAppConfig.engine);
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
        if (mKey == null || mInitVector == null) {
            throw new IllegalStateException("Script encryption key is not initialized");
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try {
            FileOutputStream fos = new FileOutputStream(target);
            try {
                EncryptedScriptFileHeader.INSTANCE.writeHeader(fos, flags);
                fos.write(new AdvancedEncryptionStandard(
                        mKey.getBytes("UTF-8"), mInitVector).encrypt(plain));
            } finally {
                fos.close();
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to encrypt script: " + target, e);
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
                if (bitmap != null) {
                    replaceIcon(bitmap);
                }
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
                    Collections.singletonList(ACCESSIBILITY_SERVICE));
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
        // The bundled template already carries a matching resources.arsc; the package
        // rename is applied through the manifest/axml path. Kept for API compatibility.
    }

    private void copyDir(String src, String dst) throws IOException {
        File srcDir = new File(src);
        File[] children = srcDir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                copyDir(child.getPath(), dst + "/" + child.getName());
            } else {
                FileOutputStream out = new FileOutputStream(new File(dst, child.getName()));
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
            json.put("encryptLevel", mEncryptLevel);

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

            FileOutputStream fos = new FileOutputStream(jsonFile);
            try {
                fos.write(json.toString(2).getBytes("UTF-8"));
            } finally {
                fos.close();
            }

            mKey = MD5.md5(json.getString("packageName") + json.getString("versionName") + mMainScriptFile);
            mInitVector = MD5.md5(buildInfo.getBuildId() + json.getString("name")).substring(0, 16);
        } catch (JSONException e) {
            throw new IOException("Failed to sync assets/project/project.json", e);
        }
    }

    public ApkBuilder sign() throws Exception {
        notifySign();
        Signer signer = mAppConfig != null ? mAppConfig.getSigner() : null;
        if (signer == null) {
            // 没有显式指定签名就用本机为该应用自动生成的身份。
            // tiny-sign 那份全世界共用的测试证书已经不再使用：它会让所有产物共用一份
            // 私钥，安全软件据此就能把它们归成同一家族。
            File outputDir = mOutApkFile.getParentFile();
            if (outputDir == null) {
                throw new IOException("无法确定产物目录：" + mOutApkFile);
            }
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
            if (projectConfig.getIcon() != null) {
                config.icon = () -> BitmapFactory.decodeFile(projectConfig.getIcon());
            }
            config.engine = projectConfig.getEngine(null);
            config.encryptLevel = projectConfig.getEncryptLevel();
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
