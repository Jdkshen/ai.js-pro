# QuickJS ImGui 悬浮窗 API 边界

## 定位

ImGui 不再用于 APK 主界面或工作台。它作为 **QuickJS 脚本引擎的可选悬浮窗 API**，用于脚本创建实时调试面板、按钮、日志和图形，定位与 Auto.js Pro 的脚本悬浮 UI 类似。Miuix/Compose 继续负责 APK 页面。

## 预期调用方式

```javascript
// @engine quickjs
const panel = imgui.createWindow({ title: "调试", width: 360, height: 480 });
panel.text("脚本运行中");
panel.button("停止", () => engines.myEngine().forceStop());
panel.show();
```

这是 API 形状示意，不代表当前已实现。正式 API 应避免将 ImGui 指针或 JNI 对象暴露给 JS。

## 接入点

- `QuickJsJavaScriptEngine.init()` 在创建 Native Context 后注册 `imgui` 模块。
- `QuickJsNativeBridge` 只提供句柄化的创建、帧提交、事件取回和销毁方法。
- C++ 实现放在 `modules/autojs/src/main/cpp/`，与 `quickjs_jni` 共享每个脚本引擎的所有权。
- Android 窗口层使用独立的悬浮服务/Surface，不进入 `MainActivity` 的 Miuix 导航树。
- `QuickJsJavaScriptEngine.destroy()` 和 `forceStop()` 必须销毁该引擎创建的全部窗口和回调，不留悬浮窗。

## 线程和性能

- 渲染线程只消费不可变帧命令，不直接调用 JS。
- 触摸事件进队后由 QuickJS 引擎线程取回，避免跨线程进入 JS Context。
- 每个引擎与窗口建立所有权映射，脚本停止时统一释放。
- 使用 arm64-v8a、armeabi-v7a 和 x86 独立产物，并通过构建开关决定是否打包。

## 与旧 ImGui 工作台的关系

当前删除的是旧 Activity/SurfaceView 工作台及其预编译 `libautojs_imgui.so`。新能力应在 QuickJS 模块内独立实现；如需参考旧渲染码，从 Git 历史定向提取，不恢复旧工作台入口。
