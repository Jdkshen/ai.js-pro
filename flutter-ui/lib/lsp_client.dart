// Dart 侧 LSP 客户端：编辑器补全用的最小可用实现。
//
// 桥（LocalBridge）是请求/响应模型，没有服务端推送通道，所以 Java 侧用后台
// 线程把 node 的 stdout 按 Content-Length 分帧后入队，这里轮询 lspPoll 取整条
// JSON-RPC 报文。分帧全部在 Java 侧完成，跨桥的只有纯 JSON 字符串。
//
//   final lsp = LspClient();
//   await lsp.start();
//   final items = await lsp.complete(
//       uri: 'file:///data/user/0/<pkg>/files/main.js',
//       text: source, line: 3, character: 8);
//   await lsp.stop();
//
// 握手参数与 Auto.js 自带 Ace 客户端（assets/editor/ace-builds-1.4.12/autojs/
// lsp.js 里的 LspWsConnection.sendInitialize）保持一致：initializationOptions
// 与 processId 都是 null，rootUri 用 Java 侧给的 root；initialize 成功之后依次
// 发 initialized、workspace/didChangeConfiguration{settings:{}}。
import 'dart:async';
import 'dart:convert';

import 'bridge.dart';

/// [LocalBridge.call] 的形状；测试可以注入替身，生产用默认值。
typedef LspTransport = Future<dynamic> Function(
    String method, Map<String, dynamic> arguments);

/// textDocument/completion 返回的单个条目。
class LspCompletion {
  /// 补全列表里显示的文字。
  final String label;

  /// 服务器给的简短说明（通常是 TypeScript 签名）。
  final String? detail;

  /// 要插入的文本，来自 insertText 或 textEdit（newText，或
  /// InsertReplaceEdit 的 insert）。服务器两种都没给时为 null，此时请用
  /// [text]，它会回退到 [label]。
  final String? insertText;

  /// LSP 的 CompletionItemKind：1 Text、2 Method、3 Function、6 Variable、
  /// 7 Class、9 Module……本项目 UI 不强制使用。
  final int? kind;

  /// 文档说明（字符串或 MarkupContent，统一拍平成文本）。
  final String? documentation;

  const LspCompletion(
      {required this.label,
      this.detail,
      this.insertText,
      this.kind,
      this.documentation});

  /// 编辑器实际应该插入的内容。
  String get text => insertText ?? label;

  /// 解析单个条目；解析不出可用内容时返回 null（服务器偶尔夹带脏数据，
  /// 一个坏条目不该让整次补全失败）。
  static LspCompletion? fromJson(Object? raw) {
    if (raw is String) {
      return raw.isEmpty ? null : LspCompletion(label: raw);
    }
    if (raw is! Map) return null;
    final label = raw['label'];
    if (label is! String || label.isEmpty) return null;
    return LspCompletion(
        label: label,
        detail: raw['detail'] is String ? raw['detail'] as String : null,
        insertText: _insertTextOf(raw),
        kind: raw['kind'] is int ? raw['kind'] as int : null,
        documentation: _documentationOf(raw['documentation']));
  }

  @override
  String toString() => 'LspCompletion($label, kind: $kind)';

  static String? _insertTextOf(Map<dynamic, dynamic> item) {
    String? value;
    final edit = item['textEdit'];
    if (edit is Map) {
      if (edit['newText'] is String) {
        value = edit['newText'] as String;
      } else if (edit['insert'] is String) {
        // InsertReplaceEdit（LSP 3.16）。
        value = edit['insert'] as String;
      } else if (edit['replace'] is String) {
        value = edit['replace'] as String;
      }
    }
    if (value == null && item['insertText'] is String) {
      value = item['insertText'] as String;
    }
    if (value == null) return null;
    // 我们的 capabilities 里 snippetSupport=false，但服务器仍可能按
    // insertTextFormat=2 回包；不还原的话编辑器会原样插入 ${1:name} 这种片段。
    if (item['insertTextFormat'] == 2) value = _stripSnippet(value);
    return value;
  }

  static String _stripSnippet(String snippet) {
    var text = snippet.replaceAllMapped(
        RegExp(r'\$\{\d+:([^{}]*)\}'), (match) => match.group(1)!);
    text = text.replaceAll(RegExp(r'\$\{\d+\}'), '');
    text = text.replaceAll(RegExp(r'\$\d+'), '');
    text = text.replaceAll(r'\$', r'$');
    return text.replaceAll(r'\}', '}');
  }

