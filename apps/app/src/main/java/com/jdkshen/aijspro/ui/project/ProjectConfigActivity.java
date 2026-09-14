package com.jdkshen.aijspro.ui.project;

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;

import com.jdkshen.aijspro.ui.BaseActivity;

/**
 * 新建/编辑项目页入口：界面已统一到 Miuix（{@link MiuixProjectConfigActivity}），本类只做转发。
 *
 * <p>调用方（文件列表 FAB「项目」、编辑器菜单、项目工具条）用 EXTRA_PARENT_DIRECTORY /
 * EXTRA_NEW_PROJECT / EXTRA_DIRECTORY 传参，这里原样转交给 Miuix 页面，因此常量与键名都不能改。
 * 旧 XML 实现（{@code activity_project_config}）已随 UI 统一删除。
 */
public class ProjectConfigActivity extends BaseActivity {

    public static final String EXTRA_PARENT_DIRECTORY = "parent_directory";

    public static final String EXTRA_NEW_PROJECT = "new_project";

    public static final String EXTRA_DIRECTORY = "directory";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent target = new Intent(this, MiuixProjectConfigActivity.class);
        Intent from = getIntent();
        if (from != null) {
            if (from.hasExtra(EXTRA_PARENT_DIRECTORY)) {
                target.putExtra(EXTRA_PARENT_DIRECTORY, from.getStringExtra(EXTRA_PARENT_DIRECTORY));
            }
            if (from.hasExtra(EXTRA_DIRECTORY)) {
                target.putExtra(EXTRA_DIRECTORY, from.getStringExtra(EXTRA_DIRECTORY));
            }
            if (from.hasExtra(EXTRA_NEW_PROJECT)) {
                target.putExtra(EXTRA_NEW_PROJECT, from.getBooleanExtra(EXTRA_NEW_PROJECT, false));
            }
        }
        startActivity(target);
        finish();
    }
}


