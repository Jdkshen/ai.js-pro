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
import com.jdkshen.aijspro.autojs.AutoJs
import com.jdkshen.aijspro.theme.AijsMiuixTheme
import com.jdkshen.aijspro.timing.TimedTaskManager
import com.jdkshen.aijspro.ui.main.MainPageSearchHandler
import com.jdkshen.aijspro.ui.main.ViewPagerFragment
import com.jdkshen.aijspro.ui.main.task.Task
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

    private val scriptListener = object : SimpleScriptExecutionListener() {
        override fun onStart(execution: ScriptExecution) = refreshOnUi()
        override fun onSuccess(execution: ScriptExecution, result: Any?) = refreshOnUi()
        override fun onException(execution: ScriptExecution, error: Throwable) = refreshOnUi()
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
                        TaskToolbar(
                            running = visibleRunning.size,
                            pending = visiblePending.size,
                            onRefresh = { reloadTick++ },
                            onSearch = { showSearchDialog = true }
                        )
                        LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentPadding = PaddingValues(bottom = 18.dp)
                        ) {
                            if (visibleRunning.isEmpty() && visiblePending.isEmpty()) {
                                item { MessagePanel("暂无任务。运行脚本后会显示在“运行中任务”，定时任务显示在“定时任务”。") }
                            }
                            item { GroupHeader("运行中任务", visibleRunning.size) }
                            if (visibleRunning.isEmpty()) {
                                item { EmptyHint("没有正在运行的脚本") }
                            }
                            items(visibleRunning, key = { it.task.hashCode().toString() }) { item ->
                                TaskRow(item) { selected = it }
                            }
                            item { GroupHeader("定时任务", visiblePending.size) }
                            if (visiblePending.isEmpty()) {
                                item { EmptyHint("没有待执行的定时任务") }
                            }
                            items(visiblePending, key = { it.task.hashCode().toString() }) { item ->
                                TaskRow(item) { selected = it }
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
        if (register) service.registerGlobalScriptExecutionListener(scriptListener)
        else service.unregisterGlobalScriptExecutionListener(scriptListener)
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

    override fun onBackPressed(activity: Activity): Boolean = when {
        selected != null -> { selected = null; true }
        showSearchDialog -> { showSearchDialog = false; true }
        query.isNotEmpty() -> { query = ""; true }
        else -> false
    }

    // ---------- ui ----------

    @Composable
    private fun TaskToolbar(running: Int, pending: Int, onRefresh: () -> Unit, onSearch: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().height(54.dp)
                .background(MiuixTheme.colorScheme.surface)
                .padding(start = 18.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("管理", fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("运行中 $running · 定时 $pending${if (query.isNotEmpty()) " · 已筛选" else ""}",
                    fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
            }
            ToolbarAction(R.drawable.ic_refresh_white_24dp, "刷新", onRefresh)
            ToolbarAction(R.drawable.ic_search_white_24dp, "搜索任务", onSearch)
        }
    }

    @Composable
    private fun ToolbarAction(icon: Int, description: String, onClick: () -> Unit) {
        Box(Modifier.size(48.dp).clickable(onClick = onClick)) {
            Image(painterResource(icon), contentDescription = description,
                colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurface),
                modifier = Modifier.size(27.dp).align(Alignment.Center))
        }
    }

    @Composable
    private fun GroupHeader(title: String, count: Int) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, top = 16.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 15.sp, color = MiuixTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Text("$count", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
        }
    }

    @Composable
    private fun EmptyHint(text: String) {
        Text(text, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary,
            modifier = Modifier.padding(start = 18.dp, top = 2.dp, bottom = 10.dp))
    }

    @Composable
    private fun TaskRow(item: TaskItem, open: (TaskItem) -> Unit) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 78.dp).clickable { open(item) }
                    .padding(start = 18.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Engine badge aligned with Auto.js Pro: "R" (Rhino, pink #FD999A) / "J" (QuickJS, green #99CC99)
                val isRhino = item.engineName == AutoFileSource.ENGINE
                Box(Modifier.size(50.dp).clip(CircleShape).background(
                    if (isRhino) Color(0xFFFD999A) else Color(0xFF99CC99))) {
                    Text(
                        if (isRhino) "R" else "J",
                        fontSize = 22.sp, color = Color.White,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(item.name, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(item.desc, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary)
                }
                ToolbarAction(if (item is TaskItem.Running) R.drawable.ic_close_gray600_48dp
                else R.drawable.ic_close_gray600_48dp,
                    if (item is TaskItem.Running) "停止" else "取消") {
                    item.cancel()
                    reloadTick++
                    Toast.makeText(requireContext(),
                        if (item is TaskItem.Running) "已停止：${item.name}" else "已取消：${item.name}",
                        Toast.LENGTH_SHORT).show()
                }
            }
            Box(Modifier.fillMaxWidth().padding(start = 82.dp).height(1.dp)
                .background(MiuixTheme.colorScheme.dividerLine))
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
                        color = MiuixTheme.colorScheme.onSurfaceSecondary)
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

    @Composable
    private fun MessagePanel(text: String) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(text, color = MiuixTheme.colorScheme.onSurface)
        }
    }
}
