// @engine quickjs
/**
 * 几何体检：识破「假成功」的网格（2026-09-20 真机）。
 *
 * 真机现场（K40，中局帧）：线定位**成功**返回 `origin.y = 590`，而棋盘第 0 行在约 690 ——
 * 整行偏一格（≈100px ≈0.87 格距），精校准还照样报「未知格 0」（因为偏移接近一整格，
 * 90 个交点全都落在格子上，只是全都错了一行）⇒ 所有子被读成「往下一行」：
 *   · 黑方底线跑到第 1 行、红方底线（真第 9 行）掉到网格外 → 那一行全是未知；
 *   · 基准/票据/轮次推断全部对不上 → 每帧「解释不了局面」→ 整轮空转。
 *
 * 判据：**极端行**（0 / 9）如果一个子证据都没有，而相邻行（1 / 8）却有 6 个以上 ⇒ 判为整行偏。
 * 为什么这条够用：一副棋盘上「第 1 行挤了 6 枚以上的子」而「第 0 行一个子都没有」，
 * 在任意合法局面上都几乎不可能是真的（底线那一排才是密集区）。
 *
 * 纯函数（`rowsOf` / `suspect`）离线可测；检测与取框由调用方负责。
 */
"use strict";

const DEFAULTS = {
    /** 相邻行要有这么多子，才认为「极端行空着」是异常 */
    minNeighbour: 6
};

/** 把 `[{row}]` 归成 10 行的计数 */
function rowsOf(cells) {
    const rows = [0, 0, 0, 0, 0, 0, 0, 0, 0, 0];
    for (let i = 0; i < (cells ? cells.length : 0); i++) {
        const row = cells[i] ? cells[i].row : null;
        if (typeof row === "number" && row >= 0 && row <= 9) {
            rows[row] += 1;
        }
    }
    return rows;
}

/**
 * @return { ok, reason, rows }
 */
function suspect(rows, options) {
    const opts = Object.assign({}, DEFAULTS, options || {});
    const r = rows && rows.length ? rows : [];
    const top = r[0] || 0;
    const next = r[1] || 0;
    const prev = r[8] || 0;
    const bottom = r[9] || 0;
    const snapshot = r.slice ? r.slice() : r;
    if (top === 0 && next >= opts.minNeighbour) {
        return {
            ok: false,
            reason: "第 0 行一个子都没有、第 1 行却有 " + next + " 个（疑整行偏了一行）",
            rows: snapshot
        };
    }
    if (bottom === 0 && prev >= opts.minNeighbour) {
        return {
            ok: false,
            reason: "第 9 行一个子都没有、第 8 行却有 " + prev + " 个（疑整行偏了一行）",
            rows: snapshot
        };
    }
    // ★ 第二条（2026-09-20 深夜 now49 真机）：中残局里底线往往只剩 5~7 枚子，「整行偏」
    //   的签名变成「顶行 0 + 第 1 行 4~5 枚 + **底行也只剩 1~2 枚**」（真底线整排被挤到网格外）。
    //   旧规则的 ≥6 门槛刚好漏掉这种帧（now49：0,5,1,2,3,1,1,4,1,1 被放行 ⇒ 偏一行的网格
    //   被校准收下 ⇒ 7 个未知格 + 一直不动）。
    //   判据：**两端都断**（顶行空 ∧ 第 1 行 ≥4 ∧ 底行 ≤2）⇒ 只有中间有货 = 整行偏。
    //   反向保护：合法的「顶行真空了」局面里，红方底线（帅/仕/相）不可能同时只剩 1~2 枚 ⇒ 不误判。
    if (top === 0 && next >= 4 && bottom <= 2) {
        return {
            ok: false,
            reason: "第 0 行空、第 1 行 " + next + " 个，而第 9 行只剩 " + bottom +
                " 个（两端都断，疑整行偏了一行）",
            rows: snapshot
        };
    }
    if (bottom === 0 && prev >= 4 && top <= 2) {
        return {
            ok: false,
            reason: "第 9 行空、第 8 行 " + prev + " 个，而第 0 行只剩 " + top +
                " 个（两端都断，疑整行偏了一行）",
            rows: snapshot
        };
    }
    return { ok: true, reason: "行分布 " + snapshot.join(","), rows: snapshot };
}

/**
 * 平移候选：线定位「整行/半行偏」时依次试的原点偏移（按优先级排序）。
 *
 * 真机 now47（中残局）：线定位把 origin 锚早了一行（行分布「0,7,…」被 suspect 拒），
 * 旧行为直接回退 pickGrid 缓存框 —— 比真实格点偏 ~17px（origin≈(112,718) vs 真实≈(95,705)）
 * ⇒ 裁格/判色全盘变差。正确做法：在**线定位结果本身上**试这几个平移并用同一套体检验证：
 *   · 「第 0 行空、第 1 行挤」⇒ origin 太靠上 ⇒ +1 行
 *   · 「第 9 行空、第 8 行挤」⇒ origin 太靠下 ⇒ -1 行
 *   · ±半行覆盖「线锚错半格」的老现场（`tools/p4-calibrate-shift-probe.js` 那个 ~49px）。
 */
function shiftCandidates(grid) {
    const stepY = Math.abs(Number(grid && grid.stepY) || 114);
    const rows = Math.round(stepY);
    const half = Math.round(stepY / 2);
    return [[0, rows], [0, -rows], [0, half], [0, -half]];
}

module.exports = {
    rowsOf: rowsOf, suspect: suspect, shiftCandidates: shiftCandidates, DEFAULTS: DEFAULTS
};
