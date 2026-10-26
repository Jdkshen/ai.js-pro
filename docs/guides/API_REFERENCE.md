# AI.js Pro API 参考（Rhino / QuickJS / Node24）

> 面向在本项目里写脚本的开发者。版本号不写死，以仓库更新页和实际 APK 为准；文中用“仅 Node/仅 Rhino”等文字标注归属，未标注的功能不等于三引擎都支持。
> “有导出”不等于“真机已验证”；本文不把类型声明数量、模块数量或工具数量当成兼容率。路径受应用沙箱/存储权限约束，自动化动作还受无障碍、截图和悬浮窗权限约束。

## 0. 三引擎速查
| 主题 | Rhino | QuickJS | Node24 |
|---|---|---|---|
| 首选标记 | `"rhino";` 或 `// @engine rhino`；不写且工程未配置时 Rhino | `"quickjs";` 或 `// @engine quickjs` | `"nodejs";` / `"node";` / `"nodejs ui";`；`// @engine node24` |
| UI 标记 | `"ui";`（XML/E4X） | `"ui";`（XML 用字符串） | `"ui nodejs";`；或先声明 `"ui";` 再声明 `"nodejs";` |
| 模块 | 内置 `require` + 相对/npm（jvm-npm） | 内置 `require` + 兼容模块 | Node 原生解析；`require('files')` 等被适配器接管 |
| 选择器 | `selector()` 等，同步 | `selector()` 等，同步 | `select()` 查询 Promise；节点动作同步 |
| 截图 | `images.*`，同步 | `images.*`，同步 | `media_projection`/`accessibility`，Promise |
| OCR/YOLO | `$ocr`/`yolo`，同步 | `$ocr`/`yolo`，同步 | `ocr` Promise；`yolo` 同步 |
| UI/浮窗 | 传统 `ui` + `floaty` | 传统 `ui` + `floaty` | `ui.Activity` + `floating_window` |
| Java/dex | `Packages`、`runtime.loadDex` | `Java.type`、`DexClassLoader` | `$java`、`host.runtime.loadDex` |
| 进程 | 脚本进程/主进程路径 | 脚本进程 | `:node24_1..3` 池；旧 UI/对话框在 `:nodeui` |

## 1. 引擎标记与 `require` 安装
下文含 `await` 的片段应放在 `async` 函数内，或支持顶层 await 的 ES Module 中。普通 `.js` 可用 `(async () => { /* 片段 */ })().catch(console.error)`。Node 的 require 片段与 Rhino/QuickJS 全局接口示例按标注分别使用，不要整段混合运行。

引擎选择优先级（`JavaScriptSource.java`）：MCP 的 `engine` 参数 > 首行 `// @engine ...` 或字符串指令 > 工程 `project.json` 的 engine > 默认 Rhino。字符串指令必须独占一行；`"ui";`/`"auto";` 是执行模式，不是引擎，可出现在引擎指令之前。
```js
// Node：UI + 引擎标记；Java 包全局按需安装
"ui";
"nodejs";
require('rhino').install();          // 可选、幂等；安装 java/android/... 包代理
const ui = require('ui');
const fs = require('node:fs');       // 原生 fs，与 require('files') 不同
```
- `require('rhino').install()` 是 **Node 兼容入口**，只补 Java 包/类访问和事件轮询；不支持 `JavaAdapter`。Rhino/QuickJS 不需要也不保证有同名模块。
- Node 的 `$java` 始终可用，`$java.findClass` 要求完整类名；QuickJS 用 `Java.type(...)`，Rhino 用 `Packages`/`importClass`。
- Node 中 `require('fs'|'http'|'path'|'worker_threads')` 是原生模块；`files/storages/accessibility/image/ocr/yolo/ui` 等是本项目模块。`require('events')` 是原生 EventEmitter 加旧式模块级 `emit`，与全局 `events` 不是同一对象。
- 不是所有 Pro 高级模块都已导出；`require` 未命中时交给 Node/npm，找不到就是普通 `MODULE_NOT_FOUND`。

