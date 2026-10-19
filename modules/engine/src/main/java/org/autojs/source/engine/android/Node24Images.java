// SPDX-License-Identifier: MIT
package org.autojs.source.engine.android;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.system.Os;
import android.util.Base64;
import android.util.Log;

import com.stardust.autojs.core.image.FeatureMatcher;
import com.stardust.autojs.core.image.ImageFeatures;
import com.stardust.autojs.core.image.TemplateMatching;
import com.stardust.autojs.core.opencv.Mat;
import com.stardust.autojs.core.opencv.OpenCVHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.opencv.android.Utils;
import org.opencv.core.Point;
import org.opencv.core.Rect;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * {@code kind:"image"} 的 Java 宿主实现（协议见 docs/guides/Node24基础自动化API.md）。
 *
 * <p>Bitmap 句柄只在一次 Node 执行内有效；驱动层不向 JS 暴露 Java 对象，只返回
 * {@code {id,width,height}}。{@link #close()} 在任务结束/取消/异常时由
 * {@link Node24HostBridge#close()} 调用，回收全部 Bitmap。
 *
 * <p>资源上限：同时存活图片数、单图像素数、全部存活图片总像素数都有上限；解码前先读
 * bounds，超限直接报错而不是尝试分配。颜色比较按 RGB 三通道最大差值与 threshold 比较，
 * 忽略 alpha；区域校验避免越界。找色按行优先返回第一个匹配点。
 */
public final class Node24Images implements AutoCloseable {

    private static final String TAG = "Node24Images";

    /** 同时存活的图片句柄上限。 */
    static final int DEFAULT_MAX_LIVE_IMAGES = 64;
    /** 单张图片像素上限（16M：覆盖 4K 截图与常见平板，仍能限制异常大图）。 */
    static final long DEFAULT_MAX_IMAGE_PIXELS = 16_000_000L;
    /** 全部存活图片总像素上限（256MB ARGB 量级）。 */
    static final long DEFAULT_MAX_TOTAL_PIXELS = 64_000_000L;
    private static final long MAX_FILE_BYTES = 256L * 1024 * 1024;
    private static final int MAX_PATH_CHARS = 4096;
    /** getPixels 单次返回的原始字节上限（Binder/JSON 传输安全线）。 */
    private static final int MAX_PIXEL_FETCH_BYTES = 4 * 1024 * 1024;
    private static final int MAX_OFFSET_COLORS = 1024;

    private final int mMaxLiveImages;
    private final long mMaxImagePixels;
    private final long mMaxTotalPixels;
    private final Map<Integer, Bitmap> mBitmaps = new LinkedHashMap<>();
    // task-172：特征对象（detectAndComputeFeatures）— 与图片句柄 id 分开编号。
    private final Map<Integer, FeatureState> mFeatures = new LinkedHashMap<>();
    private int mNextId = 1;
    private int mNextFeatureId = 1;
    private long mLivePixels;
    private boolean mClosed;
    /** 进程内 OpenCV 只初始化一次；失败后仍允许下次重试。 */
    private static volatile boolean sOpenCvReady;

    public Node24Images() {
        this(DEFAULT_MAX_LIVE_IMAGES, DEFAULT_MAX_IMAGE_PIXELS, DEFAULT_MAX_TOTAL_PIXELS);
    }

    /** 测试/受控宿主可缩小上限；生产路径使用无参构造。 */
    Node24Images(int maxLiveImages, long maxImagePixels, long maxTotalPixels) {
        mMaxLiveImages = Math.max(1, maxLiveImages);
        mMaxImagePixels = Math.max(1L, maxImagePixels);
        mMaxTotalPixels = Math.max(1L, maxTotalPixels);
    }

    // ------------------------------------------------------------------
    // kind:"image" 入口
    // ------------------------------------------------------------------

    /** 返回宿主统一 envelope：{@code {ok:true,value:...}} / {@code {ok:false,error:...}}。 */
    public synchronized JSONObject handle(JSONObject request) {
        try {
            String action = request.optString("action", "");
            switch (action) {
                case "read":
                    return ok(read(requirePath(request)));
                case "write":
                    write(request);
                    return ok(Boolean.TRUE);
                case "pixel":
                    return ok(pixel(request));
                case "findColor":
                    return ok(findColor(request, false));
                case "findMultiColors":
                    return ok(findColor(request, true));
                case "findImage":
                    return ok(findImage(request));
                case "matchTemplate":
                    return ok(matchTemplate(request));
                case "getPixels":
                    return ok(getPixels(request));
                case "detectsColor":
                    return ok(detectsColor(request, false));
                case "detectsMultiColors":
                    return ok(detectsColor(request, true));
                case "detectAndComputeFeatures":
                    return ok(detectFeatures(request));
                case "matchFeatures":
                    return ok(matchFeatures(request));
                case "recycleFeatures":
                    recycleFeatures(request.optInt("id", -1));
                    return ok(Boolean.TRUE);
                case "recycle":
                    recycle(request.optInt("id", -1));
                    return ok(Boolean.TRUE);
                default:
                    return error("image 不支持的操作：" + action
                            + "（支持 read/write/pixel/findColor/findMultiColors/"
                            + "findImage/matchTemplate/getPixels/detectsColor/detectsMultiColors/recycle/detectAndComputeFeatures/"
                            + "matchFeatures/recycleFeatures）");
            }
        } catch (Throwable failure) {
            Log.w(TAG, "image action failed: " + request, failure);
            return error(describe(failure));
        }
    }

    /** 从文件解码并登记一个句柄；失败时不留下资源。 */
    public synchronized JSONObject readFile(String path) throws Exception {
        return read(path);
    }

    /**
     * 登记一个已经解码的 Bitmap（无障碍截图 / 投屏帧）。超过上限时由本方法负责回收入参，
     * 调用方不需要再次 recycle；除 null/已回收外，任何校验或硬件位图转换失败也由本方法回收。
     */
    public synchronized JSONObject register(Bitmap bitmap) throws Exception {
        if (mClosed) {
            recycleQuietly(bitmap);
            throw new IllegalStateException("本次 Node 任务的图片资源已释放");
        }
        if (bitmap == null) {
            throw new IllegalArgumentException("图片解码失败：Bitmap 为空");
        }
        if (bitmap.isRecycled()) {
            throw new IllegalArgumentException("图片解码失败：Bitmap 已回收");
        }
        boolean registered = false;
        try {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            checkDimensions(width, height);
            checkRegisterBudget(width, height);
            // BitmapFactory 在某些 API 上仍可能返回 HARDWARE Bitmap；getPixels 不可用，复制成 ARGB_8888。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && bitmap.getConfig() == Bitmap.Config.HARDWARE) {
                Bitmap hardware = bitmap;
                Bitmap software;
                try {
                    software = hardware.copy(Bitmap.Config.ARGB_8888, false);
                } catch (Throwable error) {
                    throw new IllegalStateException("图片解码失败：无法转换为可读 Bitmap", error);
                }
                if (software == null) {
                    throw new IllegalStateException("图片解码失败：无法转换为可读 Bitmap");
                }
                // 先换成本方法持有的 software，再回收原 HARDWARE 图；recycle 失败也不丢新图。
                bitmap = software;
                recycleQuietly(hardware);
            }
            int id = mNextId++;
            JSONObject response = descriptor(id, bitmap);
            mBitmaps.put(id, bitmap);
            mLivePixels += (long) width * (long) height;
            registered = true;
            return response;
        } finally {
            if (!registered) {
                recycleQuietly(bitmap);
            }
        }
    }

    private JSONObject read(String path) throws Exception {
        ensureOpen();
        File file = safeFile(path);
        if (!file.isFile()) {
            throw new IOException("图片文件不存在：" + path);
        }
        long length = file.length();
        if (length <= 0L) {
            throw new IOException("图片文件为空：" + path);
        }
        if (length > MAX_FILE_BYTES) {
            throw new IOException("图片文件过大（" + length + " 字节，上限 " + MAX_FILE_BYTES + "）：" + path);
        }

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("无法解码图片（格式不支持或文件损坏）：" + path);
        }
        checkDimensions(bounds.outWidth, bounds.outHeight);
        // 解码前先占用预算检查：句柄已满/总像素会超限时不要先分配一张 ARGB_8888。
        checkRegisterBudget(bounds.outWidth, bounds.outHeight);

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inScaled = false;
        Bitmap bitmap;
        try {
            bitmap = BitmapFactory.decodeFile(path, options);
        } catch (OutOfMemoryError memory) {
            throw new IOException("图片解码内存不足（" + bounds.outWidth + "x" + bounds.outHeight
                    + "）：" + path, memory);
        }
        if (bitmap == null) {
            throw new IOException("无法解码图片（格式不支持或文件损坏）：" + path);
        }
        return register(bitmap);
    }

    private void write(JSONObject request) throws Exception {
        ensureOpen();
        int id = requireId(request);
        Bitmap bitmap = requireBitmap(id);
        String path = requirePath(request);
        File target = safeFile(path);
        String format = request.optString("format", "png").trim().toLowerCase(Locale.US);
        Bitmap.CompressFormat compressFormat;
        if ("png".equals(format)) {
            compressFormat = Bitmap.CompressFormat.PNG;
        } else if ("jpeg".equals(format) || "jpg".equals(format)) {
            compressFormat = Bitmap.CompressFormat.JPEG;
        } else {
            throw new IllegalArgumentException("图片格式只支持 png/jpeg，收到：" + format);
        }
        int quality = request.has("quality") ? request.optInt("quality", 100) : 100;
        if (quality < 0 || quality > 100) {
            throw new IllegalArgumentException("图片质量必须在 0..100 之间，收到：" + quality);
        }

        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("无法创建图片目录：" + parent);
        }
        File temporary = new File(parent, "." + target.getName() + ".tmp-" + UUID.randomUUID());
        boolean written = false;
        try {
            FileOutputStream output = new FileOutputStream(temporary);
            try {
                if (!bitmap.compress(compressFormat, quality, output)) {
                    throw new IOException("图片编码失败（" + format + "）：" + path);
                }
                output.flush();
            } finally {
                output.close();
            }
            if (temporary.length() <= 0L) {
                throw new IOException("图片编码结果为空：" + path);
            }
            // 同目录临时文件 + Linux rename 原子替换；不能先删目标，否则 rename 失败会丢掉旧图。
            try {
                Os.rename(temporary.getAbsolutePath(), target.getAbsolutePath());
            } catch (Exception error) {
                throw new IOException("无法写入图片：" + path + "：" + describe(error), error);
            }
            written = true;
        } finally {
            if (!written) {
                //noinspection ResultOfMethodCallIgnored
                temporary.delete();
            }
        }
    }

    private int pixel(JSONObject request) throws Exception {
        ensureOpen();
        Bitmap bitmap = requireBitmap(requireId(request));
        int x = requireInt(request, "x");
        int y = requireInt(request, "y");
        checkPoint(bitmap, x, y);
        return bitmap.getPixel(x, y);
    }

    private JSONObject findColor(JSONObject request, boolean multi) throws Exception {
        ensureOpen();
        Bitmap bitmap = requireBitmap(requireId(request));
        int color = parseColor(request.opt("color"), "color");
        int threshold = parseThreshold(request);
        Region region = parseRegion(request.opt("region"), bitmap.getWidth(), bitmap.getHeight());
        OffsetColor[] offsets = multi ? parseOffsetColors(request) : null;

        int[] pixels = new int[region.width * region.height];
        bitmap.getPixels(pixels, 0, region.width, region.x, region.y, region.width, region.height);
        if (!multi) {
            int[] local = findFirstColor(pixels, region.width, region.height, color, threshold);
            return local == null ? null : point(region.x + local[0], region.y + local[1]);
        }
        for (int localY = 0; localY < region.height; localY++) {
            int row = localY * region.width;
            for (int localX = 0; localX < region.width; localX++) {
                if (!colorMatches(color, pixels[row + localX], threshold)) {
                    continue;
                }
                if (matchesOffsets(bitmap, region.x + localX, region.y + localY, offsets, threshold)) {
                    return point(region.x + localX, region.y + localY);
                }
            }
        }
        return null;
    }

    /**
     * 用 OpenCV 模板匹配在源图中查找模板。返回匹配左上角（源图坐标）或 {@code null}；
     * 模板大于搜索区域、阈值非法时抛错，不伪造「未找到」。
     *
     * <p>源图/模板都来自 {@link #mBitmaps}，这里只做临时 Mat 转换；Mat 在 finally 释放，
     * 绝不回收或移除借用的 Bitmap 句柄，调用方仍可继续使用两张图。
     */
    private JSONObject findImage(JSONObject request) throws Exception {
        ensureOpen();
        int sourceId = requireId(request);
        int templateId = requireInt(request, "templateId");
        Bitmap source = requireBitmap(sourceId);
        Bitmap template = requireBitmap(templateId);
        double threshold = parseSimilarity(request, "threshold", 0.9d);
        double weakThreshold = parseSimilarity(request, "weakThreshold", 0.6d);
        int level = parseMatchLevel(request);
        Region region = parseRegion(request.opt("region"),
                source.getWidth(), source.getHeight());
        int templateWidth = template.getWidth();
        int templateHeight = template.getHeight();
        if (templateWidth > region.width || templateHeight > region.height) {
            // 与 native_frame_store/Rhino 同文案：Pro 该路径不是「返回未找到」。
            throw new IllegalArgumentException("Template " + templateWidth + "x" + templateHeight
                    + " is larger than source/search region "
                    + region.width + "x" + region.height);
        }
        ensureOpenCv();
        Mat sourceMat = new Mat();
        Mat templateMat = new Mat();
        Mat regionMat = null;
        try {
            Utils.bitmapToMat(source, sourceMat);
            Utils.bitmapToMat(template, templateMat);
            Mat search = sourceMat;
            if (region.x != 0 || region.y != 0
                    || region.width != source.getWidth()
                    || region.height != source.getHeight()) {
                regionMat = new Mat(sourceMat, new Rect(region.x, region.y,
                        region.width, region.height));
                search = regionMat;
            }
            Point matched = TemplateMatching.fastTemplateMatching(
                    search, templateMat, TemplateMatching.MATCHING_METHOD_DEFAULT,
                    (float) weakThreshold, (float) threshold, level);
            if (matched == null) {
                return null;
            }
            return point(region.x + (int) Math.round(matched.x),
                    region.y + (int) Math.round(matched.y));
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Throwable error) {
            throw new IllegalStateException("模板匹配失败：" + describe(error), error);
        } finally {
            if (regionMat != null) {
                OpenCVHelper.release(regionMat);
            }
            OpenCVHelper.release(templateMat);
            OpenCVHelper.release(sourceMat);
        }
    }

    /**
     * 多结果模板匹配：与 Rhino/QuickJS 的 images.matchTemplate 同一算法
     * （TM_CCOEFF_NORMED + 金字塔快速匹配），返回 [{x,y,similarity}]。
     */
    private JSONObject matchTemplate(JSONObject request) throws Exception {
        ensureOpen();
        int sourceId = requireId(request);
        int templateId = requireInt(request, "templateId");
        Bitmap source = requireBitmap(sourceId);
        Bitmap template = requireBitmap(templateId);
        double threshold = parseSimilarity(request, "threshold", 0.9d);
        double weakThreshold = parseSimilarity(request, "weakThreshold", 0.6d);
        int level = parseMatchLevel(request);
        int limit = Math.max(1, Math.min(4096, request.optInt("limit", 32)));
        Region region = parseRegion(request.opt("region"), source.getWidth(), source.getHeight());
        if (template.getWidth() > region.width || template.getHeight() > region.height) {
            throw new IllegalArgumentException("Template " + template.getWidth() + "x" + template.getHeight()
                    + " is larger than source/search region " + region.width + "x" + region.height);
        }
        ensureOpenCv();
        Mat sourceMat = new Mat();
        Mat templateMat = new Mat();
        Mat regionMat = null;
        try {
            Utils.bitmapToMat(source, sourceMat);
            Utils.bitmapToMat(template, templateMat);
            Mat search = sourceMat;
            if (region.x != 0 || region.y != 0 || region.width != source.getWidth()
                    || region.height != source.getHeight()) {
                regionMat = new Mat(sourceMat, new Rect(region.x, region.y, region.width, region.height));
                search = regionMat;
            }
            List<TemplateMatching.Match> matches = TemplateMatching.fastTemplateMatching(
                    search, templateMat, TemplateMatching.MATCHING_METHOD_DEFAULT,
                    (float) weakThreshold, (float) threshold, level, limit, false);
            JSONArray array = new JSONArray();
            for (TemplateMatching.Match match : matches) {
                array.put(new JSONObject()
                        .put("x", region.x + (int) Math.round(match.point.x))
                        .put("y", region.y + (int) Math.round(match.point.y))
                        .put("similarity", match.similarity));
            }
            return new JSONObject().put("matches", array);
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Throwable error) {
            throw new IllegalStateException("模板匹配失败：" + describe(error), error);
        } finally {
            if (regionMat != null) {
                OpenCVHelper.release(regionMat);
            }
            OpenCVHelper.release(templateMat);
            OpenCVHelper.release(sourceMat);
        }
    }

    /**
     * 批量像素：一次调用取回整块区域，避免「每像素一次跨进程调用」。
     * format=argb（默认，小端 4 字节/像素）或 gray（1 字节/像素，BT.601）。
     */
    private JSONObject getPixels(JSONObject request) throws Exception {
        ensureOpen();
        int imageId = requireId(request);
        Bitmap bitmap = requireBitmap(imageId);
        Region region = parseRegion(request.opt("region"), bitmap.getWidth(), bitmap.getHeight());
        String format = request.optString("format", "argb");
        if (!"argb".equals(format) && !"gray".equals(format)) {
            throw new IllegalArgumentException("getPixels 的 format 仅支持 argb/gray：" + format);
        }
        boolean argb = "argb".equals(format);
        long bytes = (long) region.width * (long) region.height * (argb ? 4L : 1L);
        if (bytes > MAX_PIXEL_FETCH_BYTES) {
            throw new IllegalArgumentException("getPixels 区域过大：" + region.width + "x" + region.height
                    + "（单次上限 " + (MAX_PIXEL_FETCH_BYTES / 1024 / 1024) + "MB，请分块读取）");
        }
        int[] pixels = new int[region.width * region.height];
        bitmap.getPixels(pixels, 0, region.width, region.x, region.y, region.width, region.height);
        byte[] raw;
        if (argb) {
            raw = new byte[pixels.length * 4];
            ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            for (int pixel : pixels) {
                buffer.putInt(pixel);
            }
        } else {
            raw = new byte[pixels.length];
            for (int i = 0; i < pixels.length; i++) {
                int pixel = pixels[i];
                int r = (pixel >> 16) & 0xFF;
                int g = (pixel >> 8) & 0xFF;
                int b = pixel & 0xFF;
                raw[i] = (byte) Math.round(0.299d * r + 0.587d * g + 0.114d * b);
            }
        }
        return new JSONObject()
                .put("width", region.width)
                .put("height", region.height)
                .put("format", format)
                .put("data", Base64.encodeToString(raw, Base64.NO_WRAP));
    }
    /**
     * task-172：计算图片特征（复用 Rhino/QuickJS 同款的 FeatureMatcher ORB-lite 实现）。
     * 特征对象保存在宿主，Node 只拿到句柄 id；用完后 ImageFeatures.recycle() 释放。
     */
    private JSONObject detectFeatures(JSONObject request) throws Exception {
        ensureOpen();
        int imageId = requireId(request);
        Bitmap bitmap = requireBitmap(imageId);
        double scale = request.optDouble("scale", -1d);
        boolean grayscale = request.optBoolean("grayscale", true);
        String method = request.optString("method", "SIFT");
        Region region = parseRegion(request.opt("region"), bitmap.getWidth(), bitmap.getHeight());
        ensureOpenCv();
        Mat rgba = new Mat();
        Utils.bitmapToMat(bitmap, rgba);
        try {
            Rect rect = new Rect(region.x, region.y, region.width, region.height);
            ImageFeatures features = FeatureMatcher.detect(rgba, scale, grayscale, method, rect);
            features.sourceMat = rgba;
            int featureId = mNextFeatureId++;
            mFeatures.put(featureId, new FeatureState(features, rgba));
            return new JSONObject()
                    .put("id", featureId)
                    .put("count", features.count)
                    .put("width", features.originalWidth)
                    .put("height", features.originalHeight)
                    .put("scale", features.scale)
                    .put("method", features.method);
        } catch (Throwable error) {
            OpenCVHelper.release(rgba);
            throw error;
        }
    }

    /** task-172：特征匹配，返回四边形中心/四角（场景原图坐标）或 null（未匹配）。 */
    private JSONObject matchFeatures(JSONObject request) throws Exception {
        ensureOpen();
        ensureOpenCv();
        FeatureState scene = requireFeatures(request.optInt("sceneId", -1));
        FeatureState object = requireFeatures(request.optInt("objectId", -1));
        double ratio = request.optDouble("ratio", 0.7d);
        if (!Double.isFinite(ratio) || ratio <= 0d || ratio >= 1d) {
            throw new IllegalArgumentException("ratio 必须在 0..1 之间：" + ratio);
        }
        List<double[]> pairs = FeatureMatcher.match(scene.features, object.features, ratio);
        Point[] corners = FeatureMatcher.estimateFrameCorners(pairs,
                object.features.scaledWidth, object.features.scaledHeight);
        String drawPath = request.optString("drawMatches", "");
        if (corners == null) {
            // task-184：未匹配时也写 drawMatches 调试图（只画场景特征点，不画四边形）。
            // Pro 示例会无条件 viewFile('./matches.jpg')，若这里不写文件就报“文件不存在”。
            if (!drawPath.isEmpty() && scene.features.sourceMat != null) {
                try {
                    File drawFile = new File(drawPath);
                    File parent = drawFile.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        Log.w(TAG, "create drawMatches parent failed: " + parent);
                    }
                    FeatureMatcher.drawMatches(scene.features.sourceMat, pairs, null, drawPath);
                } catch (Throwable error) {
                    Log.w(TAG, "drawMatches(no match) failed: " + drawPath, error);
                }
            }
            return null;
        }
        // 场景「缩放后」坐标 -> 原图坐标（与 Rhino Images.matchFeatures 一致）。
        double inverse = scene.features.scale == 0 ? 1d : 1d / scene.features.scale;
        Point[] original = new Point[4];
        for (int i = 0; i < 4; i++) {
            original[i] = new Point(corners[i].x * inverse, corners[i].y * inverse);
        }
        double centerX = (original[0].x + original[1].x + original[2].x + original[3].x) / 4d;
        double centerY = (original[0].y + original[1].y + original[2].y + original[3].y) / 4d;
        if (!drawPath.isEmpty() && scene.features.sourceMat != null) {
            try {
                File drawFile = new File(drawPath);
                File parent = drawFile.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    Log.w(TAG, "create drawMatches parent failed: " + parent);
                }
                FeatureMatcher.drawMatches(scene.features.sourceMat, pairs, corners, drawPath);
            } catch (Throwable error) {
                Log.w(TAG, "drawMatches failed: " + drawPath, error);
            }
        }
        return new JSONObject()
                .put("center", pointD(centerX, centerY))
                .put("topLeft", pointD(original[0].x, original[0].y))
                .put("topRight", pointD(original[1].x, original[1].y))
                .put("bottomRight", pointD(original[2].x, original[2].y))
                .put("bottomLeft", pointD(original[3].x, original[3].y))
                .put("matches", pairs.size())
                .put("sceneWidth", scene.features.originalWidth)
                .put("sceneHeight", scene.features.originalHeight)
                .put("objectWidth", object.features.originalWidth)
                .put("objectHeight", object.features.originalHeight);
    }

    private FeatureState requireFeatures(int id) {
        FeatureState state = mFeatures.get(id);
        if (state == null) {
            throw new IllegalArgumentException("特征句柄不存在或已回收：" + id);
        }
        state.features.ensureUsable();
        return state;
    }

    private void recycleFeatures(int id) {
        FeatureState state = mFeatures.remove(id);
        if (state == null) {
            return;
        }
        try {
            state.features.recycle();
        } catch (Throwable error) {
            Log.w(TAG, "recycle features failed: " + id, error);
        }
        OpenCVHelper.release(state.sourceMat);
    }

    private static JSONObject pointD(double x, double y) throws JSONException {
        return new JSONObject().put("x", x).put("y", y);
    }

    private static final class FeatureState {
        final ImageFeatures features;
        final Mat sourceMat;

        FeatureState(ImageFeatures features, Mat sourceMat) {
            this.features = features;
            this.sourceMat = sourceMat;
        }
    }

    /** OpenCV 在 Node 线程按需同步初始化；失败时明确抛错，不进入 JNI 崩溃路径。 */
    private static void ensureOpenCv() {
        if (sOpenCvReady || OpenCVHelper.isInitialized()) {
            sOpenCvReady = true;
            return;
        }
        synchronized (Node24Images.class) {
            if (sOpenCvReady || OpenCVHelper.isInitialized()) {
                sOpenCvReady = true;
                return;
            }
            // Node24 宿主调用在 Node 线程（没有主 Looper），initIfNeeded 会同步执行；
            // context 只被异步初始化分支使用，这里不需要。
            final boolean[] finished = {false};
            OpenCVHelper.initIfNeeded(null, () -> finished[0] = true);
            if (!finished[0] || !OpenCVHelper.isInitialized()) {
                throw new IllegalStateException("OpenCV 初始化失败，无法执行 findImage");
            }
            sOpenCvReady = true;
        }
    }

    private boolean detectsColor(JSONObject request, boolean multi) throws Exception {
        ensureOpen();
        Bitmap bitmap = requireBitmap(requireId(request));
        int x = requireInt(request, "x");
        int y = requireInt(request, "y");
        checkPoint(bitmap, x, y);
        int color = parseColor(request.opt("color"), "color");
        int threshold = parseThreshold(request);
        if (!colorMatches(color, bitmap.getPixel(x, y), threshold)) {
            return false;
        }
        return !multi || matchesOffsets(bitmap, x, y, parseOffsetColors(request), threshold);
    }

    private boolean matchesOffsets(Bitmap bitmap, int x, int y, OffsetColor[] offsets, int threshold) {
        for (OffsetColor offset : offsets) {
            long px = (long) x + offset.offsetX;
            long py = (long) y + offset.offsetY;
            if (px < 0 || py < 0 || px >= bitmap.getWidth() || py >= bitmap.getHeight()) {
                return false;
            }
            if (!colorMatches(offset.color, bitmap.getPixel((int) px, (int) py), threshold)) {
                return false;
            }
        }
        return true;
    }

    private void recycle(int id) {
        Bitmap bitmap = mBitmaps.remove(id);
        if (bitmap == null) {
            // 契约要求 recycle 幂等：未知/已回收句柄也返回 true。
            return;
        }
        mLivePixels = Math.max(0L, mLivePixels - (long) bitmap.getWidth() * (long) bitmap.getHeight());
        if (!bitmap.isRecycled()) {
            try {
                bitmap.recycle();
            } catch (Throwable error) {
                Log.w(TAG, "recycle bitmap failed: " + id, error);
            }
        }
    }

    /** 任务结束/取消/异常统一回收；幂等。 */
    @Override
    public synchronized void close() {
        if (mClosed) {
            return;
        }
        mClosed = true;
        for (Iterator<Map.Entry<Integer, Bitmap>> iterator = mBitmaps.entrySet().iterator();
             iterator.hasNext(); ) {
            Bitmap bitmap = iterator.next().getValue();
            iterator.remove();
            if (bitmap != null && !bitmap.isRecycled()) {
                try {
                    bitmap.recycle();
                } catch (Throwable error) {
                    Log.w(TAG, "recycle bitmap on close failed", error);
                }
            }
        }
        mLivePixels = 0L;
        for (Iterator<Map.Entry<Integer, FeatureState>> iterator = mFeatures.entrySet().iterator();
             iterator.hasNext(); ) {
            FeatureState state = iterator.next().getValue();
            iterator.remove();
            if (state == null) {
                continue;
            }
            try {
                state.features.recycle();
            } catch (Throwable ignored) {
            }
            OpenCVHelper.release(state.sourceMat);
        }
    }

    // ------------------------------------------------------------------
    // 校验/解析（纯逻辑，便于 JVM 单测）
    // ------------------------------------------------------------------

    private void ensureOpen() {
        if (mClosed) {
            throw new IllegalStateException("本次 Node 任务的图片资源已释放");
        }
    }

    private Bitmap requireBitmap(int id) {
        Bitmap bitmap = mBitmaps.get(id);
        if (bitmap == null) {
            throw new IllegalArgumentException("图片句柄不存在或已回收：" + id);
        }
        if (bitmap.isRecycled()) {
            mBitmaps.remove(id);
            mLivePixels = Math.max(0L, mLivePixels - (long) bitmap.getWidth() * (long) bitmap.getHeight());
            throw new IllegalArgumentException("图片句柄已回收：" + id);
        }
        return bitmap;
    }

    private int requireId(JSONObject request) {
        if (!request.has("id")) {
            throw new IllegalArgumentException("image 请求缺少 id");
        }
        return request.optInt("id", -1);
    }

    static int requireInt(JSONObject request, String name) {
        if (!request.has(name)) {
            throw new IllegalArgumentException("image 请求缺少 " + name);
        }
        return request.optInt(name, 0);
    }

    private static String requirePath(JSONObject request) {
        String path = request.optString("path", "");
        if (path.isEmpty()) {
            throw new IllegalArgumentException("image 请求缺少 path");
        }
        return path;
    }

    static File safeFile(String path) {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("图片路径不能为空");
        }
        if (path.length() > MAX_PATH_CHARS) {
            throw new IllegalArgumentException("图片路径过长（上限 " + MAX_PATH_CHARS + " 字符）");
        }
        if (path.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("图片路径包含 NUL 字符");
        }
        File file = new File(path);
        if (!file.isAbsolute()) {
            throw new IllegalArgumentException("图片路径必须是绝对路径：" + path);
        }
        return file;
    }

    void checkDimensions(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("图片尺寸无效：" + width + "x" + height);
        }
        long pixels = (long) width * (long) height;
        if (pixels > mMaxImagePixels) {
            throw new IllegalArgumentException("图片尺寸过大：" + width + "x" + height
                    + "（" + pixels + " 像素，上限 " + mMaxImagePixels + "）");
        }
    }

    /**
     * 在解码/登记前检查存活句柄数与总像素预算，避免先分配内存再被 register 拒绝。
     * 调用方须先通过 {@link #checkDimensions(int, int)}。
     */
    void checkRegisterBudget(int width, int height) {
        if (mBitmaps.size() >= mMaxLiveImages) {
            throw new IllegalStateException("图片句柄数量已达上限（" + mMaxLiveImages
                    + "），请先 recycle() 不再使用的图片");
        }
        long pixels = (long) width * (long) height;
        if (mLivePixels + pixels > mMaxTotalPixels) {
            throw new IllegalStateException("图片总像素超过上限（" + mMaxTotalPixels
                    + "），请先 recycle() 不再使用的图片");
        }
    }

    private static void recycleQuietly(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            return;
        }
        try {
            bitmap.recycle();
        } catch (Throwable error) {
            Log.w(TAG, "recycle bitmap failed", error);
        }
    }

    static int parseThreshold(JSONObject request) {
        int threshold = request.optInt("threshold", 0);
        if (threshold < 0 || threshold > 255) {
            throw new IllegalArgumentException("threshold 必须在 0..255 之间，收到：" + threshold);
        }
        return threshold;
    }

    /** 模板相似度阈值只接受 0..1 的有限数字；默认值与 Pro 9.3 的 findImage 一致。 */
    static double parseSimilarity(JSONObject request, String name, double fallback) {
        if (!request.has(name) || request.isNull(name)) {
            return fallback;
        }
        Object raw = request.opt(name);
        if (!(raw instanceof Number)) {
            throw new IllegalArgumentException(name + " 必须是 0..1 之间的数字");
        }
        double value = ((Number) raw).doubleValue();
        if (Double.isNaN(value) || Double.isInfinite(value)
                || value < 0.0d || value > 1.0d) {
            throw new IllegalArgumentException(name + " 必须在 0..1 之间，收到：" + value);
        }
        return value;
    }

    /** -1 = 自动选择金字塔层数（Pro 的 MAX_LEVEL_AUTO）；手动层数限制在 0..8。 */
    static int parseMatchLevel(JSONObject request) {
        if (!request.has("level") || request.isNull("level")) {
            return TemplateMatching.MAX_LEVEL_AUTO;
        }
        Object raw = request.opt("level");
        if (!(raw instanceof Number)) {
            throw new IllegalArgumentException("level 必须是 -1..8 之间的整数");
        }
        double value = ((Number) raw).doubleValue();
        if (Double.isNaN(value) || Double.isInfinite(value) || value != Math.rint(value)
                || value < -1.0d || value > 8.0d) {
            throw new IllegalArgumentException("level 必须是 -1..8 之间的整数，收到：" + value);
        }
        return (int) value;
    }

    /** 颜色可为 signed ARGB 整数，也兼容 #RRGGBB / #AARRGGBB 字符串。 */
    static int parseColor(Object raw, String field) {
        if (raw == null || raw == JSONObject.NULL) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        if (raw instanceof Number) {
            long value = ((Number) raw).longValue();
            if (value < Integer.MIN_VALUE || value > 0xFFFFFFFFL) {
                throw new IllegalArgumentException(field + " 不是有效的 ARGB 整数：" + value);
            }
            return (int) value;
        }
        if (raw instanceof String) {
            String text = ((String) raw).trim();
            if (text.startsWith("#")) {
                text = text.substring(1);
            }
            try {
                if (text.length() == 6) {
                    return (int) (0xFF000000L | Long.parseLong(text, 16));
                }
                if (text.length() == 8) {
                    return (int) Long.parseLong(text, 16);
                }
            } catch (NumberFormatException ignored) {
                // 走下面的统一错误。
            }
            throw new IllegalArgumentException(field + " 只支持 #RRGGBB/#AARRGGBB 或 ARGB 整数：" + raw);
        }
        throw new IllegalArgumentException(field + " 只支持 ARGB 整数：" + raw);
    }

    /** 区域：[x,y] 表示剩余图片，[x,y,w,h] 或 {x,y,width,height} 表示矩形。 */
    static Region parseRegion(Object raw, int imageWidth, int imageHeight) {
        if (raw == null || raw == JSONObject.NULL) {
            return new Region(0, 0, imageWidth, imageHeight);
        }
        long x;
        long y;
        long width;
        long height;
        if (raw instanceof JSONObject) {
            JSONObject object = (JSONObject) raw;
            x = object.optLong("x", 0L);
            y = object.optLong("y", 0L);
            width = object.optLong("width", 0L);
            height = object.optLong("height", 0L);
        } else if (raw instanceof JSONArray) {
            JSONArray array = (JSONArray) raw;
            if (array.length() == 2) {
                x = array.optLong(0, 0L);
                y = array.optLong(1, 0L);
                width = (long) imageWidth - x;
                height = (long) imageHeight - y;
            } else if (array.length() == 4) {
                x = array.optLong(0, 0L);
                y = array.optLong(1, 0L);
                width = array.optLong(2, 0L);
                height = array.optLong(3, 0L);
            } else {
                throw new IllegalArgumentException("region 数组必须是 [x,y] 或 [x,y,width,height]");
            }
        } else {
            throw new IllegalArgumentException("region 必须是数组或 {x,y,width,height} 对象");
        }

        if (x < 0 || y < 0 || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("region 无效：" + x + "," + y + "," + width + "," + height);
        }
        if (x + width > imageWidth || y + height > imageHeight) {
            throw new IllegalArgumentException("region 超出图片范围：" + x + "," + y + "," + width
                    + "," + height + "，图片 " + imageWidth + "x" + imageHeight);
        }
        return new Region((int) x, (int) y, (int) width, (int) height);
    }

    static OffsetColor[] parseOffsetColors(JSONObject request) {
        JSONArray array = request.optJSONArray("offsetColors");
        if (array == null) {
            // 兼容 JS 侧按 Auto.js 习惯传 colors 的写法；协议字段仍以 offsetColors 为准。
            array = request.optJSONArray("colors");
        }
        if (array == null || array.length() == 0) {
            throw new IllegalArgumentException("多点找色需要非空的 offsetColors");
        }
        if (array.length() > MAX_OFFSET_COLORS) {
            throw new IllegalArgumentException("offsetColors 数量过多（上限 " + MAX_OFFSET_COLORS + "）");
        }
        OffsetColor[] result = new OffsetColor[array.length()];
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) {
                throw new IllegalArgumentException("offsetColors[" + i + "] 必须是对象");
            }
            int offsetX = requireInt(item, "offsetX");
            int offsetY = requireInt(item, "offsetY");
            int color = parseColor(item.opt("color"), "offsetColors[" + i + "].color");
            result[i] = new OffsetColor(offsetX, offsetY, color);
        }
        return result;
    }

    static boolean colorMatches(int expected, int actual, int threshold) {
        int dr = Math.abs(((expected >> 16) & 0xFF) - ((actual >> 16) & 0xFF));
        int dg = Math.abs(((expected >> 8) & 0xFF) - ((actual >> 8) & 0xFF));
        int db = Math.abs((expected & 0xFF) - (actual & 0xFF));
        // alpha 不参与比较（协议明确忽略 alpha）。
        return dr <= threshold && dg <= threshold && db <= threshold;
    }

    /** 行优先找第一个颜色匹配点，返回区域内的局部坐标；找不到返回 null。 */
    static int[] findFirstColor(int[] pixels, int width, int height, int color, int threshold) {
        if (pixels == null || width <= 0 || height <= 0
                || pixels.length < width * height) {
            return null;
        }
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                if (colorMatches(color, pixels[row + x], threshold)) {
                    return new int[]{x, y};
                }
            }
        }
        return null;
    }

    private static void checkPoint(Bitmap bitmap, int x, int y) {
        if (x < 0 || y < 0 || x >= bitmap.getWidth() || y >= bitmap.getHeight()) {
            throw new IllegalArgumentException("坐标超出图片范围：" + x + "," + y
                    + "，图片 " + bitmap.getWidth() + "x" + bitmap.getHeight());
        }
    }

    private static JSONObject descriptor(int id, Bitmap bitmap) throws JSONException {
        return new JSONObject().put("id", id)
                .put("width", bitmap.getWidth())
                .put("height", bitmap.getHeight());
    }

    private static JSONObject point(int x, int y) {
        try {
            return new JSONObject().put("x", x).put("y", y);
        } catch (JSONException impossible) {
            return null;
        }
    }

    private static JSONObject ok(Object value) {
        try {
            return new JSONObject().put("ok", true)
                    .put("value", value == null ? JSONObject.NULL : value);
        } catch (JSONException error) {
            return error("图片宿主响应编码失败：" + error.getMessage());
        }
    }

    private static JSONObject error(String message) {
        try {
            return new JSONObject().put("ok", false)
                    .put("error", message == null ? "未知图片错误" : message);
        } catch (JSONException ignored) {
            return new JSONObject();
        }
    }

    private static String describe(Throwable failure) {
        if (failure == null) {
            return "未知图片错误";
        }
        String message = failure.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = failure.getClass().getSimpleName();
        }
        return message;
    }

    static final class Region {
        final int x;
        final int y;
        final int width;
        final int height;

        Region(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }

    static final class OffsetColor {
        final int offsetX;
        final int offsetY;
        final int color;

        OffsetColor(int offsetX, int offsetY, int color) {
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.color = color;
        }
    }
}
