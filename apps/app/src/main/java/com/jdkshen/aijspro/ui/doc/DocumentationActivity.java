package com.jdkshen.aijspro.ui.doc;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.jdkshen.aijspro.ui.BaseActivity;

/**
 * Bundled API reference and MCP guides, with offline search and in-page navigation.
 *
 * <p>External HTTP(S) links open in the browser. The local API search has no JavaScript bridge.
 */
public class DocumentationActivity extends BaseActivity {

    public static final String EXTRA_URL = "url";
    private WebView webView;

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
        if (TextUtils.isEmpty(url)) url = DocumentationUrlPolicy.HOME;
        if (!TextUtils.isEmpty(url)) {
            if (DocumentationUrlPolicy.isLocalDocument(url)) {
                showLocalDocument(url);
                return;
            }
            try {
                if (!DocumentationUrlPolicy.isExternalDocument(url)) { finish(); return; }
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Throwable error) {
                android.util.Log.w("MiuixRetired", "无法打开文档链接：" + url, error);
            }
        }
        finish();
    }

    /** 本地内置文档用 WebView 直接打开，不经过网络/浏览器。 */
    private void showLocalDocument(String url) {
        if (webView == null) {
            webView = new WebView(this);
            WebSettings settings = webView.getSettings();
            settings.setAllowFileAccess(true);
            settings.setAllowContentAccess(false);
            settings.setBlockNetworkLoads(true);
            webView.setWebViewClient(new WebViewClient() {
                @Override public void onPageStarted(WebView view, String target, android.graphics.Bitmap icon) {
                    view.getSettings().setJavaScriptEnabled(DocumentationUrlPolicy.allowsSearchScript(target));
                }
                @Override public boolean shouldOverrideUrlLoading(WebView view, String target) {
                    if (DocumentationUrlPolicy.isLocalDocument(target)) showLocalDocument(target);
                    else if (DocumentationUrlPolicy.isExternalDocument(target)) {
                        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(target))); }
                        catch (Throwable error) { android.util.Log.w("Documentation", "无法打开文档链接", error); }
                    }
                    return true;
                }
            });
            webView.setBackgroundColor(0xFF11151D);
            setContentView(webView);
        }
        webView.getSettings().setJavaScriptEnabled(DocumentationUrlPolicy.allowsSearchScript(url));
        webView.loadUrl(url);
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            android.webkit.WebBackForwardList history = webView.copyBackForwardList();
            int previous = history.getCurrentIndex() - 1;
            if (previous >= 0) webView.getSettings().setJavaScriptEnabled(
                    DocumentationUrlPolicy.allowsSearchScript(history.getItemAtIndex(previous).getUrl()));
            webView.goBack();
        }
        else super.onBackPressed();
    }
    @Override protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            if (webView.getParent() instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) webView.getParent()).removeView(webView);
            }
            webView.removeAllViews(); webView.destroy(); webView = null;
        }
        super.onDestroy();
    }
}
