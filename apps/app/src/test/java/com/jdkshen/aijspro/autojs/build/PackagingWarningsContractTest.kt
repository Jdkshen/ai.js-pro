package com.jdkshen.aijspro.autojs.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * task-111 钉子：打包提示的「读取时机」与「键名同源」。
 *
 * 真机 bug（K40 dev-1467）：projectBuildStatus 返回 status=success 但 warnings=[] —— 因为
 * PocPackaging 先 job.success()（把 status=success 暴露出去）再读 warnings，轮询正好落在窗口里。
 */
class PackagingWarningsContractTest {
    private fun read(relative: String): String {
        val file = File(relative)
        assertTrue("找不到 " + file.absolutePath, file.isFile)
        return file.readText()
    }

    @Test fun warningsAreCollectedBeforeStatusTurnsSuccess() {
        val poc = read("src/flutterpoc/java/com/jdkshen/aijspro/flutterpoc/PocPackaging.java")
        val warningsAt = poc.indexOf("job.warnings(warningList)")
        val successAt = poc.indexOf("job.success(outApk, sha256")
        assertTrue("必须能找到 warnings 收集点", warningsAt > 0)
        assertTrue("必须能找到 job.success", successAt > 0)
        assertTrue(
            "task-111：必须先收 warnings 再置 success（否则轮询会读到 success+空 warnings 的窗口）",
            warningsAt < successAt
        )
        // 双保险：报告里没有该键时要回退到实时列表
        assertTrue("必须有 getPackagingWarnings() 回退", poc.contains("builder.getPackagingWarnings()"))
    }

    @Test fun reportResyncsLiveWarningsOnEveryRead() {
        val builder = read("src/main/java/com/jdkshen/aijspro/autojs/build/ApkBuilder.java")
        val getter = builder.substringAfter("public JSONObject getPackagingReport()").substringBefore("private void report(")
        assertTrue("getPackagingReport() 每次读取都要同步实时 warnings：" + getter.take(200),
            getter.contains("PackagingReportFields.PACKAGING_WARNINGS") && getter.contains("mPackagingWarnings"))
        // 键名字面量只允许出现在 PackagingReportFields
        assertEquals("ApkBuilder 里不得再写死 packagingWarnings 字面量",
            0, Regex("\"packagingWarnings\"").findAll(builder).count())
    }

    /**
     * task-112 真根因：任务完成后 ACTIVE 被清 ⇒ 页面轮询（只传 path）走**文件**路径
     * （readJob/latestJob → Job.from），而 Job.from 原来不还原 warnings ⇒ 恒返回 []。
     * 这里钉住：两条读路径共用的 Job.from 必须还原 warnings，且 json() 必须写出。
     */
    @Test fun persistedJobRestoresWarningsSoBothReadPathsCarryThem() {
        val poc = read("src/flutterpoc/java/com/jdkshen/aijspro/flutterpoc/PocPackaging.java")
        val fromBody = poc.substringAfter("static Job from(JSONObject json)").substringBefore("void apis(JSONArray value)")
        assertTrue("Job.from 必须用 optJSONArray 还原 warnings（文件路径唯一入口）：" + fromBody.take(200),
            fromBody.contains("optJSONArray") && fromBody.contains("warnings") && fromBody.contains("job.warnings.add"))
        assertTrue("json() 必须写出 warnings", poc.contains("new JSONArray(warnings)"))
        // 两条读路径仍在：内存（JOBS.get）与文件（readJob/latestJob）
        assertTrue(poc.contains("Job persisted = readJob("))
        assertTrue(poc.contains("Job latest = latestJob("))
        // 轮询不带 id 时会先看 ACTIVE，清空后落到文件路径 —— 这条依赖仍在
        assertTrue(poc.contains("ACTIVE.compareAndSet(job.id, null)"))
    }

    @Test fun warningFieldNameIsSingleSourced() {
        assertEquals("packagingWarnings", PackagingReportFields.PACKAGING_WARNINGS)
        // 与「瘦身计数」是两类字段：all() 仍只列那 5 个（避免既有断言被改口径）
        assertEquals(5, PackagingReportFields.all().size)
    }
}
