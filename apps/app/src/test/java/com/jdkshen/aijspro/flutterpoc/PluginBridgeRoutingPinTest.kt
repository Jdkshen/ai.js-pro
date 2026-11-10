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

    @Test fun uninstallIsDeliveredAsDisable() {
        val pluginBridge = read("src/flutterpoc/java/com/jdkshen/aijspro/flutterpoc/PluginBridge.java")
        assertTrue("卸载按停用交付", pluginBridge.contains("本轮没有卸载桥"))
        assertTrue("停用态要有明确字段", pluginBridge.contains("data.put(\"disabled\", true)"))
    }
}
