package com.stardust.autojs.runtime.api;

import android.graphics.Bitmap;

/**
 * PP-OCR（ncnn）原生桥：只负责声明 native 方法，业务逻辑在 {@link PPOcr}。
 *
 * <p>对应的 native 实现在 {@code modules/engine/src/main/cpp/ppocr/ppocr_jni.cpp}，
 * 链接仓库内 vendor 的 ncnn 静态库与打包好的 OpenCV 共享库。
 *
 * <p>⚠️ {@code libppocr.so} 只在 **arm64-v8a** 随包（仓库只 vendor 了 arm64 的 ncnn），
 * 其它 ABI 上 {@link #isAvailable()} 为 false，{@link PPOcr} 会明确报错而不是崩。
 */
public final class PPOcrBridge {

    private static final boolean LOADED;

    static {
        boolean loaded;
        try {
            System.loadLibrary("ppocr");
            loaded = true;
        } catch (Throwable t) {
            loaded = false;
        }
        LOADED = loaded;
    }

    private PPOcrBridge() {
    }

    /** 当前 ABI 是否有可用的 libppocr.so。 */
    public static boolean isAvailable() {
        return LOADED;
    }

    /** 原生库版本号（诊断用）。 */
    public static native String nativeGetVersion();

    /**
     * 载入模型（会先释放上一个引擎）。返回是否成功。
     *
     * @param targetSize  检测阶段缩放后的长边（脚本可调，默认 512）
     * @param useFp16     ncnn fp16 存储/打包（需设备支持 ARMv8.2 fp16；实测快一截）
     * @param cpuPower    0=不绑核（推荐） 1=绑小核 2=绑大核簇
     * @param recMaxWidth rec 裁剪宽度上限（<=0 用内置默认 1024；压小更快但长行会被挤扁）
     */
    public static native boolean nativeInit(String detParamPath, String detModelPath,
                                            String recParamPath, String recModelPath,
                                            int targetSize, boolean useFp16, boolean useGpu,
                                            int cpuPower, int recMaxWidth);

    /** 只更新运行时参数（targetSize / cpuPower），不重载模型。 */
    public static native boolean nativeSetRuntimeOptions(int targetSize, int cpuPower);

    /**
     * 识别一张位图，返回 JSON 字符串（字段见 ppocr_jni.cpp 的 objectsToJson）。
     *
     * @param recThreads 识别工作线程数（1=串行；实测 4 路最快）
     * @param singleLine 只识别一个文本块
     */
    public static native String nativeRecognizeBitmap(Bitmap bitmap,
                                                      int roiLeft, int roiTop, int roiRight, int roiBottom,
                                                      int maxItems, float minScore, int recThreads,
                                                      boolean singleLine);

    /** 释放原生引擎与内存池。 */
    public static native void nativeRelease();
}
