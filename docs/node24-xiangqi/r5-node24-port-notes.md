# R5 Node24 移植探针状态与精确缺口（task-27）

> 2026-10-20 ｜ node-smem ｜ 只写 output/，旧仓库只读。
> 文件：output/three-engine-bench/r5-node24/shim.node.js、r5-node24-recognize.node24.js、本文件。
> 目标：在 Node24 上跑 chess recognizeImage(f01/skin-a)，输出 frame-bench 约定 JSON 到 /sdcard/Download/three-engine-bench/r5-node24-recognition.json，marker R5_NODE24_DONE。

## 1. 当前状态

- 探针与 shim 已写完、本地 node --check 通过；**等待 nodebench（R5 build）安装后真机跑**（当前设备未安装 com.jdkshen.aijspro.nodebench）。
- 探针对每个输入先尝试 **B 路径**（chess 原生：experiments/vision/recognize-board.js 的 recognizeImage(path)：canny-hough 定位 + YOLO 检测 + 识别）；若 B 失败则回退 **A 路径**（生产 glyph：lattice 定位 + 11 字形模板），并在 JSON 里标 source 与 bPath/aPath 明细。
- B 路径在当前代码上会缺两项 Node24 一等 API（task-26 正在补）：
  1) **yolo**：require('yolo') / 宿主 kind:'yolo'（旧 yolo.load({backend:'dnn',model,labels,inputWidth:160,inputHeight:320}) + detector.detect(image,{confidence,nms,region})）；
  2) **image.analyzeXiangqiBoardSync**（旧 images.analyzeXiangqiBoard：原生 Canny/Hough 9×10 几何；B 基线 method=canny-hough 就是它）。
  若这两项未就绪，B 会在 load/locate 阶段失败，探针自动改跑 A（glyph）并仍产出可对照 JSON + notes。
- shim 已提供：images（read/write/save/pixel/sizeOf/clip/grayscale/threshold/resize/countColor/matchTemplate 包装 + analyze/circles 按可用性暴露）、colors、files（join/path/getSdcardPath/ensureDir/append/read/write/listDir/... 用 Node fs/path）、engines、device、storages(create)、events(no-op on/off/bus)、threads、yolo(若模块存在)、sleep、context、shell(app UID stub)、requestScreenCapture/captureScreen(离线 M1 不启用)、floaty/ui stub、$ocr/$pikafish undefined、host 由 prelude 提供。
- 关键适配：旧 adapter 期望 `images.matchTemplate` 返回 `{matches:[...]}`；shim 把 Node24 的数组结果包成该形状，并把 `max` 映射到 `limit`。`images.readRaw/findAllPointsForColor` 不提供（旧 adapter 有能力探测/降级；countColor 存在）。

## 2. 运行

```
powershell
.\output\three-engine-bench\run-r5-node24.ps1 -Serial cccc62c7
```


（脚本 push r5-chess + r5-node24，跑 /sdcard/Download/three-engine-bench/r5-node24/r5-node24-recognize.node24.js，等 R5_NODE24_DONE，拉 r5-node24-recognition.json，调用 compare-recognition.py 出 parity。）

## 3. 预期差异（A-glyph vs QuickJS B 基线）

- B 基线定位 method=canny-hough，A 用 lattice；两套几何 bbox/grid 会有差异（严格对照 IoU≥0.98 可能不满足，属路径差异）。
- B 基线识别用 YOLO+字形/颜色；A 用纯 glyph 模板 + 墨色判阵营，未知格/阵营结果可能不同（尤其 f01 不同皮肤，glyph 模板会掉准确率）。
- 因此若 B 未就绪：探针输出标记 source=A-glyph、并在 notes 写明「B 缺失 API」，**不要**把 A 结果当作与基线逐格一致的证据；此时应看 r5-node24-port-notes.md 的缺口与 A 自身合理性。

## 4. 待补 Node24 能力（对应 task-26）

