package com.jdkshen.aijspro.pluginclient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Frame codec for the Auto.js Pro 9 "ws-rpc" debug protocol (device side).
 *
 * <p>这个协议是 Pro 9.3.11 的「电脑连接」通道（{@code okhttp3/internal/io} 假包里的
 * {@code RpcService.WebSocketJsonRpcConnection}）。和自研扩展的
 * {@link DevPluginFrames} 不是同一代：
 * <ul>
 *   <li>Pro 的每一帧都是 <b>4 字节大端 int 类型</b> + 负载，装载在 WebSocket
 *       的 <b>二进制</b> 消息里（JSON 也是二进制帧）；</li>
 *   <li>类型 {@code 1} = UTF-8 JSON（JSON-RPC 2.0 消息体）；</li>
 *   <li>类型 {@code 2} = 36 字节 ASCII 通道 id + 二进制数据；</li>
 *   <li>其它类型对端直接丢弃（Pro 的接收侧 {@code return false}）。</li>
 * </ul>
 *
 * <p>与反编译结果一一对应：
 * {@code new DataOutputStream(out).writeInt(1); mapper.writeValue(out, json);} 和
 * {@code writeInt(2); out.write(id.getBytes(UTF_8), 0, 36); out.write(data);}。
 *
 * <p>本类不依赖 Android，可单独用 javac 编译/单测（见
 * {@code apps/app/src/test/java/com/jdkshen/aijspro/pluginclient/ProRpcProtocolTest.java}）。
 */
public final class ProRpcFrames {

    /** 类型 1：UTF-8 JSON（JSON-RPC 2.0 消息体）。 */
    public static final int TYPE_JSON = 1;
    /** 类型 2：36 字节 ASCII 通道 id + 二进制负载。 */
    public static final int TYPE_BINARY = 2;

    /** Pro 侧 {@code check(id.length == 36)}；通道 id 固定 36 个字符。 */
    public static final int CHANNEL_ID_LENGTH = 36;
    /** 帧头：4 字节大端类型。 */
    public static final int HEADER_LENGTH = 4;
    /**
     * 单帧上限。Pro 侧没有显式上限，但一帧要整个读进内存；256MB 是自研协议
     * （{@link DevPluginFrames#MAX_FRAME_LENGTH}）的口径，这里保持一致，防止
     * 恶意/错乱对端逼我们分配超大数组。
     */
    public static final int MAX_FRAME_LENGTH = 256 * 1024 * 1024;

    private ProRpcFrames() {
    }

    /** 一帧解码后的结果：类型 1 看 {@link #json}，类型 2 看 {@link #channelId}+{@link #payload}。 */
    public static final class Message {

        public final int type;
        public final String json;
        public final String channelId;
        public final byte[] payload;

        private Message(int type, String json, String channelId, byte[] payload) {
            this.type = type;
            this.json = json;
            this.channelId = channelId;
            this.payload = payload;
        }

        public boolean isJson() {
            return type == TYPE_JSON;
        }

        public boolean isBinary() {
            return type == TYPE_BINARY;
        }
    }

    /** 编码 JSON 帧：[int32 BE 1][UTF-8 JSON]。 */
    public static byte[] encodeJson(String json) {
        byte[] payload = (json == null ? "null" : json).getBytes(StandardCharsets.UTF_8);
        byte[] frame = new byte[HEADER_LENGTH + payload.length];
        putInt32(frame, 0, TYPE_JSON);
        System.arraycopy(payload, 0, frame, HEADER_LENGTH, payload.length);
        return frame;
    }

    /**
     * 编码二进制帧：[int32 BE 2][36 字节 ASCII 通道 id][数据]。
     *
     * @throws IllegalArgumentException 通道 id 不是 36 个 ASCII 字符（Pro 侧抛
     *                                  {@code IllegalStateException("Check failed.")}）
     */
    public static byte[] encodeBinary(String channelId, byte[] payload) {
        if (!isValidChannelId(channelId)) {
            throw new IllegalArgumentException("channel id must be 36 ASCII characters: " + channelId);
        }
        byte[] id = channelId.getBytes(StandardCharsets.US_ASCII);
        byte[] data = payload == null ? new byte[0] : payload;
        ByteArrayOutputStream out = new ByteArrayOutputStream(HEADER_LENGTH + CHANNEL_ID_LENGTH + data.length);
        byte[] header = new byte[HEADER_LENGTH];
        putInt32(header, 0, TYPE_BINARY);
        out.write(header, 0, HEADER_LENGTH);
        out.write(id, 0, id.length);
        out.write(data, 0, data.length);
        return out.toByteArray();
    }

    /**
     * 解码一帧。
     *
     * @return 解码结果；类型不是 1/2 时返回 {@code null}（Pro 对未知类型直接丢弃）
     * @throws IOException 帧短于 4 字节、类型 2 缺通道 id，或超过 {@link #MAX_FRAME_LENGTH}
     */
    public static Message decode(byte[] frame) throws IOException {
        if (frame == null || frame.length < HEADER_LENGTH) {
            throw new IOException("frame shorter than " + HEADER_LENGTH + " bytes");
        }
        if (frame.length > MAX_FRAME_LENGTH) {
            throw new IOException("frame too large: " + frame.length);
        }
        int type = readInt32(frame, 0);
        switch (type) {
            case TYPE_JSON:
                return new Message(type, new String(frame, HEADER_LENGTH,
                        frame.length - HEADER_LENGTH, StandardCharsets.UTF_8), null, null);
            case TYPE_BINARY: {
                if (frame.length < HEADER_LENGTH + CHANNEL_ID_LENGTH) {
                    throw new IOException("binary frame shorter than " + (HEADER_LENGTH + CHANNEL_ID_LENGTH));
                }
                String channelId = new String(frame, HEADER_LENGTH, CHANNEL_ID_LENGTH,
                        StandardCharsets.US_ASCII);
                int offset = HEADER_LENGTH + CHANNEL_ID_LENGTH;
                byte[] payload = new byte[frame.length - offset];
                System.arraycopy(frame, offset, payload, 0, payload.length);
                return new Message(type, null, channelId, payload);
            }
            default:
                return null;
        }
    }

    public static boolean isValidChannelId(String channelId) {
        if (channelId == null || channelId.length() != CHANNEL_ID_LENGTH) {
            return false;
        }
        for (int i = 0; i < channelId.length(); i++) {
            char c = channelId.charAt(i);
            if (c <= 0x1F || c > 0x7E) {
                return false;
            }
        }
        return true;
    }

    static int readInt32(byte[] source, int offset) {
        return ((source[offset] & 0xFF) << 24)
                | ((source[offset + 1] & 0xFF) << 16)
                | ((source[offset + 2] & 0xFF) << 8)
                | (source[offset + 3] & 0xFF);
    }

    static void putInt32(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >> 24);
        target[offset + 1] = (byte) (value >> 16);
        target[offset + 2] = (byte) (value >> 8);
        target[offset + 3] = (byte) value;
    }
}
