# Flutter 桥 API 与功能对齐方案（2026-11-09）

> **决策前提（用户 2026-11-09 明确）**：**Flutter 界面的运行方式与 UI 布局保持现状不变**；只做 **API / 功能对齐**。
> 因此本方案**不做**：Flutter-first 主界面、页面 parity/布局调整、`FlutterService` 式绑定服务。**只做**：桥契约成文 + 状态推送补齐 + 调用面核实。
> 上位方案：[与 Pro 9.3.11 对齐方案](2026-11-09_与Pro9.3.11对齐方案.md)（本方案取代其档 B 的形态部分）。

## 1. 现状（已核实，全部有出处）

### 1.1 三条通道

| 通道 | 类型 | 方向 | 出处 |
|---|---|---|---|
| `autojs.local.bridge` | `BasicMessageChannel<String>`，载荷 JSON `{method,args}` | Dart ⇄ 原生 | `flutter-ui/lib/bridge.dart:8-49`；`GlassFlutterActivity.java:141-150`（`PocBridge.CHANNEL`） |
| `autojs.local.logs` | `EventChannel` | 原生 → Dart（日志流） | `flutter-ui/lib/bridge.dart:15`；`ConsoleLogEvents.java:28` |
| `com.jdkshen.aijspro/back` | `MethodChannel` | Dart → 原生（仅 `finish`） | `flutter-ui/lib/main.dart:152-199`；`GlassFlutterActivity.java:156` |

### 1.2 方法面（请求方向）

| 项 | 数字 | 说明 |
|---|---:|---|
| 原生 `PocIntegration` 的 `case` | **99** | `apps/app/src/flutterpoc/.../PocIntegration.java` |
| Dart 侧发出的方法名 | **88** | `flutter-ui/lib/**` |
| Dart 有、原生无同名 case | 13 | **其中 10 个已确认是 MCP 工具名**，走 `toolkitCall` 转发（`flutter-ui/lib/toolkit_page.dart:21`：`LocalBridge.call('toolkitCall',{name,arguments})`）→ **不是缺口** |
| 原生有、Dart 侧未直接命中 | 24 | 需核实是「动态方法名（`Bridge.call(var)`）」还是死代码（见 §3 F4） |

**结论**：方法面基本自洽；`toolkitCall` 是**通用 MCP 透传**——意味着 Flutter 界面能调用**全部 226 个 MCP 工具**（Pro 没有这个能力面）。

### 1.3 推送面（原生 → Dart）——**这是真正的短板**

| 事件 | 现状 | 出处 |
|---|---|---|
| `pendingAction` | ✅ 唯一事件 | `GlassFlutterActivity.java:50`、`:176`（`mLocalChannel.send("{\"event\":\"pendingAction\"}")`） |
| 日志 | ✅ 独立 EventChannel | `ConsoleLogEvents.java` |
| 脚本/任务状态变更 | ❌ 无 | — |
| 无障碍服务状态变更 | ❌ 无 | — |
| 调试连接状态变更 | ❌ 无 | — |
| 偏好/设置变更 | ❌ 无（设置变更后 Dart 得重新请求） | — |
| 进程恢复 | ❌ 无 | — |

## 2. 与 Pro 的功能面对照（Pro 方法名被混淆，只能按**功能**对）

| Pro 的 Flutter 层 | 证据（可读残留） | 我们 | 差距判定 |
|---|---|---|---|
| `FlutterScriptsChannel`：脚本/任务状态**推送**、`stopAll*`、以 `TimedTask`/`execution`/`path` 为参数 | 方法名 `notifyTaskChanged`、`stopAll*` 可读；`android.intent.extra.shortcut.INTENT`；Kotlin 协程 | 请求式控制已有（`run`/`stopAllScripts`/`stopTasks`/`tasks`/`timedTask`/`runProject`） | **推送缺** → F2 |
| `SettingsService`（50KB）：设置面 + **五类状态回调** `onAccessibilityServiceStateChanged` / `onDebugConnectionStateChanged` / `onCircularMenuStateChange` / `onProcessResume` / `onSharedPreferenceChanged` | 方法名可读 | 请求式设置已有（`appearance`/`editorSettings`/`engineSettings`/`runtimePreferences`/`drawerPreferences`/`browserPreferences`/`overlaySettings`/`mcpState`/`theme`…） | **状态推送缺** → F2 |
| `FlutterService`：绑定型前台服务（`onBind`/`onServiceConnected`/`onServiceDisconnected`） | 方法名可读 | 无对应（Flutter 跑在 app 进程内） | **不做**（运行方式不变） |
| `FlutterActivity`：承载 Flutter 界面 + `onActivityResult`/`onNewIntent`/状态保存 | 方法名可读 | `GlassFlutterActivity` 已有同类职责 | 已等价 |
| `FlutterExports(channel="googleplay")` | 字符串常量 `googleplay` | 无 | 不需要（渠道标记） |

