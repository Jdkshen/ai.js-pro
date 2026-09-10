# QuickJS 迁移与 Rhino 移除门禁

更新日期：2026-09-11

## 已自动验证

| 领域 | 当前状态 | 设备覆盖 |
| --- | --- | --- |
| 引擎选择 | 新建默认 QuickJS，无标记旧脚本保持 Rhino | compat/lite K40 通过 |
| files、timers、shell | QuickJS 真实调用通过 | compat/lite K40 通过 |
| app、storages、device、dialogs | QuickJS 基础桥接通过 | compat/lite K40 通过 |
| engines、threads、events | worker 参数/返回值与同步事件通过 | compat/lite K40 通过 |
| UI | 布局创建、文本更新/回读、关闭通过 | compat/lite K40 通过 |
| floaty | 真实创建、位置/尺寸/文本更新、关闭通过 | compat/lite K40 通过 |
| Rhino 兼容 | 23 项基础回归通过；lite 可控拒绝且不崩溃 | K40 通过 |

运行命令：

```powershell
.\tools\test-engine-flavors-device.ps1 -Serial cccc62c7
```

## 尚未达到移除条件

| 领域 | 硬阻塞 |
| --- | --- |
| 控件选择器 | QuickJS 没有 `text/id/desc/className/bounds` 选择器与 `UiObject`（`find/waitFor/untilFind` 全缺），这是无障碍自动化脚本的主体；移除 Rhino 前必须先补齐或明确不支持 |
| UI | QuickJS 是最小化 overlay 实现，还未覆盖 Activity 模式、完整控件属性和复杂列表交互 |
| floaty | 基础窗口已可用，但与 Rhino XML 窗口对象、控件代理和事件 API 尚未完全等价 |
| web | `http` 已可用；InjectableWebView/WebSocket 等页面级能力未迁移 |
| debugger | 现有 Dim 调试器是 Rhino 专用，QuickJS 尚无断点、单步、变量查看等价实现 |
| Java 桥接 | QuickJS 故意采用白名单 host bridge，不提供 Rhino 式任意 `java.lang.*` 反射；需先确定正式兼容边界 |
| 编辑器 | Token/AST、高亮、自动完成和部分异常仍直接使用 Rhino 类 |
| 运行时 | `ScriptRuntime`、`TimerThread`、UI 代理、continuation 仍有 Rhino 具体类耦合 |

## 样例约束

- `QuickJS 新引擎` 目录下的每个 `.js` 必须以 `// @engine quickjs` 作为第一条非空行。
- 依赖 E4X、任意 Java 反射、旧 `"ui"` 模式或 Rhino 对象代理的样例归入 `Rhino 引擎`。
- Gradle `verifyQuickJsSamplesMarked` 在每次 `preBuild` 阶段阻止未标记样例再次混入。

只有上述阻塞项完成、compat/lite 设备矩阵持续通过，才能从正式版移除 Rhino。
