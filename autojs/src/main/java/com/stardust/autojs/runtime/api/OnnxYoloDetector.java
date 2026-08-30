package com.stardust.autojs.runtime.api;

import android.graphics.Bitmap;

import com.stardust.autojs.core.image.ImageWrapper;

import java.nio.ByteBuffer;

/** Fixed-shape YOLO26 detector backed by the native ONNX Runtime C/C++ API. */
public final class OnnxYoloDetector implements AutoCloseable {

    private final Yolo mOwner;
    private long mHandle;

    OnnxYoloDetector(Yolo owner, String modelPath, int inputSize, int threads) {
        this(owner, modelPath, inputSize, inputSize, threads);
    }

    OnnxYoloDetector(Yolo owner, String modelPath, int inputWidth, int inputHeight, int threads) {
        if (!isRuntimeAvailable()) {
            throw new UnsupportedOperationException(getUnavailableReason());
        }
        if (inputWidth < 32 || inputHeight < 32) {
            throw new IllegalArgumentException("inputWidth/inputHeight 不能小于 32");
        }
        mOwner = owner;
        mHandle = nativeCreate(modelPath, inputWidth, inputHeight, Math.max(1, Math.min(threads, 8)));
        if (mHandle == 0) throw new IllegalStateException("ONNX Runtime 模型初始化失败");
    }

    public static boolean isRuntimeAvailable() {
        return Yolo.isNativeRuntimeAvailable();
    }

    public static String getUnavailableReason() {
        return isRuntimeAvailable() ? "" : Yolo.getNativeRuntimeError();
    }

    public static String getRuntimeVersion() {
        return isRuntimeAvailable() ? nativeVersion() : "unavailable";
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

    public synchronized boolean isClosed() {
        return mHandle == 0;
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
        Yolo.validateRegion(width, height, regionX, regionY, regionWidth, regionHeight);
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

    @Override
    public synchronized void close() {
        if (mHandle == 0) return;
        nativeRelease(mHandle);
        mHandle = 0;
        mOwner.unregister(this);
    }

    private static native String nativeVersion();
    private static native long nativeCreate(String modelPath, int inputWidth, int inputHeight, int threads);
    private static native float[] nativeDetectBitmap(long handle, Bitmap bitmap,
                                                       float confidence, float nmsThreshold);
    private static native float[] nativeDetectRgba(long handle, ByteBuffer rgba,
                                                     int width, int height, int rowStride,
                                                     int regionX, int regionY, int regionWidth, int regionHeight,
                                                     float confidence, float nmsThreshold);
    private static native void nativeRelease(long handle);
}
