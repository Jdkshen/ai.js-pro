// @engine quickjs
"use strict";
// APK 打包插件的脚本入口。
//
// 打包页（Java/Kotlin）不用这个文件——它直接反射 ApkBuilderPlugin.ensureTemplate()。
// 这里只是给脚本侧留同一份能力：想自己触发打包、或想知道模板在哪时用得上。
//
//   var builder = $plugins.load("com.jdkshen.apkbuilder.plugin");
//   var template = builder.ensureTemplate("/sdcard/模板缓存");   // 落地成真实文件
//   // 或者只问一句：模板在不在、多大
//   builder.capabilities.templateReady

module.exports = function (plugin) {
    var sdk = (plugin && typeof plugin.unwrap === "function") ? plugin.unwrap() : plugin;
    if (!sdk) {
        throw new Error("插件对象为空：宿主没有把插件 SDK 实例传进来");
    }
    var capabilities = decodeJson(typeof sdk.capabilities === "function" ? sdk.capabilities() : null) || {};

    return {
        /** 插件 APK 版本。 */
        version: capabilities.versionName == null ? "" : String(capabilities.versionName),
        /** {versionName, versionCode, templateAsset, templateSize, templateReady}。 */
        capabilities: capabilities,
        /** 插件里模板资产的名字（assets/<name>）。 */
        templateAsset: "template.apk",
        /**
         * 把运行时模板写到 hostDir 下，返回落地文件路径。
         * @param hostDir 宿主可写目录（插件写不了自己的私有目录）
         */
        ensureTemplate: function (hostDir) {
            return String(sdk.ensureTemplatePath(String(hostDir)));
        },
        /** 插件里模板的字节数（-1 = 读不到）。 */
        templateSize: function () {
            return Number(sdk.templateSize());
        }
    };
};

function decodeJson(text) {
    try {
        return text == null ? null : JSON.parse(String(text));
    } catch (error) {
        return null;
    }
}
