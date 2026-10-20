part of 'main.dart';

class GeneralSettingsPage extends StatefulWidget {
  final String root;
  final ValueChanged<bool> onTheme;
  const GeneralSettingsPage(
      {Key? key, required this.root, required this.onTheme})
      : super(key: key);
  @override
  State<GeneralSettingsPage> createState() => _GeneralSettingsPageState();
}

class _GeneralSettingsPageState extends State<GeneralSettingsPage>
    with WidgetsBindingObserver {
  Map<String, dynamic>? preferences;
  String? error, updating;
  String? configuredRoot;
  bool loading = false;
  // 「编辑器」分组：原生 Ace 工作区的真实字号（8–40，默认 14）。
  int editorTextSize = 14;
  // 「关于」分组：更新源为空串表示使用内置源。
  String updateSource = '';
  bool reloadQueued = false, foreground = true, routeVisible = true;
  static const numeric = [
    ['consoleMaxLogCount', '控制台日志条数', 256, 1000000, '条'],
    ['logMaxFileSize', '单个日志文件上限', 4, 1000000, 'KB'],
    ['logMaxBackups', '日志备份数量', 0, 100, '个'],
    ['consoleInitialReadFileSize', '打开控制台时读取日志', 4, 1000000, 'KB'],
  ];

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    load();
    // 字号与更新源独立读取：任一失败都不影响运行设置的加载 / 错误提示。
    loadEditorTextSize();
    loadUpdateSource();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    foreground = state == AppLifecycleState.resumed;
    if (foreground) {
      if (ModalRoute.of(context)?.isCurrent == false)
        reloadQueued = true;
      else
        load();
    }
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    routeVisible = ModalRoute.of(context)?.isCurrent != false;
    // Native settings can return while a Flutter dialog is still covering this
    // route. Flush that requested read after the route becomes visible again.
    if (reloadQueued && foreground && routeVisible) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) load();
      });
    }
  }

  Map<String, dynamic> checked(dynamic result) {
    if (result is! Map || result['stableMode'] is! bool) {
      throw StateError('运行设置格式异常');
    }
    final values = Map<String, dynamic>.from(result);
    for (final setting in numeric) {
      if (values[setting[0]] is! num) throw StateError('运行设置数值不可读');
    }
    return values;
  }

  Future<void> load() async {
    if (loading || updating != null || !foreground || !routeVisible) {
      reloadQueued = true;
      return;
    }
    do {
      reloadQueued = false;
      setState(() => loading = true);
      try {
        final values = checked(await LocalBridge.call('runtimePreferences'));
        final root = await LocalBridge.call('scriptsRoot');
        // 编辑器按这些键改变绘制，读到就要同步过去。
        EditorPreferences.instance.apply(values);
        // A resume that happened during this read requires a newer snapshot.
        if (mounted && !reloadQueued) {
          setState(() {
            preferences = values;
            if (root is Map && root['path'] is String)
              configuredRoot = root['path'] as String;
            error = null;
          });
        }
      } catch (e) {
        if (mounted && !reloadQueued) setState(() => error = e.toString());
      } finally {
        if (mounted) setState(() => loading = false);
      }
    } while (mounted &&
        reloadQueued &&
        foreground &&
        routeVisible &&
        updating == null);
  }

  Future<void> update(String key, dynamic value) async {
    if (loading || updating != null || preferences == null) return;
    setState(() => updating = key);
    try {
      final values = checked(await LocalBridge.call(
          'runtimePreferences', {'key': key, 'value': value}));
      if (values[key] != value) throw StateError('设置保存结果尚未确认');
      EditorPreferences.instance.apply(values);
      if (mounted) {
        setState(() {
          preferences = values;
          error = null;
        });
      }
    } catch (e) {
      if (mounted) setState(() => error = e.toString());
    } finally {
      if (mounted) {
        setState(() => updating = null);
        if (reloadQueued && foreground) load();
      }
    }
  }

  Future<void> number(List<Object> setting) async {
    final key = setting[0] as String;
    final value = await showDialog<int>(
        context: context,
        builder: (_) => _RuntimeNumberDialog(
            title: setting[1] as String,
            current: (preferences![key] as num).toInt(),
            minimum: setting[2] as int,
            maximum: setting[3] as int,
            unit: setting[4] as String));
    if (mounted && value != null) await update(key, value);
  }

  /// 原生 Ace 工作区当前字号。读不到时保留当前显示（默认 14），不影响其它分组。
  Future<void> loadEditorTextSize() async {
    try {
      final reply = await LocalBridge.call('editorSettings');
      final size = reply is Map ? reply['textSize'] : null;
      if (size is num && size >= 8 && size <= 40 && mounted) {
        setState(() => editorTextSize = size.round());
      }
    } catch (_) {
      // 桥尚未接入或读取失败时沿用默认值。
    }
  }

  Future<void> editEditorTextSize() async {
    if (loading || updating != null) return;
    final value = await showDialog<int>(
        context: context,
        builder: (_) => _RuntimeNumberDialog(
            title: '编辑器字号',
            current: editorTextSize,
            minimum: 8,
            maximum: 40,
            unit: 'sp'));
    if (!mounted || value == null || updating != null) return;
    setState(() => updating = 'editorSettings');
    try {
      final reply =
          await LocalBridge.call('editorSettings', {'textSize': value});
      final size = reply is Map ? reply['textSize'] : null;
      if (size is! num || size < 8 || size > 40) {
        throw StateError('编辑器字号保存结果未确认');
      }
      if (mounted) setState(() => editorTextSize = size.round());
    } catch (e) {
      if (mounted) message(context, '编辑器字号未保存：$e');
    } finally {
      if (mounted) setState(() => updating = null);
    }
  }

  /// 更新源空串表示内置源，所以读失败时也保持空串（显示“内置源”）。
  Future<void> loadUpdateSource() async {
    try {
      final reply = await LocalBridge.call('updateSource');
      final url = reply is Map ? reply['url'] : null;
      if (url is String && mounted) setState(() => updateSource = url);
    } catch (_) {
      // 桥尚未接入或读取失败时按内置源展示。
    }
  }

  Future<void> editUpdateSource() async {
    if (loading || updating != null) return;
    final controller = TextEditingController(text: updateSource);
    final value = await showDialog<String>(
        context: context,
        builder: (dialog) => AlertDialog(
                title: const Text('更新源'),
                content: TextField(
                    controller: controller,
                    autofocus: true,
                    decoration: const InputDecoration(
                        hintText: '留空表示使用内置更新源', helperText: '填写更新清单所在地址')),
                actions: [
                  TextButton(
                      onPressed: () => Navigator.pop(dialog),
                      child: const Text('取消')),
                  TextButton(
                      onPressed: () =>
                          Navigator.pop(dialog, controller.text.trim()),
                      child: const Text('保存'))
                ]));
    // Dispose after the dialog's exit animation has released its TextField.
    await Future<void>.delayed(const Duration(milliseconds: 250));
    controller.dispose();
    if (!mounted || value == null || value == updateSource) return;
    setState(() => updating = 'updateSource');
    try {
      final reply = await LocalBridge.call('updateSource', {'url': value});
      final url = reply is Map ? reply['url'] : null;
      if (url is! String) throw StateError('更新源保存结果未确认');
      if (mounted) setState(() => updateSource = url);
    } catch (e) {
      if (mounted) message(context, '更新源未保存：$e');
    } finally {
      if (mounted) setState(() => updating = null);
    }
  }

  /// 关于：对齐高级版「关于项目与开发者」——独立页面（图标 / 名称 / 版本 / 作者 / 最近提交 / 链接）。
  void openAboutPage() {
    Navigator.of(context).push(
        MaterialPageRoute<void>(builder: (_) => const GlassAboutPage()));
  }

  /// 开源许可：Flutter 自带 LicensePage，读取 main.dart 里注册的 LicenseRegistry。
  void openLicensesPage() {
    Navigator.of(context).push(MaterialPageRoute<void>(
        builder: (_) => const LicensePage(
            applicationName: 'AI.js Pro',
            applicationLegalese: 'AI.js Pro · 本地脚本运行器')));
  }

  /// 检查更新 / 问题反馈：仍交给原生实现（更新检查/下载/安装链路在 Java 侧）。
  Future<void> aboutAction(String method, String label) async {
    if (loading || updating != null) return;
    try {
      final result = await LocalBridge.call(method);
      if (result == false) throw StateError('原生功能未确认执行');
    } catch (e) {
      if (mounted) message(context, '$label失败：$e');
    }
  }

  Future<void> editScriptRoot() async {
    if (updating != null) return;
    final controller =
        TextEditingController(text: configuredRoot ?? widget.root);
    final value = await showDialog<String>(
        context: context,
        builder: (dialogContext) => AlertDialog(
              title: const Text('脚本目录'),
              content: TextField(
                controller: controller,
                autofocus: true,
                decoration: const InputDecoration(
                    labelText: '目录路径', helperText: '填写目录路径，新目录会自动创建'),
              ),
              actions: [
                TextButton(
                    onPressed: () => Navigator.pop(dialogContext),
                    child: const Text('取消')),
                TextButton(
                    onPressed: () =>
                        Navigator.pop(dialogContext, controller.text.trim()),
                    child: const Text('保存')),
              ],
            ));
    // Dispose after the dialog's exit animation has released its TextField.
    await Future<void>.delayed(const Duration(milliseconds: 250));
    controller.dispose();
    if (!mounted || value == null || value.isEmpty) return;
    setState(() => updating = 'scriptsRoot');
    try {
      final result = await LocalBridge.call('scriptsRoot', {'path': value});
      if (result is! Map || result['path'] is! String)
        throw StateError('目录保存结果未确认');
      if (mounted) setState(() => configuredRoot = result['path'] as String);
    } catch (e) {
      if (mounted) message(context, '脚本目录未修改：$e');
    } finally {
      if (mounted) setState(() => updating = null);
    }
  }

  void open(Widget page) =>
      Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => page));

  /// 与原版设置页同名的项。键名是保留运行库自己偏好存储里的真实键
  /// （用 `__pref_probe` 从 Pref.get() 导出确认），所以原厂引擎读得到，
  /// 不只是改我们这套界面。
  static const List<List<String>> runFlags = [
    ['key_use_volume_control_running', '音量上键停止所有脚本', '按音量上键停止全部运行中的脚本'],
    ['key_localize_message', '中文报错信息', '脚本报错与提示改用中文'],
    ['key_guard_mode', '保护模式', '在本软件界面无法运行自动操作命令以免误触'],
  ];
  Widget flagTile(List<String> item) {
    final key = item[0];
    return SwitchListTile(
        key: ValueKey('pref-$key'),
        title: Text(item[1]),
        subtitle: Text(item[2]),
        value: preferences?[key] == true,
        onChanged: loading || updating != null || preferences == null
            ? null
            : (value) => update(key, value));
  }

  Future<void> editText(String key, String title) async {
    final controller =
        TextEditingController(text: preferences?[key] as String? ?? '');
    final value = await showDialog<String>(
        context: context,
        builder: (dialog) => AlertDialog(
                title: Text(title),
                content: TextField(
                    controller: controller,
                    autofocus: true,
                    decoration:
                        const InputDecoration(hintText: '留空表示使用运行库默认值')),
                actions: [
                  TextButton(
                      onPressed: () => Navigator.pop(dialog),
                      child: const Text('取消')),
                  TextButton(
                      onPressed: () =>
                          Navigator.pop(dialog, controller.text.trim()),
                      child: const Text('保存'))
                ]));
    if (value == null || !mounted) return;
    await update(key, value);
  }

  // 对齐 AI.js Pro：设置页行全部走 Miuix 库默认 ——
  // SmallTitle `14.sp` Medium（原为 12）、行标题 `17.sp` Medium、摘要 `14.sp`。
  Widget heading(String value) => Padding(
      padding: const EdgeInsets.fromLTRB(12, 18, 12, 8),
      child: Text(value,
          style: TextStyle(
              fontSize: 14,
              fontWeight: FontWeight.w500,
              color: Theme.of(context).colorScheme.onSurface.withOpacity(.6))));

  @override
  Widget build(BuildContext context) => GlassBackground(
      child: Scaffold(
          appBar: AppBar(
              systemOverlayStyle: utilitySystemBars(context),
              foregroundColor: Theme.of(context).colorScheme.onSurface,
              title: const Text('设置'),
              actions: [
                ActionIcon(
                    tooltip: '刷新运行设置',
                    icon: const Icon(Icons.refresh),
                    onPressed: loading || updating != null ? null : load)
              ]),
          body: Column(children: [
            if (error != null)
              Padding(
                  padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
                  child: Row(children: [
                    Icon(Icons.error_outline,
                        size: 18, color: Theme.of(context).colorScheme.error),
                    const SizedBox(width: 8),
                    Expanded(
                        child: Text(error!,
                            style: TextStyle(
                                fontSize: 12,
                                color: Theme.of(context).colorScheme.error))),
                    TextButton(
                        onPressed: loading || updating != null ? null : load,
                        child: const Text('重试')),
                  ])),
            Expanded(
                // 用 SingleChildScrollView 而不是 ListView：ListView 只给可见区域
                // 建元素，设置项变多以后下面的分组在测试与无障碍节点里都不存在，
                // ensureVisible / 节点抓取会直接找不到。
                child: SingleChildScrollView(
                    padding: const EdgeInsets.fromLTRB(20, 0, 20, 24),
                    child: Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          heading('工作空间'),
                          GlassSurface(
                              blur: false,
                              padding: const EdgeInsets.all(14),
                              child: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    const Text('脚本目录',
                                        style: TextStyle(fontSize: 17)),
                                    const SizedBox(height: 8),
                                    SelectableText(
                                        configuredRoot ?? widget.root,
                                        style: const TextStyle(
                                            fontSize: 14, height: 1.5)),
                                    TextButton.icon(
                                        onPressed: updating == null
                                            ? editScriptRoot
                                            : null,
                                        icon: const Icon(Icons.folder_open),
                                        label: const Text('更改目录')),
                                  ])),
                          heading('外观'),
                          GlassSurface(
                              blur: false,
                              padding: EdgeInsets.zero,
                              child: SwitchListTile(
                                  title: const Text('深色主题'),
                                  secondary:
                                      const Icon(Icons.dark_mode_outlined),
                                  value: Theme.of(context).brightness ==
                                      Brightness.dark,
                                  onChanged:
                                      ThemePreferenceScope.isSaving(context)
                                          ? null
                                          : (value) {
                                              try {
                                                widget.onTheme(value);
                                              } catch (e) {
                                                message(context, e.toString());
                                              }
                                            })),
                          heading('调试与脚本运行'),
                          if (preferences != null)
                            GlassSurface(
                                blur: false,
                                padding: EdgeInsets.zero,
                                child: Column(
                                    children: runFlags.map(flagTile).toList())),
                          heading('Node.js'),
                          if (preferences != null)
                            GlassSurface(
                                blur: false,
                                padding: EdgeInsets.zero,
                                child: ListTile(
                                    key:
                                        const ValueKey('pref-key_npm_registry'),
                                    title: const Text('npm 镜像'),
                                    subtitle: Text(
                                        preferences!['key_npm_registry']
                                                as String? ??
                                            '默认 registry.npmjs.org'),
                                    trailing: updating == 'key_npm_registry'
                                        ? const SizedBox(
                                            width: 20,
                                            height: 20,
                                            child: CircularProgressIndicator(
                                                strokeWidth: 2))
                                        : const Icon(Icons.chevron_right),
                                    onTap: loading || updating != null
                                        ? null
                                        : () => editText(
                                            'key_npm_registry', 'npm 镜像'))),
                          heading('编辑器'),
                          GlassSurface(
                              blur: false,
                              padding: EdgeInsets.zero,
                              child: ListTile(
                                  key: const ValueKey(
                                      'settings-editor-text-size'),
                                  leading: const Icon(Icons.format_size),
                                  title: const Text('编辑器字号'),
                                  subtitle: Text('$editorTextSize sp'),
                                  trailing: updating == 'editorSettings'
                                      ? const SizedBox(
                                          width: 20,
                                          height: 20,
                                          child: CircularProgressIndicator(
                                              strokeWidth: 2))
                                      : const Icon(Icons.chevron_right),
                                  onTap: loading || updating != null
                                      ? null
                                      : editEditorTextSize)),
                          heading('运行与日志'),
                          if (loading || updating != null)
                            const LinearProgressIndicator(minHeight: 2),
                          if (preferences == null)
                            GlassSurface(
                                blur: false,
                                child: Column(children: [
                                  Text(loading ? '正在读取运行设置…' : '运行设置暂不可读'),
                                  if (!loading)
                                    TextButton(
                                        onPressed: load,
                                        child: const Text('重试'))
                                ]))
                          else
                            GlassSurface(
                                blur: false,
                                padding: EdgeInsets.zero,
                                child: Column(children: [
                                  SwitchListTile(
                                      key:
                                          const ValueKey('runtime-stable-mode'),
                                      title: const Text('稳定模式'),
                                      subtitle: const Text('使用原脚本引擎的稳定运行设置'),
                                      value: preferences!['stableMode'] == true,
                                      onChanged: loading || updating != null
                                          ? null
                                          : (value) =>
                                              update('stableMode', value)),
                                  for (final setting in numeric)
                                    ListTile(
                                        key: ValueKey('runtime-${setting[0]}'),
                                        title: Text(setting[1] as String),
                                        subtitle: Text(
                                            '${preferences![setting[0]]} ${setting[4]}'),
                                        trailing: updating == setting[0]
                                            ? const SizedBox(
                                                width: 20,
                                                height: 20,
                                                child:
                                                    CircularProgressIndicator(
                                                        strokeWidth: 2))
                                            : const Icon(Icons.chevron_right),
                                        onTap: loading || updating != null
                                            ? null
                                            : () => number(setting)),
                                ])),
                          const SizedBox(height: 8),
                          GlassSurface(
                              blur: false,
                              padding: EdgeInsets.zero,
                              child: Column(children: [
                                ListTile(
                                    leading:
                                        const Icon(Icons.receipt_long_outlined),
                                    title: const Text('运行日志'),
                                    subtitle: const Text('实时统一流 · 等级过滤/搜索'),
                                    trailing: const Icon(Icons.chevron_right),
                                    // 日志统一走 Flutter「运行日志」页（LiveLogs 统一流）；
                                    // 单实例：已有日志页就回到它，连点不叠加。
                                    onTap: () => pushOnce(context, 'log',
                                        (_) => const LogPage())),
                                ListTile(
                                    leading: const Icon(
                                        Icons.restore_from_trash_outlined),
                                    title: const Text('回收站'),
                                    subtitle: const Text('恢复移入回收站的脚本和目录'),
                                    trailing: const Icon(Icons.chevron_right),
                                    onTap: () => open(const TrashPage())),
                              ])),
                          heading('关于'),
                          GlassSurface(
                              blur: false,
                              padding: EdgeInsets.zero,
                              child: Column(children: [
                                ListTile(
                                    key: const ValueKey('about-check-update'),
                                    leading:
                                        const Icon(Icons.system_update_alt),
                                    title: const Text('检查更新'),
                                    trailing: const Icon(Icons.chevron_right),
                                    onTap: () =>
                                        aboutAction('checkUpdate', '检查更新')),
                                ListTile(
                                    key: const ValueKey('about-update-source'),
                                    leading: const Icon(Icons.link),
                                    title: const Text('更新源'),
                                    subtitle: Text(updateSource.isEmpty
                                        ? '内置源'
                                        : updateSource),
                                    trailing: updating == 'updateSource'
                                        ? const SizedBox(
                                            width: 20,
                                            height: 20,
                                            child: CircularProgressIndicator(
                                                strokeWidth: 2))
                                        : const Icon(Icons.chevron_right),
                                    onTap: loading || updating != null
                                        ? null
                                        : editUpdateSource),
                                ListTile(
                                    key: const ValueKey('about-issue-report'),
                                    leading:
                                        const Icon(Icons.feedback_outlined),
                                    title: const Text('问题反馈'),
                                    trailing:
                                        const Icon(Icons.open_in_new, size: 20),
                                    onTap: () => aboutAction(
                                        'openIssueReport', '打开问题反馈')),
                                ListTile(
                                    key: const ValueKey('about-app'),
                                    leading: const Icon(Icons.info_outline),
                                    title: const Text('关于'),
                                    trailing: const Icon(Icons.chevron_right),
                                    onTap: openAboutPage),
                                ListTile(
                                    key: const ValueKey('about-licenses'),
                                    leading:
                                        const Icon(Icons.description_outlined),
                                    title: const Text('开源许可'),
                                    trailing: const Icon(Icons.chevron_right),
                                    onTap: openLicensesPage),
                              ])),
                        ])))
          ])));
}

