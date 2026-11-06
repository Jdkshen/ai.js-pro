package com.jdkshen.aijspro.mcp

import java.io.File
import java.security.MessageDigest

/** Bounded project copy/publish plus an in-place build guard. No replacement of an existing user directory. */
internal object ToolkitReverseFiles {
    /** Copy statistics for apk_build_project staging diagnostics. */
    data class CopyStats(
        val sha256:String,
        val copiedEntries:Int,
        val skippedEntries:Int,
        val skippedBytes:Long,
    )

    private val SKIP_DIRS = setOf("build","dist",".git",".gradle")

    private fun skippedDirectory(dir:File,source:File):Boolean =
        dir != source && dir.name in SKIP_DIRS

    /** apktool 产物/备份不值得复制；目录级 build/dist/.git/.gradle 在 onEnter 处整棵剪掉。 */
    private fun skippedFile(file:File):Boolean =
        file.name.endsWith(".apk",ignoreCase=true) || file.name.equals("apktool.yml.bak",ignoreCase=true)

    /** 旧调用方仍按 String（完整 SHA-256）使用；详细统计走 copyTreeDetailed。 */
    fun copyTree(source:File,target:File):String = copyTreeDetailed(source,target).sha256

    fun copyTreeDetailed(source:File,target:File):CopyStats {
        require(source.isDirectory&&source.canonicalFile==source.absoluteFile.normalize()){"工程目录无效或含符号链接"}
        require(target.isDirectory||target.mkdirs()){"复制目标目录无效"}
        val digest=MessageDigest.getInstance("SHA-256")
        var copiedEntries=0
        var skippedEntries=0
        var skippedBytes=0L
        var total=0L
        source.walkTopDown().onEnter{dir->
            ToolkitIo.ensureActive()
            require(dir.canonicalFile==dir.absoluteFile.normalize()){"工程目录含符号链接"}
            if(dir==source) return@onEnter true
            if(skippedDirectory(dir,source)){
                val skipped=countSkipped(dir)
                skippedEntries+=skipped.first
                skippedBytes+=skipped.second
                return@onEnter false
            }
            require(++copiedEntries<=McpLimits.MAX_PROJECT_ENTRIES){"工程目录过多（上限 ${McpLimits.MAX_PROJECT_ENTRIES} 个条目，可在 McpLimits.MAX_PROJECT_ENTRIES 调整）"}
            val out=File(target,dir.relativeTo(source).path)
            require(out.isDirectory||out.mkdirs()){"无法创建复制目录：${dir.name}"}
            true
        }.forEach{file->
            ToolkitIo.ensureActive()
            if(!file.isFile) return@forEach
            require(file.canonicalFile==file.absoluteFile.normalize()){"工程含符号链接"}
            if(skippedFile(file)){
                skippedEntries++
                skippedBytes+=file.length()
                return@forEach
            }
            require(++copiedEntries<=McpLimits.MAX_PROJECT_ENTRIES){"工程文件过多或不是普通文件（上限 ${McpLimits.MAX_PROJECT_ENTRIES} 个条目，当前 ${copiedEntries}；可在 McpLimits.MAX_PROJECT_ENTRIES 调整）"}
            val relative=file.relativeTo(source).path
            val out=File(target,relative)
            val before=ToolkitIo.sha(file)
            file.inputStream().use{input->out.outputStream().use{output->val buffer=ByteArray(65536)
                while(true){ToolkitIo.ensureActive();val n=input.read(buffer);if(n<0)break;total+=n
                    require(total<=McpLimits.MAX_PROJECT_BYTES){"工程展开超过 ${McpLimits.MAX_PROJECT_BYTES} 字节（512MiB 的旧上限已放宽，可在 McpLimits.MAX_PROJECT_BYTES 调整）"}
                    output.write(buffer,0,n)}}}
            require(ToolkitIo.sha(out)==before&&ToolkitIo.sha(file)==before){"工程在复制期间发生变化"}
            digest.update(relative.toByteArray(Charsets.UTF_8))
            digest.update(before.toByteArray(Charsets.UTF_8))
        }
        return CopyStats(
            sha256=ToolkitIo.hex(digest.digest()).lowercase(),
            copiedEntries=copiedEntries,
            skippedEntries=skippedEntries,
            skippedBytes=skippedBytes,
        )
    }

