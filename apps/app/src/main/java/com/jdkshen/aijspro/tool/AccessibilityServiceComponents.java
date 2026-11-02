package com.jdkshen.aijspro.tool;

/** Preserve other accessibility services and recognize Android's shortened component spelling. */
final class AccessibilityServiceComponents {
    private AccessibilityServiceComponents() { }

    static String enable(String configured, String expected) {
        if (configured == null || configured.isEmpty() || "null".equals(configured)) return expected;
        String normalized = normalize(expected);
        for (String component : configured.split(":")) {
            if (normalized.equals(normalize(component))) return configured;
        }
        return configured + ":" + expected;
    }

    private static String normalize(String component) {
        int slash = component.indexOf('/');
        if (slash >= 0 && slash + 1 < component.length() && component.charAt(slash + 1) == '.')
            return component.substring(0, slash + 1) + component.substring(0, slash) + component.substring(slash + 1);
        return component;
    }
}
