package com.stardust.autojs.core.image.capture;

import android.app.Notification;
import android.os.Build;
import android.service.notification.StatusBarNotification;
import com.stardust.notification.NotificationListenerService;

/** Reads recorder metadata only; never notification title, text or extras. */
public final class ScreenRecordingMonitor {
    private boolean observedSystemRecorder;

    public static boolean isConnected() {
        NotificationListenerService service = NotificationListenerService.Companion.getInstance();
        return service != null && service.getConnected();
    }

    public AutoCapturePolicy.Recording snapshot() {
        NotificationListenerService service = NotificationListenerService.Companion.getInstance();
        if (service == null || !service.getConnected()) return AutoCapturePolicy.Recording.UNKNOWN;
        try {
            StatusBarNotification[] notifications = service.getActiveNotifications();
            if (notifications == null) return AutoCapturePolicy.Recording.UNKNOWN;
            boolean unrecognizedRecorder = false;
            for (StatusBarNotification item : notifications) {
                Notification notification = item.getNotification();
                boolean active = (notification.flags & (Notification.FLAG_ONGOING_EVENT
                        | Notification.FLAG_FOREGROUND_SERVICE)) != 0;
                RecordingNotificationProfile.Match match = RecordingNotificationProfile.classify(
                        item.getPackageName(), notification.getChannelId(), active);
                if (match == RecordingNotificationProfile.Match.ACTIVE) {
                    observedSystemRecorder = true;
                    return AutoCapturePolicy.Recording.ACTIVE;
                }
                // OEM recorder profiles are not proven; never infer an idle projection from them.
                if (match == RecordingNotificationProfile.Match.UNKNOWN) unrecognizedRecorder = true;
            }
            if (unrecognizedRecorder) return AutoCapturePolicy.Recording.UNKNOWN;
            // Initial idle is supported on the test device's Lineage SystemUI only.
            return observedSystemRecorder || Build.PRODUCT.startsWith("lineage_")
                    ? AutoCapturePolicy.Recording.IDLE : AutoCapturePolicy.Recording.UNKNOWN;
        } catch (RuntimeException unavailable) {
            return AutoCapturePolicy.Recording.UNKNOWN;
        }
    }
}
