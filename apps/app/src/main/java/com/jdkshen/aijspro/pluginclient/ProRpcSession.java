package com.jdkshen.aijspro.pluginclient;

import android.util.Log;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;

/**
 * 一条 ws-rpc 连接（手机侧服务端接受的，或「手机回连电脑」时主动建立的）。
 *
 * <p>读线程把 WebSocket 消息按 {@link ProRpcFrames} 拆成
 * 「类型 1 JSON / 类型 2 二进制」，JSON 交给 {@link ProRpcDispatcher}，
 * 响应再按同一帧格式发回（类型 1 的帧同样装在 WebSocket <b>二进制</b> 消息里，
 * 与 Pro 的 {@code webSocket.send(byte[])} 一致）。
 */
final class ProRpcSession implements ProRpcWebSocket.Listener {

    private static final String LOG_TAG = "ProRpcSession";

    private final ProRpcServer mOwner;
    private final ProRpcWebSocket mWebSocket;
    private final ProRpcDispatcher mDispatcher;
    private final CountDownLatch mClosed = new CountDownLatch(1);
    private volatile boolean mCloseStarted;
    /** 只有本连接收到过成功的 debug.authorize 才为 true；TCP/WS 建立不算。 */
    private volatile boolean mAuthorized;

    ProRpcSession(ProRpcServer owner, ProRpcWebSocket webSocket) {
        mOwner = owner;
        mWebSocket = webSocket;
        mDispatcher = new ProRpcDispatcher(owner.newHost());
    }

    void start() {
        Thread thread = new Thread(this::readLoop, "pro-rpc-session");
        thread.setDaemon(true);
        thread.start();
    }

    private void readLoop() {
        try {
            mWebSocket.readLoop(this);
        } catch (IOException error) {
            if (!mCloseStarted) {
                Log.d(LOG_TAG, "session closed: " + error.getMessage());
            }
        } catch (RuntimeException error) {
            Log.w(LOG_TAG, "protocol error", error);
        } finally {
            close();
        }
    }

    @Override
    public void onMessage(byte[] payload) {
        ProRpcFrames.Message message;
        try {
            message = ProRpcFrames.decode(payload);
        } catch (IOException error) {
            Log.w(LOG_TAG, "bad frame: " + error.getMessage());
            close();
            return;
        }
        if (message == null) {
            Log.d(LOG_TAG, "dropping frame with unknown type");
            return;
        }
        if (message.isJson()) {
            JsonObject response = mDispatcher.handleText(message.json);
            if (response != null) {
                sendJson(response);
            }
            notifyAuthorizedIfNeeded();
            return;
        }
        // 类型 2 的二进制通道（36 字节 ASCII 通道 id）：MVP 里没有挂起中的传输，
        // 收到就记一条日志（Pro 侧用于文件同步/终端数据，还没实现）。
        Log.d(LOG_TAG, "binary frame on channel " + message.channelId
                + " (" + message.payload.length + " bytes) ignored");
    }

    @Override
    public void onClosed(int code, String reason) {
        if (!mCloseStarted) {
            Log.d(LOG_TAG, "websocket closed by peer: " + code + " " + reason);
        }
        close();
    }

    void sendJson(JsonObject json) {
        if (mCloseStarted || json == null) {
            return;
        }
        try {
            mWebSocket.sendBinary(ProRpcFrames.encodeJson(json.toString()));
        } catch (IOException error) {
            Log.d(LOG_TAG, "send failed: " + error.getMessage());
            close();
        }
    }

    void sendBinary(String channelId, byte[] payload) {
        if (mCloseStarted) {
            return;
        }
        try {
            mWebSocket.sendBinary(ProRpcFrames.encodeBinary(channelId, payload));
        } catch (IOException error) {
            Log.d(LOG_TAG, "send binary failed: " + error.getMessage());
            close();
        }
    }

    /**
     * 本连接是否已经通过 {@code debug.authorize}。
     *
     * <p>回连线程只有看到 true 才把 ClientState 置为 CONNECTED；单纯 TCP/WebSocket
     * 连上、电脑端还没发 authorize 时仍是 CONNECTING，也不计入 clients。
     */
    boolean isAuthorized() {
        return mAuthorized;
    }

    /**
     * 成功 authorize 后通知 owner。状态机只在会话读线程上检查，避免并发读
     * dispatcher 内部的 HashSet；重复请求不会重复通知。
     */
    private void notifyAuthorizedIfNeeded() {
        if (mAuthorized) {
            return;
        }
        if (!mDispatcher.authorizedTokens().isEmpty()) {
            mAuthorized = true;
            mOwner.onSessionAuthorized(this);
        }
    }

    /** 阻塞到连接结束（回连模式下用来等「连接断开」）。 */
    void awaitClosed() throws InterruptedException {
        mClosed.await();
    }

    void close() {
        if (mCloseStarted) {
            return;
        }
        mCloseStarted = true;
        mWebSocket.close();
        mClosed.countDown();
        mOwner.onSessionClosed(this);
    }
}
