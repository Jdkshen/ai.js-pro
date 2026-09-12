package com.jdkshen.aijspro.ui.update

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jdkshen.aijspro.theme.AijsMiuixTheme
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Miuix 版「下载更新」进度对话框：圆角卡片 + 青色进度条 + 取消按钮，与
 * [MiuixUpdateDialog] 及设置页保持同一套视觉。
 *
 * main 侧的 DownloadManager 在 MIUIX_PILOT 时通过反射调用这里（避免 main 编译期依赖
 * miuix flavor）。用法：`show(...)` 返回句柄，随后用 `setProgress(handle, 0..100)` 更新、
 * `dismiss(handle)` 关闭；任一步失败时 main 会回退到原来的 Material 进度对话框。
 */
object MiuixDownloadProgressDialog {

    /** 对话框句柄：持有 Compose 进度状态，供反射方更新进度/关闭。 */
    @Suppress("MemberVisibilityCanBePrivate")
    class Handle internal constructor(
        internal val dialog: ComponentDialog,
        internal val progressState: MutableIntState
    )

    @JvmStatic
    fun show(activity: Activity?, title: String, fileName: String, onCancel: Runnable): Any? {
        if (activity == null || activity.isFinishing) return null
        // 与 MiuixUpdateDialog 同理：ComposeView 进入 Dialog 的视图树需要
        // ViewTreeLifecycleOwner，必须挂在 ComponentActivity 上（ComponentDialog 会自己注册）。
        val host = activity as? ComponentActivity ?: return null
        val dialog = ComponentDialog(host)
        val progress = mutableIntStateOf(0)
        val content = ComposeView(host).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AijsMiuixTheme {
                    Column(
                        Modifier.fillMaxWidth()
                            .padding(horizontal = 22.dp, vertical = 30.dp)
                            .clip(RoundedCornerShape(26.dp))
                            .background(MiuixTheme.colorScheme.surface)
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(title, fontSize = 20.sp, color = MiuixTheme.colorScheme.onSurface)
                        Text(
                            fileName,
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.weight(1f).height(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MiuixTheme.colorScheme.onSurfaceSecondary.copy(alpha = 0.15f))
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxWidth(progress.intValue / 100f)
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(MiuixTheme.colorScheme.primary)
                                )
                            }
                            Text(
                                "${progress.intValue}%",
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                                modifier = Modifier.padding(start = 12.dp)
                            )
                        }
                        Box(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(MiuixTheme.colorScheme.onSurfaceSecondary.copy(alpha = 0.12f))
                                .clickable {
                                    onCancel.run()
                                    dialog.dismiss()
                                }
                                .padding(vertical = 13.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("取消下载", fontSize = 16.sp, color = MiuixTheme.colorScheme.onSurface)
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
        // 下载中不允许返回键/点击外部关闭（与旧版 cancelable(false) 行为一致），只能点「取消下载」。
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        return Handle(dialog, progress)
    }

    @JvmStatic
    fun setProgress(handle: Any?, progress: Int) {
        (handle as? Handle)?.progressState?.intValue = progress.coerceIn(0, 100)
    }

    @JvmStatic
    fun dismiss(handle: Any?) {
        (handle as? Handle)?.dialog?.dismiss()
    }
}
