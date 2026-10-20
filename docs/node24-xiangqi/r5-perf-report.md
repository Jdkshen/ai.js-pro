# R5 Node24 识别性能优化报告（task-30）

> 2026-10-20 ｜ node-smem ｜ 设备 K40 (cccc62c7) ｜ 阶段数据在 R5e（ICC + yolo region clamp，08:30 build）测得；08:35:25 task-31 build 的独立复验见 §7。QuickJS 基线 367/364ms。
> 范围：只改 `output/three-engine-bench/r5-node24/shim.node.js` + 探针；未改新仓库源码、未装 gradle。

## 1. 结论

- **稳态单帧 352–416ms**（f01 408/416/462ms、skin-a 352/357/380ms，共享 detector；第 2/3 次为稳态），对比 QuickJS 367/364ms ≈ **0.96–1.13×**；首帧 380–462ms（含首次像素缓存填充/JIT）。
- **canonical parity 探针（已做 detector 复用）从 1839–2252ms 降到 644–822ms，且 parity 仍 PASS 90/90**（f01 822ms、skin-a 644ms）。注：canonical 探针每帧还额外跑一次 analyzeDiag（约 290–323ms，为 task-29 留的诊断），生产识别路径不含这次重复 analyze；去掉后即本报告 §2 的稳态 352–416ms。
- 目标 ≤800ms（最好 ≤600）在稳态下达成：perf 探针 352–416ms；canonical 探针 skin-a 644ms、f01 822ms（含每帧额外 analyzeDiag ~300ms 与首帧缓存填充，生产路径不含）。
- 两项优化：① shim 按帧**整帧像素缓存**（pixelsToFile 文件通道 1 次桥调用 + 本地 readFile，Uint32Array；后备 256×256 getPixelsSync 分块）；② **detector 复用**（一次 load 81ms，后续 opts.detector 注入）。

## 2. 阶段分解（共享 detector，3 次/帧）

| 帧 | run | total | locate(含analyze) | analyze(单测) | detect(native) | side(rgbAt) | assign+FEN |
|---|---|---|---|---|---|---|---|
| f01 | 0 | 462 | 261 | 323 | 29 | 61 | 24 |
| f01 | 1 | 416 | 276 | 323 | 18 | 46 | 4 |
| f01 | 2 | 408 | 271 | 323 | 21 | 40 | 3 |
| skin-a | 0 | 380 | 272 | 273 | 19 | 41 | 1 |
| skin-a | 1 | 357 | 256 | 273 | 15 | 41 | 1 |
| skin-a | 2 | 352 | 241 | 273 | 21 | 42 | 1 |

启动开销：require shim+chess 模块 **34ms**；board.loadDetector **81ms**（之后复用）。
定位耗时 ~240–276ms 中 analyzeXiangqiBoard 单测 273–323ms 是最大头（Java 移植的 Canny/Hough 分析），detect 仅 15–29ms，side 已从 359–381ms 降到 **40–61ms**。

## 3. 优化 1：按帧像素缓存（shim）

- 识别路径的阵营采样约 3.2 万次 `images.pixel`（32 格 × ~1024 点/格）；旧实现每次一次 Node24 桥调用 → sideMs 359–381ms。
- 现在 `images.read` 返回的帧包装首次取像素时：
  1) 首选 `image.exportPixelsSync(frame,{format:'argb'})`（pixelsToFile）整帧导出到宿主私有文件（ARGB int little-endian），脚本用 `fs.readFileSync` 读回构造 `Uint32Array`；用 3 个点与 `pixelSync` 对照校验字节序，失败则降级。
  2) 降级：`image.getPixelsSync(frame,{region:[...],format:'argb'})` 按 256×256 分块（单次 256KB 上限）缓存。
  3) 再降级：逐点 `pixelSync`（保持兼容）。
- `pixel()`/`colors` 都从缓存读；`frame.recycle()` 清空缓存。实测 200 随机点缓存 vs `pixelSync` **0 mismatch**（f01/skin-a）。
- sideMs：**359–381ms → 40–61ms**（约 8–9×）；整帧像素缓存只发生 1 次桥调用 + ~10MB 本地文件读。

## 4. 优化 2：detector / 启动开销复用

- 旧 canonical 探针对每张图调用 `recognizeImage(path,{})`，内部每帧 `loadDetector` 重新 load 模型（~69–81ms/次）。
- 新 perf 探针：`board.loadDetector({})` 一次（**81ms**，含模型加载），随后 `recognizeImage(path,{detector,detectorRequest})` 复用；首次/稳态差异见上表。
- 模块加载（shim + chess require）仅 **34ms**；此前 ~650ms 的「其余耗时」主要是**首次模块加载 + 每帧模型 load**，不是识别链本体。

## 5. 前后对比

| 指标 | 优化前 | 优化后 |
|---|---|---|
| canonical 探针 f01（含诊断 analyze） | 1846–2252ms | **822ms** |
| canonical 探针 skin-a（含诊断 analyze） | 1809–1839ms | **644ms** |
| 稳态（共享 detector，perf 探针） | — | **352–416ms**（f01 408–462，skin-a 352–380） |
| sideMs | 359–381ms | **40–61ms** |
| detector load | 每帧 ~69–81ms | 一次 **81ms** |
| parity | PASS 90/90 | **PASS 90/90（回归通过）** |

## 6. 复现与产物

