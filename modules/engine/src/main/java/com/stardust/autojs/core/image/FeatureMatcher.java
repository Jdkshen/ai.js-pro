package com.stardust.autojs.core.image;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 特征检测 + 匹配（PDF §2.18.2 第 164~166 页，v9.2 新增）。
 *
 * <p>⚠️ 为什么不是 SIFT/ORB：本仓库用的 OpenCV 5.0.0 AAR 是**按模块拆 .so 的构建**，
 * 只有 core/imgproc/imgcodecs/dnn/geometry/flann，**没有 features 模块**
 * （Java 包 {@code org.opencv.features} 也不在），而且这个构建的 {@code Imgproc}
 * 连 {@code goodFeaturesToTrack} / {@code HoughCircles} 都没有。
 * 所以这里用 {@code Imgproc.cornerMinEigenVal}（Shi-Tomasi 响应）+ 自写非极大值抑制选角点，
 * 再用强度质心定向 + 256 位 BRIEF 风格描述子，匹配用汉明距离暴力匹配 + Lowe ratio。
 *
 * <p>文档里的 {@code method}（SIFT/ORB）与 {@code matcher}（FLANNBASED/BRUTEFORCE*）
 * 选项都接受，但内部统一走这一套实现（差异记入对照文档 §9）。
 */
public final class FeatureMatcher {

    private FeatureMatcher() {
    }

    /** 角点数量上限（越大越准也越慢）。 */
    private static final int MAX_CORNERS = 600;
    /** 响应阈值：低于最大响应的这个比例直接淘汰（对应 OpenCV 的 qualityLevel）。 */
    private static final double QUALITY_LEVEL = 0.005;
    /** 角点最小间距（原图像素；实际间距 = 这个值 * scale）。 */
    private static final double MIN_DISTANCE_BASE = 9;
    /** 计算角点响应的窗口大小（必须是奇数）。 */
    private static final int BLOCK_SIZE = 5;
    /** 描述子采样半径（原图像素；实际采样半径 = 这个值 * scale，见 patchRadius）。 */
    private static final int PATCH_RADIUS_BASE = 16;
    /** 定向用的强度质心窗口半径（原图像素，同样随 scale 缩放）。 */
    private static final int ORIENT_RADIUS_BASE = 12;
    /** 单对描述子汉明距离上限（256 位里超过这个数就没参考价值）。 */
    private static final double MAX_HAMMING_DISTANCE = 120;
    /** RANSAC 迭代次数（确定性采样）。 */
    private static final int RANSAC_ITERATIONS = 300;
    /** RANSAC 内点判定距离（缩放后的场景坐标系，像素）。 */
    private static final double RANSAC_INLIER_DISTANCE = 4.0;
    /** 至少多少个内点才认为找到了目标。 */
    private static final int MIN_INLIERS = 8;
    private static final double MIN_SCALE = 0.05;
    private static final double MAX_SCALE = 4.0;

    /**
     * 固定的采样点对（确定性伪随机，归一化到 [-1, 1]）。
     *
     * <p>归一化很关键：采样半径要乘上当前 scale，这样“同一块纹理”在 scale=1 和 scale=0.5
     * 的图上采样到的是**同样的内容**，描述子才能互相匹配（否则缩放后描述子完全不兼容）。
     */
    private static final double[] PATTERN_X1 = new double[ImageFeatures.DESCRIPTOR_BITS];
    private static final double[] PATTERN_Y1 = new double[ImageFeatures.DESCRIPTOR_BITS];
    private static final double[] PATTERN_X2 = new double[ImageFeatures.DESCRIPTOR_BITS];
    private static final double[] PATTERN_Y2 = new double[ImageFeatures.DESCRIPTOR_BITS];

