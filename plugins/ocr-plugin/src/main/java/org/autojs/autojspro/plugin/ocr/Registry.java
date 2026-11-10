package org.autojs.autojspro.plugin.ocr;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * task-131：OCR 插件入口。
 *
 * <p><b>加载契约（W1 task-130，冻结）</b>：宿主 PluginRegistry 按清单里的
 * {@code org.autojs.plugin.sdk.registry} 反射到本类，调用
 * {@code loadDefault(Context hostContext, Context pluginContext, Object runtime, Object scope)}
 * 并把返回值交给 {@code Plugin.create(...)}。**返回 null 会被 {@code Plugin.create} 直接判为未加载**
 * （K40 dev-1482 的硬阻塞就是这里恒返回 null）⇒ 必须返回**真实对象**。</p>
 *
 * <p><b>返回对象 ≠ 伪造能力</b>：本对象只声明「插件带什么」（libs + 模型路径），真正不可用时
 * **抛点名错误**（复用宿主 OcrAvailability 的文案，反射读取，不抄第二份）。</p>
 *
 * <p>宿主 $ocr 分发接受两种形态：{@code plugin.create(options)} 或 {@code plugin.ocr.create(options)}；
 * 这里实现前者（{@code create(Object)} 用 Object 形参，兼容 Map/JSONObject/脚本对象）。</p>
 */
public final class Registry {

    private static final String TAG = "C-OCR";
    private static final String HOST_MSG_CLASS = "com.stardust.autojs.runtime.api.OcrAvailability";
    private static final String HOST_MSG_FIELD = "NOT_PACKAGED_MESSAGE";
    /** 有序：**依赖在前**（宿主按此顺序 System.load；c++_shared 先于使用它的库）。 */
    private static final String[] PLUGIN_LIBS = {
            "libc++_shared.so", "libppocr.so", "libmlkit_google_ocr_pipeline.so"
    };
    /** 默认后端：插件同时带 ppocr 与 mlkit；用户显式传 options.backend 时以用户为准（宿主读取 options）。 */
    private static final String DEFAULT_BACKEND = "ppocr";
    private static final String ABI = "arm64-v8a";
    private static final String PLUGIN_VERSION = "1.0.0";

    private Registry() {
    }

    /** 入口：返回真实插件对象（不能是 null）。 */
    public static Object loadDefault(Context hostContext, Context pluginContext, Object runtime, Object scope) {
        return new OcrPlugin(hostContext, pluginContext, runtime);
    }

    /** 插件对象：暴露 create(options)；能力真不可用时抛点名错误。 */
    public static final class OcrPlugin {
        private final Context hostContext;
        private final Context pluginContext;
        /** loadDefault 传进来的脚本 runtime：宿主侧会话工厂要求必须传（否则报点名错误）。 */
        private final Object runtime;
        private volatile boolean libsLoaded;

        OcrPlugin(Context hostContext, Context pluginContext, Object runtime) {
            this.hostContext = hostContext;
            this.pluginContext = pluginContext;
            this.runtime = runtime;
        }

        /**
         * 插件 SDK 契约（xiangqi-plugin 同款，K40 dev-1484 的 NPE 就是缺这两个方法）：
         * {@code Plugins.load} 反射调 getAssetsScriptDir() 把 assets/scripts 拷到缓存目录并设 mainScriptPath；
         * 缺方法 ⇒ Plugin.findMethods 留 null ⇒ 反射 Method.invoke 直接 NPE。
         */
        public String getAssetsScriptDir() {
            // 必须是**相对 assets 根**的路径（xiangqi-plugin 的 ASSETS_SCRIPT_DIR = "scripts"）。
            // 写成 "assets/scripts" 会让 PFiles.copyAssetDir 拷不到任何文件 ⇒ 运行期报
            // Module "…/plugin-scripts/<pkg>/index.js" not found.（K40 dev-1485 实测）。
            return "scripts";
        }

        /** 插件版本（与 project-versions.json 的 ocrPluginVersionName 保持一致）。 */
        public String getVersion() {
            return "1.0.0";
        }

