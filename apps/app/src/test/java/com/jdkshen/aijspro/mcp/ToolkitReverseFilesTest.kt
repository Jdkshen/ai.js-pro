package com.jdkshen.aijspro.mcp

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工程复制闸门的回归测试：旧上限 30000 个条目会让 5.7 万文件的大工程直接被拒
 * （「工程文件过多或不是普通文件」）。这里真造 31_000 个文件跨过旧上限，证明现在能复制。
 */
class ToolkitReverseFilesTest {
    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile()

    @Test(timeout = 180_000)
    fun copiesThirtyOneThousandFilesAcrossTheOldLimit() {
        val source = tempDir("aijs-copy-src-")
        val target = tempDir("aijs-copy-dst-")
        val perDir = 400
        val dirs = 80
        var created = 0
        for (d in 0 until dirs) {
            val dir = File(source, "d$d").apply { mkdirs() }
            for (f in 0 until perDir) {
                File(dir, "f$f.txt").writeText("x", Charsets.UTF_8)
                created++
            }
        }
        assertTrue("需要造出超过旧上限 30000 的文件，实际 $created", created > 30_000)

        val sha = ToolkitReverseFiles.copyTree(source, target)

        assertEquals("sha256 必须是 64 位 hex", 64, sha.length)
        assertTrue("sha256 只含 hex", sha.all { it in "0123456789abcdef" })
        val copied = target.walkTopDown().count { it.isFile }
        assertEquals("复制的文件数必须与源一致", created, copied)
        assertTrue("目标必须包含子目录", File(target, "d${dirs - 1}").isDirectory)
    }

    @Test(timeout = 60_000)
    fun rejectsSymlinkedSource() {
        val source = tempDir("aijs-copy-link-")
        File(source, "real.txt").writeText("x", Charsets.UTF_8)
        val supported = try {
            Files.createSymbolicLink(File(source, "link.txt").toPath(), File(source, "real.txt").toPath())
            true
        } catch (unsupported: Throwable) {
            false
        }
        if (!supported) return // Windows 非管理员建不了符号链接：跳过而不是假失败
        // Windows 的 canonicalPath 不解析符号链接（GetFullPathName），所以那套守卫在 PC 上不会被触发；
        // Android/ART 的 canonical 走 realpath 会解析——只在会解析的平台断言「必须被拒」。
        val link = File(source, "link.txt")
        if (link.canonicalPath == link.absolutePath) return
        val target = tempDir("aijs-copy-link-dst-")
        var failed = false
        try {
            ToolkitReverseFiles.copyTree(source, target)
        } catch (error: IllegalArgumentException) {
            failed = true
        }
        assertTrue("含符号链接的工程必须被拒绝", failed)
    }
}
