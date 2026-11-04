package com.jdkshen.aijspro.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import kotlin.math.min

/**
 * 本地文件工具集：19 个 file_* 工具，供 MCP 主集成注册。
 *
 * 安全边界：
 * - 所有路径都被限制在构造时传入的 root 内；绝对路径同样必须 canonical 落在 root 内；
 * - 拒绝 URI、NUL、越界路径；沿路径逐段比较 canonical，拒绝符号链接分量；
 * - 空路径只允许 file_list 与 file_get_access_policy；
 * - 不允许删除 root；递归删除必须显式 recursive=true，并且有节点/时间/不跟随链接保护；
 * - 写文件必须先校验 targetVersion（SHA-256），再写同目录临时文件，最后原子替换/回滚。
 *
 * 失败一律抛出 IllegalArgumentException / IOException / SecurityException，由主集成转换成
 * MCP 错误；成功返回普通 payload：{ok:true,data:...,error:null,nextActions:[...]}。
 */
internal class McpFileToolkit(private val root: File) {

    companion object {
        /** 对外暴露的工具名（去掉 mt_ 前缀，保留相同语义）。 */
        val names: List<String> = listOf(
            "file_get_access_policy",
            "file_list",
            "file_stat",
            "file_read_text",
            "file_read_bytes",
            "file_edit_text",
            "file_write_bytes",
            "file_patch_bytes",
            "file_copy",
            "file_move",
            "file_delete",
            "file_create_directory",
            "file_delete_directory",
            "file_search",
            "file_replace_text",
            "file_truncate_bytes",
            "file_append_bytes",
            "file_append_text",
            "file_search_text",
            "file_discover_apk",
            "file_access_diagnose",
        )

        private const val MAX_SAFE_INTEGER = 9007199254740991L
        private const val MAX_BYTE_PAGE = 65536
        private const val MAX_TEXT_BYTES = 10 * 1024 * 1024
        private const val MAX_BINARY_WRITE_BYTES = 1024 * 1024
        private const val MAX_PATCHES = 200
        private const val MAX_PATCH_BYTES_PER_ITEM = 4096
        private const val MAX_PATCH_BYTES_TOTAL = 65536
        private const val MAX_EDIT_ITEMS = 200
        private const val MAX_EDIT_TEXT_CODE_POINTS = 524288
        private const val MAX_SEARCH_PATTERN_CHARS = 16384
        private const val MAX_SEARCH_NODES = 50000
        private const val SEARCH_TIME_BUDGET_NANOS = 3000L * 1000L * 1000L
        private const val MAX_SEARCH_WARNINGS = 20
        private const val MAX_LIST_LIMIT = 1000
        private const val MAX_SEARCH_LIMIT = 1000
        private const val MAX_SEARCH_TEXT_LIMIT = 200
        private const val MAX_SEARCH_TEXT_CONTEXT = 100
        private const val MAX_SEARCH_TEXT_LINE_CHARS = 1024 * 1024
        private const val MAX_SEARCH_TEXT_LINES = 1000000
        private const val MAX_CURSOR_CHARS = 65536
        private const val CURSOR_TTL_MILLIS = 30L * 60L * 1000L
        private const val CURSOR_FORMAT_VERSION = 1
        private const val MAX_RECURSIVE_DELETE_NODES = 200000
        private const val RECURSIVE_DELETE_TIME_BUDGET_NANOS = 60000L * 1000L * 1000L
        private const val MAX_RECURSIVE_DELETE_DEPTH = 256
        private const val CONTENT_SCAN_WINDOW_CHARS = 256 * 1024
        private const val CONTENT_SCAN_CARRY_CHARS = 64 * 1024

        private val URI_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
        private val WINDOWS_DRIVE = Regex("^[A-Za-z]:[\\\\/]")
        private val VERSION_TOKEN = Regex("^[0-9a-f]{64}$")
        private val ISO_TIME = Regex(
            "^([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2})" +
                "(?:\\.([0-9]{1,3}))?(Z|[+-][0-9]{2}:[0-9]{2})$"
        )
        private val CASE_INSENSITIVE = Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE

        private val TEXT_CODECS: List<TextCodec> = listOf(
            TextCodec("utf-8", Charsets.UTF_8, ByteArray(0)),
            TextCodec("utf-8-bom", Charsets.UTF_8, byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())),
            TextCodec("utf-16le", Charsets.UTF_16LE, byteArrayOf(0xFF.toByte(), 0xFE.toByte())),
            TextCodec("utf-16be", Charsets.UTF_16BE, byteArrayOf(0xFE.toByte(), 0xFF.toByte())),
        )

        // ------------------------------------------------------------------
        // 工具定义
        // ------------------------------------------------------------------

        private fun stringField(
            description: String,
            default: String? = null,
            enumValues: List<String>? = null,
            maxLength: Int? = null,
        ): JsonObject = JsonObject().apply {
            addProperty("type", "string")
            addProperty("description", description)
            default?.let { addProperty("default", it) }
            maxLength?.let { addProperty("maxLength", it) }
            enumValues?.let { values ->
                add("enum", JsonArray().apply { values.forEach { add(it) } })
            }
        }

        private fun intField(
            description: String,
            minimum: Long? = null,
            maximum: Long? = null,
            default: Long? = null,
        ): JsonObject = JsonObject().apply {
            addProperty("type", "integer")
            addProperty("description", description)
            minimum?.let { addProperty("minimum", it) }
            maximum?.let { addProperty("maximum", it) }
            default?.let { addProperty("default", it) }
        }

        private fun boolField(description: String, default: Boolean? = null): JsonObject =
            JsonObject().apply {
                addProperty("type", "boolean")
                addProperty("description", description)
                default?.let { addProperty("default", it) }
            }

        private fun arrayField(
            description: String,
            items: JsonObject,
            minimum: Int? = null,
            maximum: Int? = null,
        ): JsonObject = JsonObject().apply {
            addProperty("type", "array")
            addProperty("description", description)
            add("items", items)
            minimum?.let { addProperty("minItems", it) }
            maximum?.let { addProperty("maxItems", it) }
        }

        private fun objectField(
            description: String,
            properties: JsonObject,
            required: List<String>,
            additionalProperties: Boolean = false,
        ): JsonObject = JsonObject().apply {
            addProperty("type", "object")
            addProperty("description", description)
            add("properties", properties)
            add("required", JsonArray().apply { required.forEach { add(it) } })
            addProperty("additionalProperties", additionalProperties)
        }

        private fun schema(required: List<String>, properties: JsonObject): JsonObject =
            JsonObject().apply {
                addProperty("type", "object")
                add("properties", properties)
                add("required", JsonArray().apply { required.forEach { add(it) } })
                addProperty("additionalProperties", false)
            }

        private fun props(vararg pairs: Pair<String, JsonObject>): JsonObject =
            JsonObject().apply { pairs.forEach { add(it.first, it.second) } }

        private fun editItemSchema(): JsonObject = objectField(
            "一次编辑：write_target 整体写；其余模式要求 matchText 在原文中恰好出现一次。",
            props(
                "mode" to stringField(
                    "编辑模式。",
                    enumValues = listOf(
                        "write_target", "replace_match", "insert_before_match", "insert_after_match"
                    ),
                ),
                "matchText" to stringField("精确匹配文本；write_target 时必须为空。"),
                "writeText" to stringField("写入文本；不自动添加换行，可为空。"),
            ),
            listOf("mode", "matchText", "writeText"),
        )

        private fun patchItemSchema(): JsonObject = objectField(
            "一个等长字节补丁。",
            props(
                "byteOffset" to intField("0 起始字节偏移。", 0, MAX_SAFE_INTEGER),
                "expectedHex" to stringField("当前字节，大写或小写十六进制、无分隔符、非空。"),
                "replacementHex" to stringField("替换字节，必须与 expectedHex 等长。"),
            ),
            listOf("byteOffset", "expectedHex", "replacementHex"),
        )