        /** 契约形态一：plugin.create(options) ⇒ 返回描述符（宿主负责把会话接成 $ocr 会话）。 */
        public Object create(Object options) {
            ensureLibsLoaded();
            ensureModelsExtracted();
            // 方案 A′（W1 给的唯一入口）：优先走**宿主侧会话工厂**，把 Duck 类型会话直接返回；
            // 插件不实现算法、也不 link engine（这里用反射调用）。
            Object session = tryHostSessionFactory();
            if (session != null) {
                Log.i(TAG, "ocr plugin session from host factory ok backend=" + DEFAULT_BACKEND);
                return session;
            }
            // W1（宿主侧方案 B）消费的字段，**保持这几个**：
            //  libs       有序、依赖在前（宿主按序 System.load 绝对路径、幂等）
            //  backend    用户没显式传 options.backend 时的默认后端（ppocr | mlkit）
            //  modelPath  绝对路径（宿主作为内置会话的 models 传入；相对路径不生效）
            //  libDir/abi/version/assetsScriptDir 仅日志与排障用
            Map<String, Object> descriptor = new HashMap<>();
            // backend 口径（Lead）：用户 options.backend 优先；默认与宿主内置默认一致（ppocr）。
            // 同时在日志里保留「来源=插件」标记，排障时能分清是插件实现还是内置实现。
            descriptor.put("backend", backendFrom(options));
            descriptor.put("pluginPackage", pluginContext.getPackageName());
            descriptor.put("libDir", libDir().getAbsolutePath());
            descriptor.put("modelPath", modelDir().getAbsolutePath());
            descriptor.put("libsLoaded", true);
            descriptor.put("libs", PLUGIN_LIBS.clone());
            descriptor.put("abi", ABI);
            descriptor.put("version", PLUGIN_VERSION);
            descriptor.put("assetsScriptDir", "scripts");
            descriptor.put("source", "plugin");
            Log.i(TAG, "descriptor=" + descriptor + " source=plugin backend=" + descriptor.get("backend"));
            Log.i(TAG, "ocr plugin create ok libDir=" + libDir().getAbsolutePath()
                    + " modelPath=" + modelDir().getAbsolutePath());
            return descriptor;
        }

        /**
         * 调宿主侧会话工厂（W1 的 A′ 入口）：
         * com.stardust.autojs.plugin.PluginNativeLoader.createOcrSession(runtime, host, pluginPackage,
         * versionCode, libDir, modelPath, backend) ⇒ 返回 Duck 类型会话（detect/release/isReleased）。
         *
         * <p>用反射（插件不 link engine）。工厂不存在（老宿主）⇒ 返回 null，让调用方回退描述符（方案 B）；
         * 工厂存在但抛错（例如 soname 不匹配）⇒ **原样抛出**（点名错误，不静默）。</p>
         */
        private Object tryHostSessionFactory() {
            try {
                Class<?> loader = Class.forName("com.stardust.autojs.plugin.PluginNativeLoader");
                java.lang.reflect.Method factory = loader.getMethod("createOcrSession", Object.class,
                        Context.class, String.class, int.class, String.class, String.class, String.class);
                return factory.invoke(null, runtime, hostContext, pluginContext.getPackageName(),
                        pluginVersionCode(), libDir().getAbsolutePath(), modelDir().getAbsolutePath(), DEFAULT_BACKEND);
            } catch (ClassNotFoundException | NoSuchMethodException missing) {
                Log.i(TAG, "宿主没有会话工厂（老宿主）⇒ 回退描述符（方案 B）：" + missing.getMessage());
                return null;
            } catch (java.lang.reflect.InvocationTargetException failure) {
                Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                throw unavailable("宿主会话工厂失败：" + cause.getClass().getSimpleName() + ": " + cause.getMessage());
            } catch (Throwable error) {
                Log.w(TAG, "调用宿主会话工厂异常：" + error);
                return null;
            }
        }

        /** 用户显式 options.backend 优先（兼容 Map / JSONObject / 其它对象），否则用默认后端。 */
        private String backendFrom(Object options) {
            if (options instanceof Map) {
                Object value = ((Map<?, ?>) options).get("backend");
                if (value instanceof String && !((String) value).isEmpty()) {
                    return (String) value;
                }
            } else if (options instanceof org.json.JSONObject) {
                String value = ((org.json.JSONObject) options).optString("backend", "");
                if (!value.isEmpty()) {
                    return value;
                }
            }
            return DEFAULT_BACKEND;
        }

        private int pluginVersionCode() {
            try {
                return pluginContext.getPackageManager()
                        .getPackageInfo(pluginContext.getPackageName(), 0).versionCode;
            } catch (Throwable error) {
                return 0;
            }
        }

        /** 契约形态二（备用）：plugin.ocr.create(options) 的等价入口。 */
        public Object ocr() {
            return this;
        }

