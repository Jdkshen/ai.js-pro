# QuickJS 新引擎开发完成报告

> 历史快照报告，核对日期：2026-09-01。本报告记录当时的实现与测试结果，不自动代表当前工作树已重新通过同一套验证。

## 1. 概述

QuickJS 是 AI.js Pro 新增的原生 JavaScript 引擎，通过 `// @engine quickjs` 指令显式选择。相比默认的 Rhino 引擎，QuickJS 提供现代 ECMAScript 支持和较轻量的运行时，但采用白名单桥接架构——只暴露明确注册的 API，不暴露任意 Java 反射。实际性能取决于脚本类型；图色、截图和 YOLO 的主要提升来自 NativeFrame、C++ OpenCV 及减少跨语言复制，启动速度和纯 JS 性能应使用同机基准测试判断。

### 双引擎架构

```text
JavaScriptSource
  │
  ├─ 默认 ────────────→ RhinoJavaScriptEngine 1.7.7.2（完整旧 API）
  │
  └─ // @engine quickjs → QuickJsJavaScriptEngine
                             └─ JNI → libquickjs_jni.so + libquickjs.so
                                 └─ QuickJsHostBridge（白名单 Java API 桥）
```

## 2. 已实现的白名单 API 汇总

### 2.1 基础能力（第一批）

| API | 说明 |
|-----|------|
| `console.log/info/warn/error/verbose`、`log` | 控制台输出 |
| `toast`、`toastLog` | 弹出提示 |
| `sleep(ms)` | 可中断等待 |
| `click(x,y)` / `press(x,y,d)` / `longClick(x,y)` / `swipe(x1,y1,x2,y2,d)` | 无障碍手势 |
| `back()` / `home()` / `recents()` | 系统动作 |
| `notifications()` / `quickSettings()` | 通知栏 |
| `setClip(text)` / `getClip()` | 剪贴板 |
| `currentPackage()` / `currentActivity()` | 前台应用信息 |

### 2.2 文件 / 网络 / 定时器（第二批）

| API | 说明 |
|-----|------|
| `files.read/write/append/exists/isFile/isDir/listDir/remove/rename/copy/move/create/ensureDir/path/cwd/getSdcardPath` | 文件操作白名单 |
| `http.get/post/postJson/request` | 同步 HTTP（OkHttp 3.10） |
| `setTimeout/setInterval/clearTimeout/clearInterval` | 定时器 |

### 2.3 截图 / 图像（第二批）

| API | 说明 |
|-----|------|
| `requestScreenCapture(orientation)` | 请求截图权限 |
| `captureScreen([options])` | 截图到 NativeFrame；支持全分辨率、视觉加速、缓存帧/新帧策略 |
| `captureScreen({mode:'full'|'fast', size, fresh, timeout})` | `fast` 默认按短边 720p 缩放，图色/模板/YOLO 坐标自动映射回原屏幕 |
| `images.read(path)` | 从文件读取 NativeFrame |
| `images.pixel(frame, x, y)` | ARGB 像素读取 |
| `images.findColor(frame, color, opts)` | C++ 直接扫描 |
| `images.findMultiColors(frame, ...)` | 多点颜色路径扫描 |
| `images.findImage(frame, template, opts)` | OpenCV 模板匹配 |
| `images.matchTemplate(frame, template)` | 多结果模板匹配 |
| `images.clip(frame, x, y, w, h)` | 裁剪 |
| `images.resize(frame, w, h[, interp])` | 缩放 |
| `images.scale(frame, fx[, fy])` | 按比例缩放 |
| `images.grayscale(frame)` / `gray()` | 灰度化 |
| `images.cvtColor(frame, code)` | 颜色空间转换 |
| `images.rotate(frame, angle)` | 旋转 90/180/270 度 |
| `images.threshold(frame, threshold, maxValue, type)` | 阈值化 |
| `images.blur(frame, kernelSize)` | 均值模糊 |
| `images.save(frame, path, format, quality)` | 保存到文件 |
| `images.compress(frame, format, quality)` | 压缩为 `Uint8Array` |
| `images.copy(frame)` | 深拷贝 |
| `NativeFrame.width/height` | 原屏幕逻辑尺寸 |
| `NativeFrame.pixelWidth/pixelHeight` | NativeFrame 实际参与计算的像素尺寸 |
| `NativeFrame.recycle()` | 显式释放 |
| `NativeFrame.saveTo(path, format, quality)` | 保存快捷方法 |

