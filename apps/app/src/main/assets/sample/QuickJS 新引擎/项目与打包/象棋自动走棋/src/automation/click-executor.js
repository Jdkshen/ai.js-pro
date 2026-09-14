// @engine quickjs
/**
 * 点击执行器（任务书 14）。
 *
 * 只做三件事：把格点（规范坐标）换成当前几何下的屏幕点、按住配置的时长点击、
 * 在起点与终点之间再查一次取消。**不做任何判定** —— 落子到底成没成由
 * move-verifier 用新观测判断，两者分开记（「点击函数返回成功」≠「棋子真的走了」）。
 *
 * 宿主接口通过 `tap` 注入：
 *   - 真机：aijs-adapter.createTap()（click/press）
 *   - 只识别 / 建议 / 离线自检：createDisabledTap(...)，**调用即计数并报错**，
 *     这样「不点击」是靠显式禁用保证的，不是靠「记得别调」。
 */
"use strict";

const geometry = require("../vision/geometry.js");
const defaults = require("../util/defaults.js");
const ticketModule = require("./move-ticket.js");

const DEFAULTS = {
    /** 按压时长（ms）：太短有些游戏不认，太长会被当成拖拽。 */
    pressMs: 60,
    /** 起点点完到点终点之间的等待（ms）：给选中动画留时间。 */
    settleMs: 140,
    /** 一张票据最多点几次（起点+终点 = 2；超出说明逻辑出错了，直接拒绝）。 */
    maxClicksPerTicket: 2,
    /** 允许的落点误差（格距为单位）：超过就不点（说明几何或坐标有问题）。 */
    pointTolerance: 0.35,
    /**
     * 采用「实测检测框中心」的最大容许偏移（格距为单位）：超过就不要那个中心，退回交点。
     * 用来挡住异常框（跨格、拖影）把点击带跑偏。
     */
    maxCenterOffset: 0.35
};

/**
 * 显式禁用的点击实现：任何一次调用都会被记下来并返回失败。
 * 只识别 / 建议 / 离线模式用它 —— 验收用例 A05 就是查这个计数为 0。
 */
function createDisabledTap(reason) {
    const state = { calls: 0, lastReason: "", points: [] };
    function tap(x, y) {
        state.calls += 1;
        state.lastReason = reason || "当前模式禁止点击";
        state.points.push({ x: x, y: y });
        return { ok: false, disabled: true, error: state.lastReason };
    }
    return {
        tap: tap,
        disabled: true,
        /** 点击调用次数（验收就断言这个） */
        calls: function () { return state.calls; },
        describe: function () {
            return "点击已禁用（" + (reason || "未启用自动模式") + "）：已拦截 " + state.calls + " 次调用";
        },
        reset: function () { state.calls = 0; state.points = []; }
    };
}

/**
 * @param options.tap        (x, y, {pressMs}) -> {ok, method, error} 或 boolean
 * @param options.nowMono    () -> 单调时间
 * @param options.waitMs     (ms) -> void（起点→终点之间的等待）
 */
