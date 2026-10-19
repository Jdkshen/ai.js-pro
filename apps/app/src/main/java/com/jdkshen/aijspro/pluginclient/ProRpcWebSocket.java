package com.jdkshen.aijspro.pluginclient;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 最小可用的 RFC 6455 WebSocket 实现（服务端 + 客户端），供 Pro 的 ws-rpc 通道使用。
 *
 * <p>为什么手写而不是用现成库：仓库里只有 OkHttp 3 的 <b>WebSocket 客户端</b>
 * （{@code okhttp3.WebSocket}，见 {@link JsonWebSocket}），它没有服务端；Pro 的
 * 「电脑连接」在手机侧是 <b>WebSocket 服务端</b>（{@code RpcService.WebSocketServer}，
 * 构造参数 {@code new InetSocketAddress(0)}），OkHttp 无法承担。这里只实现协议里
 * 真正用到的子集：
 * <ul>
 *   <li>握手：服务端校验 {@code Sec-WebSocket-Key} 并回 {@code 101} + Accept；
 *       客户端发标准请求并校验 101/Accept；</li>
 *   <li>数据帧：文本/二进制、分片（FIN=0 + continuation）、ping/pong、close；</li>
 *   <li>客户端发出的帧按 RFC 要求加掩码，服务端不加；接收侧两种都容忍。</li>
 * </ul>
 *
 * <p>负载本身是 {@link ProRpcFrames} 的「4 字节类型 + ...」结构，所以本类只管
 * WebSocket 消息边界，不解析业务。
 *
 * <p>不依赖 Android：可用 javac 单独编译并做本机回环测试。
 */
public final class ProRpcWebSocket implements Closeable {

    /** 收到一条完整的 WebSocket 消息（文本帧按 UTF-8 字节给出）。 */
    public interface Listener {

        void onMessage(byte[] payload) throws IOException;

        void onClosed(int code, String reason);
    }

    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_HEADER_LINE = 8 * 1024;
    private static final int MAX_MESSAGE_LENGTH = 64 * 1024 * 1024;

    private static final int OP_CONTINUATION = 0x0;
    private static final int OP_TEXT = 0x1;
    private static final int OP_BINARY = 0x2;
    private static final int OP_CLOSE = 0x8;
    private static final int OP_PING = 0x9;
    private static final int OP_PONG = 0xA;

    private static final char[] BASE64_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Socket mSocket;
    private final InputStream mInput;
    private final OutputStream mOutput;
    private final boolean mClient;
    private final Object mWriteLock = new Object();
    /** 只保护 mClosed 的 0→1 跃迁；绝不套在 socket I/O 或 mWriteLock 上。 */
    private final Object mCloseStateLock = new Object();
    private volatile boolean mClosed;

    private ProRpcWebSocket(Socket socket, boolean client) throws IOException {
        mSocket = socket;
        mClient = client;
        mInput = new BufferedInputStream(socket.getInputStream());
        mOutput = new BufferedOutputStream(socket.getOutputStream());
    }

    /**
     * 服务端：完成握手后返回已建立的连接。调用方应事先给 socket 设好读超时，
     * 避免半开连接把接受线程卡死。
     */
    public static ProRpcWebSocket accept(Socket socket) throws IOException {
        ProRpcWebSocket webSocket = new ProRpcWebSocket(socket, false);
        try {
            webSocket.handshakeServer();
            return webSocket;
        } catch (IOException error) {
            webSocket.rejectHandshake();
            webSocket.closeQuietly();
            throw error;
        }
    }

