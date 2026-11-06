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
        "native_function_detail" to "radare2 返回 path 中 address 函数的 JSON 反汇编与基本块信息；raw pdfj 默认不应用重定位，adrp/add 目标地址不可见、0 命中不能当作没有目标；可选 relocsApply=true 会在 aaa 前设置 e bin.relocs.apply=true（注意：只传参数而不注入命令会导致标注与实际不符）；更强重定位语义用 native_decompile(pdg)。需 radare2，后台 jobId。",
        "native_decompile" to "r2ghidra 返回指定 address 的伪代码；需 radare2 和 r2ghidra 组件。伪代码用于分析，不保证可重编译；缺插件明确报错，后台 jobId。",
        "native_xrefs" to "radare2 查询到指定 address 的引用；raw axtj 默认不解析重定位，0 条引用不能当作没有调用者；可选 relocsApply=true 先设置 e bin.relocs.apply=true；需要 pdg 或 relocs.apply=true 补 adrp/add 引用。offset/limit 分页，需 radare2，后台 jobId。",
        "native_compare" to "比较两个二进制 path/otherPath 的有界字节变化，附 radare2 元数据。返回变化范围与截断标记；不把相同大小当成语义相同。后台 jobId。",
        "artifact_fingerprint" to "读取 path 的 SHA256、格式和 APK 库/壳/框架线索。内置启发式指纹，明确证据与置信度；不是完整 APKiD，不执行目标。路径只接受工具目录内的路径（相对路径或工具目录内的绝对路径）；/system/lib64/** 等工具目录外文件会 INVALID_ARGUMENT，不能用它取外部文件哈希。后台 jobId。",
        "apk_inject_gadget" to "免Root给完整单APK集成Frida Gadget：path→新outPath，自动添加启动Provider/DEX、对应ABI库及本机配置、对齐并重新签名验签。需frida-gadget组件；不重编资源、不覆盖原APK、不安装。默认 interactionType=listen 返回 endpoint/token，用 frida_status/processes/eval 连接；listen 跨 UID 可能被 SELinux 静默 DROP（实测停在 SYN_SENT，不是组件问题），此时改用 interactionType=script + scriptPath（启动即加载脚本，不碰网络）：script 返回 interaction=script + scriptPath，不再生成 endpoint/token。providerProcess 可选：显式指定 Provider 的 android:process；省略时自动按 MAIN+LAUNCHER 入口 activity/activity-alias 的 android:process 判定，读不到再用 application 的 android:process，仍无则默认进程并返回 providerProcessSource=manifest|explicit|default。默认on_load=resume不阻塞界面，port=27042可改。重签不能直接覆盖不同证书的原应用；不支持Split/sharedUserId。后台jobId。",
        "yara_scan" to "使用 yara 组件按 rulesPath 扫描 path；规则≤1MiB，输入≤64MiB，扫描≤30秒，禁用 include。返回规则命中/偏移及截断；不是自动恶意性结论。后台 jobId。",
        "native_emulate" to "Unicorn 隔离模拟 codeHex 原始机器码，可选 dataHex 与 registers；arm64/arm/thumb/x86/x86_64。代码≤64KiB、数据≤4096B、指令≤100000、时间≤1000ms，不提供完整 Android/ELF/JNI 环境。后台 jobId。",
        "frida_status" to "检查 Frida 客户端组件和指定 endpoint 的服务连接。endpoint 默认127.0.0.1:27042；需打开已集成Gadget的目标应用，token取apk_inject_gadget结果，不需Root或Server。注意：listen 模式跨 UID 时 loopback 可能被 SELinux 静默 DROP（连接停在 SYN_SENT/超时，不是组件没装）；传入 portListening/synSentObserved/sameUid 可获得 connectDiagnosis，返回里另有 listenModeLimits 与 gadgetScriptMode（推荐改用 script 模式，不碰网络）。本版还会自动读取 /proc/net/tcp[6]（读不到时用 tcpText/tcp6Text 传入原文）解析 LISTEN(0A)/SYN_SENT(02) 作为证据；sameUid 无法从 tcp 文件推断，仍需比较目标/客户端 UID。后台 jobId。",
        "frida_processes" to "列出指定免Root Gadget endpoint 可见的进程（最多500），需 frida 组件和已打开的Gadget应用，token取apk_inject_gadget结果。应用UID不能任意附加其他应用。后台 jobId。",
        "frida_eval" to "对 endpoint 的 pid 附加并有界执行 Frida script，durationMs≤10000，返回消息后卸载脚本/分离。需可访问Gadget，传相同endpoint/token；Java.perform需另行打包frida-java-bridge；停止任务杀掉本次客户端进程组。后台 jobId。",
        "frida_session_open" to "开启有界 Frida 观测会话（内存态，TTL/消息上限）：绑定目标 packageName/pid 与 endpoint，脚本只记录长度与哈希，endpoint/token 回显掩码。本版本不实现持久 attach（attached=false 会如实标注），真实执行仍走 frida_eval；进程退出会话即丢失，需重新 open。",
        "frida_session_read" to "按 offset/limit 分页读取会话消息（seq/时间戳/截断标记）。会话过期或不存在时明确报错，不静默返回空列表。",
        "frida_session_close" to "显式停止会话并返回真实清理计数（releasedMessages/releasedSessions）；不存在或已过期返回 state=not_found/expired，不抛异常。",
        "frida_session_list" to "列出当前进程内的观测会话（TTL、上限、重复策略）；过期会话在此真实清理。",
        "native_analysis_open" to "按输入文件 SHA-256 打开/复用 native 分析上下文（有状态、可持久化）。path 默认允许工具目录内相对路径，以及只读绝对根 /system、/vendor、/apex、/data/local/tmp、/storage/emulated/0 与工具操作根下的绝对路径；其它绝对路径会被 ANALYSIS_PATH_FORBIDDEN 拒绝，确需分析时显式传 allowOutsideOperationRoot=true（绕过白名单）。artifact_fingerprint/file_stat 只接受工具目录内路径，不能用于读取外部文件；工具目录外路径请直接用报错 actual 的全量 SHA-256 重试。源文件哈希变化会让旧地址与旧状态失效（ANALYSIS_STALE_INPUT）；不启动任何进程，r2 命令只生成不执行。",
        "native_analysis_status" to "返回分析会话的计数与限制（functions/types/noreturns/overlays/TTL/状态文件路径）。",
        "native_analysis_define_function" to "补充函数起点/长度/名称（用户指定边界优先，不把符号范围当成完整函数）。",
        "native_analysis_set_type" to "补充目标（函数/变量）参数、返回值或结构体类型。",
        "native_analysis_set_noreturn" to "标记终止函数（noreturn）；只影响分析命令生成，不改输入文件。",
        "native_analysis_overlay" to "登记仅用于分析的数据/重定位覆盖层（analysisOnly=true，明确不得流入构建产物）。",
        "native_analysis_save" to "把分析会话状态保存到磁盘（原子写）。",
        "native_analysis_close" to "关闭并释放分析会话；返回真实清理计数。",
        "native_analysis_list" to "列出分析会话（含 stale/计数/覆盖层字节与上限）。",
        "native_analysis_r2_commands" to "生成可核对的分析命令文本（af/afn/tl 等），executed=false：本工具只给命令，不执行。",
        "native_session_open" to "打开常驻 r2 分析会话（project 保存/加载）：一次 aaa 分析、多次查询，默认带上 e bin.relocs.apply=true（raw 反汇编才能看到 adrp/add 目标地址）。源文件哈希变化会让会话失效（SESSION_STALE_INPUT）；会话有 TTL 与数量上限。",
        "native_session_plan" to "查看/生成该会话下一步要执行的 r2 命令（首次 aaa;Ps <project>，后续 Po <project>;<cmd>），可先核对再执行。",
        "native_session_query" to "在已有会话上执行有界 r2 查询（复用已保存的 project，不重新 aaa）；命令长度与输出字节有界，禁止 ! shell 逃逸。",
        "native_session_close" to "关闭会话并返回真实释放（project 文件是否删除、剩余会话数）。",
        "native_session_list" to "列出 r2 分析会话（输入哈希、TTL、是否已初始化、project 路径）。",
        "native_resident_open" to "打开**常驻 r2 进程**会话（一次加载、多次查询）：与 native_session_* 的 project 复用不同，这里复用同一个进程的 stdin/stdout，单条查询从秒级降到毫秒级。默认 e bin.relocs.apply=true；进程死亡/超时如实报错。",
        "native_resident_command" to "在常驻会话里执行单条 r2 命令（framing=<cmd>;?e <marker>，返回剔除 marker）；输出/超时都有界，禁止 ! shell 逃逸。",
        "native_resident_batch" to "在常驻会话里一次执行多条 r2 命令（逐条隔离：一条失败/超时不影响其它条）；返回每条 ok/errorCode/output 与总数/总字节。",
        "native_resident_close" to "关闭常驻 r2 会话并返回真实释放（closed/remainingSessions/releasedProcess）。",
        "native_resident_list" to "列出常驻 r2 会话（TTL、是否注入执行器 runnerConfigured、命令计数、marker）。"
    )
    val names = descriptions.keys.toList()
    val guideNames = setOf("reverse_guide", "reverse_plan")
    /** 会话类工具是内存态、同步返回，不做后台 job（否则 read/close 会拿不到同一进程内的会话）。 */
    val sessionTools = setOf("frida_session_open", "frida_session_read", "frida_session_close", "frida_session_list")
    /** native 分析会话同样是同步的内存/文件态接口，不做后台 job。 */
    val sessionNativeTools = setOf("native_session_open","native_session_plan","native_session_query","native_session_close","native_session_list",
        "native_resident_open","native_resident_command","native_resident_batch","native_resident_close","native_resident_list")
    val analysisTools = setOf("native_analysis_open","native_analysis_status","native_analysis_define_function",
        "native_analysis_set_type","native_analysis_set_noreturn","native_analysis_overlay",
        "native_analysis_save","native_analysis_close","native_analysis_list","native_analysis_r2_commands")
    val operations = names.toSet() - guideNames - "reverse_capabilities" - sessionTools - analysisTools - sessionNativeTools
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
            "apk_build_project"->{path("projectDir");path("outPath");field("sign",ToolkitIo.json("type" to "boolean","default" to false));
                // 工程已在 MCP 根目录内且可写时，可跳过整树复制直接原地构建（默认 false=复制）。
                // 原地址构建会在前后各算一次工程树 SHA-256，不一致会抛 IN_PLACE_SOURCE_MODIFIED。
                field("buildInPlace",ToolkitIo.json("type" to "boolean","default" to false,
                    "description" to "true=在工程目录原地构建（省掉整树复制；会校验源树未变）；false=复制到临时目录再构建"))}
            "apk_install_framework"->{path();field("tag",str())}
            "apk_inject_gadget"->{path();path("outPath");field("port",num(1024,65535,27042))
                field("interactionType",ToolkitIo.json("type" to "string","enum" to listOf("listen","script"),"default" to "listen","description" to "listen=监听127.0.0.1端口并返回 endpoint/token；script=启动即加载脚本（不碰网络，跨UID SELinux 拦不到），返回 interaction=script + scriptPath，不生成 endpoint/token"))
                field("scriptPath",str("script 模式下目标应用可读的脚本绝对路径"))
                field("providerProcess",str("可选：显式指定注入 Provider 的 android:process。省略时自动读 MAIN+LAUNCHER 入口 activity/activity-alias 的 android:process；入口未声明则用 application 的 android:process；都没有=默认进程。返回 providerProcessSource=explicit|manifest|default"))}
            "artifact_fingerprint","native_analyze"->path()
            "native_functions"->{path();page()}
            "native_function_detail"->{path();field("address",str("十进制或0x地址，禁止命令/表达式"),true)
                field("relocsApply",ToolkitIo.json("type" to "boolean","default" to false))}
            "native_decompile"->{path();field("address",str("十进制或0x地址，禁止命令/表达式"),true)
                field("includeAnnotations",ToolkitIo.json("type" to "boolean","default" to false))
                field("maxCodeChars",num(1024,1048576,131072))
                field("forceFunction",ToolkitIo.json("type" to "boolean","default" to false))
                field("functionSize",num(1,16777216,1))}
            "native_xrefs"->{path();field("address",str("十进制或0x地址，禁止命令/表达式"),true);page()
                field("includeEvidence",ToolkitIo.json("type" to "boolean","default" to true))
                field("dynamicCovered",ToolkitIo.json("type" to "boolean","default" to false))
                field("relocsApply",ToolkitIo.json("type" to "boolean","default" to false))}
            "native_compare"->{path();path("otherPath");page()}
            "yara_scan"->{path();path("rulesPath");field("scanTimeoutSeconds",num(1,30,10));field("includeOffsets",ToolkitIo.json("type" to "boolean","default" to true))}
            "native_emulate"->{field("architecture",ToolkitIo.json("type" to "string","enum" to listOf("arm64","arm","thumb","x86","x86_64")),true);field("codeHex",str(),true);field("dataHex",str());field("registers",ToolkitIo.json("type" to "object","additionalProperties" to str("0x十六进制寄存器值")));field("timeoutMs",num(1,1000,100));field("instructionLimit",num(1,100000,10000))}
            "frida_status","frida_processes","frida_eval"->{field("endpoint",str("host:port，默认127.0.0.1:27042"));field("token",str("apk_inject_gadget返回的连接令牌"));if(name=="frida_eval"){field("pid",num(1,Int.MAX_VALUE,1),true);field("script",str("Frida JavaScript ≤64KiB"),true);field("durationMs",num(1,10000,1000))}
                field("portListening",ToolkitIo.json("type" to "boolean","default" to false));field("synSentObserved",ToolkitIo.json("type" to "boolean","default" to false));field("sameUid",ToolkitIo.json("type" to "boolean","default" to false))
                field("tcpText",str("可选：/proc/net/tcp 原文（本进程读不到时用 terminal_exec/shizuku 取回再传入）"));field("tcp6Text",str("可选：/proc/net/tcp6 原文"))}
            "frida_session_open"->{field("packageName",str("目标包名"),true);field("pid",num(1,Int.MAX_VALUE,1));field("endpoint",str("host:port，默认127.0.0.1:27042"));field("token",str());field("script",str("Frida JavaScript ≤64KiB"),true)}
            "frida_session_read"->{field("sessionId",str(),true);field("offset",num(0,1000000,0));field("limit",num(1,500,50))}
            "frida_session_close"->field("sessionId",str(),true)
            "frida_session_list"->Unit
            "native_session_open"->{path();field("sha256",str("输入文件 SHA-256；可先用 artifact_fingerprint 取得"),true);field("relocsApply",ToolkitIo.json("type" to "boolean","default" to true))}
            "native_session_plan"->{field("sessionId",str(),true);field("command",str("可选；给出则只生成该命令的计划"))}
            "native_session_query"->{field("sessionId",str(),true);field("command",str("纯 r2 命令；禁止 ! 逃逸"),true);field("timeoutSeconds",num(1,McpLimits.MAX_TIMEOUT_SECONDS,60))}
            "native_session_close"->field("sessionId",str(),true)
            "native_session_list"->Unit
            "native_resident_open"->{path();field("sha256",str("输入文件 SHA-256；可先用 artifact_fingerprint 取得"),true);field("relocsApply",ToolkitIo.json("type" to "boolean","default" to true))}
            "native_resident_command"->{field("sessionId",str(),true);field("command",str("纯 r2 命令；禁止 ! 逃逸"),true);field("timeoutMillis",num(1,McpLimits.MAX_TIMEOUT_SECONDS * 1000,30000))}
            "native_resident_batch"->{field("sessionId",str(),true);field("commands",ToolkitIo.json("type" to "array","items" to ToolkitIo.json("type" to "string")));field("timeoutMillis",num(1,McpLimits.MAX_TIMEOUT_SECONDS * 1000,30000))}
            "native_resident_close"->field("sessionId",str(),true)
            "native_resident_list"->Unit
            "native_analysis_open"->{field("path",str("源文件路径：相对路径在工具目录内解析；绝对路径默认只允许 /system、/vendor、/apex、/data/local/tmp、/storage/emulated/0 与工具操作根，canonical 后判定；其它绝对路径需 allowOutsideOperationRoot=true"),true);field("sha256",str("输入文件 SHA-256；工具目录内可先用 artifact_fingerprint/file_stat 取得，外部允许根用报错 actual 全量哈希或终端 sha256sum"),true);field("abi",str());field("sessionId",str());field("allowOutsideOperationRoot",ToolkitIo.json("type" to "boolean","default" to false,"description" to "仅当确实要分析允许根之外的文件时传 true；会绕过只读白名单（默认 false）"))}
            "native_analysis_list"->Unit
            "native_analysis_status","native_analysis_save","native_analysis_close","native_analysis_r2_commands"->field("sessionId",str(),true)
            "native_analysis_define_function"->{field("sessionId",str(),true);field("address",str(),true);field("size",num(0,16777216,0));field("name",str())}
            "native_analysis_set_type"->{field("sessionId",str(),true);field("target",str(),true);field("type",str(),true)}
            "native_analysis_set_noreturn"->{field("sessionId",str(),true);field("address",str(),true);field("noreturn",ToolkitIo.json("type" to "boolean","default" to true))}
            "native_analysis_overlay"->{field("sessionId",str(),true);field("address",str(),true);field("hexBytes",str(),true);field("note",str())}
            else->error("未知逆向工具")
        }
        if(name in operations){field("background",ToolkitIo.json("type" to "boolean","default" to true));field("timeoutSeconds",num(1,McpLimits.MAX_TIMEOUT_SECONDS,120))}
        return ToolkitIo.json("type" to "object","additionalProperties" to false,"properties" to p,"required" to required)
    }
    fun definitions()=JsonArray().apply{names.forEach{name->add(ToolkitIo.json("name" to name,"description" to descriptions.getValue(name),
        "category" to "reverse","categoryTitle" to "逆向分析","inputSchema" to schema(name),
        "annotations" to ToolkitIo.json("readOnlyHint" to (name !in setOf("apk_decode_project","apk_build_project","apk_install_framework","apk_inject_gadget","frida_eval","frida_session_open","frida_session_close","native_analysis_open","native_analysis_define_function","native_analysis_set_type","native_analysis_set_noreturn","native_analysis_overlay","native_analysis_save","native_analysis_close","native_session_open","native_session_plan","native_session_query","native_session_close","native_resident_open","native_resident_command","native_resident_batch","native_resident_close")),"openWorldHint" to name.startsWith("frida_")),
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
