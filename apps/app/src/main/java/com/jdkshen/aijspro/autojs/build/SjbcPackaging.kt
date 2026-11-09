package com.jdkshen.aijspro.autojs.build

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.security.MessageDigest
import java.security.cert.Certificate

/**
 * task-80：SJBC（L3 自定义 VM）随包分发的**收集与 manifest 规则**（纯逻辑，可单测）。
 *
 * 依据 device4 的 md（Download/DSH-Go/AIjsPro-挂载SJBC到手机打包.md）：
 *  - 接法 A/B 都要求把 .sjbc + manifest.json 放进项目的 assets 目录 → 产物里是 assets/sjbc/（目录名见 TARGET_DIR）；
 *  - **不要把 .sjbc 当成「脚本加密产物」再套壳**：本类只做「原样收集 + manifest 的 certSha 对齐」，
 *    不调用任何脚本加密/编译流程。
 *  - certSha 必须是**最终装到手机上的那个 APK 的签名证书 SHA-256**（md §三.1）：换证书 → 必须重编脚本，
 *    所以 manifest 里旧值与新值不一致时给 warning（机器可查）。
 */
object SjbcPackaging {
    const val TARGET_DIR = "assets/sjbc"
    private const val MANIFEST = "manifest.json"
    private val PROJECT_SOURCE_DIRS = listOf("assets/sjbc", "sjbc")

    data class Entry(val relativePath: String, val source: File)

    data class Stage(val entries: List<Entry>, val sourceDir: File?) {
        val hasManifest: Boolean get() = entries.any { it.relativePath == MANIFEST || it.relativePath.endsWith("/" + MANIFEST) }
        fun sjbcCount(): Int = entries.count { it.relativePath.endsWith(".sjbc") }
    }

    data class PatchResult(val json: String, val changed: Boolean, val previousCertSha: String?, val warning: String?)

    /** 工程里第一个存在的 SJBC 源目录（约定 assets/sjbc，兼容 sjbc/）。 */
    @JvmStatic
    fun findSourceDir(projectDir: File?): File? {
        if (projectDir == null || !projectDir.isDirectory) return null
        for (relative in PROJECT_SOURCE_DIRS) {
            val dir = File(projectDir, relative)
            if (dir.isDirectory) return dir
        }
        return null
    }

    /**
     * 收集要随包分发的文件：**只收 .sjbc 与 manifest.json**（不动别的资源，避免改变既有 assets/excluded 策略）。
     * 返回相对目标目录的路径（保持子目录结构）。
     */
    @JvmStatic
    fun collect(sourceDir: File?): Stage {
        if (sourceDir == null || !sourceDir.isDirectory) return Stage(emptyList(), null)
        val entries = ArrayList<Entry>()
        collectInto(sourceDir, "", entries)
        entries.sortBy { it.relativePath }
        return Stage(entries, sourceDir)
    }

    private fun collectInto(dir: File, prefix: String, out: MutableList<Entry>) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child == null) continue
            val relative = if (prefix.isEmpty()) child.name else prefix + "/" + child.name
            if (child.isDirectory) {
                collectInto(child, relative, out)
            } else if (child.name.endsWith(".sjbc") || child.name == MANIFEST) {
                out.add(Entry(relative, child))
            }
        }
    }

    /**
     * 把 manifest 的 certSha 对齐到本次签名证书；旧值不同 → warning（换证书必须重编脚本）。
     * manifest 不存在/为空时合成最小 manifest（只写真实已知字段：certSha/package/files）。
     */
    @JvmStatic
    fun patchManifest(manifestJson: String?, certSha: String?, packageName: String?, sjbcFiles: List<String>): PatchResult {
        val text = manifestJson?.trim().orEmpty()
        // 用 gson 而不是 org.json：org.json 在 JVM 单测里是 android.jar stub（"Stub!"），
        // 而 gson 在 app 单测 classpath 上可用 → 这段纯逻辑才能被测到。
        val json = if (text.isEmpty()) JsonObject() else {
            try {
                JsonParser().parse(text).asJsonObject
            } catch (error: Throwable) {
                // 既有的 manifest 解析不了：不猜内容，直接报错（调用方按 warning 处理并保留原文件）。
                throw IllegalArgumentException("manifest.json 不是合法 JSON：" + (error.message ?: error.javaClass.simpleName))
            }
        }
        fun stringOf(key: String): String? = json.get(key)?.takeIf { it.isJsonPrimitive }?.asString
        val previous = stringOf("certSha")?.takeIf { it.isNotEmpty() }
        var changed = false
        if (!certSha.isNullOrEmpty()) {
            if (previous != certSha) {
                json.addProperty("certSha", certSha)
                changed = true
            }
        }
        val version = json.get("version")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
        if (version <= 0) json.addProperty("version", 1)
        if (!packageName.isNullOrEmpty() && stringOf("package").isNullOrEmpty()) json.addProperty("package", packageName)
        val files = json.get("files")
        if (files == null || !files.isJsonArray) {
            val array = JsonArray()
            sjbcFiles.forEach { array.add(it) }
            json.add("files", array)
        }
        val warning = when {
            certSha.isNullOrEmpty() -> "sjbc: certSha 未提供（签名证书取不到）→ manifest 未写 certSha，运行期可能解不开"
            previous == null -> "sjbc: manifest 原本没有 certSha，已写入本次签名证书指纹（首次对齐）"
            previous != certSha -> "sjbc: 证书指纹变了（旧 " + previous.take(16) + "… → 新 " + certSha.take(16) +
                "…）→ **必须重编全部 .sjbc**，否则运行期 HKDF 派生出的密钥不匹配"
            else -> null
        }
        val buffer = java.io.StringWriter()
        val writer = com.google.gson.stream.JsonWriter(buffer)
        writer.setIndent("  ")
        writer.jsonValue(json.toString())
        writer.flush()
        return PatchResult(buffer.toString(), changed, previous, warning)
    }

    /** 签名证书的 SHA-256（小写 hex）；certificate 为 null 返回 null。 */
    @JvmStatic
    fun certSha256(certificate: Certificate?): String? {
        if (certificate == null) return null
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        val builder = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xFF
            if (value < 0x10) builder.append('0')
            builder.append(Integer.toHexString(value))
        }
        return builder.toString()
    }

    /**
     * 把签名指纹归一成 SJBC 工具链要的形态：小写 hex（AutoSigningIdentity.certificateFingerprint()
     * 给的是冒号分隔大写，例如 AA:BB:…）。
     */
    @JvmStatic
    fun normalizeCertSha(fingerprint: String?): String? {
        val cleaned = fingerprint?.replace(":", "")?.replace(" ", "")?.trim()?.lowercase()
        return cleaned?.takeIf { it.isNotEmpty() }
    }

    /** 打包日志用的一行（C-PKG tag，便于机器检查「换证书→必须重编」）。 */
    @JvmStatic
    fun describe(stage: Stage, certSha: String?, packageName: String?): String =
        "sjbc certSha=" + (certSha ?: "(unavailable)") + " package=" + (packageName ?: "(unknown)") +
            " files=" + stage.sjbcCount() + " manifest=" + stage.hasManifest +
            (if (stage.sourceDir == null) " source=(none)" else " source=" + stage.sourceDir.name)
}
