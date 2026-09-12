# AI.js Pro 双 JavaScript 引擎架构

> 路径核对日期：2026-09-04。双引擎、Native Frame + C++ OpenCV 图色处理、OpenCV YOLO 和白名单 API 已落地；QuickJS 为显式选择的新引擎，Rhino 仍是默认兼容引擎。文中代码路径均以仓库根目录为基准。

## 1. 总体结构

```text
JavaScriptSource
  │
  ├─ 默认脚本 ───────────────→ LoopBasedJavaScriptEngine
  │                              └─ RhinoJavaScriptEngine 1.7.7.2
  │                                  └─ 完整旧 Auto.js API / UI / modules
  │
  └─ // @engine quickjs ─────→ QuickJsJavaScriptEngine
                                 └─ QuickJsNativeBridge (JNI)
                                     ├─ libquickjs_jni.so
                                     └─ libquickjs.so 2026-06-04
                                         └─ Java/Native API Bridge
```

这样设计的核心原则是：旧脚本零迁移，新脚本按需使用现代 ECMAScript 和低启动开销的 Native 引擎。两条执行链由同一个 `ScriptEngineManager` 管理，复用脚本任务、日志、停止和运行状态服务。

## 2. 如何选择引擎

普通 `.js` 文件仍然使用 Rhino：

```javascript
toast("这是 Rhino 兼容脚本");
```

要使用 QuickJS，把下面的指令放在脚本第一条非空行：

```javascript
// @engine quickjs

console.log(__engine__);
toast("这是 QuickJS 脚本");
```

指令只在第一条非空行生效，防止依赖内容或注释中偶然出现 `@engine quickjs` 时静默切换引擎。QuickJS 脚本不能使用 Rhino 专属的 `"ui";`、E4X、Java 反射对象或 CommonJS `require()`。

## 3. 当前 QuickJS 能力

### JavaScript 运行时

- 固定使用官方 QuickJS `2026-06-04`，支持 ES2025；
- 每个脚本拥有独立的 `JSRuntime` 和 `JSContext`；
- 默认内存上限 64 MiB，Native 栈上限 2 MiB；
- 支持 Promise 微任务队列；
- JS 异常会转换为 `QuickJsException`，保留 JavaScript stack；
- `stopAll()` 或单任务停止会触发 QuickJS interrupt handler；
- Java 与 JS 字符串按标准 UTF-8 转换，已验证中文内容；
- `__engine__` 可读取名称、版本和 Native 标记。

### 第一批 Auto.js 兼容桥

