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

## 8. 与旧接口的关系

Node 的 `ui.Activity` 与 Rhino/QuickJS 的传统 `ui.layout` 实现不同。Node 原生界面通常位于当前脚本的 `:node24_1..3` 槽；旧版 Node 兼容界面、对话框和回退浮窗在 `:nodeui`。主应用仍承担权限、设置和定时任务等协调职责。

关闭某一个脚本只清理它拥有的窗口；“停止全部脚本”和音量上键走全局停止链路。任意第三方 native 库仍可能崩溃，进程隔离不等于能捕获所有 native 错误。

本文依据本项目 `node24/ui.js`、`NodeUiLocalSession`、`NodeUiLocalActivity`、`ScriptResourceContext`、`Node24HostBridge` 的实际入口；资源加载、布局重建和停止路径已有小米 8 验证。未宣称全部高级版控件、所有厂商设备或独立打包 APK 都具有同等能力。
