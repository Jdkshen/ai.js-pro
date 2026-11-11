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

/**
 * task-131：打包期从插件 APK 取 OCR 载荷（合成夹具，纯 JVM）。
 *
 * 夹具按 **dev-1498 真机列出的真实插件布局**：
 *   lib/arm64-v8a/{libc++_shared.so, libppocr.so} + assets/ocr/models/（5 个）+ assets/scripts/index.js
 * 注意：真实插件**没有** assets/modules/__ocr__.js（那是宿主/模板的，插件按 SDK 契约只带 assets/scripts/index.js）
 * ⇒ hasOcrPayload 以前者为准（有库 + 有模型），不得再要求 __ocr__.js。
 */
class PluginOcrSourceTest {
    @get:Rule val temp = TemporaryFolder()

    private val modelNames = listOf(
        "PP_OCRv6_tiny_det.ncnn.bin",
        "PP_OCRv6_tiny_det.ncnn.param",
        "PP_OCRv6_tiny_rec.ncnn.bin",
        "PP_OCRv6_tiny_rec.ncnn.param",
        "ppocr_keys_v6_tiny.txt"
    )

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

    /** 真实布局：两个 so + 五个模型 + SDK 脚本入口 + 无关条目。 */
    private fun realOcrPlugin(): File {
        val entries = ArrayList<Pair<String, String>>()
        entries.add("lib/arm64-v8a/libc++_shared.so" to "cpp-bytes")
        entries.add("lib/arm64-v8a/libppocr.so" to "ppocr-bytes")
        for (model in modelNames) entries.add("assets/ocr/models/" + model to ("model:" + model))
        entries.add("assets/scripts/index.js" to "// sdk entry")
        entries.add("lib/arm64-v8a/libunrelated.so" to "nope")
        entries.add("classes.dex" to "dex")
        return pluginApk("ocr", *entries.toTypedArray())
    }

    @Test fun realLayoutYieldsTwoLibsAndFiveModels() {
        val apk = realOcrPlugin()
        assertTrue("有库 + 有模型 ⇒ 算带载荷", PluginOcrSource.hasOcrPayload(apk))
        val workspace = temp.newFolder("ws1")
        val result = PluginOcrSource.copyIntoWorkspace(apk, workspace)
        val libs = result.entries.count { it.startsWith("lib/") }
        val models = result.entries.size - libs
        assertEquals("dev-1498 验收口径：libs=2", 2, libs)
        assertEquals("dev-1498 验收口径：models=5", 5, models)
        assertEquals(7, result.files)
        assertTrue(File(workspace, "lib/arm64-v8a/libc++_shared.so").isFile)
        assertTrue(File(workspace, "lib/arm64-v8a/libppocr.so").isFile)
        for (model in modelNames) {
            assertTrue("模型必须落到 assets/ocr/models/" + model,
                File(workspace, "assets/ocr/models/" + model).isFile)
        }
        assertFalse("无关 lib 不得进产物", File(workspace, "lib/arm64-v8a/libunrelated.so").exists())
        assertFalse("classes.dex 不得进产物", File(workspace, "classes.dex").exists())
        assertTrue(result.describe().contains("files=7"))
    }

    /** 兼容旧插件布局（mlkit 前缀 + 自带 __ocr__.js）仍然可用。 */
    @Test fun legacyMlkitLayoutStillCopied() {
        val apk = pluginApk(
            "legacy",
            "lib/arm64-v8a/libmlkit_google_ocr_pipeline.so" to "mlkit",
            "assets/mlkit-google-ocr-models/rec.tflite" to "model",
            "assets/modules/__ocr__.js" to "// real module"
        )
        assertTrue(PluginOcrSource.hasOcrPayload(apk))
        val workspace = temp.newFolder("ws2")
        val result = PluginOcrSource.copyIntoWorkspace(apk, workspace)
        assertEquals(3, result.files)
        assertTrue(File(workspace, "assets/mlkit-google-ocr-models/rec.tflite").isFile)
        assertTrue(File(workspace, "assets/modules/__ocr__.js").isFile)
    }

    /** 只有库或只有模型都不算「带 OCR 载荷」（避免空壳被当成可用）。 */
    @Test fun payloadRequiresBothLibAndModels() {
        val libsOnly = pluginApk("libsonly", "lib/arm64-v8a/libppocr.so" to "x")
        assertFalse("只有库不算", PluginOcrSource.hasOcrPayload(libsOnly))
        val modelsOnly = pluginApk("modelsonly", "assets/ocr/models/" + modelNames[0] to "x")
        assertFalse("只有模型不算", PluginOcrSource.hasOcrPayload(modelsOnly))
        val shell = pluginApk("shell", "classes.dex" to "dex", "lib/arm64-v8a/libother.so" to "x")
        assertFalse(PluginOcrSource.hasOcrPayload(shell))
        val workspace = temp.newFolder("ws3")
        assertEquals(0, PluginOcrSource.copyIntoWorkspace(shell, workspace).files)
        assertEquals(0, workspace.listFiles()?.size ?: 0)
    }

    @Test fun missingPluginIsHarmless() {
        assertFalse(PluginOcrSource.hasOcrPayload(null))
        assertFalse(PluginOcrSource.hasOcrPayload(File(temp.root, "nope.apk")))
        assertEquals(0, PluginOcrSource.copyIntoWorkspace(null, temp.newFolder("ws4")).files)
    }

    /**
     * 源码钉（dev-1498 真机根因）：插件取回必须在 enforcePackagingPolicy() **之后** —— enforce 会剪掉
     * removeOcr=true 时「无法解释」的 OCR 库，放前面就白拷；且默认（removeOcr=false）必须早退。
     */
    @Test fun builderCopiesFromPluginAfterEnforceAndOnlyWhenOcrRemoved() {
        val builder = File("src/main/java/com/jdkshen/aijspro/autojs/build/ApkBuilder.java").readText()
        val enforceAt = builder.indexOf("enforcePackagingPolicy();")
        val callAt = builder.indexOf("copyOcrFromPluginIfNeeded();")
        assertTrue("两处都要在", enforceAt > 0 && callAt > 0)
        assertTrue("插件取回必须在 enforcePackagingPolicy 之后", callAt > enforceAt)
        assertTrue("默认必须早退（removeOcr=false 不进这条路）", builder.contains("mAppConfig.removeOcr"))
        assertTrue("日志要能看出时机", builder.contains("after=enforcePackagingPolicy"))
    }
}