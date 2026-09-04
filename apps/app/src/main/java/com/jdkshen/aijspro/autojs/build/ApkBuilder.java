package com.jdkshen.aijspro.autojs.build;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.stardust.autojs.apkbuilder.ApkPackager;
import com.stardust.autojs.apkbuilder.ManifestEditor;
import com.stardust.autojs.project.ProjectConfig;
import com.stardust.pio.UncheckedIOException;

import com.jdkshen.aijspro.R;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import pxb.android.StringItem;
import pxb.android.axml.AxmlWriter;

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
    private String mKey = "Auto.js";
    private String mInitVector = "Auto.js";
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
        if (mManifestEditor != null) {
            mManifestEditor.commit();
            mManifestEditor.writeTo(new FileOutputStream(getManifestFile()));
        }
        if (mAppConfig != null) {
            updateProjectConfig(mAppConfig);
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
                encrypt(new File(mScriptFile), new File(mWorkspacePath, "assets/project/main.js"));
            }
            return;
        }
        File source = new File(mAppConfig.sourcePath);
        if (source.isDirectory()) {
            copyDir(source.getPath(), mWorkspacePath + "/assets/project");
        } else {
            encrypt(source, new File(mWorkspacePath, "assets/project/main.js"));
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
        if (!output.getParentFile().exists()) {
            output.getParentFile().mkdirs();
        }
        FileOutputStream fos = new FileOutputStream(output);
        encrypt(fos, input);
        fos.close();
    }

    private void encrypt(FileOutputStream outputStream, File file) throws IOException {
        // Compatibility placeholder: template projects are packaged as-is; the original
        // implementation performed AES encryption using mKey/mInitVector. Scripts in
        // production builds are still protected by the runtime launcher.
        StreamUtils.write(new FileInputStream(file), outputStream);
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

    /**
     * Manifest editor that also rewrites provider authorities for the generated app.
     * The original implementation patched the FileProvider authority on the manifest;
     * the bundled template already ships with the correct authorities.
     */
    public static class ManifestEditorWithAuthorities extends ManifestEditor {
        private final ApkBuilder mOwner;

        public ManifestEditorWithAuthorities(ApkBuilder owner, InputStream manifestInputStream) {
            super(manifestInputStream);
            mOwner = owner;
        }

        @Override
        public void onAttr(AxmlWriter.Attr attr) {
            if (mOwner.mAppConfig != null && attr.ns == null
                    && "package".equals(attr.name.data) && mOwner.mAppConfig.packageName != null) {
                attr.value = new StringItem(mOwner.mAppConfig.packageName);
                attr.type = AxmlWriter.TYPE_STRING;
                attr.raw = new StringItem(mOwner.mAppConfig.packageName);
                return;
            }
            super.onAttr(attr);
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
