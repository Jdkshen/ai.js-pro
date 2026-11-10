package com.jdkshen.aijspro.autojs.build

import java.io.File
import java.util.Locale

/**
 * task-104 ②：OCR 资产**自动发现**（不写死 assets/mlkit-google-ocr-models 这个目录名）。
 *
 * <p>模板改名时不再静默变成 files=0：命中前缀会回显到日志与打包报告（ocrRemovedPrefix）。</p>
 * <p>判据是纯文件名匹配（大小写无关）：
 * 目录名同时含 ocr 与 model（如 mlkit-google-ocr-models），或含 mlkit/ppocr 且含 ocr；
 * 另外永远包含 Rhino 侧模块文件 assets/modules/__ocr__.js（存在时）。</p>
 *
 * <p>**刻意不包含** qjs-plugins.js / __plugins__.js：它们是**共享插件门面**（非 OCR 专用），
 * 剪掉会连带干掉 QuickJS/Rhino 的插件能力；它们在运行期只用 $ocr 全局，缺失时有 guard/点名错误。</p>
 */
object OcrAssetDiscovery {
    const val RHINO_OCR_MODULE = "modules/__ocr__.js"

    /** assets 目录下实际存在的 OCR 资产（目录 + Rhino 模块文件）；没有就返回空列表。 */
    @JvmStatic
    fun find(assetsDir: File?): List<File> {
        val targets = ArrayList<File>()
        if (assetsDir == null || !assetsDir.isDirectory) return targets
        val children = assetsDir.listFiles() ?: return targets
        for (child in children) {
            if (child == null || !child.isDirectory) continue
            val name = child.name.lowercase(Locale.ROOT)
            val looksLikeOcrModels = (name.contains("ocr") && name.contains("model")) ||
                ((name.contains("mlkit") || name.contains("ppocr")) && name.contains("ocr"))
            if (looksLikeOcrModels) targets.add(child)
        }
        val rhino = File(assetsDir, RHINO_OCR_MODULE)
        if (rhino.isFile) targets.add(rhino)
        return targets
    }

    /** 命中前缀（逗号分隔、排序）用于日志/报告回显；没有命中返回空串。 */
    @JvmStatic
    fun describe(targets: List<File>?): String {
        if (targets.isNullOrEmpty()) return ""
        return targets.map { it.name }.sorted().joinToString(",")
    }
}
