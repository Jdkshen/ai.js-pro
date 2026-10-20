import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter/foundation.dart'
    show LicenseRegistry, LicenseEntryWithLineBreaks;
import 'bridge.dart';
import 'glass.dart';
import 'glass_navigation.dart';
import 'workspace_models.dart';
import 'code_editing.dart';
import 'mono_font.dart';
part 'browser.dart';
part 'utility_pages.dart';
part 'task_page.dart';
part 'catalog_page.dart';
part 'computer_connection.dart';
part 'service_drawer.dart';
part 'settings_page.dart';
part 'device_page.dart';
part 'permission_guide.dart';
part 'project_page.dart';

/// 单实例页面导航：按 [RouteSettings.name] 登记导航栈里的页面。
///
/// 工具栏「运行日志」、设置页「运行日志」、任务页日志图标、悬浮条 pendingAction=log
/// 都可能打开同一个页面；连点或从不同入口重复打开时不该无限叠加——[pushOnce] 会先
/// 回到栈里已有的同名页面，没有再 push。原生 Ace 编辑器不是 Flutter 路由，
/// 用 [NativeEditorGate] 单独做门闩。
class OnceRoutes extends NavigatorObserver {
  static final Map<String, Route<dynamic>> _open = <String, Route<dynamic>>{};

  /// 已有同名页面则回到它并返回 true；否则返回 false，由调用方 push。
  static bool reveal(BuildContext context, String name) {
    final route = _open[name];
    if (route == null || !route.isActive) return false;
    final navigator = Navigator.of(context);
    if (route.isCurrent) return true;
    // 页面还在栈里但被压在别的页面下面：pop 回已有页面，而不是再叠一层。
    navigator.popUntil((candidate) => identical(candidate, route));
    return true;
  }

  static void _track(Route<dynamic>? route) {
    final name = route?.settings.name;
    if (name == null || name.isEmpty) return;
    _open[name] = route!;
  }

  static void _untrack(Route<dynamic>? route) {
    final name = route?.settings.name;
    if (name == null || name.isEmpty) return;
    if (identical(_open[name], route)) _open.remove(name);
  }

  @override
  void didPush(Route<dynamic> route, Route<dynamic>? previousRoute) =>
      _track(route);
  @override
  void didPop(Route<dynamic> route, Route<dynamic>? previousRoute) =>
      _untrack(route);
  @override
  void didRemove(Route<dynamic> route, Route<dynamic>? previousRoute) =>
      _untrack(route);
  @override
  void didReplace({Route<dynamic>? newRoute, Route<dynamic>? oldRoute}) {
    _untrack(oldRoute);
    _track(newRoute);
  }
}

/// 全局单实例路由观察者（[MaterialApp.navigatorObservers] 注册一次）。
final onceRoutes = OnceRoutes();

/// 单实例打开页面：同一 [name] 已打开时回到它，不重复 push。
/// [name] 需稳定（如 'log' / 'log:script:3'），供 [OnceRoutes] 识别同一个页面。
Future<void> pushOnce(
    BuildContext context, String name, WidgetBuilder builder) async {
  if (OnceRoutes.reveal(context, name)) return;
  await Navigator.of(context).push(MaterialPageRoute<void>(
      settings: RouteSettings(name: name), builder: builder));
}

/// 原生 Ace 编辑器门闩。
///
/// 编辑器是宿主 Activity，不在 Flutter 路由栈里（Dart 侧无法枚举 Activity），连点文件/
/// 工具按钮会把 Activity 叠多层。这里用「一次只允许拉起一个」的门闩挡住重复启动；
/// Flutter 回到前台（即编辑器已关闭）时 [release] 放行，打开失败也会立即放行。
class NativeEditorGate {
  static bool _opened = false;

  /// 尝试占用编辑器：已有编辑器在打开/前台时返回 false。
  static bool tryEnter() {
    if (_opened) return false;
    _opened = true;
    return true;
  }

