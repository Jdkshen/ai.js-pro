package com.jdkshen.aijspro.mcp

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Assert.assertTrue

class McpOutputConventionsTest {
    @get:Rule
    val temp = TemporaryFolder()
    @Test
    fun initializeInstructionContainsTheStrongOutputRule() {
        val rule = McpOutputConventions.INSTRUCTION
        assertTrue(rule.contains("产出"))
        assertTrue(rule.contains("project_folder"))
        assertTrue(rule.contains("organized"))
        assertTrue(rule.contains("<root>/<项目名>/"))
    }

    @Test
    fun describeForReadsOrganizerStateAndCreatesDefaultProjectOnDemand() {
        val root = temp.newFolder("root")
        val store = temp.newFolder("store")
        val before = McpOutputConventions.describeFor(root, store)
        assertTrue(before.get("projectDir").isJsonNull)

        val organizer = ToolkitOutputOrganizer(store, root)
        val created = organizer.call(ToolkitIo.json("action" to "create", "name" to "云镜分析"))
        val after = McpOutputConventions.describeFor(root, store)
        assertEquals(created.get("projectDir").asString, after.get("projectDir").asString)
        assertEquals("云镜分析", after.get("defaultProject").asString)
    }

    @Test
    fun outputConventionsShapeIsStableWithoutOrganizerState() {
        val conventions = McpOutputConventions.describe(null)
        assertTrue(conventions.get("projectDir").isJsonNull)
        assertTrue(conventions.get("defaultProject").isJsonNull)
        assertTrue(conventions.get("rule").asString.contains("project_folder"))
        assertTrue(conventions.getAsJsonArray("toolsWithOrganize").map { it.asString }
            .containsAll(listOf("capture_screen", "evidence_export")))
        assertTrue(conventions.get("note").asString.contains("project_folder"))
    }

    @Test
    fun outputConventionsUsesOrganizerStateWhenAvailable() {
        val state = JsonObject().apply {
            addProperty("projectDir", "/root/云镜分析")
            addProperty("name", "云镜分析")
        }
        val conventions = McpOutputConventions.describe(state)
        assertEquals("/root/云镜分析", conventions.get("projectDir").asString)
        assertEquals("云镜分析", conventions.get("defaultProject").asString)
        assertTrue(conventions.get("rule").asString.isNotBlank())
    }

    @Test
    fun descriptionPointerIsAppendedOnlyOnce() {
        val once = McpOutputConventions.appendPointer("说明")
        assertTrue(once.endsWith(McpOutputConventions.POINTER))
        assertEquals(once, McpOutputConventions.appendPointer(once))
    }
}