- shim：`output/three-engine-bench/r5-node24/shim.node.js`（帧包装 + 整帧/分块像素缓存 + yolo detector 解包/复用支持）。
- perf 探针：`output/three-engine-bench/r5-node24/perf-probe.node24.js`；数据：`r5-node24-perf.json`；本报告：`r5-perf-report.md` / `r5-perf.json`。
- canonical parity：`run-r5-node24.ps1`（frame-bench 可 `-Repeats 3` 独立复验）；`r5-recognition-parity.md/.json` PASS。
- 已知后续：analyzeXiangqiBoard 273–323ms 是剩余最大头（宿主 Java Canny/Hough），node-api 可继续优化；glyph A 路径模板缓存未在 B 路径使用（本报告不涉及）。

## 7. 独立复验：frame-bench -Repeats 3（2026-10-20 08:35:25 build）

frame-bench 用 `run-r5-node24.ps1 -Repeats 3` 在**新 build（08:35:25，task-31 analyze 优化）**上复验：

| 轮次 | f01 ms | skin-a ms | 逐格 | 备注 |
|---|---|---|---|---|
| rep1 | 733 | 516 | 90/90 | counts/placement 全等 |
| rep2 | 646 | 520 | 90/90 | 同上 |
| rep3 | 659 | 538 | 90/90 | 同上 |
| 中位 | **659** | **520** | — | 达到 ≤800 / ≤600 目标 |

- **性能结论不变/更好**：canonical 探针中位 659/520ms（我 R5e 上 822/644；新 build analyze 更快，f01 208ms、skin-a 184ms）。
- **但 f01 严格 grid parity 在新 build 下回归**：f01 analyze raw grid = (81.565, **699.2**, 114.63, **113.65**)，QuickJS raw = (81.565, **696.2**, 114.63, **114.4**)；autoAlign 想 dx=3/dy=6（9px）但被 maxApply=6 拒绝 → 最终用 analyze 原始几何；strict FAIL 仅 grid（originX -3.0、stepY -0.75，bbox IoU 0.9881 仍过 0.98），cells 仍 90/90。skin-a analyze/side 几何不变，PASS。
- **建议**：让 node-api 在 task-31 优化里恢复 f01 analyze 拟合精度（raw grid 与 QuickJS 完全一致），**不要**用 Node24 新输出刷新 QuickJS 基线或放宽 comparator；识别语义未变（90/90）。
- 证据：`r5-node24-analyze-diag-r5f.json`（本机新 build analyze 实测 208/184ms 与 raw grid）、frame-bench 的 `r5-recognition-parity.rep{1,2,3}.*`。


## 8. R5g 最终验证（2026-10-20 08:39:11 build，analyze 正确性优先版）

- Lead 重装 R5g 后按顺序复验：
- **① analyze-probe**：f01 raw grid **恢复 (81.565,696.2,114.63,114.4)**（=QuickJS raw）、skin-a (80.565,666.155,114.88,114.31) 不变、circles=32；debug 模式耗时 f01 326ms / skin-a 202ms（debug 开销），perf 探针内直接 analyze 为 **f01 217ms / skin-a 146ms**。
- **② strict parity：PASS**（R5_NODE24_PARITY_PASS）：f01/skin-a 均 **90/90**、bbox IoU 1.0000、grid delta 0、counts 29/2 与 32/0、placement 全等；canonical 探针 ms **f01 661 / skin-a 459**（含每帧 analyzeDiag 诊断 ~217/146ms）。
- **③ perf-probe（共享 detector，3 次/帧）**：稳态 **f01 277/279ms、skin-a 265/272ms**（首帧 350/280）；locate 141–165ms、detect 16–26ms、side 41–59ms、assign+FEN 1–19ms；require 33ms、detector load 66ms；像素缓存 200 点 0 mismatch。
- 结论：R5g 同时恢复 strict parity（几何正确）并把稳态压到 **265–279ms**（QuickJS 364/367ms ≈ **0.73–0.77×**），远优于 ≤600ms 拉伸目标；task-30 性能目标与 task-31 正确性目标均达成。
- 证据：r5-node24-analyze-diag-r5g.json、r5-node24-perf.json、canonical r5-recognition-parity.md/.json。


## 9. R5h 最终验收（独立复验，2026-10-20 08:40:28 build）

- frame-bench `run-r5-node24.ps1 -Repeats 3`：**strict parity 3/3 轮全 PASS** — f01/skin-a 90/90、bbox IoU 1.0000、grid delta 0、counts 29/2 与 32/0、placement 相等、source=B-recognizeImage、aPath=null；detection f01 34/skin-a 32；autoAlign f01 dx3/dy3、skin-a dx0/dy3；analyze f01 raw grid=(81.565,696.2,114.63,114.4)、skin-a 不变。
- canonical 探针（**含每帧 analyzeDiag 诊断调用**）perf：f01 **847 / 658 / 681ms**（中位 681，min 658/max 847，首轮冷启动）、skin-a **501 / 533 / 496ms**（中位 501，min 496/max 533）→ ≤800/≤600 达成；对比 QuickJS 367/364ms = 1.86×/1.38×。
- **生产稳态**（perf probe，共享 detector，无诊断 analyze）：f01 **277/279ms**、skin-a **265/272ms** ≈ **0.73–0.77× QuickJS**。引用时不要把 canonical 681/501 与稳态 277/265 混用：canonical 含 analyzeDiag。
- 证据：canonical r5-recognition-parity.md/.json（PASS）、r5-recognition-parity.rep{1,2,3}.*、r5-node24-recognition.rep{1,2,3}.json、r5-recognition-parity.r5h.*；README §19 已分栏记录。
- 结论：R5 识别移植验收闭环（correctness + perf 两栏 PASS）。
