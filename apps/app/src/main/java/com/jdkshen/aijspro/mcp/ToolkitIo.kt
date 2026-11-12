package com.jdkshen.aijspro.mcp

import com.google.gson.*
import java.io.*
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID

/** Shared bounds for the native APK toolkit, independent of MT and Android UI. */
internal object ToolkitIo {
    const val MAX_ENTRY = 64 * 1024 * 1024
    const val MAX_TEXT = 10 * 1024 * 1024
    const val MAX_APK = 512L * 1024 * 1024
    private val gson = GsonBuilder().serializeNulls().disableHtmlEscaping().create()
    fun json(vararg values: Pair<String, Any?>): JsonObject = JsonObject().apply {
        values.forEach { (key, value) -> add(key, if(value is JsonElement) value else gson.toJsonTree(value)) }
    }
    fun ok(data: JsonElement, actions: JsonArray = JsonArray()) = json(
        "ok" to true, "data" to data, "error" to null, "nextActions" to actions)

    /**
     * P3-1 契约 v1：失败信封（与 ok() 对称）。
     *
     * 约定（Lead 冻结 + W4 消费方反馈）：
     *  · message 是**权威**字段；error **显式**镜像 message（兼容过渡：现有 Dart 读 error）——
     *    这里刻意不依赖「ok() 反正写了 error=null」这种隐含假设，显式写死并加断言钉住；
     *  · reasonCode 用骨架/通用码（invalidArg/internal/notFound/permission/unsupported/busy/timeout/network）
     *    或工具领域码（checksum/package/missing/source/manifest…须登记+单测）；
     *  · 失败判据沿用既有 isError=true（由调用方在外层按需设置，不新造第二个失败标志）。
     */
    fun fail(reasonCode: String, message: String, data: JsonElement? = null, actions: JsonArray = JsonArray()) = json(
        "ok" to false, "reasonCode" to reasonCode, "message" to message,
        "error" to message, "data" to data, "nextActions" to actions)

