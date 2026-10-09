// @engine quickjs
/**
 * 全盘扫描（OCR 字形 + 像素颜色 → 带红黑的盘面字符）。
 *
 * 为什么要单独一个模块（2026-09-20 真机事故）：
 *   面板原来给驱动的「全盘 OCR 盘面」是**只带兵种、不带红黑**的 `cls`（全大写）。
 *   驱动的「模型漏检、OCR 看到字 ⇒ 补上」那条分支直接把它当 FEN 字符用 ⇒
 *   补出来的子**全是红方**（日志：`全盘 OCR 改正 26 格：(0,0)空→R (1,0)空→N …`），
 *   而且还会登记成已知事实、顺手改可靠基准 ⇒ 错颜色被钉进基准与票据，后手再也解释不通。
 *
 * 方案 §3.6/§13 的规矩：**字形只回答「哪一类」，红黑只能来自像素**（天天象棋里黑炮也写「炮」）。
 * 颜色判不出来就输出 `null`（不猜），让调用方走自己的兜底，绝不硬塞一个颜色进去。
 *
 * 纯几何/纯函数部分（`sideFor`）离线可测；真机的 $ocr 会话与取像素由宿主注入。
 */
"use strict";

const pieceOcr = require("./piece-ocr.js");
const cropper = require("./piece-cropper.js");
const pieceColor = require("./piece-color.js");
const inkColor = require("./ink-color.js");

const DEFAULTS = {
    /** OCR 认到这个分数才采信（与面板历史行为一致） */
    minScore: 0.6,
    /** 认到字之后，用哪个比例的中心区采颜色（与流水线一致：0.70） */
    colourRatio: 0.70,
    /** 颜色证据不足时是否允许退回「未知」而不是猜颜色 */
    allowUnknownSide: true,
    /** 交给 ink-color 的选项（找色参考色/容差/门槛等） */
    inkOptions: null,
    /**
     * OCR **主读通道**（2026-09-21 用户口径「先灰度」）：
     *   `"gray"`（默认）：每格先裁出来**转灰度**再认；读不出字形（或分太低）时再走**彩色**兜底。
     *   `"color"`：老顺序（彩色先读、灰度只补空 —— 2026-09-20 探针结论）。
     * 为什么敢默认灰度：`tools/ocr-prep-probe.js` 实测灰度过关率整体更好（帅 0.49→0.70、士 0.57→0.82、
     * 將 0.83→0.98）；但**两边互有胜负**（灰度把馬 1.00 读成「志」、兵 0.80 读成「民」）⇒
     * 兜底必须保留：灰度读不出时用彩色那一路再读一次（老顺序里是反过来）。
     */
    ocrPrimary: "gray",
    /**
     * 老顺序（`ocrPrimary:"color"`）下，彩色读不出字形时用**灰度图重试一次**。
     * 重试要先过一道「这格确实有墨迹」的闸（找色有结论），空框/空格子直接跳过（否则每格白花 ~25ms）。
     */
    retryGray: true,
    /**
     * **空框格先找色**（真机实测 2026-09-20，探针 `tools/board-scan-cost-probe.js`）：
     *   全盘 scan 要逐格 `session.detect`（≈34ms/格）⇒ 90 格 ≈2.9s，而驱动只是要「模型漏检/误读」那几格的证据。
     *   空格子里**没有墨迹**：没框的格子先用引擎找色数一下（native，**实测 90 格共 19ms**），
     *   密度低于 `inkDensity` 就直接跳过 OCR —— 有子却被漏检的格子一定有墨迹（真机实测 0 假阴性）。
     *   没找色能力的宿主（Rhino/离线夹具）自动关闸（没证据就不许跳）。
     */
    inkGate: true,
    /** 「有墨迹」的密度门槛：真机空格红/黑密度都 ≈0，真棋子 0.08~0.16（见 ink-color.js） */
    inkDensity: 0.01
};

