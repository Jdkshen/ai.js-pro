package org.autojs.source.engine.android;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.util.Log;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Loads installed Auto.js SDK APKs. Java values use the existing HostBridge codec. */
public final class Node24PluginLoader {
    public static final String KEY_REGISTRY = "org.autojs.plugin.sdk.registry";
    private static final String TAG = "Node24PluginLoader";
    private final Context context;
    // A job owns its files: concurrent jobs and unload/reload cannot overwrite each other.
    private final File cacheRoot;
    private final Map<String, LoadedPlugin> loaded = new LinkedHashMap<>();

    public Node24PluginLoader(Context context) {
        this.context = context.getApplicationContext();
        cacheRoot = new File(context.getCacheDir(), "node24-plugin-scripts/" + UUID.randomUUID());
    }

    public synchronized JSONObject load(String packageName) throws Exception {
        if (packageName == null || packageName.trim().isEmpty())
            throw new IllegalArgumentException("plugins.load 需要 packageName");
        LoadedPlugin cached = loaded.get(packageName);
        if (cached != null) return record(cached);
        ApplicationInfo app;
        try {
            app = context.getPackageManager().getApplicationInfo(packageName, PackageManager.GET_META_DATA);
        } catch (PackageManager.NameNotFoundException missing) {
            throw new IllegalStateException("插件未安装：" + packageName, missing);
        }
        String registry = app.metaData == null ? null : app.metaData.getString(KEY_REGISTRY);
        if (registry == null || registry.isEmpty())
            throw new IllegalArgumentException("不是 Auto.js SDK 插件（缺少 " + KEY_REGISTRY + "）：" + packageName);
        Context pluginContext = context.createPackageContext(packageName,
                Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
        Class<?> type = Class.forName(registry, true, pluginContext.getClassLoader());
        Method factory = type.getMethod("loadDefault", Context.class, Context.class, Object.class, Object.class);
        PluginRuntime runtime = new PluginRuntime(new File(context.getFilesDir(), "plugins").getAbsolutePath());
        Object plugin;
        try {
            plugin = factory.invoke(null, context, pluginContext, runtime, runtime.getTopLevelScope());
        } catch (InvocationTargetException failure) {
            throw new IllegalStateException("插件 Java 工厂失败：" + packageName, failure.getCause());
        }
        if (plugin == null) throw new IllegalStateException("插件工厂返回 null：" + packageName);
        LoadedPlugin item = new LoadedPlugin();
        item.name = packageName;
        item.instance = plugin;
        item.pluginContext = pluginContext;
        item.directory = new File(cacheRoot, UUID.randomUUID().toString());
        try {
            String assetDir = String.valueOf(plugin.getClass().getMethod("getAssetsScriptDir").invoke(plugin));
            validateAssetPath(assetDir);
            copyAssets(pluginContext.getAssets(), assetDir, item.directory, item.directory);
            File index = new File(item.directory, "index.js");
            if (!index.isFile()) throw new IOException("插件脚本目录缺少 index.js：" + assetDir);
            item.mainScriptPath = index.getCanonicalPath();
            item.assetDir = assetDir;
            // SDK v2 uses an integer protocol version. Legacy SDK plugins can return
            // their display version (e.g. "1.0.0"); that is not a service protocol.
            try { item.version = plugin.getClass().getMethod("getVersion").invoke(plugin); }
            catch (NoSuchMethodException legacy) { item.version = 1; }
            if (item.version instanceof Number && ((Number) item.version).intValue() >= 2
                    && plugin instanceof ServiceConnection) {
                ComponentName service = (ComponentName) plugin.getClass().getMethod("getService").invoke(plugin);
                if (service == null || !context.bindService(new Intent().setComponent(service),
                        (ServiceConnection) plugin, Context.BIND_AUTO_CREATE))
                    throw new IllegalStateException("绑定插件 Service 失败：" + packageName);
                item.bound = true;
            }
            loaded.put(packageName, item);
            return record(item);
        } catch (Exception failure) {
            release(item);
            throw failure;
        }
    }

    public synchronized Object instance(String packageName) {
        LoadedPlugin item = loaded.get(packageName);
        if (item == null) throw new IllegalStateException("插件尚未加载：" + packageName);
        return item.instance;
    }

    public synchronized Class<?> findClass(String name) {
        for (LoadedPlugin item : loaded.values()) {
            try { return Class.forName(name, false, item.pluginContext.getClassLoader()); }
            catch (ClassNotFoundException ignored) { }
        }
        return null;
    }

    private JSONObject record(LoadedPlugin item) throws Exception {
        return new JSONObject().put("packageName", item.name)
                .put("mainScriptPath", item.mainScriptPath)
                .put("scriptsDir", item.directory.getCanonicalPath())
                .put("assetsScriptDir", item.assetDir).put("version", item.version);
    }

    public synchronized boolean unload(String packageName) {
        LoadedPlugin item = loaded.remove(packageName);
        if (item == null) return false;
        release(item);
        return true;
    }

    public synchronized int unloadAll() {
        int count = loaded.size();
        for (LoadedPlugin item : loaded.values()) release(item);
        loaded.clear();
        try { deleteOwned(cacheRoot, cacheRoot); }
        catch (IOException failure) { Log.w(TAG, "清理插件脚本缓存失败", failure); }
        return count;
    }

    private void release(LoadedPlugin item) {
        if (item.bound) {
            try { context.unbindService((ServiceConnection) item.instance); }
            catch (RuntimeException failure) { Log.w(TAG, "解绑插件失败", failure); }
            item.bound = false;
        }
        // Optional SDK cleanup hook; absence is normal, thrown errors remain visible.
        try { item.instance.getClass().getMethod("onUnload").invoke(item.instance); }
        catch (NoSuchMethodException ignored) { }
        catch (Exception failure) { Log.w(TAG, "插件 onUnload 失败", failure); }
        try { deleteOwned(item.directory, cacheRoot); }
        catch (IOException failure) { Log.w(TAG, "清理插件脚本失败", failure); }
    }

    private static void validateAssetPath(String path) throws IOException {
        if (path == null || path.isEmpty() || path.startsWith("/") || path.contains("\\"))
            throw new IOException("非法插件脚本目录：" + path);
        for (String part : path.split("/"))
            if (part.equals("..") || part.equals(".")) throw new IOException("非法插件脚本目录：" + path);
    }

    private static void copyAssets(AssetManager assets, String source, File target, File root) throws IOException {
        String canonical = target.getCanonicalPath();
        String base = root.getCanonicalPath();
        if (!canonical.equals(base) && !canonical.startsWith(base + File.separator))
            throw new IOException("插件资产越出缓存目录");
        String[] children = assets.list(source);
        if (children != null && children.length > 0) {
            if (!target.isDirectory() && !target.mkdirs()) throw new IOException("无法创建 " + target);
            for (String child : children) {
                if (child.contains("/") || child.equals("..")) throw new IOException("非法插件资产名称");
                copyAssets(assets, source + "/" + child, new File(target, child), root);
            }
        } else {
            File parent = target.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("无法创建 " + parent);
            try (InputStream input = assets.open(source); FileOutputStream output = new FileOutputStream(target)) {
                byte[] buffer = new byte[32768];
                int n;
                while ((n = input.read(buffer)) >= 0) output.write(buffer, 0, n);
            }
        }
    }

    private static void deleteOwned(File file, File root) throws IOException {
        if (file == null || !file.exists()) return;
        String path = file.getCanonicalPath();
        String base = root.getCanonicalPath();
        if (!path.equals(base) && !path.startsWith(base + File.separator)) throw new IOException("缓存路径越界");
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteOwned(child, root);
        if (!file.delete()) throw new IOException("无法删除插件缓存：" + file);
    }

    /** Object-typed SDK registry context. Plugins that require a specific V8 runtime need an adapter. */
    public static final class PluginRuntime {
        private final String pluginSearchDir;
        private final Object topLevelScope = new Object();
        PluginRuntime(String directory) { pluginSearchDir = directory; }
        public String getEngine() { return "nodejs"; }
        public String getPluginSearchDir() { return pluginSearchDir; }
        public Object getTopLevelScope() { return topLevelScope; }
        public Context createScopedAppContext(Context host, Context plugin) { return plugin; }
    }

    private static final class LoadedPlugin {
        String name, mainScriptPath, assetDir;
        Object version;
        Object instance;
        Context pluginContext;
        File directory;
        boolean bound;
    }
}
