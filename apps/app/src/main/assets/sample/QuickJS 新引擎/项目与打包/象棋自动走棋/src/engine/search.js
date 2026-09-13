// @engine quickjs
/**
 * 搜索：alpha-beta（negamax）+ 迭代加深 + 置换表 + 静态搜索（只搜吃子）。
 *
 * 几个刻意的取舍：
 * 1. **用 attacks.js 的快速将军判定**，不用 rules.isInCheck —— 后者每次要扫全盘并给每个敌方子
 *    生成走法，放进搜索里（每个结点都要判几十次）深度根本上不去。
 * 2. **走法生成只做「伪合法 + 走完自己不被将」过滤**；将帅照面已经被 isInCheck 算作被将，
 *    所以不需要额外规则。
 * 3. **无着可走 = 判负**（中国象棋的困毙是输，不是和棋），所以将死与困毙返回同一个分数。
 * 4. 置换表用 Zobrist 增量哈希做 key（不是每结点拼 90 字符串）。
 * 5. 不依赖任何宿主接口（只用 Date.now），PC 上能直接跑自检。
 */
"use strict";

const rules = require("../chess/rules.js");
const attacks = require("./attacks.js");
const evalModule = require("./eval.js");

const COLS = 9;
const ROWS = 10;
const CELLS = COLS * ROWS;

const MATE = evalModule.MATE;
const INFINITY = 1 << 24;

const FLAG_EXACT = 0;
const FLAG_LOWER = 1; // 分数是下界（发生 beta 截断）
const FLAG_UPPER = 2; // 分数是上界（没能超过 alpha）

const KINDS = "RNBAKCP";

// ---------------------------------------------------------------- Zobrist 哈希

let ZOBRIST = null;

function initZobrist() {
    const table = [];
    let state = 0x1f2e3d4c;
    function next() {
        state ^= state << 13;
        state >>>= 0;
        state ^= state >>> 17;
        state ^= state << 5;
        state >>>= 0;
        return state >>> 0;
    }
    for (let p = 0; p < 14; p++) {
        const line = [];
        for (let c = 0; c < CELLS; c++) {
            line.push(next());
        }
        table.push(line);
    }
    return table;
}

if (!ZOBRIST) {
    ZOBRIST = initZobrist();
}

function pieceIndex(ch) {
    const kind = KINDS.indexOf(ch.toUpperCase());
    if (kind < 0) {
        return 0;
    }
    return ch === ch.toUpperCase() ? kind : kind + 7;
}

function hashOf(board) {
    let value = 0;
    for (let i = 0; i < board.length; i++) {
        if (board[i]) {
            value ^= ZOBRIST[pieceIndex(board[i])][i];
        }
    }
    return value >>> 0;
}

// ---------------------------------------------------------------- 落子 / 撤销

let currentHash = 0;
const undoStack = [];

function makeMove(board, move) {
    const from = move.fromRow * COLS + move.fromCol;
    const to = move.toRow * COLS + move.toCol;
    const mover = board[from];
    const captured = board[to] || null;
    board[to] = mover;
    board[from] = null;
    currentHash ^= ZOBRIST[pieceIndex(mover)][from];
    currentHash ^= ZOBRIST[pieceIndex(mover)][to];
    if (captured) {
        currentHash ^= ZOBRIST[pieceIndex(captured)][to];
    }
    undoStack.push({ from: from, to: to, mover: mover, captured: captured });
}

function unmakeMove(board) {
    const entry = undoStack.pop();
    board[entry.from] = entry.mover;
    board[entry.to] = entry.captured;
    currentHash ^= ZOBRIST[pieceIndex(entry.mover)][entry.from];
    currentHash ^= ZOBRIST[pieceIndex(entry.mover)][entry.to];
    if (entry.captured) {
        currentHash ^= ZOBRIST[pieceIndex(entry.captured)][entry.to];
    }
}

// ---------------------------------------------------------------- 走法生成

function sideToRed(side) {
    return side === "w" || side === "W" || side === true || side === "red";
}

/** 合法着法：伪合法里滤掉「走完自己被将」。 */
function generateMoves(board, red, capturesOnly) {
    const moves = [];
    for (let i = 0; i < CELLS; i++) {
        const piece = board[i];
        if (!piece || rules.isRedPiece(piece) !== red) {
            continue;
        }
        const col = i % COLS;
        const row = (i - col) / COLS;
        const pseudo = rules.pseudoMoves(board, col, row);
        for (let m = 0; m < pseudo.length; m++) {
            const move = pseudo[m];
            if (capturesOnly && !move.captured) {
                continue;
            }
            makeMove(board, move);
            const bad = attacks.isInCheck(board, red);
            unmakeMove(board);
            if (!bad) {
                moves.push(move);
            }
        }
    }
    return moves;
}

