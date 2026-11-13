package com.stardust.autojs.runtime.api;

import android.graphics.Bitmap;
import android.graphics.Rect;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.stardust.autojs.core.image.ImageWrapper;
import com.stardust.autojs.runtime.ScriptRuntime;

import java.util.ArrayList;
import java.util.List;

/**
 * PDF §2.22（第 182~185 页）：{@code $ocr.create([options])} 返回的 OCR 对象。
 *
 * <p>⚠️ 与 Pro 的实现差异（已记入对照文档 §9）：Pro 用的是**插件商店的 PaddleOCR**，
 * 我们换成 **ML Kit 中文文字识别**（模型内置、离线）。因此文档里的
 * {@code models} / {@code labelsFile} / {@code cpuPowerMode} / 线程数等选项
 * 是 PaddleOCR 专用参数：接口上接受（不报错），但不改变识别引擎。
 *
 * <p>识别结果是**行**（ML Kit 的 Text.Line）：文本、可信度、范围与 PaddleOCR 的行结果同粒度。
 */
public class Ocr implements AutoCloseable, OcrSession {

    /** ML Kit 要求输入图至少 32x32。 */
    private static final int MIN_REGION_SIZE = 32;

    /** 当前进程内未释放的 OCR 会话数（探针用；Pro 文档说 OCR 默认会在程序退出时自动释放）。 */
    private static final java.util.concurrent.atomic.AtomicInteger sLiveCount =
            new java.util.concurrent.atomic.AtomicInteger();

    public static int liveCount() {
        return sLiveCount.get();
    }

    private final ScriptRuntime mRuntime;
    private TextRecognizer mRecognizer;
    private boolean mReleased;

    public Ocr(ScriptRuntime runtime) {
        mRuntime = runtime;
        // ML Kit 的 InitProvider 只在主进程跑；:script 进程必须自己初始化组件注册表，
        // 否则 create() 会「成功」，直到第一次 detect() 才抛
        // `MlKitContext has not been initialized` —— 内置谷歌 OCR 示例就是这么挂的。
        // Node 侧（Node24HostBridge）一直有这段初始化，Rhino 侧此前漏了。
        ensureMlKitInitialized();
        sLiveCount.incrementAndGet();
        // 脚本忘了 release() 时由引擎在退出时兜底（与 Pro 文档一致）
        if (runtime != null) {
            runtime.addCleanable(this);
        }
    }

    /**
     * 幂等地初始化当前进程的 ML Kit 组件注册表。
     *
     * @return true 表示初始化后可用；false 表示当前进程拿不到 Application Context，
     *         调用方应退回 ppocr（{@code __ocr__.js} 里有这条兜底）。
     */
    public static synchronized boolean ensureMlKitInitialized() {
        try {
            com.google.mlkit.common.sdkinternal.MlKitContext.getInstance();
            return true;
        } catch (IllegalStateException notInitialized) {
            android.content.Context context = ScriptRuntime.getApplicationContext();
            if (context == null) {
                context = com.stardust.app.GlobalAppContext.get();
            }
            if (context == null) {
                return false;
            }
            try {
                com.google.mlkit.common.MlKit.initialize(context.getApplicationContext());
                return true;
            } catch (Throwable failure) {
                return false;
            }
        }
    }

    /**
     * PDF：{@code OCR.detect(image[, options])}。
     *
     * @param max          识别文本数量上限（文档默认 1000）
     * @param detectRotation 是否检测文字旋转（文档默认 false）
     * @param region       识别区域，0/2/4 个元素（[left, top, width, height] 或 [left, top]）
     */
    /**
     * 兼容「插件式」调用：AI.js Pro 的第三方/官方 OCR 插件文档里传的是 android.graphics.Bitmap，
     * 而本类内部要 ImageWrapper。真实脚本（华仔部落_佣兵战纪）就是 ocr.detect(image.getBitmap(), 1)，
     * 旧签名会让 Rhino 找不到方法直接失败，所以这里收 Object 再做一次显式转换，并给出可读错误。
     */
    public OCRResult[] detect(Object image, int max, boolean detectRotation, int[] region) {
        return detect(coerceToImageWrapper(image), max, detectRotation, region);
    }

