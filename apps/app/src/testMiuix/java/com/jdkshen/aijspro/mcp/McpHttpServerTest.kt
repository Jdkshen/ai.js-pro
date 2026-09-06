package com.jdkshen.aijspro.mcp

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.BindException
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

class McpHttpServerTest {
    private lateinit var server: McpHttpServer
    private val calls = AtomicInteger()
    private val token = "unit-test-token-only"

    @Before
    fun startServer() {
        server = McpHttpServer("127.0.0.1", 0, token) { request ->
            calls.incrementAndGet()
            if (request.get("method").asString == "fail") throw IllegalStateException("private diagnostic")
            if (!request.has("id")) null else JsonObject().apply {
                addProperty("jsonrpc", "2.0")
                add("id", request.get("id"))
                add("result", JsonObject().apply {
                    addProperty("protocolVersion", "2025-06-18")
                    addProperty("echo", "示例运行")
                })
            }
        }
        server.start()
    }

    @After
    fun stopServer() { server.stop() }

    @Test
    fun initializeUsesJsonResponseAndExactUtf8ContentLength() {
        val response = post(INITIALIZE)
        assertEquals(200, response.status)
        assertEquals("close", response.headers["connection"])
        assertEquals("application/json; charset=utf-8", response.headers["content-type"])
        assertEquals(response.body.toByteArray(Charsets.UTF_8).size.toString(), response.headers["content-length"])
        assertEquals(1, response.json().get("id").asInt)
        assertEquals("示例运行", response.json().getAsJsonObject("result").get("echo").asString)
        assertFalse(response.headers.containsKey("mcp-session-id"))
        assertEquals(1, calls.get())
    }

    @Test
    fun missingOrIncorrectTokenCannotReachHandler() {
        for (authorization in listOf(null, "Bearer wrong", "Basic $token")) {
            val response = post(INITIALIZE, overrides = mapOf("Authorization" to authorization))
            assertEquals(401, response.status)
            assertTrue(response.headers.containsKey("www-authenticate"))
            assertFalse(response.body.contains(token))
        }
        assertEquals(0, calls.get())
    }

    @Test
    fun optionalLoopbackCompatibilityAcceptsMissingButNotWrongCredentials() {
        server.stop()
        server = McpHttpServer("127.0.0.1", 0, token, true) { request ->
            calls.incrementAndGet()
            JsonObject().apply { addProperty("jsonrpc", "2.0"); add("id", request.get("id")); add("result", JsonObject()) }
        }.also { it.start() }
        assertEquals(200, post(INITIALIZE, overrides = mapOf("Authorization" to null)).status)
        assertEquals(401, post(INITIALIZE, overrides = mapOf("Authorization" to "Bearer wrong")).status)
        assertEquals(1, calls.get())
    }

    @Test
    fun originAndUntrustedHostsAreRejectedWithoutDnsResolution() {
        for (origin in listOf("https://example.com", "null", "")) {
            assertEquals(403, post(INITIALIZE, overrides = mapOf("Origin" to origin)).status)
        }
        for (host in listOf("attacker.invalid", "deadbeef", "127.0.0.1.attacker.invalid", "0.0.0.0")) {
            assertEquals(403, post(INITIALIZE, overrides = mapOf("Host" to host)).status)
        }
        assertEquals(403, post(INITIALIZE, overrides = mapOf("Host" to null)).status)
        assertEquals(0, calls.get())
        assertEquals(200, post(INITIALIZE, overrides = mapOf("Host" to "localhost:${server.localPort}")).status)
        assertEquals(200, post(INITIALIZE, overrides = mapOf("Host" to "127.0.0.1:18788")).status)
        assertEquals(403, post(INITIALIZE, overrides = mapOf("Host" to "127.0.0.1:0")).status)
    }

    @Test
    fun initializedNotificationIsAcceptedWithNoBody() {
        val response = post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        assertEquals(202, response.status)
        assertEquals("", response.body)
        assertEquals("0", response.headers["content-length"])
        assertFalse(response.headers.containsKey("content-type"))
        assertEquals(1, calls.get())
    }

    @Test
    fun invalidJsonAndLenientSyntaxAreParseErrors() {
        for (body in listOf("{", "", "{unquoted:true}", "{'single':'quotes'}", "$INITIALIZE {}", "/*comment*/$INITIALIZE", "{\"x\":1,\"x\":2}")) {
            val response = post(body)
            assertEquals(body, 400, response.status)
            assertEquals(-32700, response.json().getAsJsonObject("error").get("code").asInt)
        }
        assertEquals(0, calls.get())
    }

