package com.jdkshen.aijspro.theme;

import android.app.Activity;
import android.graphics.Color;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

/**
 * 应用自己的页面（含脚本宿主页）的**系统栏**统一处理。
 *
 * 为什么需要单独一个类：
 * 1) 状态栏颜色必须跟 Miuix 页面同源 —— 深色判定的唯一出处是
 *    {@code AijsMiuixThemeState}（{@link AijsMiuixThemeKt}），在这里算一次就好，
 *    各处自己读 Pref 重算就会出现「黑状态栏配浅色页面」。
 * 2) 顺手清掉残留的「隐藏系统栏」标记（见 {@link AppThemePalette#withoutHiddenSystemBars}）：
 *    一旦别处（历史实现：脚本画板 drawing.show()）留下 FULLSCREEN/IMMERSIVE，
 *    状态栏就再也不显示，只剩顶部一条黑条，而且不会自愈。
 *
 * 典型用点：{@code external/open/RunIntentActivity} —— 它是**常驻前台到脚本结束**的
 * 半透明窗口，主题里没有 android:statusBarColor，不处理的话脚本页面顶部一直是纯黑
 * （真机 K40 实测：跑 YOLO 脚本时看到的就是这条黑条）。
 */
public final class AijsSystemBars {

    /** 浅色页面底色（与 Miuix 的 #F7F7F7 一致）。 */
    public static final int LIGHT_SURFACE = Color.rgb(0xF7, 0xF7, 0xF7);

    private AijsSystemBars() {
    }

    /** 当前是否深色（跟随系统或应用内开关，判定与 Compose 主题完全同一份）。 */
    public static boolean isDark(Activity activity) {
        AijsMiuixThemeKt.ensureMiuixThemeStateLoaded();
        return AijsMiuixThemeKt.isAijsDarkThemeResolved(activity);
    }

    /** 当前主题下该用的状态栏颜色。 */
    public static int surfaceColor(Activity activity) {
        return isDark(activity) ? Color.BLACK : LIGHT_SURFACE;
    }

    /**
     * 把状态栏刷成当前主题的底色，并把图标明暗调对。
     *
     * ⚠️ 必须同时加 {@code FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS}：
     * 只有这个标志置位时，平台才会用 {@code setStatusBarColor} 画状态栏底色；
     * 缺了它这一行是**空操作**，状态栏回落成平台默认的纯黑。
     * 真机（K40）踩过：`RunIntentActivity` 的主题是 {@code Theme.Translucent.NoTitleBar}
     * （没有 android:statusBarColor），而它的窗口 flags 是
     * {@code NOT_FOCUSABLE LAYOUT_IN_SCREEN HARDWARE_ACCELERATED} —— 没有这个标志，
     * 于是「跑脚本时顶部一条黑」即使调了 apply() 也照旧。
     * 实测：主界面状态栏 (247,247,247)，浮窗宿主前台时 (0,0,0)。
     *
     * @return 实际使用的颜色（调用方要显示/记录时用得上）
     */
    public static int apply(Activity activity) {
        if (activity == null) {
            return 0;
        }
        boolean dark = isDark(activity);
        int surface = dark ? Color.BLACK : LIGHT_SURFACE;
        Window window = activity.getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(surface);
        applyIconAppearance(window, dark);
        return surface;
    }

    /**
     * **透明**状态栏：内容仍然铺到状态栏下面，但状态栏本身不画底色 → 透出下面的 App。
     *
     * 给「浮窗 / 覆盖式脚本宿主」（{@code external/open/RunIntentActivity}）用。
     * 这里不能刷成我们自己的页面底色：这类宿主是压在别人 App 上的（真机场景：压在腾讯天天象棋上），
     * 刷成 #F7F7F7 就变成「顶部一条浅灰带」，比原来那条黑带更眼。
     * 黑带的成因是**缺 FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS**：没有它，平台认为这个窗口不画系统栏，
     * 状态栏就用不透明的默认色（黑）。补上这个标志再把颜色设成 TRANSPARENT，就是真透明。
     *
     * @return 实际的浅/深判定（调用方日志用）
     */
    public static boolean applyTransparent(Activity activity) {
        if (activity == null) {
            return true;
        }
        boolean dark = isDark(activity);
        Window window = activity.getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.TRANSPARENT);
        applyIconAppearance(window, dark);
        return dark;
    }

    /** 状态栏图标的明暗（浅色页 → 深色图标），并顺手清掉残留的隐藏系统栏标记。 */
    private static void applyIconAppearance(Window window, boolean dark) {
        View decor = window.getDecorView();
        int flags = AppThemePalette.withoutHiddenSystemBars(decor.getSystemUiVisibility());
        if (dark) {
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        } else {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        decor.setSystemUiVisibility(flags);
    }
}
