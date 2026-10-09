// @engine quickjs
"use strict";

/**
 * 圆仅确认占位和红黑，棋种始终由字形模板决定。证据冲突留作未知，
 * 让自动走棋等待下一帧；不能用圆的检测结果凭空猜棋种。
 */
function verify(board, circles, referenceBoard) {
    const result = board.slice();
    const expected = Array.isArray(referenceBoard)
        ? referenceBoard.filter(function (piece) { return !!piece; }).length : 0;
    const minimum = Math.max(5, expected ? Math.ceil(expected * 0.85) : 8);
    if (!Array.isArray(circles) || circles.length < minimum || circles.length > 32) {
        return { board: result, active: false, conflicts: 0, circles: circles ? circles.length : 0 };
    }
    const at = {};
    for (let i = 0; i < circles.length; i++) {
        const one = circles[i];
        if (one && one.col >= 0 && one.col < 9 && one.row >= 0 && one.row < 10) {
            at[one.row * 9 + one.col] = one;
        }
    }
    const sides = {};
    let conflicts = 0;
    for (let i = 0; i < result.length; i++) {
        const piece = result[i];
        const circle = at[i];
        if ((circle && !piece) || (!circle && piece && piece !== "?")) {
            result[i] = "?";
            conflicts++;
            continue;
        }
        if (!circle) continue;
        if (circle.redFraction >= 0.8) sides[i] = "RED";
        else if (circle.redFraction <= 0.2) sides[i] = "BLACK";
        if (!piece || piece === "?") continue;
        const red = circle.redFraction >= 0.8;
        const black = circle.redFraction <= 0.2;
        if ((red && piece !== piece.toUpperCase()) ||
            (black && piece !== piece.toLowerCase())) {
            result[i] = "?";
            conflicts++;
        }
    }
    return { board: result, active: true, conflicts: conflicts, circles: circles.length, sides: sides };
}

module.exports = { verify: verify };
