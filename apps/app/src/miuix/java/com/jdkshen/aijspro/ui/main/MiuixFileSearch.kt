package com.jdkshen.aijspro.ui.main

import java.io.File
import java.io.IOException
import java.util.ArrayDeque
import java.util.Locale
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

internal data class MiuixFileSearchResult(
    val files: List<File>,
    val truncated: Boolean = false,
    val error: String? = null
)

/** File-system-only search logic kept outside Compose so it can run and be tested off the UI thread. */
internal object MiuixFileSearch {
    const val DEFAULT_LIMIT = 500

    fun search(
        scriptRoot: File,
        directory: File,
        keyword: String,
        includeSubdirectories: Boolean,
        useRegex: Boolean,
        includeHidden: Boolean = false,
        limit: Int = DEFAULT_LIMIT
    ): MiuixFileSearchResult {
        val query = keyword.trim()
        if (limit < 1) return MiuixFileSearchResult(emptyList(), error = "搜索条数限制无效")

        val root = canonicalOrNull(scriptRoot)
            ?: return MiuixFileSearchResult(emptyList(), error = "脚本根目录不可用")
        val start = canonicalOrNull(directory)
            ?: return MiuixFileSearchResult(emptyList(), error = "搜索目录不可用")
        if (!start.isDirectory || !isWithinRoot(root, start)) {
            return MiuixFileSearchResult(emptyList(), error = "搜索目录不在脚本根目录内")
        }

        val pattern = if (useRegex) {
            try {
                Pattern.compile(query, Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
            } catch (error: PatternSyntaxException) {
                return MiuixFileSearchResult(emptyList(),
                    error = "正则表达式无效：${error.description}")
            }
        } else null

        val result = ArrayList<File>(minOf(limit, 128))
        val pending = ArrayDeque<File>()
        val visitedDirectories = HashSet<String>()
        pending.addLast(start)
        visitedDirectories += start.path
        var truncated = false

        search@ while (pending.isNotEmpty()) {
            val parent = pending.removeFirst()
            val children = parent.listFiles()?.sortedWith(fileComparator) ?: continue
            for (rawChild in children) {
                val child = canonicalOrNull(rawChild) ?: continue
                if (!isWithinRoot(root, child)) continue
                if (!includeHidden && isHidden(child)) continue

                val matches = pattern?.matcher(child.name)?.find()
                    ?: child.name.contains(query, ignoreCase = true)
                if (matches) {
                    if (result.size >= limit) {
                        truncated = true
                        break@search
                    }
                    result += child
                }
                if (includeSubdirectories && child.isDirectory &&
                    visitedDirectories.add(child.path)) {
                    pending.addLast(child)
                }
            }
        }

        return MiuixFileSearchResult(result.sortedWith(fileComparator), truncated)
    }

    fun isWithinRoot(scriptRoot: File, file: File): Boolean {
        val root = canonicalOrNull(scriptRoot) ?: return false
        val target = canonicalOrNull(file) ?: return false
        return target.path == root.path || target.path.startsWith(root.path + File.separator)
    }

    fun relativeParent(directory: File, file: File): String {
        val base = canonicalOrNull(directory) ?: return ""
        val parent = canonicalOrNull(file.parentFile) ?: return ""
        if (parent == base || !isWithinRoot(base, parent)) return ""
        return parent.path.removePrefix(base.path + File.separator)
            .replace(File.separatorChar, '/')
    }

    fun typeLabel(file: File): String {
        if (file.isDirectory) {
            return if (File(file, "project.json").isFile || File(file, "main.js").isFile) {
                "AI.js Pro 项目"
            } else "普通文件夹"
        }
        return when (file.extension.lowercase(Locale.ROOT)) {
            "js" -> "JavaScript 脚本"
            "auto" -> "录制文件"
            "json" -> "JSON 文件"
            else -> "文件"
        }
    }

    private fun isHidden(file: File): Boolean = file.name.startsWith(".") || try {
        file.isHidden
    } catch (_: SecurityException) {
        true
    }

    private fun canonicalOrNull(file: File?): File? = try {
        file?.canonicalFile
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private val fileComparator = compareBy<File> { !it.isDirectory }
        .thenBy { it.name.lowercase(Locale.ROOT) }
        .thenBy { it.path }
}