    /** 客户端：连接 {@code ws://host:port/path} 并完成握手。 */
    public static ProRpcWebSocket connect(String host, int port, String path) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(10000);
            ProRpcWebSocket webSocket = new ProRpcWebSocket(socket, true);
            webSocket.handshakeClient(host, port, path == null || path.isEmpty() ? "/" : path);
            socket.setSoTimeout(0);
            return webSocket;
        } catch (IOException error) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            throw error;
        }
    }

    public boolean isClosed() {
        return mClosed;
    }

    /** 阻塞读取线程：把每条完整消息交给 {@code listener}，连接结束时调用 onClosed。 */
    public void readLoop(Listener listener) throws IOException {
        ByteArrayOutputStream buffer = null;
        while (!mClosed) {
            Frame frame = readFrame();
            switch (frame.opcode) {
                case OP_PING:
                    writeFrame(OP_PONG, frame.payload);
                    break;
                case OP_PONG:
                    break;
                case OP_CLOSE: {
                    int code = 1000;
                    String reason = "";
                    if (frame.payload.length >= 2) {
                        code = ((frame.payload[0] & 0xFF) << 8) | (frame.payload[1] & 0xFF);
                    }
                    if (frame.payload.length > 2) {
                        reason = new String(frame.payload, 2, frame.payload.length - 2,
                                StandardCharsets.UTF_8);
                    }
                    closeFromReader(code, reason);
                    listener.onClosed(code, reason);
                    return;
                }
                case OP_TEXT:
                case OP_BINARY:
                    if (frame.fin) {
                        listener.onMessage(frame.payload);
                    } else {
                        buffer = new ByteArrayOutputStream(
                                Math.max(256, frame.payload.length * 2));
                        buffer.write(frame.payload, 0, frame.payload.length);
                    }
                    break;
                case OP_CONTINUATION:
                    if (buffer == null) {
                        throw new IOException("unexpected continuation frame");
                    }
                    buffer.write(frame.payload, 0, frame.payload.length);
                    if (buffer.size() > MAX_MESSAGE_LENGTH) {
                        throw new IOException("websocket message too large");
                    }
                    if (frame.fin) {
                        listener.onMessage(buffer.toByteArray());
                        buffer = null;
                    }
                    break;
                default:
                    throw new IOException("unsupported websocket opcode: " + frame.opcode);
            }
        }
    }

    /** 发一条二进制消息（Pro 的 JSON/二进制帧都走这里）。 */
    public void sendBinary(byte[] payload) throws IOException {
        writeFrame(OP_BINARY, payload);
    }

    public void sendText(String text) throws IOException {
        writeFrame(OP_TEXT, text.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void close() {
        close(1000, "");
    }

    /**
     * 外部关闭：立即 abort socket，<b>绝不</b>在调用线程写 close 帧或 flush。
     *
     * <p>调用方可能是 Android 主线程（{@code Service.onDestroy → ProRpcServer.stop →
     * ProRpcSession.close}）。旧实现会先拿 {@link #mWriteLock} 写 close 帧并 flush：
     * 主线程会直接抛 {@code NetworkOnMainThreadException}，而 flush 被对端背压卡住时
     * 还会让 {@link #closeQuietly()} 永远等不到锁。因此这里先置 closed，再不取写锁
     * 地关闭 socket；对端看到 EOF，读线程随之解除。
     *
     * <p>协议层的 close 帧回应只在 {@link #closeFromReader(int, String)} 的读线程路径
     * 里 best-effort 发送，{@code code}/{@code reason} 保留是为了兼容调用方。
     */
    public void close(int code, String reason) {
        markClosed();
        closeQuietly();
    }

    /**
     * 读线程收到对端 close 帧后的正常协议回应：best-effort 回一帧 close，然后关 socket。
     *
     * <p>只有读线程会走到这里，因此即使 write/flush 因背压阻塞，也只占用该连接的读线程；
     * 外部 {@link #close(int, String)} 不取 {@link #mWriteLock}，仍可立即 abort socket
     * 把它唤醒。已经由外部先关闭时不再回帧。
     */
    private void closeFromReader(int code, String reason) {
        if (markClosed()) {
            synchronized (mWriteLock) {
                try {
                    writeFrameLocked(OP_CLOSE, closePayload(code, reason));
                } catch (IOException ignored) {
                }
            }
        }
        closeQuietly();
    }

    /** @return true 表示本次调用完成了 closed 的 0→1 跃迁。 */
    private boolean markClosed() {
        synchronized (mCloseStateLock) {
            if (mClosed) {
                return false;
            }
            mClosed = true;
            return true;
        }
    }

    // ---------------------------------------------------------------- 握手

    private void handshakeServer() throws IOException {
        String requestLine = readAsciiLine(mInput, MAX_HEADER_LINE);
        if (requestLine == null) {
            throw new EOFException("websocket handshake: peer closed before request line");
        }
        Map<String, String> headers = readHeaders();
        if (!requestLine.startsWith("GET ")) {
            throw new IOException("not a websocket handshake: " + requestLine);
        }
        String key = headers.get("sec-websocket-key");
        if (key == null || key.isEmpty()) {
            throw new IOException("websocket handshake without Sec-WebSocket-Key");
        }
        String accept = base64(sha1((key + WS_GUID).getBytes(StandardCharsets.US_ASCII)));
        String protocol = headers.get("sec-websocket-protocol");
        StringBuilder response = new StringBuilder(192)
                .append("HTTP/1.1 101 Switching Protocols\r\n")
                .append("Upgrade: websocket\r\n")
                .append("Connection: Upgrade\r\n")
                .append("Sec-WebSocket-Accept: ").append(accept).append("\r\n");
        if (protocol != null && !protocol.isEmpty()) {
            // 有些客户端会要求子协议；Pro 的扩展不带，但回显第一个是被动兼容的最低成本。
            int comma = protocol.indexOf(',');
            response.append("Sec-WebSocket-Protocol: ")
                    .append((comma < 0 ? protocol : protocol.substring(0, comma)).trim())
                    .append("\r\n");
        }
        response.append("\r\n");
        mOutput.write(response.toString().getBytes(StandardCharsets.US_ASCII));
        mOutput.flush();
    }

    private void handshakeClient(String host, int port, String path) throws IOException {
        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        String key = base64(nonce);
        String request = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + host + ":" + port + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "\r\n";
        mOutput.write(request.getBytes(StandardCharsets.US_ASCII));
        mOutput.flush();
        String statusLine = readAsciiLine(mInput, MAX_HEADER_LINE);
        if (statusLine == null || !statusLine.contains(" 101")) {
            throw new IOException("websocket handshake rejected: " + statusLine);
        }
        Map<String, String> headers = readHeaders();
        String accept = headers.get("sec-websocket-accept");
        if (accept != null) {
            String expected = base64(sha1((key + WS_GUID).getBytes(StandardCharsets.US_ASCII)));
            if (!expected.equals(accept)) {
                throw new IOException("websocket handshake returned a bad Sec-WebSocket-Accept");
            }
        }
    }

    private Map<String, String> readHeaders() throws IOException {
        Map<String, String> headers = new HashMap<>();
        String line;
        while ((line = readAsciiLine(mInput, MAX_HEADER_LINE)) != null) {
            if (line.isEmpty()) {
                break;
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim().toLowerCase(Locale.US),
                        line.substring(colon + 1).trim());
            }
        }
        return headers;
    }

    /** 握手失败时尽量给对端一个 400，而不是让它干等。 */
    private void rejectHandshake() {
        try {
            mOutput.write("HTTP/1.1 400 Bad Request\r\nConnection: close\r\nContent-Length: 0\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            mOutput.flush();
        } catch (IOException ignored) {
        }
    }

    // ---------------------------------------------------------------- 帧

    private static final class Frame {

        final boolean fin;
        final int opcode;
        final byte[] payload;

        Frame(boolean fin, int opcode, byte[] payload) {
            this.fin = fin;
            this.opcode = opcode;
            this.payload = payload;
        }
    }

    private Frame readFrame() throws IOException {
        int first = mInput.read();
        if (first < 0) {
            throw new EOFException("websocket closed by peer");
        }
        int second = readByte();
        boolean fin = (first & 0x80) != 0;
        int opcode = first & 0x0F;
        boolean masked = (second & 0x80) != 0;
        long length = second & 0x7F;
        if (length == 126) {
            length = ((long) readByte() << 8) | readByte();
        } else if (length == 127) {
            length = 0;
            for (int i = 0; i < 8; i++) {
                length = (length << 8) | readByte();
            }
        }
        if (length < 0 || length > MAX_MESSAGE_LENGTH) {
            throw new IOException("invalid websocket frame length: " + length);
        }
        byte[] mask = null;
        if (masked) {
            mask = new byte[4];
            readFully(mask, 0, mask.length);
        }
        byte[] payload = new byte[(int) length];
        readFully(payload, 0, payload.length);
        if (mask != null) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] ^= mask[i & 3];
            }
        }
        return new Frame(fin, opcode, payload);
    }

    private void writeFrame(int opcode, byte[] payload) throws IOException {
        synchronized (mWriteLock) {
            if (mClosed) {
                throw new IOException("websocket is closed");
            }
            writeFrameLocked(opcode, payload);
        }
    }

    private void writeFrameLocked(int opcode, byte[] payload) throws IOException {
        byte[] data = payload == null ? new byte[0] : payload;
        int length = data.length;
        int extra = length < 126 ? 0 : length <= 0xFFFF ? 2 : 8;
        int maskLength = mClient ? 4 : 0;
        byte[] frame = new byte[2 + extra + maskLength + length];
        frame[0] = (byte) (0x80 | (opcode & 0x0F));
        int offset = 2;
        if (length < 126) {
            frame[1] = (byte) (length | (mClient ? 0x80 : 0));
        } else if (length <= 0xFFFF) {
            frame[1] = (byte) (126 | (mClient ? 0x80 : 0));
            frame[2] = (byte) (length >> 8);
            frame[3] = (byte) length;
            offset = 4;
        } else {
            frame[1] = (byte) (127 | (mClient ? 0x80 : 0));
            for (int i = 0; i < 8; i++) {
                frame[2 + i] = (byte) (((long) length) >>> (56 - 8 * i));
            }
            offset = 10;
        }
        if (mClient) {
            byte[] mask = new byte[4];
            RANDOM.nextBytes(mask);
            System.arraycopy(mask, 0, frame, offset, 4);
            offset += 4;
            for (int i = 0; i < length; i++) {
                frame[offset + i] = (byte) (data[i] ^ mask[i & 3]);
            }
        } else {
            System.arraycopy(data, 0, frame, offset, length);
        }
        mOutput.write(frame);
        mOutput.flush();
    }

    private static byte[] closePayload(int code, String reason) {
        byte[] text = reason == null ? new byte[0] : reason.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[2 + text.length];
        payload[0] = (byte) (code >> 8);
        payload[1] = (byte) code;
        System.arraycopy(text, 0, payload, 2, text.length);
        return payload;
    }

    private int readByte() throws IOException {
        int value = mInput.read();
        if (value < 0) {
            throw new EOFException("websocket closed by peer");
        }
        return value;
    }

    private void readFully(byte[] target, int offset, int length) throws IOException {
        int read = 0;
        while (read < length) {
            int count = mInput.read(target, offset + read, length - read);
            if (count < 0) {
                throw new EOFException("websocket closed by peer");
            }
            read += count;
        }
    }

    private void closeQuietly() {
        try {
            mSocket.close();
        } catch (IOException ignored) {
        }
    }

    // ---------------------------------------------------------------- 工具

    private static String readAsciiLine(InputStream in, int maxLength) throws IOException {
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
            if (out.size() >= maxLength) {
                throw new IOException("websocket header line too long");
            }
            out.write(value);
        }
        return out.size() == 0 ? null : new String(out.toByteArray(), StandardCharsets.US_ASCII);
    }

    private static byte[] sha1(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(data);
        } catch (NoSuchAlgorithmException error) {
            throw new AssertionError(error);
        }
    }

    /** 标准 Base64（带填充）。手写以避开 Android 上 {@code java.util.Base64} 的 API 级别限制。 */
    static String base64(byte[] data) {
        StringBuilder builder = new StringBuilder(((data.length + 2) / 3) * 4);
        int i = 0;
        while (i + 2 < data.length) {
            int chunk = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8) | (data[i + 2] & 0xFF);
            builder.append(BASE64_ALPHABET[(chunk >> 18) & 0x3F])
                    .append(BASE64_ALPHABET[(chunk >> 12) & 0x3F])
                    .append(BASE64_ALPHABET[(chunk >> 6) & 0x3F])
                    .append(BASE64_ALPHABET[chunk & 0x3F]);
            i += 3;
        }
        int remaining = data.length - i;
        if (remaining == 1) {
            int chunk = (data[i] & 0xFF) << 16;
            builder.append(BASE64_ALPHABET[(chunk >> 18) & 0x3F])
                    .append(BASE64_ALPHABET[(chunk >> 12) & 0x3F])
                    .append("==");
        } else if (remaining == 2) {
            int chunk = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8);
            builder.append(BASE64_ALPHABET[(chunk >> 18) & 0x3F])
                    .append(BASE64_ALPHABET[(chunk >> 12) & 0x3F])
                    .append(BASE64_ALPHABET[(chunk >> 6) & 0x3F])
                    .append('=');
        }
        return builder.toString();
    }
}