| API | 状态 | 说明 |
| --- | --- | --- |
| `console.log/info/warn/error/verbose`、`log` | 已接入 | 写入 AI.js Pro 脚本控制台与全局日志 |
| `performance.now()` | 已接入 | C++ 单调高精度时钟，适合统计亚毫秒图色操作 |
| `toast`、`toastLog` | 已接入 | 通过 Java UI Handler 显示 |
| `sleep` | 已接入 | Native 分片等待，可被停止信号打断 |
| `click`、`press`、`longClick`、`swipe` | 已接入 | 调用现有无障碍手势实现 |
| `back`、`home`、`recents` | 已接入 | 调用现有全局无障碍动作 |
| `notifications`、`quickSettings` | 已接入 | 调用现有全局无障碍动作 |
| `setClip`、`getClip` | 已接入 | 复用现有剪贴板实现 |
| `currentPackage`、`currentActivity` | 已接入 | 复用前台页面信息提供器 |
| `requestScreenCapture`、`captureScreen` | 已接入 | 支持全分辨率、640/720p 视觉加速、默认最新缓存帧和 `fresh + timeout` 新帧模式；`ImageReader` RGBA Plane 直接进入 C++ `cv::Mat`，不创建 Bitmap |
| `images.pixel`、`NativeFrame.pixel` | 已接入 | 返回 ARGB 整数，像素不进入 JS 堆 |
| `colors.*` | 已接入 | `rgb` / `argb` / `parseColor` / RGBA 通道 / `toString` / `isSimilar` |
| `images.findColor`、`findColorInRegion`、`detectsColor` | 已接入 | C++ 直接扫描 RGBA，支持 `threshold` 和 `region` |
| `images.findAllPointsForColor` | 已接入 | Native 全区域扫描，返回 `{x, y}` 数组（上限 2 万点防爆），与 Rhino 语义一致 |
| `images.findMultiColors` | 已接入 | Native 多点颜色路径扫描，JS 只传颜色和偏移数组 |
| `images.opencv`、`images.toMat`、`images.matToImage` | 已接入 | 帧↔`org.opencv.core.Mat`（CV_8UC4）双向桥：`toMat` 直接写 Java Mat 原生缓冲区，`matToImage` 走 `getNativeObjAddr()` 后 clone；`images.opencv` 提供 Mat/Core/Imgproc/CvType/Scalar/Size/Point/Rect/Bitmap/BitmapFactory 类映射（惰性解析，与初始化顺序无关） |
| `images.inRange`、`interval`、`adaptiveThreshold`、`gaussianBlur`、`medianBlur`、`findCircles` | 已接入 | 走 OpenCV Java API + 帧桥，参数与 Rhino `__images__.js` 一致（`interval` 的上下界按 `color±threshold` 生成 Scalar） |
| `images.toBytes`、`fromBytes`、`readPixels` | 已接入 | `toBytes` 返回 `Uint8Array`（原生编码），`fromBytes` 走 `fromEncoded`，`readPixels` 用 BitmapFactory 逐像素读文件 |
| `images.read`、`findImage`、`matchTemplate` | 已接入 | OpenCV C++ 解码与单/多结果模板匹配 |
| `images.copy`、`clip`、`resize`、`scale` | 已接入 | 操作 Native `cv::Mat`，返回新 `NativeFrame` 句柄 |
| `images.grayscale/gray`、`cvtColor` | 已接入 | 内部保持 RGBA 句柄约束，支持灰度化和常用 RGB/BGR 转换 |
| `images.save`、`compress` | 已接入 | PNG/JPEG/WebP Native 编码；`compress` 按需返回 `Uint8Array` |
| `NativeFrame.recycle` | 已接入 | 显式释放；引擎销毁时自动回收遗留句柄 |
| `yolo.load`、`detector.detect(NativeFrame)` | 已接入 | OpenCV 5.0 DNN 后端 NativeFrame 直连，支持应用现有 ABI |
| `detector.close` | 已接入 | 显式释放 OpenCV 模型；引擎销毁时自动清理遗留 detector |
| `drawing` | 已接入 | 全屏悬浮层绘制：`show()` / `update(detections, stats)` / `hide()`，用于检测框与耗时显示 |
| `files` | 已接入 | 读写、追加、列表、存在性判断、复制/移动/重命名/删除等白名单方法 |
| `http` | 已接入 | 同步 `get` / `post` / `postJson` / `request`，OkHttp 3.10 白名单桥 |
| `timers` | 已接入 | Native 定时器队列 + 引擎线程事件循环，可被停止信号打断 |
| `app` | 已接入 | `launch` / `openUrl` / `getInstalledApps` / `getAppInfo` 白名单桥 |
| `storages` | 已接入 | `create` / `put` / `get` / `remove` / `contains` / `clear`，基于 SharedPreferences |
| `device` | 已接入 | 设备信息（model/brand/sdkInt 等）、`isScreenOn` / `vibrate` / `getBattery` / `getAvailMem` / `getTotalMem` |
| `shell` | 已接入 | 普通/Root 执行、Root 可用性检查、超时中止、输出限制和引擎关闭子进程回收 |
| `dialogs` | 已接入 | `alert` / `confirm` / `prompt` / `select` / `singleChoice` / `multiChoice` |
| `engines` | 已接入 | 启动脚本、枚举引擎、停止引擎；子脚本默认使用 QuickJS，可显式选择 Rhino |
| `threads`、`events` | 常用能力已接入 | 每个 worker 使用独立 QuickJS；支持 JSON 参数、返回值/异常查询与等待（`waitForResult` / `promise()`）；支持按键、触摸、通知、Toast 和手势观察，系统回调通过有界队列回到所属引擎线程；`events.bus` 提供 worker 间共享事件总线（JSON 载荷） |
| `floaty` | 已接入 | `window(xmlOrConfig)` / `rawWindow` / `closeAll`，支持创建配置 `{ x, y, visible: false, touchable, draggable }`（创建即定位，不再左上角闪现；`touchable:false` 等于触摸穿透）；窗口级 `setSize` / `setPosition` / `getX` / `getY` / `getWidth` / `getHeight` / `findView` / `setTouchable`（false = `FLAG_NOT_TOUCHABLE` 穿透） / `setDraggable` / `setAdjustEnabled` / `isAdjustEnabled` / `requestFocus` / `disableFocus` / `exitOnClose()` / **`show` / `hide` / `setVisibility` / `isShown` / `setContentVisible` / `setAlpha` / `getAlpha` / `setScale` / `setScaleX` / `setScaleY` / `animate` / `stopAnimation` / `javaView` / `view`（同一真 View 两个名字）**（显隐同步生效，隐藏 = `removeViewImmediate` 真正离屏；alpha/scale 直接作用于根 View，不重排布局）；形状/触摸区域：**`setOutlineShape('circle'|"roundRect"|"rect"[, radius])` / `setCornerRadius(px|'50%')` / `setClipToOutline(bool)`（窗口与控件都有，仅影响绘制与窗口内命中）；`setShape` / `setTouchShape` / `setTouchableRegion(x,y,r)` / `clearTouchRegion` / `setTouchableOnlyInShape` / `getTouchRegion()` / `floaty.touchRegionInfo()` / `floaty.supportsTouchRegion()`（按区域输入需系统隐藏 API，实测 Android 15 不支持，可用时才会真正生效）**；XML 新增 `clipToOutline="true"` 与 `cardCornerRadius="50%"`；`getX()/getY()` 立即返回设定值，`getX(true)/getRealX()` 读主线程 flush 后的生效值；`window.<id>` 控件代理：`click()`/`click(fn)` / `longClick()`/`longClick(fn)` / `on("click"/"long_click"/"key"/"touch")` / `onKey` / `onTouch` / `getText` / `setText` / `setVisibility` / `requestFocus` / `attr(name[, value])` 与属性式读写（`view.text = 'x'`）**+ `javaView` / `view` / `animate(props, duration, easing)` / `stopAnimation()`**；链式调用统一返回代理自身；不存在的控件 id 返回 undefined（与 Rhino 的 findView 回退一致）；触摸/按键事件经有界队列回到引擎线程；QuickJS 无 E4X，XML 使用字符串。**XML 单位与 Rhino 一致：`w/h` 不带单位按 dp、`margin*/padding*` 按 px，尺寸建议显式写 `px`；XML 根控件自带 `w/h` 且窗口 `wrap_content` 时按内容尺寸布局**；overlay 窗口的 z-order 由创建顺序与系统决定（无法任意插层）；触摸穿透/圆形形状的完整说明见 `docs/guides/悬浮窗触摸穿透说明.md` |
| `runOnMainThread` / `runOnUiThread` / `postToMain` | 已接入 | 在主线程同步执行脚本函数（等价 Rhino 的 `ui.run(fn)`）并回传返回值/异常；控件的真实 View API（`setPadding`、`ObjectAnimator` 等）只能在主线程调用，需用它包一层（否则系统抛 `CalledFromWrongThreadException`）；`window.post(fn[, delay])` 是窗口绑定的同语义快捷方式 |
| 悬浮窗跨引擎 / 生命周期 | 已接入 | 窗口注册表为**进程级**：`floaty.windowById(id)` / `floaty.getWindow(id)` / `floaty.exists(id)`；**把窗口直接放进 threads 参数（`threads.start(fn, {win: win})`）也会自动还原成可操作窗口**；`win.on('attached'/'detached'/'close')`；引擎销毁只关闭自己创建的窗口 |
| 原生动画写入方式 | 已接入 | 控件/窗口代理自带 `__javaHandle`：`ObjectAnimator.ofFloat(win.c, 'alpha', 1, 0)` 可直接传（Animator 需 Looper 线程 ⇒ 包在 `runOnMainThread` 里，或用一行式 `objectAnimator(win.c, 'alpha', 1, 0, 300)` / `animateView(win.c, {alpha: 1}, 300, 'bounce')`）；`view.animate(props, duration, easing)` / `win.animate(...)` 内部已处理主线程 |
| 尺寸读取语义 | 已接入 | `attr('width'/'height')` 与 `win.getWidth/getHeight`：布局未完成时回退 layoutParams / `setSize` 设定值，不再读出 `0px` |
| `ui` / `$ui` | 已接入 | 覆盖层模式的 XML 布局 + `ui.<id>`/`$ui.<id>` 控件代理（`setText`/`getText`/`setVisibility`/`setBackgroundColor`/`setTextColor`/`setTextSize`/`setEnabled`/`setDataSource`/`click()/click(fn)`/`longClick`/`on`/`attr` 与属性式读写）+ `ui.emitter`（控件事件同步转发）+ `ui.findView`/`ui.layoutFile`/`ui.post(fn, delay)`/`ui.isUiThread`/`ui.statusBarColor`/`ui.close` |
| `ui` / `$ui` | 基础实现 | 支持 XML 布局、常用控件、点击和列表事件；`ui.run(fn)` 保持 Rhino 写法兼容；E4X/JSX、Java 反射与完整动态绑定仍需 Rhino |
| `exit()` / `keys` | 已接入 | `exit()` 以正常完成结束脚本（不再报中断错误）；`keys.back/home/menu/enter` 等常量用于 `on("key")` 监听 |
| 选择器 / UiObject | 已接入 | `selector()`、`text/id/desc/className/bounds` 等过滤器与动作挂在全局作用域，`find/findOnce/findOne/untilFind/untilFindOne/exists/waitFor` 与控件属性、动作、树访问直接复用 Rhino 的 `UiSelector`/`UiObject`/`UiObjectCollection` |
| 手势与输入 | 已接入 | `gesture/gestureAsync`、`gestures/gesturesAsync`（多指，坐标经 `screenMetrics` 缩放）、`input(text)`（无障碍 ACTION_APPEND_TEXT）；`setScreenMetrics(w, h)` 与 `SetScreenMetrics` 均可用于分辨率适配 |
| RootShell 按键助手 | 已接入 | `KeyCode/Tap/Swipe/Screencap/Text` 与 `Back/Home/Menu/Power/Camera/Up/Down/Left/Right/OK/VolumeUp/VolumeDown`，内部执行 `shell(cmd, {root:true})`，无 root 时抛出明确错误 |
| 顶层兼容别名 | 已接入 | `print/err`、`random`、`sync`、`auto`（含 normal/fast 模式与 flags）、`setImmediate`/`clearImmediate`、`waitForActivity/WaitForPackage`、`launchApp`、`home` 等 Auto.js 4.x 顶层写法 |
| 内置模块 | 已接入 | `crypto`（md5/sha1/sha256/digest/hmacSha256/base64）、`zips`（zip/unzip/list）、`sqlite`（`open` + `exec/select/insert/update/delete/transaction/close`；顺带修正了 `Database.toContentValues` 把小数截断成整数的旧 bug，Rhino 同享）、`util`（判定/`format`/`join`/`range`/`extend`/`sum`）、`automator`（映射同名全局）、`context`（包名与常用目录）、`rawInput`（shell `input`，无需 root）；`require('crypto')` 等内置名同样可用 |
| 运行时状态 | 已接入 | `isRunning`/`isStopped`/`notStopped`/`stop`、`requiresApi(api)`、`requiresAutojsVersion(version)`；`files.join` 一并补齐 |
| 控制台浮窗 | 已接入 | `openConsole()` / `clearConsole()` 与 `console.show/hide/clear/setTitle/setSize/setPosition`；复用 `runtime.console`，浮窗实现类上的 `setSize/setPosition` 由宿主内部反射调用 |
| 选择器 JS 谓词 | 已接入 | `filter(fn)` / `addFilter(fn)`：Java 遍历控件时通过 `__aiInvokeCallback` 同步回调脚本函数（同一引擎线程，谓词参数是真正的 UiObject 代理）；`findAndReturnList(node, max)` 返回带 `size()/get()` 的列表（对齐 Rhino 的 `java.util.List`） |
| io / 文本文件 | 已接入 | `files.open(path[, mode[, encoding[, bufferSize]]])` 与全局 `open`（Rhino 的 `__io__.js` 把 `files.open` 提升为全局）：`r` 可 `read/read(size)/readline/readlines`（共用同一游标），`w` 打开即清空后 `write/writeline/writelines`，`a` 追加，未知模式返回 null；`io` 对象暴露 `open` 与 `files` |
| web / 可注入 WebView | 已接入 | `newInjectableWebView()` / `newInjectableWebClient()`：`inject(script[, callback])`、`loadUrl` / `loadData` / `reload` / `stopLoading` / `getUrl`；页面里的 `rhino.call(name, ...args)` / `rhino.eval(code)` 由 WebView 线程入队、脚本引擎线程执行（异常会写到脚本控制台）；`injectAndWait` 需要跨线程同步求值，明确报错。**页面桥须在 `loadUrl/loadData` 之前注册**（Chromium 只在文档开始时注入 JS 接口）：`newInjectableWebView()` 构造时自动完成，客户端可手动 `attach(webView)`；`rhino.call` 的参数经 `String` 重载拼成 JSON 数组（Android JS 桥不支持 `Object`/可变参数，混入数值重载会把字符串参数强转成 0） |
| 跨线程 JS 回调 | 已接入 | Java 线程把任务放进 `ConcurrentLinkedQueue`，native 事件循环（定时器循环与 `sleep` 切片）在引擎线程上取出并调 `__aiRunJsTask`，避免多线程同时进 QuickJS 上下文；选择器谓词用同一套回调表（同步路径） |
| Java 互操作 | 已接入（完整反射） | `Packages`/`importClass`/`importPackage`/`Java.type`；类与实例统一 long 句柄 + JS 侧 Proxy：静态/实例方法、字段读写、JavaBean 属性（`file.path`）、**嵌套类**（`android.os.Build.VERSION.SDK_INT`、`java.lang.Thread.State.NEW`）、`new` 构造、自动重载解析（整数值优先 int/long）、JS 数组↔ Java 数组/可变参数、Java 异常转脚本 Error、`obj.getClass()` 返回可用 `java.lang.Class` 对象；`context` 即真实 Android Context，并预导入 Rhino 的 `Intent`/`Paint`/`Shell`/`KeyEvent`/`MutableOkHttp`/`Canvas`/`Image`/`RootAutomator`/`Input`/`Module`。**注意：与 Rhino 一致开放任意反射，白名单桥不再是安全边界（用户拍板）** |
| continuation | 部分接入 | `delay(millis)` = 阻塞 `sleep`；`enabled` 恒为 `false`，`await/create` 与 `Promise.prototype.await` 明确报错引导到 `await` 语法 |

