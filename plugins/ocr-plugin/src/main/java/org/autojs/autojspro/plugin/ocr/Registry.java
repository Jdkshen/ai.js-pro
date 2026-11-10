package org.autojs.autojspro.plugin.ocr;

import android.content.Context;

/**
 * task-131：OCR 插件入口。
 *
 * <p><b>加载契约（W1 task-130，冻结）</b>：类名与查找规则沿用宿主 PluginRegistry 的反射常量表，
 * 方法签名为 loadDefault(Context hostContext, Context pluginContext, Object runtime, Object scope)
 * ——与 plugins/xiangqi-plugin、样例 nodeplugin 同形，不自造第二套。</p>
 *
 * <p>本类只负责「注册能力」：把插件自带的 lib/ 落盘并让宿主能按绝对路径加载（宿主侧
 * PluginNativeLoader 的钩子），$ocr 的具体分发在 W1 落地后接到这里（当前返回 null 表示未注册，
 * 宿主会走既有的点名错误）。</p>
 */
public final class Registry {

    private Registry() {
    }

    public static Object loadDefault(Context hostContext, Context pluginContext, Object runtime, Object scope) {
        // TODO(W1)：$ocr 分发落地后，这里注册 OCR 能力（走宿主 PluginNativeLoader 的落盘 + System.load）。
        // 在此之前返回 null：宿主按「能力未注册」处理，脚本拿到的是既有的点名错误（不是静默 undefined）。
        return null;
    }
}