  static String? _documentationOf(Object? raw) {
    if (raw is String) return raw.isEmpty ? null : raw;
    if (raw is Map && raw['value'] is String) {
      final value = raw['value'] as String;
      return value.isEmpty ? null : value;
    }
    return null;
  }
}

/// 通过 LocalBridge 跟 Java 侧的 node LSP 进程说话。
///
/// 所有等待都用 Future.delayed 轮询，不阻塞 UI 线程；[complete] 永远不抛异常
/// （失败/超时返回空列表）。同一 uri 的补全请求会排队串行执行。
class LspClient {
  LspClient({
    LspTransport? transport,
    this.pollInterval = const Duration(milliseconds: 50),
    this.pollTimeoutMs = 60,
    this.completionTimeout = const Duration(milliseconds: 2500),
    this.initializeTimeout = const Duration(seconds: 20),
    this.languageId = 'javascript',
  }) : _transport = transport ?? _defaultTransport;

  final LspTransport _transport;

  /// 默认走桥；包一层是为了让 LocalBridge.call 的可选参数签名能对上传送类型。
  static Future<dynamic> _defaultTransport(
          String method, Map<String, dynamic> arguments) =>
      LocalBridge.call(method, arguments);

  /// 两次 lspPoll 之间的等待时间。
  final Duration pollInterval;

  /// 单次 lspPoll 让 Java 侧等新消息的上限（毫秒）。
  final int pollTimeoutMs;

  /// 一次补全请求的总预算；超时返回空列表。
  final Duration completionTimeout;

  /// initialize 握手的预算（服务器首次启动要解压/索引，给宽一点）。
  final Duration initializeTimeout;

  /// didOpen 用的 languageId；Auto.js 脚本就是 javascript（TS 服务器）。
  final String languageId;

  /// 服务器主动发来的通知（publishDiagnostics、window/logMessage、$/progress
  /// …）以及超时之后才到的旧响应。只保留最近 64 条，供诊断使用。
  void Function(Map<String, dynamic> message)? onNotification;

  bool _running = false;
  int _nextId = 1;
  int _documentVersion = 0;
  int? _pid;
  String? _root;
  String? _rootUri;
  Map<String, dynamic>? _serverCapabilities;
  final Map<String, void Function(Map<String, dynamic>)> _waiting = {};
  final Map<String, Future<void>> _documentQueues = {};
  final List<Map<String, dynamic>> _incoming = [];
  final Map<String, int> _diagnosticsReceived = {};

  /// node 进程已启动且 initialize 握手完成（= 可以补全了）。
  bool get isRunning => _running;

  /// node 进程 pid，[start] 之后可用。
  int? get pid => _pid;

  /// Java 侧给的 LSP 工作根目录。
  String? get root => _root;

  /// 上面那个目录的 file:// 形式。
  String? get rootUri => _rootUri;

  /// initialize 响应里的 capabilities（含 completionProvider）。
  Map<String, dynamic>? get serverCapabilities => _serverCapabilities;

  /// 最近收到的、不是我们请求响应的报文（诊断用），最多 64 条。
  List<Map<String, dynamic>> get incoming =>
      List<Map<String, dynamic>>.unmodifiable(_incoming);

  /// 启动 node LSP 服务并完成 initialize 握手。
  ///
  /// 桥返回 ok=false、initialize 超时或返回 error 时抛 [StateError]，此时
  /// [isRunning] 仍为 false（可以再调 [start] 重试，或先 [stop] 清理）。
  Future<void> start() async {
    if (_running) return;
    final info = _decodeMap(await _transport('lspStart', <String, dynamic>{}));
    if (info != null && info['ok'] == false) {
      throw StateError(info['error']?.toString() ?? 'LSP 服务启动失败');
    }
    _pid = info?['pid'] is int ? info!['pid'] as int : null;
    final root = info?['root'];
    _root = root is String && root.isNotEmpty ? root : null;
    _rootUri = _root == null ? null : pathToUri(_root!);

    final id = _nextId++;
    await _send(<String, dynamic>{
      'jsonrpc': '2.0',
      'id': id,
      'method': 'initialize',
      'params': _initializeParams(),
    });
    final response = await _awaitResponse(id, initializeTimeout);
    if (response == null) {
      throw StateError('LSP initialize 超时（${initializeTimeout.inSeconds}s）');
    }
    if (response['error'] != null) {
      throw StateError('LSP initialize 失败: $response');
    }
    final result = response['result'];
    if (result is Map && result['capabilities'] is Map) {
      _serverCapabilities =
          Map<String, dynamic>.from(result['capabilities'] as Map);
    }
    await _send(<String, dynamic>{
      'jsonrpc': '2.0',
      'method': 'initialized',
      'params': <String, dynamic>{},
    });
    // 与 Auto.js 原生客户端一致：空 settings 也发一次，服务器据此建立项目。
    await _send(<String, dynamic>{
      'jsonrpc': '2.0',
      'method': 'workspace/didChangeConfiguration',
      'params': <String, dynamic>{'settings': <String, dynamic>{}},
    });
    _running = true;
  }

