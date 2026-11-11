# Flutter ↔ 原生本地桥 API 契约

> 适用：`apps/app/src/flutterpoc`（`GlassFlutterActivity` + `PocBridge` 家族）与 `flutter-ui/lib`。
> 本文件是**成文契约**：方法名、参数、返回、事件、通道一旦改动，必须同步改这里并让 `tools/flutter-bridge-contract.mjs` PASS（0 缺口）。
> 抽取自源码：`apps/app/src/flutterpoc/java/com/jdkshen/aijspro/flutterpoc/{PocBridge,PocRuntime,PocWorkspace,PocIntegration,PocPackaging,PocServices,PocAppInfo}.java`。

## 一、三条通道

| 通道 | 类型 | 方向 | 载荷 | 证据 |
|---|---|---|---|---|
| `autojs.local.bridge` | `BasicMessageChannel<String>`（`StringCodec`） | 双向（请求/应答 + 原生→Dart 事件推送） | 请求 `{"method":"…","args":{…}}`；应答 `{"ok":true,"data":…}` 或 `{"ok":false,"error":"…"}`；推送 `{"event":"…",…}` | Dart `flutter-ui/lib/bridge.dart:8-11`；原生 `GlassFlutterActivity.java:145`（`PocBridge.CHANNEL`）、`PocBridge.java:57` |
| `autojs.local.logs` | `EventChannel` | 原生→Dart 单向流 | 脚本/宿主日志行（`Stream<dynamic>`） | Dart `flutter-ui/lib/bridge.dart:15-17` |
| `com.jdkshen.aijspro/back` | `MethodChannel` | Dart→原生单向 | 目前只有 `finish`（`GlassFlutterActivity.java:156`、`:35`） | 原生 `GlassFlutterActivity.java:35/156` |

## 二、信封与 Dart 侧语义（`LocalBridge.call`）

```dart
final data = await LocalBridge.call('方法名', {'参数': 值});   // 返回应答里的 data
```

- 应答 `ok != true` 时 **Dart 抛 `StateError(error)`**（把原生错误文本当异常消息）；`reply == null` 抛 `StateError('Android 功能接口尚未连接')`。证据：`bridge.dart:53-57`。
- 超时：**默认 20s**；`import*` / `shizukuEnableAccessibility` / `toolkitCall` / `sampleDownload` / `share` / `shareFile` 为 **5 分钟**。证据：`bridge.dart:39-52`。
- 原生侧未知方法 → `UnsupportedOperationException('POC 尚未实现集成桥方法：<method>')`，经 `PocBridge` 转成 `{ok:false,error:…}`。
- `args` 缺省为空对象；参数一律**按名取值**（`getString/getBoolean/getInt/…`），不传就是各自默认（多数是 `null`/`false`/`0`）。

## 三、方法总表（100 个）

args / 返回列由脚本从源码抽取（`tools/flutter-bridge-contract.mjs` 同源可复跑）；`—` 表示该方法的参数或返回未在抽取窗口内出现（多为无参或返回的是非 JSON 对象），**以「实现位置」列的行号为准**。

### 桥内联（bootstrap / 目录 / 主题 / 编辑器等）（28）

