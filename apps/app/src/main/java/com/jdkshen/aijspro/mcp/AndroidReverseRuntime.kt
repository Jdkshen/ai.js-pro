package com.jdkshen.aijspro.mcp

import android.content.Context
import com.google.gson.*
import com.jdkshen.aijspro.autojs.build.sign.AutoSigningIdentity
import com.jdkshen.aijspro.autojs.build.sign.KeyStoreApkSigner
import com.stardust.autojs.runtime.tool.ToolchainRuntime
import java.io.File
import java.util.zip.ZipFile
import com.google.gson.stream.JsonReader

/**
 * B: apktool 子进程命令。-Xmx 必须放在 /system/bin（app_process 可执行路径）之前，
 * 否则会被当成 app_process 之后的程序参数而不是 VM 选项。抽成 internal 便于 JVM 单测断言顺序。
 */
internal fun apktoolCommand(
    temp:File,
    payload:File,
    applicationApk:String,
    framework:File,
    subcommand:List<String>,
):MutableList<String> = mutableListOf(
    "/system/bin/app_process",
    "-Djava.io.tmpdir=${temp.path}",
    "-Duser.home=${temp.path}",
    "-Xmx${McpLimits.CHILD_HEAP_MB}m",
    "/system/bin",
    "--nice-name=aijs-apktool",
    ToolkitCliMain::class.java.name,
    "brut.apktool.Main",
).apply{addAll(subcommand)}

