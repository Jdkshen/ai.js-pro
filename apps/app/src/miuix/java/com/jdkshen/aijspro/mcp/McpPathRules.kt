package com.jdkshen.aijspro.mcp

import java.io.File

/**
 * Shared boundary check for every client-supplied path. Backslashes are accepted as separators,
 * relative paths are rooted at [root], and absolute paths must still canonicalize inside [root].
 */
internal object McpPathRules {
    fun resolve(root: File, raw: String, outsideMessage: String = "路径超出允许目录"): File {
        if (raw.indexOf('\u0000') >= 0) throw IllegalArgumentException("路径无效")
        val canonicalRoot = root.canonicalFile
        val normalized = raw.replace('\\', '/')
        val candidate = when {
            normalized.isEmpty() -> canonicalRoot
            // On Windows File("/foo").isAbsolute is false and File(root, "/foo") silently
            // becomes root/foo. Treat a leading slash as root-anchored on every platform.
            normalized.startsWith('/') || File(normalized).isAbsolute -> File(normalized)
            else -> File(canonicalRoot, normalized)
        }.canonicalFile
        if (candidate != canonicalRoot && !candidate.path.startsWith(canonicalRoot.path + File.separator)) {
            throw IllegalArgumentException(outsideMessage)
        }
        return candidate
    }
}
