# 反编译 Pro 的 Node.js 引擎：Pro 提供 vs 标准 Node vs 我们

> 任务：task-131「Node API 对齐（二）反编译 Pro 的 nodejs 引擎：模块/API/桥暴露清单」。
> 输入样本：`output/auma-base.apk`（sha256 `e0010eafdd7ab8bd204e67ffdca3ec1ac7c1c0a4180ca42311fe8ebe2c0fb31a`，90,619,612 字节）。
> 方法：APK 资产清单（Python zipfile 枚举 3,814 entries）+ Java 反编译（jadx 1.5.6，已存在 `.artifacts/pro-apk/auma-src/sources`，10,968 个类；dex 在 `.artifacts/pro-apk/auma-dex/classes{1..5}.dex`）+ 原生 .so 字符串/JNI 符号扫描。
> 约束：**不改仓库代码**，只产出本报告（另把关键提取物放 `.artifacts/task131-pro-node/` 以便复现）。

## 0. 结论摘要

1. **Pro 的 Node.js 是真实 Node 运行时，不是 JS 模拟层**：`lib/arm64-v8a/libnode.so` 48,835,664 字节；V8 版本串 `9.4.146.24-node.20`；`NODE_MODULE_VERSION = 93` → **Node 16.x（16.14/16.15 线）**。另有一个 1.1MB 的定制 J2V8 原生库 `libv8.so`（110 个 JNI）把 Node/V8 嵌进 App 进程。
2. 启动桥分三段：`assets/v8/v8java.js`（JS 侧 Java 反射桥）→ `assets/v8/v8autojs.js`（构建 `global.$autojs`）→ 原生 `startNodeJS` 拿到 `process/require` → `process.nextTick(require(入口文件))`。
3. Pro 在标准 Node 之上提供 **39 个自定义 JS 模块（31 个顶层 + ui 子包 7 个 + @autojs/opencv.js，合计 553KB 源码）**、**158 个模块 d.ts（1.26MB）**、`@autojs/types-pro9` 全局类型（579KB）、以及带原生 `.node` 的 `@autojs/opencv`。模块导出面最大的是 accessibility(32)/image(32)/app(23)/floating_window(12)/work_manager(12)/dialogs(10)/shell(10)。
4. 引擎/项目标记：首行 flags `ui=1 / auto=2 / nodejs=4 / ui-thread=8 / rhino=16`，`project.json type: "node"` + `features.nodejs: "auto"|"enabled"`；`ui` 模式走 `V8ScriptExecuteActivity`（2,931 行），`ui-thread` 模式走 `f26`（主 Looper 执行），其余后台线程。
5. **我们的 Node24 是真实 Node 24.22.0（V8 13.6.233.17-node.57，ABI 137）**，但跑在 `:node24` 独立进程、原生桥只有 **6 个 JNI**（`libajsnode24bridge.so` 72KB）、JS 侧只有 `prelude.js`(528 行) + `ui.js`(616 行)、宿主桥是**单向 JSON + 25ms 轮询**、只内置 `rhino`/`app`(4 API)/`ui` 三个模块，且入口脚本没有 CJS 包装。差距集中在：**Android API 模块面、Java 互操作深度（反向回调/defineClass/异步）、项目与引擎标记、UI 承载、类型定义**。

## 1. 样本与证据清单

| 项 | 值 |
| --- | --- |
| APK | `output/auma-base.apk`，90,619,612 B，sha256 `e0010eafdd7ab8bd204e67ffdca3ec1a…` |
| package/版本 | `com.boyai.auma`，versionCode `9131100`，versionName `Pro 9.3.11-0`（aapt dump badging） |
| SDK | minSdk 21 / targetSdk 30 / compileSdk 33 |
| 关键 native | `libnode.so` 48,835,664 B；`libv8.so` 1,106,856 B；`libnodexe.so` 5,904 B；`libnpm/libnpx/libyarn/libpnpm/libcorepack` 80~85 B（stub）；`libnode-gyp.so` 179 B |
| 关键 assets | `assets/v8/`（启动 JS + 39 个 built_in_modules + node_modules typings/opencv）；`assets/flutter_assets/assets/project_templates/nodejs/`；`assets/sample/**/Node.js/**` |
| Java 反编译 | `.artifacts/pro-apk/auma-src/sources`（10,968 类），dex `.artifacts/pro-apk/auma-dex/classes{1..5}.dex` |
| 本次提取物 | `.artifacts/task131-pro-node/`（v8java.js、v8autojs.js、autojs-esm-loader.mjs、nodejs 模板、模块导出清单等） |

