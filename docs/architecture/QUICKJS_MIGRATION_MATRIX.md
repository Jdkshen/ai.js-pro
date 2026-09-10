# QuickJS 迁移与 Rhino 移除门禁

更新日期：2026-09-11

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
| io / 文本文件 | `files.open` 写入/读取/追加/未知模式 null + 全局 `open` 与 `io` 模块均通过 | Mi8（回归测试 133 项全绿） |
| web / 跨线程回调 | `newInjectableWebView()` 加载 data URL 后 `inject(script, callback)` 拿到页面里的值；`rhino.call/eval` 的任务分发与 sleep 期间的跨线程回调均通过 | Mi8（回归测试 143 项全绿） |
| continuation | `delay` 阻塞等待、`create/await` 与 `Promise.await` 的明确报错、`enabled === false` 均通过 | Mi8（回归测试 143 项全绿） |
| UI | 布局创建、文本更新/回读、关闭通过 | compat/lite K40 通过 |
| floaty | 真实创建、位置/尺寸/文本更新、关闭通过 | compat/lite K40 通过 |
| Rhino 兼容 | 23 项基础回归通过；lite 可控拒绝且不崩溃 | K40 通过 |

运行命令：

```powershell
.\tools\test-engine-flavors-device.ps1 -Serial cccc62c7
```

## 尚未达到移除条件

| 领域 | 硬阻塞 |
| --- | --- |
| 手势与输入 | `gesture*`/`input`/根助手已对齐；仅剩 `rawInput`、`Input`/`KeyEvent` 类注入与 `automator` 模块未迁移，依赖这些的旧脚本需改写法 |
| 模块 | `plugins` 尚未迁移（插件 SDK 依赖 Rhino scope）；`crypto`/`zips`/`util`/`automator`/`context`/`rawInput`/`sqlite`/`io`/`web`/`continuation` 已对齐，控制台浮窗与选择器 JS 谓词（`filter`/`addFilter`/`findAndReturnList`）也已对齐 |
| UI | QuickJS 是最小化 overlay 实现，还未覆盖 Activity 模式、完整控件属性和复杂列表交互 |
| floaty | 基础窗口已可用，但与 Rhino XML 窗口对象、控件代理和事件 API 尚未完全等价 |
| web | `http` 与 `newInjectableWebView/Client` 的 `inject`/`loadUrl`/`loadData` 已可用；页面→脚本的 `rhino.call/eval` 为异步回调（引擎线程执行，`injectAndWait` 不支持） |
| debugger | 现有 Dim 调试器是 Rhino 专用，QuickJS 尚无断点、单步、变量查看等价实现 |
| Java 桥接 | QuickJS 故意采用白名单 host bridge，不提供 Rhino 式任意 `java.lang.*` 反射；需先确定正式兼容边界 |
| 编辑器 | Token/AST、高亮、自动完成和部分异常仍直接使用 Rhino 类 |
| 运行时 | `ScriptRuntime`、`TimerThread`、UI 代理、continuation 仍有 Rhino 具体类耦合 |

## 样例约束

- `QuickJS 新引擎` 目录下的每个 `.js` 必须以 `// @engine quickjs` 作为第一条非空行。
- 依赖 E4X、任意 Java 反射、旧 `"ui"` 模式或 Rhino 对象代理的样例归入 `Rhino 引擎`。
- Gradle `verifyQuickJsSamplesMarked` 在每次 `preBuild` 阶段阻止未标记样例再次混入。

只有上述阻塞项完成、compat/lite 设备矩阵持续通过，才能从正式版移除 Rhino。
