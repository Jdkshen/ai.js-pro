# Materi-UI 独立 APK 工程

来源：`6.Materi-ui.zip` 的 `AutoJsPro-GoogleMaterial-main`。已适配 AI.js Pro 打包运行时缺失的组件，并能打出可安装的独立 APK。

- 包名：`com.materi.ui`
- 版本：1.0.0 / versionCode 1
- 入口：`main-ui.js`（内置 `ui.useAndroidResources()`、`DocumentFile` import 容错、`ui.R` 图标映射）
- 资源：从原 Auto.js Pro APK 提取 `res/drawable-xhdpi` 图标；`project.json` 增加 `androidResources.resDir=res`
- 运行时缺少 `androidx.swiperefreshlayout`，布局改为 `FrameLayout id="swipe"`，JS 调用加了能力判断
- 打包运行时缺少部分 `$settings` 项，`Cacheinit` 增加容错

构建：在手机 `/storage/emulated/0/脚本/Materi-Original` 运行 `build-apk.js`，使用 `ApkBuilderPluginHelper` + `ApkBuilder` + 打包模板，`encryptLevel=0`。

产物：`C:\Users\18101\Downloads\Materi-UI-original-1.0.0.apk`。
