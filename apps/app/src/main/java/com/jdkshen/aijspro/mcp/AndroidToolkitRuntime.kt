package com.jdkshen.aijspro.mcp

import android.content.Context
import android.os.Build
import com.google.gson.*
import com.stardust.autojs.runtime.tool.ToolchainRuntime
import com.stardust.autojs.runtime.tool.ToolchainStore
import java.io.*
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/** Components run in owned child processes, never load decompiler classes into the host. */
internal class AndroidToolkitRuntime(private val context:Context, private val root:File):ToolkitRuntime {
    private val home=File(context.noBackupFilesDir,"apk-components")
    private val reverse by lazy { AndroidReverseRuntime(context,root,::component) }
    companion object {
        val COMPONENTS=linkedMapOf("jadx" to "jadx.dex.jar","apk-resources" to "bin/aapt2","apktool" to "apktool.dex.jar",
            "radare2" to "bin/radare2","r2ghidra" to "lib/core_ghidra.so","yara" to "bin/aijs-yara","unicorn" to "bin/aijs-unicorn","frida" to "bin/aijs-frida")
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
        val javaComponent=id in setOf("jadx","apktool");val executable=File(active?:home,COMPONENTS.getValue(id))
        status.addProperty("id",id);status.addProperty("available",Build.VERSION.SDK_INT>=26&&active!=null&&executable.isFile&&(javaComponent||ToolchainRuntime.canExecuteDownloaded(context)))
        status.addProperty("verified",false);status.addProperty("verifiedThisRequest",false);status.addProperty("backend",if(javaComponent)"isolated-app-process" else "isolated-native-process")
        status.addProperty("requiresExecutableDownloadPolicy",!javaComponent)
        status.add("externalRequirements",ToolkitIo.json("fridaServerOrGadget" to (id=="frida"),"matchingRadareAbi" to (id=="r2ghidra")))
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
            val log=run(listOf("/system/bin/app_process","-Djava.io.tmpdir=${temp.path}","/system/bin","--nice-name=aijs-jadx",ToolkitComponentMain::class.java.name,request.path),temp,mapOf("CLASSPATH" to classpath),args.tInt("timeoutSeconds",120,1,300))
            require(output.isFile&&output.length()<=4L*1024*1024){"反编译器没有返回有效结果：${log.takeLast(2000)}"}
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
