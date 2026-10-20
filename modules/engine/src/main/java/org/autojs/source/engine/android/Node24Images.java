// SPDX-License-Identifier: MIT
package org.autojs.source.engine.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.os.Build;
import android.system.Os;
import android.util.Base64;
import android.util.Log;

import com.stardust.app.GlobalAppContext;
import com.stardust.autojs.core.image.FeatureMatcher;
import com.stardust.autojs.core.image.ImageFeatures;
import com.stardust.autojs.core.image.TemplateMatching;
import com.stardust.autojs.core.opencv.Mat;
import com.stardust.autojs.core.opencv.OpenCVHelper;
import com.stardust.util.ResourceMonitor;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.opencv.android.Utils;
import org.opencv.core.CvType;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
    // dev-1172: 64 → 256，象棋 glyph 多尺度识别一帧会创建 ~200 个派生图；
    // Node24 由 JS 显式 recycle，64 会把正常识别链卡在句柄上限。总像素预算仍为 64M 兜底。
    static final int DEFAULT_MAX_LIVE_IMAGES = 256;
    /** 单张图片像素上限（16M：覆盖 4K 截图与常见平板，仍能限制异常大图）。 */
    static final long DEFAULT_MAX_IMAGE_PIXELS = 16_000_000L;
    /** 全部存活图片总像素上限（256MB ARGB 量级）。 */
    static final long DEFAULT_MAX_TOTAL_PIXELS = 64_000_000L;
    private static final long MAX_FILE_BYTES = 256L * 1024 * 1024;
    private static final int MAX_PATH_CHARS = 4096;
    /**
     * getPixels 单次返回的原始字节上限。
     *
     * <p>宿主桥单次 payload 有安全线：Node 请求经 Binder 传输时 Node24Client 明确限制
     * 196608 字符，响应虽走 JNI 字符串但仍按同一量级约束；Base64 后约为原始字节的 1.37 倍。
     * 这里把原始像素限制在 256KB（base64 后约 342KB 字符）。更大的区域请改用文件通道
     * （node-smem 负责），或先在 JS 侧 resize/clip 分块读取。
     */
    private static final int MAX_PIXEL_FETCH_BYTES = 256 * 1024;
    private static final int MAX_OFFSET_COLORS = 1024;
    /** matchTemplateMulti 单次最多模板数（限制一次宿主调用的 OpenCV 工作量）。 */
    private static final int MAX_MULTI_TEMPLATES = 64;

    // ------------------------------------------------------------------
    // matchTemplateMulti 性能缓存（task-5）
    //
    // 真机拆解（K40）：multi_11 = 8.3ms，其中 11 次模板 Bitmap→Mat 重复转换、每次
    // fastTemplateMatching 内部重复建源图金字塔是固定开销。模板 Bitmap 注册后不可变，
    // 可以安全地按图片 id 缓存 Mat；源图 Mat 也按 id 做有界 LRU（Bitmap 回收时失效）。
    // ------------------------------------------------------------------
    /** 模板 Mat 缓存条数上限（11 个棋盘模板 + 其他小图，32 足够且只占很小内存）。 */
    private static final int MAX_CACHED_TEMPLATE_MATS = 32;
    /** 源图 Mat 缓存条数上限（按访问顺序 LRU）。 */
    private static final int MAX_CACHED_SOURCE_MATS = 4;
    /** 源图 Mat 缓存总字节上限（全帧 ARGB Mat ≈ 10MB，最多留 3 张）。 */
    private static final long MAX_CACHED_SOURCE_MAT_BYTES = 32L * 1024 * 1024;
    /** 超过该像素数的源图不做缓存（避免单张 4K/超大图的 Mat 长期占内存）。 */
    private static final long MAX_CACHED_SOURCE_PIXELS = 4_000_000L;
    /** 多模板并行 worker 上限；requests 可用 {threads:1..4} 覆盖（1=串行）。 */
    private static final int MATCH_MAX_THREADS = 4;
    /**
     * 默认并行线程数：K40（8 核）取 3；可用请求字段 threads=1 关闭并行做 A/B。
     * 模板匹配是纯 CPU 的独立任务，并行不改变结果顺序。
     */
    private static final int DEFAULT_MATCH_THREADS = Math.max(2,
            Math.min(3, Runtime.getRuntime().availableProcessors() / 2));

    // ------------------------------------------------------------------
    // matchMany（task-12）：跨窗口批量匹配
    // ------------------------------------------------------------------
    /** matchMany 单次最多窗口数与「窗口×模板」任务数上限（限制 OpenCV 工作量与结果 JSON 大小）。 */
    private static final int MAX_MANY_WINDOWS = 256;
    private static final long MAX_MANY_TASKS = 8192;
    /** matchMany 全部窗口 clone 后总像素上限（4B/px，16M px ≈ 64MB，避免多窗口 clone 撑爆内存）。 */
    private static final long MAX_MANY_WINDOW_PIXELS = 16_000_000L;
    /** matchMany 协议并行度上限 8；默认 min(6, cores)（K40=6，覆盖 4 大核 + 2 小核）。 */
    private static final int MATCH_POOL_MAX_THREADS = 8;
    private static final int DEFAULT_MANY_THREADS = Math.max(2,
            Math.min(6, Runtime.getRuntime().availableProcessors()));
    /** 共享 worker 池实际大小：同时服务 multi（默认 3）与 matchMany（默认 6），按最大需求建池。 */
    private static final int MATCH_POOL_THREADS = Math.max(2, Math.min(MATCH_POOL_MAX_THREADS,
            Math.max(DEFAULT_MATCH_THREADS, DEFAULT_MANY_THREADS)));

    // ------------------------------------------------------------------
    // 文件式像素交换（pixelsToFile，task-2 / node-smem）
    // ------------------------------------------------------------------
    /** pixelsToFile scratch 子目录（位于 getCacheDir() 下，按进程 pid 复用同一个文件）。 */
    private static final String PIXEL_SCRATCH_DIR = "node24-pixels";
    /** pixelsToFile region 形态单次原始字节上限（= 单图 16M 像素 × 4B）。 */
    private static final long MAX_PIXEL_FILE_BYTES = 64L * 1024 * 1024;
    /** pixelsToFile 分块写的块预算：每块约 256KB，避免为整帧分配 4MB+ 临时数组。 */
    private static final int PIXEL_FILE_BLOCK_BYTES = 256 * 1024;
    /** pixelsToFile points 形态单次点数上限（int32 4MB）。 */
    private static final int MAX_PIXEL_FILE_POINTS = 1024 * 1024;
    /** points 形态「一次 getPixels 包围盒 + 索引」允许的包围盒字节上限。 */
    private static final long MAX_POINTS_BBOX_BYTES = 32L * 1024 * 1024;
    /** 本实例自动创建的 scratch 文件（close() 时 best-effort 删除；显式 path 不动）。 */
    private File mAutoPixelFile;

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
    /** 模板 Mat 缓存：id -> Mat（Bitmap 注册后不可变；recycle/close 时释放）。 */
    private final Map<Integer, CachedMat> mTemplateMats = new LinkedHashMap<>(16, 0.75f, true);
    /** 源图 Mat 缓存：id -> Mat，按访问顺序 LRU，受字节/条数上限约束。 */
    private final Map<Integer, CachedMat> mSourceMats = new LinkedHashMap<>(8, 0.75f, true);
    private long mCachedSourceMatBytes;
    /** 多模板匹配 worker 池（懒创建；daemon 线程，空闲 30s 自动回收）。 */
    private ThreadPoolExecutor mMatchExecutor;
    /** 进程内 OpenCV 只初始化一次；失败后仍允许下次重试。 */
    private static volatile boolean sOpenCvReady;

    // task-31：木色行列扫描的整帧 ARGB 像素复用缓冲（Bitmap.getPixels 一次读入）。
    private int[] mBoardPixels;

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
        // 调试包里 ResourceMonitor 默认启用：每次 new Mat 都会 fillInStackTrace + 记 SparseArray，
        // 是 Node24 图像桥（Java 侧大量短命 Mat）的主要固定开销；且 SparseArray 非线程安全，
        // 并行模板匹配会竞态。:node24 进程一次只跑一个 Node job，因此在单次 action 期间暂停检测，
        // 结束后恢复原状态（release 包 BuildConfig.DEBUG=false，本来就不启用）。
        boolean restoreResourceMonitor = ResourceMonitor.isEnabled();
        if (restoreResourceMonitor) {
            ResourceMonitor.setEnabled(false);
        }
        try {
            return handleAction(request);
        } finally {
            if (restoreResourceMonitor) {
                ResourceMonitor.setEnabled(true);
            }
        }
    }

    /** handle 的实际 dispatch（调用方已持有本实例锁并暂停了调试期资源监控）。 */
    private JSONObject handleAction(JSONObject request) {
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
                case "matchTemplateMulti":
                    return ok(matchTemplateMulti(request));
                case "matchMany":
                    return ok(matchMany(request));
                case "analyzeXiangqiBoard":
                    return ok(analyzeXiangqiBoard(request));
                case "detectXiangqiCircles":
                    return ok(detectCirclesAction(request));
                case "prepFrame":
                    return ok(prepFrame(request));
                case "grayscale":
                    return ok(grayscale(request));
                case "clip":
                    return ok(clip(request));
                case "threshold":
                    return ok(threshold(request));
                case "resize":
                    return ok(resize(request));
                case "sizeOf":
                    return ok(sizeOf(request));
                case "countColor":
                    return ok(countColor(request));
                case "getPixels":
                    return ok(getPixels(request));
                case "samplePixels":
                    return ok(samplePixels(request));
                case "pixelsToFile":
                    return ok(pixelsToFile(request));
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
                            + "findImage/matchTemplate/matchTemplateMulti/matchMany/prepFrame/"
                            + "analyzeXiangqiBoard/detectXiangqiCircles/"
                            + "grayscale/clip/threshold/resize/sizeOf/countColor/"
                            + "getPixels/samplePixels/pixelsToFile/detectsColor/detectsMultiColors/recycle/"
                            + "detectAndComputeFeatures/matchFeatures/recycleFeatures）");
            }
        } catch (Throwable failure) {
            Log.w(TAG, "image action failed: " + request, failure);
            return error(describe(failure));
        }
    }

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

    private static final int ICC_PROFILE_PROBE_BYTES = 256 * 1024;

    /** 文件头里是否带 ICC 色彩配置（PNG iCCP / JPEG APP2 ICC_PROFILE）。 */
    private static boolean hasEmbeddedIccProfile(File file) {
        long length = file.length();
        if (length <= 0L) {
            return false;
        }
        int probe = (int) Math.min(length, ICC_PROFILE_PROBE_BYTES);
        byte[] head = new byte[probe];
        try (java.io.FileInputStream input = new java.io.FileInputStream(file)) {
            int read = 0;
            while (read < probe) {
                int count = input.read(head, read, probe - read);
                if (count < 0) {
                    break;
                }
                read += count;
            }
            return indexOfBytes(head, read, "iCCP") >= 0
                    || indexOfBytes(head, read, "ICC_PROFILE") >= 0;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static int indexOfBytes(byte[] haystack, int length, String needle) {
        byte[] pattern = needle.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        int limit = length - pattern.length;
        outer:
        for (int i = 0; i <= limit; i++) {
            for (int k = 0; k < pattern.length; k++) {
                if (haystack[i + k] != pattern[k]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /**
     * 按 QuickJS（cv::imread IMREAD_UNCHANGED）口径解码：忽略 ICC，返回 ARGB_8888 Bitmap。
     *
     * <p>OpenCV 返回 BGR/BGRA/GRAY；按通道还原成 Android ARGB int（R/G/B/A 直通），
     * 与 QuickJS 原生帧 toRgba → pixel() 的 ARGB 结果一致。深度不是 8 位或通道数异常时
     * 返回 null，让调用方回退 BitmapFactory。
     */
    private Bitmap decodeIgnoringIcc(String path) throws Exception {
        ensureOpenCv();
        org.opencv.core.Mat decoded = null;
        try {
            decoded = Imgcodecs.imread(path, Imgcodecs.IMREAD_UNCHANGED);
            if (decoded == null || decoded.empty() || decoded.depth() != CvType.CV_8U) {
                return null;
            }
            int channels = decoded.channels();
            if (channels != 1 && channels != 3 && channels != 4) {
                return null;
            }
            int width = decoded.cols();
            int height = decoded.rows();
            if (width <= 0 || height <= 0) {
                return null;
            }
            checkDimensions(width, height);
            checkRegisterBudget(width, height);
            Bitmap bitmap;
            try {
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            } catch (OutOfMemoryError memory) {
                throw new IOException("图片解码内存不足（" + width + "x" + height + "）：" + path, memory);
            }
            int[] rowPixels = new int[width];
            byte[] rowBytes = new byte[width * channels];
            for (int y = 0; y < height; y++) {
                decoded.get(y, 0, rowBytes);
                for (int x = 0; x < width; x++) {
                    int offset = x * channels;
                    int b;
                    int g;
                    int r;
                    int a = 255;
                    if (channels == 4) {
                        b = rowBytes[offset] & 0xff;
                        g = rowBytes[offset + 1] & 0xff;
                        r = rowBytes[offset + 2] & 0xff;
                        a = rowBytes[offset + 3] & 0xff;
                    } else if (channels == 3) {
                        b = rowBytes[offset] & 0xff;
                        g = rowBytes[offset + 1] & 0xff;
                        r = rowBytes[offset + 2] & 0xff;
                    } else {
                        r = rowBytes[offset] & 0xff;
                        g = r;
                        b = r;
                    }
                    rowPixels[x] = (a << 24) | (r << 16) | (g << 8) | b;
                }
                bitmap.setPixels(rowPixels, 0, width, 0, y, width, 1);
            }
            return bitmap;
        } finally {
            if (decoded != null) {
                decoded.release();
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

        // task-28：Color-managed PNG/JPEG 的 ICC 差异。QuickJS 的 images.read 走
        // cv::imread(IMREAD_UNCHANGED)，libpng/libjpeg 不应用 ICC；而 BitmapFactory 会按内嵌
        // ICC 转 sRGB（f01 实测 R+5.4 / G-1.3 / B-5.0），导致两引擎同一张图像素不一致。
        // 带 ICC 的图改走 OpenCV 解码 + ARGB 还原，与 QuickJS 逐像素对齐；其余仍走
        // BitmapFactory（已用 1090 点验证与 QuickJS 0 diff，且更快、格式支持更全）。
        Bitmap iccBitmap = null;
        if (hasEmbeddedIccProfile(file)) {
            try {
                iccBitmap = decodeIgnoringIcc(path);
            } catch (Throwable error) {
                Log.w(TAG, "忽略 ICC 的 OpenCV 解码失败，回退 BitmapFactory：" + path, error);
                iccBitmap = null;
            }
        }
        if (iccBitmap != null) {
            return register(iccBitmap);
        }

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
     * 多模板模板匹配：一次宿主调用用同一个源图 Mat 依次跑多个模板
     * （与 {@link #matchTemplate(JSONObject)} 同一算法/结果语义），
     * 返回 {@code {results:[[{x,y,similarity}...],...]}}，results 与 templateIds 同序。
     *
     * <p>task-5 真机拆解（K40）：multi_11 ≈ 8.3ms。固定开销来自三处：
     * 1) 每个模板重复 Bitmap→Mat 转换；2) 每次 fastTemplateMatching 内部重复建源图金字塔；
     * 3) 模板匹配是纯 CPU 独立任务，逐模板串行浪费多核（单次 level0 匹配实测 ~1.6ms）。
     * 因此这里：模板 Mat 按 id 缓存（Bitmap 回收即失效）、源图 Mat 按 id 有界 LRU、
     * 模板按线程分摊并行执行；结果仍按 templateIds 原顺序组装，匹配语义不变。
     *
     * <p>请求可选 {@code threads:1..4}（1=串行）控制并行度；模板/句柄/阈值非法仍直接抛错。
     */
    private JSONObject matchTemplateMulti(JSONObject request) throws Exception {
        ensureOpen();
        int sourceId = requireId(request);
        JSONArray templateIds = request.optJSONArray("templateIds");
        if (templateIds == null || templateIds.length() == 0) {
            throw new IllegalArgumentException("matchTemplateMulti 缺少非空的 templateIds");
        }
        int count = templateIds.length();
        if (count > MAX_MULTI_TEMPLATES) {
            throw new IllegalArgumentException("matchTemplateMulti 模板过多：" + count
                    + "（上限 " + MAX_MULTI_TEMPLATES + "）");
        }
        Bitmap source = requireBitmap(sourceId);
        // 先解析全部模板句柄：任一模板非法时在碰 OpenCV 前失败。
        Bitmap[] templates = new Bitmap[count];
        int[] templateIdValues = new int[count];
        for (int i = 0; i < count; i++) {
            Object raw = templateIds.opt(i);
            if (!(raw instanceof Number)) {
                throw new IllegalArgumentException("templateIds[" + i + "] 必须是图片句柄 id");
            }
            templateIdValues[i] = ((Number) raw).intValue();
            templates[i] = requireBitmap(templateIdValues[i]);
        }
        double threshold = parseSimilarity(request, "threshold", 0.9d);
        double weakThreshold = parseSimilarity(request, "weakThreshold", 0.6d);
        int level = parseMatchLevel(request);
        int limit = Math.max(1, Math.min(4096, request.optInt("limit", 32)));
        Region region = parseRegion(request.opt("region"), source.getWidth(), source.getHeight());
        for (int i = 0; i < count; i++) {
            if (templates[i].getWidth() > region.width
                    || templates[i].getHeight() > region.height) {
                throw new IllegalArgumentException("templateIds[" + i + "] 模板 "
                        + templates[i].getWidth() + "x" + templates[i].getHeight()
                        + " is larger than source/search region "
                        + region.width + "x" + region.height);
            }
        }
        ensureOpenCv();
        List<MatLease> temporaryMats = new ArrayList<>();
        Mat regionMat = null;
        try {
            MatLease sourceLease = acquireSourceMat(sourceId, source);
            if (!sourceLease.cached) {
                temporaryMats.add(sourceLease);
            }
            Mat search = sourceLease.mat;
            if (region.x != 0 || region.y != 0 || region.width != source.getWidth()
                    || region.height != source.getHeight()) {
                regionMat = new Mat(search, new Rect(region.x, region.y,
                        region.width, region.height));
                search = regionMat;
            }
            Mat[] templateMats = new Mat[count];
            for (int i = 0; i < count; i++) {
                MatLease lease = acquireTemplateMat(templateIdValues[i], templates[i]);
                templateMats[i] = lease.mat;
                if (!lease.cached) {
                    temporaryMats.add(lease);
                }
            }
            JSONArray results = matchTemplatesInParallel(search, region.x, region.y,
                    templateMats, (float) weakThreshold, (float) threshold, level, limit,
                    parseMatchThreads(request, count));
            return new JSONObject().put("results", results);
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Throwable error) {
            throw new IllegalStateException("多模板匹配失败：" + describe(error), error);
        } finally {
            if (regionMat != null) {
                OpenCVHelper.release(regionMat);
            }
            for (MatLease lease : temporaryMats) {
                OpenCVHelper.release(lease.mat);
            }
        }
    }

    /** 模板/源图 Mat 的租约：cached=true 时 Mat 归缓存所有，调用方不要 release。 */
    private static final class MatLease {
        final Mat mat;
        final boolean cached;

        MatLease(Mat mat, boolean cached) {
            this.mat = mat;
            this.cached = cached;
        }
    }

    /** 缓存的 Mat 及其字节数（源图缓存按字节做 LRU 预算）。 */
    private static final class CachedMat {
        final Mat mat;
        final long bytes;

        CachedMat(Mat mat, long bytes) {
            this.mat = mat;
            this.bytes = bytes;
        }
    }

    /**
     * 取模板 Mat：模板 Bitmap 注册后不可变，按 id 缓存可省掉每次调用 N 次 bitmapToMat
     * （真机约 0.07ms/模板）。缓存满时释放最久未用的模板 Mat（访问顺序 LRU）。
     */
    private MatLease acquireTemplateMat(int id, Bitmap bitmap) {
        CachedMat cached = mTemplateMats.get(id);
        if (cached != null) {
            return new MatLease(cached.mat, true);
        }
        Mat mat = new Mat();
        try {
            Utils.bitmapToMat(bitmap, mat);
        } catch (Throwable error) {
            OpenCVHelper.release(mat);
            throw error;
        }
        if (mTemplateMats.size() >= MAX_CACHED_TEMPLATE_MATS) {
            Iterator<Map.Entry<Integer, CachedMat>> iterator = mTemplateMats.entrySet().iterator();
            if (iterator.hasNext()) {
                Mat evicted = iterator.next().getValue().mat;
                iterator.remove();
                OpenCVHelper.release(evicted);
            }
        }
        mTemplateMats.put(id, new CachedMat(mat, 0L));
        return new MatLease(mat, true);
    }

    /**
     * 取源图 Mat：按 id 做有界 LRU。真机整帧 bitmapToMat 约 5ms，重复对同一帧
     * （region 搜索 / 多个 action 复用）可以完全省掉；超过 4M 像素的大图不缓存。
     */
    private MatLease acquireSourceMat(int id, Bitmap bitmap) {
        CachedMat cached = mSourceMats.get(id);
        if (cached != null) {
            return new MatLease(cached.mat, true);
        }
        long pixels = (long) bitmap.getWidth() * (long) bitmap.getHeight();
        Mat mat = new Mat();
        try {
            Utils.bitmapToMat(bitmap, mat);
        } catch (Throwable error) {
            OpenCVHelper.release(mat);
            throw error;
        }
        if (pixels > MAX_CACHED_SOURCE_PIXELS) {
            return new MatLease(mat, false);
        }
        long bytes = pixels * 4L;
        mSourceMats.put(id, new CachedMat(mat, bytes));
        mCachedSourceMatBytes += bytes;
        Iterator<Map.Entry<Integer, CachedMat>> iterator = mSourceMats.entrySet().iterator();
        while ((mSourceMats.size() > MAX_CACHED_SOURCE_MATS
                || mCachedSourceMatBytes > MAX_CACHED_SOURCE_MAT_BYTES) && iterator.hasNext()) {
            CachedMat evicted = iterator.next().getValue();
            iterator.remove();
            mCachedSourceMatBytes = Math.max(0L, mCachedSourceMatBytes - evicted.bytes);
            OpenCVHelper.release(evicted.mat);
        }
        return new MatLease(mat, true);
    }

    /** 释放某个图片句柄关联的缓存 Mat（Bitmap 被回收/失效时调用）。 */
    private void invalidateCachedMats(int id) {
        CachedMat source = mSourceMats.remove(id);
        if (source != null) {
            mCachedSourceMatBytes = Math.max(0L, mCachedSourceMatBytes - source.bytes);
            OpenCVHelper.release(source.mat);
        }
        CachedMat template = mTemplateMats.remove(id);
        if (template != null) {
            OpenCVHelper.release(template.mat);
        }
    }

    /** 释放全部缓存 Mat（close 用）。 */
    private void releaseCachedMats() {
        for (CachedMat cached : mSourceMats.values()) {
            OpenCVHelper.release(cached.mat);
        }
        mSourceMats.clear();
        mCachedSourceMatBytes = 0L;
        for (CachedMat cached : mTemplateMats.values()) {
            OpenCVHelper.release(cached.mat);
        }
        mTemplateMats.clear();
    }

    /** 请求级并行度：默认 DEFAULT_MATCH_THREADS；threads=1 串行（A/B 用），上限 4。 */
    static int parseMatchThreads(JSONObject request, int count) {
        int threads = request.has("threads")
                ? request.optInt("threads", DEFAULT_MATCH_THREADS) : DEFAULT_MATCH_THREADS;
        threads = Math.max(1, Math.min(MATCH_MAX_THREADS, threads));
        return Math.max(1, Math.min(threads, count));
    }

    /** 懒创建的多模板匹配 worker 池；daemon 线程、空闲 30s 回收，close() 时 shutdownNow。 */
    private synchronized ThreadPoolExecutor matchExecutor() {
        if (mMatchExecutor == null) {
            int size = MATCH_POOL_THREADS;
            mMatchExecutor = new ThreadPoolExecutor(size, size, 30L, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<Runnable>(), runnable -> {
                        Thread thread = new Thread(runnable, "node24-match");
                        thread.setDaemon(true);
                        return thread;
                    });
            // 空闲线程 30s 后回收，避免 UI 保活期间常驻。
            mMatchExecutor.allowCoreThreadTimeOut(true);
        }
        return mMatchExecutor;
    }

    /**
     * 按模板并行执行 fastTemplateMatching：worker t 处理下标 t, t+threads, t+2*threads...
     * （交叉分配让两个“重模板”尽量落在不同线程），结果按下标写回，顺序与 templateIds 一致。
     * 任一模板失败时停止其余任务并把原始异常抛给调用方（与串行语义相同）。
     */
    private JSONArray matchTemplatesInParallel(final Mat search, final int offsetX, final int offsetY,
            final Mat[] templateMats, final float weakThreshold, final float strictThreshold,
            final int level, final int limit, final int threads) throws Exception {
        final JSONArray[] slots = new JSONArray[templateMats.length];
        if (threads <= 1 || templateMats.length <= 3) {
            for (int i = 0; i < templateMats.length; i++) {
                slots[i] = matchOneTemplate(search, offsetX, offsetY, templateMats[i],
                        weakThreshold, strictThreshold, level, limit);
            }
        } else {
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            List<Future<?>> futures = new ArrayList<>(threads);
            final ThreadPoolExecutor executor = matchExecutor();
            for (int t = 0; t < threads; t++) {
                final int offset = t;
                futures.add(executor.submit(() -> {
                    try {
                        for (int i = offset; i < templateMats.length && failure.get() == null;
                             i += threads) {
                            slots[i] = matchOneTemplate(search, offsetX, offsetY, templateMats[i],
                                    weakThreshold, strictThreshold, level, limit);
                        }
                    } catch (Throwable error) {
                        failure.compareAndSet(null, error);
                    }
                }));
            }
            awaitFutures(futures, failure, "多模板匹配");
        }
        JSONArray results = new JSONArray();
        for (JSONArray slot : slots) {
            results.put(slot == null ? new JSONArray() : slot);
        }
        return results;
    }

    /** 等待并行任务结束，并把首个失败按原异常类型抛回（IllegalArgumentException 保持原样）。 */
    private static void awaitFutures(List<Future<?>> futures, AtomicReference<Throwable> failure,
            String what) throws Exception {
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(what + "被中断", interrupted);
            } catch (ExecutionException wrapped) {
                if (failure.get() == null) {
                    failure.set(wrapped.getCause());
                }
            }
        }
        Throwable error = failure.get();
        if (error == null) {
            return;
        }
        if (error instanceof IllegalArgumentException) {
            throw (IllegalArgumentException) error;
        }
        if (error instanceof IllegalStateException) {
            throw (IllegalStateException) error;
        }
        throw new IllegalStateException(what + "失败：" + describe(error), error);
    }

    /** 单个模板匹配 + 结果 JSON（只读 search/templateMat，可多线程并发调用）。 */
    private static JSONArray matchOneTemplate(Mat search, int offsetX, int offsetY, Mat templateMat,
            float weakThreshold, float strictThreshold, int level, int limit) throws JSONException {
        List<TemplateMatching.Match> matches = TemplateMatching.fastTemplateMatching(
                search, templateMat, TemplateMatching.MATCHING_METHOD_DEFAULT,
                weakThreshold, strictThreshold, level, limit, false);
        JSONArray array = new JSONArray();
        for (TemplateMatching.Match match : matches) {
            array.put(new JSONObject()
                    .put("x", offsetX + (int) Math.round(match.point.x))
                    .put("y", offsetY + (int) Math.round(match.point.y))
                    .put("similarity", match.similarity));
        }
        return array;
    }

    /**
     * 跨窗口批量模板匹配（task-12）：一次宿主调用完成「多窗口 × 多模板」匹配，
     * 用于把「逐格 clip + 逐格 matchTemplateMulti」的 N 次桥往返 / N 张中间 Bitmap 合并成 1 次。
     *
     * <p>请求：{@code id},
     * {@code windows:[[x,y,w,h] | {x,y,width,height} | {region:[x,y,w,h] | {x,y,width,height}},...]},
     * {@code templateIds:[int,...]}（也接受 {@code templates}，元素为 id 数字或 {@code {id}}）,
     * {@code threshold/weakThreshold/level/limit}, {@code threads:1..8}。
     * <p>返回 {@code {results:[perWindow:[[{x,y,similarity}...],...],...]}}；坐标已加窗口偏移
     * （源图坐标系），窗口/模板顺序与请求一致。
     *
     * <p>实现：源图 bitmapToMat 走 T5 缓存只做一次；每个窗口只建一次 ROI 并 clone 成连续 Mat
     * （避免 fastTemplateMatching 对 ROI 做整源 UMat 映射）；任务粒度 = 窗口×模板，
     * 共享线程池交叉分配。窗口非法/模板大于窗口/数量超限都直接报错（带下标）。
     */
    private JSONObject matchMany(JSONObject request) throws Exception {
        ensureOpen();
        int sourceId = requireId(request);
        JSONArray windows = request.optJSONArray("windows");
        if (windows == null || windows.length() == 0) {
            throw new IllegalArgumentException("matchMany 缺少非空的 windows");
        }
        int windowCount = windows.length();
        if (windowCount > MAX_MANY_WINDOWS) {
            throw new IllegalArgumentException("matchMany 窗口过多：" + windowCount
                    + "（上限 " + MAX_MANY_WINDOWS + "）");
        }
        JSONArray templateIds = parseManyTemplateIds(request);
        int templateCount = templateIds.length();
        if (templateCount > MAX_MULTI_TEMPLATES) {
            throw new IllegalArgumentException("matchMany 模板过多：" + templateCount
                    + "（上限 " + MAX_MULTI_TEMPLATES + "）");
        }
        long taskCount = (long) windowCount * (long) templateCount;
        if (taskCount > MAX_MANY_TASKS) {
            throw new IllegalArgumentException("matchMany 任务过多：" + windowCount + " 窗口 x "
                    + templateCount + " 模板 = " + taskCount + "（上限 " + MAX_MANY_TASKS + "）");
        }
        Bitmap source = requireBitmap(sourceId);
        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        // 先完成全部参数/句柄校验，任何非法输入都在碰 OpenCV 前失败（不产生半成品 Mat）。
        Region[] regions = new Region[windowCount];
        for (int i = 0; i < windowCount; i++) {
            regions[i] = parseManyWindow(windows.opt(i), i, sourceWidth, sourceHeight);
        }
        long windowPixels = 0L;
        for (Region region : regions) {
            windowPixels += (long) region.width * (long) region.height;
        }
        if (windowPixels > MAX_MANY_WINDOW_PIXELS) {
            throw new IllegalArgumentException("matchMany 窗口总像素过大：" + windowPixels
                    + "（上限 " + MAX_MANY_WINDOW_PIXELS + "，请减少窗口或缩小窗口尺寸）");
        }
        Bitmap[] templates = new Bitmap[templateCount];
        int[] templateIdValues = new int[templateCount];
        for (int i = 0; i < templateCount; i++) {
            templateIdValues[i] = templateIds.optInt(i, -1);
            templates[i] = requireBitmap(templateIdValues[i]);
        }
        for (int w = 0; w < windowCount; w++) {
            for (int t = 0; t < templateCount; t++) {
                if (templates[t].getWidth() > regions[w].width
                        || templates[t].getHeight() > regions[w].height) {
                    throw new IllegalArgumentException("模板 " + t + " "
                            + templates[t].getWidth() + "x" + templates[t].getHeight()
                            + " 大于 windows[" + w + "] "
                            + regions[w].width + "x" + regions[w].height);
                }
            }
        }
        double threshold = parseSimilarity(request, "threshold", 0.9d);
        double weakThreshold = parseSimilarity(request, "weakThreshold", 0.6d);
        int level = parseMatchLevel(request);
        int limit = Math.max(1, Math.min(4096, request.optInt("limit", 32)));
        int threads = parseManyThreads(request, (int) taskCount);
        ensureOpenCv();
        List<MatLease> temporaryLeases = new ArrayList<>();
        List<Mat> windowMats = new ArrayList<>(windowCount);
        try {
            MatLease sourceLease = acquireSourceMat(sourceId, source);
            if (!sourceLease.cached) {
                temporaryLeases.add(sourceLease);
            }
            Mat[] templateMats = new Mat[templateCount];
            for (int t = 0; t < templateCount; t++) {
                MatLease lease = acquireTemplateMat(templateIdValues[t], templates[t]);
                templateMats[t] = lease.mat;
                if (!lease.cached) {
                    temporaryLeases.add(lease);
                }
            }
            int[] offsetsX = new int[windowCount];
            int[] offsetsY = new int[windowCount];
            for (int w = 0; w < windowCount; w++) {
                Region region = regions[w];
                Mat roi = new Mat(sourceLease.mat, new Rect(region.x, region.y,
                        region.width, region.height));
                try {
                    // ROI 只是 header（data != datastart）：fastTemplateMatching 内部 getUMat 会为
                    // 整张源图建映射，实测比先 clip 更慢；clone 成连续小 Mat 后走快路径。
                    windowMats.add(roi.clone());
                } finally {
                    OpenCVHelper.release(roi);
                }
                offsetsX[w] = region.x;
                offsetsY[w] = region.y;
            }
            JSONArray results = matchWindowsInParallel(windowMats, offsetsX, offsetsY,
                    templateMats, (float) weakThreshold, (float) threshold, level, limit, threads);
            return new JSONObject().put("results", results);
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Throwable error) {
            throw new IllegalStateException("批量匹配失败：" + describe(error), error);
        } finally {
            for (Mat windowMat : windowMats) {
                OpenCVHelper.release(windowMat);
            }
            for (MatLease lease : temporaryLeases) {
                OpenCVHelper.release(lease.mat);
            }
        }
    }

    /** matchMany 的模板列表：templateIds:[int] 为主协议，也接受 templates:[int | {id}]。 */
    static JSONArray parseManyTemplateIds(JSONObject request) {
        JSONArray raw = request.optJSONArray("templateIds");
        if (raw == null) {
            raw = request.optJSONArray("templates");
        }
        if (raw == null || raw.length() == 0) {
            throw new IllegalArgumentException("matchMany 缺少非空的 templateIds（或 templates）");
        }
        JSONArray ids = new JSONArray();
        for (int i = 0; i < raw.length(); i++) {
            Object item = raw.opt(i);
            Object id = item;
            if (item instanceof JSONObject) {
                id = ((JSONObject) item).opt("id");
            }
            if (!(id instanceof Number)) {
                throw new IllegalArgumentException("templateIds[" + i + "] 必须是图片句柄 id");
            }
            ids.put(((Number) id).intValue());
        }
        return ids;
    }

    /**
     * matchMany 窗口条目：数组 / {x,y,width,height} / {region:[...] 或 {x,y,width,height}}，
     * 统一复用 parseRegion 做越界校验；错误信息带 windows 下标。
     */
    static Region parseManyWindow(Object raw, int index, int imageWidth, int imageHeight) {
        Object region = raw;
        if (raw instanceof JSONObject && ((JSONObject) raw).has("region")) {
            region = ((JSONObject) raw).opt("region");
        }
        if (region == null || region == JSONObject.NULL) {
            throw new IllegalArgumentException("windows[" + index + "] 不能为空");
        }
        try {
            return parseRegion(region, imageWidth, imageHeight);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("windows[" + index + "] " + error.getMessage(), error);
        }
    }

    /** matchMany 并行度：默认 min(6, cores)；threads=1 串行（A/B）；上限取共享池大小。 */
    static int parseManyThreads(JSONObject request, int taskCount) {
        int threads = request.has("threads")
                ? request.optInt("threads", DEFAULT_MANY_THREADS) : DEFAULT_MANY_THREADS;
        threads = Math.max(1, Math.min(MATCH_POOL_THREADS, threads));
        return Math.max(1, Math.min(threads, taskCount));
    }

    /**
     * 窗口×模板并行匹配：任务下标 task = windowIndex * templateCount + templateIndex，
     * worker 取 task = offset, offset+threads, ... 交叉分配（同一窗口的 11 个模板分散到不同
     * worker，两个“重模板”不会挤在同一个线程）；结果写入 slots[window][template]，
     * 组装时保持 windows/templateIds 原顺序。任一任务失败停止其余任务并按原异常抛回。
     */
    private JSONArray matchWindowsInParallel(final List<Mat> windowMats, final int[] offsetsX,
            final int[] offsetsY, final Mat[] templateMats, final float weakThreshold,
            final float strictThreshold, final int level, final int limit, final int threads)
            throws Exception {
        final int windowCount = windowMats.size();
        final int templateCount = templateMats.length;
        final int taskTotal = windowCount * templateCount;
        final JSONArray[][] slots = new JSONArray[windowCount][templateCount];
        if (threads <= 1 || taskTotal <= 3) {
            for (int w = 0; w < windowCount; w++) {
                for (int t = 0; t < templateCount; t++) {
                    slots[w][t] = matchOneTemplate(windowMats.get(w), offsetsX[w], offsetsY[w],
                            templateMats[t], weakThreshold, strictThreshold, level, limit);
                }
            }
        } else {
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            List<Future<?>> futures = new ArrayList<>(threads);
            final ThreadPoolExecutor executor = matchExecutor();
            for (int worker = 0; worker < threads; worker++) {
                final int offset = worker;
                futures.add(executor.submit(() -> {
                    try {
                        for (int task = offset; task < taskTotal && failure.get() == null;
                             task += threads) {
                            int windowIndex = task / templateCount;
                            int templateIndex = task % templateCount;
                            slots[windowIndex][templateIndex] = matchOneTemplate(
                                    windowMats.get(windowIndex), offsetsX[windowIndex],
                                    offsetsY[windowIndex], templateMats[templateIndex],
                                    weakThreshold, strictThreshold, level, limit);
                        }
                    } catch (Throwable error) {
                        failure.compareAndSet(null, error);
                    }
                }));
            }
            awaitFutures(futures, failure, "批量匹配");
        }
        JSONArray results = new JSONArray();
        for (int w = 0; w < windowCount; w++) {
            JSONArray perWindow = new JSONArray();
            for (int t = 0; t < templateCount; t++) {
                perWindow.put(slots[w][t] == null ? new JSONArray() : slots[w][t]);
            }
            results.put(perWindow);
        }
        return results;
    }

    /**
     * 灰度化（宿主 {@code images.grayscale}）：RGBA→GRAY→RGBA，返回同尺寸新句柄，原句柄不受影响。
     * 权重用 OpenCV {@code COLOR_RGBA2GRAY}（BT.601，0.299R+0.587G+0.114B），与 Rhino/QuickJS
     * 引擎一致；不能用 BGR 权重（会压暗浅木色棋子/亮面，破坏亮度门）。
     */
    private JSONObject grayscale(JSONObject request) throws Exception {
        ensureOpen();
        Bitmap source = requireBitmap(requireId(request));
        checkRegisterBudget(source.getWidth(), source.getHeight());
        ensureOpenCv();
        Mat rgba = new Mat();
        Mat gray = new Mat();
        try {
            Utils.bitmapToMat(source, rgba);
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);
            // OpenCV android 的 Utils.matToBitmap 对 CV_8UC1 直接 GRAY2RGBA 写进 Bitmap
            // （modules/java/generator/src/cpp/utils.cpp），省掉一次整帧 RGBA Mat 分配 + 转换 + 拷贝。
            return register(matToBitmap(gray));
        } finally {
            OpenCVHelper.release(gray);
            OpenCVHelper.release(rgba);
        }
    }

    /**
     * 裁剪（宿主 {@code images.clip(img, x, y, w, h)}）：按 region 复制一块新图。
     *
     * <p>这里用 Canvas 复制而不是 {@code Bitmap.createBitmap(source, x, y, w, h)}：后者在
     * 整图区域会直接返回原 Bitmap（同一对象），两个句柄共享生命周期，recycle 任一个都会让
     * 另一个失效。region 越界仍由 {@link #parseRegion} 拒绝。
     */
    private JSONObject clip(JSONObject request) throws Exception {
        ensureOpen();
        Bitmap source = requireBitmap(requireId(request));
        Region region = parseRegion(request.opt("region"), source.getWidth(), source.getHeight());
        checkRegisterBudget(region.width, region.height);
        Bitmap clipped = Bitmap.createBitmap(region.width, region.height, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(clipped);
            canvas.drawBitmap(source, -region.x, -region.y, null);
        } catch (RuntimeException error) {
            recycleQuietly(clipped);
            throw error;
        }
        return register(clipped);
    }

    /**
     * 二值化（宿主 {@code images.threshold(img, thresh, maxVal, type)}）：
     * 先 RGBA→GRAY，再 OpenCV threshold，最后 GRAY→RGBA 输出新句柄。
     * type 默认 BINARY(0)，支持 BINARY_INV/TRUNC/TOZERO/TOZERO_INV 及叠加 OTSU/TRIANGLE 标志。
     */
    private JSONObject threshold(JSONObject request) throws Exception {
        ensureOpen();
        Bitmap source = requireBitmap(requireId(request));
        double thresh = parseThresholdValue(request, "threshold", 128d);
        // 协议字段以 maxValue 为准，同时兼容宿主/Pro 常见的 max。
        double maxValue = parseThresholdValue(request,
                request.has("maxValue") ? "maxValue" : "max", 255d);
        int type = parseThresholdType(request.opt("type"));
        checkRegisterBudget(source.getWidth(), source.getHeight());
        ensureOpenCv();
        Mat rgba = new Mat();
        Mat gray = new Mat();
        Mat binary = new Mat();
        try {
            Utils.bitmapToMat(source, rgba);
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);
            Imgproc.threshold(gray, binary, thresh, maxValue, type);
            // 同 grayscale：CV_8UC1 直接交给 matToBitmap，省一次整帧 RGBA 中转。
            return register(matToBitmap(binary));
        } finally {
            OpenCVHelper.release(binary);
            OpenCVHelper.release(gray);
            OpenCVHelper.release(rgba);
        }
    }

    /**
     * 缩放（宿主 {@code images.resize(img, size, interpolation)}）：输出 width×height 新句柄。
     * interpolation 默认 INTER_LINEAR(1)，与 Rhino/QuickJS 一致；尺寸受
     * {@link #checkDimensions} 单图像素上限与登记预算约束。
     */
    private JSONObject resize(JSONObject request) throws Exception {
        ensureOpen();
        Bitmap source = requireBitmap(requireId(request));
        int width = requireInt(request, "width");
        int height = requireInt(request, "height");
        checkDimensions(width, height);
        checkRegisterBudget(width, height);
        int interpolation = parseInterpolation(request);
        ensureOpenCv();
        Mat rgba = new Mat();
        Mat output = new Mat();
        try {
            Utils.bitmapToMat(source, rgba);
            Imgproc.resize(rgba, output, new Size(width, height), 0d, 0d, interpolation);
            return register(matToBitmap(output));
        } finally {
            OpenCVHelper.release(output);
            OpenCVHelper.release(rgba);
        }
    }

    /**
     * 帧预处理批量 action（task-5）：一次宿主调用完成「灰度化(+缩放)+尺寸」，
     * 替代 Node 侧 2×grayscale + 1×resize + N×sizeOf 的多次桥往返（真机 ~33ms 级）。
     *
     * <p>请求：{@code {id, needGray?(默认 true), resize?:[width,height]}}
     * <p>返回：{@code {gray:{id,width,height}|null, resized:{id,width,height}|null,
     * size:{width,height}}}；gray/resized 都是新登记的图片句柄（调用方负责 recycle）。
     * 源图 Mat 只转换一次；resize 作用在灰度图上（1 通道，比 RGBA 缩放省 3/4 带宽）。
     */
    private JSONObject prepFrame(JSONObject request) throws Exception {
        ensureOpen();
        Bitmap source = requireBitmap(requireId(request));
        boolean needGray = !request.has("needGray") || request.optBoolean("needGray", true);
        int resizeWidth = 0;
        int resizeHeight = 0;
        boolean resize = false;
        if (request.has("resize") && !request.isNull("resize")) {
            JSONArray size = request.optJSONArray("resize");
            if (size == null || size.length() != 2) {
                throw new IllegalArgumentException("prepFrame 的 resize 必须是 [width,height]");
            }
            resizeWidth = size.optInt(0, 0);
            resizeHeight = size.optInt(1, 0);
            checkDimensions(resizeWidth, resizeHeight);
            resize = true;
        }
        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        JSONObject response = new JSONObject()
                .put("size", new JSONObject().put("width", sourceWidth).put("height", sourceHeight))
                .put("gray", JSONObject.NULL)
                .put("resized", JSONObject.NULL);
        if (!needGray && !resize) {
            return response;
        }
        ensureOpenCv();
        Mat rgba = new Mat();
        Mat gray = new Mat();
        Mat resizedMat = null;
        JSONObject grayDescriptor = null;
        try {
            Utils.bitmapToMat(source, rgba);
            if (needGray) {
                Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);
                checkRegisterBudget(sourceWidth, sourceHeight);
                grayDescriptor = register(matToBitmap(gray));
                response.put("gray", grayDescriptor);
            }
            if (resize) {
                checkRegisterBudget(resizeWidth, resizeHeight);
                if (needGray) {
                    resizedMat = new Mat();
                    Imgproc.resize(gray, resizedMat,
                            new Size(resizeWidth, resizeHeight), 0d, 0d, Imgproc.INTER_LINEAR);
                    response.put("resized", register(matToBitmap(resizedMat)));
                } else {
                    Imgproc.resize(rgba, gray, new Size(resizeWidth, resizeHeight), 0d, 0d,
                            Imgproc.INTER_LINEAR);
                    response.put("resized", register(matToBitmap(gray)));
                }
            }
            return response;
        } catch (Throwable error) {
            // gray 已登记但本次调用失败时（例如 resized 登记超预算）回收它，避免 JS 拿不到句柄还占预算。
            if (grayDescriptor != null) {
                recycle(grayDescriptor.optInt("id", -1));
            }
            throw error;
        } finally {
            if (resizedMat != null) {
                OpenCVHelper.release(resizedMat);
            }
            OpenCVHelper.release(gray);
            OpenCVHelper.release(rgba);
        }
    }

    /** 图片尺寸（宿主 {@code images.sizeOf(img)}）：不分配新图，返回 {@code {width,height}}。 */
    private JSONObject sizeOf(JSONObject request) throws Exception {
        ensureOpen();
        Bitmap bitmap = requireBitmap(requireId(request));
        return new JSONObject()
                .put("width", bitmap.getWidth())
                .put("height", bitmap.getHeight());
    }

    /**
     * 颜色计数（宿主 {@code images.countColor(img, color, {region, threshold})}）：
     * 与找色同一套比较规则（RGB 三通道最大差值 &lt;= threshold，忽略 alpha），返回命中像素数。
     * 按行读取像素，内存只占一行，避免整帧 int[] 分配。
     */
    private int countColor(JSONObject request) throws Exception {
        ensureOpen();
        Bitmap bitmap = requireBitmap(requireId(request));
        int color = parseColor(request.opt("color"), "color");
        int threshold = parseThreshold(request);
        Region region = parseRegion(request.opt("region"), bitmap.getWidth(), bitmap.getHeight());
        int[] row = new int[region.width];
        int count = 0;
        for (int y = 0; y < region.height; y++) {
            bitmap.getPixels(row, 0, region.width, region.x, region.y + y, region.width, 1);
            for (int x = 0; x < region.width; x++) {
                if (colorMatches(color, row[x], threshold)) {
                    count++;
                }
            }
        }
        return count;
    }

    // ------------------------------------------------------------------
    // 象棋棋盘几何分析（task-26 移植 / task-31 性能优化）
    //
    // 忠实移植 modules/engine/src/main/cpp/xiangqi_board_detector.cpp：木色行/列支持带 →
    // Canny+HoughLinesP 格线投票 → 9×10 网格最小二乘拟合 → HoughCircles 棋子圆 + 红墨比例。
    // 输出 JSON 与 quickjs_jni.cpp:3800-3900 的 JNI 结构完全同构（task-31 前后逐字段不变）。
    //
    // task-31 性能要点：
    //  · 木色扫描一次 Bitmap.getPixels 整块读入 int[]，直接在 ARGB int 上判断并算红墨占比
    //    （不再 bitmapToMat 全图 + 逐行 Mat.get）；
    //  · 只对棋盘 ROI 构造 CV_8UC4 Mat 再 cvtColor（Canny/Hough 用），圆检测同样只构造圆窗口
    //    ROI，避免整帧 RGBA/GRAY Mat；
    //  · 像素/字节/Mat 缓冲按实例复用，Mat 用 ensureMat 按尺寸 create；
    //  · 分段计时写 logcat：xiangqi-time: pixels=..ms wood=..ms gray=..ms canny=..ms hough=..ms
    //    fit=..ms circles=..ms total=..ms
    // ------------------------------------------------------------------

    /** 棋盘分析结果（对应 C++ XiangqiBoardAnalysis）。 */
    static final class BoardAnalysis {
        boolean ok;
        String reason = "";
        Rect outer = new Rect(0, 0, 0, 0);
        double originX;
        double originY;
        double stepX;
        double stepY;
        double lineScoreX;
        double lineScoreY;
        int verticalSegments;
        int horizontalSegments;
        /** 诊断：HoughLinesP 原始线段数 / 参数（失败时也能定位是哪一步）。 */
        int houghSegments;
        int minLine;
        int maxGap;
        int rowBandX;
        int rowBandWidth;
        int colBandX;
        int colBandWidth;
        AxisFit fitX;
        AxisFit fitY;
        /** task-31：分段计时摘要（debug=true 时随 JSON 返回，便于真机取数；默认不输出）。 */
        String timing = "";
        List<XiangqiCircle> circles = new ArrayList<>();
    }

    /** 棋子圆（对应 C++ XiangqiCircle；x/y/radius/error/redFraction 为 float 与 native 输出一致）。 */
    static final class XiangqiCircle {
        float x;
        float y;
        float radius;
        float redFraction;
        float error;
        int col;
        int row;
        String color;
    }

    /** 轴向拟合结果（对应 C++ AxisFit）。 */
    static final class AxisFit {
        double origin;
        double step;
        double score = -1;
        int supported;
    }

    private JSONObject analyzeXiangqiBoard(JSONObject request) throws Exception {
        ensureOpen();
        Bitmap source = requireBitmap(requireId(request));
        ensureOpenCv();
        // debug=true 时失败结果额外携带中间量（lineScore/segments/fit 候选等）；
        // 默认 false 时保持与 JNI 完全同构（失败只回 ok/reason）。
        boolean debug = request.optBoolean("debug", false);
        return boardAnalysisJson(analyzeBoard(source), debug);
    }

    private JSONArray detectCirclesAction(JSONObject request) throws Exception {
        ensureOpen();
        Bitmap source = requireBitmap(requireId(request));
        JSONObject grid = request.optJSONObject("grid");
        if (grid == null) {
            throw new IllegalArgumentException(
                    "detectXiangqiCircles 缺少 grid {originX,originY,stepX,stepY}");
        }
        double originX = requireFiniteNumber(grid.opt("originX"), "grid.originX");
        double originY = requireFiniteNumber(grid.opt("originY"), "grid.originY");
        double stepX = requireFiniteNumber(grid.opt("stepX"), "grid.stepX");
        double stepY = requireFiniteNumber(grid.opt("stepY"), "grid.stepY");
        ensureOpenCv();
        long started = System.nanoTime();
        Mat rgba = new Mat();
        try {
            Utils.bitmapToMat(source, rgba);
            return circlesJson(detectBoardCircles(rgba, originX, originY, stepX, stepY,
                    "detect-circles", started));
        } finally {
            OpenCVHelper.release(rgba);
        }
    }

    private static JSONObject boardAnalysisJson(BoardAnalysis analysis) throws JSONException {
        return boardAnalysisJson(analysis, false);
    }

    private static JSONObject boardAnalysisJson(BoardAnalysis analysis, boolean debug) throws JSONException {
        JSONObject result = new JSONObject()
                .put("ok", analysis.ok)
                .put("reason", analysis.reason == null ? "" : analysis.reason);
        if (!analysis.ok) {
            if (debug) {
                result.put("outer", new JSONObject()
                        .put("x", analysis.outer.x).put("y", analysis.outer.y)
                        .put("width", analysis.outer.width).put("height", analysis.outer.height));
                result.put("lineScoreX", analysis.lineScoreX)
                        .put("lineScoreY", analysis.lineScoreY)
                        .put("verticalSegments", analysis.verticalSegments)
                        .put("horizontalSegments", analysis.horizontalSegments)
                        .put("houghSegments", analysis.houghSegments)
                        .put("minLine", analysis.minLine)
                        .put("maxGap", analysis.maxGap)
                        .put("rowBand", new JSONObject().put("x", analysis.rowBandX)
                                .put("width", analysis.rowBandWidth))
                        .put("colBand", new JSONObject().put("x", analysis.colBandX)
                                .put("width", analysis.colBandWidth));
                if (analysis.fitX != null) {
                    result.put("xfit", axisFitJson(analysis.fitX));
                }
                if (analysis.fitY != null) {
                    result.put("yfit", axisFitJson(analysis.fitY));
                }
            }
            return result;
        }
        result.put("outer", new JSONObject()
                .put("x", analysis.outer.x)
                .put("y", analysis.outer.y)
                .put("width", analysis.outer.width)
                .put("height", analysis.outer.height));
        result.put("grid", new JSONObject()
                .put("originX", analysis.originX)
                .put("originY", analysis.originY)
                .put("stepX", analysis.stepX)
                .put("stepY", analysis.stepY));
        result.put("lineScoreX", analysis.lineScoreX)
                .put("lineScoreY", analysis.lineScoreY)
                .put("verticalSegments", analysis.verticalSegments)
                .put("horizontalSegments", analysis.horizontalSegments)
                .put("circles", circlesJson(analysis.circles));
        if (debug && analysis.timing != null && !analysis.timing.isEmpty()) {
            result.put("timing", analysis.timing);
        }
        return result;
    }

    private static JSONObject axisFitJson(AxisFit fit) throws JSONException {
        return new JSONObject()
                .put("origin", fit.origin)
                .put("step", fit.step)
                .put("supported", fit.supported)
                .put("score", fit.score);
    }

    private static JSONArray circlesJson(List<XiangqiCircle> circles) throws JSONException {
        JSONArray array = new JSONArray();
        for (XiangqiCircle circle : circles) {
            array.put(new JSONObject()
                    .put("x", (double) circle.x)
                    .put("y", (double) circle.y)
                    .put("radius", (double) circle.radius)
                    .put("redFraction", (double) circle.redFraction)
                    .put("error", (double) circle.error)
                    .put("col", circle.col)
                    .put("row", circle.row)
                    .put("color", circle.color));
        }
        return array;
    }

    /** 与 OpenCV cvRound 一致的「四舍六入五成双」取整（C++ 移植保真）。 */
    private static int cvRound(double value) {
        return (int) Math.rint(value);
    }

    private static boolean isWoodPixel(int r, int g, int b) {
        return r > 130 && g > 105 && 100 * r > 106 * g && 100 * g > 107 * b;
    }

    /** 平滑一维支持度后取最长连续带（C++ longestBand 的逐行移植）。 */
    static Rect longestBand(double[] support, int window, double threshold) {
        if (support == null || support.length == 0) {
            return new Rect(0, 0, 0, 0);
        }
        int half = window / 2;
        double[] prefix = new double[support.length + 1];
        for (int i = 0; i < support.length; i++) {
            prefix[i + 1] = prefix[i] + support[i];
        }
        int bestStart = 0;
        int bestLength = 0;
        int start = -1;
        for (int i = 0; i <= support.length; i++) {
            boolean on = false;
            if (i < support.length) {
                int lo = Math.max(0, i - half);
                int hi = Math.min(support.length, i + half + 1);
                // 与 C++ 注释一致：np.convolve('same') 在边界仍除以 window，不是实际核长度。
                on = (prefix[hi] - prefix[lo]) / window > threshold;
            }
            if (on && start < 0) {
                start = i;
            }
            if (!on && start >= 0) {
                if (i - start > bestLength) {
                    bestStart = start;
                    bestLength = i - start;
                }
                start = -1;
            }
        }
        return new Rect(bestStart, 0, bestLength, 1);
    }

    /**
     * 投票数组的线性插值（C++ interpolated）。
     *
     * <p>用 Java float[] 而不是 Mat：fitAxis 会做约 10 万次插值，逐次 Mat.get 的 JNI 开销会
     * 把一次棋盘分析拖到几十毫秒（C++ 侧是连续内存直接读；这里一次性把 CV_32F 拷成 float[]）。
     */
    private static double interpolated(float[] votes, int length, double coordinate) {
        if (coordinate < 0 || coordinate >= length - 1) {
            return 0;
        }
        int lo = (int) coordinate;
        double fraction = coordinate - lo;
        double left = votes[lo];
        double right = votes[lo + 1];
        return left * (1 - fraction) + right * fraction;
    }

    /** 网格轴拟合（C++ fitAxis）：在候选 origin/step 里最大化 log1p(vote) 总和并统计票数。 */
    static AxisFit fitAxis(float[] votes, int length, int boardStart, int boardLength, int count,
            double originMin, double originMax) {
        AxisFit best = new AxisFit();
        for (double origin = boardStart + boardLength * originMin;
                origin < boardStart + boardLength * originMax; origin += 1) {
            for (double step = boardLength * .09; step < boardLength * .12; step += .25) {
                if (origin + (count - 1) * step >= length) {
                    continue;
                }
                double score = 0;
                int supported = 0;
                for (int i = 0; i < count; i++) {
                    double vote = interpolated(votes, length, origin + i * step);
                    score += Math.log1p(vote);
                    if (vote >= 2) {
                        supported++;
                    }
                }
                if (score > best.score) {
                    best.origin = origin;
                    best.step = step;
                    best.score = score;
                    best.supported = supported;
                }
            }
        }
        return best;
    }

    /** 圆内红墨占比（C++ redInkFraction；按 BT.601 灰度过滤亮像素后统计红字）。 */
    private static double redInkFraction(Mat rgba, double circleX, double circleY, double circleRadius) {
        int centerX = cvRound(circleX);
        int centerY = cvRound(circleY);
        int radius = Math.max(5, cvRound(circleRadius * .52));
        double limit = Math.pow(radius * .85, 2);
        int red = 0;
        int ink = 0;
        byte[] row = new byte[rgba.cols() * 4];
        for (int dy = -radius; dy <= radius; dy++) {
            int y = centerY + dy;
            if (y < 0 || y >= rgba.rows()) {
                continue;
            }
            rgba.get(y, 0, row);
            for (int dx = -radius; dx <= radius; dx++) {
                int x = centerX + dx;
                if (x < 0 || x >= rgba.cols() || dx * dx + dy * dy >= limit) {
                    continue;
                }
                int offset = x * 4;
                int r = row[offset] & 0xff;
                int g = row[offset + 1] & 0xff;
                int b = row[offset + 2] & 0xff;
                int gray = (77 * r + 150 * g + 29 * b) >> 8;
                if (gray >= 150) {
                    continue;
                }
                ink++;
                if (100 * r > 125 * g && 100 * r > 145 * b) {
                    red++;
                }
            }
        }
        return ink > 0 ? (double) red / ink : 0;
    }

    /**
     * 9×10 网格上的棋子圆检测（C++ detectXiangqiCircles；结果按 row*9+col 升序，等价 std::map）。
     *
     * <p>task-31：保持与优化前完全相同的输入路径（rgba 全帧 Mat 的 ROI 子矩阵 → cvtColor →
     * HoughCircles），只加分段计时日志；输出与 task31-baseline 逐字段一致。
     */
    private List<XiangqiCircle> detectBoardCircles(Mat rgba, double originX, double originY,
            double stepX, double stepY, String timingTag, long requestStartedNanos) {
        List<XiangqiCircle> output = new ArrayList<>();
        if (rgba.empty() || rgba.type() != CvType.CV_8UC4 || stepX < 20 || stepY < 20) {
            return output;
        }
        long tFillStart = System.nanoTime();
        double pitch = (stepX + stepY) / 2;
        int left = Math.max(0, (int) Math.floor(originX - stepX * .7));
        int top = Math.max(0, (int) Math.floor(originY - stepY * .7));
        int right = Math.min(rgba.cols(), (int) Math.ceil(originX + stepX * 8.7));
        int bottom = Math.min(rgba.rows(), (int) Math.ceil(originY + stepY * 9.7));
        if (right <= left || bottom <= top) {
            return output;
        }
        Mat roi = new Mat(rgba, new Rect(left, top, right - left, bottom - top));
        Mat gray = new Mat();
        Mat found = new Mat();
        try {
            long tGrayStart = System.nanoTime();
            Imgproc.cvtColor(roi, gray, Imgproc.COLOR_RGBA2GRAY);
            long tHoughStart = System.nanoTime();
            Imgproc.HoughCircles(gray, found, Imgproc.HOUGH_GRADIENT, 1.2,
                    Math.max(1, cvRound(pitch * .64)), 110,
                    Math.max(25, cvRound(pitch * .39)),
                    cvRound(pitch * .30), cvRound(pitch * .49));
            TreeMap<Integer, XiangqiCircle> assigned = new TreeMap<>();
            int circleCount = Math.max(found.rows(), found.cols());
            for (int i = 0; i < circleCount; i++) {
                double[] local = found.rows() == 1 ? found.get(0, i) : found.get(i, 0);
                if (local == null || local.length < 3) {
                    continue;
                }
                float centerX = (float) (local[0] + left);
                float centerY = (float) (local[1] + top);
                int col = cvRound((centerX - originX) / stepX);
                int row = cvRound((centerY - originY) / stepY);
                if (col < 0 || col > 8 || row < 0 || row > 9) {
                    continue;
                }
                double errorX = Math.abs(centerX - (originX + col * stepX)) / stepX;
                double errorY = Math.abs(centerY - (originY + row * stepY)) / stepY;
                if (errorX > .27 || errorY > .27) {
                    continue;
                }
                XiangqiCircle circle = new XiangqiCircle();
                circle.x = centerX;
                circle.y = centerY;
                circle.radius = (float) local[2];
                circle.col = col;
                circle.row = row;
                circle.error = (float) (errorX + errorY);
                circle.redFraction = (float) redInkFraction(rgba, centerX, centerY, local[2]);
                circle.color = circle.redFraction > .5 ? "red" : "black";
                int key = row * 9 + col;
                XiangqiCircle previous = assigned.get(key);
                if (previous == null || circle.error < previous.error) {
                    assigned.put(key, circle);
                }
            }
            output.addAll(assigned.values());
            long now = System.nanoTime();
            Log.i(TAG, "xiangqi-time[" + timingTag + "]: circleGray=" + ms(tGrayStart, tHoughStart)
                    + "ms circleHough=" + ms(tHoughStart, now) + "ms circles=" + output.size()
                    + " total=" + ms(requestStartedNanos, now) + "ms");
            return output;
        } finally {
            OpenCVHelper.release(found);
            OpenCVHelper.release(gray);
            OpenCVHelper.release(roi);
        }
    }

    private static String ms(long fromNanos, long toNanos) {
        return String.format(Locale.US, "%.1f", (toNanos - fromNanos) / 1_000_000.0);
    }

    /** 整块读 Bitmap 像素（ARGB int[]）；缓冲按实例复用。只用于木色行列扫描。 */
    private int[] readPixels(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int count = width * height;
        if (mBoardPixels == null || mBoardPixels.length < count) {
            mBoardPixels = new int[count];
        }
        bitmap.getPixels(mBoardPixels, 0, width, 0, 0, width, height);
        return mBoardPixels;
    }

    /**
     * 完整棋盘分析（C++ analyzeXiangqiBoard）。
     *
     * <p>task-31 性能边界（以「输出与优化前逐字段一致」为硬约束）：
     *  · 木色行/列扫描走整块 Bitmap.getPixels 的 int[]（省掉逐行 Mat.get；这一阶段只决定 outer，
     *    与 bitmapToMat 路径的 RGB 取值一致，已验证 outer 不变）；
     *  · 格线/圆检测保持原路径：Utils.bitmapToMat(整帧) → cvtColor(整帧→GRAY) → 裁 outer →
     *    GaussianBlur/Canny/HoughLinesP；圆检测用同一 rgba 的 ROI 子矩阵。整帧转换 ~10-15ms，
     *    必须保留「先整帧转换再裁剪」的顺序（ROI 子矩阵的滤波边界语义与连续 ROI 不同）。
     */
    BoardAnalysis analyzeBoard(Bitmap bitmap) {
        BoardAnalysis result = new BoardAnalysis();
        long requestStarted = System.nanoTime();
        if (bitmap == null || bitmap.isRecycled()
                || bitmap.getWidth() < 400 || bitmap.getHeight() < 500) {
            result.reason = "需要至少 400x500 的 RGBA 帧";
            return result;
        }
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        long tPixelsStart = System.nanoTime();
        int[] pixels = readPixels(bitmap);
        long tPixels = System.nanoTime();

        // 木色行支持度 → 棋盘纵向连续带
        double[] rowSupport = new double[height];
        for (int y = 0; y < height; y++) {
            int base = y * width;
            int wood = 0;
            for (int x = 0; x < width; x++) {
                int pixel = pixels[base + x];
                if (isWoodPixel((pixel >> 16) & 0xff, (pixel >> 8) & 0xff, pixel & 0xff)) {
                    wood++;
                }
            }
            rowSupport[y] = (double) wood / width;
        }
        Rect rowBand = longestBand(rowSupport, 31, .5);
        result.rowBandX = rowBand.x;
        result.rowBandWidth = rowBand.width;
        if (rowBand.width < height * .25) {
            result.reason = "木色棋盘的连续横带不足";
            logBoardTiming(result, "analyze", tPixelsStart, tPixels, System.nanoTime(), 0, 0, 0, 0,
                    result.reason, requestStarted);
            return result;
        }
        int by = rowBand.x;
        int bh = rowBand.width;
        // 带内木色列支持度 → 棋盘横向连续带
        double[] columnSupport = new double[width];
        for (int y = by; y < by + bh; y++) {
            int base = y * width;
            for (int x = 0; x < width; x++) {
                int pixel = pixels[base + x];
                if (isWoodPixel((pixel >> 16) & 0xff, (pixel >> 8) & 0xff, pixel & 0xff)) {
                    columnSupport[x] += 1;
                }
            }
        }
        for (int x = 0; x < columnSupport.length; x++) {
            columnSupport[x] /= bh;
        }
        Rect colBand = longestBand(columnSupport, 15, .5);
        result.colBandX = colBand.x;
        result.colBandWidth = colBand.width;
        long tWood = System.nanoTime();
        if (colBand.width < width * .6) {
            result.reason = "木色棋盘的连续宽度不足";
            logBoardTiming(result, "analyze", tPixelsStart, tPixels, tWood, 0, 0, 0, 0,
                    result.reason, requestStarted);
            return result;
        }
        int bx = colBand.x;
        int bw = colBand.width;
        result.outer = new Rect(bx, by, bw, bh);

        Mat rgba = new Mat();
        Mat gray = new Mat();
        Mat boardGray = null;
        Mat blurred = new Mat();
        Mat edges = new Mat();
        Mat segments = new Mat();
        Mat xv = new Mat();
        Mat yv = new Mat();
        try {
            Utils.bitmapToMat(bitmap, rgba);
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);
            boardGray = new Mat(gray, result.outer);
            long tConvert = System.nanoTime();
            Imgproc.GaussianBlur(boardGray, blurred, new Size(5, 5), 1);
            Imgproc.Canny(blurred, edges, 50, 120);
            long tCanny = System.nanoTime();
            double scale = Math.min(bw / 1080.0, bh / 1200.0);
            int minLine = Math.max(45, cvRound(90 * scale));
            int maxGap = Math.max(12, cvRound(25 * scale));
            result.minLine = minLine;
            result.maxGap = maxGap;
            Imgproc.HoughLinesP(edges, segments, 1, Math.PI / 180, 40, minLine, maxGap);
            result.houghSegments = Math.max(segments.rows(), segments.cols());
            long tHoughStart = System.nanoTime();
            if (segments.empty()) {
                result.reason = "Canny 后没有可用格线";
                logBoardTiming(result, "analyze", tPixelsStart, tPixels, tWood, tConvert, tCanny,
                        System.nanoTime(), 0, result.reason, requestStarted);
                return result;
            }
            float[] xVotes = new float[width];
            float[] yVotes = new float[height];
            int segmentCount = result.houghSegments;
            for (int i = 0; i < segmentCount; i++) {
                double[] line = segments.rows() == 1 ? segments.get(0, i) : segments.get(i, 0);
                if (line == null || line.length < 4) {
                    continue;
                }
                int x1 = (int) line[0];
                int y1 = (int) line[1];
                int x2 = (int) line[2];
                int y2 = (int) line[3];
                int dx = Math.abs(x2 - x1);
                int dy = Math.abs(y2 - y1);
                if (dx <= 2 && dy >= minLine) {
                    int x = cvRound((x1 + x2) / 2.0) + bx;
                    if (x >= 0 && x < width) {
                        xVotes[x] = (float) (xVotes[x] + dy);
                    }
                    result.verticalSegments++;
                }
                if (dy <= 2 && dx >= minLine) {
                    int y = cvRound((y1 + y2) / 2.0) + by;
                    if (y >= 0 && y < height) {
                        yVotes[y] = (float) (yVotes[y] + dx);
                    }
                    result.horizontalSegments++;
                }
            }
            long tHough = System.nanoTime();
            xv = ensureMat(xv, 1, width, CvType.CV_32F);
            yv = ensureMat(yv, height, 1, CvType.CV_32F);
            xv.put(0, 0, xVotes);
            yv.put(0, 0, yVotes);
            Imgproc.GaussianBlur(xv, xv, new Size(0, 0), 3);
            Imgproc.GaussianBlur(yv, yv, new Size(0, 0), 3);
            float[] xFitVotes = new float[width];
            float[] yFitVotes = new float[height];
            xv.get(0, 0, xFitVotes);
            yv.get(0, 0, yFitVotes);
            AxisFit x = fitAxis(xFitVotes, xFitVotes.length, bx, bw, 9, .045, .105);
            AxisFit y = fitAxis(yFitVotes, yFitVotes.length, by, bh, 10, .045, .10);
            long tFit = System.nanoTime();
            result.fitX = x;
            result.fitY = y;
            result.lineScoreX = x.score;
            result.lineScoreY = y.score;
            Log.i(TAG, "xiangqi: verticalSegments=" + result.verticalSegments
                    + " horizontalSegments=" + result.horizontalSegments
                    + " xfit=(origin=" + x.origin + ",step=" + x.step
                    + ",supported=" + x.supported + ",score=" + x.score + ")"
                    + " yfit=(origin=" + y.origin + ",step=" + y.step
                    + ",supported=" + y.supported + ",score=" + y.score + ")");
            if (x.step <= 0 || y.step <= 0 || x.supported < 5 || y.supported < 5
                    || Math.abs(x.step - y.step) / Math.max(x.step, y.step) > .08) {
                result.reason = "格线证据不足或横竖格距不一致";
                logBoardTiming(result, "analyze", tPixelsStart, tPixels, tWood, tConvert, tCanny,
                        tHough, tFit, result.reason, requestStarted);
                return result;
            }
            result.originX = x.origin;
            result.originY = y.origin;
            result.stepX = x.step;
            result.stepY = y.step;
            long tCirclesStart = System.nanoTime();
            result.circles = detectBoardCircles(rgba, x.origin, y.origin, x.step, y.step,
                    "analyze", requestStarted);
            long tEnd = System.nanoTime();
            result.ok = true;
            logBoardTiming(result, "analyze", tPixelsStart, tPixels, tWood, tConvert, tCanny,
                    tHough, tFit, null, requestStarted);
            Log.i(TAG, "xiangqi: ok outer=(" + bx + "," + by + "," + bw + "," + bh
                    + ") grid=(origin=" + x.origin + "," + y.origin
                    + ",step=" + x.step + "," + y.step + ") circles=" + result.circles.size()
                    + " total=" + ms(requestStarted, tEnd) + "ms");
            return result;
        } finally {
            OpenCVHelper.release(yv);
            OpenCVHelper.release(xv);
            OpenCVHelper.release(segments);
            OpenCVHelper.release(edges);
            OpenCVHelper.release(blurred);
            if (boardGray != null) {
                OpenCVHelper.release(boardGray);
            }
            OpenCVHelper.release(gray);
            OpenCVHelper.release(rgba);
        }
    }

    private static Mat ensureMat(Mat mat, int rows, int cols, int type) {
        if (mat == null) {
            return new Mat(rows, cols, type);
        }
        if (mat.rows() != rows || mat.cols() != cols || mat.type() != type) {
            mat.create(rows, cols, type);
        }
        return mat;
    }

    private void logBoardTiming(BoardAnalysis result, String tag, long tPixelsStart, long tPixels,
            long tWood, long tGrayStart, long tCanny, long tHough, long tFit, String reason,
            long requestStarted) {
        long now = System.nanoTime();
        String timing = "pixels=" + ms(tPixelsStart, tPixels)
                + "ms wood=" + ms(tPixels, tWood)
                + "ms gray=" + ms(tWood, tGrayStart)
                + "ms blurCanny=" + ms(tGrayStart, tCanny)
                + "ms hough=" + ms(tCanny, tHough)
                + (tFit > 0 ? "ms fit=" + ms(tHough, tFit) : "")
                + "ms total=" + ms(requestStarted, now) + "ms"
                + (reason == null ? "" : " reason=" + reason);
        if (result != null) {
            result.timing = timing;
        }
        Log.i(TAG, "xiangqi-time[" + tag + "]: " + timing);
    }

    /** 解析有限数字（棋盘 grid 参数校验）。 */
    static double requireFiniteNumber(Object raw, String name) {
        if (!(raw instanceof Number)) {
            throw new IllegalArgumentException(name + " 必须是数字");
        }
        double value = ((Number) raw).doubleValue();
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException(name + " 必须是有限数字");
        }
        return value;
    }

    /** Mat → 独立 ARGB_8888 Bitmap；转换失败时回收已分配的 Bitmap，避免泄漏登记预算外的内存。 */
    private static Bitmap matToBitmap(Mat mat) {
        Bitmap bitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888);
        try {
            Utils.matToBitmap(mat, bitmap);
            return bitmap;
        } catch (RuntimeException error) {
            recycleQuietly(bitmap);
            throw error;
        }
    }

    /**
     * 批量像素：一次调用取回整块区域，避免「每像素一次跨进程调用」。
     * format=argb（默认，小端 4 字节/像素）或 gray（1 字节/像素，BT.601）。
     * 单次原始像素上限见 {@link #MAX_PIXEL_FETCH_BYTES}（256KB）；更大的区域请分块读取，
     * 或改用文件通道（node-smem 负责）。
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
                    + "（" + bytes + " 字节，单次上限 " + (MAX_PIXEL_FETCH_BYTES / 1024) + "KB 原始像素；"
                    + "请先 resize/clip 分块读取，或改用文件通道（node-smem 负责））");
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
                raw[i] = (byte) openCvGray(r, g, b);
            }
        }
        return new JSONObject()
                .put("width", region.width)
                .put("height", region.height)
                .put("format", format)
                .put("data", Base64.encodeToString(raw, Base64.NO_WRAP));
    }

    /**
     * 一次取回大量散点颜色（逐点取色是象棋识别的主要桥开销）。
     * points=[[x,y],...]；返回 Int32Array 的 base64 小端编码。
     */
    private JSONObject samplePixels(JSONObject request) throws Exception {
        ensureOpen();
        Bitmap bitmap = requireBitmap(requireId(request));
        JSONArray points = request.optJSONArray("points");
        if (points == null) {
            throw new IllegalArgumentException("samplePixels 缺少 points");
        }
        int count = points.length();
        if (count <= 0 || count > 65536) {
            throw new IllegalArgumentException("samplePixels 点数非法：" + count + "（1..65536）");
        }
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        byte[] raw = new byte[count * 4];
        ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) {
            JSONArray point = points.optJSONArray(i);
            int x = point.getInt(0);
            int y = point.getInt(1);
            buffer.putInt(x < 0 || y < 0 || x >= width || y >= height ? 0 : bitmap.getPixel(x, y));
        }
        return new JSONObject()
                .put("count", count)
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

    /**
     * OpenCV {@code COLOR_RGBA2GRAY} 的定点公式（yuv_shift=14）逐位复刻：
     * {@code Y = (R*4899 + G*9617 + B*1868 + 8192) >> 14}。
     *
     * <p>task-21（frame-bench R4 真实帧）：pixelsToFile/getPixels 的 gray 分支原来用
     * {@code Math.round(0.299R+0.587G+0.114B)} 浮点四舍五入，在真实帧颜色上与 C2 的 V8 定点式
     * 不一致（thresholdHits 差 0.093%）；grayscale action 走 Imgproc.cvtColor，本身就是这个定点公式，
     * 对齐后 pixelsToFile(gray) 与 grayscale(action) 逐位一致。
     */
    private static int openCvGray(int r, int g, int b) {
        return (r * 4899 + g * 9617 + b * 1868 + 8192) >> 14;
    }

    // ------------------------------------------------------------------
    // 文件式像素交换（pixelsToFile）
    // ------------------------------------------------------------------

    /**
     * 把「区域原始像素」或「散点 ARGB」写入应用私有目录文件，返回描述；Node 侧同进程
     * {@code fs.readFileSync} 读取，脚本自己构造 TypedArray（绕开 base64/JSON/JNI 大字符串）。
     *
     * <p>入参二选一：{@code region}（argb/gray 原始块）或 {@code points:[[x,y],...]}（int32 ARGB）。
     * {@code path} 省略时写宿主 scratch：{@code getCacheDir()/node24-pixels/export-<pid>.bin}
     * （每次调用覆盖；按 pid 区分，避免 :node24_1..3 池进程互相覆盖）。
     */
    private JSONObject pixelsToFile(JSONObject request) throws Exception {
        ensureOpen();
        int imageId = requireId(request);
        Bitmap bitmap = requireBitmap(imageId);
        String format = request.optString("format", "argb");
        if (!"argb".equals(format) && !"gray".equals(format)) {
            throw new IllegalArgumentException("pixelsToFile 的 format 仅支持 argb/gray：" + format);
        }
        boolean argb = "argb".equals(format);
        JSONArray points = request.optJSONArray("points");
        boolean hasPoints = request.has("points");
        Object rawRegion = request.opt("region");
        boolean hasRegion = rawRegion != null && rawRegion != JSONObject.NULL;
        if (hasPoints && points == null) {
            throw new IllegalArgumentException("pixelsToFile 的 points 必须是 [[x,y],...] 数组");
        }
        if (hasPoints && points.length() == 0) {
            throw new IllegalArgumentException("pixelsToFile 的 points 不能为空");
        }
        if (hasPoints && hasRegion) {
            throw new IllegalArgumentException("pixelsToFile 的 region 与 points 只能二选一");
        }
        if (hasPoints) {
            if (!argb) {
                throw new IllegalArgumentException("pixelsToFile 的 points 只支持 argb（Int32Array）");
            }
            return pixelsPointsToFile(request, bitmap, points);
        }
        return pixelsRegionToFile(request, bitmap, format, argb);
    }

    /** region 形态：argb=4B/像素（Uint32Array 元素 == Bitmap.getPixel）或 gray=1B/像素（BT.601）。 */
    private JSONObject pixelsRegionToFile(JSONObject request, Bitmap bitmap, String format, boolean argb)
            throws Exception {
        Region region = parseRegion(request.opt("region"), bitmap.getWidth(), bitmap.getHeight());
        int bytesPerPixel = argb ? 4 : 1;
        long bytes = (long) region.width * (long) region.height * bytesPerPixel;
        if (bytes > MAX_PIXEL_FILE_BYTES) {
            throw new IllegalArgumentException("pixelsToFile 区域过大：" + region.width + "x" + region.height
                    + "（" + bytes + " 字节，单次上限 " + (MAX_PIXEL_FILE_BYTES / 1024 / 1024) + "MB）");
        }
        File target = pixelTargetFile(request.optString("path", ""));
        int rowBytes = region.width * bytesPerPixel;
        int rowsPerBlock = Math.max(1, Math.min(region.height,
                PIXEL_FILE_BLOCK_BYTES / Math.max(1, rowBytes)));
        byte[] block = new byte[rowsPerBlock * rowBytes];
        int[] pixels = new int[rowsPerBlock * region.width];
        FileOutputStream output = new FileOutputStream(target); // 覆盖写；同进程读方在返回后同步读
        try {
            int row = 0;
            while (row < region.height) {
                int rows = Math.min(rowsPerBlock, region.height - row);
                bitmap.getPixels(pixels, 0, region.width, region.x, region.y + row, region.width, rows);
                int offset = 0;
                int count = rows * region.width;
                if (argb) {
                    for (int i = 0; i < count; i++) {
                        int pixel = pixels[i];
                        // Bitmap.getPixel 的 int 是 ARGB；小端写出后 Uint32Array 元素值与它一致。
                        block[offset++] = (byte) (pixel & 0xFF);
                        block[offset++] = (byte) ((pixel >> 8) & 0xFF);
                        block[offset++] = (byte) ((pixel >> 16) & 0xFF);
                        block[offset++] = (byte) ((pixel >> 24) & 0xFF);
                    }
                } else {
                    for (int i = 0; i < count; i++) {
                        int pixel = pixels[i];
                        int r = (pixel >> 16) & 0xFF;
                        int g = (pixel >> 8) & 0xFF;
                        int b = pixel & 0xFF;
                        block[offset++] = (byte) openCvGray(r, g, b);
                    }
                }
                output.write(block, 0, rows * rowBytes);
                row += rows;
            }
            output.flush();
        } finally {
            output.close();
        }
        long written = target.length();
        if (written != bytes) {
            throw new IOException("像素文件大小异常：期望 " + bytes + " 实际 " + written + "（" + target + "）");
        }
        JSONObject detail = new JSONObject()
                .put("kind", "region")
                .put("pixelStride", bytesPerPixel)
                .put("rowStride", rowBytes)
                .put("endian", "little")
                .put("rowMajor", true);
        if (argb) {
            detail.put("channelOrder", "bgra")
                    .put("alpha", true)
                    .put("value", "Uint32Array 元素 == Bitmap.getPixel()（ARGB int32）");
        } else {
            detail.put("channelOrder", "y")
                    .put("alpha", false)
                    .put("value", "Uint8Array 灰度（BT.601: 0.299R+0.587G+0.114B）");
        }
        return new JSONObject()
                .put("path", target.getCanonicalPath())
                .put("width", region.width)
                .put("height", region.height)
                .put("count", (long) region.width * (long) region.height)
                .put("bytes", bytes)
                .put("format", format)
                .put("formatDetail", detail);
    }

    /**
     * points 形态：按输入顺序把每个点写成 int32 ARGB（元素 == Bitmap.getPixel；越界点写 0）。
     * 大点数时先用一次 getPixels 取「所有合法点的包围盒」再索引，避免 1.38 万次逐点 JNI getPixel；
     * 稀疏点仍走逐点 getPixel（各自更便宜）。
     */
    private JSONObject pixelsPointsToFile(JSONObject request, Bitmap bitmap, JSONArray points) throws Exception {
        int count = points.length();
        if (count <= 0 || count > MAX_PIXEL_FILE_POINTS) {
            throw new IllegalArgumentException("pixelsToFile points 数量非法：" + count
                    + "（1.." + MAX_PIXEL_FILE_POINTS + "）");
        }
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = -1;
        int maxY = -1;
        int inBounds = 0;
        for (int i = 0; i < count; i++) {
            JSONArray point = points.optJSONArray(i);
            if (point == null || point.length() != 2) {
                throw new IllegalArgumentException("pixelsToFile points[" + i + "] 必须是 [x,y]");
            }
            int x = point.optInt(0, 0);
            int y = point.optInt(1, 0);
            if (x < 0 || y < 0 || x >= width || y >= height) {
                continue;
            }
            inBounds++;
            if (x < minX) {
                minX = x;
            }
            if (y < minY) {
                minY = y;
            }
            if (x > maxX) {
                maxX = x;
            }
            if (y > maxY) {
                maxY = y;
            }
        }
        int bboxWidth = maxX < 0 ? 0 : maxX - minX + 1;
        int bboxHeight = maxY < 0 ? 0 : maxY - minY + 1;
        long bboxPixels = (long) bboxWidth * (long) bboxHeight;
        // 逐点 getPixel 约 3.3µs/点；包围盒 getPixels 约 2~3ns/像素 → 点数多且包围盒不稀疏时用包围盒。
        boolean useBBox = inBounds >= 1024
                && bboxPixels > 0L
                && bboxPixels * 4L <= MAX_POINTS_BBOX_BYTES
                && bboxPixels <= (long) count * 1500L;
        int[] bbox = null;
        if (useBBox) {
            bbox = new int[(int) bboxPixels];
            bitmap.getPixels(bbox, 0, bboxWidth, minX, minY, bboxWidth, bboxHeight);
        }
        byte[] raw = new byte[count * 4];
        ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) {
            JSONArray point = points.optJSONArray(i);
            int x = point.optInt(0, 0);
            int y = point.optInt(1, 0);
            int pixel;
            if (x < 0 || y < 0 || x >= width || y >= height) {
                pixel = 0; // 与 samplePixels 的越界语义一致
            } else if (bbox != null) {
                pixel = bbox[(y - minY) * bboxWidth + (x - minX)];
            } else {
                pixel = bitmap.getPixel(x, y);
            }
            buffer.putInt(pixel);
        }
        File target = pixelTargetFile(request.optString("path", ""));
        FileOutputStream output = new FileOutputStream(target);
        try {
            output.write(raw);
            output.flush();
        } finally {
            output.close();
        }
        long bytes = (long) count * 4L;
        long written = target.length();
        if (written != bytes) {
            throw new IOException("像素文件大小异常：期望 " + bytes + " 实际 " + written + "（" + target + "）");
        }
        JSONObject detail = new JSONObject()
                .put("kind", "points")
                .put("count", count)
                .put("pixelStride", 4)
                .put("endian", "little")
                .put("rowMajor", false)
                .put("channelOrder", "bgra")
                .put("alpha", true)
                .put("value", "Int32Array 元素 == Bitmap.getPixel(points[i][0], points[i][1])；越界点 0");
        return new JSONObject()
                .put("path", target.getCanonicalPath())
                .put("count", count)
                .put("bytes", bytes)
                .put("format", "argb")
                .put("formatDetail", detail);
    }

    /** path 省略 → 进程级 scratch 文件；显式 path → canonical 校验必须落在应用私有目录内。 */
    private File pixelTargetFile(String requestedPath) throws IOException {
        String path = requestedPath == null ? "" : requestedPath.trim();
        File target;
        if (path.isEmpty()) {
            File dir = pixelScratchDir();
            target = new File(dir, "export-" + android.os.Process.myPid() + ".bin");
            File canonical = target.getCanonicalFile();
            checkPixelPathAllowed(canonical);
            mAutoPixelFile = canonical;
            return canonical;
        }
        if (path.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("pixelsToFile 路径包含 NUL 字符");
        }
        if (path.length() > MAX_PATH_CHARS) {
            throw new IllegalArgumentException("pixelsToFile 路径过长（上限 " + MAX_PATH_CHARS + " 字符）");
        }
        File candidate = new File(path);
        if (!candidate.isAbsolute()) {
            throw new IllegalArgumentException("pixelsToFile 路径必须是绝对路径：" + path);
        }
        target = candidate.getCanonicalFile();
        checkPixelPathAllowed(target);
        File parent = target.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory())) {
            throw new IOException("无法创建像素文件目录：" + parent);
        }
        // mkdirs 之后再 canonical 一次，确保新创建的目录没有把路径带出私有目录。
        target = candidate.getCanonicalFile();
        checkPixelPathAllowed(target);
        if (target.isDirectory()) {
            throw new IllegalArgumentException("pixelsToFile 路径是目录：" + target);
        }
        return target;
    }

    /** 应用 cacheDir 下的 scratch 目录（按 pid 区分文件在 pixelTargetFile 里做）。 */
    private File pixelScratchDir() throws IOException {
        File cache = requireAppContext().getCacheDir();
        if (cache == null) {
            throw new IOException("无法解析应用 cacheDir");
        }
        File dir = new File(cache, PIXEL_SCRATCH_DIR);
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("无法创建像素 scratch 目录：" + dir);
        }
        return dir;
    }

    /** path 安全硬要求：canonical 后必须是 getFilesDir()/getCacheDir() 之下的文件。 */
    private void checkPixelPathAllowed(File canonicalTarget) throws IOException {
        String target = canonicalTarget.getPath();
        for (File root : privateRoots()) {
            String rootPath = root.getCanonicalFile().getPath();
            if (target.equals(rootPath)) {
                throw new IllegalArgumentException("pixelsToFile 路径不能是目录：" + target);
            }
            String prefix = rootPath.endsWith(File.separator) ? rootPath : rootPath + File.separator;
            if (target.startsWith(prefix)) {
                return;
            }
        }
        throw new IllegalArgumentException(
                "pixelsToFile 路径必须位于应用私有目录（getFilesDir()/getCacheDir()）之下：" + target);
    }

    private File[] privateRoots() throws IOException {
        Context context = requireAppContext();
        File files = context.getFilesDir();
        File cache = context.getCacheDir();
        java.util.List<File> roots = new java.util.ArrayList<>(2);
        if (files != null) {
            roots.add(files);
        }
        if (cache != null) {
            roots.add(cache);
        }
        if (roots.isEmpty()) {
            throw new IOException("应用私有目录不可用（getFilesDir/getCacheDir 均为 null）");
        }
        return roots.toArray(new File[0]);
    }

    /**
     * :node24 进程里 App.onCreate 已调用 GlobalAppContext.set()（apps/app/.../App.kt）；
     * 兜底用 ActivityThread.currentApplication()（与仓库 Device.java 同款），不回退到任意路径。
     */
    private Context requireAppContext() throws IOException {
        Context context = appContextOrNull();
        if (context == null) {
            throw new IllegalStateException("无法解析应用私有目录：GlobalAppContext/ActivityThread 均不可用，"
                    + "pixelsToFile 需要 getFilesDir()/getCacheDir()");
        }
        return context;
    }

    private static Context appContextOrNull() {
        try {
            return GlobalAppContext.get();
        } catch (Throwable ignored) {
            // fallthrough
        }
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object application = activityThread.getMethod("currentApplication").invoke(null);
            if (application instanceof Context) {
                return (Context) application;
            }
        } catch (Throwable ignored) {
            // fallthrough
        }
        return null;
    }

    private void deleteAutoPixelFileQuietly() {
        File file = mAutoPixelFile;
        mAutoPixelFile = null;
        if (file == null) {
            return;
        }
        try {
            if (file.isFile() && !file.delete()) {
                Log.w(TAG, "pixelsToFile scratch 删除失败：" + file);
            }
        } catch (Throwable error) {
            Log.w(TAG, "pixelsToFile scratch 删除异常：" + file, error);
        }
    }

    private void recycle(int id) {
        // 先失效 Mat 缓存：Bitmap 一旦回收，缓存的 Mat 快照也没有意义且占内存。
        invalidateCachedMats(id);
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
        ThreadPoolExecutor executor = mMatchExecutor;
        mMatchExecutor = null;
        if (executor != null) {
            executor.shutdownNow();
        }
        releaseCachedMats();
        releaseBoardScratchMats();
        deleteAutoPixelFileQuietly();
    }

    /** task-31：释放棋盘分析复用缓冲（close 时调用；不改变 Bitmap 句柄）。 */
    private void releaseBoardScratchMats() {
        mBoardPixels = null;
    }

    // ------------------------------------------------------------------
    // 校验/解析（纯逻辑，便于 JVM 单测）
    // ------------------------------------------------------------------

    private void ensureOpen() {
        if (mClosed) {
            throw new IllegalStateException("本次 Node 任务的图片资源已释放");
        }
    }

    /**
     * 供同域宿主（yolo/OCR）借用 Bitmap：不转移所有权，调用方只读、不要 recycle。
     * 与 {@link #requireBitmap(int)} 共用句柄校验（不存在/已回收抛中文错误）。
     */
    Bitmap borrowBitmap(int id) {
        synchronized (this) {
            return requireBitmap(id);
        }
    }

    /** 供同域宿主（yolo）确保 OpenCV 已初始化；失败时抛中文异常（与 image 动作一致）。 */
    static void ensureOpenCvReady() {
        ensureOpenCv();
    }

    private Bitmap requireBitmap(int id) {
        Bitmap bitmap = mBitmaps.get(id);
        if (bitmap == null) {
            throw new IllegalArgumentException("图片句柄不存在或已回收：" + id);
        }
        if (bitmap.isRecycled()) {
            mBitmaps.remove(id);
            invalidateCachedMats(id);
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

    /**
     * 宿主 images.threshold 的 thresh/maxVal 是 double（Rhino 原样传给 OpenCV）；
     * 这里与宿主一致只拒绝非有限值，不额外限制 0..255。
     */
    static double parseThresholdValue(JSONObject request, String name, double fallback) {
        if (!request.has(name) || request.isNull(name)) {
            return fallback;
        }
        Object raw = request.opt(name);
        if (!(raw instanceof Number)) {
            throw new IllegalArgumentException(name + " 必须是数字");
        }
        double value = ((Number) raw).doubleValue();
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException(name + " 必须是有限数字：" + value);
        }
        return value;
    }

    /**
     * threshold 的 type 与宿主 images.threshold 一致：低 3 位是基础类型
     * （0=BINARY/1=BINARY_INV/2=TRUNC/3=TOZERO/4=TOZERO_INV），可叠加 OTSU(8)/TRIANGLE(16)；
     * 也接受 "BINARY" / "BINARY_OTSU" / "THRESH_BINARY" 等名称。
     */
    static int parseThresholdType(Object raw) {
        if (raw == null || raw == JSONObject.NULL) {
            return Imgproc.THRESH_BINARY;
        }
        if (raw instanceof String) {
            String name = ((String) raw).trim().toUpperCase(Locale.US).replace(" ", "");
            if (name.startsWith("THRESH_")) {
                name = name.substring("THRESH_".length());
            }
            switch (name) {
                case "BINARY":
                    return Imgproc.THRESH_BINARY;
                case "BINARY_INV":
                    return Imgproc.THRESH_BINARY_INV;
                case "TRUNC":
                    return Imgproc.THRESH_TRUNC;
                case "TOZERO":
                    return Imgproc.THRESH_TOZERO;
                case "TOZERO_INV":
                    return Imgproc.THRESH_TOZERO_INV;
                case "OTSU":
                case "BINARY_OTSU":
                    return Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU;
                case "BINARY_INV_OTSU":
                    return Imgproc.THRESH_BINARY_INV | Imgproc.THRESH_OTSU;
                case "TRIANGLE":
                    return Imgproc.THRESH_BINARY | Imgproc.THRESH_TRIANGLE;
                default:
                    throw new IllegalArgumentException("不支持的 threshold 类型：" + raw
                            + "（可选 BINARY/BINARY_INV/TRUNC/TOZERO/TOZERO_INV/"
                            + "OTSU/BINARY_OTSU/BINARY_INV_OTSU/TRIANGLE）");
            }
        }
        if (!(raw instanceof Number)) {
            throw new IllegalArgumentException("threshold type 必须是整数或类型名：" + raw);
        }
        double number = ((Number) raw).doubleValue();
        if (Double.isNaN(number) || Double.isInfinite(number) || number != Math.rint(number)
                || number < 0d || number > (Imgproc.THRESH_OTSU | Imgproc.THRESH_TRIANGLE | 4)) {
            throw new IllegalArgumentException("threshold type 必须是 0..28 之间的整数，收到：" + number);
        }
        int type = (int) number;
        int flags = type & ~7;
        if ((type & 7) > 4
                || (flags & ~(Imgproc.THRESH_OTSU | Imgproc.THRESH_TRIANGLE)) != 0) {
            throw new IllegalArgumentException("threshold type 非法：" + type
                    + "（低 3 位是 0..4 基础类型，可叠加 OTSU=8/TRIANGLE=16）");
        }
        return type;
    }

    /** resize 插值：0 NEAREST / 1 LINEAR / 2 CUBIC / 3 AREA / 4 LANCZOS4（默认 LINEAR，与宿主一致）。 */
    static int parseInterpolation(JSONObject request) {
        if (!request.has("interpolation") || request.isNull("interpolation")) {
            return Imgproc.INTER_LINEAR;
        }
        Object raw = request.opt("interpolation");
        if (!(raw instanceof Number)) {
            throw new IllegalArgumentException("interpolation 必须是与 OpenCV 一致的 0..4 整数");
        }
        double value = ((Number) raw).doubleValue();
        if (Double.isNaN(value) || Double.isInfinite(value) || value != Math.rint(value)
                || value < 0d || value > 4d) {
            throw new IllegalArgumentException("interpolation 必须是 0..4 之间的整数，收到：" + value);
        }
        return (int) value;
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
