package com.jdkshen.aijspro.external.foreground;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class NotificationPresentationTest {
    private JSONObject vivo() throws Exception {
        return new JSONObject().put("manufacturer", "vivo").put("notificationFamily", "vivo")
                .put("sdkInt", 36).put("runningCount", 0).put("notificationsAllowed", true)
                .put("runningChannelExists", false).put("runningChannelAllowed", JSONObject.NULL)
                .put("platform", new JSONObject().put("liveUpdates", new JSONObject()
                        .put("canPostPromotedNotifications", true).put("queryError", "")))
                .put("actualNotification", new JSONObject().put("active", false))
                .put("delivery", new JSONObject().put("serviceActive", false));
    }
    @Test public void suppliedVivoIdleReportIsIdleRatherThanUnsupported() throws Exception {
        JSONObject d = vivo();
        JSONObject assessment = NotificationPresentation.assessment(0, d.getJSONObject("actualNotification"), d.getJSONObject("delivery"));
        assertEquals("idle", assessment.getString("state"));
        String text = NotificationPresentation.summary(d);
        assertTrue(text.contains("系统允许")); assertTrue(text.contains("尚未创建"));
        assertFalse(text.contains("小米")); assertFalse(text.contains("未声明支持"));
    }
    @Test public void vivoPromotedScreenshotIsNotReportedAsAnIslandObservation() throws Exception {
        JSONObject d = vivo().put("runningCount", 1).put("runningChannelExists", true).put("runningChannelAllowed", true);
        d.getJSONObject("actualNotification").put("active", true).put("promoted", true).put("liveUpdateRequested", true);
        JSONObject assessment = NotificationPresentation.assessment(1, d.getJSONObject("actualNotification"), d.getJSONObject("delivery"));
        assertEquals("promoted", assessment.getString("state"));
        assertEquals("not_verified", assessment.getString("islandPresentation"));
        assertTrue(NotificationPresentation.summary(d).contains("回桌面"));
    }
    @Test public void aRunningScriptWithoutAServiceIsActionable() {
        assertEquals("service_not_active", NotificationPresentation.assessment(1, new JSONObject(), new JSONObject()).optString("state"));
    }
    @Test public void anEndedTaskWithAStalePromotedNotificationIsStillClearing() throws Exception {
        assertEquals("clearing", NotificationPresentation.assessment(0,
                new JSONObject().put("active", true).put("promoted", true), new JSONObject()).optString("state"));
    }
    @Test public void queryFailuresAreNotMistakenForMissingNotifications() throws Exception {
        assertEquals("notification_query_error", NotificationPresentation.assessment(1,
                new JSONObject().put("queryError", "SecurityException"), new JSONObject()).optString("state"));
    }
    @Test public void sendingErrorsDoNotMasqueradeAsUnsupportedHardware() throws Exception {
        assertEquals("delivery_error", NotificationPresentation.assessment(1, new JSONObject(),
                new JSONObject().put("lastError", "denied")).optString("state"));
    }
    @Test public void anUnpromotedRequestStillNeedsSystemDecision() throws Exception {
        assertEquals("promotion_not_observed", NotificationPresentation.assessment(1,
                new JSONObject().put("active", true).put("liveUpdateRequested", true), new JSONObject()).optString("state"));
    }
    @Test public void brandAliasesDoNotRequireXiaomiOnVivo() {
        assertEquals("vivo", NotificationPresentation.family("vivo", "iQOO"));
        assertEquals("vivo", NotificationPresentation.family(" iQOO ", ""));
        assertFalse(NotificationPresentation.isXiaomi("vivo", "iQOO"));
        assertEquals("xiaomi", NotificationPresentation.family("Xiaomi", "POCO"));
        assertEquals("android", NotificationPresentation.family("Other", "Other"));
    }
    @Test public void autoProgressIsBoundedToVivoOnAndroid16AndCanBeOverridden() {
        assertEquals("progress", NotificationPresentation.style("auto", "vivo", "", 36));
        assertEquals("text", NotificationPresentation.style("auto", "Xiaomi", "", 36));
        assertEquals("text", NotificationPresentation.style("auto", "vivo", "", 35));
        assertEquals("text", NotificationPresentation.style("text", "vivo", "", 36));
        assertEquals("progress", NotificationPresentation.style("progress", "Xiaomi", "", 36));
    }
}
