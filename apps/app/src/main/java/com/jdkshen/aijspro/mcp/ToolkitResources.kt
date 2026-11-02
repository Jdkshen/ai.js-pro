package com.jdkshen.aijspro.mcp

import com.google.gson.*
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.xml.ResXmlDocument
import com.reandroid.arsc.coder.xml.XmlCoder
import com.reandroid.arsc.value.Entry
import com.reandroid.xml.XMLDocument
import com.reandroid.xml.XMLFactory
import java.io.*

/** Resource table / binary XML access using the pinned open-source ARSCLib. */
internal class ToolkitResources(bytes: ByteArray) {
    val table: TableBlock = TableBlock.load(ByteArrayInputStream(bytes))
    fun entries(): List<Entry> {
        val result = ArrayList<Entry>()
        table.getResources().forEachRemaining { resource ->
            resource.forEach { entry ->
                require(result.size < 200000) { "资源表超过 200000 个配置项" }
                if (!entry.isNull) result.add(entry)
            }
        }; return result
    }
    fun locator(entry: Entry) = "resource:0x%08x".format(entry.resourceId)
    fun find(locator: String, variant: String): Entry {
        val id = parseId(locator.substringAfter(':', locator))
        return table.getEntries(id).asSequence().firstOrNull { it.resConfig.qualifiers == variant }
            ?: throw IllegalArgumentException("资源或配置不存在：$locator / $variant")
    }
    fun xml(entry: Entry): String {
        val output = StringWriter(); val serializer = XMLFactory.newSerializer(output)
        val result = XmlCoder().VALUES_XML.decode(serializer, entry.resourceEntry, entry.resConfig) { false }
        require(result) { "资源 XML 解码失败" }; serializer.flush()
        // ARSCLib 的序列化器会把非 ASCII 转成 &#NNNN;，直接给脚本读极难辨认；
        // 数字字符引用与原文语义完全相同，这里还原成字符（保留 &amp; 等命名实体）。
        return unescapeNumericRefs(output.toString())
    }
    fun item(entry: Entry, includeXml: Boolean = false): JsonObject = ToolkitIo.json(
        "locator" to locator(entry), "id" to "0x%08x".format(entry.resourceId),
        "name" to entry.name, "type" to entry.typeName, "variant" to entry.resConfig.qualifiers,
        "complex" to entry.isComplex, "value" to (entry.valueAsString ?: entry.resValue?.toString()),
        "valueXml" to if (includeXml) xml(entry) else null,
        "targetVersion" to ToolkitIo.version(entry.bytes))
    fun write(entry: Entry, source: String) {
        safeXml(source)
        val element = XMLDocument.load(source).documentElement
        require(element != null) { "缺少资源 XML 元素" }
        require(element.getAttributeValue("name") == entry.name) { "编辑资源时不能更改资源名" }
        val tag = element.name
        require(tag == entry.typeName || tag == entry.xmlTag || (tag == "item" && element.getAttributeValue("type") == entry.typeName)) { "资源类型不匹配" }
        if (element.childElementsCount > 0 && entry.typeName !in listOf("string", "dimen", "color", "integer", "bool"))
            XmlCoder().VALUES_XML.encodeBag(element, entry)
        else XmlCoder().VALUES_XML.encodeScalar(element, entry)
    }
    fun add(packageId: String, variant: String, source: String): Entry {
        safeXml(source)
        val pkg = table.getPackageBlockById(parseId(packageId)) ?: error("资源包不存在")
        val element = XMLDocument.load(source).documentElement ?: error("缺少资源 XML 元素")
        val name = element.getAttributeValue("name") ?: error("缺少资源名")
        val type = if (element.name == "item") element.getAttributeValue("type") else element.name
        require(!type.isNullOrBlank() && name.matches(Regex("[A-Za-z_][A-Za-z0-9_.]*"))) { "资源名或类型无效" }
        require(pkg.getEntries(type, name).asSequence().none { it.resConfig.qualifiers == variant }) { "资源配置已存在" }
        val entry = pkg.getOrCreate(variant, type, name); write(entry, source); return entry
    }
    fun bytes(): ByteArray { table.refreshFull(); return table.bytes }

    companion object {
        fun parseId(value: String): Int = value.removePrefix("0x").toLong(16).also { require(it in 0..0xffffffffL) }.toInt()
        fun safeXml(value: String) {
            require(value.toByteArray(Charsets.UTF_8).size <= ToolkitIo.MAX_TEXT) { "XML 超过 10 MiB" }
            require(!Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(value)) { "XML 不允许文档类型或外部实体" }
        }
        fun decodeXml(bytes: ByteArray, table: ToolkitResources?): String {
            val xml = ResXmlDocument()
            if (table != null) xml.setPackageBlock(table.table.pickOne())
            xml.readBytes(ByteArrayInputStream(bytes)); return unescapeNumericRefs(xml.serializeToXml())
        }

        /** 把 &#NNN; / &#xHH; 还原成字符；非数字实体的 &amp; 等保持原样。 */
        internal fun unescapeNumericRefs(value: String): String {
            if (!value.contains("&#")) return value
            val out = StringBuilder(value.length)
            var index = 0
            while (index < value.length) {
                val current = value[index]
                if (current == '&' && index + 2 < value.length && value[index + 1] == '#') {
                    val semicolon = value.indexOf(';', index + 2)
                    if (semicolon > index + 2) {
                        val body = value.substring(index + 2, semicolon)
                        val codePoint = try {
                            if (body.startsWith("x", ignoreCase = true)) body.substring(1).toInt(16) else body.toInt()
                        } catch (_: NumberFormatException) { -1 }
                        if (Character.isValidCodePoint(codePoint) && codePoint > 0) {
                            out.appendCodePoint(codePoint); index = semicolon + 1; continue
                        }
                    }
                }
                out.append(current); index++
            }
            return out.toString()
        }
        fun encodeXml(source: String, resources: ToolkitResources?): ByteArray {
            safeXml(source)
            val xml = ResXmlDocument()
            if (resources != null) xml.setPackageBlock(resources.table.pickOne())
            xml.parse(XMLFactory.newPullParser(source)); xml.refreshFull(); return xml.bytes
        }
        fun isBinaryXml(bytes: ByteArray) = bytes.size >= 8 && bytes[0] == 3.toByte() && bytes[1] == 0.toByte() && bytes[2] == 8.toByte()
    }
}
