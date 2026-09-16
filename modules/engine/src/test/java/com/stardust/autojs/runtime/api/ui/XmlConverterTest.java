package com.stardust.autojs.runtime.api.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.stardust.autojs.core.ui.xml.XmlConverter;

import org.junit.Test;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

/**
 * Created by Stardust on 2017/5/14.
 */
public class XmlConverterTest {


    @Test
    public void testNodeNameMap() throws IOException, SAXException, ParserConfigurationException {
        System.out.println(XmlConverter.convertToAndroidLayout("<linear></linear>"));
    }

    @Test
    public void testAttrNameMap() throws IOException, SAXException, ParserConfigurationException {
        System.out.println(XmlConverter.convertToAndroidLayout("<linear w=\"*\" h=\"123\"></linear>"));
    }

    @Test
    public void testNonMappedNodeName() throws IOException, SAXException, ParserConfigurationException {
        System.out.println(XmlConverter.convertToAndroidLayout(("<others></others>")));
    }

    @Test
    public void testChildrenNode() throws ParserConfigurationException, SAXException, IOException {
        System.out.println(XmlConverter.convertToAndroidLayout("<linear w=\"*\" h=\"123\"><text id=\"aaa\"/><button bg=\"#ffffff\"/></linear>"));
    }

    @Test
    public void testTextContent() throws ParserConfigurationException, SAXException, IOException {
        System.out.println(XmlConverter.convertToAndroidLayout("<linear><text id=\"aaa\">some text</text></linear>"));
    }

    /** Auto.js 的控制台组件：<console> / <globalconsole> 都要映射到视图类。 */
    @Test
    public void testConsoleTagsMap() throws ParserConfigurationException, SAXException, IOException {
        String xml = XmlConverter.convertToAndroidLayout(
                "<vertical><console id=\"c\"/><globalconsole id=\"gc\"/></vertical>");
        assertTrue(xml, xml.contains(
                com.stardust.autojs.core.console.ConsoleView.class.getName()));
        assertTrue(xml, xml.contains(
                com.stardust.autojs.core.console.GlobalConsoleView.class.getName()));
    }

    /**
     * 转换结果必须是良构 XML。
     *
     * <p>根节点的 {@code xmlns:android} 以前没有换行，会和紧随其后的第一个属性粘成
     * {@code xmlns:android="…"android:layout_width="…"} —— Android 的 DOM 解析器会直接报错。
     */
    @Test
    public void rootAttributesAreNotGluedToNamespaceDeclaration()
            throws ParserConfigurationException, SAXException, IOException {
        String xml = XmlConverter.convertToAndroidLayout("<linear w=\"*\" h=\"123\"><text id=\"aaa\"/></linear>");

        assertWellFormed(xml);
        assertTrue(xml, xml.contains("xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"));
    }

    /**
     * Android 原生布局（{@code res/layout/*.xml}）根节点自带命名空间声明时，
     * 转换结果不能出现第二份 {@code xmlns:android} —— 属性重复在 XML 里非法，
     * Android 的 harmony DOM 解析器会抛 {@code DOMException: xmlns}。
     */
    @Test
    public void nativeLayoutKeepsSingleNamespaceDeclaration()
            throws ParserConfigurationException, SAXException, IOException {
        String xml = XmlConverter.convertToAndroidLayout(
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                        + "<androidx.coordinatorlayout.widget.CoordinatorLayout\n"
                        + "    xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
                        + "    xmlns:app=\"http://schemas.android.com/apk/res-auto\"\n"
                        + "    android:layout_width=\"match_parent\"\n"
                        + "    android:layout_height=\"match_parent\"/>\n");

        assertEquals("xmlns:android 只能出现一次：" + xml, 1, countOf(xml, "xmlns:android"));
        assertEquals("xmlns:app 只能出现一次：" + xml, 1, countOf(xml, "xmlns:app"));
        assertFalse("原生布局的属性已经有前缀，不能再补一层：" + xml, xml.contains("android:android:"));
        assertTrue("原生属性要原样保留：" + xml, xml.contains("android:layout_width=\"match_parent\""));
        assertWellFormed(xml);
    }

    /** 原生布局里的 id / app: 属性也要原样保留（不能走 Auto.js 简写的名字映射）。 */
    @Test
    public void nativeLayoutKeepsNamespacedAttributes()
            throws ParserConfigurationException, SAXException, IOException {
        String xml = XmlConverter.convertToAndroidLayout(
                "<com.google.android.material.bottomnavigation.BottomNavigationView\n"
                        + "    xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
                        + "    xmlns:app=\"http://schemas.android.com/apk/res-auto\"\n"
                        + "    android:id=\"@+id/navigation\"\n"
                        + "    app:menu=\"@menu/navigation\"/>\n");

        assertFalse(xml, xml.contains("android:android:"));
        assertFalse(xml, xml.contains("android:app:"));
        assertTrue(xml, xml.contains("app:menu=\"@menu/navigation\""));
        assertTrue(xml, xml.contains("android:id=\"@+id/navigation\""));
        assertWellFormed(xml);
    }

    /** 子节点自己带命名空间声明时也不该被重复写出来。 */
    @Test
    public void childNamespaceDeclarationIsEmittedOnce()
            throws ParserConfigurationException, SAXException, IOException {
        String xml = XmlConverter.convertToAndroidLayout(
                "<linear xmlns:android=\"http://schemas.android.com/apk/res/android\" w=\"*\">\n"
                        + "    <text xmlns:android=\"http://schemas.android.com/apk/res/android\" id=\"a\"/>\n"
                        + "</linear>");

        assertEquals(xml, 1, countOf(xml, "xmlns:android"));
        assertWellFormed(xml);
    }

    private static void assertWellFormed(String xml)
            throws ParserConfigurationException, IOException, SAXException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.newDocumentBuilder().parse(
                new ByteArrayInputStream(xml.getBytes(Charset.forName("UTF-8"))));
    }

    private static int countOf(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }
}