全部 QuickJS 示例统一位于 `apps/app/src/main/assets/sample/QuickJS 新引擎/`：根目录保留入口脚本（`新模块快速上手.js`、`QuickJS 模块示例.js`、`QuickJS运行环境测试.js`），其余按分类存放——`悬浮窗/`、`图色处理/`、`YOLO目标检测/`、`引擎与线程/`、`界面与交互/`、`系统与设备/`、`文件与网络/`、`图像与视觉/`、`回归测试/`。

### Native Frame 用法

```javascript
// @engine quickjs
if (!requestScreenCapture('portrait')) throw new Error('未获得截图权限');

// 默认 captureScreen() 保持全分辨率；视觉任务建议用 720p 快速帧。
const frame = captureScreen({ mode: 'fast', size: 720 });
// 强制等待新帧；100ms 内没有新帧时自动回退到最近有效帧。
const freshFrame = captureScreen({ mode: 'fast', size: 720, fresh: true, timeout: 100 });
freshFrame.recycle();
try {
    console.log(frame.width, frame.height);           // 原屏幕逻辑尺寸
    console.log(frame.pixelWidth, frame.pixelHeight); // Native 实际处理尺寸
    const color = frame.pixel(100, 200);
    const red = images.findColor(frame, '#ff0000', {
        threshold: 8,
        region: [0, 0, frame.width, frame.height]
    });
    const template = images.read('./button.png');
    try {
        console.log(images.findImage(frame, template, { threshold: 0.9 }));
    } finally {
        template.recycle();
    }
} finally {
    frame.recycle();
}
```

