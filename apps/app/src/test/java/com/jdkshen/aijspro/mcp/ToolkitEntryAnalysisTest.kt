package com.jdkshen.aijspro.mcp

import com.reandroid.arsc.chunk.xml.ResXmlDocument
import org.jf.smali.Smali
import org.jf.smali.SmaliOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * entry_ 入口分析单测（纯 JVM）：
 * - 合成 Manifest：activity-alias / exported / android:process / appComponentFactory 两态；
 * - 合成 DEX（smali）与 ELF（.dynstr/.dynsym/.dynamic/.init_array）夹具；
 * - 断言 launcher 展开（alias 标注 targetActivity）、exported 组件与进程、nativeEntryHints
 *   （JNI_OnLoad + DT_SONAME/DT_NEEDED）、以及 **preloadRecommendation 与
 *   ToolkitGadgetPreload.plan / ToolkitGadget.assessClinit 同源一致**（同输入同结论）。
 */
class ToolkitEntryAnalysisTest {

    @get:Rule val temp = TemporaryFolder()

    private val packageName = "com.example.entrytest"
    private val appClass = packageName + ".App"
    private val abi = "arm64-v8a"

    // ------------------------------------------------------------------ Manifest

    private fun manifest(
        applicationName: String? = ".App",
        appComponentFactory: String? = null,
    ): ByteArray {
        val xml = ResXmlDocument(); val root = xml.newElement("manifest")
        root.getOrCreateAttribute("package", 0).setValueAsString(packageName)
        root.getOrCreateNamespace("http://schemas.android.com/apk/res/android", "android")
        val app = root.newElement("application")
        if (applicationName != null) app.getOrCreateAndroidAttribute("name", 0x01010003).setValueAsString(applicationName)
        if (appComponentFactory != null) app.getOrCreateAndroidAttribute("appComponentFactory", 0x0101057a).setValueAsString(appComponentFactory)

        // 入口 activity：MAIN+LAUNCHER，跑在 :script 进程（Auto.js 打包包形态）
        val main = app.newElement("activity")
        main.getOrCreateAndroidAttribute("name", 0x01010003).setValueAsString(".Main")
        main.getOrCreateAndroidAttribute("exported", 0x01010010).setValueAsBoolean(true)
        main.getOrCreateAndroidAttribute("process", 0x01010011).setValueAsString(":script")
        main.newElement("intent-filter").apply {
            newElement("action").getOrCreateAndroidAttribute("name", 0x01010003).setValueAsString("android.intent.action.MAIN")
            newElement("category").getOrCreateAndroidAttribute("name", 0x01010003).setValueAsString("android.intent.category.LAUNCHER")
        }
        // activity-alias -> .Main（同样带 MAIN+LAUNCHER）
        val alias = app.newElement("activity-alias")
        alias.getOrCreateAndroidAttribute("name", 0x01010003).setValueAsString(".MainAlias")
        alias.getOrCreateAndroidAttribute("targetActivity", 0x01010202).setValueAsString(".Main")
        alias.getOrCreateAndroidAttribute("exported", 0x01010010).setValueAsBoolean(true)
        alias.newElement("intent-filter").apply {
            newElement("action").getOrCreateAndroidAttribute("name", 0x01010003).setValueAsString("android.intent.action.MAIN")
            newElement("category").getOrCreateAndroidAttribute("name", 0x01010003).setValueAsString("android.intent.category.LAUNCHER")
        }
        // 导出但非入口的组件（带独立进程）
        val service = app.newElement("service")
        service.getOrCreateAndroidAttribute("name", 0x01010003).setValueAsString(".SyncService")
        service.getOrCreateAndroidAttribute("exported", 0x01010010).setValueAsBoolean(true)
        service.getOrCreateAndroidAttribute("process", 0x01010011).setValueAsString(packageName + ".remote")
        val receiver = app.newElement("receiver")
        receiver.getOrCreateAndroidAttribute("name", 0x01010003).setValueAsString(".BootReceiver")
        receiver.getOrCreateAndroidAttribute("exported", 0x01010010).setValueAsBoolean(true)
        // 未声明 exported 的私有 activity：入口分析必须如实给 null，不能猜成导出
        app.newElement("activity").getOrCreateAndroidAttribute("name", 0x01010003).setValueAsString(".SecretActivity")
        xml.refreshFull(); return xml.bytes
    }

