package com.jdkshen.aijspro.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.ArrayDeque
import java.util.Locale

/**
 * 文件发现（P0-B）：不依赖扩展名，用 ZIP 魔数 + APK 内部结构确认候选。
 *
 * 设计要点：
 * - 扩展名只是加分项：`.apk` / `.apk.N` 提高置信度，但 `影安加固-测试demo.apk.1`、`.bak`、
 *   无扩展名、临时缓存名同样能命中；判定主体是 ZIP 本地文件头/EOCD/中央目录里的
 *   `AndroidManifest.xml`（可选 `resources.arsc`、`classes*.dex`）。
 * - 只读文件头、EOCD 与中央目录，单文件有字节预算，绝不把整文件读进内存。
 * - 扫描有范围/深度/条目/探测数/字节/时间预算，结果显式给出 truncated/skipped/stopReason；
 *   部分结果不能当成「全部未找到」。
 * - 纯 JVM 实现（反射探测符号链接），无 Android 依赖，便于单元测试。
 */
internal object ToolkitDiscovery {
    /** 单文件探测预算：文件头 + EOCD + 中央目录，不整文件读取。 */
    const val MAX_PROBE_BYTES = 2L * 1024 * 1024

    private const val EOCD_MIN = 22
    private const val EOCD_MAX_COMMENT = 65535
    private const val EOCD_SCAN = EOCD_MIN + EOCD_MAX_COMMENT
    private const val MAX_CD_ENTRIES_PER_FILE = 200000
    private const val MAX_ENTRY_NAME_BYTES = 8192
    private const val EOCD_SIG = 0x06054b50L
    private const val ZIP64_EOCD_SIG = 0x06064b50L
    private const val ZIP64_LOCATOR_SIG = 0x07064b50L
    private const val CD_SIG = 0x02014b50L
    private const val MAX_SKIPPED_SAMPLES = 20

    data class Limits(
        val maxDepth: Int = 8,
        val maxEntries: Int = 20000,
        val maxProbes: Int = 2000,
        val maxBytes: Long = 64L * 1024 * 1024,
        val maxMillis: Long = 5000L,
        val limit: Int = 50,
    ) {
        fun normalized(): Limits = Limits(
            maxDepth = maxDepth.coerceIn(0, 32),
            maxEntries = maxEntries.coerceIn(1, 200000),
            maxProbes = maxProbes.coerceIn(1, 20000),
            maxBytes = maxBytes.coerceIn(64L, 512L * 1024 * 1024),
            maxMillis = maxMillis.coerceIn(50L, 30000L),
            limit = limit.coerceIn(1, 500),
        )
    }

    data class Probe(
        val path: String,
        val name: String,
        val size: Long,
        val modifiedTime: Long,
        val extension: String = "",
        val extensionHint: String = "none",
        val zipMagic: Boolean = false,
        val zipStatus: String = "NOT_ZIP",
        val zip64: Boolean = false,
        val entryCount: Long = 0L,
        val scannedEntries: Int = 0,
        val hasAndroidManifest: Boolean = false,
        val hasResourcesArsc: Boolean = false,
        val hasClassesDex: Boolean = false,
        val candidate: Boolean = false,
        val confidence: String = "none",
        val reasons: List<String> = emptyList(),
        val truncated: Boolean = false,
        val stopReason: String = "eof",
        val bytesRead: Long = 0L,
    )

    data class Skipped(
        val total: Int = 0,
        val symbolicLinks: Int = 0,
        val unreadableDirectories: Int = 0,
        val unreadableFiles: Int = 0,
        val depthLimited: Int = 0,
        val entryLimited: Int = 0,
        val samples: List<String> = emptyList(),
    )

    data class Scan(
        val rootPath: String,
        val candidates: List<Probe>,
        val visitedEntries: Int,
        val probes: Int,
        val bytesRead: Long,
        val elapsedMillis: Long,
        val truncated: Boolean,
        val stopReason: String,
        val skipped: Skipped,
        val limits: Limits,
        val includeZipsWithoutManifest: Boolean,
    )

