package com.jdkshen.aijspro.ui.floating.layoutinspector;

import com.stardust.view.accessibility.NodeInfo;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Only these scalar properties are displayed and persisted; never parent links or resources. */
final class NodeProperties {
    static final String[] NAMES = {"id", "idHex", "fullId", "bounds", "depth", "desc", "className",
            "packageName", "text", "drawingOrder", "accessibilityFocused", "checked", "checkable",
            "clickable", "contextClickable", "dismissable", "editable", "enabled", "focusable",
            "focused", "visibleToUser", "indexInParent", "longClickable", "row", "rowCount",
            "rowSpan", "column", "columnCount", "columnSpan", "selected", "scrollable"};
    static { Arrays.sort(NAMES); }

    static Map<String, Object> read(NodeInfo node) {
        Map<String, Object> values = new LinkedHashMap<>();
        try {
            for (String name : NAMES) {
                Field field = NodeInfo.class.getDeclaredField(name);
                field.setAccessible(true);
                values.put(name, name.equals("idHex") ? node.getIdHex() : field.get(node));
            }
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        return values;
    }

    static void restore(NodeInfo node, Map<String, Object> values) {
        try {
            for (String name : NAMES) {
                if (!values.containsKey(name)) continue;
                Field field = NodeInfo.class.getDeclaredField(name);
                field.setAccessible(true);
                Object value = values.get(name);
                if (field.getType() == int.class) {
                    if (!(value instanceof Number)) throw new IllegalArgumentException("无效整数属性: " + name);
                    double number = ((Number) value).doubleValue();
                    if (!Double.isFinite(number) || number != (int) number)
                        throw new IllegalArgumentException("无效整数属性: " + name);
                    field.setInt(node, (int) number);
                } else if (field.getType() == boolean.class) {
                    if (!(value instanceof Boolean)) throw new IllegalArgumentException("无效布尔属性: " + name);
                    field.setBoolean(node, (Boolean) value);
                } else {
                    if (value != null && !(value instanceof String))
                        throw new IllegalArgumentException("无效文本属性: " + name);
                    if (value != null || (!name.equals("text") && !name.equals("bounds"))) field.set(node, value);
                }
            }
            node.setIdHex((String) values.get("idHex"));
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
}
