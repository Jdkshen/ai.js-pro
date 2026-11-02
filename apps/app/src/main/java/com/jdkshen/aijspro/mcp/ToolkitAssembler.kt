package com.jdkshen.aijspro.mcp

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Dependency-free AArch64 mini assembler and architecture/mode routing for native patches.
 *
 * The built-in assembler intentionally covers the common instruction forms used by small
 * code patches (branches, moves, integer arithmetic, ADR/ADRP, common loads/stores and
 * calls). Anything outside that set returns null so the caller can use the optional clang
 * component. ARM, Thumb, x86 and x86-64 always use the optional clang component.
 *
 * Labels are case-sensitive, like the external assembler. A branch to a case-only
 * variant returns null instead of binding Foo to foo; the external backend then
 * reports the real (case-sensitive) symbol error. Newlines and semicolons both
 * delimit statements.
 */
internal object ToolkitAssembler {
    /** Resolved architecture/mode and the virtual address that must be used as the assembler base. */
    data class Spec(
        val machine: Int,
        val arch: String,
        val instructionSet: String,
        val instructionWidth: String,
        val effectiveAddress: Long,
    )

    private data class Reg(val n: Int, val is64: Boolean, val sp: Boolean = false, val zero: Boolean = false)
    private data class Shift(val type: Int, val amount: Int)
    private data class Mem(val base: String, val offset: String?, val preIndex: Boolean)
    private data class Item(
        val labels: List<String>,
        val mnemonic: String?,
        val operands: List<String>,
        val inst: Long?,
        val line: Int,
    )

    private data class Labels(val exact: Map<String, Long>, val folded: Set<String>)

    /** Internal signal: the source used a case-only variant of a known label. */
    private class LabelCaseMismatch : RuntimeException()

    private const val MAX_INSTRUCTIONS = 4096
    private const val MAX_SOURCE = 65536
    private val LABEL_PREFIX = Regex("^([A-Za-z_.$][A-Za-z0-9_.$]*):\\s*(.*)$")
    private val REGISTER = Regex("^([xw])([0-9]{1,2})$")
    private val SHIFT = Regex("^lsl\\s+#?(\\d+)$")

    /**
     * Resolve the requested mode. [rawAddress] is the caller's address (the Thumb T-bit may
     * be set); [mappedAddress] is the actual even virtual address used for mapping.
     */
    fun resolve(machine: Int, requestedMode: String, rawAddress: Long, virtualInput: Boolean, mappedAddress: Long): Spec {
        val mode = requestedMode.trim().lowercase()
        return when (machine) {
            183 -> {
                require(mode in listOf("auto", "aarch64")) { "AArch64 只接受 instructionMode=auto" }
                require(mappedAddress % 4 == 0L) { "AArch64 指令地址必须 4 字节对齐" }
                Spec(machine, "aarch64", "aarch64", "fixed32", mappedAddress)
            }
            62 -> {
                require(mode in listOf("auto", "x86_64")) { "x86-64 只接受 instructionMode=auto" }
                Spec(machine, "x86_64", "x86_64", "variable", mappedAddress)
            }
            3 -> {
                require(mode in listOf("auto", "x86")) { "x86 只接受 instructionMode=auto" }
                Spec(machine, "x86", "x86", "variable", mappedAddress)
            }
            40 -> {
                require(mode in listOf("auto", "arm", "thumb")) { "ARM32 只接受 instructionMode=auto/arm/thumb" }
                val thumb = when (mode) {
                    "thumb" -> true
                    "arm" -> {
                        require(!virtualInput || rawAddress and 1L == 0L) {
                            "ARM 模式地址不能带 Thumb T-bit；请使用 instructionMode=thumb"
                        }
                        false
                    }
                    else -> virtualInput && (rawAddress and 1L) != 0L
                }
                val effective = if (thumb) mappedAddress and -2L else mappedAddress
                if (thumb) require(effective % 2 == 0L) { "Thumb 指令地址必须 2 字节对齐" }
                else require(effective % 4 == 0L) { "ARM 指令地址必须 4 字节对齐；2 字节对齐地址请显式使用 thumb" }
                if (thumb) Spec(machine, "arm", "thumb", "mixed16_32", effective)
                else Spec(machine, "arm", "arm", "fixed32", effective)
            }
            else -> throw IllegalArgumentException("指令补丁仅支持 AArch64、ARM/Thumb、x86 和 x86-64")
        }
    }

