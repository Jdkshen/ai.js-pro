package com.stardust.autojs.runtime.api;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.util.Log;

import com.stardust.autojs.core.image.ImageWrapper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** YOLO runtime exposed to AI.js Pro scripts with ncnn, ONNX Runtime and OpenCV DNN backends. */
public final class Yolo implements AutoCloseable {

    private static final String TAG = "AutoJsYolo";
    private static final String LIBRARY_NAME = "autojs_yolo";
    private static final boolean NATIVE_AVAILABLE;
    private static final String NATIVE_ERROR;

    static {
        boolean available = false;
        String error = null;
        if (!supportsArm64()) {
            error = "YOLO 目前仅支持 arm64-v8a 设备";
        } else {
            try {
                System.loadLibrary(LIBRARY_NAME);
                available = true;
                Log.i(TAG, "ncnn " + nativeVersion() + " loaded; backend=CPU; ABI=arm64-v8a");
            } catch (Throwable throwable) {
                error = throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
                Log.e(TAG, "Cannot load " + LIBRARY_NAME, throwable);
            }
        }
        NATIVE_AVAILABLE = available;
        NATIVE_ERROR = error;
    }

    private final Context mContext;
    private final Set<AutoCloseable> mDetectors = Collections.synchronizedSet(new HashSet<>());

    public Yolo(Context context) {
        mContext = context.getApplicationContext();
    }

    public boolean isAvailable() {
        return NATIVE_AVAILABLE;
    }

    public String getUnavailableReason() {
        return NATIVE_AVAILABLE ? "" : NATIVE_ERROR;
    }

    public String getVersion() {
        return NATIVE_AVAILABLE ? nativeVersion() : "unavailable";
    }

    public boolean isAvailable(String backend) {
        String normalized = normalizeBackend(backend);
        if ("ncnn".equals(normalized)) return NATIVE_AVAILABLE;
        if ("onnx".equals(normalized)) return OnnxYoloDetector.isRuntimeAvailable();
        if ("opencv".equals(normalized)) return OpenCvYoloDetector.isRuntimeAvailable();
        return false;
    }

    public String getUnavailableReason(String backend) {
        String normalized = normalizeBackend(backend);
        if ("ncnn".equals(normalized)) return NATIVE_AVAILABLE ? "" : NATIVE_ERROR;
        if ("onnx".equals(normalized)) return OnnxYoloDetector.getUnavailableReason();
        if ("opencv".equals(normalized)) return OpenCvYoloDetector.getUnavailableReason();
        return "不支持的 YOLO 后端：" + backend;
    }

    public String getVersion(String backend) {
        String normalized = normalizeBackend(backend);
        if ("ncnn".equals(normalized)) return getVersion();
        if ("onnx".equals(normalized)) return OnnxYoloDetector.getRuntimeVersion();
        if ("opencv".equals(normalized)) return OpenCvYoloDetector.getRuntimeVersion();
        return "unavailable";
    }

    public Detector create(String paramPath, String binPath, int inputSize, int threads) {
        requireNative();
        File param = requireReadableFile(paramPath, "param");
        File bin = requireReadableFile(binPath, "bin");
        return register(new Detector(this, param.getAbsolutePath(), bin.getAbsolutePath(),
                inputSize, threads));
    }

    public Detector createFromAssets(String paramAsset, String binAsset, int inputSize, int threads) {
        requireNative();
        try {
            File modelDirectory = new File(mContext.getCacheDir(), "autojs-yolo-models");
            if (!modelDirectory.isDirectory() && !modelDirectory.mkdirs()) {
                throw new IOException("无法创建 YOLO 模型缓存目录");
            }
            String key = Integer.toHexString((paramAsset + "\n" + binAsset).hashCode());
            File param = new File(modelDirectory, key + ".param");
            File bin = new File(modelDirectory, key + ".bin");
            copyAsset(paramAsset, param);
            copyAsset(binAsset, bin);
            return register(new Detector(this, param.getAbsolutePath(), bin.getAbsolutePath(),
                    inputSize, threads));
        } catch (IOException error) {
            throw new IllegalStateException("准备 YOLO 模型失败：" + error.getMessage(), error);
        }
    }