| 方法 | args | 返回 `data`（键） | 实现位置 |
|---|---|---|---|
| `bootstrap` | — | `scripts`, `samples`, `storagePermission`, `dark`, `frontend`, `runtime` | `PocBridge.java:203` |
| `appearance` | — | `dark` | `PocBridge.java:205` |
| `scriptsRoot` | `path` | `path`, `saved` | `PocBridge.java:207` |
| `list` | `owner`, `path`, `query`, `showHidden`, `regex`, `recursive`, `directory`, `name` | `name`, `path`, `relativePath`, `directory`, `project`, `size`, `modified` | `PocBridge.java:209` |
| `theme` | `dark` | — | `PocBridge.java:211` |
| `localCatalog` | — | — | `PocBridge.java:214` |
| `liveLogs` | `since`, `maxLines`, `window`, `token`, `generation`, `textOnly` | `seq`, `token`, `name`, `level`, `levelName`, `time`, `line`, `content`, `entries`, `count`, `version`, `generation`, `headVersion`, `more` | `PocBridge.java:236` |
| `logAppearance` | — | `dark`, `light` | `PocBridge.java:238` |
| `clearLiveLogs` | — | `cleared`, `generation`, `version` | `PocBridge.java:247` |
| `shareLogText` | `text` | — | `PocBridge.java:249` |
| `openLog` | — | — | `PocBridge.java:272` |
| `engineSettings` | — | — | `PocBridge.java:277` |
| `openTerminal` | `path` | — | `PocBridge.java:280` |
| `openEditor` | — | — | `PocBridge.java:288` |
| `exitApp` | — | — | `PocBridge.java:303` |
| `keepScreenOn` | `enabled` | — | `PocBridge.java:306` |
| `openUrl` | `url` | — | `PocBridge.java:315` |
| `deviceState` | — | `accessibilityEnabled`, `accessibilityUserEnabled`, `accessibilityNeedsAttention`, `floatingWindowShowing`, `floatingUserEnabled`, `overlayGranted`, `shizukuAvailable`, `shizukuAuthorized`, `storageGranted` | `PocBridge.java:329` |
| `sampleLibrary` | — | — | `PocBridge.java:333` |
| `toolkitCall` | `arguments`, `name`, `ok` | — | `PocBridge.java:337` |
| `accessibilitySettings` | — | — | `PocBridge.java:379` |
| `overlaySettings` | — | — | `PocBridge.java:382` |
| `storagePermission` | — | — | `PocBridge.java:393` |
| `computerPluginState` | — | — | `PocBridge.java:412` |
| `runtimePreferences` | `key`, `value`, `consoleMaxLogCount`, `logMaxFileSize`, `logMaxBackups`, `consoleInitialReadFileSize` | `path`, `consoleMaxLogCount`, `logMaxFileSize`, `logMaxBackups`, `consoleInitialReadFileSize`, `stableMode`, `key_script_dir_path` | `PocBridge.java:419` |
| `drawerPreferences` | `value`, `drawer.developer`, `remoteDebug`, `keepScreenOn`, `devPluginTipDismissed` | `remoteDebug`, `keepScreenOn`, `devPluginTipDismissed` | `PocBridge.java:421` |
| `browserPreferences` | `profile`, `value`, `browser.` | — | `PocBridge.java:423` |
| `importRecords` | — | — | `PocBridge.java:425` |

### 运行 / 任务 / 日志（8）

| 方法 | args | 返回 `data`（键） | 实现位置 |
|---|---|---|---|
| `runProject` | `path` | — | `PocRuntime.java:172` |
| `floatRun` | `path` | — | `PocRuntime.java:175` |
| `tasks` | — | `token`, `kind`, `name`, `path`, `engine`, `detail`, `nextTime`, `running`, `scheduled` | `PocRuntime.java:177` |
| `stopAllScripts` | — | — | `PocRuntime.java:183` |
| `stopTasks` | `tokens` | — | `PocRuntime.java:179` |
| `cancelTask` | `token` | — | `PocRuntime.java:181` |
| `timedTask` | `path` | — | `PocRuntime.java:188` |
| `logs` | — | `content`, `truncated`, `length`, `start`, `end`, `name`, `path`, `modified` | `PocRuntime.java:186` |

### 文件、工作区与回收站（15）

