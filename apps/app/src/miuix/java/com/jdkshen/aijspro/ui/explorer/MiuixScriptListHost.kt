package com.jdkshen.aijspro.ui.explorer

import android.content.Intent
import android.content.SharedPreferences
import android.os.Environment
import android.preference.PreferenceManager
import android.view.View
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jdkshen.aijspro.Pref
import com.jdkshen.aijspro.R
import com.jdkshen.aijspro.external.fileprovider.AppFileProvider
import com.jdkshen.aijspro.model.explorer.ExplorerDirPage
import com.jdkshen.aijspro.model.explorer.ExplorerFileItem
import com.jdkshen.aijspro.model.explorer.ExplorerItem
import com.jdkshen.aijspro.model.explorer.ExplorerPage
import com.jdkshen.aijspro.model.explorer.ExplorerProjectPage
import com.jdkshen.aijspro.model.explorer.ExplorerSamplePage
import com.jdkshen.aijspro.model.explorer.Explorers
import com.jdkshen.aijspro.model.script.Scripts
import com.jdkshen.aijspro.theme.AijsMiuixTheme
import com.jdkshen.aijspro.ui.common.ScriptLoopDialog
import com.jdkshen.aijspro.ui.common.ScriptOperations
import com.jdkshen.aijspro.ui.project.BuildActivity
import com.jdkshen.aijspro.ui.viewmodel.ExplorerItemList
import com.jdkshen.aijspro.ui.viewmodel.ExplorerListRows
import com.jdkshen.aijspro.ui.viewmodel.ExplorerNavigationState
import com.stardust.app.GlobalAppContext
import com.stardust.pio.PFiles
import com.stardust.util.IntentUtil
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
class MiuixScriptListHost(private val fragment: androidx.fragment.app.Fragment) {

    private val navigation = ExplorerNavigationState<ExplorerPage>(rootPage())
    private var items by mutableStateOf<List<ExplorerItem>>(emptyList())
    private var loading by mutableStateOf(true)
    private var error by mutableStateOf<String?>(null)
    private var searchQuery by mutableStateOf<String?>(null)
    private var loadDisposable: Disposable? = null

