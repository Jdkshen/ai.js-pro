package com.jdkshen.aijspro.ui.service;

import android.content.Intent;
import android.os.Bundle;

import com.jdkshen.aijspro.ui.BaseActivity;

/**
 * 核心服务页入口：界面已统一到 Miuix（{@link MiuixServiceActivity}），本类只做转发。
 *
 * <p>旧 XML 实现（{@code activity_service_status} 里的无障碍/悬浮窗/前台服务/通知/电池优化开关）
 * 已随 UI 统一删除；开关逻辑在 Miuix 页面里重新实现。
 */
public class ServiceStatusActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startActivity(new Intent(this, MiuixServiceActivity.class));
        finish();
    }
}
