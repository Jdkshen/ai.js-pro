package com.jdkshen.aijspro.model.sample;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.res.AssetManager;
import android.util.Log;

import com.jdkshen.aijspro.Pref;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

import io.reactivex.schedulers.Schedulers;

/**
 * 内置示例同步：升级到新版本后，把 APK 里 {@code assets/sample} 的内容对齐到
 * {@code files/sample}（文件管理里那个「示例」文件夹）。
 *
 * <p>为什么需要它：示例目录是「懒拷贝」——浏览到某个目录时，只在磁盘上缺这个文件时
 * 才从 assets 落盘（见 {@code WorkspaceFileProvider#listSamples}）。因此<b>内容被改过的
 * 内置示例永远不会刷新</b>：示例在新版本里已经修好，手机升级后跑起来照样报错，
 * 必须手动点「重置所有示例」。本类在 versionCode 变化后补上这一次同步。
 *
 * <p>策略（每个版本只跑一次，后台线程，失败不影响启动）：
 * <ol>
 * <li>磁盘没有 → 从 assets 落盘；</li>
 * <li>与内置内容一致 → 跳过；</li>
 * <li>内容不同，但磁盘那份等于「上次同步写下的内容」→ 用户没动过，直接覆盖；</li>
 * <li>否则（首次同步，或磁盘那份与记录不符 = 用户改过）→ 先把磁盘那份备份到
 * {@code files/sample-backup/} 下同名相对路径，再覆盖，绝不静默丢用户改动。</li>
 * </ol>
 *
 * <p>内置里没有的文件（用户自己放进去的）一律不碰，也不删除内置里已下架的旧文件。
 */
public final class BundledSampleSync {

    private static final String TAG = "BundledSampleSync";
    private static final String SAMPLE_ASSET = "sample";
    /** 记录「上次同步写下的每个文件的内容 md5」，用来区分「用户改过」和「只是版本旧」。 */
    private static final String STATE_FILE = "sample-sync-state.txt";
    private static final String BACKUP_DIR = "sample-backup";
    /**
     * 历史版本用过、现在已经不存在的示例目录（相对 files/sample）。
     * 升级到三引擎树（dev-928）后旧的「QuickJS 新引擎」「Rhino 引擎/中文」等
     * 目录不能只靠 pruneRemoved：同步记录丢失/首次同步时它不知道该删哪些，
     * 目录就会一直留在示例页里。这里显式清理，内容与当前内置示例一致的直接删，
     * 用户改过的先备份到 sample-backup/legacy/ 再删。
     */
    private static final String[] LEGACY_DIRS = {
            "QuickJS 新引擎",
            "QuickJS新引擎",
            "QuickJS 引擎/中文",
            "Rhino 引擎/中文",
            "Rhino 引擎/English",
            "Node 引擎/中文",
            "Node 引擎/English",
            "pro-samples",
    };

    private BundledSampleSync() {
    }

    /** 同步结果，只用于日志/自证。 */
    public static final class Result {
        public int created;
        public int updated;
        public int unchanged;
        public int backedUp;
        public int removed;

        @Override
        public String toString() {
            return "新增 " + created + "，更新 " + updated + "，已是最新 " + unchanged
                    + "，备份用户改动 " + backedUp + "，清理下架示例 " + removed;
        }
    }

    /**
     * versionCode 与上次同步时不同才执行；在 IO 线程上跑，任何异常都只记日志
     * （示例同步失败不该影响 App 启动）。
     */
    public static void syncIfNeeded(final Context context) {
        final int current = versionCode(context);
        if (current <= 0) {
            return;
        }
        if (Pref.getSampleSyncedVersionCode() == current) {
            return;
        }
        final Context appContext = context.getApplicationContext();
        Schedulers.io().createWorker().schedule(() -> {
            try {
                Result result = sync(appContext);
                Pref.setSampleSyncedVersionCode(current);
                Log.i(TAG, "内置示例已对齐到 versionCode " + current + "：" + result);
            } catch (Throwable e) {
                Log.w(TAG, "内置示例同步失败，示例目录保持原样", e);
            }
        });
    }