/**
 * 一枚棋子的颜色（纯函数，只吃采样点；与旧行为一致，供自检/老调用方使用）。
 * 真机路径请用 `inkColor.judgeFor`（引擎找色，见 `src/vision/ink-color.js`）。
 * @return "RED" | "BLACK" | "UNKNOWN"
 */
function sideFor(samples, options) {
    const verdict = pieceColor.classifySamples(samples, options);
    return verdict.side;
}

/**
 * 还掉 `prepareRegion` 造出来的临时帧：优先用宿主能力，没有就直接 `recycle()`。
 * 原生帧不会 GC —— 不还就一直堆在帧表里（`NativeFrameStore.frames_`）。
 */
function releasePrepared(host, frame) {
    if (!frame) {
        return;
    }
    if (host && typeof host.releaseFrame === "function") {
        host.releaseFrame(frame);
        return;
    }
    if (typeof frame.recycle === "function") {
        try {
            frame.recycle();
        } catch (ignore) {
            // 忽略
        }
    }
}

/**
 * 把流水线的逐格证据（`vision-pipeline` 的 `result.evidence`）摊成 `scan()` 认的 `reuse` 载荷。
 *
 * 为什么要它（2026-09-20 性能账）：驱动每 ≥6s 问一次「全盘 OCR」，而面板**刚**为同一帧读过的格子
 * （OCR 字形 + 找色颜色）与它要的证据是同一份东西 —— 实测一次询问 1155ms 里 ~950ms 是把那 28 格重读一遍。
 *
 * 只复用**够确定**的格子（`cls` 有、分数 ≥ `minScore`、颜色已定）；其余留在外面让 `scan` 自己读 ——
 * 那些格子正是灰度重试的用武之地（流水线那 4 次候选尝试里不跑灰度，真机 `灰度重试救回 4 格` 就出在这里）。
 *
 * @param cells `evidence` 数组（元素：`{index, cls, side, ocrScore, text}`）
 * @return { chars, glyphs, sides, scores, reused } —— **稀疏**：`undefined` = 没这条证据
 */
function reuseFromEvidence(cells, options) {
    const opts = Object.assign({}, DEFAULTS, options || {});
    const out = { chars: [], glyphs: [], sides: [], scores: [], reused: 0 };
    const list = cells || [];
    for (let k = 0; k < list.length; k++) {
        const one = list[k];
        if (!one || one.index == null || one.index < 0 || one.index > 89) {
            continue;
        }
        const cls = one.cls ? String(one.cls) : null;
        const score = one.ocrScore == null ? 0 : Number(one.ocrScore) || 0;
        const side = (one.side === inkColor.SIDE_RED || one.side === inkColor.SIDE_BLACK) ? one.side : null;
        if (!cls || !side || score < opts.minScore) {
            continue;      // 不够确定：让 scan 自己重读（含灰度重试）
        }
        let glyph = null;
        if (one.text) {
            const hit = pieceOcr.classOf(one.text);
            glyph = hit ? hit.glyph : null;
        }
        out.chars[one.index] = pieceOcr.applySide(cls, side);
        out.glyphs[one.index] = glyph;
        out.sides[one.index] = side;
        out.scores[one.index] = score;
        out.reused += 1;
    }
    return out;
}

