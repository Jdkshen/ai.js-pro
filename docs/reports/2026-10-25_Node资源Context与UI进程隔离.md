# Node 资源 Context 兼容与 UI 进程隔离

日期：2026-10-25。对应用户的 `ctx.loadResources is not a function` 和“减少界面对主进程的依赖”。本轮候选 **1249**，未发布；最终包验证结果见下方。K40 保持业务运行，所有安装、停止和故障注入仅针对空闲的小米 8 `ce4d2bdb`。

## 问题与已核实契约

- 原来 `$autojs.androidContext` 返回普通 Application Context，缺少高级版的 `loadResources(String)`。这是 Java 桥返回类型不兼容，日志本身属于脚本异常。
- 本机高级版静态参考的 `res.js` 明确调用 `$autojs.androidContext.loadResources(".../resources.ap_")`；已有反编译的 `V8AndroidContext` 是带资源覆盖的 ContextWrapper，覆盖 `getResources/getAssets`。据此实现自己的资源 Context，没有复制对方实现。
- 使用 reverse-engineer-anything 技能，REA JavaScript 分析证据 ID：`ev_7064f71e7251560ad63026778a0c8aff27c4cb0fb5962c98491a5281af0040d3`。这是静态证据，不代表运行过对方播放器。REA Android JADX 工具在当前 Windows 环境返回不支持，未宣称成功；上下文类型由本机已有提取源码交叉核实。
- “听海播放器项目”原始文件目前不可读取。本轮使用等价资源包和真实 UI 复现验证；不声称原项目全部依赖已经验收。

## 资源接口

`ScriptResourceContext` 由每个 Node job 独立持有，Java 桥的 context 操作返回这个真实 Java 对象。示例：

```js
require('rhino').install();
const ctx = $autojs.androidContext;
ctx.loadResources(require('path').join(__dirname, 'build/resources.ap_'));
const ui = require('ui');
// 此后 ctx.getResources()/getAssets()/getTheme()、ui.R 和原生 Activity 使用同一资源表。
```

- 支持已编译 `.ap_` / `.apk`，相对路径按本次项目目录解析；它是资源包加载器，不承担设备上 aapt2 源码编译。
- 独立 AssetManager 挂载宿主 APK、split 和工程资源，宿主资源表不被覆盖；没有写入旧的进程全局 `CompiledProjectResources.sOverlay`。
- 从 `resources.arsc` 读取真实包名，不能用 AndroidManifest 包名代替。PC 工具生成的表包名可能带 `.feature`。
- 空路径、缺失文件、非 ZIP/无资源表、宿主 sidecar 摘要不匹配等报错；加载成功前不发布新状态，失败保留旧表。
- 同一资源包名重新加载时替换旧表。MI8 实测直接追加两个同包名 APK 会保留旧值，不能依赖 addAssetPath 的追加顺序处理更新。
- Context 的 inflater、theme 在加载后刷新；资源缓存按 job owner + revision 区分，LRU 最多 8 份工程表。结束时移除 owner 注册和引用，不主动关闭仍被 Activity/View 使用的 AssetManager。
- 直接调用 context.loadResources 后，`ui.setMainActivity` 自动选择原生 Java Activity；不必再调用一次 ui.loadAndroidResources。原有 ui.loadAndroidResources 也会挂载到本 job 的资源 Context。
- 支持只有编译资源包、没有 project.json / res 源目录的加载；`layoutId` 界面调用 recreate 时保留布局 ID，不再错误要求非空 layoutXml。
- 已创建的 Activity 保持自己的资源快照；更换资源后通过 recreate 重建界面。

主要文件：

- `modules/engine/src/main/java/com/stardust/autojs/core/ui/inflater/ScriptResourceContext.java`
- 同目录 `CompiledProjectResources.java`、`ResourceTablePackage.java`、`ProjectResourceBundle.java`、`ProjectResources.java`
- `modules/engine/src/main/java/org/autojs/source/engine/android/Node24HostBridge.java`
- `modules/engine/src/main/java/com/stardust/autojs/engine/node24/NodeUiHost.java`、`NodeUiLocalSession.java`
- `modules/engine/src/main/assets/node24/ui.js`
- `apps/app/src/main/java/com/jdkshen/aijspro/engine/NodeUiLocalActivity.java`

## UI 进程边界

| 内容 | 当前进程 | 说明 |
| --- | --- | --- |
| 原生 Node UI | `:node24_1` / `_2` / `_3` | 与该脚本的 Java 回调、动态类和资源 Context 同槽 |
| 旧版 NodeUiActivity | `:nodeui` | 本轮从主进程迁出 |
| NodeUiDialogActivity、回退 overlay | `:nodeui` | 与 NodeUiRenderService 静态回调表同进程 |
| Node 定时任务、设置读写后端 | 主进程 | 独立保留 NodeUiBridgeService，不能跟 UI 一起搬走 |
| Flutter 主界面、权限/自动化协调 | 主进程 | 沿用既有架构 |
| Rhino 传统 ScriptExecuteActivity 等 | 仍有主进程路径 | 本轮没有把三引擎的所有 Activity 统一迁移；Rhino runtime/static 依赖需另做改造 |

`NodeUiActivityClient` 分离渲染和 work/settings 两条 Binder 连接。渲染服务拒收 work 请求，主服务拒收渲染请求；重连时重新建立 latch，Binder 断开立即唤醒在等待的调用。