class _RuntimeNumberDialog extends StatefulWidget {
  final String title, unit;
  final int current, minimum, maximum;
  const _RuntimeNumberDialog(
      {required this.title,
      required this.current,
      required this.minimum,
      required this.maximum,
      required this.unit});
  @override
  State<_RuntimeNumberDialog> createState() => _RuntimeNumberDialogState();
}

class _RuntimeNumberDialogState extends State<_RuntimeNumberDialog> {
  late final input = TextEditingController(text: '${widget.current}');
  String? error;
  @override
  void dispose() {
    input.dispose();
    super.dispose();
  }

  void submit() {
    final value = int.tryParse(input.text);
    if (value == null || value < widget.minimum || value > widget.maximum) {
      setState(() =>
          error = '请输入 ${widget.minimum}–${widget.maximum} ${widget.unit}');
      return;
    }
    Navigator.of(context).pop(value);
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
          title: Text(widget.title),
          content: TextField(
              controller: input,
              autofocus: true,
              keyboardType: TextInputType.number,
              inputFormatters: [FilteringTextInputFormatter.digitsOnly],
              decoration: InputDecoration(
                  suffixText: widget.unit,
                  helperText: '${widget.minimum}–${widget.maximum}',
                  errorText: error),
              onSubmitted: (_) => submit()),
          actions: [
            TextButton(
                onPressed: () => Navigator.pop(context),
                child: const Text('返回')),
            TextButton(onPressed: submit, child: const Text('保存')),
          ]);
}

