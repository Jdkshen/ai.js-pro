package com.jdkshen.aijspro.mcp

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * W-E：在 ELF 内就地增加 DT_NEEDED，绝不改变文件大小。
 *
 * 只利用已有空隙：dynstr 已有同名串或节尾 NUL padding；dynamic 表的 spare DT_NULL。
 * 不移动任何节/段，因此不会破坏 4K/16K 页对齐。失败返回原因码和外部 patchelf 建议，不产出半补丁。
 */
internal object ToolkitElfNeeded {
    const val DEFAULT_LIBRARY = "libaijsgadget.so"

    data class Outcome(
        val applied: Boolean,
        val soname: String,
        val strtabOffset: Long = 0,
        val dynamicIndex: Int = -1,
        val dynamicOffset: Long = 0,
        val changedRanges: List<IntRange> = emptyList(),
        val verified: Boolean = false,
        val reason: String? = null,
        val reasons: List<String> = emptyList(),
        val bytes: ByteArray? = null,
    ) {
        fun json(targetPath: String? = null): com.google.gson.JsonObject = ToolkitIo.json(
            "applied" to applied,
            "soname" to soname,
            "targetPath" to targetPath,
            "strtabOffset" to strtabOffset,
            "dynamicIndex" to dynamicIndex,
            "dynamicOffset" to dynamicOffset,
            "verified" to verified,
            "reason" to reason,
            "reasons" to reasons,
            "changedRanges" to changedRanges.map { ToolkitIo.json("start" to it.first, "end" to it.last) },
            "nextActions" to if (applied) emptyList<String>() else listOf(
                "用外部 patchelf --add-needed $soname --page-size 16384 <target.so> 后重新组装 APK",
                "或换一个 .dynstr/.dynamic 有余量的目标 .so；不要用会改变文件大小的粗暴插入",
            ),
        )
    }

    private class Failure(val code: String, message: String) : Exception(message)

    private data class Load(val offset: Long, val vaddr: Long, val filesz: Long)
    private data class Section(val type: Long, val offset: Long, val size: Long, val link: Int, val entsize: Long)
    private data class DynamicEntry(val tag: Long, val value: Long)

    fun patch(input: ByteArray, soname: String = DEFAULT_LIBRARY): Outcome {
        val name = soname.trim()
        if (name.isEmpty() || name.contains('\u0000') || name.contains('/') || name.length > 255) {
            return Outcome(false, soname, reason = "INVALID_SONAME", reasons = listOf("soname 必须是 <=255 字节的库名，不能含 NUL/路径分隔符"))
        }
        return try {
            patchOrThrow(input, name)
        } catch (failure: Failure) {
            Outcome(false, name, reason = failure.code, reasons = listOf(failure.message ?: failure.code))
        } catch (failure: Exception) {
            Outcome(false, name, reason = "ELF_PATCH_FAILED", reasons = listOf("解析/写入失败：" + (failure.message ?: failure.javaClass.simpleName)))
        }
    }

