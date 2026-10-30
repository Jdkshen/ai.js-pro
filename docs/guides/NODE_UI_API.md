# Node.js UI 与 Java 资源接口

本文对应 AI.js Pro 的 `require('ui')` 和 `$autojs.androidContext`。这是 Node 引擎专用接口；Rhino/QuickJS 的传统 `ui` 用法请看各自章节。

## 1. 最小界面：ui.setMainActivity

```js
"ui nodejs";
require('rhino').install();
const ui = require('ui');
class Main extends ui.Activity {
  get layoutXml() {
    return '<vertical padding="16"><text id="title" text="你好"/><button id="close" text="关闭"/></vertical>';
  }
  onContentViewSet(view) {
    view.binding.close.setOnClickListener(() => ui.close());
  }
}
ui.setMainActivity(Main);
```

`ui.setMainActivity(ActivityClass, options?)` 同步发起界面创建，返回包含 `instance` 的结果对象；`instance` 是脚本 Activity 实例。参数必须是继承 `ui.Activity` 的类。`layoutXml` 必须返回 XML 字符串；创建失败抛异常，不会把缺失控件当成功。

显示界面会维持当前脚本的 UI 生命周期，不必为了保活额外创建空循环。关闭 UI 后，如果脚本还有定时器、服务器或工作线程，应按其 API 主动清理。

## 2. ctx.loadResources：挂载编译资源包

```js
"ui nodejs";
require('rhino').install();
const ctx = $autojs.androidContext;
ctx.loadResources(require('path').join(__dirname, 'build/resources.ap_'));
const ui = require('ui');
class Main extends ui.Activity {
  get layoutId() { return ui.R.layout.activity_main; }
  onContentViewSet() {
    this.findViewById(ui.R.id.title).setText('已加载工程资源');
  }
}
ui.setMainActivity(Main);
```

| 接口 | 参数 | 返回 / 失败 |
| --- | --- | --- |
| `ctx.loadResources(resourceFile)` | 已编译 `.ap_` 或 `.apk` 文件路径；相对路径从当前项目目录解析 | 同步 `void`；不存在、损坏、没有资源表或宿主不匹配时抛异常 |
| `ctx.getResources()` | 无 | 当前脚本的 Android `Resources` |
| `ctx.getAssets()` | 无 | 当前资源表的 `AssetManager`；`open('name')` 访问包中 `assets/name` |
| `ctx.getTheme()` / `ctx.setTheme(styleId)` | Android style 资源整数 ID | 当前主题 / 同步设置主题 |
| `ctx.getSystemService('layout_inflater')` | 固定 Android 服务名 | 使用当前资源 Context 的 LayoutInflater |

它加载资源包，不编译 `res/` 源目录。工程资源需要先编译，例如在本仓库电脑端使用 `tools/build-project-res.ps1`；不能把 `res/` 文件夹传给 loadResources。

每个脚本持有独立的资源表，不覆盖应用的主资源表。失败会保留上一次成功的表；重新加载同包名资源会替换旧表。已经创建的界面保留其资源快照，加载新包后调用 `recreate()` 更新界面。

直接调用这个方法后，`ui.R` 和原生 Activity 使用同一资源表；不必再调用 `ui.loadAndroidResources()`。只有编译包、没有 `project.json` 或 `res/` 目录，也可以走此入口。

## 3. ui.loadAndroidResources 与 ui.R

`ui.loadAndroidResources(): Promise<object>` 从项目配置加载 Android 资源。使用 `project.json` 的 `androidResources.resDir/manifest`，优先挂载 `build/project-res.apk`，没有编译包时部分 XML 能走动态布局；依赖真实 Android 资源 ID 的动画、主题等仍需要编译包。

```js
const ui = require('ui');
await ui.loadAndroidResources(); // 放在 async 函数内，失败由 catch 处理
const layoutId = ui.R.layout.activity_main;
const colorId = ui.R.color.primary;
```

`ui.R.<类型>.<名称>` 返回资源 ID；不存在返回 `0`，不要将 `0` 当有效 drawable、layout 或 style 使用。编译包的 `Resources.getIdentifier` 查找需要真实资源表包名，不能假定 AndroidManifest 包名与资源表包名一致；优先用 `ui.R`。

## 4. Activity 生命周期、查找与重建