  static void release() => _opened = false;
}

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  LicenseRegistry.addLicense(() async* {
    yield LicenseEntryWithLineBreaks(['AI.js Pro 编辑器参考'],
        await rootBundle.loadString('third_party/aijs-editor/LICENSE.md'));
    yield LicenseEntryWithLineBreaks(['Shizuku API'],
        await rootBundle.loadString('third_party/shizuku-sdk/LICENSE'));
  });
  runApp(const GlassApp());
}

@pragma('vm:entry-point')
void logMain() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const GlassApp(logOnly: true));
}

class GlassApp extends StatefulWidget {
  final bool logOnly;
  const GlassApp({Key? key, this.logOnly = false}) : super(key: key);
  @override
  State<GlassApp> createState() => _GlassAppState();
}

class _GlassAppState extends State<GlassApp> {
  // 首帧必须是深色：主题偏好是异步桥读回来的（bootstrap），在读到之前若用浅色主题，
  // 冷启动/Activity 重建（从后台返回）会先闪一帧白底。dark 默认 true、themeLoaded 之前
  // themeMode 固定 dark，读到偏好后再按用户设置切换（深->浅是正常换肤，不会再白闪）。
  bool dark = true;
  bool themeLoaded = false;
  bool savingTheme = false;
  final messenger = GlobalKey<ScaffoldMessengerState>();

  /// 返回键协商通道（task-91 Java 侧）：Java 在 onBackPressed 里先问 Dart，
  /// 我们返回 true=已内部返回（Java 不退出）、false=根页面（Java finish 回桌面）。
  static const MethodChannel backChannel =
      MethodChannel('com.jdkshen.aijspro/back');

  /// Home 的 State 句柄：返回键逻辑都落在 Home（Navigator/抽屉/目录/标签页）。
  final homeKey = GlobalKey<_HomeState>();

  @override
  void initState() {
    super.initState();
    backChannel.setMethodCallHandler(handleBackCall);
    // 玻璃主题接线：折射着色器在首帧前异步装载。装载/编译失败时
    // GlassRefraction 会让导航镜片回落原有外观，这里不需要额外处理。
    GlassRefraction.ensure();
    // 编辑器显示选项（自动换行、行号槽、页分割线、补全）来自保留运行库的
    // 偏好存储；开机就读一次，这样不打开设置页也守这些设置。读不到就保持默认。
    loadEditorPreferences();
    if (widget.logOnly) {
      LocalBridge.bootstrap().then((value) {
        if (mounted) setState(() {
          dark = value['dark'] == true;
          themeLoaded = true;
        });
      }).catchError((Object error) { });
    }
  }

  @override
  void dispose() {
    backChannel.setMethodCallHandler(null);
    super.dispose();
  }

  /// Java→Dart 返回键询问（task-91 契约）。
  ///
  /// - 有子页面/抽屉打开/目录内/非首个标签 → [homeKey] 内部返回并回 true；
  /// - 已在根页面 → false，Java 走自己的 finish（回到桌面）；
  /// - 未注册时 Java 会在 500ms 超时后走旧 fallback，不影响旧包。
  Future<dynamic> handleBackCall(MethodCall call) async {
    if (call.method != 'onBackPressed') return null;
    final handled = homeKey.currentState?.handleBackPressed() ?? false;
    if (!handled) {
      // 根页面：主动让 Java finish Activity（回桌面）；立即退出，不等 fallback 的
      // popRoute + 300ms。旧包没有 finish 方法时抛 MissingPluginException，回 false
      // 让 Java 按「根页面」fallback 处理。
      try {
        await backChannel.invokeMethod('finish');
      } catch (_) {
        return false;
      }
    }
    return true;
  }

  Future<void> loadEditorPreferences() async {
    try {
      final values = await LocalBridge.call('runtimePreferences');
      if (values is Map) {
        EditorPreferences.instance.apply(Map<String, dynamic>.from(values));
      }
    } catch (_) {
      // 读不到时沿用默认值，不影响编辑。
    }
  }

  Future<void> setTheme(bool value) async {
    if (savingTheme || value == dark) return;
    setState(() => savingTheme = true);
    try {
      if (await LocalBridge.call('theme', {'dark': value}) != true) {
        throw StateError('主题保存结果未确认');
      }
      if (mounted) {
        setState(() {
          dark = value;
          themeLoaded = true;
        });
      }
    } catch (error) {
      if (mounted) {
        messenger.currentState?.hideCurrentSnackBar();
        messenger.currentState
            ?.showSnackBar(SnackBar(content: Text('主题未保存，保留原外观：$error')));
      }
    } finally {
      if (mounted) setState(() => savingTheme = false);
    }
  }

