part of 'main.dart';

final logRouteObserver = RouteObserver<ModalRoute<dynamic>>();

SystemUiOverlayStyle utilitySystemBars(BuildContext context) {
  final dark = Theme.of(context).brightness == Brightness.dark;
  return SystemUiOverlayStyle(
      statusBarColor: Colors.transparent,
      statusBarIconBrightness: dark ? Brightness.light : Brightness.dark,
      systemNavigationBarColor:
          dark ? const Color(0xff1c2022) : const Color(0xfff7f7f8),
      systemNavigationBarIconBrightness:
          dark ? Brightness.light : Brightness.dark);
}

/// 一条日志。对齐原版 `FileConsoleView$MutableLogItem`：
/// 它存的**不是原始行**，而是重拼过的 `时间戳/级别缩写: 正文`，
/// 以及解析不出的后续行（堆栈）用 `appendln` 并进来的追加内容。
class LogItem {
  LogItem(this.text, this.level);
  String text;
  final String? level;
}

/// 任务维度日志读到的一份运行日志文件内容。
class _TaskLogFile {
  _TaskLogFile(this.text);
  final String text;
}

/// 原版 LOG_PATTERN，逐字照抄（Pro log4j 格式：yyyy-MM-dd HH:mm:ss.SSS/INFO: 正文）。
final _logPattern =
    RegExp(r'^(\d+)-(\d+)-(\d+) (\d+):(\d+):(\d+).(\d+)/([A-Z]+): (.*)$');

/// AI.js Pro 引擎自己写的运行日志（ScriptRunLog）格式：
/// `15:30:12.123 [I] 正文` —— 没有年月日、级别是 [短写]。
///
/// 之前 Flutter 日志页只认上面的 Pro log4j 格式，:script 进程写出的每一行都匹配不上，
/// 按「匹配不上且没有上一条就丢弃」的规则整篇被丢空 → 页面空白、无错误文字。
final _scriptLogPattern =
    RegExp(r'^(\d{2}):(\d{2}):(\d{2})\.(\d{3}) \[([A-Z])\] (.*)$');
final _consoleLogPattern =
    RegExp(r'^(\d{2}):(\d{2}):(\d{2})\.(\d{3})/([VDIWEA]): (.*)$');
Map<String, Color> _nativeLogDark = {};
Map<String, Color> _nativeLogLight = {};

/// log4j 承认的长写级别。
const _longLevels = <String>{
  'TRACE',
  'DEBUG',
  'INFO',
  'WARN',
  'ERROR',
  'FATAL'
};

/// 原版 parseLevel 额外承认的短写。二者都映射到长写再上色。
const _shortLevels = <String, String>{
  'V': 'TRACE',
  'D': 'DEBUG',
  'I': 'INFO',
  'W': 'WARN',
  'E': 'ERROR',
  'A': 'FATAL',
};

/// 原版 FileConsoleViewKt.toShortString。
const _levelShort = <String, String>{
  'TRACE': 'V',
  'DEBUG': 'D',
  'INFO': 'I',
  'WARN': 'W',
  'ERROR': 'E',
  'FATAL': 'A',
};

/// 日志页调色板：固定浅色值在换主题后会和背景同色/对比不足（dev-964 反馈"看不到内容"），
/// 因此所有日志色都走本调色板，由 [LogPalette.of] 按 Theme.brightness 二选一：
///
/// * **深色主题**：深底亮字。等级色沿用本项目原生日志页 MiuixLogActivity 的 dark 配色
///   （V #9E9E9E / D #E6E6E6 / I #64DD17 / W #FFD54F / E #FF5252 / A #FF7043），
///   在 #14181D 背景上对比度 5.6~14.3，符合 WCAG AA；
/// * **浅色主题**：浅底深字。沿用 MiuixLogActivity 的 light 配色
///   （V #616161 / D #212121 / I #2E7D32 / W #BF360C / E #C62828 / A #B71C1C），
///   在 #F5F7F9 背景上对比度 4.8~15；
///   —— Pro 的浅色常量（如 INFO #64DD17、WARN #2962FF）在浅底对比不足，故浅色采用本 App
///   原生日志页已验证的加深值，等级语义/行格式仍与 Pro 一致。
///
/// 时间戳、RAW/文件头、提示文字、搜索高亮、下拉刷新/选择色也全部来自调色板，不再用固定值。
class LogPalette {
  final bool dark;
  final Color background;
  final Color bodyText;
  final Color trace;
  final Color debug;
  final Color info;
  final Color warn;
  final Color error;
  final Color fatal;
  final Color rawText;
  final Color timestamp;
  final Color secondaryText;
  final Color errorText;
  final Color divider;
  final Color searchHighlightBackground;
  final Color searchHighlightForeground;
  final Color refreshIndicator;
  final Color refreshBackground;
  final Color selectionColor;
  final Color cursorColor;

  const LogPalette({
    required this.dark,
    required this.background,
    required this.bodyText,
    required this.trace,
    required this.debug,
    required this.info,
    required this.warn,
    required this.error,
    required this.fatal,
    required this.rawText,
    required this.timestamp,
    required this.secondaryText,
    required this.errorText,
    required this.divider,
    required this.searchHighlightBackground,
    required this.searchHighlightForeground,
    required this.refreshIndicator,
    required this.refreshBackground,
    required this.selectionColor,
    required this.cursorColor,
  });

  /// 等级行颜色；RAW/未识别行（level==null）用 rawText。
  Color levelColor(String? level) {
    final shared = (dark ? _nativeLogDark : _nativeLogLight)[level];
    if (shared != null) return shared;
    switch (level) {
      case 'TRACE':
        return trace;
      case 'DEBUG':
        return debug;
      case 'INFO':
        return info;
      case 'WARN':
        return warn;
      case 'ERROR':
        return error;
      case 'FATAL':
        return fatal;
      default:
        return rawText;
    }
  }

  /// 跟随 App 主题选调色板（Theme.of 依赖保证换主题时 LogPage 立即重建配色）。
  static LogPalette of(BuildContext context) =>
      Theme.of(context).brightness == Brightness.dark
          ? logPaletteDark
          : logPaletteLight;
}

/// 深色主题（Pro FileConsoleView 深色观感；背景是显式半透明深色，保证不再依赖外部主题）。
const logPaletteDark = LogPalette(
  dark: true,
  background: Color(0xE614181D),
  bodyText: Color(0xFFE6E6E6),
  trace: Color(0xFF9E9E9E),
  debug: Color(0xFFE6E6E6),
  info: Color(0xFF64DD17),
  warn: Color(0xFFFFD54F),
  error: Color(0xFFFF5252),
  fatal: Color(0xFFFF7043),
  rawText: Color(0xFF9E9E9E),
  timestamp: Color(0xFF9E9E9E),
  secondaryText: Color(0xFFB0BEC5),
  errorText: Color(0xFFFF8A80),
  divider: Color(0x33FFFFFF),
  searchHighlightBackground: Color(0xFFFFD54F),
  searchHighlightForeground: Color(0xFF212121),
  refreshIndicator: Color(0xFF80CBC4),
  refreshBackground: Color(0xFF1E252B),
  selectionColor: Color(0x6680CBC4),
  cursorColor: Color(0xFF80CBC4),
);

/// 浅色主题（Pro FileConsoleView 浅色观感，等级色用可读的加深值）。
const logPaletteLight = LogPalette(
  dark: false,
  background: Color(0xE6F5F7F9),
  bodyText: Color(0xFF212121),
  trace: Color(0xFF616161),
  debug: Color(0xFF212121),
  info: Color(0xFF2E7D32),
  warn: Color(0xFFBF360C),
  error: Color(0xFFC62828),
  fatal: Color(0xFFB71C1C),
  rawText: Color(0xFF616161),
  timestamp: Color(0xFF616161),
  secondaryText: Color(0xFF5F6B76),
  errorText: Color(0xFFB3261E),
  divider: Color(0x1F000000),
  searchHighlightBackground: Color(0xFFFFE082),
  searchHighlightForeground: Color(0xFF212121),
  refreshIndicator: Color(0xFF00796B),
  refreshBackground: Color(0xFFFFFFFF),
  selectionColor: Color(0x4400796B),
  cursorColor: Color(0xFF00796B),
);

