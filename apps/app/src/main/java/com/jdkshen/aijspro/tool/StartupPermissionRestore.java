package com.jdkshen.aijspro.tool;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import com.jdkshen.aijspro.Pref;
import com.jdkshen.aijspro.ui.floating.FloatyWindowManger;
import com.stardust.app.GlobalAppContext;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * task-54：App 启动时恢复「用户上次开启过」的系统侧状态。
 *
 * <p>背景（用户反馈）：无障碍服务、悬浮窗每次打开 App 都要手动开，没有记录。
 * Android 安全模型不允许 App 自己打开无障碍；且 {@code adb install -r} 覆盖安装后
 * MIUI/HyperOS 会把无障碍绑定清掉（真机实测 enabled_accessibility_services=null）。
 *
 * <p>本类只做三件事，不伪造绕过权限：
 * <ol>
 *   <li><b>悬浮窗</b>：{@link Pref#isFloatingMenuShown()} 为 true 时，如果仍有
 *       {@code SYSTEM_ALERT_WINDOW} 权限就自动恢复悬浮菜单；权限被撤销时，Shizuku 已授权则用
 *       shell 身份 {@code appops set … allow} 自动修复，失败才清除显示意图并提示一次。</li>
 *   <li><b>无障碍</b>：{@link Pref#isAccessibilityUserEnabled()} 为 true（用户曾开启过）但当前
 *       secure settings 显示已关闭时：Shizuku 已授权就在后台自动恢复（不弹授权框）；否则/失败时给
 *       非阻塞 Toast 提示「无障碍已关闭」。</li>
 *   <li>无 Shizuku/断网/命令失败时全部走提示，不崩溃、不影响启动。</li>
 * </ol>
 *
 * <p>只在主进程调用（{@code App.onCreate → init()} 里隔离进程早退之后），每个进程生命周期执行一次。
 */
public final class StartupPermissionRestore {

    private static final String TAG = "PermissionRestore";

    /** 悬浮窗恢复前的延迟：等首页首帧/Application 初始化完成，避免和启动抢主线程。 */
    private static final long OVERLAY_RESTORE_DELAY_MS = 1_200L;
    /** 无障碍 Shizuku 恢复的等待上限。 */
    private static final long A11Y_RESTORE_TIMEOUT_MS = 4_000L;

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    private StartupPermissionRestore() {
    }

    /** 主进程启动时调用；重复调用无副作用。 */
    public static void restoreOnStartup(Application application) {
        if (application == null || !STARTED.compareAndSet(false, true)) {
            return;
        }
        Context context = application.getApplicationContext();
        // 悬浮窗要主线程操作（FloatyService/WindowManager），延后一点等启动稳定。
        GlobalAppContext.postDelayed(() -> restoreOverlay(context), OVERLAY_RESTORE_DELAY_MS);
        // 无障碍恢复可能走 Shizuku 命令/等待服务绑定，放后台线程，绝不阻塞启动。
        Thread worker = new Thread(() -> restoreAccessibility(context), "permission-restore-a11y");
        worker.setDaemon(true);
        worker.start();
    }

    // ------------------------------------------------------------------
    // 悬浮窗
    // ------------------------------------------------------------------

    private static void restoreOverlay(Context context) {
        try {
            if (!Pref.isFloatingMenuShown()) {
                return;
            }
            if (FloatyWindowManger.isCircularMenuShowing()) {
                return;
            }
            if (AccessibilityServiceTool.canDrawOverlays(context)) {
                if (FloatyWindowManger.showCircularMenu()) {
                    Pref.setOverlayRevokedPrompted(false);
                    Log.i(TAG, "已按上次状态恢复悬浮菜单");
                }
                return;
            }
            // 权限被系统/MIUI 覆盖安装撤销：Shizuku 已授权时后台尝试 appops 恢复（不弹授权框）。
            // Shizuku 命令最坏几秒，绝不能在主线程同步跑（启动 ANR）。
            if (AccessibilityServiceTool.isShizukuAvailable()
                    && AccessibilityServiceTool.hasShizukuPermission()) {
                Thread worker = new Thread(() -> {
                    boolean healed = false;
                    try {
                        healed = AccessibilityServiceTool.enableOverlayByShizukuAndVerify();
                    } catch (Throwable error) {
                        Log.w(TAG, "通过 Shizuku 恢复悬浮窗权限失败", error);
                    }
                    final boolean restored = healed;
                    GlobalAppContext.post(() -> {
                        if (restored && FloatyWindowManger.showCircularMenu()) {
                            Pref.setOverlayRevokedPrompted(false);
                            Log.i(TAG, "已通过 Shizuku 恢复悬浮窗权限并重新显示悬浮菜单");
                            GlobalAppContext.toast("已通过 Shizuku 恢复悬浮窗权限");
                        } else {
                            clearOverlayIntentAndPrompt();
                        }
                    });
                }, "permission-restore-overlay");
                worker.setDaemon(true);
                worker.start();
                return;
            }
            // 没有 Shizuku：清除「希望显示」的意图，只提示一次，避免每次启动都弹。
            clearOverlayIntentAndPrompt();
        } catch (Throwable error) {
            Log.w(TAG, "恢复悬浮窗状态失败", error);
        }
    }

    /** 悬浮窗权限确实恢复不了：清掉显示意图并只提示一次。 */
    private static void clearOverlayIntentAndPrompt() {
        try {
            Pref.setFloatingMenuShown(false);
            if (!Pref.isOverlayRevokedPrompted()) {
                Pref.setOverlayRevokedPrompted(true);
                Log.i(TAG, "悬浮窗权限已被撤销，清除显示意图并提示");
                GlobalAppContext.toast("悬浮窗权限已被系统重置，请重新开启「悬浮窗」");
            }
        } catch (Throwable error) {
            Log.w(TAG, "清理悬浮窗意图失败", error);
        }
    }

    // ------------------------------------------------------------------
    // 无障碍
    // ------------------------------------------------------------------

    private static void restoreAccessibility(Context context) {
        try {
            boolean enabled = AccessibilityServiceTool.isAccessibilityServiceEnabled(context);
            if (enabled) {
                // 已开启：记录「用户开过」的意图，并清掉之前的一次性提示状态。
                if (!Pref.isAccessibilityUserEnabled()) {
                    Pref.setAccessibilityUserEnabled(true);
                }
                return;
            }
            if (!Pref.isAccessibilityUserEnabled()) {
                // 从没开过：不打扰用户（等用户主动点击无障碍设置）。
                return;
            }
            // 用户曾开过、现在被清掉：Shizuku 已授权则后台自动恢复（不弹授权对话框）。
            if (AccessibilityServiceTool.isShizukuAvailable()
                    && AccessibilityServiceTool.hasShizukuPermission()) {
                boolean restored = AccessibilityServiceTool
                        .restoreAccessibilityByShizukuIfAuthorized(A11Y_RESTORE_TIMEOUT_MS);
                if (restored) {
                    Log.i(TAG, "已通过 Shizuku 自动恢复无障碍服务");
                    GlobalAppContext.toast("已通过 Shizuku 恢复无障碍服务");
                    return;
                }
            }
            // 回退：非阻塞提示（不跳设置页，避免打断用户；点击无障碍行仍走原 goToAccessibilitySetting）。
            Log.i(TAG, "无障碍服务曾开启、当前关闭，提示重新开启");
            GlobalAppContext.toast("无障碍服务已关闭，请在设置中重新开启（或授权 Shizuku 自动恢复）");
        } catch (Throwable error) {
            Log.w(TAG, "恢复无障碍状态失败", error);
        }
    }
}
