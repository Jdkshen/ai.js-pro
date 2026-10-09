// @engine quickjs
/**
 * 识别 · 定位 · 绘制 —— 字形链「真·单文件版」（**生成物，别手改**）
 * ════════════════════════════════════════════════════════════════════════
 * 由 tools/build-solo-glyph.js 生成：内联以下工程模块（逐字拷贝，见文件内标注）——
 *   src/util/defaults.js
 *   src/chess/fen.js
 *   src/chess/consistency.js
 *   src/vision/glyph-recognizer.js
 *   src/vision/glyph-resolve.js
 *   src/vision/overlay-draw.js
 * 改了上面任一模块（或生成器里的主流程）后，在工程根跑：node tools/build-solo-glyph.js
 *
 * 与「测-工程版.js」的差别：不 require 任何工程文件 —— 一个文件走天下
 *   （字形模板取脚本所在工程的 assets/glyph-tpl，失败才回退设备上遗留的 files/sample 工程副本）。
 * 与「测.js」的差别：走**字形模板链**（≈99.6% 准，带多尺度抢救 + 盘面级判决），
 *   不是随包 YOLO（≈九成，会误检漏检）；定位用 images.analyzeXiangqiBoard（原生 Canny/Hough），
 *   本版不带「图色格线」回退（那是工程版 locate-board 里的那条）。
 *
 * 跑法：放到任何位置直接运行；参数都在主流程开头（KEEP_MS / DELAY_MS / DRAW_MODE / IMAGE_PATH）。
 * 日志前缀 [字形单]；结果文本写 /sdcard/xiangqi-p4/ce-glyph-solo.txt（失败退 /sdcard）。
 * 结束打点：GLYPH_SOLO_OK {json} / GLYPH_SOLO_ERR <原因>。
 */
    "use strict";

    // ═══════════════════════════ 迷你模块系统 ═══════════════════════════
    // 内联的工程模块用 __define 登记、用 __require 取用（每个工厂函数隔离作用域）。
    var __factories = {};
    var __moduleCache = {};
    function __define(id, factory) {
        __factories[id] = factory;
    }
    function __resolveId(fromId, request) {
        if (request.charAt(0) !== ".") {
            return request;
        }
        var base = fromId.split("/");
        base.pop();
        var parts = request.split("/");
        for (var i = 0; i < parts.length; i++) {
            var part = parts[i];
            if (part === "" || part === ".") {
                continue;
            }
            if (part === "..") {
                base.pop();
            } else {
                base.push(part);
            }
        }
        return base.join("/");
    }
    function __require(id) {
        if (Object.prototype.hasOwnProperty.call(__moduleCache, id)) {
            return __moduleCache[id].exports;
        }
        var factory = __factories[id];
        if (!factory) {
            throw new Error("内联模块缺失：" + id);
        }
        var module = { exports: {} };
        __moduleCache[id] = module;
        factory(module, module.exports, function (request) {
            return __require(__resolveId(id, request));
        });
        return module.exports;
    }


// ═══════════ 内联模块：src/util/defaults.js（逐字拷贝自工程） ═══════════
__define("src/util/defaults.js", function (module, exports, require) {
// @engine quickjs
/**
 * 默认值合并。
 *
 * 为什么要单独写一个：`Object.assign({}, DEFAULTS, options)` 在 **options 里存在该键但值是
 * `undefined`** 时会把默认值覆盖成 undefined —— 真机踩过两次：
 *   1) `frameQuality: undefined` → 空格全被判成「未知」（90 格全 ?）
 *   2) `sampleStep: undefined` → 采样步进变成 NaN → 阵营一个都判不出来
 * 调用方「没传这个字段」时，本意就是「用默认值」，所以这里显式忽略 undefined。
 */
"use strict";

function mergeDefaults(defaults, options) {
    const result = {};
    const hasOwn = Object.prototype.hasOwnProperty;
    for (const key in defaults) {
        if (hasOwn.call(defaults, key)) {
            result[key] = defaults[key];
        }
    }
    if (options) {
        for (const key in options) {
            if (hasOwn.call(options, key) && options[key] !== undefined) {
                result[key] = options[key];
            }
        }
    }
    return result;
}

module.exports = {
    mergeDefaults: mergeDefaults
};
});

// ═══════════ 内联模块：src/chess/fen.js（逐字拷贝自工程） ═══════════
__define("src/chess/fen.js", function (module, exports, require) {
// @engine quickjs
/**
 * 中国象棋 FEN：生成、解析、静态校验。（纯 JS，不依赖任何平台接口，可离线测试）
 *
 * 规范坐标（FEN 图面）：col 0..8 左→右，row 0..9 上→下，红方在下。
 * 大写 = 红方 R N B A K C P；小写 = 黑方。
 */
"use strict";

const ROWS = 10;
const COLS = 9;
const CELLS = ROWS * COLS;
const RED_CHARS = "RNBAKCP";
const BLACK_CHARS = "rnbakcp";
const ALL_CHARS = RED_CHARS + BLACK_CHARS;

const START_FEN = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1";

/** 每类棋子上限（标准象棋）。 */
const MAX_PER_TYPE = { R: 2, N: 2, B: 2, A: 2, K: 1, C: 2, P: 5 };

function index(col, row) {
    return row * COLS + col;
}

function colOf(idx) {
    return idx % COLS;
}

function rowOf(idx) {
    return Math.floor(idx / COLS);
}

function isRed(ch) {
    return ch >= "A" && ch <= "Z";
}

/**
 * 由 90 格生成 FEN 的**布局段**（不含轮次等字段）。
 * 单独拿出来是因为识别链路经常只能给出布局（轮次得看界面，或由时序推断）。
 * cells[i] = 棋子字符、null（空格）或 "?"（未知）。
 */
function layoutOf(cells, options) {
    const opts = options || {};
    if (!cells || cells.length !== CELLS) {
        throw new Error("cells 必须是长度 90 的数组");
    }
    const rows = [];
    for (let row = 0; row < ROWS; row++) {
        let line = "";
        let empty = 0;
        for (let col = 0; col < COLS; col++) {
            const ch = cells[index(col, row)];
            if (!ch) {
                empty++;
                continue;
            }
            if (ch === "?") {
                if (!opts.allowUnknown) {
                    throw new Error("存在未知格，不能生成 FEN（第 " + (row + 1) + " 行第 " + (col + 1) + " 列）");
                }
            } else if (ALL_CHARS.indexOf(ch) < 0) {
                throw new Error("非法棋子字符：" + ch);
            }
            if (empty > 0) {
                line += String(empty);
                empty = 0;
            }
            line += ch;
        }
        if (empty > 0) {
            line += String(empty);
        }
        rows.push(line);
    }
    return rows.join("/");
}

/** 由 90 格生成 FEN。cells[i] = 棋子字符、null（空格）或 ""（未知，用 '?' 表示）。 */
function buildFen(cells, sideToMove, options) {
    const opts = options || {};
    const layout = layoutOf(cells, opts);
    const side = sideToMove === "b" || sideToMove === "B" ? "b" : (sideToMove === "w" || sideToMove === "W" ? "w" : "-");
    if (side === "-") {
        throw new Error("轮次未知时不能生成带 side 字段的 FEN");
    }
    return layout + " " + side + " - - 0 1";
}

/** 解析 FEN；坏 FEN 一律报错，不做「补空格静默修复」。 */
function parseFen(fen) {
    const errors = [];
    const text = String(fen == null ? "" : fen).trim();
    if (text.length === 0) {
        return { ok: false, errors: ["FEN 为空"], board: null, sideToMove: null };
    }
    const parts = text.split(/\s+/);
    if (parts.length < 2) {
        errors.push("FEN 至少需要「布局 + 轮次」两段");
    }
    const rows = String(parts[0]).split("/");
    const board = new Array(CELLS);
    for (let i = 0; i < CELLS; i++) {
        board[i] = null;
    }
    if (rows.length !== ROWS) {
        errors.push("FEN 必须有 " + ROWS + " 行，实际 " + rows.length + " 行");
    }
    for (let row = 0; row < rows.length && row < ROWS; row++) {
        const line = rows[row];
        let col = 0;
        for (let i = 0; i < line.length; i++) {
            const ch = line.charAt(i);
            if (ch >= "0" && ch <= "9") {
                if (ch === "0") {
                    errors.push("第 " + (row + 1) + " 行出现非法数字 0");
                }
                col += Number(ch);
                continue;
            }
            if (ALL_CHARS.indexOf(ch) < 0) {
                errors.push("第 " + (row + 1) + " 行出现非法字符 " + ch);
                continue;
            }
            if (col >= COLS) {
                errors.push("第 " + (row + 1) + " 行超过 " + COLS + " 列");
                break;
            }
            board[index(col, row)] = ch;
            col++;
        }
        if (col !== COLS) {
            errors.push("第 " + (row + 1) + " 行展开为 " + col + " 格（应为 " + COLS + "）");
        }
    }
    const side = parts.length >= 2 ? parts[1].toLowerCase() : "";
    if (side !== "w" && side !== "b") {
        errors.push("轮次字段必须是 w 或 b，实际「" + (parts.length >= 2 ? parts[1] : "") + "」");
    }
    return {
        ok: errors.length === 0,
        errors: errors,
        board: board,
        sideToMove: side === "w" || side === "b" ? side : null
    };
}

function inPalace(col, row, red) {
    if (col < 3 || col > 5) {
        return false;
    }
    return red ? (row >= 7 && row <= 9) : (row >= 0 && row <= 2);
}

/**
 * 静态局面校验：只报告「与标准象棋规则不符」的可疑点，不替用户改棋盘。
 * 返回 { errors: [...], warnings: [...] }。
 */
function validateBoard(board) {
    const errors = [];
    const warnings = [];
    /** 可归因到具体格子的违规，供「一致性修复」把可疑格降级为未知（errors/warnings 保持原形不变） */
    const violations = [];
    const counts = {};
    let total = 0;
    for (let i = 0; i < CELLS; i++) {
        const ch = board[i];
        if (!ch) {
            continue;
        }
        total++;
        counts[ch] = (counts[ch] || 0) + 1;
        const red = isRed(ch);
        const col = colOf(i);
        const row = rowOf(i);
        const upper = ch.toUpperCase();
        if (upper === "K" && !inPalace(col, row, red)) {
            errors.push((red ? "红帅" : "黑将") + "不在九宫内（第 " + (row + 1) + " 行第 " + (col + 1) + " 列）");
            violations.push({ index: i, char: ch, kind: "palace", message: "将帅不在九宫" });
        }
        if (upper === "A" && !inPalace(col, row, red)) {
            errors.push((red ? "红仕" : "黑士") + "不在九宫内（第 " + (row + 1) + " 行第 " + (col + 1) + " 列）");
            violations.push({ index: i, char: ch, kind: "palace", message: "仕士不在九宫" });
        }
        if (upper === "B") {
            // 过河 = 到了对方半边（红方行 < 5，黑方行 > 4）—— 这是硬错误。
            const ownSideRows = red ? [5, 6, 7, 8, 9] : [0, 1, 2, 3, 4];
            if (ownSideRows.indexOf(row) < 0) {
                errors.push((red ? "红相" : "黑象") + "过河（第 " + (row + 1) + " 行第 " + (col + 1) + " 列）");
                violations.push({ index: i, char: ch, kind: "river", message: "相象过河" });
            }
            // 真·象位：红相 {5(河沿 c4/g4), 7(a2/e2/i2), 9(底线)}，黑象对称 {4,2,0}。
            // ⚠️ 2026-09-21 真机事故：旧集合写成 {7,8,9} —— 漏了河沿的 5，还把非象位行 8 放行。
            //    引擎走标准着法「飞相 e2→c4」后，河沿那枚相每帧被判「过河」⇒ inspect 永远不过
            //    ⇒ 算棋全失败 ⇒ 无限 RECOVERING（K40 卡死 14 分钟）。行 5 是**合法象位**，别删。
            const ownElephant = red ? (row === 5 || row === 7 || row === 9) : (row === 0 || row === 2 || row === 4);
            if (!ownElephant) {
                warnings.push((red ? "红相" : "黑象") + "落在非象位行（第 " + (row + 1) + " 行）");
            }
        }
        if (upper === "P") {
            const crossed = red ? row <= 4 : row >= 5;
            const startRow = red ? 6 : 3;
            const behind = red ? row > startRow : row < startRow;
            if (behind) {
                errors.push((red ? "红兵" : "黑卒") + "退到起始线之后（第 " + (row + 1) + " 行）");
                violations.push({ index: i, char: ch, kind: "pawn-back", message: "兵卒退到起始线之后" });
            }
            // 兵/卒未过河时只可能在偶数列（0/2/4/6/8）—— 告警应该在**奇数列**时发
            if (!crossed && col % 2 !== 0) {
                warnings.push((red ? "红兵" : "黑卒") + "未过河却不在兵位列（第 " + (col + 1) + " 列）");
            }
        }
    }
    if (total > 32) {
        errors.push("棋子总数 " + total + " 超过标准上限 32");
    }
    for (let t = 0; t < RED_CHARS.length; t++) {
        const red = RED_CHARS.charAt(t);
        const black = BLACK_CHARS.charAt(t);
        const max = MAX_PER_TYPE[red];
        if ((counts[red] || 0) > max) {
            errors.push("红" + red + " 有 " + counts[red] + " 个，超过上限 " + max);
            pushCountViolations(violations, board, red, "红" + red + "数量超上限");
        }
        if ((counts[black] || 0) > max) {
            errors.push("黑" + black + " 有 " + counts[black] + " 个，超过上限 " + max);
            pushCountViolations(violations, board, black, "黑" + black + "数量超上限");
        }
    }
    if ((counts.K || 0) !== 1 || (counts.k || 0) !== 1) {
        // 少子残局也可能合法，但「双方各一枚将帅」是标准在局状态；缺了要显式报告。
        errors.push("将帅数量异常：红帅 " + (counts.K || 0) + " 枚、黑将 " + (counts.k || 0) + " 枚");
        if ((counts.K || 0) > 1) {
            pushCountViolations(violations, board, "K", "红帅数量超上限");
        }
        if ((counts.k || 0) > 1) {
            pushCountViolations(violations, board, "k", "黑将数量超上限");
        }
    }
    return { errors: errors, warnings: warnings, violations: violations };
}

/** 某一类超上限时，把所有该类棋子都列成违规（具体降级哪一个交给修复逻辑按分数挑）。 */
function pushCountViolations(violations, board, ch, message) {
    for (let i = 0; i < CELLS; i++) {
        if (board[i] === ch) {
            violations.push({ index: i, char: ch, kind: "type-count", message: message });
        }
    }
}

function pieceCount(board) {
    let n = 0;
    for (let i = 0; i < CELLS; i++) {
        if (board[i]) {
            n++;
        }
    }
    return n;
}

const XQ_FEN = {
    ROWS: ROWS,
    COLS: COLS,
    CELLS: CELLS,
    START_FEN: START_FEN,
    index: index,
    colOf: colOf,
    rowOf: rowOf,
    isRed: isRed,
    inPalace: inPalace,
    layoutOf: layoutOf,
    buildFen: buildFen,
    parseFen: parseFen,
    validateBoard: validateBoard,
    pieceCount: pieceCount
};

module.exports = XQ_FEN;
});

