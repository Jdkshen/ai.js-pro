// @engine quickjs
/**
 * 快速将军判定：从**将/帅所在格反向辐射**，只看向那几个可能攻击它的子。
 *
 * 为什么不用 `rules.isInCheck`：它内部是 `isAttacked`，要遍历 90 格并对每个敌方子
 * 生成全部走法 —— 单次约几千次操作。接进搜索里（每个结点判定几十次）深度根本上不去。
 * 这里只做「从王的位置看出去」的固定工作量检查（4 条射线 + 8 个马位 + 3 个兵位）。
 *
 * 覆盖范围（穷举过一遍，剩下两类够不到）：
 *   车（射线第一个子）/ 炮（射线第二个子）/ 将（射线第一个子：相邻=普通吃，隔空=照面）
 *   马（8 个马位，蹩腿按**马**的位置算）/ 兵卒（正面 1 格 + 过河后侧面 1 格）
 *   士、象：永远够不到对方九宫，不用看
 *
 * ⚠️ 规则与 `rules.js` 必须一致；self-check 里有「随机对局中逐步与 rules.isInCheck 比对」的断言。
 */
"use strict";

const COLS = 9;
const ROWS = 10;

const ORTHOGONAL = [[0, -1], [0, 1], [-1, 0], [1, 0]];
// 马位：以王为中心，马可能在的 8 个格
const HORSE_OFFSETS = [[1, 2], [2, 1], [-1, 2], [-2, 1], [1, -2], [2, -1], [-1, -2], [-2, -1]];

function inside(col, row) {
    return col >= 0 && col < COLS && row >= 0 && row < ROWS;
}

function at(board, col, row) {
    return inside(col, row) ? (board[row * COLS + col] || null) : null;
}

function isRedPiece(ch) {
    return !!ch && ch >= "A" && ch <= "Z";
}

function findKing(board, red) {
    const target = red ? "K" : "k";
    for (let i = 0; i < board.length; i++) {
        if (board[i] === target) {
            return { col: i % COLS, row: (i - (i % COLS)) / COLS };
        }
    }
    return null;
}

/**
 * `col,row` 是否被 `byRed` 方攻击。**不含**「隔空的将帅照面」——
 * 那条规则只对将/帅生效，所以放在 isInCheck 里单独处理（用 allowFlyingKing 开关控制）。
 */
function isAttacked(board, col, row, byRed, allowFlyingKing) {
    const want = byRed ? "R" : "r";
    const wantCannon = byRed ? "C" : "c";
    const wantKing = byRed ? "K" : "k";

    // 四条正交射线：第一个子可能是车/将，第二个子可能是炮
    for (let d = 0; d < ORTHOGONAL.length; d++) {
        const dc = ORTHOGONAL[d][0];
        const dr = ORTHOGONAL[d][1];
        let c = col + dc;
        let r = row + dr;
        let first = null;
        while (inside(c, r)) {
            const piece = board[r * COLS + c] || null;
            if (piece) {
                if (first === null) {
                    first = { piece: piece, col: c, row: r };
                    if (piece === want) {
                        return true;
                    }
                    if (piece === wantKing) {
                        const distance = Math.abs(c - col) + Math.abs(r - row);
                        if (distance === 1) {
                            return true; // 相邻的将就是普通吃子
                        }
                        // 隔空的将只在**同一列**上构成照面；同一行隔多远都不算
                        if (allowFlyingKing && dc === 0) {
                            return true;
                        }
                    }
                } else if (piece === wantCannon) {
                    // 炮架之后的第一个子：是炮就吃
                    return true;
                } else {
                    break; // 第二个子不是炮，这条射线到此为止
                }
            }
            c += dc;
            r += dr;
        }
    }

    // 马：位置对，且马腿（紧邻马的直行格）是空的
    const wantHorse = byRed ? "N" : "n";
    for (let i = 0; i < HORSE_OFFSETS.length; i++) {
        const hc = col + HORSE_OFFSETS[i][0];
        const hr = row + HORSE_OFFSETS[i][1];
        if (at(board, hc, hr) !== wantHorse) {
            continue;
        }
        // 从马走到王的那一步：偏移是 (-dCol, -dRow)；长的那一维的相邻格就是马腿
        const dCol = -HORSE_OFFSETS[i][0];
        const dRow = -HORSE_OFFSETS[i][1];
        let legCol = hc;
        let legRow = hr;
        if (Math.abs(dCol) === 2) {
            legCol = hc + dCol / 2;
        } else {
            legRow = hr + dRow / 2;
        }
        if (!at(board, legCol, legRow)) {
            return true;
        }
    }

    // 兵卒：红兵朝 row 变小的方向走，黑卒朝 row 变大的方向走
    if (byRed) {
        // 正下方的红兵向前一步正好吃到这里
        if (at(board, col, row + 1) === "P") {
            return true;
        }
        // 过河后的红兵能横走；横着打过来的兵与目标同行，所以用它自己的行判是否过河
        if (row <= 4) {
            if (at(board, col - 1, row) === "P" || at(board, col + 1, row) === "P") {
                return true;
            }
        }
    } else {
        if (at(board, col, row - 1) === "p") {
            return true;
        }
        if (row >= 5) {
            if (at(board, col - 1, row) === "p" || at(board, col + 1, row) === "p") {
                return true;
            }
        }
    }

    return false;
}

/** 某方是否被将（含将帅照面）。找不到将/帅 → 视为异常，按「被将」处理（与 rules 一致）。 */
function isInCheck(board, red) {
    const king = findKing(board, red);
    if (!king) {
        return true;
    }
    return isAttacked(board, king.col, king.row, !red, true);
}

/** 将帅照面（同一列、中间无子）。搜索里判「不能照面」用得上。 */
function kingsFacing(board) {
    const redKing = findKing(board, true);
    const blackKing = findKing(board, false);
    if (!redKing || !blackKing || redKing.col !== blackKing.col) {
        return false;
    }
    const from = Math.min(redKing.row, blackKing.row) + 1;
    const to = Math.max(redKing.row, blackKing.row);
    for (let row = from; row < to; row++) {
        if (board[row * COLS + redKing.col]) {
            return false;
        }
    }
    return true;
}

const XQ_ATTACKS = {
    isAttacked: isAttacked,
    isInCheck: isInCheck,
    kingsFacing: kingsFacing,
    findKing: findKing
};

module.exports = XQ_ATTACKS;
