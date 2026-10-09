// @engine quickjs
"use strict";

function run(options) {
    const panelState = {};
    const panelActions = {};
    Object.assign(panelActions, require("./panel/environment.js").create(panelState, panelActions));
    Object.assign(panelActions, require("./panel/bus.js").create(panelState, panelActions));
    Object.assign(panelActions, require("./panel/settings.js").create(panelState, panelActions));
    Object.assign(panelActions, require("../ui/panel/status.js").create(panelState, panelActions));
    Object.assign(panelActions, require("../ui/panel/logging.js").create(panelState, panelActions));
    Object.assign(panelActions, require("../ui/panel/controls.js").create(panelState, panelActions));
    Object.assign(panelActions, require("../ui/panel/window.js").create(panelState, panelActions));
    Object.assign(panelActions, require("../ui/panel/parameters.js").create(panelState, panelActions));
    Object.assign(panelActions, require("../ui/panel/placement.js").create(panelState, panelActions));
    Object.assign(panelActions, require("../ui/panel/overlay.js").create(panelState, panelActions));
    Object.assign(panelActions, require("./panel/capture.js").create(panelState, panelActions));
    Object.assign(panelActions, require("./panel/probes.js").create(panelState, panelActions));
    Object.assign(panelActions, require("./panel/grid-check.js").create(panelState, panelActions));
    Object.assign(panelActions, require("./panel/calibration.js").create(panelState, panelActions));
    Object.assign(panelActions, require("./panel/foreground.js").create(panelState, panelActions));
    Object.assign(panelActions, require("./panel/driver-loop.js").create(panelState, panelActions));
    Object.assign(panelActions, require("./panel/board-service.js").create(panelState, panelActions));
    Object.assign(panelActions, require("./panel/vision.js").create(panelState, panelActions));
    Object.assign(panelActions, require("./panel/async-vision.js").create(panelState, panelActions));
    Object.assign(panelActions, require("./panel/run-control.js").create(panelState, panelActions));
    Object.assign(panelActions, require("./panel/start-flow.js").create(panelState, panelActions));
    Object.assign(panelActions, require("./panel/lifecycle.js").create(panelState, panelActions));
    Object.assign(panelActions, require("../ui/panel/compact.js").create(panelState, panelActions));
    Object.assign(panelActions, require("./panel/startup.js").create(panelState, panelActions));
    // 以下按依赖顺序初始化实例状态。

    panelState.BASE_DIR = options.baseDir;
    panelState.singleton = options.singleton;

    // 版本由两个入口共用的 src/app/version.js 提供。

    panelState.PROBE_SELF_NAME = options.selfName;
    panelState.PROBE_FILES = [
        panelState.PROBE_SELF_NAME,
        "src/app/version.js",
        "src/runtime/aijs-adapter.js",
        "src/automation/click-executor.js",
        "src/automation/auto-driver.js",
        "src/automation/search-budget.js",
        "src/automation/state-machine.js",

        "src/chess/rules.js",
        "src/chess/transition.js",
        "src/automation/move-verifier.js",
        "src/diagnostics/log-ring.js"
    ];

    panelState.PROBE_BUILD_ID = panelActions.load("src/app/version.js").VERSION;
    panelState.PROBE_SOURCE_SHA = panelActions.load("src/app/version.js").SOURCE_SHA256;

    panelState.adapter = panelActions.load("src/runtime/aijs-adapter.js");
    panelState.geometryModule = panelActions.load("src/vision/geometry.js");
    panelState.circleGridCheck = panelActions.load("src/vision/circle-grid-check.js");
    panelState.boardReaderModule = panelActions.load("src/vision/board-reader.js");
    panelState.frameGateModule = panelActions.load("src/vision/frame-gate.js");

    panelState.bootstrapModule = panelActions.load("src/app/bootstrap.js");
    panelState.pipelineFactory = panelActions.load("src/vision/pipeline-factory.js");
    panelState.locateBoard = panelActions.load("src/vision/locate-board.js");
    panelState.locateBoard.configureSift({ baseDir: panelState.BASE_DIR });
    panelState.driverModule = panelActions.load("src/automation/auto-driver.js");
    panelState.searchBudgetModule = panelActions.load("src/automation/search-budget.js");
    panelState.engineModule = panelActions.load("src/engine/engine-router.js"); // 算棋：Pikafish（原生 NNUE；内置 JS 引擎已移除）
    panelState.cellsContract = panelActions.load("src/vision/cells.js");
    // 探针的开局校验要用：FEN 解析 + 盘面比对（只读，不参与循环）
    panelState.fenModule = panelActions.load("src/chess/fen.js");
    panelState.legalModule = panelActions.load("src/chess/legal-moves.js");
    // §14 框架层：文案/令牌只定义一次（ui），配置只解析一处（app/config），日志走 diagnostics
    panelState.labelsModule = panelActions.load("src/ui/labels.js");
    panelState.themeTokens = panelActions.load("src/ui/theme-tokens.js");
    panelState.panelLayout = panelState.themeTokens.layoutFor(
        (function () {
            try {
                return panelState.adapter.screenSize();
            } catch (error) {
                return { width: 1080, height: 2400 };
            }
        })()
    );
    panelState.PANEL = panelState.panelLayout.PANEL;

    panelState.configModule = panelActions.load("src/app/config.js");
    panelState.recorderModule = panelActions.load("src/diagnostics/recorder.js");
    panelState.overlayDraw = panelActions.load("src/vision/overlay-draw.js");
    // 跨引擎互锁：主界面（main.js）里还有另一套驱动循环，两边一起跑会双份截图/算棋，

    panelState.loopLock = panelActions.load("src/automation/loop-lock.js");
    // 跨引擎互锁总线（与主界面 main.js 共用）：状态互锁 + 双向停止 + 配置双向同步。

    panelState.boardScan = panelActions.load("src/vision/board-scan.js");
    panelState.pieceDetector = panelActions.load("src/vision/piece-detector.js");

    panelState.gridSanity = panelActions.load("src/vision/grid-sanity.js");

    panelState.FRAME_DIR = panelActions.pickFrameDir();

    //  （应用缓存目录 adb 写不进去，我们自己的无人值守流程就断了）：跟截图目录同一个。
    panelState.AUTORUN_FILE = panelState.FRAME_DIR + "/floaty-autorun.json";
    // 模式/档位文案：**共享部分只在 src/ui/labels.js 定义**（面板自己更细的状态文案在下面追加）
    panelState.MODES = panelState.labelsModule.MODES;
    panelState.MODE_LABEL = panelState.labelsModule.MODE_LABEL;
    panelState.autorunBlocking = false; // autorun 有致命配置错误（如拼错的通道名）→ 不自动启动

    panelState.modeKey = "watch";
    panelState.driver = null;
    panelState.running = false;

    panelState.starting = false;
    panelState.mySideKey = "w";
    panelState.startError = "";
    panelState.startFeedbackTimer = null;
    panelState.timer = null;
    panelState.pendingDeclare = false; // autorun：等首个观察成功后再声明「现在轮到我」
    // 算棋放哪里跑：worker（独立引擎线程，默认）/ inline（本线程同步，对照用）
    panelState.computeModeKey = "worker";
    panelState.visionModeKey = "worker";
    panelState.observing = false;
    panelState.visionWorker = null;
    panelState.visionWorkerFailed = false;

    panelState.engineBackendKey = "auto";
    // Pikafish 参数：{evalFile, threads, hashMb, contempt}；null = 适配器默认（权重自动找 /sdcard/xiangqi-p4/pikafish.nnue）。
    panelState.pikafishOptions = null;

    panelState.observeFramesKey = 2;
    panelState.observeGapMsKey = 260;

    panelState.movetimeMsKey = panelState.searchBudgetModule.COMPETITIVE_TIME_MS;
    panelState.adaptiveSearchKey = true;

    panelState.searchModeKey = "time";
    panelState.searchDepthKey = 12;
    panelState.DEPTH_TIMEOUT_OPTIONS = [3000, 6000, 10000];
    panelState.depthTimeoutMsKey = panelState.DEPTH_TIMEOUT_OPTIONS[0];

    panelState.clickSettleMsKey = null;
    panelState.lastThinkingLogAt = 0; // 算棋中按秒报一次状态，不刷屏
    panelState.lastTimingLogAt = 0; // 「耗时分期」限频（方案 §7：一轮观察拆开看，每 2s 最多一行）
    panelState.lastAccountLogAt = 0; // 「本轮账」限频
    panelState.lastObsOkAt = 0; // 最近一次成功观察的时间戳（盲读兜底计时用）
    panelState.lastBlindRelocateAt = 0; // 上次「盲读兜底」强制重定位的时间戳（20s 限频）
    panelState.lastThinkBusyAt = 0; // 最近一次「算棋进行中」tick 的时间戳（看门狗不吃算棋时间，2026-09-26）

    panelState.lastCleanObsAt = 0;

    panelState.lastUnknownSpots = "";

    panelState.blindStuckCount = 0;

    panelState.tickObsCalls = 0;
    panelState.tickObsMs = 0;
    panelState.tickLocateCalls = 0;
    panelState.tickLocateMs = 0;
    panelState.tickGuardMs = 0;
    panelState.tickDriverMs = 0;

    panelState.lastKeepFrontAt = 0;

    panelState.lastFrontText = "";
    panelState.hideOnCapture = "auto"; // "auto" 只在面板真的压住棋盘时才藏（默认）；true/false 手动开/关
    panelState.autoAlignOn = false; // 几何按检测框自动微调（真机实测会把子认重，默认关）

    panelState.visionPipeline = null;

    panelState.visionDetectorSession = null;
    panelState.lastPipelineNoteAt = 0;

    panelState.frameProbeOn = false;

    panelState.replyFastPathKey = true;

    panelState.boardService = null;
    panelState.app = null; // A1：bootstrap 装配的应用（与 boardService 同源创建，会话/板服务都从这里取）

    panelState.boardFrameGate = null;
    // 点击通道：auto（有 Shizuku 就走 shizuku 的 `input swipe` 原地按住）/ shizuku / accessibility。
    // K40 + 天天象棋实测：超短按（`input tap`）会被游戏直接丢弃，按住 ≥300ms 才选中棋子。
    panelState.clickMethodKey = "auto";

    panelState.clickHideSettleMs = 120;

    panelState.releaseTapLease = null;

    panelState.clickLeaseActive = false;

    // 截图前是否暂避宿主的悬浮球（隐藏/恢复由宿主接口负责）：
    // 默认关（2026-10-04 用户要求「不用隐藏悬浮球」——球经常停在屏幕角上并不挡棋盘）。
    // 需要时在 autorun 里写 "suppressAppBall": true。
    panelState.suppressAppBall = false;

    panelState.clickPressMs = 350;
    panelState.lastLoggedLayout = null; // 驱动视角去重打印用
    panelState.aliveTimer = null; // 保活心跳（见 main 里的说明）
    panelState.aliveSeconds = 0;
    panelState.lastBeatAt = 0; // 心跳间隔：衡量「本站线程被占多久」（空载 ≈1000ms）
    panelState.lagMax = 0;
    panelState.lagCount = 0;

    panelState.SCRIPT_STARTED_AT = options.startedAt || Date.now();
    panelState.stageSeq = 0;

    panelState.autoReloadMinKey = 60;
    panelState.autoReloadNextTryAt = 0; // 起新实例失败后的重试时间戳（5 分钟一次）
    panelState.autoReloadDone = false; // 已交接：本实例进入退出流程，心跳不再做别的

    panelState.handingOff = false;

    // ==================== 跨引擎互锁总线（面板侧） ====================

    panelState.bus = panelState.bootstrapModule.createBus({
        owner: "floaty",
        files: files,
        log: function (text) {
            console.log("[bus] " + text);
        }
    });

    // 默认展开状态由 main() 启动后立即切换为收起，避免遮住棋盘顶行。
    panelState.collapsed = false;
    panelState.grid = null; // 已校准几何（会话内复用）
    panelState.calibrationNote = "未校准";
    panelState.logLines = [];

    panelState.LOG_LINES_COMPACT = panelState.themeTokens.LOG_LINES_COMPACT; // 默认 4 行（行高 ≈ 1.35×字号 ≈ 29.7px → 日志区 128px）

    panelState.STATE_LABEL = Object.assign({}, panelState.labelsModule.STATE_LABEL, {
        IDLE: "空闲",
        READY: "已就绪",
        WAIT_STABLE: "等待局面稳定",
        THINKING: "算棋中",
        PRECHECK: "出手前核对",
        SUGGESTED: "已给出建议（本模式不点击）",
        CLICKING: "点击中",
        VERIFYING: "确认落子",
        WAIT_TURN: "等轮次",
        WAIT_OPPONENT: "等对方",
        WAIT_OPPONENT_REPLY: "等对方应手",
        RECOVERING: "恢复中（重新定位）",
        PAUSED: "已暂停",
        STOPPED: "已停止"
    });
    panelState.progress = {
        turn: "", // 轮次推断结果（标准开局 / 己方 x + 对方 y）
        think: "", // 最近一次算棋结论（着法 + 评分）
        point: "", // 最近一次落点（含是否用了实测框中心）
        confirm: "", // 最近一次确认结论
        fill: "", // 最近一次未知格补齐的说明
        unknown: null, // 最近一次观察的未知格数
        recover: 0 // 恢复（重新定位）次数
    };

    panelState.lastHistoryStatus = null;

    panelState.LOG_LINES_FULL = 12; // 缓冲区保留行数（方便 console 侧对账）

    // 批量日志与诊断 JSON 交给独立常驻 IO Worker，写入 FRAME_DIR/panel-log.txt。
    // **每次面板启动重建**（只留本次运行），超 8MB 就停写（不吃爆存储）。
    panelState.PANEL_LOG_FILE = panelState.FRAME_DIR + "/panel-log.txt";
    panelState.PANEL_LOG_MAX_BYTES = 8 * 1024 * 1024;
    panelState.panelLogBytes = -1; // -1 = 尚未初始化（首次写时截断重建）
    panelState.panelLogStopped = false;
    panelState.panelLogBuf = [];
    panelState.panelLogFlushAt = 0;

    panelState.recorder = panelState.recorderModule.createRecorder({
        sink: panelActions.sinkLog,
        limit: 400,
        keep: 250
    });

    panelState.renderScheduled = false;

    panelState.C = {
        panel: "#F3F5F9",
        title: "#20252E",
        btnNeutral: "#E8EDF5",
        btnNeutralFg: "#303744",
        btnPrimary: "#3478F6",
        btnDanger: "#C94E56",
        btnAccent: "#E1EDFF",
        btnAccentFg: "#2364BE",
        btnDim: "#E7EBF2",
        btnDimFg: "#586575",
        card: "#FFFFFF",
        step: "#2F547D",
        logBg: "#F7F8FC",
        logFg: "#303744",
        status: "#667080"
    };

    panelState.UI_MODE_LABEL = { watch: "只识别", advice: "着法建议", auto: "自动走棋" };

    panelState.COMPACT_STATE_LABEL = {
        IDLE: "空闲",
        READY: "已就绪",
        WAIT_STABLE: "等稳定",
        THINKING: "算棋中",
        PRECHECK: "核对中",
        SUGGESTED: "已建议",
        CLICKING: "点击中",
        VERIFYING: "验落子",
        WAIT_TURN: "等轮次",
        WAIT_OPPONENT: "等对方",
        WAIT_OPPONENT_REPLY: "等应手",
        RECOVERING: "恢复中",
        PAUSED: "已暂停",
        STOPPED: "已停止"
    };

    panelState.HASH_OPTIONS = [64, 128, 256, 512];
    panelState.HASH_DEFAULT = 128;
    panelState.HASH_BTN_W = 148; // 参数行第三列（与「避和」各占一半；基础 px，panelPx 再按屏宽缩放）
    panelState.SEARCH_MODE_BTN_W = 136;
    panelState.hashMbKey = panelState.HASH_DEFAULT;
    panelState.hashBtnEl = null;

    panelState.CONTEMPT_OPTIONS = [0, 40, 80, 120, 160, 200];
    panelState.CONTEMPT_DEFAULT = 40;
    panelState.CONTEMPT_BTN_W = 118; // 参数行第四列（与「哈希」各占一半）
    panelState.contemptKey = panelState.CONTEMPT_DEFAULT;
    panelState.contemptBtnEl = null;

    panelState.STEP_BTN_W = 52;

    panelState.OPENING_BTN_W = 40;
    panelState.OPENING_VALUE_W = 100;

    panelState.ROW_H = panelState.PANEL.rowHeight;
    panelState.XML = [
        '<card id="panelCard" w="' +
            panelState.PANEL.width +
            'px" h="' +
            panelState.PANEL.height +
            'px" cardCornerRadius="' +
            panelState.PANEL.radiusPanel +
            'px" cardElevation="0dp" cardBackgroundColor="' +
            panelState.C.panel +
            '" clipToOutline="true">',
        '<vertical id="root" w="' +
            panelState.PANEL.width +
            'px" h="' +
            panelState.PANEL.height +
            // 三行按钮 + 间隔 + 状态 + 三行进度正好占满 380px；根内边距取 6px，避免最后一行被固定高度裁掉。
            'px" padding="' +
            panelActions.panelPx(6) +
            'px">',
        // 第 1 行：当前状态 + 模式 + 主操作（停止固定在这一行）+ 开局库步数 + 求胜开关。
        '  <horizontal id="primaryRow" h="' + panelState.ROW_H + 'px">',
        '    <text id="title" text="象棋助手" w="' +
            panelActions.panelPx(170) +
            'px" h="' +
            panelState.ROW_H +
            'px" textColor="' +
            panelState.C.title +
            '" textSize="' +
            panelState.themeTokens.FONT.title +
            '" textStyle="bold" gravity="center_vertical" includeFontPadding="false" singleLine="true" padding="6px"' + panelActions.fitText(panelState.themeTokens.FONT.title) + '/>',
        panelActions.gap(),
        panelActions.button("modeBtn", "只识别", 140, panelState.C.btnAccent),
        panelActions.gap(),
        panelActions.button("startBtn", "开始", 130, panelState.C.btnPrimary),
        panelActions.gap(),
        panelActions.button("stopBtn", "停止", 115, panelState.C.btnDanger),
        panelActions.gap(),
        panelActions.button("collapseBtn", "收起", 110, panelState.C.btnDim, panelState.C.btnDimFg),
        panelActions.gap(),
        panelActions.openingStepControl(),
        panelActions.gap(),
        panelActions.button("winSeekBtn", "求胜", 98, panelState.C.btnDim, panelState.C.btnDimFg),
        "  </horizontal>",
        panelActions.rowGap(),
        // 第 2 行：真正需要随手操作的对局按钮；诊断、自检、绘制、清日志在主界面。
        '  <horizontal id="gameRow" h="' + panelState.ROW_H + 'px">',
        panelActions.button("strategyBtn", "策略战绩", 156, panelState.C.btnAccent),
        panelActions.gap(),
        panelActions.button("locateBtn", "定位校准", 170, panelState.C.btnAccent),
        panelActions.gap(),
        panelActions.button("recognizeBtn", "识别一帧", 170, panelState.C.btnAccent),
        panelActions.gap(),
        panelActions.button("turnBtn", "轮到我", 132),
        panelActions.gap(),
        panelActions.button("sideBtn", "换我方", 132),
        panelActions.gap(),
        panelActions.button("sessionBtn", "新会话", 130, panelState.C.btnDim, panelState.C.btnDimFg),
        "  </horizontal>",
        panelActions.rowGap(),

        '  <horizontal id="paramRow" h="' + panelState.ROW_H + 'px">',
        panelActions.button(
            "searchModeBtn",
            "限时",
            panelState.SEARCH_MODE_BTN_W,
            panelState.C.btnDim,
            panelState.C.btnDimFg
        ),
        panelActions.gap(),
        panelActions.stepControl("think"),
        panelActions.gap(),
        panelActions.stepControl("threads"),
        panelActions.gap(),
        panelActions.button(
            "hashBtn",
            "哈希 " + panelState.HASH_DEFAULT + "M",
            panelState.HASH_BTN_W,
            panelState.C.btnDim,
            panelState.C.btnDimFg
        ),
        panelActions.gap(),
        panelActions.button(
            "contemptBtn",
            "避和" + panelState.CONTEMPT_DEFAULT,
            panelState.CONTEMPT_BTN_W,
            panelState.C.btnDim,
            panelState.C.btnDimFg
        ),
        "  </horizontal>",
        '<android.widget.HorizontalScrollView w="' + (panelState.PANEL.width - panelActions.panelPx(16)) + 'px" h="' + panelState.PANEL.statusHeight + 'px">',
        '<text id="status" text="准备中…" w="wrap_content" h="' + panelState.PANEL.statusHeight +
            'px" textColor="' + panelState.C.status + '" textSize="' + panelState.themeTokens.FONT.status +
            '" gravity="center_vertical" includeFontPadding="false" singleLine="true" padding="2px"' + panelActions.fitText(panelState.themeTokens.FONT.status) + '/>',
        '</android.widget.HorizontalScrollView>',
        '  <card w="' +
            (panelState.PANEL.width - panelActions.panelPx(16)) +
            'px" h="' +
            panelState.PANEL.stepHeight +
            'px" cardCornerRadius="' +
            panelState.PANEL.radiusCard +
            'px" cardElevation="0dp" cardBackgroundColor="' +
            panelState.C.card +
            '" clipToOutline="true">',
        '    <scroll id="stepScroll" fadeScrollbars="false" w="' +
            (panelState.PANEL.width - panelActions.panelPx(16)) +
            'px" h="' +
            panelState.PANEL.stepHeight +
            'px">',
        '      <text id="step" text="进度：等待开始" w="' +
            (panelState.PANEL.width - panelActions.panelPx(16)) +
            'px" h="wrap_content" textColor="' +
            panelState.C.step +
            '" textSize="' +
            panelState.themeTokens.FONT.step +
            '" includeFontPadding="false" padding="8px"/>',
        '    </scroll>',
        "  </card>",
        "</vertical>",
        "</card>"
    ].join("\n");

    panelState.LOG_XML =
        '<card id="logCard" w="' +
        panelState.PANEL.width +
        'px" h="' +
        panelState.PANEL.logHeight +
        'px" cardCornerRadius="' +
        panelState.PANEL.radiusPanel +
        'px" cardElevation="0dp" cardBackgroundColor="' +
        panelState.C.logBg +
        '" clipToOutline="true">' +
        '<vertical w="' + panelState.PANEL.width + 'px" h="' + panelState.PANEL.logHeight + 'px">' +
        '<horizontal w="' + panelState.PANEL.width + 'px" h="' + panelState.PANEL.logHeaderHeight + 'px" padding="10px" gravity="center_vertical" bg="#E5F0E7">' +
        '<text id="logTitle" text="运行日志" w="0dp" layout_weight="1" textColor="' + panelState.C.title + '" textSize="11sp" textStyle="bold" includeFontPadding="false" singleLine="true"' + panelActions.fitText(11) + '/>' +
        '<text id="logMeta" text="最近 0 条" textColor="' + panelState.C.status + '" textSize="9sp" includeFontPadding="false" gravity="center" singleLine="true"' + panelActions.fitText(9) + '/>' +
        '</horizontal>' +
        '<scroll id="logScroll" fadeScrollbars="false" w="' + panelState.PANEL.width + 'px" h="' +
        (panelState.PANEL.logHeight - panelState.PANEL.logHeaderHeight) +
        'px">' +
        '<text id="logArea" text="" w="' + panelState.PANEL.width + 'px" h="wrap_content" textColor="' +
        panelState.C.logFg + '" textSize="' + panelState.themeTokens.FONT.log +
        '" includeFontPadding="false" padding="10px" paddingBottom="14px"/>' +
        '</scroll>' +
        '</vertical></card>';

    panelState.win = null;
    panelState.compactWin = null;

    panelState.logWin = null;
    panelState.logPanelY = null;
    panelState.logPanelUserMoved = false;

    panelState.logWindowEnabled = true;
    panelState.COMPACT_W = panelState.panelLayout.compactWidth;
    panelState.COMPACT_H = panelState.panelLayout.compactHeight;
    panelState.COMPACT_XML = [
        '<card id="compactCard" w="' +
            panelState.COMPACT_W +
            'px" h="' +
            panelState.COMPACT_H +
            'px" cardCornerRadius="' +
            panelState.PANEL.radiusPanel +
            'px" cardElevation="0dp" cardBackgroundColor="' +
            panelState.C.panel +
            '" clipToOutline="true">',
        '<horizontal id="compactRoot" w="' +
            panelState.COMPACT_W +
            'px" h="' +
            panelState.COMPACT_H +
            'px" padding="' +
            panelActions.panelPx(6) +
            'px">',
        '  <text id="compactStatus" text="未运行" w="' +
            panelActions.panelPx(160) +
            'px" h="' +
            panelActions.panelPx(50) +
            'px" textColor="' +
            panelState.C.title +
            '" textSize="10" textStyle="bold" gravity="center_vertical" includeFontPadding="false" singleLine="true" paddingLeft="8px"' + panelActions.fitText(10) + '/>',
        panelActions.gap(),
        panelActions.button("compactMode", "只识别", 140, panelState.C.btnAccent, null, panelActions.panelPx(50)),
        panelActions.gap(),
        panelActions.button("compactStart", "开始", 115, panelState.C.btnPrimary, null, panelActions.panelPx(50)),
        panelActions.gap(),
        panelActions.button("compactStop", "停止", 105, panelState.C.btnDanger, null, panelActions.panelPx(50)),
        panelActions.gap(),
        panelActions.button(
            "compactExpand",
            "展开",
            105,
            panelState.C.btnDim,
            panelState.C.btnDimFg,
            panelActions.panelPx(50)
        ),
        "</horizontal>",
        "</card>"
    ].join("\n");

    panelState.panelPosBeforeHide = null;
    panelState.hideDepth = 0;
    panelState.hideViaVisibility = false;

    panelState.logPosBeforeHide = null;
    panelState.logHideViaVisibility = false;

    panelState.panelMoveCounts = { snap: 0, hide: 0, show: 0 };

    panelState.panelUserMoved = false;

    panelState.snapPanelEnabled = true;

    panelState.lastHideLogAt = 0;

    panelState.THINK_MIN_MS = 500;
    panelState.THINK_MAX_MS = 20000; // 2026-09-25：10s → 20s（残局加时；实测 10s→30s 只 +3 层，20s 是折中）

    panelState.THINK_STEP_MS = 100;
    panelState.DEPTH_MIN = 6;
    panelState.DEPTH_MAX = 24;
    panelState.THREAD_MIN = 1;
    panelState.THREAD_MAX = 8;

    panelState.HOLD_FIRST_DELAY_MS = 450;
    panelState.HOLD_REPEAT_MS = 140;
    panelState.HOLD_REPEAT_FAST_MS = 55;

    panelState.THINK_STEP_OPTIONS = [100, 500, 1000];
    panelState.thinkStepKey = panelState.THINK_STEP_MS;

    panelState.OPENING_MIN = 0;
    panelState.OPENING_MAX = 12;

    panelState.bookOwnMovesKey = 6;

    panelState.bookRandomKey = true;

    // ★ 2026-10-06：对面应手预判档位（策略菜单：关/快/标准/深）——启动时由存储/刷新覆盖
    panelState.replyGearKey = "std";

    panelState.winSeekKey = false;
    panelState.thinkValueEl = null;
    panelState.threadsValueEl = null;
    panelState.openingValueEl = null;

    panelState.PANEL_W = panelState.PANEL.width;
    panelState.PANEL_H = panelState.PANEL.height; // 必须与 XML 根节点的 h 一致（重叠判定/自动落位用）

    panelState.LOG_H = panelState.PANEL.logHeight;

    panelState.panelY = null;

    panelState.lastLogSnapNote = null;

    // ---- 识别绘制（给人审的）----

    panelState.drawMode = "moves"; // off | moves | grid | cells | full
    panelState.overlayOn = false; // 画板窗口是否已创建
    panelState.lastOverlay = null; // { items, text }：抓帧后立刻恢复用，避免屏幕闪空白

    panelState.lastClickPlan = null;

    panelState.DRAW_LABEL = panelState.labelsModule.DRAW_LABEL;
    panelState.DRAW_ORDER = panelState.labelsModule.DRAW_ORDER;

    panelState.STABLE_GRAB_GAP_MS = 130;
    panelState.STABLE_GRAB_DIFF = 6;
    panelState.STABLE_GRAB_TRIES = 4;
    panelState.lastStableGrabNoteAt = 0;

    panelState.lastSettledSamples = null; // { samples, key }：上一次返回帧的采样（抓帧家族的「参照」）
    panelState.SETTLED_CHANGE_BUDGET_MS = 800;
    panelState.SETTLED_CHANGE_GAP_MS = 120;
    panelState.SETTLED_SETTLE_TRIES = 4;
    panelState.lastChangedGrabNoteAt = 0;

    panelState.FRAME_REUSE_MS = 800;
    panelState.recentFrame = null; // { image, at, geometry }；这里拥有 image，替换/过期/停止时负责释放

    panelState.AUDIT_FRAME = panelState.FRAME_DIR + "/audit-frame.png";
    panelState.AUDIT_JSON = panelState.FRAME_DIR + "/audit-overlay.json";

    // ---------------------------------------------------------------- 单手探针（通道对照实验用）

    panelState.PROBE_JSON = panelState.FRAME_DIR + "/tap-probe.json";

    panelState.capturePermissionAttempted = false;

    panelState.GEOMETRY_REUSE_MAX_UNKNOWN = 4;

    panelState.lastPipelineUnknown = 0;

    panelState.lastCalibrationRejectAt = 0;
    panelState.CALIBRATION_RETRY_COOLDOWN_MS = 30000;

    panelState.gridKingMissStreak = 0;

    panelState.gridCondemnAt = 0;
    panelState.GRID_CONDEMN_MS = 120000;

    panelState.reuseDriftStrikes = 0;

    panelState.ANCHOR_SHIFTS = [
        [0, 0],
        [1, 0],
        [-1, 0],
        [0, 1],
        [0, -1],
        [1, 1],
        [-1, -1],
        [1, -1],
        [-1, 1]
    ];

    // ── 对局结束（结算页）检测 ────────────────────────────────────────────────

    panelState.GAME_OVER_PROBE_EVERY = 5;
    panelState.GAME_OVER_WORDS = ["再来一局", "复盘分析"];
    panelState.gameOverProbeTick = 0;
    panelState.gameOverSeen = false;

    panelState.pickLockStrikes = 0;

    panelState.OWN_PACKAGE = (function () {
        try {
            return context.getPackageName();
        } catch (error) {
            return "com.jdkshen.aijspro";
        }
    })();

    panelState.lastForeground = null;

    panelState.FG_TTL_MS = 4000;
    panelState.FG_TTL_MAX_MS = 15000;
    panelState.fgSlowNoted = false;

    panelState.foregroundFocus = panelActions.makeForegroundReader(function () {
        return typeof panelState.adapter.focusedWindowPackage === "function"
            ? panelState.adapter.focusedWindowPackage()
            : null;
    });

    panelState.foregroundTop = panelActions.makeForegroundReader(function () {
        return typeof panelState.adapter.foregroundPackage === "function"
            ? panelState.adapter.foregroundPackage()
            : null;
    });

    panelState.GAME_PACKAGE_DEFAULT = "com.tencent.qqgame.xq";
    panelState.gamePackage = panelState.GAME_PACKAGE_DEFAULT;

    panelState.depAccum = null;

    // 这里保留原函数名与语义（`ocrCell` 等仍按 `ocrRuntime()` 取会话）。
    panelState.ocrHolder = panelState.pipelineFactory.createOcrHolder({
        log: function (text) {
            panelActions.logLine(text);
        }
    });

    panelState.visionInjection = {
        pipeline: null,
        gate: null,
        samples: function (image, g) {
            return panelActions.boardRegionSamples(image, g);
        },
        countUnknown: function (chars) {
            return panelState.boardScan.countUnknownCells(chars);
        },
        log: function (text) {
            panelActions.logLine(text);
        }
    };

    // 走棋服务只跑它；规则门/未知格不过时丢弃这一帧，不回退第二种识别。
    panelState.glyphSource = panelState.pipelineFactory.createGlyphSource({
        adapter: panelState.adapter,
        confirmGeometry: panelState.locateBoard.confirmGeometry,
        baseDir: panelState.BASE_DIR,
        log: function (text) {
            panelActions.logLine(text);
        }
    });

    // A2：采样实现收进工厂（两入口共用）；面板这里保留同名包装（含面板自己的 grid 回退）。
    panelState.boardRegionSamplesCore = panelState.pipelineFactory.createSamples(panelState.adapter);

    panelState.recognizeBoard = panelState.boardReaderModule.create(panelState.glyphSource);

    //   （now42：恢复 25 次、同一格改正重复 ≥3 次、`(8,2)` 幻影清理空转 8 次；而且幻影清理

    //   读值不同就采信新值（更新缓存 + 打一行日志）。最多试 3 帧，认不出就放弃、绝不阻塞。
    // [{ i: 格下标, tries: 已复读次数 }]

    //   驱动每轮拿证据改正、下一帧又被喂回来（now43：同一格改正 3 次 + 恢复 30 次）。

    // 巡检游标（0..89）
    // 每帧最多巡检 2 格（只查“有子”的格）

    // 每 N 帧回全盘重认一次（防增量误差累积）。

    //   而不是靠重锚硬碰。

    panelState.scanRatio = 0.9;

    try {
        panelActions.main();
    } catch (error) {
        console.log("[floaty] 启动失败：" + error);
    }
}
module.exports = { run: run };
