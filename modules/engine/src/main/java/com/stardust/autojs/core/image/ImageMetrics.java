package com.stardust.autojs.core.image;

import android.graphics.Bitmap;

/**
 * 图像质量/相似度指标，对应 PDF §2.18.1 的 `images.getSimilarity(img1, img2, {type})`。
 *
 * <p>OpenCV 的 Java 绑定里没有 PSNR/SSIM，这里按标准定义自己算：
 * <ul>
 *   <li>PSNR：{@code 10 * log10(255² / MSE)}，逐像素对 RGB 求均方误差（不计 alpha）</li>
 *   <li>MSSIM：8×8 窗口的结构相似性均值，C1 = (0.01×255)²、C2 = (0.03×255)²（与 OpenCV/原论文一致）</li>
 * </ul>
 */
public final class ImageMetrics {

    private static final double C1 = (0.01 * 255) * (0.01 * 255);
    private static final double C2 = (0.03 * 255) * (0.03 * 255);
    private static final int WINDOW = 8;

    private ImageMetrics() {
    }

    /** 峰值信噪比；两张图完全相同时数学上是无穷大。 */
    public static double psnr(Bitmap first, Bitmap second) {
        checkSameSize(first, second);
        int width = first.getWidth();
        int height = first.getHeight();
        int[] a = new int[width * height];
        int[] b = new int[width * height];
        first.getPixels(a, 0, width, 0, 0, width, height);
        second.getPixels(b, 0, width, 0, 0, width, height);
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            int dr = ((a[i] >> 16) & 0xFF) - ((b[i] >> 16) & 0xFF);
            int dg = ((a[i] >> 8) & 0xFF) - ((b[i] >> 8) & 0xFF);
            int db = (a[i] & 0xFF) - (b[i] & 0xFF);
            sum += (double) dr * dr + (double) dg * dg + (double) db * db;
        }
        double mse = sum / (a.length * 3.0);
        if (mse == 0) {
            return Double.POSITIVE_INFINITY;
        }
        return 10 * Math.log10((255.0 * 255.0) / mse);
    }

    /** 平均结构相似性（MSSIM），取值范围 [0, 1]，越接近 1 越相似。 */
    public static double ssim(Bitmap first, Bitmap second) {
        checkSameSize(first, second);
        int width = first.getWidth();
        int height = first.getHeight();
        int[] a = new int[width * height];
        int[] b = new int[width * height];
        first.getPixels(a, 0, width, 0, 0, width, height);
        second.getPixels(b, 0, width, 0, 0, width, height);
        double total = 0;
        int windows = 0;
        for (int top = 0; top < height; top += WINDOW) {
            int bottom = Math.min(top + WINDOW, height);
            for (int left = 0; left < width; left += WINDOW) {
                int right = Math.min(left + WINDOW, width);
                total += windowSsim(a, b, width, left, top, right, bottom);
                windows++;
            }
        }
        return windows == 0 ? 0 : total / windows;
    }

    /** 单窗口 SSIM：把像素转成亮度后按定义算均值/方差/协方差。 */
    private static double windowSsim(int[] a, int[] b, int width,
                                     int left, int top, int right, int bottom) {
        int count = (right - left) * (bottom - top);
        double sumA = 0;
        double sumB = 0;
        double sumAA = 0;
        double sumBB = 0;
        double sumAB = 0;
        for (int y = top; y < bottom; y++) {
            int row = y * width;
            for (int x = left; x < right; x++) {
                double la = luminance(a[row + x]);
                double lb = luminance(b[row + x]);
                sumA += la;
                sumB += lb;
                sumAA += la * la;
                sumBB += lb * lb;
                sumAB += la * lb;
            }
        }
        double meanA = sumA / count;
        double meanB = sumB / count;
        double varianceA = sumAA / count - meanA * meanA;
        double varianceB = sumBB / count - meanB * meanB;
        double covariance = sumAB / count - meanA * meanB;
        return ((2 * meanA * meanB + C1) * (2 * covariance + C2))
                / ((meanA * meanA + meanB * meanB + C1) * (varianceA + varianceB + C2));
    }

    private static double luminance(int color) {
        return 0.299 * ((color >> 16) & 0xFF) + 0.587 * ((color >> 8) & 0xFF) + 0.114 * (color & 0xFF);
    }

    private static void checkSameSize(Bitmap first, Bitmap second) {
        if (first == null || second == null) {
            throw new IllegalArgumentException("getSimilarity 需要两张图片");
        }
        if (first.getWidth() != second.getWidth() || first.getHeight() != second.getHeight()) {
            throw new IllegalArgumentException("getSimilarity 的两张图片尺寸必须相同："
                    + first.getWidth() + "x" + first.getHeight() + " vs "
                    + second.getWidth() + "x" + second.getHeight());
        }
    }
}