## 2. 日志、退出与保活
```js
console.log('info', value); console.warn('warn'); console.error('err');
toast('短提示');
$autojs.keepRunning();               // Node：任务结束/回调后继续存活
$autojs.stopRunning();
exit(0);                             // Rhino/QuickJS；Node 也提供全局 exit
process.exit(1);                     // Node：非 0 → SCRIPT_ERROR；退出码 0 才是 SUCCESS
```
- Node 普通 headless 脚本默认 **60s** 运行预算；当前服务对 UI/保活脚本不套用这个普通脚本超时，界面持续到关闭或显式停止。旧 `UI_HOLD_MILLIS` 常量不能据此理解成当前 UI 必定 10 分钟自动退出；还需区分 MCP 等待结果的超时与脚本自身运行预算。
- Node 的 `process.exit` 被补丁：先发 `lifecycle.exitRequested`，停保活和浮窗轮询；`exit`/`beforeExit` 钩子抛错不会把退出码改成 1。Rhino/QuickJS 的 `exit()` 直接结束引擎，可在 `events.on('exit')` 做清理；Node 对应 `process.on('exit')`，钩子异常被吞掉。
- Node `sleep(ms)` 用 `Atomics.wait` 阻塞 Node 线程；要让事件循环转起来用 `const { delay } = require('lang'); await delay(ms)`。Rhino/QuickJS 的 `sleep` 同样阻塞。日志不需要权限；停止/超时不是业务异常，不要把 `TIMED_OUT` 当成功。

## 3. 文件、存储、HTTP、Shell、线程、定时器、事件、引擎
### 3.1 files / storages
```js
const files = require('files');
files.write('a.txt', 'hi');                 // 文本写入；目录需自行 ensureDir
const text = files.read('a.txt');
files.writeBytes('a.bin', [1, 2, 3]);
const names = files.listDir('.');           // 文件名数组
const st = files.stat('a.txt');             // Node: {path,size,lastModified,isFile,isDirectory,...}
const h = files.open('a.txt', 'r'); h.readline(); h.close();
files.remove('a.txt');                      // 只删文件；目录用 removeDir
```
- 三引擎共用 `common-api.js` 适配；`files.remove` 对目录不递归。Node 还有原生 `fs`/`fs.promises`，不要混用返回值。
- Node 额外提供 `files.observe(path)`，返回 EventEmitter，带 `startWatching()/stopWatching()/isWatching()`；Rhino/QuickJS 的文件监听是各自实现，不能据此认为三引擎语义一致。
- 对象存储用命名 JSON 存储；Node 的 `$autojs.storages.get/put` 是旧平铺字符串接口，不要拿它存对象：
```js
const store = storages.create('demo');     // 三引擎都有
store.put('user', {name: '甲', n: 1});    // 只接受 JSON 可序列化值
const user = store.get('user', null);      // 第二个参数是 fallback
store.contains('user'); store.remove('user'); store.clear();
```
- 直接值 `undefined`、函数，或循环引用会抛 `TypeError`（对象里的函数属性会被 JSON 序列化忽略）；`null/false/0/''` 会保留。写共享存储需要系统存储权限/SAF，应用私有目录不需要。

### 3.2 HTTP
```js
// Rhino：无 callback 时同步；有 callback 时异步回调 (response, error)
const res = http.get('https://example.com', {headers: {Accept: '*/*'}});
console.log(res.statusCode, res.body.string());
http.postJson('https://example.com/api', {a: 1}, {}, (r, e) => { /* ... */ });

// Node：内置 axios 返回 Promise；也可直接用原生 http/https
const axios = require('axios');
const r = await axios.get('https://example.com', {headers: {Accept: '*/*'}});
console.log(r.status, r.data);
```
- Rhino 的 `http.request` 在无 callback、UI 线程、continuation 可用时可能走 continuation；非 UI 线程无 callback 时是同步 `execute()`。QuickJS 走宿主 `httpRequest`，不要把 Rhino 的同步形状直接套到 QuickJS。
- Node 的 `axios` 只实现 `get/post/request`、最多 5 次重定向、`responseType:'stream'`，不是完整 axios；原生 `http` 与它并存。网络权限 `INTERNET` 是基础，HTTPS/证书/代理由系统/宿主决定。

