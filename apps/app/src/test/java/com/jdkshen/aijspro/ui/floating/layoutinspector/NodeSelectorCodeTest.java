package com.jdkshen.aijspro.ui.floating.layoutinspector;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class NodeSelectorCodeTest {
    @Test public void escapesCodeAndPreservesUnicode() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("text", "微信\"\n\\测试"); values.put("clickable", false);
        assertEquals("text(\"微信\\\"\\n\\\\测试\").clickable(false).findOne();",
                NodeSelectorCode.generate(values, new HashSet<>(values.keySet())));
    }
    @Test public void fullIdOverridesShortIdAndHexIsReadOnly() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", "name"); values.put("fullId", "com.test:id/name"); values.put("idHex", "0x123");
        assertEquals("id(\"com.test:id/name\").findOne();", NodeSelectorCode.generate(values, values.keySet()));
    }
    @Test public void keepsNegativeBoundsAndSkipsUnselectedProperties() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("bounds", "(-12,0,1080,2400)"); values.put("text", "other");
        assertEquals("bounds(-12, 0, 1080, 2400).findOne();",
                NodeSelectorCode.generate(values, Collections.singleton("bounds")));
        assertEquals("", NodeSelectorCode.generate(values, Collections.emptySet()));
    }
    @Test public void searchHandlesCaseNullAndEmptyQuery() {
        assertTrue(NodeSelectorCode.matches("WECHAT", false, null, "com.WeChat.mm"));
        assertFalse(NodeSelectorCode.matches("WECHAT", true, "com.WeChat.mm"));
        assertTrue(NodeSelectorCode.matches("微信", true, null, "打开微信"));
        assertFalse(NodeSelectorCode.matches("  ", false, "anything"));
    }
}