    /** 扩展名拆分：扩展名本身与「提示类型」；提示只做加分项，不参与结构判定。 */
    fun extensionInfo(name: String): Pair<String, String> {
        val lower = name.lowercase(Locale.ROOT)
        if (lower.endsWith(".apk")) return ".apk" to "apk"
        if (lower.contains(".apk.")) {
            val suffix = lower.substringAfterLast('.')
            if (suffix.isNotEmpty() && suffix.length <= 6 && suffix.all { it in '0'..'9' }) {
                if (lower.substring(0, lower.length - suffix.length - 1).endsWith(".apk")) {
                    return ".apk." + suffix to "apk.numbered"
                }
            }
        }
        if (lower.endsWith(".bak")) return ".bak" to "backup"
        if (lower.endsWith(".tmp") || lower.endsWith(".temp") || lower.endsWith(".cache") ||
            lower.endsWith(".partial") || lower.endsWith(".download")
        ) return lower.substringAfterLast('.') to "temporary"
        val dot = lower.lastIndexOf('.')
        if (dot <= 0 || dot == lower.length - 1) return "" to "none"
        return lower.substring(dot) to "other"
    }

    /** 单文件探测：只看魔数、中央目录与 APK 结构，最多 [maxBytes] 字节。 */
    fun probe(file: File, maxBytes: Long = MAX_PROBE_BYTES): Probe {
        if (!file.isFile) throw IOException("不是普通文件：" + file.path)
        val size = file.length()
        val budget = maxBytes.coerceIn(64L, MAX_PROBE_BYTES)
        val (extension, hint) = extensionInfo(file.name)
        val reasons = ArrayList<String>()
        val archive = RandomAccessFile(file, "r")
        try {
            val reader = BudgetReader(archive, budget)
            val header = reader.read(0, 4)
            if (header == null) {
                return Probe(
                    path = file.path, name = file.name, size = size, modifiedTime = file.lastModified(),
                    extension = extension, extensionHint = hint,
                    zipMagic = false, zipStatus = "ZIP_UNREADABLE", candidate = false,
                    confidence = "none", reasons = listOf("文件头不足 4 字节或读取预算不足，无法确认 ZIP 魔数"),
                    truncated = true, stopReason = reader.stopReason ?: "headerReadFailed",
                    bytesRead = reader.bytesRead,
                )
            }
            val zipMagic = header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
                ((header[2] == 0x03.toByte() && header[3] == 0x04.toByte()) ||
                    (header[2] == 0x05.toByte() && header[3] == 0x06.toByte()) ||
                    (header[2] == 0x07.toByte() && header[3] == 0x08.toByte()))
            if (!zipMagic) {
                return Probe(
                    path = file.path, name = file.name, size = size, modifiedTime = file.lastModified(),
                    extension = extension, extensionHint = hint,
                    zipMagic = false, zipStatus = "NOT_ZIP", candidate = false, confidence = "none",
                    reasons = listOf("文件头不是 ZIP 魔数 PK\\x03\\x04 / PK\\x05\\x06"),
                    bytesRead = reader.bytesRead,
                )
            }
            reasons.add("文件头为 ZIP 魔数")
            if (hint == "apk" || hint == "apk.numbered") reasons.add("扩展名提示 .apk 系列（仅加分项）")
            if (hint == "backup" || hint == "temporary") reasons.add("备份/临时扩展名（仅弱加分项）")

            val eocd = findEocd(archive, reader, size) ?: return Probe(
                path = file.path, name = file.name, size = size, modifiedTime = file.lastModified(),
                extension = extension, extensionHint = hint, zipMagic = true,
                zipStatus = "ZIP_UNREADABLE", candidate = false, confidence = "none",
                reasons = reasons + "未找到 ZIP EOCD（文件截断或不是完整 ZIP）",
                truncated = reader.truncated, stopReason = reader.stopReason ?: "eocdMissing",
                bytesRead = reader.bytesRead,
            )
            val entryCount = eocd.entryCount
            val zip64 = eocd.zip64
            if (entryCount == 0L) {
                return Probe(
                    path = file.path, name = file.name, size = size, modifiedTime = file.lastModified(),
                    extension = extension, extensionHint = hint, zipMagic = true, zipStatus = "ZIP_EMPTY",
                    zip64 = zip64, entryCount = 0L, candidate = false, confidence = "none",
                    reasons = reasons + "ZIP 中央目录为空，不是 APK",
                    truncated = reader.truncated, stopReason = reader.stopReason ?: "eof",
                    bytesRead = reader.bytesRead,
                )
            }
            if (eocd.cdOffset < 0L || eocd.cdSize < 0L || eocd.cdOffset + eocd.cdSize > size) {
                return Probe(
                    path = file.path, name = file.name, size = size, modifiedTime = file.lastModified(),
                    extension = extension, extensionHint = hint, zipMagic = true, zipStatus = "ZIP_UNREADABLE",
                    zip64 = zip64, entryCount = entryCount, candidate = false, confidence = "none",
                    reasons = reasons + "ZIP 中央目录偏移/长度超出文件范围",
                    truncated = reader.truncated, stopReason = "centralDirectoryOutOfRange",
                    bytesRead = reader.bytesRead,
                )
            }
            var offset = eocd.cdOffset
            var scanned = 0
            var manifest = false
            var resources = false
            var classesDex = false
            var corrupt = false
            val maximum = minOf(entryCount, MAX_CD_ENTRIES_PER_FILE.toLong())
            while (scanned.toLong() < maximum) {
                val headerBytes = reader.read(offset, 46)
                if (headerBytes == null) break
                if (u32(headerBytes, 0) != CD_SIG) {
                    corrupt = true
                    break
                }
                val nameLength = u16(headerBytes, 28)
                val extraLength = u16(headerBytes, 30)
                val commentLength = u16(headerBytes, 32)
                if (nameLength in 0..MAX_ENTRY_NAME_BYTES) {
                    val nameBytes = reader.read(offset + 46, nameLength)
                    if (nameBytes != null) {
                        val name = String(nameBytes, Charsets.UTF_8)
                        if (name == "AndroidManifest.xml") manifest = true
                        if (name == "resources.arsc") resources = true
                        if (name.length >= 6 && name.startsWith("classes") && name.endsWith(".dex")) {
                            classesDex = true
                        }
                    }
                }
                offset += 46L + nameLength + extraLength + commentLength
                scanned++
                if (reader.truncated) break
                if (offset > size) {
                    corrupt = true
                    break
                }
            }
            val truncated = reader.truncated || corrupt || scanned.toLong() < entryCount
            val stopReason = reader.stopReason ?: if (corrupt) "centralDirectoryCorrupt"
                else if (scanned.toLong() < entryCount) "centralDirectoryShort" else "eof"
            if (manifest) reasons.add("包含 AndroidManifest.xml（APK 结构确认）")
            if (resources) reasons.add("包含 resources.arsc")
            if (classesDex) reasons.add("包含 classes*.dex")
            if (!manifest) reasons.add("ZIP 结构完整但缺少 AndroidManifest.xml，不是 APK")
            if (truncated) reasons.add("中央目录未完整扫描，结构判定可能不完整")
            val candidate = manifest
            val confidence = when {
                candidate && (resources || classesDex) -> "high"
                candidate -> "medium"
                else -> "low"
            }
            return Probe(
                path = file.path, name = file.name, size = size, modifiedTime = file.lastModified(),
                extension = extension, extensionHint = hint, zipMagic = true,
                zipStatus = if (corrupt) "ZIP_UNREADABLE" else "ZIP_OK",
                zip64 = zip64, entryCount = entryCount, scannedEntries = scanned,
                hasAndroidManifest = manifest, hasResourcesArsc = resources, hasClassesDex = classesDex,
                candidate = candidate, confidence = confidence, reasons = reasons,
                truncated = truncated, stopReason = stopReason, bytesRead = reader.bytesRead,
            )
        } catch (error: EOFException) {
            return Probe(
                path = file.path, name = file.name, size = size, modifiedTime = file.lastModified(),
                extension = extension, extensionHint = hint, zipMagic = false, zipStatus = "ZIP_UNREADABLE",
                candidate = false, confidence = "none",
                reasons = listOf("读取 ZIP 结构时文件被截断：" + (error.message ?: "EOF")),
                truncated = true, stopReason = "eofWhileReading",
            )
        } finally {
            try {
                archive.close()
            } catch (_: IOException) {
                // 关闭失败不影响只读探测结果。
            }
        }
    }

