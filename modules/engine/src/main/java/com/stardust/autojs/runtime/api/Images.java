package com.stardust.autojs.runtime.api;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.media.Image;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.app.KeyguardManager;

import androidx.annotation.RequiresApi;

import android.util.Base64;
import android.view.Gravity;

import com.stardust.autojs.annotation.ScriptInterface;
import com.stardust.autojs.annotation.ScriptVariable;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import com.stardust.autojs.core.image.ColorFinder;
import com.stardust.autojs.core.image.ImageMetrics;
import com.stardust.autojs.core.image.ImageWrapper;
import com.stardust.autojs.core.image.TemplateMatching;
import com.stardust.autojs.core.image.capture.ScreenCaptureRequester;
import com.stardust.autojs.core.image.capture.ScreenCapturer;
import com.stardust.autojs.core.image.capture.AccessibilityScreenCapturer;
import com.stardust.autojs.core.image.capture.AutoCapturePermissionActivity;
import com.stardust.autojs.core.image.capture.AutoCapturePolicy;
import com.stardust.autojs.core.image.capture.ScreenRecordingMonitor;
import com.stardust.autojs.core.opencv.Mat;
import com.stardust.autojs.core.opencv.OpenCVHelper;
import com.stardust.autojs.core.ui.inflater.util.Drawables;
import com.stardust.autojs.core.util.ScriptPromiseAdapter;
import com.stardust.autojs.runtime.ScriptRuntime;
import com.stardust.concurrent.VolatileDispose;
import com.stardust.pio.UncheckedIOException;
import com.stardust.util.ScreenMetrics;

import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.imgproc.Imgproc;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Created by Stardust on 2017/5/20.
 */
@RequiresApi(api = Build.VERSION_CODES.KITKAT)
public class Images {

    private ScriptRuntime mScriptRuntime;
    private ScreenCaptureRequester mScreenCaptureRequester;
    private ScreenCapturer mScreenCapturer;
    private boolean mAccessibilityCapture;
    private AutoCapturePolicy mAutoCapturePolicy;
    private ScreenRecordingMonitor mRecordingMonitor;
    private final Handler mCaptureMainHandler = new Handler(Looper.getMainLooper());
    private volatile int mCaptureGeneration;
    private int mAutoOrientation;
    private long mNextRecordingCheck;
    private Intent mPendingProjectionConsent;
    private Looper mAutoCaptureLooper;
    private Context mContext;
    private Image mPreCapture;
    private ImageWrapper mPreCaptureImage;
    private ScreenMetrics mScreenMetrics;
    private volatile boolean mOpenCvInitialized = false;

    /** PDF §2.18.2：$images.getScreenCaptureOptions() 要报告当初申请的截图方向。 */
    private volatile int mScreenCaptureOrientation = ScreenCapturer.ORIENTATION_AUTO;

    @ScriptVariable
    public final ColorFinder colorFinder;

