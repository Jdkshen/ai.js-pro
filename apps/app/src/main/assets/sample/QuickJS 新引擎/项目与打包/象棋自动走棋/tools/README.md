# 象棋工程工具

改 `src/**` 后先跑 `node tools/run-regress.js`。它统一运行 11 个场景、自检和格子契约；需要单独定位时用 `node tools/run-regress.js --only <关键字>`。

常用工具：

| 工具 | 用途 |
|---|---|
| `run-regress.js` | 默认回归入口 |
| `self-check.js` | 纯 JS 模块自检 |
| `test-cells-contract.js` | 90 格/三态与几何契约 |
| `vision-pipeline-probe.js` | 真机视觉链路取证 |
| `board-replay-test.js` | 保存帧回放 |
| `recognize-board.js` | 主页面与面板共用的定位/旧识别底座 |
| `locate-board.js` | 格线定位备选 |
| `analyze-panel-log.py` | 对局日志耗时和故障汇总 |

其他 `ocr-*`、`probe-*` 工具是专项诊断，需要对应问题时再运行。23 个历史 `p4-*.js` 已移到仓库 `docs/archive/xiangqi-p4-probes/`，不再是日常入口，也不会随示例打包。旧工具分类与实测背景保留在 `docs/archive/xiangqi-tools-readme-before-simplification.md`。
