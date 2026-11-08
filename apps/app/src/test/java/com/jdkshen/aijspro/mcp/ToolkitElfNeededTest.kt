package com.jdkshen.aijspro.mcp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ToolkitElfNeededTest {
    private val soname = "libaijsgadget.so"

    @get:Rule val temp = TemporaryFolder()

    @Test fun planSelectsPatchableNativeLibraryInsideApk() {
        val apk = temp.newFile("with-lib.apk")
        ZipOutputStream(apk.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("lib/arm64-v8a/libfoo.so")); zip.write(elf64(sectionHeaders = true)); zip.closeEntry()
            zip.putNextEntry(ZipEntry("lib/arm64-v8a/libbad.so")); zip.write(elf64(room = false)); zip.closeEntry()
        }
        val plan = ToolkitGadgetPreload.plan(apk, "libfoo.so")
        assertTrue(plan.json.toString(), plan.applied)
        assertEquals("lib/arm64-v8a/libfoo.so", plan.targetEntry)
        assertNotNull(plan.patched)
        assertEquals(elf64(sectionHeaders = true).size, plan.patched!!.size)
        assertTrue(plan.json.get("sizeUnchanged").asBoolean)
        assertEquals(1, plan.json.getAsJsonArray("targets").size())
    }

    @Test fun planReportsUnpatchableCandidateWithSpecificReason() {
        val apk = temp.newFile("no-room.apk")
        ZipOutputStream(apk.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("lib/arm64-v8a/libbad.so")); zip.write(elf64(room = false)); zip.closeEntry()
        }
        val plan = ToolkitGadgetPreload.plan(apk, "libbad.so")
        assertFalse(plan.applied)
        assertTrue(plan.json.get("reason").asString.startsWith("DYNSTR_NO_ROOM"))
        assertEquals(1, plan.json.getAsJsonArray("targets").size())
        assertTrue(plan.json.get("nextActions").toString().contains("patchelf"))
    }

    @Test fun patchesElf64WithSectionHeadersInPlace() {
        val input = elf64(sectionHeaders = true)
        val out = ToolkitElfNeeded.patch(input, soname)
        assertTrue(out.reason ?: "", out.applied)
        assertTrue(out.verified)
        assertEquals(input.size, out.bytes!!.size)
        assertEquals(16L, out.strtabOffset)
        assertEquals(3, out.dynamicIndex)
        assertEquals(1L, u32(out.bytes!!, out.dynamicOffset + 3 * 16))
        assertEquals(out.strtabOffset, u64(out.bytes!!, out.dynamicOffset + 3 * 16 + 8))
        assertEquals(soname, cString(out.bytes!!, 256 + out.strtabOffset.toInt()))
        assertOnlyRangesChanged(input, out.bytes!!, out.changedRanges)
    }

    @Test fun patchesElf32WithSectionHeadersInPlace() {
        val input = elf32(sectionHeaders = true)
        val out = ToolkitElfNeeded.patch(input, soname)
        assertTrue(out.reason ?: "", out.applied)
        assertEquals(input.size, out.bytes!!.size)
        assertEquals(1L, u32(out.bytes!!, out.dynamicOffset + out.dynamicIndex * 8))
        assertEquals(out.strtabOffset, u32(out.bytes!!, out.dynamicOffset + out.dynamicIndex * 8 + 4))
        assertOnlyRangesChanged(input, out.bytes!!, out.changedRanges)
    }

    @Test fun patchesStrippedElf64ViaProgramHeaders() {
        val input = elf64(sectionHeaders = false)
        val out = ToolkitElfNeeded.patch(input, soname)
        assertTrue(out.reason ?: "", out.applied)
        assertEquals(input.size, out.bytes!!.size)
        assertEquals(1L, u32(out.bytes!!, out.dynamicOffset + out.dynamicIndex * 16))
    }

    @Test fun patchesStrippedElf32ViaProgramHeaders() {
        val input = elf32(sectionHeaders = false)
        val out = ToolkitElfNeeded.patch(input, soname)
        assertTrue(out.reason ?: "", out.applied)
        assertEquals(input.size, out.bytes!!.size)
    }

    @Test fun reportsNoDynstrRoomAndDoesNotProduceBytes() {
        val out = ToolkitElfNeeded.patch(elf64(room = false), soname)
        assertFalse(out.applied)
        assertTrue(out.reason!!.startsWith("DYNSTR_NO_ROOM:"))
        assertEquals(null, out.bytes)
    }

    @Test fun reportsNoSpareDynamicNullSlot() {
        val out = ToolkitElfNeeded.patch(elf64(spareNull = false), soname)
        assertFalse(out.applied)
        assertEquals("DYNAMIC_NO_SPARE_NULL", out.reason)
        assertTrue(out.nextActionsForTest().contains("patchelf"))
    }

    @Test fun reportsNoDynamicSegment() {
        val out = ToolkitElfNeeded.patch(elf64(dynamic = false), soname)
        assertFalse(out.applied)
        assertEquals("NO_DYNAMIC_SEGMENT", out.reason)
    }

    private fun ToolkitElfNeeded.Outcome.nextActionsForTest(): String =
        json("lib/x.so").getAsJsonArray("nextActions").joinToString(" ") { it.asString }

    private fun assertOnlyRangesChanged(before: ByteArray, after: ByteArray, ranges: List<IntRange>) {
        assertEquals(before.size, after.size)
        for (index in before.indices) {
            if (before[index] == after[index]) continue
            assertTrue("changed byte outside declared ranges: " + index, ranges.any { index in it })
        }
        for (range in ranges) assertTrue("empty changed range", range.first <= range.last)
    }

    private fun u32(bytes: ByteArray, offset: Long): Long =
        ByteBuffer.wrap(bytes, offset.toInt(), 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
    private fun u64(bytes: ByteArray, offset: Long): Long =
        ByteBuffer.wrap(bytes, offset.toInt(), 8).order(ByteOrder.LITTLE_ENDIAN).long
    private fun cString(bytes: ByteArray, offset: Int): String {
        var end = offset
        while (end < bytes.size && bytes[end] != 0.toByte()) end++
        return String(bytes, offset, end - offset, Charsets.UTF_8)
    }

    private fun elf64(sectionHeaders: Boolean = true, room: Boolean = true, spareNull: Boolean = true, dynamic: Boolean = true): ByteArray {
        val bytes = ByteArray(if (sectionHeaders) 1280 else 768)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        header(bytes, b, is64 = true, sectionHeaders = sectionHeaders)
        val dynstrOff = 256; val dynstrSize = if (room) 64 else 16
        "libc.so\u0000libm.so\u0000".toByteArray().copyInto(bytes, dynstrOff)
        val dynamicOff = 512
        val dynamicEntries = mutableListOf(1L to 0L, 5L to dynstrOff.toLong(), 10L to dynstrSize.toLong(), 0L to 0L)
        if (spareNull) dynamicEntries.add(0L to 0L)
        val dynamicSize = dynamicEntries.size * 16
        if (dynamic) {
            writePhdr64(b, 64, 1L, 0L, 0L, bytes.size.toLong(), 16384L)
            writePhdr64(b, 64 + 56, 2L, dynamicOff.toLong(), dynamicOff.toLong(), dynamicSize.toLong(), 0L)
        } else writePhdr64(b, 64, 1L, 0L, 0L, bytes.size.toLong(), 16384L)
        if (dynamic) dynamicEntries.forEachIndexed { i, (tag, value) -> b.putInt(dynamicOff + i * 16, tag.toInt()); b.putLong(dynamicOff + i * 16 + 8, value) }
        if (!dynamic) b.putShort(56, 1)
        if (sectionHeaders) {
            val shOff = 1024
            b.putInt(shOff + 64 + 4, 3) // .dynstr type
            b.putLong(shOff + 64 + 24, dynstrOff.toLong())
            b.putLong(shOff + 64 + 32, dynstrSize.toLong())
            b.putLong(shOff + 64 + 48, 1)
            if (dynamic) {
                b.putInt(shOff + 128 + 4, 6) // .dynamic type
                b.putLong(shOff + 128 + 24, dynamicOff.toLong())
                b.putLong(shOff + 128 + 32, dynamicSize.toLong())
                b.putInt(shOff + 128 + 40, 1) // link dynstr
                b.putLong(shOff + 128 + 56, 16)
            }
        }
        if (dynamic) b.putInt(64, 1)
        return bytes
    }

    private fun elf32(sectionHeaders: Boolean = true, room: Boolean = true, spareNull: Boolean = true, dynamic: Boolean = true): ByteArray {
        val bytes = ByteArray(if (sectionHeaders) 1280 else 768)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        header(bytes, b, is64 = false, sectionHeaders = sectionHeaders)
        val dynstrOff = 256; val dynstrSize = if (room) 64 else 16
        "libc.so\u0000libm.so\u0000".toByteArray().copyInto(bytes, dynstrOff)
        val dynamicOff = 512
        val dynamicEntries = mutableListOf(1L to 0L, 5L to dynstrOff.toLong(), 10L to dynstrSize.toLong(), 0L to 0L)
        if (spareNull) dynamicEntries.add(0L to 0L)
        val dynamicSize = dynamicEntries.size * 8
        writePhdr32(b, 52, 1L, 0L, 0L, bytes.size.toLong(), 16384L)
        if (dynamic) writePhdr32(b, 52 + 32, 2L, dynamicOff.toLong(), dynamicOff.toLong(), dynamicSize.toLong(), 0L)
        if (dynamic) dynamicEntries.forEachIndexed { i, (tag, value) -> b.putInt(dynamicOff + i * 8, tag.toInt()); b.putInt(dynamicOff + i * 8 + 4, value.toInt()) }
        if (!dynamic) b.putShort(44, 1)
        if (sectionHeaders) {
            val shOff = 1024
            b.putInt(shOff + 40 + 4, 3)
            b.putInt(shOff + 40 + 16, dynstrOff)
            b.putInt(shOff + 40 + 20, dynstrSize)
            b.putInt(shOff + 40 + 32, 1)
            if (dynamic) {
                b.putInt(shOff + 80 + 4, 6)
                b.putInt(shOff + 80 + 16, dynamicOff)
                b.putInt(shOff + 80 + 20, dynamicSize)
                b.putInt(shOff + 80 + 24, 1)
                b.putInt(shOff + 80 + 36, 8)
            }
        }
        return bytes
    }

    private fun header(bytes: ByteArray, b: ByteBuffer, is64: Boolean, sectionHeaders: Boolean) {
        bytes[0] = 0x7f; bytes[1] = 'E'.code.toByte(); bytes[2] = 'L'.code.toByte(); bytes[3] = 'F'.code.toByte()
        bytes[4] = if (is64) 2 else 1; bytes[5] = 1; bytes[6] = 1
        if (is64) {
            b.putShort(16, 3); b.putShort(18, 183); b.putInt(20, 1)
            b.putLong(32, 64); b.putLong(40, if (sectionHeaders) 1024 else 0)
            b.putShort(52, 64); b.putShort(54, 56); b.putShort(56, 2)
            b.putShort(58, 64); b.putShort(60, if (sectionHeaders) 4 else 0); b.putShort(62, 0)
        } else {
            b.putShort(16, 3); b.putShort(18, 40); b.putInt(20, 1)
            b.putInt(28, 52); b.putInt(32, if (sectionHeaders) 1024 else 0)
            b.putShort(40, 52); b.putShort(42, 32); b.putShort(44, 2)
            b.putShort(46, 40); b.putShort(48, if (sectionHeaders) 4 else 0); b.putShort(50, 0)
        }
    }

    private fun writePhdr64(b: ByteBuffer, offset: Int, type: Long, fileOffset: Long, vaddr: Long, size: Long, align: Long) {
        b.putInt(offset, type.toInt()); b.putInt(offset + 4, 4)
        b.putLong(offset + 8, fileOffset); b.putLong(offset + 16, vaddr)
        b.putLong(offset + 32, size); b.putLong(offset + 40, size); b.putLong(offset + 48, align)
    }

    private fun writePhdr32(b: ByteBuffer, offset: Int, type: Long, fileOffset: Long, vaddr: Long, size: Long, align: Long) {
        b.putInt(offset, type.toInt()); b.putInt(offset + 4, fileOffset.toInt()); b.putInt(offset + 8, vaddr.toInt())
        b.putInt(offset + 16, size.toInt()); b.putInt(offset + 20, size.toInt()); b.putInt(offset + 24, 4); b.putInt(offset + 28, align.toInt())
    }
}
