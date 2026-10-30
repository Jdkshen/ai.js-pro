package com.stardust.autojs.engine.node24;

import com.stardust.autojs.core.ui.xml.XmlConverter;
import org.junit.Test;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import java.io.StringReader;
import java.util.HashSet;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import static org.junit.Assert.*;

public class NodeUiXmlSourceTest {
    private static Node parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml))).getDocumentElement();
    }
    @Test public void registeredBuiltinRetainsUnconvertedAttributesAndEntities() throws Exception {
        NodeUiXmlSource source = new NodeUiXmlSource();
        Node node = parse(source.convert("<!-- lead --><button size=\"\" caption=\"&quot;A&amp;B&#x4e2d;&#25991;&quot;\"/>", n -> true, n -> n));
        assertEquals("aijs-widget-node", node.getNodeName());
        NodeUiXmlSource.Input input = source.take(node);
        assertEquals("button", input.tag); assertEquals("", input.attributes.get("size"));
        assertEquals("\"A&B中文\"", input.attributes.get("caption"));
        assertSame(input, source.take(node));
        assertNull(node.getAttributes().getNamedItem("android:__aijsSourceNode"));
    }
    @Test public void lateBuiltinKeepsOriginalNameAlongsideNativeFallback() throws Exception {
        NodeUiXmlSource source = new NodeUiXmlSource(); Set<String> tags = new HashSet<>();
        Node node = parse(source.convert("<button size=\"18\"/>", tags::contains, n -> n));
        assertTrue(node.getNodeName(), node.getNodeName().endsWith("JsButton"));
        tags.add("button"); NodeUiXmlSource.Input input = source.take(node);
        assertTrue(tags.contains(input.tag)); assertEquals("18", input.attributes.get("size"));
    }
    @Test public void conversionFailureIsDeferredWithoutDiscardingChildSources() throws Exception {
        NodeUiXmlSource source = new NodeUiXmlSource();
        Node root = parse(source.convert("<frame><late size=\"\"/><text text=\"safe\"/></frame>", n -> false, n -> n));
        Node late = root.getFirstChild(); while (late.getNodeType() != Node.ELEMENT_NODE) late = late.getNextSibling();
        NodeUiXmlSource.Input input = source.take(late);
        assertEquals("late", input.tag); assertNotNull(input.conversionError);
        assertEquals("", input.attributes.get("size"));
        Node text = late.getNextSibling(); while (text.getNodeType() != Node.ELEMENT_NODE) text = text.getNextSibling();
        assertEquals("text", source.take(text).tag);
    }
    @Test public void nestedConversionsDoNotOverwriteOutstandingOuterSources() throws Exception {
        NodeUiXmlSource source = new NodeUiXmlSource();
        Node outer = parse(source.convert("<outer id=\"original\"/>", n -> true, n -> n));
        Node inner = parse(source.convert("<inner id=\"nested\"/>", n -> true, n -> n));
        assertEquals("nested", source.take(inner).attributes.get("id"));
        assertEquals("original", source.take(outer).attributes.get("id"));
    }
    @Test public void ordinaryConverterStillRejectsMalformedDimensions() throws Exception {
        try { XmlConverter.convertToAndroidLayout("<text size=\"\"/>"); fail("Expected invalid native dimension"); }
        catch (StringIndexOutOfBoundsException expected) { }
    }
    @Test public void commentsBeforeNativeRootAndCdataRemainValid() throws Exception {
        NodeUiXmlSource source = new NodeUiXmlSource();
        Node root = parse(source.convert("<?xml version=\"1.0\"?><!-- <button/> --><text><![CDATA[A>B]]></text>", n -> false, n -> n));
        assertEquals("A>B", root.getAttributes().getNamedItem("android:text").getNodeValue());
        assertEquals("text", source.take(root).tag);
    }
}
