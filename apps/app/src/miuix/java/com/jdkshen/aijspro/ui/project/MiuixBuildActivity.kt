package com.jdkshen.aijspro.ui.project

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Environment
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsControllerCompat
import com.jdkshen.aijspro.Pref
import com.jdkshen.aijspro.R
import com.jdkshen.aijspro.autojs.build.ApkBuilder
import com.jdkshen.aijspro.build.ApkBuilderPluginHelper
import com.jdkshen.aijspro.external.fileprovider.AppFileProvider
import com.jdkshen.aijspro.theme.AijsMiuixTheme
import com.jdkshen.aijspro.theme.MiuixBackButton
import com.jdkshen.aijspro.theme.isAijsDarkTheme
import com.jdkshen.aijspro.ui.shortcut.ShortcutIconSelectActivity
import com.stardust.autojs.project.ProjectConfig
import com.stardust.util.IntentUtil
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.MiuixPopupUtil
import java.io.File
import java.util.concurrent.Callable
import java.util.regex.Pattern

/**
 * Miuix build (packaging) page. Legacy [BuildActivity] redirects here when
 * BuildConfig.MIUIX_PILOT is on, passing BuildActivity.EXTRA_SOURCE through.
 *
 * Shares the exact packaging pipeline (ApkBuilder + bundled template APK) with
 * the legacy page; only the presentation layer differs.
 */
class MiuixBuildActivity : ComponentActivity(), ApkBuilder.ProgressCallback {

    companion object {
        private const val TAG = "MiuixBuildActivity"
        private const val PICKER_SOURCE = 0
        private const val PICKER_OUTPUT = 1
        private val REGEX_PACKAGE_NAME =
            Pattern.compile("^([A-Za-z][A-Za-z\\d_]*\\.)+([A-Za-z][A-Za-z\\d_]*)$")
    }

    // ---- form state ----
    private var source by mutableStateOf("")
    private var output by mutableStateOf("")
    private var appName by mutableStateOf("")
    private var appPackageName by mutableStateOf("")
    private var versionName by mutableStateOf("1.0.0")
    private var versionCode by mutableStateOf("1")
    private var icon by mutableStateOf<Bitmap?>(null)
    private var errorText by mutableStateOf<String?>(null)
    private var projectMode by mutableStateOf(false)
    private var projectConfig: ProjectConfig? = null

    // ---- build state ----
    private var busy by mutableStateOf(false)
    private var stage by mutableStateOf(R.string.apk_builder_prepare)
    private val successShow = mutableStateOf(false)
    private var successPath by mutableStateOf("")
    private val failureShow = mutableStateOf(false)
    private var failureMessage by mutableStateOf("")

    // ---- picker state ----
    private val pickerShow = mutableStateOf(false)
    private var pickerTarget = PICKER_SOURCE
    private var pickerDir by mutableStateOf(File(Environment.getExternalStorageDirectory().path))

    private val disposables = CompositeDisposable()