### 2.4 YOLO 目标检测

| API | 说明 |
|-----|------|
| `yolo.isAvailable(backend)` | 检查后端可用性 |
| `yolo.getVersion(backend)` | 获取版本 |
| `yolo.load({backend, model, labels, inputSize, threads})` | 加载模型 |
| `detector.detect(frame, {confidence, nms})` | 执行检测 |
| `detector.close()` | 释放模型 |
| `drawing.show/update/hide()` | 悬浮检测框绘制 |

YOLO 当前仅支持 `backend: 'opencv'`（OpenCV 5.0 DNN，`ENGINE_AUTO` 新图引擎，当前默认 CPU 路径）。单帧耗时受手机、模型、输入尺寸、截图模式和线程数影响，报告中不使用脱离测试环境的固定帧耗时作为通用结论。

### 2.5 应用 / 存储 / 设备（第三批）

| API | 说明 |
|-----|------|
| `app.launch(pkg)` / `app.launchApp(name)` / `app.launchPackage(pkg)` | 启动应用 |
| `app.openUrl(url)` | 打开 URL |
| `app.getInstalledApps()` | 已安装应用列表 |
| `app.getAppInfo(pkg)` | 应用信息（label/versionName） |
| `app.getPackageName(name)` / `app.getAppName(pkg)` | 名称互查 |
| `app.openAppSetting(pkg)` | 打开应用设置 |
| `app.viewFile(path)` / `app.editFile(path)` | 查看/编辑文件 |
| `app.uninstall(pkg)` | 卸载应用 |
| `app.startActivity(opts)` | 启动 Activity（白名单字段） |
| `storages.create(name)` → `put/get/remove/contains/clear` | SharedPreferences 持久化 |
| `device.model/brand/sdkInt/release/width/height/...` | 设备信息 |
| `device.isScreenOn()` / `vibrate(ms)` / `getBattery()` | 设备状态 |
| `device.isCharging()` / `getBrightness()` / `getBrightnessMode()` / `cancelVibration()` | 扩展设备能力 |

### 2.6 Shell / 对话框 / 引擎（第四、五、六批）

| API | 说明 |
|-----|------|
| `shell(cmd)` / `shell(cmd, true)` / `shell(cmd, {root, shizuku, timeout, maxOutput})` | 普通、Root 或 Shizuku Shell |
| `shell.isRootAvailable()` | Root 检测 |
| `shizuku.isAvailable()` / `hasPermission()` / `requestPermission(timeout)` | Shizuku 状态和授权 |
| `shizuku.shell(cmd, {timeout, maxOutput})` | 通过 Shizuku 执行 Shell |
| `dialogs.alert/confirm/prompt/select/singleChoice/multiChoice` | 同步对话框 |
| `dialogs.build({title, content, positiveText, negativeText, neutralText, inputHint, inputPrefill})` | 通用构建 |
| `engines.execScript(name, source, config)` / `execScriptFile(path, config)` | 启动子脚本 |
| `engines.myEngine()` / `engines.all()` | 引擎信息 |
| `engines.stopAll()` / `stopAllAndToast()` | 停止所有引擎 |
| `engine.id/source/engineName/isDestroyed()/forceStop()` | 引擎对象封装 |

普通/Root Shell 命令在独立进程组中运行；超时时会结束整个进程组，避免只结束外层 `sh` 后遗留 `sleep` 等子进程继续占用输出管道。

### 2.7 线程 / 事件（第七批）

