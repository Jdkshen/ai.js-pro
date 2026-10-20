# Node24 vs QuickJS YOLO 逐框 parity + assign/autoAlign 逻辑摘录（task-29）

> 2026-10-20 ｜ node-smem ｜ 模型：`assets/models/chessai_160x320_v5r.onnx`（160x320，7 类 R N B A K C P），同一帧 f01 / f01-noicc / skin-a。
> 结论先行：**YOLO 宿主逐框完全一致**；B 路径此前的 33 vs 32 差异来自**检测区域选择**（chess detection-stage 用 `typeof image.getWidth === 'function'` 判断整屏/棋盘窗口），不是 yolo 模型/宿主。

## 1. 逐框数据（同 detector、同 region、同参数）

| 输入 | 参数 | Node24 框数 | QuickJS 框数 | 逐框 JSON 全等 | class 不同 | score 不同 | 最高 IoU 最低值 |
|---|---|---|---|---|---|---|---|
| f01 | conf=0.001 nms=0.001 | 123 | 123 | **是（逐字节）** | 0 | 0 | 1.0000（同序逐框，bounds/score 都相同） |
| f01-noicc | conf=0.001 nms=0.001 | 123 | 123 | **是（逐字节）** | 0 | 0 | 1.0000（同序逐框，bounds/score 都相同） |
| skin-a | conf=0.001 nms=0.001 | 86 | 86 | **是（逐字节）** | 0 | 0 | 1.0000（同序逐框，bounds/score 都相同） |
| f01 | conf=0.25 nms=0.45 | 34 | 34 | **是（逐字节）** | 0 | 0 | 1.0000（同序逐框，bounds/score 都相同） |
| f01-noicc | conf=0.25 nms=0.45 | 34 | 34 | **是（逐字节）** | 0 | 0 | 1.0000（同序逐框，bounds/score 都相同） |
| skin-a | conf=0.25 nms=0.45 | 33 | 33 | **是（逐字节）** | 0 | 0 | 1.0000（同序逐框，bounds/score 都相同） |

- 原始探针：`r5-node24/yolo-dump.node24.js`、`r5-node24/yolo-dump.quickjs.js`；数据：`r5-yolo-node24.json`/`r5-yolo-quickjs.json`（0.001）与 `r5-yolo025-node24.json`/`r5-yolo025-quickjs.json`（生产 0.25/0.45）。
- 结论：Node24 task-26 的 yolo host 是 QuickJS 的**逐字节复刻**（OpenCV DNN 同一预处理/NMS/坐标），不是 parity 差异来源。

## 2. B 路径 33 vs 32 的真正根因：检测区域选择

`src/vision/detection-stage.js`（QuickJS 原逻辑）：

```js
/** 把棋盘外框扩成目标宽高比，保持中心并夹在图像内。 */
function fitAspect(box, aspect, image) {
    if (!aspect || !box || !(aspect > 0)) {
        return box;
    }
    const maxWidth = image && typeof image.getWidth === "function" ? image.getWidth() : null;
    const maxHeight = image && typeof image.getHeight === "function" ? image.getHeight() : null;
    let width = box.width;
    let height = box.height;
    if (width / height < aspect) {
        width = height * aspect;
    } else {
        height = width / aspect;
    }
    const centreX = box.x + box.width / 2;
    const centreY = box.y + box.height / 2;
    let x = Math.round(centreX - width / 2);
    let y = Math.round(centreY - height / 2);
    width = Math.round(width);
    height = Math.round(height);
    if (maxWidth != null && maxHeight != null) {
        x = Math.max(0, Math.min(x, Math.max(0, maxWidth - width)));
        y = Math.max(0, Math.min(y, Math.max(0, maxHeight - height)));
        width = Math.min(width, maxWidth);
        height = Math.min(height, maxHeight);
    }
    return { x: x, y: y, width: width, height: height };
}

function regionBox(options) {
    const opts = defaults.mergeDefaults(DEFAULTS, options);
    if (opts.regionMode === "full" && opts.image && typeof opts.image.getWidth === "function") {
        const width = opts.image.getWidth();
        const height = opts.image.getHeight();
        if (width > 0 && height > 0) {
            return { x: 0, y: 0, width: width, height: height };
        }
    }
    return fitAspect(geometry.boundsOf(opts.grid, opts.cropMargin), opts.regionAspect, opts.image);
}
```

- QuickJS `NativeFrame` 只有 width/height/pixelWidth/pixelHeight/pixel/copy/saveTo/recycle，**没有 getWidth/getHeight** → `regionBox` 走 `fitAspect(board bounds, 160/320)`，即 **board-aspect 竖窗**；
- Node24 `require('image')` 的 Image 有 getWidth/getHeight → 走 `regionMode='full'` 的**整屏区域**。同一 detector、同帧、不同区域 → skin-a 33 vs 32；f01 数量巧合同为 34 但框集不同，于是 autoAlign/assignment 下游出现 +3px 与逐格差异。
- 修复（R5e + shim）：shim `images.read` 返回 **QuickJS 风格帧包装**（暴露 width/height/pixelWidth/pixelHeight/pixel/copy/saveTo/recycle/recycled，**不暴露 getWidth/getHeight**）；prelude yolo.detect 对越界 board 窗口做 QuickJS `pixelRegionOf` 同款 clamp（skin-a 未裁剪 [-6,89,1091,2182]、f01 [-4,122,1089,2178]）。

