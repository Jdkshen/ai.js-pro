package com.jdkshen.aijspro.ui.doc;

import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.JsPromptResult;

import com.jdkshen.aijspro.ui.BaseActivity;
import com.jdkshen.aijspro.theme.AppThemeRepository;
import com.jdkshen.aijspro.theme.AppThemePalette;
import com.jdkshen.aijspro.theme.HostUiAppearance;

/**
 * Bundled API reference and MCP guides, with offline search and in-page navigation.
 *
 * <p>External links open in the browser. Only the bundled API page may request a code copy.
 */
public class DocumentationActivity extends BaseActivity {

    public static final String EXTRA_URL = "url";
    private WebView webView;
    private final AppThemeRepository.ThemeListener themeListener = this::applyAppearance;

    @Override protected boolean shouldApplyThemeColorToStatusBar() { return false; }

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
        boolean dark = AppThemeRepository.get(this).isDark();
        int background = HostUiAppearance.surface(dark);
        HostUiAppearance.apply(getWindow(), dark);
        if (webView == null) {
            webView = new WebView(this);
            WebSettings settings = webView.getSettings();
            settings.setAllowFileAccess(true);
            settings.setAllowContentAccess(false);
            settings.setBlockNetworkLoads(true);
            settings.setDisplayZoomControls(false);
            webView.setWebChromeClient(new WebChromeClient() {
                @Override public boolean onJsPrompt(WebView view, String page, String message,
                                                     String code, JsPromptResult result) {
                    if (!DocumentationUrlPolicy.isCopyRequest(page, view.getUrl(), message)) {
                        result.cancel(); return true;
                    }
                    if (!DocumentationUrlPolicy.isCopyableCode(code)) {
                        result.confirm("too_large"); return true;
                    }
                    try {
                        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        if (clipboard == null) { result.confirm("error"); return true; }
                        clipboard.setPrimaryClip(ClipData.newPlainText("API 示例代码", code));
                        result.confirm("copied");
                    } catch (RuntimeException error) {
                        android.util.Log.w("Documentation", "无法复制示例代码", error);
                        result.confirm("error");
                    }
                    return true;
                }
            });
            webView.setWebViewClient(new WebViewClient() {
                @Override public void onPageStarted(WebView view, String target, android.graphics.Bitmap icon) {
                    view.getSettings().setJavaScriptEnabled(DocumentationUrlPolicy.allowsSearchScript(target));
                }
                @Override public void onPageFinished(WebView view, String target) {
                    if (DocumentationUrlPolicy.allowsSearchScript(target)) {
                        view.evaluateJavascript("window.AIJS_DOCS_NATIVE=true;", null);
                        applyAppearance(AppThemeRepository.get(DocumentationActivity.this).getPalette());
                    }
                }
                @Override public boolean shouldOverrideUrlLoading(WebView view, String target) {
                    if (DocumentationUrlPolicy.BACK_ACTION.equals(target)
                            && DocumentationUrlPolicy.allowsSearchScript(view.getUrl())) onBackPressed();
                    else if (DocumentationUrlPolicy.isLocalDocument(target)) showLocalDocument(target);
                    else if (DocumentationUrlPolicy.isExternalDocument(target)) {
                        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(target))); }
                        catch (Throwable error) { android.util.Log.w("Documentation", "无法打开文档链接", error); }
                    }
                    return true;
                }
            });
            setContentView(webView);
        }
        webView.setBackgroundColor(background);
        webView.getSettings().setJavaScriptEnabled(DocumentationUrlPolicy.allowsSearchScript(url));
        webView.loadUrl(Uri.parse(url).buildUpon().clearQuery()
                .appendQueryParameter("theme", dark ? "dark" : "light")
                .appendQueryParameter("accent", accentHex(AppThemeRepository.get(this).getPalette())).build().toString());
    }

    private String accentHex(AppThemePalette palette) {
        return String.format(java.util.Locale.ROOT, "#%06x", palette.accent & 0xffffff);
    }

    private void applyAppearance(AppThemePalette palette) {
        HostUiAppearance.apply(getWindow(), palette.isDark);
        if (webView == null) return;
        webView.setBackgroundColor(HostUiAppearance.surface(palette.isDark));
        if (DocumentationUrlPolicy.allowsSearchScript(webView.getUrl())) {
            // Only native boolean/color values enter the script. No page or user text is evaluated.
            webView.evaluateJavascript("(function(){var p={theme:'" + (palette.isDark ? "dark" : "light")
                    + "',accent:'" + accentHex(palette) + "'};window.AIJS_DOCS_APPEARANCE=p;"
                    + "if(window.AIJS_DOCS_SET_APPEARANCE)window.AIJS_DOCS_SET_APPEARANCE(p);})();", null);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        AppThemeRepository repository = AppThemeRepository.get(this);
        repository.addListener(themeListener);
        applyAppearance(repository.getPalette());
    }

    @Override protected void onPause() {
        AppThemeRepository.get(this).removeListener(themeListener);
        super.onPause();
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        AppThemeRepository repository = AppThemeRepository.get(this);
        repository.refreshSystemAppearance();
        applyAppearance(repository.getPalette());
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
        AppThemeRepository.get(this).removeListener(themeListener);
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
