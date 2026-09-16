package com.stardust.autojs.core.ui.inflater.util;

import android.content.Context;
import android.content.res.Resources;
import android.view.View;

/**
 * Created by Stardust on 2017/11/4.
 */

public class Strings {


    public static String parse(Context context, String str) {
        if (str.startsWith("@string/")) {
            // 查表要用不带前缀的名字；包名两个都试（见 ResIds）。
            // 以前是把 "@string/xxx" 整串丢给 getIdentifier，必然查不到。
            int id = ResIds.of(context, str.substring("@string/".length()), "string");
            return id == 0 ? str : context.getResources().getString(id);
        }
        return str;
    }

    public static String parse(View view, String str) {
        return parse(view.getContext(), str);
    }
}
