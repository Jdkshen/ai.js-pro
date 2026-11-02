package com.jdkshen.aijspro.mcp

import android.content.Context
import android.net.Uri
import com.google.gson.*
import java.io.File
import java.io.IOException

/** Small file-backed IPC avoids Binder's transaction limit for code and ZIP pages. */
object McpToolkitBridge {
    @JvmStatic fun call(context: Context, root: File, name: String, args: JsonObject): JsonObject = request(context, root, "call", name, args)
    internal fun definitions(context: Context, root: File): JsonArray {
        val schemas = context.assets.open("mcp/toolkit-apk-schemas.json").bufferedReader().use { JsonParser().parse(it).asJsonObject }
        return McpToolkit(root, File(context.cacheDir, "toolkit-catalog"), schemas).definitions()
    }
    private fun request(context: Context, root: File, method: String, name: String, args: JsonObject): JsonObject {
        val ipc = File(context.cacheDir, "toolkit-ipc").canonicalFile
        require(ipc.isDirectory || ipc.mkdirs()) { "无法创建工具请求目录" }
        val key = ToolkitIo.fresh(); val input = File(ipc, "$key.request"); val output = File(ipc, "$key.response")
        try {
            input.writeText(Gson().toJson(ToolkitIo.json("root" to root.canonicalPath, "name" to name, "arguments" to args)), Charsets.UTF_8)
            require(input.length() <= 20 * 1024 * 1024) { "请求超过20MiB，请分批写入" }
            val result = context.contentResolver.call(Uri.parse("content://${context.packageName}.toolkit"), method, key, null)
            if(result?.getBoolean("ready") != true || !output.isFile) throw IOException("工具进程没有返回结果，可重新打开工作区重试")
            require(output.length() <= 24 * 1024 * 1024) { "工具结果过大，请缩小分页" }
            return JsonParser().parse(output.readText(Charsets.UTF_8)).asJsonObject
        } finally { input.delete(); output.delete() }
    }
}
