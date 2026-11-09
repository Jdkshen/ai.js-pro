package com.jdkshen.aijspro.autojs.build

import java.io.File

/**
 * task-63 ②：打包时**必须默认排除**的工程内目录（判据集中在这里，可单测）。
 *
 * 背景（device4 dev-1443 实测）：旧逻辑只排除「产物 APK 所在目录，且该目录在工程内」，
 * 而用户把产物输出到工程外的 脚本/APK输出/ → 什么都没排除 → 工程里的 build/
 * （上一次的 .apk 29.9MB + .zip 29.3MB）被整份复制进 assets/project/，产物 95MB。
 *
 * 判据（**不误伤用户自己的 build 源码目录**）：
 *  1. 产物目录在工程内 → 排除它（老行为，保留）；
 *  2. 工程根下名为 build 的目录 → **只有**满足「是打包产物目录」才排除：
 *     含至少一个 .apk/.zip/.idsig/.apks，且**不含任何源码类文件**（.js/.ts/.jsx/.tsx/.vue/.java/.kt/.py/.json/.md）；
 *  3. 目录里有我们写的标记文件 [MARKER]（新产物目录会在打包开始时写入）→ 直接排除（最强判据，向后兼容旧目录靠 2）；
 *  4. AppConfig.ignoreDir(...) 显式声明的目录（语义在任务里被点名要理清：以前只在单文件分支生效）。
 */
object PackageOutputExclusions {
    const val MARKER = ".aijspro-package-output"
    private val PACKAGE_EXTENSIONS = setOf("apk", "zip", "apks", "idsig", "xapk")
    private val SOURCE_EXTENSIONS = setOf(
        "js", "mjs", "cjs", "ts", "jsx", "tsx", "vue", "java", "kt", "kts", "py", "json", "md", "txt", "html", "css"
    )
    /** 只在工程根下认这些名字（保守：不做任意深度的 build/ 匹配，避免误伤子目录里的源码包名）。 */
    private val OUTPUT_DIR_NAMES = setOf("build")

    /** 是否像「打包产物目录」：有产物、且没有源码文件。 */
    fun looksLikePackageOutputDir(dir: File): Boolean {
        if (!dir.isDirectory) return false
        if (File(dir, MARKER).isFile) return true
        var sawPackage = false
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        var visited = 0
        while (stack.isNotEmpty() && visited < 4096) {
            val current = stack.removeLast()
            val children = current.listFiles() ?: continue
            for (child in children) {
                visited++
                if (visited > 4096) break
                if (child.isDirectory) {
                    stack.addLast(child)
                    continue
                }
                val extension = child.name.substringAfterLast('.', "").lowercase()
                if (extension in PACKAGE_EXTENSIONS) sawPackage = true
                if (extension in SOURCE_EXTENSIONS) return false
            }
        }
        return sawPackage
    }

    /**
     * 计算要跳过的路径集合（规范路径）。projectDir 为工程根目录（可能是文件——调用方保证是目录）。
     */
    @JvmStatic
    fun projectExclusions(projectDir: File?, outApkFile: File?, ignoredDirPaths: List<String>?): Set<String> {
        val excluded = LinkedHashSet<String>()
        fun addCanonical(file: File?) {
            if (file == null) return
            runCatching { excluded.add(file.canonicalPath) }
        }
        if (projectDir == null || !projectDir.isDirectory) return excluded
        val project = runCatching { projectDir.canonicalFile }.getOrNull() ?: return excluded
        // 1) 产物目录在工程内
        val outputDir = outApkFile?.absoluteFile?.parentFile
        if (outputDir != null) {
            val canonical = runCatching { outputDir.canonicalFile }.getOrNull()
            if (canonical != null && canonical.path.startsWith(project.path + File.separator)) addCanonical(canonical)
        }
        // 2) 工程根下的 build/（且确实像产物目录）
        for (name in OUTPUT_DIR_NAMES) {
            val candidate = File(project, name)
            if (candidate.isDirectory && looksLikePackageOutputDir(candidate)) addCanonical(candidate)
        }
        // 3) 显式声明（AppConfig.ignoreDir / project.json ignoredDirs）
        ignoredDirPaths?.forEach { raw ->
            if (raw.isNullOrBlank()) return@forEach
            val file = File(raw)
            val resolved = if (file.isAbsolute) file else File(project, raw)
            addCanonical(resolved)
        }
        return excluded
    }

    /** 说明为什么跳过（写进打包日志，便于用户读懂产物为什么变小）。 */
    @JvmStatic
    fun describe(excluded: Set<String>, projectDir: File?): String {
        if (excluded.isEmpty()) return "no exclusion"
        val project = projectDir?.let { runCatching { it.canonicalPath }.getOrNull() } ?: ""
        return excluded.joinToString(", ") { path ->
            val relative = if (project.isNotEmpty() && path.startsWith(project + File.separator)) path.substring(project.length + 1) else path
            relative + (if (File(path, MARKER).isFile) "(marked)" else if (File(path).name == "build") "(package-output-heuristic)" else "(ignored)")
        }
    }
}
