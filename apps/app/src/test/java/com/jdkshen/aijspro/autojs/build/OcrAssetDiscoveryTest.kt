package com.jdkshen.aijspro.autojs.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** task-104 ②③ / task-106 隐患 A：OCR 资产自动发现（必须与「模板/宿主提供的条目」求交）。 */
class OcrAssetDiscoveryTest {
    @get:Rule val temp = TemporaryFolder()

    private val templateProvided = listOf(
        "assets/mlkit-google-ocr-models/rec.tflite",
        "assets/modules/__ocr__.js",
        "assets/node24/prelude.js"
    )

    @Test fun reportFieldNamesAreStableAndUnique() {
        assertEquals(
            listOf("ocrRemovedFiles", "ocrRemovedBytes", "ocrRemovedPrefix", "node24SkippedEntries", "node24SkippedBytes"),
            PackagingReportFields.all()
        )
        assertEquals(PackagingReportFields.all().size, PackagingReportFields.all().toSet().size)
    }

    @Test fun deletionTargetsMustBeProvidedByTemplate() {
        val assets = temp.newFolder("assets")
        File(assets, "mlkit-google-ocr-models").mkdirs()
        File(assets, "modules").mkdirs()
        File(assets, "modules/__ocr__.js").writeText("// rhino ocr")

        val found = OcrAssetDiscovery.find(assets, templateProvided)
        assertEquals(listOf("__ocr__.js", "mlkit-google-ocr-models"), OcrAssetDiscovery.describe(found).split(",").sorted())
    }

    /** task-106 反例：名字命中判据、但模板并不提供 ⇒ 属于用户内容，**不得**被删。 */
    @Test fun userTopLevelDirectoryThatMerelyMatchesTheNameIsNeverDeleted() {
        val assets = temp.newFolder("assets2")
        File(assets, "my-ocr-models").mkdirs()          // 用户顶层目录，名字命中
        File(assets, "mlkit-ocr").mkdirs()              // 也是命中名，但模板没提供
        File(assets, "modules").mkdirs()
        File(assets, "modules/__ocr__.js").writeText("// rhino ocr")

        // 候选（仅按名字）会把它们都列出来
        assertEquals(
            listOf("__ocr__.js", "mlkit-ocr", "my-ocr-models"),
            OcrAssetDiscovery.describe(OcrAssetDiscovery.candidates(assets)).split(",").sorted()
        )
        // 但真正可删的只有模板提供的那一个
        val deletable = OcrAssetDiscovery.find(assets, templateProvided)
        assertEquals(listOf("__ocr__.js"), deletable.map { it.name })
        assertFalse("用户目录不得进入删除清单", deletable.any { it.name == "my-ocr-models" })
        assertFalse("模板未提供的目录不得进入删除清单", deletable.any { it.name == "mlkit-ocr" })
    }

    @Test fun emptyProvidedListIsFailSafe() {
        val assets = temp.newFolder("assets3")
        File(assets, "mlkit-google-ocr-models").mkdirs()
        assertTrue("拿不到模板/宿主清单时必须一个都不删", OcrAssetDiscovery.find(assets, emptyList()).isEmpty())
        assertTrue(OcrAssetDiscovery.find(assets, null).isEmpty())
        assertEquals("", OcrAssetDiscovery.describe(emptyList()))
    }

    @Test fun discoveryNeverTouchesSharedPluginFacades() {
        val assets = temp.newFolder("assets4")
        File(assets, "modules").mkdirs()
        File(assets, "modules/qjs-plugins.js").writeText("// quickjs plugin facade")
        File(assets, "modules/__plugins__.js").writeText("// rhino plugin facade")
        File(assets, "modules/__ocr__.js").writeText("// rhino ocr")
        val found = OcrAssetDiscovery.find(assets, listOf(
            "assets/modules/__ocr__.js", "assets/modules/qjs-plugins.js", "assets/modules/__plugins__.js"
        ))
        assertEquals(listOf("__ocr__.js"), found.map { it.name })
        val builder = File("src/main/java/com/jdkshen/aijspro/autojs/build/ApkBuilder.java").readText()
        assertFalse("不能剪 qjs-plugins.js（共享插件门面）", builder.contains("modules/qjs-plugins.js"))
        assertFalse("不能剪 __plugins__.js（共享插件门面）", builder.contains("modules/__plugins__.js"))
        assertTrue("命中为空时要回显警告", builder.contains("没找到任何 OCR 资产") || builder.contains("保守不删"))
    }
}