    @Test
    fun malformedUtf8CannotBeSilentlyReplaced() {
        val response = exchange(headers(2), byteArrayOf(0xc3.toByte(), 0x28))
        assertEquals(400, response.status)
        assertEquals(-32700, response.json().getAsJsonObject("error").get("code").asInt)
    }

    @Test
    fun batchesAndRequestsWithoutValidIdsAreInvalidRequests() {
        for (body in listOf(
            "[$INITIALIZE]", "[]", "{}",
            """{"jsonrpc":"2.0","method":"initialize"}""",
            """{"jsonrpc":"2.0","id":null,"method":"tools/list"}""",
            """{"jsonrpc":"2.0","id":true,"method":"tools/list"}""",
            """{"jsonrpc":"2.0","id":{},"method":"tools/list"}""",
            """{"jsonrpc":"2.0","id":2,"method":"tools/list","params":"invalid"}""",
            """{"jsonrpc":"2.0","id":2,"result":{}}"""
        )) {
            val response = post(body)
            assertEquals(body, 400, response.status)
            assertEquals(-32600, response.json().getAsJsonObject("error").get("code").asInt)
        }
        assertEquals(0, calls.get())
    }

    @Test
    fun supportsBothProtocolVersionsAndDefaultsMissingHeader() {
        for (version in listOf(null, "2024-11-05", "2025-03-26", "2025-06-18")) {
            assertEquals(200, post(INITIALIZE, overrides = mapOf("MCP-Protocol-Version" to version)).status)
        }
        assertEquals(400, post(INITIALIZE, overrides = mapOf("MCP-Protocol-Version" to "2026-01-01")).status)
        assertEquals(4, calls.get())
    }

    @Test
    fun rejectsUnsupportedMethodsAndWrongEndpoint() {
        for (method in listOf("GET", "DELETE", "OPTIONS", "PUT")) {
            val response = post("", method = method)
            assertEquals(405, response.status)
            assertEquals("POST", response.headers["allow"])
        }
        assertEquals(404, post(INITIALIZE, path = "/other").status)
        assertEquals(0, calls.get())
    }

    @Test
    fun rejectsAmbiguousOrUnsupportedFramingBeforeReadingBody() {
        assertEquals(411, post(INITIALIZE, overrides = mapOf("Content-Length" to null)).status)
        assertEquals(400, post(INITIALIZE, overrides = mapOf("Content-Length" to "-1")).status)
        assertEquals(400, post(INITIALIZE, overrides = mapOf("Content-Length" to "2, 2")).status)
        assertEquals(400, post(INITIALIZE, overrides = mapOf("Transfer-Encoding" to "chunked")).status)
        assertEquals(417, post(INITIALIZE, overrides = mapOf("Expect" to "100-continue")).status)
        val duplicate = headers(INITIALIZE.length).dropLast(2) + "content-length: 1\r\n\r\n"
        assertEquals(400, exchange(duplicate, INITIALIZE.toByteArray()).status)
        assertEquals(0, calls.get())
    }

    @Test
    fun acceptsBoundedChunkedRequestsUsedBySdkClients() {
        val bytes = INITIALIZE.toByteArray(Charsets.UTF_8)
        val framed = (bytes.size.toString(16) + "\r\n").toByteArray(Charsets.US_ASCII) + bytes + "\r\n0\r\n\r\n".toByteArray(Charsets.US_ASCII)
        val response = exchange(headers(0, overrides = mapOf("Content-Length" to null, "Transfer-Encoding" to "chunked")), framed)
        assertEquals(200, response.status)
        assertEquals(1, calls.get())
        val ambiguous = exchange(headers(bytes.size, overrides = mapOf("Transfer-Encoding" to "chunked")), framed)
        assertEquals(400, ambiguous.status)
    }

