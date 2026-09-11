package com.stardust.autojs.script

import com.stardust.autojs.engine.encryption.ScriptEncryption
import com.stardust.pio.PFiles
import java.io.File

/**
 * 打包产物脚本的统一入口：把「文件头 + 密文」还原成可以直接执行的脚本源。
 *
 * <p>以前解密只做在打包运行时的 Rhino 引擎里（`XJavaScriptEngine`），于是
 * **QuickJS 引擎的打包应用会拿密文当源码解析**（`illegal character` 之类的报错）。
 * 这里把「解密 + 按载荷类型分发」集中到一处，两种引擎共用。
 *
 * <p>载荷类型来自文件头 flags 的高字节（见 [EncryptedScriptFileHeader]）：
 * - 文本 → [StringScriptSource]
 * - Rhino 编译类 → [CompiledJavaScriptSource]
 */
object EncryptedScripts {

    /**
     * @return 加密脚本对应的可执行脚本源；文件没有加密头时返回 null（调用方继续走普通文件源）。
     */
    @JvmStatic
    @JvmOverloads
    fun toSource(file: File, name: String = file.name): JavaScriptSource? {
        val bytes = PFiles.readBytes(file.path)
        if (!EncryptedScriptFileHeader.isValidFile(bytes)) {
            return null
        }
        val flags = EncryptedScriptFileHeader.readFlags(bytes)
        val plain = ScriptEncryption.decrypt(bytes, EncryptedScriptFileHeader.BLOCK_SIZE)
        return when (EncryptedScriptFileHeader.payloadTypeOf(flags)) {
            EncryptedScriptFileHeader.PAYLOAD_TYPE_RHINO_CLASS -> {
                val payload = CompiledScriptPayload.read(plain)
                CompiledJavaScriptSource(name, payload.className, payload.classBytes)
            }
            else -> StringScriptSource(name, String(plain, Charsets.UTF_8))
        }
    }

    /** 文件是否带加密头（打包产物）。 */
    @JvmStatic
    fun isEncrypted(file: File): Boolean {
        val bytes = PFiles.readBytes(file.path)
        return EncryptedScriptFileHeader.isValidFile(bytes)
    }
}
