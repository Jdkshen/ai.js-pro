package com.jdkshen.aijspro.tool

import com.stardust.view.accessibility.AccessibilityServiceUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 无障碍「检查是否已启用」结果对象 API 的纯 JVM 单测（Pro 对齐 ②）。
 *
 * 覆盖：已启用 → Success（含「设置读不到但实例已连上」）；未启用 → not_enabled + 可抄 nextActions；
 * 超时 → timeout（**用 seam 假时钟，不 sleep 2s**）；非阻塞 checkNow 的诚实三态；
 * 组件名与启用链路同源（常量 + 源码文本断言）；纯函数解析 settings 原文。
 */
class AccessibilityCheckTest {

    private val component = "com.jdkshen.aijspro/" + AccessibilityCheck.SERVICE_CLASS_NAME

    private class FakeProbe(
        private val setting: String? = null,
        private val readFailed: Boolean = false,
        private val readError: String? = null,
        connected: Boolean = false,
        private val connectAfterAwait: Boolean = false,
        private val advancePerAwaitMs: Long = 0L,
        private val automationReady: Boolean = false,
    ) : AccessibilityCheck.Probe {
        var awaitCalls = 0
            private set
        var lastAwaitTimeoutMs = -1L
            private set
        private var now = 0L
        private var connectedNow = connected

        override fun componentName(): String = "com.jdkshen.aijspro/" + AccessibilityCheck.SERVICE_CLASS_NAME
        override fun enabledServicesSetting(): AccessibilityCheck.SettingsRead =
            if (readFailed) AccessibilityCheck.SettingsRead.failed(readError ?: "java.lang.SecurityException")
            else AccessibilityCheck.SettingsRead.read(setting)
        override fun isServiceConnected(): Boolean = connectedNow
        override fun awaitServiceConnected(timeoutMs: Long): Boolean {
            awaitCalls++
            lastAwaitTimeoutMs = timeoutMs
            now += advancePerAwaitMs
            if (connectAfterAwait) {
                connectedNow = true
                return true
            }
            return false
        }
        override fun uptimeMillis(): Long = now
        override fun automationConnectionReady(): Boolean = automationReady
    }

    // ------------------------------------------------------------------ ① 已启用

    @Test fun connectedInstanceIsSuccessEvenWhenSettingsAreEmptyOrUnreadable() {
        val empty = FakeProbe(setting = null, connected = true)
        assertEquals(AccessibilityCheck.AccessibilityCheckResult.Success, AccessibilityCheck.checkBlocking(empty, 2000))
        assertEquals(AccessibilityCheck.AccessibilityCheckResult.Success, AccessibilityCheck.checkNow(empty))
        assertEquals("实例已连上就不该再去等待", 0, empty.awaitCalls)
        val unreadable = FakeProbe(readFailed = true, connected = true)
        assertEquals(AccessibilityCheck.AccessibilityCheckResult.Success, AccessibilityCheck.checkBlocking(unreadable, 2000))
        assertEquals(AccessibilityCheck.AccessibilityCheckResult.Success, AccessibilityCheck.checkNow(unreadable))
    }

    @Test fun serviceListedInSettingsWaitsAndThenSucceeds() {
        val probe = FakeProbe(setting = component, connected = false, connectAfterAwait = true)
        assertEquals(AccessibilityCheck.AccessibilityCheckResult.Success, AccessibilityCheck.checkBlocking(probe, 2000))
        assertEquals(1, probe.awaitCalls)
        assertEquals("等待预算应等于剩余超时", 2000L, probe.lastAwaitTimeoutMs)
    }

    /**
     * 进程边界：我们的服务在 :script，主进程里 AccessibilityService.instance 恒为 null。
     * 此时「设置在 + 自动化通路已就绪」（AutomationConnectionState.isReady）必须判 Success，
     * 而不是让主进程调用方看到 not_connected。
     */
    @Test fun mainProcessSeesSuccessThroughAutomationConnectionWhenInstanceIsNull() {
        val probe = FakeProbe(setting = component, connected = false, automationReady = true)
        assertEquals(AccessibilityCheck.AccessibilityCheckResult.Success, AccessibilityCheck.checkBlocking(probe, 2000))
        assertEquals(AccessibilityCheck.AccessibilityCheckResult.Success, AccessibilityCheck.checkNow(probe))
        assertEquals("已就绪就不该再等", 0, probe.awaitCalls)
    }

    // ------------------------------------------------------------------ ② 未启用

    @Test fun notEnabledFailsFastWithCopyableNextActions() {
        val probe = FakeProbe(setting = "com.other.app/com.other.app.Svc")
        val result = AccessibilityCheck.checkBlocking(probe, 2000)
        val failure = result as AccessibilityCheck.AccessibilityCheckResult.Failure
        assertEquals(AccessibilityCheck.REASON_NOT_ENABLED, failure.reason)
        assertEquals(false, failure.settingsContainsComponent)
        assertFalse(failure.connected)
        assertEquals("设置里没有就不该白等 2s", 0, probe.awaitCalls)
        assertTrue("hint 必须点名组件", failure.hint.contains(component))
        assertTrue("nextActions 必须可抄", failure.nextActions.isNotEmpty())
        assertTrue(failure.nextActions.any { it.contains("AccessibilityServiceTool.enableAccessibilityService()") })
        assertTrue(failure.nextActions.any { it.contains("AccessibilityServiceTool.goToAccessibilitySetting()") })
        assertTrue(AccessibilityCheck.summary(failure).contains("reason=not_enabled"))
    }

