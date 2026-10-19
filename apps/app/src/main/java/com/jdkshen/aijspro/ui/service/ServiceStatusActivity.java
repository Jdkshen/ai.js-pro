package com.jdkshen.aijspro.ui.service;

import android.os.Bundle;

import com.jdkshen.aijspro.ui.BaseActivity;

/**
 * 核心服务页入口（历史壳）。
 *
 * <p>task-177：服务状态/授权开关已统一到 Flutter 玻璃抽屉（{@code ServiceDrawer}），
 * Miuix 的 {@code MiuixServiceActivity} 已从构建排除。保留壳仅为兼容旧 Intent。
 */
public class ServiceStatusActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        finish();
    }
}
