part of 'main.dart';

/// 目录数据来自宿主同步桥 [LocalBridge] 的 `localCatalog`（内置示例目录 / 本机已安装
/// SDK 插件），不再访问 127.0.0.1:19888 外部目录服务；导入走 `importCatalogResource`
/// 的 assetPath（宿主从 assets 复制，含伴随资源）。
/// The optional loader keeps the local catalogue independently verifiable.
class CatalogPage extends StatefulWidget {
  final bool plugin;
  final int revision;
  final bool active;
  final Future<List<dynamic>> Function(bool plugin)? loader;
  const CatalogPage(
      {Key? key, required this.plugin, required this.revision,
      this.active = true, this.loader})
      : super(key: key);
  @override
  State<CatalogPage> createState() => _CatalogPageState();
}

class _CatalogPageState extends State<CatalogPage> {
  List<Map<String, dynamic>>? items, imported;
  String? error, importError;
  String query = '', category = '';
  bool searching = false, importing = false, refreshing = false, mine = false;
  int requestId = 0, importRequestId = 0;
  final icons = <String, Future<String?>>{};
  final saving = <String>{};
  final contentRevision = ValueNotifier<int>(0);
  String? installing;
  final searchInput = TextEditingController();
  // 停用集合的来源始终是桥（持久化在 flutter-glass/plugin.disabled），页面不自己记账。
  Set<String> disabledPlugins = <String>{};
  bool pluginStatesLoaded = false;
  String? pluginStateError;
  final togglingPlugins = <String>{};
  int pluginStateRequestId = 0;

  // task-134（插件统一目录）：桥名**集中在这里**，与 W2 定稿对齐时只改这一处；URL 一律由原生侧解析。
  static const String pluginCatalogMethod = 'pluginCatalog';
  // 真实桥名（Lead 在 dev-1501 真机验过）：pluginInstall / pluginInstallStatus
  static const String pluginInstallMethod = 'pluginInstall';
  static const String pluginInstallStatusMethod = 'pluginInstallStatus';
  // 轻量节流：源不可达时不要每次进页都等 6s（下拉刷新/重试会强制绕过）
  static const Duration pluginCatalogThrottle = Duration(seconds: 60);
  DateTime? lastPluginCatalogAt;
  // task-143：Dart 侧**兜底**超时。原生候选链总预算 ~18–20s ⇒ 我们放到 25s，避免抢先于原生报错；
  // 原生的可解释原因（候选数/耗时）优先 —— 它回了错我们就照原样显示。
  // 若原生返回里带 timeoutMs，则下一轮用它 + 5s 余量。
  Duration pluginCatalogTimeout = const Duration(seconds: 25);
  Map<String, dynamic> pluginManifest = <String, dynamic>{};
  bool pluginManifestLoaded = false;
  String? pluginManifestError;
  // task-137：失败时要显示「当前更新源」+ 一句怎么办（值来自既有 updateSource 桥）
  String updateSourceValue = '';
  /// 清单来源标签（来自 pluginCatalog 的 data.source；空 ⇒ 页面不显示该行）
  String manifestSourceLabel = '';
  /// notes 展开状态（长 changelog 折叠）
  final Set<String> expandedNotes = <String>{};
  /// task-147：被系统「未知来源」权限挡住的插件（失败原因码 permission）⇒ 卡片上给「去设置」
  final Set<String> permissionBlocked = <String>{};
  /// 原生给的按钮文案（actionLabel；默认「去设置」）
  final Map<String, String> permissionActionLabel = <String, String>{};
  bool updateSourceLoaded = false;
  final Map<String, Map<String, dynamic>> downloading = <String, Map<String, dynamic>>{};
  final Map<String, String> downloadError = <String, String>{};
  // task-138：原生「只下载、不安装」时记录待安装产物（Android 不允许静默安装 ⇒ 安装必须交给系统安装器）
  final Map<String, Map<String, dynamic>> downloaded = <String, Map<String, dynamic>>{};
  bool reloadWhenActive = true;
  Future<void>? pendingLoad;
  bool? pendingPlugin;
  int? pendingRevision;

