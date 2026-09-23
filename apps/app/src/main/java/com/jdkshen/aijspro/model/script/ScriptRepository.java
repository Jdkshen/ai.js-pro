package com.jdkshen.aijspro.model.script;

import java.io.File;
import java.io.FileFilter;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 统一的「脚本文件系统事实」只读门面（B1 批次1）。
 *
 * <p>此前目录枚举分散在多个实现里（{@code PFile.listFiles}、miuix 直用 {@code java.io.File}、
 * MCP/打包各扫各的）。本门面先把**枚举与条目描述**收成一处，调用方继续决定过滤/排序语义。
 *
 * <p>语义严格对齐现状（本批不改行为）：
 * <ul>
 *   <li>枚举默认跳过隐藏条目（{@code PFile.listFiles} 的历史语义）；需要时用 {@code includeHidden} 放开。</li>
 *   <li>目录不存在 / 无权限 → 空列表（旧行为：{@code listFiles() == null} 被当作空流）。</li>
 *   <li>「脚本文件」 = {@code .js} / {@code .auto}（与 {@code Scripts.FILE_FILTER} 完全一致，大小写敏感）。</li>
 * </ul>
 *
 * <p>写入类操作（新建 / 重命名 / 删除 / 导入 / 覆盖）属 B1 批次2，不在本类范围内。
 */
public final class ScriptRepository {

    private ScriptRepository() {
    }

    /** 「脚本文件」判定：与历史 {@code Scripts.FILE_FILTER} 一致（大小写敏感，只认 .js/.auto）。 */
    public static boolean isScriptFile(String name) {
        return name != null && (name.endsWith(".js") || name.endsWith(".auto"));
    }

    public static List<ScriptDescriptor> list(File directory) {
        return list(directory, null, false);
    }

    public static List<ScriptDescriptor> list(File directory, FileFilter filter) {
        return list(directory, filter, false);
    }

    /**
     * 列目录并描述每个子条目（不排序，顺序 = 文件系统返回顺序，与旧枚举一致）。
     *
     * @param filter       与 {@code File.listFiles(FileFilter)} 同语义；null = 不过滤。
     * @param includeHidden 是否包含 {@code .} 开头的隐藏条目（默认 false，对齐 {@code PFile.listFiles}）。
     */
    public static List<ScriptDescriptor> list(File directory, FileFilter filter, boolean includeHidden) {
        if (directory == null) {
            return Collections.emptyList();
        }
        File[] children = filter == null ? directory.listFiles() : directory.listFiles(filter);
        if (children == null || children.length == 0) {
            return Collections.emptyList();
        }
        List<ScriptDescriptor> result = new ArrayList<>(children.length);
        for (File child : children) {
            if (!includeHidden && child.getName().startsWith(".")) {
                continue;
            }
            result.add(ScriptDescriptor.of(child));
        }
        return result;
    }

    /**
    /**
     * 递归统计匹配的文件数（目录不计入），与旧 {@code WorkspaceFileProvider.countScriptFiles} 同语义
     * （隐藏条目同样跳过）。
     *
     * @param fileNameFilter 只作用于文件；null = 全部计数。
     */
    public static int countRecursively(File directory, FilenameFilter fileNameFilter) {
        int count = 0;
        for (ScriptDescriptor child : list(directory)) {
            if (child.isDirectory()) {
                count += countRecursively(child.toFile(), fileNameFilter);
            } else if (fileNameFilter == null
                    || fileNameFilter.accept(child.toFile(), child.getName())) {
                count++;
            }
        }
        return count;
    }

    /**
     * 深度优先列出目录下的全部文件（**包含隐藏**；目录本身不出现在结果中）。
     * 顺序与 {@code FileTreeWalk}（Kotlin {@code walkTopDown()}+isFile）一致：前序 DFS、同级按文件系统顺序。
     * 供 MCP 搜索/递归列表等内部场景使用（用户可见列表请用 {@link #list} 的默认隐藏过滤）。
     *
     * @param maxFiles 最多返回多少个文件；≤0 表示不限。
     * @param fileNameFilter 只作用于文件，**通过后才计入上限**（与 {@code filter{...}.take(n)} 同语义）；null = 全部。
     */
    public static List<ScriptDescriptor> walkFiles(File directory, int maxFiles, FilenameFilter fileNameFilter) {
        List<ScriptDescriptor> result = new ArrayList<>();
        walkInto(directory, result, maxFiles, fileNameFilter);
        return result;
    }

