package com.jdkshen.aijspro.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * crypto_（现场加解密）：hash / HMAC / AES-GCM / RSA 签验。
 *
 * 边界（同时写进返回的 notes）：
 * - 只做**通用密码学原语**（JCA 实现，algorithmSource=JCA + 实际 provider 名称），
 *   **不管理密钥**、不做 KDF/派生、不实现自定义算法；
 * - 密钥来源只允许显式 keyHex 或**工具目录内**的 keyRef 文件（走 ToolkitIo.resolve 边界），
 *   绝不读 App 私有密钥库/签名身份，也绝不把密钥内容写进返回（只回显长度与 SHA-256 前缀）；
 * - 失败不产出半成品：所有校验/运算都在写盘之前，outPath 只在成功后原子写；
 * - 不声称「已解密成功」：GCM 解密通过说明 JCA 的 tag 校验通过；明文是否有意义由调用方判断。
 */
internal object ToolkitCrypto {

    /** 输入上限（与其它工具一致：64 MiB）；hash/hmac 流式读，其它 op 需要整块内存。 */
    const val MAX_INPUT_BYTES = ToolkitIo.MAX_ENTRY
    const val MAX_KEY_BYTES = 64 * 1024
    const val MAX_OUTPUT_HEX_BYTES = 64 * 1024
    const val DIGEST_FOR_INPUT = "SHA-256"

    private const val GCM_ALG = "AES/GCM/NoPadding"
    private val AES_KEY_SIZES = setOf(16, 24, 32)
    private val HASH_ALGS = listOf("MD5", "SHA-1", "SHA-256", "SHA-384", "SHA-512")
    private val HMAC_ALGS = listOf("HmacMD5", "HmacSHA1", "HmacSHA256", "HmacSHA384", "HmacSHA512")
    private val SIGN_ALGS = listOf("MD5withRSA", "SHA1withRSA", "SHA256withRSA", "SHA384withRSA", "SHA512withRSA")

    fun call(root: File, store: File?, args: JsonObject): JsonObject = when (val op = args.tString("op").trim().lowercase()) {
        "hash" -> hash(root, store, args)
        "hmac" -> hmac(root, store, args)
        "aes_gcm" -> aesGcm(root, store, args)
        "rsa_sign" -> rsaSign(root, store, args)
        "rsa_verify" -> rsaVerify(root, store, args)
        else -> throw ToolkitException("ALG_UNSUPPORTED",
            "不支持的 op=$op（支持 hash/hmac/aes_gcm/rsa_sign/rsa_verify）", "op")
    }

    // ------------------------------------------------------------------ hash / hmac

    private fun hash(root: File, store: File?, args: JsonObject): JsonObject {
        rejectKeys(args, "hash")
        val alg = normalizeAlg(args.tString("alg", ""), HASH_ALGS, "SHA-256")
        val input = resolveInput(root, args)
        val digest = guard("ALG_UNSUPPORTED", "alg") { MessageDigest.getInstance(alg) }
        val provider = digest.provider.name
        val inputDigest = MessageDigest.getInstance(DIGEST_FOR_INPUT)
        stream(input) { buffer, read -> digest.update(buffer, 0, read); inputDigest.update(buffer, 0, read) }
        val output = digest.digest()
        val result = base("hash", alg, provider, null, input, args)
        result.add("digestOfInput", digestJson(inputDigest.digest()))
        return finish(root, store, args, result, output, "hash")
    }

    private fun hmac(root: File, store: File?, args: JsonObject): JsonObject {
        val alg = normalizeAlg(args.tString("alg", ""), HMAC_ALGS, "HmacSHA256")
        val key = requireKey(root, args)
        if (key.bytes.isEmpty()) throw ToolkitException("KEY_INVALID", "HMAC 密钥不能为空", key.argument)
        val input = resolveInput(root, args)
        val mac = guard("ALG_UNSUPPORTED", "alg") { Mac.getInstance(alg) }
        val provider = mac.provider.name
        guard("KEY_INVALID", key.argument) { mac.init(SecretKeySpec(key.bytes, alg)) }
        val inputDigest = MessageDigest.getInstance(DIGEST_FOR_INPUT)
        stream(input) { buffer, read -> mac.update(buffer, 0, read); inputDigest.update(buffer, 0, read) }
        val output = guard("ALG_UNSUPPORTED", "alg") { mac.doFinal() }
        val result = base("hmac", alg, provider, key, input, args)
        result.add("digestOfInput", digestJson(inputDigest.digest()))
        return finish(root, store, args, result, output, "hmac")
    }

