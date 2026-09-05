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

/** Miuix replacement for the explorer's legacy anchored PopupMenu. */
object MiuixExplorerMenuHost {
    @JvmStatic
    fun show(owner: ExplorerView, ids: IntArray, labels: Array<String>, title: String) {
        val dialog = Dialog(owner.context)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setContentView(ComposeView(owner.context).apply {
            setViewTreeLifecycleOwner(owner.findViewTreeLifecycleOwner())
            setViewTreeSavedStateRegistryOwner(owner.findViewTreeSavedStateRegistryOwner())
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                AijsMiuixTheme {
                    Card(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                            Text(title, fontSize = 18.sp,
                                color = MiuixTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                            labels.forEachIndexed { index, label ->
                                Text(label, fontSize = 16.sp,
                                    color = MiuixTheme.colorScheme.onSurface,
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        owner.performExplorerActionFromMiuix(ids[index])
                                        dialog.dismiss()
                                    }.padding(horizontal = 20.dp, vertical = 14.dp))
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
