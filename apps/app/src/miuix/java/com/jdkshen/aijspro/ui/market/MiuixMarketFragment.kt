package com.jdkshen.aijspro.ui.market

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.jdkshen.aijspro.R
import com.jdkshen.aijspro.network.TopicService
import com.jdkshen.aijspro.network.entity.topic.AppInfo
import com.jdkshen.aijspro.network.entity.topic.Topic
import com.jdkshen.aijspro.theme.AijsMiuixTheme
import com.jdkshen.aijspro.ui.main.ViewPagerFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.joda.time.format.DateTimeFormat
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Miuix market/scripts list. Data source and interaction stay identical to MarketFragment. */
class MiuixMarketFragment : ViewPagerFragment(0) {

    private var topics by mutableStateOf<List<Topic>>(emptyList())
    private var loading by mutableStateOf(true)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AijsMiuixTheme { MarketPage() }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        refresh()
    }

    private fun refresh() {
        loading = true
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val list = TopicService.getScriptsTopics()
                GlobalScope.launch(Dispatchers.Main) {
                    topics = list
                    loading = false
                }
            } catch (e: Exception) {
                e.printStackTrace()
                GlobalScope.launch(Dispatchers.Main) {
                    loading = false
                    Toast.makeText(requireContext(), "加载失败，请稍后重试", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    @Composable
    private fun MarketPage() {
        Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
            if (loading) {
                Text("加载中…", fontSize = 15.sp,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier.padding(24.dp))
            } else if (topics.isEmpty()) {
                Text("暂无脚本", fontSize = 15.sp,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier.padding(24.dp))
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(topics, key = { it.tid }) { topic -> TopicCard(topic) }
                }
            }
        }
    }

    @Composable
    private fun TopicCard(topic: Topic) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(40.dp).background(
                            MiuixTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            topic.user.username.take(1).uppercase(),
                            color = MiuixTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                            fontSize = 16.sp
                        )
                    }
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(topic.title, fontSize = 16.sp,
                            color = MiuixTheme.colorScheme.onSurface,
                            maxLines = 2)
                        Text(
                            "${topic.user.username} · " +
                                DateTimeFormat.mediumDateTime().print(topic.timestamp.toLong()),
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val root = topic.appInfo.permissions.contains(AppInfo.PERMISSION_ROOT)
                    TextButton(text = if (root) "需要Root" else "无需Root",
                        onClick = { }) // Same as legacy: no action yet
                    TextButton(text = "运行", onClick = { })
                }
            }
        }
    }

    override fun onFabClick(fab: FloatingActionButton?) = Unit
    override fun onBackPressed(activity: Activity): Boolean = false
}
