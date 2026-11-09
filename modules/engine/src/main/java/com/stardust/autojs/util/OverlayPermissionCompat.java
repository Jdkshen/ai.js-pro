package com.stardust.autojs.util;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

/**
 * task-77：**悬浮窗权限申请的唯一点**（`activity.startActivity` / `startActivityForResult` 的兜底）。
 *
 * <p>用户实报（打包版 `com.nuoyapro`、悬浮窗/无障碍都已开，仍弹「请求悬浮窗权限出错」）：脚本里
 * {@code intent.setAction("android.settings.action.MANAGE_OVERLAY_PERMISSION")} **只给 action、不带 data** ——
 * Android 11+/MIUI 上这种 intent 可能没有处理者（{@link ActivityNotFoundException}），而用户脚本把它 try/catch 吞了。
 *
 * <p>兜底（不改用户脚本）：① 该 action 且**无 data** → 自动补 {@code package:<当前包名>}；
 * ② 仍找不到 → 回退 {@link FloatingPermission#manageDrawOverlays(android.content.Context)}
 * （已有：Android M / MIUI / 应用详情页兜底，本类**不另写一份**）；③ 全程打日志（action/data/包名），不静默。
 *
 * <p>接线：脚本侧 `activity` 是 `__ui__.js` 里 `OverridingJavaObject.of(global, real)` 的覆盖对象 ——
 * 在它的 `__overrides__` 里把 `startActivity`/`startActivityForResult` 指到本类即可（报告里给了逐行补丁）。
 */
public final class OverlayPermissionCompat {

    private static final String TAG = "OverlayPermission";
    /** 与 android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION 同值（写字面量以便纯 JVM 单测）。 */
    public static final String ACTION_MANAGE_OVERLAY_PERMISSION = "android.settings.action.MANAGE_OVERLAY_PERMISSION";

    private OverlayPermissionCompat() {
    }

    /** 是不是「管理悬浮窗权限」这条件（纯判定，不依赖 android.*，可单测）。 */
    public static boolean isManageOverlayAction(String action) {
        return ACTION_MANAGE_OVERLAY_PERMISSION.equals(action);
    }

    /** 是否需要补 package:：是这条件 action **且**没有 data（空/空白也算没有）。 */
    public static boolean needsPackageData(String action, String dataString) {
        return isManageOverlayAction(action) && (dataString == null || dataString.trim().isEmpty());
    }

    /** 补的 data 形态（纯函数）。 */
    public static String packageData(String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) {
            return null;
        }
        return "package:" + packageName.trim();
    }

    /** 归一化：需要补时补上 package:<包名>；其它 intent 原样返回。 */
    public static Intent normalize(Intent intent, String packageName) {
        if (intent == null) {
            return null;
        }
        String action = intent.getAction();
        String data = intent.getData() == null ? null : intent.getData().toString();
        if (!needsPackageData(action, data)) {
            return intent;
        }
        String uri = packageData(packageName);
        if (uri == null) {
            Log.w(TAG, "悬浮窗权限 intent 需要 package:，但拿不到包名：action=" + action);
            return intent;
        }
        intent.setData(Uri.parse(uri));
        return intent;
    }

    /** 脚本 `activity.startActivity(intent)` 的兜底入口。 */
    public static void startActivity(Activity activity, Intent intent) {
        start(activity, intent, -1);
    }

    /** 脚本 `activity.startActivityForResult(intent, code)` 的兜底入口。 */
    public static void startActivityForResult(Activity activity, Intent intent, int requestCode) {
        start(activity, intent, requestCode);
    }

    /** 唯一实现点：归一化 → 启动 → 找不到处理者时按 action 决定回退 / 原样抛。 */
    private static void start(Activity activity, Intent intent, int requestCode) {
        String packageName = activity == null ? null : activity.getPackageName();
        Intent target = normalize(intent, packageName);
        String action = target == null ? null : target.getAction();
        String data = target == null || target.getData() == null ? null : target.getData().toString();
        if (target == null) {
            Log.w(TAG, "startActivity 收到 null intent（pkg=" + packageName + "）");
            return;
        }
        try {
            if (requestCode >= 0) {
                activity.startActivityForResult(target, requestCode);
            } else {
                activity.startActivity(target);
            }
            Log.i(TAG, "startActivity ok action=" + action + " data=" + data + " pkg=" + packageName
                    + " requestCode=" + requestCode);
        } catch (ActivityNotFoundException notFound) {
            Log.w(TAG, "startActivity 没有处理者：action=" + action + " data=" + data + " pkg=" + packageName, notFound);
            if (!isManageOverlayAction(action)) {
                throw notFound;
            }
            Log.i(TAG, "回退 FloatingPermission.manageDrawOverlays（Android M / MIUI / 应用详情页兜底）");
            try {
                FloatingPermission.manageDrawOverlays(activity);
            } catch (Throwable fallbackError) {
                Log.w(TAG, "回退 manageDrawOverlays 也失败：action=" + action + " data=" + data
                        + " pkg=" + packageName, fallbackError);
            }
        }
    }
}