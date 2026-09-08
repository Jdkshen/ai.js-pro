package com.jdkshen.aijspro.ui.mcp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowInsetsControllerCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.jdkshen.aijspro.Pref
import com.jdkshen.aijspro.mcp.McpService
import com.jdkshen.aijspro.mcp.McpSettings
import com.jdkshen.aijspro.theme.AijsMiuixTheme
import com.jdkshen.aijspro.theme.MiuixBackButton
import com.jdkshen.aijspro.theme.isAijsDarkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

/** User-controlled local MCP endpoint. Opening this page never starts the server. */
class MiuixMcpActivity : ComponentActivity() {
    private var pageActive by mutableStateOf(false)

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
                    McpPage()
                }
            }
        })
    }
    override fun onStart() { super.onStart(); pageActive = true }
    override fun onStop() { pageActive = false; super.onStop() }

    private fun copy(label: String, content: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, content))
        Toast.makeText(this, "已复制$label", Toast.LENGTH_SHORT).show()
    }
    private data class Status(val running: Boolean, val writes: Boolean, val execution: Boolean, val count: Int, val error: String?)
    private fun status() = Status(McpService.running, McpService.allowWrite, McpService.allowExecution, McpService.requestCount, McpService.lastError)

    @Composable private fun McpPage() {
        var current by remember { mutableStateOf(status()) }
        var dialog by remember { mutableStateOf<String?>(null) }
        var revision by remember { mutableIntStateOf(0) }
        var addresses by remember { mutableStateOf(emptyList<String>()) }
        var pending by remember { mutableStateOf(false) }
        var auto by remember { mutableStateOf(McpSettings.autoStart(this@MiuixMcpActivity)) }
        var events by remember { mutableStateOf(McpService.recentEvents().take(10)) }
        LaunchedEffect(pageActive, revision) {
            if (pageActive) {
                addresses = withContext(Dispatchers.IO) { McpService.lanAddresses(this@MiuixMcpActivity) }
                while (pageActive) {
                    current = status(); if (current.running || current.error != null) pending = false
                    events = McpService.recentEvents().take(10); delay(750)
                }
            }
        }
        Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
            SmallTopAppBar(title = "脚本 MCP", defaultWindowInsetsPadding = false,
                navigationIcon = { MiuixBackButton(onClick = { finish() }) }, actions = {
                    TextButton(text = "历史", onClick = { startActivity(Intent(this@MiuixMcpActivity, MiuixMcpHistoryActivity::class.java)) })
                    TextButton(text = "帮助", onClick = { dialog = "help" })
                })
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if (current.running) "服务运行中" else if (pending) "正在启动…" else "服务已停止", fontSize = 19.sp, color = MiuixTheme.colorScheme.primary)
                        Text("${current.count} 次请求", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                    }
                    Text("本地 Streamable HTTP 脚本开发与调试服务", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                    current.error?.let { Text("启动失败：$it", fontSize = 13.sp) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            if (current.running) McpService.stop(this@MiuixMcpActivity) else if (!pending) {
                                pending = true
                                runCatching { McpService.start(this@MiuixMcpActivity) }.onFailure { pending = false; Toast.makeText(this@MiuixMcpActivity, it.localizedMessage, Toast.LENGTH_LONG).show() }
                            }; revision++
                        }, modifier = Modifier.weight(1f)) { Text(if (current.running) "停止" else "启动") }
                        Button(onClick = { dialog = "settings" }, modifier = Modifier.weight(1f)) { Text("设置") }
                    }
                } }

                SmallTitle("连接")
                Card(Modifier.fillMaxWidth()) {
                    SuperArrow(title = "本机地址", summary = McpService.localAddress(this@MiuixMcpActivity), onClick = { copy("本机地址", McpService.localAddress(this@MiuixMcpActivity)) })
                    SuperArrow(title = "USB Host 转发", summary = "adb forward tcp:${McpSettings.USB_HOST_PORT} tcp:${McpSettings.port(this@MiuixMcpActivity)}", rightText = McpSettings.USB_HOST_PORT.toString(), onClick = { copy("ADB 命令", "adb forward tcp:${McpSettings.USB_HOST_PORT} tcp:${McpSettings.port(this@MiuixMcpActivity)}") })
                    if (McpSettings.lanEnabled(this@MiuixMcpActivity)) {
                        if (addresses.isEmpty()) BasicComponent(title = "局域网地址", summary = "未找到可用 IPv4 地址")
                        addresses.forEach { address -> SuperArrow(title = "局域网地址", summary = address, onClick = { copy("局域网地址", address) }) }
                    }
                    SuperArrow(title = "连接二维码", summary = "包含地址与 Bearer 访问令牌", rightText = "显示", onClick = { dialog = "qr" })
                    SuperArrow(title = "复制访问令牌", summary = "Authorization: Bearer <token>", onClick = { copy("访问令牌", McpSettings.token(this@MiuixMcpActivity)) })
                }

                SmallTitle("自动化")
                Card(Modifier.fillMaxWidth()) {
                    SuperSwitch(title = "打开 App 自动启动", summary = "打开 App 自动启动 MCP 并常驻运行（退后台不停）；手动停止后重开 App 会自动启动", checked = auto,
                        onCheckedChange = { McpSettings.setAutoStart(this@MiuixMcpActivity, it); auto = it })
                }
                SmallTitle("近期事件")
                Card(Modifier.fillMaxWidth()) {
                    if (events.isEmpty()) BasicComponent(title = "暂无事件", summary = "启动、停止、请求记录会显示在这里，并写入调用历史")
                    else events.forEach { event -> BasicComponent(title = event, summary = "自动记录 · 最近 50 条") }
                }

                SmallTitle("本次授权")
                Card(Modifier.fillMaxWidth()) {
                    BasicComponent(title = "只读访问", summary = "脚本、示例、搜索、任务状态与 APK 日志")
                    SuperSwitch(title = "允许编辑并应用", summary = "授权后 AI 可直接应用工作区；应用前校验原文件并自动备份，历史页可查看 Diff 和回退", checked = current.writes, enabled = current.running,
                        onCheckedChange = { if (it) dialog = "write" else { McpService.allowWrite = false; McpSettings.setWriteAllowed(this@MiuixMcpActivity, false); current = status() } })
                    SuperSwitch(title = "允许运行脚本", summary = "允许启动脚本并读取任务状态、异常与 APK 日志；开启后重启服务也会记住", checked = current.execution, enabled = current.running,
                        onCheckedChange = { if (it) dialog = "execute" else { McpService.allowExecution = false; McpSettings.setExecutionAllowed(this@MiuixMcpActivity, false); current = status() } })
                }
                Card(Modifier.fillMaxWidth()) {
                    SuperArrow(title = "工作区与修改历史", summary = "查看 Diff、已应用修改与安全回退", onClick = { startActivity(Intent(this@MiuixMcpActivity, MiuixMcpHistoryActivity::class.java)) })
                    SuperArrow(title = "电池优化", summary = "后台连接中断时检查系统限制", onClick = { runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } })
                }
                Text("操作目录：${operationDisplay()}", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary, modifier = Modifier.padding(horizontal = 4.dp))
                Text("仅连接可信客户端。局域网 HTTP 未加密，请勿公网暴露或分享令牌。授权开启后会被记住，重启服务自动恢复；可随时在此关闭。", fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary, modifier = Modifier.padding(horizontal = 4.dp).padding(bottom = 18.dp))
            }
        }
        when (dialog) {
            "settings" -> SettingsDialog { dialog = null; revision++ }
            "qr" -> QrDialog(addresses.firstOrNull()) { dialog = null }
            "help" -> MessageDialog("如何连接", "1. 启动服务，客户端选 Streamable HTTP。\n2. 同一手机的 MT 客户端填 http://127.0.0.1:${McpSettings.port(this)}/mcp，并删除空白请求头行；本机兼容开启时无需请求头。\n3. 电脑 USB 连接先执行 adb forward tcp:${McpSettings.USB_HOST_PORT} tcp:${McpSettings.port(this)}，客户端填 http://127.0.0.1:${McpSettings.USB_HOST_PORT}/mcp。\n4. 严格模式或局域网连接需填写 Authorization: Bearer <令牌>。\n5. 从 get_status / search_scripts 开始；手机开启编辑授权后，workspace_request_apply 会直接应用工作区，并自动备份供历史页回退。") { dialog = null }
            "write", "execute" -> {
                val write = dialog == "write"
                ConfirmDialog(if (write) "允许 AI 编辑并应用？" else "允许 AI 执行脚本？",
                    if (write) "开启后 AI 可以直接把工作区应用到真实脚本，不再逐次弹出确认。每次应用前会校验原文件并自动备份，可在历史页查看 Diff 和回退。授权会被记住。" else "脚本可使用 App 已授予的手机、网络和文件权限；授权会被记住，重启服务自动恢复。",
                    confirm = { if (McpService.running) { if (write) { McpService.allowWrite = true; McpSettings.setWriteAllowed(this@MiuixMcpActivity, true) } else { McpService.allowExecution = true; McpSettings.setExecutionAllowed(this@MiuixMcpActivity, true) } }; current = status(); dialog = null }, dismiss = { dialog = null })
            }
        }
    }

    private fun operationDisplay(): String = File(Pref.getScriptDirPath(), McpSettings.operationPath(this)).path

    @Composable private fun SettingsDialog(onClose: () -> Unit) {
        var port by remember { mutableStateOf(TextFieldValue(McpSettings.port(this).toString())) }
        var operation by remember { mutableStateOf(TextFieldValue(McpSettings.operationPath(this))) }
        var days by remember { mutableStateOf(TextFieldValue(McpSettings.historyDays(this).toString())) }
        var lan by remember { mutableStateOf(McpSettings.lanEnabled(this)) }
        var localCompat by remember { mutableStateOf(McpSettings.localCompatibility(this)) }
        var error by remember { mutableStateOf<String?>(null) }
        var reset by remember { mutableStateOf(false) }
        Dialog(onDismissRequest = onClose) { Card(Modifier.fillMaxWidth()) { Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("MCP 设置", fontSize = 20.sp); Text("修改端口和操作目录前请先停止服务。", fontSize = 13.sp)
            TextField(port, { port = it; error = null }, Modifier.fillMaxWidth(), singleLine = true, label = "端口（1024–65535）")
            TextField(operation, { operation = it; error = null }, Modifier.fillMaxWidth(), singleLine = true, label = "脚本子目录（留空为根目录）")
            TextField(days, { days = it; error = null }, Modifier.fillMaxWidth(), singleLine = true, label = "历史保留天数（1–365）")
            SuperSwitch(title = "允许局域网连接", summary = "监听所有网卡；仅在可信网络中开启", checked = lan, onCheckedChange = { lan = it })
            SuperSwitch(title = "本机客户端兼容", summary = "127.0.0.1 和 USB 转发免令牌；局域网仍强制 Bearer 令牌", checked = localCompat, onCheckedChange = { localCompat = it })
            TextButton(text = "重置访问令牌", onClick = { if (McpService.running) error = "请先停止服务" else reset = true })
            error?.let { Text(it, fontSize = 13.sp) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onClose, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(onClick = {
                    val number = port.text.toIntOrNull(); val retention = days.text.toIntOrNull(); val clean = operation.text.replace('\\', '/').trim('/')
                    val scripts = File(Pref.getScriptDirPath()).canonicalFile
                    val target = runCatching { File(scripts, clean).canonicalFile }.getOrNull()
                    when {
                        McpService.running -> error = "请先停止服务"
                        number == null || number !in 1024..65535 -> error = "端口无效"
                        retention == null || retention !in 1..365 -> error = "历史天数无效"
                        clean.split('/').any { it == "." || it == ".." } || target == null || (target != scripts && !target.path.startsWith(scripts.path + File.separator)) -> error = "操作目录必须在脚本根目录内"
                        !target.isDirectory -> error = "操作目录不存在"
                        else -> { McpSettings.setPort(this@MiuixMcpActivity, number); McpSettings.setLanEnabled(this@MiuixMcpActivity, lan); McpSettings.setLocalCompatibility(this@MiuixMcpActivity, localCompat); McpSettings.setOperationPath(this@MiuixMcpActivity, clean); McpSettings.setHistoryDays(this@MiuixMcpActivity, retention); onClose() }
                    }
                }, modifier = Modifier.weight(1f)) { Text("保存") }
            }
        } } }
        if (reset) ConfirmDialog("重置令牌？", "旧令牌将立即失效，所有客户端都需重新配置。", confirm = { McpSettings.resetToken(this); reset = false; error = "令牌已更新" }, dismiss = { reset = false })
    }

    @Composable private fun QrDialog(lanAddress: String?, dismiss: () -> Unit) {
        val address = if (McpSettings.lanEnabled(this) && lanAddress != null) lanAddress else "http://127.0.0.1:${McpSettings.USB_HOST_PORT}/mcp"
        val payload = remember(address) { JSONObject().apply { put("name", "AI.js Pro"); put("transport", "streamable-http"); put("url", address); put("headers", JSONObject().put("Authorization", "Bearer ${McpSettings.token(this@MiuixMcpActivity)}")) }.toString() }
        val bitmap = remember(payload) { qrBitmap(payload, 720) }
        Dialog(onDismissRequest = dismiss) { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("连接二维码", fontSize = 20.sp)
            Image(bitmap.asImageBitmap(), contentDescription = "MCP 连接二维码", modifier = Modifier.fillMaxWidth().aspectRatio(1f))
            Text(address, fontSize = 12.sp); Text("二维码包含访问令牌，仅使用可信客户端扫码。USB 地址需先执行页面中的 adb forward 命令。", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
            Button(onClick = dismiss, modifier = Modifier.fillMaxWidth()) { Text("关闭") }
        } } }
    }
    private fun qrBitmap(value: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.CHARACTER_SET to "UTF-8"))
        val pixels = IntArray(size * size) { index -> if (matrix[index % size, index / size]) 0xff000000.toInt() else 0xffffffff.toInt() }
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { setPixels(pixels, 0, size, 0, 0, size, size) }
    }
    @Composable private fun MessageDialog(title: String, text: String, dismiss: () -> Unit) { Dialog(onDismissRequest = dismiss) { Card(Modifier.fillMaxWidth()) { Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(title, fontSize = 20.sp); Text(text, fontSize = 14.sp); Button(onClick = dismiss, modifier = Modifier.fillMaxWidth()) { Text("知道了") } } } } }
    @Composable private fun ConfirmDialog(title: String, text: String, confirm: () -> Unit, dismiss: () -> Unit) { Dialog(onDismissRequest = dismiss) { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(title, fontSize = 20.sp); Text(text, fontSize = 14.sp); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = dismiss, modifier = Modifier.weight(1f)) { Text("取消") }; Button(onClick = confirm, modifier = Modifier.weight(1f)) { Text("允许") } } } } } }
}
