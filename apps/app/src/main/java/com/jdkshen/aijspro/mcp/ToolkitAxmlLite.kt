package com.jdkshen.aijspro.mcp

/**
 * 有界二进制 AXML 兜底解析器。
 *
 * 为什么需要（GPT 复盘 §4.1）：ARSCLib 的完整 XML 解码依赖资源包上下文，遇到加固包或纯 native
 * 包会直接失败（`Can not decode without package`），结果连 package/version/minSdk 这些基础字段都拿不到。
 * 复盘要求把「基础字段解析」与「完整资源解码」分开：基础字段不应被资源上下文问题阻断。
 *
 * 本解析器只读字符串池与 start-element 的标签/属性：不解析资源引用、不写文件、不依赖 Android 框架，
 * 所有遍历都有字节/条目/字符串预算；任何结构异常都返回 null（由调用方保留原始失败原因）。
 */
internal object ToolkitAxmlLite {
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_XML_TYPE = 0x0003
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102
    private const val RES_XML_RESOURCE_MAP_TYPE = 0x0180

    private const val NO_INDEX = -1L
    private const val MAX_CHUNKS = 200_000
    private const val MAX_STRINGS = 100_000
    private const val MAX_STRING_CHARS = 1_000_000

    /** 只从 AXML 取回的基础字段；解析不到的字段为 null，不用默认值伪装。 */
    data class ManifestFacts(
        val packageName: String? = null,
        val split: String? = null,
        val versionCode: Int? = null,
        val versionName: String? = null,
        val compileSdkVersion: String? = null,
        val minSdk: Int? = null,
        val targetSdk: Int? = null,
        val maxSdk: Int? = null,
        val extractNativeLibs: Boolean? = null,
        val elementCount: Int = 0,
        val attributeCount: Int = 0
    )

    /** 文件头是否为 RES_XML_TYPE（大小端都支持）。 */
    fun looksBinaryXml(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        val le = u16(bytes, 0, true)
        val be = u16(bytes, 0, false)
        return le == RES_XML_TYPE || be == RES_XML_TYPE
    }

