package com.jdkshen.aijspro.ui.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * task-120 钉子：「缺少打包运行时模板」卡片的动作必须是**自足**的。
 *
 * 背景：MiuixBuildActivity.TemplateNoticeCard 里原来有一个按钮会走
 * installPackagingPlugin()（从更新源下载并安装 APK 打包插件）。用户拍板不要插件
 * ⇒ 该按钮与下载链路已删除，只剩「清模板缓存 + 重新检测」。
 */
class MiuixBuildActivityTemplateActionTest {
    private val activityPath = "src/miuix/java/com/jdkshen/aijspro/ui/project/MiuixBuildActivity.kt"
    private val helperPath = "src/main/java/com/jdkshen/aijspro/build/ApkBuilderPluginHelper.java"

    private fun read(relative: String): String {
        val file = File(relative)
        assertTrue("找不到 " + file.absolutePath, file.isFile)
        return file.readText()
    }

    @Test fun pluginDownloadPathIsGone() {
        val source = read(activityPath)
        assertEquals("不得再有插件下载方法调用", 0, Regex("installPackagingPlugin").findAll(source).count())
        assertEquals("不得再引用 PluginDownloads", 0, Regex("PluginDownloads").findAll(source).count())
        assertEquals("pluginBusy 已随下载链路删除", 0, Regex("pluginBusy").findAll(source).count())
        assertEquals("不得再用「安装插件」那类字符串", 0,
            Regex("text_install_packaging_plugin").findAll(source).count())
    }

    @Test fun noticeCardOffersTheSelfSufficientAction() {
        val source = read(activityPath)
        assertTrue("卡片必须调用自足动作", source.contains("clearTemplateCacheAndRecheck()"))
        assertTrue("自足动作必须真清缓存", source.contains("ApkBuilderPluginHelper.clearCachedTemplates(this)"))
        assertTrue("重新检测必须保留", source.contains("refreshTemplateState()"))
        assertTrue("按钮文案复用既有的「重新检测」", source.contains("R.string.text_recheck"))
    }

    @Test fun helperExposesCacheClearingWithoutTouchingSdcardOverride() {
        val helper = read(helperPath)
        assertTrue("必须提供 clearCachedTemplates(Context)",
            helper.contains("public static int clearCachedTemplates(Context context)"))
        val body = helper.substringAfter("public static int clearCachedTemplates(Context context)")
            .substringBefore("private static int deleteTreeCounting")
        assertTrue("只清宿主缓存（cachedTemplateFile 的上级目录）：" + body, body.contains("cachedTemplateFile(context)"))
        assertTrue("不得动 /sdcard 覆盖", !body.contains("manualTemplateFile"))
        assertTrue("必须有删除计数实现", helper.contains("private static int deleteTreeCounting(File file)"))
    }

    @Test fun displayConditionIsTemplateNotReady() {
        val source = read(activityPath)
        assertTrue("卡片显示条件必须是 !templateReady", source.contains("if (!templateReady)"))
        assertTrue("条件内调用 TemplateNoticeCard()", source.contains("TemplateNoticeCard()"))
    }
}
