# xiangqi-aj-node 0.6.80 Node 入口兼容性分析（task-197）

日期：2026-10-20。执行：t1-native-services。验证机：K40 cccc62c7，dev-1141/1142；工程：/sdcard/脚本/xiangqi-aj-node。

## 1. 目标

用户提供的第三方工程 `xiangqi-aj-node-0.6.80`（AJ Pro v172 兼容版，Node16 UI 入口）在 K40 dev-1140 运行 `AJ-Node启动.node.js` 即失败：`TypeError: Cannot read properties of undefined (reading 'projectConfig')` at src/ui/aj-node-main.node.js:48（`require("engines").myEngine().executionConfig` 不存在）。

## 2. 已实现兼容（dev-1141/1142）

### 2.1 engines.myEngine().executionConfig（越过 line 48）

`prelude.js` 的 `engines.myEngine()` 返回对象新增：
- `executionConfig.projectConfig`：从入口目录 `project.json` 解析（工程有 project.json → 直接真值，脚本跳过 Gson/setProjectConfig 反射分支）；
- `executionConfig.projectDir`、`getProjectDir()/getProjectConfig()/setProjectConfig()/setProjectDir()`；
- `executionConfig.getClass().getMethods()`（含 setProjectConfig 的 `com.stardust.autojs.project.ProjectConfig` 参数类型，兜底兼容）；
- `myEngine().getClass().getName()`、`getSource()/getName()`、`source/execArgv`。

验证：dev-1141 跑入口不再有 projectConfig 错误，越过 line 48。

### 2.2 工程资源 res/ 缺失（非引擎问题）