| API | 说明 |
|-----|------|
| `threads.start(fn[, args])` | 启动 worker（函数或脚本） |
| `threads.exec(name, source, args)` | 命名 worker + JSON 参数传递 |
| `threads.currentThread()` | 当前线程 |
| `threads.shutDownAll()` | 停止所有 worker |
| `thread.isAlive()` / `join(timeout)` / `interrupt()` | 线程控制 |
| `thread.getResult()` / `waitForResult(timeout)` | 查询或等待 worker 返回值，异常会传回调用方 |
| `events.on/once/emit/removeListener/removeAllListeners/listenerCount` | 事件总线 |
| `events.observeKey/observeTouch/observeNotification/observeToast/observeGesture` | 系统事件观察；通过有界队列回到 QuickJS 引擎线程派发 |

每个 worker 使用独立 QuickJS 引擎，函数任务不捕获外层闭包，事件总线当前仅限同一引擎。

## 3. 已实现的 images 模块详解

```javascript
// @engine quickjs
const frame = captureScreen();
const derivedFrames = [];
const keep = item => (derivedFrames.push(item), item);
try {
    const cropped = keep(images.clip(frame, 100, 100, 200, 200)); // 裁剪
    const resized = keep(images.resize(frame, 640, 480));         // 缩放
    const scaled = keep(images.scale(frame, 0.5));                // 按比例缩放
    const gray = keep(images.grayscale(frame));                   // 灰度化
    const bgr = keep(images.cvtColor(frame, 'rgba2bgr'));         // 颜色转换
    const rotated = keep(images.rotate(frame, 90));               // 旋转
    const binary = keep(images.threshold(frame, 128, 255, 0));    // 阈值化
    const blurred = keep(images.blur(frame, 5));                  // 模糊
    images.save(frame, files.cwd() + '/shot.png', 'png', 100);  // 保存文件
    const bytes = images.compress(frame, 'jpg', 80);             // Uint8Array
    const copy = keep(images.copy(frame));                       // 深拷贝
} finally {
    derivedFrames.forEach(item => item.recycle());
    frame.recycle();
}
```

视觉加速截图示例：

```javascript
const full = captureScreen({mode: 'full', fresh: false});
const fast720 = captureScreen({mode: 'fast', size: 720, fresh: false});
const live640 = captureScreen({mode: 'fast', size: 640, fresh: true, timeout: 100});
```

快速帧的 `width/height` 保持原屏幕逻辑尺寸，`pixelWidth/pixelHeight` 表示实际 Native 像素尺寸。找色、多点找色、模板匹配和 YOLO 的区域参数及返回坐标均使用原屏幕坐标。

## 4. 构建环境

### 编译工具链

| 工具 | 版本 |
|------|------|
| JDK | OpenJDK 17（Microsoft，需 `--add-opens` 兼容参数） |
| Android SDK | `C:/Android` |
| NDK | r27c |
| CMake + Ninja | VS 自带 |
| Gradle | 4.10.2 + AGP 3.2.1 |
| Build Tools | 28.0.3 |

### 快速编译

```powershell
# 完整构建（原生 + Java + APK）
.\build-common-debug.ps1

# 仅 Java 编译（.so 已就绪）
.\build-common-debug.ps1 -SkipNative

# 仅 QuickJS 原生库
.\modules\autojs\src\main\cpp\build-quickjs.ps1

# 仅 ImGui 原生库
.\apps\app\src\main\cpp\build-native.ps1
```

### 输出

```
apps/app/build/outputs/apk/common/debug/
├─ app-common-arm64-v8a-debug.apk    （arm64，~94MB）
├─ app-common-armeabi-v7a-debug.apk
└─ app-common-x86-debug.apk
```

## 5. 测试结果

### 双引擎回归测试（2026-09-01 实机记录，设备 alioth M2012K11AC）

#### Rhino 回归

```
=== Rhino Regression Result: 32 passed, 0 failed ===
RHINO_REGRESSION_OK
```