/** Typed reverse operations. Target bytes are staged; optional engines run in owned child VMs. */
internal class AndroidReverseRuntime(private val context:Context,private val root:File,private val component:(String)->File) {
    /** §10：有界观测会话（内存态；真实 attach 仍走 frida_eval，本类只负责登记/分页/清理）。 */
    private val fridaSessions = ToolkitFridaSession()
    /** §7：有状态 native 分析会话（状态落盘在 noBackupFilesDir，不启动 r2 进程）。 */
    private val nativeAnalysis = ToolkitNativeAnalysis(File(context.noBackupFilesDir,"native-analysis"))
    private val outputOrganizer by lazy { ToolkitOutputOrganizers.forRoot(root) }
    /**
     * UX-1：r2 project 会话（一次 aaa 分析 + 多次查询），执行注入到 [ToolkitProcess]。
     * 没有 runner 时 ToolkitR2Session 会返回 executed=false，不假装跑过。
     */
    private val r2Sessions by lazy {
        ToolkitR2Session(File(context.noBackupFilesDir,"r2-sessions"), runner = R2Runner { argv, extra, timeoutSeconds ->
            val temp=File(context.cacheDir,"r2-session-"+ToolkitIo.fresh()).apply{require(mkdir())}
            try{
                val c=component("radare2")
                // ToolkitR2Session 的 argv 不含可执行文件（它单独返回 executable=radare2），这里补上。
                val command=listOf(File(c,"bin/radare2").path)+argv
                val result=ToolkitProcess.run(context,command,temp,env(c)+extra,timeoutSeconds.coerceIn(1,McpLimits.MAX_TIMEOUT_SECONDS),emptyList())
                R2RunnerResult(result.text,result.exitCode,false)
            }catch(failure:Throwable){
                R2RunnerResult(failure.message?:"r2 runner failed",1,false)
            }finally{temp.deleteRecursively()}
        })
    }
    /**
     * T4：常驻 r2 进程（一次加载、多次查询）。framing 由 ToolkitR2Resident 负责（<cmd>;?e <marker>），
     * 这里只实现进程与管道；进程死掉/超时会如实报错，不伪造输出。
     */
    private val r2Resident by lazy {
        // R2Pipe 是普通 interface（不是 fun interface），必须用 object 表达式而不是 SAM 简写。
        ToolkitR2Resident(File(context.noBackupFilesDir,"r2-resident"), object : R2Pipe {
          override fun open(inputPath:String, argv:List<String>):R2PipeHandle {
            val c=component("radare2")
            val work=File(context.cacheDir,"r2-resident-"+ToolkitIo.fresh()).apply{require(mkdirs())}
            val builder=ProcessBuilder(listOf(File(c,"bin/radare2").path)+argv)
            builder.directory(work)
            val r2ghidra=runCatching{component("r2ghidra")}.getOrNull()
            val pluginEnv=if(r2ghidra?.let{File(it,"lib/core_ghidra.so").isFile}==true) mapOf("SLEIGHHOME" to File(r2ghidra,"share/r2ghidra").path) else emptyMap()
            builder.environment().putAll(env(c)+pluginEnv)
            builder.redirectErrorStream(true)
            val process=builder.start()
            return object : R2PipeHandle {
                private val stdin=process.outputStream.bufferedWriter()
                override fun write(line:String){stdin.write(line);stdin.write("\n");stdin.flush()}
                // r2 以 -0 启动：每条命令后跟一个 NUL（保证非 TTY 下立即刷新）。
                // 这里忽略 NUL/CR，按行累积，直到出现 marker 行才算本次命令结束——
                // NUL 只用来触发刷新，不能当终止符（实测用 NUL 终止会整体错位一条命令）。
                override fun readUntil(marker:String,timeoutMillis:Long,maxBytes:Long):String{
                    val raw=java.io.ByteArrayOutputStream()
                    val deadline=System.currentTimeMillis()+timeoutMillis
                    while(System.currentTimeMillis()<deadline){
                        val available=process.inputStream.available()
                        if(available>0){
                            val buffer=ByteArray(minOf(available,8192))
                            val read=process.inputStream.read(buffer)
                            if(read<0)throw IllegalStateException("r2 stdout closed")
                            for(i in 0 until read)if(buffer[i]!=0.toByte())raw.write(buffer[i].toInt())
                            val text=String(raw.toByteArray(),Charsets.UTF_8)
                            val lastLine=text.trimEnd('\r','\n').substringAfterLast('\n').trim()
                            if(lastLine==marker)return text
                            if(raw.size()>maxBytes)return text
                            continue
                        }
                        if(!process.isAlive)throw IllegalStateException("r2 process exited")
                        Thread.sleep(5)
                    }
                    throw java.util.concurrent.TimeoutException("r2 marker not observed within "+timeoutMillis+"ms")
                }
                override fun alive():Boolean=process.isAlive
                override fun close(){
                    runCatching{stdin.close()}
                    process.destroy()
                    if(process.isAlive)process.destroyForcibly()
                    work.deleteRecursively()
                }
            }
          }
        })
    }
    private fun optionalString(args:JsonObject,key:String):String?=
        args.get(key)?.takeIf{it.isJsonPrimitive&&it.asJsonPrimitive.isString}?.asString
    private fun file(args:JsonObject,key:String="path",limit:Long=ToolkitIo.MAX_ENTRY.toLong()):File=
        ToolkitIo.resolve(root,args.tString(key)).also{require(it.isFile&&it.length()<=limit){"$key 文件不存在或超过${limit}字节"}}
    private fun native(id:String):File {
        if(!ToolchainRuntime.canExecuteDownloaded(context))throw ToolkitException("EXECUTION_POLICY","当前Android策略不允许执行下载的原生组件","component")
        return component(id)
    }
    fun call(name:String,args:JsonObject):JsonObject {
        ToolkitReverseTools.validate(name,args)
        val temp=File(context.cacheDir,"reverse-${ToolkitIo.fresh()}").apply{require(mkdir())}
        try{return when(name){
            "apk_inject_gadget"->injectGadget(args,temp)
            "apk_decode_project","apk_build_project","apk_install_framework"->apktool(name,args,temp)
            "artifact_fingerprint"->ToolkitFingerprint.inspect(file(args,limit=ToolkitIo.MAX_APK))
            "yara_scan"->{val c=native("yara");val target=stage(file(args),temp,"target.bin");val rules=stage(file(args,"rulesPath",1024*1024),temp,"rules.yar")
                runner(listOf(File(c,"bin/aijs-yara").path,rules.path,target.path,args.tInt("scanTimeoutSeconds",10,1,30).toString(),if(args.tBool("includeOffsets",true))"1" else "0"),temp,c,args)}
            "native_emulate"->emulate(args,temp)
            "frida_status","frida_processes","frida_eval"->frida(name,args,temp)
            "frida_session_open","frida_session_read","frida_session_close","frida_session_list"->fridaSession(name,args)
            "native_resident_open"->residentOpen(args)
            "native_resident_command"->residentCommand(args)
            "native_resident_batch"->r2Resident.batch(args.tString("sessionId"),
                args.getAsJsonArray("commands")?.mapNotNull{element->element?.takeIf{it.isJsonPrimitive}?.asString}?:emptyList(),
                args.tInt("timeoutMillis",30000,1,300000).toLong())
            "native_resident_close"->r2Resident.close(args.tString("sessionId"))
            "native_resident_list"->r2Resident.list()
            "native_session_open"->{
                val requested=args.tString("path")
                val resolved=runCatching{ToolkitIo.resolve(root,requested).path}.getOrDefault(requested)
                r2Sessions.open(resolved,args.tString("sha256"),args.tBool("relocsApply",true))
            }
            "native_session_plan"->r2Sessions.plan(args.tString("sessionId"),optionalString(args,"command"))
            "native_session_query"->r2Sessions.query(args.tString("sessionId"),args.tString("command"),args.tInt("timeoutSeconds",60,1,McpLimits.MAX_TIMEOUT_SECONDS))
            "native_session_close"->r2Sessions.close(args.tString("sessionId"))
            "native_session_list"->r2Sessions.list()
            // 末位 allowOutsideOperationRoot：不接线的话 schema 收了、调用方却丢掉，逃生口形同虚设（队友 A2 报的 blocker）。
            "native_analysis_open"->nativeAnalysis.open(args.tString("path"),args.tString("sha256"),optionalString(args,"abi"),optionalString(args,"sessionId"),args.tBool("allowOutsideOperationRoot",false))
            "native_analysis_status"->nativeAnalysis.status(args.tString("sessionId"))
            "native_analysis_define_function"->nativeAnalysis.defineFunction(args.tString("sessionId"),args.tString("address"),
                if(args.has("size"))args.tInt("size",0,0,16*1024*1024)else null,optionalString(args,"name"))
            "native_analysis_set_type"->nativeAnalysis.setType(args.tString("sessionId"),args.tString("target"),args.tString("type"))
            "native_analysis_set_noreturn"->nativeAnalysis.setNoreturn(args.tString("sessionId"),args.tString("address"),args.tBool("noreturn",true))
            "native_analysis_overlay"->nativeAnalysis.overlay(args.tString("sessionId"),args.tString("address"),args.tString("hexBytes"),optionalString(args,"note"))
            "native_analysis_save"->nativeAnalysis.save(args.tString("sessionId"))
            "native_analysis_close"->nativeAnalysis.close(args.tString("sessionId"))
            "native_analysis_list"->nativeAnalysis.list()
            "native_analysis_r2_commands"->{
                val sessionId=args.tString("sessionId")
                ToolkitIo.json("sessionId" to sessionId,"commands" to nativeAnalysis.r2Commands(sessionId),
                    "executed" to false,"analysisOnly" to true)
            }
            // entry_：入口分析（只读编排：Manifest 组件 + DEX 类表 + ELF 摘要 + 提前加载建议）。
            "entry_"->entryAnalysis(args)
            else->radare(name,args,temp)
        }}finally{temp.deleteRecursively()}
    }
    private fun stage(source:File,temp:File,name:String):File {
        val before=ToolkitIo.sha(source);val target=File(temp,name)
        source.inputStream().use{input->target.outputStream().use{out->val buffer=ByteArray(65536);var total=0L
            while(true){ToolkitIo.ensureActive();val n=input.read(buffer);if(n<0)break;total+=n;require(total<=ToolkitIo.MAX_APK){"输入超过512MiB"};out.write(buffer,0,n)}}}
        require(ToolkitIo.sha(target)==before&&ToolkitIo.sha(source)==before){"分析输入在复制时发生变化"};return target
    }
    private fun execute(command:List<String>,temp:File,env:Map<String,String>,args:JsonObject,watchedFiles:List<File> = emptyList())=
        ToolkitProcess.run(context,command,temp,env,args.tInt("timeoutSeconds",120,1,McpLimits.MAX_TIMEOUT_SECONDS),watchedFiles)
    private fun env(c:File)=mapOf("LD_LIBRARY_PATH" to File(c,"lib").path,"HOME" to context.filesDir.path,"R2_PREFIX" to c.path,
        "R2_LIBR_PLUGINS" to File(c,"lib/radare2").path,"R2_NOPLUGINS" to "1","R2_RCFILE" to "/dev/null")
    private fun runner(command:List<String>,temp:File,c:File,args:JsonObject):JsonObject {
        val response=execute(command,temp,env(c),args)
        val result=runCatching{JsonParser().parse(response.text).asJsonObject}.getOrElse{
            throw ToolkitException("COMPONENT_FAILED","组件未返回JSON（exit=${response.exitCode}）：${response.text.takeLast(4000)}")}
        result.addProperty("exitCode",response.exitCode)
        if(response.exitCode!=0||result.get("ok")?.asBoolean==false)throw ToolkitException("COMPONENT_FAILED",result.toString().take(12000))
        return result
    }
    private fun emulate(args:JsonObject,temp:File):JsonObject {
        val c=native("unicorn");val code=ToolkitIo.unhex(args.tString("codeHex"));require(code.isNotEmpty()&&code.size<=65536){"代码必须为1..65536字节"}
        val data=ToolkitIo.unhex(args.tString("dataHex",""));require(data.size<=4096){"初始数据超过4096字节"}
        val codeFile=File(temp,"code.bin").apply{writeBytes(code)};val dataFile=File(temp,"data.bin").apply{writeBytes(data)}
        val command=mutableListOf(File(c,"bin/aijs-unicorn").path,args.tString("architecture"),codeFile.path,dataFile.path,args.tInt("timeoutMs",100,1,1000).toString(),args.tInt("instructionLimit",10000,1,100000).toString())
        args.getAsJsonObject("registers")?.entrySet()?.let{values->require(values.size<=64){"寄存器太多"};values.forEach{(key,value)->
            require(key.matches(Regex("[A-Za-z][A-Za-z0-9]{0,7}"))&&value.isJsonPrimitive&&value.asJsonPrimitive.isString){"寄存器格式无效"}
            command+="${key.uppercase()}=${ToolkitReverseTools.address(value.asString)}"
        }}
        return runner(command,temp,c,args).apply{addProperty("backend","unicorn");addProperty("fullAndroidEnvironment",false)}
    }
    /** P2-1：resident r2 也要显式加载 r2ghidra；否则 pdg 报“install plugin”，而 native_decompile 手动 L 后正常。 */
    private fun residentOpen(args:JsonObject):JsonObject {
        val requested=args.tString("path")
        val resolved=runCatching{ToolkitIo.resolve(root,requested).path}.getOrDefault(requested)
        val opened=r2Resident.open(resolved,args.tString("sha256"),args.tBool("relocsApply",true))
        val plugin=runCatching{component("r2ghidra")}.getOrNull()
        val library=plugin?.let{File(it,"lib/core_ghidra.so")}
        val pluginState=when {
            library==null||!library.isFile -> ToolkitIo.json("installed" to false,"loaded" to false,
                "hint" to "r2ghidra 未安装/不完整；pdg 在 resident/native_decompile 都不可用。先 apk_component_status 后 apk_component_install(component=r2ghidra)。")
            else -> {
                val loaded=runCatching{r2Resident.command(opened.tString("sessionId"),"L "+library.path,15000)}
                val output=loaded.getOrNull()?.get("output")?.takeIf{it.isJsonPrimitive}?.asString.orEmpty()
                val failed=loaded.isFailure||output.contains("cannot",true)||output.contains("error",true)
                ToolkitIo.json("installed" to true,"loaded" to !failed,"pluginPath" to library.path,
                    "hint" to if(failed) "resident r2 加载 r2ghidra 失败：${loaded.exceptionOrNull()?.message ?: output.take(300)}；pdg 不可用，先检查组件/ABI，再重建会话" else "resident r2 已自动加载 r2ghidra，pdg 可用",
                    "loadError" to loaded.exceptionOrNull()?.message, "loadOutput" to output.take(300))
            }
        }
        opened.add("r2ghidra",pluginState)
        return opened
    }

    private fun residentCommand(args:JsonObject):JsonObject = try {
        r2Resident.command(args.tString("sessionId"),args.tString("command"),args.tInt("timeoutMillis",30000,1,300000).toLong())
    } catch(failure:ToolkitException){
        if(args.tString("command").contains("pdg")){
            throw ToolkitException(failure.code,
                failure.message+"；resident r2 与 native_decompile 的插件加载状态不同：pdg 需要 r2ghidra 插件被 L 加载。先 native_resident_open（会尝试自动 L），失败时用 native_decompile 或检查 apk_component_status.r2ghidra。",
                failure.argument)
        }
        throw failure
    }

