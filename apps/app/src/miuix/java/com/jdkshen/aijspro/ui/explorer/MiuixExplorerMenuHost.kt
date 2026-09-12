package com.jdkshen.aijspro.ui.explorer

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.WindowManager
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.jdkshen.aijspro.theme.AijsMiuixTheme
import com.jdkshen.aijspro.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Miuix replacement for the explorer's legacy anchored PopupMenu. */
object MiuixExplorerMenuHost {

    /** 旧入口（旧列表用）：动作直接回给 ExplorerView。 */
    @JvmStatic
    fun show(owner: ExplorerView, ids: IntArray, labels: Array<String>, title: String) {
        showFor(owner.context as android.app.Activity, ids, labels, title) { id ->
            owner.performExplorerActionFromMiuix(id)
        }
    }

    /**
     * 不依赖 ExplorerView 的版本：Compose 列表长按后直接拿回调分发动作。
     * 生命周期挂到宿主 Activity（ComponentActivity 同时是 LifecycleOwner 与 SavedStateRegistryOwner）。
     */
    @JvmStatic
    fun showFor(
        activity: android.app.Activity,
        ids: IntArray,
        labels: Array<String>,
        title: String,
        onAction: (Int) -> Unit
    ) {
        val dialog = Dialog(activity)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setContentView(ComposeView(activity).apply {
            val lifecycleOwner = activity as? androidx.lifecycle.LifecycleOwner
            val savedStateOwner = activity as? androidx.savedstate.SavedStateRegistryOwner
            if (lifecycleOwner != null) setViewTreeLifecycleOwner(lifecycleOwner)
            if (savedStateOwner != null) setViewTreeSavedStateRegistryOwner(savedStateOwner)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                AijsMiuixTheme {
                    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
                        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                            .navigationBarsPadding().padding(vertical = 8.dp)) {
                            Text(title, fontSize = 18.sp,
                                color = MiuixTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                            labels.forEachIndexed { index, label ->
                                Text(label, fontSize = 16.sp,
                                    color = if (ids[index] == R.id.delete) ComposeColor(0xFFE5484D)
                                    else MiuixTheme.colorScheme.onSurface,
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        onAction(ids[index])
                                        dialog.dismiss()
                                    }.padding(horizontal = 20.dp, vertical = 14.dp))
                            }
                        }
                    }
                }
            }
        })
        dialog.show()
        dialog.window?.apply {
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.42f }
            setLayout((activity.resources.displayMetrics.widthPixels * 0.94f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }
}
