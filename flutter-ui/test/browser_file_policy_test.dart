import 'dart:async';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import '../lib/bridge.dart';
import '../lib/glass.dart';
import '../lib/main.dart';
import '../lib/workspace_models.dart';

Map<String, dynamic> entry(String name, {bool directory = false, bool project = false}) =>
    {'name': name, 'path': '/samples/$name', 'directory': directory, 'project': project, 'size': 12, 'modified': 0};

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test('capabilities keep resources out of execution and APK packaging', () {
    expect(scriptDirectoryTitle('/storage/emulated/0/custom'), '内部存储 · custom');
    expect(scriptDirectoryTitle('/storage/emulated/0'), '内部存储');
    for (final name in ['main.js', 'entry.MJS', 'capture.auto', 'layout.xml']) {
      expect(runnable(entry(name)), isTrue, reason: name);
    }
    for (final name in ['cover.jpg', 'plugin.apk', 'lib.so', 'code.dex', 'README.md', 'project.json']) {
      expect(runnable(entry(name)), isFalse, reason: name);
      expect(packable(entry(name)), isFalse, reason: name);
    }
    expect(editable(entry('capture.auto')), isFalse);
    expect(editable(entry('.gitignore')), isTrue);
    expect(editable(entry('header.hpp')), isTrue);
    expect(floatable(entry('layout.xml')), isFalse);
    expect(runnable(entry('folder.js', directory: true)), isFalse);
    expect(browserFileSymbol(entry('main.js')), 'J');
    expect(browserFileSymbol(entry('capture.auto')), 'R');
    expect(browserFileSymbol(entry('README')), '?');
  });

  test('hidden entries and categories remain independent from open capability', () {
    final entries = [entry('README.md'), entry('lib.so'), entry('.gitignore'),
      entry('folder', directory: true), entry('project', directory: true, project: true)];
    expect(browserFiles(entries, const BrowserOptions(), '').length, 4);
    expect(browserFiles(entries, const BrowserOptions(showHidden: true), '').length, 5);
    expect(browserFiles(entries, const BrowserOptions(categories: ['projects']), '').single['name'], 'project');
    expect(browserFiles(entries, const BrowserOptions(categories: ['files']), '').single['name'], 'lib.so');
    expect(browserFiles(entries, const BrowserOptions(categories: []), ''), isEmpty);
  });

  late List<String> calls;
  late List<Map<String, dynamic>> entries;
  setUp(() {
    calls = [];
    entries = [entry('demo.js'), entry('README.md'), entry('layout.xml'), entry('lib.so')];
    NativeEditorGate.release();
    GlassRefraction.preference = false;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockDecodedMessageHandler<String>(
      LocalBridge.channel, (message) async {
        final request = jsonDecode(message!) as Map;
        final method = request['method'] as String;
        calls.add(method);
        final dynamic data = method == 'list' ? entries : method == 'sampleLibrary' ? []
          : method == 'fileClipboard' ? {'paths': []} : method == 'browserPreferences' ? {} : true;
        return jsonEncode({'ok': true, 'data': data});
      });
  });
  tearDown(() {
    NativeEditorGate.release();
    GlassRefraction.reset();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockDecodedMessageHandler<String>(LocalBridge.channel, null);
  });

  Future<void> show(WidgetTester tester, {bool sample = true, String root = '/samples'}) async {
    await tester.pumpWidget(MaterialApp(home: Scaffold(body: FileBrowser(
      key: const ValueKey('files'), root: root, title: sample ? '示例' : '脚本', readOnly: sample, revision: 0))));
    await tester.pumpAndSettle();
  }

  testWidgets('sample row runs while edit button opens its source', (tester) async {
    await show(tester);
    await tester.tap(find.text('demo'));
    await tester.pumpAndSettle();
    expect(calls.where((x) => x == 'run').length, 1);
    expect(calls, isNot(contains('sampleAction')));
    await tester.tap(find.byTooltip('查看源码：demo.js'));
    await tester.pumpAndSettle();
    expect(calls, contains('sampleAction'));
    expect(find.byTooltip('运行：README.md'), findsNothing);
    expect(find.byTooltip('预览布局：layout.xml'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('resource menu omits build and script actions; text still edits', (tester) async {
    await show(tester);
    await tester.tap(find.byTooltip('更多操作：README.md'));
    await tester.pumpAndSettle();
    expect(find.text('打包'), findsNothing);
    expect(find.text('运行示例'), findsNothing);
    await tester.tap(find.text('查看示例'));
    await tester.pumpAndSettle();
    expect(calls, contains('sampleAction'));
    expect(calls, isNot(contains('run')));
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('script file row edits; binary resources use an external app', (tester) async {
    await show(tester, sample: false);
    await tester.tap(find.text('demo'));
    await tester.pumpAndSettle();
    expect(calls, contains('aceOpenFile'));
    expect(calls, isNot(contains('run')));
    await tester.tap(find.text('lib.so'));
    await tester.pumpAndSettle();
    expect(calls, contains('openWith'));
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('changing the root rejects an old in-flight directory response', (tester) async {
    final oldReply = Completer<List<dynamic>>();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockDecodedMessageHandler<String>(
      LocalBridge.channel, (message) async {
        final request = jsonDecode(message!) as Map;
        final method = request['method'];
        dynamic data = {};
        if (method == 'fileClipboard') data = {'paths': []};
        if (method == 'list') data = request['args']['path'] == '/old' ? await oldReply.future
          : [{'name': 'new.txt', 'path': '/new/new.txt', 'directory': false}];
        return jsonEncode({'ok': true, 'data': data});
      });
    await tester.pumpWidget(MaterialApp(home: Scaffold(body: FileBrowser(
      key: const ValueKey('files'), root: '/old', title: '脚本', revision: 0))));
    await tester.pump();
    await show(tester, sample: false, root: '/new');
    expect(find.text('new.txt'), findsOneWidget);
    oldReply.complete([{'name': 'old.txt', 'path': '/old/old.txt', 'directory': false}]);
    await tester.pumpAndSettle();
    expect(find.text('old.txt'), findsNothing);
    expect(find.text('new.txt'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('settings confirms a saved directory instead of accepting a stale getter', (tester) async {
    var root = '/old';
    var saved = false;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockDecodedMessageHandler<String>(
      LocalBridge.channel, (message) async {
        final request = jsonDecode(message!) as Map;
        dynamic data = {};
        switch (request['method']) {
          case 'runtimePreferences': data = {'stableMode': false, 'consoleMaxLogCount': 1000,
            'logMaxFileSize': 1024, 'logMaxBackups': 5, 'consoleInitialReadFileSize': 256}; break;
          case 'scriptsRoot':
            if (request['args']['path'] != null) { root = request['args']['path']; saved = true; }
            data = {'path': root, 'saved': saved}; break;
          case 'editorSettings': data = {'textSize': 14}; break;
          case 'updateSource': data = {'url': ''}; break;
        }
        return jsonEncode({'ok': true, 'data': data});
      });
    await tester.pumpWidget(MaterialApp(home: GeneralSettingsPage(root: root, onTheme: (_) {})));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('更改目录'));
    await tester.tap(find.text('更改目录'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), '/new');
    await tester.tap(find.text('保存'));
    await tester.pumpAndSettle();
    expect(saved, isTrue);
    expect(root, '/new');
    expect(find.text('/new'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
  });
}
