# APK 打包插件（apkbuilder-plugin）

把「打包运行时模板」从主程序里搬出来的独立插件 APK。装了这个插件，打包页才能打包用户脚本。

- 包名：`com.jdkshen.apkbuilder.plugin`
- 注册类：`com.jdkshen.apkbuilder.plugin.PluginRegistry`（`AndroidManifest.xml` 里的
  `org.autojs.plugin.sdk.registry` 元数据，官方 Auto.js Pro 与本仓库都按它反射加载）
- 插件对象：`com.jdkshen.apkbuilder.plugin.ApkBuilderPlugin`
- 体积：约 43MB，几乎全是 `assets/template.apk`（arm64 未签名运行时，由 `:inrt` 构建）

## 为什么做成插件

模板是一份完整的 arm64 运行时 APK（~43MB），只有要打包应用的用户才用得到。
以前它以 `assets/template.apk` 常驻主 APK，等于让**所有**用户为它付体积。
搬进插件后：主 APK 从 253MB 降到 ~141MB，需要打包的人装一次插件即可。

## 构建

```powershell
. .\tools\jdk17.ps1 | Out-Null
.\gradlew.bat :apkbuilder-plugin:assembleDebug      # 或 assembleRelease
```

构建会自动先跑 `:inrt:buildApkPlugin`（`:inrt:packageRelease` → 复制 arm64 未签名运行时
到 `src/main/assets/template.apk`），所以模板永远和当前源码同版本。
签名沿用 `AIJSPRO_*` 环境变量（与主应用、象棋插件同一套）；没配时用 debug 签名，便于真机自测。

产物：`plugins/apkbuilder-plugin/build/outputs/apk/{debug,release}/apkbuilder-plugin-{debug,release}.apk`

## 宿主怎么用它

`apps/app/src/main/java/com/jdkshen/aijspro/build/ApkBuilderPluginHelper.java` 按以下顺序找模板：

1. `/sdcard/template.apk` —— 手动覆盖（调试、仪器测试用）
2. `files/apk-builder/template.apk` —— 从插件落地过的副本（旁边的 `template.plugin-version`
   记录来源版本，升级插件后会重新落地）
3. 已安装的本插件 —— 反射调用 `ApkBuilderPlugin.ensureTemplate(File)`，把模板落到上面的缓存
4. 主程序自带的 `assets/template.apk` —— 老版本/自编包可能还有，有就用

都找不到时抛带操作指引的 `IOException`，打包页把它显示成「缺少打包运行时模板」的提示卡片，
并提供「下载并安装打包插件」（从更新源下 `apkbuilder-plugin.apk` 后交给系统安装）。

## 脚本侧入口

打包页走 Java 反射，脚本侧走 `assets/scripts/index.js`：

```js
var builder = $plugins.load("com.jdkshen.apkbuilder.plugin");
builder.capabilities.templateReady;              // 模板是否可用
builder.ensureTemplate("/sdcard/模板缓存");       // 落地成真实文件，返回路径
```

## 发布

- 本地自建更新源：`tools/dev-update.ps1` 会把插件 APK 作为**附件**（不进 `update.json` 的
  `assets`，否则会被当成主程序安装包）放到更新源目录，文件名固定为 `apkbuilder-plugin.apk`。
  ⚠️ 只挑**已签名**的产物：本地没配 `AIJSPRO_*` 时 `assembleRelease` 出的是
  `*-release-unsigned.apk`，发出去手机装不上（打包页会报下载失败/解析包错误），
  脚本会跳过它改用 debug 签名产物。
- GitHub Release：`.github/workflows/android.yml` 的 release 任务构建 `:apkbuilder-plugin:assembleRelease`
  并把它作为 `apkbuilder-plugin.apk` 资产上传；App 侧按
  `releases/latest/download/apkbuilder-plugin.apk`（经内置加速前缀）下载。
  （该任务配置了发布密钥，所以上传的是已签名产物。）

## 真机验证记录（MI 8 / Android 15）

| 场景 | 结果 |
| --- | --- |
| 未装插件、无 `/sdcard/template.apk` | 打包页提示缺失；`openTemplateApk` 抛可操作异常（`ApkBuilderTemplateAvailabilityTest` 通过） |
| 装插件后打包「dsh 手机客户端」示例 | 通过（`DshClientSamplePackagingTest`，真实 ApkBuilder 全链路） |
| 打包加密回归 19 项 | 18 通过；1 项失败与本插件无关（`IMAGE_MODULE_LIBS` 未包含新模板里的 `libopencv_features.so`，属于仓库里既有的模板/清单不同步） |
| 落地结果 | `files/apk-builder/template.apk` 44,842,488 字节，来源标记 `1.0.0 (1)` |
