package com.jdkshen.aijspro.ui.project

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Toast
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
import com.jdkshen.aijspro.autojs.build.sign.ApkSignatureReader
import com.jdkshen.aijspro.autojs.build.sign.KeyStoreGenerator
import com.jdkshen.aijspro.autojs.build.sign.SigningKey
import com.jdkshen.aijspro.autojs.build.sign.SigningOptions
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
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.extra.SuperSwitch
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
        private const val PICKER_SPLASH = 2
        private const val PICKER_KEYSTORE = 3
        private val REGEX_PACKAGE_NAME =
            Pattern.compile("^([A-Za-z][A-Za-z\\d_]*\\.)+([A-Za-z][A-Za-z\\d_]*)$")
        private val IMAGE_EXTENSIONS = listOf("png", "jpg", "jpeg", "webp")
        private val KEYSTORE_EXTENSIONS = listOf("jks", "keystore", "p12", "pfx", "bks")
        private const val KEYSTORE_ALIAS = "aijspro"

        /** 签名模式：0 = 内置公共证书；1 = 使用已有密钥库；2 = 新建密钥库 */
        private const val SIGNING_MODE_DEFAULT = SigningOptions.MODE_DEFAULT
        private const val SIGNING_MODE_EXISTING = SigningOptions.MODE_EXISTING
        private const val SIGNING_MODE_NEW = SigningOptions.MODE_NEW
        private const val PREF_SIGNING_MODE = "aijspro.build.signing.mode"
        private const val PREF_SIGNING_KEYSTORE = "aijspro.build.signing.keystore"
        private const val PREF_SIGNING_ALIAS = "aijspro.build.signing.alias"
        private const val PREF_SIGNING_STORE_PASSWORD = "aijspro.build.signing.storePassword"
        private const val PREF_SIGNING_KEY_PASSWORD = "aijspro.build.signing.keyPassword"

        private fun isImageFile(name: String): Boolean =
            IMAGE_EXTENSIONS.contains(name.substringAfterLast('.', "").lowercase())

        private fun isKeyStoreFile(name: String): Boolean =
            KEYSTORE_EXTENSIONS.contains(name.substringAfterLast('.', "").lowercase())
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

    // ---- permission / runtime config state ----
    private var permissions by mutableStateOf(PermissionCatalog.DEFAULT_DECLARED)
    private var requestPermissions by mutableStateOf(PermissionCatalog.DEFAULT_REQUEST)
    /** 0 = 权限声明（写入清单）, 1 = 启动时自动申请 */
    private var permissionTab by mutableStateOf(0)
    private var permissionQuery by mutableStateOf("")
    private val permissionShow = mutableStateOf(false)
    /** 「全选」需二次确认（避免一键声明 208 条含敏感权限）。 */
    private var selectAllArmed by mutableStateOf(false)
    private lateinit var permissionDetails: PermissionDetails
    private var hideLogs by mutableStateOf(false)
    private var showSplash by mutableStateOf(true)
    private var splashText by mutableStateOf("")
    private var splashIconPath by mutableStateOf("")
    private var splashIcon by mutableStateOf<Bitmap?>(null)

    // ---- features (Pro 的“特性”组) ----
    /** "" = 自动（Rhino）, "rhino", "quickjs" */
    private var engine by mutableStateOf("")
    private var includeAccessibility by mutableStateOf(true)
    private var includeImageModule by mutableStateOf(true)

    // ---- signing (Pro 的“签名”组) ----
    /** 0 = 默认签名（tiny-sign 内嵌测试证书）, 1 = 使用已有的密钥库, 2 = 新建密钥 */
    private var signingMode by mutableStateOf(0)
    private var keyStorePath by mutableStateOf("")
    private var keyStoreAlias by mutableStateOf("")
    private var keyStorePassword by mutableStateOf("")
    private var keyPassword by mutableStateOf("")
    /** 只有验证通过的密钥才会真正参与打包，避免用错口令生成无法升级的产物 */
    private var signingKey by mutableStateOf<SigningKey?>(null)
    private var signingSummary by mutableStateOf("")
    private var signingError by mutableStateOf<String?>(null)

    // ---- build state ----
    private var busy by mutableStateOf(false)
    private var stage by mutableStateOf(R.string.apk_builder_prepare)
    private val successShow = mutableStateOf(false)
    private var successPath by mutableStateOf("")
    /** 产物里实际读出来的签名者（不是用户选的那个），用于成功提示。 */
    private var successSigner by mutableStateOf("")
    private val failureShow = mutableStateOf(false)
    private var failureMessage by mutableStateOf("")

    // ---- picker state ----
    private val pickerShow = mutableStateOf(false)
    // Observable: the dialog reads it while composing, so a plain field could keep a
    // stale mode if the popup content is reused between openings.
    private var pickerTarget by mutableStateOf(PICKER_SOURCE)
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
        permissionDetails = PermissionDetails(this)
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
        restoreSigningSettings()
    }

    override fun onDestroy() {
        disposables.clear()
        super.onDestroy()
    }

    /**
     * The page is launched from the explorer with a script/project path. When it is already
     * on top Android delivers the new intent here instead of recreating the activity, so the
     * form has to be re-targeted explicitly.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val newSource = intent.getStringExtra(BuildActivity.EXTRA_SOURCE)
        if (!newSource.isNullOrEmpty() && newSource != source) {
            applySource(File(newSource), fillDefaults = true)
        }
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
            // 项目模式下运行配置来自项目内 project.json，打包时会原样写回，
            // 所以打开页面时必须先回读，否则会把项目已有设置覆盖成默认值。
            val launchConfig = config.launchConfig
            hideLogs = launchConfig.shouldHideLogs()
            showSplash = launchConfig.shouldShowSplash()
            splashText = launchConfig.splashText ?: ""
            val projectRequest = launchConfig.requestPermissions
            if (!projectRequest.isNullOrEmpty()) {
                requestPermissions = projectRequest
            }
            engine = config.engine ?: ""
            val projectSplash = File(file, "splash.png")
            if (projectSplash.isFile()) {
                splashIconPath = projectSplash.path
                splashIcon = BitmapFactory.decodeFile(projectSplash.path)
            }
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
        Log.d(TAG, "openPicker target=$target")
        pickerTarget = target
        val startDir = when (target) {
            PICKER_OUTPUT -> output.ifEmpty { Pref.getScriptDirPath() }
            PICKER_SPLASH -> splashIconPath.takeIf { it.isNotEmpty() }?.let { File(it).parent }
                ?: Pref.getScriptDirPath()
            PICKER_KEYSTORE -> keyStorePath.takeIf { it.isNotEmpty() }?.let { File(it).parent }
                ?: Environment.getExternalStorageDirectory().path
            else -> source.takeIf { it.isNotEmpty() }?.let { File(it).parent }
                ?: Pref.getScriptDirPath()
        }
        val start = File(startDir)
        pickerDir = if (start.isDirectory) start
        else start.parentFile ?: Environment.getExternalStorageDirectory()
        pickerShow.value = true
    }

    private fun applyPicked(file: File) {
        Log.d(TAG, "applyPicked target=$pickerTarget file=$file")
        when (pickerTarget) {
            PICKER_SOURCE -> applySource(file, fillDefaults = appName.isBlank())
            PICKER_OUTPUT -> output = file.path
            PICKER_SPLASH -> {
                val bitmap = BitmapFactory.decodeFile(file.path)
                if (bitmap == null) {
                    // 扩展名是图片但内容不是（例如被改名的文本文件）：保持原选择并提示。
                    splashIconPath = ""
                    splashIcon = null
                    Toast.makeText(this, R.string.text_invalid_image, Toast.LENGTH_SHORT).show()
                } else {
                    splashIconPath = file.path
                    splashIcon = bitmap
                }
            }
            PICKER_KEYSTORE -> {
                keyStorePath = file.path
                // 选了密钥库文件就是要用它；停留在“默认签名”会让用户以为换了证书其实没换。
                if (signingMode == SIGNING_MODE_DEFAULT) signingMode = SIGNING_MODE_EXISTING
                // 换了密钥库就必须重新验证，否则会拿旧密钥的校验结果去打包。
                signingKey = null
                signingSummary = ""
                signingError = null
                persistSigningSettings()
            }
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
        if (signingMode != SIGNING_MODE_DEFAULT && signingKey == null) {
            errorText = getString(R.string.error_signing_key_required)
            return false
        }
        return true
    }

    private fun createAppConfig(): ApkBuilder.AppConfig? {
        val config = projectConfig
        val appConfig = if (config != null) {
            ApkBuilder.AppConfig.fromProjectConfig(source, config)
        } else {
            val code = versionCode.trim().toIntOrNull() ?: return null
            val created = ApkBuilder.AppConfig()
                .setAppName(appName.trim())
                .setSourcePath(source)
                .setPackageName(appPackageName.trim())
                .setVersionName(versionName.trim())
                .setVersionCode(code)
            val bitmap = icon
            if (bitmap != null) {
                created.setIcon(Callable { bitmap })
            }
            created
        }
        applyLaunchConfig(appConfig)
        return appConfig
    }

    /**
     * Permissions are sent to the packager as a diff against the template manifest: the
     * template set stays untouched when the page is used with its defaults.
     */
    private fun applyLaunchConfig(appConfig: ApkBuilder.AppConfig) {
        val selected = permissions
        appConfig.setPermissionsToAdd(
            selected.filter { !PermissionCatalog.TEMPLATE_DEFAULTS.contains(it) })
        appConfig.setPermissionsToRemove(
            PermissionCatalog.TEMPLATE_DEFAULTS.filter { !selected.contains(it) })
        appConfig.setRequestPermissions(requestPermissions.toList())
        appConfig.setHideLogs(hideLogs)
        appConfig.setShowSplash(showSplash)
        appConfig.setSplashText(splashText.trim())
        appConfig.setEngine(engine.ifEmpty { null })
        appConfig.setIncludeAccessibility(includeAccessibility)
        appConfig.setIncludeImageModule(includeImageModule)
        if (showSplash && splashIconPath.isNotEmpty()) {
            appConfig.setSplashIcon(splashIconPath)
        }
        // 没选自定义签名（或还没验证通过）时置空，保持 tiny-sign 的默认行为。
        // 判定放在 SigningOptions 里：只认「使用已有密钥库」会让新建签名静默失效。
        appConfig.setSigner(SigningOptions.signerFor(signingMode, signingKey, keyStoreAlias))
    }

    /**
     * 生成本机独有的签名身份（2048 位 RSA 自签名证书）并存成 PKCS#12，
     * 用户不需要自备密钥库也能避开 tiny-sign 那份全世界共用的测试证书。
     */
    private fun generateSigningKey() {
        val directory = output.ifEmpty { Pref.getScriptDirPath() }
        val baseName = ((if (projectMode) projectConfig?.name else null) ?: appName)
            .orEmpty().ifBlank { "app" }.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val target = File(directory, "$baseName-signing.p12")
        if (target.exists()) {
            signingKey = null
            signingSummary = ""
            signingError = getString(R.string.format_signing_key_exists, target.path)
            return
        }
        signingError = null
        signingSummary = ""
        busy = true
        val commonName = ((if (projectMode) projectConfig?.name else null) ?: appName)
            .orEmpty().ifBlank { baseName }
        disposables.add(
            Observable.fromCallable {
                val password = KeyStoreGenerator.randomPassword()
                val generated = KeyStoreGenerator.generate(commonName, "AI.js Pro", "CN")
                KeyStoreGenerator.save(generated, target, password.toCharArray(), KEYSTORE_ALIAS)
                Triple(generated, password, target)
            }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ result ->
                    busy = false
                    keyStorePath = result.third.path
                    keyStoreAlias = KEYSTORE_ALIAS
                    keyStorePassword = result.second
                    keyPassword = result.second
                    signingKey = result.first
                    signingSummary = getString(R.string.format_signing_generated, result.third.path)
                    persistSigningSettings()
                    Log.d(TAG, "Generated signing key " + result.third.path)
                }, { error ->
                    busy = false
                    signingKey = null
                    signingError = error.message ?: error.toString()
                    Log.e(TAG, "Failed to generate signing key", error)
                })
        )
    }

    /**
     * Reads the keystore on a worker thread: JKS/PKCS12 parsing and the private key check are
     * slow enough to jank the dialog, and a wrong password has to surface as a message.
     */
    private fun verifySigningKey() {
        val path = keyStorePath
        if (path.isBlank()) {
            signingKey = null
            signingSummary = ""
            signingError = getString(R.string.error_signing_key_required)
            return
        }
        signingError = null
        signingSummary = ""
        busy = true
        val storePassword = keyStorePassword.toCharArray()
        // 与 keytool 一致：密钥口令留空时沿用密钥库口令。
        val entryPassword = keyPassword.ifEmpty { keyStorePassword }.toCharArray()
        val alias = keyStoreAlias.trim()
        disposables.add(
            Observable.fromCallable {
                SigningKey.load(File(path), null, storePassword, alias, entryPassword)
            }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ key ->
                    busy = false
                    signingKey = key
                    signingSummary = getString(R.string.format_signing_verified, key.subjectName)
                    persistSigningSettings()
                    Log.d(TAG, "Signing key verified: " + key.certificateFingerprint)
                }, { error ->
                    busy = false
                    signingKey = null
                    signingError = error.message ?: error.toString()
                    Log.e(TAG, "Failed to load signing key", error)
                })
        )
    }

    /**
     * 签名设置要跨页面、跨脚本保留：否则每进一次打包页就默默退回内置证书，
     * 用户以为换了身份其实没换（报毒结果自然不会变）。
     * 口令存在应用私有 SharedPreferences（同 AutoX.js 的做法），密钥库本身仍在外部存储。
     */
    private fun persistSigningSettings() {
        Pref.setPrefInt(PREF_SIGNING_MODE, signingMode)
        Pref.setPrefString(PREF_SIGNING_KEYSTORE, keyStorePath)
        Pref.setPrefString(PREF_SIGNING_ALIAS, keyStoreAlias)
        Pref.setPrefString(PREF_SIGNING_STORE_PASSWORD, keyStorePassword)
        Pref.setPrefString(PREF_SIGNING_KEY_PASSWORD, keyPassword)
    }

    private fun restoreSigningSettings() {
        val path = Pref.getPrefString(PREF_SIGNING_KEYSTORE, "")
        val mode = Pref.getPrefInt(PREF_SIGNING_MODE, SIGNING_MODE_DEFAULT)
        if (mode == SIGNING_MODE_DEFAULT || path.isBlank() || !File(path).isFile) return
        // 密钥库已经存在，就按「选择签名」恢复：新建只在第一次需要。
        signingMode = SIGNING_MODE_EXISTING
        keyStorePath = path
        keyStoreAlias = Pref.getPrefString(PREF_SIGNING_ALIAS, "")
        keyStorePassword = Pref.getPrefString(PREF_SIGNING_STORE_PASSWORD, "")
        keyPassword = Pref.getPrefString(PREF_SIGNING_KEY_PASSWORD, "")
        if (keyStorePassword.isNotEmpty()) {
            verifySigningKey()
        }
    }

    private fun togglePermission(name: String) {
        if (permissionTab == 0) {
            permissions = if (permissions.contains(name)) permissions - name else permissions + name
        } else {
            requestPermissions = if (requestPermissions.contains(name)) {
                requestPermissions - name
            } else {
                requestPermissions + name
            }
        }
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
            Observable.fromCallable {
                buildSync(tmpDir, outApk, appConfig)
                // 以产物为准自证签名身份：设置对了但没生效的情况必须一眼能看出来。
                ApkSignatureReader.read(outApk)
            }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ signer ->
                    busy = false
                    successPath = outApk.path
                    successSigner = signer?.let { it.subject + " · " + it.shortFingerprint }.orEmpty()
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
                    LaunchConfigCard()
                    FeaturesCard()
                    SigningCard()
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
        PermissionDialog()
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
                PermissionRow()
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
                PermissionRow()
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
        val splashMode = pickerTarget == PICKER_SPLASH
        val keyStoreMode = pickerTarget == PICKER_KEYSTORE
        SuperDialog(
            show = pickerShow,
            title = getString(
                when (pickerTarget) {
                    PICKER_OUTPUT -> R.string.text_output_apk_path
                    PICKER_SPLASH -> R.string.text_splash_icon
                    PICKER_KEYSTORE -> R.string.text_key_store_file
                    else -> R.string.text_source_file_path
                }),
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
                    if (!splashMode && !keyStoreMode) {
                        item {
                            PickerRow("使用此文件夹：${pickerDir.name}", highlight = true) {
                                applyPicked(pickerDir)
                            }
                        }
                    }
                    items(children) { child ->
                        when {
                            child.isDirectory -> PickerRow("${child.name}/") { pickerDir = child }
                            keyStoreMode -> if (isKeyStoreFile(child.name)) {
                                PickerRow(child.name) { applyPicked(child) }
                            }
                            splashMode -> if (isImageFile(child.name)) {
                                PickerRow(child.name) { applyPicked(child) }
                            }
                            !outputMode -> PickerRow(child.name) { applyPicked(child) }
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
                if (successSigner.isNotEmpty()) {
                    Text(
                        getString(R.string.format_build_signer, successSigner),
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
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

    @Composable
    private fun PermissionRow() {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                getString(R.string.text_permissions),
                fontSize = 16.sp,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                getString(R.string.format_permission_summary,
                    permissions.size, requestPermissions.size),
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onBackgroundVariant
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                text = getString(R.string.text_config),
                onClick = {
                    permissionQuery = ""
                    permissionTab = 0
                    selectAllArmed = false
                    permissionShow.value = true
                },
                colors = ButtonDefaults.textButtonColorsPrimary()
            )
        }
    }

    @Composable
    private fun LaunchConfigCard() {
        SmallTitle(getString(R.string.text_launch_config))
        Card(Modifier.fillMaxWidth()) {
            SuperSwitch(
                title = getString(R.string.text_hide_logs),
                summary = getString(R.string.summary_hide_logs),
                checked = hideLogs,
                onCheckedChange = { hideLogs = it }
            )
            SuperSwitch(
                title = getString(R.string.text_show_splash),
                summary = getString(R.string.summary_show_splash),
                checked = showSplash,
                onCheckedChange = { showSplash = it }
            )
            if (showSplash) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TextField(
                        value = splashText,
                        onValueChange = { splashText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = getString(R.string.text_splash_text),
                        singleLine = true
                    )
                    Text(
                        getString(R.string.summary_splash_text),
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onBackgroundVariant
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SplashIconTile()
                        Spacer(Modifier.width(12.dp))
                        Text(
                            getString(R.string.text_splash_icon),
                            fontSize = 16.sp,
                            color = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            text = getString(R.string.text_select),
                            onClick = { openPicker(PICKER_SPLASH) },
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun SplashIconTile() {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(14.dp))
                .background(MiuixTheme.colorScheme.primary)
                .clickable { openPicker(PICKER_SPLASH) },
            contentAlignment = Alignment.Center
        ) {
            val bitmap = splashIcon
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

    /** Pro 的「特性」组：引擎 + 按需裁剪（无障碍 / 图色），直接决定产物内容与体积。 */
    @Composable
    private fun FeaturesCard() {
        SmallTitle(getString(R.string.text_features))
        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    getString(R.string.text_script_engine),
                    fontSize = 16.sp,
                    color = MiuixTheme.colorScheme.onSurface
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EngineChip(getString(R.string.text_engine_auto), engine.isEmpty()) { engine = "" }
                    EngineChip("Rhino", engine == "rhino") { engine = "rhino" }
                    EngineChip("QuickJS", engine == "quickjs") { engine = "quickjs" }
                }
                Text(
                    getString(R.string.summary_script_engine),
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onBackgroundVariant
                )
            }
            SuperSwitch(
                title = getString(R.string.text_accessibility_service),
                summary = getString(R.string.summary_include_accessibility),
                checked = includeAccessibility,
                onCheckedChange = { includeAccessibility = it }
            )
            SuperSwitch(
                title = getString(R.string.text_image_module),
                summary = getString(R.string.summary_include_image_module),
                checked = includeImageModule,
                onCheckedChange = { includeImageModule = it }
            )
        }
    }

    @Composable
    private fun EngineChip(label: String, selected: Boolean, onClick: () -> Unit) {
        Box(
            Modifier.clip(RoundedCornerShape(8.dp))
                .background(
                    if (selected) MiuixTheme.colorScheme.primary
                    else MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Text(
                label,
                fontSize = 13.sp,
                color = if (selected) Color.White else MiuixTheme.colorScheme.onSurface
            )
        }
    }

    /**
     * 签名：默认沿用 tiny-sign 内嵌的公共测试证书（所有打包应用共用同一身份），
     * 选择签名后产物换成开发者自己的密钥身份，可与自己的其他版本互相覆盖安装。
     */
    @Composable
    private fun SigningCard() {
        SmallTitle(getString(R.string.text_signing))
        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EngineChip(getString(R.string.text_default_signing), signingMode == SIGNING_MODE_DEFAULT) {
                        signingMode = SIGNING_MODE_DEFAULT
                        signingError = null
                        persistSigningSettings()
                    }
                    EngineChip(getString(R.string.text_custom_signing), signingMode == SIGNING_MODE_EXISTING) {
                        signingMode = SIGNING_MODE_EXISTING
                        persistSigningSettings()
                    }
                    EngineChip(getString(R.string.text_new_signing), signingMode == SIGNING_MODE_NEW) {
                        signingMode = SIGNING_MODE_NEW
                        persistSigningSettings()
                    }
                }
                Text(
                    getString(
                        when (signingMode) {
                            SIGNING_MODE_DEFAULT -> R.string.summary_signing_default
                            SIGNING_MODE_NEW -> R.string.summary_signing_generate
                            else -> R.string.summary_signing_custom
                        }),
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onBackgroundVariant
                )
                // 把“即将用哪个身份”明写出来：只勾了选项但没生效过的情况太多了。
                val signer = signingKey
                Text(
                    getString(
                        R.string.format_signing_current,
                        when {
                            signingMode == SIGNING_MODE_DEFAULT ->
                                getString(R.string.text_signing_builtin_identity)

                            signer != null -> signer.subjectName
                            else -> getString(R.string.text_signing_unverified)
                        }),
                    fontSize = 12.sp,
                    color = if (signingMode != SIGNING_MODE_DEFAULT && signer != null) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        Color(0xFFD32F2F)
                    }
                )
            }
            if (signingMode != SIGNING_MODE_DEFAULT) {
                SuperArrow(
                    title = getString(R.string.text_key_store_file),
                    rightText = if (keyStorePath.isEmpty()) getString(R.string.text_select)
                    else File(keyStorePath).name,
                    onClick = { openPicker(PICKER_KEYSTORE) })
                if (signingMode == SIGNING_MODE_NEW) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            text = getString(R.string.text_generate_signing_key),
                            onClick = { generateSigningKey() },
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                    }
                }
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextField(
                        value = keyStoreAlias,
                        onValueChange = {
                            keyStoreAlias = it
                            signingKey = null
                            signingSummary = ""
                            persistSigningSettings()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = getString(R.string.text_key_store_alias),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
                    )
                    TextField(
                        value = keyStorePassword,
                        onValueChange = {
                            keyStorePassword = it
                            signingKey = null
                            signingSummary = ""
                            persistSigningSettings()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = getString(R.string.text_key_store_password),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                    TextField(
                        value = keyPassword,
                        onValueChange = {
                            keyPassword = it
                            signingKey = null
                            signingSummary = ""
                            persistSigningSettings()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = getString(R.string.text_key_password),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            text = getString(R.string.text_signing_verify),
                            onClick = { verifySigningKey() },
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                    }
                    if (signingSummary.isNotEmpty()) {
                        Text(
                            signingSummary,
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.primary
                        )
                        signingKey?.let {
                            Text(
                                getString(
                                    R.string.format_signing_fingerprint,
                                    it.certificateFingerprint),
                                fontSize = 10.sp,
                                color = MiuixTheme.colorScheme.onBackgroundVariant
                            )
                        }
                    }
                    signingError?.let {
                        Text(it, fontSize = 12.sp, color = Color(0xFFD32F2F))
                    }
                    Text(
                        getString(R.string.summary_signing_switch_hint),
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onBackgroundVariant
                    )
                }
            }
        }
    }

    @Composable
    private fun PermissionDialog() {
        val declaring = permissionTab == 0
        val selected = if (declaring) permissions else requestPermissions
        SuperDialog(
            show = permissionShow,
            title = getString(R.string.text_permissions),
            onDismissRequest = {
                if (permissionShow.value) MiuixPopupUtil.dismissDialog(permissionShow)
            }
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                PermissionTabRow()
                TextField(
                    value = permissionQuery,
                    onValueChange = { permissionQuery = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = getString(R.string.text_permission_search),
                    singleLine = true
                )
                Text(
                    getString(
                        if (declaring) R.string.summary_permission_template_hint
                        else R.string.summary_permission_request_hint),
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                // 选到（模板之外的）危险权限时提醒：安全软件（含 MIUI 安装器）会对这类权限画像报风险。
                // 模板自带的 5 条危险权限是产物必需，不在此提醒，否则一打开就跑红字。
                val dangerousCount = if (declaring) {
                    selected.count {
                        !PermissionCatalog.TEMPLATE_DEFAULTS.contains(it) &&
                            permissionDetails.of(it)?.level == PermissionDetails.Level.DANGEROUS
                    }
                } else {
                    0
                }
                if (dangerousCount > 0) {
                    Text(
                        getString(R.string.format_dangerous_permission_warning, dangerousCount),
                        fontSize = 12.sp,
                        color = Color(0xFFD32F2F),
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                    )
                }
                // 分成两行：四个按钮挤一行会被裁掉（真机实测「清空」出屏）。
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (declaring) {
                        TextButton(
                            text = getString(R.string.text_restore_default),
                            onClick = {
                                permissions = PermissionCatalog.DEFAULT_DECLARED
                                selectAllArmed = false
                            },
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // “全选”会把目录里 208 条全部声明（含短信/联系人/通话等敏感权限），
                    // 产物很容易被安全软件标记为风险，所以要点两次才生效。
                    TextButton(
                        text = getString(
                            if (selectAllArmed && declaring) R.string.text_select_all_confirm
                            else R.string.text_select_all),
                        onClick = {
                            val all = PermissionCatalog.ALL.map { it.name }
                            if (declaring) {
                                if (selectAllArmed) {
                                    permissions = all
                                    selectAllArmed = false
                                } else {
                                    selectAllArmed = true
                                }
                            } else {
                                requestPermissions = all
                            }
                        },
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                    TextButton(
                        text = getString(R.string.text_clear_selection),
                        onClick = {
                            if (declaring) permissions = emptyList() else requestPermissions = emptyList()
                            selectAllArmed = false
                        },
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                    val groups = PermissionCatalog.GROUPS
                        .map { group -> group to group.entries.filter { matchesQuery(it) } }
                        .filter { it.second.isNotEmpty() }
                    groups.forEach { (group, entries) ->
                        item(key = "header-${group.title}") {
                            Text(
                                group.title,
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onBackgroundVariant,
                                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
                            )
                        }
                        items(entries, key = { it.name }) { entry ->
                            PermissionItem(entry, selected.contains(entry.name))
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = { MiuixPopupUtil.dismissDialog(permissionShow) },
                        colors = ButtonDefaults.buttonColorsPrimary()
                    ) {
                        Text(getString(R.string.text_done))
                    }
                }
            }
        }
    }

    /** 两个维度：写入清单（声明）与启动时向用户申请，等价于 Pro 的两个 Tab。 */
    @Composable
    private fun PermissionTabRow() {
        Row(Modifier.fillMaxWidth()) {
            PermissionTab(
                getString(R.string.text_permission_tab_declare),
                permissionTab == 0, Modifier.weight(1f)) { permissionTab = 0 }
            PermissionTab(
                getString(R.string.text_permission_tab_request),
                permissionTab == 1, Modifier.weight(1f)) { permissionTab = 1 }
        }
    }

    @Composable
    private fun PermissionTab(title: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
        Column(
            modifier.clickable(onClick = onClick),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                title,
                fontSize = 14.sp,
                color = if (active) MiuixTheme.colorScheme.primary
                else MiuixTheme.colorScheme.onBackgroundVariant,
                modifier = Modifier.padding(top = 10.dp, bottom = 8.dp)
            )
            Box(
                Modifier.fillMaxWidth().height(2.dp).background(
                    if (active) MiuixTheme.colorScheme.primary else Color.Transparent)
            )
        }
    }

    private fun matchesQuery(entry: PermissionCatalog.Entry): Boolean {
        val query = permissionQuery.trim()
        if (query.isEmpty()) return true
        return entry.label.contains(query, ignoreCase = true) ||
            entry.name.contains(query, ignoreCase = true) ||
            entry.summary.contains(query, ignoreCase = true)
    }

    @Composable
    private fun PermissionItem(entry: PermissionCatalog.Entry, checked: Boolean) {
        val info = permissionDetails.of(entry.name)
        Row(
            Modifier.fillMaxWidth()
                .clickable { togglePermission(entry.name) }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.label,
                        fontSize = 15.sp,
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    info?.let { PermissionLevelBadge(it.level) }
                }
                // 系统官方（已本地化）描述优先，拿不到时回退到目录里的自写说明。
                val description = info?.description?.takeIf { it.isNotBlank() } ?: entry.summary
                Text(
                    description,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(12.dp))
            Checkbox(checked = checked, onCheckedChange = { togglePermission(entry.name) })
        }
    }

    @Composable
    private fun PermissionLevelBadge(level: PermissionDetails.Level) {
        val (textRes, color) = when (level) {
            PermissionDetails.Level.DANGEROUS ->
                R.string.text_permission_level_dangerous to Color(0xFFD32F2F)
            PermissionDetails.Level.PRIVILEGED ->
                R.string.text_permission_level_privileged to Color(0xFF1976D2)
            PermissionDetails.Level.SIGNATURE ->
                R.string.text_permission_level_signature to Color(0xFF7B1FA2)
            else -> return
        }
        Text(
            getString(textRes),
            fontSize = 10.sp,
            color = color,
            modifier = Modifier
                .padding(start = 6.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(color.copy(alpha = 0.12f))
                .padding(horizontal = 5.dp, vertical = 1.dp)
        )
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
