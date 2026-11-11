package com.jdkshen.aijspro.flutterpoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 源码复查 ②③ 的钉子：安装状态「能写能读」+ 下载件「只清不再需要的」。纯 JVM（不依赖 Android）。 */
class PluginInstallStateFilesTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun stateRoundTripKeepsFields() {
        val dir = temp.newFolder("states")
        val file = PluginInstallStateFiles.stateFileIn(dir, "ocr")
        val text = "{\"state\":\"failed\",\"reasonCode\":\"permission\",\"receivedBytes\":123}"
        assertTrue("写入应成功", PluginInstallStateFiles.writeText(file, text))
        assertEquals("读回必须逐字一致（原来 readPersisted 恒 null ⇒ 重启后复盘不了）",
            text, PluginInstallStateFiles.readText(file))
    }

    @Test fun missingOrEmptyStateReadsNull() {
        val dir = temp.newFolder("states2")
        assertNull("不存在 ⇒ null", PluginInstallStateFiles.readText(PluginInstallStateFiles.stateFileIn(dir, "nope")))
        val blank = PluginInstallStateFiles.stateFileIn(dir, "blank")
        PluginInstallStateFiles.writeText(blank, "   ")
        assertNull("空白 ⇒ null", PluginInstallStateFiles.readText(blank))
    }

    @Test fun pruneKeepsCurrentVersionsAndTouchesNothingElse() {
        val dir = temp.newFolder("downloads")
        File(dir, "ocr-plugin.apk").writeText("keep")
        File(dir, "xiangqi-plugin.apk").writeText("stale")
        File(dir, "notes.txt").writeText("not an apk")
        File(dir, "subdir").mkdirs()
        val removed = PluginInstallStateFiles.pruneDownloadedApks(dir, setOf("ocr-plugin.apk"))
        assertEquals("只删不再被清单引用的 apk", listOf("xiangqi-plugin.apk"), removed)
        assertTrue("当前版本必须留着（安装器可能还在读）", File(dir, "ocr-plugin.apk").isFile)
        assertTrue("非 apk 不动", File(dir, "notes.txt").isFile)
        assertTrue("目录不动", File(dir, "subdir").isDirectory)
    }
}
