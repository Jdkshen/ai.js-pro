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
            shiftToRegion(packed, regionX, regionY);
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
            int[] shape = resolveOutputShape(output);
            int rowCount = shape[0];
            int colCount = shape[1];
            rows = output.reshape(1, rowCount);
            float[] raw = new float[rowCount * colCount];
            rows.get(0, 0, raw);
            return decode(raw, rowCount, colCount, width, height, scale, padX, padY,
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
     * 解析网络输出形状 → [rows, cols]。
     *
     * 端到端（NMS-free、模型自带 TopK）YOLO26 的输出约定是「每行一条检测」：
     *   · 检测：cols = 6 → [x1,y1,x2,y2,score,classId]
     *   · 姿态：cols = 6 + 3K → 关键点紧随 classId：每行 (kx,ky,kconf) × K
     *     （YOLO26n-pose 在 COCO 上是 K=17 → 57 列）
     * 个别导出会把通道放前（[1,6,300] / [1,57,300]，转置形态）。端到端的行数恒为
     * TopK（默认 300），所以优先认「行数=300」的一侧；都不是 300 时取列数小的一侧。
     * 不认识的形状（如 [1,4+nc,8400] 未解码原始头）直接抛错，避免把垃圾行当检测。
     */
    private static int[] resolveOutputShape(Mat output) {
        final int end2endRows = 300;
        final int maxRows = 1024;   // 原始头行数 8400 会在这里被拒掉
        int dims = output.dims();
        long total = output.total();
        int rows;
        int cols;
        if (dims == 3) {
            int first = output.size(0);
            int a = output.size(1);
            int b = output.size(2);
            if (first != 1) {
                throw new IllegalStateException("不支持的 ONNX 输出形状：["
                        + first + "," + a + "," + b + "]");
            }
            boolean aAsCols = isValidCols(a) && b <= maxRows;
            boolean bAsCols = isValidCols(b) && a <= maxRows;
            if (aAsCols && bAsCols) {
                if (b == end2endRows) {          // [1, cols, 300] 转置形态
                    rows = b;
                    cols = a;
                } else if (a == end2endRows) {   // [1, 300, cols] 常规形态
                    rows = a;
                    cols = b;
                } else {                          // 都不是 300：取列数小的一侧
                    rows = Math.max(a, b);
                    cols = Math.min(a, b);
                }
            } else if (aAsCols) {                 // a 是列数，b 是行数
                rows = b;
                cols = a;
            } else if (bAsCols) {                 // b 是列数，a 是行数
                rows = a;
                cols = b;
            } else {
                throw new IllegalStateException("不支持的 ONNX 输出形状：[1," + a + "," + b
                        + "]（端到端应为 [1,300,6] 或 [1,300,6+3K]；若是 [1,4+nc,8400] 原始头，请导出 end2end 版）");
            }
        } else if (dims == 2) {
            int r = output.rows();
            int c = output.cols();
            // c 是列宽（常规 [rows, cols]）；r 是列宽（转置 [cols, rows]）
            boolean cAsCols = isValidCols(c) && r <= maxRows;
            boolean rAsCols = isValidCols(r) && c <= maxRows;
            if (cAsCols && rAsCols) {
                // 300 既像 TopK 行数、又像 K=98 的姿态列宽：优先认 300 是行数
                if (r == end2endRows) {
                    rows = r;
                    cols = c;
                } else if (c == end2endRows) {
                    rows = c;
                    cols = r;
                } else {
                    rows = Math.max(r, c);
                    cols = Math.min(r, c);
                }
            } else if (cAsCols) {
                rows = r;
                cols = c;
            } else if (rAsCols) {
                rows = c;
                cols = r;
            } else {
                throw new IllegalStateException("不支持的 ONNX 输出形状：[" + r + "," + c + "]");
            }
        } else {
            throw new IllegalStateException("不支持的 ONNX 输出维度：" + dims);
        }
        if (rows <= 0 || cols <= 0 || (long) rows * cols != total) {
            throw new IllegalStateException("ONNX 输出形状解析失败（total=" + total + "）");
        }
        return new int[]{rows, cols};
    }

    /** 合法列数：6（检测）或 6+3K（姿态，K=1..100）。 */
    private static boolean isValidCols(int cols) {
        if (cols == 6) {
            return true;
        }
        int k = cols - 6;
        return k > 0 && k % 3 == 0 && k / 3 <= 100;
    }

    /**
     * 解码 YOLO26 ONNX 模型输出（端到端，模型自带 TopK）。
     *
     * 行布局（cols = 6 + 3K）：
     *   [x1, y1, x2, y2, score, classId, (kx1,ky1,kc1) ... (kxK,kyK,kcK)]
     * 检测模型 K=0（cols=6）；姿态模型 K=关键点数（YOLO26n-pose @COCO：17 → 57 列）。
     *
     * 打包格式 v2（给 JS 侧解包，见 quickjs_jni.cpp 胶水与 __yolo__.js）：
     *   [preprocessMs, inferenceMs, cols, 行1..., 行2...]
     *   行 = [x1, y1, x2, y2, score, classId, (kx,ky,kc)×K]（坐标已映射回原图/区域局部坐标）
     * ⚠️ 旧格式 v1 是 [preMs, infMs, 每行 6 个]；v2 多一个 cols 头字段（两个 JS 解包侧同步升级）。
     */
    static float[] decode(float[] raw, int rowCount, int cols, int width, int height,
                          float scale, int padX, int padY, float confidence,
                          float preprocessMs, float inferenceMs) {
        // 预分配最大可能大小，避免 resize
        float[] packed = new float[3 + rowCount * cols];
        packed[0] = preprocessMs;
        packed[1] = inferenceMs;
        packed[2] = cols;
        int write = 3;
        for (int row = 0; row < rowCount; row++) {
            int base = row * cols;
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
            // 姿态：关键点（与框同样做 letterbox 反映射；置信度原样传递）
            for (int k = 6; k + 2 < cols; k += 3) {
                packed[write++] = clamp((raw[base + k] - padX) / scale, 0f, width);
                packed[write++] = clamp((raw[base + k + 1] - padY) / scale, 0f, height);
                packed[write++] = raw[base + k + 2];
            }
        }
        // 精确裁剪到实际有效长度
        if (write < packed.length) {
            float[] result = new float[write];
            System.arraycopy(packed, 0, result, 0, write);
            return result;
        }
        return packed;
    }

    /**
     * 把「区域局部坐标」的检测框与关键点平移回全图坐标（detectRgba 的 region 路径）。
     * v2 打包：[preMs, infMs, cols, 每行 cols 个]；兼容旧 v1（[preMs, infMs, 每行 6 个]）。
     */
    static void shiftToRegion(float[] packed, int regionX, int regionY) {
        if (packed == null || packed.length < 2) {
            return;
        }
        // v2 判别要防误伤：v1 数组的 packed[2] 是首个框的 x1，数值也可能落在 6..306。
        // 再加两个条件——至少一整行、且（长度-3）能被行宽整除。
        boolean v2 = packed.length >= 9 && isPackedStride(packed[2])
                && (packed.length - 3) % ((int) packed[2]) == 0;
        int stride = v2 ? (int) packed[2] : 6;
        int start = v2 ? 3 : 2;
        for (int i = start; i + stride - 1 < packed.length; i += stride) {
            packed[i] += regionX;          // x1
            packed[i + 1] += regionY;      // y1
            packed[i + 2] += regionX;      // x2
            packed[i + 3] += regionY;      // y2
            for (int k = 6; k + 2 < stride; k += 3) {
                packed[i + k] += regionX;
                packed[i + k + 1] += regionY;
            }
        }
    }

    /** 第三个头字段是不是一个合法的行宽（v2 标志）：6..306 之间的整数。 */
    private static boolean isPackedStride(float value) {
        return value >= 6.0f && value <= 306.0f && value == Math.round(value);
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