    /**
     * P3-1 契约 v1：**成功但无结果**（唯一形态）。
     *
     * 判据只看 empty=true（data 可空或缺席）—— 避免「data 空集合」与「字段缺失」混淆
     * （Phase 1 复查第 1/8 条的病根）。失败**不得**用本方法表达。
     */
    fun empty(actions: JsonArray = JsonArray(), note: String? = null) = json(
        "ok" to true, "empty" to true, "data" to null, "error" to null,
        "message" to note, "nextActions" to actions)
    fun ensureActive() { if(Thread.currentThread().isInterrupted) throw InterruptedIOException("操作已取消") }
    fun sha(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        while (true) { ensureActive(); val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
        hex(digest.digest()).lowercase()
    }
    fun version(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes)).lowercase()
    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02X".format(it.toInt() and 255) }
    fun unhex(value: String): ByteArray {
        require(value.length % 2 == 0 && value.length <= MAX_ENTRY * 2 && value.all { it in "0123456789abcdefABCDEF" }) { "十六进制字节无效" }
        return ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
    fun read(input: InputStream, max: Int = MAX_ENTRY): ByteArray = input.use {
        val out = ByteArrayOutputStream(); val buffer = ByteArray(65536)
        while (true) {
            ensureActive(); val size = it.read(buffer); if (size < 0) break
            require(out.size().toLong() + size <= max) { "内容超过本次处理上限 $max 字节" }
            out.write(buffer, 0, size)
        }; out.toByteArray()
    }
    fun text(bytes: ByteArray): String {
        require(bytes.size <= MAX_TEXT) { "文本超过 10 MiB" }
        return Charset.forName("UTF-8").newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }
    fun resolve(root: File, value: String, empty: Boolean = false): File {
        require((empty || value.isNotEmpty()) && !value.contains('\u0000') && !value.contains('\\') && !value.contains("://")) { "路径无效" }
        val base = root.canonicalFile
        val absolute = (if (File(value).isAbsolute) File(value) else File(base, value)).absoluteFile.normalize()
        val canonical = absolute.canonicalFile
        require(canonical == base || canonical.path.startsWith(base.path + File.separator)) { "路径超出工具目录" }
        require(canonical == absolute) { "不接受符号链接路径" }
        return canonical
    }
    fun entry(value: String): String {
        require(value.isNotEmpty() && !value.startsWith('/') && !value.contains('\\') && !value.contains('\u0000') &&
            value.split('/').none { it == ".." || it == "." } && !value.contains(':')) { "ZIP 条目路径无效" }
        return value
    }
    /**
     * 校验 UUID 形状的标识符。
     *
     * [label] 决定报错里点名的对象：这里历史上写死了“工作区或会话 ID”，于是 **jobId** 形状不对时
     * 也会被说成工作区/会话（用户反馈 dev-1365 bug#10）。调用方应按实际对象传 label。
     */
    fun id(value: String, label: String = "工作区或会话 ID"): String {
        require(value.matches(Regex("[0-9a-f]{8}-[0-9a-f-]{27}"))) {
            label + " 无效：需要 UUID 形状（8-4-4-4-12 十六进制），本次收到 \"" + value.take(64) + "\"。" +
                "jobId 请用 apk_job_list 或上一次调用返回的完整 id；工作区/会话 id 请用 apk_workspace_open / apk_session_create 的返回值。"
        }
        return value
    }
    fun fresh() = UUID.randomUUID().toString()
    private class FileLock { val lock=java.util.concurrent.locks.ReentrantLock();var users=0 }
    private val fileLocks=HashMap<String,FileLock>()
    fun <T> withFileLocks(files:List<File>, action:()->T):T {
        val held=ArrayList<Pair<String,FileLock>>()
        fun releaseRef(key:String,ref:FileLock){synchronized(fileLocks){ref.users--;if(ref.users==0)fileLocks.remove(key)}}
        try {
            files.map{it.canonicalPath}.distinct().sorted().forEach{key->
                val ref=synchronized(fileLocks){fileLocks.getOrPut(key){FileLock()}.also{it.users++}}
                try{ref.lock.lockInterruptibly()}catch(e:InterruptedException){releaseRef(key,ref);throw e}
                held.add(key to ref)
            }
            return action()
        }finally{held.asReversed().forEach{(key,ref)->ref.lock.unlock();releaseRef(key,ref)}}
    }
    fun atomic(file: File, writer: (File) -> Unit) = withFileLocks(listOf(file)) { atomicUnlocked(file,writer) }
    private fun atomicUnlocked(file: File, writer: (File) -> Unit) {
        require(file.parentFile?.isDirectory == true) { "目标父目录不存在" }
        val temp = File.createTempFile(".aijs-tool-", ".tmp", file.parentFile)
        val backup = File(file.parentFile, ".aijs-old-${fresh()}")
        try {
            ensureActive(); writer(temp); ensureActive()
            if (file.exists()) require(file.renameTo(backup)) { "无法保留原文件" }
            if (!temp.renameTo(file)) {
                if (backup.exists()) backup.renameTo(file)
                throw IOException("无法原子替换目标文件")
            }
            if (backup.exists() && !backup.delete()) throw IOException("文件已写入，但旧文件清理失败：${backup.name}")
        } finally { temp.delete() }
    }
}

internal fun JsonObject.tString(key: String, default: String? = null): String {
    val value = get(key) ?: return default ?: throw IllegalArgumentException("缺少 $key")
    require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "$key 必须为字符串" }; return value.asString
}
internal fun JsonObject.tInt(key: String, default: Int, min: Int = 0, max: Int = Int.MAX_VALUE): Int {
    val value = get(key) ?: return default.coerceIn(min, max)
    require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber && value.asString.matches(Regex("-?\\d+"))) { "$key 必须为整数" }
    val n = value.asString.toLong(); require(n in min.toLong()..max.toLong()) { "$key 必须在 $min..$max" }; return n.toInt()
}
internal fun JsonObject.tBool(key: String, default: Boolean = false): Boolean {
    val value = get(key) ?: return default
    require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) { "$key 必须为布尔值" }; return value.asBoolean
}