// ═══════════ 内联模块：src/chess/consistency.js（逐字拷贝自工程） ═══════════
__define("src/chess/consistency.js", function (module, exports, require) {
// @engine quickjs
/**
 * 一致性修复：用棋规反查「识别结果里哪些格不可信」，把它们降级成未知，而不是当成事实。
 *
 * 动机（真机实测）：单帧识别出 32 个棋子、阵营 16 红 16 黑，看着挺好，但拿棋规一校验就露馅 ——
 * 「红仕有 3 个（上限 2）」「黑卒退到起始线之后（第 3 行）」。这两类错误是**可归因到具体格子**的，
 * 所以不必整盘作废：把违规的格子降级成 '?'，剩下的照用 —— 后面还有多帧融合和时序来补。
 *
 * 优先级：格子级违规（九宫/过河/兵卒后退）证据更硬，先降它们；数量超限只是数量矛盾，
 * 拿不出"哪个是假的"，所以按分数最低的先降。
 *
 * 纯 JS，不碰平台接口。
 */
"use strict";

const fen = require("./fen.js");
const defaults = require("../util/defaults.js");

const STATE_PIECE = "PIECE";
const STATE_UNKNOWN = "UNKNOWN";

/** 各兵种单侧上限（与 fen.js 的 MAX_PER_TYPE 一致）。 */
const MAX_PER_TYPE = { R: 2, N: 2, B: 2, A: 2, K: 1, C: 2, P: 5 };

const DEFAULTS = {
    /** 最多迭代几轮（每轮降一格 / 翻一格） */
    maxRounds: 6,
    /** 低于这个分数的格子更容易被判为可疑（数量超限时优先降它） */
    preferLowScore: true,
    /**
     * 数量超限时先试「改阵营」而不是直接丢掉：
     * 真机实测（天天象棋皮肤）单帧的红黑判错会让「红仕 3 个 / 红兵 8 个」，
     * 这时丢掉棋子会**真的少一个子**，而翻一色去正好对得上 —— 证据上前者更可信。
     */
    allowColourFlip: true
};

/** 把 cells 变成 board（只保留确定有子的格）。 */
function boardOf(cells) {
    const board = new Array(90);
    for (let i = 0; i < 90; i++) {
        const cell = cells[i];
        board[i] = cell && cell.state === STATE_PIECE && cell.piece ? cell.piece : null;
    }
    return board;
}

function demote(cells, index, reason, log) {
    const cell = cells[index];
    if (!cell) {
        return;
    }
    log.push({
        index: index,
        col: index % 9,
        row: Math.floor(index / 9),
        char: cell.piece,
        score: cell.score,
        reason: reason
    });
    cell.state = STATE_UNKNOWN;
    cell.piece = null;
    cell.conflict = true;
}

function flipChar(ch) {
    const text = String(ch == null ? "" : ch);
    return text === text.toLowerCase() ? text.toUpperCase() : text.toLowerCase();
}

function countOf(board, ch) {
    let n = 0;
    for (let i = 0; i < board.length; i++) {
        if (board[i] === ch) {
            n += 1;
        }
    }
    return n;
}

/** 硬违规 = 「这个格子本身就不合法」（九宫 / 过河 / 兵卒后退）。 */
function hardViolationsOf(validation) {
    const list = (validation && validation.violations) || [];
    return list.filter(function (item) {
        return item.kind === "palace" || item.kind === "river" || item.kind === "pawn-back";
    });
}

/**
 * 试翻一格的颜色：翻完「至少不引入新硬违规、且硬违规总数没变化」就采纳。
 *
 * 为什么值得优先试：真机实测（天天象棋皮肤）里，黑「士」被判成红「仕」时，
 * 它在**黑方九宫**里就同时触发「仕士不在九宫」和「红仕 3 个」两条违规 ——
 * 真正的错因是颜色，直接翻回去两违规一起消失；而丢掉这枚子会让棋盘**真的少一个子**。
 *
 * @returns {{index,from,to,reason}|null}
 */
function tryFlipCell(cells, index, validation, opts, reason) {
    const cell = cells[index];
    if (!cell || cell.state !== STATE_PIECE || !cell.piece) {
        return null;
    }
    const limit = MAX_PER_TYPE[String(cell.piece).toUpperCase()];
    if (limit == null) {
        return null;
    }
    const from = cell.piece;
    const to = flipChar(from);
    const board = boardOf(cells);
    if (countOf(board, to) + 1 > limit) {
        return null; // 另一色已满 → 翻过去会超限，只能降级
    }
    const hardBefore = hardViolationsOf(validation).length;
    cell.piece = to;
    const after = fen.validateBoard(boardOf(cells));
    const hardAfter = hardViolationsOf(after).length;
    // 翻完必须真的变好（硬违规减少）或不引入新的；否则回退
    if (hardAfter > hardBefore || (hardAfter === hardBefore && hardBefore > 0)) {
        cell.piece = from;
        return null;
    }
    return {
        index: index,
        col: index % 9,
        row: Math.floor(index / 9),
        from: from,
        to: to,
        score: cell.score,
        reason: reason || ("改阵营 " + from + "→" + to)
    };
}

/**
 * 试着把「数量超限的那一色」里分数最低的一枚翻成另一色。
 * 只有当翻完两色都不超限、且不会引入新的硬违规时才采纳（否则返回 null，交给降级处理）。
 */
function tryFlipColour(cells, ch, validation, opts) {
    if (opts.allowColourFlip === false) {
        return null;
    }
    const other = flipChar(ch);
    const max = MAX_PER_TYPE[String(ch).toUpperCase()];
    if (max == null) {
        return null;
    }
    const board = boardOf(cells);
    if (countOf(board, ch) <= max) {
        return null;
    }
    if (countOf(board, other) + 1 > max) {
        return null; // 另一色也满了 → 真的多出一个子，只能降级
    }
    let target = null;
    for (let i = 0; i < 90; i++) {
        const cell = cells[i];
        if (!cell || cell.state !== STATE_PIECE || cell.piece !== ch) {
            continue;
        }
        const score = cell.score == null ? 0 : cell.score;
        if (target === null || score < target.score) {
            target = { index: i, score: score };
        }
    }
    if (target === null) {
        return null;
    }
    return tryFlipCell(cells, target.index, validation, opts,
        "数量超限但另一色未满 → 改阵营（" + ch + "→" + other + "）");
}

/**
 * @param cells 90 格（assign 的产物；会被就地修改）
 * @returns { cells, demoted: [...], flipped: [...], errors, warnings, rounds, changed }
 */
function repair(cells, options) {
    const opts = defaults.mergeDefaults(DEFAULTS, options);
    const demoted = [];
    const flipped = [];
    let rounds = 0;
    let validation = null;
    for (let round = 0; round < opts.maxRounds; round++) {
        const board = boardOf(cells);
        validation = fen.validateBoard(board);
        const violations = validation.violations || [];
        if (violations.length === 0) {
            break;
        }
        // 1. 先处理格子级违规（九宫 / 过河 / 兵卒后退）—— 这类"这个格子本身就不合法"证据最硬
        const hard = violations.filter(function (item) {
            return item.kind === "palace" || item.kind === "river" || item.kind === "pawn-back";
        });
        if (hard.length > 0) {
            const target = opts.preferLowScore
                ? hard.reduce(function (worst, item) {
                    const score = cells[item.index] ? (cells[item.index].score || 0) : 0;
                    return (worst === null || score < worst.score) ? { index: item.index, score: score, message: item.message } : worst;
                }, null)
                : { index: hard[0].index, score: 0, message: hard[0].message };
            // 1a. 先想「是不是红黑判错了」：翻一色就能合法的话不要丢子
            if (opts.allowColourFlip !== false) {
                const flipResult = tryFlipCell(cells, target.index, validation, opts,
                    "硬违规优先改阵营（" + target.message + "）");
                if (flipResult) {
                    flipped.push(flipResult);
                    rounds++;
                    continue;
                }
            }
            demote(cells, target.index, target.message, demoted);
            rounds++;
            continue;
        }
        // 2. 再处理数量超限：这类拿不出"哪个是假的"。
        //    2a) 先试「改阵营」：红黑分错时这样能**保住棋子**（而且数量正好对得上）；
        //    2b) 改不了（另一色也满）才按分数最低的降级。
        const count = violations[0];
        const flipResult = tryFlipColour(cells, count.char, validation, opts);
        if (flipResult) {
            flipped.push(flipResult);
            rounds++;
            continue;
        }
        const sameChar = violations.filter(function (item) { return item.char === count.char; });
        const target = sameChar.reduce(function (worst, item) {
            const score = cells[item.index] ? (cells[item.index].score || 0) : 0;
            return (worst === null || score < worst.score) ? { index: item.index, score: score, message: item.message } : worst;
        }, null);
        demote(cells, target.index, target.message, demoted);
        rounds++;
    }
    const board = boardOf(cells);
    const final = fen.validateBoard(board);
    // ---- 必需子缺失（将/帅）----
    // 真机事故（2026-09-14，K40 + 天天象棋）：模型把黑将漏检成**空格** → 盘面上凭空少一个将。
    // `validateBoard` 会把它报成 error，但「数量超限」那套修复**没有东西可降级**（空格不是违规格）
    // → violations 为空 → 什么都不做 → 驱动拿着一张没将的盘面永远推不出轮次。
    // 处理：将/帅**不可能被吃、也走不出九宫** ⇒ 它只可能在本方九宫的空格里 → 把这些空格标成未知，
    // 把「静默的错误盘面」变成「诚实的未知格」，交给上层的唯一解释补齐/闸门。
    const missing = [];
    if ((final.errors || []).length > 0) {
        const counts = {};
        for (let i = 0; i < 90; i++) {
            const ch = board[i];
            if (ch) {
                counts[ch] = (counts[ch] || 0) + 1;
            }
        }
        const palaceEmpty = function (rowFrom, rowTo, ch) {
            const out = [];
            for (let row = rowFrom; row <= rowTo; row++) {
                for (let col = 3; col <= 5; col++) {
                    const index = row * 9 + col;
                    if (!board[index]) {
                        out.push(index);
                    }
                }
            }
            return out;
        };
        if (!counts.K) {
            missing.push({ side: "红帅", char: "K", slots: palaceEmpty(7, 9, "K") });
        }
        if (!counts.k) {
            missing.push({ side: "黑将", char: "k", slots: palaceEmpty(0, 2, "k") });
        }
        for (let m = 0; m < missing.length; m++) {
            const slots = missing[m].slots;
            for (let s = 0; s < slots.length; s++) {
                demote(cells, slots[s], missing[m].side + "缺失（应该是漏检）：九宫内该格标为未知", demoted);
            }
            rounds++;
        }
    }
    const afterMissing = missing.length > 0 ? fen.validateBoard(boardOf(cells)) : final;
    return {
        cells: cells,
        demoted: demoted,
        flipped: flipped,
        changed: demoted.length > 0 || flipped.length > 0,
        rounds: rounds,
        errors: afterMissing.errors,
        warnings: afterMissing.warnings,
        missingRequired: missing.map(function (item) { return item.char; }),
        before: validation ? validation.errors : [],
        after: afterMissing.errors
    };
}

/** 把 chars（含 '?'）里的未知格对应到 cells 的 UNKNOWN（给修复后的布局用）。 */
function charsOf(cells) {
    const chars = new Array(90);
    for (let i = 0; i < 90; i++) {
        const cell = cells[i];
        if (!cell) {
            chars[i] = null;
        } else if (cell.state === STATE_PIECE) {
            chars[i] = cell.piece;
        } else if (cell.state === STATE_UNKNOWN) {
            chars[i] = "?";
        } else {
            chars[i] = null;
        }
    }
    return chars;
}

module.exports = {
    DEFAULTS: DEFAULTS,
    repair: repair,
    boardOf: boardOf,
    charsOf: charsOf
};
});

