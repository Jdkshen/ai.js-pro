# Node24 基础自动化 API（accessibility / image / color / media_projection / app.viewFile）

> 当前验证基线：dev-1112（Node 21/21、颜色 6/6、Node UI basic 8 steps，dev-1103；dev-1104~1112 又补了 media/ocr/zip/plugins、dialogs customView、engines/threads）。

> 本文面向在 Node24 引擎中编写脚本的用户，说明四个基础自动化模块和 `app.viewFile` 的用法、
> 参数边界、返回值与宿主协议。
>
> JS 层负责参数校验、图片句柄生命周期、Promise/异常语义；能否真正点击、截图、授权，取决于
> 运行环境和系统权限。未实现或未覆盖的能力会在下文明确列出，不把“接口存在”当成“真机可用”。
>
> 当前覆盖主 App 的 **flutterPoc** 构建；打包运行时 **inrt** 和非 **flutterPoc** 构建没有这个
> 自动化客户端，不在覆盖范围内（见 3.1）。

## 1. 支持范围

### 1.1 注册面

可通过 `require(...)` 加载；前四个模块同时挂到 `$autojs`。`app.viewFile` 的入口见 1.6。

| `require(...)` | `$autojs` 属性 | 说明 |
|---|---|---|
| `accessibility` | `$autojs.accessibility` | 无障碍状态、启停辅助、坐标动作、手势、截图 |
| `image` | `$autojs.image` | 图片读取/写入、像素、找色/比色、句柄回收 |
| `color` | `$autojs.color` | `Color` 与颜色整数归一化 |
| `media_projection` | `$autojs.media_projection` | 真实屏幕捕获授权与帧获取 |
| `app` | —（用 `require('app')`） | 本页覆盖 `viewFile`；其它方法为最小兼容面 |

```js
const { accessibility, click, StrokeDescription } = require('accessibility');
const image = require('image');
const { Color } = require('color');
const { requestScreenCapture } = require('media_projection');
const { viewFile } = require('app');
```

### 1.2 accessibility

宿主请求统一为 `{kind:"automation", action, ...}`，返回 `{ok:true,value:...}` 或
`{ok:false,error:"..."}`。

| JS API | 宿主 action | JS 返回 |
|---|---|---|
| `accessibility.status()` / `statusSync()` | `status` | 直接返回 `{enabled,ready}`（`await` 也可） |
| `accessibility.openSettings()` | `openSettings` | Promise\<boolean\> |
| `accessibility.enableService(options?)` | `status`、必要时 `openSettings` | Promise\<boolean\> |
| `accessibility.click(x,y)` | `click` | Promise\<boolean\> |
| `accessibility.longClick(x,y)` | `longClick` | Promise\<boolean\> |
| `accessibility.press(x,y,duration)` | `press` | Promise\<boolean\> |
| `accessibility.swipe(x1,y1,x2,y2,duration)` | `swipe` | Promise\<boolean\> |
| `accessibility.performGesture(points,duration,delay?)` / `gesture(...)` | `gesture` | Promise\<boolean\> |
| `accessibility.performGestures(strokes)` | `gesture` | Promise\<boolean\> |
| `accessibility.back/home/recents/notifications()` | 同名 action | Promise\<boolean\> |
| `accessibility.takeScreenshot()` | `takeScreenshot` | Promise\<Image\> |

- **动作都是 Promise**；宿主返回 `false` 时原样 resolve `false`（例如系统拒绝点击），宿主错误
  一律 reject，不吞掉、不伪造成功。
- `enableService(options)` 的流程是严格的三段式：
  1. `status`；`ready === true` 直接返回 `true`；
  2. `enabled === false` 时调用一次 `openSettings`（`options.toast === true` 会额外提示）；
  3. 用 Node `setTimeout` 轮询 `status`，直到 `ready === true`。
  - 默认超时 `60000ms`；`options.timeout` 可改；`options.interval` 默认 `250ms`。
  - 超时 reject：`[Auto.js] 开启无障碍服务超时（Nms）：请在系统设置中开启本应用的无障碍服务后重试`。
  - `enabled` 已为 `true` 但 `ready` 为 `false` 时不会反复打开设置，只轮询。
  - 永远不会仅因“设置页已打开”就 resolve `true`。

