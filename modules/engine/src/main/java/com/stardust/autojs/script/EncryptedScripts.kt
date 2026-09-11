package com.stardust.autojs.script

import android.util.Log

import com.stardust.autojs.engine.encryption.ScriptEncryption
import com.stardust.pio.PFiles
import java.io.File

/**
 * 打包产物脚本的统一入口：把「文件头 + 密文」还原成可以直接执行的脚本源。
 *
 * <p>以前解密只做在打包运行时的 Rhino 引擎里（`XJavaScriptEngine`），于是
 * **QuickJS 引擎的打包应用会拿密文当源码解析**（`illegal character` 之类的报错）。
 * 这里把「解密 + 按载荷类型分发」集中到一处，两种引擎共用。
 *
 * <p>载荷类型来自文件头 flags 的高字节（见 [EncryptedScriptFileHeader]）：
 * - 文本 → [StringScriptSource]
 * - Rhino 编译类 → [CompiledJavaScriptSource]
 * - QuickJS 字节码 → [QuickJsBytecodeSource]
 */
object EncryptedScripts {

    private const val TAG = "EncryptedScripts"

    /** 新方案（随机盐 + 签名指纹）的派生材料：由启动器在解密前注入。 */
    @Volatile
    private var hardenedPackageName: String? = null

    @Volatile
    private var hardenedSalt: String? = null

    @Volatile
    private var hardenedFingerprint: String? = null

    /**
     * 告诉这里「产物用的是新方案」：能用原生就用原生解密（密钥不落到 Java 侧）。
     *
     * <p>不设也能跑：走 JVM 侧的 [ScriptEncryption]（旧算法产物、或原生库不可用时）。
     */
    @JvmStatic
    fun setHardenedParams(packageName: String?, salt: String?, fingerprint: String?) {
        hardenedPackageName = packageName
        hardenedSalt = salt
        hardenedFingerprint = fingerprint
    }

    /**
     * @return 加密脚本对应的可执行脚本源；文件没有加密头时返回 null（调用方继续走普通文件源）。
     */
    @JvmStatic
    @JvmOverloads
    fun toSource(file: File, name: String = file.name): JavaScriptSource? {
        val bytes = PFiles.readBytes(file.path)
        if (!EncryptedScriptFileHeader.isValidFile(bytes)) {
            return null
        }
        val flags = EncryptedScriptFileHeader.readFlags(bytes)
        val plain = decryptBytes(bytes)
        return when (EncryptedScriptFileHeader.payloadTypeOf(flags)) {
            EncryptedScriptFileHeader.PAYLOAD_TYPE_RHINO_CLASS -> {
                val payload = CompiledScriptPayload.read(plain)
                CompiledJavaScriptSource(name, payload.className, payload.classBytes)
            }
            EncryptedScriptFileHeader.PAYLOAD_TYPE_QUICKJS_BYTECODE ->
                QuickJsBytecodeSource(name, plain)
            else -> StringScriptSource(name, String(plain, Charsets.UTF_8))
        }
    }

    /**
     * 解密：优先让原生库干活（密钥不落到 Java 侧），不可用或失败时回退 JVM 实现。
     *
     * <p>回退是刻意保留的：原生产物没打进包、ABI 被裁剪、或者原生自检发现异常，
     * 都不应该让用户的打包应用直接跑不起来。
     */
    private fun decryptBytes(bytes: ByteArray): ByteArray {
        val packageName = hardenedPackageName
        val salt = hardenedSalt
        val fingerprint = hardenedFingerprint
        if (packageName != null && salt != null && fingerprint != null
                && NativeScriptCrypto.isAvailable()) {
            try {
                return NativeScriptCrypto.decrypt(bytes, EncryptedScriptFileHeader.BLOCK_SIZE,
                        bytes.size - EncryptedScriptFileHeader.BLOCK_SIZE,
                        packageName, salt, fingerprint)
            } catch (error: Throwable) {
                Log.w(TAG, "原生解密失败，回退 JVM 实现", error)
            }
        }
        return ScriptEncryption.decrypt(bytes, EncryptedScriptFileHeader.BLOCK_SIZE)
    }

    /** 文件是否带加密头（打包产物）。 */
    @JvmStatic
    fun isEncrypted(file: File): Boolean {
        val bytes = PFiles.readBytes(file.path)
        return EncryptedScriptFileHeader.isValidFile(bytes)
    }
}
