package com.jdkshen.aijspro.build;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
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
    /** task-107：瘦身模板需要从宿主 APK 补齐的条目清单（general/opencv/v8）。 */
    private static final String EXCLUDED_ZIP_ASSET = "excluded-zip-files.json";
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

    /** 一次打包实际选中的模板 + 指纹；调用方必须关闭 input。 */
    public static final class TemplateSelection implements Closeable {
        public final ApkTemplateInspector.Info info;
        public final InputStream input;
        TemplateSelection(ApkTemplateInspector.Info info, InputStream input) {
            this.info = info;
            this.input = input;
        }
        @Override public void close() throws IOException {
            if (input != null) input.close();
        }
    }

    public static TemplateSelection openTemplateSelection(Context context) throws IOException {
        return openTemplateSelection(context, false);
    }

    /**
     * 选模板并计算 source/path/size/sha256/init.js 修复标记。
     * forceBundled=true 时跳过 /sdcard 与宿主缓存，直接用 app 内置模板（会重新 merge 瘦身模板）。
     */
    public static TemplateSelection openTemplateSelection(Context context, boolean forceBundled) throws IOException {
        if (!forceBundled) {
            File manual = manualTemplateFile();
            if (isUsableTemplate(manual)) {
                return new TemplateSelection(ApkTemplateInspector.inspect(manual, ApkTemplateInspector.SOURCE_SDCARD),
                        new FileInputStream(manual));
            }
            File cached = cachedTemplateFile(context);
            if (isUsableTemplate(cached)) {
                return new TemplateSelection(ApkTemplateInspector.inspect(cached, ApkTemplateInspector.SOURCE_HOST_CACHE),
                        new FileInputStream(cached));
            }
        }
        File mergedBundled = mergedBundledTemplate(context);
        if (isUsableTemplate(mergedBundled)) {
            return new TemplateSelection(ApkTemplateInspector.inspect(mergedBundled, ApkTemplateInspector.SOURCE_BUNDLED),
                    new FileInputStream(mergedBundled));
        }
        // task-116：**默认不找插件**。走到这里说明 1) /sdcard 覆盖 2) 宿主缓存 3) merged 内置 三档都不成立
        // （正常设备「不装插件」必然在前三档之一命中）。插件模板只是最后手段：仅当内置模板损坏时才会用到。
        if (!forceBundled) {
            File extracted = extractTemplateFromPlugin(context);
            if (extracted != null) {
                return new TemplateSelection(ApkTemplateInspector.inspect(extracted, ApkTemplateInspector.SOURCE_HOST_CACHE),
                        new FileInputStream(extracted));
            }
        }
        try {
            InputStream bundled = context.getAssets().open(TEMPLATE_APK_PATH);
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[65536];
            int read;
            while ((read = bundled.read(chunk)) > 0) {
                if (buffer.size() + read > 64 * 1024 * 1024) throw new IOException("内置模板超过 64MiB");
                buffer.write(chunk, 0, read);
            }
            bundled.close();
            byte[] bytes = buffer.toByteArray();
            ApkTemplateInspector.Info info = ApkTemplateInspector.inspect(bytes,
                    ApkTemplateInspector.SOURCE_BUNDLED, "assets/" + TEMPLATE_APK_PATH);
            return new TemplateSelection(info, new ByteArrayInputStream(bytes));
        } catch (IOException error) {
            throw new IOException(missingTemplateMessage(context), error);
        }
    }

    /** 只读摘要（get_status 用）；失败时返回 available=false 的结构化对象，不抛异常。 */
    public static JSONObject templateSummary(Context context, boolean forceBundled) throws org.json.JSONException {
        try (TemplateSelection selection = openTemplateSelection(context, forceBundled)) {
            return selection.info.toJson();
        } catch (Throwable error) {
            return new JSONObject()
                    .put("available", false)
                    .put("source", ApkTemplateInspector.SOURCE_NONE)
                    .put("path", "")
                    .put("sizeBytes", 0)
                    .put("sha256", "")
                    .put("engineInitSha256", "")
                    .put("engineRequireRootFix", false)
                    .put("templateStale", true)
                    .put("inspectError", error.getClass().getSimpleName() + ": " + error.getMessage())
                    .put("detail", error.getMessage() == null ? "模板不可用" : error.getMessage())
                    .put("warnings", new JSONArray().put("模板不可用，打包会失败"))
                    .put("nextActions", new JSONArray().put(missingTemplateMessage(context)));
        }
    }

    /** 打包入口：打包页与仪器测试都只认这个方法。 */
    public static InputStream openTemplateApk(Context context) throws IOException {
        TemplateSelection selection = openTemplateSelection(context, false);
        // `.4` 的坑：以为换了模板其实没换 —— 打包时把「最终用了哪份 + 有没有 require 修复」写进日志。
        try {
            Log.i(TAG, "Template selection: " + selection.info.toJson());
            if (selection.info.templateStale) {
                Log.w(TAG, "Template stale: " + selection.info.warnings + " actions=" + selection.info.nextActions);
            }
        } catch (Throwable ignored) {
        }
        return selection.input;
    }


    /**
     * 打包页进入页面时问一次：模板从哪来（source/detail 给界面展示）。
     *
     * <p>task-116：**不再是「要不要提示装插件」**——默认路径不依赖插件，插件只是最后手段；
     * 界面按 source/detail 展示来源即可，不要引导用户安装插件。</p>
     */
    public static TemplateStatus templateStatus(Context context) {
        File manual = manualTemplateFile();
        if (isUsableTemplate(manual)) {
            return new TemplateStatus(true, manual.getPath(), "手动放置的模板");
        }
        File cached = cachedTemplateFile(context);
        if (isUsableTemplate(cached)) {
            String origin = readTextQuietly(cacheVersionMarker(context));
            // task-116：宿主缓存是「内置瘦身模板 + 宿主 lib 合成」的产物（默认路径），别再说成必须来自插件。
            return new TemplateStatus(true, cached.getPath(),
                    origin.isEmpty() ? "宿主缓存（内置模板与宿主 lib 合成）" : "宿主缓存（模板来源：" + origin + "）");
        }
        if (isPluginInstalled(context)) {
            // task-116：插件档只是最后手段，不是必需（文案里不写成「需要装」）。
            return new TemplateStatus(true, pluginLabel(context), "最后手段：插件模板（仅当内置模板损坏时才会用到）");
        }
        try {
            InputStream bundled = context.getAssets().open(TEMPLATE_APK_PATH);
            bundled.close();
            return new TemplateStatus(true, "assets/" + TEMPLATE_APK_PATH, "随主程序分发（默认路径）");
        } catch (IOException ignored) {
            // task-116：内置资产都读不到 —— 给自足修法，不引导装插件。
        }
        return new TemplateStatus(false, "",
                "内置模板不可用：请重装主程序、或清掉 files/apk-builder/ 后重试、或把 template.apk 放到 /sdcard/template.apk");
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

    /** task-106：模板缓存按宿主 versionCode 分目录，升级后不会用旧模板。 */
    /** 把 assets/template.apk（瘦身、无 lib）与宿主 APK 的 excluded 条目合成完整模板。 */
    private static File mergedBundledTemplate(Context context) {
        File out = cachedTemplateFile(context);
        try {
            InputStream slim = context.getAssets().open(TEMPLATE_APK_PATH);
            File parent = out.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                slim.close();
                return null;
            }
            File tmp = new File(out.getPath() + ".tmp");
            int copied = 0;
            int added = 0;
            try (java.util.zip.ZipInputStream input = new java.util.zip.ZipInputStream(slim);
                 java.util.zip.ZipOutputStream output = new java.util.zip.ZipOutputStream(
                         new java.io.FileOutputStream(tmp))) {
                Set<String> present = new HashSet<>();
                java.util.zip.ZipEntry entry;
                byte[] buffer = new byte[8192];
                while ((entry = input.getNextEntry()) != null) {
                    if (entry.isDirectory()) continue;
                    present.add(entry.getName());
                    output.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));
                    int read;
                    while ((read = input.read(buffer)) > 0) output.write(buffer, 0, read);
                    output.closeEntry();
                    copied++;
                }
                try (java.util.zip.ZipFile host = new java.util.zip.ZipFile(
                        new File(context.getPackageCodePath()))) {
                    for (String name : readExcludedEntries(context)) {
                        if (present.contains(name)) continue;
                        java.util.zip.ZipEntry hostEntry = host.getEntry(name);
                        if (hostEntry == null || hostEntry.isDirectory()) {
                            Log.w(TAG, "excluded entry missing in host APK: " + name);
                            continue;
                        }
                        output.putNextEntry(new java.util.zip.ZipEntry(name));
                        try (InputStream source = host.getInputStream(hostEntry)) {
                            int read;
                            while ((read = source.read(buffer)) > 0) output.write(buffer, 0, read);
                        }
                        output.closeEntry();
                        added++;
                    }
                }
            }
            if (!tmp.renameTo(out)) {
                tmp.delete();
                return null;
            }
            Log.i(TAG, "C-PKG: merged slim template bytes=" + out.length()
                    + " baseEntries=" + copied + " hostLibs=" + added);
            return out;
        } catch (IOException error) {
            Log.w(TAG, "merge bundled slim template failed", error);
            return null;
        }
    }

    private static List<String> readExcludedEntries(Context context) {
        List<String> result = new ArrayList<>();
        try (InputStream input = context.getAssets().open(EXCLUDED_ZIP_ASSET)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = input.read(chunk)) > 0) buffer.write(chunk, 0, read);
            JSONObject json = new JSONObject(new String(buffer.toByteArray(), "UTF-8"));
            for (String key : new String[]{"general", "opencv", "v8"}) {
                JSONArray array = json.optJSONArray(key);
                if (array == null) continue;
                for (int i = 0; i < array.length(); i++) {
                    String name = array.optString(i, "");
                    if (!name.isEmpty()) result.add(name);
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "read " + EXCLUDED_ZIP_ASSET + " failed", error);
        }
        return result;
    }

    private static File cacheDir(Context context) {
        return new File(new File(context.getFilesDir(), CACHE_DIR_NAME),
                String.valueOf(hostVersionCode(context)));
    }

    private static int hostVersionCode(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode;
        } catch (Throwable error) {
            return 0;
        }
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

    /**
     * task-120：清掉打包模板缓存（apk-builder/versionCode/），强制下次打包重新合成。
     *
     * <p>用户拍板不要打包插件 ⇒「缺少模板」时给的自足动作就是这一步（配合重装/手动覆盖）。
     * 只删应用私有缓存，不碰 /sdcard/template.apk，也不碰工程目录。</p>
     *
     * @return 实际删除的文件数（0 表示本来就没有缓存）
     */
    public static int clearCachedTemplates(Context context) {
        File cached = cachedTemplateFile(context);
        File dir = cached == null ? null : cached.getParentFile();
        int removed = dir == null ? 0 : deleteTreeCounting(dir);
        Log.i(TAG, "template cache cleared dir=" + (dir == null ? "" : dir.getPath())
                + " files=" + removed);
        return removed;
    }

    private static int deleteTreeCounting(File file) {
        if (file == null || !file.exists()) return 0;
        int count = 0;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) count += deleteTreeCounting(child);
            }
        }
        return (file.delete() ? 1 : 0) + count;
    }

    private static String displayVersion(PackageInfo info) {
        return (info.versionName == null ? String.valueOf(info.versionCode) : info.versionName)
                + " (" + info.versionCode + ")";
    }

    /**
     * task-116：兜底错误文案改成**自足指引** —— 用户拍板「不要打包插件，就在软件里面打包」。
     *
     * <p>插件仍保留为**最后手段**（仅当内置模板损坏时才可能用到），但文案里**不得**把它写成必需；
     * 这里给的是应用内可自愈的三条：重装（内置模板随包）/清缓存/手动覆盖。</p>
     */
    private static String missingTemplateMessage(Context context) {
        StringBuilder builder = new StringBuilder();
        builder.append("找不到可用的打包模板（内置 assets/template.apk 不可用）。\n");
        builder.append(TemplateSelfCheck.checkBundled(() -> {
            try {
                return context == null ? null : context.getAssets().open(TEMPLATE_APK_PATH);
            } catch (IOException error) {
                return null;
            }
        }).userMessage());
        builder.append("说明：打包**不需要**安装任何插件；插件模板只是内置模板损坏时的最后手段。");
        return builder.toString();
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