| 能力 | 现状 | 需要 |
|---|---|---|
| yolo | 无 require('yolo')/宿主 action；node-api 正在补 kind:'yolo' | Node24 一等 yolo 模块（load/detect） |
| image.analyzeXiangqiBoard | 无（task-26 正在移植 C++ xiangqi_board_detector.cpp） | image.analyzeXiangqiBoard(Sync) + Image.prototype |
| window capture / Shizuku / image→Bitmap | 无 | 后续端到端（M2+） |

（真机跑完后本文件会补：实际走 B 还是 A、失败阶段/原因、两输入 JSON 摘要、与基线差异。）


## 5. 真机首次结果（R5 build 2026-10-20 08:15:52，nodebench）

运行：output/three-engine-bench/run-r5-node24.ps1 -Serial cccc62c7 → 10s 完成；结果 output/three-engine-bench/r5-node24-recognition.json；parity：r5-recognition-parity.md（FAIL）。

capabilities（探针实测）：
- image/countColor/matchTemplate/analyzeXiangqiBoard/detectXiangqiCircles 均“函数存在”；yoloModule=true；ocrModule=true。
- **但 analyzeXiangqiBoard 实际返回 ok=false**：f01/skin-a 都是 reason=“格线证据不足或横竖格距不一致”，ms≈349/350（见 r5-node24/r5-node24-analyze-diag.json 与 analyze-probe.log）。
- 因此 B 路径的定位回退到 **lattice**（locate method=lattice），不是基线的 canny-hough；随后 B 识别本身跑通（yolo detect 成功，detectionCount=34/33，输出 layout）。
- 对比基线：f01 28/3 vs 29/2（逐格 78/90），skin-a 31/1 vs 32/0（逐格 82/90）；grid 差异 f01 origin(-3.69,-1.20)/step(0.22,-0.80)，skin-a origin(0.31,-3.15)/step(-0.03,-0.71) → **主因是定位几何差异（lattice vs canny-hough），不是 yolo/识别代码本身**。
- 耗时：f01 3649ms（B 内部 elapsedMs 1202 + lattice 定位约 2.4s），skin-a 2782ms；QuickJS 基线 367/364ms。Node24 慢的主因是 lattice countColor 的逐次桥调用（~2.4s）+ recognizer 的逐像素 rgbAt（~1.1s），属 host call 批处理优化项（可用 getPixels/samplePixels 批量取色、或等 analyzeXiangqiBoard 修好后用 canny-hough 定位省掉 lattice 扫描）。

**精确缺口（给 node-api）**：
1. image.analyzeXiangqiBoardSync 当前实现（Node24Images.analyzeBoard）在两个输入上均失败在“格线证据不足或横竖格距不一致”；需暴露失败时的 lineScoreX/lineScoreY/verticalSegments/horizontalSegments/supported/outer 等中间字段并与 C++ 对齐（C++ 参考 modules/engine/src/main/cpp/xiangqi_board_detector.cpp；基线 QuickJS grid：f01 84.565/699.2/114.63/114.4，skin-a 80.565/669.155/114.88/114.31）。
2. 修好后重跑本探针 → B 路径应使用 canny-hough 几何，parity 应显著改善（预期接近基线；若仍差再查 yolo/detect/autoAlign）。
3. 性能：analyze 本身 349ms（可接受但比 QuickJS 慢），lattice 回退 ~2.4s、recognizer 1.1s 需后续批处理优化。


## 9. task-30 性能优化（R5e build，2026-10-20）

- 优化点（只改 output/three-engine-bench/r5-node24/shim.node.js + perf 探针）：
  1) **按帧整帧像素缓存**：`image.exportPixelsSync(frame,{format:'argb'})`（pixelsToFile 文件通道，1 次桥调用 + `fs.readFileSync` 10MB → Uint32Array，ARGB little-endian；3 点与 pixelSync 校验字节序）→ 后备 `getPixelsSync` 256×256 分块（单次 256KB 上限）→ 再后备逐点 pixelSync；`frame.recycle()` 清缓存。`images.pixel`/adapter.rgbAt 全走缓存；200 随机点对照 0 mismatch。
  2) **detector 复用**：canonical/perf 探针一次 `board.loadDetector({})`（**81ms**），后续 `recognizeImage(path,{detector,detectorRequest})`；不再每帧 load。
