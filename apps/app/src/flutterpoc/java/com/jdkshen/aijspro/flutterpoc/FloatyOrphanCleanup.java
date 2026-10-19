package com.jdkshen.aijspro.flutterpoc;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;

/** task-86：清理脚本退出后残留的 floaty 浮窗（Rhino/EnhancedFloaty + QuickJS 宿主窗口）。 */
final class FloatyOrphanCleanup {

    private static final String TAG = "FloatyOrphanCleanup";

    private FloatyOrphanCleanup() {
    }

    static synchronized int cleanup(String reason) {
        int closed = 0;
        // 1) EnhancedFloaty 的 FloatyService.windows（RawWindow/BaseResizableFloatyWindow 都注册在这里）
        try {
            Class<?> service = Class.forName("com.stardust.enhancedfloaty.FloatyService");
            Field windowsField = service.getDeclaredField("windows");
            windowsField.setAccessible(true);
            Object value = windowsField.get(null);
            if (value instanceof Collection<?>) {
                Collection<?> windows = (Collection<?>) value;
                int skippedMenu = 0;
                for (Object window : windows.toArray()) {
                    String className = window == null ? "" : window.getClass().getName();
                    // task-111：不能误杀当前圆形悬浮菜单（重启 App 时菜单可能刚恢复）。
                    if (className.contains("CircularMenu")) {
                        skippedMenu++;
                        continue;
                    }
                    if (closeObject(window)) closed++;
                }
                if (skippedMenu > 0) {
                    Log.i(TAG, "keep " + skippedMenu + " circular menu window(s): " + reason);
                }
                windows.clear();
            }
        } catch (Throwable error) {
            Log.d(TAG, "cleanup enhanced floaty: " + error);
        }
        // 2) QuickJS 宿主桥的浮窗（QuickJsHostBridge 内部窗口表/句柄）
        try {
            Class<?> bridge = Class.forName("com.stardust.autojs.engine.QuickJsHostBridge");
            for (Field field : bridge.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                Class<?> type = field.getType();
                if (!Map.class.isAssignableFrom(type) && !Collection.class.isAssignableFrom(type)) continue;
                String name = field.getName().toLowerCase();
                if (!name.contains("floaty") && !name.contains("window") && !name.contains("overlay")) continue;
                field.setAccessible(true);
                Object value = field.get(null);
                if (value instanceof Map<?, ?>) {
                    for (Object item : ((Map<?, ?>) value).values().toArray()) {
                        if (closeObject(item)) closed++;
                    }
                    ((Map<?, ?>) value).clear();
                } else if (value instanceof Collection<?>) {
                    for (Object item : ((Collection<?>) value).toArray()) {
                        if (closeObject(item)) closed++;
                    }
                    ((Collection<?>) value).clear();
                }
            }
        } catch (Throwable error) {
            Log.d(TAG, "cleanup quickjs floaty: " + error);
        }
        if (closed > 0) {
            Log.i(TAG, "closed " + closed + " orphan floaty window(s): " + reason);
        }
        return closed;
    }

    private static boolean closeObject(Object target) {
        if (target == null) return false;
        for (String name : new String[]{"close", "dismiss", "remove", "release"}) {
            try {
                Method method = target.getClass().getMethod(name);
                method.invoke(target);
                return true;
            } catch (Throwable ignored) {
                // 尝试下一个
            }
        }
        return false;
    }
}
