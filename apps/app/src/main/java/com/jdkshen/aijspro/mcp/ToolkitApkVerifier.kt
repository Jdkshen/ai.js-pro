package com.jdkshen.aijspro.mcp

import com.android.apksig.ApkVerifier
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.reandroid.xml.XMLDocument
import com.reandroid.xml.XMLElement
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.LinkedHashMap
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Read-only APK evidence collector.
 *
 * The verifier deliberately separates four independent facts:
 *  - ZIP data offsets (4-byte for STORED files, 16 KiB for STORED `.so` files),
 *  - ELF PT_LOAD alignment (the archive padding does not relink native code),
 *  - manifest SDK/ABI/split declarations,
 *  - cryptographic APK signature verification performed by the official apksig `ApkVerifier`.
 *
 * The signature section never derives a "verified" result from certificate metadata. It only reports
 * the apksig verdict and its evidence. The report is not an installability or business-behavior
 * verdict; those require an explicit separate install/launch test.
 *
 * Root-owned build integration: the app needs `com.android.tools.build:apksig:8.6.1` on the runtime
 * classpath (see APKSIG_COORDINATE). This file intentionally does not edit Gradle files.
 */
internal object ToolkitApkVerifier {
    private const val APKSIG_COORDINATE = "com.android.tools.build:apksig:8.6.1"
    private const val STATUS_MEANING = "valid=所有已执行检查通过（含密码学签名）；partial=至少一项失败/跳过/不支持；unavailable=文件或检查后端不可用"
    private const val MAX_EOCD_BYTES = 22 + 0xffff
    private const val MAX_CENTRAL_BYTES = 64L * 1024 * 1024
    private const val MAX_ENTRIES = 65_535
    private const val MAX_ISSUES_PER_CHECK = 64
    private const val MAX_ISSUES = 200
    private const val MAX_NATIVE_LIBS = 256
    private const val MAX_NATIVE_TOTAL_BYTES = 256L * 1024 * 1024
    private const val MAX_ELF_SEGMENTS = 4096
    private const val MAX_REPORTED_ELF_SEGMENTS = 128
    private const val MAX_APKSIG_ISSUES = 100
    private const val MAX_MANIFEST_TEXT = 4 * 1024 * 1024

    private const val ZIP_EOCD = 0x06054b50L
    private const val ZIP_CENTRAL = 0x02014b50L
    private const val ZIP_LOCAL = 0x04034b50L
    private const val ZIP64_LOCATOR = 0x07064b50L
    private const val ZIP_FLAG_ENCRYPTED = 1
    private const val ZIP_FLAG_UTF8 = 0x800
    private const val ZIP_METHOD_STORED = 0
    private const val ZIP_METHOD_DEFLATED = 8
    private const val ALIGN_STORED = 4L
    private const val ALIGN_NATIVE = 16384L
    private const val PAGE_16K = 16384L
    private const val PAGE_4K = 4096L

    @JvmStatic
    fun inspect(file: File, minSdk: Int? = null, maxSdk: Int? = null): JsonObject {
        require(minSdk == null || minSdk >= 1) { "minSdk 必须大于 0" }
        require(maxSdk == null || maxSdk >= 1) { "maxSdk 必须大于 0" }
        require(minSdk == null || maxSdk == null || minSdk <= maxSdk) { "minSdk 不能大于 maxSdk" }
        if (!file.isFile || !file.canRead()) return unavailableFile(file, "文件不存在或不可读")
        val sha = try {
            ToolkitIo.sha(file)
        } catch (failure: Exception) {
            return unavailableFile(file, failure.message ?: "文件摘要读取失败")
        }
        val zip = readZip(file)
        val manifest = inspectManifest(file, zip, minSdk, maxSdk)
        val native = inspectNative(file, zip)
        val signature = inspectSignature(file, minSdk, maxSdk, sha)
        return buildReport(file, sha, zip, manifest, native, signature)
    }

    // ---------------------------------------------------------------- report

    private fun buildReport(file: File, sha: String?, zip: ZipSection, manifest: Section,
                            native: Section, signature: Section): JsonObject {
        val issues = ArrayList<JsonObject>()
        issues.addAll(zip.issues)
        issues.addAll(manifest.issues)
        issues.addAll(native.issues)
        issues.addAll(signature.issues)
        val statuses = listOf(zip.status, manifest.status, native.status, signature.status)
        val status = when {
            zip.status == "unavailable" && manifest.status == "unavailable" &&
                native.status == "unavailable" -> "unavailable"
            statuses.all { it == "valid" } -> "valid"
            else -> "partial"
        }
        val checks = ToolkitIo.json(
            "zip" to ToolkitIo.json("status" to zip.status, "issueCount" to zip.issues.size),
            "manifest" to ToolkitIo.json("status" to manifest.status, "issueCount" to manifest.issues.size),
            "native" to ToolkitIo.json("status" to native.status, "issueCount" to native.issues.size),
            "signature" to ToolkitIo.json("status" to signature.status, "issueCount" to signature.issues.size)
        )
        return ToolkitIo.json(
            "status" to status,
            "statusMeaning" to STATUS_MEANING,
            "file" to ToolkitIo.json("path" to file.absolutePath, "name" to file.name,
                "size" to file.length(), "sha256" to sha),
            "scope" to scope(),
            "checks" to checks,
            "zip" to zip.json,
            "manifest" to manifest.json,
            "native" to native.json,
            "signature" to signature.json,
            "issues" to issueArray(issues, MAX_ISSUES),
            "issueCount" to issues.size,
            "issuesTruncated" to (issues.size > MAX_ISSUES),
            "installability" to "not_evaluated",
            "businessBehavior" to "not_evaluated",
            "limitations" to LIMITATIONS,
            "note" to "静态证据；不证明可安装、可启动或业务行为。安装/启动必须另行显式测试，且不能静默安装被分析 APK。"
        )
    }

    private fun unavailableFile(file: File, detail: String): JsonObject {
        val issues = listOf(issue("file", "FILE_UNREADABLE", "error", detail))
        val section = Section("unavailable", sectionJson("unavailable", issues), issues)
        val zip = failureZip(issues)
        val signature = unavailableSignature(1, Int.MAX_VALUE, null, null, "", issues)
        return buildReport(file, null, zip, section, section, signature)
    }

    private fun scope() = ToolkitIo.json(
        "mode" to "static_read_only",
        "checks" to listOf("zip_alignment", "native_elf_pt_load", "manifest_sdk_abi_split", "apk_signature"),
        "fullApkInMemory" to false,
        "writes" to false,
        "installs" to false,
        "launches" to false,
        "network" to false,
        "signatureBackend" to "com.android.apksig.ApkVerifier",
        "apksigCoordinate" to APKSIG_COORDINATE,
        "zip64" to "explicitly_reported_unsupported",
        "signatureMetadataPolicy" to "仅使用 apksig 密码学结果；ToolkitSignatures 的证书元数据不作为验证通过依据"
    )

    private val LIMITATIONS = listOf(
        "静态只读检查：未安装、未启动、未执行设备兼容性或业务行为测试。",
        "ZIP 对齐只检查数据偏移；不会把 4 KiB ELF 重新链接成 16 KiB ELF。",
        "ELF 只解析程序头 PT_LOAD；不执行 native 代码，不分析符号/重定位。",
        "ABI/split 汇总来自 Manifest 与 lib/<abi>/ 路径；未校验 base APK、同组 split 或设备 ABI。",
        "签名由 apksig 做密码学验证；未提供 V4 sidecar、未验证 source stamp；通过签名不等于可安装或业务正常。",
        "ZIP64 未解析，明确报告为不支持；不会猜测偏移。",
        "未对每个 ZIP 条目重新计算 CRC；内容完整性由签名覆盖（若有签名）。",
        "apksig 8.6.1 为 Java 8 库；旧 Android API 可能缺少其使用的 Java 8 API（如 java.util.Base64/java.util.function），此时报告 unavailable，绝不当作 verified。"
    )

