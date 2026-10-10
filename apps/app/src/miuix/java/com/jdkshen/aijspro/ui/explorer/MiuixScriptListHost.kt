package com.jdkshen.aijspro.ui.explorer

import android.content.Intent
import android.content.SharedPreferences
import android.os.Environment
import android.os.Bundle
import android.preference.PreferenceManager
import android.view.View
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jdkshen.aijspro.Pref
import com.jdkshen.aijspro.theme.MiuixListDivider
import com.jdkshen.aijspro.R
import com.jdkshen.aijspro.external.fileprovider.AppFileProvider
import com.jdkshen.aijspro.model.explorer.ExplorerDirPage
import com.jdkshen.aijspro.model.explorer.ExplorerChangeEvent
import com.jdkshen.aijspro.model.explorer.ExplorerFileItem
import com.jdkshen.aijspro.model.explorer.ExplorerItem
import com.jdkshen.aijspro.model.explorer.ExplorerPage
import com.jdkshen.aijspro.model.explorer.ExplorerPageFactory
import com.jdkshen.aijspro.model.explorer.ExplorerProjectPage
import com.jdkshen.aijspro.model.explorer.ExplorerSamplePage
import com.jdkshen.aijspro.model.explorer.Explorers
import com.jdkshen.aijspro.model.script.Scripts
import com.jdkshen.aijspro.theme.AijsMiuixTheme
import com.jdkshen.aijspro.ui.common.ScriptLoopDialog
import com.jdkshen.aijspro.ui.common.ScriptOperations
import com.jdkshen.aijspro.ui.project.BuildActivity
import com.jdkshen.aijspro.ui.project.ProjectConfigActivity
import com.jdkshen.aijspro.ui.viewmodel.ExplorerItemList
import com.jdkshen.aijspro.ui.viewmodel.ExplorerListRows
import com.jdkshen.aijspro.ui.viewmodel.ExplorerNavigationState
import com.jdkshen.aijspro.ui.viewmodel.ExplorerRevealPlan
import com.jdkshen.aijspro.ui.viewmodel.ExplorerScrollPosition
import com.stardust.app.GlobalAppContext
import com.stardust.pio.PFiles
import com.stardust.util.IntentUtil
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import kotlinx.coroutines.delay

/**
 * 文件列表（Compose 版），**显示与 [ExplorerView] 逐项对齐**。
 *
 * <p>设计约束：这是「UI 统一」而不是「重新设计文件列表」——用户不应该看出两者差别。
 * 因此本文件的所有尺寸、字号、颜色、图标、间距都直接照抄旧实现，来源是：
 * - `res/layout/script_file_list_category.xml`（分类头 48dp + 4 个 48dp 按钮）
 * - `res/layout/script_file_list_directory.xml`（文件夹行：44dp 圆底图标 / 名称 16sp / 描述 12sp）
 * - `res/layout/script_file_list_file.xml`（文件行：44dp 圆底 / 名称 15sp / 描述 11sp / run + more）
 * - `ExplorerView` 的 `ExplorerPageViewHolder` / `ExplorerItemViewHolder` / `CategoryViewHolder`
 * - 行结构：平表（文件夹在前、文件在后）+ 列表上方**一个固定的**分类头，与旧 adapter 一致
 *
 * <p>排序/分组/折叠的语义不在这里实现，而在可单测的 [ExplorerListRows]（已与真实
 * [com.jdkshen.aijspro.model.explorer.ExplorerSorter] 做过对照测试）。
 */
