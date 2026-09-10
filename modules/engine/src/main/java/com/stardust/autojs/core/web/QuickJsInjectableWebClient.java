package com.stardust.autojs.core.web;

import android.annotation.SuppressLint;
import android.util.Log;
import android.util.Pair;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

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
    public void onPageFinished(WebView view, String url) {
        mWebView = view;
        setUpWebView(view);
        while (!mPendingInjections.isEmpty()) {
            Pair<String, Long> pair = mPendingInjections.poll();
            inject(view, pair.first, pair.second);
        }
        super.onPageFinished(view, url);
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

        @JavascriptInterface
        public void call(String name, String argsJson) {
            mHost.postScriptCall(name, argsJson == null ? "[]" : argsJson);
        }

        @JavascriptInterface
        public void eval(String script) {
            mHost.postScriptEval(script);
        }
    }
}
