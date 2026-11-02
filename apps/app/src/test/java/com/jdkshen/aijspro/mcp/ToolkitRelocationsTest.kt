package com.jdkshen.aijspro.mcp

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ToolkitRelocationsTest {
    @Test fun nativeSearchDoesNotAbortOnAnUnnamedRelocation() {
        val bytes = fixture()
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putLong(304, (3L shl 32) or 260L)
        val search = ToolkitApkSearch(listOf("lib/arm64-v8a/test.so"), { bytes }, { null }, { emptySequence() })
        val result = search.search(ToolkitIo.json("target" to "native_symbols", "query" to "target"))
        assertFalse(result.partial)
        assertTrue(result.hits.isNotEmpty())
    }
    @Test fun relocationListsHaveNamesAndXrefsDoNotGuessUnsupportedFormulas() {
        val elf = ToolkitElf(fixture())
        val listed = elf.items("relocations", "target")
        assertEquals(2, listed.size)
        assertTrue(listed.all { it.tString("name") == "target" })
        assertTrue(elf.items("relocations", "absent").isEmpty())
        val refs = elf.xref(ToolkitIo.json("addressKind" to "virtual", "address" to "0x100a0",
            "instructionMode" to "auto", "scanKind" to "data", "referenceKinds" to listOf("data"),
            "maxScanBytes" to 4096, "limit" to 50)).getAsJsonArray("items")
            .map { it.asJsonObject }.filter { it.get("relocation")?.asBoolean == true }
        assertEquals(1, refs.size)
        assertEquals("0x10080", refs.single().tString("address"))
    }

    private fun fixture(): ByteArray {
        val bytes = ByteArray(1024)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        byteArrayOf(0x7f, 69, 76, 70, 2, 1, 1).copyInto(bytes)
        b.putShort(16, 3); b.putShort(18, 183); b.putInt(20, 1)
        b.putLong(32, 64); b.putLong(40, 512)
        b.putShort(52, 64); b.putShort(54, 56); b.putShort(56, 1)
        b.putShort(58, 64); b.putShort(60, 6); b.putShort(62, 5)
        b.putInt(64, 1); b.putInt(68, 6); b.putLong(80, 0x10000)
        b.putLong(96, 1024); b.putLong(104, 1024); b.putLong(112, 4096)
        "\u0000target\u0000".toByteArray().copyInto(bytes, 200)
        b.putInt(248, 1); bytes[252] = 0x11; b.putShort(254, 1)
        b.putLong(256, 0x100a0); b.putLong(264, 8)
        b.putLong(272, 0x10080); b.putLong(280, (1L shl 32) or 257L)
        b.putLong(296, 0x10088); b.putLong(304, (1L shl 32) or 260L)
        val names = "\u0000.data\u0000.dynstr\u0000.dynsym\u0000.rela.data\u0000.shstrtab\u0000"
        names.toByteArray().copyInto(bytes, 336)
        fun section(index: Int, name: String, type: Int, offset: Long, size: Long, link: Int = 0, stride: Long = 0) {
            val p = 512 + index * 64
            b.putInt(p, names.indexOf(name)); b.putInt(p + 4, type)
            b.putLong(p + 16, 0x10000 + offset); b.putLong(p + 24, offset); b.putLong(p + 32, size)
            b.putInt(p + 40, link); b.putLong(p + 56, stride)
        }
        section(1, ".data", 1, 128, 64)
        section(2, ".dynstr", 3, 200, 8)
        section(3, ".dynsym", 11, 224, 48, 2, 24)
        section(4, ".rela.data", 4, 272, 48, 3, 24)
        section(5, ".shstrtab", 3, 336, names.length.toLong())
        return bytes
    }
}
