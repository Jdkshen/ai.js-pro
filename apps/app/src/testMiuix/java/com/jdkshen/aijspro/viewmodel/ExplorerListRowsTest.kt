package com.jdkshen.aijspro.viewmodel

import com.jdkshen.aijspro.ui.viewmodel.ExplorerListRows
import com.jdkshen.aijspro.ui.viewmodel.ExplorerListRows.Item
import com.jdkshen.aijspro.ui.viewmodel.ExplorerListRows.SORT_TYPE_DATE
import com.jdkshen.aijspro.ui.viewmodel.ExplorerListRows.SORT_TYPE_NAME
import com.jdkshen.aijspro.ui.viewmodel.ExplorerListRows.SORT_TYPE_SIZE
import com.jdkshen.aijspro.ui.viewmodel.ExplorerListRows.SORT_TYPE_TYPE
import com.jdkshen.aijspro.ui.viewmodel.ExplorerListRows.SortSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文件列表排序/分组/折叠的纯状态单测。
 *
 * <p>这些用例锁的是**旧 `ExplorerView` 的真实语义**，不是"看起来合理"的语义。三处容易写错的点：
 *
 * 1. `ExplorerSorter` 的比较器方向**不统一**：NAME/SIZE/TYPE 是反序写法（拿 o2 比 o1），
 *    DATE 是正序写法；再统一靠 `ascending` 翻转。逐条推导后（ascending = 默认 false）：
 *    NAME 名称升序、DATE 时间降序、SIZE 大小升序、TYPE 类型升序。单测把这条钉死，
 *    谁"顺手统一"成一种写法就会红。
 * 2. 文件夹组与文件组**各自独立**排序，用各自的 sortType/ascending。
 * 3. 折叠只是**输出空列表**，不影响另一组，也不改变排序结果。
 */
class ExplorerListRowsTest {

    /** 测试用最小实现；状态层不认识 Android 的 ExplorerItem。 */
    private data class Row(
        override val name: String,
        override val type: String = "",
        override val size: Long = 0L,
        override val lastModified: Long = 0L
    ) : Item

    private fun names(items: List<Row>) = items.map { it.name }

    private val folders = listOf(
        Row("b_folder"),
        Row("a_folder"),
        Row("c_folder")
    )

    private val files = listOf(
        Row("z.js", type = "js", size = 300L, lastModified = 3_000L),
        Row("a.js", type = "js", size = 100L, lastModified = 1_000L),
        Row("m.txt", type = "txt", size = 200L, lastModified = 2_000L)
    )

    // ---- 分组 ----------------------------------------------------------------

    @Test
    fun `folders and files stay in their own groups`() {
        val result = ExplorerListRows.build(folders, files)
        assertEquals(3, result.folders.size)
        assertEquals(3, result.files.size)
        assertEquals(6, result.count)
    }

    @Test
    fun `count matches the legacy group plus item sum`() {
        // 旧实现 count() = mItemGroups.size() + mItems.size()
        val result = ExplorerListRows.build(folders, files)
        assertEquals(folders.size + files.size, result.count)
    }

    @Test
    fun `empty input produces empty groups`() {
        val result = ExplorerListRows.build(emptyList<Row>(), emptyList<Row>())
        assertEquals(0, result.count)
        assertTrue(result.folders.isEmpty())
        assertTrue(result.files.isEmpty())
    }

    // ---- 默认排序（SortConfig 默认：NAME + ascending = false）-----------------

    @Test
    fun `default spec sorts folders and files ascending by name`() {
        val result = ExplorerListRows.build(folders, files)
        assertEquals(listOf("a_folder", "b_folder", "c_folder"), names(result.folders))
        assertEquals(listOf("a.js", "m.txt", "z.js"), names(result.files))
    }

    // ---- ascending 的语义是反的（ExplorerSorter 的比较器本身反序）-------------

    @Test
    fun `ascending true reverses into descending order because the legacy comparator is inverted`() {
        val result = ExplorerListRows.build(
            folders,
            files,
            SortSpec(dirAscending = true, fileAscending = true)
        )
        assertEquals(listOf("c_folder", "b_folder", "a_folder"), names(result.folders))
        assertEquals(listOf("z.js", "m.txt", "a.js"), names(result.files))
    }

    // ---- 文件夹组与文件组各自独立排序 ----------------------------------------

    @Test
    fun `folder and file groups sort independently with their own spec`() {
        val result = ExplorerListRows.build(
            folders,
            files,
            SortSpec(
                dirSortType = SORT_TYPE_NAME,
                dirAscending = false,
                fileSortType = SORT_TYPE_SIZE,
                fileAscending = false
            )
        )
        assertEquals(listOf("a_folder", "b_folder", "c_folder"), names(result.folders))
        assertEquals(listOf("a.js", "m.txt", "z.js"), names(result.files))
    }

