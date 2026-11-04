package com.jdkshen.aijspro.mcp

import com.google.gson.*
import java.io.File

/** A reusable utility class for the UI and MCP; implementation owns local files only. */
internal class McpToolkit(private val root: File, store: File, schemas: JsonObject,
    signer: ((File,File) -> Unit)? = null, assembler: ((Int,Long,String,String) -> ByteArray)? = null, private val runtime: ToolkitRuntime? = null,
    private val guide:ToolkitReverseGuide?=null) {
    private val fileLock=Any(); private val zipLock=Any()
    private val files = McpFileToolkit(root)
    private val zip = McpZipToolkit(root)
    private val apk = McpApkToolkit(root, store, schemas, signer, assembler, runtime)
    private val jobs = ToolkitJobs(File(store,"jobs"), ::executeOperation)
    fun definitions() = JsonArray().apply {
        add(ToolkitIo.json("name" to "toolkit_status", "description" to "查看本机文件、ZIP、APK、DEX、资源与ELF工具及真实能力限制；全部独立于 MT。操作目录沿用 MCP 设置。",
            "category" to "toolkit", "categoryTitle" to "文件与APK工具", "inputSchema" to ToolkitIo.json("type" to "object", "additionalProperties" to false, "properties" to JsonObject())))
        val definitions = files.definitions().toList() + zip.definitions().toList() + apk.definitions().toList() + ToolkitReverseTools.definitions().toList()
        McpToolkitCatalog.names.drop(1).forEach { name -> add(definitions.single { it.asJsonObject.tString("name") == name }) }
    }
    private fun executeOperation(name:String,args:JsonObject):JsonObject=when(name){
        "apk_component_install"->ToolkitIo.ok(requireNotNull(runtime){"组件后端不可用"}.install(args))
        "android_resources_compile"->ToolkitIo.ok(requireNotNull(runtime){"资源编译后端不可用"}.compileResources(args))
        in ToolkitReverseTools.operations->ToolkitIo.ok(requireNotNull(runtime){"逆向组件后端不可用"}.reverse(name,args))
        else->apk.call(name,args)
    }
    fun call(name: String, args: JsonObject): JsonObject = when {
        name=="reverse_guide"->ToolkitIo.ok(requireNotNull(guide){"当前没有指南资源"}.read(args))
        name=="reverse_plan"->ToolkitIo.ok(requireNotNull(guide){"当前没有工作流资源"}.plan(args))
        name=="reverse_capabilities"->{ToolkitReverseTools.validate(name,args);ToolkitIo.ok(ToolkitIo.json("schemaVersion" to 1,"root" to root.canonicalPath,
            "runtime" to (runtime?.capabilities()?:ToolkitIo.json("available" to false)),"tools" to ToolkitReverseTools.names,"dependencies" to ToolkitReverseTools.dependencies,
            "discovery" to ToolkitIo.json("guide" to "reverse_guide","plan" to "reverse_plan","resources" to listOf("aijs://reverse/guide","aijs://reverse/workflows")),
            "fullApkid" to false,"fullGhidraDesktop" to false,"rootlessGadgetInjection" to true,"rootServerManaged" to false,"automaticPrivilegeEscalation" to false,"verifiedThisRequest" to false,
            "limits" to ToolkitIo.json("nativeInputBytes" to ToolkitIo.MAX_ENTRY,"apkInputBytes" to ToolkitIo.MAX_APK,"processLogBytes" to 4194304,"timeoutSecondsMax" to 300,"concurrentWorkers" to 2,"maxQueuedAndRunning" to 4),
            "nextActions" to listOf("检查runtime.components[].available；缺组件显式apk_component_install","后台任务返回jobId，轮询apk_job_status到终态，再apk_job_result按nextOffset读完","免Root动态分析先apk_inject_gadget生成APK，安装打开后用返回endpoint/token连接；不需要Root/Server")))}
        name in ToolkitReverseTools.operations->{ToolkitReverseTools.validate(name,args)
            if(args.tBool("background",true))ToolkitIo.ok(jobs.start(ToolkitIo.json("operation" to name,"arguments" to args,"timeoutSeconds" to args.tInt("timeoutSeconds",120,1,300))))else executeOperation(name,args)}
        // 同步类逆向工具（Frida 观测会话、native 分析会话）：无子进程、无后台 job，必须走 runtime，
        // 否则会落到 APK 工具的 workspaceId 分支而报「缺少 workspaceId」。
        name in ToolkitReverseTools.names->{ToolkitReverseTools.validate(name,args)
            ToolkitIo.ok(requireNotNull(runtime){"逆向组件后端不可用"}.reverse(name,args))}
        name=="apk_job_start"->ToolkitIo.ok(jobs.start(args))
        name=="apk_job_list"->ToolkitIo.ok(jobs.list(args))
        name=="apk_job_status"->ToolkitIo.ok(jobs.status(args))
        name=="apk_job_result"->ToolkitIo.ok(jobs.result(args))
        name=="apk_job_cancel"->ToolkitIo.ok(jobs.cancel(args))
        name=="apk_component_status"->ToolkitIo.ok(runtime?.capabilities()?:ToolkitIo.json("available" to false,"reason" to "当前无Android组件后端"))
        name=="apk_component_remove"->ToolkitIo.ok(requireNotNull(runtime){"组件后端不可用"}.remove(args))
        name in setOf("apk_component_install","android_resources_compile","apk_decompile_class","apk_decompile_method")->
            if(args.tBool("background",true))ToolkitIo.ok(jobs.start(ToolkitIo.json("operation" to name,"arguments" to args,"timeoutSeconds" to args.tInt("timeoutSeconds",300,1,1800)))) else executeOperation(name,args)

        name == "toolkit_status" -> ToolkitIo.ok(ToolkitIo.json("implementation" to "aijs-native", "requiresMt" to false,
            "homePath" to root.canonicalPath, "optionalRuntime" to (runtime?.capabilities()?:ToolkitIo.json("available" to false)),
            "features" to listOf(ToolkitIo.json("name" to "dexNavigation","status" to "available","evidence" to "implementation"),
                ToolkitIo.json("name" to "signatureVerification","status" to "available","backend" to "apksig","verifiedThisRequest" to false,
                    "schemeStatusModel" to listOf("valid","invalid","backend_error","input_error","not_run"),
                    "backendHealthProbe" to "signature.backendDiagnosis.asn1AnnotationsRetained"),
                ToolkitIo.json("name" to "artifactAnalysis","status" to "available","formats" to listOf("apk","dex","elf","apks","xapk","apkm")),
                ToolkitIo.json("name" to "longJobsAndRollback","status" to "available","evidence" to "implementation")), "toolCount" to McpToolkitCatalog.names.size,
            "groups" to listOf("file", "zip", "apk", "dex", "resources", "elf"),
            "capabilities" to ToolkitIo.json("signatures" to listOf("V1", "V2", "V3", "V3.1"),
                "zip" to ToolkitIo.json("readEncryption" to listOf("ZipCrypto", "AES"), "writeEncryption" to "AES-256", "authentication" to true),
                "regex" to ToolkitIo.json("engine" to "java.util.regex", "lookaround" to true, "backreferences" to true, "budgeted" to true),
                "resources" to ToolkitIo.json("defaultVariant" to "default", "perItemErrors" to true, "maxValueXmlChars" to 1048576, "resolveDepth" to 1),
                "native" to ToolkitIo.json("patchArchitectures" to listOf("aarch64", "arm", "thumb", "x86", "x86_64"),
                    "demangleAvailable" to ToolkitNative.demangleAvailable, "generalAssemblerOptional" to true,
                    "installCommand" to "aijs-toolchain install cpp", "availabilityProbe" to "get_build_tools")),
            "limits" to listOf("APK 快照最大512MiB、总量1GiB；单条目分析64MiB，文本10MiB", "只操作配置的工具目录；写入需版本或原字节校验",
                "ELF 元数据支持32/64位；指令分析在原生库可用时覆盖 AArch64、ARM/Thumb、x86/x86-64（Capstone 5.0.6），不可用时回退有界 AArch64 子集；未知指令明确标出", "Java反编译由显式安装的JADX组件提供，产物用于分析，不保证原样重编；未安装时DEX仍可读Smali",
                "正则兼容Java语法（含环视/反向引用），受时间/工作量/取消限制；资源支持逐项读取及一级直接引用摘要；ELF不提供完整间接调用/数据流证明",
                "通用多架构汇编可使用终端可选 C/C++ 组件；缺后端会明确提示，不依赖MT")))
        name in McpFileToolkit.names -> synchronized(fileLock){files.call(name, args)}
        name in McpZipToolkit.names -> synchronized(zipLock){zip.call(name, args)}
        name in McpToolkitCatalog.names -> apk.call(name, args)
        else -> throw IllegalArgumentException("未知工具：$name")
    }
}
