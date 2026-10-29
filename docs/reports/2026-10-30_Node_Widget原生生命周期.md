# Node Widget 原生生命周期续修

日期：2026-10-30。接续 dev-1288 的属性兼容修复；本轮候选 dev-1289，当前尚未发布。

## 修改范围

将当前 Node 宿主的 Widget 从 JS 提前展开 XML 改为原生 inflater 分阶段创建。保持 Node 执行槽和界面进程隔离，未将同步脚本工作搬回主应用进程。

- `render(context, parent)` 接收本次真实主题 Context 和父布局；顶层逻辑父布局为 null。支持单根 XML 或未挂载的 Android View。
- `rootViewCreated(view)` 在根 View 刚创建、XML 子节点创建前执行，此时 this.view 与 Widget.of 可用。
- 内部/外部 XML 子树创建后读取 initialAttributes，清除根阶段未命中的绑定缓存，再调用 inflated。
- inflated 之后应用外部 XML 原生属性和自定义属性，最后调用 mounted，再按 attach 参数挂载。
- Widget 的原始属性单独保留，先询问实例的 hasAttribute，再让原生属性经过 XML 转换；避免 size/w/id 等别名吞掉自定义 setter。XML 实体只解码一次。
- 修复共用 ViewAttributes.layout_weight 对尚未挂载的 LinearLayout.LayoutParams 不生效的问题；以实际 LayoutParams 类型判断。
- 工厂按脚本 owner 注册和释放；关闭后拒绝回调，回调错误沿受保护的 inflate 返回，失败控件不挂载。
- Activity 重建中的嵌套 ui.inflate 使用当前 inflater Context，避免旧 Activity 或 Application Context 混入。
- 新增内置“Widget 原生生命周期”示例，同步人可读与 MCP 共用的 Node UI 手册、索引和独立模板。

## 验证记录

首次候选已在小米 8 验证根阶段、默认值、外部属性和 Context/父布局；测试发现未挂载 weight 未生效，已修正后重新构建。修正版通过 516 项引擎 JVM、7 组 Node UI JS 契约、10 项文档生成与 59 项高亮检查。小米 8 宿主：39 项原生阶段、30 项 Widget 属性、32 项原生事件；新示例真实点击、两次重建、Context 与嵌套 ui.inflate 一致、MCP/音量上停止、3 次根创建回调中停止均通过。实际打包安装的独立 Node APK 同样通过 39 项原生阶段；测试 APK 已卸载。MCP 可检索并读取新版手册（773 条同源索引）。证据在 `.artifacts/node-widget-stages-20261030/`。

同一时段插件任务修改了 AutoJs.java 日志入口；该文件保持原样且不纳入本轮提交。初步候选含该工作区改动，最终包将从独立工作树重新生成并复核，以保持本轮源码与包一致。最终摘要和发布状态在完成后补录。

## 保留边界

Node 尚未全部对齐高级版。构造时 style/theme 的全部组合、第三方通用插件、复杂别名 Widget 共享根后的属性代理、动态晚注册且已经转换的外部模板、Node 编译加密与完整 API 行为仍需专项对齐。旧宿主回退的 XML 展开路径不具备真实分阶段能力。原 Android 36 故障设备和真正 16 KB 页大小设备未在本轮接入，不能以小米 8 的结果替代。

本轮 DP 只读审查任务因余额不足失败，没有审查结果；主助手继续完成实现与验证，没有向用户的 DP 桌面对话发送消息。