    @Test
    fun `one group can be ascending while the other is descending`() {
        val result = ExplorerListRows.build(
            folders,
            files,
            SortSpec(dirAscending = true, fileAscending = false)
        )
        assertEquals(listOf("c_folder", "b_folder", "a_folder"), names(result.folders))
        assertEquals(listOf("a.js", "m.txt", "z.js"), names(result.files))
    }

    // ---- 各排序类型 ----------------------------------------------------------

    @Test
    fun `size sort orders by bytes with the legacy inverted comparator`() {
        // SIZE = (o1,o2) -> Long.compare(o2.size, o1.size)：ascending=false 时按 size 升序
        val result = ExplorerListRows.build(
            emptyList<Row>(), files,
            SortSpec(fileSortType = SORT_TYPE_SIZE, fileAscending = false)
        )
        assertEquals(listOf("a.js", "m.txt", "z.js"), names(result.files))
    }

    @Test
    fun `date sort orders by lastModified with the legacy comparator`() {
        // DATE = (o1,o2) -> Long.compare(o1.lastModified, o2.lastModified)：ascending=false 时按时间降序
        val result = ExplorerListRows.build(
            emptyList<Row>(), files,
            SortSpec(fileSortType = SORT_TYPE_DATE, fileAscending = false)
        )
        assertEquals(listOf("z.js", "m.txt", "a.js"), names(result.files))
    }

    @Test
    fun `type sort groups by type string`() {
        // TYPE = (o1,o2) -> o2.type.compareTo(o1.type)：ascending=false 时按类型升序
        val mixed = listOf(
            Row("one.js", type = "js"),
            Row("two.txt", type = "txt"),
            Row("three.apk", type = "apk")
        )
        val result = ExplorerListRows.build(
            emptyList<Row>(), mixed,
            SortSpec(fileSortType = SORT_TYPE_TYPE, fileAscending = false)
        )
        assertEquals(listOf("three.apk", "one.js", "two.txt"), names(result.files))
    }

    @Test
    fun `unknown sort type fails loudly instead of falling back`() {
        // 旧 getComparator 对未知类型抛 IllegalArgumentException，不能静默回退成名称排序
        assertThrows(IllegalArgumentException::class.java) {
            ExplorerListRows.sortWith(files, 0x99, ascending = false)
        }
    }

    // ---- 折叠（旧 ExplorerPageState.dirsCollapsed / filesCollapsed）----------

    @Test
    fun `collapsed folders are dropped without touching files`() {
        val result = ExplorerListRows.build(folders, files, dirsCollapsed = true)
        assertTrue(result.folders.isEmpty())
        assertEquals(listOf("a.js", "m.txt", "z.js"), names(result.files))
    }

    @Test
    fun `collapsed files are dropped without touching folders`() {
        val result = ExplorerListRows.build(folders, files, filesCollapsed = true)
        assertEquals(listOf("a_folder", "b_folder", "c_folder"), names(result.folders))
        assertTrue(result.files.isEmpty())
    }

    @Test
    fun `collapsing both groups yields an empty list but keeps count consistent`() {
        val result = ExplorerListRows.build(folders, files, dirsCollapsed = true, filesCollapsed = true)
        assertEquals(0, result.count)
    }

    // ---- 不改动入参 ----------------------------------------------------------

    @Test
    fun `build does not mutate the caller lists`() {
        val foldersCopy = folders.toList()
        val filesCopy = files.toList()
        ExplorerListRows.build(folders, files)
        assertEquals(foldersCopy, folders)
        assertEquals(filesCopy, files)
    }

    // ---- 排序类型与文案键的对应（UI 层菜单用它做一致性断言）-------------------

    @Test
    fun `sort type labels match the legacy constants`() {
        assertEquals("name", ExplorerListRows.sortTypeLabelKey(SORT_TYPE_NAME))
        assertEquals("type", ExplorerListRows.sortTypeLabelKey(SORT_TYPE_TYPE))
        assertEquals("size", ExplorerListRows.sortTypeLabelKey(SORT_TYPE_SIZE))
        assertEquals("date", ExplorerListRows.sortTypeLabelKey(SORT_TYPE_DATE))
        assertThrows(IllegalArgumentException::class.java) {
            ExplorerListRows.sortTypeLabelKey(0x99)
        }
    }

    @Test
    fun `sort constants keep the legacy hex values`() {
        // 这两个值要跟 ExplorerItemList.SORT_TYPE_* 完全一致，preferences 里存的就是它们
        assertEquals(0x10, SORT_TYPE_NAME)
        assertEquals(0x20, SORT_TYPE_TYPE)
        assertEquals(0x30, SORT_TYPE_SIZE)
        assertEquals(0x40, SORT_TYPE_DATE)
    }
}