    private fun patchOrThrow(input: ByteArray, soname: String): Outcome {
        if (input.size < 52) throw Failure("NOT_ELF", "文件太小，不是 ELF")
        if (input[0] != 0x7f.toByte() || input[1] != 'E'.code.toByte() || input[2] != 'L'.code.toByte() || input[3] != 'F'.code.toByte()) {
            throw Failure("NOT_ELF", "没有 ELF 魔数")
        }
        val elfClass = input[4].toInt() and 0xff
        if (elfClass != 1 && elfClass != 2) throw Failure("UNSUPPORTED_ELF_CLASS", "只支持 ELF32/ELF64，class=" + elfClass)
        val is64 = elfClass == 2
        if ((input[5].toInt() and 0xff) != 1) throw Failure("UNSUPPORTED_ENDIAN", "只支持小端 ELF（EI_DATA=1）")

        val phoff = word(input, is64, if (is64) 32 else 28)
        val phentsize = u16(input, if (is64) 54 else 42).toInt()
        val phnum = u16(input, if (is64) 56 else 44).toInt()
        val shoff = word(input, is64, if (is64) 40 else 32)
        val shentsize = u16(input, if (is64) 58 else 46).toInt()
        val shnum = u16(input, if (is64) 60 else 48).toInt()
        if (phentsize <= 0 || phnum < 0) throw Failure("DYNAMIC_INVALID", "程序头数量/大小无效")
        if (!within(input, phoff, phnum.toLong() * phentsize)) throw Failure("DYNAMIC_INVALID", "程序头越界")

        val loads = ArrayList<Load>()
        var dynamicSegment: Load? = null
        for (index in 0 until phnum) {
            val base = phoff + index.toLong() * phentsize
            val type = u32l(input, base)
            val offset = if (is64) u64(input, base + 8) else u32l(input, base + 4)
            val vaddr = if (is64) u64(input, base + 16) else u32l(input, base + 8)
            val filesz = if (is64) u64(input, base + 32) else u32l(input, base + 16)
            if (type == 1L) loads.add(Load(offset, vaddr, filesz))
            if (type == 2L) dynamicSegment = Load(offset, vaddr, filesz)
        }

        val sections = ArrayList<Section>()
        if (shoff > 0 && shnum > 0 && shentsize > 0) {
            if (within(input, shoff, shnum.toLong() * shentsize)) {
                for (index in 0 until shnum) {
                    val base = shoff + index.toLong() * shentsize
                    val type = u32l(input, base + 4)
                    val offset = if (is64) u64(input, base + 24) else u32l(input, base + 16)
                    val size = if (is64) u64(input, base + 32) else u32l(input, base + 20)
                    val link = word(input, is64, base + if (is64) 40 else 24).toInt()
                    val entsize = if (is64) u64(input, base + 56) else u32l(input, base + 36)
                    sections.add(Section(type, offset, size, link, entsize))
                }
            }
        }

        var dynamicOffset = 0L
        var dynamicSize = 0L
        var dynamicEntrySize = if (is64) 16 else 8
        var dynstrOffset = 0L
        var dynstrSize = 0L
        val dynamicSection = sections.firstOrNull { it.type == 6L }
        if (dynamicSection != null && validRange(input, dynamicSection.offset, dynamicSection.size)) {
            dynamicOffset = dynamicSection.offset
            dynamicSize = dynamicSection.size
            dynamicEntrySize = when {
                dynamicSection.entsize == 8L || dynamicSection.entsize == 16L -> dynamicSection.entsize.toInt()
                is64 -> 16
                else -> 8
            }
            val strtabSection = sections.getOrNull(dynamicSection.link)
            if (strtabSection != null && strtabSection.type == 3L && validRange(input, strtabSection.offset, strtabSection.size)) {
                dynstrOffset = strtabSection.offset
                dynstrSize = strtabSection.size
            }
        }
        if (dynamicOffset == 0L && dynamicSegment != null) {
            if (!validRange(input, dynamicSegment.offset, dynamicSegment.filesz)) throw Failure("DYNAMIC_INVALID", "PT_DYNAMIC 越界")
            dynamicOffset = dynamicSegment.offset
            dynamicSize = dynamicSegment.filesz
        }
        if (dynamicOffset <= 0L || dynamicSize < dynamicEntrySize) throw Failure("NO_DYNAMIC_SEGMENT", "没有可读的 PT_DYNAMIC/.dynamic")

        val dynamic = readDynamic(input, dynamicOffset, dynamicSize, dynamicEntrySize)
        if (dynstrOffset <= 0L || dynstrSize == 0L) {
            val strtab = dynamic.firstOrNull { it.tag == 5L }?.value ?: throw Failure("NO_DYNAMIC_SEGMENT", "缺少 DT_STRTAB")
            val strsz = dynamic.firstOrNull { it.tag == 10L }?.value ?: throw Failure("NO_DYNAMIC_SEGMENT", "缺少 DT_STRSZ")
            val mapped = vaddrToOffset(loads, strtab) ?: throw Failure("DYNSTR_INVALID", "DT_STRTAB 0x" + java.lang.Long.toHexString(strtab) + " 不映射到 PT_LOAD")
            if (!validRange(input, mapped, strsz)) throw Failure("DYNSTR_INVALID", "DT_STRTAB/DT_STRSZ 越界")
            dynstrOffset = mapped
            dynstrSize = strsz
        }
        if (!validRange(input, dynstrOffset, dynstrSize)) throw Failure("DYNSTR_INVALID", "dynstr 越界")

        val sonameBytes = soname.toByteArray(Charsets.UTF_8)
        val need = sonameBytes.size + 1
        val existing = findString(input, dynstrOffset, dynstrSize, soname)
        var newStringOffset = 0L
        if (existing < 0) {
            var lastNonZero = -1L
            for (index in 0 until dynstrSize.toInt()) {
                if (input[index(dynstrOffset + index)] != 0.toByte()) lastNonZero = index.toLong()
            }
            val freeStart = if (lastNonZero < 0) 0L else lastNonZero + 2
            val have = (dynstrSize - freeStart).toInt().coerceAtLeast(0)
            if (freeStart >= dynstrSize || need > have) throw Failure("DYNSTR_NO_ROOM:" + need + "x" + have,
                ".dynstr 节尾只有 " + have + " 字节可用 padding，需 " + need + " 字节；不要插入字节，改用外部 patchelf 或换目标 .so")
            newStringOffset = freeStart
        }
        val nullIndices = dynamic.mapIndexedNotNull { index, entry -> if (entry.tag == 0L) index else null }
        val spareIndex = nullIndices.firstOrNull { index ->
            dynamic.getOrNull(index + 1)?.tag == 0L
        } ?: throw Failure("DYNAMIC_NO_SPARE_NULL", "dynamic 表没有“改掉后仍保留 DT_NULL 终止符”的空槽")
        if (spareIndex < 0) throw Failure("DYNAMIC_NO_SPARE_NULL", "dynamic 表没有 DT_NULL 槽")

        val patched = input.copyOf()
        val ranges = ArrayList<IntRange>()
        val sonameOffset = if (existing >= 0) existing.toLong() else {
            val start = index(dynstrOffset + newStringOffset)
            sonameBytes.copyInto(patched, start)
            patched[start + sonameBytes.size] = 0
            ranges.add(start until start + sonameBytes.size + 1)
            newStringOffset
        }
        val dynBase = index(dynamicOffset + spareIndex.toLong() * dynamicEntrySize)
        putU32(patched, dynBase, 1L)
        if (is64) putU64(patched, dynBase + 8, sonameOffset) else putU32(patched, dynBase + 4, sonameOffset)
        ranges.add(dynBase until dynBase + dynamicEntrySize)

        val check = readDynamic(patched, dynamicOffset, dynamicSize, dynamicEntrySize)
        if (check.none { it.tag == 1L && it.value == sonameOffset }) throw Failure("VERIFY_FAILED", "写后读不到新增 DT_NEEDED")
        if (findString(patched, dynstrOffset, dynstrSize, soname) < 0) throw Failure("VERIFY_FAILED", "写后 dynstr 读不到 " + soname)
        if (patched.size != input.size) throw Failure("VERIFY_FAILED", "文件大小改变")
        // The exact changed ranges must be the only differences.
        for (position in patched.indices) {
            if (patched[position] == input[position]) continue
            if (ranges.none { position in it }) throw Failure("VERIFY_FAILED", "修改了声明范围外的字节 offset=" + position)
        }
        return Outcome(true, soname, sonameOffset, spareIndex, dynamicOffset,
            ranges.sortedBy { it.first }, verified = true, bytes = patched)
    }

