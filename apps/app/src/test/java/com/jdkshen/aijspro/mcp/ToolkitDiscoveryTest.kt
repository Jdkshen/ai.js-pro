package com.jdkshen.aijspro.mcp

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * P0-B 文件发现单测：扩展名只作加分项，结构判定靠 ZIP 魔数 + AndroidManifest.xml；
 * 扫描预算必须给出 truncated/skipped/stopReason，不能把部分结果当成全部未找到。
 */
class ToolkitDiscoveryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject().apply {
        pairs.forEach { (name, value) ->
            when (value) {
                null -> add(name, JsonNull.INSTANCE)
                is JsonElement -> add(name, value)
                is String -> addProperty(name, value)
                is Int -> addProperty(name, value)
                is Long -> addProperty(name, value)
                is Boolean -> addProperty(name, value)
                else -> throw IllegalArgumentException("unsupported test value: " + name)
            }
        }
    }

    private fun data(result: JsonObject): JsonObject = result.getAsJsonObject("data")

    private fun writeZip(file: File, entries: Map<String, ByteArray>) {
        file.parentFile?.mkdirs()
        ZipOutputStream(file.outputStream()).use { output ->
            for ((name, bytes) in entries) {
                output.putNextEntry(ZipEntry(name))
                output.write(bytes)
                output.closeEntry()
            }
        }
    }

    private fun apkLike(file: File, name: String = file.name) {
        val entries = LinkedHashMap<String, ByteArray>()
        entries["AndroidManifest.xml"] = ByteArray(32) { 0x03.toByte() }
        entries["resources.arsc"] = ByteArray(16) { 0x02.toByte() }
        entries["classes.dex"] = ByteArray(64) { 0x01.toByte() }
        writeZip(file, entries)
    }

    @Test
    fun probeDetectsNumberedApkByStructureNotExtension() {
        val file = File(temporaryFolder.root, "影安加固-测试demo.apk.1")
        apkLike(file)
        val probe = ToolkitDiscovery.probe(file)
        assertTrue(probe.zipMagic)
        assertEquals("ZIP_OK", probe.zipStatus)
        assertTrue(probe.hasAndroidManifest)
        assertTrue(probe.hasResourcesArsc)
        assertTrue(probe.hasClassesDex)
        assertTrue(probe.candidate)
        assertEquals("high", probe.confidence)
        assertEquals("apk.numbered", probe.extensionHint)
        assertTrue(probe.reasons.any { it.contains("AndroidManifest.xml") })
        assertTrue(probe.bytesRead in 1L..ToolkitDiscovery.MAX_PROBE_BYTES)
        assertFalse(probe.truncated)
    }

    @Test
    fun probeDoesNotTrustExtensionAlone() {
        val file = File(temporaryFolder.root, "fake.apk")
        file.writeText("this is not a zip")
        val probe = ToolkitDiscovery.probe(file)
        assertFalse(probe.zipMagic)
        assertEquals("NOT_ZIP", probe.zipStatus)
        assertFalse(probe.candidate)
        assertEquals("apk", probe.extensionHint)
    }

    @Test
    fun probeFindsExtensionlessZipWithManifest() {
        val file = File(temporaryFolder.root, "tool-payload")
        writeZip(file, mapOf("AndroidManifest.xml" to ByteArray(24) { 0x03.toByte() }))
        val probe = ToolkitDiscovery.probe(file)
        assertTrue(probe.candidate)
        assertEquals("medium", probe.confidence)
        assertEquals("none", probe.extensionHint)
    }

    @Test
    fun probeRejectsZipWithoutManifest() {
        val file = File(temporaryFolder.root, "shadow.bak")
        writeZip(file, mapOf("readme.txt" to "hello".toByteArray()))
        val probe = ToolkitDiscovery.probe(file)
        assertEquals("ZIP_OK", probe.zipStatus)
        assertFalse(probe.hasAndroidManifest)
        assertFalse(probe.candidate)
        assertTrue(probe.reasons.any { it.contains("缺少 AndroidManifest.xml") })
    }

    @Test
    fun probeHandlesEmptyZip() {
        val file = File(temporaryFolder.root, "empty.zip")
        val bytes = byteArrayOf(0x50, 0x4B, 0x05, 0x06) + ByteArray(18)
        file.writeBytes(bytes)
        val probe = ToolkitDiscovery.probe(file)
        assertTrue(probe.zipMagic)
        assertEquals("ZIP_EMPTY", probe.zipStatus)
        assertFalse(probe.candidate)
    }

    @Test
    fun probeReportsUnreadableForTruncatedZip() {
        val file = File(temporaryFolder.root, "broken.apk")
        file.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(128) { 0x41.toByte() })
        val probe = ToolkitDiscovery.probe(file)
        assertTrue(probe.zipMagic)
        assertEquals("ZIP_UNREADABLE", probe.zipStatus)
        assertFalse(probe.candidate)
        assertNotNull(probe.stopReason)
    }

    @Test
    fun probeRespectsByteBudget() {
        val file = File(temporaryFolder.root, "big.apk.1")
        val entries = LinkedHashMap<String, ByteArray>()
        for (index in 0 until 40) {
            entries["assets/file-" + index + ".bin"] = ByteArray(128) { (index and 0xFF).toByte() }
        }
        writeZip(file, entries)
        val probe = ToolkitDiscovery.probe(file, 64L)
        assertTrue(probe.truncated)
        assertEquals("byteBudget", probe.stopReason)
        assertTrue(probe.bytesRead <= 64L)
    }

    @Test
    fun scanFindsCandidatesWithoutTrustingExtension() {
        val root = temporaryFolder.newFolder("scan-root")
        apkLike(File(root, "影安加固-测试demo.apk.1"))
        val nested = File(root, "cache").apply { mkdirs() }
        writeZip(File(nested, "no-extension-payload"), mapOf("AndroidManifest.xml" to ByteArray(12) { 0x03.toByte() }))
        File(nested, "plain.txt").writeText("text")
        val scan = ToolkitDiscovery.scan(root)
        assertEquals(2, scan.candidates.size)
        assertEquals("eof", scan.stopReason)
        assertFalse(scan.truncated)
        assertTrue(scan.candidates.any { it.path.endsWith(".apk.1") })
        assertTrue(scan.candidates.any { it.name == "no-extension-payload" })
        assertTrue(scan.visitedEntries >= 4)
        assertTrue(scan.probes >= 3)
    }

    @Test
    fun scanReportsEntryBudgetInsteadOfClaimingEof() {
        val root = temporaryFolder.newFolder("budget-root")
        apkLike(File(root, "a.apk"))
        apkLike(File(root, "b.apk"))
        val limits = ToolkitDiscovery.Limits(maxEntries = 1, maxDepth = 4, maxProbes = 10, maxBytes = 1024L * 1024, maxMillis = 3000L, limit = 10)
        val scan = ToolkitDiscovery.scan(root, limits)
        assertTrue(scan.truncated)
        assertEquals("entryBudget", scan.stopReason)
    }

    @Test
    fun scanReportsCandidateLimit() {
        val root = temporaryFolder.newFolder("limit-root")
        for (index in 0 until 5) {
            apkLike(File(root, "candidate-" + index + ".apk"))
        }
        val limits = ToolkitDiscovery.Limits(maxEntries = 100, maxDepth = 4, maxProbes = 100, maxBytes = 1024L * 1024, maxMillis = 3000L, limit = 2)
        val scan = ToolkitDiscovery.scan(root, limits)
        assertEquals(2, scan.candidates.size)
        assertTrue(scan.truncated)
        assertEquals("candidateLimit", scan.stopReason)
    }

    @Test
    fun scanCanIncludeZipsWithoutManifest() {
        val root = temporaryFolder.newFolder("zip-root")
        writeZip(File(root, "payload.bak"), mapOf("readme.txt" to "hello".toByteArray()))
        val strict = ToolkitDiscovery.scan(root)
        assertEquals(0, strict.candidates.size)
        val relaxed = ToolkitDiscovery.scan(root, ToolkitDiscovery.Limits(), includeZipsWithoutManifest = true)
        assertEquals(1, relaxed.candidates.size)
        assertEquals("ZIP_OK", relaxed.candidates.first().zipStatus)
    }

    @Test
    fun scanReportsDepthLimitAsSkipped() {
        val root = temporaryFolder.newFolder("depth-root")
        val deep = File(root, "a/b/c").apply { mkdirs() }
        apkLike(File(deep, "deep.apk"))
        apkLike(File(root, "top.apk"))
        val limits = ToolkitDiscovery.Limits(maxDepth = 1, maxEntries = 1000, maxProbes = 100, maxBytes = 1024L * 1024, maxMillis = 3000L, limit = 10)
        val scan = ToolkitDiscovery.scan(root, limits)
        assertTrue(scan.truncated)
        assertTrue(scan.skipped.depthLimited >= 1)
        assertEquals(1, scan.candidates.size)
    }

    @Test
    fun scanSkipsSymbolicLinks() {
        val root = temporaryFolder.newFolder("link-root")
        apkLike(File(root, "real.apk"))
        val target = File(root, "target.apk")
        apkLike(target)
        val link = File(root, "link.apk")
        val created = try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath())
            true
        } catch (_: Throwable) {
            false
        }
        assumeTrue(created)
        val scan = ToolkitDiscovery.scan(root)
        assertTrue(scan.skipped.symbolicLinks >= 1)
        assertFalse(scan.candidates.any { it.name == "link.apk" })
    }

    @Test
    fun fileToolkitStatAndDiscoverExposeStructure() {
        val root = temporaryFolder.newFolder("toolkit-root")
        apkLike(File(root, "影安加固-测试demo.apk.1"))
        val toolkit = McpFileToolkit(root)

        val stat = data(toolkit.call("file_stat", obj("path" to "影安加固-测试demo.apk.1")))
        val probe = stat.getAsJsonObject("apkProbe")
        assertNotNull(probe)
        assertTrue(probe.get("candidate").asBoolean)
        assertEquals("ZIP_OK", probe.get("zipStatus").asString)
        assertEquals("apk.numbered", probe.get("extensionHint").asString)

        val discover = data(toolkit.call("file_discover_apk", obj("path" to ".", "recursive" to true)))
        assertEquals("directory", discover.get("mode").asString)
        assertEquals(1, discover.getAsJsonArray("candidates").size())
        assertTrue(discover.has("truncated"))
        assertTrue(discover.has("stopReason"))
        assertTrue(discover.has("skipped"))
        assertTrue(discover.has("limits"))
        assertTrue(discover.has("bytesRead"))
    }

    @Test
    fun fileDiscoverApkSupportsSingleFileAndOutsideRootFlag() {
        val root = temporaryFolder.newFolder("single-root")
        val outsideDir = temporaryFolder.newFolder("outside")
        val outside = File(outsideDir, "outside-demo.apk.1")
        apkLike(outside)
        val toolkit = McpFileToolkit(root)

        val inside = data(toolkit.call("file_discover_apk", obj(
            "path" to ".",
            "recursive" to false,
        )))
        assertEquals(0, inside.getAsJsonArray("candidates").size())

        val single = data(toolkit.call("file_discover_apk", obj(
            "path" to outside.absolutePath,
            "recursive" to false,
            "allowOutsideRoot" to true,
        )))
        assertEquals("file", single.get("mode").asString)
        assertTrue(single.get("candidate").asBoolean)
        assertTrue(single.get("outsideAllowedRoot").asBoolean)

        val rejected = runCatching {
            toolkit.call("file_discover_apk", obj("path" to outside.absolutePath, "recursive" to false))
        }.exceptionOrNull()
        assertNotNull(rejected)
        assertTrue(rejected is SecurityException)
    }
}
