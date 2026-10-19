package com.stardust.autojs.core.ui.inflater;

import android.content.Context;
import android.util.Log;

import dalvik.system.DexClassLoader;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工程 build 产物里的动态 dex（Auto.js Pro 的 {@code .codecache/*.dex}）。
 *
 * <p>Pro 会把 Node UI / 原生界面工程里「JS 里声明、但要编译成 Java 类」的类（示例的
 * {@code MyAdapter} / {@code MyViewHolder}）预编译成 dex，放到工程目录的 {@code .codecache/} 下，
 * 运行时直接加载，省掉 on-device dex 编译。本引擎之前完全没有读它 —— 布局里如果出现工程自己
 * 编出来的 View 类，{@code Class.forName} 一定失败。
 *
 * <p>这里做最小可用的加载器：收集 {@code <project>/.codecache}、{@code <project>/build/.codecache}
 * 下的 {@code *.dex}（递归 3 层），拷到应用私有目录（避免 Android 10+ 直接从 /sdcard mmap dex 的
 * 限制），再用 {@link DexClassLoader} 组一个 ClassLoader。没有 dex 时返回 {@code null}，调用方
 * 保持原来的宿主 ClassLoader 行为。
 *
 * <p>注意：本类只负责「让类找得到」；{@code $java.defineClass} 这类动态定义类仍由 Node 宿主桥实现。
 */
public final class ProjectCodeCache {

    private static final String LOG_TAG = "ProjectCodeCache";
    private static final String CODECACHE_DIR = ".codecache";
    private static final String BUILD_DIR = "build";
    /** 私有目录下 dex 的落地目录（相对 filesDir）。 */
    private static final String DEX_DIR = "project-codecache";
    /** DexClassLoader 的 odex 输出目录（相对 codeCacheDir）。 */
    private static final String OPT_DIR = "project-codecache-opt";

    /**
     * 进程内实例缓存：同一个工程、同一组 dex（路径+大小+mtime 相同）复用同一个 DexClassLoader。
     * 避免二次 load 重复拷贝 dex、重复 DexClassLoader（第二次曾因上次留下的只读文件覆盖失败 →
     * dev-988 codeCacheLoaded:false，并刷 W 堆栈）。
     */
    private static final Map<String, ProjectCodeCache> sInstances = new HashMap<>();

    private final File[] mDexFiles;
    private final ClassLoader mClassLoader;

    private ProjectCodeCache(File[] dexFiles, ClassLoader classLoader) {
        mDexFiles = dexFiles;
        mClassLoader = classLoader;
    }

    /**
     * 加载工程动态 dex。
     *
     * @return 工程没有 {@code .codecache/*.dex}、或加载失败（不阻断资源加载）时返回 {@code null}
     */
    public static ProjectCodeCache load(Context context, File projectDir) {
        List<File> dexFiles = listDexFiles(projectDir);
        if (dexFiles.isEmpty() || context == null) {
            return null;
        }
        String signature = signatureOf(projectDir, dexFiles);
        synchronized (sInstances) {
            ProjectCodeCache cached = sInstances.get(signature);
            if (cached != null) {
                return cached;
            }
        }
        try {
            // 每个工程独立子目录：不同工程的 .codecache 常有同名 dex（如 MyAdapter_1.dex），
            // 混在一个目录会互相覆盖/加载错类。
            String projectKey = hashOf(projectDir.getAbsolutePath());
            File dexDir = new File(new File(context.getFilesDir(), DEX_DIR), projectKey);
            if (!dexDir.isDirectory() && !dexDir.mkdirs()) {
                Log.w(LOG_TAG, "创建 dex 落地目录失败：" + dexDir + "（本次按无动态类处理，资源/布局继续）");
                return null;
            }
            File optDir = new File(context.getCodeCacheDir(), OPT_DIR + "-" + projectKey);
            if (!optDir.isDirectory() && !optDir.mkdirs()) {
                optDir = context.getCacheDir();
            }
            StringBuilder dexPath = new StringBuilder();
            for (File dexFile : dexFiles) {
                File target = copyToPrivateDir(dexFile, dexDir);
                if (dexPath.length() > 0) {
                    dexPath.append(File.pathSeparator);
                }
                dexPath.append(target.getAbsolutePath());
            }
            ClassLoader classLoader = new DexClassLoader(dexPath.toString(), optDir.getAbsolutePath(),
                    null, context.getClassLoader());
            ProjectCodeCache instance = new ProjectCodeCache(dexFiles.toArray(new File[0]), classLoader);
            synchronized (sInstances) {
                sInstances.put(signature, instance);
            }
            Log.i(LOG_TAG, "已加载工程动态 dex " + dexFiles.size() + " 个（parent="
                    + context.getClassLoader() + "）：" + dexPath);
            return instance;
        } catch (Throwable error) {
            // 动态 dex 加载失败不能阻断资源表/布局加载：按「没有动态类」处理，让调用方回退。
            // 必须带上 cause（dev-988 只看到 W 行、看不到原因）。
            Log.w(LOG_TAG, "加载工程动态 dex 失败（本次按无动态类处理，资源/布局继续）："
                    + dexFiles + "，cause=" + error, error);
            return null;
        }
    }

    /** dex 集合签名：工程路径 + 每个 dex 的路径/大小/mtime。工程 dex 变了就换新 loader。 */
    private static String signatureOf(File projectDir, List<File> dexFiles) {
        StringBuilder builder = new StringBuilder(projectDir == null ? "" : projectDir.getAbsolutePath());
        for (File file : dexFiles) {
            builder.append('|').append(file.getAbsolutePath())
                    .append(':').append(file.length())
                    .append(':').append(file.lastModified());
        }
        return builder.toString();
    }

    private static String hashOf(String text) {
        return Integer.toHexString(text == null ? 0 : text.hashCode());
    }

    /**
     * 落地到应用私有目录（Android 10+ 不建议直接 mmap /sdcard 上的 dex）。
     *
     * <p>幂等：目标已存在且大小一致直接复用（上次运行可能留下只读文件，直接覆盖会 FileNotFoundException）；
     * 不一致就先删再拷，删除失败则换唯一文件名。
     */
    private static File copyToPrivateDir(File source, File dir) throws IOException {
        File target = new File(dir, source.getName());
        if (target.isFile() && target.length() == source.length()) {
            return target;
        }
        if (target.exists() && !target.delete()) {
            target = new File(dir, source.getName() + "." + System.currentTimeMillis() + ".dex");
        }
        copy(source, target);
        return target;
    }



    /**
     * 只枚举工程里的动态 dex（不需要 Context，诊断用）。
     */
    public static List<File> listDexFiles(File projectDir) {
        if (projectDir == null) {
            return Collections.emptyList();
        }
        List<File> result = new ArrayList<>();
        collectDex(new File(projectDir, CODECACHE_DIR), result, 0);
        collectDex(new File(new File(projectDir, BUILD_DIR), CODECACHE_DIR), result, 0);
        Collections.sort(result, new Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                return left.getAbsolutePath().compareTo(right.getAbsolutePath());
            }
        });
        return result;
    }

    private static void collectDex(File dir, List<File> out, int depth) {
        if (dir == null || depth > 3) {
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collectDex(child, out, depth + 1);
            } else if (child.getName().endsWith(".dex")) {
                out.add(child);
            }
        }
    }

    private static void copy(File source, File target) throws IOException {
        try (InputStream input = new FileInputStream(source);
             OutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[16 * 1024];
            int size;
            while ((size = input.read(buffer)) > 0) {
                output.write(buffer, 0, size);
            }
        }
    }

    public ClassLoader getClassLoader() {
        return mClassLoader;
    }

    public File[] getDexFiles() {
        return mDexFiles;
    }

    public boolean isEmpty() {
        return mDexFiles.length == 0;
    }

    @Override
    public String toString() {
        return "ProjectCodeCache{dex=" + mDexFiles.length + ", loader=" + mClassLoader + "}";
    }
}
