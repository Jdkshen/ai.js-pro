package com.stardust.autojs.runtime.api;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;

import com.stardust.autojs.core.image.ImageWrapper;
import com.stardust.autojs.core.opencv.OpenCVHelper;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.dnn.Dnn;
import org.opencv.dnn.Net;
import org.opencv.imgproc.Imgproc;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Fixed-shape YOLO26 ONNX detector backed by the bundled OpenCV 5.0 DNN engine. */
public final class OpenCvYoloDetector implements AutoCloseable {

    private final Yolo mOwner;
    private final int mInputWidth;
    private final int mInputHeight;
    private final Mat mSource = new Mat();
    private final Mat mResizedRgba = new Mat();
    private final Mat mResizedRgb = new Mat();
    private final Mat mLetterbox = new Mat();
    private Net mNet;
    private boolean mClosed;

    OpenCvYoloDetector(Yolo owner, Context context, String modelPath, int inputSize, int threads) {
        this(owner, context, modelPath, inputSize, inputSize, threads);
    }

    OpenCvYoloDetector(Yolo owner, Context context, String modelPath, int inputWidth, int inputHeight, int threads) {
        if (inputWidth < 32 || inputHeight < 32) throw new IllegalArgumentException("inputWidth/inputHeight 不能小于 32");
        mOwner = owner;
        mInputWidth = inputWidth;
        mInputHeight = inputHeight;
        ensureOpenCv(context);
        try {
            Core.setNumThreads(Math.max(1, Math.min(threads, 8)));
            // 引擎选择（实测骁龙 870）：
            //  - ENGINE_AUTO 默认走新图引擎（KleidiCV 优化 CPU 路径）≈ 106-111ms，最快
            //  - ENGINE_CLASSIC 经典引擎 ≈ 134ms（旧卷积路径，慢）
            //  - 新图引擎不支持 setPreferableTarget（仅 CPU）；OpenCL 在 Adreno 上为负优化
            //    （OCL 仅针对 Intel GPU 优化，实测 FP32 686ms vs CPU 106ms），故不设 target。
            mNet = Dnn.readNetFromONNX(modelPath, Dnn.ENGINE_AUTO);
            if (mNet == null || mNet.empty()) {
                throw new IllegalStateException("OpenCV 无法读取 ONNX 模型");
            }
            mNet.setPreferableBackend(Dnn.DNN_BACKEND_OPENCV);
            // 新图引擎忽略 setPreferableTarget（默认 CPU），无需也不应设置。
            String targetName = "DNN_TARGET_CPU (new graph engine)";
            try {
                android.util.Log.i("OpenCvYoloDetector", "DNN target=" + targetName
                        + " | OpenCL built=" + isOpenClInBuild()
                        + " | buildInfo=" + Core.getBuildInformation().replace("\n", " | "));
            } catch (Throwable ignored) {
                // 日志失败不影响运行
            }
        } catch (Throwable error) {
            close();
            throw new IllegalStateException("OpenCV DNN 模型初始化失败：" + error.getMessage(), error);
        }
    }

    public static boolean isRuntimeAvailable() {
        try {
            Class.forName("org.opencv.dnn.Dnn");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static String getUnavailableReason() {
        return isRuntimeAvailable() ? "" : "APK 未包含 OpenCV DNN";
    }

    public static String getRuntimeVersion() {
        return isRuntimeAvailable() ? Core.VERSION : "unavailable";
    }

    private static String openClSegment() {
        try {
            String info = Core.getBuildInformation();
            int idx = info.indexOf("OpenCL");
            if (idx < 0) return "n/a";
            int end = info.indexOf('\n', idx);
            return end < 0 ? info.substring(idx) : info.substring(idx, end);
        } catch (Throwable ignored) {
            return "n/a";
        }
    }

    private static boolean isOpenClInBuild() {
        try {
            String info = Core.getBuildInformation();
            return info.contains("OpenCL") && !info.contains("OpenCL:        NO");
        } catch (Throwable ignored) {
            return false;
        }
    }


    private static void ensureOpenCv(Context context) {
        if (OpenCVHelper.isInitialized()) return;
        CountDownLatch latch = new CountDownLatch(1);
        OpenCVHelper.initIfNeeded(context, latch::countDown);
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("OpenCV 初始化超时");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OpenCV 初始化被中断", error);
        }
        if (!OpenCVHelper.isInitialized()) {
            throw new IllegalStateException("OpenCV 5.0 初始化失败");
        }
    }

    public synchronized float[] detect(ImageWrapper image, float confidence, float nmsThreshold) {
        if (mClosed || mNet == null) throw new IllegalStateException("YOLO detector 已关闭");
        if (image == null) throw new IllegalArgumentException("image == null");
        validateThresholds(confidence, nmsThreshold);

        Bitmap bitmap = image.getBitmap();
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width < 2 || height < 2) throw new IllegalArgumentException("图片尺寸无效");
        Utils.bitmapToMat(bitmap, mSource);
        return detectFromSource(width, height, confidence, nmsThreshold);
    }

