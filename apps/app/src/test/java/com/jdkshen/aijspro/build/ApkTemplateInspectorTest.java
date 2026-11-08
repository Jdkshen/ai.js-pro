package com.jdkshen.aijspro.build;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link ApkTemplateInspector}：判定规则与 dev-1413 的「大模板不再被 64MiB 上限误杀」回归。
 *
 * <p><b>全部用合成 zip 夹具</b>，不读仓库里的真 {@code template.apk}，也不依赖设备。
 *
 * <p>要钉住的历史缺陷：旧实现 {@code inspect(File)} 先 {@code readAll()} 把整包读进内存、并设了
 * 64MiB 上限，于是**插件兜底通道**（落地的是 88,448,312 B 全量模板）与
 * <b>{@code /sdcard/template.apk} 手动覆盖</b>（历史就是 88MiB 级）上，再新的模板也会被判成
 * {@code available=false / engineRequireRootFix=false / templateStale=true /
 * inspectError="IOException: 模板超过 64MiB"}。{@link #templateLargerThan64MiBIsStillInspected()}
 * 用一个**压缩后仍 >64MiB** 的夹具钉住它（STORED padding，避免压缩 CPU）。
 *
 * <p>夹具刻意"条目极少"（{@code assets/init.js} + 一个 padding），单个用例的成本只有一次 65MiB
 * 文件写入；判定逻辑与包大小无关，其余用例用 KB 级夹具。<b>覆盖边界</b>：没有断言内存占用
 * （堆增量在 dev-1413 的 A/B 里量过：98.21MiB → 1.0MiB），因为 JVM 单测里的 GC 时机不稳定，
 * 用它当断言会 flaky。
 *
 * <p>单测跑在 JVM 上，{@code org.json} 由 {@code testImplementation 'org.json:json:20240303'} 提供
 * 真实实现，所以 {@code Info} 的 warnings/nextActions/toJson 也顺便被覆盖（生产端
 * {@code put/append} 助手会吞异常，若哪天 org.json 变成 mock 桩，这些断言会失败）。
 */
public class ApkTemplateInspectorTest {

    private static final String MARKER = "__ajsSetRequireRoot";
    /** 大包夹具的 padding 字节数：必须**压缩后**仍 >64MiB，所以用 STORED（不压缩）。 */
    private static final int BIG_PADDING_BYTES = 65 * 1024 * 1024;
    private static final long SIXTY_FOUR_MIB = 64L * 1024 * 1024;
    /**
     * 单条 init.js 的上限，刻意**写字面量**而不是引用 ApkTemplateInspector 的私有常量：
     * 生产端改上限时，{@link #oversizedInitJsIsReportedAsInspectError()} 必须跟着显式失败/复核。
     */
    private static final int INIT_LIMIT_BYTES = 4 * 1024 * 1024;

    @Rule public final TemporaryFolder tmp = new TemporaryFolder();

    // ------------------------------------------------------------ ① 正常标记 / ② 缺标记

    @Test public void markerPresentIsReportedAsFixed() throws Exception {
        byte[] initJs = ("global." + MARKER + " = function (dir) { Require.root = dir; };\n")
                .getBytes(StandardCharsets.UTF_8);
        File template = zip("template-ok.apk", initJs, 0);

        ApkTemplateInspector.Info info = ApkTemplateInspector.inspect(template, ApkTemplateInspector.SOURCE_BUNDLED);

        assertTrue(info.available);
        assertTrue(info.engineRequireRootFix);
        assertFalse(info.templateStale);
        assertEquals("", info.inspectError);
        assertTrue(info.detail, info.detail.contains(MARKER));
        assertEquals(template.length(), info.sizeBytes);
        assertEquals(sha256Of(template), info.sha256);
        assertEquals(sha256(initJs).substring(0, 16), info.engineInitSha256);
        assertEquals(ApkTemplateInspector.SOURCE_BUNDLED, info.source);
        assertEquals(0, info.warnings.length());
        assertEquals(0, info.nextActions.length());

        JSONObject json = info.toJson();
        assertTrue(json.getBoolean("engineRequireRootFix"));
        assertFalse(json.getBoolean("templateStale"));
        assertEquals(info.sha256, json.getString("sha256"));
        assertEquals(info.engineInitSha256, json.getString("engineInitSha256"));
    }

    @Test public void missingMarkerIsReportedAsStale() throws Exception {
        byte[] initJs = "global.NOTHING_TO_SEE = 1;\n".getBytes(StandardCharsets.UTF_8);
        File template = zip("template-stale.apk", initJs, 0);

        ApkTemplateInspector.Info info = ApkTemplateInspector.inspect(template, ApkTemplateInspector.SOURCE_SDCARD);

        assertTrue(info.available);
        assertFalse(info.engineRequireRootFix);
        assertTrue(info.templateStale);
        assertEquals("", info.inspectError);
        assertEquals("未找到 assets/init.js 或缺少修复标记", info.detail);
        assertEquals(sha256(initJs).substring(0, 16), info.engineInitSha256);
        // 给调用方（打包日志 / get_status）的可执行提示不能丢。
        assertEquals(1, info.warnings.length());
        assertTrue(info.warnings.getString(0), info.warnings.getString(0).contains(MARKER));
        assertEquals(4, info.nextActions.length());
    }

    @Test public void zipWithoutInitJsIsStaleWithSameDetail() throws Exception {
        File template = zip("template-no-init.apk", null, 0);

        ApkTemplateInspector.Info info = ApkTemplateInspector.inspect(template, ApkTemplateInspector.SOURCE_HOST_CACHE);

        assertTrue(info.available);
        assertFalse(info.engineRequireRootFix);
        assertTrue(info.templateStale);
        assertEquals("", info.engineInitSha256);
        assertEquals("未找到 assets/init.js 或缺少修复标记", info.detail);
    }

    // ------------------------------------------------------------ ③ dev-1413 大包回归（核心）

    @Test public void templateLargerThan64MiBIsStillInspected() throws Exception {
        byte[] initJs = ("global." + MARKER + " = function (dir) {};\n").getBytes(StandardCharsets.UTF_8);
        File template = zip("template-big.apk", initJs, BIG_PADDING_BYTES);

        assertTrue("夹具必须真的超过 64MiB，否则这条回归是空的：" + template.length(),
                template.length() > SIXTY_FOUR_MIB);

        ApkTemplateInspector.Info info = ApkTemplateInspector.inspect(template, ApkTemplateInspector.SOURCE_HOST_CACHE);

        // 旧实现（readAll + 64MiB 上限）在这三行给的是：available=false / stale=true /
        // inspectError="IOException: 模板超过 64MiB"。
        assertTrue("大模板必须是可用的（插件兜底 / /sdcard 全量模板走这条）", info.available);
        assertEquals("不该再出现 64MiB 上限文案", "", info.inspectError);
        assertFalse(info.inspectError.contains("64MiB"));
        assertTrue(info.engineRequireRootFix);
        assertFalse(info.templateStale);
        assertEquals(template.length(), info.sizeBytes);
        assertEquals(sha256Of(template), info.sha256);
        assertEquals(sha256(initJs).substring(0, 16), info.engineInitSha256);
        assertEquals(0, info.warnings.length());
    }

    // ------------------------------------------------------------ ④ 单条 init.js 上限

    @Test public void oversizedInitJsIsReportedAsInspectError() throws Exception {
        byte[] initJs = new byte[INIT_LIMIT_BYTES + 16];
        Arrays.fill(initJs, (byte) 'a');
        File template = zip("template-fat-init.apk", initJs, 0);
        assertTrue("单条超限时整包应该很小：" + template.length(), template.length() < SIXTY_FOUR_MIB);

        ApkTemplateInspector.Info info = ApkTemplateInspector.inspect(template, ApkTemplateInspector.SOURCE_BUNDLED);

        assertTrue(info.available);
        assertFalse(info.engineRequireRootFix);
        assertTrue(info.templateStale);
        assertTrue(info.inspectError, info.inspectError.contains("超过"));
        assertTrue(info.inspectError, info.inspectError.contains(String.valueOf(INIT_LIMIT_BYTES)));
        assertEquals("读取 assets/init.js 失败", info.detail);
    }

    @Test public void byteArrayPathHonoursTheSingleEntryLimit() throws Exception {
        byte[] initJs = new byte[INIT_LIMIT_BYTES + 16];
        Arrays.fill(initJs, (byte) 'a');
        File template = zip("template-fat-init-bytes.apk", initJs, 0);

        ApkTemplateInspector.Info info = ApkTemplateInspector.inspect(
                Files.readAllBytes(template.toPath()), ApkTemplateInspector.SOURCE_BUNDLED, "assets/template.apk");

        assertTrue(info.available);
        assertTrue(info.inspectError, info.inspectError.contains("超过"));
        assertEquals("读取 assets/init.js 失败", info.detail);
    }

    // ------------------------------------------------------------ ⑤ 非 zip / 不存在 / 不可用

    @Test public void nonZipFileIsStaleButStillAvailable() throws Exception {
        File notZip = new File(tmp.getRoot(), "not-a-zip.apk");
        write(notZip, "{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8));

        ApkTemplateInspector.Info info = ApkTemplateInspector.inspect(notZip, ApkTemplateInspector.SOURCE_HOST_CACHE);

        // dev-1413 记录在案的差异：非 zip 时旧实现 inspectError 是空串，新实现给出 ZipException 文案
        // （ZipFile 打开失败）；判定字段 available/fix/stale/detail 两版一致。
        assertTrue(info.available);
        assertFalse(info.engineRequireRootFix);
        assertTrue(info.templateStale);
        assertEquals("读取 assets/init.js 失败", info.detail);
        assertFalse("非 zip 应给出可读的解析失败原因", info.inspectError.isEmpty());
        assertEquals(sha256Of(notZip), info.sha256);
    }

    @Test public void missingFileAndDirectoryAreUnavailable() throws Exception {
        ApkTemplateInspector.Info missing = ApkTemplateInspector.inspect(
                new File(tmp.getRoot(), "does-not-exist.apk"), ApkTemplateInspector.SOURCE_SDCARD);
        assertFalse(missing.available);
        assertTrue(missing.templateStale);
        assertEquals("", missing.sha256);
        assertEquals("", missing.engineInitSha256);
        assertEquals(0L, missing.sizeBytes);
        assertEquals("模板文件不存在", missing.detail);
        assertEquals(1, missing.warnings.length());

        ApkTemplateInspector.Info directory = ApkTemplateInspector.inspect(
                tmp.newFolder("a-directory"), ApkTemplateInspector.SOURCE_SDCARD);
        assertFalse(directory.available);
        assertEquals("模板文件不存在", directory.detail);

        ApkTemplateInspector.Info nullFile = ApkTemplateInspector.inspect(
                (File) null, ApkTemplateInspector.SOURCE_NONE);
        assertFalse(nullFile.available);
        assertEquals("模板文件不存在", nullFile.detail);

        ApkTemplateInspector.Info nullBytes = ApkTemplateInspector.inspect(
                (byte[]) null, ApkTemplateInspector.SOURCE_NONE, "assets/template.apk");
        assertFalse(nullBytes.available);
        assertEquals("模板字节为空", nullBytes.detail);
    }

    // ------------------------------------------------------------ byte[] 路径与 File 路径一致

    @Test public void byteArrayPathMatchesFilePath() throws Exception {
        byte[] initJs = ("global." + MARKER + " = function (dir) {};\n").getBytes(StandardCharsets.UTF_8);
        File template = zip("template-bytes.apk", initJs, 0);
        byte[] bytes = Files.readAllBytes(template.toPath());

        ApkTemplateInspector.Info fromFile = ApkTemplateInspector.inspect(template, ApkTemplateInspector.SOURCE_BUNDLED);
        ApkTemplateInspector.Info fromBytes = ApkTemplateInspector.inspect(
                bytes, ApkTemplateInspector.SOURCE_BUNDLED, "assets/template.apk");

        assertEquals(fromFile.available, fromBytes.available);
        assertEquals(fromFile.sizeBytes, fromBytes.sizeBytes);
        assertEquals(fromFile.sha256, fromBytes.sha256);
        assertEquals(fromFile.engineInitSha256, fromBytes.engineInitSha256);
        assertEquals(fromFile.engineRequireRootFix, fromBytes.engineRequireRootFix);
        assertEquals(fromFile.templateStale, fromBytes.templateStale);
        assertEquals(fromFile.inspectError, fromBytes.inspectError);
        assertEquals(fromFile.detail, fromBytes.detail);
        assertEquals("assets/template.apk", fromBytes.path);
    }

    // ------------------------------------------------------------ 夹具与独立参考实现

    /**
     * 造一个只有 {@code assets/init.js}（可空）与可选 STORED padding 的 zip。
     * padding 用 STORED 是为了"压缩后仍 >64MiB"，同时避免压缩 65MiB 零字节的 CPU。
     */
    private File zip(String name, byte[] initJs, int paddingBytes) throws IOException {
        File target = new File(tmp.getRoot(), name);
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(target))) {
            if (initJs != null) {
                ZipEntry entry = new ZipEntry("assets/init.js");
                entry.setTime(0L);
                out.putNextEntry(entry);
                out.write(initJs);
                out.closeEntry();
            }
            if (paddingBytes > 0) {
                ZipEntry padding = new ZipEntry("padding.bin");
                padding.setMethod(ZipEntry.STORED);
                padding.setSize(paddingBytes);
                padding.setCompressedSize(paddingBytes);
                padding.setCrc(crc32OfZeros(paddingBytes));
                padding.setTime(0L);
                out.putNextEntry(padding);
                byte[] block = new byte[1024 * 1024];
                int remaining = paddingBytes;
                while (remaining > 0) {
                    int chunk = Math.min(block.length, remaining);
                    out.write(block, 0, chunk);
                    remaining -= chunk;
                }
                out.closeEntry();
            }
        }
        return target;
    }

    private static long crc32OfZeros(int bytes) {
        CRC32 crc = new CRC32();
        byte[] block = new byte[1024 * 1024];
        int remaining = bytes;
        while (remaining > 0) {
            int chunk = Math.min(block.length, remaining);
            crc.update(block, 0, chunk);
            remaining -= chunk;
        }
        return crc.getValue();
    }

    private static void write(File target, byte[] bytes) throws IOException {
        try (FileOutputStream out = new FileOutputStream(target)) {
            out.write(bytes);
        }
    }

    /** 独立实现（**不调用被测的 ApkTemplateInspector.sha256**），避免用被测代码验证被测代码。 */
    private static String sha256(byte[] bytes) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String sha256Of(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) digest.update(buffer, 0, read);
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] hash) {
        StringBuilder builder = new StringBuilder(hash.length * 2);
        for (byte value : hash) builder.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return builder.toString();
    }
}
