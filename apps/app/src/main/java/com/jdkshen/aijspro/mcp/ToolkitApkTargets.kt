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
        val hint = when (code) {
            "AXML_ENTRY_RESERVED" ->
                "AXML/Manifest 是二进制 XML，不能走普通字节补丁或普通 ZIP 改写。可走通的路径：" +
                    "① 读：apk_read_text(locator=\"axml:$path\") 或 apk_read_text(locator=\"zip_entry:$path\")，拿到 text/targetVersion；" +
                    "② 改：apk_edit_text(locator=\"axml:$path\", targetVersion, edits=[{mode:\"write_target\",writeText:\"<完整XML>\"}])，工具会调 ToolkitResources.encodeXml 原子写回会话；" +
                    "③ 或 apk_decode_project 解出可读 XML → file_edit_text 改 → apk_build_project 回编。"
            "DEX_ENTRY_RESERVED" -> "DEX 请用 dex_class:/dex_method: 定位符和 apk_read_text/apk_edit_text(DEX 语义编辑)。"
            "RESOURCE_TABLE_RESERVED" -> "resources.arsc 请用 apk_resource_read/apk_resource_edit/apk_resource_add，不要走普通 ZIP/字节补丁。"
            else -> "请使用对应的 DEX/资源/AXML 接口。"
        }
        throw ToolkitException(code, hint + " 收到：$path", "locator")
    }
}