    public synchronized float[] detectRgba(ByteBuffer rgba, int width, int height, int rowStride,
                                           int regionX, int regionY, int regionWidth, int regionHeight,
                                           float confidence, float nmsThreshold) {
        if (mClosed || mNet == null) throw new IllegalStateException("YOLO detector 已关闭");
        if (rgba == null || !rgba.isDirect()) {
            throw new IllegalArgumentException("YOLO NativeFrame 必须使用 DirectByteBuffer");
        }
        if (width < 2 || height < 2 || rowStride < width * 4) {
            throw new IllegalArgumentException("YOLO NativeFrame RGBA 布局无效");
        }
        Yolo.validateRegion(width, height, regionX, regionY, regionWidth, regionHeight);
        validateThresholds(confidence, nmsThreshold);
        regionWidth = Math.min(regionWidth, width - regionX);
        regionHeight = Math.min(regionHeight, height - regionY);
        mSource.create(regionHeight, regionWidth, CvType.CV_8UC4);
        ByteBuffer duplicate = rgba.duplicate();
        duplicate.rewind();
        byte[] row = new byte[regionWidth * 4];
        for (int y = 0; y < regionHeight; y++) {
            duplicate.position((regionY + y) * rowStride + regionX * 4);
            duplicate.get(row);
            mSource.put(y, 0, row);
        }
        float[] packed = detectFromSource(regionWidth, regionHeight, confidence, nmsThreshold);
        if (regionX != 0 || regionY != 0) {
            for (int i = 2; i + 5 < packed.length; i += 6) {
                packed[i] += regionX;
                packed[i + 1] += regionY;
                packed[i + 2] += regionX;
                packed[i + 3] += regionY;
            }
        }
        return packed;
    }

    private float[] detectFromSource(int width, int height, float confidence, float nmsThreshold) {
        Mat blob = null;
        Mat output = null;
        Mat rows = null;
        try {
            long preprocessStarted = SystemClock.elapsedRealtimeNanos();
            float scale = Math.min(mInputWidth / (float) width, mInputHeight / (float) height);
            int resizedWidth = Math.max(1, Math.round(width * scale));
            int resizedHeight = Math.max(1, Math.round(height * scale));
            int padX = (mInputWidth - resizedWidth) / 2;
            int padY = (mInputHeight - resizedHeight) / 2;

            Imgproc.resize(mSource, mResizedRgba, new Size(resizedWidth, resizedHeight),
                    0.0, 0.0, Imgproc.INTER_LINEAR);
            Imgproc.cvtColor(mResizedRgba, mResizedRgb, Imgproc.COLOR_RGBA2RGB);
            mLetterbox.create(mInputHeight, mInputWidth, CvType.CV_8UC3);
            mLetterbox.setTo(new Scalar(114, 114, 114));
            Mat region = mLetterbox.submat(new Rect(padX, padY, resizedWidth, resizedHeight));
            try {
                mResizedRgb.copyTo(region);
            } finally {
                region.release();
            }
            blob = Dnn.blobFromImage(mLetterbox, 1.0 / 255.0,
                    new Size(mInputWidth, mInputHeight), new Scalar(0), false, false, CvType.CV_32F);
            float preprocessMs = elapsedMs(preprocessStarted);

            mNet.setInput(blob);
            long inferenceStarted = SystemClock.elapsedRealtimeNanos();
            output = mNet.forward();
            float inferenceMs = elapsedMs(inferenceStarted);
            int rowCount = (int) (output.total() / 6L);
            rows = output.reshape(1, rowCount);
            float[] raw = new float[rowCount * 6];
            rows.get(0, 0, raw);
            return decode(raw, rowCount, width, height, scale, padX, padY,
                    confidence, preprocessMs, inferenceMs);
        } catch (Throwable error) {
            throw new IllegalStateException("OpenCV 5.0 DNN 推理失败：" + error.getMessage(), error);
        } finally {
            if (rows != null) rows.release();
            if (output != null) output.release();
            if (blob != null) blob.release();
        }
    }

    private static float[] decode(float[] raw, int rowCount, int width, int height,
                                  float scale, int padX, int padY, float confidence,
                                  float preprocessMs, float inferenceMs) {
        float[] packed = new float[2 + rowCount * 6];
        packed[0] = preprocessMs;
        packed[1] = inferenceMs;
        int write = 2;
        for (int row = 0; row < rowCount; row++) {
            int base = row * 6;
            float score = raw[base + 4];
            if (score < confidence) continue;
            float x1 = clamp((raw[base] - padX) / scale, 0f, width);
            float y1 = clamp((raw[base + 1] - padY) / scale, 0f, height);
            float x2 = clamp((raw[base + 2] - padX) / scale, 0f, width);
            float y2 = clamp((raw[base + 3] - padY) / scale, 0f, height);
            if (x2 <= x1 || y2 <= y1) continue;
            packed[write++] = x1;
            packed[write++] = y1;
            packed[write++] = x2;
            packed[write++] = y2;
            packed[write++] = score;
            packed[write++] = raw[base + 5];
        }
        return Arrays.copyOf(packed, write);
    }

    private static void validateThresholds(float confidence, float nmsThreshold) {
        if (confidence < 0.0f || confidence > 1.0f) {
            throw new IllegalArgumentException("confidence 必须在 0~1 之间");
        }
        if (nmsThreshold < 0.0f || nmsThreshold > 1.0f) {
            throw new IllegalArgumentException("nms 必须在 0~1 之间");
        }
    }

    private static float elapsedMs(long started) {
        return (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0f;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    public synchronized boolean isClosed() {
        return mClosed;
    }

    @Override
    public synchronized void close() {
        if (mClosed) return;
        mClosed = true;
        mNet = null;
        mSource.release();
        mResizedRgba.release();
        mResizedRgb.release();
        mLetterbox.release();
        mOwner.unregister(this);
    }
}
