package com.jdkshen.apkbuilder.plugin;

import android.content.Context;

/**
 * 插件注册类。
 *
 * <p>宿主按 {@code AndroidManifest.xml} 里 {@code org.autojs.plugin.sdk.registry} 元数据指向的类名
 * 反射到这里，调用静态的 {@code loadDefault(宿主Context, 插件Context, runtime, scope)}。
 *
 * <p>两种调用方都走这里：
 * <ul>
 *     <li>脚本侧：{@code plugins.load("com.jdkshen.apkbuilder.plugin")}（QuickJS/Rhino 都行）；</li>
 *     <li>打包页：{@code ApkBuilderPluginHelper} 直接反射（没有脚本运行时，runtime/scope 为 null）。</li>
 * </ul>
 */
public final class PluginRegistry {

    private PluginRegistry() {
    }

    /** @return 插件对象；脚本侧交给 {@code assets/scripts/index.js}，打包页直接反射调方法。 */
    public static Object loadDefault(Context hostContext, Context pluginContext, Object runtime, Object scope) {
        return new ApkBuilderPlugin(hostContext, pluginContext, runtime, scope);
    }
}
