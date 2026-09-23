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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
}