| 方法 | args | 返回 `data`（键） | 实现位置 |
|---|---|---|---|
| `createFile` | — | — | `PocWorkspace.java:154` |
| `createFolder` | — | — | `PocWorkspace.java:156` |
| `createProject` | — | — | `PocWorkspace.java:158` |
| `rename` | — | — | `PocWorkspace.java:160` |
| `delete` | — | — | `PocWorkspace.java:162` |
| `deletePaths` | — | — | `PocWorkspace.java:164` |
| `fileClipboard` | — | — | `PocWorkspace.java:166` |
| `pastePaths` | — | — | `PocWorkspace.java:168` |
| `trashList` | — | — | `PocWorkspace.java:170` |
| `restoreTrash` | — | — | `PocWorkspace.java:172` |
| `workspaceContext` | `path` | — | `PocWorkspace.java:174` |
| `share` | — | — | `PocWorkspace.java:176` |
| `shareFile` | — | — | `PocWorkspace.java:177` |
| `openWith` | — | — | `PocWorkspace.java:179` |
| `aceOpenFile` | — | — | `PocWorkspace.java:181` |

### MCP / 插件 / 示例 / 快捷方式（31）

| 方法 | args | 返回 `data`（键） | 实现位置 |
|---|---|---|---|
| `run` | — | `path` | `PocIntegration.java:1038` |
| `mcpState` | — | `toolCount`, `requestCount`, `lastRequestAt`, `recentEvents`, `engineDiagnostics`, `schemaVersion`, `unavailable`, `bindHost`, `running`, `enabled`, `liveRunning`, `state`, `stale`, `updatedAt` | `PocIntegration.java:91` |
| `mcpSet` | `enabled`, `lanEnabled`, `port` | — | `PocIntegration.java:93` |
| `pluginStates` | — | `disabled` | `PocIntegration.java:95` |
| `pluginSetEnabled` | `packageName`, `enabled` | `enabled` | `PocIntegration.java:97` |
| `pluginIcon` | `packageName` | — | `PocIntegration.java:100` |
| `pluginCatalog` | — | `url`, `source`, `plugins[]`（每条含 `id`/`displayName`/`package`/`version`/`versionCode`/`sizeBytes`/`sha256`/`requiresRestart`/`abis`/`notes`/`state`/`detail`/`actionable`/`iconPath`/`iconBase64`） | `PocIntegration.java:131`（处理 `PluginBridge.java:72`） |
| `pluginInstall` | `id` | `state`（`running`）, `receivedBytes`, `totalBytes`, `id` | `PocIntegration.java:132`（处理 `PluginBridge.java:74`） |
| `pluginInstallStatus` | `id` | `state`（`running`/`success`/`failed`）, `receivedBytes`, `totalBytes`, `reasonCode`（`source`/`manifest`/`network`/`checksum`/`permission`/`missing`）, `requiresRestart`, `path`, `message`, `action`（`openInstallPermissionSettings`）, `actionLabel` | `PocIntegration.java:133`（处理 `PluginBridge.java:76`） |
| `pluginUninstall` | `id` | — | `PocIntegration.java:134`（处理 `PluginBridge.java:78`） |
| `openInstallPermissionSettings` | — | `opened`, `canInstall` | `PocIntegration.java:135`（处理 `PluginBridge.java:80`） |
| `createLauncherShortcut` | `label`（可选） | `supported`, `requested`, `mode`（`pin`/`broadcast`）, `reason`（不支持/旧接口时） | `PocIntegration.java:136`（处理 `PluginBridge.java:82`） |
| `shortcut` | `path` | — | `PocIntegration.java:106` |
| `resetSample` | `path` | `path` | `PocIntegration.java:108` |
| `copyExampleTree` | `path` | `path` | `PocIntegration.java:110` |
| `sampleAction` | `action`, `path` | `path` | `PocIntegration.java:112` |
| `openBuildPage` | `path` | — | `PocIntegration.java:102` |
| `apkBreakdown` | `path` | `apkBytes`, `entries`, `uncompressedBytes`, `buckets[]`（每条 `key`/`uncompressedBytes`/`uncompressedMiB`/`entries`） | `PocIntegration.java:116`（见 `ApkBreakdownBridge.java`） |
| `projectLoad` | — | — | `PocIntegration.java:115` |
| `projectSave` | — | — | `PocIntegration.java:116` |
| `projectBuild` | — | — | `PocIntegration.java:117` |
| `projectBuildStatus` | — | — | `PocIntegration.java:118` |
| `projectSigningList` | — | — | `PocIntegration.java:119` |
| `projectSigningAliases` | — | — | `PocIntegration.java:120` |
| `projectSigningCreate` | — | — | `PocIntegration.java:121` |
| `prepareSingleFile` | — | — | `PocIntegration.java:122` |
| `projectBuildInstall` | — | — | `PocIntegration.java:123` |
| `projectBuildShare` | — | — | `PocIntegration.java:124` |
| `nativePackagingRequest` | — | — | `PocIntegration.java:125` |
| `pendingAction` | — | — | `PocIntegration.java:104` |
| `importWorkspace` | `path` | — | `PocIntegration.java:143` |
| `importPlugin` | — | — | `PocIntegration.java:147` |
| `importResource` | — | — | `PocIntegration.java:150` |
| `importCatalogResource` | — | — | `PocIntegration.java:153` |
| `importProjectIcon` | `path` | — | `PocIntegration.java:156` |
| `importProjectSigning` | — | — | `PocIntegration.java:164` |
| `installPlugin` | — | — | `PocIntegration.java:173` |
| `computerPluginSet` | — | — | `PocIntegration.java:176` |

