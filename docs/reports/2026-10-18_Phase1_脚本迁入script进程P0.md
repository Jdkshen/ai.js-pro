# Phase 1 P0：脚本全面迁入 :script 进程（dev-954/955/956）

> 目标：普通脚本引擎迁到独立 `:script` 进程，主进程只做 UI/IPC；与 Pro 打包运行时架构对齐（主进程 + `:script`）。

## 1. 架构

```
主进程 com.jdkshen.aijspro          :script 进程
  Flutter 玻璃界面 / 任务页 / 悬浮条   ScriptHostService（Service+Messenger）
  PocScriptHost（bind/linkToDeath）    引擎（Rhino/QuickJS/Node）/ 主线程 UI 宿主
  PocRuntime（统一路由/token 合并）    ScriptHostStaging（项目才复制到私有目录）
                                      GlobalConsole 日志（文件共享）
```

新增/修改（flutterpoc 下）：

- 新增 `scriptproc/ScriptHostService.java`：协议与 Phase0 一致（RUN=1 / STOP=2 / 100/101/102），服务所有脚本；普通脚本后台线程，`"ui";` 脚本走 `ScriptEngineService.executeUiOnMainThread`（主线程 Looper 宿主）。
- 新增 `scriptproc/ScriptHostStaging.java`：只有「项目」（有 project.json，或 main.js + lib/.so/.dex）才暂存到 `files/script-staging/<hash>`；单文件脚本原路径执行（修复大目录 256MB 误报）。
- 新增 `scriptproc/PocScriptHost.java`：主进程客户端（bind/重连/崩溃恢复/任务列表，token `script:<id>`，兼容 `native:<id>`）。
- 修改 `PocRuntime.java`：统一路由 auto→主进程（AIDL 未落地前）、项目且 native→`:native`、其余→`:script`；单文件不做 native 递归检测；`script:` 102「暂存失败」自动回退主进程。
- 修改 `PocFloatingBar.java`：悬浮条 ▶/■/⏹ 走 `PocRuntime` 统一入口（脚本在 :script，条在主进程）。
- 修改 `AndroidManifest.xml`（flutterpoc）：注册 `ScriptHostService`，`android:process=":script"`。
- `App.kt`：隔离进程判断泛化到 `:script`（跳过定时任务/广播/示例同步等主进程职责）。
- AIDL 骨架：`src/main/aidl/.../IScriptHost.aidl`、`IAutomationHost.aidl`；`build.gradle` 显式 `aidl true`（供 P1 自动化代理用）。

## 2. 真机验证（K40，dev-955）

- 运行普通单文件脚本 `/sdcard/脚本/__phase1_probe.js`：自动进入 **`:script` 进程**（`Start proc ... aijspro:script for service ScriptHostService`），日志 `单文件脚本不暂存，按原路径执行`，控制台 start/done 都在 :script pid 输出；
- 任务管理显示 `__phase1_probe / 脚本进程 / 路径`，点 ✕ 能停止（102「脚本已停止」），结束后列表自动清空；
- 主进程界面全程正常，任务列表/停止/日志均走 IPC；
- `"auto";` 脚本在 AIDL 自动化代理落地前仍留主进程（保证无障碍可用）。

## 3. 已知限制（Phase1 P0）

- 普通脚本运行时若调用 auto/click/selector（未声明 `"auto";`）仍会失败：无障碍服务在主进程，等 P1 AIDL 自动化代理；
- 定时任务/音量键停止/编辑器仍走主进程旧逻辑，未迁入 `:script`；
- staging 语义：项目脚本在私有副本运行，产物不回写原目录（与 Pro 打包版一致）；
- UI 模式脚本在 :script 用主线程 Looper 宿主，阻塞主线程时 STOP 无法及时处理（Pro/Activity 模型同样）；
- 多进程 SharedPreferences 未统一（见 `2026-10-18_多进程Pref与设置同步评估.md`），104 SET_SETTING/settings 快照延到第二批。

## 4. 发布

- dev-956（本版）：APK 265,588,393 B；K40 与小米 8 已安装。

## 5. 下一步（Phase1 P1）

1. 实现 `IAutomationHost`：无障碍/Automator/选择器/截屏代理（主进程持有 AccessibilityService，`:script` 通过 AIDL 调用）；
2. `"auto";` 脚本放开进 `:script`；
3. 定时任务、音量键停止、悬浮窗通知等辅助服务迁入 `:script`；
4. 可选：104 SET_SETTING/settings 快照；editor/log pages 后续 P2。
