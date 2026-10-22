part of 'main.dart';

/// 首次启动权限引导（玻璃风格、一次性、不阻塞）。
///
/// 新 Flutter 界面取代旧 Miuix 主页后，旧的首启权限弹窗不会再出现。这里在首帧后读一次
/// `deviceState`，把「存储 / 悬浮窗 / 无障碍」状态列出来；缺什么就给「去开启」
/// （走已有桥 storagePermission / overlaySettings / accessibilitySettings）。
///
/// 「只提示一次」的落点：
/// - 弹出前就把标记写进 glass 偏好（`flutter-glass` 的 browserPreferences JSON；
///   Dart 侧没有通用 SharedPreferences 桥，这是目前唯一能读写任意 key 的 glass 偏好路径），
///   弹窗期间进程被杀也不会重复打扰；scripts/samples 两个 profile 各写一份，
///   用户改其中一个目录设置也还记得；
/// - 三项权限都就绪时不弹、也不写标记 —— 将来权限被收回时仍可提示一次；
/// - 用户随时可到「设置 → 设备连接」重新查看并开启（已有页面）。
const String _permissionGuidePrefKey = 'permissionGuidePrompted';

/// 本次进程内是否已调度过（避免 Home 重建反复挂 post-frame）。
bool _permissionGuideScheduled = false;

/// 正在展示引导，避免桥回调/生命周期重复触发弹窗。
bool _permissionGuideShowing = false;

/// App 首帧后异步检查一次；由 `_HomeState.schedulePermissionGuide()` 调用。
Future<void> showPermissionGuideIfNeeded(BuildContext context) async {
  if (_permissionGuideShowing) return;
  if (await _permissionGuidePrompted()) return;
  final Map<String, dynamic> state;
  try {
    final reply = await LocalBridge.call('deviceState');
    if (reply is! Map) return;
    state = Map<String, dynamic>.from(reply);
  } catch (_) {
    // 状态读不到就不打扰用户、也不写标记（下次启动再试）。
    return;
  }
  final missing = state['storageGranted'] != true ||
      state['overlayGranted'] != true ||
      state['accessibilityEnabled'] != true;
  if (!missing) return;
  _permissionGuideShowing = true;
  // 先写标记再弹：一次性引导不应因弹窗期间进程退出而重复出现。
  await _rememberPermissionGuidePrompted();
  try {
    await showDialog<void>(
        context: context,
        barrierDismissible: true,
        barrierColor: Colors.black54,
        builder: (_) => _PermissionGuideDialog(initial: state));
  } catch (_) {
    // 页面已销毁/无法挂载：静默（标记已写，不会再重复弹）。
  } finally {
    _permissionGuideShowing = false;
  }
}

/// 读取「引导已提示」标记：两个 browserPreferences profile 任一命中即可。
Future<bool> _permissionGuidePrompted() async {
  for (final profile in const ['scripts', 'samples']) {
    try {
      final reply =
          await LocalBridge.call('browserPreferences', {'profile': profile});
      if (reply is Map && reply[_permissionGuidePrefKey] == true) return true;
    } catch (_) {
      // 桥不可用（旧构建/单测环境）按「未提示」处理，绝不因标记读取失败阻塞启动。
    }
  }
  return false;
}

/// 写入「引导已提示」标记（脚本/示例两个 profile 各写一份，减少丢失概率）。
Future<void> _rememberPermissionGuidePrompted() async {
  for (final profile in const ['scripts', 'samples']) {
    try {
      final reply =
          await LocalBridge.call('browserPreferences', {'profile': profile});
      final value =
          reply is Map ? Map<String, dynamic>.from(reply) : <String, dynamic>{};
      value[_permissionGuidePrefKey] = true;
      await LocalBridge.call(
          'browserPreferences', {'profile': profile, 'value': value});
    } catch (_) {
      // 标记写失败不打扰用户；最坏下次启动再提示一次。
    }
  }
}

class _PermissionGuideDialog extends StatefulWidget {
  const _PermissionGuideDialog({required this.initial});

  /// 弹出前读到的 deviceState 快照；弹窗内「去开启」返回后会刷新。
  final Map<String, dynamic> initial;

  @override
  State<_PermissionGuideDialog> createState() => _PermissionGuideDialogState();
}

