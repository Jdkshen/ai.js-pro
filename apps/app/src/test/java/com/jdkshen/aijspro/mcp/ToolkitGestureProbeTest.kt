package com.jdkshen.aijspro.mcp

import com.stardust.automator.GestureDispatchProbe
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * task-43：手势回调表泄漏探针的纯逻辑测试（反射降级、计数、压测 before/after、调用点覆盖）。
 * 不需要真机/无障碍服务：反射用带同名隐藏字段的假类，派发用注入的 lambda。
 */
class ToolkitGestureProbeTest {
    @get:Rule val temp = TemporaryFolder()

    /** 假的服务对象：字段名与框架隐藏字段一致，用来验证反射读取路径本身。 */
    private class FakeService {
        @JvmField val mGestureStatusCallbackInfos = ArrayList<String>()
        @JvmField val mGestureStatusCallbackSequence = AtomicLong(7L)
    }

    /** ① 读不到字段 → supported=false 且不抛；字段在 → 真读到 size/sequence。 */
    @Test fun unsupportedWithoutHiddenFieldsAndReadsThemWhenPresent() {
        val missing = ToolkitGestureProbe.snapshot(Object())
        assertFalse(missing.supported)
        assertNull(missing.size)
        assertNull(missing.sequence)
        assertTrue(missing.reason ?: "", (missing.reason ?: "").contains("找不到隐藏字段"))

        val noInstance = ToolkitGestureProbe.snapshot(null)
        assertFalse(noInstance.supported)
        assertTrue(noInstance.reason ?: "", (noInstance.reason ?: "").contains("没有 AccessibilityService 实例"))

        val fake = FakeService().apply { repeat(3) { mGestureStatusCallbackInfos.add("cb") } }
        val read = ToolkitGestureProbe.snapshot(fake)
        assertTrue(read.reason ?: "", read.supported)
        assertEquals(3, read.size)
        assertEquals(7L, read.sequence)
        assertEquals(FakeService::class.java.name, read.instanceClass)
        assertTrue(read.probedFields.contains("mGestureStatusCallbackInfos"))
    }

    /** ② 三次派发 → 计数 +3，并按站点/是否带回调分开。 */
    @Test fun countersCountThreeDispatchesAndSplitBySite() {
        val dir = temp.newFolder("probe-counters")
        assertTrue(GestureDispatchProbe.init(dir))
        GestureDispatchProbe.reset()
        GestureDispatchProbe.record("GlobalActionAutomator", true)
        GestureDispatchProbe.record("GlobalActionAutomator", true)
        GestureDispatchProbe.record("ScreenCaptureAutoConsent", false)
        val snapshot = GestureDispatchProbe.snapshot()
        assertEquals(3L, snapshot.dispatched)
        assertEquals(2L, snapshot.withCallback)
        assertEquals(1L, snapshot.withoutCallback)
        assertEquals(2L, snapshot.sites.getValue("GlobalActionAutomator").getValue("withCallback"))
        assertEquals(1L, snapshot.sites.getValue("ScreenCaptureAutoConsent").getValue("withoutCallback"))
        assertTrue(snapshot.files.isNotEmpty())
        // reset 同时清内存与文件
        assertTrue(GestureDispatchProbe.reset() >= 1)
        assertEquals(0L, GestureDispatchProbe.snapshot().dispatched)
    }

    /** ③ stress{count:50} 返回 before/after 且 dispatched==50；超时/失败也如实计数。 */
    @Test fun stressDispatchesRequestedCountAndReportsBeforeAfter() {
        val dir = temp.newFolder("probe-stress")
        assertTrue(GestureDispatchProbe.init(dir))
        GestureDispatchProbe.reset()
        var dispatches = 0
        val outcome = ToolkitGestureProbe.stress(
            count = 50, timeoutSeconds = 5, withCallback = true, intervalMs = 0,
            probe = { ToolkitGestureProbe.snapshot(Object()) },
            dispatch = { withCb -> dispatches++; GestureDispatchProbe.record("dev_gesture_probe", withCb); true },
            now = { 1000L },
            sleep = { },
        )
        assertEquals(50, outcome.dispatched)
        assertEquals(0, outcome.failed)
        assertEquals(50, dispatches)
        assertEquals("completed", outcome.stoppedBy)
        assertFalse(outcome.before.supported)
        assertFalse(outcome.after.supported)
        assertEquals(50L, GestureDispatchProbe.snapshot().dispatched)

        val failing = ToolkitGestureProbe.stress(
            count = 4, timeoutSeconds = 5, withCallback = false, intervalMs = 0,
            probe = { ToolkitGestureProbe.snapshot(null) },
            dispatch = { false },
            now = { 0L }, sleep = { },
        )
        assertEquals(0, failing.dispatched)
        assertEquals(4, failing.failed)
        assertEquals("completed", failing.stoppedBy)

        val clock = AtomicLong(0L)
        val timedOut = ToolkitGestureProbe.stress(
            count = 100, timeoutSeconds = 1, withCallback = false, intervalMs = 0,
            probe = { ToolkitGestureProbe.snapshot(null) },
            dispatch = { clock.addAndGet(400L); true },
            now = { clock.get() }, sleep = { },
        )
        assertEquals("timeout", timedOut.stoppedBy)
        assertTrue("应在超时处停下而不是跑满：" + timedOut.dispatched, timedOut.dispatched < 100)
    }

    /** ④ 未 init 时是 no-op（不改行为、不抛）；三处调用点确实都被 instrument。 */
    @Test fun probeIsNoOpWithoutInitAndAllThreeCallSitesAreInstrumented() {
        GestureDispatchProbe.init(null)
        GestureDispatchProbe.record("GlobalActionAutomator", true)
        GestureDispatchProbe.record("AutomationHostBackend", false)
        val snapshot = GestureDispatchProbe.snapshot()
        assertFalse("未 init 时不能声称有计数", snapshot.initialized)
        assertEquals(0L, snapshot.dispatched)

        val root = File("../..")
        val automator = File(root, "modules/automator/src/main/java/com/stardust/automator/GlobalActionAutomator.kt").readText()
        assertEquals("GlobalActionAutomator 的带回调派发都必须计数",
            2, Regex("GestureDispatchProbe\\.record\\(GESTURE_PROBE_SITE, true\\)").findAll(automator).count())
        assertEquals("GlobalActionAutomator 的无回调派发必须计数",
            1, Regex("GestureDispatchProbe\\.record\\(GESTURE_PROBE_SITE, false\\)").findAll(automator).count())
        val backend = File(root, "modules/engine/src/main/java/com/jdkshen/aijspro/automation/AutomationHostBackend.java").readText()
        assertEquals(1, Regex("GestureDispatchProbe.INSTANCE.record\\(GESTURE_PROBE_SITE, true\\)").findAll(backend).count())
        assertEquals(1, Regex("GestureDispatchProbe.INSTANCE.record\\(GESTURE_PROBE_SITE, false\\)").findAll(backend).count())
        val consent = File(root, "modules/engine/src/main/java/com/stardust/autojs/core/image/capture/ScreenCaptureAutoConsent.java").readText()
        assertEquals(1, Regex("GestureDispatchProbe.INSTANCE.record\\(\"ScreenCaptureAutoConsent\", false\\)").findAll(consent).count())
    }
}