`NativeFrame` 在 JS 中只保存句柄和尺寸信息；完整像素始终留在 C++ 内存。默认截图立即采用当前最新缓存，不会在静止页面无限等待；`fresh: true` 用于等待新帧，`timeout` 默认 100ms，超时后回退最近有效帧。`captureScreen()` 或 `{ mode: 'full' }` 保持原分辨率和像素精度；`{ mode: 'fast', size: 720 }` 与 `size: 640` 在 Native 创建帧时直接缩放。快速帧的 `pixel`、找色、多点找色、模板匹配、YOLO 区域和检测框统一使用原屏幕坐标，桥接层自动完成双向映射。长循环必须用 `try/finally` 调用 `recycle()`，防止在单个长时间脚本内积压截图。可直接运行 `apps/app/src/main/assets/sample/QuickJS 新引擎/图色处理/01-图色 API 自动回归.js`、`05-全分辨率与视觉加速对比.js` 与 `07-截图首帧与稳定耗时测试.js` 检查整条链路。

### OpenCV 直连（与 Rhino 写法一致）

```javascript
// @engine quickjs
const frame = captureScreen();
try {
    // Rhino 的 img.mat 等价写法：帧 → Mat，直接调用 OpenCV Java API
    const mat = images.toMat(frame);              // CV_8UC4
    const gray = new images.opencv.Mat();
    const binary = new images.opencv.Mat();
    try {
        images.opencv.Imgproc.cvtColor(mat, gray, images.opencv.Imgproc.COLOR_RGBA2GRAY);
        images.opencv.Imgproc.threshold(gray, binary, 120, 255, images.opencv.Imgproc.THRESH_BINARY);
        const back = images.matToImage(binary);   // clone，binary 之后 release 也不影响
        try {
            console.log(back.width, back.height, images.pixel(back, 0, 0));
        } finally {
            back.recycle();
        }
    } finally {
        mat.release();
        gray.release();
        binary.release();
    }

    // 快捷封装（内部同样走 OpenCV Java API + 帧桥）
    const mask = images.inRange(frame, '#000000', '#666666');
    try {
        console.log(images.findAllPointsForColor(mask, '#ffffff', { threshold: 8 }).slice(0, 5));
    } finally {
        mask.recycle();
    }
} finally {
    frame.recycle();
}
```

