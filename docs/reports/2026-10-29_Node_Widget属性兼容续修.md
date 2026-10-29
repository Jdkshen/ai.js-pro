# Node Widget 属性兼容续修

日期：2026-10-29。接续 [dev-1287 原生 View 事件](2026-10-29_Node原生View事件与高级版兼容续修.md)，本轮 **dev-1288 已在小米 8 验收并发布到局域网更新源**。

## 对照范围

仍使用已确认身份的 `output/auma-base.apk`：`com.boyai.auma` / `9.3.11-0`，SHA-256 `e0010eafdd7ab8bd204e67ffdca3ec1ac7c1c0a4180ca42311fe8ebe2c0fb31a`。核对公开 `ui/view` 中 Widget 的声明和该 APK 随附模块的接口行为，独立实现本项目适配，没有复制其模块代码。

Node **尚未全部对齐高级版**。本轮只补可以在当前 XML 展开架构中实现并验证的 Widget 契约。

## 修改

- 补 `Widget.create(name)`、`hasAttribute`、`getAttribute`、`setAttribute`。创建未注册名称返回 undefined；create 只执行构造，不伪造 inflate 生命周期。
- Widget.view 在尚无原生控件时报明确错误；挂载后返回该原生控件。
- 先完成子类构造和 created，再读取 initialAttributes，修复 super() 期间读取子类未初始化字段的问题。
- 原生默认属性写入 XML；原生属性优先级为外部 XML > render 的内部值 > initialAttributes。原生默认值 null/undefined 跳过。
- `attr$name` getter/setter 支持继承；可写 JS 数据属性亦支持 XML、单个 view.attr 和批量 view.attr。已初始化的数据属性不会被默认值覆盖。
- 未声明或只读属性经调用方提供的 fallback 处理；不无条件创建新的 attr$ 字段吞掉原生 fallback。该实现对继承描述符、默认回退作了明确处理，不机械复刻高级版模块中 Reflect.set 的可疑分支。
- 基类 created/mounted 提供空方法，可调用 super；保留既有 mounted(view) 扩展和部分旧注册方式。
- Widget.of(普通 View) 返回 undefined，与公开声明一致；dev-1287 曾返回 null。
- 同步手册、App/MCP 离线文档、内置“自定义 Widget 属性”示例与打包模板。
- 首次真机候选发现动态 textColor 报“未知属性”，已补三引擎共用 TextViewAttributes 的 textColor/color/textColorHint 及原生实时颜色读取；同时修复 @android:color/name 被误当普通颜色名解析的问题。失败候选不发布，须用修正版重新验收。
- 公开入口复核发现 QuickJS 未提供 ui.inflate；现有 ui.layout/findView 路径中，ui.run 内属性读取会自等主线程约 2 秒、返回空串。已改为复用可重入的主线程读取器，主线程属性写入立即执行；ui.inflate 仍列为后续缺口，不将内部 Java 层测试当作该接口已经存在。

## 本轮边界与下一步

当前是“JS 展开 XML → 原生整体 inflate → 绑定 Widget”，尚不具备高级版全部原生 inflate 阶段：

1. `rootViewCreated` 必须在根 View 已创建、其子节点尚未 inflate 时执行；目前不能用整体 inflate 后的回调冒充。
2. `inflated` 必须在内部/初始属性应用后、外部属性应用前执行；当前外部原生属性合并在 XML 中，还不能准确提供这个阶段。
3. `render(context, parent)` 返回原生 View、依赖真实父布局或 Activity Context 的路径仍待补。当前 initialAttributes 在原生 View 创建前读取，应避免访问 this.view。

下一阶段应给原生 inflater 增加 Widget 创建/属性阶段回调，在创建根 View、创建子树、应用外部属性的真实位置调用 JS。需要维持当前脚本进程、取消协议、UI 回调重入和重建后资源归属；不能把脚本同步工作搬回主应用进程。验收要直接检查每个阶段的子节点数量、属性值、真实 parent/context，以及停止/重建时的回收。