    // ------------------------------------------------------------------ ③ 超时（假时钟，不 sleep）

    @Test fun timeoutIsReportedInsteadOfFakeSuccessUsingSeamClock() {
        // await 内部把假时钟推进 2000ms 并返回 false：真实实现里就是 waitForEnabled 等满后没连上。
        val probe = FakeProbe(setting = component, advancePerAwaitMs = 2000L)
        val wallStart = System.nanoTime()
        val result = AccessibilityCheck.checkBlocking(probe, 2000)
        val wallMs = (System.nanoTime() - wallStart) / 1_000_000L
        assertNotEquals(AccessibilityCheck.AccessibilityCheckResult.Success, result)
        val failure = result as AccessibilityCheck.AccessibilityCheckResult.Failure
        assertEquals(AccessibilityCheck.REASON_TIMEOUT, failure.reason)
        assertEquals(2000L, failure.elapsedMs)
        assertEquals(2000L, probe.lastAwaitTimeoutMs)
        assertTrue("hint 必须写清等待了多少", failure.hint.contains("2000"))
        assertTrue("超时不能算成功", failure.hint.contains("不算成功"))
        assertTrue("必须用 seam 时钟，不允许真 sleep 2s（实测 ${wallMs}ms）", wallMs < 500L)
        assertTrue(failure.nextActions.any { it.contains("AccessibilityServiceTool.getFastEnableError()") })
    }

    // ------------------------------------------------------------------ 非阻塞形态的诚实三态

    @Test fun checkNowReportsNotConnectedWithoutWaiting() {
        val listed = FakeProbe(setting = component)
        val notConnected = AccessibilityCheck.checkNow(listed) as AccessibilityCheck.AccessibilityCheckResult.Failure
        assertEquals(AccessibilityCheck.REASON_NOT_CONNECTED, notConnected.reason)
        assertEquals(true, notConnected.settingsContainsComponent)
        assertEquals("checkNow 不等待", 0, listed.awaitCalls)
        assertTrue(notConnected.hint.contains("checkBlocking"))
    }

    /**
     * 真机（dev-1438）抓到的分类缺陷回归：服务关闭后 settings 读出来是**空**（不是读异常），
     * 必须判 `not_enabled` + 可抄 nextActions，而不是 `settings_unreadable` + 误导 hint。
     */
    @Test fun emptyOrNullSettingIsNotEnabledWithNextActions() {
        for (empty in listOf<String?>(null, "", "null")) {
            val probe = FakeProbe(setting = empty)
            val now = AccessibilityCheck.checkNow(probe) as AccessibilityCheck.AccessibilityCheckResult.Failure
            assertEquals("setting=" + empty, AccessibilityCheck.REASON_NOT_ENABLED, now.reason)
            assertEquals(false, now.settingsContainsComponent)
            assertEquals("读到空不是读异常", null, now.settingsReadError)
            assertTrue(now.hint.contains("服务未启用"))
            assertTrue(now.nextActions.any { it.contains("AccessibilityServiceTool.enableAccessibilityService()") })
            assertTrue(now.nextActions.any { it.contains("AccessibilityServiceTool.goToAccessibilitySetting()") })
            val blocking = AccessibilityCheck.checkBlocking(probe, 2000) as AccessibilityCheck.AccessibilityCheckResult.Failure
            assertEquals(AccessibilityCheck.REASON_NOT_ENABLED, blocking.reason)
            assertEquals("空设置不该白等 2s", 0, probe.awaitCalls)
        }
    }

    /** 读**抛异常**才是 `settings_unreadable`（保持原语义：读不到不判未启用）。 */
    @Test fun settingsReadFailureIsUnreadableNotNotEnabled() {
        val probe = FakeProbe(readFailed = true, readError = "java.lang.SecurityException")
        val now = AccessibilityCheck.checkNow(probe) as AccessibilityCheck.AccessibilityCheckResult.Failure
        assertEquals("读异常不能判成未启用", AccessibilityCheck.REASON_SETTINGS_UNREADABLE, now.reason)
        assertEquals(null, now.settingsContainsComponent)
        assertEquals("java.lang.SecurityException", now.settingsReadError)
        assertTrue(now.hint.contains("读不到"))
        assertTrue(now.hint.contains("SecurityException"))
        val blocking = AccessibilityCheck.checkBlocking(probe, 2000) as AccessibilityCheck.AccessibilityCheckResult.Failure
        assertEquals(AccessibilityCheck.REASON_SETTINGS_UNREADABLE, blocking.reason)
        assertTrue(AccessibilityCheck.summary(blocking).contains("settingsReadError=java.lang.SecurityException"))
    }

