package com.jdkshen.aijspro.ui.sample

import com.jdkshen.aijspro.model.sample.SampleRepository
import java.util.Locale
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

internal data class SampleEntry(val name: String, val path: String, val directory: Boolean) {
    val runnable: Boolean get() = !directory && name.endsWith(".js", true)
}

internal enum class SampleFilter(val label: String) {
    ALL("全部"),
    JAVASCRIPT("JavaScript"),
    FOLDERS("文件夹"),
    OTHER("其他文件")
}

internal data class SampleSearchResult(
    val entries: List<SampleEntry>,
    val error: String? = null
)

/** Presentation-only index of bundled assets. It never opens or changes sample contents. */
internal object MiuixSampleCatalog {
    /**
     * 遍历入口统一走 [SampleRepository.listAll]（与 MCP list_samples 同源）；
     * 这里只把注入的 lister 适配成 Java 接口。
     */
    fun load(listAssets: (String) -> Array<out String>): List<SampleEntry> =
        SampleRepository.listAll(SampleRepository.AssetLister { path ->
            listAssets(path).toList().toTypedArray()
        }).map { SampleEntry(it.name, it.path, it.isDirectory) }

    fun visible(
        entries: List<SampleEntry>,
        path: String,
        query: String,
        filter: SampleFilter
    ): List<SampleEntry> = search(entries, path, query, filter).entries

    fun search(
        entries: List<SampleEntry>,
        path: String,
        query: String,
        filter: SampleFilter,
        includeSubdirectories: Boolean = true,
        useRegex: Boolean = false
    ): SampleSearchResult {
        val prefix = path.trimEnd('/') + "/"
        val search = query.trim()
        val pattern = if (useRegex && search.isNotEmpty()) {
            try {
                Pattern.compile(search, Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
            } catch (error: PatternSyntaxException) {
                return SampleSearchResult(emptyList(), "正则表达式无效：${error.description}")
            }
        } else null
        val recursive = includeSubdirectories &&
                (search.isNotEmpty() || filter != SampleFilter.ALL)
        val visible = entries.asSequence()
            .filter { entry ->
                if (!entry.path.startsWith(prefix)) return@filter false
                val relativePath = entry.path.removePrefix(prefix)
                if (!recursive && '/' in relativePath) return@filter false
                val matchesType = when (filter) {
                    SampleFilter.ALL -> true
                    SampleFilter.JAVASCRIPT -> entry.runnable
                    SampleFilter.FOLDERS -> entry.directory
                    SampleFilter.OTHER -> !entry.directory && !entry.runnable
                }
                val matchesSearch = search.isEmpty() || if (pattern != null) {
                    pattern.matcher(entry.name).find() || pattern.matcher(relativePath).find()
                } else {
                    entry.name.contains(search, ignoreCase = true) ||
                            relativePath.contains(search, ignoreCase = true)
                }
                matchesType && matchesSearch
            }
            .sortedWith(compareBy<SampleEntry> { !it.directory }
                .thenBy { it.name.lowercase(Locale.ROOT) }
                .thenBy { it.path })
            .toList()
        return SampleSearchResult(visible)
    }
}
