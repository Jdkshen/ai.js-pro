package com.jdkshen.aijspro.autojs.build

/**
 * task-129：removeOcr=true 时给 assets/modules/__ocr__.js 用的**桩**（纯 Kotlin、可单测）。
 *
 * <p>为什么不能直删：我们的点名错误守卫写在 __ocr__.js **内部**；把这个文件删掉后，
 * 模块加载器会在加载阶段先抛 Module "__ocr__" not found.（真机 22:01:39.460/E 实测），
 * 我们的文案**永远不可达**。所以这里保留模块名与 $ocr 入口，任何调用都抛同一条点名错误。</p>
 *
 * <p>文案来自 Java 侧唯一真值 {@code OcrAvailability.NOT_PACKAGED_MESSAGE}（调用方传入，不写第二份）；
 * 桩里**不引用任何 com.stardust.* Java 类**（OCR 库已不在包内，引用会在加载期就炸）。</p>
 */
object OcrModuleStub {
    /** 桩的目标大小上限（字节）：几百字节量级，绝不能变成第二份实现。 */
    const val MAX_BYTES = 2048

    private const val BEGIN = "// ---- aijspro ocr stub (task-129) ----"

    /** 生成桩内容；message 必须是 OcrAvailability.NOT_PACKAGED_MESSAGE。 */
    @JvmStatic
    fun content(message: String): String {
        val escaped = message.replace("\\\\", "\\\\\\\\").replace("'", "\\\\'").replace("\r", "").replace("\n", " ")
        return BEGIN + "\n" +
            "// 保留模块名与 \$ocr 入口：任何调用都抛点名错误，而不是让加载器报 Module not found.。\n" +
            "// 文案与 Java OcrAvailability.NOT_PACKAGED_MESSAGE 同源（由打包端注入，不手写第二份）。\n" +
            "var OCR_NOT_PACKAGED = '" + escaped + "';\n" +
            "function ocrNotPackaged() { throw new Error(OCR_NOT_PACKAGED); }\n" +
            "module.exports = function (runtime, global) {\n" +
            "    var ocr = {\n" +
            "        create: ocrNotPackaged,\n" +
            "        detect: ocrNotPackaged,\n" +
            "        release: ocrNotPackaged,\n" +
            "        isReleased: ocrNotPackaged,\n" +
            "        liveCount: ocrNotPackaged\n" +
            "    };\n" +
            "    if (global) { global.\$ocr = ocr; }\n" +
            "    return ocr;\n" +
            "};\n"
    }
}
