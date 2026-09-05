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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsControllerCompat
import com.jdkshen.aijspro.Pref
import com.jdkshen.aijspro.R
import com.jdkshen.aijspro.theme.AijsMiuixTheme
import com.jdkshen.aijspro.theme.isAijsDarkTheme
import com.jdkshen.aijspro.ui.edit.EditActivity
import com.jdkshen.aijspro.ui.edit.EditorView
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

/**
 * Miuix file search page, mirrors Auto.js Pro's advanced search:
 * keyword + sub-directories/regex options + result list + cancel/re-search.
 */
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
                    SearchPage()
                }
            }
        })
    }

    private fun searchFiles(dir: File, keyword: String, subDirs: Boolean, useRegex: Boolean): List<Pair<File, Boolean>> {
        val q = keyword.trim()
        if (q.isEmpty() || !dir.isDirectory) return emptyList()
        val pattern = if (useRegex) {
            try {
                Pattern.compile(q)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
        val out = ArrayList<Pair<File, Boolean>>()
        val stack = ArrayDeque<File>()
        stack.add(dir)
        while (stack.isNotEmpty()) {
            val d = stack.removeLast()
            val children = d.listFiles() ?: continue
            for (f in children) {
                val name = f.name
                val match = if (pattern != null) pattern.matcher(name).find()
                else name.contains(q, ignoreCase = true)
                if (match) out.add(f to f.isDirectory)
                if (subDirs && f.isDirectory) stack.add(f)
            }
        }
        return out.sortedWith(compareBy({ !it.second }, { it.first.name.lowercase() }))
    }

    private fun openFile(file: File) {
        if (file.isDirectory) return
        startActivity(Intent(this, EditActivity::class.java)
            .putExtra(EditorView.EXTRA_PATH, file.absolutePath))
    }

    @Composable
    private fun SearchPage() {
        var query by remember { mutableStateOf(TextFieldValue("")) }
        var subDirs by remember { mutableStateOf(true) }
        var useRegex by remember { mutableStateOf(false) }
        var currentDir by remember { mutableStateOf(Pref.getScriptDirPath()) }
        var results by remember { mutableStateOf<List<Pair<File, Boolean>>>(emptyList()) }

        Box(
            Modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable { finish() }
        ) {
            Column(
                Modifier.align(Alignment.Center)
                    .fillMaxWidth(0.88f)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .shadow(28.dp, RoundedCornerShape(24.dp))
                    .clip(RoundedCornerShape(24.dp))
                    .background(MiuixTheme.colorScheme.surface)
                    .padding(horizontal = 20.dp, vertical = 18.dp)
            ) {
                Text("关键字", fontSize = 13.sp, color = MiuixTheme.colorScheme.onBackgroundVariant)
                TextField(value = query, onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(Modifier.clickable { subDirs = !subDirs }.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = subDirs, onCheckedChange = { subDirs = it })
                    Text("搜索子文件夹", fontSize = 15.sp,
                        color = MiuixTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(start = 4.dp))
                }
                Row(Modifier.clickable { useRegex = !useRegex },
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = useRegex, onCheckedChange = { useRegex = it })
                    Text("正则表达式", fontSize = 15.sp,
                        color = MiuixTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(start = 4.dp))
                }
                Spacer(Modifier.height(10.dp))
                Text(currentDir, fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onBackgroundVariant)
                Spacer(Modifier.height(6.dp))
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 340.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (results.isEmpty()) {
                        Text("未找到匹配项", fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                            modifier = Modifier.padding(top = 10.dp))
                    } else {
                        results.forEach { (file, isDir) ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    if (isDir) {
                                        currentDir = file.absolutePath
                                        results = searchFiles(file, query.text, subDirs, useRegex)
                                    } else {
                                        openFile(file)
                                    }
                                }.padding(vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier.size(44.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(if (isDir) Color(0xFF3D7EEA) else Color(0xFF8A63D2)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Image(
                                        painter = painterResource(if (isDir) R.drawable.ic_folder_outline_24dp
                                        else R.drawable.ic_code_file_24dp),
                                        contentDescription = null,
                                        modifier = Modifier.size(22.dp),
                                        colorFilter = ColorFilter.tint(Color.White)
                                    )
                                }
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(file.name, fontSize = 15.sp,
                                        color = MiuixTheme.colorScheme.onBackground)
                                    val label =
                                        (if (isDir) "文件夹" else "文件") + " · " +
                                                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                                                    .format(Date(file.lastModified()))
                                    Text(label, fontSize = 12.sp,
                                        color = MiuixTheme.colorScheme.onBackgroundVariant)
                                }
                                Text("打开", fontSize = 13.sp,
                                    color = MiuixTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(text = "取消", modifier = Modifier.padding(end = 8.dp),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        onClick = { finish() })
                    TextButton(text = "重新搜索",
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        onClick = {
                            results = searchFiles(File(currentDir), query.text, subDirs, useRegex)
                        })
                }
            }
        }
    }
}