`images.opencv` 是 Rhino `__images__.js` 里 `opencvImporter` 的 QuickJS 等价物（类映射，惰性解析）；Rhino 中 `img.mat` 这类隐式转换在 QuickJS 里显式写成 `images.toMat(frame)` / `images.matToImage(mat)`，其余参数与返回语义保持一致。

### NativeFrame + OpenCV YOLO

```javascript
// @engine quickjs
const root = 'asset://sample/QuickJS 新引擎/YOLO目标检测/DNN/models/';
const detector = yolo.load({
    backend: 'dnn',
    model: root + 'yolo26_640.onnx',
    labels: root + 'labels.txt',
    inputSize: 640
});
try {
    if (!requestScreenCapture('portrait')) throw new Error('未获得截图权限');
    const frame = captureScreen({ mode: 'fast', size: 720 });
    try {
        const detections = detector.detect(frame, { confidence: 0.25, nms: 0.45 });
        console.log(detections, detections.preprocessMs, detections.inferenceMs);
    } finally {
        frame.recycle();
    }
} finally {
    detector.close();
}
```

可直接运行 `apps/app/src/main/assets/sample/QuickJS 新引擎/YOLO目标检测/DNN/` 中的 OpenCV 5.0 DNN 案例，包括环境测试、单帧、实时、持续识别、ROI 区域检测与基准脚本。`yolo.load({ backend: "dnn", model: ..., ... })` 加载 ONNX 模型，对 JS 暴露 `detect` / `close` / `isClosed`。持续识别脚本用可中断 `sleep` 分片控制帧率，任务列表停止时最迟约 100ms 中止；`detect` 支持 `region: [x, y, w, h]` 区域检测，检测框坐标会自动回移到全屏坐标系。