| 接口 / 回调 | 说明 |
| --- | --- |
| `get layoutXml()` | 返回字符串 XML；优先于 layoutId |
| `get layoutId()` | 返回编译布局 ID，例如 `ui.R.layout.activity_main` |
| `onCreate(savedInstanceState)` | Activity 创建回调；需要时调用 `super.onCreate(savedInstanceState)` |
| `onContentViewSet(root)` | 内容视图已设置，可绑定事件、查找控件 |
| `onDestroy()` | 清理当前界面的业务资源 |
| `onBackPressed()` | 覆盖后由脚本决定是否 `ui.close()`；不覆盖时按默认返回流程关闭 |
| `ui.findViewById(idOrName)` | 返回控件或 `null`；编译布局建议传 `ui.R.id.name` |
| `instance.findViewById(id)` | 通过当前 Activity 查找真实 View |
| `instance.getWindow()` / `instance.getResources()` | 当前窗口 / 当前 Activity 的资源表 |
| `instance.recreate()` | 重新创建当前 Activity；支持字符串 XML 和编译 layoutId |
| `ui.close()` | 关闭当前 UI 并释放 UI 保活状态 |

`root.binding.name` 按布局命名绑定控件。节点不存在时先检查布局 ID、名字和当前界面，避免继续调用空对象。Activity 关闭后不要再使用旧 View；重建后在新的 `onContentViewSet` 重新绑定。

## 5. UI 调度：run / post / runOnUiThread

| 调用 | 返回 | 当前实现 |
| --- | --- | --- |
| `ui.run(fn)` | `fn()` 的返回值 | 在当前 JS 调用上下文执行；不代表 JS 已迁移到 Android 主线程 |
| `ui.post(fn, delayMs?)` | Node 定时器句柄 | 延时执行 JS；异常写日志；可用 `clearTimeout` 取消 |
| `ui.runOnUiThread(fn)` | `Promise` | 延后执行 JS，同步 Java 桥操作逐次调度到 Android 主线程；resolve 为 fn 返回值，抛错则 reject |
| `ui.isUiThread()` | `boolean` | 检查实际线程状态 |

```js
const answer = await ui.runOnUiThread(() => {
  const title = ui.findViewById('title');
  if (title) title.setText('更新完成');
  return 42;
});
console.log(answer);
```

不要在 UI 回调里使用死循环、同步网络请求、长时间 sleep 或等待依赖 UI 自身完成的任务。耗时工作结束后再提交控件更新。

Node 的 JS 始终在 Node 线程执行。`runOnUiThread` 不再用一个阻塞的 Android `Runnable` 包住整段 JS，而是在回调的同步部分将 Java 构造、字段和方法调用逐次交给主线程；主线程在调用之间仍能处理输入与绘制。显式异步 Java 调用保留原有执行器；`await` 之后的 JS 延续不会继承同步调度范围。回调仍然异步执行，应使用 `await` 或 `.catch()` 接收失败，外围同步 `try/catch` 捕获不到 Promise 拒绝。

支持 `ViewGroup.suppressLayout` 的当前内容布局会在同步回调期间合并布局，结束后恢复；已有的显式布局抑制会保留，异常也会恢复状态。这个改动避免了复杂代码布局持续占住主线程并触发 ANR，并减少构建中间树的重复测量；单次耗时的 Java UI 操作依然会阻塞主线程，大型列表仍建议复用控件或分批加载。

## 6. inflate、批量创建与替换

`ui.inflate(source, parent?, attachToParent?)` 返回真实 View。source 可为 XML 字符串、编译 layoutId 或已支持的布局名称；parent 是可选的真实 ViewGroup，attachToParent 默认 false。

`ui.inflateBatch(source, count, parent?)` 返回 View 数组，用于列表预创建；不是逐个调用 JS 回调来 inflate。count 必须为有效数量；请按当前宿主报错提示控制批量大小。

`ui.setContentView(viewOrLayout)` / `ui.layout(viewOrLayout)` 安装内容视图。复用旧 View 前需要满足 Android 的 parent 规则；已属于另一个父布局的 View 不能直接重复添加。

## 7. Java 互操作入口

```js
require('rhino').install();
const File = $autojs.java.findClass('java.io.File');
const file = new File(__dirname, 'data.json');
console.log(file.exists(), String(file.getAbsolutePath()));
```

`$autojs.java.findClass(className)` 按完整类名返回 Java 类代理；可使用构造器、静态方法及实例方法。`rhino.install()` 安装 `java/android/Packages` 等兼容包入口。跨桥参数不是任意 JS 对象都能自动对应 Java 重载；重载不明确时根据报错将数值/数组/接口转换成目标类型。

`$autojs.androidContext` 是 Context，不是 Activity。需要窗口、Activity 回调或界面主题时使用当前 UI Activity。Node 自带的 fs/path/http 等仍是 Node API；不能把 JVM 线程当作另一个可任意共享 JS 闭包的 Node 环境。

## 8. 原生 View 的事件、查找和附加状态

以下用于真实 Android View，包括 `root.binding.name`、`findViewById()`、`ui.inflate()` 返回的控件；需要当前版本的 Node 引擎。`view.findView('name')` 只搜索该控件的子树，找不到返回 `null`，不是无障碍选择器。

