package com.jdkshen.aijspro.mcp

import com.google.gson.JsonObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.zip.CRC32
import java.util.zip.ZipFile

/** Metadata reader, deliberately not an APK signature verifier.
 * Layout: https://source.android.com/docs/security/features/apksigning/v2
 * V3/V3.1 IDs: AOSP tools/apksig internal/apk/v3/V3SchemeConstants.java.
 */
internal object ToolkitSignatures {
    private const val MAX_BLOCK = 64 * 1024 * 1024
    private val ids = mapOf(0x7109871a to "V2", 0xf05368c0.toInt() to "V3", 0x1b93ad61 to "V3.1")
    private data class Certificate(val cert: X509Certificate, val schemes: MutableSet<String>)

    fun read(apk: File): JsonObject {
        val certs = linkedMapOf<String, Certificate>()
        val schemes = linkedSetOf<String>()
        val warnings = arrayListOf<String>()
        fun add(bytes: ByteArray, scheme: String) {
            require(bytes.size in 1..(4 * 1024 * 1024)) { "签名证书长度无效" }
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
            val hash = hash("SHA-256", cert.encoded)
            certs.getOrPut(hash) { Certificate(cert, linkedSetOf()) }.schemes.add(scheme)
        }
        ZipFile(apk).use { zip ->
            val entries = zip.entries().asSequence().filter { e ->
                e.name.startsWith("META-INF/", true) && listOf(".RSA", ".DSA", ".EC").any { e.name.endsWith(it, true) }
            }.toList()
            require(entries.size <= 64) { "签名证书条目过多" }
            entries.forEach { entry ->
                schemes.add("V1")
                try {
                    val bytes = ToolkitIo.read(zip.getInputStream(entry), 4 * 1024 * 1024)
                    CertificateFactory.getInstance("X.509").generateCertificates(ByteArrayInputStream(bytes))
                        .forEach { add(it.encoded, "V1") }
                } catch (failure: Exception) { warnings.add("V1 ${entry.name}: ${failure.message}") }
            }
        }
        try {
            signingBlocks(apk).forEach { (id, payload) ->
                val scheme = ids[id] ?: return@forEach
                schemes.add(scheme)
                try {
                    val signers = sized(buffer(payload))
                    var count = 0
                    while (signers.hasRemaining()) {
                        require(++count <= 64) { "签名者数量过多" }
                        val signer = sized(signers)
                        val signedData = sized(signer)
                        sized(signedData) // content digests, not verified here
                        val certificates = sized(signedData)
                        var chain = 0
                        while (certificates.hasRemaining()) {
                            require(++chain <= 64) { "证书链过长" }
                            val value = sized(certificates)
                            add(ByteArray(value.remaining()).also { value.get(it) }, scheme)
                        }
                    }
                } catch (failure: Exception) { warnings.add("$scheme: ${failure.message}") }
            }
        } catch (failure: Exception) { warnings.add("APK Signing Block: ${failure.message}") }
        val identities = schemes.map { scheme -> certs.filterValues { scheme in it.schemes }.keys.toSet() }
        val certificates = certs.values.map { value ->
            val cert = value.cert; val bytes = cert.encoded
            ToolkitIo.json("schemes" to value.schemes, "sizeBytes" to bytes.size, "hashCode" to cert.hashCode(),
                "md5" to hash("MD5", bytes), "sha1" to hash("SHA-1", bytes), "sha256" to hash("SHA-256", bytes),
                "crc32" to CRC32().apply { update(bytes) }.value.toString(16).padStart(8, '0'),
                "certificateSignatureAlgorithm" to cert.sigAlgName, "certificateSignatureAlgorithmOid" to cert.sigAlgOID,
                "subject" to cert.subjectX500Principal.name, "issuer" to cert.issuerX500Principal.name,
                "serialNumberHex" to cert.serialNumber.toString(16), "notBeforeEpochMillis" to cert.notBefore.time,
                "notAfterEpochMillis" to cert.notAfter.time,
                // Existing clients may still read the original short field names.
                "algorithm" to cert.sigAlgName, "notBefore" to cert.notBefore.time, "notAfter" to cert.notAfter.time)
        }
        return ToolkitIo.json("available" to warnings.isEmpty(), "signaturePresent" to schemes.isNotEmpty(),
            "schemes" to schemes, "hasDifferentCertificates" to (identities.distinct().size > 1),
            "certificates" to certificates, "error" to warnings.takeIf { it.isNotEmpty() }?.joinToString("; "),
            "warnings" to warnings, "verified" to false, "coverage" to "V1/V2/V3/V3.1 certificate metadata")
    }

    private fun hash(algorithm: String, bytes: ByteArray) = ToolkitIo.hex(MessageDigest.getInstance(algorithm).digest(bytes)).lowercase()
    private fun buffer(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    private fun sized(source: ByteBuffer): ByteBuffer {
        require(source.remaining() >= 4) { "签名字段长度缺失" }
        val count = source.int.toLong() and 0xffffffffL
        require(count <= source.remaining() && count <= MAX_BLOCK) { "签名字段越界" }
        val slice = source.slice().order(ByteOrder.LITTLE_ENDIAN); slice.limit(count.toInt())
        source.position(source.position() + count.toInt()); return slice
    }
    private fun signingBlocks(apk: File): Map<Int, ByteArray> = RandomAccessFile(apk, "r").use { file ->
        require(file.length() >= 22) { "ZIP 尾记录缺失" }
        val tail = ByteArray(minOf(file.length(), 65557L).toInt())
        file.seek(file.length() - tail.size); file.readFully(tail)
        val bytes = buffer(tail)
        val eocd = (tail.size - 22 downTo 0).firstOrNull { at ->
            bytes.getInt(at) == 0x06054b50 && at + 22 + (bytes.getShort(at + 20).toInt() and 65535) == tail.size
        } ?: error("ZIP 尾记录无效")
        require(bytes.getShort(eocd + 4).toInt() == 0 && bytes.getShort(eocd + 6).toInt() == 0) { "不支持分卷 APK" }
        val directory = bytes.getInt(eocd + 16).toLong() and 0xffffffffL
        require(directory != 0xffffffffL) { "APK Signing Block 不支持 ZIP64 偏移" }
        val absoluteEocd = file.length() - tail.size + eocd
        require(directory <= absoluteEocd) { "ZIP 中央目录偏移越界" }
        if (directory < 24) return@use emptyMap()
        val footer = ByteArray(24); file.seek(directory - 24); file.readFully(footer)
        if (!footer.copyOfRange(8, 24).contentEquals("APK Sig Block 42".toByteArray(Charsets.US_ASCII))) return@use emptyMap()
        val size = buffer(footer).long
        require(size in 24..MAX_BLOCK.toLong() && size <= directory - 8) { "签名块长度无效" }
        val start = directory - size - 8
        file.seek(start); val block = ByteArray((size + 8).toInt()); file.readFully(block)
        val data = buffer(block); require(data.long == size) { "签名块头尾长度不匹配" }
        data.limit(block.size - 24)
        val result = linkedMapOf<Int, ByteArray>()
        var count = 0
        while (data.hasRemaining()) {
            require(++count <= 4096 && data.remaining() >= 8) { "签名块条目无效" }
            val length = data.long
            require(length >= 4 && length <= data.remaining()) { "签名块条目越界" }
            val id = data.int; val valueSize = (length - 4).toInt()
            if (id in ids) {
                require(id !in result) { "重复签名方案块" }
                result[id] = ByteArray(valueSize).also { data.get(it) }
            } else data.position(data.position() + valueSize)
        }
        result
    }
}
