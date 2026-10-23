package com.stardust.autojs.engine.node24;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.stardust.autojs.core.ui.xml.XmlConverter;

import org.junit.Test;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

/**
 * Node 动态 UI（ui.inflate / Activity.layoutXml）的 XML 别名回归。
 *
 * <p>这两个用例只走 XmlConverter 的纯字符串映射，不初始化 Android Handler/View；可在 JVM 单测中运行。
 */
public class NodeUiDynamicXmlAliasTest {

    @Test
    public void shorthandSwitchMapsToSwitchCompat() throws Exception {
        String xml = XmlConverter.convertToAndroidLayout(
                "<column w=\"*\"><switch id=\"sw\" checked=\"true\"/></column>");

        assertWellFormed(xml);
        assertTrue(xml, xml.contains("androidx.appcompat.widget.SwitchCompat"));
        assertFalse(xml, xml.contains("android.widget.switch"));
    }

    @Test
    public void fullyQualifiedSwitchIsLeftUntouched() throws Exception {
        String xml = XmlConverter.convertToAndroidLayout(
                "<column w=\"*\"><android.widget.Switch id=\"sw\"/></column>");

        assertWellFormed(xml);
        assertTrue(xml, xml.contains("<android.widget.Switch"));
        assertFalse(xml, xml.contains("androidx.appcompat.widget.SwitchCompat"));
    }

    private static void assertWellFormed(String xml)
            throws ParserConfigurationException, IOException, SAXException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.newDocumentBuilder().parse(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
}
