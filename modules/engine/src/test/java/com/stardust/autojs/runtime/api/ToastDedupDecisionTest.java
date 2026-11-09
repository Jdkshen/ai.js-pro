package com.stardust.autojs.runtime.api;

import com.stardust.view.accessibility.AccessibilityNotificationObserver;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * task-69：`toast`/`notification` 的「避重」判据（本地 kind 通道 vs 专用观察者）。
 *
 * <p>真机现象：脚本自己的 `toast()`（**同包**事件）在观察者侧被**有意跳过**，而 task-64 的避重只看
 * 「观察者已挂」→ kind 通道也被跳过 ⇒ **事件静默丢失**（`events.on('toast')` 与 `onToast()` 都 0）。
 * 修法：避重必须用观察者**同一条**投递判据（{@code willDeliver}）。
 */
public class ToastDedupDecisionTest {

    private static final String OBSERVER_SOURCE =
            "../automator/src/main/java/com/stardust/view/accessibility/AccessibilityNotificationObserver.kt";
    private static final String EVENTS_SOURCE = "src/main/java/com/stardust/autojs/runtime/api/Events.java";

    /** 观察者的投递判据：**同包（自身）事件有意跳过**；拿不到包名时照投。 */
    @Test
    public void observerDeliversEverythingExceptItsOwnPackage() {
        assertTrue(AccessibilityNotificationObserver.willDeliver("com.other.app", "com.jdkshen.aijspro"));
        assertFalse("同包事件有意跳过（脚本自己的 toast 就是这种）",
                AccessibilityNotificationObserver.willDeliver("com.jdkshen.aijspro", "com.jdkshen.aijspro"));
        assertTrue("事件没有包名时照投", AccessibilityNotificationObserver.willDeliver(null, "com.jdkshen.aijspro"));
        assertTrue("拿不到自身包名时不拦", AccessibilityNotificationObserver.willDeliver("com.x", null));
    }

    /** 避重只在「观察者真的会投递」时成立：同包 toast 必须让 kind 通道兜住（否则静默丢失）。 */
    @Test
    public void kindChannelIsOnlySkippedWhenTheObserverReallyDelivers() {
        assertFalse("task-69 的 bug：观察者已挂但同包不投递 → 不能跳过 kind 通道",
                Events.shouldSkipKindForDedicatedObserver(true, false, "toast"));
        assertTrue("观察者会投递 → 让路，避免一次事件两次回调",
                Events.shouldSkipKindForDedicatedObserver(true, true, "toast"));
        assertTrue(Events.shouldSkipKindForDedicatedObserver(true, true, "notification"));
        assertFalse("没有专用观察者的 kind（view_clicked 等）永不让路",
                Events.shouldSkipKindForDedicatedObserver(true, true, "view_clicked"));
        assertFalse("观察者没挂 → 不让路", Events.shouldSkipKindForDedicatedObserver(false, true, "toast"));
        assertFalse("kind 未知 → 不让路", Events.shouldSkipKindForDedicatedObserver(true, true, null));
    }

    /** 同源：两侧必须用同一条判据（观察者用它早退、引擎用它决定避重）。 */
    @Test
    public void bothSidesShareTheSameDeliveryRule() throws Exception {
        String observer = read(OBSERVER_SOURCE);
        assertTrue("观察者必须用 willDeliver 早退", observer.contains("if (!willDeliver(event.packageName, mContext.packageName))"));
        assertTrue("观察者必须定义 willDeliver", observer.contains("fun willDeliver(eventPackageName: CharSequence?, ownPackageName: String?)"));
        String events = read(EVENTS_SOURCE);
        assertTrue("引擎的避重必须调用同一条判据", events.contains("AccessibilityNotificationObserver.Companion"));
        assertTrue("引擎的避重必须用 willDeliver", events.contains(".willDeliver(event.getPackageName(),"));
    }

    private static String read(String relativePath) throws Exception {
        return new String(Files.readAllBytes(Paths.get(relativePath)), StandardCharsets.UTF_8);
    }
}