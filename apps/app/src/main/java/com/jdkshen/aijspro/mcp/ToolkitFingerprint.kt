package com.jdkshen.aijspro.mcp

import com.google.gson.*
import java.io.File
import java.util.zip.ZipFile

/** Transparent heuristic evidence. This deliberately does not advertise the APKiD Python CLI. */
internal object ToolkitFingerprint {
    fun inspect(file:File):JsonObject {
        require(file.isFile&&file.length()<=ToolkitIo.MAX_APK)
        val hash=ToolkitIo.sha(file);val head=file.inputStream().use{input->ByteArray(8).let{b->val n=input.read(b);if(n<0)byteArrayOf()else b.copyOf(n)}}
        val findings=JsonArray();var format="unknown";var entries=0
        fun evidence(name:String,path:String,confidence:String="medium"){if(findings.size()<200)findings.add(ToolkitIo.json("name" to name,"evidence" to path,"confidence" to confidence,"conclusive" to false))}
        if(head.size>=4&&head[0]==0x7f.toByte()&&String(head,1,3,Charsets.US_ASCII)=="ELF")format="elf"
        else if(head.size>=4&&String(head,0,4,Charsets.US_ASCII)=="dex\n")format="dex"
        else if(head.size>=4&&head[0]==0x50.toByte()&&head[1]==0x4b.toByte()){
            format="zip";ZipFile(file).use{zip->
                var manifest=false;var dex=false;var total=0L
                zip.entries().asSequence().forEach{entry->ToolkitIo.ensureActive();require(++entries<=30000){"ZIP条目超过30000"};total+=entry.size.coerceAtLeast(0);require(total<=1024L*1024*1024){"归档展开超过1GiB"}
                    val name=entry.name;manifest=manifest||name=="AndroidManifest.xml";dex=dex||ToolkitApkTargets.isDex(name)
                    val low=name.lowercase();when{
                        low.endsWith("/libflutter.so")||low=="assets/flutter_assets/kernel_blob.bin"->evidence("Flutter",name,"high")
                        low.endsWith("/libil2cpp.so")||low.endsWith("/libunity.so")->evidence("Unity",name,"high")
                        low.endsWith("/libhermes.so")||low=="assets/index.android.bundle"->evidence("React Native / Hermes",name)
                        low.endsWith("/libjiagu.so")||low.endsWith("/libjiagu_a64.so")->evidence("Jiagu naming indicator",name)
                        low.endsWith("/libdexprotector.so")||low.endsWith("/libnesec.so")->evidence("Protection library naming indicator",name)
                        low.endsWith("/libfrida-gadget.so")->evidence("Frida Gadget naming indicator",name)
                        low.endsWith("/libnode.so")->evidence("Node.js",name,"high")
                        low.startsWith("assets/www/")&&low.endsWith("cordova.js")->evidence("Cordova",name,"high")
                    }
                };if(manifest)format=if(dex)"apk"else"apk-or-resource-archive"
            }
        }
        require(ToolkitIo.sha(file)==hash){"指纹分析期间文件发生变化"}
        return ToolkitIo.json("path" to file.path,"sha256" to hash,"size" to file.length(),"format" to format,"archiveEntries" to entries,"findings" to findings,
            "backend" to "aijs-heuristics-v1","fullApkid" to false,"executesTarget" to false,"limitations" to listOf("命名线索可被伪造或移除，未命中不能证明无壳/无框架","YARA另用yara_scan；不宣称内置完整APKiD规则或Python运行环境"))
    }
}

internal object ToolkitBinaryCompare {
    fun compare(first:File,second:File,args:JsonObject):JsonObject {
        require(first.length()<=ToolkitIo.MAX_ENTRY&&second.length()<=ToolkitIo.MAX_ENTRY)
        val a=first.readBytes();val b=second.readBytes();val limit=args.tInt("limit",50,1,200);val offset=args.tInt("offset",0,0,1000000)
        val results=JsonArray();var ranges=0;var different=0L;var position=0
        while(position<maxOf(a.size,b.size)) {
            ToolkitIo.ensureActive();if(position<a.size&&position<b.size&&a[position]==b[position]){position++;continue}
            val start=position
            do{different++;position++}while(position<maxOf(a.size,b.size)&&(position>=a.size||position>=b.size||a[position]!=b[position]))
            if(ranges in offset until offset+limit)results.add(ToolkitIo.json("offset" to start,"length" to position-start,
                "beforeHex" to ToolkitIo.hex(a.copyOfRange(minOf(start,a.size),minOf(position,a.size,start+64))),
                "afterHex" to ToolkitIo.hex(b.copyOfRange(minOf(start,b.size),minOf(position,b.size,start+64))),"previewTruncated" to (position-start>64)))
            ranges++
        }
        require(offset<=ranges){"offset 超出变化范围数量"};val end=minOf(ranges,offset+limit)
        return ToolkitIo.json("firstSha256" to ToolkitIo.version(a),"secondSha256" to ToolkitIo.version(b),"identical" to a.contentEquals(b),"differentBytePositions" to different,
            "items" to results,"total" to ranges,"offset" to offset,"nextOffset" to end,"hasMore" to (end<ranges),"semanticEquivalenceChecked" to false)
    }
}
