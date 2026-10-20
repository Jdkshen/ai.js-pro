// SPDX-License-Identifier: MIT
package org.autojs.source.engine.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.stardust.autojs.core.image.capture.ScreenCaptureAutoConsent;

/** 主进程接收 {@link CaptureConsentBridge} 的广播，用主进程的无障碍服务自动点授权框。 */
public final class CaptureAutoConsentReceiver extends BroadcastReceiver {

    private static final String TAG = "CaptureAutoConsentRx";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !CaptureConsentBridge.ACTION.equals(intent.getAction())) {
            return;
        }
        boolean arm = intent.getBooleanExtra(CaptureConsentBridge.EXTRA_ARM, false);
        Log.i(TAG, arm ? "arm received" : "disarm received");
        if (arm) {
            ScreenCaptureAutoConsent.arm();
        } else {
            ScreenCaptureAutoConsent.disarm();
        }
    }
}