## 2. Pro Node 运行时与启动链

### 2.1 原生库与 JNI 面

- `libnode.so`：完整 Node core + V8 + libuv + OpenSSL 等（48.9MB）。可读到 `NODE_MODULE_VERSION %d` 错误串、node-gyp 风格的 `"node_module_version": 93` 配置 JSON、V8 版本串 `9.4.146.24-node.20`。**Node 16.x 的 ABI 是 93**，与 Node 16 完全吻合。
- `libv8.so`：1.1MB 定制 J2V8 JNI 层，ELF 里能看到 `libnode.so` 依赖与 `node::` 符号；JNI 符号总数 **110**：
  - `Java_org_autojs_autojspro_v8_PlutoJS__1*` 11 个：`_initNode`、`_initRuntime`、`_init`、`_spinEventLoop`、`_runEventLoopNoWait`、`_drainTasks`、`_emitProcessBeforeExit`、`_emitProcessExit`、`_addALooperFd`、`_removeALooperFd`、`_cs`；
  - `Java_org_autojs_autojspro_v8_j2v8_V8__1*` 约 97 个（J2V8 全量：对象/数组/函数/类型化数组/弱引用/锁/脚本执行/Inspector 等）；
  - `Java_org_autojs_autojspro_v8_api_media_NativeMediaProjection_*` 2 个。
- `node::` 符号证明嵌入方式（`libv8.so` 引用、`libnode.so` 导出）：`CommonEnvironmentSetup`、`CreateEnvironment`、`InitializeNodeWithArgs`、`LoadEnvironment`、`SpinEventLoop`、`StartExecution`、`EmitProcessBeforeExit/Exit`、`SetProcessExitHandler`、`MultiIsolatePlatform`、`CreatePlatform`、`Stop`。
- Android 事件循环整合：PlutoJS 的 `_addALooperFd/_removeALooperFd/_spinEventLoop/_runEventLoopNoWait/_drainTasks` 把 Node 事件循环接到 Android Looper/文件描述符，使原生 UI/线程回调与 Node 事件循环共存。

### 2.2 Java 启动链（关键类与调用顺序）

1. `org.autojs.autojs.autojs.AutoJs$C6634`（`ScriptEngineManager` 工厂，engine name = `"nodejs"`）→ `createEngine()` = `new a26(application, console, LinkedHashMap)`；`createExecution()` 按 source flags 分流：mode 1 → `V8ScriptExecuteActivity`，mode 8 → `f26`（主线程 Looper 执行），否则 `RunnableScriptExecution`（后台线程）。
2. `a26`（`okhttp3/internal/io/a26.java`，568 行）= Pro 的 Node 脚本引擎，implements `V8`/`PlutoJS` 回调：
   - `static f2402 = "v8/built_in_modules"`；静态块初始化 `filesDir/v8`、`filesDir/v8/built_in_modules`、`filesDir/v8/autojs-esm-loader.mjs` 三个目录常量，并挂 `NativeLogger` 到 Console。
   - `init()`：`C0863.m1670(context)` 首次把 `assets/v8` 整目录复制到 `filesDir/v8`（SharedPreferences 版本标记 `built_in_modules_app_version = 9131100`，升级时重解压）。
   - `doExecution(source)`：`m1668(source)` 组装 Node 启动参数 —— 工程 args + `--experimental-loader=<filesDir/v8/autojs-esm-loader.mjs>` + `--no-warnings` + `--trace-uncaught`（debug 追加 `--inspect-brk=0.0.0.0:9229`），然后 `PlutoJS.C6902.m15326(startupScript, args, initCallback, engineCallbacks)`。
   - `m15326`：`ye3`（V8 runtime，ThreadLocal 单实例）→ `startNodeJS(callback)`；callback 里拿到 `process` 与 `require`；随后 `_initRuntime(ptr, v8java.js)`（`n16` 构造 JS 反射桥）→ `V8AutoJsGlobal.BuiltInObjects` 包装成 V8 对象 → `_init(ptr, v8autojs.js)` 构建 `$autojs` 并取回 `__emit` → `mo1667` 设置 `process.execPath='node'`、`process.chdir(workingDir)`、`process.env.NODE_PATH = filesDir/v8/built_in_modules:filesDir/v8/built_in_modules/node_modules`。
   - 启动入口：`process.nextTick(require(入口文件))`；`C0864` 用 `_spinEventLoop` + `checkThread` 推进事件循环并回收错误。
   - `emitJson(event, args)` → `$autojs.__emit(event, args)`（宿主事件通道）；`forceStop()` 中断线程并 `PlutoJS.release()`；`put()` 注入全局对象给 V8。
