package com.jdkshen.aijspro.ui.sample

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Locale

class MiuixSampleCatalogTest {
    private val entries = listOf(
        SampleEntry("foo", "sample/foo", true),
        SampleEntry("foobar", "sample/foobar", true),
        SampleEntry("nested", "sample/foo/nested", true),
        SampleEntry("main.js", "sample/foo/main.js", false),
        SampleEntry("main.js", "sample/foo/nested/main.js", false),
        SampleEntry("MAIN.JS", "sample/foobar/MAIN.JS", false),
        SampleEntry("说明.txt", "sample/foo/说明.txt", false),
        SampleEntry("config.json", "sample/foo/nested/config.json", false),
        SampleEntry("hello.js", "sample/hello.js", false),
        SampleEntry("README", "sample/README", false)
    )

    private fun visible(
        path: String = "sample",
        query: String = "",
        filter: SampleFilter = SampleFilter.ALL
    ) = MiuixSampleCatalog.visible(entries, path, query, filter).map { it.path }

    @Test
    fun loadIncludesDirectoriesAndAllLeafTypesWithoutOpeningContents() {
        val assetTree = mapOf(
            "sample" to arrayOf("foo", "README"),
            "sample/foo" to arrayOf("nested", "run.JS"),
            "sample/foo/nested" to arrayOf("main.js", "image.png", "config.json")
        )
        val calls = mutableListOf<String>()
        val catalog = MiuixSampleCatalog.load { path ->
            calls += path
            assetTree[path] ?: emptyArray()
        }

        assertEquals(setOf(
            "sample/foo", "sample/README", "sample/foo/nested", "sample/foo/run.JS",
            "sample/foo/nested/main.js", "sample/foo/nested/image.png", "sample/foo/nested/config.json"
        ), catalog.map { it.path }.toSet())
        assertEquals(setOf("sample/foo", "sample/foo/nested"),
            catalog.filter { it.directory }.map { it.path }.toSet())
        assertEquals(catalog.size + 1, calls.size)
        assertEquals(calls.size, calls.distinct().size)
        assertTrue(catalog.single { it.name == "run.JS" }.runnable)
    }

    @Test
    fun loadDoesNotTruncateAfterFiveHundredResults() {
        val catalog = MiuixSampleCatalog.load { path ->
            when (path) {
                "sample" -> arrayOf("many", "last")
                "sample/many" -> Array(1001) { "script$it.js" }
                "sample/last" -> arrayOf("end.js")
                else -> emptyArray()
            }
        }
        assertEquals(1004, catalog.size)
        assertEquals(1002, catalog.count { it.runnable })
        assertTrue(catalog.any { it.path == "sample/last/end.js" })
        assertTrue(catalog.any { it.name == "script1000.js" })
    }

    @Test
    fun loadHandlesDeepDirectoriesWithoutRecursiveCallStack() {
        val depth = 2000
        val catalog = MiuixSampleCatalog.load { path ->
            when {
                path.endsWith("main.js") -> emptyArray()
                path.count { it == '/' } < depth -> arrayOf("d")
                else -> arrayOf("main.js")
            }
        }
        assertEquals(depth + 1, catalog.size)
        assertEquals(depth, catalog.count { it.directory })
        assertTrue(catalog.last().runnable)
    }

    @Test
    fun emptyAssetsProduceEmptyCatalog() {
        assertTrue(MiuixSampleCatalog.load { emptyArray() }.isEmpty())
    }

    @Test(expected = IOException::class)
    fun loadPropagatesAssetReadFailureInsteadOfReturningPartialResults() {
        MiuixSampleCatalog.load { path ->
            if (path == "sample") arrayOf("unreadable") else throw IOException("assets unavailable")
        }
    }

    @Test
    fun allWithoutQueryShowsOnlyDirectChildrenWithFoldersFirst() {
        assertEquals(listOf("sample/foo", "sample/foobar", "sample/hello.js", "sample/README"), visible())
        assertEquals(listOf("sample/foo/nested", "sample/foo/main.js", "sample/foo/说明.txt"),
            visible(path = "sample/foo"))
    }

    @Test
    fun trailingSlashDoesNotChangeSelectedDirectory() {
        assertEquals(visible(path = "sample/foo"), visible(path = "sample/foo/"))
    }

    @Test
    fun javascriptFilterSearchesAllDescendantsEvenWithoutQuery() {
        assertEquals(listOf("sample/hello.js", "sample/foo/main.js", "sample/foo/nested/main.js",
            "sample/foobar/MAIN.JS"), visible(filter = SampleFilter.JAVASCRIPT))
    }

    @Test
    fun folderFilterIncludesNestedFolders() {
        assertEquals(listOf("sample/foo", "sample/foobar", "sample/foo/nested"),
            visible(filter = SampleFilter.FOLDERS))
    }

    @Test
    fun otherFilterIncludesOnlyNonJavascriptLeaves() {
        assertEquals(listOf("sample/foo/nested/config.json", "sample/README", "sample/foo/说明.txt"),
            visible(filter = SampleFilter.OTHER))
    }

