package com.jdkshen.aijspro.flutterpoc

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * task-131 batch B 钉子：两处登记 + 失败不静默 + 原因码 + 回退链 + 图标 + 下载 stall 超时 + 安装权限预检。
 * 纯源码级（不需要 Android 运行时），路径相对 apps/app 解析。
 */
class PluginBridgeRoutingPinTest {
    private val bridgePath = "src/flutterpoc/java/com/jdkshen/aijspro/flutterpoc/PluginBridge.java"
    private val methods = listOf("pluginCatalog", "pluginInstall", "pluginInstallStatus", "pluginUninstall")

    private fun read(relative: String): String {
        val file = File(relative)
        assertTrue("找不到 " + file.absolutePath, file.isFile)
        return file.readText()
    }

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
        val pluginBridge = read(bridgePath)
        assertTrue("必须 Log.w 后再抛", pluginBridge.contains("Log.w(TAG, method + \" 失败: \""))
        assertTrue("下载/校验失败也要有日志", pluginBridge.contains("pluginInstall 校验不符"))
        assertTrue("校验不符必须不安装（失败分支在 installApk 之前）",
            pluginBridge.indexOf("finish(id, \"failed\", PluginFailureReason.CHECKSUM.getCode()") < pluginBridge.indexOf("installApk(activity, target)"))
        assertTrue("pending 不可下载", pluginBridge.contains("清单标 pending：该插件 APK 尚未构建，暂不可下载"))
        assertTrue("必须复用强制校验", pluginBridge.contains("PluginVerifier.verify("))
    }

    @Test fun reasonCodesMatchTheAgreedFive() {
        val pluginBridge = read(bridgePath)
        for (code in listOf("NETWORK", "CHECKSUM", "PACKAGE", "PERMISSION", "MISSING")) {
            assertTrue("必须用 PluginFailureReason." + code, pluginBridge.contains("PluginFailureReason." + code))
        }
        val reasons = read("src/main/java/com/jdkshen/aijspro/network/PluginPlanReasons.kt")
        assertTrue("要有 source 码", reasons.contains("SOURCE(") && reasons.contains("\"source\""))
        assertTrue("要有 manifest 码", reasons.contains("MANIFEST(") && reasons.contains("\"manifest\""))
        assertTrue("源问题抛点带 source: 标记", pluginBridge.contains("source:请求失败 HTTP ")
            && pluginBridge.contains("source:下载失败 HTTP "))
        assertTrue("清单问题抛点带 manifest: 标记", pluginBridge.contains("manifest:清单为空"))
    }

    @Test fun builtinFallbackChainIsOrderedAndEndsAtRaw() {
        val downloads = read("src/main/java/com/jdkshen/aijspro/network/PluginDownloads.java")
        val custom = downloads.indexOf("\"custom\"")
        val release = downloads.indexOf("\"builtin-release\"")
        val raw = downloads.indexOf("\"builtin-raw\"")
        val direct = downloads.indexOf("\"builtin-direct\"")
        assertTrue("四个来源标签都要在", custom > 0 && release > 0 && raw > 0 && direct > 0)
        assertTrue("自建源必须优先", custom < release)
        assertTrue("顺序固定 release -> raw -> direct", release < raw && raw < direct)
        assertTrue("raw 回退指向 plugin-release 分支",
            downloads.contains("raw.githubusercontent.com/Jdkshen/ai.js-pro/plugin-release/plugin-release/"))
        val pluginBridge = read(bridgePath)
        assertTrue("清单走候选链", pluginBridge.contains("pluginUrlCandidates(activity, CATALOG_FILE)"))
        assertTrue("APK 走同一条候选链", pluginBridge.contains("pluginUrlCandidates(activity, entry.getFile())"))
        assertTrue("逐条降级留日志", pluginBridge.contains("pluginCatalog 源失败 source=")
            && pluginBridge.contains("pluginInstall 源失败 source="))
    }

    @Test fun catalogFetchesOffTheCallerThread() {
        val pluginBridge = read(bridgePath)
        val catalog = pluginBridge.substringAfter("private static JSONObject catalog(final Activity activity)")
            .substringBefore("private static JSONObject catalogOnWorker")
        assertTrue("catalog 必须在后台线程跑", catalog.contains("new Thread("))
        assertTrue("catalog 必须同步等结果", catalog.contains("worker.join(CATALOG_TIMEOUT_MS)"))
        assertTrue("必须有总兜底超时", pluginBridge.contains("CATALOG_TIMEOUT_MS = 20000L"))
        assertTrue("每个候选各自约 6s 预算", pluginBridge.contains("CANDIDATE_CONNECT_TIMEOUT_MS = 3000")
            && pluginBridge.contains("CANDIDATE_READ_TIMEOUT_MS = 3000"))
        assertTrue("失败原因要能看出试了几条/最后一条/耗时",
            pluginBridge.contains("试了 ") && pluginBridge.contains("条源，耗时 ") && pluginBridge.contains("最后一条="))
        assertTrue("catalog 本身不得直接 httpGet", !catalog.contains("httpGet("))
        assertTrue("后台线程体与单源拉取方法都要在",
            pluginBridge.contains("private static JSONObject catalogOnWorker")
                && pluginBridge.contains("catalogFromSource("))
        assertTrue("失败要带异常类型", catalog.contains("classify(error)") && catalog.contains("getSimpleName()"))
    }

    @Test fun downloadUsesStallTimeoutAndReportsProgress() {
        val pluginBridge = read(bridgePath)
        assertTrue("read 超时按 stall 判定（无总时长上限）", pluginBridge.contains("READ_TIMEOUT_MS = 60000"))
        assertTrue("connect 10s", pluginBridge.contains("CONNECT_TIMEOUT_MS = 10000"))
        assertTrue("下载连接用这两个超时", pluginBridge.contains("connection.setReadTimeout(READ_TIMEOUT_MS)"))
        assertTrue("下载过程中要持续写进度", pluginBridge.contains("PROGRESS_STEP_BYTES"))
        assertTrue("进度含 received/total", pluginBridge.contains("receivedBytes") && pluginBridge.contains("totalBytes"))
        assertTrue("起手就有 running 状态", pluginBridge.contains("started.put"))
        assertTrue("下载失败要带可区分原因", pluginBridge.contains("source:下载失败 HTTP ")
            && pluginBridge.contains("classify(error)"))
    }

    /** 安装未知来源预检 + 「去设置」入口（用户点击才跳）。 */
    @Test fun installChecksUnknownSourcesPermissionFirst() {
        val pluginBridge = read(bridgePath)
        val integration = read("src/flutterpoc/java/com/jdkshen/aijspro/flutterpoc/PocIntegration.java")
        val route = read("src/flutterpoc/java/com/jdkshen/aijspro/flutterpoc/PocBridge.java")
        assertTrue("安装前必须预检", pluginBridge.contains("canRequestPackageInstalls()"))
        assertTrue("预检失败写 permission 码", pluginBridge.contains("PluginFailureReason.PERMISSION.getCode()"))
        assertTrue("要给出可执行文案", pluginBridge.contains("系统未允许安装未知来源应用"))
        assertTrue("预检必须在 installApk 之前（失败不起安装器）",
            pluginBridge.indexOf("canInstallPackages(activity)") < pluginBridge.indexOf("installApk(activity, target)"))
        assertTrue("要给 UI 一个去设置动作", pluginBridge.contains("openInstallPermissionSettings")
            && pluginBridge.contains("actionLabel"))
        assertTrue("设置页要带 package: 精确定位",
            pluginBridge.contains("ACTION_MANAGE_UNKNOWN_APP_SOURCES")
                && pluginBridge.contains("package:\" + activity.getPackageName()"))
        assertTrue("检查路径不得自动跳设置", pluginBridge.contains("用户主动点击才调"))
        assertTrue("两处登记都要有该桥方法",
            route.contains("openInstallPermissionSettings") && integration.contains("openInstallPermissionSettings"))
    }

    @Test fun catalogProvidesLocalIconPathsWithoutNetwork() {
        val pluginBridge = read(bridgePath)
        assertTrue("清单里要调图标填充", pluginBridge.contains("applyPluginIcon(activity, row, entry)"))
        assertTrue("图标要落到 app 缓存目录", pluginBridge.contains("getCacheDir(), ICON_DIR"))
        assertTrue("缓存目录名固定", pluginBridge.contains("ICON_DIR = \"plugin-icons\""))
        assertTrue("文件名带版本 ⇒ 版本变自然重画", pluginBridge.contains("-v\" + version + \".png\""))
        assertTrue("未安装留空", pluginBridge.contains("row.put(\"iconPath\", \"\")"))
        assertTrue("可选内联", pluginBridge.contains("iconBase64"))
        assertTrue("失败只记日志", pluginBridge.contains("pluginCatalog 图标失败 pkg="))
    }

    @Test fun uninstallIsDeliveredAsDisable() {
        val pluginBridge = read(bridgePath)
        assertTrue("卸载按停用交付", pluginBridge.contains("本轮没有卸载桥"))
        assertTrue("停用态要有明确字段", pluginBridge.contains("data.put(\"disabled\", true)"))
    }
