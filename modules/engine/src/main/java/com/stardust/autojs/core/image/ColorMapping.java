package com.stardust.autojs.core.image;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.stardust.autojs.annotation.ScriptInterface;

import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import org.opencv.core.Point;
import org.opencv.core.Rect;

/**
 * PDF §2.18.8 `ColorMapping`：同一张图上反复找色时先缓存像素，避免每次找色都走一遍 JNI。
 *
 * <p>文档用法：`let ColorMapping = $colors.mapping; let cm = ColorMapping.singleton;`（或 `new ColorMapping()`），
 * `cm.reset(img)` 之后再反复 `findColor` / `findMultiColors` / `findAllPointsForColor`。
 *
 * <p>坐标与 {@code images.findColor} 不同：这里返回的是**图片坐标**（不做屏幕缩放），
 * 因为缓存是按图片建的。
 */
public class ColorMapping {

    /** 全局单例（文档：ColorMapping.singleton）。 */
    public static final ColorMapping singleton = new ColorMapping();

    private int[] mPixels;
    private int mWidth;
    private int mHeight;

    public ColorMapping() {
    }

    /** `reset(img)`：重新初始化颜色映射数据。 */
    @ScriptInterface
    public void reset(ImageWrapper img) {
        if (img == null) {
            throw new IllegalArgumentException("reset(img) 需要一张图片");
        }
        Bitmap bitmap = img.getBitmap();
        if (bitmap == null) {
            throw new IllegalStateException("这张图片没有位图数据，无法建立颜色映射");
        }
        mWidth = bitmap.getWidth();
        mHeight = bitmap.getHeight();
        if (mPixels == null || mPixels.length != mWidth * mHeight) {
            mPixels = new int[mWidth * mHeight];
        }
        bitmap.getPixels(mPixels, 0, mWidth, 0, 0, mWidth, mHeight);
    }

    /** `recycle()`：释放颜色映射对象。 */
    @ScriptInterface
    public void recycle() {
        mPixels = null;
        mWidth = 0;
        mHeight = 0;
    }

    /** 文档没写、验收用的查询接口。 */
    @ScriptInterface
    public boolean isRecycled() {
        return mPixels == null;
    }

    /** `findColor(color[, options])`：返回第一个匹配点（图片坐标）。 */
    @ScriptInterface
    public Point findColor(Object color) {
        return findColor(color, null);
    }

    @ScriptInterface
    public Point findColor(Object color, Scriptable options) {
        ensureReset();
        int target = parseColor(color);
        int threshold = parseThreshold(options);
        Rect region = parseRegion(options);
        for (int y = region.y; y < region.y + region.height; y++) {
            int row = y * mWidth;
            for (int x = region.x; x < region.x + region.width; x++) {
                if (matches(mPixels[row + x], target, threshold)) {
                    return new Point(x, y);
                }
            }
        }
        return null;
    }

    /** `findAllPointsForColor(color[, options])`：返回所有匹配点；找不到返回 null。 */
    @ScriptInterface
    public Point[] findAllPointsForColor(Object color) {
        return findAllPointsForColor(color, null);
    }

    @ScriptInterface
    public Point[] findAllPointsForColor(Object color, Scriptable options) {
        ensureReset();
        int target = parseColor(color);
        int threshold = parseThreshold(options);
        Rect region = parseRegion(options);
        java.util.List<Point> points = new java.util.ArrayList<>();
        for (int y = region.y; y < region.y + region.height; y++) {
            int row = y * mWidth;
            for (int x = region.x; x < region.x + region.width; x++) {
                if (matches(mPixels[row + x], target, threshold)) {
                    points.add(new Point(x, y));
                }
            }
        }
        return points.isEmpty() ? null : points.toArray(new Point[0]);
    }

    /** `findMultiColors(firstColor, colors, options)`：colors 的每项是 [x, y, color]（相对第一个点）。 */
    @ScriptInterface
    public Point findMultiColors(Object firstColor, Object colors) {
        return findMultiColors(firstColor, colors, null);
    }

    @ScriptInterface
    public Point findMultiColors(Object firstColor, Object colors, Scriptable options) {
        ensureReset();
        int target = parseColor(firstColor);
        int threshold = parseThreshold(options);
        Rect region = parseRegion(options);
        int[] offsets = parseOffsets(colors);
        for (int y = region.y; y < region.y + region.height; y++) {
            int row = y * mWidth;
            for (int x = region.x; x < region.x + region.width; x++) {
                if (!matches(mPixels[row + x], target, threshold)) {
                    continue;
                }
                if (matchesOffsets(x, y, offsets, threshold)) {
                    return new Point(x, y);
                }
            }
        }
        return null;
    }

