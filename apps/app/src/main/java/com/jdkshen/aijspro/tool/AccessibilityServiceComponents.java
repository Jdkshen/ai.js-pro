package com.jdkshen.aijspro.tool;

import com.stardust.view.accessibility.AccessibilityServiceUtils;

/** Preserve other accessibility services and recognize Android's shortened component spelling. */
final class AccessibilityServiceComponents {
    private AccessibilityServiceComponents() { }

    /**
     * 把 expected 追加进 configured（已含则不重复）。判定复用 AccessibilityServiceUtils.settingsContainsComponent，
     * 与「检查是否已启用」用同一份解析（避免两处归一化/漂移）。
     */
    static String enable(String configured, String expected) {
        if (configured == null || configured.isEmpty() || "null".equals(configured)) return expected;
        return AccessibilityServiceUtils.INSTANCE.settingsContainsComponent(configured, expected)
                ? configured
                : configured + ":" + expected;
    }
}
