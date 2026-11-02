package com.jdkshen.aijspro.mcp

import org.junit.Assert.*
import org.junit.Test

class ToolkitAssemblerTest {
    @Test fun clangReceivesAbsolutePcRelativeTargetsAsLinkerSymbols() {
        val prepared = ToolkitAssemblySource.forClang("start: b 0x10088; adr x0, #0x1008c; ldr x1, [x2, #8]", "aarch64")
        assertEquals(mapOf("_aijs_abs_0" to 0x10088L, "_aijs_abs_1" to 0x1008cL), prepared.absoluteSymbols)
        assertEquals("start: b _aijs_abs_0\nadr x0, _aijs_abs_1\nldr x1, [x2, #8]", prepared.source)
        assertTrue(ToolkitAssemblySource.forClang("b local; local: ret", "aarch64").absoluteSymbols.isEmpty())
    }
    private fun hex(bytes: ByteArray) = ToolkitIo.hex(bytes)

    private fun assertAssembly(source: String, expectedHex: String, base: Long = 0x10080) {
        val actual = ToolkitAssembler.tryAssemble(183, base, source, "aarch64")
        assertNotNull("expected built-in assembly for: $source", actual)
        assertEquals(source, expectedHex, hex(actual!!))
    }

    private fun assertRejected(source: String) {
        try {
            ToolkitAssemblySource.validate(source)
            fail("expected source validation to reject: $source")
        } catch (_: IllegalArgumentException) {
        }
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

    @Test fun labelsAreCaseSensitiveAndCaseOnlyMismatchFallsBack() {
        assertAssembly("Foo:\n    nop\n    b Foo", "1F2003D5FFFFFF17")
        assertNull(
            "foo must never alias Foo",
            ToolkitAssembler.tryAssemble(183, 0x10080, "Foo:\n    nop\n    b foo", "aarch64"),
        )
        assertAssembly(
            "Foo: nop; foo: ret; b Foo; b foo",
            "1F2003D5C0035FD6FEFFFF17FEFFFF17",
        )
    }

    @Test fun semicolonSeparatedStatementsAreAssembledInOrder() {
        assertAssembly("mov x0, #1; ret", "200080D2C0035FD6")
        assertAssembly("start: nop; b start", "1F2003D5FFFFFF17")
        assertAssembly(".inst 0xD503201F; .inst 0xD65F03C0", "1F2003D5C0035FD6")
    }

    @Test fun sourceValidationRejectsDirectiveAndPreprocessorBypasses() {
        listOf(
            ".incbin \"payload.bin\"",
            ".include \"payload.s\"",
            ".incbin: nop",
            ".include: nop",
            "nop; .incbin \"payload.bin\"",
            "label: .include \"payload.s\"",
            "label : .include \"payload.s\"",
            "label: other: .incbin \"payload.bin\"",
            ".inst 0xD503201F; .incbin \"payload.bin\"",
            "nop; .macro evil",
            ".text",
            "nop; .section .text",
            "#include \"payload.h\"",
            "nop; #include \"payload.h\"",
            "label: #include \"payload.h\"",
            "nop /* hide */; .include \"payload.s\"",
            "nop .incbin \"payload.bin\"",
            "nop \\\n.include \"payload.s\"",
            "nop ??/\n.include \"payload.s\"",
            "mov x0, #1\u0000; ret",
            "nop; %:include \"payload.h\"",
            "nop; ??=include \"payload.h\"",
        ).forEach { assertRejected(it) }
    }

    @Test fun sourceValidationKeepsImmediatesLabelsAndSemicolonStatements() {
        listOf(
            "mov x0, #1",
            "mov x0, #-1",
            "mov x0, #0x10",
            "cmp x0, #0",
            "add x0, x1, #1, lsl #12",
            "loop:\n    nop\n    b loop",
            ".inst 0xD503201F",
            "entry : nop",
            ".entry: nop",
            "mov x0, #1; ret",
            ".Lloop: nop; b .Lloop",
            "ldr x0, [sp, #-16]",
            "nop // #include in a comment is not executed",
        ).forEach { ToolkitAssemblySource.validate(it) }
    }

    @Test fun obviousUnsupportedOperandFormsDoNotGetMisencoded() {
        assertAssembly("ldrsw x0, [x1, #4]", "200480B9")
        assertNull(ToolkitAssembler.tryAssemble(183, 0x10080, "ldrsw w0, [x1, #4]", "aarch64"))
        assertNull(ToolkitAssembler.tryAssemble(183, 0x10080, "ldrb x0, [x1]", "aarch64"))
        assertNull(ToolkitAssembler.tryAssemble(183, 0x10080, "mov w0, #0x100000000", "aarch64"))
        assertAssembly("mov w0, #0xffffffff", "00008012")
        assertAssembly("mov w0, #-1", "00008012")
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
