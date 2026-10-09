package com.jdkshen.aijspro.ui.common

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import com.jdkshen.aijspro.theme.AijsMiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 独立 Miuix 弹窗宿主：把 Compose 内容挂进无标题、透明背景的 [ComponentDialog] 窗口。
 * 视觉与 [com.jdkshen.aijspro.ui.update.MiuixUpdateDialog]（更新弹窗）保持一致：
 * 圆角卡片 + Miuix 配色，深浅色自动跟随。
 *
 * 与更新弹窗同理：ComposeView 进入 Dialog 的视图树需要 ViewTreeLifecycleOwner，
 * 宿主必须是 [ComponentActivity]；否则返回 null，调用方回退旧的 Material 实现。
 */
internal object MiuixDialogHost {

    fun showCard(activity: Activity?, content: @Composable (ComponentDialog) -> Unit): ComponentDialog? {
        if (activity == null || activity.isFinishing || activity.isDestroyed) return null
        val host = activity as? ComponentActivity ?: return null
        val dialog = ComponentDialog(host)
        val compose = ComposeView(host).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { AijsMiuixTheme { content(dialog) } }
        }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(compose)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        return dialog
    }
}

/** 独立弹窗统一的圆角卡片容器（与更新弹窗同一套内边距/圆角）。 */
@Composable
internal fun MiuixDialogCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 30.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(MiuixTheme.colorScheme.surface)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content
    )
}