/// Pro ConsoleActions.logLevels / FileConsoleViewKt.toAndroidLogString：
/// 共 6 档（TRACE/DEBUG/INFO/WARN/ERROR/FATAL），UI 文案
/// Verbose/Debug/Info/Warn/Error/Assert（log4j FATAL 在 Android 显示为 Assert）。
/// Pro 默认 TRACE（等价「全部」，过滤语义 level.isGreaterOrEqual(selected)）；
/// log4j 的 ALL/OFF 不在日志页 UI 里（task-24 已勘误：不是八档）。
const logLevelNames = <String, String>{
  'TRACE': 'Verbose',
  'DEBUG': 'Debug',
  'INFO': 'Info',
  'WARN': 'Warn',
  'ERROR': 'Error',
  'FATAL': 'Assert',
};

String logLevelLabel(String? level) =>
    level == null ? logLevelNames['TRACE']! : (logLevelNames[level] ?? level);

/// 级别高低，用于「显示 X 及以上」过滤（原版 isGreaterOrEqual）。
const logLevelOrder = <String, int>{
  'TRACE': 0,
  'DEBUG': 1,
  'INFO': 2,
  'WARN': 3,
  'ERROR': 4,
  'FATAL': 5,
};

/// 原版 console_max_log_count 默认 10000（最小 256）。
const logMaxLines = 10000;

/// 原版 console_initial_read_file_size 默认 256 KB（最小 4 KB）。
const logWindowBytes = 256 * 1024;

/// reverse 列表里 offset≈0 就是最新一行；小于该阈值视为仍跟随最新日志。
const double _logFollowThreshold = 24;

/// 照着原版 addLog + parseLog 把一段日志文本切成要显示的条目。
///
/// 三条规则，全部来自 smali：
/// 1. 能匹配 LOG_PATTERN **且**级别被承认 → 新建一条，文字是
///    `时间戳/级别缩写: 正文`，时间戳取 `substring(start(2), end(7))`（**不含年份**）；
/// 2. 匹配不上、或级别不认识的 → 并入**上一条**（堆栈续行）；
/// 3. 没有上一条可并 → **整行丢弃**（原版 `return-object p3` 时 p3 为 null）。
List<LogItem> parseLogItems(String text) {
  final items = <LogItem>[];
  for (final raw in text.split('\n')) {
    // 原版用 RandomAccessFile.readLine()：**只去掉行终止符**，保留行内和行尾空格。
    // 这里绝不能 trimRight —— 消息为空的记录（`.../TRACE: `）去掉尾空格后
    // 就不匹配 `: ` 了，会被误判成续行而原样显示。
    final line = raw.endsWith('\r') ? raw.substring(0, raw.length - 1) : raw;

    // 格式①：Pro log4j（yyyy-MM-dd HH:mm:ss.SSS/INFO: 正文）。
    final pro = _logPattern.firstMatch(line);
    if (pro != null) {
      final levelText = pro.group(8)!;
      final level =
          _longLevels.contains(levelText) ? levelText : _shortLevels[levelText];
      if (level != null) {
        // 原版取 line.substring(start(2), end(7))：从"月"开头到"毫秒"结尾，
        // 也就是**去掉年份、到级别之前为止**。Dart 的 Match 没有按组取下标的
        // 方法（start(2) 是 Java API），所以用等价写法：砍掉第一个 '-' 之前
        // 的部分，再截到 `/{级别}: ` 之前。
        final noYear = line.substring(line.indexOf('-') + 1);
        final marker = '/$levelText: ';
        final cut = noYear.indexOf(marker);
        final stamp = cut >= 0 ? noYear.substring(0, cut) : noYear;
        final body = pro.group(9) ?? '';
        items.add(LogItem('$stamp/${_levelShort[level]}: $body', level));
        continue;
      }
    }

    // 格式②：本项目 ScriptRunLog（HH:mm:ss.SSS [I] 正文），即 :script 进程写出的格式。
    final script = _scriptLogPattern.firstMatch(line) ?? _consoleLogPattern.firstMatch(line);
    if (script != null) {
      final short = script.group(5)!;
      final level = _shortLevels[short];
      if (level != null) {
        final stamp = '${script.group(1)}:${script.group(2)}:${script.group(3)}'
            '.${script.group(4)}';
        final body = script.group(6) ?? '';
        items.add(LogItem('$stamp/$short: $body', level));
        continue;
      }
    }

    // 其余行（堆栈续行、文件头/尾分隔线）：有上一条就并进去；**没有上一条时保留为
    // 普通行**（灰色、级别未知）。旧实现直接丢弃，导致整份格式不认识的日志整页空白。
    if (items.isNotEmpty) {
      items.last.text += '\n$line';
    } else if (line.trim().isNotEmpty) {
      items.add(LogItem(line, null));
    }
  }
  return items;
}

class LogPage extends StatefulWidget {
  /// 只看某个运行中任务（任务页「查看日志」入口）：统一实时流的 token，如 `script:3` / `native:3`。
  ///
  /// 非空时页面标题为「日志 - <脚本名>」，只显示该 token 的实时行；无实时行时回退该任务最近
  /// 一次运行日志文件，再不行显示空态提示。为 null 时保持原来的「最近一次脚本运行日志文件」行为。
  final String? token;

  /// 脚本名/任务名：标题与「回退运行日志文件」时按文件名匹配用；仅 [token] 非空时生效。
  final String? name;

  const LogPage({Key? key, this.token, this.name}) : super(key: key);
  @override
  State<LogPage> createState() => _LogPageState();
}

