package com.stardust.autojs.core.web;

import android.annotation.SuppressLint;
import android.util.Log;
import android.util.Pair;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedList;
import java.util.Queue;

/**
 * QuickJS 版 {@link InjectableWebClient}：与 Rhino 版保持相同的对外行为
 * （`inject(script[, callback])`、页面里的 `rhino` 桥），但页面回调不直接进 QuickJS 上下文，
 * 而是交给 {@link QuickJsWebScriptHost} 排队，由脚本引擎线程执行。
 */
public class QuickJsInjectableWebClient extends WebViewClient {

    private static final String TAG = "QuickJsWebClient";

    private final QuickJsWebScriptHost mHost;
    private final Queue<Pair<String, Long>> mPendingInjections = new LinkedList<>();
    private final ValueCallback<String> mDefaultCallback = value -> Log.i(TAG, "inject result: " + value);
    private WebView mWebView;

    public QuickJsInjectableWebClient(QuickJsWebScriptHost host) {
        mHost = host;
    }

    @Override
    public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
        setUpWebView(view);
        super.onPageStarted(view, url, favicon);
    }

    @Override
    public void onPageFinished(WebView view, String url) {
        mWebView = view;
        setUpWebView(view);
        while (!mPendingInjections.isEmpty()) {
            Pair<String, Long> pair = mPendingInjections.poll();
            inject(view, pair.first, pair.second);
        }
        super.onPageFinished(view, url);
    }

    /**
     * 与 Rhino 版 {@link InjectableWebClient#attach(WebView)} 一致：
     * 把页面桥（页面里的 `rhino` 对象）提前注册到 WebView，必须在 loadUrl / loadData 之前调用。
     */
    public void attach(WebView view) {
        setUpWebView(view);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setUpWebView(WebView view) {
        view.addJavascriptInterface(new PageBridge(), "rhino");
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
    }

    /** `inject(script[, callback])`：页面还没加载完时先排队，与 Rhino 版一致。 */
    public void inject(String script, long callbackId) {
        if (mWebView != null) {
            inject(mWebView, script, callbackId);
            return;
        }
        mPendingInjections.offer(new Pair<>(script, callbackId));
    }

    private void inject(WebView view, String script, long callbackId) {
        view.evaluateJavascript(script, callbackId == 0
                ? mDefaultCallback
                : value -> mHost.postScriptCallback(callbackId, value));
    }

    /** 页面（或注入脚本）里的 `rhino` 对象。 */
    private final class PageBridge {

        /**
         * 页面里的 `rhino.call('fnName', ...args)`（与 Auto.js 传统写法一致）。
         * <p>
         * 老实现把第二个参数直接当 JSON 数组字符串，于是 `rhino.call('f', '文本')` 会在脚本侧
         * 报 `SyntaxError: unexpected token`；而 Android 的 JS 桥不支持可变参数方法，
         * 所以这里按常见参数个数用固定重载，参数统一转成 JSON 数组再交给引擎线程。
         */
        @JavascriptInterface
        public void call(String name) {
            mHost.postScriptCall(name, "[]");
        }

        /**
         * 参数全部用 String：Android 的 JS 桥只可靠支持基本类型/字符串。
         * 不要加 `call(String, double)` / `call(String, Object)` 这类重载——
         * 前者会把字符串参数抢过去并强转成 0，后者拿到的永远是 null；可变参数（Object...）则完全传不进来。
         */
        @JavascriptInterface
        public void call(String name, String arg0) {
            mHost.postScriptCall(name, jsonArray(arg0));
        }

        @JavascriptInterface
        public void call(String name, String arg0, String arg1) {
            mHost.postScriptCall(name, jsonArray(arg0, arg1));
        }

        @JavascriptInterface
        public void call(String name, String arg0, String arg1, String arg2) {
            mHost.postScriptCall(name, jsonArray(arg0, arg1, arg2));
        }

        @JavascriptInterface
        public void call(String name, String arg0, String arg1, String arg2, String arg3) {
            mHost.postScriptCall(name, jsonArray(arg0, arg1, arg2, arg3));
        }

        @JavascriptInterface
        public void eval(String script) {
            mHost.postScriptEval(script);
        }
    }

    /** 把页面回调参数组装成 JSON 数组（`rhino.call` 任务用）。 */
    private static String jsonArray(Object... values) {
        JSONArray array = new JSONArray();
        if (values != null) {
            for (Object value : values) {
                if (value == null) {
                    array.put(JSONObject.NULL);
                } else if (value instanceof Number || value instanceof Boolean || value instanceof String) {
                    array.put(value);
                } else {
                    array.put(String.valueOf(value));
                }
            }
        }
        return array.toString();
    }
}