### 3.3 Shell
```js
// Rhino：第二参数 root
const out = shell('ls /data/data', true);

// QuickJS：同步返回 {code,result,error}，支持 root/shizuku/timeout/maxOutput
const q = shell('echo ok', {timeout: 5000, maxOutput: 4096});

// Node：Promise；cwd/env/timeout 在 options
const shell = require('shell');
const n = await shell.exec('echo ok', {timeout: 5000});
console.log(n.code, n.result, n.error);
```
- Node 当前 `shell.checkAccess()`/`isRootAvailable()` 直接返回 `false`，`shell.Tap`/`KeyEvent` 兼容入口没有真正 root 通道（`Tap.tap`/`KeyEvent.keyCode` 会抛“需要 root”），root/adb 分支尚未接通；内置示例里这些分支不能当作已验证。Rhino/QuickJS 的 root/Shizuku 能力按各自宿主实现，QuickJS 支持 `shizuku`。shell 以应用 UID 运行，超时只保证终止同组进程，不是完整沙箱。

### 3.4 threads / timers
```js
const thread = threads.start(function (n) {   // Node：参数必须可 JSON 序列化
  let sum = 0; for (let i = 0; i < n; i++) sum += i;
  return {sum, threadId: require('worker_threads').threadId};
}, 100);
thread.join(5000);                            // 同步阻塞等待
console.log(thread.result, thread.error);     // 结束后读取

setTimeout(fn, 1000); setInterval(fn, 1000); clearTimeout(id);
```
- Node 的 `threads.start` 是 **Worker 独立堆**：不能共享闭包，函数会 `toString()` 到 Worker，最多回传约 256 KiB JSON。`threads.exec`/`threads.atomic` 明确抛错，`threads.pool` 不是 Node API。
- Rhino/QuickJS 的 `threads.start(fn)` 也是线程/Worker；QuickJS 参数用 `__args`、结果可用 `waitForResult()`，`join()` 在两端都阻塞；Rhino 另有 `threads.pool`（`__threads__.js`）。三者不要假设可共享可变 JS 对象。定时器在 Node 是原生 Timer，`Deferred`/`lang.delay` 会按需持活；自然退出取决于是否还有定时器/保活。

### 3.5 events / engines
```js
events.on('message', (v) => console.log(v));
events.emit('message', 1);                   // Node 全局 events 是本地 EventEmitter
require('events').emit('legacy', 1);         // Node 模块级旧式兼容入口

const me = engines.myEngine();
console.log(me.id, me.engine, me.execArgv, me.source);
me.on('command', (v) => { me.emit('reply', v); });
const ex = engines.execScriptFile('child.js', {arguments: {n: 1}});
ex.on('success', () => console.log('done'));
ex.on('exception', (execution, error) => console.error(error));
// 引擎已创建后需要时：ex.forceStop()
```
- Node 的全局 `events` 是新建的本地 EventEmitter，不污染 `require('node:events')`；模块级 `require('events').emit` 被接管为 `kind:"events"` 宿主事件，实例的 `emit` 仍是原生方法。
- 跨引擎通信走 `engines.myEngine().on/emit` 或 `engines.execScriptFile`；Node execution 事件为 `start/success/exception`，可先 `on` 后 `engine()` 等待真实 ID，`engines.stopAll()` 影响全局。
- Rhino/QuickJS 的 `engines.execScript`/`execScriptFile` 返回 Java/JS execution，`all()` 是当前运行引擎；子脚本参数通过 `execArgv`/`config.arguments` 传，`path/workingDirectory/loopTimes` 的传播仍需逐项验证。

## 4. 无障碍、选择器与触控
```js
// Node：查询是 Promise，节点动作是同步 boolean
const { accessibility, select } = require('accessibility');
await accessibility.enableService({timeout: 60000});   // 需要系统设置授权
const node = await select({text: '登录', className: /Button$/}).firstOrNull();
if (node) console.log(node.bounds, node.click(), node.setText('测试'));
await accessibility.click(100, 200);
await accessibility.swipe(100, 800, 100, 200, 300);
```
- Node 选择器：`select(spec).firstOrNull()/findFirst({timeout,maxRetries})/all()` 返回 Promise；`findOnce()/findOne()/waitFor()` 同步并阻塞事件循环。`where(fn)`、`from(parent)`、`atMost(n)`、`atLeast(n)` 可用；节点属性 `text/desc/id/className/packageName/bounds/clickable/enabled/editable/childCount/path`。
- Rhino/QuickJS 是传统同步选择器：`selector().text('登录').findOne(2000).click()`；`auto.waitFor()` 等待无障碍。QuickJS 也经同一 Java selector 桥，但不要依赖 Node 的 `select()` 形状。
- 触控：Rhino/QuickJS 用 `touch.create({backend:'root'|'adb'|'shizuku'})`，再 `down/move/up/exit`（root 最多 10 指，Shizuku/ADB 单指）。Node **没有** `touch`；用 `accessibility.gesture`，或仅 root 的 `root_automator`（`adb/Shizuku` 未接入，`touchDown/Move/Up` 近似 swipe）。
- 权限：无障碍服务需在系统设置开启，Node 的 `enableService` 只负责引导/轮询，不能绕过用户授权；坐标手势、节点点击和截图都要求无障碍可用，`takeScreenshot` 受系统频率/`FLAG_SECURE` 限制。