    /** 把脚本给的对象转成 ImageWrapper：图片对象原样用，Bitmap 直接包，其它给明确错误。 */
    public static ImageWrapper coerceToImageWrapper(Object image) {
        if (image instanceof ImageWrapper) {
            return (ImageWrapper) image;
        }
        if (image instanceof Bitmap) {
            return ImageWrapper.ofBitmap((Bitmap) image);
        }
        throw new IllegalArgumentException("OCR.detect 需要 images.read(...) 的图片或 android.graphics.Bitmap，收到："
                + (image == null ? "null" : image.getClass().getName()));
    }

    public OCRResult[] detect(ImageWrapper image, int max, boolean detectRotation, int[] region) {
        if (mReleased) {
            throw new IllegalStateException("OCR 已经 release()，需要重新 $ocr.create()");
        }
        if (image == null) {
            throw new IllegalArgumentException("image 不能为空");
        }
        Bitmap source = image.getBitmap();
        int offsetX = 0;
        int offsetY = 0;
        Bitmap bitmap = source;
        if (region != null && region.length >= 2) {
            int left = region[0];
            int top = region[1];
            int width = region.length >= 4 ? region[2] : source.getWidth() - left;
            int height = region.length >= 4 ? region[3] : source.getHeight() - top;
            left = Math.max(0, Math.min(left, source.getWidth() - 1));
            top = Math.max(0, Math.min(top, source.getHeight() - 1));
            // ML Kit 要求输入图至少 32x32，太小就钳到 32（不报错，避免脚本因传个
            // 边角小区域就整体失败）
            width = Math.max(MIN_REGION_SIZE, Math.min(width, source.getWidth() - left));
            height = Math.max(MIN_REGION_SIZE, Math.min(height, source.getHeight() - top));
            width = Math.min(width, source.getWidth() - left);
            height = Math.min(height, source.getHeight() - top);
            bitmap = Bitmap.createBitmap(source, left, top, width, height);
            offsetX = left;
            offsetY = top;
        }
        Text text;
        try {
            TextRecognizer recognizer = recognizer();
            text = com.google.android.gms.tasks.Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)));
        } catch (Exception e) {
            throw new IllegalStateException("文字识别失败：" + e.getMessage(), e);
        } finally {
            if (bitmap != source) {
                bitmap.recycle();
            }
        }
        List<OCRResult> results = new ArrayList<>();
        int limit = max <= 0 ? Integer.MAX_VALUE : max;
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                if (results.size() >= limit) {
                    return results.toArray(new OCRResult[0]);
                }
                Rect bounds = line.getBoundingBox();
                if (bounds == null) {
                    bounds = new Rect();
                }
                Rect shifted = new Rect(bounds);
                shifted.offset(offsetX, offsetY);
                results.add(new OCRResult(mRuntime, line.getText(), line.getConfidence(), shifted,
                        detectRotation, line));
            }
        }
        return results.toArray(new OCRResult[0]);
    }

    /** PDF：{@code OCR.release()} —— 释放资源（程序退出时会自动释放）。 */
    public void release() {
        if (mReleased) {
            return;
        }
        mReleased = true;
        sLiveCount.decrementAndGet();
        if (mRuntime != null) {
            mRuntime.removeCleanable(this);
        }
        if (mRecognizer != null) {
            mRecognizer.close();
            mRecognizer = null;
        }
    }

    /** 脚本退出时的兜底释放（等同 {@link #release()}）。 */
    @Override
    public void close() {
        release();
    }

    public boolean isReleased() {
        return mReleased;
    }

    private TextRecognizer recognizer() {
        if (mRecognizer == null) {
            mRecognizer = TextRecognition.getClient(
                    new ChineseTextRecognizerOptions.Builder().build());
        }
        return mRecognizer;
    }
}
