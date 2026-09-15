package com.stardust.autojs.core.image;

import android.graphics.Color;
import android.os.Build;
import androidx.annotation.RequiresApi;

import com.stardust.autojs.core.opencv.MatOfPoint;
import com.stardust.autojs.core.opencv.OpenCVHelper;
import com.stardust.util.ScreenMetrics;

import org.opencv.core.Core;

import com.stardust.autojs.core.opencv.Mat;

import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;

/**
 * Created by Stardust on 2017/5/18.
 */

@RequiresApi(api = Build.VERSION_CODES.KITKAT)
public class ColorFinder {

    private ScreenMetrics mScreenMetrics;

    public ColorFinder(ScreenMetrics screenMetrics) {
        mScreenMetrics = screenMetrics;
    }

    public Point findColorEquals(ImageWrapper imageWrapper, int color) {
        return findColorEquals(imageWrapper, color, null);
    }

    public Point findColorEquals(ImageWrapper imageWrapper, int color, Rect region) {
        return findColor(imageWrapper, color, 0, region);
    }

    public Point findColor(ImageWrapper imageWrapper, int color, int threshold) {
        return findColor(imageWrapper, color, threshold, null);
    }

    public Point findColor(ImageWrapper image, int color, int threshold, Rect rect) {
        MatOfPoint matOfPoint = findColorInner(image, color, threshold, rect);
        if (matOfPoint == null) {
            return null;
        }
        Point point = matOfPoint.toArray()[0];
        if (rect != null) {
            point.x = mScreenMetrics.scaleX((int) (point.x + rect.x));
            point.y = mScreenMetrics.scaleX((int) (point.y + rect.y));
        }
        OpenCVHelper.release(matOfPoint);
        return point;
    }

    public Point[] findAllPointsForColor(ImageWrapper image, int color, int threshold, Rect rect) {

        MatOfPoint matOfPoint = findColorInner(image, color, threshold, rect);
        if (matOfPoint == null) {
            return new Point[0];
        }
        Point[] points = matOfPoint.toArray();
        OpenCVHelper.release(matOfPoint);
        if (rect != null) {
            for (int i = 0; i < points.length; i++) {
                points[i].x = mScreenMetrics.scaleX((int) (points[i].x + rect.x));
                points[i].y = mScreenMetrics.scaleX((int) (points[i].y + rect.y));
            }
        }
        return points;
    }

    private MatOfPoint findColorInner(ImageWrapper image, int color, int threshold, Rect rect) {
        Mat bi = new Mat();
        Scalar lowerBound = new Scalar(Color.red(color) - threshold, Color.green(color) - threshold,
                Color.blue(color) - threshold, 255);
        Scalar upperBound = new Scalar(Color.red(color) + threshold, Color.green(color) + threshold,
                Color.blue(color) + threshold, 255);
        if (rect != null) {
            Mat m = new Mat(image.getMat(), rect);
            Core.inRange(m, lowerBound, upperBound, bi);
            OpenCVHelper.release(m);
        } else {
            Core.inRange(image.getMat(), lowerBound, upperBound, bi);
        }
        Mat nonZeroPos = new Mat();
        Core.findNonZero(bi, nonZeroPos);
        MatOfPoint result;
        if (nonZeroPos.rows() == 0 || nonZeroPos.cols() == 0) {
            result = null;
        } else {
            result = OpenCVHelper.newMatOfPoint(nonZeroPos);
        }
        OpenCVHelper.release(bi);
        OpenCVHelper.release(nonZeroPos);
        return result;
    }

    /**
     * PDF §2.18.2 `images.detectsMultiColors(img, x, y, firstColor, colors, options)`：
     * 只判断「指定点开始的那一组颜色」是否成立，不做全图搜索。
     *
     * @param points 展平成 [x, y, color, …] 的相对偏移与颜色
     */
    public boolean detectsMultiColors(ImageWrapper image, int x, int y, int firstColor,
                                      int threshold, int[] points) {
        if (x < 0 || y < 0 || x >= image.getWidth() || y >= image.getHeight()) {
            return false;
        }
        if (!detects(image.pixel(x, y), firstColor, threshold)) {
            return false;
        }
        for (int i = 0; i + 2 < points.length; i += 3) {
            int px = x + points[i];
            int py = y + points[i + 1];
            if (px < 0 || py < 0 || px >= image.getWidth() || py >= image.getHeight()) {
                return false;
            }
            if (!detects(image.pixel(px, py), points[i + 2], threshold)) {
                return false;
            }
        }
        return true;
    }

    private static boolean detects(int pixel, int color, int threshold) {
        ColorDetector detector = new ColorDetector.DifferenceDetector(color, threshold);
        return detector.detectsColor(Color.red(pixel), Color.green(pixel), Color.blue(pixel));
    }

    public Point findMultiColors(ImageWrapper image, int firstColor, int threshold, Rect rect, int[] points) {        Point[] firstPoints = findAllPointsForColor(image, firstColor, threshold, rect);
        for (Point firstPoint : firstPoints) {
            if (firstPoint == null)
                continue;
            if (checksPath(image, firstPoint, threshold, rect, points)) {
                return firstPoint;
            }
        }
        return null;
    }

    private boolean checksPath(ImageWrapper image, Point startingPoint, int threshold, Rect rect, int[] points) {
        for (int i = 0; i < points.length; i += 3) {
            int x = points[i];
            int y = points[i + 1];
            int color = points[i + 2];
            ColorDetector colorDetector = new ColorDetector.DifferenceDetector(color, threshold);
            x += startingPoint.x;
            y += startingPoint.y;
            if (x >= image.getWidth() || y >= image.getHeight()
                    || x < 0 || y < 0) {
                return false;
            }
            int c = image.pixel(x, y);
            if (!colorDetector.detectsColor(Color.red(c), Color.green(c), Color.blue(c))) {
                return false;
            }

        }
        return true;
    }
}