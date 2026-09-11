# AI.js Pro 悬浮窗 API 需求清单

> 来源：QuickJS 引擎下开发「悬浮球 + 环形菜单」时的实测问题汇总
> 环境：`com.jdkshen.aijspro` v1.0.2 (versionCode 465)
> 设备：Redmi 2602BRT18C · Android 16 (SDK 36) · 1280×2772 · density 3.25

---

## 摘要

## 实施进度（2026-09-11 起，按批落地）

| 条目 | 状态 | 落地方式 |
|---|---|---|
| P0-1 窗口 `setVisibility`/`show`/`hide` | ✅ 批A 已完成 | `win.show()/hide()/setVisibility(v)/isShown()`；隐藏走 `removeViewImmediate`（窗口真正离屏，不再吃触摸），显示重新 attach，同步返回保证原子 |
| P0-2 出生在 (0,0) 闪现 | ✅ 批A 已完成 | `floaty.window(xml, { x, y, visible: false })`：创建阶段写好 `mParams.x/y`，`visible:false` 时先构建布局不上屏，`show()` 才挂载；`getX()` 创建后即返回目标坐标 |
| P0-3 多窗口显隐非原子 | ✅ 批A 已完成 | 窗口级显隐同步（调用返回即生效）；另加最快路径 `win.setContentVisible(bool)`（只切根 View，实测 20 次 0ms） |
| P1-1 原生动画不可用 | ✅ 批B + 补丁 已完成 | ① `view.javaView` 暴露真实 `android.view.View` 句柄；② **控件/窗口代理自带 `__javaHandle`**，`ObjectAnimator.ofFloat(win.c, "alpha", 1, 0)` 不再报“无法把 [object Object] 作为 Java 参数传递”（Animator 需要 Looper 线程，用 `runOnMainThread` 包一层，或用一行式 `objectAnimator(win.c,'alpha',1,0,300)` / `animateView(win.c,{alpha:1},300,'bounce')`）；③ `view.animate(props,duration,easing)` / `win.animate(...)` |
| P1-2 窗口 alpha/scale | ✅ 批A 已完成 | `setAlpha/getAlpha`、`setScale(sx,sy)`、`setScaleX/Y`：直接作用于根 View，不触发布局重排 |
| P2-1 XML 单位不统一 | ✅ 批D + 补丁 已完成（文档口径） | 与 Rhino 完全一致（同一个 `DynamicLayoutInflater`）：`w/h` 不带单位 = **dp**，`margin*/padding*` 按 **px**；**另外修了读取：`attr('width'/'height')` 与 `win.getWidth/getHeight` 在布局未完成时不再返回 0**（回退 layoutParams / setSize 值），已写进文档与样例 |
| P2-2 `setPosition` 后 `getX()` 旧值 | ✅ 批A 已完成 | `getX()/getY()` 返回最近一次设定值（立即）；`getX(true)/getRealX()` 读主线程 flush 后的生效值 |
| P2-3 子线程操作窗口 | ✅ 批C + 补丁 已完成 | 窗口注册表改为**进程级**；**原始写法 `threads.start(fn, {win: win})` + `__args.win.setPosition(...)` 现在直接可用**（参数序列化时窗口转成 `{__floatyWindowId}`，worker 侧自动还原成窗口代理）；另加 `floaty.windowById/getWindow(id)`、`win.post(fn[, delay])`；引擎销毁只关自己创建的窗口 |
| P3-1 z-order / 触摸穿透 | ✅ 批D 已完成（穿透）/ ⚠️ 层级受系统限制 | `setTouchable(false)` → 真正加 `FLAG_NOT_TOUCHABLE`（触摸穿透到下层，与 Auto.js 语义一致，默认 touchable=true）；拖动与触摸分离：`setDraggable(true)` / `setAdjustEnabled(true)`；overlay 窗口 z-order 由创建顺序与系统决定，`TYPE_APPLICATION_OVERLAY` 无法任意插层，已在文档说明 |
| P3-2 生命周期事件 | ✅ 批C 已完成 | `win.on('attached'/'detached')`（窗口上屏/离屏，由创建它的引擎接收）；`win.on('close', fn)` 等价 `onClose`；配套 `floaty.exists(id)` / `win.exists()` / `win.id` |
| MCP-1 `list_engine_api` envelope | ✅ 批C 已完成 | `McpTools.listEngineApi/probeEngineApi` 补上 `toolJson(...)`（标准 MCP tool result envelope），官方 Kotlin SDK 不再报 "Cannot determine RequestResult type from JSON: [engine, count, items]" |
| MCP-2 `engine_api_diff` + `run_script(engine)` | ✅ 批 E1 已完成 | `engine_api_diff` 一次调用给出两引擎全局 API 差异（`onlyQuickJs`/`onlyRhino`/`commonCount`）；`run_script` 新增 `engine` 参数：写到临时副本 + 自动清理，不动原文件 |
| 圆形触摸穿透（P0-1 暴露 `win.view`） | ✅ 批 E2 已完成 | 窗口/控件代理都新增 `view` getter（与 `javaView` 同一个真 View）；控件代理链式返回值统一为代理自身（`win.c.setCornerRadius(60) === win.c`） |
| 圆形触摸穿透（P0-2 `clipToOutline` / `50%` 圆角） | ✅ 批 E2 已完成（⚠️ 仅视觉+窗口内命中） | 新增 XML 属性 `clipToOutline`（ViewAttributes）与 `cardCornerRadius="50%"`（CardAttributes 百分比）；并修复「XML 根自带 `w/h` + 窗口 wrap_content → 内容被量成 0×0」的布局 bug |
| 圆形触摸穿透（P1/P2 按区域输入） | ⚠️ 系统不支持，已降级为能力位 | 实测 Android 15：`WindowManager.LayoutParams` 无 `setTouchableRegion(Region)`/`mTouchableRegion` ⇒ `floaty.touchRegionInfo().supported=false`；API 保留（`setShape`/`setTouchShape`/`setTouchableRegion`/`clearTouchRegion`）并同步 `console.warn`，不支持时行为退回矩形；**可行方案见 `docs/guides/悬浮窗触摸穿透说明.md`** |
| 触摸穿透实测结论（供脚本作者） | ✅ 已写入文档 | `setTouchable(false)` 时点在窗口内会**真的穿透到下层 App**（计算器实测：`false` 时 `formula=7`，`true` 时为空）⇒ 环形菜单用「容器 `setTouchable(false)` + 菜单项独立小窗口」 |
| F-1 `cardCornerRadius` 不支持小数 | ✅ 批F 已完成 | `Dimensions` 改用 `Float.parseFloat`：`cardCornerRadius="73.5px"` 可直接建窗（新手机实测 147×147，`attr('cardCornerRadius')` 读回 `73.5px`）；解析失败时仍报 `InflateException` 而非难懂的 `NumberFormatException` |
| F-2 `onTouch` 坐标 NaN | ✅ 批F 已完成 | 触摸事件补上窗口内坐标：`ev.getX()/getY()`（Java 侧发 `x/y`，与 `getRawX()/getRawY()` 屏幕坐标共存）；实测 `x=100 y=100 rawX=300` |
| F-3 XML 尺寸单位混淆 | ✅ 文档口径（同 P2-1） | 与 Rhino 一致：`w/h` 不带单位 = dp、`margin*/padding*` = px；`docs/guides/悬浮窗开发踩坑与性能.md` 给了“按屏宽比例 + 显式 px”的自适应模板 |
| F-4 MCP 返回结构错误 | ✅ 批C 已修复，已在 v1.0.2 生效 | `list_engine_api`/`probe_engine_api` 已包成标准 tool result envelope（见 MCP-1）；另新增 `engine_api_diff` |
| F-5 `setTouchShape` 存在但系统不支持 | ✅ 保持 API + 明确标注 | 系统无按区域输入隐藏 API（`supported=false`）时：仍可调用（链式），但会 `console.warn` 给出替代方案；`floaty.touchRegionInfo()` / `win.getTouchRegion()` 可事前探测 ⇒ 不删除，避免后续 ROM 支持时脚本要改 |
| F-6 `animate` 缺 `scale` / `easeIn` 等 | ✅ 批F 已完成 | `scale` 自动拆成 `scaleX+scaleY`；新增别名 `easeIn/easeOut/easeInOut/spring`（`ease-in` 等写法也兼容），未知属性/缓动报错里直接列可用项 |

