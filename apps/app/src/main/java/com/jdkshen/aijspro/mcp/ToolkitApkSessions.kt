package com.jdkshen.aijspro.mcp

import com.google.gson.*
import java.io.File
import java.util.zip.ZipFile

/** Checkpoints own immutable copies so subsequent replacement/deletion cannot erase rollback data. */
internal object ToolkitApkSessions {
    fun checkpoint(folder:File,args:JsonObject):JsonObject {
        val info=read(folder); expected(info,args,false)
        val parent=File(folder,"checkpoints").apply{require(isDirectory||mkdir())}
        require((parent.listFiles()?.count{it.isDirectory}?:0)<8){"单会话最多8个检查点"}
        val id=ToolkitIo.fresh();val target=File(parent,id);require(target.mkdir());val entries=File(target,"entries");require(entries.mkdir())
        try {
            val existing=parent.walkTopDown().filter{it.isFile}.sumOf{it.length()}
            var copied=0L
            info.getAsJsonObject("changedEntries").entrySet().forEach{(_,value)->
                ToolkitIo.ensureActive();val name=ToolkitIo.id(value.asString);val source=File(File(folder,"entries"),name)
                require(source.isFile);copied+=source.length();require(existing+copied<=512L*1024*1024){"检查点总量超过512MiB"}
                source.inputStream().use{input->File(entries,name).outputStream().use{output->input.copyTo(output,65536)}}
            }
            val metadata=info.deepCopy().apply{addProperty("checkpointId",id);addProperty("label",args.tString("label","").take(200));addProperty("createdAt",System.currentTimeMillis())}
            ToolkitIo.atomic(File(target,"session.json")){it.writeText(metadata.toString(),Charsets.UTF_8)}
            return ToolkitIo.json("checkpointId" to id,"revision" to info.tString("revision"),"copiedBytes" to copied,"label" to metadata.get("label"))
        }catch(e:Exception){target.deleteRecursively();throw e}
    }
    fun checkpoints(folder:File)=ToolkitIo.json("items" to (File(folder,"checkpoints").listFiles()?.mapNotNull{p->runCatching{
        val s=JsonParser().parse(File(p,"session.json").readText(Charsets.UTF_8)).asJsonObject
        ToolkitIo.json("checkpointId" to s.get("checkpointId"),"revision" to s.get("revision"),"label" to s.get("label"),"createdAt" to s.get("createdAt"))
    }.getOrNull()}?:emptyList<JsonObject>()))
    fun rollback(folder:File,args:JsonObject):JsonObject {
        val current=read(folder);expected(current,args,true)
        val checkpoint=File(File(folder,"checkpoints"),ToolkitIo.id(args.tString("checkpointId")))
        val restored=JsonParser().parse(File(checkpoint,"session.json").readText(Charsets.UTF_8)).asJsonObject
        val next=current.deepCopy();val changed=JsonObject();val created=ArrayList<File>();var committed=false
        try{
            restored.getAsJsonObject("changedEntries").entrySet().forEach{(entry,value)->
                ToolkitIo.entry(entry);ToolkitIo.ensureActive();val source=File(File(checkpoint,"entries"),ToolkitIo.id(value.asString));require(source.isFile)
                val target=File(File(folder,"entries"),ToolkitIo.fresh());created.add(target)
                source.inputStream().use{input->target.outputStream().use{output->input.copyTo(output,65536)}};changed.addProperty(entry,target.name)
            }
            next.add("changedEntries",changed);listOf("deleted","classChanges","classChangesComplete").forEach{next.add(it,restored.get(it))}
            next.addProperty("revision",ToolkitIo.fresh());next.addProperty("restoredFrom",args.tString("checkpointId"))
            ToolkitIo.atomic(File(folder,"session.json")){it.writeText(next.toString(),Charsets.UTF_8)};committed=true
            current.getAsJsonObject("changedEntries").entrySet().forEach{(_,v)->File(File(folder,"entries"),ToolkitIo.id(v.asString)).delete()}
            return ToolkitIo.json("revision" to next.get("revision"),"restoredFrom" to next.get("restoredFrom"),"sourceUnchanged" to true)
        }catch(e:Exception){if(!committed)created.forEach{it.delete()};throw e}
    }
    fun diff(base:File,folder:File):List<JsonObject>{
        val info=read(folder);val changed=info.getAsJsonObject("changedEntries");val deleted=info.getAsJsonArray("deleted").map{it.asString}.toSet()
        return ZipFile(base).use{zip->(changed.keySet()+deleted).sorted().map{entry->
            ToolkitIo.ensureActive();val before=zip.getEntry(entry);val after=changed.get(entry)?.asString?.let{File(File(folder,"entries"),ToolkitIo.id(it))}
            val row=ToolkitIo.json("entry" to entry,"kind" to if(entry in deleted)"deleted" else if(before==null)"added" else "changed",
                "beforeSize" to before?.size,"afterSize" to after?.length(),"afterSha256" to after?.let{ToolkitIo.sha(it)})
            fun preview(bytes:ByteArray):String?=runCatching{ToolkitIo.text(bytes).takeUnless{it.contains('\u0000')}}.getOrNull()
            if(before!=null&&before.size<=8192){row.addProperty("beforePreview",zip.getInputStream(before).use{preview(ToolkitIo.read(it,8192))})}
            if(after!=null&&after.length()<=8192)row.addProperty("afterPreview",preview(after.readBytes()))
            row
        }}
    }
    private fun read(folder:File)=JsonParser().parse(File(folder,"session.json").readText(Charsets.UTF_8)).asJsonObject
    private fun expected(info:JsonObject,args:JsonObject,required:Boolean){
        val revision=args.tString("expectedRevision","");require(!required||revision.isNotEmpty()){"回退必须提供expectedRevision"}
        require(revision.isEmpty()||revision==info.tString("revision")){"会话已变更，请先重新读取版本"}
    }
}
