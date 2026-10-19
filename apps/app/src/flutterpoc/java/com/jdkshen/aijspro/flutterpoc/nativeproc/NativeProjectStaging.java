package com.jdkshen.aijspro.flutterpoc.nativeproc;

import android.content.Context;
import android.util.Log;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

/**
 * 把「不在应用私有目录里的脚本项目」整棵复制到应用私有目录，再从那里执行。
 *
 * <p>为什么需要它：Pro 打包版把项目放进 {@code /data/user/0/<pkg>/files/project} 后，
 * 同一份带 {@code lib/*.so} 的项目能正常加载（包括 ImGui）；而 {@code :native} 隔离进程
 * 之前直接从 {@code /sdcard/...} 执行，加载 .so/.dex 时 logcat 里会出现
 * {@code Attempt to load writable dex file} 之类的差异。Phase0 先对齐 Pro 的目录布局：
 * RUN 前把项目复制到 {@code <filesDir>/native-staging/<路径hash>/}，入口脚本与工作目录都
 * 换到这份私有副本上（脚本本身不用改）。
 *
 * <p>规则（与 task-10 描述一致）：
 * <ul>
 *   <li>项目已经在 {@code getFilesDir()} 之下（例如 Pro 打包版的私有项目目录）：原样返回，
 *       不重复复制；</li>
 *   <li>否则复制到 {@code <filesDir>/native-staging/<稳定短hash>}，hash 取项目规范化绝对
 *       路径的 SHA-1 前 12 个十六进制字符，同一路径每次得到同一个目录；</li>
 *   <li>复制前先删掉同 hash 的旧副本，避免上次残留的 .so/旧文件影响本次运行；</li>
 *   <li>保留相对路径与文件名；跳过名为 {@code build}、{@code .git} 的目录，跳过
 *       {@code *.apk}（打包产物，脚本运行时用不到；项目自带的 26MB ImGui APK 就靠这条省掉），
 *       但 <b>不跳过</b> .so/.dex/.js/图片等资源；</li>
 *   <li>单文件上限 {@value #MAX_FILE_BYTES_TEXT}、整个项目上限 {@value #MAX_TOTAL_BYTES_TEXT}，
 *       超限直接抛中文 {@link IOException}，不做静默截断/跳过；</li>
 *   <li>复制用 {@link FileInputStream}/{@link FileOutputStream}，失败向上抛（服务端会转成
 *       给主进程的 ERROR(102)），并清掉半成品目录。</li>
 * </ul>
 *
 * <p>注意：暂存副本是「一次性」的 —— 每次 RUN 都会先删旧副本再重新复制，脚本运行中写进
 * 暂存目录的文件不会回写到原始项目目录（与 Pro 打包版的语义一致）。
 *
 * <p>Phase1 起 {@code :script} 进程的 {@code ScriptHostStaging} 也复用本类，但用各自的
 * 暂存根目录名（{@code native-staging} / {@code script-staging}）—— 两个进程同跑同一项目时
 * 不能互相清空对方正在使用的副本。见 {@link #stageIfNeeded(Context, File, String)}。
 */
public final class NativeProjectStaging {

    private static final String TAG = "NativeProjectStaging";

    /** 暂存根目录名（位于 {@code context.getFilesDir()} 下）。 */
    private static final String STAGING_DIR_NAME = "native-staging";
    /** 单文件上限 64MB。 */
    private static final long MAX_FILE_BYTES = 64L * 1024 * 1024;
    /** 整个项目上限 256MB。 */
    private static final long MAX_TOTAL_BYTES = 256L * 1024 * 1024;
    /** 给超出上限时的中文提示用（编译期字面量，见类注释）。 */
    private static final String MAX_FILE_BYTES_TEXT = "64MB";
    private static final String MAX_TOTAL_BYTES_TEXT = "256MB";
    /** 复制缓冲区 64KB。 */
    private static final int BUFFER_SIZE = 64 * 1024;
    /** 递归深度上限：防止符号链接成环时把服务主线程卡死。 */
    private static final int MAX_DEPTH = 32;
    /** 目录名跳过：构建产物与版本库元数据。 */
    private static final String DIR_BUILD = "build";
    private static final String DIR_GIT = ".git";
    /** 文件扩展名跳过：APK 是打包产物，脚本运行不需要。 */
    private static final String EXTENSION_APK = ".apk";
    /** hash 取 SHA-1 十六进制的前 12 位。 */
    private static final int HASH_LENGTH = 12;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private NativeProjectStaging() {
    }