其他待办仍见 [剩余差距](2026-10-29_Node原生View事件与高级版兼容续修.md#仍需继续的项目)：节点大树分页、BFS 组合、跨布局身份、Node 编译加密、完整控件/插件/API 行为验证。

## 验收

设备为小米 8 `ce4d2bdb`，Android 35 / 4KB 页。K40 的脚本、安装包和服务未改动。本轮不包含 Android 36 原机或真正 16KB 内核验收。

| 验证 | 结果 |
| --- | --- |
| 本地契约 | 6 组 Node UI 契约通过：Widget、动态 UI、View 事件、Java 代理、资源、显式资源 Context；引擎 JVM 516 项，0 失败 |
| 文档 | 10 项生成测试、59 项代码/原文/主题检查通过；773 条 / 30 来源；App/MCP 同源 |
| Node Widget 宿主 | 最终 APK 的 30 次断言通过，含继承、创建时序、原生默认值、外部覆盖、数据字段、getter/setter、原生颜色 fallback、Android 资源色 |
| View 事件回归 | 32 次断言通过；Node UI JS 与最终源文件逐字节一致 |
| 三引擎颜色 | `three-engine-text-colors.js` 在 Rhino / QuickJS / Node 各 7 项，通过原生颜色变化、实时读取、资源查找、失败不改旧值等检查 |
| 公开入口 | Rhino/Node 的 inflate + View.attr 通过；QuickJS 使用现有 layout/findView 路径，3 轮主线程属性读写通过；没有声称 QuickJS 的 ui.inflate 已实现 |
| QuickJS 卡顿回归 | 旧包单次读取等待 2002ms 并返回空串；修复后整组文字/颜色/提示色/资源色读写分别为 3 / 2 / 4ms。原始数据保留，不据此宣称整个应用都提速同等幅度 |
| Widget 界面 | 内置新示例实际点击及两次 recreate 通过，每次得到新控件、初值正确；MCP 停止、音量上键停止均达到 STOPPED |
| 最终独立 Node APK | 由最终模板实打包、安装，30 次 Widget 断言通过；测试后卸载。该测试包显式关闭图像/无障碍模块，验收范围为 Node Widget/颜色 |
| MCP 文档 | Widget.create 在 Node 文档中可检索并读取原文代码；QuickJS 可检索 textColor 文档；最终 activeMcpRuns=0、overlayCount=0 |
| 构建/发布 | release/R8、清单/签名、22 ELF 的 16KB 静态检查、Retrofit 审计通过；手机拉取清单 HTTP 200，并实际下载整包校验 SHA-256 |

失败候选 `node-compat-1288/` 没有发布；首次颜色失败日志保存在 `.artifacts/node-widget-20261029/before-color-fix-*.json`。最终采用 `.artifacts/node-compat-1288-final3/`，不得误用较早候选或旧摘要。

主要证据位于 `.artifacts/node-widget-20261029/`：`widget-host-final3.json`、`events-host-result.json`、`three-engine-text-colors.json`、`quickjs-main-attr-before.json`、`quickjs-main-attrs-final.json`、`sample-recreate.json`、`widget-stop.json`、`standalone-final3-result.json`、`mcp-final-docs.json`、`publisher-guard.json`、`delivery.json`。产物和日志不提交 Git。

## 发布

- APK：`C:/Users/18101/Downloads/AIjsPro-dev-1288.apk`，142,538,996 字节 / 135.94 MiB。
- SHA-256：`08f800e137664ba8c7924c2bc7e3b3973d9ee42a27c8aee0a43d6dc0772ac6f4`。
- 更新源：`http://192.168.10.7:8080/update.json`；仅局域网发布，未创建 GitHub release。
- 主 APK 签名沿用开发通道测试证书，构建为 release/R8、非 debuggable。实装 APK、手机整包下载与发布清单的摘要相等。
- 发布日志：`.artifacts/publish-20261029-213812.log`。

发布时 K40 一度断开，测试机已经装了相同版本。发布器新增显式 `-AllowInstalledCandidate`：仅允许 `-SkipBuild -ApkPath -VersionCode`，版本必须等于测试机、同时高于仓库/已发布基线；安装包和候选 SHA 必须一致，暂存后再次校验。默认仍要求版本高于所有基线。相同候选 DryRun 成功、不同候选被拒绝、未加标志的同版请求被拒绝；未降低默认版本保护。该模式不会重新安装或停止设备任务。
