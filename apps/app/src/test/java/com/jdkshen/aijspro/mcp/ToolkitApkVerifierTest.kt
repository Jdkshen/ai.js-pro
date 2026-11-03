package com.jdkshen.aijspro.mcp

import com.android.apksig.ApkSigner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Verifier tests are static/offline. The PKCS#12 below is a disposable test-only key; it is not a
 * product signing identity and is never used outside this unit test.
 */
class ToolkitApkVerifierTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun alignedArchiveReportsZipElfAndManifestFactsSeparately() {
        val apk = writeApk(elf(16384), aligned = true)
        val report = ToolkitApkVerifier.inspect(apk, minSdk = 24, maxSdk = 34)

        assertEquals("partial", report.get("status").asString) // unsigned
        val zip = report.getAsJsonObject("zip")
        assertEquals("valid", zip.get("status").asString)
        val alignment = zip.getAsJsonObject("alignment")
        assertEquals(0, alignment.get("storedEntriesMisaligned4Bytes").asInt)
        assertEquals(0, alignment.get("nativeLibrariesMisaligned16Kb").asInt)
        assertTrue(alignment.get("storedEntriesChecked").asInt >= 5)
        assertTrue(alignment.get("nativeLibrariesChecked16Kb").asInt >= 1)

        val manifest = report.getAsJsonObject("manifest")
        assertEquals("valid", manifest.get("status").asString)
        assertEquals(24, manifest.get("minSdk").asInt)
        assertEquals(34, manifest.get("targetSdk").asInt)
        assertEquals("config.arm64_v8a", manifest.get("split").asString)
        assertEquals(listOf("arm64-v8a"), manifest.getAsJsonArray("abis").map { it.asString })
        assertEquals("range_overlap", manifest.getAsJsonObject("compatibility").get("sdkRange").asString)

        val native = report.getAsJsonObject("native")
        assertEquals("valid", native.get("status").asString)
        val library = native.getAsJsonArray("libraries")[0].asJsonObject
        assertTrue(library.getAsJsonObject("elf").get("allPtLoad16KbCompatible").asBoolean)
        assertTrue(library.getAsJsonObject("zip").get("alignment16Kb").asBoolean)