    private fun frida(name:String,args:JsonObject,temp:File):JsonObject {
        val endpoint=args.tString("endpoint","127.0.0.1:27042")
        require(endpoint.matches(Regex("[A-Za-z0-9_.-]{1,253}:[0-9]{1,5}"))&&endpoint.substringAfterLast(':').toInt() in 1..65535){"endpoint 必须为host:port"}
        // 主动探测放在组件解析**之前**：即使 frida 客户端组件缺失/被策略限制，也能拿到端口证据
        // （refused=没有监听者 / timeout=可能被静默丢弃），未装组件的设备（如 K40）也能判读。
        val probe=ToolkitFridaPortProbe.probeEndpoint(endpoint,
            args.tInt("probeTimeoutMs",ToolkitFridaPortProbe.DEFAULT_TIMEOUT_MS,ToolkitFridaPortProbe.MIN_TIMEOUT_MS,ToolkitFridaPortProbe.MAX_TIMEOUT_MS))
        val c=try{native("frida")}catch(failure:ToolkitException){
            // 不改 errorCode，只在可读信息里附上端口证据（设备4 ★①：不要因为读不到 /proc 就说组件没装）。
            throw ToolkitException(failure.code,
                (failure.message?:"")+"（主动探测 probe="+probe.get("probeResult").asString+"，elapsedMs="+probe.get("elapsedMs").asLong+
                    "ms；应用域读不到 /proc/net/tcp 是预期，端口状态以 probe 为准）",failure.argument)
        }
        val request=ToolkitIo.json("operation" to name.removePrefix("frida_"),"endpoint" to endpoint,"timeoutMs" to 12000)
        val token=args.tString("token","")
        require(token.isEmpty()||token.matches(Regex("[A-Za-z0-9_-]{16,128}"))){"token 必须是16..128位字母数字、横线或下划线"}
        if(token.isNotEmpty()&&!File(c,"gadget-token-support.txt").isFile)throw ToolkitException("COMPONENT_UPDATE_REQUIRED","请调用apk_component_install更新frida客户端至支持Gadget令牌的版本","component")
        if(token.isNotEmpty())request.addProperty("token",token)
        if(name=="frida_eval") { val script=args.tString("script");require(script.toByteArray(Charsets.UTF_8).size<=65536){"Frida脚本超过64KiB"}
            request.addProperty("pid",args.tInt("pid",0,1,Int.MAX_VALUE));request.addProperty("script",script);request.addProperty("durationMs",args.tInt("durationMs",1000,1,10000)) }
        val input=File(temp,"request.json").apply{writeText(request.toString(),Charsets.UTF_8)}
        val gadgetPort=endpoint.substringAfterLast(':').toInt()
        // 设备4 Bug3 证据链 ★①：/proc/net/tcp[6] 降级为**可选补充**（probe 已在函数开头跑完；
        // 应用域读不到 /proc 是 SELinux proc_net_tcp_udp 的预期拒绝，不得据此报「组件未装」）。
        // T1：/proc/net/tcp[6] 证据（调用方给的 tcpText/tcp6Text 优先；读不到就留空，不猜）。
        // 注意 sameUid 不能从 tcp 文件推断，仍需调用方比较 UID。
        val tcpArg=args.tString("tcpText","")
        val tcp6Arg=args.tString("tcp6Text","")
        val tcpFromFile=if(tcpArg.isEmpty()) runCatching{File(ToolkitProcNetTcp.PROC_TCP).readText()}.getOrNull() else null
        val tcp6FromFile=if(tcp6Arg.isEmpty()) runCatching{File(ToolkitProcNetTcp.PROC_TCP6).readText()}.getOrNull() else null
        val tcpEvidence=ToolkitProcNetTcp.forDiagnosis(
            tcpArg.ifEmpty{tcpFromFile.orEmpty()},
            tcp6Arg.ifEmpty{tcp6FromFile.orEmpty()}.ifEmpty{null},
            gadgetPort)
        val tcpReadable=tcpArg.isNotEmpty()||tcpFromFile!=null
        tcpEvidence.addProperty("readable",tcpReadable)
        tcpEvidence.addProperty("supplement",true)
        tcpEvidence.addProperty("authoritative","probe")
        tcpEvidence.addProperty("evidenceNote","/proc/net/tcp[6] 只是可选补充证据；应用域读不到是预期行为（SELinux proc_net_tcp_udp），端口状态请以 probe 为准。")
        if(!tcpReadable){
            // 关键：读不到 ≠ 端口未监听 ≠ 组件未装。这里只如实记录读取失败。
            tcpEvidence.addProperty("readError","应用域读不到 /proc/net/tcp[6]（SELinux 对 proc_net_tcp_udp 的拒绝是预期行为）；这不代表组件缺失，端口状态以 probe 为准。")
        }
        // 证据优先级：调用方显式传入 > 主动探测 > /proc 文本。
        val probeListening=ToolkitFridaPortProbe.reachable(probe)
        val evidencePortListening=args.tBool("portListening",probeListening||(tcpEvidence.get("portListening")?.asBoolean?:false))
        val evidenceSynSent=args.tBool("synSentObserved",tcpEvidence.get("synSentObserved")?.asBoolean?:false)
        val response=try{
            runner(listOf(File(c,"bin/aijs-frida").path,input.path),temp,c,args)
        }catch(failure:ToolkitException){
            // Bug 3：listen 模式跨 UID 时 loopback 可能被 SELinux 静默 DROP（连接停在 SYN_SENT）。
            // 超时/被丢弃不是「组件没装」，这里必须把可执行的下一步写进错误信息。
            val diagnosis=ToolkitFridaDiagnosis.classifyConnectFailure(failure.message?:"",
                evidencePortListening,evidenceSynSent,args.tBool("sameUid",false),probe)
            if(diagnosis.get("selinuxLoopbackSuspect")?.asBoolean==true){
                throw ToolkitException(failure.code?:"FRIDA_CONNECT_FAILED",
                    "Frida listen 连接超时：跨 UID loopback 可能被 SELinux 静默 DROP（confidence="+diagnosis.get("confidence").asString+
                        "），这不代表组件没装。下一步：改用 Gadget interactionType=script（不碰网络）或把执行端放进目标同 UID；"+
                        "端口状态以主动探测 probe="+probe.get("probeResult").asString+" 为准（应用域读不到 /proc/net/tcp 是预期：SELinux 拒绝 proc_net_tcp_udp）；"+
                        "有 root/shizuku 时可再 grep -i ':" + ToolkitProcNetTcp.hexPort(gadgetPort) + "' /proc/net/tcp 交叉核对 state=0A(LISTEN) 与连接侧 SYN_SENT(02)。"+
                        "（本次自动解析：portHex=" + tcpEvidence.get("portHex")?.asString + " portListening=" + evidencePortListening +
                        " synSentObserved=" + evidenceSynSent + "）"+
                        "原始错误："+failure.message,
                    "endpoint")
            }
            // 反馈 #11：把 Frida 失败分类成结构化码（CONNECT_FAILED / SERVER_MISSING / COMPONENT_MISSING），
            // 否则 job 层拿到的 errorCode 是 null，调用方只能从 reason 文本里猜（apk_job_list 里就出现过）。
            throw ToolkitException(
                ToolkitFridaDiagnosis.errorCodeForReason(failure.message ?: ""),
                failure.message ?: "Frida 执行失败",
                failure.argument
            )
        }
        return response.apply{
            if(name=="frida_eval"){ ToolkitFridaEval.normalize(this) }
            if(name=="frida_status"){
                // §10：native 客户端可用 ≠ Java.perform 可用；java-bridge 必须单独报告。
                val bridgePresent=runCatching{component("frida-java-bridge").let{it.isDirectory||it.isFile}}.getOrDefault(false)
                add("javaBridge",fridaSessions.javaBridgeStatus(bridgePresent,null))
                // Bug 3：listen 模式的 SELinux 边界与 script 模式建议，一起返回，避免用户原地排查。
                add("listenModeLimits",ToolkitFridaDiagnosis.listenModeLimits())
                add("gadgetScriptMode",ToolkitFridaDiagnosis.gadgetScriptModeGuidance())
            }
            if(name=="frida_status"||name=="frida_processes"||name=="frida_eval"){
                // probe 是权威证据；tcpEvidence 只是可选补充（应用域读不到 /proc/net/tcp 是预期）。
                add("probe",probe)
                add("tcpEvidence",tcpEvidence)
                add("connectDiagnosis",ToolkitFridaDiagnosis.classifyConnectFailure("",
                    evidencePortListening,evidenceSynSent,args.tBool("sameUid",false),probe))
            }
        }
    }
    /**
     * entry_：静态入口分析。输入 path（工具目录内）或 workspaceId(+editSessionId) 二选一；
     * workspaceId 复用与 apk_* 工具相同的工作区目录布局（<filesDir>/apk-toolkit/<rootHash>/<workspaceId>/base.apk），
     * 只读校验 metadata 与（可选）编辑会话，不改任何状态。
     */
    private fun entryAnalysis(args:JsonObject):JsonObject {
        val input=entryInput(args)
        // 与 apk_inject_gadget 传给 ToolkitGadgetPreload.plan 的值完全一致（同一个 Build.SUPPORTED_ABIS 首选）。
        val preferredAbi=runCatching{android.os.Build.SUPPORTED_ABIS.firstOrNull()}.getOrNull()
        return ToolkitEntryAnalysis.analyze(input,preferredAbi)
    }
    private fun entryInput(args:JsonObject):File {
        val path=args.tString("path","").trim()
        val workspace=args.tString("workspaceId","").trim()
        require(path.isNotEmpty()||workspace.isNotEmpty()){"path 与 workspaceId 至少给一个（二选一）"}
        require(path.isEmpty()||workspace.isEmpty()){"path 与 workspaceId 只能给一个"}
        if(path.isNotEmpty())return file(args,limit=ToolkitIo.MAX_APK)
        val store=File(File(context.filesDir,"apk-toolkit"),ToolkitIo.version(root.path.toByteArray()).take(16))
        val directory=File(store,ToolkitIo.id(workspace))
        if(!File(directory,"workspace.json").isFile)throw ToolkitException("WORKSPACE_EXPIRED","工作区不存在或已过期："+workspace,"workspaceId")
        val session=args.tString("editSessionId","").trim()
        if(session.isNotEmpty()&&!File(File(File(directory,"sessions"),ToolkitIo.id(session)),"session.json").isFile){
            throw ToolkitException("SESSION_EXPIRED","编辑会话不存在或已过期："+session,"editSessionId")
        }
        val snapshot=File(directory,"base.apk")
        if(!snapshot.isFile)throw ToolkitException("WORKSPACE_EXPIRED","工作区快照 base.apk 不存在（可能已被 GC）："+workspace,"workspaceId")
        return snapshot
    }
    private fun injectGadget(args:JsonObject,temp:File):JsonObject {
        val detail=args.tString("detail","full").lowercase()
        require(detail in setOf("full","summary")){"detail 必须为 full 或 summary"}
        val organized=outputOrganizer.organize(ToolkitIo.resolve(root,args.tString("outPath")),args)
        val destination=organized.path
        require(!destination.exists()&&destination.parentFile?.isDirectory==true){"outPath 必须为父目录存在的新文件"}
        val input=stage(file(args,limit=ToolkitIo.MAX_APK),temp,"input.apk")
        val interaction=args.tString("interactionType","listen")
        require(interaction in setOf("listen","script")){"interactionType 只能是 listen 或 script"}
        val preloadMode=args.tString("preloadMode","provider").trim().lowercase()
        require(preloadMode in setOf("provider","dt_needed","clinit")) {"preloadMode 只能是 provider、dt_needed 或 clinit"}
        val preloadPlan=if(preloadMode=="dt_needed") ToolkitGadgetPreload.plan(input, args.tString("preloadTarget","").ifEmpty{null}, android.os.Build.SUPPORTED_ABIS.firstOrNull()) else null
        if(preloadPlan!=null&&!preloadPlan.applied){
            throw ToolkitException("PRELOAD_DT_NEEDED_FAILED",
                "DT_NEEDED 就地补丁失败，未产出 APK。details="+preloadPlan.json.toString(), "preloadTarget")
        }
        // clinit：只读预检 + 生成 Application 子类 DEX；失败在写出任何产物之前抛出（不产出半成品）。
        val clinitPlan=if(preloadMode=="clinit") ToolkitGadgetPreload.planClinit(input, temp, args.tString("clinitTarget","").ifEmpty{null}) else null
        if(clinitPlan!=null&&!clinitPlan.applied){
            throw ToolkitException(clinitPlan.json.get("reason")?.asString ?: "PRELOAD_CLINIT_UNSUPPORTED_APPLICATION",
                "clinit 提前加载准备失败，未产出 APK。details="+clinitPlan.json.toString(), "clinitTarget")
        }
        val preload=preloadPlan?.json ?: clinitPlan?.json ?: ToolkitGadgetPreload.assessment("provider")
        val unsigned=File(temp,"gadget-unsigned.apk")
        var scriptInfo:JsonObject?=null
        val effectiveScriptPath=if(interaction=="script"){
            val raw=args.tString("scriptPath","").ifEmpty{throw ToolkitException("SCRIPT_NOT_MATERIALIZED","script 模式必须提供 scriptPath","scriptPath")}
            val pkg=ToolkitGadget.packageName(input)
            val materialized=materializeGadgetScript(raw,pkg)
            scriptInfo=materialized
            materialized.tString("targetPath").also{require(it.isNotBlank()){"script 路径为空"}}
        } else null
        val info=ToolkitGadget.prepare(input,unsigned,temp,component("frida-gadget"),args.tInt("port",27042,1024,65535),
            android.os.Build.SUPPORTED_ABIS.firstOrNull()?:"unknown",
            interaction,
            effectiveScriptPath,
            // 反馈接线：schema 收了 providerProcess，调用方必须真传，否则逃生口/显式指定形同虚设。
            args.tString("providerProcess","").ifEmpty{null},
            patchedEntries = preloadPlan?.let { plan -> plan.targetEntry?.let { path -> mapOf(path to (plan.patched ?: byteArrayOf())) } ?: emptyMap() } ?: emptyMap(),
            clinit = clinitPlan?.hook)
        ToolkitArchiveAlignment.align(unsigned)
        val signed=File(temp,"gadget-signed.apk")
        var verification:JsonObject?=null
        var signerLabel=""
        try {
            val signer=AutoSigningIdentity.signer(temp,"MCP免Root Gadget集成")
            require(signer is KeyStoreApkSigner){"当前签名身份不支持归档签名"}
            signerLabel=(signer as? KeyStoreApkSigner)?.describe() ?: signer.javaClass.simpleName
            signer.signAlignedArchive(unsigned,signed)
            verification=ToolkitApkVerifier.inspect(signed,null,null)
            require(verification.getAsJsonObject("signature").tBool("verified")){"Gadget APK验签失败"}
            require(signed.length()<=ToolkitIo.MAX_APK){"输出APK超过512MiB"}
            ToolkitIo.withFileLocks(listOf(destination)) {
                require(!destination.exists()){"outPath 已存在，请使用新文件名"}
                ToolkitIo.atomic(destination){signed.copyTo(it,true)}
            }
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) throw failure
            // 反馈诉求：「失败也返回 {outputPath,signed,sha256} 并把"可安装"写清」。
            // 只有签名产物真的写出来了才这么回；否则原样抛出，不假装成功。
            val candidate=signed.takeIf{it.isFile&&it.length()>0}
            if (candidate==null) throw failure
            val target=runCatching{
                ToolkitIo.withFileLocks(listOf(destination)) {
                    if(!destination.exists()) ToolkitIo.atomic(destination){candidate.copyTo(it,true)}
                }
                destination.takeIf{it.isFile}
            }.getOrNull() ?: throw failure
            val reasons=gadgetPartialReasons(verification)
            if(reasons.size()==0) reasons.add(ToolkitIo.json("check" to "post_injection","status" to "failed",
                "errorIssueCount" to 1,"entry" to "","scope" to "target","owner" to "target",
                "upstream" to false,"impact" to "may-block-load","messages" to listOf(failure.message ?: failure.javaClass.simpleName)))
            val schemes=runCatching{ToolkitSignatures.read(target).getAsJsonArray("schemes") ?: JsonArray()}.getOrDefault(JsonArray())
            val note="可安装但注入验证未完成（" + (failure.message ?: failure.javaClass.simpleName) + "）：产物已重签，可直接安装后自行核对 Gadget 是否加载" + gadgetPartialNote(reasons)
            if(detail=="summary") return gadgetSummary(target,info,signerLabel,schemes,true,true,note,preload).apply { this.withOrganized(organized) }
            return ToolkitIo.json(
                "outputPath" to target.path, "path" to target.path, "size" to target.length(),
                "sha256" to runCatching{ToolkitIo.sha(target)}.getOrNull(),
                "signed" to true, "installable" to true, "partial" to true,
                "signer" to signerLabel, "schemesApplied" to schemes,
                "partialReasons" to reasons,
                "installed" to false, "originalSignaturePreserved" to false,
                "interactionType" to interaction,
                "providerProcess" to info.get("providerProcess"), "providerProcessSource" to info.get("providerProcessSource"),
                "note" to note,
                "errorCode" to (failure as? ToolkitException)?.code, "error" to failure.message,
                "nextActions" to ToolkitIo.json(
                    "install" to "安装该 APK（重签证书与原应用不同，覆盖安装可能失败，必要时保留数据重装）",
                    "verify" to "安装后用 frida_status / logcat 确认 Gadget 是否加载；注入未验证不代表不能加载",
                )).withOrganized(organized)
        }
        val partialReasons=gadgetPartialReasons(verification)
        val verificationStatus=verification?.tString("status","") ?: "unavailable"
        val onlyWarnings=partialReasons.size()>0 && partialReasons.all{it.asJsonObject.tBool("warningOnly")}
        val partial=verificationStatus!="valid" && !onlyWarnings
        val schemes=runCatching{ToolkitSignatures.read(destination).getAsJsonArray("schemes") ?: JsonArray()}.getOrDefault(JsonArray())
        val note=if(partial)
            "已重签并可安装；静态验证 status=" + verificationStatus + "，详见 partialReasons/verification；只有 warning 的检查不会造成 partial"
        else "已重签并通过静态验证；安装/启动仍需实测"
        val noteWithUpstream=note + gadgetPartialNote(partialReasons)
        if(detail=="summary") return gadgetSummary(destination,info,signerLabel,schemes,partial,true,noteWithUpstream,preload).apply { this.withOrganized(organized) }
        verification?.add("partialReasons",partialReasons)
        scriptInfo?.let { info.add("script", it) }
        info.add("preload", preload)
        return info.apply {
            addProperty("path",destination.path);addProperty("size",destination.length());addProperty("sha256",ToolkitIo.sha(destination))
            addProperty("signed",true);addProperty("installable",true);addProperty("partial",partial)
            addProperty("originalSignaturePreserved",false);addProperty("installed",false)
            addProperty("signer",signerLabel);add("schemesApplied",schemes);add("partialReasons",partialReasons);addProperty("note",noteWithUpstream)
            add("verification",verification)
            this.withOrganized(organized)
            // 只读对齐诊断（与 apk_build 同源：写入后 align、再由 verifier 复核本地头偏移）
            add("alignment",alignmentDiagnosticsJson(verification,true))
            // 反馈：script 模式不构建 endpoint/token，nextActions 也不能再引用它们（否则调用方拿到空值去连）。
            if(interaction=="script"){
                add("nextActions",ToolkitIo.json(
                    "install" to "安装输出APK；重签与原应用证书不同时不能直接覆盖，保留原数据再自行安排安装",
                    "launch" to "手动打开输出应用；script 模式不碰网络，加载后按 scriptPath 执行，不阻塞界面",
                    "check" to ToolkitIo.json("tool" to "frida_status","note" to "script 模式无 endpoint/token；用 frida_status 判断组件/服务状态即可"),
                    "script" to "确认 scriptPath 指向目标应用可读的文件（如 /data/local/tmp/a.js），否则不会执行",
                ))
            } else {
                add("nextActions",ToolkitIo.json(
                    "install" to "安装输出APK；重签与原应用证书不同时不能直接覆盖，保留原数据再自行安排安装",
                    "launch" to "手动打开输出应用；Gadget只在应用默认进程中加载，不阻塞界面",
                    "check" to ToolkitIo.json("tool" to "frida_status","args" to ToolkitIo.json("endpoint" to tString("endpoint"),"token" to tString("token"))),
                    "processes" to "frida_processes传相同endpoint/token，再用实际返回pid调用frida_eval；Java API需要打包frida-java-bridge",
                ))
            }
        }
    }
    /**
     * P1-2：把调用方给的脚本尽量放到目标 App 可达的 external files 目录，并把绝对路径写进 Gadget 配置。
     * best-effort：Android 11+ scoped storage 可能不允许我们的 UID 写别的 App 的 Android/data；
     * 失败时明确报错并给 chmod/迁移提示，不返回「看起来成功但实际不可达」的路径。
     */
    private fun materializeGadgetScript(raw:String, packageName:String):JsonObject {
        val source = runCatching { ToolkitIo.resolve(root, raw) }.getOrNull()?.takeIf { it.isFile }
            ?: File(raw).takeIf { it.isAbsolute && it.isFile }
            ?: throw ToolkitException(
                "SCRIPT_NOT_MATERIALIZED",
                "scriptPath 不是工具目录内的文件，也不是可读的绝对文件：$raw",
                "scriptPath")
        val target = ToolkitGadget.scriptTargetPath(packageName, source.name)
        val targetDir = target.parentFile
        val existed = target.isFile
        if(source.absoluteFile == target.absoluteFile && source.isFile) {
            return ToolkitIo.json(
                "sourcePath" to source.path,"targetPath" to target.absolutePath,"materialized" to true,
                "alreadyExisted" to true,"mode" to "already-at-app-external-files",
                "checks" to ToolkitIo.json("fileExists" to true,"readableByMcpUid" to source.canRead(),
                    "readableByTargetApp" to null,
                    "note" to "传入的已是目标路径；未复制。目标 App UID 可达性只能安装后验证，不能仅凭本进程可读推断。")
            )
        }
        val error = runCatching {
            if (!targetDir.isDirectory && !targetDir.mkdirs()) error("mkdirs failed: ${targetDir.path}")
            targetDir.setReadable(true, false); targetDir.setExecutable(true, false); targetDir.setWritable(true, false)
            source.copyTo(target, overwrite = true)
            target.setReadable(true, false)
            target.setExecutable(false, false)
            require(target.isFile && target.length() == source.length()) { "copied size mismatch" }
            require(target.canRead()) { "target not readable by current process" }
            // Do not claim the target app can read it: our UID is not the target UID.
        }.exceptionOrNull()
        if (error != null) {
            throw ToolkitException(
                "SCRIPT_NOT_MATERIALIZED",
                "无法把脚本写到目标 App 可达路径：source=" + source.path + " target=" + target.path + " error=" + error.message + "; " +
                    "Android 11+ 的 /sdcard/Android/data/<pkg>/files 可能只允许目标 App 自己或 shell 写；" +
                    "请用 Shizuku/文件管理器把 source 复制到 target 并 chmod 644 文件/755 目录，" +
                    "或显式传一个已在目标 App files 目录且可读的绝对 scriptPath。",
                "scriptPath")
        }
        return ToolkitIo.json(
            "sourcePath" to source.path,
            "targetPath" to target.absolutePath,
            "materialized" to true,
            "alreadyExisted" to existed,
            "directory" to targetDir.absolutePath,
            "mode" to "copy-to-app-external-files",
            "checks" to ToolkitIo.json(
                "fileExists" to target.isFile, "readableByMcpUid" to target.canRead(),
                "readableByTargetApp" to null,
                "note" to "readableByTargetApp=null：MCP UID 已确认自己可读；目标 App UID 的可达性只能安装后看 logcat/脚本执行，不能伪造。"
            ),
            "nextActions" to JsonArray().apply {
                add("若目标 App 仍报 Permission denied：用 Shizuku 执行 chmod 644 ${target.path} 与 chmod 755 ${targetDir.path}，或把脚本迁到目标 App 自己创建/拥有的 files 路径")
                add("不要使用相对路径；配置中必须是绝对路径")
            }
        )
    }

    private fun gadgetSummary(apk:File,info:JsonObject,signer:String,schemes:JsonArray,partial:Boolean,installable:Boolean,note:String,preload:JsonObject?=null):JsonObject =
        ToolkitIo.json(
            "path" to apk.path, "size" to apk.length(),
            "sha256" to runCatching{ToolkitIo.sha(apk)}.getOrNull(),
            "signed" to true, "signer" to signer, "schemesApplied" to schemes,
            "providerProcess" to info.get("providerProcess"), "providerProcessSource" to info.get("providerProcessSource"),
            "partial" to partial, "installable" to installable, "note" to note,
            "script" to info.get("script"),
            "preload" to preload,
        )
    private fun radare(name:String,args:JsonObject,temp:File):JsonObject {
        val c=native("radare2");val input=stage(file(args),temp,"target.bin")
        fun query(command:String):JsonElement {
            val result=execute(listOf(File(c,"bin/radare2").path,"-N","-2","-q","-e","scr.color=0","-e","scr.interactive=false","-c",command+";q",input.path),temp,env(c),args)
            if(result.exitCode!=0)throw ToolkitException("COMPONENT_FAILED","radare2 exit=${result.exitCode}: ${result.text.takeLast(4000)}")
            return runCatching{JsonParser().parse(result.text.trim())}.getOrElse{throw ToolkitException("COMPONENT_INVALID_RESULT","radare2 没有返回有效JSON：${result.text.takeLast(2000)}")}
        }
        fun paged(command:String):JsonObject {
            val data=File(temp,"radare-page.json")
            val result=execute(listOf(File(c,"bin/radare2").path,"-N","-2","-q","-e","scr.color=0","-e","scr.interactive=false","-c","aaa;$command >${data.path};q",input.path),temp,env(c),args,listOf(data))
            require(result.exitCode==0&&data.isFile&&data.length()<=64L*1024*1024){"radare2没有返回有界结果"}
            val offset=args.tInt("offset",0,0,1000000);val limit=args.tInt("limit",50,1,200);val items=JsonArray();var total=0
            JsonReader(data.reader(Charsets.UTF_8)).use{reader->reader.beginArray();while(reader.hasNext()){
                ToolkitIo.ensureActive();require(total<1000000){"radare2项目数量超过100万"}
                if(total in offset until offset+limit)items.add(JsonParser().parse(reader))else reader.skipValue();total++
            };reader.endArray()}
            require(offset<=total){"offset 超出结果范围"};val end=minOf(total,offset+limit)
            return ToolkitIo.json("items" to items,"offset" to offset,"nextOffset" to end,"hasMore" to (end<total),"total" to total,"backend" to "radare2","resultBudgetBytes" to 67108864)
        }
        val sourceHash=ToolkitIo.sha(input)
        return when(name){
            "native_analyze"->ToolkitIo.json("backend" to "radare2","sha256" to sourceHash,"info" to query("ij"),"executesTarget" to false)
            "native_functions"->paged("aflj").apply{addProperty("sha256",sourceHash);addProperty("heuristic",true)}
            "native_xrefs"->{
                val relocsApply=args.tBool("relocsApply",false)
                val page=paged(relocationPrelude(args)+"axtj ${ToolkitReverseTools.address(args.tString("address"))}").apply{
                    addProperty("sha256",sourceHash);addProperty("indirectReferencesComplete",false)}
                if(args.tBool("includeEvidence",true))attachXrefEvidence(page,args)
                ToolkitDisasmEvidence.annotate(page,"axtj",relocsApply)
            }
            "native_function_detail"->{
                val relocsApply=args.tBool("relocsApply",false)
                ToolkitDisasmEvidence.annotate(ToolkitIo.json("sha256" to sourceHash,
                    "function" to query(relocationPrelude(args)+"aaa;s ${ToolkitReverseTools.address(args.tString("address"))};pdfj"),
                    "backend" to "radare2"),"pdfj",relocsApply)
            }
            "native_decompile"->{
                val plugin=native("r2ghidra");val library=File(plugin,"lib/core_ghidra.so");require(library.isFile){"r2ghidra组件缺少插件"}
                // Only this pinned component is explicitly loaded; user r2rc and plugin directories stay disabled.
                val pluginEnv=env(c)+mapOf("SLEIGHHOME" to File(plugin,"share/r2ghidra").path,
                    "LD_LIBRARY_PATH" to File(plugin,"lib").path+File.pathSeparator+File(c,"lib").path)
                val address=ToolkitReverseTools.address(args.tString("address"))
                val result=execute(listOf(File(c,"bin/radare2").path,"-N","-2","-q","-e","scr.color=0","-c","L ${library.path};aaa;s $address;pdgj;q",input.path),temp,pluginEnv,args)
                val parsed=runCatching{JsonParser().parse(result.text.trim())}.getOrNull()
                require(result.exitCode==0&&parsed!=null&&parsed.isJsonObject&&parsed.asJsonObject.get("code")?.asString?.isNotBlank()==true){"r2ghidra没有返回伪代码：${result.text.takeLast(4000)}"}
                // §8：默认剔除注解（伪 C 只有几 KB，注解后可能上百 KB），并把函数边界来源与证据分级一起返回。
                val parsedObject=parsed.asJsonObject
                val detectedSize=parsedObject.get("size")?.takeIf{it.isJsonPrimitive}?.asInt
                val projection=ToolkitDecompileView.project(
                    ToolkitDecompileView.RawDecompile(
                        code=parsedObject.get("code")?.takeIf{it.isJsonPrimitive}?.asString?:"",
                        annotations=annotationStrings(parsedObject),
                        warnings=stringList(parsedObject,"warnings"),
                        coverage=parsedObject.get("coverage")?.takeIf{it.isJsonPrimitive}?.asString,
                        functionAddress=address,
                        detectedSize=detectedSize),
                    includeAnnotations=args.tBool("includeAnnotations",false),
                    maxCodeChars=args.tInt("maxCodeChars",ToolkitDecompileView.DEFAULT_MAX_CODE_CHARS,1024,ToolkitDecompileView.MAX_CODE_CHARS_LIMIT))
                val boundary=ToolkitDecompileView.boundary(
                    forceFunction=args.tBool("forceFunction",false),
                    forcedSize=args.get("functionSize")?.takeIf{it.isJsonPrimitive}?.asInt,
                    cfgSize=null,
                    symbolRange=null,
                    detectedSize=detectedSize)
                ToolkitDisasmEvidence.annotate(ToolkitIo.json("backend" to "r2ghidra","sha256" to sourceHash,
                    "rebuildGuaranteed" to false,"result" to projection,"boundary" to boundary),"pdg",false)
            }
            "native_compare"->{val other=stage(file(args,"otherPath"),temp,"other.bin");ToolkitBinaryCompare.compare(input,other,args).apply{add("info",query("ij"));addProperty("backend","radare2+bounded-byte-diff")}}
            else->error("未知逆向操作：$name")
        }
    }
    private fun page(items:JsonArray,args:JsonObject):JsonObject {
        val offset=args.tInt("offset",0,0,1000000);val limit=args.tInt("limit",50,1,200);require(offset<=items.size()){"offset 超出结果范围"}
        val end=minOf(items.size(),offset+limit)
        return ToolkitIo.json("items" to JsonArray().apply{for(i in offset until end)add(items[i])},"offset" to offset,"nextOffset" to end,"hasMore" to (end<items.size()),"total" to items.size(),"backend" to "radare2")
    }
    private fun apktool(name:String,args:JsonObject,temp:File):JsonObject {
        val c=component("apktool");val payload=File(c,"apktool.dex.jar");require(payload.isFile){"Apktool组件不完整"}
        val framework=File(context.noBackupFilesDir,"apk-frameworks").apply{mkdirs()}
        val subcommand=mutableListOf<String>()
        var output:File?=null;var projectTarget:File?=null
        var outputOrganized:ToolkitOutputOrganizer.OrganizedPath?=null
        // 任务2：staging 诊断（copy/in_place），只在 apk_build_project 成功返回中暴露。
        var stagingMode="copy"
        var copyMs=0L
        var copiedEntries=0
        var skippedEntries=0
        var skippedBytes=0L
        var inPlaceRequested=false
        var inPlaceFallbackReason:String?=null
        var inPlaceProject:File?=null
        var sourceHashBefore:String?=null
        var sourceHashAfter:String?=null
        var sourceTreeUnchanged=false
        var inPlaceCleanupOk=true
        when(name){
            "apk_decode_project"->{val input=stage(file(args,limit=ToolkitIo.MAX_APK),temp,"input.apk");validateArchive(input)
                outputOrganized=outputOrganizer.organize(ToolkitIo.resolve(root,args.tString("outDir")),args)
                projectTarget=outputOrganized!!.path;require(!projectTarget.exists()&&projectTarget.parentFile?.isDirectory==true){"outDir 必须是父目录存在的新目录"}
                output=File(temp,"decoded");subcommand+=listOf("d",input.path,"-o",output.path,"-p",framework.path,"-j","1")
                if(!args.tBool("decodeResources",true))subcommand+="-r"
            }
            "apk_build_project"->{val project=ToolkitIo.resolve(root,args.tString("projectDir"));require(project.isDirectory&&File(project,"apktool.yml").isFile){"projectDir 不是Apktool解包工程"}
                inPlaceRequested=args.tBool("buildInPlace",false)
                var buildRoot=project
                if(inPlaceRequested){
                    var refusal=if(project.canonicalFile==root.canonicalFile)"project_is_root" else ToolkitReverseFiles.inPlaceRefusal(project)
                    if(refusal==null){
                        val before=runCatching{ToolkitReverseFiles.sourceTreeSha256(project)}
                        sourceHashBefore=before.getOrNull()
                        if(sourceHashBefore==null){
                            refusal="source_hash_failed:"+(before.exceptionOrNull()?.message?:"unknown")
                        }
                    }
                    if(refusal==null){
                        stagingMode="in_place";inPlaceProject=project
                    }else{
                        inPlaceFallbackReason=refusal;sourceHashBefore=null
                    }
                }
                if(stagingMode=="copy"){
                    val copied=File(temp,"project").apply{mkdir()}
                    val started=System.nanoTime()
                    val stats=ToolkitReverseFiles.copyTreeDetailed(project,copied)
                    copyMs=(System.nanoTime()-started)/1_000_000L
                    copiedEntries=stats.copiedEntries;skippedEntries=stats.skippedEntries;skippedBytes=stats.skippedBytes
                    buildRoot=copied
                }
                val aapt=native("apk-resources");val wrapper=File(temp,"aapt2").apply{
                    writeText("#!/system/bin/sh\nLD_LIBRARY_PATH=${McpAndroidTerminal.quote(File(aapt,"lib").path)} exec ${McpAndroidTerminal.quote(File(aapt,"bin/aapt2").path)} \"\$@\"\n",Charsets.UTF_8);require(setExecutable(true,true))}
                output=File(temp,"unsigned.apk");subcommand+=listOf("b",buildRoot.path,"--aapt",wrapper.path,"--no-crunch","-p",framework.path,"-o",output.path,"-j","1")
            }
            "apk_install_framework"->{val input=stage(file(args,limit=ToolkitIo.MAX_ENTRY.toLong()),temp,"framework.apk");validateArchive(input)
                subcommand+=listOf("if",input.path,"-p",framework.path)
                val tag=args.tString("tag","");if(tag.isNotEmpty()){require(tag.matches(Regex("[A-Za-z0-9_.-]{1,64}"))){"tag 无效"};subcommand+=listOf("-t",tag)}
            }
        }
        // Do not put Termux libz/libc++ ahead of ART system libraries. Only the aapt child gets LD_LIBRARY_PATH.
        val command=apktoolCommand(temp,payload,context.applicationInfo.sourceDir,framework,subcommand)
        var result:ToolkitProcess.Result?=null
        var executionFailure:Throwable?=null
        try{
            result=execute(command,temp,mapOf("CLASSPATH" to payload.path+File.pathSeparator+context.applicationInfo.sourceDir,"LD_LIBRARY_PATH" to ""),args)
        }catch(failure:Throwable){
            executionFailure=failure
        }finally{
            if(stagingMode=="in_place")inPlaceCleanupOk=cleanupInPlaceGenerated(inPlaceProject!!)
        }
        if(stagingMode=="in_place"){
            val project=inPlaceProject!!
            sourceHashAfter=runCatching{ToolkitReverseFiles.sourceTreeSha256(project)}.getOrElse{failure->
                throw ToolkitException("IN_PLACE_SOURCE_HASH_FAILED","in_place 构建后无法计算工程树 SHA-256：${failure.message}; 请用 buildInPlace=false 复制模式重试","projectDir")}
            sourceTreeUnchanged=sourceHashBefore?.let{it==sourceHashAfter}==true
            if(!sourceTreeUnchanged)throw ToolkitException("IN_PLACE_SOURCE_MODIFIED","in_place 构建前后工程树 SHA-256 不一致（before=${sourceHashBefore?.take(16)}… after=${sourceHashAfter?.take(16)}…）：源工程可能被改动，已中止；请用 buildInPlace=false 复制模式重试","projectDir")
            if(!inPlaceCleanupOk)throw ToolkitException("IN_PLACE_CLEANUP_FAILED","in_place 构建后清理 build/dist 失败：请检查工程目录权限，或改用 buildInPlace=false 复制模式","projectDir")
        }
        executionFailure?.let{throw it}
        val completed=result!!
        if(completed.exitCode!=0)throw ToolkitException("APKTOOL_FAILED","Apktool exit=${completed.exitCode}（子进程堆 -Xmx${McpLimits.CHILD_HEAP_MB}m）: ${completed.text.takeLast(12000)}")
        if(name=="apk_install_framework")return ToolkitIo.json("installed" to true,"scope" to "apktool-private-framework-cache","diagnostics" to completed.text.takeLast(12000))
        if(name=="apk_decode_project") {
            require(output!!.isDirectory&&File(output,"apktool.yml").isFile){"解包没有生成有效工程"}
            ToolkitReverseFiles.publishTree(output,projectTarget!!)
            return ToolkitIo.json("path" to projectTarget.path,"backend" to "apktool","decoded" to true,"diagnostics" to completed.text.takeLast(12000),"rebuildGuaranteed" to false).withOrganized(outputOrganized)
        }
        require(output!!.isFile){"回编译没有生成APK"};validateArchive(output)
        ToolkitArchiveAlignment.align(output)
        var verification:JsonObject?=null
        if(args.tBool("sign")){val signed=File(temp,"signed.apk");val signer=AutoSigningIdentity.signer(temp,"MCP Apktool回编译")
            require(signer is KeyStoreApkSigner){"当前签名身份不支持归档签名"};signer.signAlignedArchive(output,signed);output=signed
            verification=ToolkitApkVerifier.inspect(output,null,null)
            require(verification.getAsJsonObject("signature").tBool("verified")){"回编译APK验签失败"}
        }
        outputOrganized=outputOrganizer.organize(ToolkitIo.resolve(root,args.tString("outPath")),args)
        val destination=outputOrganized!!.path
        ToolkitIo.withFileLocks(listOf(destination)){require(!destination.exists()){"outPath 已存在，请使用新文件名"};ToolkitIo.atomic(destination){output.copyTo(it,true)}}
        return ToolkitIo.json(
            "path" to destination.path,
            "sha256" to ToolkitIo.sha(destination),
            "size" to destination.length(),
            "signed" to args.tBool("sign"),
            "verification" to verification,
            "alignment" to alignmentDiagnosticsJson(verification,true),
            "backend" to "apktool",
            "stagingMode" to stagingMode,
            "buildInPlace" to (stagingMode=="in_place"),
            "buildInPlaceRequested" to inPlaceRequested,
            "inPlaceFallbackReason" to inPlaceFallbackReason,
            "copyMs" to copyMs,
            "copiedEntries" to copiedEntries,
            "skippedEntries" to skippedEntries,
            "skippedBytes" to skippedBytes,
            "sourceTreeSha256Before" to sourceHashBefore,
            "sourceTreeSha256After" to sourceHashAfter,
            "sourceTreeUnchanged" to if(stagingMode=="in_place") sourceTreeUnchanged else null,
            "sourceTreeVerification" to if(stagingMode=="in_place") "sha256_before_after" else "not_needed_copy_mode",
            "diagnostics" to completed.text.takeLast(12000),
        ).withOrganized(outputOrganized)
    }
    /** 删除 in_place 构建新建的 apktool 输出目录；只在预检确认 build/dist 原本不存在时调用。 */
    private fun cleanupInPlaceGenerated(project:File):Boolean{
        var ok=true
        for(name in listOf("build","dist")){val dir=File(project,name);if(dir.exists()&&!dir.deleteRecursively())ok=false}
        return ok&&!File(project,"build").exists()&&!File(project,"dist").exists()
    }
    /**
     * §10 会话工具：只做内存态登记、分页与清理，不冒充完成真实 attach。
     * endpoint/token 在响应里是掩码；真实值只用于注入口（当前版本由调用方用 frida_eval 执行）。
     */
    private fun fridaSession(name:String,args:JsonObject):JsonObject = when(name){
        "frida_session_open"->{
            val target=ToolkitFridaSession.Target(
                packageName=args.tString("packageName"),
                pid=args.get("pid")?.takeIf{!it.isJsonNull}?.asInt,
                endpoint=args.tString("endpoint","127.0.0.1:27042"),
                token=args.tString("token","").ifEmpty{null})
            val componentAvailable=runCatching{component("frida").isDirectory}.getOrDefault(false)
            val opened=fridaSessions.open(target,args.tString("script"))
            opened.addProperty("componentAvailable",componentAvailable)
            opened.addProperty("attached",false)
            opened.addProperty("attachSupported",false)
            opened.addProperty("attachNote","本版本 open 只登记会话与消息通道，不建立持久 attach；要真实执行请用 frida_eval，或等待后续持久会话实现")
            opened
        }
        "frida_session_read"->fridaSessions.read(args.tString("sessionId"),args.tInt("offset",0,0,1000000),args.tInt("limit",50,1,500))
        "frida_session_close"->fridaSessions.stop(args.tString("sessionId"))
        "frida_session_list"->fridaSessions.list()
        else->error("未知 Frida 会话操作：$name")
    }
    /**
     * §2.3：raw 反汇编/函数 JSON/axtj 默认不解析重定位，adrp/add 目标地址不可见——
     * 用户因此在 213 壳批扫里白跑一轮。relocsApply=true 时必须在 aaa **之前**注入配置命令。
     */
    private fun relocationPrelude(args:JsonObject):String=
        if(args.tBool("relocsApply",false))ToolkitDisasmEvidence.RELOC_HARDENING_COMMAND+";" else ""

    /** r2ghidra 的 annotations 可能是字符串数组或对象数组；两种都要能读成可读字符串。 */
    private fun annotationStrings(obj:JsonObject):List<String>{
        val array=obj.getAsJsonArray("annotations")?:return emptyList()
        return array.mapNotNull{element->
            if(element!=null&&element.isJsonPrimitive)element.asString
            else element?.takeIf{it.isJsonObject}?.asJsonObject?.let{item->
                val parts=ArrayList<String>()
                item.get("type")?.takeIf{it.isJsonPrimitive}?.asString?.let{parts.add(it)}
                item.get("start")?.takeIf{it.isJsonPrimitive}?.asString?.let{parts.add(it)}
                item.get("text")?.takeIf{it.isJsonPrimitive}?.asString?.let{parts.add(it)}
                parts.joinToString("@").ifEmpty{null}}
        }
    }
    private fun stringList(obj:JsonObject,key:String):List<String>{
        val array=obj.getAsJsonArray(key)?:return emptyList()
        return array.mapNotNull{element->element?.takeIf{it.isJsonPrimitive}?.asString}
    }
    /** §8：给每条交叉引用标注证据类型，并汇总；明确「没有直接引用 ≠ 没有调用者」。 */
    private fun attachXrefEvidence(page:JsonObject,args:JsonObject):JsonObject{
        val counts=LinkedHashMap<String,Int>()
        page.getAsJsonArray("items")?.forEach{element->
            if(element==null||!element.isJsonObject)return@forEach
            val item=element.asJsonObject
            val rawKind=item.get("type")?.takeIf{it.isJsonPrimitive}?.asString
                ?:item.get("opcode")?.takeIf{it.isJsonPrimitive}?.asString?:""
            val kind=ToolkitDecompileView.classifyXref(rawKind)
            item.addProperty("evidenceKind",kind)
            item.add("evidence",ToolkitDecompileView.xrefEvidence(rawKind,
                hasRelocation=item.has("refsize")||item.has("reloc"),
                staticallyResolvable=item.has("from"),
                dynamicCovered=args.tBool("dynamicCovered",false)))
            counts[kind]=(counts[kind]?:0)+1
        }
        page.add("evidenceKinds",JsonObject().apply{counts.forEach{(kind,count)->addProperty(kind,count)}})
        page.addProperty("callerAbsenceConclusive",false)
        page.addProperty("callerCoverageNote","没有直接引用不等于没有调用者：未覆盖的间接/动态调用可能仍存在")
        return page
    }
    private fun validateArchive(file:File) {
        ZipFile(file).use{zip->var total=0L;var count=0;val names=HashSet<String>()
            zip.entries().asSequence().forEach{entry->ToolkitIo.ensureActive();ToolkitIo.entry(entry.name);require(names.add(entry.name)&&++count<=30000){"APK条目重复或过多"}
                require(entry.size in 0..ToolkitIo.MAX_APK){"APK条目大小无效"};total+=entry.size;require(total<=ToolkitIo.MAX_APK){"APK展开超过512MiB"}}
        }
    }
}

