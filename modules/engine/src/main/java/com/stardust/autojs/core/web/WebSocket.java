package com.stardust.autojs.core.web;

import androidx.annotation.Nullable;

import com.stardust.autojs.core.eventloop.EventEmitter;
import com.stardust.autojs.runtime.ScriptRuntime;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Auto.js Pro 的 {@code web.newWebSocket(url)} 返回对象（对齐 Pro 的 core/web/WebSocket）。
 *
 * <p>事件名与回调参数与 Pro 一致（参数末尾附加本对象）：
 * <pre>
 *   open(response, ws) / text(text, ws) / binary(bytes, ws)
 *   closing(code, reason, ws) / closed(code, reason, ws) / failure(error, response, ws)
 * </pre>
 * 事件投递到脚本线程（自研统一行为；Pro 默认在 IO 线程、{@code eventThread:'this'} 时投递到脚本线程）。
 *
 * <p>脚本退出时自动 cancel（挂到 {@link ScriptRuntime#addCleanable}），
 * closed/failure/close()/cancel() 后不再保留清理钩子（与 Pro removeExitListener 等价）。
 */
public class WebSocket extends EventEmitter {

    private final ScriptRuntime mRuntime;
    private final okhttp3.WebSocket mWebSocket;
    private volatile boolean mFinished = false;
    private final AutoCloseable mCleaner;

    public WebSocket(ScriptRuntime runtime, String url) {
        super(runtime.bridges, runtime.timers.getMainTimer());
        mRuntime = runtime;
        OkHttpClient client = new OkHttpClient();
        mWebSocket = client.newWebSocket(new Request.Builder().url(url).build(), new WebSocketListener() {
            @Override
            public void onOpen(okhttp3.WebSocket webSocket, Response response) {
                emit("open", response, WebSocket.this);
            }

            @Override
            public void onMessage(okhttp3.WebSocket webSocket, String text) {
                emit("text", text, WebSocket.this);
            }

            @Override
            public void onMessage(okhttp3.WebSocket webSocket, ByteString bytes) {
                emit("binary", bytes, WebSocket.this);
            }

            @Override
            public void onClosing(okhttp3.WebSocket webSocket, int code, String reason) {
                emit("closing", code, reason, WebSocket.this);
            }

            @Override
            public void onClosed(okhttp3.WebSocket webSocket, int code, String reason) {
                finish();
                emit("closed", code, reason, WebSocket.this);
            }

            @Override
            public void onFailure(okhttp3.WebSocket webSocket, Throwable t, @Nullable Response response) {
                finish();
                emit("failure", t == null ? new Exception("WebSocket failure") : t, response, WebSocket.this);
            }
        });
        mCleaner = () -> {
            if (!mFinished) {
                mFinished = true;
                mWebSocket.cancel();
            }
        };
        runtime.addCleanable(mCleaner);
    }

    private void finish() {
        if (!mFinished) {
            mFinished = true;
            mRuntime.removeCleanable(mCleaner);
        }
    }

    public boolean send(String text) {
        return mWebSocket.send(text);
    }

    public boolean send(ByteString bytes) {
        return mWebSocket.send(bytes);
    }

    public boolean close(int code, String reason) {
        finish();
        return mWebSocket.close(code, reason == null ? "" : reason);
    }

    public void cancel() {
        finish();
        mWebSocket.cancel();
    }

    public long queueSize() {
        return mWebSocket.queueSize();
    }

    @Nullable
    public Request request() {
        return mWebSocket.request();
    }
}
