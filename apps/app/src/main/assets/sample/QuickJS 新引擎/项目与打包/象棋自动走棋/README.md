# 象棋自动走棋 · QuickJS 工程骨架

中国象棋自动走棋工程的**落点**：目录约定、工程配置和界面骨架已经就位，识别、棋规、算棋、落子尚未实现。
界面上的状态文案与这里保持一致，不会把"还没做"写成"已经能用"。

## 现在有什么

- `project.json`：QuickJS 工程（`engine: quickjs`、`uiMode: true`、保留 OpenCV 模块）。
- `main.js`：界面骨架 + 只读环境探针（不截图、不加载模型、不点击）。
- `assets/models/`：模型槽位，见其中的 `README.md`。

## 计划中的结构（尚未创建）

```
src/runtime/    aijs-adapter.js、worker-entry.js、capability-report.js
src/vision/     board-locator.js、detector-adapter.js、grid-calibrator.js、cell-assignment.js
                temporal-fusion.js、diagnostics.js
src/chess/      fen.js、rules.js、legal-moves.js、transition.js、repetition.js
src/engine/     uci-client.js、engine-manager.js、local-book.js
src/automation/ state-machine.js、move-ticket.js、click-executor.js、move-verifier.js
src/ui/         main-panel.js、overlay.js
tests/          离线棋规 / 坐标 / 状态用例
tools/          runtime-probe.js、inspect-model.py
```

⚠️ 放进这个示例目录的**每个 `.js` 首行都必须是 `// @engine quickjs`**（`:app:verifyQuickJsSamplesMarked`
在每次构建时校验整棵 `QuickJS 新引擎/`），模块文件也不例外。

## 硬性约束

- **完全本地**：不做云库、远程推理、远程棋力；不添加 `INTERNET` 权限。资源随包提供，断网可用。
- **模型契约**：`yolo.load({ backend: 'dnn' })` 走 OpenCV DNN，当前只适配固定输入、六列输出
  （`[x1,y1,x2,y2,score,classId]`）的 YOLO26 类模型；非六列输出必须先按 `decoder` 显式解码，
  不能靠"总元素能除以 6"猜。
- **不要用 COCO 模型冒充象棋模型**：内置的 `yolo26_160/640.onnx` 是通用目标模型，能加载不等于能识别红车黑炮。
- **点击前置条件**：轮次未知、局面不可靠、票据过期时一律不许点击（详见任务书的状态机一节）。

## 导入与打包

1. 在 AI.js Pro 的**资源页**点这个工程条目的下载按钮，整个工程会落到 `脚本目录/下载资源/…`；
   也可以在**示例页**里直接进入目录后再"打包（项目）"。
2. 打包页选择 `project.json` 所在目录作为源，按项目模式打包；保持"移除 OpenCV"关闭。
3. 包名 `com.jdkshen.xiangqi.quickjs`，当前不含任何网络权限。

## 验收现状

- ✅ 工程能导入、能打开、能进打包页
- ❌ 识别 / 棋规 / 算棋 / 落子：**未实现**，因此也**没有**任何准确率、FPS 或真机成绩
