package com.jdkshen.aijspro.mcp

import android.content.Context
import android.os.Build
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.google.gson.*
import com.stardust.autojs.runtime.api.ShizukuShell
import com.stardust.autojs.runtime.tool.ToolchainRuntime
import com.stardust.autojs.runtime.tool.ToolchainStore
import java.io.*
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * C：按操作根共享一个 ToolkitOutputOrganizer；默认项目状态持久化在工具 store 的 project.json，
 * 所以 apk_ 系、zip_ 系与 job 导出必须拿到同一个实例，不能各自复制归置规则。
 */
internal object ToolkitOutputOrganizers {
    private val cache = ConcurrentHashMap<String, ToolkitOutputOrganizer>()
    @Volatile private var storeBase: File? = null

    /** McpToolkitProvider 的 store 基目录（apk-toolkit）；由 AndroidToolkitRuntime 在构造时注册。 */
    fun registerStore(base: File) { storeBase = base.canonicalFile; cache.clear() }

    fun storeFor(root: File): File {
        val hash = ToolkitIo.version(root.canonicalPath.toByteArray()).take(16)
        val base = storeBase ?: File(root.canonicalFile.parentFile ?: root.canonicalFile, ".mcp-output-store")
        return File(base, hash)
    }

    fun forRoot(root: File): ToolkitOutputOrganizer = forStore(storeFor(root), root)

    fun forStore(store: File, root: File): ToolkitOutputOrganizer {
        val key = store.canonicalPath + "|" + root.canonicalPath
        return cache.getOrPut(key) { ToolkitOutputOrganizer(store, root) }
    }

    fun resetForTest() { storeBase = null; cache.clear() }
}

/** 把 organizer 的 organized 摘要原样加进工具结果；未改写时不加字段（不改变既有返回形状）。 */
internal fun JsonObject.withOrganized(organized: ToolkitOutputOrganizer.OrganizedPath?): JsonObject = apply {
    organized?.info?.let { add("organized", it) }
}

/**
 * B: jadx 子进程命令。-Xmx 必须放在 /system/bin（app_process 可执行路径）之前，
 * 否则会被当成 app_process 之后的程序参数而不是 VM 选项。抽成 internal 便于 JVM 单测断言顺序。
 */
internal fun jadxCommand(
    temp:File,
    payload:File,
    applicationApk:String,
    request:File,
):MutableList<String> = mutableListOf(
    "/system/bin/app_process",
    "-Djava.io.tmpdir=${temp.path}",
    "-Xmx${McpLimits.CHILD_HEAP_MB}m",
    "/system/bin",
    "--nice-name=aijs-jadx",
    ToolkitComponentMain::class.java.name,
    request.path,
)

/** Components run in owned child processes, never load decompiler classes into the host. */
/** B5：区分 JADX 真正跑过但找不到类/方法，与参数层 DEX locator 不存在。 */
internal fun jadxFailureCode(text: String): String = when {
    text.contains("JADX未找到类") -> "JADX_CLASS_NOT_FOUND"
    text.contains("JADX未找到精确方法") -> "JADX_METHOD_NOT_FOUND"
    text.contains("JADX未找到") -> "JADX_CLASS_NOT_FOUND"
    else -> "JADX_DECOMPILE_FAILED"
}

internal class AndroidToolkitRuntime(private val context:Context, private val root:File):ToolkitRuntime {
    private val home=File(context.noBackupFilesDir,"apk-components")
    private val reverse by lazy { AndroidReverseRuntime(context,root,::component) }

    init {
        // 同一个操作根的所有工具共享 organizer；store 与 McpToolkitProvider 的 apk-toolkit 目录一致。
        ToolkitOutputOrganizers.registerStore(File(context.filesDir, "apk-toolkit"))
        // file_access_diagnose（P0-B）需要真实 UID/errno 与 Shizuku 授权身份；本类持有 Context，
        // 由它把探测实现安装到进程级注册表，McpFileToolkit 只负责参数与工具外壳。
        ToolkitPathDiagnostics.installProvider { rootDir, requested, backend, compareWithShizuku ->
            diagnosePaths(rootDir, requested, backend, compareWithShizuku)
        }
    }

