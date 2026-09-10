package com.jdkshen.aijspro.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.StringReader
import java.math.BigDecimal
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Stateless Streamable HTTP: one JSON response per POST, with no SSE/session allocation. */
class McpHttpServer(
    val host: String,
    val port: Int,
    val token: String,
    val allowUnauthenticatedLoopback: Boolean = false,
    val pageProvider: (() -> String)? = null,
    /** 危险选项：局域网(非 loopback)请求也不校验令牌。 */
    val allowUnauthenticatedLan: Boolean = false,
    val handler: (JsonObject) -> JsonObject?
) {
    private val tokenDigest = sha256(token)
    private val connections = ConcurrentHashMap.newKeySet<Socket>()
    @Volatile private var listener: ServerSocket? = null
    private var workers: ThreadPoolExecutor? = null
    private var acceptThread: Thread? = null

    /** The bound port; supports port zero for isolated JVM tests. -1 means stopped. */
    val localPort: Int get() = listener?.localPort ?: -1

    init {
        require(port in 0..65535) { "Invalid MCP port" }
        require(token.isNotEmpty() && token.all { it.code in 0x21..0x7e }) { "Invalid MCP token" }
    }

    /** Binding is synchronous so callers can report address/port conflicts immediately. */
    @Synchronized
    fun start() {
        if (listener != null) return
        val server = ServerSocket()
        try {
            server.reuseAddress = true
            server.bind(InetSocketAddress(InetAddress.getByName(host), port), QUEUED_CONNECTIONS)
        } catch (e: Exception) {
            server.close()
            throw e
        }
        val threadIds = AtomicInteger()
        val executor = ThreadPoolExecutor(
            WORKER_COUNT, WORKER_COUNT, 0L, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(QUEUED_CONNECTIONS),
            { task -> Thread(task, "mcp-http-${threadIds.incrementAndGet()}").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy()
        )
        listener = server
        workers = executor
        acceptThread = Thread({ acceptConnections(server, executor) }, "mcp-http-accept").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        listener?.let { runCatching { it.close() } }
        listener = null
        workers?.shutdownNow()
        workers = null
        connections.forEach { runCatching { it.close() } }
        connections.clear()
        acceptThread?.interrupt()
        acceptThread = null
    }

    private fun acceptConnections(server: ServerSocket, executor: ThreadPoolExecutor) {
        while (!server.isClosed) {
            val socket = try { server.accept() } catch (_: IOException) { break }
            connections.add(socket)
            try {
                if (server.isClosed) {
                    socket.close()
                    connections.remove(socket)
                    break
                }
                socket.soTimeout = READ_TIMEOUT_MS
                executor.execute {
                    try { socket.use { serve(it) } } finally { connections.remove(socket) }
                }
            } catch (_: RejectedExecutionException) {
                runCatching { respond(socket, 503, "Service Unavailable") }
                runCatching { socket.close() }
                connections.remove(socket)
            } catch (_: IOException) {
                runCatching { socket.close() }
                connections.remove(socket)
            }
        }
    }

    private fun serve(socket: Socket) {
        try {
            val input = BufferedInputStream(socket.getInputStream())
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(READ_TIMEOUT_MS.toLong())
            val header = readHeader(socket, input, deadline)
            val lines = header.split("\r\n")
            val requestLine = lines.first().split(' ')
            if (requestLine.size != 3 || requestLine[2] != "HTTP/1.1") throw HttpError(400, "Bad Request")
            val headers = linkedMapOf<String, String>()
            for (line in lines.drop(1)) {
                if (line.isEmpty()) continue
                val separator = line.indexOf(':')
                if (separator <= 0 || !line.substring(0, separator).all { it in HEADER_NAME_CHARS }) {
                    throw HttpError(400, "Bad Request")
                }
                val name = line.substring(0, separator).lowercase(Locale.ROOT)
                val value = line.substring(separator + 1).trim(' ', '\t')
                if (value.any { it.code < 0x20 && it != '\t' } || headers.put(name, value) != null) {
                    throw HttpError(400, "Bad Request")
                }
            }
            if (headers.containsKey("origin")) throw HttpError(403, "Forbidden")
            if (!validHost(headers["host"], socket)) throw HttpError(403, "Forbidden")
            val authorization = headers["authorization"].orEmpty()
            val candidate = if (authorization.startsWith("Bearer ", ignoreCase = true)) authorization.substring(7) else ""
            val authenticated = MessageDigest.isEqual(tokenDigest, sha256(candidate))
            val localCompatibility = allowUnauthenticatedLoopback && authorization.isEmpty() && socket.inetAddress.isLoopbackAddress
            val lanCompatibility = allowUnauthenticatedLan && authorization.isEmpty() && !socket.inetAddress.isLoopbackAddress
            if (!authenticated && !localCompatibility && !lanCompatibility) throw HttpError(401, "Unauthorized")
            val path = requestLine[1].substringBefore('?')
            if (path != "/mcp" && path != "/") throw HttpError(404, "Not Found")
            if (requestLine[0] == "GET") {
                // MCP Streamable HTTP 客户端会先 GET /mcp(Accept: text/event-stream) 探测 SSE；
                // 本服务无 SSE，必须返回 405 让客户端回退到 POST JSON-only 模式。
                // 仅对浏览器类请求(无 text/event-stream)返回只读状态页。
                if (headers["accept"].orEmpty().contains("text/event-stream", ignoreCase = true)) {
                    throw HttpError(405, "Method Not Allowed")
                }
                respondHtml(socket, pageProvider?.invoke() ?: defaultStatusPage())
                return
            }
            if (path != "/mcp") throw HttpError(404, "Not Found")
            val version = headers["mcp-protocol-version"] ?: "2025-03-26"
            if (version !in SUPPORTED_VERSIONS) throw HttpError(400, "Unsupported Protocol Version")
            if (requestLine[0] != "POST") throw HttpError(405, "Method Not Allowed")
            if (headers.containsKey("expect")) throw HttpError(417, "Expectation Failed")
            if (!validContentType(headers["content-type"])) throw HttpError(415, "Unsupported Media Type")
            if (!acceptsResponse(headers["accept"])) throw HttpError(406, "Not Acceptable")
            val transfer = headers["transfer-encoding"]?.lowercase(Locale.ROOT)
            if (transfer != null && transfer != "chunked") throw HttpError(400, "Unsupported Transfer Encoding")
            if (transfer != null && headers.containsKey("content-length")) throw HttpError(400, "Ambiguous Body Framing")
            val body = if (transfer == "chunked") readChunked(socket, input, deadline) else {
                val lengthText = headers["content-length"] ?: throw HttpError(411, "Length Required")
                if (!lengthText.matches(Regex("[0-9]+"))) throw HttpError(400, "Bad Content Length")
                val length = lengthText.toLongOrNull() ?: throw HttpError(413, "Content Too Large")
                if (length > MAX_BODY_BYTES) throw HttpError(413, "Content Too Large")
                readExact(socket, input, deadline, length.toInt())
            }
            val message = try {
                val text = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body)).toString()
                parseJson(text)
            } catch (_: Exception) {
                respondJson(socket, 400, rpcError(null, -32700, "Parse error"))
                return
            }
            if (!message.isJsonObject || !validRequest(message.asJsonObject)) {
                respondJson(socket, 400, rpcError(null, -32600, "Invalid Request"))
                return
            }
            val request = message.asJsonObject
            if (!request.has("id")) {
                try {
                    handler(request)
                    respond(socket, 202, "Accepted")
                } catch (_: Exception) {
                    // Notifications never receive a JSON-RPC response, even on handler failure.
                    respond(socket, 500, "Internal Server Error")
                }
                return
            }
            val response = try {
                handler(request) ?: rpcError(request.get("id"), -32603, "Internal error")
            } catch (_: Exception) {
                rpcError(request.get("id"), -32603, "Internal error")
            }
            respondJson(socket, 200, response)
        } catch (e: HttpError) {
            runCatching { respond(socket, e.status, e.reason) }
        } catch (_: SocketTimeoutException) {
            runCatching { respond(socket, 408, "Request Timeout") }
        } catch (_: IOException) {
            // The peer may disconnect or the service may stop while a request is active.
        }
    }

    private fun readHeader(socket: Socket, input: BufferedInputStream, deadline: Long): String {
        val output = ByteArrayOutputStream()
        var tail = 0
        while (output.size() < MAX_HEADER_BYTES) {
            updateTimeout(socket, deadline)
            val byte = input.read()
            if (byte < 0) throw HttpError(400, "Incomplete Header")
            if (byte > 0x7e || (byte < 0x20 && byte != 9 && byte != 10 && byte != 13)) {
                throw HttpError(400, "Bad Header")
            }
            output.write(byte)
            tail = (tail shl 8) or byte
            if (tail == 0x0d0a0d0a) return output.toString("US-ASCII").dropLast(4)
        }
        throw HttpError(431, "Request Header Fields Too Large")
    }

    private fun updateTimeout(socket: Socket, deadline: Long) {
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) throw SocketTimeoutException()
        socket.soTimeout = TimeUnit.NANOSECONDS.toMillis(remaining).coerceIn(1, READ_TIMEOUT_MS.toLong()).toInt()
    }

    private fun readExact(socket: Socket, input: BufferedInputStream, deadline: Long, size: Int): ByteArray {
        val body = ByteArray(size)
        var read = 0
        while (read < size) {
            updateTimeout(socket, deadline)
            val count = input.read(body, read, size - read)
            if (count < 0) throw HttpError(400, "Incomplete Body")
            read += count
        }
        return body
    }

    private fun readChunked(socket: Socket, input: BufferedInputStream, deadline: Long): ByteArray {
        val output = ByteArrayOutputStream()
        while (true) {
            val line = readAsciiLine(socket, input, deadline, MAX_CHUNK_LINE_BYTES)
            val sizeText = line.substringBefore(';').trim()
            if (!sizeText.matches(Regex("[0-9a-fA-F]+"))) throw HttpError(400, "Bad Chunk Size")
            val size = sizeText.toLongOrNull(16) ?: throw HttpError(413, "Content Too Large")
            if (size > MAX_BODY_BYTES || output.size().toLong() + size > MAX_BODY_BYTES) throw HttpError(413, "Content Too Large")
            if (size == 0L) {
                // Trailers are unnecessary for MCP and rejecting them avoids a second header parser.
                if (readAsciiLine(socket, input, deadline, MAX_CHUNK_LINE_BYTES).isNotEmpty()) throw HttpError(400, "Chunk Trailers Not Supported")
                return output.toByteArray()
            }
            output.write(readExact(socket, input, deadline, size.toInt()))
            if (readAsciiLine(socket, input, deadline, 2).isNotEmpty()) throw HttpError(400, "Bad Chunk Ending")
        }
    }

    private fun readAsciiLine(socket: Socket, input: BufferedInputStream, deadline: Long, max: Int): String {
        val output = ByteArrayOutputStream()
        var previous = -1
        while (output.size() <= max) {
            updateTimeout(socket, deadline)
            val byte = input.read()
            if (byte < 0) throw HttpError(400, "Incomplete Body")
            if (previous == 13 && byte == 10) return output.toString("US-ASCII").dropLast(1)
            if (byte > 0x7e || (byte < 0x20 && byte != 13)) throw HttpError(400, "Bad Chunk Framing")
            output.write(byte); previous = byte
        }
        throw HttpError(400, "Chunk Line Too Long")
    }

    private fun validHost(value: String?, socket: Socket): Boolean {
        if (value.isNullOrEmpty()) return false
        val hostName: String
        val portText: String?
        if (value.startsWith('[')) {
            val bracket = value.indexOf(']')
            if (bracket < 0) return false
            hostName = value.substring(1, bracket)
            if (!hostName.contains(':')) return false
            val suffix = value.substring(bracket + 1)
            if (suffix.isNotEmpty() && !suffix.startsWith(':')) return false
            portText = if (suffix.isEmpty()) null else suffix.substring(1)
        } else {
            if (value.count { it == ':' } > 1) return false
            hostName = value.substringBefore(':')
            portText = if (value.contains(':')) value.substringAfter(':') else null
        }
        // ADB/USB forwarding rewrites the destination port but keeps the original Host header
        // (for example 127.0.0.1:18790 -> device:8788). The host literal is the security
        // boundary here; requiring the forwarded port to equal the device port breaks every
        // normal desktop MCP client without adding DNS-rebinding protection.
        if (portText != null && (!portText.matches(Regex("[0-9]+")) || portText.toIntOrNull() !in 1..65535)) return false
        if (hostName.equals("localhost", ignoreCase = true)) return true
        // Only IP literals enter getByName: incoming Host values must never trigger DNS.
        val ipv4 = hostName.matches(Regex("[0-9]+(?:\\.[0-9]+){3}")) &&
            hostName.split('.').all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }
        val ipv6 = hostName.contains(':') && hostName.matches(Regex("[0-9a-fA-F:]+"))
        if (!ipv4 && !ipv6) return false
        return try {
            val address = InetAddress.getByName(hostName)
            address.isLoopbackAddress || address == socket.localAddress
        } catch (_: Exception) { false }
    }

    private fun validContentType(value: String?): Boolean {
        val pieces = value?.split(';')?.map { it.trim().lowercase(Locale.ROOT) } ?: return false
        return pieces.first() == "application/json" && pieces.drop(1).all {
            it == "charset=utf-8" || it == "charset=\"utf-8\""
        }
    }

    private fun acceptsResponse(value: String?): Boolean {
        val types = value?.split(',')?.mapNotNull { item ->
            val parts = item.trim().lowercase(Locale.ROOT).split(';').map { it.trim() }
            val quality = parts.drop(1).firstOrNull { it.startsWith("q=") }?.substring(2)?.toDoubleOrNull() ?: 1.0
            parts.first().takeIf { quality > 0.0 }
        }.orEmpty()
        // Streamable HTTP clients should advertise JSON and SSE. This server intentionally has
        // no server-initiated SSE stream, so also accept the common JSON-only and wildcard forms
        // used by CLI bridges and older MCP SDKs.
        return types.any { it == "application/json" || it == "application/*" || it == "*/*" }
    }

    private fun validRequest(request: JsonObject): Boolean {
        val version = request.get("jsonrpc")
        val method = request.get("method")
        if (version == null || !version.isJsonPrimitive || !version.asJsonPrimitive.isString || version.asString != "2.0") return false
        if (method == null || !method.isJsonPrimitive || !method.asJsonPrimitive.isString || method.asString.isEmpty()) return false
        if (request.has("result") || request.has("error")) return false
        val params = request.get("params")
        if (params != null && !params.isJsonObject && !params.isJsonArray) return false
        val id = request.get("id") ?: return method.asString.startsWith("notifications/")
        return id.isJsonPrimitive && (id.asJsonPrimitive.isString || id.asJsonPrimitive.isNumber)
    }

    /** Gson 2.8's JsonParser enables lenient syntax, so keep JsonReader strict explicitly. */
    private fun parseJson(text: String): JsonElement = JsonReader(StringReader(text)).use { reader ->
        reader.isLenient = false
        val result = readJsonValue(reader, 0)
        check(reader.peek() == JsonToken.END_DOCUMENT)
        result
    }

    private fun readJsonValue(reader: JsonReader, depth: Int): JsonElement {
        check(depth <= MAX_JSON_DEPTH)
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> JsonObject().apply {
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    check(!has(name))
                    add(name, readJsonValue(reader, depth + 1))
                }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> JsonArray().apply {
                reader.beginArray()
                while (reader.hasNext()) add(readJsonValue(reader, depth + 1))
                reader.endArray()
            }
            JsonToken.STRING -> JsonPrimitive(reader.nextString())
            JsonToken.NUMBER -> JsonPrimitive(BigDecimal(reader.nextString()))
            JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
            JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
            else -> error("Invalid JSON")
        }
    }

    private fun rpcError(id: JsonElement?, code: Int, message: String) = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        add("id", id ?: JsonNull.INSTANCE)
        add("error", JsonObject().apply { addProperty("code", code); addProperty("message", message) })
    }

    private fun respondJson(socket: Socket, status: Int, body: JsonObject) {
        respond(socket, status, if (status == 200) "OK" else "Bad Request", body.toString().toByteArray(Charsets.UTF_8))
    }

    private fun respondHtml(socket: Socket, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Length: ${bytes.size}\r\nConnection: close\r\nCache-Control: no-store\r\n")
            append("Content-Type: text/html; charset=utf-8\r\nX-Content-Type-Options: nosniff\r\n\r\n")
        }.toByteArray(Charsets.US_ASCII)
        socket.getOutputStream().apply { write(header); write(bytes); flush() }
        socket.shutdownOutput()
    }

    private fun defaultStatusPage(): String = """<!doctype html>
<html lang="zh-CN">
<head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>AI.js Pro · 脚本 MCP</title></head>
<body style="font-family:system-ui,-apple-system,'Segoe UI',sans-serif;background:#F7F7F7;color:#1F2328;margin:0;padding:28px 20px;">
<div style="max-width:720px;margin:0 auto;">
<h1 style="font-size:22px;margin:0 0 6px;">AI.js Pro · 脚本 MCP</h1>
<p style="color:#57606A;margin:0 0 20px;">Model Context Protocol 端点 — ${host}:${port}</p>
<div style="background:#fff;border:1px solid #E1E4E8;border-radius:12px;padding:18px 20px;">
<p style="margin:0 0 10px;"><strong>服务运行中</strong> · 端点只接受 <code>POST /mcp</code>（JSON-RPC 2.0）。</p>
<p style="margin:0 0 4px;">浏览器无法直接"对话"，但可用支持 MCP 的客户端（VS Code、Claude、Codex 等）连接；
或复制下面的命令在终端验证：</p>
<pre style="background:#1F2328;color:#E6EDF3;border-radius:8px;padding:12px;overflow-x:auto;font-size:12px;line-height:1.6;">curl -X POST http://127.0.0.1:${port}/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "MCP-Protocol-Version: 2025-06-18" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"browser","version":"1.0"}}}'</pre>
<p style="margin:12px 0 0;color:#57606A;">认证：本机（127.0.0.1）访问免除 Bearer 校验；局域网访问需携带 <code>Authorization: Bearer &lt;token&gt;</code>。</p>
</div>
</div>
</body></html>"""

    private fun respond(socket: Socket, status: Int, reason: String, body: ByteArray = ByteArray(0)) {
        val header = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Length: ${body.size}\r\nConnection: close\r\nCache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            if (body.isNotEmpty()) append("Content-Type: application/json; charset=utf-8\r\n")
            if (status == 401) append("WWW-Authenticate: Bearer realm=\"AI.js Pro MCP\"\r\n")
            if (status == 405) append("Allow: POST\r\n")
            append("\r\n")
        }.toByteArray(Charsets.US_ASCII)
        socket.getOutputStream().apply { write(header); write(body); flush() }
        socket.shutdownOutput()
    }

    private class HttpError(val status: Int, val reason: String) : IOException()

    companion object {
        private const val MAX_BODY_BYTES = 256 * 1024
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val MAX_CHUNK_LINE_BYTES = 128
        private const val MAX_JSON_DEPTH = 64
        private const val READ_TIMEOUT_MS = 5000
        private const val WORKER_COUNT = 4
        private const val QUEUED_CONNECTIONS = 16
        private const val HEADER_NAME_CHARS = "!#$%&'*+-.^_`|~0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        // Some desktop bridges still send the last pre-Streamable-HTTP version while using the
        // modern POST endpoint. The message shapes used here are compatible with all three.
        private val SUPPORTED_VERSIONS = setOf("2024-11-05", "2025-03-26", "2025-06-18")
        private fun sha256(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    }
}
