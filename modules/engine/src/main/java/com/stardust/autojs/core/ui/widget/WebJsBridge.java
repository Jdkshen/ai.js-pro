package com.stardust.autojs.core.ui.widget;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.stardust.autojs.runtime.ScriptRuntime;
import com.stardust.autojs.runtime.exception.ScriptInterruptedException;
import com.stardust.concurrent.VolatileDispose;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 脚本侧 {@code $ui.web.jsBridge}（Pro 兼容层）：WebView 页面 {@code $autojs} 与脚本之间的事件/请求桥。
 *
 * <p>页面 SDK（{@code autojs://sdk/v1.js}，由 {@link JsWebView} 拦截并注入）提供
 * {@code $autojs.invoke(channel, ...args)}（请求-响应）、{@code $autojs.send(event, ...args)}（单向事件）、
 * {@code $autojs.on(event, fn)}。脚本侧：
 * <ul>
 *   <li>{@code jsBridge.handle(channel, (event, ...args) => result)}：处理页面 invoke；
 *       channel 为 {@code ''} 是兜底处理器；返回 Promise 暂不支持（同步返回值即可，Pro 的 fetch 示例就是同步）；</li>
 *   <li>{@code jsBridge.on(event, (event, ...args) => ...)}：接收页面 send 的自定义事件；</li>
 *   <li>{@code jsBridge.send(event, ...args)}：脚本 -> 页面单向事件（页面用 {@code $autojs.on} 接收）。</li>
 * </ul>
 *
 * <p>线程模型：页面回调（{@code @JavascriptInterface}）在 WebView 的 JavaBridge 线程；脚本回调统一
 * 投递到注册监听时的脚本线程 Handler 执行；请求-响应用 {@link VolatileDispose} 等待同步返回值
 * （5s 超时，超时/异常返回错误给页面，不阻塞主线程无限等待）。
 */
public class WebJsBridge {

    private static final String TAG = "WebJsBridge";
    /** 与页面 SDK 约定的内部协议事件名（与 Auto.js Pro 的 jsBridge 一致）。 */
    static final String EVENT_REQUEST = "$autojs:internal:request";
    static final String EVENT_RESPONSE_PREFIX = "$autojs:internal:response:";
    private static final long HANDLER_TIMEOUT_MS = 5000L;

    private final JsWebView mWebView;
    private final ScriptRuntime mRuntime;
    private final Map<String, Object> mRequestHandlers = new ConcurrentHashMap<>();
    private final JsWebViewEvents mEventEmitter;
    private final AtomicInteger mNextRequestId = new AtomicInteger(1);
    private volatile Handler mScriptHandler;

    WebJsBridge(JsWebView webView, ScriptRuntime runtime) {
        mWebView = webView;
        mRuntime = runtime;
        mEventEmitter = new JsWebViewEvents(runtime);
    }

    // ---- 脚本侧 API（与 Pro 的 JsBridge 方法对齐） -----------------------------

    public WebJsBridge handle(String channel, Object handler) {
        if (handler != null) {
            captureScriptHandler();
            mRequestHandlers.put(channel == null ? "" : channel, handler);
        }
        return this;
    }

    public WebJsBridge on(String event, Object listener) {
        captureScriptHandler();
        mEventEmitter.on(event, listener);
        return this;
    }

    public WebJsBridge once(String event, Object listener) {
        captureScriptHandler();
        mEventEmitter.once(event, listener);
        return this;
    }

    public WebJsBridge off(String event, Object listener) {
        mEventEmitter.off(event, listener);
        return this;
    }

    public WebJsBridge removeListener(String event, Object listener) {
        return off(event, listener);
    }

    /** 脚本 -> 页面单向事件：页面 {@code $autojs.on(event, fn)} 会收到。 */
    public void send(String event, Object... args) {
        if (event == null) {
            return;
        }
        mWebView.dispatchPageEvent(event, jsonArrayOf(args));
    }

    /**
     * 脚本 -> 页面请求-响应（页面侧 { $autojs.handle } 由页面脚本自行注册；本项目示例未用）。
     * 页面 SDK 暂未暴露页面 handler 注册接口时，本方法只发送事件、不等待响应（返回 null）。
     */
    public Object invoke(String channel, Object... args) {
        try {
            JSONObject request = new JSONObject();
            request.put("id", mNextRequestId.getAndIncrement());
            request.put("channel", channel == null ? "" : channel);
            JSONArray jsonArgs = new JSONArray();
            if (args != null) {
                for (Object arg : args) {
                    jsonArgs.put(WebViewJs.jsToJson(arg) == null ? JSONObject.NULL : WebViewJs.jsToJson(arg));
                }
            }
            request.put("args", jsonArgs);
            send(EVENT_REQUEST, request);
        } catch (Throwable error) {
            Log.w(TAG, "jsBridge.invoke failed", error);
        }
        return null;
    }

    /** 页面内执行 JS（Pro 的 jsBridge.eval 语义）；当前实现为异步注入，返回 null。 */
    public Object eval(String code) {
        if (code != null) {
            mWebView.evaluatePageScript(code);
        }
        return null;
    }

    // ---- 页面 -> 脚本 ---------------------------------------------------------

    private void captureScriptHandler() {
        if (mScriptHandler != null) {
            return;
        }
        synchronized (this) {
            if (mScriptHandler == null) {
                Looper looper = Looper.myLooper();
                if (looper != null) {
                    mScriptHandler = new Handler(looper);
                }
            }
        }
    }

    /** 页面 SDK 的 {@code __autojsBridge.postMessage(json)} 入口（WebView JavaBridge 线程）。 */
    void onPageMessage(String json) {
        if (json == null || json.isEmpty()) {
            return;
        }
        try {
            JSONObject message = new JSONObject(json);
            String type = message.optString("type", "");
            if ("request".equals(type)) {
                dispatchRequest(message);
            } else if ("event".equals(type)) {
                dispatchPageEvent(message.optString("event", ""), message.optJSONArray("args"));
            }
        } catch (Throwable error) {
            Log.w(TAG, "parse page message failed: " + json, error);
        }
    }

    private void dispatchRequest(JSONObject message) {
        final int id = message.optInt("id", -1);
        final String channel = message.optString("channel", "");
        final JSONArray args = message.optJSONArray("args");
        Object handler = mRequestHandlers.get(channel);
        if (handler == null) {
            handler = mRequestHandlers.get("");
        }
        if (handler == null) {
            respondError(id, "no handler for action: " + channel);
            return;
        }
        Object result = callHandler(handler, args);
        if (result instanceof Throwable) {
            respondError(id, String.valueOf(result));
        } else {
            respondResult(id, result);
        }
    }

    /** 同步调用脚本 handler：脚本线程不同则投递并等待（默认超时 5s）。 */
    private Object callHandler(Object handler, JSONArray args) {
        final Object[] callbackArgs = buildHandlerArgs(args);
        Handler scriptHandler = mScriptHandler;
        if (scriptHandler == null || Looper.myLooper() == scriptHandler.getLooper()) {
            return invokeHandler(handler, callbackArgs);
        }
        final VolatileDispose<Object> dispose = new VolatileDispose<>();
        boolean posted = scriptHandler.post(() -> {
            try {
                dispose.setAndNotify(invokeHandler(handler, callbackArgs));
            } catch (Throwable error) {
                dispose.setAndNotify(error);
            }
        });
        if (!posted) {
            return invokeHandler(handler, callbackArgs);
        }
        try {
            return dispose.blockedGetOrThrow(ScriptInterruptedException.class,
                    HANDLER_TIMEOUT_MS, "handler timeout: " + HANDLER_TIMEOUT_MS + "ms");
        } catch (Throwable error) {
            return error;
        }
    }

    private Object invokeHandler(Object handler, Object[] args) {
        if (mRuntime == null) {
            return new IllegalStateException("no script runtime");
        }
        try {
            return mRuntime.bridges.callFunction(handler, null, args);
        } catch (Throwable error) {
            return error;
        }
    }

    private Object[] buildHandlerArgs(JSONArray args) {
        final int length = args == null ? 0 : args.length();
        Object[] converted = new Object[length];
        for (int i = 0; i < length; i++) {
            converted[i] = WebViewJs.jsonToJs(args.opt(i));
        }
        Object[] callbackArgs = new Object[length + 1];
        callbackArgs[0] = WebViewJs.newEvent(EVENT_REQUEST, converted);
        System.arraycopy(converted, 0, callbackArgs, 1, length);
        return callbackArgs;
    }

    private void dispatchPageEvent(String event, JSONArray args) {
        if (event == null || event.isEmpty()) {
            return;
        }
        final int length = args == null ? 0 : args.length();
        Object[] converted = new Object[length];
        for (int i = 0; i < length; i++) {
            converted[i] = WebViewJs.jsonToJs(args.opt(i));
        }
        // jsBridge.on(...) 的监听通过同一个 events emitter 派发；event 参数由 emit 构造。
        mEventEmitter.emit(event, converted);
    }

    private void respondResult(int id, Object result) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("result", WebViewJs.jsToJson(result) == null ? JSONObject.NULL : WebViewJs.jsToJson(result));
            payload.put("error", JSONObject.NULL);
            respond(id, payload);
        } catch (Throwable error) {
            respondError(id, String.valueOf(error));
        }
    }

    private void respondError(int id, String message) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("result", JSONObject.NULL);
            payload.put("error", message == null ? "" : message);
            respond(id, payload);
        } catch (Throwable error) {
            Log.w(TAG, "respondError failed", error);
        }
    }

    private void respond(int id, JSONObject payload) {
        if (id < 0) {
            return;
        }
        JSONArray array = new JSONArray();
        array.put(payload);
        mWebView.dispatchPageEvent(EVENT_RESPONSE_PREFIX + id, array.toString());
    }

    private String jsonArrayOf(Object[] args) {
        JSONArray array = new JSONArray();
        if (args != null) {
            for (Object arg : args) {
                array.put(WebViewJs.jsToJson(arg) == null ? JSONObject.NULL : WebViewJs.jsToJson(arg));
            }
        }
        return array.toString();
    }
}
