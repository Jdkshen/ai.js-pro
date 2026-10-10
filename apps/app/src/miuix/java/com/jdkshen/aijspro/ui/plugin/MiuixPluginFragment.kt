package com.jdkshen.aijspro.ui.plugin

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.jdkshen.aijspro.R
import com.jdkshen.aijspro.model.plugin.PluginRegistry
import com.jdkshen.aijspro.theme.AijsMiuixTheme
import com.jdkshen.aijspro.ui.main.MainPageSearchHandler
import com.jdkshen.aijspro.ui.main.ViewPagerFragment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale

/**
 * Auto.js Pro-like "插件" page (fourth tab). Local plugin management: lists apps
 * registered through the Auto.js plugin SDK, plus install-local-APK entry.
 */
class MiuixPluginFragment : ViewPagerFragment(-1), MainPageSearchHandler {

    private lateinit var rootView: ComposeView
    private var query by mutableStateOf(TextFieldValue(""))
    private var showSearchDialog by mutableStateOf(false)
    private var plugins by mutableStateOf<List<PluginRegistry.PluginEntry>?>(null)
    private var loadError by mutableStateOf<String?>(null)
    private var scanError by mutableStateOf<String?>(null)
    private var reloadTick by mutableStateOf(0)
    private var selected by mutableStateOf<PluginRegistry.PluginEntry?>(null)
    private var refreshOnResume = false

    /** 卡片开关的本地状态（按包名）。这一页只展示与记忆，真实启停仍走系统设置。 */
    private var enabledState by mutableStateOf<Map<String, Boolean>>(emptyMap())