class TrashPage extends StatefulWidget {
  const TrashPage({Key? key}) : super(key: key);
  @override
  State<TrashPage> createState() => _TrashPageState();
}

class _TrashPageState extends State<TrashPage> {
  List<Map<String, dynamic>>? records;
  String? error, restoring;
  bool loading = false;
  final restoredTokens = <String>{};
  @override
  void initState() {
    super.initState();
    load();
  }

  Future<void> load({bool afterRestore = false}) async {
    // A pull gesture during restoration must not start a pre-restoration read
    // that can later put the restored record back into this page.
    if (loading || (restoring != null && !afterRestore)) return;
    setState(() => loading = true);
    try {
      final result = await LocalBridge.call('trashList');
      if (result is! List || result.any((item) => item is! Map)) {
        throw StateError('回收记录格式异常');
      }
      final values = result
          .map((item) => Map<String, dynamic>.from(item))
          .where((item) => !restoredTokens.contains(item['token']?.toString()))
          .toList();
      if (mounted) {
        setState(() {
          records = values;
          error = null;
        });
      }
    } catch (e) {
      if (mounted) setState(() => error = e.toString());
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  Future<void> restore(Map<String, dynamic> record) async {
    if (restoring != null || loading) return;
    final confirmed = await showDialog<bool>(
        context: context,
        builder: (_) => AlertDialog(
                title: Text('恢复 ${record['name']}？'),
                content: SingleChildScrollView(
                    child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        mainAxisSize: MainAxisSize.min,
                        children: [
                      const Text('将恢复到原位置；已有同名文件时保留回收记录。'),
                      const SizedBox(height: 12),
                      SelectableText(record['originalPath']?.toString() ?? ''),
                    ])),
                actions: [
                  TextButton(
                      onPressed: () => Navigator.pop(context, false),
                      child: const Text('返回')),
                  TextButton(
                      onPressed: () => Navigator.pop(context, true),
                      child: const Text('恢复')),
                ]));
    if (!mounted || confirmed != true) return;
    if (loading || restoring != null) {
      message(context, '正在更新回收记录，请稍后重试');
      return;
    }
    setState(() => restoring = record['token']?.toString());
    try {
      final result =
          await LocalBridge.call('restoreTrash', {'token': record['token']});
      if (result is! Map ||
          result['path'] is! String ||
          result['path'].isEmpty) {
        throw StateError('恢复未返回文件位置');
      }
      if (!mounted) return;
      // The native restore reply confirms this immutable trash token has been
      // restored. Keep that fact even if the subsequent list read fails.
      final token = record['token']?.toString();
      if (token != null) {
        setState(() {
          restoredTokens.add(token);
          records?.removeWhere((item) => item['token']?.toString() == token);
        });
      }
      await load(afterRestore: true);
      if (mounted) message(context, '已恢复\n${result['path']}');
    } catch (e) {
      if (mounted) setState(() => error = e.toString());
    } finally {
      if (mounted) setState(() => restoring = null);
    }
  }

  String time(Map record) {
    final value = record['deletedAt'];
    if (value is! num) return '';
    final date = DateTime.fromMillisecondsSinceEpoch(value.toInt());
    String pad(int value) => '$value'.padLeft(2, '0');
    return '${date.year}-${pad(date.month)}-${pad(date.day)} ${pad(date.hour)}:${pad(date.minute)}';
  }

  @override
  Widget build(BuildContext context) => GlassBackground(
      child: Scaffold(
          appBar: AppBar(
              systemOverlayStyle: utilitySystemBars(context),
              foregroundColor: Theme.of(context).colorScheme.onSurface,
              title: const Text('回收站'),
              actions: [
                ActionIcon(
                    tooltip: '刷新回收站',
                    icon: const Icon(Icons.refresh),
                    onPressed: loading || restoring != null ? null : load)
              ]),
          body: Column(children: [
            if (loading) const LinearProgressIndicator(minHeight: 2),
            if (error != null)
              Padding(
                  padding: const EdgeInsets.all(12),
                  child: Text(error!,
                      style: TextStyle(
                          color: Theme.of(context).colorScheme.error))),
            Expanded(
                child: records == null && error == null
                    ? const Center(child: CircularProgressIndicator())
                    : RefreshIndicator(
                        onRefresh: load,
                        child: ListView.builder(
                            physics: const AlwaysScrollableScrollPhysics(),
                            padding: const EdgeInsets.fromLTRB(10, 8, 10, 24),
                            itemCount:
                                (records ?? []).isEmpty ? 1 : records!.length,
                            itemBuilder: (_, index) {
                              if ((records ?? []).isEmpty) {
                                return Padding(
                                    padding: const EdgeInsets.all(32),
                                    child: Column(children: [
                                      const Icon(
                                          Icons.restore_from_trash_outlined,
                                          size: 44),
                                      const SizedBox(height: 12),
                                      Text(
                                          error == null ? '回收站为空' : '回收记录暂不可读'),
                                      if (error != null)
                                        TextButton(
                                            onPressed: load,
                                            child: const Text('重试')),
                                    ]));
                              }
                              final record = records![index];
                              return Padding(
                                  padding: const EdgeInsets.only(bottom: 8),
                                  child: GlassSurface(
                                      blur: false,
                                      padding: EdgeInsets.zero,
                                      child: ListTile(
                                          leading: Icon(
                                              record['directory'] == true
                                                  ? Icons.folder_outlined
                                                  : Icons.description_outlined),
                                          title: Text(
                                              record['name']?.toString() ??
                                                  '回收文件'),
                                          subtitle: Text(
                                              '${record['originalPath'] ?? ''}\n${time(record)}',
                                              maxLines: 3,
                                              overflow: TextOverflow.ellipsis),
                                          trailing: restoring == record['token']
                                              ? const SizedBox(
                                                  width: 20,
                                                  height: 20,
                                                  child:
                                                      CircularProgressIndicator(
                                                          strokeWidth: 2))
                                              : IconButton(
                                                  tooltip: '恢复 ${record['name']}',
                                                  icon: const Icon(Icons.restore),
                                                  onPressed: loading || restoring != null ? null : () => restore(record)),
                                          onTap: loading || restoring != null ? null : () => restore(record))));
                            })))
          ])));
}




/// 关于页（对齐高级版「关于项目与开发者」）：图标、名称/版本、作者/最近提交与链接。
class GlassAboutPage extends StatefulWidget {
  const GlassAboutPage({super.key});