> 批A 真机验证（Mi8 `ce4d2bdb`）：初始坐标 600/400 ✅、`visible:false` 不显示 ✅、显示前 `findView` 可用 ✅、show/hide/setVisibility 往返 ✅、`setPosition` 后 `getX()` 立即 300/500 ✅、`getX(true)` 生效值 ✅、alpha/scale 链式 ✅、3 窗口显隐原子 ✅、`setContentVisible` 20 次 0ms ✅。
> 批B 真机验证：`javaView` = `JsTextView` / 根 View = `FrameLayout` ✅、`runOnMainThread` 内调 View API ✅、`ObjectAnimator.ofFloat(view.javaView, "alpha", 1, 0.2)` 启动成功 ✅、`view.animate(..., 'decelerate')` 18ms ✅、`win.animate(..., 'bounce'/'linear')` ✅、链式与 `stopAnimation` ✅、未知属性/缓动报错清晰 ✅。
> 回归：QuickJS 全模块回归 **222 项 全绿**（批A/B/C + 用户的 P1-1/P2-1/P2-3 补丁共 +14 项悬浮窗断言，圆形触摸穿透 +7 项，批F 反馈修复 +2 项）。
> 批E 真机验证（Mi8）：`floaty.touchRegionInfo().supported=false`（系统无按区域输入 API）❓；`win.view`/`win.c.view` 均拿到真 View（`CardView`）✅；`<card w="200px" h="200px"/>` 作为 XML 根时窗口量到 200×200（不再 0×0）✅；`setCornerRadius('50%')` → 轮廓变 `oval`、`setCornerRadius(60)` → `roundRect`、`getClipToOutline()=true` ✅；`setTouchable(false)` 后 `getTouchRegion().touchable=false` ✅；**穿透实测：`setTouchable(false)` + `alpha=0.5/1.0` 时点击穿透到计算器（`formula=7`），`setTouchable(true)` 则被拦截（`formula` 空）** ✅。
> 补丁真机验证（Mi8）：`ObjectAnimator.ofFloat(win.c,'alpha',1,0.2)` ✅、`objectAnimator()`/`animateView()` ✅、`threads.start(fn,{win:win})` + `__args.win.setPosition(600,900)` ✅（主脚本 `getX()` 同步看到 600）、`attr('width')` = 120px（不再 0）✅、`setSize(500,300)` 后 `getWidth()` = 500x300 ✅。
> 批C 真机验证：`floaty.exists`/`win.exists` ✅、`attached/detached` 事件序列 `attached,detached,attached` ✅、`win.post` 返回值 6 / 带延迟 `delayed-ok` ✅、**worker 线程把主脚本窗口从 200 移到 240（主脚本 `getX()` 同步看到 240）** ✅、`getWindow(不存在)` 报错清晰 ✅、worker 结束后主窗口仍存活 ✅。

