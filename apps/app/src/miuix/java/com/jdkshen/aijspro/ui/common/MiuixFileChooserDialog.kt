package com.jdkshen.aijspro.ui.common

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jdkshen.aijspro.model.script.Scripts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import java.text.Collator
import java.util.Locale

/**
 * Miuix 版「选择文件」弹窗：目录可逐级进入、文件可单选，只列 js/auto 脚本
 * （过滤规则与旧 FileChooserDialogBuilder + Scripts.FILE_FILTER 完全一致）。
 *
 * 「确定」仅在选中文件后可用（旧实现未选时会拿当前目录当结果去复制，实际必然失败，
 * 这里按「选择要导入的文件」的本意收紧）。
 *
 * 返回 true = 已由 Miuix 处理；false = 宿主不是 ComponentActivity，调用方回退旧实现。
 */
object MiuixFileChooserDialog {

    fun interface FileSelected {
        fun onSelected(path: String)
    }

    @JvmStatic
    fun showDirectory(activity: Activity?, rootPath: String, onSelected: FileSelected): Boolean {
        return MiuixDialogHost.showCard(activity) { dlg ->
            ChooserContent("选择项目所在文件夹", File(rootPath), "使用此文件夹", "取消",
                { dlg.dismiss() }, { file -> dlg.dismiss(); onSelected.onSelected(file.path) }, true)
        } != null
    }

    @JvmStatic
    fun show(
        activity: Activity?,
        title: String,
        rootPath: String,
        confirmText: String,
        cancelText: String,
        onSelected: FileSelected
    ): Boolean {
        val dialog = MiuixDialogHost.showCard(activity) { dlg ->
            ChooserContent(
                title = title,
                root = File(rootPath),
                confirmText = confirmText,
                cancelText = cancelText,
                onCancel = { dlg.dismiss() },
                onConfirm = { file ->
                    dlg.dismiss()
                    onSelected.onSelected(file.path)
                }
            )
        }
        return dialog != null
    }
}

@Composable
private fun ChooserContent(
    title: String,
    root: File,
    confirmText: String,
    cancelText: String,
    onCancel: () -> Unit,
    onConfirm: (File) -> Unit,
    directoriesOnly: Boolean = false
) {
    var current by remember { mutableStateOf(root) }
    var entries by remember { mutableStateOf<List<File>>(emptyList()) }
    var selected by remember { mutableStateOf<File?>(null) }

    var loading by remember { mutableStateOf(true) }
    var readError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(current) {
        loading = true
        readError = null
        selected = null
        entries = emptyList()
        try {
            entries = withContext(Dispatchers.IO) { listEntries(current).filter { !directoriesOnly || it.isDirectory } }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            readError = e.message ?: "无法读取文件夹"
        } finally { loading = false }
    }

    MiuixDialogCard {
        Text(title, fontSize = 20.sp, color = MiuixTheme.colorScheme.onSurface)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                current.path,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            val parent = current.parentFile
            val canGoUp = current != root && parent != null && withinRoot(root, parent)
            TextButton(
                text = "上一级",
                onClick = { if (canGoUp) current = parent!! },
                enabled = canGoUp,
                colors = ButtonDefaults.textButtonColorsPrimary()
            )
        }
        if (loading || readError != null) {
            Text(if (loading) "正在读取…" else "读取失败：$readError", modifier = Modifier.padding(12.dp))
        } else if (entries.isEmpty()) {
            Text(
                if (directoriesOnly) "此文件夹没有子文件夹，可以直接选择当前位置" else "（此目录没有可导入的脚本文件）",
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceSecondary
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                items(entries, key = { it.path }) { file ->
                    EntryRow(
                        file = file,
                        selected = selected?.path == file.path,
                        onClick = {
                            if (file.isDirectory) current = file else selected = file
                        }
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MiuixTheme.colorScheme.onSurfaceSecondary.copy(alpha = 0.12f))
                    .clickable { onCancel() }
                    .padding(vertical = 13.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(cancelText, fontSize = 16.sp, color = MiuixTheme.colorScheme.onSurface)
            }
            val enabled = !loading && readError == null && if (directoriesOnly) current.isDirectory && current.canWrite() else selected != null
            Box(
                Modifier.weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (enabled) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.primary.copy(alpha = 0.35f)
                    )
                    .clickable(enabled = enabled) { if (directoriesOnly) onConfirm(current) else selected?.let(onConfirm) }
                    .padding(vertical = 13.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    confirmText,
                    fontSize = 16.sp,
                    color = MiuixTheme.colorScheme.onPrimary.copy(alpha = if (enabled) 1f else 0.7f)
                )
            }
        }
    }
}

@Composable
private fun EntryRow(file: File, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
                else Color.Transparent
            )
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            file.name,
            fontSize = 15.sp,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (file.isDirectory) {
            Text("›", fontSize = 16.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
        } else if (selected) {
            Text("✓", fontSize = 16.sp, color = MiuixTheme.colorScheme.primary)
        }
    }
}

private fun listEntries(dir: File): List<File> {
    val files = dir.listFiles() ?: throw java.io.IOException("无权限读取或文件夹已不存在：${dir.path}")
    val collator = Collator.getInstance(Locale.CHINA)
    return files
        .filter { Scripts.FILE_FILTER.accept(it) }
        .sortedWith(Comparator { left, right ->
            if (left.isDirectory != right.isDirectory) {
                return@Comparator if (left.isDirectory) -1 else 1
            }
            collator.compare(left.name, right.name)
        })
}

private fun withinRoot(root: File, file: File): Boolean {
    val rootPath = root.path
    val path = file.path
    return path == rootPath || path.startsWith(rootPath + File.separator)
}
