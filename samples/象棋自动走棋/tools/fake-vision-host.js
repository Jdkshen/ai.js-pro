// @engine quickjs
/**
 * 视觉流水线的「假宿主」黑盒测试（场景回归 + self-check 共用）。
 * 这里只做定义，真正的场景在 `tools/regress/cases/*.js`。
 *
 * 为什么值得单独立一条：2026-09-19 真机上连着栽了两次都**不是**模块级逻辑错，
 * 而是**组装错**（框分没传进融合、融合器每帧新建）——
 * 模块级的自检全绿，整条流水线却一个格都确认不了。
 *
 * 2026-09-20 扩充（跟真宿主的新契约对齐）：
 *   · `prepareRegion(image, region, mode)` / `releaseFrame(frame)`：真宿主先裁剪再送 OCR；
 *     假宿主同样发一个「裁剪帧令牌」，`ocrSession.detect(令牌)` 能从令牌反推出是哪一格；
 *   · 每枚棋子可分别预设 **彩色**（`ocr`）与 **灰度**（`ocrGray`）两遍的读数：
 *     `ocrGray === undefined` 表示「灰度跟彩色一样」；显式 `null` 表示「灰度也读不出」；
 *   · `stats()` 报帧的创建/释放数（真机上忘了 recycle 会堆在原生帧表里）。
 */
"use strict";

