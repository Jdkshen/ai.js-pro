// @engine quickjs
/**
 * 新视觉流水线（重构方案 §2 的流程图，一条不落）：
 *
 *   截图 → 稳定帧检查 → YOLO 候选框 → 交点映射 → 裁剪 → OCR 字形 → 颜色 → 证据融合 → 规则校验 → 可信快照
 *
 * 设计原则（方案 §13）：**任何单一模块都不能直接决定最终局面** ——
 * YOLO 只管位置、OCR 只管字形、颜色只管红黑、规则只管否决、融合只管稳定；
 * 拿不准一律输出 `?`，绝不猜。
 *
 * 与旧路径的关系：旧路径（recognizer 直接出 90 格字符）保持不变，本流水线是新增的
 * **可切换**实现（面板的 `autorun.visionPipeline` 打开），失败/异常时调用方可以退回旧路径。
 */
"use strict";

const detector = require("./piece-detector.js");
const cropper = require("./piece-cropper.js");
const pieceOcr = require("./piece-ocr.js");
const pieceColor = require("./piece-color.js");
const inkColor = require("./ink-color.js");
const boardScan = require("./board-scan.js");
const fusionModule = require("./evidence-fusion.js");
const boardValidator = require("./board-validator.js");
const fenModule = require("../chess/fen.js");
const timing = require("../runtime/timing.js");

const DEFAULTS = {
    /** 认哪些格：YOLO 给的交点（方案 §3.4：别对 90 格全跑 OCR） */
    ocrOnlyDetected: true,
    /** 颜色采样阈值交给 piece-color */
    colorOptions: null,
    /** 颜色判定（找色版）的参数：参考色/容差/门槛，见 `ink-color.js` */
    inkOptions: null,
    ocrOptions: null,
    detectorOptions: null,
    fusionOptions: null,
    /** 单帧内 OCR 最多认多少枚（性能兜底；K40 实测单格 ~15ms） */
    maxOcrCells: 40,
    /**
     * OCR 给不出类别时，用检测框的类别字母兜底（真机必需：黑「士」被判 OCR 读成 0.85 分的 “H”、
     * 低分格如 (4,9) 帅 0.26 分 —— 没兜底就永远盘面验证不过）。
     * 因为类别与框来自**同一个网络**，计入分类证据时按 `labelFallbackTrust` 折扣，
     * 且要求框分 ≥ `labelFallbackMinScore`；红黑仍由颜色模块独立给（方案 §3.6）。
     */
    labelFallbackMinScore: 0.35,
    labelFallbackTrust: 0.9,
    /** 规则层补全帅/将时，字形分至少要有这么高（再配合「检测类别也说是将/帅」与九宫约束） */
    inferKingMinScore: 0.45,
    /**
     * 帅/将**完全没检出**时（连证据都没有），用 `board-scan` 去九宫逐格复核一遍：
     * 字形出兵种、找色出颜色，找到唯一候选就补上 —— 在「整帧退回旧路径」之前给它一次机会。
     *
     * 真机 2026-09-20：红帅那格检测框没检出（模型对帅/将召回就差），融合层里连证据都没有，
     * `inferKings`（只吃 `?` 格子）无从下手 ⇒ 规则门报「红帅数量 0」⇒ 面板丢弃整帧、退回旧路径，
     * 本轮观察直接作废（日志里就是 `视觉流水线不可信 ⇒ 退回旧路径：局面不可信：红帅数量 0（应为 1）`）。
     */
    kingRescue: true,
    /** 帅/将抢救时的认字下限（比常规 0.6 低 —— 真机 帅 实测就在 0.5 上下） */
    kingRescueMinScore: 0.45,
    /**
     * 字形与检测类别**一致**时的印证权重（真机需要：黑「象」字形读对了但只有 0.41 分，
     * 单靠 OCR 分数会被 0.78 门槛卡掉；检测类别也说是象，两份证据互相印证就该加分）。
     */
    agreeTrust: 0.95,
    /**
     * 多尺度 OCR：帅/将所在格被九宫斜线穿过，单尺度常常读不出（真机实测 0.26~0.34 分）。
     * ⚠️ 真机实测（2026-09-19）：“以检测框为中心”**不**比“以格中心”准
     *    （棋盘定位已经把格心定得很准，而检测框自身有几像素偏移）。
     *    所以两个中心都当候选、谁认出的分数高用谁，而不是盲信其中任何一个。
     */
    ocrScales: [0.70, 0.54],
    /** 候选中心：① 格中心 ② 检测框中心（首轮就很确定就不再试第二个） */
    ocrCentres: 2,
    /** 认到这个分数就不必再试其他候选（K40 实测：再试也涨不了分） */
    ocrEnoughScore: 0.8,
    /**
     * 灰度重试可采纳的**最低分**（见 ocrCellGray）：低于这条线宁可那格是 `?`。
     * 0.60 与 board-scan 全盘重试同一条线；真机事故：不加闸门时 0.24 分的
     * 「卒」被当成棋子写进观测层（2026-09-20）。
     */
    ocrGrayMinScore: 0.60,
    /**
     * OCR **主读通道**（2026-09-21 用户口径「先灰度」）：
     *   `"gray"`（默认）：每格先裁出来转灰度再认（见 `ocrCellGray`）；读不出字形 / 分过低被丢时，
     *     再用**彩色（整帧 + region）**兜底重读一次。
     *   `"color"`：老顺序（彩色主读、灰度只补空）。
     * 探针（`tools/ocr-prep-probe.js`）实测两边互有胜负（灰度过关率整体更好，但会把馬读成「志」、
     * 兵读成「民」）⇒ **兜底必须保留**，这也是默认敢切灰度的前提。
     */
    ocrPrimary: "gray",
    /**
     * 逐格变化缓存（方案 §7 的预算靠它）：这一格的像素指纹没变 ⇒ 字形结论直接复用。
     * 换 v6 small 后单格 OCR ≈42ms、30 格 ≈1.3s/帧（真机 2026-09-19），而一手里真正变化的格
     * 往往只有两三个 ⇒ 缓存后每帧只赔检出（det）+ 变化格的 OCR。
     * 指纹直接拿**已经为颜色证据采过的像素**算，不额外取点。
     */
    cellCache: true,
    /** 是否用规则安全门否决不可信局面（方案 §3.9） */
    validate: true
};

