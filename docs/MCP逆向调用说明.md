# AI.js Pro MCP 逆向调用说明

完整教程与 App、MCP 共用同一份资源：[内置逆向指南](../apps/app/src/main/assets/mcp/AIjs-Reverse-Guide.md)，[机读工作流](../apps/app/src/main/assets/mcp/reverse-workflows.json)。本页用于把入口交给其他 AI，无需复制历史聊天。

把下面这段交给连接手机 MCP 的 AI：

```text
先 tools/list 查看当前真实工具和 inputSchema。
调用 reverse_guide({"topic":"index"})，按返回的 sectionIds 读取相关章节。
调用 reverse_capabilities({}) 确认 root、组件与执行条件。
调用 reverse_plan({"goal":"inspect"}) 获取机读步骤；其他 goal 见索引。
计划不执行；先替换 ${...} 占位符，缺组件时按指南显式安装。
返回 jobId 只代表任务已创建；apk_job_status 到终态后，读取 apk_job_result。
只在 hasMore=true 时用 nextOffset 继续分页，拼完 content 再解析 JSON。
失败看 error.code 和 nextActions；不得把缺后端、超时、截断或推断写成验收成功。
```

标准 MCP 客户端还可以通过 `resources/list` / `resources/read` 读取：

- `aijs://reverse/guide`：完整 Markdown 教程。
- `aijs://reverse/workflows`：JSON 工作流与参数模板。

`prompts/list` / `prompts/get` 提供 APK 盘点、SO 分析、APK 回编译模板。只支持工具调用的客户端直接用 `reverse_guide` 和 `reverse_plan` 即可。

手机里打开「文件与 APK 工具 → 逆向调用指南」，可以选择章节、继续分页、复制正文；主题跟随 App。

工具依赖按需下载，主 APK 不嵌入整套逆向引擎。Frida 另需可连接的 Server/Gadget 与目标附加权限；内置指纹不是完整 APKiD；r2ghidra 为独立 C++ 伪代码组件，不需要桌面 Ghidra。具体兼容边界以完整指南和实际调用结果为准。
