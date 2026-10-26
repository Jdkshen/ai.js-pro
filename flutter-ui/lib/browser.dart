part of 'main.dart';

class FileBrowser extends StatefulWidget {
  final String root, title;
  final bool readOnly, active;
  final int revision;
  const FileBrowser(
      {Key? key,
      required this.root,
      required this.title,
      this.readOnly = false,
      this.active = true,
      required this.revision})
      : super(key: key);
  @override
  State<FileBrowser> createState() => _FileBrowserState();
}

class _FileBrowserState extends State<FileBrowser> with WidgetsBindingObserver {
  late String path;
  List<Map<String, dynamic>>? files;
  String? error;
  String search = '';
  bool searching = false;
  bool createExpanded = false, busy = false;
  bool loading = false;
  Timer? searchTimer;
  Timer? refreshTimer;
  bool foreground = true, automaticPending = false;
  bool optionsReady = false, reloadWhenActive = true;
  int refreshTicks = 0;
  int request = 0, optionsRevision = 0;
  BrowserOptions options = const BrowserOptions();
  final scroll = ScrollController(keepScrollOffset: false);
  final breadcrumbScroll = ScrollController();
  final directoryOffsets = <String, double>{};
  final running = <String>{};
  bool restoreOffset = false;
  // 示例库按需下载（task-122）：大示例不在 APK 里，未下载时在目录里显示「下载」行。
  List<Map<String, dynamic>> sampleUnits = const [];
  bool sampleLibraryLoaded = false;
  List<Map<String, dynamic>>? sortedSource;
  BrowserOptions? sortedOptions;
  List<Map<String, dynamic>> sortedFiles = const [];
  final downloading = <String, Map<String, dynamic>>{};
  // 批量选择 / 文件剪贴板（复制、剪切、粘贴）
  bool selecting = false;
  final selected = <String>{};
  String clipboardMode = '';
  final clipboardPaths = <String>[];
  String get profile => widget.readOnly ? 'samples' : 'scripts';

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    foreground = WidgetsBinding.instance.lifecycleState == null ||
        WidgetsBinding.instance.lifecycleState == AppLifecycleState.resumed;
    path = widget.root;
    restoreOptions();
    loadClipboard();
    refreshTimer = Timer.periodic(const Duration(seconds: 2), (_) {
      // Recursive searches have a larger bounded scan; poll them less often.
      if (options.recursive &&
          search.trim().isNotEmpty &&
          ++refreshTicks % 3 != 0) return;
      refreshAutomatically();
    });
  }

  @override
  void dispose() {
    searchTimer?.cancel();
    refreshTimer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    request++;
    scroll.dispose();
    breadcrumbScroll.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    foreground = state == AppLifecycleState.resumed;
  }

  Future<void> refreshAutomatically() async {
    if (!mounted ||
        !widget.active ||
        !optionsReady ||
        !foreground ||
        automaticPending ||
        loading ||
        busy ||
        ModalRoute.of(context)?.isCurrent == false ||
        (scroll.hasClients && scroll.position.isScrollingNotifier.value))
      return;
    automaticPending = true;
    try {
      await load(automatic: true);
    } finally {
      automaticPending = false;
    }
  }

  @override
  void didUpdateWidget(FileBrowser old) {
    super.didUpdateWidget(old);
    if (old.root != widget.root || old.readOnly != widget.readOnly) {
      searchTimer?.cancel();
      path = widget.root;
      search = '';
      searching = createExpanded = restoreOffset = false;
      files = null;
      sampleUnits = const [];
      sampleLibraryLoaded = false;
      error = null;
      directoryOffsets.clear();
      if (old.readOnly != widget.readOnly) {
        options = const BrowserOptions();
        optionsRevision = 0;
        optionsReady = false;
        restoreOptions();
      }
      reloadWhenActive = true;
    } else if (old.revision != widget.revision) {
      reloadWhenActive = true;
    }
    if (widget.active && foreground && optionsReady &&
        (reloadWhenActive || !old.active)) {
      load(automatic: files != null);
    }
  }

  Future<void> restoreOptions() async {
    final requestedProfile = profile;
    try {
      final value =
          await LocalBridge.call('browserPreferences', {'profile': profile});
      if (mounted &&
          requestedProfile == profile &&
          optionsRevision == 0 &&
          value is Map) {
        setState(() => options = BrowserOptions.fromJson(value));
      }
    } catch (_) {
      /* Keep usable defaults if an older bridge has no preferences. */
    } finally {
      if (mounted && requestedProfile == profile) {
        optionsReady = true;
        if (widget.active && foreground) load();
      }
    }
  }

  Future<void> setOptions(BrowserOptions value) async {
    setState(() {
      options = value;
      optionsRevision++;
    });
    if (scroll.hasClients) scroll.jumpTo(0);
    final refresh = load();
    try {
      await LocalBridge.call(
          'browserPreferences', {'profile': profile, 'value': value.toJson()});
    } catch (e) {
      if (mounted) message(context, '目录设置未保存：$e');
    }
    await refresh;
  }

  Future<void> load({bool automatic = false}) async {
    reloadWhenActive = false;
    searchTimer?.cancel();
    final token = ++request;
    final requestedPath = path;
    final query = search.trim();
    if (!automatic)
      setState(() {
        loading = true;
        error = null;
      });
    try {
      if (options.regex && query.isNotEmpty) {
        try {
          RegExp(query);
        } on FormatException {
          throw StateError('正则表达式无效，请修改搜索内容');
        }
      }
      // 示例页先取「示例库」清单（哪些示例包没下载）；旧桥没有这个方法时保持空，
      // 不影响目录列表本身。自动刷新时沿用已缓存的清单，避免每秒都打一次桥。
      List<dynamic>? library;
      if (widget.readOnly && (!automatic || !sampleLibraryLoaded)) {
        try {
          final value = await LocalBridge.call('sampleLibrary');
          if (value is List) {
            library = value;
            sampleLibraryLoaded = true;
          }
        } catch (_) {
          /* 旧桥没有示例库清单时保持空。 */
        }
        if (!mounted || token != request) return;
      }
      final value = await LocalBridge.call('list', {
        'path': requestedPath,
        'owner': profile,
        'showHidden': options.showHidden,
        'recursive': options.recursive && query.isNotEmpty,
        'query': query,
        'regex': options.regex
      });
      if (!mounted || token != request) return;
      final next = List<Map<String, dynamic>>.from(
          (value as List).map((x) => Map<String, dynamic>.from(x)));
      if (widget.readOnly) applySampleUnits(next, library);
      if (!automatic ||
          error != null ||
          jsonEncode(files) != jsonEncode(next)) {
        setState(() {
          files = next;
          error = null;
        });
      }
      if (!automatic) loadClipboard();
      if (restoreOffset) {
        restoreOffset = false;
        final wanted = directoryOffsets[requestedPath] ?? 0;
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (!mounted || token != request || !scroll.hasClients) return;
          scroll.jumpTo(
              wanted.clamp(0.0, scroll.position.maxScrollExtent).toDouble());
        });
      }
    } catch (e) {
      if (!automatic && mounted && token == request) {
        setState(() =>
            error = e is StateError ? e.message.toString() : e.toString());
      }
    } finally {
      if (!automatic && mounted && token == request)
        setState(() => loading = false);
    }
  }

  void cancelLoad() {
    searchTimer?.cancel();
    request++;
    setState(() {
      loading = false;
      if (files == null) error = '已停止等待，点击刷新可重新读取目录';
    });
  }

  void changeSearch(String value) {
    searchTimer?.cancel();
    request++;
    setState(() {
      search = value;
      loading = true;
      error = null;
    });
    searchTimer = Timer(const Duration(milliseconds: 250), () {
      if (mounted) load();
    });
  }

  void navigate(String destination) {
    if (destination == path) return;
    searchTimer?.cancel();
    if (scroll.hasClients) directoryOffsets[path] = scroll.position.pixels;
    FocusScope.of(context).unfocus();
    setState(() {
      path = destination;
      search = '';
      searching = false;
      files = null;
      error = null;
      createExpanded = false;
      restoreOffset = true;
    });
    load();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted && breadcrumbScroll.hasClients) {
        breadcrumbScroll.jumpTo(breadcrumbScroll.position.maxScrollExtent);
      }
    });
  }

  void parent() {
    if (path != widget.root) navigate(File(path).parent.path);
  }

  bool dismissTransient() {
    if (createExpanded) {
      setState(() => createExpanded = false);
      return true;
    }
    if (searching) {
      FocusScope.of(context).unfocus();
      setState(() {
        searching = false;
        search = '';
      });
      load();
      return true;
    }
    return false;
  }

  Future<void> open(Map<String, dynamic> file) async {
    if (file['pendingSample'] == true) {
      // 未下载的示例包：点一下就是下载（完成后自动刷新成普通示例目录）。
      await downloadSample(file);
      return;
    }
    if (file['directory'] == true) {
      navigate(file['path']);
      return;
    }
    if (!editable(file)) {
      await mutation(() =>
          LocalBridge.call('openWith', {'path': file['path']}).then((_) {}));
      return;
    }
    // The Flutter editor is replaced by the packaged Ace editor, which is a normal
    // Activity: it owns its own window, keyboard and back handling, so nothing has to be
    // mirrored from Flutter's layout. Verified on device: an ACTION_VIEW file uri loads
    // both the file name and its contents.
    await openNativeEditor(file);
    if (mounted) load();
  }

  /// Opens the file in the packaged Ace editor (the editor used by every "open" path).
  ///
  /// The stock Activity takes an ACTION_VIEW file uri and loads both the name and the
  /// contents. It is not a platform view, so it needs no bounds mirroring from Flutter.
  Future<void> openNativeEditor(Map<String, dynamic> file) async {
    final full = file['path'] as String;
    // 原生 Ace 编辑器是宿主 Activity，不在 Flutter 路由栈里，连点文件/工具按钮会把
    // Activity 叠多层。用 [NativeEditorGate] 保证同一时刻只拉起一个：回到 Flutter
    // （生命周期 resumed）或桥调用失败时放行。
    if (!NativeEditorGate.tryEnter()) return;
    var launched = false;
    try {
      // 示例文件走原生「示例」入口：编辑器带 sample_asset_path，工具栏会出现「重置」，
      // 可以直接从内置 assets 恢复（与 MiuixSampleFragment.view 一致）。
      final reply = widget.readOnly
          ? await LocalBridge.call(
              'sampleAction', {'action': 'view', 'path': full})
          : await LocalBridge.call(
              'aceOpenFile', {'path': full, 'readOnly': widget.readOnly});
      launched = true;
      if (!mounted) return;
      final exists = reply is Map ? reply['exists'] == true : true;
      if (!exists) message(context, '文件不存在：$full');
    } catch (e) {
      NativeEditorGate.release();
      if (mounted) message(context, e.toString());
    } finally {
      // 没真正拉起编辑器（异常/未返回成功）时立即放行，避免门闩卡死；正常拉起后等
      // Home.didChangeAppLifecycleState(resumed) 放行。
      if (!launched) NativeEditorGate.release();
    }
  }

  Future<void> run(Map<String, dynamic> file) async {
    final full = file['path'] as String;
    if (running.contains(full)) return;
    setState(() => running.add(full));
    try {
      await LocalBridge.call(
          file['project'] == true ? 'runProject' : 'run', {'path': full});
    } catch (e) {
      if (mounted) message(context, e.toString());
    } finally {
      if (mounted) setState(() => running.remove(full));
    }
  }

  /// 示例库按需下载（task-122）：大示例不在 APK 里，点下载后落到 files/sample。
  Future<void> downloadSample(Map<String, dynamic> file) async {
    final id = file['sampleId']?.toString() ?? '';
    if (id.isEmpty || downloading.containsKey(id)) return;
    setState(() => downloading[id] = {
          'received': 0,
          'total': file['size'] ?? 0,
          'phase': 'downloading'
        });
    final timer = Timer.periodic(
        const Duration(milliseconds: 400), (_) => pollDownload(id));
    try {
      await LocalBridge.call('sampleDownload', {'id': id});
      if (mounted) message(context, '示例已下载，可直接打开运行');
    } catch (e) {
      if (mounted) message(context, '示例下载失败：$e');
    } finally {
      timer.cancel();
      if (mounted) {
        setState(() => downloading.remove(id));
        await load();
      }
    }
  }

  Future<void> pollDownload(String id) async {
    try {
      final value = await LocalBridge.call('sampleLibrary');
      if (!mounted || value is! List) return;
      for (final raw in value) {
        if (raw is! Map || raw['id']?.toString() != id) continue;
        if (raw['downloading'] != true) return;
        setState(() => downloading[id] = Map<String, dynamic>.from(raw));
        return;
      }
    } catch (_) {
      /* 下载中查询进度失败不影响下载本身。 */
    }
  }

  Map<String, dynamic>? downloadProgressFor(Map<String, dynamic> file) {
    final id = file['sampleId']?.toString() ?? '';
    return id.isEmpty ? null : downloading[id];
  }

  double? downloadFraction(Map<String, dynamic> file) {
    final progress = downloadProgressFor(file);
    if (progress == null) return null;
    final total = (progress['total'] as num?)?.toDouble() ?? 0;
    final received = (progress['received'] as num?)?.toDouble() ?? 0;
    if (total <= 0) return null;
    return (received / total).clamp(0.0, 1.0).toDouble();
  }

  String pendingSampleText(Map<String, dynamic> file) {
    final progress = downloadProgressFor(file);
    final total = (file['size'] as num?)?.toDouble() ?? 0;
    if (progress != null) {
      final phase = progress['phase']?.toString() ?? '';
      if (phase == 'extracting') return '示例包 · 正在解压…';
      final received = (progress['received'] as num?)?.toDouble() ?? 0;
      final bytes = (progress['total'] as num?)?.toDouble() ?? total;
      final percent = bytes > 0 ? (received / bytes * 100).clamp(0, 100) : 0;
      final text = '示例包 · 下载中 ${percent.toStringAsFixed(0)}%';
      return bytes > 0
          ? '$text（${formatSize(received)} / ${formatSize(bytes)}）'
          : text;
    }
    final description = file['sampleDescription']?.toString() ?? '';
    final size = total > 0 ? '（${formatSize(total)}）' : '';
    return description.isEmpty
        ? '示例包 · 未下载$size'
        : '示例包 · 未下载$size  $description';
  }

  Widget pendingSampleAction(Map<String, dynamic> file) {
    final progress = downloadProgressFor(file);
    if (progress != null) {
      final fraction = downloadFraction(file);
      return SizedBox(
          width: 56,
          height: 48,
          child: Center(
              child: fraction == null
                  ? const SizedBox(
                      width: 20,
                      height: 20,
                      child: CircularProgressIndicator(strokeWidth: 2))
                  : Text('${(fraction * 100).toStringAsFixed(0)}%',
                      style: const TextStyle(fontSize: 12))));
    }
    return ActionIcon(
        tooltip: '下载示例：${file['name']}',
        height: 48,
        icon: const Icon(Icons.file_download_outlined, size: 26),
        onPressed: () => downloadSample(file));
  }

  /// 是否是「按需下载」示例包里的文件（这类示例没有内置版本，不显示重置入口）。
  bool isDownloadedSamplePath(String full) {
    if (full.isEmpty) return false;
    for (final unit in sampleUnits) {
      if (unit['downloaded'] != true) continue;
      final target = unit['path']?.toString() ?? '';
      if (target.isNotEmpty && (full == target || full.startsWith('$target/'))) {
        return true;
      }
    }
    return false;
  }

  /// 把「未下载的示例包」作为虚拟行合进当前目录。
  void applySampleUnits(List<Map<String, dynamic>> items, List<dynamic>? library) {
    if (library != null) {
      final units = <Map<String, dynamic>>[];
      for (final raw in library) {
        if (raw is! Map) continue;
        final unit = Map<String, dynamic>.from(raw);
        if ((unit['id']?.toString() ?? '').isNotEmpty &&
            (unit['path']?.toString() ?? '').isNotEmpty) {
          units.add(unit);
        }
      }
      sampleUnits = units;
    }
    if (search.trim().isNotEmpty) return;
    final names = items.map((item) => item['name']?.toString() ?? '').toSet();
    for (final unit in sampleUnits) {
      if (unit['downloaded'] == true) continue;
      final target = unit['path']?.toString() ?? '';
      if (target.isEmpty) continue;
      if (File(target).parent.path != path) continue;
      final name = unit['name']?.toString() ?? '';
      if (name.isEmpty || names.contains(name)) continue;
      names.add(name);
      items.add({
        'name': name,
        'path': target,
        'relativePath': name,
        'directory': true,
        'project': false,
        'size': unit['bytes'] ?? 0,
        'modified': 0,
        'pendingSample': true,
        'sampleId': unit['id']?.toString() ?? '',
        'sampleTitle': unit['title']?.toString() ?? name,
        'sampleDescription': unit['description']?.toString() ?? '',
      });
    }
    items.sort((a, b) {
      final aDirectory = a['directory'] == true;
      final bDirectory = b['directory'] == true;
      if (aDirectory != bDirectory) return aDirectory ? -1 : 1;
      return (a['name']?.toString() ?? '')
          .toLowerCase()
          .compareTo((b['name']?.toString() ?? '').toLowerCase());
    });
  }

  /// Shows the floating controller for this script; the
  /// script starts only when the bar's ▶ is pressed.
  Future<void> floatRun(Map<String, dynamic> file) async {
    final full = file['path'] as String;
    if (running.contains(full)) return;
    setState(() => running.add(full));
    try {
      final reply = await LocalBridge.call('floatRun', {'path': full});
      if (mounted && reply is Map && reply['needsPermission'] == true) {
        message(context, '悬浮窗权限未开启，已打开系统授权页');
      }
    } catch (e) {
      if (mounted) message(context, e.toString());
    } finally {
      if (mounted) setState(() => running.remove(full));
    }
  }

  Future<void> mutation(Future<void> Function() action) async {
    if (busy) return;
    setState(() {
      busy = true;
      createExpanded = false;
    });
    try {
      await action();
    } catch (e) {
      if (mounted)
        message(context, e is StateError ? e.message.toString() : e.toString());
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<void> loadClipboard() async {
    try {
      final value = await LocalBridge.call('fileClipboard');
      if (!mounted || value is! Map) return;
      final paths = ((value['paths'] as List?) ?? const [])
          .map((item) => '$item')
          .where((item) => item.isNotEmpty)
          .toList();
      setState(() {
        clipboardMode = value['mode']?.toString() ?? '';
        clipboardPaths
          ..clear()
          ..addAll(paths);
      });
    } catch (_) {
      /* 旧桥还没有剪贴板时保持空剪贴板。 */
    }
  }

  void startSelection(Map<String, dynamic> file) {
    final full = file['path']?.toString() ?? '';
    if (full.isEmpty) return;
    setState(() {
      selecting = true;
      selected
        ..clear()
        ..add(full);
    });
  }

  void toggleSelection(Map<String, dynamic> file) {
    final full = file['path']?.toString() ?? '';
    if (full.isEmpty) return;
    setState(() {
      selecting = true;
      if (!selected.add(full)) selected.remove(full);
      if (selected.isEmpty) selecting = false;
    });
  }

  void selectAllVisible() {
    setState(() {
      selecting = true;
      selected
        ..clear()
        ..addAll((files ?? [])
            .map((file) => file['path']?.toString() ?? '')
            .where((full) => full.isNotEmpty));
    });
  }

  void clearSelection() {
    setState(() {
      selecting = false;
      selected.clear();
    });
  }

  Future<void> copySelection({required bool move}) async {
    final paths = selected.toList();
    if (paths.isEmpty) return;
    await mutation(() async {
      final value = await LocalBridge.call(
          'fileClipboard', {'mode': move ? 'move' : 'copy', 'paths': paths});
      if (!mounted) return;
      final count = value is Map && value['count'] is int
          ? value['count'] as int
          : paths.length;
      setState(() {
        clipboardMode = move ? 'move' : 'copy';
        clipboardPaths
          ..clear()
          ..addAll(paths);
        selecting = false;
        selected.clear();
      });
      message(context, '${move ? '已剪切' : '已复制'} $count 项，进入目标文件夹后点“粘贴”');
    });
  }

  Future<void> copyOne(Map<String, dynamic> file, {required bool move}) async {
    final full = file['path']?.toString() ?? '';
    if (full.isEmpty) return;
    await mutation(() async {
      await LocalBridge.call('fileClipboard', {
        'mode': move ? 'move' : 'copy',
        'paths': [full]
      });
      if (!mounted) return;
      setState(() {
        clipboardMode = move ? 'move' : 'copy';
        clipboardPaths
          ..clear()
          ..add(full);
      });
      message(context, '${move ? '已剪切' : '已复制'} 1 项，进入目标文件夹后点“粘贴”');
    });
  }

  Future<void> pasteInto(String target) async {
    if (widget.readOnly) return;
    await mutation(() async {
      final value = await LocalBridge.call('pastePaths', {'path': target});
      if (!mounted) return;
      final pasted = value is Map && value['pasted'] is List
          ? (value['pasted'] as List).length
          : 0;
      final errors = value is Map && value['errors'] is List
          ? (value['errors'] as List)
          : const [];
      if (value is Map && value['cleared'] == true) {
        setState(() {
          clipboardMode = '';
          clipboardPaths.clear();
        });
      }
      await load();
      if (!mounted) return;
      message(
          context,
          errors.isEmpty
              ? '已粘贴 $pasted 项到当前目录'
              : '已粘贴 $pasted 项，${errors.length} 项失败：${errors.first}');
    });
  }

  Future<void> deleteSelected() async {
    final paths = selected.toList();
    if (paths.isEmpty) return;
    var decided = false;
    final confirmed = await showDialog<bool>(
        context: context,
        builder: (dialog) => AlertDialog(
                title: Text('移入回收站 ${paths.length} 项？'),
                content: const Text('这些文件和文件夹会移入本地回收站，可在设置中恢复。'),
                actions: [
                  TextButton(
                      onPressed: () {
                        if (decided) return;
                        decided = true;
                        Navigator.pop(dialog, false);
                      },
                      child: const Text('取消')),
                  TextButton(
                      onPressed: () {
                        if (decided) return;
                        decided = true;
                        Navigator.pop(dialog, true);
                      },
                      child: const Text('移入回收站'))
                ]));
    if (!mounted || confirmed != true) return;
    await mutation(() async {
      final value = await LocalBridge.call('deletePaths', {'paths': paths});
      if (!mounted) return;
      final deleted = value is Map && value['deleted'] is List
          ? (value['deleted'] as List).length
          : 0;
      final errors = value is Map && value['errors'] is List
          ? (value['errors'] as List)
          : const [];
      setState(() {
        selecting = false;
        selected.clear();
      });
      await load();
      if (!mounted) return;
      message(
          context,
          errors.isEmpty
              ? '已将 $deleted 项移入回收站'
              : '已移入 $deleted 项，${errors.length} 项失败：${errors.first}');
    });
  }

  Future<_WorkspaceName?> nameDialog(String title,
          {String initial = '',
          String? original,
          bool script = false,
          bool chooseEngine = false,
          String initialEngine = 'rhino',
          bool engineLocked = false,
          String confirm = '创建'}) =>
      showDialog<_WorkspaceName>(
          context: context,
          builder: (_) => _WorkspaceNameDialog(
              title: title,
              initialName: initial,
              original: original,
              script: script,
              chooseEngine: chooseEngine,
              initialEngine: initialEngine,
              engineLocked: engineLocked,
              existing:
                  (files ?? []).map((file) => file['name'].toString()).toList(),
              confirm: confirm));

  Future<void> create(String kind) => mutation(() async {
        if (kind == 'import') {
          final value =
              await LocalBridge.call('importWorkspace', {'path': path});
          if (!mounted) return;
          if (value is Map && value['cancelled'] == true) return;
          await load();
          if (mounted) message(context, '文件已导入当前目录');
          return;
        }
        var engine = 'rhino';
        var engineLocked = false;
        if (kind == 'file') {
          final value =
              await LocalBridge.call('workspaceContext', {'path': path});
          if (value is! Map ||
              !['node', 'rhino'].contains(value['engine']) ||
              value['locked'] is! bool) {
            throw StateError('无法确认当前项目的脚本引擎');
          }
          engine = value['engine'] as String;
          engineLocked = value['locked'] as bool;
          if (!mounted) return;
        }
        final title = kind == 'folder'
            ? '新建文件夹'
            : kind == 'project'
                ? '新建项目'
                : '新建文件';
        final value = await nameDialog(title,
            initial: kind == 'folder'
                ? '新文件夹'
                : kind == 'project'
                    ? '新项目'
                    : engine == 'node'
                        ? '新脚本.node.js'
                        : '新脚本.js',
            script: kind == 'file',
            initialEngine: engine,
            engineLocked: engineLocked,
            chooseEngine: kind != 'folder' && !engineLocked);
        if (!mounted || value == null) return;
        final result = await LocalBridge.call(
            kind == 'folder'
                ? 'createFolder'
                : kind == 'project'
                    ? 'createProject'
                    : 'createFile',
            {
              'path': path,
              'name': value.name,
              if (kind != 'folder') 'engine': value.engine
            });
        if (!mounted) return;
        await load();
        if (!mounted) return;
        if (kind == 'folder') {
          message(context, '文件夹已创建');
          return;
        }
        final full =
            result is Map ? result['path'] as String : '$path/${value.name}';
        await openNativeEditor({'path': full});
        if (mounted) await load();
      });

  Future<void> rename(Map<String, dynamic> file) => mutation(() async {
        final value = await nameDialog('重命名',
            initial: file['name'], original: file['name'], confirm: '保存名称');
        if (!mounted || value == null || value.name == file['name']) return;
        await LocalBridge.call(
            'rename', {'path': file['path'], 'name': value.name});
        if (mounted) {
          await load();
          if (mounted) message(context, '名称已更新');
        }
      });

  Future<void> delete(Map<String, dynamic> file) => mutation(() async {
        var decided = false;
        final confirmed = await showDialog<bool>(
            context: context,
            builder: (dialog) => AlertDialog(
                    title: Text('删除“${file['name']}”？'),
                    content: Text(file['directory'] == true
                        ? '将此文件夹及其内容移入本地回收站，可在设置中恢复。'
                        : '将此文件移入本地回收站，可在设置中恢复。'),
                    actions: [
                      TextButton(
                          onPressed: () {
                            if (decided) return;
                            decided = true;
                            Navigator.pop(dialog, false);
                          },
                          child: const Text('取消')),
                      TextButton(
                          onPressed: () {
                            if (decided) return;
                            decided = true;
                            Navigator.pop(dialog, true);
                          },
                          child: const Text('移入回收站'))
                    ]));
        if (!mounted || confirmed != true) return;
        await LocalBridge.call('delete', {'path': file['path']});
        if (mounted) {
          await load();
          if (mounted) message(context, '已移入本地回收站');
        }
      });

  Future<void> workspace(Map<String, dynamic> file) async {
    await openNativeEditor(file);
    if (mounted) await load();
  }

  /// 打开玻璃自绘的打包页（ProjectPage）：配置、签名、权限、进度都在这一页。
  Future<void> openProjectPage(String path) async {
    if (!mounted) return;
    await Navigator.of(context)
        .push(MaterialPageRoute<void>(builder: (_) => ProjectPage(path: path)));
    if (mounted) await load();
  }

  /// 单文件/示例文件：先让桥把它整理成一个临时项目，再打开打包页。
  Future<void> openPreparedProject(Map<String, dynamic> file) =>
      mutation(() async {
        final value =
            await LocalBridge.call('prepareSingleFile', {'path': file['path']});
        if (value is! Map || value['path'] is! String) {
          throw StateError('无法准备打包项目');
        }
        if (!mounted) return;
        await openProjectPage(value['path'] as String);
      });

  /// 项目配置与打包统一走原生打包页（MiuixBuildActivity：配置、签名、进度都在那一页）。
  Future<void> projectSettings(String directory) async {
    if (busy) return;
    await openProjectPage(directory);
  }

  /// 设置任务 hands the script to the original task editor, which owns both the
  /// timed and the intent task forms. It saves through the same TimedTaskManager
  /// table the 任务管理 page polls, so a saved task appears there by itself.
  Future<void> timedTask(Map<String, dynamic> file) => mutation(() async {
        await LocalBridge.call('timedTask', {'path': file['path']});
        if (mounted) message(context, '已打开任务设置，保存后可在任务管理看到这个任务');
      });

  /// 单文件打包：桥把它整理成临时项目后，打开玻璃打包页。
  Future<void> singleFileBuild(Map<String, dynamic> file) =>
      openPreparedProject(file);

  Future<void> resetSample(Map<String, dynamic> file) => mutation(() async {
        final confirmed = await showDialog<bool>(
            context: context,
            builder: (dialog) => AlertDialog(
                    title: Text('重置“${file['name']}”？'),
                    content: const Text('恢复安装包中的原始示例，当前示例修改将被覆盖。'),
                    actions: [
                      TextButton(
                          onPressed: () => Navigator.pop(dialog, false),
                          child: const Text('取消')),
                      TextButton(
                          onPressed: () => Navigator.pop(dialog, true),
                          child: const Text('重置'))
                    ]));
        if (!mounted || confirmed != true) return;
        await LocalBridge.call('resetSample', {'path': file['path']});
        if (!mounted) return;
        await load();
        if (mounted) message(context, '已恢复原始示例');
      });

  /// 示例页：打包单个示例（先落盘成临时项目，再打开玻璃打包页）。
  Future<void> sampleBuild(Map<String, dynamic> file) =>
      openPreparedProject(file);

  /// 示例页：带 project.json 的目录按项目模式打包（复制成临时项目后打开玻璃打包页）。
  Future<void> sampleBuildProject(Map<String, dynamic> file) =>
      openPreparedProject(file);

  /// 示例页：导入到我的脚本（文件按原生 importForEdit 复制伴生文件，不覆盖已有）。
  Future<void> sampleImport(Map<String, dynamic> file) => mutation(() async {
        final result = await LocalBridge.call(
            'sampleAction', {'action': 'import', 'path': file['path']});
        if (!mounted) return;
        final imported = result is Map ? result['path'] : null;
        message(context, imported == null ? '示例已导入脚本目录' : '已导入：$imported');
      });

  /// 示例页：把所有内置示例恢复成安装包里的版本（只动 files/sample）。
  Future<void> sampleResetAll() async {
    final confirmed = await showDialog<bool>(
        context: context,
        builder: (dialog) => AlertDialog(
                title: const Text('重置所有示例？'),
                content: const Text('内置示例会恢复为安装包中的原始内容，示例目录里的改动将被覆盖；我的脚本不受影响。'),
                actions: [
                  TextButton(
                      onPressed: () => Navigator.pop(dialog, false),
                      child: const Text('取消')),
                  TextButton(
                      onPressed: () => Navigator.pop(dialog, true),
                      child: const Text('重置'))
                ]));
    if (!mounted || confirmed != true) return;
    await mutation(() async {
      await LocalBridge.call('sampleAction', {'action': 'resetAll'});
      if (!mounted) return;
      await load();
      if (mounted) message(context, '所有示例已恢复为内置版本');
    });
  }

  void details(Map<String, dynamic> file) {
    final date = modifiedDate(file['modified']);
    showDialog<void>(
        context: context,
        builder: (_) => AlertDialog(
                title: Text(file['name']),
                content: SingleChildScrollView(
                    child: SelectableText(
                        '类型：${fileType(file)}\n${file['directory'] == true ? '' : '大小：${formatSize(file['size'])}\n'}${date.isEmpty ? '' : '修改时间：$date\n'}路径：${file['path']}')),
                actions: [
                  TextButton(
                      onPressed: () => Navigator.pop(context),
                      child: const Text('关闭'))
                ]));
  }

  Future<void> itemMenu(Map<String, dynamic> file) async {
    if (busy) return;
    if (file['pendingSample'] == true) {
      if (downloadProgressFor(file) != null) return;
      final confirmed = await showDialog<bool>(
          context: context,
          builder: (dialog) => AlertDialog(
                  title: Text(file['sampleTitle']?.toString() ?? file['name']),
                  content: Column(
                      mainAxisSize: MainAxisSize.min,
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        if ((file['sampleDescription']?.toString() ?? '').isNotEmpty)
                          Text(file['sampleDescription'].toString()),
                        if ((file['sampleDescription']?.toString() ?? '').isNotEmpty)
                          const SizedBox(height: 8),
                        const Text('这个示例体积较大，安装包里没有内置；下载到本地示例目录后即可直接运行。')
                      ]),
                  actions: [
                    TextButton(
                        onPressed: () => Navigator.pop(dialog, false),
                        child: const Text('取消')),
                    TextButton(
                        onPressed: () => Navigator.pop(dialog, true),
                        child: const Text('下载'))
                  ]));
      if (!mounted || confirmed != true) return;
      await downloadSample(file);
      return;
    }
    if (createExpanded) setState(() => createExpanded = false);
    final action = await showModalBottomSheet<String>(
        context: context,
        isScrollControlled: true,
        constraints:
            BoxConstraints(maxHeight: MediaQuery.of(context).size.height * .85),
        backgroundColor: Colors.transparent,
        builder: (sheet) => GlassSurface(
            radius: 24,
            padding: const EdgeInsets.symmetric(vertical: 8, horizontal: 4),
            child: SafeArea(
                top: false,
                child: SingleChildScrollView(
                    child: Column(mainAxisSize: MainAxisSize.min, children: [
                  ListTile(
                      title: Text(file['name'],
                          maxLines: 2, overflow: TextOverflow.ellipsis),
                      subtitle: Text(fileType(file))),
                  if (file['directory'] == true || editable(file))
                    ListTile(
                        leading: const Icon(Icons.open_in_new_rounded),
                        title: Text(file['directory'] == true
                            ? '打开文件夹'
                            : widget.readOnly
                                ? '查看示例'
                                : '编辑文件'),
                        onTap: () => Navigator.pop(sheet, 'open')),
                  if (runnable(file) || file['project'] == true)
                    ListTile(
                        leading: const Icon(Icons.play_arrow_rounded),
                        title: Text(file['project'] == true
                            ? '运行项目'
                            : widget.readOnly
                                ? '运行示例'
                                : '运行脚本'),
                        onTap: () => Navigator.pop(sheet, 'run')),
                  if (runnable(file))
                    ListTile(
                        leading:
                            const Icon(Icons.picture_in_picture_alt_rounded),
                        title: const Text('悬浮运行'),
                        onTap: () => Navigator.pop(sheet, 'floatRun')),
                  if ((file['directory'] == true || editable(file)) &&
                      !widget.readOnly)
                    ListTile(
                        leading: const Icon(Icons.code_rounded),
                        title: const Text('打开工作区'),
                        onTap: () => Navigator.pop(sheet, 'workspace')),
                  if (!widget.readOnly && file['directory'] == true)
                    ListTile(
                        leading: const Icon(Icons.terminal_rounded),
                        title: const Text('终端'),
                        onTap: () => Navigator.pop(sheet, 'terminal')),
                  if (!widget.readOnly && file['project'] == true)
                    ListTile(
                        leading: const Icon(Icons.android_rounded),
                        title: const Text('项目配置与打包'),
                        onTap: () => Navigator.pop(sheet, 'project')),
                  if (!widget.readOnly) ...[
                    ListTile(
                        leading: const Icon(Icons.copy_rounded),
                        title: const Text('复制'),
                        onTap: () => Navigator.pop(sheet, 'copy')),
                    ListTile(
                        leading: const Icon(Icons.content_cut_rounded),
                        title: const Text('剪切'),
                        onTap: () => Navigator.pop(sheet, 'cut')),
                    if (file['directory'] == true && clipboardPaths.isNotEmpty)
                      ListTile(
                          leading: const Icon(Icons.content_paste_rounded),
                          title: const Text('粘贴到此处'),
                          onTap: () => Navigator.pop(sheet, 'pasteHere')),
                    ListTile(
                        leading:
                            const Icon(Icons.drive_file_rename_outline_rounded),
                        title: const Text('重命名'),
                        onTap: () => Navigator.pop(sheet, 'rename')),
                    ListTile(
                        leading: const Icon(Icons.delete_outline_rounded),
                        title: const Text('删除'),
                        onTap: () => Navigator.pop(sheet, 'delete')),
                  ],
                  if (!widget.readOnly)
                    ListTile(
                        leading: const Icon(Icons.share_outlined),
                        title: Text(
                            file['directory'] == true ? '压缩并发送文件夹' : '发送文件'),
                        onTap: () => Navigator.pop(sheet, 'share')),
                  if (file['directory'] != true && !widget.readOnly) ...[
                    ListTile(
                        leading: const Icon(Icons.add_to_home_screen_rounded),
                        title: const Text('快捷方式'),
                        onTap: () => Navigator.pop(sheet, 'shortcut')),
                    ListTile(
                        leading: const Icon(Icons.open_in_browser_rounded),
                        title: const Text('其他应用打开'),
                        onTap: () => Navigator.pop(sheet, 'openWith')),
                  ],
                  if (runnable(file) && !widget.readOnly) ...[
                    ListTile(
                        leading: const Icon(Icons.schedule_rounded),
                        title: const Text('设置任务'),
                        onTap: () => Navigator.pop(sheet, 'timedTask')),
                    ListTile(
                        leading: const Icon(Icons.android_rounded),
                        title: const Text('打包单文件'),
                        onTap: () => Navigator.pop(sheet, 'singleFileBuild')),
                  ],
                  if (widget.readOnly) ...[
                    if (file['directory'] != true)
                      ListTile(
                          leading: const Icon(Icons.android_rounded),
                          title: const Text('打包'),
                          onTap: () => Navigator.pop(sheet, 'sampleBuild')),
                    if (file['directory'] != true)
                      ListTile(
                          leading: const Icon(Icons.save_alt_rounded),
                          title: const Text('导入到我的脚本'),
                          onTap: () => Navigator.pop(sheet, 'sampleImport')),
                    if (file['directory'] == true && file['project'] == true)
                      ListTile(
                          leading: const Icon(Icons.android_rounded),
                          title: const Text('打包（项目）'),
                          onTap: () =>
                              Navigator.pop(sheet, 'sampleBuildProject')),
                    if (!isDownloadedSamplePath(file['path']?.toString() ?? ''))
                      ListTile(
                          leading: const Icon(Icons.restore_rounded),
                          title: const Text('重置示例'),
                          onTap: () => Navigator.pop(sheet, 'resetSample')),
                    if (file['directory'] == true)
                      ListTile(
                          leading: const Icon(Icons.settings_backup_restore),
                          title: const Text('重置所有示例'),
                          onTap: () => Navigator.pop(sheet, 'sampleResetAll')),
                  ],
                  if (!widget.readOnly) ...[
                    ListTile(
                        leading: const Icon(Icons.link_rounded),
                        title: const Text('复制路径'),
                        onTap: () => Navigator.pop(sheet, 'path')),
                    ListTile(
                        leading: const Icon(Icons.info_outline_rounded),
                        title: const Text('文件详情'),
                        onTap: () => Navigator.pop(sheet, 'details')),
                  ],
                ])))));
    if (!mounted || action == null) return;
    if (action == 'open') {
      await open(file);
      return;
    }
    if (action == 'run') {
      await run(file);
      return;
    }
    if (action == 'floatRun') {
      await floatRun(file);
      return;
    }
    if (action == 'openWith' || action == 'shortcut') {
      await mutation(() async {
        await LocalBridge.call(action, {'path': file['path']});
        if (mounted && action == 'shortcut') {
          message(context, '已请求添加桌面快捷方式，请在系统弹窗中确认');
        }
      });
      return;
    }
    if (action == 'workspace') {
      await workspace(file);
      return;
    }
    if (action == 'timedTask') {
      await timedTask(file);
      return;
    }
    if (action == 'singleFileBuild') {
      await singleFileBuild(file);
      return;
    }
    if (action == 'project') {
      final target = file['name'] == 'project.json'
          ? File(file['path'] as String).parent.path
          : file['path'] as String;
      await projectSettings(target);
      return;
    }
    if (action == 'resetSample') {
      await resetSample(file);
      return;
    }
    if (action == 'sampleBuild') {
      await sampleBuild(file);
      return;
    }
    if (action == 'sampleBuildProject') {
      await sampleBuildProject(file);
      return;
    }
    if (action == 'sampleImport') {
      await sampleImport(file);
      return;
    }
    if (action == 'sampleResetAll') {
      await sampleResetAll();
      return;
    }
    if (action == 'copy' || action == 'cut') {
      await copyOne(file, move: action == 'cut');
      return;
    }
    if (action == 'pasteHere') {
      await pasteInto(file['path'] as String);
      return;
    }
    if (action == 'rename') {
      await rename(file);
      return;
    }
    if (action == 'delete') {
      await delete(file);
      return;
    }
    if (action == 'share' || action == 'terminal' || action == 'copyExample') {
      await mutation(() async {
        await LocalBridge.call(
            action == 'share'
                ? 'shareFile'
                : action == 'terminal'
                    ? 'openTerminal'
                    : 'copyExampleTree',
            {'path': file['path']});
        if (mounted && action == 'copyExample')
          message(context, '已复制到脚本目录，同名文件不会覆盖');
      });
      return;
    }
    if (action == 'details') {
      details(file);
      return;
    }
    try {
      if (action == 'path') {
        await Clipboard.setData(ClipboardData(text: file['path']));
        if (mounted) message(context, '路径已复制');
      }
    } catch (e) {
      if (mounted) message(context, e.toString());
    }
  }

  Widget breadcrumbs() {
    final relative = path == widget.root
        ? <String>[]
        : path.substring(widget.root.length + 1).split('/');
    // 对齐 AI.js Pro MiuixScriptListHost.kt:481-487 ——
    // 分隔符用**文本** `›`（原为 chevron 图标），字号 14sp（原为 13）；
    // 水平内边距 8（原为 4/段），与 AI.js Pro 的 padding(horizontal = 8.dp) 一致。
    const crumbStyle = TextStyle(fontSize: 14, fontWeight: FontWeight.w500);
    const crumbPadding = EdgeInsets.symmetric(vertical: 14, horizontal: 8);
    return SingleChildScrollView(
        scrollDirection: Axis.horizontal,
        controller: breadcrumbScroll,
        child: Row(children: [
          InkWell(
              onTap: path == widget.root ? null : () => navigate(widget.root),
              onLongPress: () => copyBreadcrumb(widget.root),
              child: Padding(
                  padding: crumbPadding,
                  child: Text(
                      relative.isEmpty
                          ? widget.title
                          : widget.readOnly
                              ? '示例'
                              : '脚本',
                      style: crumbStyle))),
          for (var i = 0; i < relative.length; i++) ...[
            const Padding(
                padding: EdgeInsets.symmetric(vertical: 14),
                child: Text('›', style: crumbStyle)),
            InkWell(
                onTap: i == relative.length - 1
                    ? null
                    : () => navigate(
                        '${widget.root}/${relative.take(i + 1).join('/')}'),
                onLongPress: () => copyBreadcrumb(
                    '${widget.root}/${relative.take(i + 1).join('/')}'),
                child: Padding(
                    padding: crumbPadding,
                    child: Text(relative[i], style: crumbStyle)))
          ]
        ]));
  }

  void toggleSearch() {
    setState(() {
      searching = !searching;
      search = '';
    });
    load();
  }

  Future<void> copyBreadcrumb(String destination) async {
    await Clipboard.setData(ClipboardData(text: destination));
    if (mounted) message(context, '目录路径已复制');
  }

  Future<void> filterDialog() async {
    final selected = options.selectedCategories;
    var showHidden = options.showHidden;
    // 高级版筛选还有一个「只看项目」快捷开关：等价于只勾选「项目」分类。
    var onlyProjects = selected.length == 1 && selected.contains('projects');
    final result = await showDialog<bool>(
        context: context,
        builder: (dialog) => StatefulBuilder(
            builder: (dialog, update) => AlertDialog(
                    title: const Text('筛选文件'),
                    content: SingleChildScrollView(
                        child:
                            Column(mainAxisSize: MainAxisSize.min, children: [
                      SwitchListTile(
                          contentPadding: EdgeInsets.zero,
                          title: const Text('只看项目'),
                          value: onlyProjects,
                          onChanged: (value) => update(() {
                                onlyProjects = value;
                                selected.clear();
                                if (value) {
                                  selected.add('projects');
                                } else {
                                  selected.addAll(
                                      BrowserOptions.categoryNames.keys);
                                }
                              })),
                      const Divider(),
                      for (final item in BrowserOptions.categoryNames.entries)
                        CheckboxListTile(
                            contentPadding: EdgeInsets.zero,
                            title: Text(item.value),
                            value: selected.contains(item.key),
                            onChanged: (value) => update(() {
                                  onlyProjects = false;
                                  if (value == true) {
                                    selected.add(item.key);
                                  } else {
                                    selected.remove(item.key);
                                  }
                                })),
                      const Divider(),
                      SwitchListTile(
                          contentPadding: EdgeInsets.zero,
                          title: const Text('显示隐藏文件'),
                          value: showHidden,
                          onChanged: (value) =>
                              update(() => showHidden = value))
                    ])),
                    actions: [
                      TextButton(
                          onPressed: () => update(() {
                                onlyProjects = false;
                                selected.clear();
                                selected
                                    .addAll(BrowserOptions.categoryNames.keys);
                              }),
                          child: const Text('全部')),
                      TextButton(
                          onPressed: () => Navigator.pop(dialog, false),
                          child: const Text('取消')),
                      TextButton(
                          onPressed: () => Navigator.pop(dialog, true),
                          child: const Text('应用'))
                    ])));
    if (!mounted || result != true) return;
    await setOptions(options.copyWith(
        filter: 'all', categories: selected.toList(), showHidden: showHidden));
  }

  /// 示例页行副标题对齐原生 MiuixSampleFragment.sampleDescription：
  /// 文件夹 / JavaScript 示例 / Markdown 文档 / JSON 文件 / 安装包 / 示例资源。
  String sampleDescription(Map<String, dynamic> file) {
    final name = (file['name'] ?? '').toString().toLowerCase();
    if (file['directory'] == true) return '文件夹';
    if (name.endsWith('.js')) return 'JavaScript 示例';
    if (name.endsWith('.md')) return 'Markdown 文档';
    if (name.endsWith('.json')) return 'JSON 文件';
    if (name.endsWith('.apk')) return '安装包';
    return '示例资源';
  }

  Widget fileRow(Map<String, dynamic> file) {
    final directory = file['directory'] == true,
        project = file['project'] == true;
    // 示例页在 AI.js Pro 里是**独立一套行组件**，比文件页大一号：
    //   文件页 MiuixScriptListHost.kt:642/647/656/667/568·573·598·604/692
    //          → 行 60dp、圆标 44dp(内 24)、名称 16·15sp、次级 12·11sp、缩进 74dp
    //   示例页 MiuixSampleFragment.kt:277/290/294/299/300/312
    //          → 行 74dp、圆标 50dp(内 28)、名称 18sp、次级 14sp、缩进 82dp
    // FileBrowser 只被两处实例化（main.dart:489 脚本页 / :495 示例页），
    // readOnly 就是示例页的标识（全工程仅 main.dart:499 传 true）。
    final sample = widget.readOnly;
    final rowMinHeight = sample ? 74.0 : 60.0;
    final circleSize = sample ? 50.0 : 44.0;
    final circleIconSize = sample ? 28.0 : 24.0;
    final nameFontSize = sample ? 18.0 : 15.0;
    final infoFontSize = sample ? 14.0 : 11.0;
    final dividerIndent = sample ? 82.0 : 74.0;
    final rowPadding = sample
        ? const EdgeInsets.fromLTRB(18, 6, 8, 6)
        : const EdgeInsets.fromLTRB(16, 5, 6, 5);
    // 图标体系对齐高级版文件管理（com.boyai.auma 截图）：
    //   文件夹 = 实心蓝色圆底 + 白色实心文件夹；项目 = 同一个文件夹底 + 右下角星标角标；
    //   .js/.mjs 脚本 = code 图标（<>），可运行=绿色圆底、普通文件=近黑圆底；
    //   其余文件 = 黑色圆底 + 文档图标（白）。
    final pending = file['pendingSample'] == true;
    final runnableFile = runnable(file);
    final color = pending
        ? const Color(0xff607d8b)
        : directory
            ? const Color(0xff2196f3)
            : runnableFile
                ? const Color(0xff2e9e5b)
                : const Color(0xff24272b);
    final date = modifiedDate(file['modified']);
    final full = file['path']?.toString() ?? '';
    final parentPath = full.isEmpty ? '' : File(full).parent.path;
    final relativeParent = parentPath.startsWith('$path/')
        ? parentPath.substring(path.length + 1)
        : '';
    final muted = Theme.of(context).colorScheme.onSurface.withOpacity(.55);
    final choosing = selecting;
    final chosen = selected.contains(full);
    return Material(
        color: choosing
            ? Theme.of(context).colorScheme.primary.withValues(alpha: .10)
            : Colors.transparent,
        child: Column(children: [
          InkWell(
              onTap: () => selecting ? toggleSelection(file) : open(file),
              onLongPress: () => selecting
                  ? toggleSelection(file)
                  : (widget.readOnly ? itemMenu(file) : startSelection(file)),
              child: ConstrainedBox(
                  constraints: BoxConstraints(minHeight: rowMinHeight),
                  child: Padding(
                      padding: rowPadding,
                      child: Row(children: [
                        SizedBox(
                            width: circleSize,
                            height: circleSize,
                            child: Stack(clipBehavior: Clip.none, children: [
                              Container(
                                  width: circleSize,
                                  height: circleSize,
                                  decoration: BoxDecoration(
                                      color: color, shape: BoxShape.circle),
                                  child: Icon(
                                      pending
                                          ? Icons.cloud_download_outlined
                                          : directory
                                              ? Icons.folder_rounded
                                              : runnableFile
                                                  ? Icons.code_rounded
                                                  : Icons.description_outlined,
                                      color: Colors.white,
                                      size: circleIconSize)),
                              if (project)
                                Positioned(
                                    right: -1,
                                    bottom: -1,
                                    child: Container(
                                        padding: const EdgeInsets.all(1),
                                        decoration: BoxDecoration(
                                            color: Theme.of(context)
                                                .colorScheme
                                                .surface,
                                            shape: BoxShape.circle),
                                        child: const Icon(Icons.star_rounded,
                                            size: 15,
                                            color: Color(0xffffb300)))),
                            ])),
                        const SizedBox(width: 14),
                        Expanded(
                            child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                              Text(file['name'],
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                  style: TextStyle(fontSize: nameFontSize)),
                              if (search.isNotEmpty &&
                                  relativeParent.isNotEmpty)
                                Text(relativeParent,
                                    maxLines: 1,
                                    overflow: TextOverflow.ellipsis,
                                    style: TextStyle(
                                        fontSize: infoFontSize, color: muted)),
                              // 对齐 AI.js Pro 的 res/values/strings.xml：
                              //   text_file_modified = "%1$s\n修改于 %2$s"  → 第 2 行是**大小**
                              //   text_directory_modified = "文件夹\n修改于 %1$s"
                              // 原实现第 2 行放的是类型名（fileType），且文件行会丢掉大小语义。
                              // 类型名改由左侧圆标图标表达（与原实现一致）。
                              if (pending) ...[
                                Text(pendingSampleText(file),
                                    maxLines: 2,
                                    overflow: TextOverflow.ellipsis,
                                    style: TextStyle(
                                        fontSize: infoFontSize, color: muted)),
                                if (downloadFraction(file) != null)
                                  Padding(
                                      padding: const EdgeInsets.only(
                                          top: 5, right: 16),
                                      child: LinearProgressIndicator(
                                          value: downloadFraction(file),
                                          minHeight: 3))
                              ] else if (sample)
                                Text(sampleDescription(file),
                                    style: TextStyle(
                                        fontSize: infoFontSize, color: muted))
                              else ...[
                                Text(
                                    directory
                                        ? '文件夹'
                                        : formatSize(file['size']),
                                    style: TextStyle(
                                        fontSize: infoFontSize, color: muted)),
                                if (date.isNotEmpty)
                                  Text('修改于 $date',
                                      style: TextStyle(
                                          fontSize: infoFontSize, color: muted))
                              ]
                            ])),
                        if (selecting)
                          Padding(
                              padding: const EdgeInsets.only(right: 12),
                              child: Icon(
                                  chosen
                                      ? Icons.check_circle
                                      : Icons.radio_button_unchecked,
                                  size: 22,
                                  color: choosing
                                      ? Theme.of(context).colorScheme.primary
                                      : Theme.of(context).colorScheme.outline))
                        else if (pending)
                          pendingSampleAction(file)
                        else ...[
                          if (runnable(file) || project)
                            ActionIcon(
                                tooltip:
                                    '${project ? '运行项目' : '运行'}：${file['name']}',
                                height: 48,
                                icon: const Icon(Icons.play_arrow_rounded,
                                    size: 27),
                                onPressed: running.contains(file['path'])
                                    ? null
                                    : () => run(file)),
                          ActionIcon(
                              tooltip: '更多操作：${file['name']}',
                              height: 48,
                              icon:
                                  const Icon(Icons.more_vert_rounded, size: 24),
                              onPressed: () => itemMenu(file))
                        ]
                      ])))),
          Divider(
              height: 1,
              thickness: .5,
              // 示例页缩进 82dp（MiuixSampleFragment.kt:312），文件页 74dp
              indent: dividerIndent)
        ]));
  }

  Widget selectionBar() => Padding(
      padding: const EdgeInsets.fromLTRB(12, 0, 4, 0),
      child: Row(children: [
        Expanded(
            child: Text('已选 ${selected.length} 项',
                style: const TextStyle(fontSize: 15))),
        ActionIcon(
            tooltip: '全选',
            icon: const Icon(Icons.select_all_rounded, size: 21),
            onPressed: selectAllVisible),
        ActionIcon(
            tooltip: '复制',
            icon: const Icon(Icons.copy_rounded, size: 21),
            onPressed:
                selected.isEmpty ? null : () => copySelection(move: false)),
        ActionIcon(
            tooltip: '剪切',
            icon: const Icon(Icons.content_cut_rounded, size: 21),
            onPressed:
                selected.isEmpty ? null : () => copySelection(move: true)),
        ActionIcon(
            tooltip: '移入回收站',
            icon: const Icon(Icons.delete_outline_rounded, size: 21),
            onPressed: selected.isEmpty ? null : deleteSelected),
        ActionIcon(
            tooltip: '取消选择',
            icon: const Icon(Icons.close_rounded, size: 22),
            onPressed: clearSelection),
      ]));

  @override
  Widget build(BuildContext context) {
    // The native worker already applies the query with a time limit. Running
    // the same regular expression on Flutter's UI thread could freeze input.
    // Menu/selection/loading updates do not change the directory order.
    if (!identical(sortedSource, files) || !identical(sortedOptions, options)) {
      sortedFiles = browserFiles(files ?? [], options, '');
      sortedSource = files;
      sortedOptions = options;
    }
    final visible = sortedFiles;
    final currentProject = !widget.readOnly &&
        (files ?? []).any((file) =>
            file['name'] == 'project.json' && file['directory'] != true);
    return Stack(children: [
      Column(children: [
        if (selecting && !widget.readOnly)
          selectionBar()
        else
          Padding(
              // 示例页对齐 MiuixSampleFragment.kt:226-228（条高 48、start 8）；
              // 文件页对齐 MiuixScriptListHost.kt:471-481（高 48、水平 8）。
              padding: EdgeInsets.fromLTRB(widget.readOnly ? 8 : 12, 0, 4, 0),
              child: Row(children: [
                Expanded(child: breadcrumbs()),
                if (!widget.readOnly && clipboardPaths.isNotEmpty)
                  ActionIcon(
                      tooltip: '粘贴 ${clipboardPaths.length} 项到当前目录',
                      icon: const Icon(Icons.content_paste_rounded, size: 22),
                      onPressed:
                          busy || loading ? null : () => pasteInto(path)),
                if (currentProject) ...[
                  ActionIcon(
                      tooltip: '运行项目',
                      icon: const Icon(Icons.play_arrow_rounded, size: 24),
                      onPressed: running.contains(path)
                          ? null
                          : () => run({'path': path, 'project': true})),
                  ActionIcon(
                      tooltip: '项目配置与打包',
                      icon: const Icon(Icons.android_rounded, size: 23),
                      onPressed: busy ? null : () => projectSettings(path)),
                ],
                ActionIcon(
                    tooltip: '返回上级目录',
                    icon: const Icon(Icons.arrow_upward_rounded, size: 24),
                    onPressed: path != widget.root ? parent : null),
                Semantics(
                    label: '排序方式',
                    button: true,
                    child: PopupMenuButton<String>(
                        tooltip: '排序方式',
                        icon: const Icon(Icons.sort_rounded, size: 21),
                        onSelected: (value) => setOptions(options.copyWith(
                            sort: value == 'direction' ? options.sort : value,
                            descending: value == 'direction'
                                ? !options.descending
                                : value == 'modified')),
                        itemBuilder: (_) => [
                              for (final item in BrowserOptions.sorts.entries)
                                PopupMenuItem<String>(
                                    value: item.key,
                                    child: Semantics(
                                        selected: options.sort == item.key,
                                        child: Row(children: [
                                          Icon(
                                              options.sort == item.key
                                                  ? Icons.check_rounded
                                                  : null,
                                              size: 20),
                                          const SizedBox(width: 12),
                                          Text(item.value)
                                        ]))),
                              const PopupMenuDivider(),
                              PopupMenuItem(
                                  value: 'direction',
                                  child: Text(
                                      options.descending ? '改为升序 ↑' : '改为降序 ↓'))
                            ])),
                ActionIcon(
                    tooltip: '筛选文件',
                    icon: Icon(Icons.filter_alt_outlined,
                        size: 21,
                        color: options.allCategories && !options.showHidden
                            ? null
                            : Theme.of(context).colorScheme.primary),
                    onPressed: filterDialog),
                ActionIcon(
                    tooltip: loading ? '停止等待' : '刷新文件',
                    icon: Icon(
                        loading ? Icons.close_rounded : Icons.refresh_rounded,
                        size: 22),
                    onPressed: loading ? cancelLoad : load),
              ])),
        if (loading) const LinearProgressIndicator(minHeight: 2),
        if (searching)
          Padding(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
              child: Column(children: [
                TextField(
                    autofocus: true,
                    decoration: InputDecoration(
                        isDense: true,
                        hintText: options.regex ? '搜索文件名（正则表达式）' : '搜索文件名'),
                    onChanged: changeSearch),
                Row(children: [
                  FilterChip(
                      label: const Text('包含子目录'),
                      selected: options.recursive,
                      onSelected: (value) =>
                          setOptions(options.copyWith(recursive: value))),
                  const SizedBox(width: 12),
                  FilterChip(
                      label: const Text('正则表达式'),
                      selected: options.regex,
                      onSelected: (value) =>
                          setOptions(options.copyWith(regex: value)))
                ])
              ])),
        Expanded(
            child: error != null
                ? Center(
                    child: Padding(
                        padding: const EdgeInsets.all(24),
                        child:
                            Column(mainAxisSize: MainAxisSize.min, children: [
                          Text(error!),
                          TextButton(
                              onPressed: () =>
                                  LocalBridge.call('storagePermission'),
                              child: const Text('打开存储权限设置')),
                          TextButton(onPressed: load, child: const Text('重试'))
                        ])))
                : files == null
                    ? const Center(child: CircularProgressIndicator())
                    : RefreshIndicator(
                        onRefresh: load,
                        child: ListView.builder(
                            key: ValueKey('directory-$path'),
                            controller: scroll,
                            physics: const AlwaysScrollableScrollPhysics(),
                            padding: const EdgeInsets.only(bottom: 88),
                            itemCount: visible.isEmpty ? 1 : visible.length,
                            itemBuilder: (_, index) => visible.isEmpty
                                ? Padding(
                                    padding: const EdgeInsets.only(top: 80),
                                    child: Center(
                                        child: Text(
                                            search.isEmpty &&
                                                    options.allCategories
                                                ? '这里还没有文件\n下拉可以刷新'
                                                : '没有匹配的文件\n可清除搜索或切换筛选',
                                            textAlign: TextAlign.center)))
                                : fileRow(visible[index])))),
      ]),
      if (createExpanded)
        Positioned.fill(
            child: GestureDetector(
                behavior: HitTestBehavior.opaque,
                onTap: () => setState(() => createExpanded = false),
                child: Container(color: Colors.black.withOpacity(.04)))),
      if (createExpanded)
        Positioned(
            right: 16,
            bottom: 86,
            child:
                Column(crossAxisAlignment: CrossAxisAlignment.end, children: [
              for (final item in const [
                ['file', '文件', Icons.description_outlined],
                ['folder', '文件夹', Icons.folder_rounded],
                ['project', '项目', Icons.architecture_rounded],
                ['import', '导入', Icons.file_download_outlined]
              ])
                Padding(
                    padding: const EdgeInsets.only(bottom: 10),
                    child: GlassSurface(
                        radius: 24,
                        padding: EdgeInsets.zero,
                        child: Material(
                            color: Colors.transparent,
                            child: InkWell(
                                borderRadius: BorderRadius.circular(24),
                                onTap: busy
                                    ? null
                                    : () => create(item[0] as String),
                                child: Padding(
                                    padding: const EdgeInsets.symmetric(
                                        horizontal: 16, vertical: 12),
                                    child: Row(
                                        mainAxisSize: MainAxisSize.min,
                                        children: [
                                          Text(item[1] as String),
                                          const SizedBox(width: 16),
                                          Icon(item[2] as IconData, size: 24)
                                        ]))))))
            ])),
      if (!widget.readOnly)
        Positioned(
            right: 16,
            bottom: 16,
            child: Semantics(
                label: busy
                    ? '文件操作处理中'
                    : createExpanded
                        ? '收起新建菜单'
                        : '新建菜单',
                button: true,
                enabled: !busy,
                onTap: busy
                    ? null
                    : () => setState(() => createExpanded = !createExpanded),
                child: ExcludeSemantics(
                    child: FloatingActionButton(
                        heroTag: 'create-script',
                        tooltip: createExpanded ? '收起新建菜单' : '新建菜单',
                        backgroundColor: AppTheme.actionBackground(context,
                            enabled: !busy),
                        foregroundColor: AppTheme.actionForeground(context,
                            enabled: !busy),
                        onPressed: busy
                            ? null
                            : () => setState(
                                () => createExpanded = !createExpanded),
                        child: busy
                            ? const Icon(Icons.hourglass_top_rounded)
                            : Icon(createExpanded
                                ? Icons.close_rounded
                                : Icons.add_rounded)))))
    ]);
  }
}