/**
 * 把区域统一成真机 OCR 契约的 `[x, y, width, height]`（也接受 {x,y,width,height}）。
 * 真机 `$ocr` 的 region 只认数组，传对象直接抛 "region must be [x, y, width, height]"。
 */
function normalizeRegion(region) {
    if (!region) {
        return null;
    }
    let x, y, w, h;
    if (typeof region.length === "number" && region.length >= 4) {
        x = Number(region[0]); y = Number(region[1]);
        w = Number(region[2]); h = Number(region[3]);
    } else {
        x = Number(region.x); y = Number(region.y);
        w = Number(region.width); h = Number(region.height);
    }
    if (!isFinite(x) || !isFinite(y) || !isFinite(w) || !isFinite(h) || w <= 0 || h <= 0) {
        return null;
    }
    return [Math.round(x), Math.round(y), Math.round(w), Math.round(h)];
}

/**
 * 颜色证据分（0~1）：把「红黑得分差」归一化。
 * 分类器已经要求 gap ≥ 0.18 才出声，故 gap ≥ `COLOR_SATURATED_GAP` 视为颜色证据饱和。
 */
const COLOR_SATURATED_GAP = 0.35;

function colorScoreOf(verdict) {
    if (!verdict || verdict.side === pieceColor.SIDE_UNKNOWN) {
        return 0;
    }
    const gap = Number(verdict.confidence) || 0;
    return Math.max(0, Math.min(1, gap / COLOR_SATURATED_GAP));
}

/**
 * 建一条流水线。宿主提供三件事：截图/像素、检测、OCR 会话。
 *
 * @param host {
 *   clip(image, box), rgbAt(image, x, y),
 *   detect(image, options) → 原始框数组,
 *   ocrSession → { detect(image, {max, region}) },   // 直接来自 $ocr.create
 *   log?
 * }
 */