/**
 * 扫一遍 90 格。
 *
 * @param host {
 *   session: { detect(image, {max, region}) }   // $ocr.create 的会话
 *   rgbAt(image, x, y) → [r,g,b]                // 取像素（aijs-adapter.rgbAt，兜底通道）
 *   countColor(image, color, {region, threshold}) → 命中数   // 引擎找色（aijs-adapter.countColor）
 *   prepareRegion?(image, region, mode) → frame // 预处理（aijs-adapter.prepareRegion），灰度重试用
 *   releaseFrame?(frame)                        // 释放上面的临时帧
 *   log?
 * }
 * @param options.boxes 可选的 **YOLO 定位框**：`{ [格子下标]: { box, residual } }`。
 *   给了就跟框心裁/采样（用户口径 2026-09-20：YOLO 只管定位，**颜色判断就按它定的位置采**）；
 *   框缺失/跑太远（residual 超阈值）会自动退回格心（见 piece-cropper.boxForItem）。
 * @param options.only 只扫这些格子下标（数组）。不传就整盘 90 格。
 * @param options.reuse 已有逐格证据（`reuseFromEvidence()` 的产物）。**这些格子不再重读**（流水线刚读过）：
 *   稀疏数组里 `undefined` = 没这条证据、`null` = 已知没字。
 * @param options.minScore 覆盖认字阈值（帅/将抢救那条路会用比 0.6 低的阈值）。
 * @return { ok, chars, glyphs, sides, scores, hits, colourUnknown, ms, reason? }
 *   chars[i] 是**带红黑**的字符（`null` = 那一格没有可用证据：没字 / 颜色未定 / 分数不够）
 *   scores[i] 是那一格 OCR 的分数（驱动写基准前会再卡一道分）
 */
