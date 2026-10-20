# R5.4 Node24 识别结果 vs QuickJS 基线的自动对照

> 生成：2026-10-20T08:40:55
> QuickJS 基线：`C:\Users\18101\Documents\ai-js-pro\output\three-engine-bench\quickjs-baseline-recognition.json` ｜ Node24 输出：`C:\Users\18101\Documents\ai-js-pro\output\three-engine-bench\r5-node24-recognition.rep3.json`
> 判定阈值：bbox IoU≥0.98；grid 原点≤2.0px/步距≤0.50px；逐格一致≥100%；counts/placement 要求严格相等。ms 只记录不判。

## 总判定：**PASS**

> Node24 capabilities：{"imageModule": true, "countColor": true, "matchTemplate": true, "analyzeXiangqiBoard": true, "detectXiangqiCircles": true, "yoloModule": true, "ocrModule": true, "shimErrors": {}}

| 输入 | 判定 | Node24 路径 | bbox IoU | grid 偏差 (origin/step) | 逐格一致 | counts(QuickJS→Node24) | placement | QuickJS ms → Node24 ms |
|---|---|---|---|---|---|---|---|---|
| f01 | **PASS** | B-recognizeImage | 1.0000 | (0.00,0.00)/(0.00,0.00) | 90/90 | 29/2 → 29/2 | 相等 | 367 → 681.0 |
| skin-a | **PASS** | B-recognizeImage | 1.0000 | (0.00,0.00)/(0.00,0.00) | 90/90 | 32/0 → 32/0 | 相等 | 364 → 496.0 |

## f01

- 全部检查项一致（bbox/grid/逐格/counts/placement）。

## skin-a

- 全部检查项一致（bbox/grid/逐格/counts/placement）。

---

说明：Node24 结果 JSON 约定为 `{input,bbox,grid,pieces[{row,col,side,kind,unknown}],placement,counts{pieces,unknown},ms}`；
对照取自 `quickjs-baseline-recognition.json`（skin-a 32 子/0 未知、f01 29 子/2 未知）。