/** 桌面快捷方式入口（可选）：pin 分支 + 旧广播分支 + 不支持时明确原因（不静默）。 */
    @Test fun launcherShortcutBridgeHasPinAndLegacyFallback() {
        val pluginBridge = read(bridgePath)
        val route = read("src/flutterpoc/java/com/jdkshen/aijspro/flutterpoc/PocBridge.java")
        val integration = read("src/flutterpoc/java/com/jdkshen/aijspro/flutterpoc/PocIntegration.java")
        assertTrue("pin 分支（API>=26）", pluginBridge.contains("requestPinShortcut("))
        assertTrue("旧广播分支（API25）", pluginBridge.contains("com.android.launcher.action.INSTALL_SHORTCUT"))
        assertTrue("低版本要给明确原因", pluginBridge.contains("系统版本过低（创建快捷方式需 Android 7.1+）"))
        assertTrue("失败不静默", pluginBridge.contains("createLauncherShortcut 不可用 sdk=")
            && pluginBridge.contains("createLauncherShortcut 不被支持"))
        assertTrue("返回要区分 supported/requested", pluginBridge.contains("supported")
            && pluginBridge.contains("requested"))
        assertTrue("Intent 要带插件页路由", pluginBridge.contains("route") && pluginBridge.contains("openPluginTab"))
        assertTrue("两处登记都要有", route.contains("createLauncherShortcut")
            && integration.contains("createLauncherShortcut"))
    }
}
