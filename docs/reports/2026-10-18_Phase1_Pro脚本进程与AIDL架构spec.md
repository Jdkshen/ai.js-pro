# 2026-10-18 Phase1：Pro 的 :script 进程 + AIDL 架构 spec（Phase1-A）

> 目标：从仓库内已有的 Auto.js Pro 反编译源码出发，产出 **可指导写代码（类名/文件/方法级）** 的 Phase 1 架构 spec，用于把我们的脚本执行从“主进程 + :native 隔离”迁到与 Pro 对齐的 **:script 进程 + AIDL** 模型。
> 证据来源（只读）：
> - Pro 9.3.11 反编译：`.artifacts/pro-official/`（`classes*.dex` + jadx 源码；APK 元数据 `org.autojs.autojspro` 9.3.11-0 / versionCode 9131100，targetSdk 30）
> - 另一版本（实为同版本换包名重打包）：`.artifacts/pro-apk/auma-src/`（`com.boyai.auma` 9.3.11-0，类结构一致，无架构差异）
> - 我们现状：`modules/engine/src/main/java/com/stardust/autojs/ScriptEngineService.java`、`apps/app/src/flutterpoc/.../nativeproc/`、`docs/reports/2026-10-17_Phase0_native隔离进程MVP.md`
> - AndroidManifest 由 `aapt2 dump xmltree .artifacts/pro-apk/base.apk --file AndroidManifest.xml` 解码（源码树里的 `pro-official/AndroidManifest.xml` 是二进制 AXML，不可直读）
> 本文件只写 spec，不改代码、不跑 gradle/adb。

## 0. 摘要（TL;DR）