    /** 组装 app/shizuku 两个后端的探测结果；权限身份只在显式 backend=shizuku 时使用。 */
    private fun diagnosePaths(
        rootDir: File,
        requested: String,
        backend: String,
        compareWithShizuku: Boolean,
    ): JsonObject {
        val info = ToolkitPathDiagnostics.resolveRequested(rootDir, requested)
        var availabilityKnown = false
        var shizukuAvailable = false
        try {
            availabilityKnown = true
            shizukuAvailable = ShizukuShell.isAvailable() && ShizukuShell.hasPermission()
        } catch (_: Throwable) {
            availabilityKnown = false
            shizukuAvailable = false
        }
        val appProbe = if (backend == ToolkitPathDiagnostics.BACKEND_APP || compareWithShizuku) {
            probePathAsApp(info.path)
        } else {
            null
        }
        val needShizuku = backend == ToolkitPathDiagnostics.BACKEND_SHIZUKU ||
            (backend == ToolkitPathDiagnostics.BACKEND_APP && compareWithShizuku && shizukuAvailable)
        val shizukuProbe = if (needShizuku) probePathAsShizuku(info.path, shizukuAvailable) else null
        return ToolkitPathDiagnostics.report(
            root = rootDir,
            requested = requested,
            requestedBackend = backend,
            compareWithShizuku = compareWithShizuku,
            appProbe = appProbe,
            shizukuProbe = shizukuProbe,
            shizukuAvailable = shizukuAvailable,
            shizukuAvailabilityKnown = availabilityKnown,
            sdkInt = Build.VERSION.SDK_INT,
            appPackage = context.packageName,
        )
    }

    /** app 后端：普通应用 UID，用 android.system.Os.lstat 拿到原始 errno。 */
    private fun probePathAsApp(path: File): ToolkitPathDiagnostics.Probe {
        val uid = Process.myUid().toLong()
        return try {
            val stat = Os.lstat(path.path)
            val mode = stat.st_mode
            val fileType = mode and OsConstants.S_IFMT
            val type = when (fileType) {
                OsConstants.S_IFDIR -> "directory"
                OsConstants.S_IFREG -> "file"
                OsConstants.S_IFLNK -> "symbolic_link"
                else -> "other"
            }
            val readable: Boolean? = try {
                Os.access(path.path, OsConstants.R_OK)
            } catch (_: Throwable) {
                null
            }
            val writable: Boolean? = try {
                Os.access(path.path, OsConstants.W_OK)
            } catch (_: Throwable) {
                null
            }
            ToolkitPathDiagnostics.Probe(
                backend = ToolkitPathDiagnostics.BACKEND_APP,
                executed = true,
                available = true,
                uid = uid,
                exists = true,
                type = type,
                size = if (type == "file") stat.st_size else 0L,
                modifiedTime = stat.st_mtime * 1000L,
                readable = readable,
                writable = writable,
            )
        } catch (error: ErrnoException) {
            ToolkitPathDiagnostics.Probe(
                backend = ToolkitPathDiagnostics.BACKEND_APP,
                executed = true,
                available = true,
                uid = uid,
                errno = error.errno,
                errnoName = ToolkitPathDiagnostics.errnoName(error.errno),
                message = error.message,
                exists = false,
            )
        } catch (error: Throwable) {
            ToolkitPathDiagnostics.Probe(
                backend = ToolkitPathDiagnostics.BACKEND_APP,
                executed = false,
                available = true,
                uid = uid,
                message = error.javaClass.simpleName + ": " + (error.message ?: ""),
                notes = listOf("app 后端探测异常，未得出访问结论"),
            )
        }
    }

