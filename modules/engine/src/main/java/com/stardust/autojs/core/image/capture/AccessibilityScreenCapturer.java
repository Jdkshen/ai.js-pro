package com.stardust.autojs.core.image.capture;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.Paint;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
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
    private static final String TAG_SPLIT = "A11Y_SPLIT";
    private static final Object REQUEST_LOCK = new Object();
    private static long lastRequestMillis;

    public static boolean isAvailable() {
        if (Build.VERSION.SDK_INT < 30) return false;
        AccessibilityService service = com.stardust.view.accessibility.AccessibilityService.Companion.getInstance();
        return service != null && service.getServiceInfo() != null
                && (service.getServiceInfo().getCapabilities()
                & AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT) != 0;
    }

    /** MCP / PNG 路径：全分辨率回读（旧行为不变）。 */
    public Bitmap capture(long timeoutMillis) {
        return capture(timeoutMillis, 0, 0);
    }

    /**
     * 缩略回读：maxWidth/maxHeight &gt; 0 时在宿主侧把 HardwareBuffer 直接缩放绘制到小位图，
     * 避免全屏 copy 后再 createScaledBitmap；两者 &lt;= 0（或缩略比不足 0.1%）走全分辨率旧路径。
     */
    public Bitmap capture(long timeoutMillis, int maxWidth, int maxHeight) {
        try (HardwareFrame frame = captureHardware(timeoutMillis)) {
            long readbackStart = SystemClock.elapsedRealtime();
            Bitmap bitmap = frame.toBitmap(maxWidth, maxHeight);
            Log.i(TAG_SPLIT, "readback=" + (SystemClock.elapsedRealtime() - readbackStart)
                    + "ms size=" + bitmap.getWidth() + "x" + bitmap.getHeight()
                    + " max=" + maxWidth + "x" + maxHeight);
            return bitmap;
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
                    final long requestStart = SystemClock.elapsedRealtime();
                    AccessibilityService.TakeScreenshotCallback callback = new AccessibilityService.TakeScreenshotCallback() {
                        @Override public void onSuccess(AccessibilityService.ScreenshotResult screenshot) {
                            Log.i(TAG_SPLIT, "request=" + (SystemClock.elapsedRealtime() - requestStart) + "ms");
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
        /** 全分辨率回读（旧路径：wrap → copy）。 */
        public Bitmap toBitmap() {
            return toBitmap(0, 0);
        }

        /**
         * 按 maxWidth/maxHeight 缩略回读：max &lt;= 0 或缩略比 &gt;= 0.999 走原 full copy；
         * 否则用 Canvas 把 hardware Bitmap 缩放绘制到目标小位图（hardware 不能直接 createScaledBitmap）。
         */
        public Bitmap toBitmap(int maxWidth, int maxHeight) {
            Bitmap hardware = Bitmap.wrapHardwareBuffer(buffer, colorSpace);
            if (hardware == null) throw new IllegalStateException("系统返回了无法读取的截图缓冲区");
            try {
                int width = hardware.getWidth();
                int height = hardware.getHeight();
                int[] scaled = scaleSize(width, height, maxWidth, maxHeight);
                if (scaled[0] == width && scaled[1] == height) {
                    Bitmap software = hardware.copy(Bitmap.Config.ARGB_8888, false);
                    if (software == null) throw new IllegalStateException("截图像素复制失败");
                    return software;
                }
                return scaleHardwareBitmap(hardware, width, height, scaled[0], scaled[1]);
            } finally {
                hardware.recycle();
            }
        }
        @Override public void close() { buffer.close(); }
    }

    /**
     * 把 hardware Bitmap 缩放绘制到 ARGB_8888 小位图。
     *
     * <p>hardware Bitmap 不能直接 createScaledBitmap；某些系统也不允许把 HARDWARE 位图绘制到
     * 软件 Canvas，此时回退「全屏 copy + 软件缩放」保证功能（日志会打 fallback）。
     */
    private static Bitmap scaleHardwareBitmap(Bitmap hardware, int width, int height,
                                             int scaledWidth, int scaledHeight) {
        long scaleStart = SystemClock.elapsedRealtime();
        Bitmap output;
        try {
            output = Bitmap.createBitmap(scaledWidth, scaledHeight, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError memory) {
            throw new IllegalStateException("截图缩放内存不足（" + scaledWidth + "x" + scaledHeight + "）", memory);
        }
        if (output == null) throw new IllegalStateException("截图缩放位图创建失败");
        try {
            Canvas canvas = new Canvas(output);
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
            canvas.drawBitmap(hardware, new Rect(0, 0, width, height),
                    new Rect(0, 0, scaledWidth, scaledHeight), paint);
            Log.i(TAG_SPLIT, "scale=" + (SystemClock.elapsedRealtime() - scaleStart) + "ms "
                    + scaledWidth + "x" + scaledHeight);
            return output;
        } catch (Throwable error) {
            output.recycle();
            Log.w(TAG_SPLIT, "canvas scale failed, fallback full copy: " + error);
            Bitmap full = hardware.copy(Bitmap.Config.ARGB_8888, false);
            if (full == null) throw new IllegalStateException("截图像素复制失败", error);
            try {
                return Bitmap.createScaledBitmap(full, scaledWidth, scaledHeight, true);
            } finally {
                full.recycle();
            }
        }
    }

    /**
     * 纯 JVM：按 maxWidth/maxHeight 计算缩略目标尺寸。
     *
     * <ul>
     *   <li>max &lt;= 0 表示该边不限制；两边都不限制 / 不需要缩小 → 返回原尺寸；</li>
     *   <li>单边限制按该边比例；两边都限制取较小比例；缩略比 &gt;= 0.999 仍走全分辨率；</li>
     *   <li>目标宽高四舍五入，最小 1px（避免 0 尺寸位图）。</li>
     * </ul>
     */
    static int[] scaleSize(int width, int height, int maxWidth, int maxHeight) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("截图尺寸无效：" + width + "x" + height);
        }
        boolean limitWidth = maxWidth > 0 && maxWidth < width;
        boolean limitHeight = maxHeight > 0 && maxHeight < height;
        if (!limitWidth && !limitHeight) {
            return new int[]{width, height};
        }
        float scale;
        if (limitWidth && limitHeight) {
            scale = Math.min(maxWidth / (float) width, maxHeight / (float) height);
        } else if (limitWidth) {
            scale = maxWidth / (float) width;
        } else {
            scale = maxHeight / (float) height;
        }
        if (scale >= 0.999f) {
            return new int[]{width, height};
        }
        int scaledWidth = Math.max(1, Math.round(width * scale));
        int scaledHeight = Math.max(1, Math.round(height * scale));
        if (scaledWidth >= width && scaledHeight >= height) {
            return new int[]{width, height};
        }
        return new int[]{scaledWidth, scaledHeight};
    }

    private static final class ScreenshotFailure extends Exception {
        final int code;
        ScreenshotFailure(int code) {
            super("系统错误码 " + code + "（请检查无障碍权限；安全保护画面不能截图）");
            this.code = code;
        }
    }
}