`StrokeDescription` 支持：

```js
new StrokeDescription(points, duration);        // delay = 0
new StrokeDescription(points, duration, delay); // 与 Pro 参数顺序一致，保留起始延迟
```

`points` 元素可写 `{x,y}`、`{x,y,t}` 或 `[x,y]`、`[x,y,t]`。发往宿主前会转成
`{x,y,t}`，其中 `t` 是相对**整个手势开始**的毫秒数：

- 未写 `t` 时按 `duration` 线性分配；显式 `t` 必须非递减。
- `delay` 会加到每个点的 `t` 上，多指手势的起始延迟不会丢。
- **单点手势会发两个同位置点**：`t=delay` 和 `t=delay+duration`。宿主用首点 `t` 作为该
  stroke 的起始时间，其余点减去首点 `t`，这样延迟和按压时长都能保留。
- 坐标必须是非负整数；`delay + duration` 和最终点时间都不能超过 Android 手势上限
  `60000ms`。越界、负坐标、递减 `t` 会在 JS 侧抛 `RangeError`/`TypeError`，不会把脏参数
  发给宿主。

`performGesture(points,duration,delay?)` 也支持同样的 delay 参数。同一 stroke 的首个 `t`
表示该 stroke 的起始时间；宿主会把首点 `t` 转为该 stroke 的 `startTime`，其余点减去首点
`t` 后作为 stroke 内偏移。

`takeScreenshot()` 返回 `image.Image` 包装（不是裸 `{id,...}`），可直接传给 `writeImage`。
无障碍截图受系统限制：Android 11+、频率约 1 秒 1 次；宿主/设备不支持时 reject。

### 1.3 image

宿主请求为 `{kind:"image", action, ...}`。JS 侧只接受两种句柄形态：

- `Image` 包装；
- 宿主返回的 descriptor `{id,width,height}`（会被自动包装）。

`Image` 提供 `id`、`width`、`height`、`getWidth()`、`getHeight()`、`pixel()`、
`recycle()`/`close()`、`isRecycled()`。

| JS API | 宿主 action | 说明 |
|---|---|---|
| `readImage(path)` / `readImageSync(path)` | `read` | 异步/同步，返回 `Image` |
| `writeImage(img,path,format?,quality?)` / `writeImageSync(...)` | `write` | 异步/同步，返回 boolean |
| `image.pixel(img,x,y)` | `pixel` | 返回 signed ARGB 整数；`img.pixel()` 返回 `Color` |
| `findColor(img,color,options?)` / `findColorSync(...)` | `findColor` | 返回 `{x,y}` 或 `null` |
| `findMultiColors(...)` / `findMultiColorsSync(...)` | `findMultiColors` | 返回 `{x,y}` 或 `null` |
| `detectsColor(img,color,x,y,options?)` | `detectsColor` | 同步返回 boolean |
| `detectsMultiColors(...)` | `detectsMultiColors` | 同步返回 boolean |
| `image.recycle(img)` | `recycle` | 幂等；也可直接用 `img.recycle()` |

路径规则：

- 相对路径按**当前脚本所在目录**解析（`__ajsScriptFile` 的父目录），不是项目根；
- 绝对路径保留并 `normalize`；
- 读/写都要求得到绝对路径后再交给宿主。

归一化规则：

- 颜色：`Color` 对象、`#RRGGBB`、`#AARRGGBB`、带符号十进制整数，最终都转成 signed ARGB 整数；
- `region`：
  - `[x,y]`：从 `(x,y)` 到图片右下角，JS 会补成 `[x,y,width,height]`；
  - `[x,y,width,height]`：原样校验后发宿主；
  - `{x,y,width,height}`：转成数组；
  - 越界/非整数/零宽高会在 JS 侧抛 `RangeError`/`TypeError`，不把脏参数发给宿主。
