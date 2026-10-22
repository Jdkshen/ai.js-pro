part of 'main.dart';

/// AI.js Pro 手机连接服务状态：
/// 手机开服务器（电脑连手机）时，不再用一个布尔糊弄所有状态。
enum ComputerServerState { disconnected, connecting, listening, unknown }

/// 手机主动连接电脑的状态。
enum ComputerClientState { disconnected, connecting, connected, unknown }

/// AI.js Pro VS Code 扩展的电脑监听端口。
const int computerDefaultClientPort = 8327;

/// AI.js Pro 手机监听端口。
const int computerLegacyPort = 9317;

/// Pro 9.x 的局域网发现服务类型（NSD / mDNS）。
const String computerMdnsServiceType = '_autojsprodebug._tcp.';

String computerServerStateName(ComputerServerState value) {
  switch (value) {
    case ComputerServerState.disconnected:
      return 'DISCONNECTED';
    case ComputerServerState.connecting:
      return 'CONNECTING';
    case ComputerServerState.listening:
      return 'LISTENING';
    case ComputerServerState.unknown:
      return 'UNKNOWN';
  }
}

String computerServerStateLabel(ComputerServerState value) {
  switch (value) {
    case ComputerServerState.disconnected:
      return '未开启';
    case ComputerServerState.connecting:
      return '正在启动';
    case ComputerServerState.listening:
      return '监听中';
    case ComputerServerState.unknown:
      return '未知';
  }
}

String computerClientStateName(ComputerClientState value) {
  switch (value) {
    case ComputerClientState.disconnected:
      return 'DISCONNECTED';
    case ComputerClientState.connecting:
      return 'CONNECTING';
    case ComputerClientState.connected:
      return 'CONNECTED';
    case ComputerClientState.unknown:
      return 'UNKNOWN';
  }
}

String computerClientStateLabel(ComputerClientState value) {
  switch (value) {
    case ComputerClientState.disconnected:
      return '未连接';
    case ComputerClientState.connecting:
      return '正在连接';
    case ComputerClientState.connected:
      return '已连接';
    case ComputerClientState.unknown:
      return '未知';
  }
}

/// 读取宿主给出的 ServerState；旧宿主只给 listening 布尔时降级推断，
/// 既兼容现有 PocDeviceLink，也能直接接 Pro 的 serverState/state 字符串。
ComputerServerState computerServerStateOf(Map<String, dynamic>? state) {
  if (state == null) return ComputerServerState.unknown;
  final enumValue = state['serverState'] ?? state['server_state'];
  if (enumValue is num) {
    switch (enumValue.toInt()) {
      case 0:
        return ComputerServerState.disconnected;
      case 1:
        return ComputerServerState.connecting;
      case 2:
        return ComputerServerState.listening;
      default:
        return ComputerServerState.unknown;
    }
  }
  final raw =
      (enumValue ?? state['state'] ?? '').toString().trim().toUpperCase();
  if (raw.isNotEmpty && raw != 'UNKNOWN') {
    if (raw.contains('LISTEN')) return ComputerServerState.listening;
    if (raw.contains('DISCONNECT')) return ComputerServerState.disconnected;
    if (raw.contains('CONNECT')) return ComputerServerState.connecting;
  }
  final listening = state['listening'];
  if (listening is bool) {
    // 有显式 CONNECTING 时上面已经返回；这里 listening 的语义足够明确。
    return listening
        ? ComputerServerState.listening
        : ComputerServerState.disconnected;
  }
  final code = state['stateCode'];
  if (code is num) {
    switch (code.toInt()) {
      case 0:
        return ComputerServerState.disconnected;
      case 1:
        return ComputerServerState.connecting;
      case 2:
        return ComputerServerState.listening;
      default:
        return ComputerServerState.unknown;
    }
  }
  return ComputerServerState.unknown;
}

/// 读取宿主给出的 ClientState。clientStateKnown=false 时一律 UNKNOWN，
/// 避免把“读不到电脑端状态”伪装成“未连接/已连接”。
ComputerClientState computerClientStateOf(Map<String, dynamic>? state) {
  if (state == null) return ComputerClientState.unknown;
  if (state['clientStateKnown'] != true) return ComputerClientState.unknown;
  final enumValue = state['clientState'] ?? state['client_state'];
  if (enumValue is num) {
    switch (enumValue.toInt()) {
      case 0:
        return ComputerClientState.disconnected;
      case 1:
        return ComputerClientState.connecting;
      case 2:
        return ComputerClientState.connected;
      default:
        return ComputerClientState.unknown;
    }
  }
  final raw = (enumValue ?? '').toString().trim().toUpperCase();
  if (raw.contains('DISCONNECT')) return ComputerClientState.disconnected;
  if (raw.contains('CONNECTING')) return ComputerClientState.connecting;
  if (raw.contains('CONNECTED')) return ComputerClientState.connected;
  final clients = state['clients'] ?? state['connectionCount'];
  if (clients is num) {
    return clients.toInt() > 0
        ? ComputerClientState.connected
        : ComputerClientState.disconnected;
  }
  return ComputerClientState.unknown;
}