/// 日志页。视觉/行为对齐 Auto.js Pro 的 FileConsoleView / LogView：
///
/// * 列表：Pro 是 RecyclerView + Adapter 虚拟化（global_console_view.xml），这里用
///   ListView.builder；item 整行按等级着色、文本可选（Pro 对 item TextView
///   setTextIsSelectable(true)），长按即系统选择/复制菜单；
/// * 等宽字体 + 行格式 `时间戳/级别缩写: 正文` 与 Pro parseLog 一致（task-22 已对齐）；
/// * 等级过滤：ConsoleActions.logLevels 共 6 档（Verbose/Debug/Info/Warn/Error/Assert），
///   默认 Verbose=全部，语义「>= 选中级别」；不在 Toolbar 下方再放自定义过滤条（Pro 没有）；
/// * 搜索：Pro 是 Toolbar 内 SearchView，这里展开时把 AppBar 标题替换成搜索框；
/// * 下拉刷新：Pro 是 SwipeRefreshLayout（global_console_view.xml），这里用
///   RefreshIndicator 包 ListView.builder（P1 对齐项）；
/// * 自动跟随：reverse 列表天然停在最新；用户上翻后新日志不会抢滚动位置。
///
/// 数据来源（task-28 日志统一）：
/// * 默认「实时」：统一实时流 liveLogs 增量拉取——普通页不传 token = 主进程 + :script + :native
///   全部行；任务页（「查看日志」带 token）只显示该任务的行；
/// * 无实时行时回退运行日志文件（任务页按 token 过滤该任务文件，普通页=最近一次脚本运行日志），
///   再不行显示空态提示；
/// * 顶部「实时/历史」切换：历史模式任务页读该任务运行日志文件，普通页沿用原有文件增量读取
///   （含加载更早/256KB 窗口），行为与 task-22 一致。
class _LogPageState extends State<LogPage>
    with WidgetsBindingObserver, RouteAware {
  String? error;
  bool truncated = false, loading = false;
  bool foreground = true, currentRoute = false, reread = false;
  int observationEpoch = 0;
  Future<void>? reading;
  Timer? timer;
  StreamSubscription<dynamic>? logSubscription;
  ModalRoute<dynamic>? route;
  final scroll = ScrollController();

  /// 是否跟随最新日志：reverse 列表 offset=0 时新行自然停在底部；
  /// 用户上翻后不再抢滚动，改由右下角按钮提示“有新日志/回到最新”。
  bool followTail = true;
  bool unseenLogs = false;

  /// 历史文件/任务日志“清空显示”后暂停自动重读，直到手动刷新或切模式；
  /// 否则任务历史模式 500ms 轮询会把刚清掉的文件内容立刻刷回来。
  bool historySuppressed = false;

  /// 非空 = 任务维度日志（任务页「查看日志」）：只显示该 token 的统一实时流。
  String? get taskToken {
    final value = widget.token;
    return (value == null || value.isEmpty) ? null : value;
  }

  bool get taskMode => taskToken != null;

  /// 任务模式当前是否看「历史运行日志文件」（false = 实时统一流）。
  bool historyMode = false;

  /// 任务模式当前显示的原始文本（实时行拼接，或回退的运行日志文件内容）。
  String liveText = '';

  /// 实时流增量位置：liveLogs 返回的 version，下次作为 since 传回。
  int liveVersion = 0;
  int? liveGeneration;
  bool clearingLive = false;

  /// 当前显示的是「回退的历史运行日志文件」而不是实时行。
  bool liveShowingFile = false;

  /// 用户点过「清空显示」：实时流暂时没有新行时不要立刻把回退文件又刷回来。
  bool liveSuppressFallback = false;

  /// 任务模式内容区上方的状态提示（回退/空态原因）。
  String? liveHint;

  /// 上次 applyLiveText 显示出去的提示，用来避免无变化时的 setState 重刷。
  String? appliedLiveHint;

  Future<void>? liveReading;

  /// 任务模式加载期间又发起了新请求（如刚切「实时/历史」）：等当前请求结束后补一次。
  bool liveReread = false;

  /// 尾部窗口内容（原版：首次读尾部 256 KB，之后只按增量追加）。
  String tail = '';

  /// 往上翻出来的更早内容，显示在 tail 之前。
  String earlier = '';
  int tailStart = 0;
  int earlierFrom = 0;
  int fileLength = 0;
  String? historyFilePath;

  /// 增量读取的位置：下次从第几个字节接着读。0 表示还没读过。
  int readOffset = 0;
  bool loadingEarlier = false;

  /// 解析结果缓存。必须在数据变化时算一次，而不是每次 build 都算。
  List<LogItem> parsed = const [];

  /// 缓存对应的原始文本，用来判断要不要重算。
  String parsedFrom = '';

  /// 缓存对应的级别过滤值。
  String? parsedFilter;

  /// 搜索关键字（原版 ConsoleActions.setUpSearchMenuItem）。null = 不搜索。
  String? searchText;

  /// 搜索框是否展开（Pro 是 Toolbar 里的 SearchView，这里替换 AppBar 标题）。
  bool searching = false;

  /// 缓存对应的搜索关键字。
  String? parsedSearch;

  /// 级别过滤：null = 全部（Pro 默认 TRACE=Verbose）；否则显示该级别及以上。
  String? levelFilter;

  // RouteAware can run before Navigator updates isCurrent. Use the live route
  // value so an early cached false cannot leave an on-screen log page frozen.
  bool get observing => foreground && (route?.isCurrent ?? currentRoute);

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    scroll.addListener(_onLogScroll);
    logSubscription = LocalBridge.logEvents.listen((event) {
      if (!mounted || !observing || historyMode || clearingLive) return;
      if (event is Map && event['version'] == liveVersion &&
          event['generation'] == liveGeneration) return;
      if (liveReading != null) liveReread = true;
      else loadLive(silent: true);
    }, onError: (Object error) {
      // Older hosts keep working through the bounded fallback timer.
    });
    loadLogAppearance();
    final lifecycle = WidgetsBinding.instance.lifecycleState;
    foreground = lifecycle != AppLifecycleState.paused && lifecycle != AppLifecycleState.detached;
    // 首帧后兜底：正常路径是 didChangeDependencies -> routeVisibility -> observe -> load；
    // 但如果 RouteAware/生命周期事件晚到或当前 route 判定瞬间不 current，旧实现会一直停在
    // 「无内容也无错误」的空白页（刷新按钮也会被 observing 挡住）。这里再主动触发一次，
    // 必要时（仍不可观测）强制读一次，保证首次进入一定有结果或明确错误。
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      observe();
      if (!observing && !loaded) {
        loadLive(fresh: true, force: true);
      }
    });
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final next = ModalRoute.of(context);
    if (!identical(route, next)) {
      logRouteObserver.unsubscribe(this);
      route = next;
      if (next != null) logRouteObserver.subscribe(this, next);
    }
    routeVisibility();
  }

  void routeVisibility() {
    final active = route?.isCurrent ?? true;
    if (active != currentRoute) {
      currentRoute = active;
      observe();
    }
  }

  @override
  void didPush() => routeVisibility();
  @override
  void didPushNext() => routeVisibility();
  @override
  void didPopNext() => routeVisibility();
  @override
  void didPop() {
    currentRoute = false;
    observe();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState value) {
    final active = value != AppLifecycleState.paused && value != AppLifecycleState.detached;
    if (active == foreground) return;
    foreground = active;
    observe();
  }

  void observe() {
    timer?.cancel();
    timer = null;
    ++observationEpoch;
    if (!observing) {
      reread = false;
      return;
    }
    if (historyMode) {
      // 清空历史显示后保持空态，但计时器继续跑；手动刷新解除抑制后会自然恢复轮询。
      if (historySuppressed) {
        timer = Timer.periodic(const Duration(milliseconds: 500), (_) {
          if (historySuppressed) return;
          if (taskMode) {
            loadLive(silent: true);
          } else {
            load(silent: true);
          }
        });
        return;
      }
      if (taskMode) {
        // 任务维度的历史：一次性读该任务的运行日志文件（logs 桥按 token 过滤），刷新可重读。
        loadLive(fresh: true);
        timer = Timer.periodic(const Duration(milliseconds: 500), (_) {
          if (!historySuppressed) loadLive(silent: true);
        });
        return;
      }
      // 普通日志页的历史：沿用原有文件增量读取 + 轮询，行为与旧版一致（含加载更早）。
      load(silent: tail.isNotEmpty, fresh: true);
      timer = Timer.periodic(const Duration(milliseconds: 500), (_) {
        if (!historySuppressed) load(silent: true);
      });
      return;
    }
    // 默认实时统一流（任务页=该 token 的行；普通页=全部行，token 不传）。
    loadLive(fresh: true);
    // Native notifications update immediately; this also recovers missed events
    // and supports older APKs without the event channel.
    timer = Timer.periodic(const Duration(seconds: 1), (_) {
      loadLive(silent: true);
    });
  }

  /// reverse 列表的 offset 越小越靠近最新；只有回到阈值内才恢复跟随。
  void _onLogScroll() {
    if (!mounted || !scroll.hasClients) return;
    final atLatest = scroll.offset <= _logFollowThreshold;
    if (atLatest == followTail && (!atLatest || !unseenLogs)) return;
    setState(() {
      followTail = atLatest;
      if (atLatest) unseenLogs = false;
    });
  }

  /// 新内容到达且用户仍在底部时，把 reverse 列表钉回 offset=0；
  /// 用户上翻期间不抢滚动，只标记有新行。
  ///
  /// 除了可见条数增长，也用 [contentChanged] 兜底：环形缓冲到 10000 行上限后
  /// 新行会挤掉旧行，条数不变但确实有新日志到达。
  void _noteIncoming(int previousVisible, {bool contentChanged = false}) {
    final grew = parsed.length > previousVisible;
    if (!grew && !(contentChanged && parsed.isNotEmpty)) return;
    if (!followTail) {
      unseenLogs = true;
      return;
    }
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || !followTail || !scroll.hasClients) return;
      if (scroll.offset <= 0.5) return;
      scroll.jumpTo(0);
    });
  }

  void _jumpToLatest() {
    setState(() {
      followTail = true;
      unseenLogs = false;
    });
    if (scroll.hasClients) scroll.jumpTo(0);
  }

  @override
  void dispose() {
    ++observationEpoch;
    timer?.cancel();
    logSubscription?.cancel();
    logRouteObserver.unsubscribe(this);
    scroll.dispose();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  Future<void> loadLogAppearance() async {
    try {
      final value = await LocalBridge.call('logAppearance');
      if (!mounted || value is! Map) return;
      Map<String, Color> colors(dynamic values) {
        if (values is! Map) return {};
        return {for (final key in logLevelOrder.keys)
          if (values[key] is num) key: Color((values[key] as num).toInt())};
      }
      setState(() {
        _nativeLogDark = colors(value['dark']);
        _nativeLogLight = colors(value['light']);
      });
    } catch (_) { /* Keep the existing palette with older hosts. */ }
  }

  // ---- 历史模式的文件加载（普通日志页沿用 task-22 修复后的增量/回退逻辑） ----------

  Future<void> load(
      {bool silent = false, bool fresh = false, bool force = false}) {
    if (historySuppressed && !force) return Future.value();
    if (!observing && !force) return Future.value();
    if (reading != null) {
      if (fresh) reread = true;
      return reading!;
    }
    if (fresh) readOffset = 0;
    final request = read(observationEpoch, silent: silent, force: force);
    reading = request;
    request.whenComplete(() {
      if (identical(reading, request)) reading = null;
      if (mounted && reread && observing) {
        reread = false;
        load(silent: tail.isNotEmpty, fresh: true);
      }
    });
    return request;
  }

  /// 首次无 from → 读尾部窗口；之后带 from → 只取新增字节（Pro seek(lastFileSize)）。
  Future<void> read(int epoch,
      {required bool silent, bool force = false}) async {
    // 空文件也读过一次后 loaded=true；静默轮询不要再每 500ms 亮一次 loading。
    // 已报错且没有内容时同样不闪 loading，错误条本身就是反馈。
    final showLoading = !silent || (!loaded && error == null);
    if (showLoading) setState(() => loading = true);
    try {
      final incremental = readOffset > 0;
      final value = await LocalBridge.call('logs', {
        if (incremental) 'from': readOffset else 'window': logWindowBytes,
      });
      if (!mounted || epoch != observationEpoch || (!observing && !force))
        return;
      if (value is! Map ||
          value['content'] is! String ||
          value['truncated'] is! bool) {
        throw StateError('日志数据格式异常');
      }
      final nextPath = value['path'] is String ? value['path'] as String : null;
      if (incremental && historyFilePath != null && nextPath != null && nextPath != historyFilePath) {
        historyFilePath = nextPath;
        readOffset = 0;
        reread = true;
        return;
      }
      historyFilePath = nextPath;
      final chunk = value['content'] as String;
      final nextTruncated = value['truncated'] as bool;
      final nextLength = value['length'] is num
          ? (value['length'] as num).toInt()
          : fileLength;
      final nextStart =
          value['start'] is num ? (value['start'] as num).toInt() : tailStart;
      final nextEnd =
          value['end'] is num ? (value['end'] as num).toInt() : readOffset;
      // 文件被轮转/清空：之前的位置已经失效，退回重新读一个完整窗口。
      final rewound = incremental && nextLength < readOffset;
      final appended = incremental && !rewound && chunk.isNotEmpty;
      if (!appended && !rewound && incremental) {
        // 没有新内容：什么都不改，保持选中内容和滚动位置。
        if (mounted && showLoading) setState(() => loading = false);
        return;
      }
      if (appended) {
        tail = tail + chunk;
      } else {
        tail = chunk;
        earlier = '';
        earlierFrom = 0;
      }
      if (earlier.isEmpty) earlierFrom = nextStart;
      tailStart = nextStart;
      fileLength = nextLength;
      readOffset = nextEnd;
      if (nextEnd == 0) readOffset = nextStart;
      final previousVisible = parsed.length;
      reparseIfNeeded();
      // 历史文件增量只会在末尾追加，用户上翻时不要被新行拽走。
      _noteIncoming(previousVisible, contentChanged: appended);
      setState(() {
        truncated = nextTruncated || earlierFrom > 0;
        loaded = true;
        error = null;
      });
    } catch (e) {
      if (mounted &&
          epoch == observationEpoch &&
          (observing || force) &&
          error != e.toString()) {
        setState(() => error = e.toString());
      }
    } finally {
      if (mounted && showLoading) setState(() => loading = false);
    }
  }

  // ---- 统一实时流（普通页=全部行 / 任务页=token 过滤）+ 运行日志文件回退 ---------

  /// 实时/历史的数据加载入口（普通日志页与任务日志页共用）：
  /// 默认拉统一实时流；「历史」模式普通页走原文件增量管线、任务页读该任务运行日志文件。
  Future<void> loadLive(
      {bool silent = false, bool fresh = false, bool force = false}) {
    if (clearingLive) return Future.value();
    // 历史/任务历史模式里“清空显示”之后，轮询不要再把文件内容刷回来；
    // 手动刷新或切换实时/历史会清掉这个抑制标记。
    if (historySuppressed && !force && !fresh) return Future.value();
    if (!observing && !force) return Future.value();
    if (liveReading != null) {
      if (fresh) liveReread = true;
      return liveReading!;
    }
    final epoch = observationEpoch;
    final showLoading =
        !silent || (!loaded && liveText.isEmpty && error == null);
    if (showLoading) setState(() => loading = true);
    late Future<void> request;
    request = (historyMode
            ? readTaskHistory(epoch, fresh: fresh)
            : readLiveStream(epoch, fresh: fresh, force: force))
        .whenComplete(() {
      if (identical(liveReading, request)) liveReading = null;
      if (mounted && showLoading) setState(() => loading = false);
      if (mounted && liveReread && observing) {
        liveReread = false;
        loadLive(silent: liveText.isNotEmpty);
      }
    });
    liveReading = request;
    return request;
  }

  /// 实时统一流：liveLogs 增量拉取（主进程 + :script + :native 同一落点）。
  ///
  /// 任务页传 token=只显示该任务的行；普通页不传 token=显示全部行。
  /// 无实时行时回退运行日志文件（任务页按 token 过滤、普通页=最近一次脚本运行日志）；
  /// 都拿不到就给空态提示。
  Future<void> readLiveStream(int epoch, {required bool fresh, bool force = false}) async {
    if (fresh) {
      liveVersion = 0;
      liveText = '';
      liveShowingFile = false;
      liveSuppressFallback = false;
      liveHint = null;
    }
    try {
      final value = await LocalBridge.call('liveLogs', {
        'textOnly': true,
        // 任务页只订阅该 token 的行；普通日志页不传 token = 主进程/:script/:native 全部行。
        if (taskToken != null) 'token': taskToken,
        if (liveGeneration != null) 'generation': liveGeneration,
        if (liveVersion > 0) 'since': liveVersion else 'window': logMaxLines,
      });
      if (!mounted || epoch != observationEpoch || (!observing && !force)) return;
      if (value is! Map) throw StateError('实时日志数据格式异常');
      final version = value['version'] is num
          ? (value['version'] as num).toInt()
          : liveVersion;
      final chunk =
          value['content'] is String ? value['content'] as String : '';
      final generation = value['generation'] is num
          ? (value['generation'] as num).toInt() : liveGeneration;
      final reset = value['reset'] == true ||
          (liveGeneration != null && generation != liveGeneration);
      if (reset) {
        liveText = '';
        liveShowingFile = false;
        liveSuppressFallback = true;
      }
      liveGeneration = generation;
      if (version < liveVersion) {
        // 主进程环形缓冲被清空/回绕：从头重拉，避免把新行接在旧文本后面。
        liveVersion = 0;
        liveText = '';
        liveShowingFile = false;
        return readLiveStream(epoch, fresh: true, force: force);
      }
      liveVersion = version;
      if (value['more'] == true) liveReread = true;
      if (generation != null && generation > 0 && chunk.isEmpty) liveSuppressFallback = true;
      if (chunk.isNotEmpty) {
        // 实时行来了：如果之前显示的是回退文件，整体切回实时流。
        liveText = liveShowingFile ? chunk : appendLiveText(liveText, chunk);
        liveShowingFile = false;
        liveSuppressFallback = false;
        liveHint = value['truncated'] == true ? '较早日志已超出实时缓存，可切换历史查看' : null;
        applyLiveText();
        return;
      }
      if (liveText.isEmpty && !liveSuppressFallback) {
        final file = await readRunLogFile(epoch);
        if (!mounted || epoch != observationEpoch) return;
        if (file != null) {
          liveText = file.text;
          liveShowingFile = true;
          liveHint =
              taskMode ? '实时日志暂空，显示该脚本最近一次运行日志文件' : '实时日志暂空，显示最近一次运行日志文件';
        } else {
          liveHint = taskMode ? '该任务暂无实时日志' : '暂无实时日志（脚本还没有输出）';
        }
      } else if (liveText.isEmpty) {
        liveHint = taskMode ? '该任务暂无实时日志' : '暂无实时日志（脚本还没有输出）';
      }
      applyLiveText();
    } catch (e) {
      if (!mounted || epoch != observationEpoch) return;
      final failure = e.toString();
      // 轮询偶发失败时不能把已经显示的行清空（旧实现会整页变空），先保留内容并报错。
      if (liveText.isNotEmpty) {
        liveHint = '实时日志刷新失败，保留当前内容';
        if (error != failure) setState(() => error = failure);
        return;
      }
      // 桥未实现/旧构建时不要整页报错：回退运行日志文件，再不行给空态提示。
      final file = await readRunLogFile(epoch);
      if (!mounted || epoch != observationEpoch) return;
      liveText = file?.text ?? '';
      liveShowingFile = file != null;
      if (file != null) {
        liveHint = taskMode ? '实时日志通道不可用，显示运行日志文件' : '实时日志通道不可用，显示最近一次运行日志文件';
        applyLiveText();
      } else {
        liveHint = '实时日志通道不可用，暂无日志';
        // 保留 error 避免下一轮同一错误时 applyLiveText 先清空再设回造成闪动。
        applyLiveText(clearError: false);
        if (error != failure) setState(() => error = failure);
      }
    }
  }

  /// 「历史」模式：读该任务最近一次运行日志文件（logs 桥带 name/token 过滤）。
  Future<void> readTaskHistory(int epoch, {required bool fresh}) async {
    if (fresh) {
      liveText = '';
      liveHint = null;
    }
    try {
      final file = await readRunLogFile(epoch);
      if (!mounted || epoch != observationEpoch || !observing) return;
      if (file == null) {
        liveText = '';
        liveShowingFile = true;
        liveHint = '未找到该脚本的运行日志文件';
      } else {
        liveText = file.text;
        liveShowingFile = true;
        liveHint =
            file.text.trim().isEmpty ? '该脚本的运行日志文件是空的' : '历史：该脚本最近一次运行日志文件';
      }
      applyLiveText();
    } catch (e) {
      if (!mounted || epoch != observationEpoch) return;
      final failure = e.toString();
      if (liveText.isNotEmpty) {
        liveHint = '历史日志刷新失败，保留当前内容';
        if (error != failure) setState(() => error = failure);
        return;
      }
      liveText = '';
      applyLiveText(clearError: false);
      if (error != failure) setState(() => error = failure);
    }
  }

  /// 读运行日志文件：任务页带 token 过滤（task-28 契约），普通页不传过滤参数
  /// （= 原来的「最近一次脚本运行日志」行为）。读不到返回 null，由调用方决定空态提示。
  Future<_TaskLogFile?> readRunLogFile(int epoch) async {
    try {
      // 契约（task-28）：logs 的 token 会自动映射到运行中任务名并模糊匹配运行日志文件名；
      // 主进程任务在任务页是纯数字 token（实时流里统一是 main），映射不到名字，用 name 兜底。
      final token = taskToken;
      final hostToken = token != null &&
          (token.startsWith('script:') || token.startsWith('native:'));
      final value = await LocalBridge.call('logs', {
        'window': logWindowBytes,
        if (token != null) 'token': token,
        if (!hostToken && widget.name != null && widget.name!.isNotEmpty)
          'name': widget.name,
      });
      if (!mounted || epoch != observationEpoch) return null;
      if (value is! Map || value['content'] is! String) return null;
      // 任务页：旧桥没有 token 过滤时会返回「最近一次脚本日志」（可能是别的脚本）：文件名与
      // 任务名对不上就当没找到，宁可空态也不要显示错的任务日志。task-28 新桥按名匹配，正常通过。
      // 普通页本来就要「最近一次运行日志」，不做名字守卫。
      final fileName = value['name']?.toString() ?? '';
      if (taskMode && fileName.isNotEmpty && !logNameMatches(fileName)) {
        return null;
      }
      final content = value['content'] as String;
      if (content.trim().isEmpty) return null;
      return _TaskLogFile(content);
    } catch (_) {
      return null;
    }
  }

  /// 运行日志文件名（`yyyy-MM-dd_HH-mm-ss_脚本名.log`）是否对应当前任务名。
  ///
  /// 两边都归一化成「小写字母/数字/中文」，避免 ScriptRunLog.sanitize 把空格/标点换成 `-`
  /// 后与任务名对不上。
  bool logNameMatches(String fileName) {
    final name = scriptDisplayName;
    if (name.isEmpty || fileName.isEmpty) return false;
    final needle = normalizeLogName(name);
    if (needle.isEmpty) return false;
    return normalizeLogName(fileName).contains(needle);
  }

  String normalizeLogName(String value) {
    final buffer = StringBuffer();
    for (final code in value.toLowerCase().codeUnits) {
      final digit = code >= 0x30 && code <= 0x39;
      final letter = code >= 0x61 && code <= 0x7a;
      if (digit || letter || code > 0x2e80) {
        buffer.writeCharCode(code);
      }
    }
    return buffer.toString();
  }

  /// 实时文本追加：多行拼接 + 字符上限（超长丢最旧，避免常驻任务把内存撑爆）。
  String appendLiveText(String current, String chunk) {
    final next = current.isEmpty ? chunk : '$current\n$chunk';
    final limit = 512 * 1024;
    if (next.length <= limit) return next;
    return next.substring(next.length - limit);
  }

  /// 把实时流/回退文件的原始文本交给既有解析/过滤/渲染管线（复用 6 档过滤/搜索/等级着色）。
  ///
  /// [clearError] 为 false 时保留上一轮错误（调用方随后按需设置），避免轮询失败时
  /// “先清空再设回”导致每秒重建两次。
  void applyLiveText({bool clearError = true}) {
    // 实时流每 500ms 轮询一次，没有新行时不要 setState 整页重刷。
    final contentChanged = tail != liveText && liveText.isNotEmpty;
    final unchanged = loaded &&
        tail == liveText &&
        earlier.isEmpty &&
        (clearError ? error == null : true) &&
        appliedLiveHint == liveHint;
    final previousVisible = parsed.length;
    tail = liveText;
    earlier = '';
    earlierFrom = 0;
    truncated = false;
    reparseIfNeeded();
    _noteIncoming(previousVisible, contentChanged: contentChanged);
    if (unchanged) return;
    appliedLiveHint = liveHint;
    if (mounted) {
      setState(() {
        loaded = true;
        if (clearError) error = null;
      });
    }
  }

  /// 任务模式标题里的脚本名：去掉目录与 .js 扩展名，只留文件名。
  String get scriptDisplayName {
    final raw = widget.name?.trim() ?? '';
    if (raw.isEmpty) return '';
    var name = raw.split(RegExp(r'[\\/]')).last;
    if (name.toLowerCase().endsWith('.js')) {
      name = name.substring(0, name.length - 3);
    }
    return name;
  }

  /// 实时流 ↔ 运行日志文件（历史）切换：两种模式在任务页/普通页都可用，
  /// 切换时把实时增量位置与文件读取位置一起复位，再按新模式重新加载。
  void toggleHistory() {
    historyMode = !historyMode;
    historySuppressed = false;
    liveText = '';
    liveVersion = 0;
    liveGeneration = null;
    liveShowingFile = false;
    liveSuppressFallback = false;
    liveHint = null;
    liveReread = false;
    followTail = true;
    unseenLogs = false;
    // 历史模式的文件增量状态（普通页用；任务页历史是一次性读文件，同样复位更稳）。
    tail = '';
    earlier = '';
    earlierFrom = 0;
    readOffset = 0;
    fileLength = 0;
    tailStart = 0;
    parsedFrom = '';
    parsed = const [];
    truncated = false;
    loadingEarlier = false;
    error = null;
    loaded = false;
    setState(() {});
    observe();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted && scroll.hasClients) scroll.jumpTo(0);
    });
  }

  /// 往前翻一屏（Pro 用 SwipeRefreshLayout 重载首屏，这里保留 task-22 的增量加载）。
  Future<void> loadEarlier() async {
    // 只有「普通页 + 历史（文件增量）」才有字节窗口可回翻；实时流与任务维度没有。
    if (!historyMode || taskMode) return;
    final end = earlierFrom > 0 ? earlierFrom : tailStart;
    if (loadingEarlier || end <= 0) return;
    setState(() => loadingEarlier = true);
    try {
      final value = await LocalBridge.call(
          'logs', {'window': logWindowBytes, 'end': end});
      if (!mounted) return;
      if (value is! Map || value['content'] is! String) {
        throw StateError('日志数据格式异常');
      }
      final chunk = value['content'] as String;
      final start = value['start'] is num ? (value['start'] as num).toInt() : 0;
      earlier = chunk + earlier;
      earlierFrom = start;
      reparseIfNeeded();
      setState(() {
        truncated = start > 0;
        error = null;
      });
    } catch (e) {
      if (mounted) message(context, e.toString());
    } finally {
      if (mounted) setState(() => loadingEarlier = false);
    }
  }

  /// 只在原始文本真的变了才重新解析，并把条目总数压到上限内（Pro console_max_log_count=10000）。
  void reparseIfNeeded() {
    final source = earlier + tail;
    if (source == parsedFrom &&
        levelFilter == parsedFilter &&
        searchText == parsedSearch) {
      return;
    }
    parsedFrom = source;
    parsedFilter = levelFilter;
    parsedSearch = searchText;
    final items = parseLogItems(source);
    final capped = items.length > logMaxLines
        ? items.sublist(items.length - logMaxLines)
        : items;
    final floor =
        levelFilter == null ? null : (logLevelOrder[levelFilter] ?? 0);
    final keyword = searchText;
    // 级别过滤 + 搜索过滤后的结果直接作为要显示的列表（Pro 也是过滤后再进 RecyclerView）。
    parsed = capped.where((item) {
      if (floor != null && (logLevelOrder[item.level] ?? 0) < floor)
        return false;
      if (keyword != null &&
          keyword.isNotEmpty &&
          !item.text.contains(keyword)) {
        return false;
      }
      return true;
    }).toList();
  }

  List<LogItem> get visibleLines => parsed;

  bool get ready => tail.isNotEmpty || earlier.isNotEmpty;

  /// 成功读过一次（即便读到的是空文件）——用来区分"还没读到"和"确实没有日志"。
  bool loaded = false;

  // ---- 工具栏动作 -------------------------------------------------------

  /// Pro ConsoleActions 的等级 Spinner：6 档，下拉文案 Verbose/Debug/Info/Warn/Error/Assert，
  /// 默认 Verbose（TRACE=全部）。Toolbar 位置紧张，收起状态显示级别缩写。
  Widget _levelButton() {
    final effective = levelFilter ?? 'TRACE';
    return PopupMenuButton<String>(
      tooltip: '日志级别（6 档：Verbose/Debug/Info/Warn/Error/Assert）',
      initialValue: effective,
      onSelected: _setLevel,
      itemBuilder: (context) {
        final palette = LogPalette.of(context);
        return [
          for (final level in logLevelOrder.keys)
            CheckedPopupMenuItem<String>(
              value: level,
              checked: level == effective,
              height: 44,
              child: Text(logLevelNames[level]!,
                  style: TextStyle(
                      fontSize: 16, color: palette.levelColor(level))),
            ),
        ];
      },
      child: Builder(builder: (context) {
        final palette = LogPalette.of(context);
        return Padding(
          padding: const EdgeInsets.symmetric(horizontal: 6),
          child: Row(mainAxisSize: MainAxisSize.min, children: [
            Text(logLevelNames[effective] ?? 'Verbose',
                style: TextStyle(
                    fontSize: 14, color: palette.levelColor(effective))),
            Icon(Icons.arrow_drop_down, size: 20, color: palette.bodyText),
          ]),
        );
      }),
    );
  }

  void _setLevel(String level) {
    // 默认档 TRACE 等价于「全部」，UI 上仍显示 Verbose。
    levelFilter = level == 'TRACE' ? null : level;
    unseenLogs = false;
    reparseIfNeeded();
    setState(() {});
  }

  void _toggleSearch() {
    setState(() {
      searching = !searching;
      if (!searching) {
        searchText = null;
        reparseIfNeeded();
      }
    });
  }

  Future<void> _clearDisplay() async {
    // 只清显示，不删日志文件：实时模式清统一缓冲；历史/任务历史模式只清本地列表。
    if (!historyMode) {
      ++observationEpoch;
      clearingLive = true;
      try {
        final result = await LocalBridge.call('clearLiveLogs');
        if (!mounted) return;
        if (result is! Map || result['cleared'] != true) {
          throw StateError('清空结果未确认');
        }
        liveVersion = (result['version'] as num?)?.toInt() ?? liveVersion;
        liveGeneration =
            (result['generation'] as num?)?.toInt() ?? liveGeneration;
      } catch (e) {
        if (mounted) message(context, '清空日志失败：$e');
        return;
      } finally {
        clearingLive = false;
      }
      // 实时模式：只清本地显示；下次从当前 version 继续增量，不会把旧行刷回来；
      // 同时暂时禁用「无实时行回退文件」，免得清空后回退文件又被重新加载。
      liveText = '';
      liveShowingFile = false;
      liveSuppressFallback = true;
      liveHint = null;
    } else {
      // 任务历史模式每 500ms 会重读运行日志文件；不暂停的话清空后会立刻刷回来。
      historySuppressed = true;
      liveHint = '已清空显示（日志文件未删除）；下拉刷新可重新读取';
    }
    setState(() {
      followTail = true;
      unseenLogs = false;
      appliedLiveHint = null;
      tail = '';
      earlier = '';
      earlierFrom = 0;
      tailStart = 0;
      readOffset = 0;
      fileLength = 0;
      truncated = false;
      loadingEarlier = false;
      parsedFrom = '';
      parsed = const [];
      reparseIfNeeded();
    });
    if (!historyMode) loadLive(silent: true);
    if (mounted) {
      message(context,
          historyMode ? '已清空显示（日志文件未删除）' : '已清空日志显示（不删除日志文件）');
    }
  }

  Future<void> _refresh() {
    historySuppressed = false;
    if (!historyMode) return loadLive(fresh: true, force: true);
    if (taskMode) return loadLive(fresh: true, force: true);
    return load(fresh: true, force: true);
  }

  Future<void> _copyAll() async {
    if (visibleLines.isEmpty) {
      if (mounted) message(context, '当前没有可复制的日志');
      return;
    }
    try {
      await Clipboard.setData(ClipboardData(text: _visibleText));
      if (mounted) {
        message(context, '已复制 ${visibleLines.length} 条显示日志');
      }
    } catch (e) {
      if (mounted) message(context, '复制日志失败：$e');
    }
  }

  String get _visibleText => visibleLines.map((item) => item.text).join('\n');

  Future<void> _shareLogs() async {
    if (visibleLines.isEmpty) {
      if (mounted) message(context, '当前没有可分享的日志');
      return;
    }
    try {
      await LocalBridge.call('shareLogText', {'text': _visibleText});
    } catch (e) {
      if (mounted) message(context, '分享日志失败：$e');
    }
  }

  Widget _moreLogActions() => PopupMenuButton<String>(
        tooltip: '更多日志操作',
        onSelected: (value) {
          if (value == 'copy') _copyAll();
          if (value == 'refresh') _refresh();
        },
        itemBuilder: (context) => [
          PopupMenuItem<String>(
              value: 'copy',
              enabled: visibleLines.isNotEmpty,
              child: Text('复制显示日志（${visibleLines.length} 条）')),
          PopupMenuItem<String>(
              value: 'refresh', enabled: !loading, child: const Text('刷新日志')),
        ],
        child:
            const SizedBox(width: 36, height: 48, child: Icon(Icons.more_vert)),
      );

  Widget _buildTitle(BuildContext context, LogPalette palette) {
    if (!searching) {
      if (taskMode) {
        final name = scriptDisplayName;
        return Text(name.isEmpty ? '日志' : '日志 - $name');
      }
      return const Text('日志');
    }
    // Pro 是 Toolbar 内的 SearchView（actionViewClass=SearchView）：展开时占住标题位。
    // 输入文字/光标/hint 全用调色板，两种主题下都可读。
    return TextField(
      autofocus: true,
      style: TextStyle(fontSize: 15, color: palette.bodyText),
      cursorColor: palette.cursorColor,
      onChanged: (value) {
        setState(() {
          searchText = value.isEmpty ? null : value;
          unseenLogs = false;
          reparseIfNeeded();
        });
      },
      decoration: InputDecoration(
        isDense: true,
        border: InputBorder.none,
        hintText: '搜索日志内容',
        hintStyle: TextStyle(fontSize: 15, color: palette.secondaryText),
      ),
    );
  }

  // ---- 界面 ------------------------------------------------------------

  @override
  Widget build(BuildContext context) {
    // Theme.of(context) 建立依赖：系统/App 切换深色浅色主题时本页立即重建并换调色板。
    final palette = LogPalette.of(context);
    return GlassBackground(
        child: Scaffold(
            appBar: AppBar(
                systemOverlayStyle: utilitySystemBars(context),
                foregroundColor: palette.bodyText,
                leadingWidth: 48,
                leading: BackButton(onPressed: () {
                  if (Navigator.of(context).canPop()) Navigator.pop(context);
                  else SystemNavigator.pop();
                }),
                titleSpacing: 0,
                title: _buildTitle(context, palette),
                // Pro 的 SearchView 展开时会收起其它 action；这里搜索态也只留关闭按钮，
                // 把标题区完整让给搜索框，避免 360dp 宽度下标题被挤没。
                actions: searching
                    ? [
                        ActionIcon(
                            tooltip: '关闭搜索',
                            icon: const Icon(Icons.close),
                            onPressed: _toggleSearch),
                      ]
                    // 高级版的等级下拉 / 搜索 / 清空 / 分享；额外动作收入菜单以适配窄屏。
                    : [
                        _levelButton(),
                        ActionIcon(
                            tooltip: '搜索日志',
                            icon: const Icon(Icons.search),
                            width: 36,
                            onPressed: _toggleSearch),
                        ActionIcon(
                            tooltip: '清空日志显示',
                            icon: const Icon(Icons.delete_outline),
                            width: 36,
                            onPressed: _clearDisplay),
                        ActionIcon(
                            tooltip: '分享显示日志',
                            icon: const Icon(Icons.share),
                            width: 36,
                            onPressed:
                                visibleLines.isNotEmpty ? _shareLogs : null),
                        _moreLogActions(),
                      ]),
            body: Column(children: [
              if (error != null)
                Padding(
                    padding: const EdgeInsets.fromLTRB(12, 8, 4, 0),
                    child: Row(children: [
                      Expanded(
                          child: Text(
                              ready ? '读取日志失败，保留上次日志：$error' : '读取日志失败：$error',
                              style: TextStyle(
                                  fontSize: 12, color: palette.errorText))),
                      TextButton(
                          onPressed: _refresh,
                          child:  const Text('重试')),
                    ])),
              if (truncated)
                Padding(
                    padding: const EdgeInsets.fromLTRB(12, 4, 4, 0),
                    child: Row(children: [
                      Expanded(
                          child: Text(
                              '显示最近 ${logWindowBytes ~/ 1024} KB；可往回翻，完整日志保留在本机',
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: TextStyle(
                                  fontSize: 12, color: palette.secondaryText))),
                      TextButton(
                          onPressed: loadingEarlier ? null : loadEarlier,
                          child: Text(loadingEarlier ? '读取中…' : '加载更早')),
                    ])),
              // 窄屏 360dp 下 AppBar 放不下第 6 个 action（5 项 = task-25 真机验证可放下）：
              // 实时/历史切换挪到内容区上方，普通页与任务页都常显；回退/空态提示合并到同一行。
              Padding(
                  padding: const EdgeInsets.fromLTRB(12, 2, 6, 0),
                  child: Row(children: [
                    Expanded(
                        child: Text(
                            liveHint ?? (historyMode ? '历史：运行日志文件' : '实时日志'),
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                                fontSize: 12, color: palette.secondaryText))),
                    if (ready && visibleLines.isNotEmpty) ...[
                      const SizedBox(width: 6),
                      Text('${visibleLines.length} 条',
                          key: const ValueKey('log-visible-count'),
                          style: TextStyle(
                              fontSize: 11, color: palette.secondaryText)),
                    ],
                    const SizedBox(width: 2),
                    ActionIcon(
                        tooltip: historyMode ? '切换到实时日志' : '切换到运行日志文件（历史）',
                        icon: Icon(historyMode ? Icons.sensors : Icons.history,
                            size: 20),
                        onPressed: toggleHistory,
                        width: 36,
                        height: 36),
                  ])),
              Expanded(
                  key: const ValueKey('log-content-area'),
                  child: _buildContent(context, palette)),
            ]),
            // 上翻查看旧日志时新行不抢滚动；按钮提示有新日志并一键回到底部。
            floatingActionButton: !followTail && visibleLines.isNotEmpty
                ? FloatingActionButton.extended(
                    key: const ValueKey('log-follow-latest'),
                    heroTag: 'log-follow-latest',
                    tooltip: unseenLogs ? '有新日志，回到最新' : '回到最新日志',
                    elevation: 2,
                    onPressed: _jumpToLatest,
                    icon: const Icon(Icons.arrow_downward, size: 18),
                    label: Text(unseenLogs ? '有新日志' : '回到最新',
                        style: const TextStyle(fontSize: 12)),
                  )
                : null));
  }

  // ---- 内容渲染（调色板 + 搜索高亮） ------------------------------------

  Widget _buildContent(BuildContext context, LogPalette palette) {
    if (!ready && !loaded) {
      return _stateView(
          palette,
          error == null
              ? CircularProgressIndicator(color: palette.refreshIndicator)
              : Text('日志暂不可读，点刷新重试',
                  style: TextStyle(color: palette.secondaryText)));
    }
    if (!ready) {
      if (!historyMode) {
        return _stateView(
            palette,
            Text(liveHint ?? (taskMode ? '该任务暂无日志' : '暂无实时日志（脚本还没有输出）'),
                textAlign: TextAlign.center,
                style: TextStyle(color: palette.secondaryText)));
      }
      if (taskMode) {
        return _stateView(
            palette,
            Text(liveHint ?? '该任务暂无运行日志',
                textAlign: TextAlign.center,
                style: TextStyle(color: palette.secondaryText)));
      }
      return _stateView(
          palette,
          Text(liveHint ?? '暂无运行日志',
              textAlign: TextAlign.center,
              style: TextStyle(color: palette.secondaryText)));
    }
    if (visibleLines.isEmpty) {
      return _stateView(
          palette,
          Text(
              searchText != null && searchText!.isNotEmpty
                  ? '没有匹配“$searchText”的日志'
                  : levelFilter != null
                      ? '没有达到 ${logLevelNames[levelFilter]} 级别的日志'
                      : '暂无日志',
              textAlign: TextAlign.center,
              style: TextStyle(color: palette.secondaryText)));
    }
    final theme = Theme.of(context);
    // P1：Pro 的 SwipeRefreshLayout（global_console_view.xml）→ RefreshIndicator + ListView.builder。
    // 原版是 RecyclerView：虚拟化，只排版可见行；reverse 让最新的在底部。
    // 背景/文字/刷新指示器/选择色全部来自调色板，避免跟随系统主题时同色不可读。
    return Container(
      color: palette.background,
      child: Theme(
        data: theme.copyWith(
          textSelectionTheme: TextSelectionThemeData(
            cursorColor: palette.cursorColor,
            selectionColor: palette.selectionColor,
            selectionHandleColor: palette.refreshIndicator,
          ),
        ),
        child: RefreshIndicator(
          color: palette.refreshIndicator,
          backgroundColor: palette.refreshBackground,
          onRefresh: _refresh,
          child: ListView.builder(
            key: const ValueKey('log-scroll'),
            controller: scroll,
            reverse: true,
            physics: const AlwaysScrollableScrollPhysics(),
            padding: const EdgeInsets.all(12),
            itemCount: visibleLines.length,
            itemBuilder: (context, index) {
              final item = visibleLines[visibleLines.length - 1 - index];
              return SelectableText.rich(
                _logLineSpan(item, palette),
                style: const TextStyle(
                    fontFamily: 'monospace',
                    // 日志正文以中文为主；回退列表见 mono_font.dart。
                    fontFamilyFallback: monoFontFallback,
                    fontSize: 12,
                    height: 1.5),
              );
            },
          ),
        ),
      ),
    );
  }

  Widget _stateView(LogPalette palette, Widget child) => Container(
        color: palette.background,
        child: Center(child: child),
      );

  /// 一条日志的富文本：时间戳/级别前缀用次级色，正文用等级色；搜索时命中片段加高亮背景。
  /// 颜色全部来自调色板，不再用固定 Color(0x...) 直接画字（换主题后仍可读）。
  TextSpan _logLineSpan(LogItem item, LogPalette palette) {
    final text = item.text;
    final levelColor = palette.levelColor(item.level);
    // 行格式由 parseLogItems 生成：「时间戳/级别缩写: 正文」；RAW/文件头行没有该前缀。
    final match = RegExp(r'^([^\n]*?/[A-Z]: )').firstMatch(text);
    final prefix = match == null ? '' : match.group(1)!;
    final body = match == null ? text : text.substring(prefix.length);
    final spans = <TextSpan>[];
    if (prefix.isNotEmpty) {
      spans.addAll(_highlightSpans(prefix, palette.timestamp, palette));
    }
    if (body.isNotEmpty || prefix.isEmpty) {
      spans.addAll(_highlightSpans(body, levelColor, palette));
    }
    return TextSpan(children: spans);
  }

  /// 搜索命中高亮：命中片段用「高亮底 + 深色字」，深/浅主题下对比度都 >11。
  List<TextSpan> _highlightSpans(String text, Color color, LogPalette palette) {
    final keyword = searchText;
    if (!searching || keyword == null || keyword.isEmpty) {
      return [TextSpan(text: text, style: TextStyle(color: color))];
    }
    final spans = <TextSpan>[];
    var start = 0;
    while (start < text.length) {
      final index = text.indexOf(keyword, start);
      if (index < 0) break;
      if (index > start) {
        spans.add(TextSpan(
            text: text.substring(start, index),
            style: TextStyle(color: color)));
      }
      spans.add(TextSpan(
        text: text.substring(index, index + keyword.length),
        style: TextStyle(
          color: palette.searchHighlightForeground,
          backgroundColor: palette.searchHighlightBackground,
        ),
      ));
      start = index + keyword.length;
    }
    if (start < text.length) {
      spans.add(TextSpan(
          text: text.substring(start), style: TextStyle(color: color)));
    }
    if (spans.isEmpty) {
      spans.add(TextSpan(text: text, style: TextStyle(color: color)));
    }
    return spans;
  }
}

