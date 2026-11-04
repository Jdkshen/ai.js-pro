package com.jdkshen.aijspro.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * §11 可验证的二进制变换：XOR / 字节序 / 索引排列 / AES 分段解密 / 分段解压 / 指针表恢复。
 *
 * 复盘要求「不要为每种算法新增一堆碎片工具」，而是统一记录：输入身份与哈希、输入范围、变换与参数来源、
 * 输出哈希、验证断言，以及「分析副本还是运行时修改」的分类。
 *
 * 本模块只做纯计算：默认 `analysisOnly=true`（结果只用于分析，不进构建产物）；
 * 调用方若显式传 false 才表示这是要落盘的修改。任何 op 都不会声称「全部解密成功」——
 * 只报告断言结果与 `verified`，由调用方结合证据判断。
 */
internal object ToolkitTransforms {
    const val MAX_RANGE_BYTES = 16L * 1024 * 1024

    data class TransformOutcome(
        val bytes: ByteArray,
        val evidence: JsonObject
    )

    /** op 与参数；`paramsSource` 用于记录参数从哪来（调用方提供/推断/配置）。 */
    fun apply(input: ByteArray, rangeStart: Int, rangeEnd: Int, op: String,
              params: Map<String, String> = emptyMap(), analysisOnly: Boolean = true,
              paramsSource: String = "caller_provided"): TransformOutcome {
        require(rangeStart in 0..input.size && rangeEnd in rangeStart..input.size) {
            "范围越界：input=" + input.size + " range=" + rangeStart + ".." + rangeEnd
        }
        val length = rangeEnd - rangeStart
        require(length <= MAX_RANGE_BYTES) { "范围超过 16 MiB 上限：" + length }
        val chunk = input.copyOfRange(rangeStart, rangeEnd)
        val output = when (op.lowercase()) {
            "xor" -> xor(chunk, params["keyHex"] ?: params["key"] ?: error("xor 需要 keyHex"))
            "bswap" -> bswap(chunk, params["width"]?.toIntOrNull() ?: error("bswap 需要 width"))
            "index_permute" -> indexPermute(chunk, params["permutation"] ?: error("index_permute 需要 permutation"))
            "aes_decrypt" -> aesDecrypt(chunk, params)
            "inflate" -> inflate(chunk, params["mode"] ?: "zlib")
            "pointer_table_restore" -> pointerTableRestore(chunk, params)
            else -> throw IllegalArgumentException("不支持的变换：" + op)
        }
        // 等长变换（xor/bswap/排列/指针恢复）可以原位替换；变长变换（AES 去填充、解压）
        // 长度会变，不能硬塞回原缓冲区，否则尾部会残留旧字节——直接返回变换结果并标注模式。
        val result: ByteArray
        val resultMode: String
        if (output.size == length) {
            result = input.copyOf()
            System.arraycopy(output, 0, result, rangeStart, output.size)
            resultMode = "spliced_full_input"
        } else {
            result = output
            resultMode = "chunk_only"
        }
        val assertions = JsonArray()
        val requested = params["expectMagic"] ?: params["expect"]
        if (requested != null) assertions.add(assertMagic(output, requested))
        if (params.containsKey("minLength") || params.containsKey("maxLength")) {
            assertions.add(assertLength(output, params))
        }
        if (op.equals("index_permute", true)) assertions.add(assertPermutation(params["permutation"]!!))
        if (params.containsKey("expectCrc32")) assertions.add(assertCrc32(output, params["expectCrc32"]!!))
        if (op.equals("pointer_table_restore", true)) assertions.addAll(assertPointerRange(output, params))
        val verified = assertions.all { it.asJsonObject.get("passed").asBoolean }
        val evidence = ToolkitIo.json(
            "op" to op.lowercase(),
            "range" to ToolkitIo.json("start" to rangeStart, "end" to rangeEnd, "bytes" to length),
            "inputSha256" to sha256(input),
            "rangeSha256" to sha256(chunk),
            "outputSha256" to sha256(output),
            "resultSha256" to sha256(result),
            "params" to sanitizedParams(op, params),
            "paramsSource" to paramsSource,
            "classification" to ToolkitIo.json(
                "analysisOnly" to analysisOnly,
                "mayFlowIntoBuild" to !analysisOnly,
                "note" to if (analysisOnly) "仅分析副本；不得默认流入构建产物" else "调用方声明为可落盘修改"
            ),
            "assertions" to assertions,
            "verified" to verified,
            "verifiedNote" to if (verified) "所有已请求的断言通过；这不等于“算法/密钥已被证实”"
                else "存在未通过或未请求的断言，不能声称全部解密成功",
            "outputBytes" to output.size,
            "resultMode" to resultMode,
            "resultLengthDelta" to (output.size - length),
            "resultModeNote" to if (resultMode == "chunk_only") {
                "变换结果长度与输入范围不同，返回值只有变换后的数据；调用方需自行决定如何写回"
            } else "变换结果等长，已原位替换到输入副本的指定范围"
        )
        return TransformOutcome(result, evidence)
    }

