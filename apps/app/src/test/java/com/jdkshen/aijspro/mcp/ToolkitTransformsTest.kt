package com.jdkshen.aijspro.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** §11：变换的证据与断言必须是可复核的，失败时不能声称“解密成功”。 */
class ToolkitTransformsTest {
    @Test
    fun xorAndMagicAssertionAreEvidenceBased() {
        val input = "MAGIC-payload".toByteArray()
        val keyed = ToolkitTransforms.apply(input, 0, input.size, "xor", mapOf("keyHex" to "0f0f"), analysisOnly = true)
        assertNotEquals(String(input), String(keyed.bytes))
        assertTrue(keyed.evidence.get("verified").asBoolean)
        assertEquals(true, keyed.evidence.getAsJsonObject("classification").get("analysisOnly").asBoolean)
        assertFalse(keyed.evidence.getAsJsonObject("classification").get("mayFlowIntoBuild").asBoolean)
        // 密钥值不能出现在证据里，只允许长度+哈希前缀
        val maskedKey = keyed.evidence.getAsJsonObject("params").get("keyHex").asString
        assertTrue(maskedKey.contains("len="))
        assertFalse(maskedKey.contains("0f0f"))

        val plain = ToolkitTransforms.apply(keyed.bytes, 0, keyed.bytes.size, "xor",
            mapOf("keyHex" to "0f0f", "expectMagic" to ToolkitTransforms.bytesToHex("MAGIC".toByteArray())))
        assertEquals("MAGIC-payload", String(plain.bytes))
        assertTrue(plain.evidence.get("verified").asBoolean)
    }

    @Test
    fun wrongMagicIsReportedAsUnverified() {
        val outcome = ToolkitTransforms.apply("abcd".toByteArray(), 0, 4, "xor",
            mapOf("keyHex" to "00", "expectMagic" to "89504e47"))
        assertFalse(outcome.evidence.get("verified").asBoolean)
        assertTrue(outcome.evidence.get("verifiedNote").asString.contains("不能声称"))
        assertEquals(1, outcome.evidence.getAsJsonArray("assertions").size())
        assertFalse(outcome.evidence.getAsJsonArray("assertions")[0].asJsonObject.get("passed").asBoolean)
    }

    @Test
    fun byteSwapAndIndexPermutationAreValidated() {
        val swapped = ToolkitTransforms.apply(byteArrayOf(1, 2, 3, 4), 0, 4, "bswap", mapOf("width" to "4"))
        assertEquals(listOf<Byte>(4, 3, 2, 1), swapped.bytes.toList())

        val permuted = ToolkitTransforms.apply("abcd".toByteArray(), 0, 4, "index_permute",
            mapOf("permutation" to "3,2,1,0"))
        assertEquals("dcba", String(permuted.bytes))
        assertTrue(permuted.evidence.get("verified").asBoolean)

        try {
            ToolkitTransforms.apply("abcd".toByteArray(), 0, 4, "index_permute", mapOf("permutation" to "0,0,1,2"))
            throw AssertionError("重复下标必须被拒绝")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("重复"))
        }
    }

    @Test
    fun aesDecryptReportsKeyLengthAndNeverEchoesKey() {
        val key = ByteArray(16) { it.toByte() }
        val iv = ByteArray(16) { (it + 1).toByte() }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val encrypted = cipher.doFinal("SECRET-OK".toByteArray())

        val outcome = ToolkitTransforms.apply(encrypted, 0, encrypted.size, "aes_decrypt",
            mapOf("keyHex" to ToolkitTransforms.bytesToHex(key), "ivHex" to ToolkitTransforms.bytesToHex(iv),
                "mode" to "CBC", "expectMagic" to ToolkitTransforms.bytesToHex("SECRET".toByteArray())))
        assertEquals("SECRET-OK", String(outcome.bytes))
        assertTrue(outcome.evidence.get("verified").asBoolean)
        assertFalse(outcome.evidence.getAsJsonObject("params").get("keyHex").asString.contains("00010203"))
        assertTrue(outcome.evidence.getAsJsonObject("params").get("keyHex").asString.contains("len=16"))
    }

    @Test
    fun inflateAndPointerTableRestoreHaveBoundsAndAssertions() {
        val deflater = Deflater()
        val payload = "payload-0123456789".toByteArray()
        deflater.setInput(payload); deflater.finish()
        val compressed = ByteArrayOutputStream().also { output ->
            val buffer = ByteArray(64)
            while (!deflater.finished()) output.write(buffer, 0, deflater.deflate(buffer))
        }.toByteArray()
        val inflated = ToolkitTransforms.apply(compressed, 0, compressed.size, "inflate", mapOf("mode" to "zlib"))
        assertEquals("payload-0123456789", String(inflated.bytes))

        val table = ByteArray(8) { 1 } // 两个 4 字节指针，值都是 0x01010101
        val restored = ToolkitTransforms.apply(table, 0, table.size, "pointer_table_restore",
            mapOf("width" to "4", "baseAddress" to "0x1000", "lowAddress" to "0x1000", "highAddress" to "0x2000"))
        assertEquals(1, restored.evidence.getAsJsonArray("assertions").size())
        assertFalse(restored.evidence.get("verified").asBoolean) // 0x01011101 超出 range，必须不通过

        try {
            ToolkitTransforms.apply(ByteArray(8), 0, 8, "xor", mapOf("keyHex" to "00"), analysisOnly = false)
            // analysisOnly=false 是允许的，这里只验证分类字段
        } catch (unexpected: IllegalArgumentException) { throw unexpected }
        val modify = ToolkitTransforms.apply(ByteArray(8), 0, 8, "xor", mapOf("keyHex" to "00"), analysisOnly = false)
        assertTrue(modify.evidence.getAsJsonObject("classification").get("mayFlowIntoBuild").asBoolean)
    }

    @Test
    fun rangeBoundsAndSizeLimitsAreEnforced() {
        try { ToolkitTransforms.apply(ByteArray(4), 0, 99, "xor", mapOf("keyHex" to "00")); throw AssertionError("应拒绝越界") }
        catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.contains("越界")) }
        try { ToolkitTransforms.apply(ByteArray(4), 0, 4, "nope"); throw AssertionError("应拒绝未知 op") }
        catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.contains("不支持")) }
    }
}
