package com.jdkshen.aijspro.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * McpAppTools 的纯 JVM 单测：不依赖 Android / Robolectric。
 *
 * 只覆盖与设备无关的部分（包名 / URL / APK 路径校验、应用列表过滤排序分页、JSON 字段）；
 * PackageManager / Intent 的实际行为由真机验收。
 */
class McpAppToolsTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun app(
        packageName: String,
        label: String = packageName,
        isSystem: Boolean = false,
        versionName: String = "1.0",
        versionCode: Long = 1L,
    ) = McpAppTools.AppSummary(
        packageName = packageName,
        label = label,
        versionName = versionName,
        versionCode = versionCode,
        isSystem = isSystem,
        enabled = true,
        targetSdk = 30,
    )

    // ------------------------------------------------------------------
    // 包名校验
    // ------------------------------------------------------------------

    @Test fun packageNameAcceptsInstalledAppStyleNamesAndTrims() {
        for (valid in listOf(
            "com.example.app",
            "android",
            "com.tencent.mm",
            "org.mozilla.firefox",
            "com.example.app_2",
            "com.eg.android.AlipayGphone",
        )) {
            assertEquals(valid, McpAppTools.normalizePackageName("  $valid  "))
        }
    }

    @Test fun packageNameRejectsMalformedNamesWithChineseErrors() {
        val invalidNames = listOf(
            "",
            "   ",
            "com..app",
            "com.app.",
            ".com.app",
            "com app",
            "com/app",
            "com.example-app",
            "com.example.2app",
            "应用.包名",
            "a".repeat(256),
        )
        for (invalid in invalidNames) {
            val error = runCatching { McpAppTools.normalizePackageName(invalid) }.exceptionOrNull()
            assertTrue("应拒绝包名 [$invalid]，实际：$error", error is IllegalArgumentException)
            assertTrue("错误信息应说明 packageName：${error?.message}", error?.message?.contains("packageName") == true)
        }
    }

    // ------------------------------------------------------------------
    // URL 校验
    // ------------------------------------------------------------------

    @Test fun httpUrlAcceptsOnlyWebSchemesAndTrims() {
        for (valid in listOf(
            "http://example.com",
            "https://example.com:8443/path?q=1#frag",
            "HTTPS://EXAMPLE.COM/x",
            "  https://example.com/a/b  ",
        )) {
            assertEquals(valid.trim(), McpAppTools.normalizeHttpUrl(valid))
        }
    }

    @Test fun httpUrlRejectsNonWebSchemesAndMalformedUrls() {
        for (invalid in listOf(
            "",
            "   ",
            "example.com",
            "ftp://example.com",
            "javascript:alert(1)",
            "file:///sdcard/app.apk",
            "http://",
            "http:///only-path",
        )) {
            val error = runCatching { McpAppTools.normalizeHttpUrl(invalid) }.exceptionOrNull()
            assertTrue("应拒绝 url [$invalid]，实际：$error", error is IllegalArgumentException)
            assertTrue("错误信息应说明 url：${error?.message}", error?.message?.contains("url") == true)
        }
    }

    // ------------------------------------------------------------------
    // APK 路径校验
    // ------------------------------------------------------------------

    @Test fun apkPathRequiresAbsoluteReadableNonEmptyApkFile() {
        val apk = temporaryFolder.newFile("demo.apk").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        assertEquals(apk.canonicalFile, McpAppTools.requireApkFile("  ${apk.absolutePath}  "))

        temporaryFolder.newFile("not-apk.zip")
        temporaryFolder.newFolder("folder.apk")
        temporaryFolder.newFile("empty.apk")
        val root = temporaryFolder.root

        val cases = listOf(
            "" to "不能为空",
            "relative/demo.apk" to "绝对路径",
            File(root, "not-apk.zip").absolutePath to ".apk",
            File(root, "missing.apk").absolutePath to "不存在",
            File(root, "folder.apk").absolutePath to "不存在",
            File(root, "empty.apk").absolutePath to "为空",
        )
        for ((path, expected) in cases) {
            val error = runCatching { McpAppTools.requireApkFile(path) }.exceptionOrNull()
            assertTrue("应拒绝 apk path [$path]，实际：$error", error is IllegalArgumentException)
            assertTrue("错误信息应包含「$expected」：${error?.message}", error?.message?.contains(expected) == true)
        }
    }

    // ------------------------------------------------------------------
    // 应用列表过滤 / 排序 / 分页 / JSON
    // ------------------------------------------------------------------

    @Test fun appFilterMatchesQueryOnPackageAndLabelIgnoringCase() {
        val apps = listOf(
            app("com.tencent.mm", "微信"),
            app("com.android.settings", "设置", isSystem = true),
            app("org.mozilla.firefox", "Firefox"),
        )
        assertEquals(
            listOf("com.tencent.mm"),
            McpAppTools.filterApps(apps, "微信", includeSystem = false).map { it.packageName },
        )
        assertEquals(
            listOf("com.android.settings"),
            McpAppTools.filterApps(apps, "ANDROID", includeSystem = true).map { it.packageName },
        )
        assertEquals(
            listOf("org.mozilla.firefox"),
            McpAppTools.filterApps(apps, "fireFOX", includeSystem = false).map { it.packageName },
        )
        assertTrue(McpAppTools.filterApps(apps, "不存在的应用", includeSystem = true).isEmpty())
    }

    @Test fun queryNormalizationUsesNfcFullWidthCaseAndWhitespaceFolding() {
        assertEquals("abc def", McpAppTools.normalizeQuery("  ＡＢＣ　　ＤＥＦ  "))
        assertEquals("café", McpAppTools.normalizeQuery("Cafe\u0301"))
        assertEquals("微信 聊天", McpAppTools.normalizeQuery(" 微信 \u3000 聊天 "))
        assertEquals("firefox", McpAppTools.normalizeQuery("ＦＩＲＥＦＯＸ"))
        assertEquals("", McpAppTools.normalizeQuery("   \u3000  "))
        assertEquals("", McpAppTools.normalizeQuery(null))
    }

    @Test fun appFilterReportsMatchedByAndNormalizedQuery() {
        val apps = listOf(
            app("com.tencent.mm", "微信"),
            app("org.mozilla.firefox", "Firefox"),
            app("com.example.music", "音乐"),
        )
        val withSpaces = apps + app("com.example.wechat", "微信 聊天")
        fun hit(query: String): McpAppTools.AppSummary =
            McpAppTools.filterApps(apps, query, includeSystem = true).single()
        assertEquals("package", hit("com.tencent.mm").matchedBy)
        assertEquals("label", hit("微信").matchedBy)
        assertEquals("label", hit("ＦＩＲＥＦＯＸ").matchedBy)
        assertEquals("labelContains", hit("fire").matchedBy)
        assertEquals("package", hit("tencent").matchedBy)
        assertEquals("label", McpAppTools.filterApps(withSpaces, "微信 聊天", includeSystem = true).single().matchedBy)
        assertEquals("package", hit("com tencent").matchedBy)
        assertTrue(McpAppTools.filterApps(withSpaces, "微信 支付", includeSystem = true).isEmpty())
        assertEquals("微信 聊天", McpAppTools.searchApps(withSpaces, "  微信   聊天 ").normalizedQuery)
    }

    @Test fun appFilterMatchesPackageAndLabelConsistently() {
        val apps = listOf(
            app("com.example.alpha", "Alpha"),
            app("com.example.beta", "Beta"),
        )
        val byPackage = McpAppTools.filterApps(apps, "com.example.alpha", includeSystem = true)
        val byLabel = McpAppTools.filterApps(apps, "alpha", includeSystem = true)
        assertEquals(listOf("com.example.alpha"), byPackage.map { it.packageName })
        assertEquals(listOf("com.example.alpha"), byLabel.map { it.packageName })
        assertEquals("package", byPackage.single().matchedBy)
        assertEquals("label", byLabel.single().matchedBy)
    }

    @Test fun filterBeforePaginationKeepsFilteredTotal() {
        val apps = (1..5).map { app("com.example.app$it", "Filtered $it") } +
            app("com.example.other", "Other")
        val search = McpAppTools.searchApps(apps, "filtered", includeSystem = true)
        assertEquals(5, search.items.size)
        assertEquals(6, search.scanTotal)
        assertEquals("filtered", search.normalizedQuery)
        val first = McpAppTools.paginateApps(search.items, 0, 2)
        assertEquals(5, first.total)
        assertEquals(2, first.items.size)
        assertEquals(0, first.offset)
        assertEquals(2, first.limit)
        assertEquals(2, first.nextOffset)
        assertTrue(first.hasMore)
        val second = McpAppTools.paginateApps(search.items, first.nextOffset, 2)
        assertEquals(2, second.items.size)
        assertEquals(4, second.nextOffset)
        assertTrue(second.hasMore)
        val third = McpAppTools.paginateApps(search.items, second.nextOffset, 2)
        assertEquals(1, third.items.size)
        assertEquals(5, third.nextOffset)
        assertFalse(third.hasMore)
        assertEquals((1..5).map { "com.example.app$it" }.sorted(), (first.items + second.items + third.items).map { it.packageName }.sorted())
    }

    @Test fun packageVisibilityStateDistinguishesLimitedAndFullScans() {
        assertFalse(McpAppTools.visibilityState(29, queryAllPackagesGranted = false).limited)
        assertEquals("all_installed", McpAppTools.visibilityState(29, false).scanScope)
        assertFalse(McpAppTools.visibilityState(30, queryAllPackagesGranted = true).limited)
        assertEquals("all_installed", McpAppTools.visibilityState(30, true).scanScope)
        val limited = McpAppTools.visibilityState(30, queryAllPackagesGranted = false)
        assertTrue(limited.limited)
        assertEquals("visible_to_app", limited.scanScope)
        assertTrue(limited.note.contains("QUERY_ALL_PACKAGES"))
        assertTrue(limited.note.contains("Android 11+"))
    }

    @Test fun appSummaryJsonIncludesMatchedByWhenPresent() {
        val json = McpAppTools.appSummaryJson(
            app("com.example.app", "示例").copy(matchedBy = "label"),
        )
        assertEquals("label", json.get("matchedBy").asString)
        val unset = McpAppTools.appSummaryJson(app("com.example.app", "示例"))
        assertTrue(unset.get("matchedBy").isJsonNull)
    }

    @Test fun appFilterExcludesSystemAppsWhenRequested() {
        val apps = listOf(
            app("com.tencent.mm", "微信"),
            app("com.android.settings", "设置", isSystem = true),
        )
        assertEquals(2, McpAppTools.filterApps(apps, null, includeSystem = true).size)
        assertEquals(1, McpAppTools.filterApps(apps, "", includeSystem = false).size)
        // 默认 includeSystem=true（与工具默认值一致）
        assertEquals(2, McpAppTools.filterApps(apps, null).size)
    }

    @Test fun appFilterSortsByLabelIgnoringCaseThenPackage() {
        val apps = listOf(
            app("com.example.z", "banana"),
            app("com.example.b", "Apple"),
            app("com.example.a", "apple"),
        )
        assertEquals(
            listOf("com.example.a", "com.example.b", "com.example.z"),
            McpAppTools.filterApps(apps, null, includeSystem = true).map { it.packageName },
        )
    }

    @Test fun appPaginationClampsOffsetAndLimitAndReportsContinuation() {
        val apps = (1..5).map { app("com.example.app$it", "App$it") }

        val first = McpAppTools.paginateApps(apps, 0, 2)
        assertEquals(2, first.items.size)
        assertEquals(0, first.offset)
        assertEquals(2, first.nextOffset)
        assertEquals(5, first.total)
        assertTrue(first.hasMore)

        val last = McpAppTools.paginateApps(apps, 4, 10)
        assertEquals(1, last.items.size)
        assertEquals(5, last.nextOffset)
        assertFalse(last.hasMore)

        val pastEnd = McpAppTools.paginateApps(apps, 99, 10)
        assertTrue(pastEnd.items.isEmpty())
        assertEquals(5, pastEnd.offset)
        assertEquals(5, pastEnd.nextOffset)
        assertFalse(pastEnd.hasMore)

        val negativeOffset = McpAppTools.paginateApps(apps, -3, 2)
        assertEquals(0, negativeOffset.offset)
        assertEquals(2, negativeOffset.items.size)

        val zeroLimit = McpAppTools.paginateApps(apps, 0, 0)
        assertEquals(1, zeroLimit.items.size)

        val many = (1..McpAppTools.MAX_LIST_LIMIT + 5).map { app("com.example.app$it", "App$it") }
        val clamped = McpAppTools.paginateApps(many, 0, 10_000)
        assertEquals(McpAppTools.MAX_LIST_LIMIT, clamped.items.size)
        assertTrue(clamped.hasMore)
        assertEquals(McpAppTools.MAX_LIST_LIMIT, clamped.nextOffset)
    }

    @Test fun appSummaryJsonKeepsStableMachineReadableFields() {
        val json = McpAppTools.appSummaryJson(
            app("com.example.app", "示例", isSystem = true, versionName = "2.3", versionCode = 42L),
        )
        assertEquals("com.example.app", json.get("packageName").asString)
        assertEquals("示例", json.get("label").asString)
        assertEquals("2.3", json.get("versionName").asString)
        assertEquals(42L, json.get("versionCode").asLong)
        assertTrue(json.get("isSystem").asBoolean)
        assertTrue(json.get("enabled").asBoolean)
        assertEquals(30, json.get("targetSdk").asInt)
    }
}
