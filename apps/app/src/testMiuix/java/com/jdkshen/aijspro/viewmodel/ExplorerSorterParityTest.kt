package com.jdkshen.aijspro.viewmodel

import com.jdkshen.aijspro.model.explorer.ExplorerItem
import com.jdkshen.aijspro.model.explorer.ExplorerPage
import com.jdkshen.aijspro.model.explorer.ExplorerSorter
import com.jdkshen.aijspro.model.script.ScriptFile
import com.jdkshen.aijspro.ui.viewmodel.ExplorerListRows
import com.jdkshen.aijspro.ui.viewmodel.ExplorerListRows.Item
import com.jdkshen.aijspro.ui.viewmodel.ExplorerListRows.SORT_TYPE_DATE
import com.jdkshen.aijspro.ui.viewmodel.ExplorerListRows.SORT_TYPE_NAME
import com.jdkshen.aijspro.ui.viewmodel.ExplorerListRows.SORT_TYPE_SIZE
import com.jdkshen.aijspro.ui.viewmodel.ExplorerListRows.SORT_TYPE_TYPE
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 把 [ExplorerListRows] 的排序与**旧实现的真实排序器** [ExplorerSorter] 逐项对照。
 *
 * <p>为什么需要这个：`ExplorerSorter` 的比较器方向不统一（NAME/SIZE/TYPE 用反序写法、DATE 用正序），
 * 再叠加 `ascending` 的反转，靠人脑推导极容易搞反——事实上第一版实现就推导错了四处。
 * 这里不再"推断"旧语义，而是直接跑旧代码拿结果，与纯状态层比。
 *
 * <p>这组用例可以在 JVM 上跑，因为 [ExplorerSorter] 只依赖 JDK（没有 Android 类型）。
 * 需要的 [ExplorerItem] 实现由本文件的 [FakeItem] 提供——它只实现排序用得到的四个读取方法，
 * 其余方法在排序路径上不会被触碰。
 */
class ExplorerSorterParityTest {

    /** 排序路径只需要 name/type/size/lastModified，其余成员不会被调用。 */
    private class FakeItem(
        private val name: String,
        private val type: String,
        private val size: Long,
        private val lastModified: Long
    ) : ExplorerItem {
        override fun getName() = name
        override fun getType() = type
        override fun getSize() = size
        override fun lastModified() = lastModified

        override fun getParent(): ExplorerPage? = null
        override fun getPath() = name
        override fun canDelete() = false
        override fun canRename() = false
        override fun toScriptFile(): ScriptFile? = null
        override fun isEditable() = false
        override fun isExecutable() = false
    }

    /** [ExplorerListRows.Item] 侧的同名视图，与 [FakeItem] 一一对应。 */
    private class FakeRow(
        override val name: String,
        override val type: String,
        override val size: Long,
        override val lastModified: Long
    ) : Item

    /** 固定数据：名称、类型、大小、时间都不相同，避免并列导致排序不稳定而难以比较。 */
    private val names = listOf("alpha", "beta", "gamma", "delta", "epsilon")

    private fun legacyItems(): List<FakeItem> = names.mapIndexed { index, name ->
        FakeItem(
            name = name,
            type = listOf("js", "txt", "apk", "json", "md")[index],
            size = (index + 1) * 111L,
            lastModified = 1_700_000_000_000L + index * 86_400_000L
        )
    }

    private fun rows(): List<FakeRow> = names.mapIndexed { index, name ->
        FakeRow(
            name = name,
            type = listOf("js", "txt", "apk", "json", "md")[index],
            size = (index + 1) * 111L,
            lastModified = 1_700_000_000_000L + index * 86_400_000L
        )
    }

    /** `ExplorerItemList.getComparator` 里那张表的等价物（含默认分支抛错的行为）。 */
    private fun legacyComparator(sortType: Int) = when (sortType) {
        SORT_TYPE_NAME -> ExplorerSorter.NAME
        SORT_TYPE_DATE -> ExplorerSorter.DATE
        SORT_TYPE_SIZE -> ExplorerSorter.SIZE
        SORT_TYPE_TYPE -> ExplorerSorter.TYPE
        else -> throw IllegalArgumentException("unknown type $sortType")
    }

    /** 用旧排序器排一遍，返回名称顺序。 */
    private fun legacyOrder(sortType: Int, ascending: Boolean): List<String> {
        val items = legacyItems().toMutableList()
        ExplorerSorter.sort(items, legacyComparator(sortType), ascending)
        return items.map { it.getName() }
    }

