package com.jdkshen.xiangqi.plugin;

import android.content.Context;

/**
 * 插件注册类。
 *
 * <p>宿主按 {@code AndroidManifest.xml} 里 {@code org.autojs.plugin.sdk.registry} 元数据指向的类名
 * 反射到这里，调用静态的 {@code loadDefault(宿主Context, 插件Context, runtime, scope)}。
 * 宿主只做反射（见 {@code modules/engine/.../core/plugin/Plugin.java}），所以插件不需要依赖
 * 任何 SDK 依赖——签名一致即可，这也让它能同时被官方 Auto.js Pro 装载。
 */
public final class PluginRegistry {

    private PluginRegistry() {
    }

    /** @return 交给插件入口脚本（index.js）的插件对象。 */
    public static Object loadDefault(Context hostContext, Context pluginContext, Object runtime, Object scope) {
        return new XiangqiPlugin(hostContext, pluginContext, runtime, scope);
    }
}
