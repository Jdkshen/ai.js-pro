// @engine quickjs
"use strict";

/** SIFT 只定位棋盘；字形、阵营和棋规由原识别链判断。缓存只保存几何和河界参照图。 */
function gridFrom(location, calibration, orientation) {
    const tl = location.topLeft, tr = location.topRight;
    const bl = location.bottomLeft, br = location.bottomRight;
    const sx = (tr.x - tl.x) / calibration.width;
    const sy = (bl.y - tl.y) / calibration.height;
    const tolerance = Math.max(3, Math.min(tr.x - tl.x, bl.y - tl.y) * 0.008);
    if (!(sx > 0 && sy > 0) || Math.abs(tr.y - tl.y) > tolerance ||
        Math.abs(bl.x - tl.x) > tolerance || Math.abs(br.x - tr.x) > tolerance ||
        Math.abs(br.y - bl.y) > tolerance) return null;
    // 横竖必须接近等比：宿主 fast 快跑在低特征画面上可能收敛到压扁的错误单应（四角仍像平行四边形），
    // 例如 1280 屏空盘上竖边被压 21%。比例失衡直接判无效，交回外层换帧/换档。
    if (Math.abs(sx - sy) > Math.max(sx, sy) * 0.15) return null;
    return { originX: tl.x + calibration.originX * sx, originY: tl.y + calibration.originY * sy,
        stepX: calibration.stepX * sx, stepY: calibration.stepY * sy,
        orientation: orientation || "RED_BOTTOM", version: 1 };
}

function sameGrid(a, b) {
    if (!a || !b || a.orientation !== b.orientation) return false;
    const tolerance = Math.max(1, Math.min(a.stepX, a.stepY) * 0.02);
    return Math.abs(a.originX - b.originX) <= tolerance && Math.abs(a.originY - b.originY) <= tolerance &&
        Math.abs(a.stepX - b.stepX) * 8 <= tolerance && Math.abs(a.stepY - b.stepY) * 9 <= tolerance;
}

/** 多档模板缩放：模板 board.png 采自 1080 宽屏（板距 114.8px），别的屏宽要换档才匹得上。
 *  预测值 ≈ 屏宽 × 0.000463（1080→0.50、1280→0.59），按「离预测值近的先试」排序。 */
const TEMPLATE_SCALES = [0.5, 0.55, 0.59, 0.63, 0.68, 0.42, 0.34];
const SCALE_PER_WIDTH = 0.000463;

