package com.jdkshen.aijspro.mcp

import com.google.gson.*

internal object ToolkitNative {
    val available: Boolean = try { System.loadLibrary("aijs_binarytools"); true } catch (_: UnsatisfiedLinkError) { false }
    private external fun decodeWindow(bytes: ByteArray, machine: Int, little: Boolean, address: Long, mode: Int, maxInstructions: Int): String
    private external fun demangleSymbol(name: ByteArray): ByteArray?
    @Volatile private var demanglerLinked = true
    val demangleAvailable: Boolean get() = available && demanglerLinked
    private val names = object : LinkedHashMap<String, String>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 128
    }
    @Synchronized fun demangle(name: String): String {
        if(!demangleAvailable || !name.startsWith("_Z") || name.length > 1024) return name
        names[name]?.let { return it }
        val result = try { demangleSymbol(name.toByteArray(Charsets.UTF_8))?.takeIf { it.size <= 65536 }?.toString(Charsets.UTF_8) ?: name }
            catch (_: UnsatisfiedLinkError) { demanglerLinked = false; name }
        names[name] = result; return result
    }
    fun decode(bytes: ByteArray, machine: Int, little: Boolean, address: Long, mode: Int, count: Int): List<JsonObject> {
        check(available) { "本机原生指令分析库不可用" }
        require(bytes.size <= 65536 && count in 1..16384)
        return JsonParser().parse(decodeWindow(bytes,machine,little,address,mode,count)).asJsonArray.map { it.asJsonObject }
    }
}
