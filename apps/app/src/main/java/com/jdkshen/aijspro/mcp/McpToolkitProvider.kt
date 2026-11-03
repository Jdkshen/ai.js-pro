package com.jdkshen.aijspro.mcp

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import com.google.gson.*
import com.jdkshen.aijspro.autojs.build.sign.AutoSigningIdentity
import com.stardust.autojs.runtime.tool.ToolchainRuntime
import java.io.*
import java.util.concurrent.TimeUnit

/** Isolated :tools process: parsers/compilers cannot take down the main or script process. */
class McpToolkitProvider : ContentProvider() {
    private val toolkits = LinkedHashMap<String,McpToolkit>()
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        check(Binder.getCallingUid() == Process.myUid()) { "仅本应用可调用工具进程" }
        val context = requireNotNull(context)
        val key = ToolkitIo.id(requireNotNull(arg))
        val ipc = File(context.cacheDir, "toolkit-ipc").canonicalFile
        val request = File(ipc, "$key.request"); val response = File(ipc, "$key.response")
        require(request.isFile && request.canonicalFile == request.absoluteFile.normalize() && request.length() <= 20 * 1024 * 1024) { "工具请求无效或过大" }
        val input = JsonParser().parse(request.readText(Charsets.UTF_8)).asJsonObject
        val root = File(input.tString("root")).canonicalFile
        require(root.isDirectory) { "工具操作目录不存在" }
        val payload: JsonObject = try {
            val toolkit = synchronized(toolkits) { toolkits[root.path] ?: run {
                require(toolkits.size<8) { "已打开8个工具目录，请重启工具进程后切换目录" }
                val schemas=context.assets.open("mcp/toolkit-apk-schemas.json").bufferedReader().use{JsonParser().parse(it).asJsonObject}
                val store=File(File(context.filesDir,"apk-toolkit"),ToolkitIo.version(root.path.toByteArray()).take(16))
                McpToolkit(root,store,schemas,{archive,output->
                    val signer=AutoSigningIdentity.signer(output.parentFile,"APK工具")
                    check(signer is com.jdkshen.aijspro.autojs.build.sign.KeyStoreApkSigner){"签名身份不支持归档签名"}
                    signer.signAlignedArchive(archive,output)
                },{machine,address,source,instructionMode->assemble(machine,address,source,instructionMode)},AndroidToolkitRuntime(context,root))
                    .also{toolkits[root.path]=it}
            } }
            when(method) {
                "definitions" -> ToolkitIo.json("tools" to toolkit.definitions())
                "call" -> toolkit.call(input.tString("name"), input.getAsJsonObject("arguments") ?: JsonObject())
                else -> throw IllegalArgumentException("工具进程操作无效")
            }
        } catch (failure: Exception) {
            ToolkitIo.json("ok" to false, "data" to null, "error" to ToolkitIo.json("type" to failure.javaClass.simpleName,
                "code" to if(failure is ToolkitException) failure.code else if(failure is ToolkitRegex.BudgetExceeded) "REGEX_BUDGET_EXCEEDED" else if(failure is IllegalArgumentException) "INVALID_ARGUMENT" else "TOOL_FAILED",
                "argument" to if(failure is ToolkitException) failure.argument else null,
                "severity" to "warning", "recoverable" to true, "retrySameArguments" to false,
                "message" to (failure.message ?: "工具调用失败").take(4000)), "nextActions" to emptyList<Any>())
        }
        val json = GsonBuilder().serializeNulls().disableHtmlEscaping().create()
        var serialized = json.toJson(payload)
        if(serialized.toByteArray(Charsets.UTF_8).size > 24 * 1024 * 1024) serialized = json.toJson(ToolkitIo.json(
            "ok" to false, "data" to null, "error" to ToolkitIo.json("code" to "RESPONSE_TOO_LARGE",
                "message" to "结果超过24MiB，请缩小批量、文本或集合预算后重试", "recoverable" to true), "nextActions" to emptyList<Any>()))
        ToolkitIo.atomic(response) { it.writeText(serialized, Charsets.UTF_8) }
        return Bundle().apply { putBoolean("ready", true); putInt("pid", Process.myPid()) }
    }

    private fun assemble(machine: Int, address: Long, source: String, instructionMode: String): ByteArray {
        val spec = ToolkitAssembler.resolve(machine, instructionMode, address, virtualInput = true, mappedAddress = address)
        val effective = spec.effectiveAddress
        ToolkitAssemblySource.validate(source)
        val prepared = ToolkitAssemblySource.forClang(source, spec.instructionSet)
        val target = when (spec.instructionSet) {
            "aarch64" -> "aarch64-linux-android26"
            "arm", "thumb" -> "armv7a-linux-androideabi26"
            "x86" -> "i686-linux-android26"
            "x86_64" -> "x86_64-linux-android26"
            else -> error("未知指令集：${spec.instructionSet}")
        }
        val context = requireNotNull(context); val env = ToolchainRuntime.environment(context)
        val cpp = env["AIJS_CPP_ROOT"] ?: error("请先在应用终端安装可选组件：aijs-toolchain install cpp")
        val clang = File(cpp, "bin/clang")
        require(clang.isFile) { "可选 C/C++ 组件缺少 clang；请运行 aijs-toolchain install cpp" }
        val folder = File(context.cacheDir, "assembler-${ToolkitIo.fresh()}"); require(folder.mkdir())
        val input = File(folder, "patch.S"); val output = File(folder, "patch.elf"); val log = File(folder, "compiler.log")
        val layout = File(folder, "patch.ld")
        try {
            val syntax = when (spec.instructionSet) {
                "thumb" -> ".syntax unified\n.thumb\n.type _start, %function\n"
                "arm" -> ".syntax unified\n.arm\n.type _start, %function\n"
                "x86", "x86_64" -> ".intel_syntax noprefix\n"
                else -> ""
            }
            input.writeText("$syntax.text\n.global _start\n_start:\n${prepared.source}\n.global _aijs_end\n_aijs_end:\n")
            // A standalone text segment avoids newer LLD's default image-base subtraction
            // and PHDR/text overlap at small virtual addresses. This ELF is only an
            // assembler container; it is never loaded or installed on the device.
            layout.writeText("PHDRS { code PT_LOAD FLAGS(5); }\nSECTIONS { .text ${ToolkitElf.hex(effective)} : { *(.text .text.*) } :code /DISCARD/ : { *(.comment) *(.note*) *(.ARM.exidx*) *(.ARM.extab*) } }\n")
            val command = mutableListOf(
                clang.path,
                "--target=$target",
                "-nostdlib",
                "-static",
                "-fuse-ld=lld",
                "-Wl,--build-id=none",
                "-Wl,--no-dynamic-linker",
                "-Wl,-z,max-page-size=4096",
                "-Wl,-z,common-page-size=4096",
                "-Wl,-T,${layout.path}",
                "-Wl,-e,_start",
            )
            when (spec.instructionSet) {
                "aarch64" -> command += "-march=armv8-a"
                "arm" -> command += listOf("-marm", "-march=armv7-a")
                "thumb" -> command += listOf("-mthumb", "-march=armv7-a")
            }
            prepared.absoluteSymbols.forEach { (name, value) -> command += "-Wl,--defsym=$name=${ToolkitElf.hex(value)}" }
            command += listOf(input.path, "-o", output.path)
            val builder = ProcessBuilder(command)
            builder.environment().putAll(env); builder.redirectErrorStream(true); builder.redirectOutput(log)
            val process = builder.start()
            try {
                if (!process.waitFor(20, TimeUnit.SECONDS)) { process.destroy(); throw IOException("汇编器超时") }
                require(process.exitValue() == 0 && output.isFile) { "汇编失败：${log.readText().take(4000)}" }
            } finally { if (process.isAlive) process.destroy() }
            val elf = ToolkitElf(output.readBytes())
            val text = elf.sections.firstOrNull { it.name == ".text" } ?: error("汇编缺少 .text")
            require(text.address == effective) { "汇编 .text 地址不匹配：expected=${ToolkitElf.hex(effective)} actual=${ToolkitElf.hex(text.address)}" }
            require(elf.sections.none { (it.type == 4L || it.type == 9L) && it.size > 0 }) { "汇编含未解决重定位" }
            val startSymbol = elf.symbols.firstOrNull { it.name == "_start" }
            val endSymbol = elf.symbols.firstOrNull { it.name == "_aijs_end" }
            val start = startSymbol?.address?.let { if (spec.instructionSet == "arm" || spec.instructionSet == "thumb") it and -2L else it } ?: text.address
            val end = endSymbol?.address?.let { if (spec.instructionSet == "arm" || spec.instructionSet == "thumb") it and -2L else it }
                ?: (text.address + text.size)
            require(start == effective) { "汇编入口地址不匹配：expected=${ToolkitElf.hex(effective)} actual=${ToolkitElf.hex(start)}" }
            require(end >= start) { "汇编结束符号无效" }
            val size = end - start
            require(size in 0L..65536L) { "汇编结果过大" }
            val textOffset = start - text.address
            require(textOffset >= 0 && size <= text.size - textOffset) { "汇编符号超出 .text 范围" }
            val outputOffset = text.offset + textOffset
            require(outputOffset >= 0 && size <= elf.bytes.size - outputOffset) { "汇编结果越界" }
            return elf.bytes.copyOfRange(outputOffset.toInt(), (outputOffset + size).toInt())
        } finally { input.delete(); output.delete(); log.delete(); layout.delete(); folder.delete() }
    }
    override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, s: String?, a: Array<String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<String>?): Int = throw UnsupportedOperationException()
}
