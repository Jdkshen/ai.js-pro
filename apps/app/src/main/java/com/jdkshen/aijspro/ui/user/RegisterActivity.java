package com.jdkshen.aijspro.ui.user;

import android.os.Bundle;

import com.jdkshen.aijspro.ui.BaseActivity;

/**
 * 注册页入口（历史壳）。
 *
 * <p>task-177：Flutter 玻璃界面没有注册页（功能对等审计里标注为缺口），
 * Miuix 的 {@code MiuixRegisterActivity} 已从构建排除。保留壳仅为兼容旧 Intent。
 */
public class RegisterActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        finish();
    }
}
