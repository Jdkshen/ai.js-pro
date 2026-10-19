package com.jdkshen.aijspro.pluginclient;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * ws-rpc 协议核心的纯 JVM 自测（不需要 Android 设备/Gradle）。
 *
 * <p>覆盖三层：
 * <ul>
 *   <li>{@link ProRpcFrames}：4 字节大端类型 + 36 字节通道 id 的编码/解码；</li>
 *   <li>{@link ProRpcWebSocket}：本机回环上的握手、二进制消息、收发；</li>
 *   <li>{@link ProRpcDispatcher}：authorize → 顶层 token 校验 → debug.* 方法分发。</li>
 * </ul>
 *
 * <p>运行方式见 {@code tools/test-pro-rpc.ps1}（javac + JUnit，不碰 Gradle）。
 */
public class ProRpcProtocolTest {

    // ------------------------------------------------------------ frames

    @Test
    public void jsonFrameRoundTrip() throws Exception {
        byte[] frame = ProRpcFrames.encodeJson("{\"jsonrpc\":\"2.0\",\"id\":1}");
        assertEquals(4, ProRpcFrames.HEADER_LENGTH);
        assertEquals(ProRpcFrames.TYPE_JSON, ProRpcFrames.readInt32(frame, 0));
        ProRpcFrames.Message message = ProRpcFrames.decode(frame);
        assertNotNull(message);
        assertTrue(message.isJson());
        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1}", message.json);
    }

    @Test
    public void binaryFrameRoundTrip() throws Exception {
        String channelId = UUID.randomUUID().toString();
        assertEquals(36, channelId.length());
        byte[] payload = new byte[]{1, 2, 3, 4, 5};
        byte[] frame = ProRpcFrames.encodeBinary(channelId, payload);
        assertEquals(ProRpcFrames.TYPE_BINARY, ProRpcFrames.readInt32(frame, 0));
        ProRpcFrames.Message message = ProRpcFrames.decode(frame);
        assertNotNull(message);
        assertTrue(message.isBinary());
        assertEquals(channelId, message.channelId);
        assertArrayEquals(payload, message.payload);
    }

    @Test
    public void unknownFrameTypeIsDropped() throws Exception {
        byte[] frame = new byte[4];
        ProRpcFrames.putInt32(frame, 0, 7);
        assertNull(ProRpcFrames.decode(frame));
    }

    @Test
    public void shortFrameIsRejected() {
        try {
            ProRpcFrames.decode(new byte[]{0, 0});
            fail("expected IOException");
        } catch (IOException expected) {
            // ok
        }
    }

    @Test
    public void channelIdMustBe36AsciiChars() {
        assertFalse(ProRpcFrames.isValidChannelId("too-short"));
        assertFalse(ProRpcFrames.isValidChannelId(null));
        assertTrue(ProRpcFrames.isValidChannelId(UUID.randomUUID().toString()));
        try {
            ProRpcFrames.encodeBinary("too-short", new byte[0]);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    // ------------------------------------------------------------ websocket

    @Test
    public void websocketRoundTrip() throws Exception {
        ServerSocket serverSocket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        BlockingQueue<byte[]> serverReceived = new LinkedBlockingQueue<>();
        BlockingQueue<byte[]> clientReceived = new LinkedBlockingQueue<>();
        CountDownLatch serverReady = new CountDownLatch(1);
        ProRpcWebSocket[] clientHolder = new ProRpcWebSocket[1];
        try {
            Future<?> serverFuture = pool.submit(() -> {
                Socket socket = serverSocket.accept();
                ProRpcWebSocket server = ProRpcWebSocket.accept(socket);
                serverReady.countDown();
                server.sendBinary(ProRpcFrames.encodeJson("{\"result\":\"server-first\"}"));
                try {
                    server.readLoop(new ProRpcWebSocket.Listener() {
                        @Override
                        public void onMessage(byte[] payload) {
                            serverReceived.add(payload);
                        }

                        @Override
                        public void onClosed(int code, String reason) {
                        }
                    });
                } catch (IOException expected) {
                    // 客户端 close() 现在是直接 abort（不写 close 帧），读线程以 EOF 结束。
                }
                return null;
            });

            ProRpcWebSocket client = ProRpcWebSocket.connect(
                    "127.0.0.1", serverSocket.getLocalPort(), "/");
            clientHolder[0] = client;
            assertTrue(serverReady.await(5, TimeUnit.SECONDS));
            pool.submit(() -> {
                client.readLoop(new ProRpcWebSocket.Listener() {
                    @Override
                    public void onMessage(byte[] payload) {
                        clientReceived.add(payload);
                    }

                    @Override
                    public void onClosed(int code, String reason) {
                    }
                });
                return null;
            });

            byte[] fromClient = ProRpcFrames.encodeJson("{\"method\":\"debug.authorize\"}");
            client.sendBinary(fromClient);
            byte[] received = serverReceived.poll(5, TimeUnit.SECONDS);
            assertNotNull("server did not receive the client frame", received);
            assertArrayEquals(fromClient, received);

            byte[] fromServer = clientReceived.poll(5, TimeUnit.SECONDS);
            assertNotNull("client did not receive the server frame", fromServer);
            assertArrayEquals(ProRpcFrames.encodeJson("{\"result\":\"server-first\"}"), fromServer);

            client.close();
            serverFuture.get(5, TimeUnit.SECONDS);
        } finally {
            if (clientHolder[0] != null) {
                clientHolder[0].close();
            }
            serverSocket.close();
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------ close / teardown

    /**
     * 外部 close（如 Service.onDestroy 在主线程调用）必须只 abort socket：
     * 不写 close 帧、不 flush、不等 mWriteLock，并且立即解除阻塞中的 readLoop。
     *
     * <p>用裸 TCP 客户端充当对端：若仍发送 close 帧，客户端会先读到 0x88 而不是 EOF。
     */
    @Test
    public void externalCloseAbortsSocketAndUnblocksReader() throws Exception {
        ServerSocket serverSocket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        ExecutorService readerPool = Executors.newSingleThreadExecutor();
        CountDownLatch serverReady = new CountDownLatch(1);
        ProRpcWebSocket[] serverHolder = new ProRpcWebSocket[1];
        Socket rawClient = null;
        try {
            Future<Throwable> readerResult = readerPool.submit(() -> {
                ProRpcWebSocket server = ProRpcWebSocket.accept(serverSocket.accept());
                serverHolder[0] = server;
                serverReady.countDown();
                try {
                    server.readLoop(new ProRpcWebSocket.Listener() {
                        @Override
                        public void onMessage(byte[] payload) {
                        }

                        @Override
                        public void onClosed(int code, String reason) {
                        }
                    });
                    return null;
                } catch (IOException error) {
                    return error;
                }
            });

            rawClient = connectRawWebSocket(serverSocket);
            assertTrue("server did not accept the raw client",
                    serverReady.await(5, TimeUnit.SECONDS));
            ProRpcWebSocket server = serverHolder[0];
            assertNotNull(server);

            long startedAt = System.nanoTime();
            server.close();
            long closeMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            assertTrue("external close blocked for " + closeMillis + " ms", closeMillis < 2000);
            assertTrue(server.isClosed());

            Throwable readerError = readerResult.get(5, TimeUnit.SECONDS);
            assertTrue("readLoop should end by EOF, got " + readerError,
                    readerError == null || readerError instanceof IOException);

            rawClient.setSoTimeout(5000);
            assertEquals("external close must not write a close frame",
                    -1, rawClient.getInputStream().read());
        } finally {
            if (rawClient != null) {
                rawClient.close();
            }
            if (serverHolder[0] != null) {
                serverHolder[0].close();
            }
            serverSocket.close();
            readerPool.shutdownNow();
        }
    }

    /** 反复（含并发）close 必须幂等、快速返回，且只 abort 一次。 */
    @Test
    public void repeatedCloseIsIdempotentAndAbortsOnce() throws Exception {
        ServerSocket serverSocket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        ExecutorService readerPool = Executors.newSingleThreadExecutor();
        ExecutorService closerPool = Executors.newFixedThreadPool(4);
        CountDownLatch serverReady = new CountDownLatch(1);
        ProRpcWebSocket[] serverHolder = new ProRpcWebSocket[1];
        Socket rawClient = null;
        try {
            Future<Throwable> readerResult = readerPool.submit(() -> {
                ProRpcWebSocket server = ProRpcWebSocket.accept(serverSocket.accept());
                serverHolder[0] = server;
                serverReady.countDown();
                try {
                    server.readLoop(new ProRpcWebSocket.Listener() {
                        @Override
                        public void onMessage(byte[] payload) {
                        }

                        @Override
                        public void onClosed(int code, String reason) {
                        }
                    });
                    return null;
                } catch (IOException error) {
                    return error;
                }
            });

            rawClient = connectRawWebSocket(serverSocket);
            assertTrue("server did not accept the raw client",
                    serverReady.await(5, TimeUnit.SECONDS));
            ProRpcWebSocket server = serverHolder[0];
            assertNotNull(server);

            for (int i = 0; i < 5; i++) {
                long startedAt = System.nanoTime();
                server.close(4000 + i, "reason-" + i);
                long closeMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
                assertTrue("close #" + i + " blocked for " + closeMillis + " ms",
                        closeMillis < 2000);
            }

            List<Future<?>> concurrentCloses = new ArrayList<>();
            CountDownLatch start = new CountDownLatch(1);
            for (int i = 0; i < 8; i++) {
                concurrentCloses.add(closerPool.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    server.close();
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> close : concurrentCloses) {
                close.get(5, TimeUnit.SECONDS);
            }

            assertTrue(server.isClosed());
            Throwable readerError = readerResult.get(5, TimeUnit.SECONDS);
            assertTrue("readLoop should end by EOF, got " + readerError,
                    readerError == null || readerError instanceof IOException);
            rawClient.setSoTimeout(5000);
            assertEquals(-1, rawClient.getInputStream().read());
        } finally {
            if (rawClient != null) {
                rawClient.close();
            }
            if (serverHolder[0] != null) {
                serverHolder[0].close();
            }
            serverSocket.close();
            readerPool.shutdownNow();
            closerPool.shutdownNow();
        }
    }

    /** 对端主动 close 帧仍须：在读线程回一帧 close，并把 code/reason 交给监听者。 */
    @Test
    public void peerCloseFrameIsEchoedAndReported() throws Exception {
        ServerSocket serverSocket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        ExecutorService readerPool = Executors.newSingleThreadExecutor();
        CountDownLatch serverReady = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        AtomicInteger closedCode = new AtomicInteger(-1);
        AtomicReference<String> closedReason = new AtomicReference<>();
        AtomicInteger messages = new AtomicInteger();
        ProRpcWebSocket[] serverHolder = new ProRpcWebSocket[1];
        Socket rawClient = null;
        try {
            Future<?> readerResult = readerPool.submit(() -> {
                ProRpcWebSocket server = ProRpcWebSocket.accept(serverSocket.accept());
                serverHolder[0] = server;
                serverReady.countDown();
                server.readLoop(new ProRpcWebSocket.Listener() {
                    @Override
                    public void onMessage(byte[] payload) {
                        messages.incrementAndGet();
                    }

                    @Override
                    public void onClosed(int code, String reason) {
                        closedCode.set(code);
                        closedReason.set(reason);
                        closed.countDown();
                    }
                });
                return null;
            });

            rawClient = connectRawWebSocket(serverSocket);
            assertTrue("server did not accept the raw client",
                    serverReady.await(5, TimeUnit.SECONDS));
            writeMaskedCloseFrame(rawClient.getOutputStream(), 4001, "bye");

            RawFrame echo = readFrame(rawClient.getInputStream());
            assertEquals(0x8, echo.opcode);
            assertEquals(2 + 3, echo.payload.length);
            assertEquals(4001, ((echo.payload[0] & 0xFF) << 8) | (echo.payload[1] & 0xFF));
            assertEquals("bye", new String(echo.payload, 2, 3, StandardCharsets.UTF_8));

            assertTrue("onClosed was not called", closed.await(5, TimeUnit.SECONDS));
            assertEquals(4001, closedCode.get());
            assertEquals("bye", closedReason.get());
            assertEquals(0, messages.get());
            assertTrue(serverHolder[0].isClosed());
            readerResult.get(5, TimeUnit.SECONDS);

            rawClient.setSoTimeout(5000);
            assertEquals("server should close after echoing the close frame",
                    -1, rawClient.getInputStream().read());
        } finally {
            if (rawClient != null) {
                rawClient.close();
            }
            if (serverHolder[0] != null) {
                serverHolder[0].close();
            }
            serverSocket.close();
            readerPool.shutdownNow();
        }
    }

    /**
     * 全链路（除 Android 部分）：真 WebSocket + 4 字节帧 + JSON-RPC。
     *
     * <p>服务端循环完全按 {@code ProRpcSession} 的做法写：解码帧 → 交给
     * {@link ProRpcDispatcher} → 把响应编回 JSON 帧。
     */
    @Test
    public void endToEndAuthorizeOverWebSocket() throws Exception {
        ServerSocket serverSocket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        ProRpcDispatcher dispatcher = new ProRpcDispatcher(new FakeHost());
        BlockingQueue<JsonObject> responses = new LinkedBlockingQueue<>();
        CountDownLatch serverReady = new CountDownLatch(1);
        ProRpcWebSocket[] clientHolder = new ProRpcWebSocket[1];
        try {
            pool.submit(() -> {
                ProRpcWebSocket server = ProRpcWebSocket.accept(serverSocket.accept());
                serverReady.countDown();
                server.readLoop(new ProRpcWebSocket.Listener() {
                    @Override
                    public void onMessage(byte[] payload) throws IOException {
                        ProRpcFrames.Message message = ProRpcFrames.decode(payload);
                        if (message == null || !message.isJson()) {
                            return;
                        }
                        JsonObject response = dispatcher.handleText(message.json);
                        if (response != null) {
                            server.sendBinary(ProRpcFrames.encodeJson(response.toString()));
                        }
                    }

                    @Override
                    public void onClosed(int code, String reason) {
                    }
                });
                return null;
            });

            ProRpcWebSocket client = ProRpcWebSocket.connect(
                    "127.0.0.1", serverSocket.getLocalPort(), "/");
            clientHolder[0] = client;
            assertTrue(serverReady.await(5, TimeUnit.SECONDS));
            pool.submit(() -> {
                client.readLoop(new ProRpcWebSocket.Listener() {
                    @Override
                    public void onMessage(byte[] payload) throws IOException {
                        ProRpcFrames.Message message = ProRpcFrames.decode(payload);
                        if (message != null && message.isJson()) {
                            responses.add(new JsonParser().parse(message.json).getAsJsonObject());
                        }
                    }

                    @Override
                    public void onClosed(int code, String reason) {
                    }
                });
                return null;
            });

            // 1) debug.authorize（token 在 params 里）。
            client.sendBinary(ProRpcFrames.encodeJson(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"debug.authorize\","
                            + "\"params\":{\"token\":\"tok\"}}"));
            JsonObject authorized = responses.poll(5, TimeUnit.SECONDS);
            assertNotNull("no authorize response", authorized);
            assertEquals(1, authorized.get("id").getAsInt());
            assertEquals("uuid-1", authorized.getAsJsonObject("result").get("uuid").getAsString());
            assertEquals(1, authorized.getAsJsonObject("result").get("protocolVersion").getAsInt());

            // 2) 二进制帧（36 字节通道 id）不应打断连接，也暂时没有回包。
            client.sendBinary(ProRpcFrames.encodeBinary(
                    UUID.randomUUID().toString(), new byte[]{9, 8, 7}));

            // 3) 没有顶层 token：Pro 的错误码是 2（"no token field ..."）。
            client.sendBinary(ProRpcFrames.encodeJson(
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"debug.stopAll\"}"));
            JsonObject noToken = responses.poll(5, TimeUnit.SECONDS);
            assertNotNull("no missing-token response", noToken);
            assertEquals(2, noToken.get("id").getAsInt());
            assertEquals(ProRpcDispatcher.CODE_TOKEN,
                    noToken.getAsJsonObject("error").get("code").getAsInt());

            // 4) token 不在本连接已授权集合：3 Forbidden。
            client.sendBinary(ProRpcFrames.encodeJson(
                    "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"debug.stopAll\","
                            + "\"token\":\"wrong\"}"));
            JsonObject forbidden = responses.poll(5, TimeUnit.SECONDS);
            assertNotNull("no forbidden response", forbidden);
            assertEquals(4, forbidden.get("id").getAsInt());
            assertEquals(ProRpcDispatcher.CODE_FORBIDDEN,
                    forbidden.getAsJsonObject("error").get("code").getAsInt());
            assertEquals("Forbidden",
                    forbidden.getAsJsonObject("error").get("message").getAsString());

            // 5) 带正确顶层 token：stopAll 返回 null result。
            client.sendBinary(ProRpcFrames.encodeJson(
                    "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"debug.stopAll\",\"token\":\"tok\"}"));
            JsonObject stopped = responses.poll(5, TimeUnit.SECONDS);
            assertNotNull("no stopAll response", stopped);
            assertEquals(3, stopped.get("id").getAsInt());
            assertTrue(stopped.get("result").isJsonNull());

            client.close();
        } finally {
            if (clientHolder[0] != null) {
                clientHolder[0].close();
            }
            serverSocket.close();
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------ dispatcher

    @Test
    public void authorizeReturnsAuthInfoAndEnablesTopLevelToken() throws Exception {
        FakeHost host = new FakeHost();
        ProRpcDispatcher dispatcher = new ProRpcDispatcher(host);

        JsonObject response = dispatcher.handle(
                request(1, "debug.authorize", params("token", "token-1")));
        assertNotNull(response);
        assertEquals("2.0", response.get("jsonrpc").getAsString());
        assertEquals(1, response.get("id").getAsInt());
        JsonObject result = response.getAsJsonObject("result");
        assertEquals("Xiaomi MI 8", result.get("deviceName").getAsString());
        assertEquals("device-id", result.get("deviceId").getAsString());
        assertEquals("uuid-1", result.get("uuid").getAsString());
        assertEquals("1.0.2", result.get("appVersion").getAsString());
        assertEquals(465L, result.get("appVersionCode").getAsLong());
        assertEquals(1, result.get("protocolVersion").getAsInt());
        assertEquals(1, host.authorizeCalls);

        JsonObject withoutToken = dispatcher.handle(request(2, "debug.listFiles", emptyParams()));
        assertEquals(ProRpcDispatcher.CODE_TOKEN,
                withoutToken.getAsJsonObject("error").get("code").getAsInt());
        assertEquals("no token field or token is not a string",
                withoutToken.getAsJsonObject("error").get("message").getAsString());

        JsonObject wrongToken = dispatcher.handle(
                withToken(request(3, "debug.listFiles", emptyParams()), "wrong"));
        assertEquals(ProRpcDispatcher.CODE_FORBIDDEN,
                wrongToken.getAsJsonObject("error").get("code").getAsInt());
        assertEquals("Forbidden",
                wrongToken.getAsJsonObject("error").get("message").getAsString());

        JsonObject withToken = dispatcher.handle(
                withToken(request(4, "debug.listFiles", params("dir", "")), "token-1"));
        assertNotNull(withToken.getAsJsonObject("result"));
        assertEquals(1, withToken.getAsJsonObject("result").get("files").getAsJsonArray().size());

        JsonObject runFile = dispatcher.handle(
                withToken(request(5, "debug.runFile", params("file", "main.js")), "token-1"));
        assertEquals("exec-1",
                runFile.getAsJsonObject("result").get("executionId").getAsString());

        JsonObject stop = dispatcher.handle(
                withToken(request(6, "debug.stop", params("executionId", "exec-1")), "token-1"));
        assertTrue(stop.get("result").isJsonNull());
        assertEquals(1, host.stopCalls);

        JsonObject stopAll = dispatcher.handle(
                withToken(request(7, "debug.stopAll", emptyParams()), "token-1"));
        assertTrue(stopAll.get("result").isJsonNull());
        assertEquals(1, host.stopAllCalls);

        JsonObject inspect = dispatcher.handle(
                withToken(request(8, "debug.inspectLayout", emptyParams()), "token-1"));
        assertEquals("<hierarchy/>",
                inspect.getAsJsonObject("result").get("data").getAsString());
    }

    @Test
    public void userRejectedTokenIsReportedAsForbidden() {
        FakeHost host = new FakeHost();
        host.allowAuthorize = false;
        ProRpcDispatcher dispatcher = new ProRpcDispatcher(host);
        JsonObject response = dispatcher.handle(
                request(1, "debug.authorize", params("token", "token-1")));
        assertEquals(ProRpcDispatcher.CODE_USER_REJECTED,
                response.getAsJsonObject("error").get("code").getAsInt());
        assertEquals("user rejected",
                response.getAsJsonObject("error").get("message").getAsString());
    }

    @Test
    public void paramsMustBeAnObject() {
        FakeHost host = new FakeHost();
        ProRpcDispatcher dispatcher = new ProRpcDispatcher(host);

        JsonObject request = new JsonObject();
        request.addProperty("jsonrpc", "2.0");
        request.addProperty("id", 9);
        request.addProperty("method", "debug.authorize");
        JsonArray params = new JsonArray();
        params.add("token");
        request.add("params", params);
        JsonObject response = dispatcher.handle(request);
        assertEquals(ProRpcDispatcher.CODE_PARAMS,
                response.getAsJsonObject("error").get("code").getAsInt());
        assertEquals("params should be a object",
                response.getAsJsonObject("error").get("message").getAsString());
        assertEquals(0, host.authorizeCalls);
    }

    @Test
    public void unknownMethodsAndNamespacesAreMethodNotFound() {
        FakeHost host = new FakeHost();
        ProRpcDispatcher dispatcher = new ProRpcDispatcher(host);
        JsonObject authorize = dispatcher.handle(
                request(1, "debug.authorize", params("token", "t")));
        assertNotNull(authorize.get("result"));

        JsonObject unknownDebug = dispatcher.handle(
                withToken(request(2, "debug.noSuchMethod", emptyParams()), "t"));
        assertEquals(ProRpcDispatcher.CODE_METHOD_NOT_FOUND,
                unknownDebug.getAsJsonObject("error").get("code").getAsInt());

        JsonObject unknownNamespace = dispatcher.handle(
                withToken(request(3, "vfs.readFile", emptyParams()), "t"));
        assertEquals(ProRpcDispatcher.CODE_METHOD_NOT_FOUND,
                unknownNamespace.getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    public void notificationHasNoResponseButStillRuns() {
        FakeHost host = new FakeHost();
        ProRpcDispatcher dispatcher = new ProRpcDispatcher(host);
        dispatcher.handle(request(1, "debug.authorize", params("token", "t")));

        JsonObject notification = new JsonObject();
        notification.addProperty("jsonrpc", "2.0");
        notification.addProperty("method", "debug.stopAll");
        notification.addProperty("token", "t");
        assertNull(dispatcher.handle(notification));
        assertEquals(1, host.stopAllCalls);
    }

    @Test
    public void malformedJsonIsRejectedWithoutCrashing() {
        ProRpcDispatcher dispatcher = new ProRpcDispatcher(new FakeHost());
        JsonObject response = dispatcher.handleText("not json at all");
        assertEquals(ProRpcDispatcher.CODE_PARAMS,
                response.getAsJsonObject("error").get("code").getAsInt());
        JsonObject arrayResponse = dispatcher.handleText("[]");
        assertEquals(ProRpcDispatcher.CODE_PARAMS,
                arrayResponse.getAsJsonObject("error").get("code").getAsInt());
    }

    // ------------------------------------------------------------ helpers

    private static JsonObject request(int id, String method, JsonObject params) {
        JsonObject request = new JsonObject();
        request.addProperty("jsonrpc", "2.0");
        request.addProperty("id", id);
        request.addProperty("method", method);
        if (params != null) {
            request.add("params", params);
        }
        return request;
    }

    private static JsonObject params(String name, String value) {
        JsonObject params = new JsonObject();
        params.addProperty(name, value);
        return params;
    }

    private static JsonObject emptyParams() {
        return new JsonObject();
    }

    private static JsonObject withToken(JsonObject request, String token) {
        request.addProperty("token", token);
        return request;
    }

    /**
     * 用裸 TCP 客户端完成一次 WebSocket 握手。close 回归测试需要观察对端是否收到
     * close 帧/EOF，{@link ProRpcWebSocket#connect} 会把这些字节吃掉。
     */
    private static Socket connectRawWebSocket(ServerSocket serverSocket) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(
                    InetAddress.getByName("127.0.0.1"), serverSocket.getLocalPort()), 5000);
            socket.setSoTimeout(5000);
            socket.setTcpNoDelay(true);
            String request = "GET / HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + serverSocket.getLocalPort() + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "\r\n";
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            InputStream in = socket.getInputStream();
            String status = readAsciiLine(in);
            if (status == null || !status.contains(" 101")) {
                throw new IOException("raw websocket handshake rejected: " + status);
            }
            while (true) {
                String line = readAsciiLine(in);
                if (line == null) {
                    throw new IOException("raw websocket handshake ended unexpectedly");
                }
                if (line.isEmpty()) {
                    break;
                }
            }
            return socket;
        } catch (IOException error) {
            socket.close();
            throw error;
        }
    }

    /** 客户端 → 服务端必须加掩码；这里只覆盖小 payload 的 close 帧。 */
    private static void writeMaskedCloseFrame(OutputStream out, int code, String reason)
            throws IOException {
        byte[] reasonBytes = reason.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[2 + reasonBytes.length];
        payload[0] = (byte) (code >> 8);
        payload[1] = (byte) code;
        System.arraycopy(reasonBytes, 0, payload, 2, reasonBytes.length);
        if (payload.length > 125) {
            throw new IOException("test close payload too large");
        }
        byte[] mask = new byte[]{0x12, 0x34, 0x56, 0x78};
        byte[] frame = new byte[2 + mask.length + payload.length];
        frame[0] = (byte) 0x88;
        frame[1] = (byte) (0x80 | payload.length);
        System.arraycopy(mask, 0, frame, 2, mask.length);
        for (int i = 0; i < payload.length; i++) {
            frame[6 + i] = (byte) (payload[i] ^ mask[i & 3]);
        }
        out.write(frame);
        out.flush();
    }

    /** 读一个服务端发来的（无掩码）帧；测试只用到 close/小 payload。 */
    private static RawFrame readFrame(InputStream in) throws IOException {
        int first = readByte(in);
        int second = readByte(in);
        if ((second & 0x80) != 0) {
            throw new IOException("server frame must not be masked");
        }
        long length = second & 0x7F;
        if (length == 126) {
            length = ((long) readByte(in) << 8) | readByte(in);
        } else if (length == 127) {
            length = 0;
            for (int i = 0; i < 8; i++) {
                length = (length << 8) | readByte(in);
            }
        }
        if (length < 0 || length > 64 * 1024) {
            throw new IOException("unexpected frame length: " + length);
        }
        byte[] payload = new byte[(int) length];
        int offset = 0;
        while (offset < payload.length) {
            int count = in.read(payload, offset, payload.length - offset);
            if (count < 0) {
                throw new IOException("unexpected EOF in frame payload");
            }
            offset += count;
        }
        return new RawFrame(first & 0x0F, payload);
    }

    private static String readAsciiLine(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(128);
        int value;
        while ((value = in.read()) != -1) {
            if (value == '\n') {
                byte[] bytes = out.toByteArray();
                int length = bytes.length;
                if (length > 0 && bytes[length - 1] == '\r') {
                    length--;
                }
                return new String(bytes, 0, length, StandardCharsets.US_ASCII);
            }
            out.write(value);
        }
        return out.size() == 0 ? null
                : new String(out.toByteArray(), StandardCharsets.US_ASCII);
    }

    private static int readByte(InputStream in) throws IOException {
        int value = in.read();
        if (value < 0) {
            throw new IOException("unexpected EOF");
        }
        return value;
    }

    private static final class RawFrame {

        final int opcode;
        final byte[] payload;

        RawFrame(int opcode, byte[] payload) {
            this.opcode = opcode;
            this.payload = payload;
        }
    }

    /** 假的设备能力：只返回固定 DTO，用来验证 JSON-RPC 结构。 */
    private static final class FakeHost implements ProRpcDispatcher.Host {

        boolean allowAuthorize = true;
        int authorizeCalls;
        int stopCalls;
        int stopAllCalls;

        @Override
        public String deviceName() {
            return "Xiaomi MI 8";
        }

        @Override
        public String deviceId() {
            return "device-id";
        }

        @Override
        public String uuid() {
            return "uuid-1";
        }

        @Override
        public String appVersion() {
            return "1.0.2";
        }

        @Override
        public long appVersionCode() {
            return 465;
        }

        @Override
        public boolean authorize(String token) {
            authorizeCalls++;
            return allowAuthorize;
        }

        @Override
        public JsonObject inspectLayout(JsonObject params) {
            JsonObject result = new JsonObject();
            result.addProperty("width", 1080);
            result.addProperty("height", 2248);
            result.addProperty("screenshot", "");
            result.addProperty("data", "<hierarchy/>");
            return result;
        }

        @Override
        public JsonObject listFiles(JsonObject params) {
            JsonObject result = new JsonObject();
            result.addProperty("dir", "/sdcard/脚本");
            JsonArray files = new JsonArray();
            JsonObject entry = new JsonObject();
            entry.addProperty("name", "main.js");
            entry.addProperty("path", "main.js");
            entry.addProperty("isDirectory", false);
            files.add(entry);
            result.add("files", files);
            return result;
        }

        @Override
        public JsonObject runFile(JsonObject params) {
            JsonObject result = new JsonObject();
            result.addProperty("executionId", "exec-1");
            result.addProperty("executionMode", "file");
            return result;
        }

        @Override
        public JsonObject runProject(JsonObject params) {
            JsonObject result = new JsonObject();
            result.addProperty("executionId", "exec-2");
            result.addProperty("executionMode", "project");
            return result;
        }

        @Override
        public void stop(JsonObject params) {
            stopCalls++;
        }

        @Override
        public void stopAll() {
            stopAllCalls++;
        }
    }
}
