package com.stardust.autojs.runtime.api;

import android.graphics.Rect;

import com.stardust.autojs.annotation.ScriptInterface;
import com.stardust.autojs.runtime.ScriptRuntime;

/**
 * PDF §2.22（第 184~185 页）：{@code OCR.detect()} 返回数组的元素。
 * 包含可信度、文本内容、文本范围、文本旋转度以及旋转可信度。
 */
public class OCRResult {

    private final ScriptRuntime mRuntime;
    private final String mText;
    private final double mConfidence;
    private final Rect mBounds;
    private final boolean mDetectRotation;
    private final Object mJavaObject;

    public OCRResult(ScriptRuntime runtime, String text, Float confidence, Rect bounds,
                     boolean detectRotation, Object javaObject) {
        mRuntime = runtime;
        mText = text;
        // ML Kit 偶尔给 null（老版本模型），文档要求 [0, 1]
        mConfidence = confidence == null ? 0 : confidence;
        mBounds = bounds;
        mDetectRotation = detectRotation;
        mJavaObject = javaObject;
    }

    /** PDF：{@code OCRResult.text} —— 识别出的文字内容。 */
    @ScriptInterface
    public String getText() {
        return mText;
    }

    /** PDF：{@code OCRResult.confidence} —— 可信度，范围 [0, 1]。 */
    @ScriptInterface
    public double getConfidence() {
        return mConfidence;
    }

    /** PDF：{@code OCRResult.bounds} —— 该文字在**图片**中的范围。 */
    @ScriptInterface
    public Rect getBounds() {
        return mBounds;
    }

    /**
     * PDF：{@code OCRResult.rotation} —— 旋转角度，范围 [0, 360)，只在 detect 时
     * 指定 {@code detectRotation} 为 true 时有效。ML Kit 不做旋转检测，恒为 0。
     */
    @ScriptInterface
    public double getRotation() {
        return 0;
    }

    /** PDF：{@code OCRResult.rotationConfidence} —— 旋转可信度；同上，恒为 0。 */
    @ScriptInterface
    public double getRotationConfidence() {
        return 0;
    }

    /** PDF：{@code OCRResult.javaObject} —— 原始识别结果对象（这里是 ML Kit 的 Text.Line）。 */
    @ScriptInterface
    public Object getJavaObject() {
        return mJavaObject;
    }

    public boolean isRotationDetected() {
        return mDetectRotation;
    }

    /**
     * PDF：{@code OCRResult.clickCenter()} —— 点击文字范围的中点，返回是否点击成功。
     * 等价于 {@code click(bounds.centerX(), bounds.centerY())}。
     */
    @ScriptInterface
    public boolean clickCenter() {
        if (mBounds == null) {
            return false;
        }
        return mRuntime.automator.click(mBounds.centerX(), mBounds.centerY());
    }

    @Override
    public String toString() {
        return "OCRResult{text='" + mText + "', confidence=" + mConfidence + ", bounds=" + mBounds + '}';
    }
}