**OpenCV 引擎实测结论（小米 K40，骁龙870；2026-09-12 以 640 模型重测）**：
- **640 基线（官方构建，3 预热 + 30 帧）**：`inputSize=640, threads=4` 时 load 47.6ms、预处理 **1.64ms**、推理 **80.48ms**、总计 **82.11ms（12.2 FPS）**；同一台机 320 为 22.55ms（44.4 FPS），代价约 3.6×。
- **线程数扫描（640）**：4 线程 82.1ms 最优；6 线程 101.9ms（-24%）；2 线程 141.6ms（-72%）。默认 `threads` 取 `min(4, 核数)` 与该结论一致。
- **推理侧对比（用临时实验开关实测，640 / 4 线程；开关已在验证后移除）**：CPU_FP16(80.3ms)、ENGINE_ORT(81.3ms) 与默认 CPU/AUTO(80.8~81.6ms) 无差异；ENGINE_CLASSIC 慢 12%（93.0ms）；OpenCL 沿用下方 686ms 的负结论。**当前 `ENGINE_AUTO` + 默认 CPU target 已是这条栈上的最优组合，不要再指望换 target/引擎提速。**
- **模型侧实测（同一帧对比）**：`max_det` 300→100 检出逐位一致、耗时不变（81.99 vs 82.09ms）⇒ 不值得换；**INT8（ONNX QDQ）在 OpenCV DNN 上是死路**——同一帧 0 检出（结果错）且推理 366.6ms（4.6× 慢），ARM 侧没有 int8 快速路径，Q/DQ 只被当普通层执行；换架构也没有空间（yolo26n 2.57M/6.12 GFLOPs@640 已是 n 级最轻，yolo11n 2.62M/6.61）。
- 预处理占比仅 **2%**：640 下把每帧 `blobFromImage` 改为预分配 blob + `blobFromImageWithParams` 后，预处理 1.64 → 1.64ms、总计无明显变化（收益仅剩减少每帧 4.9MB 的分配/GC 抖动），该尝试已回退。**要提速只能动输入尺寸/模型本身（精度换速度），或换运行时（NCNN/ONNX Runtime）。**
- `OpenCvYoloDetector` 采用 `Dnn.readNetFromONNX(path, Dnn.ENGINE_AUTO)`、target 默认 CPU，不对外暴露 target/图引擎选项。
- **模型库（示例 `YOLO目标检测/模型管理.js`）**：发布包只内置 `yolo26_640.onnx` 作保底；用户可用模型管理把 `.onnx`（同名 `.txt` 作标签）导入库目录（默认 `/sdcard/脚本/模型库`）并切换当前模型。选择存在 `storages` 的 `aijspro.yolo.models`（`dir` / `current` / `inputSize.<id>` / `labels.<id>`），六个 YOLO 案例启动时读取它并打印 `[模型] 本次识别使用：…`，结构化输出带 `model=` 字段。面板每 2 秒按「文件列表 + 当前模型 + 库目录」签名比对，变了才重绘（在文件管理器里改名/增删会自动跟上）；每行可「使用 / 验证 / 删除」，另有「重命名模型」（同时改 `.onnx` 与同名 `.txt`，并把 `inputSize.<id>`、`labels.<id>` 与 `current` 迁到新名字）。
- **「跑一帧验证」必须把截图与推理分开报（dev-544 修）**：`images.captureScreen({ fresh: true, timeout })` 在**屏幕静止**时会一直等到超时（超时返回最后一帧，不抛错），真正抛 `TimeoutException: Timed out waiting for the first screen capture frame` 的是**一帧都还没到**——即投屏尚未建立（系统「开始录制或投屏」确认框没点、或 MIUI 把 `PROJECT_MEDIA` 拒了）。把这种错报成「✗ 推理失败（输出需要是 end2end）」纯属误导。现在的写法：`requestScreenCapture('portrait')` 之后**循环等待最多 9 秒**（每 ~400ms 用 `fresh: false` 立即取最近一帧），期间提示用户点「立即开始」；截图没成就只说「跳过推理验证（与模型无关）」并给处理办法，只有 `detector.detect()` 抛错才算推理失败。
- **标签（labels）路径必须先验证存在（dev-546 修）**：`labels.<id>` 存的是路径，一旦存进不是文件的值（手误填了名字、目标文件被删），下次 `yolo.load({ labels })` 会抛 `IllegalArgumentException: 无法读取 YOLO labels: <path>`，整个模型被报成「✗ 加载失败」；而六个识别案例都先做 `files.isFile(labels) ? labels : ''` 过滤，同一模型推理完全正常——表现就是「管理页说加载失败，但推理能用」。现在管理器与案例口径一致：写入时拒绝非文件路径；读取时忽略并清除失效值（面板提示「已忽略并清除」）；若标签文件存在但读不了，退一步按「无标签」加载（只出 classId）。
- **输入尺寸对速度/精度的影响（2026-09-12 实测，K40，同一组图同一帧）**：
  - 速度（pre+inf，3 轮取中位数，去掉预热）：**160 = 7.3~7.6ms（131~136 FPS）/ 320 = 21.3~22.3ms（45~47 FPS）/ 640 = 79.9~81.1ms（12.3~12.5 FPS）**。
  - 精度（7 张标准图、32 个已人工核对的目标，conf≥0.25 且 IoU≥0.5 同类别，以 640 为参考）：**320 召回 25/32 = 78%**（匹配框平均 IoU 0.895、多出 1 个）；**160 召回 14/32 = 44%**（IoU 0.847、多出 8 个）。160 不只是漏检，还会**类别错乱**（马被识别成 `cow`/`sheep`、人变 `refrigerator`）。
  - 按「目标在该输入里的像素长边」分档（160 / 320 / 640）：`<16px` → 10% / 44% / 100%；`32~64px` → 29% / 75% / 100%；`64~128px` → 91% / 86% / 100%。⇒ **60px 左右是分水岭**：目标在输入里小于 ~16px 基本检不到。
  - 换算到 1080×2400 竖屏手机（letterbox 缩放系数 = 输入边长 / 2400）：方形 160 → 0.0667（≈只能看 ≥900px 的目标）；方形 320 → 0.1333（≥450px）；方形 640 → 0.2667（≥225px）。
  - **竖屏矩形输入是「同精度减半成本」的解法**（引擎已支持 `inputWidth/inputHeight`）：`min` 缩放 + 居中灰边 ⇒ 方形 320 在 1080×2400 上的实际内容只有 144×320，左右各有 88px 灰边被白白卷积。用 `imgsz=(320,160)` 导出的竖屏模型（输入 160×320）内容同样是 144×320，实测 **12.4ms（80 FPS）**，与方形 320 的检出**一一对应（27/35 匹配、IoU 0.967）**；`imgsz=(640,288)` 的 288×640 与方形 640 同样等价（33/35 匹配、IoU 0.960），但只花 **37ms（27 FPS）**。注意 ultralytics 导出会把尺寸向上取整到 32 的倍数（要 144×320 得传 `(320,160)`）。
  - 复现脚本（都在 `.artifacts/yolo-bench/`）：`export-models.py`（导出多尺寸）、`size-bench.js` / `rect-bench.js`（真机跑，输出 `IMG/BENCH/DET/AVG`，目录从 `/sdcard/yolo-bench/bench-dir.txt` 读）、`analyze-gt.py`、`analyze-table.py`（召回/分档）、`compare-rect.py`（方形 vs 矩形一致性）、`std-overlay.jpg`（人工核对用的叠加图）。