验证（node-api task-28 三区域 + 本机 region probe）：

| 帧 | 无 region | full 区域 | board-aspect（clamp 后） | B 路径最终 | QuickJS 基线 |
|---|---|---|---|---|---|
| f01 | 34 | 34 | 34 | **34** | 34 |
| skin-a | 33 | 33 | **32** | **32** | 32 |

## 3. QuickJS recognizer 的 assign / autoAlign 关键逻辑（给 node-api）

### 3.1 autoAlign = 只用检测框集做格点平移搜索（纯几何）

```js
function alignGrid(detections, grid, assignOptions, search) {
    const cfg = search || {};
    const maxShift = cfg.maxShift == null ? 18 : cfg.maxShift;
    const step = cfg.step == null ? 3 : cfg.step;
    /** 小于这个位移（|dx|+|dy|，像素）只当诊断，不改执行几何（同一格内不会变，免得票据天天失效） */
    const minApply = cfg.minApply == null ? 3 : cfg.minApply;
    /** 低于这个分数的框不参与几何拟合（实测 0.2 以下的框基本都是假的） */
    const floorScore = cfg.floorScore == null ? 0.2 : cfg.floorScore;
    /** 至少要改善这么多（相对）才真的挪：抑制「同一格内来回微调」 */
    const minGain = cfg.minGain == null ? 0.15 : cfg.minGain;
    /**
     * 允许真的挪的最大位移（|dx|+|dy|，像素）。超过就拒绝 —— 理由见上方注释：
     * 几何同时是颜色采样的基准，大位移会把木纹采进采样框，把黑子判成红子。
     * 真的整体错位（几十像素）不是靠微调救的，那是 suspect → 重新定位的活。
     */
    const maxApply = cfg.maxApply == null ? 6 : cfg.maxApply;
    const baseVersion = grid.version == null ? 1 : grid.version;

    const baseCost = latticeCost(detections, grid, floorScore);
    let best = { dx: 0, dy: 0, cost: baseCost };
    let tried = 1;
    for (let dx = -maxShift; dx <= maxShift; dx += step) {
        for (let dy = -maxShift; dy <= maxShift; dy += step) {
            if (dx === 0 && dy === 0) {
                continue;
            }
            const candidate = Object.assign({}, grid, {
                originX: grid.originX + dx,
                originY: grid.originY + dy
            });
            const cost = latticeCost(detections, candidate, floorScore);
            tried += 1;
            // 代价更小 → 更贴合；代价相同则取位移更小的（同样的贴合度，别多挪）
            const closer = cost.cost < best.cost.cost - 1e-9;
            const tieBreak = Math.abs(cost.cost - best.cost.cost) <= 1e-9 &&
                (Math.abs(dx) + Math.abs(dy)) < (Math.abs(best.dx) + Math.abs(best.dy));
            if (closer || tieBreak) {
                best = { dx: dx, dy: dy, cost: cost };
            }
        }
    }
    const shift = Math.abs(best.dx) + Math.abs(best.dy);
    const gain = baseCost.cost > 0 ? (baseCost.cost - best.cost.cost) / baseCost.cost : 0;
    const worthwhile = baseCost.cost > 0 && gain >= minGain;
    /** 位移太大 → 拒绝（见 maxApply 的说明）；仍把最好的偏移与增益报出来，便于诊断 */
    const tooLarge = worthwhile && shift > maxApply;
    const moved = worthwhile && !tooLarge && (best.dx !== 0 || best.dy !== 0);
    const applied = moved && shift >= minApply;

    const bestGrid = moved ? Object.assign({}, grid, {
        originX: grid.originX + best.dx,
        originY: grid.originY + best.dy
    }) : grid;
    const assigned = assignment.assign(detections, bestGrid, assignOptions);
    // 基础几何的分配只为报「这次对齐认多了几个子」；没挪就不用再算一遍
    const baseAssigned = moved ? assignment.assign(detections, grid, assignOptions) : assigned;
    let appliedGrid = grid;
    if (applied) {
        appliedGrid = geometry.createGrid({
            originX: bestGrid.originX,
            originY: bestGrid.originY,
            stepX: bestGrid.stepX,
            stepY: bestGrid.stepY,
            orientation: bestGrid.orientation,
            version: baseVersion + 1,
            quality: bestGrid.quality
        });
        appliedGrid.alignedFrom = baseVersion;
    }
    const assignedAtApplied = assigned;
```

### 3.2 调用点：autoAlign → assign → 逐格判阵营