class _WorkspaceName {
  final String name, engine;
  const _WorkspaceName(this.name, this.engine);
}

class _WorkspaceNameDialog extends StatefulWidget {
  final String title, initialName, confirm;
  final String? original;
  final List<String> existing;
  final bool script, chooseEngine;
  final String initialEngine;
  final bool engineLocked;
  const _WorkspaceNameDialog(
      {required this.title,
      required this.initialName,
      required this.existing,
      required this.confirm,
      this.original,
      this.script = false,
      this.initialEngine = 'rhino',
      this.engineLocked = false,
      this.chooseEngine = false});
  @override
  State<_WorkspaceNameDialog> createState() => _WorkspaceNameDialogState();
}

class _WorkspaceNameDialogState extends State<_WorkspaceNameDialog> {
  late final input = TextEditingController(text: widget.initialName);
  late String engine = widget.initialEngine;
  String? error;
  bool submitted = false;
  @override
  void dispose() {
    input.dispose();
    super.dispose();
  }

  void submit() {
    if (submitted) return;
    final problem = workspaceNameError(input.text, widget.existing,
        original: widget.original, script: widget.script);
    if (problem != null) {
      setState(() => error = problem);
      return;
    }
    submitted = true;
    Navigator.pop(context, _WorkspaceName(input.text.trim(), engine));
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
          title: Text(widget.title),
          content: Column(mainAxisSize: MainAxisSize.min, children: [
            TextField(
                controller: input,
                autofocus: true,
                decoration: InputDecoration(
                    labelText: widget.script ? '文件名' : '名称', errorText: error),
                onChanged: (_) {
                  if (error != null) setState(() => error = null);
                },
                onSubmitted: (_) => submit()),
            if (widget.engineLocked)
              const Padding(
                  padding: EdgeInsets.only(top: 12),
                  child: Text('当前 Node 项目使用 Node.js，文件模板跟随项目引擎。',
                      style: TextStyle(fontSize: 12))),
            if (widget.chooseEngine)
              DropdownButtonFormField<String>(
                  value: engine,
                  decoration: const InputDecoration(labelText: '脚本引擎'),
                  items: const [
                    DropdownMenuItem(value: 'rhino', child: Text('Rhino')),
                    DropdownMenuItem(value: 'node', child: Text('Node.js'))
                  ],
                  onChanged: (value) {
                    if (value == null) return;
                    setState(() {
                      engine = value;
                      if (widget.script) {
                        var name = input.text;
                        if (value == 'node' &&
                            name.endsWith('.js') &&
                            !name.endsWith('.node.js')) {
                          name =
                              '${name.substring(0, name.length - 3)}.node.js';
                        } else if (value == 'rhino' &&
                            name.endsWith('.node.js')) {
                          name = '${name.substring(0, name.length - 8)}.js';
                        }
                        input.value = TextEditingValue(
                            text: name,
                            selection:
                                TextSelection.collapsed(offset: name.length));
                      }
                      error = null;
                    });
                  })
          ]),
          actions: [
            TextButton(
                onPressed: () => Navigator.pop(context),
                child: const Text('取消')),
            TextButton(onPressed: submit, child: Text(widget.confirm))
          ]);
}
