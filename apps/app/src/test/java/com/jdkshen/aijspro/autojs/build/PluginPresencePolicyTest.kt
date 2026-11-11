package com.jdkshen.aijspro.autojs.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Test

/** task-131/132：OCR 载荷处置策略（默认行为逐字不变是第一条硬约束）。 */
class PluginPresencePolicyTest {
    private fun decide(
        removeOcr: Boolean = false,
        pluginInstalled: Boolean = false,
        hostHasOcr: Boolean = true,
        pluginHasOcr: Boolean = false,
        choice: PluginPresencePolicy.BundleOcrChoice = PluginPresencePolicy.BundleOcrChoice.AUTO
    ) = PluginPresencePolicy.decide(removeOcr, pluginInstalled, hostHasOcr, pluginHasOcr, choice)

    @Test fun defaultInputsReproduceTodaysBehaviour() {
        val d = decide()
        assertEquals(PluginPresencePolicy.OcrDisposition.KEEP, d.disposition)
        assertTrue(d.reason, d.reason.contains("与今天逐字一致"))
    }

    @Test fun removeOcrAndExplicitPluginOnlyBothDropWithStub() {
        val bySwitch = decide(removeOcr = true)
        assertEquals(PluginPresencePolicy.OcrDisposition.DROP_AND_STUB, bySwitch.disposition)
        assertTrue(bySwitch.reason.contains("removeOcr"))
        val byChoice = decide(choice = PluginPresencePolicy.BundleOcrChoice.PLUGIN_ONLY)
        assertEquals(PluginPresencePolicy.OcrDisposition.DROP_AND_STUB, byChoice.disposition)
        assertTrue(byChoice.reason.contains("不随包"))
    }

    @Test fun afterOcrIsUnbundledThePluginBecomesTheSource() {
        val fromPlugin = decide(pluginInstalled = true, hostHasOcr = false, pluginHasOcr = true)
        assertEquals(PluginPresencePolicy.OcrDisposition.COPY_FROM_PLUGIN, fromPlugin.disposition)
        assertTrue(fromPlugin.reason.contains("从插件 APK 取"))

        val noPlugin = decide(pluginInstalled = false, hostHasOcr = false, pluginHasOcr = false)
        assertEquals(PluginPresencePolicy.OcrDisposition.DROP_AND_STUB, noPlugin.disposition)

        val emptyShell = decide(pluginInstalled = true, hostHasOcr = false, pluginHasOcr = false)
        assertEquals("装了空壳插件也不能声称有 OCR", PluginPresencePolicy.OcrDisposition.DROP_AND_STUB, emptyShell.disposition)
    }

    /**
     * task-131 第 1 步的四条真相表（removeOcr=true 这条路上）：
     * 未装 / 装了但空壳 ⇒ DROP_AND_STUB（产物无 OCR + 点名错误）；装了且带载荷 ⇒ COPY_FROM_PLUGIN；
     * 默认（removeOcr=false）⇒ KEEP（逐字不变）。
     */
    @Test fun removeOcrPathTakesOcrFromTheInstalledPluginWhenItHasPayload() {
        assertEquals("未装插件 ⇒ 保持现状（无 OCR）",
            PluginPresencePolicy.OcrDisposition.DROP_AND_STUB,
            decide(removeOcr = true, pluginInstalled = false).disposition)
        assertEquals("装了但空壳 ⇒ 不能声称有 OCR",
            PluginPresencePolicy.OcrDisposition.DROP_AND_STUB,
            decide(removeOcr = true, pluginInstalled = true, pluginHasOcr = false).disposition)
        val fromPlugin = decide(removeOcr = true, pluginInstalled = true, pluginHasOcr = true)
        assertEquals("装了且带载荷 ⇒ 从插件取回产物",
            PluginPresencePolicy.OcrDisposition.COPY_FROM_PLUGIN, fromPlugin.disposition)
        assertTrue(fromPlugin.reason, fromPlugin.reason.contains("从插件 APK 取"))
        assertEquals("默认（不剪）⇒ KEEP，逐字不变",
            PluginPresencePolicy.OcrDisposition.KEEP, decide(removeOcr = false).disposition)
    }

        /**
     * task-131 第 2 步：新默认形态 = 主程序不内建 OCR。
     * 未装插件 ⇒ 产物无 OCR（点名错误，不崩）；装了 ⇒ 从插件取回。
     * 旧形态（hostHasOcr=true）仍可用作回滚路径，故也钉一行。
     */
    @Test fun hostWithoutOcrIsTheNewDefault() {
        assertEquals("新默认（不装插件）⇒ 产物无 OCR，运行期点名错误",
            PluginPresencePolicy.OcrDisposition.DROP_AND_STUB,
            decide(removeOcr = false, pluginInstalled = false, hostHasOcr = false).disposition)
        assertEquals("新默认（装插件）⇒ 从插件取回",
            PluginPresencePolicy.OcrDisposition.COPY_FROM_PLUGIN,
            decide(removeOcr = false, pluginInstalled = true, hostHasOcr = false, pluginHasOcr = true).disposition)
        assertEquals("回滚路径仍成立：主程序内建 ⇒ KEEP",
            PluginPresencePolicy.OcrDisposition.KEEP,
            decide(removeOcr = false, pluginInstalled = false, hostHasOcr = true).disposition)
        val builder = File("src/main/java/com/jdkshen/aijspro/autojs/build/ApkBuilder.java").readText()
        assertTrue("第 2 步的常量必须是 false（主程序不内建 OCR）",
            builder.contains("HOST_BUNDLES_OCR = false"))
        assertTrue("判定必须同时覆盖「removeOcr」与「主程序不内建」两种触发",
            builder.contains("if (!removeOcr && hostBundlesOcr)"))
    }

    @Test fun withAppChoicePrefersHostThenPluginThenGivesUp() {
        val fromHost = decide(hostHasOcr = true, choice = PluginPresencePolicy.BundleOcrChoice.WITH_APP)
        assertEquals(PluginPresencePolicy.OcrDisposition.KEEP, fromHost.disposition)
        val fromPlugin = decide(pluginInstalled = true, hostHasOcr = false, pluginHasOcr = true,
            choice = PluginPresencePolicy.BundleOcrChoice.WITH_APP)
        assertEquals(PluginPresencePolicy.OcrDisposition.COPY_FROM_PLUGIN, fromPlugin.disposition)
        val nothing = decide(hostHasOcr = false,
            choice = PluginPresencePolicy.BundleOcrChoice.WITH_APP)
        assertEquals(PluginPresencePolicy.OcrDisposition.DROP_AND_STUB, nothing.disposition)
    }
}