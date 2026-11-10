package com.jdkshen.aijspro.autojs.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** task-104 ②③：OCR 资产自动发现 + 打包报告字段名钉子。 */
class OcrAssetDiscoveryTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun reportFieldNamesAreStableAndUnique() {
        assertEquals(
            listOf("ocrRemovedFiles", "ocrRemovedBytes", "ocrRemovedPrefix", "node24SkippedEntries", "node24SkippedBytes"),
            PackagingReportFields.all()
        )
        assertEquals(PackagingReportFields.all().size, PackagingReportFields.all().toSet().size)
    }

    @Test fun discoveryMatchesRenamedTemplateDirsAndTheRhinoModule() {
        val assets = temp.newFolder("assets")
        // 模板改名的三种形态都应命中
        File(assets, "mlkit-google-ocr-models").mkdirs()
        File(assets, "mlkit-ocr").mkdirs()
        File(assets, "vendor-ocr-models-2026").mkdirs()
        // 不该命中
        File(assets, "node24").mkdirs()
        File(assets, "source-engine-upgrade").mkdirs()
        File(assets, "modules").mkdirs()
        File(assets, "modules/__ocr__.js").writeText("// rhino ocr")

        val found = OcrAssetDiscovery.find(assets)
        assertEquals(
            listOf("__ocr__.js", "mlkit-google-ocr-models", "mlkit-ocr", "vendor-ocr-models-2026"),
            OcrAssetDiscovery.describe(found).split(",").sorted()
        )
        assertTrue("mlkit-google-ocr-models 必须在命中里", found.any { it.name == "mlkit-google-ocr-models" })
        assertFalse("node24 不能被当 OCR 资产", found.any { it.name == "node24" })
        assertFalse("source-engine-upgrade 不能被当 OCR 资产", found.any { it.name == "source-engine-upgrade" })
    }

    @Test fun discoveryNeverTouchesSharedPluginFacades() {
        val assets = temp.newFolder("assets2")
        File(assets, "modules").mkdirs()
        File(assets, "modules/qjs-plugins.js").writeText("// quickjs plugin facade")
        File(assets, "modules/__plugins__.js").writeText("// rhino plugin facade")
        File(assets, "modules/__ocr__.js").writeText("// rhino ocr")
        val found = OcrAssetDiscovery.find(assets)
        assertEquals(listOf("__ocr__.js"), found.map { it.name })
        // 源码级钉：ApkBuilder 不得删共享插件门面（task-104 ① 的结论）
        val builder = File("src/main/java/com/jdkshen/aijspro/autojs/build/ApkBuilder.java").readText()
        assertFalse("不能剪 qjs-plugins.js（共享插件门面）", builder.contains("modules/qjs-plugins.js"))
        assertFalse("不能剪 __plugins__.js（共享插件门面）", builder.contains("modules/__plugins__.js"))
        assertTrue("命中为空时要回显警告", builder.contains("没找到任何 OCR 资产"))
    }

    @Test fun emptyProjectReportsEmptyPrefix() {
        val assets = temp.newFolder("assets3")
        assertTrue(OcrAssetDiscovery.find(assets).isEmpty())
        assertEquals("", OcrAssetDiscovery.describe(emptyList()))
        assertEquals("", OcrAssetDiscovery.describe(null))
    }
}
