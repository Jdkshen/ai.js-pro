package com.jdkshen.aijspro.ui.settings

import android.os.Bundle
import android.text.Html
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsControllerCompat
import com.jdkshen.aijspro.R
import com.jdkshen.aijspro.theme.AijsMiuixTheme
import com.jdkshen.aijspro.theme.MiuixBackButton
import com.jdkshen.aijspro.theme.isAijsDarkTheme
import com.jdkshen.aijspro.tool.IntentTool
import de.psdev.licensesdialog.LicenseResolver
import de.psdev.licensesdialog.LicensesDialog
import de.psdev.licensesdialog.NoticesXmlParser
import de.psdev.licensesdialog.licenses.License
import de.psdev.licensesdialog.model.Notice
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Miuix 版「开放源代码许可」页。
 *
 * 数据与旧 WebView 对话框完全同源：res/raw/licenses.xml 的全部 notice
 * （经 NoticesXmlParser 解析、LicenseResolver 解析许可全文）+ LicensesDialog
 * 库自身的许可条目（对应旧对话框 setIncludeOwnLicense(true) 的那条）。
 * 仅把展示层换成 Compose/Miuix（圆角卡片 + 二级文字排版），不再用旧对话框。
 */
class MiuixLicensesActivity : ComponentActivity() {

    private val disposables = CompositeDisposable()
    private var notices by mutableStateOf<List<Notice>?>(null)

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
                    Screen()
                }
            }
        })
        disposables.add(Observable.fromCallable { loadNotices() }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ notices = it }, { error -> error.printStackTrace() }))
    }

    override fun onDestroy() {
        disposables.clear()
        super.onDestroy()
    }

    /** 与旧对话框同一份数据：licenses.xml 全部 notice + LicensesDialog 自身许可条目。 */
    private fun loadNotices(): List<Notice> {
        // 旧实现的同名注册（自定义 MPL 2.0：全文来自 res/raw/mpl_20_full.txt）。
        LicenseResolver.registerLicense(MozillaPublicLicense20.instance)
        val result = ArrayList<Notice>()
        resources.openRawResource(R.raw.licenses).use { input ->
            result.addAll(NoticesXmlParser.parse(input).notices)
        }
        result.add(LicensesDialog.LICENSES_DIALOG_NOTICE)
        return result
    }

    @Composable
    private fun Screen() {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
                SmallTopAppBar(
                    title = getString(R.string.text_licenses),
                    defaultWindowInsetsPadding = false,
                    navigationIcon = { MiuixBackButton(onClick = { finish() }) })
                val list = notices
                if (list == null) {
                    Text(
                        "加载许可列表…", fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                    )
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize().padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(list, key = { it.name ?: it.hashCode().toString() }) { notice ->
                            LicenseCard(notice)
                        }
                        item { Spacer(Modifier.height(24.dp)) }
                    }
                }
            }
        }
    }

    @Composable
    private fun LicenseCard(notice: Notice) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(notice.name ?: "", fontSize = 15.sp, color = MiuixTheme.colorScheme.onSurface)
                val copyright = notice.copyright
                if (!copyright.isNullOrBlank()) {
                    Text(
                        copyright, fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary
                    )
                }
                val url = notice.url
                if (!url.isNullOrBlank()) {
                    Text(
                        url, fontSize = 12.sp, color = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            IntentTool.browse(this@MiuixLicensesActivity, url)
                        })
                }
                val license = notice.license
                if (license != null) {
                    // 库的全文是 HTML（旧对话框直接交给 WebView）；这里转纯文本按段落排版。
                    val fullText = remember(notice) {
                        runCatching {
                            Html.fromHtml(
                                license.getFullText(this@MiuixLicensesActivity),
                                Html.FROM_HTML_MODE_LEGACY
                            ).toString().trim()
                        }.getOrNull()
                    }
                    if (!fullText.isNullOrEmpty()) {
                        Text(
                            fullText, fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary
                        )
                    }
                }
            }
        }
    }

    /** 与旧实现一致的自定义许可（MPL 2.0：摘要/全文均来自 raw 资源）。 */
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
