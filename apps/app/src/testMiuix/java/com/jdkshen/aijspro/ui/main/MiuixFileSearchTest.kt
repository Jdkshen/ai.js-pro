package com.jdkshen.aijspro.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MiuixFileSearchTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun directSearchIsCaseInsensitiveAndSkipsHiddenFiles() {
        val root = temporaryFolder.newFolder("scripts")
        File(root, "Alpha.JS").writeText("")
        File(root, ".alpha-secret.js").writeText("")
        File(root, "alpha-folder").mkdir()
        File(File(root, "nested").apply { mkdir() }, "alpha-nested.js").writeText("")

        val result = MiuixFileSearch.search(root, root, "ALPHA",
            includeSubdirectories = false, useRegex = false)

        assertEquals(listOf("alpha-folder", "Alpha.JS"), result.files.map { it.name })
        assertFalse(result.truncated)
        assertEquals(null, result.error)
    }

    @Test
    fun emptyKeywordListsCurrentDirectoryWithoutRecursing() {
        val root = temporaryFolder.newFolder("scripts")
        File(root, "direct.js").writeText("")
        val nested = File(root, "nested").apply { mkdir() }
        File(nested, "inside.js").writeText("")

        val result = MiuixFileSearch.search(root, root, "",
            includeSubdirectories = false, useRegex = false)

        assertEquals(listOf("nested", "direct.js"), result.files.map { it.name })
        assertEquals(null, result.error)
    }

    @Test
    fun recursiveRegexSearchFindsNestedMatches() {
        val root = temporaryFolder.newFolder("scripts")
        val nested = File(root, "nested").apply { mkdir() }
        File(nested, "main-42.js").writeText("")
        File(nested, "main-x.js").writeText("")

        val result = MiuixFileSearch.search(root, root, "MAIN-\\d+\\.JS$",
            includeSubdirectories = true, useRegex = true)

        assertEquals(listOf("main-42.js"), result.files.map { it.name })
        assertEquals("nested", MiuixFileSearch.relativeParent(root, result.files.single()))
    }

    @Test
    fun invalidRegexReportsErrorInsteadOfUsingLiteralFallback() {
        val root = temporaryFolder.newFolder("scripts")
        File(root, "[.js").writeText("")

        val result = MiuixFileSearch.search(root, root, "[",
            includeSubdirectories = true, useRegex = true)

        assertTrue(result.files.isEmpty())
        assertTrue(result.error?.startsWith("正则表达式无效：") == true)
    }

    @Test
    fun resultLimitIsExplicitlyMarkedAsTruncated() {
        val root = temporaryFolder.newFolder("scripts")
        repeat(4) { File(root, "match-$it.js").writeText("") }

        val result = MiuixFileSearch.search(root, root, "match",
            includeSubdirectories = false, useRegex = false, limit = 3)

        assertEquals(3, result.files.size)
        assertTrue(result.truncated)
    }

    @Test
    fun directoryOutsideScriptRootIsRejected() {
        val root = temporaryFolder.newFolder("scripts")
        val outside = temporaryFolder.newFolder("outside")

        val result = MiuixFileSearch.search(root, outside, "main",
            includeSubdirectories = true, useRegex = false)

        assertTrue(result.files.isEmpty())
        assertEquals("搜索目录不在脚本根目录内", result.error)
    }
}
