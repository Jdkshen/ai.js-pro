package com.jdkshen.aijspro.mcp

import com.reandroid.arsc.chunk.xml.ResXmlDocument
import com.reandroid.arsc.chunk.xml.ResXmlElement
import org.jf.smali.Smali
import org.jf.smali.SmaliOptions
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * preloadMode=clinit（P1-4 的另一半）：只追加一个 Application 子类 DEX + 只改 application@name。
 *
 * 覆盖：新类内容（.super / loadLibrary / .catch Throwable）、Manifest **只差一个属性**、
 * 既有条目零漂移、三条失败码且失败不产出产物、provider/dt_needed 的诚实字段不被改坏。
 * 真机时序（Frida: Listening 早于 attachBaseContext）不在 JVM 单测范围内。
 */
class ToolkitGadgetClinitTest {
    @get:Rule val temp = TemporaryFolder()

    private val packageName = "com.example.gadgettest"
    private val appClass = "com.example.gadgettest.ExistingApplication"

    private fun elf(abi: String): ByteArray {
        val bytes = ByteArray(256); val b = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val wide = abi in setOf("arm64-v8a", "x86_64")
        bytes[0] = 0x7f; bytes[1] = 69; bytes[2] = 76; bytes[3] = 70; bytes[4] = if (wide) 2 else 1; bytes[5] = 1
        b.putShort(18, mapOf("arm64-v8a" to 183, "armeabi-v7a" to 40, "x86" to 3, "x86_64" to 62).getValue(abi).toShort())
        if (wide) { b.putLong(32, 64); b.putShort(54, 56); b.putShort(56, 1); b.putInt(64, 1); b.putLong(112, 16384L) }
        else { b.putInt(28, 64); b.putShort(42, 32); b.putShort(44, 1); b.putInt(64, 1); b.putInt(92, 4096) }
        return bytes
    }

    private val component by lazy {
        temp.newFolder("component").apply {
            ToolkitGadget.ABIS.forEach { abi ->
                File(this, "gadget/" + abi + "/libfrida-gadget.so").apply { parentFile?.mkdirs(); writeBytes(elf(abi)) }
            }
        }
    }

    private fun manifest(applicationName: String? = ".ExistingApplication",
                         appComponentFactory: String? = null,
                         sharedUserId: String? = null): ByteArray {
        val xml = ResXmlDocument(); val root = xml.newElement("manifest")
        root.getOrCreateAttribute("package", 0).setValueAsString(packageName)
        root.getOrCreateNamespace("http://schemas.android.com/apk/res/android", "android")
        if (sharedUserId != null) root.getOrCreateAndroidAttribute("sharedUserId", 0x0101000b).setValueAsString(sharedUserId)
        val app = root.newElement("application")
        app.getOrCreateAndroidAttribute("label", 0x01010001).setValueAsString("gadgettest")
        if (applicationName != null) app.getOrCreateAndroidAttribute("name", 0x01010003).setValueAsString(applicationName)
        if (appComponentFactory != null) app.getOrCreateAndroidAttribute("appComponentFactory", 0x0101057a).setValueAsString(appComponentFactory)
        app.newElement("activity").getOrCreateAndroidAttribute("name", 0x01010003).setValueAsString(".Main")
        xml.refreshFull(); return xml.bytes
    }

    private fun dex(smali: String): ByteArray {
        val source = File(temp.root, "fx-" + ToolkitIo.fresh() + ".smali").apply { writeText(smali, Charsets.UTF_8) }
        val output = File(temp.root, "fx-" + ToolkitIo.fresh() + ".dex")
        assertTrue("smali assemble failed", Smali.assemble(SmaliOptions().apply { outputDexFile = output.path; jobs = 1 }, listOf(source.path)))
        val bytes = output.readBytes(); source.delete(); output.delete(); return bytes
    }

    private fun applicationSmali(className: String = appClass, isFinal: Boolean = false): String {
        val modifiers = if (isFinal) "final " else ""
        val type = "L" + className.replace('.', '/') + ";"
        return ".class public " + modifiers + type + "\n" +
            ".super Landroid/app/Application;\n" +
            ".method public constructor <init>()V\n" +
            "    .locals 0\n" +
            "    invoke-direct {p0}, Landroid/app/Application;-><init>()V\n" +
            "    return-void\n" +
            ".end method\n"
    }

