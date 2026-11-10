package com.jdkshen.aijspro.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * task-116 回归钉：**改方法签名必须同步所有调用点**。
 *
 * 背景：把 missingTemplateMessage() 改成 missingTemplateMessage(Context) 时只改了同一文件的
 * 一处调用（:138），:159 仍按无参调用 ⇒ :app:compileMiuixCompatReleaseJavaWithJavac 编译失败。
 * 这里用源码级钉子挡住同类错误：该名字**不得**再出现空参调用。
 */
class MethodSignatureCallSiteTest {
    private fun read(relative: String): String {
        val file = File(relative)
        assertTrue("找不到 " + file.absolutePath, file.isFile)
        return file.readText()
    }

    @Test fun missingTemplateMessageIsNeverCalledWithoutContext() {
        val source = read("src/main/java/com/jdkshen/aijspro/build/ApkBuilderPluginHelper.java")
        val noArgCalls = Regex("missingTemplateMessage\\s*\\(\\s*\\)").findAll(source).count()
        assertEquals("missingTemplateMessage() 空参调用会编译失败；所有调用点都必须传 context", 0, noArgCalls)
        val withArgCalls = Regex("missingTemplateMessage\\s*\\(\\s*context\\s*\\)").findAll(source).count()
        assertTrue("至少要有一个传 context 的调用点（打包兜底路径）", withArgCalls >= 1)
        // 定义处也带参数（防止签名被改回去而不更新调用点）
        assertTrue("定义必须接收 Context", source.contains("private static String missingTemplateMessage(Context context)"))
    }

    @Test fun pluginIsNeverDescribedAsRequiredInTheHelper() {
        val source = read("src/main/java/com/jdkshen/aijspro/build/ApkBuilderPluginHelper.java")
        // task-116 口径：默认路径与兜底文案不得把插件写成必需
        assertTrue("兜底文案必须说明不需要插件", source.contains("不需要"))
        assertTrue(source.contains("最后手段"))
    }
}
