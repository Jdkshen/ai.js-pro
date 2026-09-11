package com.jdkshen.aijspro.ui.update

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.preference.PreferenceManager
import android.view.ViewGroup
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jdkshen.aijspro.R
import com.jdkshen.aijspro.theme.AijsMiuixTheme
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Miuix 版「发现新版本」对话框：圆角卡片 + 青色圆角按钮，与设置/管理页同一套视觉。
 * main 侧的 UpdateInfoDialogBuilder 在 MIUIX_PILOT 时通过反射调用这里（避免 main 编译期依赖 miuix flavor）。
 */
object MiuixUpdateDialog {

    @JvmStatic
    fun show(
        activity: Activity?,
        title: String,
        releaseNotes: String?,
        versionCode: Int,
        canDoNotAskAgain: Boolean,
        doNotAskPrefKey: String,
        onDirectDownload: Runnable,
        historyTitles: Array<String>?,
        historyNotes: Array<String>?
    ): Boolean {
        if (activity == null || activity.isFinishing) return false
        // 必须挂在 ComponentActivity 上：ComposeView 进入 Dialog 的视图树时需要
        // ViewTreeLifecycleOwner（ComponentDialog 会自己注册），裸 android.app.Dialog
        // 没有这个 Owner，ComposeView attach 时会直接崩。
        val host = activity as? ComponentActivity ?: return false
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(host)
        val dialog = ComponentDialog(host)
        val content = ComposeView(host).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AijsMiuixTheme {
                    var doNotAsk by remember { mutableStateOf(false) }
                    Column(
                        Modifier.fillMaxWidth()
                            .padding(horizontal = 22.dp, vertical = 30.dp)
                            .clip(RoundedCornerShape(26.dp))
                            .background(MiuixTheme.colorScheme.surface)
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(title, fontSize = 20.sp, color = MiuixTheme.colorScheme.onSurface)
                        if (!releaseNotes.isNullOrBlank()) {
                            Text(
                                releaseNotes,
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                                modifier = Modifier
                                    .heightIn(max = 260.dp)
                                    .verticalScroll(rememberScrollState())
                            )
                        }
                        // 更新历史：默认收起（弹窗别一上来就很长），展开后可看历次改动。
                        val historyCount = historyTitles?.size ?: 0
                        if (historyCount > 0) {
                            var historyExpanded by remember { mutableStateOf(false) }
                            Row(
                                Modifier.fillMaxWidth().clickable { historyExpanded = !historyExpanded },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    host.getString(R.string.text_update_history_count, historyCount),
                                    fontSize = 14.sp,
                                    color = MiuixTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    host.getString(
                                        if (historyExpanded) R.string.text_collapse else R.string.text_expand
                                    ),
                                    fontSize = 13.sp,
                                    color = MiuixTheme.colorScheme.primary
                                )
                            }
                            if (historyExpanded) {
                                Column(
                                    Modifier.fillMaxWidth()
                                        .heightIn(max = 240.dp)
                                        .verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    historyTitles?.forEachIndexed { index, entryTitle ->
                                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(
                                                entryTitle,
                                                fontSize = 13.sp,
                                                color = MiuixTheme.colorScheme.onSurface
                                            )
                                            val note = historyNotes?.getOrNull(index).orEmpty()
                                            if (note.isNotBlank()) {
                                                Text(
                                                    note,
                                                    fontSize = 12.sp,
                                                    color = MiuixTheme.colorScheme.onSurfaceSecondary
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (canDoNotAskAgain) {
                            Row(
                                Modifier.fillMaxWidth().clickable { doNotAsk = !doNotAsk },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(doNotAsk, { doNotAsk = it })
                                Text("不再提示此版本", fontSize = 14.sp)
                            }
                        }
                        // 主按钮：直接下载
                        Box(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(MiuixTheme.colorScheme.primary)
                                .clickable {
                                    if (canDoNotAskAgain && doNotAsk) {
                                        prefs.edit().putBoolean(doNotAskPrefKey + versionCode, true).apply()
                                    }
                                    onDirectDownload.run()
                                    dialog.dismiss()
                                }
                                .padding(vertical = 13.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("直接下载", fontSize = 16.sp, color = MiuixTheme.colorScheme.onPrimary)
                        }
                        // 次按钮：稍后再说
                        Box(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(MiuixTheme.colorScheme.onSurfaceSecondary.copy(alpha = 0.12f))
                                .clickable {
                                    if (canDoNotAskAgain && doNotAsk) {
                                        prefs.edit().putBoolean(doNotAskPrefKey + versionCode, true).apply()
                                    }
                                    dialog.dismiss()
                                }
                                .padding(vertical = 13.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("稍后再说", fontSize = 16.sp, color = MiuixTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(content)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        return true
    }
}
