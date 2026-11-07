package com.jdkshen.aijspro.mcp

import com.google.gson.JsonObject

/**
 * 工具目录变化信号（v4 §10.4：应用升级后宿主侧工具绑定不会自动刷新）。
 *
 * <p>纯逻辑，便于 JVM 单测：指纹 = 工具数 : versionCode : versionName。
 * initialize 时与上次记录比对，变化则给出提示对象（客户端应重新 tools/list）。
 */
internal object McpToolCatalogSignal {

    fun fingerprint(toolCount: Int, versionCode: Int, versionName: String): String =
        toolCount.toString() + ":" + versionCode + ":" + versionName

    /** 与上次指纹相同（或首次）→ null；变化 → 提示对象。 */
    fun notice(previous: String?, current: String, toolCount: Int, build: String): JsonObject? {
        if (previous == null || previous == current) return null
        return JsonObject().apply {
            addProperty("changed", true)
            addProperty("previous", previous)
            addProperty("current", current)
            addProperty("toolCount", toolCount)
            addProperty("build", build)
            addProperty("advice", "应用已升级、工具目录已变化：请重新 tools/list 刷新工具绑定后再调用新工具")
        }
    }

    /** 提示行（放进 instructions 首行；instructions 在结果里是单个字符串）。 */
    fun instructionLine(notice: JsonObject): String =
        "⚠️ " + notice.get("advice").asString + "（build " + notice.get("build").asString +
            "，工具数 " + notice.get("toolCount").asInt + "）"
}