class _PermissionGuideDialogState extends State<_PermissionGuideDialog>
    with WidgetsBindingObserver {
  late Map<String, dynamic> state = Map<String, dynamic>.from(widget.initial);
  final Set<String> busy = <String>{};
  String? error;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState value) {
    // 用户从系统设置页返回（恢复前台）时刷新状态：可能刚开启了某个权限。
    if (value == AppLifecycleState.resumed) refresh();
  }

  bool get storageReady => state['storageGranted'] == true;
  bool get overlayReady => state['overlayGranted'] == true;
  bool get accessibilityReady => state['accessibilityEnabled'] == true;

  String get accessibilityHint {
    if (accessibilityReady) return '已开启 · 控件读取与自动操作可用';
    if (state['accessibilityNeedsAttention'] == true) {
      return '曾开启，当前已关闭 · 点击重新开启';
    }
    return '未开启 · 自动点击与控件读取需要它';
  }

  Future<void> refresh() async {
    try {
      final reply = await LocalBridge.call('deviceState');
      if (!mounted || reply is! Map) return;
      setState(() => state = Map<String, dynamic>.from(reply));
    } catch (_) {
      // 状态刷新失败保留上一次结果，不影响关闭弹窗。
    }
  }

  Future<void> open(String kind) async {
    if (busy.contains(kind)) return;
    setState(() {
      busy.add(kind);
      error = null;
    });
    try {
      final method = kind == 'storage'
          ? 'storagePermission'
          : kind == 'overlay'
              ? 'overlaySettings'
              : 'accessibilitySettings';
      await LocalBridge.call(method);
      await refresh();
    } catch (e) {
      if (mounted) setState(() => error = '打开系统设置失败：${_readableError(e)}');
    } finally {
      if (mounted) setState(() => busy.remove(kind));
    }
  }

  String _readableError(Object error) {
    var message = error.toString().trim();
    if (message.startsWith('Bad state: ')) {
      message = message.substring('Bad state: '.length);
    } else if (message.startsWith('Exception: ')) {
      message = message.substring('Exception: '.length);
    }
    return message;
  }

  Widget _row({
    required String kind,
    required IconData icon,
    required String title,
    required String hint,
    required bool ready,
  }) {
    final onSurface = Theme.of(context).colorScheme.onSurface;
    final busyRow = busy.contains(kind);
    return Padding(
        padding: const EdgeInsets.only(top: 14),
        child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Icon(icon,
              size: 22,
              color: ready ? Theme.of(context).colorScheme.primary : onSurface),
          const SizedBox(width: 12),
          Expanded(
              child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                Text(title,
                    style: const TextStyle(
                        fontSize: 15, fontWeight: FontWeight.w500)),
                const SizedBox(height: 2),
                Text(hint,
                    style: TextStyle(
                        fontSize: 12, color: onSurface.withOpacity(.65))),
              ])),
          const SizedBox(width: 8),
          if (ready)
            Padding(
                padding: const EdgeInsets.only(top: 2),
                child: Icon(Icons.check_circle,
                    size: 20, color: Theme.of(context).colorScheme.primary))
          else
            TextButton(
                onPressed: busyRow ? null : () => open(kind),
                child: busyRow
                    ? const SizedBox(
                        width: 16,
                        height: 16,
                        child: CircularProgressIndicator(strokeWidth: 2))
                    : const Text('去开启')),
        ]));
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final onSurface = theme.colorScheme.onSurface;
    return AlertDialog(
        backgroundColor: Colors.transparent,
        elevation: 0,
        contentPadding: EdgeInsets.zero,
        insetPadding: const EdgeInsets.symmetric(horizontal: 18, vertical: 24),
        content: GlassSurface(
            radius: 22,
            padding: const EdgeInsets.fromLTRB(18, 16, 18, 10),
            child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(children: [
                    Icon(Icons.shield_outlined,
                        color: Theme.of(context).colorScheme.primary),
                    const SizedBox(width: 10),
                    Expanded(
                        child: Text('完善首次使用权限',
                            style: TextStyle(
                                fontSize: 18,
                                fontWeight: FontWeight.w500,
                                color: onSurface))),
                  ]),
                  const SizedBox(height: 8),
                  Text(
                      '这些权限分别用于读写脚本目录、显示悬浮控制条、执行自动点击与控件读取。'
                      '本引导只自动提示一次，之后可在「设置 → 设备连接」里随时调整。',
                      style: TextStyle(
                          fontSize: 12, color: onSurface.withOpacity(.65))),
                  _row(
                      kind: 'storage',
                      icon: Icons.folder_outlined,
                      title: '存储权限',
                      hint: storageReady ? '已开启 · 可读写脚本目录' : '未开启 · 无法保存或读取脚本',
                      ready: storageReady),
                  _row(
                      kind: 'overlay',
                      icon: Icons.picture_in_picture_alt_outlined,
                      title: '悬浮窗权限',
                      hint:
                          overlayReady ? '已开启 · 悬浮控制条可用' : '未开启 · 悬浮窗/控制条无法显示',
                      ready: overlayReady),
                  _row(
                      kind: 'accessibility',
                      icon: Icons.accessibility_new_rounded,
                      title: '无障碍服务',
                      hint: accessibilityHint,
                      ready: accessibilityReady),
                  if (error != null) ...[
                    const SizedBox(height: 10),
                    Text(error!,
                        style: TextStyle(
                            fontSize: 12, color: theme.colorScheme.error)),
                  ],
                  // 一次性引导：标记在弹出前就已写入，所以「稍后设置」和「不再提示」
                  // 都只是关闭本弹窗；之后可到「设置 → 设备连接」手动调整。
                  Align(
                      alignment: Alignment.centerRight,
                      child: Row(mainAxisSize: MainAxisSize.min, children: [
                        TextButton(
                            onPressed: () => Navigator.pop(context),
                            child: const Text('稍后设置')),
                        TextButton(
                            onPressed: () => Navigator.pop(context),
                            child: const Text('不再提示')),
                      ])),
                ])));
  }
}
