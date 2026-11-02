package com.jdkshen.aijspro.mcp

import com.google.gson.*
import java.io.File

/** A reusable utility class for the UI and MCP; implementation owns local files only. */
internal class McpToolkit(private val root: File, store: File, schemas: JsonObject,
    signer: ((File,File) -> Unit)? = null, assembler: ((Int,Long,String,String) -> ByteArray)? = null) {
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
            "capabilities" to ToolkitIo.json("signatures" to listOf("V1", "V2", "V3", "V3.1"),
                "zip" to ToolkitIo.json("readEncryption" to listOf("ZipCrypto", "AES"), "writeEncryption" to "AES-256", "authentication" to true),
                "regex" to ToolkitIo.json("engine" to "java.util.regex", "lookaround" to true, "backreferences" to true, "budgeted" to true),
                "resources" to ToolkitIo.json("defaultVariant" to "default", "perItemErrors" to true, "maxValueXmlChars" to 1048576, "resolveDepth" to 1),
                "native" to ToolkitIo.json("patchArchitectures" to listOf("aarch64", "arm", "thumb", "x86", "x86_64"),
                    "demangleAvailable" to ToolkitNative.demangleAvailable, "generalAssemblerOptional" to true,
                    "installCommand" to "aijs-toolchain install cpp", "availabilityProbe" to "get_build_tools")),
            "limits" to listOf("APK 快照最大512MiB、总量1GiB；单条目分析64MiB，文本10MiB", "只操作配置的工具目录；写入需版本或原字节校验",
                "ELF 元数据支持32/64位；指令分析在原生库可用时覆盖 AArch64、ARM/Thumb、x86/x86-64（Capstone 5.0.6），不可用时回退有界 AArch64 子集；未知指令明确标出", "不支持完整 Java 反编译；DEX 使用 Smali",
                "正则兼容Java语法（含环视/反向引用），受时间/工作量/取消限制；资源支持逐项读取及一级直接引用摘要；ELF不提供完整间接调用/数据流证明",
                "通用多架构汇编可使用终端可选 C/C++ 组件；缺后端会明确提示，不依赖MT")))
        name in McpFileToolkit.names -> files.call(name, args)
        name in McpZipToolkit.names -> zip.call(name, args)
        name in McpToolkitCatalog.names -> apk.call(name, args)
        else -> throw IllegalArgumentException("未知工具：$name")
    }
}
