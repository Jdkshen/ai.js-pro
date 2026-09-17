package com.jdkshen.aijspro.ui.widget

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 悬浮窗 / 弹窗里放 Compose 的公共底座：Recomposer、生命周期宿主，以及「固定尺寸包装」。
 *
 * 这些坑都是真机（Mi8 / dev-621）踩出来的，换别的地方用 Compose-in-window 时照抄即可：
 *  1. `ComposeView` 在**挂到窗口之前被 measure** 会去要 `windowRecomposer`，拿不到直接抛
 *     `IllegalStateException: Cannot locate windowRecomposer; … is not attached to a window`。
 *     而调用方经常要先量出尺寸再决定位置 → 用 [createView]：外层自己回答固定尺寸，
 *     只在 attach 之后才去量 Compose 子树。
 *  2. attach 时 Compose 还会强校验 `ViewTreeSavedStateRegistryOwner`（缺了抛
 *     `Composed into the View which doesn't propagateViewTreeSavedStateRegistryOwner!`），
 *     而 PopupWindow 的根是系统建的 `PopupDecorView`、往上找不到 owner →
 *     这里显式 `setParentCompositionContext` + 自带 owner。
 *  3. 自带 owner 里 `SavedStateRegistryController` 必须 `performAttach()` → `performRestore(null)` →
 *     **最后**才把 lifecycle 置 RESUMED，顺序错会抛
 *     `Restarter must be created only during owner's initialization stage`。
 */
internal object FloatyCompose {

    /** 进程级 Recomposer：悬浮窗没有 Activity 可依赖，菜单又会反复开合，重建不划算。 */
    private val recomposer: Recomposer by lazy {
        val dispatcher = AndroidUiDispatcher.Main
        val instance = Recomposer(dispatcher)
        CoroutineScope(dispatcher + SupervisorJob()).launch { instance.runRecomposeAndApplyChanges() }
        instance
    }

    /** 恒为 RESUMED 的宿主；同时提供 LifecycleOwner 与 SavedStateRegistryOwner（类型由推断得到，别写死成 LifecycleOwner）。 */
    private val owner = FloatyLifecycleOwner()

    /**
     * 造一个能安全离屏测量、attach 后正常渲染的 Compose 视图。
     *
     * @param widthPx / heightPx 固定尺寸：调用方（窗口/弹窗）按它来定位，Compose 内容按它排版。
     */
    fun createView(
        context: Context,
        widthPx: Int,
        heightPx: Int,
        content: @Composable () -> Unit
    ): View {
        val compose = ComposeView(context).apply {
            setParentCompositionContext(recomposer)
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent(content)
        }
        return FixedSizeFrame(context, compose, widthPx, heightPx)
    }

    fun screenWidth(context: Context): Int = context.resources.displayMetrics.widthPixels

    fun screenHeight(context: Context): Int = context.resources.displayMetrics.heightPixels

    private class FloatyLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)

        private val savedStateController = SavedStateRegistryController.create(this).apply {
            performAttach()
            performRestore(null)
        }

        init {
            registry.currentState = Lifecycle.State.RESUMED
        }

        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    }

    /** 只回答固定尺寸、且只在 attach 之后才去量孩子的 FrameLayout（见类注释第 1 条）。 */
    private class FixedSizeFrame(
        context: Context,
        child: View,
        private val fixedWidth: Int,
        private val fixedHeight: Int
    ) : FrameLayout(context) {

        init {
            addView(child, LayoutParams(fixedWidth, fixedHeight))
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(fixedWidth, fixedHeight)
            if (isAttachedToWindow && childCount > 0) {
                measureChild(
                    getChildAt(0),
                    MeasureSpec.makeMeasureSpec(fixedWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(fixedHeight, MeasureSpec.EXACTLY)
                )
            }
        }
    }
}
