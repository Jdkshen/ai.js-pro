package com.jdkshen.aijspro.build

import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * task-116：**应用内模板自检**（纯 JVM、可单测）。
 *
 * 判据与 gradle 任务 verifySlimPackagingTemplate 等价（体积 6–20MiB、必需条目、
 * 瘦身模板不得含 lib/），失败时给**自足修法**（重装 / 清 files/apk-builder/ / 用
 * /sdcard/template.apk 覆盖），**不再要求用户安装打包插件**。
 *
 * 用法（只读，无副作用）：
 *  - 打包落到最后兜底档时把问题与修法一起打进错误信息（零额外开销）；
 *  - 也可由 UI/MCP 显式调用做体检（不动启动路径）。
 */
object TemplateSelfCheck {
    /** 与 verifySlimPackagingTemplate 对齐的体积区间。 */
    const val MIN_BYTES = 6L * 1024 * 1024
    const val MAX_BYTES = 20L * 1024 * 1024

    /** 瘦身模板的必需条目（与 verifySlimPackagingTemplate 同一份判据）。 */
    val REQUIRED_ENTRIES = listOf("AndroidManifest.xml", "classes.dex", "resources.arsc")

    data class Result(
        val ok: Boolean,
        val label: String,
        val sizeBytes: Long,
        val entryCount: Int,
        val problems: List<String>
    ) {
        /** 自足修法（永不含「装插件」）。 */
        fun fixes(): List<String> = listOf(
            "1) 重装或升级主程序（内置模板随包分发，见 assets/template.apk）；",
            "2) 清掉宿主的打包缓存后重试：删除 app 私有目录下的 files/apk-builder/；",
            "3) 或者手动覆盖：把一份可用的 template.apk 放到 /sdcard/template.apk（优先级最高）。"
        )

        fun describe(): String = buildString {
            append("template self-check: ").append(label)
            append(" ok=").append(ok)
            append(" size=").append(sizeBytes)
            append(" entries=").append(entryCount)
            if (problems.isNotEmpty()) {
                append(" problems=[").append(problems.joinToString("; ")).append("]")
            }
        }

        /** 给用户看的整段文案（错误路径用）。 */
        fun userMessage(): String = buildString {
            append("内置打包模板自检未通过（").append(label).append("）：\n")
            problems.forEach { append("· ").append(it).append("\n") }
            append("应用内修法：\n")
            fixes().forEach { append(it).append("\n") }
        }
    }

    @JvmStatic
    fun check(file: File?): Result {
        val label = file?.path ?: "(null)"
        if (file == null || !file.isFile) {
            return Result(false, label, 0, 0, listOf("模板文件不存在"))
        }
        return try {
            file.inputStream().use { check(it, label, file.length()) }
        } catch (error: Throwable) {
            Result(false, label, file.length(), 0, listOf("读取模板失败：" + (error.message ?: error.javaClass.simpleName)))
        }
    }

    @JvmStatic
    fun check(bytes: ByteArray?, label: String): Result {
        if (bytes == null || bytes.isEmpty()) {
            return Result(false, label, 0, 0, listOf("模板为空"))
        }
        return try {
            check(ByteArrayInputStream(bytes), label, bytes.size.toLong())
        } catch (error: Throwable) {
            Result(false, label, bytes.size.toLong(), 0, listOf("解析模板失败：" + (error.message ?: error.javaClass.simpleName)))
        }
    }

    /** 主入口：流式读 zip 中央目录（不整包进内存）。 */
    @JvmStatic
    fun check(input: java.io.InputStream?, label: String, sizeBytes: Long = -1L): Result {
        if (input == null) {
            return Result(false, label, 0, 0, listOf("模板流为空"))
        }
        val problems = ArrayList<String>()
        val present = HashSet<String>()
        var hasLib = false
        var count = 0
        try {
            ZipInputStream(input).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (!entry.isDirectory && name.isNotEmpty()) {
                        count++
                        present.add(name)
                        if (name.startsWith("lib/")) hasLib = true
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } catch (error: Throwable) {
            problems.add("不是有效的 zip/APK：" + (error.message ?: error.javaClass.simpleName))
        }
        for (required in REQUIRED_ENTRIES) {
            if (!present.contains(required)) problems.add("缺少必需条目：" + required)
        }
        if (hasLib) {
            problems.add("瘦身模板不得包含 lib/ 条目（lib 由宿主在打包时补齐）")
        }
        if (sizeBytes in 1 until MIN_BYTES) {
            problems.add("体积偏小：" + sizeBytes + " 字节（预期 " + MIN_BYTES + "–" + MAX_BYTES + "）")
        }
        if (sizeBytes > MAX_BYTES) {
            problems.add("体积偏大：" + sizeBytes + " 字节（预期 " + MIN_BYTES + "–" + MAX_BYTES + "；可能误用了全量模板）")
        }
        return Result(problems.isEmpty(), label, sizeBytes, count, problems)
    }

    /** 内置模板（assets/template.apk）体检；拿不到资产时返回带原因的结果。 */
    @JvmStatic
    fun checkBundled(openAsset: () -> java.io.InputStream?): Result = try {
        val stream = openAsset()
        if (stream == null) Result(false, "assets/template.apk", 0, 0, listOf("内置模板缺失")) else check(stream, "assets/template.apk")
    } catch (error: Throwable) {
        Result(false, "assets/template.apk", 0, 0, listOf("无法读取内置模板：" + (error.message ?: error.javaClass.simpleName)))
    }
}
