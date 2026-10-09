// @engine quickjs
/**
 * 自动走棋的驱动：把状态机吐出的**动作清单**变成真的截图 / 算棋 / 点击 / 确认。
 *
 * 分工：
 *   状态机（state-machine.js）  —— 什么时候允许出手（纯逻辑）
 *   本文件                      —— 把动作清单执行掉，并把结果事件喂回去
 *   适配层（aijs-adapter.js）   —— 唯一碰宿主接口的地方
 *
 * 一轮（tick）做这些事：
 *   截图 + 识别（复用已校准几何）→ 推轮次 → 喂 SNAPSHOT → 按机器给的动作干活
 *
 * 轮次怎么来的：截图里没有「轮到谁」，只有棋盘布局。所以：
 *   1) 用户按「轮到我」显式声明一次（最可靠，界面上有按钮）；
 *   2) 之后用 transition.infer 从「上一可靠局面 + 新观测」推：唯一合法单步 → 轮到下一方；
 *      解释不了 → 返回 unknown，交给状态机等待/恢复（**超时不算轮到我**）。
 */
"use strict";

const machineModule = require("./state-machine.js");
const verifier = require("./move-verifier.js");
const executorModule = require("./click-executor.js");
const searchWorkerModule = require("./search-worker.js");
const prethinkModule = require("./prethink.js");   // 手机预思考：与主搜索共用同一个 worker（见装配处注释）
// ★ 开局战绩/学习记录（2026-09-27 独立复核补装配：history.js/turn.js 里之前全是判空护栏）
const openingResultsModule = require("../services/opening-results.js");
const loopHistoryModule = require("./loop-history.js");
const transition = require("../chess/transition.js");
const fenModule = require("../chess/fen.js");
const legal = require("../chess/legal-moves.js");
const rulesModule = require("../chess/rules.js");
const positionTrackerModule = require("../services/position-tracker.js");
const defaults = require("../util/defaults.js");
const logRing = require("../diagnostics/log-ring.js");
const attemptBudgetModule = require("./attempt-budget.js");
const crossFrameGateModule = require("./cross-frame-gate.js");
// ★ 帅/将「规则反推」（2026-09-25：被将军时帅的字形只有 0.22 分 ⇒ 识别全失手，只能用棋规把帅位钉回来）
const kingInferModule = require("../chess/king-infer.js");
// ★ 求胜模式（2026-09-25 用户：「老是和棋」）：均势时避开「会走回旧局面」的着法（纯函数见 win-seek.js）
const winSeekModule = require("./win-seek.js");
const frameConsensusModule = require("./frame-consensus.js");
const knownFactsModule = require("./known-facts.js");
const pieceCapAudit = require("./piece-cap-audit.js");
const searchBudget = require("./search-budget.js");

/**
 * 合法棋子字符（FEN 字母，大写红、小写黑）。
 * 全盘 OCR 补正只认这 14 个字符：`?`、空串、其它字形一律**不写**盘面与可靠基准。
 */
const PIECE_CHAR_RE = /^[rnbakcpRNBAKCP]$/;

const DEFAULTS = require("./driver-defaults.js");

