// @engine quickjs
"use strict";

/** grid-check：每个实例独立持有状态；跨模块动作由装配入口注入。
 * 状态通过参数显式共享；跨模块调用通过动作表，模块间不循环 require。
 */

function create(panelState, panelActions) {
    function grabFrameRaw() {
        // 截图前暂避宿主悬浮球（避免球压住棋盘格子/被读成棋子）：
        // ⚠ 2026-10-04 起默认关（用户要求「不用隐藏悬浮球」）；autorun "suppressAppBall": true 可开。
        const suppressBall = panelState.suppressAppBall === true;
        if (suppressBall && !panelState.ownMenuLease) {
            panelState.ownMenuLease = require("../../runtime/own-overlay.js").createHost(panelActions.logLine);
            if (typeof events !== "undefined") events.on("exit", function () { panelState.ownMenuLease.release(); });
        }
        const preference = panelState.adapter.captureBackend();
        if (suppressBall && preference === "window") panelState.ownMenuLease.release();
        if (preference !== "shell" && !panelState.capturePermissionAttempted) {
            panelState.capturePermissionAttempted = true;
            try {
                if (!panelState.adapter.requestCapture("portrait") && (preference === "screen" || preference === "window")) {
                    panelState.renderCaptureFailed = true;
                    if (typeof panelActions.clearOverlay === "function") panelActions.clearOverlay("截图能力不可用");
                    panelActions.logLine("系统截屏授权未通过，当前是仅系统截屏模式");
                    return null;
                }
            } catch (error) {
                panelActions.logLine("系统截屏授权失败：" + error);
                if (preference === "screen" || preference === "window") {
                    panelState.renderCaptureFailed = true;
                    if (typeof panelActions.clearOverlay === "function") panelActions.clearOverlay("截图能力不可用");
                    return null;
                }
            }
        }
        try {
            if (suppressBall && preference !== "window" && panelState.ownMenuLease.acquire()) {
                panelActions.logLine("截图暂避应用悬浮球，避免遮住棋盘格子");
                sleep(90);
            }
            const frame = panelState.adapter.capture("full", 2000);
            panelState.renderCaptureFailed = !frame;
            return frame ? { image: frame, path: null, channel: panelState.adapter.captureChannel(),
                captureMetadata:preference === "window" && typeof panelState.adapter.windowCaptureInfo === "function"
                    ? panelState.adapter.windowCaptureInfo() : null } : null;
        } catch (error) {
            panelState.renderCaptureFailed = true;
            if (typeof panelActions.clearOverlay === "function") panelActions.clearOverlay("截图失败，旧标记已清除");
            panelActions.logLine("截图失败：" + preference + "）：" + error);
            return null;
        } finally {
            if (suppressBall && !panelState.running) panelState.ownMenuLease.release();
        }
    }

    /**
     * 几何体检：拿检测框落在第几行来验一下「定位给出来的网格到底对不对」。
     * ⚠️ 2026-09-20 真机：线定位**成功**也可能是整行偏（origin.y=590 vs 真实 ~690），
     * 而且精校准照样报「未知格 0」—— 偏移接近一整格时 90 个交点全落在格子上，只是全错一行。
     * 那种几何会让「底线跑到第 1 行、真底线掉到网格外」⇒ 基准/票据/轮次全对不上。
     *
     * @return { ok, reason, rows } —— 检测不可用时一律放行（不能因为体检本身把链路卡死）
     */
    function checkGridRows(image, candidate) {
        if (!image || !candidate) {
            return { ok: true, reason: "没有帧或候选几何，跳过体检" };
        }
        if (!panelState.visionDetectorSession) {
            try {
                panelState.visionDetectorSession = panelState.recognizeBoard.createDetectionSession({
                    autoAlign: false
                });
            } catch (error) {
                return { ok: true, reason: "检测会话不可用，跳过体检" };
            }
        }
        let boxes = [];
        try {
            const detected = panelState.visionDetectorSession.detect(image, candidate, {});
            boxes = panelState.pieceDetector.normalizeBoxes((detected && detected.raw) || []);
        } catch (error) {
            return { ok: true, reason: "检测异常，跳过体检" };
        }
        if (!boxes.length) {
            return { ok: true, reason: "没检出任何框，跳过体检" };
        }
        const mapped = panelState.pieceDetector.mapToCells(boxes, candidate, { autoAlign: false });
        const verdict = panelState.gridSanity.suspect(panelState.gridSanity.rowsOf((mapped && mapped.cells) || []));
        // ★ 把「落在网格内的框数」也带出去：平移救回要靠它比好坏（框数变多才说明平移救对了方向）
        verdict.pieces = mapped && mapped.cells ? mapped.cells.length : 0;
        return verdict;
    }

    /**
     * 给几何补上 `quality`：状态机的「几何可信」判定要它（`geometry.isTrustworthy`），
     * 而 `pickGrid` / `calibrateGrid` 出来的 grid 不带这个字段。
     * ⚠️ 真机 2026-09-20：校准明明报「未知格 0」，状态机却是 `RECOVERING｜几何不可信：`（连原因都没有）
     * —— 因为 `quality` 缺字段 ⇒ `isTrustworthy` 直接 false。
     * 残差用**检测框到交点**的归一化距离（与 piece-detector.mapToCells 同一把尺）。
     */
    function attachGridQuality(image, candidate) {
        if (!candidate || (candidate.quality && candidate.quality.inlierCount)) {
            return candidate;
        }
        if (!image) {
            return candidate;
        }
        try {
            if (!panelState.visionDetectorSession) {
                panelState.visionDetectorSession = panelState.recognizeBoard.createDetectionSession({
                    autoAlign: false
                });
            }
            const detected = panelState.visionDetectorSession.detect(image, candidate, {});
            const boxes = panelState.pieceDetector.normalizeBoxes((detected && detected.raw) || []);
            const mapped = panelState.pieceDetector.mapToCells(boxes, candidate, { autoAlign: false });
            const cells = (mapped && mapped.cells) || [];
            /**
             * 几何质量：残差取**鲁棒最大值**（丢最差的 10%），单个跑偏的检测框不该把整块几何否掉。
             * ⚠️ 真机 2026-09-20：pickGrid+校准落下来的网格与交点是固定偏 ~⅓ 格的
             *    （实测样本 24、mean≈0.335、max≈0.369），而几何可信阀值是 0.35 ⇒ 永远「几何不可信」。
             *    而颜色/点击都是锚在**检测框**上的（不是交点）—— 偏零点几格不影响判色与落点；
             *    真正致命的「整行/整列偏」已经由 `grid-sanity` 的极端行体检拦住了。
             */
            const residuals = [];
            for (let i = 0; i < cells.length; i++) {
                residuals.push(Number(cells[i].residual) || 0);
            }
            residuals.sort(function (a, b) {
                return a - b;
            });
            const keep = Math.max(1, Math.ceil(residuals.length * 0.9));
            const inliers = residuals.slice(0, keep);
            let sum = 0;
            for (let i = 0; i < inliers.length; i++) {
                sum += inliers[i];
            }
            candidate.quality = {
                sampleCount: cells.length,
                inlierCount: inliers.length,
                maxResidual: inliers.length ? inliers[inliers.length - 1] : 0,
                meanResidual: inliers.length ? sum / inliers.length : 0
            };
            panelActions.logLine(
                "几何质量：样本 " +
                    cells.length +
                    "｜鲁棒最大残差 " +
                    candidate.quality.maxResidual.toFixed(3) +
                    "（丢最差 10%；状态机的「几何可信」看它）"
            );
        } catch (error) {
            panelActions.logLine("几何质量计算失败（照旧放行）：" + error);
        }
        return candidate;
    }

    function gridCondemned() {
        return panelState.gridCondemnAt > 0 && Date.now() - panelState.gridCondemnAt < panelState.GRID_CONDEMN_MS;
    }

    /**
     * 数一下这帧读出几个「帅/将」，供几何候选检查使用。
     * 真机 2026-09-24 11:50（用户报「又识别不到了」）：中途一次误触发的全量重校准换上了偏移(25,22)
     * 的坏网格 —— 之后每帧都是「红帅数量 0；黑将数量 0」，而**未知格看起来并不多**
     * （错网格上大部分格子读成空、不算未知）⇒ 旧逻辑照样「沿用」⇒ 永远读不出局面。
     * 帅/将通常稳定，但选中高亮或动画会短暂遮字；不能凭单帧漏读重置几何。
     */
    function countKings(chars) {
        if (!chars || !chars.length) {
            return 0;
        }
        let n = 0;
        for (let i = 0; i < chars.length; i++) {
            const c = chars[i];
            if (c === "K" || c === "k") {
                n += 1;
            }
        }
        return n;
    }

    /** 用给定几何读一帧的 chars（失败返回 null）。校准/沿用检查用（不落地就能把坏几何否掉）。 */
    function readCharsWithGrid(image, g) {
        try {
            const r = panelState.glyphSource.recognize(image, g, {});
            return r && r.ok && r.chars ? r.chars : null;
        } catch (error) {
            return null;
        }
    }

    function findAlignedGrid(image, base, circles) {
        if (!base || !base.stepX || !base.stepY) {
            return null;
        }
        // ★ 旧几何被证伪（见 gridCondemnAt 的说明）：跳过连续性护栏 —— 不然「旧几何偏 1 格 +
        //   候选偏得远」会把**唯一正确的那份**否掉，死锁到永远（真机 16:16 铁证）。
        const reference =
            !gridCondemned() &&
            panelState.grid &&
            Math.abs(base.stepX / panelState.grid.stepX - 1) < 0.12 &&
            Math.abs(base.stepY / panelState.grid.stepY - 1) < 0.12
                ? panelState.grid
                : null;
        if (reference === null && panelState.grid && gridCondemned()) {
            panelActions.logLine(
                "旧几何已被判可疑（连续读不到帅/将）：锚定检查跳过 0.45 格连续性护栏，允许换到偏移更远的候选"
            );
        }
        let nearest = null;
        let nearestDistance = Infinity;
        let rejectedByContinuity = 0;
        let rejectedByCircles = 0;
        for (let i = 0; i < panelState.ANCHOR_SHIFTS.length; i++) {
            panelActions.checkLocateBudget();
            const sx = panelState.ANCHOR_SHIFTS[i][0] * base.stepX;
            const sy = panelState.ANCHOR_SHIFTS[i][1] * base.stepY;
            let g = base;
            if (sx !== 0 || sy !== 0) {
                g = panelState.geometryModule.createGrid({
                    originX: base.originX + sx,
                    originY: base.originY + sy,
                    stepX: base.stepX,
                    stepY: base.stepY,
                    orientation: base.orientation,
                    version: base.version == null ? 1 : base.version
                });
            }
            const circleVerdict = panelState.circleGridCheck.evaluate(circles, g);
            if (circleVerdict.conclusive && !circleVerdict.ok) {
                rejectedByCircles += 1;
                continue;
            }
            if (countKings(readCharsWithGrid(image, g)) === 2) {
                if (!reference) {
                    return { grid: g, shift: panelState.ANCHOR_SHIFTS[i] };
                }
                const dx = Math.abs(g.originX - reference.originX) / reference.stepX;
                const dy = Math.abs(g.originY - reference.originY) / reference.stepY;
                if (dx > 0.45 || dy > 0.45) {
                    rejectedByContinuity += 1;
                    continue;
                }
                if (dx + dy < nearestDistance) {
                    nearestDistance = dx + dy;
                    nearest = { grid: g, shift: panelState.ANCHOR_SHIFTS[i] };
                }
            }
        }
        if (!nearest && rejectedByContinuity > 0) {
            panelActions.logLine(
                "锚定候选虽读出帅/将，但偏离旧几何超过 0.45 格（" + rejectedByContinuity + " 个）→ 不采用，等稳定帧"
            );
        }
        if (!nearest && rejectedByCircles > 0) {
            panelActions.logLine("圆心几何校验拒绝 " + rejectedByCircles + " 个候选（棋子圆心偏离交点），保留旧几何");
        }
        return nearest;
    }

    /**
     * pickGrid 兜底：用**检测框**拟合一个候选几何（框就压在棋子上，锚定证据比稀疏线强）。
     * 返回 { grid } 或 null；失败只记日志不抛。
     */
    function pickGridCandidate(grabbed) {
        if (!grabbed || !grabbed.image) {
            return null;
        }
        try {
            const picked = panelState.recognizeBoard.pickGrid(grabbed.image, {
                orientation: "RED_BOTTOM",
                confidence: 0.001,
                radius: 18,
                step: 4,
                radiusRatio: 0.5,
                probeCropMargin: 0.9
            });
            if (!picked || !picked.ok) {
                panelActions.logLine("pickGrid 兜底失败：" + ((picked && picked.reason) || "无结果"));
                return null;
            }
            return picked.grid;
        } catch (error) {
            panelActions.logLine("pickGrid 兜底异常：" + error);
            return null;
        }
    }

    /**
     * 把几何的「交点」钉到**棋子视觉中心**上。
     *
     * 为什么需要（2026-09-21 深夜真机 now55，差分流一整晚的错格根因）：
     *   · `calibrateGrid` 是**拿 OCR 结果当代价**（未知格/丢子/修复）扫偏移选优的，可能停在
     *     一个「读着差不多、其实压着棋子下缘」的偏移上：真机实测起点 (88,730)、同帧检测框质心
     *     在 (95,705) —— 交点在棋子中心下方 ~25px；
     *   · 而逐格裁框（`cellRegionFor`：0.82×格再取中心 0.70，±32px）是**以交点为心**的 ⇒
     *     只切到字的下半截：真机稳定重现 車→卒、馬→兵/炮、象→炮、士→空，差分只读变化格 ⇒
     *     错值永不重读；驱动拿证据改正、下一帧又被喂回来，确认永远 UNRELIABLE；
     *   · 判色/落点/证据读数都是锚在**检测框**上的（所以证据一直读得对）——把网格钉到框质心，
     *     「面板读的」和「驱动读的」就同基准了（裁框几何与 board-scan 的 cropBoxFor+centreRegion 重合）。
     * 幂等：钉过（`pieceOffset` 已记）不再量；位移 < 6px 视为已钉牢只记标记。
     * 安全性：样本 ≥ 8 框（残局棋子少，2026-09-21 从 12 放宽；但 < 12 个框时散布门槛收紧到 ≤ 12px）、
     * 偏移散布残差 ≤ 18px、总位移 ≤ 90px 才动手；任何一步不满足都原样返回。
     *
     * @return 新网格（没量到 / 不用动 / 不敢动时原样返回）
     */
    function snapGridToPieces(candidate, image, why) {
        if (!candidate || !image) {
            return candidate;
        }
        if (candidate.pieceOffset) {
            return candidate; // 同一个几何只钉一次
        }
        if (!panelState.visionDetectorSession) {
            try {
                panelState.visionDetectorSession = panelState.recognizeBoard.createDetectionSession({
                    autoAlign: false
                });
            } catch (error) {
                return candidate;
            }
        }
        let cells = [];
        try {
            const detected = panelState.visionDetectorSession.detect(image, candidate, {});
            const boxes = panelState.pieceDetector.normalizeBoxes((detected && detected.raw) || []);
            const mapped = panelState.pieceDetector.mapToCells(boxes, candidate, { autoAlign: false });
            cells = (mapped && mapped.cells) || [];
        } catch (error) {
            panelActions.logLine("几何钉棋子（" + why + "）：测量失败（" + error + "），保持原几何");
            return candidate;
        }
        const flip = candidate.orientation === "RED_TOP";
        const dxs = [];
        const dys = [];
        for (let i = 0; i < cells.length; i++) {
            const one = cells[i];
            const box = one && one.box;
            if (!box || one.residual == null || one.residual > 0.34) {
                continue; // 离交点太远的框不参与（多半是误检）
            }
            if (one.score != null && one.score < 0.4) {
                continue;
            }
            const col = flip ? 8 - one.col : one.col;
            const row = flip ? 9 - one.row : one.row;
            const bx = box.centerX == null ? box.x + box.width / 2 : box.centerX;
            const by = box.centerY == null ? box.y + box.height / 2 : box.centerY;
            dxs.push(bx - (candidate.originX + col * candidate.stepX));
            dys.push(by - (candidate.originY + row * candidate.stepY));
        }
        if (dxs.length < 8) {
            panelActions.logLine("几何钉棋子（" + why + "）：只量到 " + dxs.length + " 个可用框，不钉");
            return candidate;
        }
        function midOf(list) {
            const sorted = list.slice().sort(function (a, b) {
                return a - b;
            });
            return sorted[Math.floor(sorted.length / 2)];
        }
        const dx = midOf(dxs);
        const dy = midOf(dys);
        const devX = midOf(
            dxs.map(function (v) {
                return Math.abs(v - dx);
            })
        );
        const devY = midOf(
            dys.map(function (v) {
                return Math.abs(v - dy);
            })
        );
        // 残局（可用框 < 12）时收紧散布门槛：小样本对异常值更敏感
        const maxDev = dxs.length >= 12 ? 18 : 12;
        if (devX > maxDev || devY > maxDev) {
            panelActions.logLine(
                "几何钉棋子（" +
                    why +
                    "）：框偏移太散（±" +
                    Math.round(devX) +
                    "/" +
                    Math.round(devY) +
                    "px），疑似一堆误检，不钉"
            );
            return candidate;
        }
        if (Math.abs(dx) < 6 && Math.abs(dy) < 6) {
            candidate.pieceOffset = { dx: Math.round(dx), dy: Math.round(dy), kept: true };
            return candidate; // 已经钉牢
        }
        if (Math.abs(dx) + Math.abs(dy) > 90) {
            panelActions.logLine(
                "几何钉棋子（" +
                    why +
                    "）：偏移 (" +
                    Math.round(dx) +
                    "," +
                    Math.round(dy) +
                    ") 合计超 90px，太大不敢动网格（交给整行体检/重定位处理）"
            );
            return candidate;
        }
        const shifted = panelState.geometryModule.createGrid({
            originX: candidate.originX + Math.round(dx),
            originY: candidate.originY + Math.round(dy),
            stepX: candidate.stepX,
            stepY: candidate.stepY,
            orientation: candidate.orientation,
            version: (candidate.version == null ? 1 : candidate.version) + 1
        });
        shifted.pieceOffset = { dx: Math.round(dx), dy: Math.round(dy) };
        shifted.snappedFrom = candidate.version;
        panelActions.logLine(
            "几何钉棋子（" +
                why +
                "）：按 " +
                dxs.length +
                " 个检测框质心平移 (" +
                Math.round(dx) +
                "," +
                Math.round(dy) +
                ")px（原交点压着棋子下缘 ⇒ 逐格 OCR 只切半截字）｜几何 v" +
                shifted.version +
                "｜起点 (" +
                shifted.originX +
                "," +
                shifted.originY +
                ")"
        );
        return shifted;
    }

    function probeGameOver(image, forceText, inspectHiddenFooter) {
        if (probeGameOverFrame(image, forceText)) return true;
        if (!inspectHiddenFooter || panelState.clickLeaseActive ||
            !panelActions.needHideForCapture(true)) return false;
        const now = Date.now();
        if (panelState.lastCleanGameOverProbeAt && now - panelState.lastCleanGameOverProbeAt < 3000) return false;
        panelState.lastCleanGameOverProbeAt = now;
        const visibility = panelActions.beginCaptureVisibility({ includeFooter:true, reason:"结算页复核" });
        try {
            if (!visibility.hidden) return false;
            sleep(90);
            // 连续两帧共用一次藏窗；这些临时帧不接管 recentFrame，调用方仍持有原识别帧。
            for (let i = 0; i < 2; i++) {
                let frame = null;
                try {
                    frame = panelActions.grabFrameRaw();
                    if (probeGameOverFrame(frame && frame.image, true)) return true;
                } finally {
                    if (frame && frame.image) panelState.adapter.releaseFrame(frame.image);
                }
                if (i === 0) sleep(130);
            }
            return false;
        } finally {
            visibility.release();
        }
    }

    function probeGameOverFrame(image, forceText) {
        if (panelState.gameOverSeen) return true;
        if (!image) {
            return false;
        }
        panelState.gameOverProbeTick += 1;
        if (!panelState.gameOverTemplateUnavailable) {
            try {
                if (!panelState.gameOverDetector) {
                    panelState.gameOverDetector = require("../../vision/game-over-detector.js").create({
                        images: images, host: panelState.adapter, baseDir: panelState.BASE_DIR
                    });
                    if (typeof events !== "undefined") events.on("exit", function () { panelState.gameOverDetector.close(); });
                }
                const ended = panelState.gameOverDetector.detect(image);
                panelState.gameOverCandidate = panelState.gameOverDetector.candidate();
                if (ended) {
                    panelState.gameOverSeen = true;
                    panelActions.logLine("检测到对局结算页（分享＋复盘分析按钮连续两帧匹配）→ 自动停止，不再重定位成绩页");
                    return true;
                }
            } catch (error) {
                if (panelState.gameOverDetector) panelState.gameOverDetector.close();
                panelState.gameOverTemplateUnavailable = true;
                panelActions.logLine("结算页模板检测不可用：" + error);
            }
        }
        // 原文字探针只限频运行，失败帧上的模板检测不需要 OCR 会话。
        if (!forceText && panelState.gameOverProbeTick % panelState.GAME_OVER_PROBE_EVERY !== 0) return false;
        const size = panelState.adapter.screenSize();
        if (!size || !(size.height > 0)) {
            return false;
        }
        const session = panelActions.ocrRuntime();
        if (!session) {
            return false;
        }
        let blocks = [];
        try {
            blocks =
                session.detect(image, {
                    max: 8,
                    region: [0, Math.round(size.height * 0.9), size.width, Math.round(size.height * 0.095)]
                }) || [];
        } catch (error) {
            return false;
        }
        let hit = null;
        for (let i = 0; i < blocks.length && !hit; i++) {
            const text = String((blocks[i] && blocks[i].text) || "");
            for (let k = 0; k < panelState.GAME_OVER_WORDS.length; k++) {
                if (text.indexOf(panelState.GAME_OVER_WORDS[k]) >= 0) {
                    hit = text;
                    break;
                }
            }
        }
        if (!hit) {
            return false;
        }
        panelState.gameOverSeen = true;
        panelActions.logLine(
            "检测到对局结算页（读到「" +
                hit +
                "」）→ 本轮 tick 结束后自动停：结算页棋盘被下移，继续追踪只会对着垃圾空转"
        );
        return true;
    }

    return {
        grabFrameRaw: grabFrameRaw,
        checkGridRows: checkGridRows,
        attachGridQuality: attachGridQuality,
        gridCondemned: gridCondemned,
        countKings: countKings,
        readCharsWithGrid: readCharsWithGrid,
        findAlignedGrid: findAlignedGrid,
        pickGridCandidate: pickGridCandidate,
        snapGridToPieces: snapGridToPieces,
        probeGameOver: probeGameOver
    };
}

module.exports = { create: create };