- 阶段（perf 探针，共享 detector，3 次/帧）：require shim+chess **34ms**；load detector **81ms**；locate 241–276ms（其中 analyzeXiangqiBoard 单测 **273–323ms**，最大头）；detect 15–29ms；side **40–61ms**（优化前 359–381ms）；assign+FEN 1–35ms。
- 结果：稳态单帧 **352–416ms**（f01 408–462、skin-a 352–380）；QuickJS 基线 367/364ms ≈ 0.96–1.13×。canonical parity 探针 644–822ms（含每帧额外 analyzeDiag ~300ms）且仍 **PASS 90/90**。
- 目标 ≤800ms（最好 ≤600）达成（稳态）；剩余最大头是 analyzeXiangqiBoard 273–323ms（宿主 Java Canny/Hough），可交给 node-api 继续优化；glyph A 路径模板缓存未在 B 路径使用。
- 产物：r5-perf-report.md、r5-perf.json、r5-node24-perf.json、r5-node24/perf-probe.node24.js。


## 10. 新 build 复验注意（2026-10-20 08:35:25，task-31 analyze 优化）

- perf 数据在 R5e（08:30）测得；08:35:25 又装了 task-31 analyze 优化 build：analyze **f01 208ms / skin-a 184ms**（R5e 323/295ms）。
- skin-a raw analyze grid 不变（80.565,666.155,114.88,114.31）→ parity PASS。
- **f01 analyze raw grid 回归**：(81.565,699.2,114.63,113.65) vs QuickJS raw (81.565,696.2,114.63,114.4)；autoAlign 想 dx3/dy6（9px>maxApply6）被拒 → 最终 grid 与基线差 originX -3.0 / stepY -0.75（bbox IoU 0.9881，cells 90/90）。需 node-api 在 task-31 修复 analyze 拟合精度；不要用 Node24 输出刷新基线/放宽 comparator。
- 证据：r5-node24-analyze-diag-r5f.json；frame-bench 3 轮独立复验 f01 733/646/659ms、skin-a 516/520/538ms（中位 659/520），每轮 cells 90/90。


## 11. R5g 最终验证（2026-10-20 08:39:11 build）

- analyze 正确性恢复：f01 raw grid=(81.565,696.2,114.63,114.4)、skin-a=(80.565,666.155,114.88,114.31)、circles=32；直接 analyze f01 **217ms**/skin-a **146ms**（debug 探针 326/202ms 含 debug 开销）。
- strict parity **PASS**：f01/skin-a 90/90、bbox IoU 1、grid delta 0、counts/placement 全等；canonical 探针 f01 661ms/skin-a 459ms（含每帧诊断 analyze）。
- perf（共享 detector，3 次/帧）：稳态 **f01 277/279ms、skin-a 265/272ms**（首帧 350/280）；locate 141–165、detect 16–26、side 41–59、assign+FEN 1–19；require 33、detector load 66；缓存 200 点 0 mismatch。
- 证据：r5-node24-analyze-diag-r5g.json、r5-node24-perf.json、r5-recognition-parity.md/.json。


## 12. R5h 最终验收（独立复验，2026-10-20 08:40:28 build）

- frame-bench `-Repeats 3`：**strict parity 3/3 轮全 PASS**（f01/skin-a 90/90、bbox IoU 1、grid delta 0、counts 29/2 & 32/0、placement 相等；detection 34/32；autoAlign f01 3/3、skin-a 0/3）。
- canonical perf（含 analyzeDiag 诊断）：f01 847/658/681ms（中位 681）、skin-a 501/533/496ms（中位 501）→ ≤800/≤600 达成。
- 生产稳态（perf probe，共享 detector、无诊断 analyze）：f01 277/279ms、skin-a 265/272ms ≈0.73–0.77× QuickJS；引用时需注明 canonical 含 analyzeDiag。
- 证据：r5-recognition-parity.md/.json（PASS）、r5-recognition-parity.rep{1,2,3}.*、r5-node24-recognition.rep{1,2,3}.json、r5-recognition-parity.r5h.*、README §19；r5-perf-report.md §9、r5-perf.json.finalVerificationR5h。
