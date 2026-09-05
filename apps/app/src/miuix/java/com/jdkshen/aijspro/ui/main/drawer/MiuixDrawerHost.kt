package com.jdkshen.aijspro.ui.main.drawer

import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.extra.SuperSwitch
import com.jdkshen.aijspro.theme.AijsMiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Miuix drawer content, loaded via reflection from [DrawerFragment] only when
 * BuildConfig.MIUIX_PILOT is true. Lives in the miuix flavor source set so the
 * common/coolapk variants never reference Miuix.
 */
object MiuixDrawerHost {

    @JvmStatic
    fun createView(host: DrawerFragment): View {
        val refresh = mutableIntStateOf(0)
        val context = host.requireContext()
        val density = context.resources.displayMetrics.density
        val composeView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            layoutParams = ViewGroup.LayoutParams(
                (304 * density).toInt(), ViewGroup.LayoutParams.MATCH_PARENT)
            setTag(Runnable { refresh.intValue = refresh.intValue + 1 })
        }
        composeView.setContent {
            AijsMiuixTheme {
                DrawerContent(host, refresh)
            }
        }
        return composeView
    }

    @Composable
    private fun DrawerContent(host: DrawerFragment, refresh: MutableIntState) {
        val rev = refresh.intValue
        val userName = remember(rev) { host.drawerUserName ?: "" }
        val accessibility = remember(rev) { host.isAccessibilityEnabled }
        val stableMode = remember(rev) { host.isStableModeEnabled }
        val notification = remember(rev) { host.isNotificationEnabled }
        val foreground = remember(rev) { host.isForegroundServicePrefEnabled }
        val usageStats = remember(rev) { host.isUsageStatsEnabled }
        val floating = remember(rev) { host.isFloatingWindowShowing }
        val volumeControl = remember(rev) { host.isVolumeDownControlEnabled }
        val nightMode = remember(rev) { host.isNightModePrefEnabled }
        val connected = remember(rev) { host.isRemoteConnected }

        Column(
            Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)
        ) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (userName.isEmpty()) "未登录" else userName,
                            fontSize = 17.sp,
                            color = MiuixTheme.colorScheme.onBackground,
                            modifier = Modifier.weight(1f).padding(start = 16.dp)
                        )
                        TextButton(text = if (userName.isEmpty()) "登录" else "我的主页",
                            modifier = Modifier.padding(end = 8.dp),
                            onClick = { host.openUserArea() },
                            colors = ButtonDefaults.textButtonColorsPrimary())
                    }
                }

                SmallTitle("服务")
                Card(Modifier.fillMaxWidth()) {
                    SuperArrow(title = "核心服务", summary = "管理脚本运行所需的系统服务",
                        onClick = { host.openServiceStatus() })
                    SuperSwitch(title = "无障碍服务", summary = "自动点击、查找控件和界面操作",
                        checked = accessibility,
                        onCheckedChange = { host.setAccessibilityEnabled(it); refresh.intValue++ })
                    SuperSwitch(title = "稳定模式", summary = "布局分析更稳定，部分脚本可能受影响",
                        checked = stableMode,
                        onCheckedChange = { host.setStableModeEnabled(it); refresh.intValue++ })
                    SuperSwitch(title = "通知读取权限", summary = "允许脚本监听系统通知",
                        checked = notification,
                        onCheckedChange = { host.openNotificationSettings(it); refresh.intValue++ })
                    SuperSwitch(title = "前台服务", summary = "通过常驻通知保持脚本运行",
                        checked = foreground,
                        onCheckedChange = { host.setForegroundServiceEnabled(it); refresh.intValue++ })
                    SuperSwitch(title = "查看使用统计权限", summary = "获取其他应用的使用情况",
                        checked = usageStats,
                        onCheckedChange = { host.openUsageStats(it); refresh.intValue++ })
                }

                SmallTitle("录制脚本")
                Card(Modifier.fillMaxWidth()) {
                    SuperSwitch(title = "悬浮窗", summary = "显示脚本控制按钮",
                        checked = floating,
                        onCheckedChange = { host.setFloatingWindowEnabled(it); refresh.intValue++ })
                    SuperSwitch(title = "音量下键控制", summary = "音量下键开始或停止脚本录制",
                        checked = volumeControl,
                        onCheckedChange = { host.setVolumeDownControlEnabled(it); refresh.intValue++ })
                }

                SmallTitle("其他")
                Card(Modifier.fillMaxWidth()) {
                    SuperArrow(title = "连接远程", summary = "连接电脑上的开发者插件",
                        rightText = if (connected) "已连接" else "未连接",
                        onClick = {
                            if (connected) host.disconnectRemote() else host.openRemoteConnection()
                        })
                    SuperArrow(title = "主题色",
                        onClick = { host.openThemeColorSettingsFromDrawer() })
                    SuperSwitch(title = "夜间模式", summary = "切换深色界面",
                        checked = nightMode,
                        onCheckedChange = { host.setNightModePrefEnabled(it); refresh.intValue++ })
                    SuperArrow(title = "检查更新",
                        onClick = { host.checkForUpdatesFromDrawer() })
                }
                Text("AI.js Pro · ${com.jdkshen.aijspro.BuildConfig.VERSION_NAME}",
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 8.dp, bottom = 24.dp))
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(onClick = { host.openSettingsFromDrawer() }, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary()) {
                    Text("设置")
                }
                Button(onClick = { host.exitAppFromDrawer() }, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary()) {
                    Text("退出")
                }
            }
        }
    }
}
