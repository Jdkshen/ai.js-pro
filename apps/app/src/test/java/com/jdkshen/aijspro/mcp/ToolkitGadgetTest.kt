package com.jdkshen.aijspro.mcp

import com.reandroid.arsc.chunk.xml.ResXmlDocument
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ToolkitGadgetTest {
    @get:Rule val temp = TemporaryFolder()
    private val component by lazy { temp.newFolder("component").apply {
        ToolkitGadget.ABIS.forEach { abi -> File(this,"gadget/$abi/libfrida-gadget.so").apply { parentFile?.mkdirs();writeBytes(elf(abi)) } }
    } }
    private fun elf(abi:String,alignment:Int=16384):ByteArray {
        val bytes=ByteArray(256);val b=java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val wide=abi in setOf("arm64-v8a","x86_64")
        bytes[0]=0x7f;bytes[1]=69;bytes[2]=76;bytes[3]=70;bytes[4]=if(wide)2 else 1;bytes[5]=1
        b.putShort(18,mapOf("arm64-v8a" to 183,"armeabi-v7a" to 40,"x86" to 3,"x86_64" to 62).getValue(abi).toShort())
        if(wide){b.putLong(32,64);b.putShort(54,56);b.putShort(56,1);b.putInt(64,1);b.putLong(112,alignment.toLong())}
        else{b.putInt(28,64);b.putShort(42,32);b.putShort(44,1);b.putInt(64,1);b.putInt(92,alignment)}
        return bytes
    }
    @Test fun validatesAllGadgetAbisAndDoesNotHideInvalidNativeLibraries() {
        ToolkitGadget.ABIS.forEach { ToolkitGadget.validateElf(elf(it),it);if(it in setOf("arm64-v8a","x86_64"))rejects { ToolkitGadget.validateElf(elf(it,4096),it) } else ToolkitGadget.validateElf(elf(it,4096),it) }
        val config="""{"interaction":{"type":"listen","port":27042}}""".toByteArray()
        assertTrue(ToolkitGadget.isConfiguration("lib/arm64-v8a/libaijsgadget.config.so",config))
        assertFalse(ToolkitGadget.isConfiguration("lib/arm64-v8a/libother.so",config))
        assertFalse(ToolkitGadget.isConfiguration("lib/arm64-v8a/libaijsgadget.config.so","bad".toByteArray()))
    }
    private fun manifest(extra:String="",appExtra:String=""):ByteArray {
        val xml=ResXmlDocument();val root=xml.newElement("manifest")
        root.getOrCreateAttribute("package",0).setValueAsString("com.example.gadgettest")
        root.getOrCreateNamespace("http://schemas.android.com/apk/res/android","android")
        val app=root.newElement("application")
        app.getOrCreateAndroidAttribute("name",0x01010003).setValueAsString(".ExistingApplication")
        app.newElement("activity").getOrCreateAndroidAttribute("name",0x01010003).setValueAsString(".Main")
        fun extras(element:com.reandroid.arsc.chunk.xml.ResXmlElement,text:String) {
            val ids=mapOf("sharedUserId" to 0x0101000b,"isSplitRequired" to 0x01010591,"label" to 0x01010001,"extractNativeLibs" to 0x010104ea)
            Regex("""([A-Za-z:]+)=["]([^"]*)["]""").findAll(text).forEach { match ->
                val name=match.groupValues[1];val value=match.groupValues[2]
                val attribute=if(name.startsWith("android:"))element.getOrCreateAndroidAttribute(name.substringAfter(':'),ids.getValue(name.substringAfter(':'))) else element.getOrCreateAttribute(name,0)
                when {value=="true"||value=="false"->attribute.setValueAsBoolean(value=="true")
                    value.startsWith("@0x")-> {attribute.setType(1.toByte());attribute.data=value.substring(3).toLong(16).toInt()}
                    else->attribute.setValueAsString(value)}
            }
        }
        extras(root,extra);extras(app,appExtra);xml.refreshFull();return xml.bytes
    }
    private fun fixture(bytes:ByteArray=manifest(),native:List<String> = emptyList()):File = File(temp.root,"${ToolkitIo.fresh()}.apk").apply {
        ZipOutputStream(outputStream()).use { zip ->
            val contents=linkedMapOf("AndroidManifest.xml" to bytes,"classes.dex" to byteArrayOf(1,2,3),"resources.arsc" to byteArrayOf(9,8),"assets/原文.txt" to "音乐🙂".toByteArray(),"META-INF/LICENSE.txt" to byteArrayOf(7),"META-INF/OLD.RSA" to byteArrayOf(6))
            native.forEach { contents["lib/$it/libexisting.so"]=byteArrayOf(5,4,3) }
            contents.forEach { (name,data) -> zip.putNextEntry(ZipEntry(name));zip.write(data);zip.closeEntry() }
        }
    }
    private fun rejects(block:()->Unit) { try { block();fail("expected rejection") } catch (_:IllegalArgumentException) {} }
    @Test fun preservesOriginalResourcesCodeAndApplicationWhileAddingRootlessLoader() {
        val input=fixture(native=listOf("arm64-v8a","armeabi-v7a"));val before=ToolkitIo.sha(input)
        val out=File(temp.root,"result.apk");val scratch=temp.newFolder("scratch")
        val result=ToolkitGadget.prepare(input,out,scratch,component,27131,"arm64-v8a")
        assertFalse(result.tBool("requiresRoot",true));assertFalse(result.tBool("resourceTableRecompiled",true))
        assertEquals(before,ToolkitIo.sha(input));assertEquals("classes2.dex",result.tString("dexEntry"))
        assertEquals("127.0.0.1:27131",result.tString("endpoint"));assertEquals(48,result.tString("token").length)
        ZipFile(out).use { zip ->
            for(name in listOf("classes.dex","resources.arsc","assets/原文.txt","META-INF/LICENSE.txt")) {
                ZipFile(input).use { original -> assertArrayEquals(original.getInputStream(original.getEntry(name)).readBytes(),zip.getInputStream(zip.getEntry(name)).readBytes()) }
            }
            assertNull(zip.getEntry("META-INF/OLD.RSA"))
            val dex=ToolkitDex(zip.getInputStream(zip.getEntry("classes2.dex")).readBytes())
            assertEquals("L"+result.tString("providerClass").replace('.','/')+";",dex.classes.single().type)
            val xml=ResXmlDocument().apply { readBytes(zip.getInputStream(zip.getEntry("AndroidManifest.xml"))) }
            val app=xml.documentElement.getElement("application")
            assertEquals(".ExistingApplication",app.searchAttributeByName("name").valueAsString)
            assertTrue(app.searchAttributeByResourceId(0x010104ea).valueAsBoolean)
            assertFalse(app.getElement("provider").searchAttributeByResourceId(0x01010010).valueAsBoolean)
            for(abi in listOf("arm64-v8a","armeabi-v7a")) {
                assertNotNull(zip.getEntry("lib/$abi/libaijsgadget.so"))
                val config=com.google.gson.JsonParser().parse(zip.getInputStream(zip.getEntry("lib/$abi/libaijsgadget.config.so")).reader()).asJsonObject.getAsJsonObject("interaction")
                assertEquals("resume",config.tString("on_load"));assertEquals(result.tString("token"),config.tString("token"));assertEquals("127.0.0.1",config.tString("address"))
            }
        }
        rejects { ToolkitGadget.prepare(out,File(temp.root,"twice.apk"),scratch,component,27131,"arm64-v8a") }
    }
    @Test fun patchedNativeEntryBytesAreWrittenInPlace() {
        val input=fixture(native=listOf("arm64-v8a"))
        val patched=byteArrayOf(9,4,3)
        val out=File(temp.root,"patched.apk");val scratch=temp.newFolder("scratch-patched")
        ToolkitGadget.prepare(input,out,scratch,component,27131,"arm64-v8a",
            patchedEntries=mapOf("lib/arm64-v8a/libexisting.so" to patched))
        ZipFile(out).use { zip ->
            assertArrayEquals(patched,zip.getInputStream(zip.getEntry("lib/arm64-v8a/libexisting.so")).readBytes())
        }
    }

    @Test fun rejectsSplitsSharedUidUnsupportedAbiAndOverwriteBeforeOutput() {
        for(input in listOf(fixture(manifest("split=\"config.arm64\"")),fixture(manifest("android:sharedUserId=\"com.example.uid\"")),fixture(manifest(appExtra="android:isSplitRequired=\"true\"")),fixture(native=listOf("mips")))) {
            val output=File(temp.root,"rejected.apk")
            rejects { ToolkitGadget.prepare(input,output,temp.newFolder(),component,27042,"arm64-v8a") };assertFalse(output.exists())
        }
        val output=temp.newFile("keep.apk").apply { writeText("keep") }
        rejects { ToolkitGadget.prepare(fixture(),output,temp.newFolder(),component,27042,"arm64-v8a") };assertEquals("keep",output.readText())
    }
    @Test fun preservesBinaryResourceReferencesAndAvoidsExposingProvider() {
        val original=manifest(appExtra="android:label=\"@0x7f010001\" android:extractNativeLibs=\"false\"")
        val patched=ToolkitGadget.patchManifest(original,"com.example.Loader","abc123").first
        val xml=ResXmlDocument().apply { readBytes(ByteArrayInputStream(patched)) }
        val app=xml.documentElement.getElement("application")
        assertEquals(0x7f010001,app.searchAttributeByName("label").data)
        assertNull(app.getElement("provider").searchAttributeByName("process"))
        assertEquals(Int.MAX_VALUE,app.getElement("provider").searchAttributeByName("initOrder").data)
    }
    @Test fun scriptTargetIsAbsoluteAppExternalFilesPath() {
        val target=ToolkitGadget.scriptTargetPath("com.x浏览器","X浏览器-5.6.4/hook.js")
        val normalized=target.path.replace('\\','/')
        assertTrue(normalized.startsWith("/sdcard/Android/data/"))
        assertEquals("/sdcard/Android/data/com.x浏览器/files/aijs-gadget/hook.js",normalized)
        assertEquals("com.example.gadgettest",ToolkitGadget.packageName(fixture()))
    }
    @Test fun preloadAssessmentNeverClaimsEarlyLoadWasImplemented() {
        val provider=ToolkitGadgetPreload.assessment("provider")
        assertFalse(provider.tBool("preloadImplemented",true))
        assertFalse(provider.tBool("earlyLoad",true))
        assertEquals("provider_only",provider.tString("status"))
        assertTrue(provider.getAsJsonObject("dtNeeded").tString("status").contains("available"))
        val dt=ToolkitGadgetPreload.assessment("dt_needed")
        assertEquals("dt_needed_available",dt.tString("status"))
        assertFalse(dt.tBool("preloadImplemented",true))
    }

    @Test fun apkVerificationRecognizesConfigurationButStillRejectsMalformedNativeFiles() {
        for(valid in listOf(true,false)) {
            val apk=File(temp.root,"config-$valid.apk")
            ZipOutputStream(apk.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("AndroidManifest.xml"));zip.write(manifest());zip.closeEntry()
                zip.putNextEntry(ZipEntry("lib/arm64-v8a/libaijsgadget.config.so"))
                zip.write(if(valid)"""{"interaction":{"type":"listen","port":27042}}""".toByteArray() else "malformed".toByteArray());zip.closeEntry()
            }
            val native=ToolkitApkVerifier.inspect(apk).getAsJsonObject("native")
            assertEquals(if(valid)"valid" else "partial",native.tString("status"))
            if(valid)assertEquals("frida-gadget-configuration",native.getAsJsonArray("libraries")[0].asJsonObject.tString("kind"))
        }
    }
}
