package com.stardust.autojs.core.ui.attribute;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;

import com.google.android.material.button.MaterialButton;
import com.stardust.autojs.core.ui.inflater.ResourceParser;
import com.stardust.autojs.core.ui.inflater.util.Colors;
import com.stardust.autojs.core.ui.inflater.util.Dimensions;
import com.stardust.autojs.core.ui.inflater.util.Res;
import com.stardust.autojs.core.ui.inflater.util.ResIds;

/**
 * dev-1017：MaterialButton（以及 ExtendedFAB，继承自 MaterialButton）的属性支持。
 *
 * <p>为什么需要单独一份：M3 控件只有 (C)/(C,AS)/(C,AS,int) 构造器，{@code style="@style/..."}
 * 无法经 framework 应用（见 DynamicLayoutInflater 的构造器候选链）；如果脚本/layout 用
 * {@code style="@style/Widget.Material3.Button.IconButton"} + {@code app:icon="@drawable/x"}，
 * 旧实现下 app:icon 没人处理、style 也被丢弃 → 卡片上的 ❤️/● 图标不显示（dev-1016）。
 *
 * <p>这里做两件事：
 * <ol>
 *   <li>注册 MaterialButton 常用属性：icon / iconTint / iconGravity / iconPadding / iconSize /
 *       cornerRadius / strokeWidth / strokeColor（Drawable 用工程 {@code Drawables.parse}，
 *       支持 {@code @drawable/...}、{@code file://...}、工程资源包）；</li>
 *   <li>对无法 framework 应用的 style 手工解释：先按 style res id 读 TypedArray 的 M3 属性，
 *       读不到再按名字兜底（IconButton → 图标贴边/0 padding/透明背景/去 elevation/主题 iconTint）。</li>
 * </ol>
 * 关键日志统一为 {@code C-ICON:} 前缀，便于 dev-1017 真机 grep。
 */
public class MaterialButtonAttributes extends TextViewAttributes {

    private static final String TAG = "C-ICON";

    public MaterialButtonAttributes(ResourceParser resourceParser, View view) {
        super(resourceParser, view);
    }

    @Override
    public MaterialButton getView() {
        return (MaterialButton) super.getView();
    }

    @Override
    protected void onRegisterAttrs() {
        super.onRegisterAttrs();
        // app:icon —— 图标按钮/卡片 ❤️/● 图标的核心属性
        registerAttr("icon", value -> {
            Drawable drawable = resolveIconDrawable(value);
            Log.i(TAG, "C-ICON: set icon drawable=" + value + " -> "
                    + (drawable == null ? "null(解析失败，检查 res/drawable 是否存在)" : drawable.getClass().getSimpleName())
                    + " view=" + getView().getClass().getSimpleName());
            return drawable;
        }, getView()::setIcon);
        registerAttr("iconTint", value -> {
            ColorStateList list = ColorStateList.valueOf(Colors.parse(getView(), value));
            Log.i(TAG, "C-ICON: iconTint=" + value + " -> 0x" + Integer.toHexString(list.getDefaultColor()));
            return list;
        }, getView()::setIconTint);
        registerAttr("iconGravity", MaterialButtonAttributes::parseIconGravity, getView()::setIconGravity);
        registerIntPixelAttr("iconPadding", getView()::setIconPadding);
        registerIntPixelAttr("iconSize", getView()::setIconSize);
        registerIntPixelAttr("cornerRadius", getView()::setCornerRadius);
        registerIntPixelAttr("strokeWidth", getView()::setStrokeWidth);
        registerAttr("strokeColor", value -> ColorStateList.valueOf(Colors.parse(getView(), value)),
                getView()::setStrokeColor);
        // style 属性：构造阶段已由 DynamicLayoutInflater 尝试（无 4 参构造器时会被丢弃）；
        // 这里对无法 framework 应用的 style 做手工解释。
        registerAttr("style", this::applyStyleManually);
    }

    static int parseIconGravity(String value) {
        if (value == null) {
            return MaterialButton.ICON_GRAVITY_START;
        }
        switch (value.trim()) {
            case "start":
                return MaterialButton.ICON_GRAVITY_START;
            case "textStart":
                return MaterialButton.ICON_GRAVITY_TEXT_START;
            case "end":
                return MaterialButton.ICON_GRAVITY_END;
            case "textEnd":
                return MaterialButton.ICON_GRAVITY_TEXT_END;
            case "top":
                return MaterialButton.ICON_GRAVITY_TOP;
            case "textTop":
                return MaterialButton.ICON_GRAVITY_TEXT_TOP;
            default:
                try {
                    return Integer.parseInt(value.trim());
                } catch (NumberFormatException ignored) {
                    return MaterialButton.ICON_GRAVITY_START;
                }
        }
    }