---

在 QuickJS 引擎下用 `floaty` 做悬浮窗，遇到若干 API 缺失与行为不一致问题。
其中 **3 条为阻塞级**，直接决定了悬浮窗能否做到"接近原生 App 的体验"。

| 优先级 | 数量 | 影响 |
|---|---|---|
| 🔴 P0 | 3 | 阻塞，无法做出高质量悬浮窗 |
| 🟠 P1 | 2 | 严重影响体验（动画卡顿） |
| 🟡 P2 | 3 | 功能性缺失，需绕行实现 |
| 🟢 P3 | 2 | 体验优化 |
| ⚫ MCP | 1 | MCP 工具本身缺陷（与版本无关） |

---

## 🔴 P0 · 阻塞级

### P0-1. `floaty` 窗口对象缺少 `setVisibility`

**现状**

窗口对象现有方法（实测）：
```
setPosition, getX, getY, setSize, setTouchable, close, exitOnClose,
setAdjustEnabled, getWidth, getHeight, findView
```
**没有 `setVisibility` / `show()` / `hide()`。**

**后果**

只能通过隐藏「窗口内的控件」来间接隐藏窗口。
若 `rawWindow` 的 XML 根节点没有 `id`，则**整个窗口无法隐藏**。

这是开发中反复踩坑的根源之一。

