package com.jdkshen.aijspro.mcp

import com.google.gson.*

internal object ToolkitNative {
    val available: Boolean = try { System.loadLibrary("aijs_binarytools"); true } catch (_: UnsatisfiedLinkError) { false }
    private external fun decodeWindow(bytes: ByteArray, machine: Int, little: Boolean, address: Long, mode: Int, maxInstructions: Int): String
    fun decode(bytes: ByteArray, machine: Int, little: Boolean, address: Long, mode: Int, count: Int): List<JsonObject> {
        check(available) { "本机原生指令分析库不可用" }
        require(bytes.size <= 65536 && count in 1..16384)
        return JsonParser().parse(decodeWindow(bytes,machine,little,address,mode,count)).asJsonArray.map { it.asJsonObject }
    }
}
