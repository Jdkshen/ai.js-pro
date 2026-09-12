package com.jdkshen.aijspro.ui.user;

import android.content.Intent;
import android.os.Bundle;

import com.jdkshen.aijspro.ui.BaseActivity;

/**
 * 注册页入口：界面已统一到 Miuix（{@link MiuixRegisterActivity}），本类只做转发。
 *
 * <p>旧 XML 实现（{@code activity_register} + UserService 回调）已随 UI 统一删除。
 */
public class RegisterActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startActivity(new Intent(this, MiuixRegisterActivity.class));
        finish();
    }
}