    /** 用纯状态层排一遍，返回名称顺序。 */
    private fun ourOrder(sortType: Int, ascending: Boolean): List<String> =
        ExplorerListRows.sortWith(rows(), sortType, ascending).map { it.name }

    @Test
    fun `all four sort types match the legacy sorter in descending direction`() {
        for (sortType in listOf(SORT_TYPE_NAME, SORT_TYPE_DATE, SORT_TYPE_SIZE, SORT_TYPE_TYPE)) {
            assertEquals(
                "sortType=$sortType ascending=false 与 ExplorerSorter 不一致",
                legacyOrder(sortType, ascending = false),
                ourOrder(sortType, ascending = false)
            )
        }
    }

    @Test
    fun `all four sort types match the legacy sorter in ascending direction`() {
        for (sortType in listOf(SORT_TYPE_NAME, SORT_TYPE_DATE, SORT_TYPE_SIZE, SORT_TYPE_TYPE)) {
            assertEquals(
                "sortType=$sortType ascending=true 与 ExplorerSorter 不一致",
                legacyOrder(sortType, ascending = true),
                ourOrder(sortType, ascending = true)
            )
        }
    }

    /**
     * 把两组方向的实际结果打印成断言，钉死"默认（ascending=false）时各类型分别是什么方向"。
     * 这四条是给人看的：以后谁把比较器方向"顺手改统一"，这里会立刻红。
     */
    /**
     * 把 ascending=false（生产默认值）时各排序类型的**实际方向**钉死。
     *
     * <p>这里的四个期望值全部取自真实 [ExplorerSorter] 的输出，不是手工推导——推导在这条上
     * 已经错了三次（`reversed()` 是交换参数、不是取负，方向取决于比较器原本把谁放在 o1 位）。
     * 数据：names 原始顺序 alpha,beta,gamma,delta,epsilon，size/time 随该顺序递增。
     */
    @Test
    fun `default direction per sort type is pinned against the legacy sorter`() {
        // NAME：collator.compare(o1, o2) -> 名称升序（注意 Collator 下 gamma 排在 epsilon 之后）
        assertEquals(listOf("alpha", "beta", "delta", "epsilon", "gamma"),
            legacyOrder(SORT_TYPE_NAME, ascending = false))
        // SIZE：Long.compare(o1.size, o2.size) -> 大小升序
        assertEquals(listOf("alpha", "beta", "gamma", "delta", "epsilon"),
            legacyOrder(SORT_TYPE_SIZE, ascending = false))
        // DATE：Long.compare(o2.time, o1.time) -> 时间降序
        assertEquals(listOf("epsilon", "delta", "gamma", "beta", "alpha"),
            legacyOrder(SORT_TYPE_DATE, ascending = false))
        // TYPE：o1.type.compareTo(o2.type) -> 类型字符串升序（apk < js < json < md < txt）
        assertEquals(listOf("gamma", "alpha", "delta", "epsilon", "beta"),
            legacyOrder(SORT_TYPE_TYPE, ascending = false))

        // 纯状态层必须给出与上面完全相同的四个结果
        for (sortType in listOf(SORT_TYPE_NAME, SORT_TYPE_SIZE, SORT_TYPE_DATE, SORT_TYPE_TYPE)) {
            assertEquals(
                "sortType=$sortType 的默认方向与旧排序器不一致",
                legacyOrder(sortType, ascending = false),
                ourOrder(sortType, ascending = false)
            )
        }
    }

    @Test
    fun `build with a spec matches the legacy sorter for folder and file groups separately`() {
        // 文件夹组用 NAME/descending，文件组用 SIZE/ascending —— 两组互不影响
        val folders = rows().take(3)
        val files = rows().drop(2)

        val result = ExplorerListRows.build(
            folders,
            files,
            ExplorerListRows.SortSpec(
                dirSortType = SORT_TYPE_NAME,
                dirAscending = false,
                fileSortType = SORT_TYPE_SIZE,
                fileAscending = true
            )
        )

        // 文件夹组：NAME/descending
        val legacyFolders = folders.map { FakeItem(it.name, it.type, it.size, it.lastModified) }.toMutableList()
        ExplorerSorter.sort(legacyFolders, ExplorerSorter.NAME, false)
        assertEquals(legacyFolders.map { it.getName() }, result.folders.map { it.name })

        // 文件组：SIZE/ascending
        val legacyFiles = files.map { FakeItem(it.name, it.type, it.size, it.lastModified) }.toMutableList()
        ExplorerSorter.sort(legacyFiles, ExplorerSorter.SIZE, true)
        assertEquals(legacyFiles.map { it.getName() }, result.files.map { it.name })
    }
}