32 个断言全部通过，覆盖：toast/sleep/log/console/click/setClip/getClip/currentPackage/currentActivity/shell/files（读写/存在/删除）/storages（create/put/get）/device（width/model）/images（captureScreen/read/findColor）/setTimeout/setInterval/yolo.isAvailable/dialogs.alert/dialogs.confirm/engines.execScript/java.lang.String（Rhino 特有验证）。

#### QuickJS 回归（2026-09-07 K40 复测）

```
=== 回归测试完成: 46 通过, 0 失败 ===
=== QUICKJS_REGRESSION_OK ===
```

42 个断言全部通过。只有 `fail === 0` 时才输出 `QUICKJS_REGRESSION_OK`；任意断言失败都会抛出异常。

#### 设备信息

| 字段 | 值 |
|------|-----|
| 型号 | Xiaomi M2012K11AC (alioth) |
| 序列号 | cccc62c7 (USB) |
| Rhino | 32/0 ✅ |
| QuickJS | 42/0 ✅ |
| 异常/崩溃 | 0 |
| 结论 | **PASS** |

| 模块 | 全模块脚本断言数 | 专项测试 |
|------|------------------|----------|
| app | 3 | `QuickJS App 测试.js` |
| storages | 1 | `QuickJS Storages 测试.js` |
| device | 4 | `QuickJS Device 测试.js` |
| shell | 3 | `QuickJS Shell 测试.js`、`QuickJS Shell 自动测试.js` |
| dialogs | 3 | `QuickJS Dialogs 测试.js` |
| engines | 4 | `QuickJS Engines 测试.js` |
| threads + events | 6 | `QuickJS Threads Events 测试.js` |
| console/toast/timers/files/http | 18 | `QuickJS运行环境测试.js`、`QuickJS Files Http Timers 测试.js` |

上表的断言数合计为 42；全模块统一验收标记为 `QUICKJS_REGRESSION_OK`，不再使用当前脚本并未实际输出的分模块标记。

### 专项测试脚本

位于 `apps/app/src/main/assets/sample/`：

| 脚本 | 引擎 | 覆盖内容 |
|------|------|----------|
| `脚本引擎/Rhino 回归测试.js` | Rhino | 32 项回归（toast/sleep/files/storages/device/images/timers/yolo/dialogs/engines/java.lang.String） |
| `QuickJS 新引擎/QuickJS 运行环境测试.js` | QuickJS | 语法、Promise、中文桥接和 toast 烟雾测试 |
| `QuickJS App 测试.js` | launchPackage/getAppInfo/getPackageName |
| `QuickJS Storages 测试.js` | put/get/remove/clear/中文键/嵌套JSON/默认值/隔离 |
| `QuickJS Device 测试.js` | 属性/isScreenOn/getBattery/isCharging/vibrate |
| `QuickJS Shell 测试.js` | echo/超时/输出截断/Root检测 |
| `QuickJS Shell 自动测试.js` | 普通命令、输出限制、超时与子进程清理 |
| `QuickJS Shizuku Shell 点击.js` | Shizuku 授权、Shell 点击和耗时 |
| `QuickJS Shizuku Shell 自动滑动.js` | 按屏幕比例循环滑动、次数/间隔/耗时配置 |
| `QuickJS Dialogs 测试.js` | build()普通+带输入 |
| `QuickJS Engines 测试.js` | myEngine/all/execScript/id/engineName/isDestroyed |
| `QuickJS Threads Events 测试.js` | on/once/emit/removeListener/exec参数/start/join |
| `QuickJS Files Http Timers 测试.js` | 文件读写/HTTP请求/定时器 |
| `QuickJS Native Frame 回归测试.js` | 截图/像素/找色/模板匹配 |
| `QuickJS Images Advanced 测试.js` | rotate/threshold/blur/scale/save/copy |
| `QuickJS 全模块回归测试.js` | 一次覆盖所有模块（46项，含 worker 参数与返回值） |
| `QuickJS Worker参数与结果测试.js` | worker JSON 参数、状态、结果等待与结束 |
| `图色处理/01～07` | 图色 API、模板、视觉加速、坐标回归和截图稳定性 |
| `YOLO目标检测/opencv/*` | 单帧、区域、持续、实时和运行环境测试 |