  ThemeData theme(bool isDark) {
    final scheme = ColorScheme.fromSeed(
        seedColor: const Color(0xff008f91),
        brightness: isDark ? Brightness.dark : Brightness.light);
    return ThemeData(
        brightness: scheme.brightness,
        colorScheme: scheme,
        scaffoldBackgroundColor: Colors.transparent,
        fontFamily: 'sans-serif',
        iconTheme: IconThemeData(
            color: isDark ? const Color(0xffa5afb2) : const Color(0xff8d97ac)),
        appBarTheme: const AppBarTheme(
            backgroundColor: Colors.transparent, elevation: 0),
        snackBarTheme:
            const SnackBarThemeData(behavior: SnackBarBehavior.floating));
  }

  @override
  Widget build(BuildContext context) {
    SystemChrome.setSystemUIOverlayStyle(SystemUiOverlayStyle(
      statusBarColor: Colors.transparent,
      statusBarIconBrightness: dark ? Brightness.light : Brightness.dark,
      systemNavigationBarColor:
          dark ? const Color(0xff1c2022) : const Color(0xfff7f7f8),
      systemNavigationBarIconBrightness:
          dark ? Brightness.light : Brightness.dark,
    ));
    return MaterialApp(
        title: 'AI.js Pro',
        // Material 组件中文化：关于对话框按钮（查看许可/关闭）、日期选择等。
        localizationsDelegates: const [
          GlobalMaterialLocalizations.delegate,
          GlobalWidgetsLocalizations.delegate,
          GlobalCupertinoLocalizations.delegate,
        ],
        supportedLocales: const [Locale('zh', 'CN'), Locale('en')],
        locale: const Locale('zh', 'CN'),
        scaffoldMessengerKey: messenger,
        navigatorObservers: [onceRoutes, logRouteObserver],
        debugShowCheckedModeBanner: false,
        // 两套主题都配好；偏好在桥异步读回前固定 dark，避免冷启动/重建闪白。
        theme: theme(false),
        darkTheme: theme(true),
        themeMode: themeLoaded
            ? (dark ? ThemeMode.dark : ThemeMode.light)
            : ThemeMode.dark,
        // 系统窗口/任务切换器底色也固定深色（与根容器兜底一致）。
        color: const Color(0xff0B0D18),
        // 首帧兜底：child 之外/过渡空档由纯色容器铺底，绝不露出白色窗口背景。
        // 偏好还没读回时用略透明的深色（0xCC0B0D18），之后换成当前主题的玻璃底色。
        builder: (context, child) => ThemePreferenceScope(
            saving: savingTheme,
            child: ColoredBox(
                color: themeLoaded
                    ? GlassBackground.colourFor(dark)
                    : const Color(0xCC0B0D18),
                child: child!)),
        home: widget.logOnly ? const LogPage() : SplashGate(
            child: Home(
                key: homeKey,
                onTheme: setTheme,
                onThemeLoaded: (value) => setState(() {
                      dark = value;
                      themeLoaded = true;
                    }))));
  }
}

class ThemePreferenceScope extends InheritedWidget {
  final bool saving;
  const ThemePreferenceScope(
      {Key? key, required this.saving, required Widget child})
      : super(key: key, child: child);
  static bool isSaving(BuildContext context) =>
      context
          .dependOnInheritedWidgetOfExactType<ThemePreferenceScope>()
          ?.saving ??
      false;
  @override
  bool updateShouldNotify(ThemePreferenceScope old) => old.saving != saving;
}

