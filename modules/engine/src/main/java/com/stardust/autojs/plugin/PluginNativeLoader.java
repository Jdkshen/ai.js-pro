package com.stardust.autojs.plugin;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** task-130/133 path 1: stage plugin native libs into host private dir, then System.load(absolute path).
 *  System.loadLibrary only resolves the caller app nativeLibraryDir, so plugin libs must be copied first.
 *  Sources (logged): plugin nativeLibraryDir, else lib/<abi>/*.so extracted from the plugin APK zip. */
public final class PluginNativeLoader {
    private static final String TAG = "PluginNativeLoader";
    private static final String READY = "READY";
    private static final List<String> LOADED = new ArrayList<>();

    private PluginNativeLoader() { }

    /** Named failure: library / absolute path / reason. */
    public static final class LibLoadException extends IllegalStateException {
        public LibLoadException(String message) { super(message); }
        public LibLoadException(String message, Throwable cause) { super(message, cause); }
    }

    public static File pluginDir(Context host, String pluginPackage, int versionCode) {
        return new File(new File(new File(host.getFilesDir(), "plugins"), pluginPackage), String.valueOf(versionCode));
    }

    public static File libsDirOrNull(Context host, String pluginPackage, int versionCode) {
        File dir = pluginDir(host, pluginPackage, versionCode);
        if (!new File(dir, READY).isFile()) { return null; }
        File libs = new File(dir, "lib");
        File[] files = libs.listFiles();
        return files == null || files.length == 0 ? null : libs;
    }

    public static boolean isReady(Context host, String pluginPackage, int versionCode) {
        return libsDirOrNull(host, pluginPackage, versionCode) != null;
    }

    public static File syncPluginLibs(Context host, String pluginPackage, int versionCode) {
        File dir = pluginDir(host, pluginPackage, versionCode);
        File libs = new File(dir, "lib");
        if (isReady(host, pluginPackage, versionCode)) { return libs; }
        libs.mkdirs();
        String abi = primaryAbi();
        String source = "nativeLibraryDir";
        int copied = 0;
        File nativeDir = pluginNativeLibraryDir(host, pluginPackage);
        if (nativeDir != null && nativeDir.isDirectory()) { copied = copyDirectory(nativeDir, libs); }
        if (copied == 0) {
            source = "apk-zip:lib/" + abi;
            copied = extractLibsFromApk(pluginApk(host, pluginPackage), libs, abi);
        }
        if (copied == 0) {
            Log.w(TAG, "plugin libs staging failed (0 files): pkg=" + pluginPackage + " source=" + source);
            return null;
        }
        try (FileOutputStream out = new FileOutputStream(new File(dir, READY))) {
            out.write((versionCode + "|" + abi + "|" + copied + "|" + source).getBytes("UTF-8"));
        } catch (Exception error) {
            Log.w(TAG, "write READY failed: " + error);
        }
        Log.i(TAG, "plugin libs staged pkg=" + pluginPackage + " v=" + versionCode + " files=" + copied + " source=" + source);
        cleanupOtherVersions(host, pluginPackage, versionCode);
        return libs;
    }

    public static void load(Context host, String pluginPackage, int versionCode, String... libraryNames) {
        File libs = libsDirOrNull(host, pluginPackage, versionCode);
        if (libs == null) {
            throw new LibLoadException("OCR plugin libs not ready (call syncPluginLibs first): pkg=" + pluginPackage + " v=" + versionCode);
        }
        for (String name : libraryNames) {
            File lib = new File(libs, name.endsWith(".so") ? name : name + ".so");
            if (!lib.isFile()) {
                throw new LibLoadException("OCR plugin library load failed: " + name + " (path=" + lib.getAbsolutePath() + "): file missing");
            }
            if (LOADED.contains(lib.getAbsolutePath())) { continue; }
            try {
                System.load(lib.getAbsolutePath());
                LOADED.add(lib.getAbsolutePath());
            } catch (Throwable error) {
                throw new LibLoadException("OCR plugin library load failed: " + name + " (path=" + lib.getAbsolutePath() + "): "
                        + error.getMessage() + ". Fallback: OCR_NOT_PACKAGED; reinstall/update the OCR plugin or disable plugin OCR.", error);
            }
        }
    }

    static int extractLibsFromApk(File apk, File targetDir, String abi) {
        if (apk == null || !apk.isFile()) { return 0; }
        String prefix = "lib/" + abi + "/";
        int copied = 0;
        try (ZipFile zip = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.startsWith(prefix) || !name.endsWith(".so")) { continue; }
                String fileName = name.substring(prefix.length());
                if (fileName.contains("/")) { continue; }
                targetDir.mkdirs();
                try (InputStream in = zip.getInputStream(entry);
                     FileOutputStream out = new FileOutputStream(new File(targetDir, fileName))) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) > 0) { out.write(buffer, 0, read); }
                }
                copied++;
            }
        } catch (Exception error) {
            Log.w(TAG, "extract plugin libs from apk failed: " + apk + " abi=" + abi + " : " + error);
        }
        return copied;
    }

    static String primaryAbi() {
        if (Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0) { return Build.SUPPORTED_ABIS[0]; }
        return "arm64-v8a";
    }

    private static int copyDirectory(File source, File target) {
        File[] files = source.listFiles();
        if (files == null) { return 0; }
        target.mkdirs();
        int copied = 0;
        for (File file : files) {
            if (!file.isFile() || !file.getName().endsWith(".so")) { continue; }
            try (InputStream in = new java.io.FileInputStream(file);
                 FileOutputStream out = new FileOutputStream(new File(target, file.getName()))) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) { out.write(buffer, 0, read); }
                copied++;
            } catch (Exception error) {
                Log.w(TAG, "copy plugin lib failed: " + file + " : " + error);
            }
        }
        return copied;
    }

    private static File pluginNativeLibraryDir(Context host, String pluginPackage) {
        try {
            ApplicationInfo info = host.getPackageManager().getApplicationInfo(pluginPackage, 0);
            return info.nativeLibraryDir == null ? null : new File(info.nativeLibraryDir);
        } catch (Throwable error) { return null; }
    }

    private static File pluginApk(Context host, String pluginPackage) {
        try {
            ApplicationInfo info = host.getPackageManager().getApplicationInfo(pluginPackage, 0);
            return new File(info.sourceDir);
        } catch (Throwable error) { return null; }
    }

    private static void cleanupOtherVersions(Context host, String pluginPackage, int keepVersionCode) {
        File[] dirs = new File(new File(host.getFilesDir(), "plugins"), pluginPackage).listFiles();
        if (dirs == null) { return; }
        for (File dir : dirs) {
            if (dir.isDirectory() && !String.valueOf(keepVersionCode).equals(dir.getName())) { deleteRecursively(dir); }
        }
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) { for (File child : children) { deleteRecursively(child); } }
        if (!file.delete()) { Log.i(TAG, "cleanup old plugin dir failed (ignored): " + file); }
    }
}