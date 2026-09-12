package com.jdkshen.aijspro.ui.explorer

import android.content.Intent
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jdkshen.aijspro.Pref
import com.jdkshen.aijspro.R
import com.jdkshen.aijspro.model.explorer.ExplorerDirPage
import com.jdkshen.aijspro.model.explorer.ExplorerFileItem
import com.jdkshen.aijspro.model.explorer.ExplorerItem
import com.jdkshen.aijspro.model.explorer.ExplorerPage
import com.jdkshen.aijspro.model.explorer.ExplorerSamplePage
import com.jdkshen.aijspro.model.explorer.Explorers
import com.jdkshen.aijspro.model.script.Scripts
import com.jdkshen.aijspro.ui.common.ScriptLoopDialog
import com.jdkshen.aijspro.ui.common.ScriptOperations
import com.jdkshen.aijspro.ui.project.BuildActivity
import com.jdkshen.aijspro.theme.AijsMiuixTheme
import com.jdkshen.aijspro.ui.viewmodel.ExplorerItemList
import com.jdkshen.aijspro.ui.viewmodel.ExplorerNavigationState
import com.stardust.app.GlobalAppContext
import com.stardust.util.IntentUtil
import com.jdkshen.aijspro.external.fileprovider.AppFileProvider
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
 * 文件列表（S2：Compose 版浏览）。
 *
 * 与旧 [ExplorerView] 共用同一套模型与排序（[Explorers.workspace] 拉子项、[ExplorerItemList] 排序），
 * 导航语义复用 [ExplorerNavigationState]。
 *
 * 本片只做「浏览 + 打开」：进入目录、返回上级、点文件按可编辑性走编辑器或系统查看器。
 * 多选、重命名/删除/打包、排序菜单、新建、项目工具条仍在旧实现里，S3 再补。
 */
class MiuixScriptListHost(private val fragment: androidx.fragment.app.Fragment) {

    private val navigation = ExplorerNavigationState<ExplorerPage>(rootPage())
    private var items by mutableStateOf<List<ExplorerItem>>(emptyList())
    private var loading by mutableStateOf(true)
    private var error by mutableStateOf<String?>(null)
    private var searchQuery by mutableStateOf<String?>(null)
    private var loadDisposable: Disposable? = null

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

