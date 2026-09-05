package com.jdkshen.aijspro.ui.main

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsControllerCompat
import com.jdkshen.aijspro.Pref
import com.jdkshen.aijspro.theme.AijsMiuixTheme
import com.jdkshen.aijspro.theme.MiuixBackButton
import com.jdkshen.aijspro.theme.isAijsDarkTheme
import com.jdkshen.aijspro.ui.edit.EditActivity
import com.jdkshen.aijspro.ui.edit.EditorView
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
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

        Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
            SmallTopAppBar(title = "搜索", defaultWindowInsetsPadding = false,
                navigationIcon = { MiuixBackButton(onClick = { finish() }) })
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Spacer(Modifier.height(4.dp))
                Text("关键字", fontSize = 14.sp, color = MiuixTheme.colorScheme.onBackgroundVariant)
                TextField(value = query, onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(Modifier.clickable { subDirs = !subDirs },
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
                Text(currentDir, fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(4.dp))
                if (results.isEmpty()) {
                    Text("未找到匹配项", fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                        modifier = Modifier.padding(top = 12.dp))
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
                            }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(if (isDir) "\uD83D\uDCC1" else "\uD83D\uDCC4", fontSize = 18.sp)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(file.name, fontSize = 15.sp,
                                    color = MiuixTheme.colorScheme.onBackground)
                                Text(file.parent ?: "", fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onBackgroundVariant)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                TextButton(text = "取消", modifier = Modifier.weight(1f),
                    onClick = { finish() })
                TextButton(text = "重新搜索", modifier = Modifier.weight(1f),
                    onClick = {
                        results = searchFiles(File(currentDir), query.text, subDirs, useRegex)
                    })
            }
        }
    }
}
