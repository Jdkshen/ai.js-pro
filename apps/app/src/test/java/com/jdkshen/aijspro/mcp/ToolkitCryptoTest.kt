package com.jdkshen.aijspro.mcp

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.KeyPairGenerator

/**
 * crypto_（现场加解密）单测：全部用**已知向量**与真实 JCA 运算，不 mock 密码学。
 *
 * 覆盖：SHA-256("")/("abc")、HMAC-SHA256 的 RFC 4231 case 1/2、AES-GCM 的 NIST 向量
 * （零密钥向量 + 含 AAD 的 Test Case 4）加解密往返、tag/AAD 篡改必须 MAC_INVALID、
 * RSA 自签自验（同一私钥 hex / 公钥 / PEM 文件）+ 签名篡改验签失败，
 * 以及错误码分层（ALG_UNSUPPORTED/KEY_INVALID/MAC_INVALID/DECRYPT_FAILED/INPUT_TOO_LARGE）、
 * keyRef 越界、输入三选一冲突、失败不产出产物与工具面（schema/描述）接线。
 */
class ToolkitCryptoTest {

    @get:Rule val temp = TemporaryFolder()

    private val root: File by lazy { temp.newFolder("root") }
    private val store: File by lazy { temp.newFolder("store").apply { mkdirs() } }

    private fun call(vararg pairs: Pair<String, Any?>): JsonObject =
        ToolkitExtraTools.call(root, store, "crypto_", ToolkitIo.json(*pairs))

    private fun failure(vararg pairs: Pair<String, Any?>): ToolkitException =
        assertThrows(ToolkitException::class.java) { call(*pairs) }

    private fun hex(result: JsonObject): String = result.get("outputHex").asString

    // ------------------------------------------------------------------ hash

    @Test fun hashMatchesKnownVectors() {
        val empty = call("op" to "hash", "alg" to "SHA-256", "inputHex" to "")
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hex(empty))
        assertEquals("SHA-256", empty.get("alg").asString)
        assertEquals("JCA", empty.get("algorithmSource").asString)
        assertTrue(empty.get("provider").asString.isNotEmpty())
        assertEquals(32, empty.get("bytes").asInt)
        assertEquals("inputHex", empty.get("inputSource").asString)
        assertEquals("none", empty.get("keySource").asString)

