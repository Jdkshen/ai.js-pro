package com.jdkshen.aijspro.viewmodel

import com.jdkshen.aijspro.ui.viewmodel.ExplorerScrollPosition
import org.junit.Assert.assertEquals
import org.junit.Test

class ExplorerScrollPositionTest {
    @Test fun `unchanged rows keep index and pixel offset`() {
        val position = ExplorerScrollPosition(1, 23, "/b.js")
        assertEquals(1, position.indexIn(listOf("/a.js", "/b.js", "/c.js")))
        assertEquals(23, position.offset)
    }

    @Test fun `new file before viewport keeps the original file anchored`() {
        assertEquals(2, ExplorerScrollPosition(1, 23, "/b.js")
            .indexIn(listOf("/new.js", "/a.js", "/b.js", "/c.js")))
    }

    @Test fun `removed preceding file updates anchored index`() {
        assertEquals(0, ExplorerScrollPosition(1, 23, "/b.js").indexIn(listOf("/b.js", "/c.js")))
    }

    @Test fun `missing anchor clamps after deletion`() {
        assertEquals(1, ExplorerScrollPosition(99, 0, "/deleted.js").indexIn(listOf("/a.js", "/b.js")))
    }

    @Test fun `empty directory restores safely to zero`() {
        assertEquals(0, ExplorerScrollPosition(99, 10, "/deleted.js").indexIn(emptyList()))
    }

    @Test fun `negative index clamps to zero`() {
        assertEquals(0, ExplorerScrollPosition(-1, 0).indexIn(listOf("/a.js")))
    }

    @Test fun `directory positions remain independent after back navigation`() {
        val saved = mapOf("/root" to ExplorerScrollPosition(40, 17),
            "/root/child" to ExplorerScrollPosition(5, 9))
        assertEquals(40, saved.getValue("/root").indexIn(List(100) { "/root/$it.js" }))
        assertEquals(17, saved.getValue("/root").offset)
        assertEquals(5, saved.getValue("/root/child").indexIn(List(10) { "/root/child/$it.js" }))
        assertEquals(9, saved.getValue("/root/child").offset)
    }
}
