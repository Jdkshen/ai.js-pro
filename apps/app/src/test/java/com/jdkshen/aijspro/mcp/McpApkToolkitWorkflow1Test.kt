package com.jdkshen.aijspro.mcp

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.jf.smali.Smali
import org.jf.smali.SmaliOptions
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** W1: P0-1 expiry diagnostics/idempotent reopen + P0-3 search budget + decompile outPath. */
class McpApkToolkitWorkflow1Test {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var home: File
    private lateinit var store: File
    private lateinit var tools: McpApkToolkit
    private lateinit var source: File

    private fun args(vararg pairs: Pair<String, Any?>): JsonObject = ToolkitIo.json(*pairs)

    private fun openArtifact(): JsonObject =
        tools.call("artifact_open", args("path" to source.name, "temporary" to true)).getAsJsonObject("data")

    private fun failure(block: () -> Unit): ToolkitException {
        try {
            block()
        } catch (error: ToolkitException) {
            return error
        }
        fail("expected ToolkitException")
        throw AssertionError("unreachable")
    }

    @Before
    fun setup() {
        home = temp.newFolder("home")
        store = temp.newFolder("private")
        val schema = listOf(
            File("src/main/assets/mcp/toolkit-apk-schemas.json"),
            File("apps/app/src/main/assets/mcp/toolkit-apk-schemas.json")
        ).first { it.isFile }
        tools = McpApkToolkit(
            home, store, JsonParser().parse(schema.readText()).asJsonObject,
            runtime = FakeDecompileRuntime(),
        )
        val smali = temp.newFile("Fake.smali").apply {
            writeText("""
                .class public Lfixture/Fake;
                .super Ljava/lang/Object;
                .method public static value()I
                    .registers 1
                    const/4 v0, 0x1
                    return v0
                .end method
            """.trimIndent())
        }
        val dex = File(temp.root, "classes.dex")
        assertTrue(Smali.assemble(SmaliOptions().apply {
            outputDexFile = dex.path
            jobs = 1
        }, listOf(smali.path)))
        source = File(home, "fixture.apk")
        ZipOutputStream(source.outputStream()).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            put("AndroidManifest.xml", "<manifest package=\"com.example.workflow1\"/>")
            put("assets/only.txt", "no target here")
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(dex.readBytes())
            zip.closeEntry()
        }
    }

    @Test
    fun expiredWorkspaceErrorCarriesTtlRecoveryQuotaAndDetails() {
        val workspace = ToolkitIo.fresh()
        val error = failure {
            tools.call("apk_read_text", args(
                "workspaceId" to workspace,
                "locator" to "zip_entry:AndroidManifest.xml",
            ))
        }
        assertEquals("WORKSPACE_EXPIRED", error.code)
        val message = error.message.orEmpty()
        assertTrue(message, message.contains("workspaceId=" + workspace))
        assertTrue(message, message.contains("ttlRemainingMs=null"))
        assertTrue(message, message.contains("expiredAt=null"))
        assertTrue(message, message.contains("recovery="))
        assertTrue(message, message.contains("quota="))
        assertTrue(message, message.contains("details="))
        assertTrue(message, message.contains("apk_workspace_inventory"))
    }

    @Test
    fun expiredWorkspaceWithMissingSnapshotCarriesTtlAndReason() {
        val workspace = openArtifact().tString("workspaceId")
        val workspaceFile = File(store, workspace).resolve("workspace.json")
        val info = JsonParser().parse(workspaceFile.readText()).asJsonObject
        info.addProperty("createdAt", System.currentTimeMillis() - 25L * 60 * 60 * 1000)
        workspaceFile.writeText(Gson().toJson(info))
        assertTrue(File(store, workspace).resolve("base.apk").delete())

        val error = failure {
            tools.call("apk_read_text", args(
                "workspaceId" to workspace,
                "locator" to "zip_entry:AndroidManifest.xml",
            ))
        }
        assertEquals("WORKSPACE_EXPIRED", error.code)
        val message = error.message.orEmpty()
        assertTrue(message, message.contains("ttlRemainingMs=0"))
        assertTrue(message, message.contains("expiredAt="))
        assertTrue(message, message.contains("snapshot_missing"))
    }

    @Test
    fun missingSessionIsSessionExpiredAndCarriesExpiredAt() {
        val opened = openArtifact()
        val workspace = opened.tString("workspaceId")
        val session = tools.call("apk_session_create", args("workspaceId" to workspace))
            .getAsJsonObject("data").tString("editSessionId")

        // Simulate an expired temporary workspace (default TTL is 24h) and a released/missing
        // session directory. The error must be SESSION_EXPIRED, not INVALID_ARGUMENT.
        val workspaceFile = File(store, workspace).resolve("workspace.json")
        val info = JsonParser().parse(workspaceFile.readText()).asJsonObject
        info.addProperty("createdAt", System.currentTimeMillis() - 25L * 60 * 60 * 1000)
        workspaceFile.writeText(Gson().toJson(info))
        File(store, workspace).resolve("sessions").resolve(session).deleteRecursively()

        val error = failure {
            tools.call("apk_read_text", args(
                "workspaceId" to workspace,
                "editSessionId" to session,
                "locator" to "zip_entry:assets/only.txt",
            ))
        }
        assertEquals("SESSION_EXPIRED", error.code)
        val message = error.message.orEmpty()
        assertTrue(message, message.contains("sessionId=" + session))
        assertTrue(message, message.contains("ttlRemainingMs=0"))
        assertTrue(message, message.contains("expiredAt="))
        assertTrue(message, message.contains("workspace_ttl_expired"))
        assertTrue(message, message.contains("apk_session_create"))
    }

    @Test
    fun artifactOpenReusesExpiredSnapshotRenewsTtlAndIsIdempotent() {
        val first = openArtifact()
        val workspace = first.tString("workspaceId")

        val workspaceFile = File(store, workspace).resolve("workspace.json")
        val info = JsonParser().parse(workspaceFile.readText()).asJsonObject
        val oldCreatedAt = System.currentTimeMillis() - 25L * 60 * 60 * 1000
        info.addProperty("createdAt", oldCreatedAt)
        workspaceFile.writeText(Gson().toJson(info))

        val reopened = openArtifact()
        assertEquals(workspace, reopened.tString("workspaceId"))
        assertTrue(reopened.get("reused").asBoolean)
        assertTrue(reopened.get("reopened").asBoolean)
        assertEquals("expired_ttl_renewed", reopened.tString("reuseReason"))
        assertTrue(reopened.get("createdAt").asLong > oldCreatedAt)

        val again = openArtifact()
        assertEquals(workspace, again.tString("workspaceId"))
        assertEquals("same_path_sha256_snapshot", again.tString("reuseReason"))
    }

    @Test
    fun reopeningWhenOldSnapshotPayloadIsMissingRebuildsAndMarksReason() {
        val first = openArtifact()
        val workspace = first.tString("workspaceId")
        assertTrue(File(store, workspace).resolve("base.apk").delete())

        val rebuilt = openArtifact()
        assertNotEquals(workspace, rebuilt.tString("workspaceId"))
        assertTrue(rebuilt.get("reopened").asBoolean)
        assertFalse(rebuilt.get("reused").asBoolean)
        assertEquals("previous_snapshot_missing_rebuilt", rebuilt.tString("reuseReason"))
        assertEquals(workspace, rebuilt.tString("previousWorkspaceId"))
    }

    @Test
    fun completeSearchZeroHitsIsMarkedCompleteNotBudget() {
        val workspace = openArtifact().tString("workspaceId")
        val data = tools.call("apk_search", args(
            "workspaceId" to workspace,
            "target" to "files",
            "query" to "definitely-not-present-anywhere",
            "limit" to 20,
        )).getAsJsonObject("data")
        assertEquals("COMPLETE", data.tString("stopReason"))
        assertEquals("COMPLETE_ZERO_HITS", data.tString("zeroResultMeaning"))
        assertTrue(data.get("zeroResult").asBoolean)
        assertEquals(0, data.get("total").asInt)
        assertTrue(data.get("budgetNote").isJsonNull)
    }

    @Test
    fun budgetClassificationDistinguishesCompleteZeroFromBudgetCutZero() {
        val complete = ToolkitApkSearchDiagnostics.classify(
            partial = false,
            elapsedMs = 100,
            hitCount = 0,
            metadata = JsonObject(),
            args = args("target" to "files"),
        )
        assertEquals("COMPLETE", complete.fields.tString("stopReason"))
        assertEquals("COMPLETE_ZERO_HITS", complete.fields.tString("zeroResultMeaning"))
        assertTrue(complete.fields.get("emptyResult").asBoolean)
        assertEquals(0, complete.nextActions.size())

        val budget = ToolkitApkSearchDiagnostics.classify(
            partial = true,
            elapsedMs = 8_000,
            hitCount = 0,
            metadata = ToolkitIo.json(
                "partialTargets" to listOf("dex_names"),
                "notSearchedTargets" to listOf("smali"),
            ),
            args = args("target" to "overview"),
        )
        assertEquals("TIME_BUDGET", budget.fields.tString("stopReason"))
        assertEquals("UNKNOWN_BUDGET_EXHAUSTED", budget.fields.tString("zeroResultMeaning"))
        assertFalse(budget.fields.get("emptyResult").asBoolean)
        assertTrue(budget.fields.tString("budgetNote").contains("不能判为"))
        assertTrue(budget.nextActions.size() > 0)
    }

    @Test
    fun decompileOutPathWritesTheReturnedContentThroughTheToolPath() {
        val workspace = openArtifact().tString("workspaceId")
        File(home, "decomp").mkdirs()
        val data = tools.call("apk_decompile_class", args(
            "workspaceId" to workspace,
            "locator" to "dex_class:Lfixture/Fake;",
            "background" to false,
            "outPath" to "decomp/Fake.java",
            "organize" to false,
        )).getAsJsonObject("data")
        assertEquals("class Fake {}\n", data.tString("content"))
        val saved = File(data.tString("savedPath"))
        assertEquals(File(home, "decomp/Fake.java").canonicalPath, saved.canonicalPath)
        assertEquals("class Fake {}\n", saved.readText())
        assertTrue(data.tString("savedSha256").length == 64)
    }

    @Test
    fun decompileOutPathSchemaAndAtomicWriteAreAvailable() {
        val definition = tools.definitions().map { it.asJsonObject }
            .single { it.tString("name") == "apk_decompile_class" }
        val properties = definition.getAsJsonObject("inputSchema").getAsJsonObject("properties")
        assertNotNull(properties.get("outPath"))
        assertNotNull(properties.get("organize"))

        File(home, "out").mkdirs()
        val organizer = ToolkitOutputOrganizer(store, home)
        val body = "class Dump {\n  int value() { return 1; }\n}"
        val result = ToolkitIo.json("content" to body, "truncated" to false)
        val written = writeDecompiledOutput(
            organizer, home,
            args("outPath" to "out/Dump.java", "organize" to false),
            result,
        )
        val path = File(written.tString("savedPath"))
        assertEquals(File(home, "out/Dump.java").canonicalPath, path.canonicalPath)
        assertEquals(body, path.readText())
        assertEquals(body.length, written.get("savedChars").asInt)
        assertTrue(written.tString("savedSha256").length == 64)
        assertFalse(written.has("organized"))

        val auto = writeDecompiledOutput(
            organizer, home,
            args("outPath" to "Dump2.java", "organize" to true),
            ToolkitIo.json("content" to "class Dump2 {}"),
        )
        assertTrue(auto.has("organized"))
        assertTrue(File(auto.tString("savedPath")).isFile)
    }

    private class FakeDecompileRuntime : ToolkitRuntime {
        override fun capabilities(): JsonObject = ToolkitIo.json("available" to true)
        override fun install(args: JsonObject): JsonObject = JsonObject()
        override fun remove(args: JsonObject): JsonObject = JsonObject()
        override fun compileResources(args: JsonObject): JsonObject = JsonObject()
        override fun decompile(input: File, locator: String, args: JsonObject): JsonObject =
            ToolkitIo.json("content" to "class Fake {}\n", "locator" to locator,
                "truncated" to false, "nextActions" to JsonArray())
    }
}