        val signature = report.getAsJsonObject("signature")
        assertEquals("partial", signature.get("status").asString)
        assertFalse(signature.get("verified").asBoolean)
        assertEquals("not_evaluated", report.get("installability").asString)
        assertEquals("not_evaluated", report.get("businessBehavior").asString)
        assertTrue(report.getAsJsonArray("limitations").size() > 0)
    }

    @Test
    fun misalignedArchiveDoesNotMakeTheElfLookRelinked() {
        val apk = writeApk(elf(16384), aligned = false)
        val report = ToolkitApkVerifier.inspect(apk)
        val zip = report.getAsJsonObject("zip")
        val alignment = zip.getAsJsonObject("alignment")

        assertEquals("partial", zip.get("status").asString)
        assertTrue(alignment.get("storedEntriesMisaligned4Bytes").asInt > 0)
        assertTrue(alignment.get("nativeLibrariesMisaligned16Kb").asInt > 0)

        val native = report.getAsJsonObject("native")
        assertEquals("valid", native.get("status").asString)
        val library = native.getAsJsonArray("libraries")[0].asJsonObject
        assertTrue(library.getAsJsonObject("elf").get("allPtLoad16KbCompatible").asBoolean)
        assertFalse(library.getAsJsonObject("zip").get("alignment16Kb").asBoolean)
    }

    @Test
    fun fourKilobyteElfIsReportedEvenWhenTheZipOffsetIsSixteenKilobyteAligned() {
        val apk = writeApk(elf(4096), aligned = true)
        val report = ToolkitApkVerifier.inspect(apk)

        assertEquals("valid", report.getAsJsonObject("zip").get("status").asString)
        val native = report.getAsJsonObject("native")
        assertEquals("partial", native.get("status").asString)
        assertTrue(native.getAsJsonArray("issues").any {
            it.asJsonObject.get("code").asString == "ELF_PT_LOAD_ALIGNMENT_16K"
        })
        val library = native.getAsJsonArray("libraries")[0].asJsonObject
        assertFalse(library.getAsJsonObject("elf").get("allPtLoad16KbCompatible").asBoolean)
    }

    @Test
    fun manifestSdkRangeAndSplitAreSummaryOnly() {
        val apk = writeApk(elf(16384), aligned = true)
        val narrow = ToolkitApkVerifier.inspect(apk, minSdk = 21, maxSdk = 23)
        assertEquals("incompatible", narrow.getAsJsonObject("manifest")
            .getAsJsonObject("compatibility").get("sdkRange").asString)
        assertEquals("partial", narrow.getAsJsonObject("manifest").get("status").asString)

        val overlap = ToolkitApkVerifier.inspect(apk, minSdk = 24, maxSdk = 34)
        val manifest = overlap.getAsJsonObject("manifest")
        assertEquals("range_overlap", manifest.getAsJsonObject("compatibility").get("sdkRange").asString)
        assertEquals("not_evaluated", manifest.getAsJsonObject("compatibility").get("installability").asString)
        assertEquals("config.arm64_v8a", manifest.get("split").asString)
        assertEquals("config", manifest.get("splitType").asString)
        assertEquals("not_evaluated", manifest.getAsJsonObject("compatibility").get("splitCompatibility").asString)
        assertEquals("not_evaluated", overlap.get("installability").asString)
    }

    @Test
    fun zip64SignalsAreReportedInsteadOfGuessingOffsets() {
        val fake = temp.newFile("zip64.apk")
        val bytes = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0, 0x06054b50)
            .putShort(4, 0)
            .putShort(6, 0)
            .putShort(8, 0xffff.toShort())
            .putShort(10, 0xffff.toShort())
            .putInt(12, -1)
            .putInt(16, -1)
            .putShort(20, 0)
            .array()
        fake.writeBytes(bytes)

        val report = ToolkitApkVerifier.inspect(fake)
        val zip = report.getAsJsonObject("zip")
        assertEquals("partial", zip.get("status").asString)
        assertTrue(zip.get("zip64Unsupported").asBoolean)
        assertTrue(zip.getAsJsonArray("issues").any {
            it.asJsonObject.get("code").asString == "ZIP64_UNSUPPORTED"
        })
        assertNotEquals("valid", report.get("status").asString)
    }

    @Test
    fun signedArchiveIsCryptographicallyVerifiedAndTamperingFails() {
        // No native library here: this test isolates signature verification from signer alignment.
        val unsigned = writeApk(elf(16384), aligned = true, includeNative = false)
        val signed = temp.newFile("signed.apk")
        val password = "testonly".toCharArray()
        val store = KeyStore.getInstance("PKCS12")
        store.load(ByteArrayInputStream(Base64.getDecoder().decode(TEST_P12)), password)
        val key = store.getKey("verifier", password) as PrivateKey
        val certificate = store.getCertificate("verifier") as X509Certificate
        val signer = ApkSigner.SignerConfig.Builder("verifier", key, listOf(certificate)).build()
        ApkSigner.Builder(listOf(signer))
            .setInputApk(unsigned)
            .setOutputApk(signed)
            .setMinSdkVersion(24)
            .setV1SigningEnabled(false)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
            .sign()

        val report = ToolkitApkVerifier.inspect(signed, minSdk = 24, maxSdk = 34)
        assertEquals("valid", report.get("status").asString)
        val signature = report.getAsJsonObject("signature")
        assertTrue(signature.get("verified").asBoolean)
        assertEquals("valid", signature.get("status").asString)
        assertTrue(signature.getAsJsonObject("schemes").getAsJsonObject("V2").get("verified").asBoolean)
        assertTrue(signature.getAsJsonObject("schemes").getAsJsonObject("V3").get("verified").asBoolean)
        val certificates = signature.getAsJsonArray("certificates")
        assertEquals(1, certificates.size())
        assertEquals(sha256(certificate.encoded), certificates[0].asJsonObject.get("sha256").asString)
        assertEquals(sha256(signed.readBytes()), report.getAsJsonObject("file").get("sha256").asString)

        val tampered = temp.newFile("tampered.apk")
        signed.copyTo(tampered, overwrite = true)
        val offset = dataOffset(tampered, "assets/probe.txt")
        RandomAccessFile(tampered, "rw").use { file ->
            file.seek(offset)
            val original = file.read()
            file.seek(offset)
            file.write(original xor 0xff)
        }
        val tamperedReport = ToolkitApkVerifier.inspect(tampered, minSdk = 24, maxSdk = 34)
        val tamperedSignature = tamperedReport.getAsJsonObject("signature")
        assertFalse(tamperedSignature.get("verified").asBoolean)
        assertEquals("partial", tamperedSignature.get("status").asString)
        assertTrue(tamperedSignature.getAsJsonArray("errors").size() > 0)
        assertEquals("partial", tamperedReport.get("status").asString)
    }

    private fun writeApk(elf: ByteArray, aligned: Boolean, includeNative: Boolean = true): File {
        val file = temp.newFile("verify-${System.nanoTime()}.apk")
        val payloads = linkedMapOf(
            "a" to byteArrayOf(1),
            "AndroidManifest.xml" to MANIFEST,
            "classes.dex" to byteArrayOf(0x64, 0x65, 0x78, 0x0a),
            "resources.arsc" to ByteArray(32),
            "assets/probe.txt" to "probe".toByteArray(),
            "assets/中文.txt" to "混合压缩内容".repeat(4).toByteArray()
        )
        if (includeNative) payloads["lib/arm64-v8a/libverify.so"] = elf
        ZipOutputStream(file.outputStream()).use { zip ->
            payloads.forEach { (name, bytes) ->
                val entry = ZipEntry(name).apply { time = 1700000000000L }
                if (name != "assets/中文.txt") {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = entry.size
                    entry.crc = CRC32().apply { update(bytes) }.value
                } else {
                    entry.method = ZipEntry.DEFLATED
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        if (aligned) ToolkitArchiveAlignment.align(file)
        return file
    }

    private fun elf(pAlign: Long): ByteArray {
        val bytes = ByteArray(512)
        bytes[0] = 0x7f
        bytes[1] = 'E'.code.toByte()
        bytes[2] = 'L'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        bytes[4] = 2
        bytes[5] = 1
        bytes[6] = 1
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(16, 3)
        buffer.putShort(18, 183)
        buffer.putInt(20, 1)
        buffer.putLong(24, 0x10080)
        buffer.putLong(32, 64)
        buffer.putShort(52, 64)
        buffer.putShort(54, 56)
        buffer.putShort(56, 1)
        buffer.putShort(58, 64)
        buffer.putInt(64, 1)
        buffer.putInt(68, 5)
        buffer.putLong(72, 0)
        buffer.putLong(80, 0x10000)
        buffer.putLong(96, 512)
        buffer.putLong(104, 512)
        buffer.putLong(112, pAlign)
        return bytes
    }

    private fun dataOffset(apk: File, wanted: String): Long {
        val bytes = apk.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val eocd = (bytes.size - 22 downTo 0).firstOrNull { at ->
            buffer.getInt(at) == 0x06054b50 &&
                at + 22 + (buffer.getShort(at + 20).toInt() and 0xffff) == bytes.size
        } ?: error("EOCD missing")
        val count = buffer.getShort(eocd + 10).toInt() and 0xffff
        var p = buffer.getInt(eocd + 16)
        repeat(count) {
            check(buffer.getInt(p) == 0x02014b50)
            val nameLength = buffer.getShort(p + 28).toInt() and 0xffff
            val extraLength = buffer.getShort(p + 30).toInt() and 0xffff
            val commentLength = buffer.getShort(p + 32).toInt() and 0xffff
            val local = buffer.getInt(p + 42).toLong() and 0xffffffffL
            val name = String(bytes, p + 46, nameLength, Charsets.UTF_8)
            if (name == wanted) {
                val localName = buffer.getShort(local.toInt() + 26).toInt() and 0xffff
                val localExtra = buffer.getShort(local.toInt() + 28).toInt() and 0xffff
                return local + 30L + localName + localExtra
            }
            p += 46 + nameLength + extraLength + commentLength
        }
        error("entry not found: $wanted")
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        private val MANIFEST = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.verify" split="config.arm64_v8a"
                android:versionCode="1" android:versionName="1.0">
              <uses-sdk android:minSdkVersion="24" android:targetSdkVersion="34"/>
              <application android:extractNativeLibs="false"/>
            </manifest>
        """.trimIndent().toByteArray(Charsets.UTF_8)

        // Disposable PKCS#12 test key: CN=AI.js Pro MCP Verifier Test, password "testonly".
        private val TEST_P12 = "MIIKaAIBAzCCChIGCSqGSIb3DQEHAaCCCgMEggn/MIIJ+zCCBbIGCSqGSIb3DQEHAaCCBaMEggWfMIIFmzCCBZcGCyqGSIb3DQEMCgECoIIFQDCCBTwwZgYJKoZIhvcNAQUNMFkwOAYJKoZIhvcNAQUMMCsEFPEgtii5cB25iXEsi/Stenp8ZdEUAgInEAIBIDAMBggqhkiG9w0CCQUAMB0GCWCGSAFlAwQBKgQQMRnMh8LFnY2N7AzFoy99PASCBNDvBt6VJBTDcDrp4LkN/ki+fqO/7THyyoWtpEbJ5rYmAqgBYLb3M2n5uh8Nb6PgqX/BpmdECSQHYtF9fQUaHIx695NxyDCY0xFkvQ2jI435BA584+GKXay5vT3jwTwMWc+o+AhvCQ3DaLmAqBZMn2K3WlLA6ANV/KmDs9n2Yl7Tn+xbz3AZD1GPmPBpnCK70xwX/ofevoEp9gtvuHfrNTBBGoTK43/+asqVOC1RfvYW9hk4NWoZWco5D2ukPflBZbJvM665I3SHTK/v9u+HrN81q75g9zW2UErcAF2t5P1rZFwESslcJprk0T/jzYkkFu85qQ+h5/FBrnQ9n1pjHL4Ft2pZS4UQT6JmkSclK2Bqc1KAIeM7qgcj4J96+zOuSksy0Hi477wWruvMmrZDrksiAlVneKdstPz3LBevyGZfMDUI9wR47+qH/h0/bP+SCf7iLdsZ2NrttWTwDH0h7futWANopKJnSq54brgxRcQ+fgJ4HtQltZ9hczZBhV7vRq4GJYwnIObD9gkj/7hWufRFCJMnd15T" +
            "QPrDWZe8xq67Jau24ek9bpiq0+C8DlOwdU/gr/bNy+52slu28O0JiUNVVFDAKRzq5lJjJDx2X65OyNRdnrb11J7mi3lmYW44Yp+gQ904OuG0zTdSBAqDeVvGuAL1HjjFTYXoWIfVLHeS+O7IUWs30NrOXFmIE9HQDh5Z7w+1aZucTyL4AaISQTh0w9VqVnREXK3LLS3EOwNqQeOOIiro3MA/95SQqx0QZptg+aCFBaerfhtrJUPragPnEYTKHaEKggdsfE+F6lboNRq4prokxzI/iOaFlq+6spvPmBwOZN0kZi45G1yhT5C+F45j5pMS+yzFBDBYfA3ozWUUbgRzgOSFMWZmDs5TAYb4jmFMO7PuaniK3b5nQ6fELC25JggWKO8Rec6nDhzX3sd5mMhCjZZToU7wYk4WaFN47Lllv3XsGSSxE2y0JrR9Div8HzScycT26ydmocnSEDSYWQPY/XqyvQt45KGQk6KtT/XC1a7rbZYyFyemsFq5loP0a5iNK9LjcN02dM/qDH8cApiQ7epvNfX+4XnjIruUjQ4NIPvC0v6UawjCIZYXdro1JaM7Ogx/aKaHxtnmEb70Mm2qRijpISPARbODulVaokeaZ9xlHNOnIqU/qMx0rCqLS6bH9ZUSDHK/GiFcmNf/HjsveGc+ORu5zOB7i+iWH2sl++6sFzGDBcP0urFccve07YAGnNl2SiLW2j9Usz0OgmnrQE3mCt/TjonOk8AcKr18/EZecmxzotJoGTvfQcsBXcVzc8/Qj/x5hT5vIBeeUD2JajtZbi/GnmRD" +
            "ziao7qn3uUkkQeo+p5wytJwJOPgcjLgYG3VT7Crfo8eptdeT9c5639SqO/CIjLTXpM6fbOJha/y3eIqbK1T2kUjMNboRtjWlJI/nOla5Geyc4jbNLEhr5U7VifGvr6pU2DPufDB7EC47ofNe/bu10GAKufLxHOWaSPipBfmEu+idmd0hXJw66LGjI5jMRjatUv/DcLcvwS90OJbjqWVOY8KiO4rQ1YtpUckdsrB+Nab2DMGToM4yvS50SUuw3pH7utAggNEiMOSs2dpHUJAUhGbar2vn/qz5qFlu6eA1bmdOxTFEMB8GCSqGSIb3DQEJFDESHhAAdgBlAHIAaQBmAGkAZQByMCEGCSqGSIb3DQEJFTEUBBJUaW1lIDE3OTM2ODIzODcxNDcwggRBBgkqhkiG9w0BBwagggQyMIIELgIBADCCBCcGCSqGSIb3DQEHATBmBgkqhkiG9w0BBQ0wWTA4BgkqhkiG9w0BBQwwKwQUdHaRPJxkv3MnopHClOnWPaor5RsCAicQAgEgMAwGCCqGSIb3DQIJBQAwHQYJYIZIAWUDBAEqBBDKnSQ76wvnJjQUlmGB6LP+gIIDsPrwaN7nfqx47OYqaKRBSHqG1qx/Chf3rwSxZJqLTzxM2nbUaVB1kO1N/T86jhzcbnUGU3mWnzKi6aUYlltPc5jPKzSduXbyEbog+FHkixmEgCT7/9PRytSQE4iLO5Qo9TLJGinuYaXuZmZ7id4IYsEyL4kFRqFrROqf0pW2cXMi+hEM7Dndw9rAMH4v/bFixZzCIFj6/RT0T6f+" +
            "ODFY1M9H9AqU/1w8+au/5GmFXngoWaYgypS6/oSfwA2BtMaC4x6jTygT5yfINs5mTQVD5pLb0KVKsOFquBVi6+QimhN0sYcAIaJXCt+CQ+v+OpuJFemBZl9wSBhHOkiIqXsE5/G1Cko3/7LIpPzv8LpQ69hd6pYg9Kr0avdsEgGpjeWaB92L+YdpLtSAz0P/lWcp+zHqWTTn7/Ya/JntA/+YcwRwMANbEibAkF6hz1YVmJBKXHH4t9hAsu1NGJ6417yhPnUsofw+aSuDeICUz2jNcIskALyoDhYQgbW/ymLjOhBhvvfzreSl5lDO42MSCckjKv5E/cyJSu8NjogXD3gS0NMv4I2IsVRjWWbGJbwaghKyCCK9Lf9+vSqir3HO+NYeuuBpuB7K0tsw9a5rgiCf43WcDfS5vovqmny8NShrGtHwWVprNyPzcZqn3NJJ/7RknE6ki2BOSl9GJu6AKR/DRITYwDnwr6whKYJyok5OvsAIi94tWJGA4jO9rgAISaMsp4BvuF9aHgRiH8w25zAYXUXDWfolsiSLEKVlOHM/c+kYD+878f1rUZm+GOfSMcfcaXsWkOnzixqn4YkZRNAgqz70YkBm3qLMkQdCn1LEVv1wfENBcwlCGekogodzxkxrC+crtrkie2xTVdXtohYAKRTHaTOtkvf/cv5ZESKEt4wEnHK1t4kRbTUQLqxddZayWmwC2163tvhi6kmM6VJjKkHdnwpbhLlyNURnxc0c4ZJ7xNX7Gsf0ymLfrOhkf5T75debOz0i96kFobeuLozZEIz8O1BP" +
            "cUfKMevPY+RXRD3eGAVtRTcM0HXQTnMZcOhqfHwuJKn9gx0kr7mcAaQ/2WoQxuGMFm0qihChbuv6pnVHOjIvFpVOmHgmnll2cyLRUbaLH2D0L/+Hkjp7gX+XRlDSYmtK4WgBRmN6VeUxFF5a01gnsbMFdvYFW+sgmZUbHoidphcEORMtcHpdTCREaCW5V87gLadMsEebSiuoCzlO2rtieag1czI2H0v41ea3MsGyDOBYi4YFCo5WApJkwa7QME0wMTANBglghkgBZQMEAgEFAAQgzhrgz4L5PKJPhTyFIGm8NX03A+7PZwtcXe4USB5GWwAEFJhrQNyzcgKQYnO5xhx69ZUx0jLtAgInEA=="
    }
}