/// Launch brand screen, laid out like the original `res/layout/activity_splash.xml`:
/// the logo and the "AI.js Pro" wordmark form one centred row 80 dp above the
/// bottom, and a small progress row sits 24 dp above the bottom. Both bitmaps are
/// the original APK's own drawables (`icon_autojs_logo`, `autojs`) and keep their
/// alpha channel, so they composite on either theme's background.
class BrandSplash extends StatelessWidget {
  const BrandSplash({Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    final dark = Theme.of(context).brightness == Brightness.dark;
    final background = dark ? const Color(0xff1a1c1a) : const Color(0xfffdfdf6);
    final onBackground =
        dark ? const Color(0xffe2e3de) : const Color(0xff1a1c1a);
    final primary = dark ? const Color(0xff7bda98) : const Color(0xff386a20);
    return Scaffold(
        backgroundColor: background,
        body: Stack(children: [
          Positioned(
              left: 0,
              right: 0,
              bottom: 80,
              child: Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Image.asset('assets/brand/icon_ai_js_pro.png',
                        width: 56, height: 56),
                    const SizedBox(width: 12),
                    // 品牌字标：从 Auto.js Pro 换成 AI.js Pro（不再依赖旧字标图片）。
                    Text('AI.js Pro',
                        style: TextStyle(
                            fontSize: 26,
                            fontWeight: FontWeight.w500,
                            letterSpacing: 0.2,
                            color: onBackground))
                  ])),
          Positioned(
              left: 0,
              right: 0,
              bottom: 24,
              child: Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    SizedBox(
                        width: 12,
                        height: 12,
                        child: CircularProgressIndicator(
                            strokeWidth: 2, color: primary)),
                    const SizedBox(width: 4),
                    // The original reads "检查登录状态"; this build never contacts
                    // a login server, so the label names what actually happens.
                    const Text('正在准备脚本工作区',
                        style:
                            TextStyle(fontSize: 12, color: Color(0xff929292)))
                  ]))
        ]));
  }
}

/// Holds [BrandSplash] over the first frames. The original `SplashActivity`
/// finishes in ~130 ms, so its brand layout is never actually seen; keeping it up
/// for a short, fixed hold makes the launch read as AI.js Pro while the
/// workspace loads behind it.
class SplashGate extends StatefulWidget {
  final Widget child;
  final Duration hold;
  const SplashGate(
      {Key? key,
      required this.child,
      this.hold = const Duration(milliseconds: 1200)})
      : super(key: key);
  @override
  State<SplashGate> createState() => _SplashGateState();
}

class _SplashGateState extends State<SplashGate> {
  bool released = false;
  Timer? timer;

  @override
  void initState() {
    super.initState();
    timer = Timer(widget.hold, () {
      if (mounted) setState(() => released = true);
    });
  }

  @override
  void dispose() {
    timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AnimatedSwitcher(
      duration: const Duration(milliseconds: 220),
      child: released
          ? KeyedSubtree(key: const ValueKey('workspace'), child: widget.child)
          : const BrandSplash(key: ValueKey('brand-splash')));
}

class Home extends StatefulWidget {
  final ValueChanged<bool> onTheme;
  final ValueChanged<bool>? onThemeLoaded;
  const Home({Key? key, required this.onTheme, this.onThemeLoaded})
      : super(key: key);
  @override
  State<Home> createState() => _HomeState();
}

class _HomeState extends State<Home> with WidgetsBindingObserver {
  Map<String, dynamic>? boot;
  String? failure;
  bool drawerOpen = false;
  bool nativePackagingBusy = false;
  late final VoidCallback nativePackagingReady = () {
    consumeNativePackaging();
  };
  int tab = 0, revision = 0;
  final pages = PageController();
  final scaffold = GlobalKey<ScaffoldState>();
  final scriptBrowser = GlobalKey<_FileBrowserState>();
  final sampleBrowser = GlobalKey<_FileBrowserState>();
  final resources = GlobalKey<_CatalogPageState>();
  final plugins = GlobalKey<_CatalogPageState>();
  final tasks = GlobalKey<_TaskPageState>();
  final icons = const [
    Icons.insert_drive_file,
    Icons.auto_stories,
    Icons.store,
    Icons.extension,
    Icons.desktop_windows_outlined
  ];
  final labels = const ['脚本', '示例', '资源', '插件', '管理'];
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    LocalBridge.listenForNativePackaging();
    LocalBridge.onNativePackagingReady = nativePackagingReady;
    LocalBridge.onPendingAction = consumeFloatingAction;
    initialize();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    if (identical(LocalBridge.onNativePackagingReady, nativePackagingReady)) {
      LocalBridge.onNativePackagingReady = null;
    }
    if (identical(LocalBridge.onPendingAction, consumeFloatingAction)) {
      LocalBridge.onPendingAction = null;
    }
    pages.dispose();
    super.dispose();
  }

