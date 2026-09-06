package com.jdkshen.aijspro.ui.main

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsControllerCompat
import com.jdkshen.aijspro.Pref
import com.jdkshen.aijspro.R
import com.jdkshen.aijspro.theme.AijsMiuixTheme
import com.jdkshen.aijspro.theme.isAijsDarkTheme
import com.jdkshen.aijspro.ui.edit.EditActivity
import com.jdkshen.aijspro.ui.edit.EditorView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Miuix file search with the same options as the ImGui workspace, executed off the UI thread. */
class MiuixSearchActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val dark = isAijsDarkTheme()
                AijsMiuixTheme {
                    val bg = MiuixTheme.colorScheme.background
                    SideEffect {
                        window.statusBarColor = bg.toArgb()
                        window.navigationBarColor = bg.toArgb()
                        WindowInsetsControllerCompat(window, window.decorView).apply {
                            isAppearanceLightStatusBars = !dark
                            isAppearanceLightNavigationBars = !dark
                        }
                    }
                    MiuixSearchOverlayContent(
                        onDismiss = { finish() },
                        onItemSelected = { file ->
                            if (file.isDirectory) finish()
                            else openFile(File(Pref.getScriptDirPath()).absoluteFile, file)
                        }
                    )
                }
            }
        })
    }

    private fun openFile(scriptRoot: File, file: File) {
        if (file.isDirectory || !MiuixFileSearch.isWithinRoot(scriptRoot, file)) return
        startActivity(Intent(this, EditActivity::class.java)
            .putExtra(EditorView.EXTRA_PATH, file.absolutePath))
    }
}

