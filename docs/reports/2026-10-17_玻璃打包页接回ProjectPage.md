# 玻璃打包页接回：ProjectPage + PocPackaging 桥（dev-930 / dev-931）

> 依据：`工程/output/ai-js-pro-refactor-20261017/打包界面代码定位-20261017.md` 第 7 节「把玻璃打包页接回来的步骤」。
> 结论：**打包界面已改回玻璃自绘的 `ProjectPage`**，桥后端 `PocPackaging` 全部接好；项目 / 单文件 / 示例项目三种入口都走玻璃页，打包在本机后台执行、2s 轮询进度。

## 1. 改动文件

| 文件 | 改动 |
| --- | --- |
| `apps/app/src/flutterpoc/java/.../PocPackaging.java`（新增） | 玻璃打包页后端：`projectLoad`/`projectSave`/`projectBuild`/`projectBuildStatus`、`projectSigningList`/`projectSigningAliases`/`projectSigningCreate`、`prepareSingleFile`、`projectBuildInstall`/`projectBuildShare`、真实 `nativePackagingRequest` |
| `PocBridge.java` / `PocIntegration.java` | 新方法分发；`importProjectIcon`/`importProjectSigning` 走系统选择器异步通道（新增请求码 8804/8805） |
| `browser.dart` | `projectSettings` / `singleFileBuild` / `sampleBuild` / `sampleBuildProject` 改为「准备项目 → push `ProjectPage`」；project.json 文件菜单自动取父目录；新增 `openProjectPage` / `openPreparedProject` |
| `main.dart` | 后台打包请求：`nativePackagingRequest` → `prepareSingleFile` → 打开 `ProjectPage` |
| `project_page.dart` | 未改（v181 时代整页直接启用） |

## 2. 桥后端设计（PocPackaging）

- **配置**：读/写项目 `project.json`（保留未知字段），返回 `{path, config, revision, installation…}`，revision 用文件 MD5，用于页面保存/打包的乐观锁。
- **构建**：`ApkBuilderPluginHelper.openTemplateApk` + `ApkBuilder(...).prepare().withConfig(AppConfig.fromProjectConfig()).build().sign().cleanWorkspace()`；权限按模板默认 16 条做 add/remove diff，启动申请权限取 `launchConfig.requestListOnStartup`。
- **签名**：`.keyStore/` 目录复用原生页的 `SigningOptions`（列表/口令记账/新密钥库命名），新建用 `KeyStoreGenerator`；项目 `signingConfig.keystore/alias` 存在时加载 `SigningKey` 并 `setSigner`+`setSigningCertificateFingerprint`，否则走「本机自动身份」。
- **任务**：单线程执行器，任务状态 `{id,status,projectPath,stage,events,path,size,sha256,versionCode,encryptLevel,packageName,abis,error}` 同时落 `files/flutter-project-builds/<id>.json`，进程重启后仍能读回最近任务。
- **产物**：`/sdcard/脚本/构建/<应用名>_v<版本>.apk`；安装/分享复用 `IntentUtil` 与 `AppFileProvider`。
- **单文件/示例项目**：`prepareSingleFile` 把 .js 或 `files/sample` 下的示例项目复制到 `files/flutter-single-file/<token>` 再打开玻璃页（示例语料只读，不写坏内置示例）。

## 3. 入口

| 入口 | 行为 |
| --- | --- |
| 脚本页目录工具栏「项目配置与打包」（目录有 project.json） | 直接打开玻璃 `ProjectPage` |
| 文件菜单「打包单文件」 | `prepareSingleFile` → 玻璃页（临时项目） |
| project.json 文件菜单「项目配置与打包」 | 自动取父目录 → 玻璃页 |
| 示例页「打包」/「打包（项目）」 | .js 复制成临时项目 / 项目目录整棵复制 → 玻璃页 |
| 后台打包请求 | `nativePackagingRequest` → 准备项目 → 玻璃页 |
| 原生 Miuix 打包页 | 保留为回退：编辑器菜单/资源管理器工具栏等原有入口仍进 `MiuixBuildActivity` |

## 4. K40 验收（dev-930/931）

- **project 入口**：`/sdcard/脚本/pr_proj` → 玻璃页显示「使用Android原生界面 / com.example.ui.android / 1.0.0 / main.js / Rhino 引擎 · 使用内置签名」，点「保存并打包」→ 本地打包卡片出现「正在打包 · 签名」→ 12s 后「APK 已生成」。
- 产物：`/sdcard/脚本/构建/使用Android原生界面_v1.0.0.apk`，**43,762,772 字节**，架构 arm64-v8a、armeabi-v7a，SHA-256 `8b60835cd946496b352a0a89f8a590dc97aef0727c32681c71977e4d1d1e3af2c`；页面显示 复制路径 / 分享 APK / 安装 APK 三个操作与打包日志。
- **单文件入口**：`00-codex-ui/main.js` → 文件菜单「打包单文件」→ 玻璃页显示临时项目（`/data/.../flutter-single-file/...`，应用名 main、包名 com.example.script…、main.js）。
- **示例项目入口**：示例 → Node 引擎 → Node.js → 图片与颜色处理 →「打包（项目）」→ 玻璃页显示复制出来的项目（ImageProc / org.example.imageproc / 特征匹配(全分辨率找图).js）。
- 打包期间按钮置灰并提示「打包已在本机后台开始，离开页面后仍会继续」；完成后恢复可编辑。

## 5. 已知限制 / 下一轮

- 玻璃页「插件」「已安装插件」列表返回空（PocPackaging 未接插件 SDK 清单），后续可从插件页数据补。
- 权限默认集：玻璃页用模板 16 条（与原生页一致）；页面里额外勾选的权限按 diff 加进 APK。
- 签名三种模式里本轮只实测「自动（本机内置身份）」；「选择已有 / 新建签名 / 导入图标」的桥已实现，但未在页面走完整流程。
- 打包任务在应用进程内执行，杀进程会中断（原生页同样如此）；`nativePackagingRequest` 目前没有原生写入方，等编辑器菜单切玻璃页时再补。
- 原生 Miuix 打包页仍然保留，属于回退入口，不算删除。

> 验证截图：`output/flutter-poc/glass-build-930.png`（页面加载）、`glass-build-progress.png`（打包中）、`glass-build-progress2.png`（成功+产物信息）、`glass-build-single-931.png`、`glass-build-sample-project-931.png`。
