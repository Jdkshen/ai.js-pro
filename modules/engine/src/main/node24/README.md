# Node 24 原生回调桥（Android arm64-v8a）

本目录只负责构建 `libajsnode24bridge.so`。Node 24.22.0 运行时使用
`../assets/source-engine-upgrade/node24/arm64-v8a/libnode.so` 的固定预编译产物，
不从 SDK 下载，也不重建 Node 或 V8。

## 前置条件

- NDK r26d（`C:\Android\ndk-r26d`）、CMake/Ninja
  （`C:\msys64\mingw64\bin`）、Python（`C:\Python314\python.exe`）。
- Node **v24.22.0** 源码树，含 `src/node.h`、`deps/v8/include`、
  `deps/uv/include`。
- 同一次 Node 构建生成的 Android arm64 shared `config.gypi`；其同级目录或
  `out/` 下必须有 `node_base.target.mk`，用于推导 V8 ABI 宏。
- 内置 `libnode.so` 必须与 `runtime.json` 中的 SHA-256 一致，脚本会在配置前
  校验，不匹配立即失败。不替换内置 `libc++_shared.so`。

## 构建

```powershell
.\tools\build-node24-bridge.ps1 `
  -NodeSourceDir 'C:\Users\18101\Documents\AutoJsPro-本地工程-20261008\工程\output\autojs-pro-engine-upgrade-20261015\native\third_party\node-v24.22.0' `
  -NodeConfigFile 'C:\Users\18101\Documents\AutoJsPro-本地工程-20261008\工程\output\autojs-pro-engine-upgrade-20261015\native\artifacts\node24-candidate\arm64-v8a\config.gypi' `
  -NdkRoot 'C:\Android\ndk-r26d' `
  -CMake 'C:\msys64\mingw64\bin\cmake.exe' `
  -Ninja 'C:\msys64\mingw64\bin\ninja.exe' `
  -Python 'C:\Python314\python.exe'
```

`-NdkRoot`、`-CMake`、`-Ninja`、`-Python` 的默认值即上面四项；
`-BuildDir` 默认为 `output\node24-bridge\native-build`。
脚本以 CMake 配置 Android `arm64-v8a` / API 24 / `c++_shared` / Release，
强制 `NODE24_LIBRARY` 指向上述内置 `libnode.so`，构建
`ajsnode24bridge`，再用 NDK `llvm-strip --strip-unneeded` 处理，并检查产物
是 AArch64 ELF。所有原生命令都会检查退出码，失败即停。

只构建、不改资源目录时不要加开关。确认产物后才使用：

```powershell
.\tools\build-node24-bridge.ps1 ... -UpdateAssets
```

`-UpdateAssets` 只复制 `libajsnode24bridge.so` 到内置 node24 资源目录，并且只
替换 `runtime.json` 中该库的 SHA-256；`libnode.so`、`libc++_shared.so` 及其
哈希不动，JSON 保持 UTF-8 无 BOM。脚本不删除、不移动文件，也不清理旧构建。

## 运行时语义

### V8 与线程亲和

- V8 Isolate、Node Environment 和 JS 执行**始终在 Node 运行线程**。Java 线程
  调用 `nativeInvokeCallback` 时只入队并等待，由 Node 线程的 libuv
  `uv_async` pump 取出并串行执行，绝不在 Java 线程进入 V8。
- 从 Java 发起的 callback，其外层 Java 线程会在原线程上服务该 callback 内产生
  的反射 host 调用，保持 Canvas/View 等 Android UI 调用的线程亲和；非 callback
  上下文的反射 host 调用由专用 callback worker 处理。该 worker 在第一次
  `__ajsEnableJavaCallbacks` 时才创建并 Attach 到 JVM，固定在自己的 Java 线程。
- 因此需要跨线程等待的只是 Java 调用，不是 V8 调用；嵌套的 Java/UI 调用由
  Node 线程 pump 与调用线程协作完成，避免死锁。

### 惰性启用与普通脚本快路径

Java host 可用时，桥只安装 `__autojsHost` 入口，不提前创建 worker。普通脚本
未调用 `__ajsEnableJavaCallbacks` 前，host 调用直接在 Node 线程执行，不入队、
不启线程，保留无 UI 脚本的快速路径。启用后 callback 队列和 worker 才参与；
启用状态在该会话内保持。

### 取消与会话关闭

`nativeCancel` / `nativeClose` 可从其他 Java 线程调用：设置 `closing`、清空待
执行 callback 队列、调用 `node::Stop`、唤醒等待者并中断 callback worker。Node/V8 的销毁
仍只发生在执行线程；等待中的 Java 调用会得到取消错误。单次 Java→Node callback
等待超过 10 秒也会触发停止，避免永久挂起。

### 自定义生成 UI

脚本生成的自定义 UI 仍由**同一个 Node 24 会话和同一个 Isolate**驱动。Java UI
对象仍在 Android/Java 线程上创建；生成 UI 的 JavaScript 和后续回调始终回到该
Node 线程执行，不会为 UI 另起 Node 进程或第二个 Isolate。因此 UI 回调与脚本的
`require`、模块缓存、全局状态处于同一进程上下文。

## ABI 约束

`node.h`/V8 头文件、生成 `config.gypi` 的构建、内置 `libnode.so` 必须都来自
同一个 Node **24.22.0** Android arm64 shared 构建，并使用匹配的 NDK r26d C++
运行时。`node24_bridge.cpp` 有 24.22.0 编译期静态检查，源构建还会用
`read_node_build_flags.py` 校验 `config.gypi`/`node_base.target.mk` 的 V8 ABI
开关；运行时会再次校验加载的 V8 版本。不要换成旧版外部 `libnode`，也不要在
同一 Android 进程中混用其他 Node/V8（Node 平台生命周期是进程级的，应使用独立
进程）。