  /// 取 [uri] 文档里 [line]/[character]（都是 0-based，LSP 约定）处的补全。
  ///
  /// 每次调用都重新 didOpen（带全文）→ completion → didClose，不做增量同步；
  /// 拿不到匹配 id 的响应就等 [completionTimeout]，超时返回空列表。
  /// [triggerCharacter] 一般是 . ：传了就按 TriggerCharacter 上报。
  Future<List<LspCompletion>> complete({
    required String uri,
    required String text,
    required int line,
    required int character,
    String? triggerCharacter,
  }) async {
    if (!_running) return const <LspCompletion>[];
    // 同一个 uri 串行化：否则上一次的 didClose 会和这一次的 didOpen 穿插。
    final previous = _documentQueues[uri];
    final gate = Completer<void>();
    _documentQueues[uri] = gate.future;
    if (previous != null) {
      try {
        await previous;
      } catch (_) {
        // 上一次的结果与这一次无关。
      }
    }
    try {
      return await _completeOnce(uri, text, line, character, triggerCharacter);
    } finally {
      gate.complete();
      if (identical(_documentQueues[uri], gate.future)) {
        _documentQueues.remove(uri);
      }
    }
  }

  /// 停掉 node 进程并复位状态；不抛异常。
  Future<void> stop() async {
    _running = false;
    _serverCapabilities = null;
    _pid = null;
    _root = null;
    _rootUri = null;
    _waiting.clear();
    _diagnosticsReceived.clear();
    try {
      await _transport('lspStop', <String, dynamic>{});
    } catch (_) {
      // 进程可能已经退出，忽略。
    }
  }

  /// lspStatus 的原始结果（running/pid/queued/stderr），仅用于诊断。
  Future<Map<String, dynamic>?> status() async {
    try {
      return _decodeMap(await _transport('lspStatus', <String, dynamic>{}));
    } catch (_) {
      return null;
    }
  }

  /// lspSelfTest 的原始结果，仅用于真机诊断。
  Future<String> selfTest() async {
    try {
      final raw = await _transport('lspSelfTest', <String, dynamic>{});
      return raw?.toString() ?? '';
    } catch (error) {
      return 'lspSelfTest 失败: $error';
    }
  }

