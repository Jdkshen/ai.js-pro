package com.jdkshen.aijspro.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import net.lingala.zip4j.core.ZipFile as Zip4jFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.util.Zip4jConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Regression coverage for AES-256 creation/update, ZipCrypto compatibility and mandatory
 * authentication checks. These tests are source-level in this task; the parent workflow runs the
 * JVM suite after all workers have finished.
 */
class ToolkitZipCryptoTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var root: File
    private lateinit var toolkit: McpZipToolkit

    private fun newToolkit() {
        root = temporaryFolder.newFolder()
        toolkit = McpZipToolkit(root)
    }

    @Test
    fun aesPasswordsUseUtf8IncludingChineseAndSupplementaryCharacters() {
        newToolkit()
        val password = "中文🔑password"
        File(root, "plain.txt").writeText("unicode password")
        createZip("unicode.zip", listOf("plain.txt" to "plain.txt"), password = password)
        val data = rawEntryData(File(root, "unicode.zip"), "plain.txt")
        val expected = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(
            javax.crypto.spec.PBEKeySpec(password.toCharArray(), data.copyOfRange(0, 16), 1000, 66 * 8)).encoded
        org.junit.Assert.assertArrayEquals(expected.copyOfRange(64, 66), data.copyOfRange(16, 18))
        val read = call("zip_read_bytes", obj("path" to "unicode.zip", "password" to password,
            "entryPath" to "plain.txt", "byteOffset" to 0, "maxBytes" to 100)).getAsJsonObject("data")
        assertEquals(ToolkitIo.hex("unicode password".toByteArray()), read.get("hex").asString)
    }

    @Test
    fun createAesZipAndReadTextBytesTestAndExtract() {
        newToolkit()
        File(root, "plain.txt").writeText("第一行\n第二行\n")
        File(root, "data.bin").writeBytes(byteArrayOf(0, 1, 2, 3, 0xFF.toByte()))

        createZip(
            "aes.zip",
            listOf("plain.txt" to "plain.txt", "data.bin" to "data.bin"),
            password = "s3cret",
        )

        val text = call(
            "zip_read_text",
            obj(
                "path" to "aes.zip",
                "password" to "s3cret",
                "entryPath" to "plain.txt",
                "encoding" to "",
                "startLine" to 0,
                "startColumn" to 0,
                "limit" to 10,
                "maxChars" to 100,
            ),
        ).getAsJsonObject("data")
        assertEquals("第一行\n第二行\n", text.get("text").asString)

        val bytes = call(
            "zip_read_bytes",
            obj(
                "path" to "aes.zip",
                "password" to "s3cret",
                "entryPath" to "data.bin",
                "byteOffset" to 0,
                "maxBytes" to 10,
            ),
        ).getAsJsonObject("data")
        assertEquals("00010203FF", bytes.get("hex").asString)
        assertEquals(5L, bytes.get("totalBytes").asLong)

        val tested = call("zip_test", obj("path" to "aes.zip", "password" to "s3cret"))
            .getAsJsonObject("data")
        assertEquals(2, tested.get("filesChecked").asInt)
        assertEquals(2, tested.get("checkedEntries").asInt)
        assertEquals("passed", tested.get("status").asString)

        call(
            "zip_extract",
            obj(
                "path" to "aes.zip",
                "entries" to JsonArray().apply {
                    add(obj("entryPath" to "", "destinationPath" to "out/"))
                },
                "password" to "s3cret",
                "overwrite" to false,
            ),
        )
        assertEquals("第一行\n第二行\n", File(root, "out/plain.txt").readText())
        assertTrue(File(root, "out/data.bin").readBytes().contentEquals(byteArrayOf(0, 1, 2, 3, 0xFF.toByte())))

        val wrongRead = runCatching {
            call(
                "zip_read_text",
                obj(
                    "path" to "aes.zip",
                    "password" to "wrong",
                    "entryPath" to "plain.txt",
                    "encoding" to "",
                    "startLine" to 0,
                    "startColumn" to 0,
                    "limit" to 10,
                    "maxChars" to 100,
                ),
            )
        }.exceptionOrNull()
        assertTrue(wrongRead is IOException)
        assertTrue(wrongRead?.message.orEmpty().contains("密码"))

        val wrongTest = runCatching {
            call("zip_test", obj("path" to "aes.zip", "password" to "wrong"))
        }.exceptionOrNull()
        assertTrue(wrongTest is IOException)

        val missingPassword = runCatching {
            call("zip_test", obj("path" to "aes.zip", "password" to ""))
        }.exceptionOrNull()
        assertTrue(missingPassword is IOException)
        assertTrue(missingPassword?.message.orEmpty().contains("加密"))
    }

    @Test
    fun zipTestDetectsTamperedAesAuthenticationCode() {
        newToolkit()
        File(root, "secret.txt").writeText("authenticated")
        createZip("aes.zip", listOf("secret.txt" to "secret.txt"), password = "mac-pass")

        val location = rawDataLocation(File(root, "aes.zip"), "secret.txt")
        // The last 10 bytes of an AES entry's compressed data are the HMAC. Corrupting it must
        // leave the plaintext CRC valid, so this specifically exercises the MAC path.
        val macByte = location.first + location.second - 1
        RandomAccessFile(File(root, "aes.zip"), "rw").use { raf ->
            raf.seek(macByte)
            val original = raf.readByte()
            raf.seek(macByte)
            raf.writeByte(original.toInt() xor 0x01)
        }

        val failure = runCatching {
            call("zip_test", obj("path" to "aes.zip", "password" to "mac-pass"))
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(
            "expected authentication failure, got: ${failure?.message}",
            failure?.message.orEmpty().contains("MAC") || failure?.message.orEmpty().contains("认证"),
        )
    }

    @Test
    fun updateWithoutTargetVersionPreservesEncryptedRawBytesAndOldPassword() {
        newToolkit()
        File(root, "keep.txt").writeText("keep")
        File(root, "old.txt").writeText("old")
        createZip(
            "aes.zip",
            listOf("keep.txt" to "keep.txt", "old.txt" to "old.txt"),
            password = "old-pass",
        )
        val keepRawBefore = rawEntryData(File(root, "aes.zip"), "keep.txt")
        val oldRawBefore = rawEntryData(File(root, "aes.zip"), "old.txt")

        File(root, "new.txt").writeText("new")
        val response = call(
            "zip_update",
            obj(
                "path" to "aes.zip",
                "operations" to JsonArray().apply {
                    add(obj("type" to "add", "sourcePath" to "new.txt", "entryPath" to "new.txt", "overwrite" to false))
                    add(obj("type" to "rename", "entryPath" to "old.txt", "newEntryPath" to "renamed.txt"))
                },
                "password" to "",
                // targetVersion intentionally omitted: this is the MT-compatible call shape.
            ),
        )
        assertTrue(response.get("ok").asBoolean)

        assertEquals("keep", readText("aes.zip", "keep.txt", "old-pass"))
        assertEquals("old", readText("aes.zip", "renamed.txt", "old-pass"))
        assertEquals("new", readText("aes.zip", "new.txt", ""))
        assertTrue(keepRawBefore.contentEquals(rawEntryData(File(root, "aes.zip"), "keep.txt")))
        assertTrue(oldRawBefore.contentEquals(rawEntryData(File(root, "aes.zip"), "renamed.txt")))
        assertEquals("passed", call("zip_test", obj("path" to "aes.zip", "password" to "old-pass"))
            .getAsJsonObject("data").get("status").asString)
    }

    @Test
    fun updateWithPasswordEncryptsOnlyNewOrReplacedEntries() {
        newToolkit()
        File(root, "keep.txt").writeText("keep")
        createZip("aes.zip", listOf("keep.txt" to "keep.txt"), password = "old-pass")

        File(root, "new.txt").writeText("new")
        call(
            "zip_update",
            obj(
                "path" to "aes.zip",
                "operations" to JsonArray().apply {
                    add(obj("type" to "add", "sourcePath" to "new.txt", "entryPath" to "new.txt", "overwrite" to false))
                },
                "password" to "new-pass",
            ),
        )
        assertEquals("keep", readText("aes.zip", "keep.txt", "old-pass"))
        assertEquals("new", readText("aes.zip", "new.txt", "new-pass"))

        val wrongOldPassword = runCatching {
            call(
                "zip_read_text",
                obj(
                    "path" to "aes.zip",
                    "password" to "old-pass",
                    "entryPath" to "new.txt",
                    "encoding" to "",
                    "startLine" to 0,
                    "startColumn" to 0,
                    "limit" to 10,
                    "maxChars" to 100,
                ),
            )
        }.exceptionOrNull()
        assertTrue(wrongOldPassword is IOException)
    }

    @Test
    fun readsLegacyZipCryptoEntries() {
        newToolkit()
        val zip = File(root, "legacy.zip")
        writeZipCrypto(zip, listOf("secret.txt" to "zipcrypto".toByteArray()), "legacy-pass")
        assertTrue(
            call(
                "zip_list",
                obj(
                    "path" to "legacy.zip",
                    "entryPath" to "",
                    "recursive" to false,
                    "simple" to false,
                    "cursor" to "",
                    "limit" to 10,
                ),
            ).getAsJsonObject("data").getAsJsonArray("listing")
                .map { it.asString }
                .any { it.endsWith(" e secret.txt") },
        )

        assertEquals("zipcrypto", readText("legacy.zip", "secret.txt", "legacy-pass"))
        assertEquals(
            "passed",
            call("zip_test", obj("path" to "legacy.zip", "password" to "legacy-pass"))
                .getAsJsonObject("data").get("status").asString,
        )
        call(
            "zip_extract",
            obj(
                "path" to "legacy.zip",
                "entries" to JsonArray().apply {
                    add(obj("entryPath" to "secret.txt", "destinationPath" to "legacy-out.txt"))
                },
                "password" to "legacy-pass",
                "overwrite" to false,
            ),
        )
        assertEquals("zipcrypto", File(root, "legacy-out.txt").readText())

        val wrong = runCatching {
            call("zip_test", obj("path" to "legacy.zip", "password" to "wrong"))
        }.exceptionOrNull()
        assertTrue(wrong is IOException)
    }

    @Test
    fun updatePreservesZipCryptoEntryAndPassword() {
        newToolkit()
        val zip = File(root, "legacy.zip")
        writeZipCrypto(zip, listOf("a.txt" to "a".toByteArray()), "legacy-pass")
        val rawBefore = rawEntryData(zip, "a.txt")

        File(root, "c.txt").writeText("c")
        call(
            "zip_update",
            obj(
                "path" to "legacy.zip",
                "operations" to JsonArray().apply {
                    add(obj("type" to "add", "sourcePath" to "c.txt", "entryPath" to "c.txt", "overwrite" to false))
                },
                "password" to "",
            ),
        )
        assertEquals("a", readText("legacy.zip", "a.txt", "legacy-pass"))
        assertEquals("c", readText("legacy.zip", "c.txt", ""))
        assertTrue(rawBefore.contentEquals(rawEntryData(File(root, "legacy.zip"), "a.txt")))
        assertEquals(
            "passed",
            call("zip_test", obj("path" to "legacy.zip", "password" to "legacy-pass"))
                .getAsJsonObject("data").get("status").asString,
        )
    }

    @Test
    fun targetVersionIsOptionalButStillValidatedWhenPresent() {
        newToolkit()
        File(root, "a.txt").writeText("a")
        File(root, "b.txt").writeText("b")
        createZip("v.zip", listOf("a.txt" to "a.txt", "b.txt" to "b.txt"), password = "")
        val firstVersion = listVersion("v.zip")

        File(root, "c.txt").writeText("c")
        call(
            "zip_update",
            obj(
                "path" to "v.zip",
                "operations" to JsonArray().apply {
                    add(obj("type" to "add", "sourcePath" to "c.txt", "entryPath" to "c.txt", "overwrite" to false))
                },
                "password" to "",
            ),
        )
        assertEquals("c", readText("v.zip", "c.txt", ""))
        assertNotEquals(firstVersion, listVersion("v.zip"))

        val before = File(root, "v.zip").readBytes()
        val stale = runCatching {
            call(
                "zip_update",
                obj(
                    "path" to "v.zip",
                    "operations" to JsonArray().apply {
                        add(obj("type" to "delete", "entryPath" to "a.txt"))
                    },
                    "password" to "",
                    "targetVersion" to firstVersion,
                ),
            )
        }.exceptionOrNull()
        assertTrue(stale is IllegalArgumentException)
        assertTrue(stale?.message.orEmpty().contains("版本冲突"))
        assertTrue(before.contentEquals(File(root, "v.zip").readBytes()))
    }

    @Test
    fun aesUpdateReplacesEntryWithoutRequiringOldPassword() {
        newToolkit()
        File(root, "replace.txt").writeText("old")
        createZip("aes.zip", listOf("replace.txt" to "replace.txt"), password = "old-pass")
        File(root, "replacement.txt").writeText("replacement")

        call(
            "zip_update",
            obj(
                "path" to "aes.zip",
                "operations" to JsonArray().apply {
                    add(
                        obj(
                            "type" to "add",
                            "sourcePath" to "replacement.txt",
                            "entryPath" to "replace.txt",
                            "overwrite" to true,
                        ),
                    )
                },
                "password" to "new-pass",
            ),
        )
        assertEquals("replacement", readText("aes.zip", "replace.txt", "new-pass"))
        val oldPassword = runCatching {
            call(
                "zip_read_text",
                obj(
                    "path" to "aes.zip",
                    "password" to "old-pass",
                    "entryPath" to "replace.txt",
                    "encoding" to "",
                    "startLine" to 0,
                    "startColumn" to 0,
                    "limit" to 10,
                    "maxChars" to 100,
                ),
            )
        }.exceptionOrNull()
        assertTrue(oldPassword is IOException)
    }

    // ---------------------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------------------

    private fun call(name: String, args: JsonObject): JsonObject {
        val response = toolkit.call(name, args)
        assertTrue(response.get("ok").asBoolean)
        assertTrue(response.get("error").isJsonNull)
        return response
    }

    private fun createZip(
        path: String,
        sources: List<Pair<String, String>>,
        password: String,
        level: Int = 6,
    ) {
        val response = toolkit.call(
            "zip_create",
            obj(
                "outputPath" to path,
                "sources" to JsonArray().apply {
                    sources.forEach { (source, entry) ->
                        add(obj("sourcePath" to source, "entryPath" to entry))
                    }
                },
                "overwrite" to false,
                "level" to level,
                "password" to password,
            ),
        )
        assertTrue(response.get("ok").asBoolean)
    }

    private fun readText(zipPath: String, entryPath: String, password: String): String =
        call(
            "zip_read_text",
            obj(
                "path" to zipPath,
                "password" to password,
                "entryPath" to entryPath,
                "encoding" to "",
                "startLine" to 0,
                "startColumn" to 0,
                "limit" to 100,
                "maxChars" to 1000,
            ),
        ).getAsJsonObject("data").get("text").asString

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

    /** Locates [dataOffset, compressedSize] for one entry by walking the central directory. */
    private fun rawDataLocation(zip: File, entryName: String): Pair<Long, Long> {
        RandomAccessFile(zip, "r").use { raf ->
            val central = readCentralDirectory(raf)
            var offset = 0
            while (offset < central.size) {
                assertEquals(0x02014b50L, u32(central, offset))
                val nameLength = u16(central, offset + 28)
                val extraLength = u16(central, offset + 30)
                val commentLength = u16(central, offset + 32)
                val name = String(central, offset + 46, nameLength, Charsets.UTF_8)
                val compressedSize = u32(central, offset + 20)
                val localOffset = u32(central, offset + 42)
                if (name == entryName) {
                    raf.seek(localOffset)
                    val local = ByteArray(30)
                    raf.readFully(local)
                    assertEquals(0x04034b50L, u32(local, 0))
                    val localName = u16(local, 26)
                    val localExtra = u16(local, 28)
                    return (localOffset + 30L + localName + localExtra) to compressedSize
                }
                offset += 46 + nameLength + extraLength + commentLength
            }
        }
        error("entry not found: $entryName")
    }

    private fun rawEntryData(zip: File, entryName: String): ByteArray {
        val (dataOffset, compressedSize) = rawDataLocation(zip, entryName)
        RandomAccessFile(zip, "r").use { raf ->
            raf.seek(dataOffset)
            val data = ByteArray(compressedSize.toInt())
            raf.readFully(data)
            return data
        }
    }

    private fun readCentralDirectory(raf: RandomAccessFile): ByteArray {
        val length = raf.length()
        val tailLength = minOf(length, 65_557L + 20L + 56L).toInt()
        val tail = ByteArray(tailLength)
        raf.seek(length - tailLength)
        raf.readFully(tail)
        var eocd = -1
        for (index in tail.size - 22 downTo 0) {
            if (u32(tail, index) == 0x06054b50L) {
                eocd = index
                break
            }
        }
        check(eocd >= 0) { "EOCD not found" }
        val centralSize = u32(tail, eocd + 12)
        val centralOffset = u32(tail, eocd + 16)
        val central = ByteArray(centralSize.toInt())
        raf.seek(centralOffset)
        raf.readFully(central)
        return central
    }

    private fun writeZipCrypto(
        file: File,
        entries: List<Pair<String, ByteArray>>,
        password: String,
    ) {
        file.delete()
        val zip = Zip4jFile(file)
        zip.setFileNameCharset("UTF-8")
        for ((name, bytes) in entries) {
            val parameters = ZipParameters().apply {
                setFileNameInZip(name)
                setSourceExternalStream(true)
                setEncryptFiles(true)
                setEncryptionMethod(Zip4jConstants.ENC_METHOD_STANDARD)
                setCompressionMethod(Zip4jConstants.COMP_DEFLATE)
                setCompressionLevel(6)
                setPassword(password)
            }
            ByteArrayInputStream(bytes).use { zip.addStream(it, parameters) }
        }
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in 0 until 4) {
            value = value or ((bytes[offset + index].toLong() and 0xFF) shl (8 * index))
        }
        return value
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
