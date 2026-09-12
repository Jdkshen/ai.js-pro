package com.jdkshen.aijspro.viewmodel

import com.jdkshen.aijspro.ui.viewmodel.ExplorerNavigationState
import com.jdkshen.aijspro.ui.viewmodel.ExplorerRevealPlan
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ExplorerRevealPlanTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `direct file reveals in root`() {
        val root = temporary.newFolder("scripts")
        val target = File(root, "test.js").apply { createNewFile() }
        val plan = ExplorerRevealPlan.create(root, target)!!
        assertEquals(target.canonicalPath, plan.targetPath)
        assertEquals(listOf(root.canonicalPath), plan.directories)
    }

    @Test fun `deep file builds root to parent route`() {
        val root = temporary.newFolder("scripts")
        val parent = File(root, "a/b").apply { mkdirs() }
        val target = File(parent, "test.js").apply { createNewFile() }
        assertEquals(listOf(root.canonicalPath, File(root, "a").canonicalPath, parent.canonicalPath),
            ExplorerRevealPlan.create(root, target)!!.directories)
    }

    @Test fun `directory result reveals its row not its children`() {
        val root = temporary.newFolder("scripts")
        val target = File(root, "folder").apply { mkdir() }
        assertEquals(listOf(root.canonicalPath), ExplorerRevealPlan.create(root, target)!!.directories)
    }

    @Test fun `sibling with same prefix is outside root`() {
        val root = temporary.newFolder("scripts")
        val sibling = temporary.newFolder("scripts-other")
        val target = File(sibling, "test.js").apply { createNewFile() }
        assertNull(ExplorerRevealPlan.create(root, target))
    }

    @Test fun `dot dot escape is rejected`() {
        val root = temporary.newFolder("scripts")
        val outside = temporary.newFile("outside.js")
        assertNull(ExplorerRevealPlan.create(root, File(root, "../${outside.name}")))
    }

    @Test fun `normalized descendant is accepted`() {
        val root = temporary.newFolder("scripts")
        File(root, "a").mkdir()
        val target = File(root, "test.js").apply { createNewFile() }
        assertEquals(target.canonicalPath,
            ExplorerRevealPlan.create(root, File(root, "a/../test.js"))!!.targetPath)
    }

    @Test fun `missing file is rejected`() {
        val root = temporary.newFolder("scripts")
        assertNull(ExplorerRevealPlan.create(root, File(root, "missing.js")))
    }

    @Test fun `root itself has no revealable row inside root`() {
        val root = temporary.newFolder("scripts")
        assertNull(ExplorerRevealPlan.create(root, root))
    }

    @Test fun `search from another directory uses browser root and builds meaningful back stack`() {
        val root = temporary.newFolder("scripts")
        val old = File(root, "old").apply { mkdir() }
        val parent = File(root, "new/deep").apply { mkdirs() }
        val target = File(parent, "test.js").apply { createNewFile() }
        val state = ExplorerNavigationState(root.canonicalPath)
        state.push(old.canonicalPath)
        val plan = ExplorerRevealPlan.create(root, target)!!
        state.reset(plan.directories.first())
        plan.directories.drop(1).forEach(state::push)
        assertEquals(parent.canonicalPath, state.current)
        assertEquals(File(root, "new").canonicalPath, state.back())
        assertEquals(root.canonicalPath, state.back())
        assertFalse(state.canGoBack())
    }
}
