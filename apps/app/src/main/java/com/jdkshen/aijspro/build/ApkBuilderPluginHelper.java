package com.jdkshen.aijspro.build;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;

/**
 * Opens the APK-packing runtime template and reports where it came from.
 *
 * <p>模板（~43MB 的 arm64 未签名运行时）原先是主程序的 {@code assets/template.apk}，
 * 现在随「APK 打包插件」分发（{@code plugins/apkbuilder-plugin}），主程序只为真正要用
 * 打包功能的用户付这份体积。查找顺序：
 * <ol>
 *     <li>{@code /sdcard/template.apk}：手动覆盖，调试与自动化测试都靠它；</li>
 *     <li>宿主私有缓存 {@code files/apk-builder/template.apk}：从插件落地过的副本；</li>
 *     <li>已安装的打包插件：反射调用 {@code ensureTemplate(File)} 落地一份；</li>
 *     <li>主程序自带的 {@code assets/template.apk}：老版本/自编包可能还有，有就用。</li>
 * </ol>
 *
 * <p>都找不到时抛带操作指引的 {@link IOException}——打包页会把它原样显示给用户。
 */
public class ApkBuilderPluginHelper {

    private static final String TAG = "ApkBuilderPluginHelper";
    /** 本仓库自己的打包插件（模板随它分发）。 */
    public static final String PLUGIN_PACKAGE_NAME = "com.jdkshen.apkbuilder.plugin";
    /** 历史 Auto.js ApkBuilder 插件：只保留探测能力，兼容旧装机环境。 */
    private static final String LEGACY_PLUGIN_PACKAGE_NAME = "org.autojs.apk_builder.plugin";
    private static final String TEMPLATE_APK_PATH = "template.apk";
    /** 模板小于这个大小一律当成截断/坏文件（真实模板 ~43MB）。 */
    private static final long TEMPLATE_MIN_BYTES = 20L * 1024 * 1024;
    /** 插件缓存目录名（在宿主 files 下）。 */
    private static final String CACHE_DIR_NAME = "apk-builder";
    /** 记录缓存来自哪个插件版本，便于界面/日志说明来源。 */
    private static final String CACHE_VERSION_MARKER = "template.plugin-version";
    private static final String REGISTRY_META_DATA = "org.autojs.plugin.sdk.registry";

    /** 模板可用性 + 来源说明。 */
    public static final class TemplateStatus {
        /** 能否拿到模板。 */
        public final boolean available;
        /** 来源短名（界面展示用），例如「APK 打包插件 (1.0.0)」。 */
        public final String source;
        /** 更详细的原因/说明；不可用时就是可展示的原因。 */
        public final String detail;

        TemplateStatus(boolean available, String source, String detail) {
            this.available = available;
            this.source = source;
            this.detail = detail;
        }
    }

    /** 打包入口：打包页与仪器测试都只认这个方法。 */
    public static InputStream openTemplateApk(Context context) throws IOException {
        File manual = manualTemplateFile();
        if (isUsableTemplate(manual)) {
            Log.i(TAG, "Using manual template " + manual);
            return new FileInputStream(manual);
        }
        File cached = cachedTemplateFile(context);
        if (isUsableTemplate(cached)) {
            return new FileInputStream(cached);
        }
        File extracted = extractTemplateFromPlugin(context);
        if (extracted != null) {
            return new FileInputStream(extracted);
        }
        try {
            InputStream bundled = context.getAssets().open(TEMPLATE_APK_PATH);
            Log.i(TAG, "Using bundled assets/" + TEMPLATE_APK_PATH);
            return bundled;
        } catch (IOException ignored) {
            // 现在的主程序不带模板，落到下面的操作指引。
        }
        throw new IOException(missingTemplateMessage());
    }

    /** 打包页进入页面时问一次，用来决定要不要显示「装插件」提示。 */
    public static TemplateStatus templateStatus(Context context) {
        File manual = manualTemplateFile();
        if (isUsableTemplate(manual)) {
            return new TemplateStatus(true, manual.getPath(), "手动放置的模板");
        }
        File cached = cachedTemplateFile(context);
        if (isUsableTemplate(cached)) {
            String origin = readTextQuietly(cacheVersionMarker(context));
            return new TemplateStatus(true, cached.getPath(),
                    origin.isEmpty() ? "已从打包插件落地" : "来自打包插件 " + origin);
        }
        if (isPluginInstalled(context)) {
            return new TemplateStatus(true, pluginLabel(context), "插件已安装，首次打包时会把模板落地");
        }
        try {
            InputStream bundled = context.getAssets().open(TEMPLATE_APK_PATH);
            bundled.close();
            return new TemplateStatus(true, "assets/" + TEMPLATE_APK_PATH, "随主程序分发");
        } catch (IOException ignored) {
            // 没有自带模板，确实需要装插件。
        }
        return new TemplateStatus(false, "", "未安装打包插件");
    }

