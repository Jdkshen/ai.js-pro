part of 'main.dart';

/// 宿主 mcpState 返回的 `localUrl`/`lanUrl` 已自带 `/mcp` 后缀（PocIntegration.mcpState），
/// Flutter 侧直接显示、不再追加；空值与旧宿主的 `<本机局域网IP>` 占位符视为未获取。
String _usableMcpUrl(dynamic value) {
  final text = value?.toString().trim() ?? '';
  if (text.isEmpty || text.startsWith('<') || text.contains('<本机')) return '';
  return text;
}

class DevicePage extends StatefulWidget {
  const DevicePage({Key? key}) : super(key: key);
  @override
  State<DevicePage> createState() => _DevicePageState();
}

class _DevicePageState extends State<DevicePage> with WidgetsBindingObserver {
  Map<String, dynamic> state = {};
  Map<String, dynamic>? device;
  Map<String, dynamic>? shizuku;
  String? shizukuError;
  bool requestingShizuku = false;
  Future<void>? refreshingShizuku;

  /// 「修复无障碍授权」的结果提示；null 表示还没点过。
  String? repairHint;
  bool repairRepeats = false;
  Timer? timer;
  bool busy = false;
  bool securityBusy = false;
  bool? target;
  bool? failedTarget;
  String? error;
  String? operationError;
  String? deviceError;

