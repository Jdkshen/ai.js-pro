package com.jdkshen.aijspro.sample;

import android.app.Instrumentation;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.test.platform.app.InstrumentationRegistry;

import com.stardust.autojs.core.ui.AccessibilityClickSupport;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class AccessibilityClickSupportTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

    private View newView() {
        View[] result = new View[1];
        instrumentation.runOnMainSync(() -> result[0] = new View(instrumentation.getTargetContext()));
        return result[0];
    }

    @Test public void nativeClickAndAccessibilityActionsUseTheirOwnListeners() {
        View view = newView();
        AtomicInteger clicks = new AtomicInteger();
        AccessibilityClickSupport support = new AccessibilityClickSupport();
        try {
            instrumentation.runOnMainSync(() -> view.setOnClickListener(v -> clicks.incrementAndGet()));
            support.replaceAccessibilityAction(view, 16, "选择", 11);
            support.replaceAccessibilityAction(view, 32, "全选", 12);
            boolean[] labels = new boolean[2], handled = new boolean[2];
            instrumentation.runOnMainSync(() -> {
                AccessibilityNodeInfo info = view.createAccessibilityNodeInfo();
                try {
                    labels[0] = info.getActionList().stream().anyMatch(action ->
                            action.getId() == 16 && "选择".contentEquals(action.getLabel()));
                    labels[1] = info.getActionList().stream().anyMatch(action ->
                            action.getId() == 32 && "全选".contentEquals(action.getLabel()));
                } finally { info.recycle(); }
                handled[0] = view.performAccessibilityAction(16, null);
                handled[1] = view.performAccessibilityAction(32, null);
            });
            assertTrue(labels[0]); assertTrue(labels[1]);
            assertTrue(handled[0]); assertTrue(handled[1]);
            assertEquals(11, support.poll());
            assertEquals(12, support.poll());
            assertEquals(0, clicks.get());
            assertTrue(support.performClick(view));
            assertEquals(1, clicks.get());
            assertEquals(0, support.poll());

            support.replaceAccessibilityAction(view, 16, null, 0);
            instrumentation.runOnMainSync(() -> handled[0] = view.performAccessibilityAction(16, null));
            assertTrue(handled[0]);
            assertEquals(2, clicks.get());
            assertEquals(0, support.poll());
        } finally { support.close(); }
    }

    @Test public void replacingAnActionAndClosingAnOlderSessionKeepsTheNewSession() {
        View view = newView();
        AccessibilityClickSupport older = new AccessibilityClickSupport();
        AccessibilityClickSupport newer = new AccessibilityClickSupport();
        AtomicInteger nativeClicks = new AtomicInteger();
        boolean[] handled = new boolean[1];
        try {
            instrumentation.runOnMainSync(() -> view.setOnClickListener(v -> nativeClicks.incrementAndGet()));
            older.replaceAccessibilityAction(view, 16, "旧动作", 21);
            older.replaceAccessibilityAction(view, 16, "替换动作", 22);
            instrumentation.runOnMainSync(() -> handled[0] = view.performAccessibilityAction(16, null));
            assertTrue(handled[0]);
            assertEquals(22, older.poll());
            assertEquals(0, older.poll());
            newer.replaceAccessibilityAction(view, 16, "新动作", 31);
            older.close();
            instrumentation.runOnMainSync(() -> handled[0] = view.performAccessibilityAction(16, null));
            assertTrue(handled[0]);
            assertEquals(31, newer.poll());
            assertEquals(0, older.poll());
            assertEquals(0, nativeClicks.get());
            newer.close();
            instrumentation.runOnMainSync(() -> view.performAccessibilityAction(16, null));
            assertEquals(1, nativeClicks.get());
            assertEquals(0, newer.poll());
        } finally { older.close(); newer.close(); }
    }

    @Test public void clickWithoutAListenerReturnsFalseAndInvalidActionsAreRejected() {
        View view = newView();
        AccessibilityClickSupport support = new AccessibilityClickSupport();
        try {
            assertFalse(support.performClick(view));
            try {
                support.replaceAccessibilityAction(view, 64, "无效", 1);
                fail("Only click and long click are supported");
            } catch (IllegalArgumentException expected) { }
        } finally { support.close(); }
    }

    @Test public void closingFromAnInterruptedScriptThreadStillRemovesTheAction() throws Exception {
        View view = newView();
        AtomicInteger nativeClicks = new AtomicInteger();
        AccessibilityClickSupport support = new AccessibilityClickSupport();
        instrumentation.runOnMainSync(() -> view.setOnClickListener(v -> nativeClicks.incrementAndGet()));
        support.replaceAccessibilityAction(view, 16, "脚本动作", 41);
        Thread stoppedScript = new Thread(() -> {
            Thread.currentThread().interrupt();
            support.close();
        });
        stoppedScript.start();
        stoppedScript.join(5000);
        assertFalse(stoppedScript.isAlive());
        instrumentation.runOnMainSync(() -> view.performAccessibilityAction(16, null));
        assertEquals(1, nativeClicks.get());
        assertEquals(0, support.poll());
    }
}
