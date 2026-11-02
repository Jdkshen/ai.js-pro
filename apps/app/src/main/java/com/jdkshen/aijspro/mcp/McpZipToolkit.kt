package com.jdkshen.aijspro.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.RandomAccessFile
import java.io.Reader
import java.io.SequenceInputStream
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Locale
import java.util.TreeSet
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * ZIP tools for the AI.js Pro MCP server.
 *
 * Listing and raw-entry copying use java.io/java.util.zip so existing limits stay in place.
 * AES/ZipCrypto decryption and AES-256 writing are isolated in [ToolkitZipCrypto], which wraps the
 * zip4j 1.3.2 API already exposed by `:engine`. All caller supplied paths are confined to [root];
 * ZIP contents are treated as untrusted input and are read with explicit entry, size and ratio
 * budgets.
 */
internal class McpZipToolkit(private val root: File) {
    private val canonicalRoot = root.canonicalFile.also {
        require(it.isDirectory) { "ZIP 工具 root 必须是已存在的目录" }
    }
    private val rootPrefix = canonicalRoot.path.trimEnd(File.separatorChar) + File.separator

    /**
     * Resource budgets. They are properties (instead of private constants) so tests can exercise
     * truncation with small archives. They apply to actual bytes read, not only metadata.
     */
    @Volatile internal var maxArchiveEntries: Int = 50_000
    @Volatile internal var maxTotalUncompressed: Long = 512L * 1024L * 1024L
    @Volatile internal var maxCompressionRatio: Double = 250.0
    @Volatile internal var maxReadBytes: Long = 64L * 1024L * 1024L
    @Volatile internal var maxCrcBytes: Long = 512L * 1024L * 1024L
    @Volatile internal var maxCrcEntries: Int = 50_000
    @Volatile internal var maxCreateBytes: Long = 2L * 1024L * 1024L * 1024L

    companion object {
        val names: List<String> = listOf(
            "zip_list",
            "zip_read_text",
            "zip_read_bytes",
            "zip_test",
            "zip_extract",
            "zip_create",
            "zip_update",
        )
        const val CATEGORY: String = "toolkit"
        const val CATEGORY_TITLE: String = "文件与APK工具"

        private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
        private const val CURSOR_TTL_MS = 30L * 60L * 1000L
        private const val CURSOR_PREFIX = "z1-"
        private const val RATIO_MIN_BYTES = 256L * 1024L
        private const val MAX_CENTRAL_DIRECTORY = 128L * 1024L * 1024L
        private const val MAX_CURSOR_TEXT = 64 * 1024
        private const val IO_BUFFER = 64 * 1024

        private const val SIG_CENTRAL = 0x02014b50L
        private const val SIG_EOCD = 0x06054b50L
        private const val SIG_ZIP64_EOCD = 0x06064b50L
        private const val SIG_ZIP64_LOCATOR = 0x07064b50L
        private const val SIG_SPANNING = 0x08074b50L

        private const val METHOD_STORED = 0
        private const val METHOD_DEFLATED = 8
    }

    // ---------------------------------------------------------------------------------------------
    // Tool definitions
    // ---------------------------------------------------------------------------------------------

    fun definitions(): JsonArray = JsonArray().apply {
        add(definition(
            "zip_list", "列出 ZIP 条目",
            "分页列出 ZIP 中的文件和目录，包含由文件路径隐含的目录。目录以 / 结尾；名称区分大小写并原样保留前导 /、. 与 ..。recursive=true 时按名称深度优先返回；page 之间保持 ZIP 不变并使用返回的 continuation。列表不校验 CRC。",
            listSchema(), readOnly = true,
        ))
        add(definition(
            "zip_read_text", "读取 ZIP 文本",
            "按行/列分页读取 ZIP 中某个文本条目的内容，保留原始 LF、CRLF、CR。支持未加密、ZipCrypto 与 AES-256 条目；正确密码才可解密。encoding 传空字符串自动识别，续页必须沿用返回的 encoding。该预览不校验整个条目；需要完整性时调用 zip_test。",
            readTextSchema(), readOnly = true,
        ))
        add(definition(
            "zip_read_bytes", "读取 ZIP 字节",
            "以大写十六进制分页读取 ZIP 条目的未压缩字节。偏移量是条目内容偏移，不是 ZIP 文件偏移。支持未加密、ZipCrypto 与 AES-256 条目。该预览不校验整个条目；需要完整性时调用 zip_test。",
            readBytesSchema(), readOnly = true,
        ))
        add(definition(
            "zip_test", "校验 ZIP 完整性",
            "完整读取每个非目录条目并核对长度与 CRC；AES 条目额外校验 HMAC，ZipCrypto 条目校验密码与 CRC。遇到首个错误停止。会受扫描条目数和字节预算限制；预算不足或线程被中断时报告未完成，绝不把截断扫描当作通过。",
            testSchema(), readOnly = true,
        ))
        add(definition(
            "zip_extract", "解压 ZIP",
            "按显式 entryPath→destinationPath 映射解压，支持未加密、ZipCrypto 与 AES-256 条目。会拒绝绝对条目、..、反斜线、重复目标、目标冲突、符号链接和不安全覆盖；overwrite 必须显式为 true 才能替换已有普通文件。解压前检查条目数、声明总大小和压缩比。",
            extractSchema(), readOnly = false,
        ))
        add(definition(
            "zip_create", "创建 ZIP",
            "从本地文件或目录创建 ZIP（Stored 或 Deflate），支持隐藏文件和空目录。输出不能位于源目录内，也不能同时作为输入。password 为空字符串时不加密；非空时文件条目使用 AES-256 加密，目录条目保持不加密。",
            createSchema(), readOnly = false,
        ))
        add(definition(
            "zip_update", "修改 ZIP",
            "按原始 ZIP 中的名称增加、删除或重命名条目，操作无序且不可形成链、交换或竞争目标。写入同目录临时文件；未改与重命名条目保留原压缩/加密原始字节与密码，新增/替换条目按 password 使用 AES-256 或明文。targetVersion 是可选扩展：传入时校验，省略时仍按当前版本操作并在写入期间检查源未变化。失败时保留原档。不执行 APK 签名。",
            updateSchema(), readOnly = false,
        ))
    }

    private fun definition(
        name: String,
        title: String,
        description: String,
        schema: JsonObject,
        readOnly: Boolean,
    ): JsonObject = JsonObject().apply {
        addProperty("name", name)
        addProperty("title", title)
        addProperty("category", CATEGORY)
        addProperty("categoryTitle", CATEGORY_TITLE)
        addProperty("description", description)
        add("inputSchema", schema)
        add("annotations", JsonObject().apply { addProperty("readOnlyHint", readOnly) })
    }

    private fun listSchema(): JsonObject = schema(
        "path" to str("ZIP 文件路径，root 相对或 root 内绝对路径"),
        "entryPath" to str("ZIP 内目录，必须以 / 结尾；空字符串表示 ZIP 根；/ 表示名称恰为 / 的目录"),
        "recursive" to bool("是否递归包含全部后代"),
        "simple" to bool("true 只返回相对名称；false 返回大小/加密/名称"),
        "cursor" to str("首页传空字符串；续页传返回的分页游标"),
        "limit" to int("每页条目数", 1, 1000),
        required = arrayOf("path", "entryPath", "recursive", "simple", "cursor", "limit"),
    )

    private fun readTextSchema(): JsonObject = schema(
        "path" to str("ZIP 文件路径，root 相对或 root 内绝对路径"),
        "password" to str("条目密码；未加密条目传空字符串。支持 AES-256 与 ZipCrypto，密码错误时拒绝读取"),
        "entryPath" to str("ZIP 内完整文件路径；与 zip_list 的 entryPath + relativePath 直接拼接"),
        "encoding" to str("文本编码，空字符串自动识别；如 utf-8、utf-8-bom、utf-16le、gb18030"),
        "startLine" to long("从 0 开始的逻辑行号", 0, MAX_SAFE_INTEGER),
        "startColumn" to long("从 0 开始的 Unicode 码点列号", 0, MAX_SAFE_INTEGER),
        "limit" to int("最多触碰的逻辑行数", 1, 2000),
        "maxChars" to int("最多返回的 Unicode 码点数，CRLF 作为一个换行整体", 2, 131072),
        required = arrayOf("path", "password", "entryPath", "encoding", "startLine", "startColumn", "limit", "maxChars"),
    )

    private fun readBytesSchema(): JsonObject = schema(
        "path" to str("ZIP 文件路径，root 相对或 root 内绝对路径"),
        "password" to str("条目密码；未加密条目传空字符串。支持 AES-256 与 ZipCrypto，密码错误时拒绝读取"),
        "entryPath" to str("ZIP 内完整文件路径"),
        "byteOffset" to long("从 0 开始的未压缩字节偏移", 0, MAX_SAFE_INTEGER),
        "maxBytes" to int("单页最大字节数", 1, 65536),
        required = arrayOf("path", "password", "entryPath", "byteOffset", "maxBytes"),
    )

    private fun testSchema(): JsonObject = schema(
        "path" to str("ZIP 文件路径，root 相对或 root 内绝对路径"),
        "password" to str("统一密码；未加密 ZIP 传空字符串。AES-256 条目同时校验 HMAC，ZipCrypto 校验 CRC"),
        required = arrayOf("path", "password"),
    )

    private fun extractSchema(): JsonObject = schema(
        "path" to str("源 ZIP 文件路径"),
        "entries" to JsonObject().apply {
            addProperty("type", "array")
            addProperty("minItems", 1)
            addProperty("description", "非空的解压映射；目录映射的 destinationPath 以 / 结尾")
            add("items", JsonObject().apply {
                addProperty("type", "object")
                addProperty("additionalProperties", false)
                add("properties", JsonObject().apply {
                    add("entryPath", str("ZIP 内文件路径，或必须以 / 结尾的目录/空字符串根"))
                    add("destinationPath", str("root 内目标；文件映射写完整文件名，目录映射以 / 结尾"))
                })
                add("required", JsonArray().apply { add("entryPath"); add("destinationPath") })
            })
        },
        "password" to str("统一密码；未加密条目传空字符串。支持 AES-256 与 ZipCrypto"),
        "overwrite" to bool("是否允许替换已存在的普通目标文件"),
        required = arrayOf("path", "entries", "password", "overwrite"),
    )

    private fun createSchema(): JsonObject = schema(
        "outputPath" to str("输出 ZIP 路径；父目录必须存在"),
        "sources" to JsonObject().apply {
            addProperty("type", "array")
            addProperty("minItems", 1)
            addProperty("description", "非空源映射；目录源 sourcePath 以 / 结尾")
            add("items", JsonObject().apply {
                addProperty("type", "object")
                addProperty("additionalProperties", false)
                add("properties", JsonObject().apply {
                    add("sourcePath", str("root 内源文件或目录；目录以 / 结尾"))
                    add("entryPath", str("完整 ZIP 条目路径；目录源以 / 结尾或空字符串表示根"))
                })
                add("required", JsonArray().apply { add("sourcePath"); add("entryPath") })
            })
        },
        "overwrite" to bool("是否允许替换已存在的普通输出文件"),
        "level" to int("0 仅存储；1..9 使用 Deflate，数字越大压缩越强", 0, 9),
        "password" to str("空字符串不加密；非空时文件条目使用 AES-256 加密"),
        required = arrayOf("outputPath", "sources", "overwrite", "level", "password"),
    )

    private fun updateSchema(): JsonObject = schema(
        "path" to str("已存在的 ZIP 文件路径"),
        "operations" to JsonObject().apply {
            addProperty("type", "array")
            addProperty("minItems", 1)
            addProperty("description", "非空且无序的修改；都引用原始 ZIP 名称")
            add("items", JsonObject().apply {
                add("anyOf", JsonArray().apply {
                    add(updateAddSchema())
                    add(updateDeleteFileSchema())
                    add(updateDeleteDirSchema())
                    add(updateRenameFileSchema())
                    add(updateRenameDirSchema())
                })
            })
        },
        "password" to str("新增/替换条目的统一密码；空字符串不加密，非空时使用 AES-256。未改/重命名条目保留原加密与密码"),
        "targetVersion" to str("可选扩展。传入时先调用 zip_list/zip_test 取得 version 并校验；省略时仍按当前 ZIP 版本操作，写入期间检测源未变化"),
        required = arrayOf("path", "operations", "password"),
    )

    private fun updateAddSchema(): JsonObject = objectFields(
        arrayOf("type", "sourcePath", "entryPath", "overwrite"),
        mapOf(
            "type" to enumStr("操作类型", "add"),
            "sourcePath" to str("root 内源文件或目录；目录以 / 结尾"),
            "entryPath" to str("完整目标条目路径；目录以 / 结尾或空字符串表示根"),
            "overwrite" to bool("是否允许替换原始文件"),
        ),
    )

    private fun updateDeleteFileSchema(): JsonObject = objectFields(
        arrayOf("type", "entryPath"),
        mapOf(
            "type" to enumStr("操作类型", "delete"),
            "entryPath" to str("要删除的原始文件路径，不能以 / 结尾"),
        ),
    )

    private fun updateDeleteDirSchema(): JsonObject = objectFields(
        arrayOf("type", "entryPath", "recursive"),
        mapOf(
            "type" to enumStr("操作类型", "delete"),
            "entryPath" to str("要删除的原始目录路径，必须以 / 结尾"),
            "recursive" to bool("目录含条目时必须为 true"),
        ),
    )

    private fun updateRenameFileSchema(): JsonObject = objectFields(
        arrayOf("type", "entryPath", "newEntryPath"),
        mapOf(
            "type" to enumStr("操作类型", "rename"),
            "entryPath" to str("原始文件路径，不能以 / 结尾"),
            "newEntryPath" to str("新文件路径，不能以 / 结尾且不能是已存在条目"),
        ),
    )

    private fun updateRenameDirSchema(): JsonObject = objectFields(
        arrayOf("type", "entryPath", "newEntryPath", "mergeDirectories"),
        mapOf(
            "type" to enumStr("操作类型", "rename"),
            "entryPath" to str("原始目录路径，必须以 / 结尾"),
            "newEntryPath" to str("新目录路径，必须以 / 结尾且不能是源目录后代"),
            "mergeDirectories" to bool("是否允许合并已存在的目标目录"),
        ),
    )

    private fun schema(vararg fields: Pair<String, JsonElement>, required: Array<String>): JsonObject =
        JsonObject().apply {
            addProperty("type", "object")
            addProperty("additionalProperties", false)
            add("properties", JsonObject().apply { fields.forEach { add(it.first, it.second) } })
            add("required", JsonArray().apply { required.forEach { add(it) } })
        }

    private fun objectFields(required: Array<String>, fields: Map<String, JsonElement>): JsonObject =
        JsonObject().apply {
            addProperty("type", "object")
            addProperty("additionalProperties", false)
            add("properties", JsonObject().apply { fields.forEach { (name, value) -> add(name, value) } })
            add("required", JsonArray().apply { required.forEach { add(it) } })
        }

    private fun str(description: String): JsonObject = JsonObject().apply {
        addProperty("type", "string")
        addProperty("description", description)
    }

    private fun enumStr(description: String, value: String): JsonObject = str(description).apply {
        add("enum", JsonArray().apply { add(value) })
    }

    private fun bool(description: String): JsonObject = JsonObject().apply {
        addProperty("type", "boolean")
        addProperty("description", description)
    }

    private fun int(description: String, min: Int, max: Int): JsonObject = JsonObject().apply {
        addProperty("type", "integer")
        addProperty("description", description)
        addProperty("minimum", min)
        addProperty("maximum", max)
    }

    private fun long(description: String, min: Long, max: Long): JsonObject = JsonObject().apply {
        addProperty("type", "integer")
        addProperty("description", description)
        addProperty("minimum", min)
        addProperty("maximum", max)
    }

    // ---------------------------------------------------------------------------------------------
    // Dispatch and common result envelope
    // ---------------------------------------------------------------------------------------------

    fun call(name: String, args: JsonObject): JsonObject {
        if (name !in names) throw IllegalArgumentException("未知 ZIP 工具: $name")
        val data = when (name) {
            "zip_list" -> zipList(args)
            "zip_read_text" -> zipReadText(args)
            "zip_read_bytes" -> zipReadBytes(args)
            "zip_test" -> zipTest(args)
            "zip_extract" -> zipExtract(args)
            "zip_create" -> zipCreate(args)
            "zip_update" -> zipUpdate(args)
            else -> throw IllegalArgumentException("未知 ZIP 工具: $name")
        }
        return JsonObject().apply {
            addProperty("ok", true)
            add("data", data)
            add("error", JsonNull.INSTANCE)
            add("nextActions", data.remove("_nextActions") ?: JsonArray())
        }
    }

    private fun success(data: JsonObject, actions: JsonArray? = null): JsonObject = data.apply {
        if (actions != null && actions.size() > 0) add("_nextActions", actions)
    }

    // ---------------------------------------------------------------------------------------------
    // zip_list
    // ---------------------------------------------------------------------------------------------

