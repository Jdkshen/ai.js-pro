package com.stardust.autojs.runtime.api;

import com.stardust.autojs.core.image.ImageWrapper;

/**
 * {@code $ocr} 会话的公共接口：两种后端**同形**，脚本层可按 {@code options.backend} 无缝切换。
 *
 * <ul>
 *   <li>{@link Ocr} —— ML Kit 中文文字识别（默认，模型内置）</li>
 *   <li>{@link PPOcr} —— ncnn 版 PP-OCRv6（{@code backend:'ppocr'}，更准，只随包 arm64-v8a）</li>
 * </ul>
 */
public interface OcrSession {

    /** {@code session.detect(image[, options])}：返回按行/块组织的识别结果。 */
    OCRResult[] detect(ImageWrapper image, int max, boolean detectRotation, int[] region);

    /** {@code session.release()}：释放资源（幂等）。 */
    void release();

    /** 是否已经释放。 */
    boolean isReleased();
}