    private fun readDynamic(input: ByteArray, offset: Long, size: Long, entrySize: Int): List<DynamicEntry> {
        val count = (size / entrySize).toInt()
        if (count <= 0) throw Failure("DYNAMIC_INVALID", "dynamic 表为空")
        val result = ArrayList<DynamicEntry>(count)
        for (index in 0 until count) {
            val base = index(offset + index.toLong() * entrySize)
            val tag = u32l(input, base.toLong())
            val value = if (entrySize == 16) u64(input, base.toLong() + 8) else u32l(input, base.toLong() + 4)
            result.add(DynamicEntry(tag, value))
            if (tag == 0L && index > 0) {
                // Keep scanning so a second DT_NULL can be used as the spare slot.
            }
        }
        return result
    }

    private fun findString(input: ByteArray, offset: Long, size: Long, value: String): Int {
        val needle = value.toByteArray(Charsets.UTF_8)
        val start = index(offset)
        val end = start + size.toInt()
        if (start < 0 || end > input.size || end - start < needle.size + 1) return -1
        var position = start
        while (position <= end - needle.size - 1) {
            var match = true
            for (index in needle.indices) if (input[position + index] != needle[index]) { match = false; break }
            if (match && input[position + needle.size] == 0.toByte()) return position - start
            position++
        }
        return -1
    }

    private fun vaddrToOffset(loads: List<Load>, address: Long): Long? =
        loads.firstOrNull { address >= it.vaddr && address - it.vaddr < it.filesz }
            ?.let { it.offset + (address - it.vaddr) }

    private fun validRange(input: ByteArray, offset: Long, size: Long): Boolean =
        offset >= 0 && size >= 0 && offset <= input.size.toLong() && size <= input.size.toLong() - offset

    private fun within(input: ByteArray, offset: Long, size: Long): Boolean = validRange(input, offset, size)

    private fun index(offset: Long): Int {
        if (offset < 0 || offset > Int.MAX_VALUE) throw Failure("DYNAMIC_INVALID", "offset 超出可处理范围")
        return offset.toInt()
    }

    private fun index(input: ByteArray, offset: Long): Int = index(offset).also { if (it < 0 || it >= input.size) throw Failure("DYNAMIC_INVALID", "索引越界") }
    private fun index(input: ByteArray, offset: Long, length: Int): Int = index(input, offset).also { if (it + length > input.size) throw Failure("DYNAMIC_INVALID", "索引长度越界") }

    private fun u16(input: ByteArray, offset: Long): Long = (index(input, offset, 2).let { input[it].toLong() and 0xff } or
        ((input[index(input, offset, 2) + 1].toLong() and 0xff) shl 8))
    private fun u32l(input: ByteArray, offset: Long): Long = ByteBuffer.wrap(input, index(input, offset, 4), 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
    private fun u64(input: ByteArray, offset: Long): Long = ByteBuffer.wrap(input, index(input, offset, 8), 8).order(ByteOrder.LITTLE_ENDIAN).long
    private fun word(input: ByteArray, is64: Boolean, offset: Long): Long = if (is64) u64(input, offset) else u32l(input, offset)
    private fun putU32(input: ByteArray, offset: Int, value: Long) {
        input[offset] = (value and 0xff).toByte(); input[offset + 1] = (value ushr 8 and 0xff).toByte()
        input[offset + 2] = (value ushr 16 and 0xff).toByte(); input[offset + 3] = (value ushr 24 and 0xff).toByte()
    }
    private fun putU64(input: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) input[offset + i] = (value ushr (i * 8) and 0xff).toByte()
    }
}
