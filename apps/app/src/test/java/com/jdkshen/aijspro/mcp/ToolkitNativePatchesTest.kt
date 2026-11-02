package com.jdkshen.aijspro.mcp

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ToolkitNativePatchesTest {
    private fun args(vararg values: Pair<String, Any?>) = ToolkitIo.json(*values)

    private fun fails(block: () -> Unit) {
        try {
            block()
            fail("Expected the patch planner to reject the request")
        } catch (_: IllegalArgumentException) {
        } catch (_: IllegalStateException) {
        }
    }

    private fun failsWithMessage(fragment: String, block: () -> Unit) {
        try {
            block()
            fail("Expected failure containing: $fragment")
        } catch (failure: Exception) {
            assertTrue("message was: ${failure.message}", failure.message.orEmpty().contains(fragment))
        }
    }

    @Test fun aarch64InstructionPatchUsesTheMappedVirtualAddressAndStaleGuard() {
        val elf = ToolkitElf(aarch64Elf(intArrayOf(0xd503201f.toInt(), 0xd503201f.toInt())))
        val patch = ToolkitNativePatches.instructions(elf, args(
            "addressKind" to "virtual",
            "address" to "0x10080",
            "instructionMode" to "auto",
            "expectedHex" to "1F2003D5",
            "replacementAssembly" to "ret",
            "paddingMode" to "none",
        ), null)
        assertEquals(128L, patch.byteOffset)
        assertEquals("1F2003D5", ToolkitIo.hex(patch.expected))
        assertEquals("C0035FD6", ToolkitIo.hex(patch.replacement))
        assertEquals("0x10080", patch.metadata.tString("effectiveAddress"))
        assertEquals("aarch64", patch.metadata.tString("instructionSet"))
        assertEquals("builtin", patch.metadata.tString("backend"))
        assertEquals(4, patch.metadata.get("bytesPatched").asInt)
    }

    @Test fun nopPaddingIsCountedAndNoGrowthIsRejected() {
        val elf = ToolkitElf(aarch64Elf(intArrayOf(0xd503201f.toInt(), 0xd503201f.toInt())))
        val padded = ToolkitNativePatches.instructions(elf, args(
            "addressKind" to "virtual",
            "address" to "0x10080",
            "expectedHex" to "1F2003D51F2003D5",
            "replacementAssembly" to "ret",
            "paddingMode" to "nop",
        ), null)
        assertEquals("C0035FD61F2003D5", ToolkitIo.hex(padded.replacement))
        assertEquals(4, padded.metadata.get("nopBytesInserted").asInt)
        assertEquals("C0035FD6", padded.metadata.tString("assembledHex"))

        fails {
            ToolkitNativePatches.instructions(elf, args(
                "addressKind" to "virtual",
                "address" to "0x10080",
                "instructionMode" to "auto",
                "expectedHex" to "1F2003D5",
                "replacementAssembly" to "nop\nnop",
                "paddingMode" to "none",
            ), null)
        }
    }

    @Test fun instructionsRejectStaleBytesAlignmentModesNonExecutableMappingsAndOversizeWindows() {
        val elf = ToolkitElf(aarch64Elf())
        fails {
            ToolkitNativePatches.instructions(elf, args(
                "addressKind" to "virtual", "address" to "0x10080", "instructionMode" to "auto",
                "expectedHex" to "C0035FD6", "replacementAssembly" to "ret", "paddingMode" to "none",
            ), null)
        }
        fails {
            ToolkitNativePatches.instructions(elf, args(
                "addressKind" to "virtual", "address" to "0x10082", "instructionMode" to "auto",
                "expectedHex" to "1F2003D5", "replacementAssembly" to "ret", "paddingMode" to "none",
            ), null)
        }
        fails {
            ToolkitNativePatches.instructions(elf, args(
                "addressKind" to "virtual", "address" to "0x10080", "instructionMode" to "thumb",
                "expectedHex" to "1F2003D5", "replacementAssembly" to "ret", "paddingMode" to "none",
            ), null)
        }
        val dataOnly = ToolkitElf(aarch64Elf(executable = false))
        fails {
            ToolkitNativePatches.instructions(dataOnly, args(
                "addressKind" to "virtual", "address" to "0x10080", "instructionMode" to "auto",
                "expectedHex" to "1F2003D5", "replacementAssembly" to "ret", "paddingMode" to "none",
            ), null)
        }
        fails {
            ToolkitNativePatches.instructions(elf, args(
                "addressKind" to "virtual", "address" to "0x10080", "instructionMode" to "auto",
                "expectedHex" to "00".repeat(260), "replacementAssembly" to "ret", "paddingMode" to "none",
            ), null)
        }
    }

    @Test fun unsupportedAarch64InstructionFallsBackToTheFourArgumentAssembler() {
        val elf = ToolkitElf(aarch64Elf())
        var machine = 0
        var address = 0L
        var source = ""
        var mode = ""
        val patch = ToolkitNativePatches.instructions(elf, args(
            "addressKind" to "virtual",
            "address" to "0x10080",
            "instructionMode" to "auto",
            "expectedHex" to "1F2003D5",
            "replacementAssembly" to "fadd d0, d0, d0",
            "paddingMode" to "none",
        )) { callbackMachine, callbackAddress, callbackSource, callbackMode ->
            machine = callbackMachine; address = callbackAddress; source = callbackSource; mode = callbackMode
            byteArrayOf(0xC0.toByte(), 0x03, 0x5F, 0xD6.toByte())
        }
        assertEquals(183, machine)
        assertEquals(0x10080L, address)
        assertEquals("fadd d0, d0, d0", source)
        assertEquals("aarch64", mode)
        assertEquals("C0035FD6", ToolkitIo.hex(patch.replacement))
        assertEquals("external", patch.metadata.tString("backend"))
    }

    @Test fun missingOptionalAssemblerIsReportedAsDownloadableComponent() {
        val elf = ToolkitElf(aarch64Elf())
        failsWithMessage("aijs-toolchain install cpp") {
            ToolkitNativePatches.instructions(elf, args(
                "addressKind" to "virtual",
                "address" to "0x10080",
                "instructionMode" to "auto",
                "expectedHex" to "1F2003D5",
                "replacementAssembly" to "fadd d0, d0, d0",
                "paddingMode" to "none",
            ), null)
        }
    }

    @Test fun armModeUsesTheOptionalBackendAndTheActualVirtualAddress() {
        val elf = ToolkitElf(armElf())
        var mode = ""
        val patch = ToolkitNativePatches.instructions(elf, args(
            "addressKind" to "virtual",
            "address" to "0x10080",
            "instructionMode" to "arm",
            "expectedHex" to "00F020E3",
            "replacementAssembly" to "mov r0, r0",
            "paddingMode" to "none",
        )) { machine, address, _, callbackMode ->
            assertEquals(40, machine)
            assertEquals(0x10080L, address)
            mode = callbackMode
            byteArrayOf(0x00, 0xF0.toByte(), 0x20, 0xE3.toByte())
        }
        assertEquals("arm", mode)
        assertEquals("arm", patch.metadata.tString("instructionSet"))
        assertEquals("fixed32", patch.metadata.tString("instructionWidth"))
        assertEquals("0x10080", patch.metadata.tString("patchedVirtualAddress"))
    }

    @Test fun x86BoundaryValidationRequiresTheBundledCapstoneLibrary() {
        if (ToolkitNative.available) return // device/JNI environment, covered by integration tests
        val elf = ToolkitElf(x86Elf())
        failsWithMessage("Capstone") {
            ToolkitNativePatches.instructions(elf, args(
                "addressKind" to "virtual",
                "address" to "0x10080",
                "instructionMode" to "auto",
                "expectedHex" to "C3",
                "replacementAssembly" to "ret",
                "paddingMode" to "none",
            ), null)
        }
    }

    @Test fun nullPaddingAllowsGrowthInsideAnExistingZeroWindow() {
        val bytes = aarch64Elf()
        "HELLO".toByteArray(Charsets.UTF_8).copyInto(bytes, 160)
        val patch = ToolkitNativePatches.string(ToolkitElf(bytes), args(
            "addressKind" to "virtual",
            "address" to "0x100A0",
            "expectedText" to "HELLO",
            "replacementText" to "HELLO WORLD",
            "paddingMode" to "null_pad",
        ))
        assertEquals(160L, patch.byteOffset)
        assertEquals("48454C4C4F00000000000000", patch.metadata.tString("beforeHex"))
        assertEquals("48454C4C4F20574F524C4400", patch.metadata.tString("afterHex"))
        assertEquals(12, patch.metadata.get("bytesPatched").asInt)
        assertEquals(6, patch.metadata.get("extraPaddingBytesConsumed").asInt)
        assertTrue(patch.metadata.get("terminatorPreserved").asBoolean)
        assertArrayEquals("HELLO WORLD\u0000".toByteArray(Charsets.UTF_8), patch.replacement)
    }

    @Test fun spacePaddingAllowsGrowthInsideExistingSpaces() {
        val bytes = aarch64Elf()
        "HELLO".toByteArray(Charsets.UTF_8).copyInto(bytes, 160)
        bytes.fill(0x20.toByte(), 165, 171)
        val patch = ToolkitNativePatches.string(ToolkitElf(bytes), args(
            "addressKind" to "virtual",
            "address" to "0x100A0",
            "expectedText" to "HELLO",
            "replacementText" to "HELLO WORLD",
            "paddingMode" to "space_pad",
        ))
        assertEquals("48454C4C4F202020202020", patch.metadata.tString("beforeHex"))
        assertEquals("48454C4C4F20574F524C44", patch.metadata.tString("afterHex"))
        assertFalse(patch.metadata.get("terminatorPreserved").asBoolean)
    }

    @Test fun equalLengthStringPatchAndFileOffsetInputWork() {
        val bytes = aarch64Elf()
        "HELLO".toByteArray(Charsets.UTF_8).copyInto(bytes, 160)
        val patch = ToolkitNativePatches.string(ToolkitElf(bytes), args(
            "addressKind" to "file_offset",
            "address" to "0xA0",
            "expectedText" to "HELLO",
            "replacementText" to "WORLD",
            "paddingMode" to "none",
        ))
        assertEquals(160L, patch.byteOffset)
        assertEquals("WORLD", String(patch.replacement, Charsets.UTF_8))
        assertEquals("0x100a0", patch.metadata.tString("effectiveAddress"))
    }

    @Test fun stringPatchRejectsStaleTextBadPaddingOversizeAndNulText() {
        val bytes = aarch64Elf()
        "HELLO".toByteArray(Charsets.UTF_8).copyInto(bytes, 160)
        val elf = ToolkitElf(bytes)
        fails {
            ToolkitNativePatches.string(elf, args(
                "addressKind" to "virtual", "address" to "0x100A0",
                "expectedText" to "HELLX", "replacementText" to "WORLD", "paddingMode" to "none",
            ))
        }
        val badPadding = aarch64Elf()
        "HELLO".toByteArray(Charsets.UTF_8).copyInto(badPadding, 160)
        badPadding[165] = 0x58.toByte()
        fails {
            ToolkitNativePatches.string(ToolkitElf(badPadding), args(
                "addressKind" to "virtual", "address" to "0x100A0",
                "expectedText" to "HELLO", "replacementText" to "HELLO WORLD", "paddingMode" to "null_pad",
            ))
        }
        fails {
            ToolkitNativePatches.string(elf, args(
                "addressKind" to "virtual", "address" to "0x100A0",
                "expectedText" to "A".repeat(4096), "replacementText" to "B", "paddingMode" to "null_pad",
            ))
        }
        fails {
            ToolkitNativePatches.string(elf, args(
                "addressKind" to "virtual", "address" to "0x100A0",
                "expectedText" to "HE\u0000LLO", "replacementText" to "WORLD", "paddingMode" to "none",
            ))
        }
    }

    @Test fun relocationSlotsAreDataXrefsAndRespectTheScanBudget() {
        val elf = ToolkitElf(relocationElf())
        val found = elf.xref(args(
            "addressKind" to "virtual",
            "address" to "0x10080",
            "scanKind" to "data",
            "maxScanBytes" to 4096,
            "limit" to 10,
        ))
        val items = found.getAsJsonArray("items")
        assertEquals(1, items.size())
        val item = items[0].asJsonObject
        assertEquals("0x10100", item.tString("address"))
        assertEquals(256, item.get("fileOffset").asInt)
        assertEquals("data", item.tString("referenceKind"))
        assertTrue(item.get("relocation").asBoolean)
        assertEquals(1027L, item.get("relocationType").asLong)
        assertEquals("R_AARCH64_RELATIVE", item.tString("relocationTypeName"))
        assertEquals(1, found.get("relocationXrefs").asInt)
        assertEquals(24L, found.get("scannedBytes").asLong)
        assertFalse(found.get("complete").asBoolean)

        val limited = elf.xref(args(
            "addressKind" to "virtual",
            "address" to "0x10080",
            "scanKind" to "data",
            "maxScanBytes" to 4,
            "limit" to 10,
        ))
        assertEquals(0, limited.getAsJsonArray("items").size())
        assertTrue(limited.get("truncated").asBoolean)
        assertTrue(limited.get("relocationScanTruncated").asBoolean)
        assertTrue(limited.get("scannedBytes").asLong <= 4L)
    }

    private fun aarch64Elf(
        code: IntArray = intArrayOf(0xd503201f.toInt()),
        executable: Boolean = true,
    ): ByteArray = ByteArray(512).also { bytes ->
        bytes[0] = 0x7f; bytes[1] = 69; bytes[2] = 76; bytes[3] = 70; bytes[4] = 2; bytes[5] = 1; bytes[6] = 1
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(16, 3); buffer.putShort(18, 183); buffer.putInt(20, 1); buffer.putLong(24, 0x10080)
        buffer.putLong(32, 64); buffer.putShort(52, 64); buffer.putShort(54, 56); buffer.putShort(56, 1); buffer.putShort(58, 64)
        buffer.putInt(64, 1); buffer.putInt(68, if (executable) 5 else 4)
        buffer.putLong(72, 0); buffer.putLong(80, 0x10000); buffer.putLong(96, 512); buffer.putLong(104, 512); buffer.putLong(112, 16384)
        var offset = 128
        code.forEach { buffer.putInt(offset, it); offset += 4 }
    }

    private fun armElf(): ByteArray = ByteArray(512).also { bytes ->
        bytes[0] = 0x7f; bytes[1] = 69; bytes[2] = 76; bytes[3] = 70; bytes[4] = 1; bytes[5] = 1; bytes[6] = 1
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(16, 3); buffer.putShort(18, 40); buffer.putInt(20, 1); buffer.putInt(24, 0x10080)
        buffer.putInt(28, 52); buffer.putShort(40, 52); buffer.putShort(42, 32); buffer.putShort(44, 1)
        buffer.putShort(46, 40)
        buffer.putInt(52, 1); buffer.putInt(56, 0); buffer.putInt(60, 0x10000); buffer.putInt(64, 0x10000)
        buffer.putInt(68, 512); buffer.putInt(72, 512); buffer.putInt(76, 5); buffer.putInt(80, 0x1000)
        buffer.putInt(128, 0xE320F000.toInt())
    }

    private fun x86Elf(): ByteArray = ByteArray(512).also { bytes ->
        bytes[0] = 0x7f; bytes[1] = 69; bytes[2] = 76; bytes[3] = 70; bytes[4] = 2; bytes[5] = 1; bytes[6] = 1
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(16, 3); buffer.putShort(18, 62); buffer.putInt(20, 1); buffer.putLong(24, 0x10080)
        buffer.putLong(32, 64); buffer.putShort(52, 64); buffer.putShort(54, 56); buffer.putShort(56, 1); buffer.putShort(58, 64)
        buffer.putInt(64, 1); buffer.putInt(68, 5)
        buffer.putLong(72, 0); buffer.putLong(80, 0x10000); buffer.putLong(96, 512); buffer.putLong(104, 512); buffer.putLong(112, 16384)
        bytes[128] = 0xC3.toByte()
    }

    private fun relocationElf(): ByteArray {
        val bytes = ByteArray(1536)
        bytes[0] = 0x7f; bytes[1] = 69; bytes[2] = 76; bytes[3] = 70; bytes[4] = 2; bytes[5] = 1; bytes[6] = 1
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(16, 3); buffer.putShort(18, 183); buffer.putInt(20, 1); buffer.putLong(24, 0x10080)
        buffer.putLong(32, 64); buffer.putLong(40, 0x400)
        buffer.putShort(52, 64); buffer.putShort(54, 56); buffer.putShort(56, 1)
        buffer.putShort(58, 64); buffer.putShort(60, 3); buffer.putShort(62, 2)
        buffer.putInt(64, 1); buffer.putInt(68, 5)
        buffer.putLong(72, 0); buffer.putLong(80, 0x10000); buffer.putLong(96, 0x300); buffer.putLong(104, 0x300); buffer.putLong(112, 0x1000)
        buffer.putInt(128, 0xd503201f.toInt())
        val rela = 0x300
        buffer.putLong(rela, 0x10100); buffer.putLong(rela + 8, 1027L); buffer.putLong(rela + 16, 0x10080)
        val names = byteArrayOf(0) + ".rela.dyn".toByteArray(Charsets.UTF_8) + byteArrayOf(0) +
            ".shstrtab".toByteArray(Charsets.UTF_8) + byteArrayOf(0)
        names.copyInto(bytes, 0x340)
        fun section(index: Int, name: Int, type: Int, flags: Long, address: Long, offset: Long, size: Long,
                    link: Int, info: Int, align: Long, entrySize: Long) {
            val p = 0x400 + index * 64
            buffer.putInt(p, name); buffer.putInt(p + 4, type); buffer.putLong(p + 8, flags)
            buffer.putLong(p + 16, address); buffer.putLong(p + 24, offset); buffer.putLong(p + 32, size)
            buffer.putInt(p + 40, link); buffer.putInt(p + 44, info); buffer.putLong(p + 48, align); buffer.putLong(p + 56, entrySize)
        }
        section(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        section(1, 1, 4, 0, 0, 0x300, 24, 0, 0, 8, 24)
        section(2, 11, 3, 0, 0, 0x340, names.size.toLong(), 0, 0, 1, 0)
        return bytes
    }
}