        public boolean isReady() {
            try {
                ensureLibsLoaded();
                return true;
            } catch (Throwable error) {
                return false;
            }
        }

        private void ensureLibsLoaded() {
            if (libsLoaded) {
                return;
            }
            synchronized (this) {
                if (libsLoaded) {
                    return;
                }
                File dir = libDir();
                for (String name : PLUGIN_LIBS) {
                    File lib = new File(dir, name);
                    if (!lib.isFile()) {
                        throw unavailable("插件内缺少 " + name + "（" + lib.getAbsolutePath() + "）");
                    }
                    try {
                        // 契约：只用绝对路径 System.load（loadLibrary 只从调用方 APK 自身解析）。
                        System.load(lib.getAbsolutePath());
                        Log.i(TAG, "loaded " + lib.getAbsolutePath());
                    } catch (Throwable error) {
                        // 同源去重后 c++_shared 可能已被宿主加载过：重复加载同一路径会失败但无害。
                        if (!name.equals("libc++_shared.so")) {
                            throw unavailable("加载 " + name + " 失败：" + error.getMessage());
                        }
                        Log.i(TAG, "skip already-loaded " + name + "：" + error.getMessage());
                    }
                }
                libsLoaded = true;
            }
        }

        private File libDir() {
            return new File(pluginContext.getApplicationInfo().nativeLibraryDir);
        }

        private File modelDir() {
            return new File(pluginContext.getFilesDir(), "ocr/models");
        }

        /**
         * 模型：**先用插件自带的 assets/ocr/models/**（契约路径）**，首次使用时解到
         * files/ocr/models（运行期可读的文件路径）。
         *
         * <p>不依赖宿主落盘钩子：等 W1 的 syncPluginLibs 接上后，两边以他的目录为准对齐一次即可。
         * 解包失败只记日志（不伪造能力：真正的可用性仍由 create/宿主判定）。</p>
         */
        private void ensureModelsExtracted() {
            File target = modelDir();
            File marker = new File(target, ".extracted");
            if (marker.isFile()) {
                return;
            }
            try {
                int copied = copyAssetTree("ocr/models", target);
                if (copied > 0) {
                    //noinspection ResultOfMethodCallIgnored
                    marker.createNewFile();
                }
                Log.i(TAG, "models extracted files=" + copied + " -> " + target.getAbsolutePath());
            } catch (Throwable error) {
                Log.w(TAG, "解包 OCR 模型失败（继续，由调用方判可用性）：" + error.getMessage());
            }
        }

        /** 递归把插件 assets 下的目录解出来（AssetManager 不支持直接拿 File，必须自己拷）。 */
        private int copyAssetTree(String assetDir, File targetDir) throws Exception {
            String[] names = pluginContext.getAssets().list(assetDir);
            if (names == null) {
                return 0;
            }
            int copied = 0;
            for (String name : names) {
                String childAsset = assetDir + "/" + name;
                String[] grandChildren = pluginContext.getAssets().list(childAsset);
                if (grandChildren != null && grandChildren.length > 0) {
                    copied += copyAssetTree(childAsset, new File(targetDir, name));
                    continue;
                }
                File out = new File(targetDir, name);
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    continue;
                }
                java.io.InputStream input = pluginContext.getAssets().open(childAsset);
                java.io.OutputStream output = new java.io.FileOutputStream(out);
                try {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = input.read(buffer)) > 0) {
                        output.write(buffer, 0, read);
                    }
                } finally {
                    try {
                        input.close();
                    } catch (Throwable ignored) {
                    }
                    try {
                        output.close();
                    } catch (Throwable ignored) {
                    }
                }
                copied += 1;
            }
            return copied;
        }

        /** 点名错误：优先反射读宿主的唯一文案（不抄第二份）；读不到才用我们自己的一句短话。 */
        private IllegalStateException unavailable(String detail) {
            String message = null;
            try {
                Class<?> type = Class.forName(HOST_MSG_CLASS);
                Object value = type.getField(HOST_MSG_FIELD).get(null);
                if (value instanceof String) {
                    message = (String) value;
                }
            } catch (Throwable ignored) {
                // 宿主版本过旧/类被裁：下面给一句自己的短话（不是那句的复制）。
            }
            if (message == null) {
                message = "OCR 插件不可用：无法加载插件内的 OCR 原生库（见 C-OCR 日志）";
            }
            Log.w(TAG, "ocr plugin unavailable: " + detail);
            return new IllegalStateException(message + "（插件侧： " + detail + "）");
        }
    }
}
