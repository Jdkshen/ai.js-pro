package com.jdkshen.aijspro.mcp

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/**
 * Private copy-on-write workspaces. MCP clients edit only [work]; applying to the real script
 * directory is deliberately available only to the local confirmation UI.
 */
class McpWorkspaceStore(context: Context, private val scriptRoot: File) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "mcp/workspaces")

    data class Workspace(
        val id: String,
        val targetPath: String,
        val createdAt: Long,
        val updatedAt: Long,
        val state: String,
        val pendingApproval: Boolean,
        val changedFiles: Int
    )

    @Synchronized fun open(path: String): Workspace {
        cleanup()
        val target = resolveScript(path)
        if (!target.exists()) throw IllegalArgumentException("目标不存在")
        val id = "ws-" + UUID.randomUUID().toString().substring(0, 8)
        val dir = File(root, id)
        val base = File(dir, "base")
        val work = File(dir, "work")
        val hashes = linkedMapOf<String, String>()
        var total = 0L
        var count = 0
        fun copyOne(source: File, relative: String) {
            if (!source.isFile || source.extension.lowercase() !in TEXT_EXTENSIONS) return
            val bytes = source.readBytes()
            total += bytes.size
            count++
            if (total > MAX_TOTAL_BYTES || count > MAX_FILES) throw IllegalArgumentException("项目超过工作区限制")
            hashes[relative] = sha256(bytes)
            File(base, relative).apply { parentFile?.mkdirs(); writeBytes(bytes) }
            File(work, relative).apply { parentFile?.mkdirs(); writeBytes(bytes) }
        }
        try {
            if (target.isFile) copyOne(target, target.name)
            else target.walkTopDown().filter { it.isFile }.forEach { file ->
                val canonical = file.canonicalFile
                if (!inside(target, canonical)) throw IllegalArgumentException("项目包含越界路径")
                copyOne(canonical, canonical.relativeTo(target).invariantSeparatorsPath)
            }
            if (count == 0) throw IllegalArgumentException("目标中没有可编辑文本文件")
            val now = System.currentTimeMillis()
            writeMeta(dir, JSONObject().apply {
                put("id", id); put("targetPath", target.relativeTo(scriptRoot).invariantSeparatorsPath)
                put("targetIsFile", target.isFile); put("createdAt", now); put("updatedAt", now)
                put("state", STATE_OPEN); put("pendingApproval", false)
                put("originalHashes", JSONObject(hashes as Map<*, *>)); put("appliedHashes", JSONObject())
            })
            return summary(dir)
        } catch (error: Exception) {
            dir.deleteRecursively()
            throw error
        }
    }

    @Synchronized fun list(): List<Workspace> {
        cleanup()
        return root.listFiles().orEmpty().filter { it.isDirectory && File(it, META).isFile }
            .mapNotNull { runCatching { summary(it) }.getOrNull() }.sortedByDescending { it.updatedAt }
    }

    @Synchronized fun read(id: String, path: String): ByteArray {
        val dir = workspaceDir(id)
        val file = resolveWorkspace(File(dir, "work"), path)
        if (!file.isFile || file.extension.lowercase() !in TEXT_EXTENSIONS) throw IllegalArgumentException("工作区文件不存在")
        return file.readBytes()
    }

    @Synchronized fun write(id: String, path: String, content: ByteArray): Workspace {
        if (content.size > MAX_FILE_BYTES) throw IllegalArgumentException("单文件超过 256 KiB")
        val dir = workspaceDir(id)
        val meta = readMeta(dir)
        if (meta.getString("state") !in setOf(STATE_OPEN, STATE_PENDING)) throw IllegalArgumentException("工作区已结束")
        validateWorkspacePath(meta, path)
        val file = resolveWorkspace(File(dir, "work"), path)
        if (file.extension.lowercase() !in TEXT_EXTENSIONS) throw IllegalArgumentException("不支持此文件类型")
        val workRoot = File(dir, "work")
        val existingSize = if (file.isFile) file.length() else 0L
        val totalAfter = workRoot.walkTopDown().filter { it.isFile }.sumOf { it.length() } - existingSize + content.size
        val filesAfter = workRoot.walkTopDown().count { it.isFile } + if (file.exists()) 0 else 1
        if (totalAfter > MAX_TOTAL_BYTES || filesAfter > MAX_FILES) throw IllegalArgumentException("工作区超过大小限制")
        file.parentFile?.mkdirs()
        atomicWrite(file, content)
        meta.put("updatedAt", System.currentTimeMillis()).put("state", STATE_OPEN).put("pendingApproval", false)
        writeMeta(dir, meta)
        return summary(dir)
    }

    @Synchronized fun delete(id: String, path: String): Workspace {
        val dir = workspaceDir(id)
        val meta = readMeta(dir)
        if (meta.getString("state") !in setOf(STATE_OPEN, STATE_PENDING)) throw IllegalArgumentException("工作区已结束")
        validateWorkspacePath(meta, path)
        val file = resolveWorkspace(File(dir, "work"), path)
        if (!file.isFile) throw IllegalArgumentException("工作区文件不存在")
        if (!file.delete()) throw IOException("删除失败")
        meta.put("updatedAt", System.currentTimeMillis()).put("state", STATE_OPEN).put("pendingApproval", false)
        writeMeta(dir, meta)
        return summary(dir)
    }

    @Synchronized fun diff(id: String): String {
        val dir = workspaceDir(id)
        val base = File(dir, "base")
        val work = File(dir, "work")
        val paths = (files(base) + files(work)).toSortedSet()
        val output = StringBuilder()
        paths.forEach { path ->
            val old = File(base, path).takeIf { it.isFile }?.readText(Charsets.UTF_8)
            val new = File(work, path).takeIf { it.isFile }?.readText(Charsets.UTF_8)
            if (old == new) return@forEach
            output.append("--- a/").append(path).append('\n').append("+++ b/").append(path).append('\n')
            if (old == null) output.append("@@ 新建文件 @@\n")
            else if (new == null) output.append("@@ 删除文件 @@\n")
            else output.append("@@ 修改 @@\n")
            old?.lineSequence()?.take(MAX_DIFF_LINES).orEmpty().forEach { output.append('-').append(it).append('\n') }
            new?.lineSequence()?.take(MAX_DIFF_LINES).orEmpty().forEach { output.append('+').append(it).append('\n') }
            if (output.length > MAX_DIFF_CHARS) return output.substring(0, MAX_DIFF_CHARS) + "\n...Diff 已截断"
        }
        return output.toString().ifEmpty { "无修改" }
    }

    @Synchronized fun requestApply(id: String): Workspace {
        val dir = workspaceDir(id)
        val meta = readMeta(dir)
        if (meta.getString("state") != STATE_OPEN) throw IllegalArgumentException("工作区状态不允许申请应用")
        if (changedCount(dir) == 0) throw IllegalArgumentException("工作区没有修改")
        meta.put("state", STATE_PENDING).put("pendingApproval", true).put("updatedAt", System.currentTimeMillis())
        writeMeta(dir, meta)
        return summary(dir)
    }

    /** Local UI only. Checks every original hash before touching the script directory. */
    @Synchronized fun applyConfirmed(id: String): Workspace {
        val dir = workspaceDir(id)
        val meta = readMeta(dir)
        if (meta.getString("state") != STATE_PENDING || !meta.optBoolean("pendingApproval")) {
            throw IllegalArgumentException("没有待确认的应用请求")
        }
        val base = File(dir, "base"); val work = File(dir, "work"); val backup = File(dir, "backup")
        backup.deleteRecursively(); backup.mkdirs()
        val original = meta.getJSONObject("originalHashes")
        val target = resolveScript(meta.getString("targetPath"))
        val targetIsFile = meta.optBoolean("targetIsFile")
        val paths = (files(base) + files(work)).toSortedSet()
        paths.forEach { path ->
            val destination = if (targetIsFile) target else File(target, path)
            val expected = original.optString(path, MISSING)
            val actual = if (destination.isFile) sha256(destination.readBytes()) else MISSING
            if (actual != expected) throw IllegalStateException("原文件已变化，拒绝覆盖：$path")
        }
        val applied = JSONObject()
        paths.forEach { path ->
            val destination = if (targetIsFile) target else File(target, path)
            if (destination.isFile) File(backup, path).apply { parentFile?.mkdirs(); writeBytes(destination.readBytes()) }
        }
        try {
            paths.forEach { path ->
                val destination = if (targetIsFile) target else File(target, path)
                val source = File(work, path)
                if (source.isFile) {
                    val bytes = source.readBytes(); destination.parentFile?.mkdirs(); atomicWrite(destination, bytes)
                    applied.put(path, sha256(bytes))
                } else {
                    if (destination.exists() && !destination.delete()) throw IOException("无法删除：$path")
                    applied.put(path, MISSING)
                }
            }
        } catch (error: Exception) {
            paths.forEach { path ->
                val destination = if (targetIsFile) target else File(target, path); val saved = File(backup, path)
                runCatching {
                    if (saved.isFile) { destination.parentFile?.mkdirs(); atomicWrite(destination, saved.readBytes()) }
                    else if (destination.exists()) destination.delete()
                }
            }
            throw error
        }
        meta.put("state", STATE_APPLIED).put("pendingApproval", false).put("updatedAt", System.currentTimeMillis())
            .put("appliedAt", System.currentTimeMillis()).put("appliedHashes", applied)
        writeMeta(dir, meta)
        return summary(dir)
    }

    /** Local UI only. Applied workspaces restore backups; unapplied ones are simply discarded. */
    @Synchronized fun rollbackConfirmed(id: String): Workspace {
        val dir = workspaceDir(id)
        val meta = readMeta(dir)
        if (meta.getString("state") == STATE_APPLIED) {
            val target = resolveScript(meta.getString("targetPath")); val targetIsFile = meta.optBoolean("targetIsFile")
            val applied = meta.getJSONObject("appliedHashes"); val backup = File(dir, "backup")
            val paths = applied.keys().asSequence().toList()
            paths.forEach { path ->
                val destination = if (targetIsFile) target else File(target, path)
                val actual = if (destination.isFile) sha256(destination.readBytes()) else MISSING
                if (actual != applied.getString(path)) throw IllegalStateException("应用后文件已变化，拒绝回退：$path")
            }
            paths.forEach { path ->
                val destination = if (targetIsFile) target else File(target, path); val saved = File(backup, path)
                if (saved.isFile) { destination.parentFile?.mkdirs(); atomicWrite(destination, saved.readBytes()) }
                else if (destination.exists() && !destination.delete()) throw IOException("无法回退：$path")
            }
        }
        meta.put("state", STATE_ROLLED_BACK).put("pendingApproval", false).put("updatedAt", System.currentTimeMillis())
        writeMeta(dir, meta)
        return summary(dir)
    }

    private fun summary(dir: File): Workspace {
        val meta = readMeta(dir)
        return Workspace(meta.getString("id"), meta.getString("targetPath"), meta.getLong("createdAt"),
            meta.getLong("updatedAt"), meta.getString("state"), meta.optBoolean("pendingApproval"), changedCount(dir))
    }

    private fun changedCount(dir: File): Int {
        val base = File(dir, "base"); val work = File(dir, "work")
        return (files(base) + files(work)).toSet().count { path ->
            val a = File(base, path); val b = File(work, path)
            a.isFile != b.isFile || (a.isFile && !a.readBytes().contentEquals(b.readBytes()))
        }
    }

    private fun files(dir: File): List<String> = if (!dir.isDirectory) emptyList() else dir.walkTopDown()
        .filter { it.isFile }.map { it.relativeTo(dir).invariantSeparatorsPath }.toList()

    private fun workspaceDir(id: String): File {
        if (!id.matches(Regex("ws-[a-f0-9]{8}"))) throw IllegalArgumentException("工作区 ID 无效")
        return File(root, id).also { if (!File(it, META).isFile) throw IllegalArgumentException("工作区不存在") }
    }
    private fun resolveScript(path: String): File {
        if (path.indexOf('\u0000') >= 0) throw IllegalArgumentException("路径无效")
        val file = File(scriptRoot, path).canonicalFile
        if (!inside(scriptRoot, file)) throw IllegalArgumentException("路径超出脚本目录")
        return file
    }
    private fun resolveWorkspace(base: File, path: String): File {
        if (path.isBlank() || path.indexOf('\u0000') >= 0) throw IllegalArgumentException("路径无效")
        val file = File(base, path).canonicalFile
        if (!inside(base.canonicalFile, file)) throw IllegalArgumentException("路径超出工作区")
        return file
    }
    private fun validateWorkspacePath(meta: JSONObject, path: String) {
        if (meta.optBoolean("targetIsFile") && path.replace('\\', '/').trim('/') != File(meta.getString("targetPath")).name) {
            throw IllegalArgumentException("单文件工作区不能新建其他文件")
        }
    }
    private fun inside(base: File, child: File) = child == base || child.path.startsWith(base.path + File.separator)
    private fun readMeta(dir: File) = JSONObject(File(dir, META).readText(Charsets.UTF_8))
    private fun writeMeta(dir: File, json: JSONObject) { dir.mkdirs(); atomicWrite(File(dir, META), json.toString().toByteArray(Charsets.UTF_8)) }
    private fun atomicWrite(file: File, bytes: ByteArray) {
        val temp = File(file.parentFile, ".${file.name}.${System.nanoTime()}.tmp")
        val old = File(file.parentFile, ".${file.name}.${System.nanoTime()}.old")
        try {
            temp.writeBytes(bytes)
            if (file.exists() && !file.renameTo(old)) throw IOException("无法备份原文件")
            if (!temp.renameTo(file)) {
                if (old.exists()) old.renameTo(file)
                throw IOException("无法替换文件")
            }
            if (old.exists()) old.delete()
        } finally {
            if (temp.exists()) temp.delete()
            if (old.exists() && !file.exists()) old.renameTo(file)
        }
    }
    private fun cleanup() {
        root.mkdirs()
        val cutoff = System.currentTimeMillis() - McpSettings.historyDays(appContext).toLong() * 86_400_000L
        root.listFiles().orEmpty().filter { it.isDirectory && it.lastModified() < cutoff }.forEach { it.deleteRecursively() }
    }
    companion object {
        private const val META = "workspace.json"
        private const val STATE_OPEN = "OPEN"
        private const val STATE_PENDING = "PENDING_APPROVAL"
        private const val STATE_APPLIED = "APPLIED"
        private const val STATE_ROLLED_BACK = "ROLLED_BACK"
        private const val MISSING = "<missing>"
        private const val MAX_FILES = 500
        private const val MAX_FILE_BYTES = 256 * 1024
        private const val MAX_TOTAL_BYTES = 4 * 1024 * 1024
        private const val MAX_DIFF_LINES = 1000
        private const val MAX_DIFF_CHARS = 128 * 1024
        private val TEXT_EXTENSIONS = setOf("js", "json", "txt", "md", "xml", "css", "html")
        private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