    public static List<ScriptDescriptor> walkFiles(File directory, int maxFiles) {
        return walkFiles(directory, maxFiles, null);
    }

    private static boolean walkInto(File directory, List<ScriptDescriptor> out, int maxFiles,
                                    FilenameFilter fileNameFilter) {
        File[] children = directory == null ? null : directory.listFiles();
        if (children == null) {
            return false;
        }
        for (File child : children) {
            if (maxFiles > 0 && out.size() >= maxFiles) {
                return true;
            }
            if (child.isFile()) {
                if (fileNameFilter == null || fileNameFilter.accept(child, child.getName())) {
                    out.add(ScriptDescriptor.of(child));
                }
            } else if (child.isDirectory()) {
                if (walkInto(child, out, maxFiles, fileNameFilter)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 写入原语（B1 批次2a）：语义逐条对齐旧调用点（PFiles / PFile），本批只做收口、不改行为。
    // 备份/回退语义（批次2b）以后只改本类，调用点不动。
    // ------------------------------------------------------------------

    /** 新建文件（必要时先建父目录）；已存在返回 false —— 对齐 {@code PFiles.createIfNotExists}。 */
    public static boolean createFile(File file) {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            // 旧实现（ensureDir）忽略 mkdirs 结果，失败时由后面的 createNewFile 兜底为 false。
            parent.mkdirs();
        }
        if (file.exists()) {
            return false;
        }
        try {
            return file.createNewFile();
        } catch (IOException e) {
            return false;
        }
    }

    /** 覆盖写入文本（UTF-8），失败抛 UncheckedIOException —— 对齐 {@code PFiles.write(File, String)}。 */
    public static void writeText(File file, String text) {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 递归删除（文件或目录）；目录中任一项失败立即中止返回 false —— 对齐 {@code PFiles.deleteRecursively}。 */
    public static boolean deleteRecursively(File file) {
        if (file.isFile()) {
            return file.delete();
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                if (!deleteRecursively(child)) {
                    return false;
                }
            }
        }
        return file.delete();
    }

    /**
     * 同目录内重命名；成功返回新文件，失败返回 null（调用方维持「失败 = 文件没变」的判定，
     * 与旧 {@code PFile.renameTo(String)} 返回自身的语义对应）。
     * 目标已存在时的行为由操作系统决定（与旧实现一致，不做额外策略）。
     */
    public static File renameTo(File source, String newName) {
        File target = new File(source.getParentFile(), newName);
        return source.renameTo(target) ? target : null;
    }

    // ------------------------------------------------------------------
    // 备份 / 回退（B1 批次2b）：删除不再直接抹掉，而是先移入备份区。
    // 约定：备份根 = &lt;scriptRoot&gt;/.aijspro-backups/（隐藏目录——PFile 枚举跳过 「.」 开头，
    // 文件页不会显示）；备份路径保留原相对目录结构，文件名 = 原名 + .yyyyMMdd-HHmmss-SSS.bak。
    // 备份失败时调用方应放弃破坏性操作（宁可不删，不丢数据）。
    // ------------------------------------------------------------------

    public static final String BACKUP_DIR_NAME = ".aijspro-backups";

    /** 保留的最大备份条数（超出按时间从旧到新清理）。 */
    private static final int BACKUP_KEEP_MAX = 50;
    /** 保留的最长天数（超过即清理）。 */
    private static final long BACKUP_KEEP_DAYS = 30;
    private static final Pattern BACKUP_NAME_PATTERN =
            Pattern.compile("^(.*)\\.(\\d{8}-\\d{6}-\\d{3})\\.bak$");

    /** 备份根目录（不创建）。 */
    public static File backupRoot(File scriptRoot) {
        return scriptRoot == null ? null : new File(scriptRoot, BACKUP_DIR_NAME);
    }

    /** file 是否在 root 目录内（用于区分「用户脚本目录」与临时/外部路径）。 */
    public static boolean isInsideRoot(File file, File root) {
        return relativePathOf(file, root) != null;
    }

    /**
     * 把 {@code target} 移入备份区；成功返回备份文件，失败返回 null。
     * 仅支持 {@code scriptRoot} 内的目标（与备份区同卷，rename 才能原子完成）；
     * root 外目标返回 null，由调用方决定是否保持旧行为。
     */
    public static File backupTo(File target, File scriptRoot) {
        if (target == null || scriptRoot == null || !target.exists()) {
            return null;
        }
        File root = backupRoot(scriptRoot);
        if (root == null || root.equals(target)) {
            return null;
        }
        String relative = relativePathOf(target, scriptRoot);
        if (relative == null) {
            return null;
        }
        File payload = new File(root, relative);
        File dest = new File(payload.getParentFile(),
                payload.getName() + "." + backupTimestamp() + ".bak");
        File parent = dest.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            return null;
        }
        if (dest.exists()) {
            return null;
        }
        // 同根存储下 rename 是原子的；失败（权限/异常）不退化——宁可不删，不丢数据。
        return target.renameTo(dest) ? dest : null;
    }

    /** 列出备份区里的全部条目（文件与目录），按时间倒序。 */
    public static List<ScriptDescriptor> listBackups(File scriptRoot) {
        List<ScriptDescriptor> result = new ArrayList<>();
        collectBackups(backupRoot(scriptRoot), result);
        result.sort((a, b) -> Long.compare(b.getLastModified(), a.getLastModified()));
        return result;
    }

    private static void collectBackups(File directory, List<ScriptDescriptor> out) {
        if (directory == null) {
            return;
        }
        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.getName().endsWith(".bak")) {
                out.add(ScriptDescriptor.of(child));
            } else if (child.isDirectory()) {
                collectBackups(child, out);
            }
        }
    }