function create(host, options) {
    const opts = Object.assign({}, DEFAULTS, options || {});
    const h = host || {};
    const log = h.log || function () { };
    const colorVoter = inkColor.createVoter(opts.inkOptions);
    /** 跨帧累积的证据融合器（方案 §3.8 要「最近 3 帧」，因此不能每帧新建） */
    let fuser = null;
    /** 逐格缓存：index → { fp: 像素指纹, ocr: 字形结论, gridVersion } */
    const cellCache = {};
    let cellCacheGridVersion = null;
    const stats = { frames: 0, boxes: 0, mapped: 0, ocrOk: 0, ocrUnknown: 0, ocrError: 0, colorUnknown: 0, labelFallbacks: 0, labelMismatch: 0, inferred: 0, kingRescued: 0, cellsCached: 0, rejected: 0, grayPrimary: 0, grayRetries: 0, grayHits: 0, grayLow: 0, colorRetries: 0, colorRetryHits: 0 };
    let firstOcrError = "";

    /**
     * 采样点 → 指纹（FNV-1a，隔点取样）。
     * 为什么用采样点而不是全格哈希：颜色证据**已经**采过这些点，这里不该再多取一遍像素（方案 §7）。
     */
    function fingerprintOf(colors) {
        if (!colors || !colors.length) {
            return 0;
        }
        let hash = 2166136261;
        const stride = Math.max(1, Math.floor(colors.length / 24));
        for (let i = 0; i < colors.length; i += stride) {
            const px = colors[i];
            if (!px) {
                continue;
            }
            hash = (hash ^ (((px[0] & 255) << 16) ^ ((px[1] & 255) << 8) ^ (px[2] & 255))) >>> 0;
            hash = (hash * 16777619) >>> 0;
        }
        return hash;
    }

    /** 调用方可以用 ctx.fuser 注入自己的融合器（例如跨“重新定位”保留历史） */
    function fuserFor(ctx) {
        if (ctx && ctx.fuser) {
            return ctx.fuser;
        }
        if (!fuser) {
            fuser = fusionModule.createFuser(opts.fusionOptions);
        }
        return fuser;
    }

    /**
     * 多候选认一格：候选中心（格中心 / 检测框中心）× 多个裁剪比例，
     * 取**认出兵种且分数最高**的那次。只回答「这一格的字是什么、有多确定」，不涉红黑（方案 §3.6）。
     */
    function ocrCell(image, crop, gridBox) {
        const ratios = (opts.ocrScales && opts.ocrScales.length) ? opts.ocrScales : [0.70];
        const boxes = [gridBox, crop.box];
        let best = null;
        for (let b = 0; b < boxes.length && b < Math.max(1, opts.ocrCentres); b++) {
            const box = boxes[b];
            if (!box) {
                continue;
            }
            for (let i = 0; i < ratios.length; i++) {
                const region = cropper.centreRegion(box, ratios[i]);
                if (!region) {
                    continue;
                }
                const verdict = ocrOne(image, region);
                if (verdict.cls) {
                    verdict.scale = ratios[i];
                    verdict.centre = b === 0 ? "grid" : "detector";
                    if (!best || (verdict.score || 0) > (best.score || 0)) {
                        best = verdict;
                    }
                    // 已经很确定就不必再试其他候选
                    if ((verdict.score || 0) >= opts.ocrEnoughScore) {
                        return verdict;
                    }
                } else if (!best) {
                    best = verdict;
                }
            }
        }
        return best || { state: "unknown", reason: "所有候选都没读到" };
    }

    /**
     * 灰度重试：把格子的候选裁剪区裁出来**转灰度**，再让 OCR 认一遍。
     *
     * 为什么（2026-09-20 真机）：刚落子的两格带着游戏的**高亮环** —— 彩色读不出、灰度读得出。
     * 全盘 OCR（`board-scan`）早就有这一步（日志里的 `灰度重试救回 1 格` 救的就是它），
     * 而**逐格这条路一直没有** ⇒ 那两格在观察层永远是 `?` ⇒ 验证器反复「还有 1 个未知格」。
     * 阈值与候选跟 `ocrCell` 保持一致（分数段也一样：≥0.70 ok / ≥0.55 low），只换输入图。
     * ⚠️ 2026-09-20 真机事故：第一版**不加最低分**就采纳灰度读数 —— 当场救回一格 `(5,7) = 卒`，
     *    但那是 **0.24 分**的垃圾读数 ⇒ 观测层多出一枚幽灵棋子 ⇒ 盘面永远对不上，
     *    后半局钉死在「装饰容差下有 N 种应手解释，不唯一」。
     *    现在：低于 `ocrGrayMinScore`（默认 0.60，与 board-scan 全盘重试同一条线）**一律丢**，
     *    宁可那格是 `?`（交给规则层/下一帧），也不写一个错兵种进去。
     *    调用方负责先过一道「这格有墨迹」（找色有结论）的闸，避免空格白花 ~25ms。
     */
    function ocrCellGray(image, crop, gridBox) {
        const minScore = Number(opts.ocrGrayMinScore) > 0 ? Number(opts.ocrGrayMinScore) : 0.60;
        const ratios = (opts.ocrScales && opts.ocrScales.length) ? opts.ocrScales : [0.70];
        const boxes = [gridBox, crop.box];
        let best = null;
        const low = [];
        for (let b = 0; b < boxes.length && b < Math.max(1, opts.ocrCentres); b++) {
            const box = boxes[b];
            if (!box) {
                continue;
            }
            for (let i = 0; i < ratios.length; i++) {
                const region = cropper.centreRegion(box, ratios[i]);
                if (!region) {
                    continue;
                }
                let gray = null;
                try {
                    gray = h.prepareRegion(image, [region.x, region.y, region.width, region.height], "gray");
                } catch (error) {
                    gray = null;
                }
                if (!gray) {
                    continue;
                }
                let verdict = null;
                try {
                    const blocks = h.ocrSession.detect(gray, { max: 1 }) || [];
                    const one = blocks[0];
                    if (one) {
                        const text = String(one.text || one.label || "");
                        const score = Number(one.score == null ? one.confidence : one.score) || 0;
                        const hit = pieceOcr.classOf(text);
                        if (hit) {
                            if (score < minScore) {
                                // 读到了字但分太低 —— **不采纳**，但把这次读数记下来（调用方会缓存，
                                // 环还在时不至于每帧重试）；日志要能分清“没救回”和“救回但分太低被丢”。
                                low.push({ glyph: hit.glyph, cls: hit.cls, score: score, text: text });
                                verdict = null;
                            } else {
                                verdict = {
                                    state: score >= 0.70 ? "ok" : "low",
                                    cls: hit.cls, glyph: hit.glyph, text: text, score: score,
                                    fromGray: true, scale: ratios[i]
                                };
                            }
                        }
                    }
                } catch (error) {
                    verdict = null;
                } finally {
                    // 原生帧不会 GC —— 裁出来的临时帧必须还（与 board-scan 同一规矩）
                    if (gray && typeof h.releaseFrame === "function") {
                        try { h.releaseFrame(gray); } catch (ignore) { /* 还不了就算了 */ }
                    } else if (gray && typeof gray.recycle === "function") {
                        try { gray.recycle(); } catch (ignore) { /* 同上 */ }
                    }
                }
                if (verdict && verdict.cls) {
                    if (!best || (verdict.score || 0) > (best.score || 0)) {
                        best = verdict;
                    }
                    if ((verdict.score || 0) >= opts.ocrEnoughScore) {
                        return best;
                    }
                }
            }
        }
        if (!best && low.length) {
            // 一格子都没救回、但读到过低分字：把最像的那个带回去（不带 cls），供调用方计数/缓存
            low.sort(function (a, b) { return (b.score || 0) - (a.score || 0); });
            return { state: "unknown", reason: "灰度读数分太低", text: low[0].text, score: low[0].score, grayLow: low[0] };
        }
        return best;
    }

    /** 直接认一格（给「单格复核」用；不走流水线全流程） */
    function ocrOne(image, region) {
        if (!h.ocrSession || typeof h.ocrSession.detect !== "function") {
            return { state: "error", reason: "没有 OCR 会话" };
        }
        // ⚠️ 真机契约是 **region: [x, y, width, height] 数组**；传对象会直接报
        //    "region must be [x, y, width, height]"（2026-09-19 真机踩到，30 格全废）。
        //    这里两种形态都收，避免上游几何模块的返回结构决定成败。
        const rect = normalizeRegion(region);
        if (!rect) {
            return { state: "unknown", reason: "region 非法" };
        }
        let blocks = null;
        try {
            blocks = h.ocrSession.detect(image, { max: 1, region: rect }) || [];
        } catch (error) {
            return { state: "error", reason: "OCR 异常：" + (error && error.message ? error.message : error) };
        }
        const one = blocks[0];
        if (!one) {
            // 这一帧看不到字 —— 只说明**这一帧**；是空还是被盖住/在飞行，交给多帧与稳定帧那层
            return { state: "unknown", reason: "这一帧没读到字" };
        }
        const text = String(one.text || one.label || "");
        const score = Number(one.score == null ? one.confidence : one.score) || 0;
        const hit = pieceOcr.classOf(text);
        if (!hit) {
            return { state: "unknown", reason: "读到的不是棋子的字", text: text, score: score };
        }
        stats.ocrOk += 1;
        return {
            state: score >= 0.70 ? "ok" : (score >= 0.55 ? "low" : "unknown"),
            cls: hit.cls, glyph: hit.glyph, text: text, score: score
        };
    }

    /**
     * 识一帧。
     * @return {
     *   ok, reason,
     *   cells:   [{ index, cls, side, char, finalScore, evidence, box }],
     *   board,   // 90 格（'?' = 未知）——还没被规则门否决前
     *   chars,   // 融合后的 90 格（稀疏：只有认到的有值）
     *   validation, // 规则门的结论
     *   stats
     * }
     */
    /**
     * 规则层补全（方案 §3.9）：**两路证据一致 + 九宫约束**下才能推断帅/将。
     *
     * 场景（真机 2026-09-19）：红帅那格被九宫斜线压着，PP-OCR 认出「帅」但只有 0.51 分，
     * 融合分卡在 0.78 门槛之下 ⇒ 整盘因「红帅数量 0」被否决、自动走棋停摆。
     *
     * 为什么这不是「猜」：
     *   · 字形（OCR）说是 K、检测类别标签也说是 K —— 两路独立证据一致；
     *   · 该格在九宫之内，且本方帅/将**恰好缺一个**（计数约束）；
     *   · 还要求颜色证据确定（能分清红黑），否则不推。
     * 推断出来的格子标 `inferred`，调用方可以自己决定要不要再等一帧。
     */
    function inferKings(board, evidence) {
        const out = [];
        if (!evidence || !evidence.length) {
            return out;
        }
        let redKings = 0;
        let blackKings = 0;
        for (let i = 0; i < 90; i++) {
            if (board[i] === "K") {
                redKings += 1;
            } else if (board[i] === "k") {
                blackKings += 1;
            }
        }
        const plan = [];
        if (redKings === 0) {
            plan.push({ want: "K", red: true, from: 7, to: 9 });
        }
        if (blackKings === 0) {
            plan.push({ want: "k", red: false, from: 0, to: 2 });
        }
        for (let p = 0; p < plan.length; p++) {
            const want = plan[p];
            const hits = [];
            for (let i = 0; i < evidence.length; i++) {
                const one = evidence[i];
                if (one.cls !== "K" || board[one.index] !== "?") {
                    continue;
                }
                if (one.col < 3 || one.col > 5 || one.row < want.from || one.row > want.to) {
                    continue;                                  // 不在自己的九宫里
                }
                if (one.side === pieceColor.SIDE_UNKNOWN) {
                    continue;                                  // 颜色都不确定就别推
                }
                if ((one.side === pieceColor.SIDE_RED) !== want.red) {
                    continue;
                }
                const twoWay = one.clsSource === "ocr+yolo" ||
                    (Number(one.ocrScore) || 0) >= opts.inferKingMinScore;
                if (!twoWay) {
                    continue;
                }
                hits.push(one);
            }
            if (hits.length !== 1) {
                continue;                                      // 不是唯一候选（有歧义）就不推
            }
            board[hits[0].index] = want.want;
            hits[0].inferred = true;
            out.push({
                index: hits[0].index, col: hits[0].col, row: hits[0].row, char: want.want,
                ocrScore: hits[0].ocrScore, clsSource: hits[0].clsSource
            });
        }
        return out;
    }

    /**
     * 帅/将抢救：**YOLO 定位优先**，字形只当第二路。
     *
     * 两路都只在「这一方缺帅/将」时跑，且都要求九宫里**恰好一个**候选：
     *   ① **YOLO 定位路（先）**：本帧检测框 + 类别标签（`label`）已经算好了 ⇒ **零额外开销**。
     *      用「模型说这格像将/帅」当**候选筛选**，红黑由找色像素定，再叠九宫约束。
     *   ② 字形路（后）：①没找到（例如那一格**根本没检出框**）时，用 `board-scan` 扫九宫 9 格
     *      （≈0.4s）——字形出兵种、找色出颜色。
     * 兵种仍不取框的类别（用户口径）：标签只决定「哪一格值得查」，落地靠
     * 「九宫 + 该方恰好缺一个 + 找色颜色对得上 + 宫中唯一候选」这四条。
     *
     * @return [{ index, col, row, char, viaYolo }]
     */
    function rescueKings(image, grid, board, boxes, labels) {
        const out = [];
        if (!opts.kingRescue || !grid) {
            return out;
        }
        if (!h.ocrSession || typeof h.ocrSession.detect !== "function") {
            return out;
        }
        let redKings = 0;
        let blackKings = 0;
        for (let i = 0; i < 90; i++) {
            if (board[i] === "K") {
                redKings += 1;
            } else if (board[i] === "k") {
                blackKings += 1;
            }
        }
        const plan = [];
        if (redKings === 0) {
            plan.push({ want: "K", side: pieceColor.SIDE_RED, rowFrom: 7, rowTo: 9 });
        }
        if (blackKings === 0) {
            plan.push({ want: "k", side: pieceColor.SIDE_BLACK, rowFrom: 0, rowTo: 2 });
        }
        for (let p = 0; p < plan.length; p++) {
            const one = plan[p];
            const only = [];
            for (let row = one.rowFrom; row <= one.rowTo; row++) {
                for (let col = 3; col <= 5; col++) {
                    only.push(row * 9 + col);
                }
            }
            const colourOf = function (idx) {
                const item = boxes ? boxes[idx] : null;
                if (!item || !item.box) {
                    return null;
                }
                return inkColor.judgeFor({
                    rgbAt: h.rgbAt, countColor: h.countColor
                }, image, item.box, {});
            };
            // ① YOLO 定位路（零开销：框与类别标签本帧已经有了）
            let hit = -1;
            if (labels) {
                const byYolo = [];
                for (let k = 0; k < only.length; k++) {
                    const idx = only[k];
                    const labelHit = labels[idx] == null ? null : pieceOcr.classOfLabel(labels[idx]);
                    if (!labelHit || labelHit.cls !== "K") {
                        continue;      // 模型没说这格像将/帅
                    }
                    const colour = colourOf(idx);
                    if (!colour || colour.side !== one.side) {
                        continue;      // 颜色与这一方不符（例如宫里的红仕被当成帅）⇒ 不要
                    }
                    byYolo.push(idx);
                }
                if (byYolo.length === 1) {
                    hit = byYolo[0];
                }
            }
            // ② 字形路（①没找到才跑：例如那一格根本没检出框）
            let scanned = null;
            if (hit < 0) {
                try {
                    scanned = boardScan.scan({
                        session: h.ocrSession,
                        rgbAt: h.rgbAt,
                        countColor: h.countColor,
                        // 字形路也吃「灰度重试」：帅/将就那一两格，多花 ~25ms 换回一个字很划算
                        prepareRegion: h.prepareRegion,
                        releaseFrame: h.releaseFrame
                    }, image, grid, {
                        boxes: boxes,
                        only: only,
                        minScore: opts.kingRescueMinScore
                    });
                } catch (error) {
                    scanned = null;
                }
                // 兜底重读的战果要看得见（用户口径：帅/将那两格读不出来就得有据可查）
                if (scanned && (scanned.grayHits > 0 || scanned.colorHits > 0)) {
                    log("帅/将抢救：兜底重读救回 " + (scanned.grayHits + scanned.colorHits) + " 格（灰度 " +
                        scanned.grayHits + " 次 / 彩色 " + scanned.colorHits + " 次，共试 " +
                        ((scanned.grayRetries || 0) + (scanned.colorRetries || 0)) + " 次）");
                }
                if (scanned && scanned.ok) {
                    const glyphHits = [];
                    for (let k = 0; k < only.length; k++) {
                        const idx = only[k];
                        if (scanned.chars[idx] !== one.want) {
                            continue;
                        }
                        if (scanned.sides[idx] !== one.side) {
                            continue;      // 颜色没判出来 / 判反了 ⇒ 不采用（找色没证据宁可不补）
                        }
                        glyphHits.push(idx);
                    }
                    if (glyphHits.length === 1) {
                        hit = glyphHits[0];    // 多个命中 = 有歧义，不猜
                    }
                }
            }
            if (hit < 0) {
                continue;              // 两路都没找到 ⇒ 交给调用方自己的兜底
            }
            board[hit] = one.want;
            out.push({
                index: hit, col: hit % 9, row: Math.floor(hit / 9),
                char: one.want,
                glyph: (scanned && scanned.glyphs) ? (scanned.glyphs[hit] || null) : null,
                viaYolo: !scanned
            });
            stats.kingRescued += 1;
            if (!scanned) {
                stats.kingRescuedByYolo = (stats.kingRescuedByYolo || 0) + 1;
            }
        }
        return out;
    }

    function recognize(image, grid, context) {
        const ctx = context || {};
        stats.frames += 1;
        // 分段计时（方案 §7）：检测（YOLO）/ 逐格（OCR+找色）/ 抢救 / 规则门
        const clock = timing.create();
        if (!image || !grid) {
            return { ok: false, reason: "缺帧或缺几何", cells: [], board: null };
        }
        // 几何变了（重新定位/微调）⇒ 上一帧的逐格缓存全部作废
        const geometryKey = [grid.originX, grid.originY, grid.stepX, grid.stepY].join(",");
        if (cellCacheGridVersion !== geometryKey) {
            clearCache();
            cellCacheGridVersion = geometryKey;
        }
        // ① YOLO 候选框（方案 §3.2：只回答「哪里有棋子」）
        const detected = detector.detect(h, image, opts.detectorOptions);
        clock.mark("检测(YOLO)");
        if (!detected.ok) {
            return { ok: false, reason: detected.reason, cells: [], board: null, timing: clock.summary("收尾") };
        }
        stats.boxes += detected.boxes.length;
        // ② 框 → 交点（方案 §3.3 的三条规则）
        const mapped = detector.mapToCells(detected.boxes, grid, opts.detectorOptions);
        stats.mapped += mapped.cells.length;
        const items = mapped.cells;
        // ⚠️ cropAll 的返回**不带检测分数**（它只干几何+采样），而融合公式要 0.30 权重给它。
        //    2026-09-19 真机事故：不补这一步，detectionScore 永远 null（日志里「框分 -」），
        //    综合分封顶 0.70 < 0.78 ⇒ 每格都确认不了，规则门永远否决。
        const scoreOf = {};
        const labelOf = {};
        /** 格子下标 → 检测框（帅/将抢救时要沿用同一帧的框心，而不是格心） */
        const boxesByIndex = {};
        for (let i = 0; i < items.length; i++) {
            if (items[i] && items[i].index != null) {
                scoreOf[items[i].index] = items[i].score;
                // ⚠️ cropAll 返回的 box 是**重新算的几何框**，没有 label ——
                //    直接读 crop.box.label 永远是 undefined（2026-09-19 兜底因此失效）。
                labelOf[items[i].index] = items[i].box ? items[i].box.label : null;
                boxesByIndex[items[i].index] = { box: items[i].box, residual: items[i].residual };
            }
        }
        // ③ 裁剪（只裁有棋子的交点）
        const crops = cropper.cropAll(h, image, grid, items, opts.cropOptions);
        // ★ 2026-09-21 内存泄漏修复（K40 真机实测）：cropAll 会为**每一枚棋子**裁一张原生帧
        //   （`crop.image`），但这条流水线从头到尾只读 `box / col / row / colors`
        //   （OCR 一律走「整帧 + region」）⇒ 这些裁剪帧一个都不需要。
        //   不还的话：全盘识别一次就按「有子格数」往原生帧表里堆 —— 帧探针实测
        //   `活跃 1→19`（残局 18 子正好 +18）、开局 32 子则 +32；24 帧一重锚 ⇒ 每 40 多秒 +18，
        //   跑一夜就是 100+ 帧线性增长。原生帧不会 GC（见 NativeFrameStore），这里用完当场释放；
        //   释放后 `crops[].image` 即失效 —— 下游**任何**代码都不要再去读它（现在确实没有）。
        for (let r = 0; r < crops.length; r++) {
            const cropImage = crops[r] && crops[r].image;
            if (!cropImage) {
                continue;
            }
            if (typeof h.releaseFrame === "function") {
                try { h.releaseFrame(cropImage); } catch (ignore) { /* 还不了就算了 */ }
            } else if (typeof cropImage.recycle === "function") {
                try { cropImage.recycle(); } catch (ignore) { /* 同上 */ }
            }
        }
        /** OCR 主读走灰度（默认，见 DEFAULTS.ocrPrimary）；宿主没有裁剪能力 ⇒ 自动退回彩色老路 */
        const grayFirst = opts.ocrPrimary !== "color" && typeof h.prepareRegion === "function";
        // ④⑤ 逐枚：OCR 字形 + 颜色（两者**互相独立**，方案 §3.6）
        const evidence = [];
        const limit = Math.min(crops.length, opts.maxOcrCells);
        for (let i = 0; i < crops.length; i++) {
            const crop = crops[i];
            // ⚠️ OCR 一律走「**整帧 + region**」：这是真机实测可用的路径（2026-09-19）；
            //    裁出来的图对象直接丢给 detect 不一定被接受，失败还会静默掉。
            // 变化缓存：指纹没变 ⇒ 这格没动，直接复用上一帧的字形结论（省一次 42ms 的 OCR）。
            const fingerprint = opts.cellCache ? fingerprintOf(crop.colors) : 0;
            const cached = (opts.cellCache && fingerprint) ? cellCache[crop.index] : null;
            let ocrVerdict = null;
            if (cached && cached.fp === fingerprint && cached.ocr) {
                ocrVerdict = cached.ocr;
                ocrVerdict.fromCache = true;
                stats.cellsCached += 1;
            } else {
                if (i < limit) {
                    // ★ 2026-09-21「先灰度」：主读默认走灰度（clip+gray）；彩色只在下面的兜底里出现
                    ocrVerdict = grayFirst
                        ? (ocrCellGray(image, crop, cropper.cropBoxFor(grid, crop.col, crop.row, opts.cropOptions)) ||
                            { state: "unknown", reason: "灰度路没有任何读数" })
                        : ocrCell(image, crop, cropper.cropBoxFor(grid, crop.col, crop.row, opts.cropOptions));
                    if (grayFirst) {
                        stats.grayPrimary += 1;
                        if (ocrVerdict.cls) {
                            stats.ocrOk += 1;      // 与 ocrOne 同口径（灰度路内部不计，这里补上）
                        }
                        if (ocrVerdict.grayLow) {
                            stats.grayLow += 1;    // 低分灰度读数被丢的次数（随后会走彩色兜底）
                        }
                    }
                } else {
                    ocrVerdict = { state: "unknown", reason: "超出单帧 OCR 上限" };
                }
                if (opts.cellCache && fingerprint) {
                    cellCache[crop.index] = { fp: fingerprint, ocr: ocrVerdict };
                }
            }
            // ★ 颜色证据：**优先走引擎找色**（在 YOLO 框里数红/黑墨迹；用户口径 2026-09-20），
            //   没有找色能力的宿主自动退回采样 + 老分类器（`ink-color.evidenceFor` 负责选通道）。
            //   多帧策略仍然是同一套（单帧翻面保留上一帧）。
            const colorVerdict = colorVoter.vote(crop.index, inkColor.evidenceFor(h, image, crop.box, opts.inkOptions));
            // ★ 兜底重读：主读通道没读出字形 + 找色说有墨迹 ⇒ 换**另一条通道**再读一次。
            //   主读=灰度（默认）⇒ 兜底走彩色（整帧 + region）；
            //   主读=彩色（老顺序）⇒ 兜底走灰度（见 ocrCellGray）。
            //   宿主不给 `prepareRegion` 时：灰度主读自动退回彩色主读，灰度兜底也自动不试（老行为）。
            if (!ocrVerdict.cls && !ocrVerdict.fromCache &&
                colorVerdict.side !== inkColor.SIDE_UNKNOWN) {
                if (grayFirst) {
                    if (i < limit) {
                        const colorSecond = ocrCell(image, crop, cropper.cropBoxFor(grid, crop.col, crop.row, opts.cropOptions));
                        stats.colorRetries += 1;
                        if (colorSecond && colorSecond.cls) {
                            ocrVerdict = colorSecond;
                            stats.colorRetryHits += 1;
                            if ((colorSecond.score || 0) >= opts.ocrGrayMinScore) {
                                log("逐格彩色兜底救回 1 格：(" + crop.col + "," + crop.row + ") = " + colorSecond.glyph +
                                    "（字形分 " + (colorSecond.score || 0).toFixed(2) + "，灰度读不出/分太低）");
                            }
                            if (opts.cellCache && fingerprint) {
                                // 与主读结果同等地位地进缓存：像素指纹没变就不用再试一遍
                                cellCache[crop.index] = { fp: fingerprint, ocr: ocrVerdict };
                            }
                        }
                    }
                } else if (typeof h.prepareRegion === "function") {
                    const grayVerdict = ocrCellGray(image, crop, cropper.cropBoxFor(grid, crop.col, crop.row, opts.cropOptions));
                    stats.grayRetries += 1;
                    if (grayVerdict && grayVerdict.cls) {
                        ocrVerdict = grayVerdict;
                        stats.grayHits += 1;
                        if (ocrVerdict.state === "ok" || ocrVerdict.state === "low") {
                            stats.ocrOk += 1;
                        }
                        log("逐格灰度重试救回 1 格：(" + crop.col + "," + crop.row + ") = " + ocrVerdict.glyph +
                            "（字形分 " + (ocrVerdict.score || 0).toFixed(2) + "，彩色读不出）");
                        if (opts.cellCache && fingerprint) {
                            // 与彩色结果同等地位地进缓存：像素指纹没变就不用再试一遍
                            cellCache[crop.index] = { fp: fingerprint, ocr: ocrVerdict };
                        }
                    } else if (grayVerdict && grayVerdict.grayLow) {
                        // 救不回来也要把结果写进缓存（否则环还在时每帧都白试一遍）
                        stats.grayLow += 1;
                        log("逐格灰度重试：(" + crop.col + "," + crop.row + ") 只读出「" + grayVerdict.grayLow.glyph +
                            "」但只有 " + (grayVerdict.grayLow.score || 0).toFixed(2) + " 分（低于 0.60）→ 丢弃，那格保持未知");
                        if (opts.cellCache && fingerprint) {
                            cellCache[crop.index] = { fp: fingerprint, ocr: grayVerdict };
                        }
                    }
                }
            }
            if (!ocrVerdict.fromCache) {
                if (ocrVerdict.state === "unknown") {
                    stats.ocrUnknown += 1;
                } else if (ocrVerdict.state === "error") {
                    // ⚠️ 错误也要计数/留痕：不然「一次都没调」和「调了全报错」在日志里长得一样
                    stats.ocrError += 1;
                    if (!firstOcrError) {
                        firstOcrError = ocrVerdict.reason || "未说明";
                        log("OCR 首次报错：" + firstOcrError);
                    }
                }
            }
            if (colorVerdict.side === pieceColor.SIDE_UNKNOWN) {
                stats.colorUnknown += 1;
            }
            // ★ 兵种**只认 OCR 字形**：YOLO 只负责「哪儿有子」（定位），类别字母一律不参与。
            //   用户口径（2026-09-20）+ 真机证据：
            //     · 检测模型对 車/炮/馬 本来就会混（真机 (8,9) 实测：框说 車、字形说 炮，
            //       两边一「冲突」，验证器就把整帧否掉）；
            //     · 以前还拿它当兜底（OCR 认不出时用框的类别填）和「一致就加权」的印证 ——
            //       等于让只该管定位的模型去决定兵种，判错还会把 OCR 的正确结果顶掉。
            //   现在：OCR 认不出 ⇒ 那格就是未知（`?`，交给规则层/唯一合法着法去补），绝不拿框的类别顶。
            const boxScore = Number(scoreOf[crop.index]) || 0;
            const labelHit = pieceOcr.classOfLabel(labelOf[crop.index]);   // 只用于诊断对比
            let cls = ocrVerdict.cls || null;
            let clsSource = cls ? "ocr" : null;
            let classScore = ocrVerdict.score || 0;
            if (cls && labelHit && labelHit.cls !== cls) {
                // 框的类别与字形不一致：**不改兵种**，只计数（这种格子最容易出问题，日志里要看得见）。
                // ⚠️ 只在**新算的这一帧**计数：命中缓存的格每帧都会走一遍这段逻辑。
                if (!ocrVerdict.fromCache) {
                    stats.labelMismatch += 1;
                }
            }
            evidence.push({
                index: crop.index,
                col: crop.col,
                row: crop.row,
                cls: cls,
                side: colorVerdict.side,
                detectionScore: boxScore,
                ocrScore: classScore,
                // 颜色分：把「红黑得分差」归一化到 0~1。
                // 分类器已经要求 gap ≥ 0.18 才敢出声，所以 gap ≥ 0.35 视为「颜色证据饱和」；
                // 直接用原始 gap 会让黑方（实测 0.63）拉不满 0.25 权重，好棋也被 0.78 门槛卡掉。
                colorScore: colorScoreOf(colorVerdict),
                colorGap: colorVerdict.confidence || 0,
                clsSource: clsSource,
                text: ocrVerdict.text || null,
                box: crop.box,
                ocrState: ocrVerdict.state,
                colorReason: colorVerdict.reason || ""
            });
        }
        // ⑥ 证据融合（方案 §3.7/§3.8）
        clock.mark("逐格(OCR+找色+融合)");
        // ⚠️ 2026-09-19 真机事故：这里原来每次 `createFuser()` 新建一个，
        //    历史只有 1 帧 ⇒ “两帧一致才确认”永远达不成，整盘永远是 `?`。
        //    融合器必须跟流水线实例绑定，跨帧累积。
        const fuser = fuserFor(ctx);
        const fused = fuser.push(evidence);
        // ⑦ 规则安全门（方案 §3.9）
        //    先按「两路证据一致 + 九宫约束」补齐唯一的帅/将，再交给规则门校验（见 inferKings）。
        const board = fused.chars.slice();
        const inferred = inferKings(board, evidence);
        if (inferred.length) {
            stats.inferred += inferred.length;
            for (let i = 0; i < inferred.length; i++) {
                log("规则补全：(" + inferred[i].col + "," + inferred[i].row + ") = " + inferred[i].char +
                    "（字形分 " + (inferred[i].ocrScore || 0).toFixed(2) + "，来源 " + inferred[i].clsSource + "）");
            }
        }
        // ★ 帅/将**连证据都没有**时（检测框没检出）：用 board-scan 去九宫带证据找一遍，
        //   而不是让调用方把整帧丢掉退回旧路径（真机 2026-09-20「红帅数量 0」的现场）。
        const rescued = rescueKings(image, grid, board, boxesByIndex, labelOf);
        clock.mark("抢救/规则补全");
        for (let i = 0; i < rescued.length; i++) {
            log("帅/将抢救：(" + rescued[i].col + "," + rescued[i].row + ") = " + rescued[i].char +
                (rescued[i].viaYolo
                    ? "（YOLO 框类别当候选 + 九宫 + 找色颜色）"
                    : "（board-scan 字形 " + (rescued[i].glyph || "-") + " + 找色颜色）") +
                " —— 整帧不必退回旧路径");
        }
        let validation = null;
        if (opts.validate) {
            validation = boardValidator.validate(board, {
                allowUnknown: true,
                side: ctx.side,
                legalMoves: ctx.legalMoves
            });
            if (!validation.ok) {
                stats.rejected += 1;
            }
        }
        return {
            ok: true,
            reason: "",
            cells: fused.cells,
            // ⚠️ **权威字段是 `chars`**（已含规则层补全的帅/将）；`rawChars` 是融合层原样输出，只给诊断对比用。
            //    以前 `chars` 给融合结果、`board` 给补全结果，不同调用方读不同字段会拿到两种盘面（2026-09-20 评审指出）。
            chars: board,
            rawChars: fused.chars,
            board: board,
            evidence: evidence,
            fusion: { unknown: fused.unknown, confirmed: fused.confirmed, conflicts: fused.conflicts },
            validation: validation,
            detection: { boxes: detected.boxes.length, mapped: items.length, rejected: mapped.rejected },
            timing: clock.summary("收尾"),
            stats: stats
        };
    }

    function reset() {
        colorVoter.reset();
        if (fuser) {
            fuser.reset();
        }
        clearCache();
    }

    /** 清空逐格缓存（重新定位、网格变了、或调用方知道盘面变了的时候用） */
    function clearCache() {
        for (const key in cellCache) {
            delete cellCache[key];
        }
        cellCacheGridVersion = null;
    }

    /** 主动丢掉跨帧历史（例如「重新定位」「切前台」后，旧帧不该再参与投票） */
    function resetFusion() {
        if (fuser) {
            fuser.reset();
        }
    }

    function describe() {
        return "视觉流水线：帧 " + stats.frames + "｜框 " + stats.boxes + "｜映射 " + stats.mapped +
            "｜OCR 成功 " + stats.ocrOk + "／未定 " + stats.ocrUnknown + "／报错 " + stats.ocrError +
            "｜框/字形打架 " + stats.labelMismatch + "｜规则补全 " + stats.inferred +
            "｜缓存命中 " + stats.cellsCached + "｜灰度重试 " + stats.grayRetries + "（救回 " + stats.grayHits + "／太低丢 " + stats.grayLow + "）" +
            "｜颜色未定 " + stats.colorUnknown + "｜规则否决 " + stats.rejected;
    }

    return {
        config: opts,
        recognize: recognize,
        ocrOne: ocrOne,
        reset: reset,
        resetFusion: resetFusion,
        clearCache: clearCache,
        stats: function () { return Object.assign({}, stats, { color: colorVoter.stats() }); },
        describe: describe
    };
}

/** FEN（只读用途：给日志/校验看） */
function toFen(board, side) {
    try {
        return fenModule.buildFen(board, side === "b" || side === "BLACK" ? "b" : "w");
    } catch (error) {
        return null;
    }
}

module.exports = { create: create, toFen: toFen, DEFAULTS: DEFAULTS };
