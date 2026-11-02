package com.jdkshen.aijspro.mcp

import com.google.gson.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.math.BigInteger
import java.util.ArrayDeque

/** Bounded ELF reader. Instruction analysis deliberately reports undecoded words. */
internal class ToolkitElf(val bytes: ByteArray) {
    data class Segment(val type: Long, val flags: Long, val offset: Long, val address: Long, val size: Long, val memorySize: Long, val alignment: Long)
    data class Section(val name: String, val type: Long, val flags: Long, val address: Long, val offset: Long, val size: Long, val link: Int, val entrySize: Long)
    data class Symbol(val name: String, val address: Long, val size: Long, val type: Int, val binding: Int, val section: Int)
    data class Instruction(val address: Long, val offset: Int, val hex: String, val mnemonic: String, val operands: String,
        val target: Long? = null, val kind: String? = null, val conditional: Boolean = false, val terminal: Boolean = false, val decoded: Boolean = true, val size: Int = 4)
    val wide: Boolean
    val little: Boolean
    val machine: Int
    val entryAddress: Long
    val segments = ArrayList<Segment>()
    val sections = ArrayList<Section>()
    val symbols = ArrayList<Symbol>()
    private val symbolTables = HashMap<Int, List<Symbol>>()
    private val buffer: ByteBuffer
    init {
        require(bytes.size in 52..ToolkitIo.MAX_ENTRY && bytes[0] == 0x7f.toByte() && String(bytes, 1, 3, Charsets.US_ASCII) == "ELF") { "不是受支持的 ELF 文件" }
        wide = bytes[4] == 2.toByte(); require(wide || bytes[4] == 1.toByte()) { "ELF 位数无效" }
        little = bytes[5] == 1.toByte(); require(little || bytes[5] == 2.toByte()) { "ELF 字节序无效" }
        buffer = ByteBuffer.wrap(bytes).order(if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        machine = u16(18); entryAddress = word(24)
        val phoff = word(if (wide) 32 else 28); val shoff = word(if (wide) 40 else 32)
        val phsize = u16(if (wide) 54 else 42); val phcount = u16(if (wide) 56 else 44)
        val shsize = u16(if (wide) 58 else 46); val shcount = u16(if (wide) 60 else 48); val namesIndex = u16(if (wide) 62 else 50)
        require(phcount <= 4096 && shcount <= 8192) { "ELF 表项过多" }
        if (phcount > 0) { require(phsize >= if (wide) 56 else 32); range(phoff, phsize.toLong() * phcount) }
        repeat(phcount) { index ->
            val p = (phoff + index * phsize).toInt()
            val segment = if (wide) Segment(u32(p), u32(p + 4), word(p + 8), word(p + 16), word(p + 32), word(p + 40), word(p + 48))
            else Segment(u32(p), u32(p + 24), word(p + 4), word(p + 8), word(p + 16), word(p + 20), word(p + 28))
            range(segment.offset, segment.size); require(segment.memorySize >= segment.size) { "ELF 内存范围无效" }; segments.add(segment)
        }
        if (shcount > 0) { require(shsize >= if (wide) 64 else 40); range(shoff, shsize.toLong() * shcount) }
        val raw = (0 until shcount).map { (shoff + it * shsize).toInt() }
        val names = raw.getOrNull(namesIndex)?.let { p ->
            val offset = word(p + if (wide) 24 else 16); val size = word(p + if (wide) 32 else 20)
            range(offset, size); offset to size
        }
        raw.forEach { p ->
            val offset = word(p + if (wide) 24 else 16); val size = word(p + if (wide) 32 else 20); val type = u32(p + 4)
            if (type != 8L) range(offset, size)
            sections.add(Section(if (names == null) "" else string(names.first + u32(p), names.first + names.second), type,
                word(p + 8), word(p + if (wide) 16 else 12), offset, size, u32(p + if (wide) 40 else 24).toInt(), word(p + if (wide) 56 else 36)))
        }
        sections.forEachIndexed { sectionIndex, section ->
            if (section.type != 2L && section.type != 11L) return@forEachIndexed
            val strings = sections.getOrNull(section.link) ?: return@forEachIndexed
            val stride = if (section.entrySize == 0L) if (wide) 24L else 16L else section.entrySize
            require(stride >= (if (wide) 24L else 16L) && section.size / stride <= 500000) { "ELF 符号表无效或过大" }
            val table = ArrayList<Symbol>((section.size / stride).toInt())
            repeat((section.size / stride).toInt()) { index ->
                val p = (section.offset + index * stride).toInt(); val info = bytes[p + if (wide) 4 else 12].toInt() and 255
                val symbol = Symbol(string(strings.offset + u32(p), strings.offset + strings.size), word(p + if (wide) 8 else 4),
                    word(p + if (wide) 16 else 8), info and 15, info ushr 4, u16(p + if (wide) 6 else 14))
                symbols.add(symbol); table.add(symbol)
            }
            symbolTables[sectionIndex] = table
        }
    }
    private fun range(offset: Long, size: Long) { require(offset >= 0 && size >= 0 && offset <= bytes.size && size <= bytes.size - offset) { "ELF 数据范围越界" } }
    private fun u16(p: Int): Int { range(p.toLong(), 2); return buffer.getShort(p).toInt() and 65535 }
    private fun u32(p: Int): Long { range(p.toLong(), 4); return buffer.getInt(p).toLong() and 0xffffffffL }
    private fun word(p: Int): Long {
        if (!wide) return u32(p)
        range(p.toLong(), 8); return buffer.getLong(p).also { require(it >= 0) { "暂不接受高位为 1 的 64 位 ELF 数值" } }
    }
    private fun string(offset: Long, end: Long): String {
        if (offset < 0 || offset >= end || end > bytes.size) return ""
        var p = offset.toInt(); val last = minOf(end, offset + 4096).toInt()
        while (p < last && bytes[p] != 0.toByte()) p++
        return String(bytes, offset.toInt(), p - offset.toInt(), Charsets.UTF_8)
    }
    fun fileOffset(address: Long): Long? = segments.firstOrNull { it.type == 1L && address >= it.address && address - it.address < it.size }
        ?.let { it.offset + address - it.address }
    fun virtual(offset: Long): Long? = segments.firstOrNull { it.type == 1L && offset >= it.offset && offset - it.offset < it.size }
        ?.let { it.address + offset - it.offset }
    fun address(args: JsonObject): Long {
        val value = number(args.tString("address"))
        return when (args.tString("addressKind")) {
            "virtual" -> value
            "file_offset" -> virtual(value) ?: error("文件偏移不属于已加载区段")
            else -> error("addressKind 必须为 virtual 或 file_offset")
        }
    }
    fun map(args: JsonObject): JsonObject {
        val kind = args.tString("addressKind"); val raw = number(args.tString("address"))
        require(kind in listOf("virtual", "file_offset"))
        val v = if (kind == "virtual") raw else virtual(raw)
        val segment = v?.let { value -> segments.firstOrNull { it.type == 1L && value >= it.address && value - it.address < it.memorySize } }
        val offset = v?.let { fileOffset(it) }
        val symbol = v?.let { value -> symbols.filter { it.section != 0 && value >= it.address && it.size > 0 && value - it.address < it.size }.minByOrNull { it.size } }
        return ToolkitIo.json("found" to (segment != null), "mapping" to ToolkitIo.json("virtualAddress" to v?.let(::hex), "fileOffset" to offset,
            "isLoaded" to (segment != null), "hasFileBytes" to (offset != null), "executable" to (segment != null && segment.flags and 1 != 0L), "segment" to segment, "symbol" to symbol))
    }
    fun needed(): List<String> {
        val dynamic = sections.firstOrNull { it.type == 6L }
        val segment = segments.firstOrNull { it.type == 2L }
        val start = dynamic?.offset ?: segment?.offset ?: return emptyList()
        val size = dynamic?.size ?: segment!!.size; val stride = if (wide) 16 else 8
        require(size / stride <= 100000)
        var strings: Long? = dynamic?.let { sections.getOrNull(it.link)?.offset }; val indices = ArrayList<Long>()
        for (index in 0 until (size / stride).toInt()) {
            val p = (start + index * stride).toInt(); val tag = word(p); val value = word(p + stride / 2)
            if (tag == 0L) break
            if (tag == 1L) indices.add(value)
            if (tag == 5L && strings == null) strings = fileOffset(value)
        }; return strings?.let { pool -> indices.map { string(pool + it, bytes.size.toLong()) } } ?: emptyList()
    }
    fun inspect(): JsonObject = ToolkitIo.json("format" to "ELF", "bits" to if (wide) 64 else 32,
        "endianness" to if (little) "little" else "big", "machine" to machine, "architecture" to when(machine) {183 -> "aarch64";40 -> "arm";62 -> "x86_64";3 -> "x86";else -> "machine-$machine"},
        "entryAddress" to hex(entryAddress), "neededLibraries" to needed(), "sectionCount" to sections.size,
        "segmentCount" to segments.size, "symbolCount" to symbols.size, "importCount" to symbols.count { it.section == 0 && it.name.isNotEmpty() },
        "disassembler" to if(ToolkitNative.available) "capstone-5.0.6" else "bounded-aarch64-fallback", "instructionCoverage" to if(ToolkitNative.available) "ARM64、ARM/Thumb、x86、x86_64；未识别字节明确标出" else "原生库不可用，仅AArch64常见指令回退",
        "demangleAvailable" to ToolkitNative.demangleAvailable)
    fun items(kind: String, prefix: String): List<JsonObject> = when(kind) {
        "neededLibraries" -> needed().filter { it.startsWith(prefix) }.map { ToolkitIo.json("name" to it) }
        "sections" -> sections.filter { it.name.startsWith(prefix) }.map { ToolkitIo.json("name" to it.name, "type" to it.type, "address" to hex(it.address), "fileOffset" to it.offset, "size" to it.size, "flags" to it.flags) }
        "segments" -> segments.map { ToolkitIo.json("type" to it.type, "address" to hex(it.address), "fileOffset" to it.offset, "fileSize" to it.size, "memorySize" to it.memorySize, "flags" to it.flags, "alignment" to it.alignment) }
        "symbols", "imports" -> symbols.filter { it.name.startsWith(prefix) && it.name.isNotEmpty() && (kind != "imports" || it.section == 0) }.map { ToolkitIo.json("name" to it.name, "address" to hex(it.address), "size" to it.size, "type" to it.type, "binding" to it.binding, "sectionIndex" to it.section) }
        "entries" -> if (entryAddress == 0L) emptyList() else listOf(ToolkitIo.json("address" to hex(entryAddress)))
        "initArray", "finiArray" -> sections.filter { it.type == if (kind == "initArray") 14L else 15L }.flatMap { section ->
            val stride = if (wide) 8 else 4; require(section.size / stride <= 100000)
            (0 until (section.size / stride).toInt()).map { index -> ToolkitIo.json("slotAddress" to hex(section.address + index * stride), "value" to hex(word((section.offset + index * stride).toInt())), "relocationUnresolved" to true) }
        }
        "relocations" -> sections.filter { it.type == 4L || it.type == 9L }.flatMap { section ->
            val stride = if (section.entrySize > 0) section.entrySize else if (wide) if (section.type == 4L) 24L else 16L else if (section.type == 4L) 12L else 8L
            require(stride >= (if (wide) 16 else 8) && section.size / stride <= 300000)
            (0 until (section.size / stride).toInt()).map { index ->
                val p = (section.offset + index * stride).toInt(); val info = word(p + if (wide) 8 else 4)
                val symbolIndex = if (wide) info ushr 32 else info ushr 8
                val symbol = relocationSymbol(section.link, symbolIndex)
                ToolkitIo.json("section" to section.name, "address" to hex(word(p)), "symbolIndex" to symbolIndex,
                    "name" to symbol?.name, "symbolAddress" to symbol?.address?.let(::hex), "symbolDefined" to symbol?.let { it.section != 0 },
                    "type" to if (wide) info and 0xffffffffL else info and 255,
                    "addend" to if (section.type == 4L) (if (wide) buffer.getLong(p + 16) else buffer.getInt(p + 8).toLong()) else null)
            }
        }.filter { prefix.isEmpty() || it.get("name")?.takeUnless { value -> value.isJsonNull }?.asString?.startsWith(prefix) == true }
        else -> throw IllegalArgumentException("不支持的 ELF 列表：$kind")
    }
    fun strings(max: Int): List<JsonObject> {
        val result = ArrayList<JsonObject>(); var begin = 0
        for (p in bytes.indices) {
            val n = bytes[p].toInt() and 255
            if (n in 32..126 || n >= 128) continue
            if (p - begin >= 4) result.add(ToolkitIo.json("fileOffset" to begin, "value" to String(bytes, begin, minOf(p - begin, 2048), Charsets.UTF_8)))
            begin = p + 1; if (result.size >= max) break
        }; return result
    }
    private fun requireCode(address: Long): Long {
        require((machine in listOf(183,40,62,3) && ToolkitNative.available) || (machine == 183 && little)) { "此架构的指令分析库不可用；仍可查看 ELF/地址/字节" }
        if(machine == 183) require(address % 4 == 0L) { "AArch64 指令地址必须 4 字节对齐" }
        require(segments.any { it.type == 1L && it.flags and 1L != 0L && address >= it.address && address - it.address < it.size }) { "地址不属于可执行 LOAD 区段" }
        return fileOffset(address) ?: error("地址没有文件内容")
    }
    fun instructions(args: JsonObject): List<Instruction> {
        val requested = address(args)
        val address = if(machine == 40) requested and -2L else requested
        val offset = requireCode(address)
        var budget = args.tInt("maxBytes", 256, 4, 65536)
        if (args.tBool("stopAtSymbolBoundary", true)) symbols.filter { it.type == 2 && address >= it.address && address - it.address < it.size }
            .minByOrNull { it.size }?.let { budget = minOf(budget.toLong(), it.address + it.size - address).toInt() }
        val limit = args.tInt("maxInstructions", 64, 1, 16384)
        if(ToolkitNative.available) {
            val segment = segments.first { it.type == 1L && address >= it.address && address - it.address < it.size }
            val count = minOf(budget.toLong(), segment.size - (address - segment.address), bytes.size - offset).toInt()
            val mode = if(args.tString("instructionMode", "auto") == "thumb" || (machine == 40 && requested and 1L != 0L)) 1 else 0
            return nativeDecode(bytes.copyOfRange(offset.toInt(), offset.toInt()+count), address, offset.toInt(), mode, limit)
        }
        return (0 until minOf(limit, budget / 4)).mapNotNull { index ->
            val v = address + index * 4
            if (fileOffset(v) != offset + index * 4 || offset + index * 4 + 4 > bytes.size) null else decode(v, (offset + index * 4).toInt())
        }
    }
    private fun nativeDecode(input: ByteArray, address: Long, offset: Int, mode: Int, count: Int): List<Instruction> =
        ToolkitNative.decode(input,machine,little,address,mode,count).map { item ->
            Instruction(number(item.tString("address")),offset+item.get("offset").asInt,item.tString("hex"),item.tString("mnemonic"),item.tString("operands"),
                item.get("target")?.takeIf { !it.isJsonNull }?.asString?.let(::number),item.tString("kind"),item.tBool("conditional"),item.tBool("terminal"),item.tBool("decoded"),item.get("size").asInt)
        }
    private fun decode(v: Long, p: Int): Instruction {
        val w = u32(p); fun sign(n: Long, bits: Int) = (n shl (64 - bits)) shr (64 - bits)
        val rd = (w and 31).toInt(); val rn = (w ushr 5 and 31).toInt(); val x = if (w ushr 31 != 0L) "x" else "w"
        fun ins(name: String, operands: String = "", target: Long? = null, kind: String? = null, conditional: Boolean = false, terminal: Boolean = false, decoded: Boolean = true) =
            Instruction(v, p, ToolkitIo.hex(bytes.copyOfRange(p, p + 4)), name, operands, target, kind, conditional, terminal, decoded)
        if (w == 0xd503201fL) return ins("nop")
        if (w and 0xfffffc1fL == 0xd65f0000L) return ins("ret", "x$rn", terminal = true)
        if (w and 0xfffffc1fL == 0xd61f0000L) return ins("br", "x$rn", kind = "unknown", terminal = true)
        if (w and 0xfffffc1fL == 0xd63f0000L) return ins("blr", "x$rn", kind = "unknown")
        if (w and 0x7c000000L == 0x14000000L) {
            val target = v + (sign(w and 0x3ffffff, 26) shl 2); val call = w and 0x80000000L != 0L
            return ins(if(call) "bl" else "b", hex(target), target, if(call) "call" else "jump", terminal = !call)
        }
        if (w and 0xff000010L == 0x54000000L) {
            val target = v + (sign(w ushr 5 and 0x7ffff, 19) shl 2)
            val conditions = listOf("eq","ne","cs","cc","mi","pl","vs","vc","hi","ls","ge","lt","gt","le","al","nv")
            return ins("b.${conditions[(w and 15).toInt()]}", hex(target), target, "jump", conditional = true)
        }
        if (w and 0x7e000000L == 0x34000000L) {
            val target = v + (sign(w ushr 5 and 0x7ffff, 19) shl 2)
            return ins(if(w and 0x1000000L != 0L) "cbnz" else "cbz", "$x$rd, ${hex(target)}", target, "jump", true)
        }
        if (w and 0x7e000000L == 0x36000000L) {
            val target = v + (sign(w ushr 5 and 0x3fff, 14) shl 2); val bit = (w ushr 31 shl 5) or (w ushr 19 and 31)
            return ins(if(w and 0x1000000L != 0L) "tbnz" else "tbz", "$x$rd, #$bit, ${hex(target)}", target, "jump", true)
        }
        if (w and 0x1f000000L == 0x10000000L) {
            val immediate = sign((w ushr 5 and 0x7ffff shl 2) or (w ushr 29 and 3), 21); val page = w ushr 31 != 0L
            val target = if(page) (v and -4096L) + (immediate shl 12) else v + immediate
            return ins(if(page) "adrp" else "adr", "x$rd, ${hex(target)}", target, "data")
        }
        if (w and 0x1f000000L == 0x11000000L) return ins(if(w and 0x40000000L != 0L) "sub" else "add", "$x$rd, $x$rn, #${(w ushr 10 and 4095) shl (if(w and 0x400000 != 0L) 12 else 0)}")
        if (w and 0x7f800000L == 0x52800000L || w and 0x7f800000L == 0x72800000L) return ins(if(w and 0x20000000L != 0L) "movk" else "movz", "$x$rd, #${w ushr 5 and 65535}, lsl #${(w ushr 21 and 3) * 16}")
        if (w and 0x7fe0ffe0L == 0x2a0003e0L) return ins("mov", "$x$rd, $x${w ushr 16 and 31}")
        if (w and 0xffe0001fL == 0xd4000001L) return ins("svc", "#${w ushr 5 and 65535}")
        return ins(".inst", hex(w), decoded = false)
    }
    fun instructionJson(instruction: Instruction) = ToolkitIo.json("address" to hex(instruction.address), "fileOffset" to instruction.offset,
        "hex" to instruction.hex, "mnemonic" to instruction.mnemonic, "operands" to instruction.operands,
        "target" to instruction.target?.let(::hex), "referenceKind" to instruction.kind, "decoded" to instruction.decoded, "size" to instruction.size)
    fun xref(args: JsonObject, resultLimit: Int = args.tInt("limit", 50, 1, 200)): JsonObject {
        val target = address(args); val max = args.tInt("maxScanBytes", 1048576, 4, 8 * 1024 * 1024)
        val limit = resultLimit.also { require(it in 1..10001) }; val kind = args.tString("scanKind", "both")
        require(kind in listOf("code", "data", "both"))
        val allowed = args.getAsJsonArray("referenceKinds")?.map { it.asString } ?: listOf("call", "jump", "data", "unknown")
        val result = ArrayList<JsonObject>(); val dataByAddress = HashMap<Long, JsonObject>()
        var scanned = 0L; var partial = false; var relocationXrefs = 0; var relocationScanTruncated = false
        // Relocation slots are high-value data references. Scan them first, under the same
        // byte budget, so a large text segment cannot starve them.
        if (kind != "code" && "data" in allowed) {
            relocationLoop@ for (section in sections.filter { it.type == 4L || it.type == 9L }) {
                if (section.size == 0L) continue
                val stride = relocationStride(section)
                val count = (section.size / stride).toInt()
                for (index in 0 until count) {
                    if (scanned + stride > max || result.size >= limit) {
                        partial = true; relocationScanTruncated = true; break@relocationLoop
                    }
                    val p = (section.offset + index * stride).toInt()
                    val slot = word(p)
                    val info = word(p + if (wide) 8 else 4)
                    val relocationType = if (wide) info and 0xffffffffL else info and 255L
                    val symbolIndex = if (wide) info ushr 32 else info ushr 8
                    val addend = if (section.type == 4L) {
                        if (wide) buffer.getLong(p + 16) else buffer.getInt(p + 8).toLong()
                    } else null
                    scanned += stride
                    if (relocationType == 0L) continue
                    val symbol = relocationSymbol(section.link, symbolIndex)
                    val family = relocationFamily(relocationType) ?: continue
                    val slotValue = if (addend == null) relocationSlotValue(slot, signed = symbolIndex != 0L) else null
                    val appliedAddend = addend ?: slotValue
                    val referenced = when {
                        family == "relative" && symbolIndex == 0L -> appliedAddend
                        family == "symbol" && symbol != null && symbol.section != 0 -> symbol.address
                        family == "absolute" && symbol != null && symbol.section != 0 && appliedAddend != null -> symbol.address + appliedAddend
                        else -> null
                    }
                    if (referenced == null || referenced != target) continue
                    relocationXrefs++
                    val item = dataByAddress[slot] ?: ToolkitIo.json(
                        "address" to hex(slot),
                        "fileOffset" to fileOffset(slot)?.toInt(),
                        "referenceKind" to "data",
                    ).also { result.add(it); dataByAddress[slot] = it }
                    item.addProperty("relocation", true)
                    item.addProperty("relocationSlot", hex(slot))
                    item.addProperty("relocationType", relocationType)
                    item.addProperty("relocationTypeName", relocationTypeName(relocationType))
                    item.addProperty("relocationSection", section.name)
                    item.addProperty("relocationEntryOffset", section.offset + index * stride)
                    item.addProperty("symbolIndex", symbolIndex)
                    symbol?.let { item.addProperty("symbol", it.name) }
                    addend?.let { item.addProperty("addend", it) }
                    if (result.size >= limit) { partial = true; relocationScanTruncated = true; break@relocationLoop }
                }
            }
        }
        outer@ for (segment in segments.filter { it.type == 1L }) {
            val code = segment.flags and 1 != 0L
            if ((code && kind == "data") || (!code && kind == "code")) continue
            if (code) requireCode(segment.address)
            if(code && ToolkitNative.available) {
                var local = 0L
                while(local < segment.size) {
                    if(scanned >= max || result.size >= limit) { partial=true;break@outer }
                    val size = minOf(65536L,segment.size-local,max-scanned).toInt()
                    val offset=(segment.offset+local).toInt()
                    val mode=if(args.tString("instructionMode","auto")=="thumb") 1 else 0
                    val instructions=nativeDecode(bytes.copyOfRange(offset,offset+size),segment.address+local,offset,mode,16384)
                    var used=0
                    for(instruction in instructions) {
                        if(instruction.target==target && instruction.kind in allowed) result.add(instructionJson(instruction))
                        if(!instruction.decoded)partial=true
                        used=instruction.offset-offset+instruction.size
                        if(result.size>=limit) { partial=true;break }
                    }
                    if(used==0) { partial=true;used=size }
                    local+=used;scanned+=used
                }
                continue
            }
            val step = if(code) 4 else if(wide) 8 else 4
            var n = 0L
            while (n + step <= segment.size) {
                if (scanned + step > max || result.size >= limit) { partial = true; break@outer }
                val p = (segment.offset + n).toInt(); val v = segment.address + n
                if (code) {
                    val instruction = decode(v, p)
                    if (instruction.target == target && instruction.kind in allowed) result.add(instructionJson(instruction))
                    if (!instruction.decoded) partial = true
                } else if ((if(wide) buffer.getLong(p) else u32(p)) == target && "data" in allowed) {
                    if (!dataByAddress.containsKey(v)) {
                        val item = ToolkitIo.json("address" to hex(v), "fileOffset" to p, "referenceKind" to "data")
                        result.add(item); dataByAddress[v] = item
                    }
                }
                n += step; scanned += step
            }
        }
        return ToolkitIo.json("items" to result, "scannedBytes" to scanned, "truncated" to partial, "complete" to false,
            "relocationXrefs" to relocationXrefs, "relocationScanTruncated" to relocationScanTruncated,
            "note" to "仅直接静态引用；已把可静态解析到目标地址的 REL/RELA 重定位槽纳入 data xref（与普通指针字面值同样受扫描预算限制）；间接跳转、完整控制流/数据流、ADR/ADRP+ADD 数据流与未解码指令仍不构成完整调用图")
    }

    private fun relocationStride(section: Section): Long {
        val minimum = when {
            wide && section.type == 4L -> 24L
            wide -> 16L
            section.type == 4L -> 12L
            else -> 8L
        }
        val stride = if (section.entrySize > 0) section.entrySize else minimum
        require(stride >= minimum && section.size / stride <= 300000) { "ELF 重定位表无效或过大" }
        return stride
    }

    private fun relocationSymbol(link: Int, index: Long): Symbol? {
        if (index < 0 || index > Int.MAX_VALUE.toLong()) return null
        return symbolTables[link]?.getOrNull(index.toInt())
    }

    private fun relocationSlotValue(address: Long, signed: Boolean): Long? {
        val offset = fileOffset(address) ?: return null
        val width = if (wide) 8 else 4
        if (offset < 0 || width > bytes.size - offset) return null
        return when {
            wide -> buffer.getLong(offset.toInt())
            signed -> buffer.getInt(offset.toInt()).toLong()
            else -> u32(offset.toInt())
        }
    }

    private fun relocationTypeName(type: Long): String = when (machine) {
        183 -> when (type) {
            257L -> "R_AARCH64_ABS64"
            258L -> "R_AARCH64_ABS32"
            1025L -> "R_AARCH64_GLOB_DAT"
            1026L -> "R_AARCH64_JUMP_SLOT"
            1027L -> "R_AARCH64_RELATIVE"
            else -> "type-$type"
        }
        40 -> when (type) {
            2L -> "R_ARM_ABS32"
            21L -> "R_ARM_GLOB_DAT"
            22L -> "R_ARM_JUMP_SLOT"
            23L -> "R_ARM_RELATIVE"
            else -> "type-$type"
        }
        62 -> when (type) {
            1L -> "R_X86_64_64"
            6L -> "R_X86_64_GLOB_DAT"
            7L -> "R_X86_64_JUMP_SLOT"
            8L -> "R_X86_64_RELATIVE"
            10L -> "R_X86_64_32"
            11L -> "R_X86_64_32S"
            else -> "type-$type"
        }
        3 -> when (type) {
            1L -> "R_386_32"
            6L -> "R_386_GLOB_DAT"
            7L -> "R_386_JMP_SLOT"
            8L -> "R_386_RELATIVE"
            else -> "type-$type"
        }
        else -> "type-$type"
    }
    // Only relocation formulas that identify a static target are eligible for data xrefs.
    // PC-relative instructions, TLS, IFUNC and architecture-specific encodings need analysis
    // beyond adding a symbol and an addend; do not present those as confirmed references.
    private fun relocationFamily(type: Long): String? = when (machine) {
        183 -> when(type) { 257L, 258L -> "absolute"; 1025L, 1026L -> "symbol"; 1027L -> "relative"; else -> null }
        40 -> when(type) { 2L -> "absolute"; 21L, 22L -> "symbol"; 23L -> "relative"; else -> null }
        62 -> when(type) { 1L, 10L, 11L -> "absolute"; 6L, 7L -> "symbol"; 8L -> "relative"; else -> null }
        3 -> when(type) { 1L -> "absolute"; 6L, 7L -> "symbol"; 8L -> "relative"; else -> null }
        else -> null
    }
    fun cfg(args: JsonObject): JsonObject {
        val outputMode = args.tString("outputMode", "full"); require(outputMode in listOf("full", "summary")) { "outputMode必须为full或summary" }
        var start = address(args)
        val armSpec = if(machine == 40) ToolkitAssembler.resolve(machine, args.tString("instructionMode", "auto"), start,
            virtualInput = args.tString("addressKind") == "virtual", mappedAddress = start) else null
        if(armSpec != null) start = armSpec.effectiveAddress
        requireCode(start)
        val symbol = symbols.filter { it.type == 2 && it.section != 0 && start >= it.address && start - it.address < it.size }.minByOrNull { it.size }
        if (symbol != null) start = symbol.address
        val budget = args.tInt("maxAnalysisBytes", 4096, 4, 65536); val end = start + minOf(budget.toLong(), symbol?.size ?: budget.toLong())
        val maxBlocks = args.tInt("maxBlocks", 128, 1, 4096); val maxInstructions = args.tInt("maxInstructions", 2048, 1, 16384)
        val decoded=if(ToolkitNative.available) instructions(args.deepCopy().apply { addProperty("addressKind","virtual");addProperty("address",hex(start));addProperty("maxBytes",budget);addProperty("maxInstructions",16384);addProperty("stopAtSymbolBoundary",true); armSpec?.let { addProperty("instructionMode", it.instructionSet) } }).associateBy { it.address } else null
        val queue = ArrayDeque<Long>(); queue.add(start); val seen = HashSet<Long>(); val blocks = ArrayList<JsonObject>(); val edges = ArrayList<JsonObject>()
        val external = ArrayList<JsonObject>(); val unresolved = ArrayList<JsonObject>(); val decodedAddresses = HashSet<Long>()
        var count = 0; var partial = false
        while (queue.isNotEmpty() && blocks.size < maxBlocks && count < maxInstructions) {
            val begin = queue.removeFirst(); if (!seen.add(begin) || begin < start || begin >= end) continue
            var v = begin; val instructions = ArrayList<JsonObject>()
            while (v < end && count < maxInstructions) {
                val p = fileOffset(v) ?: break
                if(decoded == null && p + 4 > bytes.size) break
                val instruction = if(decoded!=null) decoded[v] ?: break else decode(v, p.toInt())
                if(!decodedAddresses.add(v)) {
                    edges.add(ToolkitIo.json("from" to hex(begin), "to" to hex(v), "kind" to "fallthrough")); break
                }
                instructions.add(instructionJson(instruction)); count++
                if (!instruction.decoded) { partial = true; break }
                if(instruction.kind == "call") {
                    if(instruction.target != null) external.add(ToolkitIo.json("from" to hex(v), "to" to hex(instruction.target), "kind" to "call"))
                    else unresolved.add(ToolkitIo.json("from" to hex(v), "kind" to "indirect_call"))
                }
                if (instruction.kind == "jump") {
                    instruction.target?.let { target ->
                        edges.add(ToolkitIo.json("from" to hex(begin), "to" to hex(target), "kind" to "branch"))
                        if (target in start until end) queue.add(target) else external.add(ToolkitIo.json("from" to hex(v), "to" to hex(target), "kind" to "branch"))
                    } ?: unresolved.add(ToolkitIo.json("from" to hex(v), "kind" to "indirect_branch"))
                    if (instruction.conditional && v + instruction.size < end) { edges.add(ToolkitIo.json("from" to hex(begin), "to" to hex(v + instruction.size), "kind" to "fallthrough")); queue.add(v + instruction.size) }
                    break
                }
                if (instruction.terminal) break
                v += instruction.size
                if (v in seen) break
            }
            blocks.add(ToolkitIo.json("address" to hex(begin), "instructions" to instructions, "instructionCount" to instructions.size))
        }
        partial = partial || queue.isNotEmpty() || count >= maxInstructions
        val summary = ToolkitIo.json("blockCount" to blocks.size, "edgeCount" to edges.size, "instructionCount" to count,
            "decodedByteCount" to decodedAddresses.sumOf { decoded?.get(it)?.size?.toLong() ?: 4L },
            "externalFlowCount" to external.size, "unresolvedFlowCount" to unresolved.size)
        return ToolkitIo.json("functionStart" to hex(start), "symbol" to symbol?.name, "outputMode" to outputMode,
            "blocks" to if(outputMode == "full") blocks else emptyList<JsonObject>(), "edges" to if(outputMode == "full") edges else emptyList<JsonObject>(),
            "externalFlows" to if(outputMode == "full") external else emptyList<JsonObject>(), "unresolvedFlows" to if(outputMode == "full") unresolved else emptyList<JsonObject>(),
            "analysisSummary" to summary, "observedGraphSummary" to summary,
            "instructionCount" to count, "truncated" to partial, "complete" to false, "note" to "有界直接控制流；遇到未解码指令停止该路径，间接分支不猜测")
    }
    companion object {
        fun number(value: String): Long {
            val n = if (value.startsWith("0x", true)) BigInteger(value.substring(2), 16) else BigInteger(value, 10)
            require(n.signum() >= 0 && n.bitLength() <= 63) { "地址数值超出范围" }; return n.toLong()
        }
        fun hex(value: Long) = "0x" + java.lang.Long.toHexString(value)
        fun assembleSmall(source: String, base: Long): ByteArray? =
            // The legacy entry point stays AArch64-only and keeps its nullable contract so
            // existing callers still fall back to the optional clang backend.
            ToolkitAssembler.tryAssemble(183, base, source, "aarch64")
    }
}
