package com.jdkshen.aijspro.ui.resource

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.jdkshen.aijspro.Pref
import com.jdkshen.aijspro.R
import com.jdkshen.aijspro.model.sample.SampleFile
import com.jdkshen.aijspro.model.script.ScriptFile
import com.jdkshen.aijspro.model.script.Scripts
import com.jdkshen.aijspro.theme.AijsMiuixTheme
import com.jdkshen.aijspro.ui.editor.ProCodeEditorActivity
import com.jdkshen.aijspro.ui.main.MainPageSearchHandler
import com.jdkshen.aijspro.ui.main.ViewPagerFragment
import com.jdkshen.aijspro.ui.project.BuildActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import org.json.JSONObject

/**
 * Auto.js Pro-like "资源" page (third tab). Lists bundled **projects** (any sample directory
 * containing `project.json`) plus the local resource directory ("我的资源" under the script
 * root); flat `.js` samples are intentionally not listed here — they live in the 示例 page.
 * Reuses the same import/run/build flows as the ImGui workspace resource panel.
 */
class MiuixResourceFragment : ViewPagerFragment(-1), MainPageSearchHandler {

    private enum class Source { BUILTIN, LOCAL }

    private data class ResourceEntry(
        val name: String,
        val kind: Source,
        val category: String,
        val description: String,
        val metadata: String,
        val assetPath: String? = null,
        val file: File? = null,
        val imported: Boolean = false,
        /** 目录里带 `project.json` 的工程：整份一条，导入/打包都按目录走。 */
        val project: Boolean = false
    )

    private lateinit var rootView: ComposeView
    private var query by mutableStateOf(TextFieldValue(""))
    private var category by mutableStateOf("")
    private var searchMode by mutableStateOf(false)
    private var mineOnly by mutableStateOf(false)
    private var showCategoryDialog by mutableStateOf(false)
    private var entries by mutableStateOf<List<ResourceEntry>?>(null)
    private var loadError by mutableStateOf<String?>(null)
    private var reloadTick by mutableStateOf(0)
    private var selected by mutableStateOf<ResourceEntry?>(null)
    private var busy by mutableStateOf(false)

    private companion object {
        /** 目录里带这个文件就当成「工程」整条展示，里面的模块/模型不逐个铺开。 */
        private const val PROJECT_MARKER = "project.json"

        private const val PROJECT_LIMIT = 60
    }

