package com.stardust.autojs.core.image.capture;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;

import androidx.annotation.RequiresApi;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Independent full-display screenshots: never starts or replaces MediaProjection. */
@RequiresApi(30)
public final class AccessibilityScreenCapturer {
    // Screenshot requests are rate limited by Android. Serialize across script runtimes too.
    private static final Object REQUEST_LOCK = new Object();
    private static long lastRequestMillis;

    public static boolean isAvailable() {
        if (Build.VERSION.SDK_INT < 30) return false;
        AccessibilityService service = com.stardust.view.accessibility.AccessibilityService.Companion.getInstance();
        return service != null && service.getServiceInfo() != null
                && (service.getServiceInfo().getCapabilities()
                & AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT) != 0;
    }

    public Bitmap capture(long timeoutMillis) {
        try (HardwareFrame frame = captureHardware(timeoutMillis)) {
            return frame.toBitmap();
        }
    }

    public HardwareFrame captureHardware(long timeoutMillis) {
        return captureHardware(-1, timeoutMillis);
    }

    @RequiresApi(34)
    public HardwareFrame captureWindowHardware(int windowId, long timeoutMillis) {
        if (Build.VERSION.SDK_INT < 34) throw new UnsupportedOperationException("窗口截图需要 Android 14+");
        if (windowId < 0) throw new IllegalArgumentException("无效的无障碍窗口 ID");
        return captureHardware(windowId, timeoutMillis);
    }

    private HardwareFrame captureHardware(int windowId, long timeoutMillis) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("录屏兼容截图不能在主线程等待");
        }
        if (!isAvailable()) {
            throw new SecurityException("录屏兼容截图需要 Android 11+，并开启 AI.js Pro 无障碍服务；更新后若已开启，请关闭再开启一次");
        }
        long deadline = SystemClock.elapsedRealtime() + Math.max(0, timeoutMillis);
        synchronized (REQUEST_LOCK) {
            while (true) {
                long remaining = deadline - SystemClock.elapsedRealtime();
                // Window requests have a separate system path. Do not impose the display
                // screenshot's 340ms wait; retry only when Android reports rate limiting.
                long delay = windowId < 0 ? Math.max(0, 340 - (SystemClock.elapsedRealtime() - lastRequestMillis)) : 0;
                if (remaining <= delay) throw new IllegalStateException("等待录屏兼容截图超时；请将 timeout 设置为 2000 或以上");
                try {
                    if (delay > 0) Thread.sleep(delay);
                    AccessibilityService service = com.stardust.view.accessibility.AccessibilityService.Companion.getInstance();
                    if (service == null) throw new SecurityException("AI.js Pro 无障碍服务已断开，请重新开启");
                    CompletableFuture<HardwareFrame> result = new CompletableFuture<>();
                    AccessibilityService.TakeScreenshotCallback callback = new AccessibilityService.TakeScreenshotCallback() {
                        @Override public void onSuccess(AccessibilityService.ScreenshotResult screenshot) {
                            // Transfer ownership without copying full-display pixels in the callback.
                            HardwareFrame frame = new HardwareFrame(screenshot.getHardwareBuffer(),
                                    screenshot.getColorSpace());
                            if (!result.complete(frame)) frame.close();
                        }
                        @Override public void onFailure(int code) {
                            result.completeExceptionally(new ScreenshotFailure(code));
                        }
                    };
                    if (windowId < 0) {
                        lastRequestMillis = SystemClock.elapsedRealtime();
                        service.takeScreenshot(Display.DEFAULT_DISPLAY, Runnable::run, callback);
                    } else {
                        service.takeScreenshotOfWindow(windowId, Runnable::run, callback);
                    }
                    try {
                        return result.get(Math.max(1, deadline - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS);
                    } catch (ExecutionException error) {
                        if (error.getCause() instanceof ScreenshotFailure
                                && ((ScreenshotFailure) error.getCause()).code
                                == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                            if (windowId >= 0) Thread.sleep(Math.min(50, Math.max(1, deadline - SystemClock.elapsedRealtime())));
                            continue;
                        }
                        throw new IllegalStateException("录屏兼容截图失败：" + error.getCause().getMessage(), error.getCause());
                    } catch (TimeoutException | InterruptedException error) {
                        result.cancel(false);
                        // If completion won the race, ownership still belongs to this request.
                        if (!result.isCompletedExceptionally()) {
                            HardwareFrame abandoned = result.getNow(null);
                            if (abandoned != null) abandoned.close();
                        }
                        throw error;
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("录屏兼容截图已中断", error);
                } catch (TimeoutException error) {
                    throw new IllegalStateException("等待录屏兼容截图超时", error);
                }
            }
        }
    }

    public static final class HardwareFrame implements AutoCloseable {
        private final HardwareBuffer buffer;
        private final ColorSpace colorSpace;
        HardwareFrame(HardwareBuffer buffer, ColorSpace colorSpace) {
            this.buffer = buffer;
            this.colorSpace = colorSpace;
        }
        public HardwareBuffer getBuffer() { return buffer; }
        public Bitmap toBitmap() {
            Bitmap hardware = Bitmap.wrapHardwareBuffer(buffer, colorSpace);
            if (hardware == null) throw new IllegalStateException("系统返回了无法读取的截图缓冲区");
            try {
                Bitmap software = hardware.copy(Bitmap.Config.ARGB_8888, false);
                if (software == null) throw new IllegalStateException("截图像素复制失败");
                return software;
            } finally {
                hardware.recycle();
            }
        }
        @Override public void close() { buffer.close(); }
    }

    private static final class ScreenshotFailure extends Exception {
        final int code;
        ScreenshotFailure(int code) {
            super("系统错误码 " + code + "（请检查无障碍权限；安全保护画面不能截图）");
            this.code = code;
        }
    }
}
