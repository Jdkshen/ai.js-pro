package com.stardust.autojs.core.ui.inflater.util;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.view.View;

/**
 * Created by Stardust on 2017/11/3.
 */

public class Colors {

    public static int parse(Context context, String color) {
        if (color == null) {
            throw new IllegalArgumentException("Unknown color");
        }
        String text = color.trim();
        // 允许直接写颜色整数（`colors.parseColor("#F5C56B")` 的返回值）：JS 侧经常这么传，
        // 而 `Color.parseColor("-670357")` 会抛 Unknown color（真机日志：
        // `Cannot set view attribute textColor=-670357 Unknown color`）。
        if (text.matches("-?\\d+")) {
            try {
                return (int) Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                // 超出范围就落到标准解析
            }
        }
        if (text.startsWith("@color/")) {
            // app 与工程资源包两个包名都要试，见 ResIds。
            return context.getResources().getColor(ResIds.of(context,
                    text.substring("@color/".length()), "color"));
        }
        if (text.startsWith("@android:color/")) {
            String name = text.substring("@android:color/".length());
            int id = context.getResources().getIdentifier(name, "color", "android");
            if (id == 0) throw new Resources.NotFoundException("Android color resource not found: " + name);
            return context.getResources().getColor(id);
        }
        return Color.parseColor(text);
    }

    public static int parse(View view, String color){
        return parse(view.getContext(), color);
    }
}