    /** 现在能不能拿到模板（不真读模板，只看文件尺寸与插件是否在位）。 */
    public static boolean isTemplateAvailable(Context context) {
        if (isUsableTemplate(manualTemplateFile()) || isUsableTemplate(cachedTemplateFile(context))) {
            return true;
        }
        if (isPluginInstalled(context)) {
            return true;
        }
        try {
            InputStream bundled = context.getAssets().open(TEMPLATE_APK_PATH);
            bundled.close();
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    /** 打包插件（本仓库那个）是否已安装。 */
    public static boolean isPluginInstalled(Context context) {
        return packageInfoOf(context, PLUGIN_PACKAGE_NAME) != null;
    }

    /** 打包插件版本号；未安装为 0。 */
    public static int getPluginVersion(Context context) {
        PackageInfo info = packageInfoOf(context, PLUGIN_PACKAGE_NAME);
        return info == null ? 0 : info.versionCode;
    }

    /** 已安装插件的展示名（「APK 打包插件 (1.0.0)」）；未安装返回空串。 */
    public static String pluginLabel(Context context) {
        PackageInfo info = packageInfoOf(context, PLUGIN_PACKAGE_NAME);
        if (info == null) {
            return "";
        }
        String name = info.applicationInfo == null
                ? PLUGIN_PACKAGE_NAME
                : String.valueOf(context.getPackageManager().getApplicationLabel(info.applicationInfo));
        return name + " (" + displayVersion(info) + ")";
    }

    /** 手动覆盖模板的位置（放这里就不必装插件）。 */
    public static File manualTemplateFile() {
        return new File(Environment.getExternalStorageDirectory(), TEMPLATE_APK_PATH);
    }

    /** 从插件落地到宿主私有目录的模板路径。 */
    public static File cachedTemplateFile(Context context) {
        return new File(cacheDir(context), TEMPLATE_APK_PATH);
    }

    /** 历史遗留探测：官方 Auto.js 的 ApkBuilder 插件（当前打包链路不依赖它）。 */
    public static boolean isLegacyPluginAvailable(Context context) {
        return packageInfoOf(context, LEGACY_PLUGIN_PACKAGE_NAME) != null;
    }

    // ------------------------------------------------------------------ 内部

    private static File cacheDir(Context context) {
        return new File(context.getFilesDir(), CACHE_DIR_NAME);
    }

    private static File cacheVersionMarker(Context context) {
        return new File(cacheDir(context), CACHE_VERSION_MARKER);
    }

    private static boolean isUsableTemplate(File file) {
        return file != null && file.isFile() && file.length() >= TEMPLATE_MIN_BYTES;
    }

    /**
     * 反射调用打包插件，让它在宿主私有目录里落地一份模板。
     *
     * @return 落地文件；插件不在位或调用失败时返回 null（原因写进日志）
     */
    private static File extractTemplateFromPlugin(Context context) {
        PackageInfo info = packageInfoOf(context, PLUGIN_PACKAGE_NAME);
        if (info == null) {
            return null;
        }
        try {
            Context pluginContext = context.createPackageContext(PLUGIN_PACKAGE_NAME,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            ApplicationInfo applicationInfo = pluginContext.getPackageManager()
                    .getApplicationInfo(PLUGIN_PACKAGE_NAME, PackageManager.GET_META_DATA);
            String registryClass = applicationInfo.metaData == null
                    ? null
                    : applicationInfo.metaData.getString(REGISTRY_META_DATA);
            if (registryClass == null) {
                throw new IllegalStateException("插件清单缺少 " + REGISTRY_META_DATA + " 元数据");
            }
            Class<?> registry = Class.forName(registryClass, true, pluginContext.getClassLoader());
            Method loadDefault = registry.getMethod("loadDefault",
                    Context.class, Context.class, Object.class, Object.class);
            Object plugin = loadDefault.invoke(null, context, pluginContext, null, null);
            if (plugin == null) {
                throw new IllegalStateException("插件的 loadDefault 返回空");
            }
            Method ensureTemplate = plugin.getClass().getMethod("ensureTemplate", File.class);
            File template = (File) ensureTemplate.invoke(plugin, cacheDir(context));
            if (!isUsableTemplate(template)) {
                throw new IllegalStateException("插件落地的模板不可用：" + template);
            }
            writeTextQuietly(cacheVersionMarker(context), displayVersion(info));
            Log.i(TAG, "Template extracted from plugin " + displayVersion(info) + " -> " + template);
            return template;
        } catch (Throwable error) {
            Log.e(TAG, "Failed to extract the runtime template from " + PLUGIN_PACKAGE_NAME, error);
            return null;
        }
    }

    private static String displayVersion(PackageInfo info) {
        return (info.versionName == null ? String.valueOf(info.versionCode) : info.versionName)
                + " (" + info.versionCode + ")";
    }

    private static String missingTemplateMessage() {
        return "找不到打包用的运行时模板（template.apk，约 43MB）。\n"
                + "请任选一种：\n"
                + "1) 安装「APK 打包插件」后重试（设置 → 插件，或从更新源下载 apkbuilder-plugin.apk）；\n"
                + "2) 手动把 template.apk 放到 " + manualTemplateFile().getPath() + "。";
    }

    private static PackageInfo packageInfoOf(Context context, String packageName) {
        try {
            return context.getPackageManager().getPackageInfo(packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private static String readTextQuietly(File file) {
        try {
            byte[] bytes = new byte[(int) Math.min(file.length(), 256)];
            if (bytes.length == 0) {
                return "";
            }
            try (FileInputStream input = new FileInputStream(file)) {
                int read = input.read(bytes);
                return read <= 0 ? "" : new String(bytes, 0, read, "UTF-8").trim();
            }
        } catch (IOException e) {
            return "";
        }
    }

    private static void writeTextQuietly(File file, String text) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                return;
            }
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(text.getBytes("UTF-8"));
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to write " + file, e);
        }
    }
}

