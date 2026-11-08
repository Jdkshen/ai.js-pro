package com.jdkshen.aijspro.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.security.MessageDigest

/**
 * 有界 Frida 会话登记与消息缓冲（纯逻辑）。
 *
 * 本类不创建进程、不 attach、不执行 Frida 脚本；真实 attach/eval 由接线层注入函数按
 * sessionId 执行。这里只负责会话绑定、TTL、消息序号/分页/截断、上限和清理语义，
 * 便于 JVM 单测和 MCP 工具层复用。
 *
 * 重复 open 明确采用“拒绝”策略：同一 packageName+pid 的活动会话存在时拒绝；任一侧 pid
 * 未知时按 packageName 判断。调用方必须先 stop(sessionId) 或等待 TTL 过期。理由：Frida 的
 * attach/脚本卸载必须绑定唯一目标，自动替换会让旧脚本的 detach/清理责任丢失，且可能把旧消息混入新会话。
 */
internal class ToolkitFridaSession(
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = 60_000,
    private val maxSessions: Int = 2,
    private val maxMessages: Int = 500,
    private val maxMessageChars: Int = 4096,
) {
    data class Target(
        val packageName: String,
        val pid: Int?,
        val endpoint: String?,
        val token: String?,
    )

    data class Message(
        val seq: Long,
        val at: Long,
        val level: String,
        val text: String,
    )

    private data class StoredMessage(
        val message: Message,
        val originalChars: Int,
        val truncated: Boolean,
    )

    private data class Session(
        val id: String,
        val target: Target,
        val script: String,
        val scriptSha256: String,
        val createdAt: Long,
        val expiresAt: Long,
        val messages: MutableList<StoredMessage> = ArrayList(),
        var nextSeq: Long = 1L,
        var truncatedMessages: Int = 0,
    )

    private data class PurgeStats(
        var sessions: Int = 0,
        var messages: Int = 0,
    )

    private val lock = Any()
    private val sessions = LinkedHashMap<String, Session>()

    init {
        require(ttlMillis > 0) { "ttlMillis 必须大于 0" }
        require(maxSessions in 1..10_000) { "maxSessions 必须在 1..10000" }
        require(maxMessages in 1..1_000_000) { "maxMessages 必须在 1..1000000" }
        require(maxMessageChars in 1..4_194_304) { "maxMessageChars 必须在 1..4194304" }
    }

    /** 返回 sessionId，绑定 pid/包名/endpoint；脚本只保留摘要，不在响应中回显全文。 */
    fun open(target: Target, script: String): JsonObject = synchronized(lock) {
        val normalized = normalizeTarget(target)
        requireScript(script)
        val now = clock()
        val purge = pruneExpired(now)
        val duplicate = sessions.values.firstOrNull { sameTarget(it.target, normalized) }
        if (duplicate != null) {
            throw IllegalArgumentException(
                "同一 pid/包名的活动 Frida 会话已存在（" + duplicate.id +
                    "）；重复 open 被拒绝以避免 attach/脚本卸载归属歧义，请先 stop(sessionId) 或等待 TTL 过期",
            )
        }
        require(sessions.size < maxSessions) {
            "Frida 会话数已达上限 " + maxSessions + "；请先 stop(sessionId) 或等待 TTL(" + ttlMillis + "ms) 过期"
        }
        val id = uniqueId()
        val scriptHash = sha256(script)
        val session = Session(
            id = id,
            target = normalized,
            script = script,
            scriptSha256 = scriptHash,
            createdAt = now,
            expiresAt = now + ttlMillis,
        )
        sessions[id] = session
        val maskedEndpoint = maskEndpoint(normalized.endpoint)
        val maskedToken = maskToken(normalized.token)
        ToolkitIo.json(
            "sessionId" to id,
            "state" to "open",
            "packageName" to normalized.packageName,
            "pid" to normalized.pid,
            "endpoint" to maskedEndpoint,
            "endpointMasked" to maskedEndpoint,
            "token" to maskedToken,
            "tokenMasked" to maskedToken,
            "tokenPresent" to (normalized.token != null),
            "target" to targetJson(normalized),
            "createdAt" to now,
            "expiresAt" to session.expiresAt,
            "ttlMillis" to ttlMillis,
            "expiresInMillis" to ttlMillis,
            "scriptChars" to script.length,
            "scriptBytes" to script.toByteArray(Charsets.UTF_8).size,
            "scriptSha256" to scriptHash,
            "scriptRetained" to true,
            "messageCount" to 0,
            "maxMessages" to maxMessages,
            "maxMessageChars" to maxMessageChars,
            "sessions" to sessions.size,
            "maxSessions" to maxSessions,
            "expiredSessionsPurged" to purge.sessions,
            "expiredMessagesPurged" to purge.messages,
            "duplicatePolicy" to "reject-active-same-package-and-pid",
        )
    }

    /** 追加一条有界消息；超过 maxMessageChars 的单条按 UTF-16 边界截断并标注。 */
    fun append(sessionId: String, level: String, text: String): JsonObject = synchronized(lock) {
        requireSessionId(sessionId)
        val now = clock()
        pruneExpired(now)
        val session = sessions[sessionId]
            ?: throw IllegalArgumentException("Frida 会话不存在或已过期：" + sessionId + "；请重新 open")
        require(level.isNotBlank() && level.length <= 32 && level.all {
            it.isLetterOrDigit() || it == '_' || it == '-' || it == '.'
        }) { "level 必须为 1..32 位字母、数字、下划线、横线或点" }
        require(session.messages.size < maxMessages) {
            "会话消息数已达上限 " + maxMessages + "；请先 read/stop，不能无界堆积"
        }
        val originalChars = text.length
        val truncated = originalChars > maxMessageChars
        val storedText = if (truncated) truncateUtf16(text, maxMessageChars) else text
        val seq = session.nextSeq
        session.nextSeq++
        val message = Message(seq, now, level.lowercase(), storedText)
        session.messages.add(StoredMessage(message, originalChars, truncated))
        if (truncated) session.truncatedMessages++
        val messageJson = storedMessageJson(StoredMessage(message, originalChars, truncated))
        ToolkitIo.json(
            "sessionId" to sessionId,
            "state" to "open",
            "seq" to seq,
            "at" to now,
            "level" to message.level,
            "text" to storedText,
            "truncated" to truncated,
            "originalChars" to originalChars,
            "storedChars" to storedText.length,
            "messageCount" to session.messages.size,
            "maxMessages" to maxMessages,
            "messagesRemaining" to (maxMessages - session.messages.size),
            "message" to messageJson,
        )
    }

    /** 分页读取消息；offset 从 0 开始，limit 会被收敛到 maxMessages。 */
    fun read(sessionId: String, offset: Int, limit: Int): JsonObject = synchronized(lock) {
        requireSessionId(sessionId)
        require(offset >= 0) { "offset 不能为负" }
        require(limit >= 1) { "limit 必须大于 0" }
        val now = clock()
        pruneExpired(now)
        val session = sessions[sessionId]
            ?: throw IllegalArgumentException("Frida 会话不存在或已过期：" + sessionId + "；请重新 open")
        val total = session.messages.size
        require(offset <= total) { "offset 超出消息范围：offset=" + offset + ", total=" + total }
        val effectiveLimit = minOf(limit, maxMessages)
        val end = minOf(total, offset + effectiveLimit)
        val page = JsonArray()
        val pageLimited = end < total
        var pageTruncated = false
        for (index in offset until end) {
            val stored = session.messages[index]
            if (stored.truncated) pageTruncated = true
            page.add(storedMessageJson(stored))
        }
        ToolkitIo.json(
            "sessionId" to sessionId,
            "state" to "open",
            "messages" to page,
            "items" to page,
            "offset" to offset,
            "limit" to effectiveLimit,
            "requestedLimit" to limit,
            "nextOffset" to end,
            "hasMore" to (end < total),
            "total" to total,
            "messageCount" to total,
            "truncated" to (pageLimited || session.truncatedMessages > 0),
            "pageLimited" to pageLimited,
            "messageTruncated" to (session.truncatedMessages > 0),
            "pageTruncated" to pageTruncated,
            "truncatedMessages" to session.truncatedMessages,
            "maxMessages" to maxMessages,
            "maxMessageChars" to maxMessageChars,
            "expiresAt" to session.expiresAt,
            "now" to now,
        )
    }

    /** 停止并清理会话；返回真实释放的消息条数与会话状态（不包含完整 token/endpoint）。 */
    fun stop(sessionId: String): JsonObject = synchronized(lock) {
        requireSessionId(sessionId)
        val now = clock()
        val session = sessions[sessionId]
        if (session == null) {
            val purge = pruneExpired(now)
            return ToolkitIo.json(
                "sessionId" to sessionId,
                "state" to "not_found",
                "stopped" to false,
                "releasedMessages" to 0,
                "releasedSessions" to 0,
                "remainingSessions" to sessions.size,
                "expiredSessionsPurged" to purge.sessions,
                "expiredMessagesPurged" to purge.messages,
                "now" to now,
            )
        }
        sessions.remove(sessionId)
        val expired = isExpired(session, now)
        val purge = pruneExpired(now)
        ToolkitIo.json(
            "sessionId" to sessionId,
            "state" to if (expired) "expired" else "stopped",
            "stopped" to !expired,
            "releasedMessages" to session.messages.size,
            "releasedSessions" to 1,
            "remainingSessions" to sessions.size,
            "messageCountBeforeClose" to session.messages.size,
            "truncatedMessagesReleased" to session.truncatedMessages,
            "target" to targetJson(session.target),
            "closedAt" to now,
            "expiredSessionsPurged" to purge.sessions,
            "expiredMessagesPurged" to purge.messages,
            "now" to now,
        )
    }

    /** 列出活动会话；过期会话会在这里被真实清理。 */
    fun list(): JsonObject = synchronized(lock) {
        val now = clock()
        val purge = pruneExpired(now)
        val items = JsonArray()
        val sessionsAlias = JsonArray()
        sessions.values.sortedBy { it.createdAt }.forEach { session ->
            val item = sessionJson(session, now)
            items.add(item)
            sessionsAlias.add(item.deepCopy())
        }
        ToolkitIo.json(
            "items" to items,
            "sessions" to sessionsAlias,
            "count" to sessions.size,
            "maxSessions" to maxSessions,
            "ttlMillis" to ttlMillis,
            "now" to now,
            "expiredSessionsPurged" to purge.sessions,
            "expiredMessagesPurged" to purge.messages,
            "limits" to ToolkitIo.json(
                "maxSessions" to maxSessions,
                "maxMessages" to maxMessages,
                "maxMessageChars" to maxMessageChars,
                "ttlMillis" to ttlMillis,
            ),
            "duplicatePolicy" to "reject-active-same-pid-or-package",
        )
    }

    /**
     * Java Bridge 可用性。componentPresent=false 时必须明确：不能声称 Java.perform 可用；
     * 组件存在也不等于已实测可用，verified 始终为 false，需要接线层实际加载/调用后再确认。
     */
    fun javaBridgeStatus(componentPresent: Boolean, loadError: String?): JsonObject {
        val error = loadError?.trim()?.take(1000)?.takeIf { it.isNotEmpty() }
        val status = when {
            !componentPresent -> "missing"
            error != null -> "load_error"
            else -> "present"
        }
        val javaPerformAvailable = componentPresent && error == null
        val message = when {
            !componentPresent ->
                "frida-java-bridge 组件不可用（componentPresent=false）；不能声称 Java.perform 可用，即使 native Frida 客户端存在也不代表 Java Bridge 已加载"
            error != null ->
                "frida-java-bridge 组件存在但加载失败：" + error + "；不能声称 Java.perform 可用"
            else ->
                "frida-java-bridge 组件存在；Java.perform 是否可用仍须在目标进程实际加载/调用验证，不能仅凭组件存在声称已验证"
        }
        return ToolkitIo.json(
            "component" to "frida-java-bridge",
            "javaBridgeComponent" to "frida-java-bridge",
            "componentPresent" to componentPresent,
            "javaBridgePresent" to componentPresent,
            "javaPerformAvailable" to javaPerformAvailable,
            "javaPerformVerified" to false,
            "verified" to false,
            "status" to status,
            "loadError" to error,
            "requiresRuntimeProbe" to true,
            "message" to message,
            "claim" to if (javaPerformAvailable) {
                "Java.perform 组件存在，但未在本请求中验证"
            } else {
                "不能声称 Java.perform 可用"
            },
            "nextActions" to JsonArray().apply {
                add(ToolkitIo.json("tool" to "apk_component_status", "hint" to "核对 frida-java-bridge 组件是否已安装"))
                add(ToolkitIo.json("tool" to "frida_eval", "hint" to "真实 Java.perform 需要目标进程内的 java bridge 脚本与组件"))
            },
        )
    }

    // ------------------------------------------------------------------
    // 内部：校验、TTL、掩码与 JSON
    // ------------------------------------------------------------------

    private fun normalizeTarget(target: Target): Target {
        val packageName = target.packageName.trim()
        require(packageName.isNotEmpty() && packageName.length <= 255) { "packageName 不能为空且不能超过 255 字符" }
        val parts = packageName.split('.')
        require(parts.isNotEmpty() && parts.all { part ->
            part.isNotEmpty() && part.length <= 63 && part.all { c -> c.isLetterOrDigit() || c == '_' }
        }) { "packageName 格式无效：应为点分 Android 包名" }
        // W4 task-21：pid 是**可选覆盖**（省略时按 packageName 解析目标进程）。旧 schema 曾给 pid 一个
        // default=1，那等于「省略就绑 init」，没有任何可用语义 —— 已去掉该 default，并在这里把 pid<=1 明确拒绝。
        target.pid?.let { require(it >= 2) { "pid 必须是 >=2 的进程号（pid 1 是 init，不是目标应用进程）；省略 pid 时按 packageName 解析会话目标" } }
        val endpoint = target.endpoint?.trim()?.takeIf { it.isNotEmpty() }
        endpoint?.let { validateEndpoint(it) }
        val token = target.token?.trim()?.takeIf { it.isNotEmpty() }
        token?.let {
            require(it.length <= 512 && it.none { c -> c.code < 32 || c.code == 127 }) { "token 不能包含控制字符且不能超过 512 字符" }
        }
        return Target(packageName = packageName, pid = target.pid, endpoint = endpoint, token = token)
    }

    private fun validateEndpoint(endpoint: String) {
        val colon = endpoint.lastIndexOf(':')
        require(colon in 1 until endpoint.length - 1) { "endpoint 必须为 host:port" }
        val host = endpoint.substring(0, colon)
        val portText = endpoint.substring(colon + 1)
        require(host.length <= 253 && host.all { c -> c.isLetterOrDigit() || c == '.' || c == '_' || c == '-' }) {
            "endpoint host 无效"
        }
        require(portText.isNotEmpty() && portText.length <= 5 && portText.all { it.isDigit() }) { "endpoint port 无效" }
        val port = portText.toIntOrNull() ?: throw IllegalArgumentException("endpoint port 无效")
        require(port in 1..65535) { "endpoint port 必须在 1..65535" }
    }

    private fun requireScript(script: String) {
        require(script.toByteArray(Charsets.UTF_8).size <= 65536) { "Frida 脚本不能超过 64KiB" }
    }

    private fun requireSessionId(sessionId: String) {
        require(sessionId.isNotEmpty() && sessionId.length <= 128 && sessionId.none { it.code < 32 || it.code == 127 }) {
            "sessionId 不能为空、不能含控制字符且不能超过 128 字符"
        }
    }

    private fun sameTarget(existing: Target, incoming: Target): Boolean {
        if (existing.pid != null && incoming.pid != null) return existing.pid == incoming.pid
        return existing.packageName == incoming.packageName
    }

    private fun uniqueId(): String {
        var id: String
        do {
            id = ToolkitIo.fresh()
        } while (sessions.containsKey(id))
        return id
    }

    private fun isExpired(session: Session, now: Long): Boolean = now >= session.expiresAt

    private fun pruneExpired(now: Long): PurgeStats {
        val stats = PurgeStats()
        val iterator = sessions.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (isExpired(entry.value, now)) {
                stats.sessions++
                stats.messages += entry.value.messages.size
                iterator.remove()
            }
        }
        return stats
    }

    private fun truncateUtf16(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        var end = maxChars
        if (end in 1 until text.length &&
            Character.isHighSurrogate(text[end - 1]) &&
            Character.isLowSurrogate(text[end])
        ) {
            end--
        }
        if (end <= 0) end = maxChars
        return text.substring(0, end)
    }

    private fun maskToken(token: String?): String? {
        if (token == null || token.isBlank()) return null
        if (token.length <= 4) return "****"
        val dash = token.indexOf('-')
        val suffix = token.takeLast(4)
        return if (dash in 1..8) token.substring(0, dash) + "-****" + suffix else "****" + suffix
    }

    private fun maskEndpoint(endpoint: String?): String? {
        if (endpoint == null || endpoint.isBlank()) return null
        val colon = endpoint.lastIndexOf(':')
        if (colon <= 0 || colon == endpoint.length - 1) return "***"
        val host = endpoint.substring(0, colon)
        val port = endpoint.substring(colon + 1)
        val maskedHost = when {
            host.length <= 1 -> "***"
            host.length == 2 -> host.first() + "***"
            else -> host.first() + "***" + host.last()
        }
        return maskedHost + ":" + port
    }

    private fun targetJson(target: Target): JsonObject = ToolkitIo.json(
        "packageName" to target.packageName,
        "pid" to target.pid,
        "endpoint" to maskEndpoint(target.endpoint),
        "endpointMasked" to maskEndpoint(target.endpoint),
        "token" to maskToken(target.token),
        "tokenMasked" to maskToken(target.token),
        "tokenPresent" to (target.token != null),
    )

    private fun sessionJson(session: Session, now: Long): JsonObject = ToolkitIo.json(
        "sessionId" to session.id,
        "id" to session.id,
        "state" to "open",
        "target" to targetJson(session.target),
        "packageName" to session.target.packageName,
        "pid" to session.target.pid,
        "endpoint" to maskEndpoint(session.target.endpoint),
        "endpointMasked" to maskEndpoint(session.target.endpoint),
        "token" to maskToken(session.target.token),
        "tokenMasked" to maskToken(session.target.token),
        "tokenPresent" to (session.target.token != null),
        "createdAt" to session.createdAt,
        "expiresAt" to session.expiresAt,
        "ageMillis" to (now - session.createdAt),
        "remainingMillis" to (session.expiresAt - now).coerceAtLeast(0L),
        "messageCount" to session.messages.size,
        "truncatedMessages" to session.truncatedMessages,
        "scriptChars" to session.script.length,
        "scriptSha256" to session.scriptSha256,
    )

    private fun storedMessageJson(stored: StoredMessage): JsonObject = ToolkitIo.json(
        "seq" to stored.message.seq,
        "at" to stored.message.at,
        "level" to stored.message.level,
        "text" to stored.message.text,
        "truncated" to stored.truncated,
        "originalChars" to stored.originalChars,
        "storedChars" to stored.message.text.length,
    )

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }
}
