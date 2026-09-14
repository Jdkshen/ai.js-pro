# Auto.js Pro 第一代 API 兼容性与 V8 选型

整理日期：2026-09-14。对象：当前 AI.js Pro 项目。交付范围：分析与实施方案，未接入 V8，未据此修改引擎或发布更新包。

## 1. 结论：兼容这份 API 不要求上 V8

附件第 1 页明确说明这是第一代 API，对应 Rhino 默认引擎。该 PDF 共 335 页，是微信公众号整理的文档汇编，不能据此认为它覆盖当前 Pro 全部 API 或第二代 API。文内包含 Pro 9.2 OCR/插件功能及较早版本条目，应按具体条目的版本标记确定兼容目标。

这份 API 的主要兼容工作是 Android 宿主能力、Rhino 行为、语法和生命周期。V8 负责 JavaScript 执行，不自带 Auto.js 的无障碍、截图、悬浮窗、Java 互操作或插件系统。换引擎以后这些接口仍然要实现。V8 的 Isolate/Context、对象模板和回调接口只是接入宿主的基础设施。[V8 官方嵌入说明](https://v8.dev/docs/embed)

建议：保留 Rhino 承接要求原样运行的第一代脚本；继续补齐 QuickJS 的常用 API 和行为测试；V8 作为可选引擎独立推进。不要将“V8 接入完成”作为“Pro API 完全兼容”的验收条件。

| 实际目标 | 是否必须 V8 | 应做的工作 |
| --- | --- | --- |
| 运行附件中的第一代 API 脚本 | 否 | 补齐 API、Rhino 语义、XML/Java/线程/插件兼容 |
| 常用自动化脚本运行在 QuickJS | 否 | 补宿主桥和行为兼容；特殊语法转译或保留 Rhino |
| 增加 V8 执行选项、评估长时间 JS 计算性能 | 是，此目标本身选择 V8 | 独立 provider、JNI、调度、资源管理、性能对照 |
| 以后兼容 Node.js 脚本与 Node 内置模块 | 裸 V8 不够 | Node 运行时、事件循环、模块/IO/Buffer 等环境及 Android 宿主桥 |

Node 的嵌入方案包含 Node Environment 与 libuv 事件循环，并非只调用 V8 执行源码。兼容哪些 Pro 第二代 API，还需要对应文档与样例；本附件不能证明第二代接口清单。[Node 官方嵌入 API](https://nodejs.org/api/embedding.html)

## 2. PDF 中最容易误判的地方

- `$ui`、`$crypto`、`$ocr`、`$plugins`、`$work_manager` 出现在本份第一代文档中；`$` 前缀不能作为 Node/V8 的判断依据。
- `require()` / `module.exports` 是模块接口，宿主可以自己实现。第 180–181 页给的是文件模块示例，不代表具备 npm 或 Node 内置模块。
- 第 233 页明确描述计时器基于 Android Looper/Handler，主脚本阻塞会延迟回调；异步改造不能默默改变旧脚本行为。
- 第 111 页规定 EventEmitter 监听数量是硬上限；不能直接用 Node 的默认行为替代。
- 第 181–185 页 OCR 依赖插件，结果包含 bounds、rotation 等信息；只补一个返回字符串的 OCR 函数不算兼容。
- 第 185–190 页插件涉及 SDK、Java 对象、胶水代码与打包合并；装上 V8 不会自动解决插件兼容。

## 3. 36 个模块的定位与验收重点

页码使用 PDF 实际页号，与可见页脚一致。范围包含相邻章节交界页，便于定位。这是模块级工作清单，不是“全部函数已经支持”的声明。

| PDF 模块 | 页码 | 需要兼容/验收的行为 |
| --- | --- | --- |
| 2.1 自动化-控件操作 | 3–34 | 选择器链式条件、集合、查找超时、节点失效与实际无障碍行为 |
| 2.2 自动化- 坐标操作 | 34–46 | 坐标缩放、手势完成与失败、权限、取消 |
| 2.3app- 应用 | 46–55 | Intent、包名、Activity 启动、文件 URI |
| 2.4base64 | 55–55 | 编码、字符集、字节数组 |
| 2.5colors- 颜色 | 55–58 | 颜色整数符号、通道与阈值 |
| 2.6canvas- 画布 | 58–68 | Canvas/Paint 对象、绘制回调、释放 |
| 2.7console- 控制台 | 68–73 | 格式化、日志窗口、输入阻塞、退出 |
| 2.8crypto- 加解密与消息摘要 | 73–81 | 算法/模式、Key/IV、编码、异常 |
| 2.9debug- 调试工具 | 81–82 | 堆栈与 JVM HPROF；区分 JS 堆诊断 |
| 2.10device- 设备 | 82–89 | 属性、保活、屏幕操作与权限 |
| 2.11dialogs- 对话框 | 89–105 | 同步/异步、UI 线程、按钮回调与取消 |
| 2.12engines- 脚本引擎 | 105–110 | 执行参数、工作目录、脚本停止、引擎对象 |
| 2.13events- 事件与监听 | 110–117 | 回调所属线程、监听上限、退出事件顺序 |
| 2.14floaty- 悬浮窗 | 117–122 | 窗口布局、事件、触摸穿透、退出回收 |
| 2.15files- 文件系统 | 122–130 | 路径、编码、流、异常、共享存储权限 |
| 2.16globals- 全局变量与函数 | 130–136 | sleep/exit、剪贴板、同步语义与中断 |
| 2.17http-HTTP 网络请求 | 136–142 | 同步/异步、响应对象、流、超时、取消 |
| 2.18images- 图片处理 | 142–175 | 截图授权、ImageWrapper、图色算法、原生内存 |
| 2.19keys - 按键模拟 | 175–178 | 按键注入权限、设备差异 |
| 2.20media- 多媒体 | 178–180 | 播放器状态、资源与生命周期 |
| 2.21module- 模块 | 180–181 | CommonJS 路径解析、缓存、循环依赖、exports |
| 2.22ocr- 文字识别 | 181–185 | OCR 插件、结果字段/坐标/旋转，不等同仅能识字 |
| 2.23plugins- 插件 | 185–190 | SDK、类加载、胶水代码、runtime/scope、打包合并 |
| 2.24power_manager- 电源管理 | 190–191 | 电池优化查询/申请与系统限制 |
| 2.25sensors- 传感器 | 191–197 | 传感器类型、采样、事件线程、退出注销 |
| 2.26shell-Shell 命令 | 197–209 | root/非 root、返回码、编码、超时与终止 |
| 2.27storages- 本地存储 | 209–211 | 命名空间、JSON 类型、默认值、持久化 |
| 2.28settings- 设置 | 211–212 | 设置项语义、持久化、前台服务与停止开关 |
| 2.29threads- 多线程 | 212–221 | 共享闭包/变量、锁、原子变量、线程计时器、强停 |
| 2.30thirdPartyPlugins- 第三方插件 | 221–232 | 逐个插件 SDK/ABI/资源/打包检验；不保证全部兼容 |
| 2.31timers- 定时器 | 232–235 | Looper 调度、阻塞主脚本、清除、异常顺序 |
| 2.32work_manager- 定时与任务 | 235–243 | 持久任务、广播、后台启动、重启恢复、打包路径 |
| 2.33util- 工具 | 243–252 | 格式化与 Java 对象/数组/类型转换 |
| 2.34WebSocket | 252–256 | 连接/消息/关闭/失败、二进制、回调线程 |
| 2.35zip- 压缩与解压 | 256–264 | 密码、AES、进度、目录选项与解压行为 |
| 2.36Ui- 用户界面 | 264–330 | XML 语法、属性绑定、Activity、控件代理、自定义 View |

另有第 330–335 页“和 Java 交互”：LiveConnect、JavaBean 属性、importClass/importPackage/Packages、接口代理、JavaAdapter、继承及 super$ 调用。它是跨引擎兼容的重点，不只是一个工具模块。

## 4. 当前项目已有基础及实际缺口

本次对照了当前源码和已有迁移矩阵。已有测试记录只能说明当时测过相应场景，不能换算为这份 PDF 的兼容百分比。

| 现有基础 | 证据位置 | 对兼容工作的意义 |
| --- | --- | --- |
| 引擎注册/创建入口 | `modules/engine/src/main/java/com/stardust/autojs/engine/ScriptEngineManager.java`；`AutoJs.java` | 可增加 provider；不是从零写执行系统 |
| 脚本引擎指令与偏好 | `modules/engine/src/main/java/com/stardust/autojs/script/JavaScriptSource.java` | 当前识别 quickjs/rhino；不能直接配置 v8 后声称可运行 |
| Android 宿主 API | `modules/engine/src/main/java/com/stardust/autojs/runtime/ScriptRuntime.java` | 许多能力可复用，但存在 Rhino 类型耦合 |
| QuickJS Java 宿主桥 | `modules/engine/src/main/java/com/stardust/autojs/engine/QuickJsHostBridge.java` | 已有文件、手势、传感器、UI/图像等实现；应拆成共享服务 |
| QuickJS JS 包装与 JNI | `modules/engine/src/main/cpp/quickjs_jni.cpp` | JS 外观/代理/回调大量在 bootstrap 中；加 Java 方法不等于脚本已有接口 |
| Pro 风格别名 | `modules/engine/src/main/assets/init.js`；QuickJS bootstrap | Rhino 初始化已有 `$ui` 等别名；不同引擎必须分别核查注入路径 |
| 已有 QuickJS 场景记录 | [迁移矩阵](QUICKJS_MIGRATION_MATRIX.md) | 可复用案例，不替代本份 PDF 的逐函数对照与复测 |

必须优先核查以下差异：

1. **JavaAdapter 与动态继承。** PDF 第 333–335 页支持普通类/抽象类继承、多接口、构造参数和 super$。当前 QuickJS bootstrap 明确采用宿主预置子类，并对不支持的继承抛错；不能因存在 JavaAdapter 名称就算完整支持。V8 同样需要另外实现 Java 代理/动态类生成。
2. **XML 布局语法。** PDF 第 265 页使用 `$ui.layout(<vertical>...</vertical>)`。这种 XML 字面量不是标准 ECMAScript。QuickJS/V8 都需要解析/转译，或让原脚本使用 Rhino。换成 XML 字符串虽然便于移植，但属于改脚本，不能称为原样兼容。
3. **多线程闭包共享。** 第 212–221 页示例在子线程访问主脚本变量，并使用锁/原子变量。V8 在同一个 Isolate 上并行执行需要明确限制；Locker 的访问协调不等于 Rhino 式并行共享语义。独立 Isolate + 消息传递又不能自然保留同一个 JS 闭包。必须标明差异或保留 Rhino，不能把异步队列包装成同等多线程。
4. **插件 runtime/scope。** PDF 第 187–188 页胶水访问 plugin.runtime/topLevelScope。需要逐 SDK/插件实测其实际依赖；通用 Java 反射桥不能证明插件全部可用。
5. **后台任务与 UI 生命周期。** `$work_manager` 需要进程外持久任务/触发能力；setTimeout 无法代替。UI/悬浮窗退出时的回调、窗口、截图、传感器及原生内存必须跟随脚本销毁。

## 5. 建议的框架分层

```text
脚本入口（源码 + engine + API profile + 工作目录）
                 ↓
语法/模块层（模式标记、XML 转译、CommonJS、源码映射）
                 ↓
EngineProvider（Rhino / QuickJS / 将来的 V8）
                 ↓
RuntimeEndpoint（回调、对象引用、异常、中断、值转换）
                 ↓
共享 HostServices（files/http/images/automator/Java/plugins…）
                 ↓
Android 平台服务 + UI 主线程 + 脚本资源管理
```

这是建议结构，尚未整体实现。上层公开 Pro 兼容接口；引擎适配器负责将 JS 值、回调和对象引用转换给共享宿主服务。不要复制整份 QuickJsHostBridge 再改名为 V8HostBridge。

给每次脚本执行分配 ownerId，统一登记窗口、图像、回调、流、传感器、子线程及任务。停止先拒绝新调用，再取消任务、结束执行和释放引用，确认执行线程结束后销毁引擎。持久定时任务要按自身语义保存，不能随普通临时计时器一律删除。

兼容档位建议显式表达：`pro-first-gen` 为附件行为目标，`modern` 为现代 JS/异步设计；Node 环境另设目标。名称是未来设计，不是当前已支持配置。档位有差异时给出具体迁移说明，避免静默降级。

## 6. 怎么实施

### 第一步：先建逐函数清单

从上面 36 个模块开始，把每个 API 的名称/别名、签名、可选参数、默认值、返回类型、异常、线程、权限、释放行为、版本及 PDF 页码记录下来。状态统一为：未核查、已定位实现、部分兼容、行为验收通过、不支持。不要用“函数存在”代替验收。

逐函数清单已建立：[第一代 API 逐函数对照](第一代API逐函数对照.md) + [`pro-api-compatibility.csv`](pro-api-compatibility.csv)（938 条，含 PDF 页码、版本标记、签名、参数表，以及 Rhino / QuickJS 各自的静态对照状态与证据路径）。生成脚本：`tools/pro-api/rebuild.py`。本文下面的分层与验收顺序仍然适用于从清单里选出的条目。

对照表用的静态状态与本文的验收状态不是一回事：静态只回答“源码里有没有这个名字”，不要把它当成“已验收”。

该 CSV 的实际列（比下面最初的草案更细，多了 `kind` / `section` / 两条证据路径）：

```csv
module_num,module,api,kind,pdf_page,version,section,rhino,quickjs,rhino_evidence,quickjs_evidence,signature,note
```

逐条阅读时核对排版错误：例如第 2–3 页颜色算法默认值示例存在不一致，不能只按 PDF 文本机械生成测试。遇到矛盾记录下来，用明确版本的 Pro 真机行为或官方条目复核。

### 第二步：先做常用自动化接口

优先 globals/files/storages/console/app/device、选择器/坐标操作、timers/events/engines、http/images。复用现有案例，补足默认参数、边界值、错误、停止和权限取消。通用公共服务从引擎桥中逐组拆出，每完成一组立即回归。

### 第三步：完成 UI 与悬浮窗契约

统一 ui/$ui、floaty、控件 ID、属性绑定、点击/绘制回调、主线程调度和 Activity 重建。若目标要求原样脚本，先落实 XML 转译与行号映射，再判断 JavaAdapter 的支持边界。继续验证关闭日志后布局、主题切换、悬浮窗关闭与资源回收。

### 第四步：补 Pro 增量能力

按用户实际脚本需求推进 crypto/zip/WebSocket、settings/power_manager/work_manager、OCR 和插件。OCR 要对比结果对象及坐标；插件要准备有版本的 SDK 样例与 APK 打包样例；后台任务要验证重启和进程退出后的真实触发。

### 第五步：并行目标中再接 V8

遵循 [V8 接入框架方案](V8接入_框架瓶颈与迁移方案.md)：独立可选模块、最小 JS 执行、共享宿主桥、回调/停止/资源、UI/Java、打包与调试分阶段接入。第一阶段使用源码；QuickJS 字节码不能直接交给 V8 执行。V8 的版本、ABI、ICU/snapshot 与 Android 原生库构建单独管理。

以上“并行目标”指工程路线可分别推进，不要求当前启用多个代理执行。

## 7. 怎么使用和验收

现阶段迁移附件脚本时，优先显式选 Rhino；使用构建中包含 Rhino 的版本，并确认工程/脚本配置没有覆盖引擎。以下是当前项目识别的指令，实际接口以对应引擎验收结果为准：

```javascript
// @engine rhino
log("第一代脚本兼容验证");
```

移植 QuickJS 的普通源码可显式选：

```javascript
// @engine quickjs
log("QuickJS 接口验证");
```

含 UI 模式时可先保留 `"ui";` 再写引擎指令。XML 字面量、JavaAdapter、插件和共享闭包线程必须专门核查；不能只修改引擎指令就视为迁移完成。当前尚无已接入的 V8 指令。

验收应使用同一批小脚本，在明确版本的 Pro 与本项目各目标引擎上对照：输出、类型、异常、事件顺序、超时、中断及资源回收。保存 Pro 版本、Android 版本、设备、项目版本和证据。没有 Pro 对照设备的条目保留“未完成对照”，不要标记完全兼容。

必须有这些实机场景：

- Java 数组/重载/Bean、单/多方法接口、JavaAdapter 继承与 super$。
- XML 布局和动态属性、UI 线程回调、关闭日志、旋转/主题切换、脚本停止后窗口消失。
- 共享变量线程、锁/原子变量、阻塞时计时器、退出事件与强制停止。
- 截图授权接受/拒绝、原生图像释放、OCR 结果与坐标。
- 插件加载、缺失插件异常、独立安装与打包合并。
- 持久任务在重启/应用进程退出后的触发；文件/网络失败与取消。

## 8. 怎么优化

先测量纯 JS 运算、JNI/Java 调用、图像复制、UI 主线程、冷启动和内存分别花多少时间。V8 可能改善部分 JS 计算，但不应承诺提升所有脚本，也不会自动解决 UI 掉帧或 JNI 崩溃。

共享宿主服务按批次传参，减少循环中逐属性跨桥；大图像/帧尽量保留原生句柄并明确所有权；UI 更新合并提交，耗时网络/图像操作使用后台服务，回调回到引擎指定执行线程。兼容模式下保留同步返回的行为，异步优化通过独立 API 或明确档位提供。

对引擎升级同时记录 APK 大小、首次初始化、常驻内存、GC、连续运行稳定性与停止延迟。先完成真实脚本的兼容与稳定性验收，再决定是否将 V8 设为默认。

## 9. 依据与范围

- [附件：Auto.js Pro 官方 API 文档](C:/Users/18101/Downloads/Auto.js%20Pro官方API文档.pdf)：335 页；第一代范围见第 1 页；模块与语义页码见本文。
- [V8 官方嵌入说明](https://v8.dev/docs/embed)：引擎、Context/Isolate 与宿主绑定。
- [Node 官方嵌入 API](https://nodejs.org/api/embedding.html)：Node Environment 和事件循环的额外运行时职责。
- [现有 QuickJS 迁移矩阵](QUICKJS_MIGRATION_MATRIX.md)：历史能力/测试记录，需按当前源码复核。
- [整体 V8 接入方案](V8接入_框架瓶颈与迁移方案.md)：与本文配套的工程路线。

本次读取了完整 PDF 提取文本，并核对第 1 页渲染与关键章节；完成模块级映射和源码重点对照。没有执行 335 页每个函数的 Pro 真机一致性测试，没有宣称完整兼容，也没有修改运行时代码。
