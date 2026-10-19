# Node24 第三引擎接入与 Auto.js Pro 示例集实测（2026-10-17）

> 本文记录把"Auto.js Pro 引擎升级实验"的成果按本仓库源码重构的落地情况：新增 Node 24.22.0 作为第三个 JS 引擎、导入 Auto.js Pro 完整内置示例集、在 K40 上完成首轮全量实测。
> 工程外的工作目录（实验包、驱动脚本、报告、回滚包）：`<workspace>/工程/output/ai-js-pro-refactor-20261017/`。

## 1. 背景与口径

- 基座：本仓库完整源码（Gradle 8.9 / AGP 8.6.1 / JDK 17 / Miuix 界面），原有 Rhino + QuickJS 双引擎、MCP、编辑器、打包等能力保持不变。
- 目标：让本仓库产出"源码可编、三引擎、兼容 Auto.js Pro 内置示例"的新 APK，替换掉"在原厂 APK 上打补丁"的旧路线。
- 结论：**三引擎骨架已成立并在 K40 真机验证**；Auto.js Pro 403 个示例已完成首轮全量实测（235 个实际执行、81 成功），剩余工作见第 7 节。

## 2. 本仓库改动清单

### 2.1 Node24 运行时与服务（modules/engine）

| 文件 | 说明 |
| --- | --- |
| `modules/engine/src/main/java/org/autojs/source/engine/Node24Engine.java` | JNI 门面（nativeCreate/Execute/ExecuteWithHost/Cancel/Close），`Host.invoke(String)` 为宿主回调 |
| `.../org/autojs/source/engine/android/Node24Service.java` | `:node24` 内非导出单实例服务；Messenger 只认同 UID；单活跃任务；dup2 捕获 stdout/stderr（≤64KiB） |
| `.../android/Node24Libraries.java` | 运行时装载：拒绝非 `:node24` 进程与已载旧 libv8/libnode 的进程；按 `runtime.json` 哈希把 libc++/libnode/桥提取到 `getDir("node24-runtime")`，文件锁 + 只读 + 校验后 `System.load` |
| `.../android/Node24Client.java` | 主进程 Binder 客户端：`evaluate({id,source,filename,timeoutMillis})` → 结果 JSON（status/logs/exitCode/pid/uid…） |
| `.../android/SourceEngineBootstrap.java` | `/proc/self/cmdline` 判定是否 `<包名>:node24` |
| `modules/engine/src/main/assets/source-engine-upgrade/node24/arm64-v8a/` | `libnode.so`(97,079,800B, sha `50cfa7b0…`)、`libc++_shared.so`(1,822,720B)、`libajsnode24bridge.so`(72,088B, sha `9900fe76…`)、`runtime.json`（三库哈希 pin） |

### 2.2 引擎注册与选择

| 文件 | 改动 |
| --- | --- |
| `.../engine/Node24JavaScriptEngine.java`（新增） | `extends JavaScriptEngine`：`init()` 建客户端；`doExecution()` 组装请求调 `:node24`、把捕获日志回显到运行控制台、取消/失败映射脚本异常；`forceStop()`/`destroy()` 收尾 |
| `.../script/JavaScriptSource.java` | 新增 `ENGINE_NODE24`；识别 `// @engine node24`、`// @engine node`；`normalizeEngine` 放行 |
| `.../AutoJs.java` | `initScriptEngineManager()` 注册第三引擎（`setRuntime(createRuntime())`） |
| `.../ScriptEngineService.java` | QuickJS 与 Node24 统一走 `RunnableScriptExecution`（事件循环不在执行线程）；解析兜底不再把 node24 换给 Rhino/QuickJS |
| `.../execution/ScriptExecuteActivity.java` | `"ui"` 模式下 Node24 与 QuickJS 一样在后台脚本线程执行 |
| `apps/app/src/main/AndroidManifest.xml` | 新增 `Node24Service`（`android:process=":node24"`、`exported=false`） |

### 2.3 Auto.js Pro 完整示例集（工程试验面，非产品功能）

| 文件/目录 | 说明 |
| --- | --- |
| `apps/app/src/main/assets/pro-samples/` | Auto.js Pro 原包完整示例集：中文 + English，**403 个 .js**、共 538 个文件（2026-10-17 晚已并入 `assets/sample`，见 7.1 节修订） |
| `apps/app/src/main/java/com/jdkshen/aijspro/samples/ProSampleSweepActivity.java`（新增，exported） | 批量实测入口（资产根已随示例集并入改到 `assets/sample`）：按批（`--ei from/size/round`）执行示例；按首行指令分派引擎（`"nodejs";`→Node24、`"quickjs";`→QuickJS、其余→Rhino）；`"auto";` 改普通模式、`"ui";` 跳过；沿用 67 条风险清单 + 20 个 React/Vue 支持文件跳过；每样本结果与进度写 `files/pro-sample-sweep/`，8s 超时上限，整批 6 分钟墙钟上限 |

