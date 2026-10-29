package com.stardust.autojs.core.ui.attribute;

import android.view.View;
import android.widget.TextView;

import com.stardust.autojs.core.ui.inflater.ResourceParser;
import com.stardust.autojs.core.ui.inflater.util.Colors;

import java.util.Locale;

public class TextViewAttributes extends ViewAttributes {

    public TextViewAttributes(ResourceParser resourceParser, View view) {
        super(resourceParser, view);
    }

    @Override
    protected void onRegisterAttrs() {
        super.onRegisterAttrs();
        // Read the live widget value, including XML defaults and changes made with setText().
        registerAttr("text", () -> getView().getText().toString(),
                value -> getView().setText(parseString(value)));
        registerAttr("hint", () -> getView().getHint() == null ? "" : getView().getHint().toString(),
                value -> getView().setHint(parseString(value)));
        // XML already accepts both names. Keep dynamic updates on the same parser,
        // including resource colours, and read live values after setTextColor().
        registerAttr("textColor", () -> colorString(getView().getCurrentTextColor()),
                value -> getView().setTextColor(Colors.parse(getView().getContext(), value)));
        registerAttrs(new String[]{"color"}, get("textColor"));
        registerAttr("textColorHint", () -> colorString(getView().getCurrentHintTextColor()),
                value -> getView().setHintTextColor(Colors.parse(getView(), value)));
    }

    @Override
    public TextView getView() {
        return (TextView) super.getView();
    }

    private static String colorString(int color) {
        return String.format(Locale.ROOT, "#%08X", color);
    }
}
