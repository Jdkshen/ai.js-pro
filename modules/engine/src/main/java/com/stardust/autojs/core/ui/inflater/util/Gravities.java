package com.stardust.autojs.core.ui.inflater.util;

import android.view.Gravity;

/**
 * Created by Stardust on 2017/11/3.
 */

public class Gravities {

    public static int parse(String g) {
        int gravity = Gravity.NO_GRAVITY;
        String[] parts = g.toLowerCase().split("[|]");
        for (String part : parts) {
            switch (part) {
                case "center":
                    gravity = gravity | Gravity.CENTER;
                    break;
                case "left":
                case "textStart":
                    gravity = gravity | Gravity.LEFT;
                    break;
                case "right":
                case "textEnd":
                    gravity = gravity | Gravity.RIGHT;
                    break;
                // dev-993：Android 的 layout_gravity 大量使用 start/end（DrawerLayout 的
                // NavigationView layout_gravity="start"）。以前没这两个分支 → 返回 0，
                // NavigationView attach 时 isDrawerOpen 判不出 drawer 直接 FATAL。
                case "start":
                    gravity = gravity | Gravity.START;
                    break;
                case "end":
                    gravity = gravity | Gravity.END;
                    break;
                case "top":
                    gravity = gravity | Gravity.TOP;
                    break;
                case "bottom":
                    gravity = gravity | Gravity.BOTTOM;
                    break;
                case "center_horizontal":
                    gravity = gravity | Gravity.CENTER_HORIZONTAL;
                    break;
                case "center_vertical":
                    gravity = gravity | Gravity.CENTER_VERTICAL;
                    break;
            }
        }
        return gravity;
    }



}