    @Test
    fun acceptsJsonAndWildcardClientsButRejectsSseOnlyResponses() {
        for (contentType in listOf(null, "text/plain", "application/json; charset=gbk")) {
            assertEquals(415, post(INITIALIZE, overrides = mapOf("Content-Type" to contentType)).status)
        }
        for (accept in listOf(null, "text/event-stream", "application/json;q=0, text/event-stream")) {
            assertEquals(406, post(INITIALIZE, overrides = mapOf("Accept" to accept)).status)
        }
        for (accept in listOf("application/json", "application/*", "*/*", "application/json, text/event-stream")) {
            assertEquals(200, post(INITIALIZE, overrides = mapOf("Accept" to accept)).status)
        }
        assertEquals(200, post(INITIALIZE, overrides = mapOf("Content-Type" to "application/json; charset=UTF-8")).status)
    }

    @Test
    fun bodyAndHeaderLimitsAreEnforced() {
        assertEquals(413, exchange(headers(256 * 1024 + 1), ByteArray(0)).status)
        assertEquals(413, post("", overrides = mapOf("Content-Length" to "999999999999999999999999")).status)
        val exactBody = INITIALIZE + " ".repeat(256 * 1024 - INITIALIZE.length)
        assertEquals(200, post(exactBody).status)
        assertEquals(431, post(INITIALIZE, overrides = mapOf("X-Padding" to "a".repeat(16 * 1024))).status)
    }

    @Test
    fun handlerFailuresKeepIdsAndDoNotExposeExceptions() {
        val response = post("""{"jsonrpc":"2.0","id":"request-1","method":"fail"}""")
        assertEquals(200, response.status)
        assertEquals("request-1", response.json().get("id").asString)
        assertEquals(-32603, response.json().getAsJsonObject("error").get("code").asInt)
        assertFalse(response.body.contains("private diagnostic"))
    }

    @Test
    fun bindConflictsAreReportedSynchronously() {
        val other = McpHttpServer("127.0.0.1", server.localPort, token) { null }
        var conflict = false
        try { other.start() } catch (_: BindException) { conflict = true } finally { other.stop() }
        assertTrue(conflict)
        assertEquals(200, post(INITIALIZE).status)
    }

    @Test
    fun stopClosesPartialRequestsAndCanRestart() {
        Socket("127.0.0.1", server.localPort).use { socket ->
            socket.soTimeout = 2000
            socket.getOutputStream().write("POST /mcp HTTP/1.1\r\n".toByteArray())
            server.stop()
            val closed = try { socket.getInputStream().read() == -1 } catch (_: IOException) { true }
            assertTrue(closed)
        }
        assertEquals(-1, server.localPort)
        server.stop()
        server.start()
        server.start()
        assertEquals(200, post(INITIALIZE).status)
    }

    private fun post(
        body: String,
        method: String = "POST",
        path: String = "/mcp",
        overrides: Map<String, String?> = emptyMap()
    ): Response {
        val bytes = body.toByteArray(Charsets.UTF_8)
        return exchange(headers(bytes.size, method, path, overrides), bytes)
    }

    private fun headers(
        length: Int,
        method: String = "POST",
        path: String = "/mcp",
        overrides: Map<String, String?> = emptyMap()
    ): String {
        val headers = linkedMapOf<String, String?>(
            "Host" to "127.0.0.1:${server.localPort}",
            "Authorization" to "Bearer $token",
            "Accept" to "application/json, text/event-stream",
            "Content-Type" to "application/json",
            "Content-Length" to length.toString(),
            "MCP-Protocol-Version" to "2025-06-18"
        ).apply { putAll(overrides) }
        return "$method $path HTTP/1.1\r\n" +
            headers.filterValues { it != null }.entries.joinToString("") { "${it.key}: ${it.value}\r\n" } + "\r\n"
    }

    private fun exchange(headers: String, body: ByteArray): Response = Socket("127.0.0.1", server.localPort).use { socket ->
        socket.soTimeout = 7000
        val output = socket.getOutputStream()
        output.write(headers.toByteArray(Charsets.US_ASCII) + body)
        output.flush()
        socket.shutdownOutput()
        val response = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        val headerEnd = response.indexOf("\r\n\r\n")
        assertTrue("Missing HTTP response: $response", headerEnd >= 0)
        val lines = response.substring(0, headerEnd).split("\r\n")
        Response(
            lines.first().split(' ')[1].toInt(),
            lines.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() },
            response.substring(headerEnd + 4)
        )
    }

    private data class Response(val status: Int, val headers: Map<String, String>, val body: String) {
        fun json(): JsonObject = JsonParser().parse(body).asJsonObject
    }

    companion object {
        private const val INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}"
    }
}
