package com.stardust.autojs.core.ui.attribute;

import android.graphics.Color;
import android.view.View;

import com.stardust.autojs.core.ui.inflater.ResourceParser;
import com.stardust.autojs.core.ui.inflater.util.Dimensions;

import androidx.cardview.widget.CardView;

public class CardAttributes extends ViewAttributes {

    public CardAttributes(ResourceParser resourceParser, View view) {
        super(resourceParser, view);
    }

    @Override
    protected void onRegisterAttrs() {
        super.onRegisterAttrs();
        registerAttr("cardBackgroundColor", Color::parseColor, getView()::setCardBackgroundColor);
        // 支持 cardCornerRadius="50%"：百分比相对短边，量完布局后才能换算成像素。
        registerAttr("cardCornerRadius", this::setCornerRadius);
        registerPixelAttr("cardElevation", getView()::setCardElevation);
        registerPixelAttr("cardMaxElevation", getView()::setMaxCardElevation);
        registerBooleanAttr("cardPreventCornerOverlap", getView()::setPreventCornerOverlap);
        registerBooleanAttr("cardUseCompatPadding", getView()::setUseCompatPadding);
        registerAttr("contentPadding", this::setContentPadding);
        registerIntPixelAttr("contentPaddingBottom", this::setContentPaddingBottom);
        registerIntPixelAttr("contentPaddingLeft", this::setContentPaddingLeft);
        registerIntPixelAttr("contentPaddingTop", this::setContentPaddingTop);
        registerIntPixelAttr("contentPaddingRight", this::setContentPaddingRight);
    }

    private void setContentPadding(String value) {
        int[] pixels = Dimensions.parseToIntPixelArray(getView(), value);
        getView().setContentPadding(pixels[0], pixels[1], pixels[2], pixels[3]);
    }

    /**
     * cardCornerRadius：支持像素/尺寸值，也支持百分比（相对短边，50% 即内切圆）。
     * 百分比在控件量完尺寸后才能换算，所以注册一次性布局回调补设。
     */
    private void setCornerRadius(String value) {
        if (value != null && value.trim().endsWith("%")) {
            final float percent;
            try {
                percent = Float.parseFloat(value.trim().replace("%", "")) / 100f;
            } catch (NumberFormatException error) {
                return;
            }
            applyCornerRadiusPercent(percent);
            getView().addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                           int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    if (Math.min(v.getWidth(), v.getHeight()) > 0) {
                        v.removeOnLayoutChangeListener(this);
                        applyCornerRadiusPercent(percent);
                    }
                }
            });
            return;
        }
        getView().setRadius(Dimensions.parseToPixel(getView(), value));
    }

    private void applyCornerRadiusPercent(float percent) {
        CardView cardView = getView();
        int side = Math.min(cardView.getWidth(), cardView.getHeight());
        if (side > 0) {
            cardView.setRadius(Math.max(0f, percent * side));
        }
    }


    private void setContentPaddingBottom(int value) {
        CardView cardView = getView();
        cardView.setContentPadding(cardView.getContentPaddingLeft(), cardView.getContentPaddingTop(),
                cardView.getContentPaddingRight(), value);
    }


    private void setContentPaddingLeft(int value) {
        CardView cardView = getView();
        cardView.setContentPadding(value, cardView.getContentPaddingTop(),
                cardView.getContentPaddingRight(), cardView.getContentPaddingBottom());
    }


    private void setContentPaddingTop(int value) {
        CardView cardView = getView();
        cardView.setContentPadding(cardView.getContentPaddingLeft(), value,
                cardView.getContentPaddingRight(), cardView.getContentPaddingBottom());
    }


    private void setContentPaddingRight(int value) {
        CardView cardView = getView();
        cardView.setContentPadding(cardView.getContentPaddingLeft(), cardView.getContentPaddingTop(),
                value, cardView.getContentPaddingBottom());
    }


    @Override
    public CardView getView() {
        return (CardView) super.getView();
    }

}
