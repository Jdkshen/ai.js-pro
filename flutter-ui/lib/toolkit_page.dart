part of 'main.dart';

class ToolkitPage extends StatefulWidget {
  const ToolkitPage({Key? key}) : super(key: key);
  @override
  State<ToolkitPage> createState() => _ToolkitPageState();
}

class _ToolkitPageState extends State<ToolkitPage> {
  final path = TextEditingController();
  Map<String, dynamic>? status, workspace;
  List<dynamic> apks = [];
  Map<String, dynamic>? result;
  String? error, session;
  bool busy = false;
  @override
  void initState() { super.initState(); refresh(); }
  @override
  void dispose() { path.dispose(); super.dispose(); }
  Future<Map<String, dynamic>> call(String name, [Map<String, dynamic> args = const {}]) async {
    final reply = Map<String, dynamic>.from(await LocalBridge.call('toolkitCall', {'name': name, 'arguments': args}));
    if (reply['ok'] != true) throw StateError(reply['error']?['message']?.toString() ?? '工具调用失败');
    return reply;
  }
  Future<void> work(Future<void> Function() action) async {
    if (busy) return;
    setState(() { busy = true; error = null; });
    try { await action(); } catch (e) { if (mounted) setState(() => error = e.toString().replaceFirst('Bad state: ', '')); }
    finally { if (mounted) setState(() => busy = false); }
  }
  Future<void> refresh() => work(() async {
    final info = await call('toolkit_status');
    final files = await call('apk_list_available_apks', {'prefix': '', 'limit': 50});
    if (!mounted) return;
    setState(() { status = Map<String, dynamic>.from(info['data']); apks = files['data']['items'] as List<dynamic>? ?? []; });
  });
  Future<void> open(String value) => work(() async {
    final opened = await call('apk_workspace_open', {'path': value, 'temporary': true});
    if (!mounted) return;
    setState(() { workspace = Map<String, dynamic>.from(opened['data']); session = null; result = opened; });
  });
  Map<String, dynamic> get scope => {'workspaceId': workspace!['workspaceId'], 'editSessionId': session ?? ''};
  Future<void> inspect(String view) => work(() async {
    final reply = view == 'manifest'
      ? await call('apk_read_text', {...scope, 'locator': 'zip_entry:AndroidManifest.xml', 'limit': 1000, 'maxChars': 32000, 'startLine': 0, 'startColumn': 0})
      : await call('apk_list', {...scope, 'view': view, 'prefix': '', 'limit': 100});
    if (mounted) setState(() => result = reply);
  });
  Future<void> nextPage() => work(() async {
    final cursor = result?['data']?['nextCursor']?.toString() ?? '';
    if (cursor.isEmpty) return;
    final reply = await call('apk_continue', {...scope, 'nextCursor': cursor, 'limit': 100});
    if (mounted) setState(() => result = reply);
  });
  Future<void> createSession() => work(() async {
    final reply = await call('apk_session_create', {'workspaceId': workspace!['workspaceId']});
    if (mounted) setState(() { session = reply['data']['editSessionId'].toString(); result = reply; });
  });
  Future<void> closeWorkspace() => work(() async {
    await call('apk_workspace_delete', {'workspaceId': workspace!['workspaceId']});
    if (mounted) setState(() { workspace = null; session = null; result = null; });
  });
  Future<void> utility(String name, [Map<String, dynamic> args = const {}]) => work(() async {
    final reply = await call(name, args);
    if (mounted) setState(() => result = reply);
  });
  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final data = result?['data'];
    final isGuide = data is Map && data['contentFormat'] == 'markdown';
    final text = result == null ? '' : (isGuide ? data['content']?.toString() : data?['text']?.toString()) ?? const JsonEncoder.withIndent('  ').convert(data);
    return AnnotatedRegion<SystemUiOverlayStyle>(value: utilitySystemBars(context), child: Scaffold(
      appBar: AppBar(title: const Text('文件与 APK 工具'), actions: [IconButton(tooltip: '刷新', onPressed: busy ? null : refresh, icon: const Icon(Icons.refresh))]),
      body: ListView(padding: const EdgeInsets.all(16), children: [
        Card(child: Padding(padding: const EdgeInsets.all(16), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(isGuide ? '逆向调用指南' : '本机工具箱', style: Theme.of(context).textTheme.titleLarge), const SizedBox(height: 8),
          if (!isGuide) ...[
            const Text('文件、ZIP、APK、DEX、资源和原生库。可直接使用，也可通过 MCP 调用。'), const SizedBox(height: 12),
            Wrap(spacing: 8, runSpacing: 6, children: ['文件', 'ZIP', 'APK', 'DEX', '资源', 'ELF'].map((name) => Chip(label: Text(name), visualDensity: VisualDensity.compact)).toList()),
            if (status != null) ...[const SizedBox(height: 8), Text('操作目录：${status!['homePath']}', style: Theme.of(context).textTheme.bodySmall)],
          ],
          const SizedBox(height: 8),
          Wrap(spacing: 8, children: [
            OutlinedButton.icon(onPressed: busy ? null : () => utility('reverse_guide', {'topic': 'index'}), icon: const Icon(Icons.menu_book_outlined), label: const Text('逆向调用指南')),
            TextButton(onPressed: busy ? null : () => utility('reverse_capabilities'), child: const Text('组件与能力')),
            if (isGuide) ...[
              PopupMenuButton<String>(tooltip: '选择指南章节', onSelected: (topic) => utility('reverse_guide', {'topic': topic}), enabled: !busy,
                itemBuilder: (_) => [for (final raw in data['sections'] as List<dynamic>? ?? []) PopupMenuItem<String>(value: raw['id'].toString(), child: Text(raw['title'].toString()))],
                child: const Padding(padding: EdgeInsets.all(12), child: Text('选择章节'))),
              TextButton(onPressed: busy ? null : () => setState(() => result = null), child: const Text('关闭指南')),
            ],
          ]),
        ]))),
        if (!isGuide) ...[const SizedBox(height: 12),
        TextField(controller: path, enabled: !busy, decoration: const InputDecoration(labelText: 'APK 或 ZIP 路径', hintText: '操作目录中的文件', border: OutlineInputBorder()), onChanged: (_) => setState(() {}), onSubmitted: busy ? null : (value) => open(value.trim())),
        const SizedBox(height: 8),
        Wrap(spacing: 8, children: [FilledButton.icon(onPressed: busy || path.text.trim().isEmpty ? null : () => open(path.text.trim()), icon: const Icon(Icons.folder_open), label: const Text('打开 APK')),
          TextButton(onPressed: busy ? null : () => work(() async { final value = await call('file_list', {'path': '', 'cursor': '', 'limit': 100}); if(mounted) setState(() => result = value); }), child: const Text('浏览文件')),
          TextButton(onPressed: busy || path.text.trim().isEmpty ? null : () => work(() async { final value = await call('zip_list', {'path': path.text.trim(), 'entryPath': '', 'recursive': false, 'simple': false, 'cursor': '', 'limit': 100}); if(mounted) setState(() => result = value); }), child: const Text('查看 ZIP'))])],
        if (busy) const Padding(padding: EdgeInsets.symmetric(vertical: 12), child: LinearProgressIndicator()),
        if (error != null) Padding(padding: const EdgeInsets.symmetric(vertical: 12), child: SelectableText(error!, style: TextStyle(color: colors.error))),
        if (!isGuide && workspace == null && apks.isNotEmpty) ...[
          const SizedBox(height: 16), Text('目录中的 APK', style: Theme.of(context).textTheme.titleMedium),
          ...apks.map((raw) { final item = Map<String,dynamic>.from(raw); return ListTile(contentPadding: EdgeInsets.zero, leading: Icon(Icons.android, color: colors.primary), title: Text(item['name']?.toString() ?? ''), subtitle: Text(item['path'].toString(), maxLines: 1, overflow: TextOverflow.ellipsis), trailing: const Icon(Icons.chevron_right), onTap: busy ? null : () => open(item['path'].toString())); }),
        ],
        if (!isGuide && workspace != null) ...[
          const SizedBox(height: 16), Card(child: Padding(padding: const EdgeInsets.all(16), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('已打开 APK 快照', style: Theme.of(context).textTheme.titleMedium), const SizedBox(height: 4), Text(workspace!['sourcePath'].toString()), const SizedBox(height: 12),
            Wrap(spacing: 8, runSpacing: 8, children: [for(final entry in {'zip_entries':'文件','manifest':'Manifest','dex_classes':'DEX 类','resource_table':'资源表'}.entries) OutlinedButton(onPressed: busy ? null : () => inspect(entry.key), child: Text(entry.value)),
              OutlinedButton(onPressed: busy || session != null ? null : createSession, child: const Text('创建编辑会话')),
              TextButton(onPressed: busy ? null : closeWorkspace, child: const Text('关闭并清理'))]),
            const SizedBox(height: 8), Text('修改通过编辑会话进行，源 APK 保持不变。', style: Theme.of(context).textTheme.bodySmall),
          ]))),
        ],
        if (result != null) ...[
          const SizedBox(height: 16), Row(children: [Expanded(child: Text('结果', style: Theme.of(context).textTheme.titleMedium)),
            IconButton(tooltip: '复制结果', onPressed: () async { await Clipboard.setData(ClipboardData(text: text)); if(mounted) message(context, '已复制'); }, icon: const Icon(Icons.copy_outlined))]),
          Container(padding: const EdgeInsets.all(14), decoration: BoxDecoration(color: colors.primary.withAlpha(12), borderRadius: BorderRadius.circular(16)), child: SelectableText(text, style: const TextStyle(fontFamily: 'monospace', fontSize: 12, height: 1.5))),
          if (isGuide && data['hasMore'] == true) TextButton(onPressed: busy ? null : () => utility('reverse_guide', {'topic': data['selectedSection'], 'offset': data['nextOffset']}), child: const Text('继续阅读')),
          if ((result?['data']?['nextCursor']?.toString() ?? '').isNotEmpty && workspace != null) TextButton(onPressed: busy ? null : nextPage, child: const Text('下一页')),
        ], const SizedBox(height: 24),
      ])));
  }
}
