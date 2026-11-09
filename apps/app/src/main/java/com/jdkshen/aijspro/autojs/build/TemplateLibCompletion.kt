package com.jdkshen.aijspro.autojs.build

import java.io.File

/**
 * task-74：模板 lib 补齐的 ABI 判定（纯逻辑，可单测）。
 *
 * 单一事实来源：**excluded-zip-files.json**（apps/app/src/main/assets 与模板内各一份）里列出的 entry
 * 决定「要补哪些 .so」，本类只从 entry 路径推导 ABI 与「模板已经带了哪些 ABI」，**不再在代码里写死
 * lib/arm64-v8a**（也不写第二份 v7a 清单）。宿主 APK 能提供什么由宿主自己的 lib/ 决定，
 * 缺的 entry 由调用方按现有方式记日志跳过（非致命）。
 */
object TemplateLibCompletion {
    private const val LIB_PREFIX = "lib/"
    private const val SO_SUFFIX = ".so"

    /** "lib/armeabi-v7a/libquickjs.so" → "armeabi-v7a"；不合法返回 null。 */
    @JvmStatic
    fun abiOf(entry: String?): String? {
        if (entry == null) {
            return null
        }
        val normalized = entry.trim().replace('\\', '/')
        if (!normalized.startsWith(LIB_PREFIX) || !normalized.endsWith(SO_SUFFIX)) {
            return null
        }
        val rest = normalized.substring(LIB_PREFIX.length)
        val slash = rest.indexOf('/')
        if (slash <= 0) {
            return null
        }
        return rest.substring(0, slash)
    }

    /** 清单里涉及的 ABI（保持出现顺序，便于日志可读）。 */
    @JvmStatic
    fun abisOf(entries: List<String>?): MutableSet<String> {
        val result = LinkedHashSet<String>()
        if (entries != null) {
            for (entry in entries) {
                val abi = abiOf(entry)
                if (abi != null) {
                    result.add(abi)
                }
            }
        }
        return result
    }

    /** 工作区 lib/<abi>/ 下**已经有 .so** 的 ABI 集合（空目录/不存在都不算）。 */
    @JvmStatic
    fun abisWithLibs(workspace: File?): MutableSet<String> {
        val result = LinkedHashSet<String>()
        if (workspace == null) {
            return result
        }
        val root = File(workspace, "lib")
        val dirs = root.listFiles()
        if (dirs == null) {
            return result
        }
        for (dir in dirs) {
            if (dir == null || !dir.isDirectory) {
                continue
            }
            val files = dir.listFiles()
            if (files == null) {
                continue
            }
            var hasLib = false
            for (file in files) {
                if (file != null && file.isFile && file.name.endsWith(SO_SUFFIX)) {
                    hasLib = true
                    break
                }
            }
            if (hasLib) {
                result.add(dir.name)
            }
        }
        return result
    }

    /**
     * 需要从宿主补齐的 entry：只取「ABI 还没有 lib」的那些。
     * 例：模板已带 arm64（presentAbis={arm64-v8a}）而清单含 arm64+v7a → 只返回 v7a 的 entry（arm64 不动）。
     */
    @JvmStatic
    fun entriesForMissingAbis(entries: List<String>?, presentAbis: Set<String>?): List<String> {
        val result = ArrayList<String>()
        if (entries == null) {
            return result
        }
        val present: Set<String> = presentAbis ?: emptySet()
        for (entry in entries) {
            val abi = abiOf(entry)
            if (abi != null && !present.contains(abi)) {
                result.add(entry)
            }
        }
        return result
    }

    /** 打包日志可读的一行（用于「清单里所有 ABI 都已带 lib」的跳过路径）。 */
    @JvmStatic
    fun describeSkip(entries: List<String>?, presentAbis: Set<String>?): String {
        val wanted = abisOf(entries)
        val count = if (entries == null) 0 else entries.size
        val present: Set<String> = presentAbis ?: emptySet()
        return "template libs already present abis=" + wanted + " (清单 " + count +
            " 条，已有 lib 的 ABI=" + present + "，跳过从宿主补齐)"
    }
}