## 5. 截图、images、OCR 与 YOLO
```js
// Rhino / QuickJS：同步
if (!requestScreenCapture()) throw new Error('未授权');
const frame = captureScreen();
const p = images.findImage(frame, images.read('tpl.png'), {threshold: 0.9});
const ocr = $ocr.create({backend: 'mlkit'});
const blocks = ocr.detect(frame, {max: 1000}); ocr.release();
const det = yolo.load({model: '/sdcard/model.onnx', labels: ['a']});
const boxes = det.detect(frame, {confidence: 0.25, nms: 0.45});
frame.recycle();

// Node：截图和 OCR 的创建/识别是 Promise；image 有异步版和 *Sync 版
const { requestScreenCapture } = require('media_projection');
const { readImage, findImage } = require('image');
const { createOCR } = require('ocr');
const cap = await requestScreenCapture();
const img = await cap.nextImage();
const tpl = await readImage('./tpl.png');
const hit = await findImage(img, tpl, {threshold: 0.9});
const session = await createOCR({backend: 'mlkit'});
const blocks2 = await session.detect(img); session.release();
img.recycle(); tpl.recycle(); cap.stop();
```
- Node `media_projection` 一次 job 只能成功建立一次投影会话（`Session.mUsed` 对齐）；拒绝/超时可重试，`stop()` 后 `nextImage()` 会 reject。`accessibility.takeScreenshot({maxWidth,maxHeight})` 是另一条无障碍截图路径，不占用录屏会话。
- Node `Image` 是句柄：`id/width/height/pixel()/recycle()/close()/isRecycled()`；回收后访问抛错，重复回收返回 true。`findImageSync`/`findColorSync` 等同步版会阻塞，优先用异步版。`readImage`/`writeImage` 相对路径按脚本目录，`files` 相对路径按工程工作目录。
- OCR：Node `createOCR`/`detect` 是 Promise，`release()` 是同步 boolean；Rhino/QuickJS 的 `$ocr.create`/`detect`/`release` 全部同步。MLKit 是内置离线中文；`ppocr` 参数只在 ppocr 后端生效，PP-OCR 资源受 ABI/打包约束。
- YOLO：当前只支持 `backend:'opencv'`；Node `load({model|modelPath|modelAsset, labels, inputSize, threads})`，Rhino/QuickJS 主要用 `model`（也支持 `asset://`）；`detector.detect(image,{confidence,nms,region})` 返回含 `classId/label/score/bounds`（可选 `keypoints`）的数组。Node 用完 `release()/close()`，Rhino/QuickJS 用 `close()`；Node `yolo` 是同步宿主调用，`region` 会 clamp 到帧内。
- 权限：MediaProjection 需系统录屏授权（每次会话），无障碍截图需无障碍服务，模型/模板读取需文件权限；不要把“有 API”当成“已拿到授权”。

## 6. UI、浮窗、Node Java 桥、资源与 recreate
### 6.1 Rhino / QuickJS 传统 UI
```js
"ui";
ui.layout('<vertical><button id="ok" text="OK"/></vertical>');
ui.run(() => ui.ok.setText('done'));         // Rhino/QuickJS：切 Android UI 线程
ui.post(() => { /* UI 线程 */ }, 100);
var w = floaty.window('<frame><text id="t" text="浮窗"/></frame>');
w.setPosition(100, 300); w.setSize(400, 200); w.close();
```
- Rhino 可直接用 E4X XML 字面量；QuickJS 必须传 XML 字符串。`ui.registerWidget` 可注册自定义控件；`floaty.rawWindow` 返回原始 View 浮窗。`ui.run` 在 Rhino/QuickJS 会阻塞等待 UI 线程，`ui.post` 投递到 UI 线程；浮窗需要悬浮窗权限（`floaty.checkPermission/requestPermission`），普通 `ui` Activity 不需要。