// ═══════════ 内联模块：src/vision/glyph-recognizer.js（逐字拷贝自工程） ═══════════
__define("src/vision/glyph-recognizer.js", function (module, exports, require) {
// @engine quickjs
/**
 * 「字形模板」识别（电脑 py 移植，2026-09-24）—— 源自 `.artifacts/k40-now/cv-chess-recognize-2.py`：
 *   逐格三道门（暗占比 → 内窗 → 亮面）判「有子」→ 11 个字形模板做 TM_CCOEFF_NORMED 匹配（阈值 0.70）
 *   → 車/馬/炮 用笔画颜色（暗像素平均 R-B）判阵营 → 90 格字符。
 *
 * 与 py 的差异（都有实测依据）：
 *   ① **行自适应窄窗 + 宽窗复核**（v2，2026-09-24）：py 用 ±12 对称窗是因为吃它自带的「山谷网格」；
 *      本模块吃工程 lattice 网格，9 帧 284 格实测字形**纵向按行累积漂移**（|dy|max=16、行9；
 *      行间 median 最大跳 6px、行内离散 ≤6px）—— 于是按行预测：用「上一个匹配到的格子的实测 dy
 *      中位数」当窗心修正，窄窗（横 ±SEARCH_X / 纵 ±SEARCH_Y_TIGHT）先匹配；没命中或得分
 *      < SAFE_CONF 才用宽窗（纵 ±SEARCH_Y，与 v1 等价）复核。窄窗把每模板的匹配点从 533 → 169。
 *   ①b **金字塔粗搜**（v3）：整帧缩一半 + 模板缩一半，先提名 top-K（每调用约 0.22× 成本），
 *      再在全尺寸上小窗精修；精修分 < SAFE_CONF 就回退「窄+宽」全搜。匹配再省 ~3-4×。
 *      另：匹配窗能落在一帧内时改走「clip 后匹配」（原生连续内存，实测 ~0.61×）。
 *      亮面门改成数「少数类」再把亮面算回来（同一个值，少一半找色点对象分配）。
 *   ② 支持跨分辨率：模板按「板距 / REF_STEP」缩放（`scaleFor` + `prepareTemplates`）；
 *      `prepareFor` 带缓存（同一模板数组 + 同一缩放比不重复缩放）。
 *   ③ 模板由 `loadTemplates` 从 `assets/glyph-tpl/` 加载；结果带 `profile` 分段计时（gates/match/side）。
 *   ④ **「空中子」模糊门（2026-10-04）**：认不准（'?'）的格，算窗内离散拉普拉斯方差；
 *      若 < 本帧「认准的格」中位 × MOVING_RATIO（0.5）→ 判「子在空中/没落稳」按空处理（chars='.'）。
 *      落子动画中间帧的子是运动模糊的——不该对它做字符判断；静止子余量 1.35× 以上、零误伤。
 *
 * 实测成绩（K40 + 浅木皮肤 9 帧盘面级评测集）：
 *   · 「多尺度抢救」之前：763/784 = 97.3%（保留作历史对照；那时的分数还被真值错误拖了 31 格）；
 *   · 2026-09-25 真值修正（31 处）+ 抢救后：806/809 = 99.6%，比电脑 py（805/809）多救回 skin-c 那格；
 *   · 2026-10-04 加「空中子模糊门」（MOVING_*）后：**809/809 = 100%** —— skin2-04 落子动画帧的 3 格
 *     判「子在空中」归空，284 个静止格零误伤（电脑上复现：`node tools/glyph-pc-eval.js`）。
 *   电脑上复现：`node tools/glyph-pc-eval.js`（不用刷机）。
 * 边界：模板按「浅木皮肤」采集，换皮肤需重采；跨分辨率靠 `prepareTemplates` 缩放。
 *
 * 宿主注入（tools/probe-glyph.js 有现成映射；离线自检用假宿主，见 self-check.js）：
 *   sizeOf / grayscale / clip / threshold / countColor / matchTemplate / pixel / red / green / blue /
 *   resize / read / nowMono
 */
"use strict";

const DEFAULTS = {
    /** 基准板距（像素）：模板采集尺度（K40 浅木皮肤 1080 宽 ≈114.85）。 */
    REF_STEP: 114.85,
    /** 字窗半宽（68×68）与字形中心相对格点偏移（与 py 一致）。 */
    TW: 34,
    ANCHOR_DY: -5,
    /** 搜索窗半宽（像素，不对称；见文件头 ①）。宽窗 = 复核窗（面积与 py 原 92×92 持平）。 */
    SEARCH_X: 6,
    SEARCH_Y: 20,
    /** 窄窗纵向半宽（行自适应；实测行间 median 跳 ≤6px、行内离散 ≤6px）。 */
    SEARCH_Y_TIGHT: 6,
    /** 窄窗得分低于它就再用宽窗复核（正常匹配 ≥0.9，0.70~0.82 属于「能对上但要复核」）。 */
    SAFE_CONF: 0.82,
    /** 金字塔粗搜（半尺寸）：粗层阈值 / 提名数（精修候选）/ 精修半径（全尺寸像素）。 */
    COARSE_CONF: 0.30,
    PYRAMID_TOP: 3,
    PYRAMID_R: 5,
    /** 模板相似度门限（TM_CCOEFF_NORMED）。 */
    CONF: 0.70,
    /** ① 有子：窗内暗像素（gray<140）占比 > 0.18。 */
    DARK_THR: 0.18,
    /** ② 内窗：字形必在格心附近（40×40 内窗暗占比 > 0.12）。 */
    INNER_THR: 0.12,
    /** ③ 亮面：真棋子窗内必有大片亮盘面（gray≥200 占比 ≥10%，≈ py 的 p90≥200）。 */
    BRIGHT_RATIO: 0.10,
    /** 阵营：匹配窗内暗像素（gray<150）平均 R-B > 45 判红。 */
    SIDE_RB: 45,
    SIDE_STEP: 4,
    SIDE_MIN_DARK: 30,
    /** ★ 「空中子」模糊门（2026-10-04）：认不准的格若整窗清晰度 <「认准的格」中位×MOVING_RATIO，
     *  判「子在空中/没落稳」（落子动画中间帧）按空处理（不再记未知、不再数它）。
     *  实测：284 个静止子离散拉普拉斯方差 ≥1289，空中子只有 248~395（差 4~5 倍）。 */
    MOVING_RATIO: 0.5,
    /** 清晰度采样步长（像素）与参考格上限（取前 N 个认准的格求中位基准）。 */
    MOVING_STEP: 3,
    MOVING_REF_CELLS: 5,
    /** ★ 多尺度抢救（2026-09-25）：读数「不放心」时用一组缩放模板再找一遍（见 rescueMatch）。
     *  1.10 = 棋子被点起/落子动画时游戏会把棋子放大（实测 1.10 档从 0.54 → 0.88）；
     *  其余档是「轻微缩放」的兜底，按「最可能先试」排序（命中就 break，省下后面的档）。 */
    RESCUE_SCALES: [1.10, 1.06, 1.18, 0.94],
    /** 抢救要拿到的分（比常规 SAFE_CONF 略松：抢救本来就是给「常规读不出」用的）。 */
    RESCUE_CONF: 0.80,
    /** 抢救结果还要求「与次名字形的分差」≥ 这个值（防「两个字形咬得很近时硬选一个」）。 */
    RESCUE_MARGIN: 0.12,
    /** 抢救搜索窗（与旧「选中放大」复核同口径：被点起/动画中的字形会平移，实测纵向漂 12px）。 */
    RESCUE_SEARCH_X: 8,
    RESCUE_SEARCH_Y: 22,
    /** 一帧最多抢救几格 + 抢救总耗时上限（抢救是「多尺度 × 11 模板」的全窗匹配，
     *  只给「读不出」的少数格用；超时就不再抢救剩下的格子，宁可先出别的结论）。 */
    RESCUE_MAX_CELLS: 3,
    RESCUE_BUDGET_MS: 2500
};

const TPL_NAMES = ["車", "馬", "象", "士", "將", "炮", "卒", "兵", "相", "仕", "帥"];
/** 红黑同形、需颜色判阵营的三字 → [红, 黑] 输出字符。 */
const SIDED = { "車": ["R", "r"], "馬": ["N", "n"], "炮": ["C", "c"] };
/** 其余八字按字形直接定阵营。 */
const FIXED = { "帥": "K", "仕": "A", "相": "B", "兵": "P", "將": "k", "士": "a", "象": "b", "卒": "p" };
const GLYPH_FOR_CHAR = { R: "車", r: "車", N: "馬", n: "馬", C: "炮", c: "炮",
    K: "帥", A: "仕", B: "相", P: "兵", k: "將", a: "士", b: "象", p: "卒" };

function pick(options, key) {
    return options && options[key] != null ? options[key] : DEFAULTS[key];
}

/** 当前帧的模板缩放比：板距均值 / 基准板距。 */
function scaleFor(grid, refStep) {
    const ref = refStep == null ? DEFAULTS.REF_STEP : refStep;
    return ((grid.stepX + grid.stepY) / 2) / ref;
}

/**
 * 按缩放比准备模板与窗口参数。scale≈1 原样返回（不无谓 resize）；
 * 否则把每个模板缩放到 2*TW 见方，并把 TW / ANCHOR_DY 一起缩放。
 * SEARCH_X/Y 不缩放——它们是「网格漂移容差」的像素值，与字形尺寸无关。
 * @return { templates: [{name, image}], TW, ANCHOR_DY }
 */
function prepareTemplates(host, templates, scale, options) {
    const opts = options || {};
    const baseTW = pick(opts, "TW");
    const baseAnchor = pick(opts, "ANCHOR_DY");
    if (!(scale > 0) || Math.abs(scale - 1) < 0.02) {
        return { templates: templates, TW: baseTW, ANCHOR_DY: baseAnchor };
    }
    const TW = Math.round(baseTW * scale);
    const out = [];
    try {
        for (let i = 0; i < templates.length; i++) {
            out.push({
                name: templates[i].name,
                image: host.resize(templates[i].image, [TW * 2, TW * 2])
            });
        }
    } catch (error) {
        releaseTemplateImages(out);
        throw error;
    }
    return { templates: out, TW: TW, ANCHOR_DY: -Math.round(Math.abs(baseAnchor) * scale) };
}

/**
 * 带缓存的模板准备：同一模板数组 + 同一缩放比只缩放一次（帧间复用）。
 * 缩放比按 1/1000 归并（板距在会话内基本不变）。缓存挂在 templates 数组上（`__glyphPrepared`）。
 * @return { templates, TW, ANCHOR_DY, scale, cached }
 */
function prepareFor(host, templates, scale, options) {
    const bucket = Math.round((scale || 1) * 1000) / 1000;
    const memo = templates.__glyphPrepared;
    if (memo && memo.scale === bucket) {
        return {
            templates: memo.templates, TW: memo.TW, ANCHOR_DY: memo.ANCHOR_DY,
            scale: scale, cached: true, coarse: memo.coarse
        };
    }
    const prepared = prepareTemplates(host, templates, scale, options);
    // 金字塔粗搜模板（半尺寸）：全尺寸模板 2*TW → 半尺寸 TW；side 用于回算全尺寸中心。
    const coarseSide = Math.max(8, Math.round(prepared.TW));
    const coarse = [];
    try {
        for (let i = 0; i < prepared.templates.length; i++) {
            coarse.push({
                name: prepared.templates[i].name,
                side: coarseSide,
                image: host.resize(prepared.templates[i].image, [coarseSide, coarseSide])
            });
        }
    } catch (error) {
        releaseTemplateImages(coarse);
        if (prepared.templates !== templates) releaseTemplateImages(prepared.templates);
        throw error;
    }
    releasePrepared(templates);
    templates.__glyphPrepared = {
        scale: bucket, templates: prepared.templates, TW: prepared.TW, ANCHOR_DY: prepared.ANCHOR_DY,
        coarse: coarse
    };
    return {
        templates: prepared.templates, TW: prepared.TW, ANCHOR_DY: prepared.ANCHOR_DY,
        scale: scale, cached: false, coarse: coarse
    };
}

/** Release a template and all rescue-scale copies owned by it. */
function releaseTemplateImages(templates) {
    (templates || []).forEach(function (tpl) {
        const scaled = tpl.__scaled;
        delete tpl.__scaled;
        if (scaled) Object.keys(scaled).forEach(function (key) { recycle(scaled[key]); });
        recycle(tpl.image);
    });
}

function releasePrepared(templates) {
    const memo = templates && templates.__glyphPrepared;
    if (!memo) return;
    delete templates.__glyphPrepared;
    releaseTemplateImages(memo.coarse);
    // scale≈1 borrows the originals; only the derived templates are owned by the cache.
    if (memo.templates !== templates) releaseTemplateImages(memo.templates);
}

function releaseTemplates(templates) {
    releasePrepared(templates);
    releaseTemplateImages(templates);
}

/** 从 assets 目录加载 11 个模板（灰度；跨分辨率由 prepareFor 缩放）。 */
function loadTemplates(host, dir) {
    const out = [];
    try {
        for (let i = 0; i < TPL_NAMES.length; i++) {
            const name = TPL_NAMES[i];
            const path = dir + "/tpl_" + name + ".png";
            const color = host.read(path);
            if (!color) throw new Error("模板读不到：" + path);
            try {
                const gray = host.grayscale(color);
                if (!gray) throw new Error("模板灰度转换失败：" + path);
                out.push({ name: name, image: gray });
            } finally {
                recycle(color);
            }
        }
        return out;
    } catch (error) {
        releaseTemplateImages(out);
        throw error;
    }
}

/** 字形层字符（'.' 空格 / '?' 未知）→ 工程盘面格式（null 空格 / '?' 未知）。 */
function normalizeBoard(chars) {
    const out = new Array(chars.length);
    for (let i = 0; i < chars.length; i++) {
        out[i] = chars[i] === "." ? null : chars[i];
    }
    return out;
}

/** 数未知格（'?' 个数）。 */
function countUnknown(chars) {
    let n = 0;
    for (let i = 0; i < chars.length; i++) {
        if (chars[i] === "?") {
            n += 1;
        }
    }
    return n;
}

/**
 * 棋子被点起/高亮时，字形会放大，但棋子不会在同一个交点变成另一兵种。
 * 仅当全盘占位完全没变且阵营相同时，保留上一盘兵种。异色不可覆盖：
 * 同兵种吃子在后续静态帧也会占位不变；当前红黑读数必须保留。真正移动/吃子至少会
 * 改变起点占位，仍交给走法与多帧校验。原始读数须另存，供诊断使用。
 */
function preserveIdentityOnSameOccupancy(board, referenceBoard) {
    const out = board.slice();
    if (!referenceBoard || referenceBoard.length !== out.length) {
        return { board: out, held: 0 };
    }
    for (let i = 0; i < out.length; i++) {
        if (!!out[i] !== !!referenceBoard[i]) {
            return { board: out, held: 0 };
        }
    }
    let held = 0;
    for (let i = 0; i < out.length; i++) {
        if (out[i] && out[i] !== "?" && referenceBoard[i] &&
            referenceBoard[i] !== "?" && out[i] !== referenceBoard[i] &&
            (out[i] === out[i].toUpperCase()) ===
                (referenceBoard[i] === referenceBoard[i].toUpperCase())) {
            out[i] = referenceBoard[i];
            held += 1;
        }
    }
    return { board: out, held: held };
}

function countOf(host, image, color, x, y, width, height) {
    const got = host.countColor(image, color, {
        region: { x: x, y: y, width: width, height: height }, threshold: 1
    });
    return got == null ? 0 : got;
}

function recycle(image) {
    try {
        if (image && typeof image.recycle === "function") {
            image.recycle();
        }
    } catch (ignore) { /* 忽略释放异常 */ }
}

/**
 * py `match_at` 的等价物：若窗口能完整落在帧内，先 clip 再匹配（同数学；原生走连续内存，实测快 ~1.6×）。
 * ⚠️ 引擎 `images.matchTemplate` 的 `point` 是**窗口内坐标的模板左上角**（有 region 时原生会加上窗口原点）
 *    → 中心 = point + TW（+ clip 窗原点）。
 * @param size 帧尺寸（{width,height}，可选；不传则一律走 region 模式）
 * @return { name, sim, cx, cy } | null（全部低于 CONF）
 */
function bestMatch(host, grayFrame, x, cy, templates, options, size) {
    const opts = options || {};
    const TW = pick(opts, "TW");
    const sx = pick(opts, "SEARCH_X");
    const sy = pick(opts, "SEARCH_Y");
    const conf = pick(opts, "CONF");
    const rx = x - TW - sx;
    const ry = cy - TW - sy;
    const rw = (TW + sx) * 2;
    const rh = (TW + sy) * 2;
    const canClip = !!(size && rx >= 0 && ry >= 0 &&
        rx + rw <= size.width && ry + rh <= size.height);
    const clipped = canClip ? host.clip(grayFrame, rx, ry, rw, rh) : null;
    let best = null;
    try {
        for (let i = 0; i < templates.length; i++) {
            const res = clipped
                ? host.matchTemplate(clipped, templates[i].image, { threshold: conf, max: 1 })
                : host.matchTemplate(grayFrame, templates[i].image, {
                    region: [rx, ry, rw, rh], threshold: conf, max: 1
                });
            const m = (res && res.matches && res.matches.length) ? res.matches[0] : null;
            if (m && (!best || m.similarity > best.sim)) {
                best = {
                    name: templates[i].name, sim: m.similarity,
                    cx: m.point.x + TW + (clipped ? rx : 0),
                    cy: m.point.y + TW + (clipped ? ry : 0)
                };
            }
        }
    } finally {
        recycle(clipped);
    }
    return best;
}

/** v2 匹配：窄窗（窗心 = 字形中心 + 行自适应修正）+ 不放心时宽窗复核（复核计入 stats.retries）。 */
function matchNarrowWide(host, grayFrame, x, cy, rowDy, templates, tightOpts, opts, size, stats) {
    let m = bestMatch(host, grayFrame, x, cy + rowDy, templates, tightOpts, size);
    if (!m || m.sim < pick(opts, "SAFE_CONF")) {
        if (stats) {
            stats.retries += 1;
        }
        const wide = bestMatch(host, grayFrame, x, cy, templates, opts, size);
        if (wide && (!m || wide.sim > m.sim)) {
            m = wide;
        }
    }
    return m;
}

/**
 * 与 `bestMatch` 同窗口语义，但**不设门限**：每个字形都拿到自己在这个窗口里的最高分，
 * 于是能算出「头名 vs 次名」的分差 —— 认字最大的风险不是没分，而是**两个字形咬得很近**
 * （真机踩过：被点起的棋子让頭名与次名只差 0.02，直接取頭名就认错子，拿去走棋就是走错）。
 * @return { name, sim, cx, cy, secondName, secondSim, gap } | null
 */
function bestMatchRanked(host, grayFrame, x, cy, templates, options, size) {
    const opts = options || {};
    const TW = pick(opts, "TW");
    const sx = pick(opts, "SEARCH_X");
    const sy = pick(opts, "SEARCH_Y");
    const rx = x - TW - sx;
    const ry = cy - TW - sy;
    const rw = (TW + sx) * 2;
    const rh = (TW + sy) * 2;
    const canClip = !!(size && rx >= 0 && ry >= 0 && rx + rw <= size.width && ry + rh <= size.height);
    const clipped = canClip ? host.clip(grayFrame, rx, ry, rw, rh) : null;
    const scored = [];
    try {
        for (let i = 0; i < templates.length; i++) {
            const res = clipped
                ? host.matchTemplate(clipped, templates[i].image, { threshold: 0, max: 1 })
                : host.matchTemplate(grayFrame, templates[i].image, {
                    region: [rx, ry, rw, rh], threshold: 0, max: 1
                });
            const hit = (res && res.matches && res.matches.length) ? res.matches[0] : null;
            if (!hit) {
                continue;
            }
            scored.push({
                name: templates[i].name, sim: hit.similarity,
                cx: hit.point.x + TW + (clipped ? rx : 0),
                cy: hit.point.y + TW + (clipped ? ry : 0)
            });
        }
    } finally {
        recycle(clipped);
    }
    if (!scored.length) {
        return null;
    }
    scored.sort(function (a, b) { return b.sim - a.sim; });
    const top = scored[0];
    return {
        name: top.name, sim: top.sim, cx: top.cx, cy: top.cy,
        secondName: scored.length > 1 ? scored[1].name : "",
        secondSim: scored.length > 1 ? scored[1].sim : -1,
        gap: scored.length > 1 ? top.sim - scored[1].sim : 1
    };
}

/** 模板按「边长 side」缩放的副本（缓存在模板对象上；同一帧里多个格子共用同一档缩放）。 */
function scaledTemplate(host, tpl, side) {
    if (tpl.image && tpl.image.width === side && tpl.image.height === side) {
        return tpl.image;   // 已经是这个尺寸（prepareTemplates 可能已经缩过）
    }
    const cache = tpl.__scaled || (tpl.__scaled = {});
    if (!cache[side]) {
        cache[side] = host.resize(tpl.image, [side, side]);
    }
    return cache[side];
}

/**
 * ★ 多尺度抢救（2026-09-25，K40 真机「某几格一直读不出」）：
 *
 * 触发面 = 读数「不放心」的格子：没匹配上（`!m`）、分不高（< SAFE_CONF）、
 * 或者读成了**不可能的字形**（士/仕 跑到九宫外）。这些格子旧行为只有一条兜底路 ——
 * 「选中放大」档（1.10/1.18），而且只在 `!m` 或士/仕 非法时才走。
 *
 * 本例（评测集 skin-c r9c7，馬）：动画中的棋子**放大 10% 且上移 12px** ——
 * 原尺寸模板最好只有 0.54（低于门限 → 未知）；同一格换成 1.10 倍模板立刻 0.88。
 * 所以这里把「缩放档」做成**一组**，并逐档看「头名−次名」的分差，只收「分够 + 拉得开」的结果：
 * 宁可留一个未知格（状态机不出手，看得见），也不要认错子（走错棋，看不出来）。
 *
 * @return { accepted, best: {name,sim,gap,scale,secondName,secondSim,cx,cy} | null }
 */
function rescueMatch(host, grayFrame, x, cy, col, row, templates, opts, size) {
    const scales = pick(opts, "RESCUE_SCALES");
    const baseTW = pick(opts, "TW");
    const conf = pick(opts, "RESCUE_CONF");
    const minGap = pick(opts, "RESCUE_MARGIN");
    const sx = pick(opts, "RESCUE_SEARCH_X");
    const sy = pick(opts, "RESCUE_SEARCH_Y");
    let best = null;
    for (let s = 0; s < scales.length; s++) {
        const half = Math.max(16, Math.round(baseTW * scales[s]));
        const list = [];
        for (let i = 0; i < templates.length; i++) {
            if (!advisorAllowed(templates[i].name, col, row)) {
                continue;   // 士/仕 只能站九宫：盘边误读不算第三枚仕
            }
            list.push({ name: templates[i].name, image: scaledTemplate(host, templates[i], half * 2) });
        }
        if (!list.length) {
            continue;
        }
        const ranked = bestMatchRanked(host, grayFrame, x, cy, list, { TW: half, SEARCH_X: sx, SEARCH_Y: sy }, size);
        if (!ranked) {
            continue;
        }
        ranked.scale = scales[s];
        if (!best || ranked.sim > best.sim) {
            best = ranked;
        }
        if (best.sim >= conf && best.gap >= minGap) {
            break;   // 这一档已经够确定：不再往下试（一档约省 3/4 的抢救开销）
        }
    }
    return { accepted: !!(best && best.sim >= conf && best.gap >= minGap), best: best };
}

/**
 * v3 金字塔：半尺寸粗搜（11 模板各一次）→ 提名 top-K 在**全尺寸**上小窗精修。
 * 粗搜窗口位置 = 预测中心的一半；候选中心回算：全尺寸中心 = 粗点×2 + 粗模板边长。
 * @return 最佳精修结果 {name,sim,cx,cy} | null（粗层无提名）
 */
function matchPyramid(host, grayFrame, halfFrame, x, cy, templates, coarse, opts, size) {
    const TW = pick(opts, "TW");
    const ew = Math.max(2, Math.round((TW + pick(opts, "SEARCH_X")) / 2));
    const eh = Math.max(2, Math.round((TW + pick(opts, "SEARCH_Y_TIGHT")) / 2));
    const cx2 = Math.round(x / 2);
    const cy2 = Math.round(cy / 2);
    const region = [cx2 - ew, cy2 - eh, ew * 2, eh * 2];
    const coarseConf = pick(opts, "COARSE_CONF");
    const cands = [];
    for (let i = 0; i < coarse.length; i++) {
        const res = host.matchTemplate(halfFrame, coarse[i].image, {
            region: region, threshold: coarseConf, max: 1
        });
        const hit = (res && res.matches && res.matches.length) ? res.matches[0] : null;
        if (hit) {
            cands.push({
                index: i, sim: hit.similarity,
                cx: hit.point.x * 2 + coarse[i].side,
                cy: hit.point.y * 2 + coarse[i].side
            });
        }
    }
    if (!cands.length) {
        return null;
    }
    cands.sort(function (a, b) { return b.sim - a.sim; });
    const top = Math.min(cands.length, pick(opts, "PYRAMID_TOP"));
    const r = pick(opts, "PYRAMID_R");
    const safe = pick(opts, "SAFE_CONF");
    let best = null;
    for (let k = 0; k < top; k++) {
        const ref = bestMatch(host, grayFrame, cands[k].cx, cands[k].cy, [templates[cands[k].index]], {
            TW: TW, SEARCH_X: r, SEARCH_Y: r, CONF: 0
        }, size);
        if (ref && (!best || ref.sim > best.sim)) {
            best = ref;
        }
        // 提前收敛：精修分已经「可信」就不再精修剩下的提名（正常格只精修 1 个）
        if (best && best.sim >= safe) {
            break;
        }
    }
    return best;
}

/** 士/仕只能站在本方九宫的五个斜线点，盘边误读不能算成第三枚仕。 */
function advisorAllowed(name, col, row) {
    if (name !== "仕" && name !== "士") {
        return true;
    }
    const homeRow = name === "仕" ? 9 : 0;
    return (row === homeRow && (col === 3 || col === 5)) ||
        (row === homeRow + (name === "仕" ? -1 : 1) && col === 4) ||
        (row === homeRow + (name === "仕" ? -2 : 2) && (col === 3 || col === 5));
}

/**
 * 窗内清晰度（采样网格上的离散拉普拉斯方差）：值越小越糊。
 * py 参考用逐像素 cv2.Laplacian；这里按 MOVING_STEP 采样（帧内基准比较时口径自洽）。
 * @return number | null（越界/取不到像素）
 */
function windowSharpness(host, grayFrame, cx, cy, radius, step) {
    const n = Math.floor((radius * 2) / step) + 1;
    if (n < 3) {
        return null;
    }
    const vals = [];
    for (let j = 0; j < n; j++) {
        for (let i = 0; i < n; i++) {
            let pixel = null;
            try {
                pixel = host.pixel(grayFrame, cx - radius + i * step, cy - radius + j * step);
            } catch (ignore) {
                return null;
            }
            vals.push(host.red(pixel));
        }
    }
    let sum = 0;
    let sumSq = 0;
    let count = 0;
    for (let j = 1; j < n - 1; j++) {
        for (let i = 1; i < n - 1; i++) {
            const l = 4 * vals[j * n + i] - vals[j * n + i - 1] - vals[j * n + i + 1] -
                vals[(j - 1) * n + i] - vals[(j + 1) * n + i];
            sum += l;
            sumSq += l * l;
            count += 1;
        }
    }
    if (!count) {
        return null;
    }
    const mean = sum / count;
    return sumSq / count - mean * mean;
}

/** py `side_of_center`：匹配窗内暗像素（gray<150）平均 R-B > 45 判红（步进采样）。 */
function sideOf(host, colorFrame, cx, cy, options) {
    const opts = options || {};
    const TW = pick(opts, "TW");
    const step = pick(opts, "SIDE_STEP");
    let n = 0;
    let sumR = 0;
    let sumB = 0;
    for (let yy = cy - TW + 2; yy <= cy + TW - 2; yy += step) {
        for (let xx = cx - TW + 2; xx <= cx + TW - 2; xx += step) {
            let pixel = null;
            try {
                pixel = host.pixel(colorFrame, xx, yy);
            } catch (ignore) {
                continue;
            }
            const r = host.red(pixel);
            const g = host.green(pixel);
            const b = host.blue(pixel);
            const gray = (r * 299 + g * 587 + b * 114) / 1000;
            if (gray < 150) {
                n += 1;
                sumR += r;
                sumB += b;
            }
        }
    }
    if (n < pick(opts, "SIDE_MIN_DARK")) {
        return "?";
    }
    return (sumR / n - sumB / n) > pick(opts, "SIDE_RB") ? "r" : "b";
}

/**
 * 一帧识别：90 格逐格跑三道门 + 模板匹配 + 阵营。
 *
 * v2 匹配（见文件头 ①）：先「行自适应窄窗」——行内每匹配到一格，就把实测 dy 并入**实时中位数**
 * 当窗心修正（漂移是「按行」的量，实测行间跳 ≤6px）；窄窗没命中或得分 < SAFE_CONF 时用宽窗
 * 复核（宽窗窗心 = 未修正的字形中心，语义与 v1 相同；复核分更高就采用）。
 *
 * @param host { sizeOf, grayscale, clip, threshold, countColor, matchTemplate, pixel, red, green, blue, nowMono }
 * @param image 彩色帧
 * @param grid  lattice 网格 { originX, originY, stepX, stepY }
 * @param options { templates 必填; TW/ANCHOR_DY/SEARCH_X/SEARCH_Y/SEARCH_Y_TIGHT/SAFE_CONF/CONF/... 可覆盖 }
 * @return { chars: 90, inked, unknown, ms, retries, profile{gates,match,side}, error? }
 */
function recognize(host, image, grid, options) {
    const opts = options || {};
    const templates = opts.templates;
    if (!templates || !templates.length) {
        return { chars: null, inked: 0, unknown: 0, ms: 0, retries: 0, profile: null, error: "没有模板（templates 必填）" };
    }
    const TW = pick(opts, "TW");
    const ANCHOR_DY = pick(opts, "ANCHOR_DY");
    const SY_TIGHT = pick(opts, "SEARCH_Y_TIGHT");
    const SAFE_CONF = pick(opts, "SAFE_CONF");
    const DARK_THR = pick(opts, "DARK_THR");
    const INNER_THR = pick(opts, "INNER_THR");
    const BRIGHT_RATIO = pick(opts, "BRIGHT_RATIO");
    const started = host.nowMono();
    const size = host.sizeOf(image);
    const grayFrame = host.grayscale(image);
    // 金字塔（v3）：粗搜用半尺寸帧（整帧只缩一次；粗模板在 prepareFor 里备好）
    const coarseTemplates = opts.pyramid === false ? null : (opts.coarseTemplates || null);
    let halfFrame = null;
    try {
        halfFrame = coarseTemplates
            ? host.resize(grayFrame, [Math.round(size.width / 2), Math.round(size.height / 2)])
            : null;
        const chars = new Array(90);
        const winW = TW * 2;
        // 内窗：以 (x, cy) 为心的 40×40；在字窗内坐标 = [TW-20, TW+ANCHOR_DY-20]（K40 时为 [14,9]）
        const innerX = TW - 20;
        const innerY = TW + ANCHOR_DY - 20;
        /** 窄窗参数（只改纵向半宽，其余与原语义一致） */
        const tightOpts = Object.assign({}, opts, { SEARCH_Y: SY_TIGHT });
        let inked = 0;
        let unknown = 0;
        /** ★ 2026-10-06「同帧沿用」：指纹没变的格直接采用上一帧结论的格数（见 opts.adopt） */
        let adoptedCount = 0;
        let error = "";
        /** 复核/抢救计数（宽窗重试 + 金字塔回退 + 多尺度抢救；一个对象为了能传进 matchNarrowWide） */
        const matchStats = { retries: 0, rescues: 0, rescued: 0, rescueMs: 0, notes: [] };
        /** opts.collect = true 时逐格收集「认到的字形 + 匹配分」（盘面级判决挑最弱格降级用） */
        const collect = opts.collect === true;
        const collected = collect ? [] : null;
        /** 空中子模糊门：认不准的格（候选）与认准的格（清晰度基准）——收尾统一判定 */
        const blurCandidates = [];
        const sharpRefs = [];
        let gateMs = 0;
        let matchMs = 0;
        let sideMs = 0;
        /** 行自适应窗心修正（上一行/本行实测 dy 的中位数；初值 0 = 直接用网格） */
        let rowDy = 0;
        for (let r = 0; r < 10 && !error; r++) {
            const dySamples = [];
            for (let c = 0; c < 9 && !error; c++) {
                const idx = r * 9 + c;
                chars[idx] = ".";
                const x = Math.round(grid.originX + c * grid.stepX);
                const y = Math.round(grid.originY + r * grid.stepY);
                const cy = y + ANCHOR_DY;
                if (x - TW < 0 || y - TW < 0 || x + TW >= size.width || y + TW >= size.height) {
                    continue;
                }
                // ★ 「本格沿用」（2026-10-06，装配见 pipeline-factory + src/vision/reuse-gate.js）：
                //   找色指纹证明这格与上一帧是同一画面时，直接用上一帧结论，跳过三道门 + 模板匹配
                //   （匹配是逐格的大头，K40 实测复核段 ~300-400ms/32 子）。载荷只含「空格 或 sim≥0.85
                //   的认准格」——低分/未知格绝不会出现在这里（reuse-gate.buildAdopt 卡的口子）。
                const kept = opts.adopt ? opts.adopt[idx] : null;
                if (kept && kept.char && kept.char !== "?") {
                    chars[idx] = kept.char;
                    adoptedCount += 1;
                    if (kept.char !== ".") {
                        inked += 1;
                        if (kept.dy != null) {
                            dySamples.push(kept.dy);
                            dySamples.sort(function (a, b) { return a - b; });
                            rowDy = dySamples[Math.floor(dySamples.length / 2)];
                        }
                        if (kept.sim != null && kept.sim >= pick(opts, "CONF") && kept.cx != null &&
                            sharpRefs.length < pick(opts, "MOVING_REF_CELLS")) {
                            sharpRefs.push([kept.cx, kept.cy]);
                        }
                        if (collected && kept.name) {
                            collected.push({ idx: idx, name: kept.name, sim: kept.sim, char: kept.char,
                                dy: kept.dy, cx: kept.cx, cy: kept.cy, adopted: true });
                        }
                    }
                    continue;
                }
                const gateStarted = host.nowMono();
                // ① 暗占比门（有子？）
                let win = null, bin = null, faceBin = null;
                let dark, inner, bright;
                try {
                    win = host.clip(grayFrame, x - TW, y - TW, winW, winW);
                    bin = host.threshold(win, 140, 255);
                    dark = countOf(host, bin, "#000000", 0, 0, winW, winW);
                    if (dark / (winW * winW) <= DARK_THR) {
                        gateMs += host.nowMono() - gateStarted;
                        continue;
                    }
                    inner = countOf(host, bin, "#000000", innerX, innerY, 40, 40);
                    faceBin = host.threshold(win, 200, 255);
                    bright = winW * winW - countOf(host, faceBin, "#000000", 0, 0, winW, winW);
                } finally {
                    recycle(faceBin);
                    recycle(bin);
                    recycle(win);
                }
                gateMs += host.nowMono() - gateStarted;
                if (inner / 1600 <= INNER_THR) {
                    continue;
                }
                if (bright / (winW * winW) < BRIGHT_RATIO) {
                    continue;
                }
                inked += 1;
                // ④ 模板匹配：金字塔（若给了粗模板）→ 不放心再「窄窗+宽窗」全搜（行自适应窗心）
                const matchStarted = host.nowMono();
                let m = null;
                try {
                    if (halfFrame) {
                        m = matchPyramid(host, grayFrame, halfFrame, x, cy + rowDy, templates, coarseTemplates, opts, size);
                    }
                    if (!m || m.sim < SAFE_CONF) {
                        if (halfFrame) {
                            matchStats.retries += 1;   // 金字塔不放心 ⇒ 回退全搜
                        }
                        const fine = matchNarrowWide(host, grayFrame, x, cy, rowDy, templates, tightOpts, opts, size, matchStats);
                        if (fine && (!m || fine.sim > m.sim)) {
                            m = fine;
                        }
                    }
                } catch (matchError) {
                    error = "matchTemplate 异常：" + matchError;
                    break;
                }
                // (2,9) 被选中的红相放大约 10% 后，普通模板曾把它误判成九宫外的第三枚红仕。
                // ★ 2026-09-25：复核从「只在没匹配上时试 1.10/1.18 两档」升级成 rescueMatch（多档缩放 +
                //   头名/次名分差门），触发面也扩到「分不高 / 读成非法字形」—— 真机「某几格一直读不出」
                //   就是动画里放大的字形被原尺寸模板挡在门限下（分 0.54），而旧复核的条件刚好漏掉它。
                const knownChar = opts.referenceBoard && opts.referenceBoard[idx];
                const knownGlyph = GLYPH_FOR_CHAR[knownChar];
                const before = m ? { name: m.name, sim: m.sim } : null;
                const needRescue = !m || m.sim < SAFE_CONF || !advisorAllowed(m.name, c, r);
                const rescueBudgetOk = matchStats.rescues < pick(opts, "RESCUE_MAX_CELLS") &&
                    matchStats.rescueMs < pick(opts, "RESCUE_BUDGET_MS");
                if (needRescue && rescueBudgetOk) {
                    matchStats.rescues += 1;
                    const rescueStarted = host.nowMono();
                    let fixed = null;
                    try {
                        fixed = rescueMatch(host, grayFrame, x, cy, c, r, templates, opts, size);
                    } catch (rescueError) {
                        error = "多尺度抢救异常：" + rescueError;
                        break;
                    }
                    matchStats.rescueMs += host.nowMono() - rescueStarted;
                    if (fixed && fixed.accepted && fixed.best) {
                        // 旧逻辑的保护（吃子落点可能原本是另一种棋）：这个交点的可信旧字形已知时，
                        // 抢救结果必须明显胜过当前读数才允许覆盖
                        const beatsOld = !before || !knownGlyph || fixed.best.name === knownGlyph ||
                            fixed.best.sim >= before.sim + 0.05;
                        if (beatsOld) {
                            m = { name: fixed.best.name, sim: fixed.best.sim, cx: fixed.best.cx, cy: fixed.best.cy };
                            matchStats.rescued += 1;
                            matchStats.notes.push({
                                kind: "rescued", idx: idx, col: c, row: r, name: fixed.best.name,
                                sim: fixed.best.sim, gap: fixed.best.gap, scale: fixed.best.scale,
                                was: before ? before.name : "", wasSim: before ? before.sim : null
                            });
                        }
                    } else if (!m) {
                        // 抢救也没把握 ⇒ 留未知；把「读到了什么、差多少」记下来（面板/日志打得出来）
                        matchStats.notes.push({
                            kind: "unknown", gate: "match", idx: idx, col: c, row: r,
                            name: fixed && fixed.best ? fixed.best.name : "",
                            sim: fixed && fixed.best ? fixed.best.sim : null,
                            second: fixed && fixed.best ? fixed.best.secondName : "",
                            secondSim: fixed && fixed.best ? fixed.best.secondSim : null
                        });
                    }
                } else if (m && knownGlyph && m.name !== knownGlyph && rescueBudgetOk) {
                    // 同一交点的可信旧棋子被点起后可能放大。只对这一格、这一种旧字形
                    // 增加尺寸候选（不把 90 格全部扩大扫描）。
                    const expected = templates.filter(function (tpl) { return tpl.name === knownGlyph; });
                    if (expected.length) {
                        matchStats.rescues += 1;
                        try {
                            const selected = rescueMatch(host, grayFrame, x, cy, c, r, expected, opts, size);
                            // 吃子落点可能原本是另一种棋：旧字形必须明显胜过当前读数才可覆盖。
                            if (selected && selected.accepted && selected.best &&
                                selected.best.sim >= m.sim + 0.03) {
                                m = {
                                    name: selected.best.name, sim: selected.best.sim,
                                    cx: selected.best.cx, cy: selected.best.cy
                                };
                                matchStats.notes.push({
                                    kind: "held", idx: idx, col: c, row: r, name: selected.best.name,
                                    sim: selected.best.sim, gap: selected.best.gap, scale: selected.best.scale,
                                    was: before ? before.name : "", wasSim: before ? before.sim : null
                                });
                            }
                        } catch (selectedError) {
                            error = "旧字形定向复核异常：" + selectedError;
                            break;
                        }
                    }
                }
                matchMs += host.nowMono() - matchStarted;
                if (!m) {
                    chars[idx] = "?";
                    unknown += 1;
                    blurCandidates.push({ idx: idx, col: c, row: r, cx: x, cy: cy, unknown: true });
                    continue;
                }
                if (m.sim >= pick(opts, "CONF") && sharpRefs.length < pick(opts, "MOVING_REF_CELLS")) {
                    sharpRefs.push([m.cx, m.cy]);
                }
                // 行自适应：实测 dy 并入本行样本（实时中位数，抗单个错配）
                dySamples.push(m.cy - cy);
                dySamples.sort(function (a, b) { return a - b; });
                rowDy = dySamples[Math.floor(dySamples.length / 2)];
                // ⑤ 阵营
                const sideStarted = host.nowMono();
                if (SIDED[m.name]) {
                    let side = sideOf(host, image, m.cx, m.cy, opts);
                    if (side === "?") {
                        // 阵营抢救：笔画颜色样本太少（半透明遮罩/动画）时，把采样步调密、下限降下来重判；
                        // R-B 门限不变（红墨的 R-B 很硬），所以不会把黑子判成红。
                        side = sideOf(host, image, m.cx, m.cy, Object.assign({}, opts, {
                            SIDE_STEP: 2, SIDE_MIN_DARK: 12
                        }));
                    }
                    if (side === "?") {
                        chars[idx] = "?";
                        unknown += 1;
                        matchStats.notes.push({
                            kind: "unknown", gate: "side", idx: idx, col: c, row: r,
                            name: m.name, sim: m.sim
                        });
                        blurCandidates.push({ idx: idx, col: c, row: r, cx: m.cx, cy: m.cy, unknown: true });
                        sideMs += host.nowMono() - sideStarted;
                        continue;
                    }
                    chars[idx] = SIDED[m.name][side === "r" ? 0 : 1];
                } else {
                    chars[idx] = FIXED[m.name];
                }
                if (m.sim < SAFE_CONF) {
                    // 勉强对上（0.70~SAFE_CONF）也可能是「空中子」：JS 的匹配比 py 多几种窗，
                    // 偶尔能给模糊字形凑出一个过门限的分 —— 收尾统一做清晰度判定。
                    blurCandidates.push({ idx: idx, col: c, row: r, cx: m.cx, cy: m.cy, unknown: false });
                }
                if (collected) {
                    // char/dy/cx/cy 是给「同帧沿用」（reuse-gate.buildAdopt）留的：下一帧指纹没变时
                    // 这格直接照抄（含着法行自适应要用的 dy 与清晰度基准要用的中心）。
                    collected.push({ idx: idx, name: m.name, sim: m.sim, char: chars[idx],
                        dy: m.cy - cy, cx: m.cx, cy: m.cy });
                }
                sideMs += host.nowMono() - sideStarted;
            }
        }
        // ★ 「空中子」模糊门（2026-10-04）：认不准的格若整窗明显比「认准的格」糊，
        //   判「子在空中/没落稳」（落子动画中间帧）按空处理：不记未知、不数它。
        let movingDropped = 0;
        if (blurCandidates.length && sharpRefs.length) {
            const movingStep = pick(opts, "MOVING_STEP");
            const refVals = [];
            for (let i = 0; i < sharpRefs.length; i++) {
                const value = windowSharpness(host, grayFrame, sharpRefs[i][0], sharpRefs[i][1], TW, movingStep);
                if (value != null) {
                    refVals.push(value);
                }
            }
            if (refVals.length) {
                refVals.sort(function (a, b) { return a - b; });
                const base = refVals[Math.floor(refVals.length / 2)];
                const gate = base * pick(opts, "MOVING_RATIO");
                for (let i = 0; i < blurCandidates.length; i++) {
                    const cand = blurCandidates[i];
                    const value = windowSharpness(host, grayFrame, cand.cx, cand.cy, TW, movingStep);
                    if (value != null && value < gate) {
                        chars[cand.idx] = ".";
                        if (cand.unknown) {
                            unknown -= 1;
                        }
                        movingDropped += 1;
                        matchStats.notes.push({
                            kind: "moving", idx: cand.idx, col: cand.col, row: cand.row,
                            sharp: Math.round(value), base: Math.round(base)
                        });
                    }
                }
            }
        }
        return {
            chars: chars, inked: inked, adopted: adoptedCount, unknown: unknown, retries: matchStats.retries,
            rescued: matchStats.rescued, rescues: matchStats.rescues, moving: movingDropped,
            notes: matchStats.notes,
            rescueMs: matchStats.rescueMs, cells: collected,
            ms: host.nowMono() - started,
            profile: { gates: gateMs, match: matchMs, side: sideMs },
            error: error
        };
    } finally {
        recycle(halfFrame);
        recycle(grayFrame);
    }
}

/** 90 格字符 → FEN 盘面段（与 probes 一致）。 */
function charsToFen(chars) {
    const rows = [];
    for (let r = 0; r < 10; r++) {
        let text = "";
        let empty = 0;
        for (let c = 0; c < 9; c++) {
            const ch = chars[r * 9 + c];
            if (ch && ch !== ".") {
                if (empty > 0) {
                    text += empty;
                    empty = 0;
                }
                text += ch;
            } else {
                empty += 1;
            }
        }
        if (empty > 0) {
            text += empty;
        }
        rows.push(text || "9");
    }
    return rows.join("/");
}

module.exports = {
    DEFAULTS: DEFAULTS,
    TPL_NAMES: TPL_NAMES,
    SIDED: SIDED,
    FIXED: FIXED,
    scaleFor: scaleFor,
    prepareTemplates: prepareTemplates,
    prepareFor: prepareFor,
    loadTemplates: loadTemplates,
    releaseTemplates: releaseTemplates,
    normalizeBoard: normalizeBoard,
    countUnknown: countUnknown,
    preserveIdentityOnSameOccupancy: preserveIdentityOnSameOccupancy,
    bestMatch: bestMatch,
    bestMatchRanked: bestMatchRanked,
    rescueMatch: rescueMatch,
    sideOf: sideOf,
    windowSharpness: windowSharpness,
    recognize: recognize,
    charsToFen: charsToFen
};
});

