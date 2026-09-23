package com.jdkshen.aijspro.ui.shortcut

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsControllerCompat
import com.jdkshen.aijspro.theme.AijsMiuixTheme
import com.jdkshen.aijspro.theme.MiuixBackButton
import com.jdkshen.aijspro.theme.isAijsDarkTheme
import com.jdkshen.aijspro.tool.BitmapTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

object MiuixIconPicker {
    private data class Icon(val name: String, val pkg: String)
    private val cache = object : android.util.LruCache<String, android.graphics.Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap) = value.byteCount
    }
    private fun loadIcon(activity: ShortcutIconSelectActivity, pkg: String): android.graphics.Bitmap {
        val info = activity.packageManager.getPackageInfo(pkg, 0)
        val key = pkg + ":" + info.lastUpdateTime
        cache.get(key)?.let { return it }
        val original = BitmapTool.drawableToBitmap(activity.packageManager.getApplicationIcon(pkg))
        val scale = minOf(1f, 144f / maxOf(original.width, original.height))
        val bitmap = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(original,
            maxOf(1, (original.width * scale).toInt()), maxOf(1, (original.height * scale).toInt()), true) else original
        cache.put(key, bitmap)
        return bitmap
    }

    @JvmStatic fun install(activity: ShortcutIconSelectActivity) {
        activity.setContentView(ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val dark = isAijsDarkTheme()
                AijsMiuixTheme {
                    val background = MiuixTheme.colorScheme.background
                    SideEffect {
                        activity.window.statusBarColor = background.toArgb()
                        activity.window.navigationBarColor = background.toArgb()
                        WindowInsetsControllerCompat(activity.window, activity.window.decorView).apply {
                            isAppearanceLightStatusBars = !dark
                            isAppearanceLightNavigationBars = !dark
                        }
                    }
                    var icons by remember { mutableStateOf<List<Icon>?>(null) }
                    var query by remember { mutableStateOf("") }
                    var error by remember { mutableStateOf<String?>(null) }
                    LaunchedEffect(Unit) {
                        try {
                            icons = withContext(Dispatchers.IO) {
                                val pm = activity.packageManager
                                pm.getInstalledApplications(PackageManager.GET_META_DATA)
                                    .filter { it.icon != 0 }
                                    .mapNotNull { app ->
                                        try { Icon(app.loadLabel(pm).toString(), app.packageName) }
                                        catch (_: Exception) { null }
                                    }.sortedBy { it.name.lowercase() }
                            }
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            error = e.localizedMessage ?: "读取图标失败"
                        }
                    }
                    Column(Modifier.fillMaxSize().background(background)) {
                        SmallTopAppBar(title = "选择图标", navigationIcon = {
                            MiuixBackButton(onClick = { activity.finish() })
                        })
                        Text("从图片选择", color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth().clickable { activity.pickImage() }
                                .padding(horizontal = 20.dp, vertical = 14.dp))
                        TextField(value = query, onValueChange = { query = it }, label = "搜索应用名称或包名",
                            singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                        val filtered = remember(icons, query) { icons?.filter {
                            it.name.contains(query, ignoreCase = true) || it.pkg.contains(query, ignoreCase = true)
                        } ?: emptyList() }
                        if (icons != null && filtered.isEmpty()) Text("没有匹配的应用", modifier = Modifier.padding(20.dp))
                        if (icons == null) {
                            Text(error ?: "正在加载应用图标…", modifier = Modifier.padding(20.dp))
                        } else {
                            LazyVerticalGrid(columns = GridCells.Adaptive(80.dp),
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                contentPadding = PaddingValues(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(filtered, key = { it.pkg }) { icon ->
                                    Column(Modifier.fillMaxWidth().clickable {
                                        activity.setResult(android.app.Activity.RESULT_OK,
                                            Intent().putExtra(ShortcutIconSelectActivity.EXTRA_PACKAGE_NAME, icon.pkg))
                                        activity.finish()
                                    }.padding(vertical = 10.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally) {
                                        var bitmap by remember(icon.pkg) { mutableStateOf<android.graphics.Bitmap?>(null) }
                                        LaunchedEffect(icon.pkg) {
                                            try { bitmap = withContext(Dispatchers.IO) { loadIcon(activity, icon.pkg) } }
                                            catch (e: Exception) {
                                                if (e is kotlinx.coroutines.CancellationException) throw e
                                            }
                                        }
                                        bitmap?.let {
                                            Image(it.asImageBitmap(), contentDescription = icon.name,
                                                modifier = Modifier.size(48.dp))
                                        } ?: Box(Modifier.size(48.dp).background(MiuixTheme.colorScheme.surfaceVariant))
                                        Spacer(Modifier.height(8.dp))
                                        Text(icon.name, fontSize = 12.sp, maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = MiuixTheme.colorScheme.onBackground,
                                            modifier = Modifier.padding(horizontal = 4.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        })
    }
}