    static {
        long seed = 20260915L;
        for (int i = 0; i < ImageFeatures.DESCRIPTOR_BITS; i++) {
            seed = next(seed);
            PATTERN_X1[i] = (seed % 201 - 100) / 100.0;
            seed = next(seed);
            PATTERN_Y1[i] = (seed % 201 - 100) / 100.0;
            seed = next(seed);
            PATTERN_X2[i] = (seed % 201 - 100) / 100.0;
            seed = next(seed);
            PATTERN_Y2[i] = (seed % 201 - 100) / 100.0;
        }
    }

    private static long next(long seed) {
        return (seed * 6364136223846793005L + 1442695040888963407L) & Long.MAX_VALUE;
    }

    /** 文档默认值：宽 * 高 > 1000000 时 scale 默认 0.5，否则 1。 */
    public static double defaultScale(int width, int height) {
        return ((long) width * height) > 1000000L ? 0.5 : 1.0;
    }

    public static ImageFeatures detect(Mat rgba, double scale, boolean grayscale, String method,
                                       Rect region) {
        Mat source = rgba;
        boolean releaseSource = false;
        int offsetX = 0;
        int offsetY = 0;
        if (region != null) {
            int x = Math.max(0, Math.min(region.x, rgba.width() - 1));
            int y = Math.max(0, Math.min(region.y, rgba.height() - 1));
            int width = Math.min(Math.max(1, region.width), rgba.width() - x);
            int height = Math.min(Math.max(1, region.height), rgba.height() - y);
            if (width < 1 || height < 1) {
                throw new IllegalArgumentException("region 超出图片范围");
            }
            source = new Mat(rgba, new Rect(x, y, width, height));
            offsetX = x;
            offsetY = y;
            releaseSource = true;
        }
        int originalWidth = source.width();
        int originalHeight = source.height();
        Mat working = source;
        Mat gray = null;
        try {
            double effectiveScale = Math.max(MIN_SCALE, Math.min(MAX_SCALE,
                    scale <= 0 ? defaultScale(originalWidth, originalHeight) : scale));
            if (Math.abs(effectiveScale - 1.0) > 0.001) {
                Mat resized = new Mat();
                Imgproc.resize(source, resized, new org.opencv.core.Size(
                                Math.max(1, Math.round(originalWidth * effectiveScale)),
                                Math.max(1, Math.round(originalHeight * effectiveScale))),
                        0, 0, Imgproc.INTER_AREA);
                working = resized;
            }
            gray = new Mat();
            if (working.channels() >= 3) {
                Imgproc.cvtColor(working, gray, Imgproc.COLOR_RGBA2GRAY);
            } else {
                working.copyTo(gray);
            }
            // 描述子采样前先平滑（ORB 也是这么做的）：不平滑的话，缩放/亚像素差异会让
            // 二进制描述子逐位翻转，缩放后的图就完全匹配不上了（真机验收踩到过）。
            Mat blurred = new Mat();
            int kernelSize = Math.max(3, (int) Math.round(5 * effectiveScale) | 1);
            Imgproc.GaussianBlur(gray, blurred, new org.opencv.core.Size(kernelSize, kernelSize),
                    Math.max(0.8, 1.4 * effectiveScale), 0, Core.BORDER_REFLECT_101);
            gray.release();
            gray = blurred;
            ImageFeatures features = compute(gray, method, effectiveScale, grayscale,
                    working.width(), working.height(), originalWidth, originalHeight,
                    Math.max(5, (int) Math.round(PATCH_RADIUS_BASE * effectiveScale)),
                    Math.max(4, (int) Math.round(ORIENT_RADIUS_BASE * effectiveScale)),
                    Math.max(4.0, MIN_DISTANCE_BASE * effectiveScale));
            if (offsetX != 0 || offsetY != 0) {
                for (int i = 0; i < features.count; i++) {
                    features.keypointX[i] += offsetX * effectiveScale;
                    features.keypointY[i] += offsetY * effectiveScale;
                }
            }
            return features;
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException) {
                throw (IllegalArgumentException) e;
            }
            throw new IllegalStateException("计算图片特征失败：" + e.getMessage(), e);
        } finally {
            if (gray != null) {
                gray.release();
            }
            if (working != source) {
                working.release();
            }
            if (releaseSource) {
                source.release();
            }
        }
    }

    private static ImageFeatures compute(Mat gray, String method, double scale, boolean grayscale,
                                         int scaledWidth, int scaledHeight,
                                         int originalWidth, int originalHeight,
                                         int patchRadius, int orientRadius, double minDistance) {
        ImageFeatures features = ImageFeatures.empty(method, scale, grayscale,
                scaledWidth, scaledHeight, originalWidth, originalHeight);
        int width = gray.width();
        int height = gray.height();
        if (width < patchRadius * 2 + 4 || height < patchRadius * 2 + 4) {
            return features;
        }
        byte[] intensity = new byte[width * height];
        byte[] rowBuffer = new byte[width];
        for (int y = 0; y < height; y++) {
            gray.get(y, 0, rowBuffer);
            System.arraycopy(rowBuffer, 0, intensity, y * width, width);
        }
        // Shi-Tomasi 角点响应（OpenCV 的 cornerMinEigenVal；这个构建里没有 goodFeaturesToTrack）
        Mat response = new Mat();
        try {
            Imgproc.cornerMinEigenVal(gray, response, BLOCK_SIZE, 3, Core.BORDER_DEFAULT);
            float[] responses = new float[width * height];
            float[] responseRow = new float[width];
            double maxResponse = 0;
            for (int y = 0; y < height; y++) {
                response.get(y, 0, responseRow);
                System.arraycopy(responseRow, 0, responses, y * width, width);
                for (int x = 0; x < width; x++) {
                    if (responseRow[x] > maxResponse) {
                        maxResponse = responseRow[x];
                    }
                }
            }
            List<double[]> keypoints = selectKeypoints(responses, width, height, maxResponse,
                    minDistance, patchRadius);
            List<int[]> descriptors = new ArrayList<>();
            List<double[]> kept = new ArrayList<>();
            for (double[] keypoint : keypoints) {
                int x = (int) keypoint[0];
                int y = (int) keypoint[1];
                double angle = orientation(intensity, width, height, x, y, orientRadius);
                int[] descriptor = describe(intensity, width, height, x, y, angle, patchRadius);
                if (descriptor == null) {
                    continue;
                }
                kept.add(new double[]{keypoint[0], keypoint[1], angle});
                descriptors.add(descriptor);
            }
            features.count = kept.size();
            features.keypointX = new double[features.count];
            features.keypointY = new double[features.count];
            features.keypointAngle = new double[features.count];
            features.descriptors = new int[features.count * ImageFeatures.DESCRIPTOR_INTS];
            for (int i = 0; i < features.count; i++) {
                double[] keypoint = kept.get(i);
                features.keypointX[i] = keypoint[0];
                features.keypointY[i] = keypoint[1];
                features.keypointAngle[i] = keypoint[2];
                System.arraycopy(descriptors.get(i), 0, features.descriptors,
                        i * ImageFeatures.DESCRIPTOR_INTS, ImageFeatures.DESCRIPTOR_INTS);
            }
            return features;
        } finally {
            response.release();
        }
    }

    /** 响应阈值 + 非极大值抑制 + 最小间距贪心，最多取 MAX_CORNERS 个。 */
    private static List<double[]> selectKeypoints(float[] responses, int width, int height,
                                                  double maxResponse, double minDistance,
                                                  int patchRadius) {
        List<double[]> candidates = new ArrayList<>();
        if (maxResponse <= 0) {
            return candidates;
        }
        double threshold = maxResponse * QUALITY_LEVEL;
        int nms = Math.max(1, (int) Math.round(minDistance / 2));
        int margin = patchRadius + 2;
        for (int y = margin; y < height - margin; y++) {
            for (int x = margin; x < width - margin; x++) {
                float value = responses[y * width + x];
                if (value < threshold) {
                    continue;
                }
                boolean localMax = true;
                for (int dy = -nms; dy <= nms && localMax; dy++) {
                    int row = (y + dy) * width;
                    for (int dx = -nms; dx <= nms; dx++) {
                        if (dx == 0 && dy == 0) {
                            continue;
                        }
                        if (responses[row + x + dx] > value) {
                            localMax = false;
                            break;
                        }
                    }
                }
                if (localMax) {
                    candidates.add(new double[]{x, y, value});
                }
            }
        }
        candidates.sort(Comparator.comparingDouble((double[] k) -> k[2]).reversed());
        List<double[]> selected = new ArrayList<>();
        double minDistanceSquared = minDistance * minDistance;
        for (double[] candidate : candidates) {
            if (selected.size() >= MAX_CORNERS) {
                break;
            }
            boolean tooClose = false;
            for (double[] chosen : selected) {
                double dx = chosen[0] - candidate[0];
                double dy = chosen[1] - candidate[1];
                if (dx * dx + dy * dy < minDistanceSquared) {
                    tooClose = true;
                    break;
                }
            }
            if (!tooClose) {
                selected.add(candidate);
            }
        }
        return selected;
    }

    /** 主方向：强度质心法（IC），返回角度（度）。 */
    private static double orientation(byte[] intensity, int width, int height, int cx, int cy,
                                      int orientRadius) {
        double m01 = 0;
        double m10 = 0;
        for (int dy = -orientRadius; dy <= orientRadius; dy++) {
            int row = (cy + dy) * width;
            for (int dx = -orientRadius; dx <= orientRadius; dx++) {
                double value = intensity[row + cx + dx] & 0xFF;
                m10 += dx * value;
                m01 += dy * value;
            }
        }
        return Math.toDegrees(Math.atan2(m01, m10));
    }

    private static int[] describe(byte[] intensity, int width, int height, int cx, int cy,
                                  double angleDegrees, int patchRadius) {
        double radians = Math.toRadians(angleDegrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        int[] descriptor = new int[ImageFeatures.DESCRIPTOR_INTS];
        for (int i = 0; i < ImageFeatures.DESCRIPTOR_BITS; i++) {
            int x1 = cx + (int) Math.round((PATTERN_X1[i] * cos - PATTERN_Y1[i] * sin) * patchRadius);
            int y1 = cy + (int) Math.round((PATTERN_X1[i] * sin + PATTERN_Y1[i] * cos) * patchRadius);
            int x2 = cx + (int) Math.round((PATTERN_X2[i] * cos - PATTERN_Y2[i] * sin) * patchRadius);
            int y2 = cy + (int) Math.round((PATTERN_X2[i] * sin + PATTERN_Y2[i] * cos) * patchRadius);
            if (x1 < 0 || y1 < 0 || x1 >= width || y1 >= height
                    || x2 < 0 || y2 < 0 || x2 >= width || y2 >= height) {
                return null;
            }
            if ((intensity[y1 * width + x1] & 0xFF) < (intensity[y2 * width + x2] & 0xFF)) {
                descriptor[i / 32] |= (1 << (i % 32));
            }
        }
        return descriptor;
    }

    public static double hamming(ImageFeatures features, int indexA, ImageFeatures other, int indexB) {
        int offsetA = indexA * ImageFeatures.DESCRIPTOR_INTS;
        int offsetB = indexB * ImageFeatures.DESCRIPTOR_INTS;
        int distance = 0;
        for (int i = 0; i < ImageFeatures.DESCRIPTOR_INTS; i++) {
            distance += Integer.bitCount(features.descriptors[offsetA + i] ^ other.descriptors[offsetB + i]);
        }
        return distance;
    }

    /**
     * 特征匹配：返回匹配点对（场景点 x/y，目标点 x/y），没找到返回空数组。
     *
     * @param ratio 文档里的 {@code thredshold}（默认 0.7）—— Lowe ratio 阈值
     */
    public static List<double[]> match(ImageFeatures scene, ImageFeatures object, double ratio) {
        scene.ensureUsable();
        object.ensureUsable();
        List<double[]> pairs = new ArrayList<>();
        if (scene.count == 0 || object.count == 0) {
            return pairs;
        }
        for (int i = 0; i < object.count; i++) {
            double best = Double.MAX_VALUE;
            double second = Double.MAX_VALUE;
            int bestIndex = -1;
            for (int j = 0; j < scene.count; j++) {
                double distance = hamming(object, i, scene, j);
                if (distance < best) {
                    second = best;
                    best = distance;
                    bestIndex = j;
                } else if (distance < second) {
                    second = distance;
                }
            }
            if (bestIndex < 0 || best > MAX_HAMMING_DISTANCE) {
                continue;
            }
            // Lowe ratio：最优距离要明显优于次优（用 <= 与 OpenCV 的 DMatch 判断一致）
            if (second == Double.MAX_VALUE || best <= ratio * second) {
                pairs.add(new double[]{
                        scene.keypointX[bestIndex], scene.keypointY[bestIndex],
                        object.keypointX[i], object.keypointY[i]});
            }
        }
        return pairs;
    }

    /**
     * 用 RANSAC 估一个相似变换（缩放 + 旋转 + 平移），把目标图（缩放后）坐标映射到场景图（缩放后）坐标，
     * 再把目标图的四角映射过去。
     *
     * <p>为什么必须做几何校验：只靠描述子 + ratio 总会漏进一些错误匹配，错误匹配会把四边形估到离谱的位置
     * （也会让完全不相干的图“匹配上”）——第一版没做 RANSAC 时就是这样，真机验收直接打出负向对照失败。
     * 这里要求至少有 {@link #MIN_INLIERS} 个几何一致的内点，否则返回 null（文档：未找到返回 null）。
     */
    public static Point[] estimateFrameCorners(List<double[]> pairs, int objectWidth,
                                               int objectHeight) {
        if (pairs.size() < MIN_INLIERS) {
            return null;
        }
        // 1) RANSAC：两对点就能解出相似变换（复数除法），再数内点
        long seed = 20260915L;
        double[] best = null;
        int bestInliers = 0;
        for (int iteration = 0; iteration < RANSAC_ITERATIONS; iteration++) {
            seed = next(seed);
            int i = (int) (seed % pairs.size());
            seed = next(seed);
            int j = (int) (seed % pairs.size());
            if (i == j) {
                continue;
            }
            double[] first = pairs.get(i);
            double[] second = pairs.get(j);
            double zx = second[2] - first[2];
            double zy = second[3] - first[3];
            double norm = zx * zx + zy * zy;
            if (norm < 4) {
                continue;
            }
            double wx = second[0] - first[0];
            double wy = second[1] - first[1];
            // c = (wx + i·wy) / (zx + i·zy)
            double cx = (wx * zx + wy * zy) / norm;
            double cy = (wy * zx - wx * zy) / norm;
            double scale = Math.hypot(cx, cy);
            if (scale < 0.05 || scale > 20) {
                continue;
            }
            double tx = first[0] - (cx * first[2] - cy * first[3]);
            double ty = first[1] - (cx * first[3] + cy * first[2]);
            int inliers = 0;
            for (double[] pair : pairs) {
                double px = cx * pair[2] - cy * pair[3] + tx;
                double py = cx * pair[3] + cy * pair[2] + ty;
                double dx = px - pair[0];
                double dy = py - pair[1];
                if (dx * dx + dy * dy <= RANSAC_INLIER_DISTANCE * RANSAC_INLIER_DISTANCE) {
                    inliers++;
                }
            }
            if (inliers > bestInliers) {
                bestInliers = inliers;
                best = new double[]{cx, cy, tx, ty};
            }
        }
        if (best == null || bestInliers < MIN_INLIERS) {
            return null;
        }
        // 2) 用内点重新最小二乘拟合一次（比只有两对点的解更稳）
        int n = 0;
        double meanZx = 0;
        double meanZy = 0;
        double meanWx = 0;
        double meanWy = 0;
        for (double[] pair : pairs) {
            double px = best[0] * pair[2] - best[1] * pair[3] + best[2];
            double py = best[0] * pair[3] + best[1] * pair[2] + best[3];
            double dx = px - pair[0];
            double dy = py - pair[1];
            if (dx * dx + dy * dy > RANSAC_INLIER_DISTANCE * RANSAC_INLIER_DISTANCE) {
                continue;
            }
            n++;
            meanZx += pair[2];
            meanZy += pair[3];
            meanWx += pair[0];
            meanWy += pair[1];
        }
        meanZx /= n;
        meanZy /= n;
        meanWx /= n;
        meanWy /= n;
        double sumReal = 0;
        double sumImag = 0;
        double sumNorm = 0;
        for (double[] pair : pairs) {
            double px = best[0] * pair[2] - best[1] * pair[3] + best[2];
            double py = best[0] * pair[3] + best[1] * pair[2] + best[3];
            double dx = px - pair[0];
            double dy = py - pair[1];
            if (dx * dx + dy * dy > RANSAC_INLIER_DISTANCE * RANSAC_INLIER_DISTANCE) {
                continue;
            }
            double zx = pair[2] - meanZx;
            double zy = pair[3] - meanZy;
            double wx = pair[0] - meanWx;
            double wy = pair[1] - meanWy;
            sumReal += wx * zx + wy * zy;
            sumImag += wy * zx - wx * zy;
            sumNorm += zx * zx + zy * zy;
        }
        if (sumNorm < 1e-6) {
            return null;
        }
        double cx = sumReal / sumNorm;
        double cy = sumImag / sumNorm;
        double scale = Math.hypot(cx, cy);
        if (scale < 0.05 || scale > 20) {
            return null;
        }
        double[] cornersX = {0, objectWidth, objectWidth, 0};
        double[] cornersY = {0, 0, objectHeight, objectHeight};
        Point[] result = new Point[4];
        for (int i = 0; i < 4; i++) {
            double zx = cornersX[i] - meanZx;
            double zy = cornersY[i] - meanZy;
            result[i] = new Point(cx * zx - cy * zy + meanWx, cx * zy + cy * zx + meanWy);
        }
        // 退化四边形（宽或高太小）视为没找到
        double width = Math.hypot(result[1].x - result[0].x, result[1].y - result[0].y);
        double height = Math.hypot(result[3].x - result[0].x, result[3].y - result[0].y);
        if (width < 8 || height < 8) {
            return null;
        }
        return result;
    }

    /** PDF：{@code drawMatches} 选项 —— 把匹配详情画出来存成文件（调试用）。 */
    public static void drawMatches(Mat sceneRgba, List<double[]> pairs, Point[] objectCorners,
                                   String path) {
        if (path == null || path.isEmpty()) {
            return;
        }
        Mat bgr = new Mat();
        try {
            Imgproc.cvtColor(sceneRgba, bgr, Imgproc.COLOR_RGBA2BGR);
            for (double[] pair : pairs) {
                Imgproc.circle(bgr, new Point(pair[0], pair[1]), 6,
                        new org.opencv.core.Scalar(0, 0, 255), 2);
                Imgproc.line(bgr, new Point(pair[0], pair[1]), new Point(pair[2], pair[3]),
                        new org.opencv.core.Scalar(0, 255, 0), 1);
            }
            if (objectCorners != null) {
                for (int i = 0; i < 4; i++) {
                    Imgproc.line(bgr, objectCorners[i], objectCorners[(i + 1) % 4],
                            new org.opencv.core.Scalar(255, 0, 0), 3);
                }
            }
            Imgcodecs.imwrite(path, bgr);
        } finally {
            bgr.release();
        }
    }
}
