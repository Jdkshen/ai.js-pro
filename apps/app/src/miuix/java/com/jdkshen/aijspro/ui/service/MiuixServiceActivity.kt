package com.jdkshen.aijspro.ui.service

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsControllerCompat
import com.jdkshen.aijspro.BuildConfig
import com.jdkshen.aijspro.Pref
import com.jdkshen.aijspro.external.foreground.ForegroundService
import com.jdkshen.aijspro.theme.AijsMiuixTheme
import com.jdkshen.aijspro.theme.isAijsDarkTheme
import com.jdkshen.aijspro.tool.AccessibilityServiceTool
import com.jdkshen.aijspro.ui.floating.FloatyWindowManger
import com.stardust.notification.NotificationListenerService
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.theme.*

/** Isolated pilot: existing service owners remain responsible for actual state. */
class MiuixServiceActivity : ComponentActivity() {
    private var revision by mutableIntStateOf(0)

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
                    ServicePage()
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        revision++
    }

    private fun openSettings(action: String, packageUri: Boolean = false) {
        try {
            startActivity(Intent(action).apply {
                if (packageUri) data = Uri.parse("package:$packageName")
            })
        } catch (e: android.content.ActivityNotFoundException) {
            Toast.makeText(this, "系统未提供此设置入口", Toast.LENGTH_SHORT).show()
        }
    }

    @Composable
    private fun ServicePage() {
        val stateRevision = revision
        val accessibility = remember(stateRevision) {
            AccessibilityServiceTool.isAccessibilityServiceEnabled(this)
        }
        val floating = remember(stateRevision) {
            FloatyWindowManger.isCircularMenuShowing() || Pref.isFloatingMenuShown()
        }
        val foreground = remember(stateRevision) { Pref.isForegroundServiceEnabled() }
        val notification = remember(stateRevision) {
            NotificationListenerService.instance != null
        }
        val battery = remember(stateRevision) {
            getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        }
        Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
            TopAppBar(title = "核心服务", defaultWindowInsetsPadding = false,
                navigationIcon = { TextButton(text = "返回", modifier = Modifier.padding(start = 12.dp),
                    onClick = { finish() }) })
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("管理脚本运行所需的系统服务", fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier.padding(top = 8.dp, start = 8.dp))
                Card(Modifier.fillMaxWidth()) {
                    SuperArrow(title = "无障碍服务", summary = "用于自动点击、查找控件和界面操作",
                        rightText = if (accessibility) "已开启" else "未开启",
                        onClick = { openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) })
                    SuperSwitch(title = "悬浮窗", summary = "显示脚本控制按钮", checked = floating,
                        onCheckedChange = { enabled ->
                            if (enabled && !Settings.canDrawOverlays(this@MiuixServiceActivity)) {
                                openSettings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, true)
                            } else {
                                if (enabled) FloatyWindowManger.showCircularMenuIfNeeded()
                                else FloatyWindowManger.hideCircularMenu()
                                Pref.setFloatingMenuShown(enabled)
                            }
                            revision++
                        })
                    SuperSwitch(title = "前台服务", summary = "通过常驻通知保持脚本运行", checked = foreground,
                        onCheckedChange = { enabled ->
                            if (enabled) ForegroundService.start(this@MiuixServiceActivity)
                            else ForegroundService.stop(this@MiuixServiceActivity)
                            Pref.setForegroundServiceEnabled(enabled)
                            revision++
                        })
                }
                Text("系统设置", modifier = Modifier.padding(start = 8.dp))
                Card(Modifier.fillMaxWidth()) {
                    SuperArrow(title = "通知读取权限", summary = "允许脚本监听系统通知",
                        rightText = if (notification) "已开启" else "未开启",
                        onClick = { openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) })
                    SuperArrow(title = "电池优化", summary = "减少后台运行受到的限制",
                        rightText = if (battery) "不受限制" else "系统管理",
                        onClick = { openSettings(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) })
                }
                Text("AI.js Pro · ${BuildConfig.VERSION_NAME}",
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier.padding(start = 8.dp, bottom = 32.dp))
            }
        }
    }
}