```js
    // 检测区域、矩形模型宽高比与坐标回移由单一底座负责；新流水线也走同一实现。
    const detected = detectionStage.detect(opts);
    if (!detected.ok) {
        return detected;
    }
    const box = detected.box;
    const detections = detected.detections;
    timing.cropMs = detected.timing.cropMs;
    timing.detectMs = detected.timing.detectMs;

    const assignOptions = {
        minScore: opts.minScore,
        maxResidual: opts.maxResidual,
        conflictGap: opts.conflictGap,
        frameQuality: opts.frameQuality
    };
    // 几何微调（定位系统性偏差的兜底）：只花一次检测的钱，所以可以逐帧跑。
    const aligned = opts.autoAlign === true
        ? alignGrid(detections, opts.grid, assignOptions, opts.alignSearch)
        : null;
    const grid = aligned ? aligned.grid : opts.grid;
    const assigned = aligned ? aligned.assigned : assignment.assign(detections, grid, assignOptions);
    // 逐格判阵营（只对「有子」的格子采样，空格不花这个钱）
    const sideStart = Date.now();
    const sideOf = new Array(90);
    const sides = [];
    for (let index = 0; index < 90; index++) {
        const cell = assigned.cells[index];
        if (cell.state !== "PIECE") {
            continue;
        }
        const col = index % geometry.COLS;
        const row = Math.floor(index / geometry.COLS);
        if (typeof opts.rgbAt !== "function") {
            sideOf[index] = { side: pieceSide.SIDE_UNKNOWN, reason: "没有取像素的能力" };
            continue;
        }
        const result = pieceSide.classifyAt(function (x, y) {
            return opts.rgbAt(opts.image, x, y);
        }, grid, col, row, { sampleStep: opts.sideSampleStep });
        sideOf[index] = result;
        sides.push({
            col: col, row: row, piece: cell.piece, side: result.side,
            redShare: result.redShare, darkShare: result.darkShare,
            counted: result.counted, reason: result.reason
        });
    }
```

- 关键：`alignGrid` 的输入只有 `detections` + `grid`；检测框集一旦一致（本 task 已证明 yolo 逐字节一致），autoAlign 的 dx/dy/cost 就完全确定。
- R5e 实测（本机探针 `r5-node24-recognition.json`）：f01 autoAlign dx=3,dy=3，skin-a dx=0,dy=3，与 QuickJS 基线 stats.autoAlign 完全相同（costBefore/costAfter/residual 也相同）；skin-a 之前的 +3 是框集不同导致的下游结果，修 shim 后自然归零。

## 4. R5e 最终 B 路径对表（PASS 证据）

### f01
- detection/native count：**34 / 34**（QuickJS 基线 f01=34、skin-a=32）
- located raw grid：{"originX":81.565,"originY":696.2,"stepX":114.63,"stepY":114.39999999999999,"orientation":"RED_BOTTOM","version":1}
- final grid：{"originX":84.565,"originY":699.2,"stepX":114.63,"stepY":114.39999999999999,"orientation":"RED_BOTTOM"}
- autoAlign：{"dx":3,"dy":3,"tried":169,"costBefore":1.3255,"costAfter":0.5538,"residual":0.628500707069909,"applied":true,"versionBefore":1,"versionAfter":2}
- placement：rn1akabn1/3?5/2c1c2?1/p1p1p1p1p/9/2P6/P3P1P1P/CC7/9/1NBAKABNR（与 QuickJS 基线相同；逐格 90/90）
- ms：1898ms（QuickJS 基线 367ms）；bPath recognize elapsedMs=1176ms

### skin-a
- detection/native count：**32 / 32**（QuickJS 基线 f01=34、skin-a=32）
- located raw grid：{"originX":80.565,"originY":666.155,"stepX":114.88,"stepY":114.31,"orientation":"RED_BOTTOM","version":1}
- final grid：{"originX":80.565,"originY":669.155,"stepX":114.88,"stepY":114.31,"orientation":"RED_BOTTOM"}
- autoAlign：{"dx":0,"dy":3,"tried":169,"costBefore":1.0106,"costAfter":0.5294,"residual":0.5842409596077044,"applied":true,"versionBefore":1,"versionAfter":2}
- placement：Rnbakab1R/9/3C2cC1/P1P1P1P1P/9/9/P1P1P1P1P/2N1C2C1/9/r1BAKABNR（与 QuickJS 基线相同；逐格 90/90）
- ms：1836ms（QuickJS 基线 364ms）；bPath recognize elapsedMs=1161ms

## 5. 产物索引

- `r5-yolo-node24.json` / `r5-yolo-quickjs.json`（conf .001/.001，三帧 123/123/86 框，逐字节相同）
- `r5-yolo025-node24.json` / `r5-yolo025-quickjs.json`（生产 .25/.45，34/34/33 框，逐字节相同）
- `r5-region-node24.json` / `r5-region-quickjs.json`（负 region 越界测试：QuickJS f01=34/skin=32；Node24 修复前抛 region.x<0，R5e prelude clamp 后同 34/32）
- `r5-node24-recognition.json` + `r5-recognition-parity.md/.json`（f01/skin-a 90/90 PASS，bbox IoU 1.0，grid delta 0）