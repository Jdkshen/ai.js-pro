package com.jdkshen.aijspro.pluginclient;

import com.stardust.view.accessibility.NodeInfo;

import java.util.List;

/**
 * 布局分析：把无障碍捕获的 {@link NodeInfo} 树导出成 uiautomator 风格的 XML 文本。
 *
 * <p>供自研 VS Code 扩展的「布局分析(Inspect Layout)」命令使用；数据源与手机端
 * 悬浮窗里的「布局层次分析」是同一个 {@code LayoutInspector} 捕获结果。
 */
public final class DevPluginLayoutService {

    /** 命令名：捕获当前窗口并返回层次结构（无障碍服务需开启）。 */
    public static final String INSPECT_LAYOUT = "inspect_layout";

    private DevPluginLayoutService() {
    }

    public static boolean isLayoutCommand(String command) {
        return INSPECT_LAYOUT.equals(command);
    }

    /** 把捕获到的根节点导出成 XML 文本（属性与 uiautomator dump 对齐，便于阅读/对比）。 */
    public static String dump(NodeInfo root) {
        StringBuilder builder = new StringBuilder(4096);
        builder.append("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>\n");
        builder.append("<hierarchy>\n");
        appendNode(builder, root, 1);
        builder.append("</hierarchy>\n");
        return builder.toString();
    }

    private static void appendNode(StringBuilder builder, NodeInfo node, int indent) {
        for (int i = 0; i < indent; i++) {
            builder.append("  ");
        }
        builder.append("<node");
        attribute(builder, "class", node.getClassName());
        attribute(builder, "package", node.getPackageName());
        attribute(builder, "id", node.getId());
        attribute(builder, "text", node.getText());
        attribute(builder, "desc", node.getDesc());
        attribute(builder, "bounds", node.getBounds());
        attribute(builder, "depth", String.valueOf(node.getDepth()));
        attribute(builder, "index", String.valueOf(node.getIndexInParent()));
        attribute(builder, "clickable", String.valueOf(node.getClickable()));
        attribute(builder, "longClickable", String.valueOf(node.getLongClickable()));
        attribute(builder, "focusable", String.valueOf(node.getFocusable()));
        attribute(builder, "focused", String.valueOf(node.getFocused()));
        attribute(builder, "scrollable", String.valueOf(node.getScrollable()));
        attribute(builder, "selected", String.valueOf(node.getSelected()));
        attribute(builder, "checked", String.valueOf(node.getChecked()));
        attribute(builder, "enabled", String.valueOf(node.getEnabled()));
        attribute(builder, "visibleToUser", String.valueOf(node.getVisibleToUser()));
        List<NodeInfo> children = node.getChildren();
        if (children == null || children.isEmpty()) {
            builder.append(" />\n");
            return;
        }
        builder.append(">\n");
        for (NodeInfo child : children) {
            appendNode(builder, child, indent + 1);
        }
        for (int i = 0; i < indent; i++) {
            builder.append("  ");
        }
        builder.append("</node>\n");
    }

    private static void attribute(StringBuilder builder, String name, String value) {
        if (value == null) {
            return;
        }
        builder.append(' ').append(name).append("=\"").append(escape(value)).append('"');
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&': escaped.append("&amp;"); break;
                case '<': escaped.append("&lt;"); break;
                case '>': escaped.append("&gt;"); break;
                case '"': escaped.append("&quot;"); break;
                case '\n': escaped.append("&#10;"); break;
                case '\r': break;
                case '\t': escaped.append("&#9;"); break;
                default: escaped.append(c);
            }
        }
        return escaped.toString();
    }
}
