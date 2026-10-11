package com.jdkshen.aijspro.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 下拉刷新 —— 零依赖实现（不引入 `androidx.compose.material3`）。
 *
 * 背景：旧界面文件页/资源页/任务页都用 `SwipeRefreshLayout` 提供下拉刷新
 * （`ExplorerView extends ThemeColorSwipeRefreshLayout`、`ewebview.xml`、
 * `fragment_task_manager.xml`），而迁移到 Compose 后**整个手势消失了**。
 * material3 的 `PullToRefreshBox` 能用，但当前类路径上只有
 * `material3-window-size-class`、没有 material3 主体，为这一个手势新增依赖不划算，
 * 因此用 `nestedScroll` 自己实现。
 *
 * 行为对齐 `SwipeRefreshLayout` 的观感：
 *   - 只在列表已在顶部且**向下**拖动时接管，不影响正常滚动
 *   - 拖动距离带阻尼（[DAMPING]），越拉越沉
 *   - 松手未过阈值 → 回弹；过阈值 → 吸附到阈值位置并触发 [onRefresh]
 *   - [refreshing] 由调用方控制：刷新期间指示器保持显示，结束后回弹
 *   - 带速度时松手会有一次衰减滑行，避免"硬停"
 *
 * 指示器用 `Canvas` 自绘（Miuix 的 `SuperSpinner` 是下拉选择器，不是转圈控件），
 * 颜色取主题 `primary`，因此亮/暗色自动跟随。
 *
 * 用法：
 * ```
 * MiuixPullRefresh(refreshing = loading, onRefresh = { reloadTick++ }) {
 *     LazyColumn(Modifier.fillMaxSize()) { ... }
 * }
 * ```
 *
 * @param refreshing 是否正在刷新（由调用方状态驱动）
 * @param onRefresh 越过阈值松手时触发一次
 * @param enabled 关掉后可把子内容完全当作普通列表使用
 */
@Composable
fun MiuixPullRefresh(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val thresholdPx = with(density) { THRESHOLD.toPx() }
    val maxPx = with(density) { MAX_PULL.toPx() }

    // 下拉位移完全由手势驱动；回弹/吸附用 Animatable 走动画。
    val pull = remember { Animatable(0f) }

    val connection = remember(enabled, refreshing) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // 手指向上滑（available.y < 0）时先收回下拉位移，其余交给列表自己滚
                return if (available.y < 0f && pull.value > 0f) {
                    val consumed = pull.value.coerceAtMost(-available.y)
                    scope.launch { pull.snapTo(pull.value - consumed) }
                    Offset(0f, -consumed)
                } else {
                    Offset.Zero
                }
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (!enabled || refreshing) return Offset.Zero
                // 只有列表已经到顶、系统又消费不掉的那部分才算"下拉"
                if (consumed.y != 0f || available.y <= 0f) return Offset.Zero
                val next = (pull.value + available.y * DAMPING).coerceAtMost(maxPx)
                val added = next - pull.value
                scope.launch { pull.snapTo(next) }
                return Offset(0f, added)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (!enabled || refreshing) return Velocity.Zero
                // 带速度时衰减滑行一小段，观感接近 SwipeRefreshLayout
                if (pull.value > 0f && available.y > 0f) {
                    var v = available.y
                    while (v > 1f && pull.value > 0f) {
                        pull.snapTo((pull.value + v * DECAY_STEP).coerceIn(0f, maxPx))
                        v *= DECAY_FACTOR
                    }
                }
                if (pull.value >= thresholdPx) {
                    pull.snapTo(thresholdPx)
                    onRefresh()
                } else if (pull.value > 0f) {
                    pull.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                }
                // 不消费纵向速度，避免影响列表自身惯性
                return Velocity.Zero
            }

            // 基类实现就是「不消费」，直接沿用
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
                super.onPostFling(consumed, available)
        }
    }

    // 刷新结束后自动回弹
    LaunchedEffect(refreshing) {
        if (!refreshing && pull.value > 0f) {
            pull.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
        }
    }

    Box(modifier.fillMaxWidth().nestedScroll(connection)) {
        // 内容随下拉一起下移，给指示条让出位置
        Box(
            Modifier.fillMaxWidth()
                .offset(y = with(density) { pull.value.toDp() })
        ) {
            content()
        }

        val progress = (pull.value / thresholdPx).coerceIn(0f, 1f)
        val shown = if (refreshing) 1f else progress
        if (pull.value > 0f || refreshing) {
            // 细进度条贴在列表顶部边缘：高度只有 BAR_HEIGHT，纵向跟随位移。
            // 用条而不是圆环，是为了不遮挡首行文字 —— 圆环要 32dp 才看得清，
            // 而下拉量小于它时会压住第一行。
            val primary = MiuixTheme.colorScheme.primary
            val track = MiuixTheme.colorScheme.onBackgroundVariant.copy(alpha = 0.20f)
            Canvas(
                Modifier.fillMaxWidth()
                    .height(BAR_HEIGHT)
                    .offset(y = with(density) { pull.value.toDp() })
            ) {
                drawRect(color = track, size = size)
                // 从左往右填充；刷新中恒满
                if (shown > 0f) {
                    drawRect(color = primary, size = Size(size.width * shown, size.height))
                }
            }
        }
    }
}

/** 触发刷新的下拉距离。 */
private val THRESHOLD = 64.0.dp

/** 最大下拉距离（再拉也不增加）。 */
private val MAX_PULL = 96.0.dp

/** 进度条高度（用条而非圆环，避免遮挡首行）。 */
private val BAR_HEIGHT = 3.0.dp

/** 拖动阻尼：越小越沉。SwipeRefreshLayout 大致就是这个手感。 */
private const val DAMPING = 0.5f

/** 松手衰减：每步保留的比例与步长。 */
private const val DECAY_FACTOR = 0.55f
private const val DECAY_STEP = 0.12f