    public OnnxYoloDetector createOnnx(String modelPath, int inputSize, int threads) {
        File model = requireReadableFile(modelPath, "ONNX");
        return register(new OnnxYoloDetector(this, model.getAbsolutePath(), inputSize, threads));
    }

    public OnnxYoloDetector createOnnxFromAssets(String modelAsset, int inputSize, int threads) {
        return register(new OnnxYoloDetector(this, prepareAssetModel(modelAsset), inputSize, threads));
    }

    public OpenCvYoloDetector createOpenCv(String modelPath, int inputSize, int threads) {
        File model = requireReadableFile(modelPath, "ONNX");
        return register(new OpenCvYoloDetector(this, mContext, model.getAbsolutePath(), inputSize, threads));
    }

    public OpenCvYoloDetector createOpenCvFromAssets(String modelAsset, int inputSize, int threads) {
        return register(new OpenCvYoloDetector(this, mContext, prepareAssetModel(modelAsset), inputSize, threads));
    }

    private String prepareAssetModel(String modelAsset) {
        if (modelAsset == null || modelAsset.trim().isEmpty()) {
            throw new IllegalArgumentException("YOLO ONNX 模型资源路径为空");
        }
        try {
            File modelDirectory = new File(mContext.getCacheDir(), "autojs-yolo-models");
            if (!modelDirectory.isDirectory() && !modelDirectory.mkdirs()) {
                throw new IOException("无法创建 YOLO 模型缓存目录");
            }
            File model = new File(modelDirectory,
                    Integer.toHexString(modelAsset.hashCode()) + "-" + new File(modelAsset).getName());
            copyAsset(modelAsset, model);
            return model.getAbsolutePath();
        } catch (IOException error) {
            throw new IllegalStateException("准备 YOLO ONNX 模型失败：" + error.getMessage(), error);
        }
    }

    private <T extends AutoCloseable> T register(T detector) {
        mDetectors.add(detector);
        return detector;
    }

    void unregister(AutoCloseable detector) {
        mDetectors.remove(detector);
    }

    private static String normalizeBackend(String backend) {
        if (backend == null) return "ncnn";
        String normalized = backend.trim().toLowerCase();
        if (normalized.isEmpty() || "cpu".equals(normalized)) return "ncnn";
        if ("ort".equals(normalized) || "onnxruntime".equals(normalized)
                || "onnx-runtime".equals(normalized)) return "onnx";
        if ("opencv-dnn".equals(normalized) || "opencv5".equals(normalized)
                || "dnn".equals(normalized)) return "opencv";
        return normalized;
    }

    static boolean isNativeRuntimeAvailable() {
        return NATIVE_AVAILABLE;
    }

    static String getNativeRuntimeError() {
        return NATIVE_ERROR;
    }

    private void requireNative() {
        if (!NATIVE_AVAILABLE) {
            throw new UnsupportedOperationException(NATIVE_ERROR);
        }
    }

