package com.jdkshen.aijspro.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * P2-2：frida_eval 的结果/消息归一化。
 * messages 可能出现在顶层，也可能被包在 error.message.messages[]；失败但有产出要保留结果。
 */
internal object ToolkitFridaEval {
    fun normalize(response: JsonObject): JsonObject {
        val messages = extractMessages(response)
        val errors = messages?.count { it.isJsonObject && it.asJsonObject.tString("type", "") == "error" } ?: 0
        if (messages != null) {
            response.add("messages", messages)
            response.add("messageLines", JsonArray().apply { messages.forEach { add(messageLine(it)) } })
            response.addProperty("messageCount", messages.size())
        }
        val rawState = response.tString("state", "").ifEmpty {
            response.getAsJsonObject("data")?.tString("state", "") ?: ""
        }
        response.addProperty("scriptErrorCount", errors)
        response.addProperty("scriptSucceeded", errors == 0 && rawState != "FAILED")
        if (rawState == "FAILED" || errors > 0) {
            if (messages != null && messages.size() > 0) {
                response.addProperty("state", "FAILED_BUT_PARTIAL")
                response.addProperty("partial", true)
                response.addProperty("messagesPreserved", true)
                response.addProperty("warningCode", "FRIDA_FAILED_BUT_PARTIAL")
                response.addProperty("warningHint", "Frida 脚本执行失败，但 messages 有产出；不要丢弃本轮结果，先读 messages/messageLines")
            } else {
                throw ToolkitException("FRIDA_SCRIPT_ERROR", response.toString().take(12000), "script")
            }
        } else if (rawState == "SUCCEEDED_WITH_WARNINGS") {
            response.addProperty("partial", true)
        }
        response.add("statusSemantics", ToolkitIo.json(
            "SUCCEEDED" to "执行完成，无可识别错误",
            "SUCCEEDED_WITH_WARNINGS" to "结果可用但不完整；看 warningCode/warningReason；decompileIncomplete 是反编译专用字段，与 frida scriptErrorCount 无关",
            "FAILED_BUT_PARTIAL" to "执行失败但 messages 有产出；result 必须保留，partial=true",
            "FAILED" to "失败且没有产出；job 才按失败处理",
            "decompileIncomplete" to "反编译正文不完整（JADX 警告），不是本工具的 frida 脚本状态"
        ))
        return response
    }

    fun extractMessages(response: JsonObject): JsonArray? {
        response.getAsJsonArray("messages")?.let { return it }
        response.getAsJsonObject("data")?.getAsJsonArray("messages")?.let { return it }
        val error = response.getAsJsonObject("error") ?: return null
        return when (val message = error.get("message")) {
            is JsonObject -> message.getAsJsonArray("messages")
            is JsonArray -> message
            else -> null
        }
    }

    fun messageLine(message: JsonElement): String {
        if (message.isJsonPrimitive) return message.asString
        val obj = message as? JsonObject ?: return message.toString()
        for (key in listOf("message", "line", "text", "payload", "description")) {
            val value = obj.get(key)
            if (value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) return value.asString
        }
        return obj.toString()
    }
}