### 6.2 Node `ui.Activity`
```js
"ui";
"nodejs";
require('rhino').install();
const ui = require('ui');

class Main extends ui.Activity {
  get layoutXml() { return '<vertical><button id="btn" text="点我"/></vertical>'; }
  onContentViewSet(view) {
    this.btn = view.binding.btn;
    this.btn.setOnClickListener(() => console.log('clicked'));
  }
}
ui.setMainActivity(Main);
```
- Node 还提供 `ui.R`、`ui.loadAndroidResources()`（Promise）、`ui.inflate(xml,parent,attach)`、`ui.inflateBatch(layoutIdOrXml,count,parent)`、`ui.afterFirstFrame(cb)`、`ui.imageLoader`、`ui.close()`、`ui.currentActivity()`。
- `ui.run(fn)` 在 **Node 线程立即执行**，`ui.post` 用 Node `setTimeout` 调度，二者都不会把回调迁到 Android 主线程；`ui.isUiThread()` 通过 `Looper` 真实判断，Node 通常为 `false`。View 操作由宿主切线程，不能因为 `ui.run` 返回就直接改 View。Node UI 优先真实 Activity，失败回退 overlay，回退才需要悬浮窗权限；Node UI 在独立进程/槽运行，不能假设与主进程共享静态 Java 状态。

### 6.3 Node `ctx.loadResources` 与 `recreate`
```js
const path = require('path');
const ctx = $autojs.androidContext;
ctx.loadResources(path.join(__dirname, 'build/resources.ap_')); // 同步 Java 方法
const ui = require('ui');
ui.setMainActivity(Main);          // 资源已加载，自动走原生 Activity
// 当前 Activity 内：
this.recreate();                   // 仅 Node ui.Activity；重新 inflate
```
- `$autojs.androidContext` 是每个 Node job 独立的 `ScriptResourceContext`；`loadResources(String)` 支持 `.ap_`/`.apk`，相对路径按项目目录解析。加载失败保留旧表，同包名重载替换旧表，空路径/非 ZIP/无资源表会抛错。
- `ui.loadAndroidResources()` 是旧入口，仍挂载到同一资源 Context；`ctx.loadResources()` 成功后 `ui.setMainActivity` 自动选择真实 Activity，不必再调一次。`Activity.recreate()` 只在当前 Activity 已显示、未关闭时可用，编译 `layoutId` 已支持重建，不要求非空 `layoutXml`；旧 Activity 的 View/资源引用不能当成新 Activity 的。

### 6.4 Node 浮窗与 Java 桥
```js
const fw = require('floating_window');
if (!fw.canDrawOverlays()) {
  fw.manageDrawOverlays();
  throw new Error('请在系统设置授权悬浮窗后重新运行');
}
const w = fw.createWindow({initialPosition: {x: 100, y: 200}, touchable: true});
w.setViewFromXml('<vertical><text id="t" text="Node"/></vertical>');
w.show(); w.setPosition(120, 260); w.setSize(400, 200); w.close();

// $java.setThreadMode(javaObject, 'io')  // current|io|compute|ui|ui-sync
await $java.defineClass(class MyView extends android.view.View {
  onDraw(canvas) { super.onDraw(canvas); }
  onClick(v) { }
}, {packageName: 'org.example', implements: [android.view.View.OnClickListener]});
```
- Node 浮窗方法包括 `show/hide/close/setText/setPosition/getPosition/setSize/getSize/setViewFromXml/reload/enableDrag/addFlags/removeFlags/on('touch'|'drag'|'click')`。优先用 XML；真实 View 只能在支持该路径的同进程窗口中挂载，不能跨进程直接传 View。未找到工程 `res/layout` 时会明确报错。`floating_window` 是 Node 模块，Rhino/QuickJS 用 `floaty`，不能互换。
- Node Java 桥：`$java.Packages`/`findClass`/`getClass`/`implement`/`defineClass`/`setThreadMode`/`setDefaultThreadMode`。JS 回调通常用接口代理或具名生成类，任意函数直传受重载/回调通道限制；Java 同步回调不能返回 Promise。
- 权限：`defineClass`/`loadDex` 不会绕过 Android 权限；自定义 View/Activity 仍受宿主进程与主题/资源约束。

