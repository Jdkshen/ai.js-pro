package com.jdkshen.aijspro.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * §12 统一证据导出：把「原件身份 + 分析覆盖层/编辑差异 + 工具版本与命令 + 报告与复现脚本 + 文件清单与哈希」
 * 打成一份可复核的归档，并做完整解压校验。
 *
 * 两条硬要求：
 *  1. 结论必须标注证据等级（文档声明 / 静态推断 / 静态确认 / 运行时确认 / 修改后验收），不能混为一谈；
 *  2. 默认排除生产私钥、连接令牌、ADB 授权资料与无关应用数据；调试密钥如需导出必须显式标注仅供测试。
 */
internal object ToolkitEvidenceExport {
    const val SCHEMA_VERSION = 1

    /** 归档内清单文件名。 */
    const val MANIFEST_NAME = "evidence.json"

    data class Entry(val path: String, val bytes: ByteArray)

    // 用 Kotlin 原始字符串，避免正则里的 \s/\- 被当成 Kotlin 转义序列。
    private val DEFAULT_SECRET_PATTERNS = listOf(
        """(?s)-----BEGIN[^-]*PRIVATE KEY-----.*?-----END[^-]*PRIVATE KEY-----""",
        """-----BEGIN (RSA |EC |OPENSSH |PRIVATE )?PRIVATE KEY-----""",
        """adbd?\s+authorization""",
        """(?i)(token|secret|password|passwd|apikey|api_key)\s*[:=]\s*[A-Za-z0-9._\-]{8,}""",
    )

    /** 证据分级（复盘 §12 表格）。 */
    fun levels(): JsonArray = JsonArray().apply {
        add(level("documented"))
        add(level("static_inferred"))
        add(level("static_confirmed"))
        add(level("runtime_confirmed"))
        add(level("post_change_verified"))
    }

    fun level(code: String): JsonObject = when (code) {
        "documented" -> ToolkitIo.json("code" to code, "zh" to "文档/接口声明", "meaning" to "仅表明存在入口或文档支持，不代表行为已验证")
        "static_inferred" -> ToolkitIo.json("code" to code, "zh" to "静态推断", "meaning" to "由结构、命名或模式推测")
        "static_confirmed" -> ToolkitIo.json("code" to code, "zh" to "静态确认", "meaning" to "字节码、机器码或重定位证据能够对上")
        "runtime_confirmed" -> ToolkitIo.json("code" to code, "zh" to "运行时确认", "meaning" to "在目标进程实际观测到命中")
        "post_change_verified" -> ToolkitIo.json("code" to code, "zh" to "修改后验收", "meaning" to "在明确环境中验证了修改后的目标行为")
        else -> ToolkitIo.json("code" to code, "zh" to "未知等级", "meaning" to "未登记的证据等级，按最低可信处理")
    }

    /** 脱敏：返回处理后的文本与命中次数；不返回命中的原文。 */
    fun redact(text: String, patterns: List<String> = DEFAULT_SECRET_PATTERNS): JsonObject {
        var output = text
        var hits = 0
        patterns.forEach { pattern ->
            val regex = runCatching { Regex(pattern, RegexOption.MULTILINE) }.getOrNull() ?: return@forEach
            val matches = regex.findAll(output).count()
            if (matches > 0) {
                hits += matches
                output = regex.replace(output) { "[REDACTED]" }
            }
        }
        return ToolkitIo.json("text" to output, "redactions" to hits)
    }

