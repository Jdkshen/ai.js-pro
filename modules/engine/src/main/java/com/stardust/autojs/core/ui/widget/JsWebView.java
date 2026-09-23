package com.stardust.autojs.core.ui.widget;

import android.content.Context;
import android.net.http.SslError;
import android.os.Build;
import androidx.annotation.RequiresApi;
import android.util.AttributeSet;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * Created by Stardust on 2017/11/29.
 */

public class JsWebView extends WebView {

    /**
     * 是否忽略 SSL 证书错误（自签证书等）。默认关闭；由脚本在 loadUrl 之前通过
     * {@link #setIgnoreSslErrors(boolean)} 显式打开，只影响这一个 WebView。
     */
    private boolean mIgnoreSslErrors = false;

    public JsWebView(Context context) {
        super(context);
        init();
    }

    public JsWebView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public JsWebView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public JsWebView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    private void init() {
        WebSettings settings = getSettings();
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(true);
        settings.setLoadWithOverviewMode(true);
        settings.setJavaScriptEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setDomStorageEnabled(true);
        settings.setDisplayZoomControls(false);
        setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                if (mIgnoreSslErrors) {
                    handler.proceed();
                } else {
                    super.onReceivedSslError(view, handler, error);
                }
            }
        });
        setWebChromeClient(new WebChromeClient());
    }

    /**
     * 忽略本 WebView 的 SSL 证书错误（自签证书、内网 HTTPS 等场景）。
     *
     * <p>默认关闭，行为与标准 WebView 一致；脚本显式打开后，页面及其子资源的证书
     * 错误都会走 {@code handler.proceed()}。建议在 {@code loadUrl} 之前调用。</p>
     *
     * <pre>{@code
     * ui.web.setIgnoreSslErrors(true);
     * ui.web.loadUrl("https://self-signed.example");
     * }</pre>
     */
    public void setIgnoreSslErrors(boolean ignoreSslErrors) {
        mIgnoreSslErrors = ignoreSslErrors;
    }

    /** 见 {@link #setIgnoreSslErrors(boolean)}。 */
    public boolean isIgnoreSslErrors() {
        return mIgnoreSslErrors;
    }

}