    /** 搜索命中后跳到该文件/目录所在目录并高亮（S3 补高亮，这里先完成跳转）。 */
    fun revealFile(path: String): Boolean {
        val target = File(path).absoluteFile
        val parent = target.parentFile ?: return false
        val root = navigation.current
        val rootPath = root.getPath()
        if (!target.absolutePath.startsWith(rootPath)) return false
        // 从根逐级下钻到目标目录，保持面包屑与返回栈有意义
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
                // 目录在前、同组按名称；排序设置（SortConfig）留给 S3 接入
                children.toList()
                    .filter { keyword == null || it.getName().contains(keyword) }
                    .sortedWith(compareBy({ it !is ExplorerPage }, { it.getName().lowercase(Locale.ROOT) }))
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

    @Composable
    private fun FileListScreen() {
        Column(Modifier.fillMaxSize()) {
            Text(
                breadcrumbOf(navigation.current),
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
            )
            when {
                loading -> Text(
                    "加载中…",
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                error != null -> Text(
                    "读取失败：$error",
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                items.isEmpty() -> Text(
                    "空目录",
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(items, key = { it.getPath() }, span = { item ->
                        if (item is ExplorerPage) GridItemSpan(1) else GridItemSpan(maxLineSpan)
                    }) { item ->
                        when (item) {
                            is ExplorerPage -> DirectoryCell(item)
                            else -> FileRow(item)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun DirectoryCell(page: ExplorerPage) {
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MiuixTheme.colorScheme.surface)
                .itemGestures(page)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                Modifier.size(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Color(0xFFF0A03C)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    ExplorerViewHelper.getDisplayName(page).take(1).uppercase(Locale.ROOT),
                    fontSize = 15.sp,
                    color = Color.White
                )
            }
            Text(
                ExplorerViewHelper.getDisplayName(page),
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "文件夹\n修改于 " + timestampFormat.format(Date(File(page.getPath()).lastModified())),
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    @Composable
    private fun FileRow(item: ExplorerItem) {
        val iconRes = ExplorerViewHelper.getFileIconRes(item)
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MiuixTheme.colorScheme.surface)
                .itemGestures(item)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(ExplorerViewHelper.getIconColor(item))),
                contentAlignment = Alignment.Center
            ) {
                if (iconRes != 0) {
                    Image(painterResource(iconRes), contentDescription = null,
                        modifier = Modifier.size(20.dp))
                } else {
                    Text(
                        ExplorerViewHelper.getIconText(item),
                        fontSize = 14.sp,
                        color = Color.White
                    )
                }
            }
            Column(Modifier.padding(start = 12.dp)) {
                Text(
                    ExplorerViewHelper.getDisplayName(item),
                    fontSize = 15.sp,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    sizeText(item) + "\n修改于 " +
                            timestampFormat.format(Date(File(item.getPath()).lastModified())),
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }

    private fun sizeText(item: ExplorerItem): String {
        val length = File(item.getPath()).length()
        return when {
            length < 1024 -> "$length B"
            length < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", length / 1024.0)
            else -> String.format(Locale.US, "%.1f MB", length / (1024.0 * 1024.0))
        }
    }

    private fun open(item: ExplorerItem) {
        val activity = fragment.activity ?: return
        if (item.isEditable) {
            Scripts.edit(activity, item.toScriptFile())
        } else {
            IntentUtil.viewFile(GlobalAppContext.get(), item.getPath(), AppFileProvider.AUTHORITY)
        }
    }

    // ---- 长按菜单（S3 第一批：重命名 / 删除 / 发送 / 打开方式 / 定时任务 / 快捷方式 / 打包 / 循环运行 / 重置样例）----

    @OptIn(ExperimentalFoundationApi::class)
    private fun Modifier.itemGestures(item: ExplorerItem): Modifier = combinedClickable(
        onClick = {
            if (item is ExplorerPage) {
                navigation.push(item)
                reload()
            } else {
                open(item)
            }
        },
        onLongClick = { showItemMenu(item) }
    )

    private fun showItemMenu(item: ExplorerItem) {
        val activity = fragment.activity ?: return
        val ids = ArrayList<Int>()
        val labels = ArrayList<Int>()
        fun add(id: Int, label: Int) {
            ids.add(id)
            labels.add(label)
        }
        if (item !is ExplorerPage && ExplorerViewHelper.isJavaScript(item)) {
            add(R.id.run_repeatedly, R.string.text_run_repeatedly)
            add(R.id.timed_task, R.string.text_timed_task)
            add(R.id.create_shortcut, R.string.text_send_shortcut)
            add(R.id.action_build_apk, R.string.text_build_apk)
        }
        if (item.canRename()) add(R.id.rename, R.string.text_rename)
        if (item.canDelete()) add(R.id.delete, R.string.text_delete)
        if (item !is ExplorerPage) {
            add(R.id.send, R.string.text_send)
            add(R.id.open_by_other_apps, R.string.text_open_by_other_apps)
        }
        if (item is ExplorerSamplePage) add(R.id.reset, R.string.text_reset_to_initial_content)
        if (ids.isEmpty()) return
        MiuixExplorerMenuHost.showFor(activity, ids.toIntArray(),
            labels.map { fragment.getString(it) }.toTypedArray(),
            ExplorerViewHelper.getDisplayName(item)) { id -> performAction(item, id) }
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
        }
    }

    private fun toast(error: Throwable) {
        val context = fragment.context ?: return
        Toast.makeText(context, error.message ?: error.javaClass.simpleName, Toast.LENGTH_LONG).show()
    }

    private fun breadcrumbOf(page: ExplorerItem): String {
        val storage = android.os.Environment.getExternalStorageDirectory().absolutePath
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
            val storageDirectory = android.os.Environment.getExternalStorageDirectory()
            val storageRoot = ExplorerDirPage.createRoot(storageDirectory.path)
            return ExplorerDirPage(Pref.getScriptDirPath(), storageRoot)
        }
    }
}
