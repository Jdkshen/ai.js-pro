package com.jdkshen.aijspro.autojs.build

/**
 * task-131 ④ / task-132：OCR 载荷该不该进产物 —— **纯策略**（可单测，无 Android 依赖）。
 *
 * <p>背景：现在只看 optimization.removeOcr；用户口径是「OCR 彻底剥离成插件」，所以还要看
 * **主程序是否还内建 OCR** 与**OCR 插件是否已安装**。这里有两条**不同**的路径，别混为一谈：</p>
 * <ul>
 *   <li><b>app 运行期</b>（W1 的落盘钩子，A 方案：插件把 .so 落到私有目录 + 绝对路径 System.load）——
 *       由引擎/插件负责，本类不参与。</li>
 *   <li><b>打包期</b>（本类 + PluginOcrSource）：产物里的 OCR libs/模型从哪来 —— 宿主 APK 还是
 *       已安装的 OCR 插件 APK。</li>
 * </ul>
 *
 * <p><b>默认行为边界</b>：removeOcr=false、未装 OCR 插件、主程序仍内建 OCR、选择=AUTO ⇒ KEEP，
 * 与今天逐字一致；只有显式打开 removeOcr 或选择「不随包」或主程序已不再内建且装了插件时才改变。</p>
 */
object PluginPresencePolicy {

    /** 用户/打包页的选择。 */
    enum class BundleOcrChoice {
        /** 自动：按「主程序是否内建 / 插件是否已装」推断。 */
        AUTO,
        /** 显式随包（即使主程序已不内建，也从已安装插件里取）。 */
        WITH_APP,
        /** 显式不随包（等价于 removeOcr=true）。 */
        PLUGIN_ONLY
    }

    /** OCR 载荷的处置。 */
    enum class OcrDisposition {
        /** 产物带 OCR（来自宿主 APK 或插件 APK）。 */
        KEEP,
        /** 产物不带 OCR，__ocr__.js 换 796B 桩（点名错误可达）。 */
        DROP_AND_STUB,
        /** 宿主已不内建 OCR，但插件装了 ⇒ 从插件 APK 拷 libs+模型进产物。 */
        COPY_FROM_PLUGIN
    }

    data class Decision(
        val disposition: OcrDisposition,
        val reason: String
    )

    /**
     * @param removeOcr          project.json 的 optimization.removeOcr
     * @param pluginInstalled    OCR 插件是否已安装（com.jdkshen.ocr.plugin）
     * @param hostHasOcr        主程序 APK 里是否还内建 OCR libs（剥离后为 false）
     * @param pluginHasOcr      插件 APK 里是否真的有 OCR libs+模型（防止装了个空壳）
     * @param choice             打包页选择（默认 AUTO）
     */
    @JvmStatic
    @JvmOverloads
    fun decide(
        removeOcr: Boolean,
        pluginInstalled: Boolean,
        hostHasOcr: Boolean,
        pluginHasOcr: Boolean,
        choice: BundleOcrChoice = BundleOcrChoice.AUTO
    ): Decision {
        if (removeOcr || choice == BundleOcrChoice.PLUGIN_ONLY) {
            return Decision(
                OcrDisposition.DROP_AND_STUB,
                if (removeOcr) "optimization.removeOcr=true：不带内建 OCR（脚本调用 \$ocr 会得到桩的点名错误）"
                else "打包页选择「不随包」：不带内建 OCR（脚本调用 \$ocr 会得到桩的点名错误）"
            )
        }
        if (choice == BundleOcrChoice.WITH_APP) {
            return if (hostHasOcr) {
                Decision(OcrDisposition.KEEP, "显式随包 + 主程序仍内建 OCR：直接用宿主 APK 的 libs/模型")
            } else if (pluginInstalled && pluginHasOcr) {
                Decision(OcrDisposition.COPY_FROM_PLUGIN, "显式随包 + 主程序已不内建 ⇒ 从已安装的 OCR 插件 APK 取 libs/模型")
            } else {
                Decision(
                    OcrDisposition.DROP_AND_STUB,
                    "显式随包但取不到 OCR（主程序不内建且插件未装/为空壳）⇒ 不带 OCR，脚本调用 \$ocr 走桩点名错误"
                )
            }
        }
        // AUTO
        if (hostHasOcr) {
            return Decision(OcrDisposition.KEEP, "默认：主程序仍内建 OCR ⇒ 随包（与今天逐字一致）")
        }
        if (pluginInstalled && pluginHasOcr) {
            return Decision(OcrDisposition.COPY_FROM_PLUGIN, "主程序已不内建 OCR，但插件已装 ⇒ 从插件 APK 取 libs/模型")
        }
        return Decision(
            OcrDisposition.DROP_AND_STUB,
            "主程序已不内建 OCR 且插件未装/为空壳 ⇒ 不带 OCR（脚本调用 \$ocr 走桩点名错误）"
        )
    }
}
