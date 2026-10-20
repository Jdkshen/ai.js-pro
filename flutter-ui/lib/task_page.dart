part of 'main.dart';

class RuntimeTask {
  final String token, kind, name, path, engine, detail;
  final int nextTime;
  RuntimeTask._(this.token, this.kind, this.name, this.path, this.engine,
      this.detail, this.nextTime);

  factory RuntimeTask(Map<String, dynamic> row) {
    final rawKind = row['kind'] as String? ?? '';
    final rawEngine = (row['engine'] as String? ?? '').toLowerCase();
    final kind =
        rawKind == 'script' || rawKind == 'running' ? 'running' : rawKind;
    final engine = rawEngine.contains('rhino')
        ? 'Rhino'
        : (rawEngine.contains('quickjs') ? 'QuickJS' : 'Node');
    return RuntimeTask._(
      row['token'] as String,
      kind,
      row['name'] as String,
      row['path'] as String? ?? '',
      engine,
      row['detail'] as String? ?? '',
      (row['nextTime'] as num?)?.toInt() ?? 0,
    );
  }
  bool matches(String needle) => '$name $path $detail $engine'
      .toLowerCase()
      .contains(needle.trim().toLowerCase());
  String get description {
    if (nextTime <= 0) return detail;
    final date = DateTime.fromMillisecondsSinceEpoch(nextTime);
    String two(int n) => n.toString().padLeft(2, '0');
    return '$detail · ${date.year}-${two(date.month)}-${two(date.day)} '
        '${two(date.hour)}:${two(date.minute)}';
  }
}

class TaskPage extends StatefulWidget {
  final bool active;
  final VoidCallback openDevice;
  const TaskPage({Key? key, required this.active, required this.openDevice})
      : super(key: key);
  @override
  State<TaskPage> createState() => _TaskPageState();
}

