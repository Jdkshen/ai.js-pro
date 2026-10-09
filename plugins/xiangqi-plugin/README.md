# 象棋引擎插件（Auto.js 插件 APK）

`com.jdkshen.xiangqi.plugin`：把[象棋自动走棋](<../../../samples/象棋自动走棋/项目结构.md>)的
识别 / 棋规 / 算棋装成可独立安装的 Auto.js 插件，自带 Pikafish 原生引擎与 NNUE 权重，
宿主脚本 `$plugins.load("com.jdkshen.xiangqi.plugin")` 即可使用。

- 构建、安装、API、限制与验证记录：**[docs/guides/象棋插件_打包与使用.md](../../docs/guides/象棋插件_打包与使用.md)**
- 目录：
  - `src/main/assets/scripts/`：插件入口 `index.js` + 自带引擎门面 `engine.js` + 工程库加载 `library.js`
    （工程代码/模板由 `build.gradle` 的 `stagePluginScripts` 从象棋工程拷进 `assets/scripts/`）
  - `src/main/java/com/jdkshen/xiangqi/plugin/`：注册类、插件 SDK、JNI 桥
  - `src/main/cpp/`：`libxiangqi_engine.so` 的 CMake 与构建脚本（与宿主 `libpikafish.so` 同一份 JNI 胶水）
  - `tools/check-plugin-api.js`：离线自检（`node tools/check-plugin-api.js`）