| 接口 / 事件 | 参数与行为 |
| --- | --- |
| `view.on(event, handler)` / `once(event, handler)` | 注册 / 仅执行一次；返回当前 View |
| `off(event, handler)` / `removeListener(event, handler)` | 移除指定处理器；返回当前 View |
| `prependListener` / `prependOnceListener` | 优先执行 / 优先执行一次 |
| `removeAllListeners(event?)` | 清除指定事件或全部 JS 处理器；原生默认行为由 Android 决定 |
| `eventNames()` / `listenerCount(event)` / `listeners(event)` | 查询已注册的 JS 事件 / 数量 / 处理器副本 |
| `setMaxListeners(n)` / `getMaxListeners()` | 设置 / 查询 EventEmitter 警告阈值；不限制事件次数 |
| `click` | `(view)`；点击回调 |
| `check` | `(checked, view)`；CheckBox、Switch 等 CompoundButton 状态变化 |
| `long_click` | `(event, view)`；`event.view` 为当前 View，`event.consumed` 默认 false |
| `touch` / `touch_down` / `touch_up` / `touch_move` | `(event, view)`；事件可调用 `getX()`、`getY()`、`getAction()` 等 MotionEvent 方法；先触发对应阶段，再触发 touch |
| `key` / `key_down` / `key_up` | `(keyCode, event, view)`；可调用 `event.getKeyCode()` 等 KeyEvent 方法；先阶段，再 key |
| `scroll_change` | `(event, view)`；`scrollX`、`scrollY`、`oldScrollX`、`oldScrollY`；需要 Android 6 / API 23 以上 |
| `view.findView(nameOrId)` | 当前 View 子树中的控件；不存在返回 null |
| `view.extras` | 当前 View 的 JS 附加状态字典；同一个原生 View 多次查找共享字典 |
| `view.clearViewBindingCache()` | 修改子树后清除绑定缓存；已保存的 `binding` 引用也会重新查找控件 |
| `ui.Widget.of(view)` | 获取自定义 Widget；也可读取 `view.extras['autojs.widget']`；普通控件返回 undefined（dev-1287 曾返回 null） |

```js
"ui"; "nodejs";
const ui = require('ui');
class Main extends ui.Activity {
  get layoutXml() { return '<vertical padding="16"><button id="button" text="长按我"/></vertical>'; }
  onContentViewSet(root) {
    const button = root.findView('button');
    button.extras.count = 0;
    button.on('long_click', event => {
      event.consumed = true; // 同步告诉 Android 已处理；避免继续触发默认长按行为
      button.setText('已长按 ' + (++button.extras.count) + ' 次');
    });
  }
}
ui.setMainActivity(Main);
```

长按、触摸和按键是否被消费，取决于回调同步部分设置的 `event.consumed`。仅注册处理器或 `return true` 不代表已消费；`await` 后设置已来不及影响这次 Android 分发。触摸阶段与通用事件共享同一个事件对象，后执行的处理器可以继续修改 consumed。

MotionEvent 是 Android 临时借用的对象，回调结束后不要保存它继续使用；需要延后处理时，先同步取出坐标、时间等普通值。耗时操作放到异步任务，避免阻塞 Android 输入分发。`scroll_change` 是滚动后的通知，修改 consumed 不会撤销滚动。

示例入口：Node 引擎 → Node.js → Node.js 中使用 UI → 原生 View 事件与状态。现有 `item_created`、Canvas `draw` 仍使用各自的控件接口。本节不代表所有高级版列表事件、自定义事件或第三方 View 的事件已实现；不支持的事件仍明确报错。

## 9. 自定义 Widget 的创建与属性

`ui.Widget.register(name, Class)` 注册自定义 XML 标签。类应继承 `ui.Widget`；`ui.Widget.create(name)` 只构造注册的实例，未知名称返回 undefined，不自动启动界面或调用 inflate 生命周期。

| 接口 / 回调 | 当前行为 |
| --- | --- |
| `created()` | 类构造结束后、render 前调用；只在布局创建路径调用 |
| `render(context, parent)` | 返回一个根节点的 XML，或尚未挂载的 Android View；context 是本次原生 inflater 的真实主题 Context，parent 是实际父布局，顶层可能为 null |
| `rootViewCreated(view)` | 根 View 已创建、XML 子节点尚未创建时调用；this.view、Widget.of 已可用；render 自己预先创建的子 View 会保留 |
| `get initialAttributes()` | 在根 View 和子树已创建后读取，可访问 this.view；普通 XML 属性优先级：外部 XML > render 内部值 > initialAttributes；null/undefined 原生默认值跳过 |
| `inflated()` | 子树和初始属性已应用，外部 XML 属性尚未应用；可查子控件、读取初值 |
| `mounted(view?)` | 原生 View 和外部属性已可用；可读取 this.view、绑定事件；基类提供空方法，可调用 super.mounted() |
| `widget.view` | 当前原生根 View；未创建时报 `widget is not mounted` |
| `hasAttribute(name)` | 检查继承链上的 `attr$name` 描述符或 attrs 中的声明 |
| `getAttribute(name, fallback)` | 优先 getter / JS 数据属性，再读 attrs；未知属性调用 fallback() |
| `setAttribute(name, value, fallback)` | 优先 setter / 可写 JS 数据属性，再改 attrs；未知或只读属性调用 fallback(value) |