function scan(host, image, grid, options) {
    const opts = Object.assign({}, DEFAULTS, options || {});
    const out = {
        ok: false, chars: null, glyphs: null, sides: null,
        hits: 0, colourUnknown: 0, ms: 0,
        /** 灰度路：主读命中数（`grayPrimary`）/ 兜底重试次数 / 因此救回来的字形数 / 采信自灰度的字形数 */
        grayPrimary: 0, grayRetries: 0, grayHits: 0, grayChars: 0,
        /** 彩色路：同上（默认主读=灰度时，`colorRetries/colorHits` 就是「灰度读不出 → 彩色兜底」的账） */
        colorRetries: 0, colorHits: 0, colorChars: 0,
        /** 被「空框 + 无墨迹」闸门跳过的格子数（见 DEFAULTS.inkGate；性能账本） */
        inkSkipped: 0,
        /** 空框格两条通道都读不出后的「1.15×宽窗重读」账：次数 / 救回的字形数（2026-09-21，d7 漏读案） */
        wideRetries: 0, wideHits: 0,
        /** 直接复用调用方给的逐格证据、没重读的格子数（见 `reuseFromEvidence`） */
        reused: 0
    };
    if (!host || !host.session || typeof host.session.detect !== "function") {
        out.reason = "没有 OCR 会话";
        return out;
    }
    if (!image || !grid) {
        out.reason = "缺帧或缺几何";
        return out;
    }
    const started = Date.now();
    const chars = new Array(90);
    const glyphs = new Array(90);
    const sides = new Array(90);
    /**
     * 逐格 OCR 分数（没认到字的格子是 undefined）。
     * 为什么要给出去：驱动把「全盘 OCR」当补正证据写进可靠基准之前，要做第二道分数闸门
     *（低分字形只记录、不写基准，2026-09-20 账本漂移事故）。
     */
    const scores = new Array(90);
    const colourHost = { rgbAt: host.rgbAt, countColor: host.countColor };
    /**
     * 只扫指定格子（`options.only`）：帅/将抢救这种场景只需要看九宫的 9 格，
     * 扫满 90 格要多花 ~3s 的 OCR（真机实测全盘 21 格用时 ~4s）。
     */
    const only = (opts.only && opts.only.length) ? opts.only : null;
    const total = only ? only.length : 90;
    // ★ 先吸收调用方给的「刚读过的逐格证据」（`options.reuse`）：那些格子不再重读（实测省 ~950ms/次）。
    //   稀疏数组：用 `hasOwnProperty` 区分「已知没字」（值 `null`）与「没这条证据」（洞）。
    const reuse = (opts.reuse && opts.reuse.chars) ? opts.reuse : null;
    const adopted = new Array(90);
    if (reuse) {
        for (let i = 0; i < 90; i++) {
            if (!Object.prototype.hasOwnProperty.call(reuse.chars, i)) {
                continue;
            }
            chars[i] = reuse.chars[i] == null ? null : reuse.chars[i];
            glyphs[i] = (reuse.glyphs && reuse.glyphs[i] != null) ? reuse.glyphs[i] : null;
            sides[i] = (reuse.sides && reuse.sides[i] != null) ? reuse.sides[i] : inkColor.SIDE_UNKNOWN;
            scores[i] = (reuse.scores && reuse.scores[i] != null) ? reuse.scores[i] : null;
            adopted[i] = true;
            out.reused += 1;
            if (chars[i]) {
                out.hits += 1;
            } else if (glyphs[i]) {
                out.colourUnknown += 1;      // 读到字但颜色没定，与扫出来的格子同一口径
            }
        }
    }
    for (let k = 0; k < total; k++) {
        const i = only ? only[k] : k;
        if (typeof i !== "number" || i < 0 || i > 89) {
            continue;
        }
        if (adopted[i]) {
            continue;                        // 已经有证据，不重读
        }
        const col = i % 9;
        const row = Math.floor(i / 9);
        // ★ 颜色/裁块跟着 **YOLO 定位框**走（给了的话）：框心比网格交点更接近棋子真位置 ——
        //   交界处那几格、以及被高亮环/装饰推偏的格，按格心采样很容易把木纹采进去。
        const det = opts.boxes ? opts.boxes[i] : null;
        const detBox = det && det.box ? det.box : (det && det.centerX != null ? det : null);
        const box = detBox
            ? cropper.boxForItem(grid, {
                index: i, col: col, row: row, box: detBox,
                residual: det.residual == null ? 0 : det.residual
            }, {})
            : cropper.cropBoxFor(grid, col, row, {});
        if (!box) {
            continue;
        }
        const region = cropper.centreRegion(box, opts.colourRatio);
        if (!region) {
            continue;
        }
        const regionArray = [region.x, region.y, region.width, region.height];
        // ★ 空框格先找色（见 DEFAULTS.inkGate，真机探针：全盘 2895ms → 收窄 1015ms，预筛本身 19ms）：
        //   没框的格子先用找色看一眼「有没有墨迹」，没有就整格跳过 OCR —— 有子被漏检的格子一定有墨迹。
        //   `earlyCounts` 后面判色时直接复用（同一格、同一框，结论一样，省两次找色）。
        let earlyCounts = null;
        if (opts.inkGate !== false && !detBox && typeof host.countColor === "function") {
            earlyCounts = inkColor.countsFor(colourHost, image, box, opts.inkOptions);
            if (earlyCounts.source === "findColor") {
                const area = earlyCounts.area || 0;
                const inkDensity = area > 0 ? ((earlyCounts.red + earlyCounts.black) / area) : 0;
                // 暗墨迹也算「有墨迹」（2026-09-21：细笔画黑子的找色密度会压线，见 ink-color.looksEmpty）
                const darkDensity = area > 0 ? ((earlyCounts.dark || 0) / area) : 0;
                if (inkDensity < opts.inkDensity && darkDensity < opts.inkDensity) {
                    out.inkSkipped += 1;
                    // 没框 + 没墨迹 ⇒ 那格没子。**照样把「无证据」写实**（不写就是稀疏数组，
                    // 下游 `chars[i] === null` 这种判断会踩空 —— 自检用例已守着这条契约）。
                    chars[i] = null;
                    glyphs[i] = null;
                    sides[i] = inkColor.SIDE_UNKNOWN;
                    scores[i] = null;
                    continue;
                }
            }
        }
        // ① 先把格子**裁出来**再送 OCR：与「整帧 + region」**逐格完全一致**（真机 20 格
        //    text/score 全同，见 `tools/ocr-prep-probe.js`），但每格少拷 ~10MB（1080×2400 RGBA）
        //    ⇒ 31ms → 25ms。`prepareRegion` 越界（棋盘边上的格子常见）/宿主没能力时返回 null，
        //    自动回退到老路径，行为不变。
        //    ★ 2026-09-21：主读默认**灰度**（见 DEFAULTS.ocrPrimary）；`"color"` 才是老顺序（彩色裁块）。
        const grayPrimary = opts.ocrPrimary !== "color";
        let one = null;
        let clipped = null;
        if (typeof host.prepareRegion === "function") {
            clipped = host.prepareRegion(image, regionArray, grayPrimary ? "gray" : "clip");
        }
        if (clipped && grayPrimary) {
            out.grayPrimary += 1;
        }
        try {
            const blocks = clipped
                ? (host.session.detect(clipped, { max: 1 }) || [])
                : (host.session.detect(image, { max: 1, region: regionArray }) || []);
            one = blocks[0] || null;
        } catch (error) {
            one = null;
        } finally {
            if (clipped) {
                releasePrepared(host, clipped);
            }
        }
    let score = one ? (Number(one.score == null ? one.confidence : one.score) || 0) : 0;
        let hit = one ? pieceOcr.classOf(one.text || one.label) : null;
        let colourVerdict = null;
        let usedRetry = null;
        // 主读没读出可用字形 ⇒ 换**另一条通道**重试一次：
        //   主读=灰度（默认）⇒ 用彩色重试；主读=彩色（老顺序）⇒ 用灰度重试（见 DEFAULTS.retryGray）。
        // ⚠️ 重试前先问一道「这格到底有没有墨迹」（找色）：没有就跳过，
        //    不然空格子/假框也去跑一遍 OCR（每格 ~25ms）。
        const retryMode = grayPrimary ? "clip" : "gray";
        const retryAllowed = grayPrimary ? true : (opts.retryGray !== false);
        if ((!hit || score < opts.minScore) && retryAllowed &&
            typeof host.prepareRegion === "function") {
            const verdict = (earlyCounts && earlyCounts.source === "findColor")
                ? inkColor.judgeCounts(earlyCounts, opts.inkOptions)
                : inkColor.judgeFor(colourHost, image, box, opts.inkOptions);
            if (verdict.side !== inkColor.SIDE_UNKNOWN) {
                const secondFrame = host.prepareRegion(image, regionArray, retryMode);
                if (secondFrame) {
                    let second = null;
                    try {
                        const blocks = host.session.detect(secondFrame, { max: 1 }) || [];
                        second = blocks[0] || null;
                    } catch (error) {
                        second = null;
                    }
                    releasePrepared(host, secondFrame);
                    if (grayPrimary) {
                        out.colorRetries += 1;
                    } else {
                        out.grayRetries += 1;
                    }
                    if (second) {
                        const secondScore = Number(second.score == null ? second.confidence : second.score) || 0;
                        const secondHit = pieceOcr.classOf(second.text || second.label);
                        if (secondHit && secondScore >= opts.minScore) {
                            hit = secondHit;
                            score = secondScore;
                            colourVerdict = verdict;   // 兜底那遍把字认出来了，颜色结论直接沿用
                            usedRetry = retryMode;
                            if (grayPrimary) {
                                out.colorHits += 1;
                            } else {
                                out.grayHits += 1;
                            }
                        }
                    }
                }
            }
        }
        /**
         * ★★ 空框格「宽窗重读」（2026-09-21 真机 d7 黑士漏读，面板僵死 10 分钟）：
         *   没框的格子只能按**网格交点**裁，而当局几何整体偏 ~28px（框太少、snapGridToPieces
         *   的样本门槛没过）⇒ 0.70 窄窗把细笔画子的左缘切掉 ⇒ PP-OCR 读出「土」这类**无效字**。
         *   真机探针实测（同一帧）：0.72 档=土 0.78（无效）；1.10~1.20×格 宽窗 ⇒ 士 0.85/0.73 ✓。
         *   所以：空框格两条通道都读不出时，用 **1.15×格** 的宽窗（同一交点为中心）再读一次灰度。
         *   ⚠️ 宽窗重读同样要守「老顺序 + retryGray=false ⇒ 完全不碰灰度」的开关语义（自检存着这条）。
         */
        const wideAllowed = grayPrimary || (opts.retryGray !== false);
        if ((!hit || score < opts.minScore) && !detBox && wideAllowed) {
            const step = Math.min(Math.abs(grid.stepX), Math.abs(grid.stepY));
            const wideSize = Math.max(8, Math.round(step * 1.15));
            const cx = box.centerX != null ? box.centerX : (box.x + box.width / 2);
            const cy = box.centerY != null ? box.centerY : (box.y + box.height / 2);
            const wide = [Math.round(cx - wideSize / 2), Math.round(cy - wideSize / 2), wideSize, wideSize];
            out.wideRetries += 1;
            let frame3 = null;
            try {
                frame3 = (typeof host.prepareRegion === "function")
                    ? host.prepareRegion(image, wide, "gray")
                    : null;
                const blocks = frame3
                    ? (host.session.detect(frame3, { max: 1 }) || [])
                    : (host.session.detect(image, { max: 1, region: wide }) || []);
                const third = blocks[0] || null;
                if (third) {
                    const thirdScore = Number(third.score == null ? third.confidence : third.score) || 0;
                    const thirdHit = pieceOcr.classOf(third.text || third.label);
                    if (thirdHit && thirdScore >= opts.minScore) {
                        hit = thirdHit;
                        score = thirdScore;
                        usedRetry = "gray";     // 账目上算灰度路救回的
                        out.wideHits += 1;
                    }
                }
            } catch (error) {
                // 宽窗重读失败不影响主流程（这一格维持「读不出」）
            } finally {
                if (frame3) {
                    releasePrepared(host, frame3);
                }
            }
        }
        scores[i] = score;
        if (!hit || score < opts.minScore) {
            continue;
        }
        // ⚠️ 颜色只从**像素**来（方案 §3.6），且优先走引擎**找色**（用户口径 2026-09-20：
        //    「新引擎不是有找色的案例吗」）：在 YOLO 框的中心区里数红/黑墨迹像素；
        //    宿主没有找色能力时自动退回采样 + `piece-color` 分类器（Rhino/离线夹具）。
        const verdict = colourVerdict ||
            ((earlyCounts && earlyCounts.source === "findColor")
                ? inkColor.judgeCounts(earlyCounts, opts.inkOptions)
                : inkColor.judgeFor(colourHost, image, box, opts.inkOptions));
        const side = verdict.side;
        glyphs[i] = hit.glyph;
        sides[i] = side;
        if (usedRetry ? usedRetry === "gray" : (grayPrimary && clipped)) {
            out.grayChars += 1;
        } else {
            out.colorChars += 1;
        }
        if (side === inkColor.SIDE_UNKNOWN) {
            out.colourUnknown += 1;
            chars[i] = null;
            continue;
        }
        chars[i] = pieceOcr.applySide(hit.cls, side === inkColor.SIDE_RED ? "RED" : "BLACK");
        out.hits += 1;
    }
    out.ok = true;
    out.chars = chars;
    out.glyphs = glyphs;
    out.sides = sides;
    out.scores = scores;
    out.ms = Date.now() - started;
    return out;
}

/**
 * 数未知格（只有 `?` 算未知）。
 *
 * ⚠️ 字符串层的**空格是 `null`**（见 `src/vision/cells.js`），写成 `!ch || ch === "?"` 会把 60 个空位
 * 也算成未知 ⇒ 稳定帧闸门（>6 就丢帧）会把每一帧都丢掉（2026-09-20 面板真机踩到，评审也点了这条）。
 * 单独放在这里是为了能被自检直接测到。
 */
function countUnknownCells(chars) {
    if (!chars) {
        return 90;
    }
    let unknown = 0;
    for (let i = 0; i < chars.length; i++) {
        if (chars[i] === "?") {
            unknown += 1;
        }
    }
    return unknown;
}

module.exports = {
    scan: scan,
    sideFor: sideFor,
    reuseFromEvidence: reuseFromEvidence,
    countUnknownCells: countUnknownCells,
    DEFAULTS: DEFAULTS
};
