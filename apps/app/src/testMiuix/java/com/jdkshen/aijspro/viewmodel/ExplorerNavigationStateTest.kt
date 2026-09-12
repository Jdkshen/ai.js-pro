package com.jdkshen.aijspro.viewmodel

import com.jdkshen.aijspro.ui.viewmodel.ExplorerNavigationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 页面栈纯状态单测：不依赖 Android 模型，用 String 当页面即可。
 *
 * 文件列表的"进入子页 / 返回"行为在旧 [com.jdkshen.aijspro.ui.explorer.ExplorerView] 与
 * 后续 Compose 实现里共用这份语义，这里把它钉死。
 */
class ExplorerNavigationStateTest {

    @Test
    fun `starts at the initial page without history`() {
        val state = ExplorerNavigationState("root")
        assertEquals("root", state.current)
        assertFalse(state.canGoBack())
        assertEquals(0, state.depth)
    }

    @Test
    fun `push moves to the child page and remembers the parent`() {
        val state = ExplorerNavigationState("root")
        state.push("a")
        state.push("a/b")
        assertEquals("a/b", state.current)
        assertTrue(state.canGoBack())
        assertEquals(listOf("root", "a"), state.historySnapshot())
        assertEquals(2, state.depth)
    }

    @Test
    fun `back returns the previous page and pops exactly one level`() {
        val state = ExplorerNavigationState("root")
        state.push("a")
        state.push("a/b")
        assertEquals("a", state.back())
        assertEquals("a", state.current)
        assertEquals(listOf("root"), state.historySnapshot())
        assertEquals("root", state.back())
        assertEquals("root", state.current)
        assertFalse(state.canGoBack())
    }

    @Test
    fun `back on an empty history keeps the current page`() {
        val state = ExplorerNavigationState("root")
        assertEquals("root", state.back())
        assertEquals("root", state.back())
        assertEquals("root", state.current)
        assertFalse(state.canGoBack())
    }

    @Test
    fun `reset clears the history`() {
        val state = ExplorerNavigationState("root")
        state.push("a")
        state.push("a/b")
        state.reset("other")
        assertEquals("other", state.current)
        assertFalse(state.canGoBack())
        assertTrue(state.historySnapshot().isEmpty())
    }

    @Test
    fun `clearHistory keeps the current page`() {
        val state = ExplorerNavigationState("root")
        state.push("a")
        state.clearHistory()
        assertEquals("a", state.current)
        assertFalse(state.canGoBack())
    }

    @Test
    fun `replaceCurrent keeps the history intact`() {
        val state = ExplorerNavigationState("root")
        state.push("a")
        state.replaceCurrent("jumped")
        assertEquals("jumped", state.current)
        assertEquals(listOf("root"), state.historySnapshot())
        assertEquals("root", state.back())
    }

    @Test
    fun `trimTo keeps only the newest levels`() {
        val state = ExplorerNavigationState("p0")
        for (i in 1..5) {
            state.push("p$i")
        }
        assertEquals(5, state.depth)
        state.trimTo(2)
        assertEquals(2, state.depth)
        assertEquals(listOf("p3", "p4"), state.historySnapshot())
        assertEquals("p4", state.back())
        assertEquals("p3", state.back())
        assertFalse(state.canGoBack())
    }
}