    /**
     * 手工解释 style（重点：{@code Widget.Material3.Button.IconButton} 这类 M3 图标按钮样式）。
     *
     * <p>顺序：style res id → 读 TypedArray 的 iconSize/iconPadding/iconGravity/iconTint/
     * cornerRadius/strokeWidth/strokeColor；读不到的项再按 style 名兜底（IconButton）。
     */
    private void applyStyleManually(String value) {
        final MaterialButton button = getView();
        final Context context = button.getContext();
        int styleRes = 0;
        try {
            styleRes = Res.parseStyle(context, value);
        } catch (Throwable ignored) {
            // 名字兜底继续
        }
        boolean iconSizeSet = false;
        boolean iconPaddingSet = false;
        boolean iconGravitySet = false;
        boolean iconTintSet = false;
        if (styleRes != 0) {
            int[] attrIds = {
                    attrId(context, "iconSize"),
                    attrId(context, "iconPadding"),
                    attrId(context, "iconGravity"),
                    attrId(context, "iconTint"),
                    attrId(context, "cornerRadius"),
                    attrId(context, "strokeWidth"),
                    attrId(context, "strokeColor")
            };
            TypedArray typedArray = null;
            try {
                typedArray = context.obtainStyledAttributes(styleRes, attrIds);
                if (attrIds[0] != 0 && typedArray.hasValue(0)) {
                    button.setIconSize(typedArray.getDimensionPixelSize(0, 0));
                    iconSizeSet = true;
                }
                if (attrIds[1] != 0 && typedArray.hasValue(1)) {
                    button.setIconPadding(typedArray.getDimensionPixelSize(1, 0));
                    iconPaddingSet = true;
                }
                if (attrIds[2] != 0 && typedArray.hasValue(2)) {
                    button.setIconGravity(typedArray.getInt(2, MaterialButton.ICON_GRAVITY_START));
                    iconGravitySet = true;
                }
                if (attrIds[3] != 0 && typedArray.hasValue(3)) {
                    ColorStateList tint = typedArray.getColorStateList(3);
                    if (tint != null) {
                        button.setIconTint(tint);
                        iconTintSet = true;
                    }
                }
                if (attrIds[4] != 0 && typedArray.hasValue(4)) {
                    button.setCornerRadius(typedArray.getDimensionPixelSize(4, 0));
                }
                if (attrIds[5] != 0 && typedArray.hasValue(5)) {
                    button.setStrokeWidth(typedArray.getDimensionPixelSize(5, 0));
                }
                if (attrIds[6] != 0 && typedArray.hasValue(6)) {
                    ColorStateList stroke = typedArray.getColorStateList(6);
                    if (stroke != null) {
                        button.setStrokeColor(stroke);
                    }
                }
            } catch (Throwable error) {
                Log.w(TAG, "C-ICON: 读 style 资源属性失败（继续名字兜底）：" + value, error);
            } finally {
                if (typedArray != null) {
                    typedArray.recycle();
                }
            }
        }
        boolean iconButtonStyle = value != null
                && value.toLowerCase(java.util.Locale.US).contains("iconbutton");
        if (iconButtonStyle) {
            // dev-1028：Material3 IconButton 语义明确化（M3 规范 40dp 触控区/8dp 内边距/24dp 图标）。
            // 之前只按「没读到时补默认值」，真机仍看不到图标；这次无论 TypedArray 读到与否都强制一套确定值，
            // 并把 iconTint 固定为实色（避免 ColorStateList/主题引用解析成透明白）。
            final int sizePx = dp(context, 40);
            final int paddingPx = dp(context, 8);
            final int iconSizePx = dp(context, 24);
            final int tintColor = resolveSolidIconTint(context);
            button.setIconPadding(0);
            button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
            button.setIconSize(iconSizePx);
            button.setIconTint(ColorStateList.valueOf(tintColor));
            button.setGravity(android.view.Gravity.CENTER);
            button.setPadding(paddingPx, paddingPx, paddingPx, paddingPx);
            button.setMinimumWidth(sizePx);
            button.setMinimumHeight(sizePx);
            android.view.ViewGroup.LayoutParams layoutParams = button.getLayoutParams();
            if (layoutParams != null) {
                layoutParams.width = sizePx;
                layoutParams.height = sizePx;
                button.setLayoutParams(layoutParams);
            }
            // dev-1029 根因：MaterialButton.setBackground(自定义 Drawable) 会把图标的绘制链路破坏
            // （setBackgroundDrawable 之后 MaterialButton 不再走自己的 icon/compound drawable 路径），
            // 实测 D4 变体图标直接消失；而 setBackgroundTintList(TRANSPARENT) 保留 MaterialShapeDrawable，
            // 图标正常（D8 变体可见）。这里改成 tint，不要 setBackground。
            button.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
            button.setElevation(0);
            button.requestLayout();
            button.invalidate();
            iconSizeSet = true;
            iconPaddingSet = true;
            iconGravitySet = true;
            iconTintSet = true;
            // post 到下一帧再打实测宽高（attribute 应用时还没 measure）
            button.post(() -> Log.i(TAG, "C-ICON: tint=0x" + Integer.toHexString(tintColor)
                    + " size=" + iconSizePx + "px w=" + button.getWidth() + " h=" + button.getHeight()
                    + " measured=" + button.getMeasuredWidth() + "x" + button.getMeasuredHeight()
                    + " pad=" + button.getPaddingLeft()
                    + " icon=" + (button.getIcon() == null ? "null" : button.getIcon().getClass().getSimpleName())
                    + " btnIconSize=" + button.getIconSize()
                    + " bg=" + (button.getBackground() == null ? "null" : button.getBackground().getClass().getSimpleName())
                    + " view=" + button.getClass().getSimpleName()));
        }
        Log.i(TAG, "C-ICON: style=" + value + " res=0x" + Integer.toHexString(styleRes)
                + " iconButton=" + iconButtonStyle
                + " sizeSet=" + iconSizeSet + " paddingSet=" + iconPaddingSet
                + " gravitySet=" + iconGravitySet + " tintSet=" + iconTintSet
                + " view=" + button.getClass().getSimpleName());
    }

