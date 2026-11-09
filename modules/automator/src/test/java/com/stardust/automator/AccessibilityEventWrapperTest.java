package com.stardust.automator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.view.accessibility.AccessibilityEvent;

import org.junit.Test;

/**
 * task-60（P0-2）：脚本可见的无障碍事件 JSON 快照映射（唯一实现）。
 * 这些断言把「脚本实际收到的键/值」钉住：改造前后必须逐键相同（只是实现从内联 JSONObject 变成可测的包装类）。
 */
public class AccessibilityEventWrapperTest {

    @Test public void jsonContainsTheSameKeysAsBeforeAndInTheSameOrder() {
        AccessibilityEventWrapper wrapper = new AccessibilityEventWrapper(
                AccessibilityEventWrapper.KIND_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                "com.demo.app", "com.demo.app.MainActivity", "hello world", 7, 1700000000123L,
                0, 0x00000002);
        assertEquals("{\"kind\":\"window_state_changed\",\"eventType\":32,\"packageName\":\"com.demo.app\","
                + "\"className\":\"com.demo.app.MainActivity\",\"windowId\":7,\"eventTime\":1700000000123,"
                + "\"text\":\"hello world\",\"windowChangeTypes\":2}", wrapper.toJson());
    }

    @Test public void conditionalKeysOnlyAppearForTheirEventKinds() {
        AccessibilityEventWrapper contentChanged = new AccessibilityEventWrapper(
                AccessibilityEventWrapper.KIND_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                "pkg", "cls", "t", 1, 2L, 0x00000004, 0x00000008);
        String contentJson = contentChanged.toJson();
        assertTrue(contentJson.contains("\"contentChangeTypes\":4"));
        assertTrue("content 事件不该带 windowChangeTypes", !contentJson.contains("windowChangeTypes"));

        AccessibilityEventWrapper clicked = new AccessibilityEventWrapper(
                AccessibilityEventWrapper.KIND_VIEW_CLICKED, AccessibilityEvent.TYPE_VIEW_CLICKED,
                "pkg", "cls", "t", 1, 2L, 4, 8);
        String clickJson = clicked.toJson();
        assertTrue(!clickJson.contains("contentChangeTypes") && !clickJson.contains("windowChangeTypes"));
    }

    @Test public void stringsAreEscapedSoTheSnapshotStaysValidJson() {
        AccessibilityEventWrapper wrapper = new AccessibilityEventWrapper(
                AccessibilityEventWrapper.KIND_ACCESSIBILITY_EVENT, 0,
                "pkg\"quote", "cls\\slash", "line1\nline2\ttab", 0, 0L);
        assertEquals("pkg\\\"quote", AccessibilityEventWrapper.escape("pkg\"quote"));
        assertEquals("cls\\\\slash", AccessibilityEventWrapper.escape("cls\\slash"));
        assertTrue(wrapper.toJson().contains("\"text\":\"line1\\nline2\\ttab\""));
        assertEquals("\\u0001", AccessibilityEventWrapper.escape("\u0001"));
    }

    @Test public void kindOfMatchesTheFrameworkEventTypesAndKeepsTheToastSpecialCase() {
        // 与 android.view.accessibility.AccessibilityEvent 的常量对齐（防止包装类里写死的字面量漂移）。
        // 值来自 AOSP（javap -constants android-35/android.jar 复核）：
        // VIEW_CLICKED=1、VIEW_LONG_CLICKED=2、WINDOW_STATE_CHANGED=32、NOTIFICATION_STATE_CHANGED=64、
        // WINDOW_CONTENT_CHANGED=2048、VIEW_SCROLLED=4096、WINDOWS_CHANGED=4194304。
        // 这条用例在本轮真的抓到过一个错：包装类里把 64 写成了 0x00100000（notification/toast 会被误判成 accessibility_event）。
        assertEquals(AccessibilityEventWrapper.KIND_VIEW_CLICKED,
                AccessibilityEventWrapper.kindOf(AccessibilityEvent.TYPE_VIEW_CLICKED, ""));
        assertEquals(AccessibilityEventWrapper.KIND_VIEW_LONG_CLICKED,
                AccessibilityEventWrapper.kindOf(AccessibilityEvent.TYPE_VIEW_LONG_CLICKED, ""));
        assertEquals(AccessibilityEventWrapper.KIND_WINDOWS_CHANGED,
                AccessibilityEventWrapper.kindOf(AccessibilityEvent.TYPE_WINDOWS_CHANGED, ""));
        assertEquals(AccessibilityEventWrapper.KIND_WINDOW_STATE_CHANGED,
                AccessibilityEventWrapper.kindOf(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, ""));
        assertEquals(AccessibilityEventWrapper.KIND_WINDOW_CONTENT_CHANGED,
                AccessibilityEventWrapper.kindOf(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, ""));
        assertEquals(AccessibilityEventWrapper.KIND_NOTIFICATION,
                AccessibilityEventWrapper.kindOf(AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED, "com.android.systemui"));
        assertEquals(AccessibilityEventWrapper.KIND_TOAST,
                AccessibilityEventWrapper.kindOf(AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED, "android.widget.Toast$TN"));
        assertEquals(AccessibilityEventWrapper.KIND_ACCESSIBILITY_EVENT,
                AccessibilityEventWrapper.kindOf(AccessibilityEvent.TYPE_VIEW_SCROLLED, ""));
    }

    @Test public void toStringIsReadableForScriptsAndLogcat() {
        AccessibilityEventWrapper wrapper = new AccessibilityEventWrapper(
                AccessibilityEventWrapper.KIND_TOAST, 64, "com.demo", "android.widget.Toast$TN", "hi", 3, 99L);
        String text = wrapper.toString();
        assertTrue(text, text.contains("toast") && text.contains("pkg=com.demo") && text.contains("text=hi"));
    }
}
