package com.jdkshen.aijspro.mcp

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** One owned process group per invocation, including compiler grandchildren. */
internal object ToolkitProcess {
    data class Result(val text:String,val exitCode:Int)
    fun run(context:Context,command:List<String>,directory:File,env:Map<String,String>,timeout:Int,watchedFiles:List<File> = emptyList()):Result {
        ToolkitIo.ensureActive()
        val log=File(directory,"process-${ToolkitIo.fresh()}.log")
        val running=McpAndroidTerminal(context).launch(command.joinToString(" "){McpAndroidTerminal.quote(it)},directory,env)
        val failure=AtomicReference<Throwable?>()
        val reader=Thread({try{running.process.inputStream.use{input->log.outputStream().use{out->
            val buffer=ByteArray(16384);var total=0
            while(true){val n=input.read(buffer);if(n<0)break;total+=n;require(total<=4*1024*1024){"组件输出超过4MiB，请缩小分析范围"};out.write(buffer,0,n)}
        }}}catch(e:Throwable){failure.set(e)}},"reverse-output").apply{isDaemon=true;start()}
        try {
            val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(timeout.toLong())
            while(!running.process.waitFor(100,TimeUnit.MILLISECONDS)){
                ToolkitIo.ensureActive();failure.get()?.let{throw IllegalStateException(it.message,it)}
                require(watchedFiles.all{it.length()<=64L*1024*1024}){"组件分析结果超过64MiB预算"}
                if(System.nanoTime()>=end)throw ToolkitException("COMPONENT_TIMEOUT","组件运行超时；本次进程组已停止")
            }
            reader.join(2000);require(!reader.isAlive){"组件输出没有结束"}
            failure.get()?.let{throw IllegalStateException(it.message,it)}
            return Result(if(log.isFile)log.readText(Charsets.UTF_8)else "",running.process.exitValue())
        } finally {
            val interrupted=Thread.interrupted()
            try { running.terminate();runCatching{running.process.inputStream.close()};runCatching{reader.join(1000)};log.delete() }
            finally { if(interrupted)Thread.currentThread().interrupt() }
        }
    }
}
