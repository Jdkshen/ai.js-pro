package com.jdkshen.aijspro.ui.common

import android.app.Activity
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jdkshen.aijspro.Pref
import com.jdkshen.aijspro.R
import com.jdkshen.aijspro.model.explorer.ExplorerFileItem
import com.jdkshen.aijspro.model.script.Scripts
import com.jdkshen.aijspro.ui.explorer.ExplorerBreadcrumbs
import com.stardust.autojs.project.ProjectConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import java.text.Collator
import java.util.Locale

/** Shared browser for importing files, choosing destinations and switching projects. */
object MiuixFileChooserDialog {
    fun interface FileSelected { fun onSelected(path: String) }
    private enum class Mode { SCRIPT, IMPORT, DIRECTORY, PROJECT }

    @JvmStatic
    fun show(activity: Activity?, title: String, rootPath: String, confirmText: String,
             cancelText: String, onSelected: FileSelected): Boolean =
        showPicker(activity, title, rootPath, rootPath, confirmText, cancelText, Mode.SCRIPT, onSelected)

    @JvmStatic
    fun showImport(activity: Activity?, rootPath: String, initialPath: String,
                   onSelected: FileSelected): Boolean =
        showPicker(activity, "选择要导入的文件", rootPath, initialPath, "导入", "取消", Mode.IMPORT, onSelected)

    @JvmStatic
    fun showDirectory(activity: Activity?, rootPath: String, onSelected: FileSelected): Boolean =
        showDirectory(activity, "选择项目所在文件夹", rootPath, rootPath, onSelected)

    @JvmStatic
    fun showDirectory(activity: Activity?, title: String, rootPath: String, initialPath: String,
                      onSelected: FileSelected): Boolean =
        showPicker(activity, title, rootPath, initialPath, "使用此文件夹", "取消", Mode.DIRECTORY, onSelected)

    @JvmStatic
    fun showProject(activity: Activity?, rootPath: String, initialPath: String,
                    onSelected: FileSelected): Boolean =
        showPicker(activity, "选择要切换的项目", rootPath, initialPath, "打开此项目", "取消", Mode.PROJECT, onSelected)

    private fun showPicker(activity: Activity?, title: String, rootPath: String, initialPath: String,
                           confirmText: String, cancelText: String, mode: Mode,
                           onSelected: FileSelected): Boolean =
        MiuixDialogHost.showCard(activity) { dialog ->
            ChooserContent(dialog, title, File(rootPath), File(initialPath), confirmText, cancelText, mode,
                { dialog.dismiss() }, { dialog.dismiss(); onSelected.onSelected(it.path) })
        } != null

