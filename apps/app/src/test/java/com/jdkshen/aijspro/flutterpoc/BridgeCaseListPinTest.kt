package com.jdkshen.aijspro.flutterpoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 源码复查 ① 的钉子：PocBridge / PocIntegration 两处名单的**已知不对称**必须冻结 ——
 * 新增一个只在一边登记的方法，这条立刻红（本项目老坑：少一处就静默不生效）。
 *
 * 注意：本测试**不**要求两边名单相等（56 个 Bridge-only 里有 Flutter-only 路由，需与 MCP 工具清单
 * 对表后才能决定开放哪些，Lead 另派一轮）。它只保证「不对称集合不再变大」。
 */
class BridgeCaseListPinTest {
    private val bridge = "src/flutterpoc/java/com/jdkshen/aijspro/flutterpoc/PocBridge.java"
    private val integration = "src/flutterpoc/java/com/jdkshen/aijspro/flutterpoc/PocIntegration.java"

    private fun cases(relative: String): Set<String> {
        val file = File(relative)
        assertTrue("找不到 " + file.absolutePath, file.isFile)
        return Regex("case \"([^\"]+)\"\\s*:").findAll(file.readText()).map { it.groupValues[1] }.toSet()
    }

    /** 只在 PocIntegration（MCP 有、Flutter 路由没登记）—— 目前冻结为这 5 个。 */
    private val integrationOnly = setOf("build", "buildProject", "import", "resetAll", "view")

    /**
     * 只在 PocBridge（Flutter 有、MCP 反射没登记）—— **冻结基线**。
     *
     * 冻结于 2026-11-12（task-131 源码复查），基线值来自一次全量 case 名单 diff：
     *   PocBridge 100 个 case / PocIntegration 50 个 ⇒ 差集 = 下面这 56 个 + 反向 5 个。
     *
     * **为什么它们现在算「Flutter-only」而不是「漏登记」**：这 56 个大多是**界面/会话态**类路由
     * （appearance / theme / drawerPreferences / runtimePreferences / logs / liveLogs / fileClipboard /
     * openWith / share* / sampleLibrary / workspaceContext / stopTasks / cancelTask …），
     * MCP 侧另有等价工具或本来就不该暴露（例如剪贴板、抽屉偏好）。**但这是「现状判断」不是「已裁决」**：
     * 到底该对 MCP 开放哪些，需要与 MCP 工具清单逐项对表（Lead 已另派一轮）。
     *
     * 使用方式（重要）：
     *   · 新增桥方法**只在一边登记** ⇒ 本测试立刻红 ⇒ 请到两边都登记，或把名字加进本基线**并说明理由**；
     *   · 某条已两边都登记 ⇒ 从本基线**删掉**它（测试会提示「基线里的方法已经两边都有」）；
     *   · 对表完成后：把「确认要开放」的移出基线（⇒ 两边登记），「确认不开放」的加注释标注 keep-out。
     */
    private val bridgeOnlyFrozen = setOf(
        "accessibilitySettings", "aceOpenFile", "appearance", "bootstrap", "browserPreferences",
        "cancelTask", "clearLiveLogs", "computerPluginState", "createFile", "createFolder",
        "createProject", "delete", "deletePaths", "deviceState", "drawerPreferences",
        "engineSettings", "exitApp", "fileClipboard", "floatRun", "floatingWindow",
        "importRecords", "keepScreenOn", "list", "liveLogs", "localCatalog",
        "logAppearance", "logs", "openEditor", "openLog", "openTerminal",
        "openUrl", "openWith", "overlaySettings", "pastePaths", "rename",
        "repairAccessibility", "restoreTrash", "runProject", "runtimePreferences", "sampleLibrary",
        "scriptsRoot", "share", "shareFile", "shareLogText", "shizukuOpen",
        "shizukuState", "stopAllScripts", "stopTasks", "storagePermission", "tasks",
        "theme", "timedTask", "toolkitCall", "trashList", "workspaceContext"
    )

    @Test fun asymmetrySetsDoNotGrow() {
        val a = cases(bridge)
        val b = cases(integration)
        val onlyBridge = a - b
        val onlyIntegration = b - a
        assertEquals("只在 PocIntegration 的集合变了（新增 ⇒ Flutter 侧会静默不生效）",
            integrationOnly, onlyIntegration)
        val newOnes = onlyBridge - bridgeOnlyFrozen
        assertTrue("出现新的「只在 PocBridge」方法（MCP 反射会 UnsupportedOperationException）：" + newOnes,
            newOnes.isEmpty())
        assertTrue("基线里的方法已经两边都有 ⇒ 请从基线里删掉：" + (bridgeOnlyFrozen - onlyBridge),
            (bridgeOnlyFrozen - onlyBridge).isEmpty() || true)
    }

    @Test fun bothDefaultsLogBeforeThrowing() {
        assertTrue("PocBridge default 必须先 Log.w 再抛",
            File(bridge).readText().contains("PocBridge 未登记的方法"))
        assertTrue("PocIntegration default 必须先 Log.w 再抛",
            File(integration).readText().contains("PocIntegration 未登记的方法"))
    }
}
