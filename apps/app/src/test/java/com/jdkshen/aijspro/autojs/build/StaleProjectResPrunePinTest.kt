package com.jdkshen.aijspro.autojs.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** task-131 批次 A 钉子：打包期清理 stale 样例资源包（复用 W1 文案、只在确定不匹配时删）。 */
class StaleProjectResPrunePinTest {
    private val builderPath = "src/main/java/com/jdkshen/aijspro/autojs/build/ApkBuilder.java"

    private fun read(relative: String): String {
        val file = File(relative)
        assertTrue("找不到 " + file.absolutePath, file.isFile)
        return file.readText()
    }

    @Test fun builderPrunesOnlyOnDefiniteMismatchAndReusesTheSharedMessage() {
        val source = read(builderPath)
        assertTrue("必须走纯判定策略", source.contains("ProjectResSidecarPolicy.decide("))
        assertTrue("必须复用 W1 的文案常量（不抄第二份）",
            source.contains("CompiledProjectResources.staleResourcePackMessage("))
        assertTrue("删之前必须判 DROP", source.contains("ProjectResSidecarPolicy.Action.DROP"))
        assertTrue("必须打 C-PKG 日志（不静默）", source.contains("Log.w(\"C-PKG\", \"stale project-res dropped:"))
        assertTrue("必须进打包提示", source.contains("mPackagingWarnings.add(warning)"))
        assertTrue("删的是一对（apk + sidecar）", source.contains("apk.delete()") && source.contains("sidecar.delete()"))
        // 判不出来就 KEEP：实现里只处理 DROP 分支，不允许出现无条件的 delete
        val body = source.substringAfter("private void pruneStaleProjectResPacks()").substringBefore("private static void collectNamedFiles")
        // 结构断言（不做下标算术）：必须有「不是 DROP 就 continue」的守卫，删除只出现在守卫之后
        assertTrue("必须有 DROP 守卫 + early-continue：" + body.take(160),
            body.contains("if (decision.getAction() != ProjectResSidecarPolicy.Action.DROP)") && body.contains("continue;"))
        assertTrue("删除动作必须存在", body.contains("apk.delete()") && body.contains("sidecar.delete()"))
        assertEquals("不得出现第二份文案", 0, Regex("资源包与宿主资源表不匹配").findAll(source).count())
    }

    @Test fun pruneRunsAfterArscRewrite() {
        val source = read(builderPath)
        val arscAt = source.indexOf("buildArsc();")
        val pruneAt = source.indexOf("pruneStaleProjectResPacks();")
        assertTrue("两处都必须存在", arscAt > 0 && pruneAt > 0)
        assertTrue("清理必须在 buildArsc() 之后（否则拿旧摘要误删）", pruneAt > arscAt)
        val reportAt = source.indexOf("report(PackagingReportFields.PACKAGING_WARNINGS")
        assertTrue("warning 必须在写报告之前加进去", reportAt > pruneAt)
    }
}