### 系统服务（Shizuku / 无障碍 / 悬浮菜单）（4）

| 方法 | args | 返回 `data`（键） | 实现位置 |
|---|---|---|---|
| `shizukuState` | — | `running`, `authorized`, `supported`, `installed`, `busy`, `phase`, `hint` | `PocServices.java:77` |
| `shizukuOpen` | — | — | `PocServices.java:79` |
| `repairAccessibility` | — | `repaired`, `hint` | `PocServices.java:81` |
| `floatingWindow` | `enabled` | `granted`, `showing`, `userEnabled` | `PocServices.java:83` |

### 应用信息与偏好（7）

| 方法 | args | 返回 `data`（键） | 实现位置 |
|---|---|---|---|
| `checkUpdate` | — | — | `PocAppInfo.java:57` |
| `updateSource` | `url` | `url` | `PocAppInfo.java:59` |
| `openIssueReport` | — | — | `PocAppInfo.java:61` |
| `openAbout` | — | — | `PocAppInfo.java:63` |
| `openLicenses` | — | — | `PocAppInfo.java:64` |
| `editorSettings` | `textSize` | `textSize` | `PocAppInfo.java:70` |
| `aboutInfo` | — | `versionName`, `versionCode`, `author`, `gitCommit`, `gitAuthor`, `gitDate`, `gitSubject`, `flutterUi` | `PocAppInfo.java:67` |

> 合计 **93** 个桥方法（= `PocBridge.java` 的 `case` 数）。**「99」的完整分解**（`node tools/flutter-bridge-contract.mjs` 实读）：
>
> - **93** 个同步桥方法（本表，`PocBridge.java` 的 switch 逐个列举）；
> - **+1** 个**异步分流**方法 `shizukuEnableAccessibility`——它不走 switch，而由 `PocServices.isAsync()`（`PocServices.java:94`）提前分流，见 `PocBridge.java:154`；
> - **+5** 个**内部动作标签**（`view`/`build`/`buildProject`/`import`/`resetAll`，来自 `PocIntegration.java:1024` 的示例包动作 switch）——**不是桥方法**，Dart 不能直接调。
>
> 93 + 1 + 5 = **99**（= 原生 `case` 总数）。核对脚本会打印 `nativeAll=99 reachable=94 非桥方法=5`。

## 四、原生→Dart 事件（`autojs.local.bridge` 上的推送）

