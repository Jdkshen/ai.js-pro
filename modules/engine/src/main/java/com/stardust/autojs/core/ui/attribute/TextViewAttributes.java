package com.stardust.autojs.core.ui.attribute;

import android.view.View;
import android.widget.TextView;

import com.stardust.autojs.core.ui.inflater.ResourceParser;

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
    }

    @Override
    public TextView getView() {
        return (TextView) super.getView();
    }
}
