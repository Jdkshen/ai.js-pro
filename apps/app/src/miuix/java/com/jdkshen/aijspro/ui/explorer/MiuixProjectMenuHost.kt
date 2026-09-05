package com.jdkshen.aijspro.ui.explorer

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.jdkshen.aijspro.theme.AijsMiuixTheme
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Project operation panel backed by the original project launcher/build/config logic. */
object MiuixProjectMenuHost {
    @JvmStatic
    fun show(owner: ExplorerView) {
        val dialog = Dialog(owner.context)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val actions = listOf(
            "运行项目" to owner::runCurrentProjectFromMiuix,
            "构建 APK" to owner::buildCurrentProjectFromMiuix,
            "同步项目" to owner::syncCurrentProjectFromMiuix,
            "项目设置" to owner::editCurrentProjectFromMiuix
        )
        dialog.setContentView(ComposeView(owner.context).apply {
            setViewTreeLifecycleOwner(owner.findViewTreeLifecycleOwner())
            setViewTreeSavedStateRegistryOwner(owner.findViewTreeSavedStateRegistryOwner())
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                AijsMiuixTheme {
                    Card(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                            Text("项目操作", fontSize = 20.sp,
                                color = MiuixTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                            actions.forEach { (label, action) ->
                                Text(label, fontSize = 16.sp,
                                    color = MiuixTheme.colorScheme.onSurface,
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        action()
                                        dialog.dismiss()
                                    }.padding(horizontal = 20.dp, vertical = 15.dp))
                            }
                        }
                    }
                }
            }
        })
        dialog.show()
        dialog.window?.setLayout(
            (owner.resources.displayMetrics.widthPixels * 0.88f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
}
