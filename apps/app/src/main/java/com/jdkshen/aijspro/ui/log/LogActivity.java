package com.jdkshen.aijspro.ui.log;

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;

import com.jdkshen.aijspro.ui.BaseActivity;

/**
 * 日志页入口：界面已统一到 Miuix（{@link MiuixLogActivity}），本类只做转发。
 *
 * <p>脚本控制台、编辑器菜单等仍按 {@code LogActivity} 启动，因此保留这个壳。
 * 旧 XML 实现（{@code activity_log} + ConsoleView + 主题调色板）已随 UI 统一删除。
 */
public class LogActivity extends BaseActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startActivity(new Intent(this, MiuixLogActivity.class));
        finish();
    }
}
