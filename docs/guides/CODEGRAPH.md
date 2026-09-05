# CodeGraph 使用说明

[CodeGraph](https://github.com/yourgraph/codegraph) 是本仓库的**代码知识图谱**工具：它在本地为全部源码建立索引（符号定义、引用、调用边），让开发者与 AI 助手一次查询即可拿到**源码 + 调用链**，无需反复全文搜索。

## 状态

- CLI 已全局安装：`codegraph`（npm 全局，`D:\npm-global\codegraph.ps1`）。
- 本项目已初始化索引：`.codegraph/`（Git 已忽略，不提交；`init` 会自动写入 `.gitignore` 规则）。
- 索引概况（2026-09-05 建立）：1,585 个文件，44,229 个节点，93,332 条边。

## 常用命令（在仓库根目录执行）

| 命令 | 作用 |
| --- | --- |
| `codegraph status` | 查看索引状态与统计 |
| `codegraph init` | 初始化（或重新构建）索引 |
| `codegraph sync` | 增量同步自上次索引以来的改动（大改后先跑这个） |
| `codegraph explore "<符号或问题>"` | 一次查询：相关符号源码 + 调用路径（与 `codegraph_explore` MCP 工具输出一致） |
| `codegraph node <符号或文件>` | 单个符号源码 + 调用方/被调方，或带行号读文件 + 依赖方（与 `codegraph_node` MCP 工具一致） |
| `codegraph query <关键词>` | 在代码库中搜索符号 |
| `codegraph callers <符号>` | 查找所有调用某符号的函数/方法 |
| `codegraph callees <符号>` | 查找某符号调用的所有函数/方法 |
| `codegraph files` | 从索引输出项目文件结构 |

示例：

```bash
codegraph node MiuixBackButton          # 源码 + 调用方一览
codegraph explore "返回按钮如何被各页面使用"
codegraph callers openMainDrawerFromMiuix
```

## 给 AI 助手的用法

- **MCP 工具（推荐，若已配置）**：`codegraph_explore`、`codegraph_node`，直接问"某符号/某功能"即可。
- **Shell 命令（总是可用）**：通过终端跑上面的 `codegraph explore` / `codegraph node`，结果同样包含源码与调用路径。
- 在 `~/.claude/CLAUDE.md` 或工作区规则（`.instructions.md` / `AGENTS.md` 等）中已注明：**存在 `.codegraph/` 时，优先用 CodeGraph 而非 grep/全文搜索来理解或定位代码**。

## 维护要点

- 每次大规模重构/新建模块后执行 `codegraph sync`（或 `codegraph init`）刷新索引，保证 AI 与开发者的查询是最新的。
- `.codegraph/` 是纯本地产物，换机器后重新 `codegraph init` 即可，无需提交。
- 索引目录被 `git status` 忽略，不会污染提交。
