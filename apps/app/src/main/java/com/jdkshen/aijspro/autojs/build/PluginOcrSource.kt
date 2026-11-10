package com.jdkshen.aijspro.autojs.build

import java.io.File
import java.util.zip.ZipFile

// task-131 / task-132：打包期从「已安装的 OCR 插件 APK」里取 OCR 的 libs 与模型。
//
// 与 W1 的 app 运行期落盘钩子（插件把 .so 落到私有目录 + 绝对路径 System.load）是两条路：
// 这条只在打包时跑，把 OCR 载荷拷进产物；那条在运行时让已装插件可用。二者互不依赖。
//
// 判据（插件 APK 里的条目名）：lib 目录下的 libppocr.so / libmlkit_google_ocr_pipeline.so、
// assets 下的 OCR 模型目录、assets/modules/__ocr__.js（真模块，装上插件后就不该再用桩）。
object PluginOcrSource {
    val OCR_LIB_NAMES = listOf("libppocr.so", "libmlkit_google_ocr_pipeline.so")
    const val OCR_MODELS_PREFIX = "assets/mlkit-google-ocr-models/"
    const val RHINO_MODULE_ENTRY = "assets/modules/__ocr__.js"

    // 插件 APK 里是否有可用的 OCR 载荷（决定策略里的 pluginHasOcr）。
    @JvmStatic
    fun hasOcrPayload(pluginApk: File?, abi: String = "arm64-v8a"): Boolean {
        if (pluginApk == null || !pluginApk.isFile) return false
        return try {
            ZipFile(pluginApk).use { zip ->
                OCR_LIB_NAMES.any { zip.getEntry("lib/" + abi + "/" + it) != null } ||
                    zip.getEntry(RHINO_MODULE_ENTRY) != null ||
                    zip.entries().asSequence().any { it.name.startsWith(OCR_MODELS_PREFIX) }
            }
        } catch (error: Throwable) {
            false
        }
    }

    data class Result(val files: Int, val bytes: Long, val entries: List<String>) {
        fun describe(): String = "ocr from plugin: files=" + files + " bytes=" + bytes +
            " entries=[" + entries.sorted().joinToString(",") + "]"
    }

    // 把插件 APK 里的 OCR 载荷拷进打包工作区（assets 与 lib/<abi> 两处）。
    // 只拷 OCR 相关条目，不整包解压（插件里别的东西与产物无关）。
    @JvmStatic
    fun copyIntoWorkspace(pluginApk: File?, workspace: File, abi: String = "arm64-v8a"): Result {
        if (pluginApk == null || !pluginApk.isFile) return Result(0, 0L, emptyList())
        var files = 0
        var bytes = 0L
        val copied = ArrayList<String>()
        try {
            ZipFile(pluginApk).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    val name = entry.name
                    val isOcrLib = OCR_LIB_NAMES.any { name == "lib/" + abi + "/" + it }
                    val isModels = name.startsWith(OCR_MODELS_PREFIX)
                    val isModule = name == RHINO_MODULE_ENTRY
                    if (!isOcrLib && !isModels && !isModule) continue
                    val target = File(workspace, name)
                    target.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    files++
                    bytes += target.length()
                    copied.add(name)
                }
            }
        } catch (error: Throwable) {
            throw IllegalStateException("从插件 APK 取 OCR 载荷失败：" + error.message, error)
        }
        return Result(files, bytes, copied)
    }
}
