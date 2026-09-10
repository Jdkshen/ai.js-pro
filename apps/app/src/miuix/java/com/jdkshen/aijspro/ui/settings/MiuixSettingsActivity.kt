package com.jdkshen.aijspro.ui.settings

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.preference.PreferenceManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsControllerCompat
import com.jdkshen.aijspro.R
import com.jdkshen.aijspro.storage.file.FileObservable
import com.jdkshen.aijspro.tool.IntentTool
import com.jdkshen.aijspro.theme.AijsMiuixTheme
import com.jdkshen.aijspro.theme.MiuixBackButton
import com.jdkshen.aijspro.theme.isAijsDarkTheme
import com.jdkshen.aijspro.ui.update.UpdateCheckDialog
import de.psdev.licensesdialog.LicenseResolver
import de.psdev.licensesdialog.LicensesDialog
import de.psdev.licensesdialog.licenses.License
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.extra.SuperDropdown
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import java.io.File
import java.io.IOException

/**
 * Miuix settings page (pilot). Reads and writes the same default SharedPreferences
 * as the legacy PreferenceScreen, so existing listeners (volume keys, guard mode)
 * and Pref helpers keep working.
 */
class MiuixSettingsActivity : ComponentActivity() {

    private var revision by mutableIntStateOf(0)
    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }
    private val disposables = CompositeDisposable()

    private val completionShow = mutableStateOf(false)
    private val completionText = mutableStateOf(TextFieldValue("2000"))
    private val scriptDirShow = mutableStateOf(false)
    private val scriptDirText = mutableStateOf(TextFieldValue("/脚本/"))

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
                    SettingsPage()
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        revision++
    }

    override fun onDestroy() {
        disposables.clear()
        super.onDestroy()
    }

    private fun getKey(id: Int): String = getString(id)

    private fun boolPref(id: Int, def: Boolean): Boolean =
        prefs.getBoolean(getKey(id), def)

    private fun putBoolPref(id: Int, value: Boolean) {
        prefs.edit().putBoolean(getKey(id), value).apply()
    }

    private fun strPref(id: Int, def: String): String =
        prefs.getString(getKey(id), def) ?: def

    private fun putStrPref(id: Int, value: String) {
        prefs.edit().putString(getKey(id), value).apply()
    }

    @Composable
    private fun SettingsPage() {
        val stateRevision = revision
        val recordVolume = remember(stateRevision) {
            boolPref(R.string.key_use_volume_control_record, false)
        }
        val recordToast = remember(stateRevision) { boolPref(R.string.key_record_toast, true) }
        val recordTypeIndex = remember(stateRevision) {
            when (strPref(R.string.key_root_record_out_file_type, "js")) {
                "binary" -> 1
                else -> 0
            }
        }
        val stopVolume = remember(stateRevision) {
            boolPref(R.string.key_use_volume_control_running, false)
        }
        val guardMode = remember(stateRevision) { boolPref(R.string.key_guard_mode, false) }
        val completionLength = remember(stateRevision) {
            strPref(R.string.key_max_length_for_code_completion, "2000")
        }
        val rootAccessibility = remember(stateRevision) {
            boolPref(R.string.key_enable_accessibility_service_by_root, false)
        }
        val stableMode = remember(stateRevision) { boolPref(R.string.key_stable_mode, false) }
        val scriptDir = remember(stateRevision) {
            strPref(R.string.key_script_dir_path, "/脚本/")
        }
        val recordTypeItems = resources.getStringArray(R.array.root_record_out_file_type_keys).toList()
        val recordTypeValues = resources.getStringArray(R.array.root_record_out_file_type_values).toList()

        Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
            SmallTopAppBar(title = getString(R.string.text_setting), defaultWindowInsetsPadding = false,
                navigationIcon = { MiuixBackButton(onClick = { finish() }) })
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SmallTitle(getString(R.string.text_script_record))
                Card(Modifier.fillMaxWidth()) {
                    SuperSwitch(title = getString(R.string.text_use_volume_control_record),
                        summary = getString(R.string.summary_use_volume_control_record),
                        checked = recordVolume,
                        onCheckedChange = { putBoolPref(R.string.key_use_volume_control_record, it); revision++ })
                    SuperSwitch(title = getString(R.string.text_record_msg),
                        checked = recordToast,
                        onCheckedChange = { putBoolPref(R.string.key_record_toast, it); revision++ })
                    SuperDropdown(title = getString(R.string.text_root_record_out_file_type),
                        items = recordTypeItems,
                        selectedIndex = recordTypeIndex,
                        onSelectedIndexChange = { i ->
                            putStrPref(R.string.key_root_record_out_file_type, recordTypeValues[i]); revision++
                        })
                }

                SmallTitle(getString(R.string.text_script_running))
                Card(Modifier.fillMaxWidth()) {
                    SuperSwitch(title = getString(R.string.text_use_volume_to_stop_running),
                        checked = stopVolume,
                        onCheckedChange = { putBoolPref(R.string.key_use_volume_control_running, it); revision++ })
                    SuperSwitch(title = getString(R.string.text_guard_mode),
                        summary = getString(R.string.summary_guard_mode),
                        checked = guardMode,
                        onCheckedChange = { putBoolPref(R.string.key_guard_mode, it); revision++ })
                }

                SmallTitle(getString(R.string.text_edit))
                Card(Modifier.fillMaxWidth()) {
                    SuperArrow(title = getString(R.string.text_max_length_for_code_completion),
                        rightText = completionLength,
                        onClick = { showCompletionDialog() })
                }

                SmallTitle(getString(R.string.text_accessibility_service))
                Card(Modifier.fillMaxWidth()) {
                    SuperSwitch(title = getString(R.string.text_enable_accessibility_service_by_root),
                        summary = getString(R.string.summary_enable_accessibility_service_by_root),
                        checked = rootAccessibility,
                        onCheckedChange = { putBoolPref(R.string.key_enable_accessibility_service_by_root, it); revision++ })
                    SuperSwitch(title = getString(R.string.text_stable_mode),
                        summary = getString(R.string.summary_stable_mode),
                        checked = stableMode,
                        onCheckedChange = { putBoolPref(R.string.key_stable_mode, it); revision++ })
                }

                SmallTitle(getString(R.string.text_others))
                Card(Modifier.fillMaxWidth()) {
                    SuperArrow(title = getString(R.string.text_change_script_dir),
                        rightText = scriptDir,
                        onClick = { showScriptDirDialog() })
                }

                SmallTitle(getString(R.string.text_about))
                Card(Modifier.fillMaxWidth()) {
                    SuperArrow(title = getString(R.string.text_check_for_updates),
                        onClick = { UpdateCheckDialog(this@MiuixSettingsActivity).show() })
                    SuperArrow(title = getString(R.string.text_issue_report),
                        onClick = {
                            IntentTool.browse(this@MiuixSettingsActivity,
                                getString(R.string.my_github) + "/issues")
                        })
                    SuperArrow(title = getString(R.string.text_about_me_and_repo),
                        onClick = {
                            startActivity(Intent(this@MiuixSettingsActivity, AboutActivity::class.java))
                        })
                    SuperArrow(title = getString(R.string.text_licenses),
                        onClick = { showLicenseDialog() })
                }
                Text("AI.js Pro · ${com.jdkshen.aijspro.BuildConfig.VERSION_NAME}",
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier.padding(start = 8.dp, bottom = 32.dp))
            }
        }
        CompletionDialog(
            show = completionShow,
            text = completionText.value,
            onText = { completionText.value = it },
            onConfirm = { confirmCompletion() },
            onDismiss = { completionShow.value = false }
        )
        ScriptDirDialog(
            show = scriptDirShow,
            text = scriptDirText.value,
            onText = { scriptDirText.value = it },
            onDismiss = { scriptDirShow.value = false },
            onApply = { mode -> applyScriptDir(mode) }
        )
    }

    private fun showCompletionDialog() {
        completionText.value = TextFieldValue(
            strPref(R.string.key_max_length_for_code_completion, "2000"))
        completionShow.value = true
    }

    @Composable
    private fun CompletionDialog(
        show: MutableState<Boolean>,
        text: TextFieldValue,
        onText: (TextFieldValue) -> Unit,
        onConfirm: () -> Unit,
        onDismiss: () -> Unit
    ) {
        SuperDialog(show = show, title = getString(R.string.text_max_length_for_code_completion),
            onDismissRequest = onDismiss) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                TextField(value = text, onValueChange = onText, modifier = Modifier.fillMaxWidth(),
                    singleLine = true)
                Row(Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.End) {
                    TextButton(text = "取消", modifier = Modifier.padding(end = 8.dp),
                        onClick = onDismiss, colors = ButtonDefaults.textButtonColorsPrimary())
                    Button(onClick = onConfirm, colors = ButtonDefaults.buttonColorsPrimary()) { Text("确定") }
                }
            }
        }
    }

    private fun confirmCompletion() {
        val value = completionText.value.text.trim().toIntOrNull()
        if (value == null || value <= 0) {
            Toast.makeText(this, "请输入正整数", Toast.LENGTH_SHORT).show()
        } else {
            putStrPref(R.string.key_max_length_for_code_completion, value.toString())
            revision++
            completionShow.value = false
        }
    }

    private fun showScriptDirDialog() {
        scriptDirText.value = TextFieldValue(
            strPref(R.string.key_script_dir_path, "/脚本/"))
        scriptDirShow.value = true
    }

    private fun applyScriptDir(mode: Int) {
        val oldDir = canonicalFile(File(com.jdkshen.aijspro.Pref.getScriptDirPath()))
        val newRel = normalizeScriptDir(scriptDirText.value.text)
        if (newRel == null) {
            Toast.makeText(this, "请输入有效的相对路径，不能包含 .. 或冒号", Toast.LENGTH_LONG).show()
            return
        }
        val storageRoot = canonicalFile(Environment.getExternalStorageDirectory())
        val newDir = canonicalFile(File(storageRoot, newRel.trim('/')))
        if (!isWithin(newDir, storageRoot)) {
            Toast.makeText(this, "脚本目录必须位于内部存储中", Toast.LENGTH_LONG).show()
            return
        }
        if (newDir == oldDir) {
            putStrPref(R.string.key_script_dir_path, newRel)
            com.jdkshen.aijspro.model.explorer.Explorers.workspace().refreshAll()
            revision++
            scriptDirShow.value = false
            return
        }

        if (mode == 0) {
            if (!newDir.isDirectory && !newDir.mkdirs()) {
                Toast.makeText(this, "无法创建目录：${newDir.path}", Toast.LENGTH_LONG).show()
                return
            }
            putStrPref(R.string.key_script_dir_path, newRel)
            com.jdkshen.aijspro.model.explorer.Explorers.workspace().refreshAll()
            revision++
            scriptDirShow.value = false
            return
        }

        if (!oldDir.isDirectory) {
            Toast.makeText(this, "原脚本目录不存在，未修改设置", Toast.LENGTH_LONG).show()
            return
        }
        if (isWithin(newDir, oldDir) || isWithin(oldDir, newDir)) {
            Toast.makeText(this, "新旧目录不能互相包含，以免循环复制", Toast.LENGTH_LONG).show()
            return
        }

        scriptDirShow.value = false
        val observable = if (mode == 1) FileObservable.copy(oldDir.path, newDir.path)
        else FileObservable.move(oldDir.path, newDir.path)
        Toast.makeText(this, getString(R.string.text_on_progress), Toast.LENGTH_SHORT).show()
        disposables.add(observable.subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ }, { e ->
                // Keep the old preference on any failure; copied files remain recoverable.
                Toast.makeText(this, "操作失败，仍使用原目录: ${e.message}", Toast.LENGTH_LONG).show()
            }, {
                putStrPref(R.string.key_script_dir_path, newRel)
                com.jdkshen.aijspro.model.explorer.Explorers.workspace().refreshAll()
                revision++
                Toast.makeText(this, "完成", Toast.LENGTH_SHORT).show()
            }))
    }

    private fun normalizeScriptDir(input: String): String? {
        val value = input.trim().replace('\\', '/')
        if (value.isEmpty() || value.indexOf('\u0000') >= 0 || ':' in value) return null
        val parts = value.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.any { it == "." || it == ".." }) return null
        return "/${parts.joinToString("/")}/"
    }

    private fun canonicalFile(file: File): File = try {
        file.canonicalFile
    } catch (_: IOException) {
        file.absoluteFile
    }

    private fun isWithin(file: File, root: File): Boolean =
        file == root || file.path.startsWith(root.path + File.separator)

    @Composable
    private fun ScriptDirDialog(
        show: MutableState<Boolean>,
        text: TextFieldValue,
        onText: (TextFieldValue) -> Unit,
        onDismiss: () -> Unit,
        onApply: (Int) -> Unit
    ) {
        SuperDialog(show = show, title = getString(R.string.text_change_script_dir),
            summary = "输入脚本文件夹相对路径（如 /脚本/）", onDismissRequest = onDismiss) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                TextField(value = text, onValueChange = onText, modifier = Modifier.fillMaxWidth(),
                    singleLine = true)
                Row(Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onApply(0) }, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColorsPrimary()) { Text("仅刷新") }
                    Button(onClick = { onApply(1) }, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColorsPrimary()) { Text("复制") }
                    Button(onClick = { onApply(2) }, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColorsPrimary()) { Text("移动") }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End) {
                    TextButton(text = "取消", onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColorsPrimary())
                }
            }
        }
    }

    private fun showLicenseDialog() {
        LicenseResolver.registerLicense(MozillaPublicLicense20.instance)
        LicensesDialog.Builder(this)
            .setNotices(R.raw.licenses)
            .setIncludeOwnLicense(true)
            .build()
            .showAppCompat()
    }

    class MozillaPublicLicense20 : License() {
        companion object {
            val instance = MozillaPublicLicense20()
        }

        override fun getName(): String = "Mozilla Public License 2.0"

        override fun readSummaryTextFromResources(context: android.content.Context): String =
            getContent(context, R.raw.mpl_20_summary)

        override fun readFullTextFromResources(context: android.content.Context): String =
            getContent(context, R.raw.mpl_20_full)

        override fun getVersion(): String = "2.0"

        override fun getUrl(): String = "https://www.mozilla.org/en-US/MPL/2.0/"
    }
}