    /**
     * 按需把项目目录暂存到应用私有目录。
     *
     * @param context    任意 Context（内部只用 {@link Context#getFilesDir()}）
     * @param projectDir 项目根目录（入口脚本所在项目；调用方保证它与脚本的相对关系）
     * @return 实际应执行的项目目录：已在私有目录时原样返回，否则返回私有目录里的副本
     * @throws IOException 目录不存在、私有目录不可用、复制失败、超出大小上限等（消息为中文）
     */
    public static File stageIfNeeded(Context context, File projectDir) throws IOException {
        return stageIfNeeded(context, projectDir, STAGING_DIR_NAME);
    }

    /**
     * 同 {@link #stageIfNeeded(Context, File)}，但可指定暂存根目录名。
     *
     * <p>Phase1 的 {@code :script} 宿主用 {@code "script-staging"}，与 {@code :native} 的
     * {@code "native-staging"} 分开：两个进程如果同时执行同一个项目，旧副本清理/重写不能影响
     * 对方正在使用的私有副本。
     *
     * @param stagingDirName 暂存根目录名（位于 {@code context.getFilesDir()} 下）；
     *                       空/非法时退回默认 {@code native-staging}
     */
    public static File stageIfNeeded(Context context, File projectDir, String stagingDirName) throws IOException {
        if (context == null) {
            throw new IOException("暂存项目失败：Context 为空");
        }
        if (projectDir == null) {
            throw new IOException("暂存项目失败：项目目录为空");
        }
        String stagingRootName = sanitizeStagingDirName(stagingDirName);
        final File project;
        try {
            project = projectDir.getCanonicalFile();
        } catch (IOException error) {
            throw new IOException("暂存项目失败：无法解析项目路径：" + projectDir.getPath(), error);
        }
        if (!project.isDirectory()) {
            throw new IOException("暂存项目失败：项目目录不存在：" + project.getPath());
        }
        final File filesRoot;
        try {
            File filesDir = context.getFilesDir();
            if (filesDir == null) {
                throw new IOException("暂存项目失败：应用私有目录不可用");
            }
            filesRoot = filesDir.getCanonicalFile();
        } catch (IOException error) {
            if (error.getMessage() != null && error.getMessage().startsWith("暂存项目失败")) {
                throw error;
            }
            throw new IOException("暂存项目失败：无法解析应用私有目录", error);
        }

        if (isUnder(project, filesRoot)) {
            // 已经在私有目录（例如 Pro 打包版的项目目录）：本就不需要复制，避免无谓开销。
            Log.i(TAG, "项目已在应用私有目录，跳过暂存：" + project.getPath());
            return project;
        }

        File stagingRoot = new File(filesRoot, stagingRootName);
        File target = new File(stagingRoot, hashOf(project.getPath()));
        // 每次运行前清掉同 hash 旧副本：上一次运行留下的 .so/旧文件不能混进本次项目。
        if (!deleteRecursively(target) || target.exists()) {
            throw new IOException("暂存项目失败：无法清理旧的项目副本：" + target.getPath());
        }
        if (!target.mkdirs() && !target.isDirectory()) {
            throw new IOException("暂存项目失败：无法创建私有目录：" + target.getPath());
        }

        CopyStats stats = new CopyStats();
        try {
            copyTree(project, target, stats, 0);
        } catch (IOException error) {
            deleteRecursively(target);
            throw error;
        } catch (RuntimeException error) {
            deleteRecursively(target);
            throw new IOException("暂存项目失败：复制项目内容出错：" + error, error);
        }
        Log.i(TAG, "已把项目复制到私有目录：" + project.getPath() + " -> " + target.getPath()
                + "（" + stats.files + " 个文件，" + readableSize(stats.bytes) + "）");
        return target;
    }

    // ------------------------------------------------------------------
    // 复制
    // ------------------------------------------------------------------