  void selectTab(int index) {
    if (!pages.hasClients) return;
    FocusScope.of(context).unfocus();
    pages.animateToPage(index,
        duration: const Duration(milliseconds: 280),
        curve: Curves.easeOutCubic);
  }

  /// 返回键 Dart 侧实现（task-90）：按层级消费一次返回。
  ///
  /// 顺序：Navigator 子页面/对话框（设置、日志、设备、打包页、权限引导等）
  /// → 侧栏抽屉 → 文件/示例目录内的搜索/新建面板与上一级目录
  /// → 非首个标签回到「脚本」页；都没有（根页面）返回 false，交给 Java finish 回桌面。
  ///
  /// 只消费一次；调用方（MethodChannel / WillPopScope）负责决定是否退出 Activity。
  bool handleBackPressed() {
    if (!mounted) return false;
    final navigator = Navigator.of(context);
    if (navigator.canPop()) {
      // 子页面/对话框：直接 pop 一层（与 OnceRoutes 页面栈配合）。
      navigator.pop();
      return true;
    }
    final scaffoldState = scaffold.currentState;
    if (scaffoldState != null && scaffoldState.isDrawerOpen) {
      scaffoldState.closeDrawer();
      return true;
    }
    final browser = tab == 0
        ? scriptBrowser.currentState
        : tab == 1
            ? sampleBrowser.currentState
            : null;
    if (browser?.dismissTransient() == true) return true;
    if (browser != null && browser.path != browser.widget.root) {
      browser.parent();
      return true;
    }
    if (tab == 4 && tasks.currentState?.clearSearch() == true) return true;
    if (tab != 0) {
      selectTab(0);
      return true;
    }
    return false;
  }

  void searchCurrentPage() {
    if (tab == 0)
      scriptBrowser.currentState?.toggleSearch();
    else if (tab == 1)
      sampleBrowser.currentState?.toggleSearch();
    else if (tab == 2)
      resources.currentState?.toggleSearch();
    else if (tab == 3)
      plugins.currentState?.toggleSearch();
    else
      tasks.currentState?.toggleSearch();
  }

  /// 打开工具页；[name] 非空时走单实例导航（连点/多入口只保留一个页面）。
  Future<void> openUtility(Widget page, {String? name}) async {
    if (name != null && OnceRoutes.reveal(context, name)) {
      await refreshScriptRoot();
      if (mounted) setState(() => revision++);
      return;
    }
    await Navigator.of(context).push(MaterialPageRoute<void>(
        settings: name == null ? null : RouteSettings(name: name),
        builder: (_) => page));
    await refreshScriptRoot();
    if (mounted) setState(() => revision++);
  }

  Future<void> refreshScriptRoot() async {
    try {
      final value = await LocalBridge.call('scriptsRoot');
      if (!mounted || boot == null || value is! Map || value['path'] is! String)
        return;
      if (boot!['scripts'] != value['path']) {
        setState(() => boot = {...boot!, 'scripts': value['path']});
      }
    } catch (error) {
      if (mounted) message(context, '脚本目录读取失败：$error');
    }
  }

