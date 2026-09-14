package com.jdkshen.aijspro.ui.common

import android.app.Activity
import android.preference.PreferenceManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jdkshen.aijspro.R
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Miuix 版「提示」弹窗（单键确认，可带「不再提示」勾选）。
 *
 * pref 语义与 [NotAskAgainDialog] 完全一致：key 存「是否需要提醒」，默认 true；
 * 勾选「不再提示」并确认后写入 false，之后不再弹出——老 key 直接继续生效，无需迁移。
 *
 * 返回 true = 已由 Miuix 分支处理（含因「不再提示」而跳过显示）；
 * false = 宿主不是 ComponentActivity，调用方回退旧 Material 实现。
 */
object MiuixHintDialog {

    @JvmStatic
    fun show(
        activity: Activity?,
        title: String,
        message: String,
        confirmText: String,
        remindPrefKey: String?
    ): Boolean {
        if (remindPrefKey != null && activity != null) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
            if (!prefs.getBoolean(remindPrefKey, true)) return true
        }
        val dialog = MiuixDialogHost.showCard(activity) { dlg ->
            var doNotAsk by remember { mutableStateOf(false) }
            MiuixDialogCard {
                Text(title, fontSize = 20.sp, color = MiuixTheme.colorScheme.onSurface)
                Text(message, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                if (remindPrefKey != null && activity != null) {
                    Row(
                        Modifier.fillMaxWidth().clickable { doNotAsk = !doNotAsk },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(doNotAsk, { doNotAsk = it })
                        Text(activity.getString(R.string.text_do_not_remind_again), fontSize = 14.sp)
                    }
                }
                Box(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MiuixTheme.colorScheme.primary)
                        .clickable {
                            if (remindPrefKey != null && doNotAsk && activity != null) {
                                PreferenceManager.getDefaultSharedPreferences(activity)
                                    .edit().putBoolean(remindPrefKey, false).apply()
                            }
                            dlg.dismiss()
                        }
                        .padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(confirmText, fontSize = 16.sp, color = MiuixTheme.colorScheme.onPrimary)
                }
            }
        }
        return dialog != null
    }
}