| 事件 | 载荷 | 状态 | 证据 |
|---|---|---|---|
| `nativePackagingReady` | `{event:"nativePackagingReady", …}` → Dart `LocalBridge.onNativePackagingReady()` | 已实现 | Dart `bridge.dart:25-26` |
| `pendingAction` | `{event:"pendingAction", …}` → Dart `LocalBridge.onPendingAction()`；原生 `pendingAction` 方法也可主动拉取（`PocIntegration.java:104`） | 已实现 | Dart `bridge.dart:27-28`；原生 `GlassFlutterActivity.java:50/176` |
| `taskChanged(reason, path, state)` | 任务列表变化 | **本批新增（W4 task-F2）** | `FlutterBridgeEvents.java:98-99` |
| `accessibilityState(connected, reason, state, …)` | 无障碍启用状态变化 | **本批新增（W4 task-F2）** | `FlutterBridgeEvents.java:102-104` |
| `debugConnectionState(state, detail)` | 调试连接（VSCode/PC）状态变化 | **本批新增（W4 task-F2）** | `FlutterBridgeEvents.java:108-109` |
| `prefsChanged(key)` | 偏好项被外部改动 | **本批新增（W4 task-F2）** | `FlutterBridgeEvents.java:113-114` |
| `processResumed()` | 进程回到前台 | **本批新增（W4 task-F2）** | `FlutterBridgeEvents.java:117-118` |

> 新增 5 个事件的**统一语义**（`FlutterBridgeEvents.java:9-17`）：**只在 Flutter 界面存活时发送**（`activity != null && !isFinishing() && mLocalChannel != null`，不存活直接丢弃、零开销）、**150ms 内按 key 合并**、每次发送打一条 logcat `FlutterBridgeEvent: <name> <摘要>`。

> 日志不是事件而是独立 `EventChannel`（`autojs.local.logs`）。**新增事件必须同步本表**，否则 `tools/flutter-bridge-contract.mjs` 的 doc↔代码比对会报缺口。
>
> **覆盖范围更新（task-50，dev-1440）**：`taskChanged` 现在**同时覆盖 MCP 运行路径**（MCP `run_script` 不再绕过引擎全局监听）与 **TimedTask 增删改**；**事件名与字段未变**（`FlutterBridgeEventCodec` 常量不变），所以本表与核对脚本无需改判据。对照：dev-1439 时 MCP 路径**不触发** `taskChanged`（MCP 走自己的 runner），dev-1440 起已覆盖。

## 五、`toolkitCall`：Flutter 可调用全部 MCP 工具

- 形状：`LocalBridge.call('toolkitCall', {'name': '<MCP 工具名>', 'arguments': {…}})`；原生 `PocBridge` 转发到 MCP 工具桥并把工具结果放进 `data`。
- 这是**正式契约**：MCP 侧新增工具时 **Flutter 侧无需改代码**即可调用（工具名与参数以 `tools/list`/`tool_schema` 为准）。
- 当前 Dart 侧已在用它的 **10 个 MCP 工具名**（`flutter-ui/lib/toolkit_page.dart:20-21`：页面本地 helper `call(name, args)` 固定走 `LocalBridge.call('toolkitCall', {'name': name, 'arguments': args})`）：`toolkit_status`、`apk_list`、`apk_list_available_apks`、`apk_workspace_open`、`apk_workspace_delete`、`apk_read_text`、`apk_session_create`、`apk_continue`、`file_list`、`zip_list`。
- 超时按 `toolkitCall` = **5 分钟**（`bridge.dart:43`）；失败走 `{ok:false,error}` → Dart `StateError`。

## 六、适用边界：**UI 布局与运行方式不变**

- 本文只描述**桥契约**（方法/参数/返回/事件/通道）。**不改**：Flutter 页面的布局与导航结构、`GlassFlutterActivity` 的启动方式、原生页面与 Flutter 页面的职责划分。
- 桥是**进程内**调用（同一 App 进程的 Flutter engine ↔ Activity），不走网络、不跨进程；因此**没有鉴权层**，不要把它当公网接口。
- `toolkitCall` 的**能力边界等于 MCP 工具面**：MCP 侧未实现/被拒的操作，这里同样失败（错误文本来自 MCP 工具结果）。
- 事件是**推送、可能丢**（进程被杀/engine 未挂载时不补发）；需要确定性请用对应的**拉取方法**（如 `tasks`/`pendingAction`/`mcpState`）。
- 参数默认值以**源码**为准（`getXxx` 缺省），本文 args 列为抽取结果；改桥必须让核对脚本 PASS。
- **引擎侧反射与函数原型语义不属于本桥契约**：`Method.invoke(target,argArray)` 的 varargs 展开、Java 函数包装的 `Function.prototype`（`apply`/`call`/`bind`）属 **Rhino/JS↔Java 语义层**，与 Flutter 桥无关；其回归测试见 `modules/engine/src/test/java/com/stardust/autojs/rhino/ScriptJavaSemanticsTest.java`（task-53 新增，文件已在仓库中）。桥契约只覆盖 `autojs.local.bridge` 上的方法/参数/返回与事件。