    @Composable
    private fun ChooserContent(dialog: ComponentDialog, title: String, root: File, initial: File, confirmText: String,
                               cancelText: String, mode: Mode, onCancel: () -> Unit,
                               onConfirm: (File) -> Unit) {
        val folders = mode == Mode.DIRECTORY || mode == Mode.PROJECT
        var current by remember { mutableStateOf(initialDirectory(root, initial)) }
        var entries by remember { mutableStateOf<List<File>>(emptyList()) }
        var selected by remember(current) { mutableStateOf<File?>(null) }
        var query by remember(current) { mutableStateOf("") }
        var loading by remember(current) { mutableStateOf(true) }
        var readError by remember(current) { mutableStateOf<String?>(null) }
        var locationError by remember(current) { mutableStateOf<String?>(null) }
        val listState = remember(current) { androidx.compose.foundation.lazy.LazyListState() }
        fun navigate(file: File) { if (withinRoot(root, file)) current = file }
        val parent = current.parentFile?.takeIf { current.absolutePath != root.absolutePath && withinRoot(root, it) }
        DisposableEffect(dialog, current) {
            val callback = object : OnBackPressedCallback(parent != null) {
                override fun handleOnBackPressed() { parent?.let(::navigate) }
            }
            dialog.onBackPressedDispatcher.addCallback(dialog, callback)
            onDispose { callback.remove() }
        }

        LaunchedEffect(current) {
            entries = emptyList()
            try {
                val result = withContext(Dispatchers.IO) {
                    val files = current.listFiles() ?: throw java.io.IOException("无权限读取或文件夹已不存在")
                    val collator = Collator.getInstance(Locale.CHINA)
                    val visible = files.filter { file ->
                        withinRoot(root, file) && (file.isDirectory || !folders && when (mode) {
                            Mode.IMPORT -> file.extension.equals("auto", true) || ExplorerFileItem.isEditableExtension(file.extension)
                            else -> Scripts.FILE_FILTER.accept(file)
                        })
                    }.sortedWith(compareBy<File> { !it.isDirectory }.thenComparator { a, b -> collator.compare(a.name, b.name) })
                    val error = when {
                        mode == Mode.PROJECT && ProjectConfig.fromProjectDir(current.path) == null -> "此文件夹没有有效的 project.json，请进入项目目录"
                        mode == Mode.DIRECTORY && !current.canWrite() -> "此文件夹不可写，请选择其他位置"
                        else -> null
                    }
                    visible to error
                }
                entries = result.first
                locationError = result.second
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                readError = e.message ?: "无法读取文件夹"
            } finally { loading = false }
        }

        val crumbs = remember(current) {
            ExplorerBreadcrumbs.build(Pref.getScriptDirPath(), root.path, current.path)
                .filter { withinRoot(root, File(it.path)) }
        }
        val breadcrumbScroll = rememberScrollState()
        LaunchedEffect(current, breadcrumbScroll.maxValue) { breadcrumbScroll.scrollTo(breadcrumbScroll.maxValue) }
        val visible = remember(entries, query) { entries.filter { it.name.contains(query, ignoreCase = true) } }
        val enabled = !loading && readError == null && if (folders) locationError == null else selected != null

        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp)
            .clip(RoundedCornerShape(26.dp)).background(MiuixTheme.colorScheme.surface)
            .height((LocalConfiguration.current.screenHeightDp * 0.82f).dp).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontSize = 20.sp, color = MiuixTheme.colorScheme.onSurface)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(breadcrumbScroll), verticalAlignment = Alignment.CenterVertically) {
                    crumbs.forEachIndexed { index, segment ->
                        if (index > 0) Text(" › ", color = MiuixTheme.colorScheme.onSurfaceSecondary)
                        Text(segment.label, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.clickable { navigate(File(segment.path)) }.padding(vertical = 12.dp))
                    }
                }
                TextButton(text = "上一级", onClick = { parent?.let(::navigate) }, enabled = parent != null)
            }
            Text(current.path, fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            TextField(value = query, onValueChange = { query = it }, singleLine = true,
                label = "搜索当前文件夹", modifier = Modifier.fillMaxWidth())
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    loading -> StatusText("正在读取…")
                    readError != null -> StatusText("读取失败：$readError")
                    visible.isEmpty() -> StatusText(if (query.isNotEmpty()) "没有匹配的文件或文件夹" else if (folders) "没有子文件夹" else "没有可导入的脚本或文本文件")
                    else -> LazyColumn(state = listState) {
                        items(visible, key = { it.path }) { file ->
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .background(if (selected == file) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f) else MiuixTheme.colorScheme.surface)
                                .clickable { if (file.isDirectory) navigate(file) else selected = file }
                                .padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Image(painterResource(if (file.isDirectory) R.drawable.ic_folder_outline_24dp else R.drawable.ic_floating_action_menu_file),
                                    contentDescription = null, colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.primary), modifier = Modifier.size(28.dp))
                                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                    Text(file.name, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(if (file.isDirectory) "文件夹" else file.extension.uppercase(Locale.ROOT) + " 文件",
                                        fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                                }
                                Text(if (file.isDirectory) "›" else if (selected == file) "✓" else "", color = MiuixTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
            Text(locationError ?: selected?.let { "已选择：${it.name}" } ?: when (mode) {
                Mode.PROJECT -> "将打开当前目录的项目配置"
                Mode.DIRECTORY -> "确认后使用当前文件夹"
                Mode.IMPORT -> "支持脚本和文本文件，包括 Markdown"
                else -> "请选择脚本文件"
            }, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onCancel, modifier = Modifier.weight(1f)) { Text(cancelText) }
                Button(onClick = { if (folders) onConfirm(current) else selected?.let(onConfirm) }, enabled = enabled,
                    modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColorsPrimary()) { Text(confirmText) }
            }
        }
    }

    @Composable
    private fun StatusText(message: String) {
        Text(message, modifier = Modifier.padding(vertical = 16.dp), fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurfaceSecondary)
    }

    private fun initialDirectory(root: File, initial: File): File {
        var candidate: File? = if (initial.isFile) initial.parentFile else initial
        while (candidate != null && withinRoot(root, candidate)) {
            if (candidate.isDirectory) return candidate
            candidate = candidate.parentFile
        }
        return root
    }

    private fun withinRoot(root: File, file: File): Boolean = try {
        val base = root.canonicalFile
        val target = file.canonicalFile
        target == base || target.path.startsWith(base.path.trimEnd(File.separatorChar) + File.separator)
    } catch (_: java.io.IOException) { false }
}
