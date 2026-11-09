package com.stardust.autojs.core.ui.attribute;

import android.view.View;
import android.widget.LinearLayout;

import com.stardust.autojs.core.ui.inflater.ResourceParser;
import com.stardust.autojs.core.ui.inflater.util.ValueMapper;

/**
 * LinearLayout 的属性表（对齐 Pro 的 {@code LinearLayoutAttributes}；按行为/接口重写，不抄反编译代码）。
 *
 * <p><b>为什么需要</b>：这些属性在**XML 侧一直可用**（{@code LinearLayoutInflater} 的 switch；而且
 * {@code BaseViewInflater.setAttr} 会先查本表），但**脚本侧**的 {@code view.attr(name[, value])}
 * （Rhino 走 {@code ViewPrototype.attr}，QuickJS 走同一张表）只能设置**本表注册过**的名字——没注册的名字
 * 会**静默无效**：attr('orientation') 返回 undefined、attr('orientation','vertical') 什么都不做。
 *
 * <p><b>同源</b>：取值与 {@code LinearLayoutInflater} 逐字对齐（同一套 ValueMapper 语义、同一个 Drawables 解析），
 * 所以 XML 路径行为不变（本表在 inflater 之前被查，命中后语义一致）。名字也照 inflater 的 switch 补全，
 * 避免「XML 能写、脚本不能写」的第二个口子。
 */
public class LinearLayoutAttributes extends ViewAttributes {

    /** 与 {@code LinearLayoutInflater.ORIENTATIONS} 同一套映射（脚本/XML 两个入口必须同源）。 */
    static final ValueMapper<Integer> ORIENTATIONS = new ValueMapper<Integer>("orientation")
            .map("vertical", LinearLayout.VERTICAL)
            .map("horizontal", LinearLayout.HORIZONTAL);

    /** 与 {@code LinearLayoutInflater.SHOW_DIVIDERS} 同一套映射；{@code split} 支持 middle|end 组合。 */
    static final ValueMapper<Integer> SHOW_DIVIDERS = new ValueMapper<Integer>("showDividers")
            .map("beginning", LinearLayout.SHOW_DIVIDER_BEGINNING)
            .map("middle", LinearLayout.SHOW_DIVIDER_MIDDLE)
            .map("end", LinearLayout.SHOW_DIVIDER_END)
            .map("none", LinearLayout.SHOW_DIVIDER_NONE);

    public LinearLayoutAttributes(ResourceParser resourceParser, View view) {
        super(resourceParser, view);
    }

    @Override
    public LinearLayout getView() {
        return (LinearLayout) super.getView();
    }

    @Override
    protected void onRegisterAttrs() {
        super.onRegisterAttrs();
        // 名字与 LinearLayoutInflater 的 switch 逐字对齐（7 个）：
        // orientation / showDividers 需要字符串映射；divider 需要 Drawables 解析；其余是框架 setter 的直接包装。
        registerAttr("orientation", ORIENTATIONS::get, getView()::setOrientation);
        registerAttr("showDividers", SHOW_DIVIDERS::split, getView()::setShowDividers);
        registerBooleanAttr("measureWithLargestChild", getView()::setMeasureWithLargestChildEnabled);
        registerAttr("weightSum", Float::valueOf, getView()::setWeightSum);
        registerBooleanAttr("baselineAligned", getView()::setBaselineAligned);
        registerAttr("baselineAlignedChildIndex", Integer::valueOf, getView()::setBaselineAlignedChildIndex);
        registerDrawableAttr("divider", getView()::setDividerDrawable);
    }
}