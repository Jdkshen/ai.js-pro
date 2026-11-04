package com.jdkshen.aijspro.mcp

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * P0-B 路径访问诊断单测：显式后端、原始 errno、允许根、scoped storage 启发式、
 * 可见/不可见/不确定的判定，以及 app 与 shizuku 的对比结论。
 */
class ToolkitPathDiagnosticsTest {
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

    private fun appProbe(
        exists: Boolean? = false,
        errno: Int? = null,
        executed: Boolean = true,
        uid: Long = 10123L,
    ): ToolkitPathDiagnostics.Probe = ToolkitPathDiagnostics.Probe(
        backend = ToolkitPathDiagnostics.BACKEND_APP,
        executed = executed,
        available = true,
        uid = uid,
        errno = errno,
        errnoName = if (errno == null) null else ToolkitPathDiagnostics.errnoName(errno),
        exists = exists,
        type = if (exists == true) "file" else null,
    )

    private fun shizukuProbe(
        exists: Boolean?,
        executed: Boolean = true,
        available: Boolean = true,
        uid: Long = 2000L,
    ): ToolkitPathDiagnostics.Probe = ToolkitPathDiagnostics.Probe(
        backend = ToolkitPathDiagnostics.BACKEND_SHIZUKU,
        executed = executed,
        available = available,
        uid = uid,
        exists = exists,
        type = if (exists == true) "file" else null,
        notes = listOf("shizuku 后端没有原始 errno"),
    )

    @Test
    fun errnoNamesMatchLinuxValues() {
        assertEquals("EPERM", ToolkitPathDiagnostics.errnoName(1))
        assertEquals("ENOENT", ToolkitPathDiagnostics.errnoName(2))
        assertEquals("EACCES", ToolkitPathDiagnostics.errnoName(13))
        assertEquals("ENOTDIR", ToolkitPathDiagnostics.errnoName(20))
        assertEquals("ELOOP", ToolkitPathDiagnostics.errnoName(40))
        assertNull(ToolkitPathDiagnostics.errnoName(9999))
    }

