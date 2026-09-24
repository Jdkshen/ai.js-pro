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
const frameConsensusModule = require("./frame-consensus.js");
const knownFactsModule = require("./known-facts.js");

/**
 * 合法棋子字符（FEN 字母，大写红、小写黑）。
 * 全盘 OCR 补正只认这 14 个字符：`?`、空串、其它字形一律**不写**盘面与可靠基准。
 */
const PIECE_CHAR_RE = /^[rnbakcpRNBAKCP]$/;

const DEFAULTS = {
    /** 每轮观察间隔（ms）。识别一轮约 200ms，留出余量给界面。 */
    tickMs: 900,
    /**
     * 每次观察抓几帧做合并（默认 2）。两帧一致的格子才采信，不一致的降级为未知格 ——
     * 模型单帧会有多处孤立误读（真机：一帧内 3~4 处），不合并就会把假盘面喂给轮次推断。
     */
    observeFrames: 2,
    /** 多帧之间的间隔（ms）：留一点时间让游戏动画/装饰变化，避免两帧拿到同一个坏帧。 */
    observeGapMs: 260,
    /** 模型版本号（写进票据；换模型要让旧票据失效）。 */
    modelVersion: "chessai_160x320_v5r",
    /** 算棋时间预算（ms）。 */
    movetimeMs: 3500,
    /**
     * 观察帧数按状态分级（2026-09-21 提速·方案第 4 条）：等待对方（WAIT_TURN）时只用 1 帧，
     * 出手前/确认/恢复仍用 observeFrames（2 帧）——只降等待阶段的空转成本，不动关键判断的帧数。
     * 默认关：自检夹具与老宿主行为不变；面板侧显式打开。
     */
    observeFramesByState: false,
    /**
     * 算棋跑在哪里："worker" = 独立引擎线程（默认，不阻塞悬浮窗）；
     * "inline" = 老路径（本线程同步算，仅用于对照/宿主没有 threads 时）。
     * "auto" = 有 threads 用 worker，否则 inline。
     */
    computeMode: "worker",
    /** worker 算棋的结果超时（ms）：算不出来也要有结论，别把状态机永远卡在 THINKING。 */
    computeGraceMs: 4000,
    /**
     * 算棋引擎（实际选路在 engine-router）："auto"（默认）/ "pikafish"（保留键名）——
     * 两者现在都只走 Pikafish（内置 JS 引擎已移除）。worker 与 inline 两条路都会带上它，
     * 避免「面板显示的和 worker 里用的不是一个后端」这种半生效状态。
     */
    engineKey: "auto",
    /** Pikafish 配置（{evalFile, threads, hashMb}）；null = 用适配器默认。 */
    pikafish: null,
    /** 检测置信度。 */
    confidence: 0.25,
    /** 棋盘方向。 */
    orientation: "RED_BOTTOM",
    /** 棋盘大致范围（占屏幕比例 {x, y, width, height}）；null = 用 locate-board 的默认值。 */
    regionRatio: null,
    /** 每隔多少轮强制重新定位一次（0 = 只在恢复时定位）。 */
    relocateEveryTicks: 0,
    /**
     * 同一「可靠局面 + 同一着法」最多完整尝试几次（含第一次）。
     * 真机教训：点击明明发出、棋子却不落时，旧逻辑会一直重算同一手、一直重点
     * （换新票据/恢复成功都不算取得进展）→ 一整轮就烧在同一个局面上。
     */
    attemptLimitPerMove: 3,
    /** 同一可靠局面（不区分着法）总共最多尝试几次，防止不断换着法绕过上面的上限。 */
    attemptLimitPerPosition: 5,
    /**
     * ROI 快速确认（2026-09-21 提速·方案第 3 条）：点击后**先只认起点/终点两个格**，
     * 两格硬证据齐了（起点读空 + 落点读到预期子）直接 CONFIRMED，省掉一次全盘识别；
     * 任一条拿不出来就照旧走全盘确认（宁慢勿错）。
     * 需要宿主提供 `ocrCellsNow(indexes)`（抓一帧认多格）；没有它就整条跳过（自检/老宿主不受影响）。
     */
    fastConfirmRoI: false,
    /** ROI 快速确认：首探前的等待（ms，给落子动画）/ 两次探测的间隔（ms）/ 最多探测几次。 */
    fastConfirmSettleMs: 300,
    fastConfirmGapMs: 350,
    fastConfirmAttempts: 2,
    /**
     * 对方应手「占用唯一解」快路（2026-09-21，用户提议「提前推理对手的棋」）：
     * 等待对方时，如果**占用扫描**显示「某格刚空 + 某格刚有对方颜色的子」，
     * 而对方**全部合法着法里恰好只有一手**对得上 ⇒ 直接采信那一手
     * （盘面 = 基准 + 这一手），**完全不用等 OCR 读出兵种**。
     * 为什么可行：起点格只住一枚子，它在对方回合里变空只可能是刚刚那一手造成的 ⇒（起点,落点）唯一确定着法；
     *   「起点空 + 落点是对面颜色」本来就是唯一敢信的硬证据（与 ROI 快速确认同一口径）。
     * v3 加固（真机 2026-09-21，用户口径「一直扫描对方的棋有没在动、动完再判断」）：
     *   「变化格 ⊆ {起点,落点}」太严（落子帧常夹装饰闪烁，实测变化 4 格）；「只留最近两帧」也不够
     *   （等起点格的星光残留消退、读数干净时「新鲜度」早过期 ⇒ 应手放跑、白等 45 秒）。现在：
     *   驱动自算相邻帧占用差 → **整个对方回合累积翻面账本（flipEpoch）** + 同一手连续两帧佐证
     *   （挡「飞行中的子被画在途经格」这种单帧看起来完全像真的假候选）。
     * v4 加固（同晚再踩）：推理**必须用「observeOnce 之前」的基准快照**——fills/纠错会在同一帧里
     *   先撤已知事实、清幻影，把基准改得「那一手不存在了」（真机：对方卒走了一格却因此永远拼不出候选）。
     * v5 加固：fills 的改动会**跨帧留在基准里**（真机车 f0f1：下一帧基准已变，候选重新枚举不出来）⇒
     *   候选连同「冻结的基准快照 + 着法」一起保存，佐证只验「起点仍空 + 落点仍是该方颜色」，采纳时用冻结快照重建基准。
     * 需要宿主每帧带 `occupancy`（占用扫描）——面板 diff 链路（含全盘回退帧）已提供；
     * 老宿主/旧链路没有它时整条自动跳过（行为与以前完全一致）。
     */
    replyFastPath: true,
    /**
     * 「对账修订」的借基准时限（ms）：修订（找色作废 / 幻影收编）在「等对方应手」的时刻
     * 改可靠基准时，可能把对方**刚走完的那一手**抹平（起点被置空、落点被当幻影收编）⇒
     * sideFor 借回「修订前的基准」重算轮次（修订+观测的累计差里带着那一手）；
     * 超过这个时限还认不出来就放弃、回到修订后的基准（即老行为）。
     * 8s ≈ 4~8 帧观测；真机对账一般 2~5 帧内出结果（应手动画全程 ~1s）。
     */
    baselineBorrowMs: 8000,
    /**
     * 「单格重拍」：已知事实说这格**有子**、模型却整格读成空时，换一帧、**降低检测阈值**
     * 重认那几个格子（只采信这几格）。设为 0 可关闭。
     * 为什么需要：整格读成空 = 检测框都没检出（阈值 0.25），而模型 mAP50 只有 0.53，
     * 漏检很常见 —— K40 实测对方刚落下的黑炮每帧都读成空，留着 1 个未知格就永远推不出轮次。
     */
    reprobeConfidence: 0.12,
    /** 单格重拍的分类阈值（比常规的 0.45 低：宁可收一个低分框，也别把棋子整格丢掉）。 */
    reprobeMinScore: 0.28,
    /** 同一格两次单格重拍之间的最小间隔（ms）：不然每帧都多烧一次截图。 */
    reprobeCooldownMs: 4000,
    /**
     * 已知事实说有子、模型却连续这么多帧读到「整格空」⇒ 认为**画面卡住了**（不是模型漏检）。
     * 依据：天天象棋是**人机对战**（电脑 AI 瞬间应手），应手动画最多 1 秒就该走完；
     * 一连好几帧还读不出来，就是「走子动画卡在中间」——
     * 真机实测（K40，2026-09-19）：黑炮 1,2→4,2 卡住，棋子是隐形的，(1,2) 空了，
     * (4,2) 只剩一个闪烁的落点光环 ⇒ 那两格永远读不出子 ⇒ 每帧差一格未知 ⇒ 轮次未知空转。
     */
    stuckEmptyStreak: 3,
    /** 催游戏的最小间隔（ms）：每帧都催会把游戏和面板一起搞乱 */
    stuckNudgeCooldownMs: 6000,
    /** 催了这么多次仍然读不出来 → 放弃那条已知事实，改按观测算（见 noteStuck） */
    stuckNudgeLimit: 2,
    /** 同一格单格 OCR 之间的最小间隔（ms）：OCR 一次要裁图 + 跑检测，不必每帧都问 */
    ocrCooldownMs: 4000,
    /**
     * 「单格 OCR 判空」的跨帧佐证间隔（ms）：同格**两次** OCR 都说「没读到字」才算真空格。
     *
     * 为什么必须佐证（2026-09-19，用户截图实锤）：黑方那手棋的**棋子还在飞行中**时，
     * 画面上那一格是隐形的 ⇒ OCR 读不到字 ⇒ 当时直接当「空格」把**真实**的已知事实丢了，
     * 还把可靠基准一起改错 ⇒ 之后所有着法都「解释不了」，整局停住。
     * 两次读数至少隔这么久（远大于一手动画），棋子早该落位了。
     *
     * ⚠️ 2026-09-20 又一次真机实锤（新局 h4h9）：1500ms **不够** —— 对方那枚炮被吃前**在飞**，
     *   两次「读空」（相隔 ~1.5s）就把 (4,2) 那枚炮从可靠基准里删掉了
     *   （日志：`可靠基准已按观测改正那格（(4,2) 置空）` → 下一帧 `UNEXPECTED：与基准差 3 格 (4,6) P→c`，
     *   也就是对方那枚子落到目的地时基准里已经没有它了）。动画本身 ~1s，但**飞行 + 命中特效 + 高亮环**
     *   能让那一格隐形 2 秒以上。所以门槛抬到 3000ms；真·卡死的场景仍由后面的
     *   「连续读空 N 帧 → 催游戏 → 仍不行才放弃」那条路兜底（不会因此死等）。
     */
    ocrEmptyCorroborateMs: 3000,
    /**
     * 全盘 OCR 证据的限频（ms）：一次 90 格 OCR ≈1.2s，不必每帧都问。
     * 它修的是**同色不同兵种**的误读（模型把炮认成兵/卒）——那个只看模型自己永远发现不了。
     */
    /**
     * 全盘 OCR 的限频（ms）。
     * ⚠️ 2026-09-20：6s → **12s**。一次全盘 OCR 真机 ~1067ms（面板侧要抓帧 + 逐格扫描），
     * 6s 一次就占了整局 ~15% 的时间；而它要抓的是**持续性**的误读（占位不变、兵种读错），
     * 12s 一次照样抓得到 —— 单帧急事另有「已知事实补齐 / OCR+找色证据补齐」两条快路。
     */
    ocrBoardCooldownMs: 12000,
    /**
     * 全盘 OCR 补正（`applyOcrBoardFixes`）写进盘面/**可靠基准**前，同一格要**连续这么多帧**
     * 给出同一个字。
     * ⚠️ 2026-09-20 真机：单帧 OCR 直接写基准 ⇒ 基准里出现重复黑炮 `(4,4)+(4,5)`、红帅丢失之类
     *    的漂移，之后每帧都「解释不了局面」→ 确认阶段永远 UNRELIABLE。
     * 一帧 = 一次全盘 OCR 询问（限频 ≥`ocrBoardCooldownMs`，所以两帧至少隔 6 秒）。
     */
    ocrPatchFrames: 2,
    /** 补正会**翻颜色**（模型说红、像素说黑）时要求的帧数：颜色一改，票据期望全变，所以更严 */
    ocrPatchFlipFrames: 3,
    /**
     * 一格**顽固未知**多少帧之后，允许拿「上一张可靠盘面」的值兜（未知 = 没变）。
     *
     * 为什么必须有（真机 v8，2026-09-20）：盘面上 `(2,0)` 一格读不出来、连线格 OCR+找色也拿不出证据
     *（高亮环把字盖住）⇒「存在未知格，不能生成 FEN」**每轮抛异常** ⇒ RECOVERING 空转，整局停住。
     * 宁可按「那格没变」先往前走（下一帧读出来会立刻纠正），也不要因为一格看不清就整局不动。
     *
     * ⚠️ 2026-09-20 真机复测两次都是「等」比「猜」好，别调小：
     *   · 4 → 2（now33）：拿旧值硬填更早 ⇒ 观测变假 ⇒ 点击 1 次、推不出来 30 次、清掉幻影 30 次；
     *   · 「静止就重锚」（now31）同样更差（重锚 38 次空转）。
     *   要治的是**读不出来这件事本身**（动画期判定 / 多把钥匙重读），不是把等待缩短。
     */
    unknownFillFromReliableFrames: 4,
    /**
     * 「轮次被未知格挡住 ⇒ 提前兜」的那一格要**粘住**多久（ms）。
     *
     * 为什么需要（now39 复盘）：兜底会把那一格的「连续未知」计数清零 ⇒ 下一帧它又变成未知格、
     * 又要重新攒 2 帧 ⇒ 观测一帧完整一帧不完整地在「轮次已知/未知」之间抖，机器拿不到稳定快照。
     * 粘住 3 秒就稳了；那一格一旦真的读出字，或者基盘换了一张，就立刻放下（见 stallFilledUntil）。
     */
    stallFillHoldMs: 3000,
    /**
     * 「观察撞上走子动画 ⇒ 丢掉最早那帧、补抓一帧重合并」的开关与间隔。
     *
     * ⚠️ **默认关**（两轮真机 A/B：now34/35 vs now32）：补帧确实命中（日志 5 次），
     * 但**只要有未知格就会补**（未知格本来就常见）⇒ 每轮多 ~1.4s ⇒ tick 1203→1761ms、
     * 出招 8→**4** 次（180s 固定窗口），得不偿失。将来要开先收紧触发条件
     *（例如只看“变化格”里的未知格、且一轮只补一次），并重新 A/B。
     */
    observeRetryOnUnknown: false,
    /** 补帧与上一帧的间隔（ms）：留给走子动画收尾 */
    observeRetryGapMs: 420,
    /** 采纳字形的最低 OCR 分：低于它只记录、**不写**盘面与基准（面板过滤过一道，这是第二道） */
    ocrPatchMinScore: 0.6,
    /**
     * 「同字异色」僵持多少帧后，拿**原始读数**复议一次（见 doVerify）。
     * 一帧 = 一次确认验证（约 1~2s）。取 3：装饰抖动撑不过，真被反吃则一直稳定。
     */
    colorDisputeStreak: 3,
    /**
     * 「清掉幻影」僵持多少帧后**改判为基准漏子**并收编（见 clearPhantoms 的 phantomStreak）。
     * 真机 now48：`(5,3)r→空` 每帧清一次、连刷 20+ 次 —— 那枚黑車是真的（对方走过来了，
     * 但那一手没被推断采纳；基准里这枚子的另一份还在原位 ⇒ 计数恒定“多一份”）。
     * 每帧清掉它 = 把真子反复擦掉，而且这枚子永远进不了基准（它的走子永远推不出来）。
     * 取 3：模型的框漂移假子撑不过 3 帧，真子会一直多；且那格真有墨迹才收编。
     */
    phantomAdoptStreak: 3,
    /**
     * RECOVERING 后允许按当前视觉观测重建基准的最大未知格数。
     *
     * ⚠️ 可靠基准不能含 `?`（它是后面「已知事实补齐」的唯一依据，带 '?' 会把 '?' 当事实拄回去）。
     *   所以重建时**用旧已知事实去填那几格**（那是我们对那几格唯一的证据），其余格子一律以本帧视觉为准。
     *   那几格没有事实可填时**不许重建**（宁可等下一帧）。
     * 阈值 0 时几乎永远不会触发 —— 真机实测：恢复后的那一帧常带着 1 个高亮环未知格，
     * 于是“用视觉重新对局面”这条永远走不到，卡死依旧（clean3 那局：恢复 8 次、「轮次推不出」41 次）。
     */
    rebuildMaxUnknown: 2
};

