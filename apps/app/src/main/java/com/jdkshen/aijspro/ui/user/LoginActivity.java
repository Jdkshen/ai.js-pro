package com.jdkshen.aijspro.ui.user;

import android.os.Bundle;

import com.jdkshen.aijspro.ui.BaseActivity;

/**
 * 登录页入口（历史壳）。
 *
 * <p>task-177：Flutter 玻璃界面没有登录/社区入口（功能对等审计里标注为缺口），
 * Miuix 的 {@code MiuixLoginActivity} 已从构建排除。保留壳仅为兼容旧 Intent。
 */
public class LoginActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        finish();
    }
}
