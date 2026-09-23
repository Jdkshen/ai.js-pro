// @engine quickjs
/**
 * 几何跟踪（重构方案 §3.3「棋盘几何与90交点」的运行策略）。
 *
 * 方案原文的四条：
 *   1. 第一次完整定位；
 *   2. 把 YOLO 棋子中心拟合到规则网格；
 *   3. 正常对局**复用几何**，不重复完整定位；
 *   4. 平均残差 > 0.22 格距 ⇒ 小范围微调；> 0.35 或连续两帧异常 ⇒ 重新定位；
 *   5. 几何一变就递增 `version`，旧点击票据立即失效。
 *
 * 本模块只做**决策**（要不要微调/要不要重定位/版本怎么变），真正的定位与微调由
 * board-locator / recognizer 提供；这样离线可测、也不会把两套拟合逻辑混在一起。
 */
"use strict";

const DEFAULTS = {
    /** 平均残差超过它 ⇒ 小范围微调（方案 §3.3：0.22） */
    microAdjustResidual: 0.22,
    /** 超过它 ⇒ 重新定位（方案 §3.3：0.35） */
    relocateResidual: 0.35,
    /** 连续多少帧异常 ⇒ 强制重新定位（方案 §3.3：连续两帧） */
    badFrameLimit: 2,
    /** 微调的最大位移（格距倍数）：超过就不是微调了 */
    microAdjustLimit: 0.25
};

/**
 * 用一个「交点 → 残差」的列表算平均残差。
 * @param residuals [{index, residual}]（residual 单位：格距）
 */
function meanResidual(residuals) {
    let sum = 0, n = 0;
    for (let i = 0; i < (residuals ? residuals.length : 0); i++) {
        const one = residuals[i];
        if (!one || !isFinite(one.residual)) {
            continue;
        }
        sum += Math.abs(one.residual);
        n += 1;
    }
    return n === 0 ? null : sum / n;
}

function create(grid, options) {
    const opts = Object.assign({}, DEFAULTS, options || {});
    let current = grid || null;
    let badFrames = 0;
    let checks = 0;
    let microAdjusts = 0;
    let relocates = 0;

    /**
     * 每帧调一次：拿这一帧的残差决定几何怎么办。
     * @return { action: "keep"|"micro"|"relocate", reason, meanResidual, version }
     */
    function review(residuals) {
        checks += 1;
        if (!current) {
            relocates += 1;
            return { action: "relocate", reason: "还没有几何", meanResidual: null, version: null };
        }
        const mean = meanResidual(residuals);
        if (mean == null) {
            badFrames += 1;
            if (badFrames >= opts.badFrameLimit) {
                relocates += 1;
                badFrames = 0;
                return {
                    action: "relocate", badFrames: true, meanResidual: null, version: current.version,
                    reason: "连续 " + opts.badFrameLimit + " 帧算不出残差"
                };
            }
            return { action: "keep", reason: "这一帧没有可用检测框", meanResidual: null, version: current.version };
        }
        if (mean > opts.relocateResidual) {
            relocates += 1;
            badFrames = 0;
            return {
                action: "relocate", meanResidual: mean, version: current.version,
                reason: "平均残差 " + mean.toFixed(3) + " > " + opts.relocateResidual
            };
        }
        if (mean > opts.microAdjustResidual) {
            microAdjusts += 1;
            badFrames = 0;
            return {
                action: "micro", meanResidual: mean, version: current.version,
                reason: "平均残差 " + mean.toFixed(3) + " > " + opts.microAdjustResidual + " ⇒ 小范围微调"
            };
        }
        badFrames = 0;
        return { action: "keep", meanResidual: mean, version: current.version, reason: "" };
    }

    /**
     * 换几何（微调完 / 重定位完）：**版本必须递增**，否则旧点击票据会带着旧几何去点（方案 §3.3 第 5 条）。
     * @return 新的几何对象
     */
    function update(nextGrid, how) {
        if (!nextGrid) {
            return current;
        }
        const version = current && current.version != null ? Number(current.version) + 1 : 1;
        current = Object.assign({}, nextGrid, {
            version: version,
            updatedAt: Date.now(),
            updateReason: how || ""
        });
        return current;
    }

    function current_() {
        return current;
    }

    function stats() {
        return { checks: checks, microAdjusts: microAdjusts, relocates: relocates, version: current ? current.version : null };
    }

    return {
        config: opts,
        review: review,
        update: update,
        grid: current_,
        stats: stats
    };
}

module.exports = {
    create: create,
    meanResidual: meanResidual,
    DEFAULTS: DEFAULTS
};
