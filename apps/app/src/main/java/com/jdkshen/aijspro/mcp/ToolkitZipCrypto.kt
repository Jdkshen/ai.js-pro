package com.jdkshen.aijspro.mcp

import net.lingala.zip4j.core.ZipFile
import net.lingala.zip4j.crypto.engine.ZipCryptoEngine
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.io.ZipInputStream as Zip4jInputStream
import net.lingala.zip4j.model.FileHeader
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.util.Zip4jConstants
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.charset.Charset

/**
 * ZipCrypto/AES bridge used by [McpZipToolkit].
 *
 * The current ZIP toolkit keeps its own central-directory parser and raw-entry writer so that
 * unchanged entries can be copied without recompression. This object only wraps zip4j 1.3.2 for
 * the two operations that would otherwise require a second ZIP implementation:
 *
 *  - authenticated decryption of AES-256 and ZipCrypto entries, and
 *  - AES-256 encryption of new or replaced file entries.
 *
 * Zip4j 1.3.2 is already an `api` dependency of `:engine`; this file intentionally does not add
 * any Gradle dependency and does not use the 2.x package names.
 */
internal object ToolkitZipCrypto {
    private const val AES_EXTRA_ID = 0x9901
    private const val METHOD_STORED = 0
    private const val METHOD_DEFLATED = 8
    private const val METHOD_AES = 99

    /**
     * Opens an encrypted entry and returns a stream that decrypts it.
     *
     * [verifyOnClose] must only be true when the caller reads the entry to EOF. Zip4j performs its
     * CRC/HMAC check in `ZipInputStream.close`; partially-read previews must close with the check
     * disabled, otherwise a valid entry would be reported as corrupt. Full reads (`zip_test`,
     * `zip_extract`) pass true so a wrong password or a modified CRC/MAC is detected.
     */
    fun openEntry(
        archive: File,
        entryName: String,
        charset: Charset,
        password: String,
        verifyOnClose: Boolean,
    ): InputStream {
        val zip = try {
            ZipFile(archive)
        } catch (e: ZipException) {
            throw IOException("无法打开加密 ZIP '${archive.path}': ${e.message}", e)
        }
        try {
            // Always make the name charset explicit. Legacy archives otherwise fall back to the
            // JVM/Android default charset and getFileHeader(entryName) would not find the entry.
            zip.setFileNameCharset(charset.name())
            val header = zip.getFileHeader(entryName)
                ?: throw IOException("加密 ZIP 中不存在条目 '$entryName'")
            if (header.isEncrypted && password.isEmpty()) throw IOException("条目 '$entryName' 已加密，需要非空 password")
            // The pinned 1.x PBKDF2 implementation narrows char values to bytes. Supply the
            // UTF-8 bytes losslessly as Latin-1 chars, as required by interoperable AES ZIPs.
            if(header.isEncrypted) zip.setPassword(if (header.encryptionMethod == Zip4jConstants.ENC_METHOD_AES) aesPassword(password) else password)
            // zip4j 1.3.2 initializes the ZipCrypto keys but never compares the 1-byte password
            // check value, so a wrong password would only surface as garbage/CRC failure after a
            // full read. Validate that byte here to make previews fail correctly too.
            if (header.isEncrypted && header.encryptionMethod == Zip4jConstants.ENC_METHOD_STANDARD) {
                checkZipCryptoPassword(archive, header, password)
            }
            val stream = zip.getInputStream(header)
            return VerifyingEntryStream(stream, entryName, verifyOnClose)
        } catch (e: ZipException) {
            throw decryptionFailure(entryName, e)
        }
    }

