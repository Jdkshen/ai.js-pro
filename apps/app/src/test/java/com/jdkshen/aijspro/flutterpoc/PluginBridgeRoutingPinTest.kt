package com.jdkshen.aijspro.flutterpoc

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * task-131 batch B 钉子：插件桥**两处登记**（task-100 的静默失败根因）+ 失败不静默 + 原因码同源。
 *
 * 说明：本测试是源码级（不需要 Android 运行时），路径相对 apps/app 解析。
 */
class PluginBridgeRoutingPinTest {
    private fun read(relative: String): String {
        val file = File(relative)
        assertTrue("找不到 " + file.absolutePath, file.isFile)
        return file.readText()
    }

    private val methods = listOf("pluginCatalog", "pluginInstall", "pluginInstallStatus", "pluginUninstall")

    @Test fun bothRegistrationSitesListEveryMethod() {
        val bridge = read("src/flutterpoc/java/com/jdkshen/aijspro/flutterpoc/PocBridge.java")
        val integration = read("src/flutterpoc/java/com/jdkshen/aijspro/flutterpoc/PocIntegration.java")
        for (method in methods) {
            assertTrue("PocBridge 路由缺 " + method, bridge.contains("case \"" + method + "\""))
            assertTrue("PocIntegration 分发缺 " + method, integration.contains("case \"" + method + "\""))
        }
        assertTrue("PocIntegration 必须转调 PluginBridge", integration.contains("PluginBridge.execute(activity, method, args)"))
    }

    @Test fun failuresAreLoggedNotSwallowed() {
        val pluginBridge = read("src/flutterpoc/java/com/jdkshen/aijspro/flutterpoc/PluginBridge.java")
        assertTrue("必须 Log.w 后再抛", pluginBridge.contains("Log.w(TAG, method + \" 失败: \""))
        assertTrue("下载/校验失败也要有日志", pluginBridge.contains("pluginInstall 校验不符"))
        assertTrue("校验不符必须不安装（先返回失败分支）",
            pluginBridge.indexOf("finish(id, \"failed\", PluginFailureReason.CHECKSUM.getCode()") < pluginBridge.indexOf("installApk(activity, target)"))
        assertTrue("pending 不可下载", pluginBridge.contains("清单标 pending：该插件 APK 尚未构建，暂不可下载"))
        assertTrue("必须复用强制校验", pluginBridge.contains("PluginVerifier.verify("))
    }

    @Test fun reasonCodesMatchTheAgreedFive() {
        val pluginBridge = read("src/flutterpoc/java/com/jdkshen/aijspro/flutterpoc/PluginBridge.java")
        for (code in listOf("NETWORK", "CHECKSUM", "PACKAGE", "PERMISSION", "MISSING")) {
            assertTrue("必须用 PluginFailureReason." + code, pluginBridge.contains("PluginFailureReason." + code))
        }
    }

    /** dev-1494 真机 bug：pluginCatalog 的网络读取原来跑在**调用线程（UI 主线程）** ⇒ NetworkOnMainThreadException。
     *  这条钉住「同步桥 + 后台执行 + join 等结果」的结构，防止有人把 fetch 挪回同步路径。 */
    @Test fun catalogFetchesOffTheCallerThread() {
        val pluginBridge = read("src/flutterpoc/java/com/jdkshen/aijspro/flutterpoc/PluginBridge.java")
        val catalog = pluginBridge.substringAfter("private static JSONObject catalog(final Activity activity)")
            .substringBefore("/** 后台线程体")
        assertTrue("catalog 必须在后台线程跑", catalog.contains("new Thread("))
        assertTrue("catalog 必须同步等结果（Dart 侧是同步桥）", catalog.contains("worker.join(CATALOG_TIMEOUT_MS)"))
        assertTrue("必须有超时兜底", pluginBridge.contains("CATALOG_TIMEOUT_MS = 6000L"))
        assertTrue("超时要给可解释原因", catalog.contains("network:插件清单拉取后台超时"))
        assertTrue("网络读取必须只在 catalogOnWorker 里", !catalog.contains("httpGet(") && pluginBridge.contains("private static JSONObject catalogOnWorker"))
        assertTrue("失败要带异常类型", catalog.contains("classify(error)") && catalog.contains("error.getClass().getSimpleName()"))
    }

    @Test fun uninstallIsDeliveredAsDisable() {
        val pluginBridge = read("src/flutterpoc/java/com/jdkshen/aijspro/flutterpoc/PluginBridge.java")
        assertTrue("卸载按停用交付", pluginBridge.contains("本轮没有卸载桥"))
        assertTrue("停用态要有明确字段", pluginBridge.contains("data.put(\"disabled\", true)"))
    }
}