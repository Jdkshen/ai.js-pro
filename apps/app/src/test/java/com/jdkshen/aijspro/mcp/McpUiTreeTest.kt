package com.jdkshen.aijspro.mcp

import org.junit.Assert.*
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

class McpUiTreeTest {
    private class Node(val text: String = "", val children: List<Node> = emptyList()) : McpUiTree.Node {
        var closed = 0
        override val childCount get() = children.size
        override fun attributes() = mapOf("text" to text, "class" to "android.view.View")
        override fun child(index: Int) = children[index]
        override fun close() { closed++ }
    }
    @Test fun emitsValidXmlAndDetachedParentLinks() {
        val text = "中文 & < \"\n😀\u0001"
        val child = Node(text)
        val root = Node(children = listOf(child))
        val result = McpUiTree.capture(root)
        val xml = result.get("xml").asString
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
        assertEquals(text.replace("\u0001", ""), document.getElementsByTagName("node").item(1).attributes.getNamedItem("text").nodeValue)
        assertEquals(2, result.get("nodeCount").asInt)
        assertEquals(0, result.getAsJsonArray("nodes")[1].asJsonObject.get("parentId").asInt)
        assertEquals(1, root.closed); assertEquals(1, child.closed)
        assertTrue(result.get("readable").asBoolean)
    }
    @Test fun enforcesNodeAndDepthBudgets() {
        val root = Node(children = List(10) { Node("$it") })
        val result = McpUiTree.capture(root, maxNodes = 3)
        assertEquals(3, result.get("nodeCount").asInt)
        assertEquals("maxNodes", result.get("truncationReason").asString)
        assertEquals(0, root.children[2].closed)
        val deep = McpUiTree.capture(Node(children = listOf(Node("hidden"))), maxDepth = 0)
        assertEquals(1, deep.get("nodeCount").asInt)
        assertEquals("maxDepth", deep.get("truncationReason").asString)
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(result.get("xml").asString.byteInputStream())
    }
    @Test fun exposesEmptyShellAndTimeoutInsteadOfPretendingReadable() {
        assertFalse(McpUiTree.capture(Node()).get("readable").asBoolean)
        var time = 0L
        val root = Node("text")
        val result = McpUiTree.capture(root, timeoutMillis = 100, now = { time.also { time += 150 } })
        assertEquals(0, result.get("nodeCount").asInt)
        assertEquals("timeout", result.get("truncationReason").asString)
        assertEquals(1, root.closed)
    }
}
