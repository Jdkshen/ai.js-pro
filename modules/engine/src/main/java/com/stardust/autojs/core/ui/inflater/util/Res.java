package com.stardust.autojs.core.ui.inflater.util;

import android.content.Context;
import android.view.View;

/**
 * Created by Stardust on 2017/11/5.
 */

public class Res {
    public static int parseStyle(View view, String value) {
        return parseStyle(view.getContext(), value);
    }

    public static int parseStyle(Context context, String value) {
        // FIXME: 2017/11/5 Can or should it retrieve android.R.style or styleable?
        if (value.startsWith("@style/")) {
            value = value.substring(7);
        }
        // app 与工程资源包两个包名都试，见 ResIds。
        return ResIds.of(context, value, "style");
    }
}
