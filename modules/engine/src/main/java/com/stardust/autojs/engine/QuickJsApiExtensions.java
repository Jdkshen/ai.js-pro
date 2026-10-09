package com.stardust.autojs.engine;

import com.stardust.autojs.core.plugin.Plugin;
import com.stardust.autojs.runtime.ScriptRuntime;
import com.stardust.autojs.runtime.api.Images;
import com.stardust.autojs.runtime.api.TouchSession;
import java.util.concurrent.ConcurrentHashMap;

/** Asset-based APIs use the existing Java bridge without rebuilding native binaries. */
public final class QuickJsApiExtensions {
    private static final ConcurrentHashMap<Integer, QuickJsApiExtensions> ACTIVE = new ConcurrentHashMap<>();
    private final ScriptRuntime runtime;
    private final QuickJsHostBridge host;
    private QuickJsApiExtensions(ScriptRuntime runtime, QuickJsHostBridge host) { this.runtime = runtime; this.host = host; }
    static void install(ScriptRuntime runtime, QuickJsHostBridge host) {
        int id = runtime.engines.myEngine().getId();
        QuickJsApiExtensions api = new QuickJsApiExtensions(runtime, host);
        ACTIVE.put(id, api);
        runtime.addCleanable(() -> ACTIVE.remove(id, api));
    }
    public static QuickJsApiExtensions forEngine(int id) {
        QuickJsApiExtensions api = ACTIVE.get(id);
        if (api == null) throw new IllegalStateException("脚本引擎已经结束");
        return api;
    }
    public Images getImages() { return (Images) runtime.getImages(); }
    public TouchSession createTouch(boolean adb) { return new TouchSession(runtime, adb); }

    /**
     * QuickJS 侧的 `$plugins.load(包名)`：拿插件 SDK 实例 + 入口脚本路径
     * （scripts/qjs-plugins.js 再 require 入口 index.js 并把本对象交给它）。
     *
     * <p>与 Rhino 走的是同一段 Java（{@link com.stardust.autojs.runtime.api.Plugins#load}）：
     * 反射注册类、拷 assets/scripts 到 cache。QuickJS 没有 TopLevelScope，传给插件的 scope 是 null，
     * 插件不应依赖它（官方 SDK 的插件会自己判空）。
     *
     * @return 宿主侧插件包装对象（getMainScriptPath/getAssetsScriptDir/unwrap）
     */
    public Plugin loadPlugin(String packageName) {
        Plugin plugin = runtime.plugins.load(packageName);
        if (plugin == null) {
            throw new IllegalStateException("插件没安装或缺少 org.autojs.plugin.sdk.registry 元数据：" + packageName);
        }
        return plugin;
    }
    public com.stardust.autojs.core.graphics.ScriptCanvasView findCanvasView(int windowId, String id) {
        return host.findCanvasView(windowId, id);
    }
}
