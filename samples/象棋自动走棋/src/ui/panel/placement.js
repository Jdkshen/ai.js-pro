// @engine quickjs
"use strict";

/** placement：每个实例独立持有状态；跨模块动作由装配入口注入。
 * 状态通过参数显式共享；跨模块调用通过动作表，模块间不循环 require。
 */

function create(panelState, panelActions) {
    function panelRect() {
        try {
            const panel = panelActions.activePanelWindow();
            const x = panel.getX(true);
            const y = panel.getY(true);
            if (!isFinite(x) || !isFinite(y)) {
                return null;
            }
            return {
                x: x,
                y: y,
                width: panelState.collapsed ? panelState.COMPACT_W : panelState.PANEL_W,
                height: panelState.collapsed ? panelState.COMPACT_H : panelState.PANEL_H
            };
        } catch (error) {
            return null;
        }
    }

    /** 棋盘在屏幕上的矩形（用已校准几何算外框）。 */
    function boardRect() {
        if (!panelState.grid) {
            return null;
        }
        try {
            const box = panelState.geometryModule.boundsOf(panelState.grid, 0);
            return { x: box.x, y: box.y, width: box.width, height: box.height };
        } catch (error) {
            return null;
        }
    }

    function rectsOverlap(a, b) {
        if (!a || !b) {
            return null; // 不知道
        }
        return !(a.x + a.width <= b.x || b.x + b.width <= a.x || a.y + a.height <= b.y || b.y + b.height <= a.y);
    }

    /**
     * 面板的「安全带」（用户口径：**不许挡棋盘、不许挡底部菜单**）。
     *
     * 两条边界都按屏幕高分比例算（换机型不用改代码）：
     *   · 上界 `safeTopRatio`：避开状态栏 + 那条系统「指针位置」调试浮层（K40 实测 y≈120–160）。
     *     不避的话，面板顶栏那排按钮就被浮层盖住（用户截图就是这个问题）。
     *   · 下界 `menuTopRatio`：游戏底部「菜单/显示分析/悔棋/提示」带的上沿（K40 实测 ~2235）。
     *     ⚠️ 这是**硬约束**：挡住了那排就点不到（用户特别强调）。
     *
     * 控制面板装不下整条安全带时以**上界优先**（宁可压住棋盘顶行，也要看得见、点得到）；
     * 日志窗的两条边都尽量满足（见 `snapLogWindowBelowBoard`）。
     */
    function panelSafeBand(size) {
        const h = size && size.height > 0 ? size.height : 2400;
        const top = Math.max(panelActions.statusBarHeight() + 4, Math.round(h * panelState.PANEL.safeTopRatio));
        const bottom = Math.round(h * panelState.PANEL.menuTopRatio) - panelState.PANEL.menuGap;
        return { top: top, bottom: bottom };
    }

    /** 把 y 夹进安全带（见 panelSafeBand）：先夹下界（不许挡底部菜单），再抬到上界。 */
    function clampPanelY(y, size, height) {
        const band = panelSafeBand(size);
        let out = Math.round(y);
        out = Math.min(
            out,
            band.bottom - (height || (panelState.collapsed ? panelState.COMPACT_H : panelState.PANEL_H))
        );
        if (out < band.top) {
            out = band.top;
        }
        return out;
    }

    /**
     * 日志窗落位：塞进「棋盘含子底线」与「底部菜单带上沿」之间那条空带。
     * K40 实测（2026-09-20 截图）：棋盘含子底线 ~1777、菜单带顶 ~2235 ⇒ 空带 ~458px，装得下 420 高的日志窗。
     *
     * 取位口径（用户要求「不要挡住棋盘」）：
     *   · 默认尽量**往下**贴菜单上沿（y = band.bottom - LOG_H）—— 顶边离棋盘越远越好；
     *   · 棋盘已知时**绝不压棋盘**：顶边 ≥ 棋盘矩形底 + 62（棋子圆盘/木框）+ 14 呼吸；
     *   · 真放不下（空带比窗口还窄）时也让棋盘优先（那次退让只吃菜单余量，不碰棋盘）。
     * K40 结果：y=1802 —— 上留 ~25px、下离菜单带 ~13px。
     */
    function snapLogWindowBelowBoard(size) {
        if (!panelState.logWin || !panelState.snapPanelEnabled || panelState.logPanelUserMoved) {
            return;
        }
        try {
            const band = panelSafeBand(size);
            let y = band.bottom - panelState.LOG_H;
            const box = panelState.grid ? panelState.geometryModule.boundsOf(panelState.grid, 0) : null;
            if (box && isFinite(box.y) && isFinite(box.height)) {
                const boardClear = Math.round(box.y + box.height + 62) + 14; // 棋盘底线 + 棋子圆盘 + 14px 呼吸
                if (y < boardClear) {
                    y = boardClear; // 空带太窄：宁可贴近菜单，也不压棋盘
                }
            }
            if (y < band.top) {
                y = band.top;
            }
            let cur = null;
            try {
                cur = panelState.logWin.getY(true);
            } catch (error) {
                cur = null;
            }
            if (panelState.logPanelY !== null && isFinite(cur) && Math.abs(cur - panelState.logPanelY) > 24) {
                panelState.logPanelUserMoved = true; // 用户拖过日志窗 → 不再抢
                return;
            }
            if (panelState.logPanelY !== null && Math.abs(y - panelState.logPanelY) < 6) {
                panelState.logPanelY = y;
                return;
            }
            let x = panelState.PANEL.marginX;
            try {
                const curX = panelState.logWin.getX(true);
                if (isFinite(curX)) {
                    x = curX;
                }
            } catch (error) {
                // 用默认 x
            }
            panelState.logWin.setPosition(x, y);
            panelState.logPanelY = y;
            if (panelState.lastLogSnapNote !== y) {
                panelState.lastLogSnapNote = y;
                panelActions.logLine("日志窗已落在棋盘与底部菜单之间（y=" + y + "，上不挡棋盘、下不挡菜单）");
            }
        } catch (error) {
            // 拿不到就保持原样
        }
    }

    /**
     * 把面板摆到**棋盘上沿之上那条空带**里 —— 真机（天天象棋 1080×2400）实测：
     *   状态栏 0..80｜对手头像+名字 137..269｜**空木纹带 280..645**｜棋盘（含子上沿）647..1620｜
     *   空档 1620..1890｜「6步」+我方头像 1900..2010｜菜单/显示分析/悔棋/提示 2235..2400
     * 所以面板（PANEL.height）会自动往上贴：把整个面板塞在盘面（647）之上，既**不挡棋子**也不挡底部按钮排。
     * 用户自己拖过（当前 y 与我们上次设的差得远）就不再抢；定位到棋盘之前先用屏幕高比例兜底。
     */
    function snapPanelAboveBoard() {
        if (!panelState.snapPanelEnabled) {
            return; // autorun 里关了自动贴板：面板就停在你放的位置
        }
        if (panelState.panelUserMoved) {
            return; // 用户拖过：永久不抢（以前只看 24px 启发式，抖一下就会被抢回去）
        }
        try {
            const size = panelState.adapter.screenSize();
            // 兜底位置 = 屏幕高 defaultTopRatio（K40: 2400×0.10 = 240）——真机实测是棋盘上方那条空带。
            // ⚠️ 这个比例是按 **372 高**调的；面板变高后要等量上移，否则起始几帧会压住棋盘顶行，
            //    而「压住 ⇒ 抓帧时藏面板」在部分 ROM 上恢复不可靠（真机踩过：面板再也回不来）。
            // ⚠️ 2026-09-25：0.11（264）那档底边 620+ 正好压到棋盘含子上沿（~635）→ 降一档到 0.10。
            const baseY = size && size.height > 0 ? Math.round(size.height * panelState.PANEL.defaultTopRatio) : 240;
            const activeHeight = panelState.collapsed ? panelState.COMPACT_H : panelState.PANEL_H;
            let y = baseY - Math.max(0, activeHeight - 372);
            const box = panelState.grid ? panelState.geometryModule.boundsOf(panelState.grid, 0) : null;
            if (box && isFinite(box.y)) {
                // 棋子圆盘比「第一条线」高出约 55~60px（半径 + 边距）→ 面板底边要留出这段 + boardClearGap 硬缝。
                // ⚠️ 这里只把面板**往上抬**（Math.min）——优先不挡棋盘；抬不上去（会被状态栏/浮层盖住）时
                //    由下面的 clampPanelY 接管：允许压棋盘，但绝不允许挡底部菜单。
                // ⚠️ 2026-09-25：旧缝只留 10px，网格/棋盘位置一抖就压上棋盘上沿（用户报「挡住几何定位盘」）——
                //    「压住」还会连带每帧抓帧藏面板（慢）。缝给足 PANEL.boardClearGap（24px）。
                y = Math.min(y, Math.round(box.y - 62 - activeHeight - panelState.PANEL.boardClearGap));
            }
            y = clampPanelY(y, size);
            let cur = null;
            try {
                cur = panelActions.activePanelWindow().getY(true);
            } catch (error) {
                cur = null;
            }
            if (panelState.panelY !== null && isFinite(cur) && Math.abs(cur - panelState.panelY) > 24) {
                return; // 用户拖过面板（原生拖动也会改变 y）→ 尊重用户的选择
            }
            if (panelState.panelY !== null && Math.abs(y - panelState.panelY) < 6) {
                panelState.panelY = y;
                return; // 没实质变化就不动窗口（少一次 updateViewLayout）
            }
            let x = 30;
            try {
                const curX = panelActions.activePanelWindow().getX(true);
                if (isFinite(curX)) {
                    x = curX;
                }
            } catch (error) {
                // 用默认 x
            }
            panelActions.activePanelWindow().setPosition(x, y);
            panelState.panelY = y;
            panelState.panelMoveCounts.snap += 1;
            let gapNote = "";
            if (box && isFinite(box.y)) {
                const gapNow = Math.round(box.y - 62 - (y + activeHeight));
                gapNote =
                    gapNow >= 0
                        ? "，底边离棋盘 " + gapNow + "px"
                        : "，⚠ 底边压棋盘 " + -gapNow + "px（上方放不下，可见优先）";
            }
            panelActions.logLine("面板已贴到安全带内（y=" + y + gapNote + "，不挡底部菜单）");
            snapLogWindowBelowBoard(size);
        } catch (error) {
            // 拿不到就保持原样
        }
    }

    /** 普通抓帧只检查棋盘；异常盘面复核结算页时才检查底部按钮。 */
    function captureHideTargets(includeFooter, force) {
        const targets = { panel: false, log: false };
        // 隔离截图不包含覆盖窗，定位的强制干净帧也不能覆盖这一条。
        if (panelState.adapter.captureBackend() === "window") return targets;
        const hasLog = !!panelState.logWin && panelState.logWindowEnabled !== false && !panelState.collapsed;
        if (force === true || panelState.hideOnCapture === true) return { panel: true, log: hasLog };
        if (panelState.hideOnCapture === false) return targets;
        const panel = panelRect(), board = boardRect(), own = [panel];
        let log = null;
        if (hasLog) {
            try {
                log = { x:panelState.logWin.getX(true), y:panelState.logWin.getY(true),
                    width:panelState.PANEL_W, height:panelState.LOG_H };
            } catch (error) { /* 未知日志位置保守暂避，但不连带操作窗。 */ }
            own.push(log);
        }
        if (board && !panelState.captureRectsNoted && panelActions.logLine) {
            panelState.captureRectsNoted = true;
            panelActions.logLine("截图窗口检查：" + JSON.stringify({ own:own, board:board, collapsed:panelState.collapsed, logEnabled:panelState.logWindowEnabled }));
        }
        // 几何未知时保守暂避，定位不能把面板边缘当作棋盘上沿。
        targets.panel = rectsOverlap(panel, board) !== false;
        targets.log = hasLog && rectsOverlap(log, board) !== false;
        if (!includeFooter) return targets;
        // 日志窗与结算检测区交叠不影响正常棋盘识别，不应因此每帧藏显两个窗口。
        try {
            const size = panelState.adapter.screenSize(), y = Math.floor(size.height * 0.84);
            const footer = { x:0, y:y, width:size.width, height:size.height-y };
            targets.panel = targets.panel || rectsOverlap(panel, footer) === true;
            targets.log = targets.log || (hasLog && rectsOverlap(log, footer) === true);
        } catch (error) { /* 棋盘遮挡检查结果仍有效。 */ }
        return targets;
    }

    function needHideForCapture(includeFooter) {
        const targets = captureHideTargets(includeFooter);
        return targets.panel || targets.log;
    }

    /**
     * autorun 自动定位后的「几何验证」：字形真读一帧，读出乱局（规则门不过）⇒ 判定位不可信。
     * ⚠️ 2026-09-24 真机事故：`pickGrid` 兜底在中局盘上交付过偏移 (6,-92) 的**假几何**
     *   （字形全线读串：24 子・23 车・帅将 0），面板却当「定位成功」直接开跑 ⇒ 用户看到「不走了」。
     *   宁可不自动开始（让用户手动定位），也不能拿假几何跑。只验一帧，成本 ~300ms。
     * @return true = 几何可信（或全是动画帧，无法判定）；false = 首帧就说局面不可信
     */
    function verifyAutorunGeometry() {
        if (!panelState.grid) {
            panelActions.logLine("几何验证：尚未定位到棋盘；请先切到游戏画面，再点「定位校准」");
            return false;
        }
        let service = null;
        try {
            service = panelActions.ensureBoardService();
        } catch (error) {
            panelActions.logLine("几何验证：棋盘服务初始化失败 —— " + error);
            return false;
        }
        /**
         * ⚠️ 2026-09-24 真机（面板重启后不自启，两次：11:34 / 11:46）：切前台之后的**前两帧**
         *   在稳定帧门那里都算「不稳定」（第一帧预热、第二帧才攒够比对），旧逻辑重试 1 次就判
         *   「几何可疑」→ autorun 拒启、面板停在「未运行」等人手点「开始」。
         *   帧不稳定 = **没判过**（不是判过了不可信）⇒ 多等几帧；只有**稳定帧读出乱局**才拒
         *   （防的正是 pickGrid 假几何那种“稳定地读错”）。
         */
        for (let i = 0; i < 5; i++) {
            let probe = null;
            try {
                probe = service.recognizeSmart({});
            } catch (error) {
                probe = { ok: false, reason: String(error) };
            }
            if (probe && probe.ok) {
                return true;
            }
            if (probe && probe.unstable) {
                sleep(i === 0 ? 300 : 220); // 等一帧（切换前台/动画过渡）
                continue;
            }
            panelActions.logLine("几何验证：识别不过 —— " + ((probe && probe.reason) || "未知"));
            return false;
        }
        // 连续几帧都只是「没稳定」：无法判定 ⇒ 不拦（交给驱动自己的等稳定/看门狗）
        panelActions.logLine("几何验证：连续 5 帧都没稳定（切换/动画中）→ 不拦自动开始");
        return true;
    }

    return {
        panelRect: panelRect,
        boardRect: boardRect,
        rectsOverlap: rectsOverlap,
        panelSafeBand: panelSafeBand,
        clampPanelY: clampPanelY,
        snapLogWindowBelowBoard: snapLogWindowBelowBoard,
        snapPanelAboveBoard: snapPanelAboveBoard,
        captureHideTargets: captureHideTargets,
        needHideForCapture: needHideForCapture,
        verifyAutorunGeometry: verifyAutorunGeometry
    };
}

module.exports = { create: create };