int computerClientPortOf(Map<String, dynamic>? state) {
  final value = state?['clientPort'] ?? state?['client_port'];
  if (value is int) return value > 0 ? value : computerDefaultClientPort;
  if (value is num) {
    final port = value.toInt();
    return port > 0 ? port : computerDefaultClientPort;
  }
  final parsed = int.tryParse(value?.toString() ?? '');
  return parsed != null && parsed > 0 ? parsed : computerDefaultClientPort;
}

String? computerProtocolTypeOf(Map<String, dynamic>? state) {
  if (state == null) return null;
  for (final key in const [
    'protocol',
    'protocolType',
    'protocol_type',
    'transport',
    'type'
  ]) {
    final text = state[key]?.toString().trim().toLowerCase() ?? '';
    if (text.isEmpty) continue;
    if (text == 'aijs') return 'AI.js Pro';
    if (text.contains('ws-rpc') || text == 'wsrpc' || text == 'ws_rpc') {
      return 'ws-rpc';
    }
    if (text.contains('legacy')) return 'legacy';
  }
  if (state['wsRpc'] == true ||
      state['ws_rpc'] == true ||
      state['rpc'] == true) {
    return 'ws-rpc';
  }
  if (state['legacy'] == true) return 'legacy';
  // 现有 POC 宿主（DevPluginServer）没有 type 字段，走的是自研 hello 协议。
  return 'AI.js Pro';
}

/// 发现方式：provider（ContentProvider / adb / USB）与 mDNS（NSD 局域网）。
/// 支持宿主用字符串、数组或布尔字段上报，去重后按 provider → mDNS 排列。
List<String> computerDiscoveryMethodsOf(Map<String, dynamic>? state) {
  final methods = <String>[];
  if (state == null) return methods;
  void add(String? value) {
    final text = value?.toString().trim() ?? '';
    if (text.isEmpty) return;
    final lower = text.toLowerCase();
    final provider = lower.contains('provider') ||
        lower.contains('contentprovider') ||
        lower == 'usb' ||
        lower == 'adb';
    final mdns = lower.contains('mdns') ||
        lower.contains('nsd') ||
        lower.contains('bonjour') ||
        lower.contains('zeroconf');
    if (provider && !methods.contains('provider')) methods.add('provider');
    if (mdns && !methods.contains('mDNS')) methods.add('mDNS');
  }

  final raw = state['discovery'] ??
      state['discoveryMethods'] ??
      state['discovery_method'] ??
      state['discoveredBy'];
  if (raw is List) {
    for (final value in raw) {
      add(value?.toString());
    }
  } else if (raw != null) {
    for (final part in raw.toString().split(RegExp(r'[,+;/|\s]+'))) {
      add(part);
    }
  }
  if (state['provider'] == true) add('provider');
  if (state['mdns'] == true ||
      state['mDNS'] == true ||
      state['nsd'] == true ||
      state['mdnsRegistered'] == true ||
      state['nsdRegistered'] == true) {
    add('mDNS');
  }
  // 只展示宿主明确上报的发现方式；没上报时保持空集合，由调用方显示“未上报”。
  return methods;
}

String computerProtocolDescription(String? protocol) {
  if (protocol == 'AI.js Pro') return 'AI.js Pro VS Code 扩展 · TCP';
  if (protocol == 'ws-rpc') return 'ws-rpc（JSON-RPC over WebSocket）';
  if (protocol == 'legacy') return 'legacy（自研 hello 协议）';
  return '未上报';
}

String computerDiscoveryDescription(List<String> methods) {
  if (methods.isEmpty) return '未上报';
  return methods
      .map((method) => method == 'mDNS' ? 'mDNS（局域网）' : 'provider（USB/adb）')
      .join(' + ');
}

String? computerPluginVersionText(Map<String, dynamic>? state) {
  if (state == null) return null;
  // 优先用宿主明确上报的电脑插件版本；只有握手 client_version 时退回 v4 这种短写。
  for (final key in const [
    'pluginVersion',
    'clientVersionName',
    'appVersion'
  ]) {
    final text = state[key]?.toString().trim() ?? '';
    if (text.isNotEmpty) return text;
  }
  final client = state['clientVersion'] ?? state['client_version'];
  if (client is num && client > 0) return 'v${client.toInt()}';
  return null;
}

String? computerRequiredClientVersion(Map<String, dynamic>? state) {
  if (state == null) return null;
  for (final key in const ['requiredClientVersion', 'minClientVersion']) {
    final text = state[key]?.toString().trim() ?? '';
    if (text.isNotEmpty) return text;
  }
  return null;
}

bool computerPluginVersionMismatch(Map<String, dynamic>? state) {
  if (state == null) return false;
  if (state['pluginCompatible'] == false ||
      state['clientVersionOk'] == false ||
      state['versionMismatch'] == true) {
    return true;
  }
  final required = int.tryParse(computerRequiredClientVersion(state) ?? '');
  final current = state['clientVersion'];
  if (required != null && current is num) return current.toInt() < required;
  return false;
}

