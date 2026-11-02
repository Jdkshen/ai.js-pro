package com.jdkshen.aijspro.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class McpZipToolkitTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var root: File
    private lateinit var toolkit: McpZipToolkit

    private fun newToolkit(): McpZipToolkit {
        root = temporaryFolder.newFolder()
        toolkit = McpZipToolkit(root)
        return toolkit
    }

    @Test
    fun definitionsUseOwnNamesAndToolkitCategory() {
        val definitions = newToolkit().definitions()
        assertEquals(7, definitions.size())
        val expected = listOf(
            "zip_list", "zip_read_text", "zip_read_bytes", "zip_test",
            "zip_extract", "zip_create", "zip_update",
        )
        val actual = definitions.map { it.asJsonObject.get("name").asString }
        assertEquals(expected, actual)
        for (definition in definitions) {
            val obj = definition.asJsonObject
            assertEquals("toolkit", obj.get("category").asString)
            assertEquals("文件与APK工具", obj.get("categoryTitle").asString)
            assertFalse(obj.get("name").asString.startsWith("mt_"))
            assertTrue(obj.get("description").asString.isNotEmpty())
        }
        val update = definitions.first { it.asJsonObject.get("name").asString == "zip_update" }.asJsonObject
        val schema = update.getAsJsonObject("inputSchema")
        val required = schema.getAsJsonArray("required").map { it.asString }
        assertFalse(required.contains("targetVersion"))
        assertTrue(schema.getAsJsonObject("properties").has("targetVersion"))
    }

    @Test
    fun listsChineseEntriesWithRealPagination() {
        newToolkit()
        val zip = File(root, "中文.zip")
        writeZip(
            zip,
            listOf(
                "文档/说明.txt" to "第一行\n第二行\n".toByteArray(),
                "文档/图片.bin" to byteArrayOf(1, 2, 3, 4),
                "readme.md" to "root".toByteArray(),
                "空/" to ByteArray(0),
            ),
        )
        val names = mutableListOf<String>()
        var cursor = ""
        var pages = 0
        do {
            val response = call(
                "zip_list",
                obj(
                    "path" to "中文.zip",
                    "entryPath" to "",
                    "recursive" to true,
                    "simple" to true,
                    "cursor" to cursor,
                    "limit" to 2,
                ),
            )
            val data = response.getAsJsonObject("data")
            assertEquals(zip.canonicalPath, data.get("path").asString)
            assertEquals("", data.get("entryPath").asString)
            assertNotNull(data.get("version").asString)
            data.getAsJsonArray("listing").forEach { names.add(it.asString) }
            val pagination = data.getAsJsonObject("pagination")
            cursor = pagination.get("nextCursor").asString
            val hasMore = pagination.get("hasMore").asBoolean
            if (hasMore) {
                val next = response.getAsJsonArray("nextActions")
                assertEquals(1, next.size())
                val action = next[0].asJsonObject
                assertEquals("zip_list", action.get("tool").asString)
                assertEquals("continue", action.get("purpose").asString)
                assertEquals(cursor, action.getAsJsonObject("arguments").get("cursor").asString)
            } else {
                assertEquals(0, response.getAsJsonArray("nextActions").size())
            }
            pages++
            assertTrue("pagination did not terminate", pages < 10)
        } while (cursor.isNotEmpty())

        assertTrue(names.contains("readme.md"))
        assertTrue(names.contains("文档/"))
        assertTrue(names.contains("文档/说明.txt"))
        assertTrue(names.contains("文档/图片.bin"))
        assertTrue(names.contains("空/"))
        assertEquals(names.size, names.toSet().size)

        val direct = call(
            "zip_list",
            obj(
                "path" to zip.canonicalPath,
                "entryPath" to "文档/",
                "recursive" to false,
                "simple" to true,
                "cursor" to "",
                "limit" to 10,
            ),
        )
        val directNames = direct.getAsJsonObject("data").getAsJsonArray("listing").map { it.asString }
        assertEquals(listOf("图片.bin", "说明.txt"), directNames)

        val detailed = call(
            "zip_list",
            obj(
                "path" to "中文.zip",
                "entryPath" to "",
                "recursive" to false,
                "simple" to false,
                "cursor" to "",
                "limit" to 10,
            ),
        )
        val lines = detailed.getAsJsonObject("data").getAsJsonArray("listing").map { it.asString }
        assertTrue(lines.any { it.endsWith(" 文档/") })
        assertTrue(lines.any { it.endsWith(" 空/") })
    }

    @Test
    fun readsChineseTextAndBytesByPage() {
        newToolkit()
        val zip = File(root, "日志.zip")
        val text = "零行\r\n第一行\n第二行😀尾"
        writeZip(
            zip,
            listOf(
                "日志.txt" to text.toByteArray(),
                "bom.txt" to byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "中文".toByteArray(),
                "bin.dat" to byteArrayOf(0, 1, 0xFF.toByte()),
                "crlf.txt" to "a\r\nb".toByteArray(),
            ),
        )

        val first = call(
            "zip_read_text",
            obj(
                "path" to "日志.zip",
                "password" to "",
                "entryPath" to "日志.txt",
                "encoding" to "",
                "startLine" to 0,
                "startColumn" to 0,
                "limit" to 1,
                "maxChars" to 100,
            ),
        ).getAsJsonObject("data")
        assertEquals("零行\r\n", first.get("text").asString)
        assertEquals("utf-8", first.get("encoding").asString)
        assertEquals(1L, first.get("nextLine").asLong)
        assertEquals(1L, first.get("returnedLineCount").asLong)
        assertTrue(first.get("hasMore").asBoolean)

        val second = call(
            "zip_read_text",
            obj(
                "path" to "日志.zip",
                "password" to "",
                "entryPath" to "日志.txt",
                "encoding" to "utf-8",
                "startLine" to 1,
                "startColumn" to 0,
                "limit" to 1,
                "maxChars" to 100,
            ),
        ).getAsJsonObject("data")
        assertEquals("第一行\n", second.get("text").asString)

        val third = call(
            "zip_read_text",
            obj(
                "path" to "日志.zip",
                "password" to "",
                "entryPath" to "日志.txt",
                "encoding" to "utf-8",
                "startLine" to 2,
                "startColumn" to 3,
                "limit" to 10,
                "maxChars" to 100,
            ),
        ).getAsJsonObject("data")
        assertEquals("😀尾", third.get("text").asString)
        assertEquals(2L, third.get("returnedCodePoints").asLong)
        assertFalse(third.get("hasMore").asBoolean)

        val bom = call(
            "zip_read_text",
            obj(
                "path" to "日志.zip",
                "password" to "",
                "entryPath" to "bom.txt",
                "encoding" to "",
                "startLine" to 0,
                "startColumn" to 0,
                "limit" to 10,
                "maxChars" to 100,
            ),
        ).getAsJsonObject("data")
        assertEquals("utf-8-bom", bom.get("encoding").asString)
        assertEquals("中文", bom.get("text").asString)

        // CRLF is a single logical newline and is returned as one unit even at the edge.
        val crlf = call(
            "zip_read_text",
            obj(
                "path" to "日志.zip",
                "password" to "",
                "entryPath" to "crlf.txt",
                "encoding" to "",
                "startLine" to 0,
                "startColumn" to 0,
                "limit" to 2,
                "maxChars" to 2,
            ),
        ).getAsJsonObject("data")
        assertEquals("a\r\n", crlf.get("text").asString)
        assertEquals(3L, crlf.get("returnedCodePoints").asLong)

        val bytes = call(
            "zip_read_bytes",
            obj(
                "path" to "日志.zip",
                "password" to "",
                "entryPath" to "bin.dat",
                "byteOffset" to 1,
                "maxBytes" to 2,
            ),
        ).getAsJsonObject("data")
        assertEquals("01FF", bytes.get("hex").asString)
        assertEquals(3L, bytes.get("nextByteOffset").asLong)
        assertEquals(3L, bytes.get("totalBytes").asLong)
        assertFalse(bytes.get("hasMore").asBoolean)
    }

    @Test
    fun testVerifiesCrcAndNeverReportsTruncatedScanAsPassed() {
        newToolkit()
        val zip = File(root, "crc.zip")
        writeZip(zip, listOf("a.txt" to "hello".toByteArray()), method = ZipEntry.STORED)
        val passed = call("zip_test", obj("path" to "crc.zip", "password" to ""))
        val data = passed.getAsJsonObject("data")
        assertEquals(true, data.get("complete").asBoolean)
        assertEquals("passed", data.get("status").asString)
        assertEquals(1, data.get("filesChecked").asInt)
        assertEquals(5L, data.get("bytesChecked").asLong)

        val contentOffset = storedEntryContentOffset(zip, "a.txt")
        RandomAccessFile(zip, "rw").use { raf ->
            raf.seek(contentOffset)
            val original = raf.readByte()
            raf.seek(contentOffset)
            raf.writeByte((original.toInt() xor 0x01))
        }
        val error = runCatching { call("zip_test", obj("path" to "crc.zip", "password" to "")) }
            .exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(error?.message.orEmpty().contains("CRC"))

        val big = File(root, "big.zip")
        writeZip(big, listOf("zeros.bin" to ByteArray(2 * 1024 * 1024)))
        toolkit.maxCrcBytes = 1024
        val truncated = runCatching { call("zip_test", obj("path" to "big.zip", "password" to "")) }
            .exceptionOrNull()
        assertTrue(truncated is IOException)
        assertTrue(truncated?.message.orEmpty().contains("未完成"))
        assertTrue(truncated?.message.orEmpty().contains("预算"))
    }

    @Test
    fun reportsEncryptedEntriesWithARealReason() {
        newToolkit()
        val zip = File(root, "encrypted.zip")
        writeZip(zip, listOf("secret.txt" to "hidden".toByteArray()))
        markFirstEntryEncrypted(zip)

        val detailed = call(
            "zip_list",
            obj(
                "path" to "encrypted.zip",
                "entryPath" to "",
                "recursive" to false,
                "simple" to false,
                "cursor" to "",
                "limit" to 10,
            ),
        ).getAsJsonObject("data").getAsJsonArray("listing").map { it.asString }
        assertTrue(detailed.any { it.endsWith(" e secret.txt") })

        val error = runCatching {
            call("zip_test", obj("path" to "encrypted.zip", "password" to ""))
        }.exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(error?.message.orEmpty().contains("加密"))
    }

    @Test
    fun interruptedTestIsReportedAsCancelledNotPassed() {
        newToolkit()
        writeZip(File(root, "interrupt.zip"), listOf("a.txt" to "a".toByteArray()))
        Thread.currentThread().interrupt()
        try {
            val error = runCatching {
                call("zip_test", obj("path" to "interrupt.zip", "password" to ""))
            }.exceptionOrNull()
            assertTrue(error is IOException)
            assertTrue(error?.message.orEmpty().contains("取消"))
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun rejectsZipSlipDuplicateEntriesAndBombBudgets() {
        newToolkit()
        val slip = File(root, "slip.zip")
        writeZip(slip, listOf("../逃逸.txt" to "escape".toByteArray()))
        val slipError = runCatching {
            call(
                "zip_extract",
                obj(
                    "path" to "slip.zip",
                    "entries" to JsonArray().apply {
                        add(obj("entryPath" to "", "destinationPath" to "out/"))
                    },
                    "password" to "",
                    "overwrite" to false,
                ),
            )
        }.exceptionOrNull()
        assertTrue(slipError is SecurityException)
        assertFalse(File(root, "out").exists())

        val backslash = File(root, "backslash.zip")
        writeZip(backslash, listOf("a\\b.txt" to "escape".toByteArray()))
        val backslashError = runCatching {
            call(
                "zip_extract",
                obj(
                    "path" to "backslash.zip",
                    "entries" to JsonArray().apply {
                        add(obj("entryPath" to "", "destinationPath" to "out2/"))
                    },
                    "password" to "",
                    "overwrite" to false,
                ),
            )
        }.exceptionOrNull()
        assertTrue(backslashError is SecurityException)

        val duplicate = File(root, "duplicate.zip")
        writeRawStoredZip(
            duplicate,
            listOf(
                "same.txt" to "first".toByteArray(),
                "same.txt" to "second".toByteArray(),
            ),
        )
        val duplicateError = runCatching {
            call(
                "zip_list",
                obj(
                    "path" to "duplicate.zip",
                    "entryPath" to "",
                    "recursive" to true,
                    "simple" to true,
                    "cursor" to "",
                    "limit" to 10,
                ),
            )
        }.exceptionOrNull()
        assertTrue(duplicateError is IOException)
        assertTrue(duplicateError?.message.orEmpty().contains("重复"))

        val bomb = File(root, "bomb.zip")
        writeZip(bomb, listOf("zeros.bin" to ByteArray(2 * 1024 * 1024)))
        toolkit.maxTotalUncompressed = 1024 * 1024
        val bombError = runCatching {
            call(
                "zip_extract",
                obj(
                    "path" to "bomb.zip",
                    "entries" to JsonArray().apply {
                        add(obj("entryPath" to "", "destinationPath" to "bomb-out/"))
                    },
                    "password" to "",
                    "overwrite" to false,
                ),
            )
        }.exceptionOrNull()
        assertTrue(bombError is IOException)
        assertTrue(bombError?.message.orEmpty().contains("预算") || bombError?.message.orEmpty().contains("大小"))
        assertFalse(File(root, "bomb-out/zeros.bin").exists())
    }

    @Test
    fun updateRequiresVersionAndPreservesOriginalOnFailure() {
        newToolkit()
        val zip = File(root, "v.zip")
        writeZip(zip, listOf("a.txt" to "one".toByteArray(), "docs/old.txt" to "old".toByteArray()))
        val version = listVersion("v.zip")

        File(root, "b.txt").writeText("two")
        val updated = call(
            "zip_update",
            obj(
                "path" to "v.zip",
                "operations" to JsonArray().apply {
                    add(obj("type" to "add", "sourcePath" to "b.txt", "entryPath" to "b.txt", "overwrite" to false))
                    add(obj("type" to "rename", "entryPath" to "docs/old.txt", "newEntryPath" to "docs/new.txt"))
                },
                "password" to "",
                "targetVersion" to version,
            ),
        )
        assertTrue(updated.get("ok").asBoolean)
        assertEquals(3, updated.getAsJsonObject("data").get("entries").asInt)
        assertTrue(readEntry(zip, "b.txt") == "two")
        assertTrue(readEntry(zip, "docs/new.txt") == "old")

        // A stale targetVersion must not touch the archive.
        val before = zip.readBytes()
        val conflict = runCatching {
            call(
                "zip_update",
                obj(
                    "path" to "v.zip",
                    "operations" to JsonArray().apply {
                        add(obj("type" to "delete", "entryPath" to "a.txt"))
                    },
                    "password" to "",
                    "targetVersion" to version,
                ),
            )
        }.exceptionOrNull()
        assertTrue(conflict is IllegalArgumentException)
        assertTrue(conflict?.message.orEmpty().contains("版本冲突"))
        assertContentEquals(before, zip.readBytes())

        // A failing operation (missing source) also leaves the original bytes and no temp files.
        val currentVersion = listVersion("v.zip")
        val beforeFailure = zip.readBytes()
        val failure = runCatching {
            call(
                "zip_update",
                obj(
                    "path" to "v.zip",
                    "operations" to JsonArray().apply {
                        add(obj("type" to "delete", "entryPath" to "missing.txt"))
                    },
                    "password" to "",
                    "targetVersion" to currentVersion,
                ),
            )
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertContentEquals(beforeFailure, zip.readBytes())
        val leftovers = root.listFiles().orEmpty().filter { it.name.contains(".tmp") || it.name.contains(".bak") }
        assertTrue("temporary files left: $leftovers", leftovers.isEmpty())
    }

    @Test
    fun extractHonorsOverwriteAndRejectsSourceArchiveTarget() {
        newToolkit()
        val zip = File(root, "docs.zip")
        writeZip(zip, listOf("docs/a.txt" to "new".toByteArray()))
        val existing = File(root, "out/docs/a.txt")
        existing.parentFile.mkdirs()
        existing.writeText("old")

        val refused = runCatching {
            call(
                "zip_extract",
                obj(
                    "path" to "docs.zip",
                    "entries" to JsonArray().apply {
                        add(obj("entryPath" to "docs/", "destinationPath" to "out/docs/"))
                    },
                    "password" to "",
                    "overwrite" to false,
                ),
            )
        }.exceptionOrNull()
        assertTrue(refused is IllegalArgumentException)
        assertTrue(refused?.message.orEmpty().contains("overwrite"))
        assertEquals("old", existing.readText())

        call(
            "zip_extract",
            obj(
                "path" to "docs.zip",
                "entries" to JsonArray().apply {
                    add(obj("entryPath" to "docs/", "destinationPath" to "out/docs/"))
                },
                "password" to "",
                "overwrite" to true,
            ),
        )
        assertEquals("new", existing.readText())

        val selfTarget = runCatching {
            call(
                "zip_extract",
                obj(
                    "path" to "docs.zip",
                    "entries" to JsonArray().apply {
                        add(obj("entryPath" to "docs/a.txt", "destinationPath" to "docs.zip"))
                    },
                    "password" to "",
                    "overwrite" to true,
                ),
            )
        }.exceptionOrNull()
        assertTrue(selfTarget is SecurityException)

        val conflict = runCatching {
            call(
                "zip_extract",
                obj(
                    "path" to "docs.zip",
                    "entries" to JsonArray().apply {
                        add(obj("entryPath" to "docs/a.txt", "destinationPath" to "same/a.txt"))
                        add(obj("entryPath" to "docs/a.txt", "destinationPath" to "same/a.txt"))
                    },
                    "password" to "",
                    "overwrite" to false,
                ),
            )
        }.exceptionOrNull()
        assertTrue(conflict is IllegalArgumentException)
    }

    @Test
    fun createIncludesHiddenAndEmptyDirectoriesAndRejectsSelfInclusion() {
        newToolkit()
        val source = File(root, "源")
        File(source, "目录").mkdirs()
        File(source, "空目录").mkdirs()
        File(source, "目录/中文.txt").writeText("内容")
        File(source, ".hidden").writeText("hidden")

        val created = call(
            "zip_create",
            obj(
                "outputPath" to "built.zip",
                "sources" to JsonArray().apply {
                    add(obj("sourcePath" to "源/", "entryPath" to ""))
                },
                "overwrite" to false,
                "level" to 6,
                "password" to "",
            ),
        )
        assertEquals(2, created.getAsJsonObject("data").get("files").asInt)
        val listing = call(
            "zip_list",
            obj(
                "path" to "built.zip",
                "entryPath" to "",
                "recursive" to true,
                "simple" to true,
                "cursor" to "",
                "limit" to 100,
            ),
        ).getAsJsonObject("data").getAsJsonArray("listing").map { it.asString }
        assertTrue(listing.contains(".hidden"))
        assertTrue(listing.contains("空目录/"))
        assertTrue(listing.contains("目录/中文.txt"))
        assertEquals("内容", readEntry(File(root, "built.zip"), "目录/中文.txt"))

        val selfError = runCatching {
            call(
                "zip_create",
                obj(
                    "outputPath" to "源/inside.zip",
                    "sources" to JsonArray().apply {
                        add(obj("sourcePath" to "源/", "entryPath" to ""))
                    },
                    "overwrite" to false,
                    "level" to 6,
                    "password" to "",
                ),
            )
        }.exceptionOrNull()
        assertTrue(selfError is IllegalArgumentException)
        assertFalse(File(source, "inside.zip").exists())
    }

    @Test
    fun rejectsPathTraversalAndRootOperations() {
        newToolkit()
        val zip = File(root, "ok.zip")
        writeZip(zip, listOf("a.txt" to "a".toByteArray()))
        val outside = runCatching {
            call(
                "zip_list",
                obj(
                    "path" to "../ok.zip",
                    "entryPath" to "",
                    "recursive" to true,
                    "simple" to true,
                    "cursor" to "",
                    "limit" to 10,
                ),
            )
        }.exceptionOrNull()
        assertTrue(outside is SecurityException)

        val rootOperation = runCatching {
            call(
                "zip_create",
                obj(
                    "outputPath" to "",
                    "sources" to JsonArray().apply {
                        add(obj("sourcePath" to "ok.zip", "entryPath" to "x"))
                    },
                    "overwrite" to false,
                    "level" to 6,
                    "password" to "",
                ),
            )
        }.exceptionOrNull()
        assertTrue(rootOperation is SecurityException)
    }

    @Test
    fun updateSupportsDirectoryAddRenameAndDelete() {
        newToolkit()
        val zip = File(root, "tree.zip")
        writeZip(
            zip,
            listOf(
                "docs/a.txt" to "a".toByteArray(),
                "docs/sub/b.txt" to "b".toByteArray(),
                "keep.txt" to "keep".toByteArray(),
            ),
        )
        val source = File(root, "assets")
        source.mkdirs()
        File(source, "logo.txt").writeText("logo")
        val version = listVersion("tree.zip")
        call(
            "zip_update",
            obj(
                "path" to "tree.zip",
                "operations" to JsonArray().apply {
                    add(obj("type" to "add", "sourcePath" to "assets/", "entryPath" to "newassets/", "overwrite" to false))
                    add(obj("type" to "rename", "entryPath" to "docs/", "newEntryPath" to "moved/", "mergeDirectories" to false))
                    add(obj("type" to "delete", "entryPath" to "keep.txt"))
                },
                "password" to "",
                "targetVersion" to version,
            ),
        )
        val names = listNames("tree.zip")
        assertTrue(names.contains("moved/a.txt"))
        assertTrue(names.contains("moved/sub/b.txt"))
        assertTrue(names.contains("newassets/logo.txt"))
        assertFalse(names.contains("keep.txt"))
        assertFalse(names.contains("docs/a.txt"))

        val nextVersion = listVersion("tree.zip")
        call(
            "zip_update",
            obj(
                "path" to "tree.zip",
                "operations" to JsonArray().apply {
                    add(obj("type" to "delete", "entryPath" to "moved/", "recursive" to true))
                },
                "password" to "",
                "targetVersion" to nextVersion,
            ),
        )
        val afterDelete = listNames("tree.zip")
        assertFalse(afterDelete.any { it.startsWith("moved/") })
        assertTrue(afterDelete.contains("newassets/logo.txt"))
    }

    @Test
    fun readsLegacyGbkEntryNamesWithoutMojibake() {
        newToolkit()
        val zip = File(root, "gbk.zip")
        writeRawStoredZip(
            zip,
            listOf("中文.txt" to "内容".toByteArray(Charsets.UTF_8)),
            nameCharset = Charset.forName("GBK"),
            utf8Names = false,
        )
        assertTrue(listNames("gbk.zip").contains("中文.txt"))
        val data = call(
            "zip_read_text",
            obj(
                "path" to "gbk.zip",
                "password" to "",
                "entryPath" to "中文.txt",
                "encoding" to "",
                "startLine" to 0,
                "startColumn" to 0,
                "limit" to 10,
                "maxChars" to 100,
            ),
        ).getAsJsonObject("data")
        assertEquals("内容", data.get("text").asString)
    }

    @Test
    fun extractCreatesExplicitEmptyDirectories() {
        newToolkit()
        val zip = File(root, "empty-dir.zip")
        writeZip(
            zip,
            listOf(
                "空/" to ByteArray(0),
                "docs/a.txt" to "a".toByteArray(),
            ),
        )
        call(
            "zip_extract",
            obj(
                "path" to "empty-dir.zip",
                "entries" to JsonArray().apply {
                    add(obj("entryPath" to "", "destinationPath" to "unpacked/"))
                },
                "password" to "",
                "overwrite" to false,
            ),
        )
        assertTrue(File(root, "unpacked/空").isDirectory)
        assertEquals("a", File(root, "unpacked/docs/a.txt").readText())
    }

    @Test
    fun rejectsFileWhereAnEmptyDirectoryWouldBeCreated() {
        newToolkit()
        val zip = File(root, "empty-dir-conflict.zip")
        writeZip(zip, listOf("空/" to ByteArray(0)))
        File(root, "blocked").mkdirs()
        val blocked = File(root, "blocked/空").apply { writeText("already a file") }
        val error = runCatching {
            call(
                "zip_extract",
                obj(
                    "path" to "empty-dir-conflict.zip",
                    "entries" to JsonArray().apply {
                        add(obj("entryPath" to "", "destinationPath" to "blocked/"))
                    },
                    "password" to "",
                    "overwrite" to false,
                ),
            )
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertEquals("already a file", blocked.readText())
    }

    @Test
    fun createLevelZeroUsesStoredEntriesAndStaysReadable() {
        newToolkit()
        val source = File(root, "raw")
        source.mkdirs()
        File(source, "one.bin").writeBytes(byteArrayOf(0, 1, 2, 3, 4))
        call(
            "zip_create",
            obj(
                "outputPath" to "stored.zip",
                "sources" to JsonArray().apply {
                    add(obj("sourcePath" to "raw/", "entryPath" to ""))
                },
                "overwrite" to false,
                "level" to 0,
                "password" to "",
            ),
        )
        val bytes = call(
            "zip_read_bytes",
            obj(
                "path" to "stored.zip",
                "password" to "",
                "entryPath" to "one.bin",
                "byteOffset" to 0,
                "maxBytes" to 10,
            ),
        ).getAsJsonObject("data")
        assertEquals("0001020304", bytes.get("hex").asString)
        java.util.zip.ZipFile(File(root, "stored.zip")).use { zf ->
            assertEquals(java.util.zip.ZipEntry.STORED, zf.getEntry("one.bin").method)
        }
    }

    @Test
    fun cursorStopsWorkingWhenArchiveChanges() {
        newToolkit()
        val zip = File(root, "cursor.zip")
        writeZip(zip, listOf("a.txt" to "a".toByteArray(), "b.txt" to "b".toByteArray()))
        val first = call(
            "zip_list",
            obj(
                "path" to "cursor.zip",
                "entryPath" to "",
                "recursive" to false,
                "simple" to true,
                "cursor" to "",
                "limit" to 1,
            ),
        ).getAsJsonObject("data")
        val cursor = first.getAsJsonObject("pagination").get("nextCursor").asString
        assertTrue(cursor.isNotEmpty())

        writeZip(zip, listOf("a.txt" to "a".toByteArray(), "b.txt" to "b".toByteArray(), "c.txt" to "c".toByteArray()))
        val error = runCatching {
            call(
                "zip_list",
                obj(
                    "path" to "cursor.zip",
                    "entryPath" to "",
                    "recursive" to false,
                    "simple" to true,
                    "cursor" to cursor,
                    "limit" to 1,
                ),
            )
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("游标失效"))
    }

    // ---------------------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------------------

    private fun call(name: String, args: JsonObject): JsonObject {
        val response = toolkit.call(name, args)
        assertTrue(response.get("ok").asBoolean)
        assertTrue(response.has("data"))
        assertTrue(response.get("error").isJsonNull)
        assertTrue(response.has("nextActions"))
        return response
    }

    private fun listNames(path: String): List<String> = call(
        "zip_list",
        obj(
            "path" to path,
            "entryPath" to "",
            "recursive" to true,
            "simple" to true,
            "cursor" to "",
            "limit" to 1000,
        ),
    ).getAsJsonObject("data").getAsJsonArray("listing").map { it.asString }

    private fun listVersion(path: String): String = call(
        "zip_list",
        obj(
            "path" to path,
            "entryPath" to "",
            "recursive" to true,
            "simple" to true,
            "cursor" to "",
            "limit" to 100,
        ),
    ).getAsJsonObject("data").get("version").asString

    private fun readEntry(zip: File, name: String): String {
        java.util.zip.ZipFile(zip).use { zf ->
            val entry = zf.getEntry(name)
            assertNotNull("missing entry $name", entry)
            return zf.getInputStream(entry!!).bufferedReader().use { it.readText() }
        }
    }

    private fun writeZip(
        file: File,
        entries: List<Pair<String, ByteArray>>,
        method: Int = ZipEntry.DEFLATED,
    ) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(file))).use { output ->
            if (method == ZipEntry.DEFLATED) output.setLevel(6)
            for ((name, bytes) in entries) {
                val entry = ZipEntry(name)
                if (method == ZipEntry.STORED) {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                output.putNextEntry(entry)
                if (bytes.isNotEmpty()) output.write(bytes)
                output.closeEntry()
            }
        }
    }

    private fun storedEntryContentOffset(zip: File, expectedName: String): Long {
        RandomAccessFile(zip, "rw").use { raf ->
            raf.seek(0)
            assertEquals(0x04034b50L, readU32(raf))
            raf.skipBytes(22)
            val nameLength = readU16(raf)
            val extraLength = readU16(raf)
            val nameBytes = ByteArray(nameLength)
            raf.readFully(nameBytes)
            assertEquals(expectedName, String(nameBytes, Charsets.UTF_8))
            raf.skipBytes(extraLength)
            return raf.filePointer
        }
    }

    /** Raw STORED ZIP writer used to produce duplicate names, which ZipOutputStream rejects. */
    private fun writeRawStoredZip(
        file: File,
        entries: List<Pair<String, ByteArray>>,
        nameCharset: Charset = Charsets.UTF_8,
        utf8Names: Boolean = true,
    ) {
        val central = ArrayList<ByteArray>()
        var offset = 0L
        FileOutputStream(file).use { out ->
            for ((name, bytes) in entries) {
                val nameBytes = name.toByteArray(nameCharset)
                val crc = CRC32().apply { update(bytes) }.value
                val local = ByteArray(30)
                putU32(local, 0, 0x04034b50L)
                putU16(local, 4, 20)
                putU16(local, 6, if (utf8Names) 0x0800 else 0)
                putU16(local, 8, 0)
                putU16(local, 10, 0)
                putU16(local, 12, 0)
                putU32(local, 14, crc)
                putU32(local, 18, bytes.size.toLong())
                putU32(local, 22, bytes.size.toLong())
                putU16(local, 26, nameBytes.size)
                putU16(local, 28, 0)
                out.write(local)
                out.write(nameBytes)
                out.write(bytes)
                val centralRecord = ByteArray(46 + nameBytes.size)
                putU32(centralRecord, 0, 0x02014b50L)
                putU16(centralRecord, 4, 20)
                putU16(centralRecord, 6, 20)
                putU16(centralRecord, 8, if (utf8Names) 0x0800 else 0)
                putU16(centralRecord, 10, 0)
                putU16(centralRecord, 12, 0)
                putU16(centralRecord, 14, 0)
                putU32(centralRecord, 16, crc)
                putU32(centralRecord, 20, bytes.size.toLong())
                putU32(centralRecord, 24, bytes.size.toLong())
                putU16(centralRecord, 28, nameBytes.size)
                putU16(centralRecord, 30, 0)
                putU16(centralRecord, 32, 0)
                putU16(centralRecord, 34, 0)
                putU16(centralRecord, 36, 0)
                putU32(centralRecord, 38, 0)
                putU32(centralRecord, 42, offset)
                System.arraycopy(nameBytes, 0, centralRecord, 46, nameBytes.size)
                central.add(centralRecord)
                offset += 30L + nameBytes.size + bytes.size
            }
            val centralStart = offset
            var centralSize = 0L
            for (record in central) {
                out.write(record)
                centralSize += record.size
            }
            val eocd = ByteArray(22)
            putU32(eocd, 0, 0x06054b50L)
            putU16(eocd, 4, 0)
            putU16(eocd, 6, 0)
            putU16(eocd, 8, entries.size)
            putU16(eocd, 10, entries.size)
            putU32(eocd, 12, centralSize)
            putU32(eocd, 16, centralStart)
            putU16(eocd, 20, 0)
            out.write(eocd)
        }
    }

    private fun markFirstEntryEncrypted(zip: File) {
        RandomAccessFile(zip, "rw").use { raf ->
            val eocd = ByteArray(22)
            raf.seek(raf.length() - 22)
            raf.readFully(eocd)
            assertEquals(0x06054b50L, u32(eocd, 0))
            val centralStart = u32(eocd, 16)
            val central = ByteArray(46)
            raf.seek(centralStart)
            raf.readFully(central)
            assertEquals(0x02014b50L, u32(central, 0))
            val localOffset = u32(central, 42)
            val centralFlags = u16(central, 8) or 0x0001
            putU16(central, 8, centralFlags)
            raf.seek(centralStart)
            raf.write(central)

            val local = ByteArray(10)
            raf.seek(localOffset)
            raf.readFully(local)
            assertEquals(0x04034b50L, u32(local, 0))
            putU16(local, 6, u16(local, 6) or 0x0001)
            raf.seek(localOffset)
            raf.write(local)
        }
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 4) value = value or ((bytes[offset + i].toLong() and 0xFF) shl (8 * i))
        return value
    }

    private fun readU16(raf: RandomAccessFile): Int {
        val b0 = raf.read()
        val b1 = raf.read()
        return (b0 and 0xFF) or ((b1 and 0xFF) shl 8)
    }

    private fun readU32(raf: RandomAccessFile): Long {
        var value = 0L
        for (i in 0 until 4) value = value or ((raf.read().toLong() and 0xFF) shl (8 * i))
        return value
    }

    private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun putU32(bytes: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 4) bytes[offset + i] = ((value ushr (8 * i)) and 0xFF).toByte()
    }

    private fun assertContentEquals(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.size, actual.size)
        assertTrue(expected.contentEquals(actual))
    }

    private fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject().apply {
        for ((key, value) in pairs) {
            when (value) {
                null -> add(key, JsonNull.INSTANCE)
                is String -> addProperty(key, value)
                is Boolean -> addProperty(key, value)
                is Int -> addProperty(key, value)
                is Long -> addProperty(key, value)
                is JsonElement -> add(key, value)
                else -> error("unsupported test value: $value")
            }
        }
    }
}