    /** 排序设置；与旧实现共用 preferences 里的同一份 SortConfig 键。 */
    private var sortSpec by mutableStateOf(loadSortSpec())

    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

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
        navigation.back()
        reload()
    }

    /** 搜索命中后跳到该文件/目录所在目录并高亮（沿用旧语义：逐级下钻，保持返回栈有意义）。 */
    fun revealFile(path: String): Boolean {
        val target = File(path).absoluteFile
        val parent = target.parentFile ?: return false
        val rootPath = navigation.current.getPath()
        if (!target.absolutePath.startsWith(rootPath)) return false
        var current = File(rootPath)
        val segments = parent.absolutePath.removePrefix(rootPath).split('/').filter { it.isNotEmpty() }
        for (segment in segments) {
            current = File(current, segment)
            navigation.push(ExplorerDirPage(current.path, navigation.current))
        }
        reload()
        return true
    }

    fun setQuery(query: String?) {
        this.searchQuery = query
        reload()
    }

    fun dispose() {
        loadDisposable?.dispose()
    }

    fun reload() {
        loadDisposable?.dispose()
        loading = true
        error = null
        val keyword = searchQuery
        val page = navigation.current
        loadDisposable = Explorers.workspace().fetchChildren(page)
            .subscribeOn(Schedulers.io())
            .map { children ->
                children.toList().filter { keyword == null || it.getName().contains(keyword) }
            }
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ list ->
                items = list
                loading = false
            }, { throwable ->
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

    @Composable
    private fun FileListScreen() {
        val folders = items.filterIsInstance<ExplorerPage>().map { Row(it) }
        val files = items.filterNot { it is ExplorerPage }.map { Row(it) }
        val rows = ExplorerListRows.build(
            folders = folders,
            files = files,
            spec = sortSpec,
            dirsCollapsed = false,
            filesCollapsed = false
        )
        val isProjectPage = navigation.current is ExplorerProjectPage

        Column(Modifier.fillMaxSize()) {
            CategoryHeader(
                title = if (isProjectPage) navigation.current.getName() else breadcrumbOf(navigation.current),
                showBack = !isProjectPage,
                canGoBack = canGoBack(),
                showProjectCompass = isProjectPage,
                ascending = sortSpec.dirAscending,
                onBack = { goBack() },
                onProjectMenu = { showProjectActions() }
            )
            when {
                loading -> StatusText("加载中…")
                error != null -> StatusText("读取失败：$error")
                rows.count == 0 -> StatusText("空目录")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(rows.folders, key = { it.item.getPath() }) { row ->
                        FolderRow(row.item as ExplorerPage)
                    }
                    items(rows.files, key = { it.item.getPath() }) { row ->
                        FileRow(row.item)
                    }
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
            // title_container：占满剩余宽度，旧实现在项目页点击它会打开项目菜单
            Box(
                Modifier.weight(1f)
                    .height(48.dp)
                    .clickable(enabled = showProjectCompass) { onProjectMenu() }
                    .padding(start = 8.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    title,
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (showBack) {
                HeaderIcon(
                    resId = R.drawable.ic_dir_up,
                    tint = Color(0xFF828384),
                    enabled = canGoBack,
                    onClick = onBack
                )
            }
            if (showProjectCompass) {
                HeaderIcon(
                    resId = R.drawable.ic_project_compass_24dp,
                    tint = Color(0xFF828384),
                    enabled = true,
                    onClick = onProjectMenu
                )
            }
            HeaderIcon(
                resId = if (ascending) R.drawable.ic_sort_ascending_24dp
                else R.drawable.ic_sort_descending_24dp,
                tint = Color(0xFF828384),
                enabled = true,
                onClick = { toggleSortOrder() }
            )
            HeaderIcon(
                resId = R.drawable.ic_filter_funnel_24dp,
                tint = Color(0xFF828384),
                enabled = true,
                onClick = { showSortOptions() }
            )
        }
    }

    /** 分类头按钮：48dp 命中区、12dp 内边距；禁用时 alpha = 0.38（与旧实现一致）。 */
    @Composable
    private fun HeaderIcon(resId: Int, tint: Color, enabled: Boolean, onClick: () -> Unit) {
        Box(
            Modifier.size(48.dp)
                .alpha(if (enabled) 1f else 0.38f)
                .clickable(enabled = enabled) { onClick() },
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
                    .combinedClickable(onClick = onBodyClick, onLongClick = onOpen)
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
                    RowIcon(R.drawable.ic_run_gray, onRun)
                }
                RowIcon(R.drawable.ic_more_vert_black_24dp, onMore)
            }
            Box(
                Modifier.fillMaxWidth()
                    .padding(start = 74.dp)
                    .height(1.dp)
                    .background(colorResource(R.color.m3_list_divider))
            )
        }
    }

    /** 行内动作按钮：48dp 命中区、12dp 内边距。 */
    @Composable
    private fun RowIcon(resId: Int, onClick: () -> Unit) {
        Box(
            Modifier.size(48.dp).clickable { onClick() },
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
        if (page is ExplorerPage) {
            navigation.push(page)
            reload()
        }
    }

    private fun showProjectActions() {
        // 旧实现：项目页的分类头标题可点，打开项目操作菜单（ExplorerProjectToolbar 的动作）。
        // 注意：MiuixProjectMenuHost.show(owner: ExplorerView) 依赖旧 View 实例，Compose 模式拿不到它，
        // 所以这里暂时不接项目菜单——留待 S4 把项目工具条一并 Compose 化（已记入迁移方案的待办）。
    }

    private fun open(item: ExplorerItem) {
        val activity = fragment.activity ?: return
        if (item.isEditable) {
            Scripts.edit(activity, item.toScriptFile())
        } else {
            IntentUtil.viewFile(GlobalAppContext.get(), item.getPath(), AppFileProvider.AUTHORITY)
        }
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
        val context = fragment.context ?: return
        val operations = { ScriptOperations(context, null, navigation.current) }
        when (id) {
            R.id.rename -> operations().rename(item as ExplorerFileItem)
                .subscribe({ reload() }, { error -> toast(error) })
            R.id.delete -> {
                operations().delete(item.toScriptFile())
                reload()
            }
            R.id.timed_task -> operations().timedTask(item.toScriptFile())
            R.id.create_shortcut -> operations().createShortcut(item.toScriptFile())
            R.id.send -> Scripts.send(item.toScriptFile())
            R.id.open_by_other_apps -> Scripts.openByOtherApps(item.toScriptFile())
            R.id.run_repeatedly -> ScriptLoopDialog(context, item.toScriptFile()).show()
            R.id.action_build_apk -> context.startActivity(
                Intent(context, BuildActivity::class.java)
                    .putExtra(BuildActivity.EXTRA_SOURCE, item.getPath())
            )
            R.id.reset -> Explorers.Providers.workspace().resetSample(item.toScriptFile())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ reload() }, { error -> toast(error) })
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
            }, { error -> toast(error) })
    }

    private fun toast(error: Throwable) {
        val context = fragment.context ?: return
        Toast.makeText(context, error.message ?: error.javaClass.simpleName, Toast.LENGTH_LONG).show()
    }

    /** 面包屑，与旧 `buildBreadcrumbTitle()` 同构：`内部存储  ›  a  ›  b`。 */
    private fun breadcrumbOf(page: ExplorerItem): String {
        val storage = Environment.getExternalStorageDirectory().absolutePath
        val path = File(page.getPath()).absolutePath
        val builder = StringBuilder(GlobalAppContext.getString(R.string.text_internal_storage))
        if (path.startsWith(storage)) {
            path.removePrefix(storage).split('/').filter { it.isNotEmpty() }
                .forEach { builder.append("  ›  ").append(it) }
        } else if (File(path).name.isNotEmpty()) {
            builder.append("  ›  ").append(File(path).name)
        }
        return builder.toString()
    }

    companion object {
        private fun rootPage(): ExplorerPage {
            val storageDirectory = Environment.getExternalStorageDirectory()
            val storageRoot = ExplorerDirPage.createRoot(storageDirectory.path)
            return ExplorerDirPage(Pref.getScriptDirPath(), storageRoot)
        }
    }
}
