package com.jdkshen.aijspro.ui.doc;

import android.content.Intent;
import android.os.Bundle;

import com.jdkshen.aijspro.ui.BaseActivity;

/**
 * 文档页入口：界面已统一到 Miuix（{@link MiuixDocumentationActivity}），本类只做转发。
 *
 * <p>保留这个壳是因为外部（主界面、使用手册等）仍按 {@code DocumentationActivity} 启动；
 * 转发带 {@code SINGLE_TOP}：目标页已在前台时走它的 {@code onNewIntent} 换页，而不是再叠一层。
 *
 * <p>旧 XML 实现（{@code activity_documentation} + EWebView）已随 UI 统一删除。
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
        Intent target = new Intent(this, MiuixDocumentationActivity.class);
        target.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        Intent from = getIntent();
        if (from != null && from.hasExtra(EXTRA_URL)) {
            target.putExtra(EXTRA_URL, from.getStringExtra(EXTRA_URL));
        }
        startActivity(target);
        finish();
    }
}