// ═══════════ 内联模块：src/vision/glyph-resolve.js（逐字拷贝自工程） ═══════════
__define("src/vision/glyph-resolve.js", function (module, exports, require) {
// @engine quickjs
/**
 * 盘面级判决（「全局拟合 + 守恒」）—— 2026-09-25，把**棋盘定位**那套思路搬到识别判决上。
 *
 * 定位为什么准：它不逐条线独立判生死，而是把 95 条线的证据放进**一个全局拟合**里
 * （origin/step 全搜 + trimmed 求和，允许最弱 1~2 条线缺席）。字形识别恰恰相反 ——
 * 逐格独立判决，于是：
 *   · 一格「字形认得、只有红黑分不出」→ 只能留 `?`；状态机要求「未知格 0」⇒ **整盘卡住**；
 *   · 一格读数「违反棋规/数量上限」→ 也照单全收（字形链此前**没有** consistency.repair，
 *     旧检测链有 —— 这是搬过来之前的一个真缺口）。
 *
 * 这里补两层**全局**判决（只用约束算，不碰像素）：
 *   ① 修复：违规格按棋规降级 / 改阵营（复用 `src/chess/consistency.js` 的成熟逻辑）
 *   ② 守恒补全：`?` 格在「子力上限 + 棋规」下**唯一解**时补上；多解一律保持 `?`
 *
 * 原则：**宁缺勿错** —— 只在硬约束把答案逼到唯一时才落子；
 * 「空」永远合法，所以补全只对「识别已经确认『这里有子』、只是分不清是哪一色」的格生效
 * （gate="side"，字形 車/馬/炮 的三个字）。其余 `?` 一律原样保留。
 *
 * 纯 JS，不碰平台接口（离线自检可直接测）。
 */
"use strict";

const fen = require("../chess/fen.js");
const consistency = require("../chess/consistency.js");

const STATE_PIECE = "PIECE";
const STATE_EMPTY = "EMPTY";
const STATE_UNKNOWN = "UNKNOWN";

/** 红黑同形、只差颜色的三个字 → [红, 黑]。其余字形自带阵营，不存在「分不出色」的歧义。 */
const SIDED_PAIR = { "車": ["R", "r"], "馬": ["N", "n"], "炮": ["C", "c"] };

const DEFAULTS = {
    /** 读数格的默认可信度（识别已过门限的格子；只有同分比较用得到） */
    nominalScore: 0.9,
    /** gate="side" 的格：字形已确认（这些格子本来就匹配到了模板并过了两道门） */
    fillSide: true,
    /**
     * gate="match" 的格（连字形都不敢定）默认**不补**：
     * 实测空盘/木纹的假最高分能到 0.32~0.39，被动画盖住的格 0.19~0.36 —— 都不足以证明"这里有子"，
     * 而"空"又永远合法 ⇒ 补全无从谈起。真要用需显式给这个下限（默认 null = 关）。
     */
    fillMatchFloor: null,
    /** 一帧最多补几格（保守：补全是有代价的推断，不搞批量） */
    maxFill: 3,
    /** 是否启用 ① 违规修复（降级/改阵营） */
    repair: true
};

/** 90 格字符 → consistency 认的 cells（纯函数）。 */
function cellsOf(chars, options) {
    const opts = options || {};
    const scores = opts.scores || {};
    const out = new Array(chars.length);
    for (let i = 0; i < chars.length; i++) {
        const ch = chars[i];
        if (ch === "?") {
            out[i] = { state: STATE_UNKNOWN, piece: null, score: null };
        } else if (!ch || ch === ".") {
            out[i] = { state: STATE_EMPTY, piece: null, score: null };
        } else {
            out[i] = {
                state: STATE_PIECE, piece: ch,
                score: scores[i] == null ? (opts.nominalScore == null ? DEFAULTS.nominalScore : opts.nominalScore) : scores[i]
            };
        }
    }
    return out;
}

/** 硬违规（九宫/过河/兵卒后退）条数 —— 与 consistency 口径一致。 */
function hardCount(validation) {
    const list = (validation && validation.violations) || [];
    let n = 0;
    for (let i = 0; i < list.length; i++) {
        const kind = list[i].kind;
        if (kind === "palace" || kind === "river" || kind === "pawn-back") {
            n += 1;
        }
    }
    return n;
}

/** 子力数量类错误（"红炮有 3 个，超过上限 2"）。 */
function countErrors(validation) {
    const list = (validation && validation.errors) || [];
    let n = 0;
    for (let i = 0; i < list.length; i++) {
        if (/超过上限|数量异常|超过标准上限/.test(String(list[i]))) {
            n += 1;
        }
    }
    return n;
}

function boardOfChars(chars) {
    const board = new Array(chars.length);
    for (let i = 0; i < chars.length; i++) {
        const ch = chars[i];
        board[i] = (!ch || ch === "." || ch === "?") ? null : ch;
    }
    return board;
}

function validateChars(chars) {
    const validation = fen.validateBoard(boardOfChars(chars));
    return {
        errors: (validation.errors || []).length,
        hard: hardCount(validation),
        count: countErrors(validation),
        validation: validation
    };
}

/**
 * 守恒补全：对一个 `?` 格试它的候选字形（红/黑两色），
 * 只有「恰好一个候选不让盘面变坏（错误数不增加、硬违规为 0）」时才采用。
 * @return { char, why } | null
 */
function resolveCell(chars, index, candidates, base) {
    const kept = [];
    for (let i = 0; i < candidates.length; i++) {
        const ch = candidates[i];
        const probe = chars.slice();
        probe[index] = ch;
        const after = validateChars(probe);
        if (after.hard > 0) {
            continue;                      // 违反硬棋规（九宫/过河/兵种位置）
        }
        if (after.errors > base.errors) {
            continue;                      // 引入了新的错误（最常见：子力超上限）
        }
        kept.push({ char: ch, count: after.count });
    }
    if (kept.length === 1) {
        return { char: kept[0].char, why: "唯一解" };
    }
    if (kept.length === 0) {
        return { char: null, why: "两个候选都违规" };
    }
    // 多解：若其中一个能**减少**数量错误而另一个不会，优先它（这是"翻色修正"的方向）
    const better = kept.filter(function (item) { return item.count < base.count; });
    if (better.length === 1) {
        return { char: better[0].char, why: "唯一能消掉数量错误" };
    }
    return { char: null, why: "多解（" + kept.length + " 个候选都合法）" };
}

/**
 * @param chars 90 格（字形识别的产物；`.` 空 / `?` 未知 / 字母棋子）
 * @param options {
 *   scores?,  // { 格号: 匹配分 }（来自 glyph-recognizer 的 collect，只有违规降级挑"最弱格"用得到）
 *   notes?,   // glyph-recognizer 的 notes（`?` 的原因与候选字形）
 *   repair?, fillSide?, fillMatchFloor?, maxFill?
 * }
 * @return { chars, filled[], demoted[], flipped[], stats }
 */
function resolve(chars, options) {
    const opts = Object.assign({}, DEFAULTS, options || {});
    const work = chars.slice();
    const filled = [];
    const demoted = [];
    const flipped = [];

    // ① 违规修复（降级 / 改阵营）—— 复用旧检测链那套成熟逻辑
    if (opts.repair !== false && consistency && typeof consistency.repair === "function") {
        const cells = cellsOf(work, opts);
        const repaired = consistency.repair(cells, {});
        demoted.push.apply(demoted, repaired.demoted || []);
        flipped.push.apply(flipped, repaired.flipped || []);
        if ((repaired.demoted && repaired.demoted.length) || (repaired.flipped && repaired.flipped.length)) {
            const after = consistency.charsOf(cells);
            for (let i = 0; i < work.length; i++) {
                work[i] = after[i];
            }
        }
    }

    // ② 守恒补全（只对「有子但分不出色」的格）
    const notes = opts.notes || [];
    const base = validateChars(work);
    for (let n = 0; n < notes.length && filled.length < opts.maxFill; n++) {
        const note = notes[n];
        if (!note || note.kind !== "unknown") {
            continue;
        }
        const index = note.idx;
        if (work[index] !== "?") {
            continue;
        }
        let candidates = null;
        if (note.gate === "side" && opts.fillSide !== false) {
            candidates = SIDED_PAIR[note.name] || null;
        } else if (note.gate === "match" && opts.fillMatchFloor != null && note.sim != null &&
            note.sim >= opts.fillMatchFloor) {
            candidates = SIDED_PAIR[note.name] || null;
        }
        if (!candidates) {
            continue;
        }
        const got = resolveCell(work, index, candidates, base);
        if (got && got.char) {
            work[index] = got.char;
            filled.push({
                index: index, col: index % 9, row: Math.floor(index / 9),
                char: got.char, from: note.name, was: candidates.join("/"),
                sim: note.sim == null ? null : note.sim, why: got.why
            });
        }
    }

    return {
        chars: work,
        filled: filled,
        demoted: demoted,
        flipped: flipped,
        stats: {
            filled: filled.length,
            demoted: demoted.length,
            flipped: flipped.length,
            unknownBefore: countUnknown(chars),
            unknownAfter: countUnknown(work)
        }
    };
}

function countUnknown(chars) {
    let n = 0;
    for (let i = 0; i < chars.length; i++) {
        if (chars[i] === "?") {
            n += 1;
        }
    }
    return n;
}

module.exports = {
    DEFAULTS: DEFAULTS,
    SIDED_PAIR: SIDED_PAIR,
    cellsOf: cellsOf,
    resolveCell: resolveCell,
    resolve: resolve,
    countUnknown: countUnknown
};
});