/** 走法排序：置换表着法最优先，其次按 MVV-LVA（吃大子、用小子吃）。 */
function orderMoves(moves, board, ttMove) {
    for (let i = 0; i < moves.length; i++) {
        const move = moves[i];
        let score = 0;
        if (move.captured) {
            const victim = evalModule.valueOf(move.captured);
            const attacker = evalModule.valueOf(board[move.fromRow * COLS + move.fromCol]);
            score = 100000 + victim * 10 - attacker;
        }
        if (ttMove && ttMove.fromCol === move.fromCol && ttMove.fromRow === move.fromRow &&
                ttMove.toCol === move.toCol && ttMove.toRow === move.toRow) {
            score += 1000000;
        }
        move.order = score;
    }
    moves.sort(function (a, b) { return b.order - a.order; });
}

// ---------------------------------------------------------------- 搜索状态

let nodes = 0;
let deadline = 0;
let stopped = false;
let table = new Map();

function outOfNodes() {
    // 每 2048 个结点看一次表，避免 Date.now() 成为热点
    if ((nodes & 2047) === 0 && Date.now() > deadline) {
        stopped = true;
    }
    return stopped;
}

/** 静态搜索：只继续搜索吃子，避免「评估在吃到一半时收手」造成的水平线效应。 */
function quiescence(board, red, alpha, beta, ply) {
    nodes++;
    if (nodes > 0 && (nodes & 2047) === 0 && Date.now() > deadline) {
        stopped = true;
        return 0;
    }
    const standPat = evalModule.evaluate(board) * (red ? 1 : -1);
    if (standPat >= beta) {
        return beta;
    }
    if (standPat > alpha) {
        alpha = standPat;
    }
    if (ply > 24) {
        return alpha;
    }
    const moves = generateMoves(board, red, true);
    orderMoves(moves, board, null);
    for (let i = 0; i < moves.length; i++) {
        makeMove(board, moves[i]);
        const score = -quiescence(board, !red, -beta, -alpha, ply + 1);
        unmakeMove(board);
        if (stopped) {
            return 0;
        }
        if (score >= beta) {
            return beta;
        }
        if (score > alpha) {
            alpha = score;
        }
    }
    return alpha;
}

function negamax(board, red, depth, alpha, beta, ply) {
    nodes++;
    if ((nodes & 2047) === 0 && Date.now() > deadline) {
        stopped = true;
        return 0;
    }

    const original = alpha;
    const key = currentHash;
    const entry = table.get(key);
    let ttMove = null;
    if (entry) {
        ttMove = entry.move || null;
        if (entry.depth >= depth) {
            if (entry.flag === FLAG_EXACT) {
                return entry.score;
            }
            if (entry.flag === FLAG_LOWER && entry.score > alpha) {
                alpha = entry.score;
            } else if (entry.flag === FLAG_UPPER && entry.score < beta) {
                beta = entry.score;
            }
            if (alpha >= beta) {
                return entry.score;
            }
        }
    }

    if (depth <= 0) {
        return quiescence(board, red, alpha, beta, ply);
    }

    const moves = generateMoves(board, red, false);
    if (moves.length === 0) {
        // 将死与困毙都是输（中国象棋困毙判负）
        return -(MATE - ply);
    }
    orderMoves(moves, board, ttMove);

    let best = -INFINITY;
    let bestMove = null;
    for (let i = 0; i < moves.length; i++) {
        makeMove(board, moves[i]);
        const score = -negamax(board, !red, depth - 1, -beta, -alpha, ply + 1);
        unmakeMove(board);
        if (stopped) {
            return 0;
        }
        if (score > best) {
            best = score;
            bestMove = moves[i];
        }
        if (score > alpha) {
            alpha = score;
        }
        if (alpha >= beta) {
            break;
        }
    }

    if (table.size < 400000) {
        let flag = FLAG_EXACT;
        if (best <= original) {
            flag = FLAG_UPPER;
        } else if (best >= beta) {
            flag = FLAG_LOWER;
        }
        table.set(key, { depth: depth, score: best, flag: flag, move: bestMove });
    }
    return best;
}

