package com.jdkshen.aijspro.ui.settings;

import android.content.Intent;
import android.os.Bundle;

import com.jdkshen.aijspro.ui.BaseActivity;

/**
 * 设置页入口（历史壳）。
 *
 * <p>task-177「去掉 Miuix，统一 Flutter 界面」：设置页已统一到 Flutter 玻璃设置页
 * （{@code GeneralSettingsPage}），Miuix 的 {@code MiuixSettingsActivity} 已从构建排除。
 * 保留这个壳只是为了外部 Intent / 旧代码不编译报错；打开时不再跳转任何原生设置页。
 */
public class SettingsActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        finish();
    }
}
