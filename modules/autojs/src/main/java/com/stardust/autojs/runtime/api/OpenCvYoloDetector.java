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
    private final Mat mLetterbox;
    // 预分配的 letterbox 填充色，避免每帧创建 Scalar 对象
    private static final Scalar LETTERBOX_GRAY = new Scalar(114, 114, 114);
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
        // 预分配 letterbox Mat，尺寸固定后不再重建
        mLetterbox = new Mat(inputHeight, inputWidth, CvType.CV_8UC3);
        mLetterbox.setTo(LETTERBOX_GRAY);
        ensureOpenCv(context);
        try {
            Core.setNumThreads(Math.max(1, Math.min(threads, 8)));
            mNet = Dnn.readNetFromONNX(modelPath, Dnn.ENGINE_AUTO);
            if (mNet == null || mNet.empty()) {
                throw new IllegalStateException("OpenCV 无法读取 ONNX 模型");
            }
            mNet.setPreferableBackend(Dnn.DNN_BACKEND_OPENCV);
            String targetName = "DNN_TARGET_CPU (new graph engine)";
            try {
                android.util.Log.i("OpenCvYoloDetector", "OpenCV=" + Core.VERSION
                        + " | target=" + targetName
                        + " | threads=" + Math.max(1, Math.min(threads, 8))
                        + " | OpenCL built=" + isOpenClInBuild());
            } catch (Throwable ignored) {
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
        return detectFromSource(mSource, width, height, confidence, nmsThreshold);
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
        long byteOffset = (long) regionY * rowStride + (long) regionX * 4L;
        long requiredBytes = (long) (regionHeight - 1) * rowStride + (long) regionWidth * 4L;
        if (byteOffset < 0 || requiredBytes <= 0 || byteOffset + requiredBytes > rgba.capacity()) {
            throw new IllegalArgumentException("YOLO NativeFrame RGBA 区域超出缓冲区");
        }

        // OpenCV 5 can wrap a direct ByteBuffer without copying its pixels. A sliced buffer is
        // required here because JNI's direct-buffer address is the slice base, not its position.
        ByteBuffer window = rgba.duplicate();
        window.position((int) byteOffset);
        window.limit((int) (byteOffset + requiredBytes));
        window = window.slice();
        Mat sourceView = new Mat(regionHeight, regionWidth, CvType.CV_8UC4, window, rowStride);
        float[] packed;
        try {
            packed = detectFromSource(sourceView, regionWidth, regionHeight,
                    confidence, nmsThreshold);
        } finally {
            // Releases only the Mat header; NativeFrameStore still owns the external pixels.
            sourceView.release();
        }
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

    private float[] detectFromSource(Mat source, int width, int height,
                                     float confidence, float nmsThreshold) {
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

            Imgproc.resize(source, mResizedRgba, new Size(resizedWidth, resizedHeight),
                    0.0, 0.0, Imgproc.INTER_LINEAR);
            Imgproc.cvtColor(mResizedRgba, mResizedRgb, Imgproc.COLOR_RGBA2RGB);
            // 复用预分配的 letterbox Mat，只需重置填充色
            mLetterbox.setTo(LETTERBOX_GRAY);
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

    /**
     * 解码 YOLO26 ONNX 模型输出。
     * 模型已内置 TopK 选择（输出形状 [1, 300, 6]），无 IoU-based NMS。
     * 输出格式：每行 [x1, y1, x2, y2, score, classId]，坐标为像素值（相对于输入尺寸）。
     * 坐标系：模型在 320×320 输入上推理，输出坐标已映射到输入尺寸。
     */
    private float[] decode(float[] raw, int rowCount, int width, int height,
                           float scale, int padX, int padY, float confidence,
                           float preprocessMs, float inferenceMs) {
        // 预分配最大可能大小，避免 resize
        float[] packed = new float[2 + rowCount * 6];
        packed[0] = preprocessMs;
        packed[1] = inferenceMs;
        int write = 2;
        for (int row = 0; row < rowCount; row++) {
            int base = row * 6;
            float score = raw[base + 4];
            if (score < confidence) continue;
            // 模型直接输出角点坐标 [x1, y1, x2, y2]（像素值）
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
        // 精确裁剪到实际有效长度
        if (write < packed.length) {
            float[] result = new float[write];
            System.arraycopy(packed, 0, result, 0, write);
            return result;
        }
        return packed;
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
