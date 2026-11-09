package com.stardust.autojs.core.accessibility;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** task-77：无障碍未连接的**可抄**文案（组件名必须出现，修法必须可照做）。 */
public class AccessibilityServiceErrorsTest {

    private static final String COMPONENT = "com.nuoyapro/com.google.android.accessibility.selecttospeak.SelectToSpeakService";

    @Test
    public void notConnectedMessageCarriesComponentAndCopyableFix() {
        String message = AccessibilityServiceErrors.notConnectedMessage(COMPONENT);
        assertTrue("必须带组件名", message.contains(COMPONENT));
        assertTrue("必须说清现象", message.contains("无障碍服务未连接"));
        assertTrue("必须给可抄修法（关掉再打开）", message.contains("关掉再打开"));
        assertTrue("必须点名 auto()/auto.waitFor()", message.contains("auto()"));
    }

    @Test
    public void unknownComponentNameNeverRendersEmpty() {
        assertTrue(AccessibilityServiceErrors.notConnectedMessage(null).contains("(未知组件名)"));
        assertTrue(AccessibilityServiceErrors.notConnectedMessage("   ").contains("(未知组件名)"));
    }

    @Test
    public void nextActionsAreCopyableSteps() {
        List<String> actions = AccessibilityServiceErrors.nextActions(COMPONENT);
        assertEquals(4, actions.size());
        assertTrue(actions.get(1).contains("无障碍"));
        assertTrue("第 3 条给可抄的 startActivity", actions.get(2).contains("android.settings.ACCESSIBILITY_SETTINGS"));
    }

    @Test
    public void waitTimeoutMessageMentionsMillisAndActions() {
        String message = AccessibilityServiceErrors.waitTimeoutMessage(COMPONENT, 10000L);
        assertTrue(message.contains("10000ms"));
        assertTrue(message.contains(COMPONENT));
        assertTrue(message.contains("超时"));
    }
}