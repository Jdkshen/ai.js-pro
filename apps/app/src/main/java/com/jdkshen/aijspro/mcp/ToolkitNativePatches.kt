package com.jdkshen.aijspro.mcp

import com.google.gson.*
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * Semantic ELF string/instruction patch planning. This object only computes the exact
 * equal-length byte window; the caller performs the session write and the stale guard.
 *
 * No part of this file depends on MT. ARM/Thumb/x86/x86-64 assembly uses the optional clang
 * component exposed by McpToolkitProvider; AArch64 common instructions have a built-in
 * encoder. replacementAssembly is gated by [ToolkitAssemblySource] before either backend
 * sees it. The analysis is deliberately local: it does not claim a complete control-flow
 * graph, relocation fix-up, or function-semantics validation.
 *
 * Integration (central router): call [instructions]/[string], then guard the session bytes
 * with Patch.expected at Patch.byteOffset and write Patch.replacement (equal length). Merge
 * Patch.metadata into the tool result. The caller still owns locator/workspace validation,
 * session atomicity, response diagnostics and nextActions.
 */
internal object ToolkitNativePatches {
    data class Patch(
        val byteOffset: Long,
        val expected: ByteArray,
        val replacement: ByteArray,
        val metadata: JsonObject,
    )

    private const val MAX_INSTRUCTION_WINDOW = 256
    private const val MAX_STRING_WINDOW = 4096
    private const val MAX_ASSEMBLY_CHARS = 4096
    private val SUPPORTED_MACHINES = setOf(183, 40, 62, 3)

