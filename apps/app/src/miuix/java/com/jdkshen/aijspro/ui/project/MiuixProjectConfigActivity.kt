package com.jdkshen.aijspro.ui.project

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsControllerCompat
import com.jdkshen.aijspro.R
import com.jdkshen.aijspro.model.explorer.ExplorerDirPage
import com.jdkshen.aijspro.model.explorer.ExplorerFileItem
import com.jdkshen.aijspro.model.explorer.Explorers
import com.jdkshen.aijspro.model.project.ProjectTemplate
import com.jdkshen.aijspro.theme.AijsMiuixTheme
import com.jdkshen.aijspro.theme.MiuixBackButton
import com.jdkshen.aijspro.theme.isAijsDarkTheme
import com.jdkshen.aijspro.ui.common.MiuixHintDialog
import com.jdkshen.aijspro.ui.shortcut.ShortcutIconSelectActivity
import com.stardust.autojs.project.ProjectConfig
import com.stardust.pio.PFiles
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import java.io.FileOutputStream
import java.util.regex.Pattern

/**
 * Miuix 版「新建 / 编辑项目」页；旧 XML 实现（{@code activity_project_config}）
 * 已随 UI 统一删除，[ProjectConfigActivity] 现在只做转发。
 *
 * 字段、默认值（版本 1.0.0 / 版本号 1 / 主脚本 main.js）、校验文案与保存动作
 * （ProjectTemplate.newProject() / project.json 回写 + Explorer 通知）都与旧页逐条对齐。
 */
class MiuixProjectConfigActivity : ComponentActivity() {

    companion object {
        private val REGEX_PACKAGE_NAME =
            Pattern.compile("^([A-Za-z][A-Za-z\\d_]*\\.)+([A-Za-z][A-Za-z\\d_]*)$")
    }

    private var newProject = false
    private var parentDirectory: File? = null
    private var directory: File? = null
    private lateinit var projectConfig: ProjectConfig
    private var iconBitmap: Bitmap? = null
    private val disposables = CompositeDisposable()

    // ---- 表单状态放 Activity 层：提交（commit）时要读取当前值 ----
    private var appName by mutableStateOf("")
    // 注意：不能叫 packageName——它会生成 getPackageName()，与 Context.getPackageName() 同名冲突。
    private var packageNameInput by mutableStateOf("")
    private var versionName by mutableStateOf("1.0.0")
    private var versionCode by mutableStateOf("1")
    private var mainFileName by mutableStateOf("main.js")
    private var location by mutableStateOf("")
    private var iconPreview by mutableStateOf<Bitmap?>(null)
    private var errorAppName by mutableStateOf<String?>(null)
    private var errorPackageName by mutableStateOf<String?>(null)
    private var errorVersionName by mutableStateOf<String?>(null)
    private var errorVersionCode by mutableStateOf<String?>(null)