    /** shizuku 后端：只有显式请求时才使用授权身份，启动失败不静默回退 app。 */
    private fun probePathAsShizuku(path: File, available: Boolean): ToolkitPathDiagnostics.Probe {
        if (!available) {
            return ToolkitPathDiagnostics.Probe(
                backend = ToolkitPathDiagnostics.BACKEND_SHIZUKU,
                executed = false,
                available = false,
                notes = listOf("Shizuku 未运行或未授权，本轮没有执行检查；不会回退 app UID"),
            )
        }
        return try {
            val script = ToolkitPathDiagnostics.shizukuProbeScript(path.path)
            // ShizukuShell.execute：内部 /system/bin/sh -c，带 3s 超时与 16KiB 输出上限，
            // 超时会销毁远端进程；比手工读流更适合 MCP 同步调用。
            val result = ShizukuShell.execute(script, 3000, 16384)
            val parsed = ToolkitPathDiagnostics.parseShizukuProbe(result.output)
            if (parsed.executed) {
                parsed
            } else {
                parsed.copy(
                    message = "code=" + result.code +
                        "; stderr=" + result.error.take(400) +
                        "; output=" + result.output.take(200),
                )
            }
        } catch (error: Throwable) {
            ToolkitPathDiagnostics.Probe(
                backend = ToolkitPathDiagnostics.BACKEND_SHIZUKU,
                executed = false,
                available = true,
                message = error.javaClass.simpleName + ": " + (error.message ?: ""),
                notes = listOf("Shizuku 探测启动/读取失败，未得出访问结论"),
            )
        }
    }

    companion object {
        val COMPONENTS=linkedMapOf("jadx" to "jadx.dex.jar","apk-resources" to "bin/aapt2","apktool" to "apktool.dex.jar",
            "radare2" to "bin/radare2","r2ghidra" to "lib/core_ghidra.so","yara" to "bin/aijs-yara","unicorn" to "bin/aijs-unicorn","frida" to "bin/aijs-frida","frida-gadget" to "gadget/arm64-v8a/libfrida-gadget.so")
        fun terminalStatus(context:Context):JsonObject=AndroidToolkitRuntime(context,context.filesDir).capabilities()
        fun wrapTerminal(context:Context,command:String):String {
            if(!ToolchainRuntime.canExecuteDownloaded(context))return command
            val runtime=AndroidToolkitRuntime(context,context.filesDir)
            val component=runCatching{runtime.store("apk-resources").activeDirectory()}.getOrNull()?:return command
            val binary=File(component,"bin/aapt2");if(!binary.isFile)return command
            val quote:(String)->String={McpAndroidTerminal.quote(it)}
            return "aapt2() { LD_LIBRARY_PATH="+quote(File(component,"lib").path)+" "+quote(binary.path)+" \"\$@\"; };\n"+command
        }
    }

