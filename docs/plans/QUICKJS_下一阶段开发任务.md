# QuickJS 下一阶段兼容桥开发任务书

> 本文件用于交给后续 Codex 任务直接执行。开始前必须检查当前工作区，当前源码是唯一基线；不要根据旧对话或旧文档猜测架构。

## 1. 任务目标

在不破坏 Rhino 兼容层、NativeFrame、YOLO、ImGui 工作台和现有 QuickJS 功能的前提下，继续补齐 AI.js Pro 的 QuickJS Auto.js 兼容 API。

固定实施顺序：

1. `app`
2. `storages`
3. `device`
4. `shell`
5. `dialogs`
6. `engines`
7. `threads` + `events`

已经存在的 `files`、`http`、`timers` 不要重复实现，只做回归验证。每一阶段必须独立完成、独立测试，禁止一次性堆完七组 API。

## 2. 开始前的强制检查

1. 执行 `git status --short`，记录工作区已有修改。不要覆盖、回滚或删除其他任务与用户的改动。
2. 阅读并以当前文件为准：
   - [QuickJS 双引擎架构](../architecture/QUICKJS_ENGINE.md)
   - `modules/autojs/src/main/java/com/stardust/autojs/engine/QuickJsHostBridge.java`
   - `modules/autojs/src/main/java/com/stardust/autojs/engine/QuickJsJavaScriptEngine.java`
   - `modules/autojs/src/main/cpp/quickjs_jni.cpp`
   - 对应的 Rhino API 实现
3. 先列出当前 QuickJS 全局 API，确认没有同名实现后再增加。
4. 当前工作区可能同时存在其他任务的未完成改动。只修改本任务必须修改的文件，不要整理无关代码。

## 3. 不可破坏的现有功能

以下内容全部属于回归保护范围：

- Rhino 旧脚本继续由 `RhinoJavaScriptEngine` 执行；
- `// @engine quickjs` 和文件扩展名的引擎路由；
- QuickJS 的中断、内存限制、Promise 微任务和定时器事件循环；
- `console`、`toast`、`sleep`、点击/滑动、剪贴板及前台应用信息；
- `files`、`http`、`timers`；
- NativeFrame 截图、读图、找色、模板匹配和显式 `recycle()`；
- 当前工作区实际存在的 YOLO 后端、模型、示例和绘制悬浮层；
- ImGui 文件管理、打开文件返回后恢复原滚动位置；
- 三种 ABI 及 arm64 原生库 16 KiB LOAD 对齐。

未经用户明确授权，禁止：

- 删除或替换任何 YOLO 后端、模型、示例、`.so` 或第三方依赖；
- 修改 `OpenCvYoloDetector`、YOLO 解码/NMS、NativeFrame 图像链路；
- 修改 ImGui 页面、文件管理或编辑器；
- 暴露任意 Java 反射、任意类加载或无白名单的 JNI 调用；
- 使用 `git reset`、`git checkout --` 等方式清理共享工作区；
- 未做同条件测试就宣称性能提升。

## 4. 统一桥接架构

每个 API 都按下列路径实现：

```text
QuickJS JS 兼容包装层
        ↓
quickjs_jni.cpp 中的 __aiNative* 白名单函数
        ↓
QuickJsHostBridge 明确类型的方法
        ↓
现有 ScriptRuntime / Android API
```

必须遵守：

- JS 与 Java 之间只传递布尔、数字、字符串、JSON、字节缓冲或受控句柄；
- 不把任意 Java 对象、`Context`、`Activity`、`Intent` 或反射能力暴露给 JS；
- 每个句柄都归属创建它的 QuickJS 引擎，不允许跨引擎使用；
- 引擎 `destroy/close/forceStop` 时清理进程、对话框、监听器、子引擎和所有句柄；
- QuickJS `JSRuntime/JSContext` 只能由所属引擎线程访问；
- UI 操作通过主线程 Handler；等待结果时不得阻塞 Android 主线程；
- 所有阻塞等待必须支持脚本停止、中断和明确超时；
- Host 异常要转换成带清晰消息和 JS stack 的 JavaScript 异常；
- JS 包装层应尽量保持 Auto.js 4.x 名称和返回值，无法兼容时必须在文档中明确差异。

## 5. 第一阶段：`app`

优先实现常用且边界明确的 API：

```javascript
app.launchPackage(packageName)
app.launchApp(appName)
app.getPackageName(appName)
app.getAppName(packageName)
app.openAppSetting(packageName)
app.viewFile(path)
app.editFile(path)
app.uninstall(packageName)
app.startActivity(options)
```

