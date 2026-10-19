package com.stardust.autojs.core.ui.inflater;

import android.content.Context;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;

/**
 * Pro 工程布局里的「宿主类」兼容层（只在 inflater 内使用）。
 *
 * <p>Auto.js Pro 的示例工程 {@code res/layout} 会引用 Pro 自带的宿主控件；本仓库没有的那几个
 * （最典型的是 Node UI 示例 {@code page_console.xml} 里的
 * {@code com.stardust.autojs.core.console.FileConsoleView}）会让 {@code Class.forName} 抛
 * ClassNotFoundException，整个 activity_main inflate 失败（dev-988）。
 *
 * <p>这里做最小兼容：已知类映射到本仓库的等价类；其它 {@code com.stardust.autojs.*} 未知类
 * 用占位 TextView 保证布局能显示（控件功能缺失，logcat 有 W 日志），不再让整个界面失败。
 */
final class ProjectViewCompat {

    private static final String LOG_TAG = "ProjectViewCompat";

    private static final Map<String, String> ALIASES = new HashMap<>();

    static {
        // Pro 的 FileConsoleView 在本仓库对应 ConsoleView（ConsoleView 也是 FrameLayout + 日志列表，
        // 构造函数 (Context) 可用；Pro 示例里它只当显示区用）。
        ALIASES.put("com.stardust.autojs.core.console.FileConsoleView",
                "com.stardust.autojs.core.console.ConsoleView");
    }

    private ProjectViewCompat() {
    }

    /**
     * @return 兼容 View；不是本仓库能兜住的类时返回 null（调用方继续抛原始 CNFE）
     */
    static View create(Context context, String name) {
        if (context == null) {
            return null;
        }
        String alias = ALIASES.get(name);
        if (alias != null) {
            try {
                Class<?> clazz = Class.forName(alias, false, context.getClassLoader());
                Constructor<?> constructor = clazz.getConstructor(Context.class);
                Log.w(LOG_TAG, "布局里的宿主类 " + name + " 在本仓库不存在，用兼容类 " + alias + " 代替");
                return (View) constructor.newInstance(context);
            } catch (Throwable error) {
                Log.w(LOG_TAG, "兼容类 " + alias + " 实例化失败，回退占位 TextView", error);
            }
        }
        if (name.startsWith("com.stardust.autojs.")) {
            Log.w(LOG_TAG, "布局里的宿主类 " + name + " 不存在且无兼容映射，用占位 TextView 保证布局显示（该控件功能缺失）");
            TextView placeholder = new TextView(context);
            placeholder.setText("[缺少宿主控件] " + name.substring(name.lastIndexOf('.') + 1));
            return placeholder;
        }
        return null;
    }
}
