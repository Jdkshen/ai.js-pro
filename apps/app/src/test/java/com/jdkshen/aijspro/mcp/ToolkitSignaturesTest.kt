package com.jdkshen.aijspro.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Signature reading is metadata extraction, not cryptographic verification. The fixture therefore
 * uses a disposable public test certificate and a syntactically valid APK Signing Block; it never
 * contains or reads a private key. V2/V3/V3.1 use real scheme IDs and v3-shaped signed data, but the
 * test asserts only the metadata fields the MCP contract exposes.
 */
class ToolkitSignaturesTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun readsV2V3AndV31CertificateMetadata() {
        val certificate = Base64.getDecoder().decode(TEST_CERTIFICATE_DER)
        val apk = temp.newFile("v2-v3-v31.apk")
        apk.writeBytes(apkWithSigningBlock(certificate))

        val signature = ToolkitSignatures.read(apk)
        assertEquals(setOf("V2", "V3", "V3.1"), signature.getAsJsonArray("schemes").map { it.asString }.toSet())
        assertTrue(signature.get("signaturePresent").asBoolean)
        assertTrue(signature.get("available").asBoolean)
        assertEquals(0, signature.getAsJsonArray("warnings").size())
        assertFalse(signature.get("hasDifferentCertificates").asBoolean)
        assertFalse(signature.get("verified").asBoolean)
        assertTrue(signature.get("coverage").asString.contains("V3.1"))

