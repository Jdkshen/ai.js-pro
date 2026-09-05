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
            android.content.Intent miuixIntent = new android.content.Intent().setClassName(this,
                    "com.jdkshen.aijspro.ui.doc.MiuixDocumentationActivity");
            if (getIntent() != null && getIntent().hasExtra(EXTRA_URL)) {
                miuixIntent.putExtra(EXTRA_URL, getIntent().getStringExtra(EXTRA_URL));
            }
            startActivity(miuixIntent);
            finish();
            return;
        }
        setContentView(R.layout.activity_documentation);
        mEWebView = findViewById(R.id.eweb_view);
        setUpViews();
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
}