  /// 把文件路径转成 file:// URI（LSP 的 uri 一律用它）。
  ///
  /// 同时支持 Android 的 /data/... 和 Windows 的 C:\...，并把空格之类的字符
  /// 百分号编码。
  static String pathToUri(String path) {
    final normalized = path.replaceAll(r'\', '/');
    if (normalized.isEmpty) return '';
    final absolute = normalized.startsWith('/') ? normalized : '/$normalized';
    return 'file://${Uri(path: absolute)}';
  }

  Future<List<LspCompletion>> _completeOnce(String uri, String text, int line,
      int character, String? triggerCharacter) async {
    final version = ++_documentVersion;
    try {
      final beforeOpen = _diagnosticsReceived[uri] ?? 0;
      await _send(<String, dynamic>{
        'jsonrpc': '2.0',
        'method': 'textDocument/didOpen',
        'params': <String, dynamic>{
          'textDocument': <String, dynamic>{
            'uri': uri,
            'languageId': languageId,
            'version': version,
            'text': text,
          },
        },
      });
      // This bundled server handles didOpen asynchronously: it loads references,
      // updates the in-memory source, then publishes diagnostics. A completion
      // sent immediately can use the old disk text (or an invalid cursor offset).
      if (!await _awaitDiagnostics(uri, beforeOpen, completionTimeout)) {
        return const <LspCompletion>[];
      }
      final id = _nextId++;
      await _send(<String, dynamic>{
        'jsonrpc': '2.0',
        'id': id,
        'method': 'textDocument/completion',
        'params': <String, dynamic>{
          'textDocument': <String, dynamic>{'uri': uri},
          'position': <String, dynamic>{'line': line, 'character': character},
          'context': triggerCharacter == null
              ? <String, dynamic>{'triggerKind': 1} // Invoked
              : <String, dynamic>{
                  'triggerKind': 2, // TriggerCharacter
                  'triggerCharacter': triggerCharacter,
                },
        },
      });
      final response = await _awaitResponse(id, completionTimeout);
      return _completionsOf(response);
    } catch (_) {
      // 桥挂了 / 服务器出错：补全失败不该影响编辑。
      return const <LspCompletion>[];
    } finally {
      try {
        final beforeClose = _diagnosticsReceived[uri] ?? 0;
        await _send(<String, dynamic>{
          'jsonrpc': '2.0',
          'method': 'textDocument/didClose',
          'params': <String, dynamic>{
            'textDocument': <String, dynamic>{'uri': uri},
          },
        });
        // Consume the server's close notification before a later didOpen, so an
        // empty diagnostic from the previous close cannot acknowledge the new text.
        await _awaitDiagnostics(uri, beforeClose,
            completionTimeout < const Duration(seconds: 1)
                ? completionTimeout
                : const Duration(seconds: 1));
      } catch (_) {
        // 关不掉无所谓：Java 侧进程退出时会丢掉全部文档状态。
      }
    }
  }

  List<LspCompletion> _completionsOf(Map<String, dynamic>? response) {
    if (response == null || response['error'] != null) {
      return const <LspCompletion>[];
    }
    final result = response['result'];
    List<Object?>? items;
    if (result is List) {
      items = result;
    } else if (result is Map && result['items'] is List) {
      items = result['items'] as List;
    }
    if (items == null) return const <LspCompletion>[];
    final completions = <LspCompletion>[];
    for (final raw in items) {
      final item = LspCompletion.fromJson(raw);
      if (item != null) completions.add(item);
    }
    return completions;
  }

  Map<String, dynamic> _initializeParams() => <String, dynamic>{
        'processId': null,
        'initializationOptions': null,
        'rootUri': _rootUri,
        'workspaceFolders': _rootUri == null
            ? null
            : <Map<String, dynamic>>[
                <String, dynamic>{'uri': _rootUri, 'name': 'lsp'}
              ],
        'capabilities': <String, dynamic>{
          'workspace': <String, dynamic>{
            'workspaceFolders': true,
            'configuration': true,
            'didChangeConfiguration': <String, dynamic>{
              'dynamicRegistration': false,
            },
          },
          'textDocument': <String, dynamic>{
            'synchronization': <String, dynamic>{
              'dynamicRegistration': false,
              'willSave': false,
              'didSave': false,
              'willSaveWaitUntil': false,
            },
            'completion': <String, dynamic>{
              'dynamicRegistration': false,
              'contextSupport': false,
              'completionItem': <String, dynamic>{
                // 关键：不要 snippet，否则编辑器会插入 ${1:name} 这种片段。
                'snippetSupport': false,
                'commitCharactersSupport': true,
                'documentationFormat': <String>['plaintext', 'markdown'],
                'deprecatedSupport': false,
                'preselectSupport': false,
              },
            },
            'hover': <String, dynamic>{
              'dynamicRegistration': false,
              'contentFormat': <String>['plaintext', 'markdown'],
            },
            'signatureHelp': <String, dynamic>{
              'dynamicRegistration': false,
              'signatureInformation': <String, dynamic>{
                'documentationFormat': <String>['plaintext', 'markdown'],
              },
            },
            'definition': <String, dynamic>{
              'dynamicRegistration': false,
              'linkSupport': true,
            },
            'publishDiagnostics': <String, dynamic>{
              'relatedInformation': true,
            },
          },
          'window': <String, dynamic>{'workDoneProgress': false},
        },
      };

  Future<void> _send(Map<String, dynamic> message) async {
    await _transport('lspSend', <String, dynamic>{
      'message': jsonEncode(message),
    });
  }

  /// lspPoll 一次；同时兼容「JSON 字符串」和「已经解析好的 Map」两种回包。
  Future<List<Map<String, dynamic>>> _poll() async {
    final decoded = _decodeMap(await _transport(
        'lspPoll', <String, dynamic>{'timeoutMs': pollTimeoutMs}));
    final messages = decoded?['messages'];
    if (messages is! List) return const <Map<String, dynamic>>[];
    final parsed = <Map<String, dynamic>>[];
    for (final entry in messages) {
      final message = _decodeMap(entry);
      if (message != null) parsed.add(message);
    }
    return parsed;
  }

  /// 等 id 为 [id] 的响应；期间收到的通知/别人的响应都按类型分流。
  Future<Map<String, dynamic>?> _awaitResponse(int id, Duration timeout) async {
    final key = id.toString();
    Map<String, dynamic>? received;
    _waiting[key] = (message) => received = message;
    final watch = Stopwatch()..start();
    try {
      while (received == null &&
          _waiting.containsKey(key) &&
          watch.elapsed < timeout) {
        try {
          for (final message in await _poll()) {
            _dispatch(message);
          }
        } catch (_) {
          // 单次轮询失败（例如服务刚退出）不算致命：重试到超时为止。
        }
        if (received != null || !_waiting.containsKey(key)) break;
        final remaining = timeout - watch.elapsed;
        if (remaining <= Duration.zero) break;
        await Future<void>.delayed(
            remaining < pollInterval ? remaining : pollInterval);
      }
    } finally {
      _waiting.remove(key);
    }
    return received;
  }

  Future<bool> _awaitDiagnostics(String uri, int before, Duration timeout) async {
    final watch = Stopwatch()..start();
    while (_running && (_diagnosticsReceived[uri] ?? 0) <= before &&
        watch.elapsed < timeout) {
      for (final message in await _poll()) {
        _dispatch(message);
      }
      if ((_diagnosticsReceived[uri] ?? 0) > before) return true;
      final remaining = timeout - watch.elapsed;
      if (remaining <= Duration.zero) break;
      await Future<void>.delayed(
          remaining < pollInterval ? remaining : pollInterval);
    }
    return (_diagnosticsReceived[uri] ?? 0) > before;
  }

  void _dispatch(Map<String, dynamic> message) {
    final method = message['method'];
    final id = message['id'];
    if (method is String) {
      if (id == null) {
        // 通知：publishDiagnostics、window/logMessage、$/progress……
        if (method == 'textDocument/publishDiagnostics') {
          final params = message['params'];
          final uri = params is Map ? params['uri'] : null;
          if (uri is String) {
            _diagnosticsReceived[uri] = (_diagnosticsReceived[uri] ?? 0) + 1;
          }
        }
        _remember(message);
      } else {
        // 服务器反过来请求客户端（client/registerCapability、
        // workspace/configuration、window/workDoneProgress/create…）。
        // 必须回包，否则服务器会一直等我们而卡住后续流程。
        _answerServerRequest(id, method, message['params']);
        _remember(message);
      }
      return;
    }
    if (id == null) {
      _remember(message);
      return;
    }
    final waiter = _waiting.remove(id.toString());
    if (waiter == null) {
      // 已经超时的旧响应（或别人的响应）：留档，不打扰当前请求。
      _remember(message);
      return;
    }
    waiter(message);
  }

  void _answerServerRequest(Object id, String method, Object? params) {
    Object? result;
    if (method == 'workspace/configuration' &&
        params is Map &&
        params['items'] is List) {
      // 每个 item 一项；给 null 表示「没有额外配置」。
      result = List<Object?>.filled((params['items'] as List).length, null);
    }
    unawaited(_send(<String, dynamic>{
      'jsonrpc': '2.0',
      'id': id,
      'result': result,
    }).catchError((Object _) {}));
  }

  void _remember(Map<String, dynamic> message) {
    _incoming.add(message);
    if (_incoming.length > 64) {
      _incoming.removeRange(0, _incoming.length - 64);
    }
    final callback = onNotification;
    if (callback != null) {
      try {
        callback(message);
      } catch (_) {
        // 回调抛异常不能影响收报文。
      }
    }
  }

  static Map<String, dynamic>? _decodeMap(Object? raw) {
    try {
      if (raw is Map) return Map<String, dynamic>.from(raw);
      if (raw is String && raw.trim().isNotEmpty) {
        final decoded = jsonDecode(raw);
        if (decoded is Map) return Map<String, dynamic>.from(decoded);
      }
    } catch (_) {
      // 不是 JSON / key 不是字符串：按「没有数据」处理。
    }
    return null;
  }
}
