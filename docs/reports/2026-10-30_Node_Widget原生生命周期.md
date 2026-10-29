# Node Widget 原生生命周期与 dev-1291 发布

日期：2026-10-30。**dev-1291 已发布到局域网更新源，小米 8 已安装并完成最终包验收。** K40 的 dev-1290 由 VS 插件任务安装；本任务没有安装、停止或重启 K40。

## 本轮实现

接续 dev-1288，将当前 Node 的自定义 Widget 从提前展开 XML 改为原生 inflater 分阶段创建。保持脚本槽和 UI 进程隔离，没有把同步脚本工作搬回主应用进程。

顺序：constructor → created → render(context,parent) → rootViewCreated → 子树 → initialAttributes → inflated → 外部 XML 属性 → mounted。

- render 接收真实主题 Context、实际父布局；顶层逻辑父布局为 null。支持单根 XML 或尚未挂载的 Android View，保留 render 已创建的子树。
- rootViewCreated 在 XML 子节点创建前执行，this.view、Widget.of 已可用。
- 内部和外部子树创建后读取 initialAttributes，清除根阶段未命中的绑定缓存，然后调用 inflated；外部属性随后覆盖默认值。
- contentSlot、外部子节点插入、正确的父布局 LayoutParams、attach=false、别名 Widget 的基础路径已验证。
- 保留自定义属性原名，先询问 Widget，再做原生 XML 别名转换；size/w/id 不再吞掉自定义 setter。XML 实体只解码一次。
- 保留不继承 Widget 的旧式构造器默认属性兼容，新增原生与自定义默认值回归。
- 共用 ViewAttributes.layout_weight 以 LayoutParams 类型判断，修复未挂载控件的 weight 不生效。
- 工厂按脚本 owner 注册和释放；关闭后拒绝回调，错误沿受保护的 inflate 返回，失败控件不挂载。嵌套 ui.inflate 使用当前 inflater 的真实 Context。
- 新增内置“Widget原生生命周期.js”示例，更新 [Node UI 手册](../guides/NODE_UI_API.md)、App/MCP 同源文档和内置独立打包模板。

## 验收

| 范围 | 实际结果 |
| --- | --- |
| 引擎 JVM | 516 项，0 失败/跳过 |
| Node UI JS 契约 | 7 组通过，含新的原生回调协议与旧构造器默认值 |
| 文档 | 10 项生成、59 项代码高亮/复制/主题检查通过，索引生成校验通过 |
| 最终 dev-1291，MI8 | 41 项原生阶段、30 项 Widget 属性、32 项真实原生事件通过 |
| 最终界面 | 实际点击计数、两次 recreate、Activity/嵌套 Context 一致、MCP 停止与音量上键停止通过 |
| 独立 APK | 用最终模板实际打包、安装并运行，41 项通过；测试 APK 已卸载 |
| 电脑日志 | 最终 1291 经自家 DeviceConnection 协议客户端验证三引擎各 5 条，15 条均只收到一次 |
| MCP 文档 | 从最终包检索并读取 rootViewCreated/原生 View 文档，773 条同源记录 |
| 日志窗口 | 最终验证窗口 11,995 行，无 FATAL EXCEPTION、Fatal signal、RecyclerView inconsistency 或 destroyed mutex 标记 |
| 下载 | 手机完整 LAN 下载 SHA-256 与本地候选、安装 APK、更新清单一致 |

先验候选还完成了 3 次 rootViewCreated 回调执行中停止。最终相关 84 个引擎主/嵌套编译类与先验候选逐字节一致；最终模板的 JS 已同步旧构造器修复，并重新做了独立 APK 验收。

首个阶段测试发现 detached weight 未生效，已修复后重新验收。独立构建还发现 release 必需的 Retrofit 校验器被 tools 忽略规则挡住；已将 Python/Java 校验器和精确白名单纳入版本控制，没有跳过发布门禁。

## 日志任务与源码归属

保留 VS 插件任务已经交付的 dev-1290 日志转发修复：统一从 printlnAt 转发，原始时间和 source 保留。旁线的 463 项 App 单测、双机各 15 条日志、VS Code 中各显示一次的记录属于旁线验证；本轮另对最终 1291 做了上述三引擎协议客户端测试。

主工作区 AutoJs.java 保持原样，未替旁线修改或提交。最终包从独立分支 `sandbox/node-widget-dev1291` 构建，产品源码与模板快照为 `f3b32e10cb13d7e3af9439ef7aa065952e028309`；Node 创建流程的初始提交为 d6d9cd98，独立分支合入的日志修复与旁线文件一致。构建产生的示例版本元数据差异另存于本地证据，不混入主工作区。

DP 只读审查任务因余额不足失败，没有审查结果；主助手完成了实现和验证，没有给用户的 DP 桌面对话发送消息。用户选择各任务独立完成，未向另一聊天发送协调消息。

## 发布与产物

- 版本：dev-1291 / 1291，仅 LAN，未发布 GitHub release。
- 构建：release、保守 R8、既有开发测试证书、非 debuggable，未启用资源裁剪。
- 门禁：签名/清单、22 ELF 的 16 KB 静态对齐、10 个 Retrofit API 方法及 2 个包装类型泛型和下载注解通过。
- APK：142,546,658 字节，135.94 MiB。
- SHA-256：`7900689fd8eb90b49452669ea55af53188cdb87ca2fe2058deff6c22f8a9d24c`。
- PC：`C:/Users/18101/Downloads/AIjsPro-dev-1291.apk`。
- 更新源：`http://192.168.10.7:8080/update.json`。
- 包地址：`http://192.168.10.7:8080/AI-js-Pro-Flutter-dev-1291.apk`。
- 发布日志：`.artifacts/publish-20261030-072601.log`。
- 证据：`.artifacts/node-widget-stages-20261030/`；最终候选在 `.artifacts/node-widget-stages-1291-final2/`。其他 1289/1291 初步目录不作为最终包。
- MI8 保留 `/sdcard/Download/AIjsPro-dev-1291.apk`，测试目录和测试 APK 已清理；结束时 MCP 运行数、悬浮窗均为 0。

## 尚未全部对齐

高级版全部 Node API、构造时 style/theme 的全部组合、复杂别名 Widget 共享根后的属性代理、动态晚注册且已转换的外部模板、覆盖内置标签名时与原生别名转换的顺序（源码审阅发现，尚未实测）、通用第三方插件、Node 编译加密与完整行为矩阵仍需专项对齐。旧宿主的 XML 展开回退不具备真实阶段，外部旧打包插件模板未纳入本轮验收。

QuickJS ui.inflate、节点大树分页/BFS 组合/跨布局身份等原有待办没有因此关闭。原 Android 36 故障设备、真正 16 KB 内核以及原 native 图形栈长跑未在本轮接入，不能用 MI8 的结果替代。