    @Test fun evidenceStringShowsAllThreeSignalsAndSeparatesEmptyFromUnreadable() {
        val listed = FakeProbe(setting = component)
        val text = AccessibilityCheck.evidence(listed)
        assertTrue(text, text.contains("connected=false"))
        assertTrue(text, text.contains("automationConnectionReady=false"))
        assertTrue(text, text.contains("settingsContainsComponent=true"))
        assertTrue(text, text.contains(component))
        assertTrue(text, text.contains("settings=" + component))

        val empty = AccessibilityCheck.evidence(FakeProbe(setting = null))
        assertTrue(empty, empty.contains("settings=(空)"))
        assertTrue(empty, empty.contains("settingsReadFailed=false"))
        assertTrue(empty, empty.contains("settingsValueAbsent=true"))
        assertTrue(empty, empty.contains("settingsContainsComponent=false"))

        val unreadable = AccessibilityCheck.evidence(FakeProbe(readFailed = true, readError = "java.lang.SecurityException"))
        assertTrue(unreadable, unreadable.contains("settings=(读取异常:java.lang.SecurityException)"))
        assertTrue(unreadable, unreadable.contains("settingsReadFailed=true"))
        assertTrue(unreadable, unreadable.contains("settingsContainsComponent=false"))
    }

    @Test fun checkAsyncUsesTheSameBlockingLogicWithInjectedExecutor() {
        val probe = FakeProbe(setting = component, connected = true)
        var seen: AccessibilityCheck.AccessibilityCheckResult? = null
        var ran = false
        AccessibilityCheck.checkAsync(probe, 2000, executor = { task -> ran = true; task.run() }) { result -> seen = result }
        assertTrue("executor 必须被使用（单测里同步执行，不建线程）", ran)
        assertEquals(AccessibilityCheck.AccessibilityCheckResult.Success, seen)
    }

    // ------------------------------------------------------------------ ④ 与启用链路同源 + 纯函数解析

    @Test fun serviceClassNameAndSettingsParsingAreSharedWithTheEnablePath() {
        assertEquals("com.google.android.accessibility.selecttospeak.SelectToSpeakService", AccessibilityCheck.SERVICE_CLASS_NAME)
        assertEquals("com.jdkshen.aijspro/" + AccessibilityCheck.SERVICE_CLASS_NAME,
            AccessibilityCheck.componentName("com.jdkshen.aijspro"))
        assertEquals(2000L, AccessibilityCheck.WAIT_FOR_ENABLED_TIMEOUT)

        // 源码文本断言：启用链路必须走共享组件名，且保留「类名漂移」守卫。
        val source = File("src/main/java/com/jdkshen/aijspro/tool/AccessibilityServiceTool.java")
        assertTrue("找不到 " + source.absolutePath, source.isFile)
        val text = source.readText()
        assertTrue("检查与启用必须共用 AccessibilityCheck.componentName", text.contains("AccessibilityCheck.componentName("))
        assertTrue("必须保留类名漂移守卫", text.contains("AccessibilityCheck.SERVICE_CLASS_NAME.equals(sAccessibilityServiceClass.getName())"))
        assertTrue("启用链路要走共享组件名", text.contains("componentNameFor("))
        assertFalse("不该再手拼 pkg + '/' + sAccessibilityServiceClass.getName()",
            text.contains("\"/\" + sAccessibilityServiceClass.getName()"))

        // 纯函数解析（与 AccessibilityServiceUtils.isAccessibilityServiceEnabled 同一份实现）
        assertTrue(AccessibilityServiceUtils.settingsContainsComponent(component, component))
        assertTrue(AccessibilityServiceUtils.settingsContainsComponent("a/b:$component:c/d", component))
        assertTrue("大小写不敏感", AccessibilityServiceUtils.settingsContainsComponent(
            component.uppercase(), component))
        assertTrue("短组件名 pkg/.Svc 也要命中（与旧 ComponentName 语义一致）",
            AccessibilityServiceUtils.settingsContainsComponent("com.jdkshen.aijspro/.tool.Svc",
                "com.jdkshen.aijspro/com.jdkshen.aijspro.tool.Svc"))
        assertTrue("期望值本身是短形式时也要命中（AccessibilityServiceComponents.enable 依赖这条）",
            AccessibilityServiceUtils.settingsContainsComponent("com.jdkshen.aijspro/com.jdkshen.aijspro.tool.Svc",
                "com.jdkshen.aijspro/.tool.Svc"))
        assertFalse(AccessibilityServiceUtils.settingsContainsComponent(null, component))
        assertFalse(AccessibilityServiceUtils.settingsContainsComponent("", component))
        assertFalse(AccessibilityServiceUtils.settingsContainsComponent("null", component))
        assertFalse(AccessibilityServiceUtils.settingsContainsComponent("com.other/Other", component))
    }
}