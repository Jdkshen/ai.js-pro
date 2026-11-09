package com.jdkshen.aijspro.autojs.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.cert.Certificate
import java.security.cert.CertificateFactory

/** task-80：SJBC 收集规则 + manifest certSha 对齐（含「不含 .sjbc 时行为不变」回归）。 */
class SjbcPackagingTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun noSjbcDirectoryMeansNothingIsStaged() {
        val project = temp.newFolder("p1")
        File(project, "main.js").writeText("console.log(1)")
        assertNull(SjbcPackaging.findSourceDir(project))
        val stage = SjbcPackaging.collect(SjbcPackaging.findSourceDir(project))
        assertTrue("不含 .sjbc 的工程必须什么都不收（既有打包行为不变）", stage.entries.isEmpty())
        assertEquals(0, stage.sjbcCount())
        assertFalse(stage.hasManifest)
        assertTrue(SjbcPackaging.describe(stage, null, "pkg").contains("source=(none)"))
    }

    @Test fun collectsOnlySjbcAndManifestKeepingSubdirectories() {
        val project = temp.newFolder("p2")
        val sjbc = File(project, "assets/sjbc").apply { mkdirs() }
        File(sjbc, "a.sjbc").writeBytes(ByteArray(8))
        File(sjbc, "manifest.json").writeText("{}")
        File(sjbc, "readme.txt").writeText("ignore me")
        File(sjbc, "sub").mkdirs()
        File(sjbc, "sub/b.sjbc").writeBytes(ByteArray(4))
        val stage = SjbcPackaging.collect(SjbcPackaging.findSourceDir(project))
        assertEquals(listOf("a.sjbc", "manifest.json", "sub/b.sjbc"), stage.entries.map { it.relativePath })
        assertEquals(2, stage.sjbcCount())
        assertTrue(stage.hasManifest)
        val line = SjbcPackaging.describe(stage, "abc123", "com.demo")
        assertTrue(line, line.contains("certSha=abc123") && line.contains("package=com.demo") && line.contains("files=2") && line.contains("manifest=true"))
    }

    @Test fun legacySjbcDirectoryIsAlsoAccepted() {
        val project = temp.newFolder("p3")
        val legacy = File(project, "sjbc").apply { mkdirs() }
        File(legacy, "x.sjbc").writeBytes(ByteArray(2))
        assertEquals(legacy.canonicalPath, SjbcPackaging.findSourceDir(project)?.canonicalPath)
    }

    @Test fun manifestCertShaIsAlignedAndChangeIsWarned() {
        // 首次写入（原本没有 certSha）
        val first = SjbcPackaging.patchManifest("{\"version\":1}", "aa".repeat(32), "com.demo", listOf("a.sjbc"))
        assertTrue(first.changed)
        assertNull(first.previousCertSha)
        assertTrue(first.warning!!, first.warning!!.contains("首次对齐"))
        // 断言走解析（不依赖 gson 的缩进/空格风格）
        assertEquals("aa".repeat(32),
            com.google.gson.JsonParser().parse(first.json).asJsonObject.get("certSha").asString)

        // 同一个证书 → 不再改动、不告警
        val same = SjbcPackaging.patchManifest(first.json, "aa".repeat(32), "com.demo", listOf("a.sjbc"))
        assertFalse(same.changed)
        assertNull(same.warning)

        // 换证书 → 必须重编脚本（机器可查的 warning）
        val changed = SjbcPackaging.patchManifest(first.json, "bb".repeat(32), "com.demo", listOf("a.sjbc"))
        assertTrue(changed.changed)
        assertEquals("aa".repeat(32), changed.previousCertSha)
        assertNotNull(changed.warning)
        assertTrue(changed.warning!!, changed.warning!!.contains("必须重编"))

        // 没拿到证书 → 不伪造，只告警
        val unavailable = SjbcPackaging.patchManifest(first.json, null, "com.demo", listOf("a.sjbc"))
        assertFalse(unavailable.changed)
        assertTrue(unavailable.warning!!, unavailable.warning!!.contains("certSha 未提供"))
    }

    @Test fun manifestWithoutManifestFileGetsGeneratedMinimalFields() {
        val result = SjbcPackaging.patchManifest(null, "cc".repeat(32), "com.demo", listOf("a.sjbc", "b.sjbc"))
        val parsed = com.google.gson.JsonParser().parse(result.json).asJsonObject
        assertEquals("com.demo", parsed.get("package").asString)
        assertEquals("cc".repeat(32), parsed.get("certSha").asString)
        assertEquals(1, parsed.get("version").asInt)
        assertEquals(listOf("a.sjbc", "b.sjbc"), parsed.getAsJsonArray("files").map { it.asString })
    }

    @Test fun certificateFingerprintIsNormalisedToPlainLowercaseHex() {
        assertEquals("aabbcc", SjbcPackaging.normalizeCertSha("AA:BB:CC"))
        assertEquals("aabbcc", SjbcPackaging.normalizeCertSha("aa bb cc"))
        assertEquals("aabbcc", SjbcPackaging.normalizeCertSha("aAbBcC"))
        assertNull(SjbcPackaging.normalizeCertSha(""))
        assertNull(SjbcPackaging.normalizeCertSha(null))
        // 与 certSha256() 的输出同形态（64 位小写 hex）→ 可直接写进 manifest 的 certSha
        assertEquals(64, SjbcPackaging.normalizeCertSha("AB:".repeat(31) + "CD")!!.length)
    }

    @Test fun certSha256IsLowercaseHexOfEncodedBytes() {
        val certificate = object : Certificate("X.509") {
            override fun getEncoded(): ByteArray = byteArrayOf(0x00, 0x0F, 0x10, 0xFF.toByte())
            override fun verify(key: java.security.PublicKey) = throw UnsupportedOperationException("test stub")
            override fun verify(key: java.security.PublicKey, sigProvider: String?) = throw UnsupportedOperationException("test stub")
            override fun getPublicKey(): java.security.PublicKey? = null
            override fun toString(): String = "SjbcPackagingTest-cert"
        }
        val sha = SjbcPackaging.certSha256(certificate)
        assertNotNull(sha)
        assertEquals(64, sha!!.length)
        // 用标准 MessageDigest 复核（不采信自己实现的字面量）
        val expected = java.security.MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString("") { String.format("%02x", it) }
        assertEquals(expected, sha)
        assertNull(SjbcPackaging.certSha256(null))
    }
}
