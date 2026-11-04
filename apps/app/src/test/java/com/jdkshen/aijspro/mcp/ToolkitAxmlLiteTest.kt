package com.jdkshen.aijspro.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * §4.1 回归：ARSCLib 完整解码失败时，独立 AXML 解析必须能取回 package/version/sdk 等基础字段，
 * 且不把「解码器失败」当成「目标清单损坏」。测试用手写二进制 AXML 构造，不依赖设备或外部 APK。
 */
class ToolkitAxmlLiteTest {

    @Test
    fun parsesBaseManifestFieldsFromBinaryAxml() {
        val xml = buildManifest(utf8 = true)
        assertTrue(ToolkitAxmlLite.looksBinaryXml(xml))
        val facts = ToolkitAxmlLite.parseManifest(xml)
        assertEquals("com.demo.app", facts?.packageName)
        assertEquals("config.arm64_v8a", facts?.split)
        assertEquals(7, facts?.versionCode)
        assertEquals("1.2.3", facts?.versionName)
        assertEquals("35", facts?.compileSdkVersion)
        assertEquals(24, facts?.minSdk)
        assertEquals(34, facts?.targetSdk)
        assertEquals(true, facts?.extractNativeLibs)
        assertTrue((facts?.elementCount ?: 0) >= 3)
    }

    @Test
    fun utf16StringPoolIsSupported() {
        val facts = ToolkitAxmlLite.parseManifest(buildManifest(utf8 = false))
        assertEquals("com.demo.app", facts?.packageName)
        assertEquals(24, facts?.minSdk)
    }

    @Test
    fun rejectsNonAxmlAndTruncatedInputWithoutThrowing() {
        assertFalse(ToolkitAxmlLite.looksBinaryXml("not an apk".toByteArray()))
        assertNull(ToolkitAxmlLite.parseManifest("not an apk".toByteArray()))
        assertNull(ToolkitAxmlLite.parseManifest(ByteArray(0)))
        val xml = buildManifest(utf8 = true)
        assertNull(ToolkitAxmlLite.parseManifest(xml.copyOfRange(0, 12)))
        // 头部声明合法但没有 manifest 元素时也必须返回 null，而不是返回“空 manifest”。
        assertNull(ToolkitAxmlLite.parseManifest(header(emptyChunks())))
    }

    // ------------------------------------------------------------------ fixtures

    private fun buildManifest(utf8: Boolean): ByteArray {
        val strings = listOf(
            "manifest", "package", "versionCode", "versionName", "compileSdkVersion", "split",
            "uses-sdk", "minSdkVersion", "targetSdkVersion", "application", "extractNativeLibs",
            "com.demo.app", "1.2.3", "35", "config.arm64_v8a"
        )
        val index = strings.withIndex().associate { it.value to it.index }
        val chunks = ByteArrayOutputStream()
        chunks.write(stringPool(strings, utf8))
        chunks.write(startElement(index.getValue("manifest"), listOf(
            attr(index.getValue("package"), raw = index.getValue("com.demo.app")),
            attr(index.getValue("versionCode"), dataType = 0x10, data = 7L),
            attr(index.getValue("versionName"), raw = index.getValue("1.2.3")),
            attr(index.getValue("compileSdkVersion"), raw = index.getValue("35")),
            attr(index.getValue("split"), raw = index.getValue("config.arm64_v8a"))
        )))
        chunks.write(startElement(index.getValue("uses-sdk"), listOf(
            attr(index.getValue("minSdkVersion"), dataType = 0x10, data = 24L),
            attr(index.getValue("targetSdkVersion"), dataType = 0x10, data = 34L)
        )))
        chunks.write(startElement(index.getValue("application"), listOf(
            attr(index.getValue("extractNativeLibs"), dataType = 0x12, data = 1L)
        )))
        return header(chunks.toByteArray())
    }

    private fun header(chunks: ByteArray): ByteArray {
        val out = Writer()
        out.u16(0x0003)
        out.u16(8)
        out.u32((8 + chunks.size).toLong())
        out.bytes(chunks)
        return out.toByteArray()
    }

    private fun emptyChunks(): ByteArray = ByteArray(0)

    private class Attr(val name: Int, val raw: Int, val dataType: Int, val data: Long)

    private fun attr(name: Int, raw: Int = -1, dataType: Int = 0x03, data: Long = 0L) =
        Attr(name, raw, dataType, data)

    private fun startElement(nameIndex: Int, attributes: List<Attr>): ByteArray {
        val out = Writer()
        val chunkSize = 16 + 20 + attributes.size * 20
        out.u16(0x0102)
        out.u16(16)
        out.u32(chunkSize.toLong())
        out.u32(1L)
        out.u32(0xFFFFFFFFL)
        out.u32(0xFFFFFFFFL)
        out.u32(nameIndex.toLong())
        out.u16(20)
        out.u16(20)
        out.u16(attributes.size)
        out.u16(0)
        out.u16(0)
        out.u16(0)
        attributes.forEach { attribute ->
            out.u32(0xFFFFFFFFL)
            out.u32(attribute.name.toLong())
            out.u32(if (attribute.raw < 0) 0xFFFFFFFFL else attribute.raw.toLong())
            out.u16(8)
            out.u8(0)
            out.u8(attribute.dataType)
            out.u32(attribute.data)
        }
        return out.toByteArray()
    }

    private fun stringPool(strings: List<String>, utf8: Boolean): ByteArray {
        val data = Writer()
        val offsets = ArrayList<Int>()
        strings.forEach { value ->
            offsets.add(data.size())
            if (utf8) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                data.u8(value.length)
                data.u8(bytes.size)
                data.bytes(bytes)
                data.u8(0)
            } else {
                data.u16(value.length)
                value.forEach { data.u16(it.code) }
                data.u16(0)
            }
        }
        val offsetsSize = strings.size * 4
        val headerSize = 28
        var chunkSize = headerSize + offsetsSize + data.size()
        while (chunkSize % 4 != 0) chunkSize++
        val out = Writer()
        out.u16(0x0001)
        out.u16(headerSize)
        out.u32(chunkSize.toLong())
        out.u32(strings.size.toLong())
        out.u32(0L)
        out.u32(if (utf8) 0x100L else 0L)
        out.u32((headerSize + offsetsSize).toLong())
        out.u32(0L)
        offsets.forEach { out.u32(it.toLong()) }
        out.bytes(data.toByteArray())
        while (out.size() < chunkSize) out.u8(0)
        return out.toByteArray()
    }

    private class Writer {
        private val out = ByteArrayOutputStream()

        fun size(): Int = out.size()

        fun u8(value: Int) { out.write(value and 0xff) }

        fun u16(value: Int) { u8(value); u8(value ushr 8) }

        fun u32(value: Long) {
            u16((value and 0xffff).toInt())
            u16(((value ushr 16) and 0xffff).toInt())
        }

        fun bytes(value: ByteArray) { out.write(value) }

        fun toByteArray(): ByteArray = out.toByteArray()
    }
}
