package com.jdkshen.aijspro.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonObject

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

    /** 裸文本类（workspace_diff / learn_autojs_skill）：旧文本放 content，文本同时放 data 字段。 */
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

    /** W4 task-21：data 镜像的大小护栏（序列化字节）。超过就不补，避免 deepCopy 让响应体积翻倍。 */
    const val DATA_MIRROR_LIMIT_BYTES = 64 * 1024

    /**
     * W4 task-21：给「扁平且还没有 data」的成功结果补一份 data 镜像 —— 保留全部扁平顶层字段，
     * **不**改成 `{ok,data}` 信封（那会破坏 `.4` 现有的扁平断言）。跳过条件：
     *  1) 失败结果（isError=true）；
     *  2) 没有 structuredContent 或它不是对象（例如图像类结果）；
     *  3) structuredContent 里已有 `data`（terminal_* 与 shell_rish 走 terminalJson 已补过）；
     *  4) structuredContent 已是 `{ok,...}` 信封（toolkit/workspace/逆向等工具）；
     *  5) 序列化超过 [limitBytes] —— 不补，改为写 `dataOmitted{reason,bytes,limitBytes}` 说明原因，
     *     让调用方知道「这里本该有 data，但因为太大没补」，而不是静默缺失。
     */
    fun mirrorFlatResult(result: JsonObject, limitBytes: Int = DATA_MIRROR_LIMIT_BYTES): JsonObject {
        if (result.get("isError")?.asBoolean == true) return result
        val element = result.get("structuredContent") ?: return result
        if (!element.isJsonObject) return result
        val structured = element.asJsonObject
        if (structured.has("ok") || structured.has("data")) return result
        val bytes = structured.toString().toByteArray(Charsets.UTF_8).size
        if (bytes > limitBytes) {
            structured.add("dataOmitted", ToolkitIo.json(
                "reason" to "扁平结果 " + bytes + " 字节超过 data 镜像上限 " + limitBytes +
                    " 字节：再深拷贝一份会让响应体积翻倍；请直接读扁平顶层字段，或用分页/maxChars 缩小后重试",
                "bytes" to bytes,
                "limitBytes" to limitBytes,
            ))
        } else {
            structured.add("data", structured.deepCopy())
        }
        return result
    }
}
