package com.jdkshen.aijspro.mcp

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

class ToolkitOutputOrganizerTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun pair(): Triple<ToolkitOutputOrganizer, File, File> {
        val root = temp.newFolder("root")
        val store = temp.newFolder("store")
        return Triple(ToolkitOutputOrganizer(store, root), root, store)
    }

    private fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject().apply {
        pairs.forEach { (name, value) ->
            when (value) {
                null -> add(name, com.google.gson.JsonNull.INSTANCE)
                is String -> addProperty(name, value)
                is Int -> addProperty(name, value)
                is Boolean -> addProperty(name, value)
                is com.google.gson.JsonElement -> add(name, value)
                else -> throw IllegalArgumentException("unsupported: " + name)
            }
        }
    }

    @Test
    fun bareFileAutoCreatesDatedProjectAndPersistsDefault() {
        val (organizer, root, _) = pair()
        val result = organizer.organize("out-v3.apk", true, "file_write_bytes")
        val target = File(result.path)
        assertTrue(target.parentFile.name.matches(Regex("mcp-out-[0-9]{8}")))
        assertEquals(root.path, target.parentFile.parentFile.path)
        assertTrue(target.parentFile.isDirectory)
        assertNotNull(result.organized)
        assertEquals("file_write_bytes", result.organized!!.get("label").asString)
        assertEquals(target.parentFile.path, organizer.projectDir()?.path)
        assertEquals(target.parentFile.name, organizer.state().get("name").asString)
    }

    @Test
    fun explicitProjectOverridesAutoAndBareFilesGoThere() {
        val (organizer, root, _) = pair()
        val project = organizer.ensureProject("云镜分析")
        assertEquals("云镜分析", project.name)
        assertEquals(project.path, organizer.projectDir()?.path)
        val result = organizer.organize("out-v3b.apk", true, "file_write_bytes")
        assertEquals(File(project, "out-v3b.apk").path, result.path)
        assertEquals(project.path, result.organized!!.get("projectDir").asString)
        assertFalse(organizer.organize("sub/out.txt", true).organized != null)
    }

    @Test
    fun explicitSubdirAndOrganizeFalseAreRespected() {
        val (organizer, root, _) = pair()
        organizer.ensureProject("proj")
        File(root, "sub").mkdirs()
        val sub = organizer.organize("sub/out.bin", true)
        assertEquals(File(root, "sub/out.bin").path, sub.path)
        assertNull(sub.organized)
        val disabled = organizer.organize("plain.bin", false)
        assertEquals(File(root, "plain.bin").path, disabled.path)
        assertNull(disabled.organized)
    }

    @Test
    fun tidyDefaultsToDryRunAndRealMoveOnlyRenames() {
        val (organizer, root, _) = pair()
        organizer.ensureProject("proj")
        val a = File(root, "a.txt").apply { writeText("a") }
        val b = File(root, "b.txt").apply { writeText("b") }
        val preview = organizer.tidy(listOf("a.txt", "b.txt"), true)
        assertTrue(preview.get("dryRun").asBoolean)
        assertEquals(2, preview.getAsJsonArray("moved").size())
        assertTrue(a.isFile)
        assertTrue(b.isFile)
        assertFalse(File(root, "proj/_incoming/a.txt").exists())

        val moved = organizer.tidy(listOf("a.txt", "b.txt"), false)
        assertFalse(moved.get("dryRun").asBoolean)
        assertEquals(2, moved.getAsJsonArray("moved").size())
        assertFalse(a.exists())
        assertFalse(b.exists())
        assertTrue(File(root, "proj/_incoming/a.txt").isFile)
        assertTrue(File(root, "proj/_incoming/b.txt").isFile)

        File(root, "b.txt").writeText("again")
        val conflict = organizer.tidy(listOf("b.txt"), false)
        assertEquals(1, conflict.getAsJsonArray("moved").size())
        assertTrue(File(root, "proj/_incoming/b-1.txt").isFile)
    }

    @Test
    fun projectFolderStateCreateAndSetShapes() {
        val (organizer, root, _) = pair()
        val initial = organizer.state()
        assertFalse(initial.get("isDefault").asBoolean)
        assertTrue(initial.get("candidates").isJsonArray)
        val created = organizer.call(obj("action" to "create", "name" to "云镜分析"))
        assertEquals("云镜分析", created.get("name").asString)
        assertTrue(created.get("isDefault").asBoolean)
        assertEquals(File(root, "云镜分析").path, created.get("projectDir").asString)
        val set = organizer.call(obj("action" to "set", "name" to "proj2"))
        assertEquals(File(root, "proj2").path, set.get("projectDir").asString)
        assertTrue(File(root, "proj2").isDirectory)
    }

    @Test
    fun fileMoveOverwriteIsOptionalAndAliasErrorNamesCorrectFields() {
        val (_, root, store) = pair()
        val files = McpFileToolkit(root, store)
        File(root, "from.txt").writeText("x")
        val moved = files.call("file_move", obj(
            "sourcePath" to "from.txt", "destinationPath" to "to.txt",
        )).getAsJsonObject("data")
        assertTrue(moved.get("moved").asBoolean)
        assertTrue(File(root, "to.txt").isFile)
        val error = runCatching {
            files.call("file_move", obj("source" to "to.txt", "destination" to "other.txt"))
        }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("sourcePath"))
        assertTrue(error?.message.orEmpty().contains("destinationPath"))
    }

    @Test
    fun fileWriteBytesIntegrationOrganizesAndReports() {
        val (_, root, store) = pair()
        val files = McpFileToolkit(root, store)
        val response = files.call("file_write_bytes", obj(
            "path" to "out.bin", "targetVersion" to "missing", "hex" to "00",
        ))
        val data = response.getAsJsonObject("data")
        val target = File(data.get("path").asString)
        assertTrue(target.parentFile.name.matches(Regex("mcp-out-[0-9]{8}")))
        assertTrue(target.isFile)
        assertEquals(target.path, response.getAsJsonObject("organized").get("to").asString)
        assertEquals(target.path, data.getAsJsonObject("organized").get("to").asString)

        File(root, "sub").mkdirs()
        val explicit = files.call("file_write_bytes", obj(
            "path" to "sub/explicit.bin", "targetVersion" to "missing", "hex" to "00",
        )).getAsJsonObject("data")
        assertEquals(File(root, "sub/explicit.bin").path, explicit.get("path").asString)

        val disabled = files.call("file_write_bytes", obj(
            "path" to "plain.bin", "targetVersion" to "missing", "hex" to "00", "organize" to false,
        )).getAsJsonObject("data")
        assertEquals(File(root, "plain.bin").path, disabled.get("path").asString)

        val edits = com.google.gson.JsonParser().parse(
            """[{"mode":"write_target","matchText":"","writeText":"hello"}]"""
        ).asJsonArray
        val edited = files.call("file_edit_text", obj(
            "path" to "note.txt", "targetVersion" to "missing", "encoding" to "utf-8", "edits" to edits,
        ))
        val editedPath = File(edited.getAsJsonObject("data").get("path").asString)
        assertTrue(editedPath.parentFile.name.matches(Regex("mcp-out-[0-9]{8}")))
        assertTrue(editedPath.isFile)
        assertNotNull(edited.getAsJsonObject("organized"))
    }
}