function createExecutor(options) {
    const opts = options || {};
    const config = defaults.mergeDefaults(DEFAULTS, opts.config);
    const nowMono = opts.nowMono || function () { return Date.now(); };
    const waitMs = opts.waitMs || function () { };
    const tap = opts.tap;
    if (typeof tap !== "function") {
        throw new Error("点击执行器需要注入 tap 函数（真机用适配层的 createTap，禁用模式用 createDisabledTap）");
    }
    const stats = { tickets: 0, clicks: 0, ok: 0, failed: 0, cancelledBefore: 0, cancelledBetween: 0 };

    /**
     * 格点 → 屏幕坐标。
     *
     * `center`（可选）= 该格最近一次识别的**实测检测框中心**。有就用它，但要求：
     *   · 归一化偏移 ≤ maxCenterOffset（格距为单位）—— 挡住异常框；
     *   · nearest() 归到的正是同一个 (col,row) —— 否则说明几何与中心不自洽。
     *
     * 为什么要用它：定位给的原点/格距有**系统性偏差**（K40 + 天天象棋实测
     * rank0 差 10px、rank7 差 27px，误差随行号线性增长，真实 stepY ≈ 111.7 vs 定位 114.23）。
     * 识别只看「框归哪个交点」+ 0.84 格距的采样框，这点偏差不影响判读；
     * 但游戏的命中判定是「手指得落在棋子上」，按交点去点就是系统性偏移 →
     * 表现成「起点能选中、落点没反应」→ 确认阶段永远是 UNCHANGED。
     */
    function pointOf(grid, col, row, center) {
        const point = geometry.pointAt(grid, col, row);
        const snapped = geometry.nearest(grid, point.x, point.y);
        if (center && isFinite(center.x) && isFinite(center.y)) {
            const offset = geometry.normalizedResidual(grid, center.x, center.y);
            if (offset <= config.maxCenterOffset) {
                const hit = geometry.nearest(grid, center.x, center.y);
                if (hit.inside && hit.col === col && hit.row === row) {
                    return {
                        x: Math.round(center.x),
                        y: Math.round(center.y),
                        normalizedResidual: offset,
                        inside: true,
                        usedCenter: true,
                        latticeX: Math.round(point.x),
                        latticeY: Math.round(point.y)
                    };
                }
            }
        }
        return {
            x: Math.round(point.x),
            y: Math.round(point.y),
            normalizedResidual: snapped.normalized,
            inside: snapped.inside,
            usedCenter: false
        };
    }

    /** 取某格最近一次观测的实测框中心（没有就返回 null，让 pointOf 退回交点）。 */
    function centerAt(ctx, col, row) {
        const centers = ctx && ctx.centers;
        if (!centers || col == null || row == null) {
            return null;
        }
        const entry = centers[row * geometry.COLS + col];
        return (entry && isFinite(entry.x) && isFinite(entry.y)) ? entry : null;
    }

    function doTap(point, kind) {
        stats.clicks += 1;
        const started = nowMono();
        let result;
        try {
            result = tap(point.x, point.y, { pressMs: config.pressMs, kind: kind });
        } catch (error) {
            result = { ok: false, error: String(error && error.message ? error.message : error) };
        }
        const record = {
            kind: kind,
            x: point.x,
            y: point.y,
            usedCenter: !!point.usedCenter,
            ok: result === true || (result && result.ok === true),
            disabled: !!(result && result.disabled),
            method: result && result.method ? result.method : null,
            error: result && result.error ? result.error : "",
            atMono: started,
            elapsedMs: nowMono() - started
        };
        if (record.ok) {
            stats.ok += 1;
        } else {
            stats.failed += 1;
        }
        return record;
    }

    /**
     * 执行一张票据的起点/终点点击。
     *
     * @param ticket 已通过 move-ticket.canExecute 的票据
     * @param ctx.grid             当前几何（版本必须与票据一致）
     * @param ctx.cancel           () -> 是否已取消（暂停/停止/换局）
     * @returns { status, reason, clicks[], clickCount, cancelledBeforeStart, cancelledBetween }
     *          status: SENT（两次点击都已发出）/ CANCELLED / FAILED / REJECTED（条件不满足，一次都没点）
     */
    function execute(ticket, ctx) {
        const c = ctx || {};
        const grid = c.grid;
        const cancel = typeof c.cancel === "function" ? c.cancel : function () { return false; };
        const startedAtMono = nowMono();
        const outcome = {
            actionId: ticket ? ticket.actionId : null,
            moveICCS: ticket ? ticket.moveICCS : null,
            status: "REJECTED",
            reason: "",
            clicks: [],
            clickCount: 0,
            cancelledBeforeStart: false,
            cancelledBetween: false,
            startedAtMono: startedAtMono,
            finishedAtMono: startedAtMono
        };
        function finish(status, reason) {
            outcome.status = status;
            outcome.reason = reason;
            outcome.clickCount = outcome.clicks.length;
            outcome.finishedAtMono = nowMono();
            return outcome;
        }

        if (!ticket) {
            return finish("REJECTED", "没有票据");
        }
        if (!ticket.move || ticket.move.fromCol == null || ticket.move.toCol == null) {
            return finish("REJECTED", "票据里没有起终点的格点坐标（move.fromCol/fromRow/toCol/toRow）");
        }
        if (!grid) {
            return finish("REJECTED", "没有棋盘几何，无法把格点换成屏幕坐标");
        }
        if (grid.version !== ticket.geometryVersion) {
            return finish("REJECTED", "几何版本不一致（票据 " + ticket.geometryVersion +
                " / 当前 " + grid.version + "）");
        }
        if (cancel()) {
            outcome.cancelledBeforeStart = true;
            stats.cancelledBefore += 1;
            return finish("CANCELLED", "点击前已取消（暂停/停止/换局）");
        }

        const from = pointOf(grid, ticket.move.fromCol, ticket.move.fromRow, centerAt(c, ticket.move.fromCol, ticket.move.fromRow));
        const to = pointOf(grid, ticket.move.toCol, ticket.move.toRow, centerAt(c, ticket.move.toCol, ticket.move.toRow));
        if (!from.inside || !to.inside) {
            return finish("REJECTED", "起终点不在棋盘交点范围内（起 " + from.x + "," + from.y +
                " 终 " + to.x + "," + to.y + "）");
        }
        if (from.normalizedResidual > config.pointTolerance || to.normalizedResidual > config.pointTolerance) {
            return finish("REJECTED", "落点残差过大（" + from.normalizedResidual.toFixed(3) + " / " +
                to.normalizedResidual.toFixed(3) + "，阈值 " + config.pointTolerance + "），说明几何不可信");
        }
        // 几何对了也可能算到屏幕外（原点/格距异常）：屏幕外的点直接拒绝，别点"空气"
        if (c.screen && c.screen.width > 0 && c.screen.height > 0) {
            const outside = function (point) {
                return point.x < 0 || point.y < 0 ||
                    point.x > c.screen.width || point.y > c.screen.height;
            };
            if (outside(from) || outside(to)) {
                return finish("REJECTED", "落点超出屏幕（起 " + from.x + "," + from.y +
                    " 终 " + to.x + "," + to.y + " / 屏幕 " + c.screen.width + "×" + c.screen.height + "）");
            }
        }

        stats.tickets += 1;
        ticketModule.start(ticket, nowMono());

        const fromClick = doTap(from, "from");
        outcome.clicks.push(fromClick);
        if (!fromClick.ok) {
            return finish("FAILED", "起点点击失败：" + (fromClick.error || "接口返回失败"));
        }

        // 起点之后、终点之前再查一次取消（A04：起点点完立刻暂停 → 不许再点终点）
        if (config.settleMs > 0) {
            waitMs(config.settleMs);
        }
        if (cancel()) {
            outcome.cancelledBetween = true;
            stats.cancelledBetween += 1;
            return finish("CANCELLED", "起点已点、终点前的取消生效（暂停/停止/换局）—— 未发送终点点击");
        }
        if (outcome.clicks.length + 1 > config.maxClicksPerTicket) {
            return finish("FAILED", "超过单票最大点击数 " + config.maxClicksPerTicket);
        }

        const toClick = doTap(to, "to");
        outcome.clicks.push(toClick);
        if (!toClick.ok) {
            return finish("FAILED", "终点点击失败：" + (toClick.error || "接口返回失败"));
        }
        return finish("SENT", "起点与终点点击都已发出（是否真的落子由确认阶段判断）");
    }

    return {
        execute: execute,
        config: config,
        stats: function () {
            return {
                tickets: stats.tickets,
                clicks: stats.clicks,
                ok: stats.ok,
                failed: stats.failed,
                cancelledBefore: stats.cancelledBefore,
                cancelledBetween: stats.cancelledBetween
            };
        }
    };
}

module.exports = {
    DEFAULTS: DEFAULTS,
    createExecutor: createExecutor,
    createDisabledTap: createDisabledTap
};
