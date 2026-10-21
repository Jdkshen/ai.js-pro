part of 'main.dart';

class ServiceDrawer extends StatefulWidget {
  final bool active;
  final String root;
  final ValueChanged<bool> onTheme;
  final VoidCallback openWorkspace;
  final ValueChanged<int> onTab;
  final void Function(Widget) openPage;
  const ServiceDrawer(
      {Key? key,
      required this.active,
      required this.root,
      required this.onTheme,
      required this.openWorkspace,
      required this.onTab,
      required this.openPage})
      : super(key: key);
  @override
  State<ServiceDrawer> createState() => _ServiceDrawerState();
}

class _ServiceDrawerState extends State<ServiceDrawer>
    with WidgetsBindingObserver {
  Map<String, dynamic>? state;
  Map<String, dynamic>? device;
  String? error, deviceError, operationError;
  final permissionErrors = <String, String>{};
  final openingPermissions = <String>{};
  Timer? timer;
  Future<bool>? reading, readingDevice;
  bool foreground = true, pending = false;
  bool? target;
  bool? failedTarget;
  // 抽屉"开发"组：对齐原版的远程调试链路（允许远程调试 → 主动连接电脑）。
  // remoteDebug 是原厂调试服务器（MODE_LISTENING）的真实监听状态；
  // 本地 drawerPreferences 只在桥的状态读不到时作为回退。
  bool remoteDebug = false, keepScreenOn = false, devPluginTipDismissed = false;
  bool debugServerKnown = false, debugServerPending = false;
  // computerPluginState 里的状态机：抽屉直接显示 ServerState/ClientState，
  // 不用再点开“主动连接电脑”才知道连接结果。
  ComputerServerState? debugServerState;
  ComputerClientState? debugClientState;
  String? debugProtocol;
  List<String> debugDiscovery = const [];
  bool debugClientKnown = false;
  Future<bool>? readingDebugServer;
  // 开发者调试按原版折叠，展开后才显示远程调试各项；存储权限收在“更多...”里
  bool debugExpanded = false, moreExpanded = false;

  bool get observing => widget.active && foreground;

  bool get mcpRunning => state?['running'] == true;

  bool get mcpStale =>
      state?['stale'] == true || state?['state'] == 'stale';

  bool get mcpLiveRunning => state?['liveRunning'] == true;

  int get mcpPort {
    final value = state?['port'];
    if (value is num) {
      final parsed = value.toInt();
      if (parsed > 0) return parsed;
    }
    return _mcpDefaultPort;
  }

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    observe();
    loadDrawerPreferences();
  }

  @override
  void didUpdateWidget(ServiceDrawer old) {
    super.didUpdateWidget(old);
    if (old.active != widget.active) observe();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState value) {
    foreground = value == AppLifecycleState.resumed;
    observe();
  }

  void observe() {
    timer?.cancel();
    if (!observing) return;
    refresh();
    refreshDevice();
    refreshDebugServer();
    timer = Timer.periodic(const Duration(seconds: 2), (_) {
      if (!pending) refresh();
      refreshDevice();
      // 官方插件从后台重连后，抽屉打开期间也要跟上真实调试服务器状态；
      // refreshDebugServer 自带 observing/readingDebugServer 去重，
      // 启停命令未确认时不读，避免和 setRemoteDebug 的乐观状态争抢。
      if (!debugServerPending) refreshDebugServer();
    });
  }

  @override
  void dispose() {
    timer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  Future<bool> refresh() {
    if (reading != null) return reading!;
    if (!observing) return Future.value(false);
    final request = readState();
    reading = request;
    request.whenComplete(() {
      if (identical(reading, request)) reading = null;
    });
    return request;
  }

  DateTime? lastExitPressed;

  Future<void> exitApplication() async {
    final now = DateTime.now();
    if (lastExitPressed == null ||
        now.difference(lastExitPressed!) > const Duration(seconds: 2)) {
      lastExitPressed = now;
      message(context, '再按一次退出应用');
      return;
    }
    try {
      await LocalBridge.call('exitApp');
    } catch (error) {
      if (mounted) message(context, '退出失败：$error');
    }
  }

  Future<void> loadDrawerPreferences() async {
    try {
      final reply = await LocalBridge.call('drawerPreferences');
      if (!mounted || reply is! Map) return;
      setState(() {
        // 桥的调试服务器状态优先；本地偏好只在状态尚未读到时作回退。
        if (!debugServerKnown) remoteDebug = reply['remoteDebug'] == true;
        keepScreenOn = reply['keepScreenOn'] == true;
        devPluginTipDismissed = reply['devPluginTipDismissed'] == true;
      });
      await LocalBridge.call('keepScreenOn', {'enabled': keepScreenOn});
    } catch (_) {
      // 读取失败保持默认关闭，不影响抽屉其它功能
    }
  }

  Future<void> saveDrawerPreferences() async {
    try {
      await LocalBridge.call('drawerPreferences', {
        'value': {
          'remoteDebug': remoteDebug,
          'keepScreenOn': keepScreenOn,
          'devPluginTipDismissed': devPluginTipDismissed
        }
      });
    } catch (error) {
      if (mounted) message(context, '抽屉设置保存失败：$error');
    }
  }

  /// 原厂调试服务器是否在监听。新桥同时提供 debugServerListening 与 listening，
  /// 优先采用前者，兼容只返回 listening 的旧桥。
  bool? debugListening(Map<String, dynamic> value) {
    final fresh = value['debugServerListening'];
    if (fresh is bool) return fresh;
    final state = computerServerStateOf(value);
    if (state != ComputerServerState.unknown) {
      return state == ComputerServerState.listening;
    }
    final legacy = value['listening'];
    return legacy is bool ? legacy : null;
  }

  Future<bool> refreshDebugServer() {
    if (readingDebugServer != null) return readingDebugServer!;
    if (!observing) return Future.value(false);
    final request = readDebugServer();
    readingDebugServer = request;
    request.whenComplete(() {
      if (identical(readingDebugServer, request)) readingDebugServer = null;
    });
    return request;
  }

  Future<bool> readDebugServer() async {
    try {
      final reply = await LocalBridge.call('computerPluginState');
      if (reply is! Map) throw StateError('调试服务状态格式异常');
      final value = Map<String, dynamic>.from(reply);
      final listening = debugListening(value);
      if (listening == null) throw StateError('调试服务状态格式异常');
      final clientKnown = value['clientStateKnown'] == true;
      if (!mounted) return false;
      setState(() {
        debugServerKnown = true;
        debugServerState = computerServerStateOf(value);
        debugClientKnown = clientKnown;
        debugClientState = computerClientStateOf(value);
        debugProtocol = computerProtocolTypeOf(value);
        debugDiscovery = computerDiscoveryMethodsOf(value);
        // 开关命令还在路上时，别用轮询结果覆盖乐观状态。
        if (!debugServerPending) remoteDebug = listening;
      });
      return true;
    } catch (_) {
      // 读不到时保留本地偏好里的值，不影响抽屉其它功能。
      return false;
    }
  }

  /// 「允许远程调试」= 真正启停原厂调试服务器（MODE_LISTENING，mode 2）。
  Future<void> setRemoteDebug(bool value) async {
    if (debugServerPending) return;
    final previous = remoteDebug;
    setState(() {
      remoteDebug = value;
      debugServerPending = true;
    });
    try {
      final reply = await LocalBridge.call(
          'computerPluginSet', {'mode': 2, 'enabled': value});
      if (reply is! Map) throw StateError('远程调试服务状态未确认');
      final stateValue = Map<String, dynamic>.from(reply);
      final listening = debugListening(stateValue);
      if (!mounted) return;
      setState(() {
        debugServerKnown = true;
        debugServerState = computerServerStateOf(stateValue);
        debugProtocol = computerProtocolTypeOf(stateValue);
        debugDiscovery = computerDiscoveryMethodsOf(stateValue);
        debugClientKnown = stateValue['clientStateKnown'] == true;
        debugClientState = computerClientStateOf(stateValue);
        remoteDebug = listening ?? value;
      });
      if (listening != null && listening != value) {
        message(context, value ? '远程调试服务未能启动，请重试' : '远程调试服务尚未停止，请刷新重试');
      }
      await saveDrawerPreferences();
    } catch (e) {
      if (mounted) {
        setState(() => remoteDebug = previous);
        message(context, '远程调试${value ? '开启' : '关闭'}失败：$e');
      }
    } finally {
      if (mounted) setState(() => debugServerPending = false);
    }
  }

  Future<void> setKeepScreenOn(bool value) async {
    setState(() => keepScreenOn = value);
    await LocalBridge.call('keepScreenOn', {'enabled': value});
    await saveDrawerPreferences();
  }

  Future<void> showDevPluginTips() async {
    if (devPluginTipDismissed) {
      message(context, '已设置不再提示 AI.js Pro VS Code 扩展介绍');
      return;
    }
    final choice = await showDialog<String>(
        context: context,
        builder: (dialog) => AlertDialog(
                title: const Text('AI.js Pro VS Code 扩展'),
                content: const SingleChildScrollView(
                    child: Text('在 VS Code 中安装并启用 AI.js Pro 扩展后，可以：\n'
                        '🔗 连接手机，查看设备状态\n'
                        '📁 浏览、同步手机脚本文件\n'
                        '🖥 打开手机终端并执行命令\n'
                        '⚡ 配合 MCP 使用脚本、文件和任务工具')),
                actions: [
                  TextButton(
                      onPressed: () => Navigator.pop(dialog, 'never'),
                      child: const Text('不再提示')),
                  TextButton(
                      onPressed: () => Navigator.pop(dialog, 'docs'),
                      child: const Text('查看连接教程'))
                ]));
    if (!mounted) return;
    if (choice == 'never') {
      setState(() => devPluginTipDismissed = true);
      await saveDrawerPreferences();
    } else if (choice == 'docs') {
      page(const GuidePage());
    }
  }

  Future<bool> readState() async {
    try {
      final reply = await LocalBridge.call('mcpState');
      if (reply is! Map || reply['running'] is! bool) {
        throw StateError('服务状态格式异常');
      }
      if (!mounted) return false;
      setState(() {
        state = Map<String, dynamic>.from(reply);
        error = null;
        if (failedTarget != null && state?['running'] == failedTarget) {
          operationError = null;
          failedTarget = null;
        }
      });
      return true;
    } catch (e) {
      if (mounted) setState(() => error = e.toString());
      return false;
    }
  }

  Future<bool> refreshDevice() {
    if (readingDevice != null) return readingDevice!;
    if (!observing) return Future.value(false);
    final request = readDevice();
    readingDevice = request;
    request.whenComplete(() {
      if (identical(readingDevice, request)) readingDevice = null;
    });
    return request;
  }

  Future<bool> readDevice() async {
    try {
      final reply = await LocalBridge.call('deviceState');
      if (reply is! Map ||
          ['accessibilityEnabled', 'overlayGranted', 'storageGranted']
              .any((key) => reply[key] is! bool)) {
        throw StateError('系统授权状态格式异常');
      }
      if (!mounted) return false;
      setState(() {
        device = Map<String, dynamic>.from(reply);
        deviceError = null;
      });
      return true;
    } catch (e) {
      if (mounted) setState(() => deviceError = e.toString());
      return false;
    }
  }

  Future<void> permission(String method) async {
    if (openingPermissions.contains(method)) return;
    setState(() {
      openingPermissions.add(method);
      permissionErrors.remove(method);
    });
    try {
      await LocalBridge.call(method);
      if (mounted && observing) refreshDevice();
    } catch (e) {
      if (mounted) {
        setState(() => permissionErrors[method] = e.toString());
        message(context, e.toString());
      }
    } finally {
      if (mounted) setState(() => openingPermissions.remove(method));
    }
  }

  /// Shows or hides the script control button. The floating window is owned by
  /// the original runtime receiver, so the bridge broadcasts to it; there is no
  /// way to create it from Dart.
  Future<void> setFloatingWindow(bool value) async {
    const action = 'floatingWindow';
    if (openingPermissions.contains(action)) return;
    setState(() {
      openingPermissions.add(action);
      permissionErrors.remove(action);
    });
    try {
      final reply = await LocalBridge.call(action, {'enabled': value});
      if (mounted && observing) await refreshDevice();
      if (mounted && reply is Map && reply['granted'] == false) {
        message(context, '请先授予悬浮窗权限');
      }
    } catch (e) {
      if (mounted) {
        setState(() => permissionErrors[action] = e.toString());
        message(context, e.toString());
      }
    } finally {
      if (mounted) setState(() => openingPermissions.remove(action));
    }
  }

  Future<void> toggleMcp(bool value) async {
    if (pending || error != null || state?['running'] is! bool) return;
    setState(() {
      pending = true;
      target = value;
      operationError = null;
      failedTarget = null;
    });
    try {
      await LocalBridge.call('mcpSet', {'enabled': value});
      for (var attempt = 0; attempt < 20; attempt++) {
        if (!mounted || !observing) return;
        final read = await refresh();
        if (read && state?['running'] == value) return;
        if (attempt < 19) {
          await Future<void>.delayed(const Duration(milliseconds: 500));
        }
      }
      throw StateError(state?['error']?.toString().isNotEmpty == true
          ? state!['error'].toString()
          : 'MCP 状态尚未确认，请刷新后重试');
    } catch (e) {
      if (mounted) {
        setState(() {
          operationError = e.toString();
          failedTarget = value;
        });
      }
    } finally {
      if (mounted) {
        setState(() {
          pending = false;
          target = null;
        });
      }
    }
  }

  void navigate(VoidCallback action) {
    Navigator.of(context).pop();
    try {
      action();
    } catch (e) {
      if (mounted) message(context, e.toString());
    }
  }

  void page(Widget value) => navigate(() => widget.openPage(value));

  Future<void> terminal() async {
    Navigator.of(context).pop();
    try {
      await LocalBridge.call('openTerminal', {'path': widget.root});
    } catch (e) {
      if (mounted) message(context, e.toString());
    }
  }

  // 字号对齐 AI.js Pro：MiuixDrawerHost 的行组件全部走库默认
  // （SuperSwitch/SuperArrow/BasicComponent：标题 17.sp Medium、摘要 14.sp；
  //  SmallTitle：padding 28/8、14.sp Medium）。原为 14 / 10 / 11。
  Widget heading(String value) => Padding(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 5),
      child: Text(value,
          style: TextStyle(
              fontSize: 14,
              fontWeight: FontWeight.w500,
              color:
                  Theme.of(context).colorScheme.onSurface.withOpacity(.55))));

  Widget link(IconData icon, String title, VoidCallback action) => ListTile(
      dense: true,
      minVerticalPadding: 8,
      leading: Icon(icon, size: 22),
      title: Text(title, style: const TextStyle(fontSize: 17)),
      onTap: action);

  Widget groupTile(
          {required IconData icon,
          required String title,
          required bool expanded,
          required VoidCallback onTap,
          String? subtitle}) =>
      ListTile(
          dense: true,
          minVerticalPadding: 5,
          leading: Icon(icon, size: 22),
          title: Text(title, style: const TextStyle(fontSize: 17)),
          subtitle: subtitle == null
              ? null
              : Text(subtitle, style: const TextStyle(fontSize: 14)),
          trailing: Icon(expanded ? Icons.expand_less : Icons.expand_more),
          onTap: onTap);

  Widget service(String label, String key, String method, IconData icon,
      {bool toggle = false}) {
    final known = device?[key] is bool;
    // Permission entries open system settings; MCP state does not authorize or
    // block them, and opening settings does not optimistically revoke a grant.
    // The floating-window entry is a real switch instead: it shows or hides the
    // script control button through the original runtime receiver.
    final action = toggle ? 'floatingWindow' : method;
    final enabled = !openingPermissions.contains(action);
    final checked = device?[key] == true;
    final granted = device?['overlayGranted'] == true;
    final grantStatus = deviceError != null
        ? '状态未确认${known ? ' · 保留上次状态' : ''}'
        : !known
            ? '正在读取状态'
            : toggle
                ? (!granted
                    ? '未授权 · 点击授予悬浮窗权限'
                    : checked
                        ? '已显示 · 点击隐藏'
                        : '已隐藏 · 点击显示')
                : checked
                    ? '已启用 · 点击进入系统设置'
                    : '未启用 · 点击进入系统设置';
    final status = permissionErrors.containsKey(action)
        ? '$grantStatus · 上次操作失败，点击重试'
        : grantStatus;
    return ListTile(
        dense: true,
        minVerticalPadding: 5,
        leading: Icon(icon, size: 22),
        title: Text(label, style: const TextStyle(fontSize: 17)),
        subtitle: Text(status, style: const TextStyle(fontSize: 14)),
        trailing: known
            ? Semantics(
                label: '$label开关',
                child: Switch(
                    key: ValueKey('service-$key'),
                    activeColor: const Color(0xff008f91),
                    value: checked,
                    onChanged: enabled
                        ? (value) => toggle
                            ? setFloatingWindow(value)
                            : permission(method)
                        : null))
            : SizedBox(
                width: 48,
                child: Center(
                    child: deviceError == null && device == null
                        ? const SizedBox(
                            width: 16,
                            height: 16,
                            child: CircularProgressIndicator(strokeWidth: 2))
                        : const Text('未知', style: TextStyle(fontSize: 14)))),
        onTap: enabled
            ? () => toggle ? setFloatingWindow(!checked) : permission(method)
            : null);
  }

  /// 抽屉里的 ServerState/ClientState 摘要：不再只说“允许/连上”两个布尔态。
  String get _debugGroupSubtitle {
    if (!debugServerKnown) return 'AI.js Pro VS Code 扩展';
    final server = computerServerStateLabel(
        debugServerState ?? ComputerServerState.unknown);
    final client = computerClientStateLabel(debugClientKnown
        ? (debugClientState ?? ComputerClientState.unknown)
        : ComputerClientState.unknown);
    final protocol = debugProtocol;
    return '服务器$server · 电脑$client'
        '${protocol == null ? '' : ' · ${computerProtocolDescription(protocol)}'}';
  }

  @override
  Widget build(BuildContext context) {
    final running = mcpRunning;
    final stale = mcpStale;
    final liveRunning = mcpLiveRunning;
    final switchOn = running || (stale && liveRunning);
    final known = state?['running'] is bool;
    final port = mcpPort;
    final stateError = state?['error']?.toString().trim() ?? '';
    final mcpError =
        operationError ?? error ?? (stateError.isEmpty ? null : stateError);
    return Drawer(
        width: 304,
        backgroundColor: Theme.of(context).brightness == Brightness.dark
            ? const Color(0xff252c2e)
            : const Color(0xfff4f8f8),
        child: SafeArea(
            child: ListView(padding: EdgeInsets.zero, children: [
          Container(
              padding: const EdgeInsets.fromLTRB(20, 28, 20, 22),
              decoration: const BoxDecoration(
                  gradient: LinearGradient(
                      begin: Alignment.topLeft,
                      end: Alignment.bottomRight,
                      colors: [Color(0xff56b9b1), Color(0xff008f91)])),
              child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: const [
                    Icon(Icons.code, color: Colors.white, size: 30),
                    SizedBox(height: 18),
                    Text('AI.js Pro',
                        style: TextStyle(
                            color: Colors.white,
                            fontSize: 21,
                            fontWeight: FontWeight.w500)),
                    SizedBox(height: 5),
                    Text('脚本工作区',
                        style: TextStyle(color: Colors.white70, fontSize: 14)),
                  ])),
          heading('服务'),
          service('无障碍服务', 'accessibilityEnabled', 'accessibilitySettings',
              Icons.accessibility_new_rounded),
          service('悬浮窗', 'floatingWindowShowing', 'overlaySettings',
              Icons.picture_in_picture_alt_outlined,
              toggle: true),
          groupTile(
              icon: Icons.more_horiz,
              title: '更多...',
              expanded: moreExpanded,
              onTap: () => setState(() => moreExpanded = !moreExpanded)),
          if (moreExpanded)
            service('存储权限', 'storageGranted', 'storagePermission',
                Icons.folder_outlined),
          if (deviceError != null)
            Padding(
                padding: const EdgeInsets.fromLTRB(16, 4, 8, 4),
                child: Row(children: [
                  Expanded(
                      child: Text(deviceError!,
                          maxLines: 3,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                              fontSize: 11,
                              color: Theme.of(context).colorScheme.error))),
                  TextButton(onPressed: refreshDevice, child: const Text('重试'))
                ])),
          heading('开发'),
          ListTile(
              dense: true,
              minVerticalPadding: 5,
              leading: const Icon(Icons.hub_outlined, size: 22),
              title: const Text('MCP 服务', style: TextStyle(fontSize: 17)),
              onTap: () => page(const DeviceSettingsPage()),
              subtitle: Text(
                  pending
                      ? target == true
                          ? '正在启动，等待实际状态…'
                          : '正在停止，等待实际状态…'
                      : error != null
                          ? '状态未确认 · 打开管理页'
                          : !known
                              ? '正在读取状态'
                              : stale
                                  ? (liveRunning
                                      ? '心跳过期 · 进程仍在 · 0.0.0.0:$port'
                                      : '心跳过期 · 服务可能已停止')
                                  : running
                                      ? '运行中 · 0.0.0.0:$port · 免令牌'
                                      : '已停止 · 默认 $_mcpDefaultPort',
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontSize: 14)),
              trailing: known
                  ? Semantics(
                      label: '侧栏 MCP 服务开关',
                      child: Switch(
                          key: const ValueKey('drawer-mcp'),
                          activeColor: const Color(0xff008f91),
                          value: switchOn,
                          onChanged:
                              pending || error != null ? null : toggleMcp))
                  : const SizedBox(
                      width: 48,
                      child: Center(
                          child: Text('未知', style: TextStyle(fontSize: 14))))),
          if (mcpError != null)
            Padding(
                padding: const EdgeInsets.fromLTRB(16, 4, 8, 4),
                child: Row(children: [
                  Expanded(
                      child: Text(mcpError,
                          maxLines: 3,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                              fontSize: 11,
                              color: Theme.of(context).colorScheme.error))),
                  TextButton(
                      onPressed: pending
                          ? null
                          : () async {
                              final ok = await refresh();
                              if (mounted && !ok) {
                                message(context, '刷新 MCP 状态失败，请稍后重试');
                              }
                            },
                      child: const Text('刷新 MCP'))
                ])),
          groupTile(
              icon: Icons.bug_report_outlined,
              title: '开发者调试',
              subtitle: _debugGroupSubtitle,
              expanded: debugExpanded,
              onTap: () {
                setState(() => debugExpanded = !debugExpanded);
                if (debugExpanded) {
                  // 展开时补读一次真实监听状态，避免显示抽屉打开前的旧值。
                  refreshDebugServer();
                  showDevPluginTips();
                }
              }),
          if (debugExpanded) ...[
            SwitchListTile(
                key: const ValueKey('drawer-remote-debug'),
                dense: true,
                secondary: debugServerPending
                    ? const SizedBox(
                        width: 22,
                        height: 22,
                        child: CircularProgressIndicator(strokeWidth: 2))
                    : const Icon(Icons.cable_outlined, size: 22),
                title: const Text('允许远程调试', style: TextStyle(fontSize: 17)),
                subtitle: Text(
                    '手机服务器 $computerLegacyPort · 电脑扩展 $computerDefaultClientPort',
                    style: const TextStyle(fontSize: 13)),
                value: remoteDebug,
                onChanged: debugServerPending ? null : setRemoteDebug),
            if (debugServerKnown)
              Padding(
                  padding: const EdgeInsets.fromLTRB(16, 0, 16, 6),
                  child: Text(
                      '协议类型 ${debugProtocol ?? '未上报'} · 发现方式 '
                      '${debugDiscovery.isEmpty ? '未上报' : debugDiscovery.join(' / ')}',
                      style: TextStyle(
                          fontSize: 11,
                          color: Theme.of(context)
                              .colorScheme
                              .onSurface
                              .withOpacity(.55)))),
            ListTile(
                dense: true,
                minVerticalPadding: 5,
                enabled: remoteDebug,
                leading: const Icon(Icons.desktop_windows_outlined, size: 22),
                title: const Text('主动连接电脑', style: TextStyle(fontSize: 17)),
                subtitle: Text(
                    remoteDebug
                        ? '手机回连电脑 · $computerDefaultClientPort'
                            '${debugClientKnown ? ' · ${computerClientStateLabel(debugClientState ?? ComputerClientState.unknown)}' : ''}'
                        : '需先开启“允许远程调试”（电脑扩展默认 $computerDefaultClientPort）',
                    style: const TextStyle(fontSize: 14)),
                onTap: remoteDebug
                    ? () {
                        Navigator.pop(context);
                        showDialog<void>(
                            context: context,
                            builder: (_) => ComputerPluginSheet(
                                activeConnectOnly: true,
                                // 电脑端插件未安装/未就绪时，对话框里“打开插件页”
                                // 直接切到底部的本机插件页（同样的安装/管理入口）。
                                openPluginPage: () => widget.onTab(3)));
                      }
                    : null),
            SwitchListTile(
                dense: true,
                secondary: const Icon(Icons.lightbulb_outline, size: 22),
                title: const Text('前台时保持屏幕常亮', style: TextStyle(fontSize: 17)),
                value: keepScreenOn,
                onChanged: setKeepScreenOn),
            link(Icons.menu_book_outlined, '连接教程',
                () => page(const GuidePage())),
          ],
          link(Icons.terminal, '终端', terminal),
          heading('其他'),
          SwitchListTile(
              dense: true,
              secondary: const Icon(Icons.dark_mode_outlined, size: 22),
              title: const Text('深色主题', style: TextStyle(fontSize: 17)),
              value: Theme.of(context).brightness == Brightness.dark,
              onChanged: ThemePreferenceScope.isSaving(context)
                  ? null
                  : (value) {
                      try {
                        widget.onTheme(value);
                      } catch (e) {
                        message(context, e.toString());
                      }
                    }),
          link(
              Icons.settings_outlined,
              '设置',
              () => page(GeneralSettingsPage(
                  root: widget.root, onTheme: widget.onTheme))),
          link(Icons.logout_outlined, '退出', exitApplication),
          const SizedBox(height: 20),
        ])));
  }
}