    /**
     * 目录扫描：递归/深度/条目/探测数/字节/时间全部有界；候选只按结构判定，
     * 扩展名仅参与置信度说明。返回结果明确 truncated/skipped/stopReason。
     */
    fun scan(
        start: File,
        limits: Limits = Limits(),
        includeZipsWithoutManifest: Boolean = false,
    ): Scan {
        val effective = limits.normalized()
        val began = System.currentTimeMillis()
        val deadline = began + effective.maxMillis
        var visited = 0
        var probes = 0
        var bytesRead = 0L
        var stopReason: String? = null
        val candidates = ArrayList<Probe>()
        var symbolicLinks = 0
        var unreadableDirectories = 0
        var unreadableFiles = 0
        var depthLimited = 0
        val entryLimited = 0
        val samples = ArrayList<String>()

        fun skipSample(path: String) {
            if (samples.size < MAX_SKIPPED_SAMPLES) samples.add(path)
        }

        val stack = ArrayDeque<Frame>()
        stack.addLast(Frame(start, 0))
        while (stack.isNotEmpty()) {
            if (System.currentTimeMillis() > deadline) {
                stopReason = "timeBudget"
                break
            }
            val frame = stack.removeLast()
            val children = try {
                frame.dir.listFiles()
            } catch (_: SecurityException) {
                null
            }
            if (children == null) {
                unreadableDirectories++
                skipSample(frame.dir.path)
                continue
            }
            for (child in children.sortedBy { it.name }) {
                if (System.currentTimeMillis() > deadline) {
                    stopReason = "timeBudget"
                    break
                }
                if (visited >= effective.maxEntries) {
                    stopReason = "entryBudget"
                    break
                }
                visited++
                if (isSymbolicLink(child)) {
                    symbolicLinks++
                    skipSample(child.path)
                    continue
                }
                if (child.isDirectory) {
                    if (frame.depth + 1 > effective.maxDepth) {
                        depthLimited++
                        skipSample(child.path)
                    } else {
                        stack.addLast(Frame(child, frame.depth + 1))
                    }
                    continue
                }
                if (!child.isFile) {
                    unreadableFiles++
                    skipSample(child.path)
                    continue
                }
                if (probes >= effective.maxProbes) {
                    stopReason = "probeBudget"
                    break
                }
                val remaining = effective.maxBytes - bytesRead
                if (remaining < 64L) {
                    stopReason = "byteBudget"
                    break
                }
                val probe = try {
                    probe(child, minOf(MAX_PROBE_BYTES, remaining))
                } catch (_: IOException) {
                    unreadableFiles++
                    skipSample(child.path)
                    continue
                }
                probes++
                bytesRead += probe.bytesRead
                if (probe.candidate || (includeZipsWithoutManifest && probe.zipStatus == "ZIP_OK")) {
                    candidates.add(probe)
                    if (candidates.size >= effective.limit) {
                        stopReason = "candidateLimit"
                        break
                    }
                }
                if (bytesRead >= effective.maxBytes) {
                    stopReason = "byteBudget"
                    break
                }
            }
            if (stopReason != null) break
        }
        val elapsed = System.currentTimeMillis() - began
        val skippedTotal = symbolicLinks + unreadableDirectories + unreadableFiles + depthLimited + entryLimited
        val finalStop = stopReason ?: "eof"
        val truncated = stopReason != null || skippedTotal > 0
        val ordered = candidates.sortedWith(
            compareByDescending<Probe> { confidenceRank(it.confidence) }.thenBy { it.path },
        )
        return Scan(
            rootPath = start.path,
            candidates = ordered,
            visitedEntries = visited,
            probes = probes,
            bytesRead = bytesRead,
            elapsedMillis = elapsed,
            truncated = truncated,
            stopReason = finalStop,
            skipped = Skipped(
                total = skippedTotal,
                symbolicLinks = symbolicLinks,
                unreadableDirectories = unreadableDirectories,
                unreadableFiles = unreadableFiles,
                depthLimited = depthLimited,
                entryLimited = entryLimited,
                samples = samples.toList(),
            ),
            limits = effective,
            includeZipsWithoutManifest = includeZipsWithoutManifest,
        )
    }

