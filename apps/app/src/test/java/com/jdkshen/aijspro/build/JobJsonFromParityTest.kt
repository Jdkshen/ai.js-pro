package com.jdkshen.aijspro.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * task-121 通用契约钉：**json() 会写的键，from() 必须能还原**（集合差 = 0）。
 *
 * 背景：task-112（warnings）与 task-121（templateSource/templateLabel/startedAt）是同一个坑 ——
 * 写出侧加了字段、读取侧（Job.from，文件路径的唯一入口）没还原；任务结束后页面轮询走文件路径 ⇒ 恒读回空。
 */
class JobJsonFromParityTest {
    private val pocPath = "src/flutterpoc/java/com/jdkshen/aijspro/flutterpoc/PocPackaging.java"

    private fun source(): String {
        val file = File(pocPath)
        assertTrue("找不到 " + file.absolutePath, file.isFile)
        return file.readText()
    }

    /** 从 json() 体里抽出所有 put 的键名。 */
    private fun writtenKeys(): Set<String> {
        val text = source()
        val body = text.substringAfter("JSONObject json() throws Exception {").substringBefore("void persist(android.content.Context context)")
        return Regex("\\.put\\(\"([A-Za-z]+)\"").findAll(body).map { it.groupValues[1] }.toSet()
    }

    private fun fromBody(): String {
        val text = source()
        return text.substringAfter("static Job from(JSONObject json)").substringBefore("void apis(JSONArray value)")
    }

    @Test fun everyWrittenKeyIsRestorable() {
        // templateLabel 是**派生**字段（由 templateSource 计算），不需要单独还原 —— 显式登记，不隐式放过
        val derived = setOf("templateLabel")
        val required = writtenKeys() - derived
        assertTrue("json() 写出的键至少要有十来个，抽键失败？" + writtenKeys(), required.size >= 10)
        val missing = required.filterNot { fromBody().contains("\"" + it + "\"") }.sorted()
        assertEquals("from() 没还原的键（集合差必须为 0）：" + missing, emptyList<String>(), missing)
    }

    @Test fun templateSourceIsRestoredVerbatimAndEmptyStaysEmpty() {
        val body = fromBody()
        assertTrue("必须还原 templateSource：" + body.take(200), body.contains("templateSource"))
        assertTrue("不许给默认值（空串保持空串）", body.contains("json.optString(\"templateSource\", \"\")"))
        assertTrue("startedAt 也要按落盘值还原", body.contains("json.optLong(\"startedAt\""))
        // 派生标签只认打包侧常量；认不出就返回空串（页面不显示）
        val text = source()
        assertTrue(text.contains("String templateLabel()"))
        assertTrue(text.contains("return \"\";"))
    }

    @Test fun writtenKeysMatchTheDeviceReportedOnes() {
        // 真机 dev-1470 现场：这两个键在 json() 里有、但读回是空 —— 现在必须在必还原集合里
        val required = writtenKeys() - setOf("templateLabel")
        assertTrue("templateSource 属于必还原键", required.contains("templateSource"))
        assertTrue("startedAt 属于必还原键", required.contains("startedAt"))
        assertTrue("warnings 属于必还原键（task-112）", required.contains("warnings"))
    }
}
