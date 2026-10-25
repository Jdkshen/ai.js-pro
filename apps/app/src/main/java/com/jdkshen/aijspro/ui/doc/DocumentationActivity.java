package com.jdkshen.aijspro.ui.doc;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.webkit.WebSettings;
import android.webkit.WebView;

import com.jdkshen.aijspro.ui.BaseActivity;

/**
 * 文档页入口（历史壳）。
 *
 * <p>task-177：task-177 去 Miuix 后不再有原生的 {@code MiuixDocumentationActivity}。
 * 带 url 的调用改为系统浏览器打开；其余调用直接结束（Flutter 侧「使用手册」页由
 * {@code GuidePage} 自己承载）。
 */
public class DocumentationActivity extends BaseActivity {

    public static final String EXTRA_URL = "url";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        forward();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        forward();
    }

    private void forward() {
        Intent from = getIntent();
        String url = from == null ? null : from.getStringExtra(EXTRA_URL);
        if (!TextUtils.isEmpty(url)) {
            if (url.startsWith("file:///android_asset/")) {
                showLocalDocument(url);
                return;
            }
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Throwable error) {
                android.util.Log.w("MiuixRetired", "无法打开文档链接：" + url, error);
            }
        }
        finish();
    }

    /** 本地内置文档用 WebView 直接打开，不经过网络/浏览器。 */
    private void showLocalDocument(String url) {
        WebView webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(false);
        settings.setAllowFileAccess(true);
        webView.setBackgroundColor(0xFF101418);
        webView.loadUrl(url);
        setContentView(webView);
    }
}
