package com.jdkshen.aijspro.mcp

import java.io.*
import java.util.zip.*

/**
 * Zipalign-compatible ZIP alignment only; ELF LOAD alignment still belongs to the input native libraries.
 *
 * Every non-directory STORED entry is aligned to 4 bytes. STORED native libraries (`.so`) use 16 KiB.
 * DEFLATE entries keep their compression method. [forceStored] only preserves the legacy target set
 * (native libraries and resources.arsc); it does not recompress arbitrary assets. Padding a compressed
 * entry would not make its data directly loadable, so a compressed `.so` stays DEFLATE unless forced.
 */
internal object ToolkitArchiveAlignment {
    @JvmStatic @JvmOverloads fun align(file: File, forceStored: Boolean = false) {
        ToolkitIo.atomic(file) { target ->
            ZipFile(file).use { source ->
                val counter = Counter(target.outputStream())
                ZipOutputStream(counter).use { zip ->
                    source.entries().asSequence().forEach { original ->
                        val entry = ZipEntry(original.name)
                        if(original.time >= 0) entry.time = original.time
                        val directory = original.isDirectory
                        // Zip64 extra fields change the local header length after our padding calculation.
                        // APK entries are far below this limit; reject explicitly instead of misaligning.
                        if (!directory && (original.size < 0L || original.compressedSize < 0L ||
                                original.size >= 0xffffffffL || original.compressedSize >= 0xffffffffL)) {
                            throw IllegalArgumentException("不支持 ZIP64 或大小未知的条目：${original.name}")
                        }
                        val native = !directory && original.name.endsWith(".so", ignoreCase = true)
                        val required = if(directory) 1 else if(native) 16384 else 4
                        // Preserve the legacy forceStored target set: STORED native libraries and
                        // resources.arsc. Other DEFLATE entries stay DEFLATE (and are not padded).
                        val forceable = native || (!directory && original.name == "resources.arsc")
                        val stored = if(directory) original.method == ZipEntry.STORED
                            else original.method == ZipEntry.STORED || (forceStored && forceable)
                        val alignment = if(stored && !directory) required else 1
                        entry.method = if(stored) ZipEntry.STORED else ZipEntry.DEFLATED
                        if(stored) {
                            entry.method = ZipEntry.STORED; entry.size = original.size; entry.compressedSize = original.size; entry.crc = original.crc
                        }
                        if (alignment > 1) {
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
