package com.jdkshen.aijspro.mcp

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ToolkitJobsAndSessionsTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun statusAndCancelStayResponsiveUntilWorkerReallyExits(){
        val started=CountDownLatch(1);val finished=CountDownLatch(1)
        val jobs=ToolkitJobs(temp.newFolder("jobs")){_,_->try{started.countDown();CountDownLatch(1).await()}finally{finished.countDown()};ToolkitIo.ok(ToolkitIo.json("done" to true))}
        val response=jobs.start(ToolkitIo.json("operation" to "apk_search","arguments" to ToolkitIo.json("query" to "x"),"timeoutSeconds" to 30))
        val args=ToolkitIo.json("jobId" to response.tString("jobId"));assertTrue(started.await(3,TimeUnit.SECONDS))
        assertEquals("RUNNING",jobs.status(args).tString("state"));assertTrue(jobs.cancel(args).tBool("cancellationRequested"))
        assertTrue(finished.await(3,TimeUnit.SECONDS));awaitFinal(jobs,args)
        assertEquals("CANCELLED",jobs.status(args).tString("state"));assertTrue(jobs.result(args).tString("content").contains("CANCELLED"))
    }
    @Test fun resultIsPagedAndReloadDoesNotReplayWork(){
        val folder=temp.newFolder("jobs");val jobs=ToolkitJobs(folder){_,_->ToolkitIo.ok(ToolkitIo.json("value" to "abcdef"))}
        val started=jobs.start(ToolkitIo.json("operation" to "apk_search","arguments" to ToolkitIo.json(),"timeoutSeconds" to 10))
        val args=ToolkitIo.json("jobId" to started.tString("jobId"));awaitFinal(jobs,args)
        val first=jobs.result(args.deepCopy().apply{addProperty("maxChars",5)})
        val second=jobs.result(args.deepCopy().apply{addProperty("offset",first.get("nextOffset").asInt)})
        val combined=first.tString("content")+second.tString("content");assertEquals("abcdef",JsonParser().parse(combined).asJsonObject.getAsJsonObject("data").tString("value"))
        assertEquals("SUCCEEDED",ToolkitJobs(folder){_,_->fail("must not replay");ToolkitIo.json()}.status(args).tString("state"))
    }
    @Test fun rollbackRetainsCheckpointBytesAndRejectsStaleRevision(){
        val folder=temp.newFolder("session");val entries=File(folder,"entries").apply{mkdir()};val originalId=ToolkitIo.fresh()
        File(entries,originalId).writeText("checkpoint-value")
        val metadata=ToolkitIo.json("revision" to "r1","changedEntries" to mapOf("value.txt" to originalId),"deleted" to emptyList<String>(),"classChanges" to emptyMap<String,String>(),"classChangesComplete" to true)
        File(folder,"session.json").writeText(metadata.toString())
        val checkpoint=ToolkitApkSessions.checkpoint(folder,ToolkitIo.json("label" to "before"))
        File(entries,originalId).delete();metadata.addProperty("revision","r2");metadata.add("changedEntries",ToolkitIo.json())
        File(folder,"session.json").writeText(metadata.toString())
        val args=ToolkitIo.json("checkpointId" to checkpoint.tString("checkpointId"),"expectedRevision" to "stale")
        try{ToolkitApkSessions.rollback(folder,args);fail("stale rollback accepted")}catch(_:IllegalArgumentException){}
        assertEquals("r2",JsonParser().parse(File(folder,"session.json").readText()).asJsonObject.tString("revision"))
        args.addProperty("expectedRevision","r2");val result=ToolkitApkSessions.rollback(folder,args)
        assertNotEquals("r2",result.tString("revision"))
        val restored=JsonParser().parse(File(folder,"session.json").readText()).asJsonObject.getAsJsonObject("changedEntries").tString("value.txt")
        assertEquals("checkpoint-value",File(entries,restored).readText())
    }
    @Test fun diffShowsOriginalAndChangedTextWithoutChangingBase(){
        val base=temp.newFile("base.apk");ZipOutputStream(base.outputStream()).use{it.putNextEntry(ZipEntry("value.txt"));it.write("before".toByteArray());it.closeEntry()}
        val originalHash=ToolkitIo.sha(base);val folder=temp.newFolder("s");val entries=File(folder,"entries").apply{mkdir()};val id=ToolkitIo.fresh();File(entries,id).writeText("after")
        File(folder,"session.json").writeText(ToolkitIo.json("revision" to "r1","changedEntries" to mapOf("value.txt" to id),"deleted" to emptyList<String>()).toString())
        val diff=ToolkitApkSessions.diff(base,folder).single();assertEquals("changed",diff.tString("kind"));assertEquals("before",diff.tString("beforePreview"));assertEquals("after",diff.tString("afterPreview"));assertEquals(originalHash,ToolkitIo.sha(base))
    }
    private fun awaitFinal(jobs:ToolkitJobs,args:com.google.gson.JsonObject){val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(jobs.status(args).get("finishedAt").isJsonNull){check(System.nanoTime()<end);Thread.sleep(5)}}
}
