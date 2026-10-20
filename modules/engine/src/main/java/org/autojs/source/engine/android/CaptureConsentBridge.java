// SPDX-License-Identifier: MIT
package org.autojs.source.engine.android;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 跨进程转发「自动点掉系统截图授权」开关。
 *
 * <p>Node 授权页运行在 {@code :node24} 进程，而无障碍服务实例只在主进程里，
 * 所以 :node24 不能直接调用 {@code ScreenCaptureAutoConsent.arm()}（会读到 null instance 永远不点）。
 * 这里通过应用内广播让主进程的 {@link CaptureAutoConsentReceiver} 代持 arm/disarm。
 */
public final class CaptureConsentBridge {

    public static final String ACTION = "org.autojs.source.engine.CAPTURE_AUTO_CONSENT";
    public static final String EXTRA_ARM = "arm";

    private static final String TAG = "CaptureConsentBridge";

    private CaptureConsentBridge() {
    }

    public static void arm(Context context) {
        send(context, true);
    }

    public static void disarm(Context context) {
        send(context, false);
    }

    private static void send(Context context, boolean arm) {
        if (context == null) return;
        try {
            Intent intent = new Intent(ACTION);
            intent.setPackage(context.getPackageName());
            intent.putExtra(EXTRA_ARM, arm);
            context.sendBroadcast(intent);
            Log.i(TAG, arm ? "arm sent" : "disarm sent");
        } catch (Throwable error) {
            Log.w(TAG, "send failed", error);
        }
    }
}