    private fun appSmali(className: String = appClass, isFinal: Boolean = false): String {
        val modifiers = if (isFinal) "final " else ""
        val type = "L" + className.replace('.', '/') + ";"
        return ".class public " + modifiers + type + "\n" +
            ".super Landroid/app/Application;\n" +
            ".method public constructor <init>()V\n    .locals 0\n" +
            "    invoke-direct {p0}, Landroid/app/Application;-><init>()V\n    return-void\n.end method\n"
    }

    private fun dex(smali: String): ByteArray {
        val source = File(temp.root, "entry-" + ToolkitIo.fresh() + ".smali").apply { writeText(smali, Charsets.UTF_8) }
        val output = File(temp.root, "entry-" + ToolkitIo.fresh() + ".dex")
        assertTrue("smali assemble failed", Smali.assemble(SmaliOptions().apply { outputDexFile = output.path; jobs = 1 }, listOf(source.path)))
        val bytes = output.readBytes(); source.delete(); output.delete(); return bytes
    }

    private fun apk(manifestBytes: ByteArray, classes: ByteArray?, libraries: Map<String, ByteArray> = emptyMap()): File {
        val file = File(temp.root, "entry-" + ToolkitIo.fresh() + ".apk")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(name: String, data: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() }
            put("AndroidManifest.xml", manifestBytes)
            if (classes != null) put("classes.dex", classes)
            put("resources.arsc", byteArrayOf(9, 8, 7))
            libraries.forEach { (name, data) -> put(name, data) }
        }
        return file
    }

    // ------------------------------------------------------------------ ELF 夹具

    /**
     * 合成 ELF64（小端、AArch64）：1 个 PT_LOAD + .dynstr/.dynsym/.dynamic(可选 .init_array)/.shstrtab。
     * room=true 时 .dynstr 尾部补 NUL 余量（>=18），使 ToolkitElfNeeded 能就地加 DT_NEEDED。
     */
    private fun so(soname: String, needed: List<String>, room: Boolean = true, jniOnLoad: Boolean = true, initArray: Boolean = false): ByteArray {
        val dynstrText = StringBuilder()
        fun intern(value: String): Int { val offset = dynstrText.length; dynstrText.append(value).append('\u0000'); return offset }
        val neededOffsets = needed.map { intern(it) }
        val sonameOffset = intern(soname)
        val jniOffset = if (jniOnLoad) intern("JNI_OnLoad") else -1
        if (room) while (dynstrText.length < 128) dynstrText.append('\u0000')
        val dynstr = dynstrText.toString().toByteArray(Charsets.US_ASCII)
        val dynstrOffset = 128
        val dynsymOffset = align(dynstrOffset + dynstr.size, 8)
        val dynsymSize = 48
        val dynamicOffset = dynsymOffset + dynsymSize
        val dynamicCount = needed.size + 6
        val dynamicSize = dynamicCount * 16
        val initArrayOffset = dynamicOffset + dynamicSize
        val initArraySize = if (initArray) 8 else 0
        val shstrOffset = align(initArrayOffset + initArraySize, 8)
        val shstrBytes = "\u0000.dynstr\u0000.dynsym\u0000.dynamic\u0000.init_array\u0000.shstrtab\u0000".toByteArray(Charsets.US_ASCII)
        val sections = 5 + (if (initArray) 1 else 0)
        val shoff = align(shstrOffset + shstrBytes.size, 8)
        val total = shoff + sections * 64
        val bytes = ByteArray(total)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        bytes[0] = 0x7f; bytes[1] = 'E'.code.toByte(); bytes[2] = 'L'.code.toByte(); bytes[3] = 'F'.code.toByte()
        bytes[4] = 2; bytes[5] = 1; bytes[6] = 1
        buffer.putShort(16, 3); buffer.putShort(18, 183); buffer.putInt(20, 1)
        buffer.putLong(24, 0x1000); buffer.putLong(32, 64); buffer.putLong(40, shoff.toLong())
        buffer.putInt(48, 0); buffer.putShort(52, 64); buffer.putShort(54, 56); buffer.putShort(56, 1)
        buffer.putShort(58, 64); buffer.putShort(60, sections.toShort()); buffer.putShort(62, (sections - 1).toShort())
        buffer.putInt(64, 1); buffer.putInt(68, 5)
        buffer.putLong(72, 0); buffer.putLong(80, 0); buffer.putLong(88, 0)
        buffer.putLong(96, total.toLong()); buffer.putLong(104, total.toLong()); buffer.putLong(112, 0x4000)
        dynstr.copyInto(bytes, dynstrOffset)
        if (jniOnLoad) {
            val p = dynsymOffset + 24
            buffer.putInt(p, jniOffset); bytes[p + 4] = 0x12; buffer.putShort(p + 6, 1)
            buffer.putLong(p + 8, 0x1000); buffer.putLong(p + 16, 16)
        }
        var cursor = dynamicOffset
        fun dyn(tag: Long, value: Long) { buffer.putLong(cursor, tag); buffer.putLong(cursor + 8, value); cursor += 16 }
        neededOffsets.forEach { dyn(1L, it.toLong()) }
        dyn(14L, sonameOffset.toLong())
        dyn(5L, dynstrOffset.toLong())
        dyn(10L, dynstr.size.toLong())
        dyn(0L, 0L); dyn(0L, 0L); dyn(0L, 0L)
        if (initArray) buffer.putLong(initArrayOffset, 0x1000L)
        shstrBytes.copyInto(bytes, shstrOffset)
        fun nameOffsetOf(name: String): Int = shstrBytes.toString(Charsets.US_ASCII).indexOf("\u0000" + name + "\u0000") + 1
        fun sh(index: Int, nameOffset: Int, type: Long, offset: Long, size: Long, link: Int, entrySize: Long) {
            val p = shoff + index * 64
            buffer.putInt(p, nameOffset); buffer.putInt(p + 4, type.toInt())
            buffer.putLong(p + 24, offset.toLong()); buffer.putLong(p + 32, size)
            buffer.putInt(p + 40, link); buffer.putLong(p + 48, 1); buffer.putLong(p + 56, entrySize)
        }
        var index = 1
        sh(index++, nameOffsetOf(".dynstr"), 3, dynstrOffset.toLong(), dynstr.size.toLong(), 0, 0)
        sh(index++, nameOffsetOf(".dynsym"), 11, dynsymOffset.toLong(), dynsymSize.toLong(), 1, 24)
        sh(index++, nameOffsetOf(".dynamic"), 6, dynamicOffset.toLong(), dynamicSize.toLong(), 1, 16)
        if (initArray) sh(index++, nameOffsetOf(".init_array"), 14, initArrayOffset.toLong(), 8, 0, 8)
        sh(index, nameOffsetOf(".shstrtab"), 3, shstrOffset.toLong(), shstrBytes.size.toLong(), 0, 0)
        return bytes
    }

    private fun align(value: Int, to: Int) = (value + to - 1) / to * to

    // ------------------------------------------------------------------ 1) 组件与入口

    @Test fun applicationLauncherAliasAndExportedComponentsAreReported() {
        val input = apk(manifest(), dex(appSmali()), mapOf("lib/$abi/libnative.so" to so("libnative.so", listOf("libc.so"))))
        val result = ToolkitEntryAnalysis.analyze(input, abi)

        assertEquals(packageName, result.get("package").asString)
        val application = result.getAsJsonObject("application")
        assertEquals(appClass, application.get("class").asString)
        assertEquals("manifest", application.get("classSource").asString)
        assertFalse(application.get("appComponentFactoryDeclared").asBoolean)
        assertEquals("high", application.get("confidence").asString)

        assertEquals(2, result.get("launcherCount").asInt)
        val launcher = result.getAsJsonArray("launcher")
        assertEquals("activity", launcher[0].asJsonObject.get("kind").asString)
        assertEquals(packageName + ".Main", launcher[0].asJsonObject.get("name").asString)
        assertEquals(":script", launcher[0].asJsonObject.get("process").asString)
        assertTrue(launcher[0].asJsonObject.get("launcher").asBoolean)
        assertEquals("activity-alias", launcher[1].asJsonObject.get("kind").asString)
        assertEquals(".MainAlias", launcher[1].asJsonObject.get("declaredName").asString)
        assertEquals(".Main", launcher[1].asJsonObject.get("targetActivityDeclared").asString)
        assertEquals(packageName + ".Main", launcher[1].asJsonObject.get("targetActivity").asString)

        val exported = result.getAsJsonArray("exportedComponents").map { it.asJsonObject }
        val names = exported.map { it.get("name").asString }
        assertTrue(names.contains(packageName + ".Main"))
        assertTrue(names.contains(packageName + ".MainAlias"))
        assertTrue(names.contains(packageName + ".SyncService"))
        assertTrue(names.contains(packageName + ".BootReceiver"))
        assertFalse("未声明 exported 的组件不能被猜成导出", names.contains(packageName + ".SecretActivity"))
        assertEquals(packageName + ".remote", exported.first { it.get("name").asString.endsWith(".SyncService") }.get("process").asString)
        assertTrue(result.get("componentNote").asString.contains("未显式声明"))
        assertEquals(5, result.getAsJsonObject("componentCounts").get("total").asInt)
    }

    // ------------------------------------------------------------------ 2) appComponentFactory 两态

    @Test fun appComponentFactoryBlocksClinitAndFallsBackToProvider() {
        val unpatchable = mapOf("lib/$abi/libnative.so" to so("libnative.so", listOf("libc.so"), room = false))
        // 态 A：未声明 appComponentFactory + Application 可继承 -> clinit
        val withoutFactory = ToolkitEntryAnalysis.analyze(apk(manifest(), dex(appSmali()), unpatchable), abi)
        val good = withoutFactory.getAsJsonObject("preloadRecommendation")
        assertEquals("clinit", good.get("mode").asString)
        assertTrue(good.get("earlyLoad").asBoolean)
        assertTrue(good.get("reasonSource").asString.contains("assessClinit"))
        assertEquals("medium", good.get("confidence").asString)

        // 态 B：声明 appComponentFactory -> clinit 明确不可用 -> provider
        val withFactory = ToolkitEntryAnalysis.analyze(
            apk(manifest(appComponentFactory = "com.example.Factory"), dex(appSmali()), unpatchable), abi)
        val fallback = withFactory.getAsJsonObject("preloadRecommendation")
        assertEquals("provider", fallback.get("mode").asString)
        assertFalse(fallback.get("earlyLoad").asBoolean)
        assertTrue(fallback.get("reason").asString.contains("appComponentFactory"))
        val clinit = fallback.getAsJsonObject("modes").getAsJsonObject("clinit")
        assertFalse(clinit.get("available").asBoolean)
        assertEquals("PRELOAD_CLINIT_UNSUPPORTED_APPLICATION", clinit.get("failureCode").asString)
        assertTrue(withFactory.getAsJsonObject("application").get("appComponentFactoryDeclared").asBoolean)
        assertTrue(withFactory.getAsJsonObject("application").get("appComponentFactoryImpact").asString.contains("不可靠"))
    }

    // ------------------------------------------------------------------ 3) 与注入器同源

    @Test fun preloadRecommendationIsSameSourceAsApkInjectGadget() {
        val input = apk(manifest(), dex(appSmali()), mapOf("lib/$abi/libnative.so" to so("libnative.so", listOf("libc.so"))))
        val result = ToolkitEntryAnalysis.analyze(input, abi)
        val recommendation = result.getAsJsonObject("preloadRecommendation")
        val modes = recommendation.getAsJsonObject("modes")

        val plan = ToolkitGadgetPreload.plan(input, null, abi)
        assertEquals("dt_needed 预检必须与 ToolkitGadgetPreload.plan 同结论",
            plan.json.get("applied").asBoolean, modes.getAsJsonObject("dt_needed").get("available").asBoolean)
        if (plan.json.get("applied").asBoolean) {
            assertEquals("dt_needed", recommendation.get("mode").asString)
            assertEquals(plan.targetEntry, modes.getAsJsonObject("dt_needed").get("targetPath").asString)
        }
        val assessment = ToolkitGadget.assessClinit(input, null)
        assertEquals("clinit 预检必须与 ToolkitGadget.assessClinit 同结论",
            assessment.available, modes.getAsJsonObject("clinit").get("available").asBoolean)
        assertEquals(assessment.failureCode,
            modes.getAsJsonObject("clinit").get("failureCode").takeIf { it.isJsonPrimitive }?.asString)
        assertTrue(recommendation.get("checkedAgainstSameFunctionsAsApkInjectGadget").asBoolean)
        assertTrue(recommendation.get("reasonSource").asString.contains("ToolkitGadgetPreload.plan") ||
            recommendation.get("reasonSource").asString.contains("ToolkitGadget.assessClinit"))
        assertEquals(false, recommendation.get("runtimeVerified").asBoolean)
        assertTrue(recommendation.get("advice").asString.contains("PRELOAD_"))
    }

    @Test fun unusableClinitParentFallsBackToProviderWithReason() {
        // Application 不在 DEX 集里（壳加密/动态加载的典型形态）
        val input = apk(manifest(applicationName = ".Missing"), dex(appSmali()), emptyMap())
        val result = ToolkitEntryAnalysis.analyze(input, abi)
        val recommendation = result.getAsJsonObject("preloadRecommendation")
        assertEquals("provider", recommendation.get("mode").asString)
        val clinit = recommendation.getAsJsonObject("modes").getAsJsonObject("clinit")
        assertEquals("PRELOAD_CLINIT_UNSUPPORTED_APPLICATION", clinit.get("failureCode").asString)
        assertTrue(clinit.get("failureMessage").asString.contains("找不到"))
        assertFalse(clinit.get("parentClassUsable").asBoolean)
        assertTrue(recommendation.getAsJsonObject("modes").getAsJsonObject("provider").get("available").asBoolean)
    }

    // ------------------------------------------------------------------ 4) native 线索

    @Test fun nativeHintsReportJniOnLoadSonameAndNeeded() {
        val input = apk(manifest(), dex(appSmali()), mapOf(
            "lib/$abi/libnative.so" to so("libnative.so", listOf("libc.so", "libm.so")),
            "lib/$abi/libdata.so" to so("libdata.so", emptyList(), room = false, jniOnLoad = false),
        ))
        val result = ToolkitEntryAnalysis.analyze(input, abi)
        val summary = result.getAsJsonObject("nativeLibraries")
        assertEquals(2, summary.get("total").asInt)
        assertEquals(1, summary.get("withJniOnLoad").asInt)
        assertEquals("jni_onload_found", summary.get("verdict").asString)
        assertFalse(summary.get("truncated").asBoolean)

        val hints = result.getAsJsonArray("nativeEntryHints")
        val first = hints[0].asJsonObject
        assertEquals("lib/$abi/libnative.so", first.get("path").asString)
        assertTrue(first.get("isPreferredAbi").asBoolean)
        assertTrue(first.getAsJsonObject("jniOnLoad").get("found").asBoolean)
        assertEquals("libnative.so", first.get("soname").asString)
        assertEquals(listOf("libc.so", "libm.so"), first.getAsJsonArray("needed").map { it.asString })
        assertEquals("high", first.get("confidence").asString)
        assertTrue(first.getAsJsonArray("evidence").joinToString(" ") { it.asString }.contains("JNI_OnLoad"))
        // 没有 JNI_OnLoad 的库仍然如实列出，只是 confidence 更低
        assertEquals("lib/$abi/libdata.so", hints[1].asJsonObject.get("path").asString)
        assertEquals("low", hints[1].asJsonObject.get("confidence").asString)
        assertTrue(result.get("nativeNote").asString.contains("不等于没有 native 初始化"))
    }

    @Test fun brokenNativeLibraryIsReportedInsteadOfGuessed() {
        val input = apk(manifest(), dex(appSmali()), mapOf("lib/$abi/libbroken.so" to byteArrayOf(1, 2, 3, 4)))
        val result = ToolkitEntryAnalysis.analyze(input, abi)
        val hint = result.getAsJsonArray("nativeEntryHints")[0].asJsonObject
        assertEquals("lib/$abi/libbroken.so", hint.get("path").asString)
        assertEquals("low", hint.get("confidence").asString)
        assertNotNull(hint.get("parseError"))
        assertTrue(hint.getAsJsonArray("evidence")[0].asString.contains("解析失败"))
        assertEquals(0, result.getAsJsonObject("nativeLibraries").get("withJniOnLoad").asInt)
        assertEquals("no_static_jni_onload_found", result.getAsJsonObject("nativeLibraries").get("verdict").asString)
    }

    // ------------------------------------------------------------------ 5) 结论分层与诚实边界

    @Test fun staticConclusionsAndDynamicGapsAreSeparated() {
        val input = apk(manifest(applicationName = ".Missing"), dex(appSmali()))
        val result = ToolkitEntryAnalysis.analyze(input, abi)
        assertFalse("runtimeVerified 必须恒为 false", result.get("runtimeVerified").asBoolean)
        assertTrue(result.getAsJsonArray("staticConclusions").size() >= 4)
        assertTrue(result.getAsJsonArray("needsDynamicConfirmation").size() >= 4)
        assertTrue(result.getAsJsonArray("evidenceSources").size() >= 4)
        assertTrue(result.getAsJsonArray("notes").joinToString(" ") { it.asString }.contains("静态分析"))
        assertTrue(result.getAsJsonObject("limits").getAsJsonArray("notScanned").size() >= 4)
        assertTrue(result.getAsJsonObject("limits").getAsJsonArray("notScanned").joinToString(" ") { it.asString }
            .contains("System.loadLibrary"))
        assertTrue(result.getAsJsonArray("needsDynamicConfirmation").joinToString(" ") { it.asString }
            .contains("壳"))
    }
}
