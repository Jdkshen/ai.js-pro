package com.jdkshen.aijspro.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** §12：证据导出必须可复核（哈希/解压校验），且默认排除敏感材料。 */
class ToolkitEvidenceExportTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun redactionRemovesPrivateKeysAndTokens() {
        val text = listOf(
            "-----BEGIN PRIVATE KEY-----", "MIIEvQIBADANBgkq", "-----END PRIVATE KEY-----",
            "token=abcdef0123456789"
        ).joinToString("\n")
        val redacted = ToolkitEvidenceExport.redact(text)
        assertTrue(redacted.get("redactions").asInt >= 2)
        val output = redacted.get("text").asString
        assertFalse(output.contains("MIIEvQIBADANBgkq"))
        assertFalse(output.contains("abcdef0123456789"))
        assertTrue(output.contains("[REDACTED]"))
    }

    @Test
    fun manifestCarriesClaimsWithEvidenceLevels() {
        val manifest = ToolkitEvidenceExport.buildManifest(
            identity = ToolkitIo.json("apkSha256" to "deadbeef", "packageName" to "com.demo"),
            overlay = ToolkitIo.json("kind" to "decrypt", "analysisOnly" to true),
            editDiff = ToolkitIo.json("changedEntries" to 2),
            reports = listOf("report.md"),
            commands = listOf("r2 -A lib.so"),
            tools = ToolkitIo.json("toolkit" to "1"),
            files = listOf(ToolkitEvidenceExport.Entry("notes.txt", "hello".toByteArray())),
            claims = listOf("签名通过" to "static_confirmed", "目标功能正常" to "runtime_confirmed"),
            debugKeyIncluded = true,
            generatedAt = 1L
        )
        assertEquals(1, manifest.get("schemaVersion").asInt)
        assertEquals(1L, manifest.get("generatedAt").asLong)
        assertEquals(2, manifest.getAsJsonArray("claims").size())
        assertEquals("static_confirmed",
            manifest.getAsJsonArray("claims")[0].asJsonObject.get("level").asString)
        assertEquals(5, manifest.getAsJsonArray("evidenceLevels").size())
        assertTrue(manifest.getAsJsonObject("sensitivePolicy").get("debugKeyIncluded").asBoolean)
        assertTrue(manifest.getAsJsonObject("sensitivePolicy").get("debugKeyNote").asString.contains("仅供测试"))
        assertEquals(1, manifest.getAsJsonArray("files").size())
        assertFalse(manifest.getAsJsonObject("verification").get("fullExtractCheck").asBoolean)
    }

    @Test
    fun writeZipVerifiesEveryEntryAndHash() {
        val files = listOf(
            ToolkitEvidenceExport.Entry("a/one.txt", "one".toByteArray()),
            ToolkitEvidenceExport.Entry("two.bin", byteArrayOf(1, 2, 3))
        )
        val manifest = ToolkitEvidenceExport.buildManifest(
            identity = ToolkitIo.json("apkSha256" to "abc"), files = files, generatedAt = 2L)
        val output = File(temp.root, "evidence.zip")
        val result = ToolkitEvidenceExport.writeZip(manifest, files, output)
        assertTrue(output.isFile)
        assertTrue(result.get("fullExtractCheck").asBoolean)
        assertEquals(2, result.get("checkedEntries").asInt)
        assertEquals(0, result.getAsJsonArray("failures").size())
        assertEquals(ToolkitIo.sha(output), result.get("sha256").asString)
        assertTrue(result.get("manifestParsable").asBoolean)
    }

    @Test
    fun collectRespectsBudgetsAndKeepsRelativePaths() {
        val root = temp.newFolder("root")
        File(root, "sub").mkdirs()
        File(root, "top.txt").writeText("top")
        File(root, "sub/inner.txt").writeText("inner")
        val entries = ToolkitEvidenceExport.collect(root)
        assertEquals(setOf("top.txt", "sub/inner.txt"), entries.map { it.path }.toSet())
        assertEquals(1, ToolkitEvidenceExport.collect(root, maxEntries = 1).size)
    }

    @Test
    fun unknownEvidenceLevelIsTreatedAsLowestTrust() {
        val level = ToolkitEvidenceExport.level("something_new")
        assertEquals("未知等级", level.get("zh").asString)
        assertTrue(level.get("meaning").asString.contains("最低"))
    }
}
