package com.jdkshen.aijspro.ui.floating.layoutinspector;

import com.google.gson.Gson;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Generates only supported selector calls, with string values escaped as JavaScript literals. */
final class NodeSelectorCode {
    private static final Gson JSON = new Gson();
    static boolean selectable(String name, Object value) {
        return value != null && !name.equals("idHex");
    }
    static String generate(Map<String, Object> values, Set<String> selected) {
        StringBuilder code = new StringBuilder();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String name = entry.getKey();
            Object value = entry.getValue();
            if (!selected.contains(name) || !selectable(name, value)) continue;
            if (name.equals("id") && selected.contains("fullId") && values.get("fullId") != null) continue;
            String argument;
            if (name.equals("bounds")) {
                Matcher matcher = Pattern.compile("^\\((-?\\d+),(-?\\d+),(-?\\d+),(-?\\d+)\\)$").matcher(value.toString());
                if (!matcher.matches()) continue;
                argument = matcher.group(1) + ", " + matcher.group(2) + ", " + matcher.group(3) + ", " + matcher.group(4);
            } else {
                argument = JSON.toJson(value);
            }
            if (code.length() > 0) code.append('.');
            code.append(name.equals("fullId") ? "id" : name).append('(').append(argument).append(')');
        }
        return code.length() == 0 ? "" : code.append(".findOne();").toString();
    }
    static boolean matches(String query, boolean matchCase, String... values) {
        if (query == null || query.trim().isEmpty()) return false;
        String needle = matchCase ? query : query.toLowerCase(java.util.Locale.ROOT);
        for (String value : values) {
            if (value != null && (matchCase ? value : value.toLowerCase(java.util.Locale.ROOT)).contains(needle)) return true;
        }
        return false;
    }
}