    private fun zipList(args: JsonObject): JsonObject {
        val rawPath = args.requiredString("path")
        val entryPath = args.requiredString("entryPath")
        val recursive = args.requiredBoolean("recursive")
        val simple = args.requiredBoolean("simple")
        val cursor = args.requiredString("cursor")
        val limit = args.requiredInt("limit", 1, 1000)
        if (entryPath.isNotEmpty() && !entryPath.endsWith("/")) {
            throw IllegalArgumentException("entryPath 必须是空字符串或以 / 结尾的目录路径")
        }

        val archive = openArchive(rawPath)
        return archive.use {
            if (!archive.isDirectory(entryPath)) {
                throw IllegalArgumentException("ZIP 中不存在目录: ${entryPath.ifEmpty { "<root>" }}")
            }
            val cursorState = decodeCursor(cursor, archive, entryPath, recursive, simple)
            val all = archive.listNames(entryPath, recursive)
            if (cursorState.offset > all.size.toLong()) {
                throw IllegalArgumentException("分页游标偏移超出当前列表；请使用空 cursor 重新列出")
            }
            val start = cursorState.offset.toInt()
            val end = (start + limit).coerceAtMost(all.size)
            val page = all.subList(start, end)
            val hasMore = end < all.size
            val nextCursor = if (hasMore) encodeCursor(archive, entryPath, recursive, simple, end.toLong()) else ""
            val listing = JsonArray().apply {
                page.forEach { name ->
                    if (simple) add(name)
                    else add(archive.detailLine(name, entryPath))
                }
            }
            val data = JsonObject().apply {
                addProperty("path", archive.file.path)
                addProperty("entryPath", entryPath)
                add("listing", listing)
                add("pagination", JsonObject().apply {
                    addProperty("hasMore", hasMore)
                    addProperty("nextCursor", nextCursor)
                })
                addProperty("version", archive.version)
                addProperty("total", all.size)
            }
            val actions = if (hasMore) JsonArray().apply {
                add(action(
                    "zip_list", "continue", "继续列出下一页",
                    JsonObject().apply {
                        addProperty("path", archive.file.path)
                        addProperty("entryPath", entryPath)
                        addProperty("recursive", recursive)
                        addProperty("simple", simple)
                        addProperty("cursor", nextCursor)
                        addProperty("limit", limit)
                    },
                ))
            } else null
            success(data, actions)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // zip_read_text
    // ---------------------------------------------------------------------------------------------

    private fun zipReadText(args: JsonObject): JsonObject {
        val rawPath = args.requiredString("path")
        val password = args.requiredString("password")
        val entryPath = args.requiredString("entryPath")
        val encoding = args.requiredString("encoding")
        val startLine = args.requiredLong("startLine", 0, MAX_SAFE_INTEGER)
        val startColumn = args.requiredLong("startColumn", 0, MAX_SAFE_INTEGER)
        val lineLimit = args.requiredInt("limit", 1, 2000)
        val maxChars = args.requiredInt("maxChars", 2, 131072)

        val archive = openArchive(rawPath)
        return archive.use {
            val entry = archive.fileEntry(entryPath)
            archive.ensureReadable(entry, password)
            val page = archive.readTextPage(entry, password, encoding, startLine, startColumn, lineLimit, maxChars)
            val data = JsonObject().apply {
                addProperty("path", archive.file.path)
                addProperty("entryPath", entry.name)
                addProperty("text", page.text)
                addProperty("encoding", page.encoding)
                addProperty("startLine", page.startLine)
                addProperty("startColumn", page.startColumn)
                addProperty("nextLine", page.nextLine)
                addProperty("nextColumn", page.nextColumn)
                addProperty("returnedLineCount", page.returnedLineCount)
                addProperty("returnedCodePoints", page.returnedCodePoints)
                addProperty("hasMore", page.hasMore)
                addProperty("version", archive.version)
            }
            val actions = if (page.hasMore) JsonArray().apply {
                add(action(
                    "zip_read_text", "continue", "从下一页位置继续读取文本",
                    JsonObject().apply {
                        addProperty("path", archive.file.path)
                        addProperty("password", password)
                        addProperty("entryPath", entry.name)
                        addProperty("encoding", page.encoding)
                        addProperty("startLine", page.nextLine)
                        addProperty("startColumn", page.nextColumn)
                        addProperty("limit", lineLimit)
                        addProperty("maxChars", maxChars)
                    },
                ))
            } else null
            success(data, actions)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // zip_read_bytes
    // ---------------------------------------------------------------------------------------------

    private fun zipReadBytes(args: JsonObject): JsonObject {
        val rawPath = args.requiredString("path")
        val password = args.requiredString("password")
        val entryPath = args.requiredString("entryPath")
        val byteOffset = args.requiredLong("byteOffset", 0, MAX_SAFE_INTEGER)
        val maxBytes = args.requiredInt("maxBytes", 1, 65536)

        val archive = openArchive(rawPath)
        return archive.use {
            val entry = archive.fileEntry(entryPath)
            archive.ensureReadable(entry, password)
            if (entry.size >= 0 && byteOffset > entry.size) {
                throw IllegalArgumentException("byteOffset 超出条目长度: $byteOffset > ${entry.size}")
            }
            val page = archive.readBytePage(entry, password, byteOffset, maxBytes)
            val data = JsonObject().apply {
                addProperty("path", archive.file.path)
                addProperty("entryPath", entry.name)
                addProperty("byteOffset", page.offset)
                addProperty("hex", page.hex)
                addProperty("nextByteOffset", page.nextOffset)
                addProperty("totalBytes", page.totalBytes)
                addProperty("hasMore", page.hasMore)
                addProperty("version", archive.version)
            }
            val actions = if (page.hasMore) JsonArray().apply {
                add(action(
                    "zip_read_bytes", "continue", "继续读取下一页字节",
                    JsonObject().apply {
                        addProperty("path", archive.file.path)
                        addProperty("password", password)
                        addProperty("entryPath", entry.name)
                        addProperty("byteOffset", page.nextOffset)
                        addProperty("maxBytes", maxBytes)
                    },
                ))
            } else null
            success(data, actions)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // zip_test
    // ---------------------------------------------------------------------------------------------

    private fun zipTest(args: JsonObject): JsonObject {
        val rawPath = args.requiredString("path")
        val password = args.requiredString("password")
        val archive = openArchive(rawPath)
        return archive.use {
            val files = archive.entries.filter { !it.directory }
            if (files.size > maxCrcEntries) {
                throw IOException("CRC 校验未完成：条目数 ${files.size} 超过本次扫描预算 $maxCrcEntries，不能判定为通过")
            }
            var checked = 0
            var bytes = 0L
            for (entry in files) {
                checkInterrupted()
                archive.ensureReadable(entry, password)
                if (entry.method != METHOD_STORED && entry.method != METHOD_DEFLATED && !ToolkitZipCrypto.isAes(entry.method)) {
                    throw IOException("条目 '${entry.name}' 使用不支持的压缩算法 ${entry.method}")
                }
                if (ToolkitZipCrypto.isAes(entry.method)) {
                    val extra = entry.aesExtra
                        ?: throw IOException("AES 条目 '${entry.name}' 缺少 0x9901 extra，不能判定为通过")
                    ToolkitZipCrypto.aesCompressionMethod(extra)
                }
                if (checked >= maxCrcEntries) {
                    throw IOException("CRC 校验未完成：已检查 $checked 项，达到条目预算 $maxCrcEntries，不能判定为通过")
                }
                val remaining = maxCrcBytes - bytes
                // zip_test reads to EOF, but keep verification off here so a budget/read error is
                // not masked by zip4j's CRC/MAC check during close. AES gets the dedicated
                // authenticated pass below; ZipCrypto's plaintext CRC is checked directly.
                val budget = BudgetInputStream(
                    archive.openEntryStream(entry, password, verifyOnClose = false), remaining,
                    "CRC 校验未完成：读取超过预算 $maxCrcBytes 字节（条目 '${entry.name}'），不能判定为通过",
                )
                try {
                    val crc = CRC32()
                    var count = 0L
                    val buffer = ByteArray(IO_BUFFER)
                    while (true) {
                        checkInterrupted()
                        val n = budget.read(buffer)
                        if (n < 0) break
                        crc.update(buffer, 0, n)
                        count += n
                        bytes += n
                    }
                    if (entry.size >= 0 && count != entry.size) {
                        throw IOException("条目 '${entry.name}' 长度不符：声明 ${entry.size}，实际 $count（已检查 $checked 项）")
                    }
                    // AE-2 AES entries store CRC=0 and use the HMAC as authentication. Do not
                    // reject that valid form; AE-1/non-zero CRC values are still checked.
                    val crcIsMandatory = !ToolkitZipCrypto.isAes(entry.method) || entry.crc != 0L
                    if (crcIsMandatory && entry.crc >= 0 && crc.value != entry.crc) {
                        throw IOException(
                            "条目 '${entry.name}' CRC 校验失败：声明 ${entry.crc.toString(16)}，" +
                                "实际 ${crc.value.toString(16)}（此前 $checked 项通过）",
                        )
                    }
                } finally {
                    try { budget.close() } catch (_: IOException) {}
                }
                if (ToolkitZipCrypto.isAes(entry.method)) {
                    // CRC/HMAC authentication: zip4j validates the MAC when the stream is fully
                    // read and closed with verification enabled.
                    ToolkitZipCrypto.verifyAuthentication(
                        archive.file, entry.name, archive.nameCharset, password, maxCrcBytes,
                    ) { checkInterrupted() }
                }
                checked++
            }
            val data = JsonObject().apply {
                addProperty("path", archive.file.path)
                addProperty("version", archive.version)
                addProperty("entries", archive.entries.size)
                addProperty("filesChecked", checked)
                addProperty("checkedEntries", checked)
                addProperty("bytesChecked", bytes)
                addProperty("complete", true)
                addProperty("status", "passed")
            }
            success(data)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // zip_extract
    // ---------------------------------------------------------------------------------------------

    private data class ExtractionMapping(val entryPath: String, val destinationPath: String)

    private data class SelectedFile(val entry: CentralEntry, val target: File)

    private fun zipExtract(args: JsonObject): JsonObject {
        val rawPath = args.requiredString("path")
        val mappingsJson = args.requiredArray("entries")
        val password = args.requiredString("password")
        val overwrite = args.requiredBoolean("overwrite")
        if (mappingsJson.size() == 0) throw IllegalArgumentException("entries 不能为空")
        val mappings = mappingsJson.map { element ->
            val obj = element.asObject("entries[]")
            ExtractionMapping(
                obj.requiredString("entryPath"),
                obj.requiredString("destinationPath"),
            )
        }

        val archive = openArchive(rawPath)
        return archive.use {
            val selected = mutableListOf<SelectedFile>()
            val targetKeys = LinkedHashMap<String, String>()
            val directoryDestinations = LinkedHashSet<File>()
            var selectedDeclaredBytes = 0L

            for (mapping in mappings) {
                val archivePath = mapping.entryPath
                val rawDestination = mapping.destinationPath
                if (archivePath.isEmpty() || archivePath.endsWith("/")) {
                    if (!rawDestination.replace('\\', '/').endsWith("/")) {
                        throw IllegalArgumentException("目录映射的 destinationPath 必须以 / 结尾: '$rawDestination'")
                    }
                    if (!archive.isDirectory(archivePath)) {
                        throw IllegalArgumentException("ZIP 中不存在目录: ${archivePath.ifEmpty { "<root>" }}")
                    }
                    val destination = resolvePath(rawDestination, "destinationPath")
                    if (destination.exists() && !destination.isDirectory) {
                        throw IllegalArgumentException("目录目标已存在且不是目录: ${destination.path}")
                    }
                    directoryDestinations.add(destination)
                    for (entry in archive.entries) {
                        if (archivePath.isEmpty() || entry.name.startsWith(archivePath)) {
                            if (entry.name == archivePath) continue
                            val suffix = if (archivePath.isEmpty()) entry.name else entry.name.substring(archivePath.length)
                            if (entry.directory) {
                                if (entry.symlink) {
                                    throw SecurityException("拒绝解压符号链接目录条目: '${entry.name}'")
                                }
                                val directorySuffix = suffix.removeSuffix("/")
                                if (directorySuffix.isNotEmpty()) {
                                    directoryDestinations.add(
                                        resolveChild(destination, safeRelativeEntry(directorySuffix)),
                                    )
                                }
                                continue
                            }
                            val safe = safeRelativeEntry(suffix)
                            val target = resolveChild(destination, safe)
                            addSelected(
                                archive, entry, target, targetKeys, selected,
                                mappingLabel = "$archivePath -> $rawDestination",
                            )
                            selectedDeclaredBytes = addBudget(selectedDeclaredBytes, entry.size)
                        }
                    }
                } else {
                    if (rawDestination.replace('\\', '/').endsWith("/")) {
                        throw IllegalArgumentException("文件映射的 destinationPath 不能以 / 结尾: '$rawDestination'")
                    }
                    val entry = archive.byName[archivePath]
                        ?: throw IllegalArgumentException("ZIP 中不存在条目: '$archivePath'")
                    if (entry.directory) {
                        throw IllegalArgumentException("'$archivePath' 是目录，destinationPath 应使用目录映射")
                    }
                    validateFileEntryName(entry.name)
                    val destination = resolvePath(rawDestination, "destinationPath")
                    addSelected(
                        archive, entry, destination, targetKeys, selected,
                        mappingLabel = "$archivePath -> $rawDestination",
                    )
                    selectedDeclaredBytes = addBudget(selectedDeclaredBytes, entry.size)
                }
            }

            if (selected.size > maxArchiveEntries) {
                throw IOException("解压条目数 ${selected.size} 超过预算 $maxArchiveEntries")
            }
            if (selectedDeclaredBytes > maxTotalUncompressed) {
                throw IOException("解压声明总大小 $selectedDeclaredBytes 超过预算 $maxTotalUncompressed")
            }
            checkCompressionRatio(archive.entries.filter { !it.directory }, "解压")
            checkCompressionRatio(selected.map { it.entry }, "解压")
            validateTargetConflicts(selected.map { it.target })
            validateDirectoryDestinationConflicts(selected.map { it.target }, directoryDestinations)
            for (item in selected) {
                archive.ensureReadable(item.entry, password)
                if (item.entry.symlink) {
                    throw SecurityException("拒绝解压符号链接条目: '${item.entry.name}'")
                }
                if (sameFile(item.target, archive.file)) {
                    throw SecurityException("解压目标不能是源 ZIP 本身: ${item.target.path}")
                }
                if (item.target.exists()) {
                    if (!overwrite) {
                        throw IllegalArgumentException("目标已存在，overwrite=false 拒绝覆盖: ${item.target.path}")
                    }
                    if (!item.target.isFile) {
                        throw IllegalArgumentException("目标已存在且不是普通文件: ${item.target.path}")
                    }
                }
                val parent = item.target.parentFile
                if (parent != null && parent.exists() && !parent.isDirectory) {
                    throw IllegalArgumentException("目标父路径不是目录: ${parent.path}")
                }
            }

            for (dir in directoryDestinations) {
                if (dir.exists() && !dir.isDirectory) {
                    throw IllegalArgumentException("目录目标已存在且不是目录: ${dir.path}")
                }
                val parent = dir.parentFile
                if (parent != null && parent.exists() && !parent.isDirectory) {
                    throw IllegalArgumentException("目录目标的父路径不是目录: ${parent.path}")
                }
                if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory) {
                    throw IOException("无法创建目标目录: ${dir.path}")
                }
            }
            var totalWritten = 0L
            val destinations = JsonArray()
            for (item in selected) {
                checkInterrupted()
                val target = item.target
                val parent = target.parentFile ?: throw IOException("目标路径没有父目录: ${target.path}")
                if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
                    throw IOException("无法创建目标目录: ${parent.path}")
                }
                if (parent.exists() && !parent.isDirectory) {
                    throw IOException("目标父路径不是目录: ${parent.path}")
                }
                val temp = File(parent, ".${target.name}.${UUID.randomUUID()}.part")
                try {
                    val remaining = (maxTotalUncompressed - totalWritten).coerceAtLeast(0L)
                    val budget = BudgetInputStream(
                        archive.openEntryStream(item.entry, password, verifyOnClose = true), remaining,
                        "解压未完成：实际写入超过总大小预算 $maxTotalUncompressed（条目 '${item.entry.name}'）",
                    )
                    var count = 0L
                    val crc = CRC32()
                    var readCompleted = false
                    try {
                        BufferedOutputStream(FileOutputStream(temp)).use { output ->
                            val buffer = ByteArray(IO_BUFFER)
                            while (true) {
                                checkInterrupted()
                                val n = budget.read(buffer)
                                if (n < 0) break
                                crc.update(buffer, 0, n)
                                output.write(buffer, 0, n)
                                count += n
                            }
                        }
                        readCompleted = true
                    } finally {
                        if (readCompleted) {
                            // Propagate zip4j's full-read CRC/HMAC authentication for AES and
                            // ZipCrypto; the temporary output is deleted by the outer catch.
                            budget.close()
                        } else {
                            try { budget.close() } catch (_: IOException) {}
                        }
                    }
                    if (item.entry.size >= 0 && count != item.entry.size) {
                        throw IOException("条目 '${item.entry.name}' 长度不符：声明 ${item.entry.size}，实际 $count")
                    }
                    // AE-2 AES entries intentionally use CRC=0; their HMAC is verified by
                    // zip4j when the stream is closed. AE-1/non-zero CRC values are still checked.
                    val crcIsMandatory = !ToolkitZipCrypto.isAes(item.entry.method) || item.entry.crc != 0L
                    if (crcIsMandatory && item.entry.crc >= 0 && crc.value != item.entry.crc) {
                        throw IOException("条目 '${item.entry.name}' CRC 校验失败，已删除本次临时文件")
                    }
                    totalWritten += count
                    if (totalWritten > maxTotalUncompressed) {
                        throw IOException("解压未完成：总大小超过预算 $maxTotalUncompressed")
                    }
                    installTemp(temp, target, overwrite || !target.exists())
                    destinations.add(target.path)
                } catch (e: Exception) {
                    temp.delete()
                    throw e
                }
            }
            val data = JsonObject().apply {
                addProperty("path", archive.file.path)
                addProperty("version", archive.version)
                addProperty("extractedFiles", selected.size)
                addProperty("completedFiles", selected.size)
                addProperty("extractedBytes", totalWritten)
                addProperty("createdDirectories", directoryDestinations.size)
                add("warnings", JsonArray())
                add("destinations", destinations)
                addProperty("overwrite", overwrite)
            }
            success(data)
        }
    }

    private fun addSelected(
        archive: Archive,
        entry: CentralEntry,
        target: File,
        targetKeys: MutableMap<String, String>,
        selected: MutableList<SelectedFile>,
        mappingLabel: String,
    ) {
        val key = pathKey(target)
        val previous = targetKeys.put(key, entry.name)
        if (previous != null) {
            throw IllegalArgumentException(
                "多个 ZIP 条目映射到同一目标: '${target.path}'（'$previous' 与 '${entry.name}'，$mappingLabel）",
            )
        }
        selected.add(SelectedFile(entry, target))
        if (sameFile(target, archive.file)) {
            throw SecurityException("解压目标不能是源 ZIP 本身: ${target.path}")
        }
    }

    private fun validateTargetConflicts(targets: List<File>) {
        val keys = targets.map { pathKey(it) }.toSet()
        for (target in targets) {
            var parent = target.parentFile
            while (parent != null && parent.path.length >= canonicalRoot.path.length) {
                if (pathKey(parent) in keys) {
                    throw IllegalArgumentException("目标路径冲突：'${parent.path}' 同时被当作文件和目录")
                }
                if (parent == canonicalRoot) break
                parent = parent.parentFile
            }
        }
    }

    private fun validateDirectoryDestinationConflicts(fileTargets: List<File>, directories: Collection<File>) {
        for (directory in directories) {
            for (fileTarget in fileTargets) {
                if (sameFile(directory, fileTarget) || isSameOrChild(directory, fileTarget)) {
                    throw IllegalArgumentException(
                        "目标路径冲突：目录 '${directory.path}' 与文件目标 '${fileTarget.path}' 重叠",
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // zip_create
    // ---------------------------------------------------------------------------------------------

    private data class ExpandedSource(
        val name: String,
        val directory: Boolean,
        val file: File?,
        val time: Long,
    )

    private fun zipCreate(args: JsonObject): JsonObject {
        val rawOutput = args.requiredString("outputPath")
        val sourcesJson = args.requiredArray("sources")
        val overwrite = args.requiredBoolean("overwrite")
        val level = args.requiredInt("level", 0, 9)
        val password = args.requiredString("password")
        if (sourcesJson.size() == 0) throw IllegalArgumentException("sources 不能为空")

        val output = resolvePath(rawOutput, "outputPath")
        if (output.exists() && output.isDirectory) {
            throw IllegalArgumentException("输出路径是目录: ${output.path}")
        }
        if (output.exists() && !overwrite) {
            throw IllegalArgumentException("输出文件已存在，overwrite=false 拒绝覆盖: ${output.path}")
        }
        val parent = output.parentFile ?: throw IllegalArgumentException("输出路径没有父目录: ${output.path}")
        if (!parent.exists() || !parent.isDirectory) {
            throw IllegalArgumentException("输出父目录必须已存在: ${parent.path}")
        }

        val mappings = sourcesJson.map { element ->
            val obj = element.asObject("sources[]")
            obj.requiredString("sourcePath") to obj.requiredString("entryPath")
        }
        val expanded = mutableListOf<ExpandedSource>()
        var sourceBytes = 0L
        for ((sourceRaw, entryRaw) in mappings) {
            val source = resolvePath(sourceRaw, "sourcePath")
            val rawNormalized = sourceRaw.replace('\\', '/')
            val sourceIsDir = source.isDirectory
            if (sourceIsDir && !rawNormalized.endsWith("/")) {
                throw IllegalArgumentException("目录源 sourcePath 必须以 / 结尾: '$sourceRaw'")
            }
            if (!sourceIsDir && rawNormalized.endsWith("/")) {
                throw IllegalArgumentException("文件源 sourcePath 不能以 / 结尾: '$sourceRaw'")
            }
            if (!source.exists()) throw IllegalArgumentException("源路径不存在: ${source.path}")
            if (sourceIsDir) {
                if (entryRaw.isNotEmpty() && !entryRaw.endsWith("/")) {
                    throw IllegalArgumentException("目录源的 entryPath 必须以 / 结尾或为空: '$entryRaw'")
                }
                if (isSameOrChild(output, source)) {
                    throw IllegalArgumentException("输出 ZIP 不能位于源目录内: ${output.path}")
                }
                addExpandedDir(source, entryRaw, expanded)
            } else {
                if (!source.isFile) throw IllegalArgumentException("不支持的源文件类型: ${source.path}")
                if (entryRaw.isEmpty() || entryRaw.endsWith("/")) {
                    throw IllegalArgumentException("文件源的 entryPath 必须是非空文件路径: '$entryRaw'")
                }
                if (sameFile(output, source)) {
                    throw IllegalArgumentException("输出 ZIP 不能同时作为输入源: ${output.path}")
                }
                ensureNoSymlink(source, "源文件")
                expanded.add(ExpandedSource(entryRaw, false, source, source.lastModified()))
            }
        }
        sourceBytes = expanded.sumOf { it.file?.length() ?: 0L }
        if (expanded.size > maxArchiveEntries) {
            throw IOException("输出条目数 ${expanded.size} 超过预算 $maxArchiveEntries")
        }
        if (sourceBytes > maxCreateBytes) {
            throw IOException("输出源数据 $sourceBytes 字节超过预算 $maxCreateBytes")
        }
        validateExpandedNames(expanded)
        if (expanded.any { it.file != null && sameFile(it.file, output) }) {
            throw IllegalArgumentException("输出 ZIP 不能同时作为输入源: ${output.path}")
        }

        val plans = expanded.map {
            OutputEntry(
                name = it.name,
                directory = it.directory,
                sourceFile = it.file,
                method = if (it.directory) METHOD_STORED else if (level == 0) METHOD_STORED else METHOD_DEFLATED,
                time = it.time,
            )
        }
        val temp = File(parent, ".${output.name}.${UUID.randomUUID()}.tmp")
        try {
            writeOutputZip(temp, plans, null, level, password)
            verifyTempNames(temp, plans.map { it.name })
            installTemp(temp, output, overwrite || !output.exists())
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
        val outputArchive = openArchive(output)
        return outputArchive.use {
            val data = JsonObject().apply {
                addProperty("path", outputArchive.file.path)
                addProperty("version", outputArchive.version)
                addProperty("size", outputArchive.file.length())
                addProperty("changed", true)
                add("warnings", JsonArray())
                addProperty("entries", plans.size)
                addProperty("files", plans.count { !it.directory })
                addProperty("bytes", plans.filter { !it.directory }.sumOf { it.sourceFile?.length() ?: 0L })
                addProperty("overwrite", overwrite)
                addProperty("level", level)
            }
            success(data)
        }
    }

    private fun addExpandedDir(source: File, entryRoot: String, out: MutableList<ExpandedSource>) {
        checkInterrupted()
        ensureNoSymlink(source, "源目录")
        if (entryRoot.isNotEmpty()) {
            out.add(ExpandedSource(entryRoot, true, null, source.lastModified()))
            if (out.size > maxArchiveEntries) {
                throw IOException("输出条目数超过预算 $maxArchiveEntries")
            }
        }
        val children = source.listFiles()?.sortedBy { it.name }
            ?: throw IOException("无法读取源目录: ${source.path}")
        for (child in children) {
            ensureNoSymlink(child, "源目录成员")
            val childEntry = entryRoot + child.name + if (child.isDirectory) "/" else ""
            if (child.isDirectory) {
                addExpandedDir(child, childEntry, out)
            } else if (child.isFile) {
                out.add(ExpandedSource(childEntry, false, child, child.lastModified()))
                if (out.size > maxArchiveEntries) {
                    throw IOException("输出条目数超过预算 $maxArchiveEntries")
                }
            } else {
                throw IOException("不支持的源文件类型: ${child.path}")
            }
        }
    }

    private fun validateExpandedNames(entries: List<ExpandedSource>) {
        val seen = HashSet<String>()
        for (entry in entries) {
            if (entry.name.isEmpty()) throw IllegalArgumentException("ZIP 条目名不能为空")
            if (entry.name.indexOf('\u0000') >= 0) throw SecurityException("ZIP 条目名不能包含 NUL")
            if (entry.directory != entry.name.endsWith("/")) {
                throw IllegalArgumentException("目录条目必须以 / 结尾: '${entry.name}'")
            }
            if (!seen.add(entry.name)) {
                throw IllegalArgumentException("映射产生重复 ZIP 条目: '${entry.name}'")
            }
        }
        validateFileDirectoryConflicts(entries.map { it.name to it.directory })
    }

    private fun validateFileDirectoryConflicts(entries: List<Pair<String, Boolean>>) {
        val fileNames = entries.filter { !it.second }.map { it.first }.toSet()
        for ((name, directory) in entries) {
            if (directory && name.removeSuffix("/") in fileNames) {
                throw IllegalArgumentException("ZIP 条目同时作为文件和目录: '${name.removeSuffix("/")}' 与 '$name'")
            }
            var parent = if (directory) parentDirOfDir(name) else parentDirOfFile(name)
            while (parent.isNotEmpty()) {
                val base = parent.removeSuffix("/")
                if (base in fileNames) {
                    throw IllegalArgumentException("ZIP 条目冲突：'$base' 是文件，不能同时作为 '$name' 的目录")
                }
                parent = parentDirOfDir(parent)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // zip_update
    // ---------------------------------------------------------------------------------------------

    private sealed class UpdatePlan {
        data class Add(val entries: List<ExpandedSource>, val overwrite: Boolean, val index: Int) : UpdatePlan()
        data class DeleteFile(val name: String, val index: Int) : UpdatePlan()
        data class DeleteDir(val name: String, val recursive: Boolean, val index: Int) : UpdatePlan()
        data class RenameFile(val from: String, val to: String, val index: Int) : UpdatePlan()
        data class RenameDir(val from: String, val to: String, val merge: Boolean, val index: Int) : UpdatePlan()
    }

    private data class UpdateAnalysis(
        val output: List<OutputEntry>,
        val added: Int,
        val deleted: Int,
        val renamed: Int,
        val replaced: Int,
    )

    private fun zipUpdate(args: JsonObject): JsonObject {
        val rawPath = args.requiredString("path")
        val operationsJson = args.requiredArray("operations")
        val password = args.requiredString("password")
        // targetVersion is an optional extension. Omitting it must remain a normal MT-contract
        // call; an empty string is treated as omitted for clients that serialize optionals that way.
        val targetElement = args.get("targetVersion")
        val providedTarget = if (targetElement != null && !targetElement.isJsonNull) {
            args.requiredString("targetVersion")
        } else {
            ""
        }
        val targetVersion = providedTarget.takeIf { it.isNotEmpty() }
        if (operationsJson.size() == 0) throw IllegalArgumentException("operations 不能为空")

        val archive = openArchive(rawPath)
        val analysis: UpdateAnalysis
        val temp: File
        val expectedVersion = targetVersion ?: archive.version
        try {
            if (targetVersion != null && archive.version != targetVersion) {
                throw IllegalArgumentException(
                    "ZIP 版本冲突：targetVersion=$targetVersion，当前版本=${archive.version}；" +
                        "请重新 zip_list/zip_test 后重试，旧状态未写入",
                )
            }
            for (entry in archive.entries) {
                if (entry.method != METHOD_STORED && entry.method != METHOD_DEFLATED &&
                    !ToolkitZipCrypto.isAes(entry.method)
                ) {
                    throw IOException("ZIP 条目 '${entry.name}' 使用不支持的压缩算法 ${entry.method}，原档未改动")
                }
                if (ToolkitZipCrypto.isAes(entry.method)) {
                    val extra = entry.aesExtra
                        ?: throw IOException("AES 条目 '${entry.name}' 缺少 0x9901 extra，拒绝重写，原档未改动")
                    ToolkitZipCrypto.aesCompressionMethod(extra)
                }
                if (entry.symlink) {
                    throw SecurityException("ZIP 含符号链接条目 '${entry.name}'，拒绝重写，原档未改动")
                }
            }
            val plans = parseUpdateOperations(operationsJson, archive)
            analysis = analyzeUpdate(archive, plans)
            val parent = archive.file.parentFile ?: throw IOException("ZIP 文件没有父目录")
            temp = File(parent, ".${archive.file.name}.${UUID.randomUUID()}.tmp")
            try {
                writeOutputZip(temp, analysis.output, archive, 6, password)
                verifyTempNames(temp, analysis.output.map { it.name })
            } catch (e: Exception) {
                temp.delete()
                throw e
            }
        } finally {
            archive.close()
        }
        try {
            // Even without targetVersion, the source must still be byte-for-byte the version this
            // analysis was based on; otherwise a concurrent writer could be silently overwritten.
            val current = scanCentral(archive.file)
            if (current.version != expectedVersion) {
                throw IllegalArgumentException(
                    "ZIP 在写入期间被其他修改更新（版本 ${current.version}），已放弃本次修改并保留原档",
                )
            }
            installTemp(temp, archive.file, true)
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
        val outputArchive = openArchive(archive.file)
        return outputArchive.use {
            val data = JsonObject().apply {
                addProperty("path", outputArchive.file.path)
                addProperty("version", outputArchive.version)
                addProperty("size", outputArchive.file.length())
                addProperty("changed", true)
                add("warnings", JsonArray())
                addProperty("entries", outputArchive.entries.size)
                addProperty("added", analysis.added)
                addProperty("deleted", analysis.deleted)
                addProperty("renamed", analysis.renamed)
                addProperty("replaced", analysis.replaced)
                addProperty("targetVersion", expectedVersion)
            }
            success(data)
        }
    }

    private fun parseUpdateOperations(array: JsonArray, archive: Archive): List<UpdatePlan> {
        val result = mutableListOf<UpdatePlan>()
        array.forEachIndexed { index, element ->
            val obj = element.asObject("operations[$index]")
            val type = obj.requiredString("type")
            when (type) {
                "add" -> {
                    val sourceRaw = obj.requiredString("sourcePath")
                    val entryRaw = obj.requiredString("entryPath")
                    val overwrite = obj.requiredBoolean("overwrite")
                    val source = resolvePath(sourceRaw, "operations[$index].sourcePath")
                    val normalized = sourceRaw.replace('\\', '/')
                    val isDir = source.isDirectory
                    if (isDir && !normalized.endsWith("/")) {
                        throw IllegalArgumentException("operations[$index] 目录源 sourcePath 必须以 / 结尾")
                    }
                    if (!isDir && normalized.endsWith("/")) {
                        throw IllegalArgumentException("operations[$index] 文件源 sourcePath 不能以 / 结尾")
                    }
                    if (!source.exists()) throw IllegalArgumentException("operations[$index] 源路径不存在: ${source.path}")
                    val entries = mutableListOf<ExpandedSource>()
                    if (isDir) {
                        if (entryRaw.isNotEmpty() && !entryRaw.endsWith("/")) {
                            throw IllegalArgumentException("operations[$index] 目录 entryPath 必须以 / 结尾或为空")
                        }
                        if (isSameOrChild(archive.file, source)) {
                            throw IllegalArgumentException("原 ZIP 不能作为自己的输入目录: ${archive.file.path}")
                        }
                        addExpandedDir(source, entryRaw, entries)
                    } else {
                        if (!source.isFile) throw IllegalArgumentException("operations[$index] 不支持的源类型: ${source.path}")
                        if (entryRaw.isEmpty() || entryRaw.endsWith("/")) {
                            throw IllegalArgumentException("operations[$index] 文件 entryPath 必须是非空文件路径")
                        }
                        ensureNoSymlink(source, "源文件")
                        if (sameFile(source, archive.file)) {
                            throw IllegalArgumentException("原 ZIP 不能同时作为输入源: ${archive.file.path}")
                        }
                        entries.add(ExpandedSource(entryRaw, false, source, source.lastModified()))
                    }
                    result.add(UpdatePlan.Add(entries, overwrite, index))
                }
                "delete" -> {
                    val name = obj.requiredString("entryPath")
                    if (name.isEmpty()) throw IllegalArgumentException("operations[$index] entryPath 不能为空")
                    if (name.endsWith("/")) {
                        val recursive = obj.requiredBoolean("recursive")
                        result.add(UpdatePlan.DeleteDir(name, recursive, index))
                    } else {
                        result.add(UpdatePlan.DeleteFile(name, index))
                    }
                }
                "rename" -> {
                    val from = obj.requiredString("entryPath")
                    val to = obj.requiredString("newEntryPath")
                    if (from.isEmpty() || to.isEmpty()) {
                        throw IllegalArgumentException("operations[$index] rename 路径不能为空")
                    }
                    if (from.endsWith("/")) {
                        if (!to.endsWith("/")) {
                            throw IllegalArgumentException("operations[$index] 目录 rename 的 newEntryPath 必须以 / 结尾")
                        }
                        if (to.startsWith(from)) {
                            throw IllegalArgumentException("operations[$index] 目录不能重命名到自己的后代: '$to'")
                        }
                        result.add(UpdatePlan.RenameDir(from, to, obj.requiredBoolean("mergeDirectories"), index))
                    } else {
                        if (to.endsWith("/")) {
                            throw IllegalArgumentException("operations[$index] 文件 rename 的 newEntryPath 不能以 / 结尾")
                        }
                        result.add(UpdatePlan.RenameFile(from, to, index))
                    }
                }
                else -> throw IllegalArgumentException("operations[$index] 未知操作类型: '$type'")
            }
        }
        return result
    }

    private fun analyzeUpdate(archive: Archive, plans: List<UpdatePlan>): UpdateAnalysis {
        val original = archive.entries
        val originalByName = archive.byName
        val affected = LinkedHashMap<String, UpdatePlan>()
        val removed = LinkedHashSet<String>()
        val rename = LinkedHashMap<String, String>()
        val mergeSkipped = HashSet<String>()
        val produced = LinkedHashMap<String, Pair<Boolean, Int>>() // name -> directory, op index
        val addEntries = mutableListOf<ExpandedSource>()
        val replaced = LinkedHashSet<String>()
        val sourceRanges = mutableListOf<Pair<String, Int>>()
        var deletedCount = 0
        var renamedCount = 0

        fun markAffected(entry: CentralEntry, plan: UpdatePlan) {
            val old = affected.put(entry.name, plan)
            if (old != null) {
                throw IllegalArgumentException("条目 '${entry.name}' 被多个操作修改，拒绝链式/重复修改")
            }
        }

        fun overlapsSource(name: String, ownIndex: Int): Boolean = sourceRanges.any { (source, index) ->
            if (index == ownIndex) return@any false
            when {
                source.endsWith("/") -> name == source || name.startsWith(source)
                else -> name == source
            }
        }

        // Validate delete/rename operations against the original archive. Every operation uses
        // original names, so overlapping source ranges are rejected before any write.
        for (plan in plans) {
            when (plan) {
                is UpdatePlan.DeleteFile -> {
                    val entry = originalByName[plan.name]
                        ?: throw IllegalArgumentException("operations[${plan.index}] 要删除的文件不存在: '${plan.name}'")
                    if (entry.directory) {
                        throw IllegalArgumentException("operations[${plan.index}] '${plan.name}' 是目录，请使用目录删除")
                    }
                    markAffected(entry, plan)
                    removed.add(entry.name)
                    sourceRanges.add(entry.name to plan.index)
                    deletedCount++
                }
                is UpdatePlan.DeleteDir -> {
                    val entries = original.filter { it.name == plan.name || it.name.startsWith(plan.name) }
                    if (entries.isEmpty()) {
                        throw IllegalArgumentException("operations[${plan.index}] 要删除的目录不存在: '${plan.name}'")
                    }
                    if (!plan.recursive && entries.any { it.name != plan.name }) {
                        throw IllegalArgumentException("operations[${plan.index}] 目录非空，recursive=false 拒绝删除: '${plan.name}'")
                    }
                    entries.forEach { markAffected(it, plan); removed.add(it.name) }
                    sourceRanges.add(plan.name to plan.index)
                    deletedCount++
                }
                is UpdatePlan.RenameFile -> {
                    val entry = originalByName[plan.from]
                        ?: throw IllegalArgumentException("operations[${plan.index}] 要重命名的文件不存在: '${plan.from}'")
                    if (entry.directory) {
                        throw IllegalArgumentException("operations[${plan.index}] '${plan.from}' 是目录，请使用目录重命名")
                    }
                    markAffected(entry, plan)
                    rename[entry.name] = plan.to
                    sourceRanges.add(entry.name to plan.index)
                    renamedCount++
                }
                is UpdatePlan.RenameDir -> {
                    val entries = original.filter { it.name == plan.from || it.name.startsWith(plan.from) }
                    if (entries.isEmpty()) {
                        throw IllegalArgumentException("operations[${plan.index}] 要重命名的目录不存在: '${plan.from}'")
                    }
                    entries.forEach { markAffected(it, plan); rename[it.name] = plan.to + it.name.substring(plan.from.length) }
                    sourceRanges.add(plan.from to plan.index)
                    renamedCount++
                }
                is UpdatePlan.Add -> {
                    // Expanded after original operations so conflicts are visible.
                }
            }
        }

        // Validate add mappings and collect their entries.
        for (plan in plans) {
            if (plan !is UpdatePlan.Add) continue
            val seenInPlan = HashSet<String>()
            for (entry in plan.entries) {
                if (entry.name.indexOf('\u0000') >= 0) throw SecurityException("ZIP 条目名不能包含 NUL")
                if (entry.directory != entry.name.endsWith("/")) {
                    throw IllegalArgumentException("目录条目必须以 / 结尾: '${entry.name}'")
                }
                if (!seenInPlan.add(entry.name)) {
                    throw IllegalArgumentException("operations[${plan.index}] 产生重复条目: '${entry.name}'")
                }
                if (entry.name in affected || overlapsSource(entry.name, plan.index)) {
                    throw IllegalArgumentException(
                        "operations[${plan.index}] 目标 '${entry.name}' 与其它删除/重命名范围竞争，拒绝写入",
                    )
                }
                val existing = originalByName[entry.name]
                if (existing != null) {
                    if (existing.directory) {
                        if (!entry.directory) {
                            throw IllegalArgumentException("目标 '${entry.name}' 在原始 ZIP 中是目录，不能替换为文件")
                        }
                        mergeSkipped.add(entry.name)
                        continue
                    }
                    if (!entry.directory) {
                        if (!plan.overwrite) {
                            throw IllegalArgumentException(
                                "operations[${plan.index}] 目标 '${entry.name}' 已存在，overwrite=false 拒绝替换",
                            )
                        }
                        if (entry.name in removed) {
                            throw IllegalArgumentException("目标 '${entry.name}' 与其它删除/重命名操作竞争")
                        }
                        replaced.add(entry.name)
                    } else {
                        throw IllegalArgumentException("目标 '${entry.name}' 在原始 ZIP 中是文件，不能作为目录")
                    }
                }
                val previous = produced.put(entry.name, entry.directory to plan.index)
                if (previous != null) {
                    throw IllegalArgumentException("多个操作产生同一目标 '${entry.name}'，拒绝写入")
                }
                addEntries.add(entry)
            }
        }
        val addedCount = addEntries.count { !it.directory }

        // Rename output names must not collide with untouched originals or other produced names.
        for ((from, to) in rename) {
            val source = originalByName[from] ?: continue
            val plan = affected[from] ?: continue
            val owner = opIndex(plan)
            if (overlapsSource(to, owner)) {
                throw IllegalArgumentException("重命名目标 '$to' 位于其它删除/重命名范围内，存在竞争")
            }
            val targetExisting = originalByName[to]
            if (targetExisting != null) {
                if (source.directory && targetExisting.directory && plan is UpdatePlan.RenameDir && plan.merge) {
                    mergeSkipped.add(to)
                } else {
                    throw IllegalArgumentException("重命名目标 '$to' 已存在，拒绝覆盖现有条目")
                }
            }
            val previous = produced.put(to, source.directory to owner)
            if (previous != null) {
                throw IllegalArgumentException("重命名目标 '$to' 与其它操作目标冲突")
            }
        }

        // Reject produced ranges that overlap another produced range (competing directories).
        val producedDirs = produced.filter { it.value.first }.keys
        for (dir in producedDirs) {
            for (name in produced.keys) {
                if (name == dir) continue
                if (name.startsWith(dir)) {
                    val owner = produced[dir]!!.second
                    val other = produced[name]!!.second
                    if (owner != other) {
                        throw IllegalArgumentException("目标范围 '$dir' 与 '$name' 来自不同操作，存在竞争")
                    }
                }
            }
        }

        // Compose final output in original order, then additions.
        val output = mutableListOf<OutputEntry>()
        for (entry in original) {
            if (entry.name in removed || entry.name in replaced) continue
            val newName = rename[entry.name] ?: entry.name
            if (newName in mergeSkipped) continue
            output.add(OutputEntry(
                name = newName,
                directory = entry.directory,
                original = entry,
                method = entry.method,
                time = archive.timeOf(entry),
                comment = archive.commentOf(entry),
            ))
        }
        for (entry in addEntries) {
            if (entry.directory && mergeSkipped.contains(entry.name)) continue
            if (output.any { it.name == entry.name }) {
                throw IllegalArgumentException("输出条目重复: '${entry.name}'")
            }
            val method = if (entry.directory) {
                METHOD_STORED
            } else {
                val originalEntry = originalByName[entry.name]
                if (originalEntry != null && !originalEntry.directory) {
                    // AES entries store method 99 in the ZIP header; the real Stored/Deflate
                    // choice lives in the AES extra. Replacements must preserve that real method.
                    originalEntry.actualMethod
                } else {
                    METHOD_DEFLATED
                }
            }
            output.add(OutputEntry(
                name = entry.name,
                directory = entry.directory,
                sourceFile = entry.file,
                method = method,
                time = entry.time,
            ))
        }
        validateOutputNames(output)
        if (output.size > maxArchiveEntries) {
            throw IOException("输出条目数 ${output.size} 超过预算 $maxArchiveEntries")
        }
        return UpdateAnalysis(
            output = output,
            added = addedCount,
            deleted = deletedCount,
            renamed = renamedCount,
            replaced = replaced.size,
        )
    }

    private fun opIndex(plan: UpdatePlan): Int = when (plan) {
        is UpdatePlan.Add -> plan.index
        is UpdatePlan.DeleteFile -> plan.index
        is UpdatePlan.DeleteDir -> plan.index
        is UpdatePlan.RenameFile -> plan.index
        is UpdatePlan.RenameDir -> plan.index
    }

    // ---------------------------------------------------------------------------------------------
    // ZIP reading / central directory
    // ---------------------------------------------------------------------------------------------

    private data class CentralEntry(
        val name: String,
        val flags: Int,
        val method: Int,
        val actualMethod: Int,
        val crc: Long,
        val compressedSize: Long,
        val size: Long,
        val externalAttributes: Long,
        val directory: Boolean,
        val encrypted: Boolean,
        val symlink: Boolean,
        val localOffset: Long,
        val versionMadeBy: Int,
        val aesExtra: ByteArray?,
        val commentBytes: ByteArray,
    )

    private data class PendingCentral(
        val nameBytes: ByteArray,
        val flags: Int,
        val method: Int,
        val actualMethod: Int,
        val crc: Long,
        val compressedSize: Long,
        val size: Long,
        val externalAttributes: Long,
        val localOffset: Long,
        val versionMadeBy: Int,
        val aesExtra: ByteArray?,
        val commentBytes: ByteArray,
    )

    private inner class Archive(
        val file: File,
        val version: String,
        val entries: List<CentralEntry>,
        val byName: Map<String, CentralEntry>,
        val nameCharset: Charset,
        private val zip: ZipFile?,
        private val zipEntries: Map<String, ZipEntry>,
    ) : Closeable {
        val dirNames: Set<String> = collectDirectoryNames(entries)
        val children: Map<String, List<String>> = collectChildren(entries)
        private val aggregates: Map<String, LongArray> = collectAggregates(entries)

        fun isDirectory(name: String): Boolean =
            name.isEmpty() || name in dirNames || children.containsKey(name)

        fun timeOf(entry: CentralEntry): Long = zipEntries[entry.name]?.time ?: System.currentTimeMillis()
        fun commentOf(entry: CentralEntry): String? = zipEntries[entry.name]?.comment

        fun listNames(entryPath: String, recursive: Boolean): List<String> {
            val result = mutableListOf<String>()
            val first = children[entryPath] ?: return result
            if (!recursive) {
                for (child in first) result.add(child.substring(entryPath.length))
                return result
            }
            // Iterative depth-first traversal: a hostile archive can contain deeply nested
            // directory names, so recursion here would risk StackOverflowError.
            val stack = java.util.ArrayDeque<Iterator<String>>()
            stack.addLast(first.iterator())
            while (stack.isNotEmpty()) {
                val iterator = stack.peekLast()
                if (!iterator.hasNext()) {
                    stack.removeLast()
                    continue
                }
                val child = iterator.next()
                // Every page item is relative to the requested directory, not to the
                // immediate parent; otherwise recursive pages lose their parent prefix.
                result.add(child.substring(entryPath.length))
                if (child.endsWith("/")) {
                    val grandChildren = children[child]
                    if (grandChildren != null && grandChildren.isNotEmpty()) {
                        stack.addLast(grandChildren.iterator())
                    }
                }
            }
            return result
        }

        fun detailLine(relative: String, entryPath: String): String {
            val full = entryPath + relative
            val directory = full.endsWith("/")
            val size: Long
            val compressed: Long
            val encryption: String
            if (directory) {
                val aggregate = aggregates[full] ?: LongArray(2)
                size = aggregate[0]
                compressed = aggregate[1]
                encryption = "-"
            } else {
                val entry = byName[full]
                size = entry?.size ?: -1
                compressed = entry?.compressedSize ?: -1
                encryption = if (entry?.encrypted == true) "e" else "-"
            }
            return "${unavailable(size)} ${unavailable(compressed)} $encryption $relative"
        }

        fun fileEntry(name: String): CentralEntry {
            if (name.isEmpty()) throw IllegalArgumentException("entryPath 不能为空")
            val entry = byName[name] ?: throw IllegalArgumentException("ZIP 中不存在条目: '$name'")
            if (entry.directory) throw IllegalArgumentException("'$name' 是目录，不能读取内容")
            return entry
        }

        fun ensureReadable(entry: CentralEntry, password: String) {
            if (entry.encrypted) {
                if (password.isEmpty()) {
                    throw IOException("条目 '${entry.name}' 已加密，需要非空 password 才能解密读取")
                }
                if (entry.method != METHOD_STORED && entry.method != METHOD_DEFLATED &&
                    !ToolkitZipCrypto.isAes(entry.method)
                ) {
                    throw IOException(
                        "加密条目 '${entry.name}' 使用不支持的压缩/加密算法 ${entry.method}；" +
                            "仅支持 ZipCrypto(Stored/Deflate) 与 AES-256",
                    )
                }
                if (ToolkitZipCrypto.isAes(entry.method)) {
                    val extra = entry.aesExtra
                        ?: throw IOException("AES 条目 '${entry.name}' 缺少 0x9901 extra，无法解密")
                    ToolkitZipCrypto.aesCompressionMethod(extra)
                }
            } else if (entry.method != METHOD_STORED && entry.method != METHOD_DEFLATED) {
                throw IOException(
                    "条目 '${entry.name}' 使用不支持的压缩算法 ${entry.method}；仅支持 Stored(0) 与 Deflate(8)",
                )
            }
            if (entry.symlink) {
                // Callers that can copy raw content (test) may allow this; read/extract reject.
            }
        }

        fun openEntryStream(
            entry: CentralEntry,
            password: String,
            verifyOnClose: Boolean,
        ): InputStream {
            if (entry.encrypted) {
                return ToolkitZipCrypto.openEntry(file, entry.name, nameCharset, password, verifyOnClose)
            }
            // Some ZIP implementations reject the whole central directory if any member is
            // encrypted. zip4j can also read the plain members of such mixed archives.
            val zipFile = zip ?: return ToolkitZipCrypto.openEntry(file, entry.name, nameCharset, password, verifyOnClose)
            val ze = zipEntries[entry.name]
                ?: throw IOException("ZIP 条目 '${entry.name}' 无法在目录中定位")
            return try {
                zipFile.getInputStream(ze)
            } catch (e: IOException) {
                throw IOException("无法读取 ZIP 条目 '${entry.name}': ${e.message}", e)
            }
        }

        fun readTextPage(
            entry: CentralEntry,
            password: String,
            requestedEncoding: String,
            startLine: Long,
            startColumn: Long,
            lineLimit: Int,
            maxChars: Int,
        ): TextPage {
            val input = BufferedInputStream(openEntryStream(entry, password, verifyOnClose = false))
            val prefix = readPrefix(input, 8192)
            val bom = detectBom(prefix)
            val selection = selectEncoding(prefix, bom, requestedEncoding)
            val body = ByteArrayInputStream(prefix, selection.bomLength, prefix.size - selection.bomLength)
            val combined = SequenceInputStream(body, input)
            val budget = BudgetInputStream(
                combined, maxReadBytes,
                "文本读取未完成：本次实际解压超过预算 $maxReadBytes 字节（条目 '${entry.name}'）",
            )
            return InputStreamReader(budget, selection.charset).use { reader ->
                readText(reader, selection.token, startLine, startColumn, lineLimit, maxChars)
            }
        }

        fun readBytePage(entry: CentralEntry, password: String, offset: Long, maxBytes: Int): BytePage {
            val input = BudgetInputStream(
                openEntryStream(entry, password, verifyOnClose = false), maxReadBytes,
                "字节读取未完成：本次实际解压超过预算 $maxReadBytes 字节（条目 '${entry.name}'）",
            )
            try {
                var skipped = 0L
                val skipBuffer = ByteArray(IO_BUFFER)
                while (skipped < offset) {
                    checkInterrupted()
                    val want = minOf((offset - skipped), skipBuffer.size.toLong()).toInt()
                    val n = input.read(skipBuffer, 0, want)
                    if (n < 0) throw IOException("条目 '${entry.name}' 在偏移 $offset 前结束（实际 $skipped）")
                    skipped += n
                }
                val pageBytes = ByteArray(maxBytes)
                var count = 0
                while (count < maxBytes) {
                    checkInterrupted()
                    val n = input.read(pageBytes, count, maxBytes - count)
                    if (n < 0) break
                    count += n
                }
                var reachedEof = false
                if (count < maxBytes) {
                    reachedEof = true
                } else if (entry.size < 0) {
                    val probe = input.read()
                    if (probe < 0) reachedEof = true else pageBytes[count - 1] = pageBytes[count - 1]
                }
                val next = offset + count
                val total = when {
                    entry.size >= 0 -> entry.size
                    reachedEof -> next
                    else -> -1L
                }
                val hasMore = when {
                    total >= 0 -> next < total
                    else -> !reachedEof
                }
                return BytePage(offset, toHex(pageBytes, count), next, total, hasMore)
            } finally {
                try { input.close() } catch (_: IOException) {}
            }
        }

        override fun close() {
            zip?.close()
        }
    }

    private data class ScannedArchive(
        val entries: List<CentralEntry>,
        val version: String,
        val nameCharset: Charset,
    )

    private fun openArchive(rawPath: String): Archive =
        openArchive(resolvePath(rawPath, "path"))

    private fun openArchive(file: File): Archive {
        if (!file.exists()) throw IOException("ZIP 文件不存在: ${file.path}")
        if (!file.isFile) throw IOException("ZIP 路径不是普通文件: ${file.path}")
        if (file.length() <= 0) throw IOException("ZIP 文件为空: ${file.path}")
        val scanned = scanCentral(file)
        if (scanned.entries.size > maxArchiveEntries) {
            throw IOException("ZIP 条目数 ${scanned.entries.size} 超过预算 $maxArchiveEntries")
        }
        val zip: ZipFile? = try {
            openZipFile(file, scanned.nameCharset)
        } catch (e: IOException) {
            // Modern JDKs refuse to open a ZIP that contains any encrypted entry. Listing and
            // diagnostics only need the central directory, so keep those available and fail
            // later with the actual "encrypted" reason if content is requested.
            if (scanned.entries.any { it.encrypted }) {
                null
            } else {
                throw IOException("无法打开 ZIP（可能损坏、分卷、加密或使用不支持的算法）: ${e.message}", e)
            }
        }
        if (zip == null) {
            return Archive(
                file, scanned.version, scanned.entries, scanned.entries.associateBy { it.name },
                scanned.nameCharset, null, emptyMap(),
            )
        }
        try {
            val zipEntries = LinkedHashMap<String, ZipEntry>()
            val iterator = zip.entries()
            while (iterator.hasMoreElements()) {
                val ze = iterator.nextElement()
                if (zipEntries.put(ze.name, ze) != null) {
                    throw IOException("ZIP 含重复条目 '${ze.name}'，拒绝继续（名称歧义）")
                }
            }
            if (zipEntries.size != scanned.entries.size) {
                throw IOException(
                    "ZIP 中央目录条目数不一致（目录 ${scanned.entries.size}，读取 ${zipEntries.size}）；" +
                        "文件名编码不受支持或结构已损坏",
                )
            }
            for (entry in scanned.entries) {
                if (!zipEntries.containsKey(entry.name)) {
                    throw IOException(
                        "ZIP 条目 '${entry.name}' 无法按 ${scanned.nameCharset.name()} 名称定位；请确认文件名编码",
                    )
                }
            }
            return Archive(
                file, scanned.version, scanned.entries, scanned.entries.associateBy { it.name },
                scanned.nameCharset, zip, zipEntries,
            )
        } catch (e: Exception) {
            zip.close()
            throw e
        }
    }

    /**
     * [ZipFile]'s Charset constructor is API 24+, but this project still supports API 21.
     * UTF-8 archives therefore use the API-1 constructor (whose default is UTF-8); legacy
     * GBK names only use the Charset constructor reflectively on newer systems.
     */
    private fun openZipFile(file: File, charset: Charset): ZipFile {
        if (charset.name().equals("UTF-8", ignoreCase = true)) return ZipFile(file)
        return try {
            val constructor = ZipFile::class.java.getConstructor(File::class.java, Charset::class.java)
            constructor.newInstance(file, charset) as ZipFile
        } catch (e: NoSuchMethodException) {
            throw IOException(
                "当前 Android 版本（低于 API 24）不支持以 ${charset.name()} 解码 ZIP 条目名；" +
                    "请将 ZIP 条目名另存为 UTF-8",
                e,
            )
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.cause
            if (cause is IOException) throw cause
            throw IOException("无法以 ${charset.name()} 打开 ZIP: ${cause?.message ?: e.message}", cause)
        } catch (e: ReflectiveOperationException) {
            throw IOException("无法以 ${charset.name()} 打开 ZIP: ${e.message}", e)
        }
    }

    private fun scanCentral(file: File): ScannedArchive {
        val length = file.length()
        if (length < 22L) throw IOException("不是有效的 ZIP：文件过小或为空")
        val tailLength = minOf(length, 65_557L + 20L + 56L)
        val tailStart = length - tailLength
        val tail = ByteArray(tailLength.toInt())
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(tailStart)
            raf.readFully(tail)
        }
        if (tailStart == 0L && u32(tail, 0) == SIG_SPANNING) {
            throw IOException("不支持分卷 ZIP（检测到 spanning marker）")
        }
        var eocd = -1
        var i = tail.size - 22
        while (i >= 0) {
            if (u32(tail, i) == SIG_EOCD) {
                val commentLength = u16(tail, i + 20)
                if (i + 22 + commentLength == tail.size) {
                    eocd = i
                    break
                }
            }
            i--
        }
        if (eocd < 0) {
            throw IOException("不是有效的 ZIP：未找到中央目录结尾（可能为分卷 ZIP 或不完整文件）")
        }
        val eocdAbsolute = tailStart + eocd
        val disk = u16(tail, eocd + 4)
        val cdDisk = u16(tail, eocd + 6)
        val entriesOnDisk = u16(tail, eocd + 8)
        var totalEntries = u16(tail, eocd + 10).toLong()
        var cdSize = u32(tail, eocd + 12)
        var cdOffset = u32(tail, eocd + 16)
        val needsZip64 = totalEntries == 0xFFFFL || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL
        var cdEnd = eocdAbsolute
        if (disk != 0 || cdDisk != 0 || (entriesOnDisk != 0xFFFF && entriesOnDisk.toLong() != totalEntries)) {
            throw IOException("不支持分卷 ZIP（中央目录记录了多个磁盘）")
        }
        if (needsZip64) {
            val locatorOffset = eocdAbsolute - 20
            if (locatorOffset < 0) throw IOException("ZIP64 目录缺少 locator")
            val locator = ByteArray(20)
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(locatorOffset)
                raf.readFully(locator)
            }
            if (u32(locator, 0) != SIG_ZIP64_LOCATOR) {
                throw IOException("ZIP64 目录缺少 locator 签名")
            }
            val z64Offset = u64(locator, 8)
            if (z64Offset < 0 || z64Offset > length - 56) {
                throw IOException("ZIP64 目录偏移无效")
            }
            val z64 = ByteArray(56)
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(z64Offset)
                raf.readFully(z64)
            }
            if (u32(z64, 0) != SIG_ZIP64_EOCD) throw IOException("ZIP64 目录签名无效")
            val zDisk = u32(z64, 16)
            val zCdDisk = u32(z64, 20)
            if (zDisk != 0L || zCdDisk != 0L) throw IOException("不支持分卷 ZIP（ZIP64 多磁盘）")
            totalEntries = u64(z64, 32)
            cdSize = u64(z64, 40)
            cdOffset = u64(z64, 48)
            cdEnd = z64Offset
            if (totalEntries < 0 || cdSize < 0) throw IOException("ZIP64 目录字段无效")
        }
        if (totalEntries > maxArchiveEntries) {
            throw IOException("ZIP 条目数 $totalEntries 超过预算 $maxArchiveEntries")
        }
        if (cdSize > MAX_CENTRAL_DIRECTORY) {
            throw IOException("ZIP 中央目录 $cdSize 字节超过解析预算 $MAX_CENTRAL_DIRECTORY")
        }
        if (totalEntries == 0L && cdSize == 0L) {
            return ScannedArchive(emptyList(), buildVersion(file, emptyList()), Charsets.UTF_8)
        }
        val cdActual = cdEnd - cdSize
        if (cdActual < 0) throw IOException("ZIP 中央目录偏移无效")
        val central = ByteArray(cdSize.toInt())
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(cdActual)
            raf.readFully(central)
        }
        if (central.isEmpty() || u32(central, 0) != SIG_CENTRAL) {
            // Prefixed/self-extracting archives are normally handled by cdEnd-cdSize, but some
            // writers store a shifted offset. Fall back only when that location is valid.
            if (cdOffset in 0..(length - cdSize) && cdSize > 0) {
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(cdOffset)
                    raf.readFully(central)
                }
                if (u32(central, 0) != SIG_CENTRAL) {
                    throw IOException("ZIP 中央目录签名无效（文件可能损坏或不是普通 ZIP）")
                }
            } else {
                throw IOException("ZIP 中央目录签名无效（文件可能损坏或不是普通 ZIP）")
            }
        }
        val pending = ArrayList<PendingCentral>(totalEntries.toInt())
        var offset = 0
        while (offset < central.size) {
            if ((pending.size and 0x03FF) == 0) checkInterrupted()
            if (offset + 46 > central.size) throw IOException("ZIP 中央目录被截断")
            if (u32(central, offset) != SIG_CENTRAL) {
                throw IOException("ZIP 中央目录记录签名无效（偏移 $offset）")
            }
            val flags = u16(central, offset + 8)
            val method = u16(central, offset + 10)
            val crc = u32(central, offset + 16)
            var compressedSize = u32(central, offset + 20)
            var size = u32(central, offset + 24)
            val nameLength = u16(central, offset + 28)
            val extraLength = u16(central, offset + 30)
            val commentLength = u16(central, offset + 32)
            var diskStart = u16(central, offset + 34)
            val externalAttributes = u32(central, offset + 38)
            var localOffset = u32(central, offset + 42)
            val recordLength = 46 + nameLength + extraLength + commentLength
            if (recordLength < 46 || offset + recordLength > central.size) {
                throw IOException("ZIP 中央目录记录长度无效")
            }
            val nameBytes = central.copyOfRange(offset + 46, offset + 46 + nameLength)
            val displayName = String(nameBytes, Charsets.UTF_8)
            val versionMadeBy = u16(central, offset + 4)
            val extraStart = offset + 46 + nameLength
            val extraEnd = extraStart + extraLength
            val commentBytes = central.copyOfRange(extraEnd, extraEnd + commentLength)
            var aesExtra: ByteArray? = null
            var aesMethod = -1
            var extraPos = extraStart
            while (extraPos + 4 <= extraEnd) {
                val id = u16(central, extraPos)
                val len = u16(central, extraPos + 2)
                if (extraPos + 4 + len > extraEnd) throw IOException("ZIP 中央目录 extra 字段被截断")
                if (id == 0x9901) {
                    if (len != 7) throw IOException("AES 条目 '$displayName' 的 0x9901 extra 长度应为 7，实际 $len")
                    aesExtra = central.copyOfRange(extraPos, extraPos + 11)
                    aesMethod = u16(central, extraPos + 9)
                }
                extraPos += 4 + len
            }
            // A few writers leave short, unstructured padding at the end of an extra field.
            // Complete records above are authoritative; do not reject otherwise readable ZIPs.
            if (method == 99) {
                val extra = aesExtra
                    ?: throw IOException("AES 条目 '$displayName' 缺少 0x9901 extra")
                ToolkitZipCrypto.aesCompressionMethod(extra)
            }
            if (size == 0xFFFFFFFFL || compressedSize == 0xFFFFFFFFL || diskStart == 0xFFFF || localOffset == 0xFFFFFFFFL) {
                var p = extraStart
                var found = false
                while (p + 4 <= extraEnd) {
                    val id = u16(central, p)
                    val len = u16(central, p + 2)
                    if (p + 4 + len > extraEnd) throw IOException("ZIP 中央目录 extra 字段被截断")
                    if (id == 0x0001) {
                        var q = p + 4
                        val avail = len
                        fun nextU64(): Long {
                            if (q + 8 > p + 4 + avail) throw IOException("ZIP64 extra 字段不足")
                            val v = u64(central, q); q += 8; return v
                        }
                        fun nextU32(): Long {
                            if (q + 4 > p + 4 + avail) throw IOException("ZIP64 extra 字段不足")
                            val v = u32(central, q); q += 4; return v
                        }
                        if (size == 0xFFFFFFFFL) size = nextU64()
                        if (compressedSize == 0xFFFFFFFFL) compressedSize = nextU64()
                        if (localOffset == 0xFFFFFFFFL) localOffset = nextU64()
                        if (diskStart == 0xFFFF) diskStart = nextU32().toInt()
                        found = true
                        break
                    }
                    p += 4 + len
                }
                if (!found) throw IOException("ZIP64 条目缺少 0x0001 extra 字段: '$displayName'")
            }
            if (diskStart != 0) throw IOException("不支持分卷 ZIP（条目 '$displayName' 位于磁盘 $diskStart）")
            if (size < 0 || compressedSize < 0) throw IOException("ZIP 条目 '$displayName' 的大小字段无效")
            pending.add(
                PendingCentral(
                    nameBytes = nameBytes,
                    flags = flags,
                    method = method,
                    actualMethod = if (method == 99) aesMethod else method,
                    crc = crc,
                    compressedSize = compressedSize,
                    size = size,
                    externalAttributes = externalAttributes,
                    localOffset = localOffset,
                    versionMadeBy = versionMadeBy,
                    aesExtra = aesExtra,
                    commentBytes = commentBytes,
                ),
            )
            offset += recordLength
        }
        if (offset != central.size) throw IOException("ZIP 中央目录末尾存在无法解析的字节")
        if (totalEntries >= 0 && pending.size.toLong() != totalEntries) {
            throw IOException("ZIP 中央目录条目数不一致：声明 $totalEntries，实际 ${pending.size}")
        }
        val nameCharset = chooseNameCharset(pending)
        val entries = ArrayList<CentralEntry>(pending.size)
        for (raw in pending) {
            val name = String(raw.nameBytes, if ((raw.flags and 0x0800) != 0) Charsets.UTF_8 else nameCharset)
            if (name.isEmpty()) throw IOException("ZIP 条目名为空")
            if (name.indexOf('\u0000') >= 0) throw IOException("ZIP 条目名包含 NUL")
            val unixMode = (raw.externalAttributes ushr 16) and 0xFFFF
            entries.add(
                CentralEntry(
                    name = name,
                    flags = raw.flags,
                    method = raw.method,
                    actualMethod = raw.actualMethod,
                    crc = raw.crc,
                    compressedSize = raw.compressedSize,
                    size = raw.size,
                    externalAttributes = raw.externalAttributes,
                    directory = name.endsWith("/"),
                    encrypted = (raw.flags and 0x0001) != 0 || (raw.flags and 0x0040) != 0 || raw.method == 99,
                    symlink = (unixMode and 0xF000L) == 0xA000L,
                    localOffset = raw.localOffset,
                    versionMadeBy = raw.versionMadeBy,
                    aesExtra = raw.aesExtra,
                    commentBytes = raw.commentBytes,
                ),
            )
        }
        val seen = HashSet<String>()
        for (entry in entries) {
            if (!seen.add(entry.name)) {
                throw IOException("ZIP 含重复条目 '${entry.name}'，拒绝继续（名称歧义）")
            }
        }
        return ScannedArchive(entries, buildVersion(file, entries), nameCharset)
    }

    private fun chooseNameCharset(entries: List<PendingCentral>): Charset {
        var needsLegacy = false
        for (entry in entries) {
            if ((entry.flags and 0x0800) != 0) continue
            if (!strictUtf8(entry.nameBytes)) {
                needsLegacy = true
                break
            }
        }
        if (!needsLegacy) return Charsets.UTF_8
        return try {
            Charset.forName("GB18030")
        } catch (e: Exception) {
            throw IOException("ZIP 文件名不是 UTF-8，且当前环境不支持 GB18030 回退", e)
        }
    }

    private fun strictUtf8(bytes: ByteArray): Boolean = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
        true
    } catch (_: CharacterCodingException) {
        false
    }

    private fun buildVersion(file: File, entries: List<CentralEntry>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun putLong(value: Long) {
            for (shift in 56 downTo 0 step 8) digest.update((value ushr shift).toByte())
        }
        fun putString(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            putLong(bytes.size.toLong())
            digest.update(bytes)
        }
        putLong(file.length())
        putLong(file.lastModified())
        putLong(entries.size.toLong())
        for (entry in entries) {
            putString(entry.name)
            putLong(entry.flags.toLong())
            putLong(entry.method.toLong())
            putLong(entry.crc)
            putLong(entry.size)
            putLong(entry.compressedSize)
        }
        return "zip-${file.length()}-${file.lastModified()}-${toHex(digest.digest()).take(32)}"
    }

    private fun collectDirectoryNames(entries: List<CentralEntry>): Set<String> {
        val dirs = LinkedHashSet<String>()
        for (entry in entries) {
            if (entry.directory) {
                dirs.add(entry.name)
                var parent = parentDirOfDir(entry.name)
                while (parent.isNotEmpty()) {
                    dirs.add(parent)
                    parent = parentDirOfDir(parent)
                }
            } else {
                var parent = parentDirOfFile(entry.name)
                while (parent.isNotEmpty()) {
                    dirs.add(parent)
                    parent = parentDirOfDir(parent)
                }
            }
        }
        return dirs
    }

    private fun collectChildren(entries: List<CentralEntry>): Map<String, List<String>> {
        val map = LinkedHashMap<String, TreeSet<String>>()
        fun bucket(prefix: String): TreeSet<String> = map.getOrPut(prefix) {
            TreeSet(Comparator<String> { a, b ->
                val c = a.removeSuffix("/").compareTo(b.removeSuffix("/"))
                if (c != 0) c else a.compareTo(b)
            })
        }
        for (entry in entries) {
            if (entry.directory) {
                val parent = parentDirOfDir(entry.name)
                bucket(parent).add(entry.name)
                var p = parent
                while (p.isNotEmpty()) {
                    val grand = parentDirOfDir(p)
                    bucket(grand).add(p)
                    p = grand
                }
            } else {
                val parent = parentDirOfFile(entry.name)
                bucket(parent).add(entry.name)
                var p = parent
                while (p.isNotEmpty()) {
                    val grand = parentDirOfDir(p)
                    bucket(grand).add(p)
                    p = grand
                }
            }
        }
        return map.mapValues { it.value.toList() }
    }

    private fun collectAggregates(entries: List<CentralEntry>): Map<String, LongArray> {
        val map = HashMap<String, LongArray>()
        for (entry in entries) {
            if (entry.directory) continue
            var parent = parentDirOfFile(entry.name)
            while (parent.isNotEmpty()) {
                val aggregate = map.getOrPut(parent) { LongArray(2) }
                aggregate[0] = saturatingAdd(aggregate[0], entry.size.coerceAtLeast(0))
                aggregate[1] = saturatingAdd(aggregate[1], entry.compressedSize.coerceAtLeast(0))
                parent = parentDirOfDir(parent)
            }
        }
        return map
    }

    // ---------------------------------------------------------------------------------------------
    // Text / byte page helpers
    // ---------------------------------------------------------------------------------------------

    private data class TextPage(
        val text: String,
        val encoding: String,
        val startLine: Long,
        val startColumn: Long,
        val nextLine: Long,
        val nextColumn: Long,
        val returnedLineCount: Long,
        val returnedCodePoints: Long,
        val hasMore: Boolean,
    )

    private data class BytePage(
        val offset: Long,
        val hex: String,
        val nextOffset: Long,
        val totalBytes: Long,
        val hasMore: Boolean,
    )

    private class CodePointReader(private val reader: Reader) {
        private var pending: Int = -2

        fun read(): Int {
            if (pending != -2) {
                val value = pending
                pending = -2
                return value
            }
            val first = reader.read()
            if (first < 0) return -1
            if (!Character.isHighSurrogate(first.toChar())) return first
            val second = reader.read()
            if (second >= 0 && Character.isLowSurrogate(second.toChar())) {
                return Character.toCodePoint(first.toChar(), second.toChar())
            }
            if (second >= 0) pending = second
            return first
        }

        fun unread(codePoint: Int) {
            check(pending == -2) { "内部读取回退冲突" }
            pending = codePoint
        }
    }

    private fun readText(
        reader: Reader,
        encoding: String,
        startLine: Long,
        startColumn: Long,
        lineLimit: Int,
        maxChars: Int,
    ): TextPage {
        val cpr = CodePointReader(reader)
        var line = 0L
        var column = 0L
        var eof = false
        while (line < startLine) {
            if ((line and 0x0FFFL) == 0L) checkInterrupted()
            val cp = cpr.read()
            if (cp < 0) { eof = true; break }
            if (cp == '\r'.code) {
                val next = cpr.read()
                if (next >= 0 && next != '\n'.code) cpr.unread(next)
                if (next < 0) eof = true
                line++
                column = 0
            } else if (cp == '\n'.code) {
                line++
                column = 0
            }
        }
        if (!eof && column < startColumn) {
            while (column < startColumn) {
                if ((column and 0x0FFFL) == 0L) checkInterrupted()
                val cp = cpr.read()
                if (cp < 0) { eof = true; break }
                if (cp == '\r'.code || cp == '\n'.code) {
                    cpr.unread(cp)
                    break
                }
                column++
            }
        }
        val pageStartLine = line
        val pageStartColumn = column
        val out = StringBuilder()
        var codePoints = 0L
        var breaks = 0
        var endedWithBreak = false
        var loopEof = eof
        while (!loopEof) {
            if (breaks >= lineLimit) break
            if (codePoints >= maxChars.toLong()) break
            if ((codePoints and 0x0FFFL) == 0L) checkInterrupted()
            val cp = cpr.read()
            if (cp < 0) { loopEof = true; break }
            if (cp == '\r'.code) {
                out.append('\r')
                codePoints++
                val next = cpr.read()
                if (next == '\n'.code) {
                    out.append('\n')
                    codePoints++
                } else if (next >= 0) {
                    cpr.unread(next)
                } else {
                    loopEof = true
                }
                line++
                column = 0
                breaks++
                endedWithBreak = true
            } else if (cp == '\n'.code) {
                out.append('\n')
                codePoints++
                line++
                column = 0
                breaks++
                endedWithBreak = true
            } else {
                out.appendCodePoint(cp)
                codePoints++
                column++
                endedWithBreak = false
            }
        }
        var hasMore = false
        if (!loopEof) {
            val peek = cpr.read()
            if (peek >= 0) {
                hasMore = true
                cpr.unread(peek)
            }
        }
        val returnedLines = when {
            out.isEmpty() -> 0L
            endedWithBreak -> breaks.toLong()
            else -> breaks.toLong() + 1L
        }
        return TextPage(
            text = out.toString(),
            encoding = encoding,
            startLine = pageStartLine,
            startColumn = pageStartColumn,
            nextLine = line,
            nextColumn = column,
            returnedLineCount = returnedLines,
            returnedCodePoints = codePoints,
            hasMore = hasMore,
        )
    }

    private data class EncodingSelection(val charset: Charset, val token: String, val bomLength: Int)

    private data class BomInfo(val charset: Charset, val token: String, val length: Int)

    private fun detectBom(bytes: ByteArray): BomInfo? {
        if (bytes.size >= 4 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() &&
            bytes[2] == 0x00.toByte() && bytes[3] == 0x00.toByte()
        ) {
            throw IllegalArgumentException("暂不支持 UTF-32LE 文本编码")
        }
        if (bytes.size >= 4 && bytes[0] == 0x00.toByte() && bytes[1] == 0x00.toByte() &&
            bytes[2] == 0xFE.toByte() && bytes[3] == 0xFF.toByte()
        ) {
            throw IllegalArgumentException("暂不支持 UTF-32BE 文本编码")
        }
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return BomInfo(Charsets.UTF_8, "utf-8", 3)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return BomInfo(Charsets.UTF_16LE, "utf-16le", 2)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return BomInfo(Charsets.UTF_16BE, "utf-16be", 2)
        }
        return null
    }

    private fun selectEncoding(prefix: ByteArray, bom: BomInfo?, requested: String): EncodingSelection {
        if (requested.isNotEmpty()) {
            val lower = requested.lowercase(Locale.US)
            val wantsBom = lower.endsWith("-bom")
            val base = if (wantsBom) lower.removeSuffix("-bom") else lower
            val charset = charsetFor(base)
            val canonical = canonicalEncodingName(base)
            if (bom != null) {
                if (!sameCharset(charset, bom.charset)) {
                    throw IllegalArgumentException("encoding='$requested' 与条目 BOM 编码 ${bom.token} 不一致")
                }
                return EncodingSelection(charset, canonical + "-bom", bom.length)
            }
            if (wantsBom) throw IllegalArgumentException("encoding='$requested' 要求 BOM，但条目没有 BOM")
            return EncodingSelection(charset, canonical, 0)
        }
        if (bom != null) return EncodingSelection(bom.charset, bom.token + "-bom", bom.length)
        val utf8 = strictDecode(prefix, Charsets.UTF_8)
        if (utf8) return EncodingSelection(Charsets.UTF_8, "utf-8", 0)
        val gb18030 = try { Charset.forName("GB18030") } catch (_: Exception) { null }
        if (gb18030 != null) return EncodingSelection(gb18030, "gb18030", 0)
        return EncodingSelection(Charsets.UTF_8, "utf-8", 0)
    }

    private fun strictDecode(bytes: ByteArray, charset: Charset): Boolean {
        if (bytes.isEmpty()) return true
        return try {
            val decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(java.nio.ByteBuffer.wrap(bytes))
            true
        } catch (_: CharacterCodingException) {
            // A prefix can end in the middle of a multi-byte sequence; retry without the last
            // three bytes instead of falsely falling back to GB18030.
            if (bytes.size > 4) {
                try {
                    val decoder = charset.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                    decoder.decode(java.nio.ByteBuffer.wrap(bytes.copyOfRange(0, bytes.size - 3)))
                    true
                } catch (_: CharacterCodingException) {
                    false
                }
            } else {
                false
            }
        }
    }

    private fun charsetFor(token: String): Charset = when (token.lowercase(Locale.US)) {
        "utf-8", "utf8" -> Charsets.UTF_8
        "utf-16le", "utf16le" -> Charsets.UTF_16LE
        "utf-16be", "utf16be" -> Charsets.UTF_16BE
        "gb18030" -> Charset.forName("GB18030")
        "gbk" -> Charset.forName("GBK")
        "iso-8859-1", "latin1" -> Charsets.ISO_8859_1
        else -> throw IllegalArgumentException(
            "不支持的文本编码 '$token'；可用 utf-8、utf-8-bom、utf-16le、utf-16be、gb18030、gbk、iso-8859-1",
        )
    }

    private fun canonicalEncodingName(token: String): String = when (token.lowercase(Locale.US)) {
        "utf-8", "utf8" -> "utf-8"
        "utf-16le", "utf16le" -> "utf-16le"
        "utf-16be", "utf16be" -> "utf-16be"
        "gb18030" -> "gb18030"
        "gbk" -> "gbk"
        "iso-8859-1", "latin1" -> "iso-8859-1"
        else -> token.lowercase(Locale.US)
    }

    private fun sameCharset(a: Charset, b: Charset): Boolean =
        a.name().equals(b.name(), ignoreCase = true)

    private fun readPrefix(input: InputStream, max: Int): ByteArray {
        val buffer = ByteArray(max)
        var count = 0
        while (count < max) {
            val n = input.read(buffer, count, max - count)
            if (n < 0) break
            count += n
        }
        return buffer.copyOf(count)
    }

    // ---------------------------------------------------------------------------------------------
    // Output ZIP writing
    // ---------------------------------------------------------------------------------------------

    private data class OutputEntry(
        val name: String,
        val directory: Boolean,
        val original: CentralEntry? = null,
        val sourceFile: File? = null,
        val method: Int = METHOD_DEFLATED,
        val time: Long = System.currentTimeMillis(),
        val comment: String? = null,
    )

    private data class RawCentralRecord(
        val nameBytes: ByteArray,
        val flags: Int,
        val method: Int,
        val time: Int,
        val date: Int,
        val crc: Long,
        val compressedSize: Long,
        val size: Long,
        val versionMadeBy: Int,
        val versionNeeded: Int,
        val externalAttributes: Long,
        val localOffset: Long,
        val aesExtra: ByteArray?,
        val commentBytes: ByteArray,
    )

    private fun writeOutputZip(
        temp: File,
        plans: List<OutputEntry>,
        source: Archive?,
        level: Int,
        password: String,
    ) {
        // Any update carries `original` entries. Copy those raw so an unchanged/replaced-free
        // archive keeps its original compression method and, for encrypted entries, the exact
        // salt/verifier/ciphertext/MAC bytes. AES output necessarily needs the same writer.
        val needsRawWriter = plans.any { it.original != null } || password.isNotEmpty()
        if (needsRawWriter) {
            writeRawOutputZip(temp, plans, source, level, password)
        } else {
            writeJdkOutputZip(temp, plans, source, level)
        }
    }

    /** Fast path for unencrypted creation where no original entry needs raw copying. */
    private fun writeJdkOutputZip(
        temp: File,
        plans: List<OutputEntry>,
        source: Archive?,
        level: Int,
    ) {
        var total = 0L
        ZipOutputStream(BufferedOutputStream(FileOutputStream(temp))).use { zipOut ->
            zipOut.setLevel(level)
            for (plan in plans) {
                checkInterrupted()
                if (plan.name.isEmpty()) throw IllegalArgumentException("ZIP 条目名不能为空")
                if (plan.name.indexOf('\u0000') >= 0) throw SecurityException("ZIP 条目名不能包含 NUL")
                val outEntry = ZipEntry(plan.name)
                outEntry.time = plan.time
                val comment = plan.comment
                if (!comment.isNullOrEmpty()) outEntry.comment = comment
                if (plan.directory) {
                    outEntry.method = METHOD_STORED
                    outEntry.size = 0
                    outEntry.compressedSize = 0
                    outEntry.crc = 0
                    zipOut.putNextEntry(outEntry)
                    zipOut.closeEntry()
                    continue
                }
                val original = plan.original
                if (original != null) {
                    if (original.encrypted) {
                        throw IOException("条目 '${original.name}' 已加密；原始字节路径不可用")
                    }
                    if (original.method != METHOD_STORED && original.method != METHOD_DEFLATED) {
                        throw IOException("条目 '${original.name}' 使用不支持的压缩算法 ${original.method}")
                    }
                    outEntry.method = original.method
                    if (original.method == METHOD_STORED) {
                        outEntry.size = original.size
                        outEntry.compressedSize = original.size
                        outEntry.crc = original.crc
                    }
                    zipOut.putNextEntry(outEntry)
                    val zipFile = source ?: throw IOException("缺少源 ZIP")
                    val input = zipFile.openEntryStream(original, "", verifyOnClose = false)
                    try {
                        val crc = CRC32()
                        var count = 0L
                        val buffer = ByteArray(IO_BUFFER)
                        while (true) {
                            checkInterrupted()
                            val n = input.read(buffer)
                            if (n < 0) break
                            crc.update(buffer, 0, n)
                            count += n
                            if (count > maxCreateBytes) {
                                throw IOException("写入未完成：输出超过预算 $maxCreateBytes 字节")
                            }
                            zipOut.write(buffer, 0, n)
                        }
                        if (original.size >= 0 && count != original.size) {
                            throw IOException("源条目 '${original.name}' 长度不符：声明 ${original.size}，实际 $count")
                        }
                        if (original.crc >= 0 && crc.value != original.crc) {
                            throw IOException("源条目 '${original.name}' CRC 不符，原 ZIP 可能损坏")
                        }
                        total += count
                        if (total > maxCreateBytes) {
                            throw IOException("写入未完成：输出超过预算 $maxCreateBytes 字节")
                        }
                    } finally {
                        try { input.close() } catch (_: IOException) {}
                    }
                    zipOut.closeEntry()
                } else {
                    val file = plan.sourceFile ?: throw IOException("条目 '${plan.name}' 没有数据源")
                    if (!file.isFile) throw IOException("源文件不存在或不是普通文件: ${file.path}")
                    outEntry.method = plan.method
                    if (plan.method == METHOD_STORED) {
                        val meta = fileCrcAndSize(file)
                        outEntry.size = meta.second
                        outEntry.compressedSize = meta.second
                        outEntry.crc = meta.first
                    }
                    zipOut.putNextEntry(outEntry)
                    val crc = CRC32()
                    var count = 0L
                    BufferedInputStream(FileInputStream(file)).use { input ->
                        val buffer = ByteArray(IO_BUFFER)
                        while (true) {
                            checkInterrupted()
                            val n = input.read(buffer)
                            if (n < 0) break
                            crc.update(buffer, 0, n)
                            count += n
                            if (count > maxCreateBytes) {
                                throw IOException("写入未完成：输出超过预算 $maxCreateBytes 字节")
                            }
                            zipOut.write(buffer, 0, n)
                        }
                    }
                    if (plan.method == METHOD_STORED) {
                        val meta = fileCrcAndSize(file)
                        if (meta.second != count || meta.first != crc.value) {
                            throw IOException("源文件在写入期间变化，已放弃: ${file.path}")
                        }
                    }
                    total += count
                    if (total > maxCreateBytes) {
                        throw IOException("写入未完成：输出超过预算 $maxCreateBytes 字节")
                    }
                    zipOut.closeEntry()
                }
            }
        }
    }

    /**
     * Raw-entry writer used for update and AES creation.
     *
     * Unchanged/renamed original entries are copied as compressed (or encrypted) bytes together
     * with their local-header timing/flags; only the name, local offset and central extra/AES
     * metadata are rebuilt. New unencrypted files are streamed with a data descriptor. New
     * encrypted files are first produced by zip4j in a one-entry temporary ZIP, then their raw
     * bytes are copied here so all output entries follow the same central-directory writer.
     */
    private fun writeRawOutputZip(
        temp: File,
        plans: List<OutputEntry>,
        source: Archive?,
        level: Int,
        password: String,
    ) {
        val readers = LinkedHashMap<File, RandomAccessFile>()
        try {
            BufferedOutputStream(FileOutputStream(temp)).use { buffered ->
                val out = CountingOutputStream(buffered)
                val records = ArrayList<RawCentralRecord>(plans.size)
                var totalUncompressed = 0L
                for (plan in plans) {
                    checkInterrupted()
                    if (plan.name.isEmpty()) throw IllegalArgumentException("ZIP 条目名不能为空")
                    if (plan.name.indexOf('\u0000') >= 0) throw SecurityException("ZIP 条目名不能包含 NUL")
                    if (plan.directory) {
                        records.add(writeRawDirectory(out, plan))
                    } else {
                        val original = plan.original
                        if (original != null) {
                            val archive = source ?: throw IOException("缺少源 ZIP")
                            records.add(copyRawEntry(out, readers, archive.file, original, plan.name))
                        } else {
                            val file = plan.sourceFile ?: throw IOException("条目 '${plan.name}' 没有数据源")
                            if (!file.isFile) throw IOException("源文件不存在或不是普通文件: ${file.path}")
                            if (file.length() > maxCreateBytes) {
                                throw IOException("源文件超过预算 $maxCreateBytes 字节: ${file.path}")
                            }
                            if (password.isNotEmpty()) {
                                val parent = temp.parentFile ?: throw IOException("临时 ZIP 没有父目录")
                                val encryptedTemp = File(parent, ".zip-aes-${UUID.randomUUID()}.tmp")
                                try {
                                    ToolkitZipCrypto.createEncryptedZip(
                                        encryptedTemp, file, plan.name, plan.method, level, password,
                                    )
                                    val scanned = scanCentral(encryptedTemp)
                                    if (scanned.entries.size != 1) {
                                        throw IOException("临时 AES ZIP 条目数异常: ${scanned.entries.size}")
                                    }
                                    val encryptedEntry = scanned.entries[0]
                                    if (encryptedEntry.directory) {
                                        throw IOException("临时 AES ZIP 意外产生目录条目 '${encryptedEntry.name}'")
                                    }
                                    records.add(
                                        copyRawEntry(
                                            out, readers, encryptedTemp, encryptedEntry, plan.name,
                                            timeOverride = plan.time,
                                        ),
                                    )
                                } finally {
                                    readers.remove(encryptedTemp)?.let { reader ->
                                        try { reader.close() } catch (_: IOException) {}
                                    }
                                    encryptedTemp.delete()
                                }
                            } else {
                                records.add(writeNewRawEntry(out, plan, level))
                            }
                        }
                    }
                    totalUncompressed = saturatingAdd(totalUncompressed, records[records.size - 1].size)
                    if (totalUncompressed > maxCreateBytes) {
                        throw IOException("写入未完成：输出解压后大小超过预算 $maxCreateBytes 字节")
                    }
                    if (out.count > 0xFFFFFFFFL) {
                        throw IOException("输出 ZIP 超过 4 GiB，当前不支持 ZIP64 写出")
                    }
                }
                if (records.size > 0xFFFF) {
                    throw IOException("输出条目数 ${records.size} 超过普通 ZIP 的 65535 上限")
                }
                val centralStart = out.count
                for (record in records) writeCentralRecord(out, record)
                val centralSize = out.count - centralStart
                if (centralStart > 0xFFFFFFFFL || centralSize > 0xFFFFFFFFL) {
                    throw IOException("输出 ZIP 中央目录超过 4 GiB，当前不支持 ZIP64 写出")
                }
                writeEndOfCentralDirectory(out, records.size, centralStart, centralSize)
                out.flush()
            }
        } finally {
            for (reader in readers.values) {
                try { reader.close() } catch (_: IOException) {}
            }
        }
    }

    private fun writeRawDirectory(out: CountingOutputStream, plan: OutputEntry): RawCentralRecord {
        val nameBytes = plan.name.toByteArray(Charsets.UTF_8)
        if (nameBytes.size > 0xFFFF) throw IOException("ZIP 条目名过长: '${plan.name}'")
        val (time, date) = dosDateTime(plan.time)
        val localOffset = out.count
        writeLocalHeader(
            out, 20, 0x0800, METHOD_STORED, time, date, 0, 0, 0, nameBytes, ByteArray(0),
        )
        return RawCentralRecord(
            nameBytes = nameBytes,
            flags = 0x0800,
            method = METHOD_STORED,
            time = time,
            date = date,
            crc = 0,
            compressedSize = 0,
            size = 0,
            versionMadeBy = 20,
            versionNeeded = 20,
            externalAttributes = 0x10L,
            localOffset = localOffset,
            aesExtra = null,
            commentBytes = plan.original?.commentBytes ?: ByteArray(0),
        )
    }

    private fun writeNewRawEntry(
        out: CountingOutputStream,
        plan: OutputEntry,
        level: Int,
    ): RawCentralRecord {
        val file = plan.sourceFile ?: throw IOException("条目 '${plan.name}' 没有数据源")
        val method = plan.method
        if (method != METHOD_STORED && method != METHOD_DEFLATED) {
            throw IOException("条目 '${plan.name}' 使用不支持的压缩算法 $method")
        }
        val nameBytes = plan.name.toByteArray(Charsets.UTF_8)
        if (nameBytes.size > 0xFFFF) throw IOException("ZIP 条目名过长: '${plan.name}'")
        val (time, date) = dosDateTime(plan.time)
        val localOffset = out.count
        writeLocalHeader(
            out, 20, 0x0800 or 0x0008, method, time, date, 0, 0, 0, nameBytes, ByteArray(0),
        )
        val lengthBefore = file.length()
        val modifiedBefore = file.lastModified()
        val crc = CRC32()
        var count = 0L
        var compressed = 0L
        if (method == METHOD_STORED) {
            BufferedInputStream(FileInputStream(file)).use { input ->
                val buffer = ByteArray(IO_BUFFER)
                while (true) {
                    checkInterrupted()
                    val n = input.read(buffer)
                    if (n < 0) break
                    crc.update(buffer, 0, n)
                    count += n
                    if (count > maxCreateBytes) {
                        throw IOException("写入未完成：输出超过预算 $maxCreateBytes 字节")
                    }
                    out.write(buffer, 0, n)
                }
            }
            compressed = count
        } else {
            val deflater = Deflater(level.coerceIn(0, 9), true)
            try {
                BufferedInputStream(FileInputStream(file)).use { input ->
                    val buffer = ByteArray(IO_BUFFER)
                    val compressedBuffer = ByteArray(IO_BUFFER)
                    while (true) {
                        checkInterrupted()
                        val n = input.read(buffer)
                        if (n < 0) break
                        crc.update(buffer, 0, n)
                        count += n
                        if (count > maxCreateBytes) {
                            throw IOException("写入未完成：输出超过预算 $maxCreateBytes 字节")
                        }
                        deflater.setInput(buffer, 0, n)
                        while (!deflater.needsInput()) {
                            val written = deflater.deflate(compressedBuffer)
                            if (written > 0) {
                                out.write(compressedBuffer, 0, written)
                                compressed += written
                            }
                        }
                    }
                }
                deflater.finish()
                val compressedBuffer = ByteArray(IO_BUFFER)
                while (!deflater.finished()) {
                    checkInterrupted()
                    val written = deflater.deflate(compressedBuffer)
                    if (written > 0) {
                        out.write(compressedBuffer, 0, written)
                        compressed += written
                    }
                }
            } finally {
                deflater.end()
            }
        }
        // A changed source is detected after the bytes have been written; the caller deletes the
        // temporary output and leaves the destination untouched.
        if (file.length() != lengthBefore || file.lastModified() != modifiedBefore) {
            throw IOException("源文件在写入期间变化，已放弃: ${file.path}")
        }
        if (count > 0xFFFFFFFFL || compressed > 0xFFFFFFFFL) {
            throw IOException("条目 '${plan.name}' 超过普通 ZIP 的 4 GiB 上限")
        }
        writeDataDescriptor(out, crc.value, compressed, count)
        return RawCentralRecord(
            nameBytes = nameBytes,
            flags = 0x0800 or 0x0008,
            method = method,
            time = time,
            date = date,
            crc = crc.value,
            compressedSize = compressed,
            size = count,
            versionMadeBy = 20,
            versionNeeded = 20,
            externalAttributes = 0,
            localOffset = localOffset,
            aesExtra = null,
            commentBytes = ByteArray(0),
        )
    }

    private fun copyRawEntry(
        out: CountingOutputStream,
        readers: MutableMap<File, RandomAccessFile>,
        sourceFile: File,
        entry: CentralEntry,
        outputName: String,
        timeOverride: Long? = null,
    ): RawCentralRecord {
        val reader = readers.getOrPut(sourceFile) { RandomAccessFile(sourceFile, "r") }
        val fileLength = reader.length()
        if (entry.localOffset < 0 || entry.localOffset > fileLength - 30) {
            throw IOException("ZIP 条目 '${entry.name}' 的本地头偏移无效")
        }
        reader.seek(entry.localOffset)
        val header = ByteArray(30)
        reader.readFully(header)
        if (u32(header, 0) != 0x04034b50L) {
            throw IOException("ZIP 条目 '${entry.name}' 的本地头签名无效")
        }
        val localVersion = u16(header, 4)
        val localFlags = u16(header, 6)
        val localMethod = u16(header, 8)
        val localTime = u16(header, 10)
        val localDate = u16(header, 12)
        val nameLength = u16(header, 26)
        val extraLength = u16(header, 28)
        if (localMethod != entry.method) {
            throw IOException(
                "ZIP 条目 '${entry.name}' 的本地头压缩算法 $localMethod 与中央目录 ${entry.method} 不一致",
            )
        }
        if (entry.compressedSize < 0 || entry.size < 0 ||
            entry.compressedSize > 0xFFFFFFFFL || entry.size > 0xFFFFFFFFL
        ) {
            throw IOException("ZIP 条目 '${entry.name}' 超过普通 ZIP 的 4 GiB 上限")
        }
        val dataOffset = entry.localOffset + 30L + nameLength + extraLength
        if (dataOffset < entry.localOffset || dataOffset + entry.compressedSize > fileLength) {
            throw IOException("ZIP 条目 '${entry.name}' 的压缩数据超出文件范围")
        }
        val aes = ToolkitZipCrypto.isAes(entry.method)
        val aesExtra = if (aes) {
            entry.aesExtra ?: throw IOException("AES 条目 '${entry.name}' 缺少 0x9901 extra")
        } else {
            null
        }
        // ZipCrypto's 12-byte password-check header uses the DOS time (not CRC) when bit 3 is
        // set. Keep that bit for ZipCrypto so its check byte keeps the same meaning; AES and
        // unencrypted entries can safely write real local sizes and drop the descriptor.
        val zipCryptoDescriptor = entry.encrypted && !aes && (localFlags and 0x0008) != 0
        var flags = localFlags or 0x0800
        if (!zipCryptoDescriptor) flags = flags and 0xFFF7
        val versionNeeded = if (aes) maxOf(localVersion, 51) else maxOf(localVersion, 20)
        val nameBytes = outputName.toByteArray(Charsets.UTF_8)
        if (nameBytes.size > 0xFFFF) throw IOException("ZIP 条目名过长: '$outputName'")
        val extra = aesExtra ?: ByteArray(0)
        val (writeTime, writeDate) = if (timeOverride != null) {
            dosDateTime(timeOverride)
        } else {
            localTime to localDate
        }
        val localOffset = out.count
        // ZipCrypto with bit 3 follows the classic streaming form: local CRC/sizes are zero and
        // the real values follow in the data descriptor. zip4j copies them from the central
        // directory when reading. AES and unencrypted entries can carry real local sizes.
        val localCrc = if (zipCryptoDescriptor) 0L else entry.crc
        val localCompressedSize = if (zipCryptoDescriptor) 0L else entry.compressedSize
        val localSize = if (zipCryptoDescriptor) 0L else entry.size
        writeLocalHeader(
            out, versionNeeded, flags, entry.method, writeTime, writeDate,
            localCrc, localCompressedSize, localSize, nameBytes, extra,
        )
        copyRange(reader, out, dataOffset, entry.compressedSize)
        if (zipCryptoDescriptor) {
            writeDataDescriptor(out, entry.crc, entry.compressedSize, entry.size)
        }
        return RawCentralRecord(
            nameBytes = nameBytes,
            flags = flags,
            method = entry.method,
            time = writeTime,
            date = writeDate,
            crc = entry.crc,
            compressedSize = entry.compressedSize,
            size = entry.size,
            versionMadeBy = entry.versionMadeBy,
            versionNeeded = versionNeeded,
            externalAttributes = entry.externalAttributes,
            localOffset = localOffset,
            aesExtra = aesExtra,
            commentBytes = entry.commentBytes,
        )
    }

    private fun copyRange(
        reader: RandomAccessFile,
        out: CountingOutputStream,
        offset: Long,
        length: Long,
    ) {
        reader.seek(offset)
        val buffer = ByteArray(IO_BUFFER)
        var remaining = length
        while (remaining > 0) {
            checkInterrupted()
            val want = minOf(buffer.size.toLong(), remaining).toInt()
            val count = reader.read(buffer, 0, want)
            if (count < 0) throw IOException("源 ZIP 数据在复制期间提前结束")
            out.write(buffer, 0, count)
            remaining -= count
        }
    }

    private fun writeLocalHeader(
        out: OutputStream,
        versionNeeded: Int,
        flags: Int,
        method: Int,
        time: Int,
        date: Int,
        crc: Long,
        compressedSize: Long,
        size: Long,
        name: ByteArray,
        extra: ByteArray,
    ) {
        val header = ByteArray(30)
        putU32(header, 0, 0x04034b50L)
        putU16(header, 4, versionNeeded)
        putU16(header, 6, flags)
        putU16(header, 8, method)
        putU16(header, 10, time)
        putU16(header, 12, date)
        putU32(header, 14, crc)
        putU32(header, 18, compressedSize)
        putU32(header, 22, size)
        putU16(header, 26, name.size)
        putU16(header, 28, extra.size)
        out.write(header)
        out.write(name)
        out.write(extra)
    }

    private fun writeDataDescriptor(
        out: OutputStream,
        crc: Long,
        compressedSize: Long,
        size: Long,
    ) {
        val descriptor = ByteArray(16)
        putU32(descriptor, 0, 0x08074b50L)
        putU32(descriptor, 4, crc)
        putU32(descriptor, 8, compressedSize)
        putU32(descriptor, 12, size)
        out.write(descriptor)
    }

    private fun writeCentralRecord(out: OutputStream, record: RawCentralRecord) {
        val header = ByteArray(46)
        putU32(header, 0, 0x02014b50L)
        putU16(header, 4, record.versionMadeBy)
        putU16(header, 6, record.versionNeeded)
        putU16(header, 8, record.flags)
        putU16(header, 10, record.method)
        putU16(header, 12, record.time)
        putU16(header, 14, record.date)
        putU32(header, 16, record.crc)
        putU32(header, 20, record.compressedSize)
        putU32(header, 24, record.size)
        putU16(header, 28, record.nameBytes.size)
        putU16(header, 30, record.aesExtra?.size ?: 0)
        putU16(header, 32, record.commentBytes.size)
        putU16(header, 34, 0)
        putU16(header, 36, 0)
        putU32(header, 38, record.externalAttributes)
        putU32(header, 42, record.localOffset)
        out.write(header)
        out.write(record.nameBytes)
        record.aesExtra?.let { out.write(it) }
        out.write(record.commentBytes)
    }

    private fun writeEndOfCentralDirectory(
        out: OutputStream,
        entries: Int,
        centralStart: Long,
        centralSize: Long,
    ) {
        val eocd = ByteArray(22)
        putU32(eocd, 0, 0x06054b50L)
        putU16(eocd, 4, 0)
        putU16(eocd, 6, 0)
        putU16(eocd, 8, entries)
        putU16(eocd, 10, entries)
        putU32(eocd, 12, centralSize)
        putU32(eocd, 16, centralStart)
        putU16(eocd, 20, 0)
        out.write(eocd)
    }

    private fun dosDateTime(time: Long): Pair<Int, Int> {
        val calendar = java.util.Calendar.getInstance()
        calendar.timeInMillis = time
        val year = calendar.get(java.util.Calendar.YEAR)
        if (year < 1980) return 0 to ((1 shl 5) or 1)
        val dosYear = (year - 1980).coerceAtMost(127)
        val date = (dosYear shl 9) or
            ((calendar.get(java.util.Calendar.MONTH) + 1) shl 5) or
            calendar.get(java.util.Calendar.DAY_OF_MONTH)
        val timeOfDay = (calendar.get(java.util.Calendar.HOUR_OF_DAY) shl 11) or
            (calendar.get(java.util.Calendar.MINUTE) shl 5) or
            (calendar.get(java.util.Calendar.SECOND) / 2)
        return timeOfDay to date
    }

    private class CountingOutputStream(private val delegate: OutputStream) : OutputStream() {
        var count: Long = 0L
            private set

        override fun write(value: Int) {
            delegate.write(value)
            count++
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            delegate.write(buffer, offset, length)
            count += length
        }

        override fun flush() {
            delegate.flush()
        }

        override fun close() {
            delegate.close()
        }
    }

    private fun verifyTempNames(temp: File, names: List<String>) {
        val scanned = scanCentral(temp)
        val actual = scanned.entries.map { it.name }
        if (actual != names) {
            throw IOException("临时 ZIP 条目校验失败：期望 $names，实际 $actual")
        }
    }

    private fun fileCrcAndSize(file: File): Pair<Long, Long> {
        val crc = CRC32()
        var size = 0L
        BufferedInputStream(FileInputStream(file)).use { input ->
            val buffer = ByteArray(IO_BUFFER)
            while (true) {
                checkInterrupted()
                val n = input.read(buffer)
                if (n < 0) break
                crc.update(buffer, 0, n)
                size += n
                if (size > maxCreateBytes) throw IOException("源文件超过预算: ${file.path}")
            }
        }
        return crc.value to size
    }

    private fun installTemp(temp: File, target: File, overwrite: Boolean) {
        if (!temp.isFile) throw IOException("临时 ZIP 不存在: ${temp.path}")
        if (target.exists()) {
            if (!overwrite) {
                temp.delete()
                throw IllegalArgumentException("目标已存在，拒绝覆盖: ${target.path}")
            }
            if (!target.isFile) {
                temp.delete()
                throw IllegalArgumentException("目标不是普通文件: ${target.path}")
            }
            val backup = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.bak")
            if (!target.renameTo(backup)) {
                temp.delete()
                throw IOException("无法暂存原文件，未修改目标: ${target.path}")
            }
            if (!temp.renameTo(target)) {
                backup.renameTo(target)
                temp.delete()
                throw IOException("替换目标失败，已恢复原文件: ${target.path}")
            }
            backup.delete()
        } else {
            if (!temp.renameTo(target)) {
                throw IOException("无法写入目标文件: ${target.path}")
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Path / entry safety
    // ---------------------------------------------------------------------------------------------

    private fun resolvePath(raw: String, label: String): File {
        if (raw.indexOf('\u0000') >= 0) throw SecurityException("$label 含 NUL 字符")
        if (raw.isEmpty()) throw SecurityException("$label 不能为空，且不允许操作 root 目录本身")
        val normalized = raw.replace('\\', '/')
        if (normalized.isEmpty()) throw SecurityException("$label 不能为空")
        val segments = normalized.split('/').filter { it.isNotEmpty() }
        if (segments.any { it == "." || it == ".." }) {
            throw SecurityException("$label 不允许 . 或 .. 路径段: '$raw'")
        }
        val expected: File = when {
            normalized.startsWith("/") && !File(normalized).isAbsolute ->
                segments.fold(canonicalRoot) { base, segment -> File(base, segment) }
            File(normalized).isAbsolute -> File(normalized)
            else -> segments.fold(canonicalRoot) { base, segment -> File(base, segment) }
        }
        val canonical = expected.canonicalFile
        if (canonical != canonicalRoot && !canonical.path.startsWith(rootPrefix)) {
            throw SecurityException("$label 超出允许的 root 目录: ${canonical.path}")
        }
        if (canonical == canonicalRoot) {
            throw SecurityException("$label 不允许操作 root 目录本身")
        }
        val expectedPath = expected.absoluteFile.path
        if (!samePath(expectedPath, canonical.path)) {
            throw SecurityException("$label 含符号链接或不安全的目录跳转: '$raw'")
        }
        return canonical
    }

    private fun resolveChild(parent: File, relative: String): File {
        if (relative.isEmpty()) throw SecurityException("解压条目路径为空")
        if (relative.startsWith("/")) throw SecurityException("解压条目使用绝对路径: '$relative'")
        val segments = relative.split('/')
        if (segments.any { it == ".." }) throw SecurityException("解压条目包含 ..: '$relative'")
        val expected = segments.fold(parent) { base, segment -> File(base, segment) }
        val target = expected.canonicalFile
        if (!samePath(expected.absoluteFile.path, target.path)) {
            throw SecurityException("解压目标含符号链接或不安全跳转: '${expected.path}'")
        }
        if (target != canonicalRoot && !target.path.startsWith(rootPrefix)) {
            throw SecurityException("解压目标超出 root: ${target.path}")
        }
        if (target == canonicalRoot) throw SecurityException("不允许以 root 作为解压文件目标")
        return target
    }

    private fun safeRelativeEntry(entryName: String): String {
        if (entryName.indexOf('\u0000') >= 0) throw SecurityException("ZIP 条目含 NUL: '$entryName'")
        if (entryName.indexOf('\\') >= 0) throw SecurityException("ZIP 条目含反斜线，拒绝解压: '$entryName'")
        if (entryName.startsWith("/")) throw SecurityException("ZIP 条目使用绝对路径，拒绝解压: '$entryName'")
        val segments = entryName.split('/')
        val cleaned = ArrayList<String>()
        for (segment in segments) {
            when {
                segment.isEmpty() || segment == "." -> continue
                segment == ".." -> throw SecurityException("ZIP 条目包含 ..，拒绝解压: '$entryName'")
            }
            val safe = buildString {
                for (ch in segment) {
                    append(
                        when {
                            ch == '?' || ch == '*' -> '_'
                            ch.isWhitespace() -> ' '
                            ch.code < 0x20 || ch.code == 0x7F -> '_'
                            else -> ch
                        },
                    )
                }
            }
            cleaned.add(safe.ifEmpty { "_" })
        }
        if (cleaned.isEmpty()) throw SecurityException("ZIP 条目路径为空，拒绝解压: '$entryName'")
        return cleaned.joinToString("/")
    }

    private fun validateFileEntryName(name: String) {
        if (name.indexOf('\u0000') >= 0) throw SecurityException("ZIP 条目含 NUL: '$name'")
        if (name.indexOf('\\') >= 0) throw SecurityException("ZIP 条目含反斜线，拒绝解压: '$name'")
        if (name.split('/').any { it == ".." }) {
            throw SecurityException("ZIP 条目包含 ..，拒绝解压: '$name'")
        }
    }

    private fun ensureNoSymlink(file: File, label: String) {
        val canonical = file.canonicalFile
        if (!samePath(file.absoluteFile.path, canonical.path)) {
            throw SecurityException("$label 含符号链接或不安全路径: ${file.path}")
        }
    }

    private fun isSameOrChild(child: File, parent: File): Boolean {
        val childPath = child.canonicalPath
        val parentPath = parent.canonicalPath.trimEnd(File.separatorChar)
        return childPath == parentPath || childPath.startsWith(parentPath + File.separator)
    }

    private fun sameFile(a: File, b: File): Boolean =
        samePath(a.canonicalPath, b.canonicalPath)

    private fun samePath(a: String, b: String): Boolean =
        if (File.separatorChar == '\\') a.equals(b, ignoreCase = true) else a == b

    private fun pathKey(file: File): String =
        if (File.separatorChar == '\\') file.canonicalPath.lowercase(Locale.US) else file.canonicalPath

    private fun parentDirOfFile(name: String): String {
        val index = name.lastIndexOf('/')
        return if (index >= 0) name.substring(0, index + 1) else ""
    }

    private fun parentDirOfDir(name: String): String {
        val trimmed = name.removeSuffix("/")
        val index = trimmed.lastIndexOf('/')
        return if (index >= 0) trimmed.substring(0, index + 1) else ""
    }

    private fun validateOutputName(name: String, directory: Boolean) {
        if (name.isEmpty()) throw IllegalArgumentException("ZIP 条目名不能为空")
        if (name.indexOf('\u0000') >= 0) throw SecurityException("ZIP 条目名不能包含 NUL")
        if (directory != name.endsWith("/")) {
            throw IllegalArgumentException("目录条目必须以 / 结尾: '$name'")
        }
    }

    private fun validateOutputNames(plans: List<OutputEntry>) {
        val seen = HashSet<String>()
        for (plan in plans) {
            validateOutputName(plan.name, plan.directory)
            if (!seen.add(plan.name)) throw IllegalArgumentException("输出 ZIP 含重复条目: '${plan.name}'")
        }
        validateFileDirectoryConflicts(plans.map { it.name to it.directory })
    }

    // ---------------------------------------------------------------------------------------------
    // Cursor / next action / JSON helpers
    // ---------------------------------------------------------------------------------------------

    private data class CursorState(val offset: Long)

    private fun encodeCursor(
        archive: Archive,
        entryPath: String,
        recursive: Boolean,
        simple: Boolean,
        offset: Long,
    ): String {
        val json = JsonObject().apply {
            addProperty("v", archive.version)
            addProperty("p", archive.file.canonicalPath)
            addProperty("e", entryPath)
            addProperty("r", recursive)
            addProperty("s", simple)
            addProperty("o", offset)
            addProperty("t", System.currentTimeMillis())
        }
        return CURSOR_PREFIX + toHex(json.toString().toByteArray(Charsets.UTF_8))
    }

    private fun decodeCursor(
        cursor: String,
        archive: Archive,
        entryPath: String,
        recursive: Boolean,
        simple: Boolean,
    ): CursorState {
        if (cursor.isEmpty()) return CursorState(0)
        if (cursor.length > MAX_CURSOR_TEXT) throw IllegalArgumentException("分页游标过长")
        if (!cursor.startsWith(CURSOR_PREFIX)) throw IllegalArgumentException("分页游标格式无效")
        val bytes = fromHex(cursor.removePrefix(CURSOR_PREFIX))
        val json = try {
            com.google.gson.JsonParser().parse(String(bytes, Charsets.UTF_8)).asJsonObject
        } catch (e: Exception) {
            throw IllegalArgumentException("分页游标格式无效", e)
        }
        val version = json.optionalString("v")
        if (version != archive.version) {
            throw IllegalArgumentException("ZIP 已变化（版本不一致），分页游标失效；请使用空 cursor 从头重新列出")
        }
        if (!samePath(json.optionalString("p"), archive.file.canonicalPath)) {
            throw IllegalArgumentException("分页游标属于另一个 ZIP 文件")
        }
        if (json.optionalString("e") != entryPath || json.optionalBoolean("r") != recursive) {
            throw IllegalArgumentException("分页游标与当前目录/递归参数不匹配")
        }
        val issuedAt = json.optionalLong("t")
        if (issuedAt <= 0 || System.currentTimeMillis() - issuedAt > CURSOR_TTL_MS) {
            throw IllegalArgumentException("分页游标已过期；请使用空 cursor 从头重新列出")
        }
        val offset = json.optionalLong("o")
        if (offset < 0 || offset > MAX_SAFE_INTEGER) throw IllegalArgumentException("分页游标偏移无效")
        return CursorState(offset)
    }

    private fun action(
        tool: String,
        purpose: String,
        description: String,
        arguments: JsonObject,
    ): JsonObject = JsonObject().apply {
        addProperty("tool", tool)
        addProperty("purpose", purpose)
        addProperty("description", description)
        add("arguments", arguments)
    }

    private fun JsonObject.requiredString(key: String): String {
        val element = get(key) ?: throw IllegalArgumentException("缺少参数 $key")
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
            throw IllegalArgumentException("参数 $key 必须是字符串")
        }
        return element.asString
    }

    private fun JsonObject.requiredBoolean(key: String): Boolean {
        val element = get(key) ?: throw IllegalArgumentException("缺少参数 $key")
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isBoolean) {
            throw IllegalArgumentException("参数 $key 必须是布尔值")
        }
        return element.asBoolean
    }

    private fun JsonObject.requiredInt(key: String, min: Int, max: Int): Int {
        val value = requiredLong(key, min.toLong(), max.toLong())
        return value.toInt()
    }

    private fun JsonObject.requiredLong(key: String, min: Long, max: Long): Long {
        val element = get(key) ?: throw IllegalArgumentException("缺少参数 $key")
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) {
            throw IllegalArgumentException("参数 $key 必须是整数")
        }
        val value = try { element.asLong } catch (e: Exception) {
            throw IllegalArgumentException("参数 $key 必须是整数")
        }
        val asDouble = element.asDouble
        if (asDouble.isNaN() || asDouble.isInfinite() || asDouble % 1.0 != 0.0) {
            throw IllegalArgumentException("参数 $key 必须是整数")
        }
        if (value < min || value > max) {
            throw IllegalArgumentException("参数 $key 超出范围 [$min, $max]: $value")
        }
        return value
    }

    private fun JsonObject.requiredArray(key: String): JsonArray {
        val element = get(key) ?: throw IllegalArgumentException("缺少参数 $key")
        if (!element.isJsonArray) throw IllegalArgumentException("参数 $key 必须是数组")
        return element.asJsonArray
    }

    private fun JsonObject.optionalString(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: ""

    private fun JsonObject.optionalBoolean(key: String): Boolean =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: false

    private fun JsonObject.optionalLong(key: String): Long =
        try { get(key)?.asLong ?: 0L } catch (_: Exception) { 0L }

    private fun JsonElement.asObject(label: String): JsonObject {
        if (!isJsonObject) throw IllegalArgumentException("$label 必须是对象")
        return asJsonObject
    }

    // ---------------------------------------------------------------------------------------------
    // Budget / byte helpers
    // ---------------------------------------------------------------------------------------------

    private class BudgetInputStream(
        delegate: InputStream,
        private var remaining: Long,
        private val overBudgetMessage: String,
    ) : FilterInputStream(delegate) {
        var consumed: Long = 0L
            private set

        override fun read(): Int {
            if (remaining <= 0L) {
                val probe = super.read()
                if (probe < 0) return -1
                throw IOException(overBudgetMessage)
            }
            val value = super.read()
            if (value >= 0) {
                remaining--
                consumed++
            }
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (remaining <= 0L) {
                val probe = super.read(buffer, offset, 1)
                if (probe < 0) return -1
                throw IOException(overBudgetMessage)
            }
            val allowed = minOf(length.toLong(), remaining).toInt()
            val count = super.read(buffer, offset, allowed)
            if (count > 0) {
                remaining -= count
                consumed += count
            }
            return count
        }
    }

    private fun addBudget(current: Long, size: Long): Long {
        if (size <= 0) return current
        val sum = saturatingAdd(current, size)
        if (sum > maxTotalUncompressed) {
            throw IOException("解压声明总大小超过预算 $maxTotalUncompressed")
        }
        return sum
    }

    private fun checkCompressionRatio(entries: List<CentralEntry>, label: String) {
        var totalSize = 0L
        var totalCompressed = 0L
        for (entry in entries) {
            if (entry.directory) continue
            val size = entry.size
            val compressed = entry.compressedSize
            if (size > RATIO_MIN_BYTES && compressed >= 0) {
                if (compressed == 0L && size > 0L) {
                    throw IOException("$label 预算拒绝：条目 '${entry.name}' 压缩后为 0 但声明 $size 字节")
                }
                if (compressed > 0) {
                    val ratio = size.toDouble() / compressed.toDouble()
                    if (ratio > maxCompressionRatio) {
                        throw IOException(
                            "$label 预算拒绝：条目 '${entry.name}' 压缩比 " +
                                "${"%.1f".format(Locale.US, ratio)} 超过上限 $maxCompressionRatio",
                        )
                    }
                }
            }
            totalSize = saturatingAdd(totalSize, size.coerceAtLeast(0))
            totalCompressed = saturatingAdd(totalCompressed, compressed.coerceAtLeast(0))
        }
        if (totalSize > RATIO_MIN_BYTES && totalCompressed > 0) {
            val ratio = totalSize.toDouble() / totalCompressed.toDouble()
            if (ratio > maxCompressionRatio) {
                throw IOException(
                    "$label 预算拒绝：整体压缩比 ${"%.1f".format(Locale.US, ratio)} 超过上限 $maxCompressionRatio",
                )
            }
        }
    }

    private fun saturatingAdd(a: Long, b: Long): Long {
        if (b <= 0) return a
        return if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b
    }

    private fun unavailable(value: Long): String = if (value < 0) "-" else value.toString()

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) {
            Thread.currentThread().interrupt()
            throw IOException("操作已取消：线程中断，结果未完成")
        }
    }

    private fun toHex(bytes: ByteArray, length: Int = bytes.size): String {
        val digits = "0123456789ABCDEF"
        val result = CharArray(length * 2)
        for (index in 0 until length) {
            val value = bytes[index].toInt() and 0xFF
            result[index * 2] = digits[value ushr 4]
            result[index * 2 + 1] = digits[value and 0x0F]
        }
        return String(result)
    }

    private fun fromHex(text: String): ByteArray {
        if (text.length % 2 != 0) throw IllegalArgumentException("分页游标十六进制长度无效")
        val result = ByteArray(text.length / 2)
        for (index in result.indices) {
            val high = Character.digit(text[index * 2], 16)
            val low = Character.digit(text[index * 2 + 1], 16)
            if (high < 0 || low < 0) throw IllegalArgumentException("分页游标十六进制内容无效")
            result[index] = ((high shl 4) or low).toByte()
        }
        return result
    }

    // ---------------------------------------------------------------------------------------------
    // Little-endian central directory helpers
    // ---------------------------------------------------------------------------------------------

    private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun putU32(bytes: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 4) {
            bytes[offset + index] = ((value ushr (8 * index)) and 0xFF).toByte()
        }
    }

    private fun u16(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 2 > bytes.size) throw IOException("ZIP 结构被截断")
        return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun u32(bytes: ByteArray, offset: Int): Long {
        if (offset < 0 || offset + 4 > bytes.size) throw IOException("ZIP 结构被截断")
        return (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)
    }

    private fun u64(bytes: ByteArray, offset: Int): Long {
        if (offset < 0 || offset + 8 > bytes.size) throw IOException("ZIP 结构被截断")
        var value = 0L
        for (index in 7 downTo 0) {
            value = (value shl 8) or (bytes[offset + index].toLong() and 0xFF)
        }
        return value
    }
}
