package com.jdkshen.aijspro.ui.update

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jdkshen.aijspro.theme.AijsMiuixTheme
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Miuix 版「正在检查更新」提示框：圆角卡片 + 文字后面的三点循环（“正在检查更新…”）。
 *
 * 取代旧的 Material 进度框——它会在网络很快时一闪而过，而且跟 Miuix 界面不搭。
 * main 侧的 UpdateCheckDialog 通过反射调用这里（避免 main 编译期依赖 miuix flavor），
 * 用法：`show(activity, text)` 返回句柄，检查结束后 `dismiss(handle)`；失败自动回退 Material。
 */
object MiuixUpdateCheckDialog {

    class Handle internal constructor(internal val dialog: ComponentDialog)

    @JvmStatic
    fun show(activity: Activity?, text: String): Any? {
        if (activity == null || activity.isFinishing) return null
        // 同 MiuixUpdateDialog：ComposeView 需要 ViewTreeLifecycleOwner，必须挂在 ComponentActivity 上。
        val host = activity as? ComponentActivity ?: return null
        val dialog = ComponentDialog(host)
        val content = ComposeView(host).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AijsMiuixTheme {
                    var dots by remember { mutableIntStateOf(1) }
                    LaunchedEffect(Unit) {
                        while (true) {
                            delay(380)
                            dots = dots % 3 + 1
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth()
                            .padding(horizontal = 34.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(MiuixTheme.colorScheme.surface)
                            .padding(horizontal = 24.dp, vertical = 18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text + ".".repeat(dots),
                            fontSize = 17.sp,
                            color = MiuixTheme.colorScheme.onSurface
                        )
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
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        return Handle(dialog)
    }

    @JvmStatic
    fun dismiss(handle: Any?) {
        (handle as? Handle)?.dialog?.dismiss()
    }
}