    @Test
    fun searchMatchesCaseInsensitiveNamesInDescendants() {
        assertEquals(listOf("sample/foo/main.js", "sample/foo/nested/main.js", "sample/foobar/MAIN.JS"),
            visible(query = "  MaIn  "))
    }

    @Test
    fun searchMatchesRelativePathsAsWellAsNames() {
        assertEquals(listOf("sample/foo/nested/config.json", "sample/foo/nested/main.js"),
            visible(query = "FOO/NESTED/"))
        assertEquals(listOf("sample/foo/nested/config.json", "sample/foo/nested/main.js"),
            visible(path = "sample/foo", query = "nested/"))
    }

    @Test
    fun relativeSearchDoesNotMatchCurrentOrAncestorDirectoryName() {
        assertTrue(visible(path = "sample/foo", query = "foo").isEmpty())
        assertTrue(visible(query = "sample/").isEmpty())
    }

    @Test
    fun searchAndTypeFilterApplyTogether() {
        assertEquals(listOf("sample/foo/nested/main.js"),
            visible(query = "nested/", filter = SampleFilter.JAVASCRIPT))
        assertEquals(listOf("sample/foo/nested/config.json"),
            visible(query = "nested/", filter = SampleFilter.OTHER))
    }

    @Test
    fun searchCanStayInCurrentDirectory() {
        assertEquals(listOf("sample/foo/main.js"), MiuixSampleCatalog.search(
            entries, "sample/foo", "main", SampleFilter.ALL,
            includeSubdirectories = false
        ).entries.map { it.path })
    }

    @Test
    fun regexSearchIsCaseInsensitiveAndMatchesRelativePath() {
        val result = MiuixSampleCatalog.search(entries, "sample", "FOO/.+MAIN\\.JS$",
            SampleFilter.JAVASCRIPT, includeSubdirectories = true, useRegex = true)
        assertEquals(listOf("sample/foo/nested/main.js"), result.entries.map { it.path })
        assertEquals(null, result.error)
    }

    @Test
    fun invalidRegexReturnsAnErrorInsteadOfFallingBackToPlainSearch() {
        val result = MiuixSampleCatalog.search(entries, "sample", "[",
            SampleFilter.ALL, includeSubdirectories = true, useRegex = true)
        assertTrue(result.entries.isEmpty())
        assertTrue(result.error?.startsWith("正则表达式无效：") == true)
    }

    @Test
    fun subtreeDoesNotIncludeSimilarlyPrefixedSiblingOrCurrentDirectory() {
        assertEquals(listOf("sample/foo/main.js", "sample/foo/nested/main.js"),
            visible(path = "sample/foo", filter = SampleFilter.JAVASCRIPT))
        assertEquals(listOf("sample/foo/nested"),
            visible(path = "sample/foo", filter = SampleFilter.FOLDERS))
        assertTrue(visible(path = "sample/fo", filter = SampleFilter.JAVASCRIPT).isEmpty())
    }

    @Test
    fun whitespaceOnlySearchKeepsDirectChildBrowsing() {
        assertEquals(visible(), visible(query = " \t\n "))
    }

    @Test
    fun noMatchingOrMissingDirectoryResultsAreEmpty() {
        assertTrue(visible(query = "not-present").isEmpty())
        assertTrue(visible(path = "sample/missing").isEmpty())
        assertTrue(MiuixSampleCatalog.visible(emptyList(), "sample", "", SampleFilter.ALL).isEmpty())
    }

    @Test
    fun directoriesNamedWithJavascriptSuffixAreNotRunnable() {
        assertFalse(SampleEntry("folder.js", "sample/folder.js", true).runnable)
        assertFalse(SampleEntry("file.json", "sample/file.json", false).runnable)
        assertFalse(SampleEntry("file.js.txt", "sample/file.js.txt", false).runnable)
        assertTrue(SampleEntry("file.Js", "sample/file.Js", false).runnable)
    }

    @Test
    fun sortUsesRootLocaleAndPathTieBreakerRegardlessOfInputOrder() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale("tr", "TR"))
            val unordered = listOf(
                SampleEntry("I.js", "sample/z/I.js", false),
                SampleEntry("z.js", "sample/z.js", false),
                SampleEntry("i.js", "sample/a/i.js", false),
                SampleEntry("Zdir", "sample/Zdir", true)
            )
            val expected = listOf("sample/Zdir", "sample/a/i.js", "sample/z/I.js", "sample/z.js")
            assertEquals(expected, MiuixSampleCatalog.visible(unordered, "sample", "", SampleFilter.FOLDERS)
                .map { it.path } + MiuixSampleCatalog.visible(unordered, "sample", "", SampleFilter.JAVASCRIPT)
                .map { it.path })
            assertEquals(MiuixSampleCatalog.visible(unordered, "sample", "i", SampleFilter.ALL),
                MiuixSampleCatalog.visible(unordered.reversed(), "sample", "i", SampleFilter.ALL))
        } finally {
            Locale.setDefault(originalLocale)
        }
    }
}
