package com.stardust.autojs.runtime.api;

import com.stardust.autojs.runtime.api.remote.RemoteAccessibilityEvent;
import com.stardust.automator.AccessibilityEventWrapper;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * task-64：`:script` 本地无障碍事件直订的回归（字段同源 + 优先级不撞 + 接线位置）。
 *
 * <p>背景（K40 + 设备4 真机实测）：`events.on('accessibility_event')` 恒 0 事件 —— ① 主进程 backend 的 delegate
 * 注册在没有服务实例的主进程，永不触发；② MCP/:script 直跑时 `RemoteAutomationBridge.isActive()` 为 false。
 * 修法：服务实例在本进程时改**本地直订**（`Events.ensureLocalAccessibilityEvents`）。真机行为由 Lead 跑脚本验；
 * 这里钉住 JVM 能钉的部分。
 */
public class AccessibilityEventLocalSubscriptionTest {

    private static final String EVENTS_SOURCE = "src/main/java/com/stardust/autojs/runtime/api/Events.java";
    private static final String FORWARDER_SOURCE =
            "../automator/src/main/java/com/stardust/view/accessibility/AccessibilityEventForwarder.kt";
    private static final String BACKEND_SOURCE =
            "src/main/java/com/jdkshen/aijspro/automation/AutomationHostBackend.java";

    /**
     * 本地通路与远程通路必须是**同一套形状**：本地 delegate 产出 wrapper.toJson() → 远端解析器 fromJson，
     * 逐字段相等（否则脚本看到的字段会随进程而变）。
     */
    @Test
    public void localSnapshotRoundTripsThroughTheRemoteParserFieldForField() {
        String[] kinds = {
                "accessibility_event", "view_clicked", "view_long_clicked", "windows_changed",
                "window_state_changed", "window_content_changed", "notification", "toast",
        };
        int[] eventTypes = { 0x00400000, 1, 2, 0x00400000, 0x00000020, 0x00000800, 64, 64 };
        for (int i = 0; i < kinds.length; i++) {
            AccessibilityEventWrapper wrapper = new AccessibilityEventWrapper(
                    kinds[i], eventTypes[i], "com.example.pkg", "com.example.Cls",
                    "hello 世界", 7, 123456789L, 3, 5);
            String json = wrapper.toJson();
            RemoteAccessibilityEvent event = RemoteAccessibilityEvent.fromJson(json);
            assertTrue(kinds[i] + "：本地 JSON 必须能被远端解析器解析", event != null);
            assertEquals(kinds[i], event.getKind());
            assertEquals(eventTypes[i], event.getEventType());
            assertEquals("com.example.pkg", event.getPackageName());
            assertEquals("com.example.Cls", event.getClassName());
            assertEquals("hello 世界", event.getText());
            assertEquals(7, event.getWindowId());
            assertEquals(123456789L, event.getEventTime());
            if ("window_content_changed".equals(kinds[i])) {
                assertEquals(3, event.getContentChangeTypes());
            }
            if ("window_state_changed".equals(kinds[i])) {
                assertEquals(5, event.getWindowChangeTypes());
            }
            // 脚本侧 toJson() 给的是 org.json 重新序列化的结果（键序不保证）→ 只断言「再解析回去字段相同」。
            RemoteAccessibilityEvent reparsed = RemoteAccessibilityEvent.fromJson(event.toJson());
            assertTrue(kinds[i] + "：脚本侧 toJson 必须还能解析", reparsed != null);
            assertEquals(kinds[i], reparsed.getKind());
            assertEquals(eventTypes[i], reparsed.getEventType());
            assertEquals(7, reparsed.getWindowId());
            assertEquals(123456789L, reparsed.getEventTime());
            assertEquals("hello 世界", reparsed.getText());
        }
    }

    /** kind 判定也要与 JSON 里的 kind 一致（本地通路用它做「有订阅者才建 JSON」的早退判断）。 */
    @Test
    public void kindOfMatchesTheKindsTheLocalPathGuardsOn() {
        assertEquals("view_clicked", AccessibilityEventWrapper.kindOf(1, "android.widget.Button"));
        assertEquals("view_long_clicked", AccessibilityEventWrapper.kindOf(2, "android.widget.Button"));
        assertEquals("toast", AccessibilityEventWrapper.kindOf(64, "android.widget.Toast"));
        assertEquals("notification", AccessibilityEventWrapper.kindOf(64, "android.app.Notification"));
        assertEquals("window_state_changed", AccessibilityEventWrapper.kindOf(0x00000020, "android.widget.FrameLayout"));
        assertEquals("windows_changed", AccessibilityEventWrapper.kindOf(0x00400000, "com.example.Cls"));
        // 未映射的类型落到默认 kind（本地通路也用这条判定做早退）
        assertEquals("accessibility_event", AccessibilityEventWrapper.kindOf(0x00001000, "com.example.Cls"));
    }

    /** 三条通路的 delegate 优先级必须错开（同优先级会互相顶替，事件就断了一条）。 */
    @Test
    public void localDelegatePriorityDoesNotCollideWithTheOtherTwoPaths() throws Exception {
        int forwarderPriority = intConstant(read(FORWARDER_SOURCE), "PRIORITY\\s*=\\s*(\\d+)");
        int backendPriority = intConstant(read(BACKEND_SOURCE), "EVENT_DELEGATE_PRIORITY\\s*=\\s*(\\d+)");
        int localPriority = Events.LOCAL_ACCESSIBILITY_DELEGATE_PRIORITY;
        assertEquals(9101, forwarderPriority);
        assertEquals(9200, backendPriority);
        assertNotEquals(forwarderPriority, localPriority);
        assertNotEquals(backendPriority, localPriority);
        assertTrue("本地优先级应当是正数", localPriority > 0);
    }

    /** 接线位置：本地优先、本地不可用才退回远程；recycle 时摘掉本地订阅。 */
    @Test
    public void localSubscriptionIsPreferredAndReleasedOnRecycle() throws Exception {
        String source = read(EVENTS_SOURCE);
        int methodStart = source.indexOf("private void maybeSubscribeRemoteAccessibility");
        assertTrue("找不到 maybeSubscribeRemoteAccessibility", methodStart > 0);
        int localCall = source.indexOf("ensureLocalAccessibilityEvents()", methodStart);
        int remoteCall = source.indexOf("ensureRemoteAccessibilityEvents();", methodStart);
        assertTrue("必须先尝试本地直订", localCall > methodStart);
        assertTrue("本地不可用时才退回远程", remoteCall > localCall);
        assertTrue("本地判定必须看「服务实例是否在本进程」",
                source.contains("AccessibilityService.Companion.getInstance() == null"));
        assertTrue("本地订阅要真注册 delegate",
                source.contains("LOCAL_ACCESSIBILITY_DELEGATE_PRIORITY, delegate"));
        assertTrue("recycle 要摘掉本地订阅（noop 顶替）",
                source.contains("LOCAL_ACCESSIBILITY_DELEGATE_PRIORITY, LOCAL_ACCESSIBILITY_NOOP_DELEGATE"));
    }

    private static int intConstant(String source, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(source);
        assertTrue("源码里找不到常量：" + regex, matcher.find());
        return Integer.parseInt(matcher.group(1));
    }

    private static String read(String relativePath) throws Exception {
        return new String(Files.readAllBytes(Paths.get(relativePath)), StandardCharsets.UTF_8);
    }
}