    private val iconPicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult
            disposables.add(ShortcutIconSelectActivity.getBitmapFromIntent(applicationContext, data)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ bitmap ->
                    iconBitmap = bitmap
                    iconPreview = bitmap
                }, { error -> error.printStackTrace() }))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        newProject = intent.getBooleanExtra(ProjectConfigActivity.EXTRA_NEW_PROJECT, false)
        if (newProject) {
            val parent = intent.getStringExtra(ProjectConfigActivity.EXTRA_PARENT_DIRECTORY)
            if (parent == null) {
                finish()
                return
            }
            parentDirectory = File(parent)
            projectConfig = ProjectConfig()
            projectConfig.setEngine("quickjs")
            mainFileName = getString(R.string.default_main_file_name)
        } else {
            val dir = intent.getStringExtra(ProjectConfigActivity.EXTRA_DIRECTORY)
            if (dir == null) {
                finish()
                return
            }
            val config = ProjectConfig.fromProjectDir(dir)
            if (config == null) {
                // 旧实现：Material 弹窗提示「无效项目」，关闭后退出。
                // 弹窗挂在空 Activity 上，先铺一层 Miuix 背景，避免系统默认黑底。
                setContentView(ComposeView(this).apply {
                    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                    setContent {
                        AijsMiuixTheme {
                            Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background))
                        }
                    }
                })
                MiuixHintDialog.show(this, getString(R.string.text_invalid_project), "",
                    getString(R.string.ok), null, Runnable { finish() })
                return
            }
            directory = File(dir)
            projectConfig = config
            appName = config.name ?: ""
            packageNameInput = config.packageName ?: ""
            versionName = config.versionName ?: "1.0.0"
            versionCode = config.versionCode.toString()
            mainFileName = config.mainScriptFile ?: getString(R.string.default_main_file_name)
            loadIconPreview()
        }
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
                    Screen()
                }
            }
        })
    }

    override fun onDestroy() {
        disposables.clear()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // 逻辑：与旧 ProjectConfigActivity 的 XML 版逐条对齐
    // ------------------------------------------------------------------

    private fun loadIconPreview() {
        val icon = projectConfig.icon ?: return
        val dir = directory ?: return
        disposables.add(Observable.fromCallable { BitmapFactory.decodeFile(File(dir, icon).path) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ bitmap -> iconPreview = bitmap }, { error -> error.printStackTrace() }))
    }

    /** 校验文案与旧实现一致：`字段名 + 不能为空`、包名非法用 text_invalid_package_name。 */
    private fun validate(): Boolean {
        errorAppName = if (appName.isEmpty()) {
            getString(R.string.text_app_name) + getString(R.string.text_should_not_be_empty)
        } else null
        errorVersionName = if (versionName.isEmpty()) {
            getString(R.string.text_version_name) + getString(R.string.text_should_not_be_empty)
        } else null
        errorVersionCode = if (versionCode.isEmpty() || versionCode.toIntOrNull() == null) {
            getString(R.string.text_version_code) + getString(R.string.text_should_not_be_empty)
        } else null
        errorPackageName = when {
            packageNameInput.isEmpty() ->
                getString(R.string.text_package_name) + getString(R.string.text_should_not_be_empty)
            !REGEX_PACKAGE_NAME.matcher(packageNameInput).matches() ->
                getString(R.string.text_invalid_package_name)
            else -> null
        }
        return errorAppName == null && errorPackageName == null
                && errorVersionName == null && errorVersionCode == null
    }

    private fun commit() {
        if (!validate()) {
            return
        }
        syncProjectConfig()
        val bitmap = iconBitmap
        if (bitmap != null) {
            disposables.add(saveIcon(bitmap)
                .subscribe({ saveProjectConfig() }, { error ->
                    error.printStackTrace()
                    Toast.makeText(this, error.message, Toast.LENGTH_SHORT).show()
                }))
        } else {
            saveProjectConfig()
        }
    }

    private fun syncProjectConfig() {
        projectConfig.setName(appName)
        projectConfig.setVersionCode(versionCode.toIntOrNull() ?: 1)
        projectConfig.setVersionName(versionName)
        projectConfig.setMainScriptFile(mainFileName)
        projectConfig.setPackageName(packageNameInput)
        if (newProject) {
            directory = File(location)
        }
    }

    private fun saveProjectConfig() {
        val dir = directory ?: return
        if (newProject) {
            val parent = parentDirectory ?: return
            disposables.add(ProjectTemplate(projectConfig, dir)
                .newProject()
                .subscribe({
                    Explorers.workspace().notifyChildrenChanged(ExplorerDirPage(parent, null))
                    finish()
                }, { error ->
                    error.printStackTrace()
                    Toast.makeText(this, error.message, Toast.LENGTH_SHORT).show()
                }))
        } else {
            disposables.add(Observable.fromCallable {
                PFiles.write(ProjectConfig.configFileOfDir(dir.path), projectConfig.toJson())
                Void.TYPE
            }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({
                    val item = ExplorerFileItem(dir, null)
                    Explorers.workspace().notifyItemChanged(item, item)
                    finish()
                }, { error ->
                    error.printStackTrace()
                    Toast.makeText(this, error.message, Toast.LENGTH_SHORT).show()
                }))
        }
    }

    private fun saveIcon(bitmap: Bitmap): Observable<String> {
        return Observable.just(bitmap)
            .map { b ->
                var iconPath = projectConfig.icon
                if (iconPath == null) {
                    iconPath = "res/logo.png"
                }
                val dir = directory ?: throw IllegalStateException("项目目录为空")
                val iconFile = File(dir, iconPath)
                PFiles.ensureDir(iconFile.path)
                FileOutputStream(iconFile).use { output ->
                    b.compress(Bitmap.CompressFormat.PNG, 100, output)
                }
                iconPath
            }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .doOnNext { iconPath -> projectConfig.setIcon(iconPath) }
    }

    // ------------------------------------------------------------------
    // UI
    // ------------------------------------------------------------------

    @Composable
    private fun Screen() {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
                SmallTopAppBar(
                    title = if (newProject) getString(R.string.text_new_project)
                    else appName.ifEmpty { getString(R.string.text_project) },
                    defaultWindowInsetsPadding = false,
                    navigationIcon = { MiuixBackButton(onClick = { finish() }) })
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ProjectCard()
                    LaunchCard()
                    Button(
                        onClick = { commit() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColorsPrimary()
                    ) {
                        Text(if (newProject) getString(R.string.text_done) else getString(R.string.text_save))
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    @Composable
    private fun ProjectCard() {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    getString(R.string.text_project),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary
                )
                TextField(
                    value = appName,
                    onValueChange = {
                        appName = it
                        errorAppName = null
                        onAppNameChanged(it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = getString(R.string.text_app_name)
                )
                errorAppName?.let { FieldError(it) }
                TextField(
                    value = packageNameInput,
                    onValueChange = { packageNameInput = it; errorPackageName = null },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = getString(R.string.text_package_name)
                )
                errorPackageName?.let { FieldError(it) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        TextField(
                            value = versionName,
                            onValueChange = { versionName = it; errorVersionName = null },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = getString(R.string.text_version_name)
                        )
                        errorVersionName?.let { FieldError(it) }
                        TextField(
                            value = versionCode,
                            onValueChange = { versionCode = it; errorVersionCode = null },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = getString(R.string.text_version_code)
                        )
                        errorVersionCode?.let { FieldError(it) }
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            getString(R.string.text_icon),
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary
                        )
                        Box(
                            Modifier.size(64.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(MiuixTheme.colorScheme.surfaceVariant)
                                .clickable {
                                    iconPicker.launch(Intent(this@MiuixProjectConfigActivity,
                                        ShortcutIconSelectActivity::class.java))
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            val bitmap = iconPreview
                            if (bitmap != null) {
                                Image(
                                    bitmap.asImageBitmap(),
                                    contentDescription = getString(R.string.text_icon),
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Image(
                                    painterResource(R.drawable.ic_add_white_48dp),
                                    contentDescription = getString(R.string.text_icon),
                                    modifier = Modifier.size(28.dp),
                                    colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.primary)
                                )
                            }
                        }
                    }
                }
                if (newProject) {
                    TextField(
                        value = location,
                        onValueChange = { location = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = getString(R.string.text_project_location)
                    )
                }
            }
        }
    }

    @Composable
    private fun LaunchCard() {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    getString(R.string.text_launch_config),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary
                )
                TextField(
                    value = mainFileName,
                    onValueChange = { mainFileName = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = getString(R.string.text_main_file_name)
                )
            }
        }
    }

    @Composable
    private fun FieldError(text: String) {
        Text(text, fontSize = 12.sp, color = Color(0xFFD32F2F))
    }

    /** 旧实现：应用名称每次变化都会把「项目位置」重置为 `<父目录>/<名称>`。 */
    private fun onAppNameChanged(value: String) {
        if (!newProject) return
        val parent = parentDirectory ?: return
        location = File(parent, value).path
    }
}