/// 把宿主/网络层的原始错误翻译成能直接指导用户的提示（带 host:port 与插件版本）。
String computerFailureMessage(String reason,
    {required String target, String? version}) {
  final text = reason.trim();
  final lower = text.toLowerCase();
  final suffix = version == null || version.isEmpty ? '' : '（电脑插件 $version）';
  if (lower.contains('timeout') ||
      lower.contains('timed out') ||
      text.contains('超时')) {
    return '连接 $target 超时$suffix：电脑端未在预期时间内响应。'
        '请确认电脑已启动 VS Code 的 AI.js Pro 扩展、与手机在同一局域网，并放行该端口后重试。';
  }
  if (lower.contains('version') || text.contains('版本')) {
    return '连接 $target 失败$suffix：电脑插件版本不匹配。'
        '请更新 AI.js Pro VS Code 扩展后重试（原始原因：$text）。';
  }
  if (lower.contains('refused') ||
      lower.contains('econnrefused') ||
      text.contains('拒绝')) {
    return '连接 $target 被拒绝$suffix：电脑端没有监听该端口，请先在 VSCode 启动调试插件。';
  }
  if (lower.contains('unreachable') ||
      lower.contains('no route') ||
      text.contains('不可达')) {
    return '无法到达 $target$suffix：请检查手机与电脑是否在同一局域网、电脑防火墙是否放行。';
  }
  return '连接 $target 失败$suffix：$text';
}

/// 电脑连接状态一句话摘要（设备页/抽屉等只需要一行状态的地方共用）。
String computerConnectionStatus(Map<String, dynamic>? state, {String? error}) {
  if (error != null) return '连接状态暂时不可用 · 点击查看详情或重试';
  if (state == null) return '正在读取电脑连接状态…';
  final host = state['lastHost']?.toString().trim() ?? '';
  final client = computerClientStateOf(state);
  if (state['clientStateKnown'] != true || state['pluginReady'] == false) {
    return '电脑插件未安装或未就绪 · 点击查看安装/连接步骤';
  }
  switch (client) {
    case ComputerClientState.connected:
      return host.isEmpty ? '已连接到电脑' : '已连接到电脑（$host）';
    case ComputerClientState.connecting:
      return host.isEmpty ? '正在连接电脑…' : '正在连接电脑（$host）…';
    case ComputerClientState.disconnected:
    case ComputerClientState.unknown:
      break;
  }
  final server = computerServerStateOf(state);
  if (server == ComputerServerState.listening) {
    final endpoint = state['endpoint']?.toString().trim() ?? '';
    final addresses = state['addresses'];
    final address = addresses is List && addresses.isNotEmpty
        ? addresses.first.toString()
        : '';
    final port = state['port'];
    final target = endpoint.isNotEmpty
        ? endpoint
        : (address.isEmpty ? '' : (port == null ? address : '$address:$port'));
    return target.isEmpty ? '服务器监听中 · 可从电脑连接' : '服务器监听中 · $target';
  }
  if (server == ComputerServerState.connecting) return '服务器正在启动…';
  if (server == ComputerServerState.unknown) return '电脑连接状态未知 · 点击查看';
  return '未连接电脑 · 点击管理';
}

/// 设备页卡片第二行：协议类型 + 发现方式 + 插件版本（都可以缺失）。
String? computerConnectionDetail(Map<String, dynamic>? state) {
  if (state == null) return null;
  final parts = <String>[];
  final protocol = computerProtocolTypeOf(state);
  if (protocol != null) parts.add('协议 $protocol');
  final discovery = computerDiscoveryMethodsOf(state);
  if (discovery.isNotEmpty) parts.add('发现 ${discovery.join(' / ')}');
  final version = computerPluginVersionText(state);
  if (version != null) parts.add('插件 $version');
  return parts.isEmpty ? null : parts.join(' · ');
}

/// The plug-in button shows device information; the drawer opens the PC address form.
class ComputerPluginSheet extends StatefulWidget {
  final bool activeConnectOnly;

  /// “去安装/打开插件页”入口。抽屉/设备页打开时由外层提供（跳到本机插件页）；
  /// 传 null 时对话框自己压入本机插件页，保证“打开插件页”始终可用。
  final VoidCallback? openPluginPage;

  const ComputerPluginSheet(
      {Key? key, this.activeConnectOnly = false, this.openPluginPage})
      : super(key: key);
  @override
  State<ComputerPluginSheet> createState() => _ComputerPluginSheetState();
}