## 七、已知缺口（本批核对发现，待处理）

核对脚本 `--strict` 会列出 **Dart 调了、但原生没有 `case`** 的方法。本批实测 **2 条**：

| Dart 调用点 | 方法 | 现象 | 建议 |
|---|---|---|---|
| `flutter-ui/lib/browser.dart:405` | `sampleDownload` | 原生 `PocBridge` 无此 case → 走 `default` → `{ok:false,error:"POC 尚未实现集成桥方法：sampleDownload"}` → Dart 抛 `StateError` | 要么在原生补实现（示例包按需下载），要么删掉这个调用 |
| `flutter-ui/lib/main.dart:672` | `storageIntroduce` | 同上（无 case） | 同上（存储引导页） |

> **状态：两条均已修复（task-49，dev-1439）**——`sampleDownload` 清掉了死调用（该下载已禁用），`storageIntroduce` 改用现行名 `storagePermission`。当前 `node tools/flutter-bridge-contract.mjs --strict` = **PASS / 0 gaps / dartDeadCalls=0**（Lead 独立复跑确认）。本节保留为**回归记录**：以后出现同类破口，`--strict` 会立刻 FAIL。

## 八、原生有、Dart 未直接命中（F4 复核，22 条）

> 判据：`node tools/flutter-bridge-contract.mjs --native-unused` 打印的名单（原生 93 个桥方法中，Dart 侧**字面量直呼**没命中的）。逐条人工判定三态：**动态调用**（Dart 用变量/三元传方法名）/ **未接线（死代码）** / **真缺口**。
> ⚠️ **口径：24 实测 22**。差额 **恰好 = task-49 清掉的两处 Dart 死调用**（`sampleDownload`/`storageIntroduce`，`dartNames` 85→83），所以在本轮快照下「原生有、Dart 未用」只剩 22 条；数字一律以脚本实扫为准（`native=93 dart=73 toolkits=10 doc=93`），不是人工清点。

**动态调用点一共 9 处**（`toolkit_page.dart:21` 的 `toolkitCall` 透传只是其中一处）：
`browser.dart:384`（run/runProject）、`browser.dart:810`（create*）、`browser.dart:1196`（shortcut）、`browser.dart:1260`（shareFile/copyExampleTree）、`catalog_page.dart:418`（importPlugin/importResource）、`device_page.dart:404`（permission 名）、`permission_guide.dart:156`（accessibility/overlay/storage）、`service_drawer.dart:356`+`:379`（permission 名 / floatingWindow）、`settings_page.dart:260`（aboutAction）、`project_page.dart:1142`（projectBuildInstall/Share）、`toolkit_page.dart:64`（MCP 工具名透传）。

