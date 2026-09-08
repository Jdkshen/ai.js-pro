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

    private fun store(scriptRoot: File): McpWorkspaceStore {
        val storageRoot = temporaryFolder.newFolder("workspace-store-${System.nanoTime()}")
        return McpWorkspaceStore.forTesting(storageRoot, scriptRoot)
    }
}
