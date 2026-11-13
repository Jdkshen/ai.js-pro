package com.jdkshen.aijspro.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/**
 * P1-A：把历史扁平/裸文本成功结果升级成标准 {ok,data} 信封。
 *
 * <p>content[0].text 故意保留旧文本（默认是旧的扁平 JSON），这样读 content 文本的旧调用方
 * 仍能拿到旧字段；structuredContent 才是新的 {ok,data}。data 内也保留原扁平字段。</p>
 */
internal object McpToolResultShapes {
    fun success(data: JsonObject, legacyText: String = data.toString()): JsonObject = JsonObject().apply {
        add("content", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", legacyText)
            })
        })
        add("structuredContent", ToolkitIo.ok(data))
    }

    /** 裸文本类（workspace_diff / learn_aijspro_skill）：旧文本放 content，文本同时放 data 字段。 */
    fun textResult(field: String, text: String): JsonObject =
        success(ToolkitIo.json(field to text), legacyText = text)

    /**
     * P0-2：terminal_* 历史上是扁平 structuredContent（jobId 在顶层）。在保留全部旧顶层字段的同时，
     * 补一份 data 镜像，让按 structuredContent.data.jobId 解析的新客户端不再 TypeError。
     * 仅用于扁平 job 结果；已有 data 的结果不重复包。
     */
    fun flatWithDataMirror(flat: JsonObject): JsonObject = flat.apply {
        if (!has("data")) add("data", deepCopy())
    }

    /**
     * W4 task-21/27：data 镜像的体积护栏（UTF-8 字节）。
     *
     * W4 task-27（b）口径修正：以前量的是**镜像前**的 structuredContent，于是 48KiB 的扁平结果
     * 镜像后约 95KiB、再加 content[0].text 里的旧扁平 JSON 就成了约 145KiB（dev-1418 实测
     * shell_capabilities 95,972B、get_ui_layout 94,282B）。现在按「**镜像后**的 structuredContent +
     * content[0].text 文本」总字节判定（真正会发出去的体积），上限同时从 64KiB 收到 32KiB。
     */
    const val DATA_MIRROR_LIMIT_BYTES = 32 * 1024

    /**
     * W4 task-27（c）：已知大载荷工具的**轻量摘要**名单（命中就不整棵 deepCopy）。
     *
     * 名单理由（schema 上限 + dev-1418 K40 实测）：
     *  - shell_capabilities：能力矩阵，实测扁平 structuredContent 95,972B；
     *  - get_ui_layout：XML + nodes，虽受 maxNodes/maxDepth 限制，实测仍可达 94,282B；
     *  - read_api_doc：正文按 maxChars（128..32,000）分页，单页正文可达约 32KiB；
     *  - get_mcp_log：日志文本按 maxChars（默认 20,000、最大 200,000）返回。
     * 共同点：整棵 deepCopy 会让响应接近翻倍，而调用方先要的是「有没有/多大/截断了没」这类标量与计数；
     * 完整内容仍在 content[0].text 与扁平顶层字段里（本轮不改这两处）。
     */
    val SUMMARY_MIRROR_TOOLS: Set<String> = linkedSetOf(
        "shell_capabilities",
        "get_ui_layout",
        "read_api_doc",
        "get_mcp_log",
    )

    /** 摘要里长字符串的截断长度（超出即截断并标注）。 */
    const val SUMMARY_STRING_CHARS = 512

    /** 摘要 data 的固定说明（顶层 dataSummaryNote）。 */
    const val SUMMARY_NOTE =
        "data 是轻量摘要：只含标量与计数，数组/对象只给 key/类型/元素数；完整内容在 content[0].text 与扁平顶层字段（本工具属已知大载荷名单）。"

    /**
     * W4 task-27（c）：把扁平结果压成轻量摘要 —— 保留全部顶层**标量**（长字符串截断），
     * 数组/对象只记 `{key,type,count|keys,bytes}` 收进 omitted[]，并打 summary:true。
     */
    fun summarize(flat: JsonObject): JsonObject = JsonObject().apply {
        val omitted = JsonArray()
        for ((key, value) in flat.entrySet()) {
            when {
                value.isJsonPrimitive -> add(key, summarizePrimitive(value.asJsonPrimitive))
                value.isJsonArray -> {
                    var bytes = 0
                    value.asJsonArray.forEach { bytes += jsonBytes(it) }
                    omitted.add(ToolkitIo.json("key" to key, "type" to "array", "count" to value.asJsonArray.size(), "bytes" to bytes))
                }
                value.isJsonObject -> omitted.add(ToolkitIo.json(
                    "key" to key, "type" to "object", "keys" to value.asJsonObject.entrySet().size, "bytes" to jsonBytes(value)))
            }
        }
        addProperty("summary", true)
        add("omitted", omitted)
    }

    private fun summarizePrimitive(primitive: JsonPrimitive): JsonPrimitive {
        if (!primitive.isString) return primitive
        val text = primitive.asString
        return if (text.length > SUMMARY_STRING_CHARS) JsonPrimitive(text.take(SUMMARY_STRING_CHARS) + "…（已截断，完整内容见 content[0].text）") else primitive
    }

    /** JSON 序列化后的 UTF-8 字节数（护栏口径用）。 */
    private fun jsonBytes(element: JsonElement): Int = element.toString().toByteArray(Charsets.UTF_8).size

    /** content[] 里所有 text 项的总字节（图像项没有 text，不计）。 */
    private fun contentTextBytes(result: JsonObject): Int {
        val content = result.getAsJsonArray("content") ?: return 0
        var total = 0
        content.forEach { item ->
            val text = item.takeIf { it.isJsonObject }?.asJsonObject?.get("text")?.takeIf { it.isJsonPrimitive }?.asString
            if (text != null) total += text.toByteArray(Charsets.UTF_8).size
        }
        return total
    }

    /** 量「structuredContent + data」的序列化字节（只在临时副本上打，不写回 structured）。 */
    private fun measureWithData(structured: JsonObject, data: JsonObject): Int {
        val probe = structured.deepCopy()
        probe.add("data", data.deepCopy())
        return jsonBytes(probe)
    }

    private fun omittedJson(measured: Int, textBytes: Int, limitBytes: Int, kind: String): JsonObject = ToolkitIo.json(
        "reason" to "按「镜像后 structuredContent + content[0].text」总大小判定：" + measured + " 字节（含 content 文本 " + textBytes +
            " 字节）超过 data " + kind + "上限 " + limitBytes + " 字节；补 data 会让响应接近翻倍。请直接读扁平顶层字段，或用分页/maxChars/maxDims 缩小后重试",
        "bytes" to measured,
        "limitBytes" to limitBytes,
        "contentTextBytes" to textBytes,
        "measured" to "mirroredStructuredContent+contentText",
        "kind" to kind,
    )

    /**
     * W4 task-21/27：给「扁平且还没有 data」的成功结果补一份 data —— 保留全部扁平顶层字段，
     * **不**改成 `{ok,data}` 信封（那会破坏 `.4` 现有的扁平断言）。跳过条件：
     *  1) 失败结果（isError=true）；
     *  2) 没有 structuredContent 或它不是对象（例如图像类结果）；
     *  3) structuredContent 里已有 `data`（terminal_* 与 shell_rish 走 terminalJson 已补过）；
     *  4) structuredContent 已是 `{ok,...}` 信封（toolkit/workspace/逆向等工具）；
     *  5) 体积超上限（task-27 b 的新口径）—— 不补，改写 dataOmitted{reason,bytes,limitBytes,...}，
     *     让调用方知道「这里本该有 data，但因为太大没补」，而不是静默缺失。
     *
     * task-27（c）：[toolName] 命中 [SUMMARY_MIRROR_TOOLS] 时只补轻量摘要（不整棵 deepCopy），
     * 并打 dataSummary:true + dataSummaryNote；其余工具保持整棵镜像。
     */
    fun mirrorFlatResult(result: JsonObject, toolName: String? = null, limitBytes: Int = DATA_MIRROR_LIMIT_BYTES): JsonObject {
        if (result.get("isError")?.asBoolean == true) return result
        val element = result.get("structuredContent") ?: return result
        if (!element.isJsonObject) return result
        val structured = element.asJsonObject
        if (structured.has("ok") || structured.has("data")) return result
        val textBytes = contentTextBytes(result)
        if (toolName != null && toolName in SUMMARY_MIRROR_TOOLS) {
            val summary = summarize(structured)
            val measured = measureWithData(structured, summary) + textBytes
            if (measured > limitBytes) {
                structured.add("dataOmitted", omittedJson(measured, textBytes, limitBytes, "摘要"))
            } else {
                structured.add("data", summary)
                structured.addProperty("dataSummary", true)
                structured.addProperty("dataSummaryNote", SUMMARY_NOTE)
            }
            return result
        }
        val measured = measureWithData(structured, structured) + textBytes
        if (measured > limitBytes) {
            structured.add("dataOmitted", omittedJson(measured, textBytes, limitBytes, "全量镜像"))
        } else {
            structured.add("data", structured.deepCopy())
        }
        return result
    }
}
