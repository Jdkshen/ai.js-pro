package com.jdkshen.aijspro.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.icons.ArrowBack
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Miuix-style circular back button (MIUI look): rounded gray disc + back arrow,
 * used as the TopAppBar navigation icon on every pilot page.
 *
 * The disc stays 36dp for the MIUI look, but the touch target is 48dp: this is
 * the only way back on the secondary pages, and a 36dp target misses the
 * 48dp minimum (and gets harder to hit once the system font scale grows).
 */
@Composable
fun MiuixBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier.padding(start = 10.dp).size(48.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape)
                .background(MiuixTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = MiuixIcons.ArrowBack,
                contentDescription = "返回",
                modifier = Modifier.size(20.dp),
                tint = MiuixTheme.colorScheme.onBackground,
            )
        }
    }
}
