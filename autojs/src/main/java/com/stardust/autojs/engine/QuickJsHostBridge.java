package com.stardust.autojs.engine;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.Image;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;

import com.stardust.autojs.core.http.MutableOkHttp;
import com.stardust.autojs.runtime.api.Images;
import com.stardust.autojs.runtime.ScriptRuntime;
import com.stardust.autojs.runtime.api.OnnxYoloDetector;
import com.stardust.autojs.runtime.api.OpenCvYoloDetector;
import com.stardust.autojs.runtime.api.Yolo;
import com.stardust.pio.UncheckedIOException;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Deliberately small Java/Native API boundary for QuickJS. New native APIs
 * should be added here instead of exposing arbitrary Java reflection to JS.
 */
final class QuickJsHostBridge implements AutoCloseable {

    private final ScriptRuntime mRuntime;
    private final AtomicLong mNextYoloHandle = new AtomicLong(1);
    private final Map<Long, YoloSession> mYoloSessions = new ConcurrentHashMap<>();
    private final MutableOkHttp mHttpClient = new MutableOkHttp();
    private volatile long mEngineHandle;

    private static final class YoloSession {
        final String backend;
        final AutoCloseable detector;

        YoloSession(String backend, AutoCloseable detector) {
            this.backend = backend;
            this.detector = detector;
        }
    }

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

    public boolean isYoloAvailable(String backend) {
        return mRuntime.yolo.isAvailable(backend);
    }

    public String getYoloVersion(String backend) {
        return mRuntime.yolo.getVersion(backend);
    }

    public String getYoloUnavailableReason(String backend) {
        return mRuntime.yolo.getUnavailableReason(backend);
    }

    public long loadYolo(String backend, String modelPath, String paramPath, String binPath,
                         int inputSize, int threads) {
        String normalized = normalizeQuickJsBackend(backend);
        AutoCloseable detector;
        if ("ncnn".equals(normalized)) {
            boolean paramAsset = isAssetPath(paramPath);
            boolean binAsset = isAssetPath(binPath);
            if (paramAsset != binAsset) {
                throw new IllegalArgumentException("YOLO param 和 bin 必须同时使用 asset:// 或本地路径");
            }
            detector = paramAsset
                    ? mRuntime.yolo.createFromAssets(stripAssetPrefix(paramPath),
                    stripAssetPrefix(binPath), inputSize, threads)
                    : mRuntime.yolo.create(mRuntime.files.path(paramPath),
                    mRuntime.files.path(binPath), inputSize, threads);
        } else if ("onnx".equals(normalized)) {
            detector = isAssetPath(modelPath)
                    ? mRuntime.yolo.createOnnxFromAssets(stripAssetPrefix(modelPath), inputSize, threads)
                    : mRuntime.yolo.createOnnx(mRuntime.files.path(modelPath), inputSize, threads);
        } else if ("opencv".equals(normalized)) {
            detector = isAssetPath(modelPath)
                    ? mRuntime.yolo.createOpenCvFromAssets(stripAssetPrefix(modelPath), inputSize, threads)
                    : mRuntime.yolo.createOpenCv(mRuntime.files.path(modelPath), inputSize, threads);
        } else {
            throw new IllegalArgumentException("QuickJS YOLO 不支持的 backend: " + normalized);
        }
        long handle = mNextYoloHandle.getAndIncrement();
        mYoloSessions.put(handle, new YoloSession(normalized, detector));
        return handle;
    }

    public float[] detectYolo(long sessionHandle, ByteBuffer rgba,
                              int width, int height, int rowStride,
                              float confidence, float nmsThreshold) {
        YoloSession session = mYoloSessions.get(sessionHandle);
        if (session == null) {
            throw new IllegalStateException("QuickJS YOLO detector 已关闭");
        }
        AutoCloseable detector = session.detector;
        if ("onnx".equals(session.backend)) {
            return ((OnnxYoloDetector) detector).detectRgba(rgba, width, height, rowStride,
                    confidence, nmsThreshold);
        }
        if ("opencv".equals(session.backend)) {
            return ((OpenCvYoloDetector) detector).detectRgba(rgba, width, height, rowStride,
                    confidence, nmsThreshold);
        }
        return ((Yolo.Detector) detector).detectRgba(rgba, width, height, rowStride,
                confidence, nmsThreshold);
    }

    public boolean closeYolo(long sessionHandle) {
        YoloSession session = mYoloSessions.remove(sessionHandle);
        if (session == null) return false;
        try {
            session.detector.close();
        } catch (Throwable error) {
            Log.w("QuickJsHostBridge", "Cannot close YOLO detector", error);
        }
        return true;
    }

