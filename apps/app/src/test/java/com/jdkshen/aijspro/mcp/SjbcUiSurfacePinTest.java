package com.jdkshen.aijspro.mcp;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * task-86：SJBC 在 UI/MCP 支持面上的**钉**。
 *
 * <p>① UI：Dart 侧那行只读展示必须在、必须是只读、必须只在有 sjbc 字段时出现（源码钉，因为
 * Flutter 页面跑不了 JVM 单测）；② MCP：本轮**裁决不新增** SJBC 专用工具，这条钉住「目录里没有它」，
 * 免得以后有人「为对齐而加」。</p>
 */
public class SjbcUiSurfacePinTest {

    private static String read(String relative) throws Exception {
        Path path = Paths.get(relative);
        if (!Files.exists(path)) path = Paths.get("../" + relative);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String readDart(String relative) throws Exception {
        Path path = Paths.get(relative);
        if (!Files.exists(path)) path = Paths.get("apps/app/" + relative);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    @Test
    public void dartShowsSjbcRowReadOnlyAndOnlyWhenConfigured() throws Exception {
        String dart = readDart("../../flutter-ui/lib/project_page.dart");
        if (!dart.contains("sjbcRow")) {
            dart = readDart("flutter-ui/lib/project_page.dart");
        }
        assertTrue("调用点必须判 sjbc 字段存在才显示", dart.contains("if (loaded?['sjbc'] is Map) sjbcRow()"));
        assertTrue(dart.contains("SJBC 资源目录："));
        assertTrue(dart.contains("期望指纹："));
        assertTrue(dart.contains("只读，真值在 project.json"));
        int start = dart.indexOf("Widget sjbcRow()");
        int end = dart.indexOf("Future<int?> countSjbcFiles");
        assertTrue(start > 0 && end > start);
        String block = dart.substring(start, end);
        assertFalse("只读展示里不能有可编辑控件", block.contains("textField("));
        assertFalse(block.contains("DropdownButton"));
        assertFalse(block.contains("Checkbox"));
    }

    @Test
    public void mcpDeliberatelyHasNoSjbcSpecificTool() throws Exception {
        String catalog = read("src/main/java/com/jdkshen/aijspro/mcp/McpToolCatalog.kt");
        String tools = read("src/main/java/com/jdkshen/aijspro/mcp/McpTools.kt");
        assertFalse("本轮裁决：不加 SJBC 专用工具（避免第二份真值）",
                catalog.contains("project_sjbc") || tools.contains("project_sjbc"));
        assertFalse(catalog.contains("sjbc_status") || tools.contains("sjbc_status"));
    }
}
