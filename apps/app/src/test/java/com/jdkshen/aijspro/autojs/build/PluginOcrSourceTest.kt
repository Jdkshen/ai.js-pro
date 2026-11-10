package com.jdkshen.aijspro.autojs.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** task-131/132：打包期从插件 APK 取 OCR 载荷（用合成插件 APK 夹具，纯 JVM）。 */
class PluginOcrSourceTest {
    @get:Rule val temp = TemporaryFolder()

    private fun pluginApk(name: String, vararg entries: Pair<String, String>): File {
        val file = File(temp.newFolder(name), "plugin.apk")
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((entry, body) in entries) {
                zip.putNextEntry(ZipEntry(entry))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    private fun ocrPlugin() = pluginApk(
        "ocr",
        "lib/arm64-v8a/libppocr.so" to "ppocr-bytes",
        "lib/arm64-v8a/libmlkit_google_ocr_pipeline.so" to "mlkit-bytes",
        "assets/mlkit-google-ocr-models/rec.tflite" to "model-bytes",
        "assets/modules/__ocr__.js" to "// real module",
        "lib/arm64-v8a/libunrelated.so" to "nope",
        "classes.dex" to "dex"
    )

    @Test fun detectsOcrPayloadAndIgnoresUnrelatedEntries() {
        val apk = ocrPlugin()
        assertTrue(PluginOcrSource.hasOcrPayload(apk))
        val workspace = temp.newFolder("ws1")
        val result = PluginOcrSource.copyIntoWorkspace(apk, workspace)
        assertEquals("只拷 OCR 相关条目", 4, result.files)
        assertTrue(result.bytes > 0)
        assertTrue(File(workspace, "lib/arm64-v8a/libppocr.so").isFile)
        assertTrue(File(workspace, "assets/mlkit-google-ocr-models/rec.tflite").isFile)
        assertTrue(File(workspace, "assets/modules/__ocr__.js").isFile)
        assertFalse("无关 lib 不得进产物", File(workspace, "lib/arm64-v8a/libunrelated.so").exists())
        assertFalse("classes.dex 不得进产物", File(workspace, "classes.dex").exists())
        assertTrue(result.describe().contains("ocr from plugin: files=4"))
    }

    @Test fun emptyShellPluginReportsNoPayloadAndCopiesNothing() {
        val shell = pluginApk("shell", "classes.dex" to "dex", "lib/arm64-v8a/libother.so" to "x")
        assertFalse(PluginOcrSource.hasOcrPayload(shell))
        val workspace = temp.newFolder("ws2")
        val result = PluginOcrSource.copyIntoWorkspace(shell, workspace)
        assertEquals(0, result.files)
        assertEquals(0L, result.bytes)
        assertEquals(0, workspace.listFiles()?.size ?: 0)
    }

    @Test fun missingPluginIsHarmless() {
        assertFalse(PluginOcrSource.hasOcrPayload(null))
        assertFalse(PluginOcrSource.hasOcrPayload(File(temp.root, "nope.apk")))
        assertEquals(0, PluginOcrSource.copyIntoWorkspace(null, temp.newFolder("ws3")).files)
    }
}
