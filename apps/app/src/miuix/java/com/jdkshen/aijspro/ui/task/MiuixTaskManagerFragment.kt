package com.jdkshen.aijspro.ui.task

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.jdkshen.aijspro.R
import com.jdkshen.aijspro.theme.MiuixListDivider
import com.jdkshen.aijspro.autojs.AutoJs
import com.jdkshen.aijspro.theme.AijsMiuixTheme
import com.jdkshen.aijspro.timing.TimedTaskManager
import com.jdkshen.aijspro.ui.main.MainPageSearchHandler
import com.jdkshen.aijspro.ui.main.ViewPagerFragment
import com.jdkshen.aijspro.ui.main.task.Task
import com.stardust.autojs.engine.ScriptEngine
import com.stardust.autojs.engine.ScriptEngineManager
import com.stardust.autojs.execution.ScriptExecution
import com.stardust.autojs.script.AutoFileSource
import com.stardust.autojs.execution.SimpleScriptExecutionListener
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Miuix "管理" page (fifth tab), aligned with Auto.js Pro's running-task list:
 * running scripts + scheduled/intent tasks, with stop/cancel per row and
 * stop-all on the FAB. Reuses the existing Task model and engine services.
 */
class MiuixTaskManagerFragment : ViewPagerFragment(0), MainPageSearchHandler {

    private sealed interface TaskItem {
        val name: String
        val desc: String
        /** Engine id, matching Auto.js Pro's engine badge: R = Rhino, J = QuickJS. */
        val engineName: String
        fun cancel()
        data class Running(val task: Task.RunningTask) : TaskItem {
            override val name get() = task.name
            override val desc get() = task.desc
            override val engineName get() = task.engineName
            override fun cancel() = task.cancel()
        }
        data class Pending(val task: Task.PendingTask) : TaskItem {
            override val name get() = task.name
            override val desc get() = task.desc
            override val engineName get() = task.engineName
            override fun cancel() = task.cancel()
        }
    }

    private lateinit var rootView: ComposeView
    private var running by mutableStateOf<List<TaskItem.Running>>(emptyList())
    private var pending by mutableStateOf<List<TaskItem.Pending>>(emptyList())
    private var reloadTick by mutableStateOf(0)
    private var selected by mutableStateOf<TaskItem?>(null)
    private var showSearchDialog by mutableStateOf(false)
    private var query by mutableStateOf("")
    private var runningExpanded by mutableStateOf(true)
    private var pendingExpanded by mutableStateOf(true)

    private val scriptListener = object : SimpleScriptExecutionListener() {
        override fun onStart(execution: ScriptExecution) = refreshOnUi()
        override fun onSuccess(execution: ScriptExecution, result: Any?) = refreshOnUi()
        override fun onException(execution: ScriptExecution, error: Throwable) = refreshOnUi()
    }

    /**
     * 运行列表是从 ScriptEngineService 的"运行中执行"里整表重读的，而执行记录要到
     * 引擎 destroy() 时才从那张表移除——那时已晚于 onSuccess/onException 的刷新事件。
     * 没有这个回调的话，刚停掉的脚本会一直挂在"运行中任务"里（点停止全部、按返回键
     * 结束 UI 脚本都会中招），直到下次切页/回前台才消失。
     */
    private val engineLifecycleCallback = object : ScriptEngineManager.EngineLifecycleCallback {
        override fun onEngineCreate(engine: ScriptEngine<*>) = Unit
        override fun onEngineRemove(engine: ScriptEngine<*>) = refreshOnUi()
    }

    private fun refreshOnUi() {
        runCatching {
            activity?.runOnUiThread { reloadTick++ }
        }
    }