## 7. 插件与 Java dex
```js
// Node：普通插件 load 是 Promise；MLKit OCR 官方包名有内置兼容构造器
const plugins = require('plugins');
const ffmpeg = await plugins.load('org.autojs.plugin.ffmpeg');
plugins.list(); plugins.isInstalled('pkg'); plugins.info('pkg'); plugins.launch('pkg');
plugins.unload('pkg'); plugins.unloadAll();

host.runtime.loadDex('/sdcard/x.dex');          // Node，同步 boolean
const loader = host.runtime.getAndroidClassLoader();
```
- Rhino `$plugins.load(pkg)`、QuickJS `$plugins.load(pkg)` 是同步，入口脚本 `module.exports` 的返回值就是 `load()` 结果。Node 普通插件返回 Promise，且要求宿主返回真实 `mainScriptPath` + Java 对象；入口缺失会明确失败。
- `org.autojs.autojspro.plugin.mlkit.ocr` 在三个引擎中都有内置 MLKit 兼容路径（未安装该 APK 时使用内置 OCR）；其它包名仍要求真实安装的插件 APK。不能把“内置别名”当成插件已安装。
- dex：Rhino 暴露 `runtime.loadDex(path)`；QuickJS 示例用 `new dalvik.system.DexClassLoader(...)`；Node 当前实际是 `host.runtime.loadDex(path)`/`getAndroidClassLoader()`。类型声明里写了 `$java.loadDex`，但当前 `prelude.js` 的 `$java` 没有该方法，不能照声明直接调用。动态代码没有额外安全沙箱，只加载可信 dex。

## 8. MCP 与终端入口
- MCP 可用 `search_api_docs` 按关键词、引擎、专题和 guide/legacy 搜索本页与同源离线文档，再用 `read_api_doc(id,indexSha256)` 读参数说明及保留缩进的 Markdown 示例。搜索 limit 默认 10、最大 20，用 nextOffset 翻页；读取 maxChars 默认 16000、范围 128..32000，offset/nextOffset 为 UTF-16 字符偏移。索引更新后应重新搜索 ID。两项工具不启动脚本，不执行示例；传统 Rhino 参考不是其它引擎的支持证明。
- App 内：抽屉 → **MCP 服务**，开启后默认 `0.0.0.0:8765`，占用时在 `8765..8795` 顺延；端点固定 `POST /mcp`（JSON-RPC 2.0，HTTP/Streamable HTTP）。USB 用 `adb forward tcp:8765 tcp:8765`，客户端填 `http://127.0.0.1:8765/mcp`。端口和地址以手机页面为准。
- 常用工具：`list_engines`、`get_status`、`list_scripts`/`read_script`、`run_script`、`check_syntax`、`call_engine_api`、`probe_engine_api`、`get_ui_layout`、`capture_screen`、`ui_*`、`app_*`、`workspace_*`、`list_overlays`/`close_overlays`、`terminal_*`、`get_build_tools`。工具名/参数以 `tools/list` 为准，不以本文或截图数量为准；`ui_*`/`capture_screen`/`get_ui_layout` 需要无障碍，截图还受 Android 11+/`FLAG_SECURE` 限制。
- `run_script` 的 `path/file/source` 三选一；要拿结果/日志必须 `wait:true`（建议 `timeoutSeconds` 30–120）。`wait` 超时只表示 `timedOut=true`，任务可能仍在跑，用 `get_execution`/`wait_execution`/`stop_script` 继续。源码入口不落盘；文件/工作区路径限制在手机 MCP 操作目录内。
- 终端与应用内终端复用同一底层，但 MCP 自管任务：`terminal_exec` 立即返回 `jobId`，`terminal_read` 按 `offset/nextOffset` 读合并 stdout/stderr，`terminal_write` 写 stdin（`eof` 关闭），`terminal_stop` 停同进程组，`terminal_list` 查询。默认超时 300s（1..3600），最多 3 个并发，单任务保留约 256Ki 字符；非 PTY，不支持全屏终端程序。
- 终端以应用 UID 运行，不提供 root；`workingDirectory` 限制不是 shell 沙箱。内置只有 D8（JAR/class→DEX，API26+）和 Smali（Smali→DEX）转换入口，**没有**预装 JDK/clang/完整 Android SDK/NDK，不能据此宣称手机端能完整编译 Java/C/C++/APK。
- 安全：MCP 目前免访问令牌，任何能访问该端口的设备都能运行脚本和调用设备工具；只在可信局域网/VPN 使用，不要映射到公网。