    private static String normalizeQuickJsBackend(String backend) {
        if (backend == null) return "ncnn";
        String normalized = backend.trim().toLowerCase();
        if (normalized.isEmpty() || "cpu".equals(normalized)) return "ncnn";
        if ("ort".equals(normalized) || "onnxruntime".equals(normalized)
                || "onnx-runtime".equals(normalized)) return "onnx";
        if ("opencv-dnn".equals(normalized) || "opencv5".equals(normalized)
                || "dnn".equals(normalized)) return "opencv";
        return normalized;
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

    // ---- files whitelist ----

    public boolean filesExists(String path) {
        return mRuntime.files.exists(path);
    }

    public boolean filesIsFile(String path) {
        return mRuntime.files.isFile(path);
    }

    public boolean filesIsDir(String path) {
        return mRuntime.files.isDir(path);
    }

    public String filesRead(String path) {
        return mRuntime.files.read(path);
    }

    public void filesWrite(String path, String text) {
        mRuntime.files.write(path, text);
    }

    public void filesAppend(String path, String text) {
        mRuntime.files.append(path, text);
    }

    public boolean filesCreate(String path) {
        return mRuntime.files.createWithDirs(path);
    }

    public boolean filesEnsureDir(String path) {
        return mRuntime.files.ensureDir(path);
    }

    public String filesListDir(String path) {
        String[] children = mRuntime.files.listDir(path);
        JSONArray array = new JSONArray();
        if (children != null) {
            for (String child : children) {
                array.put(child);
            }
        }
        return array.toString();
    }

    public boolean filesRemove(String path) {
        return mRuntime.files.remove(path);
    }

    public boolean filesRename(String path, String newName) {
        return mRuntime.files.rename(path, newName);
    }

    public boolean filesCopy(String source, String target) {
        return mRuntime.files.copy(source, target);
    }

    public boolean filesMove(String source, String target) {
        return mRuntime.files.move(source, target);
    }

    public String filesCwd() {
        return mRuntime.files.cwd();
    }

    public String filesGetSdcardPath() {
        return mRuntime.files.getSdcardPath();
    }

    // ---- http whitelist ----

    public String httpRequest(String method, String url, String headersJson,
                              String body, String contentType) {
        if (url == null || url.isEmpty()) {
            throw new IllegalArgumentException("http url is required");
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://" + url;
        }
        String methodUpper = method == null || method.isEmpty()
                ? "GET" : method.toUpperCase(Locale.US);
        Request.Builder builder = new Request.Builder().url(url);
        try {
            JSONObject headers = new JSONObject(headersJson == null ? "{}" : headersJson);
            JSONArray names = headers.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String name = names.getString(i);
                    Object value = headers.get(name);
                    if (value instanceof JSONArray) {
                        JSONArray values = (JSONArray) value;
                        for (int j = 0; j < values.length(); j++) {
                            builder.header(name, String.valueOf(values.get(j)));
                        }
                    } else {
                        builder.header(name, String.valueOf(value));
                    }
                }
            }
        } catch (JSONException ignored) {
            // invalid header payload: proceed without headers
        }
        RequestBody requestBody = null;
        if (body != null && !body.isEmpty() && !"GET".equals(methodUpper) && !"HEAD".equals(methodUpper)) {
            MediaType mediaType = contentType == null || contentType.isEmpty()
                    ? null : MediaType.parse(contentType);
            requestBody = RequestBody.create(mediaType, body);
        }
        builder.method(methodUpper, requestBody);
        try (Response response = mHttpClient.client().newCall(builder.build()).execute()) {
            JSONObject result = new JSONObject();
            result.put("statusCode", response.code());
            result.put("statusMessage", response.message() == null ? "" : response.message());
            result.put("method", methodUpper);
            result.put("url", response.request().url().toString());
            JSONObject responseHeaders = new JSONObject();
            Headers headers = response.headers();
            for (int i = 0; i < headers.size(); i++) {
                String name = headers.name(i);
                String value = headers.value(i);
                if (responseHeaders.has(name)) {
                    Object existing = responseHeaders.get(name);
                    JSONArray values = existing instanceof JSONArray
                            ? (JSONArray) existing : new JSONArray().put(existing);
                    responseHeaders.put(name, values.put(value));
                } else {
                    responseHeaders.put(name, value);
                }
            }
            result.put("headers", responseHeaders);
            ResponseBody responseBody = response.body();
            result.put("body", responseBody == null ? "" : responseBody.string());
            result.put("contentType", responseBody == null || responseBody.contentType() == null
                    ? "" : responseBody.contentType().toString());
            return result.toString();
        } catch (IOException error) {
            throw new UncheckedIOException(new IOException(
                    "QuickJS http request failed: " + error.getMessage(), error));
        } catch (JSONException error) {
            throw new IllegalStateException("Unable to serialize http response", error);
        }
    }

    @Override
    public void close() {
        drawClose();
        for (YoloSession session : new ArrayList<>(mYoloSessions.values())) {
            try {
                session.detector.close();
            } catch (Throwable error) {
                Log.w("QuickJsHostBridge", "Cannot close YOLO detector", error);
            }
        }
        mYoloSessions.clear();
    }

    // ---- drawing overlay whitelist ----

    private volatile QuickJsOverlay mOverlay;

    public boolean drawCreate() {
        Context context = mRuntime.uiHandler.getContext();
        if (context == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            return false;
        }
        try {
            // Multiple engine runs can leave overlay windows behind when a script is
            // force-stopped before the host bridge closes. Make the overlay unique:
            // reap every previous overlay before creating the new one.
            QuickJsOverlay.closeAll();
            mOverlay = new QuickJsOverlay(context);
            if (!mOverlay.show()) {
                mOverlay = null;
                return false;
            }
            return true;
        } catch (Throwable error) {
            Log.w("QuickJsHostBridge", "Cannot create drawing overlay", error);
            mOverlay = null;
            return false;
        }
    }

    public void drawUpdate(String detectionsJson, String statsText) {
        QuickJsOverlay overlay = mOverlay;
        if (overlay == null) return;
        try {
            JSONArray array = new JSONArray(detectionsJson == null ? "[]" : detectionsJson);
            List<Detection> detections = new ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                detections.add(new Detection(
                        (float) item.optDouble("x1"), (float) item.optDouble("y1"),
                        (float) item.optDouble("x2"), (float) item.optDouble("y2"),
                        item.optString("label", ""),
                        (float) item.optDouble("score")));
            }
            overlay.update(detections, statsText == null ? "" : statsText);
        } catch (JSONException ignored) {
            // malformed payload: keep the previous frame
        }
    }

    public void drawClose() {
        QuickJsOverlay overlay = mOverlay;
        mOverlay = null;
        if (overlay != null) overlay.close();
    }

    private static final class Detection {
        final float x1;
        final float y1;
        final float x2;
        final float y2;
        final String label;
        final float score;

        Detection(float x1, float y1, float x2, float y2, String label, float score) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.label = label;
            this.score = score;
        }
    }

    private static final class QuickJsOverlay {
        private static final java.util.Set<QuickJsOverlay> sAllOverlays =
                Collections.synchronizedSet(new java.util.HashSet<>());

        private final WindowManager mWindowManager;
        private final OverlayView mView;

        QuickJsOverlay(Context context) {
            mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            mView = new OverlayView(context);
            sAllOverlays.add(this);
        }

        static void closeAll() {
            java.util.List<QuickJsOverlay> overlays;
            synchronized (sAllOverlays) {
                overlays = new ArrayList<>(sAllOverlays);
            }
            for (QuickJsOverlay overlay : overlays) {
                overlay.close();
            }
        }

        boolean show() {
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    android.graphics.PixelFormat.TRANSLUCENT);
            mWindowManager.addView(mView, params);
            return true;
        }

        void update(List<Detection> detections, String statsText) {
            mView.setData(detections, statsText);
        }

        void close() {
            sAllOverlays.remove(this);
            try {
                mWindowManager.removeViewImmediate(mView);
            } catch (Throwable ignored) {
            }
        }
    }

    private static final class OverlayView extends View {
        private final Paint mBoxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mLabelBgPaint = new Paint();
        private final Paint mStatsPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mStatsBgPaint = new Paint();
        private final Object mLock = new Object();
        private List<Detection> mDetections = Collections.emptyList();
        private String mStatsText = "";

        OverlayView(Context context) {
            super(context);
            mBoxPaint.setStyle(Paint.Style.STROKE);
            mBoxPaint.setStrokeWidth(4f);
            mBoxPaint.setColor(Color.parseColor("#33FF66"));
            mLabelPaint.setTextSize(30f);
            mLabelPaint.setColor(Color.parseColor("#FFD54F"));
            mLabelBgPaint.setColor(0xCC000000);
            mStatsPaint.setTextSize(28f);
            mStatsPaint.setColor(Color.WHITE);
            mStatsBgPaint.setColor(0x99000000);
        }

        void setData(List<Detection> detections, String statsText) {
            synchronized (mLock) {
                mDetections = detections;
                mStatsText = statsText;
            }
            postInvalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            List<Detection> detections;
            String statsText;
            synchronized (mLock) {
                detections = mDetections;
                statsText = mStatsText;
            }
            if (!statsText.isEmpty()) {
                float textWidth = mStatsPaint.measureText(statsText);
                canvas.drawRoundRect(new RectF(10, 10, 24 + textWidth, 54), 12, 12, mStatsBgPaint);
                canvas.drawText(statsText, 24, 42, mStatsPaint);
            }
            for (Detection detection : detections) {
                canvas.drawRect(new RectF(detection.x1, detection.y1,
                        detection.x2, detection.y2), mBoxPaint);
                String caption = detection.label + " " + Math.round(detection.score * 100) + "%";
                float captionWidth = mLabelPaint.measureText(caption);
                float top = Math.max(0f, detection.y1 - 40f);
                canvas.drawRoundRect(new RectF(detection.x1, top,
                        detection.x1 + captionWidth + 14, top + 36), 8, 8, mLabelBgPaint);
                canvas.drawText(caption, detection.x1 + 7, top + 26, mLabelPaint);
            }
        }
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