    // ------------------------------------------------------------- ZIP index

    private class ZipSection(val status: String, val json: JsonObject, val issues: List<JsonObject>,
                             val entries: List<ZipEntryInfo>, val usable: Boolean)
    private class Section(val status: String, val json: JsonObject, val issues: List<JsonObject>)
    private class ZipFailure(val code: String, message: String) : Exception(message)
    private data class EndRecord(val offset: Long, val disk: Int, val centralDisk: Int,
                                 val entriesDisk: Int, val entriesTotal: Int, val centralSize: Long,
                                 val centralOffset: Long, val zip64: Boolean)
    private data class ZipEntryInfo(val name: String, val nameBytes: ByteArray, val flags: Int,
                                    val method: Int, val compressedSize: Long, val uncompressedSize: Long,
                                    val localOffset: Long, val directory: Boolean, val zip64: Boolean,
                                    val dataOffset: Long? = null)
    private class AlignmentCounters {
        var stored = 0
        var storedAligned = 0
        var storedMisaligned = 0
        var deflated = 0
        var nativeChecked = 0
        var nativeAligned = 0
        var nativeMisaligned = 0
        var nativeCompressed = 0
    }

    private fun readZip(file: File): ZipSection {
        val issues = ArrayList<JsonObject>()
        return try {
            RandomAccessFile(file, "r").use { raf -> parseZip(raf, issues) }
        } catch (failure: ZipFailure) {
            issues.add(issue("zip", failure.code, "error", failure.message ?: "ZIP 解析失败"))
            failureZip(issues)
        } catch (failure: Exception) {
            issues.add(issue("zip", "ZIP_UNREADABLE", "error", failure.message ?: "ZIP 解析失败"))
            failureZip(issues)
        }
    }

    private fun failureZip(issues: List<JsonObject>): ZipSection {
        val json = sectionJson("unavailable", issues,
            "zip64Unsupported" to false,
            "entryCount" to null,
            "centralDirectoryOffset" to null,
            "centralDirectorySize" to null,
            "eocdOffset" to null,
            "alignment" to alignmentJson(AlignmentCounters()),
            "note" to "ZIP 中央目录不可读，未产生对齐结论")
        return ZipSection("unavailable", json, issues, emptyList(), false)
    }

    private fun parseZip(raf: RandomAccessFile, issues: MutableList<JsonObject>): ZipSection {
        val length = raf.length()
        if (length < 22L) throw ZipFailure("ZIP_END_RECORD_MISSING", "文件小于 ZIP EOCD")
        val eocd = findEndRecord(raf, length)
        if (eocd.zip64) {
            issues.add(issue("zip", "ZIP64_UNSUPPORTED", "error",
                "检测到 ZIP64；本验证器不解析 ZIP64，因此不提供偏移对齐结论",
                "zip64Signals" to true))
            val json = sectionJson("partial", issues,
                "zip64Unsupported" to true,
                "entryCount" to null,
                "centralDirectoryOffset" to null,
                "centralDirectorySize" to null,
                "eocdOffset" to eocd.offset,
                "alignment" to alignmentJson(AlignmentCounters()),
                "note" to "ZIP64 明确未支持；没有猜测中央目录或条目偏移")
            return ZipSection("partial", json, issues, emptyList(), false)
        }
        if (eocd.disk != 0 || eocd.centralDisk != 0) {
            issues.add(issue("zip", "ZIP_MULTI_DISK_UNSUPPORTED", "error", "不支持分卷 ZIP"))
            val json = sectionJson("partial", issues, "zip64Unsupported" to false,
                "entryCount" to null, "alignment" to alignmentJson(AlignmentCounters()),
                "note" to "分卷 ZIP 未解析")
            return ZipSection("partial", json, issues, emptyList(), false)
        }
        if (eocd.entriesDisk != eocd.entriesTotal) {
            issues.add(issue("zip", "ZIP_ENTRY_COUNT_MISMATCH", "warning",
                "EOCD 的磁盘条目数与总条目数不一致", "entriesOnDisk" to eocd.entriesDisk,
                "entriesTotal" to eocd.entriesTotal))
        }
        val centralEnd = safeAdd(eocd.centralOffset, eocd.centralSize)
            ?: throw ZipFailure("ZIP_CENTRAL_BOUNDS", "中央目录偏移/长度溢出")
        if (eocd.centralOffset > length || centralEnd > length) {
            throw ZipFailure("ZIP_CENTRAL_BOUNDS", "中央目录超出文件边界")
        }
        if (centralEnd != eocd.offset) {
            issues.add(issue("zip", "ZIP_CENTRAL_END_MISMATCH", "warning",
                "中央目录结束位置与 EOCD 不一致，可能存在附加数据",
                "centralEnd" to centralEnd, "eocdOffset" to eocd.offset))
        }
        if (eocd.centralSize > MAX_CENTRAL_BYTES) {
            throw ZipFailure("ZIP_CENTRAL_TOO_LARGE", "中央目录超过 64 MiB 预算")
        }
        val central = readAt(raf, eocd.centralOffset, eocd.centralSize.toInt())
        val entries = ArrayList<ZipEntryInfo>()
        val zip64Entry = booleanArrayOf(false)
        var parseFailure: ZipFailure? = null
        try {
            parseCentral(central, entries, issues, zip64Entry)
        } catch (failure: ZipFailure) {
            parseFailure = failure
            issues.add(issue("zip", failure.code, "error", failure.message ?: "中央目录解析失败"))
        }
        val counters = AlignmentCounters()
        val checked = ArrayList<ZipEntryInfo>()
        if (parseFailure == null) {
            for (entry in entries) {
                if (entry.zip64 || entry.localOffset < 0L) {
                    checked.add(entry)
                    continue
                }
                val dataOffset = try {
                    localDataOffset(raf, entry, length, eocd.centralOffset, issues)
                } catch (failure: ZipFailure) {
                    issues.add(issue("zip", failure.code, "error", failure.message ?: "本地文件头无效", entry.name))
                    null
                } catch (failure: Exception) {
                    issues.add(issue("zip", "ZIP_LOCAL_HEADER_INVALID", "error",
                        failure.message ?: "本地文件头无效", entry.name))
                    null
                }
                val withOffset = entry.copy(dataOffset = dataOffset)
                checked.add(withOffset)
                if (dataOffset != null) checkAlignment(withOffset, dataOffset, counters, issues)
            }
            if (entries.size != eocd.entriesTotal && eocd.entriesTotal != 0xffff) {
                issues.add(issue("zip", "ZIP_ENTRY_COUNT_MISMATCH", "warning",
                    "中央目录实际条目数与 EOCD 声明不一致",
                    "parsedEntries" to entries.size, "declaredEntries" to eocd.entriesTotal))
            }
        }
        val status = if (parseFailure != null || hasErrors(issues)) "partial" else "valid"
        val json = sectionJson(status, issues,
            "zip64Unsupported" to zip64Entry[0],
            "entryCount" to entries.size,
            "eocdEntryCount" to eocd.entriesTotal,
            "centralDirectoryOffset" to eocd.centralOffset,
            "centralDirectorySize" to eocd.centralSize,
            "eocdOffset" to eocd.offset,
            "alignment" to alignmentJson(counters),
            "entriesParsed" to checked.size,
            "note" to "偏移均为文件内绝对数据偏移；4B 只要求 Stored 非目录条目，16 KiB 只要求 Stored .so；Deflate 条目保持压缩。")
        return ZipSection(status, json, issues, checked, parseFailure == null && !zip64Entry[0])
    }