    /** 一次扫描的产物：列表 + 系统级扫描失败原因（两者可同时存在）。 */
    private data class ScanState(
        val entries: List<PluginRegistry.PluginEntry>,
        val scanError: String?
    )

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
                        if (plugins != null && reloadTick == 0) return@LaunchedEffect
                        loadError = null
                        scanError = null
                        try {
                            val state = withContext(Dispatchers.IO) { loadPlugins() }
                            plugins = state.entries
                            scanError = state.scanError
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            loadError = error.localizedMessage ?: "无法读取插件"
                        }
                    }
                    // Refresh after returning from installation/app settings, without
                    // rescanning packages for ordinary ViewPager tab changes.
                    val lifecycle = viewLifecycleOwner.lifecycle
                    DisposableEffect(lifecycle) {
                        val observer = LifecycleEventObserver { _, event ->
                            if (event == Lifecycle.Event.ON_RESUME && refreshOnResume) {
                                refreshOnResume = false
                                reloadTick++
                            }
                        }
                        lifecycle.addObserver(observer)
                        onDispose { lifecycle.removeObserver(observer) }
                    }
                    val listState = rememberLazyGridState()
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
                        // 卡片式两列网格（对齐官方 Auto.js Pro 的插件页）：
                        // 每张卡 = 封面（插件图标）+ 名称 + 版本 + 状态 + 包名 + 启用开关。
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            state = listState,
                            contentPadding = PaddingValues(
                                start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            when {
                                loadError != null -> item(span = { GridItemSpan(2) }) {
                                    MessagePanel("读取失败：$loadError", "重试") { reloadTick++ }
                                }
                                plugins == null -> item(span = { GridItemSpan(2) }) {
                                    MessagePanel("正在读取插件…")
                                }
                                visible.isEmpty() -> item(span = { GridItemSpan(2) }) {
                                    MessagePanel("没有匹配的插件，请修改关键词。")
                                }
                            }
                            // 扫描系统出错时消息卡占满整行；否则只看列表会误判成「没装插件」。
                            scanError?.let { error ->
                                item(span = { GridItemSpan(2) }) {
                                    MessagePanel(
                                        "系统包扫描失败：$error\n" +
                                            "列表可能不完整。可先下拉刷新；若仍为空，请把这一行反馈给开发者。",
                                        "重试扫描"
                                    ) { reloadTick++ }
                                }
                            }
                            items(visible, key = { it.packageName + it.isInstalled() }) { entry ->
                                PluginCard(entry) { selected = it }
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

    /**
     * 扫描本机已安装的插件。
     *
     * 事实层（过滤、排序、两遍扫描策略）在 [PluginRegistry] 里，本函数只做 PackageManager 适配。
     *
     * 与旧实现的差别（真机依据：Redmi 2602BRT18C / Android 16 / HyperOS，2026-10-10
     * 插件页一个插件都扫不出来，而同版本宿主在 K40 上正常）：
     *  1. 不再用 `catch (_: RuntimeException) {}` 把扫描异常吞掉 —— 那会让「扫不到」和
     *     「没装插件」在界面上长得一模一样，现场也留不下任何证据。异常现在回传给界面显示。
     *  2. 批量接口没给出任何注册插件、但确实存在像插件的包时，逐个 `getPackageInfo`
     *     取 meta-data 兜底（有些系统会裁剪批量返回的 metaData）。
     */
    private fun loadPlugins(): ScanState {
        val pm = requireContext().packageManager
        var bulkApps: List<ApplicationInfo>? = null
        var scanError: String? = null
        try {
            bulkApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        } catch (error: Exception) {
            // 旧实现把这里静默吞掉，导致插件页只剩「安装本地插件 APK」且无从排查。
            scanError = "${error.javaClass.simpleName}: ${error.localizedMessage ?: "无详细信息"}"
        }

        val adapted = (bulkApps ?: emptyList()).map { app ->
            PluginRegistry.InstalledApp(
                app.packageName,
                runCatching { pm.getApplicationLabel(app).toString() }.getOrNull(),
                versionNameOf(pm, app.packageName),
                versionCodeOf(pm, app.packageName),
                registryClassOf(app)
            )
        }

        val result = PluginRegistry.build(
            adapted,
            { packageName -> registryClassOfQuerying(pm, packageName) },
            true,
            KNOWN_PLUGIN_PACKAGES,
            { packageName -> matchOfQuerying(pm, packageName) }
        )
        if (result.entries.size <= 1) {
            // 除固定项外一个都没扫到 —— 打印一次现场，好定位是「系统没返回该包」
            // 还是「返回了但 metaData 被裁剪」。
            reportScanDiagnostics(pm, adapted.size, result.entries.size - 1, result.candidateCount)
        }
        return ScanState(result.entries, scanError)
    }

    /**
     * 一次性诊断：插件页扫不到东西时，把「系统到底返回了什么」打出来。
     *
     * 起因：Redmi 2602BRT18C / Android 16 / HyperOS 上插件页一个都扫不出来，而
     * `pm list packages` 明确能列出 com.jdkshen.xiangqi.plugin，该插件的 registry
     * 声明与宿主 QUERY_ALL_PACKAGES 都已逐项验证；同版本宿主在 K40（LineageOS）上正常。
     *
     * 用 System.err 是为了在 logcat 里有稳定 tag（System.err）；只在扫描为空时调用一次，
     * 正常机型不会有输出。
     */
    private fun reportScanDiagnostics(
        pm: PackageManager,
        bulkCount: Int,
        registeredCount: Int,
        candidateCount: Int
    ) {
        try {
            val probe = "com.jdkshen.xiangqi.plugin"
            val inBulk = try {
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    .any { it.packageName == probe }
            } catch (_: Exception) {
                false
            }
            val packagesWithMeta = try {
                pm.getInstalledPackages(PackageManager.GET_META_DATA).size
            } catch (_: Exception) {
                -1
            }
            val probeMeta = try {
                val meta = pm.getPackageInfo(probe, PackageManager.GET_META_DATA)
                    .applicationInfo?.metaData
                "meta=$meta registry=${meta?.getString(PLUGIN_REGISTRY_KEY)}"
            } catch (error: Exception) {
                "${error.javaClass.simpleName}: ${error.localizedMessage}"
            }
            System.err.println(
                "[plugin-scan] sdk=${Build.VERSION.SDK_INT}" +
                    " bulk=$bulkCount registered=$registeredCount candidates=$candidateCount" +
                    " bulkHasProbe=$inBulk packagesWithMeta=$packagesWithMeta" +
                    " probeMeta=$probeMeta"
            )
        } catch (_: Exception) {
            // 诊断本身绝不影响功能
        }
    }

    /** 从批量接口拿到的 meta-data 里读注册类。 */
    private fun registryClassOf(app: ApplicationInfo): String? =
        app.metaData?.getString(PLUGIN_REGISTRY_KEY)?.takeIf { it.isNotBlank() }

    /**
     * 慢路径：对单个包重新查询 meta-data。
     *
     * 有些系统（真机见到的是 HyperOS）会对批量返回的 metaData 做裁剪；用 GET_META_DATA
     * 单独查这个包通常仍能拿到。查询失败按「不是插件」处理，不抛给调用方。
     */
    private fun registryClassOfQuerying(pm: PackageManager, packageName: String): String? =
        matchOfQuerying(pm, packageName)?.registryClass

    /**
     * 精确查询一个包，连展示用的名称/版本一起带回来。
     *
     * 为什么需要名称/版本：真机 Android 16 上批量枚举不返回插件包，走精确查询时
     * 列表里只剩包名可显示，版本会变成 null。这里顺带取一次，界面就能显示
     * 「象棋引擎插件 / 已安装 · 1.0.0」而不是「com.…plugin / 已安装 · null」。
     */
    private fun matchOfQuerying(pm: PackageManager, packageName: String): PluginRegistry.PackageMatch? =
        try {
            val info = pm.getPackageInfo(packageName, PackageManager.GET_META_DATA)
            val registryClass = info.applicationInfo?.metaData
                ?.getString(PLUGIN_REGISTRY_KEY)
                ?.takeIf { it.isNotBlank() }
            val versionName = info.versionName
                ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    info.longVersionCode.toString()
                } else {
                    @Suppress("DEPRECATION")
                    info.versionCode.toString()
                }
            val label = info.applicationInfo
                ?.let { app -> runCatching { pm.getApplicationLabel(app).toString() }.getOrNull() }
            PluginRegistry.PackageMatch(registryClass, label, versionName)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (_: RuntimeException) {
            null
        }

    private fun versionNameOf(pm: PackageManager, packageName: String): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(packageName, 0).versionName
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, 0).versionName
        }
    } catch (_: Exception) {
        null
    }

    private fun versionCodeOf(pm: PackageManager, packageName: String): Int = try {
        val info = pm.getPackageInfo(packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
    } catch (_: Exception) {
        0
    }

    private fun pickInstallApk() {
        refreshOnResume = true
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/vnd.android.package-archive")
        installLauncher.launch(Intent.createChooser(picker, "选择插件 APK"))
    }

    private fun openPlugin(entry: PluginRegistry.PluginEntry) {
        val launch = requireContext().packageManager.getLaunchIntentForPackage(entry.packageName)
        if (launch != null) startActivity(launch)
        else Toast.makeText(requireContext(), "该插件没有独立启动页面", Toast.LENGTH_SHORT).show()
    }

    private fun showAppDetails(entry: PluginRegistry.PluginEntry) {
        refreshOnResume = true
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${entry.packageName}")))
    }

    private fun checkUpdate(entry: PluginRegistry.PluginEntry) {
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

    private fun uninstall(entry: PluginRegistry.PluginEntry) {
        refreshOnResume = true
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
            Modifier.fillMaxWidth().height(48.dp)
                .background(androidx.compose.ui.res.colorResource(R.color.item_background))
                .padding(start = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("插件", fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (query.text.isNotBlank()) {
                    Text("$resultCount 项 · 搜索结果", fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
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
                modifier = Modifier.size(24.dp).align(Alignment.Center))
        }
    }

    /**
     * 插件卡片（两列网格里的单元）。
     *
     * 形态对齐官方 Auto.js Pro 的插件页：
     *   封面（渐变 + 白底圆角方块包着插件自己的图标）
     *   → 名称 → 版本 → 状态（已安装/未安装）→ 包名 → 底部「状态图标 + 启用开关」。
     *
     * 与旧行式实现的关系：信息项没减少（名称/版本/状态/包名/开关都在），
     * 只是从「一行一条」改成「一卡一格」，并把封面补上（旧行式没有封面）。
     */
    @Composable
    private fun PluginCard(entry: PluginRegistry.PluginEntry, open: (PluginRegistry.PluginEntry) -> Unit) {
        val installed = entry.isInstalled()
        // 「安装本地插件 APK」那一项没有包名，是入口而不是插件：单独一套文案与封面。
        val localEntry = entry.packageName.isEmpty()
        val icon = rememberPluginIcon(entry.packageName, installed)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            // 卡片边界：参考图的卡片靠一圈细描边与页面底色区分。
            // 这里不用 Miuix 的 Card —— 它自带宽度约束，放进网格会让两列退化成单列（实测）。
            .border(1.dp, MiuixTheme.colorScheme.dividerLine, RoundedCornerShape(16.dp))
            .background(MiuixTheme.colorScheme.surfaceVariant).clickable { open(entry) }) {
            Column(Modifier.fillMaxWidth()) {
                // ---- 封面 ----
                Box(
                    Modifier.fillMaxWidth().height(96.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // 渐变底：与参考图一致的青绿双色
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.linearGradient(
                                listOf(Color(0xFF4DB6AC), Color(0xFF009688))
                            )
                        )
                    )
                    when {
                        icon != null -> {
                            // 白底圆角方块包住插件图标（参考图里那层白色「贴片」）
                            Box(
                                Modifier.size(64.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(Color.White),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    bitmap = icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(44.dp)
                                )
                            }
                        }
                        // 本地安装入口：用「+」表达「添加」，而不是拿首字当图标
                        localEntry -> Image(
                            painterResource(R.drawable.ic_add_white_48dp),
                            contentDescription = null,
                            modifier = Modifier.size(44.dp),
                            colorFilter = ColorFilter.tint(Color.White)
                        )
                        // 其余取不到图标的插件：用首字母占位，避免出现空封面
                        else -> Text(
                            entry.label.take(1),
                            fontSize = 34.sp,
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                // ---- 文本区 ----
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(entry.label, fontSize = 17.sp, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        // 本地安装入口没有版本号，summary 是说明文案，直接显示
                        if (localEntry) entry.summary else versionText(entry),
                        fontSize = 13.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary
                    )
                    if (!localEntry) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (installed) "本机已安装的 SDK 插件" else "尚未安装的 SDK 插件",
                            fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(entry.packageName, fontSize = 11.sp, maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary)
                    }
                    Spacer(Modifier.height(12.dp))
                    // ---- 底部：状态 + 开关 ----
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painterResource(R.drawable.ic_nav_market),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            colorFilter = ColorFilter.tint(
                                if (installed) Color(0xFF009688)
                                else MiuixTheme.colorScheme.onSurfaceSecondary
                            )
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            when {
                                localEntry -> "选择 APK 安装"
                                installed -> "已安装"
                                else -> "未安装"
                            },
                            fontSize = 13.sp,
                            color = if (installed) Color(0xFF009688)
                            else MiuixTheme.colorScheme.onSurfaceSecondary,
                            modifier = Modifier.weight(1f)
                        )
                        if (installed && !localEntry) {
                            Switch(
                                checked = enabledState[entry.packageName] ?: true,
                                onCheckedChange = { checked ->
                                    enabledState = enabledState +
                                        (entry.packageName to checked)
                                },
                                modifier = Modifier.height(28.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    /** 「版本 x.y.z」；summary 里带的是「已安装 · 1.0.0」，这里只取版本段。 */
    private fun versionText(entry: PluginRegistry.PluginEntry): String {
        val summary = entry.summary
        val dot = summary.indexOf('·')
        val version = if (dot >= 0) summary.substring(dot + 1).trim() else summary.trim()
        return if (version.isEmpty()) "版本未知" else "版本 $version"
    }

    /**
     * 取插件自己的应用图标（参考图封面里就是插件的图标）。
     *
     * 取不到返回 null，由调用方回落到首字母占位 —— 不让封面空着，也不让取图标失败影响列表。
     */
    @Composable
    private fun rememberPluginIcon(packageName: String, installed: Boolean): ImageBitmap? {
        if (!installed || packageName.isEmpty()) return null
        val context = LocalContext.current
        return remember(packageName) {
            runCatching {
                drawableToBitmap(context.packageManager.getApplicationIcon(packageName))
                    .asImageBitmap()
            }.getOrNull()
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
    private fun PluginDetailDialog(entry: PluginRegistry.PluginEntry, dismiss: () -> Unit) {
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
                            if (entry.isInstalled()) append("\n包名：").append(entry.packageName)
                        },
                        fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                    if (!entry.isInstalled()) {
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

    /**
     * Drawable → Bitmap。
     *
     * Compose 的 `Image` 需要 `ImageBitmap`。这里统一按 intrinsic 尺寸画一次；
     * 自适应图标（AdaptiveIconDrawable）在个别 ROM 上 intrinsic 可能 <= 0，用固定边长兜底。
     * 任何失败由调用方的 runCatching 接住，不影响列表。
     */
    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: DEFAULT_ICON_SIZE
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: DEFAULT_ICON_SIZE
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    private companion object {
        const val PLUGIN_REGISTRY_KEY = PluginRegistry.REGISTRY_KEY

        /** 取不到 intrinsic 尺寸时的兜底图标边长（px）。 */
        const val DEFAULT_ICON_SIZE = 96

        /**
         * 已知插件包名（本仓库自研插件的固定身份）。
         *
         * 真机 Android 16 上 `getInstalledApplications` / `getInstalledPackages` 都只返回
         * 1 个应用，插件包不在批量枚举结果里，只能按包名精确查询。这里的包名必须与各
         * 插件模块 `plugins` 下 build.gradle 的 applicationId、以及 `PluginDownloads`
         * 里的常量保持一致；查询后仍要确认 registry 非空才会进列表，
         * 所以名字写错只会漏掉、不会误报。
         *
         * 注：本注释里不要出现斜杠紧跟星号的写法 —— Kotlin 的块注释支持嵌套，
         * 那种写法会被当成注释开头，把后面的代码整段吃掉（本文件已因此踩过一次）。
         */
        val KNOWN_PLUGIN_PACKAGES = listOf(
            "com.jdkshen.xiangqi.plugin",
            "com.jdkshen.apkbuilder.plugin"
        )
    }
}