    /** 统计被剪掉的整棵目录（目录本身 + 所有后代；只读，不创建目标文件）。 */
    private fun countSkipped(dir:File):Pair<Int,Long> {
        var entries=0
        var bytes=0L
        dir.walkTopDown().onEnter{entry->
            ToolkitIo.ensureActive()
            entries++
            if(entry!=dir&&entry.canonicalFile!=entry.absoluteFile.normalize()) return@onEnter false
            true
        }.forEach{entry->
            if(entry.isFile){entries++;bytes+=entry.length()}
        }
        return entries to bytes
    }

    /**
     * 对工程可编辑树做稳定 SHA-256（相对路径 + 文件内容，按路径排序）。
     * 排除 apktool 输出目录 build/dist 与 VCS/构建缓存，避免“apktool 生成物”被误判为源码变化。
     * 只用于 buildInPlace 前后自检；不写任何文件。
     */
    fun sourceTreeSha256(source:File):String {
        require(source.isDirectory&&source.canonicalFile==source.absoluteFile.normalize()){"工程目录无效或含符号链接"}
        val files=ArrayList<File>()
        val dirs=ArrayList<File>()
        var count=0
        source.walkTopDown().onEnter{dir->
            ToolkitIo.ensureActive()
            require(dir.canonicalFile==dir.absoluteFile.normalize()){"工程目录含符号链接"}
            if(skippedDirectory(dir,source)) return@onEnter false
            if(dir!=source) dirs.add(dir)
            true
        }.forEach{file->
            ToolkitIo.ensureActive()
            if(!file.isFile) return@forEach
            require(file.canonicalFile==file.absoluteFile.normalize()){"工程含符号链接"}
            require(++count<=McpLimits.MAX_PROJECT_ENTRIES){"工程文件过多（上限 ${McpLimits.MAX_PROJECT_ENTRIES}）"}
            files.add(file)
        }
        files.sortBy{it.relativeTo(source).path}
        dirs.sortBy{it.relativeTo(source).path}
        val digest=MessageDigest.getInstance("SHA-256")
        dirs.forEach{dir->
            ToolkitIo.ensureActive()
            digest.update(("D:"+dir.relativeTo(source).path).toByteArray(Charsets.UTF_8))
        }
        files.forEach{file->
            ToolkitIo.ensureActive()
            val relative=file.relativeTo(source).path
            digest.update(("F:"+relative).toByteArray(Charsets.UTF_8))
            digest.update(ToolkitIo.sha(file).toByteArray(Charsets.UTF_8))
        }
        return ToolkitIo.hex(digest.digest()).lowercase()
    }

    /**
     * in_place 预检；返回 null 表示可原地构建，否则返回回退原因（非空即复制模式）。
     * 已有 build/dist 时不删除用户目录，而是拒绝原地构建（更安全，仍可复制构建）。
     */
    fun inPlaceRefusal(project:File):String? {
        if(!project.isDirectory) return "project_not_directory"
        if(!project.canWrite()) return "project_not_writable"
        val yml=File(project,"apktool.yml")
        if(!yml.isFile) return "missing_apktool_yml"
        if(!yml.canRead()) return "apktool_yml_unreadable"
        if(File(project,"build").exists()) return "existing_build_dir"
        if(File(project,"dist").exists()) return "existing_dist_dir"
        return null
    }

    fun publishTree(source:File,destination:File)=ToolkitIo.withFileLocks(listOf(destination)) {
        require(!destination.exists()&&destination.parentFile?.isDirectory==true){"输出目录已存在或父目录不存在"}
        require(destination.canonicalFile==destination.absoluteFile.normalize()){"输出路径含符号链接"}
        val staging=File(destination.parentFile,".aijs-reverse-${ToolkitIo.fresh()}")
        require(staging.mkdir())
        try{copyTree(source,staging);ToolkitIo.ensureActive();require(!destination.exists()&&staging.renameTo(destination)){"发布新工程目录失败"}}
        finally{if(staging.exists())staging.deleteRecursively()}
    }
}