/** 从置换表里把主变走法串起来（可能被覆盖，串到断为止）。 */
function principalVariation(board, red, maxLength) {
    const moves = [];
    const seen = {};
    const scratch = board.slice();
    // ⚠️ 只能回退「本次压入的帧」：早退/异常都可能让栈不干净，用一个基线护住
    const base = undoStack.length;
    for (let i = 0; i < (maxLength || 8); i++) {
        const key = hashOf(scratch);
        if (seen[key]) {
            break;
        }
        seen[key] = true;
        const entry = table.get(key);
        if (!entry || !entry.move) {
            break;
        }
        const move = entry.move;
        const legal = generateMoves(scratch, red, false).some(function (candidate) {
            return candidate.fromCol === move.fromCol && candidate.fromRow === move.fromRow &&
                candidate.toCol === move.toCol && candidate.toRow === move.toRow;
        });
        if (!legal) {
            break;
        }
        moves.push(move);
        makeMove(scratch, move);
        red = !red;
    }
    while (undoStack.length > base) {
        unmakeMove(scratch);
    }
    return moves;
}

/**
 * 搜索最佳着法。
 * @param board 90 长数组（会被就地改动，但返回前一定复原）
 * @param side  'w'（红）/'b'（黑）
 * @param options.movetime 时间预算 ms（默认 1000）
 * @param options.depth    最大深度（默认只受时间限制）
 * @param options.requestId 仅用于日志/回调
 * @returns { ok, move, iccs, score, depth, nodes, ms, mateIn, pv, reason }
 */
function search(board, side, options) {
    const opts = options || {};
    const red = sideToRed(opts.side == null ? side : opts.side);
    const movetime = opts.movetime == null ? 1000 : opts.movetime;
    const maxDepth = opts.depth == null ? 64 : opts.depth;
    const started = Date.now();
    deadline = started + movetime;
    nodes = 0;
    stopped = false;
    table = new Map();
    undoStack.length = 0; // 上一次搜索如果被时间掐断在主变里，栈可能残留

    currentHash = hashOf(board);
    // 局面本身不能是被将到无着可走，否则直接报出来
    const roots = generateMoves(board, red, false);
    if (roots.length === 0) {
        return {
            ok: false,
            reason: attacks.isInCheck(board, red) ? "被将死（或困毙）" : "无着可走（困毙判负）",
            depth: 0,
            nodes: 0,
            ms: Date.now() - started
        };
    }

    let best = null;
    let reachedDepth = 0;
    for (let depth = 1; depth <= maxDepth; depth++) {
        let alpha = -INFINITY;
        const beta = INFINITY;
        let bestScore = -INFINITY;
        let bestMove = null;
        const moves = generateMoves(board, red, false);
        orderMoves(moves, board, best ? best.move : null);
        for (let i = 0; i < moves.length; i++) {
            makeMove(board, moves[i]);
            const score = -negamax(board, !red, depth - 1, -beta, -alpha, 1);
            unmakeMove(board);
            if (stopped) {
                break;
            }
            if (score > bestScore) {
                bestScore = score;
                bestMove = moves[i];
            }
            if (score > alpha) {
                alpha = score;
            }
        }
        if (stopped || !bestMove) {
            break;
        }
        reachedDepth = depth;
        best = {
            move: bestMove,
            score: bestScore,
            depth: depth,
            nodes: nodes,
            ms: Date.now() - started
        };
        // 已经找到杀棋就不用再深了
        if (Math.abs(bestScore) > MATE - 200) {
            break;
        }
        if (Date.now() > deadline) {
            break;
        }
    }

    if (!best) {
        return {
            ok: false,
            reason: "时间太短，连第一层都没搜完",
            depth: 0,
            nodes: nodes,
            ms: Date.now() - started
        };
    }

    const pv = principalVariation(board, red, 8);
    const move = best.move;
    return {
        ok: true,
        move: move,
        iccs: rules.moveToIccs(move),
        captured: move.captured || null,
        score: best.score,
        depth: reachedDepth,
        nodes: best.nodes,
        ms: Date.now() - started,
        mateIn: Math.abs(best.score) > MATE - 200
            ? (best.score > 0 ? 1 : -1) * Math.ceil((MATE - Math.abs(best.score)) / 2)
            : null,
        pv: pv,
        pvIccs: pv.map(function (item) { return rules.moveToIccs(item); })
    };
}

/** 只是给「这个局面是不是已经结束了」用的轻量查询。 */
function hasLegalMove(board, side) {
    return generateMoves(board, sideToRed(side), false).length > 0;
}

const XQ_SEARCH = {
    MATE: MATE,
    search: search,
    hasLegalMove: hasLegalMove,
    generateMoves: generateMoves,
    hashOf: hashOf,
    sideToRed: sideToRed
};

module.exports = XQ_SEARCH;
