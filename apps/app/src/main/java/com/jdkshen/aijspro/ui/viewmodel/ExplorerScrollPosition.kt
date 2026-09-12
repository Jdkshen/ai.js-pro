package com.jdkshen.aijspro.ui.viewmodel

/** Keep the visible file anchored when directory contents change; fall back to a clamped index. */
data class ExplorerScrollPosition(val index: Int, val offset: Int, val anchorPath: String? = null) {
    fun indexIn(paths: List<String>): Int {
        val anchored = anchorPath?.let(paths::indexOf) ?: -1
        return if (anchored >= 0) anchored else index.coerceIn(0, (paths.size - 1).coerceAtLeast(0))
    }
}
