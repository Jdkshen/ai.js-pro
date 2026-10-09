package com.jdkshen.aijspro.model.script;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ProjectArchiveTest {

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void preservesProjectRootHiddenFilesEmptyDirectoriesAndBinaryResources() throws Exception {
        File project = temporary.newFolder("测试 项目");
        File cache = temporary.newFolder("cache");
        byte[] manifest = "{\"name\":\"测试 项目\",\"main\":\"src/入口 文件.js\"}"
                .getBytes(StandardCharsets.UTF_8);
        byte[] script = "console.log('共享工程');\n".getBytes(StandardCharsets.UTF_8);
        byte[] binary = new byte[32799];
        for (int i = 0; i < binary.length; i++) binary[i] = (byte) (i * 31);
        write(project, "project.json", manifest);
        write(project, "src/入口 文件.js", script);
        write(project, ".gitignore", "build/\n".getBytes(StandardCharsets.UTF_8));
        write(project, ".settings/隐藏 配置.json", "{}".getBytes(StandardCharsets.UTF_8));
        write(project, "assets/图片 资源.bin", binary);
        assertTrue(new File(project, "assets/空 目录").mkdirs());

        File archive = ProjectArchive.create(project, cache);

        try (ZipFile zip = new ZipFile(archive)) {
            String prefix = project.getName() + "/";
            assertArrayEquals(manifest, read(zip, prefix + "project.json"));
            assertArrayEquals(script, read(zip, prefix + "src/入口 文件.js"));
            assertEquals("build/\n", new String(read(zip, prefix + ".gitignore"), StandardCharsets.UTF_8));
            assertNotNull(zip.getEntry(prefix + ".settings/隐藏 配置.json"));
            assertArrayEquals(binary, read(zip, prefix + "assets/图片 资源.bin"));
            ZipEntry empty = zip.getEntry(prefix + "assets/空 目录/");
            assertNotNull(empty);
            assertTrue(empty.isDirectory());
            assertTrue(zip.stream().allMatch(entry -> entry.getName().startsWith(prefix)));
        }
        assertArrayEquals(manifest, Files.readAllBytes(new File(project, "project.json").toPath()));
        assertArrayEquals(binary, Files.readAllBytes(new File(project, "assets/图片 资源.bin").toPath()));
    }

    @Test
    public void repeatedExportsAreClosedIndependentArchivesWithTheProjectFileName() throws Exception {
        File project = temporary.newFolder("共享 项目");
        File cache = temporary.newFolder("cache");
        write(project, "project.json", "{}".getBytes(StandardCharsets.UTF_8));
        write(project, "main.js", "first".getBytes(StandardCharsets.UTF_8));

        File first = ProjectArchive.create(project, cache);
        // Opening the archive immediately also requires the central directory to be finished.
        try (ZipFile zip = new ZipFile(first)) {
            assertEquals("first", new String(read(zip, "共享 项目/main.js"), StandardCharsets.UTF_8));
        }
        write(project, "main.js", "second".getBytes(StandardCharsets.UTF_8));
        File second = ProjectArchive.create(project, cache);

        assertNotEquals(first.getCanonicalPath(), second.getCanonicalPath());
        assertEquals("共享 项目.zip", first.getName());
        assertEquals("共享 项目.zip", second.getName());
        try (ZipFile firstZip = new ZipFile(first); ZipFile secondZip = new ZipFile(second)) {
            assertEquals("first", new String(read(firstZip, "共享 项目/main.js"), StandardCharsets.UTF_8));
            assertEquals("second", new String(read(secondZip, "共享 项目/main.js"), StandardCharsets.UTF_8));
        }
    }

    @Test
    public void rejectsOutputInsideSourceBeforeWritingAnArchive() throws Exception {
        File project = temporary.newFolder("project");
        write(project, "project.json", "{}".getBytes(StandardCharsets.UTF_8));
        File nestedCache = new File(project, "export-cache");

        expectIOException(() -> ProjectArchive.create(project, nestedCache));
        assertFalse(nestedCache.exists());
        assertArrayEquals(new String[]{"project.json"}, project.list());
    }

    @Test
    public void rejectsAFileOrMissingDirectoryAsTheProjectSource() throws Exception {
        File file = temporary.newFile("main.js");
        File cache = temporary.newFolder("cache");

        expectIOException(() -> ProjectArchive.create(file, cache));
        expectIOException(() -> ProjectArchive.create(new File(temporary.getRoot(), "missing"), cache));
        assertEquals(0, cache.list().length);
    }

    private static void write(File root, String relativePath, byte[] data) throws IOException {
        Path target = new File(root, relativePath).toPath();
        Files.createDirectories(target.getParent());
        Files.write(target, data);
    }

    private static byte[] read(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        assertNotNull("Missing ZIP entry: " + name, entry);
        try (InputStream input = zip.getInputStream(entry);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private static void expectIOException(ArchiveCall operation) throws Exception {
        try {
            operation.run();
            fail("Expected IOException");
        } catch (IOException expected) {
            // A failed export must be observable by the sharing UI.
        }
    }

    private interface ArchiveCall {
        void run() throws Exception;
    }
}
