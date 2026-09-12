package com.jdkshen.aijspro.ui.sample

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.jdkshen.aijspro.R
import com.jdkshen.aijspro.model.sample.SampleFile
import com.jdkshen.aijspro.model.script.Scripts
import com.jdkshen.aijspro.theme.AijsMiuixTheme
import com.jdkshen.aijspro.ui.common.ScriptOperations
import com.jdkshen.aijspro.Pref
import com.jdkshen.aijspro.ui.editor.ProCodeEditorActivity
import com.jdkshen.aijspro.ui.main.MainPageSearchHandler
import com.jdkshen.aijspro.ui.main.ViewPagerFragment
import com.jdkshen.aijspro.ui.project.BuildActivity
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Auto.js Pro-like sample browser backed by the existing bundled assets and operations. */
class MiuixSampleFragment : ViewPagerFragment(-1), MainPageSearchHandler {
    private lateinit var rootView: ComposeView
    private var path by mutableStateOf("sample")
    private var query by mutableStateOf(TextFieldValue(""))
    private var filter by mutableStateOf(SampleFilter.ALL)
    private var includeSubdirectories by mutableStateOf(true)
    private var useRegex by mutableStateOf(false)
    private var ascending by mutableStateOf(true)
    private var showSearchDialog by mutableStateOf(false)
    private var catalog: List<SampleEntry>? = null
    private val imports = CompositeDisposable()
    private var contentInstalled = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        path = state?.getString("sample.path") ?: "sample"
        query = TextFieldValue(state?.getString("sample.query").orEmpty())
        filter = SampleFilter.values().firstOrNull { it.name == state?.getString("sample.filter") }
            ?: SampleFilter.ALL
        includeSubdirectories = state?.getBoolean("sample.subdirectories", true) ?: true
        useRegex = state?.getBoolean("sample.regex", false) ?: false
        ascending = state?.getBoolean("sample.ascending", true) ?: true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("sample.path", path)
        outState.putString("sample.query", query.text)
        outState.putString("sample.filter", filter.name)
        outState.putBoolean("sample.subdirectories", includeSubdirectories)
        outState.putBoolean("sample.regex", useRegex)
        outState.putBoolean("sample.ascending", ascending)
    }

    override fun openPageSearch() {
        showSearchDialog = true
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        rootView = ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        }
        // 不等 onPageShow：ViewPager 预创建相邻页时就把内容装好并开始组合、读取示例清单，
        // 否则滑到本页的瞬间才首次 setContent + 首帧组合，滑动过程会掉帧发卡。
        installContentIfNeeded()
        return rootView
    }

    private fun installContentIfNeeded() {
        if (contentInstalled) return
        contentInstalled = true
        val assets = requireContext().assets
        rootView.setContent {
            AijsMiuixTheme {
                    var allEntries by remember { mutableStateOf(catalog) }
                    var loadError by remember { mutableStateOf<String?>(null) }
                    var retry by remember { mutableIntStateOf(0) }
                    var importEntry by remember { mutableStateOf<SampleEntry?>(null) }
                    var actionEntry by remember { mutableStateOf<SampleEntry?>(null) }
                    val listState = rememberLazyListState()
                    LaunchedEffect(retry) {
                        if (allEntries == null) {
                            loadError = null
                            try {
                                val loaded = withContext(Dispatchers.IO) {
                                    MiuixSampleCatalog.load { assets.list(it).orEmpty() }
                                }
                                catalog = loaded
                                allEntries = loaded
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                loadError = error.localizedMessage ?: "无法读取示例"
                            }
                        }
                    }
                    val searchResult = remember(allEntries, path, query.text, filter,
                        includeSubdirectories, useRegex) {
                        MiuixSampleCatalog.search(allEntries.orEmpty(), path, query.text, filter,
                            includeSubdirectories, useRegex)
                    }
                    val entries = if (ascending) searchResult.entries else searchResult.entries.asReversed()
                    val childCounts = remember(allEntries) { directChildCounts(allEntries.orEmpty()) }
                    LaunchedEffect(path, query.text, filter, includeSubdirectories, useRegex, ascending) {
                        listState.scrollToItem(0)
                    }

                    Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
                        BrowserToolbar(
                            resultCount = entries.size,
                            onUp = { if (path != "sample") navigateUp() },
                            onSort = { ascending = !ascending },
                            onSearch = { showSearchDialog = true }
                        )
                        LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            state = listState,
                            contentPadding = PaddingValues(bottom = 18.dp)
                        ) {
                            when {
                                loadError != null -> item {
                                    MessagePanel("读取失败：$loadError", "重试") { retry++ }
                                }
                                allEntries == null -> item { MessagePanel("正在读取示例…") }
                                searchResult.error != null -> item {
                                    MessagePanel(searchResult.error, textColor = Color(0xFFD1495B))
                                }
                                entries.isEmpty() -> item {
                                    MessagePanel("没有匹配的示例，请修改关键词或筛选条件。")
                                }
                            }
                            items(entries, key = { it.path }, contentType = { it.directory }) { entry ->
                                SampleRow(entry, childCounts[entry.path] ?: 0) { actionEntry = it }
                            }
                        }
                    }

                    if (showSearchDialog) SearchDialog { showSearchDialog = false }
                    actionEntry?.let { entry ->
                        EntryActionsDialog(entry, dismiss = { actionEntry = null },
                            requestImport = { actionEntry = null; importEntry = entry })
                    }
                    importEntry?.let { entry -> ImportDialog(entry) { importEntry = null } }
            }
        }
    }

    @Composable
    private fun BrowserToolbar(
        resultCount: Int,
        onUp: () -> Unit,
        onSort: () -> Unit,
        onSearch: () -> Unit
    ) {
        Row(
            Modifier.fillMaxWidth().height(54.dp)
                .background(MiuixTheme.colorScheme.surface)
                .padding(start = 18.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(sampleBreadcrumb(), fontSize = 18.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                if (query.text.isNotBlank() || filter != SampleFilter.ALL) {
                    Text("$resultCount 项 · 搜索结果", fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary)
                }
            }
            ToolbarAction(R.drawable.ic_folder_enter_24dp, "上一级", onUp,
                enabled = path != "sample")
            SortRuleButton(ascending, onSort)
            ToolbarAction(R.drawable.ic_filter_list_24dp, "搜索和筛选", onSearch)
        }
    }

    /** Auto.js Pro style sort entry: two-line label "排序规则 / 升序" instead of an icon. */
    @Composable
    private fun SortRuleButton(ascending: Boolean, onSort: () -> Unit) {
        Column(
            Modifier.height(48.dp).clickable(onClick = onSort)
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("排序规则", fontSize = 10.sp, maxLines = 1,
                color = MiuixTheme.colorScheme.onSurfaceSecondary)
            Text(if (ascending) "升序" else "降序", fontSize = 12.sp, maxLines = 1,
                color = MiuixTheme.colorScheme.onSurface)
        }
    }

    @Composable
    private fun ToolbarAction(icon: Int, description: String, onClick: () -> Unit, enabled: Boolean = true) {
        Box(Modifier.size(48.dp).clickable(enabled = enabled, onClick = onClick)) {
            Image(painterResource(icon), contentDescription = description,
                colorFilter = ColorFilter.tint(if (enabled) MiuixTheme.colorScheme.onSurface
                    else MiuixTheme.colorScheme.onSurfaceSecondary.copy(alpha = 0.35f)),
                modifier = Modifier.size(27.dp).align(Alignment.Center))
        }
    }

    @Composable
    private fun SampleRow(entry: SampleEntry, childCount: Int, requestActions: (SampleEntry) -> Unit) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 74.dp).clickable {
                    if (entry.directory) {
                        path = entry.path
                        query = TextFieldValue("")
                        filter = SampleFilter.ALL
                    } else {
                        view(entry)
                    }
                }.padding(start = 18.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 与「文件管理」列表保持同一套图标：圆底 + 白色类型符号。
                // 颜色/图形规则与 ExplorerViewHelper.getFileIconRes / getIconColor 同步。
                Box(Modifier.size(50.dp).clip(CircleShape).background(sampleIconColor(entry))) {
                    Image(
                        painterResource(sampleIconRes(entry)),
                        contentDescription = null,
                        modifier = Modifier.size(28.dp).align(Alignment.Center),
                        colorFilter = ColorFilter.tint(Color.White)
                    )
                }
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(entry.name, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(sampleDescription(entry, childCount), fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Start)
                    if (query.text.isNotBlank() || filter != SampleFilter.ALL) {
                        Text(entry.path.removePrefix("sample/"), fontSize = 12.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary)
                    }
                }
                ToolbarAction(R.drawable.ic_more_vert_black_24dp, "更多",
                    onClick = { requestActions(entry) })
            }
            Box(Modifier.fillMaxWidth().padding(start = 82.dp).height(1.dp)
                .background(MiuixTheme.colorScheme.dividerLine))
        }
    }

    /** 示例文件图标：与文件管理的专属图标一致（js/json/md/apk 各有花色，其余走通用文件图标）。 */
    private fun sampleIconRes(entry: SampleEntry): Int = when {
        entry.directory -> R.drawable.ic_folder_outline_24dp
        entry.name.endsWith(".js", true) -> R.drawable.ic_code_file_24dp
        entry.name.endsWith(".json", true) -> R.drawable.ic_json_file_24dp
        entry.name.endsWith(".md", true) -> R.drawable.ic_markdown_file_24dp
        entry.name.endsWith(".apk", true) -> R.drawable.ic_apk_file_24dp
        else -> R.drawable.ic_floating_action_menu_file
    }

    /** 与 ExplorerViewHelper.getIconColor 的颜色规则保持一致。 */
    private fun sampleIconColor(entry: SampleEntry): Color = when {
        entry.directory -> Color(0xFF1976D2)
        entry.name.endsWith(".js", true) -> Color(0xFF0A0E0F)
        entry.name.endsWith(".json", true) -> Color(0xFF0A0E0F)
        entry.name.endsWith(".md", true) -> Color(0xFF1E88E5)
        entry.name.endsWith(".apk", true) -> Color(0xFF32D780)
        else -> Color(0xFF9E9E9E)
    }

    private fun sampleDescription(entry: SampleEntry, childCount: Int): String = when {
        entry.directory -> if (childCount > 0) "文件夹 · $childCount 项" else "文件夹"
        entry.name.endsWith(".js", true) -> "JavaScript 示例"
        entry.name.endsWith(".md", true) -> "Markdown 文档"
        entry.name.endsWith(".json", true) -> "JSON 文件"
        entry.name.endsWith(".apk", true) -> "安装包"
        else -> "示例资源"
    }

    @Composable
    private fun SearchDialog(dismiss: () -> Unit) {
        val focusRequester = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
        Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Card(Modifier.fillMaxWidth(0.84f)) {
                Column(Modifier.fillMaxWidth().padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextField(query, { query = it }, Modifier.fillMaxWidth().focusRequester(focusRequester),
                        singleLine = true, label = "搜索示例")
                    Row(Modifier.fillMaxWidth().clickable {
                        includeSubdirectories = !includeSubdirectories
                    }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(includeSubdirectories, { includeSubdirectories = it })
                        Text("搜索子目录", fontSize = 15.sp)
                    }
                    Row(Modifier.fillMaxWidth().clickable { useRegex = !useRegex },
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(useRegex, { useRegex = it })
                        Text("正则表达式", fontSize = 15.sp)
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SampleFilter.values().forEach { choice ->
                            Button(onClick = { filter = choice }) {
                                Text(if (filter == choice) "✓ ${choice.label}" else choice.label)
                            }
                        }
                    }
                    Text("当前匹配 ${MiuixSampleCatalog.search(catalog.orEmpty(), path, query.text,
                        filter, includeSubdirectories, useRegex).entries.size} 项", fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically) {
                        if (query.text.isNotBlank() || filter != SampleFilter.ALL || useRegex ||
                            !includeSubdirectories) {
                            Button(onClick = {
                                query = TextFieldValue("")
                                filter = SampleFilter.ALL
                                includeSubdirectories = true
                                useRegex = false
                            }) { Text("重置") }
                            Spacer(Modifier.width(8.dp))
                        }
                        Button(onClick = dismiss) { Text("完成") }
                    }
                }
            }
        }
    }

    @Composable
    private fun EntryActionsDialog(
        entry: SampleEntry,
        dismiss: () -> Unit,
        requestImport: () -> Unit
    ) {
        Dialog(onDismissRequest = dismiss) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()
                    .background(MiuixTheme.colorScheme.surface)
                    .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(entry.name, fontSize = 21.sp, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, color = MiuixTheme.colorScheme.onSurface)
                    Text(when {
                        entry.directory -> "示例文件夹"
                        entry.runnable -> "JavaScript 示例"
                        else -> "示例资源"
                    },
                        fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                    ActionChoice(if (entry.directory) "打开" else "查看") {
                        dismiss()
                        if (entry.directory) {
                            path = entry.path
                            query = TextFieldValue("")
                            filter = SampleFilter.ALL
                        } else view(entry)
                    }
                    if (entry.directory) {
                        // 项目型示例（目录里带 project.json）只有整份复制过去才能当项目打包，
                        // 单文件打包会把同目录的模块/资源丢掉。
                        if (isProjectSample(entry)) ActionChoice("打包（项目）") {
                            dismiss()
                            buildProject(entry)
                        }
                    } else {
                        if (entry.runnable) {
                            ActionChoice("运行") { dismiss(); run(entry) }
                            // 示例本体在 APK 内置资源里，打包器要的是磁盘上的真实文件，
                            // 所以先落到脚本目录，再进打包页。
                            ActionChoice("打包") { dismiss(); build(entry) }
                        }
                        ActionChoice("导入", requestImport)
                        val imported = java.io.File(java.io.File(Pref.getScriptDirPath()), entry.name)
                        if (imported.isFile) {
                            ActionChoice("打开已导入副本（可恢复最新版）") {
                                dismiss()
                                startActivity(ProCodeEditorActivity.sampleIntent(
                                    requireContext(), imported, entry.path))
                            }
                        }
                    }
                    ActionChoice("取消", dismiss)
                }
            }
        }
    }

    @Composable
    private fun ActionChoice(label: String, onClick: () -> Unit) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.14f))
            .clickable(onClick = onClick).padding(vertical = 14.dp, horizontal = 16.dp)) {
            Text(label, fontSize = 16.sp, color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.align(Alignment.CenterStart))
        }
    }

    @Composable
    private fun MessagePanel(text: String, action: String? = null,
        textColor: Color = MiuixTheme.colorScheme.onSurface, onAction: () -> Unit = {}) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Miuix 的 Text 默认两端对齐，长段中文会被拉伸成大字距——提示文本明确左对齐。
            Text(text, color = textColor,
                textAlign = androidx.compose.ui.text.style.TextAlign.Start)
            action?.let { Button(onClick = onAction) { Text(it) } }
        }
    }

    private fun view(entry: SampleEntry) {
        // Directly open in the new Pro editor. If the sample was already
        // imported into the script dir, reuse it; otherwise import silently
        // (no name prompt) and open after the copy completes.
        val context = requireContext()
        val existing = java.io.File(java.io.File(Pref.getScriptDirPath()), entry.name)
        if (existing.isFile) {
            Toast.makeText(context,
                "已打开导入副本；如内容较旧，可点编辑器顶部“重置”并自动备份后恢复最新版",
                Toast.LENGTH_LONG).show()
            startActivity(ProCodeEditorActivity.sampleIntent(context, existing, entry.path))
            return
        }
        imports.add(ScriptOperations(context, rootView)
            .importSampleWithName(SampleFile(entry.path, context.assets), entry.name)
            .subscribe({ path ->
                startActivity(ProCodeEditorActivity.sampleIntent(
                    context, java.io.File(path), entry.path))
            }, { failure ->
                Toast.makeText(context,
                    failure.localizedMessage ?: "打开示例失败", Toast.LENGTH_LONG).show()
            }))
    }

    private fun run(entry: SampleEntry) {
        if (!entry.runnable) return
        val execution = Scripts.run(SampleFile(entry.path, requireContext().assets).toSource())
        if (execution != null) Toast.makeText(requireContext(), "已启动：${entry.name}", Toast.LENGTH_SHORT).show()
    }

    /** 项目型示例：目录里直接带 `project.json`，打包时会按项目模式处理。 */
    private fun isProjectSample(entry: SampleEntry): Boolean =
        catalog.orEmpty().any { it.path == entry.path + "/project.json" }

    /**
     * 打包单文件示例：示例本体在 APK 内置资源里，而打包器要的是磁盘上的真实文件，
     * 所以先落到脚本目录。已有导入副本就直接用它（与「查看」一致，不覆盖用户改过的内容）。
     */
    private fun build(entry: SampleEntry) {
        val context = requireContext()
        val existing = java.io.File(java.io.File(Pref.getScriptDirPath()), entry.name)
        if (existing.isFile) {
            Toast.makeText(context, "使用已导入的副本：${existing.name}", Toast.LENGTH_SHORT).show()
            openBuildPage(existing)
            return
        }
        imports.add(ScriptOperations(context, rootView)
            .importSampleWithName(SampleFile(entry.path, context.assets), entry.name)
            .subscribe({ path -> openBuildPage(java.io.File(path)) }, { failure ->
                Toast.makeText(context,
                    failure.localizedMessage ?: "打包示例失败", Toast.LENGTH_LONG).show()
            }))
    }

    /**
     * 打包项目型示例：把整棵示例目录复制到脚本目录（同名的文件保留用户已有的版本），
     * 再以这个目录为源进打包页（打包页会读里面的 project.json，按项目模式打包）。
     */
    private fun buildProject(entry: SampleEntry) {
        val context = requireContext()
        val prefix = entry.path + "/"
        val files = catalog.orEmpty().filter { !it.directory && it.path.startsWith(prefix) }
        val target = java.io.File(java.io.File(Pref.getScriptDirPath()), entry.name)
        imports.add(Observable.fromCallable {
            files.forEach { file ->
                val copy = java.io.File(target, file.path.removePrefix(prefix))
                if (copy.isFile) return@forEach
                copy.parentFile?.mkdirs()
                context.assets.open(file.path).use { input ->
                    copy.outputStream().use { output -> input.copyTo(output) }
                }
            }
            target
        }.subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
            .subscribe({ folder -> openBuildPage(folder) }, { failure ->
                Toast.makeText(context,
                    failure.localizedMessage ?: "打包示例失败", Toast.LENGTH_LONG).show()
            }))
    }

    /** 打包页：MIUIX 司机会把这个 Intent 转给 MiuixBuildActivity（见 BuildActivity.onCreate）。 */
    private fun openBuildPage(target: java.io.File) {
        startActivity(Intent(requireContext(), BuildActivity::class.java)
            .putExtra(BuildActivity.EXTRA_SOURCE, target.absolutePath))
    }

    @Composable
    private fun ImportDialog(entry: SampleEntry, dismiss: () -> Unit) {
        var name by remember(entry.path) { mutableStateOf(TextFieldValue(entry.name)) }
        var busy by remember(entry.path) { mutableStateOf(false) }
        var error by remember(entry.path) { mutableStateOf<String?>(null) }
        Dialog(onDismissRequest = { if (!busy) dismiss() }, properties = DialogProperties(
            dismissOnBackPress = !busy, dismissOnClickOutside = !busy)) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("导入示例", fontSize = 22.sp)
                    Text("复制到我的脚本，已有文件不会被覆盖；旧副本可从详情进入编辑器后点“重置”升级。", fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary)
                    TextField(name, { if (!busy) { name = it; error = null } },
                        Modifier.fillMaxWidth(), singleLine = true, label = "文件名")
                    error?.let { Text(it, fontSize = 14.sp) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { if (!busy) dismiss() }, modifier = Modifier.weight(1f)) { Text("取消") }
                        Button(onClick = {
                            if (!busy) {
                                busy = true
                                error = null
                                val context = requireContext().applicationContext
                                imports.add(ScriptOperations(requireContext(), rootView)
                                    .importSampleWithName(SampleFile(entry.path, context.assets), name.text)
                                    .subscribe({ importedPath ->
                                        busy = false
                                        dismiss()
                                        Toast.makeText(context, "已导入：$importedPath", Toast.LENGTH_LONG).show()
                                    }, { failure ->
                                        busy = false
                                        error = failure.localizedMessage ?: "导入失败，请重试"
                                    }))
                            }
                        }, modifier = Modifier.weight(1f)) { Text(if (busy) "导入中…" else "导入") }
                    }
                }
            }
        }
    }

    private fun sampleBreadcrumb(): String {
        val suffix = path.removePrefix("sample").trim('/')
        return buildString {
            append("示例文件  ›  中文")
            if (suffix.isNotEmpty()) append("  ›  ").append(suffix.replace("/", "  ›  "))
        }
    }

    private fun directChildCounts(entries: List<SampleEntry>): Map<String, Int> {
        val counts = HashMap<String, Int>()
        entries.forEach { entry ->
            val parent = entry.path.substringBeforeLast('/', "")
            if (parent.isNotEmpty()) counts[parent] = (counts[parent] ?: 0) + 1
        }
        return counts
    }

    private fun navigateUp() {
        query = TextFieldValue("")
        filter = SampleFilter.ALL
        path = path.substringBeforeLast('/', "sample")
    }

    override fun onPageShow() {
        super.onPageShow()
        if (::rootView.isInitialized) installContentIfNeeded()
    }

    override fun onDestroyView() {
        imports.clear()
        contentInstalled = false
        super.onDestroyView()
    }

    override fun onFabClick(fab: FloatingActionButton?) = Unit

    override fun onBackPressed(activity: Activity): Boolean = when {
        showSearchDialog -> { showSearchDialog = false; true }
        query.text.isNotEmpty() || useRegex || !includeSubdirectories || filter != SampleFilter.ALL -> {
            query = TextFieldValue("")
            filter = SampleFilter.ALL
            useRegex = false
            includeSubdirectories = true
            true
        }
        path != "sample" -> { navigateUp(); true }
        else -> false
    }
}
