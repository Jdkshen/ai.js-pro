package com.jdkshen.aijspro.model.sample;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import com.jdkshen.aijspro.Pref;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 内置示例的「落盘 / 导入」工具。
 *
 * <p>为什么需要它：示例里的伴生资源都是<b>相对路径</b>引用 ——
 * {@code images.read("./mario.png")}、{@code ui.layout(files.read("./layout.xml"))}、
 * {@code require("./子模块.js")}。由此有两条硬要求：
 *
 * <ol>
 * <li><b>运行</b>必须从磁盘跑，且工作目录正好是示例自己所在的目录。
 * 直接跑 assets 正文时工作目录是脚本目录，{@code images.read("./mario.png")} 找不到文件会返回
 * null，下一行就抛 {@code NullPointerException: image = null}。</li>
 * <li><b>导入（查看/编辑）</b>不能只导入单个 .js，必须把同目录的伴生文件一起导入，
 * 否则一运行就报 {@code image = null} / {@code Cannot find module: ./xxx.js}。</li>
 * </ol>
 *
 * <p>磁盘上的示例树（{@code files/sample/…}）由 {@link BundledSampleSync} 在升级时整体对齐，
 * 这里的 {@link #materialize} 只是「缺什么补什么」的兜底。
 */
public final class SampleFiles {

    private static final String TAG = "SampleFiles";
    public static final String ASSET_ROOT = "sample";
    /**
     * 运行/导入时单个子目录超过这个大小就不跟着拷（防止把超大的模型库整个搬进 cacheDir）。
     *
     * <p>⚠️ 别调小：8 MB 曾经把**工程自己要用的模型**也挡在门外 ——
     * 「象棋自动走棋」的 {@code assets/models/chessai_160x320_v5r.onnx} 是 9.2 MB、
     * 「YOLO目标检测/DNN/models」是 9.3 MB，两个整棵目录都被跳过，
     * 而工程是「磁盘优先、{@code asset://} 兜底」的读法（且兜底那条路径拼不出正确的 asset 名），
     * 脚本侧只看得到 {@code IllegalArgumentException: 无法读取 YOLO labels: …/assets/models/xxx.txt}，
     * 完全看不出是拷贝阶段丢的文件。
     *
     * <p>16 MB = 覆盖当前随包的两个模型目录，同时仍然挡住 ≥16 MB 的模型库
     * （原先注释里提到的 18.8 MB 那份）。
     *
     * <p>⚠️ 调用方请一律用这个常量（曾经 {@code MiuixResourceFragment} 自己硬编码了一份 8 MB，
     * 改这里对「资源页 → 运行」不起作用，白查一轮）。
     */
    public static final long MAX_SUBDIR_BYTES = 16L * 1024 * 1024;

    private SampleFiles() {
    }

    /** {@code sample/xxx/yyy.js} → {@code xxx/yyy.js}（相对 assets 根目录下的 sample）。 */
    public static String relativePath(String assetPath) {
        String path = assetPath == null ? "" : assetPath;
        if (path.startsWith(ASSET_ROOT + "/")) {
            return path.substring(ASSET_ROOT.length() + 1);
        }
        return path;
    }

    /** 最后一段名字（{@code sample/a/b.js} → {@code b.js}）。 */
    public static String nameOf(String assetPath) {
        int index = assetPath.lastIndexOf('/');
        return index < 0 ? assetPath : assetPath.substring(index + 1);
    }

    /** 去掉 {@code .js} 的名字，用作导入目录名。 */
    public static String baseNameOf(String assetPath) {
        String name = nameOf(assetPath);
        return name.toLowerCase().endsWith(".js") ? name.substring(0, name.length() - 3) : name;
    }

    /** 上一级目录的 asset 路径（{@code sample/a/b.js} → {@code sample/a}）。 */
    public static String parentOf(String assetPath) {
        int index = assetPath.lastIndexOf('/');
        return index <= 0 ? ASSET_ROOT : assetPath.substring(0, index);
    }

    /** 磁盘上的示例根目录：{@code files/sample}。 */
    public static File sampleRoot(Context context) {
        return new File(context.getFilesDir(), ASSET_ROOT);
    }

    /** 某个示例在磁盘上的目标文件：{@code files/sample/<相对路径>}。 */
    public static File diskFile(Context context, String assetPath) {
        return new File(sampleRoot(context), relativePath(assetPath));
    }

    /**
     * 确保示例本体及其<b>同目录</b>的伴生文件都在磁盘上（已存在的文件不覆盖），返回磁盘文件。
     * 全部齐全时几乎不做 IO（只 stat 一遍）。失败返回 null，调用方应回退到旧的「跑 assets 正文」。
     */
    public static File materialize(Context context, String assetPath) {
        File dir = diskFile(context, assetPath).getParentFile();
        File file = diskFile(context, assetPath);
        try {
            // maxSubdirBytes = 0：只补同级文件，不进子目录
            copyAssetTree(context, parentOf(assetPath), dir, false, 0);
        } catch (IOException e) {
            Log.w(TAG, "补齐示例伴生文件失败：" + assetPath, e);
        }
        return file.isFile() ? file : null;
    }

    /**
     * 「查看/编辑」的导入：把示例**连同一目录的伴生文件**导入到脚本目录下的一个子目录，
     * 返回要打开的文件。
     *
     * <p>目录名：示例所在目录里有 {@code project.json} 时用该目录名（工程型示例，
     * 这样导入后仍是一个工程）；否则用示例自己的名字（避免不同目录下的 {@code main.js}
     * 之类互相撞名）。已存在的文件不覆盖 —— 用户改过的东西不丢，编辑器里有「重置」可从内置版本恢复。
     */
    public static File importForEdit(Context context, String assetPath) throws IOException {
        File scriptDir = new File(Pref.getScriptDirPath());
        File target = new File(scriptDir, importFolderName(context, assetPath));
        copyAssetTree(context, parentOf(assetPath), target, false, MAX_SUBDIR_BYTES);
        File file = new File(target, nameOf(assetPath));
        return file.isFile() ? file : null;
    }

    private static String importFolderName(Context context, String assetPath) {
        String parent = parentOf(assetPath);
        try {
            for (String child : context.getAssets().list(parent)) {
                if ("project.json".equals(child)) {
                    return nameOf(parent);
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "列不出示例目录：" + parent, e);
        }
        return baseNameOf(assetPath);
    }

    /**
     * 把 assets 里的目录/文件复制到 target。
     *
     * @param maxSubdirBytes 子目录体积上限：超过就不进（{@code <= 0} 表示完全不进子目录）
     * @param overwrite      已存在的文件是否覆盖
     */
    public static void copyAssetTree(Context context, String assetPath, File target,
                                     boolean overwrite, long maxSubdirBytes) throws IOException {
        AssetManager assets = context.getAssets();
        String[] children = assets.list(assetPath);
        if (children == null || children.length == 0) {
            copyAssetFile(assets, assetPath, target, overwrite);
            return;
        }
        if (maxSubdirBytes <= 0) {
            for (String child : children) {
                String childAsset = assetPath + "/" + child;
                if (isDirectory(assets, childAsset)) {
                    continue;
                }
                copyAssetFile(assets, childAsset, new File(target, child), overwrite);
            }
            return;
        }
        if (!target.isDirectory() && !target.mkdirs() && !target.isDirectory()) {
            throw new IOException("无法创建目录 " + target);
        }
        for (String child : children) {
            String childAsset = assetPath + "/" + child;
            File childTarget = new File(target, child);
            if (isDirectory(assets, childAsset)) {
                long bytes = assetsBytes(assets, childAsset);
                if (bytes > maxSubdirBytes) {
                    // 用 WARN：这一步丢掉的可能是脚本运行必需的资源（曾经静默丢掉过象棋模型，
                    // 表现为脚本侧 "无法读取 YOLO labels"，排查时极难联想到拷贝阶段）。
                    Log.w(TAG, "跳过过大的内置子目录（" + (bytes / 1048576) + "MB > "
                            + (maxSubdirBytes / 1048576) + "MB）：" + childAsset);
                    continue;
                }
                copyAssetTree(context, childAsset, childTarget, overwrite, maxSubdirBytes);
            } else {
                copyAssetFile(assets, childAsset, childTarget, overwrite);
            }
        }
    }

    private static boolean isDirectory(AssetManager assets, String assetPath) {
        try {
            String[] children = assets.list(assetPath);
            return children != null && children.length > 0;
        } catch (IOException e) {
            return false;
        }
    }

    private static void copyAssetFile(AssetManager assets, String assetPath, File target,
                                      boolean overwrite) throws IOException {
        if (!overwrite && target.isFile()) {
            return;
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("无法创建目录 " + parent);
        }
        try (InputStream input = assets.open(assetPath);
             OutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
        }
    }

    /** 目录体积，只用来判断「要不要跟着拷」。 */
    private static long assetsBytes(AssetManager assets, String assetPath) {
        try {
            String[] children = assets.list(assetPath);
            if (children == null || children.length == 0) {
                try (InputStream input = assets.open(assetPath)) {
                    return input.available();
                }
            }
            long total = 0;
            for (String child : children) {
                total += assetsBytes(assets, assetPath + "/" + child);
            }
            return total;
        } catch (IOException e) {
            return Long.MAX_VALUE;   // 量不出来就当成过大，宁可跳过
        }
    }
}