- 经典引擎（`ENGINE_CLASSIC`）：640 下 93ms，仍慢于新引擎。
- OpenCL（`DNN_TARGET_OPENCL_FP16` 等）：自编 `WITH_OPENCL=ON` 版实测 **686ms** —— OpenCV DNN 的 OCL 后端仅针对 Intel GPU 优化，在 Adreno 上是负优化，**不要启用**。
- 预处理骨架未变：letterbox（`LETTERBOX_GRAY=114`、居中、`min` 缩放）+ 1/255 归一化，坐标按 `(coord - pad) / scale` 回映；640 下预处理平均 **1.64ms**。
- 内置模型已由 `yolo26_320.onnx` 换为 **`yolo26_640.onnx`**（输入固定 640×640，`inputSize` 默认 640，导出参数与旧模型一致：opset 12 / simplify / end2end，输出仍为 `[1,300,6]`）。下文 45.42ms / 18.5 FPS / 26.4 FPS 等数字均为 **320 时期**数据，仅供参考。
- MIUI 在工作区退到后台后会将纯脚本进程放入后台受限调度组。运行脚本期间现使用计数的前台服务租约，QuickJS 执行线程使用 `THREAD_PRIORITY_DISPLAY`；脚本结束后自动恢复线程优先级，且在用户未开启常驻服务时释放租约。K40 后台 150 帧同帧实测由约 **104–112ms** 恢复到平均 **44.37ms**（p50 **42.52ms** / p95 **54.48ms**）；完整截图 + YOLO 100 帧端到端为 **26.4 FPS**。

### files / http / timers 白名单 API

```javascript
// @engine quickjs

// 定时器：脚本结束后引擎线程会留在原生事件循环里直到定时器清空
let ticks = 0;
const id = setInterval(() => console.info('tick', ++ticks), 250);
setTimeout(() => { clearInterval(id); console.log('interval 已停止', ticks); }, 1100);

// files：与 Rhino 同名 API，路径相对脚本目录解析
const file = files.cwd() + '/quickjs_test.txt';
files.write(file, '第一行\n');
files.append(file, '第二行\n');
console.log(files.read(file), files.exists(file), files.isFile(file));
console.log(files.listDir('.'));
files.remove(file);

// http：同步白名单桥，返回 { statusCode, statusMessage, url, method,
//        headers, body: { string, contentType, json() } }
// 注意：example.com 等站点在国内不可达，OkHttp 30s 超时 + 3 次重试会拖很久，测试用 baidu 等可直达域名
const res = http.get('https://www.baidu.com/', { headers: { 'User-Agent': 'AI.jsPro-QuickJS' } });
console.log(res.statusCode, res.body.string.substring(0, 40));

const json = http.postJson('https://httpbin.org/post', { hello: 'QuickJS' });
console.log(json.statusCode, json.body.json().data);
```

### app / storages / device 白名单 API

```javascript
// @engine quickjs

// device：设备信息（静态属性缓存，只读一次）
console.log('设备:', device.brand, device.model, 'Android', device.release);
console.log('屏幕:', device.width, 'x', device.height, 'DPI:', device.sdkInt);
console.log('电量:', device.getBattery().toFixed(1) + '%');
console.log('屏幕亮:', device.isScreenOn());

// storages：数据持久化（基于 SharedPreferences，值自动 JSON 序列化）
const store = storages.create('test_quickjs');
store.put('counter', 42);
store.put('name', 'QuickJS 测试');
console.log('counter:', store.get('counter'));
console.log('name:', store.get('name'));
console.log('contains counter:', store.contains('counter'));
store.remove('counter');
console.log('removed:', !store.contains('counter'));

// app：应用管理
console.log('当前包名:', app.launchPackage ? '支持' : '不支持');
var info = app.getAppInfo('com.android.settings');
console.log('设置:', info.label, 'v' + info.versionName);
```

