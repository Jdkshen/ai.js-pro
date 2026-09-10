package com.jdkshen.aijspro.autojs.build;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.stardust.autojs.apkbuilder.ApkPackager;
import com.stardust.autojs.apkbuilder.ManifestEditor;
import com.stardust.autojs.project.BuildInfo;
import com.stardust.autojs.project.ProjectConfig;
import com.stardust.autojs.script.EncryptedScriptFileHeader;
import com.stardust.pio.PFiles;
import com.stardust.pio.UncheckedIOException;
import com.stardust.util.AdvancedEncryptionStandard;
import com.stardust.util.MD5;

import com.jdkshen.aijspro.R;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
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
        return this;
    }

    private void copyProjectToWorkspace() throws Exception {
        if (mAppConfig == null || mAppConfig.sourcePath == null) {
            if (mScriptFile != null) {
                syncProjectJsonAndDeriveKeys();
                encrypt(new File(mScriptFile), new File(mWorkspacePath, "assets/project/" + mMainScriptFile));
            }
            return;
        }
        File source = new File(mAppConfig.sourcePath);
        if (source.isDirectory()) {
            copyDir(source.getPath(), mWorkspacePath + "/assets/project");
            syncProjectJsonAndDeriveKeys();
            File entryScript = new File(mWorkspacePath, "assets/project/" + mMainScriptFile);
            encrypt(entryScript, entryScript);
        } else {
            syncProjectJsonAndDeriveKeys();
            encrypt(source, new File(mWorkspacePath, "assets/project/" + mMainScriptFile));
            if (mAppConfig.ignoredDirs != null) {
                for (Object ignored : mAppConfig.ignoredDirs) {
                    new File(mWorkspacePath, "assets/project/" + ignored).delete();
                }
            }
        }
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
    }

    private void replaceIcon(Bitmap bitmap) throws IOException {
        if (bitmap == null) {
            return;
        }
        String[] densities = {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};
        String res = "res/mipmap-" + densities[0] + "/ic_launcher.png";
        File iconFile = new File(mWorkspacePath, res);
        if (!iconFile.getParentFile().exists()) {
            iconFile.getParentFile().mkdirs();
        }
        FileOutputStream out = new FileOutputStream(iconFile);
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        out.close();
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
        private final ArrayList<String> ignoredDirs = new ArrayList<>();

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