    /** 只保留非敏感参数值（密钥/IV 只记录长度与哈希前 8 位）。 */
    private fun sanitizedParams(op: String, params: Map<String, String>): JsonObject {
        val json = JsonObject()
        params.forEach { (key, value) ->
            val lower = key.lowercase()
            if (lower.contains("key") || lower.contains("iv") || lower.contains("token")) {
                // 十六进制参数按解码后的字节数报长度，避免把 32 个 hex 字符说成 32 字节。
                val trimmed = value.trim().removePrefix("0x")
                val byteLength = if (lower.endsWith("hex")) trimmed.length / 2 else value.length
                json.addProperty(key, "len=" + byteLength + " sha256=" + sha256(value.toByteArray()).take(8))
            } else {
                json.addProperty(key, value.take(4096))
            }
        }
        json.addProperty("op", op.lowercase())
        return json
    }

    private fun xor(input: ByteArray, keyHex: String): ByteArray {
        val key = hexToBytes(keyHex)
        require(key.isNotEmpty()) { "keyHex 不能为空" }
        return ByteArray(input.size) { index -> (input[index].toInt() xor key[index % key.size].toInt()).toByte() }
    }

    private fun bswap(input: ByteArray, width: Int): ByteArray {
        require(width == 2 || width == 4 || width == 8) { "width 只支持 2/4/8" }
        val output = input.copyOf()
        var offset = 0
        while (offset + width <= output.size) {
            for (index in 0 until width / 2) {
                val left = offset + index
                val right = offset + width - 1 - index
                val swap = output[left]
                output[left] = output[right]
                output[right] = swap
            }
            offset += width
        }
        return output
    }

    private fun indexPermute(input: ByteArray, permutation: String): ByteArray {
        val order = permutation.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map {
            it.toIntOrNull() ?: throw IllegalArgumentException("排列项不是整数：" + it)
        }
        require(order.size == input.size) { "排列长度 " + order.size + " 与输入长度 " + input.size + " 不一致" }
        val seen = BooleanArray(order.size)
        order.forEach { source ->
            require(source in order.indices) { "排列下标越界：" + source }
            require(!seen[source]) { "排列有重复下标：" + source }
            seen[source] = true
        }
        return ByteArray(input.size) { index -> input[order[index]] }
    }