function createDriver(options) {
    const opts = options || {};
    const cfg = defaults.mergeDefaults(DEFAULTS, opts.config);
    const log = opts.log || function () { };
    const nowMono = opts.nowMono || function () { return Date.now(); };
    const clock = opts.sleep || function (ms) {
        if (typeof sleep === "function") {
            sleep(ms);
        }
    };

    if (typeof opts.recognize !== "function") {
        throw new Error("驱动需要注入 recognize(options)（真机用 recognize-board.recognizeScreen）");
    }
    if (typeof opts.locate !== "function") {
        throw new Error("驱动需要注入 locate()（真机用 locate-board.locateBoardOnScreen）");
    }
    if (typeof opts.analyze !== "function") {
        throw new Error("驱动需要注入 analyze(board, side, options)（真机用 engine.analyze）");
    }

    const clickAllowed = opts.allowClick !== false;
    const disabledTap = executorModule.createDisabledTap("只识别 / 着法建议模式：不会点击棋盘");
    const executor = opts.executor || executorModule.createExecutor({
        tap: clickAllowed ? opts.tap : disabledTap.tap,
        nowMono: nowMono,
        waitMs: function (ms) { clock(ms); },
        config: opts.executorConfig
    });
    if (!clickAllowed && !opts.executor) {
        // 只识别/建议模式一律用禁用桩：调用会被计数并报错，验收 A05 查的就是这个计数
        executor.tapIsDisabled = true;
    }
    const machine = opts.machine || machineModule.createMachine({
        mySide: opts.mySide === "b" ? "b" : "w",
        allowClick: clickAllowed,
        movetimeMs: cfg.movetimeMs,
        ticketTtlMs: opts.ticketTtlMs
    }, { nowMono: nowMono });

    const stats = {
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
    stats.ownMovePatched = 0;
    /** 其中「同字异色（红黑读反）」被按已知事实补齐的次数（见 observeOnce 第 ③ 支） */
    stats.sameGlyphPatched = 0;
    /** 「同字异色」僵持后按**原始读数**复议并被采信的次数（见 doVerify；>0 = 真发生了反吃） */
    stats.colorDisputeAdopted = 0;
    stats.knownFactFills = 0;
    /** 颜色读反被纠正的格子数（游戏装饰导致的已知模型弱点） */
    stats.colorFlips = 0;
    /** 多帧合并：做过合并的观察次数 / 因两帧不一致而降级的格子数 */
    stats.mergedFrames = 0;
    stats.frameConflicts = 0;
    stats.sideFixes = 0;          // 「盘面按着法改正」次数（落点格被装饰读错时）
    /** 单格重拍：尝试次数 / 找回的格子数（见 reprobeSuspects） */
    stats.reprobes = 0;
    stats.reprobeFilled = 0;
    /** 「本周期刚登记的事实」被模型整格读空、按漏检补齐的次数（见 observeOnce 空读数那一支） */
    stats.freshEmptyFilled = 0;
    /** 着法推断因「还没拿到第二帧佐证」而暂缓的次数（见 tolerantSupportedByTwoFrames） */
    stats.corroborateWaits = 0;
    /** 对方应手因「还没拿到第二帧佐证」而暂缓采信的次数（见 replyCorroborated） */
    stats.replyHolds = 0;
    /** 「疑似画面卡住」催游戏的次数 / 催后仍然读不出来而放弃已知事实的条数 */
    stats.stuckNudges = 0;
    stats.stuckFactsDropped = 0;
    /** 按棋规改正的格子数（落点必是走子、途经格必空；见 observeOnce 的「按棋子怎么走对账」） */
    stats.ruleFixes = 0;
    /** 清掉的「幻影/重复子」格数（同一枚子在观测里占了两格；棋子不会分身） */
    stats.phantomCleared = 0;
    /** 「幻影」僵持后改判为**基准漏子**并收编进基准的次数（见 clearPhantoms；>0 = 真发生过） */
    stats.phantomAdopted = 0;
    /**
     * 「幻影候选」没敢清掉的次数（两个护栏各一个计数）：
     *   · phantomHeldBack：像素（找色）证据说那格是我方子（防「我方子被读成敌色」被当重复子清掉）；
     *   · phantomHeldByCheck：**拿掉它会改变我方帅的被将状态** —— 2026-09-24 真机：对方那枚
     *     贴脸将军的黑车被「重复子」连着清了 7 分钟 ⇒ 盘面上永远没有将军、引擎一直走别的
     *     （真实棋局全拒收）⇒ 用户看到的「自己被将军了 还在走其他的」。
     */
    stats.phantomHeldBack = 0;
    stats.phantomHeldByCheck = 0;
    /** 全盘 OCR 改正「同色不同兵种」误读的格子数 */
    stats.ocrBoardFixes = 0;
    /** 全盘 OCR 的询问次数（限频：见 cfg.ocrBoardCooldownMs） */
    stats.ocrBoardChecks = 0;
    /** 全盘 OCR 补正里「等连续帧一致」与「被分数/颜色/非法字形驳回」的格数（体检用） */
    stats.ocrBoardPending = 0;
    stats.ocrBoardBlocked = 0;
    /** 被「已知事实已经定死了颜色」驳回的颜色翻转格数（棋子一局里不会改颜色，见 applyOcrBoardFixes） */
    stats.ocrFlipBlocked = 0;
    /** 单格 OCR 检查：次数 / 确认「那格确实有子」次数 / 判为空格而作废已知事实次数 */
    stats.ocrChecks = 0;
    stats.ocrConfirmed = 0;
    stats.ocrRejected = 0;
    /** RECOVERING 后丢弃旧事实并按干净观测重建基准的次数。 */
    stats.baselineRebuilds = 0;
    /** 重建基准后「本帧不出手、等下一帧佐证」的按住次数（见 tick 的 rebuiltThisTick 闸门） */
    stats.rebuildHolds = 0;
    /** 「杀棋待佐证」闸门：按住次数 / 佐证通过放行次数 / 佐证未通过作废次数 */
    stats.mateHolds = 0;
    stats.mateHoldPassed = 0;
    stats.mateHoldVetoed = 0;
    /** 「疑似循环（长捉）」把重复着法交给引擎禁用的次数 */
    stats.loopBanHits = 0;
    stats.budgetPauses = 0;       // 因「同局面/同手重试超限」暂停的次数
    stats.sourceGoneRefusals = 0; // 重试前发现「起点子已不在原位」而拒发的次数（§11）
    stats.rawCorrected = 0;       // 「模型原始读数和最终盘面不一致」的观测次数（§12）
    let lastRawCorrectedText = "";
    /** 「同字异色」僵持计数器：上一次的争议格指纹 + 连续出现帧数（见 doVerify） */
    let lastColorDisputeKey = "";
    let lastColorDisputeStreak = 0;
    /**
     * 「幻影僵持」计数器：格下标 → `{ key: "下标:字", count }`。
     * 同一个格子连着多少帧都在「多出来」⇒ 到了 `cfg.phantomAdoptStreak` 就收编进基准
     *（只有「与上一张可靠基准比」那条路维护它；见 clearPhantoms）。
     * 清空时机：换边 / 新会话 / 声明轮次 / 停止（基准全换了，旧记录不再有意义）。
     */
    let phantomStreak = {};
    /**
     * 「同字异色复议」已裁定的格子（按票据记）：本票之内，那几格**一律以裁定值**为准 ——
     * 不这么做的话，票据里那份**过期的期望值**会每帧再把颜色翻回去（真机实测：
     * 僵持 → 复议 → 确认 → 下一帧又被翻回去 ⇒ 同一张票据循环 49 次、3 分钟不动）。
     * 清空时机：新会话 / 换边（旧票据已经作废，留着没意义）。
     */
    let settledRaw = null;
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
     */
    let predictedReplies = null;
    /** 冲突明细去重用的上一次文本（限频：同一局面不重复刷） */
    let lastConflictText = "";
    // 日志环：超上限**原地裁剪**（以前的 `const history` + `history = history.slice()` 会抛异常）
    const history = logRing.createLogRing(300, 200);

    let running = false;
    let grid = null;
    let lastReliable = null;      // { board, side }：上一可靠局面与「该走棋方」

    /**
     * A3 事实层（**影子接入**）：已确认局面 + 已验证着法账本（src/services/position-tracker.js）。
     *
     * `lastReliable` 仍是驱动的局面读取源；tracker 同步记录，并在算棋时提供已确认着法历史：
     *   · 每处 `lastReliable = …` 旁多一行 `track*()`；
     *   · 账本拒收（非法/与验证盘面不符/与当前局面不连续）→ `stats.trackerRejects`；
     *   · 影子与基准不一致 → `stats.trackerMismatch` + 前几次打日志。
     * 只有 tracker 局面与本次待算局面逐格一致，才向原生引擎传 `positionCommand()`；旧宿主退回当前 FEN。
     */
    const positionTracker = positionTrackerModule.createPositionTracker({
        startFen: fenModule.START_FEN,
        nowMono: nowMono,
        note: function (text) { note("[账本] " + text); }
    });
    stats.trackerMismatch = 0;
    stats.trackerRejects = 0;
    stats.trackerErrors = 0;

    function boardOfFen(fen) {
        try {
            const parsed = fenModule.parseFen(fen);
            return parsed && parsed.ok ? parsed.board : null;
        } catch (error) {
            return null;
        }
    }

    /** 影子守护：tracker 快照与 `lastReliable` 必须一致；不一致只计数 + 前几次打日志。 */
    function trackerMirror(detail) {
        const snap = positionTracker.snapshot();
        const cur = snap.position;
        const same = (!cur && !lastReliable) ||
            (cur && lastReliable && cur.side === lastReliable.side &&
                positionTrackerModule.cellsKey(cur.board) === positionTrackerModule.cellsKey(lastReliable.board));
        if (same) {
            return;
        }
        stats.trackerMismatch += 1;
        if (stats.trackerMismatch <= 5) {
            note("⚠ 账本影子与可靠基准不一致（" + (detail || "") + "）：tracker=" +
                (cur ? cur.side + "/" + cur.board.join("") : "null") + "｜基准=" +
                (lastReliable ? lastReliable.side + "/" + lastReliable.board.join("") : "null"));
        }
    }

    /** 只有账本局面与本次待算局面逐格一致，才可把已确认着法交给引擎。 */
    function verifiedHistoryFor(effect) {
        const snap = positionTracker.snapshot();
        const current = snap.position;
        if (!current || !snap.positionCommand || !effect.board ||
            current.side !== effect.side ||
            positionTrackerModule.cellsKey(current.board) !==
                positionTrackerModule.cellsKey(effect.board)) {
            return { positionCommand: null, historyStatus: "unknown" };
        }
        return { positionCommand: snap.positionCommand, historyStatus: snap.historyStatus };
    }

    /** 影子：采纳新基准（无着法语义的场合：重建/声明/开局）。 */
    function trackAdopt(board, side, meta) {
        try {
            positionTracker.adopt(Object.assign({ board: board, side: side }, meta || {}));
        } catch (error) {
            stats.trackerErrors += 1;
        }
        trackerMirror((meta && meta.reason) || "adopt");
    }

    /** 影子：收一手已验证着法（被拒也**不改变行为**，只计数）。 */
    function trackConfirm(entry) {
        try {
            const result = positionTracker.confirmMove(entry || {});
            if (result && result.ok === false) {
                stats.trackerRejects += 1;
                if (stats.trackerRejects <= 5) {
                    note("账本拒收着法：" + result.reason);
                }
            }
        } catch (error) {
            stats.trackerErrors += 1;
        }
        trackerMirror((entry && entry.iccs) || "confirm");
    }

    /** 影子：基准按观测原地改正（无着法）。 */
    function trackFix(board, side, meta) {
        try {
            positionTracker.fix(Object.assign({ board: board, side: side }, meta || {}));
        } catch (error) {
            stats.trackerErrors += 1;
        }
        trackerMirror((meta && meta.reason) || "fix");
    }

    /** 影子：把一串「刚被采纳的推断着法」按顺序记账（before 一路链推）。 */
    function trackInferredMoves(moves, beforeBoard, beforeSide, source) {
        let board = beforeBoard;
        let side = beforeSide;
        for (let i = 0; i < (moves || []).length; i++) {
            const item = moves[i] || {};
            let result = null;
            try {
                result = positionTracker.confirmMove({
                    iccs: item.iccs, fromBoard: board, side: side,
                    afterBoard: null, source: source
                });
            } catch (error) {
                result = null;
            }
            if (!result || result.ok !== true) {
                stats.trackerRejects += 1;
                if (stats.trackerRejects <= 5) {
                    note("账本拒收推断着法（" + (item.iccs || "?") + "）：" +
                        (result ? result.reason : "异常"));
                }
                trackerMirror("inferred");
                return;
            }
            board = result.appliedBoard;
            side = result.nextSide;
        }
        trackerMirror("inferred×" + ((moves || []).length));
    }

    /** 影子：作废基准（与 lastReliable = null 同步）。 */
    function trackClear(reason, resetLedger) {
        try {
            positionTracker.invalidate(reason, { resetLedger: resetLedger === true });
        } catch (error) {
            stats.trackerErrors += 1;
        }
        trackerMirror(reason);
    }
    let lastResult = null;        // 最近一次识别结果（界面/诊断用）
    let lastBoard = null;
    /** 最近一次观测里**模型读到的原始盘面**（补丁/补齐之前）——诊断与审核用（§12） */
    let lastRawChars = null;
    /** 对方应手快路用：上一帧占用图（相邻帧做差找「棋子在动」）。 */
    let prevOccupancy = null;
    /**
     * 对方应手快路用：「本个对方回合」里翻过面的格子（自上次采纳后累积，跨多帧保留）。
     * 为什么不是只看最近两帧（2026-09-21 真机）：对方落子帧常夹装饰，且起点格的「星光残留」
     * 可能让占用读数晚几帧才落定 —— 只留 2 帧的话，等读数干净了「新鲜度」早过期，应手又放跑、
     * 白等 45 秒闲置翻轮次（真机抓过现行：变化 4 格看见了应手，账却已过期）。
     */
    let flipEpoch = {};
    /** 快路跨帧佐证的待定候选（同一手连续两帧都成立才采纳）。 */
    let replyFastPending = null;
    /** 本次观测里，轮次推断给出的「按着法改正过的盘面」（仅当确实改动了观测时才存在） */
    let lastSideFix = null;
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
    const crossFrameGate = crossFrameGateModule.createCrossFrameGate({
        keyOf: function (board, side) { return positionKeyOfBoard(board, side); },
        sample: function () { return stats.observations; }
    });
    /** 本次 `sideFor` 是不是因为「还没拿到第二帧佐证」而暂缓（用来把它和「真推不出来」分开计数） */
    let lastInferHold = false;
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
    let pendingBaselineRevision = null;   // { beforeBoard, beforeSide, why, atMono }
    let baselineBorrow = null;            // { base, aside, atMono, why }

    /** 修订器在**动基准之前**调用：登记「修订前的基准」（同一轮观测里多次修订只留最早那份）。 */
    function recordBaselineRevision(why) {
        if (!lastReliable || !lastReliable.board || !lastReliable.side) {
            return;
        }
        if (pendingBaselineRevision) {
            return;   // 同一轮里的多次修订合并成「最早那份」：它才同时含起点的空与落点的子
        }
        pendingBaselineRevision = {
            beforeBoard: lastReliable.board.slice(),
            beforeSide: lastReliable.side,
            why: why || "修订",
            atMono: nowMono()
        };
    }

    /** 作废对账账本（基准重建 / 换边 / 新会话等场合：旧基准已经不存在了）。 */
    function clearBaselineBorrow() {
        pendingBaselineRevision = null;
        baselineBorrow = null;
        relaxUnknownWatchdog(false);
    }

    /** 对账期间放宽过的看门狗原值（null = 当前没放宽） */
    let borrowWatchdogSaved = null;
    /**
     * 对账进行中暂时放宽「轮次未知看门狗」（4s）：在借基准对账时侧本来就是 null，
     * 而看门狗一冒头就 RECOVERING，会抢在对账（2~3 帧）前面把恢复带起（真机 03:29 实况）。
     * 放宽上限 = 借基准时限 + 3s（超过时限 borrow 会自己回退，看门狗随后照常生效）——有界的，不会掩盖真卡死。
     */
    function relaxUnknownWatchdog(on) {
        const mc = (machine && machine.config) ? machine.config : null;
        if (!mc) {
            return;
        }
        if (on) {
            if (borrowWatchdogSaved == null) {
                if (typeof mc.unknownTurnWatchdogMs !== "number") {
                    return;   // 自定义机器没有这个配置键：别去动它（免得恢复时写出错值）
                }
                borrowWatchdogSaved = mc.unknownTurnWatchdogMs;
                mc.unknownTurnWatchdogMs = cfg.baselineBorrowMs + 3000;
            }
        } else if (borrowWatchdogSaved != null) {
            mc.unknownTurnWatchdogMs = borrowWatchdogSaved;
            borrowWatchdogSaved = null;
        }
    }

    /**
     * 「观测里走棋方的子少了一枚」（它站的格不再有那枚子）—— 等对方时应手正在发生的**最早证据**：
     * 起点先空、落点后到（飞行中的子还可能被读成 '?'/整格空）。对账修订靠它触发（见 sideFor）。
     * 反向形态（凭空多子）不算：那是幻影收编/漏收的地盘，老流程自己会自愈。
     */
    function vanishedMoverPiece(beforeBoard, nowBoard, moverSide) {
        for (let i = 0; i < 90; i++) {
            const a = beforeBoard[i];
            if (!a || a === "?") {
                continue;
            }
            const isMover = (moverSide === "w") ? (a === a.toUpperCase()) : (a === a.toLowerCase());
            if (!isMover) {
                continue;
            }
            const b = nowBoard[i];
            if (b === a) {
                continue;   // 还在原位
            }
            return true;    // 这枚子从原位消失了（真走了 / 装饰读错 —— 交给对账去分辨）
        }
        return false;
    }
    /**
     * 对方应手也要跨帧佐证：同一个应手要**连续两次验证**都枚举出来才采信。
     *
     * 为什么要它（2026-09-19，K40 假事实事故）：模型偶尔会把同一枚子读成**两个位置**
     * （重复检测框伪影）—— 实测黑炮在 (7,2)，某几帧却被读成 (4,2)，于是应手枚举「确认」了
     * 一个**不存在的应手 h7e7**。而验证阶段会把应手**直接写进 `lastReliable` 和补齐表** ⇒
     * 之后每帧都拿假事实去补（(4,2) 被钉成未知格）⇒ `sideFor` 永远数不齐子力 ⇒
     * 「轮次未知」空转、验证变「有多种应手解释，不唯一」，重定位也治不好（截图为证：(4,2) 是空的）。
     * 要求两次验证都枚举出同一个应手，单独一帧的伪影就过不了关。
     */
    function replyCorroborated(key) {
        const result = crossFrameGate.replyCorroborated(key);
        if (result.wait) {
            stats.replyHolds += 1;
        }
        return result.ok;
    }
    function tolerantSupportedByTwoFrames(baseBoard, reason) {
        // 采样单位是**观测次数**（见 cross-frame-gate.js：自检是瞬时循环，墙钟等不到第二帧）
        const result = crossFrameGate.supportedByTwoFrames(baseBoard, reason);
        if (result.wait) {
            stats.corroborateWaits += 1;
        }
        return result.ok;
    }

    /**
     * 轮次推断：**先「差分追踪」（用户 2026-09-20 提的算法），给不出结论再跑枚举式 infer**。
     *
     * 差分追踪 = 「起点空了 + 终点多了个东西 ⇒ 就是这一枚子走的」+ 兵种规则校验（马腿/象眼/炮架/
     * 九宫/过河/自将）⇒ O(路程) 的检查，而且**不依赖落点格读成了什么**（高亮环把落点读成敌色也认得出）。
     * 枚举式 infer 是「几十手 × 每手整盘比对」，解释力最强但落点被画花时会全军覆没。
     * 两者不冲突：追踪只在「差分恰好是两格、且兵种规则唯一合法」时才给结论，其余一律回退。
     */
    function inferMove(beforeBoard, beforeSide, board, options) {
        let tracked = null;
        if (typeof transition.inferTracked === "function") {
            try {
                tracked = transition.inferTracked(beforeBoard, beforeSide, board, options);
            } catch (error) {
                tracked = null;      // 追踪层不许把整轮搞崩（它只是个快路，坏了就回退）
            }
        }
        if (tracked) {
            stats.trackedMoves = (stats.trackedMoves || 0) + 1;
            return tracked;
        }
        stats.trackedMisses = (stats.trackedMisses || 0) + 1;
        return transition.infer(beforeBoard, beforeSide, board, options);
    }
    /**
     * 尝试预算（见 DEFAULTS.attemptLimitPerMove 的说明；实现见 attempt-budget.js）。
     * 键 = 会话|基准 FEN|该走棋方（+ 着法）；**故意不在恢复成功/新票据/换几何时清零** ——
     * 那些都不算取得进展，只有在确认真实合法变化（落子/应手）后才清空。
     */
    const attemptBudget = attemptBudgetModule.createAttemptBudget({
        limitPerMove: cfg.attemptLimitPerMove,
        limitPerPosition: cfg.attemptLimitPerPosition,
        note: note
    });
    /** 上次重新定位的单调时间 + 最小间隔（见 recover 的限频说明：定位一次 ~8s，而棋子移动不改变几何） */
    let lastRelocateMono = 0;
    const RELOCATE_MIN_INTERVAL_MS = 30000;
    /** 最近一次观测的**每格实测框中心**（点棋用；见 click-executor.pointOf 的说明） */
    let lastCenters = null;
    let lastCentersVersion = null;

    // ---- 算棋放在哪跑 ----
    // worker：独立 QuickJS 引擎（自己的线程），主线程只负责「起任务 + 每轮 poll 一次」；
    // inline：老路径（本线程同步算，会挡触控），只在宿主没有 threads 或显式要求对照时用。
    const projectDir = opts.projectDir || (typeof __dirname === "string" ? __dirname + "/../.." : ".");
    const workerSearch = searchWorkerModule.create({
        dir: projectDir,
        graceMs: cfg.computeGraceMs,
        nowMono: nowMono,
        log: function (text) { note(text); }
    });
    const computeMode = cfg.computeMode === "inline" || !searchWorkerModule.available()
        ? "inline"
        : "worker";
    let pendingCompute = null;    // { jobId, effect, startedAtMono }：正在 worker 里算的任务
    /**
     * 「游戏画花了的格子」的已知事实补齐表（实现见 known-facts.js；记录 { index, char, cycle, ply }）。
     * 游戏会给**刚落的子**加高亮环，那一格会被模型认错（实测：带环的炮读成马），
     * 棋规层再把它降级成 '?'。我方落子与对方应手的子都会被画环，所以是张表。
     * 兵种是**已知事实**（票据 + 唯一合法应手推出来的），不是猜；格子里读到别的子/变空就自动失效。
     */
    const knownFacts = knownFactsModule.createKnownFacts();
    /** 最近一次「全盘 OCR 待定/驳回」的日志文本与时间（避免每 6s 刷屏） */
    let lastOcrGateText = null;
    let lastOcrGateNoteAt = 0;

    /**
     * 全盘 OCR 补正的「连续帧共识」账本（实现见 frame-consensus.js）：
     * 只在 `applyOcrBoardFixes` 里维护；每格变一次结论就重置为 1 帧。
     */
    const frameConsensus = frameConsensusModule.createFrameConsensus();
    /** 「颜色抖动按住」日志限频时间戳 */
    let lastFlapNoteAt = 0;
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
    let activeOwnMove = null;
    /**
     * 最近的 ROI 快速确认结论（{ actionId, atMono, verdict }）。
     * 2026-09-24：真机实测一手棋的确认会**完整跑两遍**（两条「第 1 次探测」相隔 ~0.6s），
     * 第二遍的抓帧+OCR 完全白花 —— doVerify 入口对同一票据 2 秒内直接复用它。
     */
    let lastFastConfirm = null;
    /**
     * 「手数周期」：每确认成功一次 +1。补齐表里的记录只在**同一个周期内**有效 ——
     * 游戏的高亮环只挂在「刚落的那个子」上，下一手一旦确认，上一手的装饰就不再存在。
     * 真机踩过：不加这个界限，上一手的记录会一直生效，把「那个子真的被吃了」也覆盖掉。
     */
    let verifyCycle = 0;
    /**
     * 「我方出手序号」：每次**发出点击**就 +1。
     * 这是「刚落下的子不会动」的真正时间界限：
     *   · 我方出手之前，对方刚落的子、我方刚落的子都不可能再动（要吃它也得等我方落子）；
     *   · 我方一出手，局面就要变，旧记录不能再当事实用。
     * ⚠️ 不能用 verifyCycle 当界限：同一个着法会被**重复确认好几次**，每确认一次周期+1，
     * 于是刚登记的记录立刻沦为「旧记录」（真机踩过：补齐只生效了一帧）。
     */
    let moveEpoch = 0;
    /**
     * 单格重拍的冷却表：`index → 上次重拍时刻`。
     * 可疑格可能连续多帧都读成空（装饰会反复发作），但**不必每帧都烧一次额外截图**，
     * 而且降阈值重拍偶尔会把噪点收成棋子 —— 限频也限制了这种噪音的影响面。
     */
    const lastReprobeAt = {};
    /** 同一格上次「催游戏」的时刻（限频用；催太勤会把游戏和面板一起搞乱） */
    const stuckNudgeAt = {};
    /** 上次问全盘 OCR 的时刻（限频：一次 90 格 ≈1.2s） */
    let lastOcrBoardAt = 0;
    /** 上次「全盘 OCR 对账无可改正」的日志时刻（只用于给日志限频） */
    let lastOcrBoardNoteAt = 0;
    /**
     * 本次「确认」里有没有已知事实被证伪（OCR 读到空格/别的子、或催了多次仍读不出来）。
     * 为什么要它：证伪只把补齐表与可靠基准改对还不够 —— **当前票据**里带着那张旧期望值
     * （期望 (4,2) 有炮），于是验证会永远停在「装饰容差下有 N 种应手解释，不唯一」。
     * 有这个标记，就在这次确认结束时作废票据、重新观察。
     */
    let factInvalidated = false;
    /**
     * 最近一次「可靠基准被按观测改正」的时刻。
     * 用途：验证时据此**重建票据的期望值** —— 票据里的 expectedAfterFen 是**发票据那一刻**
     * 冻结的，基准改了它不会变，于是验证会永远「装饰容差下有 N 种应手解释，不唯一」
     * （真机实测：基准与补齐表都已改对，卡在期望值上）。
     */
    let baselineCorrectedAt = 0;
    /**
     * 「杀棋待佐证」闸门里按住的那手棋（见 finishCompute 的闸门与 tick 的放行/作废）。
     * 字段：{ jobId, board, boardKey, side, iccs, obsAtStart, outcome }。
     * 规则：算棋那一帧还没被**独立观测**复核之前，我方杀棋不许点击。
     */
    let pendingMate = null;
    /**
     * 最近一次完成的观察摘要（供 finishCompute 判「算棋之后有没有出现过干净的第二帧」）：
     * { obs: 观测计数, unknown: 未知格数, covered: 按基准填回的格数, key: 0 未知时的局面 key }。
     * 失败/异常的观察置 null。
     */
    let lastObsSummary = null;
    /**
     * 下一次观察强制「全盘新鲜重读」（杀棋复核用：跳过差分缓存，见 observeOnce 的 request.freshRead）。
     * 背景：差分管道只读变化格，栏位被「按基准填回」后它永远看不出变化 ——
     * 「两帧同错」只能靠一次不共享缓存的完整重读来打破。
     */
    let forceFreshReadOnce = false;
    /** 最近一次起算棋时的观测计数（finishCompute 用它判断「算棋这一帧有没有被第二帧复核过」） */
    let lastComputeObs = 0;
    /** 上一次「算棋失败」的盘面签名（同一张盘面不重复把 FEN 灌进日志；2026-09-21 真机排障） */
    let lastComputeFailKey = null;
    /** 「轮到的一方 0 合法着法」连续命中的次数（盘面自检用；2026-09-21） */
    let noMoveStreak = 0;
    /** 「我方杀棋」的评分下限：引擎的杀分 = MATE(30000) - ply，这条线以上都按杀棋对待。 */
    const MATE_CLAIM_MIN_SCORE = 29800;
    /** 上一句「观察层补齐」的说明文案（只用于去重打日志，免得每秒刷屏） */
    let lastFillText = "";

    function useWorkerSearch() {
        return computeMode === "worker";
    }

    function note(text) {
        history.push({ atMono: nowMono(), text: text });
        log("[driver] " + text);
    }

    /**
     * 空闲超时自动「翻轮次」（见 state-machine 的 opponentIdleDeclareMs 注释）：
     * 与用户点「轮到我」（assertTurn）同一条路 —— 把当前观测立成可靠基准（side = 我方）+ 声明。
     * 翻错有界：游戏拒绝非我方回合的点击 ⇒ 确认判 UNCHANGED ⇒ 尝试预算几次内自动暂停（可见）。
     */
    function declareTurnFromIdle(effect) {
        if (!lastBoard) {
            return null;
        }
        const bad = lastBoard.indexOf("?");
        if (bad >= 0) {
            note("自动翻轮次跳过：当前局面还有未知格（第 " + (Math.floor(bad / 9) + 1) + " 行第 " +
                (bad % 9 + 1) + " 列）");
            return null;
        }
        lastReliable = { board: lastBoard.slice(), side: machine.mySide() };
        trackAdopt(lastBoard, machine.mySide(), { reason: "declare-idle", source: "manual-confirmed" });
        frameConsensus.clear();
        phantomStreak = {};
        needFreshBaseline = false;
        note((effect.reason || "等对方空闲超时") + "：已按当前局面重立基准并翻到我方");
        return machine.dispatch({ type: machineModule.EVENT.TURN_DECLARED,
            reason: (effect.reason || "空闲超时自动翻轮次") });
    }

    // ---- 动作清单的执行 ----

    function handleEffect(effect) {
        switch (effect.type) {
            case "CALIBRATE":
                return calibrate();
            case "COMPUTE":
                return compute(effect);
            case "SUGGEST":
                return onSuggest(effect);
            case "EXECUTE_TICKET":
                return executeTicket(effect);
            case "VERIFY":
                return doVerify(effect);
            case "RECOVER":
                return recover();
            case "DECLARE_TURN":
                return declareTurnFromIdle(effect);
            case "INIT":
            case "CANCEL_TICKET":
                note(effect.type + "：" + (effect.reason || ""));
                return null;
            case "CANCEL_JOB":
                // 局面变了/暂停：worker 里的旧任务立刻停掉，别白烧 CPU
                if (workerSearch.isRunning()) {
                    workerSearch.cancel();
                    pendingCompute = null;
                    note("算棋任务已取消（worker 已停）：" + (effect.reason || ""));
                } else {
                    note(effect.type + "：" + (effect.reason || ""));
                }
                return null;
            default:
                return null;
        }
    }

    /** 把一段动作清单按顺序执行完（动作可能又产生新动作，用队列而不是递归）。 */
    function pump(outcome) {
        const queue = [outcome];
        let guard = 0;
        while (queue.length > 0 && guard++ < 24) {
            const current = queue.shift();
            for (let i = 0; i < current.effects.length; i++) {
                let follow = null;
                try {
                    follow = handleEffect(current.effects[i]);
                } catch (error) {
                    stats.errors += 1;
                    stats.lastError = String(error && error.message ? error.message : error);
                    note("动作 " + current.effects[i].type + " 失败：" + stats.lastError +
                        (error && error.stack
                            ? "｜堆栈：" + String(error.stack).split("\n").slice(1, 4).join(" ← ")
                            : ""));
                    follow = machine.dispatch({ type: machineModule.EVENT.ERROR, reason: stats.lastError });
                }
                if (follow) {
                    queue.push(follow);
                }
            }
        }
        /**
         * ★★ 2026-09-24（K40 真机根因）：返回前把 effects 清空。
         *
         * `handleEffect` 的返回值会被**调用方的 pump 再次入队**；而嵌套动作（executeTicket、
         * doVerify、compute…）自己也在调 `pump` —— 那些 effects 在里层已经执行过一次。
         * 不清空 = 同一组动作被内外两层各跑一遍：真机实测「一手棋的确认完整跑 2~3 次」
         * （每次白烧一整轮全盘观测 ~0.6s；09-24 早上用「同票据复用结论」兑过一次，
         * 但那只盖住症状、且仅对 ROI 路径生效 —— 去掉 OCR 后立刻复发）。
         * 清空后：每个 outcome 的 effects 只被执行一次（里层执行、外层看到空）。
         */
        outcome.effects = [];
        return outcome;
    }

    /**
     * ② 只识别 / 着法建议：状态机算出着法后直接把建议报出来（**不点击**）。
     * 这条日志就是面板「算棋/建议」那一格的来源；也方便 logcat 里对账。
     */
    function onSuggest(effect) {
        const ticket = effect.ticket || {};
        stats.suggestions = (stats.suggestions || 0) + 1;
        note("建议着法 " + ticket.moveICCS + "（② 只给建议，不点击）：评分 " + ticket.score +
            "，深度 " + ticket.depth);
        return null;
    }

    function calibrate() {
        try {
            const located = opts.locate(locateOptions());
            if (!located || !located.ok) {
                note("定位失败：" + (located ? located.reason : "没有结果"));
                return pump(machine.dispatch({ type: "GEOMETRY", trusted: false, reason: located ? located.reason : "" }));
            }
            grid = located.grid;
            lastReliable = null;
            clearBaselineBorrow();
            trackClear("calibrate");
            lastRelocateMono = nowMono();   // 定位过就记时间（recover 的限频用它）
            note("棋盘已定位：起点 (" + Math.round(grid.originX) + "," + Math.round(grid.originY) +
                ") 格距 (" + Math.round(grid.stepX) + "," + Math.round(grid.stepY) +
                ") 版本 v" + grid.version);
            const trusted = opts.isTrustworthy ? opts.isTrustworthy(grid) : true;
            return pump(machine.dispatch({
                type: "GEOMETRY",
                geometryVersion: grid.version,
                trusted: trusted,
                // 不可信时必须带原因：真机曾出现空原因，日志只剩「几何不可信：」（无从下手）
                reason: trusted ? "" : ("几何质量不足：" + JSON.stringify(grid.quality || null))
            }));
        } catch (error) {
            stats.errors += 1;
            stats.lastError = String(error && error.message ? error.message : error);
            note("定位异常：" + stats.lastError);
            return pump(machine.dispatch({ type: "GEOMETRY", trusted: false, reason: stats.lastError }));
        }
    }

    function compute(effect) {
        // 算棋放到独立引擎线程（worker）：主线程不再被 ~1.2s 的搜索占住，
        // 悬浮窗触摸/心跳照常。结果由 pollCompute() 在后续 tick 里非阻塞收回来。
        /**
         * ★★ 盘面自检（2026-09-21 真机：v5r 漏读一枚黑士 → 「假双将」空转 7 分钟）：
         *   真实对局里「轮到走棋的一方 0 个合法着法」= 被将死/困毙 —— 对局**早就结算了**。
         *   还坐在这局里算出 0 着，只可能是盘面读错（现场：d7 黑士漏检 ⇒ 黑炮的「隔一子打帅」
         *   线没被挡住 ⇒ 假双将 ⇒ pikafish `bestmove (none)` 每 12s 空转，面板「算棋失败」刷屏）。
         *   处置：不出手、不喂引擎 —— 强制下一帧全盘新鲜重读 + 按观测量重建基准；
         *   连续多次仍 0 着 ⇒ 提示「盘面持续矛盾：可能对局已结束或识别异常」，让它显式可见。
         */
        let legalCount = -1;
        try {
            legalCount = (effect && effect.board) ? legal.allLegalMoves(effect.board, effect.side).length : -1;
        } catch (error) {
            legalCount = -1;
        }
        if (legalCount === 0) {
            noMoveStreak += 1;
            stats.impossibleNoMoves = (stats.impossibleNoMoves || 0) + 1;
            forceFreshReadOnce = true;
            needFreshBaseline = true;
            note("盘面自检不通过：轮到" + (effect.side === "w" ? "红" : "黑") + "方却 0 合法着法" +
                "（真对局早该结束了）⇒ 判为识别错误：不出手，强制全盘新鲜重读核对（第 " + noMoveStreak + " 次）");
            if (noMoveStreak === 3) {
                note("⚠ 盘面自检连续 3 次不通过：对局可能已结束（被将死/困毙，看游戏结算），" +
                    "或识别持续异常（可用面板「绘制」+ 截图人工核对棋盘）");
            }
            return pump(machine.dispatch({
                type: "JOB_RESULT", jobId: effect.jobId, ok: false,
                reason: "盘面自检不通过：轮到的一方 0 合法着法（识别错误，强制重读）"
            }));
        }
        noMoveStreak = 0;
        lastComputeObs = stats.observations;
        // 本局面要排除的着法（游戏拒绝过的 + 疑似循环的）：worker 与同步两条路都要带上
        const excludes = excludedIccsFor(effect.board, effect.side);
        const verifiedHistory = verifiedHistoryFor(effect);
        if (useWorkerSearch()) {
            const started = nowMono();
            if (workerSearch.start(effect.fen || effect.board, {
                side: effect.side,
                movetime: effect.movetimeMs,
                excludeIccs: excludes,
                positionCommand: verifiedHistory.positionCommand,
                historyStatus: verifiedHistory.historyStatus,
                engine: cfg.engineKey,
                pikafish: cfg.pikafish
            })) {
                pendingCompute = {
                    jobId: effect.jobId,
                    effect: effect,
                    startedAtMono: started
                };
                stats.thinkOffThread += 1;
                note("算棋（worker 线程）：轮到 " + (effect.side === "w" ? "红" : "黑") +
                    "，预算 " + effect.movetimeMs + "ms");
                return null;   // 不阻塞：JOB_RESULT 稍后由 pollCompute 喂回去
            }
        }
        return computeInline(effect, excludes, verifiedHistory);
    }

    /** 兜底 / 对照路径：在本线程同步算（会挡住触控，就是被替换掉的那条老路）。 */
    function computeInline(effect, excludes, verifiedHistory) {
        stats.thinkInline += 1;
        const historyInput = verifiedHistory || verifiedHistoryFor(effect);
        let result;
        try {
            result = opts.analyze({ board: effect.board, side: effect.side }, {
                movetime: effect.movetimeMs,
                // 本局面内已经被游戏拒绝过的着法（点下去局面没变）不再选；疑似循环的也一并禁
                excludeIccs: excludes || null,
                positionCommand: historyInput.positionCommand,
                historyStatus: historyInput.historyStatus,
                engine: cfg.engineKey,
                pikafish: cfg.pikafish
            });
        } catch (error) {
            result = { ok: false, reason: String(error && error.message ? error.message : error) };
        }
        return finishCompute(effect, {
            ok: !!result.ok,
            reason: result.reason || "",
            iccs: result.iccs,
            move: result.move,
            score: result.score,
            depth: result.depth,
            ms: result.ms,
            engine: result.engine,
            historyUsed: result.historyUsed === true,
            historyStatus: result.historyStatus || "fen"
        });
    }

    /**
     * 把一个算棋结果变成 JOB_RESULT 喂回状态机（worker 与同步两条路共用）。
     * @param effect 当时那个 COMPUTE 效果（里面有 board/side，用来算预期后局面）
     * @param result { ok, reason, iccs, move, score, depth, ms }
     */
    function finishCompute(effect, result) {
        const outcome = { jobId: effect.jobId, ok: !!result.ok, reason: result.reason || "" };
        if (result.ok) {
            lastComputeFailKey = null;
            const checkedMove = moveFromIccs(effect, result.iccs);
            if (!checkedMove) {
                outcome.ok = false;
                outcome.reason = "引擎返回非法或不在当前局面的着法：" + String(result.iccs);
                note("算棋结果已拒绝：" + outcome.reason);
                return pump(machine.dispatch(Object.assign({ type: "JOB_RESULT" }, outcome)));
            }
            result.move = checkedMove;
            outcome.iccs = result.iccs;
            outcome.move = result.move;
            outcome.score = result.score;
            outcome.depth = result.depth;
            // 预期后局面：拿引擎给的着法算，不手写 FEN
            const applied = legal.applyMove(effect.board, result.move);
            outcome.expectedAfterFen = fenModule.buildFen(applied.board,
                effect.side === "b" ? "w" : "b");
            note("算棋：轮到 " + (effect.side === "w" ? "红" : "黑") + "，" + result.iccs +
                "（深度 " + result.depth + "，" + result.ms + "ms，评分 " + Math.round(result.score) +
                "｜后端 " + (result.engine || "未知") +
                "｜历史 " + (result.historyUsed ? (result.historyStatus || "partial") : "当前FEN") +
                (result.excludeRelaxed ? "｜排除放宽" : "") + "）");
            /**
             * ★★ 杀棋佐证闸门 v2（2026-09-21 下午；v1 见 16.19）：
             *   v1 只要求「算棋之后出现过一张 0 未知格、盘面一致的观测」——实测挡住了大部分
             *   假杀，但**挡不住「两帧同错」**：被盖格兜底把错误盘面稳定下来 ⇒ 两帧一致 ⇒ 放行
             *   （真机 09:50 的 d6g6：深度 1 / 4ms / 29999，点出去并不是杀）。
             *   v2：**只有“干净”的复核帧**（0 未知格 + **没有按基准填回的格** + 盘面一致）
             *   才直接放行；否则一律按住（pendingMate），由 tick 里走
             *   「干净帧放行 / 含被盖格 → 强制全盘新鲜重读复核」这两条路。
             */
            if (isMateClaim(result.score)) {
                const cleanSame = lastObsSummary && lastObsSummary.obs > lastComputeObs &&
                    lastObsSummary.unknown === 0 && lastObsSummary.covered === 0 &&
                    lastObsSummary.key === positionKeyOfBoard(effect.board);
                if (!cleanSame) {
                    stats.mateHolds += 1;
                    pendingMate = {
                        jobId: effect.jobId,
                        board: effect.board ? effect.board.slice() : null,
                        boardKey: positionKeyOfBoard(effect.board),
                        side: effect.side,
                        iccs: result.iccs,
                        obsAtStart: lastComputeObs,
                        freshRequested: false,
                        freshDeadlineObs: 0,
                        outcome: outcome
                    };
                    note("杀棋待佐证：" + result.iccs + "（评分 " + Math.round(result.score) +
                        "，深度 " + result.depth + "）先按住 —— 等一张干净的第二帧" +
                        "（无未知格、也没有按基准填回的格）再出手");
                    stats.thinkMs = pendingCompute ? (nowMono() - pendingCompute.startedAtMono) : 0;
                    pendingCompute = null;
                    return null;
                }
                note("杀棋已有干净复核帧（无未知格、无按基准填回）→ 放行 " + result.iccs);
            }
            recordPlanDecision(effect.board, effect.side, result.iccs);
        } else {
            // ★ 2026-09-21 真机（`算棋失败：bestmove (none)` 每 12s 空转，游戏僵住）：日志里看不到
            //   引擎拿到的局面，无法区分「真被将死 / 识别盘面不对 / 引擎输入路径坏了」三种原因。
            //   这里把「引擎拿到的盘面（FEN）」与「棋规层数出来的合法着法数」一起写出来：
            //   两者都 0 ⇒ 盘面按棋规就是终局；引擎说 0 而棋规有子可走 ⇒ 引擎输入/盘面数据有问题。
            //   同一张盘面只详细打一次（恢复循环里每 12s 会重复踩到它）。
            let failFen = "";
            try {
                failFen = (effect && effect.board) ? fenModule.buildFen(effect.board, effect.side) : "";
            } catch (error) {
                failFen = "";
            }
            const failKey = outcome.reason + "|" + failFen;
            if (failKey !== lastComputeFailKey) {
                lastComputeFailKey = failKey;
                let legalCount = -1;
                try {
                    legalCount = (effect && effect.board) ? legal.allLegalMoves(effect.board, effect.side).length : -1;
                } catch (error) {
                    legalCount = -1;
                }
                note("算棋失败：" + outcome.reason +
                    (failFen ? "｜盘面（" + (effect.side === "b" ? "黑先" : "红先") + "）：" + failFen : "") +
                    (legalCount >= 0 ? "｜棋规层合法着法 " + legalCount + " 种" : ""));
            } else {
                note("算棋失败（同一张盘面）：" + outcome.reason);
            }
        }
        stats.thinkMs = pendingCompute ? (nowMono() - pendingCompute.startedAtMono) : 0;
        pendingCompute = null;
        return pump(machine.dispatch(Object.assign({ type: "JOB_RESULT" }, outcome)));
    }

    /** worker 里只回传 ICCS：在这里从合法着法表里把着法对象找回来（着法生成比搜索便宜得多）。 */
    function moveFromIccs(effect, iccs) {
        // ICCS 必须是 a0..i9 到 a0..i9 的合法坐标；引擎异常时曾返回 a0b0，
        // 这不是一个真实着法，禁止进入点击确认循环。
        if (typeof iccs !== "string" || !/^[a-i][0-9][a-i][0-9]$/.test(iccs)) {
            note("拒绝非法着法：" + String(iccs));
            return null;
        }
        const list = legal.allLegalMoves(effect.board, effect.side);
        for (let i = 0; i < list.length; i++) {
            if (list[i].iccs === iccs) {
                return list[i];
            }
        }
        return null;
    }

    /**
     * 非阻塞收算棋结果（每轮 tick 开头调一次）。
     * @returns null                                  没有在算
     *          { pending: true,  elapsedMs }         还在算（本轮不用再截图/观察）
     *          { pending: false, elapsedMs, status } 已收尾（结果或失败已喂回状态机）
     */
    function pollCompute() {
        if (!pendingCompute || !workerSearch.isRunning()) {
            return null;
        }
        const state = workerSearch.poll();
        if (state.status === "running") {
            return { pending: true, elapsedMs: state.elapsedMs };
        }
        const job = pendingCompute;
        // 收尾期间状态机可能已经离开 THINKING（暂停/新会话/局面变了）：交给 finishCompute 的路由，
        // 由状态机自己判定是否接受（onJobResult 里有 S02/S03 两道门）。
        if (state.status === "done") {
            const result = state.result;
            let move = null;
            if (result.ok && result.iccs) {
                move = moveFromIccs(job.effect, result.iccs);
            }
            if (!result.ok) {
                finishCompute(job.effect, { ok: false, reason: result.reason || "worker 算棋失败" });
            } else if (!move) {
                finishCompute(job.effect, {
                    ok: false,
                    reason: "worker 给的着法 " + result.iccs + " 不在合法着法表里"
                });
            } else {
                finishCompute(job.effect, {
                    ok: true,
                    iccs: result.iccs,
                    move: move,
                    score: result.score,
                    depth: result.depth,
                    ms: result.ms,
                    engine: result.engine,
                    historyUsed: result.historyUsed === true,
                    historyStatus: result.historyStatus || "fen"
                });
            }
            return { pending: false, elapsedMs: state.elapsedMs, status: "done" };
        }
        const reason = state.status === "timeout"
            ? ("worker 算棋超时（超过 " + state.elapsedMs + "ms）")
            : ("worker 算棋异常：" + (state.error || ""));
        finishCompute(job.effect, { ok: false, reason: reason });
        return { pending: false, elapsedMs: state.elapsedMs, status: state.status };
    }

    /**
     * 取本次尝试的预算（计数 +1）→ { over, reason, moveCount, positionCount }（实现见 attempt-budget.js）。
     * 计数点放在 canClick 通过之后：只有真的把两次点击发出去才算一次「完整尝试」。
     */
    function takeAttemptBudget(ticket) {
        return attemptBudget.take(ticket);
    }

    /** 确认真实合法变化（落子/应手）后才清空预算。 */
    function clearAttemptBudget(why) {
        attemptBudget.clear(why);
    }

    function executeTicket(effect) {
        const ticket = effect.ticket;
        const gate = machine.canClick(ticket);
        if (!gate.ok) {
            // 到了这一步还被拦下：按「没发出去」处理，绝不点
            note("出手前核对不通过：" + gate.reason);
            return pump(machine.dispatch({
                type: "CLICK_RESULT", ticket: ticket, status: "REJECTED",
                clickCount: 0, reason: gate.reason
            }));
        }
        // ---- §11：重试前必须确认「起点子还在」----
        // 局面已经变了（子被吃/已走/记错了）时再点起点，只会点到空点或对方子，
        // 白烧一次尝试额度（预算就是被这种空转吃掉的）。
        const srcIndex = rulesModule.iccsSquare(ticket.moveICCS, 0);
        const trustedBoard = (lastReliable && lastReliable.board) ? lastReliable.board : lastBoard;
        if (srcIndex != null && trustedBoard) {
            const here = trustedBoard[srcIndex];
            const mine = (machine.mySide() === "w")
                ? rulesModule.isRedPiece(here)
                : rulesModule.isBlackPiece(here);
            if (!mine) {
                stats.sourceGoneRefusals += 1;
                note("重试前核对：起点格(" + ticket.move.fromCol + "," + ticket.move.fromRow + ")现在是 " +
                    (here && here !== "?" ? here : "空/未知") + "，不再是我方子 → **不发点击**（局面已变，重算）");
                return pump(machine.dispatch({
                    type: "CLICK_RESULT", ticket: ticket, status: "REJECTED", clickCount: 0,
                    reason: "起点子已不在原位（局面已变），未发送点击"
                }));
            }
        }
        // ---- 尝试预算（§11：同一局面/同一手不许无限重试）----
        const budget = takeAttemptBudget(ticket);
        if (budget.over) {
            note("同局面落子未确认：" + budget.reason + " → 暂停（不再盲点），请看截图与实际通道");
            note("  预算明细：本手第 " + budget.moveCount + " 次 / 本局面共 " + budget.positionCount +
                " 次（上限 " + cfg.attemptLimitPerMove + " / " + cfg.attemptLimitPerPosition + "）");
            /**
             * ★★ 2026-09-20 真机 now37（用户报「炮为什么去吃对面的将」）：游戏**反复拒绝**同一个
             * 着法，多半不是点击通道的问题，而是**我们这张盘不对** —— 现场是漏认了 (4,1) 那枚黑士，
             * 中路只剩一个炮架 ⇒ 引擎算出「红炮隔一子吃将」合法（评分 29999）⇒ 连试 12 次全被拒，
             * 白烧 ~100 秒。所以这里不只暂停：**作废可靠基准**，逼下一轮走恢复流程
             * （重新定位 + 全盘重读 + 按观测量重建基准），把「盘面漏子」这条路堵上。
             */
            note("  ⇒ 同时作废可靠基准：被游戏反复拒绝 ≈ 我们的盘面漏子了（下一轮重新全盘确认）");
            lastReliable = null;
            clearBaselineBorrow();
            trackClear("attempt-budget");
            needFreshBaseline = true;
            stats.budgetPauses += 1;
            return pump(machine.dispatch({ type: "PAUSE", reason: budget.reason }));
        }
        // ---- 点击前的前台守卫（真机血泪）----
        // 脚本宿主 RunIntentActivity 是**透明窗口**：用 am start 起脚本后它常常仍是「有焦点的前台」，
        // 游戏只是画在它下面。这时注入的点击会被我们自己吃掉 —— 截图一切正常、棋子纹丝不动、
        // 确认阶段永远 UNCHANGED（K40 实测：一手棋连试 4 次全 UNCHANGED，`dumpsys input` 的
        // FocusedApplications 就是本应用）。所以出手前必须确认前台不是自己；是就自动切一次
        // （最近任务快速切换，不重启游戏），切不回来就不点，别把票据白烧掉。
        if (typeof opts.foregroundGuard === "function") {
            let guard = null;
            try {
                guard = opts.foregroundGuard();
            } catch (error) {
                guard = null;
            }
            if (guard && guard.packageChanged) {
                note("前台守卫：点击前前台是" + (guard.ownPackage ? "本应用（脚本宿主）" : (guard.package || "其它")) +
                    " → 已用最近任务切回上一步应用" + (guard.ok ? "" : "（仍不是目标，先不点）"));
            }
            if (guard && guard.ok === false) {
                note("前台不是游戏（当前 " + (guard.package || "未知") + "）：**不发点击**，等下一轮再试");
                return pump(machine.dispatch({
                    type: "CLICK_RESULT", ticket: ticket, status: "CANCELLED",
                    clickCount: 0, reason: "前台不是游戏（" + (guard.package || "未知") + "），未发送点击"
                }));
            }
        }
        // ★ 出手前先作废「这一步起点」的已知事实：棋子从这一刻起就不在那儿了。
        // ⚠️ 必须在**点击之前**丢（而不是等确认成功再丢）：否则确认阶段那几帧里，
        // observeOnce 会把旧子补回起点 → 观测看起来「起点还占着、落点也有了」→
        // 被当成动画帧（UNRELIABLE）等下去，而这条记录永远等不到「确认成功」去作废 → 死锁。
        const fromIndex = transition.squareIndex(ticket.moveICCS, 0);
        if (fromIndex >= 0) {
            dropPatchesAt([fromIndex], "我方要走 " + ticket.moveICCS + "，起点已空");
        }
        // ★ 记下「这一手在飞」：确认期间观察层补齐时，起点按空、落点按走过去的那个子
        //   （不做就会把起点补回旧子 → 幻影子进基准；见 activeOwnMove 的声明注释）
        activeOwnMove = {
            iccs: ticket.moveICCS,
            fromIndex: fromIndex,
            destIndex: transition.squareIndex(ticket.moveICCS, 1)
        };
        // 出手 = 新的一手：之前那些「刚落下的子」的记录不能再当事实用（局面马上要变）
        moveEpoch += 1;
        const outcome = executor.execute(ticket, {
            grid: grid,
            // 点棋优先用最近一次观测的**实测框中心**（几何版本必须与票据一致，否则退回交点）：
            // 定位的系统性偏移会让「按交点点的击」落在棋子外沿 → 只选中、不落子。
            centers: (lastCenters && lastCentersVersion === ticket.geometryVersion) ? lastCenters : null,
            screen: opts.screen ? opts.screen() : null,
            cancel: function () { return !running || machine.state() === machineModule.STATE.PAUSED; }
        });
        // 已知事实补齐表**不在这里清空**：我方新落点的子会被再画一次环，而对方上一手的子
        // 可能还带着环；那些格子一旦能自己认出来（或变成空格/别的子），observeOnce 会自动
        // 把它从表里去掉，所以留着不会补错。
        note("点击：" + ticket.moveICCS + " → " + outcome.status + "（" + outcome.reason + "）" +
            "，点击 " + outcome.clickCount + " 次");
        if (outcome.clicks && outcome.clicks.length > 0) {
            // 每次手势单独记：真实手势名（原地 swipe≠tap、无障碍 press≠click）、格号、用的是
            // 实测框中心还是网格交点、交点坐标、接口返回、起止时刻 —— 通道对照实验就靠这几行。
            for (let i = 0; i < outcome.clicks.length; i++) {
                const click = outcome.clicks[i];
                note("  手势" + (i + 1) + " " + (click.kind || "?") + "：" + (click.method || "未知手势") +
                    " 格(" + click.col + "," + click.row + ") 屏幕(" + click.x + "," + click.y + ")" +
                    (click.usedCenter ? " 实测框中心" : " 网格交点") +
                    (click.latticeX != null ? "（交点 " + click.latticeX + "," + click.latticeY + "）" : "") +
                    " 几何v" + click.geometryVersion +
                    " ok=" + (click.ok ? "是" : "否") + " 耗时" + click.elapsedMs + "ms" +
                    (click.command ? " 命令「" + click.command + "」" : "") +
                    (click.error ? " 错误：" + click.error : ""));
                if (i > 0) {
                    const previous = outcome.clicks[i - 1];
                    const gap = click.startedAtMono - previous.finishedAtMono;
                    note("  手势间隔 " + gap + "ms" +
                        (gap < 0 ? "⚠️ 第二次手势在前一次完成前就发出了（手势可能被取消）" : ""));
                }
            }
        }
        return pump(machine.dispatch({
            type: "CLICK_RESULT", ticket: ticket, status: outcome.status,
            clickCount: outcome.clickCount, reason: outcome.reason
        }));
    }

    /**
     * ROI 快速确认（2026-09-21 提速·方案第 3 条）：点击后**只认起点/终点两个格**。
     *
     * 这步棋是否落地，只看两件事：起点空了 + 终点是我方那颗子。两格都用**硬证据**：
     *   · 终点：单格 OCR 读到字形且与票据预期同（比兵种、忽略大小写）；有像素阵营证据时必须同色；
     *   · 起点：单格 OCR 读不到字**且**墨迹计数为 0（棋子还在飞 = 读不到字但有墨迹 ⇒ 拿不出结论，重试）。
     * 全部成立才返回结果；任一条拿不出来返回 null（调用方照旧走全盘确认）。**不猜。**
     */
    function tryFastConfirmRoI(ticket, expectedBoard) {
        if (cfg.fastConfirmRoI !== true) {
            return null;
        }
        if (typeof opts.ocrCellsNow !== "function" || !ticket || !expectedBoard) {
            return null;
        }
        const fromIdx = transition.squareIndex(ticket.moveICCS, 0);
        const destIdx = transition.squareIndex(ticket.moveICCS, 1);
        if (fromIdx < 0 || destIdx < 0) {
            return null;
        }
        const want = expectedBoard[destIdx];
        if (!want || want === "?") {
            return null;
        }
        const wantSide = want === want.toUpperCase() ? "RED" : "BLACK";
        const attempts = Math.max(1, Math.min(4,
            cfg.fastConfirmAttempts == null ? 2 : Math.round(Number(cfg.fastConfirmAttempts))));
        for (let i = 0; i < attempts; i++) {
            clock(i === 0
                ? (cfg.fastConfirmSettleMs == null ? 300 : Number(cfg.fastConfirmSettleMs))
                : (cfg.fastConfirmGapMs == null ? 350 : Number(cfg.fastConfirmGapMs)));
            let cells = null;
            try {
                cells = opts.ocrCellsNow([fromIdx, destIdx]);
            } catch (error) {
                return null;   // 证据通道异常：直接回全盘确认
            }
            if (!cells || cells.length < 2 || !cells[0] || !cells[1]) {
                return null;
            }
            const from = cells[0];
            const dest = cells[1];
            if (from.unavailable || dest.unavailable) {
                return null;   // OCR/几何不可用：回全盘（那边有自己的兜底）
            }
            const fromEmpty = !from.ok && from.noText === true &&
                (!from.ink || ((Number(from.ink.red) || 0) + (Number(from.ink.black) || 0)) === 0);
            const destPiece = dest.ok === true && dest.cls != null &&
                String(dest.cls).toLowerCase() === String(want).toLowerCase() &&
                (dest.side == null || dest.side === wantSide);
            if (fromEmpty && destPiece) {
                return {
                    ok: true, destChar: want, fromIndex: fromIdx, destIndex: destIdx, attempts: i + 1
                };
            }
        }
        return null;
    }

    function doVerify(effect) {
        /** 这一帧枚举出来的对方应手是否被拒（未得到第二次佐证） */
        let replyRejected = false;
        const ticket = effect.ticket;
        // ★ 2026-09-24 去重：同一张票据 2 秒内**重复**进入确认时，直接复用上一次 ROI 快速确认的结论。
        //   真机实测（K40）：一手棋的确认会被完整跑两次（两条「第 1 次探测」相隔 ~0.6s —— 第二次多花的
        //   抓帧+OCR +300ms 等待白烧）；多次定位未成，先在这里兑底（根源待设备插桩再查）。
        if (lastFastConfirm && ticket && lastFastConfirm.actionId != null &&
            lastFastConfirm.actionId === ticket.actionId &&
            nowMono() - lastFastConfirm.atMono < 2000) {
            note("确认：复用上一轮 ROI 结论（同一票据，省一次抓帧+OCR）");
            return pump(machine.dispatch({
                type: "VERIFY_RESULT", ticket: ticket,
                verdict: Object.assign({}, lastFastConfirm.verdict)
            }));
        }
        // 把票据的「预期落子后局面」当期望传给观察层：用来纠正「同一兵种颜色读反」的装饰格
        let expectedBoard = null;
        try {
            const parsed = fenModule.parseFen(ticket && ticket.expectedAfterFen);
            expectedBoard = parsed && parsed.ok ? parsed.board : null;
        } catch (error) {
            expectedBoard = null;
        }
        // ★ ROI 快速确认（2026-09-21 提速·方案第 3 条）：先只问起点/终点两格；硬证据齐了就直接成功，
        //   省掉一整轮全盘识别（~1.5s）。拿不出结论就照旧往下走全盘确认（宁慢勿错）。
        if (expectedBoard) {
            const fast = tryFastConfirmRoI(ticket, expectedBoard);
            if (fast) {
                stats.confirms += 1;
                verifyCycle += 1;
                stats.roiConfirms += 1;
                // 与全盘确认成功路径保持一致（各自注释见下方对应块）：
                if (fast.fromIndex >= 0) {
                    dropPatchesAt([fast.fromIndex], "我方 " + ticket.moveICCS + " 已 ROI 确认，起点已空");
                }
                activeOwnMove = null;
                const fastMySide = ticket.baseSide === "b" ? "b" : "w";
                const fastNextSide = fastMySide === "w" ? "b" : "w";
                lastReliable = { board: expectedBoard.slice(), side: fastNextSide };
                // A3 影子：ROI 确认的我方着法入账（账本只记录，不参与决策）
                const roiBaseBoard = boardOfFen(ticket.baseFen);
                if (roiBaseBoard) {
                    trackConfirm({
                        iccs: ticket.moveICCS, fromBoard: roiBaseBoard, side: ticket.baseSide,
                        afterBoard: expectedBoard, source: "our-click"
                    });
                }
                decisionEpoch += 1;
                note("确认：CONFIRMED（ROI 快速确认：起点已空 + 落点 " + fast.destChar +
                    "｜第 " + fast.attempts + " 次探测）");
                note("可靠基准已刷新（确认 CONFIRMED）：" + (fastNextSide === fastMySide ? "轮到我方" : "轮到对方"));
                clearAttemptBudget("已确认真实变化 CONFIRMED（ROI）");
                if (expectedBoard[fast.destIndex]) {
                    addPatch({ index: fast.destIndex, char: expectedBoard[fast.destIndex] },
                        "落点格（我方新落的子会被画环｜ROI）");
                }
                // 途经格登记：与全盘路径同一理由（游戏画的走子轨迹会盖住中间格）
                try {
                    const pathSet = verifier.pathIndexesBetween ? verifier.pathIndexesBetween(ticket) : null;
                    if (pathSet) {
                        const pathKeys = Object.keys(pathSet);
                        for (let i = 0; i < pathKeys.length; i++) {
                            const index = Number(pathKeys[i]);
                            if (expectedBoard[index]) {
                                addPatch({ index: index, char: expectedBoard[index] }, "途经格（游戏画的走子轨迹｜ROI）");
                            } else {
                                addEmptyPatch(index, "途经格（游戏画的走子轨迹，棋规上必为空｜ROI）");
                            }
                        }
                    }
                } catch (error) { /* 途经格登记失败不影响确认 */ }
                const fastVerdict = {
                    result: verifier.RESULT.CONFIRMED,
                    board: expectedBoard.slice(),
                    plies: [ticket.moveICCS],
                    reason: "ROI 快速确认（起点空 + 落点 " + fast.destChar + "）"
                };
                // 同一票据若紧接着又被确认一次（真机实测会往返两次），直接复用这个结论。
                lastFastConfirm = { actionId: ticket.actionId, atMono: nowMono(), verdict: fastVerdict };
                return pump(machine.dispatch({
                    type: "VERIFY_RESULT", ticket: ticket,
                    verdict: fastVerdict
                }));
            }
        }
        const observed = observeOnce({ forVerify: true, expect: expectedBoard, ticket: ticket });
        if (!observed.ok) {
            return pump(machine.dispatch({
                type: "VERIFY_RESULT", ticket: ticket,
                verdict: { result: verifier.RESULT.UNRELIABLE, reason: observed.reason }
            }));
        }
        // ★ 确认盘面也要吃「已知事实」补丁表。
        // 观察路径会用补丁表把游戏给上一步画的装饰格（高亮环/命中标记）补回来，但确认走的是
        // `forVerify + expect`（按期望纠正颜色）那条路，装饰格会残留成 '?' → 验证器一路报
        // 「还有 N 个未知格（且不全是落点格）」→ 既得不出「没走成」也得不出一致，永远卡在确认。
        // 真机现场（K40）：第二手 c2c6 之后连刷 50 秒 UNRELIABLE，整局停住。
        // 只补「登记过、且当前确实是未知」的格子，所以不会凭空造子。
        let verifyChars = applyKnownPatchesTo(observed.chars);
        // ★ 已裁定的格子：本票之内一律以**裁定值**为准（见 settledRaw 的说明）。
        //   放在这里（验证之前）而不是事后复议：否则每帧都要等 N 帧僵持才能得出结论。
        if (settledRaw && settledRaw.ticketId === ticket.actionId) {
            let touched = false;
            for (let i = 0; i < settledRaw.cells.length; i++) {
                const cell = settledRaw.cells[i];
                if (verifyChars[cell.index] !== cell.char) {
                    if (!touched) {
                        verifyChars = verifyChars.slice();
                        touched = true;
                    }
                    verifyChars[cell.index] = cell.char;
                }
            }
        }
        const verifyUnknown = verifyChars === observed.chars
            ? observed.unknownCells
            : countUnknownChars(verifyChars);
        /**
         * 交给验证器的票据：基准刚被「按观测改正」过时，用当前基准 + 我方这手**重建期望值**。
         *
         * 为什么要它（2026-09-19，K40）：验证器读的是 `ticket.expectedAfterFen` ——
         * **发票据那一刻**冻结的 FEN。已知事实被证伪后（OCR 读到空格）基准与补齐表都改对了，
         * 但票据里那张旧期望值还把被证伪的子算进去 ⇒ 验证永远
         * 「装饰容差下有 N 种应手解释，不唯一」，整局钉死（日志里看得清清楚楚：
         * 确认观测没有那枚炮、期望里还有）。
         * ⚠️ 只在改正后的一段时间内重建；平时不重建，免得基准已经走到下一手时算错。
         * ⚠️ 重建的是**副本**，交给状态机的事件仍带原票据（按 id 匹配）。
         */
        let verifyTicket = ticket;
        // ⚠️ 守卫必须带 `baselineCorrectedAt > 0`：自检里用的是**假时钟**（可能从 0 开始计时），
        //    只比「间隔小于 120s」会恒为真 ⇒ 测试里也走重建路径 ⇒ 行为被改坏（2026-09-19 踩过：
        //    自检直接跑不出结果）。
        if (baselineCorrectedAt > 0 && nowMono() - baselineCorrectedAt < 120000) {
            const rebuilt = rebuildTicketBoards(ticket);
            if (rebuilt && (rebuilt.baseFen !== ticket.baseFen ||
                rebuilt.expectedAfterFen !== ticket.expectedAfterFen)) {
                verifyTicket = Object.assign({}, ticket, rebuilt);
                // 把两个盘面都打出来：验证器的容差判断同时看 baseFen 与 expectedAfterFen，
                // 不写清楚就只能猜（2026-09-19 为此多跑了两轮真机）。
                note("票据里的盘面已按当前基准重建：" + ticket.moveICCS +
                    "｜旧 base=" + (ticket.baseFen || "-").split(" ")[0] +
                    "｜新 base=" + rebuilt.baseFen.split(" ")[0] +
                    "｜旧期望=" + (ticket.expectedAfterFen || "-").split(" ")[0] +
                    "｜新期望=" + rebuilt.expectedAfterFen.split(" ")[0]);
            }
        }
        // 冲突明细（2026-09-14 真机）：以前只报「有 N 处关键冲突」，看不见是**哪一格**，
        // 48 次卡死的真因（同字异色抖动）只能靠翻视觉层源码猜。现在把格子与两个候选都打出来，
        // 并把「同字异色」单独计数（那类冲突已不再否决整帧，只在这里留痕）。
        (function noteConflicts() {
            const stats = observed.result && observed.result.stats;
            if (!stats) {
                return;
            }
            const cells = stats.conflictCells || [];
            const text = "冲突明细：异字 " + (stats.conflicts || 0) +
                " 处、同字异色 " + (stats.sameGlyphConflicts || 0) + " 处" +
                (cells.length
                    ? "｜" + cells.map(function (item) {
                        return item.name + " " + item.best + "/" + item.second +
                            "(" + item.bestScore + "/" + item.secondScore + ")" +
                            (item.sameGlyph ? "同字" : "异字");
                    }).join(" ")
                    : "");
            if (text !== lastConflictText) {
                lastConflictText = text;
                note(text);
            }
        })();
        let verdict = verifier.verify({
            ticket: verifyTicket,
            observed: verifyChars,
            unknownCells: verifyUnknown,
            stable: true,
            geometryTrusted: true,
            conflicts: observed.conflicts,
            // ★ 已裁定的票据（见 settledRaw）：**每一帧**都要告诉验证器「落点格的颜色以观测为准」——
            //   否则 `onlyDestination` 分支（落点被装饰画花 → 按票据改回）会把钉住的裁定值又改回期望值，
            //   本票就会永远在「僵持 → 复议 → 确认 → 又僵持」里循环（真机 v3 实测 48 次、5 分钟不动）。
            trustDestReading: !!(settledRaw && settledRaw.ticketId === ticket.actionId)
        });
        /**
         * ★ 「同字异色」僵持 → 拿**原始读数**复议一次（2026-09-20，K40 实拍定位）。
         *
         * 症状：整局钉死在 `UNRELIABLE：装饰容差下有 11 种应手解释，不唯一`，连刷两分钟不动。
         * 真机现场：我方炮吃掉对方一枚炮、落在 i7；对方**同一横线上的另一枚炮 b7×i7 反吃**。
         * 观测在 i7 读到的是 `c`（黑炮）—— **读得完全正确**；但
         *   ① 补齐表第 ③ 支把「同字异色」当读反，按已知事实写回 `C`；
         *   ② 期望纠色块又按 `toLowerCase()` 相等把 `c` 翻成 `C`。
         * 两处都把「我方那枚子已经没了」的证据抹掉 ⇒ 观测与期望只剩 b7 变空 ⇒ 看起来像
         * 「炮从 b7 走了、去哪儿没看见」⇒ 装饰容差枚举出的 11 种解释永不唯一（那 11 种正是
         * b7 那门炮的全部合法落点）⇒「等下一帧」等到天荒地老。截图放大核对：i7 上是黑字炮，
         * b7 留着上一步的白圈。
         *
         * 两个「纠色」本身是必要的（游戏装饰确实会让模型把红黑读反），所以不能删；
         * 改成**可证伪**：同字异色的格子连续 `cfg.colorDisputeStreak` 帧不变时，把这些格按
         * 原始读数放回去重新验证，**只有拿到成功结论（CONFIRMED / OPPONENT_REPLY）才采信**。
         * 装饰抖动撑不过连续几帧，真被反吃则一直稳定。
         */
        if (verdict.result === verifier.RESULT.UNRELIABLE && observed.rawChars && observed.chars) {
            const disputed = [];
            for (let i = 0; i < 90; i++) {
                const raw = observed.rawChars[i];
                const fixed = verifyChars[i];
                if (typeof raw !== "string" || typeof fixed !== "string") {
                    continue;
                }
                if (raw !== fixed && /^[A-Za-z]$/.test(raw) &&
                    raw.toLowerCase() === fixed.toLowerCase()) {
                    disputed.push(i);
                }
            }
            let disputeKey = "";
            for (let i = 0; i < disputed.length; i++) {
                disputeKey += disputed[i] + ":" + observed.rawChars[disputed[i]] + ",";
            }
            // ★ 第二类僵局：一个「同字异色」分歧都没有，但同一张盘面卡了 N 帧。
            //   真机现场（2026-09-20 第二局）：模型把**反吃过来的那枚黑炮直接读成我方颜色**
            //   （原始读数 == 期望）⇒ 分歧形不成 ⇒ 永远「装饰容差下有 11 种应手解释，不唯一」。
            //   这类僵局交给「对方吃在我方落点」的假设去解释（verifier.allowOwnDestCapture）。
            const disputeKeyResolved = disputeKey || ("stuck:" + verifyChars.join(""));
            const disputeMode = disputeKey ? "同字异色" : "盘面";
            if (disputeKeyResolved && disputeKeyResolved === lastColorDisputeKey) {
                lastColorDisputeStreak += 1;
            } else {
                lastColorDisputeKey = disputeKeyResolved;
                lastColorDisputeStreak = disputeKeyResolved ? 1 : 0;
            }
            if (disputeKeyResolved && lastColorDisputeStreak >= cfg.colorDisputeStreak) {
                const rawBoard = verifyChars.slice();
                for (let i = 0; i < disputed.length; i++) {
                    rawBoard[disputed[i]] = observed.rawChars[disputed[i]];
                }
                const rawVerdict = verifier.verify({
                    ticket: verifyTicket,
                    observed: rawBoard,
                    unknownCells: countUnknownChars(rawBoard),
                    stable: true,
                    geometryTrusted: true,
                    conflicts: observed.conflicts,
                    // ⚠️ 必须带这个：验证器里还有一道「落点格被装饰画花 → 按票据改回」的修复
                    //   （`onlyDestination` 分支），不关掉它，原始读数会在里面**又被改回 C** ——
                    //   刚实现时真机复现过：复议照样返回「装饰容差下有 11 种应手解释，不唯一」。
                    trustDestReading: true,
                    // ★ 允许「对方吃在我方落点上」这个解释（同兵种反吃；见 verifier 里的说明）。
                    allowOwnDestCapture: true
                });
                const cellNames = disputed.length
                    ? disputed.map(function (i) {
                        return "(" + (i % 9) + "," + Math.floor(i / 9) + ")" +
                            observed.rawChars[i] + "→" + verifyChars[i];
                    }).join(" ")
                    : "无同字异色分歧";
                if (verifier.isSuccess(rawVerdict.result)) {
                    stats.colorDisputeAdopted += 1;
                    note(disputeMode + "僵持 " + lastColorDisputeStreak + " 帧（" + cellNames +
                        "）→ 按原始读数复议：" + verifier.describe(rawVerdict));
                    verdict = rawVerdict;
                    // 下游跳过「应手两次佐证」：这个结论已经有**连续多帧**证据（就是那 N 帧僵持）。
                    verdict.disputeAdopted = true;
                    // 把**验证器改正过的那几格**钉住（本票之内以裁定值为准）
                    const pinned = [];
                    if (rawVerdict.board) {
                        for (let i = 0; i < 90; i++) {
                            if (rawVerdict.board[i] !== verifyChars[i]) {
                                pinned.push({ index: i, char: rawVerdict.board[i] });
                            }
                        }
                    }
                    if (pinned.length > 0) {
                        settledRaw = { ticketId: ticket.actionId, cells: pinned };
                    }
                    lastColorDisputeKey = "";
                    lastColorDisputeStreak = 0;
                } else if (lastColorDisputeStreak === cfg.colorDisputeStreak) {
                    note(disputeMode + "僵持 " + lastColorDisputeStreak + " 帧（" + cellNames +
                        "）→ 按原始读数复议仍无结论：" + verifier.describe(rawVerdict) +
                        "（继续按修正后的盘面等下一帧）");
                }
            }
        } else {
            lastColorDisputeKey = "";
            lastColorDisputeStreak = 0;
        }
        note("确认：" + verifier.describe(verdict));
        // ★ 「游戏没接受这一步」→ 记入本局面黑名单，下次规划不再选它。
        // 为什么必须有：引擎是确定性的，重规划会算出**同一手**；真机实测（K40，c2c6）点下去局面
        // 毫无变化 → 确认 UNCHANGED → 重试 1 次仍 UNCHANGED → RECOVERING → 重新规划又选中同一手
        // → 永远走不完一局。记黑名单后就会换次优着法继续下。
        if (verdict.result === verifier.RESULT.UNCHANGED) {
            markMoveRejected(ticket, "确认未落子（局面与走之前一致）");
        }
        // 诊断（量很少，一手棋 2~3 行）：把实际喂给验证器的观测盘面与期望/基准都打出来。
        // 「确认判错」这类问题不看这三串就只能猜（真机踩过：观测其实是「我方落子+对方应手」，
        // 但合并/补齐改过的格子没记录下来，事后无法复盘）。
        if (!verifier.isSuccess(verdict.result)) {
            note("  确认观测：" + boardText(observed.chars) +
                "｜期望：" + (ticket.expectedAfterFen || "-") +
                "｜基准：" + (ticket.baseFen || "-"));
        }
        if (verifier.isSuccess(verdict.result)) {
            stats.confirms += 1;
            verifyCycle += 1;
            // ★ 我方这一步**真的走了** → 起点那一格从此是空的，它在补齐表里的记录必须作废。
            // ⚠️ 以前这里是「按周期把**整张表**作废」（`patch.cycle !== verifyCycle`）——
            // 真机事故（2026-09-14，K40，auto35.log）：上一步「对方刚落子」的格子登记在周期 N，
            // 我方这手一确认周期变 N+1 → 那条记录立刻失效 → 那个子带着高亮环被读成**空** →
            // 观测比期望只差这一格 → 装饰容差枚举出 8 种结构等价的解释（“那格子的炮走到别处、
            // 新位置没看见”）→「不唯一」→ 连刷 54 次、一手棋白等 54 秒、连第二手都走不下去。
            // 现在只丢**我方这手的起点**，其余记录留到它们真的失效（自己认出来了 / 有子从那里离开 /
            // 被换成异色子）—— 判断依据是“棋子动了”，不是“过了几手”。
            const from = transition.squareIndex(ticket.moveICCS, 0);
            if (from >= 0) {
                dropPatchesAt([from], "我方这手 " + ticket.moveICCS + " 的起点已空");
            }
            // 这手已经写进（或即将写进）可靠基准 —— 补齐时不再按「在飞」处理：
            // 留着会把**下一手**的观测也按这一步走过之后的盘面去比（多走一步）。
            activeOwnMove = null;
        }
        // ★ 确认成功后**刷新可靠基准** —— ③ 连续走子必需的一环（K40 真机踩到）：
        // 不刷新的后果：基准还停在「我方落子之前」，而下一帧局面已经多了我方这手（以及对方应手）
        //   → 从旧基准看是「两手都变了」→ 观察层「唯一合法着法能解释」这条补不成立
        //   → 对方应手那格的高亮环补不出来（未知格≠0）→ `sideFor` 直接放弃 → 8s 看门狗
        //   → 永久 RECOVERING（真机日志：确认 CONFIRMED 之后就一直「轮次未知超时」）。
        // 刷成「确认后的局面」之后：对方应手只改 2 格，正好是 F2 能解释的情形 → 补得出来 → 轮次接得上。
        if (verifier.isSuccess(verdict.result) && verdict.board && verdict.board.indexOf("?") < 0) {
            const mySide = ticket.baseSide === "b" ? "b" : "w";
            // ★ 应手要跨帧佐证过才采信（见 replyCorroborated）：单独一帧枚举出来的应手
            //   可能是模型重复框伪影造的**假应手**，写进可靠盘面就会把整局钉死。
            const replyMove = (verdict.plies && verdict.plies.length > 1) ? verdict.plies[1] : null;
            const replyKey = replyMove
                ? (ticket.moveICCS + ">" + replyMove + ">" +
                    (verdict.replyPatch ? (verdict.replyPatch.index + ":" + verdict.replyPatch.char) : "-"))
                : null;
            // ★ 「同字异色复议」采信的应手自带连续多帧证据（僵持 ≥ `cfg.colorDisputeStreak` 帧），
            //   不再要求「两次佐证」—— 否则它永远进不了可靠基准，整局停在这一手上（真机实测）。
            const replyOk = replyMove == null || verdict.disputeAdopted === true
                ? true
                : replyCorroborated(replyKey);
            replyRejected = (replyMove != null && !replyOk);
            if (replyRejected) {
                note("应手待佐证：这一帧枚举出应手 " + replyMove +
                    "，等下一次验证再看到同一个应手才采信（防模型重复框伪影造出假应手）");
            }
            const nextSide = (verdict.result === verifier.RESULT.OPPONENT_REPLY && replyOk)
                ? mySide                                   // 对方已应手 → 又轮到我方
                : (mySide === "w" ? "b" : "w");            // 只确认了我方落子（或应手未佐证）→ 轮到对方
            // 应手未佐证时：可靠盘面只取「我方的着法」，不把那一帧的应手带进去。
            const reliableBoard = (replyOk || !expectedBoard) ? verdict.board.slice() : expectedBoard.slice();
            lastReliable = { board: reliableBoard, side: nextSide };
            // A3 影子：先把「我方这手」入账，再把已采信的应手入账（不一致只计数，不影响行为）
            const ledgerBase = expectedBoard ? boardOfFen(ticket.baseFen) : null;
            if (ledgerBase && expectedBoard) {
                trackConfirm({
                    iccs: ticket.moveICCS, fromBoard: ledgerBase, side: ticket.baseSide,
                    afterBoard: expectedBoard, source: "our-click"
                });
            }
            if (replyOk && replyMove && expectedBoard) {
                trackConfirm({
                    iccs: replyMove, fromBoard: expectedBoard,
                    side: mySide === "w" ? "b" : "w",
                    afterBoard: reliableBoard, source: "opponent-observed",
                    toleranceIndexes: verdict.replyPatch ? [verdict.replyPatch.index] : []
                });
            }
            decisionEpoch += 1;   // 一手棋真正落定 → 新的回合世代（循环历史去重只在世代内生效）
            note("可靠基准已刷新（确认 " + verdict.result + (replyRejected ? "，应手未采信" : "") + "）：" +
                (nextSide === mySide ? "轮到我方" : "轮到对方"));
            // 确实取得了进展（棋真的变了）→ 才能清尝试预算。
            // 恢复成功、换新票据、换几何**都不算**进展，所以它们都不在这里。
            clearAttemptBudget("已确认真实变化 " + verdict.result);
        }
        // ★ 把「落点格」登记进已知事实表（不管这次确认有没有用到它）。
        // 为什么要无条件登记：游戏会给我方刚落的子画高亮环，**下一帧起**那一格就认不出来了。
        // 如果只在「确认时刚好是未知格」才登记，就会出现：确认成功 → 下一帧那格变 '?' →
        // 未知格≠0 → `sideFor` 直接放弃 → 8s 看门狗 → 永久 RECOVERING
        // （真机实测：一手棋确认成功后原地空转 90 秒，没走第二手）。
        // 落点这一格是**已知事实**（票据 + 棋规推出来的），不是猜；等那格能自己认出来就自动失效。
        if (verifier.isSuccess(verdict.result) && expectedBoard) {
            let dest = null;
            try {
                dest = verifier.destinationIndex(ticket);
            } catch (error) {
                dest = null;
            }
            if (dest != null && expectedBoard[dest]) {
                addPatch({ index: dest, char: expectedBoard[dest] }, "落点格（我方新落的子会被画环）");
            }
            // ★ 途经格也要登记（2026-09-19，K40 实测）：
            // 游戏会把走子**轨迹**画出来，那条线盖住的中间格会被模型读成 '?'，而且会一直留到
            // 下一步 → 观察层每帧差 1 个未知格 → `sideFor` 放弃 → 「轮次未知」空转 60 秒（重定位也没用）。
            // 途经格的内容是**棋规唯一的**（直线滑行 ⇒ 全空；炮吃子 ⇒ 中间恰有一枚炮架），
            // 期望盘面里就写着：有子就按子登记，空就登记成空网格。读到别的子时自动作废（见 registerPatch）。
            let pathSet = null;
            try {
                pathSet = verifier.pathIndexesBetween ? verifier.pathIndexesBetween(ticket) : null;
            } catch (error) {
                pathSet = null;
            }
            if (pathSet) {
                const pathKeys = Object.keys(pathSet);
                for (let i = 0; i < pathKeys.length; i++) {
                    const index = Number(pathKeys[i]);
                    if (expectedBoard[index]) {
                        addPatch({ index: index, char: expectedBoard[index] }, "途经格（游戏画的走子轨迹）");
                    } else {
                        addEmptyPatch(index, "途经格（游戏画的走子轨迹，棋规上必为空）");
                    }
                }
            }
        }
        // 落点格被游戏画花（未知）→ 记下来，后续观察一律按已知事实补齐。
        // 我方落点与对方应手落点都是棋盘上的事实（都从票据 + 棋规唯一推出来），不是猜。
        // （addPatch 本体在工厂作用域，见 registerMoveDestinations 旁边 —— sideFor 也要用它。）
        if (verdict.patched) {
            addPatch(verdict.patched, "落点格");
        }
        if (verdict.replyPatch && !replyRejected) {
            addPatch(verdict.replyPatch, "对方应手落点格");
        }
        // 应手未得到佐证 → 把结论降级成「只确认了我方落子」，并且**不把那个应手**
        // （连同它的补齐记录）交给状态机/后续帧；下一帧还能再枚举一次，两次一致才采信。
        const finalVerdict = replyRejected
            ? Object.assign({}, verdict, {
                result: verifier.RESULT.CONFIRMED,
                replyPatch: null,
                plies: [ticket.moveICCS],
                reason: (verdict.reason || "") + "｜应手未佐证（等下一次验证再确认）"
            })
            : verdict;
        // ★ 已知事实被证伪（OCR 读到空格/别的子、或催了多次仍读不出来）：
        //   补齐表与可靠基准已经改对了，**并且期望值已经在上面按当前基准重建**（见 verifyTicket），
        //   所以验证可以照常走完 —— 走完才能把基准推进到「我方这手之后」。
        //   ⚠️ 早先这里是「作废票据、重新观察」，结果是**基准永远停在走之前**，
        //      下一帧就变成「既有合法单步也解释不了」（真机实测：卡在 WAIT_TURN 轮次未知）。
        //      期望值能重建之后这条就没必要了（2026-09-19 改）。
        if (factInvalidated) {
            factInvalidated = false;
            note("（已知事实被证伪：补齐表与基准已改正，验证按重建后的期望值继续）");
        }
        // ★ 复议采信后（含本票后续每一帧）：把那几格的已知事实写成**裁定值**。
        //   必须放在本次确认所有补丁登记之后 —— 上面的「落点格」登记会按**票据里的旧期望值**
        //   把那一格又写成我方的子，那正是循环的燃料（真机实测：一期循环 49 次）。
        if (settledRaw && settledRaw.ticketId === ticket.actionId &&
            verifier.isSuccess(verdict.result)) {
            for (let i = 0; i < settledRaw.cells.length; i++) {
                const cell = settledRaw.cells[i];
                if (typeof cell.char === "string" && cell.char && cell.char !== "?") {
                    registerPatch({ index: cell.index, char: cell.char }, "复议裁定（裁定值）");
                } else {
                    dropPatchesAt([cell.index], "复议裁定：那格被判为空");
                }
            }
        }
        return pump(machine.dispatch({ type: "VERIFY_RESULT", ticket: ticket, verdict: finalVerdict }));
    }

    function recover() {
        // 不论这次是否真的重定位，RECOVERING 都表示旧基准/已知事实已经无法解释当前画面。
        // 必须在“25 秒内跳过重定位”的早退之前置位，否则最常走的快速恢复永远不会重建基准。
        needFreshBaseline = true;
        // ⚠️ 重新定位一次 ~8s（K40 实测 7.6~8.2s），而**棋子移动不改变几何**。
        // 失败循环里每次都重新定位的话，一手棋要白烧十几秒 → 这里限频：
        // 距上次定位不足 RELOCATE_MIN_INTERVAL_MS 时只「重新武装」（直接 RECOVERED → 下一轮观察）。
        const now = nowMono();
        if (lastRelocateMono > 0 && now - lastRelocateMono < RELOCATE_MIN_INTERVAL_MS) {
            note("恢复：距上次定位不到 " + Math.round(RELOCATE_MIN_INTERVAL_MS / 1000) +
                "s —— 几何没变，直接重新观察（省掉 ~8s 重定位）");
            return pump(machine.dispatch({ type: "RECOVERED" }));
        }
        lastRelocateMono = now;
        note("恢复：重新定位棋盘");
        // 恢复 = 这一手不再「在飞」（票据会作废/重发）：补齐时不能再按「走了一手」去比基准。
        // ⚠️ 但**票据还活着时不许清**（真机 2026-09-21 07:43 铁证）：确认 g0g3 期间一次
        //   「恢复：重新定位棋盘」把 activeOwnMove 清空 ⇒ 落点 (6,6) 失去保护，被幻影规则
        //   当成「多出来的那份」反复清 ⇒ 观测里我方那枚車永远不见 ⇒ 12~13 种解释的
        //   UNRELIABLE 死循环 + 后续崩溃。票据真作废（CANCEL/确认成功）时另有清理点。
        const liveTicket = typeof machine.currentTicket === "function" ? machine.currentTicket() : null;
        if (!liveTicket) {
            activeOwnMove = null;
        }
        // ⚠️ **不要**在这里清 lastReliable / 已知事实表（曾经清过，K40 实测直接死锁）：
        // recover 只是重新对齐几何，**轮次不是从当前帧推出来的、而是从上一步延续的**。
        // 一旦清掉，而局面已经不是标准开局（双方各走了一手），`sideFor` 就永远推不出来
        // → 永久 RECOVERING。保留基准反而更安全：`transition.infer` 会自己校验（一致=MOVE/
        // MOVE_PAIR，不一致=其它状态 → 仍然是「推不出」），不会拄着错盘面出手。
        // 补齐表里的格子是**棋盘格子下标**，与几何无关，换几何也一样有效。
        const located = opts.locate(locateOptions());
        if (!located || !located.ok) {
            stats.errors += 1;
            stats.lastError = located ? located.reason : "定位没有结果";
            note("恢复时定位失败（继续等下一轮）：" + stats.lastError);
            return null;   // 留在 RECOVERING，下一轮再试
        }
        grid = located.grid;
        note("恢复完成，几何 v" + grid.version);
        return pump(machine.dispatch({ type: "RECOVERED" }));
    }

    // ---- 「游戏不接受的着法」黑名单（按局面记，用于让引擎换一手） ----
    // 症状（K40 真机实测，2026-09-19）：点了 c2c6 局面毫无变化 → 确认 UNCHANGED → 状态机重试
    // 1 次仍 UNCHANGED → RECOVERING → 重新规划又选中**同一手**（引擎是确定性的）→ 又点又不变
    // → 永远走不完一局。这里按「基准局面 | 该走棋方」记住被拒的着法，规划时传给引擎排除。
    const rejectedMoves = new Map();

    function positionKeyOfBoard(board, side) {
        return (board ? board.join("") : "") + "|" + (side || "");
    }

    function positionKeyOfTicket(ticket) {
        if (!ticket || !ticket.baseFen) {
            return "";
        }
        let board = null;
        try {
            const parsed = fenModule.parseFen(ticket.baseFen);
            board = parsed && parsed.ok ? parsed.board : null;
        } catch (error) {
            board = null;
        }
        return board ? positionKeyOfBoard(board, ticket.baseSide) : "";
    }

    /** 记录「这手游戏没接受」，本局面内不再选（最多留 8 个局面，防止无限增长）。 */
    function markMoveRejected(ticket, why) {
        const key = positionKeyOfTicket(ticket);
        if (!key || !ticket || !ticket.moveICCS) {
            return;
        }
        let entry = rejectedMoves.get(key);
        if (!entry) {
            entry = { iccs: {}, at: nowMono() };
            rejectedMoves.set(key, entry);
        }
        if (!entry.iccs[ticket.moveICCS]) {
            entry.iccs[ticket.moveICCS] = true;
            note("这手游戏没接受（" + why + "），本局面内不再选：" + ticket.moveICCS +
                "（已记 " + Object.keys(entry.iccs).length + " 手）");
        }
        while (rejectedMoves.size > 8) {
            const oldest = rejectedMoves.keys().next();
            if (oldest.done) {
                break;
            }
            rejectedMoves.delete(oldest.value);
        }
    }

    /** 当前局面要排除的着法（没有就返回 null）。 */
    function rejectedIccsFor(board, side) {
        const entry = rejectedMoves.get(positionKeyOfBoard(board, side));
        if (!entry) {
            return null;
        }
        const list = Object.keys(entry.iccs);
        return list.length ? list : null;
    }

    /** 引擎评分是不是「我方杀棋」（只看正杀分；被将死的负分不影响点击，不用管）。 */
    function isMateClaim(score) {
        return typeof score === "number" && score >= MATE_CLAIM_MIN_SCORE;
    }

    // ---- 「循环 / 长捉」看门狗（2026-09-21 真机输棋：车 f1↔f3 与马 h3↔g1 摆了 4 手） ----
    // 症状：我方车在两点来回、对方跟着摆，评分在 269/118 之间反复。引擎是确定性的、且
    // **没有「重复局面 / 长捉」概念**（中国象棋规则：单方面长捉 3 次要判负），于是永远选回来。
    // 处置（规划时把这些着法从候选里去掉，逼引擎换一手；引擎侧全被禁时会自动放弃过滤再搜）：
    //   · 同一局面第 3 次出现 ⇒ 把这个局面走过的着法都禁掉；
    //   · 两招来回（A,B,A 之后又轮到 B 局面）⇒ 禁掉「该局面上一轮走的那手」，直接断环。
    const planHistory = loopHistoryModule.createLoopHistory();
    // ★ 「回合世代」：每刷新一次可靠基准（= 一手棋真正落定 + 验证过）就 +1。
    //   同一个世代里「同局面 + 同手」连着出现只算一次（那是取消重算/重复发令，不是一个新回合）；
    //   跨世代的同局面同手才是**真摆回来**（长将/长捉），必须照记，第 3 次才禁得掉。
    //   真机 2026-09-21：09:08 一次 CANCEL 重算把 b2c2 记了两遍（误触禁手）；而 09:17/09:20
    //   两次 d6e6 是双方真的把局面摆回来了（该禁）—— 两者必须区分开。
    let decisionEpoch = 0;

    function recordPlanDecision(board, side, iccs) {
        if (!board || !iccs) {
            return;
        }
        planHistory.record(positionKeyOfBoard(board, side), iccs, decisionEpoch);
    }

    /** 当前局面的「疑似循环」禁用着法（没有就返回 null）。 */
    function loopBansFor(board, side) {
        if (!board) {
            return null;
        }
        return planHistory.bansFor(positionKeyOfBoard(board, side));
    }

    /** 规划要用 exclude 的着法 = 游戏拒绝过的 + 疑似循环（长捉）的。 */
    function excludedIccsFor(board, side) {
        const merged = [];
        const rejected = rejectedIccsFor(board, side);
        if (rejected) {
            for (let i = 0; i < rejected.length; i++) {
                merged.push(rejected[i]);
            }
        }
        const loop = loopBansFor(board, side);
        if (loop) {
            stats.loopBanHits += 1;
            note("疑似循环（长捉）：这个局面已经反复摆回来，禁用 " + loop.join("/") +
                "（规则上长捉要判负，让引擎换一手）");
            for (let i = 0; i < loop.length; i++) {
                merged.push(loop[i]);
            }
        }
        return merged.length ? merged : null;
    }

    /** 用已知事实补丁表把盘面上的 '?' 补掉（只补登记过、且当前确实未知的格子）。 */
    function applyKnownPatchesTo(chars) {
        return knownFacts.applyTo(chars);
    }

    function countUnknownChars(chars) {
        if (!chars) {
            return 0;
        }
        let count = 0;
        for (let i = 0; i < chars.length; i++) {
            if (chars[i] === "?") {
                count += 1;
            }
        }
        return count;
    }

    /**
     * 各兵种的**物理上限**（每方一个；象棋无升变、棋子不会凭空多出来）
     * —— 用来否决「兜出不可能盘面」的填充（见顽固未知兜底处的硬校验）。
     */
    function glyphPieceCap(ch) {
        switch (String(ch).toUpperCase()) {
            case "P": return 5;
            case "R": case "N": case "C": case "B": case "A": return 2;
            case "K": return 1;
            default: return 99;
        }
    }

    function countGlyphChars(chars, ch) {
        if (!chars) {
            return 0;
        }
        let count = 0;
        for (let i = 0; i < chars.length; i++) {
            if (chars[i] === ch) {
                count += 1;
            }
        }
        return count;
    }

    // ---- 观察 ----

    /** 定位参数：棋盘方向 + 可选的范围比例。 */
    function locateOptions() {
        const options = { orientation: cfg.orientation };
        if (cfg.regionRatio) {
            options.regionRatio = cfg.regionRatio;
        }
        return options;
    }

    /**
     * 观察层的「已知事实补齐」：用**唯一解释**把少数未知格补出来。
     *
     * 为什么需要：游戏给**刚落的子**画高亮环，那一格会被模型认错（实测带环的炮读成马），
     * 棋规层再降级成 '?'。而状态机的轮次推断要求 `unknownCells === 0` —— 于是对方刚应完一手，
     * 驱动就“看不见轮次” → 超时进 RECOVERING；`recover()` 又把基准清掉，而局面已经不是
     * 标准开局 → **再也推不出轮次，永久卡死**（K40 实测：走完第一手后就卡在这）。
     *
     * 两种**可验证**的解释（都不是猜，全都要精确对上其余所有已知格）：
     *   F1 与上一张可靠盘面逐格一致（对方那手的环还在）→ 直接按基准补；
     *   F2 恰好等于「上一张可靠盘面 + 某一个合法着法」→ 且只有**唯一一种**解释。
     * 解释不唯一或对不上就返回 null，保持未知（宁可等着）。
     */
    function fillKnownFacts(chars) {
        if (!lastReliable || !lastReliable.board) {
            return null;
        }
        const base = lastReliable.board;
        // ⚠️ 基准自己必须是**没有未知格**的（它的名字就是「已知事实」）。
        // 真机踩到：assertTurn 把一个仍然带 '?' 的局面当成了基准 → F1 把 '?' 原样抄进结果、
        // 还报「与上一张可靠盘面一致」并把 unknownCells 当成 0 → 下游 buildFen 抛
        // 「存在未知格，不能生成 FEN」→ 每轮都被当异常跳过，面板上只看到「轮次未知」。
        if (base.indexOf("?") >= 0) {
            return null;
        }
        const unknown = [];
        for (let i = 0; i < 90; i++) {
            if (chars[i] === "?") {
                unknown.push(i);
            }
        }
        if (unknown.length === 0) {
            return null;
        }
        const norm = function (ch) { return ch == null ? null : ch; };
        const matches = function (board) {
            for (let i = 0; i < 90; i++) {
                if (chars[i] === "?") {
                    continue;
                }
                if (norm(chars[i]) !== norm(board[i])) {
                    return false;
                }
            }
            return true;
        };
        // F1：除了未知格，其余格子与上一张可靠盘面完全一致
        // ★ 我方这手还在飞（已发出点击、还没确认）时，基准要**先走一手**再比：
        //   起点补空、落点补「走过去的那个子」。否则 F1 会照落子前的基准把**起点**
        //   补回一枚棋子 —— 真的幻影子（2026-09-20 K40：b2c2 之后基准变成 …1CC4C1…）。
        let f1Base = base;
        if (activeOwnMove && activeOwnMove.fromIndex >= 0 && activeOwnMove.destIndex >= 0) {
            const moving = base[activeOwnMove.fromIndex];
            if (moving) {
                f1Base = base.slice();
                f1Base[activeOwnMove.destIndex] = moving;
                f1Base[activeOwnMove.fromIndex] = null;
            }
        }
        if (matches(f1Base)) {
            const board = chars.slice();
            for (let k = 0; k < unknown.length; k++) {
                board[unknown[k]] = f1Base[unknown[k]];
            }
            if (board.indexOf("?") >= 0) {
                return null;   // 补不出确定的局面就别假装成功
            }
            return { board: board, how: "与上一张可靠盘面一致（对方那手的环还在）" };
        }
        // F2：唯一一个合法着法就能完全解释
        const fits = [];
        const sides = [lastReliable.side, lastReliable.side === "w" ? "b" : "w"];
        for (let s = 0; s < sides.length && fits.length < 2; s++) {
            const moves = legal.allLegalMoves(base, sides[s]);
            for (let m = 0; m < moves.length && fits.length < 2; m++) {
                let after = null;
                try {
                    after = legal.applyMove(base, moves[m]).board;
                } catch (error) {
                    continue;
                }
                // 未知格只允许落在「这一步改动过的格子」上
                const diff = legal.diffBoards(base, after);
                const changed = {};
                for (let d = 0; d < diff.length; d++) {
                    changed[diff[d].row * 9 + diff[d].col] = true;
                }
                let allowed = true;
                for (let k = 0; k < unknown.length; k++) {
                    if (!changed[unknown[k]]) {
                        allowed = false;
                        break;
                    }
                }
                if (!allowed || !matches(after)) {
                    continue;
                }
                fits.push({ board: after, iccs: moves[m].iccs });
            }
        }
        if (fits.length !== 1 || fits[0].board.indexOf("?") >= 0) {
            return null;
        }
        return { board: fits[0].board, how: "唯一合法着法 " + (fits[0].iccs || "?") + " 解释得通" };
    }

    /**
     * 多帧合并识别（默认 2 帧）：**两帧一致的格子才采信，不一致的降级成未知格**。
     *
     * 为什么必须这么干（K40 真机实测）：模型单帧会有**多处**孤立误读 —— 同一帧里同时出现
     * 「黑車(0,0) 读成空」「(0,1) 空位读成黑炮」「黑炮(7,2) 读成红炮」共 3~4 处。
     * 这种盘面任何推断都解释不了 → 轮次推不出来 → 8s 看门狗 → 永久 RECOVERING。
     * 而误读是**逐帧随机**的：第二帧往往是对的 → 取「两帧都同意」的格子，
     * 冲突格算未知格，交给已有的「唯一合法着法能解释就补齐」（fillKnownFacts）走 ——
     * 宁可拿一张“部分未知”的真盘面，也不要拿一张“看起来完整”的假盘面。
     *
     * 代价：每次观察多一帧（K40 shell 通道 ~1.2s/帧），tickMs 已留 2.4s 余量。
     */
    function recognizeMerged(request) {
        // ★ 逐次覆盖（observeFramesByState）：等待对方时 1 帧（省 ~0.4s/次观察）；其余走全局 observeFrames。
        const framesCfg = (request && request.frames != null)
            ? request.frames
            : (cfg.observeFrames == null ? 2 : cfg.observeFrames);
        const times = Math.max(1, Math.min(3, framesCfg));
        const frames = [];
        let first = null;
        for (let i = 0; i < times; i++) {
            if (i > 0 && cfg.observeGapMs > 0) {
                clock(cfg.observeGapMs);
            }
            const one = opts.recognize(request);
            if (i === 0) {
                first = one;
            }
            if (!one || !one.ok || !one.chars) {
                if (i === 0) {
                    return one;            // 第一帧就失败：直接上报（下一轮再试）
                }
                break;                     // 后续帧失败就用已有结果
            }
            frames.push(one);
        }
        if (!frames.length) {
            return first;
        }
        /** 严格合并：两帧一致的格子才采信，不一致的降级成未知格（见上面的长注释）。 */
        function combine(list) {
            const chars = list[0].chars.slice();
            let conflicts = 0;
            for (let f = 1; f < list.length; f++) {
                for (let k = 0; k < 90; k++) {
                    if (chars[k] === list[f].chars[k]) {
                        continue;
                    }
                    chars[k] = "?";
                    conflicts += 1;
                }
            }
            let unknown = 0;
            for (let k = 0; k < 90; k++) {
                if (chars[k] === "?") {
                    unknown += 1;
                }
            }
            return { chars: chars, unknown: unknown, conflicts: conflicts, source: list[list.length - 1] };
        }
        let merged = combine(frames);
        stats.frameConflicts += merged.conflicts;
        stats.mergedFrames += 1;
        /**
         * ★ 2026-09-20：观察帧撞上「走子动画」时，同一格一帧读成子、一帧读成暗影 ⇒ 严格合并把
         * 它降级成未知格 ⇒ 「布局没生成」白等。做法：丢掉最早那帧、隔 `observeRetryGapMs` 补抓一帧重合并。
         */
        if (merged.unknown > 0 && frames.length >= 2 && cfg.observeRetryOnUnknown === true) {
            clock(cfg.observeRetryGapMs == null ? 420 : cfg.observeRetryGapMs);
            const again = opts.recognize(request);
            if (again && again.ok && again.chars) {
                const retry = combine([frames[frames.length - 1], again]);
                stats.observeRetries = (stats.observeRetries || 0) + 1;
                stats.frameConflicts += retry.conflicts;
                if (retry.unknown < merged.unknown) {
                    stats.observeRetryHits = (stats.observeRetryHits || 0) + 1;
                    note("补一帧躲开走子动画：未知格 " + merged.unknown + " → " + retry.unknown);
                    merged = retry;
                }
            }
        }
        // 合并后的盘面挂到**最后一帧**上：框中心（点棋用）/几何 都要用最新的那一帧。
        const out = merged.source || first;
        if (times > 1) {
            out.chars = merged.chars;
        }
        out.unknownCells = merged.unknown;
        return out;
    }

    /**
     * 单格重拍（2026-09-19，K40 实测卡点）。
     *
     * 场景：已知事实表说「这格是子 X」（票据 + 棋规唯一推出来的），模型却**整格读成空**。
     * 这不是「子走了」的强证据 —— 整格读成空意味着**检测框都没出来**，而模型本身只有 mAP50 0.53。
     * 后果：每帧差 1 个未知格 → `sideFor` 数不齐子力 → 「轮次未知」→ RECOVERING 空转 60 秒
     * （真机：对方刚落下的黑炮，连续几十帧都是空）。
     *
     * 做法：**换一帧、降检测阈值**重认一遍，但**只采信可疑格**的读数，其余格一概忽略。
     * 采信规则两条，都不能凭空造子：
     *   · 重拍读到字母：必须与已知事实**同色**（票据说这格是谁的子，就还是谁的）；
     *   · 重拍读到 '?'（有框但认不出）：说明那格确实有东西 ⇒ 按已知事实的字补回去。
     * 仍然读不到就作罢（下一轮还会再试），**绝不用已知事实直接盖章** ——
     * 那正是 2026-09-14 的事故：把真的走掉的子补回来 → 整帧看起来没变 → 永久 UNCHANGED。
     *
     * @param suspects [{index, char}] 且 char 必须非空（空网格不走这里）
     */
    /**
     * 单格 OCR 结论（限频：同一格 `cfg.ocrCooldownMs` 内只问一次）。
     *
     * 为什么要有它（2026-09-19，K40）：模型对某些格子读不出来（一会儿 '?'、一会儿整格空），
     * 只能靠补齐表猜；而 OCR 读的是**字形**，能直接给出「那格到底有没有子」的证据。
     * 返回 state：
     *   · "match"   OCR 读到的字与已知事实一致（同字或同色）→ 那格确实有子
     *   · "empty"   OCR 没读到任何字 → 那格是空的（不是模型漏检）
     *   · "other"   OCR 读到的是别的子 → 已知事实过期/记错了
     *   · "unknown" OCR 不可用或这次没问 → 退回「猜」的策略（催游戏等）
     */
    /**
     * 「即将丢掉一条已知事实」时的最后一道证据：问一次 OCR（优先单格 `ocrCell`，退而全盘 `ocrBoard`）。
     *
     * 只比**字形类别**（`.toLowerCase()` 相等）：OCR 只可信到兵种，颜色以已知事实为准
     * （与 `ocrCheckPatch` 的口径一致）。拿不到证据返回 null —— 那就照旧丢记录。
     */
    function ocrCellEvidence(index, char) {
        if (typeof opts.ocrCell === "function") {
            try {
                const one = opts.ocrCell({ index: index, char: char });
                if (one && one.ok && (one.cls || one.char)) {
                    return String(one.cls || one.char);
                }
            } catch (error) { /* 退到全盘 OCR */ }
        }
        if (typeof opts.ocrBoard === "function") {
            try {
                const board = opts.ocrBoard();
                if (board && board.ok && board.chars) {
                    const got = board.chars[index];
                    if (typeof got === "string" && got && got !== "?") {
                        return got;
                    }
                }
            } catch (error) { /* 当没有证据 */ }
        }
        return null;
    }

    /**
     * 问一次「那一格是**哪一边**」（像素颜色，配单格 OCR 一起用）—— 用户提议 2026-09-20：
     * 「YOLO 只定位一次，剩下的用找色判走动、OCR 认子」。这里是它的最小落地：
     * 遇到「同字异色」这种最危险的歧义时，拿**像素证据**代替猜。
     *
     * 返回 { side: "RED"|"BLACK", cls } 或 null（拿不到证据就不做判断）。
     * 限频：同一格 `cfg.ocrCooldownMs` 内只问一次（一帧多问几次会把截图/OCR 打满）。
     */
    function cellSideEvidence(patch) {
        if (typeof opts.ocrCell !== "function") {
            return null;
        }
        const at = nowMono();
        if (patch.sideAt && at - patch.sideAt < cfg.ocrCooldownMs) {
            return null;
        }
        patch.sideAt = at;
        let one = null;
        try {
            one = opts.ocrCell({ index: patch.index, char: patch.char });
        } catch (error) {
            one = null;
        }
        stats.ocrChecks += 1;
        if (!one || !one.ok || (one.side !== "RED" && one.side !== "BLACK")) {
            return null;
        }
        return { side: one.side, cls: one.cls || null, score: one.score };
    }

    /**
     * 未知格的最后一道证据（2026-09-20，用户提议「YOLO 只定位一次，剩下用找色判走动、OCR 认子」）：
     * 模型整格读不出来（高亮环/木纹压字）时，直接对那一格问 **OCR（认什么子）+ 像素找色（判哪一边）**。
     *
     * 为什么必须有它：真机 v7 局卡死 —— 盘面上 (2,0) 一格未知 ⇒ 「存在未知格，不能生成 FEN」
     * 每轮抛异常 ⇒ RECOVERING 空转（连重建基准都做不了）。就差这一格，而 OCR + 找色本来就能给出来。
     * 返回棋子字符（如 'b' / 'C'）；拿不到证据（不可用/没字/阵营判不出）返回 null —— **不猜**。
     */
    const evidenceAt = {};
    /** 每一格「连续未知」的帧数（给「顽固未知→按可靠盘面兜」用，见 unknownFillFromReliableFrames） */
    const unknownStreak = {};
    /**
     * 兜底值被「兵种上限」**连续驳回**了几帧（index → 帧数；真机 2026-09-21 10:33 的 (5,7) 死锁）。
     * 驳回只说明基准那一格过时了（同一兵种已经满额）——连续几帧都这样就把那一格判空
     * （棋子不会分身，基准又拿不出第三枚），见 observeOnce 填充环节里的处置。
     */
    const capRejectedStreak = {};
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
    let unknownSideStreak = 0;
    /** 「被挡住就按基准兜」的粘住表：index → 到期时刻（ms）。见 stallFillHoldMs */
    const stallFilledUntil = {};
    /**
     * 本帧「顽固未知 → 按旧基准填」的格子（= 被游戏「上一步」标记盖住、读不出来的格）。
     * 轮次推断拿它做「被盖住的应手」唯一合法着法枚举（见 sideFor）。
     * 真机 K40 2026-09-21 死区：黑炮 b7b5 的起点/落点被盖住 ⇒ 每帧填回旧值 ⇒ 盘面永远没变化 ⇒
     * 轮次停在「轮到对方」5 分钟，直到被绝杀。
     */
    let coveredFilledIndexes = [];
    /**
     * 一格的「颜色 + OCR」证据（用户提议 2026-09-20：**棋子定位用找色扫描，不再只靠模型**）。
     * 返回：
     *   · hasPiece：找色两边的墨迹密度都低于门槛 ⇒ false（空格）；至少一边有墨迹 ⇒ true；拿不到 ⇒ null
     *   · side：RED / BLACK（像素判阵营）
     *   · char：'C' / 'c' 这种完整棋子字符（OCR 认出兵种 + 像素定阵营才有）
     * 拿不到证据返回 null —— 调用方别据此下结论。
     * 限频：同一格 `cfg.ocrCooldownMs` 内只问一次（一次 = 抓一帧 + 一次 OCR + 两次找色）。
     */
    function cellEvidence(index) {
        if (typeof opts.ocrCell !== "function") {
            return null;
        }
        const at = nowMono();
        if (evidenceAt[index] && at - evidenceAt[index] < cfg.ocrCooldownMs) {
            return null;
        }
        evidenceAt[index] = at;
        let one = null;
        try {
            one = opts.ocrCell({ index: index });
        } catch (error) {
            one = null;
        }
        stats.ocrChecks += 1;
        if (!one) {
            return null;
        }
        // 空/有子：按找色密度（实测：有子的格 0.08~0.16，空格两边都 ≈0）；
        // 暗墨迹是第三路（2026-09-21 d7 黑士案：细笔画黑子的找色密度会压 6% 线、偏裁剪掉到 5.8%，
        // 而暗像素口径 8~13%、空格 0%）⇒ 三者只要有一路到门槛就算有子。
        let hasPiece = null;
        const ink = one.ink || null;
        if (ink && ink.area > 0) {
            const redDensity = (ink.red || 0) / ink.area;
            const blackDensity = (ink.black || 0) / ink.area;
            const darkDensity = (ink.dark || 0) / ink.area;
            hasPiece = redDensity >= 0.02 || blackDensity >= 0.02 || darkDensity >= 0.04;
        }
        const side = (one.side === "RED" || one.side === "BLACK") ? one.side : null;
        let char = null;
        if (one.ok && one.cls && side) {
            const upper = String(one.cls).toUpperCase();
            char = side === "RED" ? upper : upper.toLowerCase();
        }
        return {
            hasPiece: hasPiece, side: side, char: char,
            cls: one.cls || null, noText: one.noText === true, ink: ink
        };
    }

    function evidenceCharFor(index) {
        const evidence = cellEvidence(index);
        return evidence ? evidence.char : null;
    }

    function ocrCheckPatch(patch) {
        const at = nowMono();
        if (patch.ocrAt && at - patch.ocrAt < cfg.ocrCooldownMs) {
            return { state: "unknown", reason: "限频" };
        }
        patch.ocrAt = at;
        let res = null;
        try {
            res = opts.ocrCell({ index: patch.index, char: patch.char });
        } catch (error) {
            res = null;
        }
        stats.ocrChecks += 1;
        if (!res) {
            return { state: "unknown", reason: "OCR 异常" };
        }
        if (res.ok && (res.cls || res.char)) {
            // 读到了字 ⇒ 这一格被证实了，把「连着读空」的计数清掉
            patch.ocrEmptyAt = 0;
            patch.ocrEmptySeen = 0;
            // ⚠️ 只比**类别**（方案 §13：字形不带红黑）；颜色以已知事实为准。
            const cls = String(res.cls || res.char);
            const sameGlyph = cls.toLowerCase() === String(patch.char).toLowerCase();
            const sameColor = (cls === cls.toUpperCase()) === (patch.char === patch.char.toUpperCase());
            return (sameGlyph || sameColor)
                ? { state: "match", cls: cls, glyph: res.glyph }
                : { state: "other", cls: cls, glyph: res.glyph };
        }
        // ⚠️ 只有「OCR 真的跑了但没读到字」才算候选空格；OCR 不可用/取帧失败要区分开（不能当作证据）
        if (res.noText === true) {
            // ★ 「读不到字」也要跨帧佐证：棋子可能**还在飞行中**（画面上隐形），
            //   拿那一帧判空会把真实事实丢掉（真机截图实锤：那枚子确实落在那一格）。
            const prev = patch.ocrEmptyAt || 0;
            patch.ocrEmptyAt = at;
            patch.ocrEmptySeen = (patch.ocrEmptySeen || 0) + 1;
            if (prev > 0 && at - prev >= cfg.ocrEmptyCorroborateMs) {
                return { state: "empty", seen: patch.ocrEmptySeen };
            }
            return {
                state: "unknown",
                reason: "判空待佐证（第 " + patch.ocrEmptySeen + " 次读到空，等下一次再确认）"
            };
        }
        return { state: "unknown", reason: res.reason };
    }

    /**
     * 把可靠基准里那一格按观测改正（置空）。
     * 为什么必须改：可靠基准是唯一权威，它带着一条无法证实的记录时，下一帧就会变成
     * 「基准里有子、观测里没子，没有任何合法着法解释得了」→ 永远 RECOVERING
     * （真机实测：未知格已归零，但停在「既没有合法单步也没有一步+应手能解释」）。
     */
    function resyncReliableAt(patch) {
        if (!patch || !lastReliable || !lastReliable.board ||
            lastReliable.board[patch.index] !== patch.char) {
            return;
        }
        // ★ 修订前先登记旧基准：等对方时这次修订可能吞掉对方刚走的那一手（见 recordBaselineRevision）
        recordBaselineRevision("找色/OCR 作废 (" + (patch.index % 9) + "," +
            Math.floor(patch.index / 9) + ")" + patch.char);
        const fixed = lastReliable.board.slice();
        fixed[patch.index] = null;
        lastReliable = { board: fixed, side: lastReliable.side };
        trackFix(fixed, lastReliable.side, { reason: "resync" });
        baselineCorrectedAt = nowMono();
        note("可靠基准已按观测改正那格（(" + (patch.index % 9) + "," +
            Math.floor(patch.index / 9) + ") 置空）：否则下一帧会「少一个子且解释不了」");
    }

    /**
     * 用「当前可靠基准 + 我方这手」重建票据里的两个盘面：`baseFen` 与 `expectedAfterFen`。
     *
     * 为什么要重建**两个**（2026-09-19，K40）：验证器既用 `expectedAfterFen`（期望落子后局面），
     * 也在 UNEXPECTED 分支里跟 `baseFen` 对差。只重建后者时，旧 `baseFen` 里那张带假事实的
     * 基准仍然在作祟 —— 真机日志：`UNEXPECTED：无法解释的局面（与基准差 3 格：
     * (col 4,row 2) c→空；…）`，而那时的可靠基准已经没有那枚子了。
     *
     * 只在「基准刚被按观测改正过」之后的一段时间内重建 —— 那时票据里的旧盘面必然已过期
     * （它把被证伪的那枚子算进去了）。平时不重建，避免基准已经走到下一手时算错。
     *
     * @return { baseFen, expectedAfterFen }；没法重建（缺基准/着法不合法）返回 null
     */
    function rebuildTicketBoards(ticket) {
        if (!lastReliable || !lastReliable.board) {
            return null;
        }
        // ★ 2026-09-21 真机（v5 慢局）：这里以前自己 `applyMove(board, { iccs })` —— 而 applyMove
        //   不解析 iccs ⇒ 静默返回「没走这手」的盘面 ⇒ 期望 == base ⇒ 确认阶段把「真走成了」的
        //   观测判成「动画帧」无限等（四笔卡顿 20s/39s/120s/235s 的真因）。纯逻辑挪去验证器，
        //   那边显式解析 ICCS + isLegalMove 门（可离线自检）。
        if (typeof verifier.rebuildTicketBoards !== "function") {
            return null;
        }
        try {
            return verifier.rebuildTicketBoards(ticket, lastReliable.board);
        } catch (error) {
            return null;
        }
    }

    /**
     * 清掉「幻影 / 重复子」：同一枚子在观测里**占了两格**（棋子不会分身）。
     *
     * ★ 不变量（硬）：一局里任何一方的子数**只会减少或不变**（吃子少一枚；象棋没有升变/投放）。
     *   所以「观测里某枚子的个数 > 参照盘面里的个数」⇒ 多出来的那些格**一定**是错觉
     *   （模型的检测框偏了一格／重复框）。
     *
     * 判据（收得很紧）：
     *   · 参照盘面说那格是**空**（参照那边也有子 ⇒ 不是多出来的那格）；
     *   · 观测在那格有子 X，且观测里 X 的个数**多于**参照盘面；
     *   · 候选格数 == 多出来的份数（多一格都不清，宁可交给恢复流程）。
     *
     * 真机现场（2026-09-20，K40，now14 18:20:53）：模型把 (0,0) 那枚黑車的框偏到了 (1,0)
     * ⇒ 观测 `rrbakab1r/…` vs 期望 `r1bakab1r/…` ⇒ 确认连刷 5 次
     * 「还有 N 个未知格（且看得清的格子对不上这一手）」、轮次也推不出来 → 恢复（白烧 ~20s）。
     *
     * @param chars     观测盘面（会被**就地**修改）
     * @param reference 参照盘面（确认阶段＝票据的预期局面；其余时候＝上一张可靠基准）
     * @returns 清掉的格数
     */
    function clearPhantoms(chars, reference, why) {
        if (!chars || !reference) {
            return 0;
        }
        const obsCount = {};
        const refCount = {};
        const keepCount = {};   // 参照那格有子、观测也还是**同一个字**的份数（"原地没动"）
        for (let i = 0; i < 90; i++) {
            // ⚠️ 这里**不排除我方落点格**：预期那边落点是有子的、观测这边可能读成别的字，
            //   单边排除会把两边计数算成不平衡 ⇒ 凭空多出 1 枚 ⇒ 误清我方那枚子的**起点**
            //   （2026-09-20 自检直接挂住，就是这条）。落点保护只加在下面的清理动作上。
            const a = chars[i];
            // ⚠️ '?' 是「看不清」，**不是棋子**：既不参与计数，也不许被这里当成幻影清掉
            //   （把它清成空就是「拿看不清当没子」—— 本文件里已经有好几处教训了）。
            if (typeof a === "string" && a && a !== "?") {
                obsCount[a] = (obsCount[a] || 0) + 1;
            }
            const b = reference[i];
            if (typeof b === "string" && b && b !== "?") {
                refCount[b] = (refCount[b] || 0) + 1;
                if (a === b) {
                    keepCount[b] = (keepCount[b] || 0) + 1;
                }
            }
        }
        const spots = {};
        for (let i = 0; i < 90; i++) {
            const got = chars[i];
            if (!(typeof got === "string" && got && got !== "?")) {
                continue;
            }
            // ⚠️ **我方落点格不许在这里被动**（2026-09-20 真机回归）：
            //   那一格的子是我方刚走过去的（带高亮环），被读错兵种/颜色是**已知模式**，
            //   由「票据预期 + 落点格补齐」那条专门的路救（verifier 的 decorated / 驱动的规则②）。
            //   幻影规则把它清成空之后，那条路反而接不上（空不是「装饰」）⇒ 直接 UNEXPECTED → 恢复。
            if (activeOwnMove && activeOwnMove.destIndex === i) {
                continue;
            }
            const want = reference[i];
            if (want != null && want !== "") {
                continue;   // 参照那边这个格子也有子 ⇒ 不是「多出来的那格」
            }
            if ((obsCount[got] || 0) - (refCount[got] || 0) <= 0) {
                continue;   // 这枚子的个数没变多 ⇒ 与幻影无关
            }
            if (!spots[got]) {
                spots[got] = [];
            }
            spots[got].push(i);
        }
        let cleared = 0;
        const notes = [];
        for (const ch in spots) {
            // ★★ 关键收紧（2026-09-20 真机死循环的根因）：
            //   只有当参照里这枚子的**每一份都还乖乖待在原位**时，「多出来的那一份」才只能是分身。
            //   反例（now20，卡了 19 次「轮次推不出来」）：我方炮(2,3) 被误读成黑卒 ⇒ 卒数 +1，
            //   而对方那枚卒**从 (6,3) 走到了 (6,4)** —— 参照在 (6,3) 的那份已经不在了，
            //   于是「多出来的份」其实对应「那枚卒移动了」，被误判成幻影清掉 ⇒ 盘面少了对方一枚卒
            //   ⇒ 任何合法着法都解释不了 ⇒ 恢复 → 重建基准 → 再撞，循环。
            //   移动过（keep < ref）就整类不动手，交给恢复流程。
            if ((keepCount[ch] || 0) !== (refCount[ch] || 0)) {
                continue;
            }
            const excess = (obsCount[ch] || 0) - (refCount[ch] || 0);
            if (spots[ch].length !== excess) {
                continue;   // 多出来的份数与候选格数对不上 ⇒ 说不准是哪一格，交给恢复
            }
            for (let k = 0; k < spots[ch].length; k++) {
                const index = spots[ch][k];
                // ★ 清之前先问一句「那格真的是敌方那枚子吗？」——真机 now24：(4,2) 的黑炮被连清 **8 次**，
                //   其实是我方子被高亮环读成了敌方兵种（与 (2,3)/(8,5) 同一类误读，只是那两格有
                //   已知事实记录、这格没有）。像素 + OCR 的阵营与观测**相反** ⇒ 说不准，
                //   **不清也不猜**（改成我方那只子会立刻又凑出我方那份的重复计数，只是换一出戏）；
                //   留给恢复流程（它会按整帧重新建基准，几分钟内自愈），但绝不删掉可能是真子的那枚。
                const ev = cellEvidence(index);
                const factSide = (ch === ch.toUpperCase()) ? "RED" : "BLACK";
                const where = "(" + (index % 9) + "," + Math.floor(index / 9) + ")";
                if (ev && ev.hasPiece !== false && (ev.side === "RED" || ev.side === "BLACK") &&
                    ev.side !== factSide) {
                    stats.phantomHeldBack = (stats.phantomHeldBack || 0) + 1;
                    note("幻影候选没敢清：" + where + ch +
                        "，但像素（找色）说那格是" + (ev.side === "RED" ? "红" : "黑") +
                        "（多半是我方子被读成敌色）→ 交给恢复流程");
                    continue;
                }
                // ★ 2026-09-20 晚（真机 now48：`(5,3)r→空` 每帧循环 20+ 次）：
                //   同一格连着 `cfg.phantomAdoptStreak` 帧都「多出来」、而且**那格真有墨迹**，
                //   那就不是误读 —— 是**可靠基准漏收了一枚子**（对方的子走过来了，但那一手没被
                //   推断采纳；基准里这枚子的另一份还乖乖在原位 ⇒ 计数恒定“多一份”）。
                //   每帧清掉它只会让观测永远对不上真棋盘，且这枚子永远进不了基准（它的走子永远推不出来）。
                //   僵持到点就**收编**：写进基准（计数对上），而不是继续清。
                //   ⚠️ 两条保护（都踩过）：
                //     · 证据说那格没墨迹（ev.hasPiece === false）⇒ 模型框漂移的假子 ⇒ 照旧清、绝不清账；
                //     · **收编要正证据**：决定那一帧必须有「那格真有墨迹」（ev.hasPiece === true）。
                //       没有证据的环境（离线夹具 / Rhino）一律维持旧行为 —— 18:20 那个框漂移回归
                //       夹具就是靠这条保护的（它没有 ocrCell 证据，照旧清）。
                if (lastReliable && reference === lastReliable.board) {
                    const hasInk = !!(ev && ev.hasPiece === true);
                    const noInk = !!(ev && ev.hasPiece === false);
                    if (noInk) {
                        delete phantomStreak[index];        // 假子：照旧清
                    } else {
                        const key = index + ":" + ch;
                        const prev = (phantomStreak[index] && phantomStreak[index].key === key)
                            ? phantomStreak[index].count : 0;
                        if (prev + 1 >= cfg.phantomAdoptStreak && hasInk) {
                            delete phantomStreak[index];
                            // ★ 收编也是「改基准」：先登记旧基准（它可能是对方应手的落点，也可能不是 ——
                            //   是不是，交给 sideFor 的差分追踪对账，见 recordBaselineRevision）
                            recordBaselineRevision("幻影收编 " + where + ch);
                            const adopted = lastReliable.board.slice();
                            adopted[index] = ch;
                            lastReliable = { board: adopted, side: lastReliable.side };
                            stats.phantomAdopted = (stats.phantomAdopted || 0) + 1;
                            note("幻影僵持 " + (prev + 1) + " 帧（" + where + ch +
                                " 连着多出来、且那格真有墨迹）→ 判为可靠基准漏收这枚子，收编进基准");
                            continue;   // 不清：这是真子
                        }
                        phantomStreak[index] = { key: key, count: prev + 1 };
                    }
                }
                // ★ 2026-09-24（K40 实拍：用户「自己被将军了 还在走其他的」的根因）——
                //   对方那枚**贴着我方帅将军的黑车**（观测 (3,9)r；它刚从别处走到底线）被这里
                //   当成「重复子」**连着清了 7 分钟**（日志：`清掉幻影（重复子）1 格（(3,9)r→空）`
                //   每帧一次）—— 驱动眼里的盘面**永远没有将军** ⇒ 引擎一直走别的
                //   （真实棋局里全都不合法，游戏全部拒收）⇒ 面板卡在「轮次推不出来/RECOVERING」，
                //   用户看着干着急。为什么既有护栏没拦住：收编（phantomAdoptStreak）要求
                //   「那格真有墨迹」的证据，而单格证据对那一格一直给不出/给的是空（限频 +
                //   黑车细笔画密度压线）⇒ 永远走「照旧清」。
                //   这里补的是**不依赖证据的硬判据**：清一枚子**不许改变我方帅的被将状态**
                //   （含将帅照面）—— 拿掉前后不一样 ⇒ 它可能就是将军的矛（或挡将的盾）⇒
                //   不清，留给恢复/收编流程。宁可多花几秒，也不能把「正在被将」从盘面上删掉。
                const myRedSide = machine.mySide() !== "b";
                const checkBefore = rulesModule.isInCheck(chars, myRedSide);
                chars[index] = null;
                const checkAfter = rulesModule.isInCheck(chars, myRedSide);
                chars[index] = ch;
                if (checkBefore !== checkAfter) {
                    stats.phantomHeldByCheck += 1;
                    note("幻影候选没敢清：" + where + ch + "—— 拿掉它会" +
                        (checkBefore ? "解除" : "造成") +
                        "我方被将（将军的矛 / 挡将的盾都可能就是它）→ 留给恢复流程");
                    continue;
                }
                chars[index] = null;
                cleared += 1;
                if (notes.length < 4) {
                    notes.push(where + ch + "→空");
                }
            }
        }
        // ★ 僵持表只认「连续」：这一轮没有再作为候选出现的格子，把它的记录清掉。
        const seenNow = {};
        for (const ch2 in spots) {
            for (let k = 0; k < spots[ch2].length; k++) {
                seenNow[spots[ch2][k]] = true;
            }
        }
        for (const idx in phantomStreak) {
            if (!seenNow[idx]) {
                delete phantomStreak[idx];
            }
        }
        if (cleared > 0) {
            stats.phantomCleared = (stats.phantomCleared || 0) + cleared;
            note("清掉幻影（重复子）" + cleared + " 格（" + notes.join("；") + "）——" +
                (why || "同一枚子在观测里占了两格") + "；棋子不会分身");
        }
        return cleared;
    }

    /**
     * 已知事实表里那一格的字（不查可靠基准）。
     * 用途：颜色翻转的硬闸（见 applyOcrBoardFixes）—— 已知事实是「票据 + 棋规唯一推出来」的，
     * 它说这格是我方炮，那它就是我方炮，不允许 OCR/像素把它翻成黑炮。
     * 返回 null 表示那格没有已知事实（空子的记录不算：子走了记录就该失效，颜色得重新看证据）。
     */
    function knownFactCharAt(index) {
        return knownFacts.charAt(index);
    }

    /**
     * 用**全盘 OCR**改正「同色不同兵种」的误读（模型把炮认成兵/卒那类）。
     *
     * 为什么必要时（2026-09-19，真机实拍）：模型把 (2,3) 的我方炮读成红兵、(4,6) 的黑炮读成黑卒 ——
     * 同色、只错兵种，于是驱动手里“黑方只剩一枚炮”、我方那枚炮变成兵，轮次推断永远解释不了。
     * 而 OCR 读的是**字形**（车马炮砲士相象帅将仕兵卒），那两格它读得清清楚楚。
     *
     * 只改三种情形（都是有证据的，不是猜）：
     *   ① 两边**同色**但兵种不同 ⇒ 取 OCR 的字形（这就是模型那个已知弱点）
     *   ② 模型空、OCR 有字 ⇒ 补上（漏检）
     *   ③ 模型有字、OCR 没读到 ⇒ **不动**（OCR 会被悬浮窗/水印挡佳，不能拿“没读到”去清子）
     */
    function applyOcrBoardFixes(chars, result) {
        if (typeof opts.ocrBoard !== "function" || !chars) {
            return 0;
        }        const at = nowMono();
        if (lastOcrBoardAt > 0 && at - lastOcrBoardAt < cfg.ocrBoardCooldownMs) {
            return 0;
        }
        lastOcrBoardAt = at;
        let board = null;
        try {
            board = opts.ocrBoard();
        } catch (error) {
            board = null;
        }
        stats.ocrBoardChecks += 1;
        if (!board || !board.ok || !board.chars) {
            // ⚠️ 失败也要留痕：不然「没调用」「调用了但不可用」「调用了但无可改正」在日志里长得一样
            //   （2026-09-19 为此多跑了一轮真机）。
            note("全盘 OCR 不可用：" + ((board && board.reason) || "没有返回值"));
            return 0;
        }
        let fixed = 0;
        let baselineFixed = 0;
        /** 等「连续帧一致」的格数 / 被分数、颜色、非法字形驳回的格数 */
        let pending = 0;
        let blocked = 0;
        const notes = [];
        const pendingNotes = [];
        const flipBlockedNotes = [];
        // 帧标识：面板给了 `at`（命中缓存时它不变）就用它；没给（自检/夹具）用「本次询问」当一帧
        frameConsensus.beginFrame(board.at);
        /**
         * 「同一格连续 N 帧说同一个字」闸门（实现见 frame-consensus.js：
         * 同帧重复问不刷计数；矛盾票标记 unstable 并重置）。
         */
        const consensus = function (index, ch, needed) {
            return frameConsensus.vote(index, ch, needed);
        };
        /** 同步改正可靠基准那一格（OCR 是字形证据；基准错了的话，票据期望也跟着错） */
        const fixBaseline = function (index, ch) {
            if (!lastReliable || !lastReliable.board || lastReliable.board[index] === ch) {
                return;
            }
            const board2 = lastReliable.board.slice();
            board2[index] = ch;
            lastReliable = { board: board2, side: lastReliable.side };
            trackFix(board2, lastReliable.side, { reason: "ocr-fix" });
            baselineFixed += 1;
        };
        for (let i = 0; i < 90; i++) {
            const mine = chars[i] && chars[i] !== "?" ? String(chars[i]) : null;
            const raw = board.chars[i] ? String(board.chars[i]) : null;
            // ① 不许把 `?` / 非法字形写进盘面与基准（2026-09-20：账本漂移的直接来源之一）
            const theirs = raw && PIECE_CHAR_RE.test(raw) ? raw : null;
            if (!theirs) {
                if (raw) {
                    blocked += 1;   // OCR 回了 `?`/垃圾字：不动，只计数
                }
                continue;            // 没读到 ⇒ 不动（会被悬浮窗/水印挡住，不能拿「没读到」去清子）
            }
            // ② 分数闸门：低分字形只记录、不采纳（面板按 minScore 滤过一道，这是第二道）
            const score = board.scores && board.scores[i] != null ? Number(board.scores[i]) : null;
            if (score != null && !(score >= cfg.ocrPatchMinScore)) {
                blocked += 1;
                continue;
            }
            // 兵种与颜色分开看：兵种取字形、颜色取**像素**（board-scan 已经把两者合成进 chars[i]）
            const missing = mine == null;                     // ① 模型漏检、OCR 看到了字 ⇒ 补上
            const classDiffers = !missing && mine.toLowerCase() !== theirs.toLowerCase();
            const colourDiffers = !missing && !classDiffers && mine !== theirs;
            if (!missing && !classDiffers && !colourDiffers) {
                continue;            // 完全一致：没什么要改的
            }
            // ⚠️ 这两个 const 必须先声明：下面的「我方刚落的子」早退块要用（真机 now22 铁证：
            //    声明在后面 ⇒ 一命中就抛 `from is not initialized`（TDZ）⇒ `applyOcrBoardFixes`
            //    整体抛出 ⇒ 整轮作废 ⇒ 那格的误读留到下一帧 ⇒ 又「轮次推不出来」⇒ 恢复 —— 死循环）。
            const want = theirs;
            const from = missing ? "空" : mine;
            // ★ 往**空位**补子必须有正面墨迹证据（真机 now29 坏轮，K40）：OCR 把「炮」补到空格
            //   (4,7) 上（那格其实啥也没有）⇒ 与 (7,7) 的真炮来回打架（`(4,7)空→C (7,7)?→空`）⇒
            //   任何合法着法都解释不了 ⇒ 一轮刷 15 次「推不出来」。找色说那格没墨迹就一律驳回。
            if (missing) {
                const ink = cellEvidence(i);
                if (ink && ink.hasPiece === false) {
                    blocked += 1;
                    stats.ocrEmptyBlocked = (stats.ocrEmptyBlocked || 0) + 1;
                    if (flipBlockedNotes.length < 4) {
                        flipBlockedNotes.push("(" + (i % 9) + "," + Math.floor(i / 9) + ")空→" +
                            want + "（找色说那格没墨迹 ⇒ 驳回）");
                    }
                    continue;
                }
            }
            // ★ 我方**正要落到**的那一格：OCR 的任何改正都不许动它（2026-09-20 真机第二次踩到）：
            //   点击发出 → 确认成功之间，落点格带着高亮环，模型/像素一会儿说它是黑炮、一会儿说是卒
            //   （日志 17:54:52 落子 → 17:54:55 `(2,3)C→c`；17:55:12 基准直接被写成 `p`）。
            //   可那一格是什么子**驱动自己就知道**：就是起点那枚子（观察层在点击窗口里已经按它补齐）。
            if (activeOwnMove && activeOwnMove.destIndex === i) {
                blocked += 1;
                stats.ocrFlipBlocked = (stats.ocrFlipBlocked || 0) + 1;
                if (flipBlockedNotes.length < 4) {
                    flipBlockedNotes.push("(" + (i % 9) + "," + Math.floor(i / 9) + ")" + from +
                        "→" + want + "（我方刚落的子）");
                }
                continue;
            }
            const flip = !missing && (mine === mine.toUpperCase()) !== (want === want.toUpperCase());
            // ★ 颜色翻转的**硬闸**（2026-09-20 真机铁证）：棋子一局里**不会改颜色**，
            //   所以「同一格、兵种没变、只是像素说是另一个颜色」时，颜色早就由**已知事实**定死了——
            //   再拿 OCR+像素去把它翻过来就是改坏账本。
            //   现场（日志 17:49:10~33）：我方刚落下的炮 (2,2)（落点带高亮环，像素判成黑）
            //   ⇒ 三帧共识把基准写成黑炮 ⇒ 之后每一帧都对不上、白烧一次恢复。
            //   注意闸门只看**已知事实表**（票据/棋规推出来的那些格），不看可靠基准：
            //   基准是“上一手的样子”，而「有子刚从别处走到这一格」时基准当然还是旧值。
            if (flip) {
                const settledFact = knownFactCharAt(i);
                if (settledFact) {
                    blocked += 1;
                    stats.ocrFlipBlocked = (stats.ocrFlipBlocked || 0) + 1;
                    if (flipBlockedNotes.length < 4) {
                        flipBlockedNotes.push("(" + (i % 9) + "," + Math.floor(i / 9) + ")" + from +
                            "→" + want + "（已知事实是 " + settledFact + "）");
                    }
                    continue;
                }
            }
            // ③ 与像素（找色）结论不冲突：翻颜色时必须由像素给出**决定性**结论，否则不动
            if (flip) {
                const side = board.sides ? board.sides[i] : null;
                const wantRed = want === want.toUpperCase();
                if ((side !== "RED" && side !== "BLACK") || (side === "RED") !== wantRed) {
                    blocked += 1;
                    continue;
                }
            }
            const needed = flip ? cfg.ocrPatchFlipFrames : cfg.ocrPatchFrames;
            /**
             * ① **本帧观测**立即按证据改：它只活这一帧，专给「同帧的验证/轮次推断」用
             *    （不然模型把 (4,4) 炮读成卒这种格会把整手的确认拖成 UNEXPECTED）。
             *
             * ⚠️ **翻色是例外**（2026-09-20 真机 now39，白烧 25% 的整轮时间）：
             *   现场 (4,6) 那枚棋被游戏画了高亮环 ⇒ 模型逐帧在 c / C / ? 之间跳，而全盘 OCR 的
             *   像素颜色**也跟着跳**（先 `c→C（第 1/3 帧）`，一会儿又 `C→c（第 1/3 帧）`）。
             *   老代码把「本帧立即改」也用在翻色上 ⇒ 我们自己每帧再帮着翻一次 ⇒ 差分每帧都看到
             *   「变了」⇒ 任何着法都解释不了 ⇒ 每 ~5s 一次「轮次推不出来 → 恢复」。
             *   翻色本来就写着「要 3 帧共识」，那就**本帧也一起等共识**（与基准一致时照旧立即生效）。
             *   兵种改正（炮↔卒）与补子不受影响 —— 它们才是「同帧验证」真正需要的。
             */
            const agreesWithBaseline = !!(lastReliable && lastReliable.board &&
                lastReliable.board[i] === want);
            const gate = agreesWithBaseline
                ? { ok: true, frames: needed, needed: needed, unstable: false }
                : consensus(i, want, needed);
            if (flip && !gate.ok && gate.unstable) {
                // 本帧**不改**：把这一格留给差分当「没变」，等连续两帧同票再说
                stats.colorFlipHeld = (stats.colorFlipHeld || 0) + 1;
            } else {
                chars[i] = want;
            }
            fixed += 1;
            notes.push("(" + (i % 9) + "," + Math.floor(i / 9) + ")" + from + "→" + want +
                "(OCR " + (board.glyphs[i] || theirs) + ")");
            // ② **基准 / 已知事实**要多帧共识（那才是账本，写进去就跨帧生效）
            /**
             * ★ 「OCR 说的与**上一张可靠基准**完全一致（兵种 + 颜色）」⇒ 不必等共识。
             *
             * 共识闸门防的是「拿一帧 OCR 的读数去改账本」；可这里 OCR 说的**正是账本上已有的值**，
             * 它要改的只是「这一帧读错的那一格」—— 那不是新事实，是把帧纠正回来。
             *
             * 真机（2026-09-20，K40，日志 18:15:40~53）：我方炮那格被高亮环读成黑卒 `p`，而全盘 OCR
             * 每 6s 才跑一次 ⇒ 「翻色要 3 帧」≈ 18s 才落地；可轮次看门狗 4s 就先触发了 ⇒
             * 每帧都「轮次推不出来」→ 恢复（白烧十几秒），一天里反复出现。
             */
            if (!gate.ok) {
                pending += 1;
                if (pendingNotes.length < 4) {
                    pendingNotes.push("(" + (i % 9) + "," + Math.floor(i / 9) + ")" + from + "→" + want +
                        "（第 " + gate.frames + "/" + gate.needed + " 帧）");
                }
                continue;
            }
            // ★ 登记成已知事实：OCR 一次要 1.2s，限频下总有不跑 OCR 的帧，
            //   那些帧会把这一格读回原样 ⇒ 基准与观测在两套盘面之间来回跳，永远解释不通
            //   （2026-09-19 真机踩到）。登记后每帧都按这条事实补 ✔
            registerPatch({ index: i, char: want },
                missing ? "全盘 OCR 证据（模型漏检）"
                    : (classDiffers ? "全盘 OCR 证据（兵种取字形）" : "全盘 OCR 证据（颜色取像素）"));
            fixBaseline(i, want);
        }
        if (fixed > 0) {
            stats.ocrBoardFixes += fixed;
            // 基准也被改过 ⇒ 标记一下：下次验证会用「当前基准 + 我方这手」重建票据的盘面
            // （否则票据里那张旧期望值会把这两个改正当成「无法解释的局面」）。
            if (baselineFixed > 0) {
                baselineCorrectedAt = nowMono();
                notes.push("基准改正 " + baselineFixed + " 格");
            }
            note("全盘 OCR 改正 " + fixed + " 格：" + notes.slice(0, 6).join("；") +
                (notes.length > 6 ? " …" : "") + "（OCR 读到 " + board.hits + " 格有字）");
            if (result) {
                result.ocrBoardFixes = fixed;
            }
        } else if (at - lastOcrBoardNoteAt > 30000) {
            lastOcrBoardNoteAt = at;
            note("全盘 OCR 对账：无可改正（OCR 读到 " + board.hits + " 格有字）");
        }
        stats.ocrBoardPending += pending;
        stats.ocrBoardBlocked += blocked;
        // 待定/驳回也要留痕，否则「没调用」「调了但无可改正」「调了但全被判待定」在日志里长得一样
        const gateText = "全盘 OCR 待定 " + pending + " 格／驳回 " + blocked + " 格（本帧已用，连续 " +
            cfg.ocrPatchFrames + " 帧一致才写基准，翻色要 " + cfg.ocrPatchFlipFrames + " 帧）" +
            (pendingNotes.length ? "：" + pendingNotes.join("；") : "") +
            (flipBlockedNotes.length ? "｜颜色翻转被已知事实驳回：" + flipBlockedNotes.join("；") : "");
        if ((pending > 0 || blocked > 0) &&
            (gateText !== lastOcrGateText || at - lastOcrGateNoteAt > 30000)) {
            lastOcrGateText = gateText;
            lastOcrGateNoteAt = at;
            note(gateText);
        }
        return fixed;
    }

    /**
     * 重拍/直接补上都算「这格被证实了」⇒ 连空计数与催游戏次数都清零。
     * ⚠️ 不清零的后果（2026-09-19 自己踩的）：第 2 次催游戏（最近任务切出再切回）之后
     * 棋子真的重新画出来了、单格重拍也找回了那一格，但计数还在涨 ⇒ 又催了第 3 次，
     * 把一条**真实**的已知事实当假的丢掉，还把可靠基准一起改错。
     */
    function resetStuckStreak(patch) {
        if (!patch) {
            return;
        }
        patch.emptyStreak = 0;
        patch.nudges = 0;
        if (patch.index != null) {
            delete stuckNudgeAt[patch.index];
        }
    }

    function reprobeSuspects(suspects, result) {
        if (!suspects || suspects.length === 0 || !result || !result.chars) {
            return 0;
        }
        if (cfg.reprobeConfidence == null || cfg.reprobeConfidence <= 0) {
            return 0;
        }
        const now = nowMono();
        const pending = [];
        for (let i = 0; i < suspects.length; i++) {
            const lastAt = lastReprobeAt[suspects[i].index];
            if (lastAt != null && now - lastAt < cfg.reprobeCooldownMs) {
                continue;
            }
            pending.push(suspects[i]);
        }
        if (pending.length === 0) {
            return 0;
        }
        let probe = null;
        try {
            probe = opts.recognize({
                grid: grid,
                orientation: cfg.orientation,
                confidence: cfg.reprobeConfidence,
                minScore: cfg.reprobeMinScore,
                timeoutMs: 1200
            });
        } catch (error) {
            probe = null;
        }
        stats.reprobes += 1;
        for (let i = 0; i < pending.length; i++) {
            lastReprobeAt[pending[i].index] = now;
        }
        if (!probe || !probe.ok || !probe.chars) {
            return 0;
        }
        let filled = 0;
        for (let i = 0; i < pending.length; i++) {
            const patch = pending[i];
            const got = probe.chars[patch.index];
            if (got === "?") {
                // 有框但认不出 ⇒ 那格**不是空的**，已知事实说了是什么就补回去
                result.chars[patch.index] = patch.char;
                filled += 1;
                resetStuckStreak(patch);
                continue;
            }
            if (typeof got !== "string" || !/^[A-Za-z]$/.test(got)) {
                continue;   // 重拍也说空 ⇒ 维持未知，下一轮再试
            }
            const sameColor = (got === got.toUpperCase()) === (patch.char === patch.char.toUpperCase());
            if (!sameColor) {
                // 不同色 ⇒ 已知事实可能已过期（子真被吃了）⇒ 不动，交给后续观察/恢复
                continue;
            }
            result.chars[patch.index] = got;
            filled += 1;
            resetStuckStreak(patch);
        }
        if (filled > 0) {
            stats.reprobeFilled += filled;
            note("单格重拍找回 " + filled + " 格（降阈值重认：" + pending.map(function (p) {
                return "(" + (p.index % 9) + "," + Math.floor(p.index / 9) + ")";
            }).join(" ") + "）");
        } else {
            // ⚠️ 失败也要留痕：不然「没触发」与「触发了但没找回」在日志里长得一模一样
            //（第一版就是这么被误导的：跑了 4 轮都以为没接上）。
            const reads = [];
            for (let i = 0; i < pending.length; i++) {
                const one = probe.chars[pending[i].index];
                reads.push("(" + (pending[i].index % 9) + "," + Math.floor(pending[i].index / 9) + ")=" +
                    (one === "?" ? "?" : (one || "空")) + "（已知事实 " + pending[i].char + "）");
            }
            note("单格重拍没找回（降阈值重认仍为 " + reads.join(" ") + "）");
        }
        return filled;
    }

    /**
     * 盘面 → 紧凑文本（FEN 风格：空位用数字压缩，未知格写 '?'）。
     * ⚠️ 不能用「只拼字符」的写法：那样丢掉了空格子，**看不出列号**，诊断等于白打
     * （真机踩过：想把「我方落子+对方应手」的判错盘面读出来，结果两行都没列号，只能重跑）。
     */
    function boardText(chars) {

        if (!chars) {
            return "-";
        }
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

    /** 截一帧并识别。复用已校准几何（700ms 的定位不必每轮都做）。 */
    function observeOnce(options) {
        const started = nowMono();
        const request = {
            grid: options && options.relocate ? null : grid,
            orientation: cfg.orientation,
            confidence: cfg.confidence,
            timeoutMs: 400
        };
        if (cfg.regionRatio) {
            request.regionRatio = cfg.regionRatio;
        }
        // ★ 观察帧数按状态分级（2026-09-21 提速·方案第 4 条）：等待对方（WAIT_TURN）只用 1 帧 ——
        //   那时我们只是「盯画面有没有变」，新解释本来就要跨观测佐证（轮次待佐证），1 帧足够；
        //   出手前/确认/恢复仍走 observeFrames（2 帧），别动关键判断的帧数。
        if (cfg.observeFramesByState === true && !(options && options.forVerify)) {
            let stateNow = null;
            try {
                stateNow = machine.state ? machine.state() : null;
            } catch (error) {
                stateNow = null;
            }
            if (stateNow === machineModule.STATE.WAIT_TURN) {
                request.frames = 1;
            }
        }
        if (forceFreshReadOnce) {
            // 杀棋复核：这一次观察跳过差分缓存、全盘新鲜重读（面板侧看 options.freshRead）
            request.freshRead = true;
        }
        stats.observations += 1;
        coveredFilledIndexes = [];   // 本帧「被盖住」的格由下面的填充环节登记（见里面 push）
        let result;
        try {
            result = recognizeMerged(request);
        } catch (error) {
            stats.errors += 1;
            stats.lastError = String(error && error.message ? error.message : error);
            forceFreshReadOnce = false;
            return { ok: false, reason: "识别异常：" + stats.lastError };
        }
        forceFreshReadOnce = false;   // 只对这一次观察生效（recognizeMerged 里两帧合并都会带 freshRead）
        stats.lastObserveMs = nowMono() - started;
        lastResult = result;
        if (!result || !result.ok) {
            stats.unrecognized += 1;
            return { ok: false, reason: (result && result.reason) ? result.reason : "识别失败" };
        }
        // 识别时几何被微调过（按检测框对齐）→ 记下来，让后面的点击用同一套几何，
        // 否则会出现「按 A 几何认棋盘、按 B 几何点坐标」的错位。
        if (result.grid && grid &&
            (result.grid.originX !== grid.originX || result.grid.originY !== grid.originY)) {
            const dx = Math.round(result.grid.originX - grid.originX);
            const dy = Math.round(result.grid.originY - grid.originY);
            grid = result.grid;
            note("几何按检测框微调：原点偏移 (" + dx + "," + dy + ")（未知格 " +
                (result.stats && result.stats.autoAlign ? result.stats.autoAlign.unknown : "?") + "）");
        }
        // 点棋用「实测框中心」：连它所属的几何版本一起记下（版本不符就退回交点）
        lastCenters = result.centers || null;
        lastCentersVersion = (result.grid && result.grid.version != null)
            ? result.grid.version
            : (grid && grid.version != null ? grid.version : null);
        // 「游戏画花了的格子」→ 按已知事实补齐。
        // 真机现象：游戏给刚落的子画高亮环，那一格会被模型认错（实测：带环的炮读成马），
        // 棋规层再把它降级成 '?'。**我方落子和对方应手的子都会被画环**，所以这是
        // 一张表（最多两格）。不补的话未知格≠0，轮次推断直接放弃 → 卡死在 RECOVERING。
        // 每一格一旦能自己认出来（或被吃/换子）就自动从表里去掉。
        // ⚠️ **先留一份模型读到的原始观测**（§12）：后面的补丁/补脑/纠色会就地改 `result.chars`，
        // 不留原始读数就分不清「模型看到了什么」与「我们按规则改成了什么」。
        // 诊断与审核只看 rawChars，行动仍看修正后的盘面（两者都必须能查到）。
        const rawChars = result.chars ? result.chars.slice() : null;
        let patchedNow = null;
        let unknownCells = result.unknownCells == null ? 0 : result.unknownCells;
        /** 「已知事实说有子，模型却整格读成空」的可疑格 → 交给单格重拍（见 reprobeSuspects） */
        const suspects = [];
        const facts = knownFacts.list();
        if (facts.length > 0 && result.chars) {
            const keep = [];
            for (let i = 0; i < facts.length; i++) {
                const patch = facts[i];
                if (patch == null || patch.index == null) {
                    continue;
                }
                const got = result.chars[patch.index];
                // ⚠️ 这里**不看周期**（2026-09-14 真机事故，见 doVerify 里的说明）：
                // 以前是「上一手的记录一律作废」，结果是「对方刚落子的那格」在我方一确认就失效
                // → 那格读成空时没人补 → 每帧都解释不了 → 白等 54 秒。
                // 现在改成按**棋子是否离开**作废（dropPatchesAt）：能自己认出来/变成别的同色子
                // 就留着（装饰会反复发作），真被吃成异色子则丢掉（不能把吃子藏起来）。
                // ⚠️ **票据优先**：确认阶段带着「预期落子后局面」时，如果预期说这一格已经**空了**
                //    （= 我方这手正是从这一格走出去的），那这个"补齐"就是跟票据对着干。
                //    真机实测后果（2026-09-14，K40，b2c2 确认成功后走 c2c6）：(2,7) 被补回旧字 →
                //    整帧看起来跟基准一模一样 → 确认判 UNCHANGED → 重试/恢复，
                //    而**棋其实已经走成了**（事后截图：红炮确实在 (2,3)，游戏 4 步）。
                //    所以这种记录不但不能补，还要从表里丢掉（那一格的子真的走了，不会再回来）。
                if (options && options.expect) {
                    const want = options.expect[patch.index];
                    if (want === null || want === "" || want === undefined) {
                        continue;
                    }
                }
                // ★ 空网格记录（途经格，见 addEmptyPatch）：读到「空」= 与已知事实**一致**，绝不能
                //   像下面那样翻成 '?'。翻了就等于凭空造未知格：K40 实测每帧差 1~3 格
                //   （(2,4)(2,5) 两个途经格被翻成 '?'）→ `sideFor` 放弃 → 「轮次未知」空转 60 秒。
                if (patch.char == null) {
                    if (got === "?" || got === "" || got === null || got === undefined) {
                        if (got === "?") {
                            result.chars[patch.index] = null;
                            unknownCells = Math.max(0, unknownCells - 1);
                        }
                        keep.push(patch);
                    }
                    // 读到子 ⇒ 与「这格必为空」冲突（可能对方应手正落在这里）→ 丢掉记录，让观测说话
                    continue;
                }
                if (got === "?" || got === "" || got === null || got === undefined) {
                    // ① 读不出来 / 整格漏检。
                    // ★ 2026-09-19（K40 真机）：区分「模型看到了东西但认不出」与「整格没检测到」——
                    //   `?`（看到了但认不出）在实战里几乎一定是**游戏给刚落/刚动的子画的装饰**
                    //   （高亮环/命中标记），我们的子还在那一格 ⇒ 直接按已知事实补回去。
                    //   不补的后果（实测）：每帧都留 1 个未知格 → `sideFor` 直接放弃 →
                    //   永远「轮次未知，继续观察」→ 整局停住（停在 WAIT_TURN 干等 120 秒）。
                    //   而「读到空」（`""`/null）**不能**直接补：那个子可能真的从这个高亮格走开了
                    //   （2026-09-14 的事故：把空掉的格补回旧子 → 整帧看起来没变）→ 仍交给
                    //   fillKnownFacts 用唯一解释判断。
                    if (got === "?") {
                        result.chars[patch.index] = patch.char;
                        unknownCells = Math.max(0, unknownCells - 1);
                        stats.ownMovePatched += 1;
                        stats.decorFilled = (stats.decorFilled || 0) + 1;
                        resetStuckStreak(patch);
                        keep.push(patch);
                        continue;
                    }
                    if (got !== "?") {
                        // ★ 本周期刚登记的已知事实（对方应手 / 我方落点 / 刚落子的格子）：
                        //   那一格里的子**在我方下一次确认之前不可能再动**（要吃它也得等我方落子），
                        //   所以「读到整格空」只可能是模型漏检，而不是「子走了」。
                        //   真机实测（K40，2026-09-19）：黑炮落在 (4,2)，模型一会儿读 c、一会儿整格读空，
                        //   就是**降阈值重拍（0.12）也读空**（不是阈值问题）→ 每帧差 1 个未知格 →
                        //   `sideFor` 数不齐子力 → 「轮次未知」空转 60 秒。
                        //   ⚠️ 界限用「我方出手序号」（ply），**不能**用确认周期：同一着法会重复确认好几次，
                        //   周期一涨记录立刻沦为「旧记录」（实测：补齐只生效了一帧）。
                        //   ⚠️ 我方一出手（ply 变），记录就不能再当事实 —— 那时子的去留真的可能变
                        //   （见 ④ 支的说明），仍然只敢补 '?'。
                        if (patch.ply != null && patch.ply === moveEpoch) {
                            result.chars[patch.index] = patch.char;
                            unknownCells = Math.max(0, unknownCells - 1);
                            stats.freshEmptyFilled += 1;
                            keep.push(patch);
                            continue;
                        }
                        // ★ 先拿「找色 + OCR」证据（用户提议：棋子定位用找色扫描）——
                        //   这一步能一句话说清「那格到底有没有子」，不再靠催游戏和猜：
                        //   · 找色说没墨迹 ⇒ 真的空了 ⇒ 事实过期，按观测算；
                        //   · 找色说有子（兵种也对得上）⇒ 就是模型漏检 ⇒ 直接补回去。
                        const evidence = cellEvidence(patch.index);
                        if (evidence && evidence.hasPiece === false) {
                            stats.ocrRejected += 1;
                            note("已知事实作废（找色证据是空格）：(" + (patch.index % 9) + "," +
                                Math.floor(patch.index / 9) + ") 期望 " + patch.char +
                                "，找色两边的墨迹密度都低于门槛 → 按观测算");
                            resyncReliableAt(patch);
                            factInvalidated = true;
                            continue;   // 不 keep ⇒ 记录消失，格子保持观测到的空
                        }
                        if (evidence && evidence.hasPiece === true && patch.char &&
                            evidence.cls &&
                            String(evidence.cls).toLowerCase() === String(patch.char).toLowerCase()) {
                            stats.ocrConfirmed += 1;
                            result.chars[patch.index] = evidence.char || patch.char;
                            unknownCells = Math.max(0, unknownCells - 1);
                            resetStuckStreak(patch);
                            keep.push(patch);
                            continue;
                        }
                        // ★ 单格 OCR 检查（2026-09-19，用户提的）：先拿证据，再决定补还是丢。
                        //   读到汉字 ⇒ 那格确实有子（顺便知道兵种与颜色）
                        //   读不到汉字 ⇒ 那格是空的 ⇒ 这条已知事实是错的，丢掉并同步改正可靠基准
                        if (typeof opts.ocrCell === "function") {
                            const looked = ocrCheckPatch(patch);
                            if (looked.state === "match") {
                                result.chars[patch.index] = looked.char || patch.char;
                                unknownCells = Math.max(0, unknownCells - 1);
                                stats.ocrConfirmed += 1;
                                resetStuckStreak(patch);
                                keep.push(patch);
                                continue;
                            }
                            if (looked.state === "empty" || looked.state === "other") {
                                stats.ocrRejected += 1;
                                const why = looked.state === "empty"
                                    ? ("OCR 连续 " + (looked.seen || 2) + " 次都读不到字，那格是空的")
                                    : "OCR 读到 " + looked.glyph + "（与已知事实 " + patch.char + " 不符）";
                                note("已知事实作废：" + why + " → (" + (patch.index % 9) + "," +
                                    Math.floor(patch.index / 9) + ") 按观测算");
                                resyncReliableAt(patch);
                                factInvalidated = true;
                                continue;   // 不 keep ⇒ 记录消失，格子保持观测到的空
                            }
                        }
                        // ★ 先「催游戏」，催不动再「承认观测」（2026-09-19，K40 实拍定位后加的）：
                        //   真机现象不是模型伪影，而是**走子动画卡在中间**：黑炮 1,2→4,2 卡住，
                        //   棋子是隐形的、(1,2) 空了、(4,2) 只剩一个**闪烁的**落点光环 ⇒
                        //   那两格永远读不出子（一会儿 '?'、一会儿空）⇒ 每帧差一格未知 ⇒ 轮次未知空转。
                        //   天天象棋是**人机对战**（电脑应手是瞬间的），动画最多 1 秒 ——
                        //   一连 3 帧（≈10 秒）还读不出来就已经不正常了。
                        //   ⚠️ 只做**安全动作**（重新带前台 / 最近任务切出再切回），绝不点棋盘：
                        //   万一那格真的只是模型漏检，点下去就是走错棋。
                        //   ⚠️ 没有 `opts.nudge`（自检/离线）时**不做任何降级** —— 也无法区分
                        //   「画面卡住」与「模型漏检」（自检里那两条回归用例就是后者），保持原行为。
                        patch.emptyStreak = (patch.emptyStreak || 0) + 1;
                        if (patch.emptyStreak >= cfg.stuckEmptyStreak && typeof opts.nudge === "function") {
                            const at = nowMono();
                            const last = stuckNudgeAt[patch.index] || 0;
                            if (at - last >= cfg.stuckNudgeCooldownMs) {
                                stuckNudgeAt[patch.index] = at;
                                patch.nudges = (patch.nudges || 0) + 1;
                                stats.stuckNudges += 1;
                                note("疑似画面卡住：已知事实 (" + (patch.index % 9) + "," +
                                    Math.floor(patch.index / 9) + ") 已连续 " + patch.emptyStreak +
                                    " 帧读成空 —— 催游戏一下（第 " + patch.nudges + " 次）");
                                try {
                                    opts.nudge({
                                        index: patch.index, char: patch.char,
                                        streak: patch.emptyStreak, attempt: patch.nudges
                                    });
                                } catch (error) {
                                    stats.lastError = String(error && error.message ? error.message : error);
                                }
                                if (patch.nudges > cfg.stuckNudgeLimit) {
                                    /**
                                     * ★ 2026-09-20：丢记录之前先拿**全盘 OCR**复核一次。
                                     *
                                     * 真机现场（v5 局，⑷,7 那枚炮）：模型整格读成空、降阈值重拍也读空，
                                     * 但全盘 OCR 明明认出「炮」（日志：`全盘 OCR 改正 (4,7)空→C(OCR 炮)`）。
                                     * 旧行为到这里就把记录丢掉、还把可靠基准一起改正 ⇒ 盘面上真真
                                     * 丢了一枚子 ⇒ `sideFor` 数不齐 ⇒「轮次推不出来」→ 看门狗 →
                                     * 恢复空转（v5 实测 21 次）。既然 OCR 给了证据，就该**保留**这条事实，
                                     * 只把它当成「模型漏检」。
                                     */
                                    const ocrSaid = ocrCellEvidence(patch.index, patch.char);
                                    if (ocrSaid && patch.char &&
                                        String(ocrSaid).toLowerCase() === String(patch.char).toLowerCase()) {
                                        stats.stuckFactsKeptByOcr = (stats.stuckFactsKeptByOcr || 0) + 1;
                                        note("催游戏 " + patch.nudges + " 次读不出来，但全盘 OCR 确认 (" +
                                            (patch.index % 9) + "," + Math.floor(patch.index / 9) +
                                            ") 就是 " + patch.char + " → **保留**这条已知事实（模型漏检，不是空格）");
                                        resetStuckStreak(patch);
                                        patch.nudges = 0;
                                        result.chars[patch.index] = patch.char;
                                        unknownCells = Math.max(0, unknownCells - 1);
                                        keep.push(patch);
                                        continue;
                                    }
                                    // 催了这么多次仍然读不出来 ⇒ 这条记录无法用画面证实，
                                    // 就按**观测**算（那一格当作空），让轮次推断能继续。
                                    stats.stuckFactsDropped += 1;
                                    note("催游戏 " + patch.nudges + " 次仍然读不出来 → 放弃这条已知事实，" +
                                        "按观测算：(" + (patch.index % 9) + "," + Math.floor(patch.index / 9) +
                                        ") 不再补 " + patch.char);
                                    // ⚠️ 光丢补齐表不够：**可靠基准**是唯一权威，它带着这条无法证实的记录的话，
                                    //   下一帧就会变成「基准里有子、观测里没子，没有任何合法着法解释得了」
                                    //   → 永远 RECOVERING（真机实测：未知格已归零，但停在
                                    //   「既没有合法单步也没有一步+应手能解释」）。
                                    resyncReliableAt(patch);
                                    factInvalidated = true;
                                    continue;   // 不 keep ⇒ 记录消失，格子保持观测到的空
                                }
                            }
                        }
                        result.chars[patch.index] = "?";
                        unknownCells += 1;
                        // 已知事实（票据 + 棋规）说这格有子，模型却整格读成空 ⇒ 可疑。
                        // 不能直接补（2026-09-14：子真走了会把局面改成假的），但值得**降阈值重拍一次**。
                        suspects.push(patch);
                    }
                    keep.push(patch);
                    continue;
                }
                if (got === patch.char) {
                    // ② 自己能认出来了 → 保留（同一手内下一帧可能又被盖住）
                    resetStuckStreak(patch);
                    keep.push(patch);
                    continue;
                }
                // ③ 读成了**别的子**：分两种，都要按已知事实补齐，但判据不同 ——
                //    · **同字异色**（C↔c、P↔p…）= 同一个兵种被读成红黑反了。这台机器上实测会**反复抖**
                //      （2026-09-14 r1.log：`(1,7)?→C` 这类行反复出现）。⚠️ 以前这种情况走 ④ 丢掉记录，
                //      于是**一帧抖动就足够把记录丢掉**，之后同色读错的帧再没人补 →
                //      「轮次推不出来（UNKNOWN）」→ 看门狗 → 整轮停在那儿。
                //      离线回放（diag-patch-life2.js）已复现：一帧 红黑读反 就把 (2,3) 的记录丢掉。
                //    · **同色不同字**（C↔P）= 兵种被游戏装饰读错（真机实测：我方炮吃卒落在卒位被读成红兵）。
                //    异色**且**不同字（C↔n）才是「真的被吃/走开了」→ 必须让它变，
                //    否则会把吃子藏起来，后面算棋就按错盘面走。
                const sameGlyph = got && patch.char && got.length === 1 && patch.char.length === 1 &&
                    got.toLowerCase() === patch.char.toLowerCase();
                const sameColor = got && patch.char &&
                    (got === got.toUpperCase()) === (patch.char === patch.char.toUpperCase());
                if (got && got !== "" && (sameGlyph || sameColor)) {
                    if (sameGlyph && !sameColor) {
                        /**
                         * ★ 同字异色是最危险的一种：可能是「模型红黑读反」，也可能是
                         * **对方同兵种反吃**（真机 2026-09-20 卡死两分钟的真因）。
                         * 拿**像素证据**来定（用户提议的「找色判阵营」）：
                         *   · 证据说阵营与已知事实**相反** ⇒ 事实过期（真被吃了）→ 作废、按观测算；
                         *   · 证据支持事实 / 拿不到证据 ⇒ 照旧按事实补齐。
                         */
                        const hint = cellSideEvidence(patch);
                        const wantSide = (patch.char === String(patch.char).toUpperCase()) ? "RED" : "BLACK";
                        if (hint && hint.side !== wantSide) {
                            stats.sameGlyphFalsified = (stats.sameGlyphFalsified || 0) + 1;
                            note("已知事实作废（找色证据）：(" + (patch.index % 9) + "," +
                                Math.floor(patch.index / 9) + ") 期望 " + patch.char + "（" +
                                (wantSide === "RED" ? "红" : "黑") + "），像素说是" +
                                (hint.side === "RED" ? "红" : "黑") +
                                (hint.cls ? "（OCR 读到 " + hint.cls + "）" : "") +
                                " → 按观测算（多半是对方同兵种反吃）");
                            resyncReliableAt(patch);
                            factInvalidated = true;
                            continue;   // 不 keep ⇒ 记录消失，保持观测到的那个字
                        }
                    }
                    result.chars[patch.index] = patch.char;
                    patchedNow = patch;
                    stats.ownMovePatched += 1;
                    resetStuckStreak(patch);
                    if (sameGlyph && !sameColor) {
                        stats.sameGlyphPatched += 1;   // 计数：真出现「同兵种吃子被掩盖」时能在日志里看见
                    }
                    keep.push(patch);
                    continue;
                }
                // ★★ 异色 **且** 异字（我方炮 C 被读成黑卒 p 这类）：默认就是「真被吃了 / 走开了」（④）。
                //    真机上还有另一种**每局都出现**的常态（now23 铁证，K40）：**落点被游戏画花**
                //    ⇒ 模型把我方的子读成敌方的**另一个兵种** ⇒ 走 ④ 丢掉记录 ⇒ 之后每帧都读成 p
                //    也没人管 ⇒ 差分「变化 0 格」永远不动 ⇒ 每 12 秒白烧一轮 `轮次推不出来 → 恢复 → 重建基准`。
                //    分这个歧义用**第三票：像素（找色）** —— 同帧 OCR 也说那格是 C，只有模型/融合读错：
                //      · 证据站**已知事实**这边 ⇒ 这一帧读错，保住事实（`pixelKeptFacts` 计数会出现在日志）；
                //      · 证据站观测这边 / 拿不到证据 ⇒ 照旧 ④ 丢掉（对方同兵种/异兵种反吃时正是这种）。
                if (got && got !== "" && !sameGlyph && !sameColor) {
                    const hint = cellSideEvidence(patch);
                    const factSide = (patch.char === String(patch.char).toUpperCase()) ? "RED" : "BLACK";
                    if (hint && hint.side === factSide) {
                        result.chars[patch.index] = patch.char;
                        stats.pixelKeptFacts = (stats.pixelKeptFacts || 0) + 1;
                        resetStuckStreak(patch);
                        note("按像素证据保住已知事实：(" + (patch.index % 9) + "," +
                            Math.floor(patch.index / 9) + ") 读成 " + got + "，但找色说是" +
                            (factSide === "RED" ? "红" : "黑") + "（期望 " + patch.char + "）→ 这一帧读错");
                        keep.push(patch);
                        continue;
                    }
                }
                // ④ 变空 / 换成异色且不同字 → 真的被吃或走开了，不再保留
            }
            knownFacts.replace(keep);
        }
        // 还有未知格 → 试试用「已知事实」补齐（唯一解释；见 fillKnownFacts 的说明）
        if (unknownCells > 0 && result.chars) {
            const filled = fillKnownFacts(result.chars);
            // 只有「补完真的没未知格」才算成功：宁可老老实实报未知格，也不要假装补齐（见 fillKnownFacts 的注释）
            if (filled && filled.board && filled.board.indexOf("?") < 0) {
                // ★ F1「与上一张可靠盘面一致」= 这些格是**按基准填回**的 —— 除了模型/找色读不出来，
                //   最典型的原因就是被游戏「上一步」标记压住（真机 K40 2026-09-21 死区：对手的起点/落
                //   点都被压 ⇒ 填回原样 ⇒ 差分看起来「没动」）。登记给 sideFor 的「被盖住的应手」用。
                if (String(filled.how).indexOf("与上一张可靠盘面一致") === 0) {
                    for (let i = 0; i < 90; i++) {
                        if (result.chars[i] === "?" && filled.board[i] !== "?") {
                            coveredFilledIndexes.push(i);
                        }
                    }
                }
                result.chars = filled.board;
                unknownCells = 0;
                stats.knownFactFills += 1;
                if (lastFillText !== filled.how) {
                    lastFillText = filled.how;
                    note("未知格按已知事实补齐：" + filled.how);
                }
            }
        }
        // ★ 还有未知格 → 直接拿「OCR + 找色」证据补（见 evidenceCharFor）。
        //   这是「定位一次 + 找色 + OCR」那条路在现链路里的落地：不看模型检测框，
        //   也不吃红黑读反；判不出来就留着未知（后面还有重拍/恢复兵底）。
        if (unknownCells > 0 && result.chars && typeof opts.ocrCell === "function") {
            let byEvidence = 0;
            let byReliable = 0;
            const detail = [];
            const detail2 = [];
            /** 被「兵种上限」驳回的兜底填充（见下方硬校验；日志用） */
            const rejectedFill = [];
            /** 驳回多帧后改判「空」的格子（日志用） */
            const capEmptied = [];
            // 轮次已经被未知格挡住 ⇒ 这一格的值不能无限等：把兜底门槛降到 2 帧（见 unknownSideStreak）
            const stallFill = unknownSideStreak >= 1;
            const fillNeeded = stallFill
                ? Math.min(cfg.unknownFillFromReliableFrames, 2)
                : cfg.unknownFillFromReliableFrames;
            for (let i = 0; i < 90; i++) {
                if (result.chars[i] !== "?") {
                    unknownStreak[i] = 0;
                    capRejectedStreak[i] = 0;   // 读出来了 ⇒ 驳回计数重来
                    if (stallFilledUntil[i]) {
                        stallFilledUntil[i] = 0;   // 这一格读出来了 ⇒ 放下粘住，采用读数
                    }
                    continue;
                }
                unknownStreak[i] = (unknownStreak[i] || 0) + 1;
                const ch = evidenceCharFor(i);
                if (ch) {
                    result.chars[i] = ch;
                    unknownCells = Math.max(0, unknownCells - 1);
                    byEvidence += 1;
                    unknownStreak[i] = 0;
                    if (detail.length < 6) {
                        detail.push("(" + (i % 9) + "," + Math.floor(i / 9) + ")" + ch);
                    }
                    continue;
                }
                // ★ 顽固未知（OCR+找色也没证据）→ 按上一张可靠盘面兜（未知 = 没变）。
                //   不兜的话就是「一格看不清 ⇒ 整局不动」（真机 v8 实测：FEN 生不出来、每轮抛异常）。
                if ((unknownStreak[i] >= fillNeeded || (stallFilledUntil[i] || 0) > nowMono()) &&
                    lastReliable && lastReliable.board) {
                    const fallback = lastReliable.board[i];
                    // ★ 硬校验：兜的值不许把兵种顶超上限（棋子不会分身）——真机 2026-09-21 now60：
                    //   (7,2) 起点格被「上一步星光标记」压住 20s+ 读不出 ⇒ 兜成旧值 c ⇒ 黑炮变 3 枚
                    //   （不可能盘面）⇒ 幻影去清真落点 (4,2)c、又把它收编 ⇒ 轮次永远停在「轮到对方」。
                    //   填出「不可能」比留着「未知」更坏：未知有看门狗/重锚兜底，错误盘面把推理带沟里。
                    if (typeof fallback === "string" && fallback !== "?" &&
                        countGlyphChars(result.chars, fallback) >= glyphPieceCap(fallback)) {
                        /**
                         * ★★ 驳回之后不能永远堵着（2026-09-21 10:33 K40 死锁，用户报「又卡住找不到格子」）：
                         *   真机现场 (5,7)：我方炮 f2i2 走后，起点格被游戏「上一步」标记压住读不出来；
                         *   兜底想按旧基准填 C，但盘面里红炮已经两枚（另一枚就是它自己走到 (8,7) 那里）
                         *   ⇒ 永远驳回 ⇒ 那一格永远 '?' ⇒ 轮次永远未知 ⇒ 12 分钟没出手。
                         *   判据其实在手里：**基准值放不下 = 基准那一格已被证伪**（棋子不会分身），
                         *   那枚子只能已经离开 ⇒ 连续 `capRejectEmptyFrames` 帧都被驳回就按空处理。
                         *   单帧误读不动手；下一帧真读出字会立刻纠正（与其它兜底同一条路）。
                         */
                        capRejectedStreak[i] = (capRejectedStreak[i] || 0) + 1;
                        if (capRejectedStreak[i] >= (cfg.capRejectEmptyFrames || 3)) {
                            result.chars[i] = null;
                            unknownCells = Math.max(0, unknownCells - 1);
                            unknownStreak[i] = 0;
                            stallFilledUntil[i] = nowMono() + cfg.stallFillHoldMs;
                            coveredFilledIndexes.push(i);
                            // 注意：**不计入 unknownFilledFromReliable**（那个账是「按基准填回」的）——
                            // 这里是按「基准被证伪」推出来的空格，单独计数。
                            stats.unknownFillEmptied = (stats.unknownFillEmptied || 0) + 1;
                            if (capEmptied.length < 4) {
                                capEmptied.push("(" + (i % 9) + "," + Math.floor(i / 9) + ")");
                            }
                        } else if (rejectedFill.length < 4) {
                            rejectedFill.push("(" + (i % 9) + "," + Math.floor(i / 9) + ")=" + fallback);
                        }
                        continue;
                    }
                    capRejectedStreak[i] = 0;
                    result.chars[i] = (fallback === undefined) ? null : fallback;
                    unknownCells = Math.max(0, unknownCells - 1);
                    byReliable += 1;
                    unknownStreak[i] = 0;
                    stallFilledUntil[i] = nowMono() + cfg.stallFillHoldMs;
                    coveredFilledIndexes.push(i);
                    if (detail2.length < 6) {
                        detail2.push("(" + (i % 9) + "," + Math.floor(i / 9) + ")=" +
                            (fallback === undefined || fallback === null ? "空" : fallback));
                    }
                }
            }
            if (byEvidence > 0) {
                stats.evidenceFilled = (stats.evidenceFilled || 0) + byEvidence;
                note("未知格按 OCR+找色证据补齐 " + byEvidence + " 格：" + detail.join(" "));
            }
            if (byReliable > 0) {
                stats.unknownFilledFromReliable = (stats.unknownFilledFromReliable || 0) + byReliable;
                note("顽固未知 " + byReliable + " 格（OCR+找色也没证据）→ 按上一张可靠盘面填（未知=没变）：" +
                    detail2.join(" ") + "（下一帧读出来会立刻纠正）" +
                    (stallFill ? "｜轮次已被未知格挡住，兜底门槛提前到 2 帧" : ""));
            }
            if (rejectedFill.length > 0) {
                stats.unknownFillRejected = (stats.unknownFillRejected || 0) + rejectedFill.length;
                note("兜底被兵种上限驳回 " + rejectedFill.length + " 格（填了就是不可能盘面，留 '?' 等重读）：" +
                    rejectedFill.join(" "));
            }
            if (capEmptied.length > 0) {
                note("兜底连续 " + (cfg.capRejectEmptyFrames || 3) +
                    " 帧被兵种上限驳回 ⇒ 判定那枚子已离开（基准过时），按空处理：" +
                    capEmptied.join(" ") + "（下一帧读出字会立刻纠正）");
            }
        }
        // ★ 全盘 OCR 改正「同色不同兵种」的误读（见 applyOcrBoardFixes）。
        //   放在补齐表之后、`sideFor` 之前：让轮次推断拿到的是**改对后**的盘面。
        if (result.chars && typeof opts.ocrBoard === "function") {
            const fixed = applyOcrBoardFixes(result.chars, result);
            if (fixed > 0) {
                unknownCells = countUnknownChars(result.chars);
                patchedNow = patchedNow || true;
            }
        }
        // ★ 单格重拍（见 reprobeSuspects）：唯一解释补不上，但可疑格是「已知事实说有子」的，
        //   值得换一帧降阈值再看一眼 —— 修的是「模型整格漏检」而不是「补已知事实」。
        if (unknownCells > 0 && suspects.length > 0 && result.chars) {
            if (reprobeSuspects(suspects, result) > 0) {
                let stillUnknown = 0;
                for (let i = 0; i < 90; i++) {
                    if (result.chars[i] === "?") {
                        stillUnknown += 1;
                    }
                }
                unknownCells = stillUnknown;
            }
        }
        // 颜色翻转纠正：游戏给刚落的子/被威胁的子加装饰（高亮环、命中标记），实测会让模型
        // 把**同一个兵种的颜色判反**（例：落点上的红炮被读成红兵/黑卒）。
        //
        // ⚠️ 只改「观测字符与期望字符是同一个字母的不同大小写」的格子，而且**只在确认阶段**
        // 用（options.expect = 票据的「预期落子后局面」）：
        //   · 为什么不看上一张可靠盘面？因为「我的红兵吃掉对方的黑卒」也会表现为同一格
        //     P↔p，拿旧盘面去纠就会**把真实的吃子改掉**；
        //   · 用票据的预期盘面就没有这个问题：纠正之后整盘必须与预期**完全一致**，
        //     否则验证器照样判 UNCHANGED/UNEXPECTED —— 风险由验证器兜住，不是无条件采信。
        if (options && options.expect && result.chars) {
            const expectBoard = options.expect;
            let flips = 0;
            for (let i = 0; i < 90; i++) {
                const got = result.chars[i];
                const want = expectBoard[i];
                if (typeof got !== "string" || typeof want !== "string") {
                    continue;
                }
                if (got !== want && got.toLowerCase() === want.toLowerCase() && /^[A-Za-z]$/.test(got)) {
                    result.chars[i] = want;
                    flips += 1;
                }
            }
            if (flips > 0) {
                stats.colorFlips += flips;
            }
            // ★ 按「棋子怎么走」对账（2026-09-19）：用**棋规**判断哪些格子必定是读错了，直接改正。
            //   跟「容差 N 格」那套的区别：这里的每一条都是**一步之内不可能发生的事**，
            //   所以是「按规则推出真相」，不是「容忍差异」。
            //   ① 颜色不可能变：同字异色（P↔p）只能是模型红黑读反 ⇒ 按期望改回（上面那段已做）
            //   ② **落点上一定是我方走过去的那个子**（棋规唯一）：观测在落点读到同色但别的兵种，
            //      （例：我方炮吃卒落在兵位，被游戏装饰读成红兵）⇒ 按期望改回
            //   ③ **直线移动的途经格**内容由棋规唯一决定（炮吃子 ⇒ 中间恰有一个炮架；否则全空）：
            //      期望为空的途经格观测到任何东西 ⇒ 按期望置空（游戏会把走子轨迹画在中途）
            //   其余格子一律不动 —— 那些才是「真的变了」，交给验证器判。
            if (options.ticket && verifier.pathIndexesBetween) {
                let dest = -1;
                let path = null;
                try {
                    dest = verifier.destinationIndex(options.ticket);
                } catch (error) {
                    dest = -1;
                }
                try {
                    path = verifier.pathIndexesBetween(options.ticket);
                } catch (error) {
                    path = null;
                }
                let byRule = 0;
                const notes = [];
                for (let i = 0; i < 90; i++) {
                    const got = result.chars[i];
                    const want = expectBoard[i];
                    if (got === want) {
                        continue;
                    }
                    const sameColor = typeof got === "string" && typeof want === "string" && got && want &&
                        (got === got.toUpperCase()) === (want === want.toUpperCase());
                    // ② 落点：棋规决定那里必是我方走过去的那个子
                    if (i === dest && want && typeof got === "string" && got && sameColor) {
                        result.chars[i] = want;
                        byRule += 1;
                        notes.push("落点(" + (i % 9) + "," + Math.floor(i / 9) + ")" + got + "→" + want);
                        continue;
                    }
                    // ③ 途经格：期望为空而观测有东西（轨迹/装饰）
                    if (path && path[i] != null && (want === null || want === "") && got) {
                        result.chars[i] = null;
                        byRule += 1;
                        notes.push("途经(" + (i % 9) + "," + Math.floor(i / 9) + ")" + got + "→空");
                    }
                }
                if (byRule > 0) {
                    stats.ruleFixes = (stats.ruleFixes || 0) + byRule;
                    note("按棋规改正 " + byRule + " 格（" + notes.join("；") +
                        "）——「棋子怎么走」决定的，不是容差");
                }
                // ④ 重复子（幻影）：见 clearPhantoms。确认阶段的参照 = 票据的预期局面（最可信）
                clearPhantoms(result.chars, expectBoard, "与票据的预期局面比，多出来的那份");
            }
        }
        // ★ 重复子（幻影）在这里再清一遍，参照 = **上一张可靠基准**：
        //   确认阶段已经用票据的预期盘面对过账，可**推断路径**（轮次）同样会被幻影咬到
        //   （真机 18:21：`诊断观测 rrbakab1r` vs `诊断基准 r1bakab1r` ⇒ 轮次推不出来 → 恢复）。
        //   不变量是硬的：一局里任何一方的子数**只会减少或不变**（吃子少一枚，象棋没有升变）。
        if (lastReliable && lastReliable.board) {
            clearPhantoms(result.chars, lastReliable.board, "与上一张可靠基准比，多出来的那份");
        }
        // lastBoard 要在**补齐/纠色之后**记：它会被「现在轮到我」拿去当基准，
        // 存的必须是「目前最可信的那张盘」（带 '?' 的原始观测会让基准不干净）。
        lastBoard = result.chars ? result.chars.slice() : null;
        lastRawChars = rawChars;
        // 原始读数与修正后盘面不一致时记一笔（限频）：审核、复现都靠它
        if (rawChars && result.chars && rawChars.join("") !== result.chars.join("")) {
            let diffCount = 0;
            const changed = [];
            for (let i = 0; i < 90; i++) {
                if (rawChars[i] !== result.chars[i]) {
                    diffCount += 1;
                    if (changed.length < 6) {
                        changed.push("(" + (i % 9) + "," + Math.floor(i / 9) + ")" +
                            (rawChars[i] || "空") + "→" + (result.chars[i] || "空"));
                    }
                }
            }
            stats.rawCorrected += 1;
            const text = "原始读数被修正 " + diffCount + " 格：" + changed.join(" ") +
                (diffCount > changed.length ? " …" : "");
            if (lastRawCorrectedText !== text) {
                lastRawCorrectedText = text;
                note(text);
            }
        } else {
            lastRawCorrectedText = "";
        }
        // ★ 一致性收口（2026-09-21 16:0x 真机事故）：前面各种补齐路径各自维护 unknownCells 计数，
        //   任何一条路径少写一格，就会留下「计数说 0、盘面里还留着 '?'」的帧 ⇒ 下游 buildFen 抛
        //   「存在未知格，不能生成 FEN」⇒ 每轮被当异常跳过（面板上只看到轮次未知空转）。
        //   以**盘面本身**为准归一化：未知格 = chars 里 '?' 的个数（唯一事实来源）。
        unknownCells = countUnknownChars(result.chars);
        return {
            ok: true,
            chars: result.chars,
            rawChars: rawChars,
            unknownCells: unknownCells,
            patched: patchedNow,
            coveredFills: coveredFilledIndexes.length,
            conflicts: result.stats && result.stats.conflicts ? result.stats.conflicts : 0,
            // 占用图 + 变化格（2026-09-21 对方应手快路用）：面板 diff 链路每帧都带；全盘/旧链路为 undefined
            occupancy: result.occupancy,
            changed: result.changed,
            layout: result.layout,
            result: result
        };
    }

    /** 标准开局（红先）：第一次观察到的局面就是它时，可以确定轮到红方。 */
    function isStartPosition(board) {
        const start = fenModule.parseFen(fenModule.START_FEN);
        return !!start.ok && legal.boardMatches(board, start.board);
    }

    /**
     * 登记一个「已知事实」格子：本手内那一格一律按这里给的字补齐。
     * 依据都是棋规唯一推出来的事实（票据 / 唯一合法着法），不是猜；那格能自己认出来就自动失效。
     */
    function addPatch(entry, label) {
        if (!entry || entry.index == null || !entry.char) {
            return;
        }
        registerPatch(entry, label);
    }

    /**
     * 登记一个「棋规上必为空」的格子（直线行进的途经格）。
     * char 为空也要能登记：空网格同样会被游戏装饰盖住、被模型读成 '?'，
     * 没人补就永远差一个未知格 → `sideFor` 放弃 → 「轮次未知」空转（K40 实测卡 60 秒）。
     */
    function addEmptyPatch(index, label) {
        registerPatch({ index: index, char: null }, label);
    }

    /** 真正的登记（char 允许为 null = 空网格）。 */
    function registerPatch(entry, label) {
        if (!entry || entry.index == null) {
            return;
        }
        const record = { index: entry.index, char: entry.char, cycle: verifyCycle, ply: moveEpoch };
        // 新记录 = 新开始：连着读空的计数与催过的次数都清零（旧记录可能已经催过好几回了）
        delete stuckNudgeAt[entry.index];
        if (knownFacts.upsert(record)) {
            return;
        }
        note(label + " " + (entry.char == null ? "空" : entry.char) + "（下标 " + entry.index +
            "）按已知事实补齐（那格能自己认出来、或有子从那里离开时自动作废）");
    }

    /**
     * 把「已知事实」作废：那些格子里有子**离开**了（新着法的起点），记录不再成立。
     *
     * 为什么必须有它（2026-09-14 真机，auto35.log）：
     * 补齐表以前靠 verifyCycle 整表作废 —— 我方一确认，周期 +1，连「对方刚落子」的记录一起丢。
     * 结果那个子（带高亮环、被模型读成空）没人补 → 观测比期望只差一格 → 装饰容差枚举出 8 种
     * 结构等价的解释（“那个炮从这一格走到别处、新位置没看见”）→ 不唯一 → 连刷 54 次 → 死等。
     * 反过来，如果永远不作废，一个真的掉了的子会被“补”回棋盘上 —— 所以作废依据必须是
     * 「那个格子上的子确实走了」，而不是“过了几手”。
     */
    function dropPatchesAt(indexes, why) {
        const dropped = knownFacts.drop(indexes, function (patch) {
            // 那格的子已经走了 ⇒ 补正的「连续帧共识」也一并作废，
            // 否则上一段共识会在下一枚子到达时被直接拿来用
            frameConsensus.drop(patch.index);
        });
        if (dropped > 0) {
            note("已知事实作废 " + dropped + " 格（" + why + "）");
        }
    }

    /**
     * 把「刚落的子」所在的格子登记进已知事实表。
     *
     * 游戏会给刚落的子画高亮环，**下一帧起那一格就认不出来**（读成空或别的兵种）。
     * 我方那一格在确认阶段就登记了，但**对方那一格以前没人登记** —— 真机实测的后果（2026-09-14，K40）：
     * 对方 h7e7 之后 (4,2) 一直读成空 → 每帧都「轮次推不出来（UNKNOWN）」→ 看门狗 → RECOVERING，
     * 而我方那手也白走（整轮停在那儿）。这里按刚推出来的着法登记，等那格自己能认出来就自动失效。
     *
     * @param moves   刚推出来的着法（MOVE 一步 / MOVE_PAIR 两步，落点都算「刚落的子」）
     * @param settled 推完这些着法后的可靠盘面（补的字取自它）
     */
    function registerMoveDestinations(moves, settled) {
        const list = moves || [];
        for (let i = 0; i < list.length; i++) {
            const move = list[i];
            if (!move || !move.iccs || !settled) {
                continue;
            }
            const dest = transition.squareIndex(move.iccs, 1);
            if (dest < 0 || !settled[dest]) {
                continue;
            }
            addPatch({ index: dest, char: settled[dest] }, "刚落子的格子（会被画环）");
        }
    }

    /**
     * 恢复之后要「按观测重建基准」吗（用户口径 2026-09-20：**用视觉重新对局面**）。
     *
     * 为什么要有它（真机病根）：一枚**在飞的子**被「单格 OCR 判空」从可靠基准里删掉之后，
     * 它落位时「期望里没这枚子、观测里有」⇒ 永远解释不了 ⇒ 一直在 RECOVERING 里「改正/等」。
     * 旧做法只会拿旧基准 + 一堆已知事实去凑原来那张盘面，越凑越乱；
     * 正确做法是：恢复的时候**丢掉全部已知事实，直接用这一帧的视觉读数当基准**
     *（YOLO 定位 → 找色 → OCR 融合本来就是逐帧重读的），轮次交给下一帧的变化去推。
     */
    let needFreshBaseline = false;

    /**
     * 按观测重建基准：丢掉已知事实表，基准 = 本帧盘面。
     * 只该在「恢复之后 + 这一帧够干净」时调（未知格 ≤ cfg.rebuildMaxUnknown）。
     */
    function rebuildBaselineFrom(board) {
        const dropped = knownFacts.count();
        knownFacts.clear();
        frameConsensus.clear();
        // ★ 重建基准用的是**这一帧的读数**，可高亮环/装饰会让模型把子读错（真机 2026-09-20：
        //   我方刚落下的炮被读成黑卒）⇒ 基准被写坏 ⇒ 下一帧起「既有合法单步也解释不了 4 格差异」
        //   ⇒ 又恢复、又重建 —— 循环。判据要的是**直接证据**：
        //   「全盘 OCR 说这格的子与上一张基准**完全一致**（字形兵种 + 像素颜色都一样）」
        //   ⇒ 那格根本没动过，是这一帧读错了 ⇒ 保住基准那一格。
        //   OCR 说不一样（真的换子/被吃）、或拿不到 OCR ⇒ 照旧采纳本帧（默认行为不变）。
        let kept = 0;
        const prev = (lastReliable && lastReliable.board) ? lastReliable.board : null;
        if (prev && typeof opts.ocrBoard === "function") {
            let look = null;
            try {
                look = opts.ocrBoard();
            } catch (error) {
                look = null;
            }
            if (look && look.ok && look.chars) {
                for (let i = 0; i < 90; i++) {
                    if (prev[i] === board[i]) {
                        continue;
                    }
                    const seen = look.chars[i];
                    if (typeof seen === "string" && seen && seen === prev[i]) {
                        board[i] = prev[i];
                        kept += 1;
                    }
                }
            }
        }
        if (kept > 0) {
            note("按观测量建基准：全盘 OCR 证据保住 " + kept + " 格" +
                "（与上一张基准完全一致 ⇒ 是这一帧读错，不是局面变了）");
        }
        // ★ 轮次不能沿用旧值（2026-09-20 真机踩到：整整一局卡在「轮到对方」直到超时）：
        //   重建基准用的那一帧，板面可能**已经包含对方刚应的那一手**，而旧 side 还停在
        //   「我方刚走完 ⇒ 轮到对方」→ 之后一直等一个永不再来的对方走子（差分每帧「变化 0 格」）。
        //   正确做法（与 recover 的注释同一个道理：轮次是**从上一步延续**出来的）：
        //   拿旧基准 + 这一帧推一次着法 —— 推得出（一步 / 一步+应手）就用推出来的下一手，
        //   推不出才沿用旧 side。
        let nextSide = lastReliable ? lastReliable.side : "w";
        // 轮次是「按着法推出来的」还是「沿用旧值猜的」——硬不变量的处置要靠它区分（见下）。
        let sideFromInference = false;
        if (lastReliable && lastReliable.board && typeof transition.infer === "function") {
            try {
                const inferred = inferMove(lastReliable.board, lastReliable.side, board,
                    { allowPair: true, allowSourceGhost: true });
                if ((inferred.status === "MOVE" || inferred.status === "MOVE_PAIR") && inferred.nextSide) {
                    nextSide = inferred.nextSide;
                    sideFromInference = true;
                    note("按观测量建基准：轮次按着法推出（" + inferred.reason + "）→ 现在轮到" +
                        (nextSide === "w" ? "红方" : "黑方"));
                }
            } catch (error) {
                nextSide = lastReliable ? lastReliable.side : "w";
                sideFromInference = false;
            }
        }
        /**
         * ★★ 硬不变量（2026-09-20 真机 now37，用户报「炮为什么去吃对面的将」）：
         *   重建基准是**直接照抄这一帧的读数**，而象棋规则不许「走完把自己留在被将状态」
         *   ⇒ **刚走完的那一方（= nextSide 的对面）不可能被将军**。
         *   若这张盘显示它正被攻击 ⇒ 这一帧一定读错了（现场：漏认 (4,1) 黑士 ⇒
         *   中路只剩一个炮架 ⇒ 引擎算出「红炮隔一子吃将」合法 ⇒ 连试 12 次全被游戏拒）。
         *   处置：**不采纳这张盘**（保持恢复态、下一帧再读），绝不拿它去算棋。
         */
        if (typeof rulesModule.isInCheck === "function") {
            let moverChecked = false;
            let otherChecked = false;
            try {
                moverChecked = !!rulesModule.isInCheck(board, nextSide !== "w");
                otherChecked = !!rulesModule.isInCheck(board, nextSide === "w");
            } catch (error) {
                moverChecked = false;
            }
            if (moverChecked) {
                stats.impossibleInCheck = (stats.impossibleInCheck || 0) + 1;
                /**
                 * ★★ 2026-09-20 真机 now38（这条不变量上线后第一次真机跑，白否 6 帧、多恢复 ~60s）：
                 *   轮次若是**兜底沿用旧值**（不是这一帧按着法推出来的），那这个矛盾恰好能**定轮次** ——
                 *   「被将的一方必须应着」⇒ 它就是该走棋的一方。
                 *   现场：兜底还说「黑方刚走完」，可盘面是红炮 (4,4) 隔着黑炮 (4,2) 在将黑将 (4,0)；
                 *   真正的情况是**红方刚走完、轮到黑方**，兜底只是过时了 ⇒ 白否了 6 帧。
                 *   处置（分两种，见注释上方）：
                 *     · 兜底轮次 + 只有一方被将 ⇒ **改轮次继续用这张盘**（别白烧恢复时间）；
                 *     · 按着法推出来的轮次 + 刚走完的一方被将 ⇒ 仍否掉（那时矛盾指向读数错了）。
                 *   ⚠️ 前提：两边的将/帅都在盘上（`isInCheck` 找不到将也会返回 true）。
                 */
                let bothKingsOnBoard = true;
                try {
                    if (typeof rulesModule.findKing === "function") {
                        bothKingsOnBoard = !!rulesModule.findKing(board, true) && !!rulesModule.findKing(board, false);
                    }
                } catch (error) {
                    bothKingsOnBoard = true;
                }
                if (!sideFromInference && bothKingsOnBoard && !otherChecked) {
                    const flipped = (nextSide === "w") ? "b" : "w";
                    note("⚠ 重建基准：兜底轮次（猜" + (nextSide === "w" ? "黑" : "红") + "方刚走完）与盘面冲突" +
                        "（那一方正被将，规则上不可能）⇒ 按「被将方必须应着」改成轮到" +
                        (flipped === "w" ? "红" : "黑") + "方，这张盘继续用");
                    nextSide = flipped;
                    stats.sideFromCheck = (stats.sideFromCheck || 0) + 1;
                } else {
                    note("⚠ 重建基准被否：刚走完的" + (nextSide === "w" ? "黑" : "红") +
                        "方还处在被将状态（规则上不可能）⇒ 这一帧读错了，不采纳（继续恢复，下一帧重读）");
                    return false;
                }
            }
        }
        clearBaselineBorrow();   // 基准重建：旧基准（含借出的那份）已不存在
        lastReliable = { board: board.slice(), side: nextSide };
        trackAdopt(board, nextSide, { reason: "rebuild", source: "manual-confirmed" });
        baselineCorrectedAt = 0;      // 旧票据不会再重建，避免拿旧事实去凑
        // 换了新基准 ⇒ 之前「按旧基准兜住」的粘性一格一律放下（盘面已经不是那张了）
        for (const key in stallFilledUntil) {
            delete stallFilledUntil[key];
        }
        // 新基准不能沿用旧基准积攒的“连续两帧”证据。
        crossFrameGate.reset("rebuild");
        needFreshBaseline = false;
        stats.baselineRebuilds += 1;
        note("按观测量建基准：丢掉 " + dropped + " 条已知事实，基准 = 本帧盘面" +
            "（轮次留给下一帧的变化去推）");
        // 返回值 = 这张盘有没有被采纳（tick 靠它决定「本帧不出手」；被否的帧不算基准）
        return true;
    }

    /**
     * 推轮次：从「上一可靠局面 + 新观测」推出现在轮到谁；推不出就返回 null。
     *
     * 副作用：推断成功时会把 lastReliable 刷成**按着法改正过的盘面**；
     * 若这次真的改动了观测（落点格被游戏装饰读错），额外留下 lastSideFix，
     * 交给本轮观察把 lastBoard / 快照一起改正 —— 否则算棋会拿着
     * 「我方炮在某格被读成兵、炮又不在原位」的错盘面出手。
     */
    /**
     * 对方应手「占用唯一解」快路（见 DEFAULTS.replyFastPath 的说明）。
     *
     * ⚠️ `base` 必须是**本帧 observeOnce 之前的基准快照**（由 tick 抓取）：observeOnce 里的补齐/纠错
     * 会就地改 lastReliable（真机 2026-09-21 21:39：对方卒 (3,6)→(3,7)，fills 先撤「起点有卒」的已知事实、
     * 把基准置空，又把落点当幻影连清 3 帧）——用被改过的基准枚举，那一手就不存在了，应手第二次被放跑。
     * 返回 { side } 表示已采信（基准已按「快照 + 这一手」重建、本帧观测已归一）；null = 本帧不适用。
     */
    function tryAdoptReplyByOccupancy(observed, base) {
        if (cfg.replyFastPath === false) {
            return null;
        }
        if (!observed || !observed.occupancy) {
            return null;   // 宿主/链路没带占用图（老宿主）⇒ 不适用
        }
        const occ = observed.occupancy;
        // ── 「棋子动没动」：驱动自己拿相邻帧占用做差（不看面板的变化格）──
        //   为什么自己算（2026-09-21 真机）：面板的 changed 只有「恰好只有 2 格变」才干净，
        //   而对方落子那一帧常夹着装饰闪烁（真机实测 变化 4 格）—— 旧版 `>2 就跳过` 把应手直接放跑。
        //   差出来的格子**整轮累积**（flipEpoch）：只留最近两帧的话，等读数干净时新鲜度已过期。
        let churn = null;
        if (prevOccupancy) {
            churn = [];
            for (let i = 0; i < 90; i++) {
                const a = prevOccupancy[i];
                const b = occ[i];
                if (a == null || b == null) {
                    continue;   // 看不清的格不参与
                }
                // ★ 2026-09-24：`? → 真子` 也算「翻面」——飞行中的子被读成 '?'（看不清）、
                //   落位后再读到真子，旧的「有 '?' 就跳过」会让落点格**永远进不了 flipEpoch**
                //   （真机：马 (6,4)→(5,2) 的 (5,2) 正是走 '?'→n 那条路）⇒ 快路的硬证据③
                //   “落点必须是本回合翻过面的格” 直接把它挡掉，应手放跑。
                if (a === "?" && b !== "?") {
                    churn.push(i);
                    continue;
                }
                if (a === "?" || b === "?") {
                    continue;   // 其余涉及 '?' 的过渡不参与（快路只关心「落点*出现*了走棋方的子」）
                }
                if (a !== b) {
                    churn.push(i);
                }
            }
        } else if (observed.changed && observed.changed.length) {
            churn = observed.changed.slice();   // 开机后第一帧：用面板的变化格兜底
        }
        prevOccupancy = occ.slice();
        if (churn && churn.length) {
            if (churn.length > 12) {
                flipEpoch = {};   // 大范围翻面（多半是重锚/几何抖动）⇒ 重开账本，只认这一批
            }
            for (let k = 0; k < churn.length; k++) {
                flipEpoch[churn[k]] = 1;
            }
            let size = 0;
            for (const key in flipEpoch) {
                size += 1;
            }
            if (size > 24) {
                flipEpoch = {};   // 账本太长：旧的装饰翻面清掉，只留这一批
                for (let k = 0; k < churn.length; k++) {
                    flipEpoch[churn[k]] = 1;
                }
            }
        }
        if (!base || !base.board) {
            replyFastPending = null;
            return null;
        }
        // 只在「等对方应手」时用：基准轮次必须不是我方；另外我方有活票据（点击/确认在飞）时一律不动
        if (base.side === machine.mySide()) {
            replyFastPending = null;
            return null;
        }
        if (typeof machine.currentTicket === "function" && machine.currentTicket()) {
            replyFastPending = null;
            return null;
        }
        const mover = base.side === "w" ? "R" : "B";
        // ① 先看上一帧的候选还立不立得住（佐证 = 「同一手在下一帧仍然成立」）——
        //    用**创建候选时冻结的基准快照**判断：fills 会跨帧把基准改掉（真机 21:48 的 车 f0f1：
        //    「撤事实 + 清幻影 + 收编」跑完，基准里那一手已经不存在了，重新枚举/对比都会丢候选）——
        //    所以佐证不再重新枚举，只验那两格：起点仍空 + 落点仍是走棋方的颜色。
        if (replyFastPending) {
            const pend = replyFastPending;
            const pMover = pend.base.side === "w" ? "R" : "B";
            if (occ[pend.from] === "." && occ[pend.to] === pMover) {
                return adoptReply(observed, pend.base, pend.move, pend.iccs, pend.from);
            }
            // 立不住：先别急着丢（读数可能闪一帧）——给它两帧机会；连着几帧都不行才丢
            pend.age = (pend.age || 0) + 1;
            if (pend.age <= 2) {
                return null;
            }
            replyFastPending = null;
        }
        const list = legal.allLegalMoves(base.board, base.side);
        let hit = null;
        for (let i = 0; i < list.length; i++) {
            const move = list[i];
            const iccs = move && move.iccs;
            if (!iccs) {
                continue;
            }
            const fi = transition.squareIndex(iccs, 0);
            const ti = transition.squareIndex(iccs, 1);
            if (fi < 0 || ti < 0) {
                continue;
            }
            // 硬证据 ①：起点现在读「空」 —— 起点格只住一枚子，它在对方回合变空只可能是刚走这手；
            // 硬证据 ②：落点现在读「走棋方的颜色」；
            // 两条合并后结构上已经唯一（旧版还要 changed⊆{起点,落点}，已被真机证明太严）。
            if (occ[fi] !== "." || occ[ti] !== mover) {
                continue;
            }
            // 硬证据 ③：落点得是**本个对方回合里翻过面的子**（flipEpoch）——
            //   防「陈旧落点」（基准去同步时某格本来就空/本来就有子）造成的假唯一解。
            if (!flipEpoch[ti]) {
                continue;
            }
            if (hit) {
                // 多解（理论到不了）⇒ 宁可不动，交给原路径
                stats.replyFastSkipped = (stats.replyFastSkipped || 0) + 1;
                replyFastPending = null;
                return null;
            }
            hit = { move: move, iccs: iccs, from: fi, to: ti };
        }
        if (!hit) {
            replyFastPending = null;
            return null;
        }
        // 第一次看见：把「基准快照 + 这一手」**一起冻住**当候选（基准后续被 fills 改也不怕），
        // 下一帧佐证（两格仍然成立）后采纳。
        replyFastPending = { iccs: hit.iccs, from: hit.from, to: hit.to, move: hit.move, base: base };
        stats.replyFastWaits = (stats.replyFastWaits || 0) + 1;
        note("快路：候选 " + hit.iccs + " 待佐证（下一帧再看一眼就采纳）");
        return null;
    }

    /**
     * 快路采纳收尾：按「冻结快照 + 这一手」重建基准、归一观测（待佐证路与直采路共用）。
     * 返回 { side } 或 null（硬不变量不过时交给原流程）。
     */
    function adoptReply(observed, base, move, iccs, fromIndex) {
        replyFastPending = null;
        flipEpoch = {};   // 这一手已定案：账本清空，等对面的下一手重新累积
        const applied = legal.applyMove(base.board, move).board;
        // 硬不变量：刚走完的那一方不可能被将（同 sideFor 的 MOVE 分支）——对不上说明基准有问题，交给原流程去恢复
        if (typeof rulesModule.isInCheck === "function") {
            let suspect = false;
            try {
                suspect = rulesModule.isInCheck(applied, base.side === "w");
            } catch (error) {
                suspect = false;
            }
            if (suspect) {
                stats.replyFastSkipped = (stats.replyFastSkipped || 0) + 1;
                return null;
            }
        }
        const nextSide = base.side === "w" ? "b" : "w";
        // 用**快照 + 这一手**重建基准（顺带把 fills 对基准的临时改动纠正回来）
        clearBaselineBorrow();   // 快路认出来了：对账结案（若这手是借旧基准对出来的）
        lastReliable = { board: applied, side: nextSide };
        trackConfirm({ iccs: iccs, fromBoard: base.board, side: base.side, afterBoard: applied, source: "opponent-observed" });
        lastSideFix = null;   // 观测马上被归一成这份盘面，不需要再“修正”
        dropPatchesAt([fromIndex], "占用唯一解 " + iccs + " 的起点已空");
        registerMoveDestinations([{ iccs: iccs }], applied);
        // 把本帧观测直接归一到「基准 + 这一手」：这正是快路的意义 —— 不等 OCR、不留未知格
        for (let i = 0; i < 90; i++) {
            observed.chars[i] = applied[i];
        }
        observed.unknownCells = 0;
        lastBoard = observed.chars.slice();
        stats.replyFastAdopts = (stats.replyFastAdopts || 0) + 1;
        note("对手应手=占用唯一解 " + iccs + "（起点已空 + 落点有子，免 OCR 直接采信）");
        return { side: nextSide };
    }

    function sideFor(board, unknownCells) {
        lastSideFix = null;
        lastInferHold = false;
        if (unknownCells > 0) {
            return null;
        }
        if (!lastReliable) {
            // 没有任何历史时唯一的可靠推断：棋盘就是标准开局 → 中国象棋红先
            if (isStartPosition(board)) {
                lastReliable = { board: board.slice(), side: "w" };
                trackAdopt(board, "w", { reason: "start-position", source: "manual-confirmed" });
                note("局面是标准开局 → 按红先处理（轮到红方）");
                return "w";
            }
            return null;
        }
        // ★★ 2026-09-24 对账修订（见 recordBaselineRevision 的注释）：本帧的修订器动过基准，
        //   而那时正在等对方应手 —— 借回「修订前的基准」再算轮次：修订+观测的**累计差**里
        //   就带着对方那一手。认得出 ⇒ 下面的正常流程会按它翻轮次；认不出且超过
        //   cfg.baselineBorrowMs ⇒ 放弃、回到修订后的基准（即老行为）。
        //   ⚠️ 只在**证据像应手**时才借：「观测里走棋方的子少了一枚」（= 应手正在飞的最早证据）。
        //   反过来「凭空多出一枚」（幻影收编的典型形态：now48 的漏收）**维持老行为** ——
        //   那种情况老流程（收编进基准 + 后续恢复）已经能自愈，借旧基准反而会把基准劈成两半。
        if (pendingBaselineRevision) {
            const rev = pendingBaselineRevision;
            pendingBaselineRevision = null;
            const fresh = rev && rev.beforeBoard && rev.beforeBoard.length === 90 &&
                nowMono() - rev.atMono <= cfg.baselineBorrowMs;
            if (fresh && baselineBorrow) {
                // 已经在借：把它拉回「这份修订之前」的状态（内容与借出的那份一致），继续等对账
                lastReliable = { board: rev.beforeBoard.slice(), side: rev.beforeSide };
                baselineBorrow.base = lastReliable;
            } else if (fresh && rev.beforeSide !== machine.mySide() &&
                vanishedMoverPiece(rev.beforeBoard, board, rev.beforeSide)) {
                const aside = lastReliable;
                lastReliable = { board: rev.beforeBoard.slice(), side: rev.beforeSide };
                baselineBorrow = { base: lastReliable, aside: aside, atMono: rev.atMono, why: rev.why };
                // 影子账本也跟着借回旧基准：不然「resync 改基准 + 借旧基准」一改一借，
                // 影子与诚实基准对不上，后面每一步推断着法都被它拒收（日志噪声 + 引擎丢历史）。
                trackAdopt(lastReliable.board, lastReliable.side, { reason: "baseline-borrow" });
                relaxUnknownWatchdog(true);   // 对账期间别让 4s 看门狗抢先恢复（结案/回退时恢复原值）
                stats.baselineBorrows = (stats.baselineBorrows || 0) + 1;
                note("对账修订：本帧修订（“" + rev.why + "”）改过可靠基准，且观测里" +
                    (rev.beforeSide === "w" ? "红" : "黑") + "方少了一枚子 —— " +
                    "借回修订前的基准重算轮次（修订+观测的累计差里应含对方那一手）");
            }
        } else if (baselineBorrow) {
            // 借出的旧基准还在用：期间可能被修订器刷新过 lastReliable（那也算还在借）。
            // 超时仍未认出来 ⇒ 放弃对账，回到修订后的基准（即老行为）。
            if (nowMono() - baselineBorrow.atMono > cfg.baselineBorrowMs) {
                const borrowed = baselineBorrow;
                baselineBorrow = null;
                relaxUnknownWatchdog(false);   // 回退：看门狗恢复原值，该恢复就恢复
                if (lastReliable === borrowed.base) {
                    lastReliable = borrowed.aside || lastReliable;
                }
                if (lastReliable && lastReliable.board) {
                    trackFix(lastReliable.board, lastReliable.side, { reason: "borrow-timeout" });
                }
                stats.baselineBorrowTimeouts = (stats.baselineBorrowTimeouts || 0) + 1;
                note("对账修订超时（" + Math.round(nowMono() - borrowed.atMono) + "ms 还没认出" +
                    "对方那一手）→ 放弃旧基准，回到修订后的基准");
            }
        }
        const inferred = inferMove(lastReliable.board, lastReliable.side, board, {
            allowPair: true,
            // 起点残影（真机 2026-09-20）：只在驱动这边开 —— 这里还有「同一个解释连续两帧一致」
            // 的跨帧佐证（tolerantSupportedByTwoFrames）兜着，单帧分不出残影/幻觉才敢用。
            allowSourceGhost: true
        });
        if (inferred.status === "MOVE" || inferred.status === "MOVE_PAIR") {
            const settled = inferred.board ? inferred.board.slice() : board.slice();
            // ★ 跨帧佐证：真实着法在下一帧会**重新推出同一个解释**（因为 `lastReliable` 这时还没动），
            //   而模型伪影造成的假着法下一帧会变样 ⇒ 只等一帧就能把假事实拦在外面。
            if (!tolerantSupportedByTwoFrames(lastReliable.board, inferred.reason)) {
                lastSideFix = null;
                lastInferHold = true;
                return null;
            }
            if (inferred.board && legal.diffBoards(settled, board).length > 0) {
                lastSideFix = { board: settled, how: inferred.reason };
            }
            const prevReliableBoard = lastReliable.board;
            const prevReliableSide = lastReliable.side;
            clearBaselineBorrow();   // 认出来了：对账结案（若这手是借旧基准对出来的）
            lastReliable = { board: settled, side: inferred.nextSide };
            trackInferredMoves(inferred.moves || [], prevReliableBoard, prevReliableSide,
                inferred.side === machine.mySide() ? "our-click" : "opponent-observed");
            note("轮次推断：" + inferred.reason);
            /**
             * ★★ 硬不变量（2026-09-20 真机 now37，用户报「炮为什么去吃对面的将」）：
             *   象棋规则不许「走完把自己留在被将状态」⇒ **刚刚走完的那一方不可能被将军**。
             *   所以若这张盘显示「刚走完的一方（= side）正被攻击」⇒ 它一定是错的
             *   （现场：漏认 (4,1) 黑士 ⇒ 引擎算出「红炮隔一子吃将」合法 ⇒ 连试 12 次全被游戏拒）。
             *   处置：不信它 —— 作废基准、走恢复流程重新全盘读，别拿着错盘去算棋。
             */
            if (typeof rulesModule.isInCheck === "function") {
                let suspect = false;
                try {
                    suspect = rulesModule.isInCheck(settled, inferred.side === "w");
                } catch (error) {
                    suspect = false;
                }
                if (suspect) {
                    stats.impossibleInCheck = (stats.impossibleInCheck || 0) + 1;
                    note("⚠ 盘面自相矛盾：刚走完的" + (inferred.side === "w" ? "红" : "黑") +
                        "方还处在被将状态（规则上不可能）→ 判这张盘不可信，重新全盘确认");
                    lastReliable = null;
                    clearBaselineBorrow();
                    trackClear("impossible-in-check");
                    needFreshBaseline = true;
                    lastSideFix = null;
                    return null;
                }
            }
            // 刚落的子会被画高亮环 → 把落点登记成已知事实，否则下一帧那一格变「空」→ 推不出轮次 → 恢复。
            // ⚠️ 顺序：先作废**这些着法的起点**（那里已经空了），再登记落点。
            // 反过来的话，一步「走到别处又回来」的棋会把自己的起点记录留下（真机：炮 (2,7)→(2,3)
            // 之后 (2,7) 的记录还在 → 下一帧那一格被补回旧子 → 整盘看起来没动）。
            const sources = [];
            const moves = inferred.moves || [];
            for (let i = 0; i < moves.length; i++) {
                const from = transition.squareIndex(moves[i].iccs, 0);
                if (from >= 0) {
                    sources.push(from);
                }
            }
            dropPatchesAt(sources, "刚推出来的着法起点已空");
            registerMoveDestinations(inferred.moves, settled);
            return inferred.nextSide;
        }
        // ★ 「被盖住的应手」：对手刚走完，但起点/落点被游戏「上一步」标记盖住，观测层又把那几格
        //   按旧基准填了回去（未知=没变）⇒ 差分看起来「没动」或「多出来一个子」，下面两条路全解释
        //   不了，而且「没动（UNCHANGED）」还是个**假象**——必须赶在 UNCHANGED 提前返回之前查。
        //   （真机 K40 2026-09-21：黑炮 b7b5 之后每帧「顽固未知 1 格→按旧基准填」，盘面永远
        //   没变化 ⇒ 轮次卡在「轮到对方」5 分钟，直到被绝杀。）
        //   思路：对方其实已经走完了一手，用**唯一合法着法**把它认出来——枚举它的全部合法着法m，
        //   apply(基准, m) 与观测的每一处差异都只落在「被盖住」的格上（或只是同字异色噪声）；
        //   有且只有一招说得通 ⇒ 就是这一手。
        if (coveredFilledIndexes.length > 0 && lastReliable && lastReliable.board) {
            const guess = transition.inferSingleThroughCovered(
                lastReliable.board, lastReliable.side, board, coveredFilledIndexes);
            if (guess) {
                if (!tolerantSupportedByTwoFrames(lastReliable.board, "盖住的应手 " + guess.iccs)) {
                    lastInferHold = true;
                    return null;
                }
                lastSideFix = { board: guess.board.slice(), how: "盖住的应手 " + guess.iccs };
                const prevCoveredBoard = lastReliable.board;
                const prevCoveredSide = lastReliable.side;
                clearBaselineBorrow();   // 认出来了：对账结案（若这手是借旧基准对出来的）
                lastReliable = { board: guess.board, side: guess.nextSide };
                trackConfirm({
                    iccs: guess.iccs, fromBoard: prevCoveredBoard, side: prevCoveredSide,
                    afterBoard: guess.board, source: "opponent-observed",
                    toleranceIndexes: coveredFilledIndexes
                });
                note("轮次推断（盖住的应手）：唯一合法着法 " + guess.iccs +
                    "（起点/落点在游戏「上一步」标记下读不出来，已按该着法改正）");
                dropPatchesAt([transition.squareIndex(guess.iccs, 0)], "盖住的应手起点已空");
                registerMoveDestinations([{ iccs: guess.iccs }], guess.board);
                return guess.nextSide;
            }
        }
        if (inferred.status === "UNCHANGED") {
            // 观测只差几个「颜色读反」的格 → 场面还是基准那张（本手没落子）→
            // 把观测/快照拉回基准，别把带反色的盘面拿去算棋。
            if (inferred.board && legal.diffBoards(inferred.board, board).length > 0) {
                lastSideFix = { board: inferred.board.slice(), how: inferred.reason };
            }
            return lastReliable.side;
        }
        // 严格推断失败 → 再试一次**带装饰容差**的推断（放宽唯一合法着法的**落点那一格**）。
        // 真机踩到（K40 + 天天象棋）：对方炮吃我方兵、落在兵位上，那一格被模型读成 'p'（炮认成卒）
        // → 严格推断「既没有合法单步也没有一步+应手能解释」→ 轮次未知 → 8s 看门狗 → 永久 RECOVERING。
        // 收紧点：只允许**一格**不同，而且那格必须正是该着法的落点；其余格子仍要完全一致。
        const tolerant = inferWithDecorationTolerance(lastReliable.board, lastReliable.side, board);
        if (tolerant) {
            if (!tolerantSupportedByTwoFrames(lastReliable.board,
                "唯一合法单步 " + tolerant.iccs)) {
                lastInferHold = true;
                return null;
            }
            lastSideFix = { board: tolerant.board, how: "唯一合法单步 " + tolerant.iccs };
            const prevTolerantBoard = lastReliable.board;
            const prevTolerantSide = lastReliable.side;
            clearBaselineBorrow();   // 认出来了：对账结案（若这手是借旧基准对出来的）
            lastReliable = { board: tolerant.board, side: tolerant.nextSide };
            trackConfirm({
                iccs: tolerant.iccs, fromBoard: prevTolerantBoard, side: prevTolerantSide,
                afterBoard: tolerant.board,
                source: prevTolerantSide === machine.mySide() ? "our-click" : "opponent-observed",
                toleranceIndexes: [transition.squareIndex(tolerant.iccs, 1)]
            });
            note("轮次推断（装饰容差）：唯一合法单步 " + tolerant.iccs +
                "（落点格被游戏装饰读错，已按该着法改正" +
                (tolerant.colorNoise ? "；另有 " + tolerant.colorNoise + " 格只是红黑读反，已按基准纠正" : "") +
                "）");
            // ★ 这条路上**也要登记落点**（2026-09-20 探针发现）：刚落的子会被画高亮环，
            //   不登记的话下一帧那一格又读错（颜色判反/读成空）→ 又一次「轮次推不出来」。
            //   同一套顺序：先作废起点，再登记落点（理由见上面严格分支的注释）。
            dropPatchesAt([transition.squareIndex(tolerant.iccs, 0)], "装饰容差推出的着法起点已空");
            registerMoveDestinations([{ iccs: tolerant.iccs }], tolerant.board);
            return tolerant.nextSide;
        }
        stats.unknownTurn += 1;
        note("轮次推不出来（" + inferred.status + "）：" + inferred.reason);
        // 诊断（限频）：把「基准 / 观测 / 容差两步统计」都写出来 —— 只报「推不出来」没法定位
        if (stats.unknownTurn <= 3 || stats.unknownTurn % 10 === 0) {
            const baseFen = lastReliable ? safeFen(lastReliable.board, lastReliable.side) : "（无基准）";
            note("诊断基准 " + baseFen);
            if (lastRawChars) {
                note("诊断原始读数 " + safeFen(lastRawChars, lastReliable ? lastReliable.side : "w"));
            }
            note("诊断观测 " + safeFen(board, lastReliable ? lastReliable.side : "w") +
                (inferred.debug ? "｜容差：我方着法 " + inferred.debug.ownMoves +
                    "，试 " + inferred.debug.tried + "，最小差 " + inferred.debug.minDiff +
                    "，命中 " + inferred.debug.fits + "，最优 " + inferred.debug.best : ""));
            // 补齐表内容（2026-09-14 加）：「推不出来」有两种完全不同的原因 ——
            // ① 表里根本没有那两格的记录；② 表里有、但这帧没补上（读成了别的字）。
            // 不把表打出来就只能猜（当天为这个来回跑了好几轮真机）。
            const table = [];
            const factsForLog = knownFacts.list();
            for (let i = 0; i < factsForLog.length; i++) {
                const patch = factsForLog[i];
                if (!patch || patch.index == null) {
                    continue;
                }
                table.push("(" + (patch.index % 9) + "," + Math.floor(patch.index / 9) + ")" +
                    patch.char + "/本轮读到" + (lastRawChars ? (lastRawChars[patch.index] || "空") : "？"));
            }
            note("诊断补齐表 " + (table.length ? table.join(" ") : "（空）") +
                "｜周期 " + verifyCycle);
        }
        return null;
    }

    /** 诊断用：出 FEN，不抛错。 */
    function safeFen(board, side) {
        try {
            return fenModule.buildFen(board, side === "b" ? "b" : "w");
        } catch (error) {
            return "（FEN 失败：" + (error && error.message ? error.message : error) + "）";
        }
    }

    /**
     * 带装饰容差的轮次推断：在「唯一合法单步」上允许**落点那一格**与观测不同
     * （游戏给刚落的子加高亮环/击中特效，模型会把兵种读错）。
     * 返回 { board（按该着法改正后的盘面）, nextSide, iccs } 或 null。
     */
    function inferWithDecorationTolerance(base, baseSide, observed) {
        // 逻辑已下沉到 `transition.inferSingleWithDecorationTolerance`（纯函数，可离线自检）：
        // 单步 + 只允许「落点格被装饰读错」以及「没被这步碰过的格子只是红黑读反」。
        // 这里只做形状适配（驱动要用 board/nextSide/iccs，日志要用 colorNoise）。
        const tolerant = transition.inferSingleWithDecorationTolerance(base, baseSide, observed,
            { allowSourceGhost: true });
        if (!tolerant) {
            return null;
        }
        return {
            board: tolerant.board,
            nextSide: tolerant.nextSide,
            iccs: tolerant.iccs,
            colorNoise: tolerant.colorNoise
        };
    }

    /**
     * 一轮：观察 → 喂状态机 → 执行动作。
     * @returns { ok, state, reason, board, side, unknownCells }
     */
    function tick() {
        if (!running) {
            return { ok: false, reason: "没有在运行" };
        }
        stats.ticks += 1;

        // 先非阻塞地把 worker 的算棋结果收回来（不截图、不观察，每轮只花一次 getResult()）。
        // 还在算就直接返回：既不挡界面，也不会在思考期间反复去截屏干扰棋盘。
        const thinking = pollCompute();
        if (thinking && thinking.pending) {
            return {
                ok: true,
                state: machine.state(),
                reason: "算棋中（worker 线程，已 " + Math.round(thinking.elapsedMs) + "ms）",
                thinking: true
            };
        }

        pump(machine.dispatch({ type: "TICK", nowMono: nowMono() }));

        // 没有可用几何（还没定位成功 / 恢复中）就**每一轮都重试定位**：
        // RECOVERING 不是终点，只在恢复时定位一次的话，用户把棋盘切上来之后
        // 会一直停在 RECOVERING（真机踩过：脚本先在自己的界面上启动，定位失败后就再也不试了）。
        if (!grid) {
            calibrate();
        }

        // ★ 对方应手快路要的「基准快照」必须在 observeOnce **之前**抓：
        //   observeOnce 里的补齐/纠错（真机 2026-09-21 21:39：对方卒 (3,6)→(3,7)，fills 先撤「起点有卒」
        //   的已知事实、把基准置空，又把落点当幻影连清 3 帧）会就地改 lastReliable ——
        //   等快路回头枚举「基准的合法着法」时那一手已经不在基准里，应手又被放跑、白等 45 秒。
        //   快路一律用这份快照推理；采纳时用「快照 + 这一手」重建基准。
        const replyFastBase = lastReliable
            ? { board: lastReliable.board.slice(), side: lastReliable.side }
            : null;
        const observed = observeOnce({});
        lastObsSummary = observed.ok ? {
            obs: stats.observations,
            unknown: observed.unknownCells,
            covered: observed.coveredFills || 0,
            key: (observed.unknownCells === 0 && observed.chars)
                ? positionKeyOfBoard(observed.chars) : null
        } : null;
        if (!observed.ok) {
            // 失败不能静默：否则界面停在 WAIT_STABLE，日志里什么都看不到（真机踩过）
            const failures = stats.unrecognized;
            if (failures <= 2 || failures % 10 === 0) {
                note("本轮没看清（第 " + failures + " 次）：" + observed.reason);
            }
            pump(machine.dispatch({
                type: "SNAPSHOT",
                snapshot: { board: lastBoard, side: null, fen: null, unknownCells: 90, stable: false }
            }));
            return { ok: false, reason: observed.reason, state: machine.state() };
        }

        // ★ 恢复之后：**按观测量建基准**（用户口径 2026-09-20：用视觉重新对局面）——
        //   不再拿旧基准 + 已知事实去凑原来那张盘面（那正是「一枚在飞的子被删掉」之后卡死的根源）。
        //   两个约束：① 未知格 ≤ `cfg.rebuildMaxUnknown`；② **每个未知格都要能用旧事实填上**
        //   （可靠基准不能含 '?' —— 带 '?' 的基准会把 '?' 当事实抄回去）。
        //   填不上或超限就**等下一帧**（恢复后总有几帧是干净的）。
        let rebuiltThisTick = false;
        if (needFreshBaseline) {
            const facts = knownFacts.list();
            const rebuilt = observed.chars.slice();
            let unknown = 0;
            let fillable = true;
            for (let i = 0; i < 90; i++) {
                if (rebuilt[i] !== "?") {
                    continue;
                }
                unknown += 1;
                let filled = false;
                for (let k = 0; k < facts.length; k++) {
                    if (facts[k] && facts[k].index === i && facts[k].char) {
                        rebuilt[i] = facts[k].char;
                        filled = true;
                        break;
                    }
                }
                if (!filled || unknown > cfg.rebuildMaxUnknown) {
                    fillable = false;
                }
            }
            if (unknown === 0 || fillable) {
                // ★★ 2026-09-20 真机死循环的根因就在这两行之间：
                //   重建基准时用「全盘 OCR 证据」保住了基准那一格（`board[i] = prev[i]`，在
                //   `rebuildBaselineFrom` **内部**发生），但那只改了 `rebuilt` ——
                //   **同一 tick 紧接着的 `sideFor` 用的还是原始观测**，里面那一格仍是模型的
                //   误读 ⇒ 又是「没有合法着法能解释」⇒ 恢复 ⇒ 重建 ⇒ 再撞，
                //   真机连刷 22 次「轮次推不出来」（now21）。
                //   所以**必须在重建之后**把改正写回观测（写回顺序反了就成了空操作 —— 自检抓过）。
                rebuiltThisTick = rebuildBaselineFrom(rebuilt);
                for (let i = 0; i < 90; i++) {
                    observed.chars[i] = rebuilt[i];
                }
                lastBoard = observed.chars.slice();
            }
        }
        /**
         * ★★ 2026-09-21 真机 07:59（「又输了」那局）的根因之一：重建基准用的**只有这一帧**，
         *   而下面的 `sideFor` 紧随其后就把轮次定死了 ⇒ 0.25s 后引擎在没被第二帧佐证过的盘面上
         *   算出「一步杀 29999」（深度 1、2ms），3.5s 后点击 —— 下完这手对局就结算输了。
         *   重建基准自己的注释写着「轮次留给下一帧的变化去推」—— 这里把它落到实处：
         *   **本帧不出手**（快照按「轮次未知」喂给机器），下一帧佐证盘面（UNCHANGED / 唯一合法
         *   着法）之后再算棋。代价只有一帧（~1.2s），换掉的是「单帧当事实」的整类事故。
         */
        if (rebuiltThisTick) {
            /**
             * ★★ 杀棋全盘复核的终判（2026-09-21 下午，闸门 v2 的配套）：
             *   被盖格帧不配当佐证（两帧同错）⇒ tick 里请求了「全盘新鲜重读 + 重建基准」
             *   （跳差分缓存，不共享那个可能错的锚点）。重读结果就在这里判：
             *   盘面对得上 ⇒ 放行；对不上 ⇒ 作废重算。都不再等第二帧。
             *   注意：本分支的 observed.chars 已经是 rebuilt（含事实补齐）后的盘面。
             */
            if (pendingMate && pendingMate.freshRequested) {
                const held = pendingMate;
                pendingMate = null;
                if (positionKeyOfBoard(observed.chars) === held.boardKey) {
                    stats.mateHoldPassed += 1;
                    note("杀棋全盘复核通过：全盘新鲜重读与算棋时盘面一致 → 放行 " + held.iccs);
                    recordPlanDecision(held.board, held.side, held.iccs);
                    pump(machine.dispatch(Object.assign({ type: "JOB_RESULT" }, held.outcome)));
                    return {
                        ok: true,
                        state: machine.state(),
                        reason: "杀棋全盘复核通过",
                        board: observed.chars,
                        side: null,
                        unknownCells: observed.unknownCells
                    };
                }
                stats.mateHoldVetoed += 1;
                note("杀棋全盘复核未通过：全盘新鲜重读的盘面与算棋时不一致" +
                    "→ 作废这手重算（不再蒙着点）");
                pump(machine.dispatch({
                    type: "JOB_RESULT", jobId: held.jobId, ok: false,
                    reason: "杀棋全盘复核未通过（重读盘面不一致）"
                }));
                return {
                    ok: true,
                    state: machine.state(),
                    reason: "杀棋全盘复核未通过",
                    board: observed.chars,
                    side: null,
                    unknownCells: observed.unknownCells
                };
            }
            stats.rebuildHolds += 1;
            note("重建基准（只这一帧）：本帧不出手，等下一帧佐证盘面与轮次再算");
            pump(machine.dispatch({
                type: "SNAPSHOT",
                snapshot: {
                    board: observed.chars,
                    side: null,
                    fen: null,
                    unknownCells: observed.unknownCells,
                    conflicts: observed.conflicts,
                    stable: true,
                    geometryTrusted: true,
                    // 几何版本：新票据要用它，不能拿旧版本（不然点击器核对不上，见 state-machine 的同修复）
                    geometryVersion: (grid && grid.version != null) ? grid.version : null
                }
            }));
            return {
                ok: true,
                state: machine.state(),
                reason: "重建基准后等下一帧佐证",
                board: observed.layout,
                side: null,
                unknownCells: observed.unknownCells
            };
        }
        // ★ 对方应手「占用唯一解」快路（2026-09-21，用户提议「提前推理对手的棋」）：
        //   等待对方时，占用扫描就能把那一手定死 —— 不必等 OCR 读出兵种，更不必跨帧佐证/恢复。
        //   拿不出结论（没能力带 occupancy/changed、对不上、多解）一律 null，原路径照走。
        let side = null;
        const fastReply = tryAdoptReplyByOccupancy(observed, replyFastBase);
        if (fastReply) {
            side = fastReply.side;
        } else {
            side = sideFor(observed.chars, observed.unknownCells);
        }
        /**
         * 记「轮次被未知格挡住」的连续帧数（见 unknownSideStreak 的注释）：
         * 只有「确实有未知格、而且轮次推不出来」才算挡住 —— 推得出来就不算。
         */
        if (side == null && observed.unknownCells > 0) {
            unknownSideStreak += 1;
        } else {
            unknownSideStreak = 0;
        }
        // 轮次推断如果给出了「按着法改正过的盘面」（落点格被游戏装饰读错兵种），
        // 就以它为准重算本轮的观测与 lastBoard：这是**唯一合法解释**推出来的事实，
        // 比模型对那两个装饰格的读数可信；不改正的话算棋会拿着错盘面出手。
        if (lastSideFix && lastSideFix.board && observed.chars && observed.unknownCells === 0) {
            const fix = lastSideFix;
            lastSideFix = null;
            let fixed = 0;
            for (let i = 0; i < 90; i++) {
                if (fix.board[i] && fix.board[i] !== observed.chars[i]) {
                    observed.chars[i] = fix.board[i];
                    fixed += 1;
                }
            }
            if (fixed > 0) {
                lastBoard = observed.chars.slice();
                stats.sideFixes += 1;
                note("盘面已按着法改正 " + fixed + " 格（" + fix.how + "）");
            }
        }
        /**
         * ★★ 杀棋佐证闸门（2026-09-21 真机 07:59 输棋的另一半）：
         *   引擎报出「我方杀棋」时先别点 —— 假杀都是**坏盘面**算出来的
         *   （2026-09-20 漏认 (4,1) 黑士 ⇒ 红炮「隔子吃将」29999，连点 12 次全被游戏拒；
         *   2026-09-21 07:59 又同一族：刚重建的盘面上算出车一步杀，点下去反被将死）。
         *   闸门规则：算棋那一帧必须再被**独立观测**复核一次才放行（0 未知格且盘面一致）；
         *   第二帧盘面不一致 ⇒ 作废重算（多半前一帧读错，或者其实已经轮到对方）。
         */
        if (pendingMate) {
            if (machine.state() !== machineModule.STATE.THINKING) {
                note("杀棋待佐证作废：状态已不是 THINKING（" + machine.state() + "）");
                pendingMate = null;
            } else if (stats.observations > pendingMate.obsAtStart && observed.unknownCells === 0 &&
                       positionKeyOfBoard(observed.chars) === pendingMate.boardKey) {
                const coveredNow = coveredFilledIndexes.length;
                if (coveredNow === 0) {
                    // 干净帧：盘面一致、每个格都是真读出来的 ⇒ 可信的独立复核
                    const held = pendingMate;
                    pendingMate = null;
                    stats.mateHoldPassed += 1;
                    note("杀棋佐证通过：第二帧读到同一张盘面（干净帧）→ 放行 " + held.iccs);
                    recordPlanDecision(held.board, held.side, held.iccs);
                    pump(machine.dispatch(Object.assign({ type: "JOB_RESULT" }, held.outcome)));
                    return {
                        ok: true,
                        state: machine.state(),
                        reason: "杀棋佐证通过",
                        board: observed.layout,
                        side: side,
                        unknownCells: observed.unknownCells
                    };
                }
                if (!pendingMate.freshRequested) {
                    // ★★ 「两帧同错」防不住（真机 09:50 的 d6g6：两帧一致照样放行了假杀）：
                    //   帧一致但含「按基准填回」的格 ⇒ 这两帧其实是**同一份填出来的盘面**，
                    //   不算独立复核 ⇒ 强制一次全盘新鲜重读（跳差分缓存）再判。
                    pendingMate.freshRequested = true;
                    pendingMate.freshDeadlineObs = stats.observations + 10;
                    forceFreshReadOnce = true;
                    needFreshBaseline = true;
                    note("杀棋佐证帧含 " + coveredNow + " 格「按基准填回」（可能是两帧同错）" +
                        "→ 强制全盘新鲜重读复核，盘面对上了才出手");
                } else if (pendingMate.freshDeadlineObs &&
                           stats.observations > pendingMate.freshDeadlineObs) {
                    // 全盘复核迟迟没落地（重建一直被拒/重读一直不干净）⇒ 宁可重算，不要蒙一把
                    const held = pendingMate;
                    pendingMate = null;
                    stats.mateHoldVetoed += 1;
                    note("杀棋全盘复核超时（等不到干净的重读盘面）→ 作废这手，等下一轮重算");
                    pump(machine.dispatch({
                        type: "JOB_RESULT", jobId: held.jobId, ok: false,
                        reason: "杀棋全盘复核超时"
                    }));
                    return {
                        ok: true,
                        state: machine.state(),
                        reason: "杀棋全盘复核超时",
                        board: observed.layout,
                        side: side,
                        unknownCells: observed.unknownCells
                    };
                }
                // 否则：全盘重读/重建还在路上 —— 重建帧那一拍会在 rebuiltThisTick 分支里给出终判
            } else if (stats.observations > pendingMate.obsAtStart && observed.unknownCells === 0) {
                const held = pendingMate;
                pendingMate = null;
                stats.mateHoldVetoed += 1;
                note("杀棋佐证未通过：第二帧盘面与算棋时不一致 → 作废重算（多半前一帧读错了）");
                pump(machine.dispatch({
                    type: "JOB_RESULT", jobId: held.jobId, ok: false,
                    reason: "杀棋佐证未通过（第二帧盘面不一致）"
                }));
                return {
                    ok: true,
                    state: machine.state(),
                    reason: "杀棋佐证未通过",
                    board: observed.layout,
                    side: side,
                    unknownCells: observed.unknownCells
                };
            }
            // 有未知格的帧佐证不了：继续等（下一帧读到干净的再说）
        }
        if (side == null && observed.unknownCells === 0) {
            // 「等第二帧佐证」不算「推不出来」：它是有意暂缓一帧，下一帧就会给出结论。
            // 算进去的话自检会红（`unknownTurn` 必须为 0），真机上还会让看门狗误触发。
            if (lastInferHold) {
                note("轮次待佐证：同一个解释要在下一次观测里重复出现才采信（防模型重复框伪影）");
            } else {
                stats.unknownTurn += 1;
                if (!lastReliable) {
                    const noteCount = stats.unknownTurn;
                    if (noteCount <= 2 || noteCount % 10 === 0) {
                        note("还没有轮次基准（第 " + noteCount + " 次）：按「现在轮到我」声明一次，" +
                            "或者让局面回到标准开局");
                    }
                }
            }
        }
        // ⚠️ 双保险：不只看计数，再看盘面（计数与盘面曾不一致，见 observeOnce 末尾的归一化）——
        //   带 '?' 的盘面交给 buildFen 必抛异常，而异常被上层当「本轮异常」吞掉就是空转。
        const fen = (observed.unknownCells === 0 && observed.chars && observed.chars.indexOf("?") < 0)
            ? fenModule.buildFen(observed.chars, side === "b" ? "b" : "w")
            : null;
        const outcome = machine.dispatch({
            type: "SNAPSHOT",
            snapshot: {
                board: observed.chars,
                side: side,
                fen: fen,
                unknownCells: observed.unknownCells,
                conflicts: observed.conflicts,
                stable: true,
                geometryTrusted: true,
                // 几何版本：识别自对齐会让它 +1，票据必须拿到当前值（否则点击器核对不上直接拒）
                geometryVersion: (grid && grid.version != null) ? grid.version : null
            }
        });
        if (observed.unknownCells === 0) {
            stats.stableObservations += 1;
        } else {
            const unknownNotes = stats.stableObservations + stats.unknownTurn;
            // 未知格占了大半 ⇒ 不是「个别格子认不出」，而是整帧不对（棋盘不在画面里，
            // 或画面被我们自己的面板/画板挡住）。这种要说清楚，否则会被误当成几何问题
            // （真机曾出现过未知格 44/45：面板画板截进了帧里）。
            const polluted = observed.unknownCells >= 30;
            if (polluted || unknownNotes <= 1 || unknownNotes % 10 === 0) {
                note("本轮有 " + observed.unknownCells + " 个未知格（第 " + (stats.observations) + " 次观察）：" +
                    (observed.layout || "（布局没生成）") +
                    (polluted
                        ? "　⚠ 未知格占了大半（" + observed.unknownCells + "/90）——多半是画面被我们自己的窗口/画板挡住，" +
                          "或棋盘不在画面里：把面板挪出棋盘、关掉「识别绘制」再试"
                        : ""));
            }
        }
        pump(outcome);
        return {
            ok: true,
            state: machine.state(),
            reason: outcome.reason,
            board: observed.layout,
            side: side,
            unknownCells: observed.unknownCells
        };
    }

    function start() {
        running = true;
        pump(machine.dispatch({ type: "START" }));
        pump(machine.dispatch({ type: "READY", modelVersion: cfg.modelVersion }));
        return machine.state();
    }

    return {
        config: cfg,
        machine: machine,
        executor: executor,
        disabledTap: disabledTap,
        clickAllowed: clickAllowed,
        start: start,
        /**
         * 安全网：一轮里的异常不能把整个自动循环带走。
         * 真机踩过：从我们自己的界面刚切到游戏时截屏超时，`locateBoard` 抛异常直接结束脚本。
         * 这里只记数 + 返回 ok:false（**不**把状态机推到 ERROR），下一轮还有机会成功。
         */
        tick: function () {
            try {
                return tick();
            } catch (error) {
                stats.errors += 1;
                stats.lastError = String(error && error.message ? error.message : error);
                note("本轮异常（已跳过，下一轮重试）：" + stats.lastError);
                return { ok: false, reason: stats.lastError, state: machine.state() };
            }
        },
        isRunning: function () { return running; },
        /** 还在 worker 里算棋（界面可用它决定要不要缩短轮询间隔）。 */
        isThinking: function () { return !!pendingCompute; },
        /** 非阻塞收算棋结果：宿主自己起定时器轮询时可以直接调它。 */
        pollCompute: pollCompute,
        computeMode: function () { return computeMode; },
        pause: function () { return pump(machine.dispatch({ type: "PAUSE" })); },
        resume: function () { return pump(machine.dispatch({ type: "RESUME" })); },
        stop: function () {
            running = false;
            workerSearch.cancel();
            pendingCompute = null;
            pendingMate = null;
            knownFacts.clear();
            frameConsensus.clear();
            phantomStreak = {};
            needFreshBaseline = false;
            prevOccupancy = null;
            flipEpoch = {};
            replyFastPending = null;
            clearBaselineBorrow();
            return pump(machine.dispatch({ type: "STOP" }));
        },
        setMySide: function (side) {
            machine.setMySide(side);
            lastReliable = null;
            clearBaselineBorrow();
            trackClear("side-switch", true);
            frameConsensus.clear();
            phantomStreak = {};
            needFreshBaseline = false;
            pendingMate = null;
            planHistory.clear();
            settledRaw = null;
            prevOccupancy = null;
            flipEpoch = {};
            replyFastPending = null;
        },
        mySide: function () { return machine.mySide(); },
        /** 用户显式声明「现在轮到我」：把当前观察到的局面当作可靠基准。 */
        assertTurn: function () {
            if (!lastBoard) {
                return { ok: false, reason: "还没有识别到局面（先跑一轮观察）" };
            }
            // ⚠️ 基准里不能有未知格：它是后面「已知事实补齐」的唯一依据，
            // 带 '?' 的基准会把 '?' 当成事实抄回去（真机踩到：报告「已补齐」后 buildFen 抛异常）。
            const bad = lastBoard.indexOf("?");
            if (bad >= 0) {
                return {
                    ok: false,
                    reason: "当前局面还有未知格（第 " + (Math.floor(bad / 9) + 1) + " 行第 " + (bad % 9 + 1) +
                        " 列），不能当基准：等它认出来，或先把棋盘「菜单→重来」清成干净开局"
                };
            }
            clearBaselineBorrow();   // 人工声明 = 全新基准：对账账本作废
            lastReliable = { board: lastBoard.slice(), side: machine.mySide() };
            trackAdopt(lastBoard, machine.mySide(), { reason: "assert-turn", source: "manual-confirmed" });
            // 声明后是一张**新基准**：旧的「连续帧共识」不再适用，清掉重算
            frameConsensus.clear();
            phantomStreak = {};
            needFreshBaseline = false;
            note("用户声明轮到" + (machine.mySide() === "w" ? "红方（我方）" : "黑方（我方）"));
            // ★ 声明必须能**推动状态机**：只在 WAIT_TURN 那一条路上生效的话，
            //   一旦已经进了 RECOVERING（轮次未知看门狗），声明就再也出不来 ——
            //   真机 2026-09-20：面板日志只刷「RECOVERING｜只恢复/等待」直到 stopAfterMs。
            pump(machine.dispatch({ type: "TURN_DECLARED", reason: "用户声明轮到我" }));
            return { ok: true, reason: "已把当前局面当作基准" };
        },
        /** 换局/重启：会话 +1，旧票据与基准全部作废。 */
        newSession: function () {
            const id = machine.versions().sessionId + 1;
            machine.setSession(id);
            lastReliable = null;
            clearBaselineBorrow();
            trackClear("new-session", true);
            grid = null;
            pendingMate = null;
            planHistory.clear();
            knownFacts.clear();
            frameConsensus.clear();
            phantomStreak = {};
            needFreshBaseline = false;
            settledRaw = null;
            prevOccupancy = null;
            flipEpoch = {};
            replyFastPending = null;
            noMoveStreak = 0;
            note("新会话 #" + id + "（基准与几何已清空）");
            return id;
        },
        grid: function () { return grid; },
        setGrid: function (value) { grid = value; },
        /**
         * 面板滑条：改算棋时间预算（下一次 COMPUTE 生效，不用重启循环）。
         * 驱动自己的 cfg 与状态机的 cfg 都要改：状态机发 COMPUTE 效果时读它自己的 cfg（panel 可滑）。
         */
        setMovetime: function (ms) {
            const value = Math.round(Number(ms));
            if (!isFinite(value) || value < 100 || value > 60000) {
                return null;
            }
            cfg.movetimeMs = value;
            if (machine && machine.config) {
                machine.config.movetimeMs = value;
            }
            return value;
        },
        /** Pikafish 线程等参数：写入驱动 cfg，下次算棋生效（worker/inline 两条路都从 cfg 取）。 */
        setPikafish: function (opts) {
            cfg.pikafish = opts || null;
            return cfg.pikafish;
        },
        lastResult: function () { return lastResult; },
        lastBoard: function () { return lastBoard ? lastBoard.slice() : null; },
        lastReliable: function () {
            return lastReliable ? { board: lastReliable.board.slice(), side: lastReliable.side } : null;
        },
        /** A3：事实层快照（只读；影子期供测试/面板审计用）。 */
        tracker: function () {
            return positionTracker.snapshot();
        },
        stats: function () {
            return Object.assign({}, stats, {
                clicksByExecutor: executor.stats ? executor.stats().clicks : 0,
                disabledTapCalls: disabledTap.calls(),
                machine: machine.counters()
            });
        },
        history: function () { return history.all(); },
        describe: function () {
            const lines = [];
            lines.push("状态：" + machine.state() + "（" + machine.reason() + "）");
            lines.push("我方：" + (machine.mySide() === "w" ? "红" : "黑") +
                "  轮次基准：" + (lastReliable ? (lastReliable.side === "w" ? "红" : "黑") : "未知") +
                "  几何：" + (grid ? ("v" + grid.version) : "无"));
            const counters = machine.counters();
            lines.push("观察 " + stats.observations + " 次（未识别 " + stats.unrecognized + "）" +
                "，点击 " + (executor.stats ? executor.stats().clicks : 0) + " 次" +
                "，确认 " + counters.confirms + " 次，重试 " + counters.retries + " 次");
            lines.push("算棋：" + computeMode + "（worker " + stats.thinkOffThread +
                " 次 / 本线程 " + stats.thinkInline + " 次，最近一次 " + Math.round(stats.thinkMs) + "ms）");
            lines.push("落点补齐 " + stats.ownMovePatched + " 次（其中同字异色 " + stats.sameGlyphPatched +
                " 次）、已知事实补齐 " + stats.knownFactFills + " 次、本手漏检补齐 " + stats.freshEmptyFilled +
                " 次、按棋规改正 " + stats.ruleFixes + " 格、全盘 OCR 改正 " + stats.ocrBoardFixes +
                " 格（查 " + stats.ocrBoardChecks + " 次、待定 " + stats.ocrBoardPending +
                "、驳回 " + stats.ocrBoardBlocked + "）、单格 OCR " + stats.ocrChecks +
                " 次（确认有子 " + stats.ocrConfirmed + " / 判空格作废 " + stats.ocrRejected + "）、" +
                "催游戏 " + stats.stuckNudges + " 次（放弃无法证实的已知事实 " + stats.stuckFactsDropped + " 条）、" +
                "单格重拍 " + stats.reprobes + " 次（找回 " + stats.reprobeFilled + " 格）、" +
                "跨帧佐证暂缓 " + stats.corroborateWaits + " 次（应手 " + stats.replyHolds + " 次）、" +
                "颜色纠正 " + stats.colorFlips + " 格（游戏给刚落的子加装饰，那几格认不准）");
            if (!clickAllowed) {
                lines.push("模式：不点击（拦截了 " + disabledTap.calls() + " 次点击调用）");
            }
            return lines.join("\n");
        }
    };
}

module.exports = {
    DEFAULTS: DEFAULTS,
    createDriver: createDriver
};
