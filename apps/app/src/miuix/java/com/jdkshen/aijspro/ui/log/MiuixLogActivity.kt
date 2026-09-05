package com.jdkshen.aijspro.ui.log

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsControllerCompat
import com.jdkshen.aijspro.R
import com.jdkshen.aijspro.autojs.AutoJs
import com.jdkshen.aijspro.theme.AijsMiuixTheme
import com.jdkshen.aijspro.theme.MiuixBackButton
import com.jdkshen.aijspro.theme.isAijsDarkTheme
import com.stardust.autojs.core.console.ConsoleImpl
import com.stardust.autojs.core.console.ConsoleView
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Miuix log page (pilot). Reuses the existing ConsoleView/ConsoleImpl.
 */
class MiuixLogActivity : ComponentActivity() {

    private var consoleImpl: ConsoleImpl? = null
    private var consoleView: ConsoleView? = null

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

    @Composable
    private fun LogPage() {
        Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
            SmallTopAppBar(title = getString(R.string.text_log), defaultWindowInsetsPadding = false,
                navigationIcon = { MiuixBackButton(onClick = { finish() }) },
                actions = {
                    TextButton(text = "清空", modifier = Modifier.padding(end = 12.dp),
                        onClick = { consoleImpl?.clear() })
                })
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { context ->
                    ConsoleView(context).also { view ->
                        consoleView = view
                        view.setConsole(consoleImpl)
                        val input = view.findViewById<android.view.View>(R.id.input_container)
                        input?.visibility = android.view.View.GONE
                    }
                }
            )
        }
    }
}
