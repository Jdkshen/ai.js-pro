package com.jdkshen.aijspro.ui.viewmodel

import java.io.File
import java.io.IOException

/** Canonical paths prevent sibling-prefix matches and symlink escapes during search navigation. */
data class ExplorerRevealPlan(val targetPath: String, val directories: List<String>) {
    companion object {
        fun create(root: File, target: File): ExplorerRevealPlan? = try {
            val canonicalRoot = root.canonicalFile
            val canonicalTarget = target.canonicalFile
            if (!canonicalTarget.exists()) null else {
                val parents = mutableListOf<String>()
                var directory = canonicalTarget.parentFile
                while (directory != null && directory != canonicalRoot) {
                    parents.add(directory.path)
                    directory = directory.parentFile
                }
                if (directory == null) null else {
                    parents.add(canonicalRoot.path)
                    ExplorerRevealPlan(canonicalTarget.path, parents.asReversed())
                }
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }
}
