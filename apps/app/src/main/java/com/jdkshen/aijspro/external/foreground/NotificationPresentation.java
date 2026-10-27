package com.jdkshen.aijspro.external.foreground;

import java.util.Locale;
import org.json.JSONObject;

/** Vendor labels and observable notification state, without equating promotion with an island. */
final class NotificationPresentation {
    static String family(String manufacturer, String brand) {
        String m = normalized(manufacturer), b = normalized(brand);
        if ("vivo".equals(m) || "iqoo".equals(m) || "vivo".equals(b) || "iqoo".equals(b)) return "vivo";
        if (isMi(m) || isMi(b)) return "xiaomi";
        return "android";
    }

    static boolean isXiaomi(String manufacturer, String brand) {
        return "xiaomi".equals(family(manufacturer, brand));
    }

    static String style(String preference, String manufacturer, String brand, int sdk) {
        if (sdk < 36) return "text";
        if ("progress".equals(preference) || "text".equals(preference)) return preference;
        return "vivo".equals(family(manufacturer, brand)) ? "progress" : "text";
    }

    static String styleLabel(String preference) {
        if ("progress".equals(preference)) return "运行进度";
        if ("text".equals(preference)) return "文字状态";
        return "自动（按设备选择）";
    }

    static JSONObject assessment(int count, JSONObject actual, JSONObject delivery) {
        JSONObject result = new JSONObject();
        String code, summary, next;
        if (count == 0 && actual.optBoolean("promoted")) {
            code = "clearing"; summary = "脚本已结束，实况通知正在清理";
            next = "等待3秒后刷新；持续残留时保留实际通知与发送错误诊断。";
        } else if (count == 0) {
            code = "idle"; summary = "没有运行中的脚本，无需显示实况通知";
            next = "先运行配套无悬浮窗测试脚本，运行期间复制诊断；结束后撤除是正常行为。";
        } else if (!delivery.optString("lastError").isEmpty()) {
            code = "delivery_error"; summary = "通知发送失败";
            next = "查看 delivery.lastError，保留脚本运行时的诊断。";
        } else if (!actual.optString("queryError").isEmpty()) {
            code = "notification_query_error"; summary = "未能读取实际通知";
            next = "保留 actualNotification.queryError，不能据此认定通知未发送。";
        } else if (actual.optBoolean("promoted")) {
            code = "promoted"; summary = "系统已提升为实况通知";
            next = "请回桌面或其他应用观察原子岛/状态栏胶囊；本状态页和悬浮窗可能影响展示，提升标记不等于已确认岛画面。";
        } else if (!actual.optBoolean("active")) {
            code = delivery.optBoolean("serviceActive") ? "waiting_for_notification" : "service_not_active";
            summary = delivery.optBoolean("serviceActive") ? "运行通知尚未出现" : "运行通知服务未启动";
            next = "等待3秒后刷新；仍无通知时检查应用通知权限，并回传运行期间诊断。";
        } else if (actual.optBoolean("liveUpdateRequested")) {
            code = "promotion_not_observed"; summary = "已请求实况通知，尚未观察到系统提升";
            next = "检查系统实况通知开关与通知通道；可对照文字状态/运行进度两种样式，最终展示由系统决定。";
        } else {
            code = "ordinary_notification"; summary = "当前为普通运行通知";
            next = "查看标准实况接口、系统版本和发送错误；小米专属模板能力不代表其他厂商的能力。";
        }
        try {
            result.put("state", code).put("summary", summary).put("nextStep", next)
                    .put("islandPresentation", "not_verified");
        } catch (Exception ignored) { }
        return result;
    }

    static String summary(JSONObject diagnostic) {
        String family = diagnostic.optString("notificationFamily", "android");
        JSONObject platform = object(diagnostic, "platform");
        JSONObject live = object(platform, "liveUpdates");
        JSONObject actual = object(diagnostic, "actualNotification");
        JSONObject delivery = object(diagnostic, "delivery");
        JSONObject status = assessment(diagnostic.optInt("runningCount"), actual, delivery);
        StringBuilder text = new StringBuilder();
        text.append("vivo".equals(family) ? "vivo / iQOO · 标准实况通知" :
                "xiaomi".equals(family) ? "小米 · 实况通知 / 超级岛" : "Android · 标准实况通知");
        text.append("\n应用通知：").append(diagnostic.optBoolean("notificationsAllowed") ? "已开启" : "未开启");
        if (diagnostic.optInt("sdkInt") < 26) text.append("\n运行通知通道：此系统无独立通道");
        else if (!diagnostic.optBoolean("runningChannelExists")) text.append("\n运行通知通道：尚未创建");
        else text.append("\n运行通知通道：").append(diagnostic.optBoolean("runningChannelAllowed") ? "已开启" : "已关闭");
        if (diagnostic.optInt("sdkInt") < 36) text.append("\nAndroid 实况通知：需要 Android 16 及对应系统接口");
        else if (!live.optString("queryError").isEmpty() || live.isNull("canPostPromotedNotifications"))
            text.append("\nAndroid 实况开关：未能查询，以实际通知结果为准");
        else text.append("\nAndroid 实况通知：").append(live.optBoolean("canPostPromotedNotifications") ? "系统允许" : "未允许");
        if ("xiaomi".equals(family)) {
            text.append("\n小米专属协议：").append(diagnostic.optInt("focusProtocol"));
            JSONObject registration = object(platform, "xiaomiRegistration");
            text.append("\n小米模板 App ID：").append(registration.optBoolean("appIdConfigured") ?
                    "已配置，授权未验证" : "未配置（不影响标准实况通道）");
        }
        if (actual.optBoolean("active")) text.append("\n实际样式：").append(
                actual.optString("notificationTemplate").endsWith("$ProgressStyle") ? "运行进度" : "文字状态");
        if (!actual.optString("styleFallbackReason").isEmpty()) text.append("\n进度样式不可用，已保留文字通知");
        return text.append("\n\n").append(status.optString("summary"))
                .append("\n").append(status.optString("nextStep")).toString();
    }

    private static JSONObject object(JSONObject source, String key) {
        JSONObject value = source.optJSONObject(key);
        return value == null ? new JSONObject() : value;
    }
    private static boolean isMi(String value) { return "xiaomi".equals(value) || "redmi".equals(value) || "poco".equals(value); }
    private static String normalized(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
}
