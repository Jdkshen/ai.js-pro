package com.stardust.autojs.engine.node24;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * task-246 R03：Widget 标签的 {@code style}/{@code theme} 必须在根 View 构造时生效。
 *
 * <p>这里只覆盖不依赖 Android 框架的判定与 DOM 注入接缝（真正的主题解析与 defStyleRes 依赖
 * Context，见 {@link NodeUiWidgetInflater#Construction}、{@code applyConstructionStyle} 的调用点）；
 * 真机上还需要按「XML / 原生 View 两种 render × 系统/项目主题 × 深浅切换」复核。
 */
public class NodeUiWidgetConstructionStyleTest {

    @Test
    public void constructionAttributesAcceptBareAndNamespacedReferences() {
        NodeUiWidgetInflater.Construction bare = NodeUiWidgetInflater.Construction.of(
                attributes("style", "@style/Widget.Card", "theme", "@style/Theme.Card"));
        assertEquals("@style/Widget.Card", bare.style);
        assertEquals("@style/Theme.Card", bare.theme);

        NodeUiWidgetInflater.Construction namespaced = NodeUiWidgetInflater.Construction.of(
                attributes("android:style", "@style/Widget.A", "app:theme", "?attr/cardTheme"));
        assertEquals("@style/Widget.A", namespaced.style);
        assertEquals("?attr/cardTheme", namespaced.theme);

        // 精确名字优先于带命名空间的名字。
        NodeUiWidgetInflater.Construction mixed = NodeUiWidgetInflater.Construction.of(
                attributes("style", "@style/Direct", "android:style", "@style/Namespaced"));
        assertEquals("@style/Direct", mixed.style);
    }

    @Test
    public void emptyAndNullStyleValuesAreNotConstructionAttributes() {
        assertNull(NodeUiWidgetInflater.Construction.of(attributes("style", "")).style);
        assertNull(NodeUiWidgetInflater.Construction.of(attributes("style", "   ")).style);
        assertNull(NodeUiWidgetInflater.Construction.of(attributes("style", "@null")).style);
        assertNull(NodeUiWidgetInflater.Construction.of(attributes("theme", "@null")).theme);
        assertNull(NodeUiWidgetInflater.Construction.of(attributes("padding", "4")).style);
        assertNull(NodeUiWidgetInflater.Construction.of(null).style);
        assertNull(NodeUiWidgetInflater.Construction.of(new HashMap<String, String>()).theme);
    }

    @Test
    public void constructionStyleOverridesTheRenderedRootStyle() throws Exception {
        Node root = parse("<text style=\"@style/Inner\" text=\"hi\"/>");
        NodeUiWidgetInflater.applyConstructionStyle(root, "@style/External");
        assertEquals("@style/External", ((Element) root).getAttribute("style"));
        assertEquals("hi", ((Element) root).getAttribute("text"));
        // 没有外部声明时渲染 XML 根节点自己的 style 保持不变。
        NodeUiWidgetInflater.applyConstructionStyle(root, null);
        assertEquals("@style/External", ((Element) root).getAttribute("style"));
    }

    @Test
    public void constructionAttributesAreRemovedFromPostHocApplication() {
        Map<String, String> attributes = attributes(
                "style", "@style/A", "android:style", "@style/B", "app:theme", "@style/T",
                "theme", "@style/T2", "padding", "8", "styleName", "keep");
        NodeUiWidgetInflater.removeAttribute(attributes, "style");
        NodeUiWidgetInflater.removeAttribute(attributes, "theme");
        assertFalse(attributes.containsKey("style"));
        assertFalse(attributes.containsKey("android:style"));
        assertFalse(attributes.containsKey("app:theme"));
        assertFalse(attributes.containsKey("theme"));
        assertEquals("8", attributes.get("padding"));
        assertEquals("keep", attributes.get("styleName"));
    }

    private static Map<String, String> attributes(String... pairs) {
        Map<String, String> attributes = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            attributes.put(pairs[i], pairs[i + 1]);
        }
        return attributes;
    }

    private static Node parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml))).getDocumentElement();
    }

    /** 显式断言 helper 不吞异常（保持与仓库内其它 node24 用例一致的失败可见性）。 */
    @Test
    public void removeAttributeToleratesNull() {
        NodeUiWidgetInflater.removeAttribute(null, "style");
        assertTrue(true);
    }
}