function create(options) {
    const api = options.images, host = options.host, calibration = options.calibration;
    const scales = options.templateScales || TEMPLATE_SCALES;
    const scalePerWidth = options.templateScalePerWidth || SCALE_PER_WIDTH;
    const matchers = {};   // 档位 → 匹配器（懒建；每档自带跟踪，命中过的档后续优先复用）
    let sticky = null;     // 上次命中的档
    let memo = null, candidate = null, closed = false;
    function matcherAt(ts) {
        const key = ts.toFixed(3);
        let entry = matchers[key];
        if (!entry) {
            // 不开 fast：宿主 fast 会先用 2/3 降档试配，低特征画面（空盘残局）上可能收敛到
            // 压扁的错误单应（新手机 1280 屏实测竖边被压 21%）；宁可慢一点也别拿错几何。
            entry = { ts: ts, matcher: api.createSiftMatcher(options.template, {
                scale: 0.5, templateScale: ts, track: true, trackPadding: 24
            }) };
            matchers[key] = entry;
        }
        return entry;
    }
    function orderFor(image) {
        const size = host.sizeOf(image);
        const width = size && size.width ? size.width : 0;
        const predicted = Math.min(4, Math.max(0.1, Math.round(width * scalePerWidth * 1000) / 1000));
        const order = scales.slice().sort(function (a, b) {
            return Math.abs(a - predicted) - Math.abs(b - predicted);
        });
        if (sticky != null) {
            const index = order.indexOf(sticky);
            if (index > 0) { order.splice(index, 1); order.unshift(sticky); }
        }
        return order;
    }
    /** 逐档试：匹上且四角几何成立（gridFrom 校验）才算命中；命中过的档下一帧先试。 */
    function matchAny(image, orientation) {
        const order = orderFor(image);
        let matched = false, prepareMs = 0;
        for (let i = 0; i < order.length; i++) {
            const entry = matcherAt(order[i]);
            const found = entry.matcher.match(image);
            prepareMs = entry.matcher.templatePrepareMs || prepareMs;
            if (!found) continue;
            matched = true;
            const grid = gridFrom(found, calibration, orientation);
            if (!grid) continue;
            sticky = entry.ts;
            return { location: found, grid: grid, matched: true, prepareMs: prepareMs };
        }
        return { location: null, grid: null, matched: matched, prepareMs: prepareMs };
    }
    function releaseAnchors(value) {
        if (value) value.anchors.forEach(function (a) { a.image.recycle(); });
    }
    function anchorsMatch(image) {
        if (!memo || !memo.anchors.length) return false;
        const size = host.sizeOf(image);
        return memo.anchors.every(function (a) {
            if (a.x < 0 || a.y < 0 || a.x + a.width > size.width || a.y + a.height > size.height) return false;
            let crop = null;
            try {
                crop = host.clip(image, a.x, a.y, a.width, a.height);
                const result = host.matchTemplate(crop, a.image, { threshold: 0.97, max: 1 });
                return !!(result && result.matches && result.matches.length && result.matches[0].similarity >= 0.97);
            } finally { if (crop) crop.recycle(); }
        });
    }
    function remember(image, location, grid) {
        const anchors = [], sx = (location.topRight.x - location.topLeft.x) / calibration.width;
        const sy = (location.bottomLeft.y - location.topLeft.y) / calibration.height;
        try {
            (calibration.anchors || []).forEach(function (a) {
                const x = Math.round(location.topLeft.x + a[0] * sx), y = Math.round(location.topLeft.y + a[1] * sy);
                const width = Math.max(8, Math.round(a[2] * sx)), height = Math.max(8, Math.round(a[3] * sy));
                anchors.push({ x: x, y: y, width: width, height: height, image: host.clip(image, x, y, width, height) });
            });
        } catch (error) { releaseAnchors({ anchors: anchors }); throw error; }
        releaseAnchors(memo);
        memo = { location: location, grid: grid, anchors: anchors, age: 0 };
    }
    function locate(image, options) {
        if (closed) throw new Error("棋盘定位器已释放");
        const started = host.nowMono();
        const orientation = (options && options.orientation) || "RED_BOTTOM";
        const cacheHit = !!(memo && memo.age < 30 && anchorsMatch(image) &&
            memo.grid.orientation === orientation);
        let found = null, grid = null, matchedAny = true, prepareMs = 0;
        if (cacheHit) {
            found = memo.location; grid = memo.grid; memo.age++;
        } else {
            const hit = matchAny(image, orientation);
            found = hit.location; grid = hit.grid; matchedAny = hit.matched; prepareMs = hit.prepareMs;
        }
        candidate = grid ? { location: found, grid: grid, cacheHit: cacheHit, confirmed: false } : null;
        return { ok: !!grid, reason: grid ? "" : (matchedAny ? "unsupported-board-perspective" : "board-not-found"),
            grid: grid, location: found, geometryCacheHit: cacheHit,
            locateMs: host.nowMono() - started, templatePrepareMs: prepareMs };
    }
    return {
        locate: locate,
        // 只有完整字形盘面通过规则门后才能更新缓存参照，动画/失败帧不写入。
        confirm: function (image, grid) {
            if (!closed && candidate && !candidate.confirmed && sameGrid(candidate.grid, grid)) {
                if (!candidate.cacheHit || !memo) remember(image, candidate.location, grid);
                candidate.confirmed = true;
            }
        },
        check: function (image, grid) {
            if (!memo || !sameGrid(memo.grid, grid)) return { ok: true, tracked: false };
            const started = host.nowMono();
            const cacheHit = anchorsMatch(image);
            if (!cacheHit || ++memo.age >= 30) {
                // 棋子、选中光圈和落子标记会改变河界像素；参照失配只触发复查。
                candidate = null;
                const hit = matchAny(image, grid.orientation);
                if (!hit.grid) return { ok: false, tracked: true, geometryUncertain: true,
                    reason: "SIFT 暂未确认棋盘几何，等待稳定帧", ms: host.nowMono() - started };
                if (!sameGrid(grid, hit.grid)) return { ok: false, tracked: true, geometryChanged: true,
                    reason: "SIFT 复查发现棋盘位置变化，请重新定位", ms: host.nowMono() - started };
                // 完整盘面通过后才刷新参照，失败/动画帧不写入；继续使用已发布坐标。
                candidate = { location: hit.location, grid: grid, cacheHit: false, confirmed: false };
                return { ok: true, tracked: true, geometryCacheHit: false, siftRechecked: true,
                    anchorChanged: !cacheHit, ms: host.nowMono() - started };
            }
            return { ok: true, tracked: true, geometryCacheHit: true, ms: host.nowMono() - started };
        },
        close: function () {
            if (closed) return;
            closed = true;
            const keys = Object.keys(matchers);
            for (let i = 0; i < keys.length; i++) {
                try { matchers[keys[i]].matcher.recycle(); } catch (error) { /* 已回收 */ }
            }
            releaseAnchors(memo); memo = null; candidate = null;
        }
    };
}

module.exports = { create: create, gridFrom: gridFrom, sameGrid: sameGrid };