    private fun apk(manifestBytes: ByteArray, classesDex: ByteArray?): File =
        File(temp.root, "in-" + ToolkitIo.fresh() + ".apk").apply {
            ZipOutputStream(outputStream()).use { zip ->
                val contents = linkedMapOf<String, ByteArray>()
                contents["AndroidManifest.xml"] = manifestBytes
                if (classesDex != null) contents["classes.dex"] = classesDex
                contents["resources.arsc"] = byteArrayOf(9, 8, 7)
                contents["res/values/strings.xml"] = "<resources/>".toByteArray()
                contents["assets/原文.txt"] = "音乐".toByteArray()
                contents["META-INF/CERT.RSA"] = byteArrayOf(6, 5)
                contents.forEach { (name, data) -> zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() }
            }
        }

    /**
     * 把 AXML 展平成 "E|路径|元素名" / "A|路径|属性名=值" 两行式清单，用于**属性级**比对。
     * 刻意不用 ToolkitResources.decodeXml(裸字节)：ARSCLib 的 serialize 需要 package 上下文，
     * 裸 Manifest 字节会抛 "Can not decode without package"。
     */
    private fun flatten(bytes: ByteArray, applicationNameToken: String?): List<String> {
        val xml = ResXmlDocument().apply { readBytes(ByteArrayInputStream(bytes)) }
        val root = xml.documentElement ?: error("Manifest根节点不存在")
        val out = ArrayList<String>()
        fun visit(element: ResXmlElement, path: String) {
            out.add("E|" + path + "|" + element.name)
            for (index in 0 until element.attributeCount) {
                val attribute = element.getAttributeAt(index)
                val name = attribute.name
                // 路径形如 "manifest/1:application"，所以按元素名后缀判断，而不是整串相等
                val value = if (applicationNameToken != null && path.endsWith(":application") && name == "name") {
                    applicationNameToken
                } else {
                    attribute.valueAsString
                }
                out.add("A|" + path + "|" + name + "=" + value)
            }
            var order = 0
            val children = element.elements
            while (children.hasNext()) {
                val child = children.next()
                visit(child, path + "/" + order + ":" + child.name)
                order++
            }
        }
        visit(root, "manifest")
        return out
    }

    private fun entries(file: File): Map<String, ByteArray> = ZipFile(file).use { zip ->
        zip.entries().asSequence().filter { !it.isDirectory }
            .associate { it.name to zip.getInputStream(it).use { s -> ToolkitIo.read(s, ToolkitIo.MAX_ENTRY) } }
    }

    // ---------------------------------------------------------------- 1) 成功路径 + 零漂移

    @Test fun clinitHookKeepsEveryExistingEntryByteIdentical() {
        val input = apk(manifest(), dex(applicationSmali()))
        val scratch = temp.newFolder("scratch")
        val hook = ToolkitGadget.prepareClinitHook(input, scratch, null)
        assertEquals(ToolkitGadget.CLINIT_CLASS, hook.className)
        assertEquals(appClass, hook.originalApplication)
        assertFalse("clinit 临时 smali/dex 必须清掉", File(scratch, "aijs-clinit-application.dex").exists())

        val output = File(temp.root, "out-" + ToolkitIo.fresh() + ".apk")
        ToolkitGadget.prepare(input, output, scratch, component, 27042, "arm64-v8a", clinit = hook)

        val before = entries(input); val after = entries(output)
        for ((name, bytes) in before) {
            if (name == "AndroidManifest.xml" || ToolkitGadget.signatureEntry(name)) continue
            assertArrayEquals("既有条目被改动：" + name, bytes, after[name])
        }
        assertArrayEquals("资源表被改动", byteArrayOf(9, 8, 7), after["resources.arsc"])
        assertArrayEquals("原 classes.dex 被改动", before["classes.dex"], after["classes.dex"])
        assertNotNull(after["classes2.dex"]); assertNotNull(after["classes3.dex"])
        assertNotNull(after[ToolkitGadget.MARKER])
        assertTrue(after.containsKey("lib/arm64-v8a/libaijsgadget.so"))

        val details = ToolkitGadget.readApplicationDetails(after["AndroidManifest.xml"]!!)
        assertEquals(packageName, details.packageName)
        assertEquals(ToolkitGadget.CLINIT_CLASS, details.applicationName)

        val rendered = ToolkitDex(hook.dex).render("dex_class:L" + ToolkitGadget.CLINIT_CLASS.replace('.', '/') + ";")
        assertTrue(rendered, rendered.contains(".super L" + appClass.replace('.', '/') + ";"))
        assertTrue(rendered, rendered.contains("Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V"))
        assertTrue(rendered, rendered.contains("aijsgadget"))
        assertTrue(rendered, rendered.contains(".catch Ljava/lang/Throwable;"))
        assertTrue(rendered, rendered.contains("Landroid/util/Log;->e"))
    }

