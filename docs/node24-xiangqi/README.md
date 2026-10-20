# Node24 象棋识别移植工具链（R5）

本目录存放「把象棋真实识别链跑在 Node24 上」的验证工具与基线，配套产品代码（`modules/engine` 下的 Node24 image/yolo 桥）使用。

## 文件
| 文件 | 说明 |
|---|---|
| `r5-node24/shim.node.js` | Node24 兼容层：QuickJS 风格帧对象（隐藏 getWidth/getHeight）、整帧像素缓存（exportPixelsSync→Uint32Array）、yolo 帧解包、matchTemplate 结果包 `{matches}` |
| `r5-node24/r5-node24-recognize.node24.js` | 识别探针：B 优先（chess `recognizeImage`）/ A 回退（glyph）；输出 frame-bench 约定 JSON + `R5_NODE24_DONE` |
| `r5-node24/perf-probe.node24.js` | 性能探针（共享 detector，3 次/帧，分阶段） |
| `r5-node24/analyze-probe.node24.js` | analyzeXiangqiBoard 直测探针 |
| `compare-recognition.py` | Node24 vs QuickJS 基线逐项对照（bbox IoU/grid/90 格/counts/placement），自测 `--self-test` |
| `run-r5-node24.ps1` | 一键：push 探针+bundle → 广播运行 → 拉结果 → 出 parity 报告（`-Repeats N`） |
| `quickjs-baseline-probe.js` + `.json` | QuickJS 识别基线（skin-a：32 子/0 未知；f01：29/2） |
| `decode-parity.md` / `yolo-parity.md` | 解码与 YOLO 逐项等价证据 |
| `r5-perf-report.md` / `r5-node24-port-notes.md` / `r5-recognition-parity.md` | 性能、移植笔记、最终 parity 报告 |
| `R5最终报告-Node24识别移植.md` | 里程碑总结 |
| `m2a-product-integration.md` | **M2a 产品集成**：QuickJS 面板→Node24 识别全链路 + 同帧 parity + 自动走棋实跑（PASS） |

## 前置
- 设备已装含 Node24 桥的构建（本仓库 `applications/` 构建产物，或旁路包 `com.jdkshen.aijspro.nodebench`）
- 设备上 `/sdcard/Download/three-engine-bench/` 有素材（`skin-a.png`、`r4-frames/f01.png`、`r5-chess/` 最小工程汇编）

## 典型用法
```powershell
# 1) strict parity（3 轮）
powershell -ExecutionPolicy Bypass -File tools\node24-xiangqi\run-r5-node24.ps1 -Repeats 3
# 2) 只对照已有结果
python tools\node24-xiangqi\compare-recognition.py --node24 <node24.json> --baseline tools\node24-xiangqi\quickjs-baseline-recognition.json
```

## 已验证结论
- Node24 原生 `recognizeImage` 与 QuickJS 基线：f01/skin-a **逐格 90/90**、bbox IoU 1.0、grid delta 0、counts/placement 全等（`r5-recognition-parity.md`）
- 生产路径稳态 **265–279ms/帧** vs QuickJS 364–367ms（**0.73–0.77×**，`r5-perf-report.md`）