    override fun openPageSearch() {
        showSearchDialog = true
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        rootView = ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AijsMiuixTheme {
                    LaunchedEffect(reloadTick) {
                        try {
                            val loaded = withContext(Dispatchers.IO) { loadTasks() }
                            running = loaded.first
                            pending = loaded.second
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        }
                    }
                    val needle = query.trim()
                    val visibleRunning = remember(running, needle) {
                        if (needle.isEmpty()) running
                        else running.filter { it.name.contains(needle, true) || it.desc.contains(needle, true) }
                    }
                    val visiblePending = remember(pending, needle) {
                        if (needle.isEmpty()) pending
                        else pending.filter { it.name.contains(needle, true) || it.desc.contains(needle, true) }
                    }
                    Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
                        LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp)
                        ) {
                            if (visibleRunning.isEmpty() && visiblePending.isEmpty() && query.isNotEmpty()) {
                                item { EmptyHint("没有匹配的任务") }
                            }
                            item {
                                GroupHeader("运行中任务", visibleRunning.size, R.drawable.ic_run_gray,
                                    runningExpanded) { runningExpanded = !runningExpanded }
                            }
                            if (runningExpanded) {
                                items(visibleRunning, key = { it.task.hashCode().toString() }) { item ->
                                    TaskRow(item) { selected = it }
                                }
                            }
                            item {
                                GroupHeader("任务", visiblePending.size, R.drawable.ic_schedule_black_48dp,
                                    pendingExpanded) { pendingExpanded = !pendingExpanded }
                            }
                            if (pendingExpanded) {
                                items(visiblePending, key = { it.task.hashCode().toString() }) { item ->
                                    TaskRow(item) { selected = it }
                                }
                            }
                        }
                    }

                    if (showSearchDialog) SearchDialog { showSearchDialog = false }
                    selected?.let { item -> TaskDetailDialog(item, dismiss = { selected = null }) }
                }
            }
        }
        return rootView
    }

    override fun onResume() {
        super.onResume()
        reloadTick++
    }

    override fun onStart() {
        super.onStart()
        runCatching { registerListener(true) }
    }

    override fun onStop() {
        runCatching { registerListener(false) }
        super.onStop()
    }

    private fun registerListener(register: Boolean) {
        val service = AutoJs.getInstance().scriptEngineService
        if (register) {
            service.registerGlobalScriptExecutionListener(scriptListener)
            service.registerEngineLifecycleCallback(engineLifecycleCallback)
        } else {
            service.unregisterGlobalScriptExecutionListener(scriptListener)
            service.unregisterEngineLifecycleCallback(engineLifecycleCallback)
        }
    }

    private fun loadTasks(): Pair<List<TaskItem.Running>, List<TaskItem.Pending>> {
        val executions = AutoJs.getInstance().scriptEngineService.scriptExecutions ?: emptyList()
        val runningList = executions.map { TaskItem.Running(Task.RunningTask(it)) }
        val pendingList = mutableListOf<TaskItem.Pending>()
        TimedTaskManager.getInstance().getAllTasksAsList().forEach { pendingList += TaskItem.Pending(Task.PendingTask(it)) }
        TimedTaskManager.getInstance().getAllIntentTasksAsList().forEach { pendingList += TaskItem.Pending(Task.PendingTask(it)) }
        return runningList to pendingList
    }

    override fun onFabClick(fab: FloatingActionButton?) {
        AutoJs.getInstance().scriptEngineService.stopAll()
        Toast.makeText(requireContext(), "已停止全部脚本", Toast.LENGTH_SHORT).show()
        reloadTick++
    }

    /** 管理页的 FAB 是"停止全部"，图标对齐 Pro 的 ✕。 */
    override fun getFabIconRes(): Int = R.drawable.ic_close_white_48dp

    override fun onBackPressed(activity: Activity): Boolean = when {
        selected != null -> { selected = null; true }
        showSearchDialog -> { showSearchDialog = false; true }
        query.isNotEmpty() -> { query = ""; true }
        else -> false
    }

    // ---------- ui ----------

    @Composable
    private fun ToolbarAction(icon: Int, description: String, onClick: () -> Unit) {
        Box(Modifier.size(48.dp).clickable(onClick = onClick)) {
            Image(painterResource(icon), contentDescription = description,
                colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurface),
                modifier = Modifier.size(27.dp).align(Alignment.Center))
        }
    }

    /**
     * 分组头：圆底图标 + 标题 + 计数 + 折叠箭头，点击整行折叠/展开。
     *
     * **列网格与下方 [TaskRow] 完全一致**（本轮修的重点）：
     *   左内边距 16dp + 圆底 48dp + 文字 start 16dp ⇒ 文字起点 80dp，两行同列。
     *
     * 分组头是新界面自己加的（旧界面任务页只有 RecyclerView，没有分组头），
     * 所以没有能直接照抄的旧值。做法是先把 [TaskRow] 逐项对回旧 XML
     * （`task_list_recycler_view_item.xml`：66 / 16 / 48 / 14 / 11 / 16 / 80），
     * 再让分组头复用同一套列宽 —— 否则两行的圆底中心差 7dp、文字起点差 18dp，
     * 分组一展开就能看出错位（原先 34dp 圆底 + 12dp 间距就是这个问题）。
     *
     * 标题仍用 20sp（比行内 14sp 大一档，保持「分组 > 条目」的层级感）。
     */
    @Composable
    private fun GroupHeader(title: String, count: Int, icon: Int, expanded: Boolean, onToggle: () -> Unit) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onToggle)
                .padding(start = 16.dp, end = 12.dp, top = 16.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(CircleShape)
                .background(MiuixTheme.colorScheme.onSurfaceSecondary.copy(alpha = 0.22f))) {
                Image(painterResource(icon), contentDescription = title,
                    colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceSecondary),
                    modifier = Modifier.size(22.dp).align(Alignment.Center))
            }
            Text(title, fontSize = 20.sp, color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(start = 16.dp))
            if (count > 0) {
                Text("$count", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    modifier = Modifier.padding(end = 10.dp))
            }
            Image(
                painterResource(if (expanded) R.drawable.ic_expand_less_black_48dp
                    else R.drawable.ic_expand_more_black_48dp),
                contentDescription = if (expanded) "收起" else "展开",
                colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceSecondary),
                modifier = Modifier.size(22.dp))
        }
    }

    @Composable
    private fun EmptyHint(text: String) {
        Text(text, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary,
            modifier = Modifier.padding(start = 16.dp, top = 2.dp, bottom = 10.dp))
    }

    /**
     * 任务行。
     *
     * 逐项对齐旧界面 `task_list_recycler_view_item.xml`：
     *   minHeight 66dp · paddingStart 16dp · 上下 padding 5dp
     *   圆底 48dp · 首字母 22sp 白 · 文字列 marginStart 16dp
     *   名称 14sp maxLines 1 · 描述 11sp maxLines 2 · marginTop 2dp
     *   停止按钮命中区 48dp（ToolbarAction）· 分隔线缩进 80dp
     *
     * 原先用的是 78 / 18 / 8 / 50 / 17 / 12 / 82 —— 全部偏离基准，
     * 也是「分组头与内容行看起来没对齐」的一部分原因。
     */
    @Composable
    private fun TaskRow(item: TaskItem, open: (TaskItem) -> Unit) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 66.dp).clickable { open(item) }
                    .padding(start = 16.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 引擎徽标：R（Rhino，粉 #FD999A）/ J（QuickJS，绿 #99CC99）
                val isRhino = item.engineName == AutoFileSource.ENGINE
                Box(Modifier.size(48.dp).clip(CircleShape).background(
                    if (isRhino) Color(0xFFFD999A) else Color(0xFF99CC99))) {
                    Text(
                        if (isRhino) "R" else "J",
                        fontSize = 22.sp, color = Color.White,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Text(item.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(item.desc, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Start,
                        modifier = Modifier.padding(top = 2.dp))
                }
                ToolbarAction(R.drawable.ic_close_gray600_48dp,
                    if (item is TaskItem.Running) "停止" else "取消") {
                    item.cancel()
                    reloadTick++
                    Toast.makeText(requireContext(),
                        if (item is TaskItem.Running) "已停止：${item.name}" else "已取消：${item.name}",
                        Toast.LENGTH_SHORT).show()
                }
            }
            // 旧界面分隔线 marginStart = 80dp
            MiuixListDivider(indent = 80.dp)
        }
    }

    @Composable
    private fun SearchDialog(dismiss: () -> Unit) {
        var text by remember { mutableStateOf(query) }
        Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Card(Modifier.fillMaxWidth(0.84f)) {
                Column(Modifier.fillMaxWidth().padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    top.yukonga.miuix.kmp.basic.TextField(
                        androidx.compose.ui.text.input.TextFieldValue(text),
                        { text = it.text }, Modifier.fillMaxWidth(),
                        singleLine = true, label = "搜索任务名称/描述")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Button(onClick = { query = "" }) { Text("清除") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {
                            query = text
                            dismiss()
                        }) { Text("搜索") }
                    }
                }
            }
        }
    }

    @Composable
    private fun TaskDetailDialog(item: TaskItem, dismiss: () -> Unit) {
        Dialog(onDismissRequest = dismiss) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()
                    .background(MiuixTheme.colorScheme.surface)
                    .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(item.name, fontSize = 21.sp, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, color = MiuixTheme.colorScheme.onSurface)
                    Text(item.desc, fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Start)
                    ActionChoice(if (item is TaskItem.Running) "停止脚本" else "取消定时任务") {
                        item.cancel()
                        reloadTick++
                        dismiss()
                        Toast.makeText(requireContext(),
                            if (item is TaskItem.Running) "已停止：${item.name}" else "已取消：${item.name}",
                            Toast.LENGTH_SHORT).show()
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
}
