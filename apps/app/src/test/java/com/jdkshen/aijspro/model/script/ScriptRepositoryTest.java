package com.jdkshen.aijspro.model.script;

import org.junit.Test;

import java.io.File;
import java.io.FileFilter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * B1 批次1：锁定 {@link ScriptRepository} 的只读语义。
 *
 * <p>这些断言的参照物是**旧实现的真实行为**（{@code Scripts.FILE_FILTER} +
 * {@code PFile.listFiles} 的隐藏过滤 + 目录不存在当空处理），换实现但语义不变。
 */
public class ScriptRepositoryTest {

    /** 历史 {@code Scripts.FILE_FILTER} 的等价物（不引用 Scripts，避免触发其 Android 初始化）。 */
    private static final FileFilter LEGACY_FILTER =
            file -> file.isDirectory() || file.getName().endsWith(".js") || file.getName().endsWith(".auto");

    private static File newTempDir() throws Exception {
        return Files.createTempDirectory("script-repo").toFile();
    }

    private static void touch(File dir, String name) throws Exception {
        Files.write(new File(dir, name).toPath(), "x".getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> namesOf(List<ScriptDescriptor> items) {
        List<String> names = new ArrayList<>();
        for (ScriptDescriptor item : items) {
            names.add(item.getName());
        }
        Collections.sort(names);
        return names;
    }

    @Test
    public void listDescribesEachEntry() throws Exception {
        File dir = newTempDir();
        touch(dir, "a.js");
        touch(dir, "note.txt");
        File sub = new File(dir, "sub");
        assertTrue(sub.mkdirs());

        List<ScriptDescriptor> items = ScriptRepository.list(dir);
        assertEquals(Arrays.asList("a.js", "note.txt", "sub"), namesOf(items));

        for (ScriptDescriptor item : items) {
            if ("sub".equals(item.getName())) {
                assertTrue(item.isDirectory());
                assertFalse(item.isScript());
            } else {
                assertFalse(item.isDirectory());
                assertEquals(1, item.getSize());
                assertTrue(item.getLastModified() > 0);
                assertEquals("a.js".equals(item.getName()), item.isScript());
            }
            assertEquals(item.getPath(), item.toFile().getPath());
        }
    }

    @Test
    public void listOfMissingDirectoryIsEmpty() {
        assertTrue(ScriptRepository.list(new File("build/definitely-not-here-42")).isEmpty());
    }

    @Test
    public void listSkipsHiddenEntriesUntilExplicitlyIncluded() throws Exception {
        File dir = newTempDir();
        touch(dir, ".hidden.js");
        touch(dir, "visible.js");

        assertEquals(Collections.singletonList("visible.js"), namesOf(ScriptRepository.list(dir)));
        assertEquals(Arrays.asList(".hidden.js", "visible.js"),
                namesOf(ScriptRepository.list(dir, null, true)));
    }

    /** 与旧 {@code Scripts.FILE_FILTER} 的组合语义逐项对齐（.js/.auto 通过、隐藏跳过、目录通过）。 */
    @Test
    public void listWithLegacyFilterMatchesOldSemantics() throws Exception {
        File dir = newTempDir();
        touch(dir, "a.js");
        touch(dir, "b.auto");
        touch(dir, "c.txt");
        touch(dir, ".d.js");
        assertTrue(new File(dir, "sub").mkdirs());

        assertEquals(Arrays.asList("a.js", "b.auto", "sub"),
                namesOf(ScriptRepository.list(dir, LEGACY_FILTER)));
    }

    /** 大小写敏感与 {@code endsWith} 语义要和旧过滤完全一致（.JS 不算脚本）。 */
    @Test
    public void isScriptFileMirrorsLegacyFilter() {
        assertTrue(ScriptRepository.isScriptFile("a.js"));
        assertTrue(ScriptRepository.isScriptFile("a.auto"));
        assertTrue(ScriptRepository.isScriptFile(".js"));
        assertFalse(ScriptRepository.isScriptFile("a.JS"));
        assertFalse(ScriptRepository.isScriptFile("a.txt"));
        assertFalse(ScriptRepository.isScriptFile("js"));
        assertFalse(ScriptRepository.isScriptFile(null));
    }

    /** 复刻旧 countScriptFiles 的语义：递归、目录不计、只数 .js（大小写不敏感）。 */
    @Test
    public void countRecursivelyMirrorsOldCountScriptFiles() throws Exception {
        File dir = newTempDir();
        touch(dir, "top.js");
        touch(dir, "skip.auto");
        File sub = new File(dir, "sub");
        assertTrue(sub.mkdirs());
        touch(sub, "nested.js");
        touch(sub, "upper.JS");

        int count = ScriptRepository.countRecursively(dir,
                (directory, name) -> name.toLowerCase().endsWith(".js"));
        assertEquals(3, count);
    }

    // ---- B1 批次2a：写入原语（锁定与旧 PFiles/PFile 对齐的语义） ----

    @Test
    public void createFileCreatesAndNeverOverwrites() throws Exception {
        File dir = newTempDir();
        File nested = new File(new File(dir, "a/b"), "x.js");

        assertTrue(ScriptRepository.createFile(nested));
        assertTrue(nested.isFile());

        // 已存在 → false 且内容不动（对齐 PFiles.createIfNotExists）
        ScriptRepository.writeText(nested, "keep");
        assertFalse(ScriptRepository.createFile(nested));
        assertEquals("keep", readUtf8(nested));
    }

    @Test
    public void writeTextWritesUtf8() throws Exception {
        File dir = newTempDir();
        File file = new File(dir, "a.js");
        ScriptRepository.writeText(file, "// 中文注释\nvar x = 1;\n");
        assertEquals("// 中文注释\nvar x = 1;\n", readUtf8(file));
    }

    @Test
    public void deleteRecursivelyRemovesFilesAndDirs() throws Exception {
        File dir = newTempDir();
        File tree = new File(dir, "tree");
        assertTrue(new File(tree, "inner").mkdirs());
        touch(tree, "a.js");
        touch(new File(tree, "inner"), "b.js");

        assertTrue(ScriptRepository.deleteRecursively(tree));
        assertFalse(tree.exists());

        // 不存在目标 → false（对齐 PFiles.deleteRecursively）
        assertFalse(ScriptRepository.deleteRecursively(new File(dir, "missing")));
    }

    @Test
    public void renameToReturnsNewFileOrNull() throws Exception {
        File dir = newTempDir();
        File source = new File(dir, "old.js");
        touch(dir, "old.js");

        File renamed = ScriptRepository.renameTo(source, "new.js");
        assertNotNull(renamed);
        assertEquals(new File(dir, "new.js").getPath(), renamed.getPath());
        assertTrue(renamed.isFile());
        assertFalse(source.exists());

        // 源不存在 → null（调用方据此判定「文件没变」）
        assertNull(ScriptRepository.renameTo(source, "again.js"));
    }

    private static String readUtf8(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    // ---- B1 批次4：walkFiles（MCP 递归搜索的枚举源） ----

    @Test
    public void walkFilesVisitsAllFilesDepthFirstIncludingHidden() throws Exception {
        File root = newTempDir();
        touch(root, "a.js");
        touch(root, ".hidden.js");
        touch(root, "note.txt");
        File sub = new File(root, "sub");
        assertTrue(sub.mkdirs());
        touch(sub, "b.js");
        File deep = new File(sub, "deep");
        assertTrue(deep.mkdirs());
        touch(deep, "d.js");

        List<String> names = new ArrayList<>();
        for (ScriptDescriptor descriptor : ScriptRepository.walkFiles(root, 0)) {
            names.add(descriptor.getName());
        }
        Collections.sort(names);
        assertEquals(Arrays.asList(".hidden.js", "a.js", "b.js", "d.js", "note.txt"), names);

        assertEquals(3, ScriptRepository.walkFiles(root, 3).size());

        // 过滤在计数之前（与 filter{take(n)} 同语义）：只数 .js，上限 2 → 恰好 2 个 .js
        List<ScriptDescriptor> js = ScriptRepository.walkFiles(root, 2,
                (dir, name) -> name.endsWith(".js"));
        assertEquals(2, js.size());
        for (ScriptDescriptor descriptor : js) {
            assertTrue(descriptor.getName().endsWith(".js"));
        }
    }

    // ---- B1 批次2b：备份 / 回退 / 清理 ----

    @Test
    public void backupToMovesTargetIntoBackupTreeKeepingStructure() throws Exception {
        File root = newTempDir();
        File dir = new File(root, "dir");
        assertTrue(dir.mkdirs());
        File target = new File(dir, "a.js");
        Files.write(target.toPath(), "payload".getBytes(StandardCharsets.UTF_8));

        File backup = ScriptRepository.backupTo(target, root);
        assertNotNull(backup);
        assertFalse(target.exists());
        assertTrue(backup.isFile());
        // 结构：.aijspro-backups/dir/a.js.<时间戳>.bak
        assertEquals("dir", backup.getParentFile().getName());
        assertEquals(ScriptRepository.BACKUP_DIR_NAME, backup.getParentFile().getParentFile().getName());
        assertTrue(backup.getName().startsWith("a.js."));
        assertTrue(backup.getName().endsWith(".bak"));
        assertEquals("payload", readUtf8(backup));
        assertEquals("dir/a.js", ScriptRepository.backupDisplayName(backup, root));
    }

    @Test
    public void backupOutsideRootIsRefusedAndKeepsFile() throws Exception {
        File root = newTempDir();
        File outside = newTempDir();
        File target = new File(outside, "b.js");
        Files.write(target.toPath(), "x".getBytes(StandardCharsets.UTF_8));

        assertNull(ScriptRepository.backupTo(target, root));
        assertTrue("root 外不备份也不动文件（由调用方保持旧行为）", target.exists());
        assertFalse(ScriptRepository.isInsideRoot(target, root));
        assertTrue(ScriptRepository.isInsideRoot(new File(root, "a.js"), root));
    }

    @Test
    public void restoreBackupReturnsToOriginalLocationAndRefusesConflict() throws Exception {
        File root = newTempDir();
        File dir = new File(root, "dir");
        assertTrue(dir.mkdirs());
        File target = new File(dir, "a.js");
        Files.write(target.toPath(), "v1".getBytes(StandardCharsets.UTF_8));
        File backup = ScriptRepository.backupTo(target, root);
        assertNotNull(backup);

        File restored = ScriptRepository.restoreBackup(backup, root);
        assertNotNull(restored);
        assertEquals(target.getPath(), restored.getPath());
        assertEquals("v1", readUtf8(restored));
        assertFalse(backup.exists());

        // 冲突不覆盖：再次删除产生新备份，然后在原位重建同名文件 → 恢复应失败
        File second = ScriptRepository.backupTo(restored, root);
        assertNotNull(second);
        Files.write(target.toPath(), "v2".getBytes(StandardCharsets.UTF_8));
        assertNull(ScriptRepository.restoreBackup(second, root));
        assertEquals("v2", readUtf8(target));
        assertTrue(second.exists());
    }

    @Test
    public void pruneBackupsKeepsNewestWithinLimitAndDropsExpired() throws Exception {
        File root = newTempDir();
        File backupDir = ScriptRepository.backupRoot(root);
        assertTrue(backupDir.mkdirs());
        long now = System.currentTimeMillis();
        for (int i = 0; i < 55; i++) {
            File fake = new File(backupDir, "f" + i + ".js.20260923-000000-" +
                    String.format("%03d", i) + ".bak");
            Files.write(fake.toPath(), "x".getBytes(StandardCharsets.UTF_8));
            assertTrue(fake.setLastModified(now - i));
        }
        File expired = new File(backupDir, "old.js.20260801-000000-000.bak");
        Files.write(expired.toPath(), "x".getBytes(StandardCharsets.UTF_8));
        assertTrue(expired.setLastModified(now - TimeUnit.DAYS.toMillis(31)));

        int removed = ScriptRepository.pruneBackups(root);
        assertEquals(6, removed);
        List<ScriptDescriptor> left = ScriptRepository.listBackups(root);
        assertEquals(50, left.size());
        assertFalse(expired.exists());
    }

    @Test
    public void pruneBackupsAlsoReclaimsEmptyDirectories() throws Exception {
        File root = newTempDir();
        File nested = new File(ScriptRepository.backupRoot(root), "dir/sub");
        assertTrue(nested.mkdirs());
        File backup = new File(nested, "a.js.20260923-000000-000.bak");
        Files.write(backup.toPath(), "x".getBytes(StandardCharsets.UTF_8));

        assertEquals(0, ScriptRepository.pruneBackups(root)); // 条目本身在保留期内
        assertTrue(backup.exists());

        assertTrue(backup.delete());
        ScriptRepository.pruneBackups(root);
        assertFalse("空目录应被回收", new File(ScriptRepository.backupRoot(root), "dir").exists());
        assertTrue(ScriptRepository.backupRoot(root).exists());
    }
}
