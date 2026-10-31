package com.jdkshen.aijspro.autojs.build;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link Node24TemplateManifest}：构建脚本侧（Groovy）用的模板清单生成与漂移检测。
 *
 * <p>它刻意不依赖 {@code org.json}/{@code android.util.Log}，只用 JDK；App 运行时侧的
 * 同类逻辑在 {@link Node24TemplateConsistency}。两者的 JSON 约定必须一致，所以这里也用
 * 真实仓库模板做一次往返（生成 → 校验 → 篡改后必须报过期）。
 */
public class Node24TemplateManifestTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final Charset UTF8 = Charset.forName("UTF-8");

    @Test public void writeManifestThenVerifyManifestRoundTrips() throws Exception {
        File apk = syntheticTemplate(tmp.newFolder("tpl"));
        File manifest = new File(tmp.newFolder("out"), "template-manifest.json");
        Node24TemplateManifest.verifyTemplate(apk);
        Node24TemplateManifest.writeManifest(apk, "1.0.2-265", manifest);
        Node24TemplateManifest.verifyManifest(apk, manifest);
        String text = read(manifest);
        assertTrue(text.contains("\"manifestVersion\": 1"));
        assertTrue(text.contains("\"templateVersion\": \"1.0.2-265\""));
        assertTrue(text.contains("\"nodeRequiredFilesMissing\": []"));
    }

    @Test public void verifyManifestRejectsAStaleManifest() throws Exception {
        File apk = syntheticTemplate(tmp.newFolder("tpl"));
        File manifest = new File(tmp.newFolder("out"), "template-manifest.json");
        Node24TemplateManifest.writeManifest(apk, "1.0.2-265", manifest);
        String tampered = read(manifest).replace("aaaaaaaa", "ffffffff");
        File stale = new File(tmp.newFolder("stale"), "template-manifest.json");
        write(stale, tampered);
        try {
            Node24TemplateManifest.verifyManifest(apk, stale);
            fail("清单里的哈希被改过必须报过期");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("过期"));
        }
    }

    @Test public void verifyTemplateRejectsTemplateWithoutRuntimeManifest() throws Exception {
        File apk = syntheticTemplate(tmp.newFolder("noruntime"), true);
        try {
            Node24TemplateManifest.verifyTemplate(apk);
            fail("没有 runtime.json 的模板必须被拒绝");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("缺少"));
        }
    }

    @Test public void verifyManifestRejectsMissingManifestFile() throws Exception {
        File apk = syntheticTemplate(tmp.newFolder("tpl2"));
        try {
            Node24TemplateManifest.verifyManifest(apk, new File(tmp.getRoot(), "nope.json"));
            fail("清单缺失必须报错");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("不存在"));
        }
    }

    @Test public void readRuntimeHashesIgnoresNonHashFields() throws Exception {
        File apk = syntheticTemplate(tmp.newFolder("tpl3"), false, true);
        Map<String, String> hashes = Node24TemplateManifest.readRuntimeHashes(apk);
        assertEquals(3, hashes.size());
        assertFalse("注释字段不是 64 位十六进制，不能被当哈希", hashes.containsKey("note"));
    }

    /**
     * 仓库里真实的打包模板 + 已提交清单：生成 → 校验必须自洽，且与 App 侧的
     * {@link Node24TemplateConsistency} 解析出同一组哈希（两份实现不能各写一套约定）。
     */
    @Test public void repoPluginTemplateIsSelfConsistentAcrossBothImplementations() throws Exception {
        File root = repoRoot();
        File template = new File(root, "plugins/apkbuilder-plugin/src/main/assets/template.apk");
        File manifest = new File(root, "plugins/apkbuilder-plugin/src/main/assets/template-manifest.json");
        if (!template.isFile() || !manifest.isFile()) {
            return;
        }
        Node24TemplateManifest.verifyTemplate(template);
        Node24TemplateManifest.verifyManifest(template, manifest);
        assertEquals(Node24TemplateConsistency.readRuntimeHashes(template),
                Node24TemplateManifest.readRuntimeHashes(template));
    }

    // ------------------------------------------------------------------ 夹具

    private File syntheticTemplate(File dir) throws IOException {
        return syntheticTemplate(dir, false, false);
    }

    private File syntheticTemplate(File dir, boolean withoutRuntimeManifest) throws IOException {
        return syntheticTemplate(dir, withoutRuntimeManifest, false);
    }

    private File syntheticTemplate(File dir, boolean withoutRuntimeManifest, boolean withNoise)
            throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("无法创建 " + dir);
        }
        String prefix = "assets/source-engine-upgrade/node24/arm64-v8a/";
        File apk = new File(dir, "template.apk");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(apk))) {
            put(zip, "assets/node24/prelude.js", "// prelude");
            put(zip, "assets/node24/ui.js", "// ui");
            put(zip, prefix + "libnode.so", "node");
            put(zip, prefix + "libc++_shared.so", "cxx");
            put(zip, prefix + "libajsnode24bridge.so", "bridge");
            if (!withoutRuntimeManifest) {
                StringBuilder json = new StringBuilder();
                json.append("{\n");
                json.append("  \"libnode.so\": \"").append(repeat('a')).append("\",\n");
                json.append("  \"libc++_shared.so\": \"").append(repeat('b')).append("\",\n");
                json.append("  \"libajsnode24bridge.so\": \"").append(repeat('c')).append("\"");
                if (withNoise) {
                    json.append(",\n  \"note\": \"not-a-hash\"");
                }
                json.append("\n}\n");
                put(zip, prefix + "runtime.json", json.toString());
            }
        }
        return apk;
    }

    private static String repeat(char c) {
        StringBuilder out = new StringBuilder(64);
        for (int i = 0; i < 64; i++) {
            out.append(c);
        }
        return out.toString();
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(UTF8));
        zip.closeEntry();
    }

    private static String read(File file) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        InputStream input = new FileInputStream(file);
        try {
            byte[] chunk = new byte[8192];
            int count;
            while ((count = input.read(chunk)) > 0) {
                buffer.write(chunk, 0, count);
            }
        } finally {
            input.close();
        }
        return new String(buffer.toByteArray(), UTF8);
    }

    private static void write(File file, String text) throws IOException {
        OutputStream output = new FileOutputStream(file);
        try {
            output.write(text.getBytes(UTF8));
        } finally {
            output.close();
        }
    }

    private static File repoRoot() {
        File current = new File(System.getProperty("user.dir", ".")).getAbsoluteFile();
        for (int i = 0; i < 8 && current != null; i++) {
            if (new File(current, "settings.gradle").isFile()
                    && new File(current, "apps/app").isDirectory()) {
                return current;
            }
            current = current.getParentFile();
        }
        return new File(System.getProperty("user.dir", "."));
    }
}
