package com.jdkshen.aijspro.ui.doc;

import android.os.Bundle;
import android.webkit.WebView;

import com.jdkshen.aijspro.BuildConfig;
import com.jdkshen.aijspro.Pref;
import com.jdkshen.aijspro.R;
import com.jdkshen.aijspro.ui.BaseActivity;
import com.jdkshen.aijspro.ui.widget.EWebView;

/**
 * Created by Stardust on 2017/10/24.
 */
public class DocumentationActivity extends BaseActivity {

    public static final String EXTRA_URL = "url";

    EWebView mEWebView;

    WebView mWebView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (BuildConfig.MIUIX_PILOT) {
            forwardToMiuix();
            finish();
            return;
        }
        setContentView(R.layout.activity_documentation);
        mEWebView = findViewById(R.id.eweb_view);
        setUpViews();
    }

    /**
     * MIUIX 皮肤下文档页由 {@link MiuixDocumentationActivity} 呈现，这里把 url 转交过去。
     * 带 SINGLE_TOP：目标页已在前台时走它的 onNewIntent，而不是再叠一层。
     */
    private void forwardToMiuix() {
        android.content.Intent miuixIntent = new android.content.Intent().setClassName(this,
                "com.jdkshen.aijspro.ui.doc.MiuixDocumentationActivity");
        miuixIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
        android.content.Intent from = getIntent();
        if (from != null && from.hasExtra(EXTRA_URL)) {
            miuixIntent.putExtra(EXTRA_URL, from.getStringExtra(EXTRA_URL));
        }
        startActivity(miuixIntent);
    }

    void setUpViews() {
        setToolbarAsBack(getString(R.string.text_tutorial));
        mWebView = mEWebView.getWebView();
        String url = getIntent().getStringExtra(EXTRA_URL);
        if (url == null) {
            url = Pref.getDocumentationUrl() + "index.html";
        }
        mWebView.loadUrl(url);
    }

    @Override
    public void onBackPressed() {
        if (mWebView.canGoBack()) {
            mWebView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    /**
     * 已经在文档页时再次 startActivity（例如「使用手册」传入新的 url），
     * 系统只会投递 onNewIntent，不重建 Activity；这里把新 url 载进去（MIUIX 下转交）。
     */
    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (BuildConfig.MIUIX_PILOT) {
            forwardToMiuix();
            if (!isFinishing())
                finish();
            return;
        }
        if (mWebView == null)
            return;
        String url = intent.getStringExtra(EXTRA_URL);
        if (url != null) {
            mWebView.loadUrl(url);
        }
    }
}
