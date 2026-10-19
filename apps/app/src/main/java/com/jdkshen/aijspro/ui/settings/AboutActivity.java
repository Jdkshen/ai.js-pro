package com.jdkshen.aijspro.ui.settings;

import android.os.Bundle;

import com.jdkshen.aijspro.ui.BaseActivity;

/**
 * 关于页入口（历史壳）。
 *
 * <p>task-177：关于/开源许可已改由 Flutter 玻璃设置页「关于」区块直接展示
 * （Flutter AboutDialog / LicensePage），Miuix 的 {@code MiuixAboutActivity} 已从构建排除。
 * 保留壳只为兼容旧 Intent，不再转发生效。
 */
public class AboutActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        finish();
    }
}
