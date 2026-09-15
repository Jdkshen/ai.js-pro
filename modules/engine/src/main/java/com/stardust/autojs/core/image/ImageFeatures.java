package com.stardust.autojs.core.image;

/**
 * PDF §2.18.3（第 166 页，v9.2 新增）：{@code $images.detectAndComputeFeatures()} 返回的特征对象，
 * 仅用于特征匹配；不用时必须显式 {@link #recycle()}（否则会占着内存直到脚本结束）。
 *
 * <p>实现细节：本仓库的 OpenCV（5.0.0，按模块拆 .so 的构建）**没有 features 模块**，
 * 所以这里用 imgproc 的角点检测 + 自写二进制描述子（ORB-lite），不是 OpenCV 的 SIFT/ORB。
 * 详见对照文档 §5.16 / §9。
 */
public class ImageFeatures {

    /** 描述子位数（每个特征点 256 位 = 8 个 int）。 */
    public static final int DESCRIPTOR_BITS = 256;
    public static final int DESCRIPTOR_INTS = DESCRIPTOR_BITS / 32;

    /** 特征点（都在“缩放后”的图片坐标系里，坐标 = 原图坐标 * scale）。 */
    public double[] keypointX;
    public double[] keypointY;
    /** 主方向（度，0~360）。 */
    public double[] keypointAngle;
    /** 描述子，length = count * DESCRIPTOR_INTS。 */
    public int[] descriptors;

    public int count;
    /** 计算特征时用到的缩放比例。 */
    public double scale;
    public boolean grayscale;
    public String method;
    /** 计算特征时的图片尺寸（缩放后），用于把匹配结果映射回原图坐标。 */
    public int scaledWidth;
    public int scaledHeight;
    /** 原始图片尺寸。 */
    public int originalWidth;
    public int originalHeight;

    private boolean recycled;

    /**
     * 计算特征时用的原图 Mat（不拷贝，只存引用）：仅用于 {@code matchFeatures} 的
     * {@code drawMatches} 调试绘制。原图被 recycle 后这里可能失效，绘制会被跳过。
     */
    public org.opencv.core.Mat sourceMat;

    public String getMethod() {
        return method;
    }

    public int getCount() {
        return count;
    }

    public double getScale() {
        return scale;
    }

    public int getWidth() {
        return originalWidth;
    }

    public int getHeight() {
        return originalHeight;
    }

    public boolean isRecycled() {
        return recycled;
    }

    /** PDF：{@code ImageFeatures.recycle()} —— 回收特征对象。 */
    public void recycle() {
        if (recycled) {
            return;
        }
        recycled = true;
        keypointX = null;
        keypointY = null;
        keypointAngle = null;
        descriptors = null;
        count = 0;
    }

    public void ensureUsable() {
        if (recycled) {
            throw new IllegalStateException("ImageFeatures 已经 recycle()，需要重新 detectAndComputeFeatures()");
        }
    }

    /** 缩放后坐标 -> 原图坐标。 */
    public double toOriginalX(double x) {
        return scale == 0 ? x : x / scale;
    }

    public double toOriginalY(double y) {
        return scale == 0 ? y : y / scale;
    }

    @Override
    public String toString() {
        return "ImageFeatures{method=" + method + ", count=" + count + ", scale=" + scale
                + ", grayscale=" + grayscale + ", recycled=" + recycled + '}';
    }

    static ImageFeatures empty(String method, double scale, boolean grayscale,
                               int scaledWidth, int scaledHeight,
                               int originalWidth, int originalHeight) {
        ImageFeatures features = new ImageFeatures();
        features.method = method;
        features.scale = scale;
        features.grayscale = grayscale;
        features.scaledWidth = scaledWidth;
        features.scaledHeight = scaledHeight;
        features.originalWidth = originalWidth;
        features.originalHeight = originalHeight;
        features.keypointX = new double[0];
        features.keypointY = new double[0];
        features.keypointAngle = new double[0];
        features.descriptors = new int[0];
        return features;
    }
}
