package com.jdkshen.aijspro.mcp

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.reandroid.arsc.chunk.TableBlock
import org.jf.smali.Smali
import org.jf.smali.SmaliOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * End-to-end parity regressions for the APK session API. These exercise the same public call
 * surface a client uses; fixtures are generated in the test JVM and do not depend on
 * .artifacts or on any signed/private APK.
 */
class McpApkToolkitParityTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var home: File
    private lateinit var store: File
    private lateinit var tools: McpApkToolkit
    private lateinit var source: File
    private lateinit var workspace: String
    private lateinit var session: String

    @Before
    fun setup() {
        home = temp.newFolder("home")
        store = temp.newFolder("private")
        val schema = listOf(
            File("src/main/assets/mcp/toolkit-apk-schemas.json"),
            File("apps/app/src/main/assets/mcp/toolkit-apk-schemas.json")
        ).first { it.isFile }
        tools = McpApkToolkit(home, store, JsonParser().parse(schema.readText()).asJsonObject)

        val resourcesAndAxm = fixtureResourcesAndAxml()
        val dex = fixtureDex()
        source = File(home, "fixture.apk")
        ZipOutputStream(source.outputStream()).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            put("AndroidManifest.xml", "<manifest package=\"com.example.fixture\"/>".toByteArray())
            put("resources.arsc", resourcesAndAxm.first)
            put("classes.dex", dex)
            put(MENU_PATH, resourcesAndAxm.second)
            put("assets/test.txt", "中文\r\nsecond\r\n".toByteArray())
        }
        workspace = tools.call("apk_workspace_open", args("path" to source.name, "temporary" to true))
            .getAsJsonObject("data").tString("workspaceId")
        session = tools.call("apk_session_create", args("workspaceId" to workspace))
            .getAsJsonObject("data").tString("editSessionId")
    }

    @Test
    fun reservedEntriesRejectRawMutationsAndLeaveTheSessionUntouched() {
        val revision = sessionRevision()
        val sourceHash = ToolkitIo.sha(source)
        val local = File(home, "payload.bin").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }

        for (entry in listOf("classes.dex", "resources.arsc")) {
            val locator = "zip_entry:$entry"
            val code = if (entry.endsWith(".dex")) "DEX_ENTRY_RESERVED" else "RESOURCE_TABLE_RESERVED"

            failsWithCode(code) {
                invoke("apk_delete_target", "locator" to locator)
            }
            failsWithCode(code) {
                invoke("apk_patch_bytes", "locator" to locator, "patches" to listOf(
                    args("byteOffset" to 0, "expectedHex" to "00", "replacementHex" to "01")
                ))
            }
            failsWithCode(code) {
                invoke("apk_copy_entry", "direction" to "workspace_to_local", "locator" to locator,
                    "localPath" to "out-${entry.replace('.', '-')}", "overwrite" to false)
            }
            failsWithCode(code) {
                invoke("apk_copy_entry", "direction" to "local_to_edit_session", "locator" to locator,
                    "localPath" to local.name, "overwrite" to true)
            }
        }

        assertFalse(File(home, "out-classes-dex").exists())
        assertFalse(File(home, "out-resources-arsc").exists())
        assertEquals(sourceHash, ToolkitIo.sha(source))
        assertSessionUntouched(revision)
    }

    @Test
    fun axmlLocatorIsReadableAndOverviewSearchFindsCompiledChineseText() {
        val read = invoke("apk_read_text", "locator" to "axml:$MENU_PATH")
        assertEquals("axml:$MENU_PATH", read.tString("locator"))
        assertTrue(read.tString("text").contains("功能"))

        val result = invoke("apk_search", "target" to "overview", "query" to "功能",
            "snippetMaxChars" to 120)
        val hits = result.getAsJsonArray("hits")
        assertEquals(hits.toString(), result.getAsJsonArray("items").toString())
        val axmlHit = hits.map { it.asJsonObject }.firstOrNull { locatorOf(it) == "axml:$MENU_PATH" }
        assertNotNull("search should hit the compiled menu XML, got $hits", axmlHit)
    }

    @Test
    fun missingDexClassCanBeCreatedAsARealClass() {
        val locator = "dex_class:Lfixture/NewClass;"
        val created = invoke("apk_edit_text", "locator" to locator, "targetVersion" to "missing",
            "edits" to listOf(args("mode" to "write_target", "writeText" to NEW_CLASS)))
        assertTrue(created.get("savedToSession").asBoolean)

        val outline = invoke("apk_dex_read_class_outline", "locator" to locator)
        val items = outline.getAsJsonArray("items")
        assertTrue(items.any {
            it.asJsonObject.tString("kind") == "class" &&
                it.asJsonObject.tString("locator") == locator
        })
        assertTrue(invoke("apk_session_check", "runBuildChecks" to true).get("valid").asBoolean)
    }

    @Test
    fun methodBodyEditDoesNotDisturbTheSiblingMethod() {
        val target = "dex_method:Lfixture/Sample;->namedMethod()Ljava/lang/String;"
        val sibling = "dex_method:Lfixture/Sample;->otherMethod()I"
        val beforeSibling = invoke("apk_read_text", "locator" to sibling).tString("text")
        val before = invoke("apk_read_text", "locator" to target)
        val edited = before.tString("text").replace("string-only-needle", "string-only-needle-edited")

        invoke("apk_edit_text", "locator" to target, "targetVersion" to before.tString("targetVersion"),
            "edits" to listOf(args("mode" to "write_target", "writeText" to edited)))

        assertTrue(invoke("apk_read_text", "locator" to target).tString("text").contains("string-only-needle-edited"))
        assertEquals(beforeSibling, invoke("apk_read_text", "locator" to sibling).tString("text"))
        assertTrue(invoke("apk_session_check", "runBuildChecks" to true).get("valid").asBoolean)
    }

    @Test
    fun methodRenameIsRejectedWithoutChangingTheDex() {
        val locator = "dex_method:Lfixture/Sample;->namedMethod()Ljava/lang/String;"
        val before = invoke("apk_read_text", "locator" to locator)
        val renamed = before.tString("text").replace("namedMethod", "renamedMethod")
        val revision = sessionRevision()

        fails {
            invoke("apk_edit_text", "locator" to locator, "targetVersion" to before.tString("targetVersion"),
                "edits" to listOf(args("mode" to "write_target", "writeText" to renamed)))
        }

        val after = invoke("apk_read_text", "locator" to locator)
        assertEquals(before.tString("targetVersion"), after.tString("targetVersion"))
        assertFalse(after.tString("text").contains("renamedMethod"))
        assertSessionUntouched(revision)
    }

    @Test
    fun dexSearchCoversNamesFieldConstantsOverviewStringsPrefixAndSnippetZero() {
        val methods = search("target" to "dex_names", "query" to "namedMethod", "snippetMaxChars" to 0)
        assertTrue("method-name hit missing: $methods",
            methods.any { locatorOf(it.asJsonObject).contains("namedMethod") })
        assertTrue("snippetMaxChars=0 must disable snippets", methods.all { !hasSnippet(it.asJsonObject) })

        val fields = search("target" to "dex_strings", "query" to "field-only-needle")
        assertTrue("field constant hit missing: $fields",
            fields.any { valueOf(it.asJsonObject).contains("field-only-needle") })

        val overview = search("target" to "overview", "query" to "string-only-needle")
        assertTrue("overview must include DEX string literals: $overview",
            overview.any { valueOf(it.asJsonObject).contains("string-only-needle") })

        val prefixed = search("target" to "dex_names", "query" to "Sample", "prefix" to "fixture")
        assertTrue("package prefix should be normalized to a descriptor prefix: $prefixed", prefixed.size() > 0)
        assertTrue(prefixed.all {
            val locator = locatorOf(it.asJsonObject)
            locator.contains("Lfixture/") || locator.contains("fixture")
        })
    }

    @Test
    fun searchPaginationKeepsTheHitsAliasAndCursorContract() {
        val first = invoke("apk_search", "target" to "dex_names", "query" to "fixture", "limit" to 1)
        assertEquals(1, first.getAsJsonArray("items").size())
        assertEquals(1, first.getAsJsonArray("hits").size())
        assertEquals(first.getAsJsonArray("items").toString(), first.getAsJsonArray("hits").toString())
        assertTrue(first.get("hasMore").asBoolean)
        val cursor = first.tString("nextCursor")
        assertTrue(cursor.isNotEmpty())
        assertEquals(1, first.getAsJsonObject("pagination").get("returnedCount").asInt)

        val second = invoke("apk_continue", "nextCursor" to cursor, "limit" to 1)
        assertEquals(1, second.getAsJsonArray("items").size())
        assertEquals(1, second.getAsJsonArray("hits").size())
        assertEquals(second.getAsJsonArray("items").toString(), second.getAsJsonArray("hits").toString())
        assertEquals(1, second.getAsJsonObject("pagination").get("returnedCount").asInt)
    }

    @Test
    fun resourceReadsNormalizeDefaultAndKeepPerItemFailures() {
        val title = resource("title")
        val read = invoke("apk_resource_read", "reads" to listOf(
            args("locator" to title.tString("locator"), "variant" to "default")
        )).getAsJsonArray("items").single().asJsonObject
        assertEquals("default", read.tString("variant"))
        assertFalse(read.has("errorCode"))
        assertTrue(read.tString("valueXml").contains("原值"))

        val batch = invoke("apk_resource_read", "reads" to listOf(
            args("locator" to title.tString("locator"), "variant" to "default"),
            args("locator" to "resource:0x7fffffff", "variant" to "default")
        )).getAsJsonArray("items")
        assertEquals(2, batch.size())
        val first = batch[0].asJsonObject
        val second = batch[1].asJsonObject
        assertFalse(first.has("errorCode"))
        assertTrue(first.tString("valueXml").contains("原值"))
        assertEquals("RESOURCE_NOT_FOUND", second.tString("errorCode"))
        assertEquals(1, second.get("index").asInt)
    }

    @Test
    fun resourceReadsHonorXmlBudgetReferencesAndCollectionLimits() {
        val title = resource("title")
        val alias = resource("alias")
        val names = resource("names")

        val budget = invoke("apk_resource_read", "reads" to listOf(
            args("locator" to title.tString("locator"), "variant" to "default")
        ), "maxValueXmlChars" to 1).getAsJsonArray("items").single().asJsonObject
        assertTrue("over-budget XML must be null, not a half document", budget.get("valueXml").isJsonNull)
        assertTrue(budget.get("valueXmlTruncated").asBoolean)
        assertFalse(budget.get("valueXmlComplete").asBoolean)
        assertTrue(budget.get("valueXmlCharCount").asInt > 1)

        val resolved = invoke("apk_resource_read", "reads" to listOf(
            args("locator" to alias.tString("locator"), "variant" to "default")
        ), "resolveDepth" to 1).getAsJsonArray("items").single().asJsonObject
        assertTrue(resolved.get("referencesResolved").asBoolean)
        val target = resolved.getAsJsonObject("resolvedValue")
        assertEquals(title.tString("locator"), target.tString("locator"))
        assertTrue(target.tString("value").contains("原值"))

        val collection = invoke("apk_resource_read", "reads" to listOf(
            args("locator" to names.tString("locator"), "variant" to "default")
        ), "maxItemsPerValue" to 0).getAsJsonArray("items").single().asJsonObject
        assertEquals(2, collection.get("itemCount").asInt)
        assertTrue(collection.get("itemsTruncated").asBoolean)
        assertEquals(0, collection.getAsJsonArray("items").size())
    }

    private fun args(vararg pairs: Pair<String, Any?>): JsonObject = ToolkitIo.json(*pairs)

    private fun invoke(name: String, vararg pairs: Pair<String, Any?>): JsonObject {
        val input = args("workspaceId" to workspace, "editSessionId" to session)
        pairs.forEach { (key, value) -> input.add(key, Gson().toJsonTree(value)) }
        return tools.call(name, input).getAsJsonObject("data")
    }

    private fun search(vararg pairs: Pair<String, Any?>): JsonArray =
        invoke("apk_search", *pairs).getAsJsonArray("hits")

    private fun resource(name: String): JsonObject =
        invoke("apk_list", "view" to "resource_table").getAsJsonArray("items")
            .map { it.asJsonObject }
            .single { it.tString("name") == name }

    private fun locatorOf(hit: JsonObject): String {
        val nested = hit.get("hit")?.takeIf { it.isJsonObject }?.asJsonObject
        val value = nested?.get("locator") ?: hit.get("locator")
        return value?.takeIf { it.isJsonPrimitive }?.asString ?: ""
    }

    private fun valueOf(hit: JsonObject): String {
        val nested = hit.get("hit")?.takeIf { it.isJsonObject }?.asJsonObject
        val value = nested?.get("value") ?: hit.get("value")
        return value?.takeIf { it.isJsonPrimitive }?.asString ?: ""
    }

    private fun hasSnippet(hit: JsonObject): Boolean {
        val nested = hit.get("hit")?.takeIf { it.isJsonObject }?.asJsonObject
        return hit.has("snippet") || nested?.has("snippet") == true
    }

    private fun fails(block: () -> Unit) {
        try {
            block()
            fail("Expected the operation to fail")
        } catch (expected: IllegalArgumentException) {
            // ToolkitException is an IllegalArgumentException; plain validation errors also count.
        } catch (expected: IllegalStateException) {
            // Session validation failures are expected to fail closed too.
        }
    }

    private fun failsWithCode(code: String, block: () -> Unit) {
        try {
            block()
            fail("Expected $code")
        } catch (failure: ToolkitException) {
            assertEquals(code, failure.code)
        }
    }

    private fun sessionFile(): File =
        File(store, workspace).resolve("sessions").resolve(session).resolve("session.json")

    private fun sessionRevision(): String =
        JsonParser().parse(sessionFile().readText()).asJsonObject.tString("revision")

    private fun assertSessionUntouched(revision: String) {
        val info = JsonParser().parse(sessionFile().readText()).asJsonObject
        assertEquals(revision, info.tString("revision"))
        assertTrue(info.getAsJsonObject("changedEntries").entrySet().isEmpty())
        assertEquals(0, info.getAsJsonArray("deleted").size())
    }

    private fun fixtureResourcesAndAxml(): Pair<ByteArray, ByteArray> {
        val table = TableBlock()
        val pkg = table.newPackage(0x7f, "com.example.fixture")
        pkg.getOrCreate("", "string", "title").setValueAsString("原值")
        table.refreshFull()

        // Reload once so the reference uses a linked resource ID rather than an in-memory
        // placeholder. The alias is then serialized together with the table.
        val resources = ToolkitResources(table.bytes)
        val title = resources.entries().single { it.name == "title" }
        val loadedPackage = resources.table.getPackageBlockById(0x7f) ?: error("fixture package missing")
        loadedPackage.getOrCreate("", "string", "alias").setValueAsReference(title.resourceId)
        resources.add("0x7f", "",
            "<string-array name=\"names\"><item>one</item><item>two</item></string-array>")
        val axml = ToolkitResources.encodeXml("<menu><item title=\"功能\" /></menu>", resources)
        return resources.bytes() to axml
    }

    private fun fixtureDex(): ByteArray {
        val sample = temp.newFile("Sample.smali").apply { writeText(SAMPLE_SMALI) }
        val second = temp.newFile("Second.smali").apply { writeText(SECOND_SMALI) }
        val dex = File(temp.root, "classes.dex")
        assertTrue(Smali.assemble(SmaliOptions().apply {
            outputDexFile = dex.path
            jobs = 1
        }, listOf(sample.path, second.path)))
        return dex.readBytes()
    }

    companion object {
        private const val MENU_PATH = "res/menu/menu_navigation.xml"
        private const val SAMPLE_SMALI = """.class public Lfixture/Sample;
.super Ljava/lang/Object;
.field public static final fieldOnly:Ljava/lang/String; = "field-only-needle"
.method public constructor <init>()V
    .registers 1
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    return-void
.end method
.method public static namedMethod()Ljava/lang/String;
    .registers 1
    const-string v0, "string-only-needle"
    return-object v0
.end method
.method public static otherMethod()I
    .registers 1
    const/4 v0, 0x1
    return v0
.end method
"""
        private const val SECOND_SMALI = """.class public Lfixture/Second;
.super Ljava/lang/Object;
.method public static two()I
    .registers 1
    const/4 v0, 0x2
    return v0
.end method
"""
        private const val NEW_CLASS = """.class public Lfixture/NewClass;
.super Ljava/lang/Object;
.method public static value()I
    .registers 1
    const/4 v0, 0x2
    return v0
.end method
"""
    }
}