    /**
     * Reads an AES entry to EOF once more and lets zip4j validate the authentication code. The
     * normal `zip_test` pass already checked length and plaintext CRC; this extra pass is only for
     * authenticated encryption (HMAC), which is not visible to the CRC check.
     */
    fun verifyAuthentication(
        archive: File,
        entryName: String,
        charset: Charset,
        password: String,
        maxBytes: Long,
        checkInterrupted: () -> Unit,
    ) {
        val input = openEntry(archive, entryName, charset, password, verifyOnClose = true)
        var completed = false
        var failure: Throwable? = null
        try {
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                checkInterrupted()
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > maxBytes) {
                    throw IOException(
                        "加密条目 '$entryName' 认证读取超过预算 $maxBytes 字节，不能判定为通过",
                    )
                }
            }
            completed = true
        } catch (e: Throwable) {
            failure = e
            throw e
        } finally {
            try {
                if (completed) {
                    input.close()
                } else {
                    (input as? VerifyingEntryStream)?.closeWithoutVerification()
                        ?: input.close()
                }
            } catch (e: IOException) {
                // Do not let a close-time CRC error hide the original budget/interrupt error.
                if (failure == null) throw decryptionFailure(entryName, e)
            }
        }
    }

    /**
     * Writes exactly one AES-256 entry to [output]. [output] must not already exist. The caller
     * subsequently copies the raw local header/data into the real output archive, which keeps the
     * AES salt/verifier/MAC bytes intact.
     */
    fun createEncryptedZip(
        output: File,
        source: File,
        entryName: String,
        method: Int,
        level: Int,
        password: String,
    ) {
        require(password.isNotEmpty()) { "AES 加密需要非空 password" }
        if (method != METHOD_STORED && method != METHOD_DEFLATED) {
            throw IOException("AES 加密仅支持 Stored(0) 或 Deflate(8)，收到 $method")
        }
        if (output.exists()) throw IOException("临时加密 ZIP 已存在: ${output.path}")
        val parameters = ZipParameters().apply {
            setCompressionMethod(
                if (method == METHOD_STORED) Zip4jConstants.COMP_STORE
                else Zip4jConstants.COMP_DEFLATE,
            )
            if (method == METHOD_DEFLATED) setCompressionLevel(level.coerceIn(0, 9))
            setEncryptFiles(true)
            setEncryptionMethod(Zip4jConstants.ENC_METHOD_AES)
            setAesKeyStrength(Zip4jConstants.AES_STRENGTH_256)
            setPassword(aesPassword(password))
            setSourceExternalStream(true)
            setFileNameInZip(entryName)
            setReadHiddenFiles(true)
        }
        try {
            val zip = ZipFile(output)
            zip.setFileNameCharset("UTF-8")
            FileInputStream(source).use { input ->
                zip.addStream(input, parameters)
            }
        } catch (e: ZipException) {
            output.delete()
            throw IOException("无法创建 AES-256 加密条目 '$entryName': ${e.message}", e)
        } catch (e: Exception) {
            output.delete()
            if (e is IOException) throw e
            throw IOException("无法创建 AES-256 加密条目 '$entryName': ${e.message}", e)
        }
    }

    /** Checks whether the 0x9901 AES extra record describes a supported AES entry. */
    fun aesCompressionMethod(aesExtra: ByteArray): Int {
        if (aesExtra.size < 11 || readU16(aesExtra, 0) != AES_EXTRA_ID || readU16(aesExtra, 2) != 7) {
            throw IOException("AES 条目 extra 字段无效")
        }
        val method = readU16(aesExtra, 9)
        if (method != METHOD_STORED && method != METHOD_DEFLATED) {
            throw IOException("AES 条目使用不支持的压缩算法 $method；仅支持 Stored(0) 与 Deflate(8)")
        }
        return method
    }

    /** True when the entry header method means AES-256/AES encryption (method 99). */
    fun isAes(method: Int): Boolean = method == METHOD_AES

    private fun aesPassword(value: String): String = String(value.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)

    /**
     * Validates the traditional ZipCrypto 12-byte header check byte. The last decrypted header
     * byte is the high byte of the DOS time when a data descriptor is used, otherwise the high
     * byte of the plaintext CRC. A mismatch means the password is wrong (barring a 1/256 false
     * positive, which the full CRC check in zip_test/extract still catches).
     */
    private fun checkZipCryptoPassword(archive: File, header: FileHeader, password: String) {
        val dataOffset = localDataOffset(archive, header.offsetLocalHeader) ?: return
        val encryptedHeader = ByteArray(12)
        RandomAccessFile(archive, "r").use { raf ->
            if (dataOffset + encryptedHeader.size > raf.length()) return
            raf.seek(dataOffset)
            raf.readFully(encryptedHeader)
        }
        val engine = ZipCryptoEngine()
        engine.initKeys(password.toCharArray())
        var checkByte = 0
        for (index in encryptedHeader.indices) {
            val plain = ((encryptedHeader[index].toInt() and 0xFF) xor
                (engine.decryptByte().toInt() and 0xFF)) and 0xFF
            engine.updateKeys(plain.toByte())
            if (index == encryptedHeader.lastIndex) checkByte = plain
        }
        // The ZIP spec allows either check byte depending on the data-descriptor bit, but zip4j
        // 1.3.2 itself always writes the DOS-time byte. Accept both forms for reading so archives
        // from zip4j, MT and other standard writers are all readable; the full CRC check in
        // zip_test/zip_extract remains authoritative.
        val expectedByCrc = ((header.crc32 ushr 24) and 0xFFL).toInt()
        val expectedByTime = (header.lastModFileTime ushr 8) and 0xFF
        if (checkByte != expectedByCrc && checkByte != expectedByTime) {
            throw IOException("加密条目 '${header.fileName}' 密码错误")
        }
    }

    private fun localDataOffset(archive: File, localHeaderOffset: Long): Long? {
        if (localHeaderOffset < 0) return null
        RandomAccessFile(archive, "r").use { raf ->
            if (localHeaderOffset > raf.length() - 30) return null
            raf.seek(localHeaderOffset)
            val local = ByteArray(30)
            raf.readFully(local)
            if ((local[0].toInt() and 0xFF) != 0x50 ||
                (local[1].toInt() and 0xFF) != 0x4B ||
                (local[2].toInt() and 0xFF) != 0x03 ||
                (local[3].toInt() and 0xFF) != 0x04
            ) {
                return null
            }
            val nameLength = (local[26].toInt() and 0xFF) or ((local[27].toInt() and 0xFF) shl 8)
            val extraLength = (local[28].toInt() and 0xFF) or ((local[29].toInt() and 0xFF) shl 8)
            val dataOffset = localHeaderOffset + 30L + nameLength + extraLength
            return if (dataOffset in 0L..raf.length()) dataOffset else null
        }
    }

    private fun decryptionFailure(entryName: String, error: Throwable): IOException {
        val message = error.message.orEmpty()
        val reason = when {
            message.contains("Wrong Password", ignoreCase = true) ||
                message.contains("password", ignoreCase = true) -> "密码错误"
            message.contains("CRC", ignoreCase = true) ||
                message.contains("MAC", ignoreCase = true) -> "CRC/HMAC 校验失败"
            else -> "解密失败"
        }
        return IOException("加密条目 '$entryName' $reason: ${message.ifEmpty { error.javaClass.simpleName }}", error)
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 2 > bytes.size) throw IOException("AES extra 字段被截断")
        return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    /**
     * Closing a partially-read zip4j stream must skip `checkCRC`; closing a fully-read stream must
     * run it. `ZipInputStream.close(boolean)` exposes that switch but `FilterInputStream` does not,
     * so route close explicitly instead of delegating to `in.close()`.
     */
    private class VerifyingEntryStream(
        private val delegate: Zip4jInputStream,
        private val entryName: String,
        private val verifyOnClose: Boolean,
    ) : FilterInputStream(delegate) {
        private var closed = false

        override fun close() {
            closeInternal(verifyOnClose)
        }

        /** Used when the caller failed before EOF and must not turn that failure into a CRC error. */
        fun closeWithoutVerification() {
            closeInternal(false)
        }

        private fun closeInternal(verify: Boolean) {
            if (closed) return
            closed = true
            try {
                delegate.close(!verify)
            } catch (e: ZipException) {
                throw decryptionFailure(entryName, e)
            } catch (e: IOException) {
                throw decryptionFailure(entryName, e)
            }
        }
    }
}
