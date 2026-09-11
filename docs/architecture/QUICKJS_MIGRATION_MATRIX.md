# QuickJS 迁移与 Rhino 移除门禁

更新日期：2026-09-11

设备：Mi8 `ce4d2bdb`（LineageOS/Android 15）为基线机，回归 209 项全绿；主力机 `DQKFJB59596L45BI`（Android 16 / SDK 36）上一轮同一脚本 177 项全绿（OpenCV / Java 互操作 / 悬浮窗批A 增项待复跑）；K40 `cccc62c7` 跑 compat/lite 与 Rhino 兼容回归。

## 已自动验证

| 领域 | 当前状态 | 设备覆盖 |
| --- | --- | --- |
| 引擎选择 | 新建默认 QuickJS，无标记旧脚本保持 Rhino | compat/lite K40 通过 |
| files、timers、shell | QuickJS 真实调用通过 | compat/lite K40 通过 |
| app、storages、device、dialogs | QuickJS 基础桥接通过 | compat/lite K40 通过 |
| engines、threads、events | worker 参数/返回值与同步事件通过 | compat/lite K40 通过 |
| 选择器 / UiObject | `selector()`/`text()`…全局 + `find/findOnce/findOne/untilFind/untilFindOne/exists/waitFor` + 控件属性/动作/树访问全部通过（无障碍服务已连接时） | Mi8（回归测试 76 项全绿） |
| 手势与输入 | `gesture/gestureAsync`、`gestures/gesturesAsync`（含真实滑动）、`input`、RootShell 助手（`KeyCode/Tap/Swipe/Screencap/Text` 与按键全局）全部通过 | Mi8（回归测试 104 项全绿） |
| 内置模块 | `crypto`（md5/sha256/hmac/base64 固定向量）、`zips`（真实压缩/列表/解压往返）、`sqlite`（建表/增删改查 + 事务提交与回滚）、`util`、`automator`、`context`、`rawInput` 与 `require('crypto')` 全部通过 | Mi8（回归测试 121 项全绿） |
| 运行时状态 | `isRunning`/`isStopped`/`notStopped`/`stop`/`isShuttingDown`/`loop`/`requiresApi`/`requiresAutojsVersion` 语义与异常路径通过 | Mi8（回归测试 121 项全绿） |
| 控制台浮窗 | `openConsole()`/`clearConsole()` 与 `console.show/hide/clear/setTitle/setSize/setPosition` 调用通过，`dumpsys window` 实测浮窗 700×600、`SYSTEM_ALERT_WINDOW` 存在 | Mi8（回归测试 121 项全绿） |
| 选择器 JS 谓词（Java → JS 回调） | `filter(fn)`/`addFilter(fn)` 的回调计数、`false` 谓词返回空集合、`findAndReturnList(node, 5)` 返回带 `size()` 的列表均通过 | Mi8（回归测试 133 项全绿） |
| io / 文本文件 | `files.open` 写入/读取/追加/未知模式 null + 全局 `open` 与 `io` 模块均通过（用例先 `files.createWithDirs`；Rhino 的 `files.write` 同样不自动建目录） | Mi8 + 新机 Android 16（回归测试 177 项全绿） |
| web / 跨线程回调 | `newInjectableWebView()` 加载 data URL 后 `inject(script, callback)` 拿到页面里的值；`rhino.call/eval` 的任务分发与 sleep 期间的跨线程回调均通过 | Mi8（回归测试 143 项全绿） |
| continuation | `delay` 阻塞等待、`create/await` 与 `Promise.await` 的明确报错、`enabled === false` 均通过 | Mi8（回归测试 143 项全绿） |
| Java 互操作 | `Packages`/`importClass`/`importPackage`/`Java.type`、静态字段与方法、`new` 构造、实例字段读写、JavaBean 属性（`file.path`/`context.packageName`）、嵌套类（`android.os.Build.VERSION.SDK_INT`、`java.lang.Thread.State.NEW`）、`obj.getClass()` 返回可用 `java.lang.Class`（`getName()`/`getSimpleName()`/`String(cls)`）、重载解析（`String.valueOf(42)` 选 int）、Java 异常转 Error、Java 数组返回值、JS 数组作可变参数；`context` 为真实 Android Context；Rhino 预导入的 10 个类名全部可用 | Mi8 + 新机 Android 16（回归测试 188 项全绿） |
| OpenCV / 图色 | `images.opencv` 类映射（Mat/Core/Imgproc/CvType/Scalar/Size/Point/Rect/Bitmap/BitmapFactory）与 OpenCV Java API 直连（`new Mat(w,h,CV_8UC1)` + `Imgproc.threshold`）；帧→`Mat`→帧往返、`inRange`/`interval`/`adaptiveThreshold`/`gaussianBlur`/`medianBlur`/`findCircles`/`findAllPointsForColor`、`toBytes`/`fromBytes` 往返、`readPixels` 全部通过（无截图权限时用例自动改用 OpenCV 合成帧，不依赖授权） | Mi8（回归测试 184 项全绿，`images` 成员 42 ⊇ Rhino 39） |
| UI | 布局创建、文本更新/回读、`ui.<id>`/`$ui.<id>` 控件代理、任意属性 `attr` 读写、`ui.emitter`/`ui.findView`/`ui.post`/`ui.isUiThread`/`ui.statusBarColor` 通过（`ui` 为覆盖层模式，非 Rhino 的 UI Activity） | Mi8（回归测试 162 项全绿） |
| floaty | 真实创建、位置/尺寸/文本更新、关闭通过 | compat/lite K40 通过 |
| Rhino 兼容 | 23 项基础回归通过；lite 可控拒绝且不崩溃 | K40 通过 |

