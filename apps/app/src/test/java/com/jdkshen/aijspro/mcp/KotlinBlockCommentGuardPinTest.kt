package com.jdkshen.aijspro.mcp

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P3-1 纪律钉子：Kotlin 块注释是**嵌套的**（与 Java 不同）⇒ KDoc 里出现斜杠星号序列会把注释打开到 EOF。
 *
 * 由来（Lead 定位）：McpToolArgException.kt 的 KDoc 里写了「mcp 目录通配写法」中的斜杠加双星号，
 * 末尾的斜杠加星号构成新的块注释开始 ⇒ 深度不归零 ⇒ Unclosed comment ⇒ 整个文件不编译
 * ⇒ 连带一堆 Unresolved reference（离线 PinTest 只编译测试、编不到主源码，所以当时全绿却主源码编不过）。
 *
 * 本钉子对 mcp 包下的 .kt 做一次轻量扫描：块注释深度必须归零，且块注释/KDoc **内部**不得出现新的注释开始序列。
 */
class KotlinBlockCommentGuardPinTest {

    private fun kotlinFiles(): List<File> {
        val roots = listOf(
            File("src/main/java/com/jdkshen/aijspro/mcp"),
            File("src/test/java/com/jdkshen/aijspro/mcp"),
        )
        return roots.filter { it.isDirectory }.flatMap { root -> root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList() }
    }

    /** 返回违规描述（空 = 干净）。扫描时忽略字符串字面量与行注释，避免误报。 */
    private fun scan(file: File): List<String> {
        val text = file.readText()
        val problems = ArrayList<String>()
        var depth = 0
        var inLineComment = false
        var inString = false
        var line = 1
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val next = if (i + 1 < text.length) text[i + 1] else ' '
            if (c == '\n') { line++; inLineComment = false; i++; continue }
            if (inLineComment) { i++; continue }
            if (inString) {
                if (c == '\\') { i += 2; continue }
                if (c == '"') inString = false
                i++; continue
            }
            if (depth == 0 && c == '"') { inString = true; i++; continue }
            if (depth == 0 && c == '/' && next == '/') { inLineComment = true; i += 2; continue }
            if (c == '/' && next == '*') {
                if (depth > 0) problems.add("第 " + line + " 行：块注释内部又出现注释开始序列（嵌套注释从这里开始，末尾容易 Unclosed）")
                depth++
                i += 2
                continue
            }
            if (c == '*' && next == '/') { if (depth > 0) depth--; i += 2; continue }
            i++
        }
        if (depth != 0) problems.add("文件结束时块注释深度仍为 " + depth + "（Unclosed comment）")
        return problems
    }

    @Test fun everyKotlinFileInMcpHasBalancedBlockComments() {
        val files = kotlinFiles()
        assertTrue("没扫到任何 .kt（工作目录应为 apps/app）", files.isNotEmpty())
        val bad = files.map { it to scan(it) }.filter { it.second.isNotEmpty() }
        assertTrue("块注释不配平/嵌套开始序列：" + bad.joinToString("；") { it.first.name + " -> " + it.second.joinToString("、") },
            bad.isEmpty())
    }

    @Test fun guardDetectsTheKnownTrap() {
        // 用合成文件证明这把尺子真的能抓到「KDoc 里出现斜杠星号序列」这一坑。
        val temp = File.createTempFile("kotlin-comment-trap", ".kt")
        temp.writeText("/** 说明：mcp 目录通配写法 mcp/** 里的工具。 */\nfun sample() = 1\n")
        try {
            assertTrue("尺子必须能抓到合成样例", scan(temp).isNotEmpty())
        } finally {
            temp.delete()
        }
    }
}