    // ------------------------------------------------------------------ AES-GCM

    private fun aesGcm(root: File, store: File?, args: JsonObject): JsonObject {
        val requested = args.tString("alg", GCM_ALG).trim()
        if (requested.replace(" ", "").lowercase() != "aes/gcm/nopadding") {
            throw ToolkitException("ALG_UNSUPPORTED", "aes_gcm 只支持 alg=$GCM_ALG（当前=$requested）", "alg")
        }
        val padding = args.tString("padding", "").trim()
        if (padding.isNotEmpty() && !padding.equals("NoPadding", true)) {
            throw ToolkitException("ALG_UNSUPPORTED", "GCM 必须 NoPadding（当前 padding=$padding）", "padding")
        }
        val key = requireKey(root, args)
        if (key.bytes.size !in AES_KEY_SIZES) {
            throw ToolkitException("KEY_INVALID", "AES 密钥必须是 16/24/32 字节（当前 " + key.bytes.size + " 字节）", key.argument)
        }
        val ivHex = args.tString("ivHex", "").trim()
        if (ivHex.isEmpty()) throw ToolkitException("KEY_INVALID", "aes_gcm 需要 ivHex（GCM nonce，推荐 12 字节）", "ivHex")
        val iv = hexBytes(ivHex, "KEY_INVALID", "ivHex")
        if (iv.isEmpty() || iv.size > 16) throw ToolkitException("KEY_INVALID", "ivHex 必须是 1..16 字节（推荐 12）", "ivHex")
        val aad = args.tString("aadHex", "").trim().takeIf { it.isNotEmpty() }?.let { hexBytes(it, "INVALID_ARGUMENT", "aadHex") }
        val tagBits = args.tInt("tagLengthBits", 128, 96, 128)
        if (tagBits % 8 != 0) throw ToolkitException("INVALID_ARGUMENT", "tagLengthBits 必须是 8 的倍数", "tagLengthBits")
        val direction = direction(args)
        val input = resolveInput(root, args)
        val payload = input.materialize()
        val cipher = guard("ALG_UNSUPPORTED", "alg") { Cipher.getInstance(GCM_ALG) }
        val provider = cipher.provider.name
        val output = guard("DECRYPT_FAILED", "inputHex") {
            val spec = GCMParameterSpec(tagBits, iv)
            if (direction == "encrypt") {
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.bytes, "AES"), spec)
                if (aad != null) cipher.updateAAD(aad)
                cipher.doFinal(payload)
            } else {
                if (payload.size < tagBits / 8) throw ToolkitException("DECRYPT_FAILED",
                    "密文长度 " + payload.size + " 字节小于 tag 长度 " + (tagBits / 8) + " 字节", "inputHex")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.bytes, "AES"), spec)
                if (aad != null) cipher.updateAAD(aad)
                cipher.doFinal(payload)
            }
        }
        val result = base("aes_gcm", GCM_ALG, provider, key, input, args)
        result.addProperty("direction", direction)
        result.addProperty("ivBytes", iv.size)
        result.addProperty("ivLengthNote", if (iv.size == 12) "12 字节 GCM nonce（推荐）" else "非 12 字节 IV：GCM 允许但非推荐长度，请确认与加密方一致")
        result.addProperty("tagLengthBits", tagBits)
        result.addProperty("aadBytes", aad?.size ?: 0)
        result.add("digestOfInput", digestJson(inputDigestOf(payload)))
        if (direction == "decrypt") {
            // JCA 的 GCM 解密会校验 tag：能走到这里说明 tag 匹配（否则上面已按 MAC_INVALID 抛出）。
            result.addProperty("integrityVerified", true)
            result.addProperty("integrityNote", "GCM tag 已由 JCA 校验通过；这不代表明文语义正确，也不构成对加密方的信任判断。")
        }
        return finish(root, store, args, result, output, if (direction == "decrypt") "aes_gcm_decrypt" else "aes_gcm_encrypt")
    }

    // ------------------------------------------------------------------ RSA

    private fun rsaSign(root: File, store: File?, args: JsonObject): JsonObject {
        val alg = normalizeAlg(args.tString("alg", ""), SIGN_ALGS, "SHA256withRSA")
        val key = requireKey(root, args)
        val privateKey = parsePrivateKey(key)
        val input = resolveInput(root, args)
        val payload = input.materialize()
        val signature = guard("ALG_UNSUPPORTED", "alg") { signBytes(privateKey, alg, payload) }
        val provider = guard("ALG_UNSUPPORTED", "alg") { Signature.getInstance(alg).provider.name }
        val result = base("rsa_sign", alg, provider, key, input, args)
        result.addProperty("keyKind", "private")
        result.add("digestOfInput", digestJson(inputDigestOf(payload)))
        result.addProperty("signatureNote", "RSA PKCS#1 v1.5 签名（$alg）；签名只对本次输入与密钥有效，不含时间戳/证书链。")
        return finish(root, store, args, result, signature, "rsa_sign")
    }

    private fun rsaVerify(root: File, store: File?, args: JsonObject): JsonObject {
        val alg = normalizeAlg(args.tString("alg", ""), SIGN_ALGS, "SHA256withRSA")
        val signatureHex = args.tString("signatureHex", "").trim()
        if (signatureHex.isEmpty()) throw ToolkitException("INVALID_ARGUMENT", "rsa_verify 需要 signatureHex", "signatureHex")
        val signature = hexBytes(signatureHex, "INVALID_ARGUMENT", "signatureHex")
        val key = requireKey(root, args)
        val input = resolveInput(root, args)
        val payload = input.materialize()
        val publicKey = parsePublicKeyOrNull(key.bytes)
        val method: String
        var verificationError: String? = null
        val valid: Boolean
        val provider: String
        if (publicKey != null) {
            method = "public_key_verify"
            val verifier = guard("ALG_UNSUPPORTED", "alg") { Signature.getInstance(alg) }
            provider = verifier.provider.name
            guard("KEY_INVALID", key.argument) { verifier.initVerify(publicKey); verifier.update(payload) }
            valid = runCatching { verifier.verify(signature) }.getOrElse { failure ->
                verificationError = failure.message ?: failure.javaClass.simpleName
                false
            }
        } else {
            // 只有私钥时用「重签 + 常量时间比较」（PKCS#1 v1.5 是确定性的）；PSS 不适用（本工具不支持 PSS）。
            val privateKey = parsePrivateKey(key)
            method = "private_key_resign_compare"
            val resigner = guard("ALG_UNSUPPORTED", "alg") { Signature.getInstance(alg) }
            provider = resigner.provider.name
            val resigned = guard("ALG_UNSUPPORTED", "alg") { signBytes(privateKey, alg, payload) }
            valid = MessageDigest.isEqual(resigned, signature)
        }
        val result = base("rsa_verify", alg, provider, key, input, args)
        result.addProperty("keyKind", if (publicKey != null) "public" else "private")
        result.addProperty("valid", valid)
        result.addProperty("verificationMethod", method)
        result.addProperty("verificationNote", if (publicKey != null)
            "用给出的公钥验证；valid=false 表示签名与输入/公钥不匹配（工具本身执行成功）。" else
            "给出的是私钥：用同一密钥重签后逐字节比较（PKCS#1 v1.5 确定性）；PSS 不适用，本工具不支持 PSS。")
        result.addProperty("signatureBytesGiven", signature.size)
        if (verificationError != null) result.addProperty("verificationError", verificationError)
        result.add("digestOfInput", digestJson(inputDigestOf(payload)))
        result.add("notes", notes("rsa_verify"))
        return result
    }

    /** 用私钥签名（抽出来给 rsa_sign 与「私钥验签」共用）。 */
    private fun signBytes(privateKey: PrivateKey, alg: String, payload: ByteArray): ByteArray {
        val signer = Signature.getInstance(alg)
        signer.initSign(privateKey)
        signer.update(payload)
        return signer.sign()
    }

    // ------------------------------------------------------------------ 密钥

    private class KeyMaterial(val bytes: ByteArray, val source: String, val ref: String?) {
        val argument: String get() = if (source == "file") "keyRef" else "keyHex"
    }

    private fun requireKey(root: File, args: JsonObject): KeyMaterial {
        val hasHex = args.has("keyHex")
        val hasRef = args.has("keyRef")
        if (hasHex && hasRef) throw ToolkitException("INVALID_ARGUMENT", "keyHex 与 keyRef 只能给一个", "keyHex")
        if (!hasHex && !hasRef) throw ToolkitException("KEY_INVALID",
            "该 op 需要密钥：显式 keyHex，或工具目录内的 keyRef 文件；本工具不读 App 私有密钥库/签名身份", "keyRef")
        if (hasHex) return KeyMaterial(hexBytes(args.tString("keyHex"), "KEY_INVALID", "keyHex"), "explicit", null)
        val ref = args.tString("keyRef").trim()
        val file = try {
            ToolkitIo.resolve(root, ref)
        } catch (failure: IllegalArgumentException) {
            throw ToolkitException("KEY_INVALID", "keyRef 必须在工具目录内：" + (failure.message ?: ref), "keyRef")
        }
        if (!file.isFile) throw ToolkitException("KEY_INVALID", "keyRef 不是文件：" + file.path, "keyRef")
        if (file.length() > MAX_KEY_BYTES) throw ToolkitException("KEY_INVALID", "密钥文件超过 " + MAX_KEY_BYTES + " 字节", "keyRef")
        return KeyMaterial(file.readBytes(), "file", file.path)
    }

    private fun parsePrivateKey(key: KeyMaterial): PrivateKey {
        val der = derBytes(key.bytes, key.argument)
        val factory = guard("ALG_UNSUPPORTED", key.argument) { KeyFactory.getInstance("RSA") }
        runCatching { factory.generatePrivate(PKCS8EncodedKeySpec(der)) }.getOrNull()?.let { return it }
        runCatching { factory.generatePrivate(PKCS8EncodedKeySpec(pkcs1ToPkcs8(der))) }.getOrNull()?.let { return it }
        throw ToolkitException("KEY_INVALID",
            "无法解析 RSA 私钥：需要 PKCS#8（BEGIN PRIVATE KEY）或 PKCS#1（BEGIN RSA PRIVATE KEY）的 DER/PEM；" +
                "本工具不接受 App 私有密钥库/签名身份，也不支持口令加密的密钥", key.argument)
    }

    private fun parsePublicKeyOrNull(raw: ByteArray): PublicKey? {
        val der = runCatching { derBytes(raw, "keyHex") }.getOrNull() ?: return null
        runCatching { KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der)) }.getOrNull()?.let { return it }
        // 也接受 X.509 证书（DER 或 PEM）：直接取证书里的公钥
        runCatching {
            CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(raw)).publicKey
        }.getOrNull()?.let { key ->
            if (key.algorithm.equals("RSA", true)) return key
            throw ToolkitException("ALG_UNSUPPORTED", "只支持 RSA 密钥/签名（当前公钥算法=" + key.algorithm + "）", "keyHex")
        }
        return null
    }

    /** PEM（-----BEGIN ...-----）→ DER；纯 DER 原样返回。 */
    private fun derBytes(raw: ByteArray, argument: String): ByteArray {
        val text = String(raw, Charsets.US_ASCII)
        if (!text.contains("-----BEGIN")) return raw
        if (text.contains("ENCRYPTED")) throw ToolkitException("KEY_INVALID", "不支持口令加密的 PEM 密钥；请先解密导出", argument)
        val body = text.lineSequence().filter { !it.contains("-----") }.joinToString("") { it.trim() }
        return try {
            java.util.Base64.getDecoder().decode(body)
        } catch (failure: IllegalArgumentException) {
            throw ToolkitException("KEY_INVALID", "PEM 内容 Base64 解码失败：" + (failure.message ?: ""), argument)
        }
    }

    /** PKCS#1 RSAPrivateKey → PKCS#8 PrivateKeyInfo（只包一层固定头，不做完整 ASN.1 解析）。 */
    private fun pkcs1ToPkcs8(pkcs1: ByteArray): ByteArray {
        val version = byteArrayOf(0x02, 0x01, 0x00)
        val rsaAlgorithmId = byteArrayOf(0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(),
            0xf7.toByte(), 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00)
        return derTlv(0x30, version + rsaAlgorithmId + derTlv(0x04, pkcs1))
    }

    private fun derTlv(tag: Int, content: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(tag)
        val length = content.size
        when {
            length < 0x80 -> out.write(length)
            length <= 0xFF -> { out.write(0x81); out.write(length) }
            length <= 0xFFFF -> { out.write(0x82); out.write(length ushr 8); out.write(length and 0xFF) }
            else -> { out.write(0x83); out.write(length ushr 16); out.write((length ushr 8) and 0xFF); out.write(length and 0xFF) }
        }
        out.write(content)
        return out.toByteArray()
    }

    // ------------------------------------------------------------------ 输入

    private class Input(val label: String, val path: String?, private val file: File?, private val bytes: ByteArray?) {
        val length: Long get() = bytes?.size?.toLong() ?: (file?.length() ?: 0L)
        fun materialize(): ByteArray = bytes ?: file!!.readBytes()
        fun open(): InputStream = if (bytes != null) ByteArrayInputStream(bytes) else file!!.inputStream()
    }

    private fun resolveInput(root: File, args: JsonObject): Input {
        val present = listOf("inputHex", "inputText", "path").filter { args.has(it) }
        if (present.size != 1) throw ToolkitException("INVALID_ARGUMENT",
            "inputHex / inputText / path 必须三选一（当前给了 " + present.size + " 个：" + present.joinToString(",") + "）", "inputHex")
        return when (present[0]) {
            "inputHex" -> Input("inputHex", null, null, hexBytes(args.tString("inputHex"), "INVALID_ARGUMENT", "inputHex"))
            "inputText" -> Input("inputText", null, null, args.tString("inputText").toByteArray(Charsets.UTF_8))
            else -> {
                val file = try {
                    ToolkitIo.resolve(root, args.tString("path"))
                } catch (failure: IllegalArgumentException) {
                    throw ToolkitException("INVALID_ARGUMENT", "path 必须在工具目录内：" + (failure.message ?: ""), "path")
                }
                if (!file.isFile) throw ToolkitException("INVALID_ARGUMENT", "path 不是文件：" + file.path, "path")
                if (file.length() > MAX_INPUT_BYTES) throw ToolkitException("INPUT_TOO_LARGE",
                    "输入 " + file.length() + " 字节超过上限 " + MAX_INPUT_BYTES + " 字节", "path")
                Input("path", file.path, file, null)
            }
        }
    }

    /** hash/hmac 用流式读完（不整块进内存）；其它 op 走 materialize()。 */
    private inline fun stream(input: Input, block: (ByteArray, Int) -> Unit) {
        input.open().use { source ->
            val buffer = ByteArray(65536)
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                block(buffer, read)
            }
        }
    }

    /** 输入摘要（非流式 op 用；hash/hmac 在流式循环里同步算）。 */
    private fun inputDigestOf(payload: ByteArray): ByteArray =
        MessageDigest.getInstance(DIGEST_FOR_INPUT).digest(payload)

    private fun digestJson(bytes: ByteArray): JsonObject =
        ToolkitIo.json("alg" to DIGEST_FOR_INPUT, "hex" to ToolkitIo.hex(bytes).lowercase())

    // ------------------------------------------------------------------ 公共装配

    private fun base(op: String, alg: String, provider: String, key: KeyMaterial?, input: Input, args: JsonObject): JsonObject =
        ToolkitIo.json(
            "op" to op,
            "alg" to alg,
            "algorithmSource" to "JCA",
            "provider" to provider,
            "inputSource" to input.label,
            "inputPath" to input.path,
            "inputBytes" to input.length,
            "keySource" to (key?.source ?: "none"),
            "keyRef" to key?.ref,
            "keyBytes" to (key?.bytes?.size ?: 0),
            "keySha256Prefix" to key?.let { ToolkitIo.version(it.bytes).take(12) },
            "keyNote" to "密钥只回显长度与 SHA-256 前缀：不返回密钥内容，也绝不读取 App 私有密钥库/签名身份。",
            "organizeRequested" to args.tBool("organize", true),
        )

    private fun finish(root: File, store: File?, args: JsonObject, result: JsonObject, output: ByteArray, op: String): JsonObject {
        result.addProperty("bytes", output.size)
        if (output.size <= MAX_OUTPUT_HEX_BYTES) {
            result.addProperty("outputHex", ToolkitIo.hex(output).lowercase())
        } else {
            result.add("outputHexOmitted", ToolkitIo.json("reason" to "输出超过内联十六进制上限", "bytes" to output.size,
                "limitBytes" to MAX_OUTPUT_HEX_BYTES, "hint" to "传 outPath 落盘后用 file_read_bytes/file_stat 读取"))
        }
        val outPath = args.tString("outPath", "").trim()
        if (outPath.isEmpty()) {
            result.addProperty("written", false)
            result.addProperty("writeNote", "未提供 outPath：只返回 outputHex（超过 " + MAX_OUTPUT_HEX_BYTES + " 字节时只提示 outputHexOmitted），不落盘")
        } else {
            // 所有校验/运算都已完成：到这里才写盘，失败不会留下半成品。
            val requested = ToolkitIo.resolve(root, outPath)
            val (destination, organized) = ToolkitExtraTools.organizeOutput(root, store, requested, args.tBool("organize", true))
            if (destination.exists()) throw ToolkitException("INVALID_ARGUMENT", "outPath 已存在，请使用新文件名：" + destination.path, "outPath")
            if (destination.parentFile?.isDirectory != true) throw ToolkitException("INVALID_ARGUMENT", "outPath 的父目录必须存在：" + destination.path, "outPath")
            ToolkitIo.atomic(destination) { temp -> temp.writeBytes(output) }
            result.addProperty("outputPath", destination.path)
            result.addProperty("outputSha256", ToolkitIo.sha(destination))
            result.addProperty("written", true)
            if (organized != null) result.add("organized", organized)
        }
        result.add("notes", notes(op))
        return result
    }

    private fun notes(op: String): JsonArray = JsonArray().apply {
        add("通用密码学原语（JCA，algorithmSource=JCA + provider 为实际实例）：不管理密钥、不做 KDF/密钥派生、不实现用户自定义算法。")
        add("密钥只能显式给（keyHex）或放在工具目录内（keyRef）；本工具不读 App 私有密钥库/签名身份，也不返回密钥内容。")
        add("成功只代表 JCA 在该密钥/参数下完成了运算，不构成对密钥持有者、证书链或数据来源的信任判断。")
        if (op == "aes_gcm_encrypt") add("GCM 加密输出的最后 tagLengthBits/8 字节是认证标签，解密方必须原样提供。")
        if (op == "aes_gcm_decrypt") add("GCM 解密由 JCA 校验 tag：不匹配直接 MAC_INVALID，不产出任何明文/文件；通过也不代表明文语义正确。")
        if (op == "rsa_verify") add("验签失败不算工具失败：返回 valid=false；只有 key/alg/参数问题才报错。")
        add("失败不产出半成品：所有校验/运算在写盘之前完成，outPath 只在成功后原子写。")
    }

    // ------------------------------------------------------------------ 小工具

    /** hash 不接受密钥参数：显式拒绝而不是静默忽略。 */
    private fun rejectKeys(args: JsonObject, op: String) {
        if (args.has("keyHex") || args.has("keyRef")) {
            throw ToolkitException("INVALID_ARGUMENT", op + " 不接受密钥（keyHex/keyRef）；HMAC 请用 op=hmac", "keyHex")
        }
        for (unused in listOf("ivHex", "aadHex", "signatureHex", "tagLengthBits")) {
            if (args.has(unused)) throw ToolkitException("INVALID_ARGUMENT",
                op + " 不接受 " + unused + "（该参数只对 aes_gcm/rsa_verify 有意义）", unused)
        }
    }

    private fun normalizeAlg(requested: String, supported: List<String>, default: String): String {
        val text = requested.trim()
        if (text.isEmpty()) return default
        val match = supported.firstOrNull { it.equals(text, true) || it.replace("-", "").equals(text.replace("-", ""), true) }
        return match ?: throw ToolkitException("ALG_UNSUPPORTED",
            "不支持的 alg=" + text + "（支持 " + supported.joinToString(", ") + "）", "alg")
    }

    private fun direction(args: JsonObject): String {
        val mode = args.tString("mode", "").trim().lowercase()
        val decrypt = args.tBool("decrypt", false)
        if (mode.isEmpty()) return if (decrypt) "decrypt" else "encrypt"
        if (mode != "encrypt" && mode != "decrypt") throw ToolkitException("INVALID_ARGUMENT", "mode 只能是 encrypt 或 decrypt", "mode")
        if (args.has("decrypt") && ((mode == "decrypt") != decrypt)) {
            throw ToolkitException("INVALID_ARGUMENT", "mode=" + mode + " 与 decrypt=" + decrypt + " 冲突：只给一个", "mode")
        }
        return mode
    }

    private fun hexBytes(value: String, code: String, argument: String): ByteArray = try {
        ToolkitIo.unhex(value.trim())
    } catch (failure: IllegalArgumentException) {
        throw ToolkitException(code, argument + " 不是合法十六进制（长度 " + value.trim().length + "）：" + (failure.message ?: ""), argument)
    }

    /** 把 JCA 的受检异常映射到本工具的五个错误码；ToolkitException 原样透传。 */
    private inline fun <T> guard(code: String, argument: String?, body: () -> T): T = try {
        body()
    } catch (failure: ToolkitException) {
        throw failure
    } catch (failure: java.security.NoSuchAlgorithmException) {
        throw ToolkitException("ALG_UNSUPPORTED", "JCA 不支持该算法：" + (failure.message ?: ""), argument)
    } catch (failure: javax.crypto.AEADBadTagException) {
        throw ToolkitException("MAC_INVALID", "GCM tag 校验失败：密文/AAD/IV/密钥不匹配（这不是「已解密成功」）", argument)
    } catch (failure: javax.crypto.BadPaddingException) {
        throw ToolkitException("DECRYPT_FAILED", "解密失败（padding 校验不通过）：" + (failure.message ?: ""), argument)
    } catch (failure: javax.crypto.IllegalBlockSizeException) {
        throw ToolkitException("DECRYPT_FAILED", "密文长度不合法：" + (failure.message ?: ""), argument)
    } catch (failure: java.security.InvalidKeyException) {
        throw ToolkitException("KEY_INVALID", "密钥不被 JCA 接受：" + (failure.message ?: ""), argument)
    } catch (failure: java.security.spec.InvalidKeySpecException) {
        throw ToolkitException("KEY_INVALID", "密钥格式不被 JCA 接受：" + (failure.message ?: ""), argument)
    } catch (failure: java.security.SignatureException) {
        throw ToolkitException("ALG_UNSUPPORTED", "签名运算失败：" + (failure.message ?: ""), argument)
    } catch (failure: java.security.GeneralSecurityException) {
        throw ToolkitException(code, "JCA 操作失败：" + (failure.message ?: failure.javaClass.simpleName), argument)
    }
}