    /** 解析 AndroidManifest.xml 的基础字段；结构不合法或没有 manifest 元素时返回 null。 */
    fun parseManifest(bytes: ByteArray): ManifestFacts? {
        if (!looksBinaryXml(bytes)) return null
        val little = u16(bytes, 0, true) == RES_XML_TYPE
        val headerSize = u16(bytes, 2, little)
        val declaredSize = u32(bytes, 4, little)
        if (headerSize < 8 || headerSize > bytes.size) return null
        val end = minOf(declaredSize, bytes.size.toLong()).toInt()
        if (end <= headerSize) return null

        var strings: List<String> = emptyList()
        var packageName: String? = null
        var split: String? = null
        var versionCode: Int? = null
        var versionName: String? = null
        var compileSdkVersion: String? = null
        var minSdk: Int? = null
        var targetSdk: Int? = null
        var maxSdk: Int? = null
        var extractNativeLibs: Boolean? = null
        var elements = 0
        var attributes = 0
        var sawManifest = false

        var pos = headerSize
        var chunks = 0
        while (pos + 8 <= end && chunks < MAX_CHUNKS) {
            val type = u16(bytes, pos, little)
            val chunkHeader = u16(bytes, pos + 2, little)
            val chunkSize = u32(bytes, pos + 4, little)
            if (chunkSize < 8L || pos + chunkSize > end) break
            when (type) {
                RES_STRING_POOL_TYPE -> strings = readStringPool(bytes, pos, chunkHeader, chunkSize.toInt(), little)
                RES_XML_START_ELEMENT_TYPE -> {
                    val element = readStartElement(bytes, pos, chunkHeader, chunkSize.toInt(), strings, little)
                    if (element != null) {
                        elements++
                        attributes += element.attributes.size
                        when (element.name) {
                            "manifest" -> {
                                sawManifest = true
                                element.attributes.forEach { (name, value) ->
                                    when (name) {
                                        "package" -> if (packageName == null) packageName = value
                                        "split" -> if (split == null) split = value
                                        "versionCode" -> if (versionCode == null) versionCode = parseInt(value)
                                        "versionName" -> if (versionName == null) versionName = value
                                        "compileSdkVersion" -> if (compileSdkVersion == null) compileSdkVersion = value
                                    }
                                }
                            }
                            "uses-sdk" -> element.attributes.forEach { (name, value) ->
                                when (name) {
                                    "minSdkVersion" -> if (minSdk == null) minSdk = parseInt(value)
                                    "targetSdkVersion" -> if (targetSdk == null) targetSdk = parseInt(value)
                                    "maxSdkVersion" -> if (maxSdk == null) maxSdk = parseInt(value)
                                }
                            }
                            "application" -> element.attributes.forEach { (name, value) ->
                                if (name == "extractNativeLibs" && extractNativeLibs == null) {
                                    extractNativeLibs = value?.trim()?.lowercase()?.let {
                                        when (it) {
                                            "true" -> true
                                            "false" -> false
                                            else -> null
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                RES_XML_RESOURCE_MAP_TYPE -> Unit // 属性名走字符串池即可，不需要资源映射
                else -> Unit
            }
            pos += chunkSize.toInt()
            chunks++
        }
        if (!sawManifest) return null
        return ManifestFacts(packageName, split, versionCode, versionName, compileSdkVersion,
            minSdk, targetSdk, maxSdk, extractNativeLibs, elements, attributes)
    }

    private class Element(val name: String, val attributes: List<Pair<String, String?>>)

    private fun readStringPool(bytes: ByteArray, pos: Int, headerSize: Int, chunkSize: Int, little: Boolean): List<String> {
        if (headerSize < 28 || pos + headerSize > bytes.size) return emptyList()
        val count = u32(bytes, pos + 8, little)
        val flags = u32(bytes, pos + 16, little)
        val stringsStart = u32(bytes, pos + 20, little)
        val utf8 = (flags and 0x100L) != 0L
        val offsetsBase = pos + headerSize
        val stringsBase = pos + stringsStart.toInt()
        if (stringsBase < pos || stringsBase > bytes.size) return emptyList()
        val limit = minOf(count, MAX_STRINGS.toLong()).toInt()
        val result = ArrayList<String>(limit)
        for (i in 0 until limit) {
            val offsetPos = offsetsBase + i * 4
            if (offsetPos + 4 > bytes.size) break
            val offset = u32(bytes, offsetPos, little)
            val at = stringsBase + offset.toInt()
            if (at < stringsBase || at >= bytes.size) { result.add(""); continue }
            result.add(if (utf8) readUtf8(bytes, at) else readUtf16(bytes, at, little))
        }
        // 保持索引与 AXML 里的下标一致：解析失败的位置补空串，不压缩数组。
        while (result.size < limit) result.add("")
        return result
    }

    private fun readUtf8(bytes: ByteArray, at: Int): String {
        val first = decodeLength8(bytes, at) ?: return ""
        val byteLength = decodeLength8(bytes, first.second) ?: return ""
        val start = byteLength.second
        val length = minOf(byteLength.first, bytes.size - start).coerceAtLeast(0)
        if (start < 0 || start + length > bytes.size) return ""
        return String(bytes, start, length, Charsets.UTF_8)
    }

    private fun readUtf16(bytes: ByteArray, at: Int, little: Boolean): String {
        val first = decodeLength16(bytes, at, little) ?: return ""
        val start = first.second
        val length = minOf(first.first, (bytes.size - start) / 2).coerceAtLeast(0)
        if (start < 0 || start + length * 2 > bytes.size) return ""
        val chars = CharArray(length)
        for (i in 0 until length) chars[i] = u16(bytes, start + i * 2, little).toChar()
        return String(chars).take(MAX_STRING_CHARS)
    }

    /** 返回 (字符串长度, 长度字段之后的偏移)；越界返回 null。 */
    private fun decodeLength8(bytes: ByteArray, at: Int): Pair<Int, Int>? {
        if (at >= bytes.size) return null
        val first = bytes[at].toInt() and 0xff
        return if (first and 0x80 != 0) {
            if (at + 1 >= bytes.size) null else Pair(((first and 0x7f) shl 8) or (bytes[at + 1].toInt() and 0xff), at + 2)
        } else {
            Pair(first, at + 1)
        }
    }

    private fun decodeLength16(bytes: ByteArray, at: Int, little: Boolean): Pair<Int, Int>? {
        if (at + 2 > bytes.size) return null
        val first = u16(bytes, at, little)
        return if (first and 0x8000 != 0) {
            if (at + 4 > bytes.size) null
            else Pair(((first and 0x7fff) shl 16) or u16(bytes, at + 2, little), at + 4)
        } else {
            Pair(first, at + 2)
        }
    }

    private fun readStartElement(bytes: ByteArray, pos: Int, headerSize: Int, chunkSize: Int,
                                 strings: List<String>, little: Boolean): Element? {
        if (headerSize < 16) return null
        val ext = pos + headerSize
        val chunkEnd = pos + chunkSize
        if (ext + 20 > chunkEnd || ext + 20 > bytes.size) return null
        val name = stringAt(strings, u32(bytes, ext + 4, little)) ?: return null
        val attributeStart = u16(bytes, ext + 8, little)
        val attributeSize = u16(bytes, ext + 10, little)
        val attributeCount = u16(bytes, ext + 12, little)
        if (attributeSize < 20) return null
        val base = ext + attributeStart
        val attributes = ArrayList<Pair<String, String?>>(minOf(attributeCount, 64))
        for (i in 0 until minOf(attributeCount, 1024)) {
            val at = base + i * attributeSize
            if (at + attributeSize > chunkEnd || at + 20 > bytes.size) break
            val attrName = stringAt(strings, u32(bytes, at + 4, little)) ?: continue
            attributes.add(attrName to attributeValue(bytes, at, strings, little))
        }
        return Element(name, attributes)
    }

    private fun attributeValue(bytes: ByteArray, at: Int, strings: List<String>, little: Boolean): String? {
        val raw = u32(bytes, at + 8, little)
        if (raw != NO_INDEX) {
            val value = stringAt(strings, raw)
            if (value != null) return value
        }
        val dataType = bytes[at + 15].toInt() and 0xff
        val data = u32(bytes, at + 16, little)
        return when (dataType) {
            0x03 -> stringAt(strings, data)
            0x10, 0x11 -> data.toString()
            0x12 -> (data != 0L).toString()
            else -> null
        }
    }

    private fun stringAt(strings: List<String>, index: Long): String? {
        if (index < 0 || index >= strings.size) return null
        return strings[index.toInt()]
    }

    private fun parseInt(value: String?): Int? {
        val text = value?.trim() ?: return null
        return when {
            text.matches(Regex("\\d+")) -> text.toIntOrNull()
            text.startsWith("0x", ignoreCase = true) -> text.substring(2).toIntOrNull(16)
            else -> null
        }
    }

    private fun u16(bytes: ByteArray, at: Int, little: Boolean): Int {
        if (at < 0 || at + 2 > bytes.size) return 0
        val a = bytes[at].toInt() and 0xff
        val b = bytes[at + 1].toInt() and 0xff
        return if (little) (b shl 8) or a else (a shl 8) or b
    }

    private fun u32(bytes: ByteArray, at: Int, little: Boolean): Long {
        if (at < 0 || at + 4 > bytes.size) return 0L
        var value = 0L
        if (little) {
            for (i in 3 downTo 0) value = (value shl 8) or (bytes[at + i].toLong() and 0xff)
        } else {
            for (i in 0 until 4) value = (value shl 8) or (bytes[at + i].toLong() and 0xff)
        }
        return value
    }
}