- `files` 首批：`path`、`cwd`、`getSdcardPath`、`exists`、`isFile`、`isDir`、`read`、`write`、`append`、`create`（含父目录）、`ensureDir`、`listDir`、`remove`、`rename`、`copy`、`move`。
- `timers` 首批：`setTimeout`、`setInterval`、`clearTimeout`、`clearInterval`。定时器由 C++ 端定时器表 + 引擎线程条件变量事件循环驱动，`stopAll()` 会立即唤醒并中止。
- `http` 首批：`http.get(url, options)`、`http.post(url, data, options)`、`http.postJson(url, data, options)`、`http.request(url, options)`；同步执行于脚本线程，支持 `headers`、`contentType`、`body`，`post` 对对象数据自动做表单编码。暂不支持 `bytes()`、`postMultipart` 和异步回调。
- `app` 首批：`app.launch(packageName)`、`app.openUrl(url)`、`app.getInstalledApps()`、`app.getAppInfo(packageName)`；已扩展 `launchPackage` / `getPackageName` / `getAppName` / `openAppSetting` / `viewFile` / `editFile` / `uninstall` / `startActivity(opts)`。
- `storages` 首批：`storages.create(name)` 返回存储对象，支持 `put(key, value)` / `get(key, default)` / `remove(key)` / `contains(key)` / `clear()`，值自动 JSON 序列化。
- `device` 首批：只读属性 `width`/`height`/`model`/`brand`/`board`/`hardware`/`sdkInt`/`release`/`buildId`/`display`/`product`/`manufacturer`；方法 `isScreenOn()` / `vibrate(ms)` / `getBattery()`；已扩展 `isCharging()` / `getBrightness()` / `getBrightnessMode()` / `cancelVibration()`。
- `shell` 首批：`shell(cmd)`（普通应用 UID）、`shell(cmd, true)` / `shell(cmd, {root: true, timeout, maxOutput})`（Root）、`shell(cmd, {shizuku: true, timeout, maxOutput})` 或 `shizuku.shell(cmd, options)`（Shizuku/Sui），返回 `{ code, result, error }`；支持超时中止与最大输出限制。Shizuku 辅助 API：`shizuku.isAvailable()`、`shizuku.hasPermission()`、`shizuku.requestPermission(timeout)`。
- `dialogs` 首批：`alert` / `confirm` / `prompt`（= `rawInput`） / `select` / `singleChoice` / `multiChoice`。
- `engines` 首批：`execScript` / `execScriptFile` / `myEngine` / `all` / `stopAll` / `stopAllAndToast`。
- `threads` / `events`：`threads.start(fn|src, args)` / `exec` / `currentThread` / `shutDownAll`，每个 worker 独立 QuickJS 引擎；thread/engine 句柄支持 `getResult()` / `waitForResult(timeout)` / `join` / `interrupt`；`events.on/once/emit/removeListener/removeAllListeners/listenerCount`（事件总线仅限同一引擎，函数任务不捕获外层闭包）。

## 4. 关键代码

```text
modules/engine/src/main/java/com/stardust/autojs/
├─ script/JavaScriptSource.java           首行指令识别和引擎路由
├─ engine/QuickJsJavaScriptEngine.java    Java 引擎生命周期
├─ engine/QuickJsNativeBridge.java        JNI 方法声明和动态库加载
├─ engine/QuickJsHostBridge.java          白名单 Java API 桥
├─ engine/QuickJsException.java           Native JS 异常类型
├─ AutoJs.java                            Rhino / QuickJS 双引擎注册
└─ ScriptEngineService.java               两种执行模型分流

modules/engine/src/main/cpp/
├─ CMakeLists.txt                         libquickjs + libquickjs_jni
├─ quickjs_jni.cpp                        Context、求值、中断和 Host API
├─ native_frame_store.{h,cpp}             cv::Mat 句柄、找色和模板匹配
├─ build-quickjs.ps1                      三 ABI 构建与 jniLibs 同步
└─ third_party/
   ├─ quickjs/                            固定版本的官方 QuickJS 源码
   └─ opencv/include/                     OpenCV 5 Android C++ 头文件
```

## 5. 构建

完整重建 Native 库和 Debug APK：

```powershell
.\build-miuix-debug.ps1
```

只重建 QuickJS Native 库：

```powershell
.\modules\engine\src\main\cpp\build-quickjs.ps1
```

已有 Native 库时只构建 Android 工程：

```powershell
.\build-miuix-debug.ps1 -SkipNative
```

当前输出 `armeabi-v7a`、`arm64-v8a`、`x86`，两个 QuickJS `.so` 都使用 16 KiB 最大页对齐。

## 6. 版本与来源

- 上游：Fabrice Bellard 官方 QuickJS；
- 固定版本：`2026-06-04`；
- 官方源码包：`quickjs-2026-06-04.tar.xz`；
- 源码包 SHA-256：`B376E839B322978313D929FD20663B11BA58B75DF5A46C126DD19EA2FA70AD2A`；
- 许可证：MIT，原始 `LICENSE` 已保留在 vendored 源码目录。

## 7. 下一阶段建议

当前 QuickJS 已具备第一批到第三批白名单桥（`console`/`toast`/`sleep`、`files`/`http`/`timers`、`app`/`storages`/`device`、`shell`/`dialogs`/`engines`、`threads`/`events` 基础版）、完整的 `images` 模块（clip/resize/scale/grayscale/cvtColor/save/compress/findColor/findMultiColors/findImage/matchTemplate）以及 `dialogs.build()` 和 `engines` 完整对象封装。后续建议按实际需求推进：

1. `threads` 增强：已完成 worker 间共享事件总线（`events.bus.on/once/off/emit/removeAllListeners`，载荷 JSON 序列化，各自引擎线程轮询派发）与异步 Promise 封装（`thread.promise()`，thread 对象可直接 `await`）；同步返回值/异常等待和 JSON 参数传递已实现；函数任务仍不捕获外层闭包（`threads.start(function)` 会序列化源码，数据需经 `args` 传入）；
2. `images` 高级功能：旋转、阈值化、模糊、形态学、Base64 转换和 OCR 桥；
3. Native Frame 可增加句柄数/占用字节调试统计，用于长时脚本泄漏诊断。

QuickJS 后续的 ImGui 能力定位为脚本可调用的悬浮窗 API，不是 APK 主界面。具体线程、生命周期和 Native 边界见 [QuickJS ImGui 悬浮窗 API 边界](QUICKJS_IMGUI_FLOATY.md)。

注意：NCNN / ONNX Runtime YOLO 后端已于 2026-08-30 移除，相关示例与第三方库不再维护；YOLO 统一走 OpenCV 5.0 DNN 后端。
