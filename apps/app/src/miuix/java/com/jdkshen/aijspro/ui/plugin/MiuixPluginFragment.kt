package com.jdkshen.aijspro.ui.plugin

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.jdkshen.aijspro.R
import com.jdkshen.aijspro.theme.AijsMiuixTheme
import com.jdkshen.aijspro.ui.main.MainPageSearchHandler
import com.jdkshen.aijspro.ui.main.ViewPagerFragment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale

/**
 * Auto.js Pro-like "插件" page (fourth tab). Local plugin management: lists apps
 * registered through the Auto.js plugin SDK, plus install-local-APK entry.
 */
class MiuixPluginFragment : ViewPagerFragment(-1), MainPageSearchHandler {

    private data class PluginEntry(
        val label: String,
        val summary: String,
        val packageName: String,
        val installed: Boolean
    )

    private lateinit var rootView: ComposeView
    private var query by mutableStateOf(TextFieldValue(""))
    private var showSearchDialog by mutableStateOf(false)
    private var plugins by mutableStateOf<List<PluginEntry>?>(null)
    private var loadError by mutableStateOf<String?>(null)
    private var reloadTick by mutableStateOf(0)
    private var selected by mutableStateOf<PluginEntry?>(null)

    private val installLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            val install = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(install)
        }
    }

    override fun openPageSearch() {
        showSearchDialog = true
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        rootView = ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AijsMiuixTheme {
                    LaunchedEffect(reloadTick) {
                        loadError = null
                        try {
                            plugins = withContext(Dispatchers.IO) { loadPlugins() }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            loadError = error.localizedMessage ?: "无法读取插件"
                        }
                    }
                    val all = plugins.orEmpty()
                    val visible = remember(all, query.text) {
                        val needle = query.text.trim()
                        all.filter { entry ->
                            needle.isEmpty() || entry.label.contains(needle, ignoreCase = true) ||
                                    entry.packageName.contains(needle, ignoreCase = true)
                        }
                    }
                    Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
                        PluginToolbar(
                            resultCount = visible.size,
                            onRefresh = { reloadTick++ },
                            onSearch = { showSearchDialog = true }
                        )
                        LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentPadding = PaddingValues(bottom = 18.dp)
                        ) {
                            when {
                                loadError != null -> item {
                                    MessagePanel("读取失败：$loadError", "重试") { reloadTick++ }
                                }
                                all.isEmpty() -> item { MessagePanel("正在读取插件…") }
                                visible.isEmpty() -> item {
                                    MessagePanel("没有匹配的插件，请修改关键词。")
                                }
                            }
                            items(visible, key = { "${it.packageName}:${it.label}" }) { entry ->
                                PluginRow(entry) { selected = it }
                            }
                        }
                    }

                    if (showSearchDialog) {
                        SearchDialog(dismiss = { showSearchDialog = false })
                    }
                    selected?.let { entry ->
                        PluginDetailDialog(entry, dismiss = { selected = null })
                    }
                }
            }
        }
        return rootView
    }

    // ---------- data ----------

    private fun loadPlugins(): List<PluginEntry> {
        val list = mutableListOf<PluginEntry>()
        val pm = requireContext().packageManager
        try {
            for (app in pm.getInstalledApplications(PackageManager.GET_META_DATA)) {
                if (app.metaData == null || !app.metaData.containsKey(PLUGIN_REGISTRY_KEY)) continue
                val info = pm.getPackageInfo(app.packageName, 0)
                val label = pm.getApplicationLabel(app).toString()
                val version = info.versionName ?: info.versionCode.toString()
                list += PluginEntry(label, "已安装 · $version", app.packageName, true)
            }
        } catch (_: RuntimeException) {
        } catch (_: PackageManager.NameNotFoundException) {
        }
        list.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
        list += PluginEntry("安装本地插件 APK", "选择设备中的 APK 文件", "", false)
        return list
    }

    private fun pickInstallApk() {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/vnd.android.package-archive")
        installLauncher.launch(Intent.createChooser(picker, "选择插件 APK"))
    }

    private fun openPlugin(entry: PluginEntry) {
        val launch = requireContext().packageManager.getLaunchIntentForPackage(entry.packageName)
        if (launch != null) startActivity(launch)
        else Toast.makeText(requireContext(), "该插件没有独立启动页面", Toast.LENGTH_SHORT).show()
    }

    private fun showAppDetails(entry: PluginEntry) {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${entry.packageName}")))
    }

    private fun checkUpdate(entry: PluginEntry) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("market://details?id=${entry.packageName}"))
                .setPackage("com.android.vending"))
        } catch (_: ActivityNotFoundException) {
            val web = Intent(Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id=${entry.packageName}"))
                .addCategory(Intent.CATEGORY_BROWSABLE)
            startActivity(Intent.createChooser(web, "打开应用页面"))
        }
    }

    private fun uninstall(entry: PluginEntry) {
        startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${entry.packageName}")))
    }

    override fun onFabClick(fab: FloatingActionButton?) = Unit

    override fun onBackPressed(activity: Activity): Boolean = when {
        selected != null -> { selected = null; true }
        showSearchDialog -> { showSearchDialog = false; true }
        query.text.isNotEmpty() -> { query = TextFieldValue(""); true }
        else -> false
    }

    // ---------- ui ----------

    @Composable
    private fun PluginToolbar(resultCount: Int, onRefresh: () -> Unit, onSearch: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().height(54.dp)
                .background(MiuixTheme.colorScheme.surface)
                .padding(start = 18.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("插件", fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (query.text.isNotBlank()) {
                    Text("$resultCount 项 · 搜索结果", fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary)
                }
            }
            ToolbarAction(R.drawable.ic_refresh_white_24dp, "刷新", onRefresh)
            ToolbarAction(R.drawable.ic_search_white_24dp, "搜索插件", onSearch)
        }
    }

    @Composable
    private fun ToolbarAction(icon: Int, description: String, onClick: () -> Unit) {
        Box(Modifier.size(48.dp).clickable(onClick = onClick)) {
            Image(painterResource(icon), contentDescription = description,
                colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurface),
                modifier = Modifier.size(27.dp).align(Alignment.Center))
        }
    }

    @Composable
    private fun PluginRow(entry: PluginEntry, open: (PluginEntry) -> Unit) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 78.dp).clickable { open(entry) }
                    .padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(50.dp).clip(CircleShape).background(
                    if (entry.installed) Color(0xFF2196F3) else Color(0xFF009688))) {
                    Image(
                        painterResource(if (entry.installed) R.drawable.ic_nav_market
                        else R.drawable.ic_add_white_48dp),
                        contentDescription = null,
                        modifier = Modifier.size(28.dp).align(Alignment.Center),
                        colorFilter = ColorFilter.tint(Color.White)
                    )
                }
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(entry.label, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(entry.summary, fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary)
                    if (entry.installed) {
                        Text(entry.packageName, fontSize = 12.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary)
                    }
                }
                Image(painterResource(R.drawable.ic_chevron_right), contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceSecondary))
            }
            Box(Modifier.fillMaxWidth().padding(start = 82.dp).height(1.dp)
                .background(MiuixTheme.colorScheme.dividerLine))
        }
    }

    @Composable
    private fun SearchDialog(dismiss: () -> Unit) {
        Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Card(Modifier.fillMaxWidth(0.84f)) {
                Column(Modifier.fillMaxWidth().padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextField(query, { query = it }, Modifier.fillMaxWidth(),
                        singleLine = true, label = "搜索插件名称或包名")
                    Text("支持安装本地插件 APK；插件通过 Auto.js 插件 SDK 注册后自动出现在列表。",
                        fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        if (query.text.isNotBlank()) {
                            Button(onClick = { query = TextFieldValue("") }) { Text("清除") }
                            Spacer(Modifier.width(8.dp))
                        }
                        Button(onClick = dismiss) { Text("完成") }
                    }
                }
            }
        }
    }

    @Composable
    private fun PluginDetailDialog(entry: PluginEntry, dismiss: () -> Unit) {
        Dialog(onDismissRequest = dismiss) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()
                    .background(MiuixTheme.colorScheme.surface)
                    .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(entry.label, fontSize = 21.sp, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, color = MiuixTheme.colorScheme.onSurface)
                    Text(
                        buildString {
                            append(entry.summary)
                            if (entry.installed) append("\n包名：").append(entry.packageName)
                        },
                        fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                    if (!entry.installed) {
                        ActionChoice("选择插件 APK") {
                            dismiss()
                            pickInstallApk()
                        }
                    } else {
                        ActionChoice("打开插件") { dismiss(); openPlugin(entry) }
                        ActionChoice("应用详情") { dismiss(); showAppDetails(entry) }
                        ActionChoice("检查更新") { dismiss(); checkUpdate(entry) }
                        ActionChoice("卸载插件") { dismiss(); uninstall(entry) }
                    }
                    ActionChoice("取消", dismiss)
                }
            }
        }
    }

    @Composable
    private fun ActionChoice(label: String, onClick: () -> Unit) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.14f))
            .clickable(onClick = onClick).padding(vertical = 14.dp, horizontal = 16.dp)) {
            Text(label, fontSize = 16.sp, color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.align(Alignment.CenterStart))
        }
    }

    @Composable
    private fun MessagePanel(text: String, action: String? = null,
        textColor: Color = MiuixTheme.colorScheme.onSurface, onAction: () -> Unit = {}) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Miuix 的 Text 默认两端对齐，长段中文会被拉伸成大字距——提示文本明确左对齐。
            Text(text, color = textColor,
                textAlign = androidx.compose.ui.text.style.TextAlign.Start)
            action?.let { Button(onClick = onAction) { Text(it) } }
        }
    }

    private companion object {
        const val PLUGIN_REGISTRY_KEY = "org.autojs.plugin.sdk.registry"
    }
}
