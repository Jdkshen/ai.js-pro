package com.jdkshen.aijspro.mcp

import android.content.*
import android.database.Cursor
import android.net.Uri
import android.os.*
import com.google.gson.*
import com.stardust.autojs.execution.ScriptProcessIdentity
import java.io.File
import java.util.UUID
import java.util.concurrent.Semaphore

/** Same-UID control endpoint in :script. Only a cache token crosses Binder on return. */
class McpAutomationProvider : ContentProvider() {
    private val permits = Semaphore(4)
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        check(Binder.getCallingUid() == android.os.Process.myUid()) { "Same UID required" }
        require(McpAutomationTransport.supports(method)) { "Unknown automation tool" }
        val request = extras?.getString("arguments") ?: "{}"
        require(request.toByteArray(Charsets.UTF_8).size <= 64 * 1024) { "Automation arguments too large" }
        check(permits.tryAcquire()) { "Automation host busy" }
        try {
            val app = requireNotNull(context)
            val result = McpAutomationTransport.local(method, JsonParser().parse(request).asJsonObject).toString()
            check(result.toByteArray(Charsets.UTF_8).size <= McpAutomationTransport.MAX_BYTES) { "Automation result too large" }
            app.cacheDir.listFiles { file -> file.name.startsWith("mcp-automation-") && file.name.endsWith(".json") }
                ?.filter { System.currentTimeMillis() - it.lastModified() > 300_000L }?.forEach { it.delete() }
            val file = File(app.cacheDir, "mcp-automation-${UUID.randomUUID()}.json")
            try { file.writeText(result, Charsets.UTF_8) }
            catch (error: Exception) { file.delete(); throw error }
            return Bundle().apply { putString("result", file.name) }
        } finally { permits.release() }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = throw UnsupportedOperationException()
}

internal object McpAutomationTransport {
    const val MAX_BYTES = 48 * 1024 * 1024
    private val names = setOf("get_ui_layout", "capture_screen", "ui_find", "ui_click", "ui_long_click", "ui_node_action",
        "ui_input", "ui_swipe", "ui_global", "ui_wait", "ui_scroll")
    fun supports(name: String) = name in names
    fun call(context: Context, name: String, args: JsonObject): JsonObject {
        if (ScriptProcessIdentity.suffix(context) == ":script") return local(name, args)
        check(Looper.myLooper() != Looper.getMainLooper()) { "Automation IPC must run off the UI thread" }
        val result = context.contentResolver.call(Uri.parse("content://${context.packageName}.mcp.automation"), name, null,
            Bundle().apply { putString("arguments", args.toString()) }) ?: error("Automation host unavailable")
        val token = result.getString("result") ?: error("Automation result missing")
        require(token.matches(Regex("mcp-automation-[a-f0-9-]{36}\\.json")))
        val file = File(context.cacheDir, token).canonicalFile
        require(file.parentFile == context.cacheDir.canonicalFile && file.isFile && file.length() <= MAX_BYTES)
        try { return JsonParser().parse(file.readText(Charsets.UTF_8)).asJsonObject }
        finally { file.delete() }
    }

    fun local(name: String, args: JsonObject): JsonObject {
        fun str(key: String, fallback: String = "") = args.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: fallback
        fun int(key: String, fallback: Int) = args.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: fallback
        fun bool(key: String, fallback: Boolean) = args.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: fallback
        if (name == "capture_screen") return McpDeviceTools.screenshot(str("format", "png").lowercase(), int("quality", 100),
            bool("dataUri", false), int("maxWidth", 0), int("timeoutMillis", 3000))
        val value = when (name) {
            "get_ui_layout" -> McpDeviceTools.layout(if (args.has("windowId")) int("windowId", -1) else null,
                int("maxNodes", 3000), int("maxDepth", 64), int("timeoutMillis", 3000))
            "ui_find" -> McpUiActions.find(args, int("limit", 20))
            "ui_click" -> McpUiActions.click(args)
            "ui_long_click" -> McpUiActions.longClick(args)
            "ui_node_action" -> McpUiActions.nodeAction(args, str("action"), args.getAsJsonObject("args"))
            "ui_input" -> McpUiActions.input(args, str("text"))
            "ui_swipe" -> McpUiActions.swipe(args)
            "ui_global" -> McpUiActions.global(str("action"))
            "ui_wait" -> McpUiActions.waitFor(args, int("timeoutMillis", 3000), int("intervalMillis", 100))
            "ui_scroll" -> McpUiActions.scroll(args)
            else -> error("Unknown automation tool")
        }
        return JsonObject().apply {
            add("structuredContent", value)
            add("content", JsonArray().apply { add(JsonObject().apply { addProperty("type", "text"); addProperty("text", value.toString()) }) })
        }
    }
}