## 3. 构建与安装

```powershell
# 普通构建
.\build-miuix-debug.ps1 -SkipNative -NoPublish

# 与真 Auto.js Pro（org.autojs.autojspro）同机安装时：去掉与其冲突的
# org.autojs.autojspro.debug.provider（否则 INSTALL_FAILED_CONFLICTING_PROVIDER）
.\.\gradlew.bat :app:assembleMiuixCompatDebug --no-daemon -PdevPluginUsbDiscovery=false

# 覆盖安装（同签名、数据保留）
adb install -r -d apps\app\build\outputs\apk\miuixCompat\debug\app-miuix-compat-arm64-v8a-debug.apk
```

产物（2026-10-17 构建）：arm64 239.3 MiB / sha256 `f40810ee3ee13439650cef55191107c1a34b015851113a3bab14597f0b9363e5`；v7a 180.4 MiB / `517ea544…`；x86 216.3 MiB / `942ec449…`。debug 签名（证书 SHA-256 `38166a4b…`），versionCode 465 / versionName 1.0.2。体积主要来自不可压缩的 97MB `libnode.so`。

> **覆盖安装版本号说明（2026-10-17 修订）**：工程默认 `project-versions.json` 的 versionCode 是 465，而设备上此前安装的 dev 包是 dev-899（versionCode 899），因此直接安装 465 的包会被系统按"降级"拒绝（`INSTALL_FAILED_VERSION_DOWNGRADE`）。交付验证包已临时把 `project-versions.json` 的 appVersionCode/appVersionName 改成 900 / `dev-900-node24` 后构建（构建完已还原工程文件）；实测 K40（465→900）与 MI8（dev-899→900）均可直接 `adb install -r` 覆盖安装、数据保留。**以后每次交付包请确保 versionCode 高于设备已装版本。**

## 4. K40 真机验证

- 安装覆盖原 dev-899（同签名、数据保留；回滚包见 9 节），启动正常。
- 写 `/sdcard/脚本/node24-smoke.js`（首行 `// @engine node24`），通过 `.external.ScriptExecutionIntentService` 触发：
  - 脚本在 **`com.jdkshen.aijspro:node24` 独立进程**执行，标记文件记录 `{"ok":true,"pid":11963,"node":"24.22.0","v8":"13.6.233.17-node.57"}`；
  - 进程表同时存在主进程与 `:node24` 进程；可 `require` 内置模块、可写文件。

## 5. Auto.js Pro 示例集首轮实测结果（403/403 覆盖）

| 状态 | 数量 |
| --- | --- |
| 成功 | **81** |
| 脚本错误 | 94 |
| 超时（8s 上限） | 60 |
| UI 示例（本轮跳过，待 UI 夹具） | 79 |
| 风险清单跳过 | 89 |

失败归类：无障碍服务未启动 **35**、Node 内置模块缺失 **36**、其它错误 16、API 缺失 4（`$autojs` 等）、语法 3。实际执行 235 个，成功率 34%。

**实测中发现的产品级问题**：示例 `Automatically rename all apk files.js` 递归 `files.listDir("/sdcard")` 时，ART 在 `java.io.UnixFileSystem.list0` 触发 JNI abort（`input is not valid Modified UTF-8`，设备上存在非 UTF-8 文件名）→ 整个 App 崩溃。当前已在实测清单中规避（并让驱动支持崩溃恢复/结果抢救）；**文件枚举的 UTF-8 容错需要产品级修复**。

## 6. 复现示例实测

1. 安装上面的 APK；
2. 电脑侧运行工作区脚本 `工程/output/ai-js-pro-refactor-20261017/drive_sample_sweep.py`（自带分轮启动、崩溃抢救、轮询）；
3. 结果落在 `/sdcard/Android/data/com.jdkshen.aijspro/files/pro-sample-sweep/round-*.json`，聚合脚本 `analyze_sample_sweep.py` 生成 `sweep-analysis.md/json`；
4. 也可手动单批：`adb shell am start -n com.jdkshen.aijspro/.samples.ProSampleSweepActivity --ei from 0 --ei size 60 --ei round 0`。