    /**
     * Plan an instruction patch at the actual virtual address. [assembler] receives
     * (machine, effectiveVirtualAddress, source, instructionSet), where instructionSet is
     * aarch64/arm/thumb/x86/x86_64.
     */
    fun instructions(
        elf: ToolkitElf,
        args: JsonObject,
        assembler: ((Int, Long, String, String) -> ByteArray)?,
    ): Patch {
        val machine = elf.machine
        require(machine in SUPPORTED_MACHINES) { "指令补丁仅支持 AArch64、ARM/Thumb、x86 和 x86-64" }
        require(elf.little) { "指令补丁仅支持小端 ELF" }
        require(elf.wide == (machine == 183 || machine == 62)) { "ELF 位数与指令集架构不匹配" }

        val kind = addressKind(args)
        val rawAddress = ToolkitElf.number(args.tString("address"))
        val expectedHex = args.tString("expectedHex")
        require(expectedHex.isNotEmpty() && expectedHex.length <= MAX_INSTRUCTION_WINDOW * 2) {
            "expectedHex 必须非空且不超过 $MAX_INSTRUCTION_WINDOW 字节"
        }
        val expected = ToolkitIo.unhex(expectedHex)
        require(expected.isNotEmpty() && expected.size <= MAX_INSTRUCTION_WINDOW) {
            "expectedHex 必须非空且不超过 $MAX_INSTRUCTION_WINDOW 字节"
        }
        val requestedMode = args.tString("instructionMode", "auto")
        require(requestedMode in listOf("auto", "arm", "thumb")) { "instructionMode 必须为 auto、arm 或 thumb" }

        // ToolkitElf.address maps file_offset to the loaded virtual address; assembly must use
        // that virtual address, never a zero-based or file-offset base.
        val mappedAddress = elf.address(args)
        val spec = ToolkitAssembler.resolve(machine, requestedMode, rawAddress, kind == "virtual", mappedAddress)
        val effective = spec.effectiveAddress
        val segment = elf.segments.firstOrNull {
            it.type == 1L && it.flags and 1L != 0L && effective >= it.address && effective - it.address < it.size
        } ?: error("地址不属于可执行 LOAD 区段")
        require(effective - segment.address + expected.size <= segment.size) { "补丁窗口越过可执行区段文件范围" }
        val offset = elf.fileOffset(effective) ?: error("地址没有文件内容")
        if (kind == "file_offset") require(offset == rawAddress) { "文件偏移映射不一致" }
        require(offset >= 0 && expected.size <= elf.bytes.size - offset) { "补丁窗口越界" }
        require(elf.fileOffset(effective + expected.size - 1) == offset + expected.size - 1) {
            "补丁窗口跨越了不同的文件映射"
        }

        when (spec.instructionSet) {
            "aarch64", "arm" -> require(expected.size % 4 == 0) { "expectedHex 必须覆盖完整的 4 字节指令" }
            "thumb" -> require(expected.size % 2 == 0) { "expectedHex 必须覆盖完整的 Thumb 指令" }
        }
        requireBoundary(elf, spec, expected, "expectedHex")

        val actual = elf.bytes.copyOfRange(offset.toInt(), offset.toInt() + expected.size)
        require(actual.contentEquals(expected)) {
            "expectedHex 与当前字节不匹配：expected=${ToolkitIo.hex(expected)} actual=${ToolkitIo.hex(actual)}"
        }

        val source = args.tString("replacementAssembly")
        require(source.isNotEmpty() && source.length <= MAX_ASSEMBLY_CHARS) {
            "replacementAssembly 必须非空且不超过 $MAX_ASSEMBLY_CHARS 字符"
        }
        // Single source policy shared with McpToolkitProvider's external backend:
        // no NUL, no preprocessor/file-inclusion directives, only .inst.
        ToolkitAssemblySource.validate(source)

        val builtin = try { ToolkitAssembler.tryAssemble(machine, effective, source, spec.instructionSet) }
        catch (failure: IllegalArgumentException) {
            // A supported mnemonic can still use a valid vector/expression form outside the
            // mini encoder. The shared source gate has already passed; let clang decide.
            if(assembler == null) throw failure else null
        }
        val compiled: ByteArray
        val backend: String
        if (builtin != null) {
            compiled = builtin
            backend = "builtin"
        } else {
            val external = assembler ?: throw IllegalStateException(
                "该 ${spec.instructionSet} 指令需要可选汇编后端；请先在应用终端运行 aijs-toolchain install cpp"
            )
            compiled = try {
                external.invoke(machine, effective, source, spec.instructionSet)
            } catch (failure: Exception) {
                throw IllegalArgumentException(
                    "外部汇编失败（arch=${spec.arch}, mode=${spec.instructionSet}）：${failure.message}", failure
                )
            }
            backend = "external"
        }
        require(compiled.isNotEmpty()) { "汇编结果为空" }
        require(compiled.size <= expected.size) {
            "汇编结果不能增长：assembled=${compiled.size} bytes, window=${expected.size} bytes"
        }
        when (spec.instructionSet) {
            "aarch64", "arm" -> require(compiled.size % 4 == 0) { "汇编结果不是完整的 4 字节指令序列" }
            "thumb" -> require(compiled.size % 2 == 0) { "汇编结果不是完整的 Thumb 指令序列" }
        }
        requireBoundary(elf, spec, compiled, "assembledHex")

        val padding = args.tString("paddingMode", "none")
        require(padding in listOf("none", "nop")) { "paddingMode 必须为 none 或 nop" }
        val remainder = expected.size - compiled.size
        if (padding == "none") require(remainder == 0) {
            "paddingMode=none 要求汇编结果与 expectedHex 等长（assembled=${compiled.size}, window=${expected.size}）"
        }
        val nops = if (padding == "nop") ToolkitAssembler.nopBytes(spec.instructionSet, remainder) else ByteArray(0)
        val after = ByteArray(expected.size)
        compiled.copyInto(after)
        nops.copyInto(after, compiled.size)
        require(after.size == expected.size)
        requireBoundary(elf, spec, after, "afterHex")

        val metadata = ToolkitIo.json(
            "addressKind" to kind,
            "address" to ToolkitElf.hex(rawAddress),
            "instructionMode" to requestedMode,
            "resolvedInstructionMode" to spec.instructionSet,
            "effectiveAddress" to ToolkitElf.hex(effective),
            "mapping" to elf.map(ToolkitIo.json("addressKind" to "virtual", "address" to ToolkitElf.hex(effective))),
            "arch" to spec.arch,
            "archBits" to if (elf.wide) 64 else 32,
            "instructionSet" to spec.instructionSet,
            "instructionWidth" to spec.instructionWidth,
            "patchedVirtualAddress" to ToolkitElf.hex(effective),
            "patchedFileOffset" to ToolkitElf.hex(offset),
            "bytesPatched" to expected.size,
            "expectedHex" to ToolkitIo.hex(expected),
            "assembledHex" to ToolkitIo.hex(compiled),
            "afterHex" to ToolkitIo.hex(after),
            "paddingMode" to padding,
            "nopBytesInserted" to (after.size - compiled.size),
            "backend" to backend,
            "expectedBytes" to expected.size,
            "assembledBytes" to compiled.size,
            "windowLimitBytes" to MAX_INSTRUCTION_WINDOW,
            "instructionBoundary" to if (spec.instructionWidth == "fixed32") "fixed-width" else "capstone",
            "note" to "仅校验局部等长指令替换；不保证控制流、栈平衡、寄存器约定、重定位或函数语义完整",
        )
        return Patch(offset, expected, after, metadata)
    }

