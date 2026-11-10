package com.jdkshen.aijspro.autojs.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