  /// 电脑连接（VSCode 调试插件 / 旧版开发者插件）：设备页直接给入口和摘要，
  /// 不必先进抽屉“开发者调试”。字段来自 computerPluginState 桥。
  Map<String, dynamic>? computer;
  String? computerError;
  bool computerLoading = false;
  final permissionErrors = <String, String>{};
  final openingPermissions = <String>{};
  Future<bool>? refreshing, refreshingDevice;
  bool foreground = true;
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    refresh();
    refreshDevice();
    refreshShizuku();
    refreshComputer();
    timer = Timer.periodic(const Duration(seconds: 2), (_) {
      if (foreground && ModalRoute.of(context)?.isCurrent != false) {
        if (!busy) refresh();
        refreshDevice();
        refreshShizuku();
      }
    });
  }

  @override
  void dispose() {
    timer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState value) {
    foreground = value == AppLifecycleState.resumed;
    if (foreground) {
      refresh();
      refreshDevice();
      refreshShizuku();
      refreshComputer();
    }
  }

  Future<bool> refresh() =>
      refreshing ??= readState().whenComplete(() => refreshing = null);

  Future<bool> readState() async {
    try {
      final reply = await LocalBridge.call('mcpState');
      if (reply is! Map || reply['running'] is! bool) {
        throw StateError('MCP 状态格式异常');
      }
      final value = Map<String, dynamic>.from(reply);
      if (mounted)
        setState(() {
          state = value;
          error = null;
          if (failedTarget != null && state['running'] == failedTarget) {
            operationError = null;
            failedTarget = null;
          }
        });
      return mounted;
    } catch (e) {
      if (mounted) setState(() => error = e.toString());
      return false;
    }
  }

  Future<bool> refreshDevice() => refreshingDevice ??=
      readDevice().whenComplete(() => refreshingDevice = null);

  Future<void> refreshShizuku() => refreshingShizuku ??=
      readShizuku().whenComplete(() => refreshingShizuku = null);

  Future<void> readShizuku() async {
    try {
      final reply = await LocalBridge.call('shizukuState');
      if (reply is! Map ||
          reply['running'] is! bool ||
          reply['authorized'] is! bool) {
        throw StateError('Shizuku 状态格式异常');
      }
      if (!mounted) return;
      setState(() {
        shizuku = Map<String, dynamic>.from(reply);
        shizukuError = null;
      });
      if (reply['phase'] == 'success') await refreshDevice();
    } catch (error) {
      if (mounted) setState(() => shizukuError = error.toString());
    }
  }

  Future<void> enableAccessibilityWithShizuku() async {
    if (requestingShizuku || shizuku?['busy'] == true) return;
    setState(() {
      requestingShizuku = true;
      shizukuError = null;
    });
    try {
      final reply = await LocalBridge.call('shizukuEnableAccessibility');
      if (reply is! Map) throw StateError('Shizuku 启动结果格式异常');
      if (mounted) setState(() => shizuku = Map<String, dynamic>.from(reply));
    } catch (error) {
      if (mounted) setState(() => shizukuError = error.toString());
    } finally {
      if (mounted) setState(() => requestingShizuku = false);
    }
  }

  Future<void> openShizuku() async {
    try {
      await LocalBridge.call('shizukuOpen');
    } catch (error) {
      if (mounted) setState(() => shizukuError = error.toString());
    }
  }

  Widget shizukuControls() {
    final pending = requestingShizuku || shizuku?['busy'] == true;
    final supported = shizuku?['supported'] == true;
    final installed = shizuku?['installed'] == true;
    final running = shizuku?['running'] == true;
    final hint = shizuku?['hint']?.toString() ?? '';
    final status = shizuku == null
        ? '正在读取 Shizuku 状态'
        : !supported
            ? 'Shizuku 启动需要 Android 7.0 及以上'
            : !installed
                ? '未安装 Shizuku'
                : !running
                    ? 'Shizuku 未运行，请先在 Shizuku 中启动服务'
                    : shizuku?['authorized'] == true
                        ? 'Shizuku 运行中 · 已授权'
                        : 'Shizuku 运行中 · 首次使用需授权';
    return Padding(
        padding: const EdgeInsets.only(bottom: 12),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(status, style: const TextStyle(fontSize: 12)),
          Wrap(spacing: 8, children: [
            TextButton.icon(
                key: const ValueKey('shizuku-accessibility'),
                onPressed: !pending &&
                        supported &&
                        installed &&
                        running &&
                        shizukuError == null
                    ? enableAccessibilityWithShizuku
                    : null,
                icon: pending
                    ? const SizedBox(
                        width: 18,
                        height: 18,
                        child: CircularProgressIndicator(strokeWidth: 2))
                    : const Icon(Icons.bolt, size: 18),
                label: Text(pending ? '正在通过 Shizuku 启动…' : 'Shizuku 启动无障碍')),
            TextButton(
                onPressed: installed && !pending ? openShizuku : null,
                child: const Text('打开 Shizuku')),
          ]),
          if (hint.isNotEmpty)
            Text(hint,
                key: const ValueKey('shizuku-result'),
                style: const TextStyle(fontSize: 12, height: 1.5)),
          if (shizukuError != null)
            Row(children: [
              Expanded(
                  child: Text(shizukuError!,
                      style: TextStyle(
                          fontSize: 12,
                          color: Theme.of(context).colorScheme.error))),
              TextButton(
                  onPressed: pending ? null : refreshShizuku,
                  child: const Text('重试')),
            ]),
        ]));
  }

  /// 让应用自己把被系统摘掉的无障碍授权补回来。
  /// 没授权 WRITE_SECURE_SETTINGS 时桥会返回一次性 adb 命令，原样显示给用户。
  Future<void> repairAccessibility() async {
    try {
      final reply = await LocalBridge.call('repairAccessibility');
      if (reply is! Map) throw StateError('修复结果格式异常');
      if (!mounted) return;
      setState(() {
        repairRepeats = reply['repaired'] == true;
        repairHint = reply['hint']?.toString() ??
            (reply['repaired'] == true ? '已恢复无障碍授权' : '未发生改变');
      });
      // 修复成功就顺带刷新授权状态，让上面的行立刻反映出来。
      if (reply['repaired'] == true) await refreshDevice();
    } catch (e) {
      if (mounted) setState(() => repairHint = e.toString());
    }
  }

  Future<bool> readDevice() async {
    try {
      final reply = await LocalBridge.call('deviceState');
      if (reply is! Map ||
          ['accessibilityEnabled', 'storageGranted', 'overlayGranted']
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

  /// 读取电脑连接状态（电脑插件是否已连接/未就绪、服务器模式地址）。
  /// 失败不影响 MCP/权限区，只在“电脑连接”卡片上显示可重试提示。
  Future<void> refreshComputer() async {
    if (computerLoading) return;
    computerLoading = true;
    try {
      final reply = await LocalBridge.call('computerPluginState');
      if (!mounted) return;
      setState(() {
        if (reply is Map) {
          computer = Map<String, dynamic>.from(reply);
          computerError = null;
        } else {
          computerError = '电脑连接状态格式异常';
        }
      });
    } catch (failure) {
      if (mounted) setState(() => computerError = failure.toString());
    } finally {
      computerLoading = false;
    }
  }

  /// 设备页入口：弹“设备连接信息”（与抽屉、插件页同一个 ComputerPluginSheet），
  /// 关闭后立即回读状态，避免摘要停在旧值。
  Future<void> openComputerConnection() async {
    await showDialog<void>(
        context: context,
        builder: (_) => ComputerPluginSheet(
              openPluginPage: () {
                Navigator.of(context).push(MaterialPageRoute<void>(
                    builder: (_) => Scaffold(
                        appBar: AppBar(title: const Text('本机插件')),
                        body: const CatalogPage(plugin: true, revision: 0))));
              },
            ));
    if (!mounted) return;
    await refreshComputer();
  }

  Future<void> permission(String method) async {
    if (openingPermissions.contains(method)) return;
    setState(() {
      openingPermissions.add(method);
      permissionErrors.remove(method);
    });
    try {
      await LocalBridge.call(method);
      if (mounted && foreground) refreshDevice();
    } catch (e) {
      if (mounted) {
        setState(() => permissionErrors[method] = e.toString());
        message(context, e.toString());
      }
    } finally {
      if (mounted) setState(() => openingPermissions.remove(method));
    }
  }

  Widget permissionRow(String key, String method, IconData icon, String title,
      String granted, String missing) {
    final known = device?[key] is bool;
    final grantStatus = !known
        ? '授权状态未知 · 点击打开系统设置'
        : deviceError != null
            ? '保留上次状态：${device?[key] == true ? '已授权' : '未授权'} · 点击打开系统设置'
            : device?[key] == true
                ? granted
                : missing;
    final status = permissionErrors.containsKey(method)
        ? '$grantStatus · 上次打开设置失败，点击重试'
        : grantStatus;
    return ListTile(
        key: ValueKey('device-permission-$key'),
        contentPadding: EdgeInsets.zero,
        leading: Icon(icon),
        title: Text(title),
        subtitle: Text(status),
        trailing: openingPermissions.contains(method)
            ? const SizedBox(
                width: 16,
                height: 16,
                child: CircularProgressIndicator(strokeWidth: 2))
            : null,
        onTap: openingPermissions.contains(method)
            ? null
            : () => permission(method));
  }

  Future<void> toggle(bool value) async {
    if (busy || securityBusy || state['running'] is! bool || error != null)
      return;
    setState(() {
      busy = true;
      target = value;
      operationError = null;
      failedTarget = null;
    });
    try {
      await LocalBridge.call('mcpSet', {'enabled': value});
      for (var i = 0; i < 20; i++) {
        if (!mounted ||
            !foreground ||
            ModalRoute.of(context)?.isCurrent == false) return;
        if (await refresh() && state['running'] == value) return;
        await Future<void>.delayed(const Duration(milliseconds: 500));
      }
      throw StateError(state['error']?.toString().isNotEmpty == true
          ? state['error']
          : 'MCP 状态切换超时');
    } catch (e) {
      if (mounted) {
        setState(() {
          operationError = e.toString();
          failedTarget = value;
        });
      }
    } finally {
      if (mounted)
        setState(() {
          busy = false;
          target = null;
        });
    }
  }

  Future<void> setLanAccess(bool value) async {
    if (busy || securityBusy || error != null) return;
    setState(() {
      securityBusy = true;
      operationError = null;
    });
    try {
      await LocalBridge.call('mcpSet', {
        'enabled': state['running'] == true,
        'lanEnabled': value,
      });
      await refresh();
      if (state['lanEnabled'] != value) throw StateError('局域网设置未保存');
    } catch (failure) {
      if (mounted) setState(() => operationError = failure.toString());
      await refresh();
    } finally {
      if (mounted) setState(() => securityBusy = false);
    }
  }

  /// 一个地址块：标题 + 用途说明 + 可选中地址 + 复制按钮。
  Widget _address(
      BuildContext context, String title, String hint, String address) {
    final muted = TextStyle(
        fontSize: 12,
        color: Theme.of(context).colorScheme.onSurface.withOpacity(.6));
    final available = address.isNotEmpty && !address.startsWith('<');
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text(title,
          style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w500)),
      const SizedBox(height: 2),
      Text(hint, style: muted),
      const SizedBox(height: 6),
      SelectableText(available ? address : '未获取到地址，请点右上角刷新',
          style: const TextStyle(
              fontFamily: 'monospace',
              fontFamilyFallback: monoFontFallback,
              fontSize: 14)),
      Align(
          alignment: Alignment.centerLeft,
          child: TextButton.icon(
              onPressed: available && !busy
                  ? () async {
                      await Clipboard.setData(ClipboardData(text: address));
                      if (mounted) message(context, '已复制$title');
                    }
                  : null,
              icon: const Icon(Icons.copy_outlined, size: 18),
              label: Text('复制$title'))),
    ]);
  }

  /// 标准 AI 客户端接入指引。原来只给一个开关和地址，用户还得自己查客户端
  /// 该怎么填；这里提供地址和配置示例。
  Widget _clientSection(BuildContext context) {
    // 与上面的地址块同源：mcpState 现在就会回 port/localUrl/lanUrl。
    final running = state['running'] == true;
    final port = state['port'] is int ? state['port'] as int : 8766;
    // 宿主 URL 已带 /mcp：直接显示，不再追加；空/占位符时退到本机地址（只补一个 /mcp）。
    final lan = _usableMcpUrl(state['lanUrl']);
    final local = _usableMcpUrl(state['localUrl']);
    final url = lan.isNotEmpty
        ? lan
        : (local.isNotEmpty ? local : 'http://127.0.0.1:$port/mcp');
    final config = const JsonEncoder.withIndent('  ').convert({
      'mcpServers': {
        'autojs-pro': {'url': url}
      }
    });
    final muted = TextStyle(
        fontSize: 12,
        color: Theme.of(context).colorScheme.onSurface.withOpacity(.6));
    return GlassSurface(
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Row(children: const [
        Icon(Icons.extension_outlined, color: Color(0xff008f91)),
        SizedBox(width: 10),
        Expanded(
            child: Text('接入 AI 客户端',
                style: TextStyle(fontSize: 18, fontWeight: FontWeight.w500))),
      ]),
      const SizedBox(height: 6),
      Text(running ? '直接使用下方地址连接，无需访问令牌。' : 'MCP 默认关闭，按需开启；局域网访问需单独开启。',
          style: const TextStyle(fontSize: 13)),
      const SizedBox(height: 4),
      Text(
          '工具：runScript（返回真实输出与出错原因）、stopAllScripts、checkSyntax、getUiLayout、'
          'captureScreen（默认缩放到宽 720）、getMcpLog、listScripts、deviceInfo、learnAutoJsSkill。'
          'getUiLayout 和 captureScreen 需要无障碍服务运行中，'
          '两者不可用时会直接说明原因。',
          style: muted),
      const SizedBox(height: 12),
      SelectableText(running ? url : '开启服务后显示地址',
          style: const TextStyle(
              fontFamily: 'monospace',
              fontFamilyFallback: monoFontFallback,
              fontSize: 14)),
      const SizedBox(height: 8),
      Wrap(children: [
        TextButton.icon(
            onPressed: running && url.isNotEmpty
                ? () async {
                    await Clipboard.setData(ClipboardData(text: url));
                    if (mounted) message(context, '已复制接入地址');
                  }
                : null,
            icon: const Icon(Icons.copy_outlined, size: 18),
            label: const Text('复制接入地址')),
        TextButton.icon(
            onPressed: running && url.isNotEmpty
                ? () async {
                    await Clipboard.setData(ClipboardData(text: config));
                    if (mounted) message(context, '已复制客户端配置');
                  }
                : null,
            icon: const Icon(Icons.content_paste_outlined, size: 18),
            label: const Text('复制客户端配置')),
      ]),
      const SizedBox(height: 8),
      Text('客户端配置示例（按客户端支持的 HTTP 连接方式添加）：', style: muted),
      const SizedBox(height: 6),
      Container(
          width: double.infinity,
          padding: const EdgeInsets.all(10),
          decoration: BoxDecoration(
              color:
                  Theme.of(context).colorScheme.surfaceVariant.withOpacity(.4),
              borderRadius: BorderRadius.circular(8)),
          child: SelectableText(config,
              style: const TextStyle(
                  fontFamily: 'monospace',
                  fontFamilyFallback: monoFontFallback,
                  fontSize: 12))),
      if (state['protocolVersion'] != null)
        Padding(
            padding: const EdgeInsets.only(top: 8),
            child: Text(
                '协议版本 ${state['protocolVersion']} · '
                '端口 $port；局域网访问由上方开关控制。',
                style: muted)),
    ]));
  }

  @override
  Widget build(BuildContext context) {
    final running = state['running'] == true;
    final port = state['port'] is int ? state['port'] as int : 8766;
    // 宿主返回的 localUrl/lanUrl 已带 /mcp：直接显示不追加；空/占位符视为未获取。
    final lanUrl = _usableMcpUrl(state['lanUrl']);
    final hostLocalUrl = _usableMcpUrl(state['localUrl']);
    final localUrl =
        hostLocalUrl.isNotEmpty ? hostLocalUrl : 'http://127.0.0.1:$port/mcp';
    // USB 转发提示里的端口跟本机地址保持一致，避免两处对不上。
    final forwardPort = Uri.tryParse(localUrl)?.port ?? port;
    final computerDetail = computerConnectionDetail(computer);
    // 用 SingleChildScrollView 而不是 ListView：ListView 只给可见区域建元素，
    // 页面加了接入指引以后下面的权限行在元素树里就不存在了。
    return SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(6, 6, 6, 24),
        child:
            Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          GlassSurface(
              child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                Row(children: [
                  const Icon(Icons.hub_outlined, color: Color(0xff008f91)),
                  const SizedBox(width: 10),
                  const Expanded(
                      child: Text('MCP 服务',
                          style: TextStyle(
                              fontSize: 18, fontWeight: FontWeight.w500))),
                  if (state['running'] is bool)
                    Semantics(
                        container: true,
                        label: 'MCP 服务开关',
                        child: Switch(
                            key: const ValueKey('device-mcp-toggle'),
                            activeColor: const Color(0xff008f91),
                            value: running,
                            onChanged: busy || securityBusy || error != null
                                ? null
                                : toggle))
                  else
                    const Text('未知', style: TextStyle(fontSize: 12))
                ]),
                Text(
                    busy
                        ? (target! ? '正在启动…' : '正在停止…')
                        : error != null
                            ? (state['running'] is! bool
                                ? '服务状态未知'
                                : '保留上次状态：${running ? '运行中' : '已停止'} · 未确认')
                            : state['running'] is! bool
                                ? '正在读取服务状态…'
                                : running
                                    ? '服务运行中'
                                    : '服务已停止',
                    style: const TextStyle(fontSize: 13)),
                SwitchListTile(
                    contentPadding: EdgeInsets.zero,
                    title: const Text('允许局域网连接'),
                    subtitle: const Text('默认关闭；在可信 WiFi 开启后，电脑可直接连接。'),
                    value: state['lanEnabled'] == true,
                    onChanged: busy ||
                            securityBusy ||
                            error != null ||
                            state['running'] is! bool
                        ? null
                        : setLanAccess,
                    key: const ValueKey('device-mcp-lan-toggle')),
                const SizedBox(height: 12),
                // 两个地址分别说明用途：局域网给同一 WiFi 下的电脑，本机配合 USB 转发。
                // 之前只显示一个，还会把「有局域网地址」误报成「本机地址，需 USB 转发」。
                if (running && error == null) ...[
                  if (state['lanEnabled'] == true) ...[
                    if (lanUrl.isNotEmpty)
                      _address(context, '局域网地址', '电脑与手机连接同一可信 WiFi 后，直接使用此地址',
                          lanUrl)
                    else
                      // 旧宿主会返回 `<本机局域网IP>` 占位符：不显示假地址，给可操作提示。
                      Padding(
                          padding: const EdgeInsets.only(bottom: 10),
                          child: Text('未获取到局域网地址（请确认手机已连接 WiFi）；可先用下方本机地址',
                              style: TextStyle(
                                  fontSize: 12,
                                  color: Theme.of(context)
                                      .colorScheme
                                      .onSurface
                                      .withOpacity(.6)))),
                    const SizedBox(height: 10),
                  ] else
                    Padding(
                        padding: const EdgeInsets.only(bottom: 10),
                        child: Text('局域网连接已关闭：仅本机地址（USB 转发）可用，可在上方开关开启',
                            style: TextStyle(
                                fontSize: 12,
                                color: Theme.of(context)
                                    .colorScheme
                                    .onSurface
                                    .withOpacity(.6)))),
                  _address(
                      context,
                      '本机地址',
                      'USB 连接时先执行 adb forward tcp:$forwardPort tcp:$forwardPort',
                      localUrl),
                ] else
                  Text(error != null ? '地址状态未确认，请刷新后复制' : '开启服务后显示地址',
                      style: const TextStyle(
                          fontFamily: 'monospace',
                          fontFamilyFallback: monoFontFallback,
                          fontSize: 14)),
                if (error != null)
                  Text(error!,
                      style: TextStyle(
                          color: Theme.of(context).colorScheme.error)),
                if (operationError != null)
                  Text(operationError!,
                      style: TextStyle(
                          color: Theme.of(context).colorScheme.error)),
                if (state['error']?.toString().isNotEmpty == true)
                  Text(state['error'],
                      style: TextStyle(
                          color: Theme.of(context).colorScheme.error)),
              ])),
          const SizedBox(height: 6),
          // 电脑连接（VSCode / 旧版开发者插件）：不进抽屉也能看到真实状态并管理。
          GlassSurface(
              blur: false,
              child: ListTile(
                  key: const ValueKey('device-computer-connection'),
                  contentPadding: const EdgeInsets.symmetric(horizontal: 12),
                  leading: Icon(
                      computer != null &&
                              computerClientStateOf(computer) ==
                                  ComputerClientState.connected
                          ? Icons.desktop_windows
                          : Icons.desktop_windows_outlined,
                      color: const Color(0xff008f91)),
                  title: const Text('电脑连接', style: TextStyle(fontSize: 16)),
                  subtitle: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                            computerConnectionStatus(computer,
                                error: computerError),
                            style: const TextStyle(fontSize: 13)),
                        if (computerDetail != null)
                          Padding(
                              padding: const EdgeInsets.only(top: 2),
                              child: Text(computerDetail,
                                  style: TextStyle(
                                      fontSize: 11,
                                      color: Theme.of(context)
                                          .colorScheme
                                          .onSurface
                                          .withOpacity(.6)))),
                      ]),
                  trailing: computerLoading
                      ? const SizedBox(
                          width: 18,
                          height: 18,
                          child: CircularProgressIndicator(strokeWidth: 2))
                      : const Icon(Icons.chevron_right),
                  onTap: openComputerConnection)),
          const SizedBox(height: 6),
          GlassSurface(
              blur: false,
              child: Column(children: [
                permissionRow(
                    'accessibilityEnabled',
                    'accessibilitySettings',
                    Icons.accessibility_new_rounded,
                    '无障碍服务',
                    '已启用 · 控件读取和自动操作',
                    '未启用 · 点击打开系统设置'),
                shizukuControls(),
                permissionRow(
                    'storageGranted',
                    'storagePermission',
                    Icons.folder_outlined,
                    '存储权限',
                    '已授权 · 读取和保存本地脚本',
                    '未授权 · 点击打开系统设置'),
                permissionRow(
                    'overlayGranted',
                    'overlaySettings',
                    Icons.picture_in_picture_alt_rounded,
                    '悬浮窗权限',
                    '已授权 · 点击打开系统设置',
                    '未授权 · 点击打开系统设置'),
                if (deviceError != null)
                  Text(deviceError!,
                      style: TextStyle(
                          color: Theme.of(context).colorScheme.error)),
                Align(
                    alignment: Alignment.centerLeft,
                    child: TextButton(
                        onPressed: refreshDevice, child: const Text('刷新权限状态'))),
                const Divider(height: 18),
                const Text(
                    '无障碍关闭后会保持关闭，不再后台自动补回。需要控件操作时，'
                    '可手动开启，或点击 Shizuku 启动/下方修复按钮。',
                    style: TextStyle(fontSize: 12, height: 1.5)),
                const SizedBox(height: 6),
                Align(
                    alignment: Alignment.centerLeft,
                    child: TextButton.icon(
                        onPressed: busy ? null : repairAccessibility,
                        icon: const Icon(Icons.build_outlined, size: 18),
                        label: const Text('修复无障碍授权'))),
                if (repairHint != null)
                  SelectableText(repairHint!,
                      style: TextStyle(
                          fontSize: 12,
                          height: 1.5,
                          fontFamily: 'monospace',
                          fontFamilyFallback: monoFontFallback,
                          color: repairRepeats
                              ? Theme.of(context).colorScheme.primary
                              : Theme.of(context).colorScheme.onSurface)),
              ])),
          const SizedBox(height: 6),
          // 接入指引放在权限之后：先看系统授权状态，再看怎么接客户端。
          _clientSection(context),
        ]));
  }
}
