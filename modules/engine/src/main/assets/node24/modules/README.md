# Node24 modules（task-146）

> **2026-10-19 状态（dev-1112）**：本目录的模块已由 `Node24Prelude.java` 在 prelude/ui 之后统一加载（`color.js` / `floating_window.js` / `accessibility_extra.js` / `zip.js` / `ocr.js` / `media.js` / `plugins.js`），`REGISTER-PATCH.md` 仅作历史合并方案保留。

`modules/engine/src/main/assets/node24/modules/` 是独立 JS 模块目录，**不修改 prelude.js**：

- `color.js`：Pro 语义完整 color（Color/HSVColor/HSLColor/toColorInt）。
- `floating_window.js`：Pro 面 floating_window 高级 API（当前宿主能力内的 createWindow/rawWindow/位置/尺寸/on；触摸/拖拽明确报需要宿主扩展）。
- `accessibility_extra.js`：accessibility 选择器、节点动作与异步查询，统一使用每任务 `automation` 宿主通道；注册 `accessibility`/`ui_selector`/`ui_object`/`selector`。用法见 `docs/guides/Node24控件选择器API.md`。
- `REGISTER-PATCH.md`：给 Lead/native-process 的合并片段（推荐 Java 加载方式，避免和 work_manager 冲突）。
- `OPENCV-EVAL.md`：@autojs/opencv 方案评估（建议先走 Java OpenCV 方案，不强行上 Node addon）。
- `VERIFY-K40.md`：K40 dev-1083 单工程 + JSON 验证记录。

模块文件可直接被测试工程 `require('./modules/xxx.js')` 加载（自行注册），也可由 `Node24Prelude.wrap` 在 prelude/ui 之后拼接加载。
