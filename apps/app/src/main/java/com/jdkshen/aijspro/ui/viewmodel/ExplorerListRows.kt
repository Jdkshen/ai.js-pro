package com.jdkshen.aijspro.ui.viewmodel

import java.text.Collator

/**
 * 文件列表行的纯状态：把「文件夹组 + 文件组」按 [SortSpec] 排好，并按折叠位决定是否输出。
 *
 * <p>存在的理由：旧 [com.jdkshen.aijspro.ui.explorer.ExplorerView] 的排序/折叠语义混在
 * RecyclerView adapter 与 `ExplorerItemList` 里，Compose 实现（`MiuixScriptListHost`）必须
 * 与它逐位一致，否则「UI 统一」就会退化成两套不同的列表。这里把语义抽成不依赖 Android 的
 * 纯 Kotlin，用单测钉死，两边共用。
 *
 * <p>完全不依赖 Android 类型（[Item] 只要求名字/类型/大小/修改时间），因此可以在 JVM 单测里
 * 直接验证；生产侧由调用方把 `ExplorerItem` 适配成 [Item] 传入。
 *
 * <p>排序语义严格对齐 [com.jdkshen.aijspro.model.explorer.ExplorerSorter]：该类的比较器方向
 * 并不统一（NAME/SIZE/TYPE 是「反序」写法，DATE 是正序写法），再统一靠 `ascending` 开关决定是否
 * 反转。这里逐条照抄这一约定，而不是"修正"成统一写法——否则排序结果会与旧列表不一致。
 *
 * <p>按各比较器的实际写法推导，四类的方向矩阵为（ascending = 默认 false 时）：
 * NAME 名称升序、DATE 时间降序、SIZE 大小升序、TYPE 类型升序；ascending = true 时逐一取反。
 */
object ExplorerListRows {

    /** 列表里一行数据的只读视图；避免状态层依赖 Android 的 ExplorerItem。 */
    interface Item {
        /** 展示名（同时参与按名称排序）。 */
        val name: String

        /** 类型标识（参与按类型排序，对应 ExplorerItem.getType()）。 */
        val type: String

        /** 字节数（参与按大小排序，对应 ExplorerItem.getSize()）。 */
        val size: Long

        /** 修改时间毫秒（参与按时间排序，对应 ExplorerItem.lastModified()）。 */
        val lastModified: Long
    }

    /** 排序类型常量，取值与 ExplorerItemList 的 SORT_TYPE_* 一致。 */
    const val SORT_TYPE_NAME = 0x10
    const val SORT_TYPE_TYPE = 0x20
    const val SORT_TYPE_SIZE = 0x30
    const val SORT_TYPE_DATE = 0x40

    /** 排序设置；字段语义与 ExplorerItemList.SortConfig 一一对应。 */
    data class SortSpec(
        val dirSortType: Int = SORT_TYPE_NAME,
        val dirAscending: Boolean = false,
        val fileSortType: Int = SORT_TYPE_NAME,
        val fileAscending: Boolean = false
    )

    /**
     * 排好序的分组结果。
     *
     * @param folders 文件夹组（对应旧实现的 `mItemGroups`，渲染在文件之前）
     * @param files 文件组（对应旧实现的 `mItems`）
     */
    data class Result<T : Item>(
        val folders: List<T>,
        val files: List<T>
    ) {
        /** 旧实现 `ExplorerItemList.count()` 的等价物：两组之和。 */
        val count: Int get() = folders.size + files.size
    }

    /** 名称比较用 JDK Collator，与 ExplorerSorter 一致（中文按本地化规则而非码点）。 */
    private val collator: Collator = Collator.getInstance()

    /**
     * 按 [spec] 排序并按折叠位裁剪。
     *
     * @param folders 未排序的文件夹
     * @param files 未排序的文件
     * @param dirsCollapsed 折叠文件夹组时输出空列表（旧 `ExplorerPageState.dirsCollapsed`）
     * @param filesCollapsed 折叠文件组时输出空列表（旧 `ExplorerPageState.filesCollapsed`）
     */
    fun <T : Item> build(
        folders: List<T>,
        files: List<T>,
        spec: SortSpec = SortSpec(),
        dirsCollapsed: Boolean = false,
        filesCollapsed: Boolean = false
    ): Result<T> {
        val sortedFolders = if (dirsCollapsed) {
            emptyList()
        } else {
            sortWith(folders, spec.dirSortType, spec.dirAscending)
        }
        val sortedFiles = if (filesCollapsed) {
            emptyList()
        } else {
            sortWith(files, spec.fileSortType, spec.fileAscending)
        }
        return Result(sortedFolders, sortedFiles)
    }

    /**
     * 复刻 `ExplorerItemList.sort()`：按 [sortType] 取比较器，按 [ascending] 决定是否反转。
     *
     * <p>返回新列表，不改动入参。
     */
    fun <T : Item> sortWith(items: List<T>, sortType: Int, ascending: Boolean): List<T> {
        // 显式给出类型参数：从 `when` 表达式返回值反推 T 在 Kotlin 里推不出来。
        val comparator: Comparator<T> = comparatorFor<T>(sortType)
        // 旧实现：ascending 为 true 用原比较器，false 用 reversed()。
        val effective = if (ascending) comparator else comparator.reversed()
        return items.sortedWith(effective)
    }

    /**
     * 比较器表，逐条对齐 `ExplorerSorter` 的写法（注意方向）。
     *
     * @throws IllegalArgumentException 未知 sortType，与旧实现同样抛错而不是静默回退
     */
    fun <T : Item> comparatorFor(sortType: Int): Comparator<T> = when (sortType) {
        // 逐字照抄 `ExplorerSorter` 的四个比较器（包括它们方向不统一的写法），不做任何"修正"：
        // NAME/SIZE/TYPE 是反序写法，DATE 是正序写法。方向交由 sortWith 的 ascending 开关统一决定，
        // 与旧实现 `ExplorerSorter.sort(items, comparator, ascending)` 的语义完全一致。
        //
        // 正确性不靠推导保证：ExplorerSorterParityTest 直接调用真实的 ExplorerSorter 比对结果。
        SORT_TYPE_NAME -> Comparator<T> { o1, o2 -> collator.compare(o2.name, o1.name) }
        SORT_TYPE_DATE -> Comparator<T> { o1, o2 -> o1.lastModified.compareTo(o2.lastModified) }
        SORT_TYPE_SIZE -> Comparator<T> { o1, o2 -> o2.size.compareTo(o1.size) }
        SORT_TYPE_TYPE -> Comparator<T> { o1, o2 -> o2.type.compareTo(o1.type) }
        else -> throw IllegalArgumentException("unknown type $sortType")
    }

    /** 排序类型对应的菜单/文案资源名，供 UI 层做一致性断言。 */
    fun sortTypeLabelKey(sortType: Int): String = when (sortType) {
        SORT_TYPE_NAME -> "name"
        SORT_TYPE_TYPE -> "type"
        SORT_TYPE_SIZE -> "size"
        SORT_TYPE_DATE -> "date"
        else -> throw IllegalArgumentException("unknown type $sortType")
    }
}
