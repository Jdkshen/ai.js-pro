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
}