  @override
  State<GlassAboutPage> createState() => _GlassAboutPageState();
}

class _GlassAboutPageState extends State<GlassAboutPage> {
  static const String _repoUrl = 'https://github.com/Jdkshen/ai.js-pro';
  static const String _releasesUrl = 'https://github.com/Jdkshen/ai.js-pro/releases';
  static const String _licenseUrl =
      'https://github.com/Jdkshen/ai.js-pro/blob/main/LICENSE.md';
  Map<String, dynamic>? _info;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    Map<String, dynamic>? info;
    try {
      final result = await LocalBridge.call('aboutInfo');
      if (result is Map) {
        info = result.map((key, value) => MapEntry('$key', value));
      }
    } catch (_) {
      // 桥不可用时仍展示静态关于页。
    }
    if (mounted) setState(() => _info = info);
  }

  String _text(String key, String fallback) {
    final value = _info?[key];
    final text = value == null ? '' : '$value'.trim();
    return (text.isEmpty || text == 'unknown') ? fallback : text;
  }

  Future<void> _openUrl(String url) async {
    try {
      await LocalBridge.call('openUrl', {'url': url});
    } catch (e) {
      if (mounted) message(context, '打开链接失败：$e');
    }
  }

  Future<void> _issueReport() async {
    try {
      await LocalBridge.call('openIssueReport');
    } catch (e) {
      if (mounted) message(context, '$e');
    }
  }