/**
 * 把 verifier.native 的 issue 按 entry 拆成 partialReasons，避免把目标 APK 自带库误归因给注入 Gadget。
 * 这里只做结构化与归因，不改变 verifier 的判定结果。
 */
internal fun gadgetPartialReasons(verification:JsonObject?):JsonArray {
    val reasons=JsonArray()
    if(verification==null) return reasons
    val checks=verification.getAsJsonObject("checks")
    checks?.entrySet()?.forEach { (name,value) ->
        val status=value.asJsonObject.tString("status","")
        if(status!="valid"){
            val issues=verification.getAsJsonObject(name)?.getAsJsonArray("issues")?.map{it.asJsonObject} ?: emptyList()
            val errors=issues.filter{it.tString("severity","")=="error"}
            val selected=if(errors.isNotEmpty()) errors else issues.take(1)
            if(selected.isEmpty()) reasons.add(gadgetPartialReason(name,status,null))
            else selected.forEach { issue -> reasons.add(gadgetPartialReason(name,status,issue)) }
        }
    }
    if(reasons.size()==0 && verification.tString("status","")!="valid")
        reasons.add(gadgetPartialReason("verification",verification.tString("status",""),null))
    return reasons
}

private fun gadgetPartialReason(check:String,status:String,issue:JsonObject?):JsonObject {
    val entry=issue?.tString("entry","") ?: ""
    val scope=if(gadgetEntryIsInjected(entry)) "injected" else "target"
    val severity=issue?.tString("severity","") ?: ""
    val code=issue?.tString("code","") ?: ""
    val message=issue?.tString("message","") ?: ""
    val reason=ToolkitIo.json(
        "check" to check,
        "status" to status,
        "errorIssueCount" to if(issue!=null && severity=="error") 1 else 0,
        "warningOnly" to (issue!=null && severity!="error"),
        "entry" to entry,
        "scope" to scope,
        "owner" to scope,
        "upstream" to (scope=="injected"),
        "impact" to if(scope=="injected") "static-check-only" else "may-block-load",
        "errorCodes" to if(code.isNotEmpty()) listOf(code) else emptyList<String>(),
        "messages" to if(message.isNotEmpty()) listOf(message) else emptyList<String>(),
    )
    // 透传 verifier issue 已有结构化字段（segmentIndex/pAlign/fileOffset 等）；不覆盖归因字段。
    issue?.entrySet()?.forEach { (key,value) -> if(!reason.has(key)) reason.add(key,value) }
    return reason
}

