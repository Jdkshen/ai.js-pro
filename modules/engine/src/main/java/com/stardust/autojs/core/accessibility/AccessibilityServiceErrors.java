package com.stardust.autojs.core.accessibility;

import java.util.Arrays;
import java.util.List;

/**
 * task-77：无障碍「未连接」时的**可抄**错误文案（唯一来源；`auto()` / `auto.waitFor()` 用）。
 *
 * <p>原来这两个 API 在服务未连接时抛的是笼统异常（用户脚本里只剩一句被 catch 掉的信息）。
 * 这里给：组件名 + 现象 + 可抄的修法；组件名由调用方从**既有唯一常量**传进来（不复制类名字符串）。
 */
public final class AccessibilityServiceErrors {

    private AccessibilityServiceErrors() {
    }

    /** 可抄的主错误文案（纯函数，便于单测）。 */
    public static String notConnectedMessage(String componentName) {
        return "无障碍服务未连接：component=" + display(componentName)
                + "。请到「系统设置 → 无障碍」里把本应用的服务**关掉再打开**（或调用 app.startActivity 打开无障碍设置），"
                + "然后重试 auto()/auto.waitFor()。";
    }

    /** 可抄的下一步（给日志/异常 cause 用）。 */
    public static List<String> nextActions(String componentName) {
        return Arrays.asList(
                "1) 确认已安装包与组件名一致：" + display(componentName),
                "2) 系统设置 → 无障碍 → 找到本应用的服务，关闭后重新打开（装包/强停后必须重开一次）",
                "3) 仍然不行就打开无障碍设置页：app.startActivity({action: 'android.settings.ACCESSIBILITY_SETTINGS'})",
                "4) 脚本侧可 events.on('accessibility_service_state_changed', ...) 观察连接状态变化");
    }

    /** 超时（waitFor 等待上限）文案。 */
    public static String waitTimeoutMessage(String componentName, long timeoutMillis) {
        return "等待无障碍服务连接超时（" + timeoutMillis + "ms）：component=" + display(componentName)
                + "。" + String.join("；", nextActions(componentName));
    }

    private static String display(String componentName) {
        return componentName == null || componentName.trim().isEmpty() ? "(未知组件名)" : componentName.trim();
    }
}