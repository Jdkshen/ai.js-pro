package com.stardust.autojs.engine.node24;

import com.stardust.autojs.core.ui.inflater.ProjectResources;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * bug 2.4 / 2.6 / 2.3 的 inflater 侧回归（纯 JVM 接缝，不初始化 Android View/Handler）。
 *
 * <ul>
 *   <li>2.4：{@code <include layout="…">} 的数字 layoutId / 名字都要能解析；失败时报「布局不存在」
 *       而不是只回显一个裸数字（{@code NodeUiRecyclerViewInflater} 的 itemLayout/itemLayoutId 复用同一套）。</li>
 *   <li>2.6：{@code <include>} 经 NodeUiXmlSource 转换后必须保留 include 标签与 layout 引用
 *       （包括属性转换失败时），这样 inflater 才会展开成真实 child，ViewPager/list 的
 *       initAdapterFromChildren 才有正确的 child/adapter 数量。</li>
 *   <li>2.3：工程 res/drawable 下的 vector 等 xml 图标在无 aapt2 时给出可执行诊断。</li>
 * </ul>
 *
 * <p>真机仍需复核：include 展开后的 getChildCount() 与 ViewPager.setCurrentItem()、
 * 以及 list/grid 的 initAdapterFromChildren()（见本轮报告）。
 */
public class NodeUiWidgetInflaterLayoutReferenceTest {

    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    private ProjectResources projectResources(String... layoutNames) throws Exception {
        File res = temporary.newFolder("res");
        File layout = new File(res, "layout");
        assertTrue(layout.mkdirs() || layout.isDirectory());
        for (String name : layoutNames) {
            write(new File(layout, name + ".xml"), "<TextView/>");
        }
        return ProjectResources.load(temporary.getRoot(), "res");
    }

    // ---------------------------------------------------------------- 2.4

    @Test public void parsesNumericLayoutIdsAndRejectsNames() {
        assertEquals(2131427356, NodeUiWidgetInflater.parseLayoutId("2131427356"));
        assertEquals(2131427356, NodeUiWidgetInflater.parseLayoutId("@2131427356"));
        assertEquals(0x7f0b0001, NodeUiWidgetInflater.parseLayoutId("0x7f0b0001"));
        assertEquals(0x7f0b0001, NodeUiWidgetInflater.parseLayoutId("@0x7f0b0001"));
        assertEquals(-2131427356, NodeUiWidgetInflater.parseLayoutId("-2131427356"));
        assertEquals(0, NodeUiWidgetInflater.parseLayoutId("@layout/page_main"));
        assertEquals(0, NodeUiWidgetInflater.parseLayoutId("@id/page_main"));
        assertEquals(0, NodeUiWidgetInflater.parseLayoutId("page_main"));
        assertEquals(0, NodeUiWidgetInflater.parseLayoutId(""));
        assertEquals(0, NodeUiWidgetInflater.parseLayoutId(null));
    }

    @Test public void resolvesLayoutNamesAndNumericIdsThroughProjectResources() throws Exception {
        ProjectResources resources = projectResources("page_main", "grid_item");
        assertEquals("page_main", NodeUiWidgetInflater.resolveLayoutName("@layout/page_main", resources));
        assertEquals("page_main", NodeUiWidgetInflater.resolveLayoutName("@page_main", resources));
        assertEquals("page_main", NodeUiWidgetInflater.resolveLayoutName("page_main", resources));
        assertEquals("page_main", NodeUiWidgetInflater.resolveLayoutName("page_main.xml", resources));

        int id = resources.idOf(ProjectResources.TYPE_LAYOUT, "page_main");
        assertTrue("合成 layout id 应为非 0", id != 0);
        assertEquals("page_main", NodeUiWidgetInflater.resolveLayoutName("@" + id, resources));
        assertEquals("page_main", NodeUiWidgetInflater.resolveLayoutName(Integer.toString(id), resources));

        // 不是布局的资源/framework 布局不应被当成工程布局。
        assertNull(NodeUiWidgetInflater.resolveLayoutName("@color/mainColor", resources));
        assertNull(NodeUiWidgetInflater.resolveLayoutName("@android:layout/simple_list_item_1", resources));
        assertNull(NodeUiWidgetInflater.resolveLayoutName(null, resources));
    }

    @Test public void numericIdWithoutResourceTableCannotBeAnswered() throws Exception {
        // 没有资源表时数字 id 反查不出名字：返回 null，交给上层报「布局不存在」而不是猜。
        assertNull(NodeUiWidgetInflater.resolveLayoutName("2131427356", null));
    }