Future<void> openApiDocumentation(BuildContext context) async {
  try {
    await LocalBridge.call('openUrl', {
      'url': 'file:///android_asset/docs/api-reference.html',
    });
  } catch (error) {
    if (context.mounted) message(context, '打开 API 文档失败：$error');
  }
}

class GuidePage extends StatelessWidget {
  const GuidePage({Key? key}) : super(key: key);
  @override
  Widget build(BuildContext context) => GlassBackground(
    child: Scaffold(
      appBar: AppBar(
        systemOverlayStyle: utilitySystemBars(context),
        foregroundColor: Theme.of(context).colorScheme.onSurface,
        title: const Text('本地使用指南'),
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Padding(
            padding: const EdgeInsets.only(bottom: 12),
            child: FilledButton.icon(
              onPressed: () => openApiDocumentation(context),
              icon: const Icon(Icons.menu_book_outlined),
              label: const Text('三引擎 API 文档 · 离线搜索'),
            ),
          ),
          const _GuideEntry(
            'API 文档',
            '按 Rhino、QuickJS、Node.js 选择引擎，搜索方法名或用途，展开查看参数、返回值、代码和兼容差异。文档已内置，断网也能使用；传统 API 与 Node 专题分开标注。',
          ),
          const _GuideEntry(
            '脚本和示例',
            '点击名称打开文件，长按或点击右侧更多进行重命名、分享或删除。右下角新建菜单可创建文件、文件夹和 Rhino / Node.js 项目，也可导入文件或 ZIP。删除的内容可在设置的回收站恢复。示例只读，可复制文件或整个目录到脚本目录后修改。',
          ),
          _GuideEntry(
            '编辑和运行',
            '顶部 <> 打开工作区，文件树可打开多个文档。切换标签保留各自草稿、光标和撤销记录，可全部保存。运行前保存当前脚本；保存失败会保留内容并显示错误。搜索定位代码，底部符号栏在光标处插入字符。Rhino、QuickJS 和 Node.js 使用本机脚本引擎。',
          ),
          _GuideEntry(
            '资源和插件',
            '资源来自本机目录，可搜索、分类、导入脚本或 ZIP；点击保存按钮解包到脚本目录。“我的”显示本机导入记录。同名内容保留，可换名后导入。插件卡片显示本机已安装的 SDK 插件；安装本地 APK 后需完成系统安装确认。',
          ),
          _GuideEntry(
            '设备权限',
            '权限开关会进入系统设置，返回后同步真实授权状态。设备连接页的“Shizuku 启动无障碍”可通过已启动的 Shizuku 授权并开启本应用的无障碍服务；首次使用在 Shizuku 弹窗中允许，拒绝时不会修改无障碍设置。',
          ),
          _GuideEntry(
            'MCP 服务',
            '侧栏“开发”里的 MCP 开关控制本应用的自建服务。点击“MCP 服务”打开管理页，可查看真实运行状态、实际端口、心跳和错误，并复制本机/局域网地址与客户端配置。默认端口 8765，被占用时自动顺延到 8795，实际端口以管理页为准；服务开启后监听 0.0.0.0，完全免令牌，请只在可信 WiFi 使用。客户端填写 http://…/mcp 即可；可用工具以服务返回的工具目录为准。脚本执行和语法检查使用本机引擎，读取控件和截屏需要无障碍服务。',
          ),
          _GuideEntry(
            '连接电脑',
            '电脑连接使用 AI.js Pro 自家的 VS Code 扩展：电脑端扩展监听 8327，手机端服务器使用 9317。两端需网络互通，电脑防火墙要放行对应端口；连接状态以设备连接页显示为准。',
          ),
          _GuideEntry(
            '任务管理',
            '第五个导航图标打开管理页。列表显示正在运行的 Rhino、QuickJS、Node.js 脚本，以及已有的定时和意图任务，可搜索、折叠分组、查看路径、停止脚本或取消计划。右下角按钮停止确认时的全部运行脚本。管理页右上角也可打开设备连接。',
          ),
          _GuideEntry(
            '运行日志',
            '工具栏、设置页和悬浮条都打开同一个 Flutter「运行日志」页：默认显示统一实时流（主进程 + :script + :native），可切「实时/历史」；支持 6 档等级过滤、搜索、复制和清空。任务页每个运行中任务的日志图标会带上任务 token，只显示该任务的输出。上翻查看旧日志时不会被新日志拽回，右下角“有新日志/回到最新”可回到最新；清空只清显示，不删日志文件。读取失败保留上次日志并可重试。',
          ),
          _GuideEntry(
            '导航',
            '点击图标选择页面，或按住导航横向拖动。拖动距离控制页面进度，松手后停在最近一页。内容区域也支持左右滑动，目录和列表滚动位置会保留。',
          ),
        ],
      ),
    ),
  );
}

class _GuideEntry extends StatelessWidget {
  final String title, body;
  const _GuideEntry(this.title, this.body);
  @override
  Widget build(BuildContext context) => Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: GlassSurface(
          blur: false,
          child:
              Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(title, style: const TextStyle(fontSize: 18)),
            const SizedBox(height: 8),
            Text(body, style: const TextStyle(fontSize: 14, height: 1.6))
          ])));
}
