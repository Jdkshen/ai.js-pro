// @engine quickjs
"use strict";

/** status：每个实例独立持有状态；跨模块动作由装配入口注入。
 * 状态通过参数显式共享；跨模块调用通过动作表，模块间不循环 require。
 */

function create(panelState, panelActions) {
    /** 状态栏里「页面」那一段（面板窄，只能给最少的字）。 */
    function otherLineShort() {
        const other = panelState.bus.readOther();
        if (!other || !other.state) {
            return "页面：未启动";
        }
        if (!other.fresh) {
            return "页面：离线";
        }
        // A1 批次2：和页面侧同一口径 —— 运行态｜几何｜引擎｜模式（面板窄，先放最重要的，
        // 超宽由 statusLine 截断；实测模式名放前面会把几何/引擎直接截掉）
        return (
            "页面：" +
            (other.state.running ? "跑中" : "未开始") +
            "｜" +
            (other.state.gridVersion == null ? "—" : "v" + other.state.gridVersion) +
            "｜" +
            (other.state.backend === "pikafish" || other.state.backend === "auto" ? "Pikafish" : "?") +
            "｜" +
            String(panelState.MODE_LABEL[other.state.mode] || "?").replace(/\s/g, "")
        );
    }

    /** 状态栏文字（心跳里每秒刷一次，写成一个函数免得两处不同步）。 */
    function statusLine() {
        const note = String(panelState.calibrationNote || "未定位");
        const fg =
            panelState.lastForeground == null
                ? "?"
                : panelState.lastForeground === panelState.gamePackage
                  ? "游戏✓"
                  : panelState.lastForeground === panelState.OWN_PACKAGE
                    ? "自身✗"
                    : panelState.lastForeground;
        return (
            note +
            "｜" +
            (panelState.starting ? "启动中" : panelState.running ? "运行中" : "已停止") +
            "｜" +
            panelState.aliveSeconds +
            "s｜前台 " +
            fg +
            "｜" +
            otherLineShort()
        );
    }

    /** 从一行日志里更新进度摘要（识别不了就忽略）。 */
    function noteProgress(line) {
        let m = null;
        if (line.indexOf("恢复：重新定位棋盘") >= 0) {
            panelState.progress.recover += 1;
            return;
        }
        m = line.match(/本轮观察：未知格 ([\d?]+)/);
        if (m) {
            panelState.progress.unknown = m[1];
            return;
        }
        if (line.indexOf("局面是标准开局") >= 0) {
            panelState.progress.turn = "标准开局（红先）";
            return;
        }
        m = line.match(/轮次推断：(.+)$/);
        if (m) {
            panelState.progress.turn = m[1];
            return;
        }
        m = line.match(/算棋：(.+)$/);
        if (m) {
            panelState.progress.think = m[1]
                .replace(/（深度/, " d")
                .replace(/，评分/, " ")
                .replace(/）/g, "");
            return;
        }
        m = line.match(/建议着法 (.+?)（.*?）：评分 (.+?)，深度 (.+)$/);
        if (m) {
            panelState.progress.think = "建议 " + m[1] + " " + m[2] + " d" + m[3];
            return;
        }
        m = line.match(/落点（([\d,]+)）→（([\d,]+)）(.*)$/);
        if (m) {
            panelState.progress.point = m[1] + "→" + m[2] + (m[3] ? "［中心］" : "［交点］");
            const pair = function (text) {
                const parts = String(text).split(",");
                return { x: Number(parts[0]), y: Number(parts[1]) };
            };
            panelState.lastClickPlan = { from: pair(m[1]), to: pair(m[2]), move: "" };
            for (let i = panelState.logLines.length - 1; i >= 0 && !panelState.lastClickPlan.move; i--) {
                const hit = String(panelState.logLines[i]).match(/点击：([a-i][0-9][a-i][0-9])/);
                if (hit) {
                    panelState.lastClickPlan.move = hit[1];
                }
            }
            return;
        }
        m = line.match(/确认：(\w+)：(.+)$/);
        if (m) {
            panelState.progress.confirm = m[1] + "（" + String(m[2]) + "）";
            return;
        }
        m = line.match(/按已知事实补齐：(.+)$/);
        if (m) {
            panelState.progress.fill = m[1];
        }
    }

    /** 状态 → 进度行颜色（用有符号 32 位 int，Java 那边要的不是 JS 的大整数）。 */
    /**
     * 状态行颜色。**必须返回字符串颜色**：
     * 引擎的 `setTextColor` 走 `writeViewAttribute`，把值当字符串交给 `Color.parseColor`；
     * 传颜色整数会被转成 "-670357"，直接报 `Cannot set view attribute textColor=-670357 Unknown color`
     *（真机日志就这么来的）。字符串 `#RRGGBB` 在所有引擎版本上都安全。
     */
    function stepColor() {
        if (panelState.startError) return "#B5303C";
        if (panelState.starting) return "#2364BE";
        let state = "";
        try {
            state = panelState.driver && panelState.driver.machine ? panelState.driver.machine.state() : "";
        } catch (error) {
            state = "";
        }
        if (state === "RECOVERING") {
            return "#B5303C"; // 红：在恢复，得管
        }
        if (state === "WAIT_TURN" || state === "WAIT_STABLE") {
            return "#986018"; // 黄：在等（正常但停滞）
        }
        if (
            panelState.running &&
            (state === "THINKING" ||
                state === "CLICKING" ||
                state === "VERIFYING" ||
                state === "SUGGESTED" ||
                state === "PRECHECK")
        ) {
            return "#217448"; // 绿：在干活 / 已有结论
        }
        return "#586575"; // 灰：没跑
    }

    /** 状态对应的图标（纯文本，不依赖字体库；颜色靠运行期换色，失败也不影响使用）。 */
    function stateIcon(state) {
        if (state === "RECOVERING") {
            return "⚠";
        }
        if (state === "WAIT_TURN" || state === "WAIT_STABLE") {
            return "⋯";
        }
        if (state === "SUGGESTED") {
            return "★";
        }
        if (state === "THINKING" || state === "CLICKING" || state === "VERIFYING") {
            return "⚙";
        }
        if (state === "PAUSED" || state === "STOPPED" || state === "IDLE") {
            return "■";
        }
        return "▶";
    }

    function watchHistoryStatus() {
        if (!panelState.driver || typeof panelState.driver.tracker !== "function") {
            return;
        }
        let status = null;
        try {
            const snap = panelState.driver.tracker();
            status = snap && snap.historyStatus ? String(snap.historyStatus) : null;
        } catch (error) {
            status = null;
        }
        if (!status || status === panelState.lastHistoryStatus) {
            return;
        }
        panelState.lastHistoryStatus = status;
        if (status === "complete") {
            logLine("着法历史：完整（引擎能判长将/长捉 —— 规则胜走的就是这条）");
        } else {
            logLine(
                "⚠ 着法历史：" +
                    (status === "partial" ? "部分（中途接管 / 基准被改正过）" : "无") +
                    " —— 引擎看不到完整重复局面，长将/长捉判罚用不上；想吃规则胜：先换局，停在标准开局，再点「轮到我」"
            );
        }
        panelActions.render();
    }

    /** 进度行文字（悬浮窗第二块）：**两行**，状态名中文化，一眼看出卡在哪一步。 */
    /** 状态机当前理由（面板进度区用）：运行中没别的进度信息时把它显示出来，
     *  否则用户看不出「卡在哪」（例：「轮到我方，开始算棋」、恢复/等待的缘由）。 */
    function machineReasonShort() {
        try {
            const text = String(
                panelState.driver && panelState.driver.machine ? panelState.driver.machine.reason() : ""
            ).trim();
            return text || "…";
        } catch (error) {
            return "…";
        }
    }

    function stepLine() {
        if (panelState.starting) return "正在准备 · " + (panelState.startStage || "检查中") +
            "\n点「取消」可停止后续步骤";
        if (panelState.startError) return "启动未完成\n" + panelState.startError + "\n处理后点「重试」";
        if (!panelState.running && !panelState.driver) return "准备就绪\n打开游戏棋盘 → 选模式与红黑方 → 开始";

        const line1 = [];
        const line2 = [];
        try {
            const state = panelState.driver && panelState.driver.machine ? panelState.driver.machine.state() : "-";
            line1.push(stateIcon(state) + " " + (panelState.STATE_LABEL[state] || state));
        } catch (error) {
            line1.push("▶ -");
        }
        if (panelState.driver && typeof panelState.driver.stats === "function") {
            const stats = panelState.driver.stats();
            line1.push(
                "观察 " +
                    stats.observations +
                    " 次｜未知格 " +
                    (panelState.progress.unknown == null ? "-" : panelState.progress.unknown) +
                    "｜本帧 " +
                    (stats.lastObserveMs || 0) +
                    "ms"
            );
            const fixes = [];
            if (stats.ownMovePatched) {
                fixes.push("落点补齐 " + stats.ownMovePatched);
            }
            if (stats.knownFactFills) {
                fixes.push("事实补齐 " + stats.knownFactFills);
            }
            if (stats.colorFlips) {
                fixes.push("纠色 " + stats.colorFlips);
            }
            if (panelState.progress.recover) {
                fixes.push("恢复 " + panelState.progress.recover + " 次");
            }
            if (fixes.length) {
                line1.push(fixes.join("、"));
            }
        }
        const reasonText = machineReasonShort();
        if (panelState.progress.turn) {
            const turnText = String(panelState.progress.turn);
            // 进度卡只放当前结论；等待时长和完整推断仍在下方可滚动日志里，避免摘要被截断。
            line2.push(
                "轮次 " +
                    (turnText.indexOf("未知") >= 0 && turnText.indexOf("继续观察") >= 0
                        ? "未知｜观察中"
                        : turnText.replace("标准开局（红先）", "标准开局｜红先"))
            );
        }
        if (panelState.progress.think) {
            line2.push("算棋 " + panelState.progress.think);
        }
        const line3 = [];
        // 「史」放在最前面：它决定引擎能不能判长将/长捉（规则胜的前提），比落点更该一眼看到
        if (panelState.lastHistoryStatus) {
            line3.push(
                "历史 " +
                    (panelState.lastHistoryStatus === "complete"
                        ? "完整"
                        : panelState.lastHistoryStatus === "partial"
                          ? "部分"
                          : "无")
            );
        }
        if (panelState.progress.point) {
            line3.push("落点 " + panelState.progress.point);
        }
        if (panelState.progress.confirm) {
            const confirmText = String(panelState.progress.confirm);
            line3.push("确认 " + confirmText);
        }
        if (panelState.progress.fill) {
            line3.push("补齐 " + panelState.progress.fill);
        }
        // 摘要文本不再按字符截断；step 区是可换行/可滚动的控件，完整结论不会出现半截省略号。
        const head = line1.join(" │ ");
        const mid =
            line2.length
                ? line2.join(" │ ")
                : panelState.starting
                  ? "启动中…（建驱动 + 引擎预热，约 1s）"
                  : panelState.running
                    ? reasonText
                    : "等待开始（点「开始」）";
        return head + "\n" + mid + (line3.length ? "\n" + line3.join(" │ ") : "");
    }

    function logTitle() {
        let state = "待开始";
        try {
            if (panelState.starting) state = "准备中";
            else if (panelState.startError) state = "需要处理";
            else if (panelState.running && panelState.driver && panelState.driver.machine) {
                state = panelState.STATE_LABEL[panelState.driver.machine.state()] || panelState.driver.machine.state();
            } else if (panelState.driver) state = "已停止";
        } catch (error) {
            state = "运行状态未知";
        }
        return "运行日志 · " + state;
    }

    function logMeta() {
        return "最近 " + visibleLog().length + " 条";
    }

    /** 把诊断长句压成适合悬浮窗阅读的一行；完整原文仍在 logcat / panel-log.txt。 */
    function formatLogLine(raw) {
        const text = String(raw).replace(/\s+/g, " ").trim();
        if (/着法历史：无/.test(text)) return "⚠ 历史：无｜长将/长捉判罚不可用";
        if (/着法历史：部分/.test(text)) return "⚠ 历史：部分｜长将/长捉判罚不完整";
        if (/着法历史：完整/.test(text)) return "历史：完整｜可判长将/长捉";
        if (/稳定帧：.*还缺一帧/.test(text)) return "等待稳定帧｜还缺 1 帧";
        if (/本轮没出手：帧不稳定/.test(text)) return "未出手｜帧不稳定，还缺 1 帧";
        let m = text.match(/本轮账：tick (\d+)ms.*?识别 (\d+) 次共 (\d+)ms.*?重定位 (\d+) 次共 (\d+)ms/);
        if (m) return "本轮｜" + m[1] + "ms｜识别 " + m[3] + "ms×" + m[2] + "｜重定位 " + m[5] + "ms";
        m = text.match(/耗时分期.*?抓帧（含藏窗）\s*(\d+)ms.*?稳定帧\s*(\d+)ms.*?字形识别\s*(\d+)ms.*?合计\s*(\d+)ms/);
        if (m) return "耗时｜抓帧 " + m[1] + "ms｜稳定 " + m[2] + "ms｜识别 " + m[3] + "ms｜合计 " + m[4] + "ms";
        m = text.match(/字形识别：触子\s*(\d+)\s*｜未知\s*(\d+).*?用时\s*(\d+)ms/);
        if (m) return "识别｜" + m[1] + " 子｜未知 " + m[2] + "｜" + m[3] + "ms";
        m = text.match(/本轮观察：未知格\s*(\d+)，耗时\s*(\d+)ms/);
        if (m) return "观察｜未知 " + m[1] + "｜" + m[2] + "ms";
        m = text.match(/状态 WAIT_STABLE｜几何就绪（v(\d+)）/);
        if (m) return "状态：等待稳定｜几何 v" + m[1];
        m = text.match(/校准完成：几何 v(\d+) 偏移\(([^)]+)\) 未知格 (\d+) 用时 (\d+)ms/);
        if (m) return "校准完成｜几何 v" + m[1] + "｜未知格 " + m[3] + "｜" + m[4] + "ms";
        m = text.match(/字形识别已启用（(\d+) 模板/);
        if (m) return "字形识别｜" + m[1] + " 个模板";
        if (/\[driver\] INIT/.test(text)) return "INIT｜开始准备识别";
        if (/\[driver\] 棋盘已定位/.test(text)) return text.replace(/^.*?\[driver\] /, "");
        if (/^局面\s+/.test(text)) return "局面已更新";
        if (/本轮观察：未知格/.test(text)) return text.replace("本轮观察：", "观察｜");
        if (/算棋：/.test(text)) return text.replace(/^.*?算棋：/, "算棋｜");
        return text;
    }

    function visibleLog() {
        // 心跳类日志每秒一条，会把有用的事件挤走 → 不上屏（console 里依旧有）。
        // 日志窗现在由 scroll 承载，长路径/诊断允许自然折行，绝不再按字符截断。
        const rendered = [];
        let previous = "";
        for (let i = 0; i < panelState.logLines.length; i++) {
            const raw = panelState.logLines[i];
            if (/心跳间隔|保活中|心跳延迟/.test(raw)) {
                continue;
            }
            const text = formatLogLine(raw);
            if (!text) {
                continue;
            }
            if (text === previous) continue;
            previous = text;
            rendered.push(text);
        }
        return rendered.slice(Math.max(0, rendered.length - panelState.LOG_LINES_COMPACT));
    }

    /**
     * ⚠️ **绝对不要用 `win.setSize()`**（2026-09-13 真机事故）：
     *   · `setSize(w, -2)` → 窗口变成 `(900x0)` **零高**：画得出来、点不动；
     *   · `setSize(1020, 470)` → 窗口框体直接坏掉：整个面板塔成屏幕左上角一条**撕裂窄条**，
     *     看起来就像「点一下悬浮窗就闪退了」（用户报的就是这个）。
     * 所以窗口尺寸**只由 XML 的显式 px 决定**，运行期不再改尺寸（需要另一个尺寸就重建窗口）。
     */
    function applyWindowHeight() {
        // 保留函数名（调用点不动），但**不再调 setSize** —— 尺寸全交给 XML。
    }

    function logLine(text) {
        panelState.recorder.log(text);
    }

    return {
        otherLineShort: otherLineShort,
        statusLine: statusLine,
        noteProgress: noteProgress,
        stepColor: stepColor,
        stateIcon: stateIcon,
        watchHistoryStatus: watchHistoryStatus,
        machineReasonShort: machineReasonShort,
        stepLine: stepLine,
        logTitle: logTitle,
        logMeta: logMeta,
        formatLogLine: formatLogLine,
        visibleLog: visibleLog,
        applyWindowHeight: applyWindowHeight,
        logLine: logLine
    };
}

module.exports = { create: create };