    private val uploadLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            uploadResourceUri(uri)
        }
    }

    override fun openPageSearch() {
        searchMode = true
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        rootView = ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AijsMiuixTheme {
                    LaunchedEffect(reloadTick) {
                        loadError = null
                        try {
                            entries = withContext(Dispatchers.IO) { loadEntries() }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            loadError = error.localizedMessage ?: "无法读取资源"
                        }
                    }
                    val all = entries.orEmpty()
                    val visible = remember(all, query.text, category, mineOnly) {
                        all.filter { entry ->
                            (!mineOnly || entry.kind == Source.LOCAL) &&
                                    (category.isEmpty() || entry.category == category) &&
                                    (query.text.isBlank() ||
                                            entry.name.contains(query.text, ignoreCase = true) ||
                                            entry.category.contains(query.text, ignoreCase = true) ||
                                            entry.description.contains(query.text, ignoreCase = true))
                        }
                    }
                    Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
                        if (searchMode) SearchStrip(visible.size)
                        LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentPadding = PaddingValues(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            when {
                                loadError != null -> item {
                                    MessagePanel("读取失败：$loadError", "重试") { reloadTick++ }
                                }
                                all.isEmpty() -> item { MessagePanel("正在读取资源…") }
                                visible.isEmpty() -> item {
                                    MessagePanel(if (mineOnly) "还没有上传的资源"
                                        else "没有匹配的资源，请修改关键词或分类。")
                                }
                            }
                            items(visible, key = { "${it.kind}:${it.assetPath ?: it.file?.path}" }) { entry ->
                                ResourceCard(entry, open = { selected = it }, quickAction = {
                                    if (it.kind == Source.BUILTIN && !it.imported && !busy) {
                                        importBuiltin(it) { reloadTick++ }
                                    } else selected = it
                                })
                            }
                        }
                        ResourceBottomBar()
                    }

                    if (showCategoryDialog) {
                        CategoryDialog(all, dismiss = { showCategoryDialog = false })
                    }
                    selected?.let { entry ->
                        ResourceDetailDialog(entry, dismiss = { selected = null },
                            refresh = { reloadTick++ })
                    }
                }
            }
        }
        return rootView
    }

    // ---------- data ----------

    private fun loadEntries(): List<ResourceEntry> {
        val list = mutableListOf<ResourceEntry>()
        // 内置部分只列「工程」：一个工程一条（名字/描述取工程自己的 project.json），
        // 不再把散装 .js 铺成条目——散装示例在「示例」页里看。
        val projects = mutableListOf<String>()
        collectProjectAssets("sample", projects, PROJECT_LIMIT)
        projects.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it }).forEach { assetPath ->
            val project = readBundledProject(assetPath)
            list += ResourceEntry(
                name = project?.optString("name")?.trim().orEmpty().ifEmpty { File(assetPath).name },
                kind = Source.BUILTIN,
                category = resourceCategoryFor(assetPath),
                description = project?.optString("description")?.trim().orEmpty()
                    .ifEmpty { readBundledDescription(assetPath) }
                    .ifEmpty { "AI.js Pro 内置示例工程" },
                metadata = "工程|${resourceCategoryFor(assetPath)}|${readableAssetTreeSize(assetPath)}",
                assetPath = assetPath,
                imported = resourceTargetFor(assetPath).isDirectory,
                project = true
            )
        }
        val mine = mutableListOf<File>()
        collectResourceFiles(userResourceDirectory(), mine, 100)
        mine.forEach { file ->
            list += ResourceEntry(
                name = file.name.replaceFirst(Regex("\\.(js|auto)$"), ""),
                kind = Source.LOCAL,
                category = "我的资源",
                description = "本机上传的脚本资源",
                metadata = "本机|我的资源|${readableFileSize(file)}",
                file = file,
                imported = true
            )
        }
        return list
    }

    /** 收集工程：目录里带 project.json 就整条收录并停止下钻。 */
    private fun collectProjectAssets(path: String, result: MutableList<String>, limit: Int) {
        if (result.size >= limit) return
        val children = try {
            requireContext().assets.list(path)
        } catch (_: IOException) {
            null
        }
        if (children.isNullOrEmpty()) return
        if (children.contains(PROJECT_MARKER)) {
            result += path
            return
        }
        children.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it }).forEach { child ->
            collectProjectAssets("$path/$child", result, limit)
            if (result.size >= limit) return
        }
    }

    private fun readBundledProject(assetPath: String): JSONObject? = try {
        requireContext().assets.open("$assetPath/$PROJECT_MARKER").use { input ->
            JSONObject(String(input.readBytes(), Charsets.UTF_8))
        }
    } catch (_: Exception) {
        null
    }

    /**
     * 工程的一句话描述：project.json 里的 description 优先；打包器回写工程配置时会丢掉
     * 未知字段，所以再退回 README.md 的第一行（显示用，不影响工程本身）。
     */
    private fun readBundledDescription(assetPath: String): String = try {
        requireContext().assets.open("$assetPath/README.md").use { input ->
            input.bufferedReader(Charsets.UTF_8).readLines()
                .firstOrNull { it.isNotBlank() }
                ?.trim()?.trimStart('#')?.trim()
                .orEmpty()
        }
    } catch (_: Exception) {
        ""
    }

    /** 工程条目体积：递归累加内置资源里的文件大小。 */
    private fun readableAssetTreeSize(assetPath: String): String {
        var bytes = 0L
        val pending = ArrayDeque<String>()
        pending.add(assetPath)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            val children = try {
                requireContext().assets.list(current)
            } catch (_: IOException) {
                null
            }
            if (children.isNullOrEmpty()) {
                bytes += try {
                    requireContext().assets.open(current).use { it.available().toLong() }
                } catch (_: IOException) {
                    0L
                }
            } else {
                children.forEach { pending.addLast("$current/$it") }
            }
        }
        return readableSize(bytes)
    }

    private fun collectResourceFiles(directory: File, result: MutableList<File>, limit: Int) {
        if (!directory.isDirectory || result.size >= limit) return
        directory.listFiles()?.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })?.forEach { file ->
            if (file.isDirectory) collectResourceFiles(file, result, limit)
            else if (file.name.endsWith(".js", true) || file.name.endsWith(".auto", true)) result += file
            if (result.size >= limit) return
        }
    }

    private fun scriptRoot(): File = File(Pref.getScriptDirPath()).canonicalFile

    private fun resourceTargetFor(assetPath: String): File {
        val relative = if (assetPath.startsWith("sample/")) assetPath.substring("sample/".length)
        else File(assetPath).name
        return File(File(scriptRoot(), "下载资源"), relative)
    }

    private fun resourceCategoryFor(assetPath: String): String {
        val relative = if (assetPath.startsWith("sample/")) assetPath.substring("sample/".length) else assetPath
        val slash = relative.indexOf('/')
        return if (slash > 0) relative.substring(0, slash) else "其他"
    }

    private fun userResourceDirectory(): File {
        val dir = File(scriptRoot(), "我的资源")
        if (!dir.isDirectory) dir.mkdirs()
        return dir
    }

    private fun readableFileSize(file: File): String = readableSize(file.length())

    private fun readableSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1024f / 1024f)
        bytes >= 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024f)
        else -> "$bytes B"
    }

    private fun categories(all: List<ResourceEntry>): List<String> {
        val set = linkedSetOf<String>()
        all.forEach { if (it.kind == Source.BUILTIN) set += it.category }
        set += "我的资源"
        return listOf("全部分类") + set
    }

    // ---------- actions ----------

    private fun pickUpload() {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/javascript", "application/javascript", "text/plain"))
        uploadLauncher.launch(Intent.createChooser(picker, "选择脚本资源"))
    }

    private fun uploadResourceUri(uri: Uri) {
        val name = queryDisplayName(uri) ?: "resource-${System.currentTimeMillis()}.js"
        val safeName = File(name).name
        if (!safeName.endsWith(".js", true) && !safeName.endsWith(".auto", true)) {
            Toast.makeText(requireContext(), "仅支持 .js 或 .auto 脚本资源", Toast.LENGTH_LONG).show()
            return
        }
        busy = true
        Thread {
            var ok = false
            var message = "已加入我的资源：$safeName"
            try {
                var target = File(userResourceDirectory(), safeName)
                if (target.exists()) {
                    val dot = safeName.lastIndexOf('.')
                    val stem = if (dot > 0) safeName.substring(0, dot) else safeName
                    val ext = if (dot > 0) safeName.substring(dot) else ".js"
                    target = File(userResourceDirectory(), "$stem-${System.currentTimeMillis()}$ext")
                }
                requireContext().contentResolver.openInputStream(uri).use { input ->
                    if (input == null) throw IOException("无法读取所选文件")
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(8192)
                        var length: Int
                        while (input.read(buffer).also { length = it } != -1) output.write(buffer, 0, length)
                    }
                }
                ok = true
                message = "已加入我的资源：${target.name}"
            } catch (error: Exception) {
                message = "资源上传失败：${error.localizedMessage ?: "未知错误"}"
            }
            requireActivity().runOnUiThread {
                busy = false
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                if (ok) reloadTick++
            }
        }.start()
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        requireContext().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    } catch (_: RuntimeException) {
        null
    }

    private fun copyAssetTree(assetPath: String, target: File) {
        val children = requireContext().assets.list(assetPath)
        if (!children.isNullOrEmpty()) {
            if (!target.isDirectory && !target.mkdirs()) throw IOException("无法创建目录 ${target.name}")
            children.forEach { child -> copyAssetTree("$assetPath/$child", File(target, child)) }
            return
        }
        target.parentFile?.let { parent -> if (!parent.isDirectory) parent.mkdirs() }
        requireContext().assets.open(assetPath).use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(32 * 1024)
                var count: Int
                while (input.read(buffer).also { count = it } != -1) output.write(buffer, 0, count)
                output.fd.sync()
            }
        }
    }

    private fun importBuiltin(entry: ResourceEntry, onDone: () -> Unit) {
        val assetPath = entry.assetPath ?: return
        busy = true
        Thread {
            var message: String
            var ok = false
            try {
                val target = resourceTargetFor(assetPath)
                if (!target.exists()) copyAssetTree(assetPath, target)
                message = "资源已保存到：${target.path}"
                ok = true
            } catch (error: Exception) {
                message = "资源导入失败：${error.localizedMessage ?: "未知错误"}"
            }
            requireActivity().runOnUiThread {
                busy = false
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                onDone()
            }
        }.start()
    }

    private fun viewBuiltin(entry: ResourceEntry, innerName: String = "") {
        // 预览源码统一走新版 Pro 编辑器：已导入过就直接打开「下载资源」里的副本，
        // 否则先把内置资源静默落盘再打开（编辑器记录的 assets 来源可“重置”恢复最新版）。
        // 工程条目要落在目录里，innerName 指定看哪一个文件（工程默认 main.js）。
        val context = requireContext()
        val assetPath = entry.assetPath ?: return
        val target = resourceTargetFor(assetPath)
        val openTarget = if (innerName.isEmpty()) target else File(target, innerName)
        val openAsset = if (innerName.isEmpty()) assetPath else "$assetPath/$innerName"
        if (openTarget.isFile) {
            startActivity(ProCodeEditorActivity.sampleIntent(context, openTarget, openAsset))
            return
        }
        busy = true
        Thread {
            var failure: Exception? = null
            try {
                copyAssetTree(assetPath, target)
            } catch (error: Exception) {
                failure = error
            }
            requireActivity().runOnUiThread {
                busy = false
                if (failure == null) {
                    startActivity(ProCodeEditorActivity.sampleIntent(context, openTarget, openAsset))
                } else {
                    Toast.makeText(context,
                        "打开资源失败：${failure.localizedMessage ?: "未知错误"}",
                        Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun runBuiltin(entry: ResourceEntry) {
        val assetPath = entry.assetPath ?: return
        // 工程条目的入口是工程目录里的 main.js。
        val runnable = if (entry.project) "$assetPath/main.js" else assetPath
        val execution = Scripts.run(SampleFile(runnable, requireContext().assets).toSource())
        if (execution != null) Toast.makeText(requireContext(), "已启动：${entry.name}", Toast.LENGTH_SHORT).show()
    }

    /**
     * 打包工程条目：示例本体在 APK 内置资源里，打包器要的是磁盘上的真实文件，
     * 所以先把整棵工程落到「下载资源」（已存在的文件不覆盖），再以该目录为源进打包页
     * （打包页会读目录里的 project.json，按项目模式打包）。
     */
    private fun buildProject(entry: ResourceEntry) {
        val context = requireContext()
        val assetPath = entry.assetPath ?: return
        val target = resourceTargetFor(assetPath)
        busy = true
        Thread {
            var failure: Exception? = null
            try {
                if (!target.isDirectory) copyAssetTree(assetPath, target)
            } catch (error: Exception) {
                failure = error
            }
            requireActivity().runOnUiThread {
                busy = false
                if (failure == null) {
                    startActivity(Intent(context, BuildActivity::class.java)
                        .putExtra(BuildActivity.EXTRA_SOURCE, target.absolutePath))
                } else {
                    Toast.makeText(context,
                        "打包失败：${failure.localizedMessage ?: "未知错误"}",
                        Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun runLocal(entry: ResourceEntry) {
        val file = entry.file ?: return
        val execution = Scripts.run(ScriptFile(file))
        if (execution != null) Toast.makeText(requireContext(), "已启动：${entry.name}", Toast.LENGTH_SHORT).show()
    }

    private fun editLocal(entry: ResourceEntry) {
        val file = entry.file ?: return
        startActivity(ProCodeEditorActivity.intent(requireContext(), file))
    }

    private fun shareLocal(entry: ResourceEntry) {
        val file = entry.file ?: return
        Scripts.send(ScriptFile(file))
    }

    private fun deleteLocal(entry: ResourceEntry, onDone: () -> Unit) {
        val file = entry.file ?: return
        busy = true
        Thread {
            val ok = file.delete()
            requireActivity().runOnUiThread {
                busy = false
                Toast.makeText(requireContext(),
                    if (ok) "已从我的资源删除：${entry.name}" else "资源删除失败",
                    Toast.LENGTH_LONG).show()
                if (ok) onDone()
            }
        }.start()
    }

    override fun onFabClick(fab: FloatingActionButton?) = Unit

    override fun onBackPressed(activity: Activity): Boolean = when {
        selected != null -> { selected = null; true }
        showCategoryDialog -> { showCategoryDialog = false; true }
        searchMode -> { searchMode = false; query = TextFieldValue(""); true }
        query.text.isNotEmpty() || category.isNotEmpty() || mineOnly -> {
            query = TextFieldValue("")
            category = ""
            mineOnly = false
            true
        }
        else -> false
    }

    override fun onPageShow() {
        super.onPageShow()
        reloadTick++
    }

    // ---------- ui ----------

    @Composable
    private fun SearchStrip(resultCount: Int) {
        val focus = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }
        Row(Modifier.fillMaxWidth().height(58.dp)
            .background(MiuixTheme.colorScheme.surface).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TextField(query, { query = it }, Modifier.weight(1f).focusRequester(focus),
                singleLine = true, label = "搜索资源")
            Text("$resultCount 项", fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                modifier = Modifier.padding(start = 8.dp))
            Box(Modifier.size(42.dp).clip(CircleShape).clickable {
                searchMode = false
                query = TextFieldValue("")
            }, contentAlignment = Alignment.Center) {
                Text("×", fontSize = 28.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
            }
        }
    }

    @Composable
    private fun ResourceCard(
        entry: ResourceEntry,
        open: (ResourceEntry) -> Unit,
        quickAction: (ResourceEntry) -> Unit
    ) {
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 82.dp).clickable { open(entry) }
                .padding(start = 12.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                    if (entry.project) {
                        Image(painterResource(R.drawable.ic_project_compass_24dp), "工程",
                            Modifier.size(27.dp),
                            colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.primary))
                    } else {
                        Text("‹›", fontSize = 27.sp, fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.onSurface)
                    }
                }
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(entry.name, fontSize = 18.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Text(entry.description, fontSize = 14.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(3.dp))
                    val metadata = entry.metadata.split('|')
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(metadata.getOrElse(0) { "" }, fontSize = 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MiuixTheme.colorScheme.primary)
                        Text(metadata.getOrElse(1) { entry.category }, fontSize = 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary,
                            modifier = Modifier.weight(1f).padding(start = 18.dp))
                        Text(metadata.getOrElse(2) { "" }, fontSize = 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary)
                    }
                }
                Box(Modifier.size(44.dp).clip(CircleShape).clickable { quickAction(entry) },
                    contentAlignment = Alignment.Center) {
                    if (entry.imported) {
                        Box(Modifier.size(29.dp).clip(CircleShape)
                            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center) {
                            Text("✓", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                                color = MiuixTheme.colorScheme.primary)
                        }
                    } else {
                        Image(painterResource(R.drawable.ic_file_download_black_48dp),
                            "下载 ${entry.name}", Modifier.size(30.dp),
                            colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurface))
                    }
                }
            }
        }
    }

    @Composable
    private fun ResourceBottomBar() {
        Box(Modifier.fillMaxWidth().navigationBarsPadding().heightIn(min = 72.dp)
            .background(MiuixTheme.colorScheme.surface)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).fillMaxWidth().heightIn(min = 72.dp).clickable {
                    mineOnly = false
                    showCategoryDialog = true
                }.padding(start = 26.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (category.isEmpty()) "全部" else category, fontSize = 18.sp,
                        color = if (!mineOnly) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.onSurfaceSecondary)
                    Text("▾", fontSize = 18.sp, modifier = Modifier.padding(start = 10.dp),
                        color = MiuixTheme.colorScheme.onSurfaceSecondary)
                }
                Spacer(Modifier.width(56.dp))
                Row(Modifier.weight(1f).fillMaxWidth().heightIn(min = 72.dp).clickable {
                    mineOnly = true
                    category = ""
                }.padding(end = 24.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End) {
                    Text("◎", fontSize = 25.sp,
                        color = if (mineOnly) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.onSurfaceSecondary)
                    Text("我的", fontSize = 18.sp, modifier = Modifier.padding(start = 8.dp),
                        color = if (mineOnly) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.onSurfaceSecondary)
                }
            }
            Box(Modifier.size(64.dp).align(Alignment.Center).clip(CircleShape)
                .background(MiuixTheme.colorScheme.primary).clickable(enabled = !busy) { pickUpload() },
                contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.ic_file_download_black_48dp), "上传资源",
                    Modifier.size(30.dp).rotate(180f),
                    colorFilter = ColorFilter.tint(Color.White))
            }
        }
    }

    @Composable
    private fun CategoryDialog(all: List<ResourceEntry>, dismiss: () -> Unit) {
        val choices = remember(all) { categories(all) }
        Dialog(onDismissRequest = dismiss) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                    Text("资源分类", fontSize = 22.sp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                    LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)
                        .heightIn(max = 480.dp)) {
                        items(choices) { choice ->
                            val value = if (choice == "全部分类") "" else choice
                            Row(Modifier.fillMaxWidth().clickable {
                                category = value
                                mineOnly = choice == "我的资源"
                                dismiss()
                            }.padding(horizontal = 20.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(24.dp).clip(CircleShape)
                                    .background(if (category == value && !mineOnly)
                                        MiuixTheme.colorScheme.primary
                                    else MiuixTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center) {
                                    if (category == value && !mineOnly) Text("✓", fontSize = 15.sp,
                                        color = Color.White)
                                }
                                Text(choice, fontSize = 17.sp, modifier = Modifier.padding(start = 14.dp))
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { reloadTick++ }, Modifier.weight(1f)) { Text("刷新") }
                        Button(onClick = dismiss, Modifier.weight(1f)) { Text("关闭") }
                    }
                }
            }
        }
    }

    @Composable
    private fun ResourceDetailDialog(entry: ResourceEntry, dismiss: () -> Unit, refresh: () -> Unit) {
        Dialog(onDismissRequest = { if (!busy) dismiss() }, properties = DialogProperties(
            dismissOnBackPress = !busy, dismissOnClickOutside = !busy)) {
            Card(Modifier.fillMaxWidth(0.84f)) {
                Column(Modifier.fillMaxWidth()
                    .background(MiuixTheme.colorScheme.surface)
                    .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // 正文与操作项放入可滚动区，「关闭」留在滚动区外：横屏/大字体下内容再长也只会滚动，按钮不被挤出窗口。
                    Column(Modifier.fillMaxWidth().weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.Top) {
                            Text("{ }", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                            Column(Modifier.padding(start = 16.dp)) {
                                Text(entry.name, fontSize = 24.sp, maxLines = 2,
                                    fontWeight = FontWeight.Medium, overflow = TextOverflow.Ellipsis,
                                    color = MiuixTheme.colorScheme.onSurface)
                                Text(when {
                                    entry.kind == Source.LOCAL -> "本机资源"
                                    entry.project -> "QuickJS 工程"
                                    else -> "JavaScript"
                                },
                                    fontSize = 15.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                            }
                        }
                        Text(entry.description, fontSize = 16.sp,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary)
                        Text(entry.metadata.replace("|", " · "), fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary)
                        when (entry.kind) {
                            Source.BUILTIN -> {
                                if (entry.project) {
                                    ActionChoice("预览 main.js") { dismiss(); viewBuiltin(entry, "main.js") }
                                    ActionChoice("运行") { dismiss(); runBuiltin(entry) }
                                    ActionChoice(if (entry.imported) "打开已导入工程" else "导入到脚本目录") {
                                        if (entry.imported) {
                                            dismiss()
                                            entry.assetPath?.let {
                                                startActivity(ProCodeEditorActivity.intent(requireContext(),
                                                    File(resourceTargetFor(it), "main.js")))
                                            }
                                        } else importBuiltin(entry) { dismiss(); refresh() }
                                    }
                                    ActionChoice("打包（项目）") { dismiss(); buildProject(entry) }
                                } else {
                                    ActionChoice("预览源码") { dismiss(); viewBuiltin(entry) }
                                    ActionChoice("运行") { dismiss(); runBuiltin(entry) }
                                    ActionChoice(if (entry.imported) "打开已导入文件" else "导入到脚本目录") {
                                        if (entry.imported) {
                                            dismiss()
                                            entry.assetPath?.let { startActivity(ProCodeEditorActivity.intent(
                                                requireContext(), resourceTargetFor(it))) }
                                        } else importBuiltin(entry) { dismiss(); refresh() }
                                    }
                                }
                            }
                            Source.LOCAL -> {
                                ActionChoice("编辑") { dismiss(); editLocal(entry) }
                                ActionChoice("运行") { dismiss(); runLocal(entry) }
                                ActionChoice("分享") { dismiss(); shareLocal(entry) }
                                ActionChoice("删除") {
                                    deleteLocal(entry) { dismiss(); refresh() }
                                }
                            }
                        }
                    }
                    ActionChoice("关闭", dismiss)
                }
            }
        }
    }

    @Composable
    private fun ActionChoice(label: String, onClick: () -> Unit) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.14f))
            .clickable(enabled = !busy, onClick = onClick).padding(vertical = 14.dp, horizontal = 16.dp)) {
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
}
