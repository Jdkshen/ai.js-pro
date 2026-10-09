// @engine quickjs
"use strict";

/** ocr：每个实例独立持有状态；跨模块动作由装配入口注入。
 * 状态字段：adapter, recognizeBoard, pieceSideModule, inkColorModule, pieceOcr, boardScan, pieceDetector, driver, ocrGrayEnabled, ocrPrimaryMode, visionDetectorSession, grid, ocrBoardCache, ocrBoardCacheAt, OCR_BOARD_CACHE_MS, frameReuseHits, lastEvidence, OCR_REUSE_MS
 * 外部动作：cellRegionFor, grabFrame, logLine, ocrRuntime, recentFrameImage
 */


function create(panelState, panelActions) {
function ocrCell(info) {
    // ★ 复用最近一帧（驱动问证据总在一轮观察之后，那一帧是热的；见 FRAME_REUSE_MS）
    const hot = panelActions.recentFrameImage();
    if (hot) {
        panelState.frameReuseHits += 1;
        return ocrCellOnImage(hot, info ? info.index : null);
    }
    const grabbed = panelActions.grabFrame();
    if (!grabbed || !grabbed.image) {
        return { ok: false, unavailable: true, reason: "取帧失败" };
    }
    return ocrCellOnImage(grabbed.image, info ? info.index : null);
}

/**
 * 单帧多格证据（ROI 快速确认用，2026-09-21 提速·方案第 3 条）：抓**一帧**、只认指定格，
 * 返回与 ocrCell 同构的结果数组。
 * 与 ocrCell 的差别：不复用「最近一帧」（确认要的是**点击之后**的新画面），多格共用同一次抓帧。
 */
function ocrCellsNow(indexes) {
    if (!indexes || indexes.length === 0) {
        return [];
    }
    const grabbed = panelActions.grabFrame();
    if (!grabbed || !grabbed.image) {
        return null;
    }
    const out = [];
    for (let i = 0; i < indexes.length; i++) {
        out.push(ocrCellOnImage(grabbed.image, indexes[i]));
    }
    return out;
}

/**
 * 单格证据（**复用已经拿到的那一帧**）：OCR 认兵种 + 像素判阵营 + 找色墨迹计数。
 * 差分模式逐格认兵种走这里 —— 不能再抓一帧（一次抓帧 ~200ms，一帧要认好几格）。
 */
function ocrCellOnImage(image, index) {
    const session = panelActions.ocrRuntime();
    if (!session || index == null || !panelState.grid) {
        return { ok: false, unavailable: true, reason: "OCR 或几何不可用" };
    }
    const region = panelActions.cellRegionFor(index);
    if (!region) {
        return { ok: false, unavailable: true, reason: "几何不可用" };
    }
    try {
        // ⚠️ 用**真实契约**：`$ocr` 会话给的是 `detect(image, {max, region})`，
        //    返回 [{ text, score, bounds }]，text 就是棋子上的**汉字**（炮/车/卒…）。
        //    不能走 pieceOcr.confirmCell —— 它要的是 `api.recognizeCell`，$ocr 会话没有，
        //    于是永远返回「OCR 未提供」（2026-09-19 踩过：误打误撞得出「读不到字」的结论）。
        // ★ 2026-09-21（用户口径「OCR 先灰度」）：**先灰度直读**（把格子裁出来转灰度再认）；
        //   灰度过关率整体更好（帅 彩色「印市」0.49 → 灰度「師」0.70），而每格彩色那遍常读不出
        //   （落子高亮环/动画）⇒ 先灰度省掉一次白读。
        //   灰度读不出字形时再用**彩色（整帧 + region）**兜底（灰度也有读错的：馬→志、兵→民）。
        let raw = "";
        let score = 0;
        let hit = null;
        const grayCapable = panelState.ocrGrayEnabled && typeof panelState.adapter.prepareRegion === "function";
        if (grayCapable) {
            const gray = panelState.adapter.prepareRegion(image, region, "gray");
            if (gray) {
                try {
                    const grayBlocks = session.detect(gray, { max: 1 }) || [];
                    const first = grayBlocks[0];
                    if (first) {
                        raw = String(first.text || first.label || "");
                        score = Number(first.score == null ? first.confidence : first.score) || 0;
                        hit = panelState.pieceOcr.classOf(raw);
                    }
                } catch (error) {
                    // 灰度读失败就当没读到，交给下面的彩色兜底
                }
                if (typeof panelState.adapter.releaseFrame === "function") {
                    panelState.adapter.releaseFrame(gray);
                } else if (typeof gray.recycle === "function") {
                    try {
                        gray.recycle();
                    } catch (ignore) {
                        // 忽略
                    }
                }
            }
        }
        // ★ 不管有没有字，都把这格的**墨迹计数**带回去（用户提议：棋子定位用找色扫描）：
        //   调用方靠它区分「有子 / 空格 / 分不开」，完全不看模型的检测框。
        let ink = null;
        try {
            const counts = panelState.inkColorModule.countsFor({ countColor: panelState.adapter.countColor },
                image,
                { x: region[0], y: region[1], width: region[2], height: region[3] },
                {});
            if (counts && counts.source === "findColor" && counts.area > 0) {
                ink = { red: counts.red, black: counts.black, area: counts.area };
            }
        } catch (error) {
            ink = null;
        }
        // ★ 2026-09-20 晚（真机 now45 定位，本轮卡死 62 轮的真正原因），2026-09-21 方向反转：
        //   **灰度没读出可用字形时，用彩色（整帧 + region）兜底** —— 与 `board-scan` 的同款兜底
        //   （驱动证据那条路一直有它，所以出现「驱动读得到、面板读不到」的怪象）。
        //   真机实测例：(2,7) 的红馬彩色读不出 ⇒ 面板一直 '?'、驱动的证据却能读到 N ⇒「1 个未知格」卡死。
        //   只在这格**确实有墨迹**时才兜底（空格/假框直接跳过，每格省 ~25ms）；
        //   灰度路不可用（关了/宿主没有）时，彩色就是主读 —— 老路径照旧无条件读一次。
        if (!hit || score < 0.6) {
            const inkSum = ink ? ((Number(ink.red) || 0) + (Number(ink.black) || 0)) : 0;
            if (!grayCapable || inkSum > 0) {
                let blocks = null;
                try {
                    blocks = session.detect(image, { max: 1, region: region }) || [];
                } catch (error) {
                    blocks = null;
                }
                const one = blocks ? blocks[0] : null;
                if (one) {
                    const raw2 = String(one.text || one.label || "");
                    const score2 = Number(one.score == null ? one.confidence : one.score) || 0;
                    const hit2 = panelState.pieceOcr.classOf(raw2);
                    if (hit2 && score2 >= 0.6) {
                        raw = raw2;
                        score = score2;
                        hit = hit2;
                    }
                }
            }
        }
        if (!hit || score < 0.6) {
            // 没读到可用字形 ⇒ **证据**：那格读不出来（带上墨迹计数，防止「有子没字」被当成空）
            return {
                ok: false, noText: true, score: score, reason: "置信度不足",
                text: raw, region: region, ink: ink
            };
        }
        // ★ 2026-09-20（用户提议「定位一次 + 找色判走动 + OCR 认子」）：
        //   顺带按**像素颜色**判阵营，让这一路自己给出「什么子 + 哪一边」——
        //   不吃模型的红黑读反，也不怕高亮环把格子盖住（找色只看墨迹的颜色）。
        //   判不出来就返回 null（不猜），调用方自己决定要不要用。
        let side = null;
        let sideScore = 0;
        try {
            const verdict = panelState.pieceSideModule.classifyAt(function (x, y) {
                return panelState.adapter.rgbAt(image, x, y);
            }, panelState.grid, index % 9, Math.floor(index / 9), {});
            if (verdict && verdict.side) {
                side = verdict.side;
                sideScore = verdict.score;
            }
        } catch (error) {
            side = null;
        }
        // ★ 2026-09-20 晚（真机 now44 定位）：**带「上一步光环」的棋子**会压住 classifyAt 的采样点 ⇒
        //   判不出（side=null）甚至判反，而面板侧各条路都要求 side 明确，不然整格被丢弃 ⇒
        //   缓存里留 '?' / 留错值 ⇒ 驱动每 13s 改正一次、下一帧又被打回（now44：同一格改正 3 次 + 卡死 62 轮）。
        //   字形**墨色计数**是硬证据（实测：红子 红~1000+/黑0，黑子相反，光环是白的、不参与计数）⇒
        //   「一边有、另一边≈0」时直接采信，正好盖过光环的干扰。
        if (ink) {
            const redInk = Number(ink.red) || 0;
            const blackInk = Number(ink.black) || 0;
            if (redInk >= 40 && blackInk <= 3) {
                side = "RED";
                sideScore = 0.6;
            } else if (blackInk >= 40 && redInk <= 3) {
                side = "BLACK";
                sideScore = 0.6;
            }
        }
        return {
            ok: true, cls: hit.cls, glyph: hit.glyph, score: score, text: raw, region: region,
            side: side, sideScore: sideScore, ink: ink
        };
    } catch (error) {
        return { ok: false, unavailable: true, reason: "OCR 异常：" + error };
    }
}

/**
 * 全盘 OCR 对照（每 `OCR_BOARD_INTERVAL_MS` 一次）："驱动认为的盘面" vs "OCR 逐格读出来的盘面"。
 *
 * 为什么要它（2026-09-19）：黑方那枚炮「凭空消失」时，光看驱动日志只能猜 ——
 * 驱动说 30 子、OCR 说 32 子，到底是哪一格被丢掉，一列就出来了。
 * ⚠️ 字形分不出红黑（黑炮也可能写成「炮」）⇒ 比对只比**兵种**（忽略大小写），打印时带字形。
 */
function compactBoard(chars) {
    const rows = [];
    for (let r = 0; r < 10; r++) {
        let line = "";
        let empty = 0;
        for (let c = 0; c < 9; c++) {
            const ch = chars[r * 9 + c];
            if (ch) {
                if (empty > 0) {
                    line += empty;
                    empty = 0;
                }
                line += ch;
            } else {
                empty += 1;
            }
        }
        if (empty > 0) {
            line += empty;
        }
        rows.push(line);
    }
    return rows.join("/");
}

function rememberPipelineEvidence(smart) {
    if (!smart || !smart.ok || !smart.evidence || !smart.evidence.length || !panelState.grid) {
        return;
    }
    panelState.lastEvidence = {
        cells: smart.evidence,
        geometry: [panelState.grid.originX, panelState.grid.originY, panelState.grid.stepX, panelState.grid.stepY].join(","),
        at: Date.now()
    };
}

/** 把上一条逐格证据摊成 board-scan 认的 `reuse`；不新鲜/几何变了就返回 null（宁可重读） */
function reuseFromLastEvidence() {
    if (!panelState.lastEvidence || !panelState.grid) {
        return null;
    }
    if (Date.now() - panelState.lastEvidence.at > panelState.OCR_REUSE_MS) {
        return null;
    }
    if (panelState.lastEvidence.geometry !== [panelState.grid.originX, panelState.grid.originY, panelState.grid.stepX, panelState.grid.stepY].join(",")) {
        return null;
    }
    const reuse = panelState.boardScan.reuseFromEvidence(panelState.lastEvidence.cells);
    return reuse.reused > 0 ? reuse : null;
}

/**
 * 对照用（**纯白拿**）：上次识别的逐格证据还在就直接拼一份「流水线读到的盘面」——
 * 不抓帧、不跑检测、不扫格子（0ms）。
 *
 * ⚠️ 两条边界（都必须守住）：
 *   · 它**只给对照日志**（`logOcrBoardCompare`）用。驱动要的「全盘 OCR 证据」不能走这条：
 *     流水线只看**有框的格子**，而驱动那条路的价值正在于「没框但有墨迹」的漏检候补格（要真扫才知道）。
 *   · 覆盖面只有 `evidence` 里出现过的格子 ⇒ 返回 `covered`，对比时**只比被覆盖的格**，
 *     否则「没看过」会被印成「OCR 没看到」（真机差异行就是这样误导人的）。
 */
function ocrBoardFromLastEvidence() {
    if (!panelState.lastEvidence || !panelState.grid || Date.now() - panelState.lastEvidence.at > panelState.OCR_REUSE_MS) {
        return null;
    }
    if (panelState.lastEvidence.geometry !== [panelState.grid.originX, panelState.grid.originY, panelState.grid.stepX, panelState.grid.stepY].join(",")) {
        return null;
    }
    const board = {
        ok: true, fromEvidence: true, ms: 0, at: panelState.lastEvidence.at,
        chars: new Array(90), glyphs: new Array(90), sides: new Array(90), scores: new Array(90),
        hits: 0, colourUnknown: 0, reused: 0, inkSkipped: 0, covered: []
    };
    const list = panelState.lastEvidence.cells || [];
    for (let k = 0; k < list.length; k++) {
        const one = list[k];
        if (!one || one.index == null || one.index < 0 || one.index > 89) {
            continue;
        }
        const i = one.index;
        board.covered.push(i);
        board.reused += 1;
        const cls = one.cls ? String(one.cls) : null;
        const side = (one.side === "RED" || one.side === "BLACK") ? one.side : null;
        board.scores[i] = one.ocrScore == null ? null : (Number(one.ocrScore) || 0);
        board.sides[i] = side || "UNKNOWN";
        let glyph = null;
        if (one.text) {
            const hit = panelState.pieceOcr.classOf(one.text);
            glyph = hit ? hit.glyph : null;
        }
        board.glyphs[i] = glyph;
        if (cls && side) {
            board.chars[i] = panelState.pieceOcr.applySide(cls, side);
            board.hits += 1;
        } else {
            board.chars[i] = null;
            if (glyph) {
                board.colourUnknown += 1;
            }
        }
    }
    return board.covered.length ? board : null;
}

function ocrBoard() {
    const session = panelActions.ocrRuntime();
    if (!session || !panelState.grid) {
        return { ok: false, unavailable: true, reason: "OCR 或几何不可用" };
    }
    if (panelState.ocrBoardCache && Date.now() - panelState.ocrBoardCacheAt < panelState.OCR_BOARD_CACHE_MS) {
        return panelState.ocrBoardCache;
    }
    // ★ 抓帧也优先用「最近一帧」（同一轮观察里的证据复用；见 FRAME_REUSE_MS）
    let grabbed = null;
    const hot = panelActions.recentFrameImage();
    if (hot) {
        panelState.frameReuseHits += 1;
        grabbed = { image: hot, path: null, channel: "reuse" };
    } else {
        grabbed = panelActions.grabFrame();
    }
    if (!grabbed || !grabbed.image) {
        return { ok: false, unavailable: true, reason: "取帧失败" };
    }
    // ★ 复用刚为同一帧读过的格子（见 `rememberPipelineEvidence`）：那些格子不再重读
    const reuse = reuseFromLastEvidence();
    // ⚠️ 必须走 board-scan：它给的是**带红黑**的字符（字形只给兵种、颜色从像素来，方案 §3.6）。
    //    以前这里直接返回 `hit.cls`（全大写）⇒ 驱动把「模型漏检、OCR 看到字」的格子全补成红方，
    //    还把错颜色登记成已知事实 + 改可靠基准（真机日志：`(0,0)空→R (1,0)空→N …` 26 格）。
    //
    // ★ 颜色按 **YOLO 定位框**采（用户口径 2026-09-20：YOLO 只管定位，颜色判断就按它定的位置来）：
    //    框心比网格交点更贴棋子真位置 —— 交界格、以及被高亮环/装饰推偏的格，按格心采样容易把
    //    木纹采进去（真机 (8,9) 黑炮被采成红就是这一类）。
    let boxes = null;
    try {
        if (!panelState.visionDetectorSession) {
            panelState.visionDetectorSession = panelState.recognizeBoard.createDetectionSession({ autoAlign: false });
        }
        const detected = panelState.pieceDetector.detect({
            detect: function (img, detectOptions) {
                const out = panelState.visionDetectorSession.detect(img, panelState.grid, detectOptions || {});
                return (out && out.ok) ? (out.raw || []) : [];
            }
        }, grabbed.image, { autoAlign: false });
        if (detected && detected.ok && detected.boxes && detected.boxes.length) {
            const mapped = panelState.pieceDetector.mapToCells(detected.boxes, panelState.grid, { autoAlign: false });
            boxes = {};
            for (let i = 0; i < mapped.cells.length; i++) {
                const one = mapped.cells[i];
                if (one && one.index != null) {
                    boxes[one.index] = { box: one.box, residual: one.residual };
                }
            }
        }
    } catch (error) {
        boxes = null;   // 拿不到框就退空格心采样（board-scan 自己会兜底）
    }
    const scanned = panelState.boardScan.scan({
        session: session,
        rgbAt: function (image, x, y) { return panelState.adapter.rgbAt(image, x, y); },
        countColor: function (image, color, options) { return panelState.adapter.countColor(image, color, options); },
        prepareRegion: function (image, region, mode) { return panelState.adapter.prepareRegion(image, region, mode); },
        releaseFrame: function (frame) { return panelState.adapter.releaseFrame(frame); }
    }, grabbed.image, panelState.grid, { boxes: boxes, reuse: reuse, ocrPrimary: panelState.ocrPrimaryMode });
    if (!scanned.ok) {
        return { ok: false, unavailable: true, reason: scanned.reason || "扫描失败" };
    }
    if (scanned.grayHits > 0 || scanned.colorHits > 0) {
        // 主读=灰度时，`colorHits` 是「灰度读不出 → 彩色兜底救回」的账；老顺序则相反
        panelActions.logLine("全盘 OCR 兜底救回 " + (scanned.grayHits + scanned.colorHits) + " 格（灰度 " +
            scanned.grayHits + " / 彩色 " + scanned.colorHits + "，共试 " +
            ((scanned.grayRetries || 0) + (scanned.colorRetries || 0)) + " 次）");
    }
    panelState.ocrBoardCache = {
        ok: true,
        chars: scanned.chars,
        glyphs: scanned.glyphs,
        sides: scanned.sides,
        scores: scanned.scores,
        hits: scanned.hits,
        colourUnknown: scanned.colourUnknown,
        grayHits: scanned.grayHits,
        grayRetries: scanned.grayRetries,
        colorHits: scanned.colorHits,
        colorRetries: scanned.colorRetries,
        /** 被「空框 + 无墨迹」闸门跳过的格子数（性能账本，2026-09-20） */
        inkSkipped: scanned.inkSkipped,
        /** 直接复用「刚为同一帧读过的逐格证据」的格子数（性能账本，2026-09-20） */
        reused: scanned.reused,
        ms: scanned.ms,
        // ★ 帧标识：驱动的「同一格连续 N 帧一致」闸门靠它区分新帧（命中缓存时它不变，
        //   所以重复问同一帧不会把计数刷上去）
        at: Date.now()
    };
    panelState.ocrBoardCacheAt = Date.now();
    return panelState.ocrBoardCache;
}

function logOcrBoardCompare() {
    // ★ 能白拿就白拿：上次识别的逐格证据还在就直接用它拼盘面（0 抓帧、0 扫描）。
    //   证据不新鲜就**跳过**（不再自己抓帧扫一遍）—— 这是调试日志，不该为它在主循环里花 ~900ms；
    //   驱动要的那次「全盘 OCR 询问」走的是 `ocrBoard()`（那条路才需要真扫没框但有墨迹的格）。
    const board = ocrBoardFromLastEvidence();
    if (!board || !board.ok) {
        panelActions.logLine("全盘 OCR 对照：跳过（没有新鲜的逐格证据，不另花一次抓帧+扫描）");
        return;
    }
    const view = panelState.driver && panelState.driver.lastResult ? panelState.driver.lastResult() : null;
    const driverChars = view && view.chars ? view.chars : null;
    /** 被覆盖的格子；只比这些，否则「没看过」会被印成「OCR 没看到」 */
    const covered = board.covered || [];
    const coveredMap = {};
    for (let k = 0; k < covered.length; k++) {
        coveredMap[covered[k]] = true;
    }
    panelActions.logLine("全盘 OCR 对照（读到 " + board.hits + " 格有字" +
        (board.colourUnknown ? ("，其中 " + board.colourUnknown + " 格颜色未定") : "") +
        "；复用上次识别的 " + covered.length + " 格逐格证据，未覆盖的格不参与比对，用时 " + board.ms + "ms）");
    panelActions.logLine("  驱动：" + (view && view.layout ? view.layout : (driverChars ? compactBoard(driverChars) : "-")));
    panelActions.logLine("  OCR ：" + compactBoard(board.glyphs));
    if (driverChars) {
        const diffs = [];
        const typeOnly = [];
        for (let i = 0; i < 90; i++) {
            if (covered && !coveredMap[i]) {
                continue;
            }
            const mine = driverChars[i] && driverChars[i] !== "?" ? String(driverChars[i]) : null;
            const theirs = board.chars[i] ? String(board.chars[i]) : null;
            if (String(mine || "").toLowerCase() === String(theirs || "").toLowerCase()) {
                continue;
            }
            const at = "(" + (i % 9) + "," + Math.floor(i / 9) + ")";
            if (!mine && theirs) {
                diffs.push(at + "OCR看到" + board.glyphs[i] + "｜驱动空");
            } else if (mine && !theirs) {
                diffs.push(at + "驱动" + driverChars[i] + "｜OCR没看到");
            } else {
                diffs.push(at + "驱动" + driverChars[i] + "｜OCR" + board.glyphs[i]);
                const sameColor = (mine === mine.toUpperCase()) === (theirs === theirs.toUpperCase());
                if (sameColor) {
                    typeOnly.push(at);
                }
            }
        }
        panelActions.logLine(diffs.length === 0
            ? "  差异：无（两边一致）"
            : "  差异 " + diffs.length + " 处：" + diffs.slice(0, 8).join("；") + (diffs.length > 8 ? " …" : ""));
        if (typeOnly.length > 0) {
            panelActions.logLine("  其中同色不同兵种（模型把炮认成兵/卒那类）：" + typeOnly.join(" "));
        }
    }
}

    return { ocrCell: ocrCell, ocrCellsNow: ocrCellsNow, ocrCellOnImage: ocrCellOnImage, compactBoard: compactBoard, rememberPipelineEvidence: rememberPipelineEvidence, reuseFromLastEvidence: reuseFromLastEvidence, ocrBoardFromLastEvidence: ocrBoardFromLastEvidence, ocrBoard: ocrBoard, logOcrBoardCompare: logOcrBoardCompare };
}

module.exports = { create: create };
