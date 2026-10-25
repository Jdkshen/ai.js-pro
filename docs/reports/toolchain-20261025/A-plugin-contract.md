# 工具链 A/E 首轮：插件骨架、共享契约与宿主读取

日期：2026-10-25。状态：**A 骨架与 E 读取入口已实现并通过本地构建；编译器 B/C/D 仍在真机预研。**

## 已实现

### A 可选插件骨架

新增 `plugins/buildtools-plugin`（Android application，无界面，arm64-v8a）：

- `BuildToolsPlugin`：`org.autojs.plugin.sdk.registry` 反射入口；读取并校验 assets 描述后返回 JSON；
- `toolchain/core` 共享模型：`ToolchainDescriptor`、`ComponentSpec`、`CapabilityState`、`ResolvedInvocation`、`BuildArtifact`、`Diagnostics`、`ComponentStore`；
- `assets/buildtools/descriptor.json`：schemaVersion=1、id/版本/ABI/minApi/provider/license、空组件列表、能力状态：
  - D8/Smali：`RUNNABLE`；
  - Java 源码 / C/C++ / Android APK：`NOT_INSTALLED`，不把 PATH 命中或未装组件当作可用。
- `project-versions.json` 新增 `buildToolsPluginVersionCode/Name=1/0.1.0`；`settings.gradle` 注册模块。
- 组件描述只放元数据，下载/安装/校验留给后续组件管理；没有把大二进制放进 Git。

### E 宿主读取入口

`get_build_tools` 返回新增 `toolchain` 字段：

- 未安装插件：`installed=false, error=...`；
- 已安装：通过 PackageManager 读取插件 assets 的 descriptor（不执行插件代码），返回 `installed=true + descriptor`；
- 原有 commands/builtIn/note 字段保持不变，`found=true` 仍不等于 `verified=true`。

## 构建与验证

- `:buildtools-plugin:testDebugUnitTest :buildtools-plugin:assembleDebug` **BUILD SUCCESSFUL**；未安装/已安装状态都可区分。
- `:app:compileMiuixCompatDebugKotlin` **BUILD SUCCESSFUL**（宿主读取代码可编译）。
- 插件 debug APK：`plugins/buildtools-plugin/build/outputs/apk/debug/buildtools-plugin-debug.apk`，约 18KB，未签入 Git。
- 未宣称 Java/C/C++/APK 任一编译能力已验收；未安装宿主新 APK，未发布。

## B/C/D 真机预研结果（同日）

- **B Java 源码**：ECJ 3.33.0 在 MI8 应用 UID 跑通，多文件 Java→class→JAR→D8→dex→app_process 运行，输出 `JAVA_SOURCE_DEX_OK 你好，手机编译！ message=42`。仍需 android.jar 27MB bootclasspath、SourceVersion stub；app_process 的 dex 必须位于应用 UID 只读路径（/sdcard/可写父目录 exit 134）。插件化建议专用 :build 进程/helper APK；报告 `B-java.md`。
- **C 原生**：Termux aarch64 clang/lld/sysroot 在 MI8 应用 UID 跑通，C `C_OK 42`、C++ STL `CPP_OK STL sum=15 size=5`，LOAD 对齐 0x4000；payload 145.7MB、解包 524.7MiB，只能作为可选大组件。targetSdk>=29 的 app-private exec 限制需单独方案；报告 `C-native.md`。
- **D APK**：复用宿主纯 Java 打包/签名类和内置 D8，手机端产出 304,178B 可安装 APK（独立包名），但无 aapt2/zipalign，最小 APK 启动仍因模板 provider 缺类失败；报告 `D-android.md`。
- 三项都不是“全部完成”：B 未接插件/未验证 Java9+；C 未验证真实 16KB 页和 targetSdk>=29；D 未做资源编译/启动/zipalign/v3。

## 边界与下一步

- 组件下载、安装、回滚、空间/网络/断点、SHA 校验尚未实现；当前只有描述与哈希校验 helper。
- 宿主只在已安装插件时读取描述，不自动安装插件；插件未安装时明确返回缺什么。
- B/C/D 真机预研已全部返回（见上）；下一轮由主控决定先做 Java helper/组件安装，还是先做 native 组件安装与 APK 资源工具链。不要在插件 APK 里直接塞 145MB 原生工具链或 27MB android.jar。
- 共享文件（settings.gradle/project-versions/宿主 MCP）由 Lead 独占；B/C/D 只写各自报告。
