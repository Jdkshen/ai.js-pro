package com.jdkshen.aijspro.inrt.autojs

import android.content.Context
import com.stardust.autojs.engine.LoopBasedJavaScriptEngine
import com.stardust.autojs.engine.encryption.ScriptEncryption
import com.stardust.autojs.script.CompiledJavaScriptSource
import com.stardust.autojs.script.CompiledScriptPayload
import com.stardust.autojs.script.EncryptedScriptFileHeader
import com.stardust.autojs.script.JavaScriptFileSource
import com.stardust.autojs.script.ScriptSource
import com.stardust.autojs.script.StringScriptSource
import com.stardust.pio.PFiles
import java.io.File
import java.security.GeneralSecurityException

class XJavaScriptEngine(context: Context) : LoopBasedJavaScriptEngine(context) {


    override fun execute(source: ScriptSource, callback: ExecuteCallback?) {
        if (source is JavaScriptFileSource) {
            try {
                if (execute(source.file)) {
                    return
                }
            } catch (e: Throwable) {
                e.printStackTrace()
                return
            }
        }
        super.execute(source, callback)
    }

    private fun execute(file: File): Boolean {
        val bytes = PFiles.readBytes(file.path)
        if (!EncryptedScriptFileHeader.isValidFile(bytes)) {
            return false
        }
        val flags = EncryptedScriptFileHeader.readFlags(bytes)
        try {
            val plain = ScriptEncryption.decrypt(bytes, EncryptedScriptFileHeader.BLOCK_SIZE)
            if (EncryptedScriptFileHeader.payloadTypeOf(flags) ==
                    EncryptedScriptFileHeader.PAYLOAD_TYPE_RHINO_CLASS) {
                // 打包加密等级 ≥ 2：载荷是编译好的 class，加载后直接执行（无源码）。
                val payload = CompiledScriptPayload.read(plain)
                super.execute(CompiledJavaScriptSource(file.name, payload.className, payload.classBytes))
                return true
            }
            super.execute(StringScriptSource(file.name, String(plain)))
        } catch (e: GeneralSecurityException) {
            e.printStackTrace()
        }
        return true
    }

}