// @engine quickjs
"use strict";

/** 在定位时做一次全盘棋子圆心检查；不参与每帧逐字识别。 */
function detect(api, frame, grid, size) {
    if (!api || typeof api.findCircles !== "function" || !api.clip || !grid || !size) {
        return { circles: [], reason: "圆检测接口或几何不可用" };
    }
    const step = (Math.abs(grid.stepX) + Math.abs(grid.stepY)) / 2;
    if (step < 35) {
        return { circles: [], reason: "格距太小" };
    }
    // 上下多留一行，允许校准候选把第 0 行错锚一格，圆心仍能用于否决半格漂移。
    const x = Math.max(0, Math.floor(grid.originX - grid.stepX * 0.7));
    const y = Math.max(0, Math.floor(grid.originY - grid.stepY * 1.3));
    const right = Math.min(size.width, Math.ceil(grid.originX + grid.stepX * 8.7));
    const bottom = Math.min(size.height, Math.ceil(grid.originY + grid.stepY * 10.3));
    const width = right - x;
    const height = bottom - y;
    if (width < step * 7 || height < step * 8) {
        return { circles: [], reason: "棋盘区域越界" };
    }
    let roi = null;
    try {
        roi = api.clip(frame, x, y, width, height);
        const found = api.findCircles(roi, {
            dp: 1.2, minDst: Math.round(step * 0.64),
            param1: 110, param2: Math.max(25, Math.round(step * 0.39)),
            minRadius: Math.round(step * 0.30), maxRadius: Math.round(step * 0.49)
        });
        return { circles: (found || []).map(function (one) {
            return { x: one.x + x, y: one.y + y, radius: one.radius };
        }), reason: "" };
    } catch (error) {
        return { circles: [], reason: String(error) };
    } finally {
        if (roi && typeof roi.recycle === "function") {
            try { roi.recycle(); } catch (ignore) { }
        }
    }
}

function evaluate(circles, grid) {
    if (!circles || circles.length < 3 || !grid || !grid.stepX || !grid.stepY) {
        return { conclusive: false, ok: true, matched: 0, total: circles ? circles.length : 0 };
    }
    const used = {};
    let total = 0;
    let matched = 0;
    for (let i = 0; i < circles.length; i++) {
        const c = circles[i];
        const fx = (c.x - grid.originX) / grid.stepX;
        const fy = (c.y - grid.originY) / grid.stepY;
        const col = Math.round(fx);
        const row = Math.round(fy);
        if (col < 0 || col > 8 || row < 0 || row > 9 ||
            Math.abs(fx - col) > 0.7 || Math.abs(fy - row) > 0.7) {
            continue;
        }
        total += 1;
        const key = row * 9 + col;
        if (!used[key] && Math.abs(fx - col) <= 0.27 && Math.abs(fy - row) <= 0.27) {
            used[key] = true;
            matched += 1;
        }
    }
    if (total < 3) {
        return { conclusive: false, ok: true, matched: matched, total: total };
    }
    return { conclusive: true, ok: matched >= Math.max(3, Math.ceil(total * 0.65)),
        matched: matched, total: total };
}

module.exports = { detect: detect, evaluate: evaluate };