下一个阻塞点是 `ui.loadAndroidResources()` 返回 `C-RES ok=false：找不到资源目录 /storage/emulated/0/脚本/xiangqi-aj-node/res`。检查发现**设备上的工程副本缺 res/**（只有 project.json/src/assets；本地解压包 output/xiangqi-aj-node-0.6.80-unzip 及 aj-build-manifest 都有 res/layout/xiangqi_main.xml 等 9 个文件）。从 zip 推送 res/ 后继续。

### 2.3 后续真实阻塞点与修复

1. `MainActivity.onCreate`：`this.insetsController.setAppearanceLightNavigationBars(...)` → `ui.Activity` 无 insetsController；已加 no-op marker 兼容；
2. `src/ui/aj-node-panel.node.js:197`：`$autojs.java.findClass("[I")` 被「必须含包名」校验拒绝，且数组类无构造器；已允许数组描述符 `[I`，宿主 `newInstance` 支持 `java.lang.reflect.Array.newInstance(type.getComponentType(), length)`；
3. dev-1142 实测：**Node 原生主界面成功渲染**（NodeUiActivity，layout=xiangqi_main，binding 50+ 控件，标题象棋助手，M3 样式部分 fallback），`C-VIEWS` 全量 binding 正常。

## 3. 下一个真实阻塞点

`src/ui/aj-node-panel.node.js:243`：
```js
window = floating.createWindow(...);
if (options.createView) window.setView(options.createView());
else window.setViewFromXml(layout());
```
→ `TypeError: window.setView is not a function`。

原因：本项目的 floating_window 浮窗由主进程 NodeUiBridgeService 按 **XML/文本** 渲染；工程传入的是 `android.view.LayoutInflater.from(themeContext).inflate(ui.R.layout.xiangqi_floaty, null)` 得到的**真实 View**（:node24 进程内），跨进程无法直接挂到主进程浮窗上，因此没有 setView(View) 能力。

## 4. 缺口清单（按阻塞顺序）

| # | 缺口 | 影响 | 难度/建议 |
|---|---|---|---|
| 1 | `floating_window.setView(View)` / 真实 View 绑定 | 进入游戏后的小窗无法显示 | 高：需要跨进程 View 渲染或改工程用 setViewFromXml；短期建议工程把浮窗改为 XML 字符串（setViewFromXml）或复用 ui.Activity |
| 2 | `window.view` 真实 View 方法（getLocationOnScreen/measure/getWidth 等） | 小窗几何/拖动/隐藏确认 | 中：可在 NodeUiBridge 增加窗口几何命令，或工程改用宿主提供的 overlay 命令 |
| 3 | `host.runtime.getAndroidClassLoader()/loadDex()`、`host.java` 命名空间 | NativeEngine.dex / WindowDrag.dex / NativeYolo.dex 动态加载、JNI 桥 | 高：AJ Pro 私有 host runtime；可在 QuickJS 工作台路径继续，或由宿主提供等价 loadDex/getClassLoader API |
| 4 | 工程 bundled `com.jdkshen.xiangqi.plugin.NativeEngine`（23MB SO + dex） | 后台 Pikafish/YOLO 走 JNI | 需先把 #3 的 loadDex/getClassLoader 打通；或继续用 main.js QuickJS 后台 |
| 5 | project res 样式完整度（XiangqiTheme/XiangqiCard/Material3 Button 等） | 视觉与 Pro 有差异（当前有 fallback 警告） | 中：补齐 DynamicLayoutInflater 的 style/attr 解析 |
| 6 | WindowInsetsController 实际行为 | 全屏/状态栏外观 | 低：目前 no-op 兼容 |
| 7 | Material 3 Activity 主题 `getTheme().applyStyle(...)` | 主题细节 | 低：marker no-op |

## 5. 替代建议

1. **优先 main.js QuickJS 工作台入口**：同工程 main.js 在 K40 dev-1140 已验证可正常启动，功能完整（识别/自动走棋）；Node 入口仅负责原生面板，短期不必强求。
2. 若必须 Node 面板：让工程把浮窗 `createView` 改为 **XML 字符串**（`setViewFromXml`），避免跨进程 View；或把「进入游戏」后的浮窗也做成第二个 NodeUiActivity。
3. 后台 NativeEngine/YOLO dex 依赖 AJ Pro 私有 host runtime；建议第三方工程保留 QuickJS/Rhino 后台，Node 仅做纯 JS UI（面板已可渲染，可作为过渡）。
4. 工程交付需带齐 `res/`（0.6.80 zip 中是有的），本次设备副本缺 res/ 导致走了资源加载错误；请用完整解压包。

## 6. 修改文件

- modules/engine/src/main/assets/node24/prelude.js（executionConfig/getClass/数组描述符）
- modules/engine/src/main/assets/node24/ui.js（insetsController no-op）
- modules/engine/src/main/java/org/autojs/source/engine/android/Node24HostBridge.java（Array.newInstance）

编译：`:engine + :app(-PflutterPoc=true)` BUILD SUCCESSFUL（15s）；prelude/ui node --check 通过。

## 7. 证据

- `.artifacts/task197-aj-node/run1-logcat.txt`（line48 越过后 res 缺失）
- `.artifacts/task197-aj-node/run2-logcat.txt`（res 推送后主界面渲染 + insetsController/数组类错误）
- `.artifacts/task197-aj-node/run3-logcat.txt`（dev-1142 主界面完整渲染 + setView 阻塞）
- `.artifacts/task197-aj-node/main-ui.png`（象棋助手主界面截图）

---

## 8. task-198 续：host.runtime 与 setView 降级（dev-1143）

### 8.1 host.runtime（已实现，PASS）

prelude 注入全局 `host` 兼容对象：
- `host.java.lang.Class.forName(name, initialize, loader)`（走我们的 findClass）；
- `host.runtime.getAndroidClassLoader()` → 应用 classloader；
- `host.runtime.loadDex(path)` → Node24HostBridge 用 `dalvik.system.DexClassLoader` 动态加载 dex，并让后续 findClass 优先从动态 loader 解析插件类；
- `host.context` / `host.Packages` / `host.__xiangqiProjectDir`。

K40 dev-1143 probe（n198-host-runtime.node.js）结果：
```
HOST_TYPE object / RUNTIME_TYPE object
LOADER ok:dalvik.system.PathClassLoader
LOADDEX_WINDOWDRAG true
CLASS_WINDOWDRAG_OK com.jdkshen.xiangqi.ui.WindowDrag
LOADDEX_NATIVEENGINE true
CLASS_NATIVEENGINE_OK com.jdkshen.xiangqi.plugin.NativeEngine
execute returned exitCode=0
```

### 8.2 floating_window.setView（明确降级）

- 字符串/带 `xml` 的对象 → `setViewFromXml`；
- 真实原生 View（包内 `LayoutInflater.inflate(ui.R.layout.xiangqi_floaty)` 得到的 :node24 View）→ 明确中文错误：
  `floating_window.setView(原生 View) 暂不支持：当前浮窗由主进程按 XML/文本渲染，:node24 里的真实 View 无法跨进程挂载；请改用 window.setViewFromXml(xml)，或把该界面放到 ui.Activity / NodeUiActivity 中显示`；
- 不再抛 TypeError；`MainActivity.connect().catch` 捕获后主界面继续可用。

### 8.3 当前状态 / 下一个阻塞点

- Node 原生主界面仍正常渲染（NodeUiActivity layout=xiangqi_main，标题象棋助手）；
- 下一个阻塞点仍是浮窗：**不能把 :node24 的真实 View 挂到主进程 overlay**。可行方向（按工作量）：
  1. 工程改用 `setViewFromXml`（本项目 overlay 已支持 XML inflate + 控件 id/binding + touch/click 事件）；
  2. 拦截 `ui.R.layout.*` / `LayoutInflater.inflate` 返回布局描述（layout 名），宿主 overlay 读取工程 `res/layout/*.xml` 在**主进程** inflate（复用 NodeUiActivity 的 dynamic inflater）——推荐的中期方案；
  3. 在 `:node24` 进程内自建 WindowManager overlay 直接 attach 真实 View（需重写事件/poll/几何/关闭生命周期，工作量最大）。
- 若浮窗打通，后续仍会出现：`window.view` 真实 View 几何命令（getLocationOnScreen/measure）、NativeEngine JNI dex/SO 初始化、project res 样式完整度。

### 8.4 证据

- `.artifacts/task198-verify/runtime-probe.txt`（host.runtime PASS）
- `.artifacts/task198-verify/aj-run.txt`（setView 明确降级、主界面渲染）
- `.artifacts/task198-verify/main-ui.png`

---

## 9. task-199：浮窗真实 View → 工程布局 XML 回退（dev-1145~1148）

### 9.1 实现

1. `ui.js` 访问 `ui.R.layout.<name>` 时记录 `__ajsLastLayoutName`；
2. `floating_window.setView(real View)`：真实 View 无法跨进程挂载，自动读取工程 `res/layout/<name>.xml` 并走现有主进程 XML overlay（views/binding/触摸/click/enableDrag 全链路复用）；
3. `window.view` 根视图补 `setVisibility/getWidth/getHeight/getLocationOnScreen` 安全兼容（setVisibility 纯记录，不重入 show/hide，避免与刚完成的 show 竞态 15s 超时）；
4. NodeUiBridgeService overlay XML 分支主题从 `Theme_Material_Light_NoActionBar` 改为 `NodeUiHost.wrapThemedContext(this,null)`（Material3），修复 MaterialCardView InflateException。

### 9.2 K40 验证（dev-1148）

- 面板 start 不再报 `floating_window 命令超时`；
- **浮窗成功出现**：`overlay show id=overlay-1`，C-VIEWS 全量 id（dragHandle/expand/status/start/stop/details/locate/recognize/captureScreen/tap/mode/side/searchMode/searchBudget/newSession/openMain/feedback/logs/close），黑色面板显示「象棋助手/等待连接/后台连接中…/开始/停止/更多」，主 Activity 同步显示完整象棋助手页；
- 触摸链路已确认：`C-OVERLAY touch overlay=overlay-1 action=0/1 raw=...`（adb tap/swipe 均进入 overlay）；
- 工程样式（@style/XiangqiTonalButton 等）在 inline inflater 下有 fallback 警告，但控件正常创建。

### 9.3 下一个阻塞点

1. 浮窗按钮/拖动尚未生效：触摸已进入 overlay，但点击未在 JS 侧看到对应动作（更多按钮点击后面板不展开/不变），拖动帧不动。下一步排查：
   - main 侧 `findClickableViewId` 命中 id 与 C-VIEWS 的合成 id 是否一致；
   - prelude `pollEvents` 是否把 click 事件派发到 `_viewHandlers`（需要临时日志确认）；
   - `enableDrag` 的 OVERLAY_DRAG_ANCHORS 是否在 show 后正确设置（拖动起始点需落在 dragHandle 行内）；
   - 内容高度变化（详情展开）时 overlay window 未 relayout/remeasure；
2. 面板 JS 已进入前端逻辑（「后台连接中…」），但后台 Rhino `AJ后台.js` 尚未启动/连接（工程 panel-bus 协议），下一步需要串起后台引擎/文件总线；
3. 后台启动后才会进入 NativeEngine JNI/SO、YOLO dex/SO、@style/@color 完整解析等。

### 9.4 证据

- `.artifacts/task199-verify/dev1148-run.txt`（start 无超时、overlay show、C-VIEWS）
- `.artifacts/task199-verify/dev1148-screen.png`（浮窗面板 + 主界面）
- `.artifacts/task199-verify/w3.txt`（overlay frame [36,280][861,695]，fl=NOT_FOCUSABLE/NOT_TOUCH_MODAL 可触摸）
- `.artifacts/task199-verify/after-more2.png`、`after-start.png`（按钮点击后截图）


---

## 10. task-200：浮窗交互诊断（dev-1149/1150）

### 10.1 诊断结果

- 点击命中 id 正确：`C-OVERLAY click overlay=overlay-1 hitView=20161263`（expand）、`hitView=20161215`（start），与 `C-VIEWS` 的合成 id 完全一致；
- 触摸链路正确：`C-OVERLAY touch action=0/1`；
- 但工程大 XML（xiangqi_floaty.xml，含 MaterialCardView/MaterialButton/ScrollView）的 `window.show()` 会在 **15s 后抛 `floating_window 命令超时`**，同时 main 已打出 `overlay show` 且浮窗可见；
- 因 show 超时，`_showNow` 后续的 `enableDrag` 和 `clickNames setClickable` 未执行 → 没有 `C-OVERLAY enableDrag`/`viewCommand` 日志，所以「更多/开始」无 JS 反应、拖动无效；
- 对照悬浮日志示例（小 XML）：show → viewCommand setClickable → enableDrag 全链路正常，说明问题集中在**工程大 XML overlay 的 show 回复/latch 超时**（main 已创建窗口，client 未收到回复）。

### 10.2 已尝试修复

- 增加 `LAST_OVERLAY_INFO` + overlay action `info`：show 超时后 prelude 用 `overlayCall('info')` 找回 overlayId/views；
- 实测 `info` 同样会 15s 超时（同一 reply/latch 问题），panel.start 仍走 catch，enableDrag/click 预处理仍不执行。

### 10.3 下一个阻塞点 / 建议

1. 需要让工程 XML overlay 的 show/命令不依赖 15s 同步 reply：
   - 方案 A：overlay show/viewCommand 改为**异步命令**（main 处理完写共享文件/EventBus，prelude 轮询文件取 views/结果）；
   - 方案 B：把工程 XML overlay 移到 `:node24` 进程内自建（同进程可拿到真实 View，无跨进程 latch）；
   - 方案 C：定位 NodeUiActivityClient reply 丢包（工程 XML 特有，可能是 show 处理耗时/回复线程阻塞），修 client 侧；
2. 交互打通后：Rhino `AJ后台.js`/panel-bus、NativeEngine JNI/SO、YOLO dex/SO、@style/@color 完整解析。

### 10.4 证据

- `.artifacts/task200-verify/dev1149-run.txt`（click hitView 与 C-VIEWS 一致、show 超时）；
- `.artifacts/task200-verify/dev1150-start.txt`、`dev1150-late.png`；
- `.artifacts/task200-verify/floatlog-test.txt`（小 XML 示例全链路正常）。

---

## 11. task-202：Rhino 后台 / NativeEngine 探针（dev-1150）

### 11.1 Rhino 后台 / panel-bus

- Node UI 的启动方式（工程 `aj-node-panel.node.js:349`）：`engines.execScriptFile(root + '/AJ后台.js', {arguments:{nodeFrontendId, projectDir, nodeHeadless:true}})`；
- 实测我们的 Node 端 `engines.execScriptFile` 会按脚本头 `// @engine quickjs` 启动 **QuickJS 子引擎**：
  `QuickJsEngine ... TypeError: 'window' is read-only` at `AJ后台.js:15`（`node-headless-window.js install` 给全局 `window` 赋值），后台立即结束；
- `engines.all()` 只看到当前 Node 引擎（id=0），QuickJS 子引擎未出现在 all 里（API 差异）；
- `.aijspro-floaty/bus-state-floaty.json` / `node-backend.log` 里只有 **2026-10-14 旧设备/旧工程** 的数据，说明 panel-bus 文件总线本身格式存在、但本次后台从未成功连上；
- **阻塞点**：需要让 `AJ后台.js` 跑在 QuickJS 子引擎时 `window` 可写（我们的 QuickJS prelude 把 window 设为只读），或提供 Rhino/QuickJS 子引擎选择；否则 panel 「已连接/定位/识别/算棋」都不会发生。

### 11.2 NativeEngine / NativeYolo / WindowDrag dex+SO

probe（Node24，K40）结果：
- `host.runtime.loadDex(NativeEngine.dex)` → true；`Class.forName('com.jdkshen.xiangqi.plugin.NativeEngine')` OK，`bridgeVersion()=2`；
- `NativeEngine.loadProject(dir, codeCache, filesDir, 'arm64-v8a', soSha, nnueSha)` → **true**；`libraryPath=/data/user/0/.../code_cache/xiangqi-engine/<sha>/arm64-v8a/libxiangqi_engine.so`，`nativeloader` 日志确认 SO 用 isolated clns 加载成功；`evalPath=.../files/xiangqi-engine/<sha>/pikafish.nnue` 拷贝成功；
- `WindowDrag.dex` 加载 + `com.jdkshen.xiangqi.ui.WindowDrag` Class OK；
- `NativeYolo.dex` 加载 + `com.jdkshen.xiangqi.yolo.NativeYolo` Class OK，`bridgeVersion()=1`；`nativeloader` 确认 `libonnxruntime.so`、`libxiangqi_yolo.so` 均加载成功；
- **阻塞点 1**：`NativeEngine.nativeAnalyze(...)` 在 loadProject 后直接调用返回 `error: engine-not-started`；dex 方法表含 `nativeStart/nativeStop/nativeQuit/nativeIsRunning/nativeConfiguredThreads/nativeOptions`，需先调 `nativeStart()`（本次 probe 因反射 Class 列表失败未验证到 start 后分析）；
- **阻塞点 2**：`NativeYolo.loadProject(...)` 返回 false 且无 `loadError()` 方法（dex 只有 bridgeVersion/loadProject/nativeCreate/nativeDetect/nativeClose/loadedIdentity/runtimeVersion）；需在 loadProject 后读 `runtimeVersion()/loadedIdentity()` 判断真实状态，或 nativeCreate 时传 onnx 路径；
- **阻塞点 3**：Java 反射辅助不足：`host.java.lang.Class.forName` 返回 classRef 代理，不能调 `getMethods`；用 `loader.loadClass` 走的是应用 classloader，找不到动态 dex 类。需要新 bridge API（如 `runtime.methods(className)`）来列出 NativeEngine/NativeYolo 方法签名，验证 `nativeStart` 参数。

### 11.3 证据

- `.artifacts/task202-verify/native-probe.txt`（NativeEngine loadProject + SO/nnue + engine-not-started）
- `.artifacts/task202-verify/backend-probe2.txt`（QuickJS 子引擎 window read-only、bus 旧状态）
- `.artifacts/task202-verify/engine-probe.txt`（execScriptFile QuickJS 选择）
- `.artifacts/task202-verify/n202-native-probe2.node.js`（反射/start 探针，待 bridge API 补齐）