    private fun confidenceRank(value: String): Int = when (value) {
        "high" -> 3
        "medium" -> 2
        "low" -> 1
        else -> 0
    }

    fun probeJson(value: Probe): JsonObject = JsonObject().apply {
        addProperty("path", value.path)
        addProperty("name", value.name)
        addProperty("size", value.size)
        addProperty("modifiedTime", value.modifiedTime)
        addProperty("extension", value.extension)
        addProperty("extensionHint", value.extensionHint)
        addProperty("zipMagic", value.zipMagic)
        addProperty("zipStatus", value.zipStatus)
        addProperty("zip64", value.zip64)
        addProperty("entryCount", value.entryCount)
        addProperty("scannedEntries", value.scannedEntries)
        addProperty("hasAndroidManifest", value.hasAndroidManifest)
        addProperty("hasResourcesArsc", value.hasResourcesArsc)
        addProperty("hasClassesDex", value.hasClassesDex)
        addProperty("candidate", value.candidate)
        addProperty("confidence", value.confidence)
        add("reasons", JsonArray().apply { value.reasons.forEach { add(it) } })
        addProperty("truncated", value.truncated)
        addProperty("stopReason", value.stopReason)
        addProperty("bytesRead", value.bytesRead)
    }

    fun scanJson(scan: Scan): JsonObject = JsonObject().apply {
        addProperty("root", scan.rootPath)
        add("candidates", JsonArray().apply { scan.candidates.forEach { add(probeJson(it)) } })
        addProperty("count", scan.candidates.size)
        addProperty("visitedEntries", scan.visitedEntries)
        addProperty("probes", scan.probes)
        addProperty("bytesRead", scan.bytesRead)
        addProperty("elapsedMillis", scan.elapsedMillis)
        addProperty("truncated", scan.truncated)
        addProperty("stopReason", scan.stopReason)
        addProperty("includeZipsWithoutManifest", scan.includeZipsWithoutManifest)
        add("limits", JsonObject().apply {
            addProperty("maxDepth", scan.limits.maxDepth)
            addProperty("maxEntries", scan.limits.maxEntries)
            addProperty("maxProbes", scan.limits.maxProbes)
            addProperty("maxBytes", scan.limits.maxBytes)
            addProperty("maxMillis", scan.limits.maxMillis)
            addProperty("limit", scan.limits.limit)
        })
        add("skipped", JsonObject().apply {
            addProperty("total", scan.skipped.total)
            addProperty("symbolicLinks", scan.skipped.symbolicLinks)
            addProperty("unreadableDirectories", scan.skipped.unreadableDirectories)
            addProperty("unreadableFiles", scan.skipped.unreadableFiles)
            addProperty("depthLimited", scan.skipped.depthLimited)
            addProperty("entryLimited", scan.skipped.entryLimited)
            add("samples", JsonArray().apply { scan.skipped.samples.forEach { add(it) } })
        })
    }

