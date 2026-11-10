package com.jdkshen.aijspro.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** task-116：应用内模板自检（判据与 verifySlimPackagingTemplate 等价，修法自足、不提「装插件」）。 */
class TemplateSelfCheckTest {
    private fun zipOfBytes(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, body) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray =
        zipOfBytes(*entries.map { (name, body) -> name to body.toByteArray() }.toTypedArray())

    /**
     * 健康模板：必需条目齐全，且整体体积落在 6–20MiB（与 verifySlimPackagingTemplate 同判据）。
     * 体积用一条不压缩的大条目凑（ZIP 不压缩时 size 就是原样大小）。
     */
    private fun healthy(): ByteArray = zipOfBytes(
        "AndroidManifest.xml" to "<manifest/>".toByteArray(),
        "classes.dex" to "dex".toByteArray(),
        "resources.arsc" to "arsc".toByteArray(),
        "assets/init.js" to "// init".toByteArray(),
        "assets/filler.bin" to ByteArray(6_500_000) { 1 }
    )

    @Test fun healthyTemplatePasses() {
        // 用显式体积（12MiB）避开「DEFLATE 把大条目压小」的干扰：体积判据本身由 sizeBounds 用例单独验
        val result = TemplateSelfCheck.check(healthy().inputStream(), "fixture", 12L * 1024 * 1024)
        assertTrue(result.describe(), result.ok)
        assertTrue(result.problems.toString(), result.problems.isEmpty())
        assertEquals(5, result.entryCount)
    }

    @Test fun missingRequiredEntryIsReportedWithSelfSufficientFixes() {
        val result = TemplateSelfCheck.check(
            zipOf("AndroidManifest.xml" to "<manifest/>", "resources.arsc" to "arsc"), "no-dex")
        assertFalse(result.ok)
        assertTrue(result.problems.toString(), result.problems.any { it.contains("classes.dex") })
        val fixes = result.fixes()
        assertEquals(3, fixes.size)
        assertTrue(fixes.toString(), fixes.any { it.contains("重装") })
        assertTrue(fixes.toString(), fixes.any { it.contains("apk-builder") })
        assertTrue(fixes.toString(), fixes.any { it.contains("/sdcard/template.apk") })
    }

    @Test fun libEntriesAreRejected() {
        val bytes = zipOf(
            "AndroidManifest.xml" to "<manifest/>",
            "classes.dex" to "dex",
            "resources.arsc" to "arsc",
            "lib/arm64-v8a/libquickjs.so" to "so"
        )
        val result = TemplateSelfCheck.check(bytes, "with-lib")
        assertFalse(result.ok)
        assertTrue(result.problems.toString(), result.problems.any { it.contains("lib/") })
    }

    @Test fun sizeBoundsMatchVerifyTask() {
        val small = TemplateSelfCheck.check(healthy().inputStream(), "small", 1024L)
        assertFalse(small.ok)
        assertTrue(small.problems.toString(), small.problems.any { it.contains("体积偏小") })
        val big = TemplateSelfCheck.check(healthy().inputStream(), "big", 64L * 1024 * 1024)
        assertFalse(big.ok)
        assertTrue(big.problems.toString(), big.problems.any { it.contains("体积偏大") })
        // 体积未知（-1）时不因体积判失败
        assertTrue(TemplateSelfCheck.check(healthy().inputStream(), "unknown-size").ok)
    }

    @Test fun notAZipIsReportedNotCrash() {
        val result = TemplateSelfCheck.check("not a zip at all".toByteArray(), "garbage")
        assertFalse(result.ok)
        assertTrue(result.problems.isNotEmpty())
        assertTrue(result.userMessage().contains("应用内修法"))
    }

    @Test fun fixesAndMessagesNeverAskForAPlugin() {
        val result = TemplateSelfCheck.check("garbage".toByteArray(), "garbage")
        val text = result.fixes().joinToString("\n") + "\n" + result.userMessage()
        assertFalse("文案里不得出现「插件」：" + text, text.contains("插件"))
        assertTrue("必须给自足修法", text.contains("重装") && text.contains("apk-builder"))
    }

    @Test fun checkBundledHandlesMissingAsset() {
        val missing = TemplateSelfCheck.checkBundled { null }
        assertFalse(missing.ok)
        assertTrue(missing.problems.toString(), missing.problems.any { it.contains("内置模板缺失") })
        val ok = TemplateSelfCheck.checkBundled { healthy().inputStream() }
        assertTrue(ok.describe(), ok.ok)
    }
}