function createDriver(options) {
    const driverState = { options: options };
    const driverActions = {};
    Object.assign(driverActions, require("./driver/history.js").create(driverState, driverActions));
    Object.assign(driverActions, require("./driver/effects.js").create(driverState, driverActions));
    Object.assign(driverActions, require("./driver/search.js").create(driverState, driverActions));
    Object.assign(driverActions, require("./driver/execution.js").create(driverState, driverActions));
    Object.assign(driverActions, require("./driver/verification.js").create(driverState, driverActions));
    Object.assign(driverActions, require("./driver/move-policy.js").create(driverState, driverActions));
    Object.assign(driverActions, require("./driver/recognition.js").create(driverState, driverActions));
    Object.assign(driverActions, require("./driver/evidence.js").create(driverState, driverActions));
    Object.assign(driverActions, require("./driver/board-repair.js").create(driverState, driverActions));
    Object.assign(driverActions, require("./driver/observation.js").create(driverState, driverActions));
    Object.assign(driverActions, require("./driver/baseline.js").create(driverState, driverActions));
    Object.assign(driverActions, require("./driver/turn.js").create(driverState, driverActions));
    Object.assign(driverActions, require("./driver/loop.js").create(driverState, driverActions));

    driverState.opts = driverState.options || {};
    driverState.cfg = defaults.mergeDefaults(DEFAULTS, driverState.opts.config);
    driverState.log = driverState.opts.log || function () {};
    /**
     * 崩溃前哨（2026-09-26）：把 tick 的关键分段报给宿主（面板打到 logcat、不上屏）。
     * quickjs 偶发内部崩溃（JS_DefineProperty 空指针，已有三次同点墓碑）JS 层 try/catch 抓不到，
     * 崩前最后一条 stage 就是「死在哪个动作」的唯一线索。宿主不注入时是 no-op（回归自检不受影响）。
     */
    driverState.stage = typeof driverState.opts.stage === "function" ? driverState.opts.stage : function () {};
    driverState.nowMono =
        driverState.opts.nowMono ||
        function () {
            return Date.now();
        };
    driverState.clock =
        driverState.opts.sleep ||
        function (ms) {
            if (typeof sleep === "function") {
                sleep(ms);
            }
        };

    if (typeof driverState.opts.recognize !== "function") {
        throw new Error("驱动需要注入 recognize(options)（真机用 recognize-board.recognizeScreen）");
    }
    if (typeof driverState.opts.locate !== "function") {
        throw new Error("驱动需要注入 locate()（真机用 locate-board.locateBoardOnScreen）");
    }
    if (typeof driverState.opts.analyze !== "function") {
        throw new Error("驱动需要注入 analyze(board, side, options)（真机用 engine.analyze）");
    }

    driverState.clickAllowed = driverState.opts.allowClick !== false;
    driverState.disabledTap = executorModule.createDisabledTap("只识别 / 着法建议模式：不会点击棋盘");
    driverState.executor =
        driverState.opts.executor ||
        executorModule.createExecutor({
            tap: driverState.clickAllowed ? driverState.opts.tap : driverState.disabledTap.tap,
            nowMono: driverState.nowMono,
            waitMs: function (ms) {
                driverState.clock(ms);
            },
            config: driverState.opts.executorConfig
        });
    if (!driverState.clickAllowed && !driverState.opts.executor) {
        // 只识别/建议模式一律用禁用桩：调用会被计数并报错，验收 A05 查的就是这个计数
        driverState.executor.tapIsDisabled = true;
    }
    driverState.machine =
        driverState.opts.machine ||
        machineModule.createMachine(
            {
                mySide: driverState.opts.mySide === "b" ? "b" : "w",
                allowClick: driverState.clickAllowed,
                movetimeMs: driverState.cfg.movetimeMs,
                ticketTtlMs: driverState.opts.ticketTtlMs
            },
            { nowMono: driverState.nowMono }
        );

    /**
     * 本局「我方已确认着数」：开局库步数闸门用它（见 cfg.bookOwnMoves）——
     * 前 N 手查开局库、之后交给引擎实时搜索。
     * 只有**走子方是我方**的账本记录才 +1（我方点击确认 / 观测到人工走的那一手都算）。
     * 清零点：新会话、换我方、以及「标准开局被采纳」（新的一局刚摆好）。
     */
    driverState.ownMoveCount = 0;

    driverState.stats = {
        ticks: 0,
        observations: 0,
        unrecognized: 0,
        stableObservations: 0,
        unknownTurn: 0,
        clicks: 0,
        confirms: 0,
        /** ROI 快速确认成功次数（2026-09-21 提速·方案第 3 条；>0 = 快速路径在起作用） */
        roiConfirms: 0,
        errors: 0,
        lastError: "",
        lastObserveMs: 0,
        /** 算棋去重：交给 worker 线程的次数 / 退回本线程同步算的次数 */
        thinkOffThread: 0,
        thinkInline: 0,
        /** 最近一次「状态机发 COMPUTE → 拿到结果」的总耗时（ms），含排队与轮询延迟 */
        thinkMs: 0,
        /** ② 模式给出的建议着法次数（不点击） */
        suggestions: 0
    };
    /** 落点格按票据补齐的次数（游戏给刚落的子加高亮，那一格认不出来） */
    driverState.stats.ownMovePatched = 0;
    /** 其中「同字异色（红黑读反）」被按已知事实补齐的次数（见 observeOnce 第 ③ 支） */
    driverState.stats.sameGlyphPatched = 0;
    /** 「同字异色」僵持后按**原始读数**复议并被采信的次数（见 doVerify；>0 = 真发生了反吃） */
    driverState.stats.colorDisputeAdopted = 0;
    driverState.stats.knownFactFills = 0;
    /** 颜色读反被纠正的格子数（游戏装饰导致的已知模型弱点） */
    driverState.stats.colorFlips = 0;
    /** 多帧合并：做过合并的观察次数 / 因两帧不一致而降级的格子数 */
    driverState.stats.mergedFrames = 0;
    driverState.stats.frameConflicts = 0;
    driverState.stats.sideFixes = 0; // 「盘面按着法改正」次数（落点格被装饰读错时）
    /** 单格重拍：尝试次数 / 找回的格子数（见 reprobeSuspects） */
    driverState.stats.reprobes = 0;
    driverState.stats.reprobeFilled = 0;
    /** 「本周期刚登记的事实」被模型整格读空、按漏检补齐的次数（见 observeOnce 空读数那一支） */
    driverState.stats.freshEmptyFilled = 0;
    /** 着法推断因「还没拿到第二帧佐证」而暂缓的次数（见 tolerantSupportedByTwoFrames） */
    driverState.stats.corroborateWaits = 0;
    /** 对方应手因「还没拿到第二帧佐证」而暂缓采信的次数（见 replyCorroborated） */
    driverState.stats.replyHolds = 0;
    /** 「疑似画面卡住」催游戏的次数 / 催后仍然读不出来而放弃已知事实的条数 */
    driverState.stats.stuckNudges = 0;
    driverState.stats.stuckFactsDropped = 0;
    /** 按棋规改正的格子数（落点必是走子、途经格必空；见 observeOnce 的「按棋子怎么走对账」） */
    driverState.stats.ruleFixes = 0;
    /** 清掉的「幻影/重复子」格数（同一枚子在观测里占了两格；棋子不会分身） */
    driverState.stats.phantomCleared = 0;
    /** 「幻影」僵持后改判为**基准漏子**并收编进基准的次数（见 clearPhantoms；>0 = 真发生过） */
    driverState.stats.phantomAdopted = 0;
    /**
     * 「幻影候选」没敢清掉的次数（两个护栏各一个计数）：
     *   · phantomHeldBack：像素（找色）证据说那格是我方子（防「我方子被读成敌色」被当重复子清掉）；
     *   · phantomHeldByCheck：**拿掉它会改变我方帅的被将状态** —— 2026-09-24 真机：对方那枚
     *     贴脸将军的黑车被「重复子」连着清了 7 分钟 ⇒ 盘面上永远没有将军、引擎一直走别的
     *     （真实棋局全拒收）⇒ 用户看到的「自己被将军了 还在走其他的」。
     */
    driverState.stats.phantomHeldBack = 0;
    driverState.stats.phantomHeldByCheck = 0;
    /** 全盘 OCR 改正「同色不同兵种」误读的格子数 */
    driverState.stats.ocrBoardFixes = 0;
    /** 全盘 OCR 的询问次数（限频：见 cfg.ocrBoardCooldownMs） */
    driverState.stats.ocrBoardChecks = 0;
    /** 全盘 OCR 补正里「等连续帧一致」与「被分数/颜色/非法字形驳回」的格数（体检用） */
    driverState.stats.ocrBoardPending = 0;
    driverState.stats.ocrBoardBlocked = 0;
    /** 被「已知事实已经定死了颜色」驳回的颜色翻转格数（棋子一局里不会改颜色，见 applyOcrBoardFixes） */
    driverState.stats.ocrFlipBlocked = 0;
    /** 单格 OCR 检查：次数 / 确认「那格确实有子」次数 / 判为空格而作废已知事实次数 */
    driverState.stats.ocrChecks = 0;
    driverState.stats.ocrConfirmed = 0;
    driverState.stats.ocrRejected = 0;
    /** RECOVERING 后丢弃旧事实并按干净观测重建基准的次数。 */
    driverState.stats.baselineRebuilds = 0;
    /** 重建基准后「本帧不出手、等下一帧佐证」的按住次数（见 tick 的 rebuiltThisTick 闸门） */
    driverState.stats.rebuildHolds = 0;
    /** 「杀棋待佐证」闸门：按住次数 / 佐证通过放行次数 / 佐证未通过作废次数 */
    driverState.stats.mateHolds = 0;
    driverState.stats.mateHoldPassed = 0;
    driverState.stats.mateHoldVetoed = 0;
    /** 「疑似循环（长捉）」把重复着法交给引擎禁用的次数 */
    driverState.stats.loopBanHits = 0;
    /** 求胜模式：触发次数 / 共排除了几手「会走回旧局面」的着法 */
    driverState.stats.winSeekHits = 0;
    driverState.stats.winSeekBans = 0;
    /** 求胜模式：其中「等价兑子（简化局面）」被避开的次数 / 多路候选里改挑非兑子的次数 */
    driverState.stats.winSeekSwapBans = 0;
    /** 求胜模式：「避开兑子」在多路候选里改挑非兑子的次数 */
    driverState.stats.winSeekSwapSkips = 0;
    driverState.stats.winSeekSwapSkips = 0;
    /**
     * 「疑似循环」放行的两步计数（2026-09-25 真机修：禁手只能用于**我方长将**，
     * 其余重复（安静往返/被迫应将）按规则是「双方不变作和」，禁了反而逼引擎送子）：
     *   · loopBanSkippedCheck：我方正被将军 ⇒ 整表放行（着法全是被迫应将）；
     *   · loopBanSkippedQuiet：不是将军的安静着法 ⇒ 从禁用表里放行。
     */
    driverState.stats.loopBanSkippedCheck = 0;
    driverState.stats.loopBanSkippedQuiet = 0;
    /**
     * 「多步预判线」用于**识别裁决**（多解里点名）被采纳的次数（2026-09-25 晚）：三处 ——
     * 未知格补齐的多解（fillKnownFacts F2）/ 轮次推断的多解（transition 预判裁决）/ 占用快路多解。
     */
    driverState.stats.predictionTieBreaks = 0;
    driverState.stats.budgetPauses = 0; // 因「同局面/同手重试超限」暂停的次数
    driverState.stats.sourceGoneRefusals = 0; // 重试前发现「起点子已不在原位」而拒发的次数（§11）
    driverState.stats.rawCorrected = 0; // 「模型原始读数和最终盘面不一致」的观测次数（§12）
    driverState.stats.newGamesDetected = 0; // 连续两帧确认换局，丢弃旧残局基准的次数
    driverState.lastRawCorrectedText = "";
    /** 「同字异色」僵持计数器：上一次的争议格指纹 + 连续出现帧数（见 doVerify） */
    driverState.lastColorDisputeKey = "";
    driverState.lastColorDisputeStreak = 0;
    /**
     * 「幻影僵持」计数器：格下标 → `{ key: "下标:字", count }`。
     * 同一个格子连着多少帧都在「多出来」⇒ 到了 `cfg.phantomAdoptStreak` 就收编进基准
     *（只有「与上一张可靠基准比」那条路维护它；见 clearPhantoms）。
     * 清空时机：换边 / 新会话 / 声明轮次 / 停止（基准全换了，旧记录不再有意义）。
     */
    driverState.phantomStreak = {};
    /**
     * 「同字异色复议」已裁定的格子（按票据记）：本票之内，那几格**一律以裁定值**为准 ——
     * 不这么做的话，票据里那份**过期的期望值**会每帧再把颜色翻回去（真机实测：
     * 僵持 → 复议 → 确认 → 下一帧又被翻回去 ⇒ 同一张票据循环 49 次、3 分钟不动）。
     * 清空时机：新会话 / 换边（旧票据已经作废，留着没意义）。
     */
    driverState.settledRaw = null;
    /**
     * **预测的对方应手**（2026-09-20，用户提议「提前算一下对手的棋」）：
     * 我方一落子、轮到对方走的那段空档里，顺手让引擎按**对方**的角度算一手（top-K，浅算即可）。
     *
     * 用途（**只当平局裁决，绝不单独采信**）：验证「对方应手」那一帧时，
     * 把候选交给验证器（`verifier.verify` 的 `predictedReplies`）——
     * ① 观测与某个候选**严格对得上**（只允许那一手的两格有差异）⇒ 采信，并把
     *    「起点已空 / 落点=X」登记成已知事实 ⇒ 那两格（通常正画着高亮环、读不出来）
     *    从此不再是未知格；
     * ② 对不上任何候选 ⇒ 照旧走通用匹配/恢复 —— 预测错了不会把盘面改错。
     *
     * 结构：{ key（局面键，含该走棋方）, list（ICCS 数组，按引擎给的分序） }
     *
     * ★ 2026-09-25 接上（两层）：
     *   ① 即时层：我方算棋主变（PV）的第 2 着（`result.pvIccs[1]`）——零成本，马上可用；
     *   ② 升级层：我方算完即在空档里按**对面视角**再浅算一手（top-K，见 DEFAULTS.predictReply），
     *      完成后把预判列表升级成对面 top-3（带排名）—— 对面真走完后核对"走得对不对"。
     *   命中/偏差都记账（`stats.predictHits/Misses`）；验证器侧只在「多解平局」时用它裁决；
     *   预测错 = 当没预测。
     *
     * ★★ 2026-09-25 晚（用户要求「预判对面多走几个子的步，来添加识别判断」）：结构升级为**多步预判线** ——
     *   `{ key, list, enemy: [...], own: [...] }`：
     *   · enemy = 对面第 1/2/3…手（我方主变奇数位 + 对面视角搜索的主变）；
     *   · own   = 我方后续着法（偶数位）。
     *   **实时裁决只吃「对面下一手」**（list + enemy[0]，见 predictionIccsFor）；
     *   更深的几手留给日志/将来对账。裁决点共四处：
     *   · 未知格补齐（fillKnownFacts F2）多解；· 轮次推断（transition.infer）多解；
     *   · 占用快路多解；· 应手验证的平局裁决（验证器，原有）。
     *   统一口径：**只当平局裁决，绝不单独采信** —— 点多不出唯一就保持未知；
     *   走推断路径的还要再过一道「连续两帧同一解释」的佐证。
     */
    driverState.predictedReplies = null;

    /**
     * 「最近着法」记录（2026-09-25，画板「我起/我落/敌起/敌落」四个框用）：
     * 我方与对方各留最后一手（ICCS）；更新点 = 三条采纳路径（确认帧 / 观测推断 / 占用快路）。
     */
    driverState.lastMoves = { own: null, opponent: null };
    /** 预判任务（见 DEFAULTS.predictReply）：单 worker 任务，与算棋互斥。 */
    driverState.pendingPredict = null; // { key, startedAtMono }
    /** 冲突明细去重用的上一次文本（限频：同一局面不重复刷） */
    driverState.lastConflictText = "";
    // 日志环：超上限**原地裁剪**（以前的 `const history` + `history = history.slice()` 会抛异常）
    driverState.history = logRing.createLogRing(300, 200);

    driverState.running = false;
    driverState.grid = null;
    /**
     * 「检测框说几何已明显偏移」的可疑窗口（2026-09-25 真机：新开局棋盘回到常规位置，而面板还在用
     * 上一局/结算页的几何 —— 检测框想纠 (-19,-211) 被护栏拒收）⇒ 每帧都拿错位置去读格子、将/帅全读不出。
     * 真机证据：**棋子移动不会让检测框原点差两格**，所以越界就是「几何变了」的强证据。
     * 窗口内：① recover() 跳过限频、强制重新定位；② 面板的「几何沿用」快路径必须让路（走全量校准）——
     * 否则「能读出一整盘错位的子」会被当成几何没事（真机就是这么卡了 292s、点「定位校准」也没用）。
     */
    driverState.GEOMETRY_SUSPECT_MS = 60000;
    driverState.geometryStaleUntil = 0;
    driverState.lastReliable = null; // { board, side }：上一可靠局面与「该走棋方」
    driverState.newOpeningStreak = 0; // 原始观测连续显示新开局的次数；单帧误检不换局

    /**
     * A3 事实层（**影子接入**）：已确认局面 + 已验证着法账本（src/services/position-tracker.js）。
     *
     * `lastReliable` 仍是驱动的局面读取源；tracker 同步记录，并在算棋时提供已确认着法历史：
     *   · 每处 `lastReliable = …` 旁多一行 `track*()`；
     *   · 账本拒收（非法/与验证盘面不符/与当前局面不连续）→ `stats.trackerRejects`；
     *   · 影子与基准不一致 → `stats.trackerMismatch` + 前几次打日志。
     * 只有 tracker 局面与本次待算局面逐格一致，才向原生引擎传 `positionCommand()`；旧宿主退回当前 FEN。
     */
    driverState.positionTracker = positionTrackerModule.createPositionTracker({
        startFen: fenModule.START_FEN,
        nowMono: driverState.nowMono,
        note: function (text) {
            driverActions.note("[账本] " + text);
        }
    });
    driverState.stats.trackerMismatch = 0;
    driverState.stats.trackerRejects = 0;
    driverState.stats.trackerErrors = 0;

    driverState.lastResult = null; // 最近一次识别结果（界面/诊断用）
    driverState.lastBoard = null;
    /** 最近一次观测里**模型读到的原始盘面**（补丁/补齐之前）——诊断与审核用（§12） */
    driverState.lastRawChars = null;
    /** 对方应手快路用：上一帧占用图（相邻帧做差找「棋子在动」）。 */
    driverState.prevOccupancy = null;
    /**
     * 对方应手快路用：「本个对方回合」里翻过面的格子（自上次采纳后累积，跨多帧保留）。
     * 为什么不是只看最近两帧（2026-09-21 真机）：对方落子帧常夹装饰，且起点格的「星光残留」
     * 可能让占用读数晚几帧才落定 —— 只留 2 帧的话，等读数干净了「新鲜度」早过期，应手又放跑、
     * 白等 45 秒闲置翻轮次（真机抓过现行：变化 4 格看见了应手，账却已过期）。
     */
    driverState.flipEpoch = {};
    /** 快路跨帧佐证的待定候选（同一手连续两帧都成立才采纳）。 */
    driverState.replyFastPending = null;
    /** 本次观测里，轮次推断给出的「按着法改正过的盘面」（仅当确实改动了观测时才存在） */
    driverState.lastSideFix = null;
    /**
     * 「同一个解释必须连续两次观测都成立」才行动（跨帧佐证）。
     *
     * 键 = 基准盘面 + 解释文本；两次观测之间至少隔 `cfg.corroborateGapMs`。
     * 为什么要它（2026-09-19，K40 假事实事故）：
     *   模型会给出**重复检测框的伪影**（同一枚黑炮被读成 (7,2) 和 (4,2) 两个位置），
     *   于是某一帧就能推出「唯一合法单步 h7e7（黑炮 7,2→4,2）」—— 那是**不存在的应手**。
     *   它一旦被采信，假事实就会被写进可靠盘面**和票据的「预期落子后局面」**，
     *   之后验证阶段就永远「有多种应手解释，不唯一」→ 整局钉死（重定位也治不好）。
     *   要求连续两帧都推出同一个解释，模型那一帧的伪影就过不了关（下一帧读的是空/别的盘面）。
     * 副作用：真实着法会晚一帧（约一个 tick）被采信 —— 代价可接受，而且 `lastReliable`
     * 在这期间**不变**，所以下一帧会重新推出同一个差异，不会错过。
     */
    driverState.crossFrameGate = crossFrameGateModule.createCrossFrameGate({
        keyOf: function (board, side) {
            return driverActions.positionKeyOfBoard(board, side);
        },
        sample: function () {
            return driverState.stats.observations;
        }
    });
    /** 本次 `sideFor` 是不是因为「还没拿到第二帧佐证」而暂缓（用来把它和「真推不出来」分开计数） */
    driverState.lastInferHold = false;
    // （对方应手的跨帧佐证计数已移入 crossFrameGate，见上）
    /**
     * 「对账修订」账本（2026-09-24，中途卡顿最后一层的根治）。
     *
     * 问题：等对方应手时，本帧的修订机制（找色作废、幻影收编、催游戏后放弃）会**原地改正可靠基准**。
     * 如果修订吞掉的正是对方刚走完的那一手（起点被置空、落点被当成「多出来的幻影」收编），
     * 修订后的基准直接等于观测 ⇒ 差分追踪看到「没变化」⇒ 轮次永远停在「轮到对方」，
     * 只能靠 15s「判反」兜底（真机 2026-09-24：对方马 (6,4)→(5,2)，找色作废 + 幻影收编
     * 两步就把它抹平了，之后基线=观测，一直等到兜底）。
     *
     * 处置：修订器每次都登记「修订前的基准」（`recordBaselineRevision`）；`sideFor` 里如果发现
     * 修订发生在「轮到对方」的时刻，就**借回修订前的基准**重算轮次 —— 修订 + 观测的**累计差**
     * 里带着那一手（起点空了 + 落点有子），差分追踪一步就能认出来。认出来按正常流程翻轮次；
     * 认不出且超过 `cfg.baselineBorrowMs` 就放弃，回到修订后的基准（即老行为）。
     */
    driverState.pendingBaselineRevision = null; // { beforeBoard, beforeSide, why, atMono }
    driverState.baselineBorrow = null; // { base, aside, atMono, why }

    /** 对账期间放宽过的看门狗原值（null = 当前没放宽） */
    driverState.borrowWatchdogSaved = null;

    /**
     * 尝试预算（见 DEFAULTS.attemptLimitPerMove 的说明；实现见 attempt-budget.js）。
     * 键 = 会话|基准 FEN|该走棋方（+ 着法）；**故意不在恢复成功/新票据/换几何时清零** ——
     * 那些都不算取得进展，只有在确认真实合法变化（落子/应手）后才清空。
     */
    driverState.attemptBudget = attemptBudgetModule.createAttemptBudget({
        limitPerMove: driverState.cfg.attemptLimitPerMove,
        limitPerPosition: driverState.cfg.attemptLimitPerPosition,
        note: driverActions.note
    });
    /** 上次重新定位的单调时间 + 最小间隔（见 recover 的限频说明：定位一次 ~8s，而棋子移动不改变几何） */
    driverState.lastRelocateMono = 0;
    driverState.RELOCATE_MIN_INTERVAL_MS = 30000;
    /** 最近一次观测的**每格实测框中心**（点棋用；见 click-executor.pointOf 的说明） */
    driverState.lastCenters = null;
    driverState.lastCentersVersion = null;

    // ---- 算棋放在哪跑 ----
    // worker：独立 QuickJS 引擎（自己的线程），主线程只负责「起任务 + 每轮 poll 一次」；
    // inline：老路径（本线程同步算，会挡触控），只在宿主没有 threads 或显式要求对照时用。
    driverState.projectDir =
        driverState.opts.projectDir || (typeof __dirname === "string" ? __dirname + "/../.." : ".");
    driverState.workerSearch = driverState.opts.searchWorker || searchWorkerModule.create({
        dir: driverState.projectDir,
        graceMs: driverState.cfg.computeGraceMs,
        // 预思考、预判和主搜索共用一个常驻 Worker。取消作废结果；
        // 旧任务在 JNI 中自然收尾后，新任务才能开始，不阻塞面板等待。
        nowMono: driverState.nowMono,
        log: function (text) {
            driverActions.note(text);
        }
    });
    /**
     * 手机预思考（prethink.js，2026-09-27 补齐装配）：对手回合的闲置搜索槽里做
     * 「对面应手后我方最佳着」的热身搜索（只暖 Hash，**从不出着法**；正式搜索前 take() 核对身份）。
     * 与主搜索**共用同一个 worker**：2026-10-05 起改为「预判先占、预思考排队」——我方算完先跑
     * 对面视角预判（面板画 top-K、对面走完核对），预思考等预判收完再上（见 driver/search.js 里
     * pollPrethink 的 pendingPredict 闸门）；任一方在跑时，正式搜索照旧让/取。
     */
    driverState.prethink = prethinkModule.create(driverState.workerSearch);
    /**
     * 开局战绩记录（2026-09-27 独立复核补装配）：只有面板显式打开 recordGames 才建 ——
     * 离线自检/回放/观察模式不碰设备存储；history.js 的 trackerMirror 每帧喂快照（内部按签名去重），
     * turn.js 的 resetSession 换局时 reset。战绩由「策略与战绩」菜单人工确认（services/opening-results.result）。
     */
    driverState.openingRecord = driverState.cfg.recordGames === true
        ? openingResultsModule.createSession({})
        : null;
    driverState.computeMode =
        driverState.cfg.computeMode === "inline" || !searchWorkerModule.available() ? "inline" : "worker";
    driverState.pendingCompute = null; // { jobId, effect, startedAtMono }：正在 worker 里算的任务
    /**
     * 「游戏画花了的格子」的已知事实补齐表（实现见 known-facts.js；记录 { index, char, cycle, ply }）。
     * 游戏会给**刚落的子**加高亮环，那一格会被模型认错（实测：带环的炮读成马），
     * 棋规层再把它降级成 '?'。我方落子与对方应手的子都会被画环，所以是张表。
     * 兵种是**已知事实**（票据 + 唯一合法应手推出来的），不是猜；格子里读到别的子/变空就自动失效。
     */
    driverState.knownFacts = knownFactsModule.createKnownFacts();
    /** 最近一次「全盘 OCR 待定/驳回」的日志文本与时间（避免每 6s 刷屏） */
    driverState.lastOcrGateText = null;
    driverState.lastOcrGateNoteAt = 0;

    /**
     * 全盘 OCR 补正的「连续帧共识」账本（实现见 frame-consensus.js）：
     * 只在 `applyOcrBoardFixes` 里维护；每格变一次结论就重置为 1 帧。
     */
    driverState.frameConsensus = frameConsensusModule.createFrameConsensus();
    /** 「颜色抖动按住」日志限频时间戳 */
    driverState.lastFlapNoteAt = 0;
    /**
     * 本手（已发出点击、正在等确认）的我方着法。观察层的「已知事实补齐」靠它把
     * **起点格按空、落点格按走过去的那个子**来补 —— 否则 F1（与上一张可靠盘面一致）
     * 会照**落子前**的基准把起点补回一枚棋子，凭空造出「幻影子」。
     *
     * ⚠️ 真机事故（2026-09-20）K40：`b2c2`（炮 (1,7)→(2,7)）发出后、确认那几帧里
     *    (1,7) 与 (2,7) 都是 `?` → F1 把两格都照旧基准补成 C → 基准变成 `…1CC4C1…`
     *    （**两枚炮都在**）→ 之后每一帧都「少一个子且解释不了」→
     *    轮次永远推不出来 → 永久 RECOVERING（用户看到的「卡住」就是这个）。
     */
    driverState.activeOwnMove = null;
    /**
     * 最近的 ROI 快速确认结论（{ actionId, atMono, verdict }）。
     * 2026-09-24：真机实测一手棋的确认会**完整跑两遍**（两条「第 1 次探测」相隔 ~0.6s），
     * 第二遍的抓帧+OCR 完全白花 —— doVerify 入口对同一票据 2 秒内直接复用它。
     */
    driverState.lastFastConfirm = null;
    /**
     * 「手数周期」：每确认成功一次 +1。补齐表里的记录只在**同一个周期内**有效 ——
     * 游戏的高亮环只挂在「刚落的那个子」上，下一手一旦确认，上一手的装饰就不再存在。
     * 真机踩过：不加这个界限，上一手的记录会一直生效，把「那个子真的被吃了」也覆盖掉。
     */
    driverState.verifyCycle = 0;
    /**
     * 「我方出手序号」：每次**发出点击**就 +1。
     * 这是「刚落下的子不会动」的真正时间界限：
     *   · 我方出手之前，对方刚落的子、我方刚落的子都不可能再动（要吃它也得等我方落子）；
     *   · 我方一出手，局面就要变，旧记录不能再当事实用。
     * ⚠️ 不能用 verifyCycle 当界限：同一个着法会被**重复确认好几次**，每确认一次周期+1，
     * 于是刚登记的记录立刻沦为「旧记录」（真机踩过：补齐只生效了一帧）。
     */
    driverState.moveEpoch = 0;
    /**
     * 单格重拍的冷却表：`index → 上次重拍时刻`。
     * 可疑格可能连续多帧都读成空（装饰会反复发作），但**不必每帧都烧一次额外截图**，
     * 而且降阈值重拍偶尔会把噪点收成棋子 —— 限频也限制了这种噪音的影响面。
     */
    driverState.lastReprobeAt = {};
    /** 同一格上次「催游戏」的时刻（限频用；催太勤会把游戏和面板一起搞乱） */
    driverState.stuckNudgeAt = {};
    /** 上次问全盘 OCR 的时刻（限频：一次 90 格 ≈1.2s） */
    driverState.lastOcrBoardAt = 0;
    /** 上次「全盘 OCR 对账无可改正」的日志时刻（只用于给日志限频） */
    driverState.lastOcrBoardNoteAt = 0;
    /**
     * 本次「确认」里有没有已知事实被证伪（OCR 读到空格/别的子、或催了多次仍读不出来）。
     * 为什么要它：证伪只把补齐表与可靠基准改对还不够 —— **当前票据**里带着那张旧期望值
     * （期望 (4,2) 有炮），于是验证会永远停在「装饰容差下有 N 种应手解释，不唯一」。
     * 有这个标记，就在这次确认结束时作废票据、重新观察。
     */
    driverState.factInvalidated = false;
    /**
     * 最近一次「可靠基准被按观测改正」的时刻。
     * 用途：验证时据此**重建票据的期望值** —— 票据里的 expectedAfterFen 是**发票据那一刻**
     * 冻结的，基准改了它不会变，于是验证会永远「装饰容差下有 N 种应手解释，不唯一」
     * （真机实测：基准与补齐表都已改对，卡在期望值上）。
     */
    driverState.baselineCorrectedAt = 0;
    /**
     * 「杀棋待佐证」闸门里按住的那手棋（见 finishCompute 的闸门与 tick 的放行/作废）。
     * 字段：{ jobId, board, boardKey, side, iccs, obsAtStart, outcome }。
     * 规则：算棋那一帧还没被**独立观测**复核之前，我方杀棋不许点击。
     */
    driverState.pendingMate = null;
    /**
     * 最近一次完成的观察摘要（供 finishCompute 判「算棋之后有没有出现过干净的第二帧」）：
     * { obs: 观测计数, unknown: 未知格数, covered: 按基准填回的格数, key: 0 未知时的局面 key }。
     * 失败/异常的观察置 null。
     */
    driverState.lastObsSummary = null;
    /**
     * 下一次观察强制「全盘新鲜重读」（杀棋复核用：跳过差分缓存，见 observeOnce 的 request.freshRead）。
     * 背景：差分管道只读变化格，栏位被「按基准填回」后它永远看不出变化 ——
     * 「两帧同错」只能靠一次不共享缓存的完整重读来打破。
     */
    driverState.forceFreshReadOnce = false;
    /** 最近一次起算棋时的观测计数（finishCompute 用它判断「算棋这一帧有没有被第二帧复核过」） */
    driverState.lastComputeObs = 0;
    /** 上一次「算棋失败」的盘面签名（同一张盘面不重复把 FEN 灌进日志；2026-09-21 真机排障） */
    driverState.lastComputeFailKey = null;
    /** 「轮到的一方 0 合法着法」连续命中的次数（盘面自检用；2026-09-21） */
    driverState.noMoveStreak = 0;
    /** 「我方杀棋」的评分下限：引擎的杀分 = MATE(30000) - ply，这条线以上都按杀棋对待。 */
    driverState.MATE_CLAIM_MIN_SCORE = 29800;
    /** 上一句「观察层补齐」的说明文案（只用于去重打日志，免得每秒刷屏） */
    driverState.lastFillText = "";

    // ---- 「游戏不接受的着法」黑名单（按局面记，用于让引擎换一手） ----
    // 症状（K40 真机实测，2026-09-19）：点了 c2c6 局面毫无变化 → 确认 UNCHANGED → 状态机重试
    // 1 次仍 UNCHANGED → RECOVERING → 重新规划又选中**同一手**（引擎是确定性的）→ 又点又不变
    // → 永远走不完一局。这里按「基准局面 | 该走棋方」记住被拒的着法，规划时传给引擎排除。
    driverState.rejectedMoves = new Map();

    // 计划历史仅用于诊断；不能把重复计划当作已确认的连续长将。
    // 重复责任交给带已核验棋谱的 Pikafish 判断。
    driverState.planHistory = loopHistoryModule.createLoopHistory();
    /**
     * ★ 求胜模式的「局面出现次数」账本（见 win-seek.js / cfg.winSeek）：
     *   每落定一手（trackConfirm / trackInferredMoves）就记一次新局面；
     *   查询时看「走完这一手会不会回到记过的局面」（≥ winSeekMinSeen 进入软偏好名单）。
     */
    driverState.winSeekSeen = winSeekModule.createSeenPositions();
    driverState.winSeekShuttles = winSeekModule.createConfirmedShuttles();
    /**
     * 上一次记进求胜账本的「局面+轮次」指纹：**同一局面连着记两次只算一次**。
     * 为什么需要：一手可能同时走「确认」（trackConfirm）与「推断」（trackInferredMoves）两条路，
     * 不防重的话一个局面会被记两遍 ⇒ winSeekMinSeen=2 退化成 1（真机日志里那种误触发）。
     */
    driverState.lastWinSeekKey = null;
    /** 最近一次成功算棋的评分（红方视角 cp）——求胜模式的「不落后」闸门用它 */
    driverState.lastThinkScore = null;
    driverState.lastWinSeekNoteAt = 0;
    /** 「改走非兑子候选」那条日志的独立限频（它与「有兑子候选」共用窗口会被吞掉） */
    driverState.lastSwapSkipNoteAt = 0;

    // ★ 「回合世代」：每刷新一次可靠基准（= 一手棋真正落定 + 验证过）就 +1。
    //   同一个世代里「同局面 + 同手」连着出现只算一次（那是取消重算/重复发令，不是一个新回合）；
    //   跨世代的同局面同手才是**真摆回来**（长将/长捉），必须照记，第 3 次才禁得掉。
    //   真机 2026-09-21：09:08 一次 CANCEL 重算把 b2c2 记了两遍（误触禁手）；而 09:17/09:20
    //   两次 d6e6 是双方真的把局面摆回来了（该禁）—— 两者必须区分开。
    driverState.decisionEpoch = 0;

    /**
     * 未知格的最后一道证据（2026-09-20，用户提议「YOLO 只定位一次，剩下用找色判走动、OCR 认子」）：
     * 模型整格读不出来（高亮环/木纹压字）时，直接对那一格问 **OCR（认什么子）+ 像素找色（判哪一边）**。
     *
     * 为什么必须有它：真机 v7 局卡死 —— 盘面上 (2,0) 一格未知 ⇒ 「存在未知格，不能生成 FEN」
     * 每轮抛异常 ⇒ RECOVERING 空转（连重建基准都做不了）。就差这一格，而 OCR + 找色本来就能给出来。
     * 返回棋子字符（如 'b' / 'C'）；拿不到证据（不可用/没字/阵营判不出）返回 null —— **不猜**。
     */
    driverState.evidenceAt = {};
    /** 每一格「连续未知」的帧数（给「顽固未知→按可靠盘面兜」用，见 unknownFillFromReliableFrames） */
    driverState.unknownStreak = {};
    /**
     * 兜底值被「兵种上限」**连续驳回**了几帧（index → 帧数；真机 2026-09-21 10:33 的 (5,7) 死锁）。
     * 驳回只说明基准那一格过时了（同一兵种已经满额）——连续几帧都这样就把那一格判空
     * （棋子不会分身，基准又拿不出第三枚），见 observeOnce 填充环节里的处置。
     */
    driverState.capRejectedStreak = {};
    /**
     * 「轮次因为未知格而推不出来」已经连续多少帧（now39 的真机账：25% 的时间花在这条路上）。
     *
     * 为什么需要它：看门狗是 4s，而「顽固未知→按可靠盘面兜」要 4 帧（≈ 4×1.2s = 4.8s）
     * ⇒ **看门狗总是先动手**：日志里就是「3 次『本轮有 1 个未知格』→ RECOVERING ~5s」，
     * 而本来再等一帧就可以按「未知 = 没变」往前走了。
     * 处置：一旦**确实已经被未知格挡住**（下一帧还是未知、轮次还是未知），
     * 就把这一格的兜底门槛从 `unknownFillFromReliableFrames` 放宽到 2 帧 —— 只影响“本来就要卡住”
     * 的情况，不影响正常节奏（now33 的教训是**无条件**提前填值更差，这里不是那种改法）。
     */
    driverState.unknownSideStreak = 0;
    /** 「被挡住就按基准兜」的粘住表：index → 到期时刻（ms）。见 stallFillHoldMs */
    driverState.stallFilledUntil = {};
    /**
     * 本帧「顽固未知 → 按旧基准填」的格子（= 被游戏「上一步」标记盖住、读不出来的格）。
     * 轮次推断拿它做「被盖住的应手」唯一合法着法枚举（见 sideFor）。
     * 真机 K40 2026-09-21 死区：黑炮 b7b5 的起点/落点被盖住 ⇒ 每帧填回旧值 ⇒ 盘面永远没变化 ⇒
     * 轮次停在「轮到对方」5 分钟，直到被绝杀。
     */
    driverState.coveredFilledIndexes = [];
    /**
     * 本帧被**证据**（全盘 OCR / 找色 / 单格重拍读出）写过的格（2026-09-25）：
     * 兵种上限体检（piece-cap-audit）只回滚「补齐/旧基准填回」这类猜测写出的格；
     * 证据格不回滚 —— 否则会把「全盘 OCR 改正（兵种取字形）」这类正确的纠正也顶回去
     * （自检 3010 用例当场抓到过）。每帧观察开头重置。
     */
    driverState.evidenceIndexes = [];

    /**
     * 恢复之后要「按观测重建基准」吗（用户口径 2026-09-20：**用视觉重新对局面**）。
     *
     * 为什么要有它（真机病根）：一枚**在飞的子**被「单格 OCR 判空」从可靠基准里删掉之后，
     * 它落位时「期望里没这枚子、观测里有」⇒ 永远解释不了 ⇒ 一直在 RECOVERING 里「改正/等」。
     * 旧做法只会拿旧基准 + 一堆已知事实去凑原来那张盘面，越凑越乱；
     * 正确做法是：恢复的时候**丢掉全部已知事实，直接用这一帧的视觉读数当基准**
     *（YOLO 定位 → 找色 → OCR 融合本来就是逐帧重读的），轮次交给下一帧的变化去推。
     */
    driverState.needFreshBaseline = false;

    return {
        config: driverState.cfg,
        searchWorker: driverState.workerSearch,
        machine: driverState.machine,
        executor: driverState.executor,
        disabledTap: driverState.disabledTap,
        clickAllowed: driverState.clickAllowed,
        start: driverActions.start,
        /** 最近着法（画板标注用）：{ own: {iccs, at} | null, opponent: {iccs, at} | null } */
        lastMoves: function () {
            return driverState.lastMoves;
        },
        /**
         * 安全网：一轮里的异常不能把整个自动循环带走。
         * 真机踩过：从我们自己的界面刚切到游戏时截屏超时，`locateBoard` 抛异常直接结束脚本。
         * 这里只记数 + 返回 ok:false（**不**把状态机推到 ERROR），下一轮还有机会成功。
         */
        tick: function () {
            try {
                return driverActions.tick();
            } catch (error) {
                driverState.stats.errors += 1;
                driverState.stats.lastError = String(error && error.message ? error.message : error);
                driverActions.note("本轮异常（已跳过，下一轮重试）：" + driverState.stats.lastError);
                return { ok: false, reason: driverState.stats.lastError, state: driverState.machine.state() };
            }
        },
        isRunning: function () {
            return driverState.running;
        },
        /** 还在 worker 里算棋，或算棋被推迟（等预思考退出的空档）。界面用它决定要不要缩短轮询间隔。 */
        isThinking: function () {
            return !!driverState.pendingCompute || !!driverState.deferredCompute;
        },
        /** 非阻塞收算棋结果：宿主自己起定时器轮询时可以直接调它。 */
        pollCompute: driverActions.pollCompute,
        computeMode: function () {
            return driverState.computeMode;
        },
        workerStats: function () { return driverState.workerSearch.stats(); },
        close: function () {
            driverState.running=false;
            driverState.workerSearch.close();
            driverState.pendingCompute=null;
            driverState.deferredCompute=null;
        },
        pause: function () {
            return driverActions.pump(driverState.machine.dispatch({ type: "PAUSE" }));
        },
        resume: function () {
            return driverActions.pump(driverState.machine.dispatch({ type: "RESUME" }));
        },
        stop: function () {
            driverState.running = false;
            driverState.workerSearch.cancel();
            driverState.pendingCompute = null;
            driverState.pendingMate = null;
            driverState.knownFacts.clear();
            driverState.frameConsensus.clear();
            driverState.phantomStreak = {};
            driverState.needFreshBaseline = false;
            driverState.prevOccupancy = null;
            driverState.flipEpoch = {};
            driverState.replyFastPending = null;
            driverActions.clearBaselineBorrow();
            return driverActions.pump(driverState.machine.dispatch({ type: "STOP" }));
        },
        setMySide: function (side) {
            driverState.machine.setMySide(side);
            driverActions.resetOwnMoveCount(); // 开局库步数：换我方 → 步数重新数
            driverState.winSeekSeen.clear(); // 求胜模式：换我方 → 局面重新记（旧的“轮到谁”全变了）
            driverState.lastWinSeekKey = null;
            driverState.lastReliable = null;
            driverActions.clearBaselineBorrow();
            driverActions.trackClear("side-switch", true);
            driverState.frameConsensus.clear();
            driverState.phantomStreak = {};
            driverState.needFreshBaseline = false;
            driverState.pendingMate = null;
            driverState.planHistory.clear();
            driverState.settledRaw = null;
            driverState.prevOccupancy = null;
            driverState.flipEpoch = {};
            driverState.replyFastPending = null;
        },
        mySide: function () {
            return driverState.machine.mySide();
        },
        /** 用户显式声明「现在轮到我」：把当前观察到的局面当作可靠基准。 */
        assertTurn: function () {
            if (!driverState.lastBoard) {
                return { ok: false, reason: "还没有识别到局面（先跑一轮观察）" };
            }
            // ⚠️ 基准里不能有未知格：它是后面「已知事实补齐」的唯一依据，
            // 带 '?' 的基准会把 '?' 当成事实抄回去（真机踩到：报告「已补齐」后 buildFen 抛异常）。
            const bad = driverState.lastBoard.indexOf("?");
            if (bad >= 0) {
                return {
                    ok: false,
                    reason:
                        "当前局面还有未知格（第 " +
                        (Math.floor(bad / 9) + 1) +
                        " 行第 " +
                        ((bad % 9) + 1) +
                        " 列），不能当基准：等它认出来，或先把棋盘「菜单→重来」清成干净开局"
                };
            }
            driverActions.clearBaselineBorrow(); // 人工声明 = 全新基准：对账账本作废
            driverState.lastReliable = { board: driverState.lastBoard.slice(), side: driverState.machine.mySide() };
            driverActions.trackAdopt(driverState.lastBoard, driverState.machine.mySide(), {
                reason: "assert-turn",
                source: "manual-confirmed"
            });
            // 声明后是一张**新基准**：旧的「连续帧共识」不再适用，清掉重算
            driverState.frameConsensus.clear();
            driverState.phantomStreak = {};
            driverState.needFreshBaseline = false;
            driverActions.note(
                "用户声明轮到" + (driverState.machine.mySide() === "w" ? "红方（我方）" : "黑方（我方）")
            );
            // ★ 声明必须能**推动状态机**：只在 WAIT_TURN 那一条路上生效的话，
            //   一旦已经进了 RECOVERING（轮次未知看门狗），声明就再也出不来 ——
            //   真机 2026-09-20：面板日志只刷「RECOVERING｜只恢复/等待」直到 stopAfterMs。
            driverActions.pump(driverState.machine.dispatch({ type: "TURN_DECLARED", reason: "用户声明轮到我" }));
            return { ok: true, reason: "已把当前局面当作基准" };
        },
        /** 换局/重启：会话 +1，旧票据与基准全部作废。 */
        newSession: function () {
            return driverActions.resetSession();
        },
        grid: function () {
            return driverState.grid;
        },
        /**
         * 面板发布新几何（「盲读兜底」重定位成功等）：采用它，并作废「几何可疑」窗口 ——
         * 那个判决针对的是**旧**几何；不清掉的话，面板的「几何沿用」快路径会白等 60s。
         * （2026-09-26 真机：此前面板改了几何从来不同步给驱动 ⇒ 驱动死守启动时的坏几何、
         *   每帧「检测框微调越界」拒收 ⇒ 重定位-拒收无限循环，一步棋都走不出来。）
         */
        setGrid: function (value) {
            driverState.grid = value;
            driverState.geometryStaleUntil = 0;
        },
        /**
         * 面板滑条：改算棋时间预算（下一次 COMPUTE 生效，不用重启循环）。
         * 驱动自己的 cfg 与状态机的 cfg 都要改：状态机发 COMPUTE 效果时读它自己的 cfg（panel 可滑）。
         */
        setMovetime: function (ms) {
            const value = Math.round(Number(ms));
            if (!isFinite(value) || value < 100 || value > 60000) {
                return null;
            }
            driverState.cfg.movetimeMs = value;
            if (driverState.machine && driverState.machine.config) {
                driverState.machine.config.movetimeMs = value;
            }
            return value;
        },
        setStrategy: function (values) {
            ["adaptiveSearch", "criticalReview", "prethink", "openingLearning"].forEach(function (key) {
                if (values && typeof values[key] === "boolean") driverState.cfg[key] = values[key];
            });
        },
        /**
         * 开局库步数（2026-09-25 用户：「开局用开局库走到第 N 手，之后引擎接手算棋」）：
         * N = 前 N 手允许用库（0 = 不用库；null = 不设限）。下一次算棋生效。
         */
        setBookOwnMoves: function (count) {
            if (count == null || count === "") {
                driverState.cfg.bookOwnMoves = null;
                return null;
            }
            const value = Math.max(0, Math.min(60, Math.round(Number(count))));
            if (!isFinite(value)) {
                return null;
            }
            driverState.cfg.bookOwnMoves = value;
            return value;
        },
        bookOwnMoves: function () {
            return driverState.cfg.bookOwnMoves;
        },
        /**
         * 开局库候选随机（2026-09-25 用户：「开局库好像每次都一样，搞随机的吧」）：
         * true = 从候选里抽签（默认），false = 总用主着法。下一次算棋生效。
         */
        setBookRandom: function (on) {
            driverState.cfg.bookRandom = on !== false;
            return driverState.cfg.bookRandom;
        },
        bookRandom: function () {
            return driverState.cfg.bookRandom;
        },
        /**
         * 几何可疑查询（2026-09-25）：检测框报过「原点偏移超护栏」后的窗口内为 true。
         * 面板的「几何沿用」快路径必须在此时让路（走全量校准）—— 否则会拿错位置的几何去读格子。
         */
        geometrySuspect: function () {
            return driverState.nowMono() < driverState.geometryStaleUntil;
        },
        /**
         * 求胜模式（2026-09-25 用户：「老是和棋」）：均势时避开「会走回旧局面」的着法。
         * @param on true/false（面板的「求胜」按钮）
         */
        setWinSeek: function (on) {
            driverState.cfg.winSeek = on === true;
            return driverState.cfg.winSeek;
        },
        winSeek: function () {
            return driverState.cfg.winSeek;
        },
        ownMoveCount: function () {
            return driverState.ownMoveCount;
        },
        setSearchDepth: function (depth) {
            const value = Math.round(Number(depth));
            if (!isFinite(value) || value < 0 || value > 40) {
                return null;
            }
            driverState.cfg.searchDepth = value;
            return value;
        },
        setDepthTimeout: function (ms) {
            const value = Math.round(Number(ms));
            if (!isFinite(value) || value < 1000 || value > 30000) {
                return null;
            }
            driverState.cfg.depthTimeoutMs = value;
            return value;
        },
        /** ★ 2026-10-06：对面应手预判档位（策略菜单：关/快/标准/深）——下一次预判任务生效。 */
        setPredictReply: function (opts) {
            const o = opts || {};
            if (o.predictReply != null) driverState.cfg.predictReply = o.predictReply !== false;
            const ms = Math.round(Number(o.movetimeMs));
            if (isFinite(ms) && ms >= 200 && ms <= 5000) driverState.cfg.predictReplyMovetimeMs = ms;
            const n = Math.round(Number(o.multiPv));
            if (isFinite(n) && n >= 1 && n <= 5) driverState.cfg.predictReplyMultiPv = n;
            return { predictReply: driverState.cfg.predictReply !== false,
                movetimeMs: driverState.cfg.predictReplyMovetimeMs, multiPv: driverState.cfg.predictReplyMultiPv };
        },
        /** Pikafish 线程等参数：写入驱动 cfg，下次算棋生效（worker/inline 两条路都从 cfg 取）。 */
        setPikafish: function (opts) {
            driverState.cfg.pikafish = opts || null;
            return driverState.cfg.pikafish;
        },
        lastResult: function () {
            return driverState.lastResult;
        },
        predictionForDisplay: function () {
            const p = driverState.predictedReplies;
            return p ? {key:p.key, list:p.list.slice()} : null;
        },
        lastBoard: function () {
            return driverState.lastBoard ? driverState.lastBoard.slice() : null;
        },
        lastReliable: function () {
            return driverState.lastReliable
                ? { board: driverState.lastReliable.board.slice(), side: driverState.lastReliable.side }
                : null;
        },
        /** A3：事实层快照（只读；影子期供测试/面板审计用）。 */
        tracker: function () {
            return driverState.positionTracker.snapshot();
        },
        stats: function () {
            return Object.assign({}, driverState.stats, {
                clicksByExecutor: driverState.executor.stats ? driverState.executor.stats().clicks : 0,
                disabledTapCalls: driverState.disabledTap.calls(),
                machine: driverState.machine.counters()
            });
        },
        history: function () {
            return driverState.history.all();
        },
        describe: function () {
            const lines = [];
            lines.push("状态：" + driverState.machine.state() + "（" + driverState.machine.reason() + "）");
            lines.push(
                "我方：" +
                    (driverState.machine.mySide() === "w" ? "红" : "黑") +
                    "  轮次基准：" +
                    (driverState.lastReliable ? (driverState.lastReliable.side === "w" ? "红" : "黑") : "未知") +
                    "  几何：" +
                    (driverState.grid ? "v" + driverState.grid.version : "无")
            );
            const counters = driverState.machine.counters();
            lines.push(
                "观察 " +
                    driverState.stats.observations +
                    " 次（未识别 " +
                    driverState.stats.unrecognized +
                    "）" +
                    "，点击 " +
                    (driverState.executor.stats ? driverState.executor.stats().clicks : 0) +
                    " 次" +
                    "，确认 " +
                    counters.confirms +
                    " 次，重试 " +
                    counters.retries +
                    " 次"
            );
            lines.push(
                "算棋：" +
                    driverState.computeMode +
                    "（worker " +
                    driverState.stats.thinkOffThread +
                    " 次 / 本线程 " +
                    driverState.stats.thinkInline +
                    " 次，最近一次 " +
                    Math.round(driverState.stats.thinkMs) +
                    "ms）"
            );
            lines.push(
                "落点补齐 " +
                    driverState.stats.ownMovePatched +
                    " 次（其中同字异色 " +
                    driverState.stats.sameGlyphPatched +
                    " 次）、已知事实补齐 " +
                    driverState.stats.knownFactFills +
                    " 次、本手漏检补齐 " +
                    driverState.stats.freshEmptyFilled +
                    " 次、按棋规改正 " +
                    driverState.stats.ruleFixes +
                    " 格、全盘 OCR 改正 " +
                    driverState.stats.ocrBoardFixes +
                    " 格（查 " +
                    driverState.stats.ocrBoardChecks +
                    " 次、待定 " +
                    driverState.stats.ocrBoardPending +
                    "、驳回 " +
                    driverState.stats.ocrBoardBlocked +
                    "）、单格 OCR " +
                    driverState.stats.ocrChecks +
                    " 次（确认有子 " +
                    driverState.stats.ocrConfirmed +
                    " / 判空格作废 " +
                    driverState.stats.ocrRejected +
                    "）、" +
                    "催游戏 " +
                    driverState.stats.stuckNudges +
                    " 次（放弃无法证实的已知事实 " +
                    driverState.stats.stuckFactsDropped +
                    " 条）、" +
                    "单格重拍 " +
                    driverState.stats.reprobes +
                    " 次（找回 " +
                    driverState.stats.reprobeFilled +
                    " 格）、" +
                    "跨帧佐证暂缓 " +
                    driverState.stats.corroborateWaits +
                    " 次（应手 " +
                    driverState.stats.replyHolds +
                    " 次）、" +
                    "颜色纠正 " +
                    driverState.stats.colorFlips +
                    " 格（游戏给刚落的子加装饰，那几格认不准）"
            );
            if (!driverState.clickAllowed) {
                lines.push("模式：不点击（拦截了 " + driverState.disabledTap.calls() + " 次点击调用）");
            }
            return lines.join("\n");
        }
    };
}

module.exports = {
    DEFAULTS: DEFAULTS,
    createDriver: createDriver
};
