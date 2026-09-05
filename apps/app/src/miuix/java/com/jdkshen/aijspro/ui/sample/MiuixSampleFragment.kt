package com.jdkshen.aijspro.ui.sample

import android.os.Bundle
import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.jdkshen.aijspro.R
import com.jdkshen.aijspro.model.sample.SampleFile
import com.jdkshen.aijspro.model.script.Scripts
import com.jdkshen.aijspro.theme.AijsMiuixTheme
import com.jdkshen.aijspro.theme.MiuixBackButton
import com.jdkshen.aijspro.ui.common.ScriptOperations
import com.jdkshen.aijspro.ui.edit.ViewSampleActivity
import com.jdkshen.aijspro.ui.main.ViewPagerFragment
import io.reactivex.android.schedulers.AndroidSchedulers
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.extra.SuperDialog
import java.io.File

/** Miuix tutorial/sample browser. Assets and execution/import services remain unchanged. */
class MiuixSampleFragment : ViewPagerFragment(-1) {
    private lateinit var rootView: ComposeView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        rootView = ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AijsMiuixTheme {
                    var path by remember { mutableStateOf("sample") }
                    var query by remember { mutableStateOf(TextFieldValue("")) }
                    var filter by remember { mutableIntStateOf(0) }
                    val importEntry = remember { mutableStateOf<Entry?>(null) }
                    var importName by remember { mutableStateOf(TextFieldValue("")) }
                    val entries = remember(path, query.text, filter) {
                        loadEntries(path, query.text.trim(), filter)
                    }
                    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                        TextField(query, { query = it }, Modifier.fillMaxWidth().padding(top = 10.dp),
                            singleLine = true, label = "搜索示例（包含子目录）")
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterButton("全部", filter == 0) { filter = 0 }
                            FilterButton("JavaScript", filter == 1) { filter = 1 }
                            FilterButton("文件夹", filter == 2) { filter = 2 }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                            if (path != "sample") {
                                MiuixBackButton(onClick = { path = path.substringBeforeLast('/', "sample") })
                            }
                            Text(path.removePrefix("sample/").ifEmpty { "示例代码" },
                                fontSize = 18.sp, modifier = Modifier.padding(start = 10.dp))
                        }
                        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(entries, key = { it.path }) { entry -> SampleRow(entry,
                                enter = { path = it }, requestImport = {
                                    importName = TextFieldValue(File(it.name).nameWithoutExtension)
                                    importEntry.value = it
                                }) }
                        }
                    }
                    ImportDialog(importEntry, importName, { importName = it })
                }
            }
        }
        return rootView
    }

    @androidx.compose.runtime.Composable
    private fun FilterButton(label: String, selected: Boolean, click: () -> Unit) {
        Button(onClick = click) {
            Text(if (selected) "✓ $label" else label)
        }
    }

    @androidx.compose.runtime.Composable
    private fun SampleRow(entry: Entry, enter: (String) -> Unit, requestImport: (Entry) -> Unit) {
        Card(Modifier.fillMaxWidth().clickable {
            if (entry.directory) enter(entry.path)
            else ViewSampleActivity.view(requireContext(), SampleFile(entry.path, requireContext().assets))
        }) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(if (entry.directory) R.drawable.ic_folder_outline_24dp
                    else R.drawable.ic_floating_action_menu_file), entry.name,
                    colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.primary))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(entry.name, fontSize = 17.sp)
                    Text(if (entry.directory) "示例分类" else "JavaScript 示例",
                        fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                }
                if (!entry.directory) {
                    Button(onClick = { run(entry) }, modifier = Modifier.padding(end = 6.dp)) { Text("运行") }
                    Button(onClick = { requestImport(entry) }) { Text("导入") }
                }
            }
        }
    }

    private fun run(entry: Entry) {
        Scripts.run(SampleFile(entry.path, requireContext().assets).toSource())
        Toast.makeText(requireContext(), "已运行：${entry.name}", Toast.LENGTH_SHORT).show()
    }

    @androidx.compose.runtime.Composable
    private fun ImportDialog(selected: MutableState<Entry?>, name: TextFieldValue,
                             onName: (TextFieldValue) -> Unit) {
        val show = remember { mutableStateOf(false) }
        show.value = selected.value != null
        SuperDialog(show = show, title = "导入示例", summary = "复制到我的脚本",
            onDismissRequest = { selected.value = null }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                TextField(name, onName, Modifier.fillMaxWidth(), singleLine = true, label = "文件名")
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.End) {
                    Button(onClick = { selected.value = null }, modifier = Modifier.padding(end = 8.dp)) {
                        Text("取消")
                    }
                    Button(onClick = {
                        selected.value?.let { import(it, name.text) }
                        selected.value = null
                    }) { Text("导入") }
                }
            }
        }
    }

    private fun import(entry: Entry, name: String) {
        if (name.trim().isEmpty()) {
            Toast.makeText(requireContext(), "请输入文件名", Toast.LENGTH_SHORT).show()
            return
        }
        ScriptOperations(requireContext(), rootView)
            .importSampleWithName(SampleFile(entry.path, requireContext().assets), name.trim())
            .subscribe({ path -> Toast.makeText(requireContext(), "已导入：$path", Toast.LENGTH_LONG).show() },
                { error -> Toast.makeText(requireContext(), "导入失败：${error.message}", Toast.LENGTH_LONG).show() })
    }

    private fun loadEntries(path: String, query: String, filter: Int): List<Entry> {
        val result = mutableListOf<Entry>()
        fun collect(dir: String, recursive: Boolean) {
            requireContext().assets.list(dir).orEmpty().forEach { name ->
                val child = "$dir/$name"
                val directory = requireContext().assets.list(child)?.isNotEmpty() == true
                if ((query.isEmpty() || name.contains(query, true)) &&
                    (filter == 0 || filter == 1 && !directory && name.endsWith(".js", true) || filter == 2 && directory)) {
                    result += Entry(name, child, directory)
                }
                if (recursive && directory && result.size < 500) collect(child, true)
            }
        }
        collect(path, query.isNotEmpty())
        return result.distinctBy { it.path }.sortedWith(compareBy<Entry> { !it.directory }.thenBy { it.name.lowercase() })
    }

    override fun onFabClick(fab: FloatingActionButton?) = Unit
    override fun onBackPressed(activity: Activity): Boolean = false
    private data class Entry(val name: String, val path: String, val directory: Boolean)
}
