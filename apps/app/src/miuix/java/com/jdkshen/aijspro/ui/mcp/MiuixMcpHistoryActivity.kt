package com.jdkshen.aijspro.ui.mcp

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowInsetsControllerCompat
import com.jdkshen.aijspro.mcp.McpHistoryStore
import com.jdkshen.aijspro.mcp.McpService
import com.jdkshen.aijspro.mcp.McpWorkspaceStore
import com.jdkshen.aijspro.theme.AijsMiuixTheme
import com.jdkshen.aijspro.theme.MiuixBackButton
import com.jdkshen.aijspro.theme.isAijsDarkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Persistent history and the only UI surface allowed to apply or roll back MCP workspaces. */
class MiuixMcpHistoryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val dark = isAijsDarkTheme()
                AijsMiuixTheme {
                    val background = MiuixTheme.colorScheme.background
                    SideEffect {
                        window.statusBarColor = background.toArgb(); window.navigationBarColor = background.toArgb()
                        WindowInsetsControllerCompat(window, window.decorView).apply {
                            isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
                        }
                    }
                    HistoryPage()
                }
            }
        })
    }

    @Composable private fun HistoryPage() {
        var workspaces by remember { mutableStateOf(emptyList<McpWorkspaceStore.Workspace>()) }
        var calls by remember { mutableStateOf(emptyList<McpHistoryStore.Entry>()) }
        var tab by remember { mutableIntStateOf(0) }
        var selected by remember { mutableStateOf<McpWorkspaceStore.Workspace?>(null) }
        var diff by remember { mutableStateOf("") }
        var confirm by remember { mutableStateOf<String?>(null) }
        var revision by remember { mutableIntStateOf(0) }
        val scope = rememberCoroutineScope()
        LaunchedEffect(revision) {
            val snapshot = withContext(Dispatchers.IO) {
                McpService.workspaceStore(this@MiuixMcpHistoryActivity).list() to McpService.history(this@MiuixMcpHistoryActivity)
            }
            workspaces = snapshot.first; calls = snapshot.second
        }
        Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
            SmallTopAppBar(title = "MCP 历史", defaultWindowInsetsPadding = false,
                navigationIcon = { MiuixBackButton(onClick = { finish() }) })
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { tab = 0 }, modifier = Modifier.weight(1f)) { Text("工作区 ${workspaces.size}") }
                Button(onClick = { tab = 1 }, modifier = Modifier.weight(1f)) { Text("调用记录 ${calls.size}") }
            }
            if (tab == 0) LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (workspaces.isEmpty()) item { EmptyCard("暂无工作区", "AI 客户端创建工作区后，Diff 和应用请求会显示在这里。") }
                items(workspaces, key = { it.id }) { ws ->
                    Card(Modifier.fillMaxWidth()) {
                        SuperArrow(title = ws.targetPath.ifEmpty { "脚本根目录" },
                            summary = "${stateText(ws.state)} · ${ws.changedFiles} 个变更 · ${formatTime(ws.updatedAt)}",
                            rightText = if (ws.pendingApproval) "待确认" else "Diff",
                            onClick = {
                                selected = ws; diff = "正在读取…"
                                revision++
                            })
                    }
                    if (selected?.id == ws.id && diff == "正在读取…") LaunchedEffect(ws.id, revision) {
                        diff = withContext(Dispatchers.IO) { runCatching { McpService.workspaceStore(this@MiuixMcpHistoryActivity).diff(ws.id) }.getOrElse { "读取失败：${it.localizedMessage}" } }
                    }
                }
            } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (calls.isEmpty()) item { EmptyCard("暂无调用记录", "服务启动、工具调用和脚本任务会保留在本机。") }
                items(calls) { entry -> Card(Modifier.fillMaxWidth()) {
                    BasicComponent(title = if (entry.state == "error") "${entry.title} · 失败" else entry.title,
                        summary = "${entry.displayTime} · ${entry.detail}")
                } }
            }
        }
        selected?.let { ws -> Dialog(onDismissRequest = { selected = null }) {
            Card(Modifier.fillMaxWidth().fillMaxHeight(0.82f)) {
                Column(Modifier.padding(16.dp)) {
                    Text(ws.targetPath.ifEmpty { "脚本根目录" }, fontSize = 20.sp)
                    Text("${stateText(ws.state)} · ${ws.id}", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                    Text(diff, fontSize = 12.sp, modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { selected = null }, modifier = Modifier.weight(1f)) { Text("关闭") }
                        if (ws.state == "APPLIED" || ws.state == "OPEN" || ws.state == "PENDING_APPROVAL")
                            Button(onClick = { confirm = "rollback" }, modifier = Modifier.weight(1f)) { Text(if (ws.state == "APPLIED") "回退" else "丢弃") }
                        if (ws.pendingApproval) Button(onClick = { confirm = "apply" }, modifier = Modifier.weight(1f)) { Text("应用") }
                    }
                }
            }
        } }
        confirm?.let { action -> ConfirmDialog(
            if (action == "apply") "应用到真实脚本？" else "回退这个工作区？",
            if (action == "apply") "将再次校验原文件，有任何外部变化都会拒绝覆盖。" else "已应用的工作区会恢复备份；未应用的工作区会被标记为已丢弃。",
            confirm = {
                val ws = selected ?: return@ConfirmDialog
                confirm = null
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching {
                        val store = McpService.workspaceStore(this@MiuixMcpHistoryActivity)
                        if (action == "apply") store.applyConfirmed(ws.id) else store.rollbackConfirmed(ws.id)
                    } }
                    result.onSuccess {
                        Toast.makeText(this@MiuixMcpHistoryActivity, if (action == "apply") "已应用修改" else "已回退", Toast.LENGTH_SHORT).show()
                        selected = null; revision++
                    }.onFailure {
                        Toast.makeText(this@MiuixMcpHistoryActivity, it.localizedMessage ?: "操作失败", Toast.LENGTH_LONG).show()
                    }
                }
            }, dismiss = { confirm = null }) }
    }

    @Composable private fun EmptyCard(title: String, summary: String) = Card(Modifier.fillMaxWidth()) {
        BasicComponent(title = title, summary = summary)
    }
    @Composable private fun ConfirmDialog(title: String, text: String, confirm: () -> Unit, dismiss: () -> Unit) {
        Dialog(onDismissRequest = dismiss) { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, fontSize = 20.sp); Text(text, fontSize = 14.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = dismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(onClick = confirm, modifier = Modifier.weight(1f)) { Text("确认") }
            }
        } } }
    }
    private fun stateText(state: String) = when (state) {
        "OPEN" -> "编辑中"; "PENDING_APPROVAL" -> "等待应用"; "APPLIED" -> "已应用"; "ROLLED_BACK" -> "已回退"; else -> state
    }
    private fun formatTime(time: Long) = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(time))
}