| 方法 | 判定 | 证据 | 建议动作 |
|---|---|---|---|
| `run` | 动态调用 | `flutter-ui/lib/browser.dart:384-385`（`file['project']==true ? 'runProject' : 'run'`） | 不需动 |
| `runProject` | 动态调用 | 同上 | 不需动 |
| `createFile` | 动态调用 | `browser.dart:810-815`（`kind=='folder'?'createFolder':kind=='project'?'createProject':'createFile'`） | 不需动 |
| `createFolder` | 动态调用 | 同上 | 不需动 |
| `createProject` | 动态调用 | 同上 | 不需动 |
| `shareFile` | 动态调用 | `browser.dart:1260-1262`（`action=='share' ? 'shareFile' : …`） | 不需动 |
| `copyExampleTree` | 动态调用 | `browser.dart:1260-1265`（`… : 'copyExampleTree'`） | 不需动 |
| `importPlugin` | 动态调用 | `flutter-ui/lib/catalog_page.dart:418-419`（`plugin ? 'importPlugin' : 'importResource'`） | 不需动 |
| `importResource` | 动态调用 | 同上 | 不需动 |
| `accessibilitySettings` | 动态调用 | `flutter-ui/lib/permission_guide.dart:151-156`（kind 三元）+ `device_page.dart:397-404`/`service_drawer.dart:349-356`（形参 `method`） | 不需动 |
| `overlaySettings` | 动态调用 | `permission_guide.dart:151-156`（kind 三元） | 不需动 |
| `floatingWindow` | 动态调用 | `service_drawer.dart:372-379`（`const action='floatingWindow'`） | 不需动 |
| `projectBuildInstall` | 动态调用 | `flutter-ui/lib/project_page.dart:1248` → `artifactAction`（`:1137`/`:1142`） | 不需动 |
| `projectBuildShare` | 动态调用 | `project_page.dart:1242` → 同上 | 不需动 |
| `checkUpdate` | 动态调用 | `flutter-ui/lib/settings_page.dart:581` → `aboutAction`（`:257`/`:260`） | 不需动 |
| `shortcut` | 动态调用 | `browser.dart:1194-1196`（`action=='openWith'||action=='shortcut'` → `LocalBridge.call(action,…)`） | 不需动 |
| `share` | 未接线（旧名） | Dart 只用 `shareFile`（`browser.dart:1260-1262`）；`share` 仅出现在超时名单 `bridge.dart:97`，**无调用点**（原生 `PocBridge` 有 case） | 保留 case（兼容旧客户端）；可在本文标注「旧名，未接线」 |
| `openLog` | 未接线 | Dart **无任何引用**；Dart 自带任务日志页 `task_page.dart:249 openLog()`（同名不同物，不走桥） | 保留（原生页可达） |
| `openAbout` | 未接线 | Dart 走**本地页** `settings_page.dart:243 openAboutPage()`（`:613` 绑定） | 保留 |
| `openLicenses` | 未接线 | Dart 走**本地页** `settings_page.dart:249 openLicensesPage()`（`:620` 绑定） | 保留 |
| `engineSettings` | 未接线（**低危缺口**） | 原生 `PocBridge.java:298`；Dart 侧**无引用**（`settings_page.dart` 只有 `:522` 的「稳定运行设置」文案，不是这个页面） | **仅原生页可达（有意）**：按用户决策「**不改 Flutter UI 布局/入口**」，**不补 Dart 入口**；原生 case 保留（原生侧仍可达）。 |
| `openBuildPage` | 未接线（原生内部可达） | 原生 `PocBridge.java:375` + **内部调用** `PocIntegration.java:1046/1053`（示例包动作）→ 不经 Dart | 不需动 |

**小结**：**16 条动态调用**（全部有明确调用点，不需要动）+ **6 条未接线**（`share`/`openLog`/`openAbout`/`openLicenses` 是「原生命名 / Dart 本地页取代」的有意并存，保留不删；`openBuildPage` 由原生菜单内部到达）。**本表唯一低危缺口是 `engineSettings`**（Dart 侧无入口）：**按「不改 Flutter UI 布局/入口」的用户决策定判为「仅原生页可达（有意）」**，不补 Dart 入口——本节因此**无遗留待办**，后续若出现新的未接线项，`--native-unused` 会立刻列出来。

## 九、可复跑核对

```text
node tools/flutter-bridge-contract.mjs
```

输出形如 `native=93 dart=<N> toolkits=<M> doc=93 缺口=[…]`；**0 缺口才 PASS**（双向：文档 ⊇ 原生、原生 ⊇ 文档、Dart 调用名 ⊆ 原生、三通道名一致）。