        val abc = call("op" to "hash", "alg" to "sha256", "inputText" to "abc")
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex(abc))
        // digestOfInput 与 SHA-256 输出同源：hash/SHA-256 时两者必须一致
        assertEquals(hex(abc), abc.getAsJsonObject("digestOfInput").get("hex").asString)

        assertEquals("900150983cd24fb0d6963f7d28e17f72", hex(call("op" to "hash", "alg" to "MD5", "inputText" to "abc")))
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", hex(call("op" to "hash", "alg" to "SHA-1", "inputText" to "abc")))
        // alg 缺省=合理默认并在返回里回显
        assertEquals("SHA-256", call("op" to "hash", "inputText" to "abc").get("alg").asString)
    }

    // ------------------------------------------------------------------ HMAC

    @Test fun hmacMatchesRfc4231Case1AndCase2() {
        val case1 = call("op" to "hmac", "inputText" to "Hi There",
            "keyHex" to "0b".repeat(20))
        assertEquals("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7", hex(case1))
        assertEquals("HmacSHA256", case1.get("alg").asString)
        assertEquals("explicit", case1.get("keySource").asString)
        assertEquals(20, case1.get("keyBytes").asInt)
        assertEquals(32, case1.get("bytes").asInt)
        assertTrue(case1.get("keySha256Prefix").asString.isNotBlank())
        assertFalse("响应里不能出现密钥内容", case1.toString().contains("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"))

        val case2 = call("op" to "hmac", "inputText" to "what do ya want for nothing?", "keyHex" to "4a656665")
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843", hex(case2))

        // keyRef：工具目录内的密钥文件（同样只回显长度/前缀）
        File(root, "hmac.key").writeBytes(ByteArray(20) { 0x0b })
        val fromFile = call("op" to "hmac", "inputText" to "Hi There", "keyRef" to "hmac.key")
        assertEquals(hex(case1), hex(fromFile))
        assertEquals("file", fromFile.get("keySource").asString)
        assertEquals(File(root, "hmac.key").path, fromFile.get("keyRef").asString)
    }

    // ------------------------------------------------------------------ AES-GCM

    @Test fun aesGcmMatchesNistVectorsRoundTripsAndRejectsTampering() {
        // NIST GCM：K=0^256、IV=0^96、P=0^128 → C/T 为公开已知向量
        val zero = call("op" to "aes_gcm", "keyHex" to "00".repeat(32), "ivHex" to "00".repeat(12),
            "inputHex" to "00".repeat(16))
        assertEquals("cea7403d4d606b6e074ec5d3baf39d18d0d1c8a799996bf0265b98b5d48ab919", hex(zero))
        assertEquals("encrypt", zero.get("direction").asString)
        assertEquals(32, zero.get("bytes").asInt)
        assertEquals(12, zero.get("ivBytes").asInt)   // 传入的是 96-bit IV（"00".repeat(12)）

        // NIST GCM Test Case 4（AES-128 + 96-bit IV + AAD，明文 60 字节）
        val key = "feffe9928665731c6d6a8f9467308308"
        val iv = "cafebabefacedbaddecaf888"
        val aad = "feedfacedeadbeeffeedfacedeadbeefabaddad2"
        val plain = "d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a72" +
            "1c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b39"
        val expected = "42831ec2217774244b7221b784d0d49ce3aa212f2c02a4e035c17e2329aca12e" +
            "21d514b25466931c7d8f6a5aac84aa051ba30b396a0aac973d58e091" + "5bc94fbc3221a5db94fae95ae7121a47"
        val encrypted = call("op" to "aes_gcm", "alg" to "AES/GCM/NoPadding", "keyHex" to key, "ivHex" to iv,
            "aadHex" to aad, "inputHex" to plain)
        assertEquals(expected, hex(encrypted))
        assertEquals(60 + 16, encrypted.get("bytes").asInt)
        assertEquals(128, encrypted.get("tagLengthBits").asInt)

        // 解密往返：明文逐字节一致 + 明确 integrityVerified
        val decrypted = call("op" to "aes_gcm", "mode" to "decrypt", "keyHex" to key, "ivHex" to iv,
            "aadHex" to aad, "inputHex" to expected)
        assertEquals(plain, hex(decrypted))
        assertEquals("decrypt", decrypted.get("direction").asString)
        assertTrue(decrypted.get("integrityVerified").asBoolean)

        // tag 篡改必须 MAC_INVALID
        val tampered = expected.dropLast(2) + (if (expected.takeLast(2) == "47") "46" else "47")
        assertEquals("MAC_INVALID", failure("op" to "aes_gcm", "decrypt" to true, "keyHex" to key, "ivHex" to iv,
            "aadHex" to aad, "inputHex" to tampered).code)

        // AAD 不匹配同样 MAC_INVALID（AAD 参与 tag 计算）
        assertEquals("MAC_INVALID", failure("op" to "aes_gcm", "decrypt" to true, "keyHex" to key, "ivHex" to iv,
            "aadHex" to "0000", "inputHex" to expected).code)

        // 自定义 tagLengthBits 往返
        val shortTag = call("op" to "aes_gcm", "keyHex" to "11".repeat(16), "ivHex" to "22".repeat(12),
            "inputText" to "hello gcm", "tagLengthBits" to 96)
        val back = call("op" to "aes_gcm", "mode" to "decrypt", "keyHex" to "11".repeat(16), "ivHex" to "22".repeat(12),
            "inputHex" to hex(shortTag), "tagLengthBits" to 96)
        assertEquals("hello gcm", String(ToolkitIo.unhex(hex(back)), Charsets.UTF_8))
    }

    // ------------------------------------------------------------------ RSA

    @Test fun rsaSignVerifyTamperAndPemKeyFile() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val privateHex = ToolkitIo.hex(pair.private.encoded).lowercase()
        val publicHex = ToolkitIo.hex(pair.public.encoded).lowercase()

        val signed = call("op" to "rsa_sign", "keyHex" to privateHex, "inputText" to "sign me")
        val signature = hex(signed)
        assertEquals("SHA256withRSA", signed.get("alg").asString)
        assertEquals("private", signed.get("keyKind").asString)
        assertTrue(signature.isNotEmpty())

        // 同一私钥 hex 验签（重签后常量时间比较）与公钥验签都必须 valid
        val byPrivate = call("op" to "rsa_verify", "keyHex" to privateHex, "inputText" to "sign me", "signatureHex" to signature)
        assertTrue(byPrivate.get("valid").asBoolean)
        assertEquals("private_key_resign_compare", byPrivate.get("verificationMethod").asString)
        assertFalse("验签结果不写盘/不返回 outputHex", byPrivate.has("outputHex"))

        val byPublic = call("op" to "rsa_verify", "keyHex" to publicHex, "inputText" to "sign me", "signatureHex" to signature)
        assertTrue(byPublic.get("valid").asBoolean)
        assertEquals("public_key_verify", byPublic.get("verificationMethod").asString)

        // 签名篡改 → valid=false（工具本身仍然成功）
        val tampered = signature.dropLast(2) + (if (signature.takeLast(2) == "00") "01" else "00")
        assertFalse(call("op" to "rsa_verify", "keyHex" to publicHex, "inputText" to "sign me", "signatureHex" to tampered).get("valid").asBoolean)
        assertFalse(call("op" to "rsa_verify", "keyHex" to privateHex, "inputText" to "sign me", "signatureHex" to tampered).get("valid").asBoolean)

        // 输入被改也验不过
        assertFalse(call("op" to "rsa_verify", "keyHex" to publicHex, "inputText" to "sign ME", "signatureHex" to signature).get("valid").asBoolean)

        // 换一把公钥 → valid=false
        val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        assertFalse(call("op" to "rsa_verify", "keyHex" to ToolkitIo.hex(other.public.encoded).lowercase(),
            "inputText" to "sign me", "signatureHex" to signature).get("valid").asBoolean)

        // PEM 私钥文件（PKCS#8）走 keyRef
        File(root, "rsa.key").writeText(pem("PRIVATE KEY", pair.private.encoded), Charsets.US_ASCII)
        val pemSigned = call("op" to "rsa_sign", "keyRef" to "rsa.key", "inputText" to "sign me")
        assertEquals("file", pemSigned.get("keySource").asString)
        assertEquals(signature, hex(pemSigned))
    }

    // ------------------------------------------------------------------ 错误码

    @Test fun errorCodesAreDistinguishable() {
        assertEquals("ALG_UNSUPPORTED", failure("op" to "hash", "alg" to "SHA3-256", "inputText" to "abc").code)
        assertEquals("ALG_UNSUPPORTED", failure("op" to "aes_gcm", "alg" to "AES/CBC/PKCS5Padding",
            "keyHex" to "00".repeat(16), "ivHex" to "00".repeat(12), "inputText" to "x").code)
        assertEquals("ALG_UNSUPPORTED", failure("op" to "aes_gcm", "padding" to "PKCS5Padding",
            "keyHex" to "00".repeat(16), "ivHex" to "00".repeat(12), "inputText" to "x").code)
        assertEquals("ALG_UNSUPPORTED", failure("op" to "bogus", "inputText" to "x").code)

        // AES 密钥长度不符（15/17 字节）→ KEY_INVALID
        assertEquals("KEY_INVALID", failure("op" to "aes_gcm", "keyHex" to "00".repeat(15), "ivHex" to "00".repeat(12),
            "inputText" to "x").code)
        // HMAC 允许任意非空密钥（1 字节也合法，JCA 接受）→ 只拒绝空密钥
        assertEquals(1, call("op" to "hmac", "keyHex" to "00", "inputText" to "x").get("keyBytes").asInt)
        assertEquals("KEY_INVALID", failure("op" to "hmac", "keyHex" to "", "inputText" to "x").code)
        assertEquals("KEY_INVALID", failure("op" to "hmac", "inputText" to "x").code)
        assertEquals("KEY_INVALID", failure("op" to "hmac", "keyHex" to "zz", "inputText" to "x").code)
        // keyRef 越界 / 不是文件 → KEY_INVALID
        assertEquals("KEY_INVALID", failure("op" to "hmac", "keyRef" to "../outside.key", "inputText" to "x").code)
        assertEquals("KEY_INVALID", failure("op" to "hmac", "keyRef" to "missing.key", "inputText" to "x").code)
        // RSA 密钥格式不对
        assertEquals("KEY_INVALID", failure("op" to "rsa_sign", "keyHex" to "00112233", "inputText" to "x").code)

        // 解密失败（密文短于 tag）
        assertEquals("DECRYPT_FAILED", failure("op" to "aes_gcm", "decrypt" to true, "keyHex" to "00".repeat(16),
            "ivHex" to "00".repeat(12), "inputHex" to "0011").code)
        // 非法十六进制输入
        assertEquals("INVALID_ARGUMENT", failure("op" to "hash", "inputHex" to "abc").code)
        // 输入三选一
        assertEquals("INVALID_ARGUMENT", failure("op" to "hash", "inputHex" to "00", "inputText" to "x").code)
        assertEquals("INVALID_ARGUMENT", failure("op" to "hash", "inputHex" to "00", "path" to "x.txt").code)
        assertEquals("INVALID_ARGUMENT", failure("op" to "hash").code)
        // hash 不接受密钥/IV
        assertEquals("INVALID_ARGUMENT", failure("op" to "hash", "inputText" to "x", "keyHex" to "00").code)
        assertEquals("INVALID_ARGUMENT", failure("op" to "hash", "inputText" to "x", "ivHex" to "00").code)
        // 方向冲突 / 缺 iv / 缺签名
        assertEquals("INVALID_ARGUMENT", failure("op" to "aes_gcm", "mode" to "encrypt", "decrypt" to true,
            "keyHex" to "00".repeat(16), "ivHex" to "00".repeat(12), "inputText" to "x").code)
        assertEquals("KEY_INVALID", failure("op" to "aes_gcm", "keyHex" to "00".repeat(16), "inputText" to "x").code)
        assertEquals("INVALID_ARGUMENT", failure("op" to "rsa_verify", "keyHex" to "00", "inputText" to "x").code)
    }

    @Test fun inputTooLargeIsReportedBeforeReading() {
        val huge = File(root, "huge.bin")
        java.io.RandomAccessFile(huge, "rw").use { it.setLength(ToolkitCrypto.MAX_INPUT_BYTES.toLong() + 1L) }
        assertEquals("INPUT_TOO_LARGE", failure("op" to "hash", "path" to "huge.bin").code)
        assertEquals("INPUT_TOO_LARGE", failure("op" to "aes_gcm", "keyHex" to "00".repeat(16),
            "ivHex" to "00".repeat(12), "path" to "huge.bin").code)
    }

    // ------------------------------------------------------------------ 落盘与诚实边界

    @Test fun failureProducesNoFileAndSuccessWritesAtomically() {
        val out = File(root, "out.bin")
        // 1) 密钥非法：失败且不产出
        assertEquals("KEY_INVALID", failure("op" to "aes_gcm", "keyHex" to "00".repeat(15), "ivHex" to "00".repeat(12),
            "inputText" to "x", "outPath" to "out.bin").code)
        assertFalse("失败不能产出文件", out.exists())
        // 2) MAC 失败：同样不产出
        val encrypted = call("op" to "aes_gcm", "keyHex" to "33".repeat(16), "ivHex" to "44".repeat(12), "inputText" to "payload")
        val cipherHex = hex(encrypted)
        val tampered = cipherHex.dropLast(2) + (if (cipherHex.takeLast(2) == "00") "01" else "00")
        assertEquals("MAC_INVALID", failure("op" to "aes_gcm", "decrypt" to true, "keyHex" to "33".repeat(16),
            "ivHex" to "44".repeat(12), "inputHex" to tampered, "outPath" to "out.bin").code)
        assertFalse(out.exists())
        // 3) outPath 已存在 → 拒绝，不覆盖（organize=false 固定路径，才能断言具体文件）
        out.writeText("keep")
        assertEquals("INVALID_ARGUMENT", failure("op" to "hash", "inputText" to "x", "outPath" to "out.bin",
            "organize" to false).code)
        assertEquals("keep", out.readText())
        out.delete()
        // 4) 成功落盘：内容 == outputHex 解码、sha 自洽、written=true
        val ok = call("op" to "aes_gcm", "keyHex" to "33".repeat(16), "ivHex" to "44".repeat(12),
            "inputText" to "payload", "outPath" to "out.bin", "organize" to false)
        assertTrue(ok.get("written").asBoolean)
        assertEquals(out.path, ok.get("outputPath").asString)
        assertTrue(out.isFile)
        assertEquals(ToolkitIo.hex(out.readBytes()).lowercase(), hex(ok))
        assertEquals(ToolkitIo.sha(out), ok.get("outputSha256").asString)
    }

    @Test fun honestyFieldsAndToolSurfaceAreWired() {
        val result = call("op" to "hash", "inputText" to "abc")
        assertEquals("JCA", result.get("algorithmSource").asString)
        assertTrue(result.get("keyNote").asString.contains("不返回密钥内容"))
        val notes = result.getAsJsonArray("notes").map { it.asString }.joinToString(" ")
        assertTrue("必须写明不管理密钥", notes.contains("不管理密钥"))
        assertTrue("必须写明不做 KDF", notes.contains("KDF"))
        assertTrue(notes.contains("不读 App 私有密钥库"))

        assertTrue("crypto_ 必须注册在扩展工具里", ToolkitExtraTools.names.contains("crypto_"))
        val schemaFile = File("src/main/assets/mcp/toolkit-apk-schemas.json")
        assertTrue("找不到 " + schemaFile.absolutePath, schemaFile.isFile)
        val schemas = JsonParser().parse(schemaFile.readText()).asJsonObject
        val schema = schemas.getAsJsonObject("crypto_")
        assertNotNull("crypto_ 必须写进 toolkit-apk-schemas.json（客户端据此发现参数）", schema)
        assertEquals(listOf("op"), schema.getAsJsonArray("required").map { it.asString })
        assertTrue(schema.getAsJsonObject("properties").has("keyRef"))
        assertTrue(schema.getAsJsonObject("properties").getAsJsonObject("op").getAsJsonArray("enum").size() == 5)
    }

    private fun pem(label: String, der: ByteArray): String {
        val base64 = java.util.Base64.getEncoder().encodeToString(der)
        return "-----BEGIN " + label + "-----\n" + base64.chunked(64).joinToString("\n") + "\n-----END " + label + "-----\n"
    }
}
