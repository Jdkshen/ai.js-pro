package com.jdkshen.aijspro.ui.user;

import android.content.Intent;
import android.os.Bundle;

import com.jdkshen.aijspro.ui.BaseActivity;

/**
 * 登录页入口：界面已统一到 Miuix（{@link MiuixLoginActivity}），本类只做转发。
 *
 * <p>旧 XML 实现（{@code activity_login} + UserService 回调）已随 UI 统一删除；
 * 登录逻辑在 Miuix 页面里复用同一套 UserService/NodeBB。
 */
public class LoginActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startActivity(new Intent(this, MiuixLoginActivity.class));
        finish();
    }
}
