package com.stardust.autojs.runtime.api;

import android.graphics.Rect;

/**
 * PP-OCR 单条识别结果：{@link OCRResult#getJavaObject()} 返回的原生结果对象。
 *
 * <p>与 ML Kit 后端（{@code OCRResult.javaObject} 是 {@code Text.Line}）对应，
 * PP-OCR 后端的 javaObject 就是这个 POJO。
 */
public final class PPOcrResult {

    /** 识别出的文字。 */
    public final String text;
    /** 平均字符置信度，范围 [0, 1]。 */
    public final float confidence;
    /** 文字在原图中的范围。 */
    public final Rect bounds;

    public PPOcrResult(String text, float confidence, Rect bounds) {
        this.text = text;
        this.confidence = confidence;
        this.bounds = bounds;
    }

    @Override
    public String toString() {
        return "PPOcrResult{text='" + text + "', confidence=" + confidence + ", bounds=" + bounds + '}';
    }
}
