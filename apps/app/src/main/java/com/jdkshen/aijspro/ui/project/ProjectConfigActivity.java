package com.jdkshen.aijspro.ui.project;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.jdkshen.aijspro.ui.BaseActivity;
import com.jdkshen.aijspro.ui.FlutterGlassEntry;

/**
 * 项目配置页入口。
 *
 * <p>task-177「去掉 Miuix，统一 Flutter 界面」：项目配置/打包已统一到 Flutter 玻璃
 * {@code ProjectPage}。带 {@link #EXTRA_DIRECTORY}（已有项目目录，原生编辑器菜单传入）时
 * 直接请求 Flutter 打开该项目的打包/配置页；「新建项目」只拿到父目录、还没有 project.json，
 * 交给 Flutter 文件页的新建项目流程（先打开 Flutter 界面并提示），不再转发 Miuix 页面。
 */
public class ProjectConfigActivity extends BaseActivity {

    public static final String EXTRA_PARENT_DIRECTORY = "parent_directory";

    public static final String EXTRA_NEW_PROJECT = "new_project";

    public static final String EXTRA_DIRECTORY = "directory";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent from = getIntent();
        String directory = from == null ? null : from.getStringExtra(EXTRA_DIRECTORY);
        if (directory != null && !directory.trim().isEmpty()) {
            FlutterGlassEntry.requestPackaging(this, directory);
        } else {
            Toast.makeText(this, "请在 Flutter 文件页使用「新建项目」", Toast.LENGTH_SHORT).show();
            FlutterGlassEntry.open(this);
        }
        finish();
    }
}
