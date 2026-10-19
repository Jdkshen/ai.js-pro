# Phase 0：「:native」隔离脚本进程 MVP（dev-942/943/944）

> 目标：带 `lib/*.so` 或 `*.dex` 的第三方项目，自动在独立进程 `:native` 里运行；native 崩溃不带走主 App；主进程只做 IPC 控制、任务列表、停止、日志。

## 1. 架构与文件

```
主进程 com.jdkshen.aijspro            :native 进程 com.jdkshen.aijspro:native
  Flutter 玻璃界面 / 文件页  ──Messenger──▶  NativeScriptService
  PocNativeRunner（bind/linkToDeath）        AutoJs 引擎（App.onCreate 初始化）
  PocRuntime（run 路由/tasks/stopTasks）      floaty / console / ScriptRunLog
                                            第三方 .so（自己的符号空间）
```

新增/修改：

- `apps/app/src/flutterpoc/.../nativeproc/NativeScriptService.java`（新增，`android:process=":native"` 的 Service + Messenger 协议）
- `apps/app/src/flutterpoc/.../nativeproc/PocNativeRunner.java`（新增，主进程客户端：start+bind、运行列表、linkToDeath 崩溃恢复）
- `apps/app/src/flutterpoc/.../nativeproc/NativeProjectDetector.java`（新增，检测项目里是否有 `.so`/`.dex`）
- `apps/app/src/flutterpoc/AndroidManifest.xml`（注册 service，process=`:native`）
- `apps/app/src/flutterpoc/.../PocRuntime.java`（run/runProject 路由 native、tasks 追加、stopTasks 支持 `native:` token）
- `modules/engine/.../ScriptEngineService.java`（新增 `setHeadlessUiMode`：隔离进程里 UI 模式脚本不开 ScriptExecuteActivity）
- `modules/engine/.../runtime/api/Floaty.java`（隔离进程 FloatyService 未创建时就地建窗，避免脚本线程永久阻塞）
- `apps/app/src/main/java/.../App.kt`（`:native` 进程跳过定时任务/动态广播/示例同步等主进程职责）

协议（Messenger）：RUN=1 `{path,workDir,token}`+replyTo；STOP=2 `{token}`；STARTED=100 / FINISHED=101 / ERROR=102。任务 token = `native:<id>`，任务页显示「隔离进程」。

## 2. 真机验证（K40，dev-944）

- **隔离进程可跑脚本**：临时 native 项目 `__native_probe`（含 dummy `.so` + `sleep(8000)`）→ :native 进程里输出 `native-probe start` / `done`，8s 后任务列表自动消失。
- **崩溃隔离生效**：`auto_jspro_imgui` 在 :native 进程里加载其 `libImGuiBridge.so` 后仍在它自己的 `ButtonEx` 处 SIGSEGV（GImGui 为空，与之前一致）；主进程 pid 不变、Flutter 界面仍在；`PocNativeRunner` 收到 binder death 后打印「隔离脚本进程已退出（连续第 1 次）」并自动重连。
- **任务页**：运行中显示 `main / 隔离进程 / 路径`，点 ✕ 能停止（走 `native:` token），结束后列表自动清空。

## 3. 仍未达成 / 下一步

- **ImGui 悬浮窗仍未显示**：崩溃点仍是它自己 `.so` 的 `ButtonEx`（`GImGui == NULL`），说明该第三方桥的 native 上下文问题独立于主进程/符号冲突；隔离进程让它可以安全复现和调试，但要让画面出来还需继续定位它的 native 桥（下一步：在其 `nativeInit/nativeNewFrame` 前后加桩、验证 DexClassLoader/JNI 注册和 ImGui 上下文创建顺序）。
- UI 模式脚本在 :native 里 headless 跑（无 Activity），脚本若用 `ui.*` 会运行时报错；隔离进程不提供 auto/click/selector（设计限制）。
- :native 进程会被常驻绑定；主进程重启后旧 native 任务无 QUERY 恢复；floatRun 仍走主进程。

## 4. 发布

- dev-944：APK 265,555,573 B，sha256 `7ee8ab09f67661d34290c1380fc693f21b08dccdc28cc31a73b0c525a5a95581`；K40 与小米 8 均已安装。

## 5. 选项 1 继续定位（dev-945 实验，结果：仍未显示 ImGui）

- 从 :native 崩溃 tombstone 看到关键差异：隔离进程里崩溃线程是 **Script-0（脚本线程）**，而主进程 UI 模式时崩溃在主线程。推测它自己的 ImGui 上下文创建线程与渲染/控件调用线程不一致。
- 实验 dev-945：把 Floaty.requestWindowCreation 改成任何有 Looper 的线程就地建窗，让 surfaceCreated / nativeInit 与脚本渲染循环都在 Script-0 上。结果：仍然在它自己的 ButtonEx 崩（GImGui 为空），说明不是简单线程切换能解决的，崩溃根因在该桥自身的 ImGui 上下文创建 / JNI 注册序列。
- 已回退该实验（dev-946 恢复稳定行为）；崩溃隔离仍然有效：主 App 不闪退、runner 自动重连。
- 下一步候选：a) 反汇编它的 nativeInit/nativeNewFrame 并做最小 JNI 桩验证上下文创建顺序；b) 以它自带打包 APK 为参考运行时，反向对比进程/初始化差异；c) 转 Phase 1（完整 :script 进程）。

## 6. 参考 Auto.js Pro（反编译/真机对照，dev-947~950）

- Pro 反编译源码已在仓库 `.artifacts/pro-official/src/sources`、`.artifacts/pro-apk/auma-src/sources`（此前逆向留下的完整源码树），可直接对照 `ScriptExecuteActivity` / `LoopedBasedJavaScriptExecution` / floaty 实现。
- 真机对照结论：Pro 打包运行时（`js_imgui:script` 进程）里 `ImGuiBridge: EGL 880x1155` 的 pid=tid=main，脚本与 native 初始化/控件调用都在脚本进程主线程；我们 :native 里 EGL/nativeInit 在主线程，但 Button 崩在 `Script-0` 脚本线程 → 线程模型不同是当前最可疑根因。
- 实验 dev-947/948：:native 先把项目整树复制到应用私有目录再执行（对齐 Pro 打包布局）——staging 生效，但崩溃点不变，排除路径/dex 可写假设。
- 实验 dev-949：把 headless UI 模式脚本整段 post 到主线程执行（模拟 Pro 主线程模型）——脚本 2ms 内结束且无 ImGui 日志，进程随后退出，说明只把 Runnable 丢到主线程并不能让 `LoopBased` 引擎的循环正确跑起来，该实验已回退（dev-950 恢复稳定）。
- 下一步正解：按 Pro 模型给 :native 提供「主线程 Looper 上跑的 UI 模式宿主」（等价于一个在 :native 进程内的 ScriptExecuteActivity/主线程引擎循环），可直接对照 `.artifacts/pro-official` 里的 Pro `ScriptExecuteActivity` 与引擎执行实现照做。