1. **Pro 9.3.11 只有 3 类进程**：主进程（Flutter/UI，默认）、**`:script`（所有脚本引擎、自动化、悬浮窗、控制台、无障碍服务、Shizuku、编辑器/工作区/日志 Activity 都在这里）**、`:crash_report`（只放 ErrorReportActivity）。**本版本没有 `:node` 进程**；Node/V8 引擎（`AutoJs.C6636`、`V8ScriptExecuteActivity`）同样跑在 `:script` 内。
2. 跨进程只有一条主通道：主进程 `bindService(com.stardust.autojs.ScriptService)`，AIDL **`com.stardust.autojs.IScriptServiceInterface`（10 个 transaction）**；:script 回调主进程用 **`com.stardust.autojs.IScriptServiceEventCallback`（1 个 transaction）**。另有 2 条轻量通道：显式广播 `autojs.pro.action.execution.started/finished`（引擎生命周期）和 **`org.autojs.autojspro.FlutterInterface`（:script→主进程 Flutter 的 pushRoute`，1 个 transaction）**。
3. “运行一个脚本”统一的入口是 **AIDL tx1 `run(ScriptExecutionTask, boolean showFloaty)`**：任务本身是 Parcelable（`ScriptSource` + `ExecutionConfig`），:script 里的 `ScriptService.BinderC0496` 调进程内单例 `bc4.f3405`（= ScriptEngineService 等价物）创建执行；UI 模式由引擎工厂返回“Activity 型执行”，再在 :script 内 `startActivity(ScriptExecuteActivity)`，用 executionId 从进程内单例取回执行对象。
4. **自动化全部在 :script 进程内**：无障碍服务（manifest 里伪装成 `com.google.android.accessibility.selecttospeak.SelectToSpeakService`，`process=":script"`）持有全局单例 `com.stardust.view.accessibility.AccessibilityService.INSTANCE`；`AccessibilityBridge`/`SimpleActionAutomator`/`UiObject`/`UiSelector`/`GlobalActionAutomator`/截屏 `ScreenCapturer` 全部在 :script 内直接调用。主进程不持任何自动化对象，只通过 AIDL tx7/tx8（按类名反射到 :script 单例）做“开/关无障碍、读状态”。
5. 对 Phase 1 的最小落地：先做 **:script + ScriptService + 上面那套 AIDL + 把 ScriptEngineService/引擎/Console/Floaty/无障碍/自动化搬到 :script**，主进程只保留 Flutter UI + 客户端封装（复用现有 `PocNativeRunner` 风格的 bind/linkToDeath/重连）；编辑器/工作区/V8/Node/调试服务器/定时任务可后置。

---

## 1. Pro 的进程模型

### 1.1 manifest 实测（base.apk，org.autojs.autojspro 9.3.11）

| 进程 | 承载的组件（举例，均来自 manifest） |
| --- | --- |
| **默认（主进程）** | `org.autojs.autojs.flutter.FlutterActivity`（Flutter UI）、`FlutterService`（实现 FlutterInterface，供 :script 反推路由）、`MainActivity / SplashActivity / SettingsActivity / WebActivity`、`ScriptExecutionIntentService`（IntentService 转 AIDL run）、`ScriptWidget/ApplicationBroadcastReceiver/TaskReceiver/StaticBroadcastReceiver`（默认进程的广播接收器）、`TinkerResultService` 等 |
| **`:script`** | `com.stardust.autojs.ScriptService`（**bound + 前台服务**，foregroundServiceType=0x20 mediaProjection）、`com.stardust.autojs.execution.ScriptExecuteActivity`、`org.autojs.autojspro.v8.api.ui.V8ScriptExecuteActivity`、`com.stardust.enhancedfloaty.FloatyService`、`com.stardust.notification.NotificationListenerService`、`com.google.android.accessibility.selecttospeak.SelectToSpeakService`（无障碍服务）、`com.stardust.autojs.core.permission.PermissionRequestActivity`、`com.stardust.autojs.core.activity.StartForResultActivity / ExternalActionActivity`、`rikka.shizuku.ShizukuProvider`、`org.autojs.autojs.ui.edit.EditActivity / ui.log.LogActivity / ui.timing.TimedTaskSettingActivity / ui.main.workspace.WorkspaceActivity / ui.edit.visual.VisualEditorActivity`、`debugserver.rpc.DebugAuthConfirmActivity`、`external.contentprovider.DebugContentProvider`、`external.tile.LayoutBoundsTile/LayoutHierarchyTile`、`FloatyWindowManger（receiver）`、`TaskSchedulerImpl$RunTaskReceiver`、`ThemeChangedBroadcastReceiver`、`NotificationBroadcastReceiver` |
| **`:crash_report`** | `org.autojs.autojs.ui.error.ErrorReportActivity`（崩溃上报页） |

结论：**Pro 的思路是“把一切危险的/需要长生命周期的都塞进 :script，主进程只剩下 UI 壳”**。UI 模式 Activity、编辑器、日志页、定时任务设置页都在 :script —— 因为它们要直接访问 :script 内的引擎单例/运行时。`:crash_report` 只用于崩溃兜底。

### 1.2 如何 start / bind / 前台服务与通知

- 主进程 `org.autojs.autojs.App.onCreate()`（默认进程分支）执行：
  - `ScriptService.f1244.m722(this, new BinderC6301())` —— 这是 **ScriptService 的静态客户端助手（Kotlin Companion）**：`bindService(new Intent(context, ScriptService.class), conn, BIND_AUTO_CREATE=1)`；连接成功后 `onServiceConnected` 里：
    1. 用 `iBinder.queryLocalInterface("com.stardust.autojs.IScriptServiceInterface")` 取 AIDL 代理（混淆名 `yx0`；Proxy 内类 `yx0.AbstractBinderC3719.C3720`），存静态 `ScriptService.f1246`；
    2. 回调传入的 listener（即注册 `IScriptServiceEventCallback`：`mo729(cb)`）；
    3. 排空排队中的待执行 lambda（`ScriptService.f1248`）。
  - 失败/断连（`onServiceDisconnected`）：`ScriptService.f1244.m722(ctx, cb)` **立即重绑**（Pro 就是靠这个在 :script 进程崩溃/被系统杀死后自动恢复 IPC，见 §5.4）。
- `:script` 自己的 `App.onCreate()`（`Processes.isScriptProcess()` 分支）执行：`FlutterService.f30156.m15173(this)`（绑定主进程的 FlutterService 拿反推路由的代理）、`AutoJs 初始化`（进程级引擎单例）、`ScriptService.f1244.m722(this, BinderC6301())`（:script 内本地绑定，便于统一走 AIDL 客户端 API）、`ApplicationBroadcastReceiver` 注册。
- **前台服务**：`ScriptService.onCreate()` 里若 `Pref.KEY_FOREGROUND_SERVICE` 为 true 就 `startForeground(7001, notification)`（channel id = `ScriptService.foreground`）；AIDL tx6 `setForeground(boolean)` 可在运行中开关。**foregroundServiceType=mediaProjection(0x20)** 是给截屏（MediaProjection 需要同进程有该类型的前台服务）用的。
- 注意：`ScriptService` 未重写 `onStartCommand`，即默认 `START_STICKY`；进程被杀后系统会尝试重建服务（但**不会**自动恢复已死脚本，脚本列表由主进程重新查询得到空表）。

### 1.3 与我们 Phase0 的关键差异（先记住，§6 详述）

| 维度 | Pro | 我们 Phase0 |
| --- | --- | --- |
| 进程 | `:script`（全部脚本相关）+ `:crash_report` | `:native`（只有“带 so/dex 的项目”才进，普通脚本仍在主进程） |
| IPC | **AIDL**（10+1 transactions）+ 显式广播 + FlutterInterface | **Messenger**（RUN/STOP/STARTED/FINISHED/ERROR 5 种消息） |
| 执行位置 | 所有引擎/自动化/浮窗/控制台都在 :script | :native 里只有引擎/浮窗/控制台；自动化、无障碍仍缺失 |
| UI 模式 | :script 内真 Activity（ScriptExecuteActivity） | headless，不开 Activity（`setHeadlessUiMode`） |
| 任务列表/日志 | 主进程通过 AIDL 拉 + 广播推送 + 日志文件 | Messenger 推送 + ScriptRunLog 文件 |

---

## 2. AIDL / IPC 清单（类名、包名、方法、事务号）

### 2.1 核心：`com.stardust.autojs.IScriptServiceInterface`

- **接口全名**：`com.stardust.autojs.IScriptServiceInterface`（AIDL）。反编译里接口/Stub/Proxy 被混淆为 `okhttp3/internal/io/yx0.java`（接口 + `AbstractBinderC3719` Stub + `C3720` Proxy），服务端实现在 `com/stardust/autojs/ScriptService.java` 的内部类 `BinderC0496`（`onBind` 返回它）。
- 反编译与事务表（**这是实现时可以直接照抄的契约**；方法名取语义名，原实现被混淆）：

| tx | 原方法（混淆名） | 语义（建议命名） | 参数 Parcel 顺序 | 返回值 |
| --- | --- | --- | --- | --- |
| 1 | `mo735` | `int run(ScriptExecutionTask task, boolean showFloaty)` | `writeInt(1)` + `task.writeToParcel` + `writeInt(z)` | `int` executionId（-1 = showFloaty 分支/失败） |
| 2 | `mo728` | `void stop(int executionId)` | `writeInt(id)` | void |
| 3 | `mo733` | `int stopAll()`（不弹 toast 的版本） | — | `int` 被停止数量 |
| 4 | `mo731` | `int stopAllAndToast()` | — | `int`（实现 = stopAll + toast；Pro 主进程按 SDK/场景选用 tx3 或 tx4） |
| 5 | `mo727` | `List<ScriptExecutionInfo> getExecutions()` | — | `createTypedArrayList(ScriptExecutionInfo)` |
| 6 | `mo734` | `void setForeground(boolean)` | `writeInt(z)` | void |
| 7 | `mo730` | `Map call(String serviceClassName, String action, Map args)` | `writeString(cls)` + `writeString(action)` + `writeMap(args)` | `Map`（通用服务分发，见 2.3） |
| 8 | `mo736` | `Map get(String serviceClassName, String name)` | `writeString(cls)` + `writeString(name)` | `Map` |
| 9 | `mo729` | `void registerCallback(IScriptServiceEventCallback cb)` | `writeStrongBinder` | void |
| 10 | `mo732` | `List<RunningActivityInfo> getRunningActivities()` | — | `createTypedArrayList(RunningActivityInfo)` |

- 服务端行为（`ScriptService.BinderC0496` 逐条）：
  - tx1：`z==true` → 在主线程 `post` 一个 lambda：先 `FloatingPermission.ensurePermissionGranted(context)`，再 `new u70(service, task)`（`u70` = 创建悬浮“运行控制条” FloatingControllerView，含退出/编辑按钮），返回 -1；`z==false` → `bc4.f3405.m2414(task).getExectionId()`，异常时 console.error 并返回 -1。
  - tx2：遍历 `bc4.f3405.f3409.getEngines()` 找 `id` 相同者 → `engine.forceStop()`。
  - tx3/tx4：`bc4.f3405.m2418()`（= `ScriptEngineManager.stopAll()`）/ `m2419()`（= `m2418()` + toast “已停止 n 个脚本”）。
  - tx5：把 `bc4.f3405.f3412`（`LinkedHashMap<Integer, ScriptExecution>`）转成 `ScriptExecutionInfo(source, config, id)` 列表。
  - tx6：`ScriptService.m720(boolean)` → true 时建 notification channel + `startForeground(7001)`，false 时 `stopForeground(true)`。
  - tx7/tx8：`ScriptService.m719(service, cls)` 反射加载类 → 取静态单例（带 @Keep 的 INSTANCE 字段，或 new）→ 调 `pz3.mo5775(action, args)` / `pz3.mo5776(name)`（见 2.3）。
  - tx9：保存回调代理到 `ScriptService.f1251`（之后 :script 侧任何地方可通过 `ScriptService.f1244.m723(event, map)` 向主进程推事件）。
  - tx10：`bo0.f3658.m2607()`（内部维护的 Activity 栈快照）→ `RunningActivityInfo(className, RunningActivityStateInfo, isTop)` 列表，用于主进程 UI“运行中的界面”。

### 2.2 回调：`com.stardust.autojs.IScriptServiceEventCallback`

- 反编译：`okhttp3/internal/io/xx0.java`（接口 + `AbstractBinderC3625` Stub + `C3626` Proxy）；:script 侧调用点是 `ScriptService.m723(name, map)` → `f1251.mo11410(name, map)`。
- 事务表：**tx1 `void onEvent(String name, Map params)`**（`writeString(name)` + `writeMap(params)`）。
- 主进程接收器：`okhttp3/internal/io/BinderC6301.java`（App.onCreate 里注册）→ `oz3.f13290.get(name)` 取注册的 `nz3` 适配器 → `adapter.mo7510(name, params)` → 结果 post 到 EventBus（greenrobot）。**事件是“命名事件 + Map”，不是固定 struct**；Pro 目前只注册了两类适配器（见 2.3），新增事件只需在 :script 侧 `m723("xxx.state", map)` 并让同名 `nz3` 在 `oz3` 注册。

### 2.3 通用服务分发（tx7/tx8）——:script 暴露给主进程的“服务注册表”

- 机制：AIDL 只带“类名 + action + args(Map)”→ `ScriptService.m719` 在 :script 里反射拿类实例（优先取带 `@Keep` 的 public static 单例字段），然后调该类的 `pz3.mo5775(action, Map)` / `mo5776(name)`。主进程侧通过 `ScriptService.f1244.m724(context, lambda)` 拿 AIDL 代理后直接调用。
- 现有 4 个可远程分发的 `pz3` 实现（`okhttp3/internal/io/*.java`，按类名传字符串）：

| 类（混淆名/实际语义） | action / name | 行为 |
| --- | --- | --- |
| `C4259` = 无障碍服务工具 | `mo5775: "disable" / "enable_by_root"`；`mo5776: "state"` | disable → `AccessibilityService.INSTANCE.disable()`；enable_by_root → `AccessibilityServiceTool.checkAccessibilityServiceBlocking(...)`（root/Shizuku/settings）；state → `AccessibilityService.INSTANCE.getInstance() != null` |
| `y2` = 调试服务/Shizuku 状态 | `mo5775: "set_debug_service"`（args: nodejs_enabled/mode/host）；`mo5776: "state"` | 启停 VSCode 扩展用的文件服务器；返回 `State(serverState, clientState, connectionCount, fileServerPort)` |
| `iv2` = 通知监听服务 | `mo5776: "state"` | `NotificationListenerService.INSTANCE != null` |
| `r82` = 全局控制台 | `mo5775: "BT3YgnTXNQp56BKP"`（固定 token） | `GlobalConsole.setOutputFilePath()`（主进程改日志路径后让 :script 重新指向） |

- 主进程调用点示例：`SettingsService.java:892`（`mo730(C4259.class.getName(), "enable_by_root", …)`）、`FlutterScriptsChannel.java:172`（`mo730(r82…, "BT3YgnTXNQp56BKP")`）、`SettingsService.java:747`（`mo736(iv2…, "state")`）。

### 2.4 其他跨进程接口（非 :script 主链，Phase1 可后置）

| AIDL / 通道 | 接口全名 | 方向 | 事务 | 用途 |
| --- | --- | --- | --- | --- |
| FlutterInterface | `org.autojs.autojspro.FlutterInterface`（`nb0.java`） | :script → 主进程 | tx1 `boolean pushRoute(String route)` | :script 请求主进程 Flutter UI 导航（如打开日志/编辑页）。服务端 = `org.autojs.autojs.flutter.FlutterService.BinderC6673`（在默认进程，`onBind` 返回它）；:script 侧通过 `FlutterService.f30156.m15173(ctx)` 绑定取得代理 `f30157` |
| 显式广播 | `autojs.pro.action.execution.started / .finished` | :script → 主进程 | — | 引擎生命周期事件，extra: `"execution"=ScriptExecutionInfo(Parcelable)`、finished 另有 `success/message/lineNumber/columnNumber`；:script `bc4.C1005` 的 onStart/onSuccess/onException 里 `sendBroadcast(...setPackage(pkg))`；主进程 `FlutterScriptsChannel.scriptExecutionReceiver` 与 `EditorView` 动态注册接收 |
| BroadcastReceiver(默认进程静态) | `com.stardust.autojs.core.timing.TaskReceiver`（action `com.stardust.autojs.action.task`）、`StaticBroadcastReceiver`、`TaskSchedulerImpl$RunTaskReceiver(:script)` | 系统 → 两进程 | — | 定时/意图任务：收到后 `BaseBroadcastReceiver.Companion.runTask()` 里 `ScriptService.f1244.m725(lambda: mo735(task,false))` 远程运行 |
| 通知/Toast 等 | — | — | — | 通过 `ScriptNotification`/`ConsoleFloaty` 在 :script 内直接发系统通知 |
| 第三方插件 | `org.autojs.plugin.sdk.IAutoJsRemoteCallback`（`vv0.java`） | 插件 APK ↔ 宿主 | — | 插件 SDK，本 spec 不展开 |
| Shizuku | `moe.shizuku.server.IShizukuService/IRemoteProcess`（`xo4/ix0/ay0`） | :script → Shizuku | — | 特权 shell/远程进程，`rikka.shizuku.ShizukuProvider` 声明在 :script |
| 计费 | `com.android.vending.billing.IInAppBillingService` | 默认进程 → Play | — | 与 Phase1 无关 |

### 2.5 跨进程数据结构（必须 Parcelable）

- `com.stardust.autojs.execution.ScriptExecutionTask`：`ExecutionConfig config`（先写）+ `Parcelable source`（后写）；构造有 `(ScriptSource, ScriptExecutionListener, ExecutionConfig)`，**listener 不跨进程**（transient，AIDL 里传 null）。
- `com.stardust.autojs.execution.ExecutionConfig`：字段顺序（读 Parcel 顺序）= workingDirectory:String → path:String[] → intentFlags:int → delay:long → interval:long → loopTimes:int → flags:long → Bundle arguments → uiScriptActivity:String（类名，可空）。另含 `FLAG_DEBUG=1 / FLAG_WAITING_FOR_DEBUGGER=2`、transient 的 `projectConfig/projectDir`、scriptSerializedArguments、mScriptArguments。
- `com.stardust.autojs.execution.ScriptExecutionInfo`：source:ScriptSource + config:ExecutionConfig + id:int（Parcel 顺序正是这个）。
- `com.stardust.app.RunningActivityInfo`：className:String + `RunningActivityStateInfo`（Parcelable）+ isTop:boolean。
- `com.stardust.autojs.script.ScriptSource` 家族（都要 Parcelable）：`JavaScriptFileSource / StringScriptSource / SequenceScriptSource / JavaScriptSource`；`JavaScriptSource.getExecutionMode()`（bit：1=UI 模式、4=强制 nodejs、16=强制 rhino，见 `C4441.m12693` 与 `AutoJs.C6636.createExecution`）。

---

## 3. 自动化代理：Accessibility / Automator / 截屏 / 手势

### 3.1 结论：**没有“自动化 AIDL”**，自动化全部在 :script 进程内

- Pro 的无障碍服务声明在 `:script`：manifest `com.google.android.accessibility.selecttospeak.SelectToSpeakService（process=":script", permission=BIND_ACCESSIBILITY_SERVICE)`。其类 `com/google/android/accessibility/selecttospeak/SelectToSpeakService.java` 继承 Pro 自己的基类 `com.stardust.view.accessibility.AccessibilityService`（extends `android.accessibilityservice.NoMemoryLeakAccessibilityService`）。基类持有 **静态单例 `AccessibilityService.INSTANCE / instance`**、按键/手势观察者、delegate 注册表、状态变更广播（`ACTION_STATE_CHANGE`）。
- 因此脚本进程内的 `AccessibilityBridge`（抽象类，`com/stardust/autojs/core/accessibility/AccessibilityBridge.java`；具体实现是 `org.autojs.autojs.autojs.AutoJs.C4943`，在 `AbstractC4936` 里）可以直接拿到 `AccessibilityService.INSTANCE`，**不需要跨进程**。
- **主进程侧没有任何自动化对象**：它只在设置页里通过 AIDL tx7 调 `C4259` 去 enable/disable/读状态（见 §2.3），以及动态注册广播 `AccessibilityService.ACTION_STATE_CHANGE` 刷新 UI。

### 3.2 接口清单（类名 + 方法级）

| 能力 | 类 / 方法（:script 内） | 说明 |
| --- | --- | --- |
| 无障碍服务本体 | `com.stardust.view.accessibility.AccessibilityService`：`INSTANCE`、`getInstance()`、`disable()`、`waitForEnabled(timeout)`、`addDelegate(id, AccessibilityDelegate)`、`removeDelegate(id)`、`getMFastRootInActiveWindow()`、`getWindows()/getRootInActiveWindow()`、`getStickOnKeyObserver()`、`ACTION_STATE_CHANGE` 广播 | 系统绑定服务（:script 进程），脚本用到的全局单例 |
| 服务开关（root/Shizuku/设置页） | `com.stardust.autojs.core.accessibility.AccessibilityServiceTool`：`checkAccessibilityServiceBlocking(context, timeout, wait)`、`enableAccessibilityServiceByRoot(context, Class)`、`enableAccessibilityServiceByRootAndWaitFor(...)`、`enableAccessibilityServiceBySecureSettings(...)`、`reEnableAccessibilityService(...)` | :script 内执行；主进程经 AIDL tx7 触发 |
| 选择器 | `com.stardust.autojs.core.accessibility.UiSelector`（extends `com.stardust.automator.UiGlobalSelector`）+ `com.stardust.automator.filter.*` + `com.stardust.automator.search.*` + `UiObjectCollection` | 脚本 API `selector()/text()/id()/desc()/className()/bounds()…` 的底层 |
| 节点对象/动作 | `com.stardust.automator.UiObject`（extends `AccessibilityNodeInfoCompat`）：`click()`、`longClick()`、`setText()`、`scrollForward/Backward()`、`children()/parent()/child(i)`、`bounds()/text()/desc()/className()/packageName()/id()`、`refresh()` 等；`Companion.Clicker`（全局点击坐标回退） | 全部在 :script 内直接操作 AccessibilityNodeInfo |
| 全局动作/手势（API≥24 dispatchGesture） | `com.stardust.automator.GlobalActionAutomator`：`dispatchGesture` 系列（`gesturesWithHandler/WithoutHandler`、`GestureResultCallback`）、`pointsToPath`、`performGlobalAction(GLOBAL_ACTION_BACK/HOME/RECENTS/NOTIFICATIONS...)`、`gesture(duration, points[][])`、`gestureAsync`、`swipe/click/press` 等 | 由 `SimpleActionAutomator.mGlobalActionAutomator` 持有；`GestureOnMainThreadException` 防主线程阻塞 |
| 常用动作门面 | `com.stardust.autojs.core.accessibility.SimpleActionAutomator`：`click(x,y)/longClick/press/swipe/gesture/scroll/back/home/recent/notifications/quickSettings/powerDialog/splitScreen/setText/input/appendText` 等（696 行）；`AccessibilityEventEmitter` 注册无障碍事件回调 | 脚本全局对象 `automator` 的实现 |
| 截屏 | `com.stardust.autojs.runtime.api.Images`：`requestScreenCapture()/captureScreen()/getScreenCaptureOptions()/releaseScreenCapturer()`；`com.stardust.autojs.core.image.capture.ScreenCaptureRequester`（用 `StartForResultActivity(:script)` 拉 `MediaProjectionManager.createScreenCaptureIntent()`）；`ScreenCapturer`（MediaProjection + VirtualDisplay + ImageReader，`getMediaProjection `/`createVirtualDisplay`/`setImageCaptureCallback`） | 依赖 `ScriptService` 的 `foregroundServiceType=mediaProjection`；截图不需要主进程参与 |
| Root/Shell 输入 | `com.stardust.autojs.core.inputevent.RootAutomator/RootAutomator2`、`ShellKeyObserver`、`GlobalKeyObserver`、`com.stardust.autojs.core.shell.RootShell/ShizukuShell` | 都在 :script；音量键监听也在这里（§5.3） |
| 屏幕度量 | `com.stardust.automator.util.ScreenMetrics` | :script 全局 |

### 3.3 各操作路径（截屏/点击/滑动/选择器/手势分别怎么走）

- **选择器**：脚本 → Rhino/V8 binding → `UiSelector`（进程内）→ `AccessibilityService.INSTANCE.getMFastRootInActiveWindow()/getWindows()` → 递归搜索 → `UiObject`。
- **点击/长按/输入**：脚本 → `UiObject.click()` → `AccessibilityNodeInfo.performAction(ACTION_CLICK)`（失败回退 `Companion.globalClicker.click(x,y)` → `GlobalActionAutomator` 坐标手势）。
- **滑动/手势**：脚本 → `automator.swipe/gesture` → `GlobalActionAutomator` → `AccessibilityService.dispatchGesture`（Handler 版走 `gesturesWithHandler`，阻塞等待 `VolatileDispose`）。**不经过主进程**。
- **截屏**：脚本 → `images.requestScreenCapture()` → `ScreenCaptureRequester` 用 :script 内 Activity 请求授权 → `ScreenCapturer` 创建 VirtualDisplay/ImageReader → 回传 Bitmap。首次授权是系统对话框，仍需一个 Activity（在 :script）。
- **无障碍开关**：主进程 Flutter 设置页 → `SettingsService` → AIDL tx7 `call(C4259.class, "enable_by_root"/"disable")` → :script 内 `AccessibilityServiceTool`/`AccessibilityService.INSTANCE.disable()` → 结果 Map 回主进程；状态变更还会双向通知（:script 侧 `ACTION_STATE_CHANGE` 广播 + 可选回调事件）。
- **root/Shizuku 特权**：:script 内 `ShizukuProvider`/`RootShell` 直接拿 binder/binder 转 shell；主进程不碰。

---

## 4. 引擎侧：ScriptRuntime 里各组件在哪个进程、如何回传

### 4.1 ScriptRuntime 的构建位置与组件（`com/stardust/autojs/runtime/ScriptRuntime.java:163`）

`ScriptRuntime(Builder)` 构造函数（实测）按顺序创建：

| 组件 | 类/字段 | 进程 | 生命周期 |
| --- | --- | --- | --- |
| 屏幕度量 | `mScreenMetrics = new ScreenMetrics()` | :script | 每 runtime |
| 资源清理 | `mCloseableManager = new CloseableManager()` | :script | 每 runtime（`addCleanable`） |
| ShellOptions | `mDefaultShellOptions = new ShellOptions("sh", false)` | :script | 每 runtime |
| UiHandler | `builder.mUiHandler`（:script 主线程 Handler） | :script | 单例（AutoJs） |
| AppUtils | `builder.mAppUtils`（当前 Activity 栈） | :script | 单例 |
| Console | `builder.mConsole`（`GlobalConsole`，log4j RollingFileAppender） | :script | 进程级单例 |
| AccessibilityBridge | `builder.mAccessibilityBridge`（`AutoJs.C4943`） | :script | 进程级单例 |
| UI | `new UI(context, this)`（ui.layout/showToast 等） | :script | 每 runtime |
| Automator | `new SimpleActionAutomator(bridge, this)` → 全局 `automator` | :script | 每 runtime |
| Info | `bridge.getInfoProvider()`（ActivityInfoProvider：包名/Activity 栈） | :script | 每 runtime |
| Images | `new Images(context, this)`（截屏/找图/OCR） | :script | 每 runtime |
| Engines | `new Engines(builder.mEngineService, this)`（`execScript`/`all`/`stopAll`） | :script | 每 runtime（内部引用进程级 bc4） |
| Dialogs | `new Dialogs(this)`（`alert/confirm/select/rawInput`；`DialogType.OVERLAY` 走悬浮窗，`Theme` 走 Activity） | :script | 每 runtime |
| Device | `new Device(context)` | :script | 每 runtime |
| Floaty | `new Floaty(uiHandler, ui, this)`（悬浮窗 API，依赖 `FloatyService(:script)`） | :script | 每 runtime |
| Files/Media | `new Files(this)` / `new Media(context, this)` | :script | 每 runtime |
| 其余（console==GlobalConsole、timers、threads、events、loopers、sensors、clipboard、app、yolo、ocr、plugins…） | 在 `AutoJs` 构建 ScriptRuntime 时注入（builder） | :script | 进程级或每 runtime |

### 4.2 “跨进程如何回传”——实际上是：**引擎侧 API 不回传，因为全部同进程**

- 脚本 API 调用（automator/floaty/console/timers/dialog/文件/截图）全部在 :script 本地完成，没有 IPC 往返。
- 需要“回传给主进程可见”的只有三类：
  1. **引擎/执行生命周期**：`bc4.C1005(ScriptExecutionListener)` 在 onStart/onSuccess/onException 里 ① `sendBroadcast(autojs.pro.action.execution.started/finished, scriptExecutionInfo)`（主进程 FlutterScriptsChannel/EditorView 接收并刷新任务列表/编辑器运行结果）② EventBus 本地事件（:script 内部 console 显示“开始运行/已停止”）。
  2. **控制台日志**：`GlobalConsole`（log4j RollingFileAppender，路径由 `Pref.getLogOutputFilePath()` 决定）写文件；:script 的 `LogActivity`/`ConsoleFloaty` 直接读；主进程 Flutter 日志页读**同一路径文件**；路径变更通过 AIDL tx7 `call(r82, "BT3YgnTXNQp56BKP")` 通知 :script 重新 setOutputFilePath。
  3. **无障碍/通知/调试服务状态**：:script 内状态变化 → `ScriptService.f1244.m723("<pz3类名>.state", map)` → 主进程回调 `BinderC6301` → `oz3` 适配器 → EventBus → 设置页刷新。

### 4.3 Timers / 定时任务 / 权限 / dialog 的进程归属

- **Timers**：每个引擎 runtime 一套（`Timers/Threads/Loopers`，与开源版结构一致）。`setTimeout/setInterval` 绑定到脚本线程 Looper（`LoopBasedJavaScriptEngine`）或 :script 主线程（ui 模式 Activity 线程）；`hasPendingCallbacks()` 决定脚本是否继续存活（Loopers.shouldQuitLooper）。
- **定时任务（TimedTask）**：
  - 持久化与调度：`com.stardust.autojs.core.timing.TimedTaskManager / TaskSchedulerImpl`（单例；:script 内有 `TaskSchedulerImpl$RunTaskReceiver`，默认进程有静态 `TaskReceiver/StaticBroadcastReceiver`）。
  - 触发：AlarmManager/Broadcast → 默认进程静态 receiver 或 :script 的 `TaskSchedulerImpl$RunTaskReceiver` → `BaseBroadcastReceiver.Companion.runTask()`（默认进程）→ `ScriptService.f1244.m725(mo735(task,false))` 交给 :script 执行；:script 内触发的走进程内 `bc4.m2414`。
  - 设置页 `TimedTaskSettingActivity` 在 :script（直接读写 TimedTaskManager 单例）。
- **权限**：`com.stardust.autojs.core.permission.PermissionRequestActivity` 在 :script；`ScriptRuntime.requestPermissions()` → :script 内 Activity 弹系统权限；脚本自身的运行时权限申请不需要主进程。
- **Dialog**：`Dialogs` 默认 `DialogType.OVERLAY`（:script 内悬浮窗 MaterialDialog，不依赖 Activity，任意脚本可用）；需要 Activity 主题时用 `runtime.app.getTopActivity()`（:script 的 Activity 栈）或 fallback applicationContext。**没有“主进程弹 dialog”的机制**。
- **Floaty**：`runtime.api.Floaty` 创建窗口 → `FloatyService(:script)` `startService + addView`；主进程不参与。UI 模式脚本的 `ui.layout` 渲染在 :script 的 `ScriptExecuteActivity` 内。
- **Console**：`GlobalConsole`（进程级，log4j 文件）+ 每引擎 `ConsoleImpl/UiConsole`；`console.show()` 是 :script 内 ConsoleFloaty 悬浮窗。

---

## 5. UI 模式脚本：ScriptExecuteActivity 在哪个进程、引擎如何绑定

### 5.1 进程与声明

- `com.stardust.autojs.execution.ScriptExecuteActivity`：manifest `process=":script"`（不是主进程！）。
- `org.autojs.autojspro.v8.api.ui.V8ScriptExecuteActivity`：同样 `:script`，是 V8/Node UI 模式的 Activity。
- 因此 Pro 的 “UI 模式脚本 = 在 :script 里开一个 Activity”，Activity 与引擎**同进程**，可以直接从进程级单例取到执行对象。

### 5.2 执行链（方法级）

1. 主进程（Flutter 运行按钮）→ AIDL tx1 `run(task, false)`（UI 模式在 task 的 `JavaScriptSource` 里带 executionMode bit1；`ExecutionConfig.uiScriptActivity` 可指定 Activity 类名，缺省 `ScriptExecuteActivity`）。
2. :script `ScriptService.BinderC0496.mo735` → `bc4.f3405.m2414(task)` → `bc4.m2415`：解析 `ProjectConfig`（向上找 project.json）→ `ScriptEngineManager.createExecution(context, task, engineName)` → 引擎工厂：
   - `AutoJs.C6634`（rhino）→ 返回 `RunnableScriptExecution`/LoopBased（后台）；
   - `AutoJs.C6636`（nodejs）按 executionMode 分派：bit1 → **`V8ScriptExecuteActivity.C6954`（Activity 型执行）**；bit8 → `f26`（主线程 Looper 型）；否则 `RunnableScriptExecution`。
3. 返回的 `ScriptExecution` 放入 `bc4.f3405.f3412`（id → execution），然后 `execution.start()`：
   - Activity 型执行 `start()`：`new Intent(context, uiScriptActivityClass).putExtra(ScriptExecuteActivity.EXTRA_EXECUTION_ID, executionId).addFlags(config.intentFlags)`，若 context 非 Activity 再加 `FLAG_ACTIVITY_NEW_TASK`，然后 `context.startActivity(intent)`（context 在 :script）。
4. `ScriptExecuteActivity.onHandleIntent/handleIntent(intent, bundle)`：读 `EXTRA_EXECUTION_ID` → `bc4.f3405.m2417(id)` 取回 `ActivityScriptExecution` → `execution.createEngine()`（waitForDebuggerIfNeeded → `ScriptEngineManager.createEngineOfSourceOrThrow(source,id)`，setTag(ExecutionConfig.TAG)）→ 把 runtime 注入 Activity（`getView` 设置 ui.layout 内容视图）→ `doExecution()`：`LoopBasedJavaScriptEngine.execute(source, callback)` → 结果/异常回调 `ActivityScriptExecution.notifyStart/notifySuccess/notifyException`。
5. 结束：`finish()` → 若有未处理异常则 onException，否则 notifySuccess；onDestroy 里清理引擎与 runtime（并 `bc4.C1005` 发 finished 广播）。主进程任务列表通过广播+下次 tx5 查询保持一致。
6. **注意**：Activity 的 `createResources()` 用私有 AssetManager `addAssetPath` 加载项目资源（ui.layout 里引用项目内资源）；`getAaptPackageName()` 在 debug 包下返回调试包名（VSCode 调试用）。

### 5.3 与我们现状的冲突点

- 我们 Phase0 的 `:native` 是 headless（`ScriptEngineService.setHeadlessUiMode(true)`，UI 模式脚本不开 Activity，ui.* 会报错）。Phase1 必须把 `ScriptExecuteActivity` 声明进 `:script` 并在 AIDL run 时正常走 Activity 型执行。<br>**这正是 Phase0 报告里“下一步正解”的第 2 条**（报告第 56-57 行：按 Pro 模型给隔离进程提供主线程 Looper 上跑的 UI 模式宿主 = 进程内 ScriptExecuteActivity）。

---

## 6. 日志 / 任务列表 / 停止 / 音量键停止 / 崩溃重启 的跨进程实现

### 6.1 任务列表（主进程 Flutter 任务页）

- 初次/轮询：AIDL tx5 `getExecutions()` → `List<ScriptExecutionInfo>（source/config/id）`；tx10 `getRunningActivities()` → Activity 类名/isTop（判断“运行中的界面”）。
- 实时更新：:script 发显式广播 `autojs.pro.action.execution.started / finished`（`setPackage(pkg)`）→ 主进程 `FlutterScriptsChannel` 动态注册的 receiver 收到 `ScriptExecutionInfo`（Parcelable）→ 推 Dart 侧（事件码 0=started、2=finished，map 含 name/source/id）。finished 额外带 success/message/line/column（异常时）。
- 主进程 UI 侧另有一个“可停止”列表：tx2 stop(id) 后 :script 从 map 移除（`bc4.C1006.onEngineRemove` 里 `f3412.remove`）。

### 6.2 日志/控制台

- :script 进程：`GlobalConsole`（log4j，`RollingFileAppender`，maxFileSize/maxBackups 来自 Pref）写日志文件；`LogActivity(:script)`、`ConsoleFloaty`、`UiConsole/FileConsoleView` 读同一文件。
- 主进程：Flutter 日志页读同一日志文件（共享外部文件目录）；日志路径变更 → AIDL tx7 `call(r82, "BT3YgnTXNQp56BKP")` 让 :script 切到新路径。
- 脚本级 `console.log`：写入引擎 console（`ConsoleImpl`）+ GlobalConsole 文件。

### 6.3 停止

- 单脚本停止：主进程 AIDL tx2 `stop(executionId)` → :script 找 `ScriptEngineManager.getEngines()` 中 id 匹配的 engine → `forceStop()`（内部 `Thread.interrupt` + engine.uncaughtException(ScriptInterruptedException)）。
- 全部停止：AIDL tx3/tx4（区别是是否 toast），:script `ScriptEngineManager.stopAll()`。
- 其它停止入口：脚本自身 `exit()`（`ScriptRuntime.exit()` → engine.uncaughtException → 走 onException）、通知栏停止（ScriptNotification 带 PendingIntent）、任务页 ✕。

### 6.4 音量键停止（Pro 是“音量上键”停所有脚本）

- `com.stardust.autojs.core.inputevent.GlobalKeyObserver`（:script，由 `ScriptService.onCreate` 里 `GlobalKeyObserver.initIfNeeded()` 初始化；开关 Pref `key_use_volume_control_running`）：
  - 监听来源有两条：`com.stardust.view.accessibility.AccessibilityService.INSTANCE.getStickOnKeyObserver()`（无障碍 filterKeyEvents）和 `ShellKeyObserver`（root/Shizuku getevent），互相去重；
  - `onVolumeUp()` → `AbstractC4936.m13246().m13261().m2419()` = **stopAllAndToast()**（即 AIDL tx4 的实现同一方法）；
  - `onVolumeDown()` → 分发给 `OnVolumeDownListener`（脚本可注册，不是停止）。
- 因此**音量键停止完全在 :script 内**：进程持有无障碍/root 按键通道，直接调进程内引擎服务。Phase1 必须把 GlobalKeyObserver 初始化放进 :script 的 ScriptService（或等价宿主 Service），主进程不需要参与。

### 6.5 崩溃/进程死亡与重启

- **主进程侧**：`ScriptService.f1244.m722` 的 `ServiceConnection.onServiceDisconnected` 会 **立刻重绑**（m722 再 bind），所以 :script 崩溃后主进程能自动恢复 IPC；同时 `FlutterScriptsChannel` 收到的 execution 广播停止 → 任务列表靠轮询 tx5 清空。
- **:script 侧**：`ScriptService` 未重写 `onStartCommand` → 默认 STICKY，进程被杀后系统尝试重建服务；重建时 `App.onCreate` → `AutoJs` 单例重建 → `ScriptService.onCreate`（GlobalKeyObserver/前台通知/pref 监听）→ 但**已死脚本不会自动恢复**（Pro 也没有“重启后恢复运行中脚本”）。
- **崩溃上报**：`:crash_report` 进程的 `ErrorReportActivity` + Bugly（`com.tencent.bugly`）；脚本异常本身不杀进程，只走 `bc4.C1005.onException` → console.error + finished 广播（success=false）。
- **保活/白名单**：`mr2`（NetService，默认进程）维护后端下发的白名单并发 `rs.wl` 广播（`ScriptService` 静态 receiver 接收，回调列表），与 Pro 的授权/白名单有关，Phase1 不必实现。

### 6.6 跨进程时序图（文字版）

    ┌──────────── 主进程 (Flutter) ────────────┐        ┌──────────── :script ────────────┐
    │ FlutterScriptsChannel / SettingsService  │        │ ScriptService (AIDL Stub)       │
    │  App.onCreate → bind + registerCallback  │───────▶│  BinderC0496: run/stop/list/... │
    │  ServiceConnection.onServiceDisconnected │◀───────│  IScriptServiceEventCallback     │
    │  receiver(started/finished 广播)         │◀───────│  sendBroadcast(execution.*)      │
    │  FlutterService (FlutterInterface Stub)  │◀───────│  :script 侧 bind FlutterService  │
    │  读日志文件                               │◀───────│  GlobalConsole 写日志文件        │
    └──────────────────────────────────────────┘        │  bc4(引擎单例) / AutoJs / 无障碍 │
                                                        │  ScriptExecuteActivity / Floaty  │
                                                        └──────────────────────────────────┘

---

## 7. 与我们 Phase0 :native 的对照 + 最小可用 Phase1 分阶段落地

### 7.1 我们现状盘点（Phase0 已完成，可直接复用/改造）

| 模块 | 现有实现 | 位置 |
| --- | --- | --- |
| :native 隔离进程服务 | `NativeScriptService`（`android:process=":native"`，Messenger IPC：RUN=1/STOP=2/STARTED=100/FINISHED=101/ERROR=102；项目先 staging 到私有目录；headless UI 模式） | `apps/app/src/flutterpoc/java/com/jdkshen/aijspro/flutterpoc/nativeproc/NativeScriptService.java` |
| 主进程客户端 | `PocNativeRunner`（startService+bindService、Messenger、linkToDeath、binder 死亡自动重连、runningTasks、token 前缀 native:） | 同目录 `PocNativeRunner.java` |
| 项目检测/暂存 | `NativeProjectDetector`、`NativeProjectStaging` | 同目录 |
| 路由 | `PocRuntime.run/runProject/tasks/stopTasks` 按检测结果路由到 :native | `apps/app/src/flutterpoc/.../PocRuntime.java` |
| 引擎 | `modules/engine` 的 `ScriptEngineService` + `ScriptEngineManager` + Rhino/QuickJS 引擎；`setHeadlessUiMode` | `modules/engine/src/main/java/com/stardust/autojs/` |
| 浮窗/控制台/日志 | `FloatyService`、`GlobalConsole`、`ScriptRunLog`（Phase0 在 :native 内可用） | modules/engine |

### 7.2 对照差异（Phase1 要补的）

| 维度 | Pro :script | 我们 Phase0 :native | Phase1 动作 |
| --- | --- | --- | --- |
| 进程覆盖 | 所有脚本都在 :script | 只有带 so/dex 的项目进 :native，普通脚本仍主进程 | 新增 :script，普通脚本也迁；:native 后续并入 |
| 控制 IPC | AIDL 10 tx + 回调 + 广播 | Messenger 5 消息 | 新写 AIDL（见附录 A），Messenger 保留给 :native 过渡 |
| 执行入口 | 统一 `ScriptService` | `NativeScriptService.handleRun` | 把 `ScriptService` 作为 :script 唯一执行入口 |
| UI 模式 | :script 内真 Activity | headless（ui.* 报错） | 把 `ScriptExecuteActivity` 声明进 :script 并接回执行链 |
| 自动化 | 无障碍/选择器/手势/截屏全在 :script | 无（:native 设计限制） | 无障碍服务 + AccessibilityBridge + automator + Images 全部搬 :script |
| 任务列表 | AIDL tx5/tx10 + 广播 | Messenger 推送 + PocRuntime 合并 | 广播/AIDL 双通道；PocRuntime 增加 :script token 路由 |
| 日志 | GlobalConsole 文件 + 主进程读文件 | ScriptRunLog 文件 + Messenger | 复用文件方案，路径变更走 AIDL |
| 停止 | tx2/tx3/tx4 + 音量上键 | Messenger STOP + PocRuntime.stopTasks | AIDL stop + GlobalKeyObserver(:script) |
| 崩溃恢复 | 主进程 ServiceConnection 自动重绑；服务 STICKY | linkToDeath + 自动重连 + 日志“连续第 N 次” | 沿用，:script 侧也做 onServiceDisconnected 重绑 |

### 7.3 分阶段落地（推荐顺序；P0 为 Phase1 最小可用）

#### P0 —— 第一批必须做（:script 能跑普通脚本 + UI 模式 + 基本控制）

1. **新建 :script 宿主服务**
   - 新增 `apps/app/src/.../scriptproc/ScriptService.java`（`android:process=":script"`，bound + 可选前台；`onBind` 返回 AIDL Stub）。
   - 新增 `apps/app/src/.../scriptproc/ScriptServiceBridge.java`（主进程/其他进程通用客户端：`ensureBound(context, Consumer)`、pending lambda 队列、`onServiceDisconnected` 自动重绑，照抄 Pro `ScriptService.C0494` 的 m722/m724/m725/m726）。
   - Manifest 增加 service（`process=":script"`、`foregroundServiceType` 视需要）。
2. **AIDL 接口（见附录 A）**
   - `IScriptServiceInterface`：run/stop/stopAll(两版)/getExecutions/setForeground/call/get/registerCallback/getRunningActivities。
   - `IScriptServiceEventCallback`：`onEvent(String, Map)`。
   - Parcelable：`ScriptExecutionTask`、`ExecutionConfig`、`ScriptExecutionInfo`、`RunningActivityInfo`、`ScriptSource` 家族。
3. **把引擎搬进 :script**
   - 在 :script 的 `Application.onCreate` 分支初始化 AutoJs/`ScriptEngineService` 单例（参考 Pro `App.java:128-141` 的进程判断分支：`Processes.isScriptProcess()`）；`ScriptEngineService` 静态单例、`ScriptEngineManager`、`GlobalConsole`、`UiHandler` 全部只在 :script 创建。
   - `ScriptService` 的 AIDL 实现直接调这些单例（Pro `BinderC0496` 的逐条逻辑见 §2.1）。
4. **主进程客户端 + 路由**
   - 新增 `ScriptServiceClient`（主进程 Flutter 侧的 Java 封装）：run(task, showFloaty)、stop(id)、getExecutions()、setForeground、call/get、registerEventCallback。
   - `PocRuntime.run/runProject/tasks/stopTasks` 增加“普通脚本 → :script”路由（token 前缀如 script:）；:native 路由保留一个过渡期。
   - 广播接收：主进程注册 `autojs.pro.action.execution.started/finished`（我们包名前缀自定）刷新任务列表/日志。
5. **UI 模式**
   - 把 `ScriptExecuteActivity` 声明进 :script；AIDL run 后由引擎工厂返回 Activity 型 `ScriptExecution`，`start()` 时 `startActivity(ScriptExecuteActivity, EXTRA_EXECUTION_ID)`；Activity 从 :script 进程内 `ScriptEngineService.getExecution(id)` 取回执行（Pro `ScriptExecuteActivity.handleIntent` 第 559-580 行）。
   - 删除/绕过 `setHeadlessUiMode(true)` 对普通 :script 脚本的限制（:native 仍保留 headless 直到并入）。
6. **验收（P0 通过标准）**
   - 普通脚本、UI 脚本（ui.layout/floaty/console.show）在 :script 运行，主进程任务列表/停止/日志正常；:script 被 kill 后主进程自动重连且不闪退；连续运行/停止 10 次无残留；`adb shell ps` 只看到主进程 + :script（+ :crash_report 可选）。

#### P1 —— 第二批（自动化与特权能力齐全）

7. **无障碍/自动化搬 :script**：`com.google.android.accessibility.selecttospeak.SelectToSpeakService`（`process=":script"`，BIND_ACCESSIBILITY_SERVICE）+ `com.stardust.view.accessibility.AccessibilityService` 基类 + `AccessibilityBridge` 具体实现 + `SimpleActionAutomator/UiSelector/UiObject/GlobalActionAutomator` 全部只依赖 :script；主进程设置页通过 AIDL tx7 call（`AccessibilityServiceTool` 或自定义 pz3 等价类，“enable”/“disable”/“state”）+ 广播刷新。
8. **截屏**：`ScriptService` 声明 `foregroundServiceType="mediaProjection"`（manifest 已给值 0x20）+ `Images/ScreenCaptureRequester/ScreenCapturer` 在 :script；权限请求 Activity（`StartForResultActivity`）声明进 :script。
9. **音量键停止**：`GlobalKeyObserver.initIfNeeded()` 放在 :script 的 ScriptService.onCreate（Pref 开关）；音量上 → 引擎服务 stopAll。
10. **定时任务**：默认进程静态 receiver 收到 Alarm/广播 → 经新增客户端走 AIDL run；:script 内 `TaskSchedulerImpl` 处理进程内定时；设置/管理页（`TimedTaskSettingActivity`）声明进 :script。
11. **验收**：auto/click/swipe/selector/gesture/截图/找图在 :script 正常；音量上键停止生效；定时任务能触发 :script 脚本。

#### P2 —— 后置（对齐 Pro 完整 UI/调试/插件）

12. 编辑器/工作区/日志页/可视化编辑器等 Activity 搬进 :script（`EditActivity/WorkspaceActivity/LogActivity/VisualEditorActivity` 等）。
13. `FlutterInterface`（:script → 主进程 pushRoute）+ Flutter 侧路由处理。
14. V8/Node 引擎（`V8ScriptExecuteActivity`、nodejs 引擎工厂、`set_debug_service` 文件服务器）搬 :script；:node 进程如果未来版本引入再单独评估（Pro 9.3.11 没有）。
15. 插件 SDK、Shizuku 深度集成、计费/白名单（rs.wl）。
16. 把 :native 并入 :script（详见 7.5）。

### 7.4 新文件清单（建议）

| 文件 | 职责 |
| --- | --- |
| `apps/app/src/main/aidl/com/jdkshen/aijspro/script/IScriptServiceInterface.aidl`（或手写 Stub） | AIDL 契约（附录 A） |
| `apps/app/src/main/aidl/.../IScriptServiceEventCallback.aidl` | 事件回调 |
| `apps/app/src/.../scriptproc/ScriptService.java` | :script 宿主 Service（AIDL Stub，run/stop/list/foreground/call/get/getRunningActivities） |
| `apps/app/src/.../scriptproc/ScriptServiceBridge.java` | bind/重连/回调管理（照抄 Pro C0494） |
| `apps/app/src/.../scriptproc/Events.java` | :script 侧事件 -> 主进程（IScriptServiceEventCallback.onEvent，oz3/nz3 等价物） |
| `apps/app/src/.../scriptproc/RunningActivityTracker.java` | Activity 栈快照（tx10） |
| `apps/app/src/.../scriptproc/ScriptExecutionLauncher.java` | 引擎工厂 + UI/后台分派（等价 Pro AutoJs.C6636.createExecution） |
| Manifest 补 :script 声明 | ScriptService/ScriptExecuteActivity/无障碍/StartForResultActivity/PermissionRequestActivity/LogActivity/TimedTaskSettingActivity |
| 主进程：`apps/app/src/flutterpoc/.../scriptproc/ScriptServiceClient.java` | 主进程 AIDL 客户端 + 广播接收 + PocRuntime 路由 |

### 7.5 :native 与 :script 的过渡策略（重要）

- **不要在 Phase1 第一步就删 :native**：先在 :script 打通“普通脚本 + UI 模式”，:native 留给“已知会带 native 崩溃”的项目；两条通道并行。
- 过渡完成后，把 `NativeScriptService` 的执行改成“:script 的 AIDL 客户端”，或直接把 :native 的 run/stop 逻辑并入 `ScriptService`（:native 只作为可选的“再隔离一层”）；届时 `PocNativeRunner` 的 Messenger 协议可保留为兼容层（token 前缀区分 native:/script:）。
- 进程命名：Pro 用 :script，我们建议同名（:script），未来对照/移植成本最低；:native 保留为二级隔离（仍可声明 process=":native"）。
- 迁移注意：`modules/engine` 的 `ScriptEngineService` 是进程级静态单例（与 Pro 同构），主进程若仍直接 new 引擎会与 :script 单例冲突 → Phase1 后主进程**只允许**通过 AIDL 触达引擎，禁止 main 进程创建引擎（可加断言：主进程调用 ScriptEngineService.getInstance() 抛异常，逼迫调用方改走 :script）。

### 7.6 真机验收（Phase1 总体验收，供 Lead 构建后执行）

- 普通脚本 / UI 脚本 / floaty+console 脚本 / 无障碍（click/selector/gesture）/ 截图 各跑 1 个，全部在 :script（`ps -A` 可见）；主进程任务页可见/可停；日志页能读。
- :script 用 `kill -9` 杀掉：主进程不闪退、自动重连（日志出现“隔离/脚本进程已退出（连续第 N 次）”类），再次运行成功。
- 音量上键停止所有脚本。
- 连续 10 轮 run/stop 无 binder 泄漏、无残留进程、无崩溃。
- 回滚路径：保留 :native 通道 + 主进程引擎直跑开关（debug 开关），一旦 :script 有问题可切回。


## 8. 风险与实现注意

1. **AIDL 数据结构**：跨进程只能传 Parcelable/基本类型；不要把 ScriptExecutionListener/Activity/Context 放进 AIDL（Pro `ScriptExecutionTask` 的 listener 字段是 transient，AIDL 侧传 null；执行结果通过回调/广播回来）。
2. **大 payload**：脚本源码建议走“文件路径/项目目录”而不是把整个源码字符串塞进 Parcel（`StringScriptSource` 只用于小脚本/预执行片段；Pro 也是以 `JavaScriptFileSource` 为主）。Binder 事务有 1MB 限制。
3. **线程**：AIDL 方法在 Binder 线程池执行，`run` 必须快速返回（Pro 的 tx1 里 showFloaty 分支还要 post 到主线程）；`getExecutions` 等读进程级 map 要加锁（Pro `bc4.f3412` 是 LinkedHashMap，靠调用时机串行；我们建议 ConcurrentHashMap）。
4. **UI 模式 Activity 必须与引擎同进程**：`ScriptExecuteActivity` 不能声明在主进程，否则 `bc4.f3405.m2417(id)` 取不到执行对象（跨进程 static 不共享）。这是 Phase1 最容易踩的坑。
5. **前台服务类型**：截屏需要 `foregroundServiceType="mediaProjection"`（Android 10+ 强制）；不同时声明会抛 SecurityException（Pro 值 0x20）。
6. **:script 承载 Activity 的影响**：:script 进程会有 task/Activity（UI 模式），进程优先级提高但内存占用变大；taskAffinity/excludeFromRecents/launchMode 要按 Pro 的 manifest 抄（ScriptExecuteActivity 同 :script 默认 affinity）。
7. **无障碍服务被杀**：系统可能随时 stop 无障碍服务；:script 侧 `AccessibilityService` 静态单例要能重建（Pro 有 enableCallbacks/waitForEnabled + ACTION_STATE_CHANGE 广播），主进程不能本地缓存“已启用”状态，必须每次 tx8 查或订阅事件。
8. **进程死亡一致性**：主进程任务列表以 AIDL tx5 为唯一真相；广播只做增量更新；:script 重启后列表为空是 Pro 的现状（我们不要试图恢复脚本，保持简单）。
9. **回调事件无 schema**：Pro 的 `IScriptServiceEventCallback` 是 (String, Map)，容易演进但无类型安全；我们建议沿用“命名事件 + Map”但统一在 Events.java 常量类里定义 key，每个事件加 version。
10. **不要跨进程传 Class**：Pro `ExecutionConfig.uiScriptActivity` 以“类名字符串”跨进程（读 parcel 时 Class.forName），我们照做但要做类白名单校验。
11. **日志并发**：`GlobalConsole` 是 log4j RollingFileAppender（单进程内），主进程只读；若未来主进程也要写日志（如启动阶段），用独立文件避免 rollover 竞争。
12. **AIDL 兼容性**：接口一旦发布，事务号顺序不能变（Pro 的 tx1..tx10 已固定）；新增方法只能追加事务号。若我们自定义接口，建议一开始就按 Pro 顺序排（见附录 A），方便对照/未来兼容。

---

## 附录 A：建议的 AIDL 契约（可直接落地）

**IScriptServiceInterface.aidl**（包名 com.jdkshen.aijspro.script，事务顺序照 Pro）：

    interface IScriptServiceInterface {
        // tx1: 运行；showFloaty=true 时只在 :script 弹“运行控制条”并返回 -1；否则返回 executionId
        int run(in ScriptExecutionTask task, boolean showFloaty);
        // tx2
        void stop(int executionId);
        // tx3
        int stopAll();
        // tx4
        int stopAllAndToast();
        // tx5
        List<ScriptExecutionInfo> getExecutions();
        // tx6
        void setForeground(boolean foreground);
        // tx7：按“服务类名 + action + args(Map)”反射分发到 :script 内单例（pz3 等价）
        Map call(String serviceClassName, String action, in Map args);
        // tx8
        Map get(String serviceClassName, String name);
        // tx9
        void registerCallback(IScriptServiceEventCallback callback);
        // tx10
        List<RunningActivityInfo> getRunningActivities();
    }

**IScriptServiceEventCallback.aidl**：

    oneway interface IScriptServiceEventCallback {
        void onEvent(String name, in Map params);   // 命名事件 + Map，见 Events.java
    }

**Parcelable 清单（与 Pro 一致）**：`ScriptExecutionTask(ScriptSource source, ExecutionConfig config)`、`ExecutionConfig(workingDirectory, path[], intentFlags, delay, interval, loopTimes, flags, Bundle arguments, uiScriptActivity)`、`ScriptExecutionInfo(source, config, id)`、`RunningActivityInfo(className, state, isTop)`、`ScriptSource` 子类（JavaScriptFileSource/StringScriptSource/SequenceScriptSource/JavaScriptSource）。

> 兼容提示：Pro 的 AIDL 接口全名是 com.stardust.autojs.IScriptServiceInterface / com.stardust.autojs.IScriptServiceEventCallback。我们若未来要与 Pro 的脚本/调试器互操作可沿用同名；否则用自包名 + 同事务号即可（本 spec 建议后者，避免与真 Pro 同机冲突）。

## 附录 B：Manifest 关键片段（照 Pro 抄，落到我们包名）

    <service android:name=".scriptproc.ScriptService"
             android:process=":script" android:exported="false"
             android:foregroundServiceType="mediaProjection" />
    <activity android:name="com.stardust.autojs.execution.ScriptExecuteActivity"
              android:process=":script" android:excludeFromRecents="true" />
    <activity android:name="org.autojs.autojspro.v8.api.ui.V8ScriptExecuteActivity"
              android:process=":script" />
    <service android:name="com.google.android.accessibility.selecttospeak.SelectToSpeakService"
             android:process=":script" android:exported="true"
             android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
        <intent-filter><action android:name="android.accessibilityservice.AccessibilityService" /></intent-filter>
        <meta-data android:name="android.accessibilityservice" android:resource="@xml/accessibility_service_config" />
    </service>
    <activity android:name="com.stardust.autojs.core.permission.PermissionRequestActivity"
              android:process=":script" android:theme="@style/ScriptTheme" />
    <activity android:name="com.stardust.autojs.core.activity.StartForResultActivity"
              android:process=":script" android:theme="@style/ScriptTheme" />

## 附录 C：关键类索引（Pro 混淆名/源码路径 ↔ 语义）

| Pro 类/文件（.artifacts/pro-official/src/sources 下） | 语义 | 我们 Phase1 对应 |
| --- | --- | --- |
| `com/stardust/autojs/ScriptService.java`（BinderC0496 内部类） | :script 宿主 Service + AIDL Stub | 新 scriptproc/ScriptService.java |
| `okhttp3/internal/io/yx0.java` | IScriptServiceInterface（10 tx） | 新 AIDL |
| `okhttp3/internal/io/xx0.java` | IScriptServiceEventCallback（1 tx） | 新 AIDL |
| `okhttp3/internal/io/BinderC6301.java + oz3/nz3` | 主进程事件回调分发（注册表） | Events.java + 广播/回调 |
| `okhttp3/internal/io/bc4.java` | ScriptEngineService 等价物（f3405 单例、f3412 executions、m2413/m2414/m2417/m2418/m2419） | modules/engine 的 ScriptEngineService |
| `org/autojs/autojs/autojs/AutoJs.java:380-450` | 引擎工厂（rhino/nodejs、UI 模式分派） | ScriptExecutionLauncher |
| `com/stardust/autojs/execution/ScriptExecuteActivity.java` | UI 模式 Activity（:script） | 复用/移植 |
| `com/stardust/autojs/execution/ScriptExecutionTask.java / ExecutionConfig.java / ScriptExecutionInfo.java` | AIDL Parcelable | 复用（加 Parcelable） |
| `okhttp3/internal/io/u70.java` | “运行控制条”悬浮窗 | showFloaty 分支 |
| `org/autojs/autojs/flutter/FlutterService.java + nb0.java` | FlutterInterface（:script→主进程路由） | P2 |
| `com/stardust/autojs/core/inputevent/GlobalKeyObserver.java` | 音量键监听/停止 | 搬 :script |
| `com/stardust/view/accessibility/AccessibilityService.java` + `SelectToSpeakService` | 无障碍服务本体（:script） | 搬 :script |
| `com/stardust/autojs/core/accessibility/{AccessibilityBridge,SimpleActionAutomator,UiSelector}.java` + `com/stardust/automator/*` | 自动化 API | 搬 :script |
| `com/stardust/autojs/runtime/ScriptRuntime.java:163-190` | runtime 组件构建 | 不改，注意进程 |
| `com/stardust/autojs/runtime/api/{Engines,Floaty,Dialogs,Images,Timers}.java` | 运行时 API | 不改，进程内 |
| `com/stardust/autojs/core/console/GlobalConsole.java` | log4j 文件控制台 | 不改 |
| `com/stardust/autojs/core/util/Processes.java` | 进程敏感接口（@ProcessMode + asProcessSensitiveInterface） | 可借鉴：assert 主进程禁止调引擎 |
| `org/autojs/autojs/App.java:128-141` | 进程分支初始化 | App.kt 加 :script 分支 |
| `com/stardust/autojs/core/intent/ScriptIntents.java` | Intent 入口 → AIDL run | 复用改造 |
| `com/stardust/autojs/core/timing/receiver/BaseBroadcastReceiver.java + TaskReceiver` | 定时/广播任务触发 | P1 |

## 附录 D：一句话记住 Pro 的架构

**主进程 = Flutter UI + ServiceConnection 客户端；:script = 一个“脚本操作系统”（ScriptService 做 AIDL 门面，bc4/AutoJs 是进程级引擎与自动化单例，ScriptExecuteActivity 是 UI 脚本的宿主，GlobalConsole 写日志，无障碍+截屏+音量键全在里面）；两边用“AIDL 控制面 + 广播数据面 + FlutterInterface 反向路由 + 共享日志文件”连接。**
