package com.jdkshen.aijspro.mcp

import com.google.gson.*
import com.reandroid.arsc.chunk.TableBlock
import org.jf.smali.Smali
import org.jf.smali.SmaliOptions
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.*

class McpApkToolkitTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var home: File
    private lateinit var source: File
    private lateinit var tools: McpApkToolkit
    private lateinit var workspace: String
    private lateinit var session: String
    private fun args(vararg p: Pair<String,Any?>) = ToolkitIo.json(*p)
    private fun invoke(name: String, vararg p: Pair<String,Any?>): JsonObject {
        val input = args("workspaceId" to workspace, "editSessionId" to session)
        p.forEach { (k,v) -> input.add(k,Gson().toJsonTree(v)) }
        // 归置行为由专门用例覆盖；既有用例继续验证原路径语义。
        if (!input.has("organize")) input.addProperty("organize", false)
        return tools.call(name,input).getAsJsonObject("data")
    }
    private fun fails(block: () -> Unit) { try { block(); fail("Expected failure") } catch(expected: IllegalArgumentException) { } catch(expected: IllegalStateException) { } }
    @Before fun setup() {
        home = temp.newFolder("home"); val store = temp.newFolder("private")
        val schema = listOf(File("src/main/assets/mcp/toolkit-apk-schemas.json"), File("apps/app/src/main/assets/mcp/toolkit-apk-schemas.json")).first { it.isFile }
        tools = McpApkToolkit(home,store,JsonParser().parse(schema.readText()).asJsonObject)
        val table = TableBlock(); table.newPackage(0x7f,"com.example.fixture").getOrCreate("","string","title").setValueAsString("原值")
        table.refreshFull()
        val smali = temp.newFile("Test.smali"); val dex = File(temp.root,"classes.dex")
        smali.writeText(""".class public Lsample/Test;
.super Ljava/lang/Object;
.method public static value()I
    .registers 1
    const/4 v0, 0x1
    return v0
.end method
""")
        val secondSmali = temp.newFile("Second.smali")
        secondSmali.writeText(""".class public Lsample/Second;
.super Ljava/lang/Object;
""")
        assertTrue(Smali.assemble(SmaliOptions().apply { outputDexFile = dex.path; jobs = 1 },listOf(smali.path, secondSmali.path)))
        source = File(home,"fixture.apk")
        ZipOutputStream(source.outputStream()).use { zip ->
            mapOf("AndroidManifest.xml" to "<manifest package=\"com.example.fixture\"/>".toByteArray(), "assets/test.txt" to "中文\r\nsecond\r\n".toByteArray(),
                "resources.arsc" to table.bytes, "classes.dex" to dex.readBytes(), "lib/arm64-v8a/libfixture.so" to elf(), "META-INF/ORIGINAL.SF" to byteArrayOf(1)).forEach { (name,bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
        }
        workspace = tools.call("apk_workspace_open",args("path" to source.name,"temporary" to true)).getAsJsonObject("data").tString("workspaceId")
        session = tools.call("apk_session_create",args("workspaceId" to workspace)).getAsJsonObject("data").tString("editSessionId")
    }
    @Test fun artifactOpenAcceptsApkStructureWithoutStandardExtension() {
        // §5.2：真实目标可能叫 xxx.apk.1；结构判定优先于扩展名。
        val disguised = File(home, "影安加固-测试demo.apk.1")
        source.copyTo(disguised)
        val opened = tools.call("artifact_open", args("path" to disguised.name, "temporary" to true)).getAsJsonObject("data")
        assertTrue(opened.tString("workspaceId").isNotEmpty())

        // 扩展名像 APK 但结构不是：报结构原因，不要只说“不支持”。
        val fake = File(home, "fake.apk"); fake.writeText("not a zip at all")
        val fakeError = try { tools.call("artifact_open", args("path" to fake.name, "temporary" to true)); null }
            catch (failure: IllegalArgumentException) { failure.message ?: "" }
        assertNotNull(fakeError)
        assertTrue(fakeError!!.contains("不是 ZIP"))

        // 目录：给出有界扫描候选，而不是笼统失败。
        val scanDir = File(home, "scan-dir"); assertTrue(scanDir.mkdirs())
        source.copyTo(File(scanDir, "inner.apk"))
        val dirError = try { tools.call("artifact_open", args("path" to "scan-dir", "temporary" to true)); null }
            catch (failure: IllegalArgumentException) { failure.message ?: "" }
        assertNotNull(dirError)
        assertTrue(dirError!!.contains("目录"))
        assertTrue(dirError.contains("inner.apk"))
    }

    @Test fun snapshotsRemainImmutableAndTextChangesAreVersioned() {
        val original = ToolkitIo.sha(source)
        val read = invoke("apk_read_text","locator" to "zip_entry:assets/test.txt")
        assertEquals("中文\nsecond\n",read.tString("text"))
        fails { invoke("apk_edit_text","locator" to "zip_entry:assets/test.txt","targetVersion" to "wrong","edits" to listOf(args("mode" to "write_target","writeText" to "bad"))) }
        invoke("apk_edit_text","locator" to "zip_entry:assets/test.txt","targetVersion" to read.tString("targetVersion"),"edits" to listOf(args("mode" to "replace_match","matchText" to "second","writeText" to "changed")))
        assertTrue(invoke("apk_read_text","locator" to "zip_entry:assets/test.txt").tString("text").contains("changed"))
        assertEquals(original,ToolkitIo.sha(source))
        assertTrue(tools.call("apk_read_text",args("workspaceId" to workspace,"editSessionId" to "","locator" to "zip_entry:assets/test.txt")).getAsJsonObject("data").tString("text").contains("second"))
    }
    @Test fun smaliEditProducesARealReloadableDexAndPreservesOtherMethods() {
        val locator = "dex_method:Lsample/Test;->value()I"
        val before = invoke("apk_read_text","locator" to locator)
        assertTrue(before.tString("text").contains("0x1"))
        invoke("apk_edit_text","locator" to locator,"targetVersion" to before.tString("targetVersion"),"edits" to listOf(args("mode" to "write_target","writeText" to before.tString("text").replace("0x1","0x2"))))
        assertTrue(invoke("apk_read_text","locator" to locator).tString("text").contains("0x2"))
        assertTrue(invoke("apk_session_check","runBuildChecks" to true).get("valid").asBoolean)
    }
    @Test fun invalidSmaliDoesNotOverwriteTheSession() {
        val locator = "dex_class:Lsample/Test;"; val before = invoke("apk_read_text","locator" to locator)
        fails { invoke("apk_edit_text","locator" to locator,"targetVersion" to before.tString("targetVersion"),"edits" to listOf(args("mode" to "write_target","writeText" to "broken"))) }
        assertEquals(before.tString("targetVersion"),invoke("apk_read_text","locator" to locator).tString("targetVersion"))
    }
    @Test fun resourceXmlRoundTripAndVariantPreservesItsResourceId() {
        val listed = invoke("apk_list","view" to "resource_table").getAsJsonArray("items").single().asJsonObject
        val locator = listed.tString("locator")
        val read = invoke("apk_resource_read","reads" to listOf(args("locator" to locator,"variant" to ""))).getAsJsonArray("items").single().asJsonObject
        assertTrue("resource read should preserve the original value: " + read, read.tString("valueXml").contains("原值"))
        invoke("apk_resource_edit","edits" to listOf(args("locator" to locator,"variant" to "","targetVersion" to read.tString("targetVersion"),"valueXml" to "<string name=\"title\">修改</string>")))
        invoke("apk_resource_add","adds" to listOf(args("kind" to "variant","locator" to locator,"values" to listOf(args("variant" to "en","valueXml" to "<string name=\"title\">English</string>")))))
        val entries = invoke("apk_list","view" to "resource_table").getAsJsonArray("items")
        assertEquals(2,entries.size()); assertTrue(entries.all { it.asJsonObject.tString("locator") == locator })
    }
    @Test fun dexListCursorIsScopedToProducerAndRejectedByApkContinue() {
        val first = invoke("apk_dex_list_classes", "limit" to 1)
        val cursor = first.tString("nextCursor")
        assertTrue(cursor.startsWith("dxi1:classes:"))
        assertEquals("apk_dex_list_classes", first.tString("cursorTool"))
        assertTrue(first.tString("nextCursorHint").contains("不能用于 apk_continue"))

        val message = try {
            invoke("apk_continue", "nextCursor" to cursor, "limit" to 1)
            fail("apk_continue must reject a dex-list cursor")
            ""
        } catch (failure: IllegalArgumentException) { failure.message ?: "" }
        assertTrue(message, message.contains("apk_dex_list_classes"))
        assertTrue(message, message.contains("不能用于 apk_continue"))

        val second = invoke("apk_dex_list_classes", "limit" to 1, "cursor" to cursor)
        assertEquals(2, second.get("total").asInt)
        assertEquals(1, second.getAsJsonArray("items").size())
    }

    @Test fun editsInvalidateContinuationAndCrossSessionReuseIsRejected() {
        val first = invoke("apk_list","view" to "zip_entries","limit" to 1); val cursor = first.tString("nextCursor")
        assertTrue(cursor.isNotEmpty())
        invoke("apk_delete_target","locator" to "zip_entry:assets/test.txt")
        fails { invoke("apk_continue","nextCursor" to cursor,"limit" to 1) }
    }
    @Test fun elfMappingInstructionsAndGuardedPatchesUseSoOffsets() {
        val locator = "zip_entry:lib/arm64-v8a/libfixture.so"
        val mapping = invoke("apk_native_map_address","locator" to locator,"addressKind" to "virtual","address" to "0x10080")
        assertEquals(128,mapping.getAsJsonObject("mapping").get("fileOffset").asInt)
        val disassembly = invoke("apk_native_disassemble","locator" to locator,"addressKind" to "virtual","address" to "0x10080","maxBytes" to 16,"maxInstructions" to 4)
        assertEquals("nop",disassembly.getAsJsonArray("items")[0].asJsonObject.tString("mnemonic"))
        invoke("apk_native_patch_instructions","locator" to locator,"addressKind" to "virtual","address" to "0x10080","expectedHex" to "1F2003D5","replacementAssembly" to "ret","paddingMode" to "none")
        fails { invoke("apk_native_patch_instructions","locator" to locator,"addressKind" to "virtual","address" to "0x10080","expectedHex" to "1F2003D5","replacementAssembly" to "nop","paddingMode" to "none") }
        val read = invoke("apk_read_bytes","locator" to locator,"byteOffset" to 128,"maxBytes" to 4)
        assertEquals("C0035FD6",read.getAsJsonObject("byteWindow").tString("hex"))
    }
    @Test fun unsignedBuildIsANewArchiveAndPreservesCompressionMethods() {
        val hash = ToolkitIo.sha(source)
        invoke("apk_build","outputName" to "result.apk","overwrite" to false,"sign" to false)
        assertEquals(hash,ToolkitIo.sha(source))
        val output = File(home,"result.apk")
        ZipFile(output).use { zip -> assertNull(zip.getEntry("META-INF/ORIGINAL.SF")); assertEquals(ZipEntry.DEFLATED,zip.getEntry("lib/arm64-v8a/libfixture.so").method) }
        val bytes = output.readBytes(); val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var p = 0
        while(p + 30 < bytes.size && b.getInt(p) == 0x04034b50) {
            val length = b.getShort(p+26).toInt() and 65535; val extra = b.getShort(p+28).toInt() and 65535; val name = String(bytes,p+30,length,Charsets.UTF_8)
            val start = p+30+length+extra
            if(b.getShort(p+8).toInt() == ZipEntry.STORED) {
                if(name.endsWith(".so")) assertEquals(0,start % 16384)
                if(name == "resources.arsc") assertEquals(0,start % 4)
            }
            val compressed = b.getInt(p+18)
            if(b.getShort(p+6).toInt() and 8 != 0) break
            p = start + compressed
        }
        fails { invoke("apk_build","outputName" to "fixture.apk","overwrite" to true,"sign" to false) }
    }
    @Test fun buildOutputIsOrganizedOnlyForRootOrBareNames() {
        val result = invoke("apk_build", "outputName" to "organized-result.apk", "overwrite" to false,
            "sign" to false, "organize" to true)
        val organized = result.getAsJsonObject("organized")
        assertNotNull(organized)
        val projectDir = File(organized.get("projectDir").asString)
        assertTrue(projectDir.name.startsWith("mcp-out-"))
        assertEquals(projectDir.path, File(result.get("path").asString).parentFile.path)
        assertTrue(File(result.get("path").asString).isFile)
        assertFalse(File(home, "organized-result.apk").exists())

        val explicitDir = File(home, "explicit"); assertTrue(explicitDir.mkdirs())
        val explicit = invoke("apk_build", "outputName" to "explicit/kept.apk", "overwrite" to false,
            "sign" to false, "organize" to true)
        assertEquals(File(explicitDir, "kept.apk").path, explicit.get("path").asString)
        assertFalse(explicit.has("organized"))
    }

    @Test fun summaryCfgHasEmptyGraphsAndKeepsObservedCounts() {
        val result = invoke("apk_native_analyze_control_flow", "locator" to "zip_entry:lib/arm64-v8a/libfixture.so",
            "addressKind" to "virtual", "address" to "0x10080", "outputMode" to "summary",
            "maxAnalysisBytes" to 16, "maxBlocks" to 4096, "maxInstructions" to 10000)
        for(key in listOf("blocks", "edges", "externalFlows", "unresolvedFlows")) assertEquals(0, result.getAsJsonArray(key).size())
        assertTrue(result.getAsJsonObject("analysisSummary").get("instructionCount").asInt > 0)
    }
    @Test fun ordinary80MiBImportsCanBeCheckedAndBuiltWithoutWholeEntryAnalysis() {
        val large = File(home, "large.bin")
        java.io.RandomAccessFile(large, "rw").use { it.setLength(80L * 1024 * 1024) }
        invoke("apk_copy_entry", "direction" to "local_to_edit_session", "locator" to "zip_entry:assets/large.bin",
            "localPath" to large.name, "overwrite" to false)
        val tail = invoke("apk_read_bytes", "locator" to "zip_entry:assets/large.bin", "byteOffset" to large.length() - 16, "maxBytes" to 16)
        assertEquals("00".repeat(16), tail.getAsJsonObject("byteWindow").tString("hex"))
        assertTrue(invoke("apk_session_check", "runBuildChecks" to true).get("valid").asBoolean)
        invoke("apk_build", "outputName" to "large-built.apk", "sign" to false)
        ZipFile(File(home,"large-built.apk")).use { assertEquals(large.length(), it.getEntry("assets/large.bin").size) }
    }
    @Test fun nativeReferenceResultsHaveTheRefsAliasAndItemsSupport500Entries() {
        val locator = "zip_entry:lib/arm64-v8a/libfixture.so"
        val refs = invoke("apk_native_xref", "locator" to locator, "addressKind" to "virtual", "address" to "0x1008c",
            "instructionMode" to "auto", "scanKind" to "code", "referenceKinds" to listOf("jump"), "maxScanBytes" to 512, "limit" to 50)
        assertEquals(refs.getAsJsonArray("items"), refs.getAsJsonArray("refs"))
        assertTrue(refs.getAsJsonArray("refs").size() > 0)
        val items = invoke("apk_native_list_items", "locator" to locator, "itemKind" to "segments", "namePrefix" to "", "limit" to 500)
        assertEquals(500, items.getAsJsonObject("pagination").get("limitMax").asInt)
    }
    @Test fun outsidePathsAndUnsafeArchiveNamesAreRejected() {
        fails { tools.call("apk_workspace_open",args("path" to "../outside.apk","temporary" to true)) }
        fails { invoke("apk_copy_entry","direction" to "workspace_to_local","locator" to "zip_entry:assets/test.txt","localPath" to "../outside.txt","overwrite" to false) }
        fails { ToolkitIo.entry("assets/../outside") }
        fails { ToolkitResources.safeXml("<!DOCTYPE x [<!ENTITY secret SYSTEM 'file:///private'>]><x/>") }
    }
    private fun elf(): ByteArray = ByteArray(512).also { bytes ->
        bytes[0]=0x7f; bytes[1]=69; bytes[2]=76; bytes[3]=70; bytes[4]=2; bytes[5]=1; bytes[6]=1
        val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        b.putShort(16,3); b.putShort(18,183); b.putInt(20,1); b.putLong(24,0x10080); b.putLong(32,64)
        b.putShort(52,64); b.putShort(54,56); b.putShort(56,1); b.putShort(58,64)
        b.putInt(64,1); b.putInt(68,5); b.putLong(72,0); b.putLong(80,0x10000); b.putLong(96,512); b.putLong(104,512); b.putLong(112,16384)
        b.putInt(128,0xd503201f.toInt()); b.putInt(132,0x14000002); b.putInt(136,0xd503201f.toInt()); b.putInt(140,0xd65f03c0.toInt())
        "HELLO".toByteArray().copyInto(bytes,160)
    }
}