// ═══════════ 内联模块：src/vision/overlay-draw.js（逐字拷贝自工程） ═══════════
__define("src/vision/overlay-draw.js", function (module, exports, require) {
// @engine quickjs
/**
 * 识别绘制：把「棋盘几何 + 识别结果 + 本手计划」编成画板能画的 **框 + 标签** 列表。
 *
 * 为什么要它：驱动出问题时日志只能告诉我「第 N 行第 M 列是 ?」，看不出到底是哪一类错：
 *   · 网格没对齐（所有框整体偏了）      → 看网格线与棋子是否同心
 *   · 某个子漏检 / 同一子被检测两次     → 看原始检测框
 *   · 兵种或颜色读错（高亮环装饰）      → 看每格上的字
 * 把这三样画在屏幕上，人一眼就能审 —— 这是「模型域差」这类问题最快的定位方式。
 *
 * ⚠️ 画板是**系统悬浮窗**（TYPE_APPLICATION_OVERLAY），`screencap` 会**把它一起截进去**，
 * 所以抓帧前必须先 `drawing.hide()`（面板的 grabFrame 已经处理），否则绿框和文字会被
 * 当成棋盘上的东西、直接把识别搞坏。
 *
 * ⚠️ 画板能画的原语只有**矩形 + 一行标签**（颜色写死在 Java 侧），所以状态只能靠标签文字区分：
 *   `?`           未知格（棋规/冲突降级，或模型没认出来）
 *   `X!`          与「期望盘面」不一致（期望是别的子 / 期望有子却读成空）
 *   `·`           期望该有子、却读成空格（只在给了期望盘面时画）
 *   裸框（无标签） 网格线、以及"读成空的原检测框"
 */
"use strict";

const DEFAULTS = {
    /** 网格线用多细的框画（px）。 */
    lineThickness: 3,
    /** 网格线两端各伸出多少 px（棋盘外框一般比"九/十条线"再宽一点）。 */
    linePadding: 24,
    /** 每格标签框的边长（px）；画板把标签画在框左上角 +7px 处。 */
    cellBoxSize: 16,
    /** 检测框缺失宽高时的兜底尺寸（棋子圆盘直径约 96px）。 */
    defaultBoxSize: 88,
    /** 空格子要不要也画（只画框、不带标签）；"full" 模式下才开。 */
    drawEmptyCells: false,
    /** 最多画多少个原始检测框（防手滑把画板塞爆）。 */
    maxDetections: 80,
    /** 「最近着法」框的边长（px）：0 = 自适应（≈格距×0.86，整格框住那枚子）。 */
    moveMarkSize: 0
};

/**
 * 棋盘 9 列 × 10 行的**线**（不是格子中心）→ 细长矩形。
 * 这是判断「几何有没有对齐」最直观的东西：线应该正好穿过棋子圆心。
 */
function gridBoxes(grid, options) {
    const cfg = options || {};
    const thickness = cfg.lineThickness == null ? DEFAULTS.lineThickness : cfg.lineThickness;
    const pad = cfg.linePadding == null ? DEFAULTS.linePadding : cfg.linePadding;
    const items = [];
    if (!grid || !isFinite(grid.originX) || !isFinite(grid.stepX)) {
        return items;
    }
    const half = thickness / 2;
    const x1 = grid.originX - pad;
    const x2 = grid.originX + grid.stepX * 8 + pad;
    const y1 = grid.originY - pad;
    const y2 = grid.originY + grid.stepY * 9 + pad;
    for (let row = 0; row < 10; row++) {
        const y = grid.originY + grid.stepY * row;
        items.push({ x1: x1, y1: y - half, x2: x2, y2: y + half, label: "", score: 1 });
    }
    for (let col = 0; col < 9; col++) {
        const x = grid.originX + grid.stepX * col;
        items.push({ x1: x - half, y1: y1, x2: x + half, y2: y2, label: "", score: 1 });
    }
    return items;
}

/** 某格中心的屏幕坐标。 */
function centerOf(grid, row, col) {
    return {
        x: grid.originX + grid.stepX * col,
        y: grid.originY + grid.stepY * row
    };
}

/** ICCS 着法（如 "f6f5"）→ 起点/落点格坐标（row 0 在顶部，与网格口径一致）。 */
function squaresOfIccs(iccs) {
    const text = String(iccs == null ? "" : iccs);
    if (text.length < 4) {
        return null;
    }
    const cols = [text.charCodeAt(0) - 97, text.charCodeAt(2) - 97];
    const rows = [text.charCodeAt(1) - 48, text.charCodeAt(3) - 48];
    for (let i = 0; i < 2; i++) {
        if (cols[i] < 0 || cols[i] > 8 || rows[i] < 0 || rows[i] > 9) {
            return null;
        }
    }
    return { from: { col: cols[0], row: rows[0] }, to: { col: cols[1], row: rows[1] } };
}

/**
 * ★ 2026-09-25（用户要求）：「最近着法」框 —— 对方与我方的棋子，各画**走动之前（起点）**
 * 与**走动之后（落点）**两个框。宿主画板颜色写死，只能靠标签区分：
 *   我起 / 我落 = 我方最近一手； 敌起 / 敌落 = 对方最近一手。
 * 框默认自适应（≈格距×0.86，整格框住那枚子）；标签上会被宿主固定加上「分数%」。
 * @param marks { own: {iccs} | null, opponent: {iccs} | null }（都可空）
 */
function moveMarkBoxes(grid, marks, options) {
    const cfg = options || {};
    const items = [];
    if (!grid || !marks || !isFinite(grid.stepX)) {
        return items;
    }
    const size = (cfg.moveMarkSize > 0)
        ? cfg.moveMarkSize
        : Math.round(Math.min(grid.stepX, grid.stepY) * 0.86);
    const push = function (iccs, fromLabel, toLabel) {
        const squares = squaresOfIccs(iccs);
        if (!squares) {
            return;
        }
        const from = centerOf(grid, squares.from.row, squares.from.col);
        const to = centerOf(grid, squares.to.row, squares.to.col);
        items.push({ x1: from.x - size / 2, y1: from.y - size / 2,
            x2: from.x + size / 2, y2: from.y + size / 2, label: fromLabel, score: 1 });
        items.push({ x1: to.x - size / 2, y1: to.y - size / 2,
            x2: to.x + size / 2, y2: to.y + size / 2, label: toLabel, score: 1 });
    };
    if (marks.own && marks.own.iccs) {
        push(marks.own.iccs, "我起", "我落");
    }
    if (marks.opponent && marks.opponent.iccs) {
        push(marks.opponent.iccs, "敌起", "敌落");
    }
    return items;
}

/**
 * 每格的识别结果 → 小方框 + 一个字的标签。
 *   · 有子 → 画那个字（大写=红方 / 小写=黑方，与 FEN 一致），框上带该格的置信度
 *   · `?`（未知）→ 画 `?`
 *   · 该格有两个候选打不出高下（conflict）→ 标签前加 `?!`
 *   · 给了 `expectChars` 且与观测不同 → 画 `X!读到→期望`（这是「人审」最想看的一格）
 *
 * ⚠️ 标签不能为空串 否则画板不会画文字（引擎侧：空标签 = 纯框）；所以空格的标签是单个空格。
 */
function cellBoxes(grid, chars, options) {
    const cfg = options || {};
    const size = cfg.cellBoxSize == null ? DEFAULTS.cellBoxSize : cfg.cellBoxSize;
    const expect = cfg.expectChars || null;
    const scores = cfg.scores || null;
    const conflicts = cfg.conflicts || null;
    const drawEmpty = cfg.drawEmptyCells === true;
    const items = [];
    if (!grid || !chars) {
        return items;
    }
    for (let row = 0; row < 10; row++) {
        for (let col = 0; col < 9; col++) {
            const index = row * 9 + col;
            const got = chars[index];
            const empty = got == null || got === "";
            if (empty && !drawEmpty) {
                // 空格的例外：期望该有子却读成空 —— 这才是漏检，必须画出来
                const want = expect ? expect[index] : null;
                if (!want) {
                    continue;
                }
            }
            const center = centerOf(grid, row, col);
            let label = empty ? " " : String(got);
            if (conflicts && conflicts[index]) {
                label = "?!" + label;
            }
            if (expect) {
                const want = expect[index] == null ? "" : String(expect[index]);
                if (want !== (empty ? "" : String(got))) {
                    label = "X!" + (empty ? "空" : String(got)) + "→" + (want || "空");
                }
            }
            const score = (scores && isFinite(scores[index])) ? Number(scores[index]) : 0;
            items.push({
                x1: center.x - size / 2,
                y1: center.y - size / 2,
                x2: center.x + size / 2,
                y2: center.y + size / 2,
                label: label,
                score: score
            });
        }
    }
    return items;
}

/**
 * 原始检测框（模型直接输出的那些）→ 框 + `标签 分数`。
 * 用来判断「漏检 / 同一子检测两次 / 框偏到格子外」。
 */
function detectionBoxes(detections, options) {
    const cfg = options || {};
    const fallback = cfg.defaultBoxSize == null ? DEFAULTS.defaultBoxSize : cfg.defaultBoxSize;
    const max = cfg.maxDetections == null ? DEFAULTS.maxDetections : cfg.maxDetections;
    const items = [];
    const list = detections || [];
    for (let i = 0; i < list.length && items.length < max; i++) {
        const det = list[i];
        if (!det || !isFinite(det.x) || !isFinite(det.y)) {
            continue;
        }
        const width = isFinite(det.width) && det.width > 0 ? det.width : fallback;
        const height = isFinite(det.height) && det.height > 0 ? det.height : fallback;
        const score = det.score == null ? 1 : Number(det.score);
        items.push({
            x1: det.x - width / 2,
            y1: det.y - height / 2,
            x2: det.x + width / 2,
            y2: det.y + height / 2,
            label: String(det.label == null ? "?" : det.label) + " " + Math.round(score * 100) / 100,
            score: score
        });
    }
    return items;
}

/** 本手计划（票据的起点/落点）→ 两个框，标签带箭头，方便核对「点的地方对不对」。 */
function planBoxes(grid, ticket, options) {
    const cfg = options || {};
    const size = cfg.planBoxSize == null ? 40 : cfg.planBoxSize;
    const items = [];
    const iccsText = ticket && ticket.moveICCS ? String(ticket.moveICCS) : "";
    if (!grid || !/^[a-i][0-9][a-i][0-9]$/.test(iccsText)) {
        return items;
    }
    const place = function (offset, prefix) {
        const col = "abcdefghi".indexOf(iccsText.charAt(offset));
        const row = 9 - Number(iccsText.charAt(offset + 1));
        const center = centerOf(grid, row, col);
        items.push({
            x1: center.x - size / 2,
            y1: center.y - size / 2,
            x2: center.x + size / 2,
            y2: center.y + size / 2,
            label: prefix + iccsText.substring(offset, offset + 2),
            score: 1
        });
    };
    place(0, "▶");
    place(2, "◀");
    return items;
}

/**
 * 屏幕上一个点 → 小方框（用来标「实际点在哪里」：点击落点/鼠标位）。
 * 人审点击准确度时最有用：框应该正好套住棋子圆心。
 */
function pointBoxes(points, options) {
    const cfg = options || {};
    const size = cfg.pointBoxSize == null ? 34 : cfg.pointBoxSize;
    const items = [];
    const list = points || [];
    for (let i = 0; i < list.length; i++) {
        const point = list[i];
        if (!point || !isFinite(point.x) || !isFinite(point.y)) {
            continue;
        }
        items.push({
            x1: point.x - size / 2,
            y1: point.y - size / 2,
            x2: point.x + size / 2,
            y2: point.y + size / 2,
            label: String(point.label == null ? "" : point.label),
            score: 1
        });
    }
    return items;
}

/**
 * HUD 那行文字：画的是什么模式 + 关键数字 + 结论。 */
function summary(input) {
    const result = input && input.result;
    const parts = [];
    parts.push("绘制 " + (input && input.mode ? input.mode : "cells"));
    if (result) {
        parts.push("检测 " + (result.detectionCount == null ? "?" : result.detectionCount));
        parts.push("未知 " + (result.unknownCells == null ? "?" : result.unknownCells));
        if (result.timing && result.timing.totalMs != null) {
            parts.push("本帧 " + Math.round(result.timing.totalMs) + "ms");
        }
        if (result.stats && result.stats.autoAlign) {
            parts.push("微调 " + result.stats.autoAlign.unknown);
        }
        if (result.repaired && result.repaired.demoted && result.repaired.demoted.length) {
            parts.push("降级 " + result.repaired.demoted.length);
        }
    }
    if (input && input.grid && isFinite(input.grid.originX)) {
        parts.push("原点 (" + Math.round(input.grid.originX) + "," + Math.round(input.grid.originY) +
            ") 格距 (" + Math.round(input.grid.stepX) + "," + Math.round(input.grid.stepY) + ")");
    }
    if (input && input.extraText) {
        parts.push(String(input.extraText));
    }
    return parts.join("｜");
}

/**
 * 汇总成一帧画板内容。
 *
 * @param input.grid       已校准几何（必须）
 * @param input.result     recognizer 的结果（detections / chars / unknownCells / timing）
 * @param input.expectChars 期望盘面（可选：验证阶段传票据的预期后局面，能把「读错」标成 X!）
 * @param input.ticket     本手票据（可选：画起点/落点）
 * @param input.moveMarks  最近着法（可选：{own:{iccs}, opponent:{iccs}} → 画「我起/我落/敌起/敌落」4 个框）
 * @param input.mode       "grid"（只网格）/ "cells"（网格+每格字）/ "full"（再加原始检测框）
 * @param input.extraText  HUD 追加文字（轮次/结论等）
 * @returns {items, text}
 */
function build(input) {
    const opts = input || {};
    const cfg = opts.config || {};
    const mode = opts.mode === "grid" || opts.mode === "full" ? opts.mode : "cells";
    let items = gridBoxes(opts.grid, cfg);
    if (mode !== "grid") {
        const assigned = opts.result && opts.result.assigned ? opts.result.assigned : null;
        const scores = assigned ? assigned.map(function (cell) { return cell ? cell.score : 0; }) : null;
        const conflicts = assigned ? assigned.map(function (cell) { return !!(cell && cell.conflict); }) : null;
        items = items.concat(cellBoxes(opts.grid, opts.result ? opts.result.chars : null, {
            cellBoxSize: cfg.cellBoxSize,
            expectChars: opts.expectChars,
            scores: scores,
            conflicts: conflicts,
            drawEmptyCells: mode === "full" && cfg.drawEmptyCells === true
        }));
        items = items.concat(planBoxes(opts.grid, opts.ticket, cfg));
        items = items.concat(moveMarkBoxes(opts.grid, opts.moveMarks, cfg));
    }
    if (mode === "full" && opts.result) {
        items = items.concat(detectionBoxes(opts.result.detections, cfg));
    }
    if (mode !== "grid" && opts.points) {
        items = items.concat(pointBoxes(opts.points, cfg));
    }
    return { items: items, text: summary(opts) };
}

module.exports = {
    DEFAULTS: DEFAULTS,
    gridBoxes: gridBoxes,
    cellBoxes: cellBoxes,
    detectionBoxes: detectionBoxes,
    planBoxes: planBoxes,
    pointBoxes: pointBoxes,
    moveMarkBoxes: moveMarkBoxes,
    centerOf: centerOf,
    summary: summary,
    build: build
};
});

