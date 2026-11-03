package com.jdkshen.aijspro.mcp

import com.google.gson.*

/** Small, discoverable contracts. Optional binaries stay out of the APK and run out of process. */
internal object ToolkitReverseTools {
    val descriptions = linkedMapOf(
        "reverse_guide" to "逆向工具入门与分页指南。首次连接先 topic=index，再按 sectionIds 读取；offset 使用 nextOffset，maxChars≤24000。包括依赖安装、APK/DEX/SO/Frida/YARA/Unicorn、任务轮询及错误处理。",
        "reverse_capabilities" to "查询当前手机逆向后端、按需组件、执行策略和真实限制。installed/available 只代表文件与平台条件；verifiedThisRequest=false 不代表已运行验证。不会下载或启动目标。",
        "reverse_plan" to "按 goal 返回机读逆向工作流、工具与组件依赖、参数模板及限制；goal 见 reverse_guide index。只规划不执行，占位符必须替换，工具注册不代表组件已安装。",
        "apk_decode_project" to "Apktool 完整解包 APK 为可编辑的 Manifest/XML/Smali 工程；path→新 outDir。需 apktool 组件。后台任务默认开启，返回 jobId 后用 apk_job_status/result。不会覆盖源 APK/目录。",
        "apk_build_project" to "Apktool 将已解包 projectDir 回编译到新 outPath；需 apktool 与 apk-resources。默认不签名，sign=true 使用本应用签名身份并验签；不会保留旧签名。后台 jobId。",
        "apk_install_framework" to "把工具目录内厂商 framework APK 安装到 Apktool 私有框架缓存；需 apktool。可选 tag 仅字母数字点下划线横线。不会安装到 Android 系统。后台 jobId。",
        "native_analyze" to "radare2 读取 path 的 ELF/二进制元数据。需 radare2；分析输入≤64MiB，隔离子进程，不执行目标代码。后台 jobId。",
        "native_functions" to "radare2 分析函数并按 offset/limit 分页；path 固定，limit≤200。启发式结果不保证覆盖全部函数，后台 jobId。",
        "native_function_detail" to "radare2 返回 path 中 address 函数的 JSON 反汇编与基本块信息；address 为十进制或0x十六进制字符串。需 radare2，后台 jobId。",
        "native_decompile" to "r2ghidra 返回指定 address 的伪代码；需 radare2 和 r2ghidra 组件。伪代码用于分析，不保证可重编译；缺插件明确报错，后台 jobId。",
        "native_xrefs" to "radare2 查询到指定 address 的引用；offset/limit 分页，需 radare2。间接调用可能缺失，后台 jobId。",
        "native_compare" to "比较两个二进制 path/otherPath 的有界字节变化，附 radare2 元数据。返回变化范围与截断标记；不把相同大小当成语义相同。后台 jobId。",
        "artifact_fingerprint" to "读取 path 的 SHA256、格式和 APK 库/壳/框架线索。内置启发式指纹，明确证据与置信度；不是完整 APKiD，不执行目标。后台 jobId。",
        "apk_inject_gadget" to "免Root给完整单APK集成Frida Gadget：path→新outPath，自动添加启动Provider/DEX、对应ABI库及本机配置、对齐并重新签名验签。需frida-gadget组件；不重编资源、不覆盖原APK、不安装。返回endpoint/token，打开输出应用后用frida_status/processes/eval连接。默认on_load=resume不阻塞界面，port=27042可改。重签不能直接覆盖不同证书的原应用；不支持Split/sharedUserId。后台jobId。",
        "yara_scan" to "使用 yara 组件按 rulesPath 扫描 path；规则≤1MiB，输入≤64MiB，扫描≤30秒，禁用 include。返回规则命中/偏移及截断；不是自动恶意性结论。后台 jobId。",
        "native_emulate" to "Unicorn 隔离模拟 codeHex 原始机器码，可选 dataHex 与 registers；arm64/arm/thumb/x86/x86_64。代码≤64KiB、数据≤4096B、指令≤100000、时间≤1000ms，不提供完整 Android/ELF/JNI 环境。后台 jobId。",
        "frida_status" to "检查 Frida 客户端组件和指定 endpoint 的服务连接。endpoint 默认127.0.0.1:27042；需打开已集成Gadget的目标应用，token取apk_inject_gadget结果，不需Root或Server。后台 jobId。",
        "frida_processes" to "列出指定免Root Gadget endpoint 可见的进程（最多500），需 frida 组件和已打开的Gadget应用，token取apk_inject_gadget结果。应用UID不能任意附加其他应用。后台 jobId。",
        "frida_eval" to "对 endpoint 的 pid 附加并有界执行 Frida script，durationMs≤10000，返回消息后卸载脚本/分离。需可访问Gadget，传相同endpoint/token；Java.perform需另行打包frida-java-bridge；停止任务杀掉本次客户端进程组。后台 jobId。"
    )
    val names = descriptions.keys.toList()
    val guideNames = setOf("reverse_guide", "reverse_plan")
    val operations = names.toSet() - guideNames - "reverse_capabilities"
    val dependencies = mapOf(
        "apk_decode_project" to listOf("apktool"), "apk_build_project" to listOf("apktool", "apk-resources"),
        "apk_install_framework" to listOf("apktool"), "native_decompile" to listOf("radare2", "r2ghidra"),
        "yara_scan" to listOf("yara"), "native_emulate" to listOf("unicorn"), "apk_inject_gadget" to listOf("frida-gadget")
    ) + names.filter { it.startsWith("native_") && it !in setOf("native_decompile", "native_emulate") }.associateWith { listOf("radare2") } +
        names.filter { it.startsWith("frida_") }.associateWith { listOf("frida") }
    private fun str(description:String="")=ToolkitIo.json("type" to "string", "description" to description)
    private fun num(min:Int,max:Int,default:Int)=ToolkitIo.json("type" to "integer","minimum" to min,"maximum" to max,"default" to default)
    fun schema(name:String):JsonObject {
        val p=JsonObject(); val required=ArrayList<String>()
        fun field(key:String,type:JsonObject=str(),needed:Boolean=false){p.add(key,type);if(needed)required+=key}
        fun path(key:String="path"){field(key,str("工具目录内的手机相对路径；先 reverse_capabilities 确认 root"),true)}
        fun page(){field("offset",num(0,1000000,0));field("limit",num(1,200,50))}
        when(name){
            "reverse_guide"->{field("topic",str("index、all 或返回的章节id"));field("offset",num(0,1000000,0));field("maxChars",num(1,24000,6000))}
            "reverse_plan"->field("goal",str("reverse_guide index 返回的 workflows[].goal"),true)
            "reverse_capabilities"->Unit
            "apk_decode_project"->{path();path("outDir");field("decodeResources",ToolkitIo.json("type" to "boolean","default" to true))}
            "apk_build_project"->{path("projectDir");path("outPath");field("sign",ToolkitIo.json("type" to "boolean","default" to false))}
            "apk_install_framework"->{path();field("tag",str())}
            "apk_inject_gadget"->{path();path("outPath");field("port",num(1024,65535,27042))}
            "artifact_fingerprint","native_analyze"->path()
            "native_functions"->{path();page()}
            "native_function_detail","native_decompile","native_xrefs"->{path();field("address",str("十进制或0x地址，禁止命令/表达式"),true);if(name=="native_xrefs")page()}
            "native_compare"->{path();path("otherPath");page()}
            "yara_scan"->{path();path("rulesPath");field("scanTimeoutSeconds",num(1,30,10));field("includeOffsets",ToolkitIo.json("type" to "boolean","default" to true))}
            "native_emulate"->{field("architecture",ToolkitIo.json("type" to "string","enum" to listOf("arm64","arm","thumb","x86","x86_64")),true);field("codeHex",str(),true);field("dataHex",str());field("registers",ToolkitIo.json("type" to "object","additionalProperties" to str("0x十六进制寄存器值")));field("timeoutMs",num(1,1000,100));field("instructionLimit",num(1,100000,10000))}
            "frida_status","frida_processes","frida_eval"->{field("endpoint",str("host:port，默认127.0.0.1:27042"));field("token",str("apk_inject_gadget返回的连接令牌"));if(name=="frida_eval"){field("pid",num(1,Int.MAX_VALUE,1),true);field("script",str("Frida JavaScript ≤64KiB"),true);field("durationMs",num(1,10000,1000))}}
            else->error("未知逆向工具")
        }
        if(name in operations){field("background",ToolkitIo.json("type" to "boolean","default" to true));field("timeoutSeconds",num(1,300,120))}
        return ToolkitIo.json("type" to "object","additionalProperties" to false,"properties" to p,"required" to required)
    }
    fun definitions()=JsonArray().apply{names.forEach{name->add(ToolkitIo.json("name" to name,"description" to descriptions.getValue(name),
        "category" to "reverse","categoryTitle" to "逆向分析","inputSchema" to schema(name),
        "annotations" to ToolkitIo.json("readOnlyHint" to (name !in setOf("apk_decode_project","apk_build_project","apk_install_framework","apk_inject_gadget","frida_eval")),"openWorldHint" to name.startsWith("frida_")),
        "_meta" to ToolkitIo.json("aijs" to ToolkitIo.json("guideTool" to "reverse_guide","dependencies" to (dependencies[name]?:emptyList<String>()),"backgroundDefault" to (name in operations),
            "resultTools" to if(name in operations)listOf("apk_job_status","apk_job_result","apk_job_cancel")else emptyList<String>()))))}}
    fun validate(name:String,args:JsonObject){
        val s=schema(name);val props=s.getAsJsonObject("properties")
        require(args.keySet().all{props.has(it)}){"未知参数：${args.keySet()-props.keySet()}"}
        s.getAsJsonArray("required").forEach{require(args.has(it.asString)){"缺少 ${it.asString}"}}
        args.entrySet().forEach{(key,value)->val field=props.getAsJsonObject(key);when(field.tString("type")){
            "string"->args.tString(key)
            "boolean"->args.tBool(key)
            "integer"->args.tInt(key,0,field.get("minimum").asInt,field.get("maximum").asInt)
            "object"->require(value.isJsonObject){"$key 必须为对象"}
        };field.getAsJsonArray("enum")?.let{require(it.any{allowed->allowed==value}){"$key 不在支持范围"}}}
    }
    fun address(value:String):String {
        require(value.matches(Regex("(?:0[xX][0-9a-fA-F]{1,16}|[0-9]{1,20})"))){"address 必须是无符号十进制或0x地址"}
        val number=if(value.startsWith("0x",true))value.substring(2).toULong(16)else value.toULong()
        return "0x"+number.toString(16)
    }
}
