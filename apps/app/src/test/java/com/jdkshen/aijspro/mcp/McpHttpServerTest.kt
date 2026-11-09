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
import java.io.OutputStream
import java.net.BindException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
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
    fun tokenIsIgnoredAndRequestsAlwaysReachHandler() {
        // dev-1176：用户要求 MCP 完全免令牌（本机/局域网一致），Authorization 内容一律忽略。
        for (authorization in listOf(null, "Bearer wrong", "Basic $token")) {
            val response = post(INITIALIZE, overrides = mapOf("Authorization" to authorization))
            assertEquals(200, response.status)
            assertFalse(response.headers.containsKey("www-authenticate"))
        }
        assertEquals(3, calls.get())
    }

    @Test
    fun compatibilityFlagsNoLongerGateAuthentication() {
        server.stop()
        server = McpHttpServer("127.0.0.1", 0, token, true) { request ->
            calls.incrementAndGet()
            JsonObject().apply { addProperty("jsonrpc", "2.0"); add("id", request.get("id")); add("result", JsonObject()) }
        }.also { it.start() }
        assertEquals(200, post(INITIALIZE, overrides = mapOf("Authorization" to null)).status)
        assertEquals(200, post(INITIALIZE, overrides = mapOf("Authorization" to "Bearer wrong")).status)
        assertEquals(2, calls.get())
    }

    @Test
    fun originAndUntrustedHostsAreRejectedWithoutDnsResolution() {
        for (origin in listOf(
            "https://example.com", "null", "",
            "http://localhost.evil", "http://127.0.0.1.evil", "http://evil@localhost",
            "http://localhost/path", "http://localhost?x", "http://localhost#x",
            "http://[::2]", "http://0.0.0.0", "http://127.0.0.1:0", "http://127.0.0.1:65536"
        )) {
            val response = post(INITIALIZE, overrides = mapOf("Origin" to origin))
            assertEquals(origin, 403, response.status)
            assertFalse(response.headers.containsKey("access-control-allow-origin"))
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
    fun allowedLoopbackOriginsAreEchoedExactlyWithVaryAndNoCredentials() {
        val origins = listOf(
            "http://localhost",
            "https://localhost:8443",
            "http://127.0.0.1:${server.localPort}",
            "http://127.8.9.10",
            "https://[::1]:5173"
        )
        origins.forEach { origin ->
            val response = post(INITIALIZE, overrides = mapOf("Origin" to origin))
            assertEquals(origin, 200, response.status)
            assertEquals(origin, response.headers["access-control-allow-origin"])
            assertEquals("Origin", response.headers["vary"])
            assertFalse(response.headers.containsKey("access-control-allow-credentials"))
        }
        val withoutOrigin = post(INITIALIZE)
        assertEquals(200, withoutOrigin.status)
        assertFalse(withoutOrigin.headers.containsKey("access-control-allow-origin"))
        assertFalse(withoutOrigin.headers.containsKey("vary"))
        assertEquals(origins.size + 1, calls.get())
    }

    @Test
    fun validCorsPreflightSkipsBearerAndHandler() {
        val origin = "http://localhost:5173"
        val response = post(
            "", method = "OPTIONS", overrides = mapOf(
                "Origin" to origin,
                "Access-Control-Request-Method" to "POST",
                "Access-Control-Request-Headers" to "authorization, content-type, mcp-protocol-version, mcp-session-id",
                "Authorization" to null
            )
        )

        assertEquals(204, response.status)
        assertEquals("", response.body)
        assertEquals("0", response.headers["content-length"])
        assertEquals(origin, response.headers["access-control-allow-origin"])
        assertEquals("Origin", response.headers["vary"])
        assertEquals("POST", response.headers["access-control-allow-methods"])
        val allowHeaders = response.headers["access-control-allow-headers"].orEmpty()
        listOf("Authorization", "Content-Type", "MCP-Protocol-Version", "MCP-Session-Id").forEach {
            assertTrue("Missing $it in $allowHeaders", allowHeaders.contains(it, ignoreCase = true))
        }
        assertFalse(response.headers.containsKey("access-control-allow-credentials"))
        assertFalse(response.headers.containsKey("www-authenticate"))
        assertEquals(0, calls.get())
    }

    @Test
    fun invalidOrOriginlessOptionsStayRejectedWithoutReachingHandler() {
        val origin = "http://localhost:5173"
        assertEquals(405, post("", method = "OPTIONS").status)
        assertEquals(405, post("", method = "OPTIONS", overrides = mapOf("Origin" to origin)).status)
        assertEquals(405, post("", method = "OPTIONS", overrides = mapOf(
            "Origin" to origin, "Access-Control-Request-Method" to "GET"
        )).status)
        assertEquals(405, post("", method = "OPTIONS", overrides = mapOf(
            "Origin" to origin,
            "Access-Control-Request-Method" to "POST",
            "Access-Control-Request-Headers" to "x-not-allowed"
        )).status)
        val badHost = post("", method = "OPTIONS", overrides = mapOf(
            "Origin" to origin,
            "Access-Control-Request-Method" to "POST",
            "Host" to "127.0.0.1.evil"
        ))
        assertEquals(403, badHost.status)
        assertEquals(origin, badHost.headers["access-control-allow-origin"])
        assertEquals(0, calls.get())
    }

    @Test
    fun authlessRequestsAndRpcErrorsKeepAllowedOriginCorsHeaders() {
        val origin = "http://127.0.0.1:${server.localPort}"
        val unauthorized = post(INITIALIZE, overrides = mapOf(
            "Origin" to origin, "Authorization" to "Bearer wrong"
        ))
        // 免令牌后，带错误 Bearer 也直接进入 handler，且 CORS 头保持精确回显。
        assertEquals(200, unauthorized.status)
        assertEquals(origin, unauthorized.headers["access-control-allow-origin"])
        assertEquals("Origin", unauthorized.headers["vary"])
        assertFalse(unauthorized.headers.containsKey("www-authenticate"))

        val parseError = post("{", overrides = mapOf("Origin" to origin))
        assertEquals(400, parseError.status)
        assertEquals(origin, parseError.headers["access-control-allow-origin"])
        assertEquals("Origin", parseError.headers["vary"])
        assertEquals(-32700, parseError.json().getAsJsonObject("error").get("code").asInt)

        val invalidRequest = post("{}", overrides = mapOf("Origin" to origin))
        assertEquals(400, invalidRequest.status)
        assertEquals(origin, invalidRequest.headers["access-control-allow-origin"])
        assertEquals(-32600, invalidRequest.json().getAsJsonObject("error").get("code").asInt)
        assertEquals(1, calls.get())

        val internalError = post(
            """{"jsonrpc":"2.0","id":"request-1","method":"fail"}""",
            overrides = mapOf("Origin" to origin)
        )
        assertEquals(200, internalError.status)
        assertEquals(origin, internalError.headers["access-control-allow-origin"])
        assertEquals("Origin", internalError.headers["vary"])
        assertEquals(-32603, internalError.json().getAsJsonObject("error").get("code").asInt)
        assertEquals(2, calls.get())
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

    /**
     * W4 task-5 C：连接队列打满（4 worker + 16 排队 = 20）时，多出来的连接必须在
     * McpHttpServer 的 RejectedExecutionException 分支里拿到结构化 503；并且把这些连接
     * 用 RST 中止（dev-1411 里让 503 分支裸 respondJson 抛 IOException 的场景）之后，
     * accept 线程必须还活着继续服务（dev-1411 会在这里再也拿不到响应）。
     */
    @Test
    fun floodedQueueAnswersStructured503AndAcceptLoopSurvivesAbortedPeers() {
        val held = ArrayList<Socket>()
        var callsBeforeBusyProbe = -1
        try {
            // 40 个「连上但一个字节都不发」的裸 socket：4 个占住 worker（5s 读超时），
            // 16 个占住排队槽，其余走到拒绝分支被就地回 503。
            val connectDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (held.size < FLOOD_SOCKETS && System.nanoTime() < connectDeadline) {
                // 内核 accept 队列瞬时满时（Windows 会直接 RST）重试即可；只要建立 >=21 个，
                // 4 worker + 16 排队 = 20 个槽位就必然被打满。
                val socket = try { Socket("127.0.0.1", server.localPort) } catch (_: IOException) { null }
                if (socket == null) Thread.sleep(20) else held.add(socket)
            }
            assertTrue("只建立了 ${held.size}/$FLOOD_SOCKETS 个连接", held.size >= 21)
            var busy: Response? = null
            val probeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (System.nanoTime() < probeDeadline) {
                callsBeforeBusyProbe = calls.get()
                val response = probe()
                if (response != null && response.status == 503) { busy = response; break }
                Thread.sleep(50)
            }
            assertTrue("$FLOOD_SOCKETS 个连接占满队列后，新请求应拿到 503", busy != null)
            assertEquals(503, busy!!.status)
            assertTrue(
                "503 状态行必须是 Service Unavailable，实际：${busy.statusLine}",
                busy.statusLine.startsWith("HTTP/1.1 503 Service Unavailable")
            )
            val body = busy.json()
            assertEquals("service_busy", body.get("errorCode").asString)
            assertEquals("service_busy", body.getAsJsonObject("error").get("code").asString)
            assertTrue(body.getAsJsonObject("error").get("recoverable").asBoolean)
            assertTrue(body.getAsJsonObject("error").get("retrySameArguments").asBoolean)
            assertEquals(1000, body.get("retryAfterMs").asInt)
            // W4 task-12 A：503 必须有标准退避头，且与 body.retryAfterMs 同源（秒 = ceil(ms/1000)）。
            assertEquals("1", busy.headers["retry-after"])
            assertEquals(body.get("retryAfterMs").asLong, busy.headers["retry-after"]!!.toLong() * 1000)
            assertTrue(body.get("recovery").asString.isNotEmpty())
            // 被拒绝的请求没有进入 handler（与 body 里「本次请求未进入处理」一致）：
            // 打出 503 的那次探测前后 handler 计数不变。
            assertEquals(callsBeforeBusyProbe, calls.get())
        } finally {
            for (socket in held) {
                runCatching { socket.setSoLinger(true, 0) } // RST，不是 FIN
                runCatching { socket.close() }
            }
        }
        // dev-1411 的回归点就在这里：503 是对端已断开时写 socket 抛 IOException（accept 线程死亡）。
        // 除了上面那批「保持到断言之后再 RST」的连接，再连 2 轮「连上就 RST」的风暴，
        // 尽量让服务端的 503 写入撞上已经复位的对端。
        repeat(2) {
            val storm = ArrayList<Socket>()
            repeat(FLOOD_SOCKETS) {
                val socket = try { Socket("127.0.0.1", server.localPort) } catch (_: IOException) { null }
                if (socket != null) storm.add(socket)
            }
            for (socket in storm) {
                runCatching { socket.setSoLinger(true, 0) } // RST，不是 FIN
                runCatching { socket.close() }
            }
        }
        val after = awaitHealthyResponse()
        assertTrue("RST 掉 $FLOOD_SOCKETS 个连接后端点不再响应：accept 线程可能已死", after != null)
        assertEquals(200, after!!.status)
        assertEquals("示例运行", after.json().getAsJsonObject("result").get("echo").asString)
    }

    /**
     * dev-1411 的回归点：503 分支以前是裸 respondJson(...)，对端不可写时抛 IOException 会穿出
     * accept 循环、杀死 accept 线程（listener 仍占端口却再也不 accept）。这里用 seam 精确复现：
     * worker + 排队槽被占满 → 下一个连接必然走 503 分支；交给它的 socket 未连接（取输出流/写必然
     * 失败），所以只有 503 分支的 try/catch 生效时循环才会继续 accept。
     */
    @Test
    fun acceptLoopSurvives503WriteFailureToUnwritablePeer() {
        val unwritable = Socket() // 未连接、未关闭：soTimeout 可设，但取输出流/写必抛 SocketException
        val fake = FlakyServerSocket(unwritable)
        val saturating = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1))
        val release = CountDownLatch(1)
        // 显式 Runnable：测试结束 shutdownNow() 时不留下未捕获的 InterruptedException 噪声。
        val hold = Runnable { try { release.await() } catch (_: InterruptedException) { Thread.currentThread().interrupt() } }
        saturating.execute(hold) // worker 占住
        saturating.execute(hold) // 排队槽占住 → 下一次 execute 必然被 AbortPolicy 拒绝
        val loop = Thread { server.acceptConnections(fake, saturating) }
        loop.isDaemon = true
        loop.start()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (fake.acceptCalls.get() < 2 && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(
                "503 写失败后 accept 循环退出了（dev-1411 回归）：accept() 只被调用 ${fake.acceptCalls.get()} 次",
                fake.acceptCalls.get() >= 2
            )
        } finally {
            release.countDown()
            fake.close()
            saturating.shutdownNow()
            loop.join(3000)
        }
        assertFalse("close() 之后 accept 循环应退出", loop.isAlive)
    }

    /**
     * W4 task-5 A：accept() 抛一次瞬时 IOException（EMFILE/ENFILE、ECONNABORTED）时，
     * 旧的 catch (_: IOException) { break } 会永久退出循环（listener 仍占端口却不再 accept，
     * 且 start()/McpAutoLifecycle 都不会重启它）。这里用一个始终抛瞬时 IOException 的
     * ServerSocket 直接驱动 acceptConnections，断言它持续重试、且 close() 之后能退出。
     */
    @Test
    fun acceptLoopKeepsRetryingAfterTransientIoExceptionAndStillStopsOnClose() {
        val flaky = FlakyServerSocket()
        val executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1))
        val loop = Thread { server.acceptConnections(flaky, executor) }
        loop.isDaemon = true
        loop.start()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (flaky.acceptCalls.get() < 2 && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(
                "首次 IOException 之后没有重试：accept() 只被调用 ${flaky.acceptCalls.get()} 次（旧行为=1）",
                flaky.acceptCalls.get() >= 2
            )
            // 退避检查：50ms 退避下 300ms 约 6 次；忙等会是几十万次。
            val first = flaky.acceptCalls.get()
            Thread.sleep(300)
            val retries = flaky.acceptCalls.get() - first
            assertTrue("accept 重试疑似忙等：300ms 内 $retries 次", retries in 1..100)
            // task-10 A：失败必须被记录，否则「重试但没人知道」等于静默僵尸。
            assertTrue("accept 失败没有被记录", server.listenerFailureCount >= 1)
            assertTrue("lastFailure 应标明位置", server.lastListenerFailure.orEmpty().startsWith("accept"))
        } finally {
            flaky.close()
            loop.join(3000)
        }
        assertFalse("close() 之后 accept 循环应退出", loop.isAlive)
    }

    /** RST 风暴之后再要一个正常响应（dev-1411 的 accept 线程已死时会一直拿不到）。 */
    private fun awaitHealthyResponse(): Response? {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val response = probe()
            if (response != null && response.status == 200) return response
            Thread.sleep(50)
        }
        return null
    }

    /** 带短超时的正常 POST；连接被拒/超时/无响应一律返回 null（不抛断言）。 */
    private fun probe(): Response? = try {
        Socket("127.0.0.1", server.localPort).use { socket ->
            socket.soTimeout = PROBE_TIMEOUT_MS
            val output = socket.getOutputStream()
            output.write(headers(INITIALIZE.toByteArray(Charsets.UTF_8).size).toByteArray(Charsets.US_ASCII))
            output.write(INITIALIZE.toByteArray(Charsets.UTF_8))
            output.flush()
            parseResponse(socket.getInputStream().readBytes().toString(Charsets.UTF_8))
        }
    } catch (_: IOException) {
        null
    }

    private fun parseResponse(raw: String): Response {
        val headerEnd = raw.indexOf("\r\n\r\n")
        if (headerEnd < 0) return Response(-1, emptyMap(), raw, raw.lineSequence().firstOrNull().orEmpty())
        val lines = raw.substring(0, headerEnd).split("\r\n")
        return Response(
            lines.first().split(' ').getOrNull(1)?.toIntOrNull() ?: -1,
            lines.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() },
            raw.substring(headerEnd + 4),
            lines.first()
        )
    }

    /**
     * W4 task-10 A：503 写失败以前只 catch Exception —— Error（OOM 等）仍会穿出 while、杀死 accept
     * 线程。现在扩到 Throwable 并记录：循环必须活着，且 get_status 能从 listener.alive/failureCount/
     * lastFailure 看到它（不许变静默僵尸）。
     */
    @Test
    fun acceptLoopSurvivesErrorWhileWriting503AndRecordsIt() {
        val fake = OneShotThenBlockingServerSocket(ErrorSocket())
        val saturating = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1))
        val release = CountDownLatch(1)
        // 显式 Runnable：测试结束 shutdownNow() 时不留下未捕获的 InterruptedException 噪声。
        val hold = Runnable { try { release.await() } catch (_: InterruptedException) { Thread.currentThread().interrupt() } }
        saturating.execute(hold) // worker 占住
        saturating.execute(hold) // 排队槽占住 → 下一个连接必然走 503 分支
        val loop = Thread { server.acceptConnections(fake, saturating) }
        loop.isDaemon = true
        loop.start()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (fake.acceptCalls.get() < 2 && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(
                "写 503 抛 Error 后 accept 循环退出了：accept() 只被调用 ${fake.acceptCalls.get()} 次",
                fake.acceptCalls.get() >= 2
            )
            // 第二次 accept 在阻塞、不会再记录，所以这里能精确断言「那次 Error 被记录了」。
            assertEquals(1, server.listenerFailureCount)
            assertTrue(
                "lastFailure 应指出 Error 类型与位置，实际：${server.lastListenerFailure}",
                server.lastListenerFailure.orEmpty().startsWith("respond_503") &&
                    server.lastListenerFailure.orEmpty().contains("OutOfMemoryError")
            )
        } finally {
            release.countDown()
            fake.close()
            saturating.shutdownNow()
            loop.join(3000)
        }
    }

    /** W4 task-10 A/B：accept 循环的真实存活状态必须与 start()/stop() 同步（get_status 靠它判 listening）。 */
    @Test
    fun acceptLoopAliveTracksStartAndStop() {
        assertTrue("start() 之后 accept 循环应在跑", server.acceptLoopAlive)
        server.stop()
        assertFalse("stop() 之后 acceptLoopAlive 必须为 false", server.acceptLoopAlive)
        server.start()
        assertTrue("重新 start() 之后应恢复", server.acceptLoopAlive)
    }

    /**
     * W4 task-55：**flaky 的确定性复现**（不是靠概率碰运气）。
     *
     * 竞态：accept 循环的 `finally { acceptLoopAlive = false }` 只知道自己要退出，**不知道已经换了一轮**；
     * 于是 stop()→start() 之后，旧循环稍后到达 finally 时会把新循环刚置上的 true 清成 false
     * → get_status.mcp 误报 listening=false（产品侧问题），测试 :585 断言随之偶发失败。
     *
     * 这里用 seam 精确制造这个交错：旧循环在 accept() 里阻塞 → stop() → start() → 再放旧循环退出 → join 断言。
     * 全程用 latch/join 做时序，不依赖 sleep。
     */
    @Test
    fun staleAcceptLoopMustNotClearTheFlagAfterRestart() {
        val staleSocket = BlockingServerSocket()
        val stalePool = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1))
        val staleLoop = Thread({ server.acceptConnections(staleSocket, stalePool) }, "stale-accept-loop")
        staleLoop.isDaemon = true
        staleLoop.start()
        assertTrue("旧循环未能在 2s 内进入 accept()", staleSocket.acceptEntered.await(2, TimeUnit.SECONDS))

        server.stop()   // 真实 listener 关闭、flag=false
        server.start()  // 新一轮：新 listener + flag=true
        assertTrue("start() 之后应为 true", server.acceptLoopAlive)

        staleSocket.close()  // 旧循环此刻退出，它的 finally 会执行
        staleLoop.join(3000)
        assertFalse("旧循环应已退出", staleLoop.isAlive)

        assertTrue("旧循环的 finally 不能清掉新循环的状态（task-55 根因）", server.acceptLoopAlive)
        stalePool.shutdownNow()
    }

    /** accept() 立刻发信号并阻塞到 close()：让「旧循环已进入 accept 但还没退出」这个状态可精确构造。 */
    private class BlockingServerSocket : ServerSocket() {
        val acceptEntered = CountDownLatch(1)
        override fun accept(): Socket {
            acceptEntered.countDown()
            while (!isClosed) {
                try { Thread.sleep(10) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            throw IOException("stale socket closed")
        }
    }

    /**
     * 只用于 task-5 A/C 的 seam：第一次 accept() 返回 first（若非 null），之后永远抛瞬时
     * IOException，直到测试 close() 它。
     */
    private class FlakyServerSocket(private val first: Socket? = null) : ServerSocket() {
        val acceptCalls = AtomicInteger()
        override fun accept(): Socket {
            val call = acceptCalls.incrementAndGet()
            if (call == 1 && first != null) return first
            throw IOException("transient accept failure (EMFILE)")
        }
    }

    /** 未连接、未关闭的 Socket，但取输出流时抛 Error：模拟 503 写响应期间的 OOM。 */
    private class ErrorSocket : Socket() {
        override fun getOutputStream(): OutputStream =
            throw OutOfMemoryError("simulated OOM while writing 503")
    }

    /**
     * 第一次 accept() 返回 first，之后阻塞直到测试 close()：这样「第一次失败」的记录不会被后续
     * 失败覆盖，可以精确断言 lastFailure（task-10 A）。
     */
    private class OneShotThenBlockingServerSocket(private val first: Socket) : ServerSocket() {
        val acceptCalls = AtomicInteger()
        override fun accept(): Socket {
            if (acceptCalls.incrementAndGet() == 1) return first
            while (!isClosed) {
                try { Thread.sleep(20) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            throw IOException("socket closed")
        }
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
            response.substring(headerEnd + 4),
            lines.first()
        )
    }

    private data class Response(
        val status: Int,
        val headers: Map<String, String>,
        val body: String,
        /** 完整状态行，用于断言 reason phrase（task-5 C 的 503 Service Unavailable）。 */
        val statusLine: String = ""
    ) {
        fun json(): JsonObject = JsonParser().parse(body).asJsonObject
    }

    companion object {
        /** 连接队列容量 = WORKER_COUNT(4) + QUEUED_CONNECTIONS(16)；用 40 留足余量。 */
        private const val FLOOD_SOCKETS = 40
        private const val PROBE_TIMEOUT_MS = 2000
        private const val INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}"
    }
}
