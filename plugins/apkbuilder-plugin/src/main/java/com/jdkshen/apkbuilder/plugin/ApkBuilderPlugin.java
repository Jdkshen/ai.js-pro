package com.jdkshen.apkbuilder.plugin;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 插件对象：把「打包运行时模板」交给宿主。
 *
 * <p>模板是一份未签名的 arm64 运行时 APK（{@code assets/template.apk}，由 {@code :inrt} 构建）。
 * {@code ApkBuilder} 会重写它再签名，所以宿主需要的是一份**真实文件**：
 * {@link #ensureTemplate(File)} 负责把插件里的模板落到宿主可写目录（插件的 Java 代码跑在宿主进程里，
 * 用的是宿主的 uid，写不了插件自己的私有目录）。
 *
 * <p>方法一律返回字符串/文件等 Java 基础类型，宿主只做反射调用，不需要任何共享 SDK 依赖——
 * 这也是它能同时被官方 Auto.js Pro 与 AI.js Pro 装载的原因。
 */
public final class ApkBuilderPlugin {

    /** 插件内脚本目录（宿主会整棵拷到 cache/plugin-scripts/<包名>）。 */
    static final String ASSETS_SCRIPT_DIR = "scripts";
    /** 模板在插件资产里的路径。 */
    static final String TEMPLATE_ASSET = "template.apk";
    /** 模板小于这个大小一律当成截断/坏文件（真实模板 ~43MB）。 */
    static final long TEMPLATE_MIN_BYTES = 20L * 1024 * 1024;

    @SuppressWarnings("unused")
    private final Context mHostContext;
    private final Context mPluginContext;
    @SuppressWarnings("unused")
    private final Object mRuntime;
    @SuppressWarnings("unused")
    private final Object mScope;

    ApkBuilderPlugin(Context hostContext, Context pluginContext, Object runtime, Object scope) {
        mHostContext = hostContext;
        mPluginContext = pluginContext;
        mRuntime = runtime;
        mScope = scope;
    }

    // ---------------------------------------------------------------- 宿主（脚本侧）约定的成员

    /** 宿主侧 {@code plugin.getVersion()}。 */
    public String getVersion() {
        return versionName();
    }

    /** 宿主侧 {@code plugin.getAssetsScriptDir()}：脚本目录在 APK 里的相对路径。 */
    public String getAssetsScriptDir() {
        return ASSETS_SCRIPT_DIR;
    }

    // ---------------------------------------------------------------- 打包页（Java 侧）用的成员

    /**
     * 把插件里的运行时模板落到 {@code targetDir/template.apk} 并返回它。
     *
     * <p>已存在且大小一致时直接复用（宿主会给每个插件版本一份缓存，升级插件后自动重取）。
     * 写入走「临时文件 + 改名」，中途失败不会留下半截文件让 {@code ApkBuilder} 读到。
     *
     * @param targetDir 宿主可写目录（通常是宿主 files 下的缓存目录）
     */
    public File ensureTemplate(File targetDir) throws IOException {
        if (targetDir == null) {
            throw new IOException("目标目录为空");
        }
        long expected = templateSize();
        if (expected < TEMPLATE_MIN_BYTES) {
            throw new IOException("插件里的 template.apk 大小异常：" + expected + " 字节");
        }
        File target = new File(targetDir, TEMPLATE_ASSET);
        if (target.isFile() && target.length() == expected) {
            return target;
        }
        if (!targetDir.isDirectory() && !targetDir.mkdirs() && !targetDir.isDirectory()) {
            throw new IOException("无法创建目录：" + targetDir);
        }
        File temporary = new File(targetDir, TEMPLATE_ASSET + ".tmp");
        try {
            copyAsset(TEMPLATE_ASSET, temporary);
            if (temporary.length() != expected) {
                throw new IOException("模板拷贝不完整：" + temporary.length() + " / " + expected + " 字节");
            }
            if (target.isFile() && !target.delete()) {
                throw new IOException("无法覆盖旧模板：" + target);
            }
            if (!temporary.renameTo(target)) {
                throw new IOException("无法重命名模板：" + temporary + " -> " + target);
            }
        } finally {
            if (temporary.isFile()) {
                //noinspection ResultOfMethodCallIgnored
                temporary.delete();
            }
        }
        return target;
    }

    /** 直接读插件里的模板（宿主只想流式转发时用；打包页要文件，用 {@link #ensureTemplate}）。 */
    public InputStream openTemplate() throws IOException {
        return assets().open(TEMPLATE_ASSET, AssetManager.ACCESS_STREAMING);
    }

    /**
     * {@link #ensureTemplate(File)} 的脚本友好版本：参数与返回值都是字符串，
     * QuickJS 与 Rhino 都不用碰 Java 类型。
     */
    public String ensureTemplatePath(String targetDir) throws IOException {
        return ensureTemplate(new File(String.valueOf(targetDir))).getAbsolutePath();
    }

    /** 插件里模板的字节数（取自资产表；资产被压缩时退回流式统计，不真读进内存）。 */
    public long templateSize() {
        try {
            return assets().openFd(TEMPLATE_ASSET).getLength();
        } catch (IOException e) {
            long total = 0;
            byte[] buffer = new byte[64 * 1024];
            try (InputStream input = assets().open(TEMPLATE_ASSET, AssetManager.ACCESS_STREAMING)) {
                int read;
                while ((read = input.read(buffer)) > 0) {
                    total += read;
                }
            } catch (IOException ignored) {
                return -1;
            }
            return total;
        }
    }

    /** 能力清单：脚本侧用来给用户报「为什么不能用同一份模板」。 */
    public String capabilities() throws JSONException {
        JSONObject result = new JSONObject();
        result.put("versionName", versionName());
        result.put("versionCode", versionCode());
        result.put("assetsScriptDir", ASSETS_SCRIPT_DIR);
        result.put("templateAsset", TEMPLATE_ASSET);
        result.put("templateSize", templateSize());
        result.put("templateReady", templateSize() >= TEMPLATE_MIN_BYTES);
        return result.toString();
    }

    // ---------------------------------------------------------------- 内部

    private AssetManager assets() {
        return mPluginContext.getAssets();
    }

    private void copyAsset(String name, File target) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        try (InputStream input = assets().open(name, AssetManager.ACCESS_STREAMING);
             OutputStream output = new FileOutputStream(target)) {
            int read;
            while ((read = input.read(buffer)) > 0) {
                output.write(buffer, 0, read);
            }
            output.flush();
        }
    }

    private String versionName() {
        PackageInfo info = packageInfo();
        return info == null || info.versionName == null ? "" : info.versionName;
    }

    private int versionCode() {
        PackageInfo info = packageInfo();
        return info == null ? 0 : info.versionCode;
    }

    private PackageInfo packageInfo() {
        try {
            return mPluginContext.getPackageManager().getPackageInfo(mPluginContext.getPackageName(), 0);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }
}
