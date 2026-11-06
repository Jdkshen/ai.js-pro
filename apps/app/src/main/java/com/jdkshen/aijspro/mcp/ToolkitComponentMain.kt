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
                // #17：JADX 的 getErrorsCount 是全局计数；即使目标方法自己没报错，也说明本次工程存在未反编译完整的方法。
                val incompleteReasons=ArrayList<String>()
                if(errorsCount>0)incompleteReasons.add("errorsCount=" + errorsCount)
                if(code.contains("JADX ERROR",ignoreCase=true))incompleteReasons.add("content contains JADX ERROR")
                if(code.contains("Method dump skipped",ignoreCase=true))incompleteReasons.add("content contains Method dump skipped")
                val incomplete=incompleteReasons.isNotEmpty()
                val incompleteReason=if(incomplete) "反编译结果不完整：" + incompleteReasons.joinToString("；") +
                    "。请改用 smali 视图：apk_read_text(locator=\"" + locator + "\")" else ""
                val nextActions=if(incomplete) listOf(ToolkitIo.json("tool" to "apk_read_text",
                    "locator" to locator,"hint" to "换 smali 视图读取同一方法的字节码/注释")) else emptyList<Any>()
                val response=ToolkitIo.json("locator" to locator,"language" to "java","content" to code.substring(start,end),"offset" to start,"nextOffset" to end,
                    "hasMore" to (end<code.length),"totalChars" to code.length,"anchors" to anchors,"errorsCount" to errorsCount,
                    "warningsCount" to warningsCount,"decompileIncomplete" to incomplete,"incompleteReason" to incompleteReason,
                    "nextActions" to nextActions,"purpose" to "analysis","rebuildGuaranteed" to false)
                File(args.tString("output")).writeText(response.toString(),Charsets.UTF_8)
            }finally{invoke(engine,"close")}
        }catch(e:Throwable){e.printStackTrace();System.exit(1)}
    }
    private fun invoke(target:Any,name:String,vararg args:Any?):Any?{
        fun box(c:Class<*>):Class<*> = when(c){java.lang.Boolean.TYPE->java.lang.Boolean::class.java;java.lang.Integer.TYPE->java.lang.Integer::class.java;else->c}
        val method=target.javaClass.methods.firstOrNull{it.name==name&&it.parameterCount==args.size&&it.parameterTypes.indices.all{i->args[i]==null||box(it.parameterTypes[i]).isInstance(args[i])}}
            ?:error("可选组件API不兼容：${target.javaClass.name}.$name")
        return method.invoke(target,*args)
    }
}
