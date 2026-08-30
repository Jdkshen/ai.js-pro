# AI.js Pro 双 JavaScript 引擎架构

> 状态：双引擎与 Native Frame + C++ OpenCV 第一批能力已落地。QuickJS 为显式选择的新引擎，Rhino 仍是默认兼容引擎。

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
| `toast`、`toastLog` | 已接入 | 通过 Java UI Handler 显示 |
| `sleep` | 已接入 | Native 分片等待，可被停止信号打断 |
| `click`、`press`、`longClick`、`swipe` | 已接入 | 调用现有无障碍手势实现 |
| `back`、`home`、`recents` | 已接入 | 调用现有全局无障碍动作 |
| `notifications`、`quickSettings` | 已接入 | 调用现有全局无障碍动作 |
| `setClip`、`getClip` | 已接入 | 复用现有剪贴板实现 |
| `currentPackage`、`currentActivity` | 已接入 | 复用前台页面信息提供器 |
| `requestScreenCapture`、`captureScreen` | 已接入 | `ImageReader` RGBA Plane 直接复制到 C++ `cv::Mat`，不创建 Bitmap |
| `images.pixel`、`NativeFrame.pixel` | 已接入 | 返回 ARGB 整数，像素不进入 JS 堆 |
| `images.findColor` | 已接入 | C++ 直接扫描 RGBA，支持 `threshold` 和 `region` |
| `images.read`、`images.findImage` | 已接入 | OpenCV C++ 解码与 `matchTemplate`，只返回坐标/相似度 |
| `NativeFrame.recycle` | 已接入 | 显式释放；引擎销毁时自动回收遗留句柄 |
| `yolo.load`、`detector.detect(NativeFrame)` | 已接入 | arm64 NCNN；`cv::Mat` 通过 DirectByteBuffer 同步直连推理，不创建 Bitmap |
| `detector.close` | 已接入 | 显式释放 NCNN 模型；引擎销毁时自动清理遗留 detector |
| `files`、`http`、`shell`、`app` | 待接入 | 应通过白名单 Host API 分批增加 |
| `timers`、`threads`、`events` | 待接入 | 需要 QuickJS Job Queue 与 Android Looper 的专用调度层 |
| `ui`、E4X、Rhino Java 互操作 | 不兼容 | 继续使用 Rhino 执行这类旧脚本 |

示例脚本位于 `app/src/main/assets/sample/脚本引擎/QuickJS运行环境测试.js` 和 `QuickJS Native Frame 回归测试.js`。

### Native Frame 用法

```javascript
// @engine quickjs
if (!requestScreenCapture('portrait')) throw new Error('未获得截图权限');

const frame = captureScreen();
try {
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

`NativeFrame` 在 JS 中只保存句柄、宽和高；完整像素始终留在 C++ 内存。长循环必须用 `try/finally` 调用 `recycle()`，防止在单个长时间脚本内积压截图。

### NativeFrame + NCNN YOLO

```javascript
// @engine quickjs
const root = 'asset://sample/YOLO目标检测/NCNN版本/models/';
const detector = yolo.load({
    backend: 'ncnn',
    param: root + 'yolo26_320.param',
    bin: root + 'yolo26_320.bin',
    labels: root + 'labels.txt',
    inputSize: 320,
    threads: 4
});
try {
    if (!requestScreenCapture('portrait')) throw new Error('未获得截图权限');
    const frame = captureScreen();
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

可直接运行 `app/src/main/assets/sample/YOLO目标检测/QuickJS NativeFrame版本/` 中的单帧和 60 帧实时案例。当前这条 QuickJS 直连桥先开放 arm64 NCNN；ONNX Runtime 和 OpenCV DNN 的旧 Rhino API 保持不变。

## 4. 关键代码

```text
autojs/src/main/java/com/stardust/autojs/
├─ script/JavaScriptSource.java           首行指令识别和引擎路由
├─ engine/QuickJsJavaScriptEngine.java    Java 引擎生命周期
├─ engine/QuickJsNativeBridge.java        JNI 方法声明和动态库加载
├─ engine/QuickJsHostBridge.java          白名单 Java API 桥
├─ engine/QuickJsException.java           Native JS 异常类型
├─ AutoJs.java                            Rhino / QuickJS 双引擎注册
└─ ScriptEngineService.java               两种执行模型分流

autojs/src/main/cpp/
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
.\build-common-debug.ps1
```

只重建 QuickJS Native 库：

```powershell
.\autojs\src\main\cpp\build-quickjs.ps1
```

已有 Native 库时只构建 Android 工程：

```powershell
.\build-common-debug.ps1 -SkipNative
```

当前输出 `armeabi-v7a`、`arm64-v8a`、`x86`，两个 QuickJS `.so` 都使用 16 KiB 最大页对齐。

## 6. 版本与来源

- 上游：Fabrice Bellard 官方 QuickJS；
- 固定版本：`2026-06-04`；
- 官方源码包：`quickjs-2026-06-04.tar.xz`；
- 源码包 SHA-256：`B376E839B322978313D929FD20663B11BA58B75DF5A46C126DD19EA2FA70AD2A`；
- 许可证：MIT，原始 `LICENSE` 已保留在 vendored 源码目录。

## 7. 下一阶段建议

下一步可在同一句柄层增加裁剪、缩放、灰度化和多点找色，并把 NativeFrame 直连推理扩展到 ONNX Runtime、OpenCV DNN 和 YOLO 结果悬浮框。之后再增加 `files/http/shell/app` 白名单桥。
