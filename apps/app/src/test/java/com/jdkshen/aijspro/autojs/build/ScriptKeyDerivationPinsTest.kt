package com.jdkshen.aijspro.autojs.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * task-123 钉子：**打包端只用新方案**派生脚本密钥（salt + 签名证书指纹）。
 *
 * 旧 MD5 方案（key=MD5(pkg+ver+main) / vector=MD5(buildId+name)[:16]）已从打包端删除；
 * 运行端不再支持它 ⇒ 任何降级产物都必然解不开，所以「拿不到指纹」时必须明确失败。
 */
class ScriptKeyDerivationPinsTest {
    private val builderPath = "src/main/java/com/jdkshen/aijspro/autojs/build/ApkBuilder.java"
    private val testPath = "src/androidTest/java/com/jdkshen/aijspro/packaging/ApkBuilderEncryptionTest.java"

    private fun read(relative: String): String {
        val file = File(relative)
        assertTrue("找不到 " + file.absolutePath, file.isFile)
        return file.readText()
    }

    @Test fun builderNoLongerDerivesTheScriptKeyWithMd5() {
        val source = read(builderPath)
        assertEquals("ApkBuilder 不得再用 MD5.md5( 派生脚本密钥", 0, Regex("MD5\\.md5\\(").findAll(source).count())
        assertEquals("不得再用旧算法的 legacyKey/legacyVector", 0,
            Regex("legacyKey|legacyVector").findAll(source).count())
        // 也不该再 import MD5（删掉旧算法后应无引用）
        assertEquals("不得再 import com.stardust.util.MD5", 0,
            Regex("import com\\.stardust\\.util\\.MD5;").findAll(source).count())
    }

    @Test fun builderAlwaysWritesSaltAndFingerprint() {
        val source = read(builderPath)
        assertTrue("必然把 scriptSalt 写进产物", source.contains("json.put(\"scriptSalt\", salt)"))
        assertTrue("必然把指纹写进产物", source.contains("json.put(\"signatureFingerprint\", fingerprint)"))
        assertTrue("派生只走 ScriptKeyDerivation.deriveKey", source.contains("ScriptKeyDerivation.deriveKey("))
        assertTrue("向量只走 ScriptKeyDerivation.deriveVector", source.contains("ScriptKeyDerivation.deriveVector("))
        assertTrue("拿不到盐时要生成随机盐", source.contains("ScriptKeyDerivation.randomSaltHex()"))
    }

    @Test fun missingFingerprintFailsInsteadOfSilentlyDowngrading() {
        val source = read(builderPath)
        assertTrue("必须明确说明不会静默降级", source.contains("不会静默降级"))
        assertTrue("需要密钥但没有指纹时必须抛错", source.contains("打包需要脚本加密密钥，但拿不到签名证书指纹"))
        assertTrue("只在确实需要脚本密钥时才失败（encryptLevel>0）", source.contains("boolean needsScriptKey = mEncryptLevel > 0"))
        // 旧行为（缺指纹就 remove 两个字段、留旧算法）不得残留
        assertEquals("不得再出现「缺指纹 ⇒ 删字段走旧算法」的注释/逻辑", 0,
            Regex("运行端看到字段缺失会走旧算法").findAll(source).count())
    }

    /** task-127：打包端的加密路径必须走 v2（ScriptPayloadV2），不得再用 CBC/JVM ScriptEncryption。 */
    @Test fun builderEncryptsWithV2PayloadApiOnly() {
        val source = read(builderPath)
        assertTrue("必须用 ScriptPayloadV2.encrypt 产出载荷", source.contains("ScriptPayloadV2.encrypt("))
        assertTrue("必须用 ScriptPayloadV2.deriveKeyV2 派生 32 字节密钥", source.contains("ScriptPayloadV2.deriveKeyV2("))
        assertEquals("不得再用 AdvancedEncryptionStandard（CBC）", 0,
            Regex("AdvancedEncryptionStandard").findAll(source).count())
        assertEquals("不得再走 JVM 侧 ScriptEncryption 加密", 0,
            source.split("ScriptEncryption.").size - 1)
        assertEquals("不得再自己写 v1 头", 0,
            source.split("writeHeader(").size - 1)
        assertTrue("native + 加密必须明确失败并复用引擎文案",
            source.contains("ScriptPayloadV2.NATIVE_UNSUPPORTED_MESSAGE"))
        assertTrue("32 字节密钥字段", source.contains("byte[] mKeyV2"))
    }

    @Test fun packagedTestDecryptsOnlyWithTheNewScheme() {
        val source = read(testPath)
        assertEquals("测试也不能再回退旧算法", 0, Regex("legacyKey|legacyVector").findAll(source).count())
        assertTrue("测试必须断言产物带 scriptSalt", source.contains("产物必须带 scriptSalt"))
        assertTrue("测试必须断言产物带指纹", source.contains("产物必须带 signatureFingerprint"))
    }
}
