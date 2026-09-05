package com.jdkshen.aijspro.ui.main.drawer

import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jdkshen.aijspro.R
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.icons.ArrowRight
import top.yukonga.miuix.kmp.icon.icons.Settings
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
        // Local optimistic state: tapping a switch flips it immediately, the real
        // host state is re-read (and saved by host.setXxx) on every onResume/refresh.
        val userName = remember { mutableStateOf(host.drawerUserName ?: "") }
        val accessibility = remember { mutableStateOf(host.isAccessibilityEnabled) }
        val stableMode = remember { mutableStateOf(host.isStableModeEnabled) }
        val notification = remember { mutableStateOf(host.isNotificationEnabled) }
        val foreground = remember { mutableStateOf(host.isForegroundServicePrefEnabled) }
        val usageStats = remember { mutableStateOf(host.isUsageStatsEnabled) }
        val floating = remember { mutableStateOf(host.isFloatingWindowShowing) }
        val volumeControl = remember { mutableStateOf(host.isVolumeDownControlEnabled) }
        val nightMode = remember { mutableStateOf(host.isNightModePrefEnabled) }
        val followSystem = remember { mutableStateOf(host.isFollowSystemEnabled) }
        val connected = remember { mutableStateOf(host.isRemoteConnected) }
        val moreOpen = remember { mutableStateOf(false) }
        val themeOpen = remember { mutableStateOf(true) }
        LaunchedEffect(rev) {
            userName.value = host.drawerUserName ?: ""
            accessibility.value = host.isAccessibilityEnabled
            stableMode.value = host.isStableModeEnabled
            notification.value = host.isNotificationEnabled
            foreground.value = host.isForegroundServicePrefEnabled
            usageStats.value = host.isUsageStatsEnabled
            floating.value = host.isFloatingWindowShowing
            volumeControl.value = host.isVolumeDownControlEnabled
            nightMode.value = host.isNightModePrefEnabled
            followSystem.value = host.isFollowSystemEnabled
            connected.value = host.isRemoteConnected
        }

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
                            if (userName.value.isEmpty()) "未登录" else userName.value,
                            fontSize = 17.sp,
                            color = MiuixTheme.colorScheme.onBackground,
                            modifier = Modifier.weight(1f).padding(start = 16.dp)
                        )
                        TextButton(text = if (userName.value.isEmpty()) "登录" else "我的主页",
                            modifier = Modifier.padding(end = 8.dp),
                            onClick = { host.openUserArea() },
                            colors = ButtonDefaults.textButtonColorsPrimary())
                    }
                }

                SmallTitle("服务")
                Card(Modifier.fillMaxWidth()) {
                    SuperSwitch(title = "无障碍服务", summary = "自动点击、查找控件和界面操作",
                        checked = accessibility.value,
                        onCheckedChange = { accessibility.value = it; host.setAccessibilityEnabled(it) })
                    SuperSwitch(title = "悬浮窗", summary = "显示脚本控制按钮",
                        checked = floating.value,
                        onCheckedChange = { floating.value = it; host.setFloatingWindowEnabled(it) })
                    BasicComponent(
                        title = "更多...",
                        onClick = { moreOpen.value = !moreOpen.value },
                        rightActions = {
                            Icon(
                                imageVector = MiuixIcons.ArrowRight,
                                contentDescription = null,
                                modifier = Modifier.graphicsLayer {
                                    rotationZ = if (moreOpen.value) -90f else 90f
                                },
                                tint = MiuixTheme.colorScheme.onSurfaceVariantActions
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                    )
                }
                if (moreOpen.value) {
                    Card(Modifier.fillMaxWidth()) {
                        SuperArrow(title = "核心服务", summary = "管理脚本运行所需的系统服务",
                            onClick = { host.openServiceStatus() })
                        SuperSwitch(title = "稳定模式", summary = "布局分析更稳定，部分脚本可能受影响",
                            checked = stableMode.value,
                            onCheckedChange = { stableMode.value = it; host.setStableModeEnabled(it) })
                        SuperSwitch(title = "通知读取权限", summary = "允许脚本监听系统通知",
                            checked = notification.value,
                            onCheckedChange = { notification.value = it; host.openNotificationSettings(it) })
                        SuperSwitch(title = "前台服务", summary = "通过常驻通知保持脚本运行",
                            checked = foreground.value,
                            onCheckedChange = { foreground.value = it; host.setForegroundServiceEnabled(it) })
                        SuperSwitch(title = "查看使用统计权限", summary = "获取其他应用的使用情况",
                            checked = usageStats.value,
                            onCheckedChange = { usageStats.value = it; host.openUsageStats(it) })
                        SuperSwitch(title = "音量下键控制", summary = "音量下键开始或停止脚本录制",
                            checked = volumeControl.value,
                            onCheckedChange = { volumeControl.value = it; host.setVolumeDownControlEnabled(it) })
                    }
                }

                SmallTitle("开发")
                Card(Modifier.fillMaxWidth()) {
                    SuperArrow(title = "开发者调试", summary = "连接电脑上的开发者插件",
                        rightText = if (connected.value) "已连接" else "未连接",
                        onClick = {
                            if (connected.value) host.disconnectRemote() else host.openRemoteConnection()
                        })
                    SuperArrow(title = "终端",
                        onClick = { host.openTerminal() })
                }

                SmallTitle("其他")
                Card(Modifier.fillMaxWidth()) {
                    BasicComponent(
                        title = "主题",
                        onClick = { themeOpen.value = !themeOpen.value },
                        rightActions = {
                            Icon(
                                imageVector = MiuixIcons.ArrowRight,
                                contentDescription = null,
                                modifier = Modifier.graphicsLayer {
                                    rotationZ = if (themeOpen.value) -90f else 90f
                                },
                                tint = MiuixTheme.colorScheme.onSurfaceVariantActions
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                    )
                }
                if (themeOpen.value) {
                    Card(Modifier.fillMaxWidth()) {
                        SuperSwitch(title = "跟随系统", summary = "深浅色跟随系统外观",
                            checked = followSystem.value,
                            onCheckedChange = {
                                followSystem.value = it
                                host.setFollowSystemEnabled(it)
                                com.jdkshen.aijspro.theme.refreshMiuixFollowSystemTheme()
                            })
                        SuperSwitch(title = "暗色主题", summary = "手动切换深色界面",
                            checked = nightMode.value,
                            enabled = !followSystem.value,
                            onCheckedChange = {
                                nightMode.value = it
                                host.setNightModePrefEnabled(it)
                                com.jdkshen.aijspro.theme.refreshMiuixDarkTheme()
                            })
                    }
                }
                BasicComponent(
                    title = "设置",
                    leftAction = {
                        Icon(
                            imageVector = MiuixIcons.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MiuixTheme.colorScheme.onBackgroundVariant
                        )
                        Spacer(Modifier.width(12.dp))
                    },
                    onClick = { host.openSettingsFromDrawer() }
                )
                BasicComponent(
                    title = "检查更新",
                    leftAction = {
                        Image(
                            painter = painterResource(R.drawable.ic_check_for_updates),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onBackgroundVariant)
                        )
                        Spacer(Modifier.width(12.dp))
                    },
                    onClick = { host.checkForUpdatesFromDrawer() }
                )
                BasicComponent(
                    title = "退出",
                    leftAction = {
                        Image(
                            painter = painterResource(R.drawable.ic_ali_exit),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onBackgroundVariant)
                        )
                        Spacer(Modifier.width(12.dp))
                    },
                    onClick = { host.exitAppFromDrawer() }
                )
                Text("AI.js Pro · ${com.jdkshen.aijspro.BuildConfig.VERSION_NAME}",
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                    textAlign = TextAlign.Center)
            }
        }
    }
}