要求：

- 优先复用现有 Rhino `AppUtils` / `ScriptRuntime.app` 能力；
- `startActivity` 只接受白名单字段：`action`、`packageName`、`className`、`data`、`type`、`flags`、基本类型 `extras`；
- 拒绝不可序列化对象和任意 Parcelable；
- 找不到应用或 Activity 时返回明确结果或抛出明确异常，不允许静默失败；
- 启动外部页面的 API 必须在无当前 Activity 时正确添加新任务 flag。

验收标记：`QUICKJS_APP_OK`。

## 6. 第二阶段：`storages`

实现：

```javascript
const store = storages.create(name);
store.put(key, value);
store.get(key, defaultValue);
store.contains(key);
store.remove(key);
store.clear();
```

要求：

- 复用现有 Auto.js storage/SharedPreferences 实现，不再创建第二套存储格式；
- 支持 `null`、布尔、数字、字符串、数组和普通 JSON 对象；
- `undefined` 的行为必须与 Rhino 兼容或在文档中说明；
- 存储对象使用受控句柄或名称包装，不暴露 SharedPreferences；
- 不同 storage 名称相互隔离，引擎重启后数据仍可读取；
- 增加中文键名、中文值、嵌套 JSON 和默认值测试。

验收标记：`QUICKJS_STORAGES_OK`。

## 7. 第三阶段：`device`

第一批只实现稳定、低权限、以读取为主的字段和方法：

```javascript
device.width
device.height
device.brand
device.manufacturer
device.model
device.device
device.product
device.sdkInt
device.release
device.getBattery()
device.isCharging()
device.getBrightness()
device.getBrightnessMode()
device.isScreenOn()
device.vibrate(duration)
device.cancelVibration()
```

要求：

- 不加入 IMEI、Android ID、序列号等敏感或新版 Android 已限制的标识符；
- 屏幕尺寸应与 Auto.js 脚本坐标系保持一致；
- 振动参数必须校验范围，脚本停止时取消尚未结束的长振动；
- 亮度写入、唤醒设备等改变系统状态的能力放到后续阶段，不要顺手扩展。

验收标记：`QUICKJS_DEVICE_OK`。

## 8. 第四阶段：`shell`

兼容基本调用：

```javascript
shell(command)
shell(command, true)
shell(command, { root: false, timeout: 10000, maxOutput: 1048576 })
```

返回统一对象：

```javascript
{
  code: 0,
  result: "stdout",
  error: "stderr"
}
```

要求：

- 默认永远是非 Root；只有脚本明确传入 `true` 或 `{root:true}` 才允许尝试 Root；
- 不得因为设备已 Root 就自动提权；
- 复用现有 shell/RootShell 层，不自己拼接 `su -c` 字符串；
- stdout、stderr 分离，限制最大输出，避免无限占用内存；
- 支持超时；引擎停止时必须终止本次命令和子进程；
- 保持命令字符串原样交给单一 shell，不做二次跨 shell 拼接；
- 超时、无 Root、命令不存在分别提供可判断的错误。

验收标记：`QUICKJS_SHELL_OK`。

## 9. 第五阶段：`dialogs`

实现同步兼容层：

```javascript
dialogs.alert(title, content)
dialogs.confirm(title, content)
dialogs.prompt(title, prefill)
dialogs.select(title, items)
dialogs.singleChoice(title, index, items)
dialogs.multiChoice(title, indices, items)
```

要求：

- 对话框创建与关闭必须运行在 Android 主线程；
- QuickJS 脚本线程可以等待结果，但绝不能阻塞主线程；
- 用户取消时返回与 Rhino 一致的值；
- 脚本被停止、Activity 销毁或引擎关闭时，立即关闭对话框并唤醒等待线程；
- 同一引擎只允许一个同步对话框等待，防止嵌套死锁；
- 没有可用 Activity 时给出明确异常，不偷偷使用悬浮窗代替。

验收标记：`QUICKJS_DIALOGS_OK`。

## 10. 第六阶段：`engines`

先实现元数据和生命周期管理，不直接暴露 Java `ScriptEngine`：

```javascript
engines.myEngine()
engines.all()
engines.stopAll()
engines.stopAllAndToast()
engines.execScript(name, source, options)
engines.execScriptFile(path, options)
```

JS 中的 Engine 包装对象第一批仅允许：

```javascript
engine.id
engine.source
engine.engineName
engine.isDestroyed()
engine.forceStop()
```

要求：

