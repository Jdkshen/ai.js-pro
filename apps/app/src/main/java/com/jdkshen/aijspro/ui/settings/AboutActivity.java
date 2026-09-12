package com.jdkshen.aijspro.ui.settings;

import android.content.Intent;
import android.os.Bundle;

import com.jdkshen.aijspro.ui.BaseActivity;

/**
 * 关于页入口：界面已统一到 Miuix（{@link MiuixAboutActivity}），本类只做转发。
 *
 * <p>旧 XML 实现（{@code activity_about}）已随 UI 统一删除。
 */
public class AboutActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startActivity(new Intent(this, MiuixAboutActivity.class));
        finish();
    }
}