    private fun aesDecrypt(input: ByteArray, params: Map<String, String>): ByteArray {
        val key = hexToBytes(params["keyHex"] ?: error("aes_decrypt 需要 keyHex"))
        require(key.size == 16 || key.size == 24 || key.size == 32) { "AES 密钥长度必须 16/24/32 字节" }
        val mode = (params["mode"] ?: "CBC").uppercase()
        val transformation = if (mode == "ECB") "AES/ECB/PKCS5Padding" else "AES/CBC/PKCS5Padding"
        val cipher = Cipher.getInstance(transformation)
        val keySpec = SecretKeySpec(key, "AES")
        if (mode == "ECB") {
            cipher.init(Cipher.DECRYPT_MODE, keySpec)
        } else {
            val iv = hexToBytes(params["ivHex"] ?: error("CBC 模式需要 ivHex"))
            require(iv.size == 16) { "AES IV 必须 16 字节" }
            cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(iv))
        }
        return cipher.doFinal(input)
    }

    private fun inflate(input: ByteArray, mode: String): ByteArray {
        val inflater = Inflater(mode.equals("raw", true))
        inflater.setInput(input)
        val output = java.io.ByteArrayOutputStream(input.size * 4)
        val buffer = ByteArray(16 * 1024)
        try {
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count <= 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                output.write(buffer, 0, count)
                if (output.size() > MAX_RANGE_BYTES) throw IllegalArgumentException("解压结果超过 16 MiB 上限")
            }
        } finally {
            inflater.end()
        }
        return output.toByteArray()
    }

    private fun pointerTableRestore(input: ByteArray, params: Map<String, String>): ByteArray {
        val width = params["width"]?.toIntOrNull() ?: 4
        require(width == 4 || width == 8) { "指针宽度只支持 4/8" }
        val base = params["baseAddress"]?.let { parseLong(it) } ?: throw IllegalArgumentException("需要 baseAddress")
        val output = input.copyOf()
        var offset = 0
        while (offset + width <= output.size) {
            var value = 0L
            for (index in 0 until width) value = value or ((output[offset + index].toLong() and 0xff) shl (8 * index))
            val restored = value + base
            for (index in 0 until width) {
                output[offset + index] = ((restored ushr (8 * index)) and 0xff).toByte()
            }
            offset += width
        }
        return output
    }

    private fun parseLong(value: String): Long = if (value.startsWith("0x", true)) {
        value.substring(2).toLong(16)
    } else value.toLong()

    private fun assertMagic(output: ByteArray, expectedHex: String): JsonObject {
        val magic = try { hexToBytes(expectedHex) } catch (error: IllegalArgumentException) { ByteArray(0) }
        val actual = output.copyOfRange(0, minOf(magic.size, output.size))
        val passed = magic.isNotEmpty() && actual.contentEquals(magic)
        return ToolkitIo.json("kind" to "magic", "passed" to passed,
            "expected" to expectedHex, "actual" to bytesToHex(actual),
            "note" to if (passed) "魔数匹配" else "魔数不匹配，不能声称解密成功")
    }

    private fun assertLength(output: ByteArray, params: Map<String, String>): JsonObject {
        val min = params["minLength"]?.toIntOrNull()
        val max = params["maxLength"]?.toIntOrNull()
        val passed = (min == null || output.size >= min) && (max == null || output.size <= max)
        return ToolkitIo.json("kind" to "length", "passed" to passed, "actual" to output.size,
            "min" to min, "max" to max)
    }

    private fun assertPermutation(permutation: String): JsonObject {
        val order = permutation.split(',').mapNotNull { it.trim().toIntOrNull() }
        val complete = order.size == order.toSet().size && order.isNotEmpty()
        return ToolkitIo.json("kind" to "permutationCompleteness", "passed" to complete,
            "entries" to order.size, "distinct" to order.toSet().size)
    }

    private fun assertCrc32(output: ByteArray, expectedHex: String): JsonObject {
        val crc = CRC32().apply { update(output) }.value
        val expected = parseLong(expectedHex) and 0xffffffffL
        return ToolkitIo.json("kind" to "crc32", "passed" to (crc == expected),
            "expected" to java.lang.Long.toHexString(expected), "actual" to java.lang.Long.toHexString(crc))
    }

    private fun assertPointerRange(output: ByteArray, params: Map<String, String>): JsonArray {
        val width = params["width"]?.toIntOrNull() ?: 4
        val low = params["lowAddress"]?.let { parseLong(it) }
        val high = params["highAddress"]?.let { parseLong(it) }
        if (low == null || high == null) {
            return JsonArray().apply { add(ToolkitIo.json("kind" to "pointerRange", "passed" to false,
                "note" to "未提供 lowAddress/highAddress，未验证指针范围")) }
        }
        var offset = 0; var checked = 0; var inside = 0
        while (offset + width <= output.size) {
            var value = 0L
            for (index in 0 until width) value = value or ((output[offset + index].toLong() and 0xff) shl (8 * index))
            checked++
            if (value in low..high) inside++
            offset += width
        }
        val passed = checked > 0 && inside == checked
        return JsonArray().apply { add(ToolkitIo.json("kind" to "pointerRange", "passed" to passed,
            "checked" to checked, "inside" to inside, "low" to low, "high" to high)) }
    }

    fun hexToBytes(hex: String): ByteArray {
        val value = hex.trim().removePrefix("0x").replace(" ", "")
        require(value.length % 2 == 0) { "十六进制长度必须是偶数：" + value.length }
        val output = ByteArray(value.length / 2)
        for (index in output.indices) {
            output[index] = value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        return output
    }

    fun bytesToHex(bytes: ByteArray): String {
        val builder = StringBuilder(bytes.size * 2)
        bytes.forEach { builder.append(String.format("%02x", it.toInt() and 0xff)) }
        return builder.toString()
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return bytesToHex(digest)
    }
}