    // ---------------------------------------------------------------- 2) Manifest 只差一个属性

    @Test fun clinitOnlyChangesTheApplicationNameAttribute() {
        val original = manifest()
        val providerOnly = ToolkitGadget.patchManifest(original, "com.example.GadgetProvider", "sfx1", null)
        val withClinit = ToolkitGadget.patchManifest(original, "com.example.GadgetProvider", "sfx1", null, ToolkitGadget.CLINIT_CLASS)

        assertEquals(".ExistingApplication", ToolkitGadget.readApplicationDetails(providerOnly.bytes).applicationName)
        assertEquals(ToolkitGadget.CLINIT_CLASS, ToolkitGadget.readApplicationDetails(withClinit.bytes).applicationName)
        assertEquals(appClass, ToolkitGadget.expandClassName(packageName, withClinit.originalApplication!!))
        assertEquals(packageName, withClinit.packageName)

        // 判据不变：除 application@name 外，元素/属性逐项相等。
        val rawLeft = flatten(providerOnly.bytes, null)
        val rawRight = flatten(withClinit.bytes, null)
        assertEquals("元素/属性项数必须一致", rawLeft.size, rawRight.size)
        val differing = rawLeft.indices.filter { rawLeft[it] != rawRight[it] }
        assertEquals("只允许 application@name 一项不同，实际：$differing", 1, differing.size)
        assertTrue(rawLeft[differing[0]], rawLeft[differing[0]].endsWith("name=.ExistingApplication"))
        assertTrue(rawRight[differing[0]], rawRight[differing[0]].endsWith("name=" + ToolkitGadget.CLINIT_CLASS))
        // 把 application@name 归一化成同一个 token 后，两份必须逐项完全相等
        assertEquals(flatten(providerOnly.bytes, "APPNAME"), flatten(withClinit.bytes, "APPNAME"))
        assertTrue("两个版本都应保留 provider/uses-permission/uses-sdk", rawLeft.any { it.contains("provider") })
    }

    // ---------------------------------------------------------------- 3) 失败码 + 不产出产物

    @Test fun clinitRejectsFinalApplication() {
        val input = apk(manifest(), dex(applicationSmali(isFinal = true)))
        val failure = assertThrows(ToolkitException::class.java) { ToolkitGadget.prepareClinitHook(input, temp.newFolder("s1"), null) }
        assertEquals("PRELOAD_CLINIT_UNSUPPORTED_APPLICATION", failure.code)
        assertTrue(failure.message!!, failure.message!!.contains("final"))
    }

    @Test fun clinitRejectsAppComponentFactory() {
        val input = apk(manifest(appComponentFactory = "com.example.Factory"), dex(applicationSmali()))
        val failure = assertThrows(ToolkitException::class.java) { ToolkitGadget.prepareClinitHook(input, temp.newFolder("s2"), null) }
        assertEquals("PRELOAD_CLINIT_UNSUPPORTED_APPLICATION", failure.code)
        assertTrue(failure.message!!, failure.message!!.contains("appComponentFactory"))
    }

    @Test fun clinitRejectsApplicationMissingFromDexSet() {
        val input = apk(manifest(applicationName = ".OtherApplication"), dex(applicationSmali()))
        val failure = assertThrows(ToolkitException::class.java) { ToolkitGadget.prepareClinitHook(input, temp.newFolder("s3"), null) }
        assertEquals("PRELOAD_CLINIT_UNSUPPORTED_APPLICATION", failure.code)
        assertTrue(failure.message!!, failure.message!!.contains("找不到"))
    }

    @Test fun clinitRejectsUnparseableDexInsteadOfGuessing() {
        val input = apk(manifest(), byteArrayOf(1, 2, 3))
        val failure = assertThrows(ToolkitException::class.java) { ToolkitGadget.prepareClinitHook(input, temp.newFolder("s4"), null) }
        assertEquals("PRELOAD_CLINIT_UNSUPPORTED_APPLICATION", failure.code)
    }