## 9. 已知边界与源码依据
**明确边界**
- 不是“高级版全兼容”：类型/契约清单存在大量 `unverified` 记录，不能拿导出数、模块数、工具数当验证数；三引擎同名 API 也不保证参数、异常、线程和资源释放一致。
- Rhino 传统 UI 仍有主进程路径；原“听海播放器”工程未取得，不能宣称全部高级版 UI/播放器场景已等价。Node UI 独立进程/overlay 与 Rhino/QuickJS 的 `ui` 不是同一实现。
- Node 的 `shell` root/adb/Tap、`threads.exec/atomic`、`floaty`、`$java.loadDex`、完整 Pro UI 组件等未实现或仅部分实现；不要把示例中的目标态当现状。
- Node 自动化在主 App 已覆盖，但打包运行时（inrt）和独立打包 APK 的能力面可能不同；Node UI/截图/无障碍需按实际包单独验证。
- Android 11+ 的无障碍截图有频率/页面保护限制；MediaProjection 每次授权只对应一个 job 会话；设备厂商可能额外限制后台启动/悬浮窗。本文未逐项执行 Android 真机回归，类型声明、示例和源码只能证明入口存在。

**源码依据**
- 引擎选择：`modules/engine/src/main/java/com/stardust/autojs/script/JavaScriptSource.java`（指令与优先级）；`Node24JavaScriptEngine.java`（60s/10min、feature 推断）。
- Node prelude/模块表：`modules/engine/src/main/assets/node24/prelude.js`（模块系统 704–747、builtin 6434–6462、require 补丁 6775–6792、Java 桥 754–858、engines/threads 1215–1738、shell 5937–6065、yolo 6132–6414）。
- Node UI/资源：`node24/ui.js`（Activity 1190–1258、资源 1573–1605、导出 2017–2049）；`ScriptResourceContext.java`；`NodeUiHost.java`；`docs/reports/2026-10-25_Node资源Context与UI进程隔离.md`。
- Node 自动化：`node24/modules/accessibility_extra.js`（选择器 934–1061、导出 1130–1144）；`docs/guides/Node24基础自动化API.md`、`docs/guides/Node24控件选择器API.md`。
- Node 图像/OCR/YOLO：`node24/prelude.js`（image 2520–4036、accessibility 4472–4496、projection 4502–4665）；`node24/modules/ocr.js`；`node24/modules/floating_window.js`；`node24/modules/plugins.js`。
- 三引擎公共层：`modules/common-api.js`、`common-lang.js`、`__http__.js`、`__threads__.js`、`__events__.js`、`__engines__.js`、`__images__.js`、`__ocr__.js`、`__yolo__.js`、`__touch__.js`、`__selector__.js`、`__ui__.js`。
- MCP：`apps/app/src/main/assets/mcp/AIjs-MCP-Guide.md`、`docs/guides/MCP连接指南.md`、`docs/reports/2026-10-25_MCP手机终端与编译入口.md`。
- 类型声明（仅作对照，不等于运行时实现）：`flutter-payload-base/assets/flutter_assets/assets/project_templates/nodejs/node_modules/@autojs/types-pro9/libautojs9.d.ts`。

**未确认/未验证项**
- QuickJS 的 HTTP/continuation 细节、部分高级模块的 Promise/回调差异未逐项实测；本文只写能从当前源码确认的形状。
- Node 的 `$java.loadDex` 只存在于类型声明，运行时请用 `host.runtime.loadDex`，等待后续版本是否补齐。
- Rhino/QuickJS 的 `threads`/`shell` 在 inrt/独立打包 APK 中的行为未逐项验证；Node `ui.Activity` 的完整 Pro 控件/自定义 View/多窗口/系统栏等价性也未全部验收。