    /** 立即执行一次同步（不做版本判断，供测试/手动调用）。 */
    public static Result sync(Context context) throws IOException {
        AssetManager assets = context.getAssets();
        File root = new File(context.getFilesDir(), SAMPLE_ASSET);
        if (!root.isDirectory() && !root.mkdirs()) {
            throw new IOException("无法创建示例目录：" + root);
        }
        File stateFile = new File(context.getFilesDir(), STATE_FILE);
        Map<String, String> state = readState(stateFile);
        Map<String, String> next = new HashMap<>();
        Result result = new Result();
        syncDir(context, assets, SAMPLE_ASSET, root, "", state, next, result);
        pruneRemoved(context, root, state, next, result);
        removeLegacyDirs(context, root, next, result);
        writeState(stateFile, next);
        return result;
    }

    /**
     * 清理「新版 APK 里已下架/改名」的示例：上次同步记录里有、这次内置清单里没有的文件，
     * 磁盘上没被用户改过就直接删；用户改过的先备份到 {@code files/sample-backup/} 再删。
     *
     * <p>这是示例集从「两棵自定义树」重排成「Rhino/QuickJS/Node 三引擎树」时必须的一步 ——
     * 只做新增/覆盖的话旧目录会一直留在手机上。
     */
    private static void pruneRemoved(Context context, File root, Map<String, String> previous,
                                     Map<String, String> next, Result result) {
        for (Map.Entry<String, String> entry : previous.entrySet()) {
            if (next.containsKey(entry.getKey())) {
                continue;
            }
            File file = new File(root, entry.getKey());
            if (!file.isFile()) {
                continue;
            }
            if (!entry.getValue().equals(md5(file))) {
                backup(context, file, entry.getKey());
            }
            if (file.delete()) {
                result.removed++;
            }
        }
        deleteEmptyDirs(root);
    }

    /** 清理历史版本遗留的示例目录（见 {@link #LEGACY_DIRS}）。 */
    private static void removeLegacyDirs(Context context, File root, Map<String, String> next,
                                         Result result) {
        java.util.Set<String> bundled = new java.util.HashSet<>(next.values());
        for (String relative : LEGACY_DIRS) {
            File legacy = new File(root, relative);
            if (legacy.isDirectory()) {
                deleteLegacyTree(context, legacy, relative, bundled, result);
            } else if (legacy.isFile()) {
                removeLegacyFile(context, legacy, relative, bundled, result);
            }
        }
        deleteEmptyDirs(root);
    }

