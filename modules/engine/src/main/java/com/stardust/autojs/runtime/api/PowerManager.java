package com.stardust.autojs.runtime.api;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

import com.stardust.autojs.annotation.ScriptInterface;

/**
 * PDF §2.24 `$power_manager`：电源管理（忽略电池优化）。
 *
 * <p>文档：「此模块可让您控制设备的电源状态。使用此 API 有可能影响设备的电池寿命。」
 */
public class PowerManager {

    private final Context mContext;

    public PowerManager(Context context) {
        mContext = context;
    }

    /** `$power_manager.isIgnoringBatteryOptimizations([pkg])`：默认查本应用包名。 */
    @ScriptInterface
    public boolean isIgnoringBatteryOptimizations() {
        return isIgnoringBatteryOptimizations(null);
    }

    @ScriptInterface
    public boolean isIgnoringBatteryOptimizations(String pkg) {
        String packageName = pkg == null || pkg.isEmpty() ? mContext.getPackageName() : pkg;
        try {
            android.os.PowerManager powerManager =
                    (android.os.PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
            return powerManager != null && powerManager.isIgnoringBatteryOptimizations(packageName);
        } catch (Exception e) {
            throw new RuntimeException("isIgnoringBatteryOptimizations failed: " + e.getMessage());
        }
    }

    /** `$power_manager.requestIgnoreBatteryOptimizations(forceRequest = false, pkg = 本应用)`。 */
    @ScriptInterface
    public void requestIgnoreBatteryOptimizations() {
        requestIgnoreBatteryOptimizations(false, null);
    }

    @ScriptInterface
    public void requestIgnoreBatteryOptimizations(boolean forceRequest) {
        requestIgnoreBatteryOptimizations(forceRequest, null);
    }

    /**
     * 请求忽略电池优化：系统弹出确认弹窗，**异步**，不返回结果。
     *
     * @param forceRequest false 且当前已开启忽略电池优化时，不发起请求
     * @param pkg          目标包名，默认本应用
     */
    @ScriptInterface
    public void requestIgnoreBatteryOptimizations(boolean forceRequest, String pkg) {
        String packageName = pkg == null || pkg.isEmpty() ? mContext.getPackageName() : pkg;
        if (!forceRequest && isIgnoringBatteryOptimizations(packageName)) {
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + packageName));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mContext.startActivity(intent);
        } catch (Exception e) {
            // 部分 ROM/目标 SDK 会拒绝这个 action，退回到「电池优化」列表页
            try {
                Intent fallback = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mContext.startActivity(fallback);
            } catch (Exception inner) {
                throw new RuntimeException("requestIgnoreBatteryOptimizations failed: "
                        + e.getMessage());
            }
        }
    }
}
