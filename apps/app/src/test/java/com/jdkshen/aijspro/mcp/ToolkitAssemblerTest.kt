package com.jdkshen.aijspro.mcp

import org.junit.Assert.*
import org.junit.Test

class ToolkitAssemblerTest {
    private fun hex(bytes: ByteArray) = ToolkitIo.hex(bytes)

    private fun assertAssembly(source: String, expectedHex: String, base: Long = 0x10080) {
        val actual = ToolkitAssembler.tryAssemble(183, base, source, "aarch64")
        assertNotNull("expected built-in assembly for: $source", actual)
        assertEquals(source, expectedHex, hex(actual!!))
    }

    @Test fun commonAarch64InstructionsEncodeAtTheGivenVirtualAddress() {
        assertAssembly("nop", "1F2003D5")
        assertAssembly("ret", "C0035FD6")
        assertAssembly("mov x0, #1", "200080D2")
        assertAssembly("mov w0, #0xffff", "E0FF9F52")
        assertAssembly("mov x0, #0x10000", "2000A0D2")
        assertAssembly("mov x0, #-1", "00008092")
        assertAssembly("add x0, x1, #1", "20040091")
        assertAssembly("sub sp, sp, #16", "FF4300D1")
        assertAssembly("adds x2, x3, x4", "620004AB")
        assertAssembly("cmp x0, #0", "1F0000F1")
        assertAssembly("ldr x0, [x1, #8]", "200440F9")
        assertAssembly("mul x2, x3, x4", "627C049B")
        assertAssembly("adrp x0, 0x11000", "000000B0")
        assertAssembly("stp x29, x30, [sp, #-16]!", "FD7BBFA9")
    }

    @Test fun labelsAndBranchesUseTheRealInstructionAddresses() {
        assertAssembly("loop:\n    nop\n    b loop", "1F2003D5FFFFFF17")
        assertAssembly("target:\n    nop\n    bl target", "1F2003D5FFFFFF97")
        assertAssembly(".inst 0xD503201F", "1F2003D5")
    }

    @Test fun unsupportedFormsReturnNullForTheOptionalBackend() {
        assertNull(ToolkitAssembler.tryAssemble(183, 0x10080, "fadd d0, d0, d0", "aarch64"))
        assertNull(ToolkitAssembler.tryAssemble(183, 0x10080, "ldp x29, x30, [sp], #16", "aarch64"))
        assertNull(ToolkitAssembler.tryAssemble(40, 0x10080, "nop", "arm"))
        assertNull(ToolkitAssembler.tryAssemble(62, 0x10080, "ret", "x86_64"))
    }

    @Test fun modeResolutionClearsTheThumbBitAndRejectsWrongModes() {
        val thumb = ToolkitAssembler.resolve(40, "auto", 0x10081, virtualInput = true, mappedAddress = 0x10081)
        assertEquals("thumb", thumb.instructionSet)
        assertEquals(0x10080L, thumb.effectiveAddress)
        assertEquals("mixed16_32", thumb.instructionWidth)

        val arm = ToolkitAssembler.resolve(40, "arm", 0x10080, virtualInput = true, mappedAddress = 0x10080)
        assertEquals("arm", arm.instructionSet)
        assertEquals(0x10080L, arm.effectiveAddress)

        try {
            ToolkitAssembler.resolve(40, "arm", 0x10081, virtualInput = true, mappedAddress = 0x10081)
            fail("ARM mode with a Thumb T-bit must be rejected")
        } catch (_: IllegalArgumentException) { }
        try {
            ToolkitAssembler.resolve(183, "thumb", 0x10080, virtualInput = true, mappedAddress = 0x10080)
            fail("AArch64 must not accept thumb")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun nopPaddingUsesArchitectureSpecificEncodings() {
        assertEquals("1F2003D51F2003D5", hex(ToolkitAssembler.nopBytes("aarch64", 8)))
        assertEquals("00F020E3", hex(ToolkitAssembler.nopBytes("arm", 4)))
        assertEquals("00BF00BF", hex(ToolkitAssembler.nopBytes("thumb", 4)))
        assertEquals("909090", hex(ToolkitAssembler.nopBytes("x86", 3)))
    }

    @Test fun legacyAssembleSmallStillWorksAndUsesTheExpandedEncoder() {
        val ret = ToolkitElf.assembleSmall("ret", 0x10080)
        assertNotNull(ret)
        assertEquals("C0035FD6", hex(ret!!))
        val stp = ToolkitElf.assembleSmall("stp x29, x30, [sp, #-16]!", 0x10080)
        assertNotNull(stp)
        assertEquals("FD7BBFA9", hex(stp!!))
        assertNull(ToolkitElf.assembleSmall("fadd d0, d0, d0", 0x10080))
    }
}
