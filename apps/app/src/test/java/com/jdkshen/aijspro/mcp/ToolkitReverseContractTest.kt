package com.jdkshen.aijspro.mcp

import com.google.gson.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ToolkitReverseContractTest {
    @get:Rule val temp=TemporaryFolder()
    private fun args(text:String)=JsonParser().parse(text).asJsonObject
    private fun rejects(block:()->Unit){try{block();fail("expected rejection")}catch(_:IllegalArgumentException){}}
    @Test fun jobResultPagesPreserveUnicodeExactly(){
        val jobs=ToolkitJobs(temp.newFolder("unicode-jobs")){_,_->ToolkitIo.ok(ToolkitIo.json("text" to "🙂界面".repeat(10)))}
        val id=jobs.start(args("""{"operation":"native_analyze","arguments":{}}""")).tString("jobId")
        val query=ToolkitIo.json("jobId" to id);val deadline=System.currentTimeMillis()+3000
        while(!jobs.status(query).tBool("resultAvailable")&&System.currentTimeMillis()<deadline)Thread.sleep(10)
        val joined=StringBuilder();var offset=0
        do{val page=jobs.result(ToolkitIo.json("jobId" to id,"offset" to offset,"maxChars" to 3));val content=page.tString("content")
            assertFalse(content.isNotEmpty()&&Character.isHighSurrogate(content.last()));assertFalse(content.isNotEmpty()&&Character.isLowSurrogate(content.first()))
            joined.append(content);offset=page.get("nextOffset").asInt
        }while(page.tBool("hasMore"))
        assertEquals("🙂界面".repeat(10),JsonParser().parse(joined.toString()).asJsonObject.getAsJsonObject("data").tString("text"))
    }
    @Test fun commandsRejectAddressExpressionsAndTypeCoercion(){
        assertEquals("0xffffffffffffffff",ToolkitReverseTools.address("18446744073709551615"))
        assertEquals("0x10",ToolkitReverseTools.address("0X0010"))
        for(value in listOf("-1","0x1;!echo x","1+2","0x10000000000000000","18446744073709551616"))rejects{ToolkitReverseTools.address(value)}
        rejects{ToolkitReverseTools.validate("native_xrefs",args("""{"path":"a.so","address":12}"""))}
        rejects{ToolkitReverseTools.validate("native_emulate",args("""{"architecture":"arm64","codeHex":"00","timeoutMs":"100"}"""))}
        rejects{ToolkitReverseTools.validate("native_emulate",args("""{"architecture":"arm64","codeHex":"00","instructionLimit":100001}"""))}
        rejects{ToolkitReverseTools.validate("native_analyze",args("""{"path":"a.so","command":"!ls"}"""))}
    }
    @Test fun everyNewToolHasTypedSchemaDependenciesAndBackgroundContract(){
        val definitions=ToolkitReverseTools.definitions().map{it.asJsonObject}
        assertEquals(19,definitions.size)
        assertEquals(ToolkitReverseTools.names,definitions.map{it.tString("name")})
        definitions.forEach{definition->val name=definition.tString("name");assertTrue(name in McpToolCatalog.canonicalNames)
            assertFalse(definition.getAsJsonObject("inputSchema").tBool("additionalProperties",true))
            val meta=definition.getAsJsonObject("_meta").getAsJsonObject("aijs")
            assertEquals(name in ToolkitReverseTools.operations,meta.tBool("backgroundDefault"))
            if(name in ToolkitReverseTools.operations)assertTrue(name in ToolkitJobs.OPERATIONS)
        }
        assertEquals(listOf("radare2","r2ghidra"),ToolkitReverseTools.dependencies["native_decompile"])
        assertEquals(listOf("apktool","apk-resources"),ToolkitReverseTools.dependencies["apk_build_project"])
    }
    @Test fun projectPublishPreservesExistingOutputsAndCopiesUnicode(){
        val source=temp.newFolder("project");File(source,"目录").mkdir();File(source,"目录/界面.xml").writeText("<view/>🙂")
        val destination=File(temp.root,"published");ToolkitReverseFiles.publishTree(source,destination)
        assertEquals("<view/>🙂",File(destination,"目录/界面.xml").readText())
        File(source,"目录/界面.xml").writeText("changed")
        rejects{ToolkitReverseFiles.publishTree(source,destination)}
        assertEquals("<view/>🙂",File(destination,"目录/界面.xml").readText())
        assertFalse(temp.root.listFiles()!!.any{it.name.startsWith(".aijs-reverse-")})
    }
    @Test fun binaryComparisonHandlesInsertionAndPagesRanges(){
        val a=temp.newFile("a").apply{writeBytes(byteArrayOf(1,2,3,4,5))}
        val b=temp.newFile("b").apply{writeBytes(byteArrayOf(1,8,3,9,5,6))}
        val first=ToolkitBinaryCompare.compare(a,b,args("""{"limit":1}"""))
        assertFalse(first.tBool("identical"));assertEquals(3,first.get("total").asInt)
        assertEquals(3,first.get("differentBytePositions").asInt);assertTrue(first.tBool("hasMore"))
        val last=ToolkitBinaryCompare.compare(a,b,args("""{"limit":2,"offset":1}"""))
        assertFalse(last.tBool("hasMore"));assertEquals(3,last.get("nextOffset").asInt)
        assertEquals("",last.getAsJsonArray("items")[1].asJsonObject.tString("beforeHex"))
        assertTrue(ToolkitBinaryCompare.compare(a,a,JsonObject()).tBool("identical"))
    }
    @Test fun fingerprintReportsEvidenceWithoutClaimingFullApkid(){
        val file=temp.newFile("fixture.apk")
        ZipOutputStream(file.outputStream()).use{zip->for(name in listOf("AndroidManifest.xml","classes.dex","lib/arm64-v8a/libflutter.so")){zip.putNextEntry(ZipEntry(name));zip.write(byteArrayOf(1));zip.closeEntry()}}
        val result=ToolkitFingerprint.inspect(file)
        assertEquals("apk",result.tString("format"));assertFalse(result.tBool("fullApkid"));assertFalse(result.tBool("executesTarget"))
        val finding=result.getAsJsonArray("findings").single().asJsonObject
        assertEquals("Flutter",finding.tString("name"));assertFalse(finding.tBool("conclusive"))
        assertEquals(ToolkitIo.sha(file),result.tString("sha256"))
    }
    @Test fun standardKnowledgeMethodsAreAdvertisedAsSupported(){
        listOf("resources/list","resources/read","resources/templates/list","prompts/list","prompts/get").forEach{assertTrue(McpRpcValidation.isSupportedMethod(it))}
        assertFalse(McpRpcValidation.isSupportedMethod("resources/delete"))
    }
    @Test fun backgroundMissingComponentPreservesActionableCode(){
        val folder=temp.newFolder("jobs")
        val jobs=ToolkitJobs(folder){_,_->throw ToolkitException("COMPONENT_NOT_INSTALLED","请先安装radare2组件","component")}
        val id=jobs.start(args("""{"operation":"native_analyze","arguments":{"path":"x.so"}}""")).tString("jobId")
        val query=ToolkitIo.json("jobId" to id);val deadline=System.currentTimeMillis()+3000
        while(!jobs.status(query).tBool("resultAvailable")&&System.currentTimeMillis()<deadline)Thread.sleep(10)
        val result=JsonParser().parse(jobs.result(query).tString("content")).asJsonObject
        assertFalse(result.tBool("ok"));assertEquals("COMPONENT_NOT_INSTALLED",result.getAsJsonObject("error").tString("code"))
        assertTrue(result.getAsJsonArray("nextActions").size()>0)
    }
}
