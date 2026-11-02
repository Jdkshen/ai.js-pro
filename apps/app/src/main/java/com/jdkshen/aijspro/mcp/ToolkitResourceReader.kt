package com.jdkshen.aijspro.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.reandroid.arsc.value.Entry

/** One failed resource must not erase other results; incomplete XML is never editable text. */
internal class ToolkitResourceReader(private val resources: ToolkitResources) {
    fun read(args: JsonObject): JsonObject {
        val reads = args.getAsJsonArray("reads") ?: error("缺少 reads")
        require(reads.size() in 1..200) { "reads 数量必须为1..200" }
        val maxValue = args.tInt("maxValueChars", 4096, 1, 32768)
        val maxXml = args.tInt("maxValueXmlChars", 32768, 1, 1048576)
        val maxItems = args.tInt("maxItemsPerValue", 50, 0, 2000)
        val depth = args.tInt("resolveDepth", 0, 0, 1)
        val all = resources.entries()
        val byId = all.groupBy { it.resourceId }
        val variants = linkedMapOf<String, List<String>>()
        val results = JsonArray(); var returnedBytes = 0L
        reads.forEachIndexed { index, request ->
            val read = request.asJsonObject; val locator = read.tString("locator")
            val variant = ToolkitResources.publicVariant(ToolkitResources.internalVariant(read.tString("variant", "default")))
            try {
                val id = ToolkitResources.parseId(locator.substringAfter(':'))
                if(id in byId) variants["resource:0x%08x".format(id)] = byId[id].orEmpty().map { ToolkitResources.publicVariant(it.resConfig.qualifiers) }.distinct()
                val entry = resources.find(locator, variant)
                val result = value(entry, maxValue, maxXml, maxItems, depth, byId).apply { addProperty("index", index) }
                val bytes = result.toString().toByteArray(Charsets.UTF_8).size
                if(returnedBytes + bytes > 8 * 1024 * 1024) throw ToolkitException("OUTPUT_BUDGET_EXCEEDED", "批量结果超过预算，请减少reads或XML/集合预算")
                returnedBytes += bytes; results.add(result)
            } catch (failure: IllegalArgumentException) {
                results.add(ToolkitIo.json("index" to index, "locator" to locator, "variant" to variant,
                    "errorCode" to if(failure is ToolkitException) failure.code else "INVALID_ARGUMENT", "message" to failure.message))
            }
        }
        return ToolkitIo.json("results" to results, "items" to results,
            "variantSets" to variants.map { (locator, values) -> ToolkitIo.json("locator" to locator, "variants" to values) },
            "resourceNamePolicy" to ToolkitIo.json("confused" to false, "appReferenceOutput" to "names_or_hex",
                "appNameReferencesAccepted" to true, "systemNameReferencesAccepted" to ToolkitResources.systemReferencesAvailable, "hexPrefixAccepted" to true))
    }

    private fun value(entry: Entry, maxValue: Int, maxXml: Int, maxItems: Int, depth: Int,
                      byId: Map<Int, List<Entry>>): JsonObject {
        val full = resources.item(entry)
        val xml = resources.xml(entry)
        val scalar = entry.resValue
        val kind = if(entry.isComplex) "item_list" else kind(scalar?.valueType?.name.orEmpty())
        val children = entry.resValueMapArray?.listItems().orEmpty()
        val summary = if(entry.isComplex) "${children.size} items" else entry.valueAsString ?: when(kind) {
            "reference" -> "@0x%08x".format(scalar!!.data)
            "attribute" -> "?0x%08x".format(scalar!!.data)
            "boolean" -> (scalar!!.data != 0).toString()
            "integer" -> scalar!!.data.toString()
            else -> scalar?.toString().orEmpty()
        }
        full.addProperty("valueKind", kind)
        full.addProperty("value", prefix(summary, maxValue))
        full.addProperty("valueTruncated", summary.codePointCount(0, summary.length) > maxValue)
        full.addProperty("valueComplete", summary.codePointCount(0, summary.length) <= maxValue)
        full.add("valueXml", if(xml.length <= maxXml) com.google.gson.JsonPrimitive(xml) else com.google.gson.JsonNull.INSTANCE)
        full.addProperty("valueXmlCharCount", xml.length)
        full.addProperty("valueXmlTruncated", xml.length > maxXml)
        full.addProperty("valueXmlComplete", xml.length <= maxXml)
        full.addProperty("editable", true)
        full.add("editableReason", if(xml.length > maxXml) com.google.gson.JsonPrimitive("VALUE_XML_TRUNCATED") else com.google.gson.JsonNull.INSTANCE)
        if(entry.isComplex) {
            full.addProperty("itemCount", children.size)
            full.addProperty("itemsTruncated", children.size > maxItems)
            full.add("items", ToolkitIo.json("items" to children.take(maxItems).map { item ->
                val named = byId[item.nameId]?.firstOrNull()
                ToolkitIo.json("name" to if(named == null) "0x%08x".format(item.nameId) else "${named.typeName}/${named.name}",
                    "valueKind" to kind(item.valueType.name), "value" to item.toString())
            }).get("items"))
        }
        var resolved = false
        if(depth == 1 && kind == "reference" && scalar != null) {
            val candidates = byId[scalar.data].orEmpty()
            val target = candidates.firstOrNull { it.resConfig.qualifiers == entry.resConfig.qualifiers }
                ?: candidates.firstOrNull { it.resConfig.qualifiers.isEmpty() }
                ?: runCatching { resources.find("resource:0x%08x".format(scalar.data), ToolkitResources.publicVariant(entry.resConfig.qualifiers)) }.getOrNull()
                ?: runCatching { resources.find("resource:0x%08x".format(scalar.data), "default") }.getOrNull()
            if(target != null) {
                full.add("resolvedValue", value(target, maxValue, maxXml, maxItems, 0, byId)); resolved = true
            } else full.addProperty("resolveError", "RESOURCE_REFERENCE_UNRESOLVED")
        }
        full.addProperty("referencesResolved", resolved)
        return full
    }
    private fun prefix(value: String, count: Int) = value.substring(0, value.offsetByCodePoints(0, minOf(count, value.codePointCount(0, value.length))))
    private fun kind(type: String) = when(type) {
        "REFERENCE", "DYNAMIC_REFERENCE" -> "reference"
        "ATTRIBUTE", "DYNAMIC_ATTRIBUTE" -> "attribute"
        "BOOLEAN" -> "boolean"
        "STRING" -> "string"
        "DEC", "HEX", "INT_DEC", "INT_HEX" -> "integer"
        "DIMENSION" -> "dimen"
        else -> type.lowercase().ifEmpty { "unknown" }
    }
}
