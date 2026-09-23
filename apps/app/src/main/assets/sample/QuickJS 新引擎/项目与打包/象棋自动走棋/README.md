# 象棋自动走棋（QuickJS 工程）

当前工程版本：**0.6.3**。它在 AI.js Pro 内运行；AI.js Pro 的 `dev-xxx` 是宿主 APK 版本，和本工程版本分别记录。识别、棋规、Pikafish 本地搜索、点击与落子确认已有实现；整局稳定性仍需真机逐手验收。

## 日常使用

1. 在 AI.js Pro 运行 `main.js`，或从主页面打开 `悬浮窗运行与测试.js`。需要让游戏保持前台时用悬浮窗。
2. 选「只识别」先看棋盘与红黑识别；确认无误后用「着法建议」。只有「自动走棋」会点击棋盘。
3. 自动模式先选我方颜色；非标准开局需要明确声明「现在轮到我」。未知格、轮次不明、几何不可靠或落子未确认时，驱动应停止出手并给出原因。
4. 排障先记 **宿主 dev 版本 + 本工程版本 + 面板启动指纹**。APK 更新不会覆盖手机里已经导入的工程副本，旧副本需单独更新。

棋盘几何只由本地图色格线算法定位。格线证据不足会暂停并提示，不再回退到旧的手机区域扫描。主页面识别默认尝试视觉流水线；悬浮窗自动走棋默认使用已验收的 `legacy` 识别模式，`visionPipeline: true` 与 `visionPipeline: "diff"` 仍可用于对照。定位与棋子识别是两个独立步骤。

## 一条运行主线

```text
main.js / 悬浮窗运行与测试.js
  → src/app/bootstrap.js + controller.js
  → src/services/board-service.js（抓帧、定位、识别）
  → src/automation/auto-driver.js（局面与轮次、算棋、执行、确认）
  → src/engine/engine-router.js → 本地 Pikafish / JS 退回
  → src/automation/click-executor.js + move-verifier.js
```

`src/chess/` 是纯棋规和 FEN；`src/vision/` 负责模型检测、OCR、颜色与证据融合；`src/runtime/aijs-adapter.js` 集中访问 AI.js Pro 宿主 API。模型文件是 `assets/models/chessai_160x320_v5r.onnx`；Pikafish 原生能力由宿主提供，NNUE 文件路径可配置。运行问题从入口、棋盘服务、驱动、引擎四层依次定位。

## 改动后验证

在本工程目录运行 `node tools/run-regress.js`，它会运行场景回归、自检和格子契约。真机可先运行 `tools/vision-pipeline-probe.js` 或面板的只识别模式；**完整自动对局需要另行验收**，离线测试不能替代点击回屏和内存观察。

日常工具见 [tools/README.md](tools/README.md)。23 个不再维护的 P4 一次性探针已移到仓库 `docs/archive/xiangqi-p4-probes/`，不再随 APK 内置示例发布。原长篇说明留在 `docs/archive/xiangqi-project-readme-before-simplification.md`，仅作历史记录。