    private static void copyTree(File sourceDir, File targetDir, CopyStats stats, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("暂存项目失败：目录层级过深（疑似符号链接成环）：" + sourceDir.getPath());
        }
        File[] children = sourceDir.listFiles();
        if (children == null) {
            throw new IOException("暂存项目失败：无法读取目录：" + sourceDir.getPath());
        }
        // 固定按名字排序：复制顺序稳定，排查问题时目录结构可预期。
        Arrays.sort(children, new Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                return left.getName().compareTo(right.getName());
            }
        });
        for (File child : children) {
            String name = child.getName();
            if (child.isDirectory()) {
                if (isSkippedDirectory(name)) {
                    continue;
                }
                File targetChild = new File(targetDir, name);
                if (!targetChild.mkdirs() && !targetChild.isDirectory()) {
                    throw new IOException("暂存项目失败：无法创建目录：" + targetChild.getPath());
                }
                copyTree(child, targetChild, stats, depth + 1);
            } else if (child.isFile()) {
                if (isSkippedFile(name)) {
                    continue;
                }
                copyFile(child, new File(targetDir, name), stats);
            }
            // 既不是普通文件也不是目录的条目（FIFO/设备节点等）直接跳过：项目里不该有，
            // 盲目打开可能把服务主线程卡住。
        }
    }

    private static void copyFile(File source, File target, CopyStats stats) throws IOException {
        long length = source.length();
        if (length > MAX_FILE_BYTES) {
            throw new IOException("暂存项目失败：文件超过 " + MAX_FILE_BYTES_TEXT + " 上限："
                    + source.getPath() + "（" + readableSize(length) + "）");
        }
        if (stats.bytes + length > MAX_TOTAL_BYTES) {
            throw new IOException("暂存项目失败：项目总大小超过 " + MAX_TOTAL_BYTES_TEXT
                    + " 上限（复制到 " + source.getPath() + " 时超限）");
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("暂存项目失败：无法创建目录：" + parent.getPath());
        }
        InputStream input = null;
        OutputStream output = null;
        try {
            input = new FileInputStream(source);
            output = new FileOutputStream(target);
            byte[] buffer = new byte[BUFFER_SIZE];
            long fileBytes = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                fileBytes += read;
                stats.bytes += read;
                // 复制过程中文件变大也要拦住（source.length() 只是复制前的快照）。
                if (fileBytes > MAX_FILE_BYTES) {
                    throw new IOException("暂存项目失败：文件超过 " + MAX_FILE_BYTES_TEXT
                            + " 上限：" + source.getPath());
                }
                if (stats.bytes > MAX_TOTAL_BYTES) {
                    throw new IOException("暂存项目失败：项目总大小超过 " + MAX_TOTAL_BYTES_TEXT
                            + " 上限（复制到 " + source.getPath() + " 时超限）");
                }
            }
        } catch (IOException error) {
            String message = error.getMessage();
            if (message != null && message.startsWith("暂存项目失败")) {
                throw error;
            }
            throw new IOException("暂存项目失败：复制文件失败：" + source.getPath()
                    + " -> " + target.getPath() + "：" + message, error);
        } finally {
            closeQuietly(input);
            closeQuietly(output);
        }
        stats.files++;
    }

    // ------------------------------------------------------------------
    // 规则 / 工具
    // ------------------------------------------------------------------

    /** 跳过构建产物与版本库元数据目录（按原样名字比较，和 Unix 习惯一致）。 */
    private static boolean isSkippedDirectory(String name) {
        return DIR_BUILD.equals(name) || DIR_GIT.equals(name);
    }

    /** 跳过 APK 打包产物（扩展名大小写不敏感）。 */
    private static boolean isSkippedFile(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(EXTENSION_APK);
    }

    /** 暂存根目录名消毒：只允许字母/数字/下划线/中划线，避免调用方传进路径分隔符。 */
    private static String sanitizeStagingDirName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return STAGING_DIR_NAME;
        }
        String trimmed = name.trim();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            boolean valid = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.';
            if (!valid) {
                return STAGING_DIR_NAME;
            }
        }
        return trimmed;
    }

    /**
     * child 是否在 dir 之下（两者都是规范化绝对路径，含相等的情况）。
     * 公开给 {@code :script} 的 {@code ScriptHostStaging} 复用，避免两套路径判断逻辑分叉。
     */
    public static boolean isUnder(File child, File dir) throws IOException {
        String childPath = child.getCanonicalPath();
        String dirPath = dir.getCanonicalPath();
        if (childPath.equals(dirPath)) {
            return true;
        }
        String prefix = dirPath.endsWith(File.separator) ? dirPath : dirPath + File.separator;
        return childPath.startsWith(prefix);
    }

    /** 项目路径 -> 稳定短 hash（SHA-1 前 12 位十六进制）。 */
    private static String hashOf(String path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(path.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(HASH_LENGTH);
            for (byte value : bytes) {
                int unsigned = value & 0xFF;
                builder.append(HEX[unsigned >>> 4]).append(HEX[unsigned & 0x0F]);
                if (builder.length() >= HASH_LENGTH) {
                    break;
                }
            }
            return builder.substring(0, HASH_LENGTH);
        } catch (Exception error) {
            throw new IOException("暂存项目失败：无法计算项目路径摘要", error);
        }
    }

    /** 递归删除；返回是否删干净（调用方据此决定要不要报错）。 */
    private static boolean deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return true;
        }
        boolean deleted = true;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleted = deleteRecursively(child) && deleted;
                }
            }
        }
        return file.delete() && deleted;
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Throwable ignored) {
            // 读/写已经结束时 close 失败没有补救手段，忽略。
        }
    }

    private static String readableSize(long bytes) {
        if (bytes < 1024L) {
            return bytes + "B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.US, "%.1fKB", bytes / 1024.0);
        }
        return String.format(Locale.US, "%.1fMB", bytes / (1024.0 * 1024.0));
    }

    /** 复制统计：日志里报告文件数与总字节数。 */
    private static final class CopyStats {
        long bytes;
        int files;
    }
}
