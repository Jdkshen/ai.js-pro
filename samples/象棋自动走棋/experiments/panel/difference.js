// @engine quickjs
"use strict";

/** difference：每个实例独立持有状态；跨模块动作由装配入口注入。
 * 状态字段：adapter, inkColorModule, grid, diffCache, diffPending, DIFF_RECHECK_TRIES, diffAuditCursor, DIFF_AUDIT_PER_FRAME, DIFF_MAX_CHANGES, DIFF_REANCHOR_FRAMES, DIFF_INK_DENSITY, scanRatio
 * 外部动作：logLine, ocrCellOnImage
 */


function create(panelState, panelActions) {
// `recognizeViaPipeline` 已并入 `services/board-service.js`（2026-09-20 入口收口）：
// 稳定帧判定、流水线调用、规则门、不可信回退、未知格阈值都只在服务层维护一份，
// 面板不再自己组装（曾经三处各写一遍，改一处漏两处）。

// `recognizeViaPipeline`（面板旧实现）已于 2026-09-20 删除：
// 「稳定帧判定 → 流水线 → 规则门 → 不可信回退旧路径 → 未知格阈值 → 字段归一」全部收进
// `services/board-service.js` 的 `recognizeSmart()`，面板只注入能力、画结果。

/**
 * 「这一格到底有没有字符」—— 用 OCR 直接看。
 *
 * 格子中心与裁剪尺寸的算法跟 `recognize-board.js` 里的 ocrConfirm 保持一致
 * （方向 RED_TOP 时要镜像列/行）。阈值用 piece-ocr 默认的 0.78。
 *
 * @param info { index, char }  char = 已知事实里的兵种（可空，仅用于 matches 标记）
 * @return { ok, char, glyph, score, reason }  ok=false 表示「没读到可靠汉字」
 */
/**
 * 某格在屏幕上的裁剪区域（列/行 → 像素框）。方向 RED_TOP 时要镜像列/行，
 * 算法与 `recognize-board.js` 的 ocrConfirm 保持一致。
 */
function cellRegionFor(index) {
    if (!panelState.grid || index == null) {
        return null;
    }
    const col = index % 9;
    const row = Math.floor(index / 9);
    const flip = panelState.grid.orientation === "RED_TOP";
    const cx = panelState.grid.originX + (flip ? 8 - col : col) * panelState.grid.stepX;
    const cy = panelState.grid.originY + (flip ? 9 - row : row) * panelState.grid.stepY;
    /**
     * ★ 2026-09-20 深夜（now49/now51 真机）：裁框从「0.9×格」收敛到「0.82×格 再取中心 0.70」——
     *   与驱动证据那条路（board-scan 的 cropBoxFor(0.82) + centreRegion(0.70)）**完全一致**。
     *   为什么要收：游戏给「上一步」的子画的光圈/高亮环半径 ≈48~60px，0.9×格（±51px）正好把
     *   环圈进裁框 ⇒ 逐格 OCR 把带环的子读错/读不出（真机 now51：(5,3) 的車读成「馬」、
     *   (4,8)/(0,3) 直接 '?'、连带整个局面 6~7 个未知格推不动棋），而 board-scan 的窄裁框
     *   （≈±32px，只留文字区）读得干干净净 —— 「驱动读得到、面板读不到」的最后一处差异就在这里。
     *   灰度重试（同款）也在**同一个窄框**上跑。
     */
    const size = Math.max(8, Math.round(Math.min(Math.abs(panelState.grid.stepX), Math.abs(panelState.grid.stepY)) * 0.82 * 0.70));
    return [
        Math.round(cx - size / 2), Math.round(cy - size / 2), size, size
    ];
}

/**
 * 占用扫描/找色判色的取样框：**要比 `cellRegionFor` 更小**，小到把游戏画的
 * 「选中/刚落子的红环」排除在外。
 *
 * 真机实测（K40，2026-09-20，`.artifacts/tools/ring-probe.py` / `ring-impact.py`）：
 *   · 那个环是**细红圆环**，半径 ≈48px（棋子外沿）——它**不在**判色框（ 0.72×棋子）里，
 *     所以对「红/黑墨迹判色」没有影响（带环红炮 红999/黑0 vs 不带环 红1028/黑0）；
 *   · 但旧的扫描框是 0.9×格 ≈ ±51px ⇒ **环被扫进去了** ⇒ 「只有环、子还在飞」的那一帧
 *     会被扫成「有子」，随后 OCR 认不出 ⇒ 一个多余的 `?`；
 *   · 0.6×格 = ±34px（< 环半径 48）刚好把环排除，而棋子自己的墨迹还剩 600~1300 点
 *     （密度 ≈0.13，远高于门槛 0.02）⇒ 判定依旧稳。
 * 可以用 autorun 的 `scanInner: false` 回到旧行为做 A/B。
 */
function scanRegionFor(index) {
    if (!panelState.grid || index == null) {
        return null;
    }
    const col = index % 9;
    const row = Math.floor(index / 9);
    const flip = panelState.grid.orientation === "RED_TOP";
    const cx = panelState.grid.originX + (flip ? 8 - col : col) * panelState.grid.stepX;
    const cy = panelState.grid.originY + (flip ? 9 - row : row) * panelState.grid.stepY;
    const size = Math.max(8, Math.round(Math.min(Math.abs(panelState.grid.stepX), Math.abs(panelState.grid.stepY)) * panelState.scanRatio));
    return [
        Math.round(cx - size / 2), Math.round(cy - size / 2), size, size
    ];
}

function inkLooksEmpty(ink) {
    // ★ 2026-09-21（d7 黑士漏读案）：交给 ink-color.looksEmpty 统一判 —— 「红/黑墨迹 **或** 暗墨迹」
    //   双证都低于门槛才算空。细笔画黑子（士/炮/象）的找色密度只有 5.4~8%（压 6% 线），
    //   裁剪中心偏 ~28px 就掉到 5.8% 被误判为空；暗墨迹口径同格 8~13%、空格 0% —— 风险清零。
    return panelState.inkColorModule.looksEmpty(ink);
}

function diffGeometryKey() {
    return panelState.grid ? [panelState.grid.originX, panelState.grid.originY, panelState.grid.stepX, panelState.grid.stepY].join(",") : "";
}

/** 逐格「有子/空格 + 哪一边」扫描（纯找色：每格两次原生 findAllPointsForColor，不跑模型、不 OCR）。 */
function occupancyScan(image) {
    const map = new Array(90);
    let scanned = 0;
    const started = Date.now();
    for (let i = 0; i < 90; i++) {
        const region = scanRegionFor(i);
        if (!region) {
            map[i] = "?";
            continue;
        }
        const counts = panelState.inkColorModule.countsFor({ countColor: panelState.adapter.countColor }, image,
            { x: region[0], y: region[1], width: region[2], height: region[3] }, {});
        if (!counts || counts.source !== "findColor" || !(counts.area > 0)) {
            map[i] = "?";
            continue;
        }
        const red = counts.red / counts.area;
        const black = counts.black / counts.area;
        scanned += 1;
        if (red >= panelState.DIFF_INK_DENSITY || black >= panelState.DIFF_INK_DENSITY) {
            map[i] = red >= black ? "R" : "B";
        } else {
            map[i] = ".";
        }
    }
    return { map: map, ms: Date.now() - started, scanned: scanned };
}

/** 把一次**全盘**识别的结果变成差分锚点（用它那一帧扫一份占用图）。 */
function seedDiffCache(smart) {
    if (!panelState.grid || !smart || !smart.ok || !smart.chars || !smart.frame || !smart.frame.image) {
        return;
    }
    try {
        const scan = occupancyScan(smart.frame.image);
        if (scan.scanned < 40) {
            panelState.diffCache = null;
            panelState.diffPending = [];
            return;
        }
        const board = smart.chars.slice();
        /**
         * ★ 2026-09-20 深夜（now52 真机）：**重锚不许把「占用没变」的格子重读**——
         *   全盘那一份是**两帧合并**的读（不一致的格子会被降级成 '?'），而带「上一步光圈」的格子
         *   在两帧间本来就会轻微变化 ⇒ 每次重锚都打出 6~7 个 '?'，把刚养好的缓存打回未知，
         *   驱动 4s 看门狗连炸、永远走不出恢复（真机：每次重锚后 6~7 未知 → 慢慢收敛到 1 → 又重锚…）。
         *   差分的老规矩本来就是「只读变化格」——重锚（防漂移）只管**占用图**，
         *   占用没变的格子沿用上一版读数（那是单帧逐格读的，比合并帧可靠）。
         *   错值的兜底另有两条路：巡检（每帧 2 格轮着复核）+ 驱动证据改正。
         */
        const keptFlags = new Array(90);
        let kept = 0;
        if (panelState.diffCache && panelState.diffCache.geometry === diffGeometryKey() &&
            panelState.diffCache.occupancy && panelState.diffCache.board) {
            for (let i = 0; i < 90; i++) {
                const oldOcc = panelState.diffCache.occupancy[i];
                const newOcc = scan.map[i];
                if (oldOcc !== "?" && newOcc !== "?" && oldOcc === newOcc && panelState.diffCache.board[i] !== "?") {
                    board[i] = panelState.diffCache.board[i];
                    keptFlags[i] = true;
                    kept += 1;
                }
            }
            if (kept > 0) {
                panelActions.logLine("差分锚点：沿用上一版读数 " + kept + " 格（占用没变的格子不重读，防合并帧噪音）");
            }
        }
        // ★ 锚点盘面里的**未知格先用「OCR+找色」证据补掉**（2026-09-20 真机踩到）：
        //   不然这些 '?' 会被差分**一直背着**（只有那一格再变化才会被重读），
        //   而驱动拿到带 '?' 的盘面就只能猜 —— 现场就是「对方应手那两格被猜成两个位置都有馬」
        //   → 轮次推不出来 → 卡在等对方。
        let filled = 0;
        for (let i = 0; i < 90; i++) {
            if (board[i] !== "?") {
                continue;
            }
            const one = panelActions.ocrCellOnImage(smart.frame.image, i);
            if (!one || !one.ok || !one.cls || (one.side !== "RED" && one.side !== "BLACK")) {
                continue;
            }
            const upper = String(one.cls).toUpperCase();
            board[i] = one.side === "RED" ? upper : upper.toLowerCase();
            filled += 1;
        }
        if (filled > 0) {
            panelActions.logLine("差分锚点：未知格用 OCR+找色补齐 " + filled + " 格");
        }
        // ★ 锚点落地前**逐格复核有子的格**（见 diffAuditCursor 注释的 now43 现场）：
        //   全盘流水线读错的格子在这里被逐格 OCR 换掉，错值就进不了缓存。
        const healed = [];
        for (let i = 0; i < 90; i++) {
            if (keptFlags[i]) {
                continue;   // 沿用上一版读数的格子不复核（复核用合并帧，越复核越糟）
            }
            if (scan.map[i] === ".") {
                // 找色说没墨迹、流水线却有子 ⇒ 幻影脏值，直接清掉（下一帧真变了会被差分重新读）
                if (board[i] != null && board[i] !== "?") {
                    if (healed.length < 4) {
                        healed.push("(" + (i % 9) + "," + Math.floor(i / 9) + ")" + board[i] + "→空");
                    }
                    board[i] = null;
                }
                continue;
            }
            if (scan.map[i] === "?" || board[i] === "?") {
                continue;              // 扫不清/'?' 刚在上一支补过
            }
            const one = panelActions.ocrCellOnImage(smart.frame.image, i);
            if (!one || !one.ok || !one.cls || (one.side !== "RED" && one.side !== "BLACK")) {
                // ★ 判空按**墨迹密度**（见 inkLooksEmpty）：旧的像素级阈值会把星光标记辉光当墨迹
                if (inkLooksEmpty(one && one.ink)) {
                    if (healed.length < 4) {
                        healed.push("(" + (i % 9) + "," + Math.floor(i / 9) + ")" + board[i] + "→空");
                    }
                    board[i] = null;
                }
                continue;
            }
            const upper = String(one.cls).toUpperCase();
            const value = one.side === "RED" ? upper : upper.toLowerCase();
            if (value !== board[i]) {
                if (healed.length < 4) {
                    healed.push("(" + (i % 9) + "," + Math.floor(i / 9) + ")" + board[i] + "→" + value);
                }
                board[i] = value;
            }
        }
        if (healed.length > 0) {
            panelActions.logLine("差分锚点：全盘读值被逐格 OCR 纠正（" + healed.join("；") + "）");
        }
        panelState.diffCache = {
            geometry: diffGeometryKey(),
            board: board,
            occupancy: scan.map,
            at: Date.now(),
            frames: 0
        };
        panelState.diffPending = [];   // 新锚点 = 全盘重读，旧的「复读名单」作废
        panelState.diffAuditCursor = 0;   // 巡检从头开始
    } catch (error) {
        panelState.diffCache = null;
        panelState.diffPending = [];
    }
}

/** 差分一帧；不放心返回 null（调用方退回全盘流水线）。 */
function recognizeDiffFrame(grabbed) {
    if (!panelState.diffCache || panelState.diffCache.geometry !== diffGeometryKey()) {
        return null;
    }
    if (panelState.diffCache.frames >= panelState.DIFF_REANCHOR_FRAMES) {
        return null;   // 定期回全盘对账
    }
    const scan = occupancyScan(grabbed.image);
    if (scan.scanned < 40) {
        return null;   // 找色大面积不可用：退回全盘
    }
    const changed = [];
    for (let i = 0; i < 90; i++) {
        if (scan.map[i] === "?") {
            continue;
        }
        if (scan.map[i] !== panelState.diffCache.occupancy[i]) {
            changed.push(i);
        }
    }
    if (changed.length > panelState.DIFF_MAX_CHANGES) {
        return null;
    }
    const board = panelState.diffCache.board.slice();
    // ── 落点复读（见 diffPending 注释）：把前几帧「刚变化」的格子，在**动画结束后**再读一次 ──
    for (let p = panelState.diffPending.length - 1; p >= 0; p--) {
        const item = panelState.diffPending[p];
        if (changed.indexOf(item.i) >= 0) {
            continue;                       // 这一帧又在变：交给下面变化格处理
        }
        if (scan.map[item.i] === "?") {
            item.tries += 1;
            if (item.tries >= panelState.DIFF_RECHECK_TRIES) {
                panelState.diffPending.splice(p, 1);
            }
            continue;                       // 扫不清：下一帧再看
        }
        if (scan.map[item.i] === ".") {
            board[item.i] = null;           // 确实空了（复读只做确认）
            panelState.diffPending.splice(p, 1);
            continue;
        }
        const again = panelActions.ocrCellOnImage(grabbed.image, item.i);
        if (!again || !again.ok || !again.cls || (again.side !== "RED" && again.side !== "BLACK")) {
            // ★ 读不出「字」但**也没有棋子级墨迹** ⇒ 这格是空的（缓存里那个子是幻影/脏值）——
            //   真机 now46 的 `(1,4)n→空` 幻影循环死在这条上（旧逻辑把「读不出」一律当跳过）；
            //   真机 now60：起点格 (7,2) 的**白色星光标记辉光**被找色计成红墨迹（≈4.2% 密度、
            //   ~170 像素 > 旧的绝对阈值 5）⇒ 判不了空 ⇒ '?' 背死 ⇒ 驱动填旧值造出「黑炮 3 枚」。
            //   改成按密度判（见 inkLooksEmpty）。
            if (inkLooksEmpty(again && again.ink)) {
                if (board[item.i] != null && board[item.i] !== "?") {
                    panelActions.logLine("差分复读：(" + (item.i % 9) + "," + Math.floor(item.i / 9) + ") " +
                        board[item.i] + " → 空（那格没有墨迹）");
                }
                board[item.i] = null;
                panelState.diffPending.splice(p, 1);
                continue;
            }
            item.tries += 1;
            if (item.tries >= panelState.DIFF_RECHECK_TRIES) {
                panelState.diffPending.splice(p, 1);   // 认不出就放弃（不阻塞这一帧）
            }
            continue;
        }
        const againUpper = String(again.cls).toUpperCase();
        const againValue = again.side === "RED" ? againUpper : againUpper.toLowerCase();
        if (board[item.i] !== againValue) {
            panelActions.logLine("差分复读：(" + (item.i % 9) + "," + Math.floor(item.i / 9) + ") " +
                (board[item.i] == null ? "空" : board[item.i]) + " → " + againValue +
                "（缓存与稳定帧读数不符，已纠正）");
            board[item.i] = againValue;
        }
        panelState.diffPending.splice(p, 1);           // 复读完成（确认一致或已纠正），出名单
    }
    // ★ 锚点里的**未知格每帧顺手重认一次**（2026-09-20 真机踩到）：
    //   差分只重读「变化格」，未知格自己不动就永远读不出来 → 驱动只能猜。
    //   重认失败**不**当错误（那格本来就没动，维持 '?' 交给驱动自己的证据链）。
    let recheck = 0;
    for (let i = 0; i < 90; i++) {
        if (board[i] !== "?" || changed.indexOf(i) >= 0) {
            continue;
        }
        recheck += 1;
        if (recheck > 4) {
            break;   // 一帧最多补 4 格，别把单帧时间拖长
        }
        const one = panelActions.ocrCellOnImage(grabbed.image, i);
        if (!one || !one.ok || !one.cls || (one.side !== "RED" && one.side !== "BLACK")) {
            continue;
        }
        const upper = String(one.cls).toUpperCase();
        board[i] = one.side === "RED" ? upper : upper.toLowerCase();
    }
    for (let k = 0; k < changed.length; k++) {
        const i = changed[k];
        // ★ 变化格当场读完先别完全信：排进「复读名单」，下一帧稳定后再确认（见 diffPending 注释）
        let queued = false;
        for (let q = 0; q < panelState.diffPending.length; q++) {
            if (panelState.diffPending[q].i === i) {
                panelState.diffPending[q].tries = 0;
                queued = true;
                break;
            }
        }
        if (!queued) {
            panelState.diffPending.push({ i: i, tries: 0 });
        }
        if (scan.map[i] === ".") {
            board[i] = null;    // 这格空了
            continue;
        }
        const one = panelActions.ocrCellOnImage(grabbed.image, i);
        if (!one || !one.ok || !one.cls || (one.side !== "RED" && one.side !== "BLACK")) {
            return null;        // 变化格认不出兵种/阵营：这一帧不冒险
        }
        const upper = String(one.cls).toUpperCase();
        board[i] = one.side === "RED" ? upper : upper.toLowerCase();
    }
    // ── 缓存巡检（低强度）：每帧挑 2 格（只查“有子”的）与缓存对一遍 ──
    //   不符**不立即采信**（可能又撞上走子动画帧）：排进复读名单，下一帧再读一次，
    //   两帧读数一致才纠正（走稳定的 pending 路）；一致不了就自动丢弃。
    for (let a = 0, checked = 0; a < 90 && checked < panelState.DIFF_AUDIT_PER_FRAME; a++) {
        const i = panelState.diffAuditCursor;
        panelState.diffAuditCursor = (panelState.diffAuditCursor + 1) % 90;
        if (changed.indexOf(i) >= 0 || scan.map[i] === "?" || board[i] === "?") {
            continue;
        }
        let inPending = false;
        for (let q = 0; q < panelState.diffPending.length; q++) {
            if (panelState.diffPending[q].i === i) {
                inPending = true;
                break;
            }
        }
        if (inPending) {
            continue;
        }
        if (scan.map[i] === ".") {
            // ★ 扫出来没墨迹、缓存里却有子 ⇒ 幻影（真机 now46 的 (1,4)n 就是这么清了 66 次）：
            //   推给复读名单，下一帧复读确认后清掉（复读里已有「没墨迹 ⇒ 置空」那条）。
            if (board[i] != null) {
                panelState.diffPending.push({ i: i, tries: 0 });
            }
            continue;
        }
        checked += 1;
        const one = panelActions.ocrCellOnImage(grabbed.image, i);
        if (!one || !one.ok || !one.cls || (one.side !== "RED" && one.side !== "BLACK")) {
            continue;
        }
        const upper = String(one.cls).toUpperCase();
        const value = one.side === "RED" ? upper : upper.toLowerCase();
        if (value !== board[i]) {
            panelState.diffPending.push({ i: i, tries: 0 });
        }
    }
    return { board: board, occupancy: scan.map, changed: changed, scan: scan };
}

    return { cellRegionFor: cellRegionFor, scanRegionFor: scanRegionFor, inkLooksEmpty: inkLooksEmpty, diffGeometryKey: diffGeometryKey, occupancyScan: occupancyScan, seedDiffCache: seedDiffCache, recognizeDiffFrame: recognizeDiffFrame };
}

module.exports = { create: create };
