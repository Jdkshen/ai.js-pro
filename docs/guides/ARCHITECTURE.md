# AI.js Pro 架构总览（10 分钟版）

> 基线：2026-11-06 的工作区源码（dev-1362 前后），只做静态阅读，不假设设备已连接。
> 目的：先建立“模块 → 进程 → 引擎 → MCP → UI → 打包”的整体图，再看细节。
> 结论尽量带 <code>文件:行号</code>。不确定的地方集中放在 §8，不在正文里假装确定。
> 工具数、文件数、体积会随源码变化；标“按当前源码计数”的地方请重新核对。

## 0. 先记住 8 件事

1. 主 APK 是 <code>:app</code>；脚本执行默认不在主进程，而在 <code>:script</code>；Node 又在独立的 <code>:node24_1..3</code> 池进程。
2. 三个脚本引擎是 Rhino、QuickJS、Node24；脚本首行 directive 决定引擎，默认仍是 Rhino（<code>modules/engine/src/main/java/com/stardust/autojs/script/JavaScriptSource.java:16-31,95-110</code>）。
3. Flutter 是唯一主 UI；旧 Miuix 源集仍留在磁盘，但已从构建排除（<code>apps/app/build.gradle:290-304</code>）。
4. MCP HTTP 服务在主进程，工具定义集中在 <code>McpTools.kt</code>；APK/文件解析工具在 <code>:tools</code> 进程，通过 ContentProvider + cache 文件 IPC。
5. Node 进程池每进程单 Node runtime，不能用“同进程多 Node”当并行方案（<code>modules/engine/src/main/java/org/autojs/source/engine/android/Node24Pool.java:8-18</code>）。
6. 打包运行时模板有两份：插件里的全量模板，和主 APK 内的瘦身模板；<code>apps/inrt</code> 是生成模板的“运行时壳”。
7. 新增能力通常不是“加一个 Java 类”就结束：脚本 API、MCP 工具、示例、文档、构建校验各有一处入口，见 §7 速查表。
8. 不要被目录名误导：<code>apps/app/src/main/java/**/build/</code>、<code>autojs/build/</code> 是包名，不是构建产物；tracked 的 <code>gradle-wrapper.jar</code>、<code>flutter_embedding_*.jar</code>、<code>dx.jar</code>、<code>npm.zip</code>、两个 <code>template.apk</code> 都是有意入库的。

---

## 1. Gradle 模块图与职责

### 1.1 真实依赖关系

<code>settings.gradle:3-29</code> 注册了 16 个模块。把 project 依赖单独拉出来：

~~~text
:app
├─ :engine
│  ├─ :common ── :settingscompat（third-party）
│  ├─ :automator ── :common
│  ├─ :enhancedfloaty（third-party）
│  ├─ :rootshell（third-party）
│  └─ :apkbuilder（third-party）
├─ :automator
├─ :common
├─ :apkbuilder
├─ :mutabletheme（third-party）── :colorpicker（third-party）
├─ :multilevellistview（third-party）
├─ :rhino-language（compileOnly；lite flavor 额外 implementation）
└─ :engine-rhino（compatImplementation）── :rhino-language + :engine

:inrt（独立 application）── :automator + :common + :engine + :engine-rhino

plugins/ 下两个独立 application：
:apkbuilder-plugin（无 project 依赖；消费 :inrt 生成的 template.apk）
:xiangqi-plugin（无 project 依赖；自包含原生引擎 + samples/象棋自动走棋）
~~~

证据：

- <code>settings.gradle:3-29</code> 模块列表与 projectDir。
- <code>apps/app/build.gradle:630-748</code> 依赖；project 依赖集中在 <code>648,657,705-716</code>。
- <code>modules/engine/build.gradle:46-116</code>；<code>modules/automator/build.gradle:42-50</code>；<code>modules/common/build.gradle:33-45</code>；<code>modules/engine-rhino/build.gradle:25-28</code>；<code>modules/rhino-language/build.gradle:14-19</code>；<code>apps/inrt/build.gradle:118-131</code>。
- <code>third-party/MutableTheme/mutable-theme/build.gradle:25-33</code> → <code>:colorpicker</code>。
- plugin 的 build.gradle 见 <code>plugins/apkbuilder-plugin/build.gradle:1-108</code>、<code>plugins/xiangqi-plugin/build.gradle:1-146</code>。

### 1.2 每个模块一句话

| 模块 | 一句话职责 |
|---|---|
| <code>:app</code> | 主 Android 应用：Flutter/native UI、脚本宿主路由、三引擎注册、自动化、MCP 服务、打包与更新。 |
| <code>:engine</code> | 脚本运行时核心：Rhino/QuickJS/Node 宿主桥、ScriptRuntime API、Node 资产、原生库、工具链入口。 |
| <code>:automator</code> | 无障碍自动化基础：选择器、节点动作、UI 对象集合等。 |
| <code>:common</code> | 公共基础库：Activity/工具/日志/通用 UI 依赖、设置兼容库。 |
| <code>:rhino-language</code> | Rhino 1.7.15.1 的 vendored jar 包装；只提供语言/解析依赖。 |
| <code>:engine-rhino</code> | 可选 Rhino 执行模块；通过反射 provider 被 compat flavor 装进包。 |
| <code>:inrt</code> | 打包运行时壳 App：自带引擎和 project 资产，产出 template.apk 的基础 APK。 |
| <code>:apkbuilder-plugin</code> | 可选安装的 APK 打包插件：携带全量 template.apk，供主 App 反射取模板。 |
| <code>:xiangqi-plugin</code> | 可选安装的象棋插件：自包含 Pikafish/识别 JNI 和 samples/象棋自动走棋 脚本。 |
| <code>:enhancedfloaty</code> | vendored 浮窗库。 |
| <code>:mutabletheme</code> | vendored 主题/换肤库。 |
| <code>:colorpicker</code> | vendored 颜色选择库（由 MutableTheme 依赖）。 |
| <code>:multilevellistview</code> | vendored 多级列表视图。 |
| <code>:settingscompat</code> | vendored 旧版设置兼容库（被 common 依赖）。 |
| <code>:rootshell</code> | vendored root shell 支持库。 |
| <code>:apkbuilder</code> | vendored ApkBuilder 库：修改模板/替换 project/重新签名，产出独立 APK。 |

补充：<code>:rhino-language</code> 的 jar 在 <code>modules/rhino-language/libs/rhino-1.7.15.1.jar</code>；<code>:engine</code> 还有 vendored <code>dx.jar</code>、<code>opencv-5.0.0-16kb-page-fix.aar</code> 等 flatDir 依赖。<code>third-party/</code> 下的模块都是 Android library，不是 Maven 坐标。<code>tools/apk-components</code> 有独立 settings.gradle，是可选工具链组件，不在根 settings.gradle 的 16 个模块里。

### 1.3 变体/flavor

<code>apps/app/build.gradle:272-289</code> 只保留一个 channel flavor <code>miuix</code>，engine flavor 有：