    /**
     * 把备份恢复到原位置；目标已存在或备份不可解析时返回 null（不覆盖）。
     */
    public static File restoreBackup(File backupFile, File scriptRoot) {
        if (backupFile == null || scriptRoot == null || !backupFile.exists()) {
            return null;
        }
        String relative = relativePathOf(backupFile, backupRoot(scriptRoot));
        if (relative == null) {
            return null;
        }
        Matcher matcher = BACKUP_NAME_PATTERN.matcher(backupFile.getName());
        if (!matcher.matches()) {
            return null;
        }
        String originalName = matcher.group(1);
        File relativeParent = new File(relative).getParentFile();
        File target = relativeParent == null
                ? new File(scriptRoot, originalName)
                : new File(new File(scriptRoot, relativeParent.getPath()), originalName);
        if (target.exists()) {
            return null;
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            return null;
        }
        return backupFile.renameTo(target) ? target : null;
    }

    /** 备份条目的展示名：原相对路径（去掉时间戳后缀），解析失败回退文件名。 */
    public static String backupDisplayName(File backupFile, File scriptRoot) {
        String relative = relativePathOf(backupFile, backupRoot(scriptRoot));
        if (relative == null) {
            relative = backupFile.getName();
        }
        Matcher matcher = BACKUP_NAME_PATTERN.matcher(new File(relative).getName());
        if (matcher.matches()) {
            File parent = new File(relative).getParentFile();
            String name = matcher.group(1);
            return parent == null ? name : parent.getPath() + "/" + name;
        }
        return relative;
    }

    /** 清理备份：超过 {@link #BACKUP_KEEP_MAX} 条或超过 {@link #BACKUP_KEEP_DAYS} 天的从旧到新删除；返回删除条数。 */
    public static int pruneBackups(File scriptRoot) {
        List<ScriptDescriptor> backups = listBackups(scriptRoot);
        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(BACKUP_KEEP_DAYS);
        int removed = 0;
        for (int i = 0; i < backups.size(); i++) {
            ScriptDescriptor backup = backups.get(i);
            if (i >= BACKUP_KEEP_MAX || backup.getLastModified() < cutoff) {
                if (deleteRecursively(backup.toFile())) {
                    removed++;
                }
            }
        }
        pruneEmptyDirs(backupRoot(scriptRoot));
        return removed;
    }

    /** 删除备份区里已无内容的目录（只删空目录，不动用户可能自己放进去的东西）。 */
    private static void pruneEmptyDirs(File directory) {
        File[] children = directory == null ? null : directory.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (!child.isDirectory()) {
                continue;
            }
            pruneEmptyDirs(child);
            File[] rest = child.listFiles();
            if (rest != null && rest.length == 0) {
                child.delete();
            }
        }
    }

    private static String backupTimestamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(new Date());
    }

    /** file 相对 root 的路径；不在 root 内（或就是 root）时返回 null。 */
    private static String relativePathOf(File file, File root) {
        if (file == null || root == null) {
            return null;
        }
        String filePath = file.getAbsolutePath();
        String rootPath = root.getAbsolutePath();
        if (filePath.equals(rootPath) || !filePath.startsWith(rootPath + File.separator)) {
            return null;
        }
        return filePath.substring(rootPath.length() + 1);
    }
}
