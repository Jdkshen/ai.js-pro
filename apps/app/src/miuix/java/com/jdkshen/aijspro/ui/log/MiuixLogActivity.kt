package com.jdkshen.aijspro.ui.log

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsControllerCompat
import com.jdkshen.aijspro.R
import com.jdkshen.aijspro.autojs.AutoJs
import com.jdkshen.aijspro.theme.AijsMiuixTheme
import com.jdkshen.aijspro.theme.AppThemeRepository
import com.jdkshen.aijspro.theme.ConsoleThemeHelper
import com.jdkshen.aijspro.theme.MiuixBackButton
import com.jdkshen.aijspro.theme.isAijsDarkTheme
import com.stardust.autojs.core.console.ConsoleImpl
import com.stardust.autojs.core.console.ConsoleView
import com.stardust.util.IntentUtil
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

    @Composable
    private fun LogPage() {
        var levelIndex by remember { mutableIntStateOf(0) }
        var levelsOpen by remember { mutableStateOf(false) }
        var searchShown by remember { mutableStateOf(false) }
        var query by remember { mutableStateOf(TextFieldValue("")) }

        Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
            SmallTopAppBar(title = getString(R.string.text_log), defaultWindowInsetsPadding = false,
                navigationIcon = { MiuixBackButton(onClick = { finish() }) },
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(text = levels[levelIndex].first + " ▾",
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
                        IconButton(onClick = { consoleImpl?.clear() }, modifier = Modifier.padding(end = 4.dp)) {
                            Icon(painter = painterResource(R.drawable.ic_clear_white_48dp),
                                contentDescription = "清空",
                                tint = MiuixTheme.colorScheme.onBackgroundVariant)
                        }
                    }
                })
            if (levelsOpen) {
                Column(
                    Modifier.fillMaxWidth()
                        .background(MiuixTheme.colorScheme.surfaceContainer)
                ) {
                    levels.forEachIndexed { i, (name, _) ->
                        Text(name,
                            fontSize = 16.sp,
                            color = if (i == levelIndex) MiuixTheme.colorScheme.primary
                            else MiuixTheme.colorScheme.onBackground,
                            modifier = Modifier.fillMaxWidth().clickable {
                                levelIndex = i
                                consoleView?.setMinimumLogLevel(levels[i].second)
                                levelsOpen = false
                            }.padding(horizontal = 20.dp, vertical = 12.dp))
                    }
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
                        ConsoleThemeHelper.apply(view, AppThemeRepository.get(context).getPalette())
                        val input = view.findViewById<android.view.View>(R.id.input_container)
                        input?.visibility = android.view.View.GONE
                    }
                }
            )
        }
    }
}
