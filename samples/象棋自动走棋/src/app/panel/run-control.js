// @engine quickjs
"use strict";

/** run-control：每个实例独立持有状态；跨模块动作由装配入口注入。
 * 状态通过参数显式共享；跨模块调用通过动作表，模块间不循环 require。
 */

function create(panelState, panelActions) {
    /**
     * 催游戏一下（画面卡在**走子动画中间**时用）。
     *
     * 现场（2026-09-19，K40 实拍放大）：黑方那手「炮 8 平 6（1,2 → 4,2）」卡住没落位 ——
     * 棋子是**隐形的**（在途），起点 (1,2) 已空，终点 (4,2) 只剩一个**闪烁的落点光环**。
     * 于是那两格永远读不出子（一会儿 `?`、一会儿空）→ 驱动每帧差一格未知 → 轮次未知空转。
     * 天天象棋是**人机对战**（电脑 AI 是瞬间应手），动画本该 1 秒内走完 —— 一连 3 帧读不出来
     * 就已经不正常了，所以驱动会调这里催一下。
     *
     * ⚠️ 只做**安全动作**，**绝不点棋盘**：万一那一格真的只是模型漏检，点下去就是走错棋。
     *   第 1 次：重新把游戏带回前台（`am start`，不重启进程）。
     *   第 2 次：用「最近任务」切出再切回（连按两次 APP_SWITCH），强制 pause→resume，
     *            让游戏的渲染/动画重新跑起来。
     */
    function nudgeGame(info) {
        const text = info
            ? "落点 (" +
              (info.index % 9) +
              "," +
              Math.floor(info.index / 9) +
              ") 连续 " +
              info.streak +
              " 帧读不出（已知事实 " +
              info.char +
              "）"
            : "";
        if (!info || info.attempt <= 1) {
            panelActions.logLine("催游戏（第 1 次：重新带前台）：" + text);
            try {
                panelState.adapter.bringPackageToFront(panelState.gamePackage);
            } catch (error) {
                panelActions.logLine("催游戏异常：" + error);
            }
            return;
        }
        panelActions.logLine("催游戏（第 " + info.attempt + " 次：最近任务切出再切回，强制 pause→resume）：" + text);
        try {
            if (!panelState.adapter.bringToFrontByRecents()) {
                panelActions.logLine("催游戏：最近任务切换不可用，退回重新带前台");
                panelState.adapter.bringPackageToFront(panelState.gamePackage);
            }
        } catch (error) {
            panelActions.logLine("催游戏异常：" + error);
        }
    }

    /**
     * 把游戏留在前台（限频）：③ 的脚本宿主是一个透明窗口，它一拿到焦点游戏就 pause 了。
     *
     * 真机实测（K40，2026-09-14，r2.log）：面板跑着的时候对方（联网 AI「特级大师」）**一直不应手**
     * —— 日志里连续 25 次「轮到对方，等待其落子」、棋盘一直没变；面板一停、游戏拿回前台，
     * 棋盘几秒内就变成 4 步。没有这一步，③ 就是在「等一个永远不会来的应手」。
     *
     * 与出招前的前台守卫（foregroundGuard）的区别：那个是「出手前必须验一次」，
     * 这个是「等待期间持续把游戏留在前台」；两者共用适配层的 bringPackageToFront（不重启游戏进程）。
     */
    /**
     * 面板启动时的一次性「把游戏带回前台」（见 main 里的注释）。
     *
     * ★ 2026-09-25（用户报「面板打开后按钮要等几秒才活」）：这里**刻意不再先读两次 dumpsys**。
     *   宿主把脚本拉起后，焦点八成就在我们这个透明窗口上（见 main 里的注释），
     *   而两次 dumpsys 要 1~4s，正好压在启动后 1.2s —— 用户第一次点按钮就撞在这段上。
     *   先用宿主 API `currentPackage()`（毫秒级）判一次：
     *     · 已经是游戏 → 什么都不做（连壳调用都省了）；
     *     · 不是游戏（我们自己 / 桌面 / 读不到）→ 直接「带前台」，不再先证明一遍。
     */
    function keepGameInFrontOnce() {
        let pkg = null;
        try {
            pkg = typeof currentPackage === "function" ? String(currentPackage() || "") : null;
        } catch (error) {
            pkg = null;
        }
        if (pkg === panelState.gamePackage) {
            panelState.lastForeground = pkg; // 状态行有真读数，且不花一次壳调用
            panelState.lastKeepFrontAt = Date.now();
            return; // 游戏本来就在前台：不查、不切
        }
        panelState.lastKeepFrontAt = Date.now(); // 与 keepGameInFront 的限频口径一致
        let ok = false;
        try {
            ok = panelState.adapter.bringPackageToFront(panelState.gamePackage) === true;
        } catch (error) {
            ok = false;
        }
        if (ok) {
            panelState.lastForeground = panelState.gamePackage;
            panelActions.logLine("启动：把游戏带回前台（切入前 " + (pkg || "读不到") + "）");
            if (panelState.boardFrameGate) {
                panelState.boardFrameGate.onFocusChange();
            }
            if (panelState.boardService) {
                panelState.boardService.resetFrameBaseline();
            }
        } else if (pkg === panelState.OWN_PACKAGE) {
            // 只在「确认焦点被我们自己拿着」时才说话：没有 Shizuku/root 的机器上这是正常情况
            panelActions.logLine("启动：没能把游戏带回前台（当前是我们自己）—— 点「开始」时会再试");
        }
    }

    function keepGameInFront() {
        const now = Date.now();
        if (now - panelState.lastKeepFrontAt < 4000) {
            return; // 限频：切前台本身有开销，而且频繁 am start 会把面板自己的输入也搅乱
        }
        panelState.lastKeepFrontAt = now;
        // 读数走缓存（TTL 4s，与这里的 4s 限频同档 ⇒ 每次进来基本都能拿到新读数，
        // 但和心跳/守卫共用同一次壳调用，不再各查一遍）
        const focus = panelState.foregroundFocus(false);
        const top = panelState.foregroundTop(false);
        if (focus === null && top === null) {
            return; // 两个都读不到（没有 Shizuku/root）就不折腾
        }
        // 读数变化时记一行（限频 4s，最多 15 行/分钟）：真机上「此刻到底谁在前台」必须能查到 ——
        // 只按 focusedWindowPackage 判断时它一直报游戏（真机踩到：这一步一次都没触发，白等一整轮）。
        const text = "触摸焦点 " + (focus || "未知") + "／栈顶 " + (top || "未知");
        if (text !== panelState.lastFrontText) {
            panelState.lastFrontText = text;
            panelActions.logLine("等对方应手期间的前台：" + text);
        }
        if (focus === panelState.gamePackage && top === panelState.gamePackage) {
            return;
        }
        try {
            const ok = panelState.adapter.bringPackageToFront(panelState.gamePackage);
            panelActions.logLine("等对方应手：把游戏带回前台（之前 " + text + "，切换" + (ok ? "成功" : "失败") + "）");
            if (ok) {
                // 切完前台，画面会有一段时间在重建（恰好是动画/半帧的窗口）⇒ 让稳定帧闸门先预热一帧
                if (panelState.boardFrameGate) {
                    panelState.boardFrameGate.onFocusChange();
                }
                // 基线本身在服务层（切窗前的采样不能拿来配新画面，否则会被判「不稳定」白丢一帧）
                if (panelState.boardService) {
                    panelState.boardService.resetFrameBaseline();
                }
            }
        } catch (error) {
            // 切不动就算了：下一次 tick 还会再试，不抦断循环
        }
    }

    return { nudgeGame: nudgeGame, keepGameInFrontOnce: keepGameInFrontOnce, keepGameInFront: keepGameInFront };
}
module.exports = { create: create };
