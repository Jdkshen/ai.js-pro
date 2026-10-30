package com.stardust.autojs.core.ui.xml;

import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import androidx.cardview.widget.CardView;
import androidx.drawerlayout.widget.DrawerLayout;

import android.widget.CheckBox;
import android.widget.DatePicker;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TimePicker;

import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.stardust.autojs.core.graphics.ScriptCanvasView;
import com.stardust.autojs.core.ui.widget.JsButton;
import com.stardust.autojs.core.ui.widget.JsEditText;
import com.stardust.autojs.core.ui.widget.JsFrameLayout;
import com.stardust.autojs.core.ui.widget.JsGridView;
import com.stardust.autojs.core.ui.widget.JsImageView;
import com.stardust.autojs.core.ui.widget.JsLinearLayout;
import com.stardust.autojs.core.ui.widget.JsListView;
import com.stardust.autojs.core.ui.widget.JsRelativeLayout;
import com.stardust.autojs.core.ui.widget.JsSpinner;
import com.stardust.autojs.core.ui.widget.JsTabLayout;
import com.stardust.autojs.core.ui.widget.JsTextView;
import com.stardust.autojs.core.ui.widget.JsToolbar;
import com.stardust.autojs.core.ui.widget.JsViewPager;
import com.stardust.autojs.core.ui.widget.JsWebView;

import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.StringReader;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

/**
 * Created by Stardust on 2017/5/14.
 */

public class XmlConverter {

    private static final String ANDROID_NAMESPACE_DECLARATION_NAME = "xmlns:android";
    private static final String ANDROID_NAMESPACE_DECLARATION =
            ANDROID_NAMESPACE_DECLARATION_NAME + "=\"http://schemas.android.com/apk/res/android\"\n";

    private static final NodeHandler NODE_HANDLER = new NodeHandler.NameRouter()
            .handler("vertical", new NodeHandler.VerticalHandler(JsLinearLayout.class.getName()))
            .defaultHandler(new NodeHandler.MapNameHandler()
                    .map("frame", JsFrameLayout.class.getName())
                    .map("linear", JsLinearLayout.class.getName())
                    .map("horizontal", JsLinearLayout.class.getName())
                    .map("relative", JsRelativeLayout.class.getName())
                    .map("button", JsButton.class.getName())
                    .map("text", JsTextView.class.getName())
                    .map("input", JsEditText.class.getName())
                    .map("img", JsImageView.class.getName())
                    .map("datepicker", DatePicker.class.getName())
                    .map("timepicker", TimePicker.class.getName())
                    .map("webview", JsWebView.class.getName())
                    .map("progressbar", ProgressBar.class.getName())
                    .map("seekbar", SeekBar.class.getName())
                    .map("spinner", JsSpinner.class.getName())
                    .map("radio", RadioButton.class.getName())
                    .map("radiogroup", RadioGroup.class.getName())
                    .map("checkbox", CheckBox.class.getName())
                    // Java 关键字大小写：android.widget.switch 不存在，所有引擎统一用兼容性更好的 SwitchCompat。
                    .map("switch", SwitchCompat.class.getName())
                    .map("scroll", ScrollView.class.getName())
                    .map("toolbar", JsToolbar.class.getName())
                    .map("canvas", ScriptCanvasView.class.getName())
                    .map("list", JsListView.class.getName())
                    .map("grid", JsGridView.class.getName())
                    .map("drawer", DrawerLayout.class.getName())
                    .map("appbar", AppBarLayout.class.getName())
                    .map("tabs", JsTabLayout.class.getName())
                    .map("viewpager", JsViewPager.class.getName())
                    .map("card", CardView.class.getName())
                    .map("fab", FloatingActionButton.class.getName())
                    // Auto.js 的控制台组件：console = 本脚本那份，globalconsole = 全局那份
                    .map("console", com.stardust.autojs.core.console.ConsoleView.class.getName())
                    .map("globalconsole",
                            com.stardust.autojs.core.console.GlobalConsoleView.class.getName())
            );

    private static final AttributeHandler ATTRIBUTE_HANDLER = new AttributeHandler.AttrNameRouter()
            .handler("w", new AttributeHandler.DimenHandler("width"))
            .handler("h", new AttributeHandler.DimenHandler("height"))
            .handler("size", new AttributeHandler.DimenHandler("textSize"))
            .handler("id", new AttributeHandler.IdHandler())
            .handler("vertical", new AttributeHandler.OrientationHandler())
            .handler("margin", new AttributeHandler.DimenHandler("layout_margin"))
            .handler("padding", new AttributeHandler.DimenHandler("padding"))
            .handler("marginLeft", new AttributeHandler.DimenHandler("layout_marginLeft"))
            .handler("marginRight", new AttributeHandler.DimenHandler("layout_marginRight"))
            .handler("marginTop", new AttributeHandler.DimenHandler("layout_marginTop"))
            .handler("marginBottom", new AttributeHandler.DimenHandler("layout_marginBottom"))
            .handler("paddingLeft", new AttributeHandler.DimenHandler("paddingLeft"))
            .handler("paddingRight", new AttributeHandler.DimenHandler("paddingRight"))
            .handler("paddingTop", new AttributeHandler.DimenHandler("paddingTop"))
            .handler("paddingBottom", new AttributeHandler.DimenHandler("paddingBottom"))
            .defaultHandler(new AttributeHandler.MappedAttributeHandler()
                    .mapName("align", "layout_gravity")
            );

    public static String convertToAndroidLayout(String xml) throws IOException, SAXException, ParserConfigurationException {
        return convertToAndroidLayout(new InputSource(new StringReader(xml)));
    }

