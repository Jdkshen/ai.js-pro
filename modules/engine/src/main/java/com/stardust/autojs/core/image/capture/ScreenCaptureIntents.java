// SPDX-License-Identifier: MIT
package com.stardust.autojs.core.image.capture;

import android.content.Intent;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.util.Log;

/**
 * MediaProjection 授权 Intent 工厂。
 *
 * <p>Android 14+ 的系统授权弹窗会多一步「共享一个应用 / 共享整个屏幕」；选「单应用」后，
 * 被共享应用一旦切到后台，投屏帧会变成全黑且不报错（真机实测），对自动化脚本是灾难：
 * 脚本切到别的 App 操作时截图就失效。这里在 API 34+ 明确请求「默认显示器」范围
 * （{@link MediaProjectionConfig#createConfigForDefaultDisplay()}），系统会直接按整屏授权，
 * 不再让用户/自动化误选单应用。
 *
 * <p>API &lt; 34 没有该选项，保持系统默认行为。
 */
public final class ScreenCaptureIntents {

    private static final String TAG = "ScreenCaptureIntents";

    private ScreenCaptureIntents() {
    }

    public static Intent create(MediaProjectionManager manager) {
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                return manager.createScreenCaptureIntent(
                        MediaProjectionConfig.createConfigForDefaultDisplay());
            } catch (Throwable error) {
                Log.w(TAG, "createConfigForDefaultDisplay failed, fallback to default intent", error);
            }
        }
        return manager.createScreenCaptureIntent();
    }
}