function createFakeHost(options) {
    const opts = options || {};
    // 棋盘几何：原点 (100,100)，格距 120，RED_BOTTOM ⇒ (col,row) → (100+col*120, 100+row*120)
    const grid = {
        originX: 100, originY: 100, stepX: 120, stepY: 120,
        cols: 9, rows: 10, orientation: "RED_BOTTOM", version: 1
    };
    const pieces = opts.pieces || [
        { col: 0, row: 0, label: "K", ocr: { text: "帅", score: 0.92 }, side: "red" },
        { col: 1, row: 0, label: "K", ocr: { text: "将", score: 0.92 }, side: "black" },
        { col: 2, row: 0, label: "A", ocr: { text: "H", score: 0.85 }, side: "black" }
    ];
    const wood = [196, 168, 138];
    const red = [176, 44, 40];
    const dark = [58, 56, 54];
    /** 「检测框没检出」的格子（"col,row"）：真机上帅/将就经常这样（模型召回差） */
    const hiddenFromDetect = {};
    for (let i = 0; i < (opts.hiddenFromDetect || []).length; i++) {
        hiddenFromDetect[String(opts.hiddenFromDetect[i])] = true;
    }
    /** 帧计数：真机忘了 recycle 就会堆在原生帧表里，场景可以拿它断言「不泄漏」 */
    const stats = { created: 0, released: 0, clips: 0, grays: 0, detects: 0 };

    /**
     * 区域中心 → 格子。⚠️ 两种形状都要认：找色那条路传的是 `{x,y,width,height}`（对象），
     * OCR 的 `region` 必须是 `[x,y,w,h]`（数组）—— 假宿主两种都得支持，否则会静默判成「没有墨迹」。
     */
    function cellOfRegion(region) {
        if (!region) {
            return null;
        }
        const x = region.x != null ? Number(region.x) : Number(region[0]);
        const y = region.y != null ? Number(region.y) : Number(region[1]);
        const width = region.width != null ? Number(region.width) : Number(region[2]);
        const height = region.height != null ? Number(region.height) : Number(region[3]);
        if (!isFinite(x) || !isFinite(y) || !isFinite(width) || !isFinite(height)) {
            return null;
        }
        const col = Math.round((x + width / 2 - grid.originX) / grid.stepX);
        const row = Math.round((y + height / 2 - grid.originY) / grid.stepY);
        if (col < 0 || col > 8 || row < 0 || row > 9) {
            return null;
        }
        return { col: col, row: row };
    }

    function pieceAt(col, row) {
        for (let i = 0; i < pieces.length; i++) {
            if (pieces[i].col === col && pieces[i].row === row) {
                return pieces[i];
            }
        }
        return null;
    }

    function cellAt(x, y) {
        const col = Math.round((x - grid.originX) / grid.stepX);
        const row = Math.round((y - grid.originY) / grid.stepY);
        if (col < 0 || col > 8 || row < 0 || row > 9) {
            return null;
        }
        for (let i = 0; i < pieces.length; i++) {
            if (pieces[i].col === col && pieces[i].row === row) {
                return pieces[i];
            }
        }
        return null;
    }

    return {
        grid: grid,
        /** 框：只给有子的格（形状用原生 bounds 形态，顺便验 normalizeBoxes） */
        detect: function () {
            const out = [];
            for (let i = 0; i < pieces.length; i++) {
                const p = pieces[i];
                if (hiddenFromDetect[p.col + "," + p.row]) {
                    continue;      // 模拟「检测没检出这一格」（帅/将召回差）
                }
                out.push({
                    bounds: {
                        centerX: grid.originX + p.col * grid.stepX,
                        centerY: grid.originY + p.row * grid.stepY,
                        width: 100, height: 100
                    },
                    label: p.label,
                    score: 0.94
                });
            }
            return out;
        },
        /**
         * 假「找色」（`images.findAllPointsForColor` 的行为模拟）：按格子预设的阵营给命中数。
         * 为什么直接按阵营给，而不是真的算颜色距离：真机实测红/黑墨迹在原生找色下的命中数是**数量级**分开的
         * （红格 881~1227、黑格 493~769、空格 0，见 `docs/reports/2026-09-20_红黑判色改用找色.md`），
         * 这里复现那个分布 —— 自检考的是**链路**（区域是否跟着框心、密度门槛、红黑是否分开），
         * 而不是颜色空间本身。
         */
        countColor: function (image, color, options) {
            const region = options && options.region;
            if (!region) {
                return 0;
            }
            const cell = cellOfRegion(region);
            const piece = cell ? pieceAt(cell.col, cell.row) : null;
            if (!piece) {
                return 0;
            }
            const wantRed = piece.side !== "black";
            const isRedRef = String(color).toUpperCase() === "#83412D";
            if (isRedRef !== wantRed) {
                return 0;
            }
            const area = Math.max(1, Math.round(region.width) * Math.round(region.height));
            return Math.round(area * (wantRed ? 0.09 : 0.13));
        },
        clip: function () {
            return {};
        },
        /**
         * 真宿主的预处理契约：`prepareRegion(image, region, mode)` → 帧（调用方负责 releaseFrame）。
         * 假宿主返回一个**令牌对象**，`ocrSession.detect(令牌)` 能从它反推出是哪一格、哪一通道。
         * `opts.prepareRegion === false` 可以模拟「宿主没这个能力」（老路径）。
         */
        prepareRegion: function (image, region, mode) {
            stats.created += 1;
            if (mode === "gray") {
                stats.grays += 1;
            } else {
                stats.clips += 1;
            }
            return {
                __clipped: true,
                __cell: cellOfRegion(region),
                __gray: mode === "gray",
                mode: mode,
                region: region,
                recycled: false,
                recycle: function () {
                    if (this.recycled) {
                        return false;
                    }
                    this.recycled = true;
                    stats.released += 1;
                    return true;
                }
            };
        },
        releaseFrame: function (frame) {
            if (frame && typeof frame.recycle === "function") {
                return frame.recycle();
            }
            return false;
        },
        /** 帧账目：`leaked > 0` 说明有临时帧没还 */
        stats: function () {
            return {
                created: stats.created, released: stats.released,
                leaked: stats.created - stats.released,
                clips: stats.clips, grays: stats.grays, detects: stats.detects
            };
        },
        /** 木纹底 + 20% 笔画：模拟真机采样分布 */
        rgbAt: function (image, x, y) {
            const p = cellAt(x, y);
            if (!p) {
                return wood;
            }
            const inside = (Math.abs(x - (grid.originX + p.col * grid.stepX)) + Math.abs(y - (grid.originY + p.row * grid.stepY))) % 5;
            if (inside !== 0) {
                return wood;
            }
            return p.side === "black" ? dark : red;
        },
        /**
         * 假 OCR 会话：两个入口都支持（跟真宿主一致）——
         *   · `detect(整帧, {region})`：老路径（没裁剪能力时）；
         *   · `detect(裁剪帧令牌)`：新路径（先 clip / clip→gray）。
         * 读数按每枚棋子预设：彩色用 `ocr`，灰度用 `ocrGray`（未设 = 同彩色；显式 null = 也读不出）。
         */
        ocrSession: {
            detect: function (image, args) {
                stats.detects += 1;
                let cell = null;
                let gray = false;
                if (image && image.__cell) {
                    cell = image.__cell;
                    gray = !!image.__gray;
                } else if (args && args.region) {
                    if (typeof args.region.length !== "number" || args.region.length < 4) {
                        throw new Error("region must be [x, y, width, height]");
                    }
                    cell = cellOfRegion(args.region);
                } else {
                    throw new Error("detect 需要「裁剪帧」或「整帧 + region」");
                }
                if (!cell) {
                    return [];
                }
                const piece = pieceAt(cell.col, cell.row);
                if (!piece) {
                    return [];
                }
                const presets = [];
                // `ocrGray === undefined` 表示「灰度跟彩色一样」（见文件头契约）：灰度路拿不到预设就退回彩色预设
                presets.push(gray ? (piece.ocrGray !== undefined ? piece.ocrGray : piece.ocr) : piece.ocr);
                if (!gray && piece.ocr === undefined) {
                    presets.push(piece.ocrGray);
                }
                for (let i = 0; i < presets.length; i++) {
                    const one = presets[i];
                    if (one === undefined) {
                        continue;
                    }
                    if (one === null || one === false) {
                        return [];      // 显式「这一遍读不出」
                    }
                    return [{ text: one.text, score: one.score }];
                }
                return [];
            }
        },
        log: function () { }
    };
}

module.exports = { createFakeHost: createFakeHost };
