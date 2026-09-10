package com.jdkshen.aijspro.ui.community

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.jdkshen.aijspro.network.NodeBB
import com.jdkshen.aijspro.theme.AijsMiuixTheme
import com.jdkshen.aijspro.ui.main.QueryEvent
import com.jdkshen.aijspro.ui.main.ViewPagerFragment
import com.jdkshen.aijspro.ui.main.community.CommunityFragment
import com.jdkshen.aijspro.ui.main.community.CommunityWebView
import com.stardust.util.BackPressedHandler
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import java.net.URLEncoder

/**
 * Miuix community tab. Reuses CommunityWebView (and all its JS/bridge behaviour);
 * only the outer chrome is Miuix.
 */
class MiuixCommunityFragment : ViewPagerFragment(0), BackPressedHandler {

    private var eWebView: CommunityWebView? = null
    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EventBus.getDefault().register(this)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AijsMiuixTheme { CommunityPage() }
            }
        }
    }

    @Composable
    private fun CommunityPage() {
        Column(Modifier.fillMaxSize().background(top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.background)) {
            AndroidView(
                modifier = Modifier.fillMaxWidth(),
                factory = { context ->
                    CommunityWebView(context).also { view ->
                        eWebView = view
                        webView = view.webView
                        val saved = arguments?.getBundle("savedWebViewState")
                        if (saved != null) {
                            webView?.restoreState(saved)
                        } else {
                            webView?.loadUrl("https://github.com/Jdkshen/ai.js-pro")
                        }
                    }
                }
            )
        }
    }

    override fun onPause() {
        super.onPause()
        webView?.let {
            val saved = Bundle()
            it.saveState(saved)
            arguments?.putBundle("savedWebViewState", saved)
        }
    }

    override fun onBackPressed(activity: Activity): Boolean {
        val wv = webView
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
            return true
        }
        return false
    }

    override fun onFabClick(fab: FloatingActionButton?) {
        val wv = webView ?: return
        wv.loadUrl("https://github.com/Jdkshen/ai.js-pro/issues/new/choose")
    }

    @Subscribe
    fun onLoadUrl(event: CommunityFragment.LoadUrl) {
        webView?.loadUrl(NodeBB.url(event.url))
    }

    @Subscribe
    fun onSubmitQuery(event: QueryEvent) {
        if (!isShown || event == QueryEvent.CLEAR) return
        webView?.loadUrl("https://github.com/Jdkshen/ai.js-pro/issues?q=" +
            URLEncoder.encode(event.query, "UTF-8"))
        event.collapseSearchView()
    }

    override fun onPageShow() {
        super.onPageShow()
        EventBus.getDefault().post(CommunityFragment.VisibilityChange(true))
    }

    override fun onPageHide() {
        super.onPageHide()
        EventBus.getDefault().post(CommunityFragment.VisibilityChange(false))
    }

    override fun onDestroy() {
        super.onDestroy()
        EventBus.getDefault().unregister(this)
    }
}