    /** 没 reset（或已 recycle）时给出明确错误，而不是 NPE。 */
    private void ensureReset() {
        if (mPixels == null) {
            throw new IllegalStateException("ColorMapping 还没有初始化，请先调用 reset(img)");
        }
    }

    private boolean matchesOffsets(int x, int y, int[] offsets, int threshold) {        for (int i = 0; i + 2 < offsets.length; i += 3) {
            int px = x + offsets[i];
            int py = y + offsets[i + 1];
            if (px < 0 || py < 0 || px >= mWidth || py >= mHeight) {
                return false;
            }
            if (!matches(mPixels[py * mWidth + px], offsets[i + 2], threshold)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matches(int pixel, int target, int threshold) {        return Math.abs(Color.red(pixel) - Color.red(target)) <= threshold
                && Math.abs(Color.green(pixel) - Color.green(target)) <= threshold
                && Math.abs(Color.blue(pixel) - Color.blue(target)) <= threshold;
    }

    private int parseColor(Object color) {        if (color instanceof Number) {
            return ((Number) color).intValue();
        }
        Object unwrapped = color instanceof org.mozilla.javascript.Wrapper
                ? ((org.mozilla.javascript.Wrapper) color).unwrap() : color;
        if (unwrapped instanceof Number) {
            return ((Number) unwrapped).intValue();
        }
        if (unwrapped == null) {
            throw new IllegalArgumentException("需要一个颜色值");
        }
        return Color.parseColor(String.valueOf(unwrapped));
    }

    /**
     * 文档：`similarity`（0~1，越大越相似）与 `threshold`（0~255，越小越相似，默认 4）二选一，
     * 同时存在以 similarity 为准；换算关系 similarity = (255 - threshold) / 255。
     */
    private int parseThreshold(Scriptable options) {
        Object similarity = option(options, "similarity");
        if (similarity instanceof Number) {
            double value = ((Number) similarity).doubleValue();
            return (int) Math.round((1 - value) * 255);
        }
        Object threshold = option(options, "threshold");
        if (threshold instanceof Number) {
            return ((Number) threshold).intValue();
        }
        return 4;
    }

    /** region 两个元素表示左上角到图片右下角；四个元素表示 x, y, w, h。 */
    private Rect parseRegion(Scriptable options) {
        Object region = option(options, "region");
        if (!(region instanceof Scriptable)) {
            return new Rect(0, 0, mWidth, mHeight);
        }
        Scriptable array = (Scriptable) region;
        int length = toInt(ScriptableObject.getProperty(array, "length"), 0);
        int x = toInt(ScriptableObject.getProperty(array, 0), 0);
        int y = toInt(ScriptableObject.getProperty(array, 1), 0);
        int width = length >= 4 ? toInt(ScriptableObject.getProperty(array, 2), mWidth - x) : mWidth - x;
        int height = length >= 4 ? toInt(ScriptableObject.getProperty(array, 3), mHeight - y) : mHeight - y;
        // 越界区域裁进图片范围，避免数组越界
        x = Math.max(0, Math.min(x, mWidth));
        y = Math.max(0, Math.min(y, mHeight));
        width = Math.max(0, Math.min(width, mWidth - x));
        height = Math.max(0, Math.min(height, mHeight - y));
        return new Rect(x, y, width, height);
    }

    private int[] parseOffsets(Object colors) {
        Object unwrapped = colors instanceof org.mozilla.javascript.Wrapper
                ? ((org.mozilla.javascript.Wrapper) colors).unwrap() : colors;
        if (!(unwrapped instanceof Scriptable)) {
            throw new IllegalArgumentException("colors 需要 [{x, y, color}, …] 数组");
        }
        Scriptable array = (Scriptable) unwrapped;
        int length = toInt(ScriptableObject.getProperty(array, "length"), 0);
        int[] offsets = new int[length * 3];
        for (int i = 0; i < length; i++) {
            Object item = ScriptableObject.getProperty(array, i);
            if (!(item instanceof Scriptable)) {
                throw new IllegalArgumentException("colors[" + i + "] 需要 [x, y, color]");
            }
            Scriptable triple = (Scriptable) item;
            offsets[i * 3] = toInt(ScriptableObject.getProperty(triple, 0), 0);
            offsets[i * 3 + 1] = toInt(ScriptableObject.getProperty(triple, 1), 0);
            offsets[i * 3 + 2] = parseColor(ScriptableObject.getProperty(triple, 2));
        }
        return offsets;
    }

    private static Object option(Scriptable options, String name) {
        if (options == null) {
            return null;
        }
        Object value = ScriptableObject.getProperty(options, name);
        if (value == null || value == Scriptable.NOT_FOUND
                || value instanceof org.mozilla.javascript.Undefined) {
            return null;
        }
        return value;
    }

    private static int toInt(Object value, int fallback) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof CharSequence) {
            try {
                return (int) Double.parseDouble(value.toString().trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }
}