3. `PlutoJS`（624 行）：java 侧 Node/V8 会话封装；`m15329` 静态初始化 `System.loadLibrary("v8")` + `_initNode(connectivityManager, assets, modulePaths)`；`_initNode` 的 modulePaths 来自 `PlutoJS.f30866`（模块名 → asset 路径 map），初始化后清空；`v8autojs.js` 资产由 `C6904` 从 `assets/v8/v8autojs.js` 读取。
4. `V8AutoJsGlobal`（702 行）：`BuiltInObjects` = 暴露给 JS 的 Java 对象图 —— `androidContext`、`application`、`AccessibilityGlobals`、`ApplicationGlobals`、`UI`、`EngineService`、`ScriptService`、`Imgproc`、`DatastoreManager`、`properties`、`version()`（"Pro 9.3.11-0"）、`isReleaseMode()`；`emit` 函数用于宿主事件。`V8JavaScriptDelegates`（4,877 行 / 约 203 个委托方法）是 Java API→V8 的生成委托层，配合 `V8JavaMethods`/`n16` 把 Java 方法注册成 V8 函数。
5. UI / 线程模式：`V8ScriptExecuteActivity`（2,931 行）承载 `"ui";` 脚本的 Activity/布局；`V8WebView`/`FloatyWindow`/`V8GridView`/`V8RecyclerViewAdapter`/`V8AndroidContext` 提供 UI 控件与上下文；`f26` 承载 `"ui-thread";` 的主 Looper 执行；`V8Promise`/`V8ValueHolder`/`V8EventSource`/`NativeLogger`/`V8GeneratedClass`/`JavaException` 补齐异步/异常/日志/运行时生成类。
6. 项目/模板：`assets/flutter_assets/assets/project_templates/nodejs/` 含 `main.js`（默认 node，示例 require app/toast/ui/accessibility）、`rhino.js`（`"rhino";` 切回 Rhino 的示例）、`package.json`、`project.json`（`type: "node"`、`features.nodejs: "auto"`）与 `node_modules/@autojs/types-pro9`。

### 2.3 引擎/项目标记语义（Pro）

| 标记 | flag | Pro 行为 |
| --- | --- | --- |
| `"ui";` | 1 | 启动 `V8ScriptExecuteActivity` 承载 UI/布局 |
| `"ui-thread";` | 8 | `f26` 在主 Looper 执行（UI 线程/主线程模式） |
| `"nodejs";` / `"auto"` | 4 / 2 | 默认 nodejs 引擎（auto 表示按项目 type 自动选） |
| `"rhino";` | 16 | 同一文件/project 内切回 Rhino 引擎 |
| `project.json type` | — | `"node"` = 项目内所有文件默认 node 模式 |
| `features.nodejs` | — | `"auto"` / `"enabled"` 控制打包/运行是否启用 node |

（对应 Java：`JavaScriptSource` 静态 map 与 `C4439.m12687` 的引擎选择逻辑；`AutoJs$C6634.createExecution` 的模式分流。）

## 3. JS 桥与全局 API

