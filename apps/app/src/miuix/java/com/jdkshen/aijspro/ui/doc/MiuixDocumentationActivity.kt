package com.jdkshen.aijspro.ui.doc

import android.content.Intent
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsControllerCompat
import com.jdkshen.aijspro.Pref
import com.jdkshen.aijspro.R
import com.jdkshen.aijspro.theme.AijsMiuixTheme
import com.jdkshen.aijspro.theme.MiuixBackButton
import com.jdkshen.aijspro.theme.isAijsDarkTheme
import com.jdkshen.aijspro.ui.widget.EWebView
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Miuix documentation page (pilot). Same WebView behaviour as DocumentationActivity.
 */
class MiuixDocumentationActivity : ComponentActivity() {

    private var eWebView: EWebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val dark = isAijsDarkTheme()
                AijsMiuixTheme {
                    val bg = MiuixTheme.colorScheme.background
                    SideEffect {
                        window.statusBarColor = bg.toArgb()
                        window.navigationBarColor = bg.toArgb()
                        WindowInsetsControllerCompat(window, window.decorView).apply {
                            isAppearanceLightStatusBars = !dark
                            isAppearanceLightNavigationBars = !dark
                        }
                    }
                    DocPage()
                }
            }
        })
    }

    @Composable
    private fun DocPage() {
        Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
            SmallTopAppBar(title = getString(R.string.text_tutorial), defaultWindowInsetsPadding = false,
                navigationIcon = { MiuixBackButton(onClick = { finish() }) })
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { context ->
                    EWebView(context).also { view ->
                        eWebView = view
                        val webView: WebView = view.webView
                        val url = intent.getStringExtra(EXTRA_URL)
                            ?: (Pref.getDocumentationUrl() + "index.html")
                        webView.loadUrl(url)
                    }
                }
            )
        }
    }

    override fun onBackPressed() {
        val webView = eWebView?.webView
        if (webView != null && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    /** 已在文档页时再次 startActivity：系统只投递 onNewIntent，这里换页。 */
    /**
     * 已在文档页时再次 startActivity（清单里声明了 singleTop）：系统只投递 onNewIntent，
     * 不会重建 Activity，这里把新的 url 载进去。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val url = intent.getStringExtra(EXTRA_URL) ?: (Pref.getDocumentationUrl() + "index.html")
        eWebView?.webView?.loadUrl(url)
    }

    companion object {
        const val EXTRA_URL = "url"
    }
}
