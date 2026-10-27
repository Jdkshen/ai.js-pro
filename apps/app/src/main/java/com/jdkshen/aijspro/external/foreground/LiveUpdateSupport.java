package com.jdkshen.aijspro.external.foreground;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import androidx.core.app.NotificationCompat;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Public Android 16 live updates and observable delivery, distinct from OEM authorization. */
final class LiveUpdateSupport {
    static final String PERMISSION = "android.permission.POST_PROMOTED_NOTIFICATIONS";
    static final String SETTINGS = "android.settings.MANAGE_APP_PROMOTED_NOTIFICATIONS";
    // Public API 36.1 extras; string keys allow the existing compile SDK and old ROMs to coexist.
    private static final String REQUEST = "android.requestPromotedOngoing";
    private static final String CRITICAL_TEXT = "android.shortCriticalText";

    static void request(NotificationCompat.Builder builder, int count) {
        if (Build.VERSION.SDK_INT < 36) return;
        Bundle extras = new Bundle();
        extras.putBoolean(REQUEST, true);
        extras.putString(CRITICAL_TEXT, (count > 999 ? "999+" : String.valueOf(count)) + "项");
        builder.addExtras(extras);
    }

    /** Avoid mixing an authorized public live update with an unregistered OEM template. */
    static boolean usePlatformRoute(JSONObject platform) {
        JSONObject live = platform.optJSONObject("liveUpdates");
        return live != null && live.optBoolean("apiAvailable")
                && live.optBoolean("permissionDeclared")
                && live.optBoolean("canPostPromotedNotifications");
    }

    static JSONObject capabilities(Context context) {
        JSONObject result = new JSONObject();
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo info = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_PERMISSIONS);
            boolean declared = false;
            if (info.requestedPermissions != null) for (String permission : info.requestedPermissions) {
                if (PERMISSION.equals(permission)) declared = true;
            }
            JSONObject live = new JSONObject().put("permissionDeclared", declared)
                    .put("permissionGranted", Build.VERSION.SDK_INT >= 36 &&
                            pm.checkPermission(PERMISSION, context.getPackageName()) == PackageManager.PERMISSION_GRANTED);
            boolean api = false;
            Boolean allowed = null;
            String error = "";
            if (Build.VERSION.SDK_INT >= 36) {
                try {
                    Object manager = context.getSystemService(Context.NOTIFICATION_SERVICE);
                    // We request through the public extras. Some Android 16 OEM builds do not
                    // expose the newer Builder setter to an app compiled against an older SDK.
                    allowed = (Boolean) manager.getClass().getMethod("canPostPromotedNotifications").invoke(manager);
                    api = true;
                } catch (Exception failure) { error = failure.getClass().getSimpleName(); }
            }
            live.put("apiAvailable", api).put("canPostPromotedNotifications", allowed == null ? JSONObject.NULL : allowed)
                    .put("queryError", error);
            Bundle metadata = pm.getApplicationInfo(context.getPackageName(), PackageManager.GET_META_DATA).metaData;
            Object id = metadata == null ? null : metadata.get("com.xiaomi.xms.APP_ID");
            String appId = id == null ? "" : String.valueOf(id).trim();
            Object debug = metadata == null ? null : metadata.get("com.xiaomi.xms.BUILD_TYPE_DEBUG");
            result.put("appVersion", info.versionName).put("liveUpdates", live).put("xiaomiRegistration", new JSONObject()
                    .put("appIdConfigured", !appId.isEmpty()).put("appId", appId)
                    .put("debugCertificateMetadata", debug == null ? JSONObject.NULL : debug)
                    .put("authorizationStatus", appId.isEmpty() ? "missing_app_id" : "not_verified"));
        } catch (Exception failure) { put(result, "queryError", failure.getClass().getSimpleName()); }
        return result;
    }

    /** Reads only the app's own untagged foreground notification, excluding system summaries. */
    static JSONObject activeNotification(Context context) {
        JSONObject result = new JSONObject();
        try {
            result.put("active", false);
            if (Build.VERSION.SDK_INT < 23) return result.put("queryAvailable", false);
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            for (StatusBarNotification posted : manager.getActiveNotifications()) {
                if (posted.getTag() != null || posted.getId() != ForegroundService.NOTIFICATION_ID) continue;
                Notification n = posted.getNotification();
                if (Build.VERSION.SDK_INT >= 26 && !ForegroundService.CHANEL_ID.equals(n.getChannelId())) continue;
                Bundle extras = n.extras;
                String payload = extras.getString("miui.focus.param");
                result.put("active", true).put("id", posted.getId()).put("flags", n.flags)
                        .put("title", String.valueOf(extras.getCharSequence(Notification.EXTRA_TITLE)))
                        .put("category", n.category == null ? JSONObject.NULL : n.category)
                        .put("group", n.getGroup() == null ? JSONObject.NULL : n.getGroup())
                        .put("postedAt", posted.getPostTime()).put("focusPayloadAttached", payload != null)
                        .put("focusPayloadBytes", payload == null ? 0 : payload.getBytes(StandardCharsets.UTF_8).length)
                        .put("focusPayload", payload == null ? JSONObject.NULL : payload)
                        .put("liveUpdateRequested", extras.getBoolean(REQUEST, false))
                        .put("promoted", hasFlag(n, "FLAG_PROMOTED_ONGOING"))
                        .put("silent", hasFlag(n, "FLAG_SILENT"));
                if (Build.VERSION.SDK_INT >= 26) {
                    result.put("channelId", n.getChannelId());
                    android.app.NotificationChannel channel = manager.getNotificationChannel(n.getChannelId());
                    if (channel != null) result.put("channelImportance", channel.getImportance());
                }
                if (Build.VERSION.SDK_INT >= 36) try {
                    result.put("hasPromotableCharacteristics", n.getClass().getMethod("hasPromotableCharacteristics").invoke(n));
                } catch (Exception failure) { result.put("promotionQueryError", failure.getClass().getSimpleName()); }
                break;
            }
        } catch (Exception failure) { put(result, "queryError", failure.getClass().getSimpleName()); }
        return result;
    }

    private static boolean hasFlag(Notification notification, String name) {
        try { return (notification.flags & Notification.class.getField(name).getInt(null)) != 0; }
        catch (Exception ignored) { return false; }
    }
    private static void put(JSONObject object, String key, Object value) {
        try { object.put(key, value); } catch (Exception ignored) { }
    }
}