### 3.1 `assets/v8/v8java.js`（12,753 B）— JS 侧 Java 反射桥

- 纯 JS 实现 `$java`（挂在 `$autojs.java`）：`constructors/prototypes` 缓存、`findClass/findClassOrNull`、按 classInfo 安装方法/字段、构造器（含数组型）、内部类、静态成员；
- native 原语：`__callMethod`、`__createJavaObject`、`__classForName`、`__anyThreadWrapper`、`__createValueHolder`、`__defineClass`、`__loadDex`、`__loadJar`；
- 线程模式：`current` / `ui` / `compute` / `ui-sync`（异步模式统一返回 Promise；`createSync` 只允许同步模式）；
- 返回值：`_getReturnValue` 识别 Java 数组/对象，`V8Promise` 自动映射为 JS Promise（`onResolve/onReject` + keepRunning）；
- `$java.defineClass(jsClass, options)`：运行时生成 dex（`__defineClass(className, superclass, interfaces, methods, outputDexFile)`）实现 JS 继承 Java 类；带 `.codecache/*.dex` 缓存；`$java.loadDex/loadJar` 支持动态加载；
- 装箱：`boxInt/boxLong/boxFloat/boxDouble/boxBoolean/...` → `org.autojs.autojspro.v8.api.lang.BoxedValue`。

### 3.2 `assets/v8/v8autojs.js`（7,316 B）— `$autojs` 全局构建

- 签名 `(function($java, built_in_objects, require){...})`，返回并挂到 `global.$autojs`；
- 属性/能力：`$autojs.java`、`$autojs.androidContext`、`$autojs.application`、`$autojs.__internals`（accessibilityGlobals/applicationGlobals/ui/engineService/scriptService/imgproc/datastoreManager）、`$autojs.properties`（Java map 转 Map）、`keepRunning/cancelKeepRunning`（setInterval 引用计数保活）、`__emitter/__emit`（宿主事件入口）、`requireAutoJsVersion`、`isReleaseMode`；
- 版本/环境：`process.versions.autojspro = "Pro 9.3.11-0"`；`process.env.AUTOJS_NATIVE_LIBRARY_PATH = <filesDir>/nodejs_native_libs`；
- Java 扩展：`$java.defineClass`、`$java.loadDex/loadJar`、`$java.findClass/_getReturnValue` 辅助、`console.setLogFilePath(file)`（GlobalConsole.setFile）；
- 事件：`__emit(event, argsJson)` 由 Java `emitJson` 调用，按 JSON 解析后 `EventEmitter.emit`；
- 退出：`process.on('exit')` 打日志。

### 3.3 模块解析机制

- CJS：`process.env.NODE_PATH` 指向 `filesDir/v8/built_in_modules` 与 `.../node_modules`，配合自定义 `require` 搜索路径（`a26.mo1667` 启动脚本里写入），使 `require('app')`、`require('accessibility')`、`require('ui')`、`require('@autojs/opencv')` 等解析到内置模块；
- ESM：`--experimental-loader=autojs-esm-loader.mjs`；loader 把 bare specifier 解析为 `built_in_modules/<specifier>.js`，或 `<specifier>/index.js`，否则交给 Node；
- 模块实现均为 TypeScript 编译后的 CJS（`exports.*`），内部通过 `$autojs.__internals` / `$java` / `require('util')` 等调用 Java 能力；文件头保留中英双语 JSDoc/`@eng` 注释；
- 第三方依赖：`node_modules/axios`（64KB dist）、`follow-redirects`，`@types/node`（仅 typings），`@autojs/opencv`（JS + `prebuilt/opencv4autojs.{arm,arm64}.node`）。

### 3.4 自定义模块清单（39 个 JS + 158 个 d.ts）