## 7. 已知限制与后续工作

1. **Node24 暂无 Auto.js 宿主 API 桥**：目前只能跑纯 Node 脚本（console/require/process/fs）；示例中 36 个失败源于 `accessibility/toast/plugins/media_projection` 等模块缺失。下一步按"移植已验证的 ScriptHost 宿主层"或"照 `QuickJsHostBridge` 做 Node 版桥"二选一推进（预计 1–2 天）。
2. **UI 示例夹具**：79 个 `"ui";` 示例未执行，需要在受控 Activity 里跑并检查界面（约半天）。
3. **无障碍对照轮**：35 个手脚势/点击类示例因无障碍未开启而失败；开一轮无障碍对照可覆盖（约 30–40 分钟，需现场授权并还原设置）。
4. **`/sdcard` 非 UTF-8 文件名崩溃**：文件枚举层加固（0.5–1 天）。
5. **发布工程**：debug 包 239MiB，release 需要签名环境变量（`AIJSPRO_KEYSTORE/STORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD`）、体积/变体策略（把 libnode + pro-samples 拆到独立变体）、版本号 bump 与更新通道发布。
6. minSdk：工程配置为 21，但 K40 安装信息显示 minSdk 26（受依赖合并影响）；Node24 的 `libnode.so` 为 android-24 构建，低于 24 的设备不应注册 Node24 引擎（建议加 SDK 门控）。

## 7.1 示例集并回产品（2026-10-17 晚修订，dev-928）

工程试验面已经转正：`assets/pro-samples` 的 Auto.js Pro 示例集不再是独立目录，而是并入产品示例页，并按引擎分成三棵：

```
apps/app/src/main/assets/sample/
  Rhino 引擎/*  Rhino 引擎/English/*                  ← Pro 示例集里非 Node 的文件（335 个）
  Node 引擎/Node.js/*  Node 引擎/English/Node.js/*    ← Pro 示例集 Node.js 部分（203 个）
  QuickJS 引擎/*                                          ← 本仓库原有 QuickJS 示例（由「QuickJS 新引擎」改名）
```

- 示例页（Flutter 玻璃界面与原生 Miuix 示例页）都直接读这棵树，所以顶层就是「Rhino 引擎 / QuickJS 引擎 / Node 引擎」。
- `BundledSampleSync` 增加「下架示例清理」：升级后手机上旧的两棵树按上次同步记录清理（用户改过的先备份再删），不再残留旧目录。
- `verifyBundledSamplesNoNode` 对 `Node 引擎` 目录放行 nodejs 入口（其它目录仍禁）；`verifyQuickJsSamplesMarked` / `verifyBundledYoloSamples` / `verifyBundledFindImageAssets` 的路径同步改到新目录。
- `ProSampleSweepActivity` 的 `ASSET_ROOT` 改为 `sample`，实测入口保留。
- QuickJS 示例里写死的 `asset://sample/QuickJS 引擎/...` 与 `asset://sample/Rhino 引擎/图片与图色处理/找图/...` 已同步修正。
- APK 体积从 269.3 MiB 降到 266.3 MiB（旧自定义 Rhino 示例树与 pro-samples 合并，重复资产去掉）。

## 8. 边界声明

- Node24 与旧 `libnode.so`/`libv8.so` **不能同进程共存**（`Node24Libraries` 会拒绝）；本仓库没有旧 Node/V8，无冲突。
- `:node24` 为**同 UID 独立进程**（Zygote fork + Binder），无 fork/exec、无 root；SELinux 为普通 untrusted_app 上下文。
- 示例实测入口 `ProSampleSweepActivity` 是工程试验面，建议在正式发布前从 release 变体移除或加 `BuildConfig.DEBUG` 门控。

## 9. 归档与回滚（工程外）

- 原 K40 安装的 **dev-899** APK：`<workspace>/工程/output/ai-js-pro-refactor-20261017/backups/aijspro-dev-899-installed.apk`（150,930,707 B，sha256 `377495313db8e3c903dac05f6fabc6be821b7daf5ec5ca01b72c039a7710d12b`）；
- 实测报告与驱动：`<workspace>/工程/output/ai-js-pro-refactor-20261017/`（含 `sweep-analysis.md/json`、`drive_sample_sweep.py`、`analyze_sample_sweep.py`、进度 README）；
- 分发包：`<workspace>/APK/AIjsPro-Node24-三引擎重构-20261017/`（三 ABI debug APK + README）。
