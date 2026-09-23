package com.jdkshen.aijspro.model.script;

import java.io.File;
import java.io.FileFilter;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
}
