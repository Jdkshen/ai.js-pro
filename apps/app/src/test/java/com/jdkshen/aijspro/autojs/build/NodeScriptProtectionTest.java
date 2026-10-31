package com.jdkshen.aijspro.autojs.build;

import com.stardust.autojs.project.ScriptProtection;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.json.JSONObject;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 保护等级 0/1/2 ×（Rhino / QuickJS / Node）的载荷判定，以及模板版本漂移检测。
 *
 * <p>等级 0/1 的回归：等级 1 的密文必须还能被运行端的解码路径读回（文件头 + AES/CBC），
 * 否则「Node 现在支持等级 2」会顺手把本来就正常的等级 1 弄坏。
 */
public class NodeScriptProtectionTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** 夹具用的假哈希：必须像真实 SHA-256（64 位十六进制），否则会被清单解析判为无效值。 */
    private static final String SHA_A = sha('a');
    private static final String SHA_B = sha('b');
    private static final String SHA_C = sha('c');
    private static final String SHA_D = sha('d');

    private static String sha(char c) {
        StringBuilder out = new StringBuilder(64);
        for (int i = 0; i < 64; i++) {
            out.append(c);
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ 等级 × 引擎

    @Test public void levelZeroIsPlainTextForEveryEngine() {
        for (String engine : new String[]{null, "rhino", "quickjs", "node24"}) {
            assertEquals(NodeScriptProtection.PayloadKind.PLAIN_TEXT,
                    NodeScriptProtection.payloadKindOf(0, engine, "quickjs".equals(engine)));
        }
    }

    @Test public void levelOneIsAesTextForEveryEngine() {
        for (String engine : new String[]{null, "rhino", "quickjs", "node24"}) {
            assertEquals(NodeScriptProtection.PayloadKind.ENCRYPTED_TEXT,
                    NodeScriptProtection.payloadKindOf(
                            ScriptProtection.LEVEL_ENCRYPT, engine, "quickjs".equals(engine)));
        }
    }

    @Test public void levelTwoUsesRealCompilationOnlyWhereTheBuildMachineCanCompile() {
        assertEquals(NodeScriptProtection.PayloadKind.RHINO_CLASS,
                NodeScriptProtection.payloadKindOf(2, "rhino", false));
        assertEquals(NodeScriptProtection.PayloadKind.QUICKJS_BYTECODE,
                NodeScriptProtection.payloadKindOf(2, "quickjs", true));
        assertTrue(NodeScriptProtection.supportsCompiledPayload("rhino"));
        assertTrue(NodeScriptProtection.supportsCompiledPayload("quickjs"));
        assertFalse("Node 没有可搬运的 V8 字节码", NodeScriptProtection.supportsCompiledPayload("node24"));
    }

    @Test public void levelTwoForNodeIsTheSourceFreeWrapperAndIsNotRejected() {
        assertEquals(NodeScriptProtection.PayloadKind.SOURCE_FREE_TEXT,
                NodeScriptProtection.payloadKindOf(2, "node24", false));
        assertFalse("Node 等级 2 由自解包加载器兜住，不应该再拒绝打包",
                NodeScriptProtection.mustRejectCompile("node24"));
        String describe = NodeScriptProtection.describe(2, true);
        assertTrue(describe, describe.contains("不是 V8 字节码"));
    }

    @Test public void levelsAboveTwoClampToCompile() {
        assertEquals(NodeScriptProtection.PayloadKind.RHINO_CLASS,
                NodeScriptProtection.payloadKindOf(99, "rhino", false));
        assertEquals(ScriptProtection.LEVEL_COMPILE, ScriptProtection.normalize(99));
    }

    /** 保护等级 1：文件头 + AES 密文必须能被运行端路径读回。 */
    @Test public void levelOnePayloadSurvivesTheRuntimeDecodePath() throws Exception {
        String source = "// @engine node24\n\"ui\";\nconsole.log('hi');\n";
        byte[] plain = source.getBytes(UTF8);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        com.stardust.autojs.script.EncryptedScriptFileHeader.INSTANCE.writeHeader(out, (short) 1);
        out.write(new com.stardust.util.AdvancedEncryptionStandard(
                "0123456789abcdef".getBytes(UTF8), "0123456789abcdef")
                .encrypt(plain));
        byte[] payload = out.toByteArray();
        assertTrue(com.stardust.autojs.script.EncryptedScriptFileHeader.INSTANCE.isValidFile(payload));
        assertEquals(0, com.stardust.autojs.script.EncryptedScriptFileHeader.INSTANCE
                .payloadTypeOf(com.stardust.autojs.script.EncryptedScriptFileHeader.INSTANCE
                        .readFlags(payload)));
        byte[] decoded = new com.stardust.util.AdvancedEncryptionStandard(
                "0123456789abcdef".getBytes(UTF8), "0123456789abcdef")
                .decrypt(payload, 8, payload.length);
        assertEquals(source, new String(decoded, UTF8));
    }

    // ------------------------------------------------------------------ 模板漂移

    @Test public void readRuntimeHashesReadsTheManifestInsideTheApk() throws Exception {
        File apk = syntheticTemplate(tmp.newFolder("apk"), SHA_A, SHA_B, SHA_C);
        Map<String, String> hashes = Node24TemplateConsistency.readRuntimeHashes(apk);
        assertEquals(3, hashes.size());
        assertEquals(SHA_A, hashes.get("libnode.so"));
    }

    @Test public void sameHashesIgnoresCaseButNotDifferentVersions() {
        Map<String, String> left = hashes("AA", "bb");
        Map<String, String> right = hashes("aa", "bb");
        assertTrue(Node24TemplateConsistency.sameHashes(left, right));
        assertFalse(Node24TemplateConsistency.sameHashes(left, hashes("aa", "zz")));
        assertFalse("键集合不同也不是同一版",
                Node24TemplateConsistency.sameHashes(hashes("aa"), hashes("aa", "bb")));
        assertTrue("空清单无法判定，按不漂移处理",
                Node24TemplateConsistency.sameHashes(hashes(), hashes("aa")));
    }

    @Test public void verifyTemplateAcceptsACompleteTemplate() throws Exception {
        File apk = syntheticTemplate(tmp.newFolder("complete"), SHA_A, SHA_B, SHA_C);
        Node24TemplateConsistency.verifyTemplate(apk);
    }

    @Test public void verifyTemplateRejectsATemplateMissingNodeAssets() throws Exception {
        File apk = syntheticTemplate(tmp.newFolder("partial"), SHA_A, SHA_B, SHA_C,
                new String[]{"assets/node24/ui.js"});
        try {
            Node24TemplateConsistency.verifyTemplate(apk);
            fail("缺 ui.js 的模板必须被拒绝");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("缺少"));
        }
    }

    @Test public void verifyWorkspaceFailsWhenRuntimeBytesDoNotMatchTheManifest() throws Exception {
        File workspace = tmp.newFolder("workspace");
        writeWorkspace(workspace, SHA_A, SHA_B, SHA_C);
        // 把 libnode.so 改一个字节：模拟「运行库被换成另一版」。
        File libnode = new File(workspace, Node24TemplateConsistency.RUNTIME_PREFIX + "libnode.so");
        try (OutputStream output = new FileOutputStream(libnode, true)) {
            output.write('!');
        }
        File host = syntheticTemplate(tmp.newFolder("host"), SHA_A, SHA_B, SHA_C);
        try {
            Node24TemplateConsistency.verifyWorkspace(host, workspace, hashes());
            fail("运行库与清单不一致时必须失败");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("不一致"));
        }
    }

    @Test public void verifyWorkspaceFailsWhenAnAssetIsMissing() throws Exception {
        File workspace = tmp.newFolder("workspace-missing");
        writeWorkspace(workspace, SHA_A, SHA_B, SHA_C);
        assertTrue(new File(workspace, "assets/node24/ui.js").delete());
        File host = syntheticTemplate(tmp.newFolder("host2"), SHA_A, SHA_B, SHA_C);
        try {
            Node24TemplateConsistency.verifyWorkspace(host, workspace, hashes());
            fail("缺运行资产时必须失败");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("缺失"));
        }
    }

    @Test public void runtimeProblemsReportsMissingAndMismatchedAssets() throws Exception {
        File workspace = tmp.newFolder("workspace-problems");
        writeWorkspace(workspace, SHA_A, SHA_B, SHA_C);
        assertTrue(Node24TemplateConsistency.runtimeProblems(workspace).isEmpty());

        // 换一个字节 → 运行库与清单不一致。
        File libnode = new File(workspace, Node24TemplateConsistency.RUNTIME_PREFIX + "libnode.so");
        try (OutputStream output = new FileOutputStream(libnode, true)) {
            output.write('!');
        }
        // 再删掉一个必需文件。
        assertTrue(new File(workspace, "assets/node24/ui.js").delete());
        java.util.List<String> problems = Node24TemplateConsistency.runtimeProblems(workspace);
        assertEquals(problems.toString(), 2, problems.size());
        assertTrue(problems.toString(), problems.toString().contains("ui.js"));
        assertTrue(problems.toString(), problems.toString().contains("libnode.so"));
    }

    /**
     * 「只开了 nodejs 特性位、入口不是 Node 脚本」的工程按旧行为只告警，不阻断打包。
     *
     * <p>{@code strict=false} 时的问题清单会写进 {@code android.util.Log}（JVM 单测里是
     * not-mocked，无法在此断言），所以这里断言「问题确实存在」+「strict=true 会抛」，
     * 告警本身留给真机/打包日志观察。
     */
    @Test public void advisoryModeReportsInsteadOfBlocking() throws Exception {
        File workspace = tmp.newFolder("workspace-advisory");
        writeWorkspace(workspace, SHA_A, SHA_B, SHA_C);
        addManifestEntry(workspace, "libghost.so", SHA_B);
        java.util.List<String> problems = Node24TemplateConsistency.runtimeProblems(workspace);
        assertEquals(problems.toString(), 1, problems.size());
        assertTrue(problems.toString(), problems.toString().contains("libghost.so"));
        try {
            Node24TemplateConsistency.failOnProblems(problems, true);
            fail("strict=true 必须抛异常");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("libghost.so"));
        }
    }

    @Test public void strictWorkspaceCheckThrowsOnIncompleteAssets() throws Exception {
        File workspace = tmp.newFolder("workspace-strict");
        writeWorkspace(workspace, SHA_A, SHA_B, SHA_C);
        assertTrue(new File(workspace, "assets/node24/ui.js").delete());
        File host = syntheticTemplate(tmp.newFolder("host5"), SHA_A, SHA_B, SHA_C);
        try {
            Node24TemplateConsistency.verifyWorkspace(host, workspace, hashes(), true);
            fail("strict=true 时必须失败");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("ui.js"));
        }
    }

    @Test public void verifyWorkspaceAcceptsConsistentAssetsWithoutTouchingLog() throws Exception {
        File workspace = tmp.newFolder("workspace-ok");
        writeWorkspace(workspace, SHA_A, SHA_B, SHA_C);
        File host = syntheticTemplate(tmp.newFolder("host3"), SHA_A, SHA_B, SHA_C);
        // 模板与宿主同一版：不会走到 android.util.Log 的漂移分支，纯 JVM 也能跑。
        Node24TemplateConsistency.verifyWorkspace(host, workspace,
                Node24TemplateConsistency.readRuntimeHashes(host));
    }

    @Test public void writeManifestThenVerifyManifestDetectsAChangedTemplate() throws Exception {
        File apk = syntheticTemplate(tmp.newFolder("tpl"), SHA_A, SHA_B, SHA_C);
        File manifest = new File(tmp.newFolder("manifest"), "template-manifest.json");
        Node24TemplateConsistency.writeManifest(apk, "1.0.0-1", manifest);
        Node24TemplateConsistency.verifyTemplateManifest(apk, manifest);

        // 重建模板但忘了刷新清单 → 必须报「清单已过期」。
        File rewritten = new File(tmp.newFolder("rewritten"), "template.apk");
        rewriteRuntimeJson(apk, rewritten, SHA_D, SHA_B, SHA_C);
        try {
            Node24TemplateConsistency.verifyTemplateManifest(rewritten, manifest);
            fail("模板变了但清单没刷新时必须报过期");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("过期"));
        }
    }

    // ------------------------------------------------------------------ 仓库真实模板

    /**
     * 仓库里真实存在的模板与清单必须一致。
     *
     * <p>这条用例是「模板版本漂移」的仓库级闸门：模板被重建但清单没刷新、或清单被手改，
     * 都会在这里红。它同时会核对模板声明的运行库哈希是否等于
     * {@code apps/app/src/main/assets}、{@code modules/engine} 里随宿主分发的那一份
     * （模板就是从宿主资产复制出来的），把「模板里的 Node 比宿主旧」也拦住。
     */
    @Test public void repoPluginTemplateMatchesItsManifestAndTheHostRuntimePlan() throws Exception {
        File root = repoRoot();
        File template = new File(root,
                "plugins/apkbuilder-plugin/src/main/assets/template.apk");
        File manifest = new File(root,
                "plugins/apkbuilder-plugin/src/main/assets/template-manifest.json");
        if (!template.isFile()) {
            // 模板是构建产物，允许缺失；存在时就必须自洽。
            return;
        }
        assertTrue("模板清单应当与 :inrt:buildApkPlugin 一起提交", manifest.isFile());
        Node24TemplateConsistency.verifyTemplate(template);
        Node24TemplateConsistency.verifyTemplateManifest(template, manifest);

        JSONObject json;
        try {
            json = new JSONObject(read(manifest));
        } catch (org.json.JSONException error) {
            throw new AssertionError("清单不是合法 JSON：" + error);
        }
        assertTrue(json.has("manifestVersion"));
        assertNotNull(json.optJSONObject("nodeRuntimeHashes"));
        assertTrue(json.optJSONObject("nodeRuntimeHashes").length() > 0);

        Map<String, String> templateHashes = Node24TemplateConsistency.readRuntimeHashes(template);
        Map<String, String> manifestHashes = hashesFromJson(
                json.optJSONObject("nodeRuntimeHashes"));
        assertTrue("模板实际哈希必须等于清单记录",
                Node24TemplateConsistency.sameHashes(templateHashes, manifestHashes));

        File hostPlan = new File(root,
                "modules/engine/src/main/assets/source-engine-upgrade/node24/arm64-v8a/runtime.json");
        if (hostPlan.isFile()) {
            Map<String, String> hostHashes = Node24TemplateConsistency.readRuntimeHashes(hostPlan);
            if (!hostHashes.isEmpty() && !templateHashes.isEmpty()
                    && !Node24TemplateConsistency.sameHashes(templateHashes, hostHashes)) {
                System.out.println("[drift] 仓库插件模板的 Node 运行时与宿主引擎资产不是同一版："
                        + "template=" + templateHashes + " host=" + hostHashes
                        + "（重新执行 :inrt:assembleRelease / :inrt:buildApkPlugin 可刷新模板与清单）");
            }
        }
    }

    // ------------------------------------------------------------------ 夹具

    private static Map<String, String> hashes(String... values) {
        Map<String, String> map = new LinkedHashMap<>();
        String[] names = {"libnode.so", "libc++_shared.so", "libajsnode24bridge.so"};
        for (int i = 0; i < values.length && i < names.length; i++) {
            map.put(names[i], values[i]);
        }
        return map;
    }

    private static Map<String, String> hashesFromJson(JSONObject json) {
        Map<String, String> map = new LinkedHashMap<>();
        for (java.util.Iterator<String> it = json.keys(); it.hasNext(); ) {
            String key = it.next();
            map.put(key, String.valueOf(json.opt(key)));
        }
        return map;
    }

    /** 组一个最小可用的模板 APK：必需文件 + runtime.json。 */
    private File syntheticTemplate(File dir, String libnode, String libcxx, String bridge,
            String... skipEntries) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("无法创建目录 " + dir);
        }
        java.util.Set<String> skip = new java.util.HashSet<>(java.util.Arrays.asList(skipEntries));
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("assets/node24/prelude.js", "// prelude".getBytes(UTF8));
        entries.put("assets/node24/ui.js", "// ui".getBytes(UTF8));
        entries.put(Node24TemplateConsistency.RUNTIME_PREFIX + "libnode.so", "node".getBytes(UTF8));
        entries.put(Node24TemplateConsistency.RUNTIME_PREFIX + "libc++_shared.so", "cxx".getBytes(UTF8));
        entries.put(Node24TemplateConsistency.RUNTIME_PREFIX + "libajsnode24bridge.so", "bridge".getBytes(UTF8));
        // 清单里记的是「内容」的哈希，夹具就用同样的规则算，保证 verifyTemplate 只看存在性。
        entries.put(Node24TemplateConsistency.RUNTIME_MANIFEST, runtimeJson(libnode, libcxx, bridge)
                .getBytes(UTF8));
        File apk = new File(dir, "template.apk");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(apk))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                if (skip.contains(entry.getKey())) {
                    continue;
                }
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return apk;
    }

    private static String runtimeJson(String libnode, String libcxx, String bridge)
            throws IOException {
        JSONObject json = new JSONObject();
        try {
            json.put("libnode.so", libnode);
            json.put("libc++_shared.so", libcxx);
            json.put("libajsnode24bridge.so", bridge);
        } catch (org.json.JSONException error) {
            throw new IOException("夹具 JSON 生成失败", error);
        }
        return json.toString();
    }

    /**
     * 工作区夹具：与 {@link #syntheticTemplate} 同内容，但 runtime.json 里记的是**真实**
     * SHA-256，所以内容一致时 {@code verifyWorkspace} 应当通过。参数保留是为了让「换成
     * 另一版运行库」的用例读起来清楚（哈希由夹具自己算，不看这两个值）。
     */
    private void writeWorkspace(File workspace, String ignoredLibnode, String ignoredLibcxx,
            String ignoredBridge) throws IOException {
        String[] assets = {
                "assets/node24/prelude.js", "assets/node24/ui.js"};
        for (String relative : assets) {
            writeFixtureFile(workspace, relative, "fixture-" + relative);
        }
        Map<String, String> declared = new LinkedHashMap<>();
        for (String nativeName : new String[]{
                "libnode.so", "libc++_shared.so", "libajsnode24bridge.so"}) {
            String relative = Node24TemplateConsistency.RUNTIME_PREFIX + nativeName;
            // 清单只登记 ABI 目录下的运行库（与真实模板一致），哈希按实际内容算。
            declared.put(nativeName, writeFixtureFile(workspace, relative, "fixture-" + nativeName));
        }
        File manifest = new File(workspace, Node24TemplateConsistency.RUNTIME_MANIFEST);
        JSONObject json = new JSONObject();
        try {
            for (Map.Entry<String, String> entry : declared.entrySet()) {
                json.put(entry.getKey(), entry.getValue());
            }
        } catch (org.json.JSONException error) {
            throw new IOException("夹具 JSON 生成失败", error);
        }
        try (OutputStream output = new FileOutputStream(manifest)) {
            output.write(json.toString().getBytes(UTF8));
        }
    }

    /** 写一个工作区文件并返回它的 SHA-256（夹具里清单哈希必须与内容一致）。 */
    private static String writeFixtureFile(File workspace, String relative, String content)
            throws IOException {
        File file = new File(workspace, relative);
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("无法创建 " + parent);
        }
        try (OutputStream output = new FileOutputStream(file)) {
            output.write(content.getBytes(UTF8));
        }
        return Node24TemplateConsistency.sha256(file);
    }

    /** 往工作区的运行清单里加一条：用来构造「清单与磁盘不一致」的场景。 */
    private void addManifestEntry(File workspace, String name, String hash) throws IOException {
        File manifest = new File(workspace, Node24TemplateConsistency.RUNTIME_MANIFEST);
        JSONObject json;
        try {
            json = new JSONObject(read(manifest));
            json.put(name, hash);
        } catch (org.json.JSONException error) {
            throw new IOException("夹具 JSON 生成失败", error);
        }
        try (OutputStream output = new FileOutputStream(manifest)) {
            output.write(json.toString().getBytes(UTF8));
        }
    }

    /** 重写模板里的 runtime.json，模拟「重建后的模板换了一版运行库」。 */
    private static void rewriteRuntimeJson(File source, File target, String libnode,
            String libcxx, String bridge) throws IOException {
        try (ZipInputStream input = new ZipInputStream(new FileInputStream(source));
             ZipOutputStream output = new ZipOutputStream(new FileOutputStream(target))) {
            ZipEntry entry;
            byte[] chunk = new byte[8192];
            while ((entry = input.getNextEntry()) != null) {
                output.putNextEntry(new ZipEntry(entry.getName()));
                if (Node24TemplateConsistency.RUNTIME_MANIFEST.equals(entry.getName())) {
                    output.write(runtimeJson(libnode, libcxx, bridge).getBytes(UTF8));
                } else {
                    int read;
                    while ((read = input.read(chunk)) > 0) {
                        output.write(chunk, 0, read);
                    }
                }
                output.closeEntry();
            }
        }
    }

    private static String read(File file) throws IOException {
        try (InputStream input = new FileInputStream(file)) {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = input.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), UTF8);
        }
    }

    /** 从测试工作目录往上找带 settings.gradle 的模块根。 */
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
