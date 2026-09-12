package com.stardust.autojs.core.web;

import android.content.Context;
import android.webkit.WebView;

/**
 * QuickJS 版 {@link InjectableWebView}：带 `inject` 能力的 WebView，
 * 页面里可以用 `rhino.call(...)` / `rhino.eval(...)` 回调脚本（回调在脚本引擎线程执行）。
 */
public class QuickJsInjectableWebView extends WebView {

    private final QuickJsInjectableWebClient mClient;

    public QuickJsInjectableWebView(Context context, QuickJsWebScriptHost host) {
        super(context);
        mClient = new QuickJsInjectableWebClient(host);
        setWebViewClient(mClient);
        // 页面桥必须在第一次 loadUrl/loadData 之前注册，否则页面里看不到 `rhino`
        mClient.attach(this);
    }

    public void inject(String script, long callbackId) {
        mClient.inject(script, callbackId);
    }
}
