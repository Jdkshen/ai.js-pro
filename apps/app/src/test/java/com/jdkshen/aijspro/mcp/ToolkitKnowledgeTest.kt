package com.jdkshen.aijspro.mcp

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class ToolkitKnowledgeTest {
    @Test fun commonRequestMetadataDoesNotBecomePromptArguments(){
        val knowledge=knowledge();val meta=JsonParser().parse("""{"_meta":{"progressToken":"client-1"}}""").asJsonObject
        assertEquals(2,knowledge.resourcesList(meta).getAsJsonArray("resources").size())
        assertEquals(3,knowledge.promptsList(meta).getAsJsonArray("prompts").size())
        val read=meta.deepCopy().apply{addProperty("uri",ToolkitKnowledge.GUIDE_URI)}
        assertEquals(1,knowledge.resourcesRead(read).getAsJsonArray("contents").size())
        rejects{knowledge.resourcesList(JsonParser().parse("""{"_meta":1}""").asJsonObject)}
        rejects{knowledge.promptsGet(JsonParser().parse("""{"name":"reverse-apk-inspect","arguments":{"_meta":{}}}""").asJsonObject)}
    }
    private fun asset(name: String): Path = listOf(
        Paths.get("src/main/assets/mcp/$name"),
        Paths.get("apps/app/src/main/assets/mcp/$name"),
    ).first { Files.isRegularFile(it) }

    private fun guideText(): String = String(Files.readAllBytes(asset("AIjs-Reverse-Guide.md")), StandardCharsets.UTF_8)
    private fun workflows(): JsonObject =
        JsonParser().parse(String(Files.readAllBytes(asset("reverse-workflows.json")), StandardCharsets.UTF_8)).asJsonObject

    private fun knowledge() = ToolkitKnowledge(guideText(), workflows())
    private fun args(vararg pairs: Pair<String, String>) = JsonObject().apply {
        pairs.forEach { (name, value) -> addProperty(name, value) }
    }

    private fun rejects(block: () -> Unit): IllegalArgumentException {
        val error = runCatching(block).exceptionOrNull()
        assertTrue("Expected IllegalArgumentException, got $error", error is IllegalArgumentException)
        return error as IllegalArgumentException
    }

    private fun textOf(result: JsonObject): String =
        result.getAsJsonArray("messages")[0].asJsonObject.getAsJsonObject("content").get("text").asString

    @Test
    fun resourcesExposeBothCompleteBoundedDocuments() {
        val guideText = guideText()
        val workflows = workflows()
        val knowledge = ToolkitKnowledge(guideText, workflows)
        val resources = knowledge.resourcesList(JsonObject()).getAsJsonArray("resources")
        assertEquals(2, resources.size())
        val byUri = resources.map { it.asJsonObject }.associateBy { it.get("uri").asString }

        val guide = byUri.getValue(ToolkitKnowledge.GUIDE_URI)
        assertEquals("text/markdown", guide.get("mimeType").asString)
        assertEquals(guideText.toByteArray(StandardCharsets.UTF_8).size, guide.get("size").asInt)
        assertTrue(guide.get("size").asInt <= ToolkitKnowledge.MAX_RESOURCE_BYTES)

        val readGuide = knowledge.resourcesRead(args("uri" to ToolkitKnowledge.GUIDE_URI))
        val guideContent = readGuide.getAsJsonArray("contents")[0].asJsonObject
        assertEquals(ToolkitKnowledge.GUIDE_URI, guideContent.get("uri").asString)
        assertEquals("text/markdown", guideContent.get("mimeType").asString)
        assertEquals(guideText, guideContent.get("text").asString)

        val workflowResource = byUri.getValue(ToolkitKnowledge.WORKFLOWS_URI)
        assertEquals("application/json", workflowResource.get("mimeType").asString)
        assertTrue(workflowResource.get("size").asInt <= ToolkitKnowledge.MAX_RESOURCE_BYTES)
        val readWorkflows = knowledge.resourcesRead(args("uri" to ToolkitKnowledge.WORKFLOWS_URI))
        val workflowText = readWorkflows.getAsJsonArray("contents")[0].asJsonObject.get("text").asString
        assertEquals(workflows, JsonParser().parse(workflowText))
        assertEquals(0, knowledge.resourceTemplatesList(JsonObject()).getAsJsonArray("resourceTemplates").size())
    }

    @Test
    fun listsRejectCursorsAndReadsRejectMissingOrUnknownUris() {
        val knowledge = knowledge()
        rejects { knowledge.resourcesList(args("cursor" to "next")) }
        rejects { knowledge.resourceTemplatesList(args("cursor" to "next")) }
        rejects { knowledge.promptsList(args("cursor" to "next")) }

        val missing = rejects { knowledge.resourcesRead(JsonObject()) }
        assertTrue(missing.message!!.contains("uri"))
        val wrongType = rejects {
            knowledge.resourcesRead(JsonObject().apply { addProperty("uri", 1) })
        }
        assertTrue(wrongType.message!!.contains("uri"))
        val unknown = rejects { knowledge.resourcesRead(args("uri" to "aijs://reverse/not-found")) }
        assertTrue(unknown.message!!.contains("aijs://reverse/not-found"))
        assertTrue(unknown.message!!.contains(ToolkitKnowledge.GUIDE_URI))
        rejects { knowledge.resourcesRead(args("uri" to ToolkitKnowledge.GUIDE_URI, "extra" to "x")) }
    }

    @Test
    fun promptsListAdvertisesThreeOptionalPathAndGoalArguments() {
        val prompts = knowledge().promptsList(JsonObject()).getAsJsonArray("prompts")
        assertEquals(
            listOf("reverse-apk-inspect", "reverse-native-analysis", "reverse-apk-rebuild"),
            prompts.map { it.asJsonObject.get("name").asString },
        )
        for (element in prompts) {
            val prompt = element.asJsonObject
            assertFalse(prompt.get("description").asString.isBlank())
            val arguments = prompt.getAsJsonArray("arguments").map { it.asJsonObject }
            assertEquals(listOf("path", "goal"), arguments.map { it.get("name").asString })
            arguments.forEach { assertFalse(it.get("required").asBoolean) }
        }
    }

    @Test
    fun everyPromptBootstrapsTheRealWorkflowAndOperationalRules() {
        val workflows = workflows()
        val knowledge = ToolkitKnowledge(guideText(), workflows)
        val prompts = listOf(
            "reverse-apk-inspect" to "inspect",
            "reverse-native-analysis" to "native",
            "reverse-apk-rebuild" to "rebuild",
        )
        for ((name, goal) in prompts) {
            val text = textOf(knowledge.promptsGet(args("name" to name)))
            assertTrue("$name: guide", text.contains("reverse_guide") && text.contains("topic=index"))
            assertTrue("$name: capabilities", text.contains("reverse_capabilities"))
            assertTrue("$name: plan", text.contains("reverse_plan") && text.contains("\"goal\":\"$goal\""))
            assertTrue("$name: status", text.contains("apk_job_status"))
            assertTrue("$name: result", text.contains("apk_job_result"))
            assertTrue("$name: paging", text.contains("offset") && text.contains("nextOffset") && text.contains("hasMore"))
            assertTrue("$name: dependency check", text.contains("apk_component_status") && text.contains("tools/list"))
            assertTrue("$name: status takes empty object", text.contains("using `{}` (no component field)"))
            assertFalse("$name: status must not take component", text.contains("{\"component\":"))
            assertTrue("$name: paging stops on hasMore", text.contains("only while `hasMore=true`"))
            assertFalse("$name: nextOffset alone is not a loop rule", text.contains("or a `nextOffset`"))
            assertTrue("$name: reuses task authorization", text.contains("already authorized"))
            assertTrue("$name: no fake plugin", text.contains("No plugin is present by default"))
            assertTrue("$name: no execution", text.contains("never executes") && text.contains("performs no execution"))

            val workflow = workflows.getAsJsonArray("workflows").map { it.asJsonObject }
                .first { it.get("goal").asString == goal }
            workflow.getAsJsonArray("steps").forEach { element ->
                val tool = element.asJsonObject.get("tool")?.asString ?: return@forEach
                assertTrue("$name is missing workflow tool $tool", text.contains(tool))
            }
        }
    }

    @Test
    fun requestedPathIsEmbeddedAsJsonDataAndNeverAsACommand() {
        val hostile = "target.apk\"; rm -rf / #\n\u0000$(id)"
        val result = knowledge().promptsGet(
            JsonObject().apply {
                addProperty("name", "reverse-apk-inspect")
                add("arguments", args("path" to hostile, "goal" to "inspect"))
            },
        )
        val text = textOf(result)
        val payload = Regex("<request-json>(.*?)</request-json>", RegexOption.DOT_MATCHES_ALL)
            .find(text)?.groupValues?.get(1) ?: error("missing request JSON")
        val parsed = JsonParser().parse(payload).asJsonObject
        assertEquals(hostile, parsed.get("path").asString)
        assertEquals("inspect", parsed.get("goal").asString)
        val outsidePayload = text.substringBefore("<request-json>") + text.substringAfter("</request-json>")
        assertFalse(outsidePayload.contains(hostile))
        assertFalse(outsidePayload.contains("rm -rf"))
    }

    @Test
    fun promptArgumentsAreValidatedByKeyAndType() {
        val knowledge = knowledge()
        assertTrue(rejects { knowledge.promptsGet(JsonObject()) }.message!!.contains("name"))
        assertTrue(rejects { knowledge.promptsGet(args("name" to "missing-prompt")) }.message!!.contains("missing-prompt"))
        rejects { knowledge.promptsGet(args("name" to "reverse-apk-inspect", "cursor" to "x")) }
        rejects {
            knowledge.promptsGet(JsonObject().apply {
                addProperty("name", "reverse-apk-inspect")
                add("arguments", JsonParser().parse("[]"))
            })
        }
        rejects {
            knowledge.promptsGet(JsonObject().apply {
                addProperty("name", "reverse-apk-inspect")
                add("arguments", args("extra" to "x"))
            })
        }
        rejects {
            knowledge.promptsGet(JsonObject().apply {
                addProperty("name", "reverse-apk-inspect")
                add("arguments", JsonObject().apply { addProperty("path", 1) })
            })
        }
        rejects {
            knowledge.promptsGet(JsonObject().apply {
                addProperty("name", "reverse-apk-inspect")
                add("arguments", args("goal" to "not-a-workflow-goal"))
            })
        }
        rejects {
            knowledge.promptsGet(JsonObject().apply {
                addProperty("name", "reverse-apk-inspect")
                add("arguments", args("path" to "  "))
            })
        }
    }

    @Test
    fun oversizedDocumentsAreRejectedBeforeTheyCanBeServed() {
        rejects {
            ToolkitKnowledge(
                "x".repeat(ToolkitKnowledge.MAX_RESOURCE_BYTES + 1),
                JsonParser().parse("""{"workflows":[]}""").asJsonObject,
            )
        }
        val oversizedWorkflows = JsonObject().apply {
            add("workflows", JsonParser().parse("[]"))
            addProperty("padding", "x".repeat(ToolkitKnowledge.MAX_RESOURCE_BYTES))
        }
        rejects { ToolkitKnowledge("guide", oversizedWorkflows) }
    }
}