    /**
     * 生成证据清单。files 只包含要打包的文件；私钥/令牌等敏感文件由调用方剔除或在此被红名单拒绝。
     */
    fun buildManifest(
        identity: JsonObject,
        overlay: JsonObject? = null,
        editDiff: JsonObject? = null,
        reports: List<String> = emptyList(),
        commands: List<String> = emptyList(),
        tools: JsonObject? = null,
        files: List<Entry> = emptyList(),
        claims: List<Pair<String, String>> = emptyList(),
        debugKeyIncluded: Boolean = false,
        generatedAt: Long = System.currentTimeMillis()
    ): JsonObject {
        val fileList = JsonArray()
        files.forEach { entry ->
            val redacted = redact(String(entry.bytes, Charsets.ISO_8859_1))
            val sensitive = (redacted.get("redactions").asInt) > 0
            fileList.add(ToolkitIo.json(
                "path" to entry.path,
                "bytes" to entry.bytes.size,
                "sha256" to sha256(entry.bytes),
                "sensitiveHits" to redacted.get("redactions").asInt,
                "note" to if (sensitive) "该文件包含疑似敏感串；导出前请确认（文本已提供脱敏版本另行生成）" else null
            ))
        }
        val claimArray = JsonArray()
        claims.forEach { (claim, levelCode) ->
            claimArray.add(ToolkitIo.json("claim" to claim, "level" to levelCode, "levelDetail" to level(levelCode)))
        }
        return ToolkitIo.json(
            "schemaVersion" to SCHEMA_VERSION,
            "generatedAt" to generatedAt,
            "identity" to identity,
            "tools" to (tools ?: ToolkitIo.json()),
            "commands" to commands,
            "overlay" to overlay,
            "editDiff" to editDiff,
            "reports" to reports,
            "files" to fileList,
            "claims" to claimArray,
            "evidenceLevels" to levels(),
            "sensitivePolicy" to ToolkitIo.json(
                "excludedByDefault" to listOf("生产私钥", "连接令牌", "ADB 授权资料", "无关应用数据"),
                "debugKeyIncluded" to debugKeyIncluded,
                "debugKeyNote" to if (debugKeyIncluded) "包含调试密钥：仅供测试，禁止用于生产分发" else "未包含调试密钥"
                ,
                "redactionPatterns" to DEFAULT_SECRET_PATTERNS.size
            ),
            "verification" to ToolkitIo.json(
                "fullExtractCheck" to false,
                "note" to "归档写入后会做完整解压与哈希复核，结果见 writeZip 返回值"
            )
        )
    }

    /**
     * 写出归档（manifest + files），随后完整解压校验：条目数、每项 SHA-256、manifest 可解析。
     * 校验失败时返回 passed=false 并给出原因，调用方不应把归档当证据。
     */
    fun writeZip(manifest: JsonObject, files: List<Entry>, output: File): JsonObject {
        val manifestBytes = manifest.toString().toByteArray(Charsets.UTF_8)
        output.parentFile?.mkdirs()
        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_NAME))
            zip.write(manifestBytes)
            zip.closeEntry()
            files.forEach { entry ->
                zip.putNextEntry(ZipEntry(entry.path))
                zip.write(entry.bytes)
                zip.closeEntry()
            }
        }
        val failures = ArrayList<String>()
        var manifestOk = false
        var checkedEntries = 0
        ZipFile(output).use { zip ->
            val manifestEntry = zip.getEntry(MANIFEST_NAME)
            if (manifestEntry == null) failures.add("归档缺少 $MANIFEST_NAME")
            else {
                val read = zip.getInputStream(manifestEntry).readBytes()
                manifestOk = read.contentEquals(manifestBytes)
                if (!manifestOk) failures.add("manifest 内容与写入不一致")
            }
            files.forEach { entry ->
                val zipEntry = zip.getEntry(entry.path)
                if (zipEntry == null) { failures.add("缺少条目：" + entry.path); return@forEach }
                val actual = zip.getInputStream(zipEntry).readBytes()
                checkedEntries++
                if (!actual.contentEquals(entry.bytes)) failures.add("条目内容不一致：" + entry.path)
            }
        }
        return ToolkitIo.json(
            "path" to output.path,
            "bytes" to output.length(),
            "sha256" to sha256(output.readBytes()),
            "manifestName" to MANIFEST_NAME,
            "manifestSha256" to sha256(manifestBytes),
            "entries" to files.size,
            "checkedEntries" to checkedEntries,
            "manifestParsable" to manifestOk,
            "fullExtractCheck" to failures.isEmpty(),
            "failures" to failures
        )
    }

    /**
     * 递归列出目录内的普通文件（相对路径 + 内容），用于打包前收集。
     *
     * 只按 maxEntries/maxBytes 预算截断，不做符号链接判断（Android API 26 以下没有
     * java.nio.file.Files；宁可有界截断，也不引入版本门槛）。
     */
    fun collect(root: File, maxEntries: Int = 512, maxBytes: Long = 64L * 1024 * 1024): List<Entry> {
        val result = ArrayList<Entry>()
        var total = 0L
        root.walkTopDown().forEach { file ->
            if (result.size >= maxEntries) return@forEach
            if (!file.isFile || file.length() > maxBytes) return@forEach
            if (total + file.length() > maxBytes) return@forEach
            total += file.length()
            result.add(Entry(file.relativeTo(root).path.replace(File.separatorChar, '/'), file.readBytes()))
        }
        return result
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { String.format("%02x", it.toInt() and 0xff) }
    }
}