| 模块 | 导出数 | 代表 API |
| --- | --- | --- |
| accessibility | 32 | accessibility, click/clickText/longClick, back/home, currentActivity/currentPackage, performGesture(s), inputText, lockScreen, nextEvent, openNotifications/openQuickSettings, StrokeDescription |
| app | 23 | launch/launchApp, getPackageName/getAppName/getInstalledApps/getInstalledPackages, openUrl/openAppSettings, sendBroadcast/sendEmail/registerBroadcastReceiver, startActivity 等价（makeIntent/parseUri/getUriForFile/intentToShell/editFile） |
| clip_manager | 5 | getClip/setClip/hasClip/clearClip/clipboardManager |
| color | 4 | Color/HSVColor/HSLColor/toColorInt |
| convert | 0 | 占位（空模块） |
| datastore | 1 | createDatastore |
| device | 4 | device/Device/OS/BrightnessMode |
| dialogs | 10 | showDialog/showAlertDialog/showConfirmDialog/showInputDialog/showSelectDialog/showSingleChoiceDialog/showMultiChoiceDialog/buildDialog/defaultDialogType/setDefaultDialogType |
| engines | 5 | myEngine/getRunningEngines/execScriptFile/broadcast/stopAll |
| floating_window | 12 | createWindow/canDrawOverlays/manageDrawOverlays + FLAG_* 常量 |
| handler_thread | 2 | HandlerThread/sharedHandlerThread |
| image | 32 | Image/ImageFeatures/FeatureMatcher, decode/encode(Image/Buffer), findColor/detectsColor, findImage 等 |
| java_utils | 7 | toStringOr/toStringOrEmpty/javaListToArray/javaMapToObject/javaSetToArray/objectToJavaMap/bufferToByteArray |
| lang | 6 | lazy/lazyProp/delay/Deferred/TimeoutError/promise |
| lazy_ui_object | 1 | LazyUiObject |
| media | 3 | MediaPlayer/playMusic/scanFile |
| media_projection | 5 | requestScreenCapture/ScreenCapturer/Orientation/ScreenCaptureRequestError/ImageUnavailableError |
| notification | 4 | notify/cancel/requestListeningNotifications/goToNotificationListenerSettings |
| ocr | 2 | createOCR/OCRResult |
| plugins | 3 | load/unload/unloadAll |
| power_manager | 5 | wakeUp/newWakeLock/isScreenOn/isIgnoringBatteryOptimizations/requestIgnoreBatteryOptimizations |
| rhino | 2 | install/Packages |
| root_automator | 2 | createRootAutomator/createRootAutomator2 |
| sensors | 2 | getSensor/SensorDelay |
| settings | 5 | foregroundService/stableMode/noConsoleOnLaunch/stopAllOnVolumeUp/enableAccessibilityServiceByRoot |
| shell | 10 | exec/createShell/isRootAvailable/checkAccess/SendKey/Swipe/Tap/InputText + options |
| toast | 1 | showToast |
| ui_object | 2 | UiObject/Rect |
| ui_selector | 3 | UiSelector/TraversalOrder/NotFoundError |
| work_manager | 12 | addOneTimeTask/addDailyTask/addWeeklyTask/addIntentTask/addActivityIntentTask/addBroadcastIntentTask/query*/remove* |
| zip | 5 | open/unzip/zipFile/zipFiles/zipDir |
| ui (子包 7 个) | activity(4)/index(2)/layout(3)/res(2)/util(1)/view(1)/web(1) | Activity/activityLifecycle/setMainActivity/startActivity；MATCH_PARENT/WRAP_CONTENT；R/defaultThemeContext/inflateXml；imageLoader/loadAndroidResources；isUiThread；Widget；WebView 初始化 |
| @autojs/opencv | (native addon + JS) | opencv4autojs.{arm,arm64}.node + JS 封装/类型 |

另：`assets/v8/built_in_modules/node_modules/` 下有 **158 个 d.ts，合计 1,262,361 B**（其中大多数是 `@autojs/opencv` 的类型；其余模块的 d.ts 与 JS 模块同名）；模板 `@autojs/types-pro9/libautojs9.d.ts` 579,450 B（全局 `$autojs`/`$java`/线程模式/生成类等类型）。

### 3.5 全局 API（`$autojs` / `$java` / Node 标准）