        private fun buildDefinitions(): JsonArray {
            val array = JsonArray()

            fun add(name: String, title: String, description: String, schema: JsonObject) {
                array.add(JsonObject().apply {
                    addProperty("name", name)
                    addProperty("title", title)
                    addProperty("description", description)
                    addProperty("category", "toolkit")
                    addProperty("categoryTitle", "文件与APK工具")
                    add("inputSchema", schema)
                })
            }

            add(
                "file_get_access_policy", "读取文件访问策略",
                "返回当前本地文件 root、读写能力和路径规则。该工具不需要参数，用于确认可用范围。",
                schema(
                    emptyList(),
                    JsonObject(),
                ),
            )

            add(
                "file_list", "列出本地目录",
                "按深度优先顺序列出 root 内目录；path 为空表示 root。包含隐藏项，不跟随符号链接。" +
                    "分页 cursor 绑定目录、递归开关与页大小，不能跨目录复用。",
                schema(
                    listOf("path", "recursive", "cursor", "limit"),
                    props(
                        "path" to stringField("绝对路径或相对 root 的目录；空字符串表示 root。"),
                        "recursive" to boolField("是否递归进入可读子目录。"),
                        "cursor" to stringField("第一页传空字符串；后续原样复制上一页返回的 cursor。"),
                        "limit" to intField("每页条目数，1..1000。", 1, MAX_LIST_LIMIT.toLong(), null),
                    ),
                ),
            )

            add(
                "file_stat", "查看路径信息",
                "查看 root 内路径的类型、大小、修改时间；普通文件返回流式 SHA-256 targetVersion。" +
                    "缺失路径返回 type=missing 与 targetVersion=missing，便于创建前判断。",
                schema(
                    listOf("path"),
                    props("path" to stringField("非空绝对路径或相对 root 的路径。")),
                ),
            )

            add(
                "file_read_text", "分页读取文本",
                "严格按 UTF-8 / UTF-8 BOM / UTF-16LE / UTF-16BE 解码，保留真实 CRLF、LF、CR。" +
                    "位置与 maxChars 都按 Unicode 码点计，单页不会把 CRLF 拆开。",
                schema(
                    listOf("path", "encoding", "targetVersion", "limit", "maxChars", "startLine", "startColumn"),
                    props(
                        "path" to stringField("非空绝对路径或相对 root 的路径。"),
                        "encoding" to stringField("空字符串自动识别；否则使用上次返回的精确小写编码令牌。"),
                        "targetVersion" to stringField("首读传空字符串；续读原样复制上一页返回值。"),
                        "limit" to intField("本页最多触及的逻辑行数，1..2000。", 1, 2000, 500),
                        "maxChars" to intField("本页最多返回的 Unicode 码点数，2..131072。", 2, 131072, 49152),
                        "startLine" to intField("0 起始逻辑行号。", 0, MAX_SAFE_INTEGER, 0),
                        "startColumn" to intField("0 起始 Unicode 码点列号。", 0, MAX_SAFE_INTEGER, 0),
                    ),
                ),
            )

            add(
                "file_read_bytes", "分页读取字节",
                "读取普通文件的原始字节页，返回大写十六进制和整文件 SHA-256。" +
                    "targetVersion 非空时必须与当前文件一致，避免拼接不同版本。",
                schema(
                    listOf("path", "targetVersion", "byteOffset", "maxBytes"),
                    props(
                        "path" to stringField("非空绝对路径或相对 root 的路径。"),
                        "targetVersion" to stringField("首读传空字符串；续读原样复制上一页返回值。"),
                        "byteOffset" to intField("0 起始字节偏移。", 0, MAX_SAFE_INTEGER, 0),
                        "maxBytes" to intField("每页字节数，1..65536。", 1, MAX_BYTE_PAGE.toLong(), 256),
                    ),
                ),
            )

            add(
                "file_edit_text", "编辑或创建文本",
                "严格解码、编辑、严格编码，不转码、不改换行、不自动加 BOM。" +
                    "已有文件必须携带 read_text 的 encoding 与 targetVersion；创建时 targetVersion=missing，" +
                    "且 edits 必须只含一个 write_target。",
                schema(
                    listOf("path", "targetVersion", "encoding", "edits"),
                    props(
                        "path" to stringField("非空绝对路径或相对 root 的路径。"),
                        "targetVersion" to stringField("已有文件传 SHA-256；仅创建新文件传 missing。"),
                        "encoding" to stringField("已有文件传实际编码令牌；创建传 utf-8 / utf-8-bom 等。"),
                        "edits" to arrayField("1..200 条编辑，全部匹配文本与写入文本合计不超过 524288 码点。", editItemSchema(), 1, MAX_EDIT_ITEMS),
                    ),
                ),
            )

            add(
                "file_write_bytes", "写入字节文件",
                "用十六进制创建或整体替换普通文件。创建严格使用 targetVersion=missing；" +
                    "覆盖必须携带当前 SHA-256。空 hex 会创建或清空为零字节文件。",
                schema(
                    listOf("path", "targetVersion", "hex"),
                    props(
                        "path" to stringField("非空绝对路径或相对 root 的路径，父目录必须存在。"),
                        "targetVersion" to stringField("missing 仅用于创建；否则为当前 64 位小写十六进制 SHA-256。"),
                        "hex" to stringField("0..1048576 原始字节，偶数长度十六进制，无分隔符。", maxLength = 2 * MAX_BINARY_WRITE_BYTES),
                    ),
                ),
            )

            add(
                "file_patch_bytes", "按字节补丁",
                "对已有普通文件应用 1..200 个等长、互不重叠的字节补丁；先校验所有 expectedHex 与版本，" +
                    "再流式生成同目录临时文件并原子替换。",
                schema(
                    listOf("path", "targetVersion", "patches"),
                    props(
                        "path" to stringField("非空绝对路径或相对 root 的普通文件。"),
                        "targetVersion" to stringField("当前 64 位小写十六进制 SHA-256；不接受 missing。"),
                        "patches" to arrayField("1..200 个补丁；单条 1..4096 字节，总计不超过 65536 字节。", patchItemSchema(), 1, MAX_PATCHES),
                    ),
                ),
            )

            add(
                "file_copy", "复制文件",
                "以原始字节复制一个普通文件；目标父目录必须存在。overwrite=false 拒绝已存在目标，" +
                    "overwrite=true 只替换普通文件。复制通过同目录临时文件完成。",
                schema(
                    listOf("sourcePath", "destinationPath", "overwrite"),
                    props(
                        "sourcePath" to stringField("非空源文件路径。"),
                        "destinationPath" to stringField("非空目标路径，不能与源相同。"),
                        "overwrite" to boolField("是否允许替换已存在的普通文件。"),
                    ),
                ),
            )

            add(
                "file_move", "移动或重命名",
                "移动一个普通文件或目录。目录要求目标不存在且不能移动到自身子树；" +
                    "文件 overwrite=true 可替换普通文件。跨文件系统的复制+删除移动不受支持。",
                schema(
                    listOf("sourcePath", "destinationPath", "overwrite"),
                    props(
                        "sourcePath" to stringField("非空源路径。"),
                        "destinationPath" to stringField("非空目标路径，父目录必须存在。"),
                        "overwrite" to boolField("文件是否允许替换现有目标；目录必须为 false。"),
                    ),
                ),
            )

            add(
                "file_delete", "删除单个文件",
                "永久删除一个普通文件；不接受目录、通配符或符号链接，不使用回收站。",
                schema(
                    listOf("path"),
                    props("path" to stringField("非空普通文件路径。")),
                ),
            )

            add(
                "file_create_directory", "创建目录",
                "等价于 mkdir -p：递归创建缺失父目录；已存在目录成功且 changed=false，已存在非目录失败。",
                schema(
                    listOf("path"),
                    props("path" to stringField("非空目录路径。")),
                ),
            )

            add(
                "file_delete_directory", "删除目录",
                "删除一个真实目录。recursive=false 只删除空目录；recursive=true 才允许递归删除，" +
                    "并有节点、时间、深度守卫，不跟随符号链接。root 永远不可删除。",
                schema(
                    listOf("path", "recursive"),
                    props(
                        "path" to stringField("非空目录路径，必须是真实目录。"),
                        "recursive" to boolField("false 只删空目录；true 递归删除整棵树。"),
                    ),
                ),
            )

            add(
                "file_search", "搜索本地文件",
                "在 root 内查找普通文件；名称、大小、修改时间、内容过滤条件按 AND 组合。" +
                    "不跟随符号链接。每页有节点与时间预算，结果显式返回 truncated/stopReason，并给出继续 cursor。",
                schema(
                    listOf(
                        "path", "recursive", "namePattern", "namePatternType", "nameMatchMode",
                        "nameCaseSensitive", "minSize", "maxSize", "minModifiedAt", "maxModifiedAt",
                        "contentPattern", "contentPatternType", "contentCaseSensitive", "cursor", "limit"
                    ),
                    props(
                        "path" to stringField("绝对或相对 root 的目录；用 . 表示 root，空字符串无效。"),
                        "recursive" to boolField("是否递归搜索子目录。"),
                        "namePattern" to stringField("匹配文件名（含扩展名）；空字符串禁用名称过滤，* 不是通配符。", maxLength = MAX_SEARCH_PATTERN_CHARS),
                        "namePatternType" to stringField("名称表达式类型。", enumValues = listOf("literal", "regex")),
                        "nameMatchMode" to stringField("exact 全名匹配；contains 子串或正则查找。", enumValues = listOf("exact", "contains")),
                        "nameCaseSensitive" to boolField("名称是否大小写敏感。"),
                        "minSize" to intField("包含下界字节数；-1 禁用。", -1, MAX_SAFE_INTEGER),
                        "maxSize" to intField("不包含上界字节数；-1 禁用。", -1, MAX_SAFE_INTEGER),
                        "minModifiedAt" to stringField("包含下界修改时间，ISO-8601（Z 或时区偏移，最多毫秒）；空禁用。"),
                        "maxModifiedAt" to stringField("不包含上界修改时间，ISO-8601；空禁用。"),
                        "contentPattern" to stringField("内容匹配表达式；空禁用。", maxLength = MAX_SEARCH_PATTERN_CHARS),
                        "contentPatternType" to stringField("内容表达式类型。", enumValues = listOf("literal", "regex")),
                        "contentCaseSensitive" to boolField("内容是否大小写敏感。"),
                        "cursor" to stringField("第一页传空字符串；续页原样复制上一页 cursor；过滤器必须保持不变。"),
                        "limit" to intField("每页最多返回的匹配数，1..1000。", 1, MAX_SEARCH_LIMIT.toLong(), 200),
                    ),
                ),
            )

            add(
                "file_replace_text", "文本替换",
                "在已有普通文件中按字面量或正则替换，保留编码、BOM 和原有换行。" +
                    "先校验版本、匹配数和替换模板，全部通过后才写临时文件并原子发布。",
                schema(
                    listOf(
                        "path", "targetVersion", "encoding", "contentPattern", "contentPatternType",
                        "contentCaseSensitive", "writeText", "replacementMode", "replaceMode", "expectedReplacements"
                    ),
                    props(
                        "path" to stringField("非空已有普通文件路径。"),
                        "targetVersion" to stringField("当前 64 位小写十六进制 SHA-256；不接受空或 missing。"),
                        "encoding" to stringField("read_text 返回的精确编码令牌（含 BOM 后缀）。"),
                        "contentPattern" to stringField("非空字面量或正则。", maxLength = MAX_SEARCH_PATTERN_CHARS),
                        "contentPatternType" to stringField("表达式类型。", enumValues = listOf("literal", "regex")),
                        "contentCaseSensitive" to boolField("是否大小写敏感。"),
                        "writeText" to stringField("替换文本；空字符串表示删除匹配，不自动加换行。"),
                        "replacementMode" to stringField("literal 原样写入；template 仅正则并支持数字组、花括号数字/命名组与反斜杠转义。", enumValues = listOf("literal", "template")),
                        "replaceMode" to stringField("first 只替换首个非重叠匹配；all 替换全部。", enumValues = listOf("first", "all")),
                        "expectedReplacements" to intField("期望替换数，-1 禁用检查。", -1, MAX_SAFE_INTEGER),
                    ),
                ),
            )

            add(
                "file_truncate_bytes", "截断字节文件",
                "保留已有文件 [0,size) 字节并丢弃尾部；size 不得大于当前大小。通过临时文件原子发布。",
                schema(
                    listOf("path", "targetVersion", "size"),
                    props(
                        "path" to stringField("非空已有普通文件路径。"),
                        "targetVersion" to stringField("当前 SHA-256；不接受空或 missing。"),
                        "size" to intField("最终字节数，0..当前大小。", 0, MAX_SAFE_INTEGER),
                    ),
                ),
            )

            add(
                "file_append_bytes", "追加字节",
                "在已有普通文件 EOF 追加原始十六进制字节；不添加分隔符、换行或 BOM。" +
                    "空 hex 在校验版本后是成功 no-op。",
                schema(
                    listOf("path", "targetVersion", "hex"),
                    props(
                        "path" to stringField("非空已有普通文件路径。"),
                        "targetVersion" to stringField("当前 SHA-256；不接受空或 missing。"),
                        "hex" to stringField("0..1048576 字节，偶数长度十六进制，无分隔符。", maxLength = 2 * MAX_BINARY_WRITE_BYTES),
                    ),
                ),
            )

            add(
                "file_append_text", "追加文本",
                "使用已有文件的确切编码在 EOF 追加文本；保留所有原字节和物理 BOM，" +
                    "不转码、不自动加换行、不展开替换模板。先严格验证整个原文本和追加文本。",
                schema(
                    listOf("path", "targetVersion", "encoding", "writeText"),
                    props(
                        "path" to stringField("非空已有普通文件路径。"),
                        "targetVersion" to stringField("当前 SHA-256；不接受空或 missing。"),
                        "encoding" to stringField("read_text 返回的精确编码令牌（含 BOM 后缀）。"),
                        "writeText" to stringField("要追加的字面量文本，最多 524288 码点/编码字节；空为 no-op。"),
                    ),
                ),
            )

            add(
                "file_search_text", "搜索单个文本文件",
                "按行搜索一个已有文本文件，返回 1 起始行号、匹配行和前后文。" +
                    "cursor 绑定文件路径、SHA-256、编码和全部检索参数，文件变化后必须重开搜索。",
                schema(
                    listOf(
                        "path", "encoding", "pattern", "patternType", "caseSensitive",
                        "contextBefore", "contextAfter", "limit", "maxChars", "cursor"
                    ),
                    props(
                        "path" to stringField("非空已有普通文件路径。"),
                        "encoding" to stringField("首搜可传空字符串自动识别；续搜传解析后的精确令牌。"),
                        "pattern" to stringField("非空字面量或正则（逐行匹配），最多 16384 UTF-16 单元。", maxLength = MAX_SEARCH_PATTERN_CHARS),
                        "patternType" to stringField("表达式类型。", enumValues = listOf("literal", "regex")),
                        "caseSensitive" to boolField("是否大小写敏感。"),
                        "contextBefore" to intField("匹配行前文行数，0..100。", 0, MAX_SEARCH_TEXT_CONTEXT.toLong(), 10),
                        "contextAfter" to intField("匹配行后文行数，0..100。", 0, MAX_SEARCH_TEXT_CONTEXT.toLong(), 20),
                        "limit" to intField("本页最多新匹配行数，1..200。", 1, MAX_SEARCH_TEXT_LIMIT.toLong(), 50),
                        "maxChars" to intField("本页最多返回的原文码点数，1024..131072。", 1024, 131072, 32768),
                        "cursor" to stringField("首搜传空字符串；续搜原样复制上一页 cursor。"),
                    ),
                ),
            )

            add(
                "file_discover_apk", "发现 APK 候选（不依赖扩展名）",
                "按 ZIP 魔数 + APK 内部结构（AndroidManifest.xml，可选 resources.arsc / classes*.dex）发现候选，" +
                    "扩展名只作加分项：.apk.1、.bak、无扩展名、临时缓存名都能命中。扫描有深度/条目/探测数/字节/时间预算，" +
                    "结果显式返回 truncated/skipped/stopReason，部分结果不能当成全部未找到。path 可为目录（扫描）或单个文件（只探测该文件）。",
                schema(
                    listOf("path", "recursive"),
                    props(
                        "path" to stringField("绝对/相对 root 的目录，或单个文件路径；空字符串表示 root。"),
                        "recursive" to boolField("目录扫描时是否递归子目录。", true),
                        "maxDepth" to intField("递归最大深度，0..32。", 0, 32, 8),
                        "maxEntries" to intField("最多访问的文件/目录条目数。", 1, 200000, 20000),
                        "maxProbes" to intField("最多探测的文件数。", 1, 20000, 2000),
                        "maxBytes" to intField("扫描总读取字节预算（只读文件头/中央目录）。", 64, 536870912, 67108864),
                        "maxMillis" to intField("扫描墙钟时间预算（毫秒）。", 50, 30000, 5000),
                        "limit" to intField("最多返回候选数。", 1, 500, 50),
                        "includeZipsWithoutManifest" to boolField("是否把结构完整但缺少 AndroidManifest.xml 的 ZIP 也列为候选（低置信）。", false),
                        "allowOutsideRoot" to boolField("允许只读扫描 MCP 允许目录之外的路径（不会写入、不会切换身份）。", false),
                    ),
                ),
            )

            add(
                "file_access_diagnose", "诊断路径访问（app 与 shizuku 身份）",
                "解释「app UID 得到 ENOENT，Shizuku shell 却能看到」这类问题：返回实际 uid、显式 backend、原始 errno、" +
                    "是否在允许根内、scoped storage 可能性、shizuku 是否可用及本轮是否真的执行过检查。" +
                    "backend 必须显式指定 app 或 shizuku（不静默切换身份）；无法区分不存在/不可见时返回 UNCERTAIN 与 uncertainty，不编造结论。" +
                    "诊断只读；目标在允许目录之外时只报告状态，不会因此获得写权限。",
                schema(
                    listOf("path", "backend"),
                    props(
                        "path" to stringField("要诊断的路径（绝对路径或相对 root；允许目录之外也可只读诊断）。"),
                        "backend" to stringField("显式选择检查身份：app=普通应用 UID；shizuku=Shizuku 授权身份。", enumValues = listOf("app", "shizuku")),
                        "compareWithShizuku" to boolField("backend=app 时是否额外用 shizuku 身份做只读对比（用于区分不存在/不可见）；默认 false。", false),
                    ),
                ),
            )

            return array
        }
    }

    private val rootDir: File = root.canonicalFile
    private val caseInsensitivePaths: Boolean = File.separatorChar == '\\'
    private val definitionsJson: JsonArray by lazy { buildDefinitions() }

    init {
        if (!rootDir.isDirectory) {
            throw IllegalArgumentException("root 必须是已存在的目录：${rootDir.path}")
        }
    }

    /** 返回工具定义副本；调用方可安全修改。 */
    fun definitions(): JsonArray = definitionsJson.deepCopy()

    /**
     * 执行一个 file_* 工具。成功返回普通 payload；参数/边界/I/O 错误直接抛出。
     */
    fun call(name: String, args: JsonObject): JsonObject {
        val result = when (name) {
            "file_get_access_policy" -> accessPolicy()
            "file_list" -> listFiles(args)
            "file_stat" -> stat(args)
            "file_read_text" -> readText(args)
            "file_read_bytes" -> readBytes(args)
            "file_edit_text" -> editText(args)
            "file_write_bytes" -> writeBytes(args)
            "file_patch_bytes" -> patchBytes(args)
            "file_copy" -> copyFile(args)
            "file_move" -> movePath(args)
            "file_delete" -> deleteFile(args)
            "file_create_directory" -> createDirectory(args)
            "file_delete_directory" -> deleteDirectory(args)
            "file_search" -> searchFiles(args)
            "file_replace_text" -> replaceText(args)
            "file_truncate_bytes" -> truncateBytes(args)
            "file_append_bytes" -> appendBytes(args)
            "file_append_text" -> appendText(args)
            "file_search_text" -> searchText(args)
            "file_discover_apk" -> discoverApk(args)
            "file_access_diagnose" -> accessDiagnose(args)
            else -> throw IllegalArgumentException("未知的文件工具：" + name)
        }
        return JsonObject().apply {
            addProperty("ok", true)
            add("data", result.data)
            add("error", JsonNull.INSTANCE)
            add("nextActions", result.nextActions)
        }
    }

    private data class ToolResult(
        val data: JsonObject,
        val nextActions: JsonArray = JsonArray(),
    )

    private data class TextCodec(
        val token: String,
        val charset: Charset,
        val bom: ByteArray,
    )

    private data class Frame(val dir: File, var index: Int)

    private data class LineSpan(val start: Int, val contentEnd: Int, val end: Int)

    // ------------------------------------------------------------------
    // 参数读取
    // ------------------------------------------------------------------

    private fun JsonObject.requiredString(name: String): String {
        val value = get(name) ?: throw IllegalArgumentException("缺少参数 $name")
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
            throw IllegalArgumentException("参数 $name 必须是字符串")
        }
        return value.asString
    }

    private fun JsonObject.optionalString(name: String, fallback: String = ""): String {
        val value = get(name) ?: return fallback
        if (value.isJsonNull) return fallback
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
            throw IllegalArgumentException("参数 $name 必须是字符串")
        }
        return value.asString
    }

    private fun JsonObject.optionalBoolean(name: String, fallback: Boolean): Boolean {
        val value = get(name) ?: return fallback
        if (value.isJsonNull) return fallback
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isBoolean) {
            throw IllegalArgumentException("参数 " + name + " 必须是布尔值")
        }
        return value.asBoolean
    }

    private fun JsonObject.optionalInt(name: String, fallback: Int): Int {
        val value = get(name) ?: return fallback
        if (value.isJsonNull) return fallback
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) {
            throw IllegalArgumentException("参数 " + name + " 必须是整数")
        }
        val number = try {
            value.asLong
        } catch (_: NumberFormatException) {
            throw IllegalArgumentException("参数 " + name + " 必须是整数")
        }
        if (number < Int.MIN_VALUE || number > Int.MAX_VALUE) {
            throw IllegalArgumentException("参数 " + name + " 超出整数范围")
        }
        return number.toInt()
    }

    private fun JsonObject.optionalLong(name: String, fallback: Long): Long {
        val value = get(name) ?: return fallback
        if (value.isJsonNull) return fallback
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) {
            throw IllegalArgumentException("参数 " + name + " 必须是整数")
        }
        return try {
            value.asLong
        } catch (_: NumberFormatException) {
            throw IllegalArgumentException("参数 " + name + " 必须是整数")
        }
    }

    private fun JsonObject.requiredBoolean(name: String): Boolean {
        val value = get(name) ?: throw IllegalArgumentException("缺少参数 $name")
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isBoolean) {
            throw IllegalArgumentException("参数 $name 必须是布尔值")
        }
        return value.asBoolean
    }

    private fun JsonObject.requiredLong(name: String): Long {
        val value = get(name) ?: throw IllegalArgumentException("缺少参数 $name")
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) {
            throw IllegalArgumentException("参数 $name 必须是整数")
        }
        return try {
            value.asLong
        } catch (error: NumberFormatException) {
            throw IllegalArgumentException("参数 $name 必须是整数")
        }
    }

    private fun JsonObject.requiredInt(name: String): Int {
        val value = requiredLong(name)
        if (value < Int.MIN_VALUE || value > Int.MAX_VALUE) {
            throw IllegalArgumentException("参数 $name 超出整数范围")
        }
        return value.toInt()
    }

    private fun JsonObject.requiredObject(name: String): JsonObject {
        val value = get(name) ?: throw IllegalArgumentException("缺少参数 $name")
        if (!value.isJsonObject) throw IllegalArgumentException("参数 $name 必须是对象")
        return value.asJsonObject
    }

    private fun JsonObject.requiredArray(name: String): JsonArray {
        val value = get(name) ?: throw IllegalArgumentException("缺少参数 $name")
        if (!value.isJsonArray) throw IllegalArgumentException("参数 $name 必须是数组")
        return value.asJsonArray
    }

    private fun JsonObject.objString(name: String): String {
        val value = get(name) ?: throw IllegalArgumentException("对象缺少字段 $name")
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
            throw IllegalArgumentException("字段 $name 必须是字符串")
        }
        return value.asString
    }

    private fun JsonObject.objLong(name: String): Long {
        val value = get(name) ?: throw IllegalArgumentException("对象缺少字段 $name")
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) {
            throw IllegalArgumentException("字段 $name 必须是整数")
        }
        return try {
            value.asLong
        } catch (error: NumberFormatException) {
            throw IllegalArgumentException("字段 $name 必须是整数")
        }
    }

    private fun checkRange(value: Long, name: String, minimum: Long, maximum: Long) {
        if (value < minimum || value > maximum) {
            throw IllegalArgumentException("参数 $name 必须在 $minimum..$maximum 之间")
        }
    }

    // ------------------------------------------------------------------
    // 路径与版本
    // ------------------------------------------------------------------

    private fun resolvePath(raw: String, allowEmpty: Boolean): File {
        if (raw.length > 4096) throw IllegalArgumentException("路径过长")
        if (raw.indexOf('\u0000') >= 0) throw IllegalArgumentException("路径包含 NUL 字符")
        if (raw.isEmpty()) {
            if (!allowEmpty) throw IllegalArgumentException("路径不能为空")
            return rootDir
        }
        if (URI_SCHEME.containsMatchIn(raw) && !WINDOWS_DRIVE.containsMatchIn(raw)) {
            throw IllegalArgumentException("不接受 URI 形式的路径：$raw")
        }
        val slashed = raw.replace('\\', '/')
        val candidate = if (slashed.startsWith("/") || File(slashed).isAbsolute) {
            File(slashed)
        } else {
            File(rootDir, slashed)
        }
        val normalized = lexicalNormalize(candidate)
        val rootText = normalizedPath(rootDir)
        val candidateText = normalizedPath(normalized)
        if (!isSameOrChildText(rootText, candidateText)) {
            throw SecurityException("路径超出允许目录：$raw")
        }
        // 不用 canonicalFile 直接当结果（那会跟随链接），而是逐段验证链接后返回词法路径。
        var current = rootDir
        for (segment in relativeSegments(rootText, candidateText)) {
            val next = File(current, segment)
            if (isSymlink(next)) {
                throw SecurityException("不接受符号链接路径：$raw")
            }
            if (!next.exists()) break
            val canonical = try {
                next.canonicalFile
            } catch (error: IOException) {
                throw SecurityException("无法解析路径，已拒绝：$raw")
            }
            if (!samePath(canonical, next)) {
                throw SecurityException("不接受符号链接路径：$raw")
            }
            current = next
        }
        return normalized
    }

    /** 词法归一化，不解析符号链接；`..` 不会越过文件系统根。 */
    private fun lexicalNormalize(file: File): File {
        val absolute = file.absolutePath.replace('\\', '/')
        val drive = if (absolute.length >= 2 && absolute[1] == ':') absolute.substring(0, 2) else ""
        var rest = if (drive.isEmpty()) absolute else absolute.substring(2)
        if (!rest.startsWith("/")) rest = "/$rest"
        val output = ArrayList<String>()
        for (segment in rest.split('/')) {
            when {
                segment.isEmpty() || segment == "." -> Unit
                segment == ".." -> if (output.isNotEmpty()) output.removeAt(output.size - 1)
                else -> output.add(segment)
            }
        }
        val suffix = output.joinToString("/")
        val text = if (drive.isEmpty()) {
            if (suffix.isEmpty()) "/" else "/$suffix"
        } else {
            "$drive/${suffix}"
        }
        return File(text)
    }

    private fun normalizedPath(file: File): String {
        val text = file.path.replace('\\', '/')
        return if (text.length > 1) text.trimEnd('/') else text
    }

    private fun samePath(left: File, right: File): Boolean =
        samePathText(normalizedPath(left), normalizedPath(right))

    private fun samePathText(left: String, right: String): Boolean =
        if (caseInsensitivePaths) left.equals(right, ignoreCase = true) else left == right

    private fun isSameOrChildText(rootText: String, candidateText: String): Boolean {
        val root = if (rootText == "/") "/" else rootText.trimEnd('/')
        if (root == "/") return candidateText.startsWith("/")
        val prefix = "$root/"
        return if (caseInsensitivePaths) {
            candidateText.equals(root, ignoreCase = true) ||
                candidateText.startsWith(prefix, ignoreCase = true)
        } else {
            candidateText == root || candidateText.startsWith(prefix)
        }
    }

    private fun relativeSegments(rootText: String, candidateText: String): List<String> {
        val root = if (rootText == "/") "/" else rootText.trimEnd('/')
        val relative = if (root == "/") {
            candidateText.trimStart('/')
        } else {
            if (candidateText.length <= root.length) return emptyList()
            candidateText.substring(root.length).trimStart('/')
        }
        return if (relative.isEmpty()) emptyList() else relative.split('/').filter { it.isNotEmpty() }
    }

    private data class NioSymlinkProbe(
        val getPath: java.lang.reflect.Method,
        val isSymbolicLink: java.lang.reflect.Method,
    )

    private val nioSymlinkProbe: NioSymlinkProbe? by lazy { createNioSymlinkProbe() }

    private fun createNioSymlinkProbe(): NioSymlinkProbe? = try {
        val filesClass = Class.forName("java.nio.file.Files")
        val pathsClass = Class.forName("java.nio.file.Paths")
        val pathClass = Class.forName("java.nio.file.Path")
        NioSymlinkProbe(
            getPath = pathsClass.getMethod("get", String::class.java, Array<String>::class.java),
            isSymbolicLink = filesClass.getMethod("isSymbolicLink", pathClass),
        )
    } catch (error: ClassNotFoundException) {
        null
    } catch (error: NoSuchMethodException) {
        null
    } catch (error: SecurityException) {
        null
    } catch (error: LinkageError) {
        null
    }

    private fun isSymlink(file: File): Boolean {
        // JVM/API 26+ 可直接用 NIO 判定（包括悬空链接）；API 21-25 上回退到 canonical 比较。
        // 反射调用是为了让本文件保持 minSdk 21 可编译，同时 JVM 单测能覆盖真实符号链接。
        nativeSymlinkCheck(file)?.let { return it }
        if (!file.exists()) return false
        val canonical = try {
            file.canonicalFile
        } catch (error: IOException) {
            throw SecurityException("无法解析路径，已拒绝：${file.path}")
        }
        return !samePath(canonical, file)
    }

    private fun nativeSymlinkCheck(file: File): Boolean? {
        val probe = nioSymlinkProbe ?: return null
        return try {
            val path = probe.getPath.invoke(null, file.absolutePath, emptyArray<String>())
            probe.isSymbolicLink.invoke(null, path) as Boolean
        } catch (error: ReflectiveOperationException) {
            null
        } catch (error: LinkageError) {
            null
        }
    }

    private fun requireExistingDirectory(path: File, label: String = "目录"): File {
        if (!path.exists()) throw IOException("$label 不存在：${path.path}")
        if (isSymlink(path)) throw SecurityException("不接受符号链接：${path.path}")
        if (!path.isDirectory) throw IllegalArgumentException("$label 不是目录：${path.path}")
        return path
    }

    private fun requireExistingFile(path: File, label: String = "文件"): File {
        if (!path.exists()) throw IOException("$label 不存在：${path.path}")
        if (isSymlink(path)) throw SecurityException("不接受符号链接：${path.path}")
        if (path.isDirectory) throw IllegalArgumentException("$label 是目录：${path.path}")
        if (!path.isFile) throw IllegalArgumentException("$label 不是普通文件：${path.path}")
        return path
    }

    private fun requireParentDirectory(path: File): File {
        val parent = path.parentFile ?: throw IOException("路径没有父目录：${path.path}")
        if (!parent.isDirectory) throw IOException("父目录不存在或不是目录：${parent.path}")
        if (isSymlink(parent)) throw SecurityException("不接受符号链接父目录：${parent.path}")
        return parent
    }

    private fun checkVersionToken(value: String, allowMissing: Boolean): String {
        if (allowMissing && value == "missing") return value
        if (value.isEmpty()) throw IllegalArgumentException("targetVersion 不能为空")
        if (!VERSION_TOKEN.matches(value)) {
            throw IllegalArgumentException("targetVersion 必须是 64 位小写十六进制 SHA-256，或 missing")
        }
        return value
    }

    private fun versionConflict(path: File, expected: String): IOException =
        IOException("版本冲突：${path.path} 的 targetVersion 已变化（期望 $expected），请重新读取后再写")

    private fun verifyVersion(path: File, expected: String) {
        if (expected == "missing") {
            if (path.exists()) throw versionConflict(path, expected)
            return
        }
        requireExistingFile(path)
        val current = sha256File(path)
        if (current != expected) throw versionConflict(path, expected)
    }

    // ------------------------------------------------------------------
    // 哈希、十六进制、受限读取
    // ------------------------------------------------------------------

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return hexEncode(digest.digest(), upper = false)
    }

    private fun sha256Bytes(bytes: ByteArray): String =
        hexEncode(MessageDigest.getInstance("SHA-256").digest(bytes), upper = false)

    private fun hexEncode(bytes: ByteArray, upper: Boolean): String {
        val digits = if (upper) "0123456789ABCDEF" else "0123456789abcdef"
        val output = CharArray(bytes.size * 2)
        for (index in bytes.indices) {
            val value = bytes[index].toInt() and 0xFF
            output[index * 2] = digits[value ushr 4]
            output[index * 2 + 1] = digits[value and 0x0F]
        }
        return String(output)
    }

    private fun hexValue(char: Char): Int = when (char) {
        in '0'..'9' -> char - '0'
        in 'a'..'f' -> char - 'a' + 10
        in 'A'..'F' -> char - 'A' + 10
        else -> -1
    }

    private fun parseHex(value: String, field: String, maxBytes: Int, allowEmpty: Boolean): ByteArray {
        if (value.isEmpty()) {
            if (allowEmpty) return ByteArray(0)
            throw IllegalArgumentException("$field 不能为空")
        }
        if (value.length % 2 != 0) throw IllegalArgumentException("$field 必须是偶数长度十六进制")
        val bytes = value.length / 2
        if (bytes > maxBytes) throw IllegalArgumentException("$field 超过 $maxBytes 字节上限")
        val output = ByteArray(bytes)
        for (index in 0 until bytes) {
            val high = hexValue(value[index * 2])
            val low = hexValue(value[index * 2 + 1])
            if (high < 0 || low < 0) throw IllegalArgumentException("$field 包含非十六进制字符")
            output[index] = ((high shl 4) or low).toByte()
        }
        return output
    }

    private fun hexDecodeText(value: String): ByteArray {
        if (value.length % 2 != 0) throw IllegalArgumentException("十六进制文本长度无效")
        val output = ByteArray(value.length / 2)
        for (index in output.indices) {
            val high = hexValue(value[index * 2])
            val low = hexValue(value[index * 2 + 1])
            if (high < 0 || low < 0) throw IllegalArgumentException("十六进制文本包含非法字符")
            output[index] = ((high shl 4) or low).toByte()
        }
        return output
    }

    private fun readBytesCapped(file: File, maxBytes: Int): ByteArray {
        val length = file.length()
        if (length > maxBytes) {
            throw IOException("文件超过 ${maxBytes / (1024 * 1024)} MiB 文本上限：${file.path}")
        }
        val initial = min(length, maxBytes.toLong()).toInt().coerceAtLeast(32)
        val output = ByteArrayOutputStream(initial)
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                total += read
                if (total > maxBytes) {
                    throw IOException("文件读取时超过 ${maxBytes / (1024 * 1024)} MiB 文本上限：${file.path}")
                }
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray()
    }

    private fun copyStream(input: java.io.InputStream, output: OutputStream, limit: Long = -1L): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            if (limit >= 0 && total >= limit) break
            val wanted = if (limit >= 0) min(buffer.size.toLong(), limit - total).toInt() else buffer.size
            if (wanted <= 0) break
            val read = input.read(buffer, 0, wanted)
            if (read < 0) break
            if (read == 0) continue
            output.write(buffer, 0, read)
            total += read
        }
        return total
    }

    // ------------------------------------------------------------------
    // 分页 cursor：绑定 root/路径/版本/过滤条件，校验和防止随手改写
    // ------------------------------------------------------------------

    private fun encodeCursor(body: JsonObject): String {
        body.addProperty("v", CURSOR_FORMAT_VERSION)
        body.addProperty("t", System.currentTimeMillis())
        val json = body.toString()
        val bytes = json.toByteArray(Charsets.UTF_8)
        val checksum = sha256Bytes(json.toByteArray(Charsets.UTF_8)).substring(0, 16)
        return hexEncode(bytes, upper = false) + "." + checksum
    }

    private fun decodeCursor(cursor: String): JsonObject {
        if (cursor.length > MAX_CURSOR_CHARS) throw IllegalArgumentException("cursor 过长")
        val dot = cursor.lastIndexOf('.')
        if (dot <= 0 || dot == cursor.length - 1) throw IllegalArgumentException("cursor 无效")
        val payload = try {
            String(hexDecodeText(cursor.substring(0, dot)), Charsets.UTF_8)
        } catch (error: RuntimeException) {
            throw IllegalArgumentException("cursor 无效")
        }
        val checksum = cursor.substring(dot + 1)
        if (checksum != sha256Bytes(payload.toByteArray(Charsets.UTF_8)).substring(0, 16)) {
            throw IllegalArgumentException("cursor 校验失败")
        }
        val objectValue = try {
            JsonParser().parse(payload)
        } catch (error: RuntimeException) {
            throw IllegalArgumentException("cursor 内容无效")
        }
        if (!objectValue.isJsonObject) throw IllegalArgumentException("cursor 内容无效")
        val body = objectValue.asJsonObject
        if (body.get("v")?.asInt != CURSOR_FORMAT_VERSION) {
            throw IllegalArgumentException("cursor 版本不支持")
        }
        val createdAt = body.get("t")?.asLong ?: throw IllegalArgumentException("cursor 缺少时间")
        val age = System.currentTimeMillis() - createdAt
        if (age < -60000L || age > CURSOR_TTL_MILLIS) {
            throw IllegalArgumentException("cursor 已过期，请从第一页重新开始")
        }
        return body
    }

    private fun cursorStack(body: JsonObject): MutableList<Frame> {
        val stackValue = body.get("st") ?: throw IllegalArgumentException("cursor 缺少遍历状态")
        if (!stackValue.isJsonArray) throw IllegalArgumentException("cursor 遍历状态无效")
        val stack = ArrayList<Frame>()
        for (element in stackValue.asJsonArray) {
            if (!element.isJsonObject) throw IllegalArgumentException("cursor 遍历状态无效")
            val frame = element.asJsonObject
            val pathValue = frame.get("p")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?: throw IllegalArgumentException("cursor 遍历状态无效")
            val indexValue = frame.get("i")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                ?: throw IllegalArgumentException("cursor 遍历状态无效")
            val resolved = resolvePath(pathValue.asString, allowEmpty = false)
            val index = indexValue.asLong
            if (index < 0 || index > Int.MAX_VALUE) throw IllegalArgumentException("cursor 遍历位置无效")
            if (resolved.exists() && !isSymlink(resolved) && resolved.isDirectory) {
                stack.add(Frame(resolved, index.toInt()))
            }
        }
        if (stack.isEmpty()) throw IllegalArgumentException("cursor 已失效，请从第一页重新开始")
        return stack
    }

    private fun encodeStack(stack: List<Frame>): JsonArray = JsonArray().apply {
        stack.forEach { frame ->
            add(JsonObject().apply {
                addProperty("p", frame.dir.path)
                addProperty("i", frame.index)
            })
        }
    }

    private fun hasMoreListWork(stack: MutableList<Frame>): Boolean {
        while (stack.isNotEmpty()) {
            val frame = stack.last()
            val children = try {
                frame.dir.listFiles()
            } catch (error: SecurityException) {
                null
            }
            if (children != null && frame.index < children.size) return true
            stack.removeAt(stack.size - 1)
        }
        return false
    }

    // ------------------------------------------------------------------
    // 文本编码
    // ------------------------------------------------------------------

    private fun codecForToken(token: String): TextCodec {
        return TEXT_CODECS.firstOrNull { it.token == token }
            ?: throw IllegalArgumentException(
                "不支持的 encoding：$token（可用 utf-8、utf-8-bom、utf-16le、utf-16be）"
            )
    }

    private fun detectCodec(bytes: ByteArray): TextCodec {
        for (codec in TEXT_CODECS) {
            if (codec.bom.isNotEmpty() && startsWith(bytes, codec.bom)) return codec
        }
        return TEXT_CODECS[0]
    }

    /**
     * 解析实际使用的 codec：自动检测看 BOM；显式 utf-16le/utf-16be 在没有 BOM 时保留“无 BOM”状态，
     * 这样读取/追加不会凭空加 BOM；创建文件仍按令牌写入声明的 BOM。
     */
    private fun resolveCodecForBytes(bytes: ByteArray, encodingToken: String): TextCodec {
        if (encodingToken.isEmpty()) return detectCodec(bytes)
        val declared = codecForToken(encodingToken)
        if (declared.bom.isNotEmpty() && !startsWith(bytes, declared.bom) &&
            (declared.charset == Charsets.UTF_16LE || declared.charset == Charsets.UTF_16BE)
        ) {
            return declared.copy(bom = ByteArray(0))
        }
        return declared
    }

    private fun startsWith(bytes: ByteArray, prefix: ByteArray): Boolean {
        if (bytes.size < prefix.size) return false
        for (index in prefix.indices) {
            if (bytes[index] != prefix[index]) return false
        }
        return true
    }

    private fun hasForeignBom(bytes: ByteArray, codec: TextCodec): Boolean {
        return TEXT_CODECS.any { other ->
            other.bom.isNotEmpty() && other !== codec && startsWith(bytes, other.bom)
        }
    }

    private fun decodeText(bytes: ByteArray, codec: TextCodec): String {
        if (codec.bom.isNotEmpty()) {
            if (!startsWith(bytes, codec.bom)) {
                throw IllegalArgumentException("encoding=${codec.token} 与文件 BOM 不一致")
            }
        } else if (hasForeignBom(bytes, codec)) {
            throw IllegalArgumentException("encoding=${codec.token} 与文件 BOM 不一致")
        }
        val offset = codec.bom.size
        val decoder = codec.charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
        } catch (error: CharacterCodingException) {
            throw IOException("文件不是有效的 ${codec.token} 文本：${error.message}")
        }
    }

    /**
     * 无 BOM 的编码（当前只有 utf-8）不允许结果文本以 U+FEFF 开头，
     * 否则落盘字节会被下一次读取识别成物理 BOM，编码语义发生变化。
     */
    private fun rejectAccidentalBom(codec: TextCodec, text: String, operation: String) {
        if (codec.bom.isEmpty() && text.startsWith('\uFEFF')) {
            throw IllegalArgumentException(
                "$operation 会让 ${codec.token} 文本以 U+FEFF 开头并形成物理 BOM；" +
                    "请去掉该字符，或使用带 BOM 的编码"
            )
        }
    }

    private fun encodeText(text: String, codec: TextCodec): ByteArray {
        val encoder = codec.charset.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val encoded = try {
            encoder.encode(CharBuffer.wrap(text))
        } catch (error: CharacterCodingException) {
            throw IOException("文本无法用 ${codec.token} 无损编码：${error.message}")
        }
        val body = ByteArray(encoded.remaining())
        encoded.get(body)
        if (codec.bom.isEmpty()) return body
        val output = ByteArray(codec.bom.size + body.size)
        System.arraycopy(codec.bom, 0, output, 0, codec.bom.size)
        System.arraycopy(body, 0, output, codec.bom.size, body.size)
        return output
    }

    private data class DecodedFile(
        val bytes: ByteArray,
        val text: String,
        val codec: TextCodec,
        val version: String,
    )

    private fun readDecodedFile(file: File, encodingToken: String, expectedVersion: String?): DecodedFile {
        val bytes = readBytesCapped(file, MAX_TEXT_BYTES)
        val version = sha256Bytes(bytes)
        if (expectedVersion != null && expectedVersion.isNotEmpty() && expectedVersion != version) {
            throw versionConflict(file, expectedVersion)
        }
        val codec = resolveCodecForBytes(bytes, encodingToken)
        val text = decodeText(bytes, codec)
        val roundTrip = encodeText(text, codec)
        if (!roundTrip.contentEquals(bytes)) {
            throw IOException("文件文本无法按 ${codec.token} 无损重编码，已拒绝写操作：${file.path}")
        }
        return DecodedFile(bytes, text, codec, version)
    }

    // ------------------------------------------------------------------
    // 逻辑行与码点位置
    // ------------------------------------------------------------------

    private fun findLineStartOrNull(text: String, line: Long): Int? {
        if (line < 0) return null
        var index = 0
        var current = 0L
        while (current < line) {
            if (index >= text.length) return null
            while (index < text.length && text[index] != '\r' && text[index] != '\n') index++
            if (index >= text.length) return null
            index = if (text[index] == '\r' && index + 1 < text.length && text[index + 1] == '\n') {
                index + 2
            } else {
                index + 1
            }
            current++
        }
        return index
    }

    private fun lineStartOrThrow(text: String, line: Long): Int =
        findLineStartOrNull(text, line)
            ?: throw IllegalArgumentException("startLine 超出文本范围：$line")

    private fun lineContentEnd(text: String, start: Int): Int {
        var index = start
        while (index < text.length && text[index] != '\r' && text[index] != '\n') index++
        return index
    }

    private fun offsetByCodePointsCapped(text: String, start: Int, maxCodePoints: Int): Int {
        var index = start
        var count = 0
        while (index < text.length && count < maxCodePoints) {
            val codePoint = text.codePointAt(index)
            index += Character.charCount(codePoint)
            count++
        }
        // 不在 CR 与 LF 之间结束。
        if (index > start && index < text.length && text[index - 1] == '\r' && text[index] == '\n') {
            index--
        }
        return index
    }

    private fun positionOf(text: String, target: Int, startLine: Long, startColumn: Long, startIndex: Int): Pair<Long, Long> {
        var index = startIndex
        var line = startLine
        var column = startColumn
        while (index < target) {
            val char = text[index]
            when {
                char == '\r' && index + 1 < target && text[index + 1] == '\n' -> {
                    index += 2
                    line++
                    column = 0
                }
                char == '\r' || char == '\n' -> {
                    index++
                    line++
                    column = 0
                }
                else -> {
                    val codePoint = text.codePointAt(index)
                    index += Character.charCount(codePoint)
                    column++
                }
            }
        }
        return line to column
    }

    private fun countTouchedLines(text: String, start: Int, end: Int): Int {
        if (end <= start) return 0
        var count = 1
        var index = start
        while (index < end) {
            when {
                text[index] == '\r' && index + 1 < end && text[index + 1] == '\n' -> {
                    count++
                    index += 2
                }
                text[index] == '\r' || text[index] == '\n' -> {
                    count++
                    index++
                }
                else -> index++
            }
        }
        if (text[end - 1] == '\r' || text[end - 1] == '\n') count--
        return count
    }

    private fun lineSpans(text: String): List<LineSpan> {
        val spans = ArrayList<LineSpan>()
        var index = 0
        while (index < text.length) {
            val start = index
            while (index < text.length && text[index] != '\r' && text[index] != '\n') index++
            val contentEnd = index
            if (index < text.length) {
                index = if (text[index] == '\r' && index + 1 < text.length && text[index + 1] == '\n') {
                    index + 2
                } else {
                    index + 1
                }
            }
            spans.add(LineSpan(start, contentEnd, index))
            if (spans.size > MAX_SEARCH_TEXT_LINES) {
                throw IOException("文本行数超过 $MAX_SEARCH_TEXT_LINES，暂不支持逐行搜索：${text.length} 字符")
            }
        }
        if (text.isEmpty() || text[text.length - 1] == '\r' || text[text.length - 1] == '\n') {
            spans.add(LineSpan(text.length, text.length, text.length))
        }
        if (spans.isEmpty()) spans.add(LineSpan(0, 0, 0))
        return spans
    }

    private fun takeCodePoints(text: String, maxCodePoints: Int): String {
        if (maxCodePoints <= 0 || text.isEmpty()) return ""
        val count = text.codePointCount(0, text.length)
        if (count <= maxCodePoints) return text
        val end = text.offsetByCodePoints(0, maxCodePoints)
        return text.substring(0, end)
    }

    // ------------------------------------------------------------------
    // ISO-8601 时间（毫秒精度，不依赖 java.time，兼容 minSdk 21）
    // ------------------------------------------------------------------

    private fun parseIsoMillis(value: String, field: String): Long {
        if (value.isEmpty()) return -1L
        if (value.length > 64) throw IllegalArgumentException("$field 时间格式无效")
        val match = ISO_TIME.matchEntire(value)
            ?: throw IllegalArgumentException("$field 必须是 ISO-8601（Z 或 ±HH:MM）")
        return try {
            val year = match.groupValues[1].toInt()
            val month = match.groupValues[2].toInt()
            val day = match.groupValues[3].toInt()
            val hour = match.groupValues[4].toInt()
            val minute = match.groupValues[5].toInt()
            val second = match.groupValues[6].toInt()
            val fraction = match.groupValues[7]
            val millis = if (fraction.isEmpty()) 0 else fraction.padEnd(3, '0').toInt()
            val zone = match.groupValues[8]
            val offsetMillis = if (zone == "Z") {
                0
            } else {
                val sign = if (zone[0] == '-') -1 else 1
                val hours = zone.substring(1, 3).toInt()
                val minutes = zone.substring(4, 6).toInt()
                if (hours > 23 || minutes > 59) throw IllegalArgumentException("$field 时区偏移无效")
                sign * (hours * 60 + minutes) * 60 * 1000
            }
            val calendar = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
                isLenient = false
                clear()
                set(year, month - 1, day, hour, minute, second)
                set(Calendar.MILLISECOND, millis)
            }
            calendar.timeInMillis - offsetMillis
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("$field 时间无效：$value")
        } catch (error: IndexOutOfBoundsException) {
            throw IllegalArgumentException("$field 时间无效：$value")
        }
    }

    // ------------------------------------------------------------------
    // 原子写入：同目录临时文件 + 版本复核 + 原子替换/备份回滚
    // ------------------------------------------------------------------

    /**
     * 把 [writer] 生成的字节写入同目录临时文件，校验版本后发布到 [target]。
     *
     * @param expectedVersion null 表示不检查版本（复制）；否则必须是 64 位 SHA-256。
     * @param mustBeMissing true 表示只允许创建，目标已存在即版本冲突。
     * @return 新文件内容的 SHA-256。
     */
    private fun transactionalWrite(
        target: File,
        expectedVersion: String?,
        mustBeMissing: Boolean,
        writer: (OutputStream) -> Unit,
    ): String {
        requireParentDirectory(target)
        if (mustBeMissing) {
            if (target.exists()) throw versionConflict(target, "missing")
        } else {
            if (target.exists()) {
                if (isSymlink(target)) throw SecurityException("不接受符号链接目标：${target.path}")
                if (!target.isFile) throw IllegalArgumentException("目标不是普通文件：${target.path}")
            }
            if (expectedVersion != null) verifyVersion(target, expectedVersion)
        }
        val parent = target.parentFile ?: throw IOException("目标没有父目录")
        val temp = File.createTempFile(".aijspro-", ".tmp", parent)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileOutputStream(temp).use { fileOutput ->
                val buffered = BufferedOutputStream(DigestOutputStream(fileOutput, digest), 64 * 1024)
                writer(buffered)
                buffered.flush()
                fileOutput.fd.sync()
            }
            // 发布前再次校验，缩小并发修改窗口。
            if (mustBeMissing) {
                if (target.exists()) throw versionConflict(target, "missing")
            } else if (expectedVersion != null) {
                if (!target.isFile) throw versionConflict(target, expectedVersion)
                if (sha256File(target) != expectedVersion) throw versionConflict(target, expectedVersion)
            }
            if (target.exists()) {
                if (isSymlink(target)) throw SecurityException("不接受符号链接目标：${target.path}")
                if (!target.isFile) throw IllegalArgumentException("目标不是普通文件：${target.path}")
            }
            publishTemp(temp, target)
            return hexEncode(digest.digest(), upper = false)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun publishTemp(temp: File, target: File) {
        if (temp.renameTo(target)) return
        if (!target.exists()) throw IOException("无法发布临时文件到：${target.path}")
        if (!target.isFile) throw IllegalArgumentException("目标不是普通文件：${target.path}")
        val backup = File(target.parentFile, ".aijspro-backup-${System.nanoTime()}-${Thread.currentThread().id}")
        if (!target.renameTo(backup)) throw IOException("无法替换目标文件：${target.path}")
        try {
            if (!temp.renameTo(target)) throw IOException("无法发布临时文件到：${target.path}")
            backup.delete()
        } catch (error: Exception) {
            if (!backup.renameTo(target)) {
                error.addSuppressed(IOException("回滚失败，原文件保留在：${backup.path}"))
            }
            throw error
        }
    }

    // ------------------------------------------------------------------
    // 文件工具实现
    // ------------------------------------------------------------------

    private fun accessPolicy(): ToolResult {
        val data = JsonObject().apply {
            addProperty("root", rootDir.path)
            addProperty("home", rootDir.path)
            addProperty("homePath", rootDir.path)
            addProperty("access", "read_write")
            addProperty("readable", true)
            addProperty("writable", true)
            addProperty("pathRule", "绝对路径或相对 root 的路径；canonical 必须落在 root 内，逐段拒绝符号链接。")
            add("rules", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("path", rootDir.path)
                    addProperty("targetType", "root")
                    addProperty("access", "read_write")
                    addProperty("active", true)
                })
            })
            add("denied", JsonArray())
            add("notes", JsonArray().apply {
                add("空路径只允许 file_list 与 file_get_access_policy。")
                add("不允许删除 root；递归删除必须显式 recursive=true。")
                add("所有写入都会校验 targetVersion，并先写同目录临时文件。")
            })
        }
        return ToolResult(data)
    }

    private fun listFiles(args: JsonObject): ToolResult {
        val rawPath = args.requiredString("path")
        val recursive = args.requiredBoolean("recursive")
        val cursor = args.requiredString("cursor")
        val limit = args.requiredInt("limit")
        checkRange(limit.toLong(), "limit", 1, MAX_LIST_LIMIT.toLong())

        val directory = requireExistingDirectory(resolvePath(rawPath, allowEmpty = true))
        val stack = if (cursor.isEmpty()) {
            mutableListOf(Frame(directory, 0))
        } else {
            val body = decodeCursor(cursor)
            if (body.get("k")?.asString != "list") throw IllegalArgumentException("cursor 不属于 file_list")
            if (!samePathText(body.get("root")?.asString ?: "", normalizedPath(rootDir))) {
                throw IllegalArgumentException("cursor 不属于当前 root")
            }
            if (!samePathText(body.get("d")?.asString ?: "", normalizedPath(directory))) {
                throw IllegalArgumentException("cursor 与当前目录不匹配，不能跨目录复用")
            }
            if (body.get("r")?.asBoolean != recursive) {
                throw IllegalArgumentException("cursor 与 recursive 参数不匹配")
            }
            if (body.get("l")?.asLong != limit.toLong()) {
                throw IllegalArgumentException("cursor 与 limit 参数不匹配")
            }
            cursorStack(body)
        }

        val entries = JsonArray()
        val warnings = ArrayList<String>()
        var count = 0
        while (count < limit && stack.isNotEmpty()) {
            val frame = stack.last()
            val children = try {
                frame.dir.listFiles()
            } catch (error: SecurityException) {
                addWarning(warnings, "无法读取目录：${frame.dir.path}")
                null
            }
            if (children == null) {
                addWarning(warnings, "无法读取目录：${frame.dir.path}")
                stack.removeAt(stack.size - 1)
                continue
            }
            val ordered = children.sortedBy { it.name }
            if (frame.index >= ordered.size) {
                stack.removeAt(stack.size - 1)
                continue
            }
            val child = ordered[frame.index]
            frame.index++
            val link = try {
                isSymlink(child)
            } catch (error: SecurityException) {
                addWarning(warnings, "无法判断路径类型：${child.path}")
                true
            }
            entries.add(entryJson(child, link))
            count++
            if (!link && child.isDirectory && recursive) {
                stack.add(Frame(child, 0))
            }
        }

        val more = count >= limit && hasMoreListWork(stack)
        val nextCursor = if (more) {
            encodeCursor(JsonObject().apply {
                addProperty("k", "list")
                addProperty("root", normalizedPath(rootDir))
                addProperty("d", normalizedPath(directory))
                addProperty("r", recursive)
                addProperty("l", limit)
                add("st", encodeStack(stack))
            })
        } else {
            null
        }

        val data = JsonObject().apply {
            addProperty("directoryPath", directory.path)
            addProperty("recursive", recursive)
            addProperty("limit", limit)
            add("entries", entries)
            addProperty("count", count)
            addProperty("truncated", more || warnings.isNotEmpty())
            if (nextCursor == null) add("nextCursor", JsonNull.INSTANCE) else addProperty("nextCursor", nextCursor)
            add("warnings", stringArray(warnings))
        }
        val actions = JsonArray()
        if (nextCursor != null) {
            actions.add(nextAction("file_list", "继续列出目录", JsonObject().apply {
                addProperty("path", directory.path)
                addProperty("recursive", recursive)
                addProperty("cursor", nextCursor)
                addProperty("limit", limit)
            }))
        }
        return ToolResult(data, actions)
    }

    private fun entryJson(file: File, link: Boolean): JsonObject {
        val type = when {
            link -> "symbolic_link"
            file.isDirectory -> "directory"
            file.isFile -> "file"
            else -> "other"
        }
        return JsonObject().apply {
            addProperty("name", file.name)
            addProperty("path", file.path)
            addProperty("relativePath", relativePath(file))
            addProperty("type", type)
            addProperty("size", if (type == "file") file.length() else 0L)
            addProperty("modifiedTime", file.lastModified())
        }
    }

    private fun relativePath(file: File): String {
        val rootText = normalizedPath(rootDir)
        val fileText = normalizedPath(file)
        val root = if (rootText == "/") "/" else rootText.trimEnd('/')
        val relative = if (root == "/") {
            fileText.trimStart('/')
        } else {
            if (fileText.length <= root.length) "" else fileText.substring(root.length).trimStart('/')
        }
        return relative
    }

    private fun stat(args: JsonObject): ToolResult {
        val path = resolvePath(args.requiredString("path"), allowEmpty = false)
        val exists = path.exists()
        if (exists && isSymlink(path)) {
            throw SecurityException("不接受符号链接：${path.path}")
        }
        val type = when {
            !exists -> "missing"
            path.isDirectory -> "directory"
            path.isFile -> "file"
            else -> "other"
        }
        val data = JsonObject().apply {
            addProperty("path", path.path)
            addProperty("relativePath", relativePath(path))
            addProperty("exists", exists)
            addProperty("type", type)
            addProperty("size", if (type == "file") path.length() else 0L)
            addProperty("modifiedTime", if (exists) path.lastModified() else 0L)
            addProperty("readable", exists && path.canRead())
            addProperty("writable", exists && path.canWrite())
            when (type) {
                "file" -> addProperty("targetVersion", sha256File(path))
                "missing" -> addProperty("targetVersion", "missing")
                else -> add("targetVersion", JsonNull.INSTANCE)
            }
        }
        if (type == "file") {
            data.add("apkProbe", probeJsonSafe(path))
        }
        return ToolResult(data)
    }

    private fun readBytes(args: JsonObject): ToolResult {
        val path = requireExistingFile(resolvePath(args.requiredString("path"), allowEmpty = false))
        val targetVersion = args.requiredString("targetVersion")
        val byteOffset = args.requiredLong("byteOffset")
        val maxBytes = args.requiredInt("maxBytes")
        checkRange(byteOffset, "byteOffset", 0, MAX_SAFE_INTEGER)
        checkRange(maxBytes.toLong(), "maxBytes", 1, MAX_BYTE_PAGE.toLong())
        if (targetVersion.isNotEmpty()) checkVersionToken(targetVersion, allowMissing = false)

        val size = path.length()
        if (byteOffset > size) {
            throw IllegalArgumentException("byteOffset 超出文件大小：$byteOffset > $size")
        }
        val version = sha256File(path)
        if (targetVersion.isNotEmpty() && targetVersion != version) {
            throw versionConflict(path, targetVersion)
        }
        val wanted = min(maxBytes.toLong(), size - byteOffset).toInt()
        val bytes = ByteArray(wanted)
        if (wanted > 0) {
            RandomAccessFile(path, "r").use { input ->
                input.seek(byteOffset)
                var read = 0
                while (read < wanted) {
                    val count = input.read(bytes, read, wanted - read)
                    if (count < 0) break
                    read += count
                }
                if (read != wanted) {
                    throw IOException("读取字节时文件发生了变化：${path.path}")
                }
            }
        }
        val eof = byteOffset + wanted >= size
        val data = JsonObject().apply {
            addProperty("path", path.path)
            addProperty("targetVersion", version)
            addProperty("size", size)
            addProperty("byteOffset", byteOffset)
            addProperty("bytesRead", wanted)
            addProperty("hex", hexEncode(bytes, upper = true))
            addProperty("eof", eof)
        }
        val actions = JsonArray()
        if (!eof) {
            actions.add(nextAction("file_read_bytes", "继续读取字节", JsonObject().apply {
                addProperty("path", path.path)
                addProperty("targetVersion", version)
                addProperty("byteOffset", byteOffset + wanted)
                addProperty("maxBytes", maxBytes)
            }))
        }
        return ToolResult(data, actions)
    }

    private fun readText(args: JsonObject): ToolResult {
        val path = requireExistingFile(resolvePath(args.requiredString("path"), allowEmpty = false))
        val encodingToken = args.requiredString("encoding")
        val targetVersion = args.requiredString("targetVersion")
        val limit = args.requiredInt("limit")
        val maxChars = args.requiredInt("maxChars")
        val startLine = args.requiredLong("startLine")
        val startColumn = args.requiredLong("startColumn")
        checkRange(limit.toLong(), "limit", 1, 2000)
        checkRange(maxChars.toLong(), "maxChars", 2, 131072)
        checkRange(startLine, "startLine", 0, MAX_SAFE_INTEGER)
        checkRange(startColumn, "startColumn", 0, MAX_SAFE_INTEGER)
        if (targetVersion.isNotEmpty()) checkVersionToken(targetVersion, allowMissing = false)

        val decoded = readDecodedFile(path, encodingToken, targetVersion.ifEmpty { null })
        val text = decoded.text
        val lineStart = lineStartOrThrow(text, startLine)
        val contentEnd = lineContentEnd(text, lineStart)
        val lineCodePoints = text.codePointCount(lineStart, contentEnd)
        if (startColumn > lineCodePoints.toLong()) {
            throw IllegalArgumentException("startColumn 超出该行长度：$startColumn > $lineCodePoints")
        }
        val startIndex = text.offsetByCodePoints(lineStart, startColumn.toInt())
        val lineLimitEnd = findLineStartOrNull(text, startLine + limit) ?: text.length
        val maxEnd = offsetByCodePointsCapped(text, startIndex, maxChars)
        val end = min(lineLimitEnd, maxEnd).coerceAtLeast(startIndex)
        val (endLine, endColumn) = positionOf(text, end, startLine, startColumn, startIndex)
        val hasMore = end < text.length
        val page = text.substring(startIndex, end)
        val data = JsonObject().apply {
            addProperty("path", path.path)
            addProperty("encoding", decoded.codec.token)
            addProperty("targetVersion", decoded.version)
            addProperty("fileBytes", decoded.bytes.size)
            addProperty("startLine", startLine)
            addProperty("startColumn", startColumn)
            addProperty("endLine", endLine)
            addProperty("endColumn", endColumn)
            addProperty("linesTouched", countTouchedLines(text, startIndex, end))
            addProperty("codePoints", text.codePointCount(startIndex, end))
            addProperty("text", page)
            addProperty("eof", !hasMore)
            addProperty("hasMore", hasMore)
        }
        val actions = JsonArray()
        if (hasMore) {
            actions.add(nextAction("file_read_text", "继续读取文本", JsonObject().apply {
                addProperty("path", path.path)
                addProperty("encoding", decoded.codec.token)
                addProperty("targetVersion", decoded.version)
                addProperty("limit", limit)
                addProperty("maxChars", maxChars)
                addProperty("startLine", endLine)
                addProperty("startColumn", endColumn)
            }))
        }
        return ToolResult(data, actions)
    }

    private fun nextAction(tool: String, title: String, arguments: JsonObject): JsonObject =
        JsonObject().apply {
            addProperty("tool", tool)
            addProperty("purpose", "continue")
            addProperty("title", title)
            addProperty("description", title)
            add("arguments", arguments)
        }

    /**
     * file_discover_apk：按结构发现候选（不依赖扩展名）。
     * path 为文件时只探测该文件；为目录时按扫描预算递归/单层发现，结果带 truncated/skipped/stopReason。
     */
    private fun discoverApk(args: JsonObject): ToolResult {
        val rawPath = args.requiredString("path")
        val recursive = args.requiredBoolean("recursive")
        val maxDepth = args.optionalInt("maxDepth", 8)
        val maxEntries = args.optionalInt("maxEntries", 20000)
        val maxProbes = args.optionalInt("maxProbes", 2000)
        val maxBytes = args.optionalLong("maxBytes", 64L * 1024 * 1024)
        val maxMillis = args.optionalLong("maxMillis", 5000L)
        val limit = args.optionalInt("limit", 50)
        val includeZips = args.optionalBoolean("includeZipsWithoutManifest", false)
        val allowOutsideRoot = args.optionalBoolean("allowOutsideRoot", false)
        checkRange(maxDepth.toLong(), "maxDepth", 0, 32)
        checkRange(maxEntries.toLong(), "maxEntries", 1, 200000)
        checkRange(maxProbes.toLong(), "maxProbes", 1, 20000)
        checkRange(maxBytes, "maxBytes", 64, 512L * 1024 * 1024)
        checkRange(maxMillis, "maxMillis", 50, 30000)
        checkRange(limit.toLong(), "limit", 1, 500)

        val start: File
        var outside = false
        if (allowOutsideRoot) {
            val info = ToolkitPathDiagnostics.resolveRequested(rootDir, rawPath)
            start = info.path
            outside = !info.insideAllowedRoot
        } else {
            start = resolvePath(rawPath, allowEmpty = true)
        }
        if (!start.exists()) throw IOException("路径不存在：" + start.path)
        val actions = JsonArray()
        if (start.isDirectory) {
            val limits = ToolkitDiscovery.Limits(
                maxDepth = if (recursive) maxDepth else 0,
                maxEntries = maxEntries,
                maxProbes = maxProbes,
                maxBytes = maxBytes,
                maxMillis = maxMillis,
                limit = limit,
            )
            val scan = ToolkitDiscovery.scan(start, limits, includeZips)
            val data = ToolkitDiscovery.scanJson(scan)
            data.addProperty("mode", "directory")
            data.addProperty("recursive", recursive)
            data.addProperty("outsideAllowedRoot", outside)
            val notes = JsonArray()
            notes.add("扩展名只作加分项；候选判定来自 ZIP 魔数与 AndroidManifest.xml 等内部结构。")
            if (scan.truncated) {
                notes.add("扫描被预算截断或存在跳过项：truncated=true，不能把结果当成全部未找到。")
            }
            if (outside) {
                notes.add("目标在 MCP 允许目录之外：本次只读扫描，不会写入或改变权限；操作请使用终端并显式指定路径。")
            }
            data.add("notes", notes)
            if (scan.candidates.isNotEmpty()) {
                val best = scan.candidates.first()
                actions.add(nextAction("artifact_open", "打开最可能的 APK 候选", JsonObject().apply {
                    addProperty("path", best.path)
                }))
                actions.add(nextAction("file_stat", "查看候选的 APK 结构探测", JsonObject().apply {
                    addProperty("path", best.path)
                }))
            }
            if (scan.truncated) {
                actions.add(nextAction("file_discover_apk", "扩大预算继续扫描", JsonObject().apply {
                    addProperty("path", rawPath)
                    addProperty("recursive", recursive)
                    addProperty("maxDepth", maxDepth)
                    addProperty("maxEntries", minOf(maxEntries * 2, 200000))
                    addProperty("maxProbes", minOf(maxProbes * 2, 20000))
                    addProperty("maxBytes", minOf(maxBytes * 2, 512L * 1024 * 1024))
                    addProperty("maxMillis", minOf(maxMillis * 2, 30000L))
                    addProperty("limit", minOf(limit * 2, 500))
                    addProperty("includeZipsWithoutManifest", includeZips)
                    addProperty("allowOutsideRoot", allowOutsideRoot)
                }))
            }
            return ToolResult(data, actions)
        }
        if (!start.isFile) throw IllegalArgumentException("只支持普通文件或目录：" + start.path)
        val probeBudget = minOf(ToolkitDiscovery.MAX_PROBE_BYTES, maxBytes)
        val probe = ToolkitDiscovery.probe(start, probeBudget)
        val data = JsonObject().apply {
            addProperty("mode", "file")
            addProperty("path", start.path)
            addProperty("outsideAllowedRoot", outside)
            addProperty("candidate", probe.candidate)
            addProperty("truncated", probe.truncated)
            addProperty("stopReason", probe.stopReason)
            add("probe", ToolkitDiscovery.probeJson(probe))
        }
        if (probe.candidate) {
            actions.add(nextAction("artifact_open", "打开探测到的 APK 候选", JsonObject().apply {
                addProperty("path", start.path)
            }))
        }
        return ToolResult(data, actions)
    }

    /**
     * file_access_diagnose：把参数交给后端（AndroidToolkitRuntime 安装），这里只做参数校验与 nextActions。
     * backend 缺失或非法时直接报错：不得静默选择另一权限身份。
     */
    private fun accessDiagnose(args: JsonObject): ToolResult {
        val rawPath = args.requiredString("path")
        val backend = args.optionalString("backend", "")
        if (backend != ToolkitPathDiagnostics.BACKEND_APP && backend != ToolkitPathDiagnostics.BACKEND_SHIZUKU) {
            throw IllegalArgumentException("backend 必须显式指定 app 或 shizuku（不会静默切换权限身份）")
        }
        val compare = args.optionalBoolean("compareWithShizuku", false)
        val provider = ToolkitPathDiagnostics.activeProvider()
            ?: throw ToolkitException(
                "BACKEND_UNAVAILABLE",
                "路径访问诊断需要 Android 工具进程后端；当前进程没有安装诊断后端",
                "path",
            )
        val data = provider.invoke(rootDir, rawPath, backend, compare)
        val actions = JsonArray()
        val conclusionElement = data.get("conclusion")
        val conclusion = if (conclusionElement != null && conclusionElement.isJsonPrimitive) {
            conclusionElement.asString
        } else {
            ""
        }
        val insideElement = data.get("insideAllowedRoot")
        val inside = if (insideElement != null && insideElement.isJsonPrimitive) {
            insideElement.asBoolean
        } else {
            true
        }
        if (conclusion == ToolkitPathDiagnostics.INVISIBLE_TO_APP) {
            val pathElement = data.get("path")
            val pathText = if (pathElement != null && pathElement.isJsonPrimitive) pathElement.asString else rawPath
            actions.add(nextAction("terminal_exec", "用 shizuku 后端查看该路径", JsonObject().apply {
                addProperty("command", "ls -ld -- " + ToolkitPathDiagnostics.shellQuote(pathText))
                addProperty("backend", ToolkitPathDiagnostics.BACKEND_SHIZUKU)
            }))
        }
        if (conclusion == ToolkitPathDiagnostics.UNCERTAIN && !compare) {
            actions.add(nextAction("file_access_diagnose", "加 compareWithShizuku 再检查", JsonObject().apply {
                addProperty("path", rawPath)
                addProperty("backend", backend)
                addProperty("compareWithShizuku", true)
            }))
        }
        if (!inside) {
            actions.add(nextAction("file_access_diagnose", "确认允许目录边界", JsonObject().apply {
                addProperty("path", rootDir.path)
                addProperty("backend", ToolkitPathDiagnostics.BACKEND_APP)
            }))
        }
        return ToolResult(data, actions)
    }

    /** file_stat 的附加结构探测；失败只降级为字段错误，不影响 stat 本身。 */
    private fun probeJsonSafe(file: File): JsonObject = try {
        ToolkitDiscovery.probeJson(ToolkitDiscovery.probe(file))
    } catch (error: IOException) {
        JsonObject().apply {
            addProperty("error", error.message ?: "APK 结构探测失败")
            addProperty("candidate", false)
        }
    }

    private fun addWarning(warnings: MutableList<String>, message: String) {
        if (warnings.size < MAX_SEARCH_WARNINGS) warnings.add(message)
    }

    private fun stringArray(values: List<String>): JsonArray = JsonArray().apply {
        values.forEach { add(it) }
    }

    private data class EditSpec(val mode: String, val matchText: String, val writeText: String)

    private fun parseEdits(array: JsonArray): List<EditSpec> {
        if (array.size() < 1 || array.size() > MAX_EDIT_ITEMS) {
            throw IllegalArgumentException("edits 数量必须在 1..$MAX_EDIT_ITEMS")
        }
        val result = ArrayList<EditSpec>(array.size())
        var totalCodePoints = 0L
        for (element in array) {
            if (!element.isJsonObject) throw IllegalArgumentException("edits 的每一项必须是对象")
            val item = element.asJsonObject
            val mode = item.objString("mode")
            if (mode !in setOf("write_target", "replace_match", "insert_before_match", "insert_after_match")) {
                throw IllegalArgumentException("不支持的编辑模式：$mode")
            }
            val matchText = item.objString("matchText")
            val writeText = item.objString("writeText")
            if (mode == "write_target" && matchText.isNotEmpty()) {
                throw IllegalArgumentException("write_target 的 matchText 必须为空")
            }
            if (mode != "write_target" && matchText.isEmpty()) {
                throw IllegalArgumentException("$mode 的 matchText 不能为空")
            }
            totalCodePoints += textCodePoints(matchText).toLong()
            totalCodePoints += textCodePoints(writeText).toLong()
            if (totalCodePoints > MAX_EDIT_TEXT_CODE_POINTS) {
                throw IllegalArgumentException("edits 的 matchText + writeText 超过 $MAX_EDIT_TEXT_CODE_POINTS 码点")
            }
            result.add(EditSpec(mode, matchText, writeText))
        }
        return result
    }

    private fun textCodePoints(value: String): Int = value.codePointCount(0, value.length)

    private fun applyTextEdits(original: String, edits: List<EditSpec>): String {
        if (edits.size == 1 && edits[0].mode == "write_target") {
            if (edits[0].matchText.isNotEmpty()) throw IllegalArgumentException("write_target 的 matchText 必须为空")
            return edits[0].writeText
        }
        if (edits.any { it.mode == "write_target" }) {
            throw IllegalArgumentException("write_target 必须是 edits 中唯一的编辑")
        }
        data class LocatedEdit(val start: Int, val end: Int, val edit: EditSpec)

        val located = ArrayList<LocatedEdit>(edits.size)
        for (edit in edits) {
            if (edit.matchText.isEmpty()) throw IllegalArgumentException("匹配编辑的 matchText 不能为空")
            var found = -1
            var count = 0
            var searchFrom = 0
            while (searchFrom <= original.length) {
                val index = original.indexOf(edit.matchText, searchFrom)
                if (index < 0) break
                count++
                if (count > 1) break
                found = index
                searchFrom = index + edit.matchText.length
            }
            if (count != 1) {
                throw IllegalArgumentException("matchText 必须原文中恰好出现一次，实际 $count 次：${edit.matchText.take(40)}")
            }
            if (edit.mode == "write_target") throw IllegalArgumentException("write_target 只能单独使用")
            located.add(LocatedEdit(found, found + edit.matchText.length, edit))
        }
        located.sortBy { it.start }
        for (index in 1 until located.size) {
            if (located[index].start < located[index - 1].end) {
                throw IllegalArgumentException("编辑匹配范围不能重叠")
            }
        }
        val builder = StringBuilder(original)
        for (item in located.asReversed()) {
            when (item.edit.mode) {
                "replace_match" -> builder.replace(item.start, item.end, item.edit.writeText)
                "insert_before_match" -> builder.insert(item.start, item.edit.writeText)
                "insert_after_match" -> builder.insert(item.end, item.edit.writeText)
                else -> throw IllegalArgumentException("不支持的编辑模式：${item.edit.mode}")
            }
        }
        return builder.toString()
    }

    private fun editText(args: JsonObject): ToolResult {
        val path = resolvePath(args.requiredString("path"), allowEmpty = false)
        val targetVersion = args.requiredString("targetVersion")
        val encoding = args.requiredString("encoding")
        val edits = parseEdits(args.requiredArray("edits"))
        val creating = targetVersion == "missing"
        if (!creating) checkVersionToken(targetVersion, allowMissing = false)
        if (encoding.isEmpty()) {
            throw IllegalArgumentException("encoding 不能为空；已有文件请复制 read_text 的令牌")
        }
        val codec = codecForToken(encoding)

        if (creating) {
            if (path.exists()) throw versionConflict(path, "missing")
            if (edits.size != 1 || edits[0].mode != "write_target") {
                throw IllegalArgumentException("创建文件时 edits 必须只包含一个 write_target")
            }
            rejectAccidentalBom(codec, edits[0].writeText, "创建文件")
            val output = encodeText(edits[0].writeText, codec)
            if (output.size > MAX_TEXT_BYTES) throw IllegalArgumentException("编码结果超过 10 MiB")
            val newVersion = transactionalWrite(path, null, mustBeMissing = true) { it.write(output) }
            val data = JsonObject().apply {
                addProperty("path", path.path)
                addProperty("changed", true)
                addProperty("created", true)
                addProperty("encoding", codec.token)
                addProperty("bytesWritten", output.size)
                addProperty("editsApplied", 1)
                addProperty("targetVersion", newVersion)
            }
            return ToolResult(data)
        }

        if (!path.exists()) throw versionConflict(path, targetVersion)
        val decoded = readDecodedFile(path, encoding, targetVersion)
        val outputText = applyTextEdits(decoded.text, edits)
        rejectAccidentalBom(codec, outputText, "编辑文本")
        val output = encodeText(outputText, codec)
        if (output.size > MAX_TEXT_BYTES) throw IllegalArgumentException("编码结果超过 10 MiB")
        if (output.contentEquals(decoded.bytes)) {
            val data = JsonObject().apply {
                addProperty("path", path.path)
                addProperty("changed", false)
                addProperty("created", false)
                addProperty("encoding", codec.token)
                addProperty("bytesWritten", decoded.bytes.size)
                addProperty("editsApplied", edits.size)
                addProperty("targetVersion", decoded.version)
            }
            return ToolResult(data)
        }
        val newVersion = transactionalWrite(path, targetVersion, mustBeMissing = false) { it.write(output) }
        val data = JsonObject().apply {
            addProperty("path", path.path)
            addProperty("changed", true)
            addProperty("created", false)
            addProperty("encoding", codec.token)
            addProperty("bytesWritten", output.size)
            addProperty("editsApplied", edits.size)
            addProperty("targetVersion", newVersion)
        }
        return ToolResult(data)
    }

    private fun writeBytes(args: JsonObject): ToolResult {
        val path = resolvePath(args.requiredString("path"), allowEmpty = false)
        val targetVersion = args.requiredString("targetVersion")
        val bytes = parseHex(args.requiredString("hex"), "hex", MAX_BINARY_WRITE_BYTES, allowEmpty = true)
        val creating = targetVersion == "missing"
        if (!creating) {
            checkVersionToken(targetVersion, allowMissing = false)
            if (!path.exists()) throw versionConflict(path, targetVersion)
        } else if (path.exists()) {
            throw versionConflict(path, "missing")
        }
        val newVersion = transactionalWrite(
            path,
            if (creating) null else targetVersion,
            mustBeMissing = creating,
        ) { output -> output.write(bytes) }
        val data = JsonObject().apply {
            addProperty("path", path.path)
            addProperty("created", creating)
            addProperty("changed", true)
            addProperty("bytesWritten", bytes.size)
            addProperty("targetVersion", newVersion)
        }
        return ToolResult(data)
    }

    private data class BytePatch(
        val offset: Long,
        val expected: ByteArray,
        val replacement: ByteArray,
    )

    private fun patchBytes(args: JsonObject): ToolResult {
        val path = requireExistingFile(resolvePath(args.requiredString("path"), allowEmpty = false))
        val targetVersion = args.requiredString("targetVersion")
        if (targetVersion.isEmpty() || targetVersion == "missing") {
            throw IllegalArgumentException("patch 需要当前 SHA-256，不接受空或 missing")
        }
        checkVersionToken(targetVersion, allowMissing = false)
        val array = args.requiredArray("patches")
        if (array.size() < 1 || array.size() > MAX_PATCHES) {
            throw IllegalArgumentException("patches 数量必须在 1..$MAX_PATCHES")
        }
        val size = path.length()
        val patches = ArrayList<BytePatch>(array.size())
        var totalBytes = 0L
        for (element in array) {
            if (!element.isJsonObject) throw IllegalArgumentException("patches 的每一项必须是对象")
            val item = element.asJsonObject
            val offset = item.objLong("byteOffset")
            checkRange(offset, "byteOffset", 0, MAX_SAFE_INTEGER)
            val expected = parseHex(item.objString("expectedHex"), "expectedHex", MAX_PATCH_BYTES_PER_ITEM, allowEmpty = false)
            val replacement = parseHex(item.objString("replacementHex"), "replacementHex", MAX_PATCH_BYTES_PER_ITEM, allowEmpty = true)
            if (expected.size != replacement.size) {
                throw IllegalArgumentException("expectedHex 与 replacementHex 必须等长")
            }
            if (expected.isEmpty()) throw IllegalArgumentException("expectedHex 不能为空")
            val end = offset + expected.size
            if (end < offset || end > size) throw IllegalArgumentException("补丁范围超出文件大小")
            totalBytes += expected.size
            if (totalBytes > MAX_PATCH_BYTES_TOTAL) {
                throw IllegalArgumentException("补丁总字节数超过 $MAX_PATCH_BYTES_TOTAL")
            }
            patches.add(BytePatch(offset, expected, replacement))
        }
        patches.sortBy { it.offset }
        for (index in 1 until patches.size) {
            val previousEnd = patches[index - 1].offset + patches[index - 1].expected.size
            if (patches[index].offset < previousEnd) throw IllegalArgumentException("补丁范围不能重叠")
        }
        if (sha256File(path) != targetVersion) throw versionConflict(path, targetVersion)

        // 发布前先验证所有 expected 窗口。
        RandomAccessFile(path, "r").use { input ->
            for (patch in patches) {
                val current = ByteArray(patch.expected.size)
                input.seek(patch.offset)
                input.readFully(current)
                if (!current.contentEquals(patch.expected)) {
                    throw IOException("expectedHex 与文件当前字节不一致（offset=${patch.offset}），已拒绝写入")
                }
            }
        }

        val newVersion = transactionalWrite(path, targetVersion, mustBeMissing = false) { output ->
            RandomAccessFile(path, "r").use { input ->
                var position = 0L
                for (patch in patches) {
                    copyRange(input, output, patch.offset - position)
                    val current = ByteArray(patch.expected.size)
                    input.readFully(current)
                    if (!current.contentEquals(patch.expected)) {
                        throw IOException("文件在补丁准备期间发生变化（offset=${patch.offset}）")
                    }
                    output.write(patch.replacement)
                    position = patch.offset + patch.expected.size
                }
                copyRange(input, output, Long.MAX_VALUE)
            }
        }
        val data = JsonObject().apply {
            addProperty("path", path.path)
            addProperty("changed", true)
            addProperty("patchCount", patches.size)
            addProperty("patchedBytes", totalBytes)
            addProperty("size", size)
            addProperty("targetVersion", newVersion)
        }
        return ToolResult(data)
    }

    private fun copyRange(input: RandomAccessFile, output: OutputStream, count: Long) {
        if (count <= 0) return
        val buffer = ByteArray(64 * 1024)
        var remaining = count
        while (remaining > 0) {
            val wanted = min(buffer.size.toLong(), remaining).toInt()
            val read = input.read(buffer, 0, wanted)
            if (read < 0) {
                if (count != Long.MAX_VALUE) throw IOException("读取源文件时提前到达 EOF")
                return
            }
            if (read == 0) continue
            output.write(buffer, 0, read)
            if (remaining != Long.MAX_VALUE) remaining -= read
        }
    }

    private fun copyFile(args: JsonObject): ToolResult {
        val source = requireExistingFile(resolvePath(args.requiredString("sourcePath"), allowEmpty = false))
        val destination = resolvePath(args.requiredString("destinationPath"), allowEmpty = false)
        val overwrite = args.requiredBoolean("overwrite")
        if (samePath(source, destination)) throw IllegalArgumentException("源与目标不能相同")
        requireParentDirectory(destination)
        if (destination.exists()) {
            if (isSymlink(destination)) throw SecurityException("不接受符号链接目标：${destination.path}")
            if (!destination.isFile) throw IllegalArgumentException("目标已存在且不是普通文件：${destination.path}")
            if (!overwrite) throw IOException("目标已存在且 overwrite=false：${destination.path}")
        }
        val existed = destination.exists()
        val sourceSize = source.length()
        val newVersion = transactionalWrite(
            destination,
            expectedVersion = null,
            mustBeMissing = !overwrite,
        ) { output ->
            FileInputStream(source).use { input ->
                copyStream(input, output)
            }
        }
        val data = JsonObject().apply {
            addProperty("sourcePath", source.path)
            addProperty("destinationPath", destination.path)
            addProperty("copiedBytes", sourceSize)
            addProperty("overwritten", existed)
            addProperty("targetVersion", newVersion)
            add("warnings", stringArray(listOf("Java File API 未完全复制 owner/group/修改时间等平台元数据")))
        }
        return ToolResult(data)
    }

    private fun movePath(args: JsonObject): ToolResult {
        val source = resolvePath(args.requiredString("sourcePath"), allowEmpty = false)
        val destination = resolvePath(args.requiredString("destinationPath"), allowEmpty = false)
        val overwrite = args.requiredBoolean("overwrite")
        if (samePath(source, destination)) throw IllegalArgumentException("源与目标不能相同")
        if (samePath(source, rootDir)) throw SecurityException("不能移动 root")
        if (!source.exists()) throw IOException("源不存在：${source.path}")
        if (isSymlink(source)) throw SecurityException("不接受符号链接源：${source.path}")
        requireParentDirectory(destination)
        val destinationExists = destination.exists()
        if (destinationExists && isSymlink(destination)) {
            throw SecurityException("不接受符号链接目标：${destination.path}")
        }
        val sourceIsDirectory = source.isDirectory
        if (sourceIsDirectory) {
            if (overwrite) throw IllegalArgumentException("目录移动要求 overwrite=false")
            if (destinationExists) throw IOException("目录目标已存在，不能合并或替换：${destination.path}")
            if (isSameOrChildText(normalizedPath(source), normalizedPath(destination))) {
                throw IllegalArgumentException("不能把目录移动到自身或子目录")
            }
        } else {
            if (!source.isFile) throw IllegalArgumentException("源不是普通文件或目录：${source.path}")
            if (destinationExists && !destination.isFile) {
                throw IllegalArgumentException("目标已存在且不是普通文件：${destination.path}")
            }
            if (destinationExists && !overwrite) {
                throw IOException("目标已存在且 overwrite=false：${destination.path}")
            }
        }
        renameWithRollback(source, destination)
        val data = JsonObject().apply {
            addProperty("sourcePath", source.path)
            addProperty("destinationPath", destination.path)
            addProperty("moved", true)
            addProperty("overwritten", destinationExists)
            addProperty("type", if (sourceIsDirectory) "directory" else "file")
        }
        return ToolResult(data)
    }

    private fun renameWithRollback(source: File, destination: File) {
        if (source.renameTo(destination)) return
        if (!destination.exists()) {
            throw IOException("无法直接移动（跨文件系统或不支持）：${source.path} -> ${destination.path}")
        }
        val backup = File(destination.parentFile, ".aijspro-backup-${System.nanoTime()}-${Thread.currentThread().id}")
        if (!destination.renameTo(backup)) throw IOException("无法备份现有目标：${destination.path}")
        try {
            if (!source.renameTo(destination)) throw IOException("无法直接移动（跨文件系统或不支持）：${source.path} -> ${destination.path}")
            backup.delete()
        } catch (error: Exception) {
            if (!backup.renameTo(destination)) {
                error.addSuppressed(IOException("回滚失败，原目标保留在：${backup.path}"))
            }
            throw error
        }
    }

    private fun deleteFile(args: JsonObject): ToolResult {
        val path = resolvePath(args.requiredString("path"), allowEmpty = false)
        if (samePath(path, rootDir)) throw SecurityException("不能删除 root")
        requireExistingFile(path)
        if (!path.delete()) throw IOException("删除失败：${path.path}")
        val data = JsonObject().apply {
            addProperty("path", path.path)
            addProperty("deleted", true)
        }
        return ToolResult(data)
    }

    private fun createDirectory(args: JsonObject): ToolResult {
        val path = resolvePath(args.requiredString("path"), allowEmpty = false)
        if (path.exists()) {
            if (isSymlink(path)) throw SecurityException("不接受符号链接：${path.path}")
            if (path.isDirectory) {
                return ToolResult(JsonObject().apply {
                    addProperty("path", path.path)
                    addProperty("created", false)
                    addProperty("changed", false)
                    addProperty("type", "directory")
                })
            }
            throw IOException("已存在同名的非目录路径：${path.path}")
        }
        if (!path.mkdirs() && !path.isDirectory) {
            throw IOException("创建目录失败（可能父路径不是目录或权限不足；非原子操作，可能已创建部分父目录）：${path.path}")
        }
        val data = JsonObject().apply {
            addProperty("path", path.path)
            addProperty("created", true)
            addProperty("changed", true)
            addProperty("type", "directory")
        }
        return ToolResult(data)
    }

    private fun deleteDirectory(args: JsonObject): ToolResult {
        val path = resolvePath(args.requiredString("path"), allowEmpty = false)
        val recursive = args.requiredBoolean("recursive")
        if (samePath(path, rootDir)) throw SecurityException("不能删除 root")
        requireExistingDirectory(path)
        if (!recursive) {
            val children = path.listFiles()
                ?: throw IOException("无法读取目录：${path.path}")
            if (children.isNotEmpty()) {
                throw IllegalArgumentException("目录非空；递归删除必须显式设置 recursive=true：${path.path}")
            }
            if (!path.delete()) throw IOException("删除空目录失败：${path.path}")
            val data = JsonObject().apply {
                addProperty("path", path.path)
                addProperty("recursive", false)
                addProperty("deleted", true)
                addProperty("deletedEntries", 1)
            }
            return ToolResult(data)
        }

        val counter = longArrayOf(0L)
        val deadline = System.nanoTime() + RECURSIVE_DELETE_TIME_BUDGET_NANOS
        try {
            deleteTreeGuarded(path, counter, deadline, 0)
        } catch (error: IOException) {
            throw IOException("递归删除未完整完成（已删除 ${counter[0]} 项，可能留下部分目录）：${error.message}", error)
        }
        val data = JsonObject().apply {
            addProperty("path", path.path)
            addProperty("recursive", true)
            addProperty("deleted", true)
            addProperty("deletedEntries", counter[0])
        }
        return ToolResult(data)
    }

    /** 后序遍历删除；链接只 unlink，不跟随；节点/时间/深度都有硬上限。 */
    private fun deleteTreeGuarded(dir: File, counter: LongArray, deadline: Long, depth: Int) {
        if (depth > MAX_RECURSIVE_DELETE_DEPTH) {
            throw IOException("目录深度超过 $MAX_RECURSIVE_DELETE_DEPTH")
        }
        if (System.nanoTime() > deadline) throw IOException("递归删除超过时间预算")
        val children = dir.listFiles() ?: throw IOException("无法读取目录：${dir.path}")
        for (child in children) {
            counter[0]++
            if (counter[0] > MAX_RECURSIVE_DELETE_NODES) {
                throw IOException("递归删除超过 $MAX_RECURSIVE_DELETE_NODES 项上限")
            }
            if (System.nanoTime() > deadline) throw IOException("递归删除超过时间预算")
            val link = isSymlink(child)
            if (link) {
                if (!child.delete()) throw IOException("无法删除符号链接（未跟随目标）：${child.path}")
                continue
            }
            val canonical = child.canonicalFile
            if (!isSameOrChildText(normalizedPath(rootDir), normalizedPath(canonical))) {
                throw SecurityException("递归删除遇到越界路径，已中止：${child.path}")
            }
            if (child.isDirectory) {
                deleteTreeGuarded(child, counter, deadline, depth + 1)
            } else {
                if (!child.delete()) throw IOException("无法删除文件：${child.path}")
            }
        }
        if (!dir.delete()) throw IOException("无法删除目录：${dir.path}")
        counter[0]++
    }

    private fun truncateBytes(args: JsonObject): ToolResult {
        val path = requireExistingFile(resolvePath(args.requiredString("path"), allowEmpty = false))
        val targetVersion = args.requiredString("targetVersion")
        if (targetVersion.isEmpty() || targetVersion == "missing") {
            throw IllegalArgumentException("truncate 需要当前 SHA-256，不接受空或 missing")
        }
        checkVersionToken(targetVersion, allowMissing = false)
        val size = args.requiredLong("size")
        checkRange(size, "size", 0, MAX_SAFE_INTEGER)
        val oldSize = path.length()
        if (size > oldSize) throw IllegalArgumentException("size 不能大于当前文件大小：$size > $oldSize")
        if (sha256File(path) != targetVersion) throw versionConflict(path, targetVersion)
        if (size == oldSize) {
            val data = JsonObject().apply {
                addProperty("path", path.path)
                addProperty("changed", false)
                addProperty("oldSize", oldSize)
                addProperty("size", size)
                addProperty("targetVersion", targetVersion)
            }
            return ToolResult(data)
        }
        val newVersion = transactionalWrite(path, targetVersion, mustBeMissing = false) { output ->
            FileInputStream(path).use { input ->
                val copied = copyStream(input, output, size)
                if (copied != size) throw IOException("读取源文件时提前到达 EOF")
            }
        }
        val data = JsonObject().apply {
            addProperty("path", path.path)
            addProperty("changed", true)
            addProperty("oldSize", oldSize)
            addProperty("size", size)
            addProperty("targetVersion", newVersion)
        }
        return ToolResult(data)
    }

    private fun appendBytes(args: JsonObject): ToolResult {
        val path = requireExistingFile(resolvePath(args.requiredString("path"), allowEmpty = false))
        val targetVersion = args.requiredString("targetVersion")
        if (targetVersion.isEmpty() || targetVersion == "missing") {
            throw IllegalArgumentException("append 需要当前 SHA-256，不接受空或 missing")
        }
        checkVersionToken(targetVersion, allowMissing = false)
        val bytes = parseHex(args.requiredString("hex"), "hex", MAX_BINARY_WRITE_BYTES, allowEmpty = true)
        val oldSize = path.length()
        if (bytes.isEmpty()) {
            if (sha256File(path) != targetVersion) throw versionConflict(path, targetVersion)
            val data = JsonObject().apply {
                addProperty("path", path.path)
                addProperty("changed", false)
                addProperty("appendedBytes", 0)
                addProperty("size", oldSize)
                addProperty("targetVersion", targetVersion)
            }
            return ToolResult(data)
        }
        val newVersion = transactionalWrite(path, targetVersion, mustBeMissing = false) { output ->
            FileInputStream(path).use { input -> copyStream(input, output) }
            output.write(bytes)
        }
        val data = JsonObject().apply {
            addProperty("path", path.path)
            addProperty("changed", true)
            addProperty("appendedBytes", bytes.size)
            addProperty("size", oldSize + bytes.size)
            addProperty("targetVersion", newVersion)
        }
        return ToolResult(data)
    }

    private fun appendText(args: JsonObject): ToolResult {
        val path = requireExistingFile(resolvePath(args.requiredString("path"), allowEmpty = false))
        val targetVersion = args.requiredString("targetVersion")
        if (targetVersion.isEmpty() || targetVersion == "missing") {
            throw IllegalArgumentException("append_text 需要当前 SHA-256，不接受空或 missing")
        }
        checkVersionToken(targetVersion, allowMissing = false)
        val encoding = args.requiredString("encoding")
        if (encoding.isEmpty()) throw IllegalArgumentException("encoding 不能为空")
        val writeText = args.requiredString("writeText")
        if (textCodePoints(writeText) > MAX_EDIT_TEXT_CODE_POINTS) {
            throw IllegalArgumentException("追加文本超过 $MAX_EDIT_TEXT_CODE_POINTS 码点")
        }
        val decoded = readDecodedFile(path, encoding, targetVersion)
        if (decoded.text.isEmpty()) {
            rejectAccidentalBom(decoded.codec, writeText, "追加文本")
        }
        val encodedWithBom = encodeText(writeText, decoded.codec)
        val encoded = if (decoded.codec.bom.isEmpty()) {
            encodedWithBom
        } else {
            encodedWithBom.copyOfRange(decoded.codec.bom.size, encodedWithBom.size)
        }
        if (encoded.size > MAX_EDIT_TEXT_CODE_POINTS) {
            throw IllegalArgumentException("追加文本编码后超过 $MAX_EDIT_TEXT_CODE_POINTS 字节")
        }
        if (writeText.isEmpty()) {
            val data = JsonObject().apply {
                addProperty("path", path.path)
                addProperty("changed", false)
                addProperty("encoding", decoded.codec.token)
                addProperty("appendedBytes", 0)
                addProperty("size", decoded.bytes.size)
                addProperty("targetVersion", decoded.version)
            }
            return ToolResult(data)
        }
        if (decoded.bytes.size.toLong() + encoded.size > MAX_TEXT_BYTES) {
            throw IllegalArgumentException("追加后文本超过 10 MiB")
        }
        val output = ByteArray(decoded.bytes.size + encoded.size)
        System.arraycopy(decoded.bytes, 0, output, 0, decoded.bytes.size)
        System.arraycopy(encoded, 0, output, decoded.bytes.size, encoded.size)
        val newVersion = transactionalWrite(path, targetVersion, mustBeMissing = false) { it.write(output) }
        val data = JsonObject().apply {
            addProperty("path", path.path)
            addProperty("changed", true)
            addProperty("encoding", decoded.codec.token)
            addProperty("appendedBytes", encoded.size)
            addProperty("size", output.size)
            addProperty("targetVersion", newVersion)
        }
        return ToolResult(data)
    }

    // ------------------------------------------------------------------
    // 文本替换（字面量/正则 + 模板）
    // ------------------------------------------------------------------

    private data class TemplatePart(
        val literal: String? = null,
        val groupNumber: Int? = null,
    )

    private fun parseTemplate(template: String, groupCount: Int, namedGroups: Map<String, Int>): List<TemplatePart> {
        val parts = ArrayList<TemplatePart>()
        val literal = StringBuilder()
        fun flush() {
            if (literal.isNotEmpty()) {
                parts.add(TemplatePart(literal = literal.toString()))
                literal.setLength(0)
            }
        }
        var index = 0
        while (index < template.length) {
            val char = template[index]
            when (char) {
                '\\' -> {
                    if (index + 1 >= template.length) throw IllegalArgumentException("模板以孤立反斜杠结尾")
                    val next = template[index + 1]
                    literal.append(
                        when (next) {
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            '\\' -> '\\'
                            '$' -> '$'
                            else -> throw IllegalArgumentException("模板包含不支持的转义：\\$next")
                        }
                    )
                    index += 2
                }
                '$' -> {
                    if (index + 1 >= template.length) throw IllegalArgumentException("模板以孤立 $ 结尾")
                    val next = template[index + 1]
                    if (next == '{') {
                        val close = template.indexOf('}', index + 2)
                        if (close < 0) throw IllegalArgumentException("模板中的 \${...} 未闭合")
                        val name = template.substring(index + 2, close)
                        if (name.isEmpty()) throw IllegalArgumentException("模板中的 \${...} 为空")
                        val number = if (name.all { it.isDigit() }) {
                            parseTemplateGroupNumber(name)
                        } else {
                            namedGroups[name]
                                ?: throw IllegalArgumentException("模板引用了不存在的命名组：$name")
                        }
                        if (number > groupCount) throw IllegalArgumentException("模板引用了不存在的组：$number")
                        flush()
                        parts.add(TemplatePart(groupNumber = number))
                        index = close + 1
                    } else if (next.isDigit()) {
                        val number = next - '0'
                        if (number > groupCount) throw IllegalArgumentException("模板引用了不存在的组：$number")
                        flush()
                        parts.add(TemplatePart(groupNumber = number))
                        index += 2
                    } else {
                        throw IllegalArgumentException("模板中的 $ 后必须是数字或 {name}")
                    }
                }
                else -> {
                    literal.append(char)
                    index++
                }
            }
        }
        flush()
        return parts
    }

    private fun parseTemplateGroupNumber(value: String): Int {
        if (value.length > 9) throw IllegalArgumentException("模板组编号过大")
        return value.toIntOrNull() ?: throw IllegalArgumentException("模板组编号无效")
    }

    /** 不调用 Matcher.group(String)（minSdk 21 无此 API），自行把命名组映射到编号。 */
    private fun namedGroupNumbers(pattern: String): Map<String, Int> {
        val result = HashMap<String, Int>()
        var group = 0
        var index = 0
        var escaped = false
        var inClass = false
        while (index < pattern.length) {
            val char = pattern[index]
            if (escaped) {
                escaped = false
                index++
                continue
            }
            if (char == '\\') {
                escaped = true
                index++
                continue
            }
            if (inClass) {
                if (char == ']') inClass = false
                index++
                continue
            }
            if (char == '[') {
                inClass = true
                index++
                continue
            }
            if (char != '(') {
                index++
                continue
            }
            if (index + 1 < pattern.length && pattern[index + 1] == '?') {
                if (index + 3 < pattern.length && pattern[index + 2] == '<' &&
                    pattern[index + 3] != '=' && pattern[index + 3] != '!'
                ) {
                    val close = pattern.indexOf('>', index + 3)
                    if (close > 0) {
                        group++
                        result[pattern.substring(index + 3, close)] = group
                        index = close + 1
                        continue
                    }
                }
                index++
                continue
            }
            group++
            index++
        }
        return result
    }

    private fun expandTemplate(parts: List<TemplatePart>, matcher: java.util.regex.Matcher): String {
        val builder = StringBuilder()
        for (part in parts) {
            val number = part.groupNumber
            if (number == null) {
                builder.append(part.literal.orEmpty())
            } else {
                builder.append(matcher.group(number).orEmpty())
            }
        }
        return builder.toString()
    }

    private fun replaceText(args: JsonObject): ToolResult {
        val path = requireExistingFile(resolvePath(args.requiredString("path"), allowEmpty = false))
        val targetVersion = args.requiredString("targetVersion")
        if (targetVersion.isEmpty() || targetVersion == "missing") {
            throw IllegalArgumentException("replace_text 需要当前 SHA-256，不接受空或 missing")
        }
        checkVersionToken(targetVersion, allowMissing = false)
        val encoding = args.requiredString("encoding")
        if (encoding.isEmpty()) throw IllegalArgumentException("encoding 不能为空")
        val contentPattern = args.requiredString("contentPattern")
        if (contentPattern.isEmpty()) throw IllegalArgumentException("contentPattern 不能为空")
        if (contentPattern.length > MAX_SEARCH_PATTERN_CHARS) throw IllegalArgumentException("contentPattern 过长")
        val patternType = args.requiredString("contentPatternType")
        if (patternType !in setOf("literal", "regex")) throw IllegalArgumentException("contentPatternType 无效")
        val caseSensitive = args.requiredBoolean("contentCaseSensitive")
        val writeText = args.requiredString("writeText")
        val replacementMode = args.requiredString("replacementMode")
        if (replacementMode !in setOf("literal", "template")) throw IllegalArgumentException("replacementMode 无效")
        if (replacementMode == "template" && patternType != "regex") {
            throw IllegalArgumentException("replacementMode=template 只支持正则 contentPatternType=regex")
        }
        val replaceMode = args.requiredString("replaceMode")
        if (replaceMode !in setOf("first", "all")) throw IllegalArgumentException("replaceMode 无效")
        val expectedReplacements = args.requiredLong("expectedReplacements")
        if (expectedReplacements != -1L) checkRange(expectedReplacements, "expectedReplacements", 0, MAX_SAFE_INTEGER)
        if (textCodePoints(contentPattern).toLong() + textCodePoints(writeText) > MAX_EDIT_TEXT_CODE_POINTS) {
            throw IllegalArgumentException("contentPattern + writeText 超过 $MAX_EDIT_TEXT_CODE_POINTS 码点")
        }

        val decoded = readDecodedFile(path, encoding, targetVersion)
        val flags = Pattern.MULTILINE or (if (caseSensitive) 0 else CASE_INSENSITIVE)
        val pattern = try {
            ToolkitRegex.compile(if (patternType == "literal") Pattern.quote(contentPattern) else contentPattern, flags)
        } catch (error: PatternSyntaxException) {
            throw IllegalArgumentException("正则无效：${error.message}")
        }
        val matcher = ToolkitRegex.matcher(pattern, decoded.text)
        val groupCount = matcher.groupCount()
        val template = if (replacementMode == "template") {
            parseTemplate(writeText, groupCount, namedGroupNumbers(contentPattern))
        } else {
            emptyList()
        }
        val builder = StringBuilder(decoded.text.length)
        var last = 0
        var count = 0L
        while (ToolkitRegex.find(matcher)) {
            builder.append(decoded.text, last, matcher.start())
            if (replacementMode == "template") {
                builder.append(expandTemplate(template, matcher))
            } else {
                builder.append(writeText)
            }
            last = matcher.end()
            count++
            if (replaceMode == "first") break
            if (count > 1000000L) throw IllegalArgumentException("匹配数超过 1000000，已拒绝")
        }
        if (count == 0L) {
            val data = JsonObject().apply {
                addProperty("path", path.path)
                addProperty("changed", false)
                addProperty("replacements", 0)
                addProperty("encoding", decoded.codec.token)
                addProperty("targetVersion", decoded.version)
            }
            return ToolResult(data)
        }
        if (expectedReplacements != -1L && expectedReplacements != count) {
            throw IllegalArgumentException("替换数量不符：期望 $expectedReplacements，实际 $count")
        }
        builder.append(decoded.text, last, decoded.text.length)
        val replacedText = builder.toString()
        rejectAccidentalBom(decoded.codec, replacedText, "文本替换")
        val output = encodeText(replacedText, decoded.codec)
        if (output.size > MAX_TEXT_BYTES) throw IllegalArgumentException("替换结果超过 10 MiB")
        if (output.contentEquals(decoded.bytes)) {
            val data = JsonObject().apply {
                addProperty("path", path.path)
                addProperty("changed", false)
                addProperty("replacements", count)
                addProperty("encoding", decoded.codec.token)
                addProperty("targetVersion", decoded.version)
            }
            return ToolResult(data)
        }
        val newVersion = transactionalWrite(path, targetVersion, mustBeMissing = false) { it.write(output) }
        val data = JsonObject().apply {
            addProperty("path", path.path)
            addProperty("changed", true)
            addProperty("replacements", count)
            addProperty("encoding", decoded.codec.token)
            addProperty("bytesWritten", output.size)
            addProperty("targetVersion", newVersion)
        }
        return ToolResult(data)
    }

    // ------------------------------------------------------------------
    // 目录搜索
    // ------------------------------------------------------------------

    private class TextMatcher(
        pattern: String,
        type: String,
        private val mode: String,
        caseSensitive: Boolean,
        multiline: Boolean = false,
    ) {
        private val regex: Pattern

        init {
            var flags = if (caseSensitive) 0 else CASE_INSENSITIVE
            if (multiline) flags = flags or Pattern.MULTILINE
            regex = try {
                ToolkitRegex.compile(if (type == "literal") Pattern.quote(pattern) else pattern, flags)
            } catch (error: PatternSyntaxException) {
                throw IllegalArgumentException("正则无效：${error.message}")
            }
        }

        fun matches(value: String): Boolean {
            val matcher = ToolkitRegex.matcher(regex, value)
            return if (mode == "exact") ToolkitRegex.matches(matcher) else ToolkitRegex.find(matcher)
        }
    }

    private enum class ContentOutcome { MATCH, NO_MATCH, DECODE_ERROR, IO_ERROR, BUDGET }

    private data class SearchSpec(
        val directory: File,
        val recursive: Boolean,
        val nameMatcher: TextMatcher?,
        val minSize: Long,
        val maxSize: Long,
        val minModifiedAt: Long,
        val maxModifiedAt: Long,
        val contentMatcher: TextMatcher?,
        val limit: Int,
        val fingerprint: String,
        val pathArgument: String,
        val namePattern: String,
        val namePatternType: String,
        val nameMatchMode: String,
        val nameCaseSensitive: Boolean,
        val minModifiedText: String,
        val maxModifiedText: String,
        val contentPattern: String,
        val contentPatternType: String,
        val contentCaseSensitive: Boolean,
    )

    private fun searchFiles(args: JsonObject): ToolResult {
        val rawPath = args.requiredString("path")
        if (rawPath.isEmpty()) throw IllegalArgumentException("path 不能为空；搜索 root 请用 .")
        val recursive = args.requiredBoolean("recursive")
        val namePattern = args.requiredString("namePattern")
        val namePatternType = args.requiredString("namePatternType")
        val nameMatchMode = args.requiredString("nameMatchMode")
        val nameCaseSensitive = args.requiredBoolean("nameCaseSensitive")
        val minSize = args.requiredLong("minSize")
        val maxSize = args.requiredLong("maxSize")
        val minModifiedText = args.requiredString("minModifiedAt")
        val maxModifiedText = args.requiredString("maxModifiedAt")
        val contentPattern = args.requiredString("contentPattern")
        val contentPatternType = args.requiredString("contentPatternType")
        val contentCaseSensitive = args.requiredBoolean("contentCaseSensitive")
        val cursor = args.requiredString("cursor")
        val limit = args.requiredInt("limit")

        checkRange(limit.toLong(), "limit", 1, MAX_SEARCH_LIMIT.toLong())
        if (namePatternType !in setOf("literal", "regex")) throw IllegalArgumentException("namePatternType 无效")
        if (nameMatchMode !in setOf("exact", "contains")) throw IllegalArgumentException("nameMatchMode 无效")
        if (contentPatternType !in setOf("literal", "regex")) throw IllegalArgumentException("contentPatternType 无效")
        if (namePattern.length > MAX_SEARCH_PATTERN_CHARS) throw IllegalArgumentException("namePattern 过长")
        if (contentPattern.length > MAX_SEARCH_PATTERN_CHARS) throw IllegalArgumentException("contentPattern 过长")
        if (minSize != -1L) checkRange(minSize, "minSize", 0, MAX_SAFE_INTEGER)
        if (maxSize != -1L) checkRange(maxSize, "maxSize", 0, MAX_SAFE_INTEGER)
        if (minSize != -1L && maxSize != -1L && maxSize <= minSize) {
            throw IllegalArgumentException("maxSize 必须大于已启用的 minSize")
        }
        val minModifiedAt = parseIsoMillis(minModifiedText, "minModifiedAt")
        val maxModifiedAt = parseIsoMillis(maxModifiedText, "maxModifiedAt")
        if (minModifiedAt >= 0 && maxModifiedAt >= 0 && maxModifiedAt <= minModifiedAt) {
            throw IllegalArgumentException("maxModifiedAt 必须晚于已启用的 minModifiedAt")
        }

        val directory = requireExistingDirectory(resolvePath(rawPath, allowEmpty = false))
        val nameMatcher = if (namePattern.isEmpty()) {
            null
        } else {
            TextMatcher(namePattern, namePatternType, nameMatchMode, nameCaseSensitive)
        }
        val contentMatcher = if (contentPattern.isEmpty()) {
            null
        } else {
            TextMatcher(contentPattern, contentPatternType, "contains", contentCaseSensitive, multiline = true)
        }
        val fingerprint = searchFingerprint(
            directory, recursive, namePattern, namePatternType, nameMatchMode, nameCaseSensitive,
            minSize, maxSize, minModifiedText, maxModifiedText, contentPattern, contentPatternType,
            contentCaseSensitive, limit
        )
        val spec = SearchSpec(
            directory, recursive, nameMatcher, minSize, maxSize, minModifiedAt, maxModifiedAt,
            contentMatcher, limit, fingerprint, directory.path, namePattern, namePatternType,
            nameMatchMode, nameCaseSensitive, minModifiedText, maxModifiedText, contentPattern,
            contentPatternType, contentCaseSensitive
        )

        val stack = if (cursor.isEmpty()) {
            mutableListOf(Frame(directory, 0))
        } else {
            val body = decodeCursor(cursor)
            if (body.get("k")?.asString != "search") throw IllegalArgumentException("cursor 不属于 file_search")
            if (!samePathText(body.get("root")?.asString ?: "", normalizedPath(rootDir))) {
                throw IllegalArgumentException("cursor 不属于当前 root")
            }
            if (!samePathText(body.get("p")?.asString ?: "", normalizedPath(directory))) {
                throw IllegalArgumentException("cursor 与当前搜索目录不匹配")
            }
            if (body.get("fp")?.asString != fingerprint) {
                throw IllegalArgumentException("cursor 与当前搜索条件不匹配，请从第一页重新开始")
            }
            cursorStack(body)
        }

        val results = JsonArray()
        val warnings = ArrayList<String>()
        var visited = 0
        var stopReason: String? = null
        var canContinue = true
        var incompleteWarning = false
        val deadline = System.nanoTime() + SEARCH_TIME_BUDGET_NANOS

        while (results.size() < limit && stack.isNotEmpty()) {
            if (visited >= MAX_SEARCH_NODES) {
                stopReason = "nodeBudget"
                break
            }
            if (System.nanoTime() > deadline) {
                stopReason = "timeBudget"
                break
            }
            val frame = stack.last()
            val children = try {
                frame.dir.listFiles()
            } catch (error: SecurityException) {
                null
            }
            if (children == null) {
                addWarning(warnings, "无法读取目录：${frame.dir.path}")
                stack.removeAt(stack.size - 1)
                continue
            }
            val ordered = children.sortedBy { it.name }
            if (frame.index >= ordered.size) {
                stack.removeAt(stack.size - 1)
                continue
            }
            val child = ordered[frame.index]
            frame.index++
            visited++
            val link = try {
                isSymlink(child)
            } catch (error: SecurityException) {
                addWarning(warnings, "无法判断路径类型：${child.path}")
                true
            }
            if (link) continue
            if (child.isDirectory) {
                if (recursive) stack.add(Frame(child, 0))
                continue
            }
            if (!child.isFile) continue

            val matchedBy = ArrayList<String>()
            if (nameMatcher != null) {
                try { if (!nameMatcher.matches(child.name)) continue }
                catch (failure: ToolkitRegex.BudgetExceeded) {
                    stopReason = "regexBudget"; canContinue = false; addWarning(warnings, failure.message.orEmpty()); break
                }
                matchedBy.add("name")
            }
            val fileSize = child.length()
            if (minSize >= 0 && fileSize < minSize) continue
            if (maxSize >= 0 && fileSize >= maxSize) continue
            if (minSize >= 0 || maxSize >= 0) matchedBy.add("size")
            val modifiedTime = child.lastModified()
            if (minModifiedAt >= 0 && modifiedTime < minModifiedAt) continue
            if (maxModifiedAt >= 0 && modifiedTime >= maxModifiedAt) continue
            if (minModifiedAt >= 0 || maxModifiedAt >= 0) matchedBy.add("time")
            if (contentMatcher != null) {
                when (contentScan(child, contentMatcher, deadline)) {
                    ContentOutcome.MATCH -> matchedBy.add("content")
                    ContentOutcome.NO_MATCH -> continue
                    ContentOutcome.DECODE_ERROR -> {
                        addWarning(warnings, "内容不是可严格解码的 UTF 文本，已跳过：${child.path}")
                        incompleteWarning = true
                        continue
                    }
                    ContentOutcome.IO_ERROR -> {
                        addWarning(warnings, "读取内容失败，已跳过：${child.path}")
                        incompleteWarning = true
                        continue
                    }
                    ContentOutcome.BUDGET -> {
                        stopReason = "contentBudget"
                        canContinue = false
                        break
                    }
                }
            }
            results.add(searchEntry(child, matchedBy))
        }

        val more = canContinue && hasMoreListWork(stack)
        if (stopReason == null) {
            stopReason = when {
                more -> "limit"
                incompleteWarning -> "warnings"
                else -> "eof"
            }
        }
        val finalStopReason = stopReason ?: "eof"
        val truncated = more || !canContinue || incompleteWarning
        val nextCursor = if (more) {
            encodeCursor(JsonObject().apply {
                addProperty("k", "search")
                addProperty("root", normalizedPath(rootDir))
                addProperty("p", normalizedPath(directory))
                addProperty("fp", fingerprint)
                add("st", encodeStack(stack))
            })
        } else {
            null
        }
        val data = JsonObject().apply {
            addProperty("path", directory.path)
            addProperty("recursive", recursive)
            add("entries", results)
            addProperty("count", results.size())
            addProperty("limit", limit)
            addProperty("nodesVisited", visited)
            addProperty("truncated", truncated)
            addProperty("stopReason", finalStopReason)
            if (nextCursor == null) add("nextCursor", JsonNull.INSTANCE) else addProperty("nextCursor", nextCursor)
            add("warnings", stringArray(warnings))
        }
        val actions = JsonArray()
        if (nextCursor != null) {
            actions.add(nextAction("file_search", "继续搜索", searchArguments(spec, nextCursor)))
        }
        return ToolResult(data, actions)
    }

    private fun searchEntry(file: File, matchedBy: List<String>): JsonObject = JsonObject().apply {
        addProperty("name", file.name)
        addProperty("path", file.path)
        addProperty("relativePath", relativePath(file))
        addProperty("size", file.length())
        addProperty("modifiedTime", file.lastModified())
        add("matchedBy", stringArray(matchedBy))
    }

    private fun searchFingerprint(
        directory: File,
        recursive: Boolean,
        namePattern: String,
        namePatternType: String,
        nameMatchMode: String,
        nameCaseSensitive: Boolean,
        minSize: Long,
        maxSize: Long,
        minModifiedText: String,
        maxModifiedText: String,
        contentPattern: String,
        contentPatternType: String,
        contentCaseSensitive: Boolean,
        limit: Int,
    ): String {
        val value = listOf(
            normalizedPath(directory), recursive.toString(), namePattern, namePatternType,
            nameMatchMode, nameCaseSensitive.toString(), minSize.toString(), maxSize.toString(),
            minModifiedText, maxModifiedText, contentPattern, contentPatternType,
            contentCaseSensitive.toString(), limit.toString()
        ).joinToString("\u0000")
        return sha256Bytes(value.toByteArray(Charsets.UTF_8))
    }

    private fun searchArguments(spec: SearchSpec, cursor: String): JsonObject = JsonObject().apply {
        addProperty("path", spec.pathArgument)
        addProperty("recursive", spec.recursive)
        addProperty("namePattern", spec.namePattern)
        addProperty("namePatternType", spec.namePatternType)
        addProperty("nameMatchMode", spec.nameMatchMode)
        addProperty("nameCaseSensitive", spec.nameCaseSensitive)
        addProperty("minSize", spec.minSize)
        addProperty("maxSize", spec.maxSize)
        addProperty("minModifiedAt", spec.minModifiedText)
        addProperty("maxModifiedAt", spec.maxModifiedText)
        addProperty("contentPattern", spec.contentPattern)
        addProperty("contentPatternType", spec.contentPatternType)
        addProperty("contentCaseSensitive", spec.contentCaseSensitive)
        addProperty("cursor", cursor)
        addProperty("limit", spec.limit)
    }

    private fun detectCodecFromFile(file: File): TextCodec {
        val prefix = ByteArray(4)
        var length = 0
        FileInputStream(file).use { input ->
            while (length < prefix.size) {
                val read = input.read(prefix, length, prefix.size - length)
                if (read < 0) break
                length += read
            }
        }
        return detectCodec(if (length == prefix.size) prefix else prefix.copyOf(length))
    }

    private fun contentScan(file: File, matcher: TextMatcher, deadline: Long): ContentOutcome {
        val codec = try {
            detectCodecFromFile(file)
        } catch (error: IOException) {
            return ContentOutcome.IO_ERROR
        }
        var outcome = ContentOutcome.NO_MATCH
        try {
            FileInputStream(file).use { raw ->
                val input = BufferedInputStream(raw, 64 * 1024)
                var skipped = 0
                while (skipped < codec.bom.size) {
                    if (input.read() < 0) return@use
                    skipped++
                }
                val decoder = codec.charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                InputStreamReader(input, decoder).use { reader ->
                    val buffer = CharArray(CONTENT_SCAN_WINDOW_CHARS)
                    var carry = ""
                    scan@ while (true) {
                        if (System.nanoTime() > deadline) {
                            outcome = ContentOutcome.BUDGET
                            break@scan
                        }
                        val read = try {
                            reader.read(buffer)
                        } catch (error: CharacterCodingException) {
                            outcome = ContentOutcome.DECODE_ERROR
                            break@scan
                        } catch (error: IOException) {
                            outcome = ContentOutcome.IO_ERROR
                            break@scan
                        }
                        if (read < 0) {
                            outcome = ContentOutcome.NO_MATCH
                            break@scan
                        }
                        val window = carry + String(buffer, 0, read)
                        if (matcher.matches(window)) {
                            outcome = ContentOutcome.MATCH
                            break@scan
                        }
                        carry = if (window.length <= CONTENT_SCAN_CARRY_CHARS) {
                            window
                        } else {
                            window.substring(window.length - CONTENT_SCAN_CARRY_CHARS)
                        }
                    }
                }
            }
        } catch (error: CharacterCodingException) {
            outcome = ContentOutcome.DECODE_ERROR
        } catch (error: IOException) {
            outcome = ContentOutcome.IO_ERROR
        } catch (error: ToolkitRegex.BudgetExceeded) {
            outcome = ContentOutcome.BUDGET
        } catch (error: StackOverflowError) {
            outcome = ContentOutcome.BUDGET
        }
        return outcome
    }

    // ------------------------------------------------------------------
    // 单文件逐行搜索
    // ------------------------------------------------------------------

    private fun searchText(args: JsonObject): ToolResult {
        val path = requireExistingFile(resolvePath(args.requiredString("path"), allowEmpty = false))
        val encodingArg = args.requiredString("encoding")
        val patternText = args.requiredString("pattern")
        if (patternText.isEmpty()) throw IllegalArgumentException("pattern 不能为空")
        if (patternText.length > MAX_SEARCH_PATTERN_CHARS) throw IllegalArgumentException("pattern 过长")
        val patternType = args.requiredString("patternType")
        if (patternType !in setOf("literal", "regex")) throw IllegalArgumentException("patternType 无效")
        val caseSensitive = args.requiredBoolean("caseSensitive")
        val contextBefore = args.requiredInt("contextBefore")
        val contextAfter = args.requiredInt("contextAfter")
        val limit = args.requiredInt("limit")
        val maxChars = args.requiredInt("maxChars")
        val cursor = args.requiredString("cursor")
        checkRange(contextBefore.toLong(), "contextBefore", 0, MAX_SEARCH_TEXT_CONTEXT.toLong())
        checkRange(contextAfter.toLong(), "contextAfter", 0, MAX_SEARCH_TEXT_CONTEXT.toLong())
        checkRange(limit.toLong(), "limit", 1, MAX_SEARCH_TEXT_LIMIT.toLong())
        checkRange(maxChars.toLong(), "maxChars", 1024, 131072)

        val fileBytes = readBytesCapped(path, MAX_TEXT_BYTES)
        val version = sha256Bytes(fileBytes)
        val codec = resolveCodecForBytes(fileBytes, encodingArg)
        val text = try {
            decodeText(fileBytes, codec)
        } catch (error: IOException) {
            // 与 schema 一致：解码错误返回部分结果，由 stopReason/truncated 说明，而不是让整次调用失败。
            val data = JsonObject().apply {
                addProperty("path", path.path)
                addProperty("encoding", codec.token)
                addProperty("targetVersion", version)
                addProperty("pattern", patternText)
                addProperty("patternType", patternType)
                add("matches", JsonArray())
                add("matchedLines", JsonArray())
                add("blocks", JsonArray())
                addProperty("scanSize", fileBytes.size)
                addProperty("searchedThroughByteOffset", 0)
                addProperty("count", 0)
                addProperty("totalLines", 0)
                addProperty("truncated", true)
                addProperty("stopReason", "decodeError")
                add("nextCursor", JsonNull.INSTANCE)
                add("warnings", stringArray(listOf("文件无法按 ${codec.token} 严格解码：${error.message}")))
            }
            return ToolResult(data)
        }
        val spans = lineSpans(text)
        val flags = Pattern.MULTILINE or (if (caseSensitive) 0 else CASE_INSENSITIVE)
        val pattern = try {
            ToolkitRegex.compile(if (patternType == "literal") Pattern.quote(patternText) else patternText, flags)
        } catch (error: PatternSyntaxException) {
            throw IllegalArgumentException("正则无效：${error.message}")
        }

        var startOrdinal = 0
        if (cursor.isNotEmpty()) {
            val body = decodeCursor(cursor)
            if (body.get("k")?.asString != "search_text") {
                throw IllegalArgumentException("cursor 不属于 file_search_text")
            }
            if (!samePathText(body.get("p")?.asString ?: "", normalizedPath(path))) {
                throw IllegalArgumentException("cursor 与当前文件不匹配")
            }
            if (body.get("ver")?.asString != version) {
                throw IOException("文件已变化，cursor 失效，请重新开始搜索")
            }
            if (body.get("enc")?.asString != codec.token) {
                throw IOException("文件编码与 cursor 不一致，请重新开始搜索")
            }
            if (body.get("pat")?.asString != patternText ||
                body.get("pt")?.asString != patternType ||
                body.get("cs")?.asBoolean != caseSensitive ||
                body.get("cb")?.asInt != contextBefore ||
                body.get("ca")?.asInt != contextAfter ||
                body.get("l")?.asInt != limit ||
                body.get("mc")?.asInt != maxChars
            ) {
                throw IllegalArgumentException("cursor 与当前检索参数不匹配")
            }
            startOrdinal = body.get("m")?.asInt ?: 0
            if (startOrdinal < 0) throw IllegalArgumentException("cursor 匹配位置无效")
        }

        val warnings = ArrayList<String>()
        val matchIndices = ArrayList<Int>()
        val deadline = System.nanoTime() + SEARCH_TIME_BUDGET_NANOS
        var timeBudget = false
        var lineScanLimited = false
        var line = 0
        val needed = startOrdinal.toLong() + limit.toLong() + 1L
        while (line < spans.size) {
            if ((line and 1023) == 0 && System.nanoTime() > deadline) {
                timeBudget = true
                break
            }
            val span = spans[line]
            val content = text.substring(span.start, span.contentEnd)
            try {
                if (content.length <= MAX_SEARCH_TEXT_LINE_CHARS) {
                    if (ToolkitRegex.find(ToolkitRegex.matcher(pattern, content))) {
                        matchIndices.add(line)
                        if (matchIndices.size.toLong() >= needed) break
                    }
                } else {
                    if (!lineScanLimited) addWarning(warnings, "存在超过 1 MiB 的行，仅搜索并返回前 1 MiB：${path.path}")
                    lineScanLimited = true
                    if (ToolkitRegex.find(ToolkitRegex.matcher(pattern, content.substring(0, MAX_SEARCH_TEXT_LINE_CHARS)))) {
                        matchIndices.add(line)
                        if (matchIndices.size.toLong() >= needed) break
                    }
                }
            } catch (failure: ToolkitRegex.BudgetExceeded) {
                timeBudget = true; addWarning(warnings, failure.message ?: "正则匹配预算耗尽"); break
            }
            line++
        }

        val matches = JsonArray()
        var chars = 0
        var delivered = 0
        var contextClipped = false
        var lineClipped = false
        var ordinal = startOrdinal
        while (ordinal < matchIndices.size && delivered < limit) {
            if (chars >= maxChars) break
            val lineIndex = matchIndices[ordinal]
            val span = spans[lineIndex]
            val fullLine = text.substring(span.start, span.contentEnd)
            val clippedLine = fullLine.length > MAX_SEARCH_TEXT_LINE_CHARS
            var lineForOutput = if (clippedLine) fullLine.substring(0, MAX_SEARCH_TEXT_LINE_CHARS) else fullLine
            if (clippedLine) {
                lineClipped = true
                addWarning(warnings, "第 ${lineIndex + 1} 行过长，已截断输出：${path.path}")
            }
            val lineCodePoints = textCodePoints(lineForOutput)
            val remainingForLine = maxChars - chars
            if (lineCodePoints > remainingForLine) {
                lineForOutput = takeCodePoints(lineForOutput, remainingForLine)
                lineClipped = true
            }
            val item = JsonObject().apply {
                addProperty("lineNumber", lineIndex + 1)
                addProperty("lineIndex", lineIndex)
                addProperty("text", lineForOutput)
                addProperty("textClipped", lineForOutput.length != fullLine.length)
            }
            chars += textCodePoints(lineForOutput)

            val beforeArray = JsonArray()
            val beforeStart = maxOf(0, lineIndex - contextBefore)
            for (index in beforeStart until lineIndex) {
                if (chars >= maxChars) {
                    contextClipped = true
                    break
                }
                val contextSpan = spans[index]
                val contextFull = text.substring(contextSpan.start, contextSpan.contentEnd)
                var contextText = if (contextFull.length > MAX_SEARCH_TEXT_LINE_CHARS) {
                    contextClipped = true
                    contextFull.substring(0, MAX_SEARCH_TEXT_LINE_CHARS)
                } else {
                    contextFull
                }
                val contextCodePoints = textCodePoints(contextText)
                if (contextCodePoints > maxChars - chars) {
                    contextText = takeCodePoints(contextText, maxChars - chars)
                    contextClipped = true
                }
                chars += textCodePoints(contextText)
                beforeArray.add(contextLineJson(index, contextText, contextText.length != contextFull.length))
            }
            item.add("before", beforeArray)

            val afterArray = JsonArray()
            val afterEnd = minOf(spans.size, lineIndex + 1 + contextAfter)
            for (index in (lineIndex + 1) until afterEnd) {
                if (chars >= maxChars) {
                    contextClipped = true
                    break
                }
                val contextSpan = spans[index]
                val contextFull = text.substring(contextSpan.start, contextSpan.contentEnd)
                var contextText = if (contextFull.length > MAX_SEARCH_TEXT_LINE_CHARS) {
                    contextClipped = true
                    contextFull.substring(0, MAX_SEARCH_TEXT_LINE_CHARS)
                } else {
                    contextFull
                }
                val contextCodePoints = textCodePoints(contextText)
                if (contextCodePoints > maxChars - chars) {
                    contextText = takeCodePoints(contextText, maxChars - chars)
                    contextClipped = true
                }
                chars += textCodePoints(contextText)
                afterArray.add(contextLineJson(index, contextText, contextText.length != contextFull.length))
            }
            item.add("after", afterArray)

            matches.add(item)
            delivered++
            ordinal++
        }

        val intervals = ArrayList<Pair<Int, Int>>()
        matches.forEach { value ->
            val index = value.asJsonObject.get("lineIndex").asInt
            val start = maxOf(0, index - contextBefore); val end = minOf(spans.size, index + contextAfter + 1)
            val previous = intervals.lastOrNull()
            if(previous != null && start <= previous.second) intervals[intervals.lastIndex] = previous.first to maxOf(previous.second, end)
            else intervals.add(start to end)
        }
        val blocks = JsonArray(); var blockChars = 0
        for((start, end) in intervals) {
            if(blockChars >= maxChars) { contextClipped = true; break }
            val raw = text.substring(spans[start].start, spans[end - 1].end)
            var fragment = takeCodePoints(raw, maxChars - blockChars)
            if(fragment.length < raw.length && fragment.endsWith('\r') && raw[fragment.length] == '\n') fragment = fragment.dropLast(1)
            if(fragment.length < raw.length) contextClipped = true
            blockChars += textCodePoints(fragment)
            blocks.add(JsonObject().apply { addProperty("startLine", start); addProperty("startColumn", 0); addProperty("text", fragment) })
        }
        val more = ordinal < matchIndices.size
        val nextCursor = if (more && !timeBudget) {
            encodeCursor(JsonObject().apply {
                addProperty("k", "search_text")
                addProperty("p", normalizedPath(path))
                addProperty("ver", version)
                addProperty("enc", codec.token)
                addProperty("pat", patternText)
                addProperty("pt", patternType)
                addProperty("cs", caseSensitive)
                addProperty("cb", contextBefore)
                addProperty("ca", contextAfter)
                addProperty("l", limit)
                addProperty("mc", maxChars)
                addProperty("m", ordinal)
            })
        } else {
            null
        }
        if (contextClipped) {
            addWarning(warnings, "上下文被裁剪（maxChars 或单行 1 MiB 上限）：${path.path}")
        }
        val truncated = more || timeBudget || contextClipped || lineClipped || lineScanLimited
        val stopReason = when {
            timeBudget -> "timeBudget"
            more -> if (delivered >= limit) "limit" else "maxChars"
            lineClipped || lineScanLimited -> "lineTruncated"
            contextClipped -> "contextClipped"
            else -> "eof"
        }
        val data = JsonObject().apply {
            addProperty("path", path.path)
            addProperty("encoding", codec.token)
            addProperty("targetVersion", version)
            addProperty("pattern", patternText)
            addProperty("patternType", patternType)
            add("matches", matches)
            add("matchedLines", JsonArray().apply { matches.forEach { add(it.asJsonObject.get("lineIndex")) } })
            add("blocks", blocks)
            addProperty("scanSize", fileBytes.size)
            val through = if(!timeBudget && line >= spans.size) fileBytes.size else {
                val end = spans.getOrNull(line)?.end ?: text.length
                encodeText(text.substring(0, end), codec).size.coerceAtMost(fileBytes.size)
            }
            addProperty("searchedThroughByteOffset", through)
            addProperty("count", matches.size())
            addProperty("totalLines", spans.size)
            addProperty("truncated", truncated)
            addProperty("stopReason", stopReason)
            if (nextCursor == null) add("nextCursor", JsonNull.INSTANCE) else addProperty("nextCursor", nextCursor)
            add("warnings", stringArray(warnings))
        }
        val actions = JsonArray()
        if (nextCursor != null) {
            actions.add(nextAction("file_search_text", "继续搜索文本", JsonObject().apply {
                addProperty("path", path.path)
                addProperty("encoding", codec.token)
                addProperty("pattern", patternText)
                addProperty("patternType", patternType)
                addProperty("caseSensitive", caseSensitive)
                addProperty("contextBefore", contextBefore)
                addProperty("contextAfter", contextAfter)
                addProperty("limit", limit)
                addProperty("maxChars", maxChars)
                addProperty("cursor", nextCursor)
            }))
        }
        return ToolResult(data, actions)
    }

    private fun contextLineJson(index: Int, text: String, clipped: Boolean): JsonObject =
        JsonObject().apply {
            addProperty("lineNumber", index + 1)
            addProperty("lineIndex", index)
            addProperty("text", text)
            addProperty("textClipped", clipped)
        }
}
