package com.jdkshen.aijspro.ui.settings;

import android.content.Intent;
import android.os.Bundle;

import com.jdkshen.aijspro.ui.BaseActivity;

/**
 * 设置页入口：界面已统一到 Miuix（{@link MiuixSettingsActivity}），本类只做转发。
 *
 * <p>旧实现（{@code activity_settings} + {@code preferences.xml} 的 PreferenceFragment、
 * 以及 {@code selectThemeColor} 那套旧主题色选择器）已随 UI 统一删除 ——
 * Miuix 设置页直接读写同一份 SharedPreferences，行为一致。
 */
public class SettingsActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startActivity(new Intent(this, MiuixSettingsActivity.class));
        finish();
    }
}