class MiuixScriptListHost @JvmOverloads constructor(
    private val fragment: androidx.fragment.app.Fragment,
    savedState: Bundle? = null
) {

    private val navigation = ExplorerNavigationState<ExplorerPage>(rootPage())
    private var items by mutableStateOf<List<ExplorerItem>>(emptyList())
    private var loading by mutableStateOf(true)
    private var error by mutableStateOf<String?>(null)
    private var searchQuery by mutableStateOf<String?>(null)
    private var loadDisposable: Disposable? = null
    private val operations = CompositeDisposable()
    private var disposed = false
    private var loadGeneration = 0
    private var currentPath by mutableStateOf(navigation.current.getPath())
    private var loadedPath: String? = null
    // Owned by the host, not remember() inside a loading branch. Each directory has its own viewport.
    private val listStates = mutableMapOf<String, LazyListState>()
    private val restoredPositions = mutableMapOf<String, ExplorerScrollPosition>()
    private var pendingHighlightPath by mutableStateOf<String?>(null)
    private var highlightedPath by mutableStateOf<String?>(null)

    /** 排序设置；与旧实现共用 preferences 里的同一份 SortConfig 键。 */
    private var sortSpec by mutableStateOf(loadSortSpec())

    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    init {
        restoreState(savedState)
        Explorers.workspace().registerChangeListener(this)
    }

    fun getCurrentPage(): ExplorerPage = navigation.current

    fun saveState(): Bundle = Bundle().apply {
        putStringArrayList("pages", ArrayList((navigation.historySnapshot() + navigation.current).map { it.getPath() }))
        putString("query", searchQuery)
        putString("highlight", pendingHighlightPath)
        val positions = Bundle()
        restoredPositions.forEach { (path, position) ->
            positions.putBundle(path, positionBundle(position))
        }
        listStates.forEach { (path, state) ->
            val anchor = if (path == loadedPath) rowPaths().getOrNull(state.firstVisibleItemIndex) else null
            positions.putBundle(path, positionBundle(ExplorerScrollPosition(
                state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset, anchor
            )))
        }
        putBundle("positions", positions)
    }

    private fun positionBundle(position: ExplorerScrollPosition) = Bundle().apply {
        putInt("index", position.index)
        putInt("offset", position.offset)
        putString("anchor", position.anchorPath)
    }

    private fun restoreState(state: Bundle?) {
        state ?: return
        val pages = state.getStringArrayList("pages")
        if (!pages.isNullOrEmpty()) {
            navigation.reset(pageForPath(pages.first(), null))
            pages.drop(1).forEach { navigation.push(pageForPath(it, navigation.current)) }
        }
        currentPath = navigation.current.getPath()
        searchQuery = state.getString("query")
        pendingHighlightPath = state.getString("highlight")
        state.getBundle("positions")?.let { positions ->
            positions.keySet().forEach { path ->
                positions.getBundle(path)?.let {
                    restoredPositions[path] = ExplorerScrollPosition(
                        it.getInt("index").coerceAtLeast(0), it.getInt("offset").coerceAtLeast(0), it.getString("anchor")
                    )
                }
            }
        }
    }

    private fun rowPaths(): List<String> {
        val rows = sortedRows()
        return (rows.folders + rows.files).map { it.item.getPath() }
    }

    private fun pageForPath(path: String, parent: ExplorerPage?): ExplorerPage =
        ExplorerPageFactory.fromPath(fragment.requireContext(), path, parent)

    private fun listState(path: String): LazyListState = listStates.getOrPut(path) {
        val position = restoredPositions.remove(path)
        LazyListState(position?.indexIn(rowPaths()) ?: 0, position?.offset ?: 0)
    }

    fun createView(): View = ComposeView(fragment.requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            AijsMiuixTheme {
                FileListScreen()
            }
        }
    }

    fun canGoBack(): Boolean = navigation.canGoBack()

    fun goBack() {
        if (!canGoBack()) return
        pendingHighlightPath = null
        highlightedPath = null
        navigation.back()
        reload()
    }

    /** 搜索命中后跳到该文件/目录所在目录并高亮（沿用旧语义：逐级下钻，保持返回栈有意义）。 */
    fun revealFile(path: String): Boolean {
        if (disposed) return false
        // Match the legacy explorer's storage root, not the directory currently being browsed.
        val target = File(path)
        val plan = ExplorerRevealPlan.create(Environment.getExternalStorageDirectory(), target)
            ?: ExplorerRevealPlan.create(File(fragment.requireContext().filesDir, "sample"), target)
            ?: return false
        val currentDirectory = runCatching { File(navigation.current.getPath()).canonicalPath }.getOrNull()
        if (currentDirectory != plan.directories.last()) {
            navigation.reset(pageForPath(plan.directories.first(), null))
            plan.directories.drop(1).forEach {
                navigation.push(pageForPath(it, navigation.current))
            }
        }
        searchQuery = null
        pendingHighlightPath = plan.targetPath
        highlightedPath = null
        reload()
        return true
    }

    fun setQuery(query: String?) {
        this.searchQuery = query
        reload()
    }

    /**
     * 直接进入某个目录（资源页「打开文件夹」用）：与 [revealFile] 一样逐级下钻保证返回栈有意义，
     * 但把**目标目录本身**也压栈，进不去就等于没打开。
     */
    fun openDirectory(path: String): Boolean {
        if (disposed) return false
        val target = File(path)
        if (!target.isDirectory) return false
        val plan = ExplorerRevealPlan.create(Environment.getExternalStorageDirectory(), target)
            ?: ExplorerRevealPlan.create(File(fragment.requireContext().filesDir, "sample"), target)
            ?: return false
        val currentDirectory = runCatching { File(navigation.current.getPath()).canonicalPath }.getOrNull()
        if (currentDirectory != plan.targetPath) {
            navigation.reset(pageForPath(plan.directories.first(), null))
            plan.directories.drop(1).forEach {
                navigation.push(pageForPath(it, navigation.current))
            }
            navigation.push(pageForPath(plan.targetPath, navigation.current))
        }
        searchQuery = null
        pendingHighlightPath = null
        highlightedPath = null
        reload()
        return true
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        loadGeneration++
        loadDisposable?.dispose()
        operations.dispose()
        Explorers.workspace().unregisterChangeListener(this)
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onExplorerChange(event: ExplorerChangeEvent) {
        if (disposed) return
        if (event.getAction() == ExplorerChangeEvent.ALL ||
            event.getPage()?.getPath() == navigation.current.getPath() ||
            event.getItem()?.getPath() == navigation.current.getPath()) {
            reload()
        }
    }

    fun reload() {
        if (disposed) return
        val generation = ++loadGeneration
        loadDisposable?.dispose()
        loading = true
        error = null
        val keyword = searchQuery
        val page = navigation.current
        currentPath = page.getPath()
        loadDisposable = Explorers.workspace().fetchChildren(page)
            .subscribeOn(Schedulers.io())
            .map { children ->
                children.toList().filter { keyword == null || it.getName().contains(keyword) }
            }
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ list ->
                if (disposed || generation != loadGeneration) return@subscribe
                items = list
                loadedPath = page.getPath()
                loading = false
            }, { throwable ->
                if (disposed || generation != loadGeneration) return@subscribe
                error = throwable.message ?: throwable.javaClass.simpleName
                loading = false
            })
    }

    // ---- 排序设置：与旧实现共用同一份 preferences ----------------

    private fun prefs(): SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(fragment.requireContext())

    private fun loadSortSpec(): ExplorerListRows.SortSpec {
        val config = ExplorerItemList.SortConfig.from(prefs())
        return ExplorerListRows.SortSpec(
            dirSortType = config.getDirSortType(),
            dirAscending = config.isDirSortedAscending(),
            fileSortType = config.getFileSortType(),
            fileAscending = config.isFileSortedAscending()
        )
    }

    private fun persistSortSpec(spec: ExplorerListRows.SortSpec) {
        val config = ExplorerItemList.SortConfig()
        config.setDirSortType(spec.dirSortType)
        config.setDirSortedAscending(spec.dirAscending)
        config.setFileSortType(spec.fileSortType)
        config.setFileSortedAscending(spec.fileAscending)
        config.saveInto(prefs())
        sortSpec = spec
    }

    /** 旧实现的 `changeSortOrder()`：同时翻转两组方向并落盘。 */
    private fun toggleSortOrder() {
        val ascending = !sortSpec.dirAscending
        persistSortSpec(sortSpec.copy(dirAscending = ascending, fileAscending = ascending))
    }

    /** 旧实现的 `showSortOptions()`：名称 / 时间 / 类型 / 大小。 */
    private fun showSortOptions() {
        val activity = fragment.activity ?: return
        val ids = intArrayOf(
            R.id.action_sort_by_name,
            R.id.action_sort_by_date,
            R.id.action_sort_by_type,
            R.id.action_sort_by_size
        )
        val labels = arrayOf(
            fragment.getString(R.string.text_name),
            fragment.getString(R.string.text_time),
            fragment.getString(R.string.text_type),
            fragment.getString(R.string.text_size)
        )
        MiuixExplorerMenuHost.showFor(activity, ids, labels, fragment.getString(R.string.text_sort)) { id ->
            val sortType = when (id) {
                R.id.action_sort_by_name -> ExplorerListRows.SORT_TYPE_NAME
                R.id.action_sort_by_date -> ExplorerListRows.SORT_TYPE_DATE
                R.id.action_sort_by_type -> ExplorerListRows.SORT_TYPE_TYPE
                R.id.action_sort_by_size -> ExplorerListRows.SORT_TYPE_SIZE
                else -> return@showFor
            }
            // 旧实现 sortAll(sortType)：两组一起换排序方式
            persistSortSpec(sortSpec.copy(dirSortType = sortType, fileSortType = sortType))
        }
    }

    // ---- 界面 ----------------

    /** 一行数据：把 Android 模型适配成可排序的 [ExplorerListRows.Item]。 */
    private class Row(val item: ExplorerItem) : ExplorerListRows.Item {
        override val name: String get() = item.getName()
        override val type: String get() = item.getType()
        override val size: Long get() = item.getSize()
        override val lastModified: Long get() = item.lastModified()
    }

    private fun sortedRows(): ExplorerListRows.Result<Row> {
        val folders = items.filterIsInstance<ExplorerPage>().map { Row(it) }
        val files = items.filterNot { it is ExplorerPage }.map { Row(it) }
        return ExplorerListRows.build(
            folders = folders,
            files = files,
            spec = sortSpec,
            dirsCollapsed = false,
            filesCollapsed = false
        )
    }

    @Composable
    private fun FileListScreen() {
        val path = currentPath
        val rows = sortedRows()
        val isProjectPage = navigation.current is ExplorerProjectPage

        Column(Modifier.fillMaxSize()) {
            CategoryHeader(
                title = breadcrumbOf(navigation.current),
                showBack = !isProjectPage,
                canGoBack = canGoBack(),
                showProjectCompass = isProjectPage,
                ascending = sortSpec.dirAscending,
                onBack = { goBack() },
                onProjectMenu = { showProjectActions() }
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // Keep the current list composed while refreshing; don't measure a new page against old/empty rows.
                if (loadedPath == path && rows.count > 0) key(path) {
                    val state = listState(path)
                    val paths = (rows.folders + rows.files).map { it.item.getPath() }
                    val target = pendingHighlightPath
                    LaunchedEffect(path, target, paths) {
                        val index = target?.let { wanted -> paths.indexOfFirst {
                            try { File(it).canonicalPath == wanted } catch (_: Exception) { false }
                        } } ?: -1
                        if (index >= 0) {
                            state.scrollToItem(index)
                            pendingHighlightPath = null
                            highlightedPath = paths[index]
                        }
                    }
                    LaunchedEffect(highlightedPath) {
                        if (highlightedPath != null) {
                            delay(1800)
                            highlightedPath = null
                        }
                    }
                    LazyColumn(Modifier.fillMaxSize(), state = state) {
                        items(rows.folders, key = { it.item.getPath() }) { row ->
                            FolderRow(row.item as ExplorerPage)
                        }
                        items(rows.files, key = { it.item.getPath() }) { row ->
                            FileRow(row.item)
                        }
                    }
                }
                when {
                    error != null -> StatusText("读取失败：$error")
                    loading -> StatusText("加载中…")
                    rows.count == 0 -> StatusText("空目录")
                }
            }
        }
    }

    /**
     * 分类头，对应 `script_file_list_category.xml`：
     * 48dp 高、`item_background` 底、标题 14sp；右侧 4 个 48dp 按钮（返回 / 项目 / 排序方向 / 排序方式）。
     */
    @Composable
    private fun CategoryHeader(
        title: String,
        showBack: Boolean,
        canGoBack: Boolean,
        showProjectCompass: Boolean,
        ascending: Boolean,
        onBack: () -> Unit,
        onProjectMenu: () -> Unit
    ) {
        Row(
            Modifier.fillMaxWidth()
                .height(48.dp)
                .background(colorResource(R.color.item_background)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val pathScroll = rememberScrollState()
            LaunchedEffect(currentPath, pathScroll.maxValue) {
                pathScroll.scrollTo(pathScroll.maxValue)
            }
            Row(
                Modifier.weight(1f).height(48.dp).horizontalScroll(pathScroll)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                breadcrumbSegments(navigation.current).forEachIndexed { index, segment ->
                    if (index > 0) Text("  ›  ", fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onBackgroundVariant)
                    Text(segment.first, fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                        maxLines = 1,
                        modifier = Modifier.clickable { openDirectory(segment.second) }
                            .padding(vertical = 12.dp))
                }
            }
            if (showBack) {
                HeaderIcon(
                    resId = R.drawable.ic_dir_up,
                    label = "返回上级目录",
                    tint = Color(0xFF828384),
                    enabled = canGoBack,
                    onClick = onBack
                )
            }
            if (showProjectCompass) {
                HeaderIcon(
                    resId = R.drawable.ic_project_compass_24dp,
                    label = "项目操作",
                    tint = Color(0xFF828384),
                    enabled = true,
                    onClick = onProjectMenu
                )
            }
            HeaderIcon(
                resId = if (ascending) R.drawable.ic_sort_ascending_24dp
                else R.drawable.ic_sort_descending_24dp,
                label = "切换排序方向",
                tint = Color(0xFF828384),
                enabled = true,
                onClick = { toggleSortOrder() }
            )
            HeaderIcon(
                resId = R.drawable.ic_filter_funnel_24dp,
                label = "排序方式",
                tint = Color(0xFF828384),
                enabled = true,
                onClick = { showSortOptions() }
            )
        }
    }

    /** 分类头按钮：48dp 命中区、12dp 内边距；禁用时 alpha = 0.38（与旧实现一致）。 */
    @Composable
    private fun HeaderIcon(resId: Int, label: String, tint: Color, enabled: Boolean, onClick: () -> Unit) {
        Box(
            Modifier.size(48.dp)
                .alpha(if (enabled) 1f else 0.38f)
                .semantics { contentDescription = label }
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = label) { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Image(
                painterResource(resId),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(tint)
            )
        }
    }

    /**
     * 文件夹行，对应 `script_file_list_directory.xml`：整行、最小 60dp、左侧 44dp 圆底图标、
     * 名称 16sp、描述 12sp（`text_directory_modified`）、右侧 more。
     */
    @Composable
    private fun FolderRow(page: ExplorerPage) {
        val isProject = page is ExplorerProjectPage
        ItemRow(
            path = page.getPath(),
            // 旧实现用 circle_folder / circle_project 作图标底（固定色，不随主题变化），
            // 图标本身则叠在上面：文件夹/项目图标统一 tint 成 colorOnPrimary（白色系）。
            iconBackground = colorResource(
                if (isProject) R.color.project_icon_background else R.color.folder_icon_background
            ),
            iconRes = if (isProject) R.drawable.ic_project_compass_24dp
            else R.drawable.ic_folder_outline_24dp,
            iconTint = Color.White,
            initialText = "",
            name = ExplorerViewHelper.getDisplayName(page).toString(),
            nameSize = 16,
            description = fragment.getString(
                R.string.text_directory_modified,
                timestampFormat.format(Date(page.lastModified()))
            ),
            descriptionSize = 12,
            onMore = { showItemMenu(page) },
            runVisible = false,
            onRun = {},
            onOpen = { showItemMenu(page) },
            onBodyClick = { enterPage(page) }
        )
    }

    /**
     * 文件行，对应 `script_file_list_file.xml`：左侧 44dp 圆底（有专属图标则显示图标、否则首字母），
     * 名称 15sp、描述 11sp（`text_file_modified`）、右侧 run（仅可执行时可见）+ more。
     */
    @Composable
    private fun FileRow(item: ExplorerItem) {
        val iconRes = ExplorerViewHelper.getFileIconRes(item)
        ItemRow(
            path = item.getPath(),
            // 旧实现：circle_light_green 固定 #5cab7d 椭圆底；有专属图标时图标不 tint、
            // 隐藏首字母，否则显示 22sp 白色首字母。
            iconBackground = colorResource(R.color.file_icon_background),
            iconRes = iconRes,
            iconTint = null,
            initialText = ExplorerViewHelper.getIconText(item),
            name = ExplorerViewHelper.getDisplayName(item).toString(),
            nameSize = 15,
            description = fragment.getString(
                R.string.text_file_modified,
                PFiles.getHumanReadableSize(item.getSize()),
                timestampFormat.format(Date(item.lastModified()))
            ),
            descriptionSize = 11,
            onMore = { showItemMenu(item) },
            runVisible = item.isExecutable(),
            onRun = { Scripts.run(item.toScriptFile()) },
            onOpen = { showItemMenu(item) },
            onBodyClick = { open(item) }
        )
    }

    /**
     * 行骨架：两行内容 + 右侧动作按钮 + 底部 1px 分隔线（缩进 74dp）。
     * 参数化的只有尺寸/字号/图标与回调，布局本身两种行完全一致。
     */
    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun ItemRow(
        path: String,
        iconBackground: Color,
        iconRes: Int,
        iconTint: Color?,
        initialText: String,
        name: String,
        nameSize: Int,
        description: String,
        descriptionSize: Int,
        runVisible: Boolean,
        onMore: () -> Unit,
        onRun: () -> Unit,
        onOpen: () -> Unit,
        onBodyClick: () -> Unit
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth()
                    .background(if (highlightedPath == path) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
                    .semantics { selected = highlightedPath == path }
                    .combinedClickable(onClick = onBodyClick, onClickLabel = "打开 $name",
                        onLongClick = onOpen, onLongClickLabel = "更多操作：$name")
                    .heightIn(min = 60.dp)
                    .padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(44.dp)
                        .clip(CircleShape)
                        .background(iconBackground),
                    contentAlignment = Alignment.Center
                ) {
                    if (iconRes != 0) {
                        Image(
                            painterResource(iconRes),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            // 旧实现里文件夹/项目图标用 app:tint="?attr/colorOnPrimary"（白色系）；
                            // 文件的专属图标没有 tint，保持原色。
                            colorFilter = iconTint?.let { ColorFilter.tint(it) }
                        )
                    } else {
                        Text(initialText, fontSize = 22.sp, color = Color.White)
                    }
                }
                Column(
                    Modifier.weight(1f)
                        .padding(start = 14.dp, top = 5.dp, bottom = 5.dp)
                ) {
                    Text(
                        name,
                        fontSize = nameSize.sp,
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        description,
                        fontSize = descriptionSize.sp,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                if (runVisible) {
                    RowIcon(R.drawable.ic_run_gray, "运行 $name", onRun)
                }
                RowIcon(R.drawable.ic_more_vert_black_24dp, "更多操作：$name", onMore)
            }
            MiuixListDivider(indent = 74.dp)
        }
    }

    /** 行内动作按钮：48dp 命中区、12dp 内边距。 */
    @Composable
    private fun RowIcon(resId: Int, label: String, onClick: () -> Unit) {
        Box(
            Modifier.size(48.dp).semantics { contentDescription = label }
                .clickable(role = Role.Button, onClickLabel = label) { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Image(
                painterResource(resId),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceSecondary)
            )
        }
    }

    @Composable
    private fun StatusText(text: String) {
        Text(
            text,
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }

    // ---- 行为 ----------------

    private fun enterPage(page: ExplorerPage) {
        pendingHighlightPath = null
        highlightedPath = null
        navigation.push(page)
        reload()
    }

    private fun showProjectActions() {
        if (disposed) return
        val activity = fragment.activity ?: return
        val project = navigation.current as? ExplorerProjectPage ?: return
        MiuixProjectMenuHost.showFor(activity,
            onRun = {
                runCatching {
                    com.stardust.autojs.project.ProjectLauncher(project.getPath())
                        .launch(com.jdkshen.aijspro.autojs.AutoJs.getInstance().scriptEngineService)
                }.onFailure { toast(it) }
            },
            onBuild = {
                activity.startActivity(Intent(activity, BuildActivity::class.java)
                    .putExtra(BuildActivity.EXTRA_SOURCE, project.getPath()))
            },
            onSync = {
                Explorers.workspace().notifyChildrenChanged(project)
                Toast.makeText(activity, "项目已同步", Toast.LENGTH_SHORT).show()
            },
            onEdit = {
                activity.startActivity(Intent(activity, ProjectConfigActivity::class.java)
                    .putExtra(ProjectConfigActivity.EXTRA_DIRECTORY, project.getPath()))
            },
            onShare = { Scripts.send(project.toScriptFile(), activity) }
        )
    }

    private fun open(item: ExplorerItem) {
        val activity = fragment.activity ?: return
        com.jdkshen.aijspro.ui.common.FileOpenRouter.open(activity, item)
    }

    // ---- 长按 / more 菜单（与旧 ExplorerView 的 showOptionMenu 保持同样的条件）----

    private fun showItemMenu(item: ExplorerItem) {
        val activity = fragment.activity ?: return
        val ids = ArrayList<Int>()
        val labels = ArrayList<Int>()
        fun add(id: Int, label: Int) {
            ids.add(id)
            labels.add(label)
        }
        if (item.isExecutable()) {
            add(R.id.run_repeatedly, R.string.text_run_repeatedly)
            add(R.id.timed_task, R.string.text_timed_task)
            add(R.id.create_shortcut, R.string.text_send_shortcut)
            add(R.id.action_build_apk, R.string.text_build_apk)
        }
        if (item.canRename()) add(R.id.rename, R.string.text_rename)
        if (item.canDelete()) add(R.id.delete, R.string.text_delete)
        // 示例文件夹只给「重置所有示例」，避免改名/删除把内置示例目录弄丢（旧实现的特例）
        if (item is ExplorerSamplePage) {
            MiuixExplorerMenuHost.showFor(
                activity,
                intArrayOf(R.id.reset_all),
                arrayOf(fragment.getString(R.string.text_reset_all_samples)),
                ExplorerViewHelper.getDisplayName(item).toString()
            ) { performAction(item, R.id.reset_all) }
            return
        }
        if (item is ExplorerProjectPage) add(R.id.send, R.string.text_share_project)
        if (item !is ExplorerPage) {
            add(R.id.send, R.string.text_send)
            add(R.id.open_by_other_apps, R.string.text_open_by_other_apps)
        }
        // 注意：`R.id.reset`（重置单个示例）只在 ExplorerSampleItem 上有意义，而那条分支
        // 已在上面的 ExplorerSamplePage 特例里 return——旧实现同样如此，别在这里重复添加。
        if (ids.isEmpty()) return
        MiuixExplorerMenuHost.showFor(
            activity, ids.toIntArray(),
            labels.map { fragment.getString(it) }.toTypedArray(),
            ExplorerViewHelper.getDisplayName(item).toString()
        ) { id -> performAction(item, id) }
    }

    private fun performAction(item: ExplorerItem, id: Int) {
        if (disposed) return
        val context = fragment.context ?: return
        val operations = { ScriptOperations(context, null, navigation.current) }
        when (id) {
            R.id.rename -> operations().rename(item as ExplorerFileItem)
                .subscribe({}, { error -> toast(error) }).also(this.operations::add)
            // ExplorerChangeEvent is emitted only after confirmation AND successful IO deletion.
            R.id.delete -> operations().delete(item.toScriptFile())
            R.id.timed_task -> operations().timedTask(item.toScriptFile())
            R.id.create_shortcut -> operations().createShortcut(item.toScriptFile())
            R.id.send -> fragment.activity?.let { Scripts.send(item.toScriptFile(), it) }
            R.id.open_by_other_apps -> Scripts.openByOtherApps(item.toScriptFile())
            R.id.run_repeatedly -> ScriptLoopDialog(context, item.toScriptFile()).show()
            R.id.action_build_apk -> context.startActivity(
                Intent(context, BuildActivity::class.java)
                    .putExtra(BuildActivity.EXTRA_SOURCE, item.getPath())
            )
            R.id.reset -> Explorers.Providers.workspace().resetSample(item.toScriptFile())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ reload() }, { error -> toast(error) })
                .also(this.operations::add)
            R.id.reset_all -> confirmResetAllSamples()
        }
    }

    /**
     * 重置全部示例：**必须先确认**（旧实现 `confirmResetAllSamples()` 的行为，删改级操作不能省确认）。
     * 确认后跑 `resetAllSamples()` 并用 Toast 汇报条数；旧实现用的是 Snackbar，Compose 页面里没有
     * Snackbar 宿主，故改用 Toast——文案与旧实现共用同一批字符串资源。
     */
    private fun confirmResetAllSamples() {
        val context = fragment.context ?: return
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(R.string.text_reset_all_samples)
            .setMessage(R.string.text_reset_all_samples_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.text_reset_to_initial_content) { _, _ -> runResetAllSamples() }
            .show()
    }

    private fun runResetAllSamples() {
        if (disposed) return
        val context = fragment.context ?: return
        Toast.makeText(context, R.string.text_reset_all_samples_running, Toast.LENGTH_SHORT).show()
        Explorers.Providers.workspace().resetAllSamples()
            ?.observeOn(AndroidSchedulers.mainThread())
            ?.subscribe({ count ->
                Toast.makeText(
                    context,
                    context.getString(R.string.text_reset_all_samples_done, count),
                    Toast.LENGTH_LONG
                ).show()
                reload()
            }, { error -> toast(error) })?.also(operations::add)
    }

    private fun toast(error: Throwable) {
        if (disposed) return
        val context = fragment.context ?: return
        Toast.makeText(context, error.message ?: error.javaClass.simpleName, Toast.LENGTH_LONG).show()
    }

    /** 面包屑，与旧 `buildBreadcrumbTitle()` 同构：`内部存储  ›  a  ›  b`。 */
    private fun breadcrumbSegments(page: ExplorerItem): List<Pair<String, String>> =
        ExplorerBreadcrumbs.build(Pref.getScriptDirPath(),
            Environment.getExternalStorageDirectory().path, page.getPath()).map { it.label to it.path }

    private fun breadcrumbOf(page: ExplorerItem): String =
        breadcrumbSegments(page).joinToString("  ›  ") { it.first }

    companion object {
        private fun rootPage(): ExplorerPage {
            val storageDirectory = Environment.getExternalStorageDirectory()
            val storageRoot = ExplorerDirPage.createRoot(storageDirectory.path)
            return ExplorerDirPage(Pref.getScriptDirPath(), storageRoot)
        }
    }
}
