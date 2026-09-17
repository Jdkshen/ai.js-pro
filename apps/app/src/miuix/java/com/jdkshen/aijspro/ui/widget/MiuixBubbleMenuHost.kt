package com.jdkshen.aijspro.ui.widget

import android.content.Context
import android.graphics.Paint
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jdkshen.aijspro.theme.AijsMiuixTheme
import java.util.function.IntConsumer
import kotlin.math.ceil
import kotlin.math.max
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 布局分析悬浮窗那个「气泡菜单」的 Miuix 版内容。
 *
 * 原来的 `BubblePopupMenu` 是 `PopupWindow` + `RecyclerView` + 写死的深色气泡资源
 * （`@color/bubble_bg` / `bubble.xml` / `little_triangle.xml`）。这里保留窗口与定位逻辑不变，
 * 只把**内容**换成 Miuix 风格的卡片 + 文本行。
 *
 * 两件事要注意：
 *  1. **尺寸必须能「离屏预算」**：调用方在 `showAsDropDownAtLocation()` 之前会先读
 *     `getContentView().getMeasuredWidth()/Height()` 来定位（居中 + 上/下翻转）。所以这里提供
 *     [preferredSize]，由 `BubblePopupMenu` 用来定尺寸；真正的离屏测量问题在 [FloatyCompose] 里解决。
 *  2. **视觉**：真机反馈过「白得太白、圆角太小」，所以底色用 `surfaceContainer`（不用纯白的
 *     `surface`/Miuix `Card` 默认色），圆角 [BUBBLE_CORNER_DP]、再加一层阴影。
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

    /** 气泡卡片圆角（dp）：与仓库里其它 Miuix 对话框（`MiuixDialogHost.kt` 的 26dp）保持一致。 */
    private const val BUBBLE_CORNER_DP = 26f

    /** 气泡卡片阴影（dp）：给一点悬浮感，不然贴在别的应用画面上像一块白板。 */
    private const val BUBBLE_ELEVATION_DP = 8f

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
     * Compose-in-window 的三个坑（离屏 measure、attach 要 owner、自造 owner 的初始化顺序）
     * 都在 [FloatyCompose] 里处理好了，这里只负责画。
     *
     * @param onAction 点击第 index 项时回调（用 [IntConsumer] 而不是 Kotlin 函数类型，
     *                 `BubblePopupMenu` 是 Java、反射调用时不用塞 `Function1`）；
     *                 `dismiss` 由调用方负责（旧路径也是调用方自己 dismiss）。
     */
    @JvmStatic
    fun createView(context: Context, options: List<String>, onAction: IntConsumer): View {
        val size = preferredSize(context, options)
        return FloatyCompose.createView(context, size[0], size[1]) {
            AijsMiuixTheme {
                // 底色用 `surface`（就是仓库里其它 Miuix 对话框 `MiuixDialogCard` 的容器色，深浅自动切换），
                // 靠**阴影 + 细描边**做边界，而不是把底色压灰 ——
                // 上一版用 surfaceContainerHigh(#E8E8E8) 被反馈「整块发灰、跟其它界面不统一」。
                // 描边仍然必要：悬浮窗只有内容那么大的空间，阴影会被窗口裁掉，没描边就「糊」在背景上。
                val shape = RoundedCornerShape(BUBBLE_CORNER_DP.dp)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .shadow(BUBBLE_ELEVATION_DP.dp, shape)
                        .clip(shape)
                        .background(MiuixTheme.colorScheme.surface)
                        .border(1.dp, MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f), shape)
                ) {
                    Column(Modifier.fillMaxSize()) {
                        options.forEachIndexed { index, label ->
                            // 按下即时反馈：悬浮窗里没有系统 ripple 兜底，手指按下去必须有反应。
                            val interaction = remember { MutableInteractionSource() }
                            val pressed by interaction.collectIsPressedAsState()
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(ROW_HEIGHT_DP.dp)
                                    .clickable(interactionSource = interaction, indication = null) {
                                        onAction.accept(index)
                                    }
                                    .background(
                                        if (pressed) MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                                        else Color.Transparent
                                    )
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
}
