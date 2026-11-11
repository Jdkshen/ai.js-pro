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
    // 按**真实插件布局**对齐（dev-1498 真机清单）：lib/arm64-v8a/{libc++_shared.so, libppocr.so}、
    // assets/ocr/models/**（5 个 ppocr 模型）、assets/scripts/index.js（SDK 契约入口）。
    // libc++_shared.so 必带：libppocr.so 的 DT_NEEDED 依赖它。mlkit 库可选（当前默认后端是 ppocr）。
    val OCR_LIB_NAMES = listOf("libc++_shared.so", "libppocr.so", "libmlkit_google_ocr_pipeline.so")
    /** 契约路径优先；mlkit 旧前缀作为兼容保留（两种都拷）。 */
    val OCR_MODELS_PREFIXES = listOf("assets/ocr/models/", "assets/mlkit-google-ocr-models/")
    const val OCR_MODELS_PREFIX = "assets/ocr/models/"
    const val LEGACY_MODELS_PREFIX = "assets/mlkit-google-ocr-models/"
    /** 插件**可选**自带的真模块（新版插件按 SDK 契约只带 assets/scripts/index.js；真模块由宿主/模板提供）。 */
    const val RHINO_MODULE_ENTRY = "assets/modules/__ocr__.js"

    // 插件 APK 里是否有可用的 OCR 载荷（决定策略里的 pluginHasOcr）。
    @JvmStatic
    fun hasOcrPayload(pluginApk: File?, abi: String = "arm64-v8a"): Boolean {
        if (pluginApk == null || !pluginApk.isFile) return false
        return try {
            ZipFile(pluginApk).use { zip ->
                // 判据（对齐真机布局）：**有库 且 有模型** ⇒ 才算「带 OCR 载荷」。
                // 不再要求 assets/modules/__ocr__.js（那是宿主/模板的，插件按契约只带 assets/scripts/index.js）。
                val hasLib = OCR_LIB_NAMES.any { zip.getEntry("lib/" + abi + "/" + it) != null }
                val hasModels = zip.entries().asSequence()
                    .any { entry -> OCR_MODELS_PREFIXES.any { entry.name.startsWith(it) } }
                hasLib && hasModels
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
                    val isModels = OCR_MODELS_PREFIXES.any { name.startsWith(it) }
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