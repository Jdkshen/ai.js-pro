package com.jdkshen.aijspro.mcp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ToolkitArchiveAlignmentRegressionTest {
    @get:Rule
    val temp = TemporaryFolder()

    private data class Entry(
        val name: String,
        val method: Int,
        val dataOffset: Long,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val directory: Boolean
    )

    @Test
    fun everyStoredFileIsFourByteAlignedAndDeflateIsPreserved() {
        val archive = temp.newFile("mixed.apk")
        val payloads = linkedMapOf(
            "a" to byteArrayOf(1, 2, 3),
            "AndroidManifest.xml" to "<manifest package=\"odd\"/>".toByteArray(),
            "assets/奇数 名称.txt" to "第一行\n第二行\n".toByteArray(),
            "classes.dex" to ByteArray(17) { (it * 3).toByte() },
            "resources.arsc" to ByteArray(23) { it.toByte() },
            "lib/arm64-v8a/lib奇数.so" to ByteArray(5) { 0x7f },
            "lib/armeabi-v7a/libx.so" to ByteArray(7) { (it + 1).toByte() },
            "assets/deflated.bin" to "deflate-content".repeat(100).toByteArray(),
            "lib/x86_64/libcompressed.so" to "compressed-native".repeat(10).toByteArray()
        )
        val stored = setOf("a", "AndroidManifest.xml", "assets/奇数 名称.txt", "classes.dex",
            "resources.arsc", "lib/arm64-v8a/lib奇数.so", "lib/armeabi-v7a/libx.so")
        writeZip(archive, payloads, stored, "assets/")

        val before = entries(archive)
        assertTrue("fixture must start with at least one misaligned stored entry",
            before.any { !it.directory && it.method == ZipEntry.STORED && it.dataOffset % 4L != 0L })

        ToolkitArchiveAlignment.align(archive)
        val after = entries(archive)
        assertEquals(payloads.keys + "assets/", after.map { it.name }.toSet())

        for (entry in after.filterNot { it.directory }) {
            when (entry.method) {
                ZipEntry.STORED -> {
                    assertEquals("stored ${entry.name} must be 4-byte aligned", 0L, entry.dataOffset % 4L)
                    if (entry.name.endsWith(".so")) {
                        assertEquals("stored ${entry.name} must be 16 KiB aligned", 0L, entry.dataOffset % 16384L)
                    }
                }
                ZipEntry.DEFLATED -> assertEquals(
                    "deflate must be preserved for ${entry.name}", ZipEntry.DEFLATED, entry.method)
                else -> error("unexpected method for ${entry.name}: ${entry.method}")
            }
        }
        assertEquals(ZipEntry.DEFLATED, after.single { it.name == "assets/deflated.bin" }.method)
        assertEquals(ZipEntry.DEFLATED, after.single { it.name == "lib/x86_64/libcompressed.so" }.method)

        ZipFile(archive).use { zip ->
            payloads.forEach { (name, bytes) ->
                assertArrayEquals(name, bytes, zip.getInputStream(zip.getEntry(name)).readBytes())
            }
            assertArrayEquals(ByteArray(0), zip.getInputStream(zip.getEntry("assets/")).readBytes())
        }
    }

    @Test
    fun explicitForceStoredKeepsLegacyTargetsAndPreservesOtherDeflate() {
        val archive = temp.newFile("force-stored.apk")
        val payloads = linkedMapOf(
            "a" to byteArrayOf(9, 8, 7),
            "assets/deflated.bin" to "force-me".repeat(260).toByteArray(),
            "lib/arm64-v8a/lib中文.so" to "native-payload".repeat(30).toByteArray(),
            "resources.arsc" to ByteArray(11) { (it + 1).toByte() },
            "assets/odd.dat" to byteArrayOf(0, 1, 2, 3, 4, 5)
        )
        writeZip(archive, payloads, stored = setOf("a", "assets/odd.dat"), "assets/")
        ToolkitArchiveAlignment.align(archive, forceStored = true)

        val after = entries(archive)
        val byName = after.associateBy { it.name }
        assertEquals(ZipEntry.STORED, byName.getValue("a").method)
        assertEquals(ZipEntry.STORED, byName.getValue("assets/odd.dat").method)
        assertEquals(ZipEntry.STORED, byName.getValue("resources.arsc").method)
        assertEquals(ZipEntry.STORED, byName.getValue("lib/arm64-v8a/lib中文.so").method)
        assertEquals(ZipEntry.DEFLATED, byName.getValue("assets/deflated.bin").method)
        for (entry in after.filterNot { it.directory }) {
            if (entry.method == ZipEntry.STORED) {
                assertEquals("STORED ${entry.name} must be 4-byte aligned", 0L, entry.dataOffset % 4L)
                if (entry.name.endsWith(".so")) {
                    assertEquals("forceStored ${entry.name} must be 16 KiB aligned", 0L, entry.dataOffset % 16384L)
                }
            }
        }
        ZipFile(archive).use { zip ->
            payloads.forEach { (name, bytes) ->
                assertArrayEquals(name, bytes, zip.getInputStream(zip.getEntry(name)).readBytes())
            }
        }
    }

    private fun writeZip(file: File, payloads: Map<String, ByteArray>, stored: Set<String>, directory: String) {
        ZipOutputStream(file.outputStream()).use { zip ->
            payloads.forEach { (name, bytes) ->
                val entry = ZipEntry(name).apply { time = 1700000000000L }
                if (name in stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = entry.size
                    entry.crc = CRC32().apply { update(bytes) }.value
                } else {
                    entry.method = ZipEntry.DEFLATED
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
            val dir = ZipEntry(directory).apply {
                time = 1700000000000L
                method = ZipEntry.STORED
                size = 0
                compressedSize = 0
                crc = 0
            }
            zip.putNextEntry(dir)
            zip.closeEntry()
        }
    }

    private fun entries(file: File): List<Entry> {
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val eocd = (bytes.size - 22 downTo 0).firstOrNull { at ->
            buffer.getInt(at) == 0x06054b50 &&
                at + 22 + (buffer.getShort(at + 20).toInt() and 0xffff) == bytes.size
        } ?: error("EOCD missing")
        val count = buffer.getShort(eocd + 10).toInt() and 0xffff
        var p = buffer.getInt(eocd + 16)
        val result = ArrayList<Entry>(count)
        repeat(count) {
            check(buffer.getInt(p) == 0x02014b50)
            val method = buffer.getShort(p + 10).toInt() and 0xffff
            val compressed = buffer.getInt(p + 20).toLong() and 0xffffffffL
            val uncompressed = buffer.getInt(p + 24).toLong() and 0xffffffffL
            val nameLength = buffer.getShort(p + 28).toInt() and 0xffff
            val extraLength = buffer.getShort(p + 30).toInt() and 0xffff
            val commentLength = buffer.getShort(p + 32).toInt() and 0xffff
            val local = buffer.getInt(p + 42).toLong() and 0xffffffffL
            val name = String(bytes, p + 46, nameLength, Charsets.UTF_8)
            check(buffer.getInt(local.toInt()) == 0x04034b50)
            val localName = buffer.getShort(local.toInt() + 26).toInt() and 0xffff
            val localExtra = buffer.getShort(local.toInt() + 28).toInt() and 0xffff
            result.add(Entry(name, method, local + 30L + localName + localExtra,
                compressed, uncompressed, name.endsWith("/")))
            p += 46 + nameLength + extraLength + commentLength
        }
        return result
    }
}
