package com.jdkshen.aijspro.mcp

import com.google.gson.*
import java.io.File

/** A reusable utility class for the UI and MCP; implementation owns local files only. */
internal class McpToolkit(private val root: File, store: File, schemas: JsonObject,
    signer: ((File,File) -> Unit)? = null, assembler: ((Int,Long,String) -> ByteArray)? = null) {
    private val files = McpFileToolkit(root)
    private val zip = McpZipToolkit(root)
    private val apk = McpApkToolkit(root, store, schemas, signer, assembler)
    fun definitions() = JsonArray().apply {
        add(ToolkitIo.json("name" to "toolkit_status", "description" to "查看本机文件、ZIP、APK、DEX、资源与ELF工具及真实能力限制；全部独立于 MT。操作目录沿用 MCP 设置。",
            "category" to "toolkit", "categoryTitle" to "文件与APK工具", "inputSchema" to ToolkitIo.json("type" to "object", "additionalProperties" to false, "properties" to JsonObject())))
        val definitions = files.definitions().toList() + zip.definitions().toList() + apk.definitions().toList()
        McpToolkitCatalog.names.drop(1).forEach { name -> add(definitions.single { it.asJsonObject.tString("name") == name }) }
    }
    fun call(name: String, args: JsonObject): JsonObject = when {
        name == "toolkit_status" -> ToolkitIo.ok(ToolkitIo.json("implementation" to "aijs-native", "requiresMt" to false,
            "homePath" to root.canonicalPath, "toolCount" to McpToolkitCatalog.names.size,
            "groups" to listOf("file", "zip", "apk", "dex", "resources", "elf"),
            "limits" to listOf("APK 快照最大512MiB、总量1GiB；单条目分析64MiB，文本10MiB", "只操作配置的工具目录；写入需版本或原字节校验",
                "ELF 元数据支持32/64位；指令分析在原生库可用时覆盖 AArch64、ARM/Thumb、x86/x86-64（Capstone 5.0.6），不可用时回退有界 AArch64 子集；未知指令明确标出", "不支持完整 Java 反编译；DEX 使用 Smali",
                "正则使用RE2，不支持回溯/反向引用/环视；资源引用保留；ELF不提供完整间接调用/数据流证明")))
        name in McpFileToolkit.names -> files.call(name, args)
        name in McpZipToolkit.names -> zip.call(name, args)
        name in McpToolkitCatalog.names -> apk.call(name, args)
        else -> throw IllegalArgumentException("未知工具：$name")
    }
}