  void refreshVisiblePage(int index) {
    if (index == 0) scriptBrowser.currentState?.load();
    if (index == 1) sampleBrowser.currentState?.load();
    if (index == 2) resources.currentState?.load();
    if (index == 3) plugins.currentState?.load();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed && mounted) {
      // 回到 Flutter 即原生 Ace 编辑器已关闭：放行下一次「打开编辑器」。
      NativeEditorGate.release();
      setState(() => revision++);
      refreshScriptRoot();
      consumeFloatingAction();
      consumeNativePackaging();
    }
  }

  /// The floating controller's ✎ and ▤ raise this task after storing a request in
  /// preferences; resuming is the only channel back while the app was in the
  /// background, since the overlay has no live binding to this Dart isolate.
  Future<void> consumeFloatingAction() async {
    if (!mounted) return;
    try {
      final pending = await LocalBridge.call('pendingAction');
      if (!mounted || pending is! Map || pending['action'] == null) return;
      final action = pending['action'];
      if (action == 'log') {
        // 日志统一走 Flutter 的「运行日志」页（统一实时流 + 6 档过滤/搜索），
        // 单实例：悬浮条连点只在已有日志页上停住。
        openUtility(const LogPage(), name: 'log');
      } else if (action == 'edit' && pending['path'] is String) {
        // The packaged Ace editor is the editor: it runs as its own Activity, so the
        // pending-edit route hands the file straight to it instead of pushing a route.
        // 与文件列表打开共用 [NativeEditorGate]，连点不会叠出多层编辑器 Activity。
        if (!NativeEditorGate.tryEnter()) return;
        var launched = false;
        try {
          await LocalBridge.call(
              'aceOpenFile', {'path': pending['path'] as String});
          launched = true;
          if (mounted) setState(() => revision++);
        } finally {
          if (!launched) NativeEditorGate.release();
        }
      }
    } catch (_) {
      // A stale or unreadable request must never break the resume path.
    }
  }

  Future<void> initialize() async {
    setState(() => failure = null);
    try {
      final value = await LocalBridge.bootstrap();
      if (!mounted) return;
      setState(() => boot = value);
      (widget.onThemeLoaded ?? widget.onTheme)(value['dark'] == true);
      await introduceStoragePermission(value['storagePermission']);
      // 首启权限引导：首帧后异步检查一次，不阻塞文件列表/后续初始化。
      schedulePermissionGuide();
      await consumeNativePackaging();
    } catch (error) {
      if (mounted) setState(() => failure = error.toString());
    }
  }

  /// 首次启动权限引导：首帧后异步弹一次（本进程只调度一次）。
  ///
  /// 不 await、不阻塞 bootstrap/文件列表；具体阈值与「只提示一次」标记在
  /// permission_guide.dart（读 deviceState，缺权限才弹）。
  void schedulePermissionGuide() {
    if (_permissionGuideScheduled) return;
    _permissionGuideScheduled = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) showPermissionGuideIfNeeded(context);
    });
  }

  Future<void> consumeNativePackaging() async {
    if (!mounted || boot == null || nativePackagingBusy) return;
    nativePackagingBusy = true;
    try {
      final request = await LocalBridge.call('nativePackagingRequest');
      if (!mounted || request is! Map || request['path'] is! String) return;
      // 后台留下的打包请求先整理成项目，再打开玻璃打包页。
      final prepared = await LocalBridge.call(
          'prepareSingleFile', {'path': request['path']});
      if (!mounted) return;
      if (prepared is! Map || prepared['path'] is! String) {
        throw StateError('无法准备打包项目');
      }
      await Navigator.of(context).push(MaterialPageRoute<void>(
          builder: (_) => ProjectPage(path: prepared['path'] as String)));
      if (mounted) setState(() => revision++);
    } catch (error) {
      if (mounted) message(context, '无法打开打包配置：$error');
    } finally {
      nativePackagingBusy = false;
    }
  }

  Future<void> introduceStoragePermission(dynamic state) async {
    if (state is! Map ||
        state['granted'] != false ||
        state['prompted'] != false) return;
    try {
      if (await LocalBridge.call('storageIntroduce') != true || !mounted)
        return;
      final accepted = await showDialog<bool>(
          context: context,
          builder: (context) => AlertDialog(
                  title: const Text('需要文件管理权限'),
                  content: Text(state['allFiles'] == true
                      ? '读取、保存脚本和管理文件需要访问存储。请在接下来的系统设置中开启“允许管理所有文件”。'
                      : '读取、保存脚本和管理文件需要访问存储。请在接下来的系统弹窗中允许文件访问。'),
                  actions: [
                    TextButton(
                        onPressed: () => Navigator.pop(context, false),
                        child: const Text('暂不授权')),
                    TextButton(
                        onPressed: () => Navigator.pop(context, true),
                        child: const Text('去授权'))
                  ]));
      if (!mounted || accepted != true) return;
      if (await LocalBridge.call('storagePermission') != true && mounted)
        message(context, '未能打开系统授权，请在设置中允许文件访问');
    } catch (error) {
      if (mounted) message(context, '文件权限申请失败：$error');
    }
  }

  Future<void> openWorkspace() async {
    if (boot == null) return;
    // Opens the packaged Ace editor with no document; the user picks a file inside it.
    try {
      await LocalBridge.call('openEditor', {});
    } catch (error) {
      if (mounted) message(context, error.toString());
    }
    if (mounted) setState(() => revision++);
  }

  Widget drawer() => ServiceDrawer(
      active: drawerOpen,
      root: boot?['scripts'] as String? ?? '',
      onTheme: widget.onTheme,
      openWorkspace: openWorkspace,
      onTab: selectTab,
      openPage: openUtility);

  @override
  Widget build(BuildContext context) => WillPopScope(
      // 兜底（旧原生仍走 navigationChannel.popRoute 时）：先做一次内部返回；
      // 无论是否消费都返回 false —— 不 pop Home 路由（pop 掉根路由会黑屏），
      // 是否退出 Activity 由 Java 返回键协商（task-91）或旧 fallback 的 finish 决定。
      onWillPop: () async {
        handleBackPressed();
        return false;
      },
      child: GlassBackground(
          child: Scaffold(
              key: scaffold,
              drawer: drawer(),
              onDrawerChanged: (value) => setState(() => drawerOpen = value),
              body: SafeArea(
                  child: Column(children: [
                SizedBox(
                    height: 56,
                    child: Padding(
                        // 对齐 AI.js Pro MiuixMainNavigationHost.kt:49 —— 水平 12（原为 8）
                        padding: const EdgeInsets.symmetric(horizontal: 12),
                        child: Row(children: [
                          ActionIcon(
                              tooltip: '打开菜单',
                              // 对齐 AI.js Pro MiuixMainNavigationHost.kt:88 —— 命中区 48×48（原为 56×56）
                              width: 48,
                              height: 48,
                              icon: const Icon(Icons.menu_rounded),
                              onPressed: () =>
                                  scaffold.currentState?.openDrawer()),
                          const SizedBox(width: 12),
                          const Expanded(
                              child: FittedBox(
                                  fit: BoxFit.scaleDown,
                                  alignment: Alignment.centerLeft,
                                  child: Text('AI.js Pro',
                                      style: TextStyle(
                                          // 对齐 AI.js Pro MiuixMainNavigationHost.kt:55 —— 22sp（原为 20）
                                          fontSize: 22,
                                          fontWeight: FontWeight.w400)))),
                          ActionIcon(
                              tooltip: '打开工作区',
                              icon: const Icon(Icons.code),
                              onPressed: boot == null ? null : openWorkspace),
                          ActionIcon(
                              tooltip: '运行日志',
                              icon: const Icon(Icons.assignment),
                              onPressed: () =>
                                  openUtility(const LogPage(), name: 'log')),
                          ActionIcon(
                              tooltip: '本地使用指南',
                              icon: const Icon(Icons.bookmark),
                              onPressed: () => openUtility(const GuidePage())),
                          ActionIcon(
                              tooltip: tab == 4 ? '搜索任务' : '搜索文件',
                              icon: const Icon(Icons.search),
                              onPressed:
                                  boot == null ? null : searchCurrentPage)
                        ]))),
                Padding(
                    padding: const EdgeInsets.fromLTRB(0, 0, 0, 4),
                    child: GlassNavigation(
                        controller: pages,
                        icons: icons,
                        labels: labels,
                        selected: tab,
                        enabled: boot != null,
                        onSelect: selectTab)),
                Expanded(
                    child: boot == null
                        ? Center(
                            child: failure == null
                                ? const CircularProgressIndicator()
                                : Padding(
                                    padding: const EdgeInsets.all(24),
                                    child: Column(
                                        mainAxisSize: MainAxisSize.min,
                                        children: [
                                          Text(failure!),
                                          TextButton(
                                              onPressed: initialize,
                                              child: const Text('重新连接'))
                                        ])))
                        : PageView(
                            controller: pages,
                            onPageChanged: (index) {
                              FocusScope.of(context).unfocus();
                              setState(() => tab = index);
                              refreshVisiblePage(index);
                            },
                            children: [
                                _WorkspacePage(
                                    child: FileBrowser(
                                        key: scriptBrowser,
                                        root: boot!['scripts'],
                                        title: '内部存储 · 脚本',
                                        active: tab == 0,
                                        revision: revision)),
                                _WorkspacePage(
                                    child: FileBrowser(
                                        key: sampleBrowser,
                                        root: boot!['samples'],
                                        title: '示例文件',
                                        readOnly: true,
                                        active: tab == 1,
                                        revision: revision)),
                                _WorkspacePage(
                                    child: CatalogPage(
                                        key: resources,
                                        plugin: false,
                                        revision: revision)),
                                _WorkspacePage(
                                    child: CatalogPage(
                                        key: plugins,
                                        plugin: true,
                                        revision: revision)),
                                _WorkspacePage(
                                    child: TaskPage(
                                        key: tasks,
                                        active: tab == 4,
                                        openDevice: () => openUtility(
                                            const DeviceSettingsPage())))
                              ]))
              ])))));
}