## 3. 对齐方案（只动 API/功能）

| # | 条目 | 做什么 | 验收（可观察） | 估算 | 写作用域 |
|---|---|---|---|---|---|
| **F1** | **桥 API 契约成文** | 把 `autojs.local.bridge` 的 99 个方法（含 args/返回形状/错误形状）、3 条通道、事件列表写成文档；明确 `toolkitCall` = 任意 MCP 工具透传 | 文档与代码逐条对齐（脚本抽 99 个 case 与文档比对，0 缺口） | 0.5–1 天 | `docs/`（+ 可选 `apps/app/src/main/assets/` 随包文档） |
| **F2** | **补齐状态推送（对齐 Pro 的推送面）** | 原生 → Dart 新增事件：`taskChanged`（脚本/任务启停、TimedTask 增删改）、`accessibilityState`、`debugConnectionState`、`prefsChanged`、`processResumed`；Dart 侧订阅后**只做数据刷新（UI 不变）** | 真机：启停脚本/改任务/开关无障碍 → Dart 侧收到事件（用现有日志或临时探针打印）；事件名与载荷写进 F1 契约 | 2–4 天 | `apps/app/src/flutterpoc/**`、`flutter-ui/lib/bridge.dart` 等 |
| **F3** | 核实 3 个未命中方法 | `sampleDownload` / `storageIntroduce` / `apk_continue`：是否有原生处理；没有就补 `case` 或改调用 | 3 个各自写明「已转发/已补/已删」+ 证据 | 0.5 天 | 同 F2 |
| **F4** | 核实 24 个「原生有、Dart 未直接命中」 | 逐个判定：动态方法名 / 死代码；死代码**只标注不删** | 24 行表：判定 + 证据 | 0.5 天 | 文档 + 注释 |
| **F5** | 功能面终判 | 把 Pro Flutter 层的功能逐条落「补/不补」终态（与本方案 §2 表同列） | 表里每条有终态 | 0.5 天 | 本文档 |

**F1 与 F2 是主体**（合计 2.5–5 天），F3–F5 是澄清性工作（1.5 天）。

## 4. 明确不做

1. Flutter-first 主界面（`MainActivity extends FlutterActivity`）——按决策保持现状。
2. 页面 parity / UI 布局改动——按决策保持现状。
3. `FlutterService` 式绑定服务——我们的运行方式不引入它。
4. 渠道标记 `FlutterExports("googleplay")`——无需求。

## 4.5 进度（2026-11-09 18:50，dev-1439）

| 项 | 状态 |
|---|---|
| F1 契约成文 + 核对脚本 | ✅ 完成（`docs/guides/Flutter本地桥API.md` + `tools/flutter-bridge-contract.mjs`，`--strict` PASS/0 gaps） |
| F2 状态推送 5 事件 | ✅ 交付并真机验三类：`processResumed` / `accessibilityState` / `prefsChanged`（logcat `FlutterBridgeEvent`）；`debugConnectionState` 未真机触发（需 Dart 轮询路径） |
| F3 两个未命中方法 | ✅ 完成（`sampleDownload` 清死调用、`storageIntroduce` 改现行名） |
| F4「原生有、Dart 未用」核实 | ⬜ 未做（脚本目前只 gate Dart 死调用方向） |
| F5 功能面终判表 | ⬜ 未做 |
| **taskChanged 覆盖 MCP 路径 + TimedTask** | ⬜ 已派 task-50（真机证实：MCP `run_script` 不触发） |

## 5. 风险与边界

1. **推送是双刃**：事件太密会拖慢 Flutter 侧；F2 必须给节流/合并（参考 `ConsoleLogEvents.java:49` 已有的 150ms 合并写法）。
2. **契约漂移**：F1 的文档必须能被脚本核对（抽 `case` 与文档比对），否则又会漂。
3. **Pro 侧方法名被混淆**：本方案的对齐是**功能级**，不是 API 名字级；不声称「与 Pro 的 Flutter API 一一对应」。
4. 未验证项：F2 的五个事件在真机上的触发时机与线程（需在 `:script`/主进程间确认回调线程）。