    private val iconPickLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult
            disposables.add(
                ShortcutIconSelectActivity.getBitmapFromIntent(applicationContext, data)
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe({ bitmap -> icon = bitmap }, { error ->
                        Log.e(TAG, "Failed to load selected icon", error)
                    })
            )
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val dark = isAijsDarkTheme()
                AijsMiuixTheme {
                    val background = MiuixTheme.colorScheme.background
                    SideEffect {
                        window.statusBarColor = background.toArgb()
                        window.navigationBarColor = background.toArgb()
                        WindowInsetsControllerCompat(window, window.decorView).apply {
                            isAppearanceLightStatusBars = !dark
                            isAppearanceLightNavigationBars = !dark
                        }
                    }
                    BuildScreen()
                }
            }
        })
        val initialSource = intent.getStringExtra(BuildActivity.EXTRA_SOURCE)
        if (!initialSource.isNullOrEmpty()) {
            applySource(File(initialSource), fillDefaults = true)
        }
    }

    override fun onDestroy() {
        disposables.clear()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // State helpers (mirror the legacy page's defaults and validation)
    // ------------------------------------------------------------------

    private fun applySource(file: File, fillDefaults: Boolean) {
        source = file.path
        val config = if (file.isDirectory) ProjectConfig.fromProjectDir(file.path) else null
        if (config != null) {
            projectConfig = config
            projectMode = true
            output = File(file, config.buildDir).path
            return
        }
        projectConfig = null
        projectMode = false
        if (!fillDefaults) return
        var dir = file.parent ?: Pref.getScriptDirPath()
        if (dir.startsWith(filesDir.path)) dir = Pref.getScriptDirPath()
        output = dir
        appName = simplifiedName(file)
        appPackageName = getString(R.string.format_default_package_name, System.currentTimeMillis())
    }

    private fun simplifiedName(file: File): String =
        if (file.isDirectory) file.name else file.name.substringBeforeLast('.', file.name)

    private fun openPicker(target: Int) {
        pickerTarget = target
        val startDir = if (target == PICKER_OUTPUT) {
            output.ifEmpty { Pref.getScriptDirPath() }
        } else {
            source.takeIf { it.isNotEmpty() }?.let { File(it).parent }
                ?: Pref.getScriptDirPath()
        }
        val start = File(startDir)
        pickerDir = if (start.isDirectory) start
        else start.parentFile ?: Environment.getExternalStorageDirectory()
        pickerShow.value = true
    }

    private fun applyPicked(file: File) {
        if (pickerTarget == PICKER_SOURCE) {
            applySource(file, fillDefaults = appName.isBlank())
        } else {
            output = file.path
        }
        MiuixPopupUtil.dismissDialog(pickerShow)
    }

    private fun validate(): Boolean {
        if (source.isBlank()) {
            errorText = getString(R.string.text_source_file_path) +
                    getString(R.string.text_should_not_be_empty)
            return false
        }
        if (output.isBlank()) {
            errorText = getString(R.string.text_output_apk_path) +
                    getString(R.string.text_should_not_be_empty)
            return false
        }
        if (!projectMode) {
            if (appName.isBlank()) {
                errorText = getString(R.string.text_app_name) +
                        getString(R.string.text_should_not_be_empty)
                return false
            }
            if (versionName.isBlank()) {
                errorText = getString(R.string.text_version_name) +
                        getString(R.string.text_should_not_be_empty)
                return false
            }
            if (versionCode.trim().toIntOrNull() == null) {
                errorText = getString(R.string.text_version_code) +
                        getString(R.string.text_should_not_be_empty)
                return false
            }
            if (!REGEX_PACKAGE_NAME.matcher(appPackageName.trim()).matches()) {
                errorText = getString(R.string.text_invalid_package_name)
                return false
            }
        }
        return true
    }

    private fun createAppConfig(): ApkBuilder.AppConfig? {
        val config = projectConfig
        if (config != null) {
            return ApkBuilder.AppConfig.fromProjectConfig(source, config)
        }
        val code = versionCode.trim().toIntOrNull() ?: return null
        val appConfig = ApkBuilder.AppConfig()
            .setAppName(appName.trim())
            .setSourcePath(source)
            .setPackageName(appPackageName.trim())
            .setVersionName(versionName.trim())
            .setVersionCode(code)
        val bitmap = icon
        if (bitmap != null) {
            appConfig.setIcon(Callable { bitmap })
        }
        return appConfig
    }

    // ------------------------------------------------------------------
    // Building
    // ------------------------------------------------------------------

    private fun buildApk() {
        errorText = null
        if (!validate()) return
        val appConfig = createAppConfig()
        if (appConfig == null) {
            errorText = getString(R.string.text_version_code) +
                    getString(R.string.text_should_not_be_empty)
            return
        }
        val tmpDir = File(cacheDir, "build/")
        val outApk = File(output, String.format("%s_v%s.apk", appConfig.appName, appConfig.versionName))
        busy = true
        stage = R.string.apk_builder_prepare
        disposables.add(
            Observable.fromCallable { buildSync(tmpDir, outApk, appConfig) }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({
                    busy = false
                    successPath = outApk.path
                    successShow.value = true
                }, { error ->
                    busy = false
                    failureMessage = error.message ?: error.toString()
                    failureShow.value = true
                    Log.e(TAG, "Build failed", error)
                })
        )
    }

    private fun buildSync(tmpDir: File, outApk: File, appConfig: ApkBuilder.AppConfig): ApkBuilder {
        val template = ApkBuilderPluginHelper.openTemplateApk(this)
        return ApkBuilder(template, outApk, tmpDir.path)
            .setProgressCallback(this)
            .prepare()
            .withConfig(appConfig)
            .build()
            .sign()
            .cleanWorkspace()
    }

    private fun updateStage(resId: Int) {
        runOnUiThread { stage = resId }
    }

    override fun onPrepare(builder: ApkBuilder) = updateStage(R.string.apk_builder_prepare)

    override fun onBuild(builder: ApkBuilder) = updateStage(R.string.apk_builder_build)

    override fun onSign(builder: ApkBuilder) = updateStage(R.string.apk_builder_package)

    override fun onClean(builder: ApkBuilder) = updateStage(R.string.apk_builder_clean)

    private fun installApk(path: String) {
        IntentUtil.installApkOrToast(this, path, AppFileProvider.AUTHORITY)
    }

    // ------------------------------------------------------------------
    // UI
    // ------------------------------------------------------------------

    @Composable
    private fun BuildScreen() {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
                SmallTopAppBar(
                    title = getString(R.string.text_build_apk),
                    defaultWindowInsetsPadding = false,
                    navigationIcon = { MiuixBackButton(onClick = { finish() }) })
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (projectMode) {
                        ProjectConfigCard()
                    } else {
                        SourceAndOutputCards()
                        AppConfigCard()
                    }
                    errorText?.let {
                        Text(it, fontSize = 13.sp, color = Color(0xFFD32F2F),
                            modifier = Modifier.padding(start = 4.dp))
                    }
                    Button(
                        onClick = { buildApk() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                        colors = ButtonDefaults.buttonColorsPrimary()
                    ) {
                        Text(getString(R.string.text_build_apk))
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
            if (busy) {
                BuildProgressOverlay()
            }
            // Miuix publishes SuperDialog content into a process-wide holder that only
            // MiuixPopupHost() renders, and the library never mounts the host itself.
            // Keep exactly one host per activity so a dialog cannot be rendered twice.
            MiuixPopupUtil.MiuixPopupHost()
        }
        FilePickerDialog()
        SuccessDialog()
        FailureDialog()
    }

    @Composable
    private fun SourceAndOutputCards() {
        SmallTitle(getString(R.string.text_file))
        Card(Modifier.fillMaxWidth()) {
            SuperArrow(
                title = getString(R.string.text_source_file_path),
                rightText = displayName(source),
                onClick = { openPicker(PICKER_SOURCE) })
            SuperArrow(
                title = getString(R.string.text_output_apk_path),
                rightText = displayName(output),
                onClick = { openPicker(PICKER_OUTPUT) })
        }
    }

    @Composable
    private fun AppConfigCard() {
        SmallTitle(getString(R.string.text_config))
        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextField(
                    value = appName,
                    onValueChange = { appName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = getString(R.string.text_app_name),
                    singleLine = true
                )
                TextField(
                    value = appPackageName,
                    onValueChange = { appPackageName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = getString(R.string.text_package_name),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextField(
                        value = versionName,
                        onValueChange = { versionName = it },
                        modifier = Modifier.weight(1f),
                        label = getString(R.string.text_version_name),
                        singleLine = true
                    )
                    TextField(
                        value = versionCode,
                        onValueChange = { versionCode = it },
                        modifier = Modifier.weight(1f),
                        label = getString(R.string.text_version_code),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile()
                    Spacer(Modifier.width(12.dp))
                    Text(
                        getString(R.string.text_icon),
                        fontSize = 16.sp,
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        text = getString(R.string.text_select),
                        onClick = {
                            iconPickLauncher.launch(Intent(
                                this@MiuixBuildActivity, ShortcutIconSelectActivity::class.java))
                        },
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }
    }

    @Composable
    private fun ProjectConfigCard() {
        val config = projectConfig ?: return
        SmallTitle(getString(R.string.text_config))
        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                InfoRow(getString(R.string.text_app_name), config.name ?: "")
                InfoRow(getString(R.string.text_package_name), config.packageName ?: "")
                InfoRow(
                    getString(R.string.text_version_name),
                    "${config.versionName} (${config.versionCode})")
                Text(
                    "使用项目目录中的 project.json 配置",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onBackgroundVariant
                )
            }
        }
    }

    @Composable
    private fun InfoRow(title: String, value: String) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 15.sp, color = MiuixTheme.colorScheme.onSurface)
            Spacer(Modifier.width(16.dp))
            Text(
                value, fontSize = 15.sp,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }

    @Composable
    private fun IconTile() {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(14.dp))
                .background(MiuixTheme.colorScheme.primary)
                .clickable {
                    iconPickLauncher.launch(Intent(
                        this@MiuixBuildActivity, ShortcutIconSelectActivity::class.java))
                },
            contentAlignment = Alignment.Center
        ) {
            val bitmap = icon
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Image(
                    painter = painterResource(R.drawable.ic_add_white_48dp),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }

    @Composable
    private fun BuildProgressOverlay() {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {})
            ,
            contentAlignment = Alignment.Center
        ) {
            Card(Modifier.fillMaxWidth().padding(horizontal = 56.dp)) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spinner()
                    Spacer(Modifier.height(14.dp))
                    Text(getString(stage), fontSize = 15.sp, color = MiuixTheme.colorScheme.onSurface)
                }
            }
        }
    }

    @Composable
    private fun Spinner() {
        val transition = rememberInfiniteTransition()
        val angle by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing))
        )
        Canvas(Modifier.size(30.dp)) {
            drawArc(
                color = Color(0xFF009688),
                startAngle = angle,
                sweepAngle = 260f,
                useCenter = false,
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            )
        }
    }

    @Composable
    private fun FilePickerDialog() {
        val outputMode = pickerTarget == PICKER_OUTPUT
        SuperDialog(
            show = pickerShow,
            title = getString(
                if (outputMode) R.string.text_output_apk_path else R.string.text_source_file_path),
            // SuperDialog invokes onDismissRequest even while show == false; calling
            // dismissDialog there would clear the process-wide visibility flag and hide
            // an unrelated open dialog, so only do it when this dialog is actually open.
            onDismissRequest = { if (pickerShow.value) MiuixPopupUtil.dismissDialog(pickerShow) }
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(
                    pickerDir.path, fontSize = 12.sp, maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MiuixTheme.colorScheme.onBackgroundVariant
                )
                val children = remember(pickerDir) {
                    pickerDir.listFiles()
                        ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                        ?: emptyList()
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp).padding(top = 8.dp)) {
                    val parent = pickerDir.parentFile
                    if (parent != null) {
                        item { PickerRow(".. ${parent.name}") { pickerDir = parent } }
                    }
                    item {
                        PickerRow("使用此文件夹：${pickerDir.name}", highlight = true) {
                            applyPicked(pickerDir)
                        }
                    }
                    items(children) { child ->
                        if (child.isDirectory) {
                            PickerRow("${child.name}/") { pickerDir = child }
                        } else if (!outputMode) {
                            PickerRow(child.name) { applyPicked(child) }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        text = "取消",
                        onClick = { MiuixPopupUtil.dismissDialog(pickerShow) },
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }
    }

    @Composable
    private fun PickerRow(label: String, highlight: Boolean = false, onClick: () -> Unit) {
        Text(
            label,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (highlight) MiuixTheme.colorScheme.primary
            else MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
                .padding(vertical = 12.dp)
        )
    }

    @Composable
    private fun SuccessDialog() {
        SuperDialog(
            show = successShow,
            title = getString(R.string.text_build_successfully),
            onDismissRequest = { if (successShow.value) MiuixPopupUtil.dismissDialog(successShow) }
        ) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(
                    getString(R.string.format_build_successfully, successPath),
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onBackgroundVariant
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        text = "取消",
                        modifier = Modifier.padding(end = 8.dp),
                        onClick = { MiuixPopupUtil.dismissDialog(successShow) },
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                    Button(
                        onClick = {
                            MiuixPopupUtil.dismissDialog(successShow)
                            installApk(successPath)
                        },
                        colors = ButtonDefaults.buttonColorsPrimary()
                    ) {
                        Text(getString(R.string.text_install))
                    }
                }
            }
        }
    }

    @Composable
    private fun FailureDialog() {
        SuperDialog(
            show = failureShow,
            title = getString(R.string.text_build_failed),
            onDismissRequest = { if (failureShow.value) MiuixPopupUtil.dismissDialog(failureShow) }
        ) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(
                    failureMessage, fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onBackgroundVariant
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = { MiuixPopupUtil.dismissDialog(failureShow) },
                        colors = ButtonDefaults.buttonColorsPrimary()
                    ) {
                        Text("知道了")
                    }
                }
            }
        }
    }

    private fun displayName(path: String): String {
        if (path.isEmpty()) return ""
        val file = File(path)
        val name = file.name
        if (name.isEmpty()) return path
        val parent = file.parentFile?.name
        return if (parent.isNullOrEmpty()) name else "$parent/$name"
    }
}