- `threshold`：`0..255`，未给出时默认 `0`；
- `quality`：`0..100`，默认 `100`；`format` 支持 `png`/`jpeg`，`jpg` 归一为 `jpeg`。

多点找色兼容两种写法：

```js
// 现有示例写法
findMultiColorsSync(img, {
  firstColor: Color.parse('#009688'),
  offsetColors: [{ color: '#ffffff', offsetX: 20, offsetY: 20 }]
}, { threshold: 0, region: [100, 100, 500, 500] });

// 分离参数
findMultiColors(img, color, [[20, 20, '#ffffff']], { threshold: 0 });
```

`offsetColors` 元素支持 `{offsetX,offsetY,color}` 或 `[dx,dy,color]`；数组必须非空（宿主同样拒绝空数组）。

句柄与回收：

- `recycle()` 第一次发宿主 `recycle`，成功后标记关闭；重复调用直接返回 `true`，不再打宿主；
- 回收后再访问 `width/height/pixel/findColor...` 会抛“图片句柄已回收”；
- 宿主 `recycle` 报错时不会把句柄标记为已回收，允许重试；
- `image.recycle({id,...})` 对已回收的同一 id 也返回 `true`，方便 finally 里重复清理；
- 宿主负责 bitmap 资源；脚本结束/取消时宿主仍应兜底释放全部图片。

### 1.4 color

```js
const { Color, toColorInt } = require('color');
Color.parse('#009688');
Color.parse('#80010203');
Color.parse(-16711936);
Color.argb(255, 1, 2, 3);
Color.rgb(1, 2, 3);
```

`Color` 实例有 `value`、`alpha/red/green/blue`、`toInt()`、`valueOf()`、`toString()`、`equals()`。
颜色比较（`color.isSimilar`）按 RGB 三通道最大差值 `<= threshold`，忽略 alpha。
`Color.parse` 对无法解析的字符串返回 `null`；严格的 `color.parseColor` 对无效输入抛 `TypeError`。
整数支持 signed int32 与 unsigned 32bit 写法，越界抛 `RangeError`，不会静默截断。

### 1.5 media_projection

宿主请求为 `{kind:"projection", action, ...}`。

| JS API | 宿主 action | 说明 |
|---|---|---|
| `requestScreenCapture(options?)` | `request`（`options` 嵌套发送） | Promise\<ScreenCapturer\>；用户拒绝时 reject |
| `capturer.nextImage(options?)` | `capture` + `fresh:true` | Promise\<Image\>，默认 timeout 5000ms |
| `capturer.latestImage()` | `capture` + `fresh:false` | 同步返回最近帧 |
| `capturer.stop()` / `close()` | `stop` | 同步、幂等 |
| `captureScreen(options?)` | 复用/请求 + `capture` | 便捷入口，内部 `nextImage()` |

- `request` 由宿主等待系统授权（最长 55 秒）；拒绝时宿主返回 `false`，JS 抛
  `ScreenCaptureRequestError`；宿主超时/失败的错误原样 reject。
- `options` 作为嵌套对象发送（`{"action":"request","options":{...}}`）。`width/height` 必须
  同时提供或同时省略；`orientation` 按宿主取值：`-1`/`0` 自动、`1` 竖屏、`2` 横屏，也接受
  `auto`/`portrait`/`landscape` 字符串。模块导出的 `Orientation` 即
  `{CURRENT:0,AUTO:0,PORTRAIT:1,LANDSCAPE:2}`（不是 Pro 旧枚举的 1/2/3 编号）。
- 同一 job 只允许成功建立一个投影会话：成功后即使 `stop()` 过，再次
  `requestScreenCapture` 也会明确 reject（与 Java `Session.mUsed` 对齐）。拒绝/超时没有消耗
  授权机会，可以重试。
- `stop()` 后 `nextImage()` reject `ImageUnavailableError`，`latestImage()` 同步抛同类型错误；
  `stop()` 重复调用不会重复打宿主。
- `captureScreen()` 在没有活动 capturer 时会先请求授权；已有活动 capturer 时直接复用。
- 脚本结束时由宿主自动释放投影；JS 不依赖 `process.on('exit')` 去做异步清理。
- `requestScreenCapture`/`nextImage` 的 Promise `.then` 回调仍在 Node 线程，不会切到 Android UI 线程。

