# AI.js Pro 文档索引

本文档目录按用途分类。除文档间的显式相对链接外，文中代码路径均以**仓库根目录**为基准。

## 当前状态

- **[接手指南（给 Codex / 下一个 agent）](reports/2026-09-12_接手指南_Codex.md)：从当前 commit 接手要看的第一份。进度、未验证项、下一步顺序、本仓库专用工具与坑。**
- **[项目接手检查](reports/2026-09-12_项目接手检查.md)：同批的独立源码审计。列出文件列表的 P1/P2 具体缺陷（滚动位置丢失、新建菜单无响应、搜索只切目录、删除后刷新时机、Host 生命周期、无障碍名称缺失）与验证结果 —— 动手修之前先看这份。**
- [项目现状与接手说明](PROJECT_STATUS.md)：当前主架构、功能边界、构建安装、K40 状态和后续整理顺序。接手项目应先读此文。
- [剩余细节审计与收尾清单](REMAINING_WORK.md)：按 P0/P1/P2 列出尚需处理的 MCP、发布、Miuix、ImGui、引擎、测试和文档事项。
- [权限与发布基线](PERMISSIONS_AND_RELEASE.md)：正式签名变量、release 构建、SDK 范围、权限用途和发布验收。
- [详细交接记录](HANDOVER.md)：按时间累积的开发背景和历史验证；与当前状态冲突时，以项目现状说明和当前源码为准。

## 架构

- [源码项目说明](architecture/项目说明.md)：模块职责、运行流程、开发入口和维护风险。
- [Rhino / QuickJS 双引擎架构](architecture/QUICKJS_ENGINE.md)：引擎选择、API 覆盖、Native Frame 和构建方式。
- [QuickJS ImGui 悬浮窗 API 边界](architecture/QUICKJS_IMGUI_FLOATY.md)：脚本调用的 ImGui 悬浮 UI，以及与 Miuix 应用界面的分工。

## 指南

- [编译指南](guides/编译指南.md)：环境要求、原生构建、Gradle 组装、输出位置和常见问题。
- [双引擎 API 与 UI 性能验证](guides/ENGINE_API_AND_PERFORMANCE.md)：可重复的 JVM 测试、API 差异生成和真机帧耗时流程。
- [CodeGraph 使用说明](guides/CODEGRAPH.md)：代码知识图谱索引与查询，供开发者与 AI 助手快速定位源码和调用链。

## 开发计划

`plans/` 保存阶段任务与改造方案，内容可能描述实施当时的状态，应结合当前源码和架构文档阅读。标有“（已作废）”的方案针对已删除的 ImGui 工作台，仅供历史参考。

- [ImGui 左侧抽屉菜单改造](plans/IMGUI_左侧抽屉菜单改造任务.md)（已作废）
- [MIMO 全局主题完整覆盖](plans/MIMO_全局主题完整覆盖改造任务.md)
- [MIMO 抽屉按钮无响应返修](plans/MIMO_抽屉按钮无响应返修任务.md)（已作废）
- [MIMO 脚本 APK 加密恢复与打包链路回归](plans/MIMO_脚本APK加密恢复与打包链路回归任务.md)
- [Native Frame 截图、图色、模板和 YOLO 稳定测试](plans/NATIVE_FRAME_截图图色模板YOLO稳定测试任务.md)
- [QuickJS 下一阶段开发](plans/QUICKJS_下一阶段开发任务.md)
- [Rhino 双引擎回归与发布验收](plans/RHINO_双引擎回归与发布验收任务.md)
- [侧边栏按钮与全分辨率 UI 适配](plans/侧边栏按钮与全分辨率UI适配方案.md)（已作废）
- [悬浮窗流畅度优化](plans/悬浮窗流畅度优化方案.md)（部分失效，见文内说明）
- [UI 统一到 Compose(Miuix) 迁移方案](plans/UI_统一到 Compose(Miuix) 迁移方案.md)：退役 legacy flavor 与双轨分支、剩下的 View 页面迁移顺序与验收清单

## 历史报告

`reports/` 记录特定提交或测试环境下的结果，不自动代表当前工作树已经通过相同验证。

- [项目接手检查（2026-09-12，基线 commit 7252b58）](reports/2026-09-12_项目接手检查.md)：接手指南的依据——当前架构与已验证范围、源码确认的 P1/P2 问题、接手顺序；报告结尾另列「本轮未宣称完成」。
- [QuickJS 完成报告](reports/QUICKJS_完成报告.md)
- [Native Frame 稳定测试报告](reports/NATIVE_FRAME_稳定测试报告.md)
- [高精度耗时与案例同步更新说明](reports/高精度耗时与案例同步更新说明.md)

## 本地产物

构建包、日志、截图、录屏和工具检查产物统一放在 `.artifacts/`，该目录被 Git 忽略。重组前 APK 的原路径、大小和 SHA-256 位于 `.artifacts/releases/pre-reorg/MANIFEST.json`。
