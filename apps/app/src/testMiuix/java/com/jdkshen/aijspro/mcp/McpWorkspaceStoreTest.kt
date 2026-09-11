package com.jdkshen.aijspro.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class McpWorkspaceStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun createsNewTextFileThroughWorkspace() {
        val root = temporaryFolder.newFolder("scripts")
        val store = store(root)

        val workspace = store.open("new-script.js", create = true)
        store.write(workspace.id, "new-script.js", "console.log('ok')".toByteArray())
        val applied = store.applyAuthorized(workspace.id)

        assertEquals("APPLIED", applied.state)
        assertEquals("console.log('ok')", File(root, "new-script.js").readText())
    }

    @Test
    fun refusesApplyWhenOriginalChanged() {
        val root = temporaryFolder.newFolder("scripts")
        val target = File(root, "main.js").apply { writeText("original") }
        val store = store(root)
        val workspace = store.open("main.js")
        store.write(workspace.id, "main.js", "from mcp".toByteArray())
        target.writeText("external edit")

        val error = runCatching { store.applyAuthorized(workspace.id) }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("原文件已变化"))
        assertEquals("external edit", target.readText())
    }

    @Test
    fun restoresBackupWhenAppliedFileIsUnchanged() {
        val root = temporaryFolder.newFolder("scripts")
        val target = File(root, "main.js").apply { writeText("original") }
        val store = store(root)
        val workspace = store.open("main.js")
        store.write(workspace.id, "main.js", "from mcp".toByteArray())
        store.applyAuthorized(workspace.id)

        val rolledBack = store.rollbackConfirmed(workspace.id)

        assertEquals("ROLLED_BACK", rolledBack.state)
        assertFalse(rolledBack.pendingApproval)
        assertEquals("original", target.readText())
    }

    @Test
    fun refusesRollbackWhenAppliedFileChangedAgain() {
        val root = temporaryFolder.newFolder("scripts")
        val target = File(root, "main.js").apply { writeText("original") }
        val store = store(root)
        val workspace = store.open("main.js")
        store.write(workspace.id, "main.js", "from mcp".toByteArray())
        store.applyAuthorized(workspace.id)
        target.writeText("newer phone edit")

        val error = runCatching { store.rollbackConfirmed(workspace.id) }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("应用后文件已变化"))
        assertEquals("newer phone edit", target.readText())
    }

    @Test
    fun readsAndWritesFileInSubdirectory() {
        val root = temporaryFolder.newFolder("scripts")
        File(root, "悬浮球项目").mkdirs()
        File(root, "悬浮球项目/README.md").writeText("hello")
        val store = store(root)

        val workspace = store.open("悬浮球项目/README.md")
        assertEquals("悬浮球项目/README.md", workspace.targetPath)
        assertEquals("hello", String(store.read(workspace.id, "悬浮球项目/README.md")))
        // 子目录文件也能直接写回（旧实现只存 basename，读写都会失败）
        store.write(workspace.id, "悬浮球项目/README.md", "updated".toByteArray())
        val applied = store.applyAuthorized(workspace.id)

        assertEquals("APPLIED", applied.state)
        assertEquals("updated", File(root, "悬浮球项目/README.md").readText())
    }

    @Test
    fun singleFileWorkspaceAcceptsBasenameAndRejectsOtherPaths() {
        val root = temporaryFolder.newFolder("scripts")
        File(root, "dir").mkdirs()
        File(root, "dir/a.js").writeText("a")
        File(root, "b.js").writeText("b")
        val store = store(root)
        val workspace = store.open("dir/a.js")

        // 传文件名（旧客户端写法）仍可用
        assertEquals("a", String(store.read(workspace.id, "a.js")))
        val error = runCatching { store.write(workspace.id, "b.js", "x".toByteArray()) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("dir/a.js"))
    }

    @Test
    fun createIsIdempotentAndCreatesParentDirectories() {
        val root = temporaryFolder.newFolder("scripts")
        File(root, "existing.js").writeText("keep")
        val store = store(root)

        // 已存在时 create=true 直接打开（不再报“新建目标已存在”）
        val existing = store.open("existing.js", create = true)
        assertEquals("existing.js", existing.targetPath)
        assertEquals("keep", String(store.read(existing.id, "existing.js")))

        // 父目录不存在时自动创建，且创建出来的工作区可以直接写入
        val created = store.open("新目录/新文件.js", create = true)
        store.write(created.id, "新目录/新文件.js", "console.log(1)".toByteArray())
        store.applyAuthorized(created.id)
        assertEquals("console.log(1)", File(root, "新目录/新文件.js").readText())
    }

    @Test
    fun reusesUnchangedWorkspaceForSameTarget() {
        val root = temporaryFolder.newFolder("scripts")
        File(root, "main.js").writeText("x")
        val store = store(root)

        val first = store.open("main.js")
        val second = store.open("main.js")
        assertEquals(first.id, second.id)

        store.write(first.id, "main.js", "y".toByteArray())
        // 有修改时不再复用，避免把两个客户端的改动混在一起
        assertFalse(first.id == store.open("main.js").id)
    }

    @Test
    fun cancelsPendingApprovalAndPurgesFinishedWorkspaces() {
        val root = temporaryFolder.newFolder("scripts")
        File(root, "main.js").writeText("x")
        val store = store(root)

        val workspace = store.open("main.js")
        store.write(workspace.id, "main.js", "y".toByteArray())
        val pending = store.requestApply(workspace.id)
        assertTrue(pending.pendingApproval)

        val cancelled = store.cancel(workspace.id)
        assertFalse(cancelled.pendingApproval)
        assertEquals("OPEN", cancelled.state)

        // cancel 后可以重新申请并应用
        store.applyAuthorized(workspace.id)
        assertEquals(0, store.purge(applied = false))          // 已应用的工作区默认保留（还能回退）
        assertEquals(1, store.purge(applied = true, keepAppliedHours = 0))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun makesDirectoryInScriptRoot() {
        val root = temporaryFolder.newFolder("scripts")
        val store = store(root)

        assertEquals("a/b", store.makeDirectory("a/b/"))
        assertTrue(File(root, "a/b").isDirectory)
    }

    private fun store(scriptRoot: File): McpWorkspaceStore {
        val storageRoot = temporaryFolder.newFolder("workspace-store-${System.nanoTime()}")
        return McpWorkspaceStore.forTesting(storageRoot, scriptRoot)
    }
}
