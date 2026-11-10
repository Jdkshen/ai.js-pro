package com.jdkshen.aijspro.mcp;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * task-119：用户可见文案里**不得再要求/引导安装打包插件**（用户已拍板「不要插件，就在软件里打包」）。
 *
 * <p>入口（本任务只改 res，入口代码不在写作用域 ⇒ 记录在此供复核）：</p>
 * <ul>
 *   <li>MiuixBuildActivity.kt:941/943 ——「缺少打包运行时模板」区块（用 missing / missing_detail）</li>
 *   <li>MiuixBuildActivity.kt:969 —— 一个按钮（旧文案 text_install_packaging_plugin「下载并安装打包插件」）</li>
 *   <li>MiuixBuildActivity.kt:794/808/825/832 —— 失败消息（missing_detail / download_failed）</li>
 * </ul>
 *
 * <p>本测试钉：① 中文与英文 strings 都不含「安装打包插件 / 立即下载 / Download and install the packaging plugin」
 * 这类引导；② 原有键仍存在（代码不破）；③ 自足口径的三条路径（重装/升级主程序、清 files/apk-builder/、
 * 手动放 /sdcard/template.apk）至少出现在 missing_detail 里。</p>
 */
public class PackagingPluginGuidancePinTest {

    private static String read(String relative) throws Exception {
        Path path = Paths.get(relative);
        if (!Files.exists(path)) path = Paths.get("apps/app/" + relative);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    @Test
    public void noGuidanceToInstallAPackagingPlugin() throws Exception {
        String[] files = {"src/main/res/values/strings.xml", "src/main/res/values-en/strings.xml"};
        String[] forbidden = {
                "安装打包插件", "下载并安装", "是否立即下载",
                "Download and install the packaging plugin",
                "apkbuilder plugin", "APK 打包插件", "APK packaging plugin",
        };
        for (String file : files) {
            String text = read(file);
            for (String bad : forbidden) {
                assertFalse(file + " 不应再出现：" + bad, text.contains(bad));
            }
        }
    }

    @Test
    public void keysStillExistSoCodeCompiles() throws Exception {
        String zh = read("src/main/res/values/strings.xml");
        String en = read("src/main/res/values-en/strings.xml");
        for (String key : new String[]{"text_packaging_plugin_missing", "text_packaging_plugin_missing_detail",
                "text_install_packaging_plugin", "text_packaging_plugin_download_failed",
                "no_apk_builder_plugin", "text_apk_builder_plugin_unavailable"}) {
            assertTrue("中文缺键：" + key, zh.contains("name=\"" + key + "\""));
        }
        // 英文侧只有其中 4 个键（后两个从来只在中文里），按实际存在性钉
        for (String key : new String[]{"text_packaging_plugin_missing", "text_packaging_plugin_missing_detail",
                "text_install_packaging_plugin", "text_packaging_plugin_download_failed"}) {
            assertTrue("英文缺键：" + key, en.contains("name=\"" + key + "\""));
        }
    }

    @Test
    public void missingDetailCarriesTheSelfSufficientPaths() throws Exception {
        String zh = read("src/main/res/values/strings.xml");
        assertTrue("要说清主程序自带模板", zh.contains("主程序已自带"));
        assertTrue("要给「重装或升级主程序」", zh.contains("重装或升级主程序"));
        assertTrue("要给「清 files/apk-builder/」", zh.contains("files/apk-builder/"));
        assertTrue("要给「手动放 /sdcard/template.apk」", zh.contains("/sdcard/template.apk"));
        assertTrue("要说明无需插件", zh.contains("无需任何插件"));
    }
}