  void _showPrivacy() {
    showDialog<void>(
        context: context,
        builder: (dialogContext) => AlertDialog(
            title: const Text('隐私政策'),
            content: const Text('本应用不收集任何个人数据。脚本、日志、配置与运行记录都保存在本机；'
                '无障碍、截图、悬浮窗等权限仅在系统授权后由本机使用。'
                'MCP 与电脑连接只在你主动开启时监听本机/局域网；服务完全免令牌，客户端只需填写地址。'),
            actions: [
              TextButton(
                  onPressed: () => Navigator.of(dialogContext).pop(),
                  child: const Text('关闭'))
            ]));
  }

  Widget _link(String label, IconData icon, VoidCallback onTap) => ListTile(
        leading: Icon(icon),
        title: Text(label),
        onTap: onTap,
      );

  @override
  Widget build(BuildContext context) {
    final muted = Theme.of(context).colorScheme.onSurface.withOpacity(.6);
    final versionName = _text('versionName', '');
    final versionCode = _text('versionCode', '');
    final versionLabel = versionName.isEmpty
        ? '版本未知'
        : (versionCode.isEmpty ? versionName : '$versionName ($versionCode)');
    final author = _text('author', 'Jdkshen');
    final commit = _text('gitCommit', '');
    final commitDate = _text('gitDate', '');
    final commitSubject = _text('gitSubject', '');
    final commitLine = [
      '最近提交：' + (commit.isEmpty ? '未知' : commit),
      if (commitDate.isNotEmpty) commitDate,
      if (commitSubject.isNotEmpty) commitSubject,
    ].join(' · ');

    return GlassBackground(
        child: Scaffold(
            appBar: AppBar(
                systemOverlayStyle: utilitySystemBars(context),
                foregroundColor: Theme.of(context).colorScheme.onSurface,
                title: const Text('关于')),
            body: LayoutBuilder(
                builder: (context, constraints) => SingleChildScrollView(
                    child: ConstrainedBox(
                        constraints: BoxConstraints(minHeight: constraints.maxHeight),
                        child: Padding(
                            padding: const EdgeInsets.fromLTRB(20, 8, 20, 16),
                            child: Column(
                                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                                children: [
                                  Column(
                                      crossAxisAlignment:
                                          CrossAxisAlignment.stretch,
                                      children: [
                                        const SizedBox(height: 16),
                                        Center(
                                            child: ClipRRect(
                                                borderRadius:
                                                    BorderRadius.circular(28),
                                                child: Image.asset(
                                                    'assets/brand/icon_ai_js_pro.png',
                                                    width: 104,
                                                    height: 104,
                                                    fit: BoxFit.cover))),
                                        const SizedBox(height: 16),
                                        const Text('AI.js Pro',
                                            textAlign: TextAlign.center,
                                            style: TextStyle(
                                                fontSize: 24,
                                                fontWeight: FontWeight.w600)),
                                        const SizedBox(height: 6),
                                        Text('自动化工作流 JavaScript IDE',
                                            textAlign: TextAlign.center,
                                            style: TextStyle(
                                                fontSize: 14, color: muted)),
                                        const SizedBox(height: 6),
                                        Text(versionLabel,
                                            textAlign: TextAlign.center,
                                            style: TextStyle(
                                                fontSize: 13, color: muted)),
                                        const SizedBox(height: 12),
                                        Text('作者：$author',
                                            textAlign: TextAlign.center,
                                            style: TextStyle(
                                                fontSize: 12, color: muted)),
                                        const SizedBox(height: 2),
                                        Text(commitLine,
                                            textAlign: TextAlign.center,
                                            maxLines: 1,
                                            overflow: TextOverflow.ellipsis,
                                            style: TextStyle(
                                                fontSize: 12, color: muted)),
                                        const SizedBox(height: 24),
                                        GlassSurface(
                                            blur: false,
                                            padding: EdgeInsets.zero,
                                            child: Column(children: [
                                              _link('Github 仓库', Icons.code,
                                                  () => _openUrl(_repoUrl)),
                                              _link(
                                                  '更新日志',
                                                  Icons.history,
                                                  () => _openUrl(
                                                      _releasesUrl)),
                                              _link(
                                                  '问题反馈 / 联系我们',
                                                  Icons.bug_report_outlined,
                                                  _issueReport),
                                              _link(
                                                  '开放源代码许可',
                                                  Icons.article_outlined,
                                                  () => _openUrl(
                                                      _licenseUrl)),
                                            ])),
                                      ]),
                                  Row(
                                      mainAxisAlignment:
                                          MainAxisAlignment.center,
                                      children: [
                                        TextButton(
                                            onPressed: () =>
                                                _openUrl(_licenseUrl),
                                            child: const Text(
                                                '《软件许可与服务协议》',
                                                style:
                                                    TextStyle(fontSize: 12))),
                                        TextButton(
                                            onPressed: _showPrivacy,
                                            child: const Text('《隐私政策》',
                                                style:
                                                    TextStyle(fontSize: 12))),
                                      ]),
                                ])))))));
  }
}
