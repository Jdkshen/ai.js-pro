package com.jdkshen.aijspro.network;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 插件 APK 与主程序更新同目录发布（打包页要按固定文件名下载模板所在的插件），
 * 这里锁住两件事：地址文件名规则、以及插件不会被当成主程序安装包。
 */
public class PluginDownloadsTest {

    @Test
    public void resolvesPluginUrlNextToTheDefaultUpdateManifest() {
        String url = PluginDownloads.defaultSourceUrl(PluginDownloads.APK_BUILDER_PLUGIN_FILE);
        assertTrue(url, url.endsWith("/releases/latest/download/apkbuilder-plugin.apk"));
        assertTrue(url, url.startsWith(GitHubUpdateProxy.PREFIX));
    }

    @Test
    public void neverTreatsPluginApksAsAppInstallPackages() {
        String[] arm64 = {"arm64-v8a", "armeabi-v7a"};
        assertFalse(VersionService.isCompatibleApkAsset(PluginDownloads.APK_BUILDER_PLUGIN_FILE, true, arm64));
        assertFalse(VersionService.isCompatibleApkAsset("xiangqi-plugin.apk", true, arm64));
        // 正常的主程序包不受影响。
        assertTrue(VersionService.isCompatibleApkAsset("aijspro-miuix-compat-arm64-v8a.apk", true, arm64));
    }

    @Test
    public void pluginPackageNameMatchesTheModuleWeBuild() {
        assertEquals("com.jdkshen.apkbuilder.plugin", PluginDownloads.APK_BUILDER_PLUGIN_PACKAGE);
    }
}