    private static int attrId(Context context, String name) {
        try {
            return ResIds.of(context, name, "attr");
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static int dp(Context context, int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }

    /**
     * dev-1028：IconButton 的 iconTint 固定为实色——优先主题 colorOnSurfaceVariant，解析失败兜底 0xFF49454F。
     *
     * <p>之前直接把 {@code theme.resolveAttribute} 的结果当 int 颜色用：当主题里该 attr 指向
     * {@code ColorStateList} 资源（Material 主题很常见）时会解析成引用/透明，图标就是"看不见"的。
     * 这里显式处理 COLOR_INT 与 REFERENCE→ColorStateList 两种情况，最终一定给出不透明实色。
     */
    private static int resolveSolidIconTint(Context context) {
        int color = resolveThemeColor(context, "?attr/colorOnSurfaceVariant");
        if (color == 0) {
            color = resolveThemeColor(context, "?attr/colorOnSurface");
        }
        if (color == 0) {
            color = 0xFF49454F;
        }
        if ((color >>> 24) == 0) {
            // 完全透明的颜色在图标上看不到，强制不透明黑灰。
            color = 0xFF49454F;
        }
        return color;
    }

    private static int resolveThemeColor(Context context, String themeAttr) {
        try {
            int attr = Dimensions.resolveAttr(context, themeAttr);
            if (attr == 0) {
                return 0;
            }
            TypedValue typedValue = new TypedValue();
            if (!context.getTheme().resolveAttribute(attr, typedValue, true)) {
                return 0;
            }
            if (typedValue.type >= TypedValue.TYPE_FIRST_COLOR_INT
                    && typedValue.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return typedValue.data;
            }
            if (typedValue.type == TypedValue.TYPE_REFERENCE && typedValue.resourceId != 0) {
                try {
                    ColorStateList list = androidx.core.content.ContextCompat.getColorStateList(
                            context, typedValue.resourceId);
                    if (list != null) {
                        return list.getDefaultColor();
                    }
                } catch (Throwable ignored) {
                    // 不是 ColorStateList 资源就继续
                }
                try {
                    return androidx.core.content.ContextCompat.getColor(context, typedValue.resourceId);
                } catch (Throwable ignored) {
                    // 解析不到就用调用方兜底
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "C-ICON: 解析主题颜色失败 " + themeAttr, error);
        }
        return 0;
    }

    /**
     * dev-1028：解析 app:icon 的 Drawable（@drawable/@mipmap/file:///工程资源包）。
     * Drawables.parse 已覆盖大部分路径；再补一层按资源 id / mipmap / 工程 res 文件的兜底并打日志。
     */
    private Drawable resolveIconDrawable(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String text = value.trim();
        try {
            Drawable drawable = getDrawables().parse(getView(), text);
            if (drawable != null) {
                return drawable;
            }
        } catch (Throwable error) {
            Log.w(TAG, "C-ICON: Drawables.parse 失败 value=" + text, error);
        }
        try {
            Context context = getView().getContext();
            String name = com.stardust.autojs.core.ui.inflater.ProjectResources.nameOfReference(text);
            for (String type : new String[]{"drawable", "mipmap"}) {
                int resId = ResIds.of(context, name, type);
                if (resId != 0) {
                    Drawable drawable = context.getResources().getDrawable(resId);
                    Log.i(TAG, "C-ICON: icon 兜底按 resId 解析 value=" + text + " type=" + type
                            + " resId=0x" + Integer.toHexString(resId));
                    return drawable;
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "C-ICON: icon 按 resId 兜底失败 value=" + text, error);
        }
        return null;
    }
}
