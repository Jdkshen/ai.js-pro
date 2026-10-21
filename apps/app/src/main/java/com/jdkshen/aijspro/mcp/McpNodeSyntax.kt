package com.jdkshen.aijspro.mcp

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.autojs.source.engine.android.Node24Tools
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal object McpNodeSyntax {
    fun check(context: Context, code: String, name: String): JsonObject {
        require(code.toByteArray(Charsets.UTF_8).size <= 256 * 1024) { "源码不得超过 256 KiB" }
        val started = System.nanoTime()
        val tools = JSONObject(Node24Tools.prepare(context))
        val output = java.io.File.createTempFile("node-syntax-", ".txt", context.cacheDir)
        try {
            val process = ProcessBuilder(tools.getString("node"), "--check", "--input-type=commonjs")
                .redirectErrorStream(true).redirectOutput(output).start()
            try {
                process.outputStream.use { it.write(code.removePrefix("\uFEFF").toByteArray(Charsets.UTF_8)) }
                check(process.waitFor(15, TimeUnit.SECONDS)) { "Node 语法编译器超时" }
                val text = output.inputStream().use { input ->
                    val bytes = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (bytes.size() < 65536) {
                        val count = input.read(buffer, 0, minOf(buffer.size, 65536 - bytes.size()))
                        if (count < 0) break
                        bytes.write(buffer, 0, count)
                    }
                    String(bytes.toByteArray(), Charsets.UTF_8)
                }
                return result(process.exitValue(), text, name, (System.nanoTime() - started) / 1_000_000)
            } finally { if (process.isAlive) process.destroyForcibly() }
        } finally { output.delete() }
    }
    fun result(exit: Int, text: String, name: String, elapsed: Long): JsonObject = JsonObject().apply {
        addProperty("success", exit == 0); addProperty("valid", exit == 0)
        addProperty("engine", "nodejs"); addProperty("name", name)
        addProperty("preprocessed", false); addProperty("executed", false); addProperty("elapsedMs", elapsed)
        add("errors", JsonArray().apply { if (exit != 0) add(JsonObject().apply {
            addProperty("type", "SyntaxError"); addProperty("message", text.take(16384))
            Regex("(?m)^\\[stdin]:(\\d+)").find(text)?.let { addProperty("line", it.groupValues[1].toInt()) }
            val caret = text.lines().firstOrNull { it.trimStart().startsWith('^') }
            if (caret != null) addProperty("column", caret.indexOf('^') + 1)
            addProperty("coordinates", "source")
        }) })
    }
}