    /**
     * Plan a UTF-8 string patch. Growth is allowed only inside already-present NUL padding
     * (null_pad) or ASCII space padding (space_pad); the complete window is at most 4096 bytes.
     */
    fun string(elf: ToolkitElf, args: JsonObject): Patch {
        val kind = addressKind(args)
        val rawAddress = ToolkitElf.number(args.tString("address"))
        val expectedText = args.tString("expectedText")
        val replacementText = args.tString("replacementText", "")
        val padding = args.tString("paddingMode", "none")
        require(padding in listOf("none", "null_pad", "space_pad")) {
            "paddingMode 必须为 none、null_pad 或 space_pad"
        }
        val expected = utf8(expectedText, "expectedText", allowEmpty = false)
        val replacement = utf8(replacementText, "replacementText", allowEmpty = true)
        val window = when (padding) {
            "none" -> {
                require(expected.size == replacement.size) { "paddingMode=none 要求替换文本与 expectedText 等长" }
                expected.size
            }
            "null_pad" -> maxOf(expected.size, replacement.size) + 1
            else -> maxOf(expected.size, replacement.size)
        }
        require(window in 1..MAX_STRING_WINDOW) {
            "字符串补丁窗口必须在 1..$MAX_STRING_WINDOW 字节（null_pad 含末尾 NUL）"
        }
        val offset = when (kind) {
            "virtual" -> elf.fileOffset(rawAddress) ?: error("虚拟地址没有文件内容")
            else -> rawAddress.also {
                require(it in 0L..elf.bytes.size.toLong()) { "文件偏移超出条目范围" }
            }
        }
        require(offset >= 0 && window <= elf.bytes.size - offset) { "字符串补丁窗口越界" }
        if (kind == "virtual") {
            val segment = elf.segments.firstOrNull {
                it.type == 1L && rawAddress >= it.address && rawAddress - it.address < it.size
            }
            require(segment == null || rawAddress - segment.address + window <= segment.size) {
                "字符串补丁窗口越过分段文件范围"
            }
        }

        val before = elf.bytes.copyOfRange(offset.toInt(), offset.toInt() + window)
        val actualText = before.copyOfRange(0, expected.size)
        require(actualText.contentEquals(expected)) {
            "expectedText 与当前字节不匹配：expected=${ToolkitIo.hex(expected)} actual=${ToolkitIo.hex(actualText)}"
        }
        when (padding) {
            "null_pad" -> {
                val end = maxOf(expected.size, replacement.size)
                for (index in expected.size..end) {
                    require(before[index] == 0.toByte()) { "NUL padding 或终止符不足，无法在现有窗口内增长" }
                }
            }
            "space_pad" -> {
                if (replacement.size > expected.size) {
                    for (index in expected.size until replacement.size) {
                        require(before[index] == 0x20.toByte()) { "空格 padding 不足，无法在现有窗口内增长" }
                    }
                }
            }
        }

        val after = ByteArray(window) { if (padding == "space_pad") 0x20.toByte() else 0.toByte() }
        replacement.copyInto(after)
        val extra = if (replacement.size > expected.size) replacement.size - expected.size else 0
        val effective = if (kind == "virtual") rawAddress else elf.virtual(offset)
        val metadata = ToolkitIo.json(
            "addressKind" to kind,
            "address" to ToolkitElf.hex(rawAddress),
            "effectiveAddress" to effective?.let { ToolkitElf.hex(it) },
            "mapping" to elf.map(args),
            "patchedFileOffset" to ToolkitElf.hex(offset),
            "textEncoding" to "utf8",
            "expectedTextByteCount" to expected.size,
            "replacementTextByteCount" to replacement.size,
            "extraPaddingBytesConsumed" to extra,
            "terminatorPreserved" to (padding == "null_pad"),
            "bytesPatched" to window,
            "beforeHex" to ToolkitIo.hex(before),
            "afterHex" to ToolkitIo.hex(after),
            "paddingMode" to padding,
            "limitBytes" to MAX_STRING_WINDOW,
            "endByteOffset" to ToolkitElf.hex(offset + window),
            "entrySize" to elf.bytes.size,
            "growthWithinExistingPadding" to (replacement.size > expected.size),
            "note" to "不调整条目大小、长度字段、指针或重定位；仅使用目标地址后已有的 padding",
        )
        return Patch(offset, before, after, metadata)
    }