`attr$name` 支持继承的 getter/setter 和可写 JS 数据属性；外部 XML、单属性 `view.attr`、批量 `view.attr({...})` 均可设置。默认值不会覆盖已经初始化的 JS 数据属性。普通 Android 属性用 `view.attr` 更新；`widget.attrs` 是 JS 声明和值字典，直接修改它不等于更新原生控件。

TextView 及其子类可动态设置 `textColor` / `color`、`textColorHint`；读取返回当前 `#AARRGGBB`，也能反映直接 Java setTextColor 后的值。颜色与 XML 共用解析器，支持十六进制、颜色整数、`@color/name` 和 `@android:color/name`。例如 `view.attr('textColor', '@android:color/holo_blue_light')`；此文字颜色处理位于三引擎共用属性层。

```js
const ui = require('ui');
class Counter extends ui.Widget {
  constructor() { super(); this.count = 0; }
  get initialAttributes() { return {count: '0'}; }
  get attr$count() { return String(this.count); }
  set attr$count(value) {
    this.count = Number(value) || 0;
    this.view.setText('计数 ' + this.count);
  }
  render() { return '<button/>'; }
  mounted() {
    this.view.on('click', () => this.view.attr('count', this.count + 1));
  }
}
ui.Widget.register('demo-counter', Counter);
// 在 Activity 的 layoutXml 中使用：<demo-counter id="counter" count="3"/>
```

示例入口：Node 引擎 → Node.js → Node.js 中使用 UI → 自定义 Widget 属性。完整可运行的 Activity 包装见内置示例。

上述阶段由原生 inflater 的真实创建点调用。顺序为 constructor → created → render → rootViewCreated → 子树 → initialAttributes → inflated → 外部 XML 属性 → mounted。`contentSlot` 可指定接收外部 XML 子节点的 ViewGroup ID；未指定时优先 slot/content，再使用根 ViewGroup。返回原生 View 时保留 render 已创建的子树，但该根必须尚无 parent。

回调同步参与 Android 布局创建，避免在其中做长时间网络或计算。界面重建会重新创建 Widget，旧 Widget/旧 View 不应继续更新。旧宿主没有原生工厂时保留 XML 展开兼容路径，该旧路径不提供真实阶段；当前包与内置独立打包模板使用原生工厂。高级版全部 UI/API、外部打包插件模板、构造时 style/theme 的所有组合仍需继续验证。

注册名可以覆盖 `button`、`grid`、`row` 等内置简称，实例创建时优先查询 Widget；大小写按注册名统一处理。注册只影响之后创建的控件，不改写已有 View。父 Widget 的 render 中注册外部子标签，以及先设置列表模板、后注册其中的 Widget，均在实际创建/绑定时判断。原始属性（包括 size 等别名、自定义空字符串和 XML 实体）会保留给实例。

覆盖内置简称后，render 应返回完整原生类名或直接创建原生 View，例如 `return '<android.widget.Button/>'`。若返回自己覆盖的 `<button/>` 会递归；嵌套超过 32 层报错。未注册控件继续走原生转换，原生属性转换错误不会被静默当作 Widget 成功。

## 10. 与旧接口的关系

Node 的 `ui.Activity` 与 Rhino/QuickJS 的传统 `ui.layout` 实现不同。Node 原生界面通常位于当前脚本的 `:node24_1..3` 槽；旧版 Node 兼容界面、对话框和回退浮窗在 `:nodeui`。主应用仍承担权限、设置和定时任务等协调职责。

关闭某一个脚本只清理它拥有的窗口；“停止全部脚本”和音量上键走全局停止链路。任意第三方 native 库仍可能崩溃，进程隔离不等于能捕获所有 native 错误。

本文依据本项目 `node24/ui.js`、`NodeUiLocalSession`、`NodeUiLocalActivity`、`ScriptResourceContext`、`Node24HostBridge` 的实际入口；资源加载、布局重建和停止路径已有小米 8 验证。未宣称全部高级版控件、所有厂商设备或独立打包 APK 都具有同等能力。
