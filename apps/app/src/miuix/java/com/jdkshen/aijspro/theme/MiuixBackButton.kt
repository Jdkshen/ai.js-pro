package com.jdkshen.aijspro.theme

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.icons.ArrowBack
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Miuix-style circular back button (MIUI look): rounded gray disc + back arrow,
 * used as the TopAppBar navigation icon on every pilot page.
 */
@Composable
fun MiuixBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.padding(start = 10.dp),
        backgroundColor = MiuixTheme.colorScheme.surfaceVariant,
        minWidth = 36.dp,
        minHeight = 36.dp,
    ) {
        Icon(
            imageVector = MiuixIcons.ArrowBack,
            contentDescription = "返回",
            modifier = Modifier.size(20.dp),
            tint = MiuixTheme.colorScheme.onBackground,
        )
    }
}
