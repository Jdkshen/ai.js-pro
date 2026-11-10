package com.jdkshen.aijspro.autojs.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * task-131 跨文件钉子：插件载荷的模型文件名必须与 engine 期望的 PPOcr.MODEL_FILES 一致。
 *
 * 两端分别在 plugins/ocr-plugin 与 engine ⇒ 放这里最自然（两侧都能读，且不让 engine 单测依赖插件工程）。
 * 谁改模型名 / 漏拷 / 多拷，这条立刻红。
 *
 * 实现选择：解析 PPOcr.java 源码取期望名单，而不是直接引用 PPOcr.MODEL_FILES —— 这样这条钉子只读文件、
 * 不引入编译期依赖，不会再出现「离线绿 / 整包红」那类 classpath 事故；解析失败会明确报错（不静默）。
 */
class OcrPayloadModelNamesPinTest {
    private val engineSource = "../../modules/engine/src/main/java/com/stardust/autojs/runtime/api/PPOcr.java"
    private val payloadDir = "../../plugins/ocr-plugin/src/main/assets/ocr/models"
    private val lockPath = "../../plugins/ocr-plugin/payload-sources.json"

    private fun read(relative: String): String {
        val file = File(relative)
        assertTrue("找不到 " + file.absolutePath, file.isFile)
        return file.readText()
    }

    private fun expectedModelFiles(): List<String> {
        val source = read(engineSource).replace(Regex("//[^\\n]*"), "")
        val at = source.indexOf("MODEL_FILES")
        assertTrue("PPOcr.java 里找不到 MODEL_FILES（结构变了？）", at > 0)
        val open = source.indexOf('{', at)
        val close = source.indexOf('}', open)
        assertTrue("MODEL_FILES 不是数组字面量（结构变了？）", open > 0 && close > open)
        return Regex("\"([^\"]+)\"").findAll(source.substring(open, close))
            .map { it.groupValues[1] }.toList()
    }

    @Test fun payloadTopLevelNamesMatchEngineExpectation() {
        val expected = expectedModelFiles()
        assertTrue("PPOcr.MODEL_FILES 解析失败（请改用直接引用）：" + expected, expected.size >= 5)
        val dir = File(payloadDir)
        assertTrue("找不到插件载荷模型目录 " + dir.absolutePath, dir.isDirectory)
        val children = dir.listFiles()!!
        assertEquals("载荷模型目录不应有子目录（PPOcr 读 modelsDir 加名字，顶层直放）",
            emptyList<String>(), children.filter { it.isDirectory }.map { it.name })
        assertEquals("插件载荷顶层文件名必须与 PPOcr.MODEL_FILES 逐字一致",
            expected.sorted(), children.filter { it.isFile }.map { it.name }.sorted())
    }

    @Test fun lockfileListsEveryModelWithDigest() {
        val lock = read(lockPath)
        for (name in expectedModelFiles()) {
            assertTrue("锁文件缺模型条目 " + name, lock.contains("assets/ocr/models/" + name))
        }
        assertTrue("锁文件要记 sha256 与来源", lock.contains("\"sha256\"") && lock.contains("\"source\""))
    }

    @Test fun mlkitLibIsIntentionallyAbsent() {
        val libs = File("../../plugins/ocr-plugin/src/main/jniLibs/arm64-v8a")
        val names = if (libs.isDirectory) libs.listFiles()!!.map { it.name }.sorted() else emptyList()
        assertEquals("默认后端 ppocr：插件只带 libc++ 与 libppocr（mlkit 库按 Lead 裁决移除，重开条件见 sync 脚本头）",
            listOf("libc++_shared.so", "libppocr.so"), names)
    }
}
