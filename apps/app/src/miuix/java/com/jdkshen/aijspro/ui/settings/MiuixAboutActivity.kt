package com.jdkshen.aijspro.ui.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsControllerCompat
import com.jdkshen.aijspro.BuildConfig
import com.jdkshen.aijspro.R
import com.jdkshen.aijspro.theme.AijsMiuixTheme
import com.jdkshen.aijspro.theme.MiuixBackButton
import com.jdkshen.aijspro.theme.isAijsDarkTheme
import com.stardust.util.IntentUtil
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.MiuixPopupUtil
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * Miuix about page (pilot). Mirrors AboutActivity: logo + version, share
 * and the hidden crash-test easter egg on the logo.
 */
class MiuixAboutActivity : ComponentActivity() {

    private var iconClickCount by mutableIntStateOf(0)
    private val crashShow = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val dark = isAijsDarkTheme()
                AijsMiuixTheme {
                    val background = MiuixTheme.colorScheme.background
                    SideEffect {
                        window.statusBarColor = background.toArgb()
                        window.navigationBarColor = background.toArgb()
                        WindowInsetsControllerCompat(window, window.decorView).apply {
                            isAppearanceLightStatusBars = !dark
                            isAppearanceLightNavigationBars = !dark
                        }
                    }
                    Box(Modifier.fillMaxSize()) {
                        AboutPage()
                        // Miuix never mounts its popup host itself; SuperDialog stays
                        // invisible until the host is present exactly once.
                        MiuixPopupUtil.MiuixPopupHost()
                    }
                }
            }
        })
    }

    @Composable
    private fun AboutPage() {
        Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
            SmallTopAppBar(title = getString(R.string.text_about), defaultWindowInsetsPadding = false,
                navigationIcon = { MiuixBackButton(onClick = { finish() }) })
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(Modifier.size(88.dp).clickable {
                            iconClickCount++
                            if (iconClickCount >= 5) {
                                crashShow.value = true
                                iconClickCount = 0
                            }
                        }, contentAlignment = Alignment.Center) {
                            Image(
                                painter = painterResource(R.drawable.ai_js_pro_logo),
                                contentDescription = getString(R.string.app_name),
                                modifier = Modifier.size(88.dp)
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            getString(R.string.app_name),
                            fontSize = 18.sp,
                            color = MiuixTheme.colorScheme.onBackground
                        )
                        Text(
                            "Version ${BuildConfig.VERSION_NAME}",
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }

                Card(Modifier.fillMaxWidth()) {
                    SuperArrow(title = "分享应用",
                        onClick = { IntentUtil.shareText(this@MiuixAboutActivity, getString(R.string.share_app)) })
                }

                Text(getString(R.string.copyright),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 32.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        CrashDialog(show = crashShow,
            onConfirm = { MiuixPopupUtil.dismissDialog(crashShow); crashTest() },
            onDismiss = { if (crashShow.value) MiuixPopupUtil.dismissDialog(crashShow) })
    }

    @Composable
    private fun CrashDialog(show: MutableState<Boolean>, onConfirm: () -> Unit, onDismiss: () -> Unit) {
        SuperDialog(show = show, title = "Crash Test",
            summary = "触发一次测试崩溃（用于验证崩溃上报）", onDismissRequest = onDismiss) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.End) {
                    TextButton(text = "取消", modifier = Modifier.padding(end = 8.dp),
                        onClick = onDismiss, colors = ButtonDefaults.textButtonColorsPrimary())
                    Button(onClick = onConfirm, colors = ButtonDefaults.buttonColorsPrimary()) { Text("崩溃") }
                }
            }
        }
    }

    private fun crashTest() {
        com.tencent.bugly.crashreport.CrashReport.testJavaCrash()
    }
}
