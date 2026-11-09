package com.jdkshen.aijspro.autojs.build

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** task-74：模板 lib 补齐的 ABI 判定 + v7a 条目清单（单一事实来源）回归。 */
class TemplateLibCompletionTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun abiOfParsesOnlyWellFormedLibEntries() {
        assertEquals("arm64-v8a", TemplateLibCompletion.abiOf("lib/arm64-v8a/libquickjs.so"))
        assertEquals("armeabi-v7a", TemplateLibCompletion.abiOf("lib/armeabi-v7a/libopencv_java5.so"))
        assertNull(TemplateLibCompletion.abiOf("assets/foo.so"))
        assertNull(TemplateLibCompletion.abiOf("lib/arm64-v8a/readme.txt"))
        assertNull(TemplateLibCompletion.abiOf(null))
    }

    /** 核心回归：模板只带 arm64 时，**只**补 v7a 的 entry（arm64 现有条目一个都不动）。 */
    @Test fun arm64OnlyTemplateStillCompletesV7aEntries() {
        val workspace = temp.newFolder("ws")
        File(workspace, "lib/arm64-v8a").mkdirs()
        File(workspace, "lib/arm64-v8a/libquickjs.so").writeBytes(ByteArray(4))
        val entries = listOf(
            "lib/arm64-v8a/libquickjs.so",
            "lib/armeabi-v7a/libquickjs.so",
            "lib/armeabi-v7a/libopencv_java5.so")
        val present = TemplateLibCompletion.abisWithLibs(workspace)
        assertEquals(setOf("arm64-v8a"), present)
        assertEquals(listOf("lib/armeabi-v7a/libquickjs.so", "lib/armeabi-v7a/libopencv_java5.so"),
            TemplateLibCompletion.entriesForMissingAbis(entries, present))
    }

    @Test fun completeTemplateSkipsCompletionForAllListedAbis() {
        val workspace = temp.newFolder("ws2")
        listOf("arm64-v8a", "armeabi-v7a").forEach { abi ->
            File(workspace, "lib/" + abi).mkdirs()
            File(workspace, "lib/" + abi + "/libquickjs.so").writeBytes(ByteArray(4))
        }
        val entries = listOf("lib/arm64-v8a/libquickjs.so", "lib/armeabi-v7a/libquickjs.so")
        val present = TemplateLibCompletion.abisWithLibs(workspace)
        assertEquals(setOf("arm64-v8a", "armeabi-v7a"), present)
        assertTrue(TemplateLibCompletion.entriesForMissingAbis(entries, present).isEmpty())
        val skip = TemplateLibCompletion.describeSkip(entries, present)
        assertTrue(skip, skip.contains("arm64-v8a") && skip.contains("armeabi-v7a"))
    }

    /** 空目录不算「已有 lib」（避免 mkdir 过的空 lib/<abi>/ 让补齐被跳过）。 */
    @Test fun emptyAbiDirectoryDoesNotCountAsPresent() {
        val workspace = temp.newFolder("ws3")
        File(workspace, "lib/armeabi-v7a").mkdirs()
        assertTrue(TemplateLibCompletion.abisWithLibs(workspace).isEmpty())
    }

    /** 清单本身：加 v7a 组、**arm64 现有条目不动**、v7a 不带 ppocr（唯一硬缺口）。 */
    @Test fun assetListCarriesBothAbiGroupsAndKeepsArm64Entries() {
        val file = File("src/main/assets/excluded-zip-files.json")
        assertTrue("找不到 " + file.absolutePath, file.isFile)
        // gson 在 app 单测 classpath 上可能是 2.8.5（没有 JsonParser.parseString 静态方法）→ 用实例 API。
        val json = JsonParser().parse(file.readText()).asJsonObject
        val general = json.getAsJsonArray("general").map { it.asString }
        val opencv = json.getAsJsonArray("opencv").map { it.asString }
        listOf("lib/arm64-v8a/libaijscrypto.so", "lib/arm64-v8a/libc++_shared.so",
            "lib/arm64-v8a/libmlkit_google_ocr_pipeline.so", "lib/arm64-v8a/libppocr.so",
            "lib/arm64-v8a/libquickjs.so", "lib/arm64-v8a/libquickjs_jni.so").forEach {
            assertTrue("arm64 条目被改动了：" + it, general.contains(it))
        }
        listOf("lib/armeabi-v7a/libaijscrypto.so", "lib/armeabi-v7a/libc++_shared.so",
            "lib/armeabi-v7a/libmlkit_google_ocr_pipeline.so", "lib/armeabi-v7a/libquickjs.so",
            "lib/armeabi-v7a/libquickjs_jni.so").forEach {
            assertTrue("v7a 条目缺失：" + it, general.contains(it))
        }
        assertTrue("v7a 的 OpenCV 是单库：lib/armeabi-v7a/libopencv_java5.so", opencv.contains("lib/armeabi-v7a/libopencv_java5.so"))
        assertFalse("v7a 不该带 ppocr（唯一硬缺口）", general.any { it.contains("armeabi-v7a") && it.contains("ppocr") })
        assertEquals(setOf("arm64-v8a", "armeabi-v7a"), TemplateLibCompletion.abisOf(general + opencv))
    }
}
