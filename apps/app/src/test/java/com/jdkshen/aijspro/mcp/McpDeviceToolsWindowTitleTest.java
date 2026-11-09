package com.jdkshen.aijspro.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * task-76：get_ui_layout.windows[] 的 title 字段（API&gt;=24）与既有字段顺序的**源码钉**测试。
 *
 * <p>为什么是源码钉而不是运行时断言：McpDeviceTools.layout 需要真实 AccessibilityService 与框架
 * AccessibilityWindowInfo（JVM 里无法构造，android.jar 是 stub），所以这里钉住「字段确实加了、
 * 加在最后、其它字段与顺序没动」这三件事；真机可观察点见交付报告里的命令。</p>
 */
public class McpDeviceToolsWindowTitleTest {

    private static String read(String relative) throws Exception {
        Path path = Paths.get(relative);
        if (!Files.exists(path)) path = Paths.get("apps/app/" + relative);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    @Test
    public void windowsJsonAddsTitleWithApiGuard() throws Exception {
        String source = read("src/main/java/com/jdkshen/aijspro/mcp/McpDeviceTools.kt");
        assertTrue("title 必须带 API>=24 守卫（低版本访问 window.title 会 NoSuchMethodError）",
                source.contains("addProperty(\"title\", if (Build.VERSION.SDK_INT >= 24) window.title?.toString() else null)"));
        assertTrue("低版本给 null，绝不能伪造字符串", !source.contains("\"title\", \"\""));
    }

    @Test
    public void existingFieldsKeepTheirOrderAndTitleGoesLast() throws Exception {
        String source = read("src/main/java/com/jdkshen/aijspro/mcp/McpDeviceTools.kt");
        String[] ordered = {
                "addProperty(\"id\", window.id)",
                "addProperty(\"type\", window.type)",
                "addProperty(\"active\", window.isActive)",
                "addProperty(\"focused\", window.isFocused)",
                "addProperty(\"layer\", window.layer)",
                "addProperty(\"packageName\", root?.packageName?.toString())",
                "addProperty(\"rawChildren\", root?.childCount ?: 0)",
                "addProperty(\"title\", if (Build.VERSION.SDK_INT >= 24) window.title?.toString() else null)",
        };
        int previous = -1;
        for (String field : ordered) {
            int at = source.indexOf(field);
            assertTrue("字段必须存在：" + field, at > 0);
            assertTrue("字段顺序不能变（客户端按字段读）：" + field, at > previous);
            previous = at;
        }
        assertEquals("windows[] 的字段数就是这 8 个（本测试钉住集合）", 8, ordered.length);
    }

    @Test
    public void toolDescriptionDocumentsTheApiRequirement() throws Exception {
        String source = read("src/main/java/com/jdkshen/aijspro/mcp/McpTools.kt");
        int at = source.indexOf("add(tool(\"get_ui_layout\",");
        assertTrue(at > 0);
        String description = source.substring(at, Math.min(source.length(), at + 400));
        assertTrue("描述要点名 title 与 API 24 与 null：" + description,
                description.contains("title") && description.contains("API 24") && description.contains("null"));
    }

    @Test
    public void noOtherMcpToolDumpsWindows() throws Exception {
        String provider = read("src/main/java/com/jdkshen/aijspro/mcp/McpAutomationProvider.kt");
        assertTrue("ui_global 只是全局动作，不是窗口 dump",
                provider.contains("\"ui_global\" -> McpUiActions.global("));
        String device = read("src/main/java/com/jdkshen/aijspro/mcp/McpDeviceTools.kt");
        assertEquals("mcp 包里只有一个 window 枚举点", 1, device.split("service\\.windows", -1).length - 1);
    }
}
