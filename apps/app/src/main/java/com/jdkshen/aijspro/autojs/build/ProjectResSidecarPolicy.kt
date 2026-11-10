package com.jdkshen.aijspro.autojs.build

import java.io.File
import java.security.MessageDigest
import java.util.Locale

// task-131 批次 A（打包期 res 补丁，方案 ⑵）：样例工程资源包 project-res.apk(+json) 是在**构建期**
// 按当时的宿主编译的（tools/build-bundled-project-res.py，sidecar 里记的是那时 resources.arsc 的 sha256），
// 打进产物后**必然**与产物宿主的资源表不匹配。本类只做**纯判定**：
//
//   摘要相等        ⇒ KEEP（原样保留，默认行为不变）
//   摘要不同        ⇒ DROP（确定不匹配 ⇒ 丢掉 apk+json + 警告，绝不静默）
//   摘要缺失/不可读 ⇒ KEEP（判不出来就别动，宁可不修也不能误删）
//
// 依赖：只用 JDK（正则从 sidecar 文本里取摘要）——**故意不用 gson**：app 模块的 gson 是 2.8.2，
// 少一个第三方类型就少一个"离线绿/整包红"的坑。文案由调用方注入（W1 的 staleResourcePackMessage
// 是唯一来源，不在这里抄第二份）。
object ProjectResSidecarPolicy {

    const val SIDECAR_NAME = "project-res.json"
    const val APK_NAME = "project-res.apk"

    enum class Action { KEEP, DROP }

    data class Decision(val action: Action, val reason: String, val expectedDigest: String?, val actualDigest: String?)

    // 从 sidecar 文本里取「宿主 resources.arsc 的 sha256」。生成器写的是这个语义，但键名历史上变过，
    // 所以按「键名含 sha256 且含 arsc/resources」的宽容规则取，取不到就是未知（⇒ KEEP）。
    @JvmStatic
    fun extractHostDigest(sidecarText: String?): String? {
        if (sidecarText.isNullOrBlank()) return null
        val keyed = Regex("\"([A-Za-z0-9_]*sha256[A-Za-z0-9_]*)\"\\s*:\\s*\"([0-9a-fA-F]{64})\"")
            .findAll(sidecarText)
            .firstOrNull { match ->
                val key = match.groupValues[1].lowercase(Locale.ROOT)
                key.contains("arsc") || key.contains("resource") || key.contains("host")
            }
        if (keyed != null) return keyed.groupValues[2].lowercase(Locale.ROOT)
        // 兜底：只有 64 位 hex 也能用（sidecar 很小，且只记这一个摘要）
        return Regex("\"([0-9a-fA-F]{64})\"").find(sidecarText)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
    }

    @JvmStatic
    fun sha256(file: File?): String? = try {
        if (file == null || !file.isFile) null else {
            val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            val out = StringBuilder(digest.size * 2)
            for (b in digest) out.append(String.format("%02x", b))
            out.toString()
        }
    } catch (error: Throwable) {
        null
    }

    /**
     * @param sidecar  样例的 project-res.json（可空：缺 sidecar ⇒ 判不出来 ⇒ KEEP）
     * @param hostArsc 产物宿主的 resources.arsc（打包工作区里那份；可空 ⇒ KEEP）
     */
    @JvmStatic
    fun decide(sidecar: File?, hostArsc: File?): Decision {
        val expected = try {
            extractHostDigest(sidecar?.takeIf { it.isFile }?.readText())
        } catch (error: Throwable) {
            null
        }
        if (expected == null) {
            return Decision(Action.KEEP, "sidecar 里没有可用的宿主摘要 ⇒ 判不出来，保持原样", null, null)
        }
        val actual = sha256(hostArsc)
        if (actual == null) {
            return Decision(Action.KEEP, "读不到产物宿主的 resources.arsc ⇒ 判不出来，保持原样", expected, null)
        }
        return if (expected.equals(actual, ignoreCase = true)) {
            Decision(Action.KEEP, "摘要一致（"+ actual.substring(0, 12) + "…）⇒ 原样保留", expected, actual)
        } else {
            Decision(
                Action.DROP,
                "样例资源包是按别的宿主编的（期望 " + expected.substring(0, 12) + "… / 产物宿主 " + actual.substring(0, 12) + "…）⇒ 丢掉该 apk+json",
                expected, actual
            )
        }
    }

    /** 在样例目录里找同名的 sidecar/apk。 */
    @JvmStatic
    fun apkFor(sidecar: File?): File? = sidecar?.let { File(it.parentFile, APK_NAME) }
}