    public static String convertToAndroidLayout(InputSource source) throws ParserConfigurationException, IOException, SAXException {
        return convertToAndroidLayout(source, null);
    }

    /** Optional per-inflation adapter; the shared converter keeps no adapter state. */
    public interface NodeAdapter {
        Node prepare(Node source);
        default Node onFailure(Node source, RuntimeException error) { throw error; }
    }

    public static String convertToAndroidLayout(String xml, NodeAdapter adapter)
            throws ParserConfigurationException, IOException, SAXException {
        return convertToAndroidLayout(new InputSource(new StringReader(xml)), adapter);
    }

    private static String convertToAndroidLayout(InputSource source, NodeAdapter adapter)
            throws ParserConfigurationException, IOException, SAXException {
        StringBuilder layoutXml = new StringBuilder();
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document document = builder.parse(source);
        Node root = document.getDocumentElement();
        handleNode(root, namespaceDeclarationsOf(root), layoutXml, adapter);
        return layoutXml.toString();
    }

    /**
     * 根节点要写的命名空间声明（每行一条，结尾带换行）。
     *
     * <p>以前这里是无条件写死一句 {@code xmlns:android="…"} 而且结尾没有换行，有两个问题：
     *
     * <ul>
     *     <li>布局本身就是从 {@code res/layout/*.xml} 拿来的 Android 原生布局时，根节点自带
     *         {@code xmlns:android}，于是同一个声明出现两次 —— 属性重复在 XML 里是非法的，
     *         Android 的 DOM 解析器（harmony）会直接抛 {@code DOMException: xmlns}
     *         （即工程示例「使用Android原生界面」用 {@code ui.layoutFile} 加载原生布局时报的那个错）；</li>
     *     <li>没有换行会和紧随其后的第一个属性粘成
     *         {@code xmlns:android="…"android:layout_width="…"}，同样不是合法 XML。</li>
     * </ul>
     *
     * <p>现在改成：源里的声明照抄（去重靠 {@link #isNamespaceDeclaration} 不再重复写属性），
     * 源里没有 {@code xmlns:android} 才补默认的。
     */
    private static String namespaceDeclarationsOf(Node root) {
        StringBuilder declarations = new StringBuilder();
        boolean hasAndroidNamespace = false;
        NamedNodeMap attributes = root.getAttributes();
        for (int i = 0; attributes != null && i < attributes.getLength(); i++) {
            Node attr = attributes.item(i);
            if (!isNamespaceDeclaration(attr)) {
                continue;
            }
            declarations.append(attr.getNodeName()).append("=\"")
                    .append(attr.getNodeValue()).append("\"\n");
            if (ANDROID_NAMESPACE_DECLARATION_NAME.equals(attr.getNodeName())) {
                hasAndroidNamespace = true;
            }
        }
        if (!hasAndroidNamespace) {
            declarations.insert(0, ANDROID_NAMESPACE_DECLARATION);
        }
        return declarations.toString();
    }

    /** {@code xmlns} / {@code xmlns:xxx} 这类命名空间声明：统一由根节点负责，不在属性里重复写。 */
    private static boolean isNamespaceDeclaration(Node attr) {
        String name = attr.getNodeName();
        return "xmlns".equals(name) || name.startsWith("xmlns:");
    }

    private static void handleNode(Node source, String namespace, StringBuilder layoutXml, NodeAdapter adapter) {
        Node node = adapter == null ? source : adapter.prepare(source);
        int openingStart = layoutXml.length();
        String mappedNodeName;
        try {
            mappedNodeName = writeOpening(node, source.getTextContent(), namespace, layoutXml);
        } catch (RuntimeException error) {
            if (adapter == null) throw error;
            layoutXml.setLength(openingStart);
            node = adapter.onFailure(source, error);
            mappedNodeName = writeOpening(node, source.getTextContent(), namespace, layoutXml);
        }
        layoutXml.append(">\n");
        handleChildren(source.getChildNodes(), layoutXml, adapter);
        layoutXml.append("</").append(mappedNodeName).append(">\n");
    }

    private static String writeOpening(Node node, String text, String namespace, StringBuilder xml) {
        String mapped = NODE_HANDLER.handleNode(node, namespace, xml);
        handleText(node.getNodeName(), text, xml);
        handleAttributes(node.getNodeName(), node.getAttributes(), xml);
        return mapped;
    }

    private static void handleText(String nodeName, String textContent, StringBuilder layoutXml) {
        if (textContent == null || textContent.isEmpty()) {
            return;
        }
        if (nodeName.equals("text") || nodeName.equals("button") || nodeName.equals("input"))
            layoutXml.append("android:text=\"").append(escapeAttribute(textContent)).append("\"\n");
    }

    public static String escapeAttribute(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
                .replace("\r", "&#13;").replace("\n", "&#10;");
    }

    private static void handleChildren(NodeList nodes, StringBuilder layoutXml, NodeAdapter adapter) {
        if (nodes == null)
            return;
        int len = nodes.getLength();
        for (int i = 0; i < len; i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE)
                continue;
            handleNode(node, "", layoutXml, adapter);
        }
    }


    private static void handleAttributes(String nodeName, NamedNodeMap attributes, StringBuilder layoutXml) {
        if (attributes == null)
            return;
        int len = attributes.getLength();
        for (int i = 0; i < len; i++) {
            Node attr = attributes.item(i);
            if (isNamespaceDeclaration(attr)) {
                continue;
            }
            handleAttribute(nodeName, attr, layoutXml);
        }
    }

    private static void handleAttribute(String nodeName, Node attr, StringBuilder layoutXml) {
        ATTRIBUTE_HANDLER.handle(nodeName, attr, layoutXml);
    }


}
