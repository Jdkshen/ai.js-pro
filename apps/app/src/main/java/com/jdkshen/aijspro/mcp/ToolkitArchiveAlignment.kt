package com.jdkshen.aijspro.mcp

import java.io.*
import java.util.zip.*

/** ZIP alignment only; ELF LOAD alignment still belongs to the input native libraries. */
internal object ToolkitArchiveAlignment {
    @JvmStatic fun align(file: File) {
        ToolkitIo.atomic(file) { target ->
            ZipFile(file).use { source ->
                val counter = Counter(target.outputStream())
                ZipOutputStream(counter).use { zip ->
                    source.entries().asSequence().forEach { original ->
                        val entry = ZipEntry(original.name); val alignment = when { original.name.endsWith(".so") -> 16384; original.name == "resources.arsc" -> 4; else -> 1 }
                        if (!original.isDirectory && alignment > 1) {
                            entry.method = ZipEntry.STORED; entry.size = original.size; entry.compressedSize = original.size; entry.crc = original.crc
                            val headerEnd = counter.count + 30 + original.name.toByteArray(Charsets.UTF_8).size
                            if (headerEnd % alignment != 0L) {
                                val padding = ((alignment - ((headerEnd + 4) % alignment)) % alignment).toInt()
                                entry.extra = ByteArray(padding + 4).apply { this[0] = 0x35; this[1] = 0xd9.toByte(); this[2] = padding.toByte(); this[3] = (padding ushr 8).toByte() }
                            }
                        }
                        zip.putNextEntry(entry)
                        if (!original.isDirectory) source.getInputStream(original).use { input ->
                            val buffer = ByteArray(65536); var count = 0L
                            while(true) { val read = input.read(buffer); if(read < 0) break; count += read; require(count <= ToolkitIo.MAX_APK) { "ZIP 条目过大" }; zip.write(buffer,0,read) }
                        }; zip.closeEntry()
                    }
                }
            }
        }
    }
    private class Counter(output: OutputStream) : FilterOutputStream(output) {
        var count = 0L
        override fun write(value: Int) { out.write(value); count++ }
        override fun write(bytes: ByteArray, offset: Int, length: Int) { out.write(bytes,offset,length); count += length }
    }
}
