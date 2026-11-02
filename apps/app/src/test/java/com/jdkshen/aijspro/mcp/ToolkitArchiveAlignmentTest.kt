package com.jdkshen.aijspro.mcp

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.*

class ToolkitArchiveAlignmentTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun storedNativeAndResourcePayloadsAreAlignedWhileDeflateIsPreserved() {
        val archive = temp.newFile("alignment.apk")
        val payload = byteArrayOf(1, 2, 3, 4)
        ZipOutputStream(archive.outputStream()).use { out ->
            for (name in listOf("lib/arm64-v8a/stored.so", "resources.arsc", "lib/arm64-v8a/compressed.so")) {
                out.putNextEntry(ZipEntry(name).apply {
                    time = 1700000000000L
                    if(!name.endsWith("compressed.so")) {
                        method = ZipEntry.STORED; size = payload.size.toLong(); compressedSize = size
                        crc = CRC32().apply { update(payload) }.value
                    }
                })
                out.write(payload); out.closeEntry()
            }
        }
        ToolkitArchiveAlignment.align(archive)
        ZipFile(archive).use { zip ->
            assertEquals(ZipEntry.DEFLATED, zip.getEntry("lib/arm64-v8a/compressed.so").method)
            assertArrayEquals(payload, zip.getInputStream(zip.getEntry("resources.arsc")).readBytes())
        }
        val bytes = archive.readBytes(); val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var p = 0; var checked = 0
        while(p + 30 <= bytes.size && b.getInt(p) == 0x04034b50) {
            val nameSize = b.getShort(p + 26).toInt() and 65535
            val extraSize = b.getShort(p + 28).toInt() and 65535
            val name = String(bytes, p + 30, nameSize, Charsets.UTF_8)
            val start = p + 30 + nameSize + extraSize
            if(b.getShort(p + 8).toInt() != ZipEntry.STORED) break
            assertEquals(name, 0, start % if(name.endsWith(".so")) 16384 else 4)
            checked++
            p = start + b.getInt(p + 18)
        }
        assertEquals(2, checked)
    }
}