运行命令：

```powershell
.\tools\test-engine-flavors-device.ps1 -Serial cccc62c7
```

## 尚未达到移除条件

| 领域 | 硬阻塞 |
| --- | --- |
| 手势与输入 | `gesture*`/`input`/根助手已对齐；`rawInput`/`Input`/`KeyEvent` 类注入与 `automator` 模块已随完整 Java 反射补齐，旧脚本无需改写法 |
| 模块 | `plugins` 尚未迁移（插件 SDK 依赖 Rhino scope）；`images`/OpenCV（帧↔Mat 桥、`images.opencv`、`inRange`/`interval`/`adaptiveThreshold`/`gaussianBlur`/`medianBlur`/`findCircles`/`findAllPointsForColor`/`toBytes`/`fromBytes`/`readPixels`）已对齐，`crypto`/`zips`/`util`/`automator`/`context`/`rawInput`/`sqlite`/`io`/`web`/`continuation` 已对齐 |
| UI | QuickJS 是 overlay 实现（`ui.layout` 全屏覆盖层 + `ui.<id>` 代理 + `ui.emitter`），未覆盖 Rhino 的 UI Activity 模式与 JSX/动态绑定 |
| floaty | XML/文本窗口、控件代理（含 `attr` 与属性式读写、`click()/click(fn)`、`on("click"/"long_click"/"key"/"touch")`）、窗口 `getWidth/getHeight/findView`、不存在控件返回 undefined 均已对齐，且窗口级 `show/hide/setVisibility/isShown/setContentVisible` + `setAlpha/setScale` + `{x,y,visible:false}` 创建即定位、`javaView` 真句柄 + `animate()` 系统动画 + `runOnMainThread`、**进程级窗口注册表 + `windowById`/`post` + `attached`/`detached` 生命周期** 已补齐（需求清单批A/B/C）；与 Rhino 的差异只剩 XML 单位提示（批D）与 overlay 层级限制 |
| web | `http` 与 `newInjectableWebView/Client` 的 `inject`/`loadUrl`/`loadData` 已可用；页面→脚本的 `rhino.call/eval` 为异步回调（引擎线程执行，`injectAndWait` 不支持） |
| debugger | 现有 Dim 调试器是 Rhino 专用，QuickJS 尚无断点、单步、变量查看等价实现 |
| Java 桥接 | 已按用户拍板放开为完整 public 反射（与 Rhino 一致）；白名单 host bridge 仅作为内部默认实现保留，不再是安全边界 |
| 编辑器 | Token/AST、高亮、自动完成和部分异常仍直接使用 Rhino 类 |
| 运行时 | `ScriptRuntime`、`TimerThread`、UI 代理、continuation 仍有 Rhino 具体类耦合 |

## 样例约束

- `QuickJS 新引擎` 目录下的每个 `.js` 必须以 `// @engine quickjs` 作为第一条非空行。
- 每个样例统一头部：`// @engine quickjs` → 标题 → `// 用途：…` → `// 前置：…`（权限/依赖）→ `// 覆盖：…`（涉及的模块，由规范化脚本按内容生成）。
- 依赖 E4X、旧 `"ui"` 模式或 Rhino 对象代理的样例归入 `Rhino 引擎`（QuickJS 已支持任意 Java 反射，但仍无反式写法）。
- Gradle `verifyQuickJsSamplesMarked` 在每次 `preBuild` 阶段阻止未标记样例再次混入。

只有上述阻塞项完成、compat/lite 设备矩阵持续通过，才能从正式版移除 Rhino。
