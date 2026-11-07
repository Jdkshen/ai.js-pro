package com.jdkshen.aijspro.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.File

/**
 * §11/§12 的 MCP 入口：可验证二进制变换与统一证据导出。
 *
 * 纯计算在 [ToolkitTransforms] / [ToolkitEvidenceExport]；这里负责路径校验、落盘与结果汇总，
 * 并保持两条边界：① 分析副本默认不流入构建；② 导出默认排除/脱敏敏感串。
 */
internal object ToolkitExtraTools {
    val names = setOf("binary_transform", "evidence_export")

    fun call(root: File, store: File?, name: String, args: JsonObject): JsonObject = when (name) {
        "binary_transform" -> transform(root, store, args)
        "evidence_export" -> export(root, store, args)
        else -> throw IllegalArgumentException("未知扩展工具：$name")
    }

    private fun transform(root: File, store: File?, args: JsonObject): JsonObject {
        val source = ToolkitIo.resolve(root, args.tString("path"))
        require(source.isFile && source.length() <= ToolkitIo.MAX_ENTRY) { "输入必须是工具目录内不超过 64 MiB 的文件" }
        val bytes = source.readBytes()
        val start = args.tInt("rangeStart", 0, 0, bytes.size)
        val end = args.tInt("rangeEnd", bytes.size, start, bytes.size)
        val params = args.getAsJsonObject("params")?.entrySet()?.associate { entry ->
            entry.key to (entry.value.takeIf { it.isJsonPrimitive }?.asString ?: "")
        } ?: emptyMap()
        val analysisOnly = args.tBool("analysisOnly", true)
        val outcome = ToolkitTransforms.apply(
            bytes, start, end, args.tString("op"), params,
            analysisOnly = analysisOnly,
            paramsSource = args.tString("paramsSource", "caller_provided")
        )
        val result = ToolkitIo.json(
            "sourcePath" to source.path,
            "sourceSha256" to ToolkitIo.sha(source),
            "outputBytes" to outcome.bytes.size
        )
        result.add("evidence", outcome.evidence)
        val outPath = args.tString("outPath", "")
        if (outPath.isEmpty()) {
            result.addProperty("written", false)
            result.addProperty("writeNote", "未提供 outPath：只返回证据与输出哈希，不落盘")
        } else {
            // Lead 接线：B 的 organizeOutput 之前只在 export 路径用上，这里补到 transform 的写盘前。
            val requested = ToolkitIo.resolve(root, outPath)
            val (destination, organized) = organizeOutput(root, store, requested, args.tBool("organize", true))
            require(!destination.exists()) { "outPath 已存在，请使用新文件名" }
            require(destination.parentFile?.isDirectory == true) { "outPath 的父目录必须存在" }
            // ToolkitIo.atomic 的写入器拿到的是临时 File（不是流），写完再原子替换。
            ToolkitIo.atomic(destination) { temp -> temp.writeBytes(outcome.bytes) }
            result.addProperty("outPath", destination.path)
            if (organized != null) result.add("organized", organized)
            result.addProperty("outputSha256", ToolkitIo.sha(destination))
            result.addProperty("written", true)
            result.addProperty("writeNote", if (analysisOnly) "已写出分析副本：不得作为构建输入" else "已写出修改结果")
        }
        return result
    }

    private fun export(root: File, store: File?, args: JsonObject): JsonObject {
        val outPath = args.tString("outPath", "")
        require(outPath.isNotEmpty()) { "evidence_export 需要 outPath" }
        val requested = ToolkitIo.resolve(root, outPath)
        val (destination, organized) = organizeOutput(root, store, requested, args.tBool("organize", true))
        require(!destination.exists()) { "outPath 已存在，请使用新文件名" }
        require(destination.parentFile?.isDirectory == true) { "outPath 的父目录必须存在" }

        val rawFiles = ArrayList<ToolkitEvidenceExport.Entry>()
        val sourceDir = args.tString("path", "")
        if (sourceDir.isNotEmpty()) {
            val dir = ToolkitIo.resolve(root, sourceDir)
            require(dir.isDirectory) { "path 必须是工具目录内的目录" }
            rawFiles.addAll(ToolkitEvidenceExport.collect(dir, args.tInt("maxEntries", 512, 1, 4096)))
        }
        // 默认脱敏：命中私钥/令牌的**文本**文件替换为脱敏副本；二进制文件原样保留但在清单里标注命中数。
        val redactSensitive = args.tBool("redactSensitive", true)
        var redactedFiles = 0
        val files = rawFiles.map { entry ->
            if (!redactSensitive || entry.bytes.any { it == 0.toByte() }) entry
            else {
                val outcome = ToolkitEvidenceExport.redact(String(entry.bytes, Charsets.UTF_8))
                if (outcome.get("redactions").asInt > 0) {
                    redactedFiles++
                    ToolkitEvidenceExport.Entry(entry.path, outcome.get("text").asString.toByteArray(Charsets.UTF_8))
                } else entry
            }
        }

        val claims = ArrayList<Pair<String, String>>()
        args.getAsJsonArray("claims")?.forEach { element ->
            if (element != null && element.isJsonObject) {
                val claim = element.asJsonObject
                val text = claim.get("claim")?.takeIf { it.isJsonPrimitive }?.asString ?: return@forEach
                val level = claim.get("level")?.takeIf { it.isJsonPrimitive }?.asString ?: "documented"
                claims.add(text to level)
            }
        }
        val stringList = { key: String ->
            val array = args.getAsJsonArray(key)
            array?.mapNotNull { element -> element?.takeIf { it.isJsonPrimitive }?.asString } ?: emptyList()
        }
        val manifest = ToolkitEvidenceExport.buildManifest(
            identity = args.getAsJsonObject("identity") ?: ToolkitIo.json(),
            overlay = args.getAsJsonObject("overlay"),
            editDiff = args.getAsJsonObject("editDiff"),
            reports = stringList("reports"),
            commands = stringList("commands"),
            tools = args.getAsJsonObject("tools"),
            files = files,
            claims = claims,
            debugKeyIncluded = args.tBool("debugKeyIncluded", false)
        )
        manifest.addProperty("redactedFiles", redactedFiles)
        manifest.addProperty("redactSensitive", redactSensitive)
        val archive = ToolkitEvidenceExport.writeZip(manifest, files, destination)
        return ToolkitIo.json(
            "archive" to archive,
            "manifest" to manifest,
            "entries" to files.size,
            "redactedFiles" to redactedFiles,
            "verification" to ToolkitIo.json(
                "fullExtractCheck" to archive.get("fullExtractCheck"),
                "failures" to archive.get("failures")
            )
        )
    }

    /** B：证据落盘走 A 的归置器；拿不到 store/归置器时保持原路径（旧行为）。 */
    private fun organizeOutput(root: File, store: File?, requested: File, organize: Boolean): Pair<File, JsonObject?> {
        if (!organize) return requested to null
        val configuredStore = store ?: runCatching {
            val context = com.stardust.app.GlobalAppContext.get()
            File(File(context.filesDir, "apk-toolkit"), ToolkitIo.version(root.path.toByteArray()).take(16))
        }.getOrNull() ?: return requested to null
        return runCatching {
            val result = ToolkitOutputOrganizers.forStore(configuredStore, root).organize(requested.path, true, "evidence_export")
            File(result.path) to result.organized
        }.getOrElse { requested to null }
    }
}