    @Test
    fun resolveRequestedKeepsRootContainmentAndOutsideWarning() {
        val root = temporaryFolder.newFolder("root")
        val nested = File(root, "a/b").apply { mkdirs() }
        val relative = ToolkitPathDiagnostics.resolveRequested(root, "a/b")
        assertTrue(relative.insideAllowedRoot)
        assertEquals(nested.absoluteFile, relative.path.absoluteFile)
        val absoluteInside = ToolkitPathDiagnostics.resolveRequested(root, nested.absolutePath)
        assertTrue(absoluteInside.insideAllowedRoot)
        val outside = File(temporaryFolder.root, "outside.txt")
        val outsideRequest = ToolkitPathDiagnostics.resolveRequested(root, outside.absolutePath)
        assertFalse(outsideRequest.insideAllowedRoot)
        assertTrue(outsideRequest.warnings.isNotEmpty())

        assertTrue(runCatching { ToolkitPathDiagnostics.resolveRequested(root, "") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { ToolkitPathDiagnostics.resolveRequested(root, "a\u0000b") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { ToolkitPathDiagnostics.resolveRequested(root, "https://example.com/x") }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun scopedStorageHeuristicOnlyFlagsOtherAppsAndroidData() {
        val other = ToolkitPathDiagnostics.scopedStorageAssessment(
            "/storage/emulated/0/Android/data/com.other.app/cache/demo.apk.1", 30, "com.jdkshen.aijspro")
        assertTrue(other.likely)
        assertTrue(other.reasons.isNotEmpty())
        val own = ToolkitPathDiagnostics.scopedStorageAssessment(
            "/storage/emulated/0/Android/data/com.jdkshen.aijspro/cache/demo.apk.1", 30, "com.jdkshen.aijspro")
        assertFalse(own.likely)
        val normal = ToolkitPathDiagnostics.scopedStorageAssessment(
            "/storage/emulated/0/MT2/apks/demo.apk.1", 30, "com.jdkshen.aijspro")
        assertFalse(normal.likely)
        val oldSdk = ToolkitPathDiagnostics.scopedStorageAssessment(
            "/storage/emulated/0/Android/data/com.other.app/x", 29, "com.jdkshen.aijspro")
        assertFalse(oldSdk.likely)
    }

    @Test
    fun appEnoentWithShizukuVisibleIsInvisibleToApp() {
        val decision = ToolkitPathDiagnostics.decide(
            ToolkitPathDiagnostics.BACKEND_APP,
            appProbe(exists = false, errno = 2),
            shizukuProbe(exists = true),
            shizukuAvailable = true,
            compareWithShizuku = true,
        )
        assertEquals(ToolkitPathDiagnostics.INVISIBLE_TO_APP, decision.conclusion)
        assertTrue(decision.distinguishable)
        assertNull(decision.uncertainty)
        assertTrue(decision.visibleTo.contains(ToolkitPathDiagnostics.BACKEND_SHIZUKU))
        assertTrue(decision.invisibleTo.contains(ToolkitPathDiagnostics.BACKEND_APP))
    }

    @Test
    fun appEnoentWithoutSecondIdentityIsUncertain() {
        val decision = ToolkitPathDiagnostics.decide(
            ToolkitPathDiagnostics.BACKEND_APP,
            appProbe(exists = false, errno = 2),
            null,
            shizukuAvailable = true,
            compareWithShizuku = false,
        )
        assertEquals(ToolkitPathDiagnostics.UNCERTAIN, decision.conclusion)
        assertFalse(decision.distinguishable)
        assertNotNull(decision.uncertainty)
    }

    @Test
    fun appEnoentWithShizukuAlsoInvisibleStaysUncertain() {
        val decision = ToolkitPathDiagnostics.decide(
            ToolkitPathDiagnostics.BACKEND_APP,
            appProbe(exists = false, errno = 2),
            shizukuProbe(exists = false),
            shizukuAvailable = true,
            compareWithShizuku = true,
        )
        assertEquals(ToolkitPathDiagnostics.UNCERTAIN, decision.conclusion)
        assertTrue(decision.distinguishable)
        assertNotNull(decision.uncertainty)
    }

    @Test
    fun accessibleAndPermissionDeniedAreExplicit() {
        val accessible = ToolkitPathDiagnostics.decide(
            ToolkitPathDiagnostics.BACKEND_APP,
            appProbe(exists = true),
            null,
            shizukuAvailable = false,
            compareWithShizuku = false,
        )
        assertEquals(ToolkitPathDiagnostics.ACCESSIBLE, accessible.conclusion)
        val denied = ToolkitPathDiagnostics.decide(
            ToolkitPathDiagnostics.BACKEND_APP,
            appProbe(exists = false, errno = 13),
            null,
            shizukuAvailable = false,
            compareWithShizuku = false,
        )
        assertEquals(ToolkitPathDiagnostics.PERMISSION_DENIED, denied.conclusion)
        assertNotNull(denied.uncertainty)
    }

    @Test
    fun shizukuBackendIsExplicitAndNeverFallsBack() {
        val unavailable = ToolkitPathDiagnostics.decide(
            ToolkitPathDiagnostics.BACKEND_SHIZUKU,
            null,
            null,
            shizukuAvailable = false,
            compareWithShizuku = false,
        )
        assertEquals(ToolkitPathDiagnostics.BACKEND_UNAVAILABLE, unavailable.conclusion)
        assertNotNull(unavailable.uncertainty)

        val accessible = ToolkitPathDiagnostics.decide(
            ToolkitPathDiagnostics.BACKEND_SHIZUKU,
            null,
            shizukuProbe(exists = true),
            shizukuAvailable = true,
            compareWithShizuku = false,
        )
        assertEquals(ToolkitPathDiagnostics.ACCESSIBLE, accessible.conclusion)

        val uncertain = ToolkitPathDiagnostics.decide(
            ToolkitPathDiagnostics.BACKEND_SHIZUKU,
            null,
            shizukuProbe(exists = false),
            shizukuAvailable = true,
            compareWithShizuku = false,
        )
        assertEquals(ToolkitPathDiagnostics.UNCERTAIN, uncertain.conclusion)
        assertNotNull(uncertain.uncertainty)
    }

    @Test
    fun appProbeNotExecutedIsUncertain() {
        val decision = ToolkitPathDiagnostics.decide(
            ToolkitPathDiagnostics.BACKEND_APP,
            appProbe(executed = false, exists = null),
            null,
            shizukuAvailable = false,
            compareWithShizuku = false,
        )
        assertEquals(ToolkitPathDiagnostics.UNCERTAIN, decision.conclusion)
        assertNotNull(decision.uncertainty)
    }

    @Test
    fun parseShizukuProbeReadsMachineLines() {
        val output = "probe=1\nuid=2000\nexists=1\ntype=file\nsize=12345\nmtime=1700000000\nreadable=1\nwritable=0\n"
        val probe = ToolkitPathDiagnostics.parseShizukuProbe(output)
        assertTrue(probe.executed)
        assertEquals(2000L, probe.uid ?: -1L)
        assertEquals(true, probe.exists)
        assertEquals("file", probe.type)
        assertEquals(12345L, probe.size ?: -1L)
        assertEquals(1700000000000L, probe.modifiedTime ?: -1L)
        assertEquals(true, probe.readable)
        assertEquals(false, probe.writable)
        val missing = ToolkitPathDiagnostics.parseShizukuProbe("garbage output")
        assertFalse(missing.executed)
        assertNotNull(missing.notes)
    }

    @Test
    fun shellQuoteAndScriptKeepPathSafe() {
        assertEquals("'a'\\''b'", ToolkitPathDiagnostics.shellQuote("a'b"))
        val script = ToolkitPathDiagnostics.shizukuProbeScript("/tmp/a b'c")
        assertTrue(script.startsWith("set -u"))
        assertTrue(script.contains("probe=1"))
        assertTrue(script.contains("exists=1"))
        assertTrue(script.contains("'/tmp/a b'\\''c'"))
        assertFalse(script.contains("\u0000"))
    }

    @Test
    fun reportCarriesRequiredDiagnosticFields() {
        val root = temporaryFolder.newFolder("report-root")
        val target = File(root, "demo.apk.1").apply { writeText("x") }
        val report = ToolkitPathDiagnostics.report(
            root = root,
            requested = target.absolutePath,
            requestedBackend = ToolkitPathDiagnostics.BACKEND_APP,
            compareWithShizuku = false,
            appProbe = appProbe(exists = true),
            shizukuProbe = null,
            shizukuAvailable = false,
            shizukuAvailabilityKnown = true,
            sdkInt = 34,
            appPackage = "com.jdkshen.aijspro",
        )
        assertEquals(ToolkitPathDiagnostics.BACKEND_APP, report.get("backend").asString)
        assertEquals(ToolkitPathDiagnostics.ACCESSIBLE, report.get("conclusion").asString)
        assertTrue(report.get("insideAllowedRoot").asBoolean)
        assertFalse(report.get("scopedStorageLikely").asBoolean)
        assertFalse(report.get("shizukuExecuted").asBoolean)
        assertTrue(report.get("shizukuAvailabilityKnown").asBoolean)
        assertEquals(10123L, report.get("uid").asLong)
        assertNotNull(report.get("canonicalPath"))
        assertEquals(1, report.getAsJsonArray("executedBackends").size())

        val badBackend = runCatching {
            ToolkitPathDiagnostics.report(
                root, target.absolutePath, "root", false, appProbe(exists = true), null,
                false, true, 34, "com.jdkshen.aijspro")
        }.exceptionOrNull()
        assertTrue(badBackend is IllegalArgumentException)
    }

    @Test
    fun reportFlagsOutsideAllowedRootAndUncertainty() {
        val root = temporaryFolder.newFolder("root")
        val outside = File(temporaryFolder.root, "outside-demo.apk.1").apply { writeText("x") }
        val report = ToolkitPathDiagnostics.report(
            root = root,
            requested = outside.absolutePath,
            requestedBackend = ToolkitPathDiagnostics.BACKEND_APP,
            compareWithShizuku = false,
            appProbe = appProbe(exists = false, errno = 2),
            shizukuProbe = null,
            shizukuAvailable = true,
            shizukuAvailabilityKnown = true,
            sdkInt = 34,
            appPackage = "com.jdkshen.aijspro",
        )
        assertFalse(report.get("insideAllowedRoot").asBoolean)
        assertEquals(ToolkitPathDiagnostics.UNCERTAIN, report.get("conclusion").asString)
        assertNotNull(report.get("uncertainty"))
        val notes = report.getAsJsonArray("notes")
        assertTrue(notes.any { it.asString.contains("允许目录") })
    }

    @Test
    fun providerRegistryWiresToolkitDiagnosticsAndResets() {
        val root = temporaryFolder.newFolder("provider-root")
        val toolkit = McpFileToolkit(root)
        val missing = runCatching {
            toolkit.call("file_access_diagnose", obj("path" to ".", "backend" to "app"))
        }.exceptionOrNull()
        assertTrue(missing is ToolkitException)
        assertEquals("BACKEND_UNAVAILABLE", (missing as ToolkitException).code)
        assertNull(ToolkitPathDiagnostics.activeProvider())

        try {
            ToolkitPathDiagnostics.installProvider { _, _, _, _ ->
                JsonObject().apply {
                    addProperty("marker", "fake-backend")
                    addProperty("conclusion", "UNCERTAIN")
                }
            }
            assertNotNull(ToolkitPathDiagnostics.activeProvider())
            val result = toolkit.call("file_access_diagnose", obj("path" to ".", "backend" to "app"))
            assertEquals("fake-backend", data(result).get("marker").asString)
            val badBackend = runCatching {
                toolkit.call("file_access_diagnose", obj("path" to ".", "backend" to "root"))
            }.exceptionOrNull()
            assertTrue(badBackend is IllegalArgumentException)
            val noBackend = runCatching {
                toolkit.call("file_access_diagnose", obj("path" to "."))
            }.exceptionOrNull()
            assertTrue(noBackend is IllegalArgumentException)
        } finally {
            ToolkitPathDiagnostics.resetProviderForTests()
        }
        assertNull(ToolkitPathDiagnostics.activeProvider())
    }
}