class _TaskPageState extends State<TaskPage> with WidgetsBindingObserver {
  List<RuntimeTask> running = [], scheduled = [];
  final search = TextEditingController();
  final notices = GlobalKey<ScaffoldMessengerState>();
  Timer? timer;
  Future<bool>? inFlight;
  bool loading = false, loaded = false, changing = false, foreground = true;
  bool searching = false, runningExpanded = false, scheduledExpanded = false;
  String? error, operationError;
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    updatePolling();
  }

  void updatePolling() {
    timer?.cancel();
    if (!widget.active) return;
    refresh();
    timer = Timer.periodic(const Duration(seconds: 2), (_) {
      if (foreground && ModalRoute.of(context)?.isCurrent != false) refresh();
    });
  }

  @override
  void didUpdateWidget(TaskPage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.active != widget.active) updatePolling();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    foreground = state == AppLifecycleState.resumed;
    if (foreground && widget.active) refresh();
  }

  @override
  void dispose() {
    timer?.cancel();
    search.dispose();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  Future<bool> refresh({bool requireFresh = false}) async {
    // A mutation must wait for an older poll and then read again. Sharing its
    // pre-mutation snapshot can otherwise confirm the wrong task state.
    if (requireFresh && inFlight != null) await inFlight;
    if (!mounted) return false;
    if (inFlight != null) return inFlight!;
    final completed = Completer<bool>();
    inFlight = completed.future;
    readSnapshot().then((result) {
      inFlight = null;
      completed.complete(result);
    }, onError: (Object failure) {
      inFlight = null;
      completed.complete(false);
    });
    return completed.future;
  }

  Future<bool> readSnapshot() async {
    setState(() => loading = true);
    try {
      final value = await LocalBridge.call('tasks') as Map<String, dynamic>;
      final active = (value['running'] as List)
          .map((e) => RuntimeTask(Map<String, dynamic>.from(e)))
          .toList();
      final pending = (value['scheduled'] as List)
          .map((e) => RuntimeTask(Map<String, dynamic>.from(e)))
          .toList();
      if (!mounted) return false;
      setState(() {
        if (!loaded) {
          runningExpanded = active.isNotEmpty;
          scheduledExpanded = pending.isNotEmpty;
        }
        running = active;
        scheduled = pending;
        loaded = true;
        error = null;
      });
      return true;
    } catch (e) {
      if (mounted) setState(() => error = e.toString());
      return false;
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  void toggleSearch() => setState(() {
        searching = !searching;
        if (searching) {
          runningExpanded = true;
          scheduledExpanded = true;
        }
        if (!searching) search.clear();
      });

  bool clearSearch() {
    if (!searching && search.text.isEmpty) return false;
    setState(() {
      searching = false;
      search.clear();
    });
    return true;
  }

  void notice(String text) {
    notices.currentState?.hideCurrentSnackBar();
    notices.currentState?.showSnackBar(
        SnackBar(content: Text(text), behavior: SnackBarBehavior.floating));
  }

  Future<void> stop(List<RuntimeTask> snapshot) async {
    if (changing || error != null || snapshot.isEmpty) return;
    // 直接停止：不弹确认框（停止是可恢复/可重跑的动作，用户明确要求少一步）。
    final tokens = snapshot.map((t) => t.token).toSet();
    if (!mounted) return;
    setState(() {
      changing = true;
      operationError = null;
    });
    try {
      await LocalBridge.call('stopTasks', {'tokens': tokens.toList()});
      for (var i = 0; i < 8; i++) {
        if (!mounted) return;
        if (await refresh(requireFresh: true) &&
            !running.any((t) => tokens.contains(t.token))) {
          if (mounted) notice('所选脚本已结束');
          return;
        }
        await Future<void>.delayed(const Duration(milliseconds: 250));
      }
      throw StateError('脚本仍在结束中，请稍后刷新确认');
    } catch (e) {
      if (mounted) setState(() => operationError = e.toString());
    } finally {
      if (mounted) setState(() => changing = false);
    }
  }

  /// task-165：停止全部脚本。走独立的 stopAllScripts 桥，不依赖任务页缓存的任务列表，
  /// 因此列表过期/为空时也能把 :script / :native / 主进程脚本一起停掉（并清掉前台通知）。
  Future<void> stopAll() async {
    if (changing || error != null) return;
    if (!mounted) return;
    setState(() {
      changing = true;
      operationError = null;
    });
    try {
      await LocalBridge.call('stopAllScripts', const <String, dynamic>{});
      for (var i = 0; i < 8; i++) {
        if (!mounted) return;
        if (await refresh(requireFresh: true) && running.isEmpty) {
          if (mounted) notice('已停止全部脚本');
          return;
        }
        await Future<void>.delayed(const Duration(milliseconds: 250));
      }
      throw StateError('脚本仍在结束中，请稍后刷新确认');
    } catch (e) {
      if (mounted) setState(() => operationError = e.toString());
    } finally {
      if (mounted) setState(() => changing = false);
    }
  }

  Future<void> cancel(RuntimeTask task) async {
    if (changing || error != null) return;
    // 直接取消：不弹确认框。
    if (!mounted) return;
    setState(() {
      changing = true;
      operationError = null;
    });
    try {
      await LocalBridge.call(
          'cancelTask', {'token': task.token, 'path': task.path});
      if (!mounted) return;
      if (!await refresh(requireFresh: true))
        throw StateError('无法确认取消结果，请刷新任务列表');
      if (scheduled.any((t) => t.token == task.token))
        throw StateError('任务仍在列表中，请重试');
      if (mounted) notice('任务已取消');
    } catch (e) {
      if (mounted) setState(() => operationError = e.toString());
    } finally {
      if (mounted) setState(() => changing = false);
    }
  }

  /// 任务维度日志：打开日志页并只订阅该任务的统一实时流（token 过滤）。
  ///
  /// 这里的 token 是任务页展示形态（script:3 / native:5 / 主进程纯数字），
  /// PocBridge.liveLogs 会归一化后交给 HostConsoleSink 匹配。
  /// 单实例：同一个任务连点只保留一个日志页（按 token 区分任务），不同任务各自一页。
  void openLog(RuntimeTask task) {
    pushOnce(context, 'log:${task.token}',
        (_) => LogPage(token: task.token, name: task.name));
  }

  void details(RuntimeTask task) => showDialog<void>(
      context: context,
      builder: (context) => AlertDialog(
              title: Text(task.name),
              content: SingleChildScrollView(
                  child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      mainAxisSize: MainAxisSize.min,
                      children: [
                    Text(task.description),
                    const SizedBox(height: 12),
                    SelectableText(task.path.isEmpty ? '内存脚本' : task.path)
                  ])),
              actions: [
                TextButton(
                    onPressed: () => Navigator.pop(context),
                    child: const Text('关闭')),
                TextButton(
                    onPressed: changing || error != null
                        ? null
                        : () {
                            Navigator.pop(context);
                            task.kind == 'running'
                                ? stop([task])
                                : cancel(task);
                          },
                    child: Text(task.kind == 'running' ? '停止脚本' : '取消任务'))
              ]));

  Widget heading(String title, IconData icon, int count, bool expanded,
          VoidCallback toggle) =>
      ListTile(
          contentPadding: const EdgeInsets.symmetric(horizontal: 18),
          leading: Icon(icon, size: 24, color: const Color(0xff008f91)),
          title: Text(title,
              style: const TextStyle(fontSize: 20, color: Color(0xff008f91))),
          trailing: Row(mainAxisSize: MainAxisSize.min, children: [
            if (count > 0) Text('$count', style: const TextStyle(fontSize: 13)),
            const SizedBox(width: 10),
            Icon(expanded ? Icons.expand_less : Icons.expand_more, size: 22)
          ]),
          onTap: toggle);

  Widget empty(String label, String searchLabel) => Semantics(
      label: search.text.isEmpty ? label : searchLabel,
      child: SizedBox(
          height: 160,
          child: Center(
              child: ExcludeSemantics(
                  child: Text(search.text.isEmpty ? '空无一物' : searchLabel,
                      style: TextStyle(
                          fontSize: 15,
                          color: Theme.of(context)
                              .colorScheme
                              .onSurface
                              .withOpacity(.65)))))));

  Widget row(RuntimeTask task) => Column(children: [
        InkWell(
            onTap: () => details(task),
            child: Padding(
                padding: const EdgeInsets.fromLTRB(18, 8, 6, 8),
                child: Row(children: [
                  CircleAvatar(
                      radius: 25,
                      backgroundColor: task.engine == 'Rhino'
                          ? const Color(0xfffd999a)
                          : const Color(0xff99cc99),
                      child: Text(task.engine == 'Rhino' ? 'R' : 'N',
                          style: const TextStyle(
                              fontSize: 22, color: Colors.white))),
                  const SizedBox(width: 14),
                  Expanded(
                      child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                        Text(task.name,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(fontSize: 17)),
                        Text(task.description,
                            maxLines: 2,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                                fontSize: 12,
                                color: Theme.of(context)
                                    .colorScheme
                                    .onSurface
                                    .withOpacity(.65))),
                        if (task.path.isNotEmpty)
                          Text(task.path,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              // 对齐 AI.js Pro MiuixTaskManagerFragment.kt:304 ——
                              // 筛选时第三行为路径，字号与次级一致（12.sp），原为 11
                              style: TextStyle(
                                  fontSize: 12,
                                  color: Theme.of(context)
                                      .colorScheme
                                      .onSurface
                                      .withOpacity(.55)))
                      ])),
                  if (task.kind == 'running')
                    ActionIcon(
                        key: ValueKey('task-log-${task.token}'),
                        tooltip: '查看 ${task.name} 的日志',
                        icon: const Icon(Icons.article_outlined),
                        onPressed: () => openLog(task)),
                  ActionIcon(
                      key: ValueKey('task-action-${task.token}'),
                      tooltip:
                          '${task.kind == 'running' ? '停止' : '取消'} ${task.name}',
                      icon: const Icon(Icons.close),
                      onPressed: changing || error != null
                          ? null
                          : () => task.kind == 'running'
                              ? stop([task])
                              : cancel(task))
                ]))),
        Padding(
            padding: const EdgeInsets.only(left: 82),
            child: Divider(
                height: 1,
                color: Theme.of(context).dividerColor.withOpacity(.12)))
      ]);

  @override
  Widget build(BuildContext context) {
    final active = running.where((t) => t.matches(search.text)).toList();
    final pending = scheduled.where((t) => t.matches(search.text)).toList();
    return ScaffoldMessenger(
        key: notices,
        child: Scaffold(
            backgroundColor: Colors.transparent,
            resizeToAvoidBottomInset: false,
            body: Column(children: [
              Padding(
                  padding: const EdgeInsets.fromLTRB(16, 0, 6, 0),
                  child: Row(children: [
                    const Expanded(
                        child: Text('任务管理', style: TextStyle(fontSize: 16))),
                    ActionIcon(
                        tooltip: '刷新任务',
                        icon: const Icon(Icons.refresh),
                        onPressed: loading ? null : refresh),
                    ActionIcon(
                        tooltip: '设备连接',
                        icon: const Icon(Icons.hub_outlined),
                        onPressed: widget.openDevice)
                  ])),
              if (searching)
                Padding(
                    padding: const EdgeInsets.fromLTRB(18, 0, 18, 6),
                    child: TextField(
                        controller: search,
                        autofocus: true,
                        onChanged: (_) => setState(() {}),
                        decoration: const InputDecoration(
                            hintText: '搜索任务名称、路径或引擎', isDense: true))),
              if (loading && !loaded) const LinearProgressIndicator(),
              if (error != null || operationError != null)
                Padding(
                    padding:
                        const EdgeInsets.symmetric(horizontal: 18, vertical: 6),
                    child: Row(children: [
                      Expanded(
                          child: Text(error ?? operationError!,
                              style: TextStyle(
                                  color: Theme.of(context).colorScheme.error))),
                      TextButton(
                          onPressed: () {
                            setState(() => operationError = null);
                            refresh();
                          },
                          child: const Text('重试'))
                    ])),
              Expanded(
                  child: RefreshIndicator(
                      onRefresh: () async {
                        await refresh();
                      },
                      child: ListView(
                          key: const PageStorageKey('task-scroll'),
                          physics: const AlwaysScrollableScrollPhysics(),
                          padding: const EdgeInsets.only(bottom: 90),
                          children: [
                            heading(
                                '运行中任务',
                                Icons.play_arrow,
                                active.length,
                                runningExpanded,
                                () => setState(
                                    () => runningExpanded = !runningExpanded)),
                            if (runningExpanded) ...[
                              if (loaded && active.isEmpty)
                                empty('没有运行中的脚本', '没有匹配的运行任务'),
                              ...active.map(row)
                            ],
                            heading(
                                '任务',
                                Icons.schedule,
                                pending.length,
                                scheduledExpanded,
                                () => setState(() =>
                                    scheduledExpanded = !scheduledExpanded)),
                            if (scheduledExpanded) ...[
                              if (loaded && pending.isEmpty)
                                empty('没有定时或意图任务', '没有匹配的计划任务'),
                              ...pending.map(row)
                            ]
                          ])))
            ]),
            floatingActionButton: Semantics(
                label: '停止全部脚本',
                button: true,
                enabled: loaded && error == null && !changing,
                onTap: loaded && error == null && !changing ? stopAll : null,
                child: ExcludeSemantics(
                    child: FloatingActionButton(
                        heroTag: 'task-stop-all',
                        tooltip: '停止全部脚本',
                        backgroundColor: const Color(0xff008f91),
                        onPressed:
                            loaded && error == null && !changing ? stopAll : null,
                        child: changing
                            ? const SizedBox(
                                width: 22,
                                height: 22,
                                child: CircularProgressIndicator(
                                    strokeWidth: 2, color: Colors.white))
                            : const Icon(Icons.close, color: Colors.white))))));
  }
}

class DeviceSettingsPage extends StatelessWidget {
  const DeviceSettingsPage({Key? key}) : super(key: key);
  @override
  Widget build(BuildContext context) => GlassBackground(
      child: Scaffold(
          appBar: AppBar(
              title: const Text('设备连接'),
              foregroundColor: Theme.of(context).colorScheme.onSurface,
              systemOverlayStyle: utilitySystemBars(context)),
          body: const DevicePage()));
}