        val certificates = signature.getAsJsonArray("certificates")
        assertEquals(1, certificates.size())
        val metadata = certificates[0].asJsonObject
        assertEquals(setOf("V2", "V3", "V3.1"), metadata.getAsJsonArray("schemes").map { it.asString }.toSet())
        assertEquals(certificate.size, metadata.get("sizeBytes").asInt)
        assertEquals(digest("SHA-256", certificate), metadata.get("sha256").asString)
        assertEquals(digest("SHA-1", certificate), metadata.get("sha1").asString)
        assertEquals(digest("MD5", certificate), metadata.get("md5").asString)
        assertEquals(CRC32().apply { update(certificate) }.value, metadata.get("crc32").asLong)
        assertEquals("4a534f4e", metadata.get("serialNumberHex").asString)
        assertTrue(metadata.get("subject").asString.contains("CN=AI.js Pro MCP Parity Test"))
        assertEquals(metadata.get("subject").asString, metadata.get("issuer").asString)
        assertTrue(metadata.get("notBeforeEpochMillis").asLong > 0)
        assertTrue(metadata.get("notAfterEpochMillis").asLong >= metadata.get("notBeforeEpochMillis").asLong)
        assertEquals(metadata.get("notBeforeEpochMillis").asLong, metadata.get("notBefore").asLong)
        assertEquals(metadata.get("notAfterEpochMillis").asLong, metadata.get("notAfter").asLong)
        assertTrue(metadata.get("certificateSignatureAlgorithm").asString.contains("SHA256"))
        assertEquals("1.2.840.113549.1.1.11", metadata.get("certificateSignatureAlgorithmOid").asString)
        assertEquals(metadata.get("certificateSignatureAlgorithm").asString, metadata.get("algorithm").asString)
        assertFalse(metadata.get("sha256").asString.contains(' '))
    }

    @Test
    fun v2OnlyApkDoesNotClaimV3Coverage() {
        val certificate = Base64.getDecoder().decode(TEST_CERTIFICATE_DER)
        val apk = temp.newFile("v2-only.apk")
        apk.writeBytes(apkWithSigningBlock(certificate, includeV3 = false))

        val signature = ToolkitSignatures.read(apk)
        assertEquals(listOf("V2"), signature.getAsJsonArray("schemes").map { it.asString })
        assertFalse(signature.get("hasDifferentCertificates").asBoolean)
        assertEquals(1, signature.getAsJsonArray("certificates").size())
    }

    private fun apkWithSigningBlock(certificate: ByteArray, includeV3: Boolean = true): ByteArray {
        val plain = ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                listOf(
                    "AndroidManifest.xml" to "<manifest package=\"com.example.fixture\"/>".toByteArray(),
                    "classes.dex" to byteArrayOf(0x64, 0x65, 0x78, 0x0a)
                ).forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            output.toByteArray()
        }
        val eocd = eocdOffset(plain)
        val buffer = ByteBuffer.wrap(plain).order(ByteOrder.LITTLE_ENDIAN)
        val directory = buffer.getInt(eocd + 16)
        val pairs = if (includeV3) {
            pair(0x7109871a, schemeValue(certificate, v3 = false)) +
                pair(0xf05368c0.toInt(), schemeValue(certificate, v3 = true)) +
                pair(0x1b93ad61, schemeValue(certificate, v3 = true))
        } else {
            pair(0x7109871a, schemeValue(certificate, v3 = false))
        }
        // APK Signing Block: uint64 size, id/value pairs, uint64 size, magic. The size counts the
        // pairs plus the trailing size and magic but not the leading size field.
        val size = pairs.size.toLong() + 24L
        val block = uint64(size) + pairs + uint64(size) + "APK Sig Block 42".toByteArray(Charsets.US_ASCII)
        val combined = plain.copyOfRange(0, directory) + block + plain.copyOfRange(directory, plain.size)
        ByteBuffer.wrap(combined).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(eocd + block.size + 16, directory + block.size)
        return combined
    }

    private fun schemeValue(certificate: ByteArray, v3: Boolean): ByteArray =
        lengthPrefixed(signer(certificate, v3))

    private fun signer(certificate: ByteArray, v3: Boolean): ByteArray {
        val signedData = signedData(certificate, v3)
        return if (v3) lengthPrefixed(
            signedData,
            uint32(0),
            uint32(Int.MAX_VALUE),
            lengthPrefixed(ByteArray(0)),
            lengthPrefixed(ByteArray(0))
        ) else lengthPrefixed(
            signedData,
            lengthPrefixed(ByteArray(0)),
            lengthPrefixed(ByteArray(0))
        )
    }

    private fun signedData(certificate: ByteArray, v3: Boolean): ByteArray {
        val digestsAndCertificates = lengthPrefixed(ByteArray(0), certificate)
        return if (v3) digestsAndCertificates + uint32(0) + uint32(Int.MAX_VALUE) +
            lengthPrefixed(ByteArray(0)) else digestsAndCertificates
    }

    private fun pair(id: Int, value: ByteArray): ByteArray =
        uint64(4L + value.size) + uint32(id) + value

    private fun lengthPrefixed(vararg chunks: ByteArray): ByteArray {
        val body = chunks.fold(ByteArray(0)) { result, bytes -> result + bytes }
        return uint32(body.size) + body
    }

    private fun uint32(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun uint64(value: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()

    private fun eocdOffset(bytes: ByteArray): Int {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (offset in bytes.size - 22 downTo 0) {
            if (buffer.getInt(offset) != 0x06054b50) continue
            val commentLength = buffer.getShort(offset + 20).toInt() and 0xffff
            if (offset + 22 + commentLength == bytes.size) return offset
        }
        error("ZIP end-of-central-directory record not found")
    }

    private fun digest(algorithm: String, bytes: ByteArray): String =
        MessageDigest.getInstance(algorithm).digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        // Disposable public self-signed test certificate (subject:
        // CN=AI.js Pro MCP Parity Test, O=Fixture, OU=Test; serial 0x4a534f4e).
        private const val TEST_CERTIFICATE_DER = "MIIDWzCCAkOgAwIBAgIESlNPTjANBgkqhkiG9w0BAQsFADBFMSIwIAYDVQQDDBlBSS5qcyBQcm8gTUNQIFBhcml0eSBUZXN0MRAwDgYDVQQKDAdGaXh0dXJlMQ0wCwYDVQQLDARUZXN0MB4XDTI2MTEwMjE4MjgyNFoXDTQ2MTAyODE4MjgyNFowRTEiMCAGA1UEAwwZQUkuanMgUHJvIE1DUCBQYXJpdHkgVGVzdDEQMA4GA1UECgwHRml4dHVyZTENMAsGA1UECwwEVGVzdDCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBANtNQTulBUuWuJ7VvzoBmveO+32Wmv2+l8rBQyaoVlHFNgkkRM9QGL/wC3pTF7fEqAafYwyt9XzdtU3jZhOkPikC9umyzB04YdydD9P9uFkNMHkJqFFQznzXlz8XlK0dcxzMTUCHCKKAPTTM2MCCM7MLxTV+wfmdXmciECMPkSdoRkjPggA9qvbE3QgDcP/cWpg+b/XZhRsQPiIdxXm6xZ4TISoB092leKtWdOtZAOn/JY1TKKamVgBcPHjtisWkspMtjuzmqdKnGmBSMgepKxg83nU07+5b5FoRYuv5UA7mk9VTdIWC+0x43z41C7exQ89bX4epMimuYT8KwONpka8CAwEAAaNTMFEwHQYDVR0OBBYEFFu2e1mWHS48VyQYDsqz3p3jXgp6MB8GA1UdIwQYMBaAFFu2e1mWHS48VyQYDsqz3p3jXgp6MA8GA1UdEwEB/wQFMAMBAf8wDQYJKoZIhvcNAQELBQADggEBAK66+Q54ivewXvFV6DoKhTrG+Zfe4R9laa75ti9yuWl4lzpFJxd4QSuWNwz1HTTMoA+0e7wNtn0fXKqtkwyosDnrvWnzIliCek1keXUaomzZRAno4JOivr/3csl+RtFNb3ox50JEjOKDNcOZ6oMh99Yg5xTvf911/EOVI8tnkZ1qobs9kDIzTT+0lAhHKSHOIsqtEsBew+t+8+RyqdZGXL0xah4l8bInI3v1AEGvNA167cYh62MGaIr0yvGgLIqlFPzYEltbUCZq3GKRHnsiZrR5pFukaFzL3/MvfP5DpelzaiGTeOuJUmgPrTvWhrq+LlFK90B22VZozqrRzVh2jsQ="
    }
}
