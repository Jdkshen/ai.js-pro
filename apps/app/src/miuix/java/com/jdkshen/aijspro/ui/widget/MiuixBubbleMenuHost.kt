package com.jdkshen.aijspro.ui.widget

import android.content.Context
import android.graphics.Paint
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.jdkshen.aijspro.theme.AijsMiuixTheme
import java.util.function.IntConsumer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.ceil
import kotlin.math.max

/**
 * 布局分析悬浮窗那个「气泡菜单」的 Miuix 版内容。
 *
 * 原来的 `BubblePopupMenu` 是 `PopupWindow` + `RecyclerView` + 写死的深色气泡资源
 * （`@color/bubble_bg` / `bubble.xml` / `little_triangle.xml`）。这里保留窗口与定位逻辑不变，
 * 只把**内容**换成 Miuix 的 Card + 文本行（与 `MiuixExplorerMenuHost` 同一视觉语言）。
 *
 * ⚠️ 两个容易踩的点：
 *  1. **尺寸必须能"离屏预算"**：调用方在 `showAsDropDownAtLocation()` 之前会先读
 *     `getContentView().getMeasuredWidth()/Height()` 来定位（居中 + 上/下翻转）。
 *     而 `ComposeView` 在 attach 之前不组合、量出来是 0 —— 所以这里提供 [preferredSize]，
 *     由 `BubblePopupMenu` 用它给 ComposeView 设**固定** LayoutParams，量出来的值就是确定的。
 *  2. **没有 Activity 兜底**：悬浮窗活在 WindowManager 里（用户可能已经回到桌面），
 *     没有 Activity 就当不了 LifecycleOwner，所以自带一个恒为 RESUMED 的 owner。
 */
object MiuixBubbleMenuHost {

    /** 每行高度（dp）；[preferredSize] 的高度必须与 [createView] 里实际布局一致。 */
    private const val ROW_HEIGHT_DP = 46f

    /** 文本两侧留白（dp）。 */
    private const val H_PADDING_DP = 20f

    /** 菜单最小宽度（dp）——太窄的菜单在屏幕上看着不像菜单。 */
    private const val MIN_WIDTH_DP = 132f

    /** 选项字号（sp），与旧 `bubble_popup_menu_item.xml` 的 15sp 对齐。 */
    private const val TEXT_SIZE_SP = 15f

    /**
     * 菜单的预算尺寸（像素）：宽度 = 最长文案 + 两侧留白（不小于 [MIN_WIDTH_DP]），高度 = 行高 × 行数。
     * 与 [createView] 的布局参数一一对应，改一边必须改另一边。
     */
    @JvmStatic
    fun preferredSize(context: Context, options: List<String>): IntArray {
        val metrics = context.resources.displayMetrics
        val paint = Paint().apply { textSize = TEXT_SIZE_SP * metrics.scaledDensity }
        var widest = 0f
        for (option in options) {
            widest = max(widest, paint.measureText(option))
        }
        val width = max(MIN_WIDTH_DP * metrics.density, widest + 2 * H_PADDING_DP * metrics.density)
        val height = ROW_HEIGHT_DP * metrics.density * options.size
        return intArrayOf(ceil(width.toDouble()).toInt(), ceil(height.toDouble()).toInt())
    }

    /**
     * 造菜单内容视图。尺寸由调用方按 [preferredSize] 设成固定值。
     *
     * ⚠️ 返回的是一层**固定尺寸包装**而不是裸 `ComposeView`：
     * `ComposeView` 一旦在**挂到窗口之前**被 measure，就会去要 `windowRecomposer`，拿不到直接抛
     * `IllegalStateException: Cannot locate windowRecomposer; ... is not attached to a window`
     * （真机 dev-620 实测崩溃）。而调用方偏偏要求先 `preMeasure()` 拿尺寸再定位，
     * 所以包装层自己回答尺寸、把 Compose 子树的第一次测量推迟到 attach 之后。
     *
     * @param onAction 点击第 index 项时回调（用 [IntConsumer] 而不是 Kotlin 函数类型，
     *                 `BubblePopupMenu` 是 Java、反射调用时不用塞 `Function1`）；
     *                 `dismiss` 由调用方负责（旧路径也是调用方自己 dismiss）。
     */
    @JvmStatic
    fun createView(context: Context, options: List<String>, onAction: IntConsumer): View {
        val compose = ComposeView(context).apply {
            // 显式给 CompositionContext：PopupWindow / 悬浮窗的根是系统建的 PopupDecorView，
            // 里面没有 ViewTreeLifecycleOwner，而 Compose 在 attach 时会从 **window 的 rootView**
            // 往上找 owner 并造 windowRecomposer —— 找不到就抛
            // "ViewTreeLifecycleOwner not found from android.widget.PopupWindow$PopupDecorView"
            // （真机 dev-620 实测）。给了 context 就不会再走那条查找。
            setParentCompositionContext(floatyRecomposer)
            setViewTreeLifecycleOwner(FloatyLifecycleOwner)
            // Compose 在 attach 时还会强校验 SavedStateRegistryOwner，缺了会抛
            // "Composed into the View which doesn't propagateViewTreeSavedStateRegistryOwner!"
            setViewTreeSavedStateRegistryOwner(FloatyLifecycleOwner)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                AijsMiuixTheme {
                    Card(modifier = Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize()) {
                            options.forEachIndexed { index, label ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(ROW_HEIGHT_DP.dp)
                                        .clickable { onAction.accept(index) }
                                        .padding(horizontal = H_PADDING_DP.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        fontSize = TEXT_SIZE_SP.sp,
                                        color = MiuixTheme.colorScheme.onSurface,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        val size = preferredSize(context, options)
        return FixedSizeFrame(context, compose, size[0], size[1])
    }

    /** 只回答固定尺寸、且只在 attach 之后才去量孩子的 FrameLayout（见 [createView] 的说明）。 */
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

    /**     * 悬浮窗/弹窗专用的 Recomposer：跟进程走，不依赖任何 Activity 或 window owner。
     * 只在真的用过菜单后才初始化，初始化后一直活着（菜单会反复开合，重建成本不划算）。
     */
    private val floatyRecomposer: Recomposer by lazy {
        val dispatcher = AndroidUiDispatcher.Main
        val recomposer = Recomposer(dispatcher)
        CoroutineScope(dispatcher + SupervisorJob()).launch { recomposer.runRecomposeAndApplyChanges() }
        recomposer
    }

    /**
     * 恒为 RESUMED 的宿主：同时当 LifecycleOwner 与 SavedStateRegistryOwner。
     *
     * 悬浮窗/弹窗活在 WindowManager 里，没有 Activity 兜底（用户可能已回到桌面），
     * 而 Compose 在 attach 时会同时要这两个 owner，所以自己造一个。
     * 不用 `rememberSaveable` 就不需要真的存状态，`performRestore(null)` 就够。
     */
    private object FloatyLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)

        private val savedStateController = SavedStateRegistryController.create(this).apply {
            // 顺序有讲究：performAttach() 要求 owner 的 lifecycle 还是 INITIALIZED，
            // 所以 RESUMED 必须放到两个属性之后的 init 块里再设
            // （先设 RESUMED 会抛 "Restarter must be created only during owner's initialization stage"）。
            performAttach()
            performRestore(null)
        }

        init {
            registry.currentState = Lifecycle.State.RESUMED
        }

        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    }
}
