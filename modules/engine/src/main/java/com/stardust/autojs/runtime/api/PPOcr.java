package com.stardust.autojs.runtime.api;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Rect;

import com.stardust.autojs.core.image.ImageWrapper;
import com.stardust.autojs.runtime.ScriptRuntime;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PDF §2.22（第 182~185 页）：{@code $ocr.create({backend:'ppocr'})} 返回的 OCR 对象。
 *
 * <p>和 {@link Ocr}（ML Kit 后端）**接口完全同形**：{@code detect(image, max, detectRotation, region)}
 * 返回 {@link OCRResult}[]，因此脚本层可以按 {@code options.backend} 无缝切换。
 *
 * <p>引擎是 ncnn 版 **PP-OCRv6 tiny**（det + rec），模型随包在 {@code assets/models/ppocrv6/}，
 * 首次使用时复制到应用私有目录再交给原生侧。识别产物与 PaddleOCR 同为「行」粒度。
 *
 * <p>⚠️ 与 ML Kit 的差异：
 * <ul>
 *   <li>只随包 **arm64-v8a**（{@link PPOcrBridge#isAvailable()} 为 false 时 {@link #detect} 会明确报错）。</li>
 *   <li>原生侧引擎是**进程内单例**：多个会话共用一份模型，{@code release()} 在最后一个会话释放时才真正卸载。</li>
 *   <li>识别框数量上限会被钳到 {@link #MAX_ITEMS}（PP-OCR 的识别是按框串行/并行的，框太多会明显变慢）。</li>
 * </ul>
 */
public class PPOcr implements AutoCloseable, OcrSession {

    /** 随包模型所在 assets 子目录。 */
    private static final String ASSET_DIR = "models/ppocrv6";
    /** 必须成套存在的模型文件（字典文件名是原生侧写死的，不能改）。 */
    private static final String[] MODEL_FILES = {
            "PP_OCRv6_tiny_det.ncnn.param",
            "PP_OCRv6_tiny_det.ncnn.bin",
            "PP_OCRv6_tiny_rec.ncnn.param",
            "PP_OCRv6_tiny_rec.ncnn.bin",
            "ppocr_keys_v6_tiny.txt",
    };
    private static final String DET_PARAM = "PP_OCRv6_tiny_det.ncnn.param";
    private static final String DET_MODEL = "PP_OCRv6_tiny_det.ncnn.bin";
    private static final String REC_PARAM = "PP_OCRv6_tiny_rec.ncnn.param";
    private static final String REC_MODEL = "PP_OCRv6_tiny_rec.ncnn.bin";

    /** 一次识别最多认多少个框（超出直接丢弃，避免脚本传 max=1000 时卡住）。 */
    private static final int MAX_ITEMS = 120;
    /**
     * 默认检测长边。K40（1080×2400）实测 512 是甜点：比 768 快 1.5–1.7×，而且准确率**更高**
     * （0.978 vs 0.944）—— 480 相当，640 居中，960 明显更慢更差。脚本可用 targetSize 覆盖。
     */
    private static final int DEFAULT_TARGET_SIZE = 512;
    /** 默认识别线程数（实测 4 路最优：长文本图 928→432ms）。 */
    private static final int DEFAULT_THREADS = 4;
    /** 默认最低置信度（原生侧默认 0.2）。 */
    private static final float DEFAULT_MIN_SCORE = 0.2f;
    /** 默认是否开 fp16（保守 false；实测开着反而 2.2–2.4× 慢，保持关闭）。 */
    private static final boolean DEFAULT_USE_FP16 = false;
    /**
     * 默认 rec 裁剪宽度上限。实测 cap 1024 → 640：长文本屏 329 → 270 ms（-18%），
     * 平均置信度只从 0.953 掉到 0.950；cap 320 就开始掉精度了（0.922）。
     */
    private static final int DEFAULT_REC_MAX_WIDTH = 640;

    private static final AtomicInteger sLiveCount = new AtomicInteger();
    /** 原生引擎是单例，用它串行化所有 native 调用。 */
    private static final Object sNativeLock = new Object();
    /** 仍在用原生引擎的会话数；归零才真正 release。 */
    private static int sNativeUsers = 0;
    /** 当前原生引擎已加载的配置（变了就重新 init）。 */
    private static String sLoadedConfig = null;

    public static int liveCount() {
        return sLiveCount.get();
    }

    private final ScriptRuntime mRuntime;
    private final String mModelsDir;
    private final int mCpuPowerMode;
    private final int mThreads;
    private final float mMinScore;
    private final int mTargetSize;
    /** ncnn fp16 存储/打包（需设备支持 ARMv8.2 fp16 才有收益）。 */
    private final boolean mUseFp16;
    /** rec 裁剪宽度上限（0 = 内置默认 1024）。 */
    private final int mRecMaxWidth;
    private boolean mReleased;

    public PPOcr(ScriptRuntime runtime) {
        this(runtime, null, 0, DEFAULT_THREADS, DEFAULT_MIN_SCORE, DEFAULT_TARGET_SIZE, DEFAULT_USE_FP16, 0);
    }

    /**
     * @param modelsDir   模型目录（null = 用随包 assets 复制出来的那份；传目录时需自带那 5 个文件）
     * @param cpuPowerMode 0=不绑核（推荐） 1=绑小核 2=绑大核簇
     * @param useFp16     ncnn fp16（默认 false，脚本可开）
     * @param recMaxWidth rec 裁剪宽度上限（0=默认 1024）
     */
    public PPOcr(ScriptRuntime runtime, String modelsDir, int cpuPowerMode, int threads,
                 float minScore, int targetSize, boolean useFp16, int recMaxWidth) {
        mRuntime = runtime;
        mModelsDir = modelsDir;
        mCpuPowerMode = Math.max(0, Math.min(2, cpuPowerMode));
        mThreads = threads <= 0 ? DEFAULT_THREADS : Math.max(1, Math.min(8, threads));
        mMinScore = minScore <= 0 ? DEFAULT_MIN_SCORE : Math.min(1f, minScore);
        mTargetSize = targetSize <= 0 ? DEFAULT_TARGET_SIZE : Math.max(320, Math.min(1280, targetSize));
        mUseFp16 = useFp16;
        mRecMaxWidth = recMaxWidth <= 0 ? DEFAULT_REC_MAX_WIDTH
                : Math.max(64, Math.min(2048, recMaxWidth));
        sLiveCount.incrementAndGet();
        synchronized (sNativeLock) {
            // 原生引擎是单例：这里只登记引用，真正的模型加载在第一次 detect() 时按配置惰性完成。
            sNativeUsers++;
        }
        if (runtime != null) {
            runtime.addCleanable(this);
        }
    }

    /** 当前 ABI 是否支持 PP-OCR。 */
    public static boolean isAvailable() {
        return PPOcrBridge.isAvailable();
    }

    /**
     * PDF：{@code OCR.detect(image[, options])}（与 ML Kit 后端同形）。
     *
     * @param max          识别文本数量上限（会被钳到 {@link #MAX_ITEMS}）
     * @param detectRotation 忽略：PP-OCR 这里不做旋转检测，rotation 恒为 0
     * @param region       识别区域 [left, top, width, height] 或 [left, top]（null=整图）
     */
    @Override
    public OCRResult[] detect(ImageWrapper image, int max, boolean detectRotation, int[] region) {
        if (mReleased) {
            throw new IllegalStateException("OCR 已经 release()，需要重新 $ocr.create()");
        }
        if (image == null) {
            throw new IllegalArgumentException("image 不能为空");
        }
        if (!PPOcrBridge.isAvailable()) {
            throw new IllegalStateException("PP-OCR 在当前 ABI 不可用（只随包 arm64-v8a）；" +
                    "请去掉 backend:'ppocr' 用内置的 ML Kit 后端");
        }
        Bitmap bitmap = image.getBitmap();
        if (bitmap == null) {
            throw new IllegalArgumentException("image 不能为空");
        }
        int left = 0;
        int top = 0;
        int right = 0;
        int bottom = 0;
        if (region != null && region.length >= 2) {
            left = Math.max(0, Math.min(region[0], bitmap.getWidth() - 1));
            top = Math.max(0, Math.min(region[1], bitmap.getHeight() - 1));
            int width = region.length >= 4 ? region[2] : bitmap.getWidth() - left;
            int height = region.length >= 4 ? region[3] : bitmap.getHeight() - top;
            width = Math.max(1, Math.min(width, bitmap.getWidth() - left));
            height = Math.max(1, Math.min(height, bitmap.getHeight() - top));
            right = left + width;
            bottom = top + height;
        }
        int maxItems = max <= 0 ? MAX_ITEMS : Math.min(max, MAX_ITEMS);

        String json;
        synchronized (sNativeLock) {
            ensureEngineLoaded();
            json = PPOcrBridge.nativeRecognizeBitmap(bitmap, left, top, right, bottom,
                    maxItems, mMinScore, mThreads, false);
        }
        return parseResults(json, detectRotation);
    }

    private OCRResult[] parseResults(String json, boolean detectRotation) {
        if (json == null || json.isEmpty()) {
            throw new IllegalStateException("文字识别失败：原生侧没有返回结果");
        }
        try {
            JSONObject root = new JSONObject(json);
            if (!root.optBoolean("ok", false)) {
                throw new IllegalStateException("文字识别失败：" + root.optString("error", "未知错误"));
            }
            JSONArray items = root.optJSONArray("items");
            List<OCRResult> results = new ArrayList<>();
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item == null) {
                        continue;
                    }
                    String text = item.optString("text", "");
                    if (text.isEmpty()) {
                        continue;
                    }
                    float confidence = (float) item.optDouble("confidence", 0);
                    int x = item.optInt("x", 0);
                    int y = item.optInt("y", 0);
                    int w = item.optInt("w", 0);
                    int h = item.optInt("h", 0);
                    Rect bounds = new Rect(x, y, x + w, y + h);
                    results.add(new OCRResult(mRuntime, text, confidence, bounds, detectRotation,
                            new PPOcrResult(text, confidence, bounds)));
                }
            }
            return results.toArray(new OCRResult[0]);
        } catch (JSONException e) {
            throw new IllegalStateException("文字识别失败：结果解析错误 " + e.getMessage(), e);
        }
    }

    /** 需要时把模型从 assets 复制到私有目录，并（配置变了就）让原生侧加载。 */
    private void ensureEngineLoaded() {
        String dir = mModelsDir;
        if (dir == null) {
            dir = materializeBundledModels();
        }
        String config = dir + "|" + mTargetSize + "|" + mCpuPowerMode + "|" + (mUseFp16 ? 1 : 0)
                + "|" + mRecMaxWidth;
        if (config.equals(sLoadedConfig)) {
            return;
        }
        File base = new File(dir);
        boolean ok = PPOcrBridge.nativeInit(
                new File(base, DET_PARAM).getAbsolutePath(),
                new File(base, DET_MODEL).getAbsolutePath(),
                new File(base, REC_PARAM).getAbsolutePath(),
                new File(base, REC_MODEL).getAbsolutePath(),
                mTargetSize, mUseFp16, false, mCpuPowerMode, mRecMaxWidth);
        if (!ok) {
            sLoadedConfig = null;
            StringBuilder missing = new StringBuilder();
            for (String name : MODEL_FILES) {
                if (!new File(base, name).isFile()) {
                    missing.append(name).append(' ');
                }
            }
            throw new IllegalStateException("PP-OCR 模型加载失败，模型目录：" + dir
                    + (missing.length() > 0 ? "（缺少：" + missing.toString().trim() + "）" : ""));
        }
        sLoadedConfig = config;
    }

    /** 把随包模型复制到 {@code filesDir/models/ppocrv6/}（大小一致就跳过）。 */
    private String materializeBundledModels() {
        Context context = ScriptRuntime.getApplicationContext();
        if (context == null) {
            throw new IllegalStateException("PP-OCR 无法取得 Context，无法释放随包模型");
        }
        File dir = new File(context.getFilesDir(), ASSET_DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建模型目录：" + dir);
        }
        AssetManager assets = context.getAssets();
        byte[] buffer = new byte[16384];
        for (String name : MODEL_FILES) {
            File out = new File(dir, name);
            try (InputStream in = assets.open(ASSET_DIR + "/" + name)) {
                int expected = in.available();
                if (out.isFile() && out.length() > 0 && expected > 0 && out.length() == expected) {
                    continue;
                }
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        fos.write(buffer, 0, n);
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("释放随包模型失败（" + name + "）：" + e.getMessage(), e);
            }
        }
        return dir.getAbsolutePath();
    }

    /** PDF：{@code OCR.release()} —— 释放资源（程序退出时会自动释放）。 */
    @Override
    public void release() {
        if (mReleased) {
            return;
        }
        mReleased = true;
        sLiveCount.decrementAndGet();
        if (mRuntime != null) {
            mRuntime.removeCleanable(this);
        }
        synchronized (sNativeLock) {
            if (sNativeUsers > 0) {
                sNativeUsers--;
            }
            if (sNativeUsers == 0 && sLoadedConfig != null) {
                PPOcrBridge.nativeRelease();
                sLoadedConfig = null;
            }
        }
    }

    /** 脚本退出时的兜底释放（等同 {@link #release()}）。 */
    @Override
    public void close() {
        release();
    }

    @Override
    public boolean isReleased() {
        return mReleased;
    }
}
