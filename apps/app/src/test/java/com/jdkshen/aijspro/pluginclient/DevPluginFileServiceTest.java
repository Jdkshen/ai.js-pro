package com.jdkshen.aijspro.pluginclient;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 自研 VS Code 扩展的文件命令（list_files / read_file / write_file）单测。
 *
 * <p>重点是路径安全（必须落在脚本目录内）与相对路径格式（正斜杠、相对脚本目录）。
 */
public class DevPluginFileServiceTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private File write(File base, String relativePath, String text) throws IOException {
        File file = new File(base, relativePath);
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            assertTrue(parent.mkdirs());
        }
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    @Test
    public void resolveRejectsEscapesAndAcceptsNestedPaths() throws Exception {
        File base = folder.newFolder("scripts");
        assertEquals(new File(base, "src/app.js").getCanonicalPath(),
                DevPluginFileService.resolve(base, "src/app.js").getCanonicalPath());
        // 前导斜杠按相对路径处理，两种写法都能传。
        assertEquals(new File(base, "src/app.js").getCanonicalPath(),
                DevPluginFileService.resolve(base, "/src/app.js").getCanonicalPath());
        try {
            DevPluginFileService.resolve(base, "../outside.js");
            fail("`..` 逃逸必须被拒绝");
        } catch (IOException expected) {
            // 预期
        }
        try {
            DevPluginFileService.resolve(base, "src/../../outside.js");
            fail("嵌套逃逸必须被拒绝");
        } catch (IOException expected) {
            // 预期
        }
    }

    @Test
    public void listFilesReportsRelativePathsAndSizes() throws Exception {
        File base = folder.newFolder("scripts");
        write(base, "a.js", "console.log(1);");
        write(base, "sub/b.js", "console.log(2);");
        write(base, "sub/deep/c.js", "console.log(3);");

        JsonObject top = DevPluginFileService.listFiles(base, null, false);
        assertEquals(1, top.getAsJsonArray("files").size());
        assertEquals("a.js", top.getAsJsonArray("files").get(0).getAsJsonObject()
                .get("path").getAsString());
        // 非递归列表要带子目录名，树视图才能展开下一层。
        assertEquals(1, top.getAsJsonArray("dirs").size());
        assertEquals("sub", top.getAsJsonArray("dirs").get(0).getAsString());
        assertFalse(top.get("truncated").getAsBoolean());

        JsonObject all = DevPluginFileService.listFiles(base, "", true);
        Set<String> paths = new HashSet<>();
        JsonArray files = all.getAsJsonArray("files");
        for (int i = 0; i < files.size(); i++) {
            JsonObject entry = files.get(i).getAsJsonObject();
            paths.add(entry.get("path").getAsString());
            assertTrue("条目必须有大小", entry.has("size"));
            assertTrue("条目必须有 mtime", entry.has("mtime"));
        }
        assertEquals(new HashSet<>(java.util.Arrays.asList(
                "a.js", "sub/b.js", "sub/deep/c.js")), paths);
    }

    @Test
    public void writeThenReadRoundTripsUtf8() throws Exception {
        File base = folder.newFolder("scripts");
        String text = "// 中文注释\nconsole.log('ok');\n";
        JsonObject result = DevPluginFileService.writeFile(base, "src/new/中文.js", text);
        assertEquals("src/new/中文.js", result.get("path").getAsString());
        assertEquals(text.getBytes(StandardCharsets.UTF_8).length,
                result.get("size").getAsInt());
        assertTrue(new File(base, "src/new/中文.js").isFile());

        byte[] bytes = DevPluginFileService.readFile(base, "src/new/中文.js");
        assertEquals(text, new String(bytes, StandardCharsets.UTF_8));
    }

    @Test
    public void readFileRejectsMissingAndOutsidePaths() throws Exception {
        File base = folder.newFolder("scripts");
        write(base, "a.js", "1");
        try {
            DevPluginFileService.readFile(base, "missing.js");
            fail("不存在的文件必须报错");
        } catch (IOException expected) {
            // 预期
        }
        try {
            DevPluginFileService.readFile(base, "../outside.js");
            fail("目录外读取必须被拒绝");
        } catch (IOException expected) {
            // 预期
        }
    }

    @Test
    public void writeFileRejectsEmptyPathAndOutsidePaths() throws Exception {
        File base = folder.newFolder("scripts");
        try {
            DevPluginFileService.writeFile(base, "  ", "x");
            fail("空路径必须被拒绝");
        } catch (IOException expected) {
            // 预期
        }
        try {
            DevPluginFileService.writeFile(base, "../evil.js", "x");
            fail("目录外写入必须被拒绝");
        } catch (IOException expected) {
            // 预期
        }
    }

    @Test
    public void isFileCommandRecognisesOnlyOurCommands() {
        assertTrue(DevPluginFileService.isFileCommand("list_files"));
        assertTrue(DevPluginFileService.isFileCommand("read_file"));
        assertTrue(DevPluginFileService.isFileCommand("write_file"));
        // 官方扩展的既有命令不能被文件分支截胡。
        assertFalse(DevPluginFileService.isFileCommand("run"));
        assertFalse(DevPluginFileService.isFileCommand("save_project"));
        assertFalse(DevPluginFileService.isFileCommand(null));
    }
}
