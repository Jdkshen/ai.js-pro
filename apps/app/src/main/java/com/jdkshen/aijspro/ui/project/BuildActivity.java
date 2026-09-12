package com.jdkshen.aijspro.ui.project;

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;

import com.jdkshen.aijspro.ui.BaseActivity;

/**
 * 打包页入口：界面已统一到 Miuix（{@link MiuixBuildActivity}），本类只做转发。
 *
 * <p>外部（编辑器菜单、快捷方式、示例页）用 {@link #EXTRA_SOURCE} 传脚本路径，
 * 这里原样转交给 Miuix 页面，因此常量与键名都不能改。
 * 旧 XML 实现（{@code activity_build} 与其中的 ApkBuilder 调用）已随 UI 统一删除。
 */
public class BuildActivity extends BaseActivity {

    /** 打包源文件路径（键名带类名，历史上就如此，别改）。 */
    public static final String EXTRA_SOURCE = BuildActivity.class.getName() + ".extra_source_file";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent target = new Intent(this, MiuixBuildActivity.class);
        Intent from = getIntent();
        if (from != null && from.hasExtra(EXTRA_SOURCE)) {
            target.putExtra(EXTRA_SOURCE, from.getStringExtra(EXTRA_SOURCE));
        }
        startActivity(target);
        finish();
    }
}
