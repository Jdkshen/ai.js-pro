package com.jdkshen.aijspro.network

import java.io.File
import java.security.MessageDigest

// task-131/132：下载后的**强制**校验（sha256 + 大小）。纯逻辑，无 Android 依赖。
//
// 用户口径：插件 APK 不能只校包名；清单里的 sha256 必须逐字节校验，不符**不安装**。
object PluginVerifier {
    // 只允许 64 位小写 hex（生成侧就是这么写的）；pending 由调用方提前拦掉。
    @JvmStatic
    fun isHexSha256(value: String?): Boolean =
        value != null && Regex("^[0-9a-f]{64}$").matches(value)

    sealed class VerifyResult {
        object Ok : VerifyResult()
        data class ChecksumMismatch(val expected: String, val actual: String) : VerifyResult()
        data class SizeMismatch(val expected: Long, val actual: Long) : VerifyResult()
        data class Unreadable(val message: String) : VerifyResult()
    }

    @JvmStatic
    fun verify(file: File?, expectedSha256: String?, expectedSize: Long?): VerifyResult {
        if (file == null || !file.isFile) return VerifyResult.Unreadable("文件不存在")
        if (!isHexSha256(expectedSha256)) {
            return VerifyResult.ChecksumMismatch(expectedSha256 ?: "", "(清单未提供可校验的 sha256)")
        }
        val actual = try {
            sha256(file)
        } catch (error: Throwable) {
            return VerifyResult.Unreadable(error.message ?: error.javaClass.simpleName)
        }
        if (expectedSize != null && expectedSize > 0 && expectedSize != file.length()) {
            return VerifyResult.SizeMismatch(expectedSize, file.length())
        }
        val expected = expectedSha256!!
        if (!actual.equals(expected, ignoreCase = true)) {
            return VerifyResult.ChecksumMismatch(expected, actual)
        }
        return VerifyResult.Ok
    }

    @JvmStatic
    fun sha256(file: File): String = sha256(file.readBytes())

    @JvmStatic
    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val out = StringBuilder(digest.size * 2)
        for (b in digest) out.append(String.format("%02x", b))
        return out.toString()
    }
}
