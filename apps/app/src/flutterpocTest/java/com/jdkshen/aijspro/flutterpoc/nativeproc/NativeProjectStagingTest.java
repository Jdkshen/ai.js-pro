package com.jdkshen.aijspro.flutterpoc.nativeproc;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link NativeProjectStaging} 的纯 JVM 复制规则回归。
 *
 * <p>重点：{@code build} 目录通常是构建中间产物，但 Node/UI 工程的编译资源包
 * {@code build/project-res.apk}（可选 sidecar {@code build/project-res.json}）是运行期
 * {@code ProjectResourceBundle} 的唯一来源，不能再被当成普通中间产物丢掉。
 *
 * <p>{@code NativeProjectStaging} 在 {@code src/flutterpoc} 源集；按本仓库既有约定，
 * 测试放在 {@code src/flutterpocTest}，运行需带 {@code -PflutterPoc=true}
 * （例如 {@code :app:testMiuixCompatDebugUnitTest --tests ...}），普通构建不依赖这些类。
 * 测试只走不经 Context/Log 的包内复制入口，避免 android.jar 桩方法。
 */
public class NativeProjectStagingTest {

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void preservesRootCompiledResourcePairUnderBuild() throws Exception {
        File project = temporary.newFolder("project");
        byte[] apk = binary(4099, 17);
        byte[] sidecar = "{\"resourcePackageName\":\"org.local.chess\"}"
                .getBytes(StandardCharsets.UTF_8);
        write(project, "main.js", "console.log('chess');".getBytes(StandardCharsets.UTF_8));
        write(project, "build/project-res.apk", apk);
        write(project, "build/project-res.json", sidecar);

        File staged = stage(project);

        assertArrayEquals(apk, read(new File(staged, "build/project-res.apk")));
        assertArrayEquals(sidecar, read(new File(staged, "build/project-res.json")));
        assertEquals("console.log('chess');",
                new String(read(new File(staged, "main.js")), StandardCharsets.UTF_8));
    }

    @Test
    public void preservesNestedProjectCompiledResourcePair() throws Exception {
        File project = temporary.newFolder("project");
        byte[] apk = binary(8193, 31);
        byte[] sidecar = "{\"appResourcesSha256\":\"abc\"}".getBytes(StandardCharsets.UTF_8);
        write(project, "chess-auto/node-port/main.node.js",
                "require('./build/project-res.apk');".getBytes(StandardCharsets.UTF_8));
        write(project, "chess-auto/node-port/build/project-res.apk", apk);
        write(project, "chess-auto/node-port/build/project-res.json", sidecar);

        File staged = stage(project);

        File stagedBuild = new File(staged, "chess-auto/node-port/build");
        assertArrayEquals(apk, read(new File(stagedBuild, "project-res.apk")));
        assertArrayEquals(sidecar, read(new File(stagedBuild, "project-res.json")));
        assertTrue(new File(staged, "chess-auto/node-port/main.node.js").isFile());
    }

    @Test
    public void buildDirectoryKeepsOnlyCompiledResourcePair() throws Exception {
        File project = temporary.newFolder("project");
        byte[] apk = binary(257, 7);
        write(project, "build/project-res.apk", apk);
        write(project, "build/project-res.json", "{}".getBytes(StandardCharsets.UTF_8));
        write(project, "build/intermediates/merged/classes.dex", new byte[]{1, 2, 3});
        write(project, "build/intermediates/res/compiled.zip", new byte[]{4, 5});
        write(project, "build/outputs/apk/app-debug.apk", new byte[]{6});
        write(project, "build/helper.apk", new byte[]{7});
        write(project, "build/notes.txt", "intermediate".getBytes(StandardCharsets.UTF_8));
        write(project, "project-res.apk", new byte[]{8});
        write(project, ".git/config", "[core]".getBytes(StandardCharsets.UTF_8));
        write(project, "lib/runtime.dex", new byte[]{9, 10});

        File staged = stage(project);

        File stagedBuild = new File(staged, "build");
        assertTrue(stagedBuild.isDirectory());
        String[] names = stagedBuild.list();
        assertNotNull(names);
        Arrays.sort(names);
        assertArrayEquals(new String[]{"project-res.apk", "project-res.json"}, names);
        assertArrayEquals(apk, read(new File(stagedBuild, "project-res.apk")));
        assertFalse(new File(stagedBuild, "intermediates").exists());
        assertFalse(new File(stagedBuild, "helper.apk").exists());
        assertFalse(new File(staged, "project-res.apk").exists());
        assertFalse(new File(staged, ".git").exists());
        assertArrayEquals(new byte[]{9, 10}, read(new File(staged, "lib/runtime.dex")));
    }

    @Test
    public void compiledApkWithoutOptionalSidecarStillCopies() throws Exception {
        File project = temporary.newFolder("project");
        byte[] apk = binary(1023, 3);
        write(project, "build/project-res.apk", apk);

        File staged = stage(project);

        File stagedBuild = new File(staged, "build");
        assertArrayEquals(apk, read(new File(stagedBuild, "project-res.apk")));
        assertFalse(new File(stagedBuild, "project-res.json").exists());
        String[] names = stagedBuild.list();
        assertNotNull(names);
        assertEquals(1, names.length);
    }

    @Test
    public void sidecarWithoutCompiledApkDoesNotCreateBuildDirectory() throws Exception {
        File project = temporary.newFolder("project");
        write(project, "build/project-res.json", "{}".getBytes(StandardCharsets.UTF_8));
        write(project, "build/outputs/apk/app-debug.apk", new byte[]{1});

        File staged = stage(project);

        assertFalse(new File(staged, "build").exists());
    }

    private File stage(File project) throws IOException {
        File staged = temporary.newFolder("staged-" + System.nanoTime());
        NativeProjectStaging.copyProjectContents(project, staged);
        return staged;
    }

    private static void write(File project, String path, byte[] content) throws IOException {
        File file = new File(project, path);
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("无法创建测试目录：" + parent);
        }
        Files.write(file.toPath(), content);
    }

    private static byte[] read(File file) throws IOException {
        return Files.readAllBytes(file.toPath());
    }

    private static byte[] binary(int size, int seed) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) {
            bytes[i] = (byte) (i * seed + 11);
        }
        return bytes;
    }
}
