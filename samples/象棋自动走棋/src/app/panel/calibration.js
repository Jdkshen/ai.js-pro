// @engine quickjs
"use strict";

/** calibration：每个实例独立持有状态；跨模块动作由装配入口注入。
 * 状态通过参数显式共享；跨模块调用通过动作表，模块间不循环 require。
 */

function create(panelState, panelActions) {
    function checkLocateBudget() {
        if (panelState.locateGeneration !== (panelState.startGeneration || 0))
            throw new Error("定位已取消");
        if (panelState.locateDeadline && Date.now() >= panelState.locateDeadline)
            throw new Error("本次定位超时；可点「定位校准」深入检查，或调整棋盘后重试");
    }
    /**
     * 几何「发布」：面板 + 服务层 + **驱动** 三份一起换（2026-09-26 修，真机事故）。
     *
     * 事故现场（用户报「你看看」，10:22–10:26 循环 4 分钟一步没走）：
     *   启动时线定位先给了一份错的 v1（起点 y=905，比棋盘顶多出 208px ≈ 两格）；
     *   面板「盲读兜底」后来重定位成了对的 v2（起点 81.5,697，读得未知格 0）——
     *   但它只写了面板与服务层两份，**驱动还抱着 v1** ⇒ 每帧识别结果按检测框纠偏 (-17,-208)，
     *   驱动一看「微调越界」就拒收（护栏本身没错：棋子移动不会让原点差两格）
     *   ⇒ 驱动永远拒收 → 看门狗 25s 后重定位 → 又不通知驱动 → 死循环。
     * 教训：**任何一处理新几何的代码都必须走这个函数**（驱动不是只读面板那份的）。
     */
    function publishGrid(next) {
        const changed = next !== panelState.grid;
        if ((changed || !next) && typeof panelActions.clearOverlay === "function") panelActions.clearOverlay();
        panelState.grid = next;
        if (panelState.boardService) {
            panelState.boardService.setGrid(panelState.grid);
        }
        if (panelState.driver && typeof panelState.driver.setGrid === "function") {
            try {
                panelState.driver.setGrid(panelState.grid);
                if (changed && panelState.grid) {
                    panelActions.logLine(
                        "几何发布：v" +
                            (panelState.grid.version != null ? panelState.grid.version : "?") +
                            "（起点 " +
                            Math.round(panelState.grid.originX) +
                            "," +
                            Math.round(panelState.grid.originY) +
                            "）→ 面板/服务/驱动三份同步"
                    );
                }
            } catch (error) {
                panelActions.logLine("几何同步给驱动失败：" + error);
            }
        }
    }

    /**
     * 「几何沿用」快路径：**已经有几何**、体检也认同、且这一帧真能读出盘面 ⇒ 不再做全量校准。
     *
     * 为什么（2026-09-20 性能账）：`calibrateGrid` 会把 ~40 个候选**逐个整帧识别**一遍
     * —— 真机日志 `校准完成：几何 v1 偏移(0,0) 未知格 1 用时 7975ms`，而那局的棋盘压根没动
     * （几何 v1 一直没变），这 8s 纯属白等，恢复/重定位路径上一触发就是一次。
     *
     * 接受条件比校准更严（两道路）：① `grid-sanity` 的行体检过（抓「整行偏」）；
     * ② 用现几何真读一帧，未定格 ≤ `GEOMETRY_REUSE_MAX_UNKNOWN`。任一不过就老实走全量（线定位+校准）。
     *
     * @param grabbed `{ image }`（调用方已经抓好的帧，不再多抓一次）
     * @return { ok, method, grid, unknown } 或 null（不能用 ⇒ 走全量定位+校准）
     */
    function tryReuseGrid(grabbed) {
        if (!panelState.grid || !grabbed || !grabbed.image) {
            return null;
        }
        // ★ 2026-09-25 真机（用户报「又识别不到了」「定位校准也不行」）：新开局棋盘回到常规位置，
        //   旧几何差两格（-19,-211）；检测框纠偏被护栏拒收，而这里的沿用检查却因为
        //   「用错位几何也能读出一整盘（只是全是错位的子）」放行 ⇒ 盲读兜底每 25s 一次、卡了 292s。
        //   处置：驱动报过「检测框越界」= 几何变了的强证据 ⇒ 沿用检查直接让路（手动「定位校准」也走这条路）。
        if (
            panelState.driver &&
            typeof panelState.driver.geometrySuspect === "function" &&
            panelState.driver.geometrySuspect()
        ) {
            panelActions.logLine("几何沿用检查：驱动报过「检测框越界」⇒ 旧几何可疑，直接走全量校准");
            return null;
        }
        const geometryCheck = panelState.locateBoard.checkGeometry(grabbed.image, panelState.grid);
        if (!geometryCheck.ok) {
            panelActions.logLine("几何沿用检查：" + geometryCheck.reason);
            return null;
        }
        const sanity = panelActions.checkGridRows(grabbed.image, panelState.grid);
        if (!sanity.ok) {
            panelState.gridKingMissStreak = 0;
            panelActions.logLine("几何沿用检查不过（" + sanity.reason + "），走全量校准");
            return null;
        }
        const started = Date.now();
        // 上一帧未定格成堆：不再直接判死（多数是动画/高亮暂态），交给下面「真读一帧 + 帅将判据」定夺。
        if (panelState.lastPipelineUnknown >= 8) {
            panelActions.logLine(
                "几何沿用检查：上一帧读出 " + panelState.lastPipelineUnknown + " 个未定格（继续真读一帧再判）"
            );
        }
        // 识别链只有字形（2026-09-24「其他的都不要了」）：用现几何真读一帧，未定超限就不沿用。
        let result = null;
        try {
            result = panelState.glyphSource.recognize(grabbed.image, panelState.grid, {});
        } catch (error) {
            result = null;
            panelActions.logLine("几何沿用检查：字形识别异常（" + error + "），走全量校准");
            return null;
        }
        const unknown =
            result && result.ok && result.chars ? panelState.boardScan.countUnknownCells(result.chars) : 999;
        const kings = panelActions.countKings(result && result.chars);
        if (!result || !result.ok) {
            panelActions.logLine(
                "几何沿用检查：识别失败（" + ((result && result.reason) || "无结果") + "），走全量校准"
            );
            return null;
        }
        if (kings === 0) {
            // 帅/将都读不到也可能只是选中高亮或动画的单帧：真机 112 步就在这一帧重校准错了整行。
            // 行体检还通过时先保留旧几何；连续三次独立恢复检查都漏读，才允许全量重校准。
            panelState.gridKingMissStreak += 1;
            const condemned = panelActions.gridCondemned();
            if (
                Date.now() - panelState.lastCalibrationRejectAt < panelState.CALIBRATION_RETRY_COOLDOWN_MS &&
                !condemned
            ) {
                panelActions.logLine(
                    "几何沿用检查：帅/将读不到，但全量校准刚被否（" +
                        Math.round((Date.now() - panelState.lastCalibrationRejectAt) / 1000) +
                        "s 前）→ 冷却期内先用旧几何"
                );
            } else if (panelState.gridKingMissStreak < 3 && !condemned) {
                panelActions.logLine(
                    "几何沿用检查：帅/将暂时漏读 " +
                        panelState.gridKingMissStreak +
                        "/3 次，行体检仍过 → 保留旧几何等下一帧"
                );
            } else {
                if (!condemned) {
                    // ★ 旧几何连续读不到帅/将且仍要重校准 ⇒ 判它「有罪」：接下来这一轮铝定
                    //   检查不许再拿它当连续性参照（否则正确候选全被 0.45 格护栏否掉，死锁）。
                    //   撒回条件：任何一次沿用检查真读出了帅/将，或全量校准重锁成功。
                    panelState.gridCondemnAt = Date.now();
                }
                panelState.gridKingMissStreak = 0;
                panelActions.logLine(
                    "几何沿用检查：帅/将连续三次读不到 ⇒ 走全量校准" +
                        (condemned
                            ? "（旧几何已被判可疑：跳过连续性护栏，允许换到偏移更远的候选）"
                            : "（旧几何判为可疑：本轮铝定检查不再信它）")
                );
                return null;
            }
        } else {
            panelState.gridKingMissStreak = 0;
            panelState.gridCondemnAt = 0; // 旧几何这一帧读出了帅/将 ⇒ 撒回「有罪」判定
        }
        if (kings > 0 && unknown > panelState.GEOMETRY_REUSE_MAX_UNKNOWN) {
            // ★ 2026-09-24 11:49 真机事故：这一帧只是读出 未定 5 格（游戏高亮/动画的**暂态**），却被当成
            //   「几何坏了」→ 全量校准 → 换上一个坏网格（偏移(25,22)）→ 之后全部读不出帅/将。
            //   帅/将都在 ⇒ 几何仍然可信；暂态未定格交给驱动当「这一帧不观察」就行，**不重校准**。
            panelActions.logLine("几何沿用检查：未定 " + unknown + " 格（帅/将都在 ⇒ 几何仍可信，不重校准）");
        }
        // 帅/将这帧都漏读时只保留上一张几何；不能再用同一张可疑帧的检测框钉原点。
        if (kings > 0) {
            const snapped = panelActions.snapGridToPieces(panelState.grid, grabbed.image, "几何沿用");
            const shifted =
                snapped && (snapped.originX !== panelState.grid.originX || snapped.originY !== panelState.grid.originY);
            // ★ 2026-09-25 真机硬锁（用户报「又识别不到了」「定位校准也不行」，卡 18 分钟、44 次重试）：
            //   错位两格的旧几何照样能读出一整盘（只是全是错位的子）⇒ 沿用检查放行；而吸附到检测框的结果
            //   又因为「吸附后验证不过」被丢 ⇒ 两边互相抵消、永远出不来。
            //   处置：检测框想挪 **> 0.5 格**、且吸附后验证不过 → 记账；连续两次 ⇒ 判旧几何可疑
            //   （全量校准会跳过连续性护栏）并让路。
            const driftCells = shifted
                ? Math.max(
                      Math.abs(snapped.originX - panelState.grid.originX) /
                          Math.max(1, Math.abs(panelState.grid.stepX) || 1),
                      Math.abs(snapped.originY - panelState.grid.originY) /
                          Math.max(1, Math.abs(panelState.grid.stepY) || 1)
                  )
                : 0;
            const verifyOk =
                !shifted || panelActions.countKings(panelActions.readCharsWithGrid(grabbed.image, snapped)) > 0;
            if (driftCells > 0.5 && !verifyOk) {
                panelState.reuseDriftStrikes += 1;
                if (panelState.reuseDriftStrikes >= 2) {
                    panelState.reuseDriftStrikes = 0;
                    panelState.gridCondemnAt = Date.now(); // 判旧几何可疑：全量校准将跳过连续性护栏
                    panelActions.logLine(
                        "几何沿用：检测框想挪 " +
                            driftCells.toFixed(1) +
                            " 格、吸附后验证不过（连续 2 次）⇒ 判旧几何可疑，强制全量校准"
                    );
                    return null;
                }
                panelActions.logLine(
                    "几何沿用：检测框想挪 " +
                        driftCells.toFixed(1) +
                        " 格、吸附后验证不过（第 " +
                        panelState.reuseDriftStrikes +
                        "/2 次，先按旧几何等下一帧）"
                );
            } else {
                panelState.reuseDriftStrikes = 0;
            }
            if (verifyOk) {
                panelState.grid = panelActions.attachGridQuality(grabbed.image, snapped);
            } else {
                panelActions.logLine("几何沿用：检测框钉点后帅/将全丢 ⇒ 保持原几何");
            }
        }
        publishGrid(panelState.grid); // 三份同步（面板/服务/驱动）——2026-09-26：漏了驱动 ⇒ 拒收死循环
        panelActions.logLine(
            "几何沿用（体检过 + 未定 " +
                unknown +
                " 格，用时 " +
                (Date.now() - started) +
                "ms，省掉全量校准）｜几何 v" +
                panelState.grid.version
        );
        panelActions.snapPanelAboveBoard();
        return { ok: true, method: "reuse-verified", grid: panelState.grid, unknown: unknown };
    }

    /**
     * 两帧一致性确认（多证据融合方案 §10 最小版第 3 条）：
     * 用**第二帧**围绕候选几何再搜一次（种子=候选自身、±0.15 格、步距 ±2%），
     * 漂移 ≤ 0.12 格 / 2.5% 才认为「锁得住」。拿不到第二帧、或第二帧搜不出结果时**放行**
     * （打印注记）—— 确认是用来抓「单帧假几何」的，不是把能用的几何挡在门外的。
     * @param grid 候选几何（pickGrid 的结果，含 originX/originY/stepX/stepY）
     * @return { ok, confirmed, note }
     */
    function confirmPickedGrid(grid) {
        if (!grid) {
            return { ok: true, confirmed: false, note: "没有候选几何，跳过确认" };
        }
        let second = null;
        try {
            const grabbed2 = panelActions.grabFrame();
            if (grabbed2 && grabbed2.image) {
                second = panelState.recognizeBoard.pickGrid(grabbed2.image, {
                    orientation: "RED_BOTTOM",
                    confidence: 0.001,
                    seeds: [grid],
                    radius: 6,
                    step: 3,
                    radiusRatio: 0.15,
                    stepScales: [1, 0.98, 1.02],
                    probeCropMargin: 0.65
                });
            }
        } catch (error) {
            second = null;
        }
        if (!second) {
            return { ok: true, confirmed: false, note: "第二帧不可用，未做确认" };
        }
        if (!second.ok) {
            return { ok: true, confirmed: false, note: "第二帧搜索未成功（" + second.reason + "）" };
        }
        const cell = Math.max(1, (Math.abs(grid.stepX) + Math.abs(grid.stepY)) / 2);
        const dx = Math.abs(second.grid.originX - grid.originX);
        const dy = Math.abs(second.grid.originY - grid.originY);
        const dsx = Math.abs(second.grid.stepX - grid.stepX) / Math.max(1e-6, Math.abs(grid.stepX));
        const dsy = Math.abs(second.grid.stepY - grid.stepY) / Math.max(1e-6, Math.abs(grid.stepY));
        const note =
            "Δ(" +
            Math.round(dx) +
            "," +
            Math.round(dy) +
            ")px，格距漂移 " +
            (dsx * 100).toFixed(1) +
            "% / " +
            (dsy * 100).toFixed(1) +
            "%";
        if (dx <= cell * 0.12 && dy <= cell * 0.12 && dsx <= 0.025 && dsy <= 0.025) {
            return { ok: true, confirmed: true, note: note };
        }
        return { ok: false, confirmed: false, note: note };
    }

    /**
     * 定位 + 校准。
     * 顺序：线定位（在验证集上质量最好）→ 失败则 pickGrid 缓存框搜索 → 再用 calibrateGrid 精校准。
     * K40 实测：跳过 calibrateGrid，纯阵营错会从 0 涨到 77 格，所以这一步不能省。
     */
    function locateAndCalibrate() {
        if (typeof panelActions.cancelVision === "function") panelActions.cancelVision();
        if (typeof panelActions.clearOverlay === "function") panelActions.clearOverlay();
        if (typeof panelState.adapter.windowCaptureNeedsCalibration === "function" && panelState.adapter.windowCaptureNeedsCalibration()) {
            publishGrid(null);
            panelState.adapter.resetWindowCaptureGeometryForCalibration();
        }
        panelState.locateGeneration = panelState.startGeneration || 0;
        // 快速启动 8s；手动定位保留深入搜索预算。取消在每个候选之间检查。
        panelState.locateDeadline = Date.now() + (panelState.starting ? 8000 : 30000);
        // ★ 定位/校准必须拿「静止帧」：转场/动画帧上算出来的几何会把整局带沟里
        //   （2026-09-25 16:16 事故：转场帧锁了一份偏 1~2 格的几何 → 卡 200s+）。
        //   strict：等不到静止宁可不定位（看门狗/按钮稍后重试）。
        // ★ 2026-09-25（用户报「悬浮窗挡住几何来定位盘」）：定位帧**强制藏面板**——
        //   面板的白块/底边是两条强横边，贴着棋盘上沿时会被线定位当成/干扰「盘面顶线」。
        //   面板默认已让开 boardClearGap（24px），这一刀是兜底：定位帧永远只见干净盘面。
        const grabbed = panelActions.grabStableFrame({ strict: true, maxTries: 6, hide: true });
        checkLocateBudget();
        if (!grabbed) {
            panelActions.logLine("定位失败：拿不到帧或画面一直在动（等稳未果，稍后重试）");
            return null;
        }
        // 启动/重定位也先判页面；成绩页的缩小棋盘不能进入全量校准。
        if (!panelState.running) panelState.gameOverSeen = false;
        let ended = panelActions.probeGameOver(grabbed.image, true);
        if (!ended && panelState.gameOverCandidate) {
            const next = panelActions.grabStableFrame({ strict:true, maxTries:3, hide:true });
            ended = !!(next && panelActions.probeGameOver(next.image, true));
        }
        if (ended) {
            panelState.startError = "对局已结束，请进入新对局后再开始";
            panelActions.logLine(panelState.startError + "；跳过棋盘定位和校准");
            return null;
        }
        // ★ 快路径：几何还在就直接用（见 tryReuseGrid；真机 8.4s 的全量校准就省在这里）
        const reused = tryReuseGrid(grabbed);
        if (reused) {
            return reused;
        }
        let circleEvidence = null;
        function circlesFor(candidate) {
            if (circleEvidence === null) {
                circleEvidence = panelState.circleGridCheck.detect(
                    images,
                    grabbed.image,
                    candidate,
                    panelState.adapter.sizeOf(grabbed.image)
                );
                panelActions.logLine(
                    "棋子圆心校验：找到 " +
                        circleEvidence.circles.length +
                        " 个圆" +
                        (circleEvidence.reason ? "（" + circleEvidence.reason + "）" : "")
                );
            }
            return circleEvidence.circles;
        }
        const options = {
            orientation: "RED_BOTTOM",
            confidence: 0.001,
            autoAlign: false,
            search: { maxShift: 18, step: 3 },
            maxCandidates: 25,
            acceptBaseGrid: function (item) {
                checkLocateBudget();
                const verdict = panelState.circleGridCheck.evaluate(circlesFor(item.grid), item.grid);
                // 完整无修复的盘面 + 圆心覆盖：避免「把有子格读空」的低代价假成功。
                return item.layout && item.layout.indexOf("K") >= 0 && item.layout.indexOf("k") >= 0 &&
                    verdict.conclusive && verdict.ok && verdict.matched >= item.pieces &&
                    verdict.matched >= Math.ceil(verdict.total * 0.9);
            },
            shouldAbort: function () {
                return Date.now() >= panelState.locateDeadline ||
                    panelState.locateGeneration !== (panelState.startGeneration || 0);
            }
        };
        let located = null;
        // ★ 2026-09-21（P0，MediaProjection 也走线定位）：条件从「shell 通道」放宽成「拿到可读帧」——
        //   手机没有 Shizuku 时截图走 MediaProjection（app 通道），旧条件会把 app 帧直接跳过线定位、
        //   送进 pickGrid → 在 1280×2772 上连续拒绝（未知格 6、检测框棋盘覆盖率 0.36~0.50）。
        //   同一帧离线线定位是好的（竖 9/9、横 8/10）⇒ app 帧与 shell 帧共用线定位，失败再回退 pickGrid。
        if (grabbed && grabbed.image) {
            // 直接传**帧对象**：raw 快通道没有磁盘路径（`readImage` 已支持帧直通，省一次解码）
            located = panelState.locateBoard.locateBoardInImage(grabbed.image, {});
            checkLocateBudget();
            const fast = panelState.recognizeBoard.confirmSiftGrid(grabbed.image, located, options);
            if (fast && fast.ok) {
                publishGrid(fast.grid);
                panelState.gridCondemnAt = 0;
                panelState.calibrationNote = "SIFT 几何 v" + panelState.grid.version +
                    " 定位 " + located.timing.locateMs + "ms／盘面校验 " + fast.ms + "ms";
                panelActions.logLine("校准完成：" + panelState.calibrationNote + "（字形/棋规/圆心校验通过）");
                panelActions.snapPanelAboveBoard();
                return fast;
            }
            // 候选择优可见性（2026-09-22 多证据融合方案 P0 雏形）：试了多个候选时把结果写进日志，
            // 便于下次冷启动时直接核对「选了哪个/为什么继续看后面的范围」。
            if (located.ok && located.attempts > 1) {
                panelActions.logLine(
                    "线定位候选择优：共试 " +
                        located.attempts +
                        " 个候选，选中分数 " +
                        (located.chosenScore == null ? "-" : located.chosenScore.toFixed(2)) +
                        (located.earlyAccept ? "（足够好，提前收工）" : "（比完全部范围）")
                );
            }
            // ★ 定位「成功」也要体检：整行偏是最常见的假成功（见 checkGridRows 的说明）
            if (located.ok && located.grid) {
                let sanity = panelActions.checkGridRows(grabbed.image, located.grid);
                if (!sanity.ok) {
                    // ★ 2026-09-20 晚（now47/now49 真机，中残局）：线定位找对了线、只是 origin 锚错一行时，
                    //   体检会拒；旧行为直接回退 pickGrid 缓存框 —— 实测那路给偏 ~17px 的网格，读数全盘变差。
                    //   先在线定位结果**本身上**依次试平移候选（±整行/±半行），选「体检过 + **落在网格内的
                    //   检测框数变多**」的最优者。
                    //   ⚠️ 「框数必须变多」是防误判的硬保护：合法的「顶行真空了」局面里，平移不会让框数变多
                    //   （本来就没有子掉在网格外）⇒ 不会把一个正确的网格换成错的。
                    const tries = panelState.gridSanity.shiftCandidates(located.grid);
                    let rescued = null;
                    for (let s = 0; s < tries.length; s++) {
                        checkLocateBudget();
                        const shifted = Object.assign({}, located.grid, {
                            originX: located.grid.originX + tries[s][0],
                            originY: located.grid.originY + tries[s][1]
                        });
                        const alt = panelActions.checkGridRows(grabbed.image, shifted);
                        if (!alt.ok) {
                            continue;
                        }
                        if (sanity.pieces != null && alt.pieces != null && alt.pieces <= sanity.pieces) {
                            continue; // 平移没把子救回网格里 ⇒ 多半不是偏行，别换
                        }
                        if (!rescued || alt.pieces > rescued.alt.pieces) {
                            rescued = { shifted: shifted, alt: alt, dx: tries[s][0], dy: tries[s][1] };
                        }
                    }
                    if (rescued) {
                        panelActions.logLine(
                            "线定位几何体检平移救回：原点 (" +
                                rescued.dx +
                                "," +
                                rescued.dy +
                                ") 后通过（" +
                                rescued.alt.reason +
                                "；框数 " +
                                sanity.pieces +
                                " → " +
                                rescued.alt.pieces +
                                "）"
                        );
                        sanity = rescued.alt;
                        located = {
                            ok: true,
                            method: "line-shift",
                            grid: rescued.shifted,
                            imageObject: grabbed.image,
                            frameObject: grabbed.image
                        };
                    } else {
                        panelActions.logLine("线定位几何体检不过（" + sanity.reason + "），改用 pickGrid 缓存框搜索");
                        located = { ok: false, reason: "几何体检不过：" + sanity.reason };
                    }
                } else {
                    panelActions.logLine("线定位几何体检通过（" + sanity.reason + "）");
                }
            }
            if (!located.ok) {
                panelActions.logLine("线定位失败（" + located.reason + "），改用 pickGrid 缓存框搜索");
                const picked = panelState.recognizeBoard.pickGrid(grabbed.image, {
                    orientation: "RED_BOTTOM",
                    confidence: 0.001,
                    radius: 18,
                    step: 4,
                    // 2026-09-22（多证据融合方案 §10）：平移 ±0.5 格 + 步距 ±2%/±4% 五档；
                    // 旧参数（±18px、固定格距）够不到「线定位第一条线锚错半格」（真机偏差 49px）。
                    // 裁剪外扩 0.9：要盖住「搜索半径 0.5 格 + 棋子半径 ~0.45 格」的范围。
                    radiusRatio: 0.5,
                    probeCropMargin: 0.9
                });
                if (!picked.ok) {
                    panelActions.logLine("pickGrid 也失败：" + picked.reason);
                    return null;
                }
                // 两帧一致性确认（方案 §10 最小版第 3 条）：第二帧再搜一次，漂移在容差内才锁定。
                const lock = confirmPickedGrid(picked.grid);
                if (!lock.ok) {
                    panelState.pickLockStrikes += 1;
                    panelActions.logLine(
                        "回退几何两帧不一致（" + lock.note + "），连续 " + panelState.pickLockStrikes + " 次"
                    );
                    if (panelState.pickLockStrikes < 2) {
                        return null; // 本轮放弃，下一轮重新定位（避免拿单帧假几何去点）
                    }
                    panelActions.logLine(
                        "两帧不一致已连续 " + panelState.pickLockStrikes + " 次，放行本轮几何（避免定位死循环）"
                    );
                } else {
                    panelState.pickLockStrikes = 0;
                    if (lock.note) {
                        panelActions.logLine("回退几何两帧确认：" + lock.note);
                    }
                }
                located = {
                    ok: true,
                    method: "pick-grid-cached",
                    grid: picked.grid,
                    imageObject: grabbed.image,
                    frameObject: grabbed.image
                };
                panelActions.logLine(
                    "pickGrid 成功：尝试 " +
                        picked.tried +
                        " 个候选（检测 " +
                        (picked.report.detectMs == null ? "-" : picked.report.detectMs) +
                        "ms，搜索 " +
                        picked.report.searchMs +
                        "ms）"
                );
            }
        } else {
            located = {
                ok: true,
                method: "captured-frame",
                grid: null,
                imageObject: grabbed.image,
                frameObject: grabbed.image
            };
            const picked = panelState.recognizeBoard.pickGrid(grabbed.image, {
                orientation: "RED_BOTTOM",
                confidence: 0.001,
                radius: 18,
                step: 4,
                probeCropMargin: 0.5
            });
            if (!picked.ok) {
                panelActions.logLine("pickGrid 失败：" + picked.reason);
                return null;
            }
            located.grid = picked.grid;
        }
        if (!located.grid) {
            panelActions.logLine("定位没有给出几何");
            return null;
        }
        const started = Date.now();
        const calibrated = panelState.recognizeBoard.calibrateGrid(grabbed.image, located, options);
        checkLocateBudget();
        if (calibrated && calibrated.ok && calibrated.grid) {
            const candidate = panelActions.attachGridQuality(
                grabbed.image,
                panelActions.snapGridToPieces(calibrated.grid, grabbed.image, "校准后")
            );
            // ★ 2026-09-24 11:50 / 12:02 真机事故的护栏：**校准结果先过可信性检查**（读一帧，帅/将必须看得到）；
            //   读不到时再在 **±1 格锚定邻域**里扫一圈（稀疏残局/棋盘放大后，线定位会把序号锚错一整格）。
            //   代价函数会爱上「大部分格子读成空」的坏网格（未知格少、丢子不计）—— 真机就是这样把
            //   偏移(25,22)/(23,32) 的网格选进来的，之后每帧「帅/将 0」把面板卡死。
            const aligned = panelActions.findAlignedGrid(grabbed.image, candidate, circlesFor(candidate));
            if (!aligned) {
                // ⓵ 校准可能把**线定位本来对的**锚定带歪（真机 12:02：候选被拖到 偏移(-81,26)）——
                //   先拿线定位的原始几何做 ±1 格邻域再验一次。
                const alignedLine = located.grid
                    ? panelActions.findAlignedGrid(
                          grabbed.image,
                          panelActions.attachGridQuality(grabbed.image, located.grid),
                          circlesFor(located.grid)
                      )
                    : null;
                if (alignedLine) {
                    publishGrid(alignedLine.grid);
                    panelState.gridCondemnAt = 0; // 重锁成功：撤回「旧几何有罪」判定
                    panelState.calibrationNote = "几何 v" + panelState.grid.version + "（线定位原始几何）";
                    panelActions.logLine(
                        "校准候选不可信 ⇒ 退回线定位原始几何（±1 格修正后读出帅/将）：" +
                            panelState.calibrationNote +
                            "｜起点 " +
                            Math.round(panelState.grid.originX) +
                            "," +
                            Math.round(panelState.grid.originY) +
                            " 格距 " +
                            Math.round(panelState.grid.stepX) +
                            "," +
                            Math.round(panelState.grid.stepY)
                    );
                    panelActions.snapPanelAboveBoard();
                    return { ok: true, method: "line-raw-rescue", grid: panelState.grid };
                }
                // ⓶ 线定位/校准的锚定都不可信：最后一招用**检测框拟合**（pickGrid）—— 检测框就压在
                //   棋子（=交点）上，锚定证据比稀疏线的“哪条线是第 0 条”强得多。
                const pickBase = panelActions.pickGridCandidate(grabbed);
                const alignedPick = pickBase
                    ? panelActions.findAlignedGrid(
                          grabbed.image,
                          panelActions.attachGridQuality(grabbed.image, pickBase),
                          circlesFor(pickBase)
                      )
                    : null;
                if (alignedPick) {
                    publishGrid(alignedPick.grid);
                    panelState.gridCondemnAt = 0; // 重锁成功：撤回「旧几何有罪」判定
                    panelState.calibrationNote = "几何 v" + panelState.grid.version + "（pickGrid 兜底）";
                    panelActions.logLine(
                        "线定位/校准的锚定不可信 ⇒ 改用 pickGrid 检测框拟合（±1 修正后读出帅/将）：" +
                            panelState.calibrationNote +
                            "｜起点 " +
                            Math.round(panelState.grid.originX) +
                            "," +
                            Math.round(panelState.grid.originY) +
                            " 格距 " +
                            Math.round(panelState.grid.stepX) +
                            "," +
                            Math.round(panelState.grid.stepY)
                    );
                    panelActions.snapPanelAboveBoard();
                    return { ok: true, method: "pick-grid-rescue", grid: panelState.grid };
                }
                panelState.lastCalibrationRejectAt = Date.now();
                panelActions.logLine(
                    "校准结果可信性检查不过（±1 格邻域 + pickGrid 兜底都读不到帅/将）⇒ 不采用：候选 偏移(" +
                        calibrated.dx +
                        "," +
                        calibrated.dy +
                        ") 未知格 " +
                        calibrated.unknown +
                        (panelState.grid ? "，保留原几何" : "，当前无可用几何")
                );
                return null;
            }
            if (aligned.shift[0] !== 0 || aligned.shift[1] !== 0) {
                panelActions.logLine(
                    "锚定修正：候选读数偏了一整格，按 (" +
                        aligned.shift[0] +
                        "," +
                        aligned.shift[1] +
                        ") 格平移后读出双方帅/将 ⇒ 采用（起点 " +
                        Math.round(aligned.grid.originX) +
                        "," +
                        Math.round(aligned.grid.originY) +
                        " 格距 " +
                        Math.round(aligned.grid.stepX) +
                        "," +
                        Math.round(aligned.grid.stepY) +
                        "）"
                );
            }
            publishGrid(aligned.grid);
            panelState.gridCondemnAt = 0; // 全量校准重锁成功：撤回「旧几何有罪」判定
            panelState.calibrationNote =
                "几何 v" +
                panelState.grid.version +
                " 偏移(" +
                calibrated.dx +
                "," +
                calibrated.dy +
                ") 未知格 " +
                calibrated.unknown +
                " 用时 " +
                (Date.now() - started) +
                "ms";
            panelActions.logLine("校准完成：" + panelState.calibrationNote + "｜定位方式 " + located.method);
            if (calibrated.layout) {
                panelActions.logLine("局面 " + calibrated.layout);
            }
            panelActions.snapPanelAboveBoard(); // 知道棋盘在哪了 → 把自己摆到棋盘上沿之上那条空带里
            return calibrated;
        }
        if (located.grid) {
            const fallbackCandidate = panelActions.attachGridQuality(grabbed.image, located.grid);
            // 同样过可信性检查（带 ±1 格锚定邻域）：定位几何读不到帅/将也不采
            const alignedFallback = panelActions.findAlignedGrid(
                grabbed.image,
                fallbackCandidate,
                circlesFor(fallbackCandidate)
            );
            if (!alignedFallback) {
                panelState.lastCalibrationRejectAt = Date.now();
                panelActions.logLine(
                    "定位几何可信性检查不过（±1 格邻域也读不到帅/将）⇒ 不采用" +
                        (panelState.grid ? "，保留原几何" : "，当前无可用几何")
                );
                return null;
            }
            if (alignedFallback.shift[0] !== 0 || alignedFallback.shift[1] !== 0) {
                panelActions.logLine(
                    "锚定修正：定位几何按 (" +
                        alignedFallback.shift[0] +
                        "," +
                        alignedFallback.shift[1] +
                        ") 格平移后读出双方帅/将 ⇒ 采用（未精校准）"
                );
            }
            publishGrid(alignedFallback.grid);
            panelState.calibrationNote = "几何 v" + panelState.grid.version + "（未精校准）";
            panelActions.logLine("精校准没成功，沿用定位几何：" + panelState.calibrationNote);
            panelActions.snapPanelAboveBoard();
            return null;
        }
        panelActions.logLine("校准失败且没有可用几何");
        return null;
    }

    /** 识别一帧（复用已校准几何；没有就先定位）。 */
    function recognizeOnce() {
        if (!panelState.grid && !locateAndCalibrate()) return null;
        const result = panelActions.ensureBoardService().recognizeSmart({});
        if (!result || !result.ok) {
            panelActions.logLine("识别失败：" + ((result && result.reason) || "无结果"));
            return null;
        }
        const counts = panelState.cellsContract.count(result.chars || []);
        panelActions.logLine("字形识别：子 " + counts.pieces + " 空 " + counts.empty + " 未知 " + result.unknownCells);
        panelActions.logLine("局面 " + result.layout);
        panelActions.refreshOverlay(result, null);
        return result;
    }

    return {
        checkLocateBudget: checkLocateBudget,
        publishGrid: publishGrid,
        tryReuseGrid: tryReuseGrid,
        confirmPickedGrid: confirmPickedGrid,
        locateAndCalibrate: locateAndCalibrate,
        recognizeOnce: recognizeOnce
    };
}

module.exports = { create: create };