// Keep each workspace's directory, scroll position and service state while
// PageView lazily mounts the other tabs.
class _WorkspacePage extends StatefulWidget {
  final Widget child;
  const _WorkspacePage({required this.child});
  @override
  State<_WorkspacePage> createState() => _WorkspacePageState();
}

class _WorkspacePageState extends State<_WorkspacePage>
    with AutomaticKeepAliveClientMixin {
  @override
  bool get wantKeepAlive => true;
  @override
  Widget build(BuildContext context) {
    super.build(context);
    return widget.child;
  }
}

class ActionIcon extends StatelessWidget {
  final String tooltip;
  final Widget icon;
  final VoidCallback? onPressed;
  // 参考版顶部按钮为 48×56 dp、左侧菜单键 56×56 dp（即 132×154 / 154×154 px）。
  final double width;
  final double height;
  const ActionIcon(
      {Key? key,
      required this.tooltip,
      required this.icon,
      required this.onPressed,
      this.width = 48,
      this.height = 56})
      : super(key: key);
  @override
  Widget build(BuildContext context) => Semantics(
      label: tooltip,
      button: true,
      enabled: onPressed != null,
      onTap: onPressed,
      child: ExcludeSemantics(
          child: IconButton(
              tooltip: tooltip,
              icon: icon,
              padding: EdgeInsets.zero,
              constraints:
                  BoxConstraints.tightFor(width: width, height: height),
              onPressed: onPressed)));
}