- <code>compat</code>：<code>RHINO_COMPAT=true</code>，<code>compatImplementation project(':engine-rhino')</code>，可执行 Rhino。
- <code>lite</code>：<code>RHINO_COMPAT=false</code>，不执行 Rhino，但仍把 <code>:rhino-language</code> 编进来供编辑器高亮等旧代码链接（<code>apps/app/build.gradle:708-714</code>）。
- 常见任务名是 <code>miuixCompatDebug</code>/<code>miuixLiteDebug</code>；miuix 只是历史命名，不代表 UI 仍是 Miuix（<code>docs/HANDOFF_AI.md:252-257</code>）。

---

## 2. 进程模型

### 2.1 进程总表

| 进程 | 负责什么 | 谁启动/绑定 | 主要通信 | 证据 |
|---|---|---|---|---|
| 主进程（包名本身） | Flutter/native UI、路由、AutoJs/ScriptEngineService、定时任务、MCP HTTP、更新、部分自动化 | Launcher/系统；App.onCreate | Binder 到子进程、ContentProvider、广播、HTTP | <code>apps/app/src/main/AndroidManifest.xml:51-63,224-229</code>；<code>apps/app/src/main/java/com/jdkshen/aijspro/App.kt:66-96</code> |
| <code>:script</code> | 普通脚本宿主（Rhino/QuickJS/Node 编排）、脚本 UI、日志、<code>:script</code> 自动化代理 | 主进程 <code>PocScriptHost</code> bind <code>ScriptHostService</code>；UI 模式由 <code>ScriptExecuteActivity</code> | Messenger（RUN/STOP/STARTED/FINISHED/ERROR/CONSOLE）、ContentProvider（mcp.automation）、广播 | <code>apps/app/src/flutterpoc/AndroidManifest.xml:44-49</code>；<code>apps/app/src/flutterpoc/java/com/jdkshen/aijspro/flutterpoc/scriptproc/PocScriptHost.java:31-100</code>；<code>modules/engine/src/main/AndroidManifest.xml:17-29</code> |
| <code>:native</code> | Phase0 隔离脚本（自带 .so/.dex 的项目）；保留为回退路径 | 主进程 <code>PocNativeRunner</code> bind <code>NativeScriptService</code> | Messenger 协议与 <code>:script</code> 同形（RUN/STOP/100-103） | <code>apps/app/src/flutterpoc/AndroidManifest.xml:37-42</code>；<code>apps/app/src/flutterpoc/java/com/jdkshen/aijspro/flutterpoc/nativeproc/PocNativeRunner.java:30-79</code> |
| <code>:node24</code> | 旧版单 Node 服务；capture 授权 Activity；Node UI 的 dialog/capture 辅助 | 主/脚本进程 <code>Node24Client</code> bind；或 legacy fallback | Messenger EXECUTE/RESULT/CANCEL/EVENT/LOG；状态/控制广播 | <code>apps/app/src/main/AndroidManifest.xml:474-478</code>；<code>modules/engine/src/main/java/org/autojs/source/engine/android/Node24Service.java:36-39,74-97,156-177</code> |
| <code>:node24_1..3</code> | Node 池槽：每进程单 Node runtime；默认并行执行 Node 的实体 | <code>:script</code> 的 <code>Node24Pool.acquire</code> 绑定 <code>Node24PoolServiceN</code> | 与 <code>:node24</code> 相同的 Messenger；池分配/释放 | <code>apps/app/src/main/AndroidManifest.xml:479-482</code>；<code>modules/engine/src/main/java/org/autojs/source/engine/android/Node24Pool.java:8-18,48-75</code>；<code>Node24Client.java:91-107</code> |
| <code>:nodeui</code> | 旧 Node UI/对话框/回退浮窗的渲染服务与 Activity | <code>NodeUiRenderService</code>/<code>NodeUiBridgeService</code> 路径 | Messenger（NodeUiProtocol）+ 广播 | <code>apps/app/src/main/AndroidManifest.xml:530-551</code>；<code>apps/app/src/main/java/com/jdkshen/aijspro/engine/NodeUiBridgeService.java:30-40</code>；<code>NodeUiRenderService.java:9-26</code> |
| <code>:tools</code> | MCP 的 APK/文件/ZIP 解析、编译、DEX/ELF 工具；不加载引擎/UI | 主进程首次调用 <code>McpToolkitProvider</code> 时由 ContentProvider 拉起 | content://<applicationId>.toolkit + cache 目录 request/response 文件 | <code>apps/app/src/main/AndroidManifest.xml:197-200</code>；<code>apps/app/src/main/java/com/jdkshen/aijspro/mcp/McpToolkitProvider.kt:16-27</code>；<code>McpToolkitBridge.kt:9-30</code> |
| <code>:inspector</code> | 布局分析快照的离线渲染器；不访问主进程 AutoJs | 主进程 <code>InspectorProcessBridge</code> bind <code>InspectorHostService</code> | Messenger（OPEN/CLOSE/READY/ERROR 等）+ 快照文件传输 | <code>apps/app/src/main/AndroidManifest.xml:560-562</code>；<code>apps/app/src/main/java/com/jdkshen/aijspro/ui/floating/layoutinspector/InspectorHostService.java:15-38</code>；<code>InspectorProcessBridge.java:19-24,36-50</code> |
| <code>:background</code> | Tasker <code>FireSettingReceiver</code> 专用 | Tasker 广播 | 广播 | <code>apps/app/src/main/AndroidManifest.xml:320-324</code> |
| <code>aijs_shizuku_&lt;pid&gt;</code> | Shizuku user service：以 Shizuku 授权身份执行 shell/安装，持有远端进程组/PFD | 各客户端进程通过 <code>Shizuku.bindUserService</code> 拉起 | Binder + ParcelFileDescriptor | <code>modules/engine/src/main/java/com/stardust/autojs/runtime/api/shizuku/ShizukuRemoteProcesses.java:38-65,66-80</code> |

补充：<code>:script</code> 自身还有 <code>LogActivity</code>、<code>InspectorCaptureService</code>、<code>McpAutomationProvider</code>、<code>AijsUiReceiver</code>、<code>NodeEngineBridgeScriptService</code> 等组件；无障碍服务 <code>SelectToSpeakService</code> 也声明在 <code>:script</code>（<code>modules/engine/src/main/AndroidManifest.xml:106-123</code>）。<code>:native</code> 的 Activity 在 <code>modules/engine/src/main/AndroidManifest.xml:33-35</code>；<code>:script</code> 的 <code>ScriptExecuteActivity</code> 在 25-29 行。<code>aijs_shizuku_&lt;pid&gt;</code> 不是 manifest 固定进程名：代码用当前客户端 pid 生成 suffix（<code>ShizukuRemoteProcesses.java:50-52</code>），由 Shizuku 启动/管理。<code>App.onCreate</code> 在 <code>:tools</code> 直接 return（<code>App.kt:69-70</code>），在非主进程跳过 MCP/调度/示例同步/悬浮球恢复等（<code>App.kt:147-162</code>）。

### 2.2 启动与通信速览

~~~text
Launcher → Splash/Main → Flutter GlassFlutterActivity
                              │
                 PocBridge / PocRuntime（主进程）
                    │                    │
          bind :script              bind :native（native 项目/Phase0 回退）
                    │
          :script ScriptHostService
             │             │
     Rhino/QuickJS   Node24Pool → bind :node24_1..3（每槽单 runtime）
             │
      Node24HostBridge / NodeUi（需要 UI 时）
             │
       NodeUiBridgeService（主进程）→ :nodeui 渲染；或 :node24_N 的本地 Activity

