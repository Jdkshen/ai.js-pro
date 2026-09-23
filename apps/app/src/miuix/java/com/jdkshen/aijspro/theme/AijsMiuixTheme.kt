package com.jdkshen.aijspro.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import android.content.Context
import android.content.res.Configuration
import com.jdkshen.aijspro.Pref
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * Single source of truth for Miuix theming across all pilot pages.
 *
 * - Follow-system has priority over the saved manual dark/light choice, matching
 *   the app-wide AppCompatDelegate mode used for native resources.
 * - Primary color is teal #009688 (light) / #4DD0E1 (dark), matching
 *   res/values/colors.xml and res/values-night/colors.xml used by the M3 theme.
 */

private val TEAL_500 = Color(0xFF009688)
private val TEAL_300 = Color(0xFF4DB6AC)
private val TEAL_200 = Color(0xFF80CBC4)
private val TEAL_100 = Color(0xFFB2DFDB)
private val TEAL_50 = Color(0xFFE0F2F1)
private val TEAL_800 = Color(0xFF00695C)
private val CYAN_300 = Color(0xFF4DD0E1)
private val CYAN_900 = Color(0xFF006064)

/**
 * Observable theme flags so every Miuix surface repaints immediately when the
 * in-app night mode or follow-system switches change (Pref changes are not
 * Compose state by themselves).
 */
object AijsMiuixThemeState {
    val dark = mutableStateOf(Pref.isNightModeEnabled())
    val followSystem = mutableStateOf(Pref.isFollowSystemThemeEnabled())
}

fun isAijsFollowSystemTheme(): Boolean = AijsMiuixThemeState.followSystem.value

/**
 * 有效深色状态（Composable 用）。
 *
 * ⚠️ 必须与 `AijsMiuixTheme` 用**一模一样**的规则，否则页面与系统栏会打架。
 * 真机踩过：本函数原来只读 `AijsMiuixThemeState.dark`（应用内开关），
 * 而 MainActivity 自己读 Pref 并且「跟随系统优先」—— 系统深色时
 * MainActivity 把状态栏刷成纯黑、页面却是浅色的（黑状态栏配白页面）。
 */
@Composable
fun isAijsDarkTheme(): Boolean =
    if (AijsMiuixThemeState.followSystem.value) {
        isSystemInDarkTheme()
    } else {
        AijsMiuixThemeState.dark.value
    }

/**
 * 有效深色状态（非 Composable 用，比如 Java 的 Activity 要给状态栏上色）。
 *
 * 与 [isAijsDarkTheme] / [AijsMiuixTheme] 同规则、同数据源：
 * 只认 `AijsMiuixThemeState`，不再自己重算 Pref —— 两边各算一次就会不一致。
 */
fun isAijsDarkThemeResolved(context: Context): Boolean {
    if (!AijsMiuixThemeState.followSystem.value) {
        return AijsMiuixThemeState.dark.value
    }
    val mode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    return mode == Configuration.UI_MODE_NIGHT_YES
}

fun refreshMiuixDarkTheme() {
    AijsMiuixThemeState.dark.value = Pref.isNightModeEnabled()
}

/**
 * 进程内第一次用到主题状态时，从 Pref 读一次真实值。
 *
 * 为什么需要：[AijsMiuixThemeState] 的几个 mutableStateOf 是在**对象初始化那一刻**求值的，
 * 若那一刻 Pref 还没 init（或调用方还没读到设置），它们会停在默认的 false ——
 * 而刷新函数原来只有抽屉里的开关会调，于是整个进程里页面都按「浅色」画，
 * 与现场（跟随系统 + 系统深色）对不上。Activity 上色前调一下这个就对齐了。
 */
fun ensureMiuixThemeStateLoaded() {
    AijsMiuixThemeState.dark.value = Pref.isNightModeEnabled()
    AijsMiuixThemeState.followSystem.value = Pref.isFollowSystemThemeEnabled()
}

fun refreshMiuixFollowSystemTheme() {
    AijsMiuixThemeState.followSystem.value = Pref.isFollowSystemThemeEnabled()
}

@Composable
fun AijsMiuixTheme(content: @Composable () -> Unit) {
    val dark = if (AijsMiuixThemeState.followSystem.value) {
        isSystemInDarkTheme()
    } else {
        AijsMiuixThemeState.dark.value
    }
    MiuixTheme(
        colors = if (dark) DARK_COLORS else LIGHT_COLORS,
        content = content
    )
}

private val LIGHT_COLORS by lazy { aijsLightColors() }
private val DARK_COLORS by lazy { aijsDarkColors() }

private fun aijsLightColors(): Colors = lightColorScheme(
    primary = TEAL_500,
    onPrimary = Color.White,
    primaryVariant = TEAL_500,
    onPrimaryVariant = Color(0xFFAEE8E0),
    disabledPrimary = TEAL_100,
    disabledOnPrimary = Color.White,
    disabledPrimaryButton = TEAL_100,
    disabledOnPrimaryButton = Color.White,
    disabledPrimarySlider = TEAL_200,
    primaryContainer = TEAL_300,
    onPrimaryContainer = Color.White,
    tertiaryContainer = TEAL_50,
    onTertiaryContainer = TEAL_800,
    tertiaryContainerVariant = TEAL_50
)

private fun aijsDarkColors(): Colors = darkColorScheme(
    primary = CYAN_300,
    onPrimary = Color(0xFF00363A),
    primaryVariant = Color(0xFF26A69A),
    onPrimaryVariant = Color(0xFFE0F2F1),
    disabledPrimary = Color(0xFF3A6B69),
    disabledOnPrimary = Color(0xFF8FB8B5),
    disabledPrimaryButton = Color(0xFF3A6B69),
    disabledOnPrimaryButton = Color(0xFF8FB8B5),
    disabledPrimarySlider = Color(0xFF2E5B59),
    primaryContainer = Color(0xFF00897B),
    onPrimaryContainer = Color.White,
    tertiaryContainer = Color(0xFF103332),
    onTertiaryContainer = Color(0xFF80CBC4),
    tertiaryContainerVariant = Color(0xFF103332)
)
