# Node Widget 标签注册兼容续修

日期：2026-10-30。接续 dev-1291；**dev-1292 已发布到 LAN，小米 8 已安装并完成验收。** 本轮未安装、停止或重启 K40。

## 问题与修改

原流程先将 row/grid 等替换为 Node 控件类，再由 XmlConverter 映射 button 等内置简称；Widget 注册名因此丢失。父组件 render 才注册外部子组件时，子标签及原始属性也已转换。

本轮为每次原生 inflate 保存标签/原始属性快照，转换后的 DOM 仅携带本次短期 ID。在实际创建节点时优先查询当前注册表，恢复原始名称和属性；普通原生控件仍走已有转换。原生属性转换错误先保留，只有节点确实走原生路径才抛出，支持晚注册 Widget 的自定义空字符串，同时保留原生错误。

- 覆盖 button/grid/list/row/column/canvas/webview/switch/include/vertical/frame 等内置标签。
- 父 Widget.render 内注册外部子标签，原始自定义属性、实体和空字符串不会丢失。
- 列表保留模板原文，到绑定时再转换，支持设置模板后才注册 Widget。
- 完整原生类名绕过简称覆盖；render 返回自己覆盖的标签会受 32 层递归保护。
- 移除每次 inflate 对 JS prepareXml 的往返；源码身份保存在当前 inflater，未使用全局共享映射。
- XmlConverter 的可选 NodeAdapter 仅参与指定的调用；默认三引擎转换器继续拒绝无效尺寸。根节点读取同时支持前置 XML 注释。

## 验证结果

| 范围 | 实际结果 |
| --- | --- |
| 引擎 JVM | 522 项通过，含新增 6 项来源/别名/晚注册/实体/错误/嵌套上下文测试 |
| JS / 文档 | 7 组 Node UI 契约、10 项文档生成、59 项高亮/复制/主题检查通过 |
| MI8 最终候选 | 新增 36 项标签检查；旧 41 项生命周期、30 项属性、32 项事件通过 |
| 页面 | 示例真实点击、两次 recreate、音量上键停止及 MCP 停止通过 |
| 独立 APK | 用最终模板实际打包、安装并跑完新增 36 项；测试包已卸载 |
| MCP 文档 | 最终 App 773 条索引可检索并读取新增注册/模板绑定说明 |
| 发布门禁 | release/R8/签名/非 debuggable、22 ELF 的 16 KB 静态对齐、10 Retrofit 方法与 2 包装类型检查通过 |
| 日志 | 验证窗口 11,917 行，无 FATAL EXCEPTION、Fatal signal、RecyclerView inconsistency 或 destroyed mutex 标记 |
| 下载 | 手机完整 LAN 下载与安装 APK、清单、本地候选摘要一致 |

实际覆盖 11 个简称，并检查大小写、完整原生类名、空值/实体、自闭合/注释、父布局参数、列表实际 create/bind、原生错误、32 层递归及出错后恢复。列表测试使用真正的 RecyclerView Adapter；JS getAdapter 返回的是公共数据操作接口，反射原生 Adapter 需显式空参数数组。

同机 100 个 TextView 小样本各 5 次：已注册工厂路径中位数 dev-1291 为 36ms，dev-1292 为 38ms；冷/JIT/调度噪声明显，不据此宣称整体加速。原始结果保留在证据目录。

## 发布与源码

- APK：`C:/Users/18101/Downloads/AIjsPro-dev-1292.apk`，142,552,014 字节 / 135.95 MiB。
- SHA-256：`befa99b6cc6d20b749ca054db4a10f4d9457ac36d695c3ac490cbc3578b71b8a`。
- 更新源：`http://192.168.10.7:8080/update.json`，仅 LAN，未发布 GitHub release。
- 包地址：`http://192.168.10.7:8080/AI-js-Pro-Flutter-dev-1292.apk`。
- 发布时间日志：`.artifacts/publish-20261030-124942.log`。
- 最终候选：`.artifacts/node-widget-tags-1292/`；证据：`.artifacts/node-widget-tags-20261030/`。
- 发布源码/模板快照：分支 `sandbox/node-widget-tags-dev1292`，提交 `8a540fb7709edd8c3bde282720c4fef509909cb6`。
- 复用上轮保留的独立构建目录，没有再次尝试被拦截的目录删除；保留目录和生成差异供追溯。
- 保留 dev-1291 中已验的 VS 日志修复；主工作区旁线 AutoJs.java 和 VS 报告未修改、未替旁线提交。
- MI8 保留 `/sdcard/Download/AIjsPro-dev-1292.apk`；运行数/浮窗为 0，测试目录、测试包已清理。

## 保留边界

构造时 style/theme 全组合、复杂别名 Widget 共享根属性代理、通用第三方插件、Node 编译加密及高级版完整行为矩阵仍需继续。原 Android 36 故障设备及真正 16 KB 内核未接入；静态对齐不代表这些场景已经验收。

DP 只读审查任务 b1a428dc779645bf 因 QUOTA/402 余额不足失败；已检查 status/result，没有完成审查结果。由主助手实现、复核，没有向现有聊天发消息。

另记录 Java 桥既有边界：Class.getMethod(name) 省略空可变参数数组仍报找不到方法；本轮用 getMethod(name, []).invoke(target, []) 完成真实 Adapter 验证，未把此反射缺口冒称已修复。
