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
        if (color.startsWith("@color/")) {
            // app 与工程资源包两个包名都要试，见 ResIds。
            return context.getResources().getColor(ResIds.of(context,
                    color.substring("@color/".length()), "color"));
        }
        if (color.startsWith("@android:color/")) {
            return Color.parseColor(color.substring(15));
        }
        return Color.parseColor(color);
    }

    public static int parse(View view, String color){
        return parse(view.getContext(), color);
    }
}
