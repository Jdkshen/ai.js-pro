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
