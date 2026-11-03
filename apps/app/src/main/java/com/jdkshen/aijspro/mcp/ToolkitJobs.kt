package com.jdkshen.aijspro.mcp

import com.google.gson.*
import java.io.File
import java.io.InterruptedIOException
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/** Long APK operations never hold the provider's IPC thread. Cancellation waits for worker exit. */
internal class ToolkitJobs(private val folder: File, private val execute: (String, JsonObject) -> JsonObject) {
    private class Job(val id: String, val operation: String, val arguments: JsonObject, val timeout: Int, val created:Long=System.currentTimeMillis()) {
        val cancelled = AtomicBoolean()
        @Volatile var state = "QUEUED"; @Volatile var thread: Thread? = null
        @Volatile var started = 0L; @Volatile var finished = 0L; @Volatile var reason = ""
        @Volatile var resultReady = false
    }
    private val jobs = ConcurrentHashMap<String, Job>()
    init { require(folder.isDirectory || folder.mkdirs()); recover() }
    @Synchronized fun start(args: JsonObject): JsonObject {
        val operation = args.tString("operation")
        require(operation in OPERATIONS) { "此操作不支持后台任务：$operation" }
        require(jobs.values.count { it.finished == 0L } < 4) { "最多排队4个 APK 任务" }
        val values = args.getAsJsonObject("arguments")?.deepCopy() ?: JsonObject()
        val job = Job(ToolkitIo.fresh(), operation, values, args.tInt("timeoutSeconds", 300, 1, 1800))
        jobs[job.id] = job; persist(job); trim()
        val timeout = TIMER.schedule({ requestCancel(job, "timeout") }, job.timeout.toLong(), TimeUnit.SECONDS)
        WORKERS.execute {
            job.thread = Thread.currentThread(); job.started = System.currentTimeMillis()
            try {
                if (job.cancelled.get()) throw InterruptedIOException("任务已取消")
                job.state = "RUNNING"; persist(job)
                val response = execute(operation, values)
                // A committed operation can finish just before cancellation; its real response is retained.
                Thread.interrupted()
                writeResult(job, response)
                job.state = if(job.cancelled.get() && (response.get("ok")?.asBoolean==false || response.get("data")?.takeIf{it.isJsonObject}?.asJsonObject?.get("cancelled")?.asBoolean==true)) "CANCELLED"
                    else if (response.get("ok")?.asBoolean == false) "FAILED" else "SUCCEEDED"
            } catch (failure: Exception) {
                job.state = if (job.cancelled.get() || failure is InterruptedIOException || failure is InterruptedException) "CANCELLED" else "FAILED"
                job.reason = job.reason.ifEmpty { failure.message ?: failure.javaClass.simpleName }
                Thread.interrupted()
                writeResult(job, ToolkitIo.json("ok" to false, "error" to ToolkitIo.json("code" to job.state, "message" to job.reason)))
            } finally {
                Thread.interrupted()
                synchronized(job) { job.finished = System.currentTimeMillis(); job.thread = null
                    timeout.cancel(false); persist(job) }
            }
        }
        return status(job)
    }
    fun list() = ToolkitIo.json("items" to jobs.values.sortedByDescending { it.created }.map(::status))
    fun status(args: JsonObject): JsonObject = status(find(args))
    fun cancel(args: JsonObject): JsonObject { val j=find(args); requestCancel(j,"user"); return status(j) }
    fun result(args: JsonObject): JsonObject {
        val j=find(args); require(j.resultReady) { "任务尚无结果，请读取状态" }
        val file=File(folder,j.id+".result.json"); require(file.length()<=24L*1024*1024) { "任务结果超过读取预算" }
        val offset=args.tInt("offset",0,0,24*1024*1024); val max=args.tInt("maxChars",16384,1,200000)
        val text=file.readText(Charsets.UTF_8); require(offset<=text.length) { "结果偏移超出范围" }
        val end=minOf(text.length,offset+max)
        return ToolkitIo.json("jobId" to j.id,"state" to j.state,"content" to text.substring(offset,end),"nextOffset" to end,"hasMore" to (end<text.length),"totalChars" to text.length)
    }
    private fun find(args:JsonObject)=jobs[ToolkitIo.id(args.tString("jobId"))] ?: error("任务不存在")
    private fun requestCancel(j:Job,reason:String) = synchronized(j) {
        if(j.finished!=0L || j.state in setOf("SUCCEEDED","FAILED","CANCELLED","INTERRUPTED"))return@synchronized
        j.reason=reason; j.cancelled.set(true); j.state="CANCELLING"; j.thread?.interrupt(); persist(j)
    }
    private fun status(j:Job)=synchronized(j){ToolkitIo.json("jobId" to j.id,"operation" to j.operation,"state" to j.state,"createdAt" to j.created,
        "startedAt" to j.started.takeIf{it>0},"finishedAt" to j.finished.takeIf{it>0},"cancellationRequested" to j.cancelled.get(),
        "reason" to j.reason,"resultAvailable" to j.resultReady,"progress" to ToolkitIo.json("phase" to j.state.lowercase(),"percent" to if(j.finished>0)100 else null))}
    private fun persist(j:Job) = synchronized(j) { ToolkitIo.atomic(File(folder,j.id+".json")) { it.writeText(status(j).toString(),Charsets.UTF_8) } }
    private fun writeResult(j:Job,response:JsonObject) {
        val data=response.toString(); require(data.toByteArray(Charsets.UTF_8).size<=24*1024*1024) { "任务结果过大，请缩小预算" }
        ToolkitIo.atomic(File(folder,j.id+".result.json")){it.writeText(data,Charsets.UTF_8)}; j.resultReady=true
    }
    private fun recover() {
        folder.listFiles()?.filter { it.name.endsWith(".json") && !it.name.endsWith(".result.json") }?.forEach { file -> runCatching {
            val s=JsonParser().parse(file.readText(Charsets.UTF_8)).asJsonObject
            val id=ToolkitIo.id(s.tString("jobId")); val j=Job(id,s.tString("operation"),JsonObject(),1,s.get("createdAt")?.asLong?:System.currentTimeMillis())
            j.state=if(s.get("finishedAt")?.isJsonNull==false)s.tString("state") else "INTERRUPTED"
            j.finished=s.get("finishedAt")?.takeUnless{it.isJsonNull}?.asLong ?: System.currentTimeMillis()
            j.reason=if(j.state=="INTERRUPTED")"工具进程已重启，任务没有自动重放" else s.tString("reason","")
            j.resultReady=File(folder,id+".result.json").isFile; jobs[id]=j
        } }
    }
    private fun trim() { jobs.values.filter{it.finished>0}.sortedByDescending{it.created}.drop(32).forEach { j -> jobs.remove(j.id); File(folder,j.id+".json").delete(); File(folder,j.id+".result.json").delete() } }
    companion object {
        private val WORKERS=Executors.newFixedThreadPool(2){r->Thread(r,"apk-tool-job").apply{isDaemon=true}}
        private val TIMER=Executors.newSingleThreadScheduledExecutor{r->Thread(r,"apk-job-deadline").apply{isDaemon=true}}
        val OPERATIONS=setOf("apk_search","apk_build","apk_verify","apk_dex_call_graph","apk_dex_search_members","apk_dex_hierarchy",
            "apk_decompile_class","apk_decompile_method","android_resources_compile","apk_component_install","artifact_open","artifact_export")
    }
}
