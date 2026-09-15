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
public class Ocr {

    /** ML Kit 要求输入图至少 32x32。 */
    private static final int MIN_REGION_SIZE = 32;

    private final ScriptRuntime mRuntime;
    private TextRecognizer mRecognizer;
    private boolean mReleased;

    public Ocr(ScriptRuntime runtime) {
        mRuntime = runtime;
    }

    /**
     * PDF：{@code OCR.detect(image[, options])}。
     *
     * @param max          识别文本数量上限（文档默认 1000）
     * @param detectRotation 是否检测文字旋转（文档默认 false）
     * @param region       识别区域，0/2/4 个元素（[left, top, width, height] 或 [left, top]）
     */
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
        if (mRecognizer != null) {
            mRecognizer.close();
            mRecognizer = null;
        }
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
