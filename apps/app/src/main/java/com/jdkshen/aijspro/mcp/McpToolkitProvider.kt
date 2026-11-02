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
    private var toolkit: McpToolkit? = null
    private var selectedRoot = ""
    override fun onCreate() = true
    @Synchronized override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
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
            if (toolkit == null || selectedRoot != root.path) {
                val schemas = context.assets.open("mcp/toolkit-apk-schemas.json").bufferedReader().use { JsonParser().parse(it).asJsonObject }
                val store = File(File(context.filesDir, "apk-toolkit"), ToolkitIo.version(root.path.toByteArray()).take(16))
                toolkit = McpToolkit(root, store, schemas, { directory, output ->
                    val signer = AutoSigningIdentity.signer(output.parentFile, "APK工具")
                    if (signer is com.jdkshen.aijspro.autojs.build.sign.KeyStoreApkSigner) signer.signAligned(directory, output) else signer.sign(directory, output)
                }, { machine, address, source -> assemble(machine, address, source) })
                selectedRoot = root.path
            }
            when(method) {
                "definitions" -> ToolkitIo.json("tools" to toolkit!!.definitions())
                "call" -> toolkit!!.call(input.tString("name"), input.getAsJsonObject("arguments") ?: JsonObject())
                else -> throw IllegalArgumentException("工具进程操作无效")
            }
        } catch (failure: Exception) {
            ToolkitIo.json("ok" to false, "data" to null, "error" to ToolkitIo.json("type" to failure.javaClass.simpleName,
                "message" to (failure.message ?: "工具调用失败").take(4000)), "nextActions" to emptyList<Any>())
        }
        ToolkitIo.atomic(response) { it.writeText(Gson().toJson(payload), Charsets.UTF_8) }
        return Bundle().apply { putBoolean("ready", true); putInt("pid", Process.myPid()) }
    }
    private fun assemble(machine: Int, address: Long, source: String): ByteArray {
        require(machine == 183) { "可选汇编器当前支持 AArch64" }
        val context = requireNotNull(context); val env = ToolchainRuntime.environment(context)
        val cpp = env["AIJS_CPP_ROOT"] ?: error("请先在应用终端安装可选组件：aijs-toolchain install cpp")
        val folder = File(context.cacheDir, "assembler-${ToolkitIo.fresh()}"); require(folder.mkdir())
        val input = File(folder, "patch.S"); val output = File(folder, "patch.elf"); val log = File(folder, "compiler.log")
        try {
            input.writeText(".text\n.global _start\n_start:\n$source\n")
            val builder = ProcessBuilder(File(cpp, "bin/clang").path, "--target=aarch64-linux-android26", "-nostdlib", "-fuse-ld=lld",
                "-Wl,--build-id=none", "-Wl,-Ttext=${ToolkitElf.hex(address)}", "-Wl,-e,_start", input.path, "-o", output.path)
            builder.environment().putAll(env); builder.redirectErrorStream(true); builder.redirectOutput(log)
            val process = builder.start()
            try { if(!process.waitFor(20, TimeUnit.SECONDS)) { process.destroy(); throw IOException("汇编器超时") }
                require(process.exitValue() == 0 && output.isFile) { "汇编失败：${log.readText().take(4000)}" }
            } finally { if(process.isAlive) process.destroy() }
            val elf = ToolkitElf(output.readBytes()); val text = elf.sections.singleOrNull { it.name == ".text" } ?: error("汇编缺少.text")
            require(text.address == address && text.size <= 65536 && elf.sections.none { (it.type == 4L || it.type == 9L) && it.size > 0 }) { "汇编含未解决重定位或地址不匹配" }
            return elf.bytes.copyOfRange(text.offset.toInt(), (text.offset + text.size).toInt())
        } finally { input.delete(); output.delete(); log.delete(); folder.delete() }
    }
    override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, s: String?, a: Array<String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<String>?): Int = throw UnsupportedOperationException()
}
