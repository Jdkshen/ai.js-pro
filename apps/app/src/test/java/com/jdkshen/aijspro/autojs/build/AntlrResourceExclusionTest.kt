package com.jdkshen.aijspro.autojs.build

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/*
 * task-98 钉子：ANTLR 的「工具」代码生成模板（org/antlr/codegen/ 下的 .stg，约 0.7 MiB 未压缩）
 * 必须在打包期被排除，而 org/antlr/runtime 的类必须保留（modules/engine 的
 * Node24DirectDexGenerator 只用 runtime；全仓 org.antlr.codegen / stringtemplate 零调用点）。
 *
 * 这里做源码级钉：两个会产出 APK 的模块（:app 与 :inrt —— 后者产出瘦身打包模板）都必须声明
 * 这条 excludes；同时禁止出现更宽的 org/antlr/ 全排（会把 runtime 一起排掉 ⇒ node24 打包路径炸）。
 *
 * 产物级断言在 gradle 任务 verifySlimPackagingTemplate 里（模板里 codegen 必须 0 条 + dex 里必须
 * 能搜到 org/antlr/runtime），两者互为补充：这里防「配置被改」，那里防「配置没生效」。
 * 注意：Kotlin 的块注释会嵌套，注释里不要出现斜杠加双星号，否则会「Unclosed comment」。
 */
class AntlrResourceExclusionTest {
    private fun read(relative: String): String {
        val file = File(relative)
        assertTrue("找不到 " + file.absolutePath, file.isFile)
        return file.readText()
    }

    @Test fun appModuleExcludesAntlrCodegenTemplates() {
        val text = read("build.gradle")
        assertTrue(":app 的 packagingOptions 必须排除 org/antlr/codegen/**", text.contains("org/antlr/codegen/**"))
    }

    @Test fun inrtModuleExcludesAntlrCodegenTemplates() {
        // inrt 产出瘦身打包模板（apps/app/src/main/assets/template.apk），少了它模板里就还带着 .stg
        val text = read("../inrt/build.gradle")
        assertTrue(":inrt 必须声明 packagingOptions.resources.excludes", text.contains("packagingOptions"))
        assertTrue(":inrt 的 excludes 必须含 org/antlr/codegen/**", text.contains("org/antlr/codegen/**"))
    }

    @Test fun neitherModuleExcludesTheWholeAntlrPackage() {
        for (relative in listOf("build.gradle", "../inrt/build.gradle")) {
            val text = read(relative)
            assertFalse("$relative 不能排除整个 org/antlr/ 目录（会连 runtime 一起排掉）", text.contains("'org/antlr/**'"))
            assertFalse("$relative 不能排除 org/antlr/runtime", text.contains("org/antlr/runtime/"))
        }
    }
}
