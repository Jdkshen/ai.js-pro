package com.jdkshen.aijspro.ui.project;

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;

import com.jdkshen.aijspro.ui.BaseActivity;
import com.jdkshen.aijspro.ui.FlutterGlassEntry;

/**
 * 打包页入口。
 *
 * <p>task-177「去掉 Miuix，统一 Flutter 界面」：打包界面统一到 Flutter 玻璃
 * {@code ProjectPage}。本类只把源文件路径写进 {@code native_packaging_request} 并拉起
 * Flutter 界面（{@link FlutterGlassEntry#requestPackaging}），不再转发到已退役的
 * Miuix 打包页。{@link #EXTRA_SOURCE} 的键名/取值来自调用方（原生 Ace 编辑器、外部
 * Intent、快捷方式），不能改。
 */
public class BuildActivity extends BaseActivity {

    /** 打包源文件路径（键名带类名，历史上就如此，别改）。 */
    public static final String EXTRA_SOURCE = BuildActivity.class.getName() + ".extra_source_file";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent from = getIntent();
        String source = from == null ? null : from.getStringExtra(EXTRA_SOURCE);
        FlutterGlassEntry.requestPackaging(this, source);
        finish();
    }
}