    private static File requireReadableFile(String path, String type) {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("YOLO " + type + " 模型路径为空");
        }
        File file = new File(path);
        if (!file.isFile() || !file.canRead()) {
            throw new IllegalArgumentException("YOLO " + type + " 模型不可读：" + path);
        }
        return file;
    }

    private void copyAsset(String assetPath, File target) throws IOException {
        try (InputStream input = mContext.getAssets().open(assetPath);
             FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[128 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            output.getFD().sync();
        }
    }

    private static boolean supportsArm64() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false;
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi)) return true;
        }
        return false;
    }

    @Override
    public void close() {
        List<AutoCloseable> detectors;
        synchronized (mDetectors) {
            detectors = new ArrayList<>(mDetectors);
        }
        for (AutoCloseable detector : detectors) {
            try {
                detector.close();
            } catch (Exception error) {
                Log.w(TAG, "Cannot close YOLO detector", error);
            }
        }
        mDetectors.clear();
    }

    public static final class Detector implements AutoCloseable {
        private final Yolo mOwner;
        private long mHandle;

        private Detector(Yolo owner, String paramPath, String binPath, int inputSize, int threads) {
            if (inputSize < 32) throw new IllegalArgumentException("inputSize 不能小于 32");
            mOwner = owner;
            mHandle = nativeCreate(paramPath, binPath, inputSize, Math.max(1, Math.min(threads, 8)));
            if (mHandle == 0) throw new IllegalStateException("YOLO 模型初始化失败");
        }

        public synchronized float[] detect(ImageWrapper image, float confidence, float nmsThreshold) {
            if (mHandle == 0) throw new IllegalStateException("YOLO detector 已关闭");
            if (image == null) throw new IllegalArgumentException("image == null");
            if (confidence < 0.0f || confidence > 1.0f) {
                throw new IllegalArgumentException("confidence 必须在 0~1 之间");
            }
            if (nmsThreshold < 0.0f || nmsThreshold > 1.0f) {
                throw new IllegalArgumentException("nms 必须在 0~1 之间");
            }
            Bitmap bitmap = image.getBitmap();
            Bitmap converted = null;
            if (bitmap.getConfig() != Bitmap.Config.ARGB_8888) {
                converted = bitmap.copy(Bitmap.Config.ARGB_8888, false);
                bitmap = converted;
            }
            try {
                return nativeDetectBitmap(mHandle, bitmap, confidence, nmsThreshold);
            } finally {
                if (converted != null) converted.recycle();
            }
        }

        public synchronized float[] detectRgba(ByteBuffer rgba, int width, int height, int rowStride,
                                               int regionX, int regionY, int regionWidth, int regionHeight,
                                               float confidence, float nmsThreshold) {
            if (mHandle == 0) throw new IllegalStateException("YOLO detector 已关闭");
            if (rgba == null || !rgba.isDirect()) {
                throw new IllegalArgumentException("YOLO NativeFrame 必须使用 DirectByteBuffer");
            }
            if (width < 2 || height < 2 || rowStride < width * 4) {
                throw new IllegalArgumentException("YOLO NativeFrame RGBA 布局无效");
            }
            validateRegion(width, height, regionX, regionY, regionWidth, regionHeight);
            if (confidence < 0.0f || confidence > 1.0f) {
                throw new IllegalArgumentException("confidence 必须在 0~1 之间");
            }
            if (nmsThreshold < 0.0f || nmsThreshold > 1.0f) {
                throw new IllegalArgumentException("nms 必须在 0~1 之间");
            }
            return nativeDetectRgba(mHandle, rgba, width, height, rowStride,
                    regionX, regionY, regionWidth, regionHeight,
                    confidence, nmsThreshold);
        }

        public synchronized boolean isClosed() {
            return mHandle == 0;
        }

        @Override
        public synchronized void close() {
            if (mHandle == 0) return;
            nativeRelease(mHandle);
            mHandle = 0;
            mOwner.unregister(this);
        }

        @Override
        protected void finalize() throws Throwable {
            try {
                close();
            } finally {
                super.finalize();
            }
        }
    }

    private static native String nativeVersion();
    private static native long nativeCreate(String paramPath, String binPath, int inputSize, int threads);
    private static native float[] nativeDetectBitmap(long handle, Bitmap bitmap,
                                                       float confidence, float nmsThreshold);
    private static native float[] nativeDetectRgba(long handle, ByteBuffer rgba,
                                                     int width, int height, int rowStride,
                                                     int regionX, int regionY, int regionWidth, int regionHeight,
                                                     float confidence, float nmsThreshold);
    private static native void nativeRelease(long handle);

    static void validateRegion(int width, int height, int rx, int ry, int rw, int rh) {
        if (rx < 0 || ry < 0 || rw <= 0 || rh <= 0 || rx + rw > width || ry + rh > height) {
            throw new IllegalArgumentException("region [x,y,w,h] 超出画面范围");
        }
    }
}