// ═══════════ 主流程（生成自 tools/build-solo-glyph.js 的 SOLO_MAIN） ═══════════
(function SOLO_MAIN() {
    "use strict";

    // ═════════════ 参数（要改就改这里；重新生成会重置本段） ═════════════
    var KEEP_MS = 20000;      // 画板保留多少毫秒后自动收起；0 = 不收
    var DELAY_MS = 3000;      // 抓屏前等待（毫秒）：从文件页点「运行」后用这几秒切到棋局；0 = 立即抓
    var DRAW_MODE = "cells";  // "grid" 只画网格线｜"cells" 网格 + 每格的字（默认）｜"full" 再多画原始检测框
    var IMAGE_PATH = "";      // 留空 = 抓当前屏幕；填路径（如 "/sdcard/Pictures/棋局.png"）= 用已保存的截图
    var CAPTURE_TIMEOUT_MS = 1500;
    var OUT_TXT = "/sdcard/xiangqi-p4/ce-glyph-solo.txt";
    // 模板目录候选（按序尝试）：脚本所在工程的 assets（工程随插件分发，装机后用
    // installProject() 还原到脚本目录）→ 设备上遗留的旧内置副本。
    // ⚠️ 不再有 APK 内置的 asset://sample/…/象棋自动走棋：工程已从主 APK 移出。
    var SOLO_SCRIPT_DIR = (typeof __dirname === "string" && __dirname) ? __dirname.replace(/[\\/]+$/, "") : "";
    var TPL_CANDIDATES = [
        "./assets/glyph-tpl",
        SOLO_SCRIPT_DIR ? SOLO_SCRIPT_DIR + "/assets/glyph-tpl" : null,
        "/data/data/com.jdkshen.aijspro/files/sample/QuickJS 新引擎/项目与打包/象棋自动走棋/assets/glyph-tpl"
    ].filter(function (item) { return !!item; });

    var report = [];
    function log(text) {
        console.log("[字形单] " + text);
        report.push(String(text));
    }

    var glyph = __require("src/vision/glyph-recognizer.js");
    var glyphResolve = __require("src/vision/glyph-resolve.js");
    var overlayDraw = __require("src/vision/overlay-draw.js");

    // 宿主接口（识别链只认这些函数；直接指向原生 API）
    var host = {
        sizeOf: function (image) { return { width: Number(image.width) || 0, height: Number(image.height) || 0 }; },
        grayscale: function (image) { return images.grayscale(image); },
        clip: function (image, x, y, w, h) { return images.clip(image, x, y, w, h); },
        threshold: function (image, t, max) { return images.threshold(image, t, max); },
        countColor: function (image, color, options) {
            // ⚠️ 原生 images.countColor 的 region 只认 [x,y,w,h] 数组，而识别链传的是
            // {x,y,width,height} 对象（工程里这层归一化在 aijs-adapter.countColor 里做）。
            // 异常时回退 findAllPointsForColor / null（识别链对 null 有回落通道）。
            var opts = options || {};
            var region = opts.region || null;
            var request = {
                region: region
                    ? [region.x != null ? region.x : region[0], region.y != null ? region.y : region[1],
                        region.width != null ? region.width : region[2],
                        region.height != null ? region.height : region[3]]
                    : null,
                threshold: opts.threshold == null ? 24 : opts.threshold
            };
            try {
                var count = images.countColor(image, color, request);
                return count == null ? null : count;
            } catch (error) {
                try {
                    var points = images.findAllPointsForColor(image, color, request);
                    return points ? points.length : 0;
                } catch (ignored) {
                    return null;
                }
            }
        },
        matchTemplate: function (image, template, options) { return images.matchTemplate(image, template, options); },
        pixel: function (image, x, y) { return images.pixel(image, x, y); },
        red: function (pixel) { return colors.red(pixel); },
        green: function (pixel) { return colors.green(pixel); },
        blue: function (pixel) { return colors.blue(pixel); },
        resize: function (image, size) { return images.resize(image, size); },
        read: function (path) { return images.read(path); },
        nowMono: function () { return Date.now(); }
    };

    // ⓪ 模板目录：脚本所在工程的 assets 优先，失败回退设备上遗留的旧内置副本
    function pickTplDir() {
        for (var i = 0; i < TPL_CANDIDATES.length; i++) {
            try {
                var probe = images.read(TPL_CANDIDATES[i] + "/tpl_兵.png");
                if (probe) {
                    if (typeof probe.recycle === "function") {
                        try { probe.recycle(); } catch (ignored) { /* 已回收 */ }
                    }
                    return TPL_CANDIDATES[i];
                }
            } catch (ignored) {
                // 试下一个候选
            }
        }
        return null;
    }

    // ═════════════ 1. 抓帧 ═════════════

    /** 抽样十几个点全黑 → 当作「刚授权后的黑帧」（真机踩过：第一帧整幅 0,0,0）。 */
    function frameIsBlack(frame) {
        var w = frame.width;
        var h = frame.height;
        var sum = 0;
        for (var i = 1; i <= 4; i++) {
            for (var j = 1; j <= 3; j++) {
                var p = images.pixel(frame, Math.round(w * i / 5), Math.round(h * j / 4));
                sum += colors.red(p) + colors.green(p) + colors.blue(p);
            }
        }
        return sum === 0;
    }

    function grabFrame() {
        if (!requestScreenCapture("portrait")) {
            throw new Error("截图授权被拒绝");
        }
        var frame = null;
        for (var i = 0; i < 3; i++) {
            try {
                frame = captureScreen({ mode: "full", fresh: true, timeout: CAPTURE_TIMEOUT_MS });
            } catch (error) {
                frame = null;
            }
            if (frame && !frameIsBlack(frame)) {
                return frame;
            }
            if (frame) {
                try { frame.recycle(); } catch (ignored) { /* 已回收 */ }
            }
            sleep(250);
        }
        throw new Error("连续 3 次截图失败/全黑（屏幕录制权限？）");
    }

    // ═════════════ 4. 绘制（宿主画板） ═════════════

    function showOverlay(grid, payload, extraText) {
        try { drawing.hide(); } catch (ignored) { /* 上一轮没画过 */ }
        var items = [];
        var text = String(extraText || "");
        if (grid && payload) {
            var rec = payload.rec;
            var resolved = payload.resolved;
            // 字形链的逐格分数 → 画板认的 assigned（标签配色/排序用）
            var scoreMap = {};
            (rec.cells || []).forEach(function (item) {
                if (item && item.idx != null && item.sim != null) {
                    scoreMap[item.idx] = item.sim;
                }
            });
            var assigned = [];
            for (var i = 0; i < 90; i++) {
                assigned.push(scoreMap[i] != null ? { score: scoreMap[i] } : null);
            }
            var result = {
                chars: resolved.chars,
                assigned: assigned,
                detectionCount: rec.inked,
                detections: [],
                unknownCells: resolved.stats.unknownAfter,
                timing: { totalMs: payload.ms },
                repaired: { demoted: resolved.demoted }
            };
            var built = overlayDraw.build({ grid: grid, result: result, mode: DRAW_MODE, extraText: extraText });
            items = built.items;
            text = built.text;
        }
        var shown = false;
        try { shown = drawing.show() === true; } catch (ignored) { shown = false; }
        if (!shown) {
            log("画板打不开（没有「显示在其他应用上层」权限？）——结果看日志");
            return false;
        }
        drawing.update(items, text);
        log("画板已显示：" + items.length + " 个框｜" + text);
        return true;
    }

    // ═════════════ 主流程 ═════════════

    var frame = null;
    try {
        var tplDir = pickTplDir();
        if (!tplDir) {
            throw new Error("字形模板读不到（脚本所在工程与设备上的旧副本都不行）");
        }
        log("模板目录：" + tplDir);

        // ① 抓帧：先收掉可能残留的旧画板；DELAY_MS 给「点运行后切棋局」留时间
        if (IMAGE_PATH) {
            frame = images.read(IMAGE_PATH);
            if (!frame) {
                throw new Error("读不到图片：" + IMAGE_PATH);
            }
        } else {
            try { drawing.hide(); } catch (ignored) { /* 没画过 */ }
            if (DELAY_MS > 0) {
                log(DELAY_MS + "ms 后抓屏 —— 请把棋局画面切到前台（脚本在后台继续跑）");
                sleep(DELAY_MS);
            }
            frame = grabFrame();
        }
        log("帧 " + frame.width + "x" + frame.height + (IMAGE_PATH ? "（文件 " + IMAGE_PATH + "）" : "（实时截屏）"));

        // ② 定位（原生 Canny/Hough）
        var loc = images.analyzeXiangqiBoard(frame);
        if (!loc || loc.ok !== true || !loc.grid) {
            throw new Error("原生定位失败：" + ((loc && loc.reason) || "没有返回几何"));
        }
        var grid = {
            originX: loc.grid.originX, originY: loc.grid.originY,
            stepX: loc.grid.stepX, stepY: loc.grid.stepY,
            orientation: loc.grid.orientation || "RED_BOTTOM"
        };
        log("定位（原生 canny-hough）：原点 (" + grid.originX.toFixed(1) + ", " + grid.originY.toFixed(1) +
            ")，格距 (" + grid.stepX.toFixed(1) + ", " + grid.stepY.toFixed(1) + ")，交点圆 " +
            (loc.circles ? loc.circles.length : "?") + " 枚");

        // ③ 识别（字形模板链）
        var templates = glyph.loadTemplates(host, tplDir);
        var scale = glyph.scaleFor(grid);
        var prepared = glyph.prepareFor(host, templates, scale);
        var started = Date.now();
        var rec = glyph.recognize(host, frame, grid, {
            templates: prepared.templates,
            TW: prepared.TW,
            ANCHOR_DY: prepared.ANCHOR_DY,
            coarseTemplates: prepared.coarse,
            collect: true
        });
        log("识别：触子 " + rec.inked + "｜未定 " + rec.unknown + "｜复核 " + rec.retries +
            "｜抢救 " + (rec.rescued || 0) + "/" + (rec.rescues || 0) + " 次（" + Math.round(rec.rescueMs || 0) + "ms）" +
            "｜耗时 " + rec.ms + "ms" + (Math.abs(scale - 1) >= 0.02 ? "｜模板缩放 " + scale.toFixed(3) : ""));

        // 盘面级判决：守恒补全 + 违规降级/改阵营（把「只差红黑/违棋规」的格救回来）
        var scores = {};
        (rec.cells || []).forEach(function (item) {
            if (item && item.idx != null && item.sim != null) {
                scores[item.idx] = item.sim;
            }
        });
        var resolved = glyphResolve.resolve(glyph.normalizeBoard(rec.chars), {
            scores: scores, notes: rec.notes || []
        });
        var chars = resolved.chars.map(function (ch) {
            return (ch == null || ch === ".") ? null : ch;
        });
        log("判决：补齐 " + resolved.filled.length + "｜降级 " + resolved.demoted.length +
            "｜改阵营 " + resolved.flipped.length + "｜未知 " + resolved.stats.unknownBefore +
            " → " + resolved.stats.unknownAfter);
        log("FEN: " + glyph.charsToFen(chars));
        log("识别总耗时 " + (Date.now() - started) + "ms");

        // ④ 绘制
        showOverlay(grid, { rec: rec, resolved: resolved, ms: rec.ms },
            "字形单文件" + (resolved.stats.unknownAfter > 0 ? "（未知 " + resolved.stats.unknownAfter + "）" : ""));

        console.log("GLYPH_SOLO_OK " + JSON.stringify({
            ok: true, method: "canny-hough",
            pieces: rec.inked, unknown: resolved.stats.unknownAfter,
            fen: glyph.charsToFen(chars)
        }));
    } catch (error) {
        log("出错：" + (error && error.message ? error.message : error));
        console.log("GLYPH_SOLO_ERR " + (error && error.message ? error.message : error));
    } finally {
        if (frame && typeof frame.recycle === "function") {
            try { frame.recycle(); } catch (ignored) { /* 已回收 */ }
        }
    }

    // 收尾：保留一会儿给肉眼审，然后收起画板；结果文本落盘（方便 adb pull）
    if (KEEP_MS > 0) {
        sleep(KEEP_MS);
        try { drawing.hide(); } catch (ignored) { /* 收不掉也不影响 */ }
        log("画板已收起（KEEP_MS=" + KEEP_MS + "）");
    }
    try {
        files.write(OUT_TXT, report.join("\n"));
        log("文本已写到 " + OUT_TXT);
    } catch (error) {
        try {
            files.write("/sdcard/ce-glyph-solo.txt", report.join("\n"));
            log("文本已写到 /sdcard/ce-glyph-solo.txt");
        } catch (ignored) { /* 写不了就算了，日志为准 */ }
    }
})();
