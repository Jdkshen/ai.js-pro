package com.jdkshen.aijspro.ui.log

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.util.SparseArray
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsControllerCompat
import com.jdkshen.aijspro.R
import com.jdkshen.aijspro.autojs.AutoJs
import com.jdkshen.aijspro.theme.AijsMiuixTheme
import com.jdkshen.aijspro.theme.MiuixBackButton
import com.jdkshen.aijspro.theme.isAijsDarkTheme
import com.jdkshen.aijspro.theme.isAijsDarkThemeResolved
import com.stardust.autojs.core.console.ConsoleImpl
import com.stardust.autojs.core.console.ConsoleView
import com.stardust.autojs.core.console.ScriptRunLog
import com.stardust.util.IntentUtil
import java.io.File
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Miuix log page (pilot). Reuses the existing ConsoleView/ConsoleImpl and
 * mirrors the Auto.js Pro top bar: level dropdown, search, share, clear.
 */
class MiuixLogActivity : ComponentActivity() {

    private var consoleImpl: ConsoleImpl? = null
    private var consoleView: ConsoleView? = null
    private var themedConsoleView: ConsoleView? = null
    private var appliedConsoleDark: Boolean? = null

    private val levels = listOf(
        "Verbose" to Log.VERBOSE,
        "Debug" to Log.DEBUG,
        "Info" to Log.INFO,
        "Warn" to Log.WARN,
        "Error" to Log.ERROR,
        "Assert" to Log.ASSERT
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consoleImpl = AutoJs.getInstance().globalConsole
        setContentView(ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val dark = isAijsDarkTheme()
                AijsMiuixTheme {
                    val bg = MiuixTheme.colorScheme.background
                    SideEffect {
                        window.statusBarColor = bg.toArgb()
                        window.navigationBarColor = bg.toArgb()
                        WindowInsetsControllerCompat(window, window.decorView).apply {
                            isAppearanceLightStatusBars = !dark
                            isAppearanceLightNavigationBars = !dark
                        }
                    }
                    LogPage()
                }
            }
        })
    }

    private fun shareLog() {
        val logs = consoleImpl?.getAllLogs() ?: return
        if (logs.isEmpty()) return
        val sb = StringBuilder()
        for (entry in logs) {
            sb.append(entry.content).append('\n')
        }
        IntentUtil.shareText(this, sb.toString())
    }

    private fun copyLog() {
        val logs = consoleImpl?.getAllLogs() ?: return
        if (logs.isEmpty()) return
        val sb = StringBuilder()
        for (entry in logs) {
            sb.append(entry.content).append('\n')
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("log", sb.toString()))
        Toast.makeText(this, "日志已复制到剪贴板（${logs.size} 条）", Toast.LENGTH_SHORT).show()
    }

    /** 读取历史日志文件（后台线程；大文件只取尾部 3000 行 —— 尾部才是现场）。 */
    private fun loadHistoryFile(file: File, onDone: (List<String>) -> Unit) {
        Thread {
            val lines = try {
                file.readLines(Charsets.UTF_8)
            } catch (error: Throwable) {
                listOf("[加载失败] " + (error.message ?: error.toString()))
            }
            val max = 3000
            val capped = if (lines.size > max) {
                listOf("（文件共 ${lines.size} 行，这里只显示最后 $max 行）")
                    .plus(lines.subList(lines.size - max, lines.size))
            } else {
                lines
            }
            runOnUiThread { onDone(capped) }
        }.start()
    }

    /** 从落盘日志行里还原级别（格式 `HH:mm:ss.SSS [I] ...`）。 */
    private fun levelOf(line: String): Int {
        return if (line.length >= 16 && line[13] == '[' && line[15] == ']') {
            when (line[14]) {
                'V' -> Log.VERBOSE
                'D' -> Log.DEBUG
                'I' -> Log.INFO
                'W' -> Log.WARN
                'E' -> Log.ERROR
                'A' -> Log.ASSERT
                else -> Log.VERBOSE
            }
        } else {
            Log.VERBOSE
        }
    }

    /** Console colors following the Miuix theme directly (not the legacy M3 palette). */
    private fun applyLogTheme(view: ConsoleView, dark: Boolean = isAijsDarkThemeResolved(this)) {
        if (themedConsoleView === view && appliedConsoleDark == dark) return
        themedConsoleView = view
        appliedConsoleDark = dark
        // 这里不是 @Composable 上下文，用带 Context 的版本（与 Compose 主题同规则、同数据源）
        val colors = SparseArray<Int>()
        colors.put(Log.VERBOSE, if (dark) 0xFF9E9E9E.toInt() else 0xFF757575.toInt())
        colors.put(Log.DEBUG, if (dark) 0xFFE6E6E6.toInt() else 0xFF212121.toInt())
        colors.put(Log.INFO, if (dark) 0xFF64DD17.toInt() else 0xFF2E7D32.toInt())
        colors.put(Log.WARN, if (dark) 0xFFFFD54F.toInt() else 0xFFE65100.toInt())
        colors.put(Log.ERROR, if (dark) 0xFFFF5252.toInt() else 0xFFC62828.toInt())
        colors.put(Log.ASSERT, if (dark) 0xFFFF7043.toInt() else 0xFFB71C1C.toInt())
        view.setColors(colors)
        val bg = if (dark) 0xFF000000.toInt() else 0xFFF7F7F7.toInt()
        view.setBackgroundColor(bg)
        view.findViewById<android.view.View>(R.id.log_list)?.setBackgroundColor(bg)
        view.findViewById<android.view.View>(R.id.input_container)?.setBackgroundColor(
            if (dark) 0xFF161616.toInt() else 0xFFEDEDED.toInt())
    }

    @Composable
    private fun LogPage() {
        val dark = isAijsDarkTheme()
        var levelIndex by remember { mutableIntStateOf(0) }
        var levelsOpen by remember { mutableStateOf(false) }
        var searchShown by remember { mutableStateOf(false) }
        var query by remember { mutableStateOf(TextFieldValue("")) }
        var anchorPos by remember { mutableStateOf(IntOffset.Zero) }
        var anchorSize by remember { mutableStateOf(IntSize.Zero) }
        var historyOpen by remember { mutableStateOf(false) }
        var historyFile by remember { mutableStateOf<File?>(null) }
        var historyConsole by remember { mutableStateOf<ConsoleImpl?>(null) }
        var histAnchorPos by remember { mutableStateOf(IntOffset.Zero) }
        var histAnchorSize by remember { mutableStateOf(IntSize.Zero) }

        Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
            Column(Modifier.fillMaxSize()) {
                SmallTopAppBar(title = getString(R.string.text_log), defaultWindowInsetsPadding = false,
                    navigationIcon = { MiuixBackButton(onClick = { finish() }) },
                    actions = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(text = "历史",
                                modifier = Modifier
                                    .background(MiuixTheme.colorScheme.surfaceVariant,
                                        RoundedCornerShape(20.dp))
                                    .onGloballyPositioned {
                                        histAnchorPos = IntOffset(
                                            it.positionInRoot().x.toInt(),
                                            it.positionInRoot().y.toInt()
                                        )
                                        histAnchorSize = it.size
                                    },
                                onClick = { historyOpen = !historyOpen })
                            TextButton(text = levels[levelIndex].first + " ▾",
                                modifier = Modifier
                                    .background(MiuixTheme.colorScheme.surfaceVariant,
                                        RoundedCornerShape(20.dp))
                                    .onGloballyPositioned {
                                        anchorPos = IntOffset(
                                            it.positionInRoot().x.toInt(),
                                            it.positionInRoot().y.toInt()
                                        )
                                        anchorSize = it.size
                                    },
                                onClick = { levelsOpen = !levelsOpen })
                            IconButton(onClick = { searchShown = !searchShown }) {
                                Icon(painter = painterResource(R.drawable.ic_search_white_24dp),
                                    contentDescription = "搜索",
                                    tint = MiuixTheme.colorScheme.onBackgroundVariant)
                            }
                            IconButton(onClick = { copyLog() }) {
                                Icon(painter = painterResource(R.drawable.ic_content_copy_white_24dp),
                                    contentDescription = "复制全部",
                                    tint = MiuixTheme.colorScheme.onBackgroundVariant)
                            }
                            IconButton(onClick = { shareLog() }) {
                                Icon(painter = painterResource(R.drawable.ic_share_white_48dp),
                                    contentDescription = "分享",
                                    tint = MiuixTheme.colorScheme.onBackgroundVariant)
                            }
                            IconButton(onClick = { consoleImpl?.clear() },
                                modifier = Modifier.padding(end = 4.dp)) {
                                Icon(painter = painterResource(R.drawable.ic_clear_white_48dp),
                                    contentDescription = "清空",
                                    tint = MiuixTheme.colorScheme.onBackgroundVariant)
                            }
                        }
                    })
                historyFile?.let { file ->
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
                        Text("历史日志：" + file.name, fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                            modifier = Modifier.weight(1f))
                        TextButton(text = "返回实时", onClick = {
                            consoleImpl?.let { live -> consoleView?.setConsole(live) }
                            historyConsole?.clear()
                            historyConsole = null
                            historyFile = null
                        })
                    }
                }
                if (searchShown) {
                    TextField(value = query, onValueChange = {
                        query = it
                        consoleView?.setSearchQuery(it.text)
                    }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        singleLine = true)
                }
                AndroidView(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    factory = { context ->
                        ConsoleView(context).also { view ->
                            consoleView = view
                            view.setConsole(consoleImpl)
                            applyLogTheme(view)
                            val input = view.findViewById<android.view.View>(R.id.input_container)
                            input?.visibility = android.view.View.GONE
                        }
                    },
                    update = { view -> applyLogTheme(view, dark) }
                )
            }
            if (levelsOpen) {
                Box(
                    Modifier.fillMaxSize()
                        .pointerInput(Unit) { detectTapGestures { levelsOpen = false } }
                )
                Column(
                    Modifier
                        .offset { IntOffset(anchorPos.x, anchorPos.y + anchorSize.height + 6) }
                        .shadow(24.dp, RoundedCornerShape(16.dp))
                        .clip(RoundedCornerShape(16.dp))
                        .background(MiuixTheme.colorScheme.surface)
                        .padding(4.dp)
                ) {
                    levels.forEachIndexed { i, (name, _) ->
                        Text(name,
                            fontSize = 15.sp,
                            color = MiuixTheme.colorScheme.onBackground,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (i == levelIndex) MiuixTheme.colorScheme.surfaceVariant
                                else Color.Transparent)
                                .clickable {
                                    levelIndex = i
                                    consoleView?.setMinimumLogLevel(levels[i].second)
                                    levelsOpen = false
                                }
                                .padding(horizontal = 18.dp, vertical = 9.dp))
                    }
                }
            }
            if (historyOpen) {
                Box(
                    Modifier.fillMaxSize()
                        .pointerInput(Unit) { detectTapGestures { historyOpen = false } }
                )
                Column(
                    Modifier
                        .offset { IntOffset(histAnchorPos.x, histAnchorPos.y + histAnchorSize.height + 6) }
                        .shadow(24.dp, RoundedCornerShape(16.dp))
                        .clip(RoundedCornerShape(16.dp))
                        .background(MiuixTheme.colorScheme.surface)
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(4.dp)
                ) {
                    val files = remember(historyOpen) {
                        ScriptRunLog.listRecent(this@MiuixLogActivity, 30)
                    }
                    if (files.isEmpty()) {
                        Text("还没有日志文件（跑一次脚本后就会生成）", fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp))
                    } else {
                        files.forEach { file ->
                            Text(file.name + "   " + (file.length() / 1024) + " KB",
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onBackground,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        historyOpen = false
                                        loadHistoryFile(file) { lines ->
                                            val target = ConsoleImpl(AutoJs.getInstance().uiHandler)
                                            consoleView?.setConsole(target)
                                            // 先清掉上一份日志在视图里的残留，再灌历史内容
                                            target.clear()
                                            for (line in lines) {
                                                target.println(levelOf(line), line)
                                            }
                                            historyConsole = target
                                            historyFile = file
                                        }
                                    }
                                    .padding(horizontal = 14.dp, vertical = 9.dp))
                        }
                    }
                }
            }
            FloatingActionButton(
                onClick = { consoleView?.clear() },
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
                shape = RoundedCornerShape(60.dp),
            ) {
                Text("×", fontSize = 28.sp, color = Color.White)
            }
        }
    }
}
