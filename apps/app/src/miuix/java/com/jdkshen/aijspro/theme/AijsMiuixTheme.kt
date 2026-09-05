package com.jdkshen.aijspro.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import com.jdkshen.aijspro.Pref
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * Single source of truth for Miuix theming across all pilot pages.
 *
 * - Dark/light follows the in-app night mode switch (Pref.isNightModeEnabled),
 *   matching BaseActivity.setNightModeEnabled (YES/NO, not follow-system).
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
 * Observable dark-mode flag so every Miuix surface repaints immediately when the
 * in-app night mode switch is toggled (Pref changes are not Compose state by themselves).
 */
object AijsMiuixThemeState {
    val dark = mutableStateOf(Pref.isNightModeEnabled())
}

fun isAijsDarkTheme(): Boolean = AijsMiuixThemeState.dark.value

fun refreshMiuixDarkTheme() {
    AijsMiuixThemeState.dark.value = Pref.isNightModeEnabled()
}

@Composable
fun AijsMiuixTheme(content: @Composable () -> Unit) {
    MiuixTheme(
        colors = if (isAijsDarkTheme()) aijsDarkColors() else aijsLightColors(),
        content = content
    )
}

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