主进程 McpService（HTTP 8765）
  ├─ McpTools 直接在主进程处理 status/script/workspace/engine/...
  ├─ McpToolkitBridge → content://<applicationId>.toolkit → :tools（文件 IPC）
  └─ McpAutomationTransport → content://<applicationId>.mcp.automation → :script（文件 token IPC）

脚本/终端里的 Shizuku 命令 → bindUserService → aijs_shizuku_<pid>（Binder + PFD）
布局分析 → InspectorProcessBridge → bind :inspector（Messenger + snapshot）
~~~

关键协议点：

- 主进程到 <code>:script</code>/<code>:native</code>：都是 bindService + Messenger；消息号 1 RUN、2 STOP、3 STOP_ALL；回包 100 STARTED、101 FINISHED、102 ERROR、103 CONSOLE；<code>PocScriptHost</code> 注释把字段和 token 前缀写得很清楚（<code>PocScriptHost.java:43-67</code>；<code>PocNativeRunner.java:36-54</code>）。
- Node：<code>Node24Client</code> 绑定服务后用 Messenger 发 EXECUTE，结果通过 replyTo Messenger 回 RESULT/LOG；状态和控制另走显式广播（<code>Node24Service.java:36-39,74-97,156-177,248-266</code>）。
- <code>:tools</code> 不通过 Binder 传大 payload：主进程写 <code>cache/toolkit-ipc/&lt;key&gt;.request</code>，ContentProvider 读请求、写 response，主进程再读 response（<code>McpToolkitBridge.kt:19-30</code>；<code>McpToolkitProvider.kt:20-62</code>）。
- MCP 的 UI 自动化有双重路径：同进程 <code>:script</code> 直接 local；其它进程通过 <code>content://<applicationId>.mcp.automation</code> 的 call() 返回一个 cache 文件 token，再由调用方读文件，避免 Binder 大结果（<code>McpAutomationProvider.kt:13-33,42-58</code>）。
- Shizuku 的 start/install 通过 <code>ShizukuRemoteProcesses</code> 绑定 user service，stdout/stderr/stdin 都是 PFD（<code>ShizukuRemoteProcesses.java:66-118</code>）。
- 定时任务/停止等主进程职责：<code>:script</code> 通过显式广播交给主进程 <code>TaskSchedulerBridgeReceiver</code>/<code>StopAllScriptsReceiver</code>（manifest 522-527、496；<code>StopAllScriptsReceiver.java:7-12</code>）。

---

## 3. 三引擎架构

### 3.1 入口与引擎选择

- 引擎注册在 <code>AutoJs.initScriptEngineManager</code>：Rhino（仅 <code>RhinoEngineLoader.initialize</code> 成功时）、QuickJS、Node24、RootAutomator（<code>AutoJs.java:114-134</code>）。
- 引擎常量/首行 directive：<code>JavaScriptSource.java:16-31</code>；识别 <code>"quickjs";</code>、<code>"nodejs";</code>、<code>"rhino";</code> 和 <code>// @engine ...</code>（同文件 95-110、162-169）。
- 优先级：脚本首行 directive → 工程 <code>scripts.&lt;path&gt;.engine</code> → 工程顶层 engine → 默认 Rhino（<code>JavaScriptSource.java:95-110</code>）。
- MCP 侧引擎名归一化：<code>rhino / quickjs / nodejs</code>（兼容 node/node24），<code>McpEngines.kt:5-19</code>。
- 常见执行路径：主进程 <code>PocRuntime</code> 将普通脚本交给 <code>:script</code>；<code>ScriptExecuteActivity.execute</code> 会根据当前进程选 Activity（<code>ScriptExecuteActivity.java:56-69</code>）。

### 3.2 三个引擎各自是什么

**Rhino（兼容引擎）**