### 1.6 app.viewFile

```js
const { viewFile } = require('app');
viewFile('./screenshot.png');
```

- 相对路径按**当前脚本所在目录**解析；绝对路径保留并规范化。宿主只会收到绝对路径。
- 请求固定为 `{kind:"app",action:"viewFile",args:[absolutePath]}`。
- 返回真实的 `boolean`：`true` 表示宿主已发起查看，`false` 表示未能查看；不会用 truthy
  值冒充成功。
- 宿主错误原样抛出；返回值不是 `boolean` 时会抛协议错误。
- 需要宿主实现 `viewFile` action。缺少该 action 的环境会明确报错，而不是静默成功。

### 1.7 ui 线程诚实性

- `ui.isUiThread()` 与 `ui.uiThread` 每次都通过
  `Looper.myLooper() === Looper.getMainLooper()` 的 Java 反射路径判定，不再永远返回 `true`。
- Node 线程通常没有 Android Looper，因此结果为 `false`；判定异常也返回 `false` 并告警。
- `ui.run(fn)` 立即在当前 Node 线程执行；`ui.post(fn, delay)` 用 Node `setTimeout` 调度，
  回调**仍在 Node 线程**，不会迁移到 Android main thread。
- 已存在的 View 命令（`findViewById`/`setContentView`/`inflate`/`viewCommand` 等）仍通过
  `hostCall` 交给宿主，由宿主切到 Android UI 线程；不要因为 `ui.run` 返回就认为可以绕过 Binder
  直接改 View。
- 保留 `ui.run`/`ui.post`/`ui.runOnUiThread` 的现有调用兼容，不把它们改成一律抛出。

## 2. 宿主协议摘要

本节是当前 JS/宿主协议的定义，契约测试也按本节断言。

```json
{"kind":"automation","action":"click","x":200,"y":200}
{"kind":"automation","action":"gesture","strokes":[[{"x":1,"y":2,"t":0},{"x":3,"y":4,"t":350}]]}
{"kind":"automation","action":"gesture","strokes":[[{"x":9,"y":9,"t":50},{"x":9,"y":9,"t":250}]]}
{"kind":"image","action":"findColor","id":1,"color":-16738680,"threshold":0,"region":[100,100,500,500]}
{"kind":"image","action":"recycle","id":1}
{"kind":"app","action":"viewFile","args":["/sdcard/DCIM/screenshot.png"]}
{"kind":"projection","action":"request","options":{"width":1080,"height":2400,"orientation":1}}
{"kind":"projection","action":"capture","fresh":true,"timeoutMillis":5000}
{"kind":"projection","action":"stop"}
```

`gesture` 的 `strokes` 是点数组，`t` 相对整个手势开始。同一 stroke 的首点 `t` 表示起始
延迟，宿主应将其转为该 stroke 的 `startTime`，其余点减去首点 `t` 后作为 stroke 内偏移。
单点手势固定发两个同位置点（`t=delay` 与 `t=delay+duration`），否则无法同时表达延迟和时长。

中间点用于定义 Android 手势路径；实际移动速度由 Android 按路径和总时长分配，不支持逐点精确控制到达时间。

响应仍使用既有 envelope：`{ok:true,value:...}` / `{ok:false,error:"..."}`。JS 通过
`__ajsHostValue`（prelude 内部的 `hostValue`）调用，宿主错误会带 `[Auto.js]` 前缀 reject。

## 3. 运行环境与限制

### 3.1 当前覆盖的环境

- 主 App 的 **flutterPoc** 构建（`-PflutterPoc=true`）包含本页使用的 Node24 自动化客户端
  与宿主桥，是当前覆盖范围。
- 打包运行时 **inrt** 和非 **flutterPoc** 构建**没有**这个自动化客户端，不在本页覆盖范围
  内；不要把这些环境当成已支持。
- 宿主缺少对应 `kind`/`action` 时会明确报错，不会伪造成功。