    /**
     * Try the built-in assembler. Returns null when the architecture or any instruction form
     * is not covered by the built-in encoder (the caller may then use clang).
     */
    fun tryAssemble(machine: Int, address: Long, source: String, instructionMode: String): ByteArray? {
        // Apply the shared policy before either backend sees the text.
        ToolkitAssemblySource.validate(source)
        if (machine != 183) return null
        require(instructionMode.trim().lowercase() in listOf("auto", "aarch64")) {
            "AArch64 只接受 instructionMode=auto"
        }
        return try {
            assembleAArch64(address, source)
        } catch (_: LabelCaseMismatch) {
            // The spelling only differs by case from a known label. Never alias it;
            // let the case-sensitive external assembler handle (and report) it.
            null
        }
    }

    /** Architecture-specific NOP bytes for padding. */
    fun nopBytes(instructionSet: String, count: Int): ByteArray {
        require(count >= 0) { "NOP 填充长度无效" }
        return when (instructionSet) {
            "aarch64" -> {
                require(count % 4 == 0) { "AArch64 NOP 填充必须 4 字节对齐" }
                val one = byteArrayOf(0x1f, 0x20, 0x03, 0xd5.toByte())
                ByteArray(count) { one[it % 4] }
            }
            "arm" -> {
                require(count % 4 == 0) { "ARM NOP 填充必须 4 字节对齐" }
                val one = byteArrayOf(0x00, 0xf0.toByte(), 0x20, 0xe3.toByte())
                ByteArray(count) { one[it % 4] }
            }
            "thumb" -> {
                require(count % 2 == 0) { "Thumb NOP 填充必须 2 字节对齐" }
                val one = byteArrayOf(0x00, 0xbf.toByte())
                ByteArray(count) { one[it % 2] }
            }
            "x86", "x86_64" -> ByteArray(count) { 0x90.toByte() }
            else -> throw IllegalArgumentException("未知指令集：$instructionSet")
        }
    }

    fun instructionWidth(instructionSet: String): String = when (instructionSet) {
        "thumb" -> "mixed16_32"
        "x86", "x86_64" -> "variable"
        "aarch64", "arm" -> "fixed32"
        else -> throw IllegalArgumentException("未知指令集：$instructionSet")
    }

