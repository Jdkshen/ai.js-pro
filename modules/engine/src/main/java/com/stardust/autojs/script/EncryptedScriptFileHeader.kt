package com.stardust.autojs.script

import java.io.File
import java.io.FileInputStream
import java.io.OutputStream

object EncryptedScriptFileHeader {

    const val FLAG_INVALID_FILE: Short = Short.MIN_VALUE

    const val FLAG_EXECUTION_MODE_UI: Short = 0x0001
    const val FLAG_EXECUTION_MODE_AUTO: Short = 0x0002

    /** 载荷类型：纯文本脚本（历来的形式）。 */
    const val PAYLOAD_TYPE_TEXT: Int = 0
    /** 载荷类型：Rhino 编译产物（class 字节，见 ScriptCompiler / CompiledScriptPayload）。 */
    const val PAYLOAD_TYPE_RHINO_CLASS: Int = 1
    /** 载荷类型：QuickJS 字节码（预留）。 */
    const val PAYLOAD_TYPE_QUICKJS_BYTECODE: Int = 2

    /**
     * 载荷类型存放在 flags 的高字节：低字节留给执行模式等既有标记，
     * 这样旧运行时即使不认识类型位也能把低字节读出来。
     */
    private const val PAYLOAD_TYPE_SHIFT = 8

    const val BLOCK_SIZE = 8
    private val BLOCK = byteArrayOf(0x77, 0x01, 0x17, 0x7F, 0x12, 0x12)

    /** 从内存字节里读 flags（运行时解密前用）。 */
    fun readFlags(bytes: ByteArray): Short {
        if (bytes.size < BLOCK_SIZE || !isValidFile(bytes)) {
            return FLAG_INVALID_FILE
        }
        return (bytes[BLOCK.size].toShort() * 256 + bytes[BLOCK.size + 1]).toShort()
    }

    /** 取 flags 里的载荷类型。 */
    fun payloadTypeOf(flags: Short): Int = (flags.toInt() shr PAYLOAD_TYPE_SHIFT) and 0xFF

    /** 在保留低字节既有标记的前提下，写入载荷类型。 */
    @JvmOverloads
    fun flagsWithPayloadType(payloadType: Int, baseFlags: Short = 0): Short =
            ((baseFlags.toInt() and 0xFF) or ((payloadType and 0xFF) shl PAYLOAD_TYPE_SHIFT)).toShort()

    fun getHeaderFlags(file: File): Short {
        val fis = FileInputStream(file)
        val bytes = ByteArray(BLOCK_SIZE)
        if (fis.read(bytes) < BLOCK_SIZE) {
            return FLAG_INVALID_FILE
        }
        if (!isValidFile(bytes)) {
            return FLAG_INVALID_FILE
        }
        return (bytes[BLOCK.size].toShort() * 256 + bytes[BLOCK.size + 1]).toShort()
    }

    fun isValidFile(bytes: ByteArray): Boolean {
        for (i in 0 until BLOCK.size) {
            if (bytes[i] != BLOCK[i]) {
                return false
            }
        }
        return true
    }

    fun writeHeader(os: OutputStream, flags: Short = 0) {
        os.write(BLOCK)
        val byte6 = flags / 256
        val byte7 = flags % 256
        os.write(byte6)
        os.write(byte7)
    }


}