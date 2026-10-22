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
  String pluginStatus(Map item, {bool detail = false}) {
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
    return Container(
        height: height,
        width: double.infinity,
        decoration: BoxDecoration(
            gradient: LinearGradient(
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
                colors: [tint.withOpacity(.68), tint])),
        child: Stack(children: [
          Positioned(
              right: -28,
              top: -24,
              child: Icon(symbol,
                  size: 148, color: Colors.white.withOpacity(.10))),
          Positioned(
              left: -12,
              bottom: -20,
              child: Container(
                  width: 84,
                  height: 84,
                  decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      border: Border.all(
                          width: 2, color: Colors.white.withOpacity(.18))))),
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
                              color: Colors.white.withOpacity(.91)),
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
                            const SizedBox(height: 8),
                            SelectableText(package(item)),
                            const SizedBox(height: 8),
                            Text(
                                installed(item) == true
                                    ? '本机插件随宿主 APK 内置；系统应用详情请在系统设置中查看'
                                    : '该条目没有可用的本地安装包；请用底部「安装本地插件 APK」导入',
                                style: const TextStyle(fontSize: 12)),
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
  /// ⚠️ 状态文案仍由 [pluginStatus] 产出且**保持为独立 Text**（`已安装` / `已停用` 等），
  /// 不合并进「版本 xxx」那一行 —— `plugin_manager_test` 用 `find.text('已安装')`
  /// 精确匹配，且详情对话框与行共用同一份状态。
  /// 详情对话框仍走 [pluginCard]，本方法只用于列表。
  Widget pluginRow(Map item) {
    final isInstalled = installed(item) == true;
    final identity = pluginIdentity(item);
    // key 挂在整行根上：既有实现把它放在卡片根，测试用
    // `ValueKey('plugin-<identity>')` 定位（含 catalog_test / catalog_sync_test）。
    return Column(key: ValueKey('plugin-$identity'), children: [
      InkWell(
          onTap: () => details(item),
          child: ConstrainedBox(
              constraints: const BoxConstraints(minHeight: 78),
              child: Padding(
                  padding: const EdgeInsets.fromLTRB(18, 8, 18, 8),
                  child: Row(children: [
                    // 圆标：已装蓝 #2196F3 / 未装青 #009688（MiuixPluginFragment.kt:453）。
                    // 已装时里面放插件自己的应用图标（AI.js Pro 的做法），
                    // 取不到才回落成状态图标 —— 这也保留了原先「只对已装插件请求
                    // pluginIcon」的行为（catalog_test 断言了这次调用）。
                    Container(
                        width: 50,
                        height: 50,
                        decoration: BoxDecoration(
                            color: isInstalled
                                ? const Color(0xff2196f3)
                                : const Color(0xff009688),
                            shape: BoxShape.circle),
                        child: ClipOval(
                            child: FutureBuilder<String?>(
                                future: isInstalled
                                    ? pluginIcon(package(item))
                                    : Future<String?>.value(null),
                                builder: (_, snapshot) {
                                  if (snapshot.data != null) {
                                    try {
                                      return Image.memory(
                                          base64Decode(snapshot.data!),
                                          width: 28,
                                          height: 28,
                                          fit: BoxFit.contain,
                                          errorBuilder: (_, __, ___) =>
                                              pluginRowSymbol(item));
                                    } catch (_) {
                                      // 落到下面的状态图标
                                    }
                                  }
                                  return Center(child: pluginRowSymbol(item));
                                }))),
                    const SizedBox(width: 14),
                    Expanded(
                        child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                          // 名称 18.sp（MiuixPluginFragment.kt:463）
                          Text(name(item),
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: const TextStyle(fontSize: 18)),
                          // 状态 14.sp（.kt:464）—— 保持独立 Text，测试按精确文案匹配
                          Text(pluginStatus(item),
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: TextStyle(
                                  fontSize: 14,
                                  color: pluginDisabled(item)
                                      ? Theme.of(context).disabledColor
                                      : isInstalled
                                          ? Theme.of(context).colorScheme.primary
                                          : Theme.of(context)
                                              .colorScheme
                                              .onSurface
                                              .withOpacity(.65))),
                          // 包名 12.sp（.kt:467）
                          Text(package(item),
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: TextStyle(
                                  fontSize: 12,
                                  color: Theme.of(context)
                                      .textTheme
                                      .bodySmall
                                      ?.color)),
                        ])),
                    if (canRetryPlugin(item))
                      IconButton(
                          tooltip: '重新安装：${name(item)}',
                          padding: const EdgeInsets.all(6),
                          constraints:
                              const BoxConstraints(minWidth: 36, minHeight: 36),
                          icon: installing == identity
                              ? const SizedBox(
                                  width: 18,
                                  height: 18,
                                  child:
                                      CircularProgressIndicator(strokeWidth: 2))
                              : const Icon(Icons.install_mobile_outlined,
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
                      const Icon(Icons.chevron_right, size: 24),
                  ])))),
      // 分隔线缩进 82.dp（MiuixPluginFragment.kt:476）
      const Divider(height: 1, thickness: .5, indent: 82)
    ]);
  }

  /// 插件行圆标里的状态图标（取不到插件自身图标时用）。
  /// 未装=下载图标、已停用=暂停、已装=勾（对齐 MiuixPluginFragment.kt:458 的 28dp）。
  Widget pluginRowSymbol(Map item) => Icon(
      pluginDisabled(item)
          ? Icons.pause_circle_outline
          : installed(item) == true
              ? Icons.check_circle_outline
              : Icons.install_mobile_outlined,
      color: Colors.white,
      size: 28);

  /// 详情对话框顶部封面 + 卡片式呈现（列表已改为 [pluginRow]，此处只服务对话框）。
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
                          if (summary(item).isNotEmpty) ...[
                            Text(summary(item),
                                maxLines: 2,
                                overflow: TextOverflow.ellipsis,
                                style: const TextStyle(fontSize: 12)),
                            const SizedBox(height: 4),
                          ],
                          Text(package(item),
                              maxLines: 2,
                              overflow: TextOverflow.ellipsis,
                              style: TextStyle(
                                  fontSize: 11,
                                  color: Theme.of(context)
                                      .textTheme
                                      .bodySmall
                                      ?.color)),
                          const Spacer(),
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
                                    maxLines: 2,
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
              child: Row(children: [
                // 对齐 MiuixResourceFragment.kt:742 —— 图标位 38（原为 36）
                SizedBox(
                    width: 38,
                    child:
                        Icon(Icons.code, size: 25,
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
                      if (summary(item).isNotEmpty) ...[
                        const SizedBox(height: 3),
                        // 对齐 MiuixResourceFragment.kt:755 —— 次级 14sp（原为 12）
                        Text(summary(item),
                            maxLines: 2,
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
                        if (text(item, 'category').isNotEmpty &&
                            text(item, 'category') != '本地导入')
                          Text(text(item, 'category'),
                              style: const TextStyle(fontSize: 11)),
                        Text(formatSize(size(item)),
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
          child: source == null && currentError == null
              ? const Center(child: CircularProgressIndicator())
              : RefreshIndicator(
                  onRefresh: load,
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
                            // 对齐 AI.js Pro MiuixPluginFragment.kt:163 —— 行式列表，
                            // contentPadding bottom 18；原为 SliverGrid + pluginCard。
                            SliverPadding(
                                padding: const EdgeInsets.only(bottom: 18),
                                sliver: SliverList(
                                    delegate: SliverChildBuilderDelegate(
                                        (_, index) => pluginRow(visible[index]),
                                        childCount: visible.length)))
                          else if (visible.isNotEmpty)
                            SliverPadding(
                                padding: const EdgeInsets.fromLTRB(6, 0, 6, 8),
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