void message(BuildContext context, String text) {
  final messenger = ScaffoldMessenger.of(context);
  messenger.hideCurrentSnackBar();
  messenger.showSnackBar(SnackBar(content: Text(text)));
}

String formatSize(dynamic bytes) {
  final size = (bytes as num?)?.toInt() ?? 0;
  return size < 1024
      ? '$size B'
      : size < 1024 * 1024
          ? '${(size / 1024).toStringAsFixed(1)} KB'
          : '${(size / 1024 / 1024).toStringAsFixed(1)} MB';
}

Future<String?> askName(BuildContext context) async {
  final input = TextEditingController(text: '新脚本.js');
  final result = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
              title: const Text('新建脚本'),
              content: TextField(
                  controller: input,
                  autofocus: true,
                  decoration:
                      const InputDecoration(labelText: '文件名（.js / .node.js）')),
              actions: [
                TextButton(
                    onPressed: () => Navigator.pop(context),
                    child: const Text('取消')),
                TextButton(
                    onPressed: () {
                      final name = input.text.trim();
                      if (name.isNotEmpty &&
                          !name.contains('/') &&
                          !name.contains('\\') &&
                          name != '.' &&
                          name != '..' &&
                          name.endsWith('.js')) Navigator.pop(context, name);
                    },
                    child: const Text('创建'))
              ]));
  // A route's closing animation can still paint its TextField after the result resolves.
  Future<void>.delayed(const Duration(seconds: 1), input.dispose);
  return result;
}
