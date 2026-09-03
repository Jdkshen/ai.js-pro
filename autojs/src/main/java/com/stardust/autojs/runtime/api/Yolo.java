package com.stardust.autojs.runtime.api;

import android.content.Context;
import android.util.Log;

import com.stardust.autojs.core.image.ImageWrapper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** YOLO runtime exposed to AI.js Pro scripts, backed by the bundled OpenCV 5.0 DNN engine. */
public final class Yolo implements AutoCloseable {

    private static final String TAG = "AutoJsYolo";

    private final Context mContext;
    private final Set<AutoCloseable> mDetectors = Collections.synchronizedSet(new HashSet<>());

    public Yolo(Context context) {
        mContext = context.getApplicationContext();
    }

    public boolean isAvailable() {
        return OpenCvYoloDetector.isRuntimeAvailable();
    }

    public String getUnavailableReason() {
        return OpenCvYoloDetector.getUnavailableReason();
    }

    public String getVersion() {
        return OpenCvYoloDetector.getRuntimeVersion();
    }

    public boolean isAvailable(String backend) {
        String normalized = normalizeBackend(backend);
        if ("opencv".equals(normalized)) return OpenCvYoloDetector.isRuntimeAvailable();
        return false;
    }

    public String getUnavailableReason(String backend) {
        String normalized = normalizeBackend(backend);
        if ("opencv".equals(normalized)) return OpenCvYoloDetector.getUnavailableReason();
        return "不支持的 YOLO 后端：" + backend;
    }

    public String getVersion(String backend) {
        String normalized = normalizeBackend(backend);
        if ("opencv".equals(normalized)) return OpenCvYoloDetector.getRuntimeVersion();
        return "unavailable";
    }

    public OpenCvYoloDetector createOpenCv(String modelPath, int inputSize, int threads) {
        return createOpenCv(modelPath, inputSize, inputSize, threads);
    }

    public OpenCvYoloDetector createOpenCv(String modelPath, int inputWidth, int inputHeight, int threads) {
        File model = requireReadableFile(modelPath, "ONNX");
        return register(new OpenCvYoloDetector(this, mContext, model.getAbsolutePath(), inputWidth, inputHeight, threads));
    }

    public OpenCvYoloDetector createOpenCvFromAssets(String modelAsset, int inputSize, int threads) {
        return createOpenCvFromAssets(modelAsset, inputSize, inputSize, threads);
    }

    public OpenCvYoloDetector createOpenCvFromAssets(String modelAsset, int inputWidth, int inputHeight, int threads) {
        return register(new OpenCvYoloDetector(this, mContext, prepareAssetModel(modelAsset), inputWidth, inputHeight, threads));
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
        if (backend == null) return "opencv";
        String normalized = backend.trim().toLowerCase();
        if (normalized.isEmpty() || "cpu".equals(normalized)) return "opencv";
        if ("opencv-dnn".equals(normalized) || "opencv5".equals(normalized)
                || "dnn".equals(normalized)) return "opencv";
        return normalized;
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

    static void validateRegion(int width, int height, int rx, int ry, int rw, int rh) {
        if (rx < 0 || ry < 0 || rw <= 0 || rh <= 0 || rx + rw > width || ry + rh > height) {
            throw new IllegalArgumentException("region [x,y,w,h] 超出画面范围");
        }
    }
}
