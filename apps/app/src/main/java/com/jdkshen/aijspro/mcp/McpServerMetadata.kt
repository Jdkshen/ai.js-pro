package com.jdkshen.aijspro.mcp

import com.google.gson.JsonObject
import com.jdkshen.aijspro.BuildConfig

/**
 * B14：MCP 的 serverInfo 必须能自证真实构建版本。
 *
 * tools/list 按 MCP 规范没有 serverInfo；这里作为兼容扩展也放一份，客户端即使
 * 不做 initialize 握手（.4 的客户端就是直接 tools/list）也能拿到 name/version。
 */
internal object McpServerMetadata {
    const val NAME: String = "AI.js Pro MCP"

    fun versionName(): String = BuildConfig.VERSION_NAME.ifBlank { "unknown" }
    fun versionCode(): Int = BuildConfig.VERSION_CODE

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("name", NAME)
        // 真机 release 为 dev-1380；debug 单元测试则是 build.gradle 的 1.0.2/465。
        addProperty("version", versionName())
        addProperty("build", versionName())
        addProperty("versionCode", versionCode())
    }
}
