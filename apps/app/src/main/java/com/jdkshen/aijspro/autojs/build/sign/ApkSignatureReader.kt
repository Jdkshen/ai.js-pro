package com.jdkshen.aijspro.autojs.build.sign

import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.zip.ZipFile

/**
 * 读取**已经打包好的 APK** 实际使用的签名证书。
 *
 * 打包页用它把「产物到底是谁签的」直接写进成功提示里：光看配置项容易以为换了证书，
 * 实际产物可能还是内置公共证书（默认签名的 CN=Test），这一步是唯一可信的自证。
 *
 * 只读 v1 的 META-INF 证书条目（后缀 .RSA/.DSA/.EC）：tiny-sign 和自研签名器都会写 v1，
 * 覆盖我们所有产物形态。
 */
object ApkSignatureReader {

    private val CERT_EXTENSIONS = listOf("RSA", "DSA", "EC")

    data class Signer(val subject: String, val sha256: String) {
        /** 指纹太长，界面上只取前 16 位够区分不同证书。 */
        val shortFingerprint: String
            get() = sha256.replace(":", "").take(16)
    }

    fun read(apk: File): Signer? {
        if (!apk.isFile) return null
        return try {
            ZipFile(apk).use { zip ->
                val entry = zip.entries().asSequence().firstOrNull { entry ->
                    val name = entry.name.uppercase(Locale.US)
                    name.startsWith("META-INF/") && CERT_EXTENSIONS.any { name.endsWith(".$it") }
                } ?: return null
                val certificate = zip.getInputStream(entry).use { input ->
                    CertificateFactory.getInstance("X.509").generateCertificate(input) as X509Certificate
                }
                Signer(certificate.subjectX500Principal.name, sha256(certificate.encoded))
            }
        } catch (error: Exception) {
            null
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { String.format(Locale.US, "%02x", it) }
}