    @Test fun clinitRejectsTargetThatIsOurGeneratedClass() {
        val input = apk(manifest(), dex(applicationSmali()))
        val failure = assertThrows(ToolkitException::class.java) {
            ToolkitGadget.prepareClinitHook(input, temp.newFolder("s5"), ToolkitGadget.CLINIT_CLASS)
        }
        assertEquals("PRELOAD_CLINIT_UNSUPPORTED_APPLICATION", failure.code)
    }

    @Test fun clinitPlanReportsFailureWithoutAnyHookOrApk() {
        val input = apk(manifest(), dex(applicationSmali(isFinal = true)))
        val scratch = temp.newFolder("s6")
        val plan = ToolkitGadgetPreload.planClinit(input, scratch, null)
        assertFalse(plan.applied)
        assertNull(plan.hook)
        assertEquals("clinit", plan.json.get("mode").asString)
        assertEquals("PRELOAD_CLINIT_UNSUPPORTED_APPLICATION", plan.json.get("reason").asString)
        assertEquals("prepare_clinit_hook", plan.json.get("stage").asString)
        assertFalse(plan.json.get("earlyLoad").asBoolean)
        assertTrue(plan.json.get("reasons").asJsonArray.size() > 0)
        assertTrue(plan.json.get("detail").asString.contains("未产出任何 APK"))
        assertEquals("该阶段不应留下任何中间文件", 0, scratch.listFiles()?.size ?: 0)
    }

    @Test fun clinitManifestPatchFailureHasItsOwnCode() {
        val input = apk(manifest(sharedUserId = "com.example.shared"), dex(applicationSmali()))
        val scratch = temp.newFolder("s7")
        val hook = ToolkitGadget.prepareClinitHook(input, scratch, null)
        val output = File(temp.root, "never-" + ToolkitIo.fresh() + ".apk")
        val failure = assertThrows(ToolkitException::class.java) {
            ToolkitGadget.prepare(input, output, scratch, component, 27042, "arm64-v8a", clinit = hook)
        }
        assertEquals("PRELOAD_CLINIT_MANIFEST_PATCH_FAILED", failure.code)
        assertFalse("失败不能产出 APK", output.exists())
    }

    // ---------------------------------------------------------------- 4) assessment 诚实性回归

    @Test fun assessmentKeepsProviderHonestAndMarksClinitImplemented() {
        val provider = ToolkitGadgetPreload.assessment("provider")
        assertEquals("provider_only", provider.get("status").asString)
        assertFalse(provider.get("preloadImplemented").asBoolean)
        assertFalse(provider.get("earlyLoad").asBoolean)

        val dt = ToolkitGadgetPreload.assessment("dt_needed")
        assertEquals("dt_needed_available", dt.get("status").asString)
        assertFalse("dt_needed 的评估字段不能被改坏", dt.get("preloadImplemented").asBoolean)
        assertFalse(dt.get("earlyLoad").asBoolean)
        assertTrue(dt.getAsJsonObject("dtNeeded").get("status").asString.contains("available"))

        val clinit = ToolkitGadgetPreload.assessment("clinit")
        assertEquals("clinit_available", clinit.get("status").asString)
        assertTrue(clinit.get("preloadImplemented").asBoolean)
        assertTrue(clinit.get("earlyLoad").asBoolean)
        assertEquals("clinit", clinit.get("effectiveMode").asString)
        // classInitializer 必须在**顶层**可读（客户端 assessment()["classInitializer"] 不再拿到 null）
        val initializer = clinit.getAsJsonObject("classInitializer")
        assertTrue(initializer.get("implemented").asBoolean)
        assertEquals("available", initializer.get("status").asString)
        assertTrue(initializer.get("blockers").asJsonArray.size() >= 3)
        assertEquals(ToolkitGadget.CLINIT_CLASS, initializer.get("clinitClass").asString)
        assertEquals("application_clinit_loadLibrary", initializer.get("method").asString)
        // provider 模式下顶层同样可读（能力评估与模式无关）
        assertTrue(provider.getAsJsonObject("classInitializer").get("implemented").asBoolean)
        // 历史位置 dtNeeded.classInitializer 仍在（dev-1413/1414 的读取路径向后兼容）
        val legacy = dt.getAsJsonObject("dtNeeded").getAsJsonObject("classInitializer")
        assertTrue(legacy.get("implemented").asBoolean)
        assertEquals(ToolkitGadget.CLINIT_CLASS, legacy.get("clinitClass").asString)

        assertEquals("INVALID_PRELOAD_MODE", ToolkitGadgetPreload.assessment("bogus").get("status").asString)
    }
}