    private fun findEndRecord(raf: RandomAccessFile, length: Long): EndRecord {
        val tailLength = minOf(length, MAX_EOCD_BYTES.toLong()).toInt()
        val tail = readAt(raf, length - tailLength, tailLength)
        for (at in tailLength - 22 downTo 0) {
            if (u32(tail, at) != ZIP_EOCD) continue
            val commentLength = u16(tail, at + 20)
            if (at + 22 + commentLength != tailLength) continue
            val disk = u16(tail, at + 4)
            val centralDisk = u16(tail, at + 6)
            val entriesDisk = u16(tail, at + 8)
            val entriesTotal = u16(tail, at + 10)
            val centralSize = u32(tail, at + 12)
            val centralOffset = u32(tail, at + 16)
            val absolute = length - tailLength + at
            val locator = absolute >= 20L && readU32At(raf, absolute - 20L) == ZIP64_LOCATOR
            val sentinel = disk == 0xffff || centralDisk == 0xffff || entriesDisk == 0xffff ||
                entriesTotal == 0xffff || centralSize == 0xffffffffL || centralOffset == 0xffffffffL
            return EndRecord(absolute, disk, centralDisk, entriesDisk, entriesTotal,
                centralSize, centralOffset, locator || sentinel)
        }
        throw ZipFailure("ZIP_END_RECORD_MISSING", "未找到可信 ZIP EOCD")
    }

    private fun parseCentral(bytes: ByteArray, entries: MutableList<ZipEntryInfo>,
                             issues: MutableList<JsonObject>, zip64Entry: BooleanArray) {
        var p = 0
        val names = HashSet<String>()
        while (p < bytes.size) {
            if (bytes.size - p < 46) throw ZipFailure("ZIP_CENTRAL_TRUNCATED", "中央目录头被截断")
            if (u32(bytes, p) != ZIP_CENTRAL) throw ZipFailure("ZIP_CENTRAL_SIGNATURE", "中央目录签名无效")
            val flags = u16(bytes, p + 8)
            val method = u16(bytes, p + 10)
            val compressedRaw = u32(bytes, p + 20)
            val uncompressedRaw = u32(bytes, p + 24)
            val nameLength = u16(bytes, p + 28)
            val extraLength = u16(bytes, p + 30)
            val commentLength = u16(bytes, p + 32)
            val diskStart = u16(bytes, p + 34)
            val localRaw = u32(bytes, p + 42)
            val total = 46L + nameLength + extraLength + commentLength
            if (total > (bytes.size - p).toLong()) throw ZipFailure("ZIP_CENTRAL_TRUNCATED", "中央目录条目越界")
            if (entries.size >= MAX_ENTRIES) throw ZipFailure("ZIP_ENTRY_LIMIT", "ZIP 条目数超过预算")
            val nameBytes = bytes.copyOfRange(p + 46, p + 46 + nameLength)
            val name = String(nameBytes, Charsets.UTF_8)
            if (name.indexOf('\uFFFD') >= 0) {
                issues.add(issue("zip", "ZIP_ENTRY_NAME_ENCODING", "warning",
                    "条目名不是有效 UTF-8，已按替换字符读取", name))
            }
            if (name.isEmpty()) issues.add(issue("zip", "ZIP_ENTRY_NAME_EMPTY", "error", "ZIP 条目名为空"))
            if ((flags and ZIP_FLAG_UTF8) == 0 && name.any { it.code > 0x7f }) {
                issues.add(issue("zip", "ZIP_ENTRY_NAME_UNFLAGGED", "warning",
                    "非 ASCII 条目名未设置 UTF-8 标志；名称/偏移仍按实际字节读取", name))
            }
            if ((flags and ZIP_FLAG_ENCRYPTED) != 0) {
                issues.add(issue("zip", "ZIP_ENTRY_ENCRYPTED", "error",
                    "APK ZIP 条目不应加密；仅报告结构，不尝试解密", name))
            }
            if (method != ZIP_METHOD_STORED && method != ZIP_METHOD_DEFLATED) {
                issues.add(issue("zip", "ZIP_UNSUPPORTED_COMPRESSION", "error",
                    "不支持的 ZIP 压缩方法 $method", name))
            }
            val packed = compressedRaw == 0xffffffffL || uncompressedRaw == 0xffffffffL ||
                localRaw == 0xffffffffL || diskStart == 0xffff
            if (packed) {
                if (!zip64Entry[0]) {
                    issues.add(issue("zip", "ZIP64_UNSUPPORTED", "error",
                        "存在 ZIP64 条目；不解析其 64 位偏移/长度"))
                    zip64Entry[0] = true
                }
            } else if (diskStart != 0) {
                issues.add(issue("zip", "ZIP_MULTI_DISK_ENTRY", "error", "条目位于其他分卷", name))
            }
            if (!names.add(name)) {
                issues.add(issue("zip", "ZIP_DUPLICATE_ENTRY_NAME", "error", "重复 ZIP 条目名", name))
            }
            entries.add(ZipEntryInfo(
                name = name, nameBytes = nameBytes, flags = flags, method = method,
                compressedSize = if (packed) -1L else compressedRaw,
                uncompressedSize = if (packed) -1L else uncompressedRaw,
                localOffset = if (packed) -1L else localRaw,
                directory = name.endsWith("/"), zip64 = packed))
            p += total.toInt()
        }
        if (p != bytes.size) throw ZipFailure("ZIP_CENTRAL_TRAILING", "中央目录存在未解析尾部")
    }

    private fun localDataOffset(raf: RandomAccessFile, entry: ZipEntryInfo, length: Long,
                                centralOffset: Long, issues: MutableList<JsonObject>): Long {
        if (entry.localOffset < 0L || entry.localOffset > length - 30L) {
            throw ZipFailure("ZIP_LOCAL_HEADER_BOUNDS", "本地文件头偏移越界")
        }
        val header = readAt(raf, entry.localOffset, 30)
        if (u32(header, 0) != ZIP_LOCAL) {
            throw ZipFailure("ZIP_LOCAL_HEADER_MISSING", "本地文件头签名无效")
        }
        val localFlags = u16(header, 6)
        val localMethod = u16(header, 8)
        val nameLength = u16(header, 26)
        val extraLength = u16(header, 28)
        val dataOffset = entry.localOffset + 30L + nameLength + extraLength
        if (dataOffset < entry.localOffset || dataOffset > length) {
            throw ZipFailure("ZIP_LOCAL_HEADER_BOUNDS", "本地文件头名称/额外字段越界")
        }
        if ((localFlags and ZIP_FLAG_ENCRYPTED) != 0) {
            issues.add(issue("zip", "ZIP_LOCAL_ENCRYPTED_FLAG", "error",
                "本地文件头声明了加密标志", entry.name))
        }
        if (localMethod != entry.method) {
            issues.add(issue("zip", "ZIP_LOCAL_METHOD_MISMATCH", "error",
                "本地/中央目录压缩方法不一致", entry.name,
                "localMethod" to localMethod, "centralMethod" to entry.method))
        }
        val localNameLength = nameLength.toLong()
        if (localNameLength != entry.nameBytes.size.toLong()) {
            issues.add(issue("zip", "ZIP_LOCAL_NAME_LENGTH_MISMATCH", "error",
                "本地/中央目录条目名长度不一致", entry.name,
                "localNameLength" to nameLength, "centralNameLength" to entry.nameBytes.size))
        } else if (nameLength > 0) {
            val localName = readAt(raf, entry.localOffset + 30L, nameLength)
            if (!localName.contentEquals(entry.nameBytes)) {
                issues.add(issue("zip", "ZIP_LOCAL_NAME_MISMATCH", "error",
                    "本地/中央目录条目名不一致", entry.name))
            }
        }
        if (entry.compressedSize >= 0L) {
            val end = safeAdd(dataOffset, entry.compressedSize)
            if (end == null || end > length) {
                issues.add(issue("zip", "ZIP_ENTRY_DATA_BOUNDS", "error",
                    "条目数据超出文件边界", entry.name,
                    "dataOffset" to dataOffset, "compressedSize" to entry.compressedSize))
            } else if (end > centralOffset) {
                issues.add(issue("zip", "ZIP_ENTRY_OVERLAPS_CENTRAL_DIRECTORY", "error",
                    "条目数据越过中央目录起点", entry.name,
                    "dataEnd" to end, "centralDirectoryOffset" to centralOffset))
            }
        }
        if (entry.method == ZIP_METHOD_STORED && entry.compressedSize >= 0L &&
            entry.uncompressedSize >= 0L && entry.compressedSize != entry.uncompressedSize) {
            issues.add(issue("zip", "ZIP_STORED_SIZE_MISMATCH", "error",
                "Stored 条目压缩/原始长度不一致", entry.name))
        }
        return dataOffset
    }

