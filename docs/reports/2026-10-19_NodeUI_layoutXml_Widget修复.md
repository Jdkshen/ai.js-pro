# Node UI layoutXml/Widget/自定义 View 修复（task-155，2026-10-19）

## 结论
- **layoutXml**：dev-1096 已具备（`Activity.layoutXml` → `NodeUiHost.inflateInlineLayout`，见 `output/mi8-three-engines-20261019/layout-xml-fix.md`）；本轮未回归、未破坏。
- **Widget（自定义组件）**：本轮补齐最小子集：`ui.Widget` 基类、`Widget.register(name, Class)`、`Widget.of(view)`；`Activity.layoutXml` 里的自定义标签在发宿主前展开为 `render()` 的 XML，宿主返回后绑定 `widget.view`、按 `attr$*` setter 应用属性、调用 `mounted(view)`；事件沿用现有 click 绑定（`view.on('click', fn)` / `setOnClickListener`）。
- **defineClass 真实子类**：**不可行/不实现**。现有 native 桥是单向 `__autojsHost(JSON)`，没有 Java→Node 反向调用，也没有 dex 生成/类加载通道；真实 `$java.defineClass` 需要「Java 回调 JS（onDraw/构造器/lifecycle）+ 运行时生成 dex」两条链。当前 ui.js 对 defineClass 仅返回 marker/Promise.resolve(spec)，不构成 Java 子类。建议后续另立任务评估 native 反向通道，不在本轮伪装成功。

## 实现（modules/engine/src/main/assets/node24/ui.js）
- 新增 `widgetRegistry`、`Widget` 基类（`initialAttributes` / `render()` / `attrs` / `view`）；
- `Widget.register(name, Ctor)`：注册 XML 标签；`Widget.of(view)`：从 binding 取回 widget；
- `expandCustomWidgetsInXml(xml)`：识别已注册标签（自闭合/成对），实例化、合并 `initialAttributes + 外层属性`、`render()` 展开，并把外层 `id`/普通属性合并到渲染根标签；自定义属性（存在 `attr$name` setter）不发给宿主、等视图绑定后再赋值；
- `attachCustomWidgets(records, views)`：show 成功后按 id 建真实 binding、`widget.view= binding`、执行 `attr$*` setter、调用 `mounted(binding)`；
- `ui` 导出新增 `Widget`；未改 prelude.js、NodeUiHost、cpp。

## 本地契约验证（系统 Node + mock 宿主）
- `node --check` ui.js：SYNTAX=0；
- mock 宿主 show 返回 views，验证：
  - 自定义标签被展开：`<colored-button id="btn1" text="第一个按钮" color="#934c00"/>` → `<com.google.android.material.button.MaterialButton ... id="btn1" text="第一个按钮"/>`；
  - `onContentViewSet` 中 `ui.Widget.of(view.binding.btn1)` 返回实例；`attrs.color=#934c00`（attr$ setter 生效）；`widget.view` 非空；`onClick` 能绑定 click。

## 剩余限制
- Widget 只支持基础属性放行 + `attr$*`；真实自定义 View（onDraw/构造参数）仍需要 defineClass/native 反向通道；
- MaterialButton/复杂控件是否能在 inline inflater 真实构造需 K40 验证（失败时按宿主 inflate 错误报告，不静默）；
- `initialStatusBar` 仍未生效（既有缺口）。

## K40 验证计划（dev-1097 待构建）
1. 跑 `sample/Node 引擎/Node.js/Node.js中使用UI/自定义组件/colored-button.js`：预期显示两个 MaterialButton（第一个为 #934c00 背景），logcat `mounted: attrs = {color:'#934c00'}`，点第二个按钮出现 toast（attrs）；
2. 跑 `传统界面/main.node.js`（layoutXml+grid/list/canvas 占位）确认 layoutXml 回归正常；
3. 跑 `继承Java类-自定义View.js` 记录 defineClass 仍 blocked（中文提示），作为评估结论。

## dev-1098 K40 验证结果（2026-10-19）
| 用例 | 结果 | 证据 |
|---|---|---|
| 自定义组件/colored-button.js | ✅ UI 渲染：两个 MaterialButton 成功 inflate，`C-VIEWS: inline {"btn1":...,"btn2":...}`；`NodeUiActivity C-INLINE layoutXml inflated` 成功。⚠️ `mounted` console 日志与点击 toast 本轮未在 logcat/截图中稳定捕获（Node UI console 未进 GlobalConsole / 后续 Activity 被并发脚本干扰），需后续专项复验。 | `output/flutter-poc/evidence/task155/widget-buttons.png` |
| 传统界面/main.node.js | ✅ layoutXml 回归：文本/输入框/按钮显示，grid/list/canvas 按设计替换占位；`C-INLINE`/`C-VIEWS` 日志完整，无「没有设置 layoutId」。 | `output/flutter-poc/evidence/task155/legacy-layoutxml.png` |
| 继承Java类-自定义View.js | ⛔ blocked：`TypeError: Class extends value does not have valid prototype property undefined`（全局 android.widget.Button 在 extends 求值时不是有效构造器）；defineClass 中文降级提示未走到。评估结论不变：真实 defineClass 需要 native 反向通道，另立任务。 | logcat GlobalConsole |

## 结论
- layoutXml（字符串布局）与 Widget 自定义标签展开在本轮 dev-1098 均可用；
- Widget 的 mounted/click 回调需要再确认事件链路（Node UI console 输出与 Activity 前台稳定性），不阻塞已完成的布局能力；
- defineClass 真实继承不可行；样例当前报 TypeError，建议下轮在 ui.js 的 Java 包代理层给 `extends <未安装 Java 类>` 更明确的中文错误。