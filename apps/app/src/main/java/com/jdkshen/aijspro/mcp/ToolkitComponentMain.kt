package com.jdkshen.aijspro.mcp

import com.google.gson.*
import java.io.File

/** Reflection boundary: JADX stays in a separately downloaded, pre-DEXed component. */
object ToolkitComponentMain {
    @JvmStatic fun main(argv:Array<String>){
        try{require(argv.size==1);val args=JsonParser().parse(File(argv[0]).readText(Charsets.UTF_8)).asJsonObject
            val input=File(args.tString("input"));require(input.isFile);val locator=args.tString("locator");require(locator.startsWith("dex_class:")||locator.startsWith("dex_method:"))
            val descriptor=locator.substringAfter(':').substringBefore("->");val name=descriptor.removePrefix("L").removeSuffix(";").replace('/','.')
            val opts=Class.forName("jadx.api.JadxArgs").getDeclaredConstructor().newInstance()
            invoke(opts,"setInputFiles",listOf(input));invoke(opts,"setSkipResources",true);invoke(opts,"setThreadsCount",1)
            val engine=Class.forName("jadx.api.JadxDecompiler").getConstructor(opts.javaClass).newInstance(opts)
            try{
                val manager=invoke(engine,"getPluginManager")!!
                val plugin=Class.forName("jadx.plugins.input.dex.DexInputPlugin").getDeclaredConstructor().newInstance();invoke(manager,"register",plugin)
                invoke(engine,"load")
                fun find(classes:Iterable<*>):Any?{for(c in classes.filterNotNull()){
                    val raw=invoke(c,"getRawName").toString();if(raw==name)return c
                    if(name.startsWith(raw+"$")){val nested=find(invoke(c,"getInnerClasses") as Iterable<*>);if(nested!=null)return nested}
                };return null}
                val target=find(invoke(engine,"getClasses") as Iterable<*>)?:error("JADX未找到类：$descriptor")
                val classCode=invoke(target,"getCode").toString()
                val methods=(invoke(target,"getMethods") as Iterable<*>).filterNotNull()
                val anchors=methods.map{method->
                    val node=invoke(method,"getMethodNode")!!;val info=invoke(node,"getMethodInfo")!!;val short=invoke(info,"getShortId").toString()
                    val pos=(invoke(method,"getDefPos") as Number).toInt().coerceIn(0,classCode.length)
                    ToolkitIo.json("locator" to "dex_method:$descriptor->$short","classCodeOffset" to pos,"classLine" to (classCode.take(pos).count{it=='\n'}+1))
                }
                val selected=if(locator.startsWith("dex_method:"))methods.singleOrNull{method->
                    val info=invoke(invoke(method,"getMethodNode")!!,"getMethodInfo")!!;invoke(info,"getShortId").toString()==locator.substringAfter("->")
                }?:error("JADX未找到精确方法") else null
                val code=if(selected==null)classCode else invoke(selected,"getCodeStr").toString()
                require(code.length<=10000000){"反编译源码超过10M字符"};val start=args.tInt("offset",0,0,code.length);val end=minOf(code.length,start+args.tInt("maxChars",16384,1,200000))
                val errorsCount=(invoke(engine,"getErrorsCount") as? Number)?.toInt()?:0
                val warningsCount=invoke(engine,"getWarnsCount")
                val resultWarnings=resultWarnings(code,start,end,locator)
                // #17：JADX 的 getErrorsCount 是全局计数；即使目标方法自己没报错，也说明本次工程存在未反编译完整的方法。
                val incompleteReasons=ArrayList<String>()
                if(errorsCount>0)incompleteReasons.add("errorsCount=" + errorsCount)
                if(code.contains("JADX ERROR",ignoreCase=true))incompleteReasons.add("content contains JADX ERROR")
                if(code.contains("Method dump skipped",ignoreCase=true))incompleteReasons.add("content contains Method dump skipped")
                val incomplete=incompleteReasons.isNotEmpty()
                val incompleteReason=if(incomplete) "反编译结果不完整：" + incompleteReasons.joinToString("；") +
                    "。请改用 smali 视图：apk_read_text(locator=\"" + locator + "\")" else ""
                val warningCode: String
                val warningHint: String
                val nextActions: List<Any>
                if (incomplete) {
                    warningCode="DECOMPILE_INCOMPLETE"
                    warningHint=incompleteReason
                    nextActions=listOf(ToolkitIo.json("tool" to "apk_read_text", "locator" to locator,
                        "hint" to "换 smali 视图读取同一方法的字节码/注释"))
                } else {
                    warningCode=resultWarnings.tString("warningCode")
                    warningHint=resultWarnings.tString("warningHint")
                    nextActions=resultWarnings.getAsJsonArray("nextActions")?.map { it } ?: emptyList()
                }
                val response=ToolkitIo.json("locator" to locator,"language" to "java","content" to code.substring(start,end),"offset" to start,"nextOffset" to end,
                    "hasMore" to (end<code.length),"totalChars" to code.length,"anchors" to anchors,"errorsCount" to errorsCount,
                    "warningsCount" to warningsCount,"decompileIncomplete" to incomplete,"incompleteReason" to incompleteReason,
                    "truncated" to resultWarnings.tBool("truncated"),"emptyResult" to resultWarnings.tBool("emptyResult"),
                    "warningCode" to warningCode,"warningHint" to warningHint,
                    "nextActions" to nextActions,"purpose" to "analysis","rebuildGuaranteed" to false)
                File(args.tString("output")).writeText(response.toString(),Charsets.UTF_8)
            }finally{invoke(engine,"close")}
        }catch(e:Throwable){e.printStackTrace();System.exit(1)}
    }
    /**
     * B2/B3：把“成功但空/被截断”从普通 success 里标出来，不改既有 content/totalChars/decompileIncomplete 字段语义。
     * JADX 错误仍由 decompileIncomplete/incompleteReason 表达；这里只产生 warningCode 与分页建议。
     */
    internal fun resultWarnings(code: String, start: Int, end: Int, locator: String): JsonObject {
        val empty = code.isBlank()
        val truncated = end < code.length
        val warningCode = when {
            empty -> "EMPTY_RESULT"
            truncated -> "RESULT_TRUNCATED"
            else -> ""
        }
        val warningHint = when {
            empty -> "JADX 返回空内容，无法确认该类/方法已反编译；请改用 apk_read_text(locator=\"" + locator + "\") 读 smali 字节码。"
            truncated -> "结果被 maxChars 截断，只返回了前 " + end + " 个字符；请用 offset=" + end + " 继续读取，或增大 maxChars / 用 apk_job_result 分页。"
            else -> ""
        }
        val actions = when {
            empty -> listOf(ToolkitIo.json("tool" to "apk_read_text", "locator" to locator,
                "hint" to "用同一 locator 的 smali 视图确认该类/方法"))
            truncated -> listOf(ToolkitIo.json("tool" to if (locator.startsWith("dex_method:")) "apk_decompile_method" else "apk_decompile_class", "locator" to locator,
                "offset" to end, "maxChars" to 200000, "hint" to "从 nextOffset 继续反编译"))
            else -> emptyList<Any>()
        }
        return ToolkitIo.json("emptyResult" to empty, "truncated" to truncated,
            "warningCode" to warningCode, "warningHint" to warningHint, "nextActions" to actions)
    }

    private fun invoke(target:Any,name:String,vararg args:Any?):Any?{
        fun box(c:Class<*>):Class<*> = when(c){java.lang.Boolean.TYPE->java.lang.Boolean::class.java;java.lang.Integer.TYPE->java.lang.Integer::class.java;else->c}
        val method=target.javaClass.methods.firstOrNull{it.name==name&&it.parameterCount==args.size&&it.parameterTypes.indices.all{i->args[i]==null||box(it.parameterTypes[i]).isInstance(args[i])}}
            ?:error("可选组件API不兼容：${target.javaClass.name}.$name")
        return method.invoke(target,*args)
    }
}
