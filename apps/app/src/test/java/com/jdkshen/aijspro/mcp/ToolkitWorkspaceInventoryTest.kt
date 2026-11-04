package com.jdkshen.aijspro.mcp

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * task-250 / BUG-2：工作区清单、配额、受控 GC、会话释放的纯 JVM 测试。
 * 不启动 Android、不运行 gradle；由 Lead 统一编译执行。
 */
class ToolkitWorkspaceInventoryTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var store: File
    private lateinit var inventory: ToolkitWorkspaceInventory
    private val now = System.currentTimeMillis()

    private fun init() {
        store = temp.newFolder("store")
        inventory = ToolkitWorkspaceInventory(store)
    }

    private fun workspace(id: String, temporary: Boolean, createdAt: Long, baseBytes: Int,
                          owner: String? = null, ownerSource: String? = null, clientId: String? = null, sessionId: String? = null,
                          basePresent: Boolean = true, sessions: Int = 0): File {
        val dir = File(store, id)
        assertTrue(dir.mkdirs())
        val info = ToolkitIo.json(
            "workspaceId" to id,
            "temporary" to temporary,
            "size" to baseBytes.toLong(),
            "createdAt" to createdAt,
            "sourcePath" to ("/probe/" + id + ".apk"),
            "sha256" to ("sha-" + id),
        )
        if (owner != null) info.addProperty("owner", owner)
        if (ownerSource != null) info.addProperty("ownerSource", ownerSource)
        if (clientId != null) info.addProperty("clientId", clientId)
        if (sessionId != null) info.addProperty("sessionId", sessionId)
        File(dir, "workspace.json").writeText(info.toString(), Charsets.UTF_8)
        if (basePresent) File(dir, "base.apk").writeBytes(ByteArray(baseBytes) { it.toByte() })
        if (sessions > 0) {
            repeat(sessions) { index ->
                val sessionDir = File(File(dir, "sessions"), "session-" + index)
                assertTrue(sessionDir.mkdirs())
                File(sessionDir, "session.json").writeText(
                    ToolkitIo.json("workspaceId" to id, "editSessionId" to ("session-" + index)).toString(),
                    Charsets.UTF_8)
            }
        }
        return dir
    }

    @Test fun inventoryExplainsQuotaStatesAndIntegrityGaps() = init().let {
        workspace("id-saved", false, now - 3 * 60_000L, 7, owner = null)
        workspace("id-temp-recent", true, now - 60_000L, 3)
        workspace("id-temp-old", true, now - 2 * 24 * 60 * 60_000L, 5, owner = "ownerB", clientId = "clientB")
        workspace("id-edit", true, now - 2 * 24 * 60 * 60_000L, 4, sessions = 1)
        workspace("id-dangling", true, now - 60_000L, 0, basePresent = false)
        File(store, "orphan-x").mkdirs()
        val bad = File(store, "bad-json"); bad.mkdirs(); File(bad, "workspace.json").writeText("{not json", Charsets.UTF_8)
        val running = File(File(store, "jobs"), "running.json"); running.parentFile.mkdirs()
        running.writeText(ToolkitIo.json("jobId" to "job-1", "state" to "RUNNING").toString(), Charsets.UTF_8)
        val active = ToolkitWorkspaceInventory.ActiveReferences(
            currentWorkspaceId = "id-temp-recent",
            pageReferences = mapOf("id-temp-old" to 1),
        )
        val data = inventory.inventory(ToolkitIo.json("expiredAfterMinutes" to 60), active)
        assertEquals(16, data.get("quota").asInt)
        assertEquals(5, data.get("totalCount").asInt)
        assertTrue(data.get("totalBytes").asLong > 0L)
        assertEquals(5L + 4L + 7L + 3L + 0L, data.get("totalDeclaredBytes").asLong)
        assertEquals(11, data.get("quotaRemaining").asInt)
        assertEquals(1, data.getAsJsonObject("byState").get("saved").asInt)
        assertEquals(1, data.getAsJsonObject("byState").get("temporary").asInt)
        assertEquals(2, data.getAsJsonObject("byState").get("expired").asInt)
        assertEquals(1, data.getAsJsonObject("byState").get("dangling").asInt)
        assertEquals(1, data.get("activeJobs").asInt)
        assertEquals(1, data.get("orphanCount").asInt)
        assertTrue(data.getAsJsonArray("orphanDirs").any { it.asJsonObject.get("directory").asString == "orphan-x" })
        assertEquals(2, data.get("danglingCount").asInt)
        assertEquals(2, data.get("activeReferenceCount").asInt) // temp-recent current + temp-old one page
        val items = data.getAsJsonArray("items").associateBy { it.asJsonObject.tString("workspaceId") }
            .mapValues { it.value.asJsonObject }
        assertEquals("saved", items.getValue("id-saved").get("state").asString)
        assertEquals("temporary", items.getValue("id-temp-recent").get("state").asString)
        assertEquals("expired", items.getValue("id-temp-old").get("state").asString)
        assertEquals("expired", items.getValue("id-edit").get("state").asString)
        assertEquals("dangling", items.getValue("id-dangling").get("state").asString)
        assertEquals("ownerB", items.getValue("id-temp-old").get("owner").asString)
        assertEquals("clientB", items.getValue("id-temp-old").get("clientId").asString)
        assertTrue(items.getValue("id-saved").get("owner").isJsonNull)
        assertTrue(items.getValue("id-saved").get("clientId").isJsonNull)
        assertTrue(items.getValue("id-saved").get("sessionId").isJsonNull)
        assertEquals(1, items.getValue("id-edit").get("sessionCount").asInt)
        assertEquals(1, items.getValue("id-temp-old").get("activeRefCount").asInt)
        assertEquals(1, items.getValue("id-temp-recent").get("activeRefCount").asInt)
        assertTrue(items.getValue("id-temp-old").get("lastAccessAt").asLong > 0L)
        assertEquals("filesystem_mtime", items.getValue("id-saved").get("lastAccessSource").asString)
        val warning = data.getAsJsonArray("warnings").map { it.asString }.joinToString(" ")
        assertTrue(warning.contains("activeJobs=1"))
    }

    @Test fun gcDryRunDoesNotDeleteAndProtectsActiveSavedSessionAndOwner() = init().let {
        workspace("id-saved", false, now - 60 * 60_000L, 7)
        workspace("id-temp-old", true, now - 2 * 24 * 60 * 60_000L, 5, owner = "ownerB", clientId = "clientB")
        workspace("id-edit", true, now - 2 * 24 * 60 * 60_000L, 4, sessions = 1)
        workspace("id-temp-active", true, now - 2 * 24 * 60 * 60_000L, 3)
        val active = ToolkitWorkspaceInventory.ActiveReferences(
            currentWorkspaceId = "id-saved",
            pageReferences = mapOf("id-temp-active" to 1),
        )
        val data = inventory.gc(ToolkitIo.json(
            "dryRun" to true,
            "includeUnknownOwner" to true,
            "minAgeMinutes" to 0,
            "expiredAfterMinutes" to 60,
        ), active)
        assertTrue(data.get("dryRun").asBoolean)
        assertEquals(0, data.get("deletedCount").asInt)
        assertFalse(data.get("applied").asBoolean)
        assertTrue(File(store, "id-temp-old").isDirectory)
        val skipped = data.getAsJsonArray("skipped").map { it.asJsonObject }
        val old = skipped.first { it.get("workspaceId").asString == "id-temp-old" }
        assertTrue(old.get("protectedReasons").asJsonArray.any { it.asString == "other_owner" })
        val edit = skipped.first { it.get("workspaceId").asString == "id-edit" }
        assertTrue(edit.get("protectedReasons").asJsonArray.any { it.asString == "edit_session" })
        val activeRow = skipped.first { it.get("workspaceId").asString == "id-temp-active" }
        assertTrue(activeRow.get("protectedReasons").asJsonArray.any { it.asString == "active_reference" })
        val saved = skipped.first { it.get("workspaceId").asString == "id-saved" }
        assertTrue(saved.get("protectedReasons").asJsonArray.any { it.asString == "saved_workspace" })
    }

    @Test fun gcActualDeletesOnlyEligibleTemporaryAndReportsOrphans() = init().let {
        workspace("id-saved", false, now - 60 * 60_000L, 7)
        workspace("id-old", true, now - 2 * 24 * 60 * 60_000L, 5)
        workspace("id-other", true, now - 2 * 24 * 60 * 60_000L, 4, owner = "other", clientId = "other-client")
        workspace("id-edit", true, now - 2 * 24 * 60 * 60_000L, 3, sessions = 1)
        workspace("id-dangling", true, now - 2 * 24 * 60 * 60_000L, 0, basePresent = false)
        File(store, "orphan-y").mkdirs()
        val data = inventory.gc(ToolkitIo.json(
            "dryRun" to false,
            "includeUnknownOwner" to true,
            "minAgeMinutes" to 0,
            "expiredAfterMinutes" to 60,
        ), ToolkitWorkspaceInventory.ActiveReferences())
        assertEquals(1, data.get("deletedCount").asInt)
        assertTrue(data.getAsJsonArray("deletedIds").any { it.asString == "id-old" })
        assertTrue(data.get("reclaimedBytes").asLong > 0L)
        assertFalse(File(store, "id-old").exists())
        assertTrue(File(store, "id-saved").isDirectory)
        assertTrue(File(store, "id-other").isDirectory)
        assertTrue(File(store, "id-edit").isDirectory)
        assertTrue(File(store, "id-dangling").isDirectory)
        assertTrue(File(store, "orphan-y").isDirectory)
        assertEquals(0, data.getAsJsonArray("failures").size())
        val skipped = data.getAsJsonArray("skipped").map { it.asJsonObject }
        assertTrue(skipped.first { it.get("workspaceId").asString == "id-other" }
            .get("protectedReasons").asJsonArray.any { it.asString == "other_owner" })
        assertTrue(skipped.first { it.get("workspaceId").asString == "id-edit" }
            .get("protectedReasons").asJsonArray.any { it.asString == "edit_session" })
        assertTrue(skipped.first { it.get("workspaceId").asString == "id-dangling" }
            .get("protectedReasons").asJsonArray.any { it.asString == "dangling" })
        assertEquals(1, data.getAsJsonArray("orphanDirs").size())
    }

    @Test fun activeJobWithoutWorkspaceMappingProtectsTemporaryWorkspaces() = init().let {
        workspace("id-old", true, now - 2 * 24 * 60 * 60_000L, 5)
        val running = File(File(store, "jobs"), "running.json"); running.parentFile.mkdirs()
        running.writeText(ToolkitIo.json("jobId" to "job-2", "state" to "RUNNING").toString(), Charsets.UTF_8)
        val data = inventory.gc(ToolkitIo.json(
            "dryRun" to false, "includeUnknownOwner" to true, "minAgeMinutes" to 0,
            "expiredAfterMinutes" to 60,
        ), ToolkitWorkspaceInventory.ActiveReferences())
        assertEquals(0, data.get("deletedCount").asInt)
        assertTrue(File(store, "id-old").isDirectory)
        val row = data.getAsJsonArray("skipped").map { it.asJsonObject }
            .first { it.get("workspaceId").asString == "id-old" }
        assertTrue(row.get("protectedReasons").asJsonArray.any {
            it.asString == "active_jobs_workspace_unknown"
        })
    }

    @Test fun protectedIdsAndOwnerFilterAreHonored() = init().let {
        workspace("id-legacy", true, now - 2 * 24 * 60 * 60_000L, 5)
        workspace("id-owned", true, now - 2 * 24 * 60 * 60_000L, 4, owner = "ownerA", clientId = "clientA")
        val protected = inventory.gc(ToolkitIo.json(
            "dryRun" to true, "includeUnknownOwner" to true, "minAgeMinutes" to 0,
            "protectedIds" to listOf("id-legacy"), "expiredAfterMinutes" to 60,
        ), ToolkitWorkspaceInventory.ActiveReferences())
        val protectedRow = protected.getAsJsonArray("skipped").map { it.asJsonObject }
            .first { it.get("workspaceId").asString == "id-legacy" }
        assertTrue(protectedRow.get("protectedReasons").asJsonArray.any { it.asString == "protected_ids" })
        val owned = inventory.gc(ToolkitIo.json(
            "dryRun" to true, "minAgeMinutes" to 0, "owner" to "ownerA", "clientId" to "clientA",
            "expiredAfterMinutes" to 60,
        ), ToolkitWorkspaceInventory.ActiveReferences())
        val planned = owned.getAsJsonArray("planned").map { it.asJsonObject }
        assertTrue(planned.any { it.get("workspaceId").asString == "id-owned" })
        val legacy = owned.getAsJsonArray("skipped").map { it.asJsonObject }
            .first { it.get("workspaceId").asString == "id-legacy" }
        assertTrue(legacy.get("protectedReasons").asJsonArray.any { it.asString == "unknown_owner" })
    }

    @Test fun gcRelaxedExplicitReleaseDeletesExpiredUnknownOwnerAndEditSessions() {
        init()
        workspace("id-expired", true, now - 2 * 24 * 60 * 60_000L, 5, sessions = 2)
        workspace("id-saved", false, now - 60 * 60_000L, 7)
        workspace("id-current", true, now - 2 * 24 * 60 * 60_000L, 3)
        workspace("id-page", true, now - 2 * 24 * 60 * 60_000L, 4)
        val active = ToolkitWorkspaceInventory.ActiveReferences(
            currentWorkspaceId = "id-current",
            pageReferences = mapOf("id-page" to 1),
        )
        val before = inventory.inventory(ToolkitIo.json("expiredAfterMinutes" to 60), active)
        val expired = before.getAsJsonArray("items").map { it.asJsonObject }
            .first { it.get("workspaceId").asString == "id-expired" }
        assertTrue(expired.get("pendingCleanup").asBoolean)
        assertEquals(2, expired.get("sessionCount").asInt)

        val result = inventory.gc(ToolkitIo.json(
            "dryRun" to false,
            "includeUnknownOwner" to true,
            "includeTemporary" to true,
            "minAgeMinutes" to 0,
            "expiredAfterMinutes" to 60,
        ), active)
        assertEquals(1, result.get("deletedCount").asInt)
        assertTrue(result.getAsJsonArray("deletedIds").any { it.asString == "id-expired" })
        assertEquals(2, result.get("wouldReleaseEditSessions").asInt)
        assertEquals(2, result.get("releasedEditSessions").asInt)
        assertFalse(File(store, "id-expired").exists())
        assertTrue(File(store, "id-saved").isDirectory)
        assertTrue(File(store, "id-current").isDirectory)
        assertTrue(File(store, "id-page").isDirectory)
        val skipped = result.getAsJsonArray("skipped").map { it.asJsonObject }
        assertTrue(skipped.first { it.get("workspaceId").asString == "id-saved" }
            .get("protectedReasons").asJsonArray.any { it.asString == "saved_workspace" })
        assertTrue(skipped.first { it.get("workspaceId").asString == "id-current" }
            .get("protectedReasons").asJsonArray.any { it.asString == "current_workspace" })
        assertTrue(skipped.first { it.get("workspaceId").asString == "id-page" }
            .get("protectedReasons").asJsonArray.any { it.asString == "active_reference" })
    }

    @Test fun serverDefaultOwnerIsVisibleAndEligibleUnderExplicitGc() {
        init()
        workspace("id-server", true, now - 2 * 24 * 60 * 60_000L, 3,
            owner = "mcp-host", ownerSource = "server-default")
        val item = inventory.inventory(ToolkitIo.json("expiredAfterMinutes" to 60))
            .getAsJsonArray("items").map { it.asJsonObject }
            .first { it.get("workspaceId").asString == "id-server" }
        assertEquals("mcp-host", item.get("owner").asString)
        assertEquals("server-default", item.get("ownerSource").asString)
        val result = inventory.gc(ToolkitIo.json(
            "dryRun" to false, "includeUnknownOwner" to true,
            "includeTemporary" to true, "minAgeMinutes" to 0, "expiredAfterMinutes" to 60,
        ), ToolkitWorkspaceInventory.ActiveReferences())
        assertTrue(result.getAsJsonArray("deletedIds").any { it.asString == "id-server" })
    }

    @Test fun sessionReleaseCountsBytesRemainingAndIsIdempotent() {
        init()
        workspace("id-rel", true, now, 4, sessions = 2)
        workspace("id-rel2", true, now, 2, sessions = 1)
        val home = temp.newFolder("release-home")
        val toolkit = McpApkToolkit(home, store, JsonObject())

        val first = toolkit.call("apk_session_release",
            ToolkitIo.json("workspaceId" to "id-rel")).getAsJsonObject("data")
        assertEquals(2, first.get("releasedSessions").asInt)
        assertTrue(first.get("releasedBytes").asLong > 0L)
        assertEquals(1, first.get("remainingSessions").asInt)
        assertFalse(File(store, "id-rel/sessions/session-0").exists())
        assertTrue(File(store, "id-rel/base.apk").isFile)

        val again = toolkit.call("apk_session_release",
            ToolkitIo.json("workspaceId" to "id-rel")).getAsJsonObject("data")
        assertEquals(0, again.get("releasedSessions").asInt)
        assertEquals(0, again.get("releasedBytes").asLong)
        assertTrue(again.get("idempotent").asBoolean)

        val all = toolkit.call("apk_session_release",
            ToolkitIo.json("all" to true)).getAsJsonObject("data")
        assertEquals(1, all.get("releasedSessions").asInt)
        assertEquals(0, all.get("remainingSessions").asInt)
    }

    @Test fun quotaErrorContainsExecutableInventoryGcDeleteAndReleaseCommands() {
        init()
        repeat(16) { workspace(ToolkitIo.fresh(), true, now, 1) }
        val home = temp.newFolder("quota-home")
        File(home, "dummy.dex").writeBytes(byteArrayOf(1, 2, 3))
        val toolkit = McpApkToolkit(home, store, JsonObject())
        val error = runCatching {
            toolkit.call("artifact_open", ToolkitIo.json("path" to "dummy.dex"))
        }.exceptionOrNull()
        assertNotNull(error)
        val message = error?.message.orEmpty()
        for (marker in listOf(
            "apk_workspace_inventory",
            "apk_workspace_gc",
            "\"dryRun\":false",
            "includeUnknownOwner",
            "includeTemporary",
            "minAgeMinutes",
            "apk_workspace_delete",
            "apk_session_release",
            "owner=null",
        )) {
            assertTrue("quota hint missing " + marker + ": " + message, message.contains(marker))
        }
    }

    @Test fun openWritesServerDefaultOwnerAndOwnerSource() {
        init()
        val home = temp.newFolder("owner-home")
        val source = File(home, "fixture.apk")
        ZipOutputStream(source.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write("<manifest package=\"com.example.fixture\"/>".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(byteArrayOf(0x64, 0x65, 0x78, 0x0a, 0x30, 0x33, 0x35, 0x00))
            zip.closeEntry()
        }
        val toolkit = McpApkToolkit(home, store, JsonObject())
        val opened = toolkit.call("apk_workspace_open",
            ToolkitIo.json("path" to source.name, "temporary" to true)).getAsJsonObject("data")
        val id = opened.get("workspaceId").asString
        val info = JsonParser().parse(File(File(store, id), "workspace.json").readText()).asJsonObject
        assertEquals("mcp-host", info.get("owner").asString)
        assertEquals("server-default", info.get("ownerSource").asString)
        assertFalse(info.has("clientId"))
    }
}