    private fun addressKind(args: JsonObject): String {
        val kind = args.tString("addressKind")
        require(kind in listOf("virtual", "file_offset")) { "addressKind 必须为 virtual 或 file_offset" }
        return kind
    }

    private fun utf8(value: String, field: String, allowEmpty: Boolean): ByteArray {
        if (!allowEmpty) require(value.isNotEmpty()) { "$field 不能为空" }
        require(!value.contains('\u0000')) { "$field 不能包含 NUL" }
        val encoder = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val buffer = try {
            encoder.encode(CharBuffer.wrap(value))
        } catch (failure: Exception) {
            throw IllegalArgumentException("$field 不是严格合法的 UTF-8 文本：${failure.message}", failure)
        }
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }

    private fun requireBoundary(elf: ToolkitElf, spec: ToolkitAssembler.Spec, bytes: ByteArray, label: String) {
        when (spec.instructionSet) {
            "aarch64", "arm" -> require(bytes.size % 4 == 0) { "$label 不是完整的 4 字节指令序列" }
            "thumb" -> {
                require(bytes.size % 2 == 0) { "$label 不是完整的 Thumb 指令序列" }
                require(ToolkitNative.available) { "Thumb 指令边界校验需要内置 Capstone 原生库" }
                require(decodesCompletely(elf, spec, bytes, 1)) { "$label 不是完整且可解码的 Thumb 指令序列" }
            }
            "x86", "x86_64" -> {
                require(ToolkitNative.available) { "x86 指令边界校验需要内置 Capstone 原生库" }
                require(decodesCompletely(elf, spec, bytes, 0)) { "$label 不是完整且可解码的 x86 指令序列" }
            }
            else -> throw IllegalArgumentException("未知指令集：${spec.instructionSet}")
        }
    }

    private fun decodesCompletely(elf: ToolkitElf, spec: ToolkitAssembler.Spec, bytes: ByteArray, mode: Int): Boolean {
        if (bytes.isEmpty()) return false
        return try {
            val items = ToolkitNative.decode(bytes, elf.machine, elf.little, spec.effectiveAddress, mode, 16384)
            if (items.isEmpty()) return false
            var consumed = 0
            for (item in items) {
                val offset = item.get("offset")?.asInt ?: return false
                val size = item.get("size")?.asInt ?: return false
                val decoded = item.get("decoded")?.asBoolean ?: false
                if (!decoded || size <= 0 || offset != consumed || consumed + size > bytes.size) return false
                consumed += size
            }
            consumed == bytes.size
        } catch (_: Exception) {
            false
        }
    }
}
