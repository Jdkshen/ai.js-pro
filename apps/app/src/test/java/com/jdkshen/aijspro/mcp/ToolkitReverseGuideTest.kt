package com.jdkshen.aijspro.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class ToolkitReverseGuideTest {
    private fun asset(name: String): File = listOf(
        File("src/main/assets/mcp/$name"),
        File("apps/app/src/main/assets/mcp/$name"),
    ).first { it.isFile }

    private fun guideText() = asset("AIjs-Reverse-Guide.md").readText(Charsets.UTF_8)
    private fun workflows() = JsonParser().parse(asset("reverse-workflows.json").readText(Charsets.UTF_8)).asJsonObject
    private fun schemas() = JsonParser().parse(asset("toolkit-apk-schemas.json").readText(Charsets.UTF_8)).asJsonObject
    private fun args(json: String = "{}") = JsonParser().parse(json).asJsonObject
    private fun currentTools() = McpToolCatalog.names.toSet()
    private fun guide(tools: Set<String> = currentTools()) = ToolkitReverseGuide(guideText(), workflows(), tools)

    private fun rejects(block: () -> Unit) {
        try {
            block()
            fail("expected rejection")
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun walk(value: JsonElement, block: (String, JsonElement) -> Unit) {
        when {
            value.isJsonObject -> value.asJsonObject.entrySet().forEach { block(it.key, it.value); walk(it.value, block) }
            value.isJsonArray -> value.asJsonArray.forEach { walk(it, block) }
        }
    }

    private fun assertFieldValue(
        path: String,
        value: JsonElement,
        spec: JsonObject,
        placeholderTyped: MutableSet<String>,
    ) {
        val type = spec.get("type")?.takeIf { it.isJsonPrimitive }?.asString
        val placeholder = value.isJsonPrimitive && value.asJsonPrimitive.isString && value.asString.contains("\${")
        when (type) {
            "string" -> assertTrue("$path must be a string", value.isJsonPrimitive && value.asJsonPrimitive.isString)
            "boolean" -> assertTrue("$path must be a boolean", value.isJsonPrimitive && value.asJsonPrimitive.isBoolean)
            "integer" -> if (placeholder) {
                placeholderTyped += path
            } else {
                assertTrue("$path must be an integer", value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
            }
            "object" -> assertTrue("$path must be an object", value.isJsonObject)
            "array" -> assertTrue("$path must be an array", value.isJsonArray)
        }
        spec.getAsJsonArray("enum")?.let { allowed ->
            assertTrue("$path enum ${value}", allowed.any { it.isJsonPrimitive && it.asString == value.asString })
        }
    }

    @Test
    fun readIndexExposesHashSectionsAndWorkflows() {
        val index = guide().read(args())
        val expected = MessageDigest.getInstance("SHA-256").digest(guideText().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals("sha256:$expected", index.get("guideSha256").asString)
        assertEquals(expected, index.get("guideSha256Hex").asString)
        assertEquals("dev1329-1", index.get("guideVersion").asString)
        assertEquals("index", index.get("selectedSection").asString)
        assertFalse(index.get("hasMore").asBoolean)
        val ids = index.getAsJsonArray("sectionIds").map { it.asString }
        assertTrue(ids.containsAll(listOf("overview", "quickstart", "jobs", "components", "native", "rebuild", "dynamic", "emulate", "workflows", "limitations")))
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(8, index.getAsJsonArray("workflows").size())
        assertEquals(18, index.getAsJsonArray("candidateTools").size())
        index.getAsJsonArray("candidateTools").forEach {
            assertTrue("registered reverse tool should be available: $it", it.asJsonObject.get("available").asBoolean)
        }
        assertTrue(index.get("content").asString.contains("quickstart"))
    }

    @Test
    fun allContentPagesReassembleExactlyAndRespectTheBudget() {
        val guide = guide()
        val defaultPage = guide.read(args("""{"topic":"all"}"""))
        assertEquals(6000, defaultPage.get("content").asString.length)
        assertTrue(defaultPage.get("hasMore").asBoolean)
        assertEquals(6000, defaultPage.get("nextOffset").asInt)
        val capped = guide.read(args("""{"topic":"all","maxChars":24000}"""))
        assertEquals(24000, capped.get("content").asString.length)
        assertTrue(capped.get("hasMore").asBoolean)

        var offset = 0
        var pages = 0
        var more: Boolean
        val rebuilt = StringBuilder()
        do {
            val page = guide.read(args("""{"topic":"all","offset":$offset,"maxChars":700}"""))
            val part = page.get("content").asString
            assertTrue(part.length <= 700)
            assertFalse(part.isNotEmpty() && Character.isHighSurrogate(part.last()))
            rebuilt.append(part)
            offset = page.get("nextOffset").asInt
            more = page.get("hasMore").asBoolean
            pages++
        } while (more)
        assertEquals(guideText(), rebuilt.toString())
        assertTrue(pages > 5)
        assertEquals(0, guide.read(args("""{"topic":"all","offset":${guideText().length}}""")).get("content").asString.length)
        rejects { guide.read(args("""{"topic":"all","maxChars":24001}""")) }
        rejects { guide.read(args("""{"topic":"all","offset":99999999}""")) }
        rejects { guide.read(args("""{"topic":"all","unknown":1}""")) }
    }

    @Test
    fun sectionReadsAreCopyableAndUnknownTopicsFail() {
        val quick = guide().read(args("""{"topic":"quickstart","maxChars":24000}"""))
        assertEquals("quickstart", quick.get("selectedSection").asString)
        assertTrue(quick.get("content").asString.contains("\"method\":\"tools/call\""))
        assertTrue(quick.get("content").asString.contains("apk_workspace_open"))
        assertTrue(quick.get("content").asString.contains("Accept: application/json, text/event-stream"))
        assertTrue(guide().read(args("""{"topic":"workflows"}""")).get("content").asString.contains("reverse_plan"))
        rejects { guide().read(args("""{"topic":"no-such-section"}""")) }
    }

    @Test
    fun currentInspectPlanIsReadyAndOnlyUsesRealTools() {
        val plan = guide().plan(args("""{"goal":"inspect"}"""))
        assertEquals("inspect", plan.get("goal").asString)
        assertFalse(plan.get("executed").asBoolean)
        assertEquals("ready", plan.get("readiness").asString)
        assertTrue(plan.get("templateRequiresReplacement").asBoolean)
        assertEquals(0, plan.getAsJsonArray("unknownVariables").size())
        val steps = plan.getAsJsonArray("steps")
        assertTrue(steps.size() > 3)
        steps.forEach { element ->
            val step = element.asJsonObject
            if (step.get("required").asBoolean) {
                assertTrue(step.get("tool").asString in currentTools())
                assertTrue(step.get("supported").asBoolean)
            }
        }
        plan.getAsJsonArray("variables").forEach {
            assertTrue(it.asJsonObject.get("replaceBeforeUse").asBoolean)
            assertTrue(it.asJsonObject.get("placeholder").asString.startsWith("\${"))
        }
        plan.getAsJsonArray("toolReferences").forEach {
            assertEquals(it.asJsonObject.get("tool").asString in currentTools(), it.asJsonObject.get("available").asBoolean)
        }
    }

    @Test
    fun decodeRequiresAComponentCheckWhileDynamicAndEmulateUseRegisteredTools() {
        val decode = guide().plan(args("""{"goal":"decode"}"""))
        assertEquals("ready_with_component_check", decode.get("readiness").asString)
        val jadx = decode.getAsJsonArray("requiredComponents").map { it.asJsonObject }.first { it.get("id").asString == "jadx" }
        assertEquals("check_required", jadx.get("status").asString)
        assertTrue(jadx.get("installSupported").asBoolean)
        assertFalse(jadx.get("checkedByPlan").asBoolean)
        assertEquals("apk_component_install", jadx.get("installTool").asString)

        for (goal in listOf("dynamic", "emulate")) {
            val plan = guide().plan(args("""{"goal":"$goal"}"""))
            assertEquals("ready_with_component_check", plan.get("readiness").asString)
            val component = plan.getAsJsonArray("requiredComponents")[0].asJsonObject
            assertEquals("check_required", component.get("status").asString)
            assertTrue(component.get("installSupported").asBoolean)
        }

        val empty = ToolkitReverseGuide(guideText(), workflows(), emptySet())
        for (goal in listOf("dynamic", "emulate")) {
            val plan = empty.plan(args("""{"goal":"$goal"}"""))
            assertEquals("blocked", plan.get("readiness").asString)
            assertFalse(plan.get("executed").asBoolean)
        }
        val dynamic = empty.plan(args("""{"goal":"dynamic"}"""))
        assertTrue(dynamic.getAsJsonArray("unsupportedTools").map { it.asString }.containsAll(listOf("frida_status", "frida_processes", "frida_eval")))
        assertEquals("unsupported", dynamic.getAsJsonArray("requiredComponents")[0].asJsonObject.get("status").asString)
        val emulate = empty.plan(args("""{"goal":"emulate"}"""))
        assertTrue(emulate.getAsJsonArray("unsupportedComponents").map { it.asString }.contains("unicorn"))
        rejects { empty.plan(args("""{"goal":"missing"}""")) }
        rejects { empty.plan(args("""{"goal":"inspect","unknown":1}""")) }
    }

    @Test
    fun everyGoalValidatesReferencesAndInventoriesNoWaitOrResumeKeys() {
        val guide = guide()
        for (goal in listOf("inspect", "decode", "rebuild", "native", "dynamic", "fingerprint", "emulate", "compare")) {
            val plan = guide.plan(args("""{"goal":"$goal"}"""))
            assertEquals(goal, plan.get("goal").asString)
            assertFalse(plan.get("executed").asBoolean)
            val references = plan.getAsJsonArray("toolReferences")
            assertTrue(references.size() > 0)
            references.forEach {
                val row = it.asJsonObject
                assertEquals(row.get("tool").asString in currentTools(), row.get("available").asBoolean)
            }
            walk(plan) { key, _ -> assertFalse("plan/$goal contains $key", key == "waitMillis" || key == "resume") }
        }
    }

    @Test
    fun pageBoundaryNeverSplitsASurrogatePair() {
        val content = "<!-- section:id=alpha -->\n## Alpha\n" + "a".repeat(127) + "\uD83D\uDE00" + "b".repeat(300) + "\n"
        val minimal = JsonParser().parse(
            """{"schemaVersion":1,"guideVersion":"test","workflows":[{"goal":"inspect","id":"i","title":"I","steps":[],"variables":[],"requiredComponents":[],"limitations":[]}],"candidateTools":[]}""",
        ).asJsonObject
        val guide = ToolkitReverseGuide(content, minimal, emptySet())
        val first = guide.read(args("""{"topic":"alpha","maxChars":137}"""))
        assertEquals(136, first.get("content").asString.length)
        assertFalse(Character.isHighSurrogate(first.get("content").asString.last()))
        val next = first.get("nextOffset").asInt
        assertEquals(136, next)
        assertTrue(guide.read(args("""{"topic":"alpha","offset":$next,"maxChars":8}""")).get("content").asString.startsWith("\uD83D\uDE00"))
        rejects { guide.read(args("""{"topic":"alpha","offset":137}""")) }
    }

    @Test
    fun workflowAssetMatchesToolSchemasAndDeclaresEveryComponentAndPlaceholder() {
        val workflows = workflows()
        val schemas = schemas()
        val sectionIds = Regex("""(?m)^<!-- section:id=([a-z][a-z0-9-]{0,39}) -->$""")
            .findAll(guideText()).map { it.groupValues[1] }.toSet()
        assertEquals(8, workflows.getAsJsonArray("workflows").size())
        val expectedComponents = setOf("jadx", "apk-resources", "apktool", "radare2", "r2ghidra", "yara", "unicorn", "frida")
        val statusSchema = schemas.getAsJsonObject("apk_component_status")
        assertEquals(0, statusSchema.getAsJsonObject("properties").size())
        val installEnum = schemas.getAsJsonObject("apk_component_install").getAsJsonObject("properties")
            .getAsJsonObject("component").getAsJsonArray("enum").map { it.asString }.toSet()
        assertEquals(expectedComponents, installEnum)
        val placeholderTyped = linkedSetOf<String>()
        for (workflow in workflows.getAsJsonArray("workflows")) {
            val row = workflow.asJsonObject
            val goal = row.get("goal").asString
            val declared = row.getAsJsonArray("variables").map { it.asJsonObject.get("name").asString }.toSet()
            row.getAsJsonArray("read").forEach { assertTrue("$goal read ${it.asString}", it.asString in sectionIds) }
            for (componentElement in row.getAsJsonArray("requiredComponents")) {
                val component = componentElement.asJsonObject
                val id = component.get("id").asString
                assertTrue("$goal component $id", id in expectedComponents)
                if (component.has("installTool")) {
                    assertTrue("$goal/$id installArgumentSupported", component.get("installArgumentSupported").asBoolean)
                    val install = component.getAsJsonObject("installArgs")
                    if (install != null && install.has("component")) {
                        assertEquals("$goal/$id installArgs.component", id, install.get("component").asString)
                    }
                }
            }
            for (stepElement in row.getAsJsonArray("steps")) {
                val step = stepElement.asJsonObject
                val tool = step.get("tool")?.asString ?: continue
                val arguments = step.getAsJsonObject("args") ?: JsonObject()
                val used = linkedSetOf<String>()
                walk(arguments) { _, value ->
                    if (value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                        Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)\}""").findAll(value.asString).forEach { used += it.groupValues[1] }
                    }
                }
                assertTrue("$goal/$tool undeclared $used", declared.containsAll(used))
                assertFalse("$goal/$tool has waitMillis", arguments.has("waitMillis"))
                assertFalse("$goal/$tool has resume", arguments.has("resume"))
                when (tool) {
                    "file_stat" -> assertTrue(arguments.has("path"))
                    "zip_test" -> assertTrue(arguments.has("path") && arguments.has("password"))
                    "toolkit_status" -> assertTrue(arguments.size() == 0)
                    "apk_component_status" -> assertTrue("$goal/$tool must use {}", arguments.size() == 0)
                    "apk_copy_entry" -> {
                        assertEquals("$goal/$tool direction", "workspace_to_local", arguments.get("direction").asString)
                        assertTrue("$goal/$tool locator", arguments.get("locator").asString.startsWith("zip_entry:"))
                        assertTrue("$goal/$tool editSessionId", arguments.has("editSessionId"))
                        assertFalse("$goal/$tool overwrite", arguments.get("overwrite").asBoolean)
                    }
                    "native_emulate" -> {
                        assertTrue("$goal/$tool codeHex", arguments.has("codeHex"))
                        assertFalse("$goal/$tool must not take path", arguments.has("path"))
                    }
                }
                val schema = if (tool in ToolkitReverseTools.names) {
                    ToolkitReverseTools.schema(tool)
                } else {
                    schemas.getAsJsonObject(tool) ?: continue
                }
                val properties = schema.getAsJsonObject("properties")?.keySet() ?: emptySet()
                assertTrue("$goal/$tool extra ${arguments.keySet() - properties}", arguments.keySet().all { it in properties })
                schema.getAsJsonArray("required")?.forEach { assertTrue("$goal/$tool missing ${it.asString}", arguments.has(it.asString)) }
                schema.getAsJsonArray("oneOf")?.let { alternatives ->
                    assertTrue("$goal/$tool oneOf", alternatives.any { alternative ->
                        alternative.asJsonObject.getAsJsonArray("required")?.all { arguments.has(it.asString) } == true
                    })
                }
                arguments.entrySet().forEach { (key, value) ->
                    val spec = schema.getAsJsonObject("properties")?.getAsJsonObject(key) ?: return@forEach
                    assertFieldValue("$goal/$tool.$key", value, spec, placeholderTyped)
                }
            }
        }
        assertTrue("placeholder numeric fields recorded: $placeholderTyped", "dynamic/frida_eval.pid" in placeholderTyped)
        val expectedCandidates = setOf(
            "reverse_guide", "reverse_capabilities", "reverse_plan", "apk_decode_project", "apk_build_project",
            "apk_install_framework", "native_analyze", "native_functions", "native_function_detail",
            "native_decompile", "native_xrefs", "native_compare", "artifact_fingerprint", "yara_scan",
            "native_emulate", "frida_status", "frida_processes", "frida_eval",
        )
        val candidates = workflows.getAsJsonArray("candidateTools").map { it.asString }.toSet()
        assertEquals(expectedCandidates, candidates)
        assertTrue("all 18 reverse tools must be registered", currentTools().containsAll(expectedCandidates))
    }
}
