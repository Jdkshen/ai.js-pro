package com.jdkshen.aijspro.flutterpoc.nativeproc;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 暂存「清单化合并」（dev-1410 起的 {@link NativeProjectStaging#mergeIntoStaging}）的纯 JVM 回归。
 *
 * <p>{@link NativeProjectStagingTest} 只覆盖复制规则（{@code copyProjectContents}）；本类覆盖
 * 2026-11-08 在 K40 上做过端到端的那四类合并行为（task-1 的验证步骤 / task-7 的记录）：
 * <ol>
 *   <li>脚本自己写进运行目录的文件（不在清单里）跨 RUN 保留，且不进清单；</li>
 *   <li>「清单里有、源里已没有」的陈旧文件被删；</li>
 *   <li>源里仍在（内容已改）的文件被覆盖式更新，清单按字典序重写；</li>
 *   <li>清单缺失时按空清单处理：本轮不删任何东西（现状行为，见第三个用例上的缺口注释）。</li>
 * </ol>
 *
 * <p>测试走的是不接触 {@code Context}/{@code Log} 的包内入口 {@code mergeIntoStaging} /
 * {@code pruneStaleCopied} / {@code parseCopiedManifest} / {@code formatCopiedManifest}，
 * 因此能在不启 Robolectric 的纯 JVM 单测里跑（约定同 {@link NativeProjectStagingTest}：
 * 测试放 {@code src/flutterpocTest}，运行需带 {@code -PflutterPoc=true}）。
 */
public class NativeProjectStagingManifestTest {

    private static final String MANIFEST = ".aijs-staging-manifest.txt";

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    /** 源项目目录（脚本工程）。 */
    private File project;
    /** 暂存目录（对应 {@code <filesDir>/script-staging/<hash>}）。 */
    private File staged;

    private void openProject() throws IOException {
        project = temporary.newFolder("project");
        staged = temporary.newFolder("staged");
    }

    // ------------------------------------------------------------------
    // ① 脚本自写文件保留
    // ------------------------------------------------------------------

    @Test
    public void scriptWrittenFileSurvivesNextRunAndStaysOutOfManifest() throws Exception {
        openProject();
        source("main.js", "console.log('v1');");
        source("dep.js", "console.log('dep');");

        assertEquals(setOf("dep.js", "main.js"), merge().copied);
        assertEquals(setOf("dep.js", "main.js"), manifest());

        // 第 1 次运行期间：自更新脚本把下载的依赖写进运行目录（源工程里并没有这个文件）。
        write(new File(staged, "脚本.js"), "exports.hi=1");

        // 第 2 次运行：重新合并暂存 —— 自写文件必须原样保留，且永远不进清单。
        assertEquals(setOf("dep.js", "main.js"), merge().copied);
        assertTrue(new File(staged, "脚本.js").isFile());
        assertEquals("exports.hi=1", read(new File(staged, "脚本.js")));
        assertFalse("自写文件不能进清单（否则下一轮会被当成陈旧源文件删掉）",
                manifest().contains("脚本.js"));
    }

    // ------------------------------------------------------------------
    // ② 源里已删 -> 暂存清掉
    // ------------------------------------------------------------------

    @Test
    public void deletesCopiedFileThatDisappearedFromSource() throws Exception {
        openProject();
        source("main.js", "console.log('v1');");
        source("dep.js", "console.log('dep');");
        merge();
        write(new File(staged, "脚本.js"), "exports.hi=1");

        assertTrue(new File(project, "dep.js").delete());

        assertEquals(setOf("main.js"), merge().copied);
        assertFalse("源里已删的文件必须从暂存清掉", new File(staged, "dep.js").exists());
        assertTrue("脚本自写文件不受影响", new File(staged, "脚本.js").isFile());
        assertEquals(setOf("main.js"), manifest());
    }

    // ------------------------------------------------------------------
    // ③ 覆盖式复制
    // ------------------------------------------------------------------

    @Test
    public void overwritesSourceFileThatChanged() throws Exception {
        openProject();
        source("main.js", "v1");
        merge();
        assertEquals("v1", read(new File(staged, "main.js")));

        source("main.js", "v2");
        merge();

        assertEquals("v2", read(new File(staged, "main.js")));
        assertEquals(setOf("main.js"), manifest());
    }

    @Test
    public void manifestFileIsSortedOneRelativePathPerLine() throws Exception {
        openProject();
        source("b.js", "b");
        source("a.js", "a");
        source("dir/c.js", "c");

        merge();

        assertEquals("a.js\nb.js\ndir/c.js\n", read(new File(staged, MANIFEST)));
    }

    // ------------------------------------------------------------------
    // ④ 清单缺失 = 不删任何东西（现状，已知缺口）
    // ------------------------------------------------------------------

    /**
     * 现状行为，也是已知缺口 R1：清单缺失/不可读 -> 空清单 -> 本轮不做任何删除。
     *
     * <p>这么钉住的原因：这条「保守」行为保护了脚本自写的依赖，但也意味着上一轮漏记的陈旧
     * 文件从此永远不会被清（它不在新清单里）。以后若要改成「无清单就整目录清理」，必须先把
     * 这里改成失败——否则会悄悄删掉脚本自己下载的依赖。
     */
    @Test
    public void missingManifestDeletesNothing() throws Exception {
        openProject();
        // 上次运行遗留、且源工程里已经没有了：没有清单，只能保守保留。
        write(new File(staged, "遗留-old.js"), "legacy");
        source("main.js", "v1");

        assertEquals(setOf("main.js"), merge().copied);
        assertTrue("没有清单时不得删除暂存里的既有文件",
                new File(staged, "遗留-old.js").isFile());
        assertEquals("清单只记本次真正从源复制的文件", setOf("main.js"), manifest());
    }

    // ------------------------------------------------------------------
    // 纯函数（清单解析 / 生成 / 差异清理）
    // ------------------------------------------------------------------

    @Test
    public void parseIgnoresBlankLinesAndPaddedLines() {
        assertEquals(setOf("a.js", "b/c.js"),
                NativeProjectStaging.parseCopiedManifest("a.js\n\n b/c.js \r\n"));
        assertTrue(NativeProjectStaging.parseCopiedManifest("").isEmpty());
        assertTrue(NativeProjectStaging.parseCopiedManifest(null).isEmpty());
    }

    @Test
    public void formatSortsAndRoundTrips() {
        assertEquals("a.js\nb/c.js\n",
                NativeProjectStaging.formatCopiedManifest(Arrays.asList("b/c.js", "a.js")));
        assertEquals("", NativeProjectStaging.formatCopiedManifest(Collections.<String>emptyList()));
        assertEquals(setOf("a.js", "b.js"), NativeProjectStaging.parseCopiedManifest(
                NativeProjectStaging.formatCopiedManifest(setOf("b.js", "a.js"))));
    }

    @Test
    public void pruneKeepsSelfWrittenFilesDirectoriesAndMissingEntries() throws Exception {
        openProject();
        File stagedSub = new File(staged, "sub");
        assertTrue(stagedSub.mkdirs());
        write(new File(staged, "stale.js"), "x");
        write(new File(staged, "自写.js"), "self");

        List<String> failed = NativeProjectStaging.pruneStaleCopied(project, staged,
                setOf("sub", "stale.js", "gone.js"));

        assertTrue("目录项与不存在的条目都不算删除失败：" + failed, failed.isEmpty());
        assertTrue("清单里的目录项不动", stagedSub.isDirectory());
        assertTrue("不在清单里的自写文件不动", new File(staged, "自写.js").isFile());
        assertFalse("源里已没有的清单项删掉", new File(staged, "stale.js").exists());
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private File source(String relative, String content) throws IOException {
        File file = new File(project, relative);
        write(file, content);
        return file;
    }

    /** 驱动一次生产同款合并（删陈旧 + 覆盖复制 + 写清单），并断言没有删除失败项。 */
    private NativeProjectStaging.CopyStats merge() throws IOException {
        NativeProjectStaging.CopyStats stats = new NativeProjectStaging.CopyStats();
        List<String> failed = NativeProjectStaging.mergeIntoStaging(project, staged, stats);
        assertTrue("不应有删除失败项：" + failed, failed.isEmpty());
        return stats;
    }

    /** 暂存目录里的清单内容（缺文件时按空集合）；清单文件本身始终用生产写法读取。 */
    private Set<String> manifest() throws IOException {
        File manifest = new File(staged, MANIFEST);
        if (!manifest.isFile()) {
            return new LinkedHashSet<>();
        }
        return NativeProjectStaging.parseCopiedManifest(read(manifest));
    }

    private static void write(File file, String content) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("无法创建测试目录：" + parent);
        }
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /** 可变集合比较用：测试目标是 Java 8，{@code Set.of} 不可用。 */
    private static Set<String> setOf(String... values) {
        return new LinkedHashSet<>(Arrays.asList(values));
    }
}