    private static void deleteLegacyTree(Context context, File dir, String relative,
                                         java.util.Set<String> bundled, Result result) {
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                String childRelative = relative + "/" + child.getName();
                if (child.isDirectory()) {
                    deleteLegacyTree(context, child, childRelative, bundled, result);
                } else {
                    removeLegacyFile(context, child, childRelative, bundled, result);
                }
            }
        }
        dir.delete();
    }

    private static void removeLegacyFile(Context context, File file, String relative,
                                        java.util.Set<String> bundled, Result result) {
        if (!bundled.contains(md5(file))) {
            backup(context, file, "legacy/" + relative);
        }
        if (file.delete()) {
            result.removed++;
        }
    }

    /** 递归删除示例目录内的空目录（示例根目录本身保留）。 */
    private static void deleteEmptyDirs(File dir) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (!child.isDirectory()) {
                continue;
            }
            deleteEmptyDirs(child);
            File[] rest = child.listFiles();
            if (rest != null && rest.length == 0) {
                child.delete();
            }
        }
    }

    private static void syncDir(Context context, AssetManager assets, String assetPath, File dir,
                                String relative, Map<String, String> state,
                                Map<String, String> next, Result result) {
        String[] children;
        try {
            children = assets.list(assetPath);
        } catch (IOException e) {
            Log.w(TAG, "列不出内置示例目录 " + assetPath, e);
            return;
        }
        if (children == null) {
            return;
        }
        for (String child : children) {
            String childAsset = assetPath + "/" + child;
            String childRelative = relative.isEmpty() ? child : relative + "/" + child;
            String[] grandChildren;
            try {
                grandChildren = assets.list(childAsset);
            } catch (IOException e) {
                grandChildren = null;
            }
            if (grandChildren != null && grandChildren.length > 0) {
                File sub = new File(dir, child);
                if (!sub.isDirectory() && !sub.mkdirs() && !sub.isDirectory()) {
                    Log.w(TAG, "无法创建示例子目录：" + sub);
                    continue;
                }
                syncDir(context, assets, childAsset, sub, childRelative, state, next, result);
            } else {
                syncFile(context, assets, childAsset, new File(dir, child), childRelative,
                        state, next, result);
            }
        }
    }

    private static void syncFile(Context context, AssetManager assets, String assetPath,
                                 File target, String relative, Map<String, String> state,
                                 Map<String, String> next, Result result) {
        byte[] bundled;
        try (InputStream in = assets.open(assetPath)) {
            bundled = readAll(in);
        } catch (IOException e) {
            Log.w(TAG, "打不开内置示例 " + assetPath, e);
            return;
        }
        String bundledMd5 = md5(bundled);
        next.put(relative, bundledMd5);

        if (!target.isFile()) {
            if (write(target, bundled)) {
                result.created++;
            }
            return;
        }
        String diskMd5 = md5(target);
        if (bundledMd5.equals(diskMd5)) {
            result.unchanged++;
            return;
        }
        String recorded = state.get(relative);
        if (recorded == null || !recorded.equals(diskMd5)) {
            // 首次同步，或磁盘那份和上次写下的内容不一样（用户改过）→ 先备份再覆盖
            if (backup(context, target, relative)) {
                result.backedUp++;
            }
        }
        if (write(target, bundled)) {
            result.updated++;
        }
    }

    /** 把用户改过的那份复制到 {@code files/sample-backup/<相对路径>}。 */
    private static boolean backup(Context context, File file, String relative) {
        File dest = new File(new File(context.getFilesDir(), BACKUP_DIR), relative);
        File parent = dest.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            Log.w(TAG, "无法创建备份目录：" + parent);
            return false;
        }
        try (InputStream in = new FileInputStream(file);
             OutputStream out = new FileOutputStream(dest)) {
            copy(in, out);
            return true;
        } catch (IOException e) {
            Log.w(TAG, "备份失败（继续覆盖）：" + file, e);
            return false;
        }
    }

    private static Map<String, String> readState(File file) {
        Map<String, String> state = new HashMap<>();
        if (!file.isFile()) {
            return state;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab > 0) {
                    state.put(line.substring(0, tab), line.substring(tab + 1));
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "读同步记录失败，按首次同步处理", e);
        }
        return state;
    }

    private static void writeState(File file, Map<String, String> state) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : state.entrySet()) {
            builder.append(entry.getKey()).append('\t').append(entry.getValue()).append('\n');
        }
        try {
            byte[] bytes = builder.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = new FileOutputStream(file)) {
                out.write(bytes);
            }
        } catch (IOException e) {
            // 写不下记录只会让下次同步多做一次备份，不影响正确性
            Log.w(TAG, "写同步记录失败：" + file, e);
        }
    }

    @SuppressWarnings("deprecation")
    private static int versionCode(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return info.versionCode;
        } catch (Exception e) {
            Log.w(TAG, "读不到 versionCode，跳过示例同步", e);
            return -1;
        }
    }

    private static String md5(File file) {
        try (InputStream in = new FileInputStream(file)) {
            return md5(readAll(in));
        } catch (IOException e) {
            Log.w(TAG, "读不出文件内容：" + file, e);
            return "";
        }
    }

    private static String md5(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hash = digest.digest(data);
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                builder.append(Character.forDigit((b >> 4) & 0xF, 16));
                builder.append(Character.forDigit(b & 0xF, 16));
            }
            return builder.toString();
        } catch (Exception e) {
            // 理论上不会发生；退化成「内容不同」也不会写坏数据
            return String.valueOf(data.length);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, in.available()));
        copy(in, out);
        return out.toByteArray();
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }

    private static boolean write(File file, byte[] data) {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            Log.w(TAG, "无法创建目录：" + parent);
            return false;
        }
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(data);
            return true;
        } catch (IOException e) {
            Log.w(TAG, "写示例失败：" + file, e);
            return false;
        }
    }
}
