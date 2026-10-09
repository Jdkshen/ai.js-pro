package com.jdkshen.aijspro.network;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.text.TextUtils;

import java.io.File;
import java.io.IOException;

/**
 * 「插件 APK」的下载地址解析：插件和主程序一起发布（更新源目录 / GitHub Release 资产），
 * 打包页只按文件名向同一个源要。
 *
 * <p>规则与主程序自身的更新保持一致：
 * <ul>
 *     <li>用户配了自建更新源 → 相对 {@code update.json} 所在目录解析文件名；</li>
 *     <li>用内置源（GitHub 加速）→ {@code releases/latest/download/<文件名>}，
 *         失败时由 {@link GitHubUpdateProxy#downloadWithFallback} 直连 GitHub。</li>
 * </ul>
 */
public final class PluginDownloads {

    /** APK 打包插件（模板随它分发），发布脚本会把它的 APK 放到更新源目录下。 */
    public static final String APK_BUILDER_PLUGIN_PACKAGE = "com.jdkshen.apkbuilder.plugin";
    public static final String APK_BUILDER_PLUGIN_FILE = "apkbuilder-plugin.apk";

    private PluginDownloads() {
    }

    /** 解析插件 APK 的下载地址（自建源与内置源都能用）。 */
    public static String pluginUrl(Context context, String fileName) throws IOException {
        String source = VersionService.updateSourceUrl(context);
        if (!TextUtils.isEmpty(source) && !GitHubUpdateProxy.isDefaultProxySource(source)) {
            return VersionService.resolveUrl(source, fileName);
        }
        // 内置源是「加速前缀 + releases/latest/download/<文件名>」：同一个前缀换文件名即可。
        return defaultSourceUrl(fileName);
    }

    /** 内置（GitHub 加速）源里某个文件的地址；与 {@link GitHubUpdateProxy#UPDATE_SOURCE} 同目录。 */
    static String defaultSourceUrl(String fileName) {
        String source = GitHubUpdateProxy.UPDATE_SOURCE;
        int slash = source.lastIndexOf('/');
        return slash < 0 ? fileName : source.substring(0, slash + 1) + fileName;
    }

    /**
     * 校验刚下载的插件 APK 是不是我们要的那个包：安装前拦一次「下到 HTML 错误页」的情况。
     *
     * @return 校验通过返回 true；包名不符返回 false
     */
    public static boolean isExpectedPackage(Context context, File apk, String expectedPackage) {
        PackageManager packageManager = context.getPackageManager();
        PackageInfo info = packageManager.getPackageArchiveInfo(apk.getPath(), 0);
        return info != null && expectedPackage.equals(info.packageName);
    }
}