    // ------------------------------------------------------------------
    // 低层读取：有界、无整文件读入
    // ------------------------------------------------------------------

    private data class Frame(val dir: File, val depth: Int)

    private class BudgetReader(private val archive: RandomAccessFile, private val budget: Long) {
        var bytesRead = 0L
            private set
        var truncated = false
            private set
        var stopReason: String? = null
            private set

        fun read(offset: Long, length: Int): ByteArray? {
            if (length <= 0) return ByteArray(0)
            if (offset < 0L || length < 0) {
                truncated = true
                stopReason = "invalidOffset"
                return null
            }
            if (bytesRead + length > budget) {
                truncated = true
                stopReason = "byteBudget"
                return null
            }
            if (offset + length > archive.length()) return null
            val bytes = ByteArray(length)
            archive.seek(offset)
            var read = 0
            while (read < length) {
                val count = archive.read(bytes, read, length - read)
                if (count < 0) return null
                read += count
            }
            bytesRead += length
            return bytes
        }
    }

    private data class Eocd(
        val entryCount: Long,
        val cdOffset: Long,
        val cdSize: Long,
        val zip64: Boolean,
    )

    private fun findEocd(archive: RandomAccessFile, reader: BudgetReader, size: Long): Eocd? {
        val tailLength = minOf(size, EOCD_SCAN.toLong()).toInt()
        if (tailLength < EOCD_MIN) return null
        val tailOffset = size - tailLength
        val tail = reader.read(tailOffset, tailLength) ?: return null
        var eocdIndex = -1
        var index = tail.size - EOCD_MIN
        while (index >= 0) {
            if (u32(tail, index) == EOCD_SIG) {
                val comment = u16(tail, index + 20)
                if (index + EOCD_MIN + comment == tail.size) {
                    eocdIndex = index
                    break
                }
                if (eocdIndex < 0) eocdIndex = index
            }
            index--
        }
        if (eocdIndex < 0) return null
        var entryCount = u16(tail, eocdIndex + 10).toLong()
        var cdSize = u32(tail, eocdIndex + 12)
        var cdOffset = u32(tail, eocdIndex + 16)
        var zip64 = false
        if (entryCount == 0xFFFFL || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
            val locatorIndex = eocdIndex - 20
            if (locatorIndex >= 0 && u32(tail, locatorIndex) == ZIP64_LOCATOR_SIG) {
                val zip64Offset = u64(tail, locatorIndex + 8)
                if (zip64Offset >= 0L) {
                    val zip64Header = reader.read(zip64Offset, 56)
                    if (zip64Header != null && u32(zip64Header, 0) == ZIP64_EOCD_SIG) {
                        val count = u64(zip64Header, 32)
                        val cdSize64 = u64(zip64Header, 40)
                        val cdOffset64 = u64(zip64Header, 48)
                        if (count >= 0L && cdSize64 >= 0L && cdOffset64 >= 0L) {
                            entryCount = count
                            cdSize = cdSize64
                            cdOffset = cdOffset64
                            zip64 = true
                        }
                    }
                }
            }
        }
        return Eocd(entryCount = entryCount, cdOffset = cdOffset, cdSize = cdSize, zip64 = zip64)
    }

    private fun u16(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 2 > bytes.size) return 0
        return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun u32(bytes: ByteArray, offset: Int): Long {
        if (offset < 0 || offset + 4 > bytes.size) return 0L
        return (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)
    }

    private fun u64(bytes: ByteArray, offset: Int): Long {
        if (offset < 0 || offset + 8 > bytes.size) return -1L
        val low = u32(bytes, offset)
        val high = u32(bytes, offset + 4)
        if (high > 0x7FFFFFFFL) return -1L
        return (high shl 32) or low
    }

    private fun isSymbolicLink(file: File): Boolean = try {
        val files = Class.forName("java.nio.file.Files")
        val paths = Class.forName("java.nio.file.Paths")
        val pathType = Class.forName("java.nio.file.Path")
        val path = paths.getMethod("get", String::class.java, Array<String>::class.java)
            .invoke(null, file.absolutePath, emptyArray<String>())
        files.getMethod("isSymbolicLink", pathType).invoke(null, path) as Boolean
    } catch (_: Throwable) {
        false
    }
}
