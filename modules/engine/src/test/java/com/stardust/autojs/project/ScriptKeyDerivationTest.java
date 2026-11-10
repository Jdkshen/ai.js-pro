package com.stardust.autojs.project;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * task-122：加密**硬切换**到新方案后的判据（离线可跑）。
 *
 * <p>三条：⑴ 新方案往返稳定、key/vector 分离、绑定包名+盐+指纹；⑵ 源码钉：`ScriptKeyDerivation`
 * **不得**再有 `legacyKey`/`legacyVector`/`MD5`，运行端（inrt 启动器）**不得**再出现 `legacyKey(`/`legacyVector(`
 * 且旧产物必须给**点名错误**；⑶ 指纹可用性判据。
 */
public class ScriptKeyDerivationTest {

    private static final String PKG = "com.example.app";
    private static final String SALT = "0123456789abcdef0123456789abcdef";
    private static final String FP =
            "38166a4b51f1a3992c96512324345a994c8e7331a531e6e51ce3c9d91691cc70";

    /** 往返稳定 + key/vector 分离 + 三个输入都参与派生（换任一个 key 必变）。 */
    @Test
    public void newSchemeIsStableAndBoundToAllInputs() {
        String key = ScriptKeyDerivation.deriveKey(PKG, SALT, FP);
        String vector = ScriptKeyDerivation.deriveVector(SALT, FP);
        assertEquals("派生必须确定性", key, ScriptKeyDerivation.deriveKey(PKG, SALT, FP));
        assertEquals("派生必须确定性", vector, ScriptKeyDerivation.deriveVector(SALT, FP));
        assertEquals("key 32 字符（历史口径，见报告未核项）", 32, key.length());
        assertEquals("vector 16 字符", 16, vector.length());
        assertNotEquals("key/vector 必须分离", key.substring(0, 16), vector);
        assertNotEquals("换包名 ⇒ key 变", key, ScriptKeyDerivation.deriveKey("com.other.app", SALT, FP));
        assertNotEquals("换盐 ⇒ key 变", key,
                ScriptKeyDerivation.deriveKey(PKG, "ffffffffffffffffffffffffffffffff", FP));
        assertNotEquals("换指纹 ⇒ key 变", key,
                ScriptKeyDerivation.deriveKey(PKG, SALT, FP.replace('3', '4')));
        assertNotEquals("换指纹 ⇒ vector 变", vector,
                ScriptKeyDerivation.deriveVector(SALT, FP.replace('3', '4')));
    }

    /** 随机盐：长度 = 32 hex 字符、两次不同。 */
    @Test
    public void randomSaltIsHexAndUnique() {
        String a = ScriptKeyDerivation.randomSaltHex();
        String b = ScriptKeyDerivation.randomSaltHex();
        assertEquals(32, a.length());
        assertTrue(a.matches("[0-9a-f]{32}"));
        assertNotEquals(a, b);
    }

    /** 指纹可用性：null/短串不可用，64 位 hex（真证书指纹）可用。 */
    @Test
    public void fingerprintUsability() {
        assertFalse(ScriptKeyDerivation.hasCertificateFingerprint(null));
        assertFalse(ScriptKeyDerivation.hasCertificateFingerprint(""));
        assertFalse(ScriptKeyDerivation.hasCertificateFingerprint("abc"));
        assertTrue(ScriptKeyDerivation.hasCertificateFingerprint(FP));
    }

    /** 源码钉 ①：派生类只剩新方案（不得再有 legacyKey/legacyVector/MD5）。 */
    @Test
    public void derivationHasNoLegacyPathLeft() throws Exception {
        String source = read("src/main/java/com/stardust/autojs/project/ScriptKeyDerivation.java");
        // 只看**代码**，不看注释：KDoc 里保留「旧 MD5 方案已下线」的历史说明是允许的，
        // 但代码里不得再出现 MD5/legacyKey/legacyVector（Lead 修：旧实现扫全文 ⇒ 被 KDoc 误报）。
        String code = source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
        assertFalse("不得再有 legacyKey", code.contains("legacyKey"));
        assertFalse("不得再有 legacyVector", code.contains("legacyVector"));
        assertFalse("不得再 import MD5", code.contains("MD5"));
        assertTrue("deriveKey 必须在", source.contains("public static String deriveKey("));
        assertTrue("deriveVector 必须在", source.contains("public static String deriveVector("));
    }

    /** 源码钉 ②：运行端不得再回退旧派生；旧产物必须点名错误且可见。 */
    @Test
    public void runtimeRejectsOldArtifactsLoudly() throws Exception {
        String launcher = read("../../apps/inrt/src/main/java/com/jdkshen/aijspro/inrt/launch/AssetsProjectLauncher.kt");
        assertFalse("启动器不得再调 legacyKey(", launcher.contains("legacyKey("));
        assertFalse("启动器不得再调 legacyVector(", launcher.contains("legacyVector("));
        assertTrue("缺 salt/指纹必须点名错误", launcher.contains("旧加密方案已下线"));
        assertTrue("错误要点名缺哪个字段", launcher.contains("产物缺少 scriptSalt / signatureFingerprint"));
        assertTrue("错误要给出行动（重新打包）", launcher.contains("请用新版打包器重新打包"));
        assertTrue("新方案入口仍在", launcher.contains("ScriptKeyDerivation.deriveKey("));
    }

    private static String read(String relativePath) throws Exception {
        return new String(Files.readAllBytes(Paths.get(relativePath)), StandardCharsets.UTF_8);
    }
}