    @Test public void missingLayoutMessageSaysBuJuBuCunZaiAndListsCandidates() throws Exception {
        ProjectResources resources = projectResources("page_main");
        String byName = NodeUiWidgetInflater.layoutMissingMessage("@layout/page_console", resources);
        assertTrue(byName, byName.contains("布局不存在"));
        assertTrue(byName, byName.contains("page_console"));
        assertTrue(byName, byName.contains("page_main"));

        String byId = NodeUiWidgetInflater.layoutMissingMessage("2131427356", resources);
        assertTrue(byId, byId.contains("布局不存在"));
        assertTrue(byId, byId.contains("2131427356"));
        assertFalse("诊断不能只是裸数字", byId.trim().equals("2131427356"));

        String noTable = NodeUiWidgetInflater.layoutMissingMessage("@layout/page_main", null);
        assertTrue(noTable, noTable.contains("布局不存在"));
        assertTrue(noTable, noTable.contains("project resources=null"));
    }

    // ---------------------------------------------------------------- 2.6

    @Test public void includeKeepsTagAndLayoutReferenceThroughConversion() throws Exception {
        NodeUiXmlSource source = new NodeUiXmlSource();
        Node root = parse(source.convert(
                "<frame><include layout=\"@layout/page_main\" w=\"match_parent\"/></frame>",
                n -> false, n -> n));
        Node include = firstElementChild(root);
        assertEquals("include", include.getNodeName());
        assertEquals("@layout/page_main", attribute(include, "layout"));
        NodeUiXmlSource.Input input = source.take(include);
        assertEquals("include", input.tag);
        assertNull(input.conversionError);
    }

    @Test public void includeSurvivesAttributeConversionFailure() throws Exception {
        // w="" 会让 DimenHandler 转换失败：DOM 换成占位标签，但 include 的 layout 引用必须留在
        // Input 里，NodeUiWidgetInflater 会把它还原后展开（否则页面不会进入 parent，child 数算成 0）。
        NodeUiXmlSource source = new NodeUiXmlSource();
        Node root = parse(source.convert(
                "<frame><include layout=\"@layout/page_main\" w=\"\"/></frame>",
                n -> false, n -> n));
        Node include = firstElementChild(root);
        NodeUiXmlSource.Input input = source.take(include);
        assertEquals("include", input.tag);
        assertEquals("@layout/page_main", input.attributes.get("layout"));
        assertNotNull(input.conversionError);
    }

    // ---------------------------------------------------------------- 2.3

    @Test public void vectorDrawableRootsAreDetectedAndReportedWithExecutableFix() throws Exception {
        File vector = temporary.newFile("ic_add.xml");
        write(vector, "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\""
                + " android:width=\"24dp\" android:height=\"24dp\"/>");
        assertEquals("vector", NodeUiWidgetInflater.xmlDrawableRootTag(vector));
        assertTrue(NodeUiWidgetInflater.isUnsupportedXmlDrawableRoot("vector"));
        assertTrue(NodeUiWidgetInflater.isUnsupportedXmlDrawableRoot("animated-vector"));
        assertTrue(NodeUiWidgetInflater.isUnsupportedXmlDrawableRoot("selector"));
        assertFalse(NodeUiWidgetInflater.isUnsupportedXmlDrawableRoot("shape"));
        assertFalse(NodeUiWidgetInflater.isUnsupportedXmlDrawableRoot(null));

        String message = NodeUiWidgetInflater.vectorDrawableDiagnostic("@drawable/ic_add", vector, "vector");
        assertTrue(message, message.contains("build-project-res.ps1"));
        assertTrue(message, message.contains("ic_add.xml"));
        assertTrue(message, message.contains("<shape>"));
    }

    @Test public void shapeDrawablesAreNotReported() throws Exception {
        File shape = temporary.newFile("bg_card.xml");
        write(shape, "<shape xmlns:android=\"http://schemas.android.com/apk/res/android\">"
                + "<solid android:color=\"#FFFFFF\"/></shape>");
        assertEquals("shape", NodeUiWidgetInflater.xmlDrawableRootTag(shape));
        assertFalse(NodeUiWidgetInflater.isUnsupportedXmlDrawableRoot("shape"));
    }

    // ---------------------------------------------------------------- helpers

    private static void write(File file, String text) throws Exception {
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            writer.write(text);
        }
    }

    private static Node parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml))).getDocumentElement();
    }

    private static Node firstElementChild(Node parent) {
        Node node = parent.getFirstChild();
        while (node != null && node.getNodeType() != Node.ELEMENT_NODE) {
            node = node.getNextSibling();
        }
        return node;
    }

    /** 转换后的 include 属性可能是裸 layout，也可能被 XmlConverter 写成 android:layout。 */
    private static String attribute(Node node, String name) {
        Node direct = node.getAttributes().getNamedItem(name);
        if (direct != null) {
            return direct.getNodeValue();
        }
        for (int i = 0; i < node.getAttributes().getLength(); i++) {
            Node attr = node.getAttributes().item(i);
            if (attr.getNodeName().endsWith(":" + name)) {
                return attr.getNodeValue();
            }
        }
        return null;
    }
}
