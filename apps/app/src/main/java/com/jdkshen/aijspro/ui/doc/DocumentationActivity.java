package com.jdkshen.aijspro.ui.doc;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;

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
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Throwable error) {
                android.util.Log.w("MiuixRetired", "无法打开文档链接：" + url, error);
            }
        }
        finish();
    }
}