    private fun assembleAArch64(base: Long, source: String): ByteArray? {
        val items = parse(source)
        var address = base
        val labels = HashMap<String, Long>()
        var instructions = 0
        for (item in items) {
            for (label in item.labels) {
                require(labels.put(label, address) == null) { "汇编第 ${item.line} 行：重复标签 $label" }
            }
            if (item.mnemonic != null) {
                address += 4
                instructions++
                require(instructions <= MAX_INSTRUCTIONS) { "汇编指令太多" }
            }
        }
        val size = address - base
        require(size in 0L..MAX_SOURCE.toLong()) { "汇编结果过大" }
        val table = Labels(labels, labels.keys.mapTo(HashSet<String>()) { it.lowercase() })
        val output = ByteBuffer.allocate(size.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        address = base
        for (item in items) {
            val mnemonic = item.mnemonic ?: continue
            val word = if (mnemonic == ".inst") item.inst!!.toInt()
            else encode(mnemonic, item.operands, address, table, item.line) ?: return null
            output.putInt(word)
            address += 4
        }
        return output.array()
    }

    private fun parse(source: String): List<Item> {
        require(source.length <= MAX_SOURCE) { "汇编源码超过 $MAX_SOURCE 字符" }
        val items = ArrayList<Item>()
        var instructions = 0
        source.lineSequence().forEachIndexed { index, raw ->
            val code = raw.substringBefore("//")
            // The clang integrated assembler treats ';' as a statement separator
            // for these targets; the mini assembler mirrors that. Strings and block
            // comments are rejected by ToolkitAssemblySource, so splitting here is
            // unambiguous.
            for (statement in code.split(';')) {
                val line = statement.trim()
                if (line.isEmpty()) continue
                val labels = ArrayList<String>()
                var rest = line
                while (true) {
                    val match = LABEL_PREFIX.matchEntire(rest) ?: break
                    labels.add(match.groupValues[1])
                    rest = match.groupValues[2].trim()
                    if (rest.isEmpty()) break
                }
                if (rest.isEmpty()) {
                    items.add(Item(labels, null, emptyList(), null, index + 1))
                    continue
                }
                if (rest.startsWith(".")) {
                    val parts = rest.split(Regex("\\s+"), limit = 2)
                    require(parts[0].equals(".inst", true)) { "汇编第 ${index + 1} 行：仅接受指令、标签与 .inst" }
                    val value = parseNumber(parts.getOrNull(1)?.trim().orEmpty())
                    require(value in 0L..0xffffffffL) { "汇编第 ${index + 1} 行：.inst 超出 32 位" }
                    items.add(Item(labels, ".inst", emptyList(), value, index + 1))
                } else {
                    val parts = rest.split(Regex("\\s+"), limit = 2)
                    val mnemonic = parts[0].lowercase()
                    val operands = if (parts.size > 1) splitOperands(parts[1]) else emptyList()
                    items.add(Item(labels, mnemonic, operands, null, index + 1))
                }
                instructions++
                require(instructions <= MAX_INSTRUCTIONS) { "汇编指令太多" }
            }
        }
        return items
    }

    private fun encode(mnemonic: String, operands: List<String>, address: Long, labels: Labels, line: Int): Int? {
        fun fail(message: String): Nothing = throw IllegalArgumentException("汇编第 $line 行：$message")
        fun regAt(index: Int, allowSp: Boolean = false, allowZero: Boolean = true): Reg? {
            val token = operands.getOrNull(index) ?: return null
            return parseReg(token, allowSp, allowZero)
        }
        fun target(token: String): Long = parseTarget(token, labels) ?: fail("未知标签或地址：$token")
        fun branch(delta: Long, bits: Int, base: Long): Int {
            if (delta % 4 != 0L) fail("分支目标必须 4 字节对齐")
            val imm = delta shr 2
            val min = -(1L shl (bits - 1))
            val max = (1L shl (bits - 1)) - 1
            if (imm < min || imm > max) fail("分支范围超限")
            return (base or (imm and ((1L shl bits) - 1))).toInt()
        }

        return when (mnemonic) {
            "nop" -> {
                if (operands.isNotEmpty()) fail("nop 不接受操作数")
                0xd503201fL.toInt()
            }
            "ret" -> {
                if (operands.size > 1) fail("ret 最多接受一个寄存器")
                if (operands.isEmpty()) 0xd65f03c0L.toInt()
                else {
                    val rn = regAt(0, allowZero = false) ?: fail("ret 需要 x0..x30")
                    if (!rn.is64) fail("ret 需要 64 位寄存器")
                    (0xd65f0000L or (rn.n.toLong() shl 5)).toInt()
                }
            }
            "br", "blr" -> {
                if (operands.size != 1) fail("$mnemonic 需要 x0..x30")
                val rn = regAt(0, allowZero = false) ?: fail("$mnemonic 需要 x0..x30")
                if (!rn.is64) fail("$mnemonic 需要 64 位寄存器")
                val base = if (mnemonic == "br") 0xd61f0000L else 0xd63f0000L
                (base or (rn.n.toLong() shl 5)).toInt()
            }
            "b", "bl" -> {
                if (operands.size != 1) fail("$mnemonic 需要一个目标")
                val delta = target(operands[0]) - address
                branch(delta, 26, if (mnemonic == "bl") 0x94000000L else 0x14000000L)
            }
            "b.eq", "b.ne", "b.cs", "b.hs", "b.cc", "b.lo", "b.mi", "b.pl", "b.vs", "b.vc",
            "b.hi", "b.ls", "b.ge", "b.lt", "b.gt", "b.le", "b.al", "b.nv" -> {
                if (operands.size != 1) fail("$mnemonic 需要一个目标")
                val cond = condition(mnemonic.removePrefix("b.")) ?: fail("未知条件码")
                val delta = target(operands[0]) - address
                if (delta % 4 != 0L) fail("分支目标必须 4 字节对齐")
                val imm = delta shr 2
                if (imm !in -262144L..262143L) fail("分支范围超限")
                (0x54000000L or ((imm and 0x7ffffL) shl 5) or cond.toLong()).toInt()
            }
            "cbz", "cbnz" -> {
                if (operands.size != 2) fail("$mnemonic 需要 Rt, target")
                val rt = regAt(0) ?: fail("$mnemonic 需要 w/x 寄存器")
                if (rt.sp || rt.zero) fail("$mnemonic 需要普通 w/x 寄存器")
                val delta = target(operands[1]) - address
                if (delta % 4 != 0L) fail("分支目标必须 4 字节对齐")
                val imm = delta shr 2
                if (imm !in -262144L..262143L) fail("分支范围超限")
                val base = if (rt.is64) 0xb4000000L else 0x34000000L
                val op = if (mnemonic == "cbnz") 1L shl 24 else 0L
                (base or op or ((imm and 0x7ffffL) shl 5) or rt.n.toLong()).toInt()
            }
            "tbz", "tbnz" -> {
                if (operands.size != 3) fail("$mnemonic 需要 Rt, #bit, target")
                val rt = regAt(0) ?: fail("$mnemonic 需要 w/x 寄存器")
                if (rt.sp || rt.zero) fail("$mnemonic 需要普通 w/x 寄存器")
                val bit = immediate(operands[1]) ?: fail("$mnemonic 需要位号")
                if (bit !in 0L..(if (rt.is64) 63L else 31L)) fail("位号超出寄存器宽度")
                val delta = target(operands[2]) - address
                if (delta % 4 != 0L) fail("分支目标必须 4 字节对齐")
                val imm = delta shr 2
                if (imm !in -8192L..8191L) fail("分支范围超限")
                val base = 0x36000000L or (if (mnemonic == "tbnz") 1L shl 24 else 0L)
                (base or ((bit / 32) shl 31) or ((bit % 32) shl 19) or ((imm and 0x3fffL) shl 5) or rt.n.toLong()).toInt()
            }
            "adr", "adrp" -> {
                if (operands.size != 2) fail("$mnemonic 需要 Xd, target")
                val rd = regAt(0) ?: fail("$mnemonic 需要 64 位寄存器")
                if (!rd.is64 || rd.sp) fail("$mnemonic 需要 x0..x30/xzr")
                val targetAddress = target(operands[1])
                if (mnemonic == "adr") {
                    val delta = targetAddress - address
                    if (delta !in -1048576L..1048575L) fail("ADR 范围超限")
                    (0x10000000L or ((delta and 3L) shl 29) or (((delta shr 2) and 0x7ffffL) shl 5) or rd.n.toLong()).toInt()
                } else {
                    val pageTarget = targetAddress and -4096L
                    val pageAddress = address and -4096L
                    val delta = pageTarget - pageAddress
                    if (delta % 4096L != 0L) fail("ADRP 目标必须页对齐")
                    val pages = delta shr 12
                    if (pages !in -1048576L..1048575L) fail("ADRP 范围超限")
                    (0x90000000L or ((pages and 3L) shl 29) or (((pages shr 2) and 0x7ffffL) shl 5) or rd.n.toLong()).toInt()
                }
            }
            "movz", "movn", "movk" -> {
                if (operands.size !in 2..3) fail("$mnemonic 需要 Xd, #imm[, lsl #shift]")
                val rd = regAt(0) ?: fail("$mnemonic 需要 w/x 寄存器")
                if (rd.sp) fail("$mnemonic 目标不能是 SP")
                val value = immediate(operands[1]) ?: fail("$mnemonic 需要 16 位立即数")
                if (value !in 0L..0xffffL) fail("$mnemonic 立即数超出 16 位")
                val shift = if (operands.size == 3) {
                    val match = SHIFT.matchEntire(operands[2].trim().lowercase()) ?: fail("$mnemonic 仅支持 lsl #0/16/32/48")
                    match.groupValues[1].toIntOrNull() ?: fail("$mnemonic 移位无效")
                } else 0
                val maxShift = if (rd.is64) 48 else 16
                if (shift !in 0..maxShift || shift % 16 != 0) fail("$mnemonic 移位无效")
                val base = when (mnemonic) {
                    "movz" -> 0x52800000L
                    "movn" -> 0x12800000L
                    else -> 0x72800000L
                }
                val sf = if (rd.is64) 1L shl 31 else 0L
                (base or sf or ((shift / 16).toLong() shl 21) or (value shl 5) or rd.n.toLong()).toInt()
            }
            "mov" -> {
                if (operands.size != 2) fail("mov 需要两个操作数")
                val rd = regAt(0, allowSp = true) ?: fail("mov 目标无效")
                val sourceText = operands[1].trim()
                val immediateValue = immediate(sourceText)
                if (immediateValue != null) {
                    if (rd.sp) fail("mov 不能把立即数写入 SP")
                    moveImmediate(rd, immediateValue) ?: return null
                } else {
                    val rs = parseReg(sourceText, allowSp = true) ?: fail("mov 源操作数无效")
                    if (rd.is64 != rs.is64) fail("mov 两侧寄存器宽度必须一致")
                    if (rd.sp || rs.sp) {
                        // The SP spelling is ADD #0; XZR cannot be represented by that alias.
                        if ((rd.sp && rs.zero) || (rs.sp && rd.zero)) return null
                        val base = if (rd.is64) 0x91000000L else 0x11000000L
                        (base or (rs.n.toLong() shl 5) or rd.n.toLong()).toInt()
                    } else {
                        val base = if (rd.is64) 0xaa0003e0L else 0x2a0003e0L
                        (base or (rs.n.toLong() shl 16) or rd.n.toLong()).toInt()
                    }
                }
            }
            "add", "adds", "sub", "subs" -> {
                if (operands.size !in 3..4) fail("$mnemonic 需要 Xd, Xn, operand")
                val flags = mnemonic.endsWith("s")
                val subtract = mnemonic.startsWith("sub")
                val rd = regAt(0, allowSp = !flags) ?: fail("$mnemonic 目标无效")
                val rn = regAt(1, allowSp = true) ?: fail("$mnemonic 第一源操作数无效")
                if (rd.is64 != rn.is64) fail("$mnemonic 寄存器宽度必须一致")
                if (flags && rd.sp) fail("$mnemonic 目标不能是 SP")
                val third = operands[2].trim()
                val immediateValue = immediate(third)
                if (immediateValue != null) {
                    // In ADD/SUB immediate, Rn=31 means SP; in flag-setting forms Rd=31 means XZR.
                    if (rn.zero) return null
                    if (!flags && rd.zero) return null
                    if (immediateValue !in 0L..4095L) return null
                    val shifted = operands.getOrNull(3)?.let { shift12(it) } ?: false
                    if (operands.size == 4 && !shifted) return null
                    val base = when {
                        subtract && flags -> 0x71000000L
                        subtract -> 0x51000000L
                        flags -> 0x31000000L
                        else -> 0x11000000L
                    }
                    val sf = if (rn.is64) 1L shl 31 else 0L
                    val sh = if (shifted) 1L shl 22 else 0L
                    (base or sf or sh or (immediateValue shl 10) or (rn.n.toLong() shl 5) or rd.n.toLong()).toInt()
                } else {
                    if (operands.size == 4 && shift12(operands[3]) == false) return null
                    val rm = regAt(2) ?: fail("$mnemonic 第三操作数无效")
                    if (rm.is64 != rd.is64 || rm.sp) return null
                    if (rd.sp || rn.sp) return null
                    val shift = parseShift(operands.getOrNull(3), rn.is64) ?: return null
                    val base = when {
                        subtract && flags -> 0x6b000000L
                        subtract -> 0x4b000000L
                        flags -> 0x2b000000L
                        else -> 0x0b000000L
                    }
                    val sf = if (rn.is64) 1L shl 31 else 0L
                    (base or sf or (shift.type.toLong() shl 22) or (shift.amount.toLong() shl 10) or
                        (rm.n.toLong() shl 16) or (rn.n.toLong() shl 5) or rd.n.toLong()).toInt()
                }
            }
            "and", "orr", "eor", "ands" -> {
                if (operands.size !in 3..4) fail("$mnemonic 需要 Xd, Xn, Xm")
                if (immediate(operands[2]) != null) return null
                val rd = regAt(0, allowSp = false) ?: fail("$mnemonic 目标无效")
                val rn = regAt(1, allowSp = false) ?: fail("$mnemonic 第一源操作数无效")
                val rm = regAt(2, allowSp = false) ?: fail("$mnemonic 第二源操作数无效")
                if (rd.is64 != rn.is64 || rd.is64 != rm.is64) fail("$mnemonic 寄存器宽度必须一致")
                val shift = parseShift(operands.getOrNull(3), rn.is64) ?: return null
                val base = when (mnemonic) {
                    "and" -> 0x0a000000L
                    "orr" -> 0x2a000000L
                    "eor" -> 0x4a000000L
                    else -> 0x6a000000L
                }
                val sf = if (rn.is64) 1L shl 31 else 0L
                (base or sf or (shift.type.toLong() shl 22) or (shift.amount.toLong() shl 10) or
                    (rm.n.toLong() shl 16) or (rn.n.toLong() shl 5) or rd.n.toLong()).toInt()
            }
            "cmp", "tst" -> {
                if (operands.size != 2) fail("$mnemonic 需要两个操作数")
                val rn = regAt(0) ?: fail("$mnemonic 第一操作数无效")
                if (rn.sp) fail("$mnemonic 第一操作数不能是 SP")
                val second = operands[1].trim()
                if (mnemonic == "tst") {
                    if (immediate(second) != null) return null
                    val rm = regAt(1) ?: fail("tst 第二操作数无效")
                    if (rm.is64 != rn.is64 || rm.sp) fail("tst 寄存器宽度必须一致")
                    val base = 0x6a000000L
                    val sf = if (rn.is64) 1L shl 31 else 0L
                    (base or sf or (rm.n.toLong() shl 16) or (rn.n.toLong() shl 5) or 31L).toInt()
                } else {
                    val immediateValue = immediate(second)
                    if (immediateValue != null) {
                        // SUBS immediate encodes Rn=31 as SP, not XZR.
                        if (rn.zero) return null
                        if (immediateValue !in 0L..4095L) return null
                        val base = 0x71000000L
                        val sf = if (rn.is64) 1L shl 31 else 0L
                        (base or sf or (immediateValue shl 10) or (rn.n.toLong() shl 5) or 31L).toInt()
                    } else {
                        val rm = regAt(1) ?: fail("cmp 第二操作数无效")
                        if (rm.is64 != rn.is64 || rm.sp) fail("cmp 寄存器宽度必须一致")
                        val base = 0x6b000000L
                        val sf = if (rn.is64) 1L shl 31 else 0L
                        (base or sf or (rm.n.toLong() shl 16) or (rn.n.toLong() shl 5) or 31L).toInt()
                    }
                }
            }
            "mul" -> {
                if (operands.size != 3) fail("mul 需要三个操作数")
                val rd = regAt(0) ?: fail("mul 目标无效")
                val rn = regAt(1) ?: fail("mul 第一源操作数无效")
                val rm = regAt(2) ?: fail("mul 第二源操作数无效")
                if (rd.is64 != rn.is64 || rd.is64 != rm.is64) fail("mul 寄存器宽度必须一致")
                if (rd.sp || rn.sp || rm.sp) fail("mul 不接受 SP")
                val base = if (rd.is64) 0x9b007c00L else 0x1b007c00L
                (base or (rm.n.toLong() shl 16) or (rn.n.toLong() shl 5) or rd.n.toLong()).toInt()
            }
            "svc", "brk", "udf" -> {
                if (operands.size != 1) fail("$mnemonic 需要一个立即数")
                val value = immediate(operands[0]) ?: fail("$mnemonic 需要立即数")
                if (value !in 0L..0xffffL) fail("$mnemonic 立即数超出 16 位")
                if (mnemonic == "udf") value.toInt()
                else {
                    val base = if (mnemonic == "svc") 0xd4000001L else 0xd4200000L
                    (base or (value shl 5)).toInt()
                }
            }
            "ldr", "str", "ldrb", "strb", "ldrh", "strh", "ldrsw" -> {
                // Post-index and literal forms are valid AArch64; leave them to clang.
                if (operands.size != 2) return null
                val rt = regAt(0) ?: fail("$mnemonic 目标寄存器无效")
                val mem = parseMem(operands[1]) ?: return null
                if (mem.preIndex) return null
                val rn = parseReg(mem.base, allowSp = true) ?: fail("$mnemonic 基址寄存器无效")
                if (!rn.is64 || rn.zero) fail("$mnemonic 基址必须是 64 位寄存器或 SP")
                val offset = if (mem.offset == null) 0L else (immediate(mem.offset) ?: return null)
                if (offset < 0) return null
                val (base, scale) = when (mnemonic) {
                    "ldr" -> if (rt.is64) (0xf9400000L to 8L) else (0xb9400000L to 4L)
                    "str" -> if (rt.is64) (0xf9000000L to 8L) else (0xb9000000L to 4L)
                    "ldrb" -> 0x39400000L to 1L
                    "strb" -> 0x39000000L to 1L
                    "ldrh" -> 0x79400000L to 2L
                    "strh" -> 0x79000000L to 2L
                    else -> 0xb9800000L to 4L
                }
                if (mnemonic == "ldrsw") {
                    // LDRSW sign-extends into an X register; Wt is not encodable.
                    if (!rt.is64) return null
                } else if (rt.is64) {
                    // LDRB/STRB/LDRH/STRH use W registers; X registers are not encodable.
                    if (mnemonic in listOf("ldrb", "strb", "ldrh", "strh")) return null
                }
                if (offset % scale != 0L) return null
                val imm = offset / scale
                if (imm !in 0L..4095L) return null
                (base or (imm shl 10) or (rn.n.toLong() shl 5) or rt.n.toLong()).toInt()
            }
            "ldp", "stp" -> {
                // Post-index ([sp], #16) has four operands; let clang handle it rather than
                // mis-encode it as a zero-offset pair.
                if (operands.size != 3) return null
                val rt = regAt(0) ?: fail("$mnemonic 第一寄存器无效")
                val rt2 = regAt(1) ?: fail("$mnemonic 第二寄存器无效")
                if (rt.is64 != rt2.is64 || rt.sp || rt2.sp) fail("$mnemonic 寄存器无效")
                val mem = parseMem(operands[2]) ?: return null
                val rn = parseReg(mem.base, allowSp = true) ?: fail("$mnemonic 基址寄存器无效")
                if (!rn.is64 || rn.zero) fail("$mnemonic 基址必须是 64 位寄存器或 SP")
                val offset = if (mem.offset == null) 0L else (immediate(mem.offset) ?: return null)
                val scale = if (rt.is64) 8L else 4L
                if (offset % scale != 0L) return null
                val imm = offset / scale
                if (imm !in -64L..63L) return null
                val base = when {
                    mnemonic == "stp" && rt.is64 && mem.preIndex -> 0xa9800000L
                    mnemonic == "stp" && rt.is64 -> 0xa9000000L
                    mnemonic == "stp" && mem.preIndex -> 0x29800000L
                    mnemonic == "stp" -> 0x29000000L
                    rt.is64 && mem.preIndex -> 0xa9c00000L
                    rt.is64 -> 0xa9400000L
                    mem.preIndex -> 0x29c00000L
                    else -> 0x29400000L
                }
                (base or ((imm and 0x7fL) shl 15) or (rt2.n.toLong() shl 10) or (rn.n.toLong() shl 5) or rt.n.toLong()).toInt()
            }
            else -> null
        }
    }

    private fun moveImmediate(reg: Reg, value: Long): Int? {
        if (reg.sp) return null
        // The external assembler rejects a 32-bit immediate that does not fit the
        // register. Masking it here would silently encode a different value.
        if (!reg.is64 && value !in -0x80000000L..0xffffffffL) return null
        val mask = if (reg.is64) -1L else 0xffffffffL
        val v = value and mask
        val chunk = singleChunk(v, reg.is64)
        if (chunk != null) {
            val base = if (reg.is64) 0xd2800000L else 0x52800000L
            return (base or ((chunk.second / 16).toLong() shl 21) or (chunk.first.toLong() shl 5) or reg.n.toLong()).toInt()
        }
        val inverse = v.inv() and mask
        val inverseChunk = singleChunk(inverse, reg.is64)
        if (inverseChunk != null) {
            val base = if (reg.is64) 0x92800000L else 0x12800000L
            return (base or ((inverseChunk.second / 16).toLong() shl 21) or (inverseChunk.first.toLong() shl 5) or reg.n.toLong()).toInt()
        }
        return null
    }

    /** Returns (imm16, bit offset) when all other 16-bit lanes are zero. */
    private fun singleChunk(value: Long, is64: Boolean): Pair<Int, Int>? {
        val chunks = if (is64) 4 else 2
        for (index in 0 until chunks) {
            val shift = index * 16
            val part = (value ushr shift) and 0xffffL
            if (part == 0L) continue
            val lower = if (shift == 0) 0L else (1L shl shift) - 1L
            val upper = if (shift + 16 >= 64) 0L else -1L shl (shift + 16)
            if ((value and (lower or upper)) != 0L) return null
            return part.toInt() to shift
        }
        return 0 to 0
    }

    private fun parseReg(token: String, allowSp: Boolean = false, allowZero: Boolean = true): Reg? {
        val value = token.trim().lowercase()
        when (value) {
            "sp" -> return if (allowSp) Reg(31, true, sp = true) else null
            "wsp" -> return if (allowSp) Reg(31, false, sp = true) else null
            "xzr" -> return if (allowZero) Reg(31, true, zero = true) else null
            "wzr" -> return if (allowZero) Reg(31, false, zero = true) else null
        }
        val match = REGISTER.matchEntire(value) ?: return null
        val number = match.groupValues[2].toIntOrNull() ?: return null
        if (number > 30) return null
        return Reg(number, match.groupValues[1] == "x")
    }

    private fun parseTarget(token: String, labels: Labels): Long? {
        val value = token.trim()
        labels.exact[value]?.let { return it }
        val body = if (value.startsWith("#")) value.substring(1) else value
        val numeric = try { parseNumber(body) } catch (_: Exception) { null }
        if (numeric != null) return numeric
        // A case-only miss is ambiguous for a case-insensitive helper but not for
        // the assembler. Refuse to guess; tryAssemble turns this into null so the
        // case-sensitive external backend decides.
        if (labels.folded.contains(value.lowercase())) throw LabelCaseMismatch()
        return null
    }

    private fun immediate(token: String): Long? {
        val value = token.trim()
        if (value.isEmpty()) return null
        val body = if (value.startsWith("#")) value.substring(1) else value
        if (body.isEmpty() || body.any { it.isWhitespace() }) return null
        return try { parseNumber(body) } catch (_: Exception) { null }
    }

    private fun parseNumber(value: String): Long {
        require(value.isNotEmpty()) { "数字为空" }
        val negative = value.startsWith("-")
        val body = if (negative) value.substring(1) else value
        require(body.isNotEmpty()) { "数字无效" }
        val number = if (body.startsWith("0x", true)) BigInteger(body.substring(2), 16) else BigInteger(body, 10)
        require(number.bitLength() <= 63) { "数字超出范围" }
        return if (negative) number.negate().toLong() else number.toLong()
    }

    private fun parseShift(token: String?, is64: Boolean): Shift? {
        if (token == null) return Shift(0, 0)
        val match = SHIFT.matchEntire(token.trim().lowercase()) ?: return null
        val amount = match.groupValues[1].toIntOrNull() ?: return null
        val max = if (is64) 63 else 31
        if (amount !in 0..max) return null
        return Shift(0, amount)
    }

    /** true means LSL #12; false means no shift; null means an unsupported shift form. */
    private fun shift12(token: String): Boolean? {
        val value = token.trim().lowercase()
        if (value == "lsl #12" || value == "lsl 12") return true
        return null
    }

    private fun parseMem(token: String): Mem? {
        val value = token.trim()
        if (value.isEmpty() || value[0] != '[') return null
        val preIndex = value.endsWith("]!")
        val close = if (preIndex) value.length - 2 else value.length - 1
        if (close < 1 || value[close] != ']') return null
        val inside = value.substring(1, close)
        val parts = splitOperands(inside)
        if (parts.isEmpty() || parts.size > 2) return null
        return Mem(parts[0].trim(), parts.getOrNull(1)?.trim(), preIndex)
    }

    private fun splitOperands(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val result = ArrayList<String>()
        val current = StringBuilder()
        var depth = 0
        for (character in text) {
            when (character) {
                '[', '(' -> {
                    depth++
                    current.append(character)
                }
                ']', ')' -> {
                    depth--
                    if (depth < 0) throw IllegalArgumentException("汇编操作数括号不匹配")
                    current.append(character)
                }
                ',' -> if (depth == 0) {
                    result.add(current.toString().trim())
                    current.setLength(0)
                } else current.append(character)
                else -> current.append(character)
            }
        }
        if (depth != 0) throw IllegalArgumentException("汇编操作数括号不匹配")
        result.add(current.toString().trim())
        return result
    }

    private fun condition(name: String): Int? = when (name) {
        "eq" -> 0
        "ne" -> 1
        "cs", "hs" -> 2
        "cc", "lo" -> 3
        "mi" -> 4
        "pl" -> 5
        "vs" -> 6
        "vc" -> 7
        "hi" -> 8
        "ls" -> 9
        "ge" -> 10
        "lt" -> 11
        "gt" -> 12
        "le" -> 13
        "al" -> 14
        "nv" -> 15
        else -> null
    }
}
