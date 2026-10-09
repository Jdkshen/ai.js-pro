// @engine quickjs
"use strict";

/** 自动求胜策略的停止条件，不代替游戏判和或象棋规则裁决。 */
function inspect(board, side) {
    if (!Array.isArray(board) || board.length !== 90 || (side !== "w" && side !== "b")) return null;
    let majors=0, pawns=0, kings=0, enemyKings=0, key="";
    for (let i=0;i<board.length;i++) {
        const piece=board[i];
        if (piece == null || piece === "") continue;
        if (typeof piece !== "string" || !/^[KABRCNPkabrcnp]$/.test(piece)) return null;
        const mine=(piece === piece.toUpperCase()) === (side === "w");
        if (piece.toUpperCase() === "K") {if (mine) kings++; else enemyKings++;}
        if (!mine) continue;
        key+=i+":"+piece+";";
        if (/^[RCN]$/i.test(piece)) majors++;
        if (/^P$/i.test(piece)) pawns++;
    }
    if (kings !== 1 || enemyKings !== 1) return null;
    return {majors:majors,pawns:pawns,attackers:majors+pawns,key:key};
}

/** 原始新帧、修正盘面与可信账本三者一致无攻子，并由两张不同帧佐证。 */
function createGuard() {
    let key="",lastFrame=null,streak=0;
    function clear(){key="";lastFrame=null;streak=0;}
    return {clear:clear,check:function (input) {
        const x=input||{},raw=inspect(x.rawBoard,x.side),view=inspect(x.board,x.side),
            reference=inspect(x.referenceBoard,x.side);
        if (!raw || !view || !reference || raw.attackers || view.attackers || reference.attackers ||
            raw.key !== view.key || raw.key !== reference.key) {clear();return null;}
        const nextKey=x.sessionId+"|"+x.side+"|"+raw.key;
        const frame=x.frameId == null ? "obs:"+x.observationId : "frame:"+x.frameId;
        if (nextKey !== key) {key=nextKey;lastFrame=null;streak=0;}
        if (frame !== lastFrame) {streak++;lastFrame=frame;}
        return {streak:streak,confirmed:streak>=2,
            reason:"我方仅剩将帅、士相，已无车马炮兵；自动求胜停止（未判定游戏和棋）"};
    }};
}

module.exports={inspect:inspect,createGuard:createGuard};
