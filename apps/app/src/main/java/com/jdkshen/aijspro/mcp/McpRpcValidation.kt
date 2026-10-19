package com.jdkshen.aijspro.mcp

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** A JSON-RPC envelope/parameter shape error, never a tool execution error. */
internal class McpInvalidParamsException(message: String) : Exception(message)

/** A validated tools/call request with the public name and the canonical tool to dispatch. */
internal data class McpToolCall(
    val requestedName: String,
    val canonicalName: String,
    val arguments: JsonObject
)

/**
 * Pure JSON-RPC parameter validation shared by the handler and unit tests.
 * It is stateless so one request can never observe another request's shape state.
 */
internal object McpRpcValidation {
    private val supportedMethods = setOf("initialize", "ping", "tools/list", "tools/call")

    fun isSupportedMethod(method: String): Boolean = method in supportedMethods

    /** initialize/ping/tools/list keep accepting omitted params; an explicit non-object is invalid. */
    fun optionalParams(params: JsonElement?): JsonObject? {
        if (params == null) return null
        if (!params.isJsonObject) throw McpInvalidParamsException("params 必须是对象")
        return params.asJsonObject
    }

    fun parseToolCall(params: JsonElement?): McpToolCall {
        val paramsObject = optionalParams(params) ?: throw McpInvalidParamsException("tools/call 缺少 params")
        val nameElement = paramsObject.get("name") ?: throw McpInvalidParamsException("缺少参数 name")
        if (!nameElement.isJsonPrimitive || !nameElement.asJsonPrimitive.isString) {
            throw McpInvalidParamsException("参数 name 必须是字符串")
        }
        val requestedName = nameElement.asString
        if (requestedName.isBlank()) throw McpInvalidParamsException("参数 name 不能为空")
        val canonicalName = McpToolCatalog.canonicalName(requestedName)
        if (canonicalName !in McpToolCatalog.canonicalNames) {
            throw McpInvalidParamsException("未知工具：$requestedName")
        }
        val arguments = when (val element = paramsObject.get("arguments")) {
            null -> JsonObject()
            is JsonObject -> element
            else -> throw McpInvalidParamsException("参数 arguments 必须是对象或省略")
        }
        return McpToolCall(requestedName, canonicalName, arguments)
    }
}