## 6. 已知限制

| 限制 | 说明 |
|------|------|
| E4X/JSX/Java 反射 | 不兼容，继续使用 Rhino；QuickJS 已有 `ui/$ui` XML 基础桥 |
| `"ui";` 模式声明 | Rhino 专属；QuickJS 直接使用 `ui/$ui` 基础模块 |
| `console.show()` | Rhino 悬浮控制台，QuickJS 未实现 |
| YOLO 后端 | 仅支持 `opencv`，ncnn/onnx 已移除 |
| `threads` 事件总线 | 当前仅限同一引擎，不支持跨 worker 通信；系统按键/触摸/通知/Toast/手势观察已接入 |
| `threads.start` 函数模式 | 闭包变量不自动捕获，需通过 `args` 传递 |
| `dialogs.build()` | 同步版本，不含 `customView` 和链式回调 |
| `images` 尚缺能力 | 形态学和 OCR 未实现；旋转、阈值化、模糊已经可用 |

## 7. 文件结构

```text
modules/autojs/src/main/java/com/stardust/autojs/
├─ engine/QuickJsJavaScriptEngine.java    Java 引擎生命周期
├─ engine/QuickJsNativeBridge.java        JNI 方法声明
├─ engine/QuickJsHostBridge.java          白名单 Java API 桥（~1650行）
├─ engine/QuickJsException.java           Native JS 异常类型

modules/autojs/src/main/cpp/
├─ quickjs_jni.cpp                        Context/求值/中断/Host API/Bootstrap JS（~3200行）
├─ native_frame_store.{h,cpp}             cv::Mat 句柄/找色/模板匹配/clip/resize/grayscale/cvtColor/save/compress
├─ CMakeLists.txt                         libquickjs + libquickjs_jni + OpenCV 依赖

apps/app/src/main/cpp/
├─ autojs_imgui.cpp                       ImGui 工作台/文件管理/编辑器
├─ third_party/imgui/                     Dear ImGui 1.92.4（含 imconfig.h 启用 32 位索引修复）
```

## 8. 后续方向

| 优先级 | 方向 | 复杂度 |
|--------|------|--------|
| P1 | `threads` 增强：跨 worker 事件总线 + Promise 封装（同步结果/异常传递已完成） | 中 |
| P2 | `images` 高级：腐蚀/膨胀/开闭运算等形态学 | 中 |
| P3 | NativeFrame 调试统计（句柄数/占用字节） | 低 |
| P4 | OCR 桥接 | 高 |

## 9. 构建注意事项

1. **源码编码**：修改含中文的 Java/JavaScript/Markdown 文件时使用 `apply_patch` 或明确保留 UTF-8 的编辑器；不要使用未指定 UTF-8 编码的旧版 PowerShell 写文件命令
2. **.so 同步**：`build-quickjs.ps1` 会自动把 `libquickjs.so` 和 `libquickjs_jni.so` 复制到三个 ABI 的 `modules/autojs/src/main/jniLibs/`；成功后再执行 Gradle 打包
3. **JNI NEEDED 路径**：CMake 的 `IMPORTED_LOCATION` 会嵌入 ELF NEEDED 条目，需用 `IMPORTED_NO_SONAME TRUE` + flat 目录避免绝对路径
4. **ImGui 32 位索引**：Android GLES2 不支持 `glDrawElementsBaseVertex`，必须启用 `#define ImDrawIdx unsigned int` 解决 64K+ 顶点溢出
5. **定时器事件循环**：QuickJS 定时器在脚本主线程结束后才运行，测试脚本不能用 `sleep` 后断言回调结果
6. **Markdown 中文**：统一使用 UTF-8，并在修改后重新读取中文段落或执行编码检查，避免终端默认代码页造成乱码
