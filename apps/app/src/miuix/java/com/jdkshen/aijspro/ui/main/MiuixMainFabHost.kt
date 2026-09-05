package com.jdkshen.aijspro.ui.main

import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.shape.CircleShape
import com.jdkshen.aijspro.R
import com.jdkshen.aijspro.theme.AijsMiuixTheme
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Visible Miuix FAB; MainActivity's original FAB remains the business-action bridge. */
object MiuixMainFabHost {
    @JvmStatic
    fun createView(host: MainActivity): View {
        val view = ComposeView(host).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AijsMiuixTheme {
                    var expanded by remember { mutableStateOf(false) }
                    Column(horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        AnimatedVisibility(visible = expanded,
                            enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
                            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom)) {
                            Column(horizontalAlignment = Alignment.End,
                                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Action(host, "项目", R.drawable.ic_project, 3) { expanded = false }
                                Action(host, "导入", R.drawable.ic_floating_action_menu_open, 2) { expanded = false }
                                Action(host, "文件", R.drawable.ic_floating_action_menu_file, 1) { expanded = false }
                                Action(host, "文件夹", R.drawable.ic_floating_action_menu_dir, 0) { expanded = false }
                            }
                        }
                        FloatingActionButton(
                            onClick = {
                                // File page: open the create menu. Other pages keep the
                                // original per-page FAB behaviour (stop all / reply, etc.).
                                if (host.isCurrentPageCreateMenu) {
                                    expanded = !expanded
                                } else {
                                    host.performMainFabClickFromMiuix()
                                }
                            },
                            shape = CircleShape,
                            minWidth = 56.dp, minHeight = 56.dp,
                            containerColor = MiuixTheme.colorScheme.primary,
                            shadowElevation = 10f,
                            defaultWindowInsetsPadding = false
                        ) {
                            Image(
                                painter = painterResource(if (expanded) R.drawable.ic_close_white_48dp else R.drawable.ic_menu),
                                contentDescription = if (expanded) "关闭新建菜单" else "打开新建菜单",
                                colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onPrimary)
                            )
                        }
                    }
                }
            }
        }
        // Keep the FAB composable above page content on every tab (CoordinatorLayout z-order).
        view.elevation = 14f
        return view
    }

    @androidx.compose.runtime.Composable
    private fun Action(host: MainActivity, label: String, icon: Int, position: Int,
                       close: () -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(label, fontSize = 14.sp, color = Color.White,
                modifier = Modifier.background(Color(0xB8000000), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                    .padding(horizontal = 9.dp, vertical = 5.dp))
            FloatingActionButton(onClick = {
                host.performMainCreateActionFromMiuix(position)
                close()
            }, shape = CircleShape, minWidth = 48.dp, minHeight = 48.dp,
                containerColor = MiuixTheme.colorScheme.primary,
                shadowElevation = 8f,
                defaultWindowInsetsPadding = false) {
                Image(painterResource(icon), contentDescription = label,
                    colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onPrimary))
            }
        }
    }
}