class _ComputerPluginSheetState extends State<ComputerPluginSheet>
    with WidgetsBindingObserver {
  final hostInput = TextEditingController();
  Map<String, dynamic>? state;
  String? error, operationError, connectionHint;

  /// 本次“主动连接电脑”提交的地址：失败提示里要能说清连的是哪台电脑。
  String? connectingHost;
  bool busy = false, connecting = false, foreground = true, hostEdited = false;
  bool awaitingConnection = false;
  bool? target;
  DateTime? attemptStarted;
  Timer? timer;
  Future<bool>? refreshing;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    refresh();
    timer = Timer.periodic(const Duration(seconds: 2), (_) {
      if (foreground &&
          mounted &&
          ModalRoute.of(context)?.isCurrent != false &&
          !busy &&
          !connecting) refresh();
    });
  }

  @override
  void dispose() {
    timer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    hostInput.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState value) {
    foreground = value == AppLifecycleState.resumed;
    if (foreground && !busy && !connecting) refresh();
  }

  Future<bool> refresh() =>
      refreshing ??= readState().whenComplete(() => refreshing = null);

  // ---- computerPluginState 字段映射 -------------------------------------
  // 宿主（POC 的 PocDeviceLink / 重建线 PluginManager）提供的字段：
  //   stateCode / state / serverState / listening / port / clientPort /
  //   addresses / endpoint / lastHost / clientStateKnown / clientState /
  //   provider / type / clientVersion / clientError / pluginReady
  // 本文件额外兼容增量字段（没有时自动降级，不影响现有宿主）：
  //   protocol / protocolType / discovery / discoveryMethods / mdns / nsd /
  //   pluginCompatible / requiredClientVersion
  bool get stateKnown => state?['clientStateKnown'] == true;
  ComputerServerState get serverState => computerServerStateOf(state);
  ComputerClientState get clientState => computerClientStateOf(state);
  bool get serverListening => serverState == ComputerServerState.listening;
  bool get connected =>
      error == null && clientState == ComputerClientState.connected;
  String get lastHostAddress => state?['lastHost']?.toString().trim() ?? '';

  int get clientPort => computerClientPortOf(state);

  int? get clientVersion {
    final value = state?['clientVersion'];
    if (value is int) return value;
    if (value is num) return value.toInt();
    return int.tryParse(value?.toString() ?? '');
  }

  String? get clientVersionLabel {
    final text = computerPluginVersionText(state);
    if (text != null) return text;
    final version = clientVersion;
    if (version != null && version > 0) return 'v$version';
    return null;
  }

  String? get protocolType => computerProtocolTypeOf(state);
  List<String> get discoveryMethods => computerDiscoveryMethodsOf(state);

  String? get clientReason {
    for (final key in const [
      'clientError',
      'connectError',
      'clientErrorMessage',
      'lastError'
    ]) {
      final value = state?[key]?.toString().trim() ?? '';
      if (value.isNotEmpty) return value;
    }
    return null;
  }

  String get _hostAndPort {
    final host = connectingHost ?? lastHostAddress;
    if (host.isEmpty) return '电脑';
    // 用户可能已经填了 IP:端口，别拼成 host:21029:21029。
    if (host.contains(':')) return host;
    return '$host:$clientPort';
  }

  String get _activeConnectSubtitle {
    return '手机连电脑 · VS Code 扩展监听 $clientPort';
  }

  /// 监听中的手机地址：端口还没分配时只显示 IP，不拼出 “ip:null”。
  String _listeningAddress(String address) {
    final port = state?['port'];
    return port == null ? address : '$address:$port';
  }

  /// 未能连接电脑时的具体原因（宿主给了 clientError 就翻译后显示）。
  String get _connectionFailureMessage {
    final reason = clientReason;
    if (reason != null) {
      return computerFailureMessage(reason,
          target: _hostAndPort, version: clientVersionLabel);
    }
    return '未能连接电脑（$_hostAndPort）：请确认 VS Code 已启用 AI.js Pro 扩展、与手机处于同一局域网，'
        '且电脑防火墙放行该调试端口。';
  }

  void accept(Map result) {
    state = Map<String, dynamic>.from(result);
    error = null;
    if (!hostEdited && hostInput.text.isEmpty) {
      hostInput.text = state?['lastHost']?.toString() ?? '';
    }
    final isConnected =
        stateKnown && clientState == ComputerClientState.connected;
    if (isConnected) {
      // 轮询可能比操作返回值更晚看到连接成功：清掉之前的超时/失败提示。
      operationError = null;
      if (awaitingConnection) {
        awaitingConnection = false;
        connectionHint = '已连接到电脑';
      }
    }
    if (!awaitingConnection) return;
    final elapsed = DateTime.now().difference(attemptStarted!).inMilliseconds;
    if (isConnected) return;
    if (stateKnown &&
        clientState == ComputerClientState.disconnected &&
        clientReason != null &&
        elapsed >= 2000) {
      awaitingConnection = false;
      connectionHint = null;
      operationError = _connectionFailureMessage;
    } else if (!stateKnown && elapsed >= 1500) {
      // 宿主自己都读不到电脑端状态：别冒充“连接失败”，直接给插件安装/就绪提示。
      awaitingConnection = false;
      connectionHint = null;
      operationError = '无法确认连接结果：电脑插件未安装或未就绪。'
          '请在电脑的 VSCode 中安装并启动调试插件后重试（可点下方“安装/连接教程”）。';
    } else if (stateKnown &&
        clientState == ComputerClientState.disconnected &&
        elapsed >= 2000) {
      awaitingConnection = false;
      connectionHint = null;
      operationError = _connectionFailureMessage;
    } else if (elapsed >= 15000) {
      awaitingConnection = false;
      connectionHint = null;
      operationError = clientState == ComputerClientState.connecting
          ? '连接 $_hostAndPort 超时：电脑端没有响应。请确认电脑已启动 VS Code 的 AI.js Pro 扩展、'
              '与手机同一局域网，且电脑防火墙允许该端口后重试。'
          : '连接超时，请检查手机与电脑的网络和电脑端调试服务后重试。';
    }
  }

  Future<bool> readState() async {
    try {
      final result = await LocalBridge.call('computerPluginState');
      if (result is! Map) throw StateError('电脑连接状态暂时不可用');
      final value = Map<String, dynamic>.from(result);
      final recognized = value.containsKey('stateCode') ||
          value.containsKey('serverState') ||
          value.containsKey('server_state') ||
          value.containsKey('state') ||
          value.containsKey('listening') ||
          value.containsKey('clientState');
      if (!recognized) throw StateError('电脑连接状态暂时不可用');
      if (!mounted) return false;
      setState(() => accept(value));
      return true;
    } catch (failure) {
      if (mounted) setState(() => error = failure.toString());
      return false;
    }
  }

  Future<void> toggleServer(bool value) async {
    if (busy || connecting || state == null || error != null) return;
    setState(() {
      busy = true;
      target = value;
      operationError = null;
    });
    try {
      // A mutation must not race an earlier poll and then display its stale result.
      await refreshing;
      final result = await LocalBridge.call(
          'computerPluginSet', {'mode': 2, 'enabled': value});
      if (result is! Map) throw StateError('服务器状态暂时不可用，请刷新重试');
      final listening =
          computerServerStateOf(Map<String, dynamic>.from(result)) ==
              ComputerServerState.listening;
      if (listening != value) {
        throw StateError(
            value ? '调试服务器未能启动：端口可能被占用，请重试或重启应用' : '调试服务器尚未停止，请刷新后重试');
      }
      if (mounted)
        setState(() {
          if (!value) {
            // 停服务器后界面不应继续停留在“正在连接电脑…”的等待态。
            awaitingConnection = false;
            connectionHint = null;
          }
          accept(result);
        });
    } catch (failure) {
      if (mounted) setState(() => operationError = failure.toString());
      await refresh();
    } finally {
      if (mounted)
        setState(() {
          busy = false;
          target = null;
        });
    }
  }

  String? get hostError {
    final host = hostInput.text.trim();
    if (host.isEmpty) return null;
    final parts = host.split(':');
    if (parts.length > 2 ||
        !RegExp(r'^[A-Za-z0-9][A-Za-z0-9.-]*$').hasMatch(parts[0]) ||
        parts[0].contains('..') ||
        parts[0].endsWith('.')) return '请输入电脑 IP 地址，可在末尾添加端口';
    if (RegExp(r'^[0-9.]+$').hasMatch(parts[0])) {
      final numbers = parts[0].split('.');
      if (numbers.length != 4 ||
          numbers.any((n) => int.tryParse(n) == null || int.parse(n) > 255))
        return 'IP 地址格式不正确';
    }
    if (parts.length == 2) {
      final port = int.tryParse(parts[1]);
      if (port == null || port < 1 || port > 65535) return '端口应在 1 到 65535 之间';
    }
    return null;
  }

  Future<void> connectComputer() async {
    final host = hostInput.text.trim();
    if (host.isEmpty ||
        hostError != null ||
        connecting ||
        busy ||
        awaitingConnection ||
        error != null) return;
    FocusScope.of(context).unfocus();
    setState(() {
      connecting = true;
      operationError = null;
      connectingHost = host;
      connectionHint = '正在连接 $host…';
    });
    try {
      await refreshing;
      final result = await LocalBridge.call(
          'computerPluginSet', {'mode': 1, 'enabled': true, 'host': host});
      if (result is! Map) throw StateError('连接结果暂时不可用，请刷新重试');
      if (!mounted) return;
      setState(() {
        awaitingConnection = true;
        attemptStarted = DateTime.now();
        accept(result);
      });
    } catch (failure) {
      if (mounted)
        setState(() {
          operationError = failure.toString();
          connectionHint = null;
        });
    } finally {
      if (mounted) setState(() => connecting = false);
    }
  }

  Future<void> disconnectComputer() async {
    if (busy || connecting) return;
    setState(() {
      busy = true;
      operationError = null;
    });
    try {
      await refreshing;
      final result = await LocalBridge.call(
          'computerPluginSet', {'mode': 1, 'enabled': false});
      if (result is! Map) throw StateError('断开结果暂时不可用，请刷新重试');
      final value = Map<String, dynamic>.from(result);
      if (mounted)
        setState(() {
          awaitingConnection = false;
          connectionHint = null;
          accept(result);
        });
    } catch (failure) {
      if (mounted) setState(() => operationError = failure.toString());
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<void> openActiveConnection() async {
    await showDialog<void>(
        context: context,
        builder: (_) => const ComputerPluginSheet(activeConnectOnly: true));
    if (mounted) refresh();
  }

  Future<void> copyAddress(String value) async {
    await Clipboard.setData(ClipboardData(text: value));
    if (mounted) message(context, '已复制 $value');
  }

  Future<void> openTutorial() async {
    await showDialog<void>(
        context: context,
        builder: (dialogContext) => AlertDialog(
              title: const Text('连接 AI.js Pro VS Code'),
              content: const SingleChildScrollView(
                  child: Text(
                      '1. 在电脑 VS Code 中安装并启用 AI.js Pro 扩展（jdkshen.aijspro-vscode）。\n\n'
                      '2. 电脑连手机：开启本页“服务器模式”，在 VS Code 运行“AI.js Pro: 连接设备”，输入手机地址，默认端口 9317。\n\n'
                      '3. 手机连电脑：保持 VS Code 扩展运行，在本页“主动连接电脑”填写电脑 IP，默认端口 8327。两种连接方式任选一种。\n\n'
                      '4. USB 连接：电脑执行 adb forward tcp:19317 tcp:9317，再在扩展中连接 127.0.0.1:19317。多设备时在 adb 后加 -s 设备编号。\n\n'
                      '连接后可运行文件、推送工程、查看日志、截图与分析控件。手机和电脑通过局域网连接时，请确认防火墙放行所用端口。')),
              actions: [
                TextButton(
                    onPressed: () => Navigator.pop(dialogContext),
                    child: const Text('知道了'))
              ],
            ));
  }

  /// “打开插件页”：优先用外层给的入口；插件页自己打开时退回本机插件页。
  void openPluginPageEntry() {
    final open = widget.openPluginPage;
    final navigator = Navigator.of(context);
    navigator.pop();
    if (open != null) {
      open();
      return;
    }
    navigator.push(MaterialPageRoute<void>(
        builder: (_) => Scaffold(
            appBar: AppBar(title: const Text('本机插件')),
            body: const CatalogPage(plugin: true, revision: 0))));
  }

  /// 电脑端插件状态：未安装/未就绪、已就绪未连接、已连接（有版本就显示版本）。
  String get pluginStatus {
    if (error != null) return '电脑插件状态未知：连接状态读取失败';
    if (state == null) return '正在读取电脑插件状态…';
    if (computerPluginVersionMismatch(state)) {
      final required = computerRequiredClientVersion(state);
      final version = clientVersionLabel;
      final current = version == null ? '' : '（当前电脑插件 $version）';
      return required == null
          ? '电脑插件版本不匹配$current'
          : '电脑插件版本不匹配$current（需要 v$required）';
    }
    if (connected) {
      final parts = <String>['电脑插件已连接'];
      final protocol = protocolType;
      if (protocol != null) parts.add(protocol);
      final version = clientVersionLabel;
      if (version != null) parts.add('插件版本 $version');
      return parts.join(' · ');
    }
    if (!stateKnown) return '电脑插件未安装或未就绪';
    if (state?['pluginReady'] == false) return '电脑插件未安装或未就绪';
    if (clientState == ComputerClientState.connecting) return '正在连接电脑插件…';
    if (!serverListening) return '电脑插件未就绪：手机端调试服务未开启';
    if (lastHostAddress.isEmpty) return '等待电脑端插件连接';
    return '电脑插件未连接（上次地址：$_hostAndPort）';
  }

  /// 状态行下面的可操作说明；null 表示不需要额外解释。
  String? get pluginHint {
    if (error != null || state == null) return null;
    if (computerPluginVersionMismatch(state)) {
      final required = computerRequiredClientVersion(state);
      final version = clientVersionLabel;
      return '电脑端调试插件版本不匹配'
          '${version == null ? '' : '（当前 $version）'}'
          '${required == null ? '' : '，需要 v$required'}，'
          '请更新 AI.js Pro VS Code 扩展后重试。';
    }
    if (connected) return null;
    final reason = clientReason;
    if (reason != null) {
      return computerFailureMessage(reason,
          target: _hostAndPort, version: clientVersionLabel);
    }
    if (!stateKnown) {
      return '请在电脑的 VSCode 中安装并启动 AI.js Pro 扩展后，再回到本页连接。'
          '可点下方“安装/连接教程”。';
    }
    if (state?['pluginReady'] == false) return '电脑端插件尚未就绪，可先查看安装/连接教程。';
    if (clientState == ComputerClientState.connecting) return '正在连接电脑插件…';
    if (!serverListening) {
      return '开启“服务器模式”（电脑连手机）或“主动连接电脑”（手机连电脑）后即可开始连接。';
    }
    if (lastHostAddress.isNotEmpty) {
      return '上次连接的电脑：$_hostAndPort。确认电脑端插件已启动后，点“主动连接电脑”重试。';
    }
    return '在电脑的 VS Code 中启用 AI.js Pro 扩展后，用下方地址连接本机。';
  }

  /// “安装/连接教程 + 打开插件页”入口：只在还没连上电脑时出现。
  bool get showInstallEntry =>
      error == null &&
      state != null &&
      (!connected || computerPluginVersionMismatch(state));

  Widget pluginBlock() {
    final muted = TextStyle(
        fontSize: 12,
        color: Theme.of(context).colorScheme.onSurface.withOpacity(.6));
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Row(children: [
        Icon(
            connected
                ? Icons.check_circle_outline
                : Icons.desktop_windows_outlined,
            size: 18,
            color: connected
                ? Theme.of(context).colorScheme.primary
                : Theme.of(context).colorScheme.onSurface.withOpacity(.6)),
        const SizedBox(width: 8),
        Expanded(
            child: Text(pluginStatus,
                key: const ValueKey('computer-plugin-state'),
                style: const TextStyle(fontSize: 14))),
      ]),
      if (pluginHint != null)
        Padding(
            padding: const EdgeInsets.only(top: 6, left: 26),
            child: Text(pluginHint!,
                key: const ValueKey('computer-plugin-hint'), style: muted)),
      if (showInstallEntry)
        Padding(
            padding: const EdgeInsets.only(top: 2),
            child: Wrap(spacing: 4, children: [
              TextButton.icon(
                  key: const ValueKey('computer-plugin-install'),
                  onPressed: openTutorial,
                  icon: const Icon(Icons.download_outlined, size: 18),
                  label: const Text('安装/连接教程')),
            ])),
    ]);
  }

  String get clientStatus {
    if (error != null) return '连接状态暂时不可用，请刷新重试';
    if (!stateKnown) return '尚未获取电脑连接状态';
    switch (clientState) {
      case ComputerClientState.connected:
        return '已连接到电脑';
      case ComputerClientState.connecting:
        return '正在连接电脑…';
      case ComputerClientState.disconnected:
        return '未连接电脑';
      case ComputerClientState.unknown:
        return '电脑连接状态未知';
    }
  }

  Widget statusLine(String text, {bool success = false}) => Row(children: [
        Icon(success ? Icons.check_circle_outline : Icons.info_outline,
            size: 18,
            color: success
                ? Theme.of(context).colorScheme.primary
                : Theme.of(context).colorScheme.onSurface.withOpacity(.6)),
        const SizedBox(width: 8),
        Expanded(
            child: Text(text,
                key: const ValueKey('computer-plugin-status'),
                style: const TextStyle(fontSize: 14))),
      ]);

  Widget _detailRow(String label, String value, {Key? key, String? hint}) =>
      Padding(
          key: key,
          padding: const EdgeInsets.only(top: 4),
          child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
            SizedBox(
                width: 76,
                child: Text(label,
                    style: TextStyle(
                        fontSize: 12,
                        color: Theme.of(context)
                            .colorScheme
                            .onSurface
                            .withOpacity(.55)))),
            Expanded(
                child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                  Text(value,
                      style: const TextStyle(
                          fontSize: 12, fontWeight: FontWeight.w500)),
                  if (hint != null)
                    Text(hint,
                        style: TextStyle(
                            fontSize: 11,
                            height: 1.4,
                            color: Theme.of(context)
                                .colorScheme
                                .onSurface
                                .withOpacity(.55))),
                ])),
          ]));

  /// 枚举状态 + 协议类型 + 发现方式 + 插件版本：把“布尔状态”补成可诊断的明细。
  Widget connectionDetails() {
    if (state == null || error != null) return const SizedBox.shrink();
    final count = state?['clients'] ?? 0;
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      _detailRow('手机服务', computerServerStateLabel(serverState),
          key: const ValueKey('computer-plugin-server-state')),
      _detailRow(
          '电脑连接', '${computerClientStateLabel(clientState)} · $count 个连接',
          key: const ValueKey('computer-plugin-client-state')),
      if (connected && clientVersionLabel != null)
        _detailRow('扩展版本', clientVersionLabel!,
            key: const ValueKey('computer-plugin-version')),
    ]);
  }

  @override
  Widget build(BuildContext context) {
    final clientPortValue = clientPort;
    final server = serverState;
    final listening = server == ComputerServerState.listening;
    final addresses = (state?['addresses'] as List?)
            ?.map((v) => '$v')
            .where((v) => v.isNotEmpty)
            .toList() ??
        <String>[];
    final muted = TextStyle(
        fontSize: 13,
        color: Theme.of(context).colorScheme.onSurface.withOpacity(.65));
    final serverStatus = error != null
        ? '服务器状态暂时不可用，请刷新重试'
        : state == null
            ? '正在读取连接信息…'
            : busy && target != null
                ? (target! ? '正在启动服务器…' : '正在停止服务器…')
                : listening
                    ? '服务器已开启 · 可从电脑连接'
                    : server == ComputerServerState.connecting
                        ? '服务器正在启动…'
                        : server == ComputerServerState.unknown
                            ? '服务器状态未知 · 请刷新重试'
                            : '服务器模式未开启';
    final serverSubtitle = listening
        ? '电脑连手机 · 端口 ${state?['port'] ?? computerLegacyPort}'
        : '电脑连手机 · 端口 $computerLegacyPort';
    return AlertDialog(
      key: const ValueKey('computer-plugin-sheet'),
      insetPadding: const EdgeInsets.symmetric(horizontal: 20, vertical: 24),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
      title: Row(children: [
        Icon(Icons.desktop_windows_outlined,
            color: Theme.of(context).colorScheme.primary),
        const SizedBox(width: 10),
        Expanded(
            child: Text(widget.activeConnectOnly ? '主动连接电脑' : '电脑连接',
                style: const TextStyle(fontSize: 20))),
        IconButton(
            tooltip: '刷新连接状态',
            onPressed: busy || connecting ? null : refresh,
            icon: const Icon(Icons.refresh, size: 22)),
      ]),
      content: SizedBox(
          width: 440,
          child: SingleChildScrollView(
              child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              if (widget.activeConnectOnly) ...[
                Text('请输入用于调试的 VSCode 所在电脑的 IP 地址。手机和电脑需要连接同一局域网。',
                    style: muted),
                const SizedBox(height: 16),
                TextField(
                    key: const ValueKey('computer-plugin-host'),
                    controller: hostInput,
                    autofocus: true,
                    enabled: !busy && !connecting && !awaitingConnection,
                    keyboardType: TextInputType.url,
                    textInputAction: TextInputAction.done,
                    onChanged: (_) => setState(() => hostEdited = true),
                    onSubmitted: (_) => connectComputer(),
                    decoration: InputDecoration(
                        labelText: '电脑 IP 地址',
                        hintText: '例如 192.168.1.100',
                        helperText: 'VS Code 扩展端口 $clientPortValue，可填写 IP:端口',
                        errorText: hostEdited ? hostError : null,
                        border: const OutlineInputBorder())),
                const SizedBox(height: 16),
                statusLine(clientStatus, success: connected),
                if (connectionHint != null && !connected)
                  Padding(
                      padding: const EdgeInsets.only(top: 8),
                      child: Text(connectionHint!, style: muted)),
                const SizedBox(height: 12),
                pluginBlock(),
                const SizedBox(height: 8),
                connectionDetails(),
              ] else ...[
                Row(children: [
                  Expanded(
                      child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                        const Text('服务器模式',
                            style: TextStyle(
                                fontSize: 16, fontWeight: FontWeight.w500)),
                        Text(serverSubtitle, style: muted),
                      ])),
                  Semantics(
                      label: '服务器模式开关',
                      child: Switch(
                          key: const ValueKey('computer-plugin-server'),
                          value: listening,
                          onChanged: busy ||
                                  connecting ||
                                  state == null ||
                                  error != null
                              ? null
                              : toggleServer)),
                ]),
                const SizedBox(height: 12),
                statusLine(serverStatus, success: listening && error == null),
                connectionDetails(),
                const SizedBox(height: 12),
                pluginBlock(),
                const SizedBox(height: 16),
                Text(
                    listening
                        ? '在 AI.js Pro VS Code 扩展中输入以下手机地址：'
                        : '手机 IP 地址（开启服务器模式后显示完整连接地址）：',
                    style: muted),
                const SizedBox(height: 6),
                if (addresses.isEmpty)
                  Text('没有任何连接信息，请先连接 WiFi 或开启手机热点。', style: muted),
                for (final address in addresses)
                  Row(children: [
                    Expanded(
                        child: SelectableText(
                            listening ? _listeningAddress(address) : address,
                            style: const TextStyle(
                                fontSize: 15,
                                fontFamily: 'monospace',
                                fontFamilyFallback: monoFontFallback))),
                    IconButton(
                        tooltip: listening ? '复制连接地址' : '复制手机 IP',
                        icon: const Icon(Icons.copy_outlined, size: 20),
                        onPressed: () => copyAddress(
                            listening ? _listeningAddress(address) : address)),
                  ]),
                const Divider(height: 24),
                ListTile(
                    contentPadding: EdgeInsets.zero,
                    leading: const Icon(Icons.link),
                    title: const Text('主动连接电脑'),
                    subtitle: Text(_activeConnectSubtitle, style: muted),
                    trailing: const Icon(Icons.chevron_right),
                    onTap: busy ? null : openActiveConnection),
                Text(clientStatus, style: muted),
                if (state?['lastHost']?.toString().isNotEmpty == true)
                  Text('上次地址：$_hostAndPort', style: muted),
              ],
              if (operationError != null || error != null)
                Padding(
                    padding: const EdgeInsets.only(top: 12),
                    child: Text(operationError ?? error!,
                        style: TextStyle(
                            fontSize: 13,
                            color: Theme.of(context).colorScheme.error))),
            ],
          ))),
      actions: [
        TextButton(onPressed: openTutorial, child: const Text('连接教程')),
        if (widget.activeConnectOnly && state?['outboundEnabled'] == true)
          TextButton(
              onPressed: busy ? null : disconnectComputer,
              child: const Text('停止主动连接')),
        TextButton(
            onPressed: () => Navigator.pop(context), child: const Text('关闭')),
        if (widget.activeConnectOnly && !connected)
          ElevatedButton.icon(
              key: const ValueKey('computer-plugin-connect'),
              onPressed: busy ||
                      connecting ||
                      awaitingConnection ||
                      error != null ||
                      hostInput.text.trim().isEmpty ||
                      hostError != null
                  ? null
                  : connectComputer,
              icon: const Icon(Icons.link, size: 18),
              label: Text(connecting || awaitingConnection ? '正在连接…' : '连接电脑')),
      ],
    );
  }
}