### 3.2 执行时间、线程与并发

- 默认 **headless** Node24 执行有 **60 秒**上限（`timeoutMillis=60000`）；普通脚本结束即返回。
- 带 `keepRunning()` 或 UI 特征的脚本由宿主保活，现有上限为 **10 分钟**；超过后仍需重新运行。
- Promise 只表示“结果稍后返回”，**不等于** native 并行：回调仍在 Node 线程执行，连续
  `await` 的宿主调用可能按顺序完成，不能据此假设多指/多任务已经并行。
- `ui.run()` 立即在 Node 线程执行；`ui.post()` 用 Node `setTimeout` 调度，回调仍在 Node 线程。
  需要 Android UI 线程的 View 操作仍由宿主切线程完成（见 1.7）。
- `accessibility.enableService` 的默认等待是 60 秒，与 headless 总时长是两回事；长授权可能
  先撞上执行上限。

### 3.3 未实现 / 未覆盖（不要当作已支持）

- `image.findImage` / `findImageSync` / `Image.findImage`：真实 OpenCV 模板定位，支持 region、threshold、weakThreshold、level；逐帧使用后请回收截图。
- `ocr.createOCR({backend:'mlkit'|'ppocr'})`：内置离线 OCR，会话用完 `release()`；截图来源由 `media_projection` 提供。
- `matchTemplate`（多结果）、透明掩膜和特征匹配仍未接入 Node24。
- 控件选择器、UiObject 遍历和节点文字输入见 [Node24 控件选择器 API](Node24控件选择器API.md)；本页主要说明基础动作与截图。
- 无障碍事件订阅与完整 `automator` 模块尚未覆盖。
- `app` 仅提供最小方法面（含本页的 `viewFile`），不是完整的 Pro `app` API。
- `threads`/`engines` 只有最小转发，不提供真正的多线程并行语义。
- `ui` 不是完整的 Pro UI 模块；只保留现有兼容入口和宿主 View 命令。
- 图片压缩质量、JPEG 编码、色差实现细节由宿主决定；JS 只校验参数范围。
- `region` 的越界校验依赖宿主返回的 `width/height`；宿主尺寸错误时最终仍应由宿主拒绝。

### 3.4 验证状态

- JS 契约测试在系统 Node 上运行，覆盖本页列出的请求、返回值与边界；结果见第 4 节。
- 真机验证（系统授权、坐标点击、多指时序、截图、`viewFile` 实际打开）不在契约测试范围内；
  真机结果见项目集成报告。

## 4. 契约测试

契约测试位于 `tools/test-node24-automation-api.cjs`。它直接加载 App 内同一份
`prelude.js`/`ui.js`，用 mock `__autojsHost` 记录请求，**不镜像重写模块实现**。

运行（系统 Node 可用）：

```powershell
where.exe node
# C:\Program Files\nodejs\node.exe
node tools/test-node24-automation-api.cjs
```

当前结果（Node v24.16.0）：`29 passed, 0 failed`。

覆盖：

- 四模块注册到内置表与 `$autojs`，以及 `app.viewFile` 入口；
- Color 解析/ARGB/非法输入；
- `enableService` 的 status -> openSettings -> Node timer 轮询、ready 早返回、超时 reject；
- 动作 Promise、请求 shape、宿主 false/error 传播、参数边界；
- 单点/多指/延迟手势的精确 payload、负坐标、递减 `t`、`delay+duration` 60000ms 边界；
- `app.viewFile` 相对路径解析、精确请求、`false`/非 boolean 返回值、宿主错误传播；
- `takeScreenshot` 返回 Image 并可 `writeImage`；
- 图片相对路径、异步/Sync、region/颜色/多点找色归一化、回收幂等与回收后访问错误；
- media_projection 授权/拒绝/错误、`nextImage`/`latestImage`/`stop`、`captureScreen` 复用、
  单 job 只授权一次；
- `ui.isUiThread` 真实 Looper 分支与异常降级、`ui.run`/`ui.post` 调用兼容。

这些测试不能替代 Java 宿主真机测试，也不能证明真机权限链路可用。