    private fun checkAlignment(entry: ZipEntryInfo, offset: Long, counters: AlignmentCounters,
                               issues: MutableList<JsonObject>) {
        if (entry.directory) return
        val native = entry.name.endsWith(".so", ignoreCase = true)
        when (entry.method) {
            ZIP_METHOD_STORED -> {
                counters.stored++
                if (offset % ALIGN_STORED == 0L) counters.storedAligned++ else {
                    counters.storedMisaligned++
                    issues.add(issue("zip", "ZIP_STORED_ALIGNMENT_4B", "error",
                        "Stored 非目录条目未按 4 字节对齐", entry.name,
                        "expectedAlignment" to ALIGN_STORED, "actualOffset" to offset,
                        "remainder" to offset % ALIGN_STORED))
                }
                if (native) {
                    counters.nativeChecked++
                    if (offset % ALIGN_NATIVE == 0L) counters.nativeAligned++ else {
                        counters.nativeMisaligned++
                        issues.add(issue("zip", "ZIP_NATIVE_ALIGNMENT_16KB", "error",
                            "Stored .so 未按 16 KiB 对齐", entry.name,
                            "expectedAlignment" to ALIGN_NATIVE, "actualOffset" to offset,
                            "remainder" to offset % ALIGN_NATIVE))
                    }
                }
            }
            ZIP_METHOD_DEFLATED -> {
                counters.deflated++
                if (native) {
                    counters.nativeCompressed++
                    issues.add(issue("zip", "ZIP_NATIVE_LIBRARY_COMPRESSED", "warning",
                        "Native library 是 Deflate；ZIP 16 KiB 对齐不适用（仍检查 ELF PT_LOAD）", entry.name))
                }
            }
            else -> if (native) {
                issues.add(issue("zip", "ZIP_NATIVE_LIBRARY_COMPRESSION_UNSUPPORTED", "error",
                    "Native library 使用不支持的 ZIP 压缩方法", entry.name, "method" to entry.method))
            }
        }
    }

    private fun alignmentJson(c: AlignmentCounters) = ToolkitIo.json(
        "requiredBytes" to ToolkitIo.json("storedNonDirectory" to ALIGN_STORED, "nativeLibrary" to ALIGN_NATIVE),
        "storedEntriesChecked" to c.stored,
        "storedEntriesAligned4Bytes" to c.storedAligned,
        "storedEntriesMisaligned4Bytes" to c.storedMisaligned,
        "deflatedEntryCount" to c.deflated,
        "nativeLibrariesChecked16Kb" to c.nativeChecked,
        "nativeLibrariesAligned16Kb" to c.nativeAligned,
        "nativeLibrariesMisaligned16Kb" to c.nativeMisaligned,
        "nativeLibrariesCompressed" to c.nativeCompressed
    )

    private fun readAt(raf: RandomAccessFile, offset: Long, length: Int): ByteArray {
        if (offset < 0L || length < 0 || offset > raf.length() - length.toLong()) {
            throw ZipFailure("ZIP_READ_BOUNDS", "ZIP 读取越界")
        }
        val bytes = ByteArray(length)
        raf.seek(offset)
        raf.readFully(bytes)
        return bytes
    }

    private fun readU32At(raf: RandomAccessFile, offset: Long): Long = try {
        u32(readAt(raf, offset, 4), 0)
    } catch (_: Exception) { -1L }

    private fun u16(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset > bytes.size - 2) throw ZipFailure("ZIP_BOUNDS", "ZIP 字段越界")
        return (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
    }

