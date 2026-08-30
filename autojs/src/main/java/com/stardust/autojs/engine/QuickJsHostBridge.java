package com.stardust.autojs.engine;

import android.content.res.Configuration;
import android.media.Image;
import android.os.Build;
import android.util.Log;

import com.stardust.autojs.runtime.api.Images;
import com.stardust.autojs.runtime.ScriptRuntime;
import com.stardust.autojs.runtime.api.Yolo;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Deliberately small Java/Native API boundary for QuickJS. New native APIs
 * should be added here instead of exposing arbitrary Java reflection to JS.
 */
final class QuickJsHostBridge implements AutoCloseable {

    private final ScriptRuntime mRuntime;
    private final AtomicLong mNextYoloHandle = new AtomicLong(1);
    private final Map<Long, Yolo.Detector> mYoloDetectors = new ConcurrentHashMap<>();
    private volatile long mEngineHandle;

    QuickJsHostBridge(ScriptRuntime runtime) {
        mRuntime = runtime;
    }

    void attachEngine(long engineHandle) {
        mEngineHandle = engineHandle;
    }

    public void console(int level, String message) {
        switch (level) {
            case Log.ERROR:
                mRuntime.console.error(message);
                break;
            case Log.WARN:
                mRuntime.console.warn(message);
                break;
            case Log.INFO:
                mRuntime.console.info(message);
                break;
            case Log.VERBOSE:
                mRuntime.console.verbose(message);
                break;
            default:
                mRuntime.console.log(message);
                break;
        }
    }

    public void toast(String message) {
        mRuntime.toast(message);
    }

    public boolean click(int x, int y) {
        return mRuntime.automator.click(x, y);
    }

    public boolean press(int x, int y, int duration) {
        return mRuntime.automator.press(x, y, duration);
    }

    public boolean longClick(int x, int y) {
        return mRuntime.automator.longClick(x, y);
    }

    public boolean swipe(int x1, int y1, int x2, int y2, int duration) {
        return mRuntime.automator.swipe(x1, y1, x2, y2, duration);
    }

    public boolean globalAction(String action) {
        switch (action) {
            case "back":
                return mRuntime.automator.back();
            case "home":
                return mRuntime.automator.home();
            case "recents":
                return mRuntime.automator.recents();
            case "notifications":
                return mRuntime.automator.notifications();
            case "quickSettings":
                return mRuntime.automator.quickSettings();
            default:
                throw new IllegalArgumentException("Unknown global action: " + action);
        }
    }

    public void setClip(String text) {
        mRuntime.setClip(text);
    }

    public String getClip() {
        return mRuntime.getClip();
    }

    public String getForegroundInfo(String kind) {
        if ("activity".equals(kind)) {
            return mRuntime.info.getLatestActivity();
        }
        return mRuntime.info.getLatestPackage();
    }

    public boolean requestScreenCapture(int orientation) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return false;
        }
        return images().requestScreenCaptureBlocking(normalizeOrientation(orientation));
    }

    public long captureScreenNative() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            throw new UnsupportedOperationException("Screen capture requires Android 5.0 or newer");
        }
        long engineHandle = mEngineHandle;
        if (engineHandle == 0) {
            throw new IllegalStateException("QuickJS engine is not attached");
        }
        Image image = images().captureScreenRaw();
        Image.Plane[] planes = image.getPlanes();
        if (planes.length == 0) {
            throw new IllegalStateException("Screen capture returned no image planes");
        }
        Image.Plane plane = planes[0];
        ByteBuffer buffer = plane.getBuffer().duplicate();
        buffer.rewind();
        return QuickJsNativeBridge.createNativeFrame(
                engineHandle,
                buffer,
                image.getWidth(),
                image.getHeight(),
                plane.getRowStride(),
                plane.getPixelStride()
        );
    }

    public String resolvePath(String path) {
        return mRuntime.files.path(path);
    }

    public boolean isNcnnYoloAvailable() {
        return mRuntime.yolo.isAvailable("ncnn");
    }

    public String getNcnnYoloVersion() {
        return mRuntime.yolo.getVersion("ncnn");
    }

    public String getNcnnYoloUnavailableReason() {
        return mRuntime.yolo.getUnavailableReason("ncnn");
    }

    public long loadNcnnYolo(String paramPath, String binPath, int inputSize, int threads) {
        boolean paramAsset = isAssetPath(paramPath);
        boolean binAsset = isAssetPath(binPath);
        if (paramAsset != binAsset) {
            throw new IllegalArgumentException("YOLO param 和 bin 必须同时使用 asset:// 或本地路径");
        }
        Yolo.Detector detector = paramAsset
                ? mRuntime.yolo.createFromAssets(stripAssetPrefix(paramPath), stripAssetPrefix(binPath),
                inputSize, threads)
                : mRuntime.yolo.create(mRuntime.files.path(paramPath), mRuntime.files.path(binPath),
                inputSize, threads);
        long handle = mNextYoloHandle.getAndIncrement();
        mYoloDetectors.put(handle, detector);
        return handle;
    }

    public float[] detectNcnnYolo(long detectorHandle, ByteBuffer rgba,
                                  int width, int height, int rowStride,
                                  float confidence, float nmsThreshold) {
        Yolo.Detector detector = mYoloDetectors.get(detectorHandle);
        if (detector == null) {
            throw new IllegalStateException("QuickJS YOLO detector 已关闭");
        }
        return detector.detectRgba(rgba, width, height, rowStride, confidence, nmsThreshold);
    }

    public boolean closeNcnnYolo(long detectorHandle) {
        Yolo.Detector detector = mYoloDetectors.remove(detectorHandle);
        if (detector == null) return false;
        detector.close();
        return true;
    }

    public String readYoloLabels(String path) {
        try (InputStream input = isAssetPath(path)
                ? mRuntime.uiHandler.getContext().getAssets().open(stripAssetPrefix(path))
                : new FileInputStream(mRuntime.files.path(path));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalArgumentException("无法读取 YOLO labels：" + path, error);
        }
    }

    @Override
    public void close() {
        for (Yolo.Detector detector : new ArrayList<>(mYoloDetectors.values())) {
            try {
                detector.close();
            } catch (Throwable error) {
                Log.w("QuickJsHostBridge", "Cannot close YOLO detector", error);
            }
        }
        mYoloDetectors.clear();
    }

    private static boolean isAssetPath(String path) {
        return path != null && path.startsWith("asset://");
    }

    private static String stripAssetPrefix(String path) {
        return path.substring("asset://".length());
    }

    private Images images() {
        Object images = mRuntime.getImages();
        if (!(images instanceof Images)) {
            throw new IllegalStateException("Image APIs are unavailable on this Android version");
        }
        return (Images) images;
    }

    private static int normalizeOrientation(int orientation) {
        switch (orientation) {
            case Configuration.ORIENTATION_PORTRAIT:
            case Configuration.ORIENTATION_LANDSCAPE:
                return orientation;
            default:
                return Configuration.ORIENTATION_UNDEFINED;
        }
    }
}
