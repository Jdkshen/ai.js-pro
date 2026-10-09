package com.jdkshen.aijspro.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** Bounded, detached snapshot. No accessibility handles are retained in a tool result. */
internal object McpUiTree {
    interface Node : AutoCloseable {
        fun attributes(): Map<String, String>
        val childCount: Int
        fun child(index: Int): Node?
    }

    fun capture(root: Node, maxNodes: Int = 3000, maxDepth: Int = 64,
                timeoutMillis: Int = 3000, now: () -> Long = { System.nanoTime() / 1_000_000 }): JsonObject {
        require(maxNodes in 1..10000 && maxDepth in 0..128 && timeoutMillis in 100..15000)
        val started = now()
        val nodes = JsonArray()
        val xml = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<hierarchy>")
        var reason = ""
        var slots = 0
        var readable = false
        fun visit(node: Node, index: Int, parent: Int, depth: Int) {
            node.use {
                if (nodes.size() >= maxNodes) { reason = "maxNodes"; return }
                if (now() - started >= timeoutMillis) { reason = "timeout"; return }
                val attributes = node.attributes()
                val id = nodes.size()
                val children = node.childCount
                readable = readable || children > 0 || listOf("text", "content-desc").any {
                    !attributes[it].isNullOrBlank()
                } || listOf("clickable", "long-clickable", "scrollable", "editable").any {
                    attributes[it] == "true"
                }
                nodes.add(JsonObject().apply {
                    addProperty("nodeId", id); addProperty("parentId", parent)
                    addProperty("index", index); addProperty("depth", depth)
                    addProperty("childCount", children)
                    add("attributes", JsonObject().apply { attributes.forEach { (k, v) -> addProperty(k, v) } })
                })
                xml.append("<node index=\"").append(index).append('"')
                attributes.forEach { (key, value) ->
                    xml.append(' ').append(key).append("=\"").append(escapeXml(value)).append('"')
                }
                xml.append('>')
                if (children > 0 && depth >= maxDepth) reason = "maxDepth"
                else for (i in 0 until children) {
                    if (nodes.size() >= maxNodes || ++slots > maxNodes * 2) { reason = "maxNodes"; break }
                    if (now() - started >= timeoutMillis) { reason = "timeout"; break }
                    val child = node.child(i) ?: continue
                    visit(child, i, id, depth + 1)
                }
                xml.append("</node>")
            }
        }
        visit(root, 0, -1, 0)
        xml.append("</hierarchy>")
        return JsonObject().apply {
            addProperty("success", readable); addProperty("readable", readable)
            addProperty("status", if (readable) "SUCCESS" else "EMPTY_TREE")
            addProperty("nodeCount", nodes.size()); addProperty("xml", xml.toString())
            add("nodes", nodes); addProperty("truncated", reason.isNotEmpty())
            if (reason.isNotEmpty()) addProperty("truncationReason", reason)
            addProperty("elapsedMs", now() - started)
        }
    }

    private fun escapeXml(value: String): String = buildString {
        value.codePoints().forEach { cp ->
            when (cp) {
                38 -> append("&amp;")
                60 -> append("&lt;")
                62 -> append("&gt;")
                34 -> append("&quot;")
                10 -> append("&#10;")
                13 -> append("&#13;")
                9 -> append("&#9;")
                in 0x20..0xD7FF, in 0xE000..0xFFFD, in 0x10000..0x10FFFF -> appendCodePoint(cp)
            }
        }
    }
}