- `$autojs`：`java`（$java 桥）、`androidContext`、`application`、`keepRunning/cancelKeepRunning`、`requireAutoJsVersion(version)`、`properties`、`isReleaseMode`；
- `$java`：`defineClass`、`loadDex`、`loadJar`、`findClass`、`Packages/java/javax/android/androidx/com/org/net/io` 等（由 `v8java.js` 提供）；
- 标准 Node：`fs/path/os/http/https/net/tls/dns/crypto/zlib/child_process/worker_threads/url/querystring/assert/buffer/events/stream/util/vm/module/timers/tty/readline/string_decoder/punycode/v8/perf_hooks/async_hooks/diagnostics_channel/inspector/repl/wasi` 等（真实 Node 16 原样提供）；
- `process.versions.autojspro`、`process.execPath='node'`、`NODE_PATH` 指向内置模块，是 Pro 的定制点。

## 4. 标准 Node 对照

| 维度 | Pro nodejs | 标准 Node（参考） | 我们 Node24 |
| --- | --- | --- | --- |
| 运行时 | Node 16.x（V8 9.4.146.24-node.20，ABI 93） | 用户安装的 Node | Node 24.22.0（V8 13.6.233.17-node.57，ABI 137，uv 1.52.1，OpenSSL 3.5.8；task-130 K40 实测） |
| 形态 | 定制 J2V8 嵌入 App 进程：`libnode.so`+`libv8.so`（110 JNI） | 独立可执行文件 | `:node24` 独立进程：`libnode.so` + `libajsnode24bridge.so`（6 JNI） |
| 标准内置模块 | ✅ 原样（Node 16 集合） | ✅ | ✅ 原样（Node 24 集合） |
| 新 Web API（fetch/WebSocket/structuredClone/AbortController/performance/crypto/WebAssembly 等） | 大多 ❌/实验性（Node 16） | 视版本 | ✅ 全部可用（task-130 实测） |
| 第三方 npm 模块 | CJS/ESM + `NODE_PATH`/ESM loader；ABI 93 的 `.node` | ✅ | CJS/ESM 原生 Node 解析；ABI 137 的 `.node` |
| Android API 层 | 39 个内置模块 + Java 反射桥 + Java 委托 + UI Activity | ❌ | 仅 rhino/app(4)/ui + 单向 JSON 宿主桥 + 轮询事件 |
| 进程/线程模型 | App 进程内 V8：后台线程/主线程（ui-thread）/V8ScriptExecuteActivity | 单线程事件循环 | :node24 服务进程 + Node 事件循环；主进程 Binder 协作 |
| 引擎标记 | ui=1/auto=2/nodejs=4/ui-thread=8/rhino=16 + project type/features | — | 首行 ui/auto/nodejs/node24 解析；无位标志/项目 type/features |
| 类型/文档 | 158 模块 d.ts + types-pro9（579KB）+ 内置 JSDoc | 官方类型 | 无（仅源码与报告） |

## 5. 我们 Node24 现状与差距

### 5.1 现状（证据：task-130 报告 + 本次代码盘点）

