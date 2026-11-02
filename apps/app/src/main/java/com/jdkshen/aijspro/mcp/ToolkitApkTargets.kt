package com.jdkshen.aijspro.mcp

internal class ToolkitException(val code: String, message: String, val argument: String? = null) :
    IllegalArgumentException(message)

/** One classification for every raw ZIP mutation; semantic editors use their own path. */
internal object ToolkitApkTargets {
    fun isDex(path: String) = path.matches(Regex("classes(?:[2-9]|[1-9][0-9]+)?\\.dex"))
    fun axmlPath(locator: String): String {
        require(locator.startsWith("axml:")) { "需要 axml: 定位符" }
        return ToolkitIo.entry(locator.removePrefix("axml:")).also { require(!it.endsWith('/')) { "目录不是 AXML 文件" } }
    }
    fun ordinary(path: String, bytes: ByteArray? = null, allowAxml: Boolean = false) {
        ToolkitIo.entry(path)
        require(!path.endsWith('/')) { "目录不是可操作的普通 ZIP 条目" }
        val code = when {
            isDex(path) -> "DEX_ENTRY_RESERVED"
            path == "resources.arsc" -> "RESOURCE_TABLE_RESERVED"
            !allowAxml && (path == "AndroidManifest.xml" || bytes?.let { ToolkitResources.isBinaryXml(it) } == true) -> "AXML_ENTRY_RESERVED"
            else -> return
        }
        throw ToolkitException(code, "此目标不能作为普通 ZIP 条目修改，请使用对应的 DEX/资源/AXML 接口：$path", "locator")
    }
}
