package com.jdkshen.aijspro.ui.floating

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.widget.PopupWindow
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jdkshen.aijspro.theme.AijsMiuixTheme
import com.jdkshen.aijspro.ui.widget.FloatyCompose
import java.util.function.IntConsumer
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 悬浮球菜单里那两种「列表弹框」（布局分析、更多设置）的 Miuix 版。
 *
 * 原来走 `OperationDialogBuilder`（Material 白卡片、圆角很小），真机反馈「白得太白、没圆角」，
 * 于是改成：全屏遮罩 + 居中 Miuix 卡片（26dp 圆角、`surfaceContainer` 底色、带阴影），
 * 行高 52dp、行首带图标。调用方拿不到 Miuix 宿主（返回 false）时回落到旧实现。
 *
 * 注意这里**不能**用 `androidx.activity.ComponentDialog`：这些弹框是从悬浮窗的
 * `FloatyService` context 里弹的，没有 Activity；所以用 `PopupWindow` + [FloatyCompose]
 * 那套「自带 Recomposer / 生命周期宿主」的底座（坑见 [FloatyCompose] 的注释）。
 */
object MiuixActionMenuHost {

    /** 卡片圆角（dp）：与仓库里 `MiuixDialogCard` 保持一致的 26dp。 */
    private const val CARD_CORNER_DP = 26f

    /** 行高（dp）。 */
    private const val ROW_HEIGHT_DP = 52f

    /** 标题区高度（dp），无标题时也算进去，避免两种形态跳动。 */
    private const val TITLE_HEIGHT_DP = 52f

    /** 卡片内左右留白（dp）。 */
    private const val CARD_PADDING_DP = 8f

    /** 卡片距屏幕两侧留白（dp）。 */
    private const val CARD_MARGIN_DP = 34f

    /** 图标尺寸（dp）。 */
    private const val ICON_SIZE_DP = 22f

    /**
     * 弹出菜单。
     *
     * @param title 标题（空串则不显示标题区）
     * @param ids 每项的 id（回调原样带回，调用方按 id 分发）
     * @param icons 每项的图标资源（`0` 表示不画图标）
     * @param labels 每项的文案
     * @param onAction 点击回调（用 [IntConsumer] 而不是 Kotlin 函数类型，方便 Java 反射调用）
     * @return 是否已用 Miuix 弹窗展示；`false` 表示调用方该回落旧实现
     */
    @JvmStatic
    fun show(
        context: Context,
        title: String,
        ids: IntArray,
        icons: IntArray,
        labels: Array<String>,
        onAction: IntConsumer
    ): Boolean {
        if (ids.isEmpty() || labels.isEmpty()) {
            return false
        }
        return try {
            showInternal(context, title, ids, icons, labels, onAction)
            true
        } catch (e: Throwable) {
            // 回落是兜底，但不能静默（真机上曾因为静默 catch 看不出「弹框没换成 Miuix」）。
            android.util.Log.w("MiuixActionMenuHost", "Miuix 弹框创建失败，回落到旧实现", e)
            false
        }
    }

    private fun showInternal(
        context: Context,
        title: String,
        ids: IntArray,
        icons: IntArray,
        labels: Array<String>,
        onAction: IntConsumer
    ) {
        val screenWidth = FloatyCompose.screenWidth(context)
        val screenHeight = FloatyCompose.screenHeight(context)

        lateinit var popup: PopupWindow
        val dismiss = { popup.dismiss() }

        val content = FloatyCompose.createView(context, screenWidth, screenHeight) {
            AijsMiuixTheme {
                // 整屏遮罩：点遮罩关掉（悬浮窗场景下没有「返回键」可用时才不会被卡住）
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x66000000))
                        .clickable { dismiss() },
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier
                            .padding(horizontal = CARD_MARGIN_DP.dp)
                            .fillMaxWidth()
                            .shadow(10.dp, RoundedCornerShape(CARD_CORNER_DP.dp))
                            .clip(RoundedCornerShape(CARD_CORNER_DP.dp))
                            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                            // 细描边：底色是浅灰（不用纯白，真机反馈过「白得太白」），
                            // 描边把卡片从深色遮罩里「抠」出边界，不然整块灰会发闷。
                            .border(
                                1.dp,
                                MiuixTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                                RoundedCornerShape(CARD_CORNER_DP.dp)
                            )
                            // 卡片自身要吃掉点击，否则点卡片空白处会被下面的遮罩当成「点外面」而关掉。
                            // 用带 interactionSource + indication=null 的 clickable：只吞事件、不加涟漪。
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { }
                            .padding(vertical = CARD_PADDING_DP.dp)
                    ) {
                        if (title.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(TITLE_HEIGHT_DP.dp)
                                    .padding(horizontal = 16.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Text(
                                    text = title,
                                    fontSize = 17.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            }
                        }
                        labels.forEachIndexed { index, label ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(ROW_HEIGHT_DP.dp)
                                    .clickable {
                                        dismiss()
                                        onAction.accept(ids[index])
                                    }
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                val icon = icons.getOrNull(index) ?: 0
                                if (icon != 0) {
                                    Image(
                                        painter = painterResource(icon),
                                        contentDescription = null,
                                        colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurface),
                                        modifier = Modifier.size(ICON_SIZE_DP.dp)
                                    )
                                } else {
                                    Box(Modifier.size(ICON_SIZE_DP.dp))
                                }
                                Text(
                                    text = label,
                                    fontSize = 16.sp,
                                    color = MiuixTheme.colorScheme.onSurface,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        // 卡片本身比内容高一点（上下各 8dp），这里补足高度，保证和 contentHeight 对得上
                        Box(Modifier.height(CARD_PADDING_DP.dp))
                    }
                }
            }
        }

        popup = PopupWindow(content, screenWidth, screenHeight, true).apply {
            setBackgroundDrawable(ColorDrawable(0x66000000))
            isOutsideTouchable = true
            isFocusable = true   // 让返回键能关掉
        }
        popup.showAtLocation(content, Gravity.CENTER, 0, 0)
    }
}