全局停止和悬浮菜单触摸切换改走应用内非导出控制广播；不为了清理而启动新的 renderer。脚本结束按 owner 关闭它自己的对话框和回退浮窗；透明 Dialog 的音量上键也转到主进程全局停止协调器。旧版 UI 的静态回调、服务和 Activity 必须一起放在 `:nodeui`，不能只改一个 Activity 的 manifest process。

### 取消时正在执行的 Java 回调

收尾读取 crash buffer 发现 21:51 调试阶段一条 `:node24_1` 异常：`JavaScript callback threw an exception or was cancelled` 从 Handler Runnable 抛到 Looper。JNI 的通用错误把“取消中”与“真实脚本异常”共用一条路径，原来的 CallbackClosedException 分类无法覆盖这个竞态。

补充 `beginCallbackShutdown`：Job.cancel、UI 生命周期取消和 native 执行结束时先发布 Java 回调关闭标记，再停止 native。接口代理和动态类回调在入口及异常出口检查该标记，取消/关闭后返回 Java 默认值或调用父类；脚本活跃时仍传播真实异常。没有按错误文字一概吞掉异常，也没有修改 Node native 二进制。

MI8 调试包及最终 release/R8 包各通过 20 轮 Handler 回调运行/排队时取消，crash buffer 没有新增条目、主 PID 不变；同步 Runnable 内主动抛出的 JS 错误仍可被外层脚本捕获。旧调试崩溃记录保留在证据中，不清空缓存伪造“没有崩溃”。

主要文件：`NodeUiProtocol.java`、`NodeUiActivityClient.java`、`NodeUiBridgeService.java`、新增 `NodeUiRenderService.java`、`NodeUiDialogActivity.java`、主 Manifest，以及 `HostScripts` / `PocRuntime` / `CircularMenuWindow`。

## 验证记录

- JVM：资源表解析新增 5 项、Manifest 进程边界契约新增 3 项；最终 engine **450** 项、app **393** 项通过，共 **843** 项，0 失败/错误。
- Node 契约：资源缺失/ID、显式 context 资源、动态 UI、Java 交互、dialogs、settings、work_manager 共 7 组通过。
- MI8 调试包：资源接口 **32 项**断言通过，包含错误回滚、ALPHA/BETA 同包资源替换、20 次加载以及真实原生布局/Activity 资源一致。
- `.ap_` 与无 project.json：**6 项**通过，包含 assets、主题、宿主资源表未改变和原生 UI。
- 编译布局 recreate：**20 轮**通过，结束后无任务残留（任务快照中只有采集自身的 MCP 探针）。
- 渲染隔离：**15 项**通过，旧版 Activity、Dialog 真实 WindowManager 窗口和 IPC 回执、独立停止、全局停止、主 settings/work 后端均通过。
- 故障注入只终止经同 UID/进程名/PID 核对的 `:nodeui`：调试包主 PID **30519**、最终 release/R8 主 PID **3560** 分别保持不变，MCP 响应，renderer 新 PID 出现并可再次打开旧版 UI。该证据验证进程退出隔离，不等于复现了之前 destroyed mutex 的根因。
- 最终 release/R8 包重复通过以上资源 32 项、`.ap_` 6 项、20 轮重建和隔离 15 项；另验证系统返回键关闭旧版 Activity、音量上键关闭 Dialog 并停止任务。最终任务列表只剩采集探针，无 UI 窗口残留。
- DP 只读架构审阅任务 `23bd213c90ac4bf5` 超时 420 秒，无最终报告；没有把提交回执或超时当成完成。代码、集成和上述验证由主助手完成。

原始证据：`.artifacts/ui-context-20261025/` 下的 `resource-result.json`、`raw-result.json`、`stress-result.json`、`isolation-result.json`、各构建日志和脚本；这些路径在当前电脑保留，未加入 Git。测试工程均在手机 `_ui_context_20261025` 下，未改用户业务工程。

## 候选包与剩余验收

- 已重建 inrt 内置模板，19.30 MiB，`verifySlimPackagingTemplate` 通过；插件自己的旧模板未覆盖。独立 APK 本轮只同步共享引擎实现，没有新增 Node24 独立打包能力。
- 最终包：`.artifacts/ui-context-20261025/candidate-verified/AI-js-Pro-Flutter-release-candidate-1249.apk`，**140,785,224 bytes / 134.26 MiB**。
- SHA-256：`774ba19ea886f84446939ffb158a67c015877d2bd6417e30c153c9c165c17613`。本地测试签名、非 debuggable；候选构建未发布，不可把该包标为正式签名发布包。
- 22 个 ELF 无白名单 16KB 检查、最终 DEX Retrofit 10 API / 2 wrapper 泛型及 streaming 注解检查、瘦身模板校验均通过。
- MI8 已覆盖安装最终 1249；宿主资源表与 debug 不同，验收工程已针对最终 APK 重新编译、更新 sidecar，不能复用调试包的资源表摘要。
- 更新源、`project-versions.json`、GitHub Release 均未改，仍为 **dev-1247**。K40 保持 dev-1199、未停止或安装。
- 原播放器、原 Android 16/16KB 设备尚未验证；高级版全部 UI/API 的等价性仍不能据此宣称完成。