- 所有 Engine 对象使用 ID/句柄查询，不持有裸 Java 对象；
- `all()` 返回快照，避免列表迭代期间引擎被销毁导致崩溃；
- `forceStop()` 必须能停止 Rhino 与 QuickJS，但不能停止已经结束或不属于当前服务的对象；
- 子脚本默认继承调用者的工作目录，明确指定引擎时才切换；
- 禁止形成父子引擎关闭死锁；父引擎退出时是否停止子引擎要与现有 Auto.js 语义一致并写入测试。

验收标记：`QUICKJS_ENGINES_OK`。

## 11. 第七阶段：`threads` + `events`

这是风险最高的一组，必须单独设计和提交。禁止从多个线程直接操作同一个 QuickJS Runtime。

### `threads`

目标兼容面：

```javascript
const worker = threads.start(function () { /* ... */ });
worker.isAlive();
worker.interrupt();
worker.join(timeout);
threads.currentThread();
threads.shutDownAll();
```

要求：

- 每个 worker 使用独立的 `JSRuntime`、`JSContext` 和 HostBridge；
- 不能把父 Context 的函数对象、NativeFrame、detector 或任意句柄直接交给子 Context；
- 如果通过函数源码创建 worker，必须明确闭包变量不自动捕获，并提供 JSON 参数传递；
- `join()` 不能阻塞主线程，必须可被引擎停止打断；
- 父引擎销毁时停止并回收所有所属 worker；
- 线程异常应回传到父引擎日志，不能静默丢失。

### `events`

第一批只实现引擎内事件总线：

```javascript
events.on(name, listener)
events.once(name, listener)
events.emit(name, ...args)
events.removeListener(name, listener)
events.removeAllListeners(name)
```

之后再逐项接入按键、通知、广播等 Android 事件。要求所有 JS 回调都排队回所属 QuickJS 引擎线程执行，禁止由 Binder、主线程或监听器线程直接调用 QuickJS。

验收标记：`QUICKJS_THREADS_EVENTS_OK`。

## 12. 测试要求

每个阶段都必须同时完成：

1. Java/JNI 编译；
2. JS 示例脚本；
3. 自动断言和唯一成功标记；
4. 真机运行；
5. 停止脚本与资源清理测试；
6. 原有 QuickJS、NativeFrame、YOLO 和 Rhino 回归。

建议新增：

```text
apps/app/src/main/assets/sample/脚本引擎/
├─ QuickJS App 测试.js
├─ QuickJS Storages 测试.js
├─ QuickJS Device 测试.js
├─ QuickJS Shell 测试.js
├─ QuickJS Dialogs 测试.js
├─ QuickJS Engines 测试.js
└─ QuickJS Threads Events 测试.js
```

至少覆盖：

- 正常返回值；
- 中文字符串和 JSON；
- 空参数、错误参数和边界值；
- 用户取消；
- 权限不足；
- 超时；
- 运行中点击“停止”；
- 连续创建/销毁 50 次；
- 引擎结束后无残留进程、窗口、监听器和句柄；
- logcat 无 `FATAL EXCEPTION`、`JNI DETECTED ERROR` 和 native crash。

## 13. 构建与真机验收

Native 代码有修改时完整执行：

```powershell
.\modules\autojs\src\main\cpp\build-quickjs.ps1 -Configuration Release
.\build-common-debug.ps1 -SkipNative
```

如果构建脚本的实际参数已经变化，以当前脚本帮助信息为准。最终必须：

- 三种 ABI 构建成功；
- arm64 APK 安装到当前连接手机；
- 所有本阶段成功标记从手机 logcat 实际出现；
- 原有回归标记继续通过；
- `git diff --check` 通过；
- APK 路径、大小和 SHA256 写入交付结果；
- 修改 [QuickJS 双引擎架构](../architecture/QUICKJS_ENGINE.md) 和 [项目说明](../architecture/项目说明.md)，文档只能写实际完成并验证的能力。

## 14. 每阶段交付格式

完成一个阶段后，只报告以下内容：

1. 实现了哪些公开 API；
2. 修改了哪些核心文件；
3. 自动测试和真机测试结果；
4. 是否完整保留现有功能；
5. APK 路径与 SHA256；
6. 下一阶段建议，但未经确认不要顺手开始下一阶段。

## 15. 完成标准

只有同时满足以下条件才可以说“完成”：

- API 已真实连接现有 Android/Auto.js 能力，不是假实现；
- 错误、取消、停止和资源回收路径均已测试；
- 示例可在 AI.js Pro 内直接找到并运行；
- 真机成功标记和无崩溃日志已核对；
- 已重新构建并安装包含本次修改的 APK；
- 没有删除、替换或重构任务范围外的模块。

