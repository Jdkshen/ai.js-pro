package com.jdkshen.aijspro.ui.log

import android.os.Bundle
import android.util.Log
import android.util.SparseArray
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import com.stardust.autojs.core.console.ConsoleImpl
import com.stardust.autojs.core.console.ConsoleView
import com.stardust.util.IntentUtil
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.icons.Check
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Miuix log page (pilot). Reuses the existing ConsoleView/ConsoleImpl and
 * mirrors the Auto.js Pro top bar: level dropdown, search, share, clear.
 */
class MiuixLogActivity : ComponentActivity() {

    private var consoleImpl: ConsoleImpl? = null
    private var consoleView: ConsoleView? = null

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

    /** Console colors following the Miuix theme directly (not the legacy M3 palette). */
    private fun applyLogTheme(view: ConsoleView) {
        val dark = isAijsDarkTheme()
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
        var levelIndex by remember { mutableIntStateOf(0) }
        var levelsOpen by remember { mutableStateOf(false) }
        var searchShown by remember { mutableStateOf(false) }
        var query by remember { mutableStateOf(TextFieldValue("")) }
        var anchorPos by remember { mutableStateOf(IntOffset.Zero) }
        var anchorSize by remember { mutableStateOf(IntSize.Zero) }

        Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
            Column(Modifier.fillMaxSize()) {
                SmallTopAppBar(title = getString(R.string.text_log), defaultWindowInsetsPadding = false,
                    navigationIcon = { MiuixBackButton(onClick = { finish() }) },
                    actions = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
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
                    }
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
                        .padding(vertical = 6.dp)
                ) {
                    levels.forEachIndexed { i, (name, _) ->
                        Row(
                            Modifier.clickable {
                                levelIndex = i
                                consoleView?.setMinimumLogLevel(levels[i].second)
                                levelsOpen = false
                            }.fillMaxWidth().padding(horizontal = 24.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(name,
                                fontSize = 16.sp,
                                color = if (i == levelIndex) MiuixTheme.colorScheme.primary
                                else MiuixTheme.colorScheme.onBackground)
                            if (i == levelIndex) {
                                Spacer(Modifier.width(10.dp))
                                Icon(MiuixIcons.Check, contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MiuixTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
            FloatingActionButton(
                onClick = { consoleImpl?.clear() },
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
                shape = RoundedCornerShape(60.dp),
            ) {
                Text("×", fontSize = 28.sp, color = Color.White)
            }
        }
    }
}