- **进程/服务**：`:node24` 独立进程；`Node24Service`（333 行，消息 EXECUTE/RESULT/CANCEL/EVENT，无 legacy application boot）；主进程 `Node24JavaScriptEngine` 经 `Node24Client` Binder 调服务；默认 30s 超时，UI/keepRunning 保活 10min（task-63）。
- **原生**：`libnode.so` Node 24.22.0；`libajsnode24bridge.so` 72KB，仅 6 JNI：`nativeCreate/nativeExecute/nativeExecuteWithHost/nativeCancel/nativeClose/nativeCompiledVersion`；hash-pinned 资产运行时（`assets/source-engine-upgrade/node24/arm64-v8a/runtime.json`），加载前检查没有 legacy `libv8.so/libnode.so`，`Node24Libraries` 负责解压到 `getDir("node24-runtime")`。
- **JS 注入**：`Node24Prelude.wrap()` = 注入两个全局（`__ajsScriptFile/__ajsProjectDir`）+ `assets/node24/prelude.js`（528 行）+ `assets/node24/ui.js`（616 行）+ 用户源码（入口无 CJS 包装）。
- **宿主桥**：`__autojsHost(JSON 字符串) → JSON 字符串` 单向；无 Java→Node 反向调用；事件由 Node `setInterval(25ms)` 调 `__autojsPoll()` 轮询；`Node24HostBridge`（925 行）kind = `java/rhino/app/poll/keepalive/ui/host`，java op = `class/new/static/call/member/field/setField/classOf/context`；`ui` 转 B 的 `NodeUiHost`；`app` 只有 openUrl/startActivity/getPackageName/toast；`$autojs.java.defineClass` 明确抛不支持。
- **`$autojs` 面**：`java/androidContext/projectDir/scriptFile/keepRunning/stopRunning/on/off/pollEvents/registerModule/isRoot`；`require('rhino')`（install/getClass/defineClass[不支持]/Packages）；`require('app')` 4 个方法；`require('ui')`（ui.js，NodeUiHost overlay/Activity 双模式）。
- **标记/项目**：`JavaScriptSource` 识别 `"ui"`/`"auto"` 两个 EXECUTION_MODES，以及 `nodejs`/`node`/`node24` 映射 `ENGINE_NODE24`；**不支持** Pro 的 `nodejs=4/ui-thread=8/rhino=16` 位语义、`project.json type=node`、`features.nodejs`（`ProjectConfig` 无相关字段）。

### 5.2 差距清单（按优先级）

**P0（阻塞常规 Pro Node 脚本）**

1. **Android API 模块面**：Pro 39 个模块（上表）几乎全缺；当前只有 `rhino`/`app`(4)/`ui`。建议按 Pro `built_in_modules` 清单逐项移植（优先 samples 使用频率：app/toast/device/dialogs/floating_window/ui/accessibility/engines/shell/image/media_projection）。
2. **CJS 入口包装**：入口脚本 `module/exports` 未定义（task-130 P0）；`Node24Prelude.wrap` 应加 `(function(module,exports,require,__filename,__dirname){...})`（或 native 注入），与 Node 标准语义/模块化项目对齐。
3. **Java 互操作深度**：Pro 有 Java→JS 回调（V8 delegates/V8Promise/defineClass/生成 dex/线程模式）；我们只有单向 JSON+轮询，无法做 listener/onClick/JS 继承 Java。至少先补常用回调桥（UI 事件、accessibility 事件、Broadcast、`$java.defineClass` 的受限替代），中期评估在 native 桥加 Java→Node 通知通道。
4. **项目/引擎标记**：补 `project.json type=node`、`features.nodejs`、首行组合标记（`"nodejs";`/`"nodejs ui";`/`"ui-thread";`/`"rhino";`）与按文件引擎切换（Pro `C4439.m12687` 逻辑），否则 Pro 项目直接运行会走错引擎/模式。

**P1（UI/示例兼容）**

5. **ui 模块完整度**：Pro `ui/*`（activity/layout/R/res/view/web/Widget/Activity/startActivity/activityLifecycle/MATCH_PARENT/WRAP_CONTENT 等）vs 我们 `ui.js` 的 overlay/Activity 双模式；用 NodeAndroidUI/React/Vue 示例做基准逐项补。
6. **opencv / image / media / accessibility / floating_window / engines / notification / work_manager**：Pro 有原生 opencv `.node`、屏幕捕获 media_projection、无障碍事件、悬浮窗、引擎间通信、定时任务等；我们缺。
7. **`$autojs` 定制点**：`process.versions.autojspro`、`requireAutoJsVersion`、`isReleaseMode`、`console.setLogFilePath`、`AUTOJS_NATIVE_LIBRARY_PATH` 等等价物；`process.execPath='node'`（Pro 覆盖）也可考虑对齐。

**P2（长期/兼容性）**

