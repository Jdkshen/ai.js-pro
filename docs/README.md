# AI.js Pro 文档索引

本文档目录按用途分类。除文档间的显式相对链接外，文中代码路径均以**仓库根目录**为基准。

> **当前状态（2026-10-20，dev-1154）**：HEAD `b7877e05`；dev 更新通道 **dev-1154**（Flutter-only 构建，约 127.3 MB）；三引擎 Rhino / QuickJS / Node24；多进程 `:script` / `:native` / `:node24_1..3`（Node 进程池）；UI 统一 Flutter，Miuix 已移出构建；MCP 默认端口 **8765**。接手先读 [HANDOFF_AI.md](HANDOFF_AI.md)；当前结论以 [PROJECT_STATUS](PROJECT_STATUS.md) 与 [REMAINING_WORK](REMAINING_WORK.md) §0 为准；`reports/` 均为时点快照。

## 当前状态

- **[新环境编译问题与原作者补充清单（上游更新，2026-10-20）](reports/2026-10-20_新环境编译问题与补充清单_上游更新.md)**：回应外部新电脑编译反馈的 P0/P1（Flutter 外置源、AOT/payload 路径、Python 构建脚本、瘦身模板、3 个失败单测、文档/错误处理）现状，并给出「新环境从零编译步骤」；外部原文作为附录保留。
- **[dev-1112 收尾交接（2026-10-19）](reports/2026-10-18_收尾交接_给Codex_v2.md)**：从 dev-1073 到 dev-1112 的逐版记录；末尾「2026-10-19 20:30 更新（dev-1112）」是当前最新进度：Rhino `:script` 无障碍反向通道、inrt 定时任务补全、三引擎回归全绿。
- **[三引擎 API 与示例核对（2026-10-19）](reports/2026-10-19_三引擎API与示例核对.md)**：全量枚举 377 个示例文件，Rhino 150 / QuickJS 84 / Node 49 编译通过；QuickJS 227/227、Rhino 22/22、Node 21/21 真机回归；列出 QuickJS `:script`、Node 引擎/UI/模块、Rhino 跨进程等核心缺口。
- [Rhino `:script` 无障碍事件回调（2026-10-19）](reports/2026-10-19_Rhino_script无障碍事件回调.md)：`IAutomationHostCallback` oneway AIDL + 主进程 delegate 转发；`auto.service` 远程代理；K40 收到 accessibility 46 条 / view_clicked 4 条 / toast 1 条。
- [inrt 定时任务补全（2026-10-19）](reports/2026-10-19_inrt定时任务补全.md)：广播 Intent 任务、按 path 执行工程内脚本、headless `exit()` 中止、exact alarm 未授权引导；模板重建并打入 dev-1111/1112。
- [Flutter 玻璃界面 POC 验收（2026-10-17）](reports/2026-10-17_Flutter玻璃界面POC验收.md)：按《统一 Flutter 界面可行性评估》§9 在 ai-js-pro 上跑通玻璃界面——首页渲染、真实脚本目录、主题与 Miuix 一致全部验收通过；冷启动 835 ms、PSS +35.6 MiB、APK +17.73 MiB（arm64 debug）。POC 代码以 `-PflutterPoc=true` 隔离在 debug 源集，默认构建/发布零影响；下一步按 A 路线分批移植 95 个桥方法。已按仓库发布流程迭代到 **dev-905**：**主入口已切到 Flutter**（桌面「AI.js Pro」进玻璃首页，安装器「打开」默认也进 Flutter），桌面保留「AI.js Pro（Miuix 回退）」入口；日志/终端/设置/工作区/退出等走原生页兜底，每版都通过 App 内检查更新 → 下载 → 安装全链路实测；**dev-906 起界面品牌统一为 AI.js Pro**（首页标题、抽屉头部、启动字标）；**dev-907 起顶栏 `<>` 直接打开新版多文件工作区**（恢复上次会话文件/标签，不再跳旧 Miuix 列表），抽屉副标题改为「脚本工作区」。**dev-909 完成桥移植第一批**：Flutter 侧可直接运行脚本、看运行中/定时任务并停止、读运行日志，文件新建/改名/删除到回收站/恢复/分享/在编辑器打开全部打通；启动页换 AI.js Pro 新 Logo。**dev-910 完成桥移植第二批（部分）**：抽屉 MCP 开关真正启停服务；插件页可启停/看图标/装本地或 URL 插件包；导入（工作区多选、插件/资源选择器、资源目录导入）、示例重置/复制、桌面快捷方式都打通；桥支持延迟回复（系统选择器类）。**dev-911 把打包/签名入口改走原生打包页**（单文件/工程都直接打开 MiuixBuildActivity，签名模式复用原生），不再移植 Flutter 工程页；**dev-912~914 补上最后两个原生桥**：电脑连接（服务器模式启停 + 主动连接电脑，PC 扩展在线时自动握手显示已连接）与悬浮运行（悬浮控制条 ▶/■/✕，脚本无界面执行可随时停止）；设备连接开关改为异步等待，不再误报失败。**dev-915 补齐最后 6 个未对齐项**：设备页 Shizuku/修复无障碍、抽屉悬浮窗（原生悬浮菜单）、允许远程调试真启停服务、资源页导入记录、设置页编辑器字号（写原生 Ace 偏好）、设置页「关于」五项（检查更新/更新源/问题反馈/关于/许可）。**dev-916 继续补交互**：文件浏览器支持长按多选、复制/剪切/粘贴（同名自动加 (1)、移动后清空剪贴板）与批量移入回收站；悬浮控制条可拖动并记住位置，新增「停止全部」「打开运行日志」按钮。**dev-917~919 对齐原厂 FloatingControllerView 源码**：补齐 ⌄ 收起/⌃ 展开（收起只留箭头、状态持久化）与 ✎ 编辑按钮，并修复控制条加宽后 ✕ 被裁掉的问题。**dev-920~923 视觉完善**：⌄/⌃ 放大为 30dp 圆形按钮，▶/■/⏹/✎/📄/✕ 改为彩色圆底芯片（运行绿/停止红/停止全部橙/编辑蓝/日志青/关闭红），运行中标题「● 脚本名」绿色加粗；窗口宽度按实际内容先量后定，✕ 不再被屏幕裁掉。**dev-924 细节**：收起按钮换成居中的紫色三角（▼ 展开 / 旋转 180° 收起为 ▲），拖动把手也加圆底、运行中变绿；所有芯片加按下水波；标题区收窄留边距。**dev-925**：收起按钮改为自绘 chevron（⌄/⌃ 几何居中，不受字体基线影响），补「收起/展开悬浮窗」tooltip 与首次收起 Toast 提示。**dev-936/937/938 停止任务直接执行**：任务管理页停止脚本/停止全部/取消任务不再弹确认框，点一下即停并浮动提示；运行中任务 kind 归一、引擎标签修正（Rhino 红 R）。**dev-935 跑脚本不再需要先按返回**：`PocRuntime.run` 不再启动透明 `RunIntentActivity`，普通脚本直接后台执行（UI 脚本仍走 `ScriptExecuteActivity`），运行期间界面可正常点击/滑动；悬浮条运行补工作目录。**dev-934 清理历史示例目录**：`BundledSampleSync` 增加 `LEGACY_DIRS` 显式清理（`QuickJS 新引擎`、`Rhino/Node 引擎/中文`、`English`、`pro-samples`），升级时旧目录自动消失（用户改过的先备份到 `sample-backup/legacy/`）；K40 与小米 8 均验证示例页只剩三棵引擎树。**dev-933 三引擎示例全部加首行标记**：Rhino 示例首行 `rhino`、Node 示例 `nodejs`、QuickJS 示例 `quickjs`（字符串指令，ui/auto 模式保持第一行）；引擎识别同时认 Pro 字符串写法与 `// @engine` 注释写法。**dev-932 示例引擎标记与路径清理**：`JavaScriptSource` 引擎识别支持 Auto.js Pro 首行写法 `nodejs` / `quickjs` 字符串指令（Node 示例现在真正由 Node24 执行，真机探针输出 `probe-node v24.22.0`）；示例目录去掉多余的「中文」层（Rhino 引擎/分类、Node 引擎/Node.js），同步修正 QuickJS 找图资源路径与构建校验。**dev-930/931 打包界面切回玻璃 ProjectPage**：新增 `PocPackaging` 桥后端（项目读写、后台打包任务轮询、签名列表/别名/新建/导入、单文件准备、安装/分享），项目/单文件/示例项目/后台请求四种入口都打开 Flutter 自绘打包页；打包在后台线程执行、2s 轮询进度，产物落 `/sdcard/脚本/构建/`；原生 Miuix 打包页保留为回退。报告：`docs/reports/2026-10-17_玻璃打包页接回ProjectPage.md`。**dev-928/929 示例集按引擎重排**：`assets/sample` 顶层改为 `Rhino 引擎 / QuickJS 引擎 / Node 引擎`；Rhino/Node 两棵语料换成 Auto.js Pro 原包示例集中文部分（dev-929 去掉 English），QuickJS 沿用本仓库示例并改名；示例菜单同步 Auto.js Pro（查看/运行/悬浮运行/打包/导入/重置）；`BundledSampleSync` 增加下架示例清理。报告：`docs/reports/2026-10-17_示例集按引擎重排为Rhino-QuickJS-Node.md`。**dev-926/927 示例页对齐原厂 Auto.js Pro**：标题改「示例文件」，行副标题改示例类型（不再显示修改时间）；菜单补齐 运行示例/打包示例/打包（项目）/导入到我的脚本/重置所有示例；点示例走原生编辑器并带 sample_asset_path（工具栏可「重置」）；桥新增 sampleAction。顺手清理了 assets/sample 下 6 个本地空壳目录（不进 APK，纯仓库卫生）。
- [Phase 0：:native 隔离脚本进程 MVP（2026-10-17）](reports/2026-10-17_Phase0_native隔离进程MVP.md)：带 lib/*.so 或 .dex 的 native 项目自动在独立 :native 进程运行，脚本进程 native 崩溃不再带崩主 App；IPC 运行/停止/任务列表/日志/崩溃自动重连已真机验证；auto_jspro_imgui 仍崩在其自带 native Button（GImGui 空），ImGui 显示问题待继续定位。
- [Phase 1 P0：脚本迁入 :script 进程（2026-10-18）](reports/2026-10-18_Phase1_脚本迁入script进程P0.md)：普通脚本在独立 :script 进程执行（ScriptHostService/PocScriptHost/ScriptHostStaging），主进程只做 UI/IPC；任务/停止/日志/崩溃重连已真机验证；auto 脚本暂留主进程，AIDL 自动化代理为 P1。
- [Phase 1 P1：自动化代理 IAutomationHost（2026-10-19）](reports/2026-10-19_Phase1P1_自动化代理实现记录.md)：主进程 AutomationHostService + :script 远程自动化后端（坐标/手势/选择器链/截图）、AUTO 门控开关、定时任务/通知/音量键停止统一调度；契约缺口第一批降级；真机全链路验收待下一轮。
- [第三方 ImGui 项目跑不起来诊断（2026-10-17）](reports/2026-10-17_第三方ImGui项目跑不起来诊断.md)：auto_jspro_imgui 自带 JNI .so + DexClassLoader，源码在 Android 16 跑不了（Pro 9.3.11 拒绝可写目录 dex；我们的 App 加载后 native SIGSEGV 带崩主进程），但包里自带打包 APK 可正常跑。
- [象棋与软件悬浮窗冲突检查](reports/2026-10-04_象棋与软件悬浮窗冲突检查.md)：K40 复现页面恢复重新打开悬浮球与多任务提前恢复的问题；共享避让令牌源码修复、6 项管理测试及 348 项象棋自检通过，0.6.54 源码待发布。
- [K40 更新实测（dev-783）](reports/2026-10-04_K40更新实测_dev783.md)：默认检查正常；局域网完整 APK 下载约 3 秒、原生哈希及签名校验通过；GH-Proxy 外网下载 10 分钟只完成约 39%，GitHub 直连连接超时，当前瓶颈在外网线路。
- [GitHub 加速内置（dev-783）](reports/2026-10-04_GitHub加速内置_dev783.md)：默认加速、失败直连、清单缓存修复已写入 APK；28 项更新测试通过，小米 8 验证默认入口并恢复 dev-780，可检查更新到 dev-783。当前 GitHub Latest。[下载](https://github.com/Jdkshen/ai.js-pro/releases/tag/dev-783)
- [首次 GitHub 发布与检查更新（dev-781）](reports/2026-10-04_GitHub发布_dev781.md)：三种 ABI 安装包、更新清单和校验文件首次公开；当时 K40 真实应用检查得到 779 → 781，APK 与清单链接均可访问。
- [MCP 接口对齐（dev-781）](reports/2026-10-03_MCP接口对齐_dev781.md)：补齐高级版 7 项入口及兼容调用名，当前 32 个功能入口与 7 个别名；修复双引擎执行参数、日志隔离和任务收尾。59 项单测、21 项真机检查通过，已发布。[使用说明](MCP_SCRIPT_SERVICE.md)
- [MCP 接口补齐检查（修复前，2026-10-03）](reports/2026-10-03_MCP接口补齐检查.md)：26 个工具时的历史审计；所列 MCP 缺项和日志差异已在 dev-781 补齐。
- [SIFT 缓存与渐进搜索（dev-771）](reports/2026-10-02_SIFT缓存与渐进搜索_dev771.md)：借鉴象棋引擎的数据复用和渐进搜索，精确验证场景灰度后复用特征及 FLANN 索引；低分辨率优先搜索失败后完整搜索。K40 静态全图重复匹配约4/6ms，局部约2/4ms，首次单次搜索约140ms。
- [SIFT C++ 后端与原生帧直连（dev-770）](reports/2026-10-02_SIFT_Cpp后端与原生帧直连_dev770.md)：完整匹配流程下沉 C++，QuickJS 避免整图复制，新增分段耗时和预读缓存示例；K40 稳定位置跟踪中位数 QuickJS 9ms、Rhino 13ms，453 项单测及 57/56 项真机检查通过。
- [SIFT 示例耗时日志（dev-769）](reports/2026-10-02_SIFT示例耗时日志_dev769.md)：根据反馈补齐双引擎单次匹配总耗时、模板准备和逐次缓存匹配耗时，日志以毫秒明确显示。
- [SIFT 内置 API 与速度优化（dev-768）](reports/2026-10-02_SIFT内置API与速度优化_dev768.md)：新增双引擎 matchSift/createSiftMatcher，入口各 7 行，支持模板缓存与局部跟踪后全图回退；K40 位置稳定跟踪实测 QuickJS 30ms、Rhino 15ms，453 项单测通过。[使用说明](guides/SIFT特征匹配示例.md)
- [SIFT 模板匹配适配（dev-766 / dev-767）](reports/2026-10-02_SIFT模板匹配适配_dev766.md)：补齐 arm64 OpenCV features，加入带原图的双引擎 SIFT 示例；448 项单测、双引擎各 16 项真机检查及发布图色门禁通过。dev-767 同步更新 Rhino 原入口为彩色全分辨率匹配，实测 22 个可靠内点。[使用说明](guides/SIFT特征匹配示例.md)
- [QuickJS 画布与佣兵项目兼容修复（dev-765）](reports/2026-10-02_QuickJS画布与佣兵项目兼容修复_dev765.md)：实现 QuickJS draw 与画布生命周期，修复 Rhino 语法、箭头线程、Symbol/Map、原生浮窗工厂、后台截图与文本属性；448 项测试通过，用户原项目的画布、OCR、悬浮菜单与启动流程已复测。
- [Rhino 模块反引号兼容修复（dev-764）](reports/2026-10-02_Rhino模块反引号兼容修复_dev764.md)：修复 require 模块中的反引号、多行 XML 和模板插值报 illegal character；421 项单测通过，发布包 K40 模块内 Canvas 绘制 36 帧；QuickJS draw 的后续修复见 dev-765。
- [高级版 MCP 对比与 Canvas 兼容复测](reports/2026-10-02_高级版MCP对比与Canvas兼容复测.md)：对照高级版包内 MCP 类和说明，建议补节点读取、截图和双引擎语法检查；记录直接运行和 require 模块的兼容差异及 dev-764 修复。
- [共享截图与连续触控 API（dev-763）](reports/2026-10-02_共享截图与连续触控API_dev763.md)：新增双引擎共享截图和 touch.create、6 个示例、编辑器索引及离线文档；417 项单测、双引擎截图与 Shizuku 注入/停止收尾通过，Root 多指待有 su 设备实测。用法见 [共享截图与连续触控](guides/共享截图与连续触控.md)。
- [节点“更多”与停止按钮（dev-761）](reports/2026-10-02_节点更多代码生成与停止按钮_dev761.md)：对照 Pro 完善代码生成弹窗、文本动作与返回修改，修正节点回退和列表生成；停止按钮改为红底白“×”；408 项单测及 12 项设备代码生成检查通过。
- [节点分析细节对齐（dev-760）](reports/2026-10-02_节点分析细节对齐_dev760.md)：按用户反馈去掉固定顶部栏，补齐紧凑界面、搜索状态保留、窗口实时获取、横向浏览和布局文件分享；408 项测试及 K40 功能验证通过。
- [内置节点分析悬浮窗（dev-759）](reports/2026-10-02_内置节点分析悬浮窗_dev759.md)：根据视频新增节点搜索、属性勾选复制代码、窗口选择、布局保存读取与范围图／层次树联动；407 项单测和 K40 功能验证通过。
- [微信空节点诊断与兼容服务（dev-758）](reports/2026-10-02_微信空节点诊断与兼容服务_dev758.md)：用户原机空节点日志、微信服务白名单与 Pro 组件对照、兼容入口及启用和打包路径修复；397 项单测和 K40 双引擎验证通过，原机故障页待复测。更新后需重新开启无障碍。
- [微信布局分析对照与修复（dev-755）](reports/2026-10-02_微信布局分析对照与修复_dev755.md)：源码修复、官方微信安装、Pro 同页对比、失败恢复与发布证据；欢迎页和登录页已验证，登录后的具体空白页面待复现。
- [UI 适配记录（2026-09-12）](reports/2026-09-12_UI适配_大字体横屏侧边栏.md)：大字体/横屏/侧边栏按钮/字号矩阵的真机适配——抽屉 304dp 曾是死代码（修复后竖屏 904→836px、横屏 2144→836px）、顶栏与工具栏去固定高度、触摸目标补到 48dp；含未验证清单与并发会话造成的构建污染说明。已发布 **dev-539** 并实测手机可检测到。
- [文件管理修复记录（2026-09-12）](reports/2026-09-12_文件管理修复.md)：接手检查六项缺陷的代码处理、新旧列表滚动恢复、自动测试和待做真机验收；已发布 dev-537 到局域网更新源，待手机安装验收。
- [接手指南（2026-09-12，历史）](reports/2026-09-12_接手指南_Codex.md)：截至 commit `7252b58` 的接手说明与工具/坑；**当前进度请改看 [2026-10-18 收尾交接 v2](reports/2026-10-18_收尾交接_给Codex_v2.md) 末尾的 dev-1112 更新**。
- [项目接手检查（2026-09-12，历史）](reports/2026-09-12_项目接手检查.md)：同批独立源码审计（文件列表滚动、新建菜单、搜索、删除刷新、Host 生命周期、无障碍名称等）；其中大部分已在 dev-537 之后修复，仅作历史依据。
- [项目现状与接手说明](PROJECT_STATUS.md)：当前主架构、三引擎/多进程、构建安装、K40 状态和后续整理顺序。接手项目应先读此文。
- [剩余细节审计与收尾清单](REMAINING_WORK.md)：§0 为 dev-1112 当前剩余项；其后保留 2026-09-08 的历史审计清单。
- [权限与发布基线](PERMISSIONS_AND_RELEASE.md)：正式签名变量、release 构建、SDK 范围、权限用途和发布验收。
- [详细交接记录](HANDOVER.md)：按时间累积的开发背景和历史验证；与当前状态冲突时，以项目现状说明和当前源码为准。

## 架构

- [象棋项目架构与运行流程](../samples/象棋自动走棋/项目结构.md)：按 0.6.54 源码梳理启动、截图识别、Pikafish 搜索、落子确认、跨引擎通信与两种悬浮窗的避让；该版源码修复待发布。
- [源码项目说明（2026-09-04 归档快照）](architecture/项目说明.md)：模块职责、运行流程、开发入口和维护风险；当前架构以上方 `PROJECT_STATUS` 为准。
- [Rhino / QuickJS 双引擎架构](architecture/QUICKJS_ENGINE.md)：Rhino/QuickJS 的引擎选择、API 覆盖、Native Frame 和构建方式；Node24 第三引擎见 [Node24 基础自动化 API](guides/Node24基础自动化API.md)。
- [双引擎功能对比（Rhino / QuickJS）](architecture/双引擎功能对比.md)：逐模块对照；Node24 不在本表内，单独见 Node24 系列文档。
- [Node24 基础自动化 API](guides/Node24基础自动化API.md) / [Node24 控件选择器 API](guides/Node24控件选择器API.md)：Node 引擎的无障碍、图片、投屏、控件查询与文字输入。
- [Auto.js Pro API 兼容性与 V8 选型](architecture/Auto.jsPro_API兼容性与V8选型.md)：依据 335 页第一代 API PDF 的 36 模块清单、语义缺口、框架分层与实施验收顺序。
- **[第一代 API 逐函数对照](architecture/第一代API逐函数对照.md)（含 [pro-api-compatibility.csv](architecture/pro-api-compatibility.csv)）：把 335 页 PDF 解析成 939 条逐函数清单，并与当前源码做静态对照（逐条带证据路径）；含模块汇总、缺口清单与复现方法。**
- [V8 接入：框架瓶颈与迁移方案](architecture/V8接入_框架瓶颈与迁移方案.md)：整个 AI.js Pro 的宿主解耦、可选 provider、线程与回调、数据桥、打包和性能验收；其中 V8 路线已通过 Node24 第三引擎落地（真实 Node v24.22.0，独立 `:node24` 进程），其余宿主解耦条目仍为设计目标。
- [QuickJS ImGui 悬浮窗 API 边界](architecture/QUICKJS_IMGUI_FLOATY.md)：脚本调用的 ImGui 悬浮 UI，以及与 Miuix/Flutter 应用界面的分工。

## 指南

- [液态玻璃导航：实现、接入与优化](guides/液态玻璃导航_实现接入与优化.md)：顶部胶囊的进度联动、AGSL 折射、完整 Java 源码、接入与性能优化，含当前验证范围。
- [编译指南（2026-10-20 更新）](guides/编译指南.md)：工具链真实版本（JDK17 / Gradle 8.9 / AGP 8.6.1 / Kotlin 2.1.0 / Flutter 3.3.4 / Python+fonttools / NDK r27c / CMake≥3.18.1 / Ninja 1.13.2）、原生 `.so` 复用/重编规则、Flutter AOT/payload、本地 Debug 安装与 dev 发布三套命令、输出路径修正与常见问题。
- [象棋引擎插件（Auto.js 插件 APK）](guides/象棋插件_打包与使用.md)：把象棋识别/棋规/算棋打包成可独立安装的插件（自带 Pikafish + NNUE），含两个引擎的加载差异、权重解析顺序与真机验证记录。
- [安装包瘦身：插件化与子集](guides/安装包瘦身_插件与子集.md)：把运行时模板、象棋工程与 NNUE 权重移出主 APK、ImGui 字体子集，以及各自的验证方式与回退开关；当前 dev-1112 约 132.1 MB，打包模板约 16.4 MB。
- [双引擎 API 与 UI 性能验证](guides/ENGINE_API_AND_PERFORMANCE.md)：可重复的 JVM 测试、API 差异生成和真机帧耗时流程。
- [CodeGraph 使用说明](guides/CODEGRAPH.md)：代码知识图谱索引与查询，供开发者与 AI 助手快速定位源码和调用链。

## 开发计划

`plans/` 保存阶段任务与改造方案，内容可能描述实施当时的状态，应结合当前源码和架构文档阅读。标有“（已作废）”的方案针对已删除的 ImGui 工作台，仅供历史参考。

- **[整仓重构全程计划（2026-09-23）](plans/整仓重构全程计划_2026-09-23.md)：A（象棋）/ B（平台）两条轨道的里程碑、依赖、验收门槛与当前状态；顶部有 2026-10-19 dev-1112 执行状态。**
- [AI.js Pro 整体框架重构任务书（2026-09-23）](plans/AI.js_Pro_整体框架重构任务书_2026-09-23.md)：平台线目标方案（P0–P6）；顶部标注了已落地/未收口项。
- [象棋自动走棋框架重构任务书（2026-09-23）](plans/象棋自动走棋_框架重构任务书_2026-09-23.md)：象棋线目标方案（P0–P5）；A0–A3 已推进，A4/A5 待完成。
- [ImGui 左侧抽屉菜单改造](plans/IMGUI_左侧抽屉菜单改造任务.md)（已作废）
- [MIMO 全局主题完整覆盖](plans/MIMO_全局主题完整覆盖改造任务.md)
- [MIMO 抽屉按钮无响应返修](plans/MIMO_抽屉按钮无响应返修任务.md)（已作废）
- [MIMO 脚本 APK 加密恢复与打包链路回归](plans/MIMO_脚本APK加密恢复与打包链路回归任务.md)
- [Native Frame 截图、图色、模板和 YOLO 稳定测试](plans/NATIVE_FRAME_截图图色模板YOLO稳定测试任务.md)
- [PP-OCRv6 (NCNN) 接入方案](plans/PP-OCRv6_NCNN接入方案.md)：本机现有资产（模型/ncnn/已编译桥）、真机实测、移植路线与工作量
- [PP-OCR 接入评估](plans/PP-OCR接入评估.md)（已作废）：早期的 ONNX/OpenCV DNN 路线，仅供历史参考
- [QuickJS 下一阶段开发](plans/QUICKJS_下一阶段开发任务.md)
- [Rhino 双引擎回归与发布验收](plans/RHINO_双引擎回归与发布验收任务.md)
- [QUICKJS 悬浮窗 API 需求清单](plans/QUICKJS_悬浮窗API需求清单.md)
- [象棋棋盘定位：多证据融合实施方案](plans/象棋棋盘定位_多证据融合实施方案.md)
- [象棋 YOLO / OCR / 红黑分离视觉架构重构方案](plans/象棋_YOLO_OCR_红黑分离视觉架构重构方案.md)
- [侧边栏按钮与全分辨率 UI 适配](plans/侧边栏按钮与全分辨率UI适配方案.md)（已作废）
- [悬浮窗流畅度优化](plans/悬浮窗流畅度优化方案.md)（部分失效，见文内说明）
- [UI 统一到 Compose(Miuix) 迁移方案](plans/UI_统一到 Compose(Miuix) 迁移方案.md)：退役 legacy flavor 与双轨分支、剩下的 View 页面迁移顺序与验收清单

## 历史报告

`reports/` 记录特定提交或测试环境下的结果，不自动代表当前工作树已经通过相同验证。

### 2026-10-18 / 10-19 · 三引擎、多进程与收尾

- [收尾交接给 Codex v2（2026-10-18，末尾更新到 dev-1112）](reports/2026-10-18_收尾交接_给Codex_v2.md)：当前最完整的逐版交付记录（Node24 API、投屏路由、模型管理、远程 require、三引擎回归、dev-1112 无障碍与 inrt）。
- [三引擎 API 与示例核对](reports/2026-10-19_三引擎API与示例核对.md)
- [Rhino `:script` 无障碍事件回调](reports/2026-10-19_Rhino_script无障碍事件回调.md)
- [inrt 定时任务补全](reports/2026-10-19_inrt定时任务补全.md) / [inrt 定时任务端到端](reports/2026-10-19_inrt定时任务端到端.md)
- [Phase 1 P0：脚本迁入 :script 进程](reports/2026-10-18_Phase1_脚本迁入script进程P0.md) / [Phase 1 P1：自动化代理](reports/2026-10-19_Phase1P1_自动化代理实现记录.md) / [IAutomationHost 契约](reports/2026-10-19_IAutomationHost契约与JSON协议.md)
- [Node24 基础自动化 API](reports/2026-10-18_Node24基础自动化API实现与K40验证.md) / [Node 引擎与线程](reports/2026-10-19_Node_engines_threads实现.md) / [Node media/ocr/zip/plugins](reports/2026-10-19_Node_media_ocr_zip_plugins.md) / [Node dialogs customView](reports/2026-10-19_Node_dialogs_customView.md) / [Node UI layoutXml/Widget](reports/2026-10-19_NodeUI_layoutXml_Widget修复.md) / [Node 模态与通知浮窗事件](reports/2026-10-19_Node_模态与通知浮窗事件.md)
- [远程运行 require 验证](reports/2026-10-19_远程运行require验证.md) / [自动化 API 使用面与代理拦截点](reports/2026-10-19_自动化API使用面与代理拦截点.md)
- [16KB RELRO 守卫与 task80](reports/2026-10-19_16KB_RELRO守卫与task80.md) / [OpenCV 原生库 strip 减重](reports/2026-10-18_OpenCV原生库strip减重.md) / [图色示例修复与 dev1049 验收](reports/2026-10-18_图色示例修复与dev1049验收.md)
- [日志页与 Pro 对照及改造建议](reports/2026-10-19_日志页与Pro对照及改造建议.md)

- [项目接手检查（2026-09-12，基线 commit 7252b58）](reports/2026-09-12_项目接手检查.md)：接手指南的依据——当前架构与已验证范围、源码确认的 P1/P2 问题、接手顺序；报告结尾另列「本轮未宣称完成」。
- [QuickJS 完成报告](reports/QUICKJS_完成报告.md)
- [Native Frame 稳定测试报告](reports/NATIVE_FRAME_稳定测试报告.md)
- [高精度耗时与案例同步更新说明](reports/高精度耗时与案例同步更新说明.md)

### 象棋自动走棋 · 2026-09-20 真机迭代（K40）

- **[差分视觉与卡点四修](reports/2026-09-20_差分视觉与卡点四修.md)：本轮主报告。** 差分模式（找色判走动 + 只对变化格 OCR）落地，
  以及四个卡点的真机定位与修法：锚点背 `?`、重建基准丢轮次、起点残影、刚落子的颜色被像素判反并写进基准；
  含验证器「未知格唯一解释」判据、幻影（重复子）规则与踩过的两个坑、测试 212/0 与 180s 真机对照表。
- [OCR 补正闸门与基准防漂](reports/2026-09-20_OCR补正闸门与基准防漂.md)：全盘 OCR 改盘面的三道闸门（字符合法/分数/连续帧共识）
  + 几何体检（整行偏一格的假几何）。
- [红黑判色改用找色](reports/2026-09-20_红黑判色改用找色.md)
- [视觉流水线：性能与准确率](reports/2026-09-20_视觉流水线_性能与准确率.md)
- [OCR 预处理实测：灰度重试](reports/2026-09-20_OCR预处理实测_灰度重试.md)
- [场景回归框架](reports/2026-09-20_场景回归框架.md)
- [象棋当前架构路线图](reports/2026-09-20_象棋当前架构路线图.md)
- [方案对照：实现现状与验收缺口](reports/2026-09-20_方案对照_实现现状与验收缺口.md)
- [象棋代码评审](reports/2026-09-20_象棋代码评审.md)
- [象棋项目文件结构评审](reports/2026-09-20_象棋项目文件结构评审.md)
- [评审修复交付说明](reports/2026-09-20_评审修复交付说明.md)

### 象棋自动走棋 · 2026-09-25 悬浮窗与算力口径

- [Contempt 下发通道与 UCI 选项自检](reports/2026-09-25_象棋Contempt下发通道与UCI选项自检.md)：把自编译引擎的避和
  选项接到手机链路（`pikafish.start({contempt})`，工程默认 40），启动时抓取 UCI 选项清单供真机自检；
  同时记录：本地 NNUE 权重已是最新（与上游 master-net 摘要一致），字符串扫描看不到选项属正常（立即数编码）。
- [算力结论与参数开放（含规则胜前置）](reports/2026-09-25_象棋算力结论与参数开放.md)：对手是游戏内置 Pikafish
  （depth 25 封顶 + 5s/手 + 单核 + 完整着法历史）；K40 实测 10s/2 线程 ≈ 23 层，与它同档 ⇒ 「赢不了」主要是
  同档互和 + 我方链条失误。本次把 算棋/线程/**哈希** 三个参数开放到面板，并把「着法历史是否完整」
  （规则胜的前提）做成可看见的日志与进度行。
- [悬浮窗刷新慢：根因与修复](reports/2026-09-25_象棋悬浮窗刷新慢_根因与修复.md)：面板自测的「最长心跳间隔」
  在**空载**时就有 1500~3000ms → 三处 Shizuku `dumpsys` 同步压在脚本线程上（1s 心跳每 5s 一次、
  启动 nudge 先读两次、等待对方每 4s 两次）。改为带缓存的读数（TTL 4s，>800ms 自适应 ×2、上限 15s）+
  空载不查 + 点击前守卫仍取新鲜值；「开始」期间全程「启动中…」反馈；滑条拖动限频 50ms。
- [字形识别：多尺度抢救 + 电脑侧评测](reports/2026-09-25_字形识别_多尺度抢救与电脑侧评测.md)：
  「某几格一直读不出」的根因是**游戏把刚点起/刚落地的棋子放大 ≈1.10 且上移 12px**（原尺寸模板只 0.48）；
  新增按缩放档抢救（分 ≥0.80 且头名−次名 ≥0.12 才采信）、阵营扫描抢救、一帧上限与面板日志；
  另新增 `tools/glyph-pc-eval.js`（电脑上跑同一份识别，不用刷机）；并**逐格核对修正评测集真值 31 处**
  （含全部 `?` 格：炮/车搞反、馬炮位置、被高亮环盖住的空格、漏卒）⇒ 计分格 784→809，
  基线 763/784 = 97.3% → JS **806/809 = 99.6%**（剩 3 格是落子动画中间帧）。
  另把**定位那套「全局拟合」**搬到判决层：新增 `src/vision/glyph-resolve.js`（违规格降级/改阵营 +
  「字形认得、只差红黑」的子力守恒补全），字形链从此不再"逐格独立判生死"。

## 本地产物

构建包、日志、截图、录屏和工具检查产物统一放在 `.artifacts/`，该目录被 Git 忽略。重组前 APK 的原路径、大小和 SHA-256 位于 `.artifacts/releases/pre-reorg/MANIFEST.json`。