**期望**

```js
win.setVisibility(0);   // 显示
win.setVisibility(8);   // 隐藏
// 或
win.show();
win.hide();
```

---

### P0-2. 窗口创建时「出生在 (0,0)」，造成左上角闪现

**现状**

```js
var win = floaty.rawWindow(xml);   // 此刻窗口已在 (0,0) 显示
console.log(win.getX());           // → 0
win.setPosition(600, 400);         // 约 22ms 后才执行到这里
```
窗口创建瞬间即渲染于屏幕左上角，等到 `setPosition` 执行时已过去约 22ms。

**后果**

**肉眼可见的左上角闪现。** 已尝试并失败的方案：

| 方案 | 结果 |
|---|---|
| XML 根节点 `visibility="gone"` | ❌ 无效（窗口本身不隐藏） |
| 透明色 `#00000000` 创建后改色 | ⚠️ 有效但慢（setBackgroundColor 5-8ms/次） |
| 先 `setSize(1,1)` 再放大 | ⚠️ 有效但有残留、占位 |
| 创建后立即 `setPosition` | ❌ 仍有 22ms 窗口期 |

**期望**

```js
// 方案 A：支持初始坐标
floaty.rawWindow(xml, { x: 600, y: 400 });

// 方案 B：创建时不可见，首次 setPosition 前不渲染
var win = floaty.rawWindow(xml, { visible: false });
win.setPosition(600, 400);
win.setVisibility(0);
```

---

### P0-3. 控件 `setVisibility` 非原子，多窗口下会「部分隐藏」

**现状**

用 `menu.wrap.setVisibility(0/8)` 切换整个菜单容器，实测耗时波动 5–18ms。
在「1 个球 + 5 个独立菜单窗口」的多窗口方案中，出现过**部分窗口隐藏失败**（菜单展开后收不回去）。

**后果**

多窗口方案的可靠性差，最终只能改为「1 个容器窗口装所有菜单项」来规避。

**期望**

- 窗口级容器的 `setVisibility` 为**原子操作**
- 或提供 `window.setContentVisible(bool)`

---

## 🟠 P1 · 严重影响体验

### P1-1. 无法使用 Android 原生属性动画（最大痛点）

**现状**

```js
importClass(android.animation.ObjectAnimator);   // ✅ 可导入
ObjectAnimator.ofFloat(win.c, "alpha", 1, 0);    // ❌ 报错

// 报错信息：
// TypeError: 无法把 [object Object] 作为 Java 参数传递
//            （支持数字/字符串/布尔/null/数组/Java 对象）
```

**原因**

QuickJS 的浮窗控件是**代理对象**，不是真正的 `android.view.View`，
因此无法作为 Java 参数传入系统动画 API。

**后果**

**做不到官方 Rhino 那种丝滑动画。**
只能退化为「主线程 `sleep` 逐帧移动」，而每帧 `sleep` 最低约 16ms：

| 帧数 | 耗时 | 观感 |
|---|---|---|
| 2 帧 | 41ms | 只有"变大"，无过程 |
| 3 帧 | 94ms | 有明显弹出感（当前采用） |
| 6 帧 | 237ms | 明显卡顿 |

而官方 Rhino 版用 `ObjectAnimator` 驱动，动画由系统渲染线程执行，
脚本零开销，完全丝滑。

**期望**（任选其一或组合）

- (a) 让控件代理可透传真实 View 引用给 Java API
- (b) 暴露原生动画接口：
  ```js
  win.animate({ alpha: 1, scale: 1.2, x: 600 }, 300, 'bounce');
  ```
- (c) 至少支持基础的 `setAlpha()` / `setScale()`

> 此条若解决，悬浮窗体验将有质的飞跃。

---

### P1-2. 缺少窗口级 Alpha / Scale

**现状**

窗口只能通过 `setSize` 改变尺寸、通过 `setPosition` 改变位置。
没有 `setAlpha` / `setScale`。

**后果**

想做「淡入」或「缩放弹出」只能改尺寸，
而改尺寸会导致**内部布局重排**（内容位置错乱）。

**期望**