- 执行类：<code>LoopBasedJavaScriptEngine</code>（<code>modules/engine/src/main/java/com/stardust/autojs/engine/LoopBasedJavaScriptEngine.java:21</code>）；实际 provider 在 <code>:engine-rhino</code>，通过反射加载 <code>RhinoEngineProvider</code>（<code>RhinoEngineLoader.java:9-30</code>；<code>RhinoEngineProvider.java:33-46</code>）。
- prelude：<code>modules/engine/src/main/assets/init.js</code>；模块列表在 112-121 行，<code>runtime.bridges.setBridges(require('__bridges__.js'))</code> 在 101-102 行。
- Rhino API 资产：<code>modules/engine/src/main/assets/modules/*.js</code>，例如 <code>__http__.js</code>、<code>__floaty__.js</code>、<code>__ui__.js</code>、<code>__engines__.js</code>、<code>__ocr__.js</code>、<code>__plugins__.js</code>；Java 侧 API 在 <code>modules/engine/src/main/java/com/stardust/autojs/runtime/api/</code>。
- 结论：老 API 面最全；新引擎不保证“照抄一份”就完事，必须看具体 API 的移植状态。

**QuickJS（native 引擎）**

- 执行类：<code>QuickJsJavaScriptEngine</code>；init 创建 <code>QuickJsHostBridge</code>，调用 <code>QuickJsNativeBridge.create</code>（<code>QuickJsJavaScriptEngine.java:53-100</code>）。
- 原生实现：<code>modules/engine/src/main/cpp/quickjs_jni.cpp</code>；Java 桥 <code>QuickJsHostBridge.java</code>。
- prelude：C++ bootstrap 里直接在 global 上建对象（<code>$ocr</code> 6509-6579、<code>http</code> 6995、<code>engines</code> 7332-7351、<code>floaty</code> 8607-8608、<code>ui</code> 8994）；JS 兼容资产为 <code>modules/engine/src/main/assets/modules/qjs-*.js</code> + <code>common-*.js</code> 等，由 <code>QuickJsHostBridge.modernModulesScript</code> 的 assets 数组加载（<code>QuickJsHostBridge.java:334-348</code>）。
- 常见补充：<code>qjs-plugins.js</code> 是插件/OCR 的兼容层；C++ 和 Java 桥是实际执行者。

**Node24（Node 24.22.0 进程池）**

- 执行类：<code>Node24JavaScriptEngine</code>（<code>Node24JavaScriptEngine.java:20-30</code>）；init 通过 <code>Node24Pool</code> 分配 <code>:node24_N</code> 槽（同文件 166 行起；<code>Node24Pool.java:8-18,48-75</code>）。
- Host 服务：<code>Node24Service</code> + <code>Node24PoolService1..3</code>；每进程一个 Node runtime，客户端是 <code>Node24Client</code>。
- prelude：<code>Node24Prelude.wrap</code> 先注入 <code>node24/prelude.js</code>，再注可选 <code>node24/ui.js</code>，再按 <code>MODULE_ASSETS</code> 顺序加载 <code>node24/modules/*</code> 和公共 <code>modules/common-*.js</code>（<code>Node24Prelude.java:18-58,76-122</code>）。
- Host 桥：<code>Node24HostBridge.java</code> 暴露 <code>kind:"automation"|"image"|"projection"|"ocr"|"plugins"|"ui"</code> 等（协议注释 331 行附近；OCR 599 行起；UI 1185 行）。
- 默认用进程池；<code>:node24</code> legacy 仅在 <code>Node24Pool.legacyEnabled</code> 或池启动失败时回退（<code>Node24Pool.java:137-148</code>；<code>Node24JavaScriptEngine.java:166-170</code>）。

### 3.3 同一个 API，三引擎各自怎么实现
> 路径约定：本节和 §3.3 表格中的 <code>assets/...</code> 都指 <code>modules/engine/src/main/assets/...</code>（例如 <code>assets/modules/__ocr__.js</code> = <code>modules/engine/src/main/assets/modules/__ocr__.js</code>）；<code>node24/...</code> 指 <code>modules/engine/src/main/assets/node24/...</code>。表格里的 <code>...</code> 表示实现跨多个 block，不是省略源码。


| API / 模块 | Rhino | QuickJS | Node24 |
|---|---|---|---|
| <code>$ocr.create</code> / OCR | JS 包装在 <code>assets/modules/__ocr__.js:33-97</code>；失败可降级 ppocr（39-46）。 | C++ <code>$ocr.create</code>（<code>quickjs_jni.cpp:6509-6579</code>）→ <code>QuickJsHostBridge.ocrCreate</code>（1277-1285）→ Java <code>Ocr/PPOcr</code>。 | <code>require('ocr').createOCR</code> 在 <code>assets/node24/modules/ocr.js:217-287</code>；host <code>Node24HostBridge.handleOcr/createOcr</code>（599-680）。 |
| <code>http</code> | <code>assets/modules/__http__.js:1-20</code> 基于 OkHttp。 | C++ <code>nativeHttpRequest</code>（<code>quickjs_jni.cpp:2839-...</code>），global <code>http</code> 在 6995。 | Node 原生 <code>http/https</code>；prelude 的请求封装用 <code>require('https')/require('http')</code>（<code>prelude.js:2495</code>）。 |
| <code>ui</code> | <code>assets/modules/__ui__.js:1-...</code> + runtime inflater/XML。 | C++ global <code>ui</code>（<code>quickjs_jni.cpp:8994</code>）+ JS <code>qjs-*.js</code> + <code>QuickJsHostBridge</code>。 | <code>node24/ui.js</code>（<code>require('ui')</code>）+ <code>NodeUiBridgeService</code>/<code>:nodeui</code>（<code>NodeUiBridgeService.java:30-40</code>）。 |
| <code>floaty</code> / 浮窗 | <code>assets/modules/__floaty__.js:5-15</code> + runtime floaty。 | C++ <code>global.floaty</code>（<code>quickjs_jni.cpp:8607-8608</code>）+ native floaty 函数（2207 起）。 | <code>node24/modules/floating_window.js</code>（<code>require('floating_window')</code>）；进程内 <code>Node24LocalOverlay</code> 优先，失败回主进程 <code>NodeUiBridgeService</code>（<code>HANDOFF_AI.md:245-249</code>）。 |
| <code>engines</code> | <code>assets/modules/__engines__.js:1-18</code> + runtime engines。 | C++ <code>_engines</code>（<code>quickjs_jni.cpp:7332-7351</code>）+ <code>QuickJsHostBridge</code> 跨引擎。 | <code>node24/modules/legacy-runtime.js:636-638</code> 设置 <code>globalThis.engines/$engines</code>；实际执行经 <code>Node24Pool</code>/<code>NodeEngineBridgeService</code>。 |
| <code>plugins.load</code> | <code>assets/modules/__plugins__.js:79-103</code>；缺 MLKit/OCR 插件时有内置回退。 | <code>assets/modules/qjs-plugins.js:54-72</code> + 内置 MLKit/ppocr 回退（74 行起）；<code>QuickJsApiExtensions.loadPlugin</code>。 | <code>require('plugins')</code> 在 <code>node24/modules/plugins.js:36-...</code>；host <code>kind:"plugins"</code> / <code>Node24PluginLoader</code>（<code>Node24PluginLoader.java:22-84</code>）。 |

> 表中 QuickJS 行号是 <code>quickjs_jni.cpp</code> 的行；Node 的 <code>...</code> 表示该 API 的实现跨多个 block。不要只看“有同名函数”就认为三引擎行为等价；不同引擎的实际执行后端和限制不同。

### 3.4 三引擎资产位置

- Rhino：<code>modules/engine/src/main/assets/init.js</code> + <code>modules/engine/src/main/assets/modules/*.js</code>（<code>__*.js</code> 是 API，<code>common-*.js</code> 是共享模块）。
- QuickJS：<code>modules/engine/src/main/assets/modules/qjs-*.js</code>、<code>common-*.js</code>；执行器是 <code>modules/engine/src/main/cpp/quickjs_jni.cpp</code> + <code>QuickJsHostBridge.java</code>。
- Node：<code>modules/engine/src/main/assets/node24/prelude.js</code>、<code>ui.js</code>、<code>modules/*.js</code>、<code>tools/node</code>、<code>tools/npm.zip</code>；执行器是 <code>Node24Service.java</code>/<code>Node24HostBridge.java</code>；native libnode 资产在 <code>modules/engine/src/main/assets/source-engine-upgrade/node24/</code>。

---

## 4. MCP 层

### 4.1 定义与分发

- HTTP 服务：<code>McpService.kt:23-127</code> 创建 <code>McpHttpServer</code>，默认端口 8765..8795 顺延，绑定 0.0.0.0；<code>McpHttpServer.kt:30-78</code> 是无状态 Streamable HTTP（一个 POST 一个 JSON 响应）。
- 工具定义：<code>McpTools.listTools</code>（<code>McpTools.kt:197-284</code>）手工添加主要工具；<code>McpToolkitBridge.definitions</code> 追加工具进程工具（<code>McpToolkitBridge.kt:15-18</code>），再追加 9 个 Pro 别名（<code>McpTools.kt:273-279</code>）。
- 工具目录与顺序校验：<code>McpToolCatalog.kt:5-129</code>（顺序/重名/别名断言）；<code>McpTools.kt:280-282</code> 对 definitions 与 <code>McpToolCatalog.names</code> 做一致性检查。
- 分类：<code>McpToolCategories.kt:12-23</code> 定义 status/script/workspace/engine/device/terminal/overlay/asset/toolkit/reverse；分类映射在 27-61；工具名上的 <code>category/categoryTitle</code> 由 <code>McpTools.kt:1111-1117</code> 写入。
- 参数 schema：<code>McpToolSchema.kt</code>；通用 <code>objSchema</code> 在 <code>McpTools.kt:1119-1120</code>；工具进程 schema 在 <code>assets/mcp/toolkit-apk-schemas.json</code>。

分发顺序（<code>McpTools.call:286-465</code>）：

1. <code>reverse_guide/reverse_plan</code> → <code>ToolkitReverseGuide</code>。
2. <code>name in McpToolkitCatalog.names</code> → <code>McpToolkitBridge.call</code> → <code>:tools</code> 的 <code>McpToolkitProvider</code>。
3. UI 自动化在 <code>McpAutomationTransport.supports</code> 内 → 本进程 local，或 <code>:script</code> 的 <code>McpAutomationProvider</code>。
4. 其余按 <code>when(name)</code> 走：状态/脚本/工作区/引擎/文档走 <code>McpTools</code> 本身 + <code>McpApiDocs</code>/<code>McpEngineApi</code>；应用/设备/UI/截图走 <code>McpAppTools</code>、<code>McpDeviceTools</code>、<code>McpUiActions</code>、<code>McpUiSelector</code>、<code>McpCoordinateTools</code>、<code>McpFloatingTools</code>；终端走 <code>McpTerminalJobs</code> + <code>McpAndroidTerminal</code> + <code>McpBuildTools</code>；应用 trace 走 <code>McpAppTraceTools</code>；逆向/文件/APK 走 <code>ToolkitReverseTools</code>、<code>McpToolkit</code> 及其 <code>Toolkit*</code> 实现。

### 4.1.1 分类到实现文件

| 分类 | 主要分发/实现文件 |
|---|---|
| status | <code>McpTools</code> 的 <code>get_status</code>/<code>read_apk_logs</code>/<code>get_mcp_log</code> + <code>McpRootStability</code> |
| script | <code>McpTools</code> 的 run/execution 方法 + <code>McpRunInput</code> + <code>McpSyntaxChecker</code>/<code>McpNodeSyntax</code> |
| workspace | <code>McpWorkspaceStore</code>（工作区） + <code>McpTools</code> 的 workspace_* 分支 |
| engine | <code>McpEngineApi</code>、<code>McpEngines</code>、<code>McpApiDocs</code> |
| device | <code>McpAppTools</code>、<code>McpDeviceTools</code>、<code>McpUiActions</code>、<code>McpUiSelector</code>、<code>McpCoordinateTools</code>、<code>McpFloatingTools</code>、<code>McpAppTraceTools</code> |
| terminal | <code>McpTerminalJobs</code>、<code>McpAndroidTerminal</code>、<code>McpBuildTools</code> |
| overlay | <code>McpTools.listOverlays/closeOverlays</code> + <code>OverlayStateReader</code> |
| asset | <code>McpApiDocs</code>、<code>SampleRepository</code>（内置示例读取） |
| toolkit | <code>McpToolkitBridge</code> → <code>McpToolkit</code> → <code>McpApkToolkit</code>/<code>McpFileToolkit</code>/<code>McpZipToolkit</code>/<code>Toolkit*</code> |
| reverse | <code>ToolkitReverseTools</code> + <code>ToolkitReverseGuide</code> + 各 <code>Toolkit*</code> 实现 |

### 4.2 工具数怎么算（canonical + aliases）

按当前源码的列表长度（<code>McpToolCatalog.kt</code> 与 <code>McpToolkitCatalog.kt</code>）：

- <code>McpToolCatalog.canonicalNames</code> = 73 个基础名 + <code>McpToolkitCatalog.names</code>（58 + 23 + 43 + 7 = 131）= 204 canonical。
  - 这 131 的构成：<code>McpToolkitCatalog.kt:5-63</code> 的 58 个、65-87 的 23 个、<code>ToolkitReverseTools.names</code> 的 43 个、88-100 的 7 个追加项。
- <code>McpToolCatalog.aliases</code> 是 9 个出现在 tools/list 的 Pro 兼容名（<code>getUiLayout</code> 等，<code>McpToolCatalog.kt:90-100</code>）。
- 因此 <code>McpToolCatalog.names</code> = 204 + 9 = 213 个对外名称；<code>get_status</code> 的 <code>toolCount=213</code>、<code>canonicalToolCount=204</code> 直接回报这个值（<code>McpTools.kt:312-313</code>）。
- 还有两类“只接受、不展示”的别名：
  - <code>McpToolCatalog.dispatchAliases</code>：每个 canonical 自动生成 camelCase，共 204 个，tools/call 可接受但 tools/list 不列出（<code>McpToolCatalog.kt:102-110</code>）。
  - <code>McpToolkitCatalog.aliases</code>：<code>names.take(58)</code> 去掉 <code>toolkit_status</code> 后的 57 个 <code>mt_*</code>，只做派发兼容（<code>McpToolkitCatalog.kt:102</code>）。
- 所以“工具数”分三层：canonical（204）、tools/list 公开名称（213）、实际可派发名称（204 canonical + 204 camelCase + 9 Pro + 57 mt_，去重后更大）。新增工具时不要只改一个列表。

### 4.3 后台 job 与工作区

- 脚本运行：<code>McpTools.runScript</code> 把运行记录放进进程内 <code>runs</code> 表；<code>list_executions/get_execution/wait_execution/stop_script</code> 使用 <code>RunRecord</code>（<code>McpTools.kt:58-84,344-350</code>）。
- 终端：<code>McpTerminalJobs</code> 是异步 pipe 任务；默认最多 3 个运行、保留 16 个、256KiB 输出环（<code>McpTerminalJobs.kt:10-16,68-94</code>）；<code>terminal_read</code> 按 offset/nextOffset 读，<code>stop</code> 终止进程组。
- APK/逆向长任务：<code>ToolkitJobs</code> 用文件分层保存：<code>&lt;jobId&gt;.json</code> 元数据、<code>&lt;jobId&gt;.result.json</code> 输出、<code>jobs.index.json</code> 轻量索引；错误码区分 <code>JOB_NOT_FOUND/JOB_EVICTED/SERVICE_RESTARTED/JOB_QUEUE_FULL</code>（<code>ToolkitJobs.kt:9-25</code>；start/list/status/result/cancel 在 94 行起）。对应工具是 <code>apk_job_*</code>，结果用 <code>apk_job_result</code> 逐页读。
- 脚本工作区：<code>McpWorkspaceStore</code> 在应用私有目录做 copy-on-write 快照（base/work），编辑不会直接改真实脚本；<code>workspace_write</code> 只写 work，<code>workspace_request_apply</code> 才校验并备份后落真实文件（<code>McpWorkspaceStore.kt:12-15,37-108,417-430</code>）。<code>workspace_cleanup</code> 默认 dryRun，并保护 applied/pending/saved/被引用/未知 owner（<code>McpWorkspaceStore.kt:207-355</code>）。
- APK 工作区/编辑会话是另一套：<code>ToolkitWorkspaceInventory</code>、<code>ToolkitApkSessions</code>、<code>ToolkitApkTargets</code> 管配额、快照、编辑会话和导出；与脚本工作区不要混用。

---

## 5. UI 层

### 5.1 Flutter 与原生

- **Flutter 唯一真源**：<code>flutter-ui/</code>（<code>lib/main.dart</code>、<code>bridge.dart</code>、各页面）。它编译成 AOT <code>libapp.so</code> + <code>flutter_assets</code>。
- Android 宿主：<code>apps/app/src/flutterpoc/java/com/jdkshen/aijspro/flutterpoc/</code>。
  - <code>GlassFlutterActivity.java:22-31</code>：FlutterActivity 宿主，<code>autojs.local.bridge</code> 一条 JSON 通道。
  - <code>PocBridge.java:44-53,94-100</code>：Flutter ↔ Android 桥；<code>PocRuntime</code> 负责运行/任务路由；日志/设置/终端/工作区等仍跳原生 Activity。
  - <code>PocRuntime.java:43-55</code>：普通脚本默认走 <code>:script</code>，native 项目走 <code>:native</code>，auto 模式有独立门控。
- **原生页面**：<code>apps/app/src/main/java/com/jdkshen/aijspro/ui/**</code>（编辑器、日志、终端、设置、悬浮菜单等）仍被 Flutter 桥作为原生兜底/嵌入入口；<code>PocBridge</code> 的常量明确了这些 Activity。
- **旧 Miuix 源集**：<code>apps/app/src/miuix</code> 仍存在，但 <code>apps/app/build.gradle:290-304</code> 把它的 java/resources/assets 全部置空；非 UI 逻辑已逐步 git mv 到 <code>src/main</code>。不要把“miuixCompat”变体名误认为 Miuix UI 还在使用。
- **构建接线**：只有 <code>-PflutterPoc=true</code> 时，debug/release 才把 <code>src/flutterpoc</code>、Flutter payload 的 <code>flutter_assets</code>/<code>jniLibs</code> 和 embedding jar 接进来（<code>apps/app/build.gradle:23-57,305-328</code>）。Flutter payload 从 <code>flutter-payload-base/</code> 的锁定版本构建，<code>tools/flutter-poc/build-aot.ps1</code> 生成/校验，Gradle <code>verifyFlutterPayload</code> 校验 engine/embedding/AOT 哈希（<code>apps/app/build.gradle:430-459</code>；<code>flutter-payload-base/README.md:1-22</code>）。
- 默认不带 <code>-PflutterPoc</code> 的构建没有 Flutter UI；当前产品发布使用带 Flutter payload 的构建。具体发布命令由 Lead/工具链决定，本文不把它写成单一路径。

### 5.2 独立 APK（apps/inrt）

- <code>:inrt</code> 是打包运行时壳：applicationId <code>com.jdkshen.aijspro.inrt</code>，内置三个引擎，<code>assets/project/</code> 是占位工程（<code>apps/inrt/build.gradle:4-20</code>；<code>apps/inrt/src/main/assets/project/project.json:1-13</code>）。
- 它的 release arm64 unsigned APK 会被 <code>:inrt:buildApkPlugin</code> 复制为 <code>plugins/apkbuilder-plugin/src/main/assets/template.apk</code>，同时生成/校验 <code>template-manifest.json</code>（<code>apps/inrt/build.gradle:74-103</code>）。
- 用户脚本最终“独立 APK”不是直接改 <code>apps/inrt</code>：主 App 的 <code>ApkBuilder</code> 用模板 + 用户 project/assets 替换，重签并验签；打包插件负责把全量模板落地（<code>plugins/apkbuilder-plugin/build.gradle:1-108</code>；<code>apps/app/src/main/java/com/jdkshen/aijspro/build/ApkBuilderPluginHelper.java:27-48</code>）。
- <code>:inrt</code> 自己的 manifest 也把脚本执行放到 <code>:script</code>、Node 池放在 <code>:node24_1..3</code>（<code>apps/inrt/src/main/AndroidManifest.xml:49-118</code>），所以“独立 APK”内部仍是同一套进程/引擎模型。

---

## 6. 资产与打包流

### 6.1 App assets 分类

按当前 <code>apps/app/src/main/assets</code> 目录（数量会变）：

| 目录 | 内容 | 当前规模（约） |
|---|---|---|
| <code>sample/</code> | 内置示例，按 Rhino 引擎/、QuickJS 引擎/、Node 引擎/ 三棵组织 | 429 文件，13.5MB |
| <code>docs/</code> | 离线 API 文档 HTML/JS/CSS（由 <code>tools/build-api-reference.py</code> 生成） | 78 文件，4.9MB |
| <code>editor/</code> | Ace 编辑器/高亮/编辑器资源 | 28 文件，10.1MB |
| <code>models/ppocrv6/</code> | 随包 PP-OCRv6 tiny 模型（det/rec + keys） | 5 文件，14.9MB |
| <code>mcp/</code> | AIjs-MCP-Guide.md、Playbook、逆向指南、toolkit-apk-schemas.json 等 | 5 文件，约 0.2MB |
| <code>indices/</code> | 类/API 索引 all.json、all_android_classes.json | 2 文件，约 0.5MB |
| <code>fonts/</code> | NotoSansCJKsc 子集 | 2 文件，约 0.6MB |
| <code>js/</code> | 少量运行期 JS 资源 | 2 文件，约 0.1MB |

根 assets 还有：<code>excluded-zip-files.json</code>（瘦身模板需从宿主 APK 补齐的 lib 清单）、<code>sample-manifest.json</code>、<code>template.apk</code>（瘦身模板，2026-11-08 实测 19,814,709 B ≈ 19.8MB，SHA-256 <code>3AC69531…</code>）。

引擎/Node 资产不在 app assets，而在 <code>modules/engine/src/main/assets</code>：

- <code>init.js</code>、<code>modules/__*.js</code> 与 <code>common-*.js</code>（Rhino/QuickJS 共享）；
- <code>node24/prelude.js</code>、<code>ui.js</code>、<code>node24/modules/*.js</code>、<code>node24/tools/node</code>、<code>node24/tools/npm.zip</code>；
- <code>source-engine-upgrade/node24/arm64-v8a/</code> 下的 libnode.so/libajsnode24bridge.so/runtime.json 等；
- <code>models/ppocrv6</code> 在 app assets（见上表），不是 engine assets。

### 6.2 template.apk 与独立 APK 的生成链

~~~text
apps/inrt (:inrt) release arm64 unsigned APK
        │ :inrt:buildApkPlugin
        ▼
plugins/apkbuilder-plugin/src/main/assets/template.apk   （全量模板，2026-11-08 实测 88,448,312 B ≈ 88.4MB）
        │ 安装打包插件；宿主 ApkBuilderPluginHelper.ensureTemplate 反射落地
        ▼
主 App 打包页：ApkBuilder 用 template + 用户 project/assets 替换、重签/验签
        ▼
用户“独立 APK”

另一条（主 APK 自带瘦身模板）：
apps/inrt packageRelease
        │ tools/build-slim-template.ps1（剔除 lib/，生成 excluded 清单）
        ▼
apps/app/src/main/assets/template.apk（瘦身，2026-11-08 实测 19,814,709 B ≈ 19.8MB）
        + excluded-zip-files.json
        │ 宿主缺少插件时，从自己的 APK 补齐 excluded libs
        ▼
主 App 内置打包 fallback
~~~

- 插件模板由 <code>:inrt:buildApkPlugin</code> 生成（<code>apps/inrt/build.gradle:74-103</code>），插件构建前会执行 <code>verifyTemplateApk</code>（<code>plugins/apkbuilder-plugin/build.gradle:71-108</code>）。
- 主 APK 瘦身模板由 <code>tools/build-slim-template.ps1</code> 生成，Gradle 的 <code>verifySlimPackagingTemplate</code> 检查无 lib/、体积 6-20MB、必需 entry、excluded 三数组（<code>apps/app/build.gradle:1176-1262</code>）。
- 两个模板都是 tracked 二进制（有意入库）；gradle-wrapper.jar、flutter_embedding_*.jar、dx.jar、npm.zip 同理，不要按“构建产物”清理。

### 6.3 tools/*.ps1 一行一个

| 脚本 | 作用 |
|---|---|
| <code>tools/jdk17.ps1</code> | 选择 JDK 17+ 并导出 JAVA_HOME/PATH（dot-source）。 |
| <code>tools/build-node24-bridge.ps1</code> | 用固定 libnode.so 构建/剥离 Node24 JNI 回调桥，可只更新 asset+hash。 |
| <code>tools/build-project-res.ps1</code> | 用 PC aapt2 把工程 res 编成独立资源包（设备无 arm64 aapt2）。 |
| <code>tools/build-slim-template.ps1</code> | 从 :inrt release 生成主 APK 瘦身模板与 excluded-zip-files.json。 |
| <code>tools/compare-engine-api.ps1</code> | 通过 MCP/curl 对比三个引擎的 API 清单。 |
| <code>tools/dev-update.ps1</code> | 自动升版本→构建 Miuix compat debug→发布到自建更新源；会真的发布。 |
| <code>tools/measure-ui-performance.ps1</code> | ADB 连续滑动后用 dumpsys gfxinfo 采样 UI 卡顿并落报告。 |
| <code>tools/node-sample-log-verdict.ps1</code> | 从 Node 示例日志判定脚本错误/语义失败（排除 Android 服务告警）。 |
| <code>tools/observe-filelist.ps1</code> | 通过 run-as 观察文件列表排序/前台状态等 prefs 变化。 |
| <code>tools/pro-device-run.ps1</code> | 在已安装的 Auto.js Pro 家族 App 中推送脚本、打开编辑器运行并取结果。 |
| <code>tools/probe-engine-api.ps1</code> | 通过 MCP/curl 探测指定引擎/模块的 API 形状。 |
| <code>tools/publish.ps1</code> | 带前置校验和发布后校验的 LAN 发布入口（支持 -DryRun/-SkipBuild）。 |
| <code>tools/render-update-history.ps1</code> | 把本地更新历史 JSON 渲染成独立 HTML 页。 |
| <code>tools/serve-updates.ps1</code> | 复制 APK、写 update.json（SHA-256/size）并可选起 HTTP 服务。 |
| <code>tools/strip-opencv-aar.ps1</code> | 从 OpenCV AAR 的 .so 中去掉 DWARF/.symtab，减小 debug 包。 |
| <code>tools/test-ace-clipboard-jvm.ps1</code> | 离线编译/运行编辑器剪贴板策略 JVM 测试，不启 Gradle/设备。 |
| <code>tools/test-engine-flavors-device.ps1</code> | 在单台设备上跑 QuickJS/Rhino flavor 矩阵。 |
| <code>tools/test-miuix.ps1</code> | 选 JDK17、按需 subst 临时盘符后跑 Miuix 变体单测。 |
| <code>tools/test-node-samples-device.ps1</code> | 把 Node 示例按白名单推到设备运行并汇总 summary.json/md。 |
| <code>tools/test-pro-rpc.ps1</code> | 用 javac 编译/运行 Pro ws-rpc 协议核心自测（无 Gradle/设备）。 |
| <code>tools/verify-16kb-alignment.ps1</code> | APK/.so 的 16KB 页对齐守卫（PT_LOAD p_align/RELRO）。 |
| <code>tools/verify-image-samples-device.ps1</code> | 跑 QuickJS 图像 API 回归示例；无设备时 SKIP。 |
| <code>tools/verify-native-strip.ps1</code> | 检查打包 .so 无 .symtab、R8 API 数据库未被误带。 |

> 还有非 .ps1 的构建/审计工具（<code>tools/*.py</code>、<code>tools/flutter-poc/build-aot.ps1</code>、<code>tools/apk-components</code> 等）；本表只列根 <code>tools/*.ps1</code>。

---

## 7. “我要加 X 该动哪里”速查表

### 7.1 新增一个 MCP 工具

顺序：

1. **加 canonical 名**：普通工具加到 <code>McpToolCatalog.canonicalNames</code>（<code>McpToolCatalog.kt:5-83</code>）；工具进程/APK 工具加到 <code>McpToolkitCatalog.names</code>（<code>McpToolkitCatalog.kt:5-101</code>）；逆向工具加到 <code>ToolkitReverseTools.descriptions</code>（<code>ToolkitReverseTools.kt:7-51</code>），名字由 keys 自动生成。
2. **加分类**：<code>McpToolCategories.kt:27-60</code> 的 <code>toolToCategory</code>。
3. **加 schema/定义**：普通工具在 <code>McpTools.listTools</code>（197-283）加 <code>tool(...)</code>；复杂 schema 放 <code>McpToolSchema.kt</code>；工具进程 schema 走 <code>assets/mcp/toolkit-apk-schemas.json</code>。
4. **加分发**：<code>McpTools.call</code>（286-465）的 when(name)；工具进程工具只需进 <code>McpToolkitCatalog.names</code>（291-293 自动走 bridge）。
5. **加实现**：按类别放 <code>mcp/*Tools.kt</code> 或 <code>Toolkit*.kt</code>；不要在主线程做长任务，长任务用 <code>ToolkitJobs</code>/<code>McpTerminalJobs</code>。
6. **加测试**：<code>apps/app/src/test/java/com/jdkshen/aijspro/mcp/*Test.kt</code>；目录一致性/别名由 <code>McpToolCatalog</code> 的 init 断言保护。
7. **加文档**：<code>assets/mcp/AIjs-MCP-Guide.md</code>/<code>AIjs-MCP-Playbook.md</code>；如果改 API 文档索引，跑 <code>tools/build-api-reference.py</code> 并提交生成资产（<code>apps/app/build.gradle:909-916</code> 有 --check 硬门）。

### 7.2 新增一个脚本 API（三引擎）

先决定 API 属于全局、模块还是 Java 对象；然后每一路都要有：

- **Rhino**
  1. JS 实现/包装：<code>modules/engine/src/main/assets/modules/__x__.js</code> 或复用 <code>common-*.js</code>。
  2. 注册到 <code>init.js</code> 的 modules 数组（<code>init.js:112-121</code>）或直接挂 global。
  3. 需要 Java API 时放 <code>modules/engine/src/main/java/com/stardust/autojs/runtime/api/</code>。
  4. 加示例/回归（见 7.3）。
- **QuickJS**
  1. 原生 global：<code>modules/engine/src/main/cpp/quickjs_jni.cpp</code> 的 bootstrap（参考 global <code>$ocr</code> 6509、<code>http</code> 6995、<code>engines</code> 7332）。
  2. Java 桥：<code>QuickJsHostBridge.java</code>（JNI 回调/参数转换）。
  3. 需要 JS 层兼容/包装时加 <code>modules/engine/src/main/assets/modules/qjs-*.js</code>，并确认它在 <code>QuickJsHostBridge.modernModulesScript</code> 的 assets 列表（334-348）。
  4. 修改 C++ 后必须先跑 <code>modules/engine/src/main/cpp/build-quickjs.ps1</code>；否则 <code>verifyQuickJsNativeLibrariesFresh</code> 会失败。
- **Node24**
  1. JS 模块：<code>modules/engine/src/main/assets/node24/modules/x.js</code>，用 <code>__ajsRegisterModule</code> 注册；或改 <code>prelude.js</code>/<code>ui.js</code>。
  2. 加入加载顺序：<code>Node24Prelude.MODULE_ASSETS</code>（<code>Node24Prelude.java:44-58</code>），否则不会自动加载。
  3. Host 能力：<code>Node24HostBridge.java</code> 加 kind/action；必要时用 <code>Node24Service</code> 的池/取消机制。
  4. 可能需要更新 <code>assets/node24/modules/*.md</code> 和 Node API 文档。
- **统一约束**：三个引擎的同名 API 不自动等价；返回值形状/异常/异步语义需分别测试。共享示例可放 <code>samples/engine-common/</code>，由 <code>tools/sync-common-engine-samples.py</code> 同步到三棵 sample（<code>apps/app/build.gradle:886-907</code> 会校验）。

### 7.3 新增一个内置示例

1. 放到 <code>apps/app/src/main/assets/sample/&lt;Rhino 引擎|QuickJS 引擎|Node 引擎&gt;/&lt;分类&gt;/...</code>。
2. QuickJS 示例第一行必须是 <code>"quickjs";</code> 或 <code>// @engine quickjs</code>，否则 <code>verifyQuickJsSamplesMarked</code> 失败（<code>apps/app/build.gradle:918-943</code>）。
3. Node 示例只能放 <code>Node 引擎/</code>，其他目录禁止 Node 入口/node_modules（<code>apps/app/build.gradle:812-851</code>）。
4. 工程资源：<code>project.json</code> 的 <code>androidResources</code> + PC 上 <code>tools/build-project-res.ps1</code> 生成 <code>build/project-res.apk</code>；示例构建还会调用 <code>tools/build-bundled-project-res.py</code> 生成真实资源 id（<code>apps/app/build.gradle:757-798</code>）。
5. 更新根 assets 的 <code>sample-manifest.json</code>（示例索引），必要时跑 <code>BundledSampleSync</code>/相关回归。
6. 三引擎共同示例改 <code>samples/engine-common/</code> 后用 <code>python tools/sync-common-engine-samples.py</code>，不要手改三份。

### 7.4 改 UI

- **Flutter**：改 <code>flutter-ui/lib/**</code>（页面、<code>bridge.dart</code>、主题）；改完用 <code>tools/flutter-poc/build-aot.ps1</code> 生成 payload，再带 <code>-PflutterPoc=true</code> 构建；Gradle 会校验 runtime.json 哈希和 AOT（<code>apps/app/build.gradle:430-459</code>）。
- **Android 桥/原生兜底**：改 <code>PocBridge.java</code>/<code>PocRuntime.java</code>；如果新增跳转，补 <code>PocBridge</code> 的 Activity 常量/通道 handler。
- **纯原生页面**：改 <code>apps/app/src/main/java/com/jdkshen/aijspro/ui/**</code>；注意 Flutter 桥仍按 Activity 名跳转（<code>PocBridge.java:94-100</code>）。
- **不要重新引入 Miuix 作为主 UI**；<code>src/miuix</code> 已被排除（<code>apps/app/build.gradle:290-304</code>），构建变体名是历史遗留。
- 任何 Flutter payload/engine 升级必须成套替换 <code>flutter-payload-base/</code> 的 jar/so 和 runtime.json，不要只换其中一个（<code>flutter-payload-base/README.md:1-22</code>）。

### 7.5 改打包/模板

1. 改 <code>apps/inrt</code> 的运行壳 → 重新生成插件模板：<code>:inrt:buildApkPlugin</code>（会写 <code>plugins/apkbuilder-plugin/src/main/assets/template.apk</code> + manifest，<code>apps/inrt/build.gradle:74-103</code>）。
2. 同步主 APK 瘦身模板：<code>tools/build-slim-template.ps1</code>（生成 <code>apps/app/src/main/assets/template.apk</code> + <code>excluded-zip-files.json</code>）。
3. 改模板体积/条目策略：看 <code>plugins/apkbuilder-plugin/build.gradle:71-108</code> 的 <code>verifyTemplateApk</code> 和 <code>apps/app/build.gradle:1176-1262</code> 的 <code>verifySlimPackagingTemplate</code>。
4. 改最终独立 APK 行为：<code>apps/app/src/main/java/com/jdkshen/aijspro/autojs/build/ApkBuilder.java</code> + <code>ApkBuilderPluginHelper.java</code>；不要直接修改模板内的用户工程。
5. 签名/发布：环境变量 <code>AIJSPRO_*</code>；本地测试只能用显式 <code>-PflutterReleaseTestSigning=true</code>；发布用 <code>tools/publish.ps1</code>，不要裸跑 <code>dev-update.ps1</code> 做参数试验。

---

## 8. 不确定 / 需要验证的地方

1. **工具数**：204/213/57 等是按当前源码列表逐项数的；<code>McpToolkitCatalog.names</code> 和 <code>McpToolCatalog.canonicalNames</code> 会随版本变化，命令行工具的“当前工具数”应以 <code>get_status</code> 返回为准。
2. **Flutter 发布路径**：build.gradle 只在 <code>-PflutterPoc=true</code> 时接入 Flutter；默认构建不含 Flutter UI。当前发布脚本/流水线具体传了哪些参数，本文未运行构建确认，属于“按源码能力推断”。
3. **:nodeui 的首个启动者**：Node UI 可能由 <code>NodeUiBridgeService</code>（主进程）或 <code>:node24</code> 侧发起，取决于 UI 模式/时序；本表写的是职责和可达路径，不是唯一启动顺序。
4. **:native 的实际使用率**：<code>PocRuntime</code> 注释说明 native 项目走 <code>:native</code>，普通脚本走 <code>:script</code>，auto 模式另有门控；具体脚本的最终路由取决于 project.json/执行模式，未逐示例验证。
5. **aijs_shizuku_&lt;pid&gt; 的进程生命周期**：由 Shizuku 管理，manifest 里没有固定声明；不同 Shizuku/Android 版本可能表现不同，本文只按源码的 bindUserService/processNameSuffix 说明。
6. **模板体积**：文中 20.4MB/88.3MB 是当前工作区文件大小；每次重新构建可能变化，校验任务才是硬门。
7. **MCP 工具进程的重启/恢复**：<code>ToolkitJobs</code> 有 <code>SERVICE_RESTARTED</code> 等错误码，但实际系统在杀进程后的表现仍需按具体工具测试；本文描述的是设计意图。
8. **“UI 只有 Flutter”**：这是源码/构建约束与 HANDOFF 的结论；Flutter 桥仍会跳转原生 Activity（编辑/日志/终端/设置），两者不是矛盾，而是“主界面 Flutter + 原生功能页”。

## 9. 建议的 10 分钟阅读顺序

1. §0 + §2.1 进程表（先知道东西跑在哪）。
2. §3.1/§3.2（知道三个引擎谁执行什么）。
3. §4.1/§4.2（知道 MCP 工具从哪进、从哪出）。
4. §1.2 模块表 + §5.1 UI 边界。
5. 真正动手前再看 §7 的对应速查表，以及 <code>docs/HANDOFF_AI.md</code> 的构建/测试/发布章节。

关键入口速查：<code>apps/app/src/main/AndroidManifest.xml</code>（进程）、<code>apps/app/build.gradle</code>（依赖/构建门）、<code>modules/engine/src/main/java/com/stardust/autojs/AutoJs.java</code>（引擎注册）、<code>McpTools.kt</code>（MCP 定义/分发）、<code>PocRuntime.java</code>/<code>PocScriptHost.java</code>（脚本路由）、<code>Node24Pool.java</code>/<code>Node24Service.java</code>（Node 执行）、<code>apps/inrt/build.gradle</code> 与 <code>plugins/apkbuilder-plugin/build.gradle</code>（模板）。
