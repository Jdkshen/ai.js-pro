package com.jdkshen.aijspro.mcp
import com.google.gson.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
object ToolkitIo {
    private val gson = GsonBuilder().serializeNulls().disableHtmlEscaping().create()
    fun json(vararg values: Pair<String, Any?>): JsonObject = JsonObject().apply {
        values.forEach { (k, v) -> add(k, if (v is JsonElement) v else gson.toJsonTree(v)) }
    }
    fun fresh(): String = UUID.randomUUID().toString()
    fun sha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buf = ByteArray(65536); while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    fun atomic(file: File, writer: (File) -> Unit) {
        file.parentFile?.mkdirs()
        val temp = File.createTempFile(".tmp-", ".json", file.parentFile)
        try { writer(temp); if (file.exists()) file.delete(); temp.renameTo(file) } finally { temp.delete() }
    }
    fun resolve(root: File, value: String, empty: Boolean = false): File {
        require(empty || value.isNotEmpty())
        val base = root.canonicalFile
        val candidate = (if (File(value).isAbsolute) File(value) else File(base, value)).canonicalFile
        require(candidate == base || candidate.path.startsWith(base.path + File.separator)) { "path outside" }
        return candidate
    }
}