@Composable
internal fun MiuixSearchOverlayContent(
    onDismiss: () -> Unit,
    onItemSelected: (File) -> Unit
) {
        val scriptRoot = remember { File(Pref.getScriptDirPath()).absoluteFile }
        val scope = rememberCoroutineScope()
        val listState = rememberLazyListState()
        val timestamp = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
        var query by remember { mutableStateOf(TextFieldValue("")) }
        val focusRequester = remember { FocusRequester() }
        val keyboardController = LocalSoftwareKeyboardController.current
        var includeSubdirectories by remember { mutableStateOf(true) }
        var useRegex by remember { mutableStateOf(false) }
        var includeHidden by remember { mutableStateOf(false) }
        var sortDescending by remember { mutableStateOf(false) }
        var currentDir by remember { mutableStateOf(scriptRoot.absolutePath) }
        var results by remember { mutableStateOf<List<File>>(emptyList()) }
        var error by remember { mutableStateOf<String?>(null) }
        var truncated by remember { mutableStateOf(false) }
        var searching by remember { mutableStateOf(false) }
        var searchJob by remember { mutableStateOf<Job?>(null) }

        fun runSearch(target: File = File(currentDir)) {
            searchJob?.cancel()
            searching = true
            error = null
            truncated = false
            searchJob = scope.launch {
                try {
                    val found = withContext(Dispatchers.IO) {
                        MiuixFileSearch.search(scriptRoot, target, query.text,
                            query.text.isNotBlank() && includeSubdirectories,
                            useRegex, includeHidden)
                    }
                    currentDir = target.absolutePath
                    results = found.files
                    error = found.error
                    truncated = found.truncated
                    searching = false
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    results = emptyList()
                    error = failure.localizedMessage ?: "搜索失败"
                    searching = false
                }
            }
        }

        DisposableEffect(Unit) {
            onDispose { searchJob?.cancel() }
        }
        LaunchedEffect(results) {
            if (results.isNotEmpty()) listState.scrollToItem(0)
        }
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
            keyboardController?.show()
            runSearch(scriptRoot)
        }

        val visibleResults = remember(results, sortDescending) {
            if (!sortDescending) results else results.sortedWith(
                compareBy<File> { !it.isDirectory }
                    .thenByDescending { it.name.lowercase(Locale.ROOT) }
                    .thenByDescending { it.path }
            )
        }

        Box(
            Modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable { onDismiss() }
        ) {
            Column(
                Modifier.align(Alignment.TopCenter)
                    .padding(top = 28.dp)
                    .fillMaxWidth(0.80f)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .shadow(20.dp, RoundedCornerShape(6.dp))
                    .clip(RoundedCornerShape(6.dp))
                    .background(MiuixTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                TextField(value = query, onValueChange = {
                    query = it
                    if (error?.startsWith("正则表达式无效") == true) error = null
                }, modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    singleLine = true, label = "关键字")
                Spacer(Modifier.height(7.dp))
                SearchOption("搜索子文件夹", includeSubdirectories, Modifier.fillMaxWidth()) {
                    includeSubdirectories = it
                }
                SearchOption("正则表达式", useRegex, Modifier.fillMaxWidth()) {
                    useRegex = it
                }
                Row(
                    Modifier.fillMaxWidth().height(48.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val relativeDir = File(currentDir).absolutePath
                        .removePrefix(scriptRoot.absolutePath).trimStart(File.separatorChar)
                        .replace(File.separatorChar, '›')
                    Text(
                        if (relativeDir.isEmpty()) "内部存储  ›  ${scriptRoot.name}"
                        else "内部存储  ›  ${scriptRoot.name}  ›  $relativeDir",
                        modifier = Modifier.weight(1f).clickable {
                            if (currentDir != scriptRoot.absolutePath) runSearch(scriptRoot)
                        },
                        fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MiuixTheme.colorScheme.onBackgroundVariant
                    )
                    SearchIcon(if (sortDescending) R.drawable.ic_sort_descending_24dp
                        else R.drawable.ic_sort_ascending_24dp,
                        if (sortDescending) "降序" else "升序",
                        active = sortDescending) { sortDescending = !sortDescending }
                    SearchIcon(R.drawable.ic_filter_funnel_24dp, "隐藏文件",
                        active = includeHidden) {
                        includeHidden = !includeHidden
                        runSearch()
                    }
                }
                val status = when {
                    searching -> "正在搜索…"
                    error != null -> error!!
                    results.isEmpty() -> "未找到匹配项"
                    truncated -> "显示前 ${results.size} 项，结果已达到上限"
                    else -> null
                }
                if (status != null) {
                    Text(status, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp),
                        color = if (error != null) Color(0xFFD1495B)
                        else MiuixTheme.colorScheme.onBackgroundVariant)
                }
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(min = 210.dp, max = 225.dp),
                    state = listState,
                ) {
                    items(visibleResults, key = { it.absolutePath }, contentType = { it.isDirectory }) { file ->
                        val isDirectory = file.isDirectory
                        val isProject = isDirectory &&
                                MiuixFileSearch.typeLabel(file) == "AI.js Pro 项目"
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                onItemSelected(file)
                            }.padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier.size(50.dp).clip(RoundedCornerShape(25.dp))
                                    .background(when {
                                        isProject -> Color(0xFF6677D2)
                                        isDirectory -> Color(0xFF2196ED)
                                        else -> Color(0xFF8A63D2)
                                    }),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    painter = painterResource(when {
                                        isProject -> R.drawable.ic_project_compass_24dp
                                        isDirectory -> R.drawable.ic_folder_outline_24dp
                                        else -> R.drawable.ic_code_file_24dp
                                    }),
                                    contentDescription = null,
                                    modifier = Modifier.size(26.dp),
                                    colorFilter = ColorFilter.tint(Color.White)
                                )
                            }
                            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                Text(file.name, fontSize = 17.sp, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MiuixTheme.colorScheme.onBackground)
                                val parent = MiuixFileSearch.relativeParent(File(currentDir), file)
                                val type = (if (parent.isEmpty()) "" else "$parent · ") +
                                        if (isDirectory) "文件夹" else MiuixFileSearch.typeLabel(file)
                                Text(type, fontSize = 12.sp, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MiuixTheme.colorScheme.onBackgroundVariant)
                                Text("修改于 ${timestamp.format(Date(file.lastModified()))}",
                                    fontSize = 11.sp, maxLines = 1,
                                    color = MiuixTheme.colorScheme.onBackgroundVariant)
                            }
                            if (isDirectory) {
                                Image(painterResource(R.drawable.ic_folder_enter_24dp),
                                    contentDescription = "进入文件夹",
                                    modifier = Modifier.size(38.dp).padding(7.dp),
                                    colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.primary))
                            }
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp)
                            .background(MiuixTheme.colorScheme.onBackground.copy(alpha = 0.10f)))
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    DialogAction("取消", enabled = true) { onDismiss() }
                    DialogAction(if (searching) "搜索中…" else "重新搜索",
                        enabled = !searching && query.text.isNotBlank()) { runSearch() }
                }
            }
        }
    }

    @Composable
    private fun SearchOption(
        label: String,
        checked: Boolean,
        modifier: Modifier,
        onCheckedChange: (Boolean) -> Unit
    ) {
        Row(modifier.height(36.dp).clickable { onCheckedChange(!checked) },
            verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = onCheckedChange)
            Text(label, fontSize = 14.sp, color = MiuixTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 4.dp))
        }
    }

    @Composable
    private fun DialogAction(text: String, enabled: Boolean, onClick: () -> Unit) {
        Box(
            Modifier.height(48.dp)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text, fontSize = 16.sp,
                color = if (enabled) MiuixTheme.colorScheme.primary
                else MiuixTheme.colorScheme.onBackgroundVariant.copy(alpha = 0.55f))
        }
    }

    @Composable
    private fun SearchIcon(
        icon: Int,
        description: String,
        active: Boolean,
        onClick: () -> Unit
    ) {
        Image(
            painter = painterResource(icon),
            contentDescription = description,
            modifier = Modifier.size(40.dp).clickable(onClick = onClick).padding(8.dp),
            colorFilter = ColorFilter.tint(
                if (active) MiuixTheme.colorScheme.primary
                else MiuixTheme.colorScheme.onBackground
            )
        )
    }