    private fun store(id:String):ToolchainStore { require(id in COMPONENTS){"未知APK组件"};return ToolchainStore(File(home,id),id) }
    override fun capabilities():JsonObject=ToolkitIo.json("components" to COMPONENTS.keys.map{id->
        val status=runCatching{JsonParser().parse(store(id).status().toString()).asJsonObject}.getOrElse{ToolkitIo.json("error" to it.message,"installed" to false)}
        val active=runCatching{store(id).activeDirectory()}.getOrNull()
        val javaComponent=id in setOf("jadx","apktool");val dataComponent=id=="frida-gadget";val executable=File(active?:home,COMPONENTS.getValue(id))
        val protocolReady=id!="frida"||active!=null&&File(active,"gadget-token-support.txt").isFile
        status.addProperty("needsUpdate",active!=null&&!protocolReady)
        if(id=="frida")status.addProperty("tokenAuthenticationSupported",protocolReady)
        status.addProperty("id",id);status.addProperty("available",Build.VERSION.SDK_INT>=26&&active!=null&&executable.isFile&&protocolReady&&(javaComponent||dataComponent||ToolchainRuntime.canExecuteDownloaded(context)))
        status.addProperty("verified",false);status.addProperty("verifiedThisRequest",false);status.addProperty("backend",if(dataComponent)"apk-payload-only" else if(javaComponent)"isolated-app-process" else "isolated-native-process")
        status.addProperty("requiresExecutableDownloadPolicy",!javaComponent&&!dataComponent)
        status.add("externalRequirements",ToolkitIo.json("runningGadgetApp" to (id=="frida"),"requiresRoot" to false,"matchingRadareAbi" to (id=="r2ghidra")))
        status.addProperty("manifestUrl",defaultManifest(id));status.addProperty("installTool","apk_component_install");status
    })
    private fun defaultManifest(id:String)="http://192.168.10.7:8080/components/$id-arm64-v1.json"
    override fun install(args:JsonObject):JsonObject{
        require(Build.VERSION.SDK_INT>=26){"可选APK组件需要Android8或更高"}
        val id=args.tString("component");val url=URL(args.tString("manifestUrl",defaultManifest(id)))
        val progress=ByteArrayOutputStream();val abi=Build.SUPPORTED_ABIS.firstOrNull()?:"unknown"
        val result=store(id).install(url,abi,Build.VERSION.SDK_INT,PrintStream(progress,true,"UTF-8"))
        if(id in setOf("jadx","apktool"))store(id).activeDirectory()?.let{File(it,COMPONENTS.getValue(id)).setWritable(false,false)}
        return JsonParser().parse(result.toString()).asJsonObject.apply{addProperty("log",progress.toString("UTF-8").replace("cpp",id).takeLast(8000))}
    }
    override fun remove(args:JsonObject):JsonObject=JsonParser().parse(store(args.tString("component")).remove().toString()).asJsonObject
    override fun reverse(name:String,args:JsonObject)=reverse.call(name,args)
    private fun component(id:String):File=store(id).activeDirectory()?:throw ToolkitException("COMPONENT_NOT_INSTALLED","请先调用apk_component_install安装${id}组件","component")
    override fun compileResources(args:JsonObject):JsonObject{
        require(Build.VERSION.SDK_INT>=26){"资源编译组件需要Android8或更高"}
        require(ToolchainRuntime.canExecuteDownloaded(context)){"当前Android执行策略不允许下载的编译器"}
        val project=ToolkitIo.resolve(root,args.tString("projectDir"));require(project.isDirectory)
        val config=File(project,"project.json").takeIf{it.isFile}?.let{JsonParser().parse(it.readText(Charsets.UTF_8)).asJsonObject}
        val resources=config?.getAsJsonObject("androidResources")
        val res=ToolkitIo.resolve(project,args.tString("resDir",resources?.tString("resDir","res")?:"res"));require(res.isDirectory){"res目录不存在"}
        val manifest=ToolkitIo.resolve(project,args.tString("manifest",resources?.tString("manifest","AndroidManifest.xml")?:"AndroidManifest.xml"));require(manifest.isFile){"Manifest不存在"}
        val host=if(args.tString("hostApk","").isEmpty())File(context.applicationInfo.sourceDir)else ToolkitIo.resolve(root,args.tString("hostApk"))
        require(host.isFile);val component=component("apk-resources");val binary=File(component,"bin/aapt2");val framework=File(component,"android.jar")
        require(binary.isFile&&framework.isFile){"组件缺少aapt2或android.jar，请重装"}
        val inputVersion=fingerprint(res,manifest)
        val out=ToolkitIo.resolve(project,args.tString("outDir","build"));require(out.isDirectory||out.mkdirs())
        val temp=File(context.cacheDir,"apk-resource-${ToolkitIo.fresh()}").apply{require(mkdir())}
        try{
            val compiled=File(temp,"compiled.zip");val result=File(temp,"project-res.apk")
            val env=mapOf("LD_LIBRARY_PATH" to File(component,"lib").path)
            val first=run(listOf(binary.path,"compile","--dir",res.path,"-o",compiled.path),temp,env,120)
            val hostPackage=context.packageName // resource-only feature is linked against the exact host snapshot.
            // Keep the host package namespace so unqualified Material style/attr references
            // resolve against its imported table, while the feature uses a distinct package id.
            val packageName=args.tString("resourcePackage",hostPackage)
            require(packageName.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+"))){"资源包名无效"}
            val manifestText=manifest.readText(Charsets.UTF_8);ToolkitResources.safeXml(manifestText)
            val document=javax.xml.parsers.DocumentBuilderFactory.newInstance().apply{isNamespaceAware=true}.newDocumentBuilder()
                .parse(java.io.ByteArrayInputStream(manifestText.toByteArray(Charsets.UTF_8)))
            require(document.documentElement.tagName=="manifest"){"Manifest根元素无效"};document.documentElement.setAttribute("package",packageName)
            val linkedManifest=File(temp,"AndroidManifest.xml")
            javax.xml.transform.TransformerFactory.newInstance().newTransformer().transform(javax.xml.transform.dom.DOMSource(document),javax.xml.transform.stream.StreamResult(linkedManifest))
            val linked=run(listOf(binary.path,"link","-o",result.path,"--manifest",linkedManifest.path,"-I",framework.path,"-I",host.path,
                "--package-id","0x7e","--allow-reserved-package-id","--min-sdk-version","21","--target-sdk-version","28",compiled.path),temp,env,120)
            // AAPT2 can append .feature for a non-0x7f package id. Record the actual table,
            // rather than assuming its namespace still equals the manifest input.
            val resourcePackageName=ZipFile(result).use{archive->
                val table=archive.getEntry("resources.arsc")?:error("编译结果缺少资源表")
                ToolkitResources(ToolkitIo.read(archive.getInputStream(table))).table.getPackages().asSequence().single{it.id==0x7e}.name
            }
            val sha=ZipFile(host).use{zip->val e=zip.getEntry("resources.arsc")?:error("宿主缺少资源表");ToolkitIo.version(ToolkitIo.read(zip.getInputStream(e)))}
            val sidecar=ToolkitIo.json("packageName" to context.packageName,"resourcePackageName" to resourcePackageName,"appApk" to host.path,
                "appApkSize" to host.length(),"appResourcesSha256" to sha,"generatedAt" to System.currentTimeMillis().toString(),"compiler" to "android-aapt2")
            // The pair is committed with backups; on failure neither old file is lost.
            require(inputVersion==fingerprint(res,manifest)){"编译期间资源源码已变化，请重试"}
            synchronized(ToolkitResourcePair){ToolkitResourcePair.commit(out,result,sidecar.toString())}
            return ToolkitIo.json("path" to File(out,"project-res.apk").path,"sidecar" to File(out,"project-res.json").path,"sha256" to ToolkitIo.sha(File(out,"project-res.apk")),
                "appResourcesSha256" to sha,"resourcePackageName" to resourcePackageName,"compiled" to true,"diagnostics" to (first+linked).takeLast(12000))
        }finally{temp.deleteRecursively()}
    }
    override fun decompile(input:File,locator:String,args:JsonObject):JsonObject{
        require(Build.VERSION.SDK_INT>=26){"Java阅读组件需要Android8或更高"}
        val directory=component("jadx");val payload=File(directory,"jadx.dex.jar");require(payload.isFile){"JADX组件不完整"}
        val temp=File(context.cacheDir,"jadx-${ToolkitIo.fresh()}").apply{require(mkdir())}
        try{
            // Java reading needs DEX only. Avoid unrelated APK resource XML and platform parser
            // differences, while keeping all bounded DEX inputs for cross-DEX type resolution.
            val codeOnly=File(temp,"code-only.apk")
            val requestedDex=args.tString("dexEntry", "")
            ZipFile(input).use{archive->java.util.zip.ZipOutputStream(codeOnly.outputStream()).use{output->
                val entries=archive.entries().asSequence().filter{!it.isDirectory&&it.name.endsWith(".dex",true)&&(requestedDex.isEmpty()||it.name==requestedDex)}.take(33).toList()
                require(entries.isNotEmpty()&&entries.size<=32){"Java阅读支持1..32个DEX输入"}
                var total=0L
                for(entry in entries){
                    ToolkitIo.ensureActive();output.putNextEntry(java.util.zip.ZipEntry(ToolkitIo.entry(entry.name)))
                    archive.getInputStream(entry).use{source->val buffer=ByteArray(65536);var entrySize=0L
                        while(true){ToolkitIo.ensureActive();val n=source.read(buffer);if(n<0)break;total+=n;entrySize+=n
                            require(entrySize<=ToolkitIo.MAX_ENTRY&&total<=128L*1024*1024){"Java阅读DEX输入超过预算"};output.write(buffer,0,n)}}
                    output.closeEntry()
                }
            }}
            val request=File(temp,"request.json");val output=File(temp,"result.json")
            request.writeText(ToolkitIo.json("input" to codeOnly.path,"locator" to locator,"offset" to args.tInt("offset",0,0,10000000),
                "maxChars" to args.tInt("maxChars",16384,1,200000),"output" to output.path).toString(),Charsets.UTF_8)
            val classpath=payload.path+File.pathSeparator+context.applicationInfo.sourceDir
            val command=jadxCommand(temp,payload,context.applicationInfo.sourceDir,request)
            val result=ToolkitProcess.run(context,command,temp,mapOf("CLASSPATH" to classpath),args.tInt("timeoutSeconds",120,1,McpLimits.MAX_TIMEOUT_SECONDS))
            if (result.exitCode != 0) {
                val code = jadxFailureCode(result.text)
                val smaliHint = if (code == "JADX_CLASS_NOT_FOUND" || code == "JADX_METHOD_NOT_FOUND")
                    "；可改用 apk_read_text(locator=\"" + locator + "\") 读取 smali 视图" else ""
                throw ToolkitException(code,
                    "JADX 反编译失败（exit=" + result.exitCode + "，子进程堆 -Xmx" + McpLimits.CHILD_HEAP_MB + "m）" +
                        smaliHint + "。原始输出：" + result.text.takeLast(12000),
                    "locator")
            }
            val log=result.text
            if (!output.isFile || output.length() > 4L * 1024 * 1024) {
                throw ToolkitException("JADX_INVALID_RESULT", "反编译器没有返回有效结果：" + log.takeLast(2000), "locator")
            }
            return JsonParser().parse(output.readText(Charsets.UTF_8)).asJsonObject.apply{addProperty("backend","jadx-component");addProperty("diagnostics",log.takeLast(4000))}
        }finally{temp.deleteRecursively()}
    }
    private fun fingerprint(res:File,manifest:File):String {
        val digest=java.security.MessageDigest.getInstance("SHA-256");var count=0;var bytes=0L
        var visited=0
        val inputs=res.walkTopDown().onEnter{dir->require(++visited<=20000&&dir.canonicalFile==dir.absoluteFile.normalize()){"资源目录过多或含符号链接"};true}
            .filter{it.isFile}.take(10001).toList()
        for(file in (inputs+manifest).sortedBy{it.path}) {
            require(++count<=10000 && file.canonicalFile==file.absoluteFile.normalize()){ "资源文件数量过多或含符号链接" }
            digest.update(file.path.toByteArray(Charsets.UTF_8));file.inputStream().use{input->val buffer=ByteArray(65536);while(true){ToolkitIo.ensureActive();val n=input.read(buffer);if(n<0)break;bytes+=n;require(bytes<=128L*1024*1024){"资源输入超过128MiB"};digest.update(buffer,0,n)}}
        }
        return ToolkitIo.hex(digest.digest()).lowercase()
    }
    private fun run(command:List<String>,directory:File,env:Map<String,String>,timeout:Int):String{
        val result=ToolkitProcess.run(context,command,directory,env,timeout)
        require(result.exitCode==0){"组件执行失败：${result.text.takeLast(12000)}"};return result.text
    }
}

internal object ToolkitResourcePair {
    fun commit(out:File,compiled:File,sidecar:String)=ToolkitIo.withFileLocks(listOf(File(out,"project-res.apk"),File(out,"project-res.json"))){commitUnlocked(out,compiled,sidecar)}
    private fun commitUnlocked(out:File,compiled:File,sidecar:String){
        require(compiled.isFile);val apk=File(out,"project-res.apk");val json=File(out,"project-res.json")
        val token=ToolkitIo.fresh();val stagedApk=File(out,".$token.apk");val stagedJson=File(out,".$token.json")
        val oldApk=File(out,".$token.old-apk");val oldJson=File(out,".$token.old-json")
        var movedApk=false;var movedJson=false;var installedApk=false;var installedJson=false
        try{
            compiled.inputStream().use{input->stagedApk.outputStream().use{input.copyTo(it,65536)}};stagedJson.writeText(sidecar,Charsets.UTF_8);ToolkitIo.ensureActive()
            if(apk.exists()){require(apk.renameTo(oldApk));movedApk=true};if(json.exists()){require(json.renameTo(oldJson));movedJson=true}
            require(stagedApk.renameTo(apk));installedApk=true;require(stagedJson.renameTo(json));installedJson=true
        }catch(e:Exception){if(installedApk)apk.delete();if(installedJson)json.delete();if(movedApk)oldApk.renameTo(apk);if(movedJson)oldJson.renameTo(json);throw e}
        finally{stagedApk.delete();stagedJson.delete();if(installedApk&&installedJson){oldApk.delete();oldJson.delete()}}
    }
}
