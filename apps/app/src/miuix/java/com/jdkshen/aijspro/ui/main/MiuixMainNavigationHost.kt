package com.jdkshen.aijspro.ui.main

import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.TextFieldValue
import com.jdkshen.aijspro.R
import com.jdkshen.aijspro.theme.AijsMiuixTheme
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
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
        val searchShown = remember { mutableStateOf(false) }
        val query = remember { mutableStateOf(TextFieldValue("")) }
        Row(
            Modifier.fillMaxWidth().height(56.dp)
                .background(MiuixTheme.colorScheme.background)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Action(R.drawable.ic_menu_hamburger, "菜单", host::openMainDrawerFromMiuix)
            if (searchShown.value) {
                TextField(value = query.value, onValueChange = {
                    query.value = it
                    host.submitSearchFromMiuix(it.text)
                }, modifier = Modifier.weight(1f).padding(horizontal = 8.dp), singleLine = true)
                Action(R.drawable.ic_close_white_48dp, "关闭搜索") {
                    query.value = TextFieldValue("")
                    host.submitSearchFromMiuix("")
                    searchShown.value = false
                }
            } else {
                Text(
                    text = "AI.js Pro",
                    fontSize = 22.sp,
                    color = MiuixTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f).padding(start = 14.dp)
                )
                Action(R.drawable.ic_search_white_24dp, "搜索") { searchShown.value = true }
                Action(R.drawable.ic_log_white_24dp, "日志", host::openLogFromMiuix)
                Action(R.drawable.ic_bookmark_white_24dp, "文档", host::openDocumentationFromMiuix)
                Action(R.drawable.ic_code_white_24dp, "工作台", host::openImguiFromMiuix)
            }
        }
    }

    @Composable
    private fun Action(icon: Int, description: String, onClick: () -> Unit) {
        Box(Modifier.clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp)) {
            Image(painter = painterResource(icon), contentDescription = description,
                colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onBackgroundVariant),
                modifier = Modifier.align(Alignment.Center))
        }
    }
}