8. **运行时版本差异**：Pro Node 16（EOL 2023-09）vs 我们 Node 24；ABI 93 vs 137 —— Pro 的第三方 `.node` 不能直接用于我们，需按 Node24 ABI 重编；依赖 Node16 行为的脚本需在 Node24 上验证（新 API 更多，但老语义差异需 shim/测试）。
9. **线程模式语义**：Pro `current/ui/compute/ui-sync` 四线程模式 + V8Promise；我们 :node24 独立进程 + Node 事件循环，UI 走 NodeUiHost overlay；若移植 Pro 模块需要把线程模式映射到我们的 Binder/事件模型（至少文档化差异）。
10. **类型/文档**：Pro 自带 158 模块 d.ts + 579KB 全局类型；我们目前无 .d.ts；搬 modules 时一并搬 typings 可提升 IDE/示例兼容性。

## 6. 复现方法与证据路径

- APK 元信息：`C:\Android\build-tools\34.0.0\aapt.exe dump badging output/auma-base.apk`。
- 资产枚举/提取：Python `zipfile`（脚本思路见本报告；本次提取物在 `.artifacts/task131-pro-node/`）。
- Java 反编译：`.artifacts/jadx-toolchain/run-jadx.ps1`（jadx 1.5.6 all jar）；已有输出 `.artifacts/pro-apk/auma-src/sources` 与 `.artifacts/pro-apk/auma-dex/classes{1..5}.dex`。
- 原生：Python 读字节 + 正则扫 `node v\d`、`NODE_MODULE_VERSION`、`V8 版本`、`Java_org_autojs_autojspro_v8_*`、`_ZN4node*`（脚本在 `.artifacts/task131-pro-node/scan-notes.txt` 概述）。
- 我们 Node24 基线：`docs/reports/2026-10-18_Node24_API现状与缺口.md`（task-130，K40 实测 Node v24.22.0/ABI 137/标准模块全可用）。
- 关键 Pro 文件：`assets/v8/v8java.js`、`assets/v8/v8autojs.js`、`assets/v8/autojs-esm-loader.mjs`、`assets/v8/built_in_modules/**`、`assets/flutter_assets/assets/project_templates/nodejs/**`；Java：`okhttp3/internal/io/a26.java`、`org/autojs/autojspro/v8/{PlutoJS,V8AutoJsGlobal}.java`、`org/autojs/autojspro/v8/j2v8/V8.java`、`org/autojs/autojspro/v8/util/V8JavaScriptDelegates.java`、`org/autojs/autojspro/v8/api/ui/V8ScriptExecuteActivity.java`。

## 7. 未决/限制

- `assets/v8/post.js` 只有 29 字节（`BQS?8F#ks-…` 加密/二进制串），未能在静态阶段还原其用途（推测为 native loader/解密的片段），需要运行时抓取或 native 反汇编确认。
- `libnodexe.so`（5.9KB）未展开分析；`libaapt2.so/libapp.so` 属 Flutter/AAPT 工具链，不在 Node 链路。
- Pro Node 精确 patch 版本只能推断（V8 9.4.146.24-node.20 + ABI 93 → Node 16.14/16.15 线）；Node 16 内多个 patch 的 V8 相同，静态无法唯一定位。
- 反编译 Java 名被 jadx 重命名/混淆（如 `a26`/`ye3`/`n16`/`f26`/`C6634`），报告以“类职责”为准；`PlutoJS.f30866` 的模块 map 具体填充由 native `_initNode` 完成，静态只能看到 map 消费端与 `_initNode(..., modulePaths)` 参数。
- `V8ScriptExecuteActivity`（2,931 行）的 UI/布局细节本轮只到“承载 UI 模式”的层面，逐 API 行为需后续真机/对照测试。

## 8. 对下一步（Node API 对齐二/三）的建议顺序

1. 先搬 `built_in_modules` 中不依赖 UI/Java 回调的纯逻辑模块（lang/java_utils/color/zip/toast/app 扩展/shell 基础），并补 d.ts；
2. 再补 CJS 入口包装与项目/引擎标记（type=node/features/位 flags），让 Pro 项目能跑到正确引擎；
3. 按优先级补 UI/设备/无障碍/媒体/悬浮窗模块，同时设计 Java→Node 回调通道（或先用轮询事件补齐常用 listener 语义）；
4. 用 Pro 自带 samples（`assets/sample/**/Node.js/**`）做逐目录冒烟，记录“通过/报错/缺失 API”，作为对齐验收表。