  void changed() => WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) contentRevision.value++;
      });

  String text(Map item, String key, [String fallback = '']) =>
      item[key]?.toString().trim() ?? fallback;
  String package(Map item) =>
      text(item, 'package_name', text(item, 'packageName'));
  String summary(Map item) => text(item, 'summary', text(item, 'description'));
  String author(Map item) {
    final supplied = text(item, 'author');
    if (supplied.isNotEmpty) return supplied;
    final user = item['user'];
    return user is Map ? text(user, 'fullName') : '';
  }

  String name(Map item) => text(item, 'name', '本地资源');
  bool? installed(Map item) =>
      item['installed'] is bool ? item['installed'] as bool : null;
  bool isLocal(Map item) => item['local'] == true;

  /// 桥错误 → 一行可读中文：去掉 StateError/Exception 前缀并截断，
  /// 避免把整段 SocketException/堆栈原样显示在失败条上。
  String readable(Object error) {
    var message = error.toString().trim();
    if (message.startsWith('Bad state: ')) {
      message = message.substring('Bad state: '.length);
    } else if (message.startsWith('Exception: ')) {
      message = message.substring('Exception: '.length);
    }
    if (message.length > 160) {
      message = '${message.substring(0, 160)}…';
    }
    return message.isEmpty ? '未知错误' : message;
  }

  String versionOf(Map item) =>
      text(item, 'versionName', text(item, 'version', '未知'));
  /// 兼容入口：三处渲染共用四态判据（task-134）。旧的三态逻辑保留在 _pluginStatusFallback 里。
  String pluginStatus(Map item, {bool detail = false}) =>
      pluginStateLabel(item, detail: detail);

  String _pluginStatusFallback(Map item, {bool detail = false}) {
    if (pluginDisabled(item)) {
      return detail ? '已停用 · 脚本不可用' : '已停用';
    }
    final value = installed(item);
    if (value == true) {
      return detail ? (isLocal(item) ? '已安装 · 本机 SDK 插件' : '已安装') : '已安装';
    }
    if (value == false) return detail ? '未安装 · 本地 SDK 安装包' : '未安装';
    return detail ? '安装状态未知 · 本地插件目录' : '安装状态未知';
  }

  bool pluginDisabled(Map item) {
    final name = package(item);
    return name.isNotEmpty && disabledPlugins.contains(name);
  }

  /// 脚本可用集合：已安装且未停用。停用的插件仍留在管理列表里，只是不计入这一集合。
  bool scriptUsable(Map item) =>
      installed(item) == true && !pluginDisabled(item);

  /// 只读拉取插件清单（W2 的 plugins.json 经原生桥）；**拿不到就显式说**，不静默、不猜。
  /// 只读取「当前更新源」（与设置页同一个桥 updateSource）；拿不到也要说，不静默。
  Future<void> loadUpdateSource() async {
    try {
      final value = await LocalBridge.call('updateSource');
      if (!mounted) return;
      setState(() {
        updateSourceValue = value is String ? value : '';
        updateSourceLoaded = true;
      });
    } catch (_) {
      if (mounted) {
        setState(() {
          updateSourceValue = '（更新源读取失败）';
          updateSourceLoaded = true;
        });
      }
    }
  }

  /// 一键重试：源 + 清单 + 本地目录各再拉一次（失败态的唯一出口）。
  Future<void> retryPluginCatalog() async {
    if (mounted) {
      setState(() {
        downloadError.clear();
        downloading.clear();
      });
    }
    await loadUpdateSource();
    await loadPluginCatalog(force: true);
    if (mounted && widget.plugin) load();
  }

  Future<void> loadPluginCatalog({bool force = false}) async {
    if (!widget.plugin) return;
    final now = DateTime.now();
    final last = lastPluginCatalogAt;
    if (!force && last != null && now.difference(last) < pluginCatalogThrottle) {
      // 节流命中：不重复拉（避免源不可达时每次进页都等 6s）
      return;
    }
    lastPluginCatalogAt = now;
    try {
      final value = await LocalBridge
          .call(pluginCatalogMethod, const {})
          // task-135：桥未响应也必须落到可见降级（否则提示永远不出现 = 静默失败）
          .timeout(pluginCatalogTimeout,
              onTimeout: () => throw StateError('清单读取超时（桥未响应）'));
      // 真实形态：{ok:true, data:{url, plugins:[…]}}（兼容裸数组/裸 plugins）
      final envelope = value is Map ? Map<String, dynamic>.from(value) : <String, dynamic>{};
      final data = envelope['data'] is Map
          ? Map<String, dynamic>.from(envelope['data'] as Map)
          : envelope;
      final url = data['url'];
      // 原生若告知它的总预算，就按它 + 5s 余量，避免我们抢先（task-143）
      final nativeTimeout = data['timeoutMs'];
      if (nativeTimeout is num && nativeTimeout > 0) {
        pluginCatalogTimeout =
            Duration(milliseconds: (nativeTimeout + 5000).round());
      }
      // task-141：清单来源标签（builtin-release / builtin-raw / custom…）——**有才显示，不猜**
      final sourceLabel = data['source'];
      final raw = data['plugins'] is List ? data['plugins'] : value;
      final next = <String, dynamic>{};
      if (raw is List) {
        for (final entry in raw) {
          if (entry is! Map) continue;
          final manifest = Map<String, dynamic>.from(entry);
          final key = text(manifest, 'id').isNotEmpty
              ? text(manifest, 'id')
              : text(manifest, 'package');
          if (key.isNotEmpty) next[key] = manifest;
        }
      }
      if (!mounted) return;
      setState(() {
        pluginManifest = next;
        pluginManifestLoaded = true;
        pluginManifestError = next.isEmpty ? '清单为空' : null;
        // task-145：重新拉到清单 ⇒ 清掉旧失败态（否则旧版本留下的「下载失败」会一直挂着误导 ✗）
        downloadError.clear();
        downloading.clear();
        if (url is String && url.isNotEmpty) {
          updateSourceValue = url;
          updateSourceLoaded = true;
        }
        manifestSourceLabel = sourceLabel is String ? sourceLabel : '';
      });
    } catch (e) {
      if (mounted) {
        setState(() {
          pluginManifestLoaded = true;
          pluginManifestError = readable(e);
        });
      }
    }
  }

  /// 是否「已下载、待安装」（原生返回下载完成但未安装时记录）。
  bool canInstallDownloaded(Map item) {
    final record = downloaded[package(item)];
    final path = record == null ? '' : (record['path'] ?? '').toString();
    return path.isNotEmpty && path.toLowerCase().endsWith('.apk');
  }

  String downloadedSizeLabel(Map item) {
    final record = downloaded[package(item)];
    final size = record == null ? null : record['sizeBytes'];
    final label = sizeLabel(size);
    return label.isEmpty ? '' : '，' + label;
  }

  /// 安装已下载的 APK：**复用既有 `installPlugin` 桥**（它本来就是「把本地 APK 交给系统安装器」）。
  /// Android 不允许静默安装 ⇒ 这一步只能由用户确认（文案里说明白）。
  /// task-147/148：只有**用户点击**才跳系统设置（W2 的 openInstallPermissionSettings，无参）。
  /// 返回 {ok, data:{opened, canInstall}}：ok=false ⇒ 明确提示「打不开设置页」（不静默 ✓）；
  /// 若返回 canInstall=true ⇒ 说明权限已开，清掉本项目上的 permission 提示并提示可重试。
  Future<void> openInstallPermissionSettings(Map item) async {
    final pkg = package(item);
    try {
      final reply =
          await LocalBridge.call('openInstallPermissionSettings', const {});
      final ok = reply is Map && reply['ok'] == true;
      final data = reply is Map && reply['data'] is Map
          ? Map<String, dynamic>.from(reply['data'] as Map)
          : <String, dynamic>{};
      if (!mounted) return;
      if (!ok) {
        notify('打不开设置页（可手动到：设置 → 应用 → AI.js Pro → 安装未知应用）');
        return;
      }
      if (data['canInstall'] == true) {
        setState(() => permissionBlocked.remove(pkg));
        notify('权限已开启，可重试「下载安装」');
      } else {
        notify('已打开设置页：请允许「安装未知应用」后返回重试');
      }
    } catch (e) {
      if (mounted) notify('打不开设置页：' + readable(e));
    }
  }

  Future<void> installDownloaded(Map item) async {
    final record = downloaded[package(item)];
    final path = record == null ? '' : (record['path'] ?? '').toString();
    if (path.isEmpty || package(item).isEmpty) return;
    try {
      final result = await LocalBridge.call('installPlugin', {
        'path': path,
        'packageName': package(item),
      });
      if (!mounted) return;
      if (result is Map && result['cancelled'] == true) return;
      if (result != true) throw StateError('系统安装界面尚未确认打开');
      await load();
      if (mounted) notify('已打开系统安装界面（Android 不允许静默安装，需你确认）；装完可回本页刷新');
    } catch (e) {
      if (mounted) notify(readable(e));
    }
  }

  /// 清单里对应的条目（按包名或 id 匹配）；没有清单就返回 null ⇒ 退回今天的显示。
  Map<String, dynamic>? manifestOf(Map item) {
    if (pluginManifest.isEmpty) return null;
    final pkg = package(item);
    final id = text(item, 'id');
    for (final entry in pluginManifest.values) {
      if (entry is! Map) continue;
      final manifest = Map<String, dynamic>.from(entry);
      if (pkg.isNotEmpty && text(manifest, 'package') == pkg) return manifest;
      if (id.isNotEmpty && text(manifest, 'id') == id) return manifest;
    }
    return null;
  }

  String sizeLabel(Object? bytes) {
    if (bytes is! num || bytes <= 0) return '';
    final mib = bytes / (1024 * 1024);
    return (mib >= 1 ? mib.toStringAsFixed(1) : (bytes / 1024).toStringAsFixed(0) + ' KB') +
        (mib >= 1 ? ' MB' : '');
  }

  /// 四态判定（下载中 > 失败 > 可更新 > 已安装/未安装），三处渲染共用同一份判据。
  String pluginStateLabel(Map item, {bool detail = false}) {
    final pkg = package(item);
    final progress = downloading[pkg];
    // task-146：只有「运行中/有进度数字」才算下载中；空记录（陈旧）不显示 ✗
    final activeDownload = progress != null &&
        (progress['state'] == 'running' || progress['receivedBytes'] is num);
    if (activeDownload) {
      final total = progress['totalBytes'];
      final received = progress['receivedBytes'];
      if (total is num && total > 0 && received is num) {
        final percent = (received * 100 / total).clamp(0, 100).toStringAsFixed(0);
        return '下载中 ' + percent + '%（' + sizeLabel(received) + ' / ' + sizeLabel(total) + '）';
      }
      return '下载中…';
    }
    final failure = downloadError[pkg];
    if (failure != null) return UiCopy.downloadFailed + failure;
    if (canInstallDownloaded(item)) {
      return '已下载（待安装' + downloadedSizeLabel(item) + '）';
    }
    final manifest = manifestOf(item);
    // V4（task-156）：原生 detail 太长会被卡片截成「已装 versionCod…」（切掉最有用的版本号 ✗）
    // ⇒ 短文案（≤16 字）才用原生；否则用含版本号的短句。
    final nativeDetail = manifest == null ? '' : text(manifest, 'detail');
    if (nativeDetail.isNotEmpty && nativeDetail.length <= 16) return nativeDetail;
    if (pluginLooksInstalled(item) && !pluginHasUpdate(item)) {
      return '已装 v' + versionOf(item) + (pluginUpToDate(item) ? ' · 最新' : '');
    }
    final remote = manifest == null ? '' : text(manifest, 'version');
    final remoteCode = manifest == null ? 0 : (manifest['versionCode'] is num ? manifest['versionCode'] as num : 0);
    final localCode = item['versionCode'] is num ? item['versionCode'] as num : 0;
    if (manifest != null && installed(item) == true && remoteCode > localCode && remote.isNotEmpty) {
      return '可更新到 v' + remote + '（当前 v' + versionOf(item) + '）';
    }
    if (manifest != null && installed(item) != true && remote.isNotEmpty) {
      final size = sizeLabel(manifest['sizeBytes']);
      return detail
          ? '未安装 · 可下载 v' + remote + (size.isEmpty ? '' : '（' + size + '）')
          : '未安装 · 可下载 v' + remote;
    }
    return _pluginStatusFallback(item, detail: detail);
  }

  /// 失败原因码 → 可读中文（W2 的结构化原因码；未知码不隐藏，原样带出来）。
  String downloadReason(Map<String, dynamic> result) {
    // 真实字段是 reasonCode（空串=成功/无原因）
    final code = text(result, 'reasonCode', text(result, 'reason', text(result, 'code')));
    switch (code) {
      case 'network':
        return '网络不可达（下载源连不上）';
      case 'checksum':
        return '校验不符（sha256 与清单不一致，未安装）';
      case 'package':
        return '安装包与清单不一致（包名不符，未安装）';
      case 'permission':
        return '系统未允许安装（请在系统里允许本应用安装未知来源应用）';
      case 'missing':
        return '清单未收录该插件';
      default:
        return code.isEmpty ? readable(StateError(text(result, 'message', '未知原因'))) : code;
    }
  }

  /// 下载安装/更新：真实契约 = `pluginInstall(id)` → `pluginInstallStatus(id)` 轮询到终态。
  /// URL 由原生侧解析（Dart 只传 id）；失败用 `reasonCode` 映射可读原因；成功按 `requiresRestart` 提示重启。
  Future<void> downloadPlugin(Map item) async {
    if (!widget.plugin) return;
    final manifest = manifestOf(item);
    final pkg = package(item);
    if (manifest == null || pkg.isEmpty) {
      notify('清单未收录该插件，无法下载');
      return;
    }
    final id = text(manifest, 'id');
    setState(() {
      downloading[pkg] = <String, dynamic>{};
      downloadError.remove(pkg);
    });
    changed();
    try {
      final started = await LocalBridge.call(pluginInstallMethod, {'id': id});
      if (started is Map && mounted) {
        setState(() => downloading[pkg] = Map<String, dynamic>.from(started));
      }
      // task-144：**只要有进展就一直轮询**（大包慢网必然超过固定窗口）——
      // 每 2s 一次；只有在「120s 内 receivedBytes 没有任何增长」时才判超时，**总时长不设硬上限**。
      const pollInterval = Duration(seconds: 2);
      const stallLimit = Duration(seconds: 120);
      var stalled = Duration.zero;
      num? lastReceived;
      while (mounted) {
        await Future<void>.delayed(pollInterval);
        if (!mounted) return;
        final status = await pollPluginProgress(pkg, id);
        if (status == null) {
          stalled += pollInterval; // 单次读失败不算失败，但要计入「无进展」
        } else {
          final state = text(status, 'state');
          final received = status['receivedBytes'];
          final progressed = received is num &&
              (lastReceived == null || received > lastReceived);
          if (progressed) {
            lastReceived = received;
            stalled = Duration.zero; // 有进展 ⇒ 重新计时（也不显示失败）
          } else {
            stalled += pollInterval;
          }
          if (state == 'success') {
            await load();
            if (!mounted) return;
            final message = text(status, 'message');
            final restart = status['requiresRestart'] == true ||
                manifest['requiresRestart'] == true;
            notify('安装已完成' +
                (message.isEmpty ? '' : '：' + message) +
                (restart ? ' · 需重启应用生效' : ''));
            return;
          }
          if ((state == 'failed' || state == 'error') && !progressed) {
            // task-144：区分「下载中断（网络）」与「长时间无进展」
            final code = text(status, 'reasonCode');
            final action = text(status, 'action');
            final message = text(status, 'message');
            // task-147/148：原生预检挡住安装 ⇒ code=permission 且带 action/actionLabel（用它的 message 最准）
            final needsSettings =
                code == 'permission' || action == 'openInstallPermissionSettings';
            final reason = needsSettings
                ? (message.isNotEmpty
                    ? message
                    : '系统未允许安装未知来源应用：请在 设置 → 应用 → AI.js Pro → 安装未知应用 里允许后重试')
                : code == 'network'
                    ? '下载中断（网络，可换网络或换源后重试）'
                    : downloadReason(status);
            if (needsSettings && mounted) {
              setState(() {
                permissionBlocked.add(pkg);
                permissionActionLabel[pkg] =
                    text(status, 'actionLabel', '去设置');
              });
            }
            setState(() => downloadError[pkg] = reason);
            notify(UiCopy.downloadFailed + reason);
            return;
          }
        }
        if (stalled >= stallLimit) {
          setState(() => downloadError[pkg] = '安装状态轮询超时（长时间无进展，可重试）');
          notify(UiCopy.downloadFailed + '安装状态轮询超时（长时间无进展，可点「重试」）');
          return;
        }
      }
    } catch (e) {
      if (mounted) {
        final reason = readable(e);
        setState(() => downloadError[pkg] = reason);
        notify(UiCopy.downloadFailed + reason);
      }
    } finally {
      if (mounted) {
        setState(() => downloading.remove(pkg));
        changed();
      }
    }
  }

  /// 读一次安装状态（只读轮询；单次失败返回 null，交给调用方继续等）。
  Future<Map<String, dynamic>?> pollPluginProgress(String pkg, String id) async {
    try {
      final value = await LocalBridge.call(
          pluginInstallStatusMethod, {'id': id, 'packageName': pkg});
      if (!mounted || value is! Map) return null;
      final next = Map<String, dynamic>.from(value);
      if (downloading.containsKey(pkg)) setState(() => downloading[pkg] = next);
      return next;
    } catch (_) {
      return null;
    }
  }

  /// 当前是否可下载/更新（有清单条目 + 没有其它下载在跑）。
  bool canDownloadPlugin(Map item) =>
      widget.plugin &&
      !downloading.containsKey(package(item)) &&
      manifestOf(item) != null &&
      // task-145：已装且已是最新 ⇒ 不给「下载安装/更新」按钮（避免「都最新版还要更新」✗）
      // task-149：已装（含原生 detail 判定为已装）且无可靠更新 ⇒ 不给按钮 ✗
      // task-150：state=install ⇒ 未安装可下载；state=update ⇒ 可更新；其它 ⇒ 不给按钮
      (pluginManifestState(item) == 'install' ||
          pluginManifestState(item) == 'update' ||
          (!pluginLooksInstalled(item) && pluginHasUpdate(item)) ||
          (!pluginLooksInstalled(item) &&
              manifestOf(item)!['actionable'] == true) ||
          pluginHasUpdate(item)) &&
      (text(manifestOf(item)!, 'file').isNotEmpty ||
          manifestOf(item)!['actionable'] == true);

  /// task-145：只有**可靠地**判定「清单比本机新」才算可更新（≥ 而非 > 的语义：
  /// 本机 code ≥ 清单 code ⇒ 已是最新，不给更新按钮）。任一侧缺 code ⇒ 不作判定（不猜）。
  /// task-150：**读结构化字段**（`detail` 只用于显示，不参与判定 ✗）。
  /// 清单每条带 `state`：`install` = 未安装（可下载安装）/ `update` = 已装可更新 / 其它（如 upToDate）= 已装已最新。
  String pluginManifestState(Map item) => text(manifestOf(item) ?? const {}, 'state');

  bool pluginLooksInstalled(Map item) {
    if (installed(item) == true) return true;
    final state = pluginManifestState(item);
    if (state == 'install') return false; // 结构化判定：未安装
    if (state == 'update') return true; // 已装（只是有更新）
    if (state == 'upToDate' || state == 'uptodate') return true;
    return false; // 没有结构信息就不猜
  }

  bool pluginHasUpdate(Map item) {
    final manifest = manifestOf(item);
    if (manifest == null) return false;
    // 结构化 state 优先（原生 PluginUpdatePlanner 的判定，我们不覆盖）
    final state = text(manifest, 'state');
    if (state == 'update') return true;
    if (state == 'install' || state == 'upToDate' || state == 'uptodate') {
      return false;
    }
    if (installed(item) != true) return false;
    final remote = manifest['versionCode'];
    final local = item['versionCode'];
    if (remote is! num || local is! num || local <= 0) return false;
    return remote > local; // 兜底：两侧都有 code 才比
  }

  /// 已装且能可靠判断「已是最新」时，给一句明确文案（≥ ⇒ 最新）。
  bool pluginUpToDate(Map item) {
    final manifest = manifestOf(item);
    if (manifest == null) return false;
    final state = text(manifest, 'state');
    if (state == 'upToDate' || state == 'uptodate') return true;
    if (state == 'install' || state == 'update') return false;
    if (installed(item) != true) return false;
    final remote = manifest['versionCode'];
    final local = item['versionCode'];
    if (remote is! num || local is! num || local <= 0) return false;
    return local >= remote;
  }

  String downloadButtonLabel(Map item) {
    return pluginHasUpdate(item) ? '更新' : '下载安装';
  }
  bool showPluginToggle(Map item) =>
      widget.plugin &&
      pluginStatesLoaded &&
      package(item).isNotEmpty &&
      (installed(item) == true || pluginDisabled(item));

  Future<void> loadPluginStates() async {
    if (!widget.plugin) return;
    final token = ++pluginStateRequestId;
    try {
      final result = await LocalBridge.call('pluginStates');
      if (!mounted || token != pluginStateRequestId) return;
      if (result is! Map || result['disabled'] is! List) {
        // 状态未知时不冒充任何一侧：开关保持只读，计数沿用目录项数。
        setState(() => pluginStatesLoaded = false);
        return;
      }
      final names = <String>{};
      for (final value in result['disabled'] as List) {
        final name = '$value'.trim();
        if (name.isNotEmpty) names.add(name);
      }
      setState(() {
        disabledPlugins = names;
        pluginStatesLoaded = true;
        pluginStateError = null;
      });
      changed();
    } catch (e) {
      if (mounted && token == pluginStateRequestId) {
        setState(() => pluginStateError = readable(e));
      }
    }
  }

  Future<void> togglePlugin(Map original, bool value) async {
    final item = currentItem(original) ?? original;
    final pkg = package(item);
    if (pkg.isEmpty ||
        !pluginStatesLoaded ||
        togglingPlugins.contains(pkg) ||
        pluginDisabled(item) == !value) return;
    setState(() => togglingPlugins.add(pkg));
    changed();
    try {
      final result = await LocalBridge.call(
          'pluginSetEnabled', {'packageName': pkg, 'enabled': value});
      if (result is! Map || result['enabled'] != value) {
        throw StateError('插件启停状态未被确认');
      }
      await loadPluginStates();
      if (!mounted) return;
      if (pluginDisabled(item) == value) throw StateError('插件启停状态未生效');
      notify(value ? '已启用：${name(item)}' : '已停用：${name(item)} · 不再计入脚本可用');
    } catch (e) {
      if (mounted) notify(readable(e));
    } finally {
      if (mounted) {
        setState(() => togglingPlugins.remove(pkg));
        changed();
      }
    }
  }

  void openComputerPlugin() => showDialog<void>(
      context: context, builder: (_) => const ComputerPluginSheet());

  String pluginIdentity(Map item) {
    final archive = text(item, 'url', text(item, 'path'));
    return '${package(item)}${archive.isEmpty ? '' : '@$archive'}';
  }

  Map<String, dynamic>? currentItem(Map original) {
    final source = original['importRecord'] == true ? imported : items;
    Map<String, dynamic>? samePackage;
    for (final item in source ?? <Map<String, dynamic>>[]) {
      if (widget.plugin) {
        if (pluginIdentity(item) == pluginIdentity(original)) return item;
        if (package(item) == package(original)) {
          if (installed(item) == true) return item;
          samePackage ??= item;
        }
      } else if (text(original, 'id').isNotEmpty) {
        if (text(item, 'id') == text(original, 'id')) return item;
      } else if (text(original, 'path').isNotEmpty) {
        if (text(item, 'path') == text(original, 'path')) return item;
      } else if (name(item) == name(original)) {
        return item;
      }
    }
    return samePackage;
  }

  /// 仅当宿主条目带本机 APK 路径且未安装时可重试安装；
  /// 内置目录不再提供 19888 下载 URL（旧 HTTP 目录服务已移除）。
  bool canRetryPlugin(Map item) {
    if (!widget.plugin || installed(item) != false || package(item).isEmpty) {
      return false;
    }
    final path = text(item, 'path');
    // Never derive a private filename from a package name. Android rechecks
    // the actual path and package before opening its prompt.
    return path.startsWith('/') && path.toLowerCase().endsWith('.apk');
  }

  Future<void> retryPlugin(Map original) async {
    if (installing != null || importing || refreshing || error != null) return;
    final item = currentItem(original);
    if (item == null || !canRetryPlugin(item)) return;
    final identity = pluginIdentity(item);
    setState(() => installing = identity);
    changed();
    try {
      final result = await LocalBridge.call('installPlugin', {
        'path': text(item, 'path'),
        'packageName': package(item),
      });
      if (!mounted) return;
      if (result is Map && result['cancelled'] == true) return;
      if (result != true) throw StateError('系统安装界面尚未确认打开');
      await load();
      if (mounted) notify('已打开系统安装界面，安装状态以返回后的目录为准');
    } catch (e) {
      if (mounted) notify(readable(e));
    } finally {
      if (mounted) {
        setState(() => installing = null);
        changed();
      }
    }
  }

  num size(Map item) {
    final value = item['fileSize'] ?? item['size'];
    return value is num ? value : num.tryParse('$value') ?? 0;
  }

  void notify(String value) {
    final messenger = ScaffoldMessenger.of(context);
    messenger.hideCurrentSnackBar();
    messenger.showSnackBar(SnackBar(
        behavior: SnackBarBehavior.floating,
        // Flutter 3.3's dismiss gesture also captures the bottom margin, which
        // would make the catalogue's bottom controls unresponsive.
        dismissDirection: DismissDirection.none,
        margin: EdgeInsets.fromLTRB(12, 0, 12,
            (widget.plugin ? 72 : 92) + MediaQuery.of(context).padding.bottom),
        content: Text(value, maxLines: 3, overflow: TextOverflow.ellipsis)));
  }

  void toggleSearch() => setState(() {
        searching = !searching;
        query = '';
        searchInput.clear();
      });

  @override
  void initState() {
    super.initState();
    if (widget.active) load();
    if (widget.plugin) loadPluginCatalog();
    if (widget.plugin) loadUpdateSource();
  }

  @override
  void didUpdateWidget(CatalogPage old) {
    super.didUpdateWidget(old);
    if (old.plugin != widget.plugin) {
      ++requestId;
      ++importRequestId;
      ++pluginStateRequestId;
      items = imported = null;
      error = importError = null;
      query = category = '';
      mine = searching = false;
      searchInput.clear();
      icons.clear();
      disabledPlugins = <String>{};
      pluginStatesLoaded = false;
      pluginStateError = null;
      togglingPlugins.clear();
      changed();
    }
    if (old.revision != widget.revision || old.plugin != widget.plugin) {
      reloadWhenActive = true;
    }
    if (widget.active && reloadWhenActive) load();
    // task-138：**每次进入插件 tab 都自动查一次清单**（VS Code 式自动检查更新）；失败也照样重试
    if (widget.active && widget.plugin) {
      loadPluginCatalog();
      loadUpdateSource();
    }
  }

  @override
  void dispose() {
    ++requestId;
    ++importRequestId;
    ++pluginStateRequestId;
    searchInput.dispose();
    contentRevision.dispose();
    super.dispose();
  }

  /// 宿主内置目录：同步桥 `localCatalog`（task-39）返回 {items: [...]}。
  /// 失败时优先使用宿主给的 {error: 中文}，否则给可读兜底文案（不再出现 SocketException）。
  Future<List<dynamic>> localCatalog(bool plugin) async {
    try {
      final value = await LocalBridge.call('localCatalog', {'plugin': plugin});
      if (value is! Map) throw StateError('内置目录数据格式异常');
      final result = Map<String, dynamic>.from(value);
      final items = result['items'];
      final message = text(result, 'error');
      if (items is! List) {
        throw StateError(message.isNotEmpty ? message : '内置目录数据格式异常');
      }
      // 宿主扫描失败时返回 {items: [], error: 中文}: 空列表 + 错误要显示可读提示并可重试。
      if (items.isEmpty && message.isNotEmpty) throw StateError(message);
      return items;
    } on StateError {
      rethrow;
    } catch (error) {
      throw StateError('内置目录读取失败：${readable(error)}');
    }
  }

  Future<void> load() async {
    reloadWhenActive = false;
    final previous = pendingLoad;
    if (previous != null && pendingPlugin == widget.plugin &&
        pendingRevision == widget.revision) return previous;
    final operation = loadCatalog();
    pendingLoad = operation;
    pendingPlugin = widget.plugin;
    pendingRevision = widget.revision;
    try {
      await operation;
    } finally {
      if (identical(pendingLoad, operation)) pendingLoad = null;
    }
  }

  Future<void> loadCatalog() async {
    final token = ++requestId;
    final plugin = widget.plugin;
    setState(() => refreshing = true);
    changed();
    // Records are independent: a failed catalogue refresh must not discard them.
    if (!plugin) loadImports();
    if (plugin) loadPluginStates();
    try {
      final value = await (widget.loader ?? localCatalog)(plugin);
      final result = value.map((item) {
        if (item is! Map) throw StateError('内置目录条目格式异常');
        return Map<String, dynamic>.from(item);
      }).toList();
      if (plugin) {
        // 本机已安装插件列表：已安装在前，同状态按名称排序，方便一眼看到可用插件。
        result.sort((a, b) {
          final stateA = installed(a) == true ? 0 : 1;
          final stateB = installed(b) == true ? 0 : 1;
          if (stateA != stateB) return stateA - stateB;
          return name(a).compareTo(name(b));
        });
      }
      if (!mounted || token != requestId) return;
      setState(() {
        items = result;
        icons.clear();
        error = null;
        // Preserve a valid filter, including when an earlier request arrives late.
        if (!mine && !result.any((x) => text(x, 'category') == category)) {
          category = '';
        }
      });
      changed();
    } catch (e) {
      if (mounted && token == requestId) {
        setState(() => error = readable(e));
        changed();
      }
    } finally {
      if (mounted && token == requestId) {
        setState(() => refreshing = false);
        changed();
      }
    }
  }

  Future<void> loadImports() async {
    final token = ++importRequestId;
    try {
      final result = await LocalBridge.call('importRecords');
      if (result is! List) throw StateError('导入记录格式异常');
      final records = result.whereType<Map>().where((item) {
        return item['kind'] != 'plugin';
      }).map((item) {
        final record = Map<String, dynamic>.from(item);
        record['category'] = '本地导入';
        record['summary'] = text(record, 'path');
        record['importRecord'] = true;
        return record;
      }).toList()
        ..sort((a, b) => (b['importedAt'] as num? ?? 0)
            .compareTo(a['importedAt'] as num? ?? 0));
      if (mounted && token == importRequestId) {
        setState(() {
          imported = records;
          importError = null;
        });
        changed();
      }
    } catch (e) {
      if (mounted && token == importRequestId) {
        setState(() => importError = readable(e));
      }
    }
  }

  Future<void> importFile() async {
    if (importing || installing != null) return;
    final plugin = widget.plugin;
    setState(() => importing = true);
    try {
      final result =
          await LocalBridge.call(plugin ? 'importPlugin' : 'importResource');
      if (!mounted || plugin != widget.plugin) return;
      if (result is Map && result['cancelled'] == true) return;
      if ((result is! String || result.trim().isEmpty) &&
          (result is! Map || text(result, 'path').isEmpty)) {
        throw StateError('导入未返回文件位置');
      }
      await load();
      if (mounted) {
        notify(plugin ? '已提交插件 APK，请完成系统安装' : '资源已导入');
      }
    } catch (e) {
      if (mounted) notify(readable(e));
    } finally {
      if (mounted) setState(() => importing = false);
    }
  }

  /// 内置示例条目自带 assetPath：直接让宿主从 assets 复制（含同目录伴随资源），
  /// 不再依赖 19888 的 project.zip 下载地址。
  bool canSave(Map item) {
    if (widget.plugin) return false;
    final assetPath = text(item, 'assetPath');
    return assetPath.isNotEmpty && !assetPath.contains('..');
  }

  Future<void> saveResource(Map item) async {
    final assetPath = text(item, 'assetPath');
    if (!canSave(item) || saving.contains(assetPath)) return;
    setState(() => saving.add(assetPath));
    try {
      final result = await LocalBridge.call('importCatalogResource', {
        'assetPath': assetPath,
        'name': name(item),
      });
      if (!mounted) return;
      if (result is Map && result['cancelled'] == true) return;
      final path = result is Map ? text(result, 'path') : '';
      if (path.isEmpty) throw StateError('导入未返回文件位置');
      await loadImports();
      if (mounted) notify('已导入到脚本目录\n$path');
    } catch (e) {
      if (mounted) notify(readable(e));
    } finally {
      if (mounted) setState(() => saving.remove(assetPath));
    }
  }

  Future<String?> pluginIcon(String packageName) =>
      icons.putIfAbsent(packageName, () async {
        if (packageName.isEmpty) return null;
        try {
          final result = await LocalBridge.call(
              'pluginIcon', {'packageName': packageName});
          return result is String && result.isNotEmpty ? result : null;
        } catch (_) {
          return null;
        }
      });

  Widget cover(Map item, {double? height}) {
    final key = package(item);
    final description = '${name(item)} ${summary(item)} $key'.toLowerCase();
    final ocr = description.contains('ocr');
    final zip = description.contains('zip');
    final tint = ocr
        ? const Color(0xff247da8)
        : zip
            ? const Color(0xff866fbe)
            : const Color(0xff008f91);
    final symbol = ocr
        ? Icons.document_scanner_outlined
        : zip
            ? Icons.folder_zip_outlined
            : Icons.extension_outlined;
    Widget fallback() => Icon(symbol, color: Colors.white, size: 58);
    // task-142：清单里的 iconPath（W2 把插件图标转 PNG 落到缓存，给绝对路径）优先
    final iconPath = manifestOf(item)?['iconPath']?.toString() ?? '';
    if (iconPath.isNotEmpty) {
      return Container(
          height: height,
          width: double.infinity,
          decoration: BoxDecoration(
              gradient: LinearGradient(
                  begin: Alignment.topLeft,
                  end: Alignment.bottomRight,
                  colors: [tint.withValues(alpha: .68), tint])),
          child: Center(
              child: Container(
                  padding: const EdgeInsets.all(10),
                  decoration: BoxDecoration(
                      borderRadius: BorderRadius.circular(22),
                      color: Colors.white.withValues(alpha: .91)),
                  child: Image.file(File(iconPath),
                      width: 58,
                      height: 58,
                      fit: BoxFit.contain,
                      errorBuilder: (_, __, ___) => fallback()))));
    }
    return Container(
        height: height,
        width: double.infinity,
        decoration: BoxDecoration(
            gradient: LinearGradient(
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
                colors: [tint.withValues(alpha: .68), tint])),
        child: Stack(children: [
          Positioned(
              right: -28,
              top: -24,
              child: Icon(symbol,
                  size: 148, color: Colors.white.withValues(alpha: .10))),
          Positioned(
              left: -12,
              bottom: -20,
              child: Container(
                  width: 84,
                  height: 84,
                  decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      border: Border.all(
                          width: 2, color: Colors.white.withValues(alpha: .18))))),
          Center(
              child: FutureBuilder<String?>(
                  future: installed(item) == true
                      ? pluginIcon(key)
                      : Future<String?>.value(null),
                  builder: (_, snapshot) {
                    if (snapshot.data == null) return fallback();
                    try {
                      return Container(
                          padding: const EdgeInsets.all(10),
                          decoration: BoxDecoration(
                              borderRadius: BorderRadius.circular(22),
                              color: Colors.white.withValues(alpha: .91)),
                          child: Image.memory(base64Decode(snapshot.data!),
                              width: 58,
                              height: 58,
                              fit: BoxFit.contain,
                              errorBuilder: (_, __, ___) => fallback()));
                    } catch (_) {
                      return fallback();
                    }
                  })),
        ]));
  }

  /// V3 补刀（task-157）：`category` 是**宿主运行时给的**层级路径
  /// （如「Node 引擎/Node.js/Node.js中使用UI」✗ —— 源码里没有字面量，静态 grep 找不到 ✓）
  /// ⇒ 只显示**最后一段**（没有 `/` 时原样），让元信息行回到单行。
  String categoryTail(String value) {
    final trimmed = value.trim();
    if (trimmed.isEmpty) return '';
    final index = trimmed.lastIndexOf('/');
    if (index < 0) return trimmed;
    final tail = trimmed.substring(index + 1).trim();
    return tail.isEmpty ? trimmed : tail;
  }

  /// V2（task-156）：卡片次级信息 = 引擎/语言（信息量 > 与区块标题重复的「本地示例工程」✓）。
  /// 结构化字段优先；没有就用名称/分类的文字提示；都认不出就返回空（不留空行 ✓）。
  String resourceEngineLabel(Map item) {
    for (final key in ['engine', 'language', 'runtime']) {
      final value = text(item, key);
      if (value.isNotEmpty) return value;
    }
    final hint = ('${text(item, 'category')} ${name(item)}').toLowerCase();
    if (hint.contains('node')) return 'Node.js';
    if (hint.contains('quickjs')) return 'QuickJS';
    if (hint.contains('rhino')) return 'Rhino';
    return '';
  }

  /// task-151：示例/资源卡片图标按引擎或语言区分（减少视觉噪音；认不出就退回 <>）。
  IconData resourceIcon(Map item) {
    final hint = ('${text(item, 'engine')} ${text(item, 'language')} '
            '${text(item, 'category')} ${name(item)} ${summary(item)}')
        .toLowerCase();
    if (hint.contains('node')) return Icons.terminal_rounded;
    if (hint.contains('quickjs')) return Icons.flash_on_rounded;
    if (hint.contains('rhino')) return Icons.settings_input_component_rounded;
    if (hint.contains('java')) return Icons.coffee_rounded;
    if (hint.contains('python')) return Icons.terminal;
    if (hint.contains('html') || hint.contains('vue') || hint.contains('react')) {
      return Icons.web_rounded;
    }
    return Icons.code;
  }

  String importedTime(Map item) {
    final timestamp = item['importedAt'];
    if (timestamp is! num || timestamp <= 0) return '';
    final date = DateTime.fromMillisecondsSinceEpoch(timestamp.toInt());
    String pad(int number) => '$number'.padLeft(2, '0');
    return '${date.year}-${pad(date.month)}-${pad(date.day)} ${pad(date.hour)}:${pad(date.minute)}';
  }

  void details(Map original) => showDialog<void>(
      context: context,
      builder: (_) => ValueListenableBuilder<int>(
          valueListenable: contentRevision,
          builder: (_, __, ___) {
            final current = currentItem(original);
            final item = current ?? {...original, 'installed': null};
            return AlertDialog(
                shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(22)),
                title: Text(name(item)),
                content: SizedBox(
                    width: 420,
                    child: SingleChildScrollView(
                        child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                          if (widget.plugin)
                            ClipRRect(
                                borderRadius: BorderRadius.circular(16),
                                child: cover(item, height: 130)),
                          const SizedBox(height: 12),
                          if (current == null)
                            const Padding(
                                padding: EdgeInsets.only(bottom: 12),
                                child: Text('该条目已不在当前目录中，请刷新目录')),
                          if (error != null && widget.plugin)
                            const Padding(
                                padding: EdgeInsets.only(bottom: 12),
                                child: Text('目录刷新失败，显示上次记录')),
                          // task-134：清单拿不到必须说出来（不静默、不猜状态）
                          if (widget.plugin && pluginManifestError != null)
                            Padding(
                                padding: const EdgeInsets.only(bottom: 12),
                                child: DegradedLine(
                                    UiCopy.pluginCatalogUnavailable +
                                        pluginManifestError!,
                                    key: const ValueKey('plugin-manifest-error'))),
                          if (summary(item).isNotEmpty)
                            Padding(
                                padding: const EdgeInsets.only(bottom: 12),
                                child: SelectableText(summary(item))),
                          if (widget.plugin) ...[
                            Text(pluginStatus(item, detail: true)),
                            if (showPluginToggle(item))
                              Row(children: [
                                Expanded(
                                    child: Text(
                                        pluginDisabled(item)
                                            ? '已停用 · 不计入脚本可用'
                                            : '已启用 · 计入脚本可用',
                                        style: const TextStyle(fontSize: 12))),
                                pluginToggle(item, detail: true)
                              ]),
                            const SizedBox(height: 8),
                            SelectableText('版本 ${versionOf(item)}'),
                            if (manifestOf(item) != null) ...[
                              // task-144：详情里也显示真实进度（只要在走就不显示失败）
                              // task-146：只在**真的有进度数字**时显示（未安装且没在下载 ⇒ 什么都不显示 ✗）
                              if (downloading.containsKey(package(item)) &&
                                  downloading[package(item)]!['receivedBytes'] is num &&
                                  downloading[package(item)]!['totalBytes'] is num &&
                                  (downloading[package(item)]!['totalBytes'] as num) > 0)
                                SelectableText(
                                    '下载中 ' +
                                        (() {
                                          final record = downloading[package(item)]!;
                                          final total = record['totalBytes'] as num;
                                          final received = record['receivedBytes'] as num;
                                          final percent = (received * 100 / total)
                                              .clamp(0, 100)
                                              .toStringAsFixed(0);
                                          return percent +
                                              '%（' +
                                              sizeLabel(received) +
                                              ' / ' +
                                              sizeLabel(total) +
                                              '）';
                                        })(),
                                    key: const ValueKey('plugin-detail-progress'),
                                    style: const TextStyle(fontSize: 12)),
                              if (sizeLabel(manifestOf(item)!['sizeBytes']).isNotEmpty)
                                SelectableText(
                                    '大小 ' + sizeLabel(manifestOf(item)!['sizeBytes']),
                                    key: const ValueKey('plugin-detail-size')),
                              SelectableText(
                                  '安装后需重启应用：' +
                                      (manifestOf(item)!['requiresRestart'] == true ? '是' : '否'),
                                  key: const ValueKey('plugin-detail-restart')),
                              if (text(manifestOf(item)!, 'notes').isNotEmpty) ...[
                                Padding(
                                    // task-146：折叠时**必须**有 overflow，且上下留白（上一版没约束 ⇒ 真机与标题重叠 ✗）
                                    padding: const EdgeInsets.only(top: 4, bottom: 2),
                                    // SelectableText 不支持 overflow ✗ ⇒ 用 Text（折叠裁剪可靠，代价是不可选中）
                                    child: Text(
                                        '更新说明：' + text(manifestOf(item)!, 'notes'),
                                        key: const ValueKey('plugin-detail-notes'),
                                        maxLines: expandedNotes.contains(package(item))
                                            ? null
                                            : 3,
                                        overflow: TextOverflow.fade,
                                        style: const TextStyle(fontSize: 12))),
                                if (text(manifestOf(item)!, 'notes').length > 120)
                                  TextButton(
                                      key: const ValueKey('plugin-detail-notes-toggle'),
                                      onPressed: () => setState(() {
                                            if (!expandedNotes.add(package(item))) {
                                              expandedNotes.remove(package(item));
                                            }
                                          }),
                                      child: Text(
                                          expandedNotes.contains(package(item))
                                              ? '收起说明'
                                              : '展开说明')),
                              ],
                              // task-141：清单来源（data.source 有才显示；没有则整行不出现，不猜）
                              if (manifestSourceLabel.isNotEmpty)
                                SelectableText(
                                    '清单来源：' + manifestSourceLabel,
                                    key: const ValueKey('plugin-detail-source'),
                                    style: const TextStyle(fontSize: 12)),
                            ],
                            if (canInstallDownloaded(item))
                              TextButton.icon(
                                  key: const ValueKey('plugin-install-downloaded'),
                                  onPressed: () => installDownloaded(item),
                                  icon: const Icon(Icons.install_desktop_outlined,
                                      size: 18),
                                  label: const Text('安装已下载（交给系统安装器）')),
                            const SizedBox(height: 8),
                            SelectableText(package(item)),
                            if (canDownloadPlugin(item))
                              Row(children: [
                                TextButton.icon(
                                    key: const ValueKey('plugin-download-action'),
                                    onPressed: () => downloadPlugin(item),
                                    icon: const Icon(Icons.download_outlined,
                                        size: 18),
                                    label: Text(downloadButtonLabel(item))),
                                if (installed(item) == true)
                                  const Expanded(
                                      child: Text('停用后仍占空间，卸载请到系统应用管理',
                                          style: TextStyle(fontSize: 12))),
                              ]),
                            const SizedBox(height: 8),
                            if (installed(item) == true)
                              const Text('本机插件随宿主 APK 内置；系统应用详情请在系统设置中查看',
                                  style: TextStyle(fontSize: 12))
                            else if (manifestOf(item) == null)
                              // task-146：清单里有（可下载）⇒ 不再说「没有本地安装包」（自相矛盾 ✗）
                              const Text('该条目没有可用的本地安装包；请用底部「安装本地插件 APK」导入',
                                  style: TextStyle(fontSize: 12)),
                          ] else ...[
                            Text(
                                '${text(item, 'category', '内置示例')} · ${formatSize(size(item))}'),
                            if (author(item).isNotEmpty)
                              Text('作者：${author(item)}'),
                            if (text(item, 'assetPath').isNotEmpty) ...[
                              const SizedBox(height: 8),
                              const Text('内置示例',
                                  style: TextStyle(fontSize: 12)),
                              SelectableText(text(item, 'assetPath')),
                            ],
                          ],
                          if (text(item, 'path').isNotEmpty) ...[
                            const SizedBox(height: 12),
                            const Text('文件位置', style: TextStyle(fontSize: 12)),
                            SelectableText(text(item, 'path')),
                          ],
                          if (importedTime(item).isNotEmpty) ...[
                            const SizedBox(height: 8),
                            Text('导入于 ${importedTime(item)}'),
                          ],
                          if (text(item, 'details').isNotEmpty) ...[
                            const SizedBox(height: 12),
                            SelectableText(text(item, 'details')),
                          ],
                        ]))),
                actions: [
                  if (widget.plugin && canRetryPlugin(item))
                    TextButton.icon(
                        onPressed: current == null ||
                                installing != null ||
                                importing ||
                                refreshing ||
                                error != null
                            ? null
                            : () {
                                Navigator.pop(context);
                                retryPlugin(item);
                              },
                        icon: const Icon(Icons.install_mobile_outlined),
                        label: const Text('重新安装')),
                  if (!widget.plugin && canSave(item))
                    TextButton.icon(
                        onPressed: () {
                          Navigator.pop(context);
                          saveResource(item);
                        },
                        icon: const Icon(Icons.file_download_outlined),
                        label: const Text('导入到脚本目录')),
                  TextButton(
                      onPressed: () => Navigator.pop(context),
                      child: const Text('关闭'))
                ]);
          }));

  /// 插件行。
  ///
  /// 对齐 AI.js Pro `MiuixPluginFragment.kt:448-477` 的**行式列表**（原为网格卡片）：
  ///   行高下限 `78.dp`、内边距 `start18/end18/top8/bottom8`、
  ///   圆标 `50.dp`（内图标 `28.dp`）、名称 `18.sp`、次级（=状态）`14.sp`、
  ///   包名 `12.sp`、分隔线缩进 `82.dp`、
  ///   圆标底色 已装 `#2196F3` / 未装 `#009688`。
  ///

  /// 详情对话框顶部封面 + 卡片式呈现（插件网格与对话框共用 [pluginCard]）。
  Widget pluginCard(Map item, double coverHeight) => GlassSurface(
      key: ValueKey('plugin-${pluginIdentity(item)}'),
      blur: false,
      padding: EdgeInsets.zero,
      radius: 18,
      child: InkWell(
          onTap: () => details(item),
          child:
              Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            cover(item, height: coverHeight),
            Expanded(
                child: Padding(
                    padding: const EdgeInsets.fromLTRB(12, 10, 12, 10),
                    child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(name(item),
                              maxLines: 2,
                              overflow: TextOverflow.ellipsis,
                              style: const TextStyle(
                                  fontSize: 16, fontWeight: FontWeight.w600)),
                          const SizedBox(height: 5),
                          Text('版本 ${versionOf(item)}',
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: const TextStyle(fontSize: 12)),
                          const SizedBox(height: 4),
                          // task-147：卡片按「整体统一」分层——名称/版本/包名/状态行/按钮，逐块单行 + ellipsis
                          // （说明文字只在详情里显示 ⇒ 卡片高度可预测、不会被内容撑破 ✗）
                          Text(package(item),
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: TextStyle(
                                  fontSize: 11,
                                  color: Theme.of(context)
                                      .textTheme
                                      .bodySmall
                                      ?.color)),
                          const Spacer(),
                          // task-142：下载进度（真实桥的 receivedBytes/totalBytes）
                          if (downloading.containsKey(package(item)))
                            Padding(
                                padding: const EdgeInsets.only(bottom: 4),
                                child: LinearProgressIndicator(
                                    minHeight: 3,
                                    value: (() {
                                      final record = downloading[package(item)];
                                      final total = record == null
                                          ? null
                                          : record['totalBytes'];
                                      final received = record == null
                                          ? null
                                          : record['receivedBytes'];
                                      if (total is! num ||
                                          received is! num ||
                                          total <= 0) return null;
                                      return (received / total).clamp(0.0, 1.0);
                                    })())),
                          if (downloadError[package(item)] != null)
                            Text(UiCopy.downloadFailed + downloadError[package(item)]!,
                                key: const ValueKey('plugin-card-error'),
                                maxLines: 2,
                                overflow: TextOverflow.ellipsis,
                                style: TextStyle(
                                    fontSize: 11,
                                    color: Theme.of(context).colorScheme.error)),
                          if (permissionBlocked.contains(package(item)))
                            TextButton(
                                key: const ValueKey('plugin-open-install-settings'),
                                onPressed: () => openInstallPermissionSettings(item),
                                child: Text(
                                    permissionActionLabel[package(item)] ??
                                        '去设置',
                                    style: const TextStyle(fontSize: 12))),
                          if (canDownloadPlugin(item) || canInstallDownloaded(item))
                            Row(children: [
                              if (canDownloadPlugin(item))
                                TextButton.icon(
                                    key: const ValueKey('plugin-card-download'),
                                    onPressed: downloading.containsKey(package(item))
                                        ? null
                                        : () => downloadPlugin(item),
                                    icon: const Icon(Icons.download_outlined,
                                        size: 16),
                                    label: Text(downloadButtonLabel(item),
                                        style: const TextStyle(fontSize: 12))),
                              if (canInstallDownloaded(item))
                                TextButton.icon(
                                    key: const ValueKey('plugin-card-install'),
                                    onPressed: () => installDownloaded(item),
                                    icon: const Icon(
                                        Icons.install_desktop_outlined,
                                        size: 16),
                                    label: const Text('安装',
                                        style: TextStyle(fontSize: 12))),
                            ]),
                          Row(children: [
                            Icon(
                                pluginDisabled(item)
                                    ? Icons.pause_circle_outline
                                    : installed(item) == true
                                        ? Icons.check_circle_outline
                                        : installed(item) == false
                                            ? Icons.install_mobile_outlined
                                            : Icons.help_outline,
                                size: 14,
                                color: pluginDisabled(item)
                                    ? Theme.of(context).disabledColor
                                    : installed(item) == true
                                        ? Theme.of(context).colorScheme.primary
                                        : null),
                            const SizedBox(width: 4),
                            Expanded(
                                child: Text(pluginStatus(item),
                                    maxLines: 1,
                                    overflow: TextOverflow.ellipsis,
                                    style: TextStyle(
                                        fontSize: 11,
                                        color: pluginDisabled(item)
                                            ? Theme.of(context).disabledColor
                                            : installed(item) == true
                                                ? Theme.of(context).colorScheme.primary
                                                : null))),
                            if (canRetryPlugin(item))
                              IconButton(
                                  tooltip: '重新安装：${name(item)}',
                                  padding: const EdgeInsets.all(6),
                                  constraints: const BoxConstraints(
                                      minWidth: 36, minHeight: 36),
                                  icon: installing == pluginIdentity(item)
                                      ? const SizedBox(
                                          width: 18,
                                          height: 18,
                                          child: CircularProgressIndicator(
                                              strokeWidth: 2))
                                      : const Icon(
                                          Icons.install_mobile_outlined,
                                          size: 20),
                                  onPressed: installing != null ||
                                          importing ||
                                          refreshing ||
                                          error != null
                                      ? null
                                      : () => retryPlugin(item)),
                            if (showPluginToggle(item))
                              pluginToggle(item)
                            else if (!canRetryPlugin(item))
                              const Icon(Icons.chevron_right, size: 18),
                          ])
                        ])))
          ])));

  /// 卡片上的启用开关。状态一律来自桥的持久化结果；未确认时不渲染这个控件。
  Widget pluginToggle(Map item, {bool detail = false}) {
    final busy = togglingPlugins.contains(package(item));
    return Tooltip(
        message:
            pluginDisabled(item) ? '启用插件：${name(item)}' : '停用插件：${name(item)}',
        child: SizedBox(
            width: 44,
            height: 32,
            child: FittedBox(
                fit: BoxFit.contain,
                child: Switch(
                    key: ValueKey(
                        '${detail ? 'plugin-detail-enable' : 'plugin-enable'}-${package(item)}'),
                    value: !pluginDisabled(item),
                    materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                    onChanged: busy || pluginStateError != null
                        ? null
                        : (value) => togglePlugin(item, value)))));
  }

  Widget resourceCard(Map item) => GlassSurface(
      blur: false,
      padding: EdgeInsets.zero,
      // 对齐 AI.js Pro MiuixResourceFragment.kt:738 —— Card 圆角用库默认 16（原为 13）
      radius: 16,
      child: InkWell(
          onTap: () => details(item),
          child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 11),
              // V1（task-156）：图标与标题**首行对齐**（原来垂直居中 ⇒ 右侧一片空白 ✗）
              child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                // 对齐 MiuixResourceFragment.kt:742 —— 图标位 38（原为 36）
                SizedBox(
                    width: 38,
                    child: Icon(resourceIcon(item),
                        size: 20, // task-151：与其它图标一致（原 25 偏大且千篇一律 ✗）
                        color: Theme.of(context).colorScheme.primary)),
                const SizedBox(width: 10),
                Expanded(
                    child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                      // 对齐 MiuixResourceFragment.kt:753 —— 标题 18sp（原为 16）
                      Text(name(item),
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(fontSize: 18)),
                      // V2（task-156）：删掉与区块标题重复的副标题（每张卡白撑一行 ✗）⇒ 改显示引擎/语言（没有就不显示）
                      if (resourceEngineLabel(item).isNotEmpty) ...[
                        const SizedBox(height: 3),
                        Text(resourceEngineLabel(item),
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(fontSize: 14)),
                      ],
                      const SizedBox(height: 7),
                      Wrap(spacing: 12, runSpacing: 3, children: [
                        Text(
                            author(item).isEmpty
                                ? item['importRecord'] == true
                                    ? '本地导入'
                                    : '本地资源'
                                : '作者：${author(item)}',
                            style: TextStyle(
                                fontSize: 11,
                                color: author(item).isNotEmpty
                                    ? const Color(0xff247da8)
                                    : null)),
                        // task-157：category 可能是「引擎/语言/子目录」层级路径 ⇒ 只显示末段（回单行 ✓）
                        if (categoryTail(text(item, 'category')).isNotEmpty &&
                            text(item, 'category') != '本地导入')
                          Text(categoryTail(text(item, 'category')),
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: const TextStyle(fontSize: 11)),
                        // V3（task-156）：元信息单行不换行（否则同屏卡片 3 行/4 行不一致 ✗）
                        Text(formatSize(size(item)),
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(fontSize: 11)),
                      ])
                    ])),
                canSave(item)
                    ? ActionIcon(
                        tooltip: '保存到脚本目录：${name(item)}',
                        icon: saving.contains(text(item, 'assetPath'))
                            ? const SizedBox(
                                width: 20,
                                height: 20,
                                child:
                                    CircularProgressIndicator(strokeWidth: 2))
                            : const Icon(Icons.file_download_outlined,
                                size: 23),
                        onPressed: saving.contains(text(item, 'assetPath'))
                            ? null
                            : () => saveResource(item))
                    : ActionIcon(
                        tooltip: '查看资源：${name(item)}',
                        icon: const Icon(Icons.info_outline, size: 22),
                        onPressed: () => details(item))
              ]))));

  Widget empty(String message, IconData icon) => Center(
      child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            Icon(icon, size: 46, color: Theme.of(context).disabledColor),
            const SizedBox(height: 12),
            Text(message, textAlign: TextAlign.center),
          ])));

  Widget failure(String message, {required bool hasData}) => Padding(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
      child: GlassSurface(
          blur: false,
          padding: const EdgeInsets.fromLTRB(12, 4, 4, 4),
          radius: 12,
          child: Row(children: [
            const Icon(Icons.info_outline, size: 20),
            const SizedBox(width: 8),
            Expanded(
                child: Text('${hasData ? '刷新失败，保留上次内容\n' : ''}$message',
                    maxLines: 3,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontSize: 12))),
            TextButton(
                onPressed: mine ? loadImports : load, child: const Text('重试'))
          ])));

  @override
  Widget build(BuildContext context) {
    final source = mine ? imported : items;
    final visible = (source ?? <Map<String, dynamic>>[]).where((item) {
      final haystack =
          '${name(item)} ${summary(item)} ${package(item)} ${text(item, 'category')} ${author(item)}'
              .toLowerCase();
      return (category.isEmpty || mine || item['category'] == category) &&
          haystack.contains(query.trim().toLowerCase());
    }).toList();
    // task-145：插件 tab 的列表 = **清单里的全部插件**（已装 + **未装**）
    // —— 只显示本机已装的会把「未安装/可下载」整个吞掉（用户最早的要求就是这个）✗
    if (widget.plugin && pluginManifest.isNotEmpty) {
      final known = <String>{
        ...visible.map(package),
        ...visible.map((x) => text(x, 'id')),
      }..removeWhere((x) => x.isEmpty);
      final keyword = query.trim().toLowerCase();
      for (final entry in pluginManifest.values) {
        if (entry is! Map) continue;
        final manifest = Map<String, dynamic>.from(entry);
        final id = text(manifest, 'id');
        final pkg = text(manifest, 'package');
        if ((id.isNotEmpty && known.contains(id)) ||
            (pkg.isNotEmpty && known.contains(pkg))) {
          continue; // 本机已有这条 ⇒ 用本机那条（状态更准）
        }
        final title = text(manifest, 'displayName', text(manifest, 'name'));
        if (keyword.isNotEmpty &&
            !(title + ' ' + pkg + ' ' + id).toLowerCase().contains(keyword)) {
          continue;
        }
        visible.add(<String, dynamic>{
          'id': id,
          'name': title.isEmpty ? id : title,
          'package': pkg,
          'packageName': pkg,
          'package_name': pkg,
          'installed': false, // 清单里有、本机没有 ⇒ 未安装
          'version': text(manifest, 'version'),
          'versionName': text(manifest, 'version'),
          'versionCode': manifest['versionCode'],
          'summary': text(manifest, 'notes'),
        });
      }
    }
    final categories = (items ?? <Map<String, dynamic>>[])
        .map((x) => text(x, 'category'))
        .where((x) => x.isNotEmpty)
        .toSet()
        .toList()
      ..sort();
    final currentError = mine ? importError : error;
    // 「脚本可用」只算已安装且未停用的插件；停用的仍留在管理列表里。
    final all = items ?? <Map<String, dynamic>>[];
    final usable = all.where(scriptUsable).length;
    final disabledInList = all.where(pluginDisabled).length;
    return Column(children: [
      // task-136/137：清单提示**独占整行**；失败态 = 原因 + 「多半是源不可达」+ 当前更新源 + 怎么办 + **重试**按钮
      if (widget.plugin && pluginManifestError != null)
        Padding(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 0),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Expanded(
                    child: Text(
                        UiCopy.pluginCatalogUnavailable +
                            pluginManifestError! +
                            '（多半是更新源不可达 —— 换网络或换源后重试）',
                        key: const ValueKey('plugin-manifest-error-header'),
                        maxLines: 3,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                            fontSize: 12,
                            color: Theme.of(context).colorScheme.error))),
                TextButton(
                    key: const ValueKey('plugin-manifest-retry'),
                    onPressed: retryPluginCatalog,
                    child: const Text('重试')),
              ]),
              Text(
                  '当前更新源：' +
                      (updateSourceValue.isEmpty ? '内置源（未设置）' : updateSourceValue) +
                      (updateSourceLoaded ? '' : '（读取中…）'),
                  key: const ValueKey('plugin-manifest-source'),
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontSize: 12)),
              const Text(
                  '要公网下载请在「设置 → 更新源」填公网地址（或留空用内置源）；局域网地址仅在手机与电脑同一 Wi-Fi 时可达。',
                  key: ValueKey('plugin-manifest-source-hint'),
                  style: TextStyle(fontSize: 12)),
            ])),
      if (widget.plugin &&
          pluginManifestError == null &&
          !pluginManifestLoaded)
        const Padding(
            padding: EdgeInsets.fromLTRB(16, 8, 16, 0),
            child: Align(
                alignment: Alignment.centerLeft,
                child: Text('插件目录：加载中…',
                    key: ValueKey('plugin-manifest-loading'),
                    style: TextStyle(fontSize: 12)))),
      ConstrainedBox(
          constraints: const BoxConstraints(minHeight: 48),
          child: Padding(
              padding: const EdgeInsets.only(left: 16),
              child: Row(children: [
                Expanded(
                    child: Text(
                        widget.plugin
                            ? '本机已安装插件'
                            : mine
                                ? '我的导入'
                                : '内置示例目录',
                        maxLines: 2,
                        style: const TextStyle(fontSize: 14))),
                if (source != null)
                  Text(
                      widget.plugin
                          ? '${visible.length} 个插件'
                          : '${visible.length} 项',
                      style: const TextStyle(fontSize: 12)),
                if (widget.plugin)
                  ActionIcon(
                      tooltip: '电脑插件连接管理',
                      onPressed: openComputerPlugin,
                      icon: const Icon(Icons.desktop_windows_outlined)),
                ActionIcon(
                    tooltip: '刷新内置目录',
                    onPressed: load,
                    icon: const Icon(Icons.refresh)),
                ActionIcon(
                    tooltip: '搜索目录',
                    onPressed: toggleSearch,
                    icon: const Icon(Icons.search))
              ]))),
      if (widget.plugin && pluginStateError != null)
        Padding(
            padding: const EdgeInsets.fromLTRB(12, 0, 12, 6),
            child: Text('插件启停状态未确认：$pluginStateError',
                maxLines: 3,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                    fontSize: 12, color: Theme.of(context).colorScheme.error))),
      if (widget.plugin && pluginStatesLoaded && disabledInList > 0)
        Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 6),
            child: Row(children: [
              Icon(Icons.pause_circle_outline,
                  size: 14, color: Theme.of(context).disabledColor),
              const SizedBox(width: 4),
              Expanded(
                  child: Text(
                      '已停用 $disabledInList 个插件 · 脚本可用 $usable/${all.length}',
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(fontSize: 12)))
            ])),
      if (searching)
        Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
            child: TextField(
                controller: searchInput,
                autofocus: true,
                decoration: const InputDecoration(
                    isDense: true, hintText: '搜索名称、说明或包名'),
                onChanged: (value) => setState(() => query = value))),
      if (refreshing)
        const LinearProgressIndicator(minHeight: 2)
      else
        const SizedBox(height: 2),
      if (currentError != null) failure(currentError, hasData: source != null),
      Expanded(
          // task-137：清单失败时**不再留一个永不结束的转圈**（用户会当成卡死）⇒ 失败 = 明确失败态 + 重试
          child: source == null &&
                  currentError == null &&
                  pluginManifestError == null &&
                  !pluginManifestLoaded
              ? const Center(child: CircularProgressIndicator())
              : RefreshIndicator(
                  // 下拉刷新 = 强制绕过清单节流
                  onRefresh: () async {
                    await load();
                    await loadPluginCatalog(force: true);
                  },
                  child: LayoutBuilder(builder: (_, bounds) {
                    // 插件页已改为行式列表（对齐 MiuixPluginFragment），
                    // 不再需要原来的网格列数/封面高度计算。
                    return CustomScrollView(
                        key: PageStorageKey(
                            'catalog-${widget.plugin ? 'plugins' : mine ? 'imports' : 'resources'}'),
                        physics: const AlwaysScrollableScrollPhysics(),
                        slivers: [
                          if (visible.isEmpty)
                            SliverFillRemaining(
                                hasScrollBody: false,
                                child: empty(
                                    currentError != null && source == null
                                        ? '暂时无法读取内置目录，请重试'
                                        : query.isNotEmpty ||
                                                category.isNotEmpty
                                            ? '没有匹配的内置条目'
                                            : widget.plugin
                                                ? '未检测到已安装的 SDK 插件'
                                                : mine
                                                    ? '还没有导入记录'
                                                    : '内置示例目录为空',
                                    widget.plugin
                                        ? Icons.extension_outlined
                                        : Icons.inventory_2_outlined)),
                          if (visible.isNotEmpty && widget.plugin)
                            // task-142：用户拍板改回 **2 列卡片网格**（窄屏 2 列、宽屏 3 列）
                            // 对齐 Auto.js Pro 的卡片样式：圆角 + 居中图标 + 名称 + 副标题。
                            SliverPadding(
                                // task-147：底部要留出「安装本地插件 APK」底栏 + 16（否则最后一行被切 ✗）
                                padding: EdgeInsets.fromLTRB(
                                    12,
                                    4,
                                    12,
                                    72 + 16 + MediaQuery.of(context).padding.bottom),
                                sliver: SliverGrid(
                                    gridDelegate:
                                        const SliverGridDelegateWithMaxCrossAxisExtent(
                                            maxCrossAxisExtent: 190,
                                            mainAxisSpacing: 12,
                                            crossAxisSpacing: 12,
                                            // task-147：固定卡片高度（不再用比例 ⇒ 同一行两张卡片等高、不被内容撑破 ✗）
                                            // task-149：按**最坏组合**（图标+名称2行+版本+包名+状态行+按钮）给足，避免底部被切 ✗
                                            mainAxisExtent: 264),
                                    delegate: SliverChildBuilderDelegate(
                                        (_, index) => pluginCard(
                                            visible[index], 96),
                                        childCount: visible.length)))
                          else if (visible.isNotEmpty)
                            SliverPadding(
                                // task-151：底部留出 FAB + 16（否则最后一行被压住 ✗）
                                padding: const EdgeInsets.fromLTRB(6, 0, 6, 120),
                                sliver: SliverList(
                                    delegate: SliverChildBuilderDelegate(
                                        (_, index) => Padding(
                                            padding: const EdgeInsets.only(
                                                bottom: 6),
                                            child:
                                                resourceCard(visible[index])),
                                        childCount: visible.length))),
                        ]);
                  }))),
      if (widget.plugin)
        SafeArea(
            top: false,
            child: Padding(
                padding: const EdgeInsets.fromLTRB(12, 4, 12, 10),
                child: SizedBox(
                    width: double.infinity,
                    child: OutlinedButton.icon(
                        onPressed:
                            importing || installing != null ? null : importFile,
                        icon: Icon(importing
                            ? Icons.hourglass_empty
                            : Icons.add_box_outlined),
                        label: Text(importing ? '正在导入…' : '安装本地插件 APK')))))
      else
        SafeArea(
            top: false,
            child: GlassSurface(
                blur: false,
                radius: 0,
                padding: const EdgeInsets.symmetric(vertical: 8),
                child: Row(children: [
                  Expanded(
                      child: Semantics(
                          label: '资源分类',
                          button: true,
                          selected: !mine,
                          child: PopupMenuButton<String>(
                              tooltip: '资源分类',
                              initialValue: category,
                              onSelected: (value) => setState(() {
                                    category = value;
                                    mine = false;
                                  }),
                              itemBuilder: (_) => [
                                    const PopupMenuItem(
                                        value: '', child: Text('全部')),
                                    ...categories.map((x) =>
                                        PopupMenuItem(value: x, child: Text(x)))
                                  ],
                              child: Padding(
                                  padding: const EdgeInsets.symmetric(
                                      horizontal: 10, vertical: 12),
                                  child: Row(
                                      mainAxisAlignment:
                                          MainAxisAlignment.center,
                                      children: [
                                        Flexible(
                                            child: Text(
                                                category.isEmpty || mine
                                                    ? '全部'
                                                    : category,
                                                maxLines: 1,
                                                overflow:
                                                    TextOverflow.ellipsis)),
                                        const Icon(Icons.arrow_drop_down)
                                      ]))))),
                  Expanded(
                      child: Center(
                          child: Semantics(
                              label: '导入脚本 / ZIP',
                              button: true,
                              enabled: !importing,
                              onTap: importing ? null : importFile,
                              child: ExcludeSemantics(
                                  child: FloatingActionButton(
                                      heroTag: 'import-resource',
                                      tooltip: '导入脚本 / ZIP',
                                      backgroundColor: AppTheme.actionBackground(
                                          context, enabled: !importing),
                                      foregroundColor: AppTheme.actionForeground(
                                          context, enabled: !importing),
                                      onPressed: importing ? null : importFile,
                                      child: Icon(importing
                                          ? Icons.hourglass_empty
                                          : Icons.file_upload)))))),
                  Expanded(
                      child: Semantics(
                          selected: mine,
                          child: TextButton.icon(
                              onPressed: () {
                                setState(() {
                                  mine = !mine;
                                  query = '';
                                  searchInput.clear();
                                });
                                if (mine) loadImports();
                              },
                              icon: Icon(
                                  mine ? Icons.person : Icons.person_outline),
                              label: const Text('我的'))))
                ])))
    ]);
  }
}