private fun gadgetEntryIsInjected(entry:String):Boolean {
    // 本工具注入的文件名固定为 libaijsgadget.so；目标 APK 自带的库即使也叫 frida-gadget 也不是我们注入的。
    return entry.substringAfterLast('/').equals("libaijsgadget.so",ignoreCase=true)
}

/** full/summary 共用的 note；只对确实出现的 entry 追加说明，不把目标库甩锅给 Gadget。 */
internal fun gadgetPartialNote(reasons:JsonArray):String {
    fun is16k(r:JsonObject):Boolean =
        r.tString("code","")=="ELF_PT_LOAD_ALIGNMENT_16K" ||
            r.getAsJsonArray("errorCodes")?.any { it.asString=="ELF_PT_LOAD_ALIGNMENT_16K" }==true
    val target=reasons.any { val r=it.asJsonObject; r.tString("scope","")=="target" && is16k(r) }
    val injected=reasons.any { val r=it.asJsonObject; r.tString("scope","")=="injected" && is16k(r) }
    return when {
        target && !injected ->
            "；partial 原因来自目标 APK 自带 native 库的 PT_LOAD 页对齐（见 partialReasons[].entry）；本工具注入的 Gadget（64 位 ABI）本次检测通过。当前测试机为 4096 页，实测不影响加载；16 KiB 页设备/上架校验会判不合格，需目标提供方重编该库"
        target && injected ->
            "；partial 原因同时来自目标 APK 自带 native 库和注入的 Gadget 库的 PT_LOAD 页对齐（见 partialReasons[].entry）；当前测试机为 4096 页，实测不影响加载；16 KiB 页设备/上架校验会判不合格，需对应提供方重编该库"
        injected ->
            "；partial 原因来自注入的 Frida Gadget 库的 PT_LOAD 页对齐（见 partialReasons[].entry）；当前测试机为 4096 页，实测不影响加载；16 KiB 页设备/上架校验会判不合格"
        else -> ""
    }
}
