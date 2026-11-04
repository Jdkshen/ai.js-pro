package com.jdkshen.aijspro.mcp

import android.content.Context
import com.google.gson.*
import com.jdkshen.aijspro.autojs.build.sign.AutoSigningIdentity
import com.jdkshen.aijspro.autojs.build.sign.KeyStoreApkSigner
import com.stardust.autojs.runtime.tool.ToolchainRuntime
import java.io.File
import java.util.zip.ZipFile
import com.google.gson.stream.JsonReader

/** Typed reverse operations. Target bytes are staged; optional engines run in owned child VMs. */
internal class AndroidReverseRuntime(private val context:Context,private val root:File,private val component:(String)->File) {
    /** §10：有界观测会话（内存态；真实 attach 仍走 frida_eval，本类只负责登记/分页/清理）。 */
    private val fridaSessions = ToolkitFridaSession()
    /** §7：有状态 native 分析会话（状态落盘在 noBackupFilesDir，不启动 r2 进程）。 */
    private val nativeAnalysis = ToolkitNativeAnalysis(File(context.noBackupFilesDir,"native-analysis"))
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
            "native_analysis_open"->nativeAnalysis.open(args.tString("path"),args.tString("sha256"),optionalString(args,"abi"),optionalString(args,"sessionId"))
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
        ToolkitProcess.run(context,command,temp,env,args.tInt("timeoutSeconds",120,1,300),watchedFiles)
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
    private fun frida(name:String,args:JsonObject,temp:File):JsonObject {
        val c=native("frida");val endpoint=args.tString("endpoint","127.0.0.1:27042")
        require(endpoint.matches(Regex("[A-Za-z0-9_.-]{1,253}:[0-9]{1,5}"))&&endpoint.substringAfterLast(':').toInt() in 1..65535){"endpoint 必须为host:port"}
        val request=ToolkitIo.json("operation" to name.removePrefix("frida_"),"endpoint" to endpoint,"timeoutMs" to 12000)
        val token=args.tString("token","")
        require(token.isEmpty()||token.matches(Regex("[A-Za-z0-9_-]{16,128}"))){"token 必须是16..128位字母数字、横线或下划线"}
        if(token.isNotEmpty()&&!File(c,"gadget-token-support.txt").isFile)throw ToolkitException("COMPONENT_UPDATE_REQUIRED","请调用apk_component_install更新frida客户端至支持Gadget令牌的版本","component")
        if(token.isNotEmpty())request.addProperty("token",token)
        if(name=="frida_eval") { val script=args.tString("script");require(script.toByteArray(Charsets.UTF_8).size<=65536){"Frida脚本超过64KiB"}
            request.addProperty("pid",args.tInt("pid",0,1,Int.MAX_VALUE));request.addProperty("script",script);request.addProperty("durationMs",args.tInt("durationMs",1000,1,10000)) }
        val input=File(temp,"request.json").apply{writeText(request.toString(),Charsets.UTF_8)}
        return runner(listOf(File(c,"bin/aijs-frida").path,input.path),temp,c,args).apply{
            if(name=="frida_eval"){
                val errors=getAsJsonArray("messages")?.count{it.isJsonObject&&it.asJsonObject.tString("type","")=="error"}?:0
                addProperty("scriptErrorCount",errors);addProperty("scriptSucceeded",errors==0)
                if(errors>0)throw ToolkitException("FRIDA_SCRIPT_ERROR",toString().take(12000),"script")
            }
            if(name=="frida_status"){
                // §10：native 客户端可用 ≠ Java.perform 可用；java-bridge 必须单独报告。
                val bridgePresent=runCatching{component("frida-java-bridge").let{it.isDirectory||it.isFile}}.getOrDefault(false)
                add("javaBridge",fridaSessions.javaBridgeStatus(bridgePresent,null))
            }
        }
    }
    private fun injectGadget(args:JsonObject,temp:File):JsonObject {
        val destination=ToolkitIo.resolve(root,args.tString("outPath"))
        require(!destination.exists()&&destination.parentFile?.isDirectory==true){"outPath 必须为父目录存在的新文件"}
        val input=stage(file(args,limit=ToolkitIo.MAX_APK),temp,"input.apk")
        val unsigned=File(temp,"gadget-unsigned.apk")
        val info=ToolkitGadget.prepare(input,unsigned,temp,component("frida-gadget"),args.tInt("port",27042,1024,65535),
            android.os.Build.SUPPORTED_ABIS.firstOrNull()?:"unknown")
        ToolkitArchiveAlignment.align(unsigned)
        val signed=File(temp,"gadget-signed.apk")
        val signer=AutoSigningIdentity.signer(temp,"MCP免Root Gadget集成")
        require(signer is KeyStoreApkSigner){"当前签名身份不支持归档签名"}
        signer.signAlignedArchive(unsigned,signed)
        val verification=ToolkitApkVerifier.inspect(signed,null,null)
        require(verification.getAsJsonObject("signature").tBool("verified")){"Gadget APK验签失败"}
        require(signed.length()<=ToolkitIo.MAX_APK){"输出APK超过512MiB"}
        ToolkitIo.withFileLocks(listOf(destination)) {
            require(!destination.exists()){"outPath 已存在，请使用新文件名"}
            ToolkitIo.atomic(destination){signed.copyTo(it,true)}
        }
        return info.apply {
            addProperty("path",destination.path);addProperty("size",destination.length());addProperty("sha256",ToolkitIo.sha(destination))
            addProperty("signed",true);addProperty("originalSignaturePreserved",false);addProperty("installed",false)
            add("verification",verification)
            add("nextActions",ToolkitIo.json("install" to "安装输出APK；重签与原应用证书不同时不能直接覆盖，保留原数据再自行安排安装",
                "launch" to "手动打开输出应用；Gadget只在应用默认进程中加载，不阻塞界面",
                "check" to ToolkitIo.json("tool" to "frida_status","args" to ToolkitIo.json("endpoint" to tString("endpoint"),"token" to tString("token"))),
                "processes" to "frida_processes传相同endpoint/token，再用实际返回pid调用frida_eval；Java API需要打包frida-java-bridge"))
        }
    }
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
                val page=paged("axtj ${ToolkitReverseTools.address(args.tString("address"))}").apply{
                    addProperty("sha256",sourceHash);addProperty("indirectReferencesComplete",false)}
                if(args.tBool("includeEvidence",true))attachXrefEvidence(page,args)
                page
            }
            "native_function_detail"->ToolkitIo.json("sha256" to sourceHash,"function" to query("aaa;s ${ToolkitReverseTools.address(args.tString("address"))};pdfj"),"backend" to "radare2")
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
                ToolkitIo.json("backend" to "r2ghidra","sha256" to sourceHash,"rebuildGuaranteed" to false,
                    "result" to projection,"boundary" to boundary)
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
        val command=mutableListOf("/system/bin/app_process","-Djava.io.tmpdir=${temp.path}","-Duser.home=${temp.path}","/system/bin","--nice-name=aijs-apktool",ToolkitCliMain::class.java.name,"brut.apktool.Main")
        var output:File?=null;var projectTarget:File?=null
        when(name){
            "apk_decode_project"->{val input=stage(file(args,limit=ToolkitIo.MAX_APK),temp,"input.apk");validateArchive(input)
                projectTarget=ToolkitIo.resolve(root,args.tString("outDir"));require(!projectTarget.exists()&&projectTarget.parentFile?.isDirectory==true){"outDir 必须是父目录存在的新目录"}
                output=File(temp,"decoded");command+=listOf("d",input.path,"-o",output.path,"-p",framework.path,"-j","1")
                if(!args.tBool("decodeResources",true))command+="-r"
            }
            "apk_build_project"->{val project=ToolkitIo.resolve(root,args.tString("projectDir"));require(project.isDirectory&&File(project,"apktool.yml").isFile){"projectDir 不是Apktool解包工程"}
                val copied=File(temp,"project").apply{mkdir()};ToolkitReverseFiles.copyTree(project,copied)
                val aapt=native("apk-resources");val wrapper=File(temp,"aapt2").apply{
                    writeText("#!/system/bin/sh\nLD_LIBRARY_PATH=${McpAndroidTerminal.quote(File(aapt,"lib").path)} exec ${McpAndroidTerminal.quote(File(aapt,"bin/aapt2").path)} \"\$@\"\n",Charsets.UTF_8);require(setExecutable(true,true))}
                output=File(temp,"unsigned.apk");command+=listOf("b",copied.path,"--aapt",wrapper.path,"--no-crunch","-p",framework.path,"-o",output.path,"-j","1")
            }
            "apk_install_framework"->{val input=stage(file(args,limit=ToolkitIo.MAX_ENTRY.toLong()),temp,"framework.apk");validateArchive(input)
                command+=listOf("if",input.path,"-p",framework.path)
                val tag=args.tString("tag","");if(tag.isNotEmpty()){require(tag.matches(Regex("[A-Za-z0-9_.-]{1,64}"))){"tag 无效"};command+=listOf("-t",tag)}
            }
        }
        // Do not put Termux libz/libc++ ahead of ART system libraries. Only the aapt child gets LD_LIBRARY_PATH.
        val result=execute(command,temp,mapOf("CLASSPATH" to payload.path+File.pathSeparator+context.applicationInfo.sourceDir,"LD_LIBRARY_PATH" to ""),args)
        if(result.exitCode!=0)throw ToolkitException("APKTOOL_FAILED","Apktool exit=${result.exitCode}: ${result.text.takeLast(12000)}")
        if(name=="apk_install_framework")return ToolkitIo.json("installed" to true,"scope" to "apktool-private-framework-cache","diagnostics" to result.text.takeLast(12000))
        if(name=="apk_decode_project") {
            require(output!!.isDirectory&&File(output,"apktool.yml").isFile){"解包没有生成有效工程"}
            ToolkitReverseFiles.publishTree(output,projectTarget!!)
            return ToolkitIo.json("path" to projectTarget.path,"backend" to "apktool","decoded" to true,"diagnostics" to result.text.takeLast(12000),"rebuildGuaranteed" to false)
        }
        require(output!!.isFile){"回编译没有生成APK"};validateArchive(output)
        ToolkitArchiveAlignment.align(output)
        var verification:JsonObject?=null
        if(args.tBool("sign")){val signed=File(temp,"signed.apk");val signer=AutoSigningIdentity.signer(temp,"MCP Apktool回编译")
            require(signer is KeyStoreApkSigner){"当前签名身份不支持归档签名"};signer.signAlignedArchive(output,signed);output=signed
            verification=ToolkitApkVerifier.inspect(output,null,null)
            require(verification.getAsJsonObject("signature").tBool("verified")){"回编译APK验签失败"}
        }
        val destination=ToolkitIo.resolve(root,args.tString("outPath"))
        ToolkitIo.withFileLocks(listOf(destination)){require(!destination.exists()){"outPath 已存在，请使用新文件名"};ToolkitIo.atomic(destination){output.copyTo(it,true)}}
        return ToolkitIo.json("path" to destination.path,"sha256" to ToolkitIo.sha(destination),"size" to destination.length(),"signed" to args.tBool("sign"),"verification" to verification,"backend" to "apktool","diagnostics" to result.text.takeLast(12000))
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
