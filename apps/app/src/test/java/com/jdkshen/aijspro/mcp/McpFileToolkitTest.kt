package com.jdkshen.aijspro.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files

class McpFileToolkitTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun newToolkit(): Pair<McpFileToolkit, File> {
        val root = temporaryFolder.newFolder("root")
        return McpFileToolkit(root) to root
    }

    private fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject().apply {
        pairs.forEach { (name, value) ->
            when (value) {
                null -> add(name, JsonNull.INSTANCE)
                is JsonElement -> add(name, value)
                is String -> addProperty(name, value)
                is Int -> addProperty(name, value)
                is Long -> addProperty(name, value)
                is Boolean -> addProperty(name, value)
                else -> throw IllegalArgumentException("unsupported test value: $name")
            }
        }
    }

    private fun data(result: JsonObject): JsonObject = result.getAsJsonObject("data")

    private fun readText(
        toolkit: McpFileToolkit,
        path: String,
        targetVersion: String = "",
        startLine: Int = 0,
        startColumn: Int = 0,
        maxChars: Int = 49152,
    ): JsonObject = data(toolkit.call("file_read_text", obj(
        "path" to path,
        "encoding" to "",
        "targetVersion" to targetVersion,
        "limit" to 500,
        "maxChars" to maxChars,
        "startLine" to startLine,
        "startColumn" to startColumn,
    )))

    private fun searchTextArgs(
        path: String,
        pattern: String,
        cursor: String = "",
        limit: Int = 50,
        before: Int = 0,
        after: Int = 0,
    ): JsonObject = obj(
        "path" to path,
        "encoding" to "",
        "pattern" to pattern,
        "patternType" to "literal",
        "caseSensitive" to false,
        "contextBefore" to before,
        "contextAfter" to after,
        "limit" to limit,
        "maxChars" to 4096,
        "cursor" to cursor,
    )

    @Test
    fun definitionsExposeAllOwnToolsWithToolkitCategory() {
        val (toolkit, _) = newToolkit()
        assertEquals(19, McpFileToolkit.names.size)
        assertTrue(McpFileToolkit.names.all { it.startsWith("file_") })
        assertEquals(19, McpFileToolkit.names.toSet().size)

        val definitions = toolkit.definitions()
        assertEquals(19, definitions.size())
        val byName = definitions.associateBy { it.asJsonObject.get("name").asString }
        assertEquals(McpFileToolkit.names.toSet(), byName.keys)
        byName.forEach { (name, element) ->
            val definition = element.asJsonObject
            assertEquals("toolkit", definition.get("category").asString)
            assertEquals("文件与APK工具", definition.get("categoryTitle").asString)
            assertEquals("object", definition.getAsJsonObject("inputSchema").get("type").asString)
            assertTrue("$name should not keep mt_ prefix", !name.startsWith("mt_"))
        }
    }

    @Test
    fun readTextPreservesCrlfAndEditUsesVersion() {
        val (toolkit, root) = newToolkit()
        val file = File(root, "中文.txt")
        file.writeBytes("第一行\r\n第二行\r\n".toByteArray(Charsets.UTF_8))

        // maxChars=3 ends before CR; the next page starts at the delimiter and must keep CRLF whole.
        val first = readText(toolkit, file.name, maxChars = 3)
        assertEquals("第一行", first.get("text").asString)
        assertFalse(first.get("eof").asBoolean)
        assertEquals(0, first.get("endLine").asInt)
        assertEquals(3, first.get("endColumn").asInt)

        val second = readText(toolkit, file.name, startLine = 0, startColumn = 3, maxChars = 2)
        assertEquals("\r\n", second.get("text").asString)
        assertFalse(second.get("eof").asBoolean)

        val third = readText(toolkit, file.name, startLine = 1, startColumn = 0)
        assertEquals("第二行\r\n", third.get("text").asString)
        assertTrue(third.get("eof").asBoolean)

        val full = readText(toolkit, file.name)
        assertEquals("第一行\r\n第二行\r\n", full.get("text").asString)
        assertEquals("utf-8", full.get("encoding").asString)
        val version = full.get("targetVersion").asString
        assertEquals(64, version.length)

        val edits = JsonArray().apply {
            add(obj("mode" to "replace_match", "matchText" to "第二行", "writeText" to "第二行改"))
        }
        val editResult = toolkit.call("file_edit_text", obj(
            "path" to file.name,
            "targetVersion" to version,
            "encoding" to "utf-8",
            "edits" to edits,
        ))
        assertTrue(data(editResult).get("changed").asBoolean)

        val reread = readText(toolkit, file.name)
        assertEquals("第一行\r\n第二行改\r\n", reread.get("text").asString)
        val raw = String(file.readBytes(), Charsets.UTF_8)
        assertTrue(raw.contains("\r\n"))
        assertFalse(raw.contains("\n\n"))
    }

    @Test
    fun staleVersionAndBadExpectedBytesLeaveFilesUntouched() {
        val (toolkit, root) = newToolkit()
        val created = data(toolkit.call("file_write_bytes", obj(
            "path" to "a.bin",
            "targetVersion" to "missing",
            "hex" to "616263",
        )))
        val oldVersion = created.get("targetVersion").asString
        val file = File(root, "a.bin")
        file.writeText("changed")

        val writeError = runCatching {
            toolkit.call("file_write_bytes", obj(
                "path" to "a.bin",
                "targetVersion" to oldVersion,
                "hex" to "00",
            ))
        }.exceptionOrNull()
        assertTrue("expected version conflict, got $writeError", writeError is IOException)
        assertEquals("changed", file.readText())
        assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".aijspro-") })

        val patchFile = File(root, "patch.bin")
        patchFile.writeBytes(byteArrayOf(0, 1, 2))
        val patchVersion = data(toolkit.call("file_stat", obj("path" to patchFile.name)))
            .get("targetVersion").asString
        val patches = JsonArray().apply {
            add(obj("byteOffset" to 0, "expectedHex" to "FFFF", "replacementHex" to "0000"))
        }
        val patchError = runCatching {
            toolkit.call("file_patch_bytes", obj(
                "path" to patchFile.name,
                "targetVersion" to patchVersion,
                "patches" to patches,
            ))
        }.exceptionOrNull()
        assertTrue("expected expectedHex failure, got $patchError", patchError is IOException)
        assertArrayEquals(byteArrayOf(0, 1, 2), patchFile.readBytes())
        assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".aijspro-") })
    }

    @Test
    fun rejectsTraversalAbsoluteOutsideAndEmptyWrites() {
        val (toolkit, _) = newToolkit()
        val outside = temporaryFolder.newFolder("outside")
        File(outside, "secret.txt").writeText("secret")

        listOf(
            "../outside/secret.txt",
            outside.absolutePath,
        ).forEach { path ->
            val error = runCatching {
                toolkit.call("file_stat", obj("path" to path))
            }.exceptionOrNull()
            assertTrue("expected SecurityException for $path, got $error", error is SecurityException)
        }

        val emptyWrite = runCatching {
            toolkit.call("file_write_bytes", obj("path" to "", "targetVersion" to "missing", "hex" to ""))
        }.exceptionOrNull()
        assertTrue(emptyWrite is IllegalArgumentException)

        val list = data(toolkit.call("file_list", obj(
            "path" to "",
            "recursive" to false,
            "cursor" to "",
            "limit" to 10,
        )))
        assertEquals(0, list.get("count").asInt)
    }

    @Test
    fun symbolicLinkIsNotFollowed() {
        val (toolkit, root) = newToolkit()
        val outside = temporaryFolder.newFolder("outside-link")
        val secret = File(outside, "secret.txt").apply { writeText("secret") }
        val link = File(root, "link.txt")
        val created = runCatching { Files.createSymbolicLink(link.toPath(), secret.toPath()) }.isSuccess
        assumeTrue("symlink creation is unavailable: $created", created)

        val error = runCatching {
            toolkit.call("file_stat", obj("path" to link.name))
        }.exceptionOrNull()
        assertTrue("expected SecurityException, got $error", error is SecurityException)
    }

    @Test
    fun listPagesAreDirectoryBoundAndComplete() {
        val (toolkit, root) = newToolkit()
        repeat(5) { File(root, "f$it.txt").writeText("x$it") }
        File(root, "sub").mkdirs()

        val firstResult = toolkit.call("file_list", obj(
            "path" to "",
            "recursive" to false,
            "cursor" to "",
            "limit" to 2,
        ))
        val first = data(firstResult)
        assertEquals(2, first.get("count").asInt)
        val firstCursor = first.get("nextCursor").asString
        assertEquals("file_list", firstResult.getAsJsonArray("nextActions")[0]
            .asJsonObject.get("tool").asString)

        val crossDirectory = runCatching {
            toolkit.call("file_list", obj(
                "path" to "sub",
                "recursive" to false,
                "cursor" to firstCursor,
                "limit" to 2,
            ))
        }.exceptionOrNull()
        assertTrue("cursor must not cross directories, got $crossDirectory", crossDirectory is IllegalArgumentException)

        val names = ArrayList<String>()
        first.getAsJsonArray("entries").forEach { names.add(it.asJsonObject.get("name").asString) }
        var cursor = firstCursor
        var pages = 1
        while (cursor.isNotEmpty()) {
            val page = data(toolkit.call("file_list", obj(
                "path" to "",
                "recursive" to false,
                "cursor" to cursor,
                "limit" to 2,
            )))
            page.getAsJsonArray("entries").forEach { names.add(it.asJsonObject.get("name").asString) }
            val next = page.get("nextCursor")
            cursor = if (next == null || next.isJsonNull) "" else next.asString
            pages++
            assertTrue("listing must terminate", pages < 10)
        }
        assertEquals(6, names.size)
        assertEquals(6, names.toSet().size)
        assertTrue(names.contains("sub"))
        assertEquals(listOf("f0.txt", "f1.txt", "f2.txt", "f3.txt", "f4.txt", "sub"), names)
    }

    @Test
    fun recursiveDeleteNeedsFlagAndRootIsAlwaysProtected() {
        val (toolkit, root) = newToolkit()
        val tree = File(root, "tree/nested").apply { mkdirs() }
        File(tree, "file.txt").writeText("x")

        val withoutFlag = runCatching {
            toolkit.call("file_delete_directory", obj("path" to "tree", "recursive" to false))
        }.exceptionOrNull()
        assertTrue(withoutFlag is IllegalArgumentException)
        assertTrue(File(tree, "file.txt").isFile)

        data(toolkit.call("file_delete_directory", obj("path" to "tree", "recursive" to true)))
        assertFalse(File(root, "tree").exists())

        val rootError = runCatching {
            toolkit.call("file_delete_directory", obj("path" to ".", "recursive" to true))
        }.exceptionOrNull()
        assertTrue(rootError is SecurityException)
        assertTrue(root.isDirectory)
    }

    @Test
    fun bytePatchIsVerifiedAndNonOverlapping() {
        val (toolkit, root) = newToolkit()
        val file = File(root, "patch.bin")
        val created = data(toolkit.call("file_write_bytes", obj(
            "path" to file.name,
            "targetVersion" to "missing",
            "hex" to "00010203AABBCC",
        )))
        val version = created.get("targetVersion").asString

        val patches = JsonArray().apply {
            add(obj("byteOffset" to 3, "expectedHex" to "03AA", "replacementHex" to "FFFF"))
        }
        val patched = data(toolkit.call("file_patch_bytes", obj(
            "path" to file.name,
            "targetVersion" to version,
            "patches" to patches,
        )))
        assertEquals(2L, patched.get("patchedBytes").asLong)

        val read = data(toolkit.call("file_read_bytes", obj(
            "path" to file.name,
            "targetVersion" to "",
            "byteOffset" to 0,
            "maxBytes" to 64,
        )))
        assertEquals("000102FFFFBBCC", read.get("hex").asString)
        assertEquals(7L, file.length())

        val overlapping = JsonArray().apply {
            add(obj("byteOffset" to 0, "expectedHex" to "0001", "replacementHex" to "1111"))
            add(obj("byteOffset" to 1, "expectedHex" to "0102", "replacementHex" to "2222"))
        }
        val overlapError = runCatching {
            toolkit.call("file_patch_bytes", obj(
                "path" to file.name,
                "targetVersion" to read.get("targetVersion").asString,
                "patches" to overlapping,
            ))
        }.exceptionOrNull()
        assertTrue(overlapError is IllegalArgumentException)
        assertEquals(7L, file.length())
    }

    @Test
    fun bomTextSearchPaginationAndAppendKeepEncoding() {
        val (toolkit, root) = newToolkit()
        val file = File(root, "bom.txt")
        val edits = JsonArray().apply {
            add(obj("mode" to "write_target", "matchText" to "", "writeText" to "标题\r\nalpha\r\nbeta alpha\r\n"))
        }
        data(toolkit.call("file_edit_text", obj(
            "path" to file.name,
            "targetVersion" to "missing",
            "encoding" to "utf-8-bom",
            "edits" to edits,
        )))
        val bytes = file.readBytes()
        assertArrayEquals(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()), bytes.copyOfRange(0, 3))
        val read = readText(toolkit, file.name)
        assertEquals("utf-8-bom", read.get("encoding").asString)

        val both = data(toolkit.call("file_search_text", searchTextArgs(file.name, "alpha", before = 1, after = 1)))
        assertEquals(2, both.get("count").asInt)
        assertEquals(2, both.getAsJsonArray("matches")[0].asJsonObject.get("lineNumber").asInt)
        assertEquals(3, both.getAsJsonArray("matches")[1].asJsonObject.get("lineNumber").asInt)
        assertFalse(both.get("truncated").asBoolean)

        val firstPage = data(toolkit.call("file_search_text", searchTextArgs(file.name, "alpha", limit = 1)))
        assertEquals(1, firstPage.get("count").asInt)
        val cursor = firstPage.get("nextCursor").asString
        assertNotNull(cursor)
        val secondPage = data(toolkit.call("file_search_text", searchTextArgs(file.name, "alpha", cursor = cursor, limit = 1)))
        assertEquals(1, secondPage.get("count").asInt)
        assertEquals(3, secondPage.getAsJsonArray("matches")[0].asJsonObject.get("lineNumber").asInt)

        val other = File(root, "other.txt").apply { writeText("alpha") }
        val crossFile = runCatching {
            toolkit.call("file_search_text", searchTextArgs(other.name, "alpha", cursor = cursor, limit = 1))
        }.exceptionOrNull()
        assertTrue("cursor must not cross files, got $crossFile", crossFile is IllegalArgumentException)

        val appended = data(toolkit.call("file_append_text", obj(
            "path" to file.name,
            "targetVersion" to both.get("targetVersion").asString,
            "encoding" to "utf-8-bom",
            "writeText" to "gamma\r\n",
        )))
        assertTrue(appended.get("changed").asBoolean)
        assertEquals("utf-8-bom", appended.get("encoding").asString)
        assertArrayEquals(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()), file.readBytes().copyOfRange(0, 3))
        val afterAppend = readText(toolkit, file.name)
        assertTrue(afterAppend.get("text").asString.endsWith("beta alpha\r\ngamma\r\n"))

        val stale = runCatching {
            toolkit.call("file_search_text", searchTextArgs(file.name, "alpha", cursor = cursor, limit = 1))
        }.exceptionOrNull()
        assertTrue("cursor must be invalidated by file changes, got $stale", stale is IOException)
    }

    @Test
    fun utf16BomRoundTripAndAppendUseDeclaredEncoding() {
        val (toolkit, root) = newToolkit()
        val file = File(root, "u16.txt")
        val edits = JsonArray().apply {
            add(obj("mode" to "write_target", "matchText" to "", "writeText" to "中文\r\n第二行"))
        }
        data(toolkit.call("file_edit_text", obj(
            "path" to file.name,
            "targetVersion" to "missing",
            "encoding" to "utf-16le",
            "edits" to edits,
        )))
        val bytes = file.readBytes()
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xFE.toByte()), bytes.copyOfRange(0, 2))

        val read = readText(toolkit, file.name)
        assertEquals("utf-16le", read.get("encoding").asString)
        assertEquals("中文\r\n第二行", read.get("text").asString)
        val appended = data(toolkit.call("file_append_text", obj(
            "path" to file.name,
            "targetVersion" to read.get("targetVersion").asString,
            "encoding" to "utf-16le",
            "writeText" to "\r\n尾",
        )))
        assertTrue(appended.get("changed").asBoolean)
        val reread = readText(toolkit, file.name)
        assertEquals("utf-16le", reread.get("encoding").asString)
        assertEquals("中文\r\n第二行\r\n尾", reread.get("text").asString)
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xFE.toByte()), file.readBytes().copyOfRange(0, 2))

        // BOM-less UTF-16LE is only addressable with an explicit token; append must not invent a BOM.
        val noBom = File(root, "u16-nobom.txt")
        noBom.writeBytes("无BOM".toByteArray(Charsets.UTF_16LE))
        val noBomRead = data(toolkit.call("file_read_text", obj(
            "path" to noBom.name,
            "encoding" to "utf-16le",
            "targetVersion" to "",
            "limit" to 500,
            "maxChars" to 49152,
            "startLine" to 0,
            "startColumn" to 0,
        )))
        assertEquals("无BOM", noBomRead.get("text").asString)
        data(toolkit.call("file_append_text", obj(
            "path" to noBom.name,
            "targetVersion" to noBomRead.get("targetVersion").asString,
            "encoding" to "utf-16le",
            "writeText" to "!",
        )))
        assertEquals("无BOM!", String(noBom.readBytes(), Charsets.UTF_16LE))
        assertFalse(noBom.readBytes()[0] == 0xFF.toByte() && noBom.readBytes()[1] == 0xFE.toByte())
    }

    @Test
    fun contentSearchUsesAllFiltersAndReportsCompletion() {
        val (toolkit, root) = newToolkit()
        File(root, "hit.txt").writeText("before needle after")
        File(root, "miss.txt").writeText("haystack")
        File(root, "ignored.txt").writeText("needle but filtered")

        val result = data(toolkit.call("file_search", obj(
            "path" to ".",
            "recursive" to true,
            "namePattern" to "hit",
            "namePatternType" to "literal",
            "nameMatchMode" to "contains",
            "nameCaseSensitive" to false,
            "minSize" to -1,
            "maxSize" to -1,
            "minModifiedAt" to "",
            "maxModifiedAt" to "",
            "contentPattern" to "needle",
            "contentPatternType" to "literal",
            "contentCaseSensitive" to false,
            "cursor" to "",
            "limit" to 20,
        )))
        assertEquals(1, result.get("count").asInt)
        assertEquals("hit.txt", result.getAsJsonArray("entries")[0].asJsonObject.get("name").asString)
        assertFalse(result.get("truncated").asBoolean)
        assertEquals("eof", result.get("stopReason").asString)
        assertTrue(result.getAsJsonArray("entries")[0].asJsonObject
            .getAsJsonArray("matchedBy").map { it.asString }.contains("content"))
    }
}
