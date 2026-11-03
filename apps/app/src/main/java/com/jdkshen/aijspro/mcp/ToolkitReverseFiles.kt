package com.jdkshen.aijspro.mcp

import java.io.File
import java.security.MessageDigest

/** Bounded project copy and publish. No replacement of an existing user directory. */
internal object ToolkitReverseFiles {
    fun copyTree(source:File,target:File):String {
        require(source.isDirectory&&source.canonicalFile==source.absoluteFile.normalize()){"工程目录无效或含符号链接"}
        val digest=MessageDigest.getInstance("SHA-256");var count=0;var total=0L
        source.walkTopDown().onEnter{dir->ToolkitIo.ensureActive();require(++count<=30000&&dir.canonicalFile==dir.absoluteFile.normalize()){"工程目录过多或含符号链接"};true}.forEach{file->
            ToolkitIo.ensureActive();require(file.canonicalFile==file.absoluteFile.normalize()){"工程含符号链接"}
            val relative=file.relativeTo(source).path;val out=File(target,relative)
            if(file.isDirectory){require(out.isDirectory||out.mkdirs())}
            else {require(++count<=30000&&file.isFile){"工程文件过多或不是普通文件"};digest.update(relative.toByteArray(Charsets.UTF_8))
                val before=ToolkitIo.sha(file);file.inputStream().use{input->out.outputStream().use{output->val buffer=ByteArray(65536)
                    while(true){ToolkitIo.ensureActive();val n=input.read(buffer);if(n<0)break;total+=n;require(total<=ToolkitIo.MAX_APK){"工程展开超过512MiB"};output.write(buffer,0,n)}}}
                require(ToolkitIo.sha(out)==before&&ToolkitIo.sha(file)==before){"工程在复制期间发生变化"};digest.update(before.toByteArray())
            }
        }
        return ToolkitIo.hex(digest.digest()).lowercase()
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
