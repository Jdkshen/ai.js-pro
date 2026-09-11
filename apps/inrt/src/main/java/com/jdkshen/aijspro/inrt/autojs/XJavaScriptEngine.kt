package com.jdkshen.aijspro.inrt.autojs

import android.content.Context
import com.stardust.autojs.engine.LoopBasedJavaScriptEngine
import com.stardust.autojs.script.EncryptedScripts
import com.stardust.autojs.script.JavaScriptFileSource
import com.stardust.autojs.script.ScriptSource
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
        // 统一入口：解密 + 按载荷类型分发（文本 / Rhino 编译类）。
        // 用 EncryptedScripts 而不是在本类里自行解密，是为了让 QuickJS 等其他引擎
        // 也能用同一套逻辑（QuickJS 引擎的打包应用曾经完全拿不到解密后的脚本）。
        val source = try {
            EncryptedScripts.toSource(file)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } ?: return false
        super.execute(source)
        return true
    }

}