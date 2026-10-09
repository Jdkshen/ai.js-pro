package com.jdkshen.aijspro.pluginclient;

import android.graphics.Bitmap;
import android.os.Build;

import com.stardust.autojs.core.image.capture.AccessibilityScreenCapturer;

import java.io.ByteArrayOutputStream;

/**
 * 图色助手（自研 VS Code 扩展）：一次性全屏截图。
 *
 * <p>走「无障碍截图」通道——与悬浮窗/布局分析同一个依赖：Android 11+ 且开启 AI.js Pro
 * 的无障碍服务；不弹 MediaProjection 授权、不影响脚本自己的截图状态。
 * 返回原图尺寸的 PNG（不做缩放，保证扩展侧取到的坐标就是屏幕坐标）。
 */
public final class DevPluginScreenshotService {

    public static final String SCREENSHOT = "screenshot";
    /** 无障碍截图通道有 340ms 左右的系统限频，给足等待余量。 */
    private static final int TIMEOUT_MILLIS = 2500;

    private DevPluginScreenshotService() {
    }

    public static boolean isScreenshotCommand(String command) {
        return SCREENSHOT.equals(command);
    }

    public static Result capture() throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw new IllegalStateException("截图需要 Android 11 及以上（无障碍截图通道）");
        }
        if (!AccessibilityScreenCapturer.isAvailable()) {
            throw new IllegalStateException(
                    "截图需要开启 AI.js Pro 的无障碍服务（与布局分析同一通道）");
        }
        Bitmap bitmap = new AccessibilityScreenCapturer().capture(TIMEOUT_MILLIS);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(
                    Math.max(64 * 1024, bitmap.getByteCount() / 8));
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw new IllegalStateException("PNG 编码失败");
            }
            return new Result(out.toByteArray(), bitmap.getWidth(), bitmap.getHeight());
        } finally {
            bitmap.recycle();
        }
    }

    /** 截图结果：PNG 字节 + 原始像素尺寸（扩展侧坐标换算要用）。 */
    public static final class Result {
        public final byte[] png;
        public final int width;
        public final int height;

        Result(byte[] png, int width, int height) {
            this.png = png;
            this.width = width;
            this.height = height;
        }
    }
}
