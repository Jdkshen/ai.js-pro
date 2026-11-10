package com.jdkshen.aijspro.autojs.build

import java.io.File
import java.util.Locale

/**
 * task-104 ② / task-106 隐患 A：OCR 资产**自动发现**（不写死 assets/mlkit-google-ocr-models 这个目录名）。
 *
 * <p>关键安全约束（task-106）：名字匹配只是**候选**，最终必须是「模板/宿主确实提供的条目」才会被删。
 * providedAssetPaths 为空（拿不到模板/宿主清单）时**一个都不删**（fail-safe），由调用方打 W。</p>
 *
 * <p>判据是纯文件名匹配（大小写无关）：
 * 目录名同时含 ocr 与 model（如 mlkit-google-ocr-models），或含 mlkit/ppocr 且含 ocr；
 * 另外永远考虑 Rhino 侧模块文件 assets/modules/__ocr__.js（存在时，且必须由 providedAssetPaths 证实）。</p>
 *
 * <p>**刻意不包含** qjs-plugins.js / __plugins__.js：它们是**共享插件门面**（非 OCR 专用），
 * 剪掉会连带干掉 QuickJS/Rhino 的插件能力。</p>
 */
object OcrAssetDiscovery {
    const val RHINO_OCR_MODULE = "modules/__ocr__.js"

    /** 只按名字找候选（不判断是否模板提供）——给日志/诊断用。 */
    @JvmStatic
    fun candidates(assetsDir: File?): List<File> {
        val targets = ArrayList<File>()
        if (assetsDir == null || !assetsDir.isDirectory) return targets
        val children = assetsDir.listFiles() ?: return targets
        for (child in children) {
            if (child == null || !child.isDirectory) continue
            if (looksLikeOcrModels(child.name)) targets.add(child)
        }
        val rhino = File(assetsDir, RHINO_OCR_MODULE)
        if (rhino.isFile) targets.add(rhino)
        return targets
    }

    /**
     * 真正可以删的目标 = 候选 ∩ 「模板/宿主提供的 assets 条目」。
     *
     * @param providedAssetPaths 形如 assets/mlkit-google-ocr-models/xx.tflite、assets/modules/__ocr__.js 的条目名集合；
     *                           **空集合 ⇒ 返回空**（宁可不剪，也不误删）。
     */
    @JvmStatic
    fun find(assetsDir: File?, providedAssetPaths: Collection<String>?): List<File> {
        if (providedAssetPaths == null || providedAssetPaths.isEmpty()) return emptyList()
        val provided = providedAssetPaths.map { it.replace('\\', '/') }.toSet()
        return candidates(assetsDir).filter { candidate ->
            val relative = relativeToAssets(assetsDir, candidate) ?: return@filter false
            if (candidate.isDirectory) {
                provided.any { it.startsWith(relative + "/") }
            } else {
                provided.contains(relative)
            }
        }
    }

    /** 命中前缀（逗号分隔、排序）用于日志/报告回显；没有命中返回空串。 */
    @JvmStatic
    fun describe(targets: List<File>?): String {
        if (targets.isNullOrEmpty()) return ""
        return targets.map { it.name }.sorted().joinToString(",")
    }

    private fun looksLikeOcrModels(rawName: String): Boolean {
        val name = rawName.lowercase(Locale.ROOT)
        return (name.contains("ocr") && name.contains("model")) ||
            ((name.contains("mlkit") || name.contains("ppocr")) && name.contains("ocr"))
    }

    private fun relativeToAssets(assetsDir: File?, target: File): String? {
        val root = assetsDir ?: return null
        val rootPath = root.absolutePath.replace('\\', '/').trimEnd('/')
        val targetPath = target.absolutePath.replace('\\', '/')
        if (!targetPath.startsWith("$rootPath/")) return null
        return "assets/" + targetPath.substring(rootPath.length + 1)
    }
}
