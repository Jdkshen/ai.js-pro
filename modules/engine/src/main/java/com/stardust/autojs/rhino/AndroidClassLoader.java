package com.stardust.autojs.rhino;

import android.os.Build;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.android.dx.command.dexer.Main;
import com.stardust.pio.PFiles;
import com.stardust.util.MD5;

import net.lingala.zip4j.core.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.model.FileHeader;
import net.lingala.zip4j.model.ZipParameters;

import org.mozilla.javascript.GeneratedClassLoader;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import dalvik.system.DexClassLoader;

/**
 * Created by Stardust on 2017/4/5.
 */

public class AndroidClassLoader extends ClassLoader implements GeneratedClassLoader {


    private static final String LOG_TAG = "AndroidClassLoader";
    private final ClassLoader parent;
    private final List<DexClassLoader> mDexClassLoaders = new ArrayList<>();
    private final File mCacheDir;

    /**
     * Create a new instance with the given parent classloader and cache dierctory
     *
     * @param parent the parent
     * @param dir    the cache directory
     */
    public AndroidClassLoader(ClassLoader parent, File dir) {
        this.parent = parent;
        mCacheDir = dir;
        if (dir.exists()) {
            PFiles.deleteFilesOfDir(dir);
        }
        // 目录可能被宿主清理逻辑删除（或 deleteFilesOfDir 失败），写 dex 前必须确保存在
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(LOG_TAG, "cannot create class cache dir: " + dir);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Class<?> defineClass(String name, byte[] data) {
        return defineClass(name, data, false);
    }

    /** Node's ClassFileWriter emits legacy bytecode without Java 8 desugaring.
     * Avoid booting D8 for these tiny callback adapters; general jars still use D8. */
    public Class<?> defineClass(String name, byte[] data, boolean preferDx) {
        Log.d(LOG_TAG, "defineClass: name = " + name + " data.length = " + data.length);
        File classFile = null;
        try {
            classFile = generateTempFile(name, false);
            final ZipFile zipFile = new ZipFile(classFile);
            final ZipParameters parameters = new ZipParameters();
            parameters.setFileNameInZip(name.replace('.', '/') + ".class");
            parameters.setSourceExternalStream(true);
            zipFile.addStream(new ByteArrayInputStream(data), parameters);
            boolean legacy = data.length >= 8 && (data[6] & 0xff) == 0 && (data[7] & 0xff) <= 51;
            return dexJar(classFile, null, preferDx && legacy).loadClass(name);
        } catch (IOException | ZipException | ClassNotFoundException e) {
            throw new FatalLoadingException(e);
        } finally {
            if (classFile != null) {
                classFile.delete();
            }
        }
    }

    private File generateTempFile(String name, boolean create) throws IOException {
        File file = new File(mCacheDir, name.hashCode() + System.currentTimeMillis() + ".jar");
        if (create) {
            if (!file.exists()) {
                file.createNewFile();
            }
        } else {
            file.delete();
        }
        return file;
    }

    public void loadJar(File jar) throws IOException {
        Log.d(LOG_TAG, "loadJar: jar = " + jar);
        if (!mCacheDir.exists() && !mCacheDir.mkdirs()) {
            Log.w(LOG_TAG, "class cache dir missing and cannot create: " + mCacheDir);
        }
        if (!jar.exists() || !jar.canRead()) {
            throw new FileNotFoundException("File does not exist or readable: " + jar.getPath());
        }
        File dexFile = new File(mCacheDir, generateDexFileName(jar));
        if (dexFile.exists()) {
            loadDex(dexFile);
            return;
        }
        File classFile = null;
        try {
            classFile = generateTempFile(jar.getPath(), false);
            final ZipFile zipFile = new ZipFile(classFile);
            final ZipFile jarFile = new ZipFile(jar);
            //noinspection unchecked
            for (FileHeader header : (List<FileHeader>) jarFile.getFileHeaders()) {
                if (!header.isDirectory()) {
                    final ZipParameters parameters = new ZipParameters();
                    parameters.setFileNameInZip(header.getFileName());
                    parameters.setSourceExternalStream(true);
                    zipFile.addStream(jarFile.getInputStream(header), parameters);
                }
            }
            dexJar(classFile, dexFile);
        } catch (ZipException e) {
            throw new IOException(e);
        } finally {
            // 修复旧实现异常路径下临时 jar 泄漏的问题。
            if (classFile != null) {
                classFile.delete();
            }
        }
    }

    private String generateDexFileName(File jar) {
        String message = jar.getPath() + "_" + jar.lastModified();
        // API 26+ 优先 D8，缓存的是原始 classes.dex，用 .dex 后缀更明确；
        // API 21~25 只能走 dx（jar 输出），保持旧版无后缀的缓存名，避免行为变化。
        String suffix = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? ".dex" : "";
        return MD5.md5(message) + suffix;
    }

    public DexClassLoader loadDex(File file) throws FileNotFoundException {
        Log.d(LOG_TAG, "loadDex: file = " + file);
        if (!file.exists()) {
            throw new FileNotFoundException(file.getPath());
        }
        DexClassLoader loader = new DexClassLoader(file.getPath(), mCacheDir.getPath(), appNativeLibraryDir(), parent);
        mDexClassLoaders.add(loader);
        return loader;
    }

    /** 应用 native 库目录：作为 DexClassLoader 的 librarySearchPath，脚本 jar 里的 System.load
     *  才能找到 libc++_shared 等依赖（否则 Android 15 会为 cache 里的 dex 创建不含该 lib 的 isolated namespace）。 */
    private static volatile String sAppNativeLibraryDir;
    private static volatile boolean sAppNativeLibraryDirQueried;

    private static String appNativeLibraryDir() {
        if (!sAppNativeLibraryDirQueried) {
            synchronized (AndroidClassLoader.class) {
                if (!sAppNativeLibraryDirQueried) {
                    try {
                        Class<?> activityThread = Class.forName("android.app.ActivityThread");
                        Object app = activityThread.getMethod("currentApplication").invoke(null);
                        if (app != null) {
                            Object info = app.getClass().getMethod("getApplicationInfo").invoke(app);
                            sAppNativeLibraryDir = (String) info.getClass().getField("nativeLibraryDir").get(info);
                        }
                    } catch (Throwable ignored) {
                        Log.w(LOG_TAG, "cannot resolve app nativeLibraryDir: " + ignored);
                    }
                    sAppNativeLibraryDirQueried = true;
                }
            }
        }
        return sAppNativeLibraryDir;
    }

    private DexClassLoader dexJar(File classFile, File dexFile) throws IOException {
        return dexJar(classFile, dexFile, false);
    }

    private DexClassLoader dexJar(File classFile, File dexFile, boolean preferDx) throws IOException {
        boolean isTmpDex = dexFile == null;
        if (isTmpDex) {
            dexFile = generateTempDexFile("dex-" + classFile.getPath());
        }
        try {
            if (preferDx) {
                try {
                    dexWithDx(classFile, dexFile);
                    return loadDex(dexFile);
                } catch (Exception | LinkageError failure) {
                    Log.w(LOG_TAG, "Generated class dx failed, retry with D8: " + classFile, failure);
                    deleteQuietly(dexFile);
                }
            }
            // 1) 优先 D8：支持 Java 11/17 的 class（major 55/61）。
            // D8 依赖 java.nio.file，Android 8.0（API 26）以下不能加载。
            Throwable d8Error = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    dexWithD8(classFile, dexFile);
                    return loadDex(dexFile);
                } catch (Exception | LinkageError t) {
                    // 只把正常的 dex/类加载失败当作可回退错误；OOM、StackOverflow 等
                    // Error 继续向上抛，避免在内存不足时再跑一遍 dx。
                    d8Error = t;
                    Log.w(LOG_TAG, "D8 dex failed, fallback to dx: " + classFile, t);
                    // D8 可能写了半成品；删掉，避免回退 dx 或下次 loadJar 复用坏缓存。
                    deleteQuietly(dexFile);
                }
            } else {
                Log.d(LOG_TAG, "D8 requires API 26+, fallback to dx: " + classFile);
            }

            // 2) 回退旧 dx（本仓库 dx 1.11 实测最多支持 major 51），
            // 保留旧 jar 的兼容路径；major 52+ 仍需要 D8（API 26+）。
            try {
                dexWithDx(classFile, dexFile);
                return loadDex(dexFile);
            } catch (Exception | LinkageError dxError) {
                deleteQuietly(dexFile);
                IOException exception = new IOException(
                        "jar 的 class 版本过高或 dex 失败: " + classFile.getName()
                                + "（D8: " + describe(d8Error) + "；dx: " + describe(dxError) + "）",
                        dxError);
                if (d8Error != null) {
                    exception.addSuppressed(d8Error);
                }
                throw exception;
            }
        } finally {
            // defineClass 的临时 dex 在 DexClassLoader 构造后即可删除（旧实现同样如此）。
            if (isTmpDex) {
                deleteQuietly(dexFile);
            }
        }
    }

    @RequiresApi(26)
    private void dexWithD8(File classFile, File dexFile) throws IOException {
        File outputDir = new File(mCacheDir, "d8-" + System.nanoTime() + ".tmp");
        if (!outputDir.mkdirs()) {
            throw new IOException("cannot create D8 output dir: " + outputDir);
        }
        try {
            D8JarDexer.dex(classFile, outputDir);
            File[] entries = outputDir.listFiles();
            List<File> dexFiles = new ArrayList<>();
            if (entries != null) {
                for (File entry : entries) {
                    if (entry.isFile() && isDexName(entry.getName())) {
                        dexFiles.add(entry);
                    }
                }
            }
            if (dexFiles.isEmpty()) {
                throw new IOException("D8 未生成 classes.dex: " + classFile);
            }
            if (dexFiles.size() == 1) {
                // 单 dex：直接把 classes.dex 交给 DexClassLoader。
                copyFile(dexFiles.get(0), dexFile);
            } else {
                // 多 dex：打成 jar（classes.dex/classes2.dex...），DexClassLoader 支持。
                Collections.sort(dexFiles, DEX_FILE_COMPARATOR);
                zipDexFiles(dexFiles, dexFile);
            }
            validateDexOutput(dexFile);
        } finally {
            deleteRecursively(outputDir);
        }
    }

    private void dexWithDx(File classFile, File dexFile) throws IOException {
        final Main.Arguments arguments = new Main.Arguments();
        arguments.fileNames = new String[]{classFile.getPath()};
        arguments.outName = dexFile.getPath();
        arguments.jarOutput = true;
        int result = Main.run(arguments);
        if (!dexFile.isFile() || dexFile.length() == 0) {
            throw new IOException("dx 未生成 dex（exit code " + result + "）");
        }
        if (result != 0) {
            Log.w(LOG_TAG, "dx returned " + result + " but output exists: " + dexFile);
        }
    }

    private File generateTempDexFile(String name) {
        File file = new File(mCacheDir, name.hashCode() + System.currentTimeMillis() + ".dex");
        file.delete();
        return file;
    }

    private static boolean isDexName(String name) {
        return name.startsWith("classes") && name.endsWith(".dex");
    }

    private static int dexIndex(String name) {
        if ("classes.dex".equals(name)) {
            return 1;
        }
        if (name.startsWith("classes") && name.endsWith(".dex")) {
            try {
                return Integer.parseInt(name.substring("classes".length(), name.length() - ".dex".length()));
            } catch (NumberFormatException ignored) {
                return Integer.MAX_VALUE;
            }
        }
        return Integer.MAX_VALUE;
    }

    private static void validateDexOutput(File file) throws IOException {
        if (!file.isFile() || file.length() < 8) {
            throw new IOException("D8 输出为空或不存在: " + file);
        }
        FileInputStream in = new FileInputStream(file);
        try {
            byte[] header = new byte[4];
            if (in.read(header) != 4) {
                throw new IOException("D8 输出的 dex 头不完整: " + file);
            }
            boolean dex = header[0] == 'd' && header[1] == 'e' && header[2] == 'x' && header[3] == '\n';
            boolean zip = header[0] == 'P' && header[1] == 'K';
            if (!dex && !zip) {
                throw new IOException("D8 输出不是有效的 dex/zip: " + file);
            }
        } finally {
            in.close();
        }
    }

    private static void copyFile(File source, File target) throws IOException {
        BufferedInputStream in = new BufferedInputStream(new FileInputStream(source));
        try {
            BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(target));
            try {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }

    private static void zipDexFiles(List<File> dexFiles, File target) throws IOException {
        ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(target)));
        try {
            byte[] buffer = new byte[8192];
            for (File dexFile : dexFiles) {
                out.putNextEntry(new ZipEntry(dexFile.getName()));
                BufferedInputStream in = new BufferedInputStream(new FileInputStream(dexFile));
                try {
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                } finally {
                    in.close();
                }
                out.closeEntry();
            }
        } finally {
            out.close();
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        if (!file.delete() && file.exists()) {
            Log.w(LOG_TAG, "cannot delete temp file: " + file);
        }
    }

    private static void deleteQuietly(File file) {
        if (file != null) {
            file.delete();
        }
    }

    private static String describe(Throwable t) {
        if (t == null) {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                    ? "未尝试（Android 版本低于 8.0）"
                    : "未知错误";
        }
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private static final Comparator<File> DEX_FILE_COMPARATOR = new Comparator<File>() {
        @Override
        public int compare(File left, File right) {
            int leftIndex = dexIndex(left.getName());
            int rightIndex = dexIndex(right.getName());
            if (leftIndex != rightIndex) {
                return leftIndex < rightIndex ? -1 : 1;
            }
            return left.getName().compareTo(right.getName());
        }
    };

    /**
     * Does nothing
     *
     * @param aClass ignored
     */
    @Override
    public void linkClass(Class<?> aClass) {
        //doesn't make sense on android
    }

    /**
     * Try to load a class. This will search all defined classes, all loaded jars and the parent class loader.
     *
     * @param name    the name of the class to load
     * @param resolve ignored
     * @return the class
     * @throws ClassNotFoundException if the class could not be found in any of the locations
     */
    @Override
    public Class<?> loadClass(String name, boolean resolve)
            throws ClassNotFoundException {
        Class<?> loadedClass = findLoadedClass(name);
        if (loadedClass == null) {
            for (DexClassLoader dex : mDexClassLoaders) {
                loadedClass = dex.loadClass(name);
                if (loadedClass != null) {
                    break;
                }
            }
            if (loadedClass == null) {
                loadedClass = parent.loadClass(name);
            }
        }
        return loadedClass;
    }

    /**
     * Might be thrown in any Rhino method that loads bytecode if the loading failed
     */
    public static class FatalLoadingException extends RuntimeException {
        FatalLoadingException(Throwable t) {
            super("Failed to define class", t);
        }
    }
}
