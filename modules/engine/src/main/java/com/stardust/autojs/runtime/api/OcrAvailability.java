package com.stardust.autojs.runtime.api;

/**
 * task-105：OCR「能力未随包」的**唯一同源**文案与判据（三引擎共用）。
 *
 * <p>`optimization.removeOcr=true` 时产物不带 libppocr.so / libmlkit* 与识别模型；三处入口
 * （Rhino `assets/modules/__ocr__.js`、QuickJS `assets/modules/qjs-plugins.js`、Node24 `assets/node24/modules/ocr.js`
 * + 宿主 `Node24HostBridge.handleOcr`）**必须给同一句点名错误**，不能出现 UnsatisfiedLinkError 或静默 undefined。
 *
 * <p>默认（开关关）路径**逐字不变**：本类只在「识别到能力缺失」时被使用。
 */
public final class OcrAvailability {

    /** 三引擎共用的点名文案（脚本侧 JS 常量与本串逐字一致，测试钉住）。 */
    public static final String NOT_PACKAGED_MESSAGE =
            "OCR 能力未随包（optimization.removeOcr=true）：libppocr.so / libmlkit* 不在包内；"
                    + "如需 OCR 请把该开关关掉并重新打包。";

    private OcrAvailability() {
    }

    /**
     * 判据：沿 cause 链找「类/库缺失」这一类失败（UnsatisfiedLinkError / NoClassDefFoundError /
     * ClassNotFoundException / 消息点名 libppocr|libmlkit|dlopen）。其它失败一律 false（保持原样文本）。
     */
    public static boolean isAbilityMissing(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof UnsatisfiedLinkError || current instanceof NoClassDefFoundError
                    || current instanceof ClassNotFoundException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && (message.contains("libppocr") || message.contains("libmlkit")
                    || message.contains("couldn't find") && message.contains("dlopen"))) {
                return true;
            }
        }
        return false;
    }
}