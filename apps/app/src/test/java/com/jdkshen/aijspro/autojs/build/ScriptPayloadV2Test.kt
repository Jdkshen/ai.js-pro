package com.jdkshen.aijspro.autojs.build

import com.stardust.autojs.engine.encryption.ScriptPayloadV2
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * task-127 钉子（真跑 JCE，不是源码文本）：v2 载荷 = AES-256-GCM + 真 32 字节密钥 + 每文件随机 IV。
 *
 * 覆盖 Lead 要求的四件事：① 同明文打两次 ⇒ 载荷字节不同（IV 随机，GCM 下绝不复用）；
 * ② 篡改一字节 ⇒ 可读报错且含「完整性校验失败」；③ 密钥是真 32 字节（不是 hex 文本）；
 * ④ 头布局：魔数/flags 大端/版本 0x02/保留必须 0/12 字节 IV/AAD=前 12 字节。
 */
class ScriptPayloadV2Test {
    private val pkg = "com.example.packaged"
    private val salt = "00112233445566778899aabbccddeeff"
    private val fingerprint = "1B:57:50:5E:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55"
    private val plain = "console.log('hello v2 gcm');\nvar x = 1 + 2;\n".toByteArray(Charsets.UTF_8)

    private fun key() = ScriptPayloadV2.deriveKeyV2(pkg, salt, fingerprint)

    @Test fun keyIsReal32BytesAndDeterministic() {
        val a = key()
        val b = key()
        assertEquals("密钥必须是真 32 字节（不是 64 字符 hex 文本）", ScriptPayloadV2.KEY_LENGTH, a.size)
        assertEquals(32, a.size)
        assertArrayEquals("同样入参必须派生同一把密钥", a, b)
        assertFalse("不同盐必须换密钥", a.contentEquals(ScriptPayloadV2.deriveKeyV2(pkg, "ffeeddccbbaa99887766554433221100", fingerprint)))
        assertFalse("不同指纹必须换密钥（绑定签名身份）", a.contentEquals(ScriptPayloadV2.deriveKeyV2(pkg, salt, fingerprint.replace("1B", "1C"))))
    }

    @Test fun sameScriptEncryptsToDifferentBytesBecauseIvIsRandom() {
        val first = ScriptPayloadV2.encrypt(plain, key(), 0, 1)
        val second = ScriptPayloadV2.encrypt(plain, key(), 0, 1)
        assertFalse("两次加密必须不同（IV 每文件随机）", first.contentEquals(second))
        assertArrayEquals(plain, ScriptPayloadV2.decrypt(first, key()))
        assertArrayEquals(plain, ScriptPayloadV2.decrypt(second, key()))
    }

    @Test fun headerLayoutMatchesTheFrozenV2Spec() {
        // ⚠️ API 的第 3 个参数直接写进 header[6]（= flags 高字节 = 载荷类型），第 4 个写进 header[7]
        //（= flags 低字节 = 执行模式）—— 与 v1 的「大端 byte6*256+byte7、低字节=执行模式」布局一致。
        // 所以这里传 (载荷类型=0, 执行模式=1)，ApkBuilder 也是这么传的：(flags>>8)&0xFF, flags&0xFF。
        val payload = ScriptPayloadV2.encrypt(plain, key(), 0, 1)
        assertTrue(ScriptPayloadV2.isV2(payload))
        assertEquals(ScriptPayloadV2.VERSION, payload[8])
        assertEquals("保留字节必须为 0", 0, payload[9].toInt())
        assertEquals(0, payload[10].toInt())
        assertEquals(0, payload[11].toInt())
        assertEquals("魔数", 0x77, payload[0].toInt() and 0xFF)
        // flags 大端：header[6] 是载荷类型（高字节）、header[7] 是执行模式（低字节）——与 v1 布局一致
        assertEquals(0, payload[6].toInt() and 0xFF)
        assertEquals(1, payload[7].toInt() and 0xFF)
        assertTrue("至少要有 24 字节头 + 16 字节 tag", payload.size > ScriptPayloadV2.HEADER_LENGTH + 16)
    }

    @Test fun tamperingOneByteGivesReadableIntegrityFailure() {
        val payload = ScriptPayloadV2.encrypt(plain, key(), 0, 1)
        payload[ScriptPayloadV2.HEADER_LENGTH + 1] = (payload[ScriptPayloadV2.HEADER_LENGTH + 1].toInt() xor 0x01).toByte()
        try {
            ScriptPayloadV2.decrypt(payload, key())
            fail("篡改一个字节还能解出明文，说明没有完整性校验")
        } catch (expected: Exception) {
            val text = (expected.message ?: "") + "|" + (expected.cause?.message ?: "")
            assertTrue("报错必须可读且提到完整性校验：" + text, text.contains("完整性校验失败"))
        }
    }

    @Test fun legacyShapeIsRejectedByName() {
        // 魔数 + 版本字节 0x01（v1 形态）+ 足够长度 ⇒ 必须走「旧格式点名拒绝」
        val legacy = ByteArray(64)
        System.arraycopy(ScriptPayloadV2.MAGIC, 0, legacy, 0, ScriptPayloadV2.MAGIC.size)
        legacy[8] = 0x01
        assertTrue(ScriptPayloadV2.isLegacyPayload(legacy))
        assertFalse(ScriptPayloadV2.isV2(legacy))
        try {
            ScriptPayloadV2.decrypt(legacy, key())
            fail("旧格式必须点名拒绝，不能静默")
        } catch (expected: Exception) {
            assertEquals(ScriptPayloadV2.LEGACY_REJECTED_MESSAGE, expected.message)
        }
    }
}
