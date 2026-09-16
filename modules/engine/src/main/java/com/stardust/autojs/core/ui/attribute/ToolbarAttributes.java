package com.stardust.autojs.core.ui.attribute;

import android.view.View;

import androidx.appcompat.widget.Toolbar;

import com.stardust.autojs.core.ui.inflater.ResourceParser;
import com.stardust.autojs.core.ui.inflater.util.Colors;

/**
 * {@code androidx.appcompat.widget.Toolbar} 的属性。
 *
 * <p>以前只有 Auto.js 自己的 {@code <toolbar>}（{@code JsToolbar}）有 setter 表，appcompat 的 Toolbar
 * 走的是空的 {@link ViewAttributes}，于是 {@code res/layout} 里原生布局写的
 * {@code app:title} / {@code app:subtitle} 全被忽略 —— Toolbar 出来是空白的。
 */
public class ToolbarAttributes extends ViewAttributes {

    public ToolbarAttributes(ResourceParser resourceParser, View view) {
        super(resourceParser, view);
    }

    @Override
    protected void onRegisterAttrs() {
        super.onRegisterAttrs();
        registerAttr("title", getView()::setTitle);
        registerAttr("subtitle", getView()::setSubtitle);
        registerAttr("titleTextColor", value -> getView().setTitleTextColor(Colors.parse(getView(), value)));
        registerAttr("subtitleTextColor", value -> getView().setSubtitleTextColor(Colors.parse(getView(), value)));
        registerAttr("navigationContentDescription", getView()::setNavigationContentDescription);
        registerDrawableAttr("navigationIcon", getView()::setNavigationIcon);
        registerDrawableAttr("logo", getView()::setLogo);
    }

    @Override
    public Toolbar getView() {
        return (Toolbar) super.getView();
    }
}
