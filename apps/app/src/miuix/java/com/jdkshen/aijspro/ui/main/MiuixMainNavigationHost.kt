package com.jdkshen.aijspro.ui.main

import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jdkshen.aijspro.R
import com.jdkshen.aijspro.theme.AijsMiuixTheme
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Miuix navigation shell; the existing ViewPager/RecyclerView remains the content owner. */
object MiuixMainNavigationHost {
    @JvmStatic
    fun createView(host: MainActivity): View = ComposeView(host).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent { AijsMiuixTheme { MainBar(host) } }
    }

    @Composable
    private fun MainBar(host: MainActivity) {
        var showSearch by remember { mutableStateOf(false) }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .background(MiuixTheme.colorScheme.background)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Action(R.drawable.ic_menu_hamburger, "菜单", host::openMainDrawerFromMiuix)
            Text(
                text = "AI.js Pro",
                fontSize = 22.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MiuixTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f).padding(start = 14.dp)
            )
            Action(R.drawable.ic_log_white_24dp, "日志", host::openLogFromMiuix)
            Action(R.drawable.ic_bookmark_white_24dp, "文档", host::openDocumentationFromMiuix)
            Action(R.drawable.ic_search_white_24dp, "搜索") {
                if (!host.openCurrentPageSearchFromMiuix()) showSearch = true
            }
        }
        if (showSearch) {
            Dialog(
                onDismissRequest = { showSearch = false },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                MiuixSearchOverlayContent(
                    onDismiss = { showSearch = false },
                    onItemSelected = { file ->
                        showSearch = false
                        host.revealScriptFileFromMiuix(file.absolutePath)
                    }
                )
            }
        }
    }

    @Composable
    private fun Action(icon: Int, description: String, onClick: () -> Unit) {
        // 48dp minimum touch target; the old padding gave only 48x44dp, and a
        // larger system font scale made the bar taller while the target stayed put.
        Box(
            Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Image(painter = painterResource(icon), contentDescription = description,
                colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onBackgroundVariant),
                modifier = Modifier.size(24.dp))
        }
    }
}
