// @engine quickjs
"use strict";

/** probes：每个实例独立持有状态；跨模块动作由装配入口注入。
 * 状态通过参数显式共享；跨模块调用通过动作表，模块间不循环 require。
 */

function create(panelState, panelActions) {
    function probePng(name) {
        return panelState.FRAME_DIR + "/tap-probe-" + name + ".png";
    }

    /** 采样方框里「与参考帧不同」的像素数（稀疏采样，够用且快）。 */
    function diffBox(refFrame, frame, box, step) {
        const s = step == null ? 4 : step;
        let changed = 0;
        let total = 0;
        for (let y = box.y1; y <= box.y2; y += s) {
            for (let x = box.x1; x <= box.x2; x += s) {
                const a = panelState.adapter.rgbAt(refFrame, x, y);
                const b = panelState.adapter.rgbAt(frame, x, y);
                total += 1;
                if (!a || !b) {
                    continue;
                }
                if (Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]) + Math.abs(a[2] - b[2]) > 60) {
                    changed += 1;
                }
            }
        }
        return { changed: changed, total: total };
    }

    /** 以某个格点为中心、边长 2*margin 的方框（点的位置 = 实际发送的坐标）。 */
    function boxAround(x, y, margin) {
        const m = margin == null ? 46 : margin;
        return { x1: Math.round(x - m), y1: Math.round(y - m), x2: Math.round(x + m), y2: Math.round(y + m) };
    }

    /** 计步器区域（K40：≈660~900 × 2050~2160）——「这手到底落没落子」的真值判据之一。 */
    function stepBoxNow() {
        const screen = panelState.adapter.screenSize ? panelState.adapter.screenSize() : null;
        if (screen && screen.height > 0) {
            const y2 = Math.round(screen.height * 0.9);
            const y1 = Math.round(screen.height * 0.854);
            return { x1: Math.round(screen.width * 0.61), y1: y1, x2: Math.round(screen.width * 0.83), y2: y2 };
        }
        return { x1: 660, y1: 2050, x2: 900, y2: 2160 };
    }

    /**
     * 单手探针：只发**一手**（起点一格 + 落点一格）的两次手势，不做自动搜索、不做后续重试。
     *
     * autorun 用法：
     *   "tapProbe": { "fromCol": 1, "fromRow": 7, "toCol": 1, "toRow": 0,
     *                 "channel": "auto", "pressMs": 350, "gapMs": 600, "postMs": 1500, "label": "capture-b2x" }
     *
     * · 起点 tap 之后先看「棋面有没有反应」（选中高亮/提示点），**没反应就终止**，不发落点手势；
     * · 全程把面板窗口藏起来（保留宿主与服务：Shizuku / 无障碍都还要用），finally 恢复；
     * · 每个环节都落盘 PNG + 结论 JSON，坐标可离线叠十字核对（见 report §10.2）。
     */
    function runTapProbe(config) {
        const cfg = config || {};
        const label = cfg.label ? String(cfg.label) : "probe";
        const channel = cfg.channel ? String(cfg.channel) : panelState.clickMethodKey;
        const pressMs = cfg.pressMs != null ? Math.round(Number(cfg.pressMs)) : panelState.clickPressMs;
        const gapMs = cfg.gapMs != null ? Math.round(Number(cfg.gapMs)) : Math.max(300, pressMs + 250);
        const postMs = cfg.postMs != null ? Math.round(Number(cfg.postMs)) : 1500;
        const report = {
            label: label,
            buildId: panelState.PROBE_BUILD_ID,
            at: new Date().toISOString(),
            requestedChannel: channel,
            pressMs: pressMs,
            gapMs: gapMs,
            postMs: postMs,
            gamePackage: panelState.gamePackage,
            steps: [],
            verdict: "未完成"
        };

        // 前台守卫：注入的点击必须落在游戏上（自家透明宿主在前台时点击会被自己吃掉）
        const guard = panelActions.foregroundGuard();
        report.foregroundAfterGuard = guard ? guard.package || "" : "";
        report.foregroundGuardOk = !!(guard && guard.ok);
        if (!report.foregroundGuardOk) {
            report.verdict = "前台不是游戏，未发手势";
            panelActions.logLine(
                "探针 " + label + "：" + report.verdict + "（当前 " + (report.foregroundAfterGuard || "未知") + "）"
            );
            saveProbeReport(report);
            return report;
        }

        let hidden = false;
        try {
            // 整轮藏面板（不是"抓帧才藏"）：两次手势期间棋盘上不能有我们自己的窗口
            hidden = panelActions.setActivePanelVisibility(false, "手势探针") !== false;
            if (hidden) {
                sleep(140);
            }
            const grid = ensureProbeGrid();
            if (!grid) {
                report.verdict = "定位失败，未发手势";
                panelActions.logLine("探针 " + label + "：" + report.verdict);
                saveProbeReport(report);
                return report;
            }
            // 坐标来源可选："lattice"（网格交点 = 棋子圆心）/ "center"（检测框实测圆心）。
            // 实验目的：区分「游戏命中判定偏严（必须打实测圆心）」与「坐标无关」。
            const needCenters = cfg.fromSource === "center" || cfg.toSource === "center";
            const recognized = needCenters ? probeRecognize(grid) : null;
            const centers = recognized && recognized.centers ? recognized.centers : null;
            report.centersAvailable = !!centers;
            const from = probePoint(grid, cfg.fromCol, cfg.fromRow, cfg.fromSource, centers);
            const to = probePoint(grid, cfg.toCol, cfg.toRow, cfg.toSource, centers);
            report.geometryVersion = grid.version;
            report.geometry = { originX: grid.originX, originY: grid.originY, stepX: grid.stepX, stepY: grid.stepY };
            report.from = Object.assign({ col: cfg.fromCol, row: cfg.fromRow }, from);
            report.to = Object.assign({ col: cfg.toCol, row: cfg.toRow }, to);

            let tapper;
            try {
                tapper = panelState.adapter.createTap({ pressMs: pressMs, method: channel });
            } catch (error) {
                report.verdict = "通道不可用：" + error;
                panelActions.logLine("探针 " + label + "：" + report.verdict);
                saveProbeReport(report);
                return report;
            }
            report.actualChannel = tapper.method;

            const before = panelActions.grabFrameRaw();
            if (before) {
                images.save(before.image, probePng(label + "-before"));
                // 帧尺寸 / 屏幕尺寸 / 换算比例（§10.2）：识别坐标是帧上的，点击坐标是屏幕上的，
                // 两者不等比时（缩放/旋转）必须能从日志里看出来。
                try {
                    const info = panelState.adapter.sizeOf(before.image);
                    const screen = panelState.adapter.screenSize ? panelState.adapter.screenSize() : null;
                    report.frame = {
                        width: info ? info.width : null,
                        height: info ? info.height : null,
                        screenWidth: screen ? screen.width : null,
                        screenHeight: screen ? screen.height : null,
                        scaleX: info && screen && info.width ? screen.width / info.width : null,
                        scaleY: info && screen && info.height ? screen.height / info.height : null
                    };
                    panelActions.logLine(
                        "探针帧：" +
                            (info ? info.width + "×" + info.height : "?") +
                            "，屏幕 " +
                            (screen ? screen.width + "×" + screen.height : "?") +
                            "，换算 " +
                            (report.frame.scaleX ? report.frame.scaleX.toFixed(3) : "?") +
                            "×" +
                            (report.frame.scaleY ? report.frame.scaleY.toFixed(3) : "?")
                    );
                } catch (error) {
                    panelActions.logLine("探针帧尺寸读取失败：" + error);
                }
            }
            // 开局校验（§10.2）：32 子 + 与标准开局一致，否则本轮作废（除非调用方显式关掉）。
            if (cfg.expectOpening !== false) {
                const check = probeCheckOpening(grid);
                report.openingCheck = check;
                if (!check.ok) {
                    report.verdict = "开局校验不通过，未发手势：" + check.reason;
                    panelActions.logLine("探针 " + label + "：" + report.verdict);
                    saveProbeReport(report);
                    return report;
                }
            }
            panelActions.logLine(
                "探针 " +
                    label +
                    "：请求通道=" +
                    channel +
                    " 实际=" +
                    tapper.method +
                    " 按压=" +
                    pressMs +
                    "ms｜起点格(" +
                    cfg.fromCol +
                    "," +
                    cfg.fromRow +
                    ")→(" +
                    from.x +
                    "," +
                    from.y +
                    ")｜" +
                    "落点格(" +
                    cfg.toCol +
                    "," +
                    cfg.toRow +
                    ")→(" +
                    to.x +
                    "," +
                    to.y +
                    ")｜几何v" +
                    grid.version
            );

            // ① 起点手势
            report.focusBeforeFrom = panelState.adapter.focusedWindowPackage
                ? panelState.adapter.focusedWindowPackage()
                : null;
            report.windowsBeforeFrom = panelState.adapter.windowsAt ? panelState.adapter.windowsAt(from.x, from.y) : [];
            panelActions.logLine(
                "探针出手前：焦点=" +
                    (report.focusBeforeFrom || "读不到") +
                    "，覆盖该点的窗口 " +
                    (report.windowsBeforeFrom.length ? JSON.stringify(report.windowsBeforeFrom) : "（未查到）")
            );
            const r1 = tapper.tap(from.x, from.y, { pressMs: pressMs });
            report.steps.push({
                step: "tap-from",
                x: from.x,
                y: from.y,
                ok: !!r1.ok,
                method: r1.method,
                command: r1.command || null,
                error: r1.error || "",
                startedAtMono: r1.startedAtMono,
                finishedAtMono: r1.finishedAtMono
            });
            panelActions.logLine(
                "探针 手势1：ok=" +
                    (r1.ok ? "是" : "否") +
                    " 手势=" +
                    r1.method +
                    (r1.command ? " 命令「" + r1.command + "」" : "") +
                    (r1.error ? " 错误：" + r1.error : "")
            );

            sleep(gapMs);
            const selected = panelActions.grabFrameRaw();
            if (selected) {
                images.save(selected.image, probePng(label + "-selected"));
            }
            const selDiff =
                before && selected ? diffBox(before.image, selected.image, boxAround(from.x, from.y)) : null;
            report.selectionDiff = selDiff;
            const selectedOk = !!(selDiff && selDiff.changed >= 12); // 选中会有高亮/提示，明显超过噪声
            report.selected = selectedOk;
            panelActions.logLine(
                "探针 选中检查：" +
                    (selectedOk ? "有反应" : "**没反应**") +
                    (selDiff ? "（该格变化 " + selDiff.changed + "/" + selDiff.total + " 采样点）" : "（没有帧）")
            );
            if (!selectedOk) {
                report.verdict = "起点未选中，按规则终止（不发落点手势）";
                panelActions.logLine("探针 " + label + "：" + report.verdict);
                saveProbeReport(report);
                return report;
            }

            // ② 落点手势（必须等第一次手势完成后再发）
            const r2 = tapper.tap(to.x, to.y, { pressMs: pressMs });
            report.steps.push({
                step: "tap-to",
                x: to.x,
                y: to.y,
                ok: !!r2.ok,
                method: r2.method,
                command: r2.command || null,
                error: r2.error || "",
                startedAtMono: r2.startedAtMono,
                finishedAtMono: r2.finishedAtMono,
                gapAfterPreviousMs:
                    r2.startedAtMono != null && r1.finishedAtMono != null ? r2.startedAtMono - r1.finishedAtMono : null
            });
            panelActions.logLine(
                "探针 手势2：ok=" +
                    (r2.ok ? "是" : "否") +
                    " 手势=" +
                    r2.method +
                    (r2.command ? " 命令「" + r2.command + "」" : "") +
                    (r2.error ? " 错误：" + r2.error : "")
            );

            sleep(gapMs);
            const after1 = panelActions.grabFrameRaw();
            if (after1) {
                images.save(after1.image, probePng(label + "-after-1"));
            }
            sleep(postMs);
            const after2 = panelActions.grabFrameRaw();
            if (after2) {
                images.save(after2.image, probePng(label + "-after-2"));
            }

            if (before && after2) {
                report.sourceAfter = diffBox(before.image, after2.image, boxAround(from.x, from.y));
                report.targetAfter = diffBox(before.image, after2.image, boxAround(to.x, to.y));
                report.stepAfter = diffBox(before.image, after2.image, stepBoxNow());
                const srcMoved = report.sourceAfter.changed >= 30; // 子离开起点 = 大面积变化
                const dstChanged = report.targetAfter.changed >= 30;
                const stepChanged = report.stepAfter.changed >= 6; // 计步器数字变了
                report.verdict =
                    srcMoved && dstChanged
                        ? "落子成立（起点清空 + 落点变化" + (stepChanged ? " + 计步器变化" : "") + "）"
                        : !srcMoved && !dstChanged
                          ? "两格都没变（手势没被游戏采纳）"
                          : "部分变化，需要人工看图";
            } else {
                report.verdict = "缺帧，无法判定";
            }
            panelActions.logLine(
                "探针 " +
                    label +
                    " 结论：" +
                    report.verdict +
                    "｜起点变化 " +
                    (report.sourceAfter ? report.sourceAfter.changed : "?") +
                    "｜落点变化 " +
                    (report.targetAfter ? report.targetAfter.changed : "?") +
                    "｜计步器变化 " +
                    (report.stepAfter ? report.stepAfter.changed : "?")
            );
            saveProbeReport(report);
            return report;
        } finally {
            if (hidden) {
                try {
                    panelActions.setActivePanelVisibility(true);
                } catch (error) {
                    panelActions.logLine("探针恢复面板失败：" + error);
                }
            }
        }
    }

    /** 探针用几何：已有就直接用，没有就现定位一次。 */
    function ensureProbeGrid() {
        if (panelState.grid) {
            return panelState.grid;
        }
        try {
            panelActions.locateAndCalibrate();
        } catch (error) {
            panelActions.logLine("探针定位异常：" + error);
        }
        return panelState.grid || null;
    }

    /**
     * 探针用的识别：拿每格的**实测框圆心**（center 模式要用）。
     * ⚠️ 不画覆盖层（recognizeOnce 会画）—— 手势期间屏幕上不能有我们自己的东西。
     */
    function probeRecognize(grid) {
        const grabbed = panelActions.grabFrameRaw();
        if (!grabbed) {
            return null;
        }
        try {
            return panelState.recognizeBoard.run(
                grabbed.image,
                {
                    ok: true,
                    method: "floaty-probe",
                    grid: grid,
                    imageObject: grabbed.image,
                    frameObject: grabbed.image
                },
                { orientation: "RED_BOTTOM", confidence: 0.001, autoAlign: false },
                null
            );
        } catch (error) {
            panelActions.logLine("探针识别失败：" + error);
            return null;
        }
    }

    /**
     * 计算探针要发的坐标。
     *   · "lattice"（默认）：`geometry.pointAt` = 网格交点 = **棋子圆心**（象棋棋子就摆在交点上）；
     *   · "center"：该格最近一次识别的**检测框实测圆心**（需通过残差/归属校验，否则退回交点）。
     */
    function probePoint(grid, col, row, mode, centers) {
        const lattice = panelState.geometryModule.pointAt(grid, col, row);
        const point = {
            x: Math.round(lattice.x),
            y: Math.round(lattice.y),
            usedCenter: false,
            source: "网格交点",
            latticeX: Math.round(lattice.x),
            latticeY: Math.round(lattice.y)
        };
        if (mode !== "center" || !centers) {
            return point;
        }
        const entry = centers[row * panelState.geometryModule.COLS + col];
        if (!entry || !isFinite(entry.x) || !isFinite(entry.y)) {
            point.source = "网格交点（该格没有实测框）";
            return point;
        }
        const snapped = panelState.geometryModule.nearest(grid, entry.x, entry.y);
        if (!snapped || !snapped.inside || snapped.col !== col || snapped.row !== row) {
            point.source = "网格交点（实测框不属于该格）";
            return point;
        }
        point.x = Math.round(entry.x);
        point.y = Math.round(entry.y);
        point.usedCenter = true;
        point.source = "实测框圆心";
        point.normalizedResidual = panelState.geometryModule.normalizedResidual(grid, entry.x, entry.y);
        return point;
    }

    /**
     * 探针开局校验（§10.2）：子力 32 且与标准开局完全一致。
     * 为什么要：真机上曾经“重置开局”实际没重开（选中/悔棋残留），后面所有对照都白做。
     */
    function probeCheckOpening(grid) {
        const recognized = probeRecognize(grid);
        if (!recognized || !recognized.ok || !recognized.chars) {
            return {
                ok: false,
                reason:
                    "识别失败，拿不到盘面（" + (recognized && recognized.reason ? recognized.reason : "无结果") + "）"
            };
        }
        const counts = panelState.cellsContract.count(recognized.chars);
        if (recognized.unknownCells > 0) {
            return { ok: false, reason: "盘面有 " + recognized.unknownCells + " 个未知格" };
        }
        if (counts && counts.pieces !== 32) {
            return { ok: false, reason: "子力不是 32（实际 " + counts.pieces + "）" };
        }
        let start = null;
        try {
            start = panelState.fenModule.parseFen(panelState.fenModule.START_FEN);
        } catch (error) {
            start = null;
        }
        if (
            start &&
            start.ok &&
            !(panelState.legalModule && panelState.legalModule.boardMatches
                ? panelState.legalModule.boardMatches(recognized.chars, start.board)
                : recognized.chars.join("") === start.board.join(""))
        ) {
            return { ok: false, reason: "盘面不是标准开局（子力 " + (counts ? counts.pieces : "?") + "）" };
        }
        return { ok: true, reason: "标准开局，子力 " + (counts ? counts.pieces : "?") };
    }

    function saveProbeReport(report) {
        try {
            if (typeof files === "undefined" || !files || typeof files.write !== "function") {
                return;
            }
            if (!panelActions.panelFileWrite(panelState.PROBE_JSON, JSON.stringify(report, null, 2)))
                throw new Error("IO 队列拒绝写入");
            panelActions.logLine("探针结论已提交保存：" + panelState.PROBE_JSON);
        } catch (error) {
            panelActions.logLine("探针结论落盘失败：" + error);
        }
    }

    /**
     * **识别绘制（审核）**：抓一帧 → 认一帧 → 把结果画在冻结的画面上，并落盘一份。
     *
     * 这是「人审」的入口：屏幕上能直接看到
     *   · 网格线是否穿过棋子圆心（几何对齐）
     *   · 每格读成了什么子 + 该格置信度（兵种/颜色读错一眼可见）
     *   · 原始检测框（漏检、同一个子被检测两次）
     *   · 本手实际点击的起点/落点（点得准不准）
     * 跑自动循环时不给画（驱动每帧抓屏会把画板截进去），所以让用户先暂停。
     */
    function auditDraw() {
        if (panelState.running) {
            panelActions.logLine(
                "循环在跑，没法画审核帧：先点「暂停」，再点「识别绘制」（驱动每帧都要抓屏，画板会被截进图里）"
            );
            return;
        }
        if (!panelState.grid) {
            const located = panelActions.locateAndCalibrate();
            if (!located) {
                panelActions.logLine("识别绘制：还没有几何（定位失败）");
                return;
            }
        }
        const grabbed = panelActions.grabStableFrame();
        if (!grabbed) {
            panelActions.logLine("识别绘制：拿不到帧");
            return;
        }
        const result = panelState.recognizeBoard.run(
            grabbed.image,
            {
                ok: true,
                method: "floaty-audit",
                grid: panelState.grid,
                imageObject: grabbed.image,
                frameObject: grabbed.image
            },
            {
                orientation: "RED_BOTTOM",
                confidence: 0.001,
                autoAlign: false
            },
            null
        );
        if (!result || !result.ok) {
            panelActions.logLine("识别绘制：识别失败（" + ((result && result.reason) || "无结果") + "）");
            return;
        }
        if (panelState.drawMode === "off" || panelState.drawMode === "moves") {
            panelState.drawMode = "cells"; // 点这个按钮就是要看图：先给最干净的一档（每格的字 + 置信度）
        }
        panelActions.refreshOverlay(result, null, { force: true });
        const counts = result.chars ? panelState.cellsContract.count(result.chars) : null;
        let saved = "";
        try {
            if (typeof images !== "undefined" && images && typeof images.save === "function") {
                images.save(grabbed.image, panelState.AUDIT_FRAME);
                saved = panelState.AUDIT_FRAME;
            }
            if (typeof files !== "undefined" && files && typeof files.write === "function" && panelState.lastOverlay) {
                panelActions.panelFileWrite(
                    panelState.AUDIT_JSON,
                    JSON.stringify({
                        at: new Date().toString(),
                        text: panelState.lastOverlay.text,
                        items: panelState.lastOverlay.items,
                        layout: result.layout,
                        unknownCells: result.unknownCells,
                        detectionCount: result.detectionCount
                    })
                );
                saved += (saved ? " / " : "") + panelState.AUDIT_JSON;
            }
        } catch (error) {
            saved += "（落盘失败：" + error + "）";
        }
        panelActions.logLine(
            "识别绘制：子 " +
                (counts ? counts.pieces : "?") +
                " 空 " +
                (counts ? counts.empty : "?") +
                " 未知 " +
                result.unknownCells +
                "｜检测框 " +
                result.detectionCount +
                "｜已画在屏幕上（截图/录屏就能发给我）" +
                (saved ? "｜落盘 " + saved : "")
        );
    }

    return {
        probePng: probePng,
        diffBox: diffBox,
        boxAround: boxAround,
        stepBoxNow: stepBoxNow,
        runTapProbe: runTapProbe,
        ensureProbeGrid: ensureProbeGrid,
        probeRecognize: probeRecognize,
        probePoint: probePoint,
        probeCheckOpening: probeCheckOpening,
        saveProbeReport: saveProbeReport,
        auditDraw: auditDraw
    };
}

module.exports = { create: create };