    public Images(Context context, ScriptRuntime scriptRuntime, ScreenCaptureRequester screenCaptureRequester) {
        mScriptRuntime = scriptRuntime;
        mScreenCaptureRequester = screenCaptureRequester;
        mContext = context;
        mScreenMetrics = mScriptRuntime.getScreenMetrics();
        colorFinder = new ColorFinder(mScreenMetrics);
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public synchronized ScriptPromiseAdapter requestScreenCapture(int orientation) {
        ScriptRuntime.requiresApi(21);
        mScreenCaptureOrientation = orientation;
        mAutoCapturePolicy = null;        mRecordingMonitor = null;
        mPendingProjectionConsent = null;
        final int generation = ++mCaptureGeneration;
        mAccessibilityCapture = false;
        ScriptPromiseAdapter promiseAdapter = new ScriptPromiseAdapter();
        if (mScreenCapturer != null && !mScreenCapturer.isReleased()) {
            mScreenCapturer.setOrientation(orientation);
            promiseAdapter.resolve(true);
            return promiseAdapter;
        }
        // A stopped projection cannot be reused with the old consent token.
        if (mScreenCapturer != null) {
            mScreenCapturer.release();
        }
        mScreenCapturer = null;
        mPreCapture = null;
        if (mPreCaptureImage != null) {
            mPreCaptureImage.recycle();
            mPreCaptureImage = null;
        }
        Looper servantLooper = mScriptRuntime.loopers.getServantLooper();
        mScreenCaptureRequester.setOnActivityResultCallback((result, data) -> {
            if (generation != mCaptureGeneration) {
                promiseAdapter.resolve(false);
                return;
            }
            if (result == Activity.RESULT_OK) {
                try {
                    mScreenCapturer = new ScreenCapturer(mContext, data, orientation, ScreenMetrics.getDeviceScreenDensity(),
                            new Handler(servantLooper));
                    promiseAdapter.resolve(true);
                } catch (Exception error) {
                    promiseAdapter.reject(error);
                }
            } else {
                promiseAdapter.resolve(false);
            }
        });
        mScreenCaptureRequester.request();
        return promiseAdapter;
    }

    /**
     * PDF §2.18.2 `$images.getScreenCaptureOptions()`（Pro 8.8.12 新增）：
     * 当前截图配置；**没申请过截图权限时返回 null**。
     */
    @ScriptInterface
    public Object getScreenCaptureOptions() {
        ScreenCapturer capturer = mScreenCapturer;
        boolean projectionReady = capturer != null && !capturer.isReleased();
        // 无障碍（录屏兼容）截图通道没有 ScreenCapturer 对象，但同样算"已申请截图权限"
        boolean accessibilityReady = mAccessibilityCapture && AccessibilityScreenCapturer.isAvailable();
        if (!projectionReady && !accessibilityReady) {
            return null;
        }
        Scriptable scope = mScriptRuntime.getTopLevelScope();
        if (scope == null) {
            return null;
        }
        int orientation = mScreenCaptureOrientation;
        org.mozilla.javascript.Context context = org.mozilla.javascript.Context.getCurrentContext();
        Scriptable options = context.newObject(scope);
        ScriptableObject.putProperty(options, "width",
                ScreenMetrics.getOrientationAwareScreenWidth(orientation));
        ScriptableObject.putProperty(options, "height",
                ScreenMetrics.getOrientationAwareScreenHeight(orientation));
        ScriptableObject.putProperty(options, "orientation", orientation);
        ScriptableObject.putProperty(options, "density", ScreenMetrics.getDeviceScreenDensity());
        // 本实现的截图都是同步取的（ScreenCapturer.capture 阻塞到拿到帧），所以固定 false
        ScriptableObject.putProperty(options, "async", false);
        return options;
    }

    /**
     * PDF §2.18.2 `$images.stopScreenCapture()`（Pro 8.8.12 新增）：释放截图权限；
     * 没申请过就什么都不做。
     */
    @ScriptInterface
    public void stopScreenCapture() {
        releaseScreenCapturer();
    }

    /**
     * PDF §2.18.1 `images.getSimilarity(img1, img2, options)`：
     * `type` 支持 `MSSIM`（默认）与 `PNSR`/`PSNR`。
     */
    @ScriptInterface
    public double getSimilarity(ImageWrapper first, ImageWrapper second, String type) {
        String algorithm = type == null || type.trim().isEmpty()
                ? "MSSIM" : type.trim().toUpperCase(java.util.Locale.ROOT);
        switch (algorithm) {
            case "PNSR":
            case "PSNR":
                return ImageMetrics.psnr(first.getBitmap(), second.getBitmap());
            case "MSSIM":
            case "SSIM":
                return ImageMetrics.ssim(first.getBitmap(), second.getBitmap());
            default:
                throw new IllegalArgumentException("不支持的相似度算法: " + type + "（可选 MSSIM / PNSR）");
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public boolean requestScreenCaptureBlocking(int orientation) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("Blocking screen capture permission cannot run on the main thread");
        }
        VolatileDispose<Boolean> result = new VolatileDispose<>();
        requestScreenCapture(orientation)
                .onResolve(value -> result.setAndNotify(Boolean.TRUE.equals(value)))
                .onReject(error -> result.setAndNotify(false));
        return result.blockedGet();
    }

    public synchronized boolean requestAccessibilityScreenCapture() {
        if (Build.VERSION.SDK_INT < 30 || !AccessibilityScreenCapturer.isAvailable()) {
            throw new SecurityException("录屏兼容截图需要 Android 11+，请开启 AI.js Pro 无障碍服务；更新后若已开启，请关闭再开启一次");
        }
        releaseScreenCapturer();
        mAccessibilityCapture = true;
        mScriptRuntime.console.warn("截图通道：无障碍（录屏兼容，整个屏幕；系统限制截图频率，不等同于高速录屏通道）");
        return true;
    }

    public synchronized boolean requestAutoScreenCapture(int orientation) {
        mScreenCaptureOrientation = orientation;
        requestAccessibilityScreenCapture();
        mAutoCapturePolicy = new AutoCapturePolicy();
        mRecordingMonitor = new ScreenRecordingMonitor();
        mAutoOrientation = orientation;
        mAutoCaptureLooper = mScriptRuntime.loopers.getServantLooper();
        mNextRecordingCheck = 0;
        final int generation = mCaptureGeneration;
        if (!ScreenRecordingMonitor.isConnected()) {
            mCaptureMainHandler.post(() -> {
                synchronized (Images.this) {
                    if (generation != mCaptureGeneration || mAutoCapturePolicy == null) return;
                }
                try {
                    AutoCapturePermissionActivity.show(mContext);
                } catch (RuntimeException error) {
                    mScriptRuntime.console.warn("无法打开通知权限说明，请在系统设置开启通知使用权");
                }
            });
        }
        mScriptRuntime.console.info("截图通道：自动；录屏状态无法确认时保持无障碍兼容，恢复高速需要系统授权");
        return true;
    }

    /** Advance only at frame boundaries; UI callbacks never change the channel mid-frame. */
    public synchronized void prepareAutoCapture() {
        if (mAutoCapturePolicy == null) return;
        if (mScreenCapturer != null && mScreenCapturer.isReleased()) {
            clearProjectionResources();
            mAccessibilityCapture = true;
            mScriptRuntime.console.warn("高速截图已被系统停止，自动切换为无障碍兼容截图");
        }
        long now = SystemClock.elapsedRealtime();
        if (now < mNextRecordingCheck && mPendingProjectionConsent == null) return;
        mNextRecordingCheck = now + 500;
        AutoCapturePolicy.Recording recording = mRecordingMonitor.snapshot();
        KeyguardManager keyguard = (KeyguardManager) mContext.getSystemService(Context.KEYGUARD_SERVICE);
        if (keyguard != null && keyguard.isKeyguardLocked()) recording = AutoCapturePolicy.Recording.UNKNOWN;
        if (recording == AutoCapturePolicy.Recording.ACTIVE && !mAccessibilityCapture) {
            clearProjectionResources();
            mAccessibilityCapture = true;
            mScriptRuntime.console.info("系统录屏运行中，自动切换为无障碍兼容截图");
        }
        if (mPendingProjectionConsent != null) {
            Intent consent = mPendingProjectionConsent;
            mPendingProjectionConsent = null;
            boolean restored = false;
            if (recording == AutoCapturePolicy.Recording.IDLE) {
                try {
                    clearProjectionResources();
                    mScreenCapturer = new ScreenCapturer(mContext, consent, mAutoOrientation,
                            ScreenMetrics.getDeviceScreenDensity(), new Handler(mAutoCaptureLooper));
                    mAccessibilityCapture = false;
                    restored = true;
                    mScriptRuntime.console.info("截图通道：高速（自动恢复）");
                } catch (RuntimeException error) {
                    mScriptRuntime.console.warn("恢复高速截图失败，保留兼容通道：" + error.getMessage());
                }
            }
            mAutoCapturePolicy.complete(restored);
        }
        if (!mAutoCapturePolicy.update(recording, now, mAccessibilityCapture)) return;
        final int generation = mCaptureGeneration;
        mCaptureMainHandler.post(() -> {
            synchronized (Images.this) {
                if (generation != mCaptureGeneration || mAutoCapturePolicy == null) return;
                if (mRecordingMonitor.snapshot() != AutoCapturePolicy.Recording.IDLE) {
                    mAutoCapturePolicy.complete(false);
                    return;
                }
                mScreenCaptureRequester.setOnActivityResultCallback((result, data) -> {
                    synchronized (Images.this) {
                        if (generation != mCaptureGeneration || mAutoCapturePolicy == null) return;
                        if (result == Activity.RESULT_OK && data != null) {
                            mPendingProjectionConsent = data;
                        } else {
                            mAutoCapturePolicy.complete(false);
                            mScriptRuntime.console.info("已取消恢复高速截图，继续兼容通道");
                        }
                    }
                });
                try {
                    mScriptRuntime.console.info("录屏已结束，申请高速截图授权；取消可继续兼容截图");
                    mScreenCaptureRequester.request();
                } catch (RuntimeException error) {
                    mAutoCapturePolicy.complete(false);
                    mScriptRuntime.console.warn("无法打开截图授权，继续兼容通道：" + error.getMessage());
                }
            }
        });
    }

    public synchronized boolean hasStoppedAutoProjection() {
        return mAutoCapturePolicy != null && mScreenCapturer != null && mScreenCapturer.isReleased();
    }

    public synchronized boolean isAccessibilityScreenCapture() {
        return mAccessibilityCapture;
    }

    public synchronized Bitmap captureAccessibilityScreen(long timeoutMillis) {
        if (!mAccessibilityCapture) throw new IllegalStateException("尚未选择录屏兼容截图通道");
        return new AccessibilityScreenCapturer().capture(timeoutMillis);
    }

    public synchronized AccessibilityScreenCapturer.HardwareFrame captureAccessibilityHardware(long timeoutMillis) {
        if (!mAccessibilityCapture) throw new IllegalStateException("尚未选择录屏兼容截图通道");
        return new AccessibilityScreenCapturer().captureHardware(timeoutMillis);
    }

    /**
     * Returns the current ImageReader frame without creating a Bitmap or Java OpenCV Mat.
     * The caller must copy its plane before requesting another frame.
     */
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public synchronized Image captureScreenRaw() {
        return captureScreenRaw(false, ScreenCapturer.DEFAULT_CAPTURE_TIMEOUT_MILLIS);
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public synchronized Image captureScreenRaw(boolean fresh, long timeoutMillis) {
        ScriptRuntime.requiresApi(21);
        if (mScreenCapturer == null) {
            throw new SecurityException("No screen capture permission");
        }
        return mScreenCapturer.capture(fresh, timeoutMillis);
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public synchronized ImageWrapper captureScreen() {
        return captureScreen(false, mAccessibilityCapture || mAutoCapturePolicy != null
                ? 2000 : ScreenCapturer.DEFAULT_CAPTURE_TIMEOUT_MILLIS);
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public synchronized ImageWrapper captureScreen(boolean fresh, long timeoutMillis) {
        ScriptRuntime.requiresApi(21);
        prepareAutoCapture();
        if (mAccessibilityCapture) {
            Bitmap bitmap = captureAccessibilityScreen(timeoutMillis);
            if (mPreCaptureImage != null) mPreCaptureImage.recycle();
            mPreCaptureImage = ImageWrapper.ofBitmap(bitmap);
            return mPreCaptureImage;
        }
        if (mScreenCapturer == null) {
            throw new SecurityException("No screen capture permission");
        }
        Image capture;
        try {
            capture = mScreenCapturer.capture(fresh, timeoutMillis);
        } catch (RuntimeException error) {
            if (!hasStoppedAutoProjection()) throw error;
            prepareAutoCapture();
            return captureScreen(fresh, timeoutMillis);
        }
        if (capture == mPreCapture && mPreCaptureImage != null && !mPreCaptureImage.isRecycled()) {
            return mPreCaptureImage;
        }
        mPreCapture = capture;
        if (mPreCaptureImage != null) {
            mPreCaptureImage.recycle();
        }
        mPreCaptureImage = ImageWrapper.ofImage(capture);
        return mPreCaptureImage;
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public boolean captureScreen(String path) {
        path = mScriptRuntime.files.path(path);
        ImageWrapper image = captureScreen();
        if (image != null) {
            image.saveTo(path);
            return true;
        }
        return false;
    }

    public ImageWrapper copy(ImageWrapper image) {
        return image.clone();
    }

    public boolean save(ImageWrapper image, String path, String format, int quality) throws IOException {
        Bitmap.CompressFormat compressFormat = parseImageFormat(format);
        if (compressFormat == null)
            throw new IllegalArgumentException("unknown format " + format);
        Bitmap bitmap = image.getBitmap();
        FileOutputStream outputStream = new FileOutputStream(mScriptRuntime.files.path(path));
        return bitmap.compress(compressFormat, quality, outputStream);
    }

    public static int pixel(ImageWrapper image, int x, int y) {
        if (image == null) {
            throw new NullPointerException("image = null");
        }
        return image.pixel(x, y);
    }

    public static ImageWrapper concat(ImageWrapper img1, ImageWrapper img2, int direction) {
        if (!Arrays.asList(Gravity.LEFT, Gravity.RIGHT, Gravity.TOP, Gravity.BOTTOM).contains(direction)) {
            throw new IllegalArgumentException("unknown direction " + direction);
        }
        int width;
        int height;
        if (direction == Gravity.LEFT || direction == Gravity.TOP) {
            ImageWrapper tmp = img1;
            img1 = img2;
            img2 = tmp;
        }
        if (direction == Gravity.LEFT || direction == Gravity.RIGHT) {
            width = img1.getWidth() + img2.getWidth();
            height = Math.max(img1.getHeight(), img2.getHeight());
        } else {
            width = Math.max(img1.getWidth(), img2.getHeight());
            height = img1.getHeight() + img2.getHeight();
        }
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint();
        if (direction == Gravity.LEFT || direction == Gravity.RIGHT) {
            canvas.drawBitmap(img1.getBitmap(), 0, (height - img1.getHeight()) / 2, paint);
            canvas.drawBitmap(img2.getBitmap(), img1.getWidth(), (height - img2.getHeight()) / 2, paint);
        } else {
            canvas.drawBitmap(img1.getBitmap(), (width - img1.getWidth()) / 2, 0, paint);
            canvas.drawBitmap(img2.getBitmap(), (width - img2.getWidth()) / 2, img1.getHeight(), paint);
        }
        return ImageWrapper.ofBitmap(bitmap);
    }

    public ImageWrapper rotate(ImageWrapper img, float x, float y, float degree) {
        Matrix matrix = new Matrix();
        matrix.postRotate(degree, x, y);
        return ImageWrapper.ofBitmap(Bitmap.createBitmap(img.getBitmap(), 0, 0, img.getWidth(), img.getHeight(), matrix, true));
    }

    public ImageWrapper clip(ImageWrapper img, int x, int y, int w, int h) {
        return ImageWrapper.ofBitmap(Bitmap.createBitmap(img.getBitmap(), x, y, w, h));
    }

    public ImageWrapper read(String path) {
        if (path != null && path.startsWith("asset://")) {
            String assetPath = path.substring("asset://".length());
            try (InputStream input = mContext.getAssets().open(assetPath)) {
                Bitmap bitmap = BitmapFactory.decodeStream(input);
                if (bitmap == null) {
                    throw new IllegalArgumentException("Unable to decode bundled image: " + path);
                }
                return ImageWrapper.ofBitmap(bitmap);
            } catch (IOException error) {
                throw new IllegalArgumentException("Unable to read bundled image: " + path, error);
            }
        }
        path = mScriptRuntime.files.path(path);
        Bitmap bitmap = BitmapFactory.decodeFile(path);
        return ImageWrapper.ofBitmap(bitmap);
    }

    public ImageWrapper fromBase64(String data) {
        return ImageWrapper.ofBitmap(Drawables.loadBase64Data(data));
    }

    public String toBase64(ImageWrapper wrapper, String format, int quality) {
        return Base64.encodeToString(toBytes(wrapper, format, quality), Base64.NO_WRAP);
    }

    public byte[] toBytes(ImageWrapper wrapper, String format, int quality) {
        Bitmap.CompressFormat compressFormat = parseImageFormat(format);
        if (compressFormat == null)
            throw new IllegalArgumentException("unknown format " + format);
        Bitmap bitmap = wrapper.getBitmap();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        bitmap.compress(compressFormat, quality, outputStream);
        return outputStream.toByteArray();
    }

    public ImageWrapper fromBytes(byte[] bytes) {
        return ImageWrapper.ofBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.length));
    }

    private Bitmap.CompressFormat parseImageFormat(String format) {
        switch (format) {
            case "png":
                return Bitmap.CompressFormat.PNG;
            case "jpeg":
            case "jpg":
                return Bitmap.CompressFormat.JPEG;
            case "webp":
                return Bitmap.CompressFormat.WEBP;
        }
        return null;
    }

    public ImageWrapper load(String src) {
        try {
            URL url = new URL(src);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setDoInput(true);
            connection.connect();
            InputStream input = connection.getInputStream();
            Bitmap bitmap = BitmapFactory.decodeStream(input);
            return ImageWrapper.ofBitmap(bitmap);
        } catch (IOException e) {
            return null;
        }
    }

    public static void saveBitmap(Bitmap bitmap, String path) {
        try {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, new FileOutputStream(path));
        } catch (FileNotFoundException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Bitmap scaleBitmap(Bitmap origin, int newWidth, int newHeight) {
        if (origin == null) {
            return null;
        }
        int height = origin.getHeight();
        int width = origin.getWidth();
        float scaleWidth = ((float) newWidth) / width;
        float scaleHeight = ((float) newHeight) / height;
        Matrix matrix = new Matrix();
        matrix.postScale(scaleWidth, scaleHeight);
        return Bitmap.createBitmap(origin, 0, 0, width, height, matrix, false);
    }

    public synchronized void releaseScreenCapturer() {
        ++mCaptureGeneration;
        mAutoCapturePolicy = null;
        mRecordingMonitor = null;
        mPendingProjectionConsent = null;
        mAccessibilityCapture = false;
        clearProjectionResources();
    }

    private void clearProjectionResources() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && mScreenCapturer != null) {
            mScreenCapturer.release();
            mScreenCapturer = null;
            mPreCapture = null;
            if (mPreCaptureImage != null) {
                mPreCaptureImage.recycle();
                mPreCaptureImage = null;
            }
        }
        if (mPreCaptureImage != null) {
            mPreCaptureImage.recycle();
            mPreCaptureImage = null;
        }
    }

    public Point findImage(ImageWrapper image, ImageWrapper template) {
        return findImage(image, template, 0.9f, null);
    }

    public Point findImage(ImageWrapper image, ImageWrapper template, float threshold) {
        return findImage(image, template, threshold, null);
    }

    public Point findImage(ImageWrapper image, ImageWrapper template, float threshold, Rect rect) {
        return findImage(image, template, 0.7f, threshold, rect, TemplateMatching.MAX_LEVEL_AUTO);
    }

    public Point findImage(ImageWrapper image, ImageWrapper template, float weakThreshold, float threshold, Rect rect, int maxLevel) {
        initOpenCvIfNeeded();
        if (image == null)
            throw new NullPointerException("image = null");
        if (template == null)
            throw new NullPointerException("template = null");
        Mat imageMat = image.getMat();
        Mat src = imageMat;
        try {
            if (rect != null) {
                src = new Mat(imageMat, rect);
            }
            org.opencv.core.Point point = TemplateMatching.fastTemplateMatching(src, template.getMat(), TemplateMatching.MATCHING_METHOD_DEFAULT,
                    weakThreshold, threshold, maxLevel);
            if (point != null) {
                if (rect != null) {
                    point.x += rect.x;
                    point.y += rect.y;
                }
                point.x = mScreenMetrics.scaleX((int) point.x);
                point.y = mScreenMetrics.scaleY((int) point.y);
            }
            return point;
        } finally {
            if (src != imageMat) {
                OpenCVHelper.release(src);
            }
        }
    }

    public List<TemplateMatching.Match> matchTemplate(ImageWrapper image, ImageWrapper template, float weakThreshold, float threshold, Rect rect, int maxLevel, int limit) {
        initOpenCvIfNeeded();
        if (image == null)
            throw new NullPointerException("image = null");
        if (template == null)
            throw new NullPointerException("template = null");
        Mat imageMat = image.getMat();
        Mat src = imageMat;
        try {
            if (rect != null) {
                src = new Mat(imageMat, rect);
            }
            List<TemplateMatching.Match> result = TemplateMatching.fastTemplateMatching(src, template.getMat(), Imgproc.TM_CCOEFF_NORMED,
                    weakThreshold, threshold, maxLevel, limit);
            for (TemplateMatching.Match match : result) {
                Point point = match.point;
                if (rect != null) {
                    point.x += rect.x;
                    point.y += rect.y;
                }
                point.x = mScreenMetrics.scaleX((int) point.x);
                point.y = mScreenMetrics.scaleY((int) point.y);
            }
            return result;
        } finally {
            if (src != imageMat) {
                OpenCVHelper.release(src);
            }
        }
    }

    public Mat newMat() {
        return new Mat();
    }

    public Mat newMat(Mat mat, Rect roi) {
        return new Mat(mat, roi);
    }

    public void initOpenCvIfNeeded() {
        if (mOpenCvInitialized || OpenCVHelper.isInitialized()) {
            return;
        }
        Activity currentActivity = mScriptRuntime.app.getCurrentActivity();
        Context context = currentActivity == null ? mContext : currentActivity;
        mScriptRuntime.console.info("opencv initializing");
        if (Looper.myLooper() == Looper.getMainLooper()) {
            OpenCVHelper.initIfNeeded(context, () -> {
                mOpenCvInitialized = true;
                mScriptRuntime.console.info(OpenCVHelper.getVersionInfo());
            });
        } else {
            VolatileDispose<Boolean> result = new VolatileDispose<>();
            OpenCVHelper.initIfNeeded(context, () -> {
                mOpenCvInitialized = true;
                result.setAndNotify(true);
                mScriptRuntime.console.info(OpenCVHelper.getVersionInfo());
            });
            result.blockedGet();
        }

    }
}