    private fun u32(bytes: ByteArray, offset: Int): Long {
        if (offset < 0 || offset > bytes.size - 4) throw ZipFailure("ZIP_BOUNDS", "ZIP 字段越界")
        return (bytes[offset].toLong() and 0xffL) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xffL) shl 24)
    }

    // ---------------------------------------------------------- manifest/ABI

    private class ManifestInfo(
        var present: Boolean = false,
        var binaryXml: Boolean = false,
        var packageName: String? = null,
        var split: String? = null,
        var versionCode: Int? = null,
        var versionName: String? = null,
        var minSdk: Int? = null,
        var targetSdk: Int? = null,
        var maxSdk: Int? = null,
        var previewMinSdk: String? = null,
        var abis: List<String> = emptyList(),
        var extractNativeLibs: Boolean? = null,
        var compileSdkVersion: String? = null
    )

    private fun inspectManifest(file: File, zip: ZipSection, minSdk: Int?, maxSdk: Int?): Section {
        val issues = ArrayList<JsonObject>()
        val abis = observedAbis(zip)
        val info = ManifestInfo(abis = abis)
        if (!zip.usable) {
            issues.add(issue("manifest", "MANIFEST_CHECK_UNAVAILABLE", "error",
                "ZIP 索引不可用，未读取 Manifest"))
            return manifestSection(info, issues, minSdk, maxSdk, "unavailable")
        }
        val manifests = zip.entries.filter { it.name == "AndroidManifest.xml" && !it.directory }
        if (manifests.isEmpty()) {
            issues.add(issue("manifest", "MANIFEST_MISSING", "error", "APK 缺少 AndroidManifest.xml"))
            return manifestSection(info, issues, minSdk, maxSdk)
        }
        info.present = true
        if (manifests.size > 1) {
            issues.add(issue("manifest", "MANIFEST_DUPLICATE", "error",
                "存在多个 AndroidManifest.xml 条目", "count" to manifests.size))
        }
        try {
            val bytes = ZipFile(file).use { source ->
                val entry = source.getEntry("AndroidManifest.xml")
                    ?: throw IOException("AndroidManifest.xml 条目不可读")
                if (entry.size > ToolkitIo.MAX_ENTRY.toLong()) throw IOException("Manifest 超过 64 MiB 预算")
                ToolkitIo.read(source.getInputStream(entry), ToolkitIo.MAX_ENTRY)
            }
            info.binaryXml = ToolkitResources.isBinaryXml(bytes)
            val text = if (info.binaryXml) ToolkitResources.decodeXml(bytes, null) else ToolkitIo.text(bytes)
            if (text.length > MAX_MANIFEST_TEXT) throw IllegalArgumentException("Manifest 文本超过 4 MiB 预算")
            ToolkitResources.safeXml(text)
            val root = XMLDocument.load(text).documentElement
                ?: throw IllegalArgumentException("Manifest 缺少根元素")
            if (root.name != "manifest") {
                issues.add(issue("manifest", "MANIFEST_ROOT_INVALID", "error",
                    "Manifest 根元素不是 manifest", "root" to root.name))
            }
            info.packageName = attribute(root, "package")?.takeIf { it.isNotBlank() }
            if (info.packageName == null) {
                issues.add(issue("manifest", "MANIFEST_PACKAGE_MISSING", "error", "Manifest 缺少 package"))
            }
            info.split = attribute(root, "split")?.takeIf { it.isNotBlank() }
            info.versionCode = parseInt(attribute(root, "versionCode"))
            info.versionName = attribute(root, "versionName")?.takeIf { it.isNotBlank() }
            info.compileSdkVersion = attribute(root, "compileSdkVersion")?.takeIf { it.isNotBlank() }
            val usesSdk = root.childElementList.firstOrNull { it.name == "uses-sdk" }
            val minRaw = usesSdk?.let { attribute(it, "minSdkVersion") }
            val targetRaw = usesSdk?.let { attribute(it, "targetSdkVersion") }
            val maxRaw = usesSdk?.let { attribute(it, "maxSdkVersion") }
            info.minSdk = parseInt(minRaw)
            info.targetSdk = parseInt(targetRaw)
            info.maxSdk = parseInt(maxRaw)
            info.previewMinSdk = minRaw?.takeIf { info.minSdk == null && it.isNotBlank() }
            if (minRaw == null) {
                issues.add(issue("manifest", "MANIFEST_USES_SDK_MISSING", "warning",
                    "Manifest 未声明 minSdkVersion"))
            } else if (info.minSdk == null) {
                issues.add(issue("manifest", "MANIFEST_MIN_SDK_UNPARSEABLE", "warning",
                    "minSdkVersion 不是整数（可能是预览代号）", "value" to minRaw))
            }
            val application = root.childElementList.firstOrNull { it.name == "application" }
            info.extractNativeLibs = parseBoolean(application?.let { attribute(it, "extractNativeLibs") })
        } catch (failure: Exception) {
            issues.add(issue("manifest", "MANIFEST_UNREADABLE", "error",
                failure.message ?: "Manifest 无法解析"))
        }
        return manifestSection(info, issues, minSdk, maxSdk)
    }

    private fun manifestSection(info: ManifestInfo, issues: List<JsonObject>,
                                requestedMin: Int?, requestedMax: Int?,
                                forcedStatus: String? = null): Section {
        val splitType = when {
            info.split == null -> "base"
            info.split!!.startsWith("config.") -> "config"
            info.split!!.startsWith("feature.") -> "feature"
            else -> "unknown"
        }
        val range = sdkRange(info.minSdk, info.maxSdk, requestedMin, requestedMax)
        val compatibilityIssues = ArrayList(issues)
        if (range == "incompatible") {
            compatibilityIssues.add(issue("manifest", "MANIFEST_SDK_RANGE_INCOMPATIBLE", "error",
                "Manifest 声明的 SDK 范围与请求范围不相交；不等同于安装测试结论",
                "declaredMinSdk" to info.minSdk, "declaredMaxSdk" to info.maxSdk,
                "requestedMinSdk" to requestedMin, "requestedMaxSdk" to requestedMax))
        }
        val compatibility = ToolkitIo.json(
            "requestedMinSdk" to requestedMin,
            "requestedMaxSdk" to requestedMax,
            "sdkRange" to range,
            "sdkRangeNote" to "range_overlap 只表示声明范围与请求范围有交集，不表示设备可安装或可运行。",
            "abiSelection" to "not_evaluated",
            "observedAbis" to info.abis,
            "abiNote" to "未提供设备 ABI；仅列出 lib/<abi>/ 路径。",
            "split" to (info.split ?: "base"),
            "splitCompatibility" to "not_evaluated",
            "splitNote" to "未校验 base APK、同组 split、版本码或签名一致性。",
            "installability" to "not_evaluated"
        )
        val status = forcedStatus ?: statusOfIssues(compatibilityIssues)
        val json = sectionJson(status, compatibilityIssues,
            "present" to info.present,
            "binaryXml" to info.binaryXml,
            "package" to info.packageName,
            "split" to info.split,
            "splitName" to info.split,
            "isSplit" to (info.split != null),
            "splitType" to splitType,
            "versionCode" to info.versionCode,
            "versionName" to info.versionName,
            "minSdk" to info.minSdk,
            "targetSdk" to info.targetSdk,
            "maxSdk" to info.maxSdk,
            "effectiveTargetSdk" to (info.targetSdk ?: info.minSdk),
            "previewMinSdk" to info.previewMinSdk,
            "abis" to info.abis,
            "extractNativeLibs" to info.extractNativeLibs,
            "compileSdkVersion" to info.compileSdkVersion,
            "compatibility" to compatibility
        )
        return Section(status, json, compatibilityIssues)
    }

    private fun observedAbis(zip: ZipSection): List<String> =
        zip.entries.asSequence().mapNotNull { entry ->
            if (entry.directory || !entry.name.startsWith("lib/")) return@mapNotNull null
            val rest = entry.name.substringAfter("lib/")
            val slash = rest.indexOf('/')
            if (slash <= 0) null else rest.substring(0, slash)
        }.distinct().sorted().toList()

    private fun attribute(element: XMLElement, localName: String): String? {
        val attributes = element.attributes
        while (attributes.hasNext()) {
            val attribute = attributes.next()
            val name = attribute.name
            if (name == localName || name.substringAfter(':') == localName) return attribute.valueAsString
        }
        return null
    }

    private fun parseInt(value: String?): Int? {
        if (value == null) return null
        val text = value.trim()
        return when {
            text.matches(Regex("\\d+")) -> text.toIntOrNull()
            text.startsWith("0x", ignoreCase = true) -> text.substring(2).toIntOrNull(16)
            else -> null
        }
    }

    private fun parseBoolean(value: String?): Boolean? = when (value?.trim()?.lowercase(Locale.ROOT)) {
        "true" -> true
        "false" -> false
        else -> null
    }

    private fun sdkRange(declaredMin: Int?, declaredMax: Int?, requestedMin: Int?, requestedMax: Int?): String {
        if (declaredMin == null && declaredMax == null) return "unknown"
        if (requestedMin == null && requestedMax == null) return "declared_range_only"
        if (requestedMax != null && declaredMin != null && declaredMin > requestedMax) return "incompatible"
        if (requestedMin != null && declaredMax != null && declaredMax < requestedMin) return "incompatible"
        return "range_overlap"
    }

    // ------------------------------------------------------------- native ELF

    private class ElfFailure(val code: String, message: String) : Exception(message)
    private class ElfSegment(val index: Int, val flags: Long, val fileOffset: Long, val virtualAddress: Long,
                             val fileSize: Long, val memorySize: Long, val alignment: Long,
                             val alignment16Kb: Boolean, val alignment4Kb: Boolean,
                             val offsetVaddrCongruent16Kb: Boolean, val offsetVaddrCongruent4Kb: Boolean)
    private class ElfInspection(val bits: Int, val little: Boolean, val machine: Int, val abi: String,
                                val type: Long, val totalLoadSegments: Int, val all16Kb: Boolean,
                                val segments: List<ElfSegment>, val status: String, val issues: List<JsonObject>)

    private fun inspectNative(file: File, zip: ZipSection): Section {
        val issues = ArrayList<JsonObject>()
        if (!zip.usable) {
            issues.add(issue("native", "NATIVE_CHECK_UNAVAILABLE", "error",
                "ZIP 索引不可用，未检查 ELF PT_LOAD"))
            return Section("unavailable", sectionJson("unavailable", issues,
                "applicable" to true, "pageSizeBytes" to ALIGN_NATIVE,
                "libraries" to emptyList<Any>()), issues)
        }
        val libraries = zip.entries.filter { !it.directory && it.name.endsWith(".so", ignoreCase = true) }
        if (libraries.isEmpty()) {
            return Section("valid", sectionJson("valid", emptyList(),
                "applicable" to false, "pageSizeBytes" to ALIGN_NATIVE,
                "libraries" to emptyList<Any>(),
                "note" to "未发现 .so 条目；ELF 检查不适用"), emptyList())
        }
        val items = JsonArray()
        var readBytes = 0L
        var checked = 0
        var totalSegments = 0
        var failedLibraries = 0
        try {
            ZipFile(file).use { source ->
                for (entry in libraries) {
                    if (checked >= MAX_NATIVE_LIBS) {
                        issues.add(issue("native", "NATIVE_LIBRARY_LIMIT", "error",
                            "Native library 数量超过 $MAX_NATIVE_LIBS 预算"))
                        break
                    }
                    checked++
                    val zipEntry = source.getEntry(entry.name)
                    if (zipEntry == null) {
                        issues.add(issue("native", "NATIVE_LIBRARY_MISSING", "error",
                            "ZIP 中找不到 .so 条目", entry.name))
                        failedLibraries++
                        continue
                    }
                    if (zipEntry.size > ToolkitIo.MAX_ENTRY.toLong()) {
                        issues.add(issue("native", "NATIVE_LIBRARY_TOO_LARGE", "error",
                            "Native library 超过 64 MiB 单项预算", entry.name,
                            "size" to zipEntry.size))
                        failedLibraries++
                        continue
                    }
                    val remaining = MAX_NATIVE_TOTAL_BYTES - readBytes
                    if (remaining <= 0L) {
                        issues.add(issue("native", "NATIVE_LIBRARY_BUDGET", "error",
                            "Native library 读取总量超过 256 MiB 预算"))
                        failedLibraries++
                        break
                    }
                    val bytes = try {
                        ToolkitIo.read(source.getInputStream(zipEntry),
                            minOf(ToolkitIo.MAX_ENTRY.toLong(), remaining).toInt())
                    } catch (failure: Exception) {
                        issues.add(issue("native", "NATIVE_LIBRARY_READ_FAILED", "error",
                            failure.message ?: "Native library 读取失败", entry.name))
                        failedLibraries++
                        continue
                    }
                    readBytes += bytes.size
                    val abiDir = Regex("^lib/([^/]+)/.+\\.so$", RegexOption.IGNORE_CASE)
                        .find(entry.name)?.groupValues?.get(1)
                    if(ToolkitGadget.isConfiguration(entry.name,bytes)) {
                        items.add(ToolkitIo.json("path" to entry.name,"kind" to "frida-gadget-configuration","abiDirectory" to abiDir,"elfApplicable" to false,"status" to "valid"))
                        continue
                    }
                    val elf = try {
                        parseElf(bytes, entry.name)
                    } catch (failure: ElfFailure) {
                        issues.add(issue("native", failure.code, "error",
                            failure.message ?: "ELF 解析失败", entry.name))
                        failedLibraries++
                        continue
                    } catch (failure: Exception) {
                        issues.add(issue("native", "ELF_UNREADABLE", "error",
                            failure.message ?: "ELF 解析失败", entry.name))
                        failedLibraries++
                        continue
                    }
                    issues.addAll(elf.issues)
                    val abiMatches = abiMatches(abiDir, elf.abi)
                    if (!abiMatches) {
                        issues.add(issue("native", "ELF_ABI_PATH_MISMATCH", "error",
                            "lib/<abi>/ 路径与 ELF machine 不一致", entry.name,
                            "abiDirectory" to abiDir, "elfAbi" to elf.abi,
                            "machine" to elf.machine, "bits" to elf.bits))
                    }
                    if (!abiMatches || elf.status != "valid") failedLibraries++
                    totalSegments += elf.totalLoadSegments
                    items.add(ToolkitIo.json(
                        "path" to entry.name,
                        "zip" to ToolkitIo.json(
                            "method" to entry.method,
                            "compressed" to (entry.method == ZIP_METHOD_DEFLATED),
                            "dataOffset" to entry.dataOffset,
                            "alignment16KbApplicable" to (entry.method == ZIP_METHOD_STORED),
                            "alignment16Kb" to if (entry.method == ZIP_METHOD_STORED) {
                                entry.dataOffset?.let { it % ALIGN_NATIVE == 0L }
                            } else null,
                            "note" to if (entry.method == ZIP_METHOD_DEFLATED)
                                "Deflate .so 不满足 ZIP 16 KiB 直接映射条件；本项只检查 ELF 自身" else null
                        ),
                        "abiDirectory" to abiDir,
                        "abiMatchesElf" to abiMatches,
                        "elf" to ToolkitIo.json(
                            "status" to elf.status,
                            "bits" to elf.bits,
                            "endianness" to if (elf.little) "little" else "big",
                            "machine" to elf.machine,
                            "abi" to elf.abi,
                            "type" to elf.type,
                            "ptLoadCount" to elf.totalLoadSegments,
                            "ptLoadSegments" to elf.segments.take(MAX_REPORTED_ELF_SEGMENTS).map {
                                ToolkitIo.json("index" to it.index, "flags" to it.flags,
                                    "fileOffset" to it.fileOffset, "virtualAddress" to it.virtualAddress,
                                    "fileSize" to it.fileSize, "memorySize" to it.memorySize,
                                    "alignment" to it.alignment,
                                    "alignment16KbCompatible" to it.alignment16Kb,
                                    "alignment4KbCompatible" to it.alignment4Kb,
                                    "offsetVaddrCongruent16Kb" to it.offsetVaddrCongruent16Kb,
                                    "offsetVaddrCongruent4Kb" to it.offsetVaddrCongruent4Kb)
                            },
                            "segmentsReported" to minOf(elf.segments.size, MAX_REPORTED_ELF_SEGMENTS),
                            "segmentsTruncated" to (elf.segments.size > MAX_REPORTED_ELF_SEGMENTS),
                            "allPtLoad16KbCompatible" to elf.all16Kb,
                            "issues" to issueArray(elf.issues, MAX_ISSUES_PER_CHECK)
                        )
                    ))
                }
            }
        } catch (failure: Exception) {
            issues.add(issue("native", "NATIVE_ZIP_READ_FAILED", "error",
                failure.message ?: "读取 ZIP 中的 native library 失败"))
        }
        val status = statusOfIssues(issues)
        val json = sectionJson(status, issues,
            "applicable" to true,
            "pageSizeBytes" to ALIGN_NATIVE,
            "libraries" to items,
            "summary" to ToolkitIo.json(
                "librariesObserved" to libraries.size,
                "librariesChecked" to checked,
                "librariesFailed" to failedLibraries,
                "ptLoadSegments" to totalSegments,
                "bytesRead" to readBytes
            ),
            "note" to "只解析 ELF PT_LOAD；ZIP 16 KiB 对齐与 ELF p_align 分开报告，归档填充不会改变 ELF。"
        )
        return Section(status, json, issues)
    }

    private fun parseElf(bytes: ByteArray, path: String): ElfInspection {
        val issues = ArrayList<JsonObject>()
        if (bytes.size < 52) throw ElfFailure("ELF_TOO_SHORT", "ELF 文件过短")
        if (bytes[0] != 0x7f.toByte() || bytes[1] != 'E'.code.toByte() ||
            bytes[2] != 'L'.code.toByte() || bytes[3] != 'F'.code.toByte()) {
            throw ElfFailure("ELF_MAGIC_INVALID", "不是 ELF 文件")
        }
        val bits = bytes[4].toInt() and 0xff
        val endian = bytes[5].toInt() and 0xff
        if (bits != 1 && bits != 2) throw ElfFailure("ELF_CLASS_INVALID", "ELF 位数无效")
        if (endian != 1 && endian != 2) throw ElfFailure("ELF_ENDIAN_INVALID", "ELF 字节序无效")
        val wide = bits == 2
        val little = endian == 1
        val buffer = ByteBuffer.wrap(bytes).order(if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        fun range(offset: Int, size: Int) {
            if (offset < 0 || size < 0 || offset > bytes.size - size) {
                throw ElfFailure("ELF_BOUNDS", "ELF 头部或程序头越界")
            }
        }
        fun u16(offset: Int): Int {
            range(offset, 2)
            return buffer.getShort(offset).toInt() and 0xffff
        }
        fun u32(offset: Int): Long {
            range(offset, 4)
            return buffer.getInt(offset).toLong() and 0xffffffffL
        }
        fun u64(offset: Int): Long {
            range(offset, 8)
            return buffer.getLong(offset)
        }
        val type = u16(16).toLong()
        val machine = u16(18)
        val phoff = if (wide) u64(32) else u32(28)
        val phentsize = u16(if (wide) 54 else 42)
        val phnum = u16(if (wide) 56 else 44)
        if (phoff < 0L) throw ElfFailure("ELF_PHOFF_INVALID", "ELF 程序头偏移为负")
        if (phnum > MAX_ELF_SEGMENTS) throw ElfFailure("ELF_PH_LIMIT", "ELF 程序头数量超过预算")
        if (phnum == 0) {
            issues.add(issue("native", "ELF_NO_PT_LOAD", "error", "ELF 没有程序头/PT_LOAD", path))
            return ElfInspection(bits * 32, little, machine, abiOf(machine, bits * 32), type,
                0, false, emptyList(), statusOfIssues(issues), issues)
        }
        val minimum = if (wide) 56 else 32
        if (phentsize < minimum) throw ElfFailure("ELF_PHENTSIZE_INVALID", "ELF 程序头大小无效")
        val tableSize = phentsize.toLong() * phnum.toLong()
        val tableEnd = safeAdd(phoff, tableSize) ?: throw ElfFailure("ELF_BOUNDS", "ELF 程序头表溢出")
        if (tableEnd > bytes.size.toLong()) throw ElfFailure("ELF_BOUNDS", "ELF 程序头表越界")
        val segments = ArrayList<ElfSegment>()
        var total = 0
        var all16 = true
        for (index in 0 until phnum) {
            val p = (phoff + index.toLong() * phentsize).toInt()
            val pType = u32(p)
            val flags: Long
            val offset: Long
            val vaddr: Long
            val filesz: Long
            val memsz: Long
            val align: Long
            if (wide) {
                flags = u32(p + 4)
                offset = u64(p + 8)
                vaddr = u64(p + 16)
                filesz = u64(p + 32)
                memsz = u64(p + 40)
                align = u64(p + 48)
            } else {
                offset = u32(p + 4)
                vaddr = u32(p + 8)
                filesz = u32(p + 16)
                memsz = u32(p + 20)
                flags = u32(p + 24)
                align = u32(p + 28)
            }
            if (pType != 1L) continue
            total++
            if (offset < 0L || filesz < 0L || memsz < 0L || align < 0L) {
                issues.add(issue("native", "ELF_PT_LOAD_NEGATIVE", "error",
                    "PT_LOAD 偏移/长度/对齐为负", path, "segmentIndex" to index))
                all16 = false
                continue
            }
            if (memsz < filesz) {
                issues.add(issue("native", "ELF_PT_LOAD_MEMORY_SIZE", "error",
                    "PT_LOAD p_memsz < p_filesz", path, "segmentIndex" to index))
                all16 = false
            }
            val end = safeAdd(offset, filesz)
            if (end == null || end > bytes.size.toLong()) {
                issues.add(issue("native", "ELF_PT_LOAD_BOUNDS", "error",
                    "PT_LOAD 文件范围越界", path, "segmentIndex" to index))
                all16 = false
            }
            val powerOfTwo = align == 0L || align == 1L || (align and (align - 1L)) == 0L
            val alignment16 = powerOfTwo && align >= PAGE_16K && align % PAGE_16K == 0L
            val alignment4 = powerOfTwo && align >= PAGE_4K && align % PAGE_4K == 0L
            val congruent16 = (offset % PAGE_16K) == (vaddr and (PAGE_16K - 1L))
            val congruent4 = (offset % PAGE_4K) == (vaddr and (PAGE_4K - 1L))
            val ok16 = alignment16 && congruent16
            if (!ok16) {
                all16 = false
                issues.add(issue("native", "ELF_PT_LOAD_ALIGNMENT_16K", "error",
                    "PT_LOAD 不满足 16 KiB 页兼容（p_align/p_offset/p_vaddr）", path,
                    "segmentIndex" to index, "pAlign" to align, "fileOffset" to offset,
                    "virtualAddress" to hex(vaddr), "offsetVaddrCongruent16Kb" to congruent16,
                    "expectedAlignmentBytes" to PAGE_16K))
            }
            if (segments.size < MAX_REPORTED_ELF_SEGMENTS) {
                segments.add(ElfSegment(index, flags, offset, vaddr, filesz, memsz, align,
                    alignment16, alignment4, congruent16, congruent4))
            }
        }
        if (total == 0) {
            issues.add(issue("native", "ELF_NO_PT_LOAD", "error", "ELF 没有 PT_LOAD", path))
            all16 = false
        }
        val status = statusOfIssues(issues)
        return ElfInspection(bits * 32, little, machine, abiOf(machine, bits * 32), type,
            total, all16, segments, status, issues)
    }

    private fun abiOf(machine: Int, bits: Int): String = when (machine) {
        183 -> "arm64-v8a"
        40 -> if (bits == 64) "arm64-v8a" else "armeabi-v7a"
        3 -> "x86"
        62 -> "x86_64"
        8, 10 -> if (bits == 64) "mips64" else "mips"
        243 -> if (bits == 64) "riscv64" else "riscv32"
        else -> "machine-$machine"
    }

    private fun abiMatches(directory: String?, elfAbi: String): Boolean {
        if (directory == null) return true
        if (directory == elfAbi) return true
        return elfAbi == "armeabi-v7a" && directory == "armeabi"
    }

    private fun hex(value: Long): String = "0x" + java.lang.Long.toHexString(value)

    // -------------------------------------------------------------- signature

    private fun inspectSignature(file: File, minSdk: Int?, maxSdk: Int?, apkSha256: String): Section {
        val actualMin = minSdk ?: 1
        val actualMax = maxSdk ?: Int.MAX_VALUE
        val issues = ArrayList<JsonObject>()
        return try {
            val builder = ApkVerifier.Builder(file)
            if (minSdk != null) builder.setMinCheckedPlatformVersion(minSdk)
            if (maxSdk != null) builder.setMaxCheckedPlatformVersion(maxSdk)
            val result = builder.build().verify()
            signatureResult(result, actualMin, actualMax, minSdk, maxSdk, apkSha256)
        } catch (failure: LinkageError) {
            issues.add(issue("signature", "SIGNATURE_VERIFIER_UNAVAILABLE", "error",
                "apksig 后端不可用：${failure.message ?: failure.javaClass.name}"))
            unavailableSignature(actualMin, actualMax, minSdk, maxSdk, apkSha256, issues)
        } catch (failure: Exception) {
            issues.add(issue("signature", "SIGNATURE_VERIFICATION_ERROR", "error",
                failure.message ?: "apksig 无法完成验证"))
            unavailableSignature(actualMin, actualMax, minSdk, maxSdk, apkSha256, issues)
        }
    }

    private fun signatureResult(result: ApkVerifier.Result, actualMin: Int, actualMax: Int,
                                requestedMin: Int?, requestedMax: Int?, apkSha256: String): Section {
        val issues = ArrayList<JsonObject>()
        val certificates = LinkedHashMap<String, X509Certificate>()
        val certificateSchemes = LinkedHashMap<String, LinkedHashSet<String>>()
        fun addCertificate(certificate: X509Certificate?, scheme: String) {
            if (certificate == null) return
            val hash = digest("SHA-256", certificate.encoded)
            if (!certificates.containsKey(hash)) certificates[hash] = certificate
            certificateSchemes.getOrPut(hash) { linkedSetOf() }.add(scheme)
        }
        result.getV1SchemeSigners().forEach { signer ->
            signer.getCertificateChain()?.forEach { addCertificate(it, "V1") }
        }
        result.getV2SchemeSigners().forEach { signer ->
            signer.getCertificates()?.forEach { addCertificate(it, "V2") }
        }
        result.getV3SchemeSigners().forEach { signer ->
            signer.getCertificates()?.forEach { addCertificate(it, "V3") }
        }
        result.getV31SchemeSigners().forEach { signer ->
            signer.getCertificates()?.forEach { addCertificate(it, "V3.1") }
        }
        result.getV4SchemeSigners().forEach { signer ->
            signer.getCertificates()?.forEach { addCertificate(it, "V4") }
        }
        result.getSignerCertificates().forEach { certificate ->
            val hash = digest("SHA-256", certificate.encoded)
            if (!certificates.containsKey(hash)) certificates[hash] = certificate
            certificateSchemes.getOrPut(hash) { linkedSetOf() }
        }
        val certificateJson = certificates.map { (hash, certificate) ->
            ToolkitIo.json(
                "sha256" to hash,
                "sha1" to digest("SHA-1", certificate.encoded),
                "md5" to digest("MD5", certificate.encoded),
                "schemes" to certificateSchemes[hash].orEmpty().toList(),
                "subject" to certificate.getSubjectX500Principal().name,
                "issuer" to certificate.getIssuerX500Principal().name,
                "serialNumberHex" to certificate.getSerialNumber().toString(16),
                "notBeforeEpochMillis" to certificate.getNotBefore().time,
                "notAfterEpochMillis" to certificate.getNotAfter().time,
                "signatureAlgorithm" to certificate.getSigAlgName(),
                "publicKeyAlgorithm" to certificate.getPublicKey().algorithm,
                "encodedSizeBytes" to certificate.encoded.size
            )
        }
        val errors = result.getAllErrors()
        val warnings = result.getWarnings()
        val schemes = ToolkitIo.json(
            "V1" to schemeJson(result.isVerifiedUsingV1Scheme(), result.getV1SchemeSigners().size,
                "V1 证书/JAR 签名"),
            "V2" to schemeJson(result.isVerifiedUsingV2Scheme(), result.getV2SchemeSigners().size,
                "APK Signature Scheme v2"),
            "V3" to schemeJson(result.isVerifiedUsingV3Scheme(), result.getV3SchemeSigners().size,
                "APK Signature Scheme v3"),
            "V3.1" to schemeJson(result.isVerifiedUsingV31Scheme(), result.getV31SchemeSigners().size,
                "APK Signature Scheme v3.1（rotation）"),
            "V4" to ToolkitIo.json("verified" to result.isVerifiedUsingV4Scheme(),
                "signerCount" to result.getV4SchemeSigners().size, "note" to "未提供 V4 sidecar")
        )
        val verified = result.isVerified()
        if (!verified) {
            issues.add(issue("signature", "SIGNATURE_NOT_VERIFIED", "error",
                "apksig 未通过密码学验证",
                "errorCount" to errors.size, "warningCount" to warnings.size))
        }
        val status = if (verified) "valid" else "partial"
        val json = sectionJson(status, issues,
            "verified" to verified,
            "signaturePresent" to (certificates.isNotEmpty() || verified),
            "schemes" to schemes,
            "certificates" to certificateJson,
            "errors" to apksigIssues(errors),
            "errorsTruncated" to (errors.size > MAX_APKSIG_ISSUES),
            "warnings" to apksigIssues(warnings),
            "warningsTruncated" to (warnings.size > MAX_APKSIG_ISSUES),
            "checkedPlatform" to ToolkitIo.json("minSdk" to actualMin, "maxSdk" to actualMax,
                "minSdkFromArgument" to (requestedMin != null), "maxSdkFromArgument" to (requestedMax != null)),
            "apkSha256" to apkSha256,
            "apksigCoordinate" to APKSIG_COORDINATE,
            "coverage" to "apksig V1/V2/V3/V3.1/V4 密码学验证；V4 sidecar/source stamp 未提供",
            "notChecked" to listOf("V4 sidecar", "source stamp", "installability", "business behavior")
        )
        return Section(status, json, issues)
    }

    private fun schemeJson(verified: Boolean, signerCount: Int, note: String) = ToolkitIo.json(
        "verified" to verified, "signerCount" to signerCount, "note" to note
    )

    private fun unavailableSignature(actualMin: Int, actualMax: Int, requestedMin: Int?,
                                     requestedMax: Int?, apkSha256: String,
                                     issues: List<JsonObject>): Section {
        val schemes = ToolkitIo.json(
            "V1" to schemeJson(false, 0, "后端不可用"),
            "V2" to schemeJson(false, 0, "后端不可用"),
            "V3" to schemeJson(false, 0, "后端不可用"),
            "V3.1" to schemeJson(false, 0, "后端不可用"),
            "V4" to schemeJson(false, 0, "后端不可用/未提供 sidecar")
        )
        val json = sectionJson("unavailable", issues,
            "verified" to false,
            "signaturePresent" to null,
            "schemes" to schemes,
            "certificates" to emptyList<Any>(),
            "errors" to emptyList<Any>(),
            "warnings" to emptyList<Any>(),
            "checkedPlatform" to ToolkitIo.json("minSdk" to actualMin, "maxSdk" to actualMax,
                "minSdkFromArgument" to (requestedMin != null), "maxSdkFromArgument" to (requestedMax != null)),
            "apkSha256" to apkSha256,
            "apksigCoordinate" to APKSIG_COORDINATE,
            "coverage" to "apksig 后端不可用；未读取证书元数据，未声称任何验证结果",
            "notChecked" to listOf("V1/V2/V3/V3.1/V4 signature", "installability", "business behavior")
        )
        return Section("unavailable", json, issues)
    }

    private fun apksigIssues(values: List<ApkVerifier.IssueWithParams>): JsonArray {
        val array = JsonArray()
        values.take(MAX_APKSIG_ISSUES).forEach { issueValue ->
            array.add(ToolkitIo.json(
                "code" to issueValue.getIssue().name,
                "message" to issueValue.toString(),
                "params" to issueValue.getParams().map { it?.toString() }
            ))
        }
        return array
    }

    // ---------------------------------------------------------------- helpers

    private fun issue(check: String, code: String, severity: String, message: String,
                      entry: String?, vararg details: Pair<String, Any?>): JsonObject {
        val values = arrayListOf<Pair<String, Any?>>(
            "check" to check, "code" to code, "severity" to severity,
            "message" to message, "entry" to entry)
        details.forEach { values.add(it) }
        return ToolkitIo.json(*values.toTypedArray())
    }

    private fun issue(check: String, code: String, severity: String, message: String,
                      vararg details: Pair<String, Any?>): JsonObject =
        issue(check, code, severity, message, null, *details)

    private fun sectionJson(status: String, issues: List<JsonObject>,
                            vararg extra: Pair<String, Any?>): JsonObject {
        val values = arrayListOf<Pair<String, Any?>>(
            "status" to status,
            "issueCount" to issues.size,
            "issues" to issueArray(issues, MAX_ISSUES_PER_CHECK),
            "issuesTruncated" to (issues.size > MAX_ISSUES_PER_CHECK)
        )
        extra.forEach { values.add(it) }
        return ToolkitIo.json(*values.toTypedArray())
    }

    private fun issueArray(issues: List<JsonObject>, limit: Int): JsonArray {
        val array = JsonArray()
        issues.take(limit).forEach { array.add(it) }
        return array
    }

    private fun hasErrors(issues: List<JsonObject>): Boolean =
        issues.any { it.get("severity")?.asString == "error" }

    private fun statusOfIssues(issues: List<JsonObject>): String =
        if (hasErrors(issues)) "partial" else "valid"

    private fun safeAdd(left: Long, right: Long): Long? {
        if (left < 0L || right < 0L || left > Long.MAX_VALUE - right) return null
        return left + right
    }

    private fun digest(algorithm: String, bytes: ByteArray): String =
        MessageDigest.getInstance(algorithm).digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