```js
win.setAlpha(0.5);      // 0 ~ 1
win.setScaleX(1.2);
win.setScaleY(1.2);
```

---

## 🟡 P2 · 功能性缺失

### P2-1. XML 尺寸单位不统一（已确认为坑）

**现状**

| 写法 | 实际解析 | 结果 |
|---|---|---|
| `w="300"` | **dp** | ×3.25 → 实际 **975px** |
| `margin="91"` | **px** | 实际 91px |

**后果**

曾做出「窗口巨大 975×975、内容全挤在角落」的完全错乱效果，
排查成本很高。必须显式写 `w="300px"` 才正确。

**期望**

- 统一默认单位
- 或**在文档中明确标注每个属性的单位**

---

### P2-2. `setPosition` 异步生效，`getX()` 返回旧值

**现状**

```js
win.setPosition(500, 500);
console.log(win.getX());   // → 300（旧值！）
sleep(10);
console.log(win.getX());   // → 500
```
实测约 **10ms** 后才生效。

**后果**

所有依赖 `getX()` / `getY()` 的判断逻辑都不可靠，
开发中因此多次误判（例如收起动画基于错误坐标计算）。

**期望**

- `setPosition` 同步更新内部坐标记录
- 或提供 `getX(true)` 读取实时值

---

### P2-3. 子线程无法操作窗口

**现状**

```js
threads.start(function () {
    __args.win.setPosition(x, y);   // ❌ 无效，位置不变
}, { win: win });
```

**后果**

动画无法放到后台线程执行，只能占用主线程，
这是「动画必然卡顿」的直接原因之一。

**期望**

- 允许跨线程操作浮窗
- 或提供线程安全的窗口更新队列（`window.post(fn)`）

---

## 🟢 P3 · 体验优化

### P3-1. 浮窗层级 / Z-order 不可控

**现状**

多窗口场景下，出现过「上层透明窗口吃掉触摸事件」的现象，
导致下层按钮点击无响应，且难以排查。

**期望**

- 可指定 z-order
- 或明确 `setTouchable(false)` 的穿透语义

---

### P3-2. 缺少浮窗生命周期事件

**现状**

窗口被系统回收 / 被其他应用遮挡 / 屏幕旋转时，脚本无法感知。

**期望**

```js
win.on('attached', fn);
win.on('detached', fn);
```

---

## ⚫ MCP 工具缺陷（与应用版本无关）

### MCP-1. `list_engine_api` / `probe_engine_api` 通过 MCP 调用必失败

**现象**

```
[McpException] Error while sending message:
Cannot determine RequestResult type from JSON: [engine, count, items]
```

**原因**

返回结构未包成标准 MCP tool result envelope，
官方 Kotlin SDK 反序列化失败。

**期望**

按 MCP 规范包一层：
```json
{ "content": [ { "type": "text", "text": "..." } ] }
```

---

## 优先级建议（若只能改三条）

1. **P0-1 窗口 `setVisibility`** — 解决隐藏/显示窗口的基础能力
2. **P1-1 原生动画支持** — 体验质变
3. **P0-2 `rawWindow` 支持初始坐标** — 消除左上角闪现

改完这三条，QuickJS 悬浮窗可达到接近原生 App 的体验。

---

## 附录：本轮实测数据汇总

供参考，均为 QuickJS 引擎下实测：

| 操作 | 耗时 | 备注 |
|---|---|---|
| `setVisibility` 显隐（5 控件） | **0ms** | 最廉价的显隐方式 |
| `setSize` | **0.03ms** | 可放心用于缩放 |
| `setPosition` 调用 | 1ms | 但**生效**需约 10ms |
| `setBackgroundColor` | 5–8ms | 较贵，需重绘 |
| `rawWindow()` 创建 | **约 33ms/个** | 昂贵，应复用而非重建 |
| `sleep(16)` | 实际 16.6ms | 逐帧动画的硬成本 |
| 3 帧动画（缩放+位移） | 94ms | 当前悬浮球采用 |
| 6 帧动画 | 237ms | 卡顿明显 |

**结论**：`setPosition` 与 `setSize` 极其廉价，但 `sleep` 的固定开销
使得「多帧流畅动画」成本高昂——这正是需要原生动画支持的